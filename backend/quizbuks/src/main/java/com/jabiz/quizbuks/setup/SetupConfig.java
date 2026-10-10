package com.jabiz.quizbuks.setup;

import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.quizbuks.country.CountryCatalog;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Registers {@code QB_SETUP}. */
@Configuration
class SetupConfig {

    @Bean
    ProcessDefinition<QbSetupProcesses.SetupInput, QbSetupProcesses.SetupOutput, ProcessContext> qbSetupProcess(
        CountryCatalog countries) {
        return QbSetupProcesses.process(countries);
    }
}
