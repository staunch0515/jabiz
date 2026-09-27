-- Content model of Culture, Unfiltered (docs/culture/00-design.md section 3). Ordinary tables, not temporal: they
-- hold personal data of minors that must be deletable for real. Multilingual texts are jsonb ({language: text}),
-- file fields are uuid without a foreign key (docs/design/14-files.md section 4). Keys are text (UUIDv7), the
-- platform's canonical type of the keys of ordinary entities.

CREATE TABLE cu_location (
    location_id   varchar(36)         PRIMARY KEY,
    slug          varchar(40)  NOT NULL CONSTRAINT uk_cu_location_slug UNIQUE,
    name          jsonb        NOT NULL,
    country_code  varchar(2),
    place_label   jsonb,
    latitude      numeric(8,5),
    longitude     numeric(9,5),
    sort_order    numeric(5,0),
    visible       boolean      NOT NULL,
    version       bigint       NOT NULL DEFAULT 1
);

CREATE TABLE cu_theme (
    theme_id    varchar(36)         PRIMARY KEY,
    slug        varchar(40)  NOT NULL CONSTRAINT uk_cu_theme_slug UNIQUE,
    icon        varchar(8),
    title       jsonb        NOT NULL,
    question    jsonb        NOT NULL,
    intro       jsonb,
    sort_order  numeric(5,0),
    visible     boolean      NOT NULL,
    version     bigint       NOT NULL DEFAULT 1
);

CREATE TABLE cu_participant (
    participant_id    varchar(36)          PRIMARY KEY,
    slug              varchar(40)   NOT NULL CONSTRAINT uk_cu_participant_slug UNIQUE,
    display_name      varchar(40)   NOT NULL,
    location_id       varchar(36)          NOT NULL REFERENCES cu_location (location_id),
    portrait_file_id  uuid,
    portrait_alt      jsonb,
    short_bio         jsonb,
    bio               jsonb,
    perspective       jsonb,
    reflection        jsonb,
    interests         jsonb,
    languages         varchar(200),
    sort_order        numeric(5,0),
    account_actor_id  varchar(64)   CONSTRAINT uk_cu_participant_account UNIQUE,
    adult             boolean       NOT NULL,
    curator_note      text,
    status            varchar(16)   NOT NULL,
    version           bigint        NOT NULL DEFAULT 1
);
CREATE INDEX cu_participant_location_idx ON cu_participant (location_id);
CREATE INDEX cu_participant_portrait_idx ON cu_participant (portrait_file_id) WHERE portrait_file_id IS NOT NULL;

CREATE TABLE cu_story (
    story_id              varchar(36)          PRIMARY KEY,
    slug                  varchar(80)   NOT NULL CONSTRAINT uk_cu_story_slug UNIQUE,
    title                 jsonb         NOT NULL,
    summary               jsonb,
    about                 jsonb,
    body                  jsonb,
    media_type            varchar(32)   NOT NULL,
    story_date            timestamptz,
    thumbnail_file_id     uuid,
    thumbnail_alt         jsonb,
    video_provider        varchar(16),
    video_id              varchar(32),
    captions_confirmed    boolean,
    transcript            jsonb,
    reflection_surprised  jsonb,
    reflection_assumed    jsonb,
    reflection_learned    jsonb,
    featured              boolean,
    owner_actor_id        varchar(64),
    status                varchar(16)   NOT NULL,
    published_time        timestamptz,
    review_note           text,
    version               bigint        NOT NULL DEFAULT 1
);
CREATE INDEX cu_story_owner_idx ON cu_story (owner_actor_id) WHERE owner_actor_id IS NOT NULL;
CREATE INDEX cu_story_status_idx ON cu_story (status);
CREATE INDEX cu_story_thumbnail_idx ON cu_story (thumbnail_file_id) WHERE thumbnail_file_id IS NOT NULL;

CREATE TABLE cu_story_theme (
    story_theme_id  varchar(36)         PRIMARY KEY,
    story_id        varchar(36)         NOT NULL REFERENCES cu_story (story_id),
    theme_id        varchar(36)         NOT NULL REFERENCES cu_theme (theme_id),
    visibility      varchar(16)  NOT NULL,
    version         bigint       NOT NULL DEFAULT 1,
    CONSTRAINT uk_cu_story_theme UNIQUE (story_id, theme_id)
);
CREATE INDEX cu_story_theme_theme_idx ON cu_story_theme (theme_id);

