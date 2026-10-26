-- Inbound webhooks whose signature covers the body only (decision D57; docs/design/11-ledger-events-jobs.md section 2.5).
-- Such a scheme signs no time, so signed_time may be empty. One action of the sender may give several event types the
-- same id, so an event is its id within its type. Where the id is not signed, a request captured once could come back
-- with another id: replay_guard marks those events, and the same body of the same type is then a duplicate too.
-- metadata keeps the provider's configured, unsigned headers (such as the sending shop) as received.
ALTER TABLE sys_inbound_event ALTER COLUMN signed_time DROP NOT NULL;
ALTER TABLE sys_inbound_event ADD COLUMN metadata jsonb;
ALTER TABLE sys_inbound_event ADD COLUMN replay_guard boolean NOT NULL DEFAULT false;
ALTER TABLE sys_inbound_event DROP CONSTRAINT sys_inbound_event_external_key;
ALTER TABLE sys_inbound_event ADD CONSTRAINT sys_inbound_event_external_key
    UNIQUE (provider, event_type, external_event_id);
CREATE UNIQUE INDEX sys_inbound_event_body_key ON sys_inbound_event (provider, event_type, body_sha256)
    WHERE replay_guard;
