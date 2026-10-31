-- A sponsor's products (docs/quizbuks/plans/Q60-sponsor-page.md, Q60b), shown on their published page and with their
-- quizzes, written by the QB_PRODUCT_* processes only (the history is the platform's audit). The price is the shop's,
-- shown as it is (no decimals for the yen, two for the others; QB_PRODUCT_SAVE checks them).
CREATE TABLE qb_sponsor_product (
    product_id           varchar(36)   PRIMARY KEY,
    user_id              varchar(128)  NOT NULL,
    name                 varchar(100)  NOT NULL,
    description          text,
    image                uuid,
    price                numeric(12, 2) NOT NULL,
    currency             varchar(3)    NOT NULL,
    shop_url             varchar(300)  NOT NULL,
    status               varchar(16)   NOT NULL,
    seq                  numeric(4, 0) NOT NULL,
    taken_down_at        timestamptz,
    takedown_reason      text,
    takedown_operator_id varchar(36),
    edited_at            timestamptz   NOT NULL,
    revision             numeric(9, 0) NOT NULL,
    version              bigint        NOT NULL
);
CREATE INDEX ix_qb_sponsor_product_user ON qb_sponsor_product (user_id, seq);

-- Which products a quiz shows (question_no 0) and which one a question shows (by its number in whichever version is
-- played): not part of the quiz's versions, so a change applies to its publications at once.
CREATE TABLE qb_quiz_product (
    link_id     varchar(36)   PRIMARY KEY,
    quiz_id     varchar(36)   NOT NULL,
    question_no numeric(3, 0) NOT NULL,
    product_id  varchar(36)   NOT NULL,
    owner_id    varchar(128)  NOT NULL,
    seq         numeric(2, 0) NOT NULL,
    version     bigint        NOT NULL,
    CONSTRAINT uk_qb_quiz_product UNIQUE (quiz_id, question_no, product_id)
);
CREATE INDEX ix_qb_quiz_product_product ON qb_quiz_product (product_id);
CREATE INDEX ix_qb_quiz_product_owner ON qb_quiz_product (owner_id);
