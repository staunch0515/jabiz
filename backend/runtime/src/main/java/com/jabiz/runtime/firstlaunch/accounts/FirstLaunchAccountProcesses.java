package com.jabiz.runtime.firstlaunch.accounts;

import com.jabiz.entity.Violation;
import com.jabiz.i18n.PlatformErrorCodes;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.process.steps.LoadEntity;
import com.jabiz.runtime.process.steps.QueryEntities;
import com.jabiz.runtime.security.Rbac;
import com.jabiz.runtime.security.SecurityEntities;
import com.jabiz.runtime.security.SecurityPermissions;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The first launch's accounts (docs/design/09-decisions.md D82; plan F-LAUNCH sections 2.3 and 5). Run by the system
 * only, from the first launch's runner and job:
 * <ul>
 *   <li>{@value #WAITING_ROLE_CREATE} and {@value #ACCOUNT_CREATE}: in the content phase, the waiting role
 *       ({@link WaitingRole}) and each account - without a password and without an invitation (the invitations go out
 *       once the content is in place) - holding the waiting role, recorded in {@code sys_first_launch_account}.</li>
 *   <li>{@value #EMAIL_REVISE}: in the waiting phase, the corrected address of an account the run created that was
 *       never used. Counted as entered at creation ({@code CredentialChain}), so the
 *       invitation ({@code SEC_USER_INVITE}, run next in a transaction of its own, which reads the committed address)
 *       may go there; the earlier link is void as the address changed.</li>
 * </ul>
 */
@Configuration
public class FirstLaunchAccountProcesses {

    public static final String WAITING_ROLE_CREATE = "FIRST_LAUNCH_WAITING_ROLE";
    public static final String ACCOUNT_CREATE = "FIRST_LAUNCH_ACCOUNT_CREATE";
    public static final String EMAIL_REVISE = "FIRST_LAUNCH_EMAIL_REVISE";
    /** The job's item of the waiting phase: invitations, grants, the waiting role's withdrawal, the end. */
    public static final String ADVANCE = "FIRST_LAUNCH_ADVANCE";
    /** The application's steps for a ready account (section 9), as a sub-process of {@value #ADVANCE}. */
    public static final String ACCOUNT_READY = "FIRST_LAUNCH_ACCOUNT_READY";

    public record RoleInput() {}

    public record RoleOutput(String roleId) {}

    /**
     * An account to create.
     *
     * @param key      the account's key in the configuration
     * @param position its place in the configuration's list
     * @param userName the name to sign in with (a verified address works too)
     * @param locale   the language of the user's mail; optional
     * @param roles    the configured role codes, granted in the waiting phase
     * @param report   whether the first launch's report goes to the account
     */
    public record NewAccount(@NotBlank @Size(max = 40) String key, int position,
        @NotBlank @Size(max = 100) String userName, @Size(max = 200) String displayName,
        @NotBlank @Size(max = 320) String email, @Size(max = 10) String locale, @NotEmpty List<String> roles,
        boolean report) {}

    /** The output of {@code account:<key>} steps: the platform's runner reads {@link #userId()}. */
    public record AccountOutput(String userId) {}

    public record ReviseInput(@NotNull UUID userId, @NotBlank @Size(max = 320) String email) {}

    public record ReviseOutput(String userId, String status) {}

    static final String EXISTING = "existing";
    static final String ROLE_ID = "role_id";
    static final String NEW = "new_account";
    static final String USER_ID = "userId";
    static final String USER = "user";
    static final String EMAIL = "email";

    public static final ProcessDefinition<RoleInput, RoleOutput, ProcessContext> WAITING_ROLE_PROCESS =
        ProcessDefinition.define(WAITING_ROLE_CREATE, 1, RoleInput.class, RoleOutput.class, ProcessContext.class,
            pb -> pb
                .description("Creates the first launch's waiting role: no permission, a second factor required; run"
                    + " by the first launch only.")
                .permissions(SecurityPermissions.BOOTSTRAP)
                .internal()
                .contextFactory((start, input) -> new ProcessContext(start))
                .outputMapper(ctx -> new RoleOutput(String.valueOf(ctx.get(ROLE_ID))))
                .step("Check the phase", FirstLaunchContentPhase.require())
                .step("Find the waiting role", QueryEntities.of(SecurityEntities.ROLE_DATASET, ctx -> waitingRole(),
                    EXISTING))
                .compute("Register the waiting role", (metadata, ctx) -> {
                    if (!((List<?>) ctx.get(EXISTING)).isEmpty()) {
                        ctx.reject(new Violation(null, PlatformErrorCodes.FIRST_LAUNCH_ROLE_RESERVED, "The waiting"
                            + " role " + WaitingRole.CODE + " exists already"));
                        return;
                    }
                    ctx.put(ROLE_ID, ctx.changes().in(SecurityEntities.ROLE_DATASET)
                        .granted(FirstLaunchGrant.of(ctx.processSeqId())).insert(SecurityEntities.ROLE, Map.of(
                            "roleCode", WaitingRole.CODE, "labels", Map.of("en", "Waiting for the first launch",
                                "zh", "首发待命", "ja", "初回起動の待機"), "enabled", true, "requireMfa", true)));
                }));

    public static final ProcessDefinition<NewAccount, AccountOutput, ProcessContext> ACCOUNT_PROCESS =
        ProcessDefinition.define(ACCOUNT_CREATE, 1, NewAccount.class, AccountOutput.class, ProcessContext.class,
            pb -> pb
                .description("Creates an account of the first launch, without a password or an invitation, holding"
                    + " the waiting role; run by the first launch only.")
                .permissions(SecurityPermissions.BOOTSTRAP)
                .internal()
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put(NEW, input);
                    return ctx;
                })
                .outputMapper(ctx -> new AccountOutput(String.valueOf(ctx.get(USER_ID))))
                .step("Check the phase", FirstLaunchContentPhase.require())
                .step("Find the waiting role", QueryEntities.of(SecurityEntities.ROLE_DATASET, ctx -> waitingRole(),
                    EXISTING))
                .compute("Register the account", (metadata, ctx) -> {
                    List<?> roles = (List<?>) ctx.get(EXISTING);
                    if (roles.isEmpty()) {
                        throw new IllegalStateException("The waiting role " + WaitingRole.CODE + " does not exist:"
                            + " it is created by the step before the accounts");
                    }
                    NewAccount account = ctx.get(NEW, NewAccount.class);
                    Map<String, Object> user = new LinkedHashMap<>();
                    user.put("userName", account.userName().strip());
                    user.put("displayName", account.displayName());
                    // Stripped: an invitation goes to the address as stored.
                    user.put("email", account.email().strip());
                    user.put("locale", account.locale());
                    user.put("enabled", true);
                    Object userId = ctx.changes().insert(SecurityEntities.USER, user);
                    ctx.changes().in(SecurityEntities.USER_ROLE_DATASET).granted(FirstLaunchGrant.of(
                        ctx.processSeqId())).insert(SecurityEntities.USER_ROLE, Map.of("userId", userId,
                        "roleId", ((EntityInstance) roles.getFirst()).id()));
                    ctx.put(USER_ID, userId);
                })
                .step("Record the account", FirstLaunchContentPhase.record(ctx -> {
                    NewAccount account = ctx.get(NEW, NewAccount.class);
                    return List.of(new FirstLaunchAccountStore.Recorded((UUID) ctx.get(FirstLaunchContentPhase.RUN_ID),
                        UUID.fromString(String.valueOf(ctx.get(USER_ID))), account.position(), account.roles(),
                        account.report()));
                })));

    static EntityQuery waitingRole() {
        return EntityQuery.builder().where(new QueryPredicate.Eq("roleCode", WaitingRole.CODE)).limit(1).build();
    }

    public static final ProcessDefinition<ReviseInput, ReviseOutput, ProcessContext> REVISE_PROCESS =
        ProcessDefinition.define(EMAIL_REVISE, 1, ReviseInput.class, ReviseOutput.class, ProcessContext.class, pb -> pb
            .description("Corrects the address of a first launch account nobody has used yet; run by the first"
                + " launch only. Its job invites the corrected address in a transaction of its own.")
            .permissions(SecurityPermissions.BOOTSTRAP)
            .internal()
            .contextFactory((start, input) -> {
                ProcessContext ctx = new ProcessContext(start);
                ctx.put(USER_ID, input.userId());
                ctx.put(EMAIL, input.email().strip());
                return ctx;
            })
            .outputMapper(ctx -> new ReviseOutput(String.valueOf(ctx.get(USER_ID)), "REVISED"))
            .step("Check the account", FirstLaunchContentPhase.waitingAccount(ctx -> (UUID) ctx.get(USER_ID)))
            .step("Load the user", LoadEntity.by(SecurityEntities.USER_DATASET, USER_ID, USER))
            .step("Check the account's history", CorrectableAddress.of(ctx -> (UUID) ctx.get(USER_ID)))
            .compute("Correct the address", (metadata, ctx) -> {
                if (ctx.hasViolations()) {
                    return;
                }
                EntityInstance user = ctx.get(USER, EntityInstance.class);
                ctx.changes().update(SecurityEntities.USER, user.id(), user.version(),
                    Map.of("email", ctx.get(EMAIL, String.class)));
            }));

    @Bean
    ProcessDefinition<RoleInput, RoleOutput, ProcessContext> firstLaunchWaitingRoleProcess() {
        return WAITING_ROLE_PROCESS;
    }

    @Bean
    ProcessDefinition<NewAccount, AccountOutput, ProcessContext> firstLaunchAccountCreateProcess() {
        return ACCOUNT_PROCESS;
    }

    @Bean
    ProcessDefinition<ReviseInput, ReviseOutput, ProcessContext> firstLaunchEmailReviseProcess() {
        return REVISE_PROCESS;
    }
}
