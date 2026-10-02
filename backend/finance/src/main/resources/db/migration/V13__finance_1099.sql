-- Form 1099 (docs/finance/00-design.md section 9; ROADMAP F4d): the amounts payments count on vendors' Forms 1099, the
-- records filed, and the company's taxpayer identification number as the payer. The tables are temporal, written
-- once and only ever inserted into (platform decisions D9, D29).

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

SELECT finance_create_temporal_table('fi_1099_amount_version', 'amount_id', '
    vendor_code  varchar(20)   NOT NULL,
    tax_year     numeric(4,0)  NOT NULL,
    form_1099    varchar(10)   NOT NULL,
    box_1099     varchar(2)    NOT NULL,
    amount       numeric(15,2) NOT NULL,
    source       varchar(15)   NOT NULL,
    payment_date date          NOT NULL,
    payment_id   uuid          NOT NULL REFERENCES entity_registry (entity_id),
    payment_no   varchar(20)   NOT NULL,
    bill_id      uuid          REFERENCES entity_registry (entity_id),
    bill_no      varchar(40)');
CREATE UNIQUE INDEX fi_1099_amount_version_once_uk ON fi_1099_amount_version (amount_id);
CREATE INDEX fi_1099_amount_version_year_idx ON fi_1099_amount_version (tax_year, vendor_code);
CREATE INDEX fi_1099_amount_version_payment_idx ON fi_1099_amount_version (payment_id);

SELECT finance_create_temporal_table('fi_1099_filing_version', 'filing_id', '
    tax_year          numeric(4,0)  NOT NULL,
    vendor_code       varchar(20)   NOT NULL,
    form_1099         varchar(10)   NOT NULL,
    box_1099          varchar(2)    NOT NULL,
    amount            numeric(15,2) NOT NULL,
    tin               varchar(11)   NOT NULL,
    kind              varchar(15)   NOT NULL,
    supersedes_id     uuid          REFERENCES entity_registry (entity_id),
    generated_file_id varchar(36)   NOT NULL,
    filed_by          varchar(100)  NOT NULL');
CREATE UNIQUE INDEX fi_1099_filing_version_once_uk ON fi_1099_filing_version (filing_id);
CREATE INDEX fi_1099_filing_version_year_idx ON fi_1099_filing_version (tax_year, vendor_code);
-- Two exports or corrections at once never file a record twice: one original per box, one correction per filing.
CREATE UNIQUE INDEX fi_1099_filing_version_original_uk ON fi_1099_filing_version
    (tax_year, vendor_code, form_1099, box_1099) WHERE kind = 'ORIGINAL';
CREATE UNIQUE INDEX fi_1099_filing_version_supersedes_uk ON fi_1099_filing_version (supersedes_id)
    WHERE supersedes_id IS NOT NULL;

DROP FUNCTION finance_create_temporal_table(text, text, text);

-- The payer's TIN on the company's Forms 1099 (FIN-AP-022); an EIN, shown in full: the forms carry it so.
ALTER TABLE fi_company_profile_version ADD COLUMN tax_id varchar(11);
