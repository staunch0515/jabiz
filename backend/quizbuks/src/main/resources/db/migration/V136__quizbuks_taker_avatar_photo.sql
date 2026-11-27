-- Q114b (docs/quizbuks/plans/impl/Q114.md section 5): a taker's own photo as the avatar. The file of the policy
-- qb.taker-avatar (no foreign key, as every file field); QB_AVATAR_SET deletes a photo it replaces or removes.
ALTER TABLE qb_taker_avatar ADD COLUMN photo uuid;
ALTER TABLE qb_taker_avatar DROP CONSTRAINT ck_qb_taker_avatar_preset;
-- A preset exactly when the kind is one, a photo exactly when the kind is one (AvatarProcesses.Kind).
ALTER TABLE qb_taker_avatar ADD CONSTRAINT ck_qb_taker_avatar_kind CHECK (
    kind IN ('INITIAL', 'PRESET', 'PHOTO')
    AND (kind = 'PRESET') = (preset IS NOT NULL)
    AND (kind = 'PHOTO') = (photo IS NOT NULL));
