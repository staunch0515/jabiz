package com.jabiz.app.commerce;

import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.dataset.PublicCounter;
import com.jabiz.dictionary.StaticDictionary;
import com.jabiz.document.DocumentLayout;
import com.jabiz.ledger.LedgerDimension;
import com.jabiz.mail.MailTemplate;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.external.ExternalJobCompletion;
import com.jabiz.external.ExternalJobFailure;
import com.jabiz.external.ExternalJobType;
import com.jabiz.external.ExternalService;
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

    /** The shipment notice as a push notification (decision D69). */
    @Bean
    com.jabiz.push.PushTemplate orderShippedPush() {
        return CommerceProcesses.ORDER_SHIPPED_PUSH;
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

    /**
     * How often anonymous visitors look at a product of the public catalog (docs/design/15-public-access.md section
     * 10; decision D65): once per visitor and day, told in Tokyo time, and only for products on sale.
     */
    @Bean
    PublicCounter productViews() {
        return PublicCounter.define(CommerceEntities.PRODUCT_VIEWS, c -> c
            .keys(CommerceEntities.PUBLIC_PRODUCT_DATASET, "productId")
            .zone("Asia/Tokyo"));
    }

    @Bean
    DatasetDefinition warehouseDataset(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        return dataset(CommerceEntities.WAREHOUSE_DATASET, CommerceEntities.WAREHOUSE, "commerce.warehouse.read",
            "commerce.warehouse.write", false, poolRef);
    }

    /**
     * Stock levels; the warehouse entry's sessions see and write their warehouse's only (decision D64), in the
     * dataset API, STOCK_RECEIVE and the templates alike. Other sessions are not limited.
     */
    @Bean
    DatasetDefinition stockLevelDataset(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        return DatasetDefinition.define(CommerceEntities.STOCK_LEVEL_DATASET, d -> d
            .targetEntityType(CommerceEntities.STOCK_LEVEL)
            .asDefault()
            .permissions("commerce.stock.read", "commerce.stock.receive")
            .scope(s -> s.withinEntryScope(CommerceEntities.WAREHOUSE_SCOPE, "warehouseId"))
            .policy(p -> p.maxQueryBatchSize(500).processOnlyWrites())
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef)));
    }

    @Bean
    EntityDefinition warehouseStaffEntityDefinition() {
        return CommerceEntities.WAREHOUSE_STAFF_ENTITY;
    }

    @Bean
    DatasetDefinition warehouseStaffDataset(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        return dataset(CommerceEntities.WAREHOUSE_STAFF_DATASET, CommerceEntities.WAREHOUSE_STAFF,
            "commerce.warehouse.read", "commerce.warehouse.write", false, poolRef);
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

    /** Purchases and payables by supplier: the value is the supplier's id (an identity source, phase 16h). */
    @Bean
    LedgerDimension supplierDimension() {
        return LedgerDimension.define(3, "supplier", d -> d.entity(SupplierDefinitions.SUPPLIER, "supplierId"));
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

    /** The demo AI service the moderation of product names calls (decision D38). */
    @Bean
    ExternalService demoAi() {
        return ModerateText.DEMO_AI;
    }

    @Bean
    ProcessDefinition<ProductRenaming.RenameInput, ProductRenaming.RenameOutput, ProcessContext>
        productRenameProcess() {
        return ProductRenaming.RENAME_PROCESS;
    }

    /** Marketing copy written by the demo AI service as an external job (decision D38). */
    @Bean
    ExternalJobType<ProductCopy.ProductCopyInput, ProductCopy.ProductCopyResult> productCopyJob() {
        return ProductCopy.COPY_JOB;
    }

    @Bean
    ProcessDefinition<ProductCopy.RequestInput, ProductCopy.RequestOutput, ProcessContext> productCopyRequestProcess() {
        return ProductCopy.REQUEST_PROCESS;
    }

    @Bean
    ProcessDefinition<ExternalJobCompletion, ProductCopy.ApplyOutput, ProcessContext> productCopyApplyProcess() {
        return ProductCopy.APPLY_PROCESS;
    }

    @Bean
    ProcessDefinition<ExternalJobFailure, ProductCopy.ApplyOutput, ProcessContext> productCopyFailedProcess() {
        return ProductCopy.FAILED_PROCESS;
    }

    /** The demo AI service's images, with answers large enough for a photo (decision D50). */
    @Bean
    ExternalService demoAiImages() {
        return ProductImage.DEMO_AI_IMAGES;
    }

    /** A product photo redone by the demo AI service: an external job with an input and an output file (D50). */
    @Bean
    ExternalJobType<ProductImage.ProductImageInput, ProductImage.ProductImageResult> productImageJob() {
        return ProductImage.IMAGE_JOB;
    }

    @Bean
    ProcessDefinition<ProductImage.RequestInput, ProductImage.RequestOutput, ProcessContext>
        productImageRequestProcess() {
        return ProductImage.REQUEST_PROCESS;
    }

    @Bean
    ProcessDefinition<ExternalJobCompletion, ProductImage.ApplyOutput, ProcessContext> productImageApplyProcess() {
        return ProductImage.APPLY_PROCESS;
    }

    @Bean
    ProcessDefinition<ExternalJobFailure, ProductImage.ApplyOutput, ProcessContext> productImageFailedProcess() {
        return ProductImage.FAILED_PROCESS;
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
