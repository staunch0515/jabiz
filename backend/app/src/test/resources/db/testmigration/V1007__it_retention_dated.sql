-- A retention fixture counted from a date field (docs/design/21-audit-retention.md section 3.1, ROADMAP 14h; see
-- ItRetentionFixtures): a plain entity kept a year from its voucher date.

CREATE TABLE it_voucher (
    f_id           varchar(64) PRIMARY KEY,
    f_title        text        NOT NULL,
    f_voucher_date date,
    f_version      bigint      NOT NULL DEFAULT 1
);
