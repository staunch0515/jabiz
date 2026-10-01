package com.jabiz.finance.setup;

import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Registers {@code FIN_SETUP}. */
@Configuration
class SetupConfig {

    @Bean
    ProcessDefinition<SetupProcesses.SetupInput, SetupProcesses.SetupOutput, ProcessContext> finSetupProcess() {
        return SetupProcesses.PROCESS;
    }
}
