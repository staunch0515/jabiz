package com.jabiz.app.commerce;

import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.entity.BaseEntityDefinitions;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.Rules;
import com.jabiz.entity.TemporalRole;
import com.jabiz.entity.Violation;
import com.jabiz.file.FileKind;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Orders and inventory, the demonstration domain of ROADMAP phase 11: products, warehouses, the stock of each product
 * in each warehouse, and sales orders with their lines. All five are temporal (only ever inserted into) and are
 * declared here and nowhere else: the frontend lists and edits them from their metadata.
 *
 * <p>Stock, orders and order lines change through the processes of {@link CommerceProcesses} only (their datasets are
 * {@code processOnlyWrites}): an order reserves stock, a cancel releases it, a shipment takes it out, so a generic
 * write to any of them would break the counts the others rely on. Products and warehouses are master data and are
 * maintained through their datasets like any other entity.
 */
public final class CommerceEntities extends BaseEntityDefinitions {

    public static final String PRODUCT = "Product";
    public static final String WAREHOUSE = "Warehouse";
    public static final String CUSTOMER = "Customer";
    public static final String STOCK_LEVEL = "StockLevel";
    public static final String STOCK_RECEIPT = "StockReceipt";
    public static final String ORDER = "SalesOrder";
    public static final String ORDER_LINE = "SalesOrderLine";

    public static final String PRODUCT_DATASET = "urn:jabiz:dataset:default:Product";
    public static final String PUBLIC_PRODUCT_DATASET = "urn:jabiz:dataset:public:Product";
    public static final String WAREHOUSE_DATASET = "urn:jabiz:dataset:default:Warehouse";
    public static final String CUSTOMER_DATASET = "urn:jabiz:dataset:default:Customer";
    public static final String STOCK_LEVEL_DATASET = "urn:jabiz:dataset:default:StockLevel";
    public static final String STOCK_RECEIPT_DATASET = "urn:jabiz:dataset:default:StockReceipt";
    public static final String ORDER_DATASET = "urn:jabiz:dataset:default:SalesOrder";
    public static final String ORDER_LINE_DATASET = "urn:jabiz:dataset:default:SalesOrderLine";

    public static final String ORDER_STATUS_DICTIONARY = "urn:jabiz:dict:commerce:order-status";
    public static final String PLACED = "PLACED";
    public static final String SHIPPED = "SHIPPED";
    public static final String CANCELLED = "CANCELLED";

    /** Entity check of {@link #STOCK_LEVEL}: neither count is negative and no more is reserved than is on hand. */
    public static final String STOCK_LEVEL_INVALID = "STOCK_LEVEL_INVALID";

    /** Most lines one order has; also the largest batch the order processes read at once. */
    public static final int MAX_ORDER_LINES = 50;

    public static final EntityDefinition PRODUCT_ENTITY = EntityDefinition.define(PRODUCT, eb -> {
        eb.physicalTable("product_version");
        eb.primaryKey("productId");
        eb.field("productId", f -> f.physicalColumn("product_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:commerce:product"));
        eb.field("sku", f -> f.physicalColumn("sku").immutable(true).required(true).asText(30)
            .apply(Rules.pattern("PRODUCT_SKU_FORMAT", "[A-Z0-9][A-Z0-9-]{1,29}")));
        eb.field("productName", f -> f.physicalColumn("product_name").required(true).asText(200)
            .apply(Rules.notBlank("PRODUCT_NAME_BLANK")));
        eb.field("unitPrice", f -> f.physicalColumn("unit_price").required(true).asMonetary("JPY", 0)
            .apply(Rules.range("PRODUCT_PRICE_RANGE", BigDecimal.ONE, new BigDecimal("10000000")))
            .apply(Rules.scale("PRODUCT_PRICE_SCALE", 0)));
        eb.field("active", f -> f.physicalColumn("active").required(true).asBool());
        // A photo, re-encoded without metadata and in several widths (docs/design/14-files.md).
        eb.field("imageFileId", f -> f.physicalColumn("image_file_id").kind(FileKind.of(CommerceFiles.IMAGE)));
        eb.unique("uk_product_sku", "sku");
        eb.listView("default", lv -> lv
            .columns("imageFileId", "sku", "productName", "unitPrice", "active", "effectStartTime")
            .filters("sku", "productName", "unitPrice", "active")
            .sorts("sku", "productName", "unitPrice")
            .defaultSort("sku", true));
        // Price changes are scheduled ahead: an order is priced at what is in effect when it is placed.
        eb.temporal(t -> t.allowScheduled(true));
    });

