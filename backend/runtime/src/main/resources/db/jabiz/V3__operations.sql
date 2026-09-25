-- Operations and the append-only guard (docs/design/04-temporal-append-only.md sections 2.2, 2.3;
-- decisions D4 and D5 in docs/design/09-decisions.md).

-- Every write of a temporal entity belongs to one operation. Operations are recorded when they start and never
-- updated: their output goes to op_process_result, a revert is a new operation pointing at the one it reverts.
CREATE TABLE op_process (
    process_seq_id   bigint      PRIMARY KEY,           -- nextval('op_process_seq')
    parent_seq_id    bigint      REFERENCES op_process,  -- sub-process -> its parent
    reverts_seq_id   bigint      REFERENCES op_process,  -- revert -> the operation it reverts
    process_name     text        NOT NULL,
    process_version  integer     NOT NULL,
    actor_id         text        NOT NULL,
    tenant_id        text,
    request_id       text,
    idempotency_key  text,
    reason           text,
    input_summary    jsonb,                              -- sensitive fields masked
    op_time          timestamptz NOT NULL,               -- the one time of the operation, from the application clock
    UNIQUE (actor_id, idempotency_key)
);
CREATE INDEX op_process_parent_idx ON op_process (parent_seq_id);
CREATE INDEX op_process_reverts_idx ON op_process (reverts_seq_id);

-- One row per version an operation wrote.
CREATE TABLE op_process_item (
    process_seq_id    bigint      NOT NULL REFERENCES op_process,
    entity_type       text        NOT NULL,
    entity_id         uuid        NOT NULL,
    version_no        integer     NOT NULL,
    base_version_no   integer,                           -- version the change was based on; null on insert
    action            text        NOT NULL CHECK (action IN ('INSERT', 'UPDATE', 'DELETE', 'REBASE', 'REVERT', 'CANCEL')),
    effect_start_time timestamptz NOT NULL,
    changed_fields    text[]      NOT NULL,              -- logical field names (rebase and revert)
    PRIMARY KEY (process_seq_id, entity_type, entity_id, version_no)
);
CREATE INDEX op_process_item_entity_idx ON op_process_item (entity_type, entity_id);

-- Output of an operation, written when it ends (idempotent replay, decision D4).
CREATE TABLE op_process_result (
    process_seq_id bigint PRIMARY KEY REFERENCES op_process,
    output         jsonb  NOT NULL                       -- sensitive fields masked
);

-- Every instance of a temporal entity, registered on its first insert. Other tables refer to temporal entities
-- through this table.
CREATE TABLE entity_registry (
    entity_id      uuid   PRIMARY KEY,
    entity_type    text   NOT NULL,
    created_seq_id bigint NOT NULL REFERENCES op_process
);

-- Rejects every UPDATE, DELETE and TRUNCATE (decision D5). The only exception is the controlled purge: a
-- transaction that ran SET LOCAL jabiz.maintenance_mode = 'purge' under a role that is a member of
-- jabiz_maintenance. The role is created by operations, never by migrations, and is not granted to the
-- application's account. The platform reports SQLSTATE JZ001 as a severe error: it means a defect.
CREATE FUNCTION jabiz_reject_mutation() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    IF current_setting('jabiz.maintenance_mode', true) = 'purge' THEN
        IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'jabiz_maintenance') THEN
            IF pg_has_role(current_user, 'jabiz_maintenance', 'MEMBER') THEN
                IF TG_LEVEL = 'ROW' THEN
                    IF TG_OP = 'DELETE' THEN
                        RETURN OLD;
                    END IF;
                    RETURN NEW;
                END IF;
                RETURN NULL;
            END IF;
        END IF;
    END IF;
    RAISE EXCEPTION 'jabiz: % on append-only table %.% is not allowed', TG_OP, TG_TABLE_SCHEMA, TG_TABLE_NAME
        USING ERRCODE = 'JZ001',
              HINT = 'Temporal and operation tables only accept INSERT (docs/design/09-decisions.md D5)';
END $$;

-- Installs both guard triggers on a table; business migrations call it for each temporal table:
--   SELECT jabiz_protect_append_only('t_price');
CREATE FUNCTION jabiz_protect_append_only(target regclass) RETURNS void
    LANGUAGE plpgsql AS $$
DECLARE
    table_name text := (SELECT relname FROM pg_class WHERE oid = target);
BEGIN
    EXECUTE format('CREATE TRIGGER %I BEFORE UPDATE OR DELETE ON %s FOR EACH ROW '
                   || 'EXECUTE FUNCTION jabiz_reject_mutation()', table_name || '_append_only', target);
    EXECUTE format('CREATE TRIGGER %I BEFORE TRUNCATE ON %s FOR EACH STATEMENT '
                   || 'EXECUTE FUNCTION jabiz_reject_mutation()', table_name || '_no_truncate', target);
END $$;

SELECT jabiz_protect_append_only('op_process');
SELECT jabiz_protect_append_only('op_process_item');
SELECT jabiz_protect_append_only('op_process_result');
SELECT jabiz_protect_append_only('entity_registry');

-- Time-ordered UUID (RFC 9562 version 7) for rows created in SQL, such as seeded dictionary items; the
-- application issues its own (UuidV7Generator).
CREATE FUNCTION jabiz_uuid_v7() RETURNS uuid
    LANGUAGE sql VOLATILE AS $$
SELECT encode(
    set_bit(set_bit(
        overlay(uuid_send(gen_random_uuid())
                placing substring(int8send(floor(extract(epoch FROM clock_timestamp()) * 1000)::bigint) FROM 3)
                FROM 1 FOR 6),
        52, 1), 53, 1),
    'hex')::uuid
$$;
