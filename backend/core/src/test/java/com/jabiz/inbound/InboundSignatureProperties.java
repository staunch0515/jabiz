package com.jabiz.inbound;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;
import net.jqwik.api.constraints.StringLength;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Whatever the body and the secret: a signed request verifies, and changing any one byte breaks it. */
class InboundSignatureProperties {

    private static final long T = 1_767_225_600L;
    private static final Duration TOLERANCE = Duration.ofMinutes(5);

    @Property(tries = 300)
    void signedBodiesVerify(@ForAll @Size(max = 2048) byte[] body,
        @ForAll @StringLength(min = 32, max = 80) String secret, @ForAll("schemes") String name) {
        InboundSignatureScheme scheme = InboundSchemes.of(name).orElseThrow();
        assertThat(verify(scheme, secret, body, body).accepted()).isTrue();
    }

    @Property(tries = 300)
    void anyChangedByteFails(@ForAll @Size(min = 1, max = 2048) byte[] body,
        @ForAll @StringLength(min = 32, max = 80) String secret, @ForAll("schemes") String name,
        @ForAll @IntRange(min = 0, max = 2047) int position, @ForAll @IntRange(min = 1, max = 255) int flip) {
        InboundSignatureScheme scheme = InboundSchemes.of(name).orElseThrow();
        byte[] changed = body.clone();
        changed[position % changed.length] ^= (byte) flip;
        InboundVerification result = verify(scheme, secret, body, changed);
        assertThat(result.accepted()).isFalse();
        assertThat(result.reason()).isEqualTo(InboundVerification.Reason.NO_MATCH);
    }

    @net.jqwik.api.Provide
    net.jqwik.api.Arbitrary<String> schemes() {
        return net.jqwik.api.Arbitraries.of(InboundSchemes.names());
    }

    private static InboundVerification verify(InboundSignatureScheme scheme, String secret, byte[] signed,
        byte[] received) {
        Map<String, List<String>> headers = new java.util.HashMap<>();
        scheme.sign(secret, T, signed).forEach((header, value) -> headers.put(header, List.of(value)));
        return scheme.verify(InboundHeaders.of(headers), received, List.of(secret), Instant.ofEpochSecond(T),
            TOLERANCE);
    }
}
