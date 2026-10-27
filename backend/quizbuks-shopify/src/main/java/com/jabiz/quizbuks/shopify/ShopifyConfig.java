package com.jabiz.quizbuks.shopify;

import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.dictionary.StaticDictionary;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.event.EventSubscription;
import com.jabiz.external.ExternalService;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.runtime.inbound.InboundEvents;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The declarations of QuizBuks for Shopify (docs/quizbuks/shopify/S1-install.md): its entities and datasets, the
 * Shopify external service, its processes and the consumers of Shopify's webhooks. Every dataset is written by the
 * processes only and read by nobody but the system ({@value Shopify#SYSTEM}): the staff see their shop through
 * the answer of {@code QBS_SHOP_JOIN}.
 */
@Configuration
public class ShopifyConfig {

    private final String poolRef;

    public ShopifyConfig(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        this.poolRef = poolRef;
    }

    @Bean
    EntityDefinition qbsShopEntity() {
        return QbsShops.SHOP_ENTITY;
    }

    @Bean
    EntityDefinition qbsShopMemberEntity() {
        return QbsShops.MEMBER_ENTITY;
    }

    @Bean
    EntityDefinition qbsShopTokenEntity() {
        return QbsShops.TOKEN_ENTITY;
    }

    @Bean
    EntityDefinition qbsComplianceRequestEntity() {
        return QbsShops.COMPLIANCE_ENTITY;
    }

    @Bean
    DatasetDefinition qbsShopDataset() {
        return systemDataset(QbsShops.SHOP);
    }

    @Bean
    DatasetDefinition qbsShopMemberDataset() {
        return systemDataset(QbsShops.MEMBER);
    }

    @Bean
    DatasetDefinition qbsShopTokenDataset() {
        return systemDataset(QbsShops.TOKEN);
    }

    @Bean
    DatasetDefinition qbsComplianceRequestDataset() {
        return systemDataset(QbsShops.COMPLIANCE);
    }

    private DatasetDefinition systemDataset(String entity) {
        return DatasetDefinition.define(QbsShops.defaultDataset(entity), d -> d
            .targetEntityType(entity)
            .asDefault()
            .permissions(Shopify.SYSTEM, Shopify.SYSTEM)
            .policy(p -> p.processOnlyWrites().maxWriteBatchSize(ShopProcesses.MAX_MEMBERS))
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef)));
    }

    @Bean
    StaticDictionary qbsShopStatusDictionary() {
        return StaticDictionary.define(QbsShops.STATUS_DICTIONARY, d -> d
            .item(QbsShops.ACTIVE, "en", "Installed", "zh", "已安装", "ja", "インストール済み")
            .item(QbsShops.UNINSTALLED, "en", "Uninstalled", "zh", "已卸载", "ja", "アンインストール済み"));
    }

    @Bean
    StaticDictionary qbsConnectionDictionary() {
        return StaticDictionary.define(QbsShops.CONNECTION_DICTIONARY, d -> d
            .item(QbsShops.NONE, "en", "Not connected", "zh", "未连接", "ja", "未接続")
            .item(QbsShops.CONNECTED, "en", "Connected", "zh", "已连接", "ja", "接続済み")
            .item(QbsShops.RECONNECT, "en", "Connect again", "zh", "需要重新连接", "ja", "再接続が必要"));
    }

    @Bean
    StaticDictionary qbsMemberRoleDictionary() {
        return StaticDictionary.define(QbsShops.ROLE_DICTIONARY, d -> d
            .item(QbsShops.ADMIN, "en", "Admin", "zh", "主管理员", "ja", "管理者")
            .item(QbsShops.MEMBER_ROLE, "en", "Member", "zh", "成员", "ja", "メンバー"));
    }

    @Bean
    StaticDictionary qbsComplianceTopicDictionary() {
        return StaticDictionary.define(QbsShops.TOPIC_DICTIONARY, d -> d
            .item(QbsShops.DATA_REQUEST, "en", "Customer data request", "zh", "顾客数据请求", "ja", "顧客データの請求")
            .item(QbsShops.CUSTOMERS_REDACT, "en", "Customer data erasure", "zh", "删除顾客数据", "ja", "顧客データの削除")
            .item(QbsShops.SHOP_REDACT, "en", "Shop data erasure", "zh", "删除店铺数据", "ja", "ストアデータの削除"));
    }

    @Bean
    StaticDictionary qbsComplianceOutcomeDictionary() {
        return StaticDictionary.define(QbsShops.OUTCOME_DICTIONARY, d -> d
            .item(QbsShops.NO_DATA, "en", "No data held", "zh", "没有数据", "ja", "データなし")
            .item(QbsShops.DELETED, "en", "Deleted", "zh", "已删除", "ja", "削除済み")
            .item(QbsShops.KEPT_REINSTALLED, "en", "Kept: installed again", "zh", "保留：已重新安装",
                "ja", "保持：再インストール済み")
            .item(QbsShops.NOT_FOUND, "en", "No data of the shop", "zh", "没有该店铺的数据", "ja", "ストアのデータなし")
            .item(QbsShops.MISMATCH, "en", "Shop does not match: not acted on", "zh", "店铺不一致：未处理",
                "ja", "ストア不一致：未処理"));
    }

    @Bean
    ExternalService shopifyService() {
        return ShopifyApi.DEFINITION;
    }

    @Bean
    ProcessDefinition<ShopProcesses.JoinInput, ShopProcesses.JoinOutput, ProcessContext> qbsShopJoinProcess() {
        return ShopProcesses.join();
    }

    @Bean
    ProcessDefinition<ShopProcesses.TransferInput, ShopProcesses.TransferOutput, ProcessContext>
        qbsShopAdminTransferProcess() {
        return ShopProcesses.adminTransfer();
    }

    @Bean
    ProcessDefinition<ConnectionProcesses.ConnectInput, ConnectionProcesses.ConnectionOutput, ProcessContext>
        qbsShopConnectProcess() {
        return ConnectionProcesses.connect();
    }

    @Bean
    ProcessDefinition<ConnectionProcesses.CheckInput, ConnectionProcesses.ConnectionOutput, ProcessContext>
        qbsShopCheckProcess() {
        return ConnectionProcesses.check();
    }

    @Bean
    ProcessDefinition<WebhookProcesses.InboundRef, WebhookProcesses.Outcome, ProcessContext>
        qbsShopifyUninstalledProcess() {
        return WebhookProcesses.uninstalled();
    }

    @Bean
    ProcessDefinition<WebhookProcesses.InboundRef, WebhookProcesses.Outcome, ProcessContext>
        qbsShopifyShopRedactProcess() {
        return WebhookProcesses.shopRedact();
    }

    @Bean
    ProcessDefinition<WebhookProcesses.InboundRef, WebhookProcesses.Outcome, ProcessContext>
        qbsShopifyDataRequestProcess() {
        return WebhookProcesses.dataRequest();
    }

    @Bean
    ProcessDefinition<WebhookProcesses.InboundRef, WebhookProcesses.Outcome, ProcessContext>
        qbsShopifyCustomersRedactProcess() {
        return WebhookProcesses.customersRedact();
    }

    @Bean
    EventSubscription<WebhookProcesses.InboundRef> qbsShopifyUninstalledSubscription(
        ProcessDefinition<WebhookProcesses.InboundRef, WebhookProcesses.Outcome, ProcessContext>
            qbsShopifyUninstalledProcess) {
        return subscription("qbs.shopify-app-uninstalled", QbsShops.UNINSTALL, qbsShopifyUninstalledProcess);
    }

    @Bean
    EventSubscription<WebhookProcesses.InboundRef> qbsShopifyShopRedactSubscription(
        ProcessDefinition<WebhookProcesses.InboundRef, WebhookProcesses.Outcome, ProcessContext>
            qbsShopifyShopRedactProcess) {
        return subscription("qbs.shopify-shop-redact", QbsShops.SHOP_REDACT, qbsShopifyShopRedactProcess);
    }

    @Bean
    EventSubscription<WebhookProcesses.InboundRef> qbsShopifyDataRequestSubscription(
        ProcessDefinition<WebhookProcesses.InboundRef, WebhookProcesses.Outcome, ProcessContext>
            qbsShopifyDataRequestProcess) {
        return subscription("qbs.shopify-customers-data-request", QbsShops.DATA_REQUEST,
            qbsShopifyDataRequestProcess);
    }

    @Bean
    EventSubscription<WebhookProcesses.InboundRef> qbsShopifyCustomersRedactSubscription(
        ProcessDefinition<WebhookProcesses.InboundRef, WebhookProcesses.Outcome, ProcessContext>
            qbsShopifyCustomersRedactProcess) {
        return subscription("qbs.shopify-customers-redact", QbsShops.CUSTOMERS_REDACT,
            qbsShopifyCustomersRedactProcess);
    }

    private static EventSubscription<WebhookProcesses.InboundRef> subscription(String name, String topic,
        ProcessDefinition<WebhookProcesses.InboundRef, WebhookProcesses.Outcome, ProcessContext> process) {
        return EventSubscription.of(name, InboundEvents.type(Shopify.WEBHOOKS, topic), process,
            WebhookProcesses::input);
    }
}
