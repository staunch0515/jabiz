package com.jabiz.quizbuks.avatar;

import com.jabiz.entity.EntityDefinition;
import com.jabiz.file.FileKind;
import com.jabiz.entity.TemporalRole;

/**
 * A taker's own avatar (Q114, docs/quizbuks/plans/impl/Q114.md section 3): one row per user in an ordinary table,
 * written only by {@link AvatarProcesses}. A user without a row has the initial, in the colour the app takes from
 * their id. Only the taker sees it: the app reads it through {@link #MY_DATASET}; administrators who read users see
 * the kind, the preset and the photo's file id in the data browser (not the photo: its policy reads with {@code
 * qb.me} and only the taker knows the id), and nobody else reads it.
 */
public final class QbAvatars {

    public static final String AVATAR = "QbTakerAvatar";
    /** The administrators' read-only dataset. */
    public static final String DEFAULT_DATASET = "urn:jabiz:dataset:default:" + AVATAR;
    /** One's own avatar, read only. */
    public static final String MY_DATASET = "urn:jabiz:dataset:me:" + AVATAR;
    /**
     * The photos' file policy (Q114b): read and uploaded with {@code qb.me}, so the file's random id is what keeps a
     * photo the taker's own.
     */
    public static final String PHOTO_POLICY = "qb.taker-avatar";

    static final int USER_ID_LENGTH = 128;

    public static final EntityDefinition ENTITY = EntityDefinition.define(AVATAR, eb -> {
        eb.physicalTable("qb_taker_avatar");
        eb.primaryKey("userId");
        eb.field("userId", f -> f.physicalColumn("user_id").immutable(true).required(true).asText(USER_ID_LENGTH));
        eb.field("kind", f -> f.physicalColumn("kind").required(true).processOnly().asText(10));
        eb.field("preset", f -> f.physicalColumn("preset").processOnly().asText(10));
        eb.field("photo", f -> f.physicalColumn("photo").processOnly().kind(FileKind.of(PHOTO_POLICY)));
        eb.field("editedAt", f -> f.physicalColumn("edited_at").required(true).processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("version", f -> f.physicalColumn("version").asVersion());
        eb.listView("default", lv -> lv.columns("userId", "kind", "preset", "editedAt").filters("userId", "kind")
            .sorts("editedAt", "userId").defaultSort("editedAt", false));
    });

    /** The lock of a user's avatar: two saves of the same user's never interleave (the first inserts the row). */
    public static String lockName(Object userId) {
        return "qb.avatar:" + userId;
    }

    private QbAvatars() {
    }
}
