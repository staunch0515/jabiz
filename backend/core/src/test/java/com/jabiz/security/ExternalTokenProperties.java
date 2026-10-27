package com.jabiz.security;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Whatever the signing input and secret: a token verifies with its own signature, and any changed byte breaks it. */
class ExternalTokenProperties {

    @Property(tries = 300)
    void signedInputsVerify(@ForAll @Size(min = 32, max = 64) byte[] secret) {
        byte[] signature = ExternalTokenRules.hmac(secret, ExternalTokenRulesTest.SIGNING_INPUT
            .getBytes(StandardCharsets.US_ASCII));
        assertThat(ExternalTokenRules.verify(ExternalTokenRulesTest.header(), ExternalTokenRulesTest.claims(),
            ExternalTokenRulesTest.SIGNING_INPUT, signature, List.of(secret), ExternalTokenRulesTest.SETTINGS,
            ExternalTokenRulesTest.NOW).tenant()).isEqualTo("demo.myshopify.com");
    }

    @Property(tries = 300)
    void anyChangedSignatureByteFails(@ForAll @Size(min = 32, max = 64) byte[] secret,
        @ForAll @IntRange(min = 0, max = 31) int position, @ForAll @IntRange(min = 1, max = 255) int flip) {
        byte[] signature = ExternalTokenRules.hmac(secret, ExternalTokenRulesTest.SIGNING_INPUT
            .getBytes(StandardCharsets.US_ASCII));
        signature[position] ^= (byte) flip;
        assertThatThrownBy(() -> ExternalTokenRules.verify(ExternalTokenRulesTest.header(),
            ExternalTokenRulesTest.claims(), ExternalTokenRulesTest.SIGNING_INPUT, signature, List.of(secret),
            ExternalTokenRulesTest.SETTINGS, ExternalTokenRulesTest.NOW))
            .isInstanceOfSatisfying(ExternalTokenRules.RefusedException.class,
                e -> assertThat(e.failure()).isEqualTo(ExternalTokenRules.Failure.SIGNATURE));
    }
}
