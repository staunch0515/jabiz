-- Q7a (docs/quizbuks/plans/Q7-messages-legal.md D-Q7-2): when a user was last mailed a notice of a kind that may come
-- often, so that they get at most one a day: a sponsor's "short of funds" (one mail names every publication of theirs
-- short of funds; one that comes within the day marks the row pending, and the hourly job qb.mail-funds-short-due
-- sends it once the day has passed) and a taker's "payout account needs information". An ordinary table, written only
-- by the mail processes under the lock qb.mail-notice:<user>; one row per user and kind.
-- V12 is Q7b's (terms and help), merged before this one.
CREATE TABLE qb_mail_notice (
    notice_id    varchar(36)  PRIMARY KEY,
    recipient_id varchar(128) NOT NULL,
    kind         varchar(30)  NOT NULL,
    last_sent_at timestamptz  NOT NULL,
    pending      boolean      NOT NULL,
    version      bigint       NOT NULL,
    CONSTRAINT uk_qb_mail_notice UNIQUE (recipient_id, kind)
);
-- The hourly job's selection: the few pending rows.
CREATE INDEX qb_mail_notice_pending_idx ON qb_mail_notice (last_sent_at) WHERE pending;