    /**
     * A customer, kept by administrators. Its optional {@code userId} is the account that hears of shipments by mail
     * (docs/design/18-numbering-approvals-tasks.md section 5.6): the recipient comes from here, never from an order.
     */
    public static final EntityDefinition CUSTOMER_ENTITY = EntityDefinition.define(CUSTOMER, eb -> {
        eb.physicalTable("customer_version");
        eb.primaryKey("customerId");
        eb.field("customerId", f -> f.physicalColumn("customer_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:commerce:customer"));
        eb.field("customerCode", f -> f.physicalColumn("customer_code").immutable(true).required(true).asText(30)
            .apply(Rules.notBlank("ORDER_CUSTOMER_BLANK")));
        eb.field("customerName", f -> f.physicalColumn("customer_name").required(true).asText(100));
        eb.field("userId", f -> f.physicalColumn("user_id").asReference("SecUser"));
        eb.unique("uk_customer_code", "customerCode");
        eb.display("customerName");
        eb.listView("default", lv -> lv
            .columns("customerCode", "customerName")
            .filters("customerCode", "customerName")
            .sorts("customerCode", "customerName")
            .defaultSort("customerCode", true));
        eb.temporal(t -> t.allowScheduled(false));
    });

    public static final EntityDefinition WAREHOUSE_ENTITY = EntityDefinition.define(WAREHOUSE, eb -> {
        eb.physicalTable("warehouse_version");
        eb.primaryKey("warehouseId");
        eb.field("warehouseId", f -> f.physicalColumn("warehouse_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:commerce:warehouse"));
        eb.field("warehouseCode", f -> f.physicalColumn("warehouse_code").immutable(true).required(true).asText(10)
            .apply(Rules.pattern("WAREHOUSE_CODE_FORMAT", "[A-Z0-9]{2,10}")));
        eb.field("warehouseName", f -> f.physicalColumn("warehouse_name").required(true).asText(100)
            .apply(Rules.notBlank("WAREHOUSE_NAME_BLANK")));
        eb.field("active", f -> f.physicalColumn("active").required(true).asBool());
        eb.unique("uk_warehouse_code", "warehouseCode");
        eb.listView("default", lv -> lv
            .columns("warehouseCode", "warehouseName", "active")
            .filters("warehouseCode", "warehouseName", "active")
            .sorts("warehouseCode", "warehouseName")
            .defaultSort("warehouseCode", true));
        eb.temporal(t -> t.allowScheduled(false));
    });

    public static final EntityDefinition STOCK_LEVEL_ENTITY = EntityDefinition.define(STOCK_LEVEL, eb -> {
        eb.physicalTable("stock_level_version");
        eb.primaryKey("stockLevelId");
        eb.field("stockLevelId", f -> f.physicalColumn("stock_level_id").immutable(true).required(true)
            .generated(true).asSemanticIdentity("urn:jabiz:entity:commerce:stock-level"));
        eb.field("warehouseId", f -> f.physicalColumn("warehouse_id").immutable(true).required(true)
            .asReference(WAREHOUSE));
        eb.field("productId", f -> f.physicalColumn("product_id").immutable(true).required(true)
            .asReference(PRODUCT));
        eb.field("onHand", f -> f.physicalColumn("on_hand").required(true).asNumeric(12, 0));
        eb.field("reserved", f -> f.physicalColumn("reserved").required(true).asNumeric(12, 0));
        // One row per product and warehouse (decision D6: advisory lock and check).
        eb.unique("uk_stock_level_item", "warehouseId", "productId");
        eb.check(STOCK_LEVEL_INVALID, (state, ctx) -> stockLevelProblems(state));
        eb.listView("default", lv -> lv
            .columns("warehouseId", "productId", "onHand", "reserved")
            .filters("warehouseId", "productId", "onHand", "reserved")
            .sorts("onHand", "reserved")
            .defaultSort("onHand", true));
        eb.temporal(t -> t.allowScheduled(false));
    });

    /**
     * One receipt of goods into a warehouse: written once and never changed, like the lines of a ledger (decision
     * D29); a wrong receipt is answered by another movement, not by editing this one. STOCK_RECEIVE records one with
     * every receipt; one recorded directly through the dataset is a record only and does not change the stock.
     */
    public static final EntityDefinition STOCK_RECEIPT_ENTITY = EntityDefinition.define(STOCK_RECEIPT, eb -> {
        eb.physicalTable("stock_receipt_version");
        eb.primaryKey("receiptId");
        eb.field("receiptId", f -> f.physicalColumn("receipt_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:commerce:stock-receipt"));
        eb.field("stockLevelId", f -> f.physicalColumn("stock_level_id").immutable(true).required(true)
            .asReference(STOCK_LEVEL));
        eb.field("quantity", f -> f.physicalColumn("quantity").immutable(true).required(true).asNumeric(12, 0)
            .apply(Rules.range("STOCK_RECEIPT_QUANTITY", BigDecimal.ONE, new BigDecimal("1000000"))));
        eb.field("note", f -> f.physicalColumn("note").immutable(true).asText(200));
        eb.listView("default", lv -> lv
            .columns("stockLevelId", "quantity", "note")
            .filters("stockLevelId", "quantity")
            .sorts("quantity")
            .defaultSort("quantity", false));
        eb.temporal(t -> t.allowScheduled(false).writeOnce());
    });

