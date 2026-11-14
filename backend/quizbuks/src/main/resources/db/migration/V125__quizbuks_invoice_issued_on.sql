-- F-M13a: the day an invoice was issued (the fiscal zone's day, not the instant: the document says "11月2日", not
-- "11月1日 16:17 UTC"). New rows get it from the issuing steps (jabiz.fiscal-year-zone); rows before this migration are
-- test data only, so they are filled by the zone the application is configured with by default (Asia/Tokyo).
ALTER TABLE qb_fee_invoice ADD COLUMN issued_on date;
UPDATE qb_fee_invoice SET issued_on = (issued_at AT TIME ZONE 'Asia/Tokyo')::date;
ALTER TABLE qb_fee_invoice ALTER COLUMN issued_on SET NOT NULL;
