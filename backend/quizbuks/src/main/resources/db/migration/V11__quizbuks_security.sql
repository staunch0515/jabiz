-- The security review's fixes (docs/quizbuks/plans/Q10b-security-fixes.md).
--
-- M2: who paused a publication (SPONSOR or ADMIN; a sponsor cannot resume what an administrator paused) and the
-- reason an administrator gave for pausing or ending it, shown to the sponsor.
-- Item 13: when a top-up receipt was issued, the start of its retention (10 years from the end of that fiscal year).
-- Existing rows keep NULL: QuizBuks has no production data yet, and a row without a date is always kept.
--
-- Both tables are append-only; adding a column rewrites nothing, and the integrity seals keep the columns they sealed
-- (docs/design/21 section 2).

ALTER TABLE qb_publication_version
    ADD COLUMN paused_by    varchar(16),
    ADD COLUMN admin_reason varchar(500);

ALTER TABLE qb_topup_receipt_version
    ADD COLUMN issued_at timestamptz;
