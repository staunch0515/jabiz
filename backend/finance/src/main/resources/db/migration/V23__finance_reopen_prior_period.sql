-- Governed reopening and prior-period items (docs/finance/00-design.md section 7; ROADMAP F8b; FIN-PC-006, PC-007):
-- the requests to open a closed period again, and the day a bill or an invoice is booked when later than its date.
-- Existing documents were booked on their dates: their posting date stays empty.

ALTER TABLE fi_bill_version ADD COLUMN posting_date date;
ALTER TABLE fi_invoice_version ADD COLUMN posting_date date;
CREATE INDEX fi_bill_version_posting_idx ON fi_bill_version (posting_date) WHERE posting_date IS NOT NULL;
CREATE INDEX fi_invoice_version_posting_idx ON fi_invoice_version (posting_date) WHERE posting_date IS NOT NULL;

CREATE TABLE fi_period_reopen_version (
    row_id              bigint        GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    reopen_id           uuid          NOT NULL REFERENCES entity_registry (entity_id),
    version_no          integer       NOT NULL,
    effect_start_time   timestamptz   NOT NULL,
    created_time        timestamptz   NOT NULL,
    process_seq_id      bigint        NOT NULL REFERENCES op_process (process_seq_id),
    is_deleted          boolean       NOT NULL DEFAULT false,
    period_key          varchar(7)    NOT NULL,
    reason              varchar(1000) NOT NULL,
    artifact_id         uuid          REFERENCES entity_registry (entity_id),
    requested_by        varchar(100)  NOT NULL,
    requested_at        timestamptz   NOT NULL,
    status              varchar(10)   NOT NULL,
    approval_request_id varchar(40),
    content_hash        varchar(64),
    decided_by          varchar(100),
    decided_at          timestamptz,
    CONSTRAINT fi_period_reopen_version_uk UNIQUE (reopen_id, version_no));
CREATE INDEX fi_period_reopen_version_current_idx
    ON fi_period_reopen_version (reopen_id, effect_start_time DESC, version_no DESC);
CREATE INDEX fi_period_reopen_version_process_idx ON fi_period_reopen_version (process_seq_id);
CREATE INDEX fi_period_reopen_version_period_idx ON fi_period_reopen_version (period_key, status);
SELECT jabiz_protect_append_only('fi_period_reopen_version'::regclass);
