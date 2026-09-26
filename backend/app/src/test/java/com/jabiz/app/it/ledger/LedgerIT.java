package com.jabiz.app.it.ledger;

import com.jabiz.app.it.fixture.SqlStatementLog;
import com.jabiz.entity.Violation;
import com.jabiz.i18n.PlatformErrorCodes;
import com.jabiz.runtime.BusinessRuleViolationException;
import com.jabiz.runtime.EntityNotFoundException;
import com.jabiz.runtime.RevertService;
import com.jabiz.runtime.ledger.LedgerEntities;
import com.jabiz.runtime.ledger.LedgerProcesses;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import static com.jabiz.ledger.Direction.CREDIT;
import static com.jabiz.ledger.Direction.DEBIT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The ledger (docs/design/11-ledger-events-jobs.md section 1, ROADMAP phase 9 item 1): balanced postings, balances as
 * of a time, reversals instead of changes, processes as the only writers, the database's balance check and
 * append-only tables.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = "it.sql-log.enabled=true")
class LedgerIT extends LedgerItSupport {

    private static final Pattern MUTATION = Pattern.compile("^\\s*(UPDATE|DELETE|TRUNCATE)\\b",
        Pattern.CASE_INSENSITIVE);

    @Autowired
    RevertService reverts;

    @Test
    void postingsMoveBalancesAsOfTheirBookingTime() {
        String p = prefix();
        openAccounts(p, "1100", "4100", "2100");
        Instant before = clock.instant();
        LedgerProcesses.PostOutput sale = post(p, "1100", DEBIT, 1100, "4100", CREDIT, 1000, "2100", CREDIT, 100);
        assertThat(sale.total()).isEqualByComparingTo("1100");
        assertThat(sale.entryCount()).isEqualTo(3);
        assertThat(sale.bookingTime()).isEqualTo(clock.instant());

        clock.advance(Duration.ofDays(1));
        post(p, "4100", DEBIT, 200, "1100", CREDIT, 200);

        assertThat(balances(p, clock.instant())).containsExactly(
            Map.entry("1100", amount("900")), Map.entry("2100", amount("-100")),
            Map.entry("4100", amount("-800")));
        // As of the first day only the sale counts; before it nothing does.
        assertThat(balances(p, clock.instant().minus(Duration.ofHours(1))))
            .containsEntry("1100", amount("1100")).containsEntry("4100", amount("-1000"));
        assertThat(balances(p, before.minusSeconds(1)).values()).allMatch(balance -> balance.signum() == 0);
        assertThat(balances(p, clock.instant()).values().stream().reduce(BigDecimal.ZERO, BigDecimal::add))
            .isEqualByComparingTo("0");
    }

    @Test
    void anUnbalancedPostingIsRefusedWithAllItsProblems() {
        String p = prefix();
        openAccounts(p, "1100", "4100");
        closeAccount(p + "4100");
        int before = query("SELECT * FROM ledger_transaction_version").size();

        assertThatThrownBy(() -> post(p, "1100", DEBIT, 1000, "4100", CREDIT, 999, "9999", CREDIT, "0.5"))
            .isInstanceOfSatisfying(BusinessRuleViolationException.class, e ->
                assertThat(e.violations()).extracting(Violation::ruleCode).containsExactlyInAnyOrder(
                    PlatformErrorCodes.LEDGER_AMOUNT_SCALE, PlatformErrorCodes.LEDGER_UNBALANCED,
                    PlatformErrorCodes.LEDGER_ACCOUNT_DISABLED, PlatformErrorCodes.LEDGER_ACCOUNT_NOT_FOUND));
        assertThat(query("SELECT * FROM ledger_transaction_version")).hasSize(before);
        assertThat(unbalancedTransactions(p)).isEmpty();
    }

