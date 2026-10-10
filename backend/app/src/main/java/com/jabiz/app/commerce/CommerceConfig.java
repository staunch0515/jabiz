package com.jabiz.app.commerce;

import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.dictionary.StaticDictionary;
import com.jabiz.document.DocumentLayout;
import com.jabiz.ledger.LedgerDimension;
import com.jabiz.mail.MailTemplate;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.numbering.NumberSequence;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static com.jabiz.app.commerce.CommerceEntities.dataset;

/** Registers the orders-and-inventory sample ({@link CommerceEntities}, {@link CommerceProcesses}). */
@Configuration
class CommerceConfig {

    /** Numbers of orders placed without one, per year (docs/design/18-numbering-approvals-tasks.md section 2). */
    @Bean
    NumberSequence orderNumbers() {
        return NumberSequence.define(CommerceProcesses.ORDER_NUMBERS, s -> s.format("SO-{scope}-{n:6}").scoped());
    }

    /** The shipment notice of {@code ORDER_SHIP} (docs/design/18-numbering-approvals-tasks.md section 5.6). */
    @Bean
    MailTemplate orderShippedMail() {
        return CommerceProcesses.ORDER_SHIPPED_MAIL;
    }

    @Bean
    EntityDefinition customerEntityDefinition() {
        return CommerceEntities.CUSTOMER_ENTITY;
    }

    @Bean
    DatasetDefinition customerDataset(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        return dataset(CommerceEntities.CUSTOMER_DATASET, CommerceEntities.CUSTOMER, "commerce.customer.read",
            "commerce.customer.write", false, poolRef);
    }

    @Bean
    EntityDefinition productEntityDefinition() {
        return CommerceEntities.PRODUCT_ENTITY;
    }

    @Bean
    EntityDefinition warehouseEntityDefinition() {
        return CommerceEntities.WAREHOUSE_ENTITY;
    }

    @Bean
    EntityDefinition stockLevelEntityDefinition() {
        return CommerceEntities.STOCK_LEVEL_ENTITY;
    }

    @Bean
    EntityDefinition stockReceiptEntityDefinition() {
        return CommerceEntities.STOCK_RECEIPT_ENTITY;
    }

    @Bean
    EntityDefinition salesOrderEntityDefinition() {
        return CommerceEntities.ORDER_ENTITY;
    }

    @Bean
    EntityDefinition salesOrderLineEntityDefinition() {
        return CommerceEntities.ORDER_LINE_ENTITY;
    }

    @Bean
    DatasetDefinition productDataset(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        return dataset(CommerceEntities.PRODUCT_DATASET, CommerceEntities.PRODUCT, "commerce.product.read",
            "commerce.product.write", false, poolRef);
    }

