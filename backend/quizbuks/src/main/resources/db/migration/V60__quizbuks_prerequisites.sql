-- Q76 (docs/quizbuks/plans/Q76-prerequisites.md): a publication may require that the taker first passed, or answered,
-- another publication of the same sponsor (the edges of the "tech tree"). An ordinary table: a draft's prerequisites
-- are replaced while it is a draft (QB_PUBLICATION_PREREQUISITES_SAVE) and frozen with it once submitted; the copy
-- carries them (pointing at the same required publications). Whether a taker meets them is read from their own
-- attempts (qb_attempt_once) by the feed, the publication's page and the start.
CREATE TABLE qb_publication_prerequisite (
    prerequisite_id         varchar(36)  PRIMARY KEY,
    publication_id          uuid         NOT NULL REFERENCES entity_registry (entity_id),
    publisher_kind          varchar(20)  NOT NULL CHECK (publisher_kind IN ('SPONSOR', 'SHOPIFY_SHOP')),
    publisher_id            varchar(128) NOT NULL,
    required_publication_id uuid         NOT NULL REFERENCES entity_registry (entity_id),
    requirement             varchar(10)  NOT NULL CHECK (requirement IN ('PASSED', 'ANSWERED')),
    version                 bigint       NOT NULL,
    CONSTRAINT uk_qb_publication_prerequisite UNIQUE (publication_id, required_publication_id),
    CONSTRAINT ck_qb_publication_prerequisite_self CHECK (publication_id <> required_publication_id)
);
-- The sponsor's chain (the cycle check reads every edge of the sponsor's), and "which publications require this one".
CREATE INDEX qb_publication_prerequisite_publisher_idx ON qb_publication_prerequisite (publisher_id, required_publication_id);
