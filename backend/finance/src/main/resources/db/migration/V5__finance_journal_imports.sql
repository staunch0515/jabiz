-- Journal and payroll imports (docs/finance/00-design.md section 13; ROADMAP F2b).

-- The document an imported entry came from: each is imported once (FIN-GL-019, FIN-DI-006).
ALTER TABLE fi_journal_version ADD COLUMN external_ref varchar(100);
CREATE INDEX fi_journal_version_external_ref_idx ON fi_journal_version (external_ref);

-- How a payroll provider's code is booked (FIN-DI-004): one mapping per code, kept by the controller.
CREATE TABLE fi_payroll_mapping_version (
    row_id            bigint       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    mapping_id        uuid         NOT NULL REFERENCES entity_registry (entity_id),
    version_no        integer      NOT NULL,
    effect_start_time timestamptz  NOT NULL,
    created_time      timestamptz  NOT NULL,
    process_seq_id    bigint       NOT NULL REFERENCES op_process (process_seq_id),
    is_deleted        boolean      NOT NULL DEFAULT false,
    provider_code     varchar(40)  NOT NULL,
    account_code      varchar(20)  NOT NULL,
    side              varchar(6)   NOT NULL,
    department        varchar(20),
    description       varchar(200),
    active            boolean      NOT NULL,
    CONSTRAINT fi_payroll_mapping_version_uk UNIQUE (mapping_id, version_no)
);
CREATE INDEX fi_payroll_mapping_version_current_idx
    ON fi_payroll_mapping_version (mapping_id, effect_start_time DESC, version_no DESC);
CREATE INDEX fi_payroll_mapping_version_process_idx ON fi_payroll_mapping_version (process_seq_id);
CREATE INDEX fi_payroll_mapping_version_code_idx ON fi_payroll_mapping_version (provider_code);
SELECT jabiz_protect_append_only('fi_payroll_mapping_version');
