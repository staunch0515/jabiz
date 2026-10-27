-- Matching a publication's audience by the takers' profiles (docs/quizbuks/plans/Q39-audience.md). Both tables are
-- ordinary: a taker may clear what they told, and a draft's conditions are replaced while it is a draft.

-- What a taker told of themself, one row per registered criterion and option (several for a criterion that takes
-- several, the interests), or one number (the birth year of the criterion "age"). Written by
-- QB_PROFILE_ATTRIBUTES_SAVE only; never shown to a sponsor, only counted.
CREATE TABLE qb_profile_attribute (
    attribute_id  varchar(36)   PRIMARY KEY,
    user_id       varchar(128)  NOT NULL,
    criterion_key varchar(32)   NOT NULL,
    value_code    varchar(32),
    value_number  numeric(6, 0),
    version       bigint        NOT NULL,
    CHECK ((value_code IS NULL) <> (value_number IS NULL)),
    CONSTRAINT uk_qb_profile_attribute UNIQUE (user_id, criterion_key, value_code)
);
CREATE INDEX qb_profile_attribute_user_idx ON qb_profile_attribute (user_id, criterion_key);
-- The audience count reads the takers by option or by number.
CREATE INDEX qb_profile_attribute_code_idx ON qb_profile_attribute (criterion_key, value_code, user_id)
    WHERE value_code IS NOT NULL;
CREATE INDEX qb_profile_attribute_number_idx ON qb_profile_attribute (criterion_key, value_number, user_id)
    WHERE value_number IS NOT NULL;

-- A publication's conditions: one row per option of an option criterion (the options of one criterion are
-- alternatives), or one row with the bounds of a range criterion; the criteria of a publication all hold. Written by
-- QB_PUBLICATION_AUDIENCE_SAVE (a draft's) and the copy; frozen with the publication once it is submitted.
-- measure says how a range is read from the profile's number: VALUE as it is, YEARS_SINCE as the years since it
-- (an age from a birth year), so the templates need no criterion's name.
CREATE TABLE qb_publication_criterion (
    criterion_id   varchar(36)  PRIMARY KEY,
    publication_id uuid         NOT NULL REFERENCES entity_registry (entity_id),
    publisher_kind varchar(20)  NOT NULL CHECK (publisher_kind IN ('SPONSOR', 'SHOPIFY_SHOP')),
    publisher_id   varchar(128) NOT NULL,
    criterion_key  varchar(32)  NOT NULL,
    value_code     varchar(32),
    min_value      numeric(6, 0),
    max_value      numeric(6, 0),
    measure        varchar(12)  CHECK (measure IN ('VALUE', 'YEARS_SINCE')),
    version        bigint       NOT NULL,
    CHECK ((value_code IS NULL) = (min_value IS NOT NULL AND max_value IS NOT NULL AND measure IS NOT NULL)),
    CHECK (min_value IS NULL OR min_value <= max_value),
    CONSTRAINT uk_qb_publication_criterion UNIQUE (publication_id, criterion_key, value_code)
);
CREATE INDEX qb_publication_criterion_publication_idx ON qb_publication_criterion (publication_id, criterion_key);
CREATE INDEX qb_publication_criterion_publisher_idx ON qb_publication_criterion (publisher_id);
