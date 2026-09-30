package com.jabiz.app.commerce;

import com.jabiz.context.RequestContext;
import com.jabiz.entity.Violation;
import com.jabiz.process.ChangeSet;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessStart;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.ledger.LedgerProcesses;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The computations of the orders-and-inventory processes, without a database: what they reject and what they register
 * (the platform side is covered by {@code CommerceIT} and the scenario replay).
 */
class CommerceProcessesTest {

    private static final Instant NOW = Instant.parse("2026-04-01T00:00:00Z");
    private static final UUID WAREHOUSE = UUID.randomUUID();
    private static final UUID APPLE = UUID.randomUUID();
    private static final UUID PEAR = UUID.randomUUID();

    private static ProcessContext context(Object input) {
        ProcessContext ctx = new ProcessContext(new ProcessStart(1, NOW, new RequestContext("clerk", null,
            Locale.ENGLISH, "r", Set.of(), Set.of()), (type, attributes) -> UUID.randomUUID()));
        ctx.put(CommerceProcesses.INPUT, input);
        return ctx;
    }

    private static EntityInstance warehouse(boolean active) {
        return new EntityInstance(WAREHOUSE, CommerceEntities.WAREHOUSE, 1, null,
            Map.of("warehouseCode", "TKY", "active", active));
    }

    private static EntityInstance product(UUID id, String sku, int price, boolean active) {
        return new EntityInstance(id, CommerceEntities.PRODUCT, 1, null,
            Map.of("sku", sku, "unitPrice", BigDecimal.valueOf(price), "active", active));
    }

    private static EntityInstance stock(UUID product, int onHand, int reserved) {
        return new EntityInstance(UUID.randomUUID(), CommerceEntities.STOCK_LEVEL, 4, null, Map.of(
            "warehouseId", WAREHOUSE, "productId", product.toString(), "onHand", BigDecimal.valueOf(onHand),
            "reserved", BigDecimal.valueOf(reserved)));
    }

    private static CommerceProcesses.PlaceInput order(CommerceProcesses.LineInput... lines) {
        return new CommerceProcesses.PlaceInput("SO-1", "C1", "TKY", List.of(lines));
    }

    @Test
    void placingPricesTheLinesAndReservesTheirStock() {
        ProcessContext ctx = context(order(new CommerceProcesses.LineInput("APPLE", 3),
            new CommerceProcesses.LineInput("PEAR", 2)));
        ctx.put(CommerceProcesses.WAREHOUSES, List.of(warehouse(true)));
        ctx.put(CommerceProcesses.PRODUCTS, List.of(product(APPLE, "APPLE", 120, true), product(PEAR, "PEAR", 300, true)));
        ctx.put(CommerceProcesses.STOCK, List.of(stock(APPLE, 10, 1), stock(PEAR, 2, 0)));

        CommerceProcesses.price(ctx);
        CommerceProcesses.place(ctx);

        assertThat(ctx.violations()).isEmpty();
        CommerceProcesses.PlaceOutput output = ctx.get(CommerceProcesses.OUTPUT, CommerceProcesses.PlaceOutput.class);
        assertThat(output.totalAmount()).isEqualByComparingTo("960");
        List<ChangeSet.Change> changes = ctx.changes().pending();
        assertThat(changes).extracting(c -> c.action() + " " + c.entityType()).containsExactly(
            "INSERT SalesOrder", "INSERT SalesOrderLine", "UPDATE StockLevel", "INSERT SalesOrderLine",
            "UPDATE StockLevel");
        assertThat(changes.get(0).attributes()).containsEntry("status", CommerceEntities.PLACED)
            .containsEntry("orderedTime", NOW);
        assertThat((BigDecimal) changes.get(2).attributes().get("reserved")).isEqualByComparingTo("4");
        assertThat(changes.get(2).version()).isEqualTo(4);
    }

    @Test
    void everyLineProblemIsCollectedAndNothingIsRegistered() {
        ProcessContext ctx = context(order(new CommerceProcesses.LineInput("APPLE", 10),
            new CommerceProcesses.LineInput("NONE", 1), new CommerceProcesses.LineInput("PEAR", 1),
            new CommerceProcesses.LineInput("APPLE", 1)));
        ctx.put(CommerceProcesses.WAREHOUSES, List.of(warehouse(true)));
        ctx.put(CommerceProcesses.PRODUCTS, List.of(product(APPLE, "APPLE", 120, true),
            product(PEAR, "PEAR", 300, false)));
        ctx.put(CommerceProcesses.STOCK, List.of(stock(APPLE, 10, 1)));

        CommerceProcesses.price(ctx);
        CommerceProcesses.place(ctx);

        assertThat(ctx.violations()).extracting(Violation::ruleCode).containsExactly(
            CommerceProcesses.STOCK_INSUFFICIENT, CommerceProcesses.PRODUCT_NOT_FOUND,
            CommerceProcesses.PRODUCT_INACTIVE, CommerceProcesses.DUPLICATE_SKU);
        assertThat(ctx.violations().getFirst().params()).containsEntry("available", BigDecimal.valueOf(9));
        assertThat(ctx.changes().isEmpty()).isTrue();
        // A refused order is not priced, so the numbering step draws no number for it.
        assertThat(ctx.contains(CommerceProcesses.PRICED)).isFalse();
    }

