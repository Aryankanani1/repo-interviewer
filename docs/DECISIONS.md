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
