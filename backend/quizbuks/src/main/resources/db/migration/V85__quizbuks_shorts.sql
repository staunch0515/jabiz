-- F-S10 (docs/quizbuks/plans/impl/F-S10.md): vertical short videos, shown in a shelf of the App's home and in its
-- full-screen vertical mode, like YouTube Shorts.

-- The sponsor marks a vertical video on its material (a YouTube Shorts link is one anyway, VideoEmbeds.shorts). None is
-- not vertical: only a marked material says so, so that saving an unchanged one changes nothing. Added to an
-- append-only table: a new column only (platform 21 §2, D58).
ALTER TABLE qb_material_version ADD COLUMN vertical boolean;

-- The card freezes whether its video is vertical, when it goes on and at a switch of version (PublicationEditing.cardVideo).
ALTER TABLE qb_publication_card ADD COLUMN video_vertical boolean NOT NULL DEFAULT false;

-- The backfill, from each card's version: no material was marked before this migration, so a card's video is vertical
-- when it is a YouTube Shorts link of its version's base materials (the card's player names the same video). The links
-- are read as VideoEmbeds reads them: blanks around them dropped, the scheme and the host in any case, the path as
-- written; ShortsIT compares this with VideoEmbeds.shorts over the cases of VideoEmbedsTest.
UPDATE qb_publication_card c
SET video_vertical = true
WHERE c.video LIKE 'https://www.youtube-nocookie.com/embed/%'
  AND EXISTS (
    SELECT 1
    FROM qb_quiz_version_version v
    CROSS JOIN LATERAL jsonb_path_query(v.content::jsonb, 'lax $.materials[*] ? (@.kind == "VIDEO_LINK").url') AS u
    WHERE v.version_id = c.version_id
      AND btrim(u #>> '{}') ~* '^https?://(youtube\.com|www\.youtube\.com|m\.youtube\.com|youtube-nocookie\.com|www\.youtube-nocookie\.com)/'
      AND btrim(u #>> '{}') ~ ('^[^/]*//[^/]*/shorts/'
          || substr(c.video, length('https://www.youtube-nocookie.com/embed/') + 1) || '([?#][^[:space:]]*)?$'));
