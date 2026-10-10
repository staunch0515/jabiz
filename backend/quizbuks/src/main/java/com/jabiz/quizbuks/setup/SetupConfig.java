package com.jabiz.quizbuks.setup;

import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.quizbuks.country.CountryData;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Registers {@code QB_SETUP}. */
@Configuration
class SetupConfig {

    @Bean
    ProcessDefinition<QbSetupProcesses.SetupInput, QbSetupProcesses.SetupOutput, ProcessContext> qbSetupProcess() {
        // The country file is read here, at startup, and not by the first run on a request thread.
        CountryData.all();
        return QbSetupProcesses.PROCESS;
    }
}
