package com.jabiz.quizbuks.setup;

import com.jabiz.param.ControlledParams;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.quizbuks.country.CountryCatalog;
import com.jabiz.runtime.web.JabizWebProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Registers {@code QB_SETUP} and declares which parameters change only with four eyes (platform decision D40). */
@Configuration
class SetupConfig {

    @Bean
    ProcessDefinition<QbSetupProcesses.SetupInput, QbSetupProcesses.SetupOutput, ProcessContext> qbSetupProcess(
        CountryCatalog countries, JabizWebProperties web) {
        return QbSetupProcesses.process(countries, FirstLaunchSwitches.appliesTo(web.environment()));
    }

    @Bean
    ControlledParams qbControlledParams() {
        return ControlledParams.of(java.util.stream.Stream.of(QbParams.CONTROLLED.stream(),
            java.util.stream.Stream.of(QbParams.SHOPIFY_DAILY_CODES), QbParams.SHOPIFY_PLAN_LIMITS.stream(),
            QbParams.TREE_GATES.stream(), QbParams.SPONSOR_TREE_GATES.stream(),
            java.util.stream.Stream.of(QbParams.FEE_CREDIT_MANUAL_MAX, QbParams.TREE_GRANT_MAX_PER_NODE,
                QbParams.AI_CREDIT_MIN_PURCHASE), QbParams.AI_FEE_SITE_LIMITS.stream())
            .flatMap(java.util.function.Function.identity()).toArray(String[]::new));
    }
}
