package com.jabiz.quizbuks.settings;

import com.jabiz.entity.EntityDefinition;

/**
 * A user's own settings of the app and the sponsor console (docs/quizbuks/plans/Q57-sound.md): one row per user, in an
 * ordinary table, written only by {@link SettingsProcesses}. A user without a row has every default (sound on). The
 * first setting is the sound effects (Q57); the languages of the quizzes shown (Q59) join it.
 */
public final class QbSettings {

    public static final String SETTINGS = "QbUserSettings";
    /** The administrators' read-only dataset. */
    public static final String DEFAULT_DATASET = "urn:jabiz:dataset:default:" + SETTINGS;
    /** One's own settings, read only. */
    public static final String MY_DATASET = "urn:jabiz:dataset:me:" + SETTINGS;

    static final int USER_ID_LENGTH = 128;

    public static final EntityDefinition ENTITY = EntityDefinition.define(SETTINGS, eb -> {
        eb.physicalTable("qb_user_settings");
        eb.primaryKey("userId");
        eb.field("userId", f -> f.physicalColumn("user_id").immutable(true).required(true).asText(USER_ID_LENGTH));
        // "Off" rather than "on", so that no row and a new column's default both mean the default: sound on.
        eb.field("soundOff", f -> f.physicalColumn("sound_off").required(true).asBool());
        eb.field("version", f -> f.physicalColumn("version").asVersion());
        eb.listView("default", lv -> lv.columns("userId", "soundOff").filters("userId", "soundOff")
            .sorts("userId").defaultSort("userId", true));
    });

    /** The lock of a user's settings: two saves of the same user's never interleave (the first inserts the row). */
    public static String lockName(Object userId) {
        return "qb.settings:" + userId;
    }

    private QbSettings() {
    }
}
