-- Q114a (docs/quizbuks/plans/impl/Q114.md section 3): a taker's own avatar, one row per user, written by QB_AVATAR_SET
-- only. No row: the initial (the default). An ordinary table rather than a column of the temporal profile, so that a
-- photo taken off later (Q114b) is no longer referenced by any row and can go at once.
CREATE TABLE qb_taker_avatar (
    user_id   varchar(128) PRIMARY KEY,
    kind      varchar(10)  NOT NULL,
    preset    varchar(10),
    edited_at timestamptz  NOT NULL,
    version   bigint       NOT NULL,
    -- A preset exactly when the kind is one.
    CONSTRAINT ck_qb_taker_avatar_preset CHECK ((kind = 'PRESET') = (preset IS NOT NULL))
);
