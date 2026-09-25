package com.jabiz.runtime.process;

import com.jabiz.process.ProcessDefinition;
import com.jabiz.runtime.process.sponsor.LoginContext;
import com.jabiz.runtime.process.sponsor.SponsorSignInInput;
import com.jabiz.runtime.process.sponsor.SponsorSignInOutput;
import com.jabiz.runtime.process.sponsor.SponsorSignInProcess;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.r2dbc.core.DatabaseClient;

/** Publishes process definitions as beans and provides the default process sequence. */
@Configuration
class ProcessConfig {

    @Bean
    ProcessDefinition<SponsorSignInInput, SponsorSignInOutput, LoginContext> sponsorSignInProcess() {
        return SponsorSignInProcess.DEFINITION;
    }

    @Bean
    @ConditionalOnMissingBean(ProcessSequence.class)
    ProcessSequence processSequence(DatabaseClient databaseClient) {
        return new DatabaseProcessSequence(databaseClient);
    }
}
