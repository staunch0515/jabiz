-- Double-entry ledger (docs/design/11-ledger-events-jobs.md section 1; decision D14): accounts, transactions and
-- their entries are temporal platform entities, versions are only inserted (decision D5). Transactions and entries
-- are written by the ledger processes only; a correction is a reversing transaction, never a change.

-- The helper of V6 (dropped there, dropped again below): creates one temporal table with the system columns, the
-- version constraint and the lookup indexes of docs/design/04-temporal-append-only.md section 2.1.
CREATE FUNCTION jabiz_create_temporal_table(p_table text, p_key text, p_columns text) RETURNS void
    LANGUAGE plpgsql AS $$
BEGIN
    EXECUTE format('CREATE TABLE %I (
        row_id            bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
        %I                uuid        NOT NULL REFERENCES entity_registry (entity_id),
        version_no        integer     NOT NULL,
        effect_start_time timestamptz NOT NULL,
        created_time      timestamptz NOT NULL,
        process_seq_id    bigint      NOT NULL REFERENCES op_process (process_seq_id),
        is_deleted        boolean     NOT NULL DEFAULT false,
        %s,
        CONSTRAINT %I UNIQUE (%I, version_no))',
        p_table, p_key, p_columns, p_table || '_uk', p_key);
    EXECUTE format('CREATE INDEX %I ON %I (%I, effect_start_time DESC, version_no DESC)',
        p_table || '_current_idx', p_table, p_key);
    EXECUTE format('CREATE INDEX %I ON %I (process_seq_id)', p_table || '_process_idx', p_table);
    PERFORM jabiz_protect_append_only(p_table::regclass);
END $$;

SELECT jabiz_create_temporal_table('ledger_account_version', 'account_id', '
    account_code varchar(50)  NOT NULL,
    account_name varchar(200) NOT NULL,
    account_type varchar(20)  NOT NULL,
    enabled      boolean      NOT NULL DEFAULT true');
CREATE INDEX ledger_account_version_code_idx ON ledger_account_version (account_code);

SELECT jabiz_create_temporal_table('ledger_transaction_version', 'transaction_id', '
    booking_time            timestamptz  NOT NULL,
    description             varchar(500) NOT NULL,
    reference               varchar(100),
    reverses_transaction_id uuid         REFERENCES entity_registry (entity_id)');
CREATE INDEX ledger_transaction_version_booking_idx ON ledger_transaction_version (booking_time);
CREATE INDEX ledger_transaction_version_reverses_idx ON ledger_transaction_version (reverses_transaction_id);

SELECT jabiz_create_temporal_table('ledger_entry_version', 'entry_id', '
    transaction_id uuid          NOT NULL REFERENCES entity_registry (entity_id),
    account_id     uuid          NOT NULL REFERENCES entity_registry (entity_id),
    line_no        integer       NOT NULL,
    direction      varchar(6)    NOT NULL CHECK (direction IN (''DEBIT'', ''CREDIT'')),
    amount         numeric(19,4) NOT NULL CHECK (amount > 0)');
CREATE INDEX ledger_entry_version_transaction_idx ON ledger_entry_version (transaction_id);
CREATE INDEX ledger_entry_version_account_idx ON ledger_entry_version (account_id);

DROP FUNCTION jabiz_create_temporal_table(text, text, text);

-- Second line of defence behind the check before writing: when the database transaction commits, the current
-- entries of every ledger transaction it wrote must balance. Violations raise SQLSTATE JZ002.
CREATE FUNCTION jabiz_ledger_check_balance() RETURNS trigger
    LANGUAGE plpgsql AS $$
DECLARE
    difference numeric;
BEGIN
    SELECT coalesce(sum(CASE WHEN e.direction = 'DEBIT' THEN e.amount ELSE -e.amount END), 0)
      INTO difference
      FROM (SELECT DISTINCT ON (entry_id) *
              FROM ledger_entry_version
             WHERE transaction_id = NEW.transaction_id
             ORDER BY entry_id, effect_start_time DESC, version_no DESC) e
     WHERE NOT e.is_deleted;
    IF difference <> 0 THEN
        RAISE EXCEPTION 'Ledger transaction % does not balance (debit - credit = %)', NEW.transaction_id, difference
            USING ERRCODE = 'JZ002';
    END IF;
    RETURN NULL;
END $$;

CREATE CONSTRAINT TRIGGER ledger_entry_version_balanced
    AFTER INSERT ON ledger_entry_version
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION jabiz_ledger_check_balance();
