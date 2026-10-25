-- A sponsor's own mail server (docs/quizbuks/plans/Q29-merchant-mail-server.md; platform decision D56): the SMTP
-- settings their invitations are sent through. One row per sponsor, written by QB_MAIL_SERVER_SET / _REMOVE only.
-- The password is stored sealed (SealedSecrets, JABIZ_SEAL_KEY), never as given. A server is used only while it is
-- verified: saving it sends a test mail to the sponsor's own address and records the outcome (verified_at, or
-- last_error with the server's reason).
CREATE TABLE qb_sponsor_mail_server (
    sponsor_id      varchar(128)  PRIMARY KEY,
    host            varchar(253)  NOT NULL,
    port            integer       NOT NULL CHECK (port BETWEEN 1 AND 65535),
    tls             varchar(10)   NOT NULL CHECK (tls IN ('SMTPS', 'STARTTLS')),
    username        varchar(200)  NOT NULL,
    sealed_password text,
    from_address    varchar(320)  NOT NULL,
    from_name       varchar(100),
    verified_at     timestamptz,
    tested_at       timestamptz,
    last_error      varchar(2000),
    version         bigint        NOT NULL
);
