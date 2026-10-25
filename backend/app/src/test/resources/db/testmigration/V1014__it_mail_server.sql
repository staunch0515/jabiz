-- A mail server of the application's (com.jabiz.app.it.fixture.ItMailFixtures; ROADMAP phase 16zg, decision D56):
-- the settings a MailSenderSource hands to the platform, with the password sealed (SealSecret).
CREATE TABLE it_mail_server (
    f_key       varchar(64)  PRIMARY KEY,
    f_host      varchar(253) NOT NULL,
    f_port      integer      NOT NULL,
    f_tls       varchar(10)  NOT NULL,
    f_user      varchar(200) NOT NULL,
    f_secret    text,
    f_from      varchar(320) NOT NULL,
    f_from_name varchar(100),
    f_verified  boolean      NOT NULL DEFAULT false,
    f_version   bigint       NOT NULL DEFAULT 1
);
