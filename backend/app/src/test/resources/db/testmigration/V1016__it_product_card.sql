-- An ordinary table identified by a reference to an append-only entity (the product's card): its primary key is a
-- uuid column, the product's id. A public counter keyed on it binds the key as a uuid (ROADMAP 17zz2).
CREATE TABLE it_product_card (
    product_id uuid        PRIMARY KEY,
    title      varchar(100) NOT NULL,
    f_version  bigint      NOT NULL DEFAULT 1
);
