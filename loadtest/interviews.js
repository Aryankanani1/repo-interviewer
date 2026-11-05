// Load test for task 3.4 (k6, https://k6.io): real interviews and analyses through the API, so the workers'
// Kafka consumer lag grows and KEDA scales them. Uses the real model: every call costs money and counts toward the
// daily cap (stage: 100). Run against a port-forward:
//   kubectl -n repo-interviewer port-forward svc/api 8086:8080
//   k6 run -e SESSIONS=7 loadtest/interviews.js
// with loadtest/sample-scaling.sh in another terminal to log the replicas.
import http from 'k6/http';
import { check, sleep } from 'k6';
import exec from 'k6/execution';

const API = __ENV.API || 'http://localhost:8086/graphql';
const SESSIONS = Number(__ENV.SESSIONS || 7);
const INTERVIEW_REPO = 'https://github.com/spring-projects/spring-petclinic';
const ANALYSIS_REPOS = ['gs-rest-service', 'gs-accessing-data-jpa', 'gs-securing-web', 'gs-scheduling-tasks',
  'gs-consuming-rest', 'gs-validating-form-input'].map((name) => `https://github.com/spring-guides/${name}`);
const START_SPACING_S = 8;   // the plan calls one after another, not 7 at once (the model's per-minute token limit)
const ANSWER = 'I would start from the class the question points at. It takes the request, validates the input '
  + 'and delegates to a repository; errors become a clear response. I am not sure about the edge cases.';

export const options = {
  scenarios: {
    interviews: {
      executor: 'per-vu-iterations', vus: SESSIONS, iterations: 1, maxDuration: '30m', exec: 'interview',
    },
    analyses: { executor: 'per-vu-iterations', vus: 1, iterations: 1, maxDuration: '30m', exec: 'analyses' },
  },
};

function gql(query, variables) {
  const res = http.post(API, JSON.stringify({ query, variables }),
    { headers: { 'Content-Type': 'application/json' }, timeout: '180s' });
  const body = res.json();
  if (res.status !== 200 || body.errors) {
    throw new Error(`HTTP ${res.status}: ${JSON.stringify(body.errors || body).slice(0, 300)}`);
  }
  return body.data;
}

function submit(url) {
  const p = gql(`mutation ($i: SubmitProjectInput!) { submitProject(input: $i) {
    project { id status } errors { code message } } }`,
  { i: { githubUrl: url, goal: 'Learn Spring Boot' } }).submitProject;
  if (p.errors.length) throw new Error(`submit ${url}: ${JSON.stringify(p.errors)}`);
  return p.project;
}

export function setup() {
  const project = submit(INTERVIEW_REPO);   // analyzed already: the same commit returns the stored project
  check(project, { 'interview project READY': (p) => p.status === 'READY' });
  // Every session answers its first question at this moment, so the answers queue up together.
  return { projectId: project.id, firstAnswersAt: Date.now() + (SESSIONS * START_SPACING_S + 60) * 1000 };
}

export function interview(data) {
  sleep((exec.vu.idInTest - 1) * START_SPACING_S);
  const started = gql(`mutation ($i: StartInterviewInput!) { startInterview(input: $i) {
    session { id } firstQuestion { id } errors { code message } } }`,
  { i: { projectId: data.projectId, questionCount: 3, difficulty: 'JUNIOR' } }).startInterview;
  if (!check(started, { 'interview started': (s) => s.errors.length === 0 })) return;
  const sessionId = started.session.id;
  let questionId = started.firstQuestion.id;
  sleep(Math.max(0, (data.firstAnswersAt - Date.now()) / 1000));

  for (let answers = 0; questionId; answers++) {
    const sent = gql(`mutation ($i: SubmitAnswerInput!) { submitAnswer(input: $i) { errors { code } } }`,
      { i: { questionId, text: ANSWER } }).submitAnswer;
    check(sent, { 'answer accepted': (s) => s.errors.length === 0 });
    for (;;) {   // graded and moved on, by the workers (Kafka)
      sleep(2);
      const s = gql(`query ($id: ID!) { interviewSession(id: $id) { status currentQuestion { id } } }`,
        { id: sessionId }).interviewSession;
      if (s.status !== 'IN_PROGRESS') {
        check(s, { 'interview COMPLETED': (x) => x.status === 'COMPLETED' });
        questionId = null;
        break;
      }
      if (s.currentQuestion && s.currentQuestion.id !== questionId) {
        questionId = s.currentQuestion.id;
        break;
      }
    }
  }
}

export function analyses() {
  const projects = ANALYSIS_REPOS.map(submit);   // all at once: the ingest and embed lag grows
  for (const project of projects) {
    for (;;) {
      const p = gql('query ($id: ID!) { project(id: $id) { status } }', { id: project.id }).project;
      if (p.status === 'READY' || p.status === 'FAILED') {
        check(p, { 'analysis READY': (x) => x.status === 'READY' });
        break;
      }
      sleep(3);
    }
  }
}
