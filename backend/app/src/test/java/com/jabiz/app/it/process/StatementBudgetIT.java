package com.jabiz.app.it.process;

import com.jabiz.app.commerce.CommerceEntities;
import com.jabiz.app.commerce.CommerceProcesses;
import com.jabiz.app.commerce.SupplierDefinitions;
import com.jabiz.app.it.fixture.ItBudgetFixtures;
import com.jabiz.app.it.fixture.ItLockFixtures;
import com.jabiz.app.it.fixture.ItProcessFixtures;
import com.jabiz.app.it.fixture.SqlStatementLog;
import com.jabiz.context.RequestContext;
import com.jabiz.ledger.Direction;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.runtime.DatasetEntityManager;
import com.jabiz.runtime.EntityAction;
import com.jabiz.runtime.EntityChange;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.dataset.DatasetRegistry;
import com.jabiz.runtime.ledger.LedgerEntities;
import com.jabiz.runtime.ledger.LedgerProcesses;
import com.jabiz.runtime.process.ExecutionOptions;
import com.jabiz.runtime.process.ProcessExecutor;
import com.jabiz.runtime.process.ProcessRegistry;
import com.jabiz.runtime.test.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The statement budget of the platform's standard write paths (ROADMAP phase 16p, decision D46 rule 7): how many
 * statements one execution sends in its transaction, its {@code COMMIT} included. The counts are exact, so that a
 * change in either direction is made on purpose: whoever changes a path changes its budget here, and the failure lists
 * the statements. Each case runs once before it is counted, so that caches (dictionaries) are warm as in production.
 */
@SpringBootTest(properties = "it.sql-log.enabled=true")
class StatementBudgetIT extends PostgresIntegrationTest {

    private static final RequestContext CLERK = new RequestContext("it-budget", null, Locale.ENGLISH, "it-budget",
        Set.of(), Set.of("*"));

    @Autowired
    ProcessExecutor executor;

    @Autowired
    ProcessRegistry processes;

    @Autowired
    DatasetEntityManager entities;

    @Autowired
    DatasetRegistry datasets;

    // ---- budgets (before phase 16p in brackets, measured on 961b4b3) ----------------------------------------------

    /**
     * {@code LEDGER_POST} as the process itself, 2 entries, 1 entity and 1 dictionary dimension, a source [28]: the
     * operation (with the lock timeout and its number), the account lock, one read of the dimension values, the source
     * and the accounts, the transaction, the entries, {@code COMMIT}.
     */
    static final int LEDGER_POST_ROOT = 6;
    /**
     * {@code LEDGER_POST} as a sub-process, 2 entries, 2 entity dimensions, a source [31]: its own 5 (operation, account
     * lock, one read, transaction, entries) and the root's operation and {@code COMMIT}.
     */
    static final int LEDGER_POST_CHILD = 7;
    /**
     * [30]: operation, the three reads, the reversal's uniqueness (lock, then look), the transaction, one lookup of
     * both accounts (not read by this process), the entries, {@code COMMIT}.
     */
    static final int LEDGER_REVERSE = 10;
    /**
     * One line [29]: operation, three reads, the order's uniqueness (lock, look) and insert, the line's (lock, look)
     * and insert, the stock's versions and its update, {@code COMMIT}.
     */
    static final int ORDER_PLACE = 13;
    /**
     * One line [47]: operation, the order lock, three reads, {@code LEDGER_POST} 5, the customer (the user query of no
     * customer is not sent), the stock's versions and update, the order's versions and update, {@code COMMIT}.
     */
    static final int ORDER_SHIP = 16;
    /** A plain entity read and updated at the version read [8]: operation, read, update, {@code COMMIT}. */
    static final int KNOWN_VERSION_UPDATE = 4;
    /** [8]: operation, the lock, {@code COMMIT}. */
    static final int HOLD_LOCK = 3;
    /** [8]: operation, the lock with its own wait (one function call), {@code COMMIT}. */
    static final int HOLD_LOCK_BRIEFLY = 3;
    /**
     * A root process with an idempotency key, in its transaction [7]: operation, the ticket with its audit record, the
     * result, {@code COMMIT}; the replay lookup before it is outside.
     */
    static final int IDEMPOTENT_ROOT = 4;
    static final int IDEMPOTENT_LOOKUP = 1;

    // ---- the cases ----------------------------------------------------------------------------------------------

    @Test
    void ledgerPostAsTheProcess() {
        String p = prefix();
        openAccounts(p, "1100", "4000");
        String warehouse = warehouse();
        assertBudget("LEDGER_POST (root)", LEDGER_POST_ROOT, () -> run(LedgerProcesses.POST,
            new LedgerProcesses.PostInput(null, "budget", "ref", List.of(
                new LedgerProcesses.Line(p + "1100", Direction.DEBIT, BigDecimal.TEN, "memo",
                    Map.of("warehouse", code(warehouse), "salesChannel", "WEB")),
                new LedgerProcesses.Line(p + "4000", Direction.CREDIT, BigDecimal.TEN, null,
                    Map.of("warehouse", code(warehouse)))),
                CommerceEntities.WAREHOUSE, id(warehouse))));
    }

