-- AI prospecting, second part (docs/quizbuks/plans/Q48-operators.md, Q48d): which sponsor a business's trial brought
-- (the sign-up carries the trial's key), the one follow-up mail, and the score above which a draft is approved
-- without a person (null: never).
ALTER TABLE qb_prospect ADD COLUMN converted_user_id varchar(128);
ALTER TABLE qb_prospect ADD COLUMN converted_at timestamptz;
ALTER TABLE qb_prospect ADD COLUMN followed_up_at timestamptz;
CREATE INDEX qb_prospect_sent_idx ON qb_prospect (sent_at) WHERE status = 'SENT' AND followed_up_at IS NULL
    AND converted_at IS NULL;
ALTER TABLE qb_prospect_setting ADD COLUMN auto_approve_score numeric(3, 0);
