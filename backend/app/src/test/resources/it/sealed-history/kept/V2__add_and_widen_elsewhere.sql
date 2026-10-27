-- Adding a column keeps every sealed row's digest; an ordinary table may change at will.
ALTER TABLE it_sh_sealed ADD COLUMN extra text;
CREATE TABLE it_sh_plain (id bigint PRIMARY KEY, v text);
ALTER TABLE it_sh_plain RENAME COLUMN v TO w;
