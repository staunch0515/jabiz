-- The settings of the reports (ROADMAP F9c; FIN-RP-004, RP-012): the accounts behind the cash flow statement's
-- interest and income taxes paid and those of the note schedules.

CREATE TABLE fi_report_settings_version (
    row_id                      bigint       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    settings_id                 uuid         NOT NULL REFERENCES entity_registry (entity_id),
    version_no                  integer      NOT NULL,
    effect_start_time           timestamptz  NOT NULL,
    created_time                timestamptz  NOT NULL,
    process_seq_id              bigint       NOT NULL REFERENCES op_process (process_seq_id),
    is_deleted                  boolean      NOT NULL DEFAULT false,
    settings_key                varchar(10)  NOT NULL,
    interest_accounts           varchar(500),
    interest_payable_accounts   varchar(500),
    income_tax_accounts         varchar(500),
    income_tax_payable_accounts varchar(500),
    receivables_accounts        varchar(500),
    accrued_accounts            varchar(500),
    debt_accounts               varchar(500),
    CONSTRAINT fi_report_settings_version_uk UNIQUE (settings_id, version_no));
CREATE INDEX fi_report_settings_version_current_idx
    ON fi_report_settings_version (settings_id, effect_start_time DESC, version_no DESC);
CREATE INDEX fi_report_settings_version_process_idx ON fi_report_settings_version (process_seq_id);
CREATE INDEX fi_report_settings_version_key_idx ON fi_report_settings_version (settings_key);
SELECT jabiz_protect_append_only('fi_report_settings_version'::regclass);
