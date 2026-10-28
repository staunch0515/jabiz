-- Q39 (docs/quizbuks/plans/Q39-sponsor-staff.md): a sponsor's staff, takers the sponsor named to hand out prizes at
-- the counter; and who asked for each redeem code, so a prize collected by a staff member's rotating code is
-- recorded as redeemed by them. An ordinary table written by the processes only; a person is on one staff at most.

CREATE TABLE qb_sponsor_staff (
    staff_id   varchar(36)   PRIMARY KEY,
    sponsor_id varchar(128)  NOT NULL,
    user_id    varchar(128)  NOT NULL,
    added_at   timestamptz   NOT NULL,
    added_by   varchar(128)  NOT NULL,
    version    bigint        NOT NULL,
    CONSTRAINT uk_qb_sponsor_staff_user UNIQUE (user_id)
);
CREATE INDEX qb_sponsor_staff_sponsor_idx ON qb_sponsor_staff (sponsor_id);

-- The codes issued so far were the sponsors' own.
ALTER TABLE qb_redeem_token ADD COLUMN issued_by varchar(128);
UPDATE qb_redeem_token SET issued_by = sponsor_id;
