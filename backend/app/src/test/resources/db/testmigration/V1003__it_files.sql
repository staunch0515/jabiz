-- An ordinary (not temporal) entity with a file field (see com.jabiz.app.it.fixture.ItFileFixtures).
CREATE TABLE it_attachment (
    f_id        varchar(64)  PRIMARY KEY,
    f_title     varchar(100),
    f_document  uuid,
    f_version   bigint       NOT NULL DEFAULT 1
);
