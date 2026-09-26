package com.jabiz.app;

import com.jabiz.entity.EntityDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Publishes entity definitions as beans so that {@link EntityDefinitionRegistry} can collect them. */
@Configuration
class EntityDefinitionsConfig {

    @Bean
    EntityDefinition waybillEntityDefinition() {
        return WaybillEntityDefinitions.WAYBILL;
    }

    @Bean
    EntityDefinition customsDeclarationEntityDefinition() {
        return CustomsDeclarationEntityDefinitions.CUSTOMS_DECLARATION;
    }

    @Bean
    EntityDefinition priceEntityDefinition() {
        return PriceEntityDefinitions.PRICE;
    }

    @Bean
    EntityDefinition carrierEntityDefinition() {
        return CarrierEntityDefinitions.CARRIER;
    }
}
