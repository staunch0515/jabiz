-- Q73c (docs/quizbuks/plans/Q73-content-languages.md §4): a sponsor's page in several languages. The page offers
-- some of the platform's languages (the first its main one; none: its own language only) with its headline translated
-- into each; the onboarding's introduction and a product's name and description may be translated too. Each
-- translation is {language: {field: {text, hash}}}, the hash of the base text it translates.
ALTER TABLE qb_sponsor_page ADD COLUMN languages varchar(20);
ALTER TABLE qb_sponsor_page ADD COLUMN translations text;
ALTER TABLE qb_sponsor_version ADD COLUMN intro_translations text;
ALTER TABLE qb_sponsor_product ADD COLUMN translations text;
