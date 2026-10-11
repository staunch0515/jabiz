-- Inbound webhooks (docs/design/11-ledger-events-jobs.md section 2.5; decision D37).

-- One accepted request of a provider, its body exactly as received (strict UTF-8, so text loses nothing) with its
-- SHA-256, written in the transaction of INBOUND_RECEIVE together with the outbox event that hands it to the
-- application's processes. Rejected requests and event types outside a provider's list are never stored. The same
-- external event arriving again finds its key taken: one row, one event. Read through the platform entity InboundEvent
-- (inbound.read). The body is in a table of its own, so that lists, exports and retention reports, which read this
-- table, never carry up to 1 MB per row.
CREATE TABLE sys_inbound_event (
    inbound_event_id  varchar(36)   PRIMARY KEY,
    provider          varchar(40)   NOT NULL,
    external_event_id varchar(255)  NOT NULL,
    event_type        varchar(100)  NOT NULL,
    livemode          boolean,
    signed_time       timestamptz   NOT NULL,
    received_time     timestamptz   NOT NULL,
    content_type      varchar(100),
    body_sha256       char(64)      NOT NULL,
    process_seq_id    bigint        NOT NULL REFERENCES op_process (process_seq_id),
    CONSTRAINT sys_inbound_event_external_key UNIQUE (provider, external_event_id)
);
CREATE INDEX sys_inbound_event_type_idx ON sys_inbound_event (provider, event_type, received_time);
CREATE INDEX sys_inbound_event_process_idx ON sys_inbound_event (process_seq_id);
-- Retention reports count by the time an event was received.
CREATE INDEX sys_inbound_event_received_idx ON sys_inbound_event (received_time);
SELECT jabiz_protect_append_only('sys_inbound_event');

-- The body of an event exactly as received, written in the same transaction as its event; read only one at a time, by
-- the consumers (LoadInboundEvent) and through GET /api/inbound-events/{id}/body (inbound.read).
CREATE TABLE sys_inbound_event_body (
    inbound_event_id  varchar(36)   PRIMARY KEY REFERENCES sys_inbound_event (inbound_event_id),
    body              text          NOT NULL
);
SELECT jabiz_protect_append_only('sys_inbound_event_body');
