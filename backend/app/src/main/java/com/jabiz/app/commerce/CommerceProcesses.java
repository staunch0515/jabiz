package com.jabiz.app.commerce;

import com.jabiz.entity.Violation;
import com.jabiz.ledger.Direction;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.ledger.LedgerProcesses;
import com.jabiz.runtime.numbering.AssignNumber;
import com.jabiz.runtime.process.steps.CallProcess;
import com.jabiz.runtime.process.steps.LoadEntity;
import com.jabiz.runtime.process.steps.QueryEntities;
import com.jabiz.runtime.publicread.FileAccess;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.jabiz.app.commerce.CommerceEntities.CANCELLED;
import static com.jabiz.app.commerce.CommerceEntities.MAX_ORDER_LINES;
import static com.jabiz.app.commerce.CommerceEntities.ORDER;
import static com.jabiz.app.commerce.CommerceEntities.ORDER_DATASET;
import static com.jabiz.app.commerce.CommerceEntities.ORDER_LINE;
import static com.jabiz.app.commerce.CommerceEntities.ORDER_LINE_DATASET;
import static com.jabiz.app.commerce.CommerceEntities.PLACED;
import static com.jabiz.app.commerce.CommerceEntities.PRODUCT;
import static com.jabiz.app.commerce.CommerceEntities.PRODUCT_DATASET;
import static com.jabiz.app.commerce.CommerceEntities.SHIPPED;
import static com.jabiz.app.commerce.CommerceEntities.STOCK_LEVEL;
import static com.jabiz.app.commerce.CommerceEntities.STOCK_LEVEL_DATASET;
import static com.jabiz.app.commerce.CommerceEntities.WAREHOUSE_DATASET;

/**
 * The processes of the orders-and-inventory sample (ROADMAP phase 11). Each is a few platform I/O steps that load
 * what the computation needs, then one synchronous computation that checks the business rules and registers the
 * changes; the platform commits them in the process's transaction (docs/design/06-process.md).
 * <ul>
 *   <li>{@code STOCK_RECEIVE}: goods arrive in a warehouse; the stock row is created on the first receipt.</li>
 *   <li>{@code ORDER_PLACE}: prices the lines at the product prices in effect now and reserves the stock. Every
 *       rule is checked for every line before anything is rejected, so the caller sees all problems at once.</li>
 *   <li>{@code ORDER_CANCEL}: releases the reservation of a placed order.</li>
 *   <li>{@code ORDER_SHIP}: takes the reserved goods out of the warehouse and posts the sale to the ledger
 *       (accounts receivable {@value #RECEIVABLE_ACCOUNT} to sales revenue {@value #SALES_REVENUE_ACCOUNT}).</li>
 * </ul>
 * Every stock change is an update of the stock row under its version (decision D9, rule 1): two orders racing for
 * the last units read the same version, one commits and the other gets 409, so stock is never oversold. The entity
 * check {@link CommerceEntities#STOCK_LEVEL_INVALID} guards the counts on every write as well.
 */
public final class CommerceProcesses {

    public static final String STOCK_RECEIVE = "STOCK_RECEIVE";
    public static final String ORDER_PLACE = "ORDER_PLACE";
    public static final String ORDER_CANCEL = "ORDER_CANCEL";
    public static final String ORDER_SHIP = "ORDER_SHIP";
    public static final String PRODUCT_REPRICE = "PRODUCT_REPRICE";
    public static final String PRODUCT_WITHDRAW = "PRODUCT_WITHDRAW";

    public static final String RECEIVABLE_ACCOUNT = "1130";
    public static final String SALES_REVENUE_ACCOUNT = "4120";

