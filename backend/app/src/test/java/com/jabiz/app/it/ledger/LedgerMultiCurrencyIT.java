package com.jabiz.app.it.ledger;

import com.jabiz.entity.Violation;
import com.jabiz.i18n.PlatformErrorCodes;
import com.jabiz.ledger.Direction;
import com.jabiz.runtime.BusinessRuleViolationException;
import com.jabiz.runtime.ledger.LedgerProcesses;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Entries in foreign currencies (docs/design/11-ledger-events-jobs.md section 1.8, decision D24 item 5). The ledger of
 * the tests keeps yen without decimals: a EUR invoice converts at its rate, a receipt at another rate posts the
 * exchange difference in yen, and every currency balances on its own.
 */
@SpringBootTest
class LedgerMultiCurrencyIT extends LedgerItSupport {

    static LedgerProcesses.Line eur(String code, Direction side, String amount, String euros, String rate) {
        return new LedgerProcesses.Line(code, side, amount == null ? null : new BigDecimal(amount), null, null,
            "EUR", new BigDecimal(euros), new BigDecimal(rate));
    }

    static LedgerProcesses.Line yen(String code, Direction side, String amount) {
        return new LedgerProcesses.Line(code, side, new BigDecimal(amount));
    }

    LedgerProcesses.PostOutput post(String prefix, LedgerProcesses.Line... lines) {
        List<LedgerProcesses.Line> entries = java.util.Arrays.stream(lines).map(line -> new LedgerProcesses.Line(
            prefix + line.accountCode(), line.direction(), line.amount(), line.memo(), line.dimensions(),
            line.currency(), line.transactionAmount(), line.exchangeRate())).toList();
        return run(LedgerProcesses.POST, new LedgerProcesses.PostInput(null, "it fx", null, entries));
    }

    static List<String> codes(Throwable error) {
        assertThat(error).isInstanceOf(BusinessRuleViolationException.class);
        return ((BusinessRuleViolationException) error).violations().stream().map(Violation::ruleCode).toList();
    }

    @Test
    void anInvoiceConvertsAndAReceiptAtAnotherRatePostsTheDifference() {
        String p = prefix();
        openAccounts(p, "1010", "1200", "4000", "4900");
        // 100.00 EUR at 160.5: the yen amount is left out and converted.
        LedgerProcesses.PostOutput invoice = post(p, eur("1200", Direction.DEBIT, null, "100.00", "160.5"),
            eur("4000", Direction.CREDIT, null, "100.00", "160.5"));
        assertThat(invoice.total()).isEqualByComparingTo("16050");
        assertThat(query("SELECT currency, transaction_amount, exchange_rate, amount FROM ledger_entry_version"
            + " WHERE transaction_id = ?::uuid ORDER BY line_no", invoice.transactionId()).getFirst())
            .satisfies(row -> {
                assertThat(row.get("currency")).isEqualTo("EUR");
                assertThat((BigDecimal) row.get("transaction_amount")).isEqualByComparingTo("100");
                assertThat((BigDecimal) row.get("exchange_rate")).isEqualByComparingTo("160.5");
                assertThat((BigDecimal) row.get("amount")).isEqualByComparingTo("16050");
            });
        // Received at 165: the receivable is cleared at the invoice rate, the gain is booked in yen.
        post(p, eur("1010", Direction.DEBIT, "16500", "100.00", "165"),
            eur("1200", Direction.CREDIT, "16050", "100.00", "160.5"), yen("4900", Direction.CREDIT, "450"));

        String asOf = clock.instant().plusSeconds(60).toString();
        Map<String, Map<String, Object>> byCurrency = new java.util.LinkedHashMap<>();
        rows("jabiz.ledger.currency_balances", Map.of("asOf", asOf), p)
            .forEach(row -> byCurrency.put(row.get("accountcode") + "/" + row.get("currency"), row));
        assertThat(amount(byCurrency.get(p + "1010/EUR").get("transactionbalance"))).isEqualByComparingTo("100");
        assertThat(amount(byCurrency.get(p + "1010/EUR").get("balance"))).isEqualByComparingTo("16500");
        assertThat(amount(byCurrency.get(p + "1200/EUR").get("transactionbalance"))).isZero();
        assertThat(amount(byCurrency.get(p + "1200/EUR").get("balance"))).isZero();
        assertThat(amount(byCurrency.get(p + "4900/").get("balance"))).isEqualByComparingTo("-450");
        assertThat(balances(p, clock.instant().plusSeconds(60)).values().stream()
            .reduce(BigDecimal.ZERO, BigDecimal::add)).isZero();

        List<Map<String, Object>> activity = rows("jabiz.ledger.account_activity", Map.of("account", p + "1010",
            "from", clock.instant().minusSeconds(60).toString(), "asOf", asOf), null);
        assertThat(activity.get(1)).containsEntry("currency", "EUR");
        assertThat(amount(activity.get(1).get("transactionamount"))).isEqualByComparingTo("100");
        assertThat(amount(activity.get(1).get("exchangerate"))).isEqualByComparingTo("165");
    }

