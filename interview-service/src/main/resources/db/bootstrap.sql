-- interview-service's database user and schema (task 4.1, DECISIONS.md D-026).
-- Run ONCE per database by the admin (a superuser), before the service's first start. Safe to run again.
-- Not a Flyway migration: the service's own user may not create users or schemas, which is the point.
--   Compose, new volume:      docker-compose.yml runs it at Postgres' first start
--   Compose, existing volume: docker compose exec -T postgres psql -U ri -d repo_interviewer < interview-service/src/main/resources/db/bootstrap.sql
--   Tests:                    TestcontainersConfiguration runs it the same way, in a new container
--
-- Isolation: interview_svc owns the schema "interview" and nothing else, so Postgres itself refuses its reads of any
-- other service's tables ("permission denied"). The password is the user name, like ri/ri: for a local database
-- only. A real deployment sets its own (ALTER ROLE interview_svc PASSWORD ...) from a secret.

DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'interview_svc') THEN
        CREATE ROLE interview_svc LOGIN PASSWORD 'interview_svc';
    END IF;
END
$$;

CREATE SCHEMA IF NOT EXISTS interview AUTHORIZATION interview_svc;

-- Plain SQL resolves in its own schema. No extension: unlike analysis-service, it stores no vectors.
ALTER ROLE interview_svc SET search_path = interview;
