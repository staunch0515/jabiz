-- F-S15 (docs/quizbuks/plans/impl/F-S15.md): video links on any https address. A card's video is its player's address:
-- the seven sites' players are at most 80 characters, but a video file or another page plays at its own address, as
-- long as a material's link may be (ContentLimits.URL_LENGTH). The card is an ordinary table: its column is widened.
-- (Temporary number: renumbered to 1.3/quizbuks's highest + 1 before merging.)
ALTER TABLE qb_publication_card ALTER COLUMN video TYPE varchar(2000);
