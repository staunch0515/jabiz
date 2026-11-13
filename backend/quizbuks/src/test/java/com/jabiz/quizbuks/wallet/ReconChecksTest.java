package com.jabiz.quizbuks.wallet;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    void readsCodedRows() {
        List<ReconChecks.Item> items = ReconChecks.coded(List.of(
            Map.of("checkCode", "TOTAL_2110", "expected", 10, "actual", 0),
            Map.of("checkCode", "TOPUP_FEE", "topUpId", "t1", "sponsorId", "s1", "expected", 180, "actual", 0)));
        assertThat(items).extracting(ReconChecks.Item::check).containsExactly(ReconChecks.TOTAL_2110,
            ReconChecks.TOPUP_FEE);
        assertThat(items.get(1).reference()).isEqualTo("t1");
        assertThat(items.get(1).partyId()).isEqualTo("s1");
    }

    /**
     * A code the run does not know fails the run (and qb-reconcile-missing reports it) instead of the check being left
     * out unseen: a typo, a code cut short by its column (as DISTRIBUTION_PRICE_OUT_OF_RANGE was) or a retired one.
     */
    @Test
    void anUnknownCodeFailsTheRun() {
        for (String code : List.of("SOMETHING_ELSE", "DISTRIBUTION_PRICE_OUT_OF_RANG",
            ReconChecks.DISTRIBUTION_PRICE_OUT_OF_RANGE)) {
            assertThatThrownBy(() -> ReconChecks.coded(List.of(
                Map.of("checkCode", "TOTAL_2110", "expected", 10, "actual", 0),
                Map.of("checkCode", code, "expected", 1, "actual", 0))))
                .as(code).isInstanceOf(IllegalStateException.class).hasMessageContaining("'" + code + "'");
        }
        Map<String, Object> noCode = new java.util.HashMap<>(Map.of("expected", 1, "actual", 0));
        assertThatThrownBy(() -> ReconChecks.coded(List.of(noCode))).isInstanceOf(IllegalStateException.class);
    }

    /** A retired code is no longer a check (never written) but stays readable: the sealed summaries name it. */
    @Test
    void aRetiredCodeIsReadableButNotACheck() {
        assertThat(ReconChecks.RETIRED).contains(ReconChecks.DISTRIBUTION_PRICE_OUT_OF_RANGE)
            .doesNotContainAnyElementsOf(ReconChecks.ALL);
        var entries = new TopUpConfig("default").qbReconCheckDictionary().entries();
        assertThat(entries).filteredOn(e -> ReconChecks.RETIRED.contains(e.code())).hasSize(ReconChecks.RETIRED.size())
            .allSatisfy(e -> {
                assertThat(e.enabled()).isFalse();
                assertThat(e.labels()).hasSize(3);
            });
        assertThat(entries).filteredOn(e -> ReconChecks.ALL.contains(e.code())).hasSize(ReconChecks.ALL.size())
            .allSatisfy(e -> assertThat(e.enabled()).isTrue());
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
        // Every check by name, the F-M6 notes (DISTRIBUTION_AT_FLOOR) among them.
        assertThat(result.summary()).startsWith("{\"CASH_ACCOUNT\":0,\"CASH_LOTS\":0,\"CERTIFIED_GRANTS\":0,"
            + "\"DISTRIBUTION_AT_FLOOR\":0,\"DISTRIBUTION_DEPOSITS\":0,\"DISTRIBUTION_FINAL_MISSING\":0,"
            + "\"DISTRIBUTION_PRICE_OFF_RANGE\":0,\"DISTRIBUTION_UNSETTLED\":0,\"ENTRY_FEES_UNSETTLED\":0,"
            + "\"FEES_RECEIVABLE\":0,\"FEE_CREDIT\":0,\"FEE_CREDIT_PROMOTION\":0,\"FEE_PAYMENTS\":0,")
            .contains("\"LEDGER_ZERO\":0").contains("\"TOPUP_FEE\":0");
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

    /**
     * A code longer than the column it is stored in is cut short by the templates' CAST, and {@link
     * ReconChecks#coded} drops a code it does not know: the check would never be reported. Every code fits the
     * column {@code qb_recon_item_version.check_code}, read from its migration.
     */
    @Test
    void everyCodeFitsTheColumnItIsStoredIn() throws IOException, URISyntaxException {
        Matcher column = Pattern.compile("check_code\\s+varchar\\((\\d+)\\)")
            .matcher(Files.readString(resource("db/migration/V7__quizbuks_topups.sql")));
        assertThat(column.find()).as("check_code's width in V7").isTrue();
        int width = Integer.parseInt(column.group(1));
        assertThat(ReconChecks.ALL).allSatisfy(code -> assertThat(code).as(code).hasSizeLessThanOrEqualTo(width));
    }

    /** Every code a reconciliation template writes is a known one, and fits the width it is cast to there. */
    @Test
    void everyCodeTheTemplatesWriteIsKnownAndFitsItsCast() throws IOException, URISyntaxException {
        Pattern cast = Pattern.compile("CAST\\('([A-Z0-9_]+)' AS varchar\\((\\d+)\\)\\)");
        List<String> found = new ArrayList<>();
        try (Stream<Path> templates = Files.list(resource("queries/qb/system"))) {
            for (Path template : templates.filter(p -> p.getFileName().toString().startsWith("recon-")).toList()) {
                Matcher m = cast.matcher(Files.readString(template));
                while (m.find()) {
                    String code = m.group(1);
                    found.add(code);
                    assertThat(code).as(template.getFileName() + ": " + code)
                        .hasSizeLessThanOrEqualTo(Integer.parseInt(m.group(2)));
                    assertThat(ReconChecks.ALL).as(template.getFileName().toString()).contains(code);
                }
            }
        }
        assertThat(found).as("codes found in the templates").isNotEmpty();
    }

    private static Path resource(String name) throws URISyntaxException {
        var url = ReconChecksTest.class.getClassLoader().getResource(name);
        assertThat(url).as(name).isNotNull();
        return Path.of(url.toURI());
    }
}
