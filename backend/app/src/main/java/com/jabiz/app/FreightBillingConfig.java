package com.jabiz.app;

import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Registers the freight billing sample ({@link FreightBilling}). */
@Configuration
class FreightBillingConfig {

    @Bean
    EntityDefinition freightChargeEntityDefinition() {
        return FreightBilling.FREIGHT_CHARGE;
    }

    @Bean
    EntityDefinition freightStatementEntityDefinition() {
        return FreightBilling.FREIGHT_STATEMENT;
    }

    @Bean
    DatasetDefinition freightChargeDataset(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        return FreightBilling.dataset(FreightBilling.CHARGE_DATASET, FreightBilling.CHARGE, "logistics.freight",
            poolRef);
    }

    @Bean
    DatasetDefinition freightStatementDataset(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        return FreightBilling.dataset(FreightBilling.STATEMENT_DATASET, FreightBilling.STATEMENT,
            "logistics.freight", poolRef);
    }

    @Bean
    ProcessDefinition<FreightBilling.ChargeInput, FreightBilling.ChargeOutput, FreightBilling.ChargeContext>
        freightChargeProcess() {
        return FreightBilling.CHARGE_PROCESS;
    }

    @Bean
    ProcessDefinition<FreightBilling.CloseInput, FreightBilling.CloseOutput, ProcessContext>
        freightMonthCloseProcess() {
        return FreightBilling.CLOSE_PROCESS;
    }
}
