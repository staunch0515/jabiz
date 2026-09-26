package com.jabiz.app;

import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Registers the sample processes; each becomes executable at {@code POST /api/processes/{name}/{version}}. */
@Configuration
class ProcessesConfig {

    @Bean
    ProcessDefinition<PriceAdjustment.Input, PriceAdjustment.Output, PriceAdjustment.Context> priceAdjustProcess() {
        return PriceAdjustment.DEFINITION;
    }

    @Bean
    ProcessDefinition<TodoCompletion.Input, TodoCompletion.Output, ProcessContext> todoCompleteProcess() {
        return TodoCompletion.DEFINITION;
    }
}
