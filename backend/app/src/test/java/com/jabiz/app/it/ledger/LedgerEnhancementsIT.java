package com.jabiz.app.it.ledger;

import com.jabiz.app.commerce.CommerceEntities;
import com.jabiz.entity.Violation;
import com.jabiz.i18n.PlatformErrorCodes;
import com.jabiz.ledger.Direction;
import com.jabiz.runtime.BusinessRuleViolationException;
import com.jabiz.runtime.EntityAction;
import com.jabiz.runtime.EntityChange;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.ledger.LedgerEntities;
import com.jabiz.runtime.ledger.LedgerProcesses;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Ledger enhancements of phase 14c-1 (docs/design/11-ledger-events-jobs.md sections 1.4 to 1.7, decision D24): the
 * account hierarchy, analysis dimensions, memos, source documents, and balances over a range, as known at a time,
 * by dimension and per account line by line.
 */
@SpringBootTest
class LedgerEnhancementsIT extends LedgerItSupport {

    /** Opens an account {@code prefix + code}; returns its id. */
    String open(String prefix, String code, String parentId, Boolean summary) {
        String id = UUID.randomUUID().toString();
        Map<String, Object> attributes = new HashMap<>(Map.of("accountId", id, "accountCode", prefix + code,
            "accountName", "Account " + code, "accountType", code.startsWith("4") ? "REVENUE" : "ASSET",
            "enabled", true));
        attributes.put("parentId", parentId);
        attributes.put("summary", summary);
        asRequest(ACCOUNTANT, entities.commitBatch(datasets.findById(LedgerEntities.ACCOUNT_DATASET).orElseThrow(),
            List.of(new EntityChange(EntityAction.INSERT, new EntityInstance(id, LedgerEntities.ACCOUNT, 0, null,
                attributes), null)))).block();
        return id;
    }

    void change(String accountId, Map<String, Object> changes) {
        Map<String, Object> account = query("SELECT version_no FROM ledger_account_version WHERE account_id = ?::uuid"
            + " ORDER BY version_no DESC LIMIT 1", accountId).getFirst();
        asRequest(ACCOUNTANT, entities.commitBatch(datasets.findById(LedgerEntities.ACCOUNT_DATASET).orElseThrow(),
            List.of(new EntityChange(EntityAction.UPDATE, new EntityInstance(accountId, LedgerEntities.ACCOUNT,
                ((Number) account.get("version_no")).longValue(), null, changes), null)))).block();
    }

    static List<String> codes(Throwable error) {
        assertThat(error).isInstanceOf(BusinessRuleViolationException.class);
        return ((BusinessRuleViolationException) error).violations().stream().map(Violation::ruleCode).toList();
    }

    LedgerProcesses.PostOutput post(String prefix, String sourceEntity, String sourceId,
        LedgerProcesses.Line... lines) {
        List<LedgerProcesses.Line> entries = new ArrayList<>();
        for (LedgerProcesses.Line line : lines) {
            entries.add(new LedgerProcesses.Line(prefix + line.accountCode(), line.direction(), line.amount(),
                line.memo(), line.dimensions()));
        }
        return run(LedgerProcesses.POST, new LedgerProcesses.PostInput(null, "it posting", null, entries,
            sourceEntity, sourceId));
    }

    static LedgerProcesses.Line line(String code, Direction direction, long amount) {
        return new LedgerProcesses.Line(code, direction, BigDecimal.valueOf(amount));
    }

    static LedgerProcesses.Line line(String code, Direction direction, long amount, String memo,
        Map<String, String> dimensions) {
        return new LedgerProcesses.Line(code, direction, BigDecimal.valueOf(amount), memo, dimensions);
    }

    String warehouse() {
        String code = "W" + UUID.randomUUID().toString().substring(0, 6).toUpperCase(Locale.ROOT).replace("-", "");
        String id = UUID.randomUUID().toString();
        asRequest(ACCOUNTANT, entities.commitBatch(datasets.findById(CommerceEntities.WAREHOUSE_DATASET).orElseThrow(),
            List.of(new EntityChange(EntityAction.INSERT, new EntityInstance(id, CommerceEntities.WAREHOUSE, 0, null,
                Map.of("warehouseId", id, "warehouseCode", code, "warehouseName", "IT " + code, "active", true)),
                null)))).block();
        return code + ":" + id;
    }

