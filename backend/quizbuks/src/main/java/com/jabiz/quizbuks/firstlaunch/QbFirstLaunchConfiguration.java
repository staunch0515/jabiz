package com.jabiz.quizbuks.firstlaunch;

import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.runtime.approval.ControlChanges;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Registers QuizBuks's own steps of the first launch ({@link QbFirstLaunchProcesses}). */
@Configuration
class QbFirstLaunchConfiguration {

    @Bean
    ProcessDefinition<QbFirstLaunchProcesses.ParamInput, ControlChanges.ChangeOutput, ProcessContext>
        qbFirstLaunchParamProcess() {
        return QbFirstLaunchProcesses.param();
    }

    @Bean
    ProcessDefinition<QbFirstLaunchProcesses.ApproveInput, QbFirstLaunchProcesses.ApproveOutput, ProcessContext>
        qbFirstLaunchApproveProcess() {
        return QbFirstLaunchProcesses.approve();
    }

    @Bean
    ProcessDefinition<QbFirstLaunchProcesses.ReadyInput, Boolean, ProcessContext> qbFirstLaunchReadyProcess() {
        return QbFirstLaunchProcesses.ready();
    }

    @Bean
    ProcessDefinition<QbFirstLaunchProcesses.LeadInput, Boolean, ProcessContext> qbFirstLaunchLeadProcess() {
        return QbFirstLaunchProcesses.lead();
    }
}
