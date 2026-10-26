# Roadmap

How to use this file:
- Work **one task at a time, top to bottom**. In Claude Code, run `/next-task`.
- A task is done only when its **acceptance criteria (AC)** pass and `./mvnw verify` is green.
- Tasks tagged **[hands-on]** are ones Aryan writes himself, with Claude Code giving hints and reviewing. These are the parts interviewers ask about most.
- When a task is done: tick the box, add one line to the Status log at the bottom.
- Dates are timeboxes, not promises. If a phase runs long, cut stretch tasks first.

**Current phase: 1**

---

## Phase 0 — Setup (Oct 26–27)

- [x] **0.1 Generate the project.** Maven, Java 21, Spring Boot 4.1.x, group `com.aryankanani`, artifact `repo-interviewer`, package `com.aryankanani.repointerviewer`. Dependencies: Web, Spring for GraphQL, Data JPA, Validation, Actuator, PostgreSQL driver, Flyway (+ `flyway-database-postgresql`), Testcontainers (Postgres, JUnit), Spring AI Anthropic chat starter, Spring AI Ollama starter, `javaparser-core`, JGit, `graphql-java-extended-scalars`, ArchUnit (`archunit-junit5`). Import the Spring AI 2.0.x BOM.
  - Verify every artifact ID and version on start.spring.io, the Spring AI 2.0 reference docs, and Maven Central. Do not guess. Record the chosen versions in `docs/LLD.md` §1.
  - Keep the existing `src/main/resources` files (schema, migration, prompts, application.yml).
  - AC: `./mvnw verify` passes with a context-load test that uses Testcontainers (`pgvector/pgvector:pg17`) and the `test` profile.
- [x] **0.2 Local infrastructure.** `docker compose up -d` starts Postgres. Ollama runs natively on macOS (Homebrew) or with `docker compose --profile ollama up -d`, and has `nomic-embed-text` (DECISIONS.md D-013).
  - AC: `docker compose ps` shows healthy Postgres; `ollama list` (or `docker compose exec ollama ollama list`) shows the model. Document the commands in README.
- [x] **0.3 Flyway V1.** The app starts against local Postgres and applies `V1__init.sql`; `ddl-auto=validate` passes once entities exist (entities come in later tasks, so for now check that the migration applies).
  - AC: an integration test asserts the `vector` extension and all V1 tables exist.
- [x] **0.4 Module skeleton + ArchUnit.** Create the packages from LLD §2 with a `package-info.java` each. Add ArchUnit rules from LLD §2.
  - AC: the ArchUnit test passes, and fails if you temporarily add an import from `interview` to `api` (try it once, then remove it).
- [x] **0.5 CI.** GitHub Actions workflow: checkout, Java 21 (Temurin), Maven cache, `./mvnw -B verify`. Testcontainers works on GitHub-hosted Ubuntu runners.
  - AC: green run on push; badge in README.
- [x] **0.6 Configuration properties.** `AppProperties` record tree for the `app.*` keys in `application.yml`, with `@Validated` constraints.
  - AC: unit test binds the YAML and checks a few values; an invalid value (for example `max-files: 0`) fails startup.

## Phase 1 — GraphQL MVP (Oct 28–Nov 9)

### A. GraphQL basics and projects
- [x] **1.1 [hands-on] First queries.** `Project` entity + repository, `ProjectController` with `project(id)` and `projects(first, after)` returning `Window<ProjectView>`. The `DateTime` scalar is already registered (`api/scalar/ScalarConfig`, task 0.1); add a test that `createdAt` serializes as an ISO-8601 string. GraphiQL at `/graphiql`.
  - Claude Code: explain schema-first mapping, `@QueryMapping`, `@Argument`, and how `Window`/`ScrollSubrange` become a Connection. Let Aryan write the controller.
  - AC: `GraphQlTester` tests for found, not found (null), and pagination across 3 pages.
- [ ] **1.2 `submitProject`.** `GithubUrl` value object (LLD §6.2), `RepoFetcher.resolveHead`, dedupe by `(url, sha)`, insert `SUBMITTED`, start the pipeline after commit. Payload `errors` pattern.
  - AC: tests for an invalid URL (`INVALID_URL` with `field`), a duplicate submit returning the same project id, and a missing repo (`REPO_NOT_FOUND`, using a mocked fetcher).

### B. Ingestion
- [ ] **1.3 RepoFetcher.** `JGitRepoFetcher` (shallow clone, timeout, size check, cleanup) and `LocalDirectoryRepoFetcher`. Create the fixture repo under `src/test/resources/fixtures/sample-repo/` (LLD §12), including one fake secret.
  - AC: the fixture copy works in tests; one manual run clones a real public repo (note the command in the Status log). No network in automated tests.
