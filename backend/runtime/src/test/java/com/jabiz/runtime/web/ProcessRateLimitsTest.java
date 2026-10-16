package com.jabiz.runtime.web;

import com.jabiz.context.RequestContext;
import com.jabiz.process.NoMetadata;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.runtime.RateLimitedException;
import com.jabiz.runtime.observability.PlatformObservations;
import io.micrometer.observation.tck.TestObservationRegistry;
import io.micrometer.observation.tck.TestObservationRegistryAssert;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Rate limits of the process API (decision D45 item 5): per actor, per client address through trusted proxies only,
 * per instance; 429 with {@code Retry-After}; processes without limits are not counted.
 */
class ProcessRateLimitsTest {

    record In(String value) {}

    private static ProcessDefinition<In, In, ProcessContext> process(String name) {
        return ProcessDefinition.define(name, 1, In.class, In.class, ProcessContext.class, pb -> pb
            .permissions("p")
            .contextFactory((start, input) -> new ProcessContext(start))
            .outputMapper(ctx -> new In("x"))
            .compute("Nothing", (NoMetadata metadata, ProcessContext ctx) -> { }));
    }

    private static final ProcessDefinition<In, In, ProcessContext> LIMITED = process("LIMITED");
    private static final ProcessDefinition<In, In, ProcessContext> FREE = process("FREE");

    private final AtomicLong now = new AtomicLong();
    private final TestObservationRegistry registry = TestObservationRegistry.create();
    private final ClientAddresses addresses = new ClientAddresses(List.of("10.0.0.0/8"), null);

    private ProcessRateLimits limits(ProcessRateLimitProperties.Limit limit) {
        return new ProcessRateLimits(new ProcessRateLimitProperties(Map.of("LIMITED", limit)), addresses,
            new PlatformObservations(registry), now::get);
    }

    private static RequestContext actor(String id) {
        return new RequestContext(id, null, Locale.ENGLISH, "r", Set.of("p"), Set.of());
    }

    private static MockServerHttpRequest from(String connection, String... forwardedFor) {
        MockServerHttpRequest.BaseBuilder<?> builder = MockServerHttpRequest.post("/api/processes/LIMITED/latest")
            .remoteAddress(new InetSocketAddress(connection, 40000));
        for (String header : forwardedFor) {
            builder.header(ClientAddresses.FORWARDED_FOR, header);
        }
        return builder.build();
    }

    @Test
    void perUserEachActorHasABucketOfItsOwn() {
        ProcessRateLimits limits = limits(new ProcessRateLimitProperties.Limit(2, null, null, Duration.ofMinutes(1)));
        MockServerHttpRequest request = from("203.0.113.1");
        limits.check(LIMITED, actor("alice"), request);
        limits.check(LIMITED, actor("alice"), request);
        RateLimitedException refused = catchThrowableOfType(RateLimitedException.class,
            () -> limits.check(LIMITED, actor("alice"), request));
        // Two per minute: the next one in 30 seconds.
        assertThat(refused.retryAfterSeconds()).isEqualTo(30);
        assertThat(refused.getMessage()).contains("LIMITED");
        assertThatCode(() -> limits.check(LIMITED, actor("bob"), request)).doesNotThrowAnyException();
        // Without limits: never counted.
        for (int i = 0; i < 10; i++) {
            limits.check(FREE, actor("alice"), request);
        }
        assertThat(limits.limits("LIMITED")).isTrue();
        assertThat(limits.limits("FREE")).isFalse();

        now.addAndGet(Duration.ofSeconds(30).toNanos());
        assertThatCode(() -> limits.check(LIMITED, actor("alice"), request)).doesNotThrowAnyException();
        TestObservationRegistryAssert.assertThat(registry)
            .hasObservationWithNameEqualTo(PlatformObservations.PROCESS_RATE_LIMITED)
            .that().hasLowCardinalityKeyValue("process", "LIMITED").hasLowCardinalityKeyValue("scope", "user");
    }

    @Test
    void perAddressForwardedAddressesCountOnlyThroughTrustedProxies() {
        ProcessRateLimits limits = limits(new ProcessRateLimitProperties.Limit(null, 1, null, null));
        // Through the trusted proxy: the forwarded client.
        limits.check(LIMITED, actor("a"), from("10.0.0.1", "198.51.100.7"));
        assertThatCode(() -> limits.check(LIMITED, actor("b"), from("10.0.0.1", "198.51.100.8")))
            .doesNotThrowAnyException();
        catchThrowableOfType(RateLimitedException.class,
            () -> limits.check(LIMITED, actor("c"), from("10.0.0.2", "198.51.100.7")));
        // From an untrusted connection the header is not believed: forging it changes nothing.
        limits.check(LIMITED, actor("d"), from("203.0.113.5", "198.51.100.9"));
        catchThrowableOfType(RateLimitedException.class,
            () -> limits.check(LIMITED, actor("e"), from("203.0.113.5", "198.51.100.10")));
        // No request: counted as unknown, once.
        limits.check(LIMITED, actor("f"), null);
        catchThrowableOfType(RateLimitedException.class, () -> limits.check(LIMITED, actor("g"), null));
    }

    @Test
    void perInstanceEveryoneShares() {
        ProcessRateLimits limits = limits(new ProcessRateLimitProperties.Limit(null, null, 3, Duration.ofSeconds(3)));
        for (String actor : List.of("a", "b", "c")) {
            limits.check(LIMITED, actor(actor), from("203.0.113." + actor.length()));
        }
        RateLimitedException refused = catchThrowableOfType(RateLimitedException.class,
            () -> limits.check(LIMITED, actor("d"), from("203.0.113.99")));
        assertThat(refused.retryAfterSeconds()).isEqualTo(1);
        assertThat(refused.violations().getFirst().params()).containsEntry("retryAfter", 1L);
    }

    @Test
    void valuesOutOfRangeLimitNothing() {
        ProcessRateLimits zero = limits(new ProcessRateLimitProperties.Limit(0, -1, null, Duration.ofMinutes(1)));
        ProcessRateLimits noPeriod = limits(new ProcessRateLimitProperties.Limit(1, null, null, Duration.ZERO));
        for (int i = 0; i < 5; i++) {
            zero.check(LIMITED, actor("a"), from("203.0.113.1"));
            noPeriod.check(LIMITED, actor("a"), from("203.0.113.1"));
        }
        assertThat(noPeriod.limits("LIMITED")).isFalse();
    }
}
