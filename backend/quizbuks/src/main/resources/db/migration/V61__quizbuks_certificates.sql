-- Q77 (docs/quizbuks/plans/Q77-certificates.md): certificates of the certifications people hold and the public page
-- that lists the issued ones. A certificate is issued once per holding (its end, D-Q77-2); the PDF is the platform's
-- document run whose subject is the row, so no run id is kept here. The public page has a name and is off by default
-- (D-Q77-5); nothing is backfilled.
ALTER TABLE qb_user_settings ADD COLUMN public_handle varchar(30);
ALTER TABLE qb_user_settings ADD COLUMN public_page boolean NOT NULL DEFAULT false;
-- Unset names are null, which a unique constraint does not compare.
ALTER TABLE qb_user_settings ADD CONSTRAINT uk_qb_user_settings_public_handle UNIQUE (public_handle);

CREATE TABLE qb_certificate (
    certificate_id        varchar(36)   PRIMARY KEY,
    user_id               varchar(128)  NOT NULL,
    certification         varchar(40)   NOT NULL,
    certification_version numeric(4, 0) NOT NULL,
    held_from             timestamptz   NOT NULL,
    valid_until           timestamptz   NOT NULL,
    holder_name           varchar(100)  NOT NULL,
    verify_key            varchar(32)   NOT NULL,
    language              varchar(10)   NOT NULL,
    issued_at             timestamptz   NOT NULL,
    version               bigint        NOT NULL,
    CONSTRAINT uk_qb_certificate_holding UNIQUE (user_id, certification, valid_until),
    CONSTRAINT uk_qb_certificate_key UNIQUE (verify_key)
);
CREATE INDEX qb_certificate_issued_idx ON qb_certificate (issued_at);