- [ ] **1.4 FileScanner + FileClassifier.** Rules from LLD §6.5.
  - AC: unit tests on the fixture: expected files included, secret files and `target/` skipped, symlinks skipped, roles correct.
- [ ] **1.5 Chunkers + SecretRedactor.** `JavaChunker`, `TextChunker`, `SecretRedactor` (LLD §6.6–6.7).
  - AC: tests for exact line ranges of a method chunk, the type-header chunk listing signatures, small-method merging, long-method splitting, the parse-failure fallback, and redaction of each secret pattern (plus one false-positive check).
- [ ] **1.6 EmbeddingService + ChunkStore.** JDBC batch insert, embedding in batches, similarity search with `project_id` filter. `FakeEmbeddingModel` for tests.
  - AC: Testcontainers test: insert fixture chunks, embed with the fake model, and a query for "refund" returns the refund method chunk first.
- [ ] **1.7 RepoMapBuilder.** Token budget and priority order (LLD §6.9).
  - AC: unit tests: output stays under budget; with a tiny budget, TEST and DTO entries are collapsed before CONTROLLER/SERVICE.
- [ ] **1.8 LLM gateway.** `LlmGateway`, `SpringAiLlmGateway`, `PromptTemplates` (prompt_version hash), `LlmBudget`, `LlmCallLedger`, `FakeLlmGateway`, output records (LLD §8).
  - Claude Code: confirm the Spring AI 2.0 ChatClient structured-output and usage-metadata APIs in the docs before writing code.
  - AC: unit tests for template rendering (no unresolved `{...}` left), budget exhaustion (`BUDGET_EXCEEDED`), and ledger rows written on both success and failure. One documented manual smoke call to the real API.
- [ ] **1.9 Analysis pipeline end to end.** `InProcessAnalysisPipeline`, `AnalysisJob` with status transitions, `SummaryValidator`, `StaleAnalysisRecovery`.
  - AC: an integration test with fakes takes the fixture repo from `SUBMITTED` to `READY` with stats, chunks, embeddings and summary saved. A forced failure in EMBEDDING leaves `FAILED` with a readable reason. A stale `PARSING` project becomes `FAILED` on startup.

### C. Interview loop
- [ ] **1.10 `startInterview`.** `QuestionPlanner`, `TopicValidator`, `Retriever`, `QuestionGenerator`, `CodeRefValidator` (LLD §9.1, §9.3, §7).
  - AC: tests with `FakeLlmGateway`: happy path returns question 1 with valid refs; a plan with invented paths drops those topics; a question with invalid refs retries once and then skips the topic; `PROJECT_NOT_READY` and `INVALID_QUESTION_COUNT` errors.
- [ ] **1.11 `submitAnswer`.** Validation, idempotent resume, evaluation, `Scoring.verdict`, `FollowUpPolicy`, next question, completion (LLD §9.2, §9.5).
  - AC: state tests: at most one follow-up per topic; a score outside 3..7 never triggers a follow-up; the session completes after the last topic; re-sending the same answer returns the same result with no extra LLM call; a different text returns `ALREADY_ANSWERED`.
- [ ] **1.12 Report + `endInterview`.** `ReportWriter`, scores computed in code (LLD §9.5–9.6).
  - AC: unit tests for weighted overall score and category averages; integration test checks the report exists after completion and after an early `endInterview`.
- [ ] **1.13 [hands-on] Fix N+1 with `@BatchMapping`.** `Project.sessions`, `InterviewSession.project/questions`, `Question.answer/parent`, `CodeRef.snippet`.
  - Claude Code: first show the N+1 problem with SQL logging (one query per question), then guide Aryan to fix it.
  - AC: a test (or logged count in the Status log) shows a fixed number of SQL queries for a session with 8 questions.
- [ ] **1.14 GraphQL error handling.** `DataFetcherExceptionResolverAdapter` for unexpected errors (correlation id, `INTERNAL`), domain errors to payload `errors`, Bean Validation on inputs.
  - AC: tests for each path; no stack trace or prompt text in any response.

### D. Quality and first real numbers
- [ ] **1.15 First real run.** Analyze Aryan's e-commerce repo and run a full interview in GraphiQL. Save the transcript in `docs/RESULTS.md` along with analysis time, per-call latency, and tokens (SQL over `llm_call`).
  - AC: RESULTS.md has real numbers and 3 notes on question quality (good and bad examples).
- [ ] **1.16 Prefetch next question.** `QuestionPrefetcher` (LLD §9.4), `app.interview.prefetch-enabled=true`.
  - AC: measured p50/p95 next-question latency before and after, in RESULTS.md.
- [ ] **1.17 Eval harness v1.** `@Tag("eval")` tests excluded from the default build, run with `./mvnw verify -Pevals`. Metrics from LLD §12 on one repo.
  - AC: a results table in RESULTS.md. Prompts changed because of it are noted in the Status log.
