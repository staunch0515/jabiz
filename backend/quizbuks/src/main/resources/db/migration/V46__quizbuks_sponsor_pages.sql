-- A sponsor's page in the app (docs/quizbuks/plans/Q60-sponsor-page.md, Q60a): one per sponsor, written by the
-- QB_SPONSOR_PAGE_* processes only (the history is the platform's audit). The handle is stored lowercase, so the
-- unique index makes it unique whatever the case it was typed in.
CREATE TABLE qb_sponsor_page (
    user_id             varchar(128) PRIMARY KEY,
    handle              varchar(30)  NOT NULL,
    language            varchar(2)   NOT NULL,
    headline            varchar(120) NOT NULL,
    story               text,
    logo                uuid,
    cover               uuid,
    website             varchar(300),
    status              varchar(16)  NOT NULL,
    approval_request_id varchar(64),
    submitted_at        timestamptz,
    published_at        timestamptz,
    reject_reason       text,
    taken_down_at       timestamptz,
    takedown_reason     text,
    review_required     boolean      NOT NULL DEFAULT false,
    edited_at           timestamptz  NOT NULL,
    revision            numeric(9, 0) NOT NULL,
    version             bigint       NOT NULL,
    CONSTRAINT ck_qb_sponsor_page_handle CHECK (handle = lower(handle))
);

CREATE UNIQUE INDEX uk_qb_sponsor_page_handle ON qb_sponsor_page (handle);
CREATE INDEX ix_qb_sponsor_page_status ON qb_sponsor_page (status);
