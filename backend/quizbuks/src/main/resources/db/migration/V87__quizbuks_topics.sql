-- F-S9 (docs/quizbuks/plans/impl/F-S9.md): topics. A quiz has topics, words of the platform's word list and custom
-- words; a publication inherits its version's and adds or removes some; a taker likes some and may turn on strict
-- mode (the home and its shelf of shorts show only publications sharing a liked word).
-- The number is temporary (V80-V83 are taken by branches in flight): renumbered to the highest on 1.3/quizbuks + 1
-- before merging.

-- The word list, kept by headquarters with the QB_TOPIC_* processes (an ordinary table, every change audited by the
-- platform). A word is named by its code and shown by its name in each language (all three required: the startup
-- check TopicCheck reports a row without one). Never deleted: deactivated, or merged into another (a word merged is
-- always into one not merged itself).
CREATE TABLE qb_topic (
    topic_id    varchar(40)  PRIMARY KEY CHECK (topic_id ~ '^[a-z0-9]+(-[a-z0-9]+)*$' AND length(topic_id) >= 2),
    name        jsonb        NOT NULL,
    active      boolean      NOT NULL DEFAULT true,
    merged_into varchar(40)  REFERENCES qb_topic (topic_id),
    created_at  timestamptz  NOT NULL,
    changed_at  timestamptz  NOT NULL,
    version     bigint       NOT NULL,
    CONSTRAINT qb_topic_merged_check CHECK (merged_into IS NULL OR (NOT active AND merged_into <> topic_id))
);
CREATE INDEX qb_topic_merged_into_idx ON qb_topic (merged_into) WHERE merged_into IS NOT NULL;

-- A quiz's topics (QB_QUIZ_TOPICS_SET) and a version's (copied when it is saved): codes of the word list,
-- comma-separated in the order chosen, and custom words (a JSON array, for search only). Both are append-only tables:
-- new columns only (platform 21 §2, D58).
ALTER TABLE qb_quiz_version ADD COLUMN topics varchar(2460);
ALTER TABLE qb_quiz_version ADD COLUMN custom_topics varchar(7380);
ALTER TABLE qb_quiz_version_version ADD COLUMN topics varchar(2460);
ALTER TABLE qb_quiz_version_version ADD COLUMN custom_topics varchar(7380);

-- What a publication adds to and removes from its version's topics (QB_PUBLICATION_TOPICS_SET; a JSON object; none:
-- the version's as they are). Append-only: a new column only.
ALTER TABLE qb_publication_version ADD COLUMN topic_changes varchar(9904);

-- The card's topics, when it goes on and at a switch of version: the feed's strict mode and the statistics read the
-- codes; the custom words wait for the search (F-S6). Cards from before have none: until a switch they are left out
-- of strict mode, as a publication with no word is.
ALTER TABLE qb_publication_card ADD COLUMN topics varchar(2460);
ALTER TABLE qb_publication_card ADD COLUMN custom_topics varchar(7380);

-- A user's liked topics and strict mode (QB_SETTINGS_SAVE; deleted with the account, SponsorErasure).
ALTER TABLE qb_user_settings ADD COLUMN liked_topics varchar(2050);
ALTER TABLE qb_user_settings ADD COLUMN strict_topics boolean NOT NULL DEFAULT false;
