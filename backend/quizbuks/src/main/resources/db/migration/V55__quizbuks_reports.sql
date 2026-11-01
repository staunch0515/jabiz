-- Q49a (docs/quizbuks/plans/Q49a-contest-report.md; Apple App Review Guideline 1.2): takers' reports of publications.
-- An ordinary table: a report is written once, then only its handling changes (status, by whom, when, a note).
CREATE TABLE qb_report (
    report_id      varchar(36)   PRIMARY KEY,
    publication_id uuid          NOT NULL REFERENCES entity_registry (entity_id),
    publisher_kind varchar(20)   NOT NULL CHECK (publisher_kind IN ('SPONSOR', 'SHOPIFY_SHOP')),
    publisher_id   varchar(128)  NOT NULL,
    reporter_id    varchar(128)  NOT NULL,
    reason         varchar(20)   NOT NULL
        CHECK (reason IN ('MISLEADING', 'INAPPROPRIATE', 'REWARD_NOT_GIVEN', 'SPAM', 'OTHER')),
    note           varchar(500),
    status         varchar(10)   NOT NULL CHECK (status IN ('OPEN', 'DISMISSED', 'ACTIONED')),
    created_at     timestamptz   NOT NULL,
    resolved_at    timestamptz,
    resolved_by    varchar(128),
    resolution     varchar(500),
    version        bigint        NOT NULL,
    CONSTRAINT qb_report_resolved CHECK ((status = 'OPEN') = (resolved_at IS NULL))
);

-- A publication's open reports (the reviewers' list and the resolution), a taker's reports of a day (the limit), a
-- sponsor's (an operator's scope).
CREATE INDEX qb_report_publication_idx ON qb_report (publication_id, status);
CREATE INDEX qb_report_reporter_idx ON qb_report (reporter_id, created_at);
CREATE INDEX qb_report_publisher_idx ON qb_report (publisher_id);
