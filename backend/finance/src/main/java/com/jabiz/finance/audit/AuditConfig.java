package com.jabiz.finance.audit;

import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The audit evidence package (ROADMAP F10b). */
@Configuration
public class AuditConfig {

    @Bean
    ProcessDefinition<AuditProcesses.PackageInput, AuditProcesses.PackageOutput, ProcessContext>
        finAuditPackageProcess() {
        return AuditProcesses.PACKAGE_PROCESS;
    }
}
