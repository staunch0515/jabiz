package com.jabiz.runtime.security;

import com.jabiz.context.RequestContext;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.entity.ValidationException;
import com.jabiz.i18n.PlatformErrorCodes;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
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
import reactor.core.publisher.Mono;

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

    private static final String USER_NAME = "user_name";

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
            .internal()
            .contextFactory((start, input) -> {
                ProcessContext ctx = new ProcessContext(start);
                ctx.put(USER_NAME, input.userName());
                ctx.put(UserProcesses.PASSWORD, input.password());
                return ctx;
            })
            .outputMapper(ctx -> new Output(String.valueOf(ctx.get(UserProcesses.USER_ID))))
            .step("Hash the password", HashPasswordStep.class,
                new HashPasswordStep.Metadata(UserProcesses.PASSWORD, UserProcesses.HASH, UserProcesses.PASSWORD))
            .compute("Register the administrator", (metadata, ctx) -> {
                String userName = ctx.get(USER_NAME, String.class);
                Object roleId = ctx.changes().insert(SecurityEntities.ROLE, Map.of("roleCode", ADMIN_ROLE,
                    "labels", Map.of("en", "Administrator", "zh", "管理员", "ja", "管理者"), "enabled", true));
                ctx.changes().insert(SecurityEntities.ROLE_PERMISSION,
                    Map.of("roleId", roleId, "permission", RequestContext.ALL_PERMISSIONS));
                Map<String, Object> user = new LinkedHashMap<>();
                user.put("userName", userName);
                user.put("displayName", userName);
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
            // Only on a database without users, and without the role this would create (users may all be deleted).
            boolean initialized = Boolean.TRUE.equals(entities.query(
                    datasets.findById(SecurityEntities.USER_DATASET).orElseThrow(), SecurityEntities.SEC_USER,
                    EntityQuery.builder().limit(1).build())
                .hasElements()
                .flatMap(anyUser -> anyUser ? Mono.just(true) : entities.query(
                        datasets.findById(SecurityEntities.ROLE_DATASET).orElseThrow(), SecurityEntities.SEC_ROLE,
                        EntityQuery.builder().where(new QueryPredicate.Eq("roleCode", ADMIN_ROLE)).limit(1).build())
                    .hasElements())
                .contextWrite(view -> RequestContexts.put(view, system))
                .block());
            if (initialized) {
                return;
            }
            try {
                Output created = processes.execute(PROCESS, new Input(userName.trim(), password))
                    .contextWrite(view -> RequestContexts.put(view, system))
                    .block();
                log.info("Created the first administrator {} (user {})", userName.trim(), created.userId());
            } catch (ValidationException e) {
                // Instances starting together: another one created the administrator first (decision D6 unique keys).
                if (e.violations().stream().noneMatch(v -> PlatformErrorCodes.UNIQUE_VIOLATION.equals(v.ruleCode()))) {
                    throw e;
                }
                log.info("The first administrator was created by another instance");
            }
        };
    }
}
