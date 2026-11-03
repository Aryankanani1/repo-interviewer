# Decision records

Short records of important choices: context, decision, and what we gave up. Add a new entry when a decision changes; mark the old one "Superseded by D-0XX". These are also interview answers.

---

## D-001 Modular monolith first, split into services in Phase 4
**Context:** The project must teach microservices, but splitting early multiplies setup work (several deployables, network calls, data ownership) before the product works.
**Decision:** One Spring Boot app with packages as modules and one-way dependencies enforced by ArchUnit. Slow work sits behind ports (`AnalysisPipeline`) so Phase 2 can swap the implementation.
**Trade-off:** Phase 1 cannot scale parts independently. That is accepted; the boundaries make the Phase 4 split mechanical instead of a rewrite.

## D-002 GraphQL for the client API, REST between services
**Context:** The client reads nested data (project → sessions → questions → answers) and needs push updates for slow work.
**Decision:** Schema-first GraphQL with Spring for GraphQL, `@BatchMapping` against N+1, payload `errors` for expected failures, subscriptions for progress. Internal service calls in Phase 4 use REST + OpenAPI because they are simple, cacheable, and easy to secure with timeouts and circuit breakers.
**Trade-off:** GraphQL adds N+1 and query-cost concerns that REST does not have. Mitigated with batch loading and (Phase 4) depth/complexity limits.

## D-003 Postgres + pgvector instead of a separate vector database
**Context:** We need relational data, JSON documents, and vector search. Scale is a few thousand vectors per project.
**Decision:** One Postgres with pgvector. Vectors live next to the rows they describe, in the same transactions and backups.
**Trade-off:** A dedicated vector DB has better ANN features at large scale. Not needed here, and fewer moving parts matter more.

## D-004 Own `code_chunk` table with JdbcTemplate instead of Spring AI's VectorStore
**Context:** Spring AI's `PgVectorStore` manages its own table with metadata in JSON.
**Decision:** We own the schema in Flyway (typed columns: path, symbol, lines) and write explicit SQL for batch inserts and similarity search.
**Trade-off:** More code than the VectorStore abstraction. In exchange: typed columns, exact-path lookups, line-range validation in one indexed query, and SQL we can explain in an interview.

## D-005 Local embeddings via Ollama (`nomic-embed-text`, 768-d)
**Context:** Embedding thousands of chunks per repo with a paid API costs money during development. Anthropic does not provide an embedding model.
**Decision:** Ollama running locally, accessed through Spring AI's `EmbeddingModel` interface, so a hosted provider can be swapped in by config (for example for a deployed demo).
**Trade-off:** Slower on a laptop CPU; the vector dimension is fixed in the schema, so changing models means a migration plus re-embedding.

## D-006 Repo map + retrieval instead of sending the whole repository
**Context:** A repo does not fit in one prompt, and sending it would be slow and expensive.
**Decision:** A token-budgeted repo map (structure and signatures) for summary/plan; method-level chunks retrieved per topic (exact path/symbol hits first, then vector search) for questions and grading.
**Trade-off:** The model never sees the whole codebase at once, so cross-cutting questions depend on plan quality. The eval harness measures it.

## D-007 Code decides; the LLM writes
**Context:** LLMs are inconsistent at arithmetic, thresholds, and following "only use these files" rules.
**Decision:** Verdict bands, follow-up rules, overall and category scores, ref validation, topic validation, and budgets are deterministic Java. The LLM produces language: summaries, questions, rubric points, feedback, report text.
**Trade-off:** More validation code; much more predictable and testable behavior.

## D-008 Synchronous LLM calls in Phase 1, Kafka in Phase 2
**Context:** Async messaging is a learning goal, but adding it before the flow works makes debugging harder.
**Decision:** Phase 1 runs interview calls inside the request and analysis on virtual threads. The GraphQL schema already allows async results (`Answer.evaluation` nullable, `interviewEvents` subscription), so Phase 2 does not break clients.
**Trade-off:** Phase 1 mutations can take 5–15 s. Acceptable for a single-user MVP; prefetching reduces it.

## D-009 Java-only analysis in the MVP
**Context:** Supporting many languages multiplies parsing and classification work.
**Decision:** Parse Java with JavaParser; read config/SQL/Markdown as text. Other languages are a non-goal for Phase 1.
**Trade-off:** Smaller audience. The chunker interface allows adding languages later (for example with tree-sitter).

## D-010 No authentication until Phase 4
**Context:** Phase 1 is a local single-user tool.
**Decision:** No auth in Phase 1. Phase 4 adds JWT (RS256), ownership checks, and rate limits at the gateway.
**Trade-off:** Must not be deployed publicly before Phase 4 (it would spend the API budget for anyone).

## D-011 Never execute repository code
**Context:** Submitted repos are untrusted.
**Decision:** Shallow clone, static parsing only, no builds, no scripts, no symlink following, size caps, temp directory deleted after analysis.
**Trade-off:** Cannot ask questions based on runtime behavior or test results. Fine for this product.