    public static final String WAREHOUSE_NOT_FOUND = "COMMERCE_WAREHOUSE_NOT_FOUND";
    public static final String WAREHOUSE_INACTIVE = "COMMERCE_WAREHOUSE_INACTIVE";
    public static final String PRODUCT_NOT_FOUND = "COMMERCE_PRODUCT_NOT_FOUND";
    public static final String PRODUCT_INACTIVE = "COMMERCE_PRODUCT_INACTIVE";
    public static final String DUPLICATE_SKU = "COMMERCE_DUPLICATE_SKU";
    public static final String STOCK_INSUFFICIENT = "COMMERCE_STOCK_INSUFFICIENT";
    public static final String ORDER_NOT_PLACED = "COMMERCE_ORDER_NOT_PLACED";
    public static final String PRICE_TIME_PAST = "COMMERCE_PRICE_TIME_PAST";

    /** Largest quantity of one receipt or one order line. */
    static final int MAX_QUANTITY = 1_000_000;

    /** @param effectiveTime when the new price applies; now when absent, never in the past */
    public record RepriceInput(@NotBlank String sku, @NotNull @Positive BigDecimal unitPrice, Instant effectiveTime) {}

    public record RepriceOutput(String productId, String sku, BigDecimal unitPrice, Instant effectiveTime) {}

    public record WithdrawInput(@NotNull UUID productId) {}

    /** @param withdrawn false when the product was not on sale already, so nothing changed */
    public record WithdrawOutput(String productId, String sku, boolean withdrawn) {}

    public record ReceiveInput(@NotBlank String warehouseCode, @NotBlank String sku,
        @NotNull @Positive @Max(MAX_QUANTITY) Integer quantity) {}

    public record ReceiveOutput(String stockLevelId, String warehouseCode, String sku, BigDecimal onHand,
        BigDecimal reserved) {}

    public record LineInput(@NotBlank String sku, @NotNull @Positive @Max(MAX_QUANTITY) Integer quantity) {}

    /** @param orderNo the order's number; absent: the next number of {@link #ORDER_NUMBERS} (SO-2026-000001) */
    public record PlaceInput(@Pattern(regexp = "\\S(.*\\S)?") String orderNo, @NotBlank String customerCode,
        @NotBlank String warehouseCode,
        @NotEmpty @Size(max = MAX_ORDER_LINES) List<@Valid @NotNull LineInput> lines) {}

    public record LineOutput(int lineNo, String sku, int quantity, BigDecimal unitPrice, BigDecimal lineAmount) {}

    public record PlaceOutput(String orderId, String orderNo, BigDecimal totalAmount, List<LineOutput> lines) {}

    public record CancelInput(@NotBlank String orderId) {}

    public record CancelOutput(String orderId, String orderNo, String status) {}

    /** @param waybillId optional: the waybill (logistics sample) the goods travel on */
    public record ShipInput(@NotBlank String orderId, String waybillId) {}

    /** @param transactionId the ledger transaction of the sale */
    public record ShipOutput(String orderId, String orderNo, String status, Instant shippedTime,
        String transactionId) {}

    // Context keys.
    static final String INPUT = "input";
    static final String ORDER_ID = "orderId";
    static final String WAREHOUSES = "warehouses";
    static final String PRODUCTS = "products";
    static final String STOCK = "stock";
    static final String ORDER_KEY = "order";
    static final String LINES = "lines";
    static final String OUTPUT = "output";
    static final String PRICED = "priced";
    static final String ORDER_NO = "orderNo";

    /**
     * Numbers of orders placed without one (docs/design/18-numbering-approvals-tasks.md section 2): counted per year,
     * SO-2026-000001, SO-2026-000002 …
     */
    public static final String ORDER_NUMBERS = "commerce.order";
    static final String POSTING_INPUT = "postingInput";
    static final String POSTING = "posting";
    static final String PRODUCT_ID = "productId";
    static final String PRODUCT_KEY = "product";
    static final String IMAGE_FILE_ID = "imageFileId";

