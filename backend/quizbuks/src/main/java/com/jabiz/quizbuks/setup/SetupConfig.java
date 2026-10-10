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
        return ControlledParams.of(QbParams.CONTROLLED.toArray(String[]::new));
    }
}
