# Repo Interviewer

[![CI](https://github.com/Aryankanani1/repo-interviewer/actions/workflows/ci.yml/badge.svg)](https://github.com/Aryankanani1/repo-interviewer/actions/workflows/ci.yml)

Submit a GitHub repository you built. Repo Interviewer analyzes its architecture and runs a technical interview about **your actual code**: questions that cite real files and lines, follow-ups when an answer is shallow, rubric-based grading, and a report on what to review.

> Status: Phase 0 (setup) complete: project, local infrastructure, Flyway, module boundaries, configuration, CI. Phase 1 (GraphQL MVP) next. See `docs/ROADMAP.md`.

## Why
Interviews increasingly ask "walk me through your code" or let you use AI tools on a real codebase. Many candidates can build a project but cannot defend its design under questioning. This tool practices exactly that.

## Architecture (Phase 1)
Spring Boot 4 modular monolith · GraphQL API · PostgreSQL + pgvector · Spring AI (Claude for reasoning, local Ollama embeddings) · JGit + JavaParser for static analysis.
Evolves into Kafka-based pipelines (Phase 2), Kubernetes with lag-based autoscaling (Phase 3), and separate services with tracing and auth (Phase 4).

- High-level design: [`docs/HLD.md`](docs/HLD.md)
- Low-level design: [`docs/LLD.md`](docs/LLD.md)
- Decisions: [`docs/DECISIONS.md`](docs/DECISIONS.md)
- Roadmap: [`docs/ROADMAP.md`](docs/ROADMAP.md)
- Measured results: [`docs/RESULTS.md`](docs/RESULTS.md)

## Quick start (after Phase 0)
```bash
cp .env.example .env            # add your ANTHROPIC_API_KEY
docker compose up -d            # Postgres (pgvector) on localhost:5433
# Ollama: see "Local infrastructure" below
# Three apps since task 4.1, one terminal each:
./mvnw -pl analysis-service -am spring-boot:run    # projects and code, port 8082 (ANALYSIS_PORT)
./mvnw -pl interview-service -am spring-boot:run   # interviews and every model call for them, port 8084 (INTERVIEW_PORT)
./mvnw -pl graphql-gateway -am spring-boot:run     # the GraphQL API; GraphiQL: http://localhost:8080/graphiql
# An existing Postgres volume (from before task 4.1) needs each service's user and schema once:
docker compose exec -T postgres psql -U ri -d repo_interviewer < analysis-service/src/main/resources/db/bootstrap.sql
docker compose exec -T postgres psql -U ri -d repo_interviewer < interview-service/src/main/resources/db/bootstrap.sql
# The two services' internal REST APIs (task 4.2): http://localhost:8082/swagger-ui.html, http://localhost:8084/swagger-ui.html
```

## Environments
Code moves **dev → test → stage → prod**. `application.yml` holds the defaults every environment starts from; each environment's file overrides it ([`docs/DECISIONS.md`](docs/DECISIONS.md) D-015).

| Environment | File | How it starts |
|---|---|---|
| dev | `<service>/src/main/resources/application-dev.yml` | `./mvnw -pl <service> -am spring-boot:run` (local compose Postgres and Ollama; graphql-gateway on 8080 with GraphiQL, analysis-service on 8082, interview-service on 8084) |
| test | `<service>/src/test/resources/application-test.yml` | `./mvnw verify` locally and in CI (Testcontainers, fake models, `FakeAnalysisApi` and `FakeInterviewApi` instead of the other services) |
| stage | `<service>/src/main/resources/application-stage.yml` | `SPRING_PROFILES_ACTIVE=stage` + `ANALYSIS_BASE_URL` and `INTERVIEW_BASE_URL` (graphql-gateway, no database), or `DB_URL`, `DB_USER`, `DB_PASSWORD`, `ANTHROPIC_API_KEY` and `OLLAMA_BASE_URL` (analysis-service) or `ANALYSIS_BASE_URL` (interview-service) |
| prod | `<service>/src/main/resources/application-prod.yml` | `SPRING_PROFILES_ACTIVE=prod` + the same variables |

`<service>` is `graphql-gateway`, `analysis-service` or `interview-service` (task 4.1). Starting a jar without an environment stops with "No environment selected". Run another environment locally with `./mvnw -pl graphql-gateway -am spring-boot:run -Dspring-boot.run.profiles=stage`.

## Local infrastructure

**Postgres + pgvector** runs in Docker Compose on host port **5433** (5432 is often taken by a locally installed Postgres; change it with `DB_PORT` in `.env`).
```bash
docker compose up -d
docker compose ps        # postgres must show "(healthy)"
```

**Ollama** serves the embedding model `nomic-embed-text` (768 dimensions) on `localhost:11434`.

- **macOS (recommended): native.** Docker on a Mac cannot use the Apple GPU, and the Ollama image is 2.8 GB. The native install is about 55 MB and runs on the GPU (see `docs/DECISIONS.md` D-013).
  ```bash
  brew install ollama
  brew services start ollama          # background service, restarts at login
  ollama pull nomic-embed-text
  ollama list                         # must show nomic-embed-text:latest
  ```
- **Linux / no Homebrew: in Docker.**
  ```bash
  docker compose --profile ollama up -d                 # also pulls the model
  docker compose exec ollama ollama list                # must show nomic-embed-text:latest
  ```

Run only one of the two; both use port 11434. Tests need neither: they use Testcontainers for Postgres and a fake embedding model.

Example:
```graphql
mutation {
  submitProject(input: { githubUrl: "https://github.com/you/your-repo", goal: "E-commerce API" }) {
    project { id status }
    errors { code message }
  }
}
```

## Run as a container

One image per service (`Dockerfile`, tasks 3.1 and 4.1). Each builds its jar inside Docker, so no local JDK is needed, and runs as a non-root user (UID 10001).
```bash
for service in graphql-gateway analysis-service interview-service; do
  docker build --build-arg SERVICE=$service -t "${service}:local" .   # first build ~6 min, then 1.5-3 min each
done
```
The three run together on the local Kubernetes cluster below (each with its own database login, created by a Job), or as plain processes with the three Maven commands of the quick start. Settings come from the environment, as in the table above; the API key is never in an image. The JVM heap is 75% of the container's memory limit, and the apps only write to `/tmp`, so `--read-only` and `--cap-drop=ALL` work.

## Kubernetes (local)

A local [kind](https://kind.sigs.k8s.io/) cluster with Postgres, Kafka (Strimzi operator, KRaft), the three services and KEDA scaling them on Kafka lag: exact commands in [`k8s/README.md`](k8s/README.md) (tasks 3.2 to 3.4, one Deployment per service since task 4.1).

## Privacy
Analyzed code is sent to the Claude API. Only submit repositories you own or that are public. The tool never builds or runs submitted code.

## Working on this repo with Claude Code
Open this folder and run `claude`. It reads `CLAUDE.md` automatically.
- `/next-task`: plan and build the next roadmap task
- `/explain <thing>`: explain a part of the codebase for interview prep
- `/quiz-me`: get interviewed on your recent commits