- [ ] **1.18 (stretch) Analysis progress subscription.** `projectAnalysisProgress` over WebSocket at `/graphql-ws`, fed by in-process events.
  - AC: a test subscribes and receives `CLONING → ... → READY` for the fixture repo.

## Phase 2 — Kafka (Nov 10–16)

- [ ] **2.1 Kafka locally.** `docker compose --profile kafka up -d`; Spring for Apache Kafka config; topics as `NewTopic` beans (LLD §13.3).
  - AC: topics visible in Kafka UI at http://localhost:8081.
- [ ] **2.2 Outbox.** `V2__outbox.sql` (LLD §13.1), `OutboxWriter`, `OutboxRelay` with `FOR UPDATE SKIP LOCKED`, event envelope.
  - AC: integration test (Testcontainers Kafka): writing state + outbox in one transaction publishes exactly one event; a rolled-back transaction publishes none.
- [ ] **2.3 [hands-on] Kafka analysis pipeline.** `KafkaAnalysisPipeline` plus three stage consumers (ingest, embed, summarize), idempotent via `processed_event`. Profile switch between in-process and Kafka.
  - Claude Code: explain consumer groups, partitions and keys, offsets and commit timing, and at-least-once delivery before Aryan writes the first consumer.
  - AC: the fixture repo reaches READY through Kafka; delivering the same event twice does not duplicate chunks.
- [ ] **2.4 Retries and DLT.** `DefaultErrorHandler` with backoff, `DeadLetterPublishingRecoverer`, retryable vs non-retryable classification.
  - AC: a forced `LlmUnavailableException` retries 3 times then lands on the DLT; a validation error goes straight to the DLT.
- [ ] **2.5 Async answer evaluation.** `submitAnswer` returns `evaluation: null`; evaluation worker + orchestrator; `interviewEvents` subscription.
  - AC: a subscription test receives `ANSWER_EVALUATED` and then `QUESTION_READY`; Phase 1 clients that poll `interviewSession` still work.
- [ ] **2.6 Subscription fan-out across instances.** Unique consumer group per API instance.
  - AC: two app instances locally; the client connected to instance B gets events for work done by a worker on instance A.
- [ ] **2.7 Failure test.** Kill a worker mid-evaluation and restart it.
  - AC: the answer is evaluated exactly once (one `llm_call` EVALUATION row that succeeded, one evaluation stored).
- [ ] **2.8 (stretch) Replay.** Re-run summaries for all projects with a new prompt version by publishing events.
  - AC: the summaries' `prompt_version` changes; a note in RESULTS.md on what changed.

## Phase 3 — Kubernetes (Nov 17–23)

- [ ] **3.1 Container image.** `./mvnw spring-boot:build-image` (or a layered Dockerfile); JVM memory settings for containers.
  - AC: the image runs with `docker run` against compose infrastructure.
- [ ] **3.2 Cluster + infrastructure.** `kind` cluster; Kustomize base with Postgres (StatefulSet + PVC), Ollama (Deployment + PVC), Kafka via the Strimzi operator (KRaft).
  - AC: `kubectl get pods` all Ready; README section with exact commands.
- [ ] **3.3 [hands-on] App deployments.** One image, three Spring profiles: `api`, `analysis-worker`, `interview-worker`. ConfigMap + Secret, liveness/readiness probes from Actuator, resource requests/limits, graceful shutdown.
  - AC: a full interview works through `kubectl port-forward` to the API.
- [ ] **3.4 Autoscaling with KEDA.** `ScaledObject` on Kafka consumer lag for the embedding and evaluation workers (min 1, max 5). k6 load test.
  - AC: a screenshot or log in RESULTS.md shows replicas scaling up under load and back down.
- [ ] **3.5 Docs.** `k8s/README.md` with a diagram and commands.

## Phase 4 — Microservices and distributed systems (Nov 24–Dec 7)

- [ ] **4.1 Split services.** Maven multi-module: `graphql-gateway` (BFF), `analysis-service`, `interview-service`, `events` (shared event records). Separate DB schemas; no cross-schema access.
  - AC: ArchUnit/Maven enforcement that services do not depend on each other's code except `events`.
- [ ] **4.2 Internal REST APIs.** analysis-service: `GET /internal/projects/{id}/context`, `POST /internal/projects/{id}/chunks/search`. OpenAPI docs. Resilience4j timeouts, retries and circuit breaker on callers.
  - AC: with analysis-service stopped, interview-service fails fast with a clear error instead of hanging.
- [ ] **4.3 Observability.** OpenTelemetry traces across GraphQL → REST → Kafka headers → workers → LLM; Prometheus metrics (LLM latency, tokens, calls per day, consumer lag); Grafana dashboard JSON committed.
  - AC: one trace screenshot covering a full `submitAnswer`, in RESULTS.md.
