-- Q51 (docs/quizbuks/plans/Q51-publication-versions.md): a publication that went on may switch to another version of
-- its quiz, after a review, while it keeps running on its version; one switch in review at a time.

-- The switch in review (cleared when it is made, rejected or withdrawn) and the reason of the last rejected one. The
-- table is sealed (platform 21 §2): columns are only added.
ALTER TABLE qb_publication_version
    ADD COLUMN pending_version_id    uuid,
    ADD COLUMN pending_version_label varchar(20),
    ADD COLUMN pending_request_id    varchar(64),
    ADD COLUMN pending_since         timestamptz,
    ADD COLUMN switch_reject_reason  varchar(500),
    ADD COLUMN switch_reject_code    varchar(64);

-- The versions a publication ran on before it switched: their attempts and results still read them, so removing the
-- quiz keeps their files. Inserted once per switch, never changed; found by quiz.
CREATE TABLE qb_publication_past_version (
    past_id        varchar(36)  PRIMARY KEY,
    publication_id uuid         NOT NULL,
    quiz_id        uuid         NOT NULL,
    version_id     uuid         NOT NULL,
    replaced_at    timestamptz  NOT NULL,
    version        bigint       NOT NULL
);
CREATE INDEX qb_publication_past_version_quiz_idx ON qb_publication_past_version (quiz_id);
CREATE INDEX qb_publication_past_version_publication_idx ON qb_publication_past_version (publication_id);
