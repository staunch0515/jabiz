-- F-S7 (docs/quizbuks/plans/impl/F-S7.md §7.2, controller 2026-11-09): whether a storefront campaign's questions are
-- shuffled for each visitor. The storefront shuffles by default (A2-14), the existing campaigns too; the staff may
-- turn it off, as a sponsor may on a quiz, and the engine's version then keeps the order given.
ALTER TABLE qbs_campaign ADD COLUMN shuffle_questions boolean NOT NULL DEFAULT true;
