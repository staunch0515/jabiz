package com.jabiz.runtime.inbound;

import com.jabiz.inbound.InboundHeaders;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InboundEventExtractorTest {

    private static final InboundHeaders NONE = InboundHeaders.of(Map.of());

    private static InboundEventExtractor.Extracted stripe(String body) {
        return InboundEventExtractor.extract("stripe", NONE, body.getBytes(StandardCharsets.UTF_8));
    }

    private static String reason(Runnable extraction) {
        try {
            extraction.run();
        } catch (InboundRejectedException e) {
            return e.reason();
        }
        throw new AssertionError("not rejected");
    }

    @Test
    void stripeEventsGiveTheirTopLevelIdTypeAndMode() {
        InboundEventExtractor.Extracted extracted = stripe("""
            {"id":"evt_1","object":"event","type":"payment_intent.succeeded","livemode":false,
             "data":{"object":{"id":"pi_1","amount_received":1000}}}""");
        assertThat(extracted.externalEventId()).isEqualTo("evt_1");
        assertThat(extracted.eventType()).isEqualTo("payment_intent.succeeded");
        assertThat(extracted.livemode()).isFalse();
        assertThat(extracted.text()).startsWith("{\"id\":\"evt_1\"");
        assertThat(stripe("{\"id\":\"evt_2\",\"type\":\"t\"}").livemode()).isNull();
    }

    @Test
    void whatIsNotAnEventIsRejected() {
        assertThat(reason(() -> stripe("{\"type\":\"t\"}"))).isEqualTo("no_event_id");
        assertThat(reason(() -> stripe("{\"id\":\"\",\"type\":\"t\"}"))).isEqualTo("no_event_id");
        assertThat(reason(() -> stripe("{\"id\":42,\"type\":\"t\"}"))).isEqualTo("no_event_id");
        assertThat(reason(() -> stripe("{\"id\":\"" + "e".repeat(256) + "\",\"type\":\"t\"}")))
            .isEqualTo("no_event_id");
        assertThat(reason(() -> stripe("{\"id\":\"evt 1\",\"type\":\"t\"}"))).isEqualTo("no_event_id");
        assertThat(stripe("{\"id\":\"" + "e".repeat(255) + "\",\"type\":\"t\"}").externalEventId()).hasSize(255);
        assertThat(reason(() -> stripe("{\"id\":\"evt_1\"}"))).isEqualTo("no_event_type");
        assertThat(reason(() -> stripe("{\"id\":\"evt_1\",\"type\":[\"t\"]}"))).isEqualTo("no_event_type");
        assertThat(reason(() -> stripe("[{\"id\":\"evt_1\",\"type\":\"t\"}]"))).isEqualTo("not_json");
        assertThat(reason(() -> stripe("\"evt_1\""))).isEqualTo("not_json");
        assertThat(reason(() -> stripe("{\"id\":\"evt_1\",\"type\":\"t\"} trailing"))).isEqualTo("not_json");
        assertThat(reason(() -> stripe("{\"id\":\"evt_1\",\"id\":\"evt_2\",\"type\":\"t\"}"))).isEqualTo("not_json");
        assertThat(reason(() -> stripe(""))).isEqualTo("not_json");
        assertThat(reason(() -> stripe("{\"id\":\"evt_1\",\"type\":\"t\",\"livemode\":\"yes\"}")))
            .isEqualTo("not_json");
    }

    @Test
    void theBodyMustBeStrictUtf8() {
        byte[] latin1 = "{\"id\":\"évt\",\"type\":\"t\"}".getBytes(StandardCharsets.ISO_8859_1);
        assertThat(reason(() -> InboundEventExtractor.extract("stripe", NONE, latin1))).isEqualTo("not_utf8");
        byte[] overlong = {'{', '"', 'a', '"', ':', '"', (byte) 0xC0, (byte) 0xAF, '"', '}'};
        assertThat(reason(() -> InboundEventExtractor.extract("stripe", NONE, overlong))).isEqualTo("not_utf8");
        assertThat(InboundEventExtractor.extract("stripe", NONE,
            "{\"id\":\"evt_1\",\"type\":\"t\",\"name\":\"東京\"}".getBytes(StandardCharsets.UTF_8)).text())
            .contains("東京");
    }

    @Test
    void genericEventsAreNamedByTheirSignedBody() {
        byte[] body = "{\"eventId\":\"0190-1\",\"eventType\":\"finance.invoice-posted\",\"payload\":{}}"
            .getBytes(StandardCharsets.UTF_8);
        InboundEventExtractor.Extracted extracted = InboundEventExtractor.extract("hmac-sha256", NONE, body);
        assertThat(extracted.externalEventId()).isEqualTo("0190-1");
        assertThat(extracted.eventType()).isEqualTo("finance.invoice-posted");
        assertThat(extracted.livemode()).isNull();
        // The headers of the outbound format may come along, but they are not signed: they must agree.
        InboundHeaders agreeing = InboundHeaders.of(Map.of("X-Jabiz-Event-Id", List.of("0190-1"),
            "X-Jabiz-Event-Type", List.of("finance.invoice-posted")));
        assertThat(InboundEventExtractor.extract("hmac-sha256", agreeing, body).externalEventId()).isEqualTo("0190-1");
        InboundHeaders forged = InboundHeaders.of(Map.of("X-Jabiz-Event-Id", List.of("0190-2")));
        assertThat(reason(() -> InboundEventExtractor.extract("hmac-sha256", forged, body)))
            .isEqualTo("header_mismatch");
        InboundHeaders retyped = InboundHeaders.of(Map.of("X-Jabiz-Event-Type", List.of("ledger.posted")));
        assertThat(reason(() -> InboundEventExtractor.extract("hmac-sha256", retyped, body)))
            .isEqualTo("header_mismatch");
        assertThat(reason(() -> InboundEventExtractor.extract("hmac-sha256", NONE,
            "{\"eventType\":\"x\"}".getBytes(StandardCharsets.UTF_8)))).isEqualTo("no_event_id");
    }

    @Test
    void rejectionsTellTheCallerNothing() {
        InboundRejectedException e = new InboundRejectedException("no_match");
        assertThat(e.getMessage()).isEqualTo(InboundRejectedException.MESSAGE).doesNotContain("no_match");
        assertThat(e.reason()).isEqualTo("no_match");
        assertThatThrownBy(() -> InboundEventExtractor.extract("slack", NONE, new byte[0]))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
