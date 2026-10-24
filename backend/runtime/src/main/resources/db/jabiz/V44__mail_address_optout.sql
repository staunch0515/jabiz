-- An address that opted out of a notification template through the link of a mail sent to it without a user
-- (docs/design/18-numbering-approvals-tasks.md section 5.6; decision D55): notifications may now go to plain
-- addresses (an application's invitations, say), and such a mail's unsubscribe link stands for the address. One row per
-- address (lower case) and template, written by SEC_MAIL_ADDRESS_OPT_OUT only; the delivery (MAIL_SEND) skips the
-- template's notifications to the address while the row exists. opt_out_id is the key of the platform entity
-- MailAddressOptOut; an ordinary table (the opt-out of a user is their preference, sec_user_mail_preference_version).
CREATE TABLE sys_mail_address_optout (
    opt_out_id     varchar(36)  PRIMARY KEY,
    address        varchar(320) NOT NULL,
    template       varchar(100) NOT NULL,
    opted_out_time timestamptz  NOT NULL,
    version        bigint       NOT NULL,
    CONSTRAINT uk_sys_mail_address_optout UNIQUE (address, template)
);
