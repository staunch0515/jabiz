package com.jabiz.quizbuks.publishing;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CopyNameTest {

    private static final String SMILE = "😀";

    @Test
    void aCopyNameFitsAndNeverCutsACharacterInHalf() {
        assertThat(PublicationProcesses.copyName("{name} (copy)", "Autumn")).isEqualTo("Autumn (copy)");
        // 93 characters as people count them fit with " (copy)": exactly 100.
        String fits = "x".repeat(92) + SMILE;
        assertThat(PublicationProcesses.copyName("{name} (copy)", fits)).isEqualTo(fits + " (copy)");
        // One more: the name loses its last whole character, the emoji.
        String longer = "x".repeat(93) + SMILE;
        String copy = PublicationProcesses.copyName("{name} (copy)", longer);
        assertThat(copy).isEqualTo("x".repeat(93) + " (copy)");
        String emojis = SMILE.repeat(100);
        String cut = PublicationProcesses.copyName("{name}（副本）", emojis);
        assertThat(cut.codePointCount(0, cut.length())).isEqualTo(PublishingLimits.NAME_LENGTH);
        assertThat(cut).isEqualTo(SMILE.repeat(96) + "（副本）");
    }
}
