-- Transactional mail (docs/design/18-numbering-approvals-tasks.md section 5.6; decision D35).

-- The language of a user's mail; optional (the platform's default language). A column of a temporal table, so every
-- change is a new version.
ALTER TABLE sec_user_version ADD COLUMN locale varchar(10);

-- One mail a process decided to send, written in its transaction (step SendMail): the platform entity MailMessage.
-- The parameters are kept as JSON text with the sensitive ones masked; the one-time tokens are not among them; they
-- are drawn when the mail is sent.
CREATE TABLE sys_mail_message (
    message_id     uuid          PRIMARY KEY,
    template       varchar(100)  NOT NULL,
    category       varchar(20)   NOT NULL CHECK (category IN ('TRANSACTIONAL', 'NOTIFICATION')),
    user_id        uuid,
    address        varchar(320)  NOT NULL,
    locale         varchar(10)   NOT NULL,
    params         text          NOT NULL,
    created_time   timestamptz   NOT NULL,
    process_seq_id bigint        NOT NULL REFERENCES op_process (process_seq_id),
    version        bigint        NOT NULL
);
CREATE INDEX sys_mail_message_user_idx ON sys_mail_message (user_id, created_time);
CREATE INDEX sys_mail_message_template_idx ON sys_mail_message (template, created_time);
CREATE INDEX sys_mail_message_process_idx ON sys_mail_message (process_seq_id);
SELECT jabiz_protect_append_only('sys_mail_message');

-- Every attempt to send a message (MAIL_SEND): SENT and SKIPPED in the delivery's transaction, FAILED in one of its
-- own, since the delivery rolls back. A message is done once it has a SENT or SKIPPED attempt. attempt_id is the key of
-- the platform entity MailAttempt.
CREATE TABLE sys_mail_attempt (
    message_id     uuid          NOT NULL REFERENCES sys_mail_message (message_id),
    attempt_no     integer       NOT NULL,
    attempt_id     uuid          NOT NULL UNIQUE,
    outcome        varchar(10)   NOT NULL CHECK (outcome IN ('SENT', 'FAILED', 'SKIPPED')),
    detail         varchar(2000),
    attempted_time timestamptz   NOT NULL,
    PRIMARY KEY (message_id, attempt_no)
);
SELECT jabiz_protect_append_only('sys_mail_attempt');

-- One-time tokens, drawn when a message is sent: only their SHA-256 is kept, the token itself is only in the mail. A
-- token is valid until it expires, while no later token of the same purpose went to the same recipient (issue_seq
-- orders them), and until used.
CREATE TABLE sys_mail_token (
    token_hash   char(64)     PRIMARY KEY,
    issue_seq    bigint       GENERATED ALWAYS AS IDENTITY UNIQUE,
    message_id   uuid         NOT NULL REFERENCES sys_mail_message (message_id),
    attempt_no   integer      NOT NULL,
    purpose      varchar(40)  NOT NULL,
    user_id      uuid,
    address      varchar(320) NOT NULL,
    issued_time  timestamptz  NOT NULL,
    expires_time timestamptz  NOT NULL
);
CREATE INDEX sys_mail_token_user_idx ON sys_mail_token (purpose, user_id, issue_seq);
CREATE INDEX sys_mail_token_address_idx ON sys_mail_token (purpose, address, issue_seq);
SELECT jabiz_protect_append_only('sys_mail_token');

-- The use of a token, whose primary key allows it once.
CREATE TABLE sys_mail_token_use (
    token_hash     char(64)    PRIMARY KEY REFERENCES sys_mail_token (token_hash),
    used_time      timestamptz NOT NULL,
    process_seq_id bigint      NOT NULL REFERENCES op_process (process_seq_id)
);
SELECT jabiz_protect_append_only('sys_mail_token_use');

-- Whether a user receives the notifications of a template: the temporal platform entity SecUserMailPreference.
CREATE TABLE sec_user_mail_preference_version (
    row_id                  bigint       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_mail_preference_id uuid         NOT NULL REFERENCES entity_registry (entity_id),
    version_no              integer      NOT NULL,
    effect_start_time       timestamptz  NOT NULL,
    created_time            timestamptz  NOT NULL,
    process_seq_id          bigint       NOT NULL REFERENCES op_process (process_seq_id),
    is_deleted              boolean      NOT NULL DEFAULT false,
    user_id                 uuid         NOT NULL REFERENCES entity_registry (entity_id),
    template                varchar(100) NOT NULL,
    subscribed              boolean      NOT NULL,
    CONSTRAINT sec_user_mail_preference_version_uk UNIQUE (user_mail_preference_id, version_no)
);
CREATE INDEX sec_user_mail_preference_version_current_idx
    ON sec_user_mail_preference_version (user_mail_preference_id, effect_start_time DESC, version_no DESC);
CREATE INDEX sec_user_mail_preference_version_process_idx ON sec_user_mail_preference_version (process_seq_id);
CREATE INDEX sec_user_mail_preference_version_user_idx ON sec_user_mail_preference_version (user_id, template);
SELECT jabiz_protect_append_only('sec_user_mail_preference_version');
