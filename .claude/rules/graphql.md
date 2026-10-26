---
paths:
  - "src/main/resources/graphql/**"
  - "src/main/java/**/api/**"
  - "src/test/java/**/api/**"
---

# GraphQL rules

- `schema.graphqls` is the source of truth. Change the schema first, then the controllers.
- Mutations: one `input` argument, return a `...Payload` with `errors: [UserError!]!`. Expected failures go in `errors`; top-level GraphQL errors are for bugs only (code `INTERNAL`, with a correlation id, no stack trace).
- Every nested list or object field resolved from the database uses `@BatchMapping` (avoid N+1). Never lazy-load JPA associations in controllers (`open-in-view` is off).
- Controllers map entities to view records. `QuestionView` never includes the rubric. Ideal answers appear only on `AnswerEvaluation`.
- No LLM calls while resolving fields on read.
- Pagination uses `ScrollSubrange` in, `Window<T>` out.
- Tests: `@GraphQlTest` slices for controllers, `HttpGraphQlTester` for end-to-end. Assert on `errors[].code`, not on message text.
