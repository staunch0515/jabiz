-- Transactional outbox (docs/design/11-ledger-events-jobs.md section 2; decision D14). Events are inserted in the
-- transaction of the operation that publishes them, so a rollback leaves none behind; the deliverer hands them to
-- their consumers after the commit. All three tables are append-only (decision D5): delivery state is derived from
-- the consumptions and attempts recorded, never updated in place.
CREATE TABLE sys_outbox_event (
    event_seq      bigint       GENERATED ALWAYS AS IDENTITY UNIQUE,
    event_id       uuid         PRIMARY KEY,
    event_type     varchar(200) NOT NULL,
    entity_type    varchar(200),
    entity_id      varchar(200),
    payload        jsonb        NOT NULL,
    process_seq_id bigint       REFERENCES op_process (process_seq_id),
    created_time   timestamptz  NOT NULL
);
CREATE INDEX sys_outbox_event_type_idx ON sys_outbox_event (event_type, event_seq);
CREATE INDEX sys_outbox_event_process_idx ON sys_outbox_event (process_seq_id);
SELECT jabiz_protect_append_only('sys_outbox_event');

-- One row per event a consumer processed, inserted first in the consumer's own transaction: the primary key makes a
-- concurrent or repeated delivery of the same event wait and then fail, so each event is processed once.
CREATE TABLE sys_event_consumption (
    consumer       varchar(100) NOT NULL,
    event_id       uuid         NOT NULL REFERENCES sys_outbox_event (event_id),
    process_seq_id bigint       REFERENCES op_process (process_seq_id),
    consumed_time  timestamptz  NOT NULL,
    PRIMARY KEY (consumer, event_id)
);
SELECT jabiz_protect_append_only('sys_event_consumption');

-- Failed deliveries, one row per attempt; retries back off by the number of failures so far.
CREATE TABLE sys_outbox_attempt (
    consumer     varchar(100) NOT NULL,
    event_id     uuid         NOT NULL REFERENCES sys_outbox_event (event_id),
    attempt      integer      NOT NULL,
    error        text         NOT NULL,
    attempted_at timestamptz  NOT NULL,
    PRIMARY KEY (consumer, event_id, attempt)
);
SELECT jabiz_protect_append_only('sys_outbox_attempt');
