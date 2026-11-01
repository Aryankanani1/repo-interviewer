# Results (measured, not estimated)

Every number on the resume comes from this file. Fill it in as tasks are completed. Include the date, the commit, and how each number was measured.

## Analysis performance (task 1.15)

| Repo | Files | Chunks | Clone | Parse | Embed | Summarize | Total | Date / commit |
|---|---|---|---|---|---|---|---|---|
| shopping-cart-spring-security @ `0d2ffc0` | 400 (355 Java, 33 139 lines) | 2 102 | 2.1 s | 4.8 s | 132.0 s | 20.0 s | 159.0 s | 2026-10-31 / `39f98ec` |

Measured from the app log (stage start/end lines, `Analysis READY in 159031 ms`), dev environment on an Apple-silicon Mac, Ollama native. Embedding is 83% of the total: 66 batches of 32 at about 2.0 s each. The clone was 2.1 MB.

## Interview latency and cost (tasks 1.15, 1.16, 4.5)

| Setup | p50 next question | p95 next question | Input tokens / session | Output tokens / session | Cached tokens | Date / commit |
|---|---|---|---|---|---|---|
| Phase 1, no prefetch | 34.9 s | 41.5 s | 120 873 | 21 731 | — | 2026-10-31 / `39f98ec` |
| Phase 1, prefetch | 18.1 s | 26.8 s | 95 024 | 20 414 | — | 2026-10-31 / `36ddfce` + task 1.16 |
| Phase 4, prompt caching | | | | | | |

Useful query:

```sql
SELECT purpose, model, count(*) AS calls,
       percentile_cont(0.5) WITHIN GROUP (ORDER BY latency_ms) AS p50_ms,
       percentile_cont(0.95) WITHIN GROUP (ORDER BY latency_ms) AS p95_ms,
       sum(input_tokens) AS input_tokens, sum(output_tokens) AS output_tokens,
       avg(CASE WHEN success THEN 1 ELSE 0 END) AS success_rate
FROM llm_call
WHERE created_at > now() - interval '1 day'
GROUP BY purpose, model
ORDER BY purpose;
```

How the Phase 1 row was measured (task 1.15, one JUNIOR interview, 5 topics, 6 questions):
- **Next question** = time from sending an answer to getting the next question: grading plus generating the question. n = 5: 25.1, 28.2, 34.9, 35.6 and about 43 s (the last one includes a retry after the output cap; the client got a 503 at 30 s, see problem 2). p95 by `percentile_cont`, so with n = 5 it is close to the slowest run.
- **First question** (`startInterview`: plan + question) took 29 s. The report after the last answer took 34.4 s (grading + report).
- **Tokens per session**: the 17 calls with this `session_id` (summary not included), 3 failed calls included. The failed calls (problem 1) used 17 645 input and 3 900 output tokens: 15% and 18% of the session.

How the prefetch row was measured (task 1.16, same repo and commit, no re-analysis, one JUNIOR interview, 4 topics, 7 questions, answers pasted by Aryan after at least 20 s per question):
- **Next question** = `answer.submitted_at` → next question's `asked_at` (client times matched within 0.3 s). n = 6, three of each kind:
  - **Main question, prefetched**: 15.9, 17.2 and 13.8 s, p50 15.9 s, p95 17.1 s. Each equals its grading time (15.8, 16.7, 13.8 s): the question was already `PREPARED`, so only the grade was waited for. Every prefetch was saved 15–26 s after the previous main question was asked (19.4, 14.9, 26.3 s), minutes before the answer it was waiting for.
  - **Follow-up** (score 4, 7, 4: a follow-up cannot be prefetched, it depends on the answer): 19.0, 25.5 and 27.2 s, p50 25.5 s, p95 27.1 s, grading plus FOLLOW_UP.
  - The mix matters: 1.15 had one follow-up in five steps, this run three in six. Both runs are n ≤ 6.
- **Calls**: 16, none failed. QUESTION 4 = 1 in `startInterview` + 3 prefetched, none wasted (no `PREPARED` question left unused). EVALUATION 7, FOLLOW_UP 3, PLAN 1, REPORT 1. Latency per call like 1.15 (EVALUATION p50 14.2 s, QUESTION 18.3 s, FOLLOW_UP 8.8 s), largest output 1 942 tokens (QUESTION), below every cap.
- **What is left**: the HLD target (p95 < 12 s; < 6 s) needs grading out of the request: async evaluation, task 2.5. The report after the last answer took 29.4 s (grading + report).

