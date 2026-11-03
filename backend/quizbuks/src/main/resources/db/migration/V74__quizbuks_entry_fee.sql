-- Q88b (docs/quizbuks/plans/Q88-sponsor-certifications-entry-fee.md, D-Q88-9): a quiz may charge an entry fee in
-- Kudos, paid once at the start of the taker's attempt. The fee is the publication's setting (frozen with the policy,
-- copied to its card); what an attempt paid is written with the attempt (plan section 11.1), so the taker's ledger
-- page, the sponsor's totals and the monthly report read it without deriving it from the ledger's sources. The fee
-- itself is posted 2120 -> 2170 (entry fees unsettled) at the start and settled 2170 -> 2160 by the daily job
-- qb.entry-fee-settle (D-Q88-11); the account 2170 is opened by QB_SETUP.
ALTER TABLE qb_publication_version ADD COLUMN entry_fee numeric(7, 0) NOT NULL DEFAULT 0;
ALTER TABLE qb_publication_card ADD COLUMN entry_fee numeric(7, 0) NOT NULL DEFAULT 0;
ALTER TABLE qb_attempt ADD COLUMN entry_fee numeric(7, 0) NOT NULL DEFAULT 0;
