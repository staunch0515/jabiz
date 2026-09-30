package com.jabiz.app.it.ledger;

import com.jabiz.context.RequestContext;
import com.jabiz.ledger.Direction;
import com.jabiz.runtime.DatasetEntityManager;
import com.jabiz.runtime.EntityAction;
import com.jabiz.runtime.EntityChange;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.dataset.DatasetRegistry;
import com.jabiz.runtime.ledger.LedgerEntities;
import com.jabiz.runtime.ledger.LedgerProcesses;
import com.jabiz.runtime.process.ProcessExecutor;
import com.jabiz.runtime.process.ProcessRegistry;
import com.jabiz.runtime.security.JwtService;
import com.jabiz.runtime.test.PostgresIntegrationTest;
import com.jabiz.runtime.test.TestTokens;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Shared steps of the ledger tests: accounts are opened through their dataset, transactions posted and reversed by
 * the ledger processes, balances read through the {@code jabiz.ledger.account_balances} template API. Ledger data is
 * temporal and cannot be cleaned up, so every test works with accounts of its own prefix.
 */
abstract class LedgerItSupport extends PostgresIntegrationTest {

    static final RequestContext ACCOUNTANT = new RequestContext("it-accountant", null, Locale.ENGLISH,
        "it-ledger", Set.of(), Set.of("*"));
    static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {};

    @Autowired
    ProcessExecutor executor;

    @Autowired
    ProcessRegistry processes;

    @Autowired
    DatasetEntityManager entities;

    @Autowired
    DatasetRegistry datasets;

    @Autowired
    ApplicationContext context;

    @Autowired
    JwtService tokens;

    private static final tools.jackson.databind.json.JsonMapper EXACT = tools.jackson.databind.json.JsonMapper.builder()
        .enable(tools.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();

    /** An amount without trailing zeros, so that equal amounts are equal maps values whatever their scale. */
    static BigDecimal amount(Object value) {
        BigDecimal amount = new BigDecimal(String.valueOf(value)).stripTrailingZeros();
        return amount.signum() == 0 ? BigDecimal.ZERO : amount;
    }

    static String prefix() {
        return "L" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT) + "-";
    }

    /** Opens accounts {@code prefix + code} for the given codes, enabled. */
    void openAccounts(String prefix, String... codes) {
        List<EntityChange> changes = new ArrayList<>();
        for (String code : codes) {
            String id = UUID.randomUUID().toString();
            changes.add(new EntityChange(EntityAction.INSERT, new EntityInstance(id, LedgerEntities.ACCOUNT, 0, null,
                Map.of("accountId", id, "accountCode", prefix + code, "accountName", "Account " + code,
                    "accountType", code.startsWith("4") ? "REVENUE" : "ASSET", "enabled", true)), null));
        }
        asRequest(ACCOUNTANT, entities.commitBatch(datasets.findById(LedgerEntities.ACCOUNT_DATASET).orElseThrow(),
            changes)).block();
    }

    @SuppressWarnings("unchecked")
    <I, O> O run(String name, I input) {
        ProcessDefinition<I, O, ProcessContext> definition =
            (ProcessDefinition<I, O, ProcessContext>) processes.find(name, 1).orElseThrow();
        return asRequest(ACCOUNTANT, executor.execute(definition, input)).block();
    }

    LedgerProcesses.PostOutput post(String prefix, Object... lines) {
        List<LedgerProcesses.Line> entries = new ArrayList<>();
        for (int i = 0; i < lines.length; i += 3) {
            entries.add(new LedgerProcesses.Line(prefix + lines[i], (Direction) lines[i + 1],
                new BigDecimal(String.valueOf(lines[i + 2]))));
        }
        return run(LedgerProcesses.POST, new LedgerProcesses.PostInput(null, "it posting", "it-ref", entries));
    }