    public static final ProcessDefinition<RepriceInput, RepriceOutput, ProcessContext> REPRICE_PROCESS =
        ProcessDefinition.define(PRODUCT_REPRICE, 1, RepriceInput.class, RepriceOutput.class, ProcessContext.class,
            pb -> pb
                .description("Changes the unit price of a product, now or from a time ahead.")
                .permissions("commerce.product.write")
                .contextFactory(CommerceProcesses::withInput)
                .outputMapper(ctx -> ctx.get(OUTPUT, RepriceOutput.class))
                .step("Load the product", QueryEntities.of(PRODUCT_DATASET,
                    ctx -> byCode("sku", input(ctx, RepriceInput.class).sku()), PRODUCTS))
                .compute("Schedule the price", (metadata, ctx) -> reprice(ctx)));

    /**
     * Takes a product off sale: it leaves the public catalog at once (the public dataset's scope), and its photo
     * stops being served anonymously as soon as the commit is through (docs/design/15-public-access.md section 4).
     */
    public static final ProcessDefinition<WithdrawInput, WithdrawOutput, ProcessContext> WITHDRAW_PROCESS =
        ProcessDefinition.define(PRODUCT_WITHDRAW, 1, WithdrawInput.class, WithdrawOutput.class, ProcessContext.class,
            pb -> pb
                .description("Takes a product off sale and out of the public catalog.")
                .permissions("commerce.product.write")
                .actsOn(PRODUCT, PRODUCT_ID, a -> a.whenField("active", "true"))
                .contextFactory((start, input) -> {
                    ProcessContext ctx = withInput(start, input);
                    ctx.put(PRODUCT_ID, input.productId());
                    return ctx;
                })
                .outputMapper(ctx -> ctx.get(OUTPUT, WithdrawOutput.class))
                .step("Load the product", LoadEntity.by(PRODUCT_DATASET, PRODUCT_ID, PRODUCT_KEY))
                .compute("Take it off sale", (metadata, ctx) -> withdraw(ctx))
                .afterCommit("Stop serving its photo publicly",
                    FileAccess.invalidate(FileAccess.fromContext(IMAGE_FILE_ID))));

    public static final ProcessDefinition<ReceiveInput, ReceiveOutput, ProcessContext> RECEIVE_PROCESS =
        ProcessDefinition.define(STOCK_RECEIVE, 1, ReceiveInput.class, ReceiveOutput.class, ProcessContext.class,
            pb -> pb
                .description("Receives goods into a warehouse.")
                .permissions("commerce.stock.receive")
                .contextFactory(CommerceProcesses::withInput)
                .outputMapper(ctx -> ctx.get(OUTPUT, ReceiveOutput.class))
                .step("Load the warehouse", QueryEntities.of(WAREHOUSE_DATASET,
                    ctx -> byCode("warehouseCode", input(ctx, ReceiveInput.class).warehouseCode()), WAREHOUSES))
                .step("Load the product", QueryEntities.of(PRODUCT_DATASET,
                    ctx -> byCode("sku", input(ctx, ReceiveInput.class).sku()), PRODUCTS))
                .step("Load the stock", QueryEntities.of(STOCK_LEVEL_DATASET, CommerceProcesses::stockQuery, STOCK))
                .compute("Receive the goods", (metadata, ctx) -> receive(ctx)));

    public static final ProcessDefinition<PlaceInput, PlaceOutput, ProcessContext> PLACE_PROCESS =
        ProcessDefinition.define(ORDER_PLACE, 1, PlaceInput.class, PlaceOutput.class, ProcessContext.class,
            pb -> pb
                .description("Places a sales order and reserves its stock.")
                .permissions("commerce.order.place")
                .contextFactory(CommerceProcesses::withInput)
                .outputMapper(ctx -> ctx.get(OUTPUT, PlaceOutput.class))
                .step("Load the warehouse", QueryEntities.of(WAREHOUSE_DATASET,
                    ctx -> byCode("warehouseCode", input(ctx, PlaceInput.class).warehouseCode()), WAREHOUSES))
                .step("Load the products", QueryEntities.of(PRODUCT_DATASET, ctx -> EntityQuery.builder()
                    .where(new QueryPredicate.In("sku", List.copyOf(skus(input(ctx, PlaceInput.class)))))
                    .limit(MAX_ORDER_LINES)
                    .build(), PRODUCTS))
                .step("Load the stock", QueryEntities.of(STOCK_LEVEL_DATASET, CommerceProcesses::stockQuery, STOCK))
                .compute("Price the order and check the stock", (metadata, ctx) -> price(ctx))
                // The number is drawn only for an order that will be placed: every drawn number is kept.
                .step("Number the order", AssignNumber.when(
                    ctx -> ctx.contains(PRICED) && input(ctx, PlaceInput.class).orderNo() == null,
                    ORDER_NUMBERS, ctx -> String.valueOf(ctx.opTime().atZone(ZoneOffset.UTC).getYear()), ORDER_NO))
                .compute("Record the order and reserve the stock", (metadata, ctx) -> place(ctx)));

