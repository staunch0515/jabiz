-- Q73d (docs/quizbuks/plans/Q73-content-languages.md §5): an AI job may also translate a quiz's text into one of its
-- other languages (TRANSLATION), translate a publication's own texts (PUBLICATION_TEXTS), or compare a publication's
-- translations with their base texts for its reviewer (TRANSLATION_CHECK). The quiz translations it leads to are
-- QbContentTranslation rows already able to say so (translated_by = 'AI', ai_job_id, V54).
ALTER TABLE qb_ai_job DROP CONSTRAINT qb_ai_job_kind_check;
ALTER TABLE qb_ai_job ADD CONSTRAINT qb_ai_job_kind_check CHECK (kind IN ('QUESTIONS', 'COVER', 'TRANSLATION',
    'PUBLICATION_TEXTS', 'TRANSLATION_CHECK'));
