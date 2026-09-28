-- A second file field of it_attachment, which its public dataset whitelists (see ItFileFixtures).
ALTER TABLE it_attachment ADD COLUMN f_cover uuid;