    public static final ProcessDefinition<CancelInput, CancelOutput, ProcessContext> CANCEL_PROCESS =
        ProcessDefinition.define(ORDER_CANCEL, 1, CancelInput.class, CancelOutput.class, ProcessContext.class,
            pb -> pb
                .description("Cancels a placed sales order and releases its stock.")
                .permissions("commerce.order.cancel")
                .contextFactory((start, input) -> {
                    ProcessContext ctx = withInput(start, input);
                    ctx.put(ORDER_ID, input.orderId());
                    return ctx;
                })
                .outputMapper(ctx -> ctx.get(OUTPUT, CancelOutput.class))
                .step("Load the order", LoadEntity.by(ORDER_DATASET, ORDER_ID, ORDER_KEY))
                .step("Load its lines", QueryEntities.of(ORDER_LINE_DATASET, CommerceProcesses::linesQuery, LINES))
                .step("Load the stock", QueryEntities.of(STOCK_LEVEL_DATASET, CommerceProcesses::orderStockQuery,
                    STOCK))
                .compute("Release the stock", (metadata, ctx) -> cancel(ctx)));

    public static final ProcessDefinition<ShipInput, ShipOutput, ProcessContext> SHIP_PROCESS =
        ProcessDefinition.define(ORDER_SHIP, 1, ShipInput.class, ShipOutput.class, ProcessContext.class,
            pb -> pb
                .description("Ships a placed sales order and posts the sale to the ledger.")
                .permissions("commerce.order.ship")
                .contextFactory((start, input) -> {
                    ProcessContext ctx = withInput(start, input);
                    ctx.put(ORDER_ID, input.orderId());
                    return ctx;
                })
                .outputMapper(CommerceProcesses::shipOutput)
                .step("Load the order", LoadEntity.by(ORDER_DATASET, ORDER_ID, ORDER_KEY))
                .step("Load its lines", QueryEntities.of(ORDER_LINE_DATASET, CommerceProcesses::linesQuery, LINES))
                .step("Load the stock", QueryEntities.of(STOCK_LEVEL_DATASET, CommerceProcesses::orderStockQuery,
                    STOCK))
                .compute("Take the goods out", (metadata, ctx) -> ship(ctx))
                // Same transaction: the sale is booked exactly when the goods leave (docs/design/11 section 1.2).
                .step("Post the sale", CallProcess.<ProcessContext>when(ctx -> ctx.contains(POSTING_INPUT),
                    LedgerProcesses.POST, 1, ctx -> ctx.get(POSTING_INPUT), POSTING)));

    // ---- queries built from the context -------------------------------------------------------------------------

    private static EntityQuery byCode(String field, String code) {
        return EntityQuery.builder().where(new QueryPredicate.Eq(field, code)).limit(1).build();
    }

    /** Stock rows of the loaded warehouse and products; none when either is missing. */
    static EntityQuery stockQuery(ProcessContext ctx) {
        EntityInstance warehouse = first(ctx, WAREHOUSES);
        List<Object> productIds = new ArrayList<>();
        for (EntityInstance product : list(ctx, PRODUCTS)) {
            productIds.add(product.id());
        }
        return stockOf(warehouse == null ? null : warehouse.id(), productIds);
    }

