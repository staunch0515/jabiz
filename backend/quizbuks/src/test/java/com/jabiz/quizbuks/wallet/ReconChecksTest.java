package com.jabiz.quizbuks.wallet;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ReconChecksTest {

    @Test
    void reportsOnlyWhatDiffers() {
        List<ReconChecks.Item> items = ReconChecks.perParty(ReconChecks.SPONSOR_WALLET, List.of(
            Map.of("partyId", "a", "expected", new BigDecimal("100"), "actual", new BigDecimal("90")),
            // The template returns differences only; a row that does not differ is never an item.
            Map.of("partyId", "b", "expected", new BigDecimal("100"), "actual", new BigDecimal("100.0"))));
        assertThat(items).singleElement().satisfies(item -> {
            assertThat(item.partyId()).isEqualTo("a");
            assertThat(item.difference()).isEqualByComparingTo("-10");
        });
    }

    @Test
    void splitsThePendingRewardsIntoCountAndAmount() {
        List<ReconChecks.Item> items = ReconChecks.pending(List.of(Map.of("partyId", "a", "expectedCount", 2L,
            "actualCount", 1L, "expectedAmount", new BigDecimal("200"), "actualAmount", new BigDecimal("200"))));
        assertThat(items).extracting(ReconChecks.Item::check).containsExactly(ReconChecks.SPONSOR_PENDING_COUNT);
    }

    @Test
    void readsCodedRowsAndIgnoresUnknownChecks() {
        List<ReconChecks.Item> items = ReconChecks.coded(List.of(
            Map.of("checkCode", "TOTAL_2110", "expected", 10, "actual", 0),
            Map.of("checkCode", "TOPUP_FEE", "topUpId", "t1", "sponsorId", "s1", "expected", 180, "actual", 0),
            Map.of("checkCode", "SOMETHING_ELSE", "expected", 1, "actual", 0)));
        assertThat(items).extracting(ReconChecks.Item::check).containsExactly(ReconChecks.TOTAL_2110,
            ReconChecks.TOPUP_FEE);
        assertThat(items.get(1).reference()).isEqualTo("t1");
        assertThat(items.get(1).partyId()).isEqualTo("s1");
    }

    @Test
    void aRunWithoutDifferencesIsOk() {
        ReconChecks.Result result = ReconChecks.result(List.of(), 10);
        assertThat(result.status()).isEqualTo(ReconChecks.OK);
        assertThat(result.total()).isZero();
        assertThat(result.summary()).startsWith("{\"LEDGER_ZERO\":0,").contains("\"TOPUP_FEE\":0");
    }

    @Test
    void countsEveryDifferenceButKeepsAtMostTheMaximum() {
        List<ReconChecks.Item> found = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            found.add(new ReconChecks.Item(ReconChecks.SPONSOR_WALLET, "p" + i, null, BigDecimal.ONE,
                BigDecimal.ZERO));
        }
        found.add(new ReconChecks.Item(ReconChecks.LEDGER_ZERO, null, null, BigDecimal.ZERO, BigDecimal.TEN));
        ReconChecks.Result result = ReconChecks.result(found, 3);
        assertThat(result.status()).isEqualTo(ReconChecks.DIFF);
        assertThat(result.items()).hasSize(3);
        assertThat(result.total()).isEqualTo(6);
        assertThat(result.counts()).containsEntry(ReconChecks.SPONSOR_WALLET, 5)
            .containsEntry(ReconChecks.LEDGER_ZERO, 1);
        assertThat(result.summary()).contains("\"SPONSOR_WALLET\":5", "\"LEDGER_ZERO\":1");
    }
}
