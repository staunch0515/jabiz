-- F-M3 (docs/quizbuks/plans/impl/F-M.md §6): a Sponsor's company verification by the company's mail domain. One
-- submission is one row, kept with its outcome; an ordinary table written by the QB_SPONSOR_VERIFICATION_* processes
-- only, every change audited by the platform. The number is the next free one on 1.3/quizbuks when written: if
-- another branch takes it first, renumbered to the highest + 1 before merging.
CREATE TABLE qb_sponsor_verification (
    verification_id     varchar(36)   PRIMARY KEY,
    sponsor_id          varchar(128)  NOT NULL,
    company_name        varchar(200)  NOT NULL,
    domain              varchar(253)  NOT NULL,
    method              varchar(16)   NOT NULL,
    confirm_address     varchar(320),
    status              varchar(16)   NOT NULL,
    link_sent_at        timestamptz,
    confirmed_at        timestamptz,
    approval_request_id varchar(64),
    operator_id         varchar(36),
    submitted_at        timestamptz   NOT NULL,
    decided_at          timestamptz,
    verified_at         timestamptz,
    reject_reason       varchar(500),
    ended_at            timestamptz,
    revoke_reason       varchar(500),
    revoked_operator_id varchar(36),
    version             bigint        NOT NULL,
    CONSTRAINT qb_sponsor_verification_status_check CHECK (status IN ('LINK_SENT', 'REVIEW', 'APPROVED', 'REJECTED',
        'WITHDRAWN', 'SUPERSEDED', 'REVOKED')),
    CONSTRAINT qb_sponsor_verification_method_check CHECK (method IN ('ACCOUNT', 'LINK')),
    CONSTRAINT qb_sponsor_verification_link_check CHECK (method = 'ACCOUNT' OR confirm_address IS NOT NULL
        OR status NOT IN ('LINK_SENT', 'REVIEW'))
);
-- One domain is verified for one Sponsor only: the approval checks it under the domain's lock, the index makes it so.
CREATE UNIQUE INDEX ux_qb_sponsor_verification_domain ON qb_sponsor_verification (domain) WHERE status = 'APPROVED';
-- A Sponsor has at most one submission in progress or in effect: a new one ends what stood (under the Sponsor's lock).
CREATE UNIQUE INDEX ux_qb_sponsor_verification_standing ON qb_sponsor_verification (sponsor_id)
    WHERE status IN ('LINK_SENT', 'REVIEW', 'APPROVED');
-- A Sponsor's submissions (their page, an operator's scope); the review's list by status.
CREATE INDEX qb_sponsor_verification_sponsor_idx ON qb_sponsor_verification (sponsor_id, submitted_at DESC);
CREATE INDEX qb_sponsor_verification_status_idx ON qb_sponsor_verification (status, submitted_at DESC);
