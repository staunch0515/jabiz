-- Push notifications (decision D69).

-- A device a user registered to receive push notifications on: a browser's Web Push subscription (endpoint and keys)
-- or a store app's token, with the session it was registered in (signing out or revoking the session ends it). An
-- ordinary table: a device is replaced when registered again and deleted when its session ends, its account goes or
-- its push service says it is gone. The endpoint is the subscription's capability: one device per endpoint.
CREATE TABLE sec_user_device (
    device_id    varchar(36)   PRIMARY KEY,
    user_id      uuid          NOT NULL REFERENCES entity_registry (entity_id),
    entry        varchar(40)   NOT NULL,
    kind         varchar(10)   NOT NULL CHECK (kind IN ('webpush', 'apns')),
    endpoint     varchar(1000) NOT NULL,
    p256dh       varchar(200),
    auth         varchar(100),
    session_id   varchar(36)   NOT NULL,
    created_at   timestamptz   NOT NULL,
    last_used_at timestamptz   NOT NULL,
    version      bigint        NOT NULL,
    CONSTRAINT sec_user_device_endpoint_uk UNIQUE (endpoint)
);
CREATE INDEX sec_user_device_user_idx ON sec_user_device (user_id);

-- Whether a user receives the push notifications of a template: the temporal platform entity SecUserPushPreference.
CREATE TABLE sec_user_push_preference_version (
    row_id                  bigint       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_push_preference_id uuid         NOT NULL REFERENCES entity_registry (entity_id),
    version_no              integer      NOT NULL,
    effect_start_time       timestamptz  NOT NULL,
    created_time            timestamptz  NOT NULL,
    process_seq_id          bigint       NOT NULL REFERENCES op_process (process_seq_id),
    is_deleted              boolean      NOT NULL DEFAULT false,
    user_id                 uuid         NOT NULL REFERENCES entity_registry (entity_id),
    template                varchar(100) NOT NULL,
    subscribed              boolean      NOT NULL,
    CONSTRAINT sec_user_push_preference_version_uk UNIQUE (user_push_preference_id, version_no)
);
CREATE INDEX sec_user_push_preference_version_current_idx
    ON sec_user_push_preference_version (user_push_preference_id, effect_start_time DESC, version_no DESC);
CREATE INDEX sec_user_push_preference_version_process_idx ON sec_user_push_preference_version (process_seq_id);
CREATE INDEX sec_user_push_preference_version_user_idx ON sec_user_push_preference_version (user_id, template);
SELECT jabiz_protect_append_only('sec_user_push_preference_version');
