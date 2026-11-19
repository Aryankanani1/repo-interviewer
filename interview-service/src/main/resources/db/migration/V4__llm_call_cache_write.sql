-- Task 4.5 (DECISIONS.md D-032): input tokens written to Anthropic's prompt cache, billed at 1.25x the input price,
-- next to cached_tokens (read from the cache, billed at 0.1x). With caching, input_tokens counts only the rest.
ALTER TABLE llm_call ADD COLUMN cache_write_tokens INT;
