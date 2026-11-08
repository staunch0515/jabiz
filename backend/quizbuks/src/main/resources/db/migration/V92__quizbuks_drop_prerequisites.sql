-- F-S2c2a (docs/quizbuks/plans/impl/F-S2.md §7 D4): the publications' prerequisites (Q76, Q86) are gone; the
-- knowledge graphs replace them. Nothing reads the table any more, and its rows are not carried over (the spec: a
-- migration moves no data). qb_sponsor_certification (D6) stays, read only: issued certificates name it (D8).
DROP TABLE qb_publication_prerequisite;