CREATE TABLE cu_contribution (
    contribution_id     varchar(36)          PRIMARY KEY,
    story_id            varchar(36)          NOT NULL REFERENCES cu_story (story_id),
    participant_id      varchar(36)          NOT NULL REFERENCES cu_participant (participant_id),
    heading             jsonb,
    text                jsonb,
    video_provider      varchar(16),
    video_id            varchar(32),
    captions_confirmed  boolean,
    transcript          jsonb,
    audio_file_id       uuid,
    sort_order          numeric(5,0),
    owner_actor_id      varchar(64),
    editable            boolean,
    visibility          varchar(16)   NOT NULL,
    version             bigint        NOT NULL DEFAULT 1,
    CONSTRAINT uk_cu_contribution UNIQUE (story_id, participant_id)
);
CREATE INDEX cu_contribution_participant_idx ON cu_contribution (participant_id);
CREATE INDEX cu_contribution_owner_idx ON cu_contribution (owner_actor_id) WHERE owner_actor_id IS NOT NULL;
CREATE INDEX cu_contribution_audio_idx ON cu_contribution (audio_file_id) WHERE audio_file_id IS NOT NULL;

CREATE TABLE cu_media_item (
    media_item_id              varchar(36)          PRIMARY KEY,
    story_id                   varchar(36)          NOT NULL REFERENCES cu_story (story_id),
    contribution_id            varchar(36)          REFERENCES cu_contribution (contribution_id),
    kind                       varchar(8)    NOT NULL,
    image_file_id              uuid,
    audio_file_id              uuid,
    alt                        jsonb,
    caption                    jsonb,
    credit                     varchar(80),
    shows_identifiable_people  boolean,
    people_consent_confirmed   boolean,
    sort_order                 numeric(5,0),
    owner_actor_id             varchar(64),
    editable                   boolean,
    visibility                 varchar(16)   NOT NULL,
    version                    bigint        NOT NULL DEFAULT 1
);
CREATE INDEX cu_media_item_story_idx ON cu_media_item (story_id);
CREATE INDEX cu_media_item_contribution_idx ON cu_media_item (contribution_id) WHERE contribution_id IS NOT NULL;
CREATE INDEX cu_media_item_owner_idx ON cu_media_item (owner_actor_id) WHERE owner_actor_id IS NOT NULL;
CREATE INDEX cu_media_item_image_idx ON cu_media_item (image_file_id) WHERE image_file_id IS NOT NULL;
CREATE INDEX cu_media_item_audio_idx ON cu_media_item (audio_file_id) WHERE audio_file_id IS NOT NULL;

CREATE TABLE cu_resource (
    resource_id       varchar(36)          PRIMARY KEY,
    slug              varchar(80)   NOT NULL CONSTRAINT uk_cu_resource_slug UNIQUE,
    title             jsonb         NOT NULL,
    description       jsonb,
    activity_type     varchar(32)   NOT NULL,
    age_group         varchar(16),
    duration_minutes  numeric(4,0),
    pdf_file_id       uuid,
    body              jsonb,
    sort_order        numeric(5,0),
    status            varchar(16)   NOT NULL,
    version           bigint        NOT NULL DEFAULT 1
);
CREATE INDEX cu_resource_pdf_idx ON cu_resource (pdf_file_id) WHERE pdf_file_id IS NOT NULL;

CREATE TABLE cu_resource_story (
    resource_story_id  varchar(36)    PRIMARY KEY,
    resource_id        varchar(36)    NOT NULL REFERENCES cu_resource (resource_id),
    story_id           varchar(36)    NOT NULL REFERENCES cu_story (story_id),
    version            bigint  NOT NULL DEFAULT 1,
    CONSTRAINT uk_cu_resource_story UNIQUE (resource_id, story_id)
);
CREATE INDEX cu_resource_story_story_idx ON cu_resource_story (story_id);

CREATE TABLE cu_site_block (
    site_block_id  varchar(36)          PRIMARY KEY,
    block_key      varchar(80)   NOT NULL CONSTRAINT uk_cu_site_block_key UNIQUE,
    body           jsonb         NOT NULL,
    note           varchar(500),
    version        bigint        NOT NULL DEFAULT 1
);

-- Never public (docs/culture/00-design.md section 3.10).
CREATE TABLE cu_consent (
    consent_id        varchar(36)         PRIMARY KEY,
    participant_id    varchar(36)         NOT NULL REFERENCES cu_participant (participant_id),
    party             varchar(16)  NOT NULL,
    covers_photo      boolean      NOT NULL,
    covers_video      boolean      NOT NULL,
    covers_voice      boolean      NOT NULL,
    signed_on         timestamptz  NOT NULL,
    document_file_id  uuid,
    withdrawn_time    timestamptz,
    version           bigint       NOT NULL DEFAULT 1
);
CREATE INDEX cu_consent_participant_idx ON cu_consent (participant_id);
CREATE INDEX cu_consent_document_idx ON cu_consent (document_file_id) WHERE document_file_id IS NOT NULL;
