DO $$
DECLARE
    role_name TEXT;
BEGIN
    FOREACH role_name IN ARRAY ARRAY[
        'pas_owner', 'pas_service', 'pas_batch', 'pas_sync', 'pas_readonly'
    ] LOOP
        IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = role_name) THEN
            EXECUTE format('CREATE ROLE %I NOLOGIN', role_name);
        END IF;
    END LOOP;
END
$$;

GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA pas_batch TO pas_batch;
ALTER DEFAULT PRIVILEGES IN SCHEMA pas_batch
    GRANT USAGE, SELECT ON SEQUENCES TO pas_batch;