    private static EntityQuery linesQuery(ProcessContext ctx) {
        return EntityQuery.builder()
            .where(new QueryPredicate.Eq("orderId", ctx.get(ORDER_KEY, EntityInstance.class).id()))
            .limit(MAX_ORDER_LINES)
            .build();
    }

    private static EntityQuery orderStockQuery(ProcessContext ctx) {
        List<Object> productIds = new ArrayList<>();
        for (EntityInstance line : list(ctx, LINES)) {
            productIds.add(line.get("productId"));
        }
        return stockOf(ctx.get(ORDER_KEY, EntityInstance.class).get("warehouseId"), productIds);
    }

    private static EntityQuery stockOf(Object warehouseId, List<Object> productIds) {
        // An empty IN matches nothing (QueryCompiler), which is what a missing warehouse should load.
        QueryPredicate warehouse = warehouseId == null
            ? new QueryPredicate.In("warehouseId", List.of())
            : new QueryPredicate.Eq("warehouseId", warehouseId);
        return EntityQuery.builder()
            .where(new QueryPredicate.And(List.of(warehouse, new QueryPredicate.In("productId", productIds))))
            .limit(MAX_ORDER_LINES)
            .build();
    }

    // ---- computations -------------------------------------------------------------------------------------------

    static void reprice(ProcessContext ctx) {
        RepriceInput input = input(ctx, RepriceInput.class);
        EntityInstance product = first(ctx, PRODUCTS);
        if (product == null) {
            ctx.reject(new Violation("sku", PRODUCT_NOT_FOUND, "Unknown product " + input.sku(),
                Map.of("sku", input.sku())));
            return;
        }
        Instant effective = input.effectiveTime() == null ? ctx.opTime() : input.effectiveTime();
        if (effective.isBefore(ctx.opTime())) {
            // Correcting a past price is a back-dated correction (temporal.backdate), not a price change.
            ctx.reject(new Violation("effectiveTime", PRICE_TIME_PAST, "A price change cannot start in the past"));
            return;
        }
        // Scheduled through the platform's temporal model: before the effective time orders see the old price,
        // from then on the new one, without any job (docs/design/04-temporal-append-only.md section 4).
        ctx.changes().effectiveAt(effective).update(CommerceEntities.PRODUCT, product.id(), product.version(),
            Map.of("unitPrice", input.unitPrice()));
        ctx.put(OUTPUT, new RepriceOutput(String.valueOf(product.id()), input.sku(), input.unitPrice(), effective));
    }

    static void withdraw(ProcessContext ctx) {
        EntityInstance product = ctx.get(PRODUCT_KEY, EntityInstance.class);
        boolean onSale = Boolean.TRUE.equals(product.get("active"));
        if (onSale) {
            ctx.changes().update(CommerceEntities.PRODUCT, product.id(), product.version(), Map.of("active", false));
        }
        ctx.put(IMAGE_FILE_ID, product.get("imageFileId"));
        ctx.put(OUTPUT, new WithdrawOutput(String.valueOf(product.id()), product.get("sku"), onSale));
    }

