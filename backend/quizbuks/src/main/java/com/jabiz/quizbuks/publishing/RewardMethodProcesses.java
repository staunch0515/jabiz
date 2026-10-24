package com.jabiz.quizbuks.publishing;

import com.jabiz.entity.Violation;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.identity.QbAccounts;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.process.steps.HoldLock;
import com.jabiz.runtime.process.steps.QueryEntities;
import com.jabiz.runtime.security.SecurityEntities;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.jabiz.quizbuks.publishing.PublicationEditing.INPUT;
import static com.jabiz.quizbuks.publishing.PublicationEditing.OUTPUT;
import static com.jabiz.quizbuks.publishing.PublicationEditing.first;
import static com.jabiz.quizbuks.publishing.PublicationEditing.number;

/**
 * Which reward methods a sponsor may publish with (Q22, docs/quizbuks/plans/Q22-reward-methods.md §1):
 * {@code QB_SPONSOR_REWARD_METHODS_SET}, an administrator's, replaces the sponsor's list with the given one and the
 * reason. Written under the sponsor's account lock (D-Q2-10), as every write about a user is. Taking a method away
 * leaves the sponsor's running publications as they are: the administrator pauses them if they should stop.
 */
public final class RewardMethodProcesses {

    public static final String SET = "QB_SPONSOR_REWARD_METHODS_SET";

    static final String SETTING = "setting";
    static final String SPONSOR = "sponsor";

    /**
     * @param userId  the sponsor (a platform user)
     * @param methods the methods allowed from now on, at least one ({@link RewardMethod#ALL})
     * @param note    why, for the record
     */
    public record SetInput(@NotBlank @Size(max = 128) String userId, @NotNull @Size(min = 1, max = 3)
        List<@NotBlank String> methods, @Size(max = PublishingLimits.REJECT_REASON_LENGTH) String note) {}

    public record SetOutput(String userId, List<String> methods, long revision) {}

    public static ProcessDefinition<SetInput, SetOutput, ProcessContext> set() {
        return ProcessDefinition.define(SET, 1, SetInput.class, SetOutput.class, ProcessContext.class, pb -> pb
            .description("Sets the reward methods a sponsor may publish with.")
            .permissions(QbPermissions.ADMIN_REWARD_METHODS)
            .requiresVerifiedEmail()
            .actsOn(SecurityEntities.USER, "userId")
            .contextFactory((start, input) -> {
                ProcessContext ctx = new ProcessContext(start);
                ctx.put(INPUT, input);
                return ctx;
            })
            .outputMapper(ctx -> ctx.get(OUTPUT, SetOutput.class))
            .step("Lock the sponsor's account", HoldLock.<ProcessContext>exclusive(
                ctx -> QbAccounts.lockName(ctx.get(INPUT, SetInput.class).userId())))
            .step("Load the sponsor's onboarding", QueryEntities.<ProcessContext>of(
                QbAccounts.defaultDataset(QbAccounts.SPONSOR), ctx -> EntityQuery.builder()
                    .where(new QueryPredicate.Eq("userId", ctx.get(INPUT, SetInput.class).userId())).limit(1).build(),
                SPONSOR))
            .step("Load the setting", QueryEntities.<ProcessContext>of(
                QbPublications.defaultDataset(QbPublications.SPONSOR_METHODS), ctx -> EntityQuery.builder()
                    .where(new QueryPredicate.Eq("userId", ctx.get(INPUT, SetInput.class).userId())).limit(1).build(),
                SETTING))
            .compute("Set the methods", (metadata, ctx) -> set(ctx)));
    }

    static void set(ProcessContext ctx) {
        SetInput input = ctx.get(INPUT, SetInput.class);
        if (first(ctx, SPONSOR) == null) {
            // Only someone who applied as a sponsor has methods to set: the unknown and the never-applied look alike.
            throw new com.jabiz.runtime.EntityNotFoundException(QbAccounts.SPONSOR + " of user " + input.userId()
                + " not found");
        }
        List<String> unknown = input.methods().stream().filter(method -> !RewardMethod.ALL.contains(method)).toList();
        if (!unknown.isEmpty()) {
            ctx.reject(new Violation("methods", PublishingCodes.REWARD_METHODS_FORMAT, "Unknown reward methods: "
                + String.join(", ", unknown), Map.of("methods", String.join(",", unknown))));
            return;
        }
        String methods = RewardMethod.format(input.methods());
        EntityInstance setting = first(ctx, SETTING);
        long revision = setting == null ? 1 : number(setting.get("revision")) + 1;
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("methods", methods);
        values.put("note", input.note() == null || input.note().isBlank() ? null : input.note().strip());
        values.put("setBy", ctx.request().actorId());
        values.put("setAt", ctx.opTime());
        values.put("revision", BigDecimal.valueOf(revision));
        if (setting == null) {
            values.put("userId", input.userId());
            ctx.changes().insert(QbPublications.SPONSOR_METHODS, values);
        } else {
            ctx.changes().update(QbPublications.SPONSOR_METHODS, setting.id(), setting.version(), values);
        }
        ctx.put(OUTPUT, new SetOutput(input.userId(), RewardMethod.parse(methods), revision));
    }

    private RewardMethodProcesses() {}
}
