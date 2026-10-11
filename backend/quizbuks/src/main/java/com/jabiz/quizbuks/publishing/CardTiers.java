package com.jabiz.quizbuks.publishing;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The reward tiers of a publication as its card keeps them (docs/quizbuks/plans/Q5-answering.md, D-Q5-6): frozen at
 * submission, so copied once when it goes on, as canonical JSON ({@code [{"fromScore":..,"reward":..,"tierId":..,
 * "toScore":..}]}, keys sorted, in the tiers' order). The detail page and the scoring of an attempt read them from the
 * card (an ordinary row) rather than from the temporal tiers.
 */
public final class CardTiers {

    /** One tier: its key, the scores it covers (both ends included) and its reward. */
    public record Tier(String tierId, int fromScore, int toScore, BigDecimal reward) {}

    private static final JsonMapper JSON = JsonMapper.builder()
        .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
        .build();

    private static final TypeReference<List<Map<String, Object>>> LIST = new TypeReference<>() {};

    public static String toJson(List<Tier> tiers) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Tier tier : tiers) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("tierId", tier.tierId());
            row.put("fromScore", tier.fromScore());
            row.put("toScore", tier.toScore());
            row.put("reward", tier.reward().toPlainString());
            rows.add(row);
        }
        return JSON.writeValueAsString(rows);
    }

    public static List<Tier> fromJson(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        List<Tier> tiers = new ArrayList<>();
        for (Map<String, Object> row : JSON.readValue(json, LIST)) {
            tiers.add(new Tier(String.valueOf(row.get("tierId")), ((Number) row.get("fromScore")).intValue(),
                ((Number) row.get("toScore")).intValue(), new BigDecimal(String.valueOf(row.get("reward")))));
        }
        return List.copyOf(tiers);
    }

    private CardTiers() {}
}
