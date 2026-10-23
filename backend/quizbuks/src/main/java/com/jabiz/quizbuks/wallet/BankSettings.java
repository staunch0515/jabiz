package com.jabiz.quizbuks.wallet;

import com.jabiz.entity.Violation;
import com.jabiz.param.ParamValues;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.runtime.BusinessRuleViolationException;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.approval.ApprovalEntities;
import com.jabiz.runtime.approval.ControlChanges;
import com.jabiz.runtime.param.ParamEntities;
import com.jabiz.runtime.process.steps.CallProcess;
import com.jabiz.runtime.process.steps.HoldLock;
import com.jabiz.runtime.process.steps.LoadParams;
import com.jabiz.runtime.process.steps.QueryEntities;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The bank top-up's settings (docs/quizbuks/plans/Q18-bank-settings-credit.md, Q18a): the platform's receiving account
 * and the limits, seven controlled parameters (platform decision D40). {@value #PROPOSE} proposes, as one administrator
 * and in one step, a controlled change for each value that differs from the one in force (declaring a parameter that
 * does not exist yet with its kind, as {@code QB_SETUP} does); another administrator publishes them, each with
 * {@code CONTROL_CHANGE_PUBLISH}, on the same page. The proposer's own open proposals of these keys (the setup's
 * first values among them) are withdrawn first: the new ones take their place. Another administrator's open proposal
 * of one of them is refused until it is published or withdrawn, so that two proposals never race for one value.
 */
public final class BankSettings {

    public static final String PROPOSE = "QB_BANK_SETTINGS_PROPOSE";
    public static final String PUBLISH = "QB_BANK_SETTINGS_PUBLISH";

    /** Proposing and publishing take turns (review of Q18a): two proposals never race past each other's check. */
    static final String LOCK = "qb.bank-settings";

    /** The most open parameter proposals read at once; more is refused rather than half read. */
    static final int MAX_OPEN = 500;

    /** The seven parameters, in the order the page shows them. */
    public static final List<String> KEYS = List.of(QbParams.BANK_RECEIVING_BANK, QbParams.BANK_RECEIVING_BRANCH,
        QbParams.BANK_RECEIVING_TYPE, QbParams.BANK_RECEIVING_NUMBER, QbParams.BANK_RECEIVING_HOLDER,
        QbParams.TOPUP_BANK_MIN, QbParams.TOPUP_BANK_MAX);

    /** The values as the form sends them: every part, the limits in whole Kudos, and why. */
    public record ProposeInput(
        @NotBlank @Size(max = 100) String bank,
        @NotBlank @Size(max = 100) String branch,
        @NotBlank @Size(max = 20) String accountType,
        @NotBlank @Size(max = 20) String accountNumber,
        @NotBlank @Size(max = 100) String accountHolder,
        @NotNull @DecimalMin("1") @Digits(integer = 15, fraction = 0) BigDecimal min,
        @NotNull @DecimalMin("1") @Digits(integer = 15, fraction = 0) BigDecimal max,
        @NotBlank @Size(max = 500) String reason) {}

    /** The changes proposed, for another administrator to publish, and the keys they change. */
    public record ProposeOutput(List<String> changeIds, List<String> keys) {}

    /** Publishes every open proposal of the seven another administrator made: no input. */
    public record PublishInput() {}

    /** The changes published and the keys they set. */
    public record PublishOutput(List<String> changeIds, List<String> keys) {}

    static final String PARAMS = "params";
    static final String OPEN = "openChanges";
    static final String PROPOSALS = "proposals";
    static final String PROPOSED = "proposed";
    static final String WITHDRAWALS = "withdrawals";
    static final String INPUT = "input";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private BankSettings() {}

