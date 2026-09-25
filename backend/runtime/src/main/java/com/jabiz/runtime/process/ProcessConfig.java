package com.jabiz.process;

import com.jabiz.process.sponsor.LoginContext;
import com.jabiz.process.sponsor.SponsorSignInInput;
import com.jabiz.process.sponsor.SponsorSignInOutput;
import com.jabiz.process.sponsor.SponsorSignInProcess;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/** Publishes process definitions as beans and provides the default process sequence. */
@Configuration
class ProcessConfig {

    @Bean
    ProcessDefinition<SponsorSignInInput, SponsorSignInOutput, LoginContext> sponsorSignInProcess() {
        return SponsorSignInProcess.DEFINITION;
    }

    @Bean
    @ConditionalOnMissingBean(ProcessSequence.class)
    ProcessSequence processSequence(Clock clock) {
        return new ClockProcessSequence(clock);
    }
}
