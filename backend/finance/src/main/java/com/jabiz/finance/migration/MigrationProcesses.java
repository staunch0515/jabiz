package com.jabiz.finance.migration;

import com.jabiz.entity.Violation;
import com.jabiz.finance.FinancePermissions;
import com.jabiz.finance.gl.GlEntities;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.process.steps.QueryEntities;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Recorded decisions of the migration (FIN-DI-003): {@code FIN_MIGRATION_DECIDE} records how a legacy value is read,
 * with the reason, who decided and when; deciding again on the same value replaces the decision, the earlier one
 * staying in the history. The imports of the migration apply the decisions (an account decision maps a legacy
 * account code to one of the chart), and the migration report lists them.
 */
public final class MigrationProcesses {

    public static final String DECIDE = "FIN_MIGRATION_DECIDE";

    public static final String INVALID_KIND = "FIN_MIGRATION_INVALID_KIND";
    public static final String UNKNOWN_ACCOUNT = "FIN_MIGRATION_UNKNOWN_ACCOUNT";
    public static final String LEGACY_IS_ACCOUNT = "FIN_MIGRATION_LEGACY_IS_ACCOUNT";

    /**
     * @param kind          {@code ACCOUNT}
     * @param legacyValue   the value as the legacy data has it
     * @param decidedValue  what it is read as: for {@code ACCOUNT} an account code of the chart
     */
    public record DecisionInput(@NotBlank String kind, @NotBlank @Size(max = 100) String legacyValue,
        @NotBlank @Size(max = 100) String decidedValue, @NotBlank @Size(max = 500) String reason) {}

    /** @param changed false when the same decision stood already */
    public record DecisionOutput(String decisionId, String kind, String legacyValue, String decidedValue,
        boolean changed) {}

    static final String INPUT = "input";
    static final String OUTPUT = "output";
    static final String DECISIONS = "decisions";
    static final String ACCOUNTS = "accounts";

    public static final ProcessDefinition<DecisionInput, DecisionOutput, ProcessContext> DECIDE_PROCESS =
        ProcessDefinition.define(DECIDE, 1, DecisionInput.class, DecisionOutput.class, ProcessContext.class, pb -> pb
            .description("Records how the migration reads a legacy value, with the reason.")
            .permissions(FinancePermissions.MIGRATION)
            .contextFactory((start, input) -> {
                ProcessContext ctx = new ProcessContext(start);
                ctx.put(INPUT, input);
                return ctx;
            })
            .outputMapper(ctx -> ctx.get(OUTPUT, DecisionOutput.class))
            .step("Load the decision on the value", QueryEntities.of(MigrationEntities.DECISION_DATASET, ctx -> {
                DecisionInput input = ctx.get(INPUT, DecisionInput.class);
                return EntityQuery.builder().where(new QueryPredicate.And(List.of(
                    new QueryPredicate.Eq("kind", kind(input)),
                    new QueryPredicate.Eq("legacyValue", input.legacyValue().trim())))).limit(1).build();
            }, DECISIONS))
            .step("Load the accounts it names", QueryEntities.of(GlEntities.ACCOUNT_DATASET, ctx -> {
                DecisionInput input = ctx.get(INPUT, DecisionInput.class);
                return accountsByCode(List.of(input.legacyValue().trim(), input.decidedValue().trim()));
            }, ACCOUNTS))
            .compute("Record the decision", (metadata, ctx) -> decide(ctx)));

    /** The finance accounts with these codes. */
    public static EntityQuery accountsByCode(Collection<String> codes) {
        return EntityQuery.builder().where(new QueryPredicate.In("accountCode", new ArrayList<>(codes)))
            .limit(Math.max(1, codes.size())).build();
    }

    /** The account decisions on these legacy codes. */
    public static EntityQuery accountDecisions(Collection<String> legacyCodes) {
        return EntityQuery.builder().where(new QueryPredicate.And(List.of(
                new QueryPredicate.Eq("kind", MigrationEntities.ACCOUNT),
                new QueryPredicate.In("legacyValue", new ArrayList<>(legacyCodes)))))
            .limit(Math.max(1, legacyCodes.size())).build();
    }

    /** Legacy code to decided code, from the account decisions loaded. */
    public static Map<String, String> accountMap(List<EntityInstance> decisions) {
        Map<String, String> map = new LinkedHashMap<>();
        for (EntityInstance decision : decisions) {
            if (MigrationEntities.ACCOUNT.equals(decision.get("kind"))) {
                map.put(decision.get("legacyValue"), decision.get("decidedValue"));
            }
        }
        return map;
    }

    static void decide(ProcessContext ctx) {
        DecisionInput input = ctx.get(INPUT, DecisionInput.class);
        String kind = kind(input);
        if (!MigrationEntities.DECISION_KIND_VALUES.contains(kind)) {
            ctx.reject(new Violation("kind", INVALID_KIND, "kind must be one of "
                + MigrationEntities.DECISION_KIND_VALUES, Map.of("value", input.kind())));
            return;
        }
        String legacy = input.legacyValue().trim();
        String decided = input.decidedValue().trim();
        List<EntityInstance> accounts = list(ctx, ACCOUNTS);
        if (accounts.stream().noneMatch(a -> decided.equals(a.get("accountCode")))) {
            ctx.reject(new Violation("decidedValue", UNKNOWN_ACCOUNT, "There is no account " + decided,
                Map.of("accountCode", decided)));
        }
        // A code of the chart means that account in every file; mapping it would make the same code mean two things.
        if (accounts.stream().anyMatch(a -> legacy.equals(a.get("accountCode")))) {
            ctx.reject(new Violation("legacyValue", LEGACY_IS_ACCOUNT, legacy + " is an account of the chart: "
                + "it is read as itself", Map.of("accountCode", legacy)));
        }
        if (ctx.hasViolations()) {
            return;
        }
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("decidedValue", decided);
        state.put("reason", input.reason().trim());
        state.put("decidedBy", ctx.request().actorId());
        state.put("decidedAt", ctx.opTime());
        List<EntityInstance> found = list(ctx, DECISIONS);
        if (found.isEmpty()) {
            state.put("kind", kind);
            state.put("legacyValue", legacy);
            Object id = ctx.changes().insert(MigrationEntities.DECISION, state);
            ctx.put(OUTPUT, new DecisionOutput(String.valueOf(id), kind, legacy, decided, true));
            return;
        }
        EntityInstance decision = found.getFirst();
        boolean changed = !decided.equals(decision.get("decidedValue")) || !input.reason().trim()
            .equals(decision.get("reason"));
        if (changed) {
            ctx.changes().update(MigrationEntities.DECISION, decision.id(), decision.version(), state);
        }
        ctx.put(OUTPUT, new DecisionOutput(String.valueOf(decision.id()), kind, legacy, decided, changed));
    }

    private static String kind(DecisionInput input) {
        return input.kind().trim().toUpperCase(Locale.ROOT);
    }

    @SuppressWarnings("unchecked")
    static List<EntityInstance> list(ProcessContext ctx, String key) {
        List<EntityInstance> found = (List<EntityInstance>) ctx.get(key);
        return found == null ? List.of() : found;
    }

    private MigrationProcesses() {}
}
