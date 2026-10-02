package com.jabiz.finance.calc;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The automatic close checks worked out from the reports, and the hashes of a close artifact (FIN-PC-004, 005). */
class CloseChecksTest {

    private static final LocalDate END = LocalDate.of(2026, 1, 31);

    @Test
    void aCheckPassesWhenNothingIsListedForIt() {
        List<CloseChecks.Finding> findings = List.of(
            new CloseChecks.Finding(CloseChecks.ENTRIES_POSTED, "JE-0007", "Journal entry submitted, not posted"),
            new CloseChecks.Finding(CloseChecks.CLEARING_ZERO, "1250", "Unapplied Cash is not at zero"));

        CloseChecks.Result entries = CloseChecks.fromExceptions(CloseChecks.ENTRIES_POSTED, findings, "2026-01");
        assertThat(entries.passed()).isFalse();
        assertThat(entries.result()).isEqualTo("1 exception: JE-0007: Journal entry submitted, not posted");
        assertThat(entries.evidence())
            .isEqualTo("finance.close.exceptions?periodKey=2026-01&checkCode=ENTRIES_POSTED");
        CloseChecks.Result bank = CloseChecks.fromExceptions(CloseChecks.BANK_RECONCILED, findings, "2026-01");
        assertThat(bank.passed()).isTrue();
        assertThat(bank.result()).isEqualTo("No exceptions");
    }

    /** A long list ends with how many more there were, within the length a task keeps. */
    @Property
    void aResultFitsWhateverItLists(@ForAll @IntRange(min = 1, max = 400) int count) {
        List<CloseChecks.Finding> findings = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            findings.add(new CloseChecks.Finding(CloseChecks.ENTRIES_POSTED, "JE-" + i, "Draft journal entry " + i));
        }
        String result = CloseChecks.fromExceptions(CloseChecks.ENTRIES_POSTED, findings, "2026-01").result();
        assertThat(result.length()).isLessThanOrEqualTo(CloseChecks.MAX_RESULT);
        assertThat(result).startsWith(count + (count == 1 ? " exception: " : " exceptions: "));
        if (!result.contains("JE-" + (count - 1) + ":")) {
            assertThat(result).endsWith(" more");
        }
    }

    @Test
    void subledgersAreComparedWithTheirControlAccountsAsTheBooksCarryThem() {
        // FIN-EXP-03: receivables 158,735.00, payables 46,300.00 (a credit), assets 202,000.00 less 62,000.00.
        Map<String, String> classes = Map.of("1200", "AR", "2000", "AP", "1500", "FA_COST", "1510", "FA_COST",
            "1520", "FA_COST", "1590", "FA_ACCUM");
        Map<String, BigDecimal> balances = Map.of("1200", money("158735.00"), "2000", money("-46300.00"),
            "1500", money("120000.00"), "1510", money("70000.00"), "1520", money("12000.00"),
            "1590", money("-62000.00"), "1010", money("211555.00"));

        List<CloseChecks.Subledger> agree = CloseChecks.compare(money("158735.00"), money("46300.00"),
            money("202000.00"), money("62000.00"), classes, balances);
        assertThat(agree).extracting(s -> s.code() + " " + s.subledger() + " " + s.ledger())
            .containsExactly("AR 158735.00 158735.00", "AP 46300.00 46300.00", "FA_COST 202000.00 202000.00",
                "FA_ACCUM 62000.00 62000.00");
        CloseChecks.Result passed = CloseChecks.subledgers(agree, END);
        assertThat(passed.passed()).isTrue();
        assertThat(passed.evidence()).contains("finance.ar.aging?agingDate=2026-01-31",
            "finance.fa.register?asOf=2026-01-31");

        List<CloseChecks.Subledger> off = CloseChecks.compare(money("158385.00"), money("46300.00"),
            money("202000.00"), money("62000.00"), classes, balances);
        CloseChecks.Result failed = CloseChecks.subledgers(off, END);
        assertThat(failed.passed()).isFalse();
        assertThat(failed.result()).isEqualTo("Subledgers differ from their control accounts: Receivables aging "
            + "158385.00 against the control accounts 158735.00, a difference of -350.00");
    }

    @Test
    void theRevaluationIsNeededOnlyWithForeignItemsOpen() {
        assertThat(CloseChecks.revaluation(0, null, "2026-01", END).passed()).isTrue();
        assertThat(CloseChecks.revaluation(1, "FXR-2601", "2026-01", END).result())
            .isEqualTo("Run FXR-2601 for 1 items");
        CloseChecks.Result missing = CloseChecks.revaluation(2, null, "2026-01", END);
        assertThat(missing.passed()).isFalse();
        assertThat(missing.result()).contains("no revaluation run for 2026-01");
        assertThat(missing.evidence()).isEqualTo("finance.fx.revaluation_items?revaluationDate=2026-01-31");
    }

    @Test
    void theHashesFollowEveryFigure() {
        List<CloseChecks.Account> accounts = List.of(
            new CloseChecks.Account("1010", "Cash - Operating", money("211555.00"), money("0.00")),
            new CloseChecks.Account("2000", "Accounts Payable", money("0.00"), money("46300.00")));
        String hash = CloseChecks.trialBalanceHash(accounts);
        assertThat(hash).hasSize(64).isEqualTo(CloseChecks.trialBalanceHash(List.copyOf(accounts)));
        assertThat(CloseChecks.trialBalanceHash(List.of(accounts.get(0), new CloseChecks.Account("2000",
            "Accounts Payable", money("0.00"), money("46300.01"))))).isNotEqualTo(hash);
        // A name is no figure: renamed later, the account hashes the same.
        assertThat(CloseChecks.trialBalanceHash(List.of(new CloseChecks.Account("1010", "Operating account",
            money("211555.00"), money("0.00")), accounts.get(1)))).isEqualTo(hash);
        // The same figure at another scale is the same figure.
        assertThat(CloseChecks.trialBalanceHash(List.of(new CloseChecks.Account("1010", "Cash - Operating",
            new BigDecimal("211555"), BigDecimal.ZERO), accounts.get(1)))).isEqualTo(hash);

        List<List<Object>> lines = List.of(Arrays.asList("TRIAL_BALANCE", 1, "1010", "Cash", money("1.00"), null));
        String content = CloseChecks.contentHash(List.of("2026-01", 1), lines);
        assertThat(content).isEqualTo(CloseChecks.contentHash(List.of("2026-01", 1), lines))
            .isNotEqualTo(CloseChecks.contentHash(List.of("2026-01", 2), lines));
    }

    private static BigDecimal money(String value) {
        return new BigDecimal(value);
    }
}
