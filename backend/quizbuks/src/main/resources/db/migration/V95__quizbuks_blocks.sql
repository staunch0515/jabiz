-- F-S8 (docs/quizbuks/plans/impl/F-S8.md): a taker's blocks. The number is temporary (the base's highest is V92):
-- renumbered to the highest on 1.3/quizbuks + 1 before merging.

-- One row a blocked object of a taker's: a publication, a quiz (its later publications too), a sponsor or a video
-- provider (the shared video a card froze, F-S12). An ordinary table written by QB_BLOCK_ADD / QB_BLOCK_REMOVE only,
-- every change audited by the platform; no foreign keys (user ids and the objects' ids are text, as qb_conversation_block).
CREATE TABLE qb_block (
    block_id    varchar(36)   PRIMARY KEY,
    user_id     varchar(128)  NOT NULL,
    kind        varchar(16)   NOT NULL CHECK (kind IN ('PUBLICATION', 'QUIZ', 'SPONSOR', 'PROVIDER')),
    object_id   varchar(128)  NOT NULL,
    created_at  timestamptz   NOT NULL,
    version     bigint        NOT NULL,
    -- One row per (user, object); its index is also the one by user (the feed's condition, the user's list).
    CONSTRAINT qb_block_one UNIQUE (user_id, kind, object_id)
);
-- By object: a sponsor's rounded count of blockers and headquarters' figures.
CREATE INDEX qb_block_object_idx ON qb_block (kind, object_id);

-- The card freezes its quiz when the publication goes on (a switch of version stays within the quiz), so that blocking
-- a quiz covers its later publications. Filled from the card's version for the cards there are (every card has one;
-- the column stays nullable all the same, so that a card whose version row is missing, only ever made by hand, does
-- not stop the migration: such a card cannot be blocked by its quiz).
ALTER TABLE qb_publication_card ADD COLUMN quiz_id varchar(36);
UPDATE qb_publication_card c SET quiz_id = CAST(v.quiz_id AS varchar(36))
FROM qb_quiz_version_version v
WHERE v.version_id = c.version_id;
CREATE INDEX qb_publication_card_quiz_idx ON qb_publication_card (quiz_id);
