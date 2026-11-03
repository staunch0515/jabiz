-- Q89b-2 (docs/quizbuks/plans/Q89-sponsored-benefits.md): a digital prize that is another sponsor's digital product
-- (a video's playback rights) records its provider, who loads the codes it is issued from: the code is sealed and
-- opened under the provider's key. Columns are only added to the append-only table (D58).
ALTER TABLE qb_digital_prize_version ADD COLUMN fulfiller_id varchar(128);
CREATE INDEX qb_digital_prize_version_fulfiller_idx ON qb_digital_prize_version (fulfiller_id);
