-- AB-529: where each premiumRecalculationJob run wrote PREMIUMS (pas.batch.premium.write-target).
-- The parallel run only compares SHADOW runs, including ones that completed with no rows.
ALTER TABLE pas_batch.premium_run_summary
    ADD COLUMN write_target VARCHAR(8) NOT NULL DEFAULT 'PAS';
