package com.jabiz.quizbuks.benefit;

import com.jabiz.entity.Violation;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The rules of sponsored benefits (Q89a): what a benefit, a purchase and a message say, sides and unread. */
class BenefitRulesTest {

    private static final LocalDate TODAY = LocalDate.parse("2026-11-03");
    private static final Instant NOW = Instant.parse("2026-11-03T03:00:00Z");

    private static List<String> codes(List<Violation> problems) {
        return problems.stream().map(Violation::ruleCode).toList();
    }

    private static List<String> offer(String kind, String value, String address, String hours, int images) {
        return codes(BenefitRules.offerProblems(new BenefitRules.Offer(kind, value == null ? null : new BigDecimal(value),
            address, hours, images)));
    }

    private static List<String> purchase(String quantity, String paid, LocalDate paidOn, int proofs) {
        return codes(BenefitRules.purchaseProblems(new BenefitRules.Purchase(new BigDecimal(quantity),
            new BigDecimal(paid), paidOn, proofs), TODAY));
    }

    @Test
    void goodsAndServicesSayWhereAndWhenADigitalProductNeedsNeither() {
        assertThat(offer(QbBenefits.GOODS, "30", "Ramen B, 1-2-3 Shibuya", "11:00-21:00", 1)).isEmpty();
        assertThat(offer(QbBenefits.SERVICE, "30", " ", null, 0))
            .containsExactly(BenefitCodes.PICKUP_REQUIRED, BenefitCodes.PICKUP_REQUIRED);
        assertThat(offer(QbBenefits.DIGITAL, "500", null, null, 0)).isEmpty();
    }

    @Test
    void aBenefitIsWorthAWholeAmountOfAtLeastOneWithFewImages() {
        assertThat(offer(QbBenefits.DIGITAL, "0", null, null, 0)).containsExactly(BenefitCodes.VALUE);
        assertThat(offer(QbBenefits.DIGITAL, "1.5", null, null, 0)).containsExactly(BenefitCodes.VALUE);
        assertThat(offer(QbBenefits.DIGITAL, "1.00", null, null, 0)).isEmpty();
        assertThat(offer(QbBenefits.DIGITAL, null, null, null, 0)).containsExactly(BenefitCodes.VALUE);
        assertThat(offer(QbBenefits.DIGITAL, "1", null, null, QbBenefits.MAX_IMAGES + 1))
            .containsExactly(BenefitCodes.IMAGES);
    }

    @Test
    void aPurchaseHoldsAWholeQuantityAWholeAmountAndAPaymentNotLaterThanToday() {
        assertThat(purchase("100", "3000", TODAY, 1)).isEmpty();
        // Bought for nothing (a provider's own campaign) is a purchase too.
        assertThat(purchase("1", "0", TODAY.minusYears(1), 0)).isEmpty();
        assertThat(purchase("0", "3000", TODAY, 1)).containsExactly(BenefitCodes.QUANTITY);
        assertThat(purchase("2.5", "3000", TODAY, 1)).containsExactly(BenefitCodes.QUANTITY);
        assertThat(purchase(String.valueOf(BenefitRules.MAX_QUANTITY + 1), "3000", TODAY, 1))
            .containsExactly(BenefitCodes.QUANTITY);
        assertThat(purchase("100", "-1", TODAY, 1)).containsExactly(BenefitCodes.PAID_AMOUNT);
        assertThat(purchase("100", "29.9", TODAY, 1)).containsExactly(BenefitCodes.PAID_AMOUNT);
        assertThat(purchase("100", "3000", TODAY.plusDays(1), 1)).containsExactly(BenefitCodes.PAID_ON_FUTURE);
        assertThat(purchase("100", "3000", TODAY, QbBenefits.MAX_PROOFS + 1)).containsExactly(BenefitCodes.PROOFS);
    }

    @Test
    void aMessageMayCarryContactDetailsWithinItsLength() {
        // Unlike a taker's conversation (D-Q89-4): the two sponsors settle the payment between them.
        assertThat(codes(BenefitRules.bodyProblems("Transfer to Mizuho 1234567, call 03-1234-5678, a@b.jp"))).isEmpty();
        assertThat(codes(BenefitRules.bodyProblems(null))).containsExactly(BenefitCodes.BODY_REQUIRED);
        assertThat(codes(BenefitRules.bodyProblems("  "))).containsExactly(BenefitCodes.BODY_REQUIRED);
        assertThat(codes(BenefitRules.bodyProblems("麺".repeat(BenefitRules.BODY_LENGTH)))).isEmpty();
        assertThat(codes(BenefitRules.bodyProblems("麺".repeat(BenefitRules.BODY_LENGTH + 1))))
            .containsExactly(BenefitCodes.BODY_TOO_LONG);
    }

    @Test
    void theCallerIsTheSponsorTheProviderOrNeither() {
        assertThat(BenefitRules.sideOf("a", "a", "b")).isEqualTo(QbBenefits.SPONSOR);
        assertThat(BenefitRules.sideOf("b", "a", "b")).isEqualTo(QbBenefits.PROVIDER);
        assertThat(BenefitRules.sideOf("c", "a", "b")).isNull();
        assertThat(BenefitRules.sideOf(null, "a", "b")).isNull();
    }

    @Test
    void unreadIsTheOtherSidesMessageAfterTheLastRead() {
        assertThat(BenefitRules.unread(QbBenefits.SPONSOR, QbBenefits.PROVIDER, NOW, null)).isTrue();
        assertThat(BenefitRules.unread(QbBenefits.SPONSOR, QbBenefits.PROVIDER, NOW, NOW.minusSeconds(1))).isTrue();
        assertThat(BenefitRules.unread(QbBenefits.SPONSOR, QbBenefits.PROVIDER, NOW, NOW)).isFalse();
        assertThat(BenefitRules.unread(QbBenefits.SPONSOR, QbBenefits.SPONSOR, NOW, null)).isFalse();
    }

    @Test
    void aSavedChangeOfAPurchaseWaitsForTheProviderAgain() {
        assertThat(PurchaseStatus.afterSave(PurchaseStatus.DRAFT)).isEqualTo(PurchaseStatus.DRAFT);
        assertThat(PurchaseStatus.afterSave(PurchaseStatus.PENDING_PROVIDER)).isEqualTo(PurchaseStatus.PENDING_PROVIDER);
        assertThat(PurchaseStatus.afterSave(PurchaseStatus.CONFIRMED)).isEqualTo(PurchaseStatus.PENDING_PROVIDER);
        assertThat(PurchaseStatus.ALL).filteredOn(PurchaseStatus::editable)
            .containsExactly(PurchaseStatus.DRAFT, PurchaseStatus.PENDING_PROVIDER, PurchaseStatus.CONFIRMED);
        assertThat(PurchaseStatus.ALL).filteredOn(PurchaseStatus::submittable).containsExactly(PurchaseStatus.DRAFT);
        assertThat(PurchaseStatus.ALL).filteredOn(PurchaseStatus::answerable)
            .containsExactly(PurchaseStatus.PENDING_PROVIDER);
        assertThat(PurchaseStatus.cancellable(PurchaseStatus.CANCELLED)).isFalse();
    }
}
