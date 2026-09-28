package com.jabiz.app.commerce;

import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.dictionary.StaticDictionary;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static com.jabiz.app.commerce.CommerceEntities.dataset;

/** Registers the orders-and-inventory sample ({@link CommerceEntities}, {@link CommerceProcesses}). */
@Configuration
class CommerceConfig {

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
}
