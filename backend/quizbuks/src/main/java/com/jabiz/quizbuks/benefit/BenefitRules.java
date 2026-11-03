package com.jabiz.quizbuks.benefit;

import com.jabiz.entity.Violation;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The rules of sponsored benefits (Q89a), pure Java: what a benefit and a purchase must say, what a message between
 * two sponsors may say, which side the caller is, and what is unread. Contact and payment details are allowed in
 * messages (D-Q89-4): unlike a taker's conversation, nothing is checked for addresses.
 */
public final class BenefitRules {

    /** The longest message between two sponsors, in characters. */
    public static final int BODY_LENGTH = 2000;
    /** The most of a benefit one purchase may hold. */
    public static final int MAX_QUANTITY = 1_000_000;

    private BenefitRules() {}

    /**
     * What a benefit says, as saved.
     *
     * @param images the images given (no more than {@link QbBenefits#MAX_IMAGES})
     */
    public record Offer(String kind, BigDecimal unitValue, String pickupAddress, String pickupHours, int images) {}

    public static List<Violation> offerProblems(Offer offer) {
        List<Violation> problems = new ArrayList<>();
        if (!wholeAtLeast(offer.unitValue(), 1)) {
            problems.add(new Violation("unitValue", BenefitCodes.VALUE, "A benefit is worth a whole amount of at least "
                + "1", Map.of("min", 1)));
        }
        if (!QbBenefits.DIGITAL.equals(offer.kind())) {
            if (blank(offer.pickupAddress())) {
                problems.add(pickup("pickupAddress"));
            }
            if (blank(offer.pickupHours())) {
                problems.add(pickup("pickupHours"));
            }
        }
        if (offer.images() > QbBenefits.MAX_IMAGES) {
            problems.add(new Violation("images", BenefitCodes.IMAGES, "A benefit has at most "
                + QbBenefits.MAX_IMAGES + " images", Map.of("max", QbBenefits.MAX_IMAGES)));
        }
        return problems;
    }

    /** What the sponsor says of a purchase. */
    public record Purchase(BigDecimal quantity, BigDecimal paidAmount, LocalDate paidOn, int proofs) {}

    /** The problems of a purchase's figures; {@code today} is the day in the deployment's zone. */
    public static List<Violation> purchaseProblems(Purchase purchase, LocalDate today) {
        List<Violation> problems = new ArrayList<>();
        if (!wholeAtLeast(purchase.quantity(), 1) || purchase.quantity().compareTo(BigDecimal.valueOf(MAX_QUANTITY)) > 0) {
            problems.add(new Violation("quantity", BenefitCodes.QUANTITY, "A purchase holds 1 to " + MAX_QUANTITY,
                Map.of("min", 1, "max", MAX_QUANTITY)));
        }
        if (!wholeAtLeast(purchase.paidAmount(), 0)) {
            problems.add(new Violation("paidAmount", BenefitCodes.PAID_AMOUNT, "The amount paid is a whole amount of "
                + "at least 0", Map.of("min", 0)));
        }
        if (purchase.paidOn() != null && today != null && purchase.paidOn().isAfter(today)) {
            problems.add(new Violation("paidOn", BenefitCodes.PAID_ON_FUTURE, "The payment cannot be dated after today",
                Map.of("today", today.toString())));
        }
        if (purchase.proofs() > QbBenefits.MAX_PROOFS) {
            problems.add(new Violation("proofs", BenefitCodes.PROOFS, "A purchase has at most "
                + QbBenefits.MAX_PROOFS + " proofs", Map.of("max", QbBenefits.MAX_PROOFS)));
        }
        return problems;
    }

    /** The problems of a message's text: empty or too long. */
    public static List<Violation> bodyProblems(String body) {
        List<Violation> problems = new ArrayList<>();
        String text = body == null ? "" : body.strip();
        if (text.isEmpty()) {
            problems.add(new Violation("body", BenefitCodes.BODY_REQUIRED, "Write a message", Map.of()));
        } else if (text.codePointCount(0, text.length()) > BODY_LENGTH) {
            problems.add(new Violation("body", BenefitCodes.BODY_TOO_LONG, "The message is too long",
                Map.of("max", BODY_LENGTH)));
        }
        return problems;
    }

    /** The caller's side of a conversation or a purchase, or null for neither (as not found). */
    public static String sideOf(String actorId, Object sponsorId, Object providerId) {
        if (actorId == null) {
            return null;
        }
        if (actorId.equals(String.valueOf(sponsorId))) {
            return QbBenefits.SPONSOR;
        }
        if (actorId.equals(String.valueOf(providerId))) {
            return QbBenefits.PROVIDER;
        }
        return null;
    }

    /** Whether {@code side} has something unread: the other side wrote last, after {@code side} last read. */
    public static boolean unread(String side, String lastSender, Instant lastMessageAt, Instant readAt) {
        return lastSender != null && !Objects.equals(lastSender, side) && lastMessageAt != null
            && (readAt == null || readAt.isBefore(lastMessageAt));
    }

    private static boolean wholeAtLeast(BigDecimal value, int min) {
        return value != null && value.stripTrailingZeros().scale() <= 0 && value.compareTo(BigDecimal.valueOf(min)) >= 0;
    }

    private static boolean blank(String text) {
        return text == null || text.isBlank();
    }

    private static Violation pickup(String field) {
        return new Violation(field, BenefitCodes.PICKUP_REQUIRED, "Goods and services say where and when they are "
            + "collected", Map.of());
    }
}
