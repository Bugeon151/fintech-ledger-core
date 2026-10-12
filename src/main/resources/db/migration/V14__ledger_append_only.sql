-- Ledger entries are immutable facts. Enforce it in the database, not by convention.
CREATE FUNCTION forbid_ledger_mutation() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'ledger_entries is append-only (% rejected)', TG_OP;
END;
$$;

CREATE TRIGGER trg_ledger_entries_append_only
    BEFORE UPDATE OR DELETE ON ledger_entries
    FOR EACH ROW EXECUTE FUNCTION forbid_ledger_mutation();