    public static final EntityDefinition ORDER_ENTITY = EntityDefinition.define(ORDER, eb -> {
        eb.physicalTable("sales_order_version");
        eb.primaryKey("orderId");
        eb.field("orderId", f -> f.physicalColumn("order_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:commerce:sales-order"));
        eb.field("orderNo", f -> f.physicalColumn("order_no").immutable(true).required(true).asText(30)
            .apply(Rules.pattern("ORDER_NO_FORMAT", "[A-Z0-9][A-Z0-9-]{1,29}")));
        eb.field("customerCode", f -> f.physicalColumn("customer_code").immutable(true).required(true).asText(30)
            .apply(Rules.notBlank("ORDER_CUSTOMER_BLANK")));
        eb.field("warehouseId", f -> f.physicalColumn("warehouse_id").immutable(true).required(true)
            .asReference(WAREHOUSE));
        eb.field("orderedTime", f -> f.physicalColumn("ordered_time").immutable(true).required(true)
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("status", f -> f.physicalColumn("status").required(true)
            .asCode(ORDER_STATUS_DICTIONARY, PLACED, SHIPPED, CANCELLED));
        eb.field("totalAmount", f -> f.physicalColumn("total_amount").immutable(true).required(true)
            .asMonetary("JPY", 0));
        eb.field("shippedTime", f -> f.physicalColumn("shipped_time").asTemporal(TemporalRole.EVENT_TIME));
        // Links the order to the logistics sample: the waybill it travels on.
        eb.field("waybillId", f -> f.physicalColumn("waybill_id").asReference("WaybillTracking"));
        eb.stateTransitions("status", st -> {
            st.from(PLACED).to(SHIPPED, CANCELLED);
        });
        eb.unique("uk_sales_order_no", "orderNo");
        eb.publishChanges();
        eb.listView("default", lv -> lv
            .columns("orderNo", "customerCode", "status", "totalAmount", "orderedTime", "shippedTime")
            .filters("orderNo", "customerCode", "status", "totalAmount", "orderedTime")
            .sorts("orderNo", "orderedTime", "totalAmount")
            .defaultSort("orderedTime", false));
        eb.temporal(t -> t.allowScheduled(false));
    });

    public static final EntityDefinition ORDER_LINE_ENTITY = EntityDefinition.define(ORDER_LINE, eb -> {
        eb.physicalTable("sales_order_line_version");
        eb.primaryKey("orderLineId");
        eb.field("orderLineId", f -> f.physicalColumn("order_line_id").immutable(true).required(true)
            .generated(true).asSemanticIdentity("urn:jabiz:entity:commerce:sales-order-line"));
        eb.field("orderId", f -> f.physicalColumn("order_id").immutable(true).required(true).asReference(ORDER));
        eb.field("lineNo", f -> f.physicalColumn("line_no").immutable(true).required(true).asNumeric(4, 0));
        eb.field("productId", f -> f.physicalColumn("product_id").immutable(true).required(true)
            .asReference(PRODUCT));
        eb.field("quantity", f -> f.physicalColumn("quantity").immutable(true).required(true).asNumeric(9, 0));
        eb.field("unitPrice", f -> f.physicalColumn("unit_price").immutable(true).required(true)
            .asMonetary("JPY", 0));
        eb.field("lineAmount", f -> f.physicalColumn("line_amount").immutable(true).required(true)
            .asMonetary("JPY", 0));
        eb.unique("uk_sales_order_line_no", "orderId", "lineNo");
        eb.listView("default", lv -> lv
            .columns("orderId", "lineNo", "productId", "quantity", "unitPrice", "lineAmount")
            .filters("orderId", "productId")
            .sorts("orderId", "lineNo")
            .defaultSort("lineNo", true));
        eb.temporal(t -> t.allowScheduled(false));
    });

    static List<Violation> stockLevelProblems(Map<String, Object> state) {
        BigDecimal onHand = (BigDecimal) state.get("onHand");
        BigDecimal reserved = (BigDecimal) state.get("reserved");
        if (onHand == null || reserved == null) {
            return List.of(); // required is reported by the validator
        }
        if (onHand.signum() < 0 || reserved.signum() < 0 || reserved.compareTo(onHand) > 0) {
            return List.of(new Violation(null, STOCK_LEVEL_INVALID,
                "Stock must not be negative and cannot reserve more than is on hand",
                Map.of("onHand", onHand, "reserved", reserved)));
        }
        return List.of();
    }

    /** The default dataset of a commerce entity; stock and orders take writes from the processes only. */
    static DatasetDefinition dataset(String id, String entity, String readPermission, String writePermission,
        boolean processOnlyWrites, String poolRef) {
        return DatasetDefinition.define(id, d -> d
            .targetEntityType(entity)
            .asDefault()
            .permissions(readPermission, writePermission)
            .policy(p -> {
                p.maxQueryBatchSize(500);
                if (processOnlyWrites) {
                    p.processOnlyWrites();
                }
            })
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef)));
    }

    private CommerceEntities() {}
}