- [ ] **4.4 Auth and rate limits.** JWT (RS256) at the gateway, project/session ownership, token-bucket limits on `submitProject` and `startInterview`, `deleteProject` mutation.
  - AC: tests for 401, 403 (another user's session), and 429.
- [ ] **4.5 Prompt caching.** Enable Anthropic prompt caching for the repeated repo-context block (check the Spring AI docs for the cache strategy options); record `cached_tokens`.
  - AC: before/after input-token cost and latency per session in RESULTS.md.
- [ ] **4.6 (stretch) GraphQL hardening.** Max depth and complexity limits; persisted queries.

## Phase 5 — Evals, demo, resume (Dec 8–14)

- [ ] **5.1 Eval harness v2.** Four golden repos: e-commerce, movie recommender, TaskFlow, `spring-projects/spring-petclinic`. All metrics from LLD §12.
  - AC: RESULTS.md table per repo; one paragraph on what improved since v1.
- [ ] **5.2 Minimal web UI.** One static page served by the app: submit repo, watch progress, answer questions with code snippets, read the report.
- [ ] **5.3 README.** Architecture diagram, 2-minute demo video or GIF, real metrics, how to run.
- [ ] **5.4 Resume bullets.** Written from RESULTS.md numbers only. No invented metrics.

---

## Status log

| Date | Task | Note |
|---|---|---|
| 2026-10-25 | — | Design docs, schema, migration, prompts and Claude Code setup created. |
| 2026-10-26 | 0.1 | Boot 4.1.2, Spring AI 2.0.2 (versions in LLD §1.1). Context-load test green on Testcontainers `pgvector/pgvector:pg17` (PostgreSQL 17.11, V1 applied). Fixed model IDs to `claude-sonnet-5` / `claude-haiku-4-5`. `ScalarConfig` moved forward from 1.1: the app cannot start while `scalar DateTime` has no implementation. |
| 2026-10-26 | 0.2 | Compose Postgres healthy on host port 5433 (5432 is used by a system PostgreSQL 18), pgvector 0.8.7 available. Ollama 0.40.8 native via Homebrew (D-013): `nomic-embed-text` returns 768-d vectors, `ollama ps` shows 100% GPU. Ollama container moved behind the `ollama` compose profile. |
| 2026-10-26 | 0.3 | `./mvnw spring-boot:run` applied V1 to compose Postgres (localhost:5433) in 0.29 s, started in 14 s, health UP, GraphiQL 200. Starts without `ANTHROPIC_API_KEY` (checked only on first call). `FlywayV1MigrationTest`: vector extension, all 8 V1 tables, Flyway v1 success, `embedding` is `vector(768)`. Shares the cached test context: 1 container for the whole suite. |
| 2026-10-26 | 0.4 | 6 module packages with `package-info.java`. `ArchitectureTest`: 5 rules (module directions, Spring AI chat only in `llm`, embedding only in `codebase.embed`, GraphQL only in `api`, entities only in own module + `api`). Checked once with temporary violations: `interview → api` failed the module rule; `interview` using a `project` entity failed the entity rule. Both removed. LLD §2 entity wording updated. |
| 2026-10-26 | 0.6 | `AppProperties` record tree (8 sections), `@Validated`, strict keys, 3 cross-field checks. `AppPropertiesTest` (ApplicationContextRunner, no Docker, ~2 s): binds application.yml; `max-files=0`, `min-question-count=6` and a misspelled key each fail. Real app with `--app.repo.max-files=0` stops with "Property: app.repo.maxFiles, Reason: must be greater than 0". Done before 0.5 (needs the GitHub repo). |
| 2026-10-26 | 0.5 | Public repo github.com/Aryankanani1/repo-interviewer. Personal notes trimmed from CLAUDE.md and HLD in all commits before the first push. `.github/workflows/ci.yml` (checkout v7, setup-java v6, Temurin 21, Maven cache). First run green in 3 min: 14 tests, Testcontainers pgvector on the Ubuntu runner, `verify` 41 s. Phase 0 complete. |
| 2026-10-26 | 1.1 | `Project` entity (UUIDv7 ids, D-014), `ProjectRepository` keyset method sorted `created_at DESC, id DESC`, `ProjectService`, `ProjectView`, `ProjectController`. `ProjectQueriesTest` written first (found, not found, ISO-8601 `createdAt`, 3 pages with a `created_at` tie on the page boundary). SQL check: page 1 has no WHERE; later pages use `WHERE created_at < ? OR (created_at = ? AND id < ?)`, no OFFSET. Shared `@IntegrationTest` annotation: 1 context and 1 container for all 18 tests. `GraphQlTester` instead of `HttpGraphQlTester` (no webflux dependency). |
