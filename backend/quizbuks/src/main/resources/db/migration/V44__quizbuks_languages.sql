-- The language of each quiz and the languages a taker sees (docs/quizbuks/plans/Q59-languages.md, Q59a).
-- A quiz is written in one of the platform's languages; each version keeps the language it was made in, and the
-- publication's card the language of its version, which the feed filters on. The quizzes made before are test data
-- (the requester, 2026-10-30): they take the default language, Japanese. Only columns are added to the append-only
-- tables (decision D58).
ALTER TABLE qb_quiz_version ADD COLUMN language varchar(2) NOT NULL DEFAULT 'ja'
    CHECK (language IN ('ja', 'zh', 'en'));
ALTER TABLE qb_quiz_version_version ADD COLUMN language varchar(2) NOT NULL DEFAULT 'ja'
    CHECK (language IN ('ja', 'zh', 'en'));
ALTER TABLE qb_publication_card ADD COLUMN language varchar(2) NOT NULL DEFAULT 'ja'
    CHECK (language IN ('ja', 'zh', 'en'));
CREATE INDEX ix_qb_publication_card_language ON qb_publication_card (language, status);

-- The languages of the quizzes a user sees (a comma-separated list of the platform's languages); null: the
-- interface language only, whichever it is.
ALTER TABLE qb_user_settings ADD COLUMN content_languages varchar(20);
