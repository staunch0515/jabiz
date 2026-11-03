-- Q88a (docs/quizbuks/plans/Q88-sponsor-certifications-entry-fee.md, D-Q88-1): a sponsor's own certifications. A
-- sponsor's publication may be a round of one (the rounds, records, prerequisites and certificates keep it under the
-- key 'sp:<certification_id>', D-Q88-2: no column of theirs changes), and passing it lets a taker hold it for the
-- certification's validity. An ordinary table written by the QB_SPONSOR_CERTIFICATION_* processes only. The names
-- are multilingual ({language: text}), as the legal texts' titles.
CREATE TABLE qb_sponsor_certification (
    certification_id      varchar(36)   PRIMARY KEY,
    sponsor_id            varchar(128)  NOT NULL,
    name                  jsonb         NOT NULL,
    description           jsonb,
    validity_days         numeric(4, 0) NOT NULL,
    certification_version numeric(4, 0) NOT NULL,
    status                varchar(10)   NOT NULL,
    created_at            timestamptz   NOT NULL,
    updated_at            timestamptz   NOT NULL,
    version               bigint        NOT NULL,
    CONSTRAINT qb_sponsor_certification_status_check CHECK (status IN ('ACTIVE', 'RETIRED')),
    CONSTRAINT qb_sponsor_certification_validity_check CHECK (validity_days BETWEEN 30 AND 3650)
);
-- A sponsor's own, and the holding SQL's lookup of a round's certification by key is by the primary key.
CREATE INDEX qb_sponsor_certification_sponsor_idx ON qb_sponsor_certification (sponsor_id, status);