    /**
     * The public product catalog (docs/design/15-public-access.md): the products on sale, and of them only what a
     * shop window shows. Back-office users preview it with the product read permission.
     */
    @Bean
    DatasetDefinition publicProductDataset(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        return DatasetDefinition.define(CommerceEntities.PUBLIC_PRODUCT_DATASET, d -> d
            .targetEntityType(CommerceEntities.PRODUCT)
            .scope(s -> s.fixed("active", true))
            .publicRead(p -> p.fields("productId", "sku", "productName", "unitPrice", "imageFileId"))
            .permissions("commerce.product.read", "commerce.product.write")
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef)));
    }

    @Bean
    DatasetDefinition warehouseDataset(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        return dataset(CommerceEntities.WAREHOUSE_DATASET, CommerceEntities.WAREHOUSE, "commerce.warehouse.read",
            "commerce.warehouse.write", false, poolRef);
    }

    @Bean
    DatasetDefinition stockLevelDataset(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        return dataset(CommerceEntities.STOCK_LEVEL_DATASET, CommerceEntities.STOCK_LEVEL, "commerce.stock.read",
            "commerce.stock.receive", true, poolRef);
    }

    /** Receipts are recorded by STOCK_RECEIVE and, by those who receive goods, directly; never changed. */
    @Bean
    DatasetDefinition stockReceiptDataset(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        return dataset(CommerceEntities.STOCK_RECEIPT_DATASET, CommerceEntities.STOCK_RECEIPT, "commerce.stock.read",
            "commerce.stock.receive", false, poolRef);
    }

    @Bean
    DatasetDefinition salesOrderDataset(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        return dataset(CommerceEntities.ORDER_DATASET, CommerceEntities.ORDER, "commerce.order.read",
            "commerce.order.place", true, poolRef);
    }

    @Bean
    DatasetDefinition salesOrderLineDataset(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        return dataset(CommerceEntities.ORDER_LINE_DATASET, CommerceEntities.ORDER_LINE, "commerce.order.read",
            "commerce.order.place", true, poolRef);
    }

    /** Analysis dimensions of ledger entries (docs/design/11-ledger-events-jobs.md section 1.5). */
    @Bean
    LedgerDimension warehouseDimension() {
        return LedgerDimension.define(1, "warehouse", d -> d.entity(CommerceEntities.WAREHOUSE, "warehouseCode"));
    }

    @Bean
    LedgerDimension salesChannelDimension() {
        return LedgerDimension.define(2, "salesChannel", d -> d.dictionary(SALES_CHANNELS));
    }

    static final String SALES_CHANNELS = "urn:jabiz:dict:commerce:sales-channel";

    @Bean
    StaticDictionary salesChannelDictionary() {
        return StaticDictionary.define(SALES_CHANNELS, d -> d
            .item("WEB", "zh", "网店", "ja", "ウェブ", "en", "Web shop")
            .item("STORE", "zh", "门店", "ja", "店舗", "en", "Store"));
    }

    @Bean
    StaticDictionary orderStatusDictionary() {
        return StaticDictionary.define(CommerceEntities.ORDER_STATUS_DICTIONARY, d -> d
            .item(CommerceEntities.PLACED, "zh", "已下单", "ja", "受注済み", "en", "Placed")
            .item(CommerceEntities.SHIPPED, "zh", "已发货", "ja", "出荷済み", "en", "Shipped")
            .item(CommerceEntities.CANCELLED, "zh", "已取消", "ja", "取消済み", "en", "Cancelled"));
    }

    @Bean
    ProcessDefinition<CommerceProcesses.RepriceInput, CommerceProcesses.RepriceOutput, ProcessContext>
        productRepriceProcess() {
        return CommerceProcesses.REPRICE_PROCESS;
    }

    @Bean
    ProcessDefinition<CommerceProcesses.WithdrawInput, CommerceProcesses.WithdrawOutput, ProcessContext>
        productWithdrawProcess() {
        return CommerceProcesses.WITHDRAW_PROCESS;
    }

    @Bean
    ProcessDefinition<CommerceProcesses.ReceiveInput, CommerceProcesses.ReceiveOutput, ProcessContext>
        stockReceiveProcess() {
        return CommerceProcesses.RECEIVE_PROCESS;
    }

    @Bean
    ProcessDefinition<CommerceProcesses.PlaceInput, CommerceProcesses.PlaceOutput, ProcessContext>
        orderPlaceProcess() {
        return CommerceProcesses.PLACE_PROCESS;
    }

    @Bean
    ProcessDefinition<CommerceProcesses.CancelInput, CommerceProcesses.CancelOutput, ProcessContext>
        orderCancelProcess() {
        return CommerceProcesses.CANCEL_PROCESS;
    }

    @Bean
    ProcessDefinition<CommerceProcesses.ShipInput, CommerceProcesses.ShipOutput, ProcessContext>
        orderShipProcess() {
        return CommerceProcesses.SHIP_PROCESS;
    }

    @Bean
    DocumentLayout orderConfirmationLayout() {
        return OrderConfirmations.LAYOUT;
    }

    @Bean
    ProcessDefinition<OrderConfirmations.IssueInput, OrderConfirmations.IssueOutput, ProcessContext>
        orderConfirmationIssueProcess() {
        return OrderConfirmations.ISSUE_PROCESS;
    }

    @Bean
    ProcessDefinition<OrderConfirmations.IssueInput, OrderConfirmations.SendOutput, ProcessContext>
        orderConfirmationSendProcess() {
        return OrderConfirmations.SEND_PROCESS;
    }

    @Bean
    ProcessDefinition<OrderPickLists.ArchiveInput, OrderPickLists.ArchiveOutput, ProcessContext>
        orderPickListArchiveProcess() {
        return OrderPickLists.ARCHIVE_PROCESS;
    }
}
