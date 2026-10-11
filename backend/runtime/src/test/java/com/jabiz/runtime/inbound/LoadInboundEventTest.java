package com.jabiz.runtime.inbound;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LoadInboundEventTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void numbersAreExactDecimals() {
        Map<String, Object> body = LoadInboundEvent.parse(JSON,
            "{\"a\":10.10,\"b\":1600,\"c\":[0.1,{\"d\":-2.5e3}],\"e\":true,\"f\":null,\"g\":\"x\"}");
        assertThat(body.get("a")).isEqualTo(new BigDecimal("10.10"));
        assertThat(body.get("b")).isEqualTo(new BigDecimal("1600"));
        assertThat((List<?>) body.get("c")).first().isEqualTo(new BigDecimal("0.1"));
        assertThat(((Map<?, ?>) ((List<?>) body.get("c")).get(1)).get("d")).isEqualTo(new BigDecimal("-2.5e3"));
        assertThat(body).containsEntry("e", true).containsEntry("f", null).containsEntry("g", "x");
        ReceivedEvent event = new ReceivedEvent("id", "p", "x", "t", null, null, null, body);
        assertThat(event.at("c")).isInstanceOf(List.class);
        assertThat(event.at("a.b")).isNull();
        assertThat(event.at("missing.path")).isNull();
        assertThat(event.toString()).doesNotContain("10.10");
    }

    @Test
    void hugeExponentsAreRefusedHereToo() {
        assertThatThrownBy(() -> LoadInboundEvent.parse(JSON, "{\"a\":1e1001}"))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("out of range");
        assertThat(LoadInboundEvent.parse(JSON, "{\"a\":1e1000}").get("a")).isEqualTo(new BigDecimal("1e1000"));
    }
}
