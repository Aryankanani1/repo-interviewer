-- Task 4.4 (DECISIONS.md D-030): every session belongs to the user who started it, the JWT subject the gateway
-- passes on. The gateway compares it with the caller before every read, answer or end of the session.
ALTER TABLE interview_session ADD COLUMN user_id VARCHAR(255);

-- Sessions started before this belong to the local dev user, so a dev token for "local-dev" still sees them.
UPDATE interview_session SET user_id = 'local-dev';
ALTER TABLE interview_session ALTER COLUMN user_id SET NOT NULL;