Per call (`llm_call` for this project, same day):

| Purpose | Model | Calls | Failed | p50 | p95 | Max output tokens | Input tokens | Output tokens |
|---|---|---|---|---|---|---|---|---|
| SUMMARY | claude-haiku-4-5 | 1 | 0 | 19.8 s | 19.8 s | 1 430 | 17 301 | 1 430 |
| PLAN | claude-sonnet-5 | 1 | 0 | 14.2 s | 14.2 s | 1 425 | 21 797 | 1 425 |
| QUESTION | claude-sonnet-5 | 6 | 1 | 17.2 s | 21.0 s | 2 060 | 61 620 | 9 233 |
| EVALUATION | claude-sonnet-5 | 8 | 2 | 14.0 s | 16.3 s | 1 571 | 31 144 | 9 247 |
| FOLLOW_UP | claude-sonnet-5 | 1 | 0 | 8.9 s | 8.9 s | 751 | 2 957 | 751 |
| REPORT | claude-haiku-4-5 | 1 | 0 | 16.9 s | 16.9 s | 1 075 | 3 355 | 1 075 |

### Problems found in the first real run (2026-10-31)
1. **Output caps too low for Sonnet 5.** Three calls stopped exactly at their cap and returned cut-off JSON (`output_invalid`): one QUESTION at 1 500 and two EVALUATIONs at 1 200 (both attempts, so `submitAnswer` returned `LLM_UNAVAILABLE`). With the caps raised to 3 000 (command-line override for the rest of the run), the largest outputs were QUESTION 2 060 and EVALUATION 1 571. PLAN (1 425 of 2 000) and SUMMARY (1 430 of 2 000) are also close.
2. **`submitAnswer` is longer than the 30 s request limit.** Grading plus the next question took 25–43 s. Tomcat's default async timeout is 30 s, so the client got HTTP 503 while the server finished the work. Re-sending the same answer returned the stored result in 0.3 s with no new model call (the resume path from task 1.11). `spring.mvc.async.request-timeout=120s` fixed it: a 35.6 s request then returned normally. Prefetch (1.16) and async evaluation (2.5) are the real fixes.
3. **Code refs with no snippet** (fixed 2026-11-01: snippets now come from the stored, redacted file, `V2__code_file_content.sql`; projects analyzed before that keep the old chunk-based snippets). `CodeRef.snippet` is null when the cited lines are only in a `TYPE_HEADER` chunk, because `ChunkStore.snippets` skips header chunks (their text is an outline, not the exact lines). 57 of the 355 Java files have only a header chunk (34 DTO, 18 OTHER, 5 CONFIG), and class annotations and fields of every other class live only in headers too. Two of six questions had a ref with no snippet (Q1 `ModuleConfiguration.java:31-38`, Q4 `Cart.java:16-26`), and Q5's 234-line ref left out the `@Table` constraint and `updatable = false` fields the question was about.

## Question quality (tasks 1.17, 5.1)

| Repo | Grounding rate | Specificity | Duplicate rate | Grader consistency | Injection ≤ 2 | Empty ≤ 1 | Ideal answer ≥ 8 | Date / prompt versions |
|---|---|---|---|---|---|---|---|---|
| e-commerce @ `0d2ffc0` | 8/8 (100%) | 8/8 (100%) | 0/24 (0%) | 4/4 (100%) | 4/4 (100%) | 4/4 (100%) | 3/4 (75%) | 2026-11-01 / interview-plan@29019fc8, question-generation@c6778cfe, follow-up-question@5dd42920, answer-evaluation@b0969903 |

How the 1.17 row was measured (`./mvnw verify -Pevals -Dtest=QuestionQualityEvalTest`, 635 s, metric definitions in LLD §12): the repo analyzed from scratch (270 s; embedding 243 s against 132 s in 1.15 for the same 2 102 chunks, Ollama sharing the laptop with the test database), one JUNIOR interview with 5 topics requested and 4 planned (all 4 got a question), every answer the first half of the question's own ideal outline, then four more grades per main question.

