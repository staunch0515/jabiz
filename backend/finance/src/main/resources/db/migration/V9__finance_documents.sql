-- The invoice documents (docs/finance/00-design.md section 8; ROADMAP F3d): the company's profile its documents show,
-- temporal and only ever inserted into (platform decision D9), and the time an invoice was posted, at which its
-- document reads the data when it was entered after its date.

CREATE TABLE fi_company_profile_version (
    row_id            bigint        GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    profile_id        uuid          NOT NULL REFERENCES entity_registry (entity_id),
    version_no        integer       NOT NULL,
    effect_start_time timestamptz   NOT NULL,
    created_time      timestamptz   NOT NULL,
    process_seq_id    bigint        NOT NULL REFERENCES op_process (process_seq_id),
    is_deleted        boolean       NOT NULL DEFAULT false,
    profile_key       varchar(10)   NOT NULL,
    legal_name        varchar(200)  NOT NULL,
    street            varchar(200),
    city              varchar(100),
    state             varchar(20),
    postal_code       varchar(20),
    country           varchar(60),
    phone             varchar(40),
    email             varchar(200),
    remittance        varchar(1000),
    CONSTRAINT fi_company_profile_version_uk UNIQUE (profile_id, version_no)
);
CREATE INDEX fi_company_profile_version_current_idx
    ON fi_company_profile_version (profile_id, effect_start_time DESC, version_no DESC);
CREATE INDEX fi_company_profile_version_process_idx ON fi_company_profile_version (process_seq_id);
CREATE INDEX fi_company_profile_version_key_idx ON fi_company_profile_version (profile_key);
SELECT jabiz_protect_append_only('fi_company_profile_version');

-- Empty for opening items and for what was posted before this column.
ALTER TABLE fi_invoice_version ADD COLUMN posted_time timestamptz;
