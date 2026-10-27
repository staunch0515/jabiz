package com.jabiz.quizbuks.audience;

import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.identity.CallerChecks;
import com.jabiz.quizbuks.identity.QbAccounts;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.process.steps.HoldLock;
import com.jabiz.runtime.process.steps.QueryEntities;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * {@code QB_PROFILE_ATTRIBUTES_SAVE}: what a taker tells of themself for matching (docs/quizbuks/plans/Q39-audience.md,
 * D-Q39-1, D-Q39-9). The caller is the taker; the answers given are the whole of it: a criterion not given is cleared,
 * so an empty list clears everything. Every answer is optional. Like every QuizBuks write it needs a verified address
 * and refuses a banned account; under the account's lock, as the profile.
 */
public final class ProfileAttributeProcesses {

    public static final String SAVE = "QB_PROFILE_ATTRIBUTES_SAVE";

    static final String INPUT = "input";
    static final String OUTPUT = "output";
    static final String MINE = "mine";

    /** @param answers every criterion the taker tells; the others are cleared */
    public record AttributesInput(@NotNull @Size(max = 20) List<AudienceCriteria.@Valid @NotNull AnswerInput> answers) {}

    /** @param answered how many criteria the profile now tells */
    public record AttributesOutput(int answered) {}

    public static ProcessDefinition<AttributesInput, AttributesOutput, ProcessContext> save() {
        return ProcessDefinition.define(SAVE, 1, AttributesInput.class, AttributesOutput.class, ProcessContext.class,
            pb -> {
                pb.description("Saves what the caller tells of themself for matching: each criterion is optional.")
                    .permissions(QbPermissions.ME)
                    .requiresVerifiedEmail()
                    .contextFactory((start, input) -> {
                        ProcessContext ctx = new ProcessContext(start);
                        ctx.put(INPUT, input);
                        return ctx;
                    })
                    .outputMapper(ctx -> ctx.get(OUTPUT, AttributesOutput.class))
                    .step("Lock the account", HoldLock.<ProcessContext>exclusive(
                        ctx -> QbAccounts.lockName(ctx.request().actorId())));
                CallerChecks.refuseBanned(pb);
                pb.step("Load what the caller told", QueryEntities.<ProcessContext>of(QbAudience.ATTRIBUTE_DATASET,
                        ctx -> EntityQuery.builder().where(new QueryPredicate.Eq("userId", ctx.request().actorId()))
                            .limit(QbAudience.MAX_ROWS).build(), MINE))
                    .compute("Save the answers", (metadata, ctx) -> save(ctx));
            });
    }

    static void save(ProcessContext ctx) {
        AttributesInput input = ctx.get(INPUT, AttributesInput.class);
        int year = ctx.opTime().atZone(ZoneOffset.UTC).getYear();
        AudienceCriteria.answerProblems(input.answers(), year).forEach(ctx::reject);
        if (ctx.hasViolations()) {
            return;
        }
        String userId = ctx.request().actorId();
        // The rows the answers make, keyed as the table's unique key (an option, or the criterion's one number).
        Map<String, Map<String, Object>> wanted = new LinkedHashMap<>();
        int answered = 0;
        for (AudienceCriteria.AnswerInput answer : input.answers()) {
            List<String> options = answer.options() == null ? List.of() : answer.options();
            if (!options.isEmpty()) {
                answered++;
                for (String option : new HashSet<>(options)) {
                    wanted.put(answer.key() + "|" + option, row(userId, answer.key(), option, null));
                }
            } else if (answer.number() != null) {
                answered++;
                wanted.put(answer.key() + "|#" + answer.number(), row(userId, answer.key(), null, answer.number()));
            }
        }
        Set<String> kept = new HashSet<>();
        for (EntityInstance row : rows(ctx)) {
            String key = keyOf(row);
            if (wanted.containsKey(key)) {
                kept.add(key);
            } else {
                ctx.changes().delete(QbAudience.ATTRIBUTE, row.id(), row.version());
            }
        }
        wanted.forEach((key, values) -> {
            if (!kept.contains(key)) {
                ctx.changes().insert(QbAudience.ATTRIBUTE, values);
            }
        });
        ctx.put(OUTPUT, new AttributesOutput(answered));
    }

    private static Map<String, Object> row(String userId, String key, String option, Integer number) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("userId", userId);
        values.put("criterionKey", key);
        values.put("valueCode", option);
        values.put("valueNumber", number == null ? null : BigDecimal.valueOf(number));
        return values;
    }

    private static String keyOf(EntityInstance row) {
        Object code = row.get("valueCode");
        Object number = row.get("valueNumber");
        return row.get("criterionKey") + "|" + (code != null ? code
            : "#" + (number instanceof BigDecimal d ? d.intValueExact() : Objects.toString(number)));
    }

    @SuppressWarnings("unchecked")
    static List<EntityInstance> rows(ProcessContext ctx) {
        Object found = ctx.get(MINE);
        return found instanceof List<?> list ? new ArrayList<>((List<EntityInstance>) list) : List.of();
    }

    private ProfileAttributeProcesses() {}
}
