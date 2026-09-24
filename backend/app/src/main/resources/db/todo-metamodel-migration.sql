-- Adapts the existing todo table to the Todo entity definition.
-- If id was declared "generated ... as identity" instead of serial, use:
--   ALTER TABLE todo ALTER COLUMN id DROP IDENTITY;
ALTER TABLE todo ALTER COLUMN id DROP DEFAULT;
ALTER TABLE todo ALTER COLUMN id TYPE varchar(36) USING id::text;
ALTER TABLE todo ADD COLUMN version bigint NOT NULL DEFAULT 1;
ALTER TABLE todo ADD COLUMN created_at timestamptz NOT NULL DEFAULT now();
