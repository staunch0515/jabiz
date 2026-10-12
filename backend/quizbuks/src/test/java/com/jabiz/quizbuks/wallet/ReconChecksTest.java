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

    static Map<String, Object> taker(String party, long balance, long cached, long out, long cachedOut) {
        Map<String, Object> row = new java.util.LinkedHashMap<>();
        row.put("partyId", party);
        row.put("expectedBalance", BigDecimal.valueOf(balance));
        row.put("actualBalance", BigDecimal.valueOf(cached));
        row.put("expectedOut", BigDecimal.valueOf(out));
        row.put("actualOut", BigDecimal.valueOf(cachedOut));
        return row;
    }

    @Test
    void aTakerBehindCountsOnlyWhenTwoRunsInARowFindThem() {
        List<Map<String, Object>> rows = List.of(taker("u1", 100, 90, 0, 0), taker("u2", 50, 50, 30, 0),
            taker("u3", 10, 10, 0, 0));
        // First run: notes only, everyone found resynced; the run is OK.
        ReconChecks.TakerItems first = ReconChecks.takers(rows, java.util.Set.of());
        assertThat(first.items()).extracting(ReconChecks.Item::check).containsOnly(ReconChecks.TAKER_WALLET_LAG);
        assertThat(first.items()).hasSize(2);
        assertThat(first.resync()).containsExactly("u1", "u2");
        ReconChecks.Result notes = ReconChecks.result(first.items(), 10);
        assertThat(notes.status()).isEqualTo(ReconChecks.OK);
        assertThat(notes.differences()).isZero();
        assertThat(notes.total()).isEqualTo(2);
        // Next run: u1 still behind is a difference; u2 was found too, its amount in transfer differs.
        ReconChecks.TakerItems second = ReconChecks.takers(rows, java.util.Set.of("u1", "u2"));
        assertThat(second.items()).extracting(ReconChecks.Item::check)
            .containsExactly(ReconChecks.TAKER_WALLET, ReconChecks.TAKER_IN_TRANSFER);
        ReconChecks.Result diff = ReconChecks.result(second.items(), 10);
        assertThat(diff.status()).isEqualTo(ReconChecks.DIFF);
        assertThat(diff.differences()).isEqualTo(2);
    }

    @Test
    void transfersAreCodedByTransferAndTaker() {
        Map<String, Object> row = new java.util.LinkedHashMap<>();
        row.put("transferId", "tr1");
        row.put("userId", "u1");
        row.put("checkCode", ReconChecks.TRANSFER_LEDGER);
        row.put("expected", BigDecimal.TEN);
        row.put("actual", BigDecimal.ZERO);
        assertThat(ReconChecks.coded(List.of(row))).singleElement().satisfies(item -> {
            assertThat(item.reference()).isEqualTo("tr1");
            assertThat(item.partyId()).isEqualTo("u1");
        });
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

    @Test
    void theTakersAreKeptFirstWhenItemsAreCapped() {
        List<ReconChecks.Item> found = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            found.add(new ReconChecks.Item(ReconChecks.SPONSOR_WALLET, "s" + i, null, BigDecimal.ONE, BigDecimal.TEN));
        }
        found.add(new ReconChecks.Item(ReconChecks.TAKER_WALLET_LAG, "t1", null, BigDecimal.ONE, BigDecimal.TEN));
        found.add(new ReconChecks.Item(ReconChecks.TAKER_WALLET, "t2", null, BigDecimal.ONE, BigDecimal.TEN));
        ReconChecks.Result result = ReconChecks.result(found, 3);
        // The next run reads whom this one found: the notes must survive the cap.
        assertThat(result.items()).extracting(ReconChecks.Item::check).containsExactly(
            ReconChecks.TAKER_WALLET_LAG, ReconChecks.TAKER_WALLET, ReconChecks.SPONSOR_WALLET);
        assertThat(result.total()).isEqualTo(5);
        assertThat(result.differences()).isEqualTo(4);
    }
}
