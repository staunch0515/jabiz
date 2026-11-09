-- F-S13 (docs/quizbuks/plans/impl/F-S13.md): the exposures of other sponsors' shared videos and the purchased
-- exposures. The number is temporary (V95–V97 are taken by #561, S2's c2b and F-S8): renumbered to the highest on
-- 1.3/quizbuks + 1 before merging.

-- One row per publication and taker, made by the first report about it (QB_EXPOSURE_REPORT only, every change audited
-- by the platform): a push opened in the vertical mode, the exposure counted (where, how long, timed how), going on to
-- answer, and the days the daily cap refused it. An ordinary table, never written under a publication's, start or
-- wallet lock; ids only (the publication's as text, as a block's object; no foreign keys).
CREATE TABLE qb_exposure (
    exposure_id       varchar(36)   PRIMARY KEY,
    publication_id    varchar(36)   NOT NULL,
    user_id           varchar(128)  NOT NULL,
    publisher_id      varchar(128)  NOT NULL,
    provider_id       varchar(128),
    opened_at         timestamptz,
    counted_at        timestamptz,
    surface           varchar(10)   CHECK (surface IN ('VERTICAL', 'START')),
    counted_sec       numeric(6, 0),
    timer             varchar(10)   CHECK (timer IN ('PLAYER', 'SCREEN')),
    went_to_answer_at timestamptz,
    capped_days       numeric(6, 0) NOT NULL DEFAULT 0,
    last_capped_on    date,
    created_at        timestamptz   NOT NULL,
    version           bigint        NOT NULL,
    -- One row per publication and taker (at most one exposure each); its index also counts a publication's successes
    -- (the minute's job, the figures, the reconciliation).
    CONSTRAINT qb_exposure_one UNIQUE (publication_id, user_id)
);
-- A taker's rows: the report's rows of a batch and the exposures of the day (the daily cap), and the feed's opened.
CREATE INDEX qb_exposure_user_idx ON qb_exposure (user_id, counted_at);
-- By publisher: the operators' scope and the sponsors' figures.
CREATE INDEX qb_exposure_publisher_idx ON qb_exposure (publisher_id);
-- The accounts the daily cap refused (the operators' page of suspicious inflation).
CREATE INDEX qb_exposure_capped_idx ON qb_exposure (user_id) WHERE capped_days > 0;

-- The exposures bought for another's shared video (F-M8 buys them through QB_EXPOSURE_PURCHASED_SET); none: unlimited.
-- The publications' table is append-only and sealed: a column is only added.
ALTER TABLE qb_publication_version ADD COLUMN exposure_purchased numeric(9, 0);
-- Copied to the card when it goes on and kept with it by the same sub-process; the minute's job reads the cards that
-- have one.
ALTER TABLE qb_publication_card ADD COLUMN exposure_purchased numeric(9, 0);
CREATE INDEX qb_publication_card_exposure_idx ON qb_publication_card (publication_id)
    WHERE exposure_purchased IS NOT NULL;
