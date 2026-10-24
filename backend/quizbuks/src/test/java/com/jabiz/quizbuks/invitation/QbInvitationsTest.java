package com.jabiz.quizbuks.invitation;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class QbInvitationsTest {

    @Test
    void anAddressIsKeptTrimmedAndInLowerCase() {
        assertThat(QbInvitations.normalize("  Taro.Yamada@Example.COM ")).isEqualTo("taro.yamada@example.com");
        assertThat(QbInvitations.normalize("x@y.z")).isEqualTo("x@y.z");
    }

    @Test
    void whatIsNotAnAddressIsRefused() {
        assertThat(QbInvitations.normalize(null)).isNull();
        assertThat(QbInvitations.normalize("")).isNull();
        assertThat(QbInvitations.normalize("no-at-sign.example.com")).isNull();
        assertThat(QbInvitations.normalize("two words@example.com")).isNull();
        assertThat(QbInvitations.normalize("nodot@example")).isNull();
        assertThat(QbInvitations.normalize("a@" + "b".repeat(QbInvitations.EMAIL_LENGTH) + ".com")).isNull();
    }

    @Test
    void onlyAnInvitationNobodyStartedMayBeRemoved() {
        assertThat(InvitationStatus.removable(InvitationStatus.ADDED)).isTrue();
        assertThat(InvitationStatus.removable(InvitationStatus.QUEUED)).isTrue();
        assertThat(InvitationStatus.removable(InvitationStatus.SENT)).isTrue();
        assertThat(InvitationStatus.removable(InvitationStatus.STARTED)).isFalse();
        assertThat(InvitationStatus.removable(InvitationStatus.ANSWERED)).isFalse();
    }
}