| Q | Kind | Shows code | Names a symbol | Half | Half again | Whole outline | Injection | "I don't know." |
|---|---|---|---|---|---|---|---|---|
| 1 | ARCHITECTURE | yes | yes | 4 | 3 | **7** | 0 | 0 |
| 2 | follow-up | yes | yes | 7 | — | — | — | — |
| 3 | DATA_MODEL | yes | yes | 5 | 6 | 9 | 0 | 0 |
| 4 | follow-up | yes | yes | 5 | — | — | — | — |
| 5 | FAILURE_HANDLING | yes | yes | 6 | 6 | 9 | 0 | 0 |
| 6 | follow-up | yes | yes | 6 | — | — | — | — |
| 7 | SECURITY | yes | yes | 5 | 5 | 9 | 0 | 0 |
| 8 | follow-up | yes | yes | 7 | — | — | — | — |

Findings:
1. **Every HLD target met except one**: Q1's own ideal outline, used as the answer, scored 7 (target ≥ 8). One miss in four; not enough to change a prompt. Watch it on the four repos of task 5.1. The grader was consistent on the middling answers (largest gap 1 point), and gave 0 to every injection and every "I don't know."
2. **Header-only refs (problem 3 of 1.15) did not appear**: all 8 questions had a ref that shows code, against 2 of 6 in 1.15. With n = 8 this is luck, not a fix; the grounding metric now catches it when it comes back.
3. **Every main question got a follow-up**: half-outline answers scored 4–6, inside the 3–7 follow-up range, as designed. So 8 questions (4 follow-ups) from 4 topics.
4. **QUESTION output is close to its cap**: the largest reply was 3 276 tokens of a 4 000 cap, 1.2×, below the "at least 2× the largest output" rule from the 1.15 fix (largest then: 2 060). A reply cut off at the cap is unreadable JSON. Raised to 7 000 right after this run (Status log, 2026-11-01).
5. **Calls**: 35, none failed: EVALUATION 24 (p50 6.6 s; the short adversarial answers grade fast), QUESTION 4 (p50 27.1 s), FOLLOW_UP 4, PLAN, SUMMARY and REPORT 1 each. 174 721 input and 32 181 output tokens, about $0.65.

Prompt changes because of this run: none.

## Notes from real interviews (task 1.15)

1. **Good question** (Q6, FAILURE_HANDLING): "two deliveries of the same webhook event id arrive at almost the same instant and both pass the `events.existsByEventId` check ... where does the duplicate actually get caught, and why is `events.saveAndFlush(...)` placed before the recorder calls?" It cites the three places that answer it (`PaymentWebhookProcessor.process`, the `DataIntegrityViolationException` catch in `PaymentWebhookService.receive`, and the unique key in `V14__payment_webhook_events.sql`), every snippet came through, and it can only be answered by reasoning about this code. Q2 (refresh-token reuse in `RefreshTokenService.rotate`) was grounded the same way.
2. **Weak question** (Q4, CONCURRENCY): it asks what the `@Version` field on `Cart` still does, but cites `Cart.java:16-26` (class annotations and `id`), while `@Version` is on lines 28-29, and that ref had no snippet (problem 3). Q5 had the same issue with a 234-line ref. Q1 asked two questions in one (why separate Maven modules, and what the `@ModuleConfiguration` annotations do) and cited `pom.xml:35-94`, which is mostly CVE version comments. **Prompt change: none yet.** The ref problems come from code (header chunks and ref ranges), not from the prompt, so they are fixed in code first.
3. **Grader**: mostly grounded and fair. Its "refetch and retry" recovery for the optimistic-lock question matches the repo's `GlobalExceptionHandler` (409 with that exact advice), which was not among the cited refs. Small mistakes: an ideal outline said `@ModuleConfiguration` "excludes TypeExcludeFilter" (it uses that filter to decide exclusions); it called "token family" an invented idea although the code's Javadoc says "the family revocation must commit"; and it named a boolean `repeated=true` that the code does not name.

The full transcript (questions, answers, grades, report) is kept private for now, outside the repository.
