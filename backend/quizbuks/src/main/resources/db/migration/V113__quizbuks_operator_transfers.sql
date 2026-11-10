-- F-M4 (docs/quizbuks/first-launch-spec.md F-M4, docs/quizbuks/plans/impl/F-M.md §7): an operator's lead moves money
-- of the operator's account to its direct Sponsor's account that pays the platform's fees. Each transfer is written
-- once (append-only), the source of its posting (debit 2140 / credit 2160) and of the Sponsor's cash lot (OPERATOR).

CREATE TABLE qb_operator_transfer_version (
    row_id            bigint         GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    transfer_id       uuid           NOT NULL REFERENCES entity_registry (entity_id),
    version_no        integer        NOT NULL,
    effect_start_time timestamptz    NOT NULL,
    created_time      timestamptz    NOT NULL,
    process_seq_id    bigint         NOT NULL REFERENCES op_process (process_seq_id),
    is_deleted        boolean        NOT NULL DEFAULT false,
    operator_id       varchar(36)    NOT NULL REFERENCES qb_operator (operator_id),
    sponsor_id        varchar(128)   NOT NULL,
    amount            numeric(19, 0) NOT NULL CHECK (amount > 0),
    note              varchar(200),
    request_key       varchar(128)   NOT NULL,
    requested_by      varchar(128)   NOT NULL,
    transferred_at    timestamptz    NOT NULL,
    CONSTRAINT qb_operator_transfer_version_uk UNIQUE (transfer_id, version_no)
);
CREATE INDEX qb_operator_transfer_version_current_idx
    ON qb_operator_transfer_version (transfer_id, effect_start_time DESC, version_no DESC);
CREATE INDEX qb_operator_transfer_version_process_idx ON qb_operator_transfer_version (process_seq_id);
-- Written once: one row per key.
CREATE UNIQUE INDEX qb_operator_transfer_version_once_idx ON qb_operator_transfer_version (transfer_id);
CREATE INDEX qb_operator_transfer_version_operator_idx ON qb_operator_transfer_version (operator_id, transferred_at);
-- One transfer per submission: the page's request key, per operator.
CREATE UNIQUE INDEX qb_operator_transfer_version_request_idx ON qb_operator_transfer_version (operator_id, request_key);
CREATE INDEX qb_operator_transfer_version_sponsor_idx ON qb_operator_transfer_version (sponsor_id);
SELECT jabiz_protect_append_only('qb_operator_transfer_version');
