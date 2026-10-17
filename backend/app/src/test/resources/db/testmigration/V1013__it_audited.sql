-- A plain entity with a secret, an audit-masked and a database-dictionary field that publishes its changes
-- (com.jabiz.app.it.fixture.ItBudgetFixtures; ROADMAP phase 16p, decision D46): its audit records and events are
-- compared between an update written in one statement and one written statement by statement.
CREATE TABLE it_audited (
    f_id      varchar(64) PRIMARY KEY,
    f_note    text,
    f_secret  text,
    f_file    text,
    f_kind    varchar(100),
    f_version bigint      NOT NULL DEFAULT 1
);

-- The dictionary of the kind starts with one item; the tests add theirs in the commits that use them.
INSERT INTO sys_dict_item (dict_urn, item_code, labels, sort_order) VALUES
    ('urn:jabiz:dict:it_audited_kind', 'PLAIN', '{"zh": "普通", "ja": "通常", "en": "Plain"}', 1);
