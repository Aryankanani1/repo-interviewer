-- V5 (task 4.1 step 2b, DECISIONS.md D-026): projects now live in analysis-service, in its own schema. A session and a
-- model call keep their project's id, but Postgres can no longer check it across services: the interview code reads
-- the project through AnalysisApi (unknown → NOT_FOUND) before it saves a session. The names are Postgres' defaults
-- for the inline REFERENCES in V1. The project, code_file and code_chunk tables of this schema are no longer used.
ALTER TABLE interview_session DROP CONSTRAINT interview_session_project_id_fkey;
ALTER TABLE llm_call DROP CONSTRAINT llm_call_project_id_fkey;
