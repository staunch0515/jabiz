-- Platform decision D78 (key rotation): a pool's code is sealed for the person who loaded it, the provider of a digital
-- product (the publication's fulfiller) or else the publisher; the code now keeps the former, so that the platform can
-- store its content again under a new seal key for the context it was sealed for. Codes are loaded with it from now
-- on; there is no backfill (a migration's write would pass the audit by; the platform is in test and its data is
-- reset, F-S1).
ALTER TABLE qb_digital_code ADD COLUMN fulfiller_id varchar(128);
