-- F-S4a: Sponsors answer challenges in the Sponsor app (docs/quizbuks/plans/impl/F-S4.md §2).
-- A publication bound to a node of a map Sponsors light (an operator's copy) is a challenge: it says so from the save
-- on (for_sponsors, null on the publications before: none was), and its card freezes it; Takers' lists leave it out and
-- their start refuses it.
ALTER TABLE qb_publication_version ADD COLUMN for_sponsors boolean;
ALTER TABLE qb_publication_card ADD COLUMN for_sponsors boolean NOT NULL DEFAULT false;
-- The Sponsor an attempt answers for (the Sponsor's own account answers; null for a Taker's attempt).
ALTER TABLE qb_attempt ADD COLUMN on_behalf_of varchar(36);
