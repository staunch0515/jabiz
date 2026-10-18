-- Q7a (docs/quizbuks/plans/Q7-messages-legal.md D-Q7-2): when a publication's sponsor was last mailed a notice of a kind
-- that may come often ("short of funds"), so that they get at most one a day. An ordinary table, written only by
-- QB_PUBLICATION_NOTIFY under the lock qb.pub-notice:<publication>; one row per publication and kind.
-- V12 is Q7b's (terms and help), in review at the same time: an installation must not run this one before V12 exists
-- (Flyway refuses a lower version found after a higher one was applied), so Q7b is merged and deployed first (the
-- plan's implementation record).
CREATE TABLE qb_publication_notice (
    notice_id      varchar(36) PRIMARY KEY,
    publication_id varchar(36) NOT NULL,
    kind           varchar(20) NOT NULL,
    last_sent_at   timestamptz NOT NULL,
    version        bigint      NOT NULL,
    CONSTRAINT uk_qb_publication_notice UNIQUE (publication_id, kind)
);
