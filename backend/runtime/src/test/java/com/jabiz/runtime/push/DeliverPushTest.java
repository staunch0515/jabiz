package com.jabiz.runtime.push;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DeliverPushTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @SuppressWarnings("unchecked")
    private static Map<String, String> read(byte[] bytes) {
        return JSON.readValue(bytes, Map.class);
    }

    @Test
    void aShortMessageIsSentAsItIs() {
        byte[] bytes = DeliverPush.fit(JSON, "Shipped", "Order 7 is on its way.", "/portal/orders", "t1");
        assertThat(read(bytes)).containsExactly(Map.entry("title", "Shipped"),
            Map.entry("body", "Order 7 is on its way."), Map.entry("url", "/portal/orders"), Map.entry("tag", "t1"));
    }

    @Test
    void aLongBodyAndThenTitleAreShortenedOnCharactersToWhatEveryPushServiceAccepts() {
        // Three bytes a character, emoji four: the whole would be far over the limit.
        String body = "注文".repeat(1000) + "😀".repeat(300);
        byte[] bytes = DeliverPush.fit(JSON, "発送しました", body, "/portal/orders", "t1");
        assertThat(bytes.length).isLessThanOrEqualTo(WebPushCrypto.MAX_PLAINTEXT);
        Map<String, String> message = read(bytes);
        assertThat(message.get("title")).isEqualTo("発送しました");
        assertThat(message.get("body")).startsWith("注文注文").endsWith("…");
        assertThat(new String(bytes, StandardCharsets.UTF_8)).doesNotContain("�");
        // Close to the limit, not cut far below it.
        assertThat(bytes.length).isGreaterThan(WebPushCrypto.MAX_PLAINTEXT - 8);

        String title = "😀".repeat(2000);
        bytes = DeliverPush.fit(JSON, title, "body", "/portal/", "t1");
        assertThat(bytes.length).isLessThanOrEqualTo(WebPushCrypto.MAX_PLAINTEXT);
        message = read(bytes);
        assertThat(message.get("body")).isEmpty();
        assertThat(message.get("title")).startsWith("😀").endsWith("…");
        assertThat(message.get("title").codePoints().limit(message.get("title").codePointCount(0,
            message.get("title").length()) - 1)).allMatch(c -> c == 0x1F600);
    }
}
