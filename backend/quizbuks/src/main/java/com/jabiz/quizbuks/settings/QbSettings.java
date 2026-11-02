package com.jabiz.quizbuks.settings;

import com.jabiz.entity.EntityDefinition;

/**
 * A user's own settings of the app and the sponsor console (docs/quizbuks/plans/Q57-sound.md): one row per user, in an
 * ordinary table, written only by {@link SettingsProcesses}. A user without a row has every default (sound on, the
 * quizzes of the interface language). The sound effects (Q57), the languages of the quizzes shown (Q59a) and the public
 * certification page (Q77: off by default).
 */
public final class QbSettings {

    public static final String SETTINGS = "QbUserSettings";
    /** The administrators' read-only dataset. */
    public static final String DEFAULT_DATASET = "urn:jabiz:dataset:default:" + SETTINGS;
    /** One's own settings, read only. */
    public static final String MY_DATASET = "urn:jabiz:dataset:me:" + SETTINGS;
    /** Q77: the public names of the pages turned on, for the public certification page (nothing else). */
    public static final String PUBLIC_DATASET = "urn:jabiz:dataset:public:" + SETTINGS;

    static final int USER_ID_LENGTH = 128;

    public static final EntityDefinition ENTITY = EntityDefinition.define(SETTINGS, eb -> {
        eb.physicalTable("qb_user_settings");
        eb.primaryKey("userId");
        eb.field("userId", f -> f.physicalColumn("user_id").immutable(true).required(true).asText(USER_ID_LENGTH));
        // "Off" rather than "on", so that no row and a new column's default both mean the default: sound on.
        eb.field("soundOff", f -> f.physicalColumn("sound_off").required(true).asBool());
        // Q59a: the languages of the quizzes shown, comma-separated; none: the interface language only.
        eb.field("contentLanguages", f -> f.physicalColumn("content_languages").asText(20));
        // Q77: the public certification page /taker/u/<publicHandle>, shown only while publicPage is on.
        eb.field("publicHandle", f -> f.physicalColumn("public_handle").asText(PublicHandles.MAX_LENGTH));
        eb.field("publicPage", f -> f.physicalColumn("public_page").required(true).asBool());
        eb.unique("uk_qb_user_settings_public_handle", "publicHandle");
        eb.field("version", f -> f.physicalColumn("version").asVersion());
        eb.listView("default", lv -> lv.columns("userId", "soundOff", "contentLanguages").filters("userId", "soundOff")
            .sorts("userId").defaultSort("userId", true));
    });

    /** The lock of a user's settings: two saves of the same user's never interleave (the first inserts the row). */
    public static String lockName(Object userId) {
        return "qb.settings:" + userId;
    }

    private QbSettings() {
    }
}
