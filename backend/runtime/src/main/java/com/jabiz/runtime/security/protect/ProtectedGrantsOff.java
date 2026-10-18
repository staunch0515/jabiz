package com.jabiz.runtime.security.protect;

import com.jabiz.context.RequestContext;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.runtime.context.RequestContexts;
import com.jabiz.runtime.process.ProcessExecutor;
import com.jabiz.runtime.security.SecurityPermissions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Locale;

/**
 * Starting with {@code jabiz.security.protected-grants.enabled=false} after the setup window has ended (decision D48,
 * review L2): the four eyes were in force and are switched off. Logged as a warning and recorded as an operation
 * ({@code SEC_PROTECTED_GRANTS_OFF}, run by the system, writing nothing), so that the access review sees when.
 */
@Configuration
public class ProtectedGrantsOff {

    private static final Logger log = LoggerFactory.getLogger(ProtectedGrantsOff.class);

    public record Input(String note) {}

    public record Output(String note) {}

    public static final ProcessDefinition<Input, Output, ProcessContext> PROCESS =
        ProcessDefinition.define("SEC_PROTECTED_GRANTS_OFF", 1, Input.class, Output.class, ProcessContext.class,
            pb -> pb
                .description("Records that protected grants were switched off after the setup window had ended.")
                .permissions(SecurityPermissions.BOOTSTRAP)
                .internal()
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put("note", input.note());
                    return ctx;
                })
                .outputMapper(ctx -> new Output(ctx.get("note", String.class)))
                .compute("Record", (metadata, ctx) -> { }));

    @Bean
    ProcessDefinition<Input, Output, ProcessContext> secProtectedGrantsOffProcess() {
        return PROCESS;
    }

    @Bean
    ApplicationRunner protectedGrantsOffRecorder(ProtectedGrantSettings settings, ProtectedGrants grants,
        ProcessExecutor processes) {
        return arguments -> {
            if (settings.enabled()) {
                return;
            }
            RequestContext system = RequestContext.system(Locale.ENGLISH, "protected-grants");
            Boolean armed = grants.armedOnRecord().contextWrite(view -> RequestContexts.put(view, system)).block();
            if (!Boolean.TRUE.equals(armed)) {
                return;
            }
            log.warn("jabiz.security.protected-grants.enabled=false although the setup window has ended: protected"
                + " grants take effect without a second administrator's approval");
            processes.execute(PROCESS, new Input("jabiz.security.protected-grants.enabled=false"))
                .contextWrite(view -> RequestContexts.put(view, system)).block();
        };
    }
}