    @Test
    void summaryAccountsTakeNoPostingsAndShowTheTotalsOfTheirSubAccounts() {
        String p = prefix();
        String group = open(p, "1000", null, true);
        String cash = open(p, "1100", group, null);
        String bank = open(p, "1200", group, false);
        open(p, "4000", null, null);

        assertThatThrownBy(() -> post(p, null, null, line("1000", Direction.DEBIT, 5), line("4000", Direction.CREDIT,
            5))).satisfies(e -> assertThat(codes(e)).containsExactly(PlatformErrorCodes.LEDGER_ACCOUNT_NOT_POSTABLE));
        post(p, null, null, line("1100", Direction.DEBIT, 30), line("1200", Direction.DEBIT, 70),
            line("4000", Direction.CREDIT, 100));

        Map<String, Map<String, Object>> balances = byAccount(rows("jabiz.ledger.account_balances",
            Map.of("asOf", clock.instant().plusSeconds(60).toString()), p));
        assertThat(amount(balances.get(p + "1000").get("balance"))).isEqualByComparingTo("100");
        assertThat(balances.get(p + "1000")).containsEntry("summary", true).containsEntry("level", 0);
        assertThat(balances.get(p + "1100")).containsEntry("parentcode", p + "1000").containsEntry("level", 1)
            .containsEntry("summary", false);
        assertThat(amount(balances.get(p + "4000").get("balance"))).isEqualByComparingTo("-100");
        // Over the postable accounts the ledger balances to zero; summaries repeat their sub-accounts.
        assertThat(balances.values().stream().filter(row -> !Boolean.TRUE.equals(row.get("summary")))
            .map(row -> amount(row.get("balance"))).reduce(BigDecimal.ZERO, BigDecimal::add)).isZero();

        String nested = open(p, "1900", group, true);
        assertThatThrownBy(() -> open(p, "1300", cash, null))
            .satisfies(e -> assertThat(codes(e)).containsExactly(PlatformErrorCodes.LEDGER_PARENT_NOT_SUMMARY));
        assertThatThrownBy(() -> change(group, Map.of("parentId", nested)))
            .satisfies(e -> assertThat(codes(e)).containsExactly(PlatformErrorCodes.LEDGER_ACCOUNT_CYCLE));
        assertThatThrownBy(() -> change(bank, Map.of("summary", true)))
            .satisfies(e -> assertThat(codes(e)).containsExactly(PlatformErrorCodes.LEDGER_SUMMARY_HAS_ENTRIES));
        assertThatThrownBy(() -> change(group, Map.of("summary", false)))
            .satisfies(e -> assertThat(codes(e)).containsExactly(PlatformErrorCodes.LEDGER_ACCOUNT_HAS_CHILDREN));
        // An unused leaf may become a summary account, and a summary without sub-accounts a postable one.
        String spare = open(p, "1500", group, null);
        change(spare, Map.of("summary", true));
        change(nested, Map.of("summary", false));
    }

    @Test
    void dimensionValuesMustBeInTheirListsAndAreReportedByDimension() {
        String p = prefix();
        open(p, "1100", null, null);
        open(p, "4000", null, null);
        String warehouse = warehouse().split(":")[0];

        assertThatThrownBy(() -> post(p, null, null,
            line("1100", Direction.DEBIT, 10, null, Map.of("warehouse", "NOPE", "salesChannel", "FAX")),
            line("4000", Direction.CREDIT, 10, null, Map.of("colour", "red"))))
            .satisfies(e -> assertThat(codes(e)).containsExactlyInAnyOrder(
                PlatformErrorCodes.LEDGER_DIMENSION_INVALID, PlatformErrorCodes.LEDGER_DIMENSION_INVALID,
                PlatformErrorCodes.LEDGER_DIMENSION_UNKNOWN));

        LedgerProcesses.PostOutput sale = post(p, null, null,
            line("1100", Direction.DEBIT, 70, "card", Map.of("salesChannel", "WEB")),
            line("1100", Direction.DEBIT, 30, null, Map.of()),
            line("4000", Direction.CREDIT, 100, "sale", Map.of("warehouse", warehouse, "salesChannel", "WEB")));
        assertThat(query("SELECT memo, dimension_1, dimension_2 FROM ledger_entry_version WHERE transaction_id ="
            + " ?::uuid ORDER BY line_no", sale.transactionId())).extracting(row -> row.get("memo") + "|"
            + row.get("dimension_1") + "|" + row.get("dimension_2"))
            .containsExactly("card|null|WEB", "null|null|null", "sale|" + warehouse + "|WEB");

        Instant later = clock.instant().plusSeconds(60);
        List<Map<String, Object>> byChannel = rows("jabiz.ledger.dimension_balances",
            Map.of("dimension", 2, "asOf", later.toString()), p);
        assertThat(byChannel).extracting(row -> row.get("accountcode") + "/" + row.get("dimensionvalue") + "="
            + amount(row.get("balance")).toPlainString())
            .containsExactlyInAnyOrder(p + "1100/WEB=70", p + "1100/=30", p + "4000/WEB=-100");

        // The reversal carries memo and dimensions, so every dimension's balance returns to zero.
        reverse(sale.transactionId());
        assertThat(rows("jabiz.ledger.dimension_balances", Map.of("dimension", 1, "asOf", later.toString()), p))
            .allSatisfy(row -> assertThat(amount(row.get("balance"))).isZero());
        assertThat(query("SELECT count(*) AS n FROM ledger_entry_version e JOIN ledger_transaction_version t"
            + " ON t.transaction_id = e.transaction_id WHERE t.reverses_transaction_id = ?::uuid AND e.memo = 'sale'"
            + " AND e.dimension_1 = ?", sale.transactionId(), warehouse).getFirst().get("n")).isEqualTo(1L);
    }