    @Test
    void theConvertedAmountMustBeExactAndEveryCurrencyBalance() {
        String p = prefix();
        openAccounts(p, "1200", "2100", "4000");
        assertThatThrownBy(() -> post(p, eur("1200", Direction.DEBIT, "16000", "100", "160.5"),
            eur("4000", Direction.CREDIT, "16000", "100", "160.5")))
            .satisfies(e -> assertThat(codes(e)).containsExactly(PlatformErrorCodes.LEDGER_FX_AMOUNT_MISMATCH,
                PlatformErrorCodes.LEDGER_FX_AMOUNT_MISMATCH));
        // Balanced in yen, not in euros.
        assertThatThrownBy(() -> post(p, eur("1200", Direction.DEBIT, null, "100", "160"),
            eur("4000", Direction.CREDIT, null, "80", "200")))
            .satisfies(e -> assertThat(codes(e)).containsExactly(PlatformErrorCodes.LEDGER_UNBALANCED_IN_CURRENCY));
        assertThatThrownBy(() -> post(p,
            new LedgerProcesses.Line(p + "1200", Direction.DEBIT, null, null, null, "EUR", BigDecimal.TEN, null),
            yen("4000", Direction.CREDIT, "10")))
            .satisfies(e -> assertThat(codes(e)).containsExactly(PlatformErrorCodes.REQUIRED));
        assertThatThrownBy(() -> post(p,
            new LedgerProcesses.Line("1200", Direction.DEBIT, BigDecimal.TEN, null, null, "XYZ", BigDecimal.TEN,
                BigDecimal.ONE), yen("4000", Direction.CREDIT, "10")))
            .satisfies(e -> assertThat(codes(e)).containsExactly(PlatformErrorCodes.LEDGER_CURRENCY_INVALID));
        // The ledger's own currency is simply the ledger amount.
        LedgerProcesses.PostOutput yen = post(p,
            new LedgerProcesses.Line("1200", Direction.DEBIT, null, null, null, "JPY", new BigDecimal("500"), null),
            yen("4000", Direction.CREDIT, "500"));
        assertThat(query("SELECT count(*) AS n FROM ledger_entry_version WHERE transaction_id = ?::uuid"
            + " AND currency IS NULL", yen.transactionId()).getFirst().get("n")).isEqualTo(2L);
    }

    @Test
    void aReversalKeepsTheCurrenciesAndCancelsThem() {
        String p = prefix();
        openAccounts(p, "1200", "4000");
        LedgerProcesses.PostOutput invoice = post(p, eur("1200", Direction.DEBIT, null, "10.05", "160.33"),
            eur("4000", Direction.CREDIT, null, "10.05", "160.33"));
        LedgerProcesses.ReverseOutput reversal = reverse(invoice.transactionId());
        assertThat(query("SELECT direction, currency, transaction_amount, exchange_rate FROM ledger_entry_version"
            + " WHERE transaction_id = ?::uuid ORDER BY line_no", reversal.transactionId()))
            .extracting(row -> row.get("direction") + " " + row.get("currency") + " "
                + ((BigDecimal) row.get("transaction_amount")).stripTrailingZeros().toPlainString() + " @ "
                + ((BigDecimal) row.get("exchange_rate")).stripTrailingZeros().toPlainString())
            .containsExactly("CREDIT EUR 10.05 @ 160.33", "DEBIT EUR 10.05 @ 160.33");
        assertThat(rows("jabiz.ledger.currency_balances", Map.of("asOf", clock.instant().plusSeconds(60).toString()),
            p)).allSatisfy(row -> {
                assertThat(amount(row.get("transactionbalance"))).isZero();
                assertThat(amount(row.get("balance"))).isZero();
            });
    }

    @Test
    void theDatabaseRefusesEntriesUnbalancedInACurrency() {
        String p = prefix();
        openAccounts(p, "1100", "4100");
        List<Map<String, Object>> accounts = query("SELECT account_id FROM ledger_account_version "
            + "WHERE account_code IN (?, ?) ORDER BY account_code", p + "1100", p + "4100");
        long operation = (Long) query("SELECT nextval('op_process_seq') AS seq").getFirst().get("seq");
        execute("INSERT INTO op_process (process_seq_id, process_name, process_version, actor_id, op_time) "
            + "VALUES (?, 'it.sql', 1, 'it', now())", operation);
        UUID transaction = UUID.randomUUID();
        execute("INSERT INTO entity_registry (entity_id, entity_type, created_seq_id) VALUES (?, 'LedgerTransaction', ?)",
            transaction, operation);
        execute("INSERT INTO ledger_transaction_version (transaction_id, version_no, effect_start_time, "
            + "created_time, process_seq_id, booking_time, description) VALUES (?, 1, now(), now(), ?, now(), 'x')",
            transaction, operation);
        UUID debit = UUID.randomUUID();
        UUID credit = UUID.randomUUID();
        String entry = "INSERT INTO entity_registry (entity_id, entity_type, created_seq_id) VALUES (?, 'LedgerEntry', ?)";
        execute(entry, debit, operation);
        execute(entry, credit, operation);

        // Balanced in yen, but 100 EUR against 90 EUR, in one statement (one commit).
        assertThatThrownBy(() -> execute("INSERT INTO ledger_entry_version (entry_id, version_no, effect_start_time,"
                + " created_time, process_seq_id, transaction_id, account_id, line_no, direction, amount, currency,"
                + " transaction_amount, exchange_rate) VALUES"
                + " (?, 1, now(), now(), ?, ?, ?, 1, 'DEBIT', 16000, 'EUR', 100, 160),"
                + " (?, 1, now(), now(), ?, ?, ?, 2, 'CREDIT', 16000, 'EUR', 90, 177.7777777778)",
            debit, operation, transaction, accounts.get(0).get("account_id"),
            credit, operation, transaction, accounts.get(1).get("account_id")))
            .hasMessageContaining("does not balance in EUR");
        assertThat(query("SELECT * FROM ledger_entry_version WHERE transaction_id = ?", transaction)).isEmpty();
    }
}
