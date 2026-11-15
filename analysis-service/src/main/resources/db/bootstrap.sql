-- analysis-service's database user and schema (task 4.1, DECISIONS.md D-026).
-- Run ONCE per database by the admin (a superuser), before the service's first start. Safe to run again.
-- Not a Flyway migration: the service's own user may not create users, schemas or extensions, which is the point.
--   Compose, new volume:      docker-compose.yml runs it at Postgres' first start
--   Compose, existing volume: docker compose exec -T postgres psql -U ri -d repo_interviewer < analysis-service/src/main/resources/db/bootstrap.sql
--   Tests:                    TestcontainersConfiguration runs it the same way, in a new container
--
-- Isolation: analysis_svc owns the schema "analysis" and nothing else, so Postgres itself refuses its reads of any
-- other service's tables ("permission denied"). The password is the user name, like ri/ri: for a local database
-- only. A real deployment sets its own (ALTER ROLE analysis_svc PASSWORD ...) from a secret.

DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'analysis_svc') THEN
        CREATE ROLE analysis_svc LOGIN PASSWORD 'analysis_svc';
    END IF;
END
$$;

CREATE SCHEMA IF NOT EXISTS analysis AUTHORIZATION analysis_svc;

-- Plain SQL and the "vector" type and operators resolve in its own schema first, then in public.
ALTER ROLE analysis_svc SET search_path = analysis, public;

-- pgvector, in public: creating an extension needs the admin. analysis_svc only uses the type.
CREATE EXTENSION IF NOT EXISTS vector;
