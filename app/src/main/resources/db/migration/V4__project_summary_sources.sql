-- V4: what the SUMMARY prompt needs besides the repo map, stored at the end of PARSING (task 2.3). Phase 1 handed it
-- from parsing to summarizing in memory, inside one job; on Kafka those are different consumers, so it must be stored.
-- Both are cut from redacted file text (SummarySources): dependency coordinates of the build files, and the README's
-- first lines. NULL when the repository has none, and for projects parsed before V4.
ALTER TABLE project
    ADD COLUMN build_files TEXT,
    ADD COLUMN readme      TEXT;