    LedgerProcesses.ReverseOutput reverse(String transactionId) {
        return run(LedgerProcesses.REVERSE, new LedgerProcesses.ReverseInput(transactionId, "it reversal", null));
    }

    WebTestClient client() {
        return WebTestClient.bindToApplicationContext(context).build();
    }

    String bearer(String... permissions) {
        return TestTokens.bearer(tokens, "it-accountant", permissions);
    }

    /** Balance (debit minus credit) by account code of the accounts with the prefix, as the template API reports. */
    Map<String, BigDecimal> balances(String prefix, Instant asOf) {
        String body = client().post().uri("/api/queries/{id}", "jabiz.ledger.account_balances")
            .header(HttpHeaders.AUTHORIZATION, bearer("ledger.read"))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("params", Map.of("asOf", asOf.toString()),
                "filters", List.of(Map.of("field", "accountcode", "op", "like", "value", prefix + "%")),
                "limit", 500))
            .exchange().expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody();
        // Amounts are read exactly, not as doubles.
        Map<String, Object> page = EXACT.readValue(body, new tools.jackson.core.type.TypeReference<>() {});
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) page.get("items");
        Map<String, BigDecimal> balances = new LinkedHashMap<>();
        for (Map<String, Object> item : items) {
            Map<String, Object> row = new LinkedHashMap<>();
            item.forEach((key, value) -> row.put(key.toLowerCase(Locale.ROOT), value));
            BigDecimal debit = new BigDecimal(String.valueOf(row.get("debittotal")));
            BigDecimal credit = new BigDecimal(String.valueOf(row.get("credittotal")));
            BigDecimal balance = new BigDecimal(String.valueOf(row.get("balance")));
            if (debit.subtract(credit).compareTo(balance) != 0) {
                throw new AssertionError("balance of " + row + " is not debit minus credit");
            }
            balances.put(String.valueOf(row.get("accountcode")).substring(prefix.length()), amount(balance));
        }
        return balances;
    }

    /** Debit minus credit of every transaction of the prefix's accounts, from the raw tables. */
    static List<Map<String, Object>> unbalancedTransactions(String prefix) {
        return query("""
            SELECT e.transaction_id,
                   sum(CASE WHEN e.direction = 'DEBIT' THEN e.amount ELSE -e.amount END) AS difference
            FROM ledger_entry_version e
            WHERE NOT e.is_deleted AND e.transaction_id IN (
                SELECT x.transaction_id FROM ledger_entry_version x
                WHERE x.account_id IN (SELECT account_id FROM ledger_account_version WHERE account_code LIKE ?))
            GROUP BY e.transaction_id
            HAVING sum(CASE WHEN e.direction = 'DEBIT' THEN e.amount ELSE -e.amount END) <> 0""", prefix + "%");
    }

    /** Rows of a ledger template, keys in lower case, filtered to the accounts with the prefix when it has them. */
    List<Map<String, Object>> rows(String template, Map<String, Object> params, String prefix) {
        Map<String, Object> body = new LinkedHashMap<>(Map.of("params", params, "limit", 500));
        if (prefix != null) {
            body.put("filters", List.of(Map.of("field", "accountcode", "op", "like", "value", prefix + "%")));
        }
        Map<String, Object> page = client().post().uri("/api/queries/{id}", template)
            .header(HttpHeaders.AUTHORIZATION, bearer("ledger.read"))
            .contentType(MediaType.APPLICATION_JSON).bodyValue(body)
            .exchange().expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) page.get("items");
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> item : items) {
            Map<String, Object> row = new LinkedHashMap<>();
            item.forEach((key, value) -> row.put(key.toLowerCase(Locale.ROOT), value));
            rows.add(row);
        }
        return rows;
    }

    Map<String, Map<String, Object>> byAccount(List<Map<String, Object>> rows) {
        Map<String, Map<String, Object>> map = new LinkedHashMap<>();
        rows.forEach(row -> map.put(String.valueOf(row.get("accountcode")), row));
        return map;
    }
}
