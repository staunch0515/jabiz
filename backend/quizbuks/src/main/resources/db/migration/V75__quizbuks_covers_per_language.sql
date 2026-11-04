-- Q98b (docs/quizbuks/plans/Q98-covers.md): covers per language. A publication may have its own cover in each
-- language it offers besides its base (files of the policy qb.content.image); its card keeps the cover chosen for each
-- language where it is not the card's cover. The publication's table is sealed (platform 21 section 2): columns are
-- only added. A quiz's own cover per language is a translation row (element quiz.cover): no column.
ALTER TABLE qb_publication_version ADD COLUMN cover_ja uuid;
ALTER TABLE qb_publication_version ADD COLUMN cover_zh uuid;
ALTER TABLE qb_publication_version ADD COLUMN cover_en uuid;
ALTER TABLE qb_publication_card ADD COLUMN cover_ja varchar(36);
ALTER TABLE qb_publication_card ADD COLUMN cover_zh varchar(36);
ALTER TABLE qb_publication_card ADD COLUMN cover_en varchar(36);
