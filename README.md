# Repo Interviewer

Submit a GitHub repository you built. Repo Interviewer analyzes its architecture and runs a technical interview about **your actual code**: questions that cite real files and lines, follow-ups when an answer is shallow, rubric-based grading, and a report on what to review.

> Status: design complete, Phase 0 (setup) next. See `docs/ROADMAP.md`.

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
docker compose up -d            # Postgres (pgvector) + Ollama, pulls nomic-embed-text
./mvnw spring-boot:run          # GraphiQL: http://localhost:8080/graphiql
```

Example:
```graphql
mutation {
  submitProject(input: { githubUrl: "https://github.com/you/your-repo", goal: "E-commerce API" }) {
    project { id status }
    errors { code message }
  }
}
```

## Privacy
Analyzed code is sent to the Claude API. Only submit repositories you own or that are public. The tool never builds or runs submitted code.

## Working on this repo with Claude Code
Open this folder and run `claude`. It reads `CLAUDE.md` automatically.
- `/next-task`: plan and build the next roadmap task
- `/explain <thing>`: explain a part of the codebase for interview prep
- `/quiz-me`: get interviewed on your recent commits
