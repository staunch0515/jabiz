package com.jabiz.quizbuks.avatar;

import java.util.List;

/**
 * The preset avatars a taker can choose (Q114a; drawn on the prototype "学者头像原型 · Q114-0", 2026-11-27). Only the
 * codes live on the server: the drawings are the app's ({@code quizbuks-web/app/src/avatar/presets/}), and the list is
 * the same as the app's {@code avatar/presets.json} ({@code AvatarPresetsTest}). A code once offered is never reused
 * for another drawing: a taker's choice keeps meaning the same picture.
 */
public final class AvatarPresets {

    /** Every preset, in the order the picker shows them. */
    public static final List<String> CODES = List.of("p01", "p02", "p03", "p04", "p05", "p06", "p07", "p08", "p09",
        "p10", "p11", "p12");

    private AvatarPresets() {
    }
}
