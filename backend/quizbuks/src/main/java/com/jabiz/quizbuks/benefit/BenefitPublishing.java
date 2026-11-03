package com.jabiz.quizbuks.benefit;

import com.jabiz.entity.Violation;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A publication that rewards its winners with another sponsor's goods or services (Q89b-1, D-Q89-6 to D-Q89-8), pure
 * Java: the purchase as the draft saw it (the review approves it, so it is part of the publication), the prize's
 * default description, and what a submission must meet. The quota a publication takes from its purchase is its
 * {@code maxWinners}; what is left of a purchase is its quantity less the open allocations of the others and what the
 * released ones used.
 */
public final class BenefitPublishing {

    /** The reward method a purchase of goods or a service is given by (no new method, D-Q89-6). */
    public static final String OFFLINE_REDEEM = "OFFLINE_REDEEM";

    private static final JsonMapper JSON = JsonMapper.builder()
        .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
        .build();
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    private BenefitPublishing() {}

    /** The purchase as a draft saw it: what the review approves and what the submission checks again. */
    public record Snapshot(String purchaseId, String providerId, String kind, String title, BigDecimal unitValue,
        String pickupAddress, String pickupHours, String terms, long quantity, BigDecimal paidAmount, String paidOn,
        List<String> proofs, long revision) {

        public Snapshot {
            proofs = proofs == null ? List.of() : List.copyOf(proofs);
        }
    }

    /** The snapshot of a purchase's values (as read). */
    public static Snapshot of(Object purchaseId, Map<String, Object> purchase) {
        List<String> proofs = new ArrayList<>();
        for (String field : QbBenefits.proofFields()) {
            Object proof = purchase.get(field);
            if (proof != null) {
                proofs.add(String.valueOf(proof));
            }
        }
        return new Snapshot(String.valueOf(purchaseId), text(purchase.get("providerId")), text(purchase.get("kind")),
            text(purchase.get("title")), money(purchase.get("unitValue")), text(purchase.get("pickupAddress")),
            text(purchase.get("pickupHours")), text(purchase.get("terms")), whole(purchase.get("quantity")),
            money(purchase.get("paidAmount")), text(purchase.get("paidOn")), proofs, whole(purchase.get("revision")));
    }