## D-012 Fake model in tests, real model only in opt-in evals
**Context:** Tests must be fast, free, and deterministic; quality still has to be measured.
**Decision:** `FakeLlmGateway` and `FakeEmbeddingModel` in all normal tests. Real calls only in `@Tag("eval")` tests run with `-Pevals`, which write results to `docs/RESULTS.md`.
**Trade-off:** Normal tests do not catch prompt regressions; the eval harness does.

## D-013 Native Ollama on macOS for local development
**Context:** D-005 runs embeddings in Ollama. The plan was to run it in Docker Compose. On an Apple Silicon Mac, Docker runs containers inside a Linux VM that cannot use the Apple GPU, so embeddings would run on CPU. The `ollama/ollama` arm64 image is also 2.8 GB compressed, which was a real problem on a nearly full disk.
**Decision:** On macOS, install Ollama with Homebrew (about 55 MB) and run it as a background service on `localhost:11434`. It runs `nomic-embed-text` on the GPU (`ollama ps` shows `100% GPU`). The Ollama container stays in `docker-compose.yml` behind the `ollama` profile for Linux and CI, and Kubernetes (Phase 3) still runs it as a container. The app config does not change: both listen on the same port.
**Trade-off:** One more thing installed outside Docker, so local setup is less "one command". Native and container versions can drift; the embedding model and its 768 dimensions are what matter, and both pull the same model.

## D-014 Time-ordered UUIDs generated in Java (UUIDv7)
**Context:** LLD §3.3 left a choice: database `gen_random_uuid()` or Hibernate `@UuidGenerator`. Random (v4) UUIDs land at random places in the primary-key B-tree, which spreads writes and splits pages as tables grow.
**Decision:** Every entity uses `@UuidGenerator(style = VERSION_7)`. The id is created in Java before the insert, so a new entity has its id immediately (useful for logs, MDC and outbox events), and v7 ids start with a timestamp, so new rows go to the right edge of the index like a sequence would.
**Trade-off:** Ids leak creation time (fine here). Rows inserted with plain SQL still get random v4 ids from the column default; both kinds work together.

## D-015 Environments: dev → test → stage → prod over one default file
**Context:** One `application.yml` with localhost fallbacks works on one laptop, but a deployment that forgot its settings would quietly start against those fallbacks. Stage and prod also need different settings (GraphiQL, the LLM budget).
**Decision:** `application.yml` is the default: shared settings, safe values (GraphiQL off) and no fallback for the database or Ollama. Each environment has a file on top of it: `application-dev.yml` (local compose Postgres and native Ollama, GraphiQL, DEBUG logs, 60 LLM calls a day since your own prepaid credits pay for them), `src/test/resources/application-test.yml` (automated tests; not packaged in the jar), `application-stage.yml` (production-like, GraphiQL on, 100 LLM calls a day) and `application-prod.yml` (GraphiQL and the schema report off). Stage and prod read their connections from environment variables. `./mvnw spring-boot:run` uses dev; the jar has no default, and `NoEnvironmentGuard` stops a start without an environment with a clear message. In Phase 3 an environment combines with a role profile, for example `prod,api`.
**Trade-off:** Every run of the jar must set `SPRING_PROFILES_ACTIVE`. A value can come from two files (default and environment) plus environment variables, so check all three when a setting surprises you.

## D-016 Kafka behind a `kafka` profile
**Context:** Phase 2 moves analysis and grading onto Kafka step by step (tasks 2.1–2.7). Until a step is moved, the Phase 1 code still does the work, and the 578 Phase 1 tests start the app without a broker. With `spring-boot-starter-kafka` on the classpath, declared topics make `KafkaAdmin` connect at startup.
**Decision:** Everything that needs a broker is `@Profile("kafka")`: first the topic declarations (task 2.1), later the Kafka pipeline (task 2.3 switches `AnalysisPipeline` with the same profile). `dev,kafka` runs locally with Kafka; without the profile the app runs as in Phase 1 and never connects. With the profile, `spring.kafka.admin.fail-fast` stops the startup when the broker is unreachable.
**Trade-off:** Two ways to run the app until Phase 2 is done, and both must keep working; tests that need Kafka use their own context (`@ActiveProfiles({"test", "kafka"})` plus a Kafka container), which starts a second Postgres container too.

