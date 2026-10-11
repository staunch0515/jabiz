package com.jabiz.runtime.inbound;

import com.jabiz.inbound.InboundHeaders;
import com.jabiz.inbound.StripeSignature;
import com.jabiz.runtime.EntityNotFoundException;
import com.jabiz.runtime.observability.PlatformObservations;
import com.jabiz.runtime.process.ProcessExecutor;
import com.jabiz.runtime.process.ProcessResult;
import com.jabiz.runtime.storage.StorageAdapterRegistry;
import com.jabiz.runtime.storage.StorageEngine;
import com.jabiz.runtime.storage.UniqueKeyViolationException;
import io.micrometer.observation.tck.TestObservationRegistry;
import io.micrometer.observation.tck.TestObservationRegistryAssert;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The receiver's decisions without a database: what is answered as a duplicate (only the unique key of the external
 * event), what propagates, and what the observation {@code jabiz.inbound.receive} is tagged with each time.
 */
class InboundReceiverTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final String SECRET = InboundWebhookChecksTest.SECRET;

    private final TestObservationRegistry registry = TestObservationRegistry.create();
    private final ProcessExecutor executor = mock(ProcessExecutor.class);
    private final StorageEngine engine = mock(StorageEngine.class);
    private InboundReceiver receiver;

    @BeforeEach
    void receiver() {
        InboundWebhookChecks providers = new InboundWebhookChecks(new InboundWebhookProperties(Map.of("pay",
            InboundWebhookChecksTest.provider("stripe", List.of(SECRET), List.of("payment_intent.succeeded")))));
        StorageAdapterRegistry storages = mock(StorageAdapterRegistry.class);
        when(storages.getEngine(anyString())).thenReturn(engine);
        when(engine.select(anyString(), anyMap())).thenReturn(Flux.empty());
        receiver = new InboundReceiver(providers, executor, storages, "default", Clock.fixed(NOW, ZoneOffset.UTC),
            new PlatformObservations(registry));
    }

    private InboundReceiver.Outcome receive(String body) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        Map<String, String> signed = new StripeSignature().sign(SECRET, NOW.getEpochSecond(), bytes);
        return receiver.receive("pay", InboundHeaders.of(Map.of(StripeSignature.HEADER,
            List.of(signed.get(StripeSignature.HEADER)))), "application/json", bytes).block();
    }

    private static final String EVENT = "{\"id\":\"evt_1\",\"type\":\"payment_intent.succeeded\"}";

    private void tagged(String result, String reason) {
        TestObservationRegistryAssert.assertThat(registry)
            .hasObservationWithNameEqualTo(PlatformObservations.INBOUND).that()
            .hasLowCardinalityKeyValue("provider", "pay")
            .hasLowCardinalityKeyValue(PlatformObservations.RESULT, result)
            .hasLowCardinalityKeyValue(InboundReceiver.REASON, reason);
    }

    @Test
    void anAcceptedEventIsStoredByTheProcess() {
        doReturn(Mono.just(new ProcessResult<>(1L, new InboundProcesses.ReceiveOutput("id"), false)))
            .when(executor).run(any(), any(), any());
        assertThat(receive(EVENT)).isEqualTo(InboundReceiver.Outcome.ACCEPTED);
        tagged("ACCEPTED", "none");
    }

    @Test
    void theUniqueKeyOfTheExternalEventMakesADuplicate() {
        doReturn(Mono.error(new UniqueKeyViolationException(InboundEntities.EXTERNAL_KEY, null)))
            .when(executor).run(any(), any(), any());
        assertThat(receive(EVENT)).isEqualTo(InboundReceiver.Outcome.DUPLICATE);
        tagged("DUPLICATE", "none");
    }

    @Test
    void anyOtherUniqueKeyIsAnErrorThatPropagates() {
        doReturn(Mono.error(new UniqueKeyViolationException("op_process_pkey", null)))
            .when(executor).run(any(), any(), any());
        assertThatThrownBy(() -> receive(EVENT)).isInstanceOf(UniqueKeyViolationException.class);
        tagged("FAILED", "error");
    }

    @Test
    void anEventStoredBeforeIsADuplicateWithoutAnOperation() {
        when(engine.select(anyString(), anyMap())).thenReturn(Flux.just(Map.of("found", 1)));
        assertThat(receive(EVENT)).isEqualTo(InboundReceiver.Outcome.DUPLICATE);
        verify(executor, never()).run(any(), any(), any());
    }

    @Test
    void rejectionsAndIgnoredTypesAreTaggedWithTheirReason() {
        assertThat(receive(EVENT.replace("payment_intent.succeeded", "payment_intent.created")))
            .isEqualTo(InboundReceiver.Outcome.IGNORED);
        tagged("IGNORED", "none");
        registry.clear();
        byte[] bytes = EVENT.getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> receiver.receive("pay", InboundHeaders.of(Map.of(StripeSignature.HEADER,
            List.of("t=" + NOW.getEpochSecond() + ",v1=" + "0".repeat(64)))), null, bytes).block())
            .isInstanceOf(InboundRejectedException.class);
        tagged("REJECTED", "no_match");
        verify(executor, never()).run(any(), any(), any());
    }

    @Test
    void unknownProvidersAreNotFoundAndNotObserved() {
        assertThatThrownBy(() -> receiver.receive("nobody", InboundHeaders.of(Map.of()), null, new byte[0]).block())
            .isInstanceOf(EntityNotFoundException.class);
        assertThatThrownBy(() -> receiver.maxBodySize("nobody")).isInstanceOf(EntityNotFoundException.class);
        assertThat(receiver.maxBodySize("pay")).isEqualTo(1024 * 1024);
        TestObservationRegistryAssert.assertThat(registry).doesNotHaveAnyObservation();
    }

    @Test
    void theContentTypeIsKeptPrintableAndShort() {
        assertThat(InboundReceiver.contentType(null)).isNull();
        assertThat(InboundReceiver.contentType("  ")).isNull();
        assertThat(InboundReceiver.contentType("application/json; charset=utf-8"))
            .isEqualTo("application/json; charset=utf-8");
        assertThat(InboundReceiver.contentType("text/plain\r\nX-Injected: 1")).isEqualTo("text/plainX-Injected: 1");
        assertThat(InboundReceiver.contentType("a".repeat(500))).hasSize(100);
        assertThat(InboundReceiver.contentType("application/jsön")).isEqualTo("application/jsn");
    }
}
