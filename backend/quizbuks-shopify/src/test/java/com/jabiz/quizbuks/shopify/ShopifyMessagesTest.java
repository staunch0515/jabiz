package com.jabiz.quizbuks.shopify;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/** The module's bundle has the same texts in its three languages, and a text for every code, process and field. */
class ShopifyMessagesTest {

    private static Properties load(String language) throws IOException {
        Properties properties = new Properties();
        try (Reader reader = new InputStreamReader(Objects.requireNonNull(ShopifyMessagesTest.class.getResourceAsStream(
            "/jabiz/quizbuks/shopify/messages_" + language + ".properties")), StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        return properties;
    }

    @Test
    void everyLanguageHasEveryText() throws IOException {
        Properties en = load("en");
        for (String language : List.of("zh", "ja")) {
            Properties other = load(language);
            assertThat(other.stringPropertyNames()).as(language)
                .containsExactlyInAnyOrderElementsOf(en.stringPropertyNames());
            other.stringPropertyNames().forEach(key -> assertThat(other.getProperty(key)).as(key).isNotBlank());
        }
        assertThat(en.stringPropertyNames()).containsAll(ShopifyCodes.ALL)
            .contains("process." + ShopProcesses.JOIN, "process." + ShopProcesses.ADMIN_TRANSFER,
                "process." + ConnectionProcesses.CONNECT, "process." + ConnectionProcesses.CHECK,
                "process." + WebhookProcesses.UNINSTALLED, "process." + WebhookProcesses.SHOP_REDACT,
                "process." + WebhookProcesses.DATA_REQUEST, "process." + WebhookProcesses.CUSTOMERS_REDACT,
                "process." + CampaignProcesses.SAVE, "process." + CampaignProcesses.PUBLISH,
                "process." + CampaignProcesses.MOVE, "process." + CampaignProcesses.LIST,
                "process." + StorefrontProcesses.CAMPAIGN, "process." + StorefrontProcesses.START,
                "process." + StorefrontProcesses.SUBMIT, "process." + DiscountPool.REFILL,
                "query." + CampaignProcesses.STATS_TEMPLATE, "query." + DiscountPool.SHOPS_TEMPLATE,
                "process." + PlanProcesses.SYNC, "process." + PlanProcesses.GET,
                "process." + PlanProcesses.SUBSCRIPTIONS_UPDATE, "query." + PlanProcesses.SHOPS_TEMPLATE);
        for (var entity : List.of(QbsShops.SHOP_ENTITY, QbsShops.MEMBER_ENTITY, QbsShops.TOKEN_ENTITY,
            QbsShops.COMPLIANCE_ENTITY, QbsStorefront.CAMPAIGN_ENTITY, QbsStorefront.TAKER_ENTITY,
            QbsStorefront.BATCH_ENTITY, QbsStorefront.CODE_ENTITY, QbsBilling.PLAN_ENTITY, QbsBilling.USAGE_ENTITY)) {
            assertThat(en.stringPropertyNames()).contains("entity." + entity.name);
            entity.fields.keySet().forEach(field -> assertThat(en.stringPropertyNames())
                .contains("entity." + entity.name + "." + field));
        }
    }
}
