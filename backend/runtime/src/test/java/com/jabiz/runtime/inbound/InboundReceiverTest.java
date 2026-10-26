package com.jabiz.runtime.inbound;

import com.jabiz.inbound.BodyHmacSignature;
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
import org.mockito.ArgumentCaptor;
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
import static org.mockito.ArgumentMatchers.eq;
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
            InboundWebhookChecksTest.provider("stripe", List.of(SECRET), List.of("payment_intent.succeeded")),
            "pay401", new InboundWebhookProperties.Provider("stripe", List.of(SECRET),
                List.of("payment_intent.succeeded"), null, null, null, null, null, 401),
            "shop", InboundWebhookChecksTest.bodyOnly(null, null, List.of("X-Shopify-Shop-Domain"), 401,
                List.of("orders/paid", "orders/create")))));
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

    // ---- a body-only scheme (decision D57) ------------------------------------------------------------------------

    private static final String ORDER = "{\"id\":820982911946154508,\"name\":\"#1001\"}";

    private Map<String, List<String>> shopHeaders(String body, String eventId, String topic) {
        Map<String, List<String>> headers = new java.util.LinkedHashMap<>();
        BodyHmacSignature.DEFAULT.sign(SECRET, 0, body.getBytes(StandardCharsets.UTF_8))
            .forEach((name, value) -> headers.put(name, List.of(value)));
        headers.put("X-Shopify-Event-Id", List.of(eventId));
        headers.put("X-Shopify-Topic", List.of(topic));
        headers.put("X-Shopify-Shop-Domain", List.of("demo.myshopify.com"));
        headers.put("X-Shopify-Webhook-Id", List.of("not-kept"));
        return headers;
    }

    private InboundReceiver.Outcome receiveShop(Map<String, List<String>> headers, String body) {
        return receiver.receive("shop", InboundHeaders.of(headers), "application/json",
            body.getBytes(StandardCharsets.UTF_8)).block();
    }

    private InboundProcesses.ReceiveInput stored() {
        ArgumentCaptor<InboundProcesses.ReceiveInput> input = ArgumentCaptor.forClass(InboundProcesses.ReceiveInput.class);
        verify(executor).run(eq(InboundProcesses.RECEIVE_PROCESS), input.capture(), any());
        return input.getValue();
    }

    private static int status(Runnable receiving) {
        try {
            receiving.run();
        } catch (InboundRejectedException e) {
            return e.status();
        }
        throw new AssertionError("not rejected");
    }

    @Test
    void aBodyOnlyEventKeepsItsMetadataGuardsReplaysAndHasNoSignedTime() {
        doReturn(Mono.just(new ProcessResult<>(1L, new InboundProcesses.ReceiveOutput("id"), false)))
            .when(executor).run(any(), any(), any());
        assertThat(receiveShop(shopHeaders(ORDER, "ev-1", "orders/paid"), ORDER))
            .isEqualTo(InboundReceiver.Outcome.ACCEPTED);
        InboundProcesses.ReceiveInput input = stored();
        assertThat(input.externalEventId()).isEqualTo("ev-1");
        assertThat(input.eventType()).isEqualTo("orders/paid");
        assertThat(input.signedTime()).isNull();
        assertThat(input.livemode()).isNull();
        assertThat(input.replayGuard()).isTrue();
        // Only the configured header, by its configured name.
        assertThat(input.metadata()).containsExactlyEntriesOf(Map.of("X-Shopify-Shop-Domain", "demo.myshopify.com"));
        assertThat(input.toString()).doesNotContain("820982911946154508");
        // The duplicate check asks for the id within its type, or the same body of the type.
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Map<String, com.jabiz.query.BoundValue>> params = ArgumentCaptor.forClass(Map.class);
        verify(engine).select(sql.capture(), params.capture());
        assertThat(sql.getValue()).contains("event_type = :type AND external_event_id = :id",
            "body_sha256 = :sha AND replay_guard");
        assertThat(params.getValue()).containsKeys("provider", "type", "id", "sha");
    }

    @Test
    void anEventOfASchemeThatSignsTimeIsNotGuardedByItsBody() {
        doReturn(Mono.just(new ProcessResult<>(1L, new InboundProcesses.ReceiveOutput("id"), false)))
            .when(executor).run(any(), any(), any());
        receive(EVENT);
        assertThat(stored().replayGuard()).isFalse();
        assertThat(stored().metadata()).isEmpty();
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(engine).select(sql.capture(), anyMap());
        assertThat(sql.getValue()).doesNotContain("body_sha256");
    }

    @Test
    void theUniqueIndexOfTheBodyMakesADuplicateToo() {
        doReturn(Mono.error(new UniqueKeyViolationException(InboundEntities.BODY_KEY, null)))
            .when(executor).run(any(), any(), any());
        assertThat(receiveShop(shopHeaders(ORDER, "ev-2", "orders/paid"), ORDER))
            .isEqualTo(InboundReceiver.Outcome.DUPLICATE);
    }

    @Test
    void signatureFailuresAnswerWithTheProvidersStatusAndOtherRejections400() {
        Map<String, List<String>> headers = shopHeaders(ORDER, "ev-3", "orders/paid");
        // Signed for another body.
        assertThat(status(() -> receiveShop(headers, ORDER.replace("1001", "1002")))).isEqualTo(401);
        tagged("shop", "REJECTED", "no_match");
        Map<String, List<String>> missing = new java.util.LinkedHashMap<>(headers);
        missing.remove("X-Shopify-Hmac-Sha256");
        assertThat(status(() -> receiveShop(missing, ORDER))).isEqualTo(401);
        Map<String, List<String>> malformed = new java.util.LinkedHashMap<>(headers);
        malformed.put("X-Shopify-Hmac-Sha256", List.of("@@"));
        assertThat(status(() -> receiveShop(malformed, ORDER))).isEqualTo(401);
        // Signed, but not an event: 400 whatever the provider's status.
        Map<String, List<String>> noId = new java.util.LinkedHashMap<>(headers);
        noId.remove("X-Shopify-Event-Id");
        assertThat(status(() -> receiveShop(noId, ORDER))).isEqualTo(400);
        // A time out of tolerance is not a failure of the signature.
        byte[] bytes = EVENT.getBytes(StandardCharsets.UTF_8);
        Map<String, String> late = new StripeSignature().sign(SECRET, NOW.getEpochSecond() - 3600, bytes);
        assertThat(status(() -> receiver.receive("pay401", InboundHeaders.of(Map.of(StripeSignature.HEADER,
            List.of(late.get(StripeSignature.HEADER)))), null, bytes).block())).isEqualTo(400);
        assertThat(status(() -> receiver.receive("pay401", InboundHeaders.of(Map.of()), null, bytes).block()))
            .isEqualTo(401);
        verify(executor, never()).run(any(), any(), any());
    }

    @Test
    void aMetadataHeaderMustBeThereOnceAndPrintable() {
        Map<String, List<String>> twice = new java.util.LinkedHashMap<>(shopHeaders(ORDER, "ev-4", "orders/paid"));
        twice.put("X-Shopify-Shop-Domain", List.of("a.myshopify.com", "b.myshopify.com"));
        assertThatThrownBy(() -> receiveShop(twice, ORDER)).isInstanceOfSatisfying(InboundRejectedException.class,
            e -> assertThat(e.reason()).isEqualTo("metadata_header"));
        Map<String, List<String>> headers = Map.of("X-Shop", List.of("x".repeat(256)));
        assertThatThrownBy(() -> InboundReceiver.metadata(List.of("X-Shop"), InboundHeaders.of(headers)))
            .isInstanceOf(InboundRejectedException.class);
        assertThatThrownBy(() -> InboundReceiver.metadata(List.of("X-Shop"),
            InboundHeaders.of(Map.of("X-Shop", List.of("t\u00f6kyo"))))).isInstanceOf(InboundRejectedException.class);
        assertThat(InboundReceiver.metadata(List.of("X-Shop", "X-Absent"),
            InboundHeaders.of(Map.of("x-shop", List.of(" " + "x".repeat(255) + " ")))))
            .containsExactlyEntriesOf(Map.of("X-Shop", "x".repeat(255)));
        verify(executor, never()).run(any(), any(), any());
    }

    @Test
    void aTopicNotListedIsIgnoredWhateverItsMetadata() {
        Map<String, List<String>> headers = new java.util.LinkedHashMap<>(shopHeaders(ORDER, "ev-6", "products/update"));
        headers.put("X-Shopify-Shop-Domain", List.of("a.myshopify.com", "b.myshopify.com"));
        assertThat(receiveShop(headers, ORDER)).isEqualTo(InboundReceiver.Outcome.IGNORED);
    }

    @Test
    void anotherTopicOfTheSameEventIdIsAnotherEvent() {
        doReturn(Mono.just(new ProcessResult<>(1L, new InboundProcesses.ReceiveOutput("id"), false)))
            .when(executor).run(any(), any(), any());
        receiveShop(shopHeaders(ORDER, "ev-5", "orders/create"), ORDER);
        ArgumentCaptor<Map<String, com.jabiz.query.BoundValue>> params = ArgumentCaptor.forClass(Map.class);
        verify(engine).select(anyString(), params.capture());
        assertThat(params.getValue().get("type")).isEqualTo(com.jabiz.query.BoundValue.of("orders/create"));
    }

    private void tagged(String provider, String result, String reason) {
        TestObservationRegistryAssert.assertThat(registry)
            .hasObservationWithNameEqualTo(PlatformObservations.INBOUND).that()
            .hasLowCardinalityKeyValue("provider", provider)
            .hasLowCardinalityKeyValue(PlatformObservations.RESULT, result)
            .hasLowCardinalityKeyValue(InboundReceiver.REASON, reason);
    }
}
