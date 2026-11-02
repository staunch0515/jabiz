-- Q76 (docs/quizbuks/plans/Q76-prerequisites.md): a publication's prerequisites, the sponsor's own rule that a taker
-- first passes (PASSED) or answers (ANSWERED) another of the sponsor's publications. An ordinary table, replaced while
-- the publication is a draft (frozen once it is submitted), copied with it. The required publication is the sponsor's
-- own, one that was submitted (a draft has no id another can point at for good), never the publication itself.
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
-- The unique key serves "a publication's prerequisites"; this one the sponsor's chain (the cycle check).
CREATE INDEX qb_publication_prerequisite_publisher_idx ON qb_publication_prerequisite (publisher_id);
