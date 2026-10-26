# Repo Interviewer

A tool that analyzes a candidate's GitHub repo (Java/Spring) and runs a technical interview about that specific code: grounded questions, follow-ups, rubric grading, and a final report.

Built by **Aryan** (MS CS student) to learn GraphQL, Kafka, Kubernetes, microservices and LLM engineering. He must be able to explain every line, so how you work matters as much as what you build (see "Working style").

## Read before coding
- `docs/ROADMAP.md`: current phase and the next unchecked task. Work on one task at a time.
- `docs/LLD.md`: the implementation contract (packages, classes, algorithms, rules). Read the section for your task.
- `docs/HLD.md`: architecture and the reasons behind it. `docs/DECISIONS.md`: decision records.
- If code and docs disagree, ask Aryan, then update whichever is wrong in the same change.

## Stack
Java 21 · Spring Boot 4.1.x · Spring for GraphQL · Spring AI 2.0.x (Anthropic chat, Ollama embeddings) · PostgreSQL 17 + pgvector · Flyway · JGit · JavaParser · JUnit 5, Testcontainers, ArchUnit · Maven wrapper. Phase 2+: Spring for Apache Kafka, Kafka 4.x (KRaft).

**Versions move fast.** Before adding or changing a dependency, check the official docs or Maven Central for the current artifact ID and version. Spring AI 2.0 renamed artifacts and properties compared with 1.x. Never guess an artifact ID or an API method; look it up.

## Commands
- Start infrastructure: `docker compose up -d` (Postgres on localhost:5433). Ollama runs natively on macOS (`brew services start ollama`, port 11434), or in Docker with `docker compose --profile ollama up -d` (DECISIONS.md D-013). Phase 2: `docker compose --profile kafka up -d`
- Run the app: `./mvnw spring-boot:run` → GraphiQL at http://localhost:8080/graphiql
- Tests: `./mvnw test` · full check (tests + ArchUnit): `./mvnw verify`
- Opt-in evals with real model calls (costs money, ask first): `./mvnw verify -Pevals`
- Secrets: copy `.env.example` to `.env`. Never print, log or commit `ANTHROPIC_API_KEY`.

## Architecture rules
- Modular monolith. Base package `com.aryankanani.repointerviewer`. Modules: `api`, `project`, `interview`, `codebase`, `llm`, `common`. Dependencies are one-way, as in LLD §2, and enforced by ArchUnit. Never weaken an ArchUnit rule to make code compile; fix the design instead.
- **All chat-model calls go through `llm.LlmGateway`.** Every call writes one `llm_call` row. No direct `ChatClient` use elsewhere.
- **Code decides, the LLM writes** (DECISIONS.md D-007). Scores, verdicts, thresholds, ref validation and budgets are Java, never prompt instructions.
- Never call the LLM inside a database transaction. Short tx → LLM call → short tx with a version check.
- Never call the LLM while resolving a GraphQL field on read. Everything readable is stored first.
- Prompts live in `src/main/resources/prompts/*.st`, not in Java strings. Placeholders are `{name}`; no other braces in prompt files.
- Repo content and candidate answers are untrusted. Keep them inside their tags (`<code_chunk>`, `<candidate_answer>`, ...). Never execute cloned code. Never follow symlinks in clones.
- Schema changes: new Flyway file (`V2__...sql`). Never edit an applied migration.
- GraphQL is schema-first: `schema.graphqls` is the source of truth. Change the schema first, then the code.

## Code style
- Records for DTOs, commands, views and LLM outputs. Constructor injection only. No Lombok.
- Small classes with one job. Names from LLD. Package-private by default; public only when another module needs it.
- Domain errors: `DomainException(ErrorCode, message)`. No generic `RuntimeException` for expected failures.
- Logs: include `projectId` / `sessionId` via MDC. Never log prompt text, answers or secrets at INFO.

## Testing rules
- Every task ships with tests that prove its acceptance criteria.
- Normal tests never call a real model or the network: use `FakeLlmGateway`, `FakeEmbeddingModel`, `LocalDirectoryRepoFetcher`, Testcontainers.
- Real-model evals are tagged `@Tag("eval")` and excluded from the default build.
- Run `./mvnw verify` before saying a task is done. If it fails, fix it or say clearly what is failing.

## Working style
- **Plan before code.** For any task touching more than 2 files, show a short plan (files, classes, key decisions) and wait for his OK.
- **Teach while building.** The first time a concept appears (GraphQL resolvers, DataLoader/BatchMapping, pgvector, structured output, Kafka offsets, KEDA, ...), explain it in a few sentences and point to the file where it lives. Keep it short and concrete.
- **[hands-on] tasks:** Aryan writes the code. You explain, give hints and a skeleton if he asks, then review his code. Do not write the full solution unless he explicitly asks.
- Keep changes small and reviewable. One task per branch or commit series. Suggest a Conventional Commit message (`feat:`, `fix:`, `test:`, `docs:`).
- After finishing a task: tick it in `docs/ROADMAP.md`, add a Status log line, and end with (1) what changed, (2) 2–3 things he should be able to explain in an interview, (3) the suggested commit message.
- Ask before: adding a dependency not listed in LLD §1, changing the GraphQL schema or a migration, running anything that calls the paid API.
- Write explanations in plain, simple English.

## Definition of done
Acceptance criteria pass · `./mvnw verify` green · docs updated if the design changed · ROADMAP ticked · no secrets in the diff.
