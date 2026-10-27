-- Text of another length and integers of another width read the same in to_jsonb: the seals stay intact.
ALTER TABLE it_sh_sealed ALTER COLUMN note TYPE text, ALTER COLUMN qty TYPE bigint;
