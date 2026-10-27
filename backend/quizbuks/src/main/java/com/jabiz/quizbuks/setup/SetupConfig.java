package com.jabiz.quizbuks.setup;

import com.jabiz.param.ControlledParams;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.quizbuks.country.CountryCatalog;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Registers {@code QB_SETUP} and declares which parameters change only with four eyes (platform decision D40). */
@Configuration
class SetupConfig {

    @Bean
    ProcessDefinition<QbSetupProcesses.SetupInput, QbSetupProcesses.SetupOutput, ProcessContext> qbSetupProcess(
        CountryCatalog countries) {
        return QbSetupProcesses.process(countries);
    }

    @Bean
    ControlledParams qbControlledParams() {
        return ControlledParams.of(java.util.stream.Stream.of(QbParams.CONTROLLED.stream(),
            java.util.stream.Stream.of(QbParams.SHOPIFY_DAILY_CODES), QbParams.SHOPIFY_PLAN_LIMITS.stream())
            .flatMap(java.util.function.Function.identity()).toArray(String[]::new));
    }
}
