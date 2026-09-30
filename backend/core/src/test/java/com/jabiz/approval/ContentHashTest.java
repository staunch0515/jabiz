package com.jabiz.approval;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.StringLength;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContentHashTest {

    @Test
    void theCanonicalTextSortsKeysAndDropsTrailingZeros() {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("b", List.of(new BigDecimal("1.50"), 2L, 0.0, true));
        content.put("a", "x\"y\\z\n");
        content.put("c", null);
        content.put("d", Map.of("k", Instant.parse("2026-01-01T00:00:00Z")));
        content.put("e", 1.25f);
        assertThat(ContentHash.canonical(content)).isEqualTo(
            "{\"a\":\"x\\\"y\\\\z\\u000a\",\"b\":[1.5,2,0,true],\"c\":null,\"d\":{\"k\":\"2026-01-01T00:00:00Z\"},"
                + "\"e\":1.25}");
        assertThat(ContentHash.of(content)).hasSize(64).matches("[0-9a-f]{64}");
    }

    @Test
    void keysThatWriteAlikeAreRefused() {
        Map<Object, Object> content = new HashMap<>();
        content.put(1, "a");
        content.put("1", "b");
        @SuppressWarnings({"unchecked", "rawtypes"})
        Map<String, Object> raw = (Map) content;
        assertThatThrownBy(() -> ContentHash.of(raw)).hasMessageContaining("two keys");
    }

    @Test
    void valuesOfDifferentTypesHashDifferently() {
        UUID id = UUID.randomUUID();
        assertThat(ContentHash.of(Map.of("v", 1))).isNotEqualTo(ContentHash.of(Map.of("v", "1")));
        assertThat(ContentHash.of(Map.of("v", true))).isNotEqualTo(ContentHash.of(Map.of("v", "true")));
        assertThat(ContentHash.of(Map.of("v", id))).isEqualTo(ContentHash.of(Map.of("v", id.toString())));
        Instant instant = Instant.parse("2026-03-01T00:00:00Z");
        assertThat(ContentHash.of(Map.of("t", java.time.OffsetDateTime.parse("2026-03-01T09:00:00+09:00"))))
            .isEqualTo(ContentHash.of(Map.of("t", instant)))
            .isEqualTo(ContentHash.of(Map.of("t", instant.atZone(java.time.ZoneId.of("America/New_York")))));
    }

    @Property(tries = 300)
    void keyOrderAndNumberScaleDoNotMatter(@ForAll @IntRange(min = 1, max = 8) int size,
        @ForAll long seed, @ForAll @IntRange(min = 0, max = 6) int extraScale) {
        java.util.Random random = new java.util.Random(seed);
        List<String> keys = new ArrayList<>();
        Map<String, Object> first = new LinkedHashMap<>();
        for (int i = 0; i < size; i++) {
            String key = "k" + random.nextInt(1000) + "_" + i;
            keys.add(key);
            first.put(key, BigDecimal.valueOf(random.nextLong(), random.nextInt(4)));
        }
        Collections.shuffle(keys, random);
        Map<String, Object> second = new LinkedHashMap<>();
        for (String key : keys) {
            BigDecimal value = (BigDecimal) first.get(key);
            second.put(key, value.setScale(value.scale() + extraScale));
        }
        assertThat(ContentHash.of(second)).isEqualTo(ContentHash.of(first));
    }

    @Property(tries = 300)
    void changingAnyValueChangesTheHash(@ForAll @StringLength(max = 20) String a, @ForAll @StringLength(max = 20)
        String b, @ForAll long amount) {
        Map<String, Object> content = Map.of("text", a, "amount", BigDecimal.valueOf(amount));
        if (!a.equals(b)) {
            assertThat(ContentHash.of(Map.of("text", b, "amount", BigDecimal.valueOf(amount))))
                .isNotEqualTo(ContentHash.of(content));
        }
        assertThat(ContentHash.of(Map.of("text", a, "amount", BigDecimal.valueOf(amount).add(BigDecimal.ONE))))
            .isNotEqualTo(ContentHash.of(content));
    }
}
