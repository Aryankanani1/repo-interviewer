---
paths:
  - "src/main/resources/db/migration/**"
  - "src/main/java/**/*Repository.java"
  - "src/main/java/**/store/**"
---

# Database rules

- Flyway owns the schema. `spring.jpa.hibernate.ddl-auto=validate`. Never edit an applied migration; add `V{n}__description.sql`.
- Ask Aryan before adding a migration. Explain each new index: which query it serves.
- `code_chunk` is accessed with `JdbcTemplate` only (batch inserts, `vector` columns, explicit SQL). Other tables use JPA.
- Vectors: `?::vector` casts, cosine distance `<=>`, always filter by `project_id`. The embedding dimension (768) is fixed by the schema.
- Concurrency is protected by constraints, not by hope: unique `(github_url, commit_sha)`, unique `answer.question_id`, partial unique `question(topic_id) WHERE is_follow_up = false`, `@Version` on `project` and `interview_session`. Handle `DataIntegrityViolationException` as documented in LLD §11.
- Keep transactions short. No network or LLM calls inside them.
