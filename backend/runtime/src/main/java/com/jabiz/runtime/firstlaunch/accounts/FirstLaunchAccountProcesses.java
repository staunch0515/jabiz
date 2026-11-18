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
import jakarta.validation.Valid;
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
 *   <li>{@value #CREATE}: in the content phase, the waiting role ({@link WaitingRole}) and the accounts - without a
 *       password and without an invitation (the invitations go out once the content is in place) - each holding the
 *       waiting role.</li>
 *   <li>{@value #EMAIL_REVISE}: in the waiting phase, the corrected address of an account the run created that was
 *       never used. Counted as entered at creation ({@code CredentialChain}), so the
 *       invitation ({@code SEC_USER_INVITE}, run next in a transaction of its own, which reads the committed address)
 *       may go there; the earlier link is void as the address changed.</li>
 * </ul>
 */
@Configuration
public class FirstLaunchAccountProcesses {

    public static final String CREATE = "FIRST_LAUNCH_ACCOUNTS_CREATE";
    public static final String EMAIL_REVISE = "FIRST_LAUNCH_EMAIL_REVISE";
    /** The job's round of the waiting phase: grants, the waiting role's withdrawal, reminders. */
    public static final String ADVANCE = "FIRST_LAUNCH_ADVANCE";

    /**
     * An account to create.
     *
     * @param key         the account's key in the configuration
     * @param userName    the name to sign in with (a verified address works too)
     * @param locale      the language of the user's mail; optional
     */
    public record NewAccount(@NotBlank @Size(max = 64) String key, @NotBlank @Size(max = 100) String userName,
        @Size(max = 200) String displayName, @NotBlank @Size(max = 320) String email, @Size(max = 10) String locale) {}

    public record CreateInput(@NotEmpty List<@Valid NewAccount> accounts) {}

    /** @param users the new users by the accounts' keys, in the input's order */
    public record CreateOutput(String roleId, Map<String, String> users) {}

    public record ReviseInput(@NotNull UUID userId, @NotBlank @Size(max = 320) String email) {}

    public record ReviseOutput(String userId, String status) {}

    static final String ACCOUNTS = "accounts";
    static final String EXISTING = "existing";
    static final String ROLE_ID = "role_id";
    static final String USERS = "users";
    static final String USER_ID = "userId";
    static final String USER = "user";
    static final String EMAIL = "email";
    static final String FACTORS = "factors";
    static final String LINKS = "links";

    public static final ProcessDefinition<CreateInput, CreateOutput, ProcessContext> CREATE_PROCESS =
        ProcessDefinition.define(CREATE, 1, CreateInput.class, CreateOutput.class, ProcessContext.class, pb -> pb
            .description("Creates the first launch's accounts, without passwords or invitations, each holding the"
                + " waiting role; run by the first launch only.")
            .permissions(SecurityPermissions.BOOTSTRAP)
            .internal()
            .contextFactory((start, input) -> {
                ProcessContext ctx = new ProcessContext(start);
                ctx.put(ACCOUNTS, List.copyOf(input.accounts()));
                return ctx;
            })
            .outputMapper(ctx -> new CreateOutput(String.valueOf(ctx.get(ROLE_ID)), ctx.get(USERS, Map.class)))
            .step("Find the waiting role", QueryEntities.of(SecurityEntities.ROLE_DATASET,
                ctx -> EntityQuery.builder().where(new QueryPredicate.Eq("roleCode", WaitingRole.CODE)).limit(1)
                    .build(), EXISTING))
            .compute("Register the accounts", (metadata, ctx) -> {
                if (!((List<?>) ctx.get(EXISTING)).isEmpty()) {
                    // One run only: the role exists from an earlier one.
                    ctx.reject(new Violation(null, PlatformErrorCodes.FIRST_LAUNCH_ROLE_RESERVED, "The waiting role "
                        + WaitingRole.CODE + " exists already: the first launch's accounts were created before"));
                    return;
                }
                FirstLaunchGrant grant = FirstLaunchGrant.of(ctx.processSeqId());
                Object roleId = ctx.changes().in(SecurityEntities.ROLE_DATASET).granted(grant).insert(
                    SecurityEntities.ROLE, Map.of("roleCode", WaitingRole.CODE, "labels", Map.of(
                        "en", "Waiting for the first launch", "zh", "首发待命", "ja", "初回起動の待機"),
                        "enabled", true, "requireMfa", true));
                Map<String, String> users = new LinkedHashMap<>();
                @SuppressWarnings("unchecked")
                List<NewAccount> accounts = (List<NewAccount>) ctx.get(ACCOUNTS);
                for (NewAccount account : accounts) {
                    Map<String, Object> user = new LinkedHashMap<>();
                    user.put("userName", account.userName().strip());
                    user.put("displayName", account.displayName());
                    user.put("email", account.email().strip());
                    user.put("locale", account.locale());
                    user.put("enabled", true);
                    Object userId = ctx.changes().insert(SecurityEntities.USER, user);
                    ctx.changes().in(SecurityEntities.USER_ROLE_DATASET).granted(grant)
                        .insert(SecurityEntities.USER_ROLE, Map.of("userId", userId, "roleId", roleId));
                    users.put(account.key(), String.valueOf(userId));
                }
                ctx.put(ROLE_ID, roleId);
                ctx.put(USERS, Map.copyOf(users));
            }));

    public static final ProcessDefinition<ReviseInput, ReviseOutput, ProcessContext> REVISE_PROCESS =
        ProcessDefinition.define(EMAIL_REVISE, 1, ReviseInput.class, ReviseOutput.class, ProcessContext.class, pb -> pb
            .description("Corrects the address of a first launch account nobody has used yet; run by the first"
                + " launch only, which invites it there in a transaction of its own.")
            .permissions(SecurityPermissions.BOOTSTRAP)
            .internal()
            .contextFactory((start, input) -> {
                ProcessContext ctx = new ProcessContext(start);
                ctx.put(USER_ID, input.userId());
                ctx.put(EMAIL, input.email().strip());
                return ctx;
            })
            .outputMapper(ctx -> new ReviseOutput(String.valueOf(ctx.get(USER_ID)), "REVISED"))
            .step("Load the user", LoadEntity.by(SecurityEntities.USER_DATASET, USER_ID, USER))
            .step("Load the second factors", QueryEntities.of(SecurityEntities.USER_MFA_DATASET,
                ctx -> Rbac.all(new QueryPredicate.Eq("userId", ctx.get(USER_ID)), "userMfaId"), FACTORS))
            .step("Load the links", QueryEntities.of(SecurityEntities.USER_IDENTITY_DATASET,
                ctx -> Rbac.all(new QueryPredicate.Eq("userId", ctx.get(USER_ID)), "provider"), LINKS))
            .compute("Correct the address", (metadata, ctx) -> {
                EntityInstance user = ctx.get(USER, EntityInstance.class);
                if (user.get("passwordHash") != null || SecurityEntities.emailVerified(user)
                    || !((List<?>) ctx.get(FACTORS)).isEmpty() || !((List<?>) ctx.get(LINKS)).isEmpty()) {
                    ctx.reject(new Violation(USER_ID, PlatformErrorCodes.INVITATION_NOT_NEEDED, "The account was used"
                        + " already: its address is changed in the administration"));
                    return;
                }
                ctx.changes().update(SecurityEntities.USER, user.id(), user.version(),
                    Map.of("email", ctx.get(EMAIL, String.class)));
            }));

    @Bean
    ProcessDefinition<CreateInput, CreateOutput, ProcessContext> firstLaunchAccountsCreateProcess() {
        return CREATE_PROCESS;
    }

    @Bean
    ProcessDefinition<ReviseInput, ReviseOutput, ProcessContext> firstLaunchEmailReviseProcess() {
        return REVISE_PROCESS;
    }
}
