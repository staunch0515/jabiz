-- F-C1a (docs/quizbuks/plans/impl/F-C1.md): a sponsor's asset library. Ordinary tables written by the QB_ASSET_*
-- processes only. An asset is the sponsor account's own record (sponsor_id is the user), of one kind for good; its
-- files are in one column per policy, so that the platform's sweep of unreferred files sees each. The translations
-- ({language: {field: {text, hash}}}) and tags (a list) are JSON in text columns, as the sponsors' other short texts.
CREATE TABLE qb_asset (
    asset_id          varchar(36)   PRIMARY KEY,
    sponsor_id        varchar(128)  NOT NULL,
    kind              varchar(16)   NOT NULL,
    name              varchar(100)  NOT NULL,
    description       text,
    language          varchar(5)    NOT NULL,
    translations      text,
    tags              varchar(2000),
    image             uuid,
    pdf               uuid,
    audio             uuid,
    url               varchar(2000),
    duration_sec      numeric(5, 0),
    body              text,
    text_usage        varchar(32),
    prize_method      varchar(20),
    prize_description text,
    digital_delivery  varchar(20),
    digital_send_days numeric(3, 0),
    content_hash      varchar(64),
    revision          numeric(9, 0) NOT NULL,
    created_at        timestamptz   NOT NULL,
    edited_at         timestamptz   NOT NULL,
    version           bigint        NOT NULL,
    CONSTRAINT qb_asset_kind_check CHECK (kind IN ('IMAGE', 'PDF', 'AUDIO', 'VIDEO_LINK', 'LINK', 'ARTICLE', 'TEXT',
        'PRIZE')),
    CONSTRAINT qb_asset_usage_check CHECK (text_usage IN ('QUIZ_INTRO', 'REDEEM_DESCRIPTION', 'DIGITAL_DESCRIPTION',
        'REFERRAL_DESCRIPTION')),
    CONSTRAINT qb_asset_prize_method_check CHECK (prize_method IN ('OFFLINE_REDEEM', 'DIGITAL_PRIZE',
        'SPONSOR_CASH')),
    CONSTRAINT qb_asset_digital_delivery_check CHECK (digital_delivery IN ('POOL', 'SHARED', 'SPONSOR_SENT'))
);
-- A sponsor's library by kind (its pages), and the duplicates of a text (a collection, the candidates' template).
CREATE INDEX qb_asset_sponsor_kind_idx ON qb_asset (sponsor_id, kind);
CREATE INDEX qb_asset_sponsor_hash_idx ON qb_asset (sponsor_id, content_hash);

-- Where an asset was copied to (written from F-C1b on): a place comes from one asset, and records the asset's
-- revision when copied, so that a later one shows as "older content".
CREATE TABLE qb_asset_use (
    use_id         varchar(36)   PRIMARY KEY,
    asset_id       varchar(36)   NOT NULL,
    sponsor_id     varchar(128)  NOT NULL,
    target_kind    varchar(24)   NOT NULL,
    target_id      varchar(36)   NOT NULL,
    asset_revision numeric(9, 0) NOT NULL,
    used_at        timestamptz   NOT NULL,
    version        bigint        NOT NULL,
    CONSTRAINT qb_asset_use_target_check CHECK (target_kind IN ('MATERIAL', 'QUESTION_IMAGE', 'OPTION_IMAGE',
        'QUIZ_COVER', 'QUIZ_INTRO', 'PUBLICATION_COVER', 'PUBLICATION_REDEEM', 'PUBLICATION_DIGITAL',
        'PUBLICATION_REFERRAL', 'PUBLICATION_PRIZE')),
    CONSTRAINT uk_qb_asset_use_target UNIQUE (target_kind, target_id)
);
CREATE INDEX qb_asset_use_asset_idx ON qb_asset_use (asset_id);
CREATE INDEX qb_asset_use_sponsor_idx ON qb_asset_use (sponsor_id);
