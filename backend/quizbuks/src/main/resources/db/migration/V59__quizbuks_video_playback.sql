-- Q75 (docs/quizbuks/plans/Q75-video-playback.md): "watching this video is enough to answer" now asks for the
-- watching. The sponsor gives the video's duration (D-Q75-5); the card freezes whether its video must be watched and
-- how long it is (D-Q75-7: the start reads the card anyway); the attempt records what the App reported of the
-- playback at its start (D-Q75-9). Nothing is backfilled (the requester, 2026-11-01: old data stays as it is): cards
-- already on do not require the watching, their videos have no duration.
ALTER TABLE qb_material_version ADD COLUMN duration_sec numeric(5, 0)
    CHECK (duration_sec IS NULL OR duration_sec BETWEEN 1 AND 7200);
ALTER TABLE qb_publication_card ADD COLUMN watch_required boolean NOT NULL DEFAULT false;
ALTER TABLE qb_publication_card ADD COLUMN video_duration_sec numeric(5, 0);
ALTER TABLE qb_attempt ADD COLUMN watched_sec numeric(6, 0) CHECK (watched_sec IS NULL OR watched_sec >= 0);
ALTER TABLE qb_attempt ADD COLUMN video_ended boolean;
ALTER TABLE qb_attempt ADD COLUMN playback_status varchar(10)
    CHECK (playback_status IS NULL OR playback_status IN ('REPORTED', 'UNKNOWN'));
