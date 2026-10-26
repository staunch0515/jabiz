-- V29 renamed sponsor_id to publisher_id on five append-only tables, which the platform's integrity seal protects. A
-- seal block records each table's columns and verifying digests the rows over those columns again; a sealed column
-- that is renamed reads as missing, so every row sealed before V29 (1.2 seals at V28) would be reported as MODIFIED
-- (docs/design/21-audit-retention.md section 2.1: columns may only be added). The column takes its old name back; the
-- entity field stays publisherId (PublisherKind.declareSealed maps it to sponsor_id). publisher_kind and its check
-- stay as V29 left them (an added column leaves the seals intact); the indexes take their old names back too, so that
-- they keep naming their column (an index's name is not sealed).
--
-- Run with V29 in one upgrade, the application stopped: no seal runs between the two, so no block records
-- publisher_id. The ordinary tables keep publisher_id (they are not sealed).

ALTER TABLE qb_publication_version RENAME COLUMN publisher_id TO sponsor_id;
ALTER TABLE qb_reward_tier_version RENAME COLUMN publisher_id TO sponsor_id;
ALTER TABLE qb_cash_reward_version RENAME COLUMN publisher_id TO sponsor_id;
ALTER TABLE qb_redemption_version RENAME COLUMN publisher_id TO sponsor_id;
ALTER TABLE qb_digital_prize_version RENAME COLUMN publisher_id TO sponsor_id;

ALTER INDEX qb_publication_version_publisher_idx RENAME TO qb_publication_version_sponsor_idx;
ALTER INDEX qb_reward_tier_version_publisher_idx RENAME TO qb_reward_tier_version_sponsor_idx;
ALTER INDEX qb_cash_reward_version_publisher_idx RENAME TO qb_cash_reward_version_sponsor_idx;
ALTER INDEX qb_redemption_version_publisher_idx RENAME TO qb_redemption_version_sponsor_idx;
ALTER INDEX qb_digital_prize_version_publisher_idx RENAME TO qb_digital_prize_version_sponsor_idx;
