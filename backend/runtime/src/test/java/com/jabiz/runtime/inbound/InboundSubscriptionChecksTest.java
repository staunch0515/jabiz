package com.jabiz.runtime.inbound;

import com.jabiz.event.EventSubscription;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class InboundSubscriptionChecksTest {

    record In(String id) {}

    private static final ProcessDefinition<In, In, ProcessContext> PROCESS = ProcessDefinition.define("IT_IN", 1,
        In.class, In.class, ProcessContext.class, pb -> pb
            .permissions("x")
            .contextFactory((start, input) -> new ProcessContext(start))
            .outputMapper(ctx -> new In("x"))
            .compute("Nothing", (metadata, ctx) -> { }));

    private static EventSubscription<?> subscription(String consumer, String type) {
        return EventSubscription.of(consumer, type, PROCESS, event -> new In("x"));
    }

    private static List<String> problems(List<EventSubscription<?>> subscriptions) {
        InboundWebhookProperties properties = new InboundWebhookProperties(Map.of(
            "pay", InboundWebhookChecksTest.provider("stripe", List.of(InboundWebhookChecksTest.SECRET),
                List.of("payment_intent.succeeded", "charge.refunded")),
            "peer", InboundWebhookChecksTest.provider("hmac-sha256", List.of(InboundWebhookChecksTest.SECRET),
                List.of("a.b"))));
        return new InboundSubscriptionChecks(properties, subscriptions).check().stream()
            .map(p -> p.severity() + " " + p.category() + " " + p.location() + ": " + p.message()).toList();
    }

    @Test
    void subscriptionsAndListedTypesMustMeet() {
        assertThat(problems(List.of(
            subscription("shop.paid", "jabiz.inbound.pay.payment_intent.succeeded"),
            subscription("shop.refunded", "jabiz.inbound.pay.charge.refunded"),
            subscription("peer", "jabiz.inbound.peer.a.b"),
            // A provider not configured here (another environment): nothing to compare.
            subscription("other", "jabiz.inbound.elsewhere.x"),
            subscription("plain", "jabiz.task.created")))).isEmpty();
    }

    @Test
    void aSubscribedTypeTheProviderDoesNotListIsAnErrorAndAListedTypeNobodyConsumesAWarning() {
        assertThat(problems(List.of(
            subscription("shop.paid", "jabiz.inbound.pay.payment_intent.succeeded"),
            subscription("shop.failed", "jabiz.inbound.pay.payment_intent.payment_failed"),
            subscription("peer", "jabiz.inbound.peer.a.b"))))
            .containsExactlyInAnyOrder(
                "ERROR INBOUND EventSubscription shop.failed: subscribes to jabiz.inbound.pay.payment_intent.payment_failed"
                    + " but provider pay does not list payment_intent.payment_failed: such events are answered and"
                    + " dropped",
                "WARNING INBOUND jabiz.inbound-webhooks.providers.pay.event-types: charge.refunded is accepted and"
                    + " stored but no subscription consumes jabiz.inbound.pay.charge.refunded");
    }
}
