package com.jabiz.quizbuks.audience;

import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.dictionary.StaticDictionary;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.quizbuks.QbPermissions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the audience rules' management and prices (Q47c-1): the three entities, their default datasets (written
 * by the processes only; the rules read by headquarters' content administrators, the price lists by finance; the
 * operators read their own list through their template, the sponsors their prices through the quote), the fallback's
 * dictionary and the processes.
 */
@Configuration
public class AudiencePricingConfig {

    private final String poolRef;

    AudiencePricingConfig(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        this.poolRef = poolRef;
    }

    @Bean
    EntityDefinition qbAudienceRuleEntity() {
        return QbAudiencePricing.RULE_ENTITY;
    }

    @Bean
    EntityDefinition qbAudiencePriceListEntity() {
        return QbAudiencePricing.PRICE_LIST_ENTITY;
    }

    @Bean
    EntityDefinition qbAudiencePriceEntity() {
        return QbAudiencePricing.PRICE_ENTITY;
    }

    private DatasetDefinition dataset(String entity, String readPermission) {
        return DatasetDefinition.define(QbAudiencePricing.defaultDataset(entity), d -> d
            .targetEntityType(entity)
            .asDefault()
            .permissions(readPermission, QbPermissions.SPONSOR_VIEW_WRITE)
            .policy(p -> p.processOnlyWrites())
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef)));
    }

    @Bean
    DatasetDefinition qbAudienceRuleDataset() {
        return dataset(QbAudiencePricing.RULE, QbPermissions.AUDIENCE_RULES_WRITE);
    }

    @Bean
    DatasetDefinition qbAudiencePriceListDataset() {
        return dataset(QbAudiencePricing.PRICE_LIST, QbPermissions.AUDIENCE_PRICES_WRITE);
    }

    @Bean
    DatasetDefinition qbAudiencePriceDataset() {
        return dataset(QbAudiencePricing.PRICE, QbPermissions.AUDIENCE_PRICES_WRITE);
    }

    @Bean
    StaticDictionary qbAudienceFallbackDictionary() {
        return StaticDictionary.define("urn:jabiz:dict:quizbuks:audience-fallback", d -> d
            .item(AudienceBilling.FALLBACK_DIRECT, "en", "Headquarters' prices", "zh", "按总部的价格",
                "ja", "本部の価格")
            .item(AudienceBilling.FALLBACK_FREE, "en", "Free", "zh", "免费", "ja", "無料"));
    }

    @Bean
    ProcessDefinition<AudiencePricingProcesses.RulesInput, AudiencePricingProcesses.RulesOutput, ProcessContext>
        qbAudienceRulesSet() {
        return AudiencePricingProcesses.rulesSet();
    }

    @Bean
    ProcessDefinition<AudiencePricingProcesses.PricesInput, AudiencePricingProcesses.PricesOutput, ProcessContext>
        qbAudiencePricesSet() {
        return AudiencePricingProcesses.pricesSet();
    }

    @Bean
    ProcessDefinition<AudiencePricingProcesses.PricesInput, AudiencePricingProcesses.PricesOutput, ProcessContext>
        qbOperatorAudiencePricesSet() {
        return AudiencePricingProcesses.operatorPricesSet();
    }

    @Bean
    ProcessDefinition<AudiencePricingProcesses.QuoteInput, AudiencePricingProcesses.QuoteOutput, ProcessContext>
        qbAudienceFeeQuote() {
        return AudiencePricingProcesses.quote();
    }
}
