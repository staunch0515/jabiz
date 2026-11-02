-- Q82 (docs/quizbuks/plans/Q82-conversations.md): conversations between a taker and the sponsor of a publication they
-- answered (or referred people to). Ordinary tables written by the processes only: a conversation changes (its last
-- message, who read it when, closed or open), its messages are only inserted, a block is added or removed.
CREATE TABLE qb_conversation (
    conversation_id  varchar(36)   PRIMARY KEY,
    publisher_kind   varchar(20)   NOT NULL CHECK (publisher_kind IN ('SPONSOR', 'SHOPIFY_SHOP')),
    publisher_id     varchar(128)  NOT NULL,
    taker_id         varchar(128)  NOT NULL,
    publication_id   uuid          NOT NULL REFERENCES entity_registry (entity_id),
    attempt_id       varchar(36),
    status           varchar(10)   NOT NULL CHECK (status IN ('OPEN', 'CLOSED')),
    opened_at        timestamptz   NOT NULL,
    last_message_at  timestamptz   NOT NULL,
    last_sender      varchar(10)   NOT NULL CHECK (last_sender IN ('TAKER', 'SPONSOR')),
    taker_read_at    timestamptz,
    sponsor_read_at  timestamptz,
    closed_at        timestamptz,
    closed_by        varchar(10)   CHECK (closed_by IN ('TAKER', 'SPONSOR', 'SYSTEM')),
    version          bigint        NOT NULL,
    CONSTRAINT qb_conversation_one UNIQUE (taker_id, publication_id),
    CONSTRAINT qb_conversation_closed CHECK ((status = 'CLOSED') = (closed_at IS NOT NULL)
        AND (closed_at IS NULL) = (closed_by IS NULL))
);

-- The sponsor's list, the taker's list, the daily expiry.
CREATE INDEX qb_conversation_publisher_idx ON qb_conversation (publisher_id, status, last_message_at DESC);
CREATE INDEX qb_conversation_taker_idx ON qb_conversation (taker_id, last_message_at DESC);
CREATE INDEX qb_conversation_due_idx ON qb_conversation (status, last_message_at);

-- The body is emptied when its sender's account is erased (the row stays, so the other side still sees that a
-- message was there).
CREATE TABLE qb_conversation_message (
    message_id       varchar(36)   PRIMARY KEY,
    conversation_id  varchar(36)   NOT NULL REFERENCES qb_conversation (conversation_id),
    publisher_kind   varchar(20)   NOT NULL CHECK (publisher_kind IN ('SPONSOR', 'SHOPIFY_SHOP')),
    publisher_id     varchar(128)  NOT NULL,
    sender           varchar(10)   NOT NULL CHECK (sender IN ('TAKER', 'SPONSOR')),
    sender_user_id   varchar(128)  NOT NULL,
    body             varchar(1000),
    created_at       timestamptz   NOT NULL,
    version          bigint        NOT NULL
);

-- A conversation's messages in order; a sender's messages of a day (the daily cap) and of their account (erasure);
-- an operator's scope.
CREATE INDEX qb_conversation_message_conversation_idx ON qb_conversation_message (conversation_id, created_at);
CREATE INDEX qb_conversation_message_sender_idx ON qb_conversation_message (sender_user_id, created_at);
CREATE INDEX qb_conversation_message_publisher_idx ON qb_conversation_message (publisher_id);

CREATE TABLE qb_conversation_block (
    block_id         varchar(36)   PRIMARY KEY,
    taker_id         varchar(128)  NOT NULL,
    publisher_kind   varchar(20)   NOT NULL CHECK (publisher_kind IN ('SPONSOR', 'SHOPIFY_SHOP')),
    publisher_id     varchar(128)  NOT NULL,
    created_at       timestamptz   NOT NULL,
    version          bigint        NOT NULL,
    CONSTRAINT qb_conversation_block_one UNIQUE (taker_id, publisher_kind, publisher_id)
);

CREATE INDEX qb_conversation_block_publisher_idx ON qb_conversation_block (publisher_id);

-- D-Q82-7: a report of a conversation's messages names it; the new reason.
ALTER TABLE qb_report ADD COLUMN conversation_id varchar(36);
ALTER TABLE qb_report DROP CONSTRAINT qb_report_reason_check;
ALTER TABLE qb_report ADD CONSTRAINT qb_report_reason_check
    CHECK (reason IN ('MISLEADING', 'INAPPROPRIATE', 'REWARD_NOT_GIVEN', 'SPAM', 'MESSAGE', 'OTHER'));
