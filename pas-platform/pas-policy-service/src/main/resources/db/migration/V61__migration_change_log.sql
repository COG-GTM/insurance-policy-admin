CREATE TABLE pas.change_log (
    id BIGSERIAL PRIMARY KEY,
    tx_id xid8 NOT NULL DEFAULT pg_current_xact_id(),
    table_name VARCHAR(32) NOT NULL,
    record_key VARCHAR(64) NOT NULL,
    policy_number CHAR(12),
    changed_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

CREATE INDEX ix_change_log_changed_at_id ON pas.change_log (changed_at, id);
CREATE INDEX ix_change_log_tx_id_id ON pas.change_log (tx_id, id);

CREATE FUNCTION pas.capture_change_log() RETURNS trigger
    LANGUAGE plpgsql
    SECURITY INVOKER
    SET search_path = pas, pg_temp
AS $$
DECLARE
    row_data JSONB;
    key_value TEXT;
    policy_value TEXT;
BEGIN
    IF current_setting('pas.change_origin', true) = 'forward-sync' THEN
        IF TG_OP = 'DELETE' THEN
            RETURN OLD;
        END IF;
        RETURN NEW;
    END IF;
    row_data := CASE WHEN TG_OP = 'DELETE' THEN to_jsonb(OLD) ELSE to_jsonb(NEW) END;
    IF TG_TABLE_NAME = 'policy_holders' THEN
        key_value := row_data ->> 'cust_id';
        policy_value := NULL;
    ELSE
        key_value := row_data ->> 'policy_number';
        policy_value := key_value;
    END IF;
    INSERT INTO pas.change_log (table_name, record_key, policy_number)
    VALUES (upper(TG_TABLE_NAME), key_value, policy_value);
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER trg_change_log_policies AFTER INSERT OR UPDATE OR DELETE ON pas.policies
    FOR EACH ROW EXECUTE FUNCTION pas.capture_change_log();
CREATE TRIGGER trg_change_log_policy_holders AFTER INSERT OR UPDATE OR DELETE ON pas.policy_holders
    FOR EACH ROW EXECUTE FUNCTION pas.capture_change_log();
CREATE TRIGGER trg_change_log_coverages AFTER INSERT OR UPDATE OR DELETE ON pas.coverages
    FOR EACH ROW EXECUTE FUNCTION pas.capture_change_log();
CREATE TRIGGER trg_change_log_premiums AFTER INSERT OR UPDATE OR DELETE ON pas.premiums
    FOR EACH ROW EXECUTE FUNCTION pas.capture_change_log();
CREATE TRIGGER trg_change_log_endorsements AFTER INSERT OR UPDATE OR DELETE ON pas.endorsements
    FOR EACH ROW EXECUTE FUNCTION pas.capture_change_log();
CREATE TRIGGER trg_change_log_underwriting_decisions
    AFTER INSERT OR UPDATE OR DELETE ON pas.underwriting_decisions
    FOR EACH ROW EXECUTE FUNCTION pas.capture_change_log();
