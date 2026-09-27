-- Tables of the content-authoring fixtures (see com.jabiz.app.it.fixture.ItContentFixtures).
CREATE TABLE it_content_article (
    f_id        varchar(64) PRIMARY KEY,
    f_region    varchar(8)  NOT NULL,
    f_title     text        NOT NULL,
    f_headline  jsonb,
    f_status    varchar(16) NOT NULL,
    f_note      text,
    f_version   bigint      NOT NULL DEFAULT 1
);