    static void receive(ProcessContext ctx) {
        ReceiveInput input = input(ctx, ReceiveInput.class);
        EntityInstance warehouse = activeWarehouse(ctx, input.warehouseCode());
        EntityInstance product = first(ctx, PRODUCTS);
        if (product == null) {
            ctx.reject(new Violation("sku", PRODUCT_NOT_FOUND, "Unknown product " + input.sku(),
                Map.of("sku", input.sku())));
        }
        if (ctx.hasViolations()) {
            return;
        }
        BigDecimal quantity = BigDecimal.valueOf(input.quantity());
        EntityInstance stock = first(ctx, STOCK);
        Object stockLevelId;
        BigDecimal onHand;
        BigDecimal reserved;
        if (stock == null) {
            Map<String, Object> created = new LinkedHashMap<>();
            created.put("warehouseId", warehouse.id());
            created.put("productId", product.id());
            created.put("onHand", quantity);
            created.put("reserved", BigDecimal.ZERO);
            stockLevelId = ctx.changes().insert(STOCK_LEVEL, created);
            onHand = quantity;
            reserved = BigDecimal.ZERO;
        } else {
            stockLevelId = stock.id();
            onHand = stock.<BigDecimal>get("onHand").add(quantity);
            reserved = stock.get("reserved");
            ctx.changes().update(STOCK_LEVEL, stock.id(), stock.version(), Map.of("onHand", onHand));
        }
        ctx.put(OUTPUT, new ReceiveOutput(String.valueOf(stockLevelId), input.warehouseCode(), input.sku(), onHand,
            reserved));
    }

    /** The order as priced and checked, before it is numbered and recorded. */
    private record PricedOrder(EntityInstance warehouse, Map<String, EntityInstance> productsBySku,
        Map<String, EntityInstance> stockByProduct, List<LineOutput> lines, BigDecimal total) {}

    static void price(ProcessContext ctx) {
        PlaceInput input = input(ctx, PlaceInput.class);
        EntityInstance warehouse = activeWarehouse(ctx, input.warehouseCode());
        Map<String, EntityInstance> productsBySku = new HashMap<>();
        for (EntityInstance product : list(ctx, PRODUCTS)) {
            productsBySku.put(product.get("sku"), product);
        }
        Map<String, EntityInstance> stockByProduct = stockByProduct(ctx);

        Set<String> seen = new HashSet<>();
        List<LineOutput> lines = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (int i = 0; i < input.lines().size(); i++) {
            LineInput line = input.lines().get(i);
            String field = "lines[" + i + "]";
            if (!seen.add(line.sku())) {
                ctx.reject(new Violation(field + ".sku", DUPLICATE_SKU, "SKU " + line.sku() + " appears twice",
                    Map.of("sku", line.sku())));
                continue;
            }
            EntityInstance product = productsBySku.get(line.sku());
            if (product == null) {
                ctx.reject(new Violation(field + ".sku", PRODUCT_NOT_FOUND, "Unknown product " + line.sku(),
                    Map.of("sku", line.sku())));
                continue;
            }
            if (!Boolean.TRUE.equals(product.get("active"))) {
                ctx.reject(new Violation(field + ".sku", PRODUCT_INACTIVE, "Product " + line.sku() + " is not sold",
                    Map.of("sku", line.sku())));
                continue;
            }
            BigDecimal quantity = BigDecimal.valueOf(line.quantity());
            BigDecimal available = available(stockByProduct.get(key(product.id())));
            // Without a warehouse there is no stock to compare with; that is reported once, above.
            if (warehouse != null && quantity.compareTo(available) > 0) {
                ctx.reject(new Violation(field + ".quantity", STOCK_INSUFFICIENT,
                    "Only " + available.toPlainString() + " of " + line.sku() + " available",
                    Map.of("sku", line.sku(), "requested", quantity, "available", available)));
                continue;
            }
            BigDecimal unitPrice = product.get("unitPrice");
            BigDecimal amount = unitPrice.multiply(quantity);
            total = total.add(amount);
            lines.add(new LineOutput(i + 1, line.sku(), line.quantity(), unitPrice, amount));
        }
        if (ctx.hasViolations()) {
            return;
        }
        ctx.put(PRICED, new PricedOrder(warehouse, productsBySku, stockByProduct, List.copyOf(lines), total));
    }