    @Test
    void ledgerPostAsASubProcess() {
        String p = prefix();
        openAccounts(p, "1100", "4000");
        String warehouse = warehouse();
        String supplier = supplier();
        assertBudget("LEDGER_POST (sub-process)", LEDGER_POST_CHILD, () -> run(ItBudgetFixtures.POST_AS_CHILD.name(),
            new LedgerProcesses.PostInput(null, "budget", "ref", List.of(
                new LedgerProcesses.Line(p + "1100", Direction.DEBIT, BigDecimal.TEN, null,
                    Map.of("warehouse", code(warehouse), "supplier", supplier)),
                new LedgerProcesses.Line(p + "4000", Direction.CREDIT, BigDecimal.TEN, null,
                    Map.of("warehouse", code(warehouse), "supplier", supplier))),
                CommerceEntities.WAREHOUSE, id(warehouse))));
    }

    @Test
    void ledgerReverse() {
        String p = prefix();
        openAccounts(p, "1100", "4000");
        List<String> posted = new ArrayList<>();
        Supplier<Object> post = () -> {
            LedgerProcesses.PostOutput out = run(LedgerProcesses.POST, new LedgerProcesses.PostInput(null, "budget",
                "ref", List.of(new LedgerProcesses.Line(p + "1100", Direction.DEBIT, BigDecimal.ONE),
                    new LedgerProcesses.Line(p + "4000", Direction.CREDIT, BigDecimal.ONE))));
            posted.add(out.transactionId());
            return out;
        };
        assertBudget("LEDGER_REVERSE", LEDGER_REVERSE, post, () -> run(LedgerProcesses.REVERSE,
            new LedgerProcesses.ReverseInput(posted.getLast(), "budget", null)));
    }

    @Test
    void orderPlaceAndShip() {
        ensureSaleAccounts();
        String warehouse = code(warehouse());
        String sku = product();
        run(CommerceProcesses.STOCK_RECEIVE, new CommerceProcesses.ReceiveInput(warehouse, sku, 100));
        List<String> orders = new ArrayList<>();
        Supplier<Object> place = () -> {
            CommerceProcesses.PlaceOutput out = run(CommerceProcesses.ORDER_PLACE, new CommerceProcesses.PlaceInput(
                "B-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT), "BUDGET", warehouse, List.of(new CommerceProcesses.LineInput(sku, 1))));
            orders.add(out.orderId());
            return out;
        };
        assertBudget("ORDER_PLACE", ORDER_PLACE, place);
        assertBudget("ORDER_SHIP", ORDER_SHIP, place, () -> run(CommerceProcesses.ORDER_SHIP,
            new CommerceProcesses.ShipInput(orders.getLast(), null)));
    }

    @Test
    void aPlainEntityUpdatedAtTheVersionRead() {
        String id = "budget-" + UUID.randomUUID();
        run(ItProcessFixtures.CREATE_TICKET.name(), new ItProcessFixtures.TicketInput(id, "first", 1L));
        int[] round = {0};
        assertBudget("known-version update", KNOWN_VERSION_UPDATE, () -> run(ItBudgetFixtures.TOUCH.name(),
            new ItBudgetFixtures.TouchInput(id, Map.of("title", "title " + round[0]++))));
    }

    @Test
    void holdLock() {
        assertBudget("HoldLock", HOLD_LOCK, () -> run(ItLockFixtures.LOCKED.name(),
            new ItLockFixtures.LockInput("budget-" + UUID.randomUUID(), false, "budget", false)));
        assertBudget("HoldLock (shorter wait)", HOLD_LOCK_BRIEFLY, () -> run(ItLockFixtures.LOCKED_BRIEFLY.name(),
            new ItLockFixtures.LockInput("budget-" + UUID.randomUUID(), false, "budget", false)));
    }

    @Test
    void aRootProcessWithAnIdempotencyKey() {
        @SuppressWarnings("unchecked")
        ProcessDefinition<ItProcessFixtures.TicketInput, ItProcessFixtures.TicketOutput, ProcessContext> create =
            (ProcessDefinition<ItProcessFixtures.TicketInput, ItProcessFixtures.TicketOutput, ProcessContext>)
                processes.find(ItProcessFixtures.CREATE_TICKET.name(), 1).orElseThrow();
        Supplier<Object> once = () -> {
            String id = "budget-" + UUID.randomUUID();
            return asRequest(CLERK, executor.run(create, new ItProcessFixtures.TicketInput(id, "keyed", 1L),
                ExecutionOptions.idempotent("budget-" + id))).block();
        };
        once.get();
        SqlStatementLog.clear();
        once.get();
        List<List<String>> transactions = SqlStatementLog.transactions();
        List<String> outside = SqlStatementLog.outsideTransactions();
        report("idempotent root", IDEMPOTENT_ROOT, transactions);
        assertThat(transactions).hasSize(1);
        assertThat(transactions.getFirst()).as(listing("idempotent root", transactions.getFirst()))
            .hasSize(IDEMPOTENT_ROOT);
        assertThat(outside).as(String.join("\n", outside)).hasSize(IDEMPOTENT_LOOKUP);
    }

    // ---- counting -------------------------------------------------------------------------------------------------

    private void assertBudget(String name, int budget, Supplier<Object> run) {
        assertBudget(name, budget, () -> null, run);
    }

    /** Runs {@code prepare} and {@code run} once to warm up, then counts the statements of {@code run} alone. */
    private void assertBudget(String name, int budget, Supplier<Object> prepare, Supplier<Object> run) {
        prepare.get();
        run.get();
        prepare.get();
        SqlStatementLog.clear();
        run.get();
        List<List<String>> transactions = SqlStatementLog.transactions();
        report(name, budget, transactions);
        assertThat(transactions).as(name + ": one transaction").hasSize(1);
        assertThat(transactions.getFirst()).as(listing(name, transactions.getFirst())).hasSize(budget);
    }

    private static void report(String name, int budget, List<List<String>> transactions) {
        System.out.println("[statement-budget] " + name + ": budget " + budget + ", counted "
            + transactions.stream().map(t -> String.valueOf(t.size())).collect(Collectors.joining(", ")));
        transactions.forEach(t -> System.out.println(listing(name, t)));
    }

    private static String listing(String name, List<String> statements) {
        return name + ":\n" + IntStream.range(0, statements.size())
            .mapToObj(i -> String.format("%3d  %s", i + 1, statements.get(i).replaceAll("\\s+", " ")))
            .collect(Collectors.joining("\n"));
    }

    // ---- data -----------------------------------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private <I, O> O run(String name, I input) {
        ProcessDefinition<I, O, ProcessContext> definition =
            (ProcessDefinition<I, O, ProcessContext>) processes.find(name, 1).orElseThrow();
        return asRequest(CLERK, executor.execute(definition, input)).block();
    }

    private static String prefix() {
        return "B" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT) + "-";
    }