    @Test
    void aTransactionNamesItsSourceDocumentWhichMustExist() {
        String p = prefix();
        open(p, "1100", null, null);
        open(p, "4000", null, null);
        String warehouseId = warehouse().split(":")[1];

        LedgerProcesses.PostOutput posted = post(p, CommerceEntities.WAREHOUSE, warehouseId,
            line("1100", Direction.DEBIT, 5), line("4000", Direction.CREDIT, 5));
        assertThat(query("SELECT source_entity, source_id FROM ledger_transaction_version WHERE transaction_id ="
            + " ?::uuid", posted.transactionId()).getFirst()).containsEntry("source_entity", "Warehouse")
            .containsEntry("source_id", warehouseId);
        LedgerProcesses.ReverseOutput reversal = reverse(posted.transactionId());
        assertThat(query("SELECT source_id FROM ledger_transaction_version WHERE transaction_id = ?::uuid",
            reversal.transactionId()).getFirst()).containsEntry("source_id", warehouseId);

        for (String[] source : List.of(new String[] {CommerceEntities.WAREHOUSE, UUID.randomUUID().toString()},
            new String[] {CommerceEntities.WAREHOUSE, "not-a-uuid"}, new String[] {"NoSuchEntity", "1"},
            new String[] {null, "1"})) {
            assertThatThrownBy(() -> post(p, source[0], source[1], line("1100", Direction.DEBIT, 5),
                line("4000", Direction.CREDIT, 5)))
                .satisfies(e -> assertThat(codes(e)).containsExactly(PlatformErrorCodes.LEDGER_SOURCE_NOT_FOUND));
        }
    }

    @Test
    void balancesCoverARangeAsKnownAtATimeAndAnAccountLineByLine() {
        String p = prefix();
        open(p, "1100", null, null);
        open(p, "4000", null, null);
        Instant start = clock.instant();
        // Booked at the operation time, each recorded when it was run.
        post(p, null, null, line("1100", Direction.DEBIT, 10, "first", Map.of()), line("4000", Direction.CREDIT, 10));
        clock.set(start.plusSeconds(3600));
        LedgerProcesses.PostOutput second = post(p, "Warehouse", warehouse().split(":")[1],
            line("1100", Direction.DEBIT, 5, "second", Map.of()), line("4000", Direction.CREDIT, 5));
        clock.set(start.plusSeconds(7200));
        post(p, null, null, line("4000", Direction.DEBIT, 3), line("1100", Direction.CREDIT, 3, "third", Map.of()));
        Instant end = start.plusSeconds(8000);

        assertThat(amount(byAccount(rows("jabiz.ledger.account_balances", Map.of("asOf", end.toString()), p))
            .get(p + "1100").get("balance"))).isEqualByComparingTo("12");
        assertThat(amount(byAccount(rows("jabiz.ledger.account_balances", Map.of("asOf", end.toString(),
            "from", start.plusSeconds(1800).toString()), p)).get(p + "1100").get("balance"))).isEqualByComparingTo("2");
        assertThat(amount(byAccount(rows("jabiz.ledger.account_balances", Map.of("asOf", end.toString(),
            "knownAt", start.plusSeconds(3700).toString()), p)).get(p + "1100").get("balance")))
            .isEqualByComparingTo("15");

        List<Map<String, Object>> activity = rows("jabiz.ledger.account_activity", Map.of("account", p + "1100",
            "from", start.plusSeconds(1800).toString(), "asOf", end.toString()), null);
        assertThat(activity).extracting(row -> row.get("rowkind") + ":" + amount(row.get("runningbalance"))
            .toPlainString()).containsExactly("OPENING:10", "ENTRY:15", "ENTRY:12", "CLOSING:12");
        assertThat(activity.get(1)).containsEntry("memo", "second").containsEntry("sourceentity", "Warehouse")
            .containsEntry("transactionid", second.transactionId());
        assertThat(amount(activity.get(3).get("debit"))).isEqualByComparingTo("5");
        assertThat(amount(activity.get(3).get("credit"))).isEqualByComparingTo("3");
        assertThat(rows("jabiz.ledger.account_activity", Map.of("account", p + "1100", "from",
            end.plusSeconds(10).toString(), "asOf", end.plusSeconds(20).toString()), null))
            .extracting(row -> row.get("rowkind") + ":" + amount(row.get("runningbalance")).toPlainString())
            .containsExactly("OPENING:12", "CLOSING:12");
    }
}