    static void place(ProcessContext ctx) {
        if (!ctx.contains(PRICED)) {
            return;
        }
        PlaceInput input = input(ctx, PlaceInput.class);
        PricedOrder priced = ctx.get(PRICED, PricedOrder.class);
        EntityInstance warehouse = priced.warehouse();
        Map<String, EntityInstance> productsBySku = priced.productsBySku();
        Map<String, EntityInstance> stockByProduct = priced.stockByProduct();
        List<LineOutput> lines = priced.lines();
        BigDecimal total = priced.total();
        String orderNo = input.orderNo() != null ? input.orderNo() : ctx.get(ORDER_NO, String.class);

        Map<String, Object> order = new LinkedHashMap<>();
        order.put("orderNo", orderNo);
        order.put("customerCode", input.customerCode());
        order.put("warehouseId", warehouse.id());
        order.put("orderedTime", ctx.opTime());
        order.put("status", PLACED);
        order.put("totalAmount", total);
        Object orderId = ctx.changes().insert(ORDER, order);
        for (LineOutput line : lines) {
            EntityInstance product = productsBySku.get(line.sku());
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("orderId", orderId);
            values.put("lineNo", BigDecimal.valueOf(line.lineNo()));
            values.put("productId", product.id());
            values.put("quantity", BigDecimal.valueOf(line.quantity()));
            values.put("unitPrice", line.unitPrice());
            values.put("lineAmount", line.lineAmount());
            ctx.changes().insert(ORDER_LINE, values);
            EntityInstance stock = stockByProduct.get(key(product.id()));
            BigDecimal reserved = stock.<BigDecimal>get("reserved").add(BigDecimal.valueOf(line.quantity()));
            ctx.changes().update(STOCK_LEVEL, stock.id(), stock.version(), Map.of("reserved", reserved));
        }
        ctx.put(OUTPUT, new PlaceOutput(String.valueOf(orderId), orderNo, total, List.copyOf(lines)));
    }

    static void cancel(ProcessContext ctx) {
        EntityInstance order = placedOrder(ctx);
        if (order == null) {
            return;
        }
        Map<String, EntityInstance> stockByProduct = stockByProduct(ctx);
        for (EntityInstance line : list(ctx, LINES)) {
            EntityInstance stock = stockOfLine(stockByProduct, line);
            BigDecimal reserved = stock.<BigDecimal>get("reserved").subtract(line.get("quantity"));
            ctx.changes().update(STOCK_LEVEL, stock.id(), stock.version(), Map.of("reserved", reserved));
        }
        ctx.changes().update(ORDER, order.id(), order.version(), Map.of("status", CANCELLED));
        ctx.put(OUTPUT, new CancelOutput(String.valueOf(order.id()), order.get("orderNo"), CANCELLED));
    }

    static void ship(ProcessContext ctx) {
        EntityInstance order = placedOrder(ctx);
        if (order == null) {
            return;
        }
        ShipInput input = input(ctx, ShipInput.class);
        Map<String, EntityInstance> stockByProduct = stockByProduct(ctx);
        for (EntityInstance line : list(ctx, LINES)) {
            EntityInstance stock = stockOfLine(stockByProduct, line);
            BigDecimal quantity = line.get("quantity");
            ctx.changes().update(STOCK_LEVEL, stock.id(), stock.version(), Map.of(
                "onHand", stock.<BigDecimal>get("onHand").subtract(quantity),
                "reserved", stock.<BigDecimal>get("reserved").subtract(quantity)));
        }
        Map<String, Object> shipped = new LinkedHashMap<>();
        shipped.put("status", SHIPPED);
        shipped.put("shippedTime", ctx.opTime());
        if (input.waybillId() != null && !input.waybillId().isBlank()) {
            shipped.put("waybillId", input.waybillId());
        }
        ctx.changes().update(ORDER, order.id(), order.version(), shipped);
        BigDecimal total = order.get("totalAmount");
        if (total.signum() > 0) {
            // The ledger links the sale to its order, the document it was posted from.
            ctx.put(POSTING_INPUT, new LedgerProcesses.PostInput(ctx.opTime(), "Sale " + order.get("orderNo"),
                String.valueOf(order.id()), List.of(
                    new LedgerProcesses.Line(RECEIVABLE_ACCOUNT, Direction.DEBIT, total),
                    new LedgerProcesses.Line(SALES_REVENUE_ACCOUNT, Direction.CREDIT, total)),
                ORDER, String.valueOf(order.id())));
        }
    }

