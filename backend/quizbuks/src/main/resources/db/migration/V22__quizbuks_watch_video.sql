-- The video to watch (docs/quizbuks/plans/Q19-embedded-video.md): a video material watching which is enough to answer,
-- and the card's player for it, frozen when the publication goes on (the App's home shows it). Every existing row is
-- an ordinary material and a card without one. Sealed rows are not affected: a seal digests only the columns it
-- recorded (design 21 section 2).
ALTER TABLE qb_material_version ADD COLUMN watch_to_answer boolean NOT NULL DEFAULT false;
ALTER TABLE qb_publication_card ADD COLUMN video varchar(80);
