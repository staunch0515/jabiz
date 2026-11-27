package com.jabiz.quizbuks.avatar;

import java.util.List;

/** The refusals of a taker's avatar (Q114), each with a message in the three languages. */
public final class AvatarCodes {

    /** A preset that is not in {@link AvatarPresets#CODES}, or a preset with another kind (422). */
    public static final String PRESET_UNKNOWN = "QB_AVATAR_PRESET_UNKNOWN";
    /**
     * Q114b: a photo that is not a file of the photos' policy the platform keeps, a photo kind without one, or a photo
     * with another kind (422).
     */
    public static final String PHOTO_INVALID = "QB_AVATAR_PHOTO_INVALID";
    /** Q114b: a photo newly chosen while {@code qb.avatar.photo.enabled} is not on (422). */
    public static final String PHOTO_DISABLED = "QB_AVATAR_PHOTO_DISABLED";

    /** Every code above. */
    public static final List<String> ALL = List.of(PRESET_UNKNOWN, PHOTO_INVALID, PHOTO_DISABLED);

    private AvatarCodes() {
    }
}
