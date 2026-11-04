package com.jabiz.quizbuks.play;

import com.jabiz.runtime.EntityInstance;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The entry fee's check as the start asks it (Q88b, D-Q88-10). */
class EntryFeesTest {

    static EntityInstance row(String type, Map<String, Object> values) {
        return new EntityInstance("id", type, 1, null, values);
    }

    @Test
    void anUnusableHoldPeriodIsNoneAtAll() {
        assertThat(EntryFees.holdDays(List.of(row("SysParam", Map.of("value", "7"))))).contains(new BigDecimal("7"));
        assertThat(EntryFees.holdDays(List.of(row("SysParam", Map.of("value", "x"))))).isEmpty();
        assertThat(EntryFees.holdDays(List.of(row("SysParam", Map.of("value", "1.5"))))).isEmpty();
        assertThat(EntryFees.holdDays(List.of(row("SysParam", Map.of("value", "-1"))))).isEmpty();
        assertThat(EntryFees.holdDays(List.of())).isEmpty();
    }

    @Test
    void onlyATransferInReviewSetsKudosAside() {
        assertThat(EntryFees.inReview(List.of(row("QbTransfer", Map.of("status", "REVIEW", "amount", 80)))))
            .isEqualByComparingTo("80");
        assertThat(EntryFees.inReview(List.of(row("QbTransfer", Map.of("status", "SENDING", "amount", 80)))))
            .isEqualByComparingTo("0");
        assertThat(EntryFees.inReview(List.of())).isEqualByComparingTo("0");
    }

    @Test
    void theFeeIsCoveredByWhatIsTransferableLessTheReviewAndByTheCache() {
        EntityInstance wallet = row("QbWallet", Map.of("balance", new BigDecimal("100")));
        BigDecimal fee = new BigDecimal("30");
        assertThat(EntryFees.shortfall(new BigDecimal("100"), BigDecimal.ZERO, BigDecimal.ZERO, wallet, fee))
            .isEmpty();
        assertThat(EntryFees.shortfall(new BigDecimal("100"), BigDecimal.ZERO, new BigDecimal("80"), wallet, fee))
            .hasValueSatisfying(v -> assertThat(v.params()).containsEntry("available", "20")
                .containsEntry("short", "10"));
        assertThat(EntryFees.shortfall(new BigDecimal("100"), new BigDecimal("90"), BigDecimal.ZERO, wallet, fee))
            .isPresent();
        assertThat(EntryFees.shortfall(new BigDecimal("100"), BigDecimal.ZERO, BigDecimal.ZERO,
            row("QbWallet", Map.of("balance", new BigDecimal("20"))), fee)).isPresent();
        assertThat(EntryFees.shortfall(new BigDecimal("100"), BigDecimal.ZERO, BigDecimal.ZERO, null, fee)).isPresent();
    }
}
