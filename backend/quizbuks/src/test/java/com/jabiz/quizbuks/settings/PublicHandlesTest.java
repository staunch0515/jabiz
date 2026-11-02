package com.jabiz.quizbuks.settings;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** A public certification page's name (Q77, D-Q77-5). */
class PublicHandlesTest {

    @Test
    void namesAreTrimmedAndLowercasedAndBlankIsNone() {
        assertThat(PublicHandles.normalized("  Ann-Lee ")).isEqualTo("ann-lee");
        assertThat(PublicHandles.normalized("   ")).isNull();
        assertThat(PublicHandles.normalized(null)).isNull();
    }

    @Test
    void threeToThirtyLettersDigitsAndInnerSingleHyphens() {
        assertThat(PublicHandles.problem("abc")).isNull();
        assertThat(PublicHandles.problem("a-1")).isNull();
        assertThat(PublicHandles.problem("a".repeat(30))).isNull();
        assertThat(PublicHandles.problem("ab")).isEqualTo(PublicHandles.FORMAT);
        assertThat(PublicHandles.problem("a".repeat(31))).isEqualTo(PublicHandles.FORMAT);
        assertThat(PublicHandles.problem("-abc")).isEqualTo(PublicHandles.FORMAT);
        assertThat(PublicHandles.problem("abc-")).isEqualTo(PublicHandles.FORMAT);
        assertThat(PublicHandles.problem("a--b")).isEqualTo(PublicHandles.FORMAT);
        assertThat(PublicHandles.problem("ann lee")).isEqualTo(PublicHandles.FORMAT);
        assertThat(PublicHandles.problem("Ann")).isEqualTo(PublicHandles.FORMAT);
        assertThat(PublicHandles.problem("安妮安妮")).isEqualTo(PublicHandles.FORMAT);
        assertThat(PublicHandles.problem(null)).isEqualTo(PublicHandles.FORMAT);
    }

    @Test
    void theSitesOwnWordsAreReserved() {
        assertThat(PublicHandles.problem("admin")).isEqualTo(PublicHandles.RESERVED);
        assertThat(PublicHandles.problem("verify")).isEqualTo(PublicHandles.RESERVED);
        assertThat(PublicHandles.problem("quizbuks")).isEqualTo(PublicHandles.RESERVED);
        assertThat(PublicHandles.problem("admins")).isNull();
    }
}