    private void commit(String dataset, String entity, Map<String, Object> attributes, String id) {
        asRequest(CLERK, entities.commitBatch(datasets.findById(dataset).orElseThrow(), List.of(new EntityChange(
            EntityAction.INSERT, new EntityInstance(id, entity, 0, null, attributes), null)))).block();
    }

    private void openAccounts(String prefix, String... codes) {
        for (String code : codes) {
            String id = UUID.randomUUID().toString();
            commit(LedgerEntities.ACCOUNT_DATASET, LedgerEntities.ACCOUNT, Map.of("accountId", id, "accountCode",
                prefix + code, "accountName", "Account " + code, "accountType",
                code.startsWith("4") ? "REVENUE" : "ASSET", "enabled", true), id);
        }
    }

    private void ensureSaleAccounts() {
        if (query("SELECT 1 FROM ledger_account_version WHERE account_code = '"
            + CommerceProcesses.RECEIVABLE_ACCOUNT + "'").isEmpty()) {
            openAccounts("", CommerceProcesses.RECEIVABLE_ACCOUNT, CommerceProcesses.SALES_REVENUE_ACCOUNT);
        }
    }

    /** A new warehouse as {@code code:id}. */
    private String warehouse() {
        String code = "W" + UUID.randomUUID().toString().substring(0, 6).toUpperCase(Locale.ROOT).replace("-", "");
        String id = UUID.randomUUID().toString();
        commit(CommerceEntities.WAREHOUSE_DATASET, CommerceEntities.WAREHOUSE, Map.of("warehouseId", id,
            "warehouseCode", code, "warehouseName", "Budget " + code, "active", true), id);
        return code + ":" + id;
    }

    private static String code(String warehouse) {
        return warehouse.split(":")[0];
    }

    private static String id(String warehouse) {
        return warehouse.split(":")[1];
    }

    private String supplier() {
        String id = UUID.randomUUID().toString();
        String code = "S" + id.substring(0, 6).toUpperCase(Locale.ROOT);
        commit(SupplierDefinitions.DATASET, SupplierDefinitions.SUPPLIER, Map.of("supplierId", id, "supplierCode",
            code, "supplierName", "Budget " + code, "countryCode", "JP", "leadTimeDays", 5, "active", true), id);
        return id;
    }

    private String product() {
        String sku = "BP-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        String id = UUID.randomUUID().toString();
        commit(CommerceEntities.PRODUCT_DATASET, CommerceEntities.PRODUCT, Map.of("productId", id, "sku", sku,
            "productName", "Product " + sku, "unitPrice", 100, "active", true), id);
        return sku;
    }
}
