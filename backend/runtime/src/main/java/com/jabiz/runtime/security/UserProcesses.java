package com.jabiz.runtime.security;

import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.process.sponsor.SponsorSignInProcess;
import com.jabiz.runtime.process.steps.LoadEntity;
import com.jabiz.runtime.process.steps.QueryEntities;
import com.jabiz.security.LoginAttemptPolicy;
import com.jabiz.security.LoginOutcome;
import com.jabiz.security.MfaRequirement;
import com.jabiz.security.Sensitive;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The processes that set what the dataset API cannot (docs/design/10-security.md section 3): creating a user with a
 * password, setting a password, lifting a lock. The password hash is sensitive, so these are its only writers.
 */
@Configuration
public class UserProcesses {

    /** @param email where notifications go; optional */
    public record CreateUserInput(@NotBlank String userName, String displayName, String tenantId,
        @Sensitive @NotBlank String password, Boolean enabled, String email) {
        @Override
        public String toString() {
            return "CreateUserInput[userName=" + userName + ", password=***]";
        }
    }

    public record SetPasswordInput(@NotNull String userId, @Sensitive @NotBlank String password) {
        @Override
        public String toString() {
            return "SetPasswordInput[userId=" + userId + ", password=***]";
        }
    }

    public record UserIdInput(@NotNull String userId) {}

    public record UserIdOutput(String userId) {}

    static final String PASSWORD = "password";
    static final String HASH = "password_hash";
    static final String USER_ID = "user_id";
    static final String USER = "user";
    static final String LATEST_RECORD = "latest_login_record";
    static final String NEW_USER = "new_user";

    private static final HashPasswordStep.Metadata HASH_PASSWORD =
        new HashPasswordStep.Metadata(PASSWORD, HASH, PASSWORD);

    public static final ProcessDefinition<CreateUserInput, UserIdOutput, ProcessContext> CREATE_USER =
        ProcessDefinition.define("SEC_USER_CREATE", 1, CreateUserInput.class, UserIdOutput.class,
            ProcessContext.class, pb -> pb
                .description("Creates a user with a password.")
                .permissions(SecurityPermissions.USER_CREATE)
                .requiresMfa(MfaRequirement.ADMINISTRATION)
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    // The fields one by one: the input record holds the plain password, which only the hashing
                    // step may see (and removes).
                    Map<String, Object> user = new LinkedHashMap<>();
                    user.put("userName", input.userName());
                    user.put("displayName", input.displayName());
                    user.put("tenantId", input.tenantId());
                    user.put("email", input.email());
                    user.put("enabled", input.enabled() == null || input.enabled());
                    ctx.put(NEW_USER, user);
                    ctx.put(PASSWORD, input.password());
                    return ctx;
                })
                .outputMapper(ctx -> new UserIdOutput(String.valueOf(ctx.get(USER_ID))))
                .step("Hash the password", HashPasswordStep.class, HASH_PASSWORD)
                .compute("Register the user", (metadata, ctx) -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> user = new LinkedHashMap<>((Map<String, Object>) ctx.get(NEW_USER));
                    user.put("passwordHash", ctx.get(HASH, String.class));
                    ctx.put(USER_ID, ctx.changes().insert(SecurityEntities.USER, user));
                }));

    public static final ProcessDefinition<SetPasswordInput, UserIdOutput, ProcessContext> SET_PASSWORD =
        ProcessDefinition.define("SEC_USER_SET_PASSWORD", 1, SetPasswordInput.class, UserIdOutput.class,
            ProcessContext.class, pb -> pb
                .description("Sets the password of a user.")
                .permissions(SecurityPermissions.USER_PASSWORD)
                .requiresMfa(MfaRequirement.ADMINISTRATION)
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put(USER_ID, input.userId());
                    ctx.put(PASSWORD, input.password());
                    return ctx;
                })
                .outputMapper(ctx -> new UserIdOutput(String.valueOf(ctx.get(USER_ID))))
                .step("Load the user", LoadEntity.by(SecurityEntities.USER_DATASET, USER_ID, USER))
                .step("Hash the password", HashPasswordStep.class, HASH_PASSWORD)
                .compute("Register the new hash", (metadata, ctx) -> {
                    EntityInstance user = ctx.get(USER, EntityInstance.class);
                    ctx.changes().update(SecurityEntities.USER, user.id(), user.version(),
                        Map.of("passwordHash", ctx.get(HASH, String.class)));
                })
                .step("End the user's sessions", RevokeSessionsStep.class,
                    new RevokeSessionsStep.Metadata(USER, RefreshTokenStore.REASON_PASSWORD)));

    public static final ProcessDefinition<UserIdInput, UserIdOutput, ProcessContext> UNLOCK =
        ProcessDefinition.define("SEC_USER_UNLOCK", 1, UserIdInput.class, UserIdOutput.class,
            ProcessContext.class, pb -> pb
                .description("Lifts the lock of a user after failed sign-ins.")
                .permissions(SecurityPermissions.USER_UNLOCK)
                .requiresMfa(MfaRequirement.ADMINISTRATION)
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put(USER_ID, input.userId());
                    return ctx;
                })
                .outputMapper(ctx -> new UserIdOutput(String.valueOf(ctx.get(USER_ID))))
                .step("Load the user", LoadEntity.by(SecurityEntities.USER_DATASET, USER_ID, USER))
                .step("Load the latest login record", QueryEntities.of(SecurityEntities.LOGIN_RECORD_DATASET,
                    ctx -> Rbac.latestLoginRecordOf(ctx.get(USER, EntityInstance.class).id()), LATEST_RECORD))
                .compute("Record the unlock", (metadata, ctx) -> {
                    EntityInstance user = ctx.get(USER, EntityInstance.class);
                    @SuppressWarnings("unchecked")
                    List<EntityInstance> latest = (List<EntityInstance>) ctx.get(LATEST_RECORD);
                    LoginAttemptPolicy.State state = Rbac.state(latest.isEmpty() ? null : latest.getFirst());
                    LoginAttemptPolicy.State next = new LoginAttemptPolicy.State(state.attemptNo() + 1, 0, null);
                    ctx.changes().insert(SecurityEntities.LOGIN_RECORD, SponsorSignInProcess.record(user.id(),
                        user.get("userName"), LoginOutcome.UNLOCKED, next, ctx.opTime(), ctx.request().requestId(),
                        null, Rbac.mfaStep(latest.isEmpty() ? null : latest.getFirst())));
                }));

    @Bean
    ProcessDefinition<CreateUserInput, UserIdOutput, ProcessContext> secUserCreateProcess() {
        return CREATE_USER;
    }

    @Bean
    ProcessDefinition<SetPasswordInput, UserIdOutput, ProcessContext> secUserSetPasswordProcess() {
        return SET_PASSWORD;
    }

    @Bean
    ProcessDefinition<UserIdInput, UserIdOutput, ProcessContext> secUserUnlockProcess() {
        return UNLOCK;
    }
}
