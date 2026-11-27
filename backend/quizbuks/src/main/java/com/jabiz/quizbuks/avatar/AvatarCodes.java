package com.jabiz.quizbuks.avatar;

import java.util.List;

/** The refusals of a taker's avatar (Q114), each with a message in the three languages. */
public final class AvatarCodes {

    /** A preset that is not in {@link AvatarPresets#CODES}, or a preset with another kind (422). */
    public static final String PRESET_UNKNOWN = "QB_AVATAR_PRESET_UNKNOWN";

    /** Every code above. */
    public static final List<String> ALL = List.of(PRESET_UNKNOWN);

    private AvatarCodes() {
    }
}
