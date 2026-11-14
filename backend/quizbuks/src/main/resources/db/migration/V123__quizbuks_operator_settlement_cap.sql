-- F-M12 b.
--
-- cost_offset (L3): the part of a gift refund that offset the cost carried instead of being paid out (posted to 4110 in
-- the take-back posting).
-- The cap on a settlement's lines (plan §3.9, option B): when the fees waiting would make more than the cap's lines, the
-- settlement counts the oldest fees (chargedAt, charge id) that fit with their credit-used lines, says so (capped), how
-- many it left (uncounted) and the last fee counted (the watermark). The fees left are counted by a later settlement at
-- the rates and settings of the month they were left in: the settings are kept with the capped settlement
-- (capped_terms; the plan's share is platform_share). Only new columns on an ordinary table.
ALTER TABLE qb_operator_settlement
    ADD COLUMN cost_offset          numeric(19, 0) NOT NULL DEFAULT 0 CHECK (cost_offset >= 0),
    ADD COLUMN capped               boolean        NOT NULL DEFAULT false,
    ADD COLUMN uncounted            integer        NOT NULL DEFAULT 0 CHECK (uncounted >= 0),
    ADD COLUMN watermark_charged_at timestamptz,
    ADD COLUMN watermark_charge_id  varchar(36),
    ADD COLUMN capped_terms         varchar(1000);
CREATE INDEX qb_operator_settlement_capped_idx ON qb_operator_settlement (operator_id, month) WHERE capped;
