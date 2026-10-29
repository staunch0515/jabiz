-- Operators' monthly settlements (docs/quizbuks/plans/Q48-operators.md, Q48b; D-Q48-6, D-Q48-7).

-- The operator a publication's revenue belongs to: its sponsor's operator when it went on (null: headquarters, and
-- every publication that went on before operators existed). Frozen with the rest of the card, so that the commission
-- of its rewards (4110, by the publication) is settled to the operator that ran the sponsor then, and the answering
-- path reads nothing more.
ALTER TABLE qb_publication_card ADD COLUMN operator_id varchar(36);
CREATE INDEX qb_publication_card_operator_idx ON qb_publication_card (operator_id) WHERE operator_id IS NOT NULL;

-- One operator's month (Japan's calendar month): the commission of its publications, the platform's share, the
-- prospecting cost deducted (Q48d) with what was carried in and out, and what is payable; ISSUED (posted 5130 / 2140
-- and its document issued), PAYING (finance recorded the payment, another administrator approves it), PAID (posted
-- 2140 / 1120). Written by the processes only.
CREATE TABLE qb_operator_settlement (
    settlement_id          varchar(36)     PRIMARY KEY,
    operator_id            varchar(36)     NOT NULL REFERENCES qb_operator (operator_id),
    month                  varchar(7)      NOT NULL,
    period_start           timestamptz     NOT NULL,
    period_end             timestamptz     NOT NULL,
    revenue                numeric(18, 0)  NOT NULL,
    platform_share         numeric(5, 4)   NOT NULL,
    operator_share         numeric(18, 0)  NOT NULL,
    prospecting_cost       numeric(18, 0)  NOT NULL,
    carried_in             numeric(18, 0)  NOT NULL,
    payable                numeric(18, 0)  NOT NULL,
    carried_out            numeric(18, 0)  NOT NULL,
    status                 varchar(20)     NOT NULL,
    document_no            varchar(100),
    document_run_id        varchar(36),
    issue_transaction_id   varchar(36),
    issued_at              timestamptz     NOT NULL,
    paid_on                date,
    bank_reference         varchar(100),
    payment_recorded_by    varchar(128),
    payment_recorded_at    timestamptz,
    approval_request_id    varchar(36),
    payment_transaction_id varchar(36),
    paid_at                timestamptz,
    revision               numeric(9, 0)   NOT NULL,
    version                bigint          NOT NULL,
    CONSTRAINT uk_qb_operator_settlement_month UNIQUE (operator_id, month),
    CONSTRAINT qb_operator_settlement_status_check CHECK (status IN ('ISSUED', 'PAYING', 'PAID')),
    CONSTRAINT qb_operator_settlement_payable_check CHECK (payable >= 0 AND carried_out >= 0)
);
CREATE INDEX qb_operator_settlement_status_idx ON qb_operator_settlement (status, month);
