-- Matching statement lines to the books and the entries made from statement lines (docs/finance/00-design.md
-- section 10; ROADMAP F5b). Matches, their items and the entries are written once: an undone match stays, and the
-- undo is a record of its own (platform decision D9). The plain unique indexes hold against concurrent matching: a
-- reference's round (one more than its matches before) and an undo per match.

CREATE FUNCTION finance_create_temporal_table(p_table text, p_key text, p_columns text) RETURNS void
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

SELECT finance_create_temporal_table('fi_bank_match_version', 'match_id', '
    bank_code         varchar(20)   NOT NULL,
    action            varchar(10)   NOT NULL,
    reverses_match_id uuid          REFERENCES entity_registry (entity_id),
    method            varchar(10),
    amount            numeric(15,2),
    confidence        numeric(3,0),
    reason            varchar(500),
    actor             varchar(100)  NOT NULL,
    action_time       timestamptz   NOT NULL');
CREATE UNIQUE INDEX fi_bank_match_version_once_uk ON fi_bank_match_version (match_id);
CREATE UNIQUE INDEX fi_bank_match_version_undo_uk ON fi_bank_match_version (reverses_match_id)
    WHERE reverses_match_id IS NOT NULL;
CREATE INDEX fi_bank_match_version_bank_idx ON fi_bank_match_version (bank_code, action_time);

SELECT finance_create_temporal_table('fi_bank_match_item_version', 'item_id', '
    match_id  uuid          NOT NULL REFERENCES entity_registry (entity_id),
    bank_code varchar(20)   NOT NULL,
    side      varchar(10)   NOT NULL,
    ref_kind  varchar(10)   NOT NULL,
    ref_id    varchar(36)   NOT NULL,
    round     numeric(6,0)  NOT NULL,
    item_date date          NOT NULL,
    amount    numeric(15,2) NOT NULL,
    label     varchar(100)');
CREATE UNIQUE INDEX fi_bank_match_item_version_once_uk ON fi_bank_match_item_version (item_id);
CREATE UNIQUE INDEX fi_bank_match_item_version_round_uk ON fi_bank_match_item_version
    (bank_code, ref_kind, ref_id, round);
CREATE INDEX fi_bank_match_item_version_match_idx ON fi_bank_match_item_version (match_id);
CREATE INDEX fi_bank_match_item_version_bank_idx ON fi_bank_match_item_version (bank_code, side);

SELECT finance_create_temporal_table('fi_bank_entry_rule_version', 'rule_id', '
    rule_code       varchar(20)  NOT NULL,
    keywords        varchar(200) NOT NULL,
    direction       varchar(10)  NOT NULL,
    account         varchar(20)  NOT NULL,
    document_prefix varchar(15)  NOT NULL,
    description     varchar(200),
    active          boolean      NOT NULL');
CREATE INDEX fi_bank_entry_rule_version_code_idx ON fi_bank_entry_rule_version (rule_code);

SELECT finance_create_temporal_table('fi_bank_entry_version', 'entry_id', '
    entry_no    varchar(30)   NOT NULL,
    bank_code   varchar(20)   NOT NULL,
    line_id     uuid          NOT NULL REFERENCES entity_registry (entity_id),
    entry_date  date          NOT NULL,
    account     varchar(20)   NOT NULL,
    amount      numeric(15,2) NOT NULL,
    description varchar(500),
    rule_code   varchar(20)');
CREATE UNIQUE INDEX fi_bank_entry_version_once_uk ON fi_bank_entry_version (entry_id);
CREATE UNIQUE INDEX fi_bank_entry_version_no_uk ON fi_bank_entry_version (entry_no);
CREATE UNIQUE INDEX fi_bank_entry_version_line_uk ON fi_bank_entry_version (line_id);

DROP FUNCTION finance_create_temporal_table(text, text, text);
