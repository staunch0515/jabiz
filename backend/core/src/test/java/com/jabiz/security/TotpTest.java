package com.jabiz.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class TotpTest {

    private static final byte[] RFC_SECRET = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);

    /** RFC 6238 appendix B, SHA-1 column. */
    @ParameterizedTest
    @CsvSource({
        "59, 94287082",
        "1111111109, 07081804",
        "1111111111, 14050471",
        "1234567890, 89005924",
        "2000000000, 69279037",
        "20000000000, 65353130"
    })
    void matchesTheRfcTestVectors(long seconds, String expected) {
        assertThat(Totp.code(RFC_SECRET, Totp.step(Instant.ofEpochSecond(seconds)), 8)).isEqualTo(expected);
    }

    @Test
    void sixDigitCodesAreTheLastSixDigitsZeroPadded() {
        assertThat(Totp.code(RFC_SECRET, Totp.step(Instant.ofEpochSecond(1111111109)))).isEqualTo("081804");
    }

    @Test
    void acceptsTheCurrentStepAndOneEitherSide() {
        Instant now = Instant.ofEpochSecond(1_700_000_000L);
        long step = Totp.step(now);
        assertThat(Totp.verify(RFC_SECRET, Totp.code(RFC_SECRET, step), now, -1)).hasValue(step);
        assertThat(Totp.verify(RFC_SECRET, Totp.code(RFC_SECRET, step - 1), now, -1)).hasValue(step - 1);
        assertThat(Totp.verify(RFC_SECRET, Totp.code(RFC_SECRET, step + 1), now, -1)).hasValue(step + 1);
        assertThat(Totp.verify(RFC_SECRET, Totp.code(RFC_SECRET, step - 2), now, -1)).isEmpty();
        assertThat(Totp.verify(RFC_SECRET, Totp.code(RFC_SECRET, step + 2), now, -1)).isEmpty();
    }

    @Test
    void refusesACodeOfAStepAlreadyUsedOrEarlier() {
        Instant now = Instant.ofEpochSecond(1_700_000_000L);
        long step = Totp.step(now);
        String code = Totp.code(RFC_SECRET, step);
        assertThat(Totp.verify(RFC_SECRET, code, now, step)).isEmpty();
        assertThat(Totp.verify(RFC_SECRET, code, now, step + 1)).isEmpty();
        assertThat(Totp.verify(RFC_SECRET, code, now, step - 1)).hasValue(step);
    }

    @Test
    void refusesMalformedCodesAndOtherSecrets() {
        Instant now = Instant.ofEpochSecond(1_700_000_000L);
        String code = Totp.code(RFC_SECRET, Totp.step(now));
        assertThat(Totp.verify(RFC_SECRET, null, now, -1)).isEmpty();
        assertThat(Totp.verify(RFC_SECRET, "12345", now, -1)).isEmpty();
        assertThat(Totp.verify(RFC_SECRET, "12a456", now, -1)).isEmpty();
        assertThat(Totp.verify(RFC_SECRET, code.substring(0, 3) + " " + code.substring(3), now, -1)).isPresent();
        assertThat(Totp.verify("another secret 12345".getBytes(StandardCharsets.US_ASCII), code, now, -1)).isEmpty();
    }

    @Test
    void writesTheAddressAuthenticatorAppsRead() {
        assertThat(Totp.uri("jabiz demo", "alice@example", new byte[] {'H', 'e', 'l', 'l', 'o', '!'}))
            .isEqualTo("otpauth://totp/jabiz%20demo:alice%40example?secret=JBSWY3DPEE&issuer=jabiz%20demo"
                + "&algorithm=SHA1&digits=6&period=30");
    }

    @Test
    void base32RoundTripsAndMatchesTheRfc() {
        assertThat(Base32.encode("foobar".getBytes(StandardCharsets.US_ASCII))).isEqualTo("MZXW6YTBOI");
        assertThat(new String(Base32.decode("mzxw6-ytboi=="), StandardCharsets.US_ASCII)).isEqualTo("foobar");
        assertThat(Base32.decode(Base32.encode(RFC_SECRET))).isEqualTo(RFC_SECRET);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> Base32.decode("AB1"))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
