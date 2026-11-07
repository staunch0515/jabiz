-- The mail layout administrators write (decision D72; 17zh-b): the temporal platform entity MailLayout, one per
-- language. A version of kind CUSTOM replaces the default layout (configuration jabiz.mail.layout.<language>.*); a
-- version of kind DEFAULT goes back to it (its parts empty). default_hash is the SHA-256 of the default the version was
-- written against, so that a changed default can be pointed out. Versions are only inserted (decision D5).
CREATE TABLE sys_mail_layout_version (
    row_id            bigint        GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    mail_layout_id    uuid          NOT NULL REFERENCES entity_registry (entity_id),
    version_no        integer       NOT NULL,
    effect_start_time timestamptz   NOT NULL,
    created_time      timestamptz   NOT NULL,
    process_seq_id    bigint        NOT NULL REFERENCES op_process (process_seq_id),
    is_deleted        boolean       NOT NULL DEFAULT false,
    locale            varchar(10)   NOT NULL,
    layout_kind       varchar(10)   NOT NULL CHECK (layout_kind IN ('CUSTOM', 'DEFAULT')),
    display_name      text,
    logo_url          text,
    logo_alt          text,
    footer            text,
    default_hash      varchar(64)   NOT NULL,
    CONSTRAINT sys_mail_layout_version_uk UNIQUE (mail_layout_id, version_no)
);
CREATE INDEX sys_mail_layout_version_current_idx
    ON sys_mail_layout_version (mail_layout_id, effect_start_time DESC, version_no DESC);
CREATE INDEX sys_mail_layout_version_process_idx ON sys_mail_layout_version (process_seq_id);
-- The delivery reads the layout of a language, also a given version of it; the unique locale is checked through it.
CREATE INDEX sys_mail_layout_version_key_idx ON sys_mail_layout_version (locale, version_no DESC);
SELECT jabiz_protect_append_only('sys_mail_layout_version');

-- Which layout a mail was sent on: the version of the MailLayout used, null for the default (decision D72 item 4).
ALTER TABLE sys_mail_attempt ADD COLUMN layout_version_no integer;
ALTER TABLE sys_notification ADD COLUMN layout_version_no integer;
ALTER TABLE sys_document_delivery ADD COLUMN layout_version_no integer;

-- One instance per language, and per template and language: the unique keys of MailLayout and MailText are checked by
-- the platform before it writes, and these indexes refuse a second instance two concurrent first writes would create
-- (only one can be version 1).
CREATE UNIQUE INDEX sys_mail_layout_version_one_idx ON sys_mail_layout_version (locale, version_no);
CREATE UNIQUE INDEX sys_mail_text_version_one_idx ON sys_mail_text_version (template, locale, version_no);
