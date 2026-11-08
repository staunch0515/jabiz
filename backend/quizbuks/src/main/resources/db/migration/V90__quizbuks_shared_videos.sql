-- F-S12 (docs/quizbuks/plans/impl/F-S12.md): videos sponsors share with other sponsors. The number is temporary
-- (V80-V84 are taken by branches in flight): renumbered to the highest on 1.3/quizbuks + 1 before merging.

-- A shared video: copied from the provider's video link (its library's asset) when shared, with its duration,
-- orientation, title in every language and topics (codes of the word list, F-S9). What was shared never changes:
-- the provider withdraws and shares again. Reviewed once by the provider's operator (the platform's approval); an
-- ordinary table written by the QB_SHARED_VIDEO_* processes only, every change audited by the platform.
CREATE TABLE qb_shared_video (
    shared_video_id      varchar(36)   PRIMARY KEY,
    asset_id             varchar(36)   NOT NULL,
    provider_id          varchar(128)  NOT NULL,
    url                  varchar(2000) NOT NULL,
    duration_sec         numeric(5, 0) NOT NULL,
    vertical             boolean       NOT NULL,
    title                jsonb         NOT NULL,
    topics               varchar(2460) NOT NULL,
    status               varchar(16)   NOT NULL,
    approval_request_id  varchar(64),
    shared_at            timestamptz   NOT NULL,
    approved_at          timestamptz,
    reject_reason        varchar(500),
    withdrawn_at         timestamptz,
    taken_down_at        timestamptz,
    takedown_reason      varchar(500),
    takedown_operator_id varchar(36),
    revision             numeric(9, 0) NOT NULL,
    edited_at            timestamptz   NOT NULL,
    version              bigint        NOT NULL,
    CONSTRAINT qb_shared_video_status_check CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'WITHDRAWN',
        'TAKEN_DOWN'))
);
-- One share of an asset stands at a time (in review or approved): the share checks it under the provider's lock, the
-- index makes it so.
CREATE UNIQUE INDEX ux_qb_shared_video_standing ON qb_shared_video (asset_id) WHERE status IN ('PENDING', 'APPROVED');
-- The provider's shares (their page; an operator's scope), and the catalog of the approved ones.
CREATE INDEX qb_shared_video_provider_idx ON qb_shared_video (provider_id);
CREATE INDEX qb_shared_video_status_idx ON qb_shared_video (status, approved_at DESC);

-- The shared video a publication picked (QB_PUBLICATION_SHARED_VIDEO_SET, while a draft; frozen with the policy).
-- Append-only: a new column only (platform 21 §2, D58).
ALTER TABLE qb_publication_version ADD COLUMN shared_video_id varchar(36);

-- The card freezes it and its provider (for blocking, F-S8, and statistics) when the publication goes on; the job
-- qb.shared-video-ends finds the cards of a video taken down through the partial index.
ALTER TABLE qb_publication_card ADD COLUMN shared_video_id varchar(36);
ALTER TABLE qb_publication_card ADD COLUMN video_provider_id varchar(128);
CREATE INDEX qb_publication_card_shared_video_idx ON qb_publication_card (shared_video_id)
    WHERE shared_video_id IS NOT NULL;
