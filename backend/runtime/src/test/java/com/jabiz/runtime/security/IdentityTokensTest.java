package com.jabiz.runtime.security;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The pauses between attempts to revoke a token, and what it is sealed for (decision D67 item 5). */
class IdentityTokensTest {

    @Test
    void thePauseDoublesFromAQuarterOfAnHourToADay() {
        assertThat(IdentityTokens.pause(0)).isEqualTo(Duration.ofMinutes(15));
        assertThat(IdentityTokens.pause(1)).isEqualTo(Duration.ofMinutes(15));
        assertThat(IdentityTokens.pause(2)).isEqualTo(Duration.ofMinutes(30));
        assertThat(IdentityTokens.pause(5)).isEqualTo(Duration.ofHours(4));
        assertThat(IdentityTokens.pause(7)).isEqualTo(Duration.ofHours(16));
        assertThat(IdentityTokens.pause(8)).isEqualTo(Duration.ofDays(1));
        assertThat(IdentityTokens.pause(IdentityTokens.MAX_ATTEMPTS)).isEqualTo(Duration.ofDays(1));
        assertThat(IdentityTokens.pause(Integer.MAX_VALUE)).isEqualTo(Duration.ofDays(1));
    }

    @Test
    void aTokenIsSealedForItsLinkAndNeverPrinted() {
        UUID link = UUID.fromString("0192f0a0-0000-7000-8000-0000000000a1");
        assertThat(IdentityTokens.context(link)).isEqualTo("identity-token:" + link);
        assertThat(new IdentityTokens.KeepInput(link, "apple", "sub", "rt-secret").toString()).doesNotContain("rt-secret");
        assertThat(new OidcClient.Tokens("id", "rt-secret").toString()).doesNotContain("rt-secret");
    }
}
