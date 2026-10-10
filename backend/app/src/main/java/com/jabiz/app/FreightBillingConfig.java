package com.jabiz.app;

import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.event.EventSubscription;
import com.jabiz.job.JobDefinition;
import com.jabiz.param.ControlledParams;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.retention.RetentionPolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Period;

/** Registers the freight billing sample ({@link FreightBilling}). */
@Configuration
class FreightBillingConfig {

    @Bean
    EntityDefinition freightChargeEntityDefinition() {
        return FreightBilling.FREIGHT_CHARGE;
    }

    /** Charges are accounting records: kept seven years from the end of the fiscal year they shipped in. */
    @Bean
    RetentionPolicy freightChargeRetention() {
        return RetentionPolicy.of(FreightBilling.CHARGE).keep(Period.ofYears(7)).from("shippedTime")
            .afterFiscalYearEnd();
    }

    /**
     * The surcharge rate prices every charge, so one person alone may not change it: it is created, changed and
     * scheduled only through controlled changes that another person publishes (decision D40).
     */
    @Bean
    ControlledParams freightControlledParams() {
        return ControlledParams.of(FreightBilling.SURCHARGE_RATE);
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

    @Bean
    ProcessDefinition<FreightBilling.RevenueInput, FreightBilling.RevenueOutput, ProcessContext>
        freightPostRevenueProcess() {
        return FreightBilling.REVENUE_PROCESS;
    }

    /** Closes the previous month at 00:05 on the first of each month, Japan time. */
    @Bean
    JobDefinition<FreightBilling.CloseInput> freightMonthCloseJob() {
        return JobDefinition.cron(FreightBilling.CLOSE_JOB, "0 5 0 1 * *", FreightBilling.ZONE,
            FreightBilling.CLOSE_PROCESS, FreightBilling::previousMonth);
    }

    /** Posts each closed month's revenue to the ledger. */
    @Bean
    EventSubscription<FreightBilling.RevenueInput> freightRevenueSubscription() {
        return EventSubscription.of(FreightBilling.REVENUE_CONSUMER, FreightBilling.MONTH_CLOSED_EVENT,
            FreightBilling.REVENUE_PROCESS, FreightBilling::revenueOf);
    }
}