    /** The canonical JSON of a snapshot; null for none. */
    public static String toJson(Snapshot snapshot) {
        if (snapshot == null) {
            return null;
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("purchaseId", snapshot.purchaseId());
        map.put("providerId", snapshot.providerId());
        map.put("kind", snapshot.kind());
        map.put("title", snapshot.title());
        map.put("unitValue", plain(snapshot.unitValue()));
        map.put("pickupAddress", snapshot.pickupAddress());
        map.put("pickupHours", snapshot.pickupHours());
        map.put("terms", snapshot.terms());
        map.put("quantity", snapshot.quantity());
        map.put("paidAmount", plain(snapshot.paidAmount()));
        map.put("paidOn", snapshot.paidOn());
        map.put("proofs", snapshot.proofs());
        map.put("revision", snapshot.revision());
        return JSON.writeValueAsString(map);
    }

    /** The snapshot of a JSON column; null for none. */
    @SuppressWarnings("unchecked")
    public static Snapshot fromJson(Object json) {
        if (json == null || json.toString().isBlank()) {
            return null;
        }
        Map<String, Object> map = JSON.readValue(json.toString(), MAP);
        return new Snapshot(text(map.get("purchaseId")), text(map.get("providerId")), text(map.get("kind")),
            text(map.get("title")), money(map.get("unitValue")), text(map.get("pickupAddress")),
            text(map.get("pickupHours")), text(map.get("terms")), whole(map.get("quantity")),
            money(map.get("paidAmount")), text(map.get("paidOn")),
            map.get("proofs") instanceof List<?> list ? (List<String>) list : List.of(), whole(map.get("revision")));
    }

    /**
     * What the winner reads when the sponsor wrote nothing: the benefit, where and when to collect it, and its terms
     * (within {@code max} characters).
     */
    public static String defaultDescription(Snapshot snapshot, int max) {
        StringBuilder text = new StringBuilder(snapshot.title());
        if (snapshot.pickupAddress() != null) {
            text.append('\n').append(snapshot.pickupAddress());
            if (snapshot.pickupHours() != null) {
                text.append(" · ").append(snapshot.pickupHours());
            }
        }
        if (snapshot.terms() != null) {
            text.append('\n').append(snapshot.terms());
        }
        String all = text.toString();
        return all.codePointCount(0, all.length()) <= max ? all
            : all.substring(0, all.offsetByCodePoints(0, max));
    }

    /**
     * What a submission of a publication drawing on a purchase meets (D-Q89-6, D-Q89-7).
     *
     * @param publisherId  the publication's sponsor
     * @param method       its reward method
     * @param stored       the purchase as the draft saw it
     * @param purchase     the purchase now (read under its lock); null when gone
     * @param maxWinners   the quota the publication asks for (null: none given)
     * @param rewards      its tiers' rewards
     * @param allocations  the purchase's allocations of the other publications (read under its lock)
     */
    public static List<Violation> problems(String publisherId, String method, Snapshot stored,
        Map<String, Object> purchase, Integer maxWinners, List<BigDecimal> rewards, List<Allocation> allocations) {
        List<Violation> problems = new ArrayList<>();
        if (purchase == null || stored == null) {
            problems.add(new Violation("benefitPurchaseId", BenefitCodes.PURCHASE_NOT_USABLE, "The purchase is not "
                + "a confirmed purchase of yours", Map.of()));
            return problems;
        }
        if (!PurchaseStatus.CONFIRMED.equals(purchase.get("status"))
            || !Objects.equals(publisherId, text(purchase.get("sponsorId")))) {
            problems.add(new Violation("benefitPurchaseId", BenefitCodes.PURCHASE_NOT_USABLE, "The purchase is not "
                + "a confirmed purchase of yours", Map.of("status", String.valueOf(purchase.get("status")))));
            return problems;
        }
        if (stored.revision() != whole(purchase.get("revision"))) {
            problems.add(new Violation("benefitPurchaseId", BenefitCodes.PURCHASE_CHANGED, "The purchase changed "
                + "since the draft was saved: save it again", Map.of("revision", whole(purchase.get("revision")))));
        }
        if (!OFFLINE_REDEEM.equals(method) || QbBenefits.DIGITAL.equals(stored.kind())) {
            problems.add(new Violation("rewardMethod", BenefitCodes.METHOD, "Goods and services are prizes collected "
                + "at the provider's place", Map.of("kind", String.valueOf(stored.kind()))));
        }
        // One prize per winner, worth the benefit (D-Q89-6): one tier pays, and it pays the benefit's value.
        List<BigDecimal> paying = rewards.stream().filter(r -> r != null && r.signum() > 0).toList();
        if (paying.size() != 1 || paying.getFirst().compareTo(stored.unitValue()) != 0) {
            problems.add(new Violation("tiers", BenefitCodes.ONE_TIER, "One tier gives the benefit, worth "
                + plain(stored.unitValue()), Map.of("value", plain(stored.unitValue()))));
        }
        if (maxWinners == null || maxWinners < 1) {
            problems.add(new Violation("maxWinners", BenefitCodes.QUOTA_REQUIRED, "Say how many of the purchase this "
                + "publication gives", Map.of()));
        } else {
            long left = left(stored.quantity(), allocations);
            if (maxWinners > left) {
                problems.add(new Violation("maxWinners", BenefitCodes.STOCK_SHORT, "Only " + left + " of the "
                    + "purchase are left", Map.of("left", left, "asked", maxWinners)));
            }
        }
        return problems;
    }

    /** A publication's share of a purchase: open (holding {@code quantity}) or released (having used {@code used}). */
    public record Allocation(String publicationId, boolean open, long quantity, long used) {}

    /** What is left of {@code quantity} after the open allocations' quantities and the released ones' use. */
    public static long left(long quantity, List<Allocation> allocations) {
        long taken = 0;
        for (Allocation allocation : allocations) {
            taken += allocation.open() ? allocation.quantity() : allocation.used();
        }
        return Math.max(0, quantity - taken);
    }

    private static String text(Object value) {
        return value == null ? null : value.toString();
    }

    private static BigDecimal money(Object value) {
        return value == null ? BigDecimal.ZERO : new BigDecimal(value.toString());
    }

    private static long whole(Object value) {
        return value == null ? 0 : new BigDecimal(value.toString()).longValue();
    }

    private static String plain(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }
}
