package com.jabiz.quizbuks.wallet;

import com.jabiz.job.JobDefinition;
import com.jabiz.job.JobItems;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Q88b: the daily settlement of the entry fees (D-Q88-11). */
@Configuration
public class EntryFeeConfig {

    private final ZoneId zone;

    public EntryFeeConfig(@Value("${jabiz.fiscal-year-zone:UTC}") String zone) {
        this.zone = ZoneId.of(zone.strip());
    }

    @Bean
    ProcessDefinition<EntryFeeSettlements.SettleInput, EntryFeeSettlements.SettleOutput, ProcessContext>
        qbEntryFeeSettle() {
        return EntryFeeSettlements.settle();
    }

    /**
     * Every day (03:07 in Tokyo by default; {@link EntryFeeSettlements#RUN_CRON_SETTING} for the end-to-end tests):
     * one transaction per sponsor with fees on 2170, so a failure settles the others (platform decision D42).
     */
    @Bean
    JobDefinition<EntryFeeSettlements.SettleInput> qbEntryFeeSettleJob(
        ProcessDefinition<EntryFeeSettlements.SettleInput, EntryFeeSettlements.SettleOutput, ProcessContext>
            qbEntryFeeSettle,
        @Value("${" + EntryFeeSettlements.RUN_CRON_SETTING + ":" + EntryFeeSettlements.RUN_CRON + "}") String cron) {
        return JobDefinition.forEach(EntryFeeSettlements.JOB, cron.strip(), ZoneOffset.UTC,
                JobItems.template(EntryFeeSettlements.DUE_TEMPLATE, scheduledTime -> Map.of(), "sponsorId"),
                qbEntryFeeSettle,
                (scheduledTime, row) -> new EntryFeeSettlements.SettleInput(String.valueOf(row.get("sponsorId")),
                    scheduledTime.atZone(zone).toLocalDate().toString()))
            .maxItems(10_000);
    }
}