    @Test
    void aClosedWarehouseTakesNoOrdersAndIsReportedOnce() {
        ProcessContext ctx = context(order(new CommerceProcesses.LineInput("APPLE", 1)));
        ctx.put(CommerceProcesses.WAREHOUSES, List.of(warehouse(false)));
        ctx.put(CommerceProcesses.PRODUCTS, List.of(product(APPLE, "APPLE", 120, true)));
        ctx.put(CommerceProcesses.STOCK, List.of());

        CommerceProcesses.price(ctx);
        CommerceProcesses.place(ctx);

        assertThat(ctx.violations()).extracting(Violation::ruleCode)
            .containsExactly(CommerceProcesses.WAREHOUSE_INACTIVE);
    }

    @Test
    void shippingTakesTheReservedGoodsOutAndPreparesTheSalePosting() {
        ProcessContext ctx = context(new CommerceProcesses.ShipInput("o1", "WB-1"));
        ctx.put(CommerceProcesses.ORDER_KEY, new EntityInstance("o1", CommerceEntities.ORDER, 1,
            CommerceEntities.PLACED, Map.of("orderNo", "SO-1", "status", CommerceEntities.PLACED,
            "totalAmount", new BigDecimal("360"))));
        ctx.put(CommerceProcesses.LINES, List.of(new EntityInstance("l1", CommerceEntities.ORDER_LINE, 1, null,
            Map.of("productId", APPLE, "quantity", new BigDecimal("3")))));
        ctx.put(CommerceProcesses.STOCK, List.of(stock(APPLE, 10, 3)));

        CommerceProcesses.ship(ctx);

        List<ChangeSet.Change> changes = ctx.changes().pending();
        assertThat(changes.get(0).attributes()).containsEntry("onHand", new BigDecimal("7"))
            .containsEntry("reserved", new BigDecimal("0"));
        assertThat(changes.get(1).attributes()).containsEntry("status", CommerceEntities.SHIPPED)
            .containsEntry("waybillId", "WB-1").containsEntry("shippedTime", NOW);
        LedgerProcesses.PostInput posting = ctx.get(CommerceProcesses.POSTING_INPUT, LedgerProcesses.PostInput.class);
        assertThat(posting.bookingTime()).isEqualTo(NOW);
        assertThat(posting.entries()).extracting(l -> l.accountCode() + " " + l.direction() + " " + l.amount())
            .containsExactly("1130 DEBIT 360", "4120 CREDIT 360");
    }

    @Test
    void onlyAPlacedOrderIsCancelled() {
        ProcessContext ctx = context(new CommerceProcesses.CancelInput("o1"));
        ctx.put(CommerceProcesses.ORDER_KEY, new EntityInstance("o1", CommerceEntities.ORDER, 2,
            CommerceEntities.SHIPPED, Map.of("orderNo", "SO-1", "status", CommerceEntities.SHIPPED)));

        CommerceProcesses.cancel(ctx);

        assertThat(ctx.violations()).extracting(Violation::ruleCode)
            .containsExactly(CommerceProcesses.ORDER_NOT_PLACED);
        assertThat(ctx.changes().isEmpty()).isTrue();
    }

    @Test
    void aPriceChangeStartsNowOrLaterButNotEarlier() {
        ProcessContext past = context(new CommerceProcesses.RepriceInput("APPLE", BigDecimal.TEN,
            NOW.minusSeconds(1)));
        past.put(CommerceProcesses.PRODUCTS, List.of(product(APPLE, "APPLE", 120, true)));
        CommerceProcesses.reprice(past);
        assertThat(past.violations()).extracting(Violation::ruleCode)
            .containsExactly(CommerceProcesses.PRICE_TIME_PAST);

        ProcessContext later = context(new CommerceProcesses.RepriceInput("APPLE", BigDecimal.TEN,
            NOW.plusSeconds(60)));
        later.put(CommerceProcesses.PRODUCTS, List.of(product(APPLE, "APPLE", 120, true)));
        CommerceProcesses.reprice(later);
        assertThat(later.changes().pending()).singleElement()
            .satisfies(c -> assertThat(c.effectiveTime()).isEqualTo(NOW.plusSeconds(60)));
    }

    @Test
    void stockCannotGoNegativeNorReserveMoreThanIsOnHand() {
        assertThat(CommerceEntities.stockLevelProblems(Map.of("onHand", BigDecimal.ONE, "reserved", BigDecimal.ONE)))
            .isEmpty();
        assertThat(CommerceEntities.stockLevelProblems(Map.of("onHand", BigDecimal.ONE,
            "reserved", BigDecimal.TWO))).extracting(Violation::ruleCode)
            .containsExactly(CommerceEntities.STOCK_LEVEL_INVALID);
        assertThat(CommerceEntities.stockLevelProblems(Map.of("onHand", BigDecimal.ONE.negate(),
            "reserved", BigDecimal.ZERO))).hasSize(1);
    }
}
