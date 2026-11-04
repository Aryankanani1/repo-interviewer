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
./mvnw spring-boot:run          # dev environment; GraphiQL: http://localhost:8080/graphiql
```

## Environments
Code moves **dev → test → stage → prod**. `application.yml` holds the defaults every environment starts from; each environment's file overrides it ([`docs/DECISIONS.md`](docs/DECISIONS.md) D-015).

| Environment | File | How it starts |
|---|---|---|
| dev | `src/main/resources/application-dev.yml` | `./mvnw spring-boot:run` (local compose Postgres and Ollama, GraphiQL on) |
| test | `src/test/resources/application-test.yml` | `./mvnw verify` locally and in CI (Testcontainers, fake models) |
| stage | `src/main/resources/application-stage.yml` | `SPRING_PROFILES_ACTIVE=stage` + `DB_URL`, `DB_USER`, `DB_PASSWORD`, `OLLAMA_BASE_URL`, `ANTHROPIC_API_KEY` |
| prod | `src/main/resources/application-prod.yml` | `SPRING_PROFILES_ACTIVE=prod` + the same variables |

Starting the jar without an environment stops with "No environment selected". Run another environment locally with `./mvnw spring-boot:run -Dspring-boot.run.profiles=stage`.

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

One image for every environment (`Dockerfile`, task 3.1). It builds the jar inside Docker, so no local JDK is needed, and runs as a non-root user (UID 10001).
```bash
docker build -t repo-interviewer:local .      # first build ~6 min (downloads dependencies), then seconds
```
Run it against the Compose infrastructure (Postgres and Kafka up, Ollama native on the host):
```bash
docker compose up -d && docker compose --profile kafka up -d
docker run --rm --name ri-app --network repo-interviewer_default -p 8086:8080 --memory=1g \
  --read-only --tmpfs /tmp:rw,size=256m --cap-drop=ALL --security-opt=no-new-privileges \
  -e SPRING_PROFILES_ACTIVE=stage,kafka \
  -e DB_URL=jdbc:postgresql://postgres:5432/repo_interviewer \
  -e SPRING_KAFKA_BOOTSTRAP_SERVERS=kafka:29092 \
  -e OLLAMA_BASE_URL=http://host.docker.internal:11434 \
  --env-file .env \
  repo-interviewer:local
curl localhost:8086/actuator/health           # {"status":"UP"}
```
Inside the Compose network the app reaches `postgres:5432` and Kafka's internal listener `kafka:29092`; Ollama runs on the Mac itself, which a container reaches as `host.docker.internal`. `--env-file .env` passes `ANTHROPIC_API_KEY` (and `DB_USER`/`DB_PASSWORD`) at run time; the key is never in the image. The JVM heap is 75% of `--memory`. The `--read-only` and `--cap-drop` flags are optional hardening: the app only writes to `/tmp`.

## Privacy
Analyzed code is sent to the Claude API. Only submit repositories you own or that are public. The tool never builds or runs submitted code.

## Working on this repo with Claude Code
Open this folder and run `claude`. It reads `CLAUDE.md` automatically.
- `/next-task`: plan and build the next roadmap task
- `/explain <thing>`: explain a part of the codebase for interview prep
- `/quiz-me`: get interviewed on your recent commits
