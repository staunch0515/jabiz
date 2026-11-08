-- An ordinary table keeping two sealed values in one row (a shop's access and refresh tokens): the key rotation stores
-- both again in one update of the row (ROADMAP 17zz4).
CREATE TABLE it_sealed_pair (
    pair_id       varchar(36)  PRIMARY KEY,
    sealed_access text         NOT NULL,
    sealed_refresh text        NOT NULL,
    f_version     bigint       NOT NULL DEFAULT 1
);
