package com.jabiz.runtime.external;

import com.jabiz.context.RequestContext;
import com.jabiz.entity.Violation;
import com.jabiz.external.ExternalJobType;
import com.jabiz.i18n.PlatformErrorCodes;
import com.jabiz.runtime.BusinessRuleViolationException;
import com.jabiz.runtime.context.RequestContexts;
import com.jabiz.runtime.observability.PlatformObservations;
import com.jabiz.runtime.param.ParamService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.support.GenericApplicationContext;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Which job types their business parameter holds (decision D52 item 4), without a database. */
class ExternalJobGateTest {

    record In(String id) {}

    record Out(String text) {}

    static final Instant NOW = Instant.parse("2026-10-19T08:00:00Z");

    /** Values by key, read as the system at the clock's now; a key without one is missing. */
    static final class Params extends ParamService {
        final Map<String, Object> values = new HashMap<>();
        final List<String> reads = new ArrayList<>();
        final List<String> actors = new ArrayList<>();
        RuntimeException failure;

        Params() {
            super(null, null);
        }

        @Override
        public Mono<Object> get(String key, Instant asOf) {
            assertThat(asOf).isEqualTo(NOW);
            return RequestContexts.current().flatMap(request -> {
                reads.add(key);
                actors.add(request.actorId());
                if (failure != null) {
                    return Mono.error(failure);
                }
                if (!values.containsKey(key)) {
                    return Mono.error(new BusinessRuleViolationException(new Violation(null,
                        PlatformErrorCodes.PARAM_NOT_FOUND, "missing", Map.of("key", key))));
                }
                return Mono.just(values.get(key));
            });
        }
    }

    private static ExternalJobType<?, ?> type(String name, String key) {
        return ExternalJobType.define(name, In.class, Out.class, t -> {
            t.onCompleted("A").onFailed("B");
            if (key != null) {
                t.enabledBy(key);
            }
        });
    }

    private static <T> ObjectProvider<T> provider(Class<T> type, T bean) {
        GenericApplicationContext beans = new GenericApplicationContext();
        if (bean != null) {
            beans.registerBean(type, () -> bean);
        }
        beans.refresh();
        return beans.getBeanProvider(type);
    }

    private final Params params = new Params();
    private final MeterRegistry meters = new SimpleMeterRegistry();
    private final ExternalJobGate gate = new ExternalJobGate(provider(ParamService.class, params),
        provider(MeterRegistry.class, meters), Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void onlyABooleanTrueLetsATypeBeClaimed() {
        assertThat(ExternalJobGate.reason(true)).isEmpty();
        assertThat(ExternalJobGate.reason(false)).isEqualTo("is false");
        assertThat(ExternalJobGate.reason("true")).isEqualTo("is not a boolean");
        assertThat(ExternalJobGate.reason(java.math.BigDecimal.ONE)).isEqualTo("is not a boolean");
        assertThat(ExternalJobGate.reason(null)).isEqualTo("has no value in effect");
    }

    @Test
    void eachKeyIsReadOnceAsTheSystemAndOnlyGatedTypesAreHeld() {
        params.values.put("it.on", true);
        params.values.put("it.off", false);
        params.values.put("it.text", "yes");
        Map<String, String> held = gate.held(List.of(type("it.plain", null), type("it.a", "it.on"),
            type("it.b", "it.on"), type("it.c", "it.off"), type("it.d", "it.text"), type("it.e", "it.gone"))).block();
        assertThat(held).containsExactly(
            Map.entry("it.c", "parameter it.off is false"),
            Map.entry("it.d", "parameter it.text is not a boolean"),
            Map.entry("it.e", "parameter it.gone has no value in effect"));
        assertThat(params.reads).containsExactly("it.on", "it.off", "it.text", "it.gone");
        assertThat(params.actors).containsOnly(RequestContext.SYSTEM_ACTOR);
    }

    @Test
    void typesWithoutAParameterReadNothing() {
        assertThat(gate.held(List.of(type("it.plain", null))).block()).isEmpty();
        assertThat(params.reads).isEmpty();
    }

    @Test
    void aParameterThatCannotBeReadHolds() {
        params.failure = new IllegalStateException("database down");
        assertThat(gate.held(List.of(type("it.a", "it.on"))).block())
            .containsExactly(Map.entry("it.a", "parameter it.on cannot be read (IllegalStateException)"));
        ExternalJobGate withoutService = new ExternalJobGate(provider(ParamService.class, null),
            provider(MeterRegistry.class, null), Clock.fixed(NOW, ZoneOffset.UTC));
        assertThat(withoutService.held(List.of(type("it.a", "it.on"))).block())
            .containsExactly(Map.entry("it.a", "parameter it.on cannot be read (no parameter service)"));
    }

    @Test
    void theGaugeFollowsTheTypeAndCarriesItsNameOnly() {
        params.values.put("it.on", false);
        gate.held(List.of(type("it.a", "it.on"))).block();
        var gauge = meters.get(PlatformObservations.EXTERNAL_JOB_HELD).gauge();
        assertThat(gauge.value()).isEqualTo(1);
        assertThat(gauge.getId().getTags()).singleElement().satisfies(tag -> {
            assertThat(tag.getKey()).isEqualTo("job_type");
            assertThat(tag.getValue()).isEqualTo("it.a");
        });
        params.values.put("it.on", true);
        assertThat(gate.held(List.of(type("it.a", "it.on"))).block()).isEmpty();
        assertThat(gauge.value()).isZero();
    }
}