    public static ProcessDefinition<ProposeInput, ProposeOutput, ProcessContext> propose() {
        return ProcessDefinition.define(PROPOSE, 1, ProposeInput.class, ProposeOutput.class, ProcessContext.class,
            pb -> pb
                .description("Proposes the bank top-up's receiving account and limits as controlled changes.")
                // The proposer of controlled changes (the super administrator); publishing is another's.
                .permissions("control.propose")
                // Where sponsors send money: with a recent second factor, as other administration.
                .requiresMfa(com.jabiz.security.MfaRequirement.ADMINISTRATION)
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put(INPUT, input);
                    return ctx;
                })
                .outputMapper(BankSettings::output)
                .step("Take turns", HoldLock.<ProcessContext>exclusive(ctx -> LOCK))
                .step("Load the settings in force", LoadParams.<ProcessContext>optional(ProcessContext::opTime,
                    PARAMS, KEYS.toArray(String[]::new)))
                .step("Load the open proposals", openProposals())
                .compute("Work out the changes", (metadata, ctx) -> changes(ctx))
                .step("Withdraw one's own open proposals", CallProcess.forEach(ControlChanges.WITHDRAW, 1,
                    BankSettings::withdrawals, null))
                .step("Propose them", CallProcess.forEach(ControlChanges.PROPOSE, 1,
                    BankSettings::proposals, PROPOSED)));
    }

    /**
     * Publishes, in one transaction, every open proposal of the seven another administrator made (review of Q18a: one
     * by one, a failure half way would leave sponsors an account half old and half new). Refused when the limits that
     * would result are the wrong way round, or when there is nothing to publish. Each publication is the platform's
     * {@code CONTROL_CHANGE_PUBLISH}, which refuses the proposer.
     */
    public static ProcessDefinition<PublishInput, PublishOutput, ProcessContext> publish() {
        return ProcessDefinition.define(PUBLISH, 1, PublishInput.class, PublishOutput.class, ProcessContext.class,
            pb -> pb
                .description("Publishes the bank top-up's proposed settings together.")
                .permissions("control.publish")
                .requiresMfa(com.jabiz.security.MfaRequirement.ADMINISTRATION)
                .contextFactory((start, input) -> new ProcessContext(start))
                .outputMapper(ctx -> new PublishOutput(List.copyOf(ctx.get("publishedIds", List.class)),
                    List.copyOf(ctx.get("keys", List.class))))
                .step("Take turns", HoldLock.<ProcessContext>exclusive(ctx -> LOCK))
                .step("Load the settings in force", LoadParams.<ProcessContext>optional(ProcessContext::opTime,
                    PARAMS, KEYS.toArray(String[]::new)))
                .step("Load the open proposals", openProposals())
                .compute("Work out what to publish", (metadata, ctx) -> toPublish(ctx))
                .step("Publish them", CallProcess.forEach(ControlChanges.PUBLISH, 1,
                    ctx -> {
                        @SuppressWarnings("unchecked")
                        List<Object> inputs = (List<Object>) ctx.get("publications", List.class);
                        return inputs;
                    }, null)));
    }

    private static com.jabiz.process.StepSpec<?, ProcessContext> openProposals() {
        return QueryEntities.<ProcessContext>of(ApprovalEntities.CONTROL_CHANGE_DATASET, ctx -> EntityQuery.builder()
            .where(new QueryPredicate.And(List.of(
                new QueryPredicate.Eq("targetEntity", ParamEntities.ENTITY),
                new QueryPredicate.Eq("status", "PROPOSED"))))
            .limit(MAX_OPEN).build(), OPEN);
    }

    /** The open proposals as read; refused when there may be more than were read. */
    @SuppressWarnings("unchecked")
    private static List<Open> readOpen(ProcessContext ctx) {
        List<EntityInstance> rows = (List<EntityInstance>) ctx.get(OPEN, List.class);
        if (rows.size() >= MAX_OPEN) {
            throw new BusinessRuleViolationException(List.of(new Violation(null, WalletCodes.BANK_SETTINGS_OPEN,
                "Too many open parameter proposals to read at once", Map.of("keys", "…"))));
        }
        return open(rows);
    }

    static void toPublish(ProcessContext ctx) {
        String actor = ctx.request().actorId();
        List<Open> others = readOpen(ctx).stream().filter(change -> !change.proposedBy().equals(actor)).toList();
        if (others.isEmpty()) {
            throw new BusinessRuleViolationException(List.of(new Violation(null,
                WalletCodes.BANK_SETTINGS_NOTHING, "No proposal of another administrator is open", Map.of())));
        }
        // The limits as they would be: in force, then each proposal over them.
        ParamValues current = ctx.get(PARAMS, ParamValues.class);
        Map<String, String> result = new LinkedHashMap<>();
        for (String key : List.of(QbParams.TOPUP_BANK_MIN, QbParams.TOPUP_BANK_MAX)) {
            result.put(key, text(current.values().get(key)));
        }
        others.stream().filter(change -> result.containsKey(change.key()))
            .forEach(change -> result.put(change.key(), change.value()));
        BigDecimal min = number(result.get(QbParams.TOPUP_BANK_MIN));
        BigDecimal max = number(result.get(QbParams.TOPUP_BANK_MAX));
        if (min != null && max != null && min.compareTo(max) > 0) {
            throw new BusinessRuleViolationException(List.of(new Violation(null, WalletCodes.BANK_SETTINGS_RANGE,
                "The largest top-up would be below the smallest", Map.of())));
        }
        ctx.put("publications", others.stream().map(change -> (Object) new ControlChanges.ChangeInput(
            java.util.UUID.fromString(change.changeId()))).toList());
        ctx.put("publishedIds", others.stream().map(Open::changeId).toList());
        ctx.put("keys", others.stream().map(Open::key).distinct().toList());
    }

    private static BigDecimal number(String text) {
        try {
            return text == null || text.isBlank() ? null : new BigDecimal(text.strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Object> proposals(ProcessContext ctx) {
        return (List<Object>) ctx.get(PROPOSALS, List.class);
    }

    @SuppressWarnings("unchecked")
    private static List<Object> withdrawals(ProcessContext ctx) {
        return (List<Object>) ctx.get(WITHDRAWALS, List.class);
    }

    /** An open proposal of one of the seven keys, with the value it proposes. */
    record Open(String key, String changeId, String proposedBy, String value) {}

    /** Each value as a parameter stores it (text as typed, trimmed; Kudos as a whole number). */
    static Map<String, String> values(ProposeInput input) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put(QbParams.BANK_RECEIVING_BANK, input.bank().strip());
        values.put(QbParams.BANK_RECEIVING_BRANCH, input.branch().strip());
        values.put(QbParams.BANK_RECEIVING_TYPE, input.accountType().strip());
        values.put(QbParams.BANK_RECEIVING_NUMBER, input.accountNumber().strip());
        values.put(QbParams.BANK_RECEIVING_HOLDER, input.accountHolder().strip());
        values.put(QbParams.TOPUP_BANK_MIN, input.min().toBigIntegerExact().toString());
        values.put(QbParams.TOPUP_BANK_MAX, input.max().toBigIntegerExact().toString());
        return values;
    }

    /** The open proposals among the seven keys. */
    static List<Open> open(List<EntityInstance> changes) {
        List<Open> open = new ArrayList<>();
        for (EntityInstance change : changes) {
            Object text = change.get("changeValues");
            if (text == null) {
                continue;
            }
            Map<?, ?> values;
            try {
                values = JSON.readValue(String.valueOf(text), Map.class);
            } catch (RuntimeException e) {
                // Not this page's to judge: a change it cannot read is no proposal of the seven.
                continue;
            }
            Object key = values.get(ParamEntities.KEY);
            if (key != null && KEYS.contains(key.toString())) {
                Object proposer = change.get("proposedBy");
                Object value = values.get(ParamEntities.VALUE);
                open.add(new Open(key.toString(), String.valueOf(change.id()), String.valueOf(proposer),
                    value == null ? null : value.toString()));
            }
        }
        return open;
    }

    static void changes(ProcessContext ctx) {
        ProposeInput input = ctx.get(INPUT, ProposeInput.class);
        List<Violation> problems = new ArrayList<>();
        if (input.min().compareTo(input.max()) > 0) {
            problems.add(new Violation("max", WalletCodes.BANK_SETTINGS_RANGE,
                "The largest top-up is below the smallest", Map.of()));
        }
        List<Open> open = readOpen(ctx);
        String actor = ctx.request().actorId();
        Set<String> others = new LinkedHashSet<>();
        open.stream().filter(change -> !change.proposedBy().equals(actor)).forEach(change -> others.add(change.key()));
        if (!others.isEmpty()) {
            problems.add(new Violation(null, WalletCodes.BANK_SETTINGS_OPEN, "Another administrator's proposals are "
                + "open: " + others, Map.of("keys", String.join(", ", others))));
        }
        if (!problems.isEmpty()) {
            throw new BusinessRuleViolationException(problems);
        }
        ParamValues current = ctx.get(PARAMS, ParamValues.class);
        List<ControlChanges.ProposeInput> proposals = new ArrayList<>();
        List<String> keys = new ArrayList<>();
        values(input).forEach((key, value) -> {
            if (current.has(key) && Objects.equals(text(current.values().get(key)), value)) {
                return;
            }
            Map<String, Object> values = new LinkedHashMap<>();
            values.put(ParamEntities.KEY, key);
            values.put(ParamEntities.VALUE, value);
            if (!current.has(key)) {
                // Not declared yet (the setup's proposal never published): declared with its kind, as setup would.
                QbParams.Param param = QbParams.ALL.stream().filter(p -> p.key().equals(key)).findFirst().orElseThrow();
                values.put(ParamEntities.KIND, param.valueKind());
                values.put(ParamEntities.DESCRIPTION, param.description());
            }
            proposals.add(new ControlChanges.ProposeInput(ParamEntities.ENTITY, null, false, values, null,
                input.reason().strip()));
            keys.add(key);
        });
        if (proposals.isEmpty()) {
            throw new BusinessRuleViolationException(List.of(new Violation(null, WalletCodes.BANK_SETTINGS_UNCHANGED,
                "Every value is the one in force", Map.of())));
        }
        ctx.put(PROPOSALS, proposals);
        ctx.put(WITHDRAWALS, open.stream().map(change -> (Object) new ControlChanges.ChangeInput(
            java.util.UUID.fromString(change.changeId()))).toList());
        ctx.put("keys", keys);
    }

    private static String text(Object value) {
        return value instanceof BigDecimal number ? number.toPlainString() : value == null ? null : value.toString();
    }

    @SuppressWarnings("unchecked")
    private static ProposeOutput output(ProcessContext ctx) {
        List<Object> made = ctx.get(PROPOSED, List.class);
        List<String> ids = made == null ? List.of() : made.stream()
            .map(change -> ((ControlChanges.ChangeOutput) change).changeId()).toList();
        List<String> keys = ctx.get("keys", List.class);
        return new ProposeOutput(ids, keys == null ? List.of() : List.copyOf(keys));
    }
}