    @Test
    void aReversalCancelsATransactionOnce() {
        String p = prefix();
        openAccounts(p, "1100", "4100");
        LedgerProcesses.PostOutput sale = post(p, "1100", DEBIT, 5000, "4100", CREDIT, 5000);
        clock.advance(Duration.ofHours(1));

        LedgerProcesses.ReverseOutput reversal = reverse(sale.transactionId());

        assertThat(reversal.reversedTransactionId()).isEqualTo(sale.transactionId());
        assertThat(reversal.total()).isEqualByComparingTo("5000");
        assertThat(balances(p, clock.instant()).values()).allMatch(balance -> balance.signum() == 0);
        assertThat(balances(p, clock.instant().minusSeconds(1))).containsEntry("1100", amount("5000"));
        // The original transaction is untouched: one version, as posted.
        assertThat(query("SELECT version_no FROM ledger_transaction_version WHERE transaction_id = ?::uuid",
            sale.transactionId())).hasSize(1);
        assertThat(query("SELECT reverses_transaction_id FROM ledger_transaction_version "
            + "WHERE transaction_id = ?::uuid", reversal.transactionId()).getFirst().get("reverses_transaction_id"))
            .isEqualTo(UUID.fromString(sale.transactionId()));

        assertThatThrownBy(() -> reverse(sale.transactionId()))
            .isInstanceOfSatisfying(BusinessRuleViolationException.class, e -> assertThat(e.violations())
                .extracting(Violation::ruleCode).containsExactly(PlatformErrorCodes.LEDGER_ALREADY_REVERSED));
        assertThatThrownBy(() -> reverse(reversal.transactionId()))
            .isInstanceOfSatisfying(BusinessRuleViolationException.class, e -> assertThat(e.violations())
                .extracting(Violation::ruleCode).containsExactly(
                    PlatformErrorCodes.LEDGER_REVERSAL_NOT_REVERSIBLE));
        assertThatThrownBy(() -> reverse(UUID.randomUUID().toString())).isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void onlyTheLedgerProcessesWriteTransactions() {
        String p = prefix();
        openAccounts(p, "1100", "4100");
        LedgerProcesses.PostOutput sale = post(p, "1100", DEBIT, 10, "4100", CREDIT, 10);
        String token = bearer("ledger.read", "ledger.post", "entity.write");

        client().post().uri("/api/datasets/{id}/commit", LedgerEntities.TRANSACTION_DATASET)
            .header(HttpHeaders.AUTHORIZATION, token).contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("changes", List.of(Map.of("action", "INSERT", "attributes", Map.of(
                "bookingTime", clock.instant().toString(), "description", "forged")))))
            .exchange().expectStatus().isEqualTo(422)
            .expectBody(MAP).value(body -> assertThat(body.toString()).contains("PROCESS_ONLY_DATASET"));
        client().post().uri("/api/entities/{type}", LedgerEntities.ENTRY)
            .header(HttpHeaders.AUTHORIZATION, token).contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("transactionId", sale.transactionId(), "accountId", UUID.randomUUID().toString(),
                "lineNo", 3, "direction", "DEBIT", "amount", 1))
            .exchange().expectStatus().isEqualTo(422)
            .expectBody(MAP).value(body -> assertThat(body.toString()).contains("PROCESS_ONLY_DATASET"));
        // Reading is fine with the read permission.
        client().post().uri("/api/datasets/{id}/query", LedgerEntities.ENTRY_DATASET)
            .header(HttpHeaders.AUTHORIZATION, bearer("ledger.read")).contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of()).exchange().expectStatus().isOk();
        // A posting is corrected by a reversal, never by reverting its operation.
        long operation = (Long) query("SELECT process_seq_id FROM ledger_transaction_version "
            + "WHERE transaction_id = ?::uuid", sale.transactionId()).getFirst().get("process_seq_id");
        assertThatThrownBy(() -> asRequest(ACCOUNTANT, reverts.revert(operation, "undo")).block())
            .isInstanceOfSatisfying(BusinessRuleViolationException.class, e -> assertThat(e.violations())
                .extracting(Violation::ruleCode).containsExactly(PlatformErrorCodes.REVERT_NOT_ALLOWED));
    }

    @Test
    void theProcessApiNeedsTheLedgerPermissions() {
        String p = prefix();
        openAccounts(p, "1100", "4100");
        Map<String, Object> body = Map.of("description", "api", "entries", List.of(
            Map.of("accountCode", p + "1100", "direction", "DEBIT", "amount", 7),
            Map.of("accountCode", p + "4100", "direction", "CREDIT", "amount", 7)));
        client().post().uri("/api/processes/LEDGER_POST/latest").contentType(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.AUTHORIZATION, bearer("ledger.read")).bodyValue(body)
            .exchange().expectStatus().isForbidden();
        client().post().uri("/api/processes/LEDGER_POST/latest").contentType(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.AUTHORIZATION, bearer("ledger.post")).bodyValue(body)
            .exchange().expectStatus().isOk();
        client().post().uri("/api/queries/{id}", "jabiz.ledger.account_balances")
            .header(HttpHeaders.AUTHORIZATION, bearer("ledger.post")).contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("params", Map.of("asOf", clock.instant().toString())))
            .exchange().expectStatus().isForbidden();
        assertThat(balances(p, clock.instant())).containsEntry("1100", amount("7"));
    }

    @Test
    void theDatabaseRefusesUnbalancedEntriesWrittenAroundTheProcesses() {
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
        String entry = "INSERT INTO entity_registry (entity_id, entity_type, created_seq_id) VALUES (?, 'LedgerEntry', ?)";
        UUID debit = UUID.randomUUID();
        execute(entry, debit, operation);

        // Committed on its own, a single entry does not balance.
        assertThatThrownBy(() -> execute("INSERT INTO ledger_entry_version (entry_id, version_no, "
                + "effect_start_time, created_time, process_seq_id, transaction_id, account_id, line_no, direction, "
                + "amount) VALUES (?, 1, now(), now(), ?, ?, ?, 1, 'DEBIT', 100)", debit, operation, transaction,
            accounts.get(0).get("account_id")))
            .hasMessageContaining("does not balance");
        assertThat(query("SELECT * FROM ledger_entry_version WHERE transaction_id = ?", transaction)).isEmpty();
    }

    @Test
    void ledgerTablesAreOnlyEverInsertedInto() {
        SqlStatementLog.STATEMENTS.clear();
        String p = prefix();
        openAccounts(p, "1100", "4100");
        LedgerProcesses.PostOutput sale = post(p, "1100", DEBIT, 10, "4100", CREDIT, 10);
        reverse(sale.transactionId());

        List<String> statements = List.copyOf(SqlStatementLog.STATEMENTS);
        assertThat(statements).anyMatch(sql -> sql.toLowerCase(Locale.ROOT)
            .startsWith("insert into ledger_entry_version"));
        assertThat(statements).noneMatch(sql -> MUTATION.matcher(sql).find());
        assertThatThrownBy(() -> execute("UPDATE ledger_entry_version SET amount = 1 WHERE transaction_id = ?::uuid",
            sale.transactionId())).hasMessageContaining("append-only table");
        assertThatThrownBy(() -> execute("DELETE FROM ledger_transaction_version WHERE transaction_id = ?::uuid",
            sale.transactionId())).hasMessageContaining("append-only table");
    }

    private void closeAccount(String code) {
        Map<String, Object> account = query("SELECT account_id, version_no FROM ledger_account_version "
            + "WHERE account_code = ? ORDER BY version_no DESC LIMIT 1", code).getFirst();
        String id = String.valueOf(account.get("account_id"));
        asRequest(ACCOUNTANT, entities.commitBatch(datasets.findById(LedgerEntities.ACCOUNT_DATASET).orElseThrow(),
            List.of(new com.jabiz.runtime.EntityChange(com.jabiz.runtime.EntityAction.UPDATE,
                new com.jabiz.runtime.EntityInstance(id, LedgerEntities.ACCOUNT,
                    ((Number) account.get("version_no")).longValue(), null, Map.of("enabled", false)), null))))
            .block();
    }
}
