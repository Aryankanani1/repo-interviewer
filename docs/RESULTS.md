# Results (measured, not estimated)

Every number on the resume comes from this file. Fill it in as tasks are completed. Include the date, the commit, and how each number was measured.

## Analysis performance (task 1.15)

| Repo | Files | Chunks | Clone | Parse | Embed | Summarize | Total | Date / commit |
|---|---|---|---|---|---|---|---|---|
| | | | | | | | | |

## Interview latency and cost (tasks 1.15, 1.16, 4.5)

| Setup | p50 next question | p95 next question | Input tokens / session | Output tokens / session | Cached tokens | Date / commit |
|---|---|---|---|---|---|---|
| Phase 1, no prefetch | | | | | — | |
| Phase 1, prefetch | | | | | — | |
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

## Question quality (tasks 1.17, 5.1)

| Repo | Grounding rate | Specificity | Duplicate rate | Grader consistency | Injection ≤ 2 | Ideal answer ≥ 8 | Date / prompt versions |
|---|---|---|---|---|---|---|---|
| | | | | | | | |

## Notes from real interviews (task 1.15)

- Good question example:
- Weak question example, and what changed in the prompt because of it:
