-- Answering (docs/quizbuks/02-design.md sections 3.3 and 3.4, docs/quizbuks/plans/Q5-answering.md): attempts, first
-- views, publication cards and completion counts, and the wallets' balance cache. All ordinary tables, written only by
-- the processes (the platform's audit keeps their history); version is the optimistic lock of ordinary entities.
-- Generated keys of ordinary entities are text (UUIDv7); keys that are a publication's refer to entity_registry as
-- the counters do.

-- The card of a publication that went on: the takers' feed and detail read it instead of the temporal publications
-- (conditions on state, times and regions there read every version). Everything but the state is frozen at
-- submission. Lengths are those of publishing.PublishingLimits and content.ContentLimits.
CREATE TABLE qb_publication_card (
    publication_id  uuid           PRIMARY KEY REFERENCES entity_registry (entity_id),
    sponsor_id      varchar(128)   NOT NULL,
    status          varchar(20)    NOT NULL,
    regions         varchar(64)    NOT NULL,
    start_at        timestamptz    NOT NULL,
    end_at          timestamptz    NOT NULL,
    name            varchar(100)   NOT NULL,
    quiz_title      varchar(100)   NOT NULL,
    cover           varchar(36),
    version_id      uuid           NOT NULL,
    max_reward      numeric(19, 0) NOT NULL CHECK (max_reward >= 0),
    full_score      numeric(7, 0)  NOT NULL,
    pass_score      numeric(6, 0)  NOT NULL,
    time_limit_sec  numeric(5, 0)  NOT NULL,
    question_count  numeric(4, 0)  NOT NULL,
    budget_cap      numeric(19, 0),
    max_winners     numeric(9, 0),
    tiers           varchar(4000)  NOT NULL,
    sponsor_country varchar(2),
    went_on_at      timestamptz    NOT NULL,
    version         bigint         NOT NULL
);
CREATE INDEX qb_publication_card_status_idx ON qb_publication_card (status, end_at);
CREATE INDEX qb_publication_card_sponsor_idx ON qb_publication_card (sponsor_id);

-- An attempt: one per taker and publication (requirement 5.3-6). The answers, the options' order and the result are
-- canonical JSON (play.Answers, play.Scoring); the result, with the correct answers, is written only at the end.
CREATE TABLE qb_attempt (
    attempt_id            varchar(36)    PRIMARY KEY,
    user_id               varchar(128)   NOT NULL,
    publication_id        uuid           NOT NULL,
    sponsor_id            varchar(128)   NOT NULL,
    version_id            uuid           NOT NULL,
    started_at            timestamptz    NOT NULL,
    deadline              timestamptz    NOT NULL,
    status                varchar(20)    NOT NULL CHECK (status IN ('IN_PROGRESS', 'SUBMITTED', 'EXPIRED')),
    current_index         numeric(4, 0)  NOT NULL,
    option_order          varchar(1504)  NOT NULL,
    answers               varchar(1504)  NOT NULL,
    result                varchar(12004),
    submitted_at          timestamptz,
    score                 numeric(7, 0),
    full_score            numeric(7, 0),
    passed                boolean,
    tier_id               varchar(36),
    reward                numeric(19, 0) CHECK (reward >= 0),
    fee                   numeric(19, 0) CHECK (fee >= 0),
    reward_status         varchar(20)    CHECK (reward_status IN ('NONE', 'PAID', 'PENDING')),
    reward_paid_at        timestamptz,
    ledger_transaction_id varchar(64),
    version               bigint         NOT NULL,
    CONSTRAINT qb_attempt_once UNIQUE (user_id, publication_id)
);
-- The taker's history; completions by publication and time (statistics); the attempts whose time ran out (Q5b's
-- job); a sponsor's pending rewards in their order.
CREATE INDEX qb_attempt_user_idx ON qb_attempt (user_id, started_at DESC);
CREATE INDEX qb_attempt_completed_idx ON qb_attempt (publication_id, submitted_at) WHERE submitted_at IS NOT NULL;
CREATE INDEX qb_attempt_due_idx ON qb_attempt (deadline) WHERE status = 'IN_PROGRESS';
CREATE INDEX qb_attempt_pending_idx ON qb_attempt (sponsor_id, submitted_at, attempt_id)
    WHERE reward_status = 'PENDING';

-- The first view of a publication by a taker (Q9's funnel).
CREATE TABLE qb_publication_view (
    view_id         varchar(36)  PRIMARY KEY,
    user_id         varchar(128) NOT NULL,
    publication_id  uuid         NOT NULL,
    first_viewed_at timestamptz  NOT NULL,
    version         bigint       NOT NULL,
    CONSTRAINT qb_publication_view_once UNIQUE (user_id, publication_id)
);

-- Completions of the last hour, day, week and month, refreshed every ten minutes.
CREATE TABLE qb_publication_stats (
    publication_id uuid          PRIMARY KEY REFERENCES entity_registry (entity_id),
    last_1h        numeric(9, 0) NOT NULL,
    last_24h       numeric(9, 0) NOT NULL,
    last_7d        numeric(9, 0) NOT NULL,
    last_30d       numeric(9, 0) NOT NULL,
    refreshed_at   timestamptz   NOT NULL,
    version        bigint        NOT NULL
);

-- The wallets' balance cache (the ledger is the truth), changed only under the wallet's lock. A balance never goes
-- below 0: the processes check it under the lock, this constraint stops anything else.
CREATE TABLE qb_wallet (
    wallet_id      varchar(36)    PRIMARY KEY,
    owner_id       varchar(128)   NOT NULL,
    kind           varchar(10)    NOT NULL CHECK (kind IN ('SPONSOR', 'TAKER')),
    balance        numeric(19, 0) NOT NULL CHECK (balance >= 0),
    pending_count  numeric(9, 0)  NOT NULL CHECK (pending_count >= 0),
    pending_amount numeric(19, 0) NOT NULL CHECK (pending_amount >= 0),
    pending_out    numeric(19, 0) NOT NULL CHECK (pending_out >= 0),
    version        bigint         NOT NULL,
    CONSTRAINT qb_wallet_owner UNIQUE (owner_id, kind)
);

-- Cards of the publications that went on before this migration (once): the current version of each publication with
-- a schedule row that is neither a draft nor in review, its version, its current tiers (CardTiers' JSON: keys sorted,
-- in their order) and its sponsor's country.
INSERT INTO qb_publication_card (publication_id, sponsor_id, status, regions, start_at, end_at, name, quiz_title, cover,
    version_id, max_reward, full_score, pass_score, time_limit_sec, question_count, budget_cap, max_winners, tiers,
    sponsor_country, went_on_at, version)
SELECT p.publication_id, p.sponsor_id, p.status, p.regions, p.start_at, p.end_at, p.name, v.title,
    CAST(v.content AS jsonb) ->> 'cover', p.version_id, p.max_reward, p.full_score, p.pass_score, p.time_limit_sec,
    v.question_count, p.budget_cap, p.max_winners,
    coalesce((SELECT CAST(json_agg(json_build_object('fromScore', CAST(t.from_score AS integer),
                  'reward', CAST(t.reward AS text), 'tierId', CAST(t.tier_id AS text),
                  'toScore', CAST(t.to_score AS integer)) ORDER BY t.seq, t.tier_id) AS text)
              FROM (SELECT DISTINCT ON (tier_id) * FROM qb_reward_tier_version
                    WHERE publication_id = p.publication_id
                    ORDER BY tier_id, effect_start_time DESC, version_no DESC) t
              WHERE NOT t.is_deleted), '[]'),
    (SELECT f.country_code FROM (SELECT DISTINCT ON (profile_id) * FROM qb_profile_version
                                 WHERE user_id = p.sponsor_id
                                 ORDER BY profile_id, effect_start_time DESC, version_no DESC) f
     WHERE NOT f.is_deleted LIMIT 1),
    coalesce(p.approved_at, p.submitted_at, p.edited_at), 1
FROM (SELECT DISTINCT ON (publication_id) * FROM qb_publication_version
      ORDER BY publication_id, effect_start_time DESC, version_no DESC) p
JOIN qb_publication_schedule s ON s.publication_id = p.publication_id
JOIN qb_quiz_version_version v ON v.version_id = p.version_id
WHERE NOT p.is_deleted AND p.status NOT IN ('DRAFT', 'PENDING_REVIEW');
