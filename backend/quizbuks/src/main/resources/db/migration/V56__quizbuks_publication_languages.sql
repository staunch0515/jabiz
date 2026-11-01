-- A publication in several languages (docs/quizbuks/plans/Q73-content-languages.md §3, Q73b): the languages it
-- offers (the first its main one) and its own texts in each other one, frozen with the policy and copied to the card;
-- an attempt's language. Every existing publication offers its version's language only (none written means that).
-- Columns are only added (sealed rows digest the columns they recorded; platform decision D58).

ALTER TABLE qb_publication_version ADD COLUMN languages varchar(20);
ALTER TABLE qb_publication_version ADD COLUMN translations varchar(20000);

-- The card is an ordinary table: its languages are written for the cards there are, so the feed reads one column.
ALTER TABLE qb_publication_card ADD COLUMN languages varchar(20);
ALTER TABLE qb_publication_card ADD COLUMN translations varchar(20000);
UPDATE qb_publication_card SET languages = language;

ALTER TABLE qb_attempt ADD COLUMN language varchar(2);
