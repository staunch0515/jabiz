-- A user's own settings of the app and the sponsor console (docs/quizbuks/plans/Q57-sound.md): one row per user,
-- written by QB_SETTINGS_SAVE only. No row: every default (the sound effects on).
CREATE TABLE qb_user_settings (
    user_id   varchar(128) PRIMARY KEY,
    sound_off boolean      NOT NULL DEFAULT false,
    version   bigint       NOT NULL
);