    private static ShipOutput shipOutput(ProcessContext ctx) {
        EntityInstance order = ctx.get(ORDER_KEY, EntityInstance.class);
        String transactionId = ctx.contains(POSTING)
            ? ctx.get(POSTING, LedgerProcesses.PostOutput.class).transactionId() : null;
        return new ShipOutput(String.valueOf(order.id()), order.get("orderNo"), SHIPPED, ctx.opTime(), transactionId);
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    private static <I> ProcessContext withInput(com.jabiz.process.ProcessStart start, I input) {
        ProcessContext ctx = new ProcessContext(start);
        ctx.put(INPUT, input);
        return ctx;
    }

    private static <I> I input(ProcessContext ctx, Class<I> type) {
        return ctx.get(INPUT, type);
    }

    private static Set<String> skus(PlaceInput input) {
        Set<String> skus = new HashSet<>();
        for (LineInput line : input.lines()) {
            if (line != null && line.sku() != null) {
                skus.add(line.sku());
            }
        }
        return skus;
    }

    /** The loaded warehouse when it exists and is active; otherwise rejects and returns null. */
    private static EntityInstance activeWarehouse(ProcessContext ctx, String code) {
        EntityInstance warehouse = first(ctx, WAREHOUSES);
        if (warehouse == null) {
            ctx.reject(new Violation("warehouseCode", WAREHOUSE_NOT_FOUND, "Unknown warehouse " + code,
                Map.of("warehouseCode", code)));
            return null;
        }
        if (!Boolean.TRUE.equals(warehouse.get("active"))) {
            ctx.reject(new Violation("warehouseCode", WAREHOUSE_INACTIVE, "Warehouse " + code + " is closed",
                Map.of("warehouseCode", code)));
            return null;
        }
        return warehouse;
    }

    /** The loaded order when it is still placed; otherwise rejects and returns null. */
    private static EntityInstance placedOrder(ProcessContext ctx) {
        EntityInstance order = ctx.get(ORDER_KEY, EntityInstance.class);
        String status = order.get("status");
        if (!PLACED.equals(status)) {
            ctx.reject(new Violation("orderId", ORDER_NOT_PLACED,
                "Order " + order.get("orderNo") + " is " + status, Map.of("status", status)));
            return null;
        }
        return order;
    }

    private static Map<String, EntityInstance> stockByProduct(ProcessContext ctx) {
        Map<String, EntityInstance> stock = new HashMap<>();
        for (EntityInstance row : list(ctx, STOCK)) {
            stock.put(key(row.get("productId")), row);
        }
        return stock;
    }

    private static EntityInstance stockOfLine(Map<String, EntityInstance> stockByProduct, EntityInstance line) {
        EntityInstance stock = stockByProduct.get(key(line.get("productId")));
        if (stock == null) {
            // A placed order reserved its stock, and stock rows are never deleted.
            throw new IllegalStateException("No stock row for line " + line.id() + " of a placed order");
        }
        return stock;
    }

    private static BigDecimal available(EntityInstance stock) {
        return stock == null ? BigDecimal.ZERO
            : stock.<BigDecimal>get("onHand").subtract(stock.get("reserved"));
    }

    /** Identifiers compare as text: references and keys may come back as UUID or String. */
    private static String key(Object id) {
        return String.valueOf(id);
    }

    private static EntityInstance first(ProcessContext ctx, String key) {
        List<EntityInstance> found = list(ctx, key);
        return found.isEmpty() ? null : found.getFirst();
    }

    @SuppressWarnings("unchecked")
    private static List<EntityInstance> list(ProcessContext ctx, String key) {
        Object value = ctx.get(key);
        return value == null ? List.of() : (List<EntityInstance>) value;
    }

    private CommerceProcesses() {}
}
