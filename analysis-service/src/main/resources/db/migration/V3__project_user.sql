-- Task 4.4 (DECISIONS.md D-030): every project belongs to the user who submitted it, the JWT subject the gateway
-- passes on. Two users who submit the same repository at the same commit get one project each.
ALTER TABLE project ADD COLUMN user_id VARCHAR(255);

-- Projects analyzed before this belong to the local dev user, so a dev token for "local-dev" still sees them.
UPDATE project SET user_id = 'local-dev';
ALTER TABLE project ALTER COLUMN user_id SET NOT NULL;

ALTER TABLE project DROP CONSTRAINT uq_project_url_commit;
ALTER TABLE project ADD CONSTRAINT uq_project_user_url_commit UNIQUE (user_id, github_url, commit_sha);

-- The projects query pages through one user's projects, newest first.
DROP INDEX ix_project_created_at;
CREATE INDEX ix_project_user_created_at ON project (user_id, created_at DESC, id DESC);
