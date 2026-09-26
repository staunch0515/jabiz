package com.jabiz.runtime.security;

import com.jabiz.context.RequestContext;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.query.EntityQuery;
import com.jabiz.runtime.DatasetEntityManager;
import com.jabiz.runtime.context.RequestContexts;
import com.jabiz.runtime.dataset.DatasetRegistry;
import com.jabiz.runtime.process.ProcessExecutor;
import com.jabiz.security.Sensitive;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The first administrator (docs/design/10-security.md section 7). When {@code jabiz.security.bootstrap-admin.user-name}
 * and {@code .password} are set (from the environment, never the repository) and no user exists yet, startup creates
 * that user with the role {@value #ADMIN_ROLE}, which holds every permission ({@code *}). Otherwise nothing happens.
 */
@Configuration
public class BootstrapAdmin {

    private static final Logger log = LoggerFactory.getLogger(BootstrapAdmin.class);

    public static final String ADMIN_ROLE = "ADMIN";

    public record Input(String userName, @Sensitive String password) {
        @Override
        public String toString() {
            return "BootstrapAdmin.Input[userName=" + userName + ", password=***]";
        }
    }

    public record Output(String userId) {}

    public static final ProcessDefinition<Input, Output, ProcessContext> PROCESS =
        ProcessDefinition.define("SEC_BOOTSTRAP_ADMIN", 1, Input.class, Output.class, ProcessContext.class, pb -> pb
            .description("Creates the first administrator; run by the platform at startup only.")
            .permissions(SecurityPermissions.BOOTSTRAP)
            .contextFactory((start, input) -> {
                ProcessContext ctx = new ProcessContext(start);
                ctx.put("input", input);
                ctx.put(UserProcesses.PASSWORD, input.password());
                return ctx;
            })
            .outputMapper(ctx -> new Output(String.valueOf(ctx.get(UserProcesses.USER_ID))))
            .step("Hash the password", HashPasswordStep.class,
                new HashPasswordStep.Metadata(UserProcesses.PASSWORD, UserProcesses.HASH, UserProcesses.PASSWORD))
            .compute("Register the administrator", (metadata, ctx) -> {
                Input input = ctx.get("input", Input.class);
                Object roleId = ctx.changes().insert(SecurityEntities.ROLE, Map.of("roleCode", ADMIN_ROLE,
                    "labels", Map.of("en", "Administrator", "zh", "管理员", "ja", "管理者"), "enabled", true));
                ctx.changes().insert(SecurityEntities.ROLE_PERMISSION,
                    Map.of("roleId", roleId, "permission", RequestContext.ALL_PERMISSIONS));
                Map<String, Object> user = new LinkedHashMap<>();
                user.put("userName", input.userName());
                user.put("displayName", input.userName());
                user.put("enabled", true);
                user.put("passwordHash", ctx.get(UserProcesses.HASH, String.class));
                Object userId = ctx.changes().insert(SecurityEntities.USER, user);
                ctx.changes().insert(SecurityEntities.USER_ROLE, Map.of("userId", userId, "roleId", roleId));
                ctx.put(UserProcesses.USER_ID, userId);
            }));

    @Bean
    ProcessDefinition<Input, Output, ProcessContext> secBootstrapAdminProcess() {
        return PROCESS;
    }

    @Bean
    ApplicationRunner bootstrapAdministrator(ProcessExecutor processes, DatasetEntityManager entities,
        DatasetRegistry datasets,
        @Value("${jabiz.security.bootstrap-admin.user-name:}") String userName,
        @Value("${jabiz.security.bootstrap-admin.password:}") String password) {
        return (ApplicationArguments arguments) -> {
            if (userName.isBlank() || password.isBlank()) {
                return;
            }
            RequestContext system = RequestContext.system(Locale.ENGLISH, "bootstrap-admin");
            boolean anyUser = Boolean.TRUE.equals(entities.query(
                    datasets.findById(SecurityEntities.USER_DATASET).orElseThrow(), SecurityEntities.SEC_USER,
                    EntityQuery.builder().limit(1).build())
                .hasElements()
                .contextWrite(view -> RequestContexts.put(view, system))
                .block());
            if (anyUser) {
                return;
            }
            Output created = processes.execute(PROCESS, new Input(userName.trim(), password))
                .contextWrite(view -> RequestContexts.put(view, system))
                .block();
            log.info("Created the first administrator {} (user {})", userName.trim(), created.userId());
        };
    }
}