## D-017 The outbox relay sends while it holds its row locks
**Context:** The project rule is "no network call inside a database transaction" (long LLM calls would hold connections). The relay must lock the rows it sends (`FOR UPDATE SKIP LOCKED`) so that two relays, one per app instance in Phase 3, never send the same row at the same time, and `SKIP LOCKED` only protects a row while it is locked.
**Decision:** A documented exception: one transaction locks a batch of outbox rows, sends them and waits for each ack, then sets `published_at`. Only outbox rows are locked, a local ack takes milliseconds, and the producer timeouts (`max.block.ms` and `request.timeout.ms` 5 s, `delivery.timeout.ms` 10 s) cap how long one send can keep the transaction open. The first failed send ends the batch.
**Trade-off:** With Kafka down, each poll holds one database connection for up to about 10 s. The alternative that keeps the rule, claiming rows with a lease (`claimed_by`, `claimed_until`) in one short transaction and marking them in another, needs two more columns and lease-expiry handling for crashed relays.

## D-018 Kafka stages store what the next stage needs and mark their event in their last transaction
**Context:** Task 2.3 runs the analysis stages as three Kafka consumers, which may run in different JVMs. Phase 1 handed the build files and README from PARSING to SUMMARIZING in memory. Delivery is at-least-once (the outbox relay and the consumer offsets can both send again), so a stage can see its event twice.
**Decision:** Ingest stores the summary inputs on `project` (V4: `build_files`, `readme`) rather than the summary stage rebuilding them from the chunks: they are already cut from redacted text by `SummarySources`, and reading them from the row gives both pipelines one code path. Each consumer marks its event in `processed_event` as the first step of the stage's last transaction, together with the state change and the next stage's outbox event; a duplicate rolls all three back. A cheap `isProcessed` check before the slow work skips plain redeliveries. Ingest starts only from SUBMITTED, so a redelivery after a crash mid-ingest does nothing (LLD §13.4).
**Trade-off:** A redelivery that arrives while the first delivery is still running (for example after a rebalance) repeats the slow work of embed or summarize before its claim is rejected: extra embedding calls or one more model call, never a duplicate row. A crash mid-ingest leaves the project in CLONING or PARSING until `StaleAnalysisRecovery` runs at the next app start.

## D-019 The consumer decides what is retried; the DLT is for broken messages and exhausted retries
**Context:** Task 2.4 adds retries with backoff and dead-letter topics. Spring Kafka's `DefaultErrorHandler` retries almost every exception by default, and the stages (task 2.3) caught every error and stored FAILED, so nothing reached it. `LlmUnavailableException` lives in `llm`, which `common` (where the error handler lives) may not import.
**Decision:** The handler retries only `RetryableEventException` (`common.messaging`) and sends everything else to `<topic>-dlt` on the first failure. A consumer throws it when it knows a later delivery can succeed: today only `summarize` for an unavailable model, in Kafka mode only (`WhenModelUnavailable.RETRY`; the in-process job still stores FAILED). Three retries (four deliveries, the AC's "retries 3 times"), backoff 1 s, 2 s, 4 s. Failures that would repeat with the same input (repository not found, no Java files, budget, `LlmOutputException`) stay a stored FAILED with no DLT: they are not broken messages, and the user can `reanalyzeProject`. A message the consumer cannot read (`InvalidEventException`) goes to the DLT instead of being skipped. When a record is dead-lettered, a `RetryListener` in `project` marks the project FAILED, so `common` stays unaware of projects and the user is not left waiting in SUMMARIZING.
**Trade-off:** Blocking retries hold the partition for up to about 7 s plus four model calls, so other projects on that partition wait. Kafka's non-blocking retry topics (`@RetryableTopic`) would not, but they need more topics and give up per-key order. A brief database outage is not retried yet: an event that fails in `isProcessed` goes straight to the DLT.

## D-020 Async answers: subscriptions over SSE, and a session FAILED when a worker gives up
**Context:** Task 2.5 moves grading and the next question out of `submitAnswer` into Kafka workers, so clients need a push channel and a way to learn that a step failed for good. Phase 1 kept the session IN_PROGRESS on a failed grade and let the client re-send; with no request waiting, nobody would see that error. Spring Boot 4.1.2 serves GraphQL subscriptions over Server-Sent Events at `/graphql` out of the box (`GraphQlSseHandler`, checked in the jar); WebSocket needs `spring-boot-starter-websocket`.
**Decision:** Subscriptions over SSE (POST `/graphql`, `Accept: text/event-stream`), no WebSocket endpoint: no new dependency, plain HTTP that curl, browsers (`fetch`) and any proxy or ingress (Phase 3) handle. When a worker gives up on an answer (the model still down after the retries, budget exceeded, an unusable reply), the session goes IN_PROGRESS → FAILED with the reason and a `SESSION_FAILED` event; the answer stays saved. The schema did not change: `SESSION_FAILED` and `InterviewSession.failureReason` already existed. Each worker does its work first and writes its outgoing event, worked out from the stored state, in a last transaction with the `processed_event` mark (LLD §13.5).
**Trade-off:** A FAILED session is a dead end: the candidate starts a new interview instead of re-sending (keeping it IN_PROGRESS would need a new event type with an error, a schema change). SSE is one-way and needs one HTTP request per stream, and GraphiQL's subscription tab, which uses WebSocket, does not work with it.
