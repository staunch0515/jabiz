-- Q64 (docs/quizbuks/plans/Q64-home-polish.md section 4): a publication may have its own cover image, distinct from
-- its quiz version's (a file of the policy qb.content.image). Set in the draft with the rest of its policy, reviewed
-- and frozen with it; the card shows it, else the version's cover. The table is sealed (platform 21 §2): columns are
-- only added.
ALTER TABLE qb_publication_version ADD COLUMN cover uuid;
