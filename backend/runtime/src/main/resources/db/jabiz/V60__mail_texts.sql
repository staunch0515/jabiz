-- Mail texts administrators write (decision D72): the temporal platform entity MailText, one per template and
-- language. A version of kind CUSTOM overrides the default subject and body (the messages mail.<name>.*); a version of
-- kind DEFAULT goes back to them (subject and body empty). default_hash is the SHA-256 of the default the version was
-- written against, so that a changed default can be pointed out. Versions are only inserted (decision D5).
CREATE TABLE sys_mail_text_version (
    row_id            bigint        GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    mail_text_id      uuid          NOT NULL REFERENCES entity_registry (entity_id),
    version_no        integer       NOT NULL,
    effect_start_time timestamptz   NOT NULL,
    created_time      timestamptz   NOT NULL,
    process_seq_id    bigint        NOT NULL REFERENCES op_process (process_seq_id),
    is_deleted        boolean       NOT NULL DEFAULT false,
    template          varchar(100)  NOT NULL,
    locale            varchar(10)   NOT NULL,
    text_kind         varchar(10)   NOT NULL CHECK (text_kind IN ('CUSTOM', 'DEFAULT')),
    subject           text,
    body              text,
    default_hash      varchar(64)   NOT NULL,
    CONSTRAINT sys_mail_text_version_uk UNIQUE (mail_text_id, version_no)
);
CREATE INDEX sys_mail_text_version_current_idx
    ON sys_mail_text_version (mail_text_id, effect_start_time DESC, version_no DESC);
CREATE INDEX sys_mail_text_version_process_idx ON sys_mail_text_version (process_seq_id);
-- The delivery reads the text of a template and language; the unique (template, locale) is checked through it.
CREATE INDEX sys_mail_text_version_key_idx ON sys_mail_text_version (template, locale, version_no DESC);
SELECT jabiz_protect_append_only('sys_mail_text_version');

-- Which text a mail was sent with: the version of the MailText used, null for the default (decision D72 item 4).
-- Appending columns leaves the append-only protection as it is.
ALTER TABLE sys_mail_attempt ADD COLUMN text_version_no integer;
ALTER TABLE sys_notification ADD COLUMN text_version_no integer;
ALTER TABLE sys_document_delivery ADD COLUMN text_version_no integer;

-- A test mail (MAIL_TEXT_TEST_SEND) is rendered when it is queued, with sample values and no usable token: the
-- delivery sends it as it is, drawing no tokens. Null for every other mail, which is rendered when it is sent.
ALTER TABLE sys_mail_message ADD COLUMN rendered_subject text, ADD COLUMN rendered_text text,
    ADD COLUMN rendered_html text;
