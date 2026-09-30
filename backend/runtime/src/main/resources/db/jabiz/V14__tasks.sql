-- Tasks and notifications (docs/design/18-numbering-approvals-tasks.md section 5; decision D23).

-- Where a user is notified. Optional; the column of a temporal table, so every change is a new version.
ALTER TABLE sec_user_version ADD COLUMN email varchar(320);

-- The helper of V6 (dropped there, dropped again below): creates one temporal table with the system columns, the
-- version constraint and the lookup indexes of docs/design/04-temporal-append-only.md section 2.1.
CREATE FUNCTION jabiz_create_temporal_table(p_table text, p_key text, p_columns text) RETURNS void
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

-- Something a user (or every holder of a permission) has to do.
SELECT jabiz_create_temporal_table('sys_task_version', 'task_id', '
    task_type           varchar(100)  NOT NULL,
    title_key           varchar(200)  NOT NULL,
    title_params        varchar(4000) NOT NULL,
    assignee_user_id    varchar(100),
    assignee_permission varchar(200),
    subject_entity      varchar(100),
    subject_id          varchar(100),
    link                varchar(500),
    status              varchar(20)   NOT NULL,
    due_time            timestamptz,
    source_key          varchar(200),
    closed_by           varchar(100),
    CHECK ((assignee_user_id IS NULL) <> (assignee_permission IS NULL))');
CREATE INDEX sys_task_version_source_idx ON sys_task_version (source_key);
CREATE INDEX sys_task_version_assignee_idx ON sys_task_version (assignee_user_id, assignee_permission);

DROP FUNCTION jabiz_create_temporal_table(text, text, text);

-- One message to one recipient about one task, written in the process that decided to send it.
CREATE TABLE sys_notification (
    notification_id varchar(36)   PRIMARY KEY,
    task_id         uuid          NOT NULL REFERENCES entity_registry (entity_id),
    channel         varchar(20)   NOT NULL,
    recipient_id    varchar(100)  NOT NULL,
    address         varchar(320)  NOT NULL,
    subject         varchar(300)  NOT NULL,
    body            varchar(4000) NOT NULL,
    created_time    timestamptz   NOT NULL,
    process_seq_id  bigint        NOT NULL REFERENCES op_process (process_seq_id),
    version         bigint        NOT NULL
);
CREATE INDEX sys_notification_task_idx ON sys_notification (task_id);
CREATE INDEX sys_notification_process_idx ON sys_notification (process_seq_id);
SELECT jabiz_protect_append_only('sys_notification');

-- Every attempt to send a notification, after the commit; a notification is sent when it has a SENT attempt.
CREATE TABLE sys_notification_attempt (
    notification_id varchar(36)   NOT NULL REFERENCES sys_notification (notification_id),
    attempt_no      integer       NOT NULL,
    outcome         varchar(10)   NOT NULL CHECK (outcome IN ('SENT', 'FAILED')),
    error           varchar(2000),
    attempted_time  timestamptz   NOT NULL,
    PRIMARY KEY (notification_id, attempt_no)
);
SELECT jabiz_protect_append_only('sys_notification_attempt');
