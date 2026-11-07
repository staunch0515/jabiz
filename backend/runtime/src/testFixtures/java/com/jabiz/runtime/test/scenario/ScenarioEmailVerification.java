package com.jabiz.runtime.test.scenario;

import com.jabiz.entity.Violation;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.query.QueryPredicate;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.process.steps.QueryEntities;
import com.jabiz.runtime.security.Rbac;
import com.jabiz.runtime.security.SecurityEntities;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The process behind the replay step {@code verifyEmail} (docs/design/07-quality.md section 3.1). A replay cannot follow
 * the mailed verification link, so this writes what proving the address writes ({@code verifiedEmail} = the current
 * address, {@code emailVerifiedAt} = the operation time from the clock), through the process's change set like the
 * platform's own verification: the user gets a new version and an audit record as for any other write. It is no bean
 * and is never registered, so no application can start it; only {@link ScenarioRunner} runs it, as the system.
 */
final class ScenarioEmailVerification {

    /** The name of its operations. */
    static final String PROCESS = "SCENARIO_EMAIL_VERIFY";

    record Input(String userId) {}

    record Output(String userId, String verifiedEmail) {}

    private static final String INPUT = "input";
    private static final String USERS = "users";
    private static final String VERIFIED = "verified";

    static final ProcessDefinition<Input, Output, ProcessContext> DEFINITION =
        ProcessDefinition.define(PROCESS, 1, Input.class, Output.class, ProcessContext.class, pb -> pb
            .internal()
            .contextFactory((start, input) -> {
                ProcessContext ctx = new ProcessContext(start);
                ctx.put(INPUT, input);
                return ctx;
            })
            .outputMapper(ctx -> new Output(ctx.get(INPUT, Input.class).userId(), ctx.get(VERIFIED, String.class)))
            .step("Load the user", QueryEntities.<ProcessContext>of(SecurityEntities.USER_DATASET,
                ctx -> Rbac.all(new QueryPredicate.Eq("userId",
                    UUID.fromString(ctx.get(INPUT, Input.class).userId())), "userId"), USERS))
            .compute("Mark the address verified", (metadata, ctx) -> {
                @SuppressWarnings("unchecked")
                List<EntityInstance> users = (List<EntityInstance>) ctx.get(USERS);
                String userId = ctx.get(INPUT, Input.class).userId();
                if (users == null || users.isEmpty()) {
                    ctx.reject(new Violation("user", "NOT_FOUND", "No user " + userId));
                    return;
                }
                EntityInstance user = users.getFirst();
                Object email = user.get("email");
                if (email == null || email.toString().isBlank()) {
                    ctx.reject(new Violation("user", "REQUIRED", "User " + userId + " has no e-mail address"));
                    return;
                }
                Map<String, Object> changes = new LinkedHashMap<>();
                changes.put("verifiedEmail", email);
                changes.put("emailVerifiedAt", ctx.opTime());
                ctx.changes().update(SecurityEntities.USER, user.id(), user.version(), changes);
                ctx.put(VERIFIED, email.toString());
            }));

    private ScenarioEmailVerification() {}
}
