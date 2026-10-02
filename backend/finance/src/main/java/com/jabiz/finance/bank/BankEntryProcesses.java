package com.jabiz.finance.bank;

import com.jabiz.entity.Violation;
import com.jabiz.finance.FinancePermissions;
import com.jabiz.finance.gl.GlEntities;
import com.jabiz.finance.gl.JournalProcesses;
import com.jabiz.finance.gl.SubledgerPosting;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.numbering.AssignNumber;
import com.jabiz.runtime.process.steps.CallProcess;
import com.jabiz.runtime.process.steps.QueryEntities;
import com.jabiz.runtime.process.steps.RunTemplate;
import com.jabiz.runtime.process.steps.SaveChanges;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static com.jabiz.finance.bank.BankAccountProcesses.code;
import static com.jabiz.finance.bank.BankAccountProcesses.list;
import static com.jabiz.finance.bank.BankAccountProcesses.trim;

/**
 * Entries made from statement lines the books do not have yet, such as the bank's fees and interest (FIN-BK-005):
 * <ul>
 *   <li>{@code FIN_BANK_ENTRY_RULE_SAVE}: the controller's rules: words of the line's description, money in or out,
 *       the account the entry takes (no control account) and the prefix of its number.</li>
 *   <li>{@code FIN_BANK_ENTRY_FROM_LINE}: one open line becomes an entry, by the rule named or the first whose words
 *       it has, or by an account given (which only who keeps the rules may: the rules are the controller's say on
 *       where bank entries go): numbered without gaps per rule prefix and month ({@code BANK-FEE-2601}, a second one
 *       in that month {@code BANK-FEE-2601-2}), posted on the line's day against the bank account's cash account
 *       (source BANK), and matched to the line at once.</li>
 * </ul>
 */
public final class BankEntryProcesses {

    public static final String RULE_SAVE = "FIN_BANK_ENTRY_RULE_SAVE";
    public static final String FROM_LINE = "FIN_BANK_ENTRY_FROM_LINE";

    public static final String DEFAULT_PREFIX = "BANK";
    public static final String NUMBERS = "fin.bank.entry";
    public static final String FREE_ACCOUNT = "FIN_BANK_ENTRY_FREE_ACCOUNT";

    public static final String WRONG_ACCOUNT = "FIN_BANK_ENTRY_WRONG_ACCOUNT";
    public static final String WRONG_RULE = "FIN_BANK_ENTRY_RULE";
    public static final String NO_RULE = "FIN_BANK_ENTRY_NO_RULE";
    public static final String NOT_OPEN = MatchProcesses.NOT_OPEN;

    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyMM");

    /**
     * @param keywords  words of the description, separated by commas; a line with any of them takes the rule
     * @param direction {@code PAYMENT} (money out), {@code DEPOSIT} (money in) or {@code ANY}
     */
    public record RuleInput(@NotBlank @Size(max = 20) @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9_-]*") String ruleCode,
        @NotBlank @Size(max = 200) String keywords, @NotBlank @Size(max = 10) String direction,
        @NotBlank @Size(max = 20) String account,
        @NotBlank @Size(max = 10) @Pattern(regexp = "[A-Za-z][A-Za-z0-9-]*") String documentPrefix,
        @Size(max = 200) String description, Boolean active) {}

    public record RuleOutput(String ruleId, boolean created) {}

    /**
     * @param ruleCode the rule to apply; the first whose words the line has when absent
     * @param account  the account, instead of the rule's
     */
    public record EntryInput(@NotNull UUID lineId, @Size(max = 20) String ruleCode, @Size(max = 20) String account,
        @Size(max = 500) String description) {}

    public record EntryOutput(String entryId, String entryNo, String glNo, String matchId) {}

    static final String INPUT = "input";
    static final String OUTPUT = "output";
    static final String RULES = "rules";
    static final String ACCOUNTS = "accounts";
    static final String LINE = "line";
    static final String OPEN_LINES = "openLines";
    static final String BANKS = "banks";
    static final String PLAN = "plan";
    static final String NUMBER = "number";
    static final String ITEMS = "items";
    static final String NEW_ID = "newId";
    static final String SUB_INPUT = "subInput";
    static final String SUB_OUTPUT = "subOutput";

    /** What an entry from a line will be, once its rule and account are known. */
    record Plan(EntityInstance line, EntityInstance bank, String account, String ruleCode, String base,
        String description) {}

    public static final ProcessDefinition<RuleInput, RuleOutput, ProcessContext> RULE_PROCESS =
        ProcessDefinition.define(RULE_SAVE, 1, RuleInput.class, RuleOutput.class, ProcessContext.class, pb -> pb
            .description("Creates or changes a rule that makes statement lines into entries.")
            .permissions(FinancePermissions.BANK_SETTINGS)
            .contextFactory(BankAccountProcesses::withInput)
            .outputMapper(ctx -> ctx.get(OUTPUT, RuleOutput.class))
            .step("Load the rule", QueryEntities.of(MatchEntities.RULE_DATASET, ctx -> BankAccountProcesses.eq(
                "ruleCode", code(ctx.get(INPUT, RuleInput.class).ruleCode())), RULES))
            .step("Load the account", QueryEntities.of(GlEntities.ACCOUNT_DATASET, ctx -> BankAccountProcesses.eq(
                "accountCode", trim(ctx.get(INPUT, RuleInput.class).account())), ACCOUNTS))
            .compute("Save the rule", (metadata, ctx) -> saveRule(ctx)));

    public static final ProcessDefinition<EntryInput, EntryOutput, ProcessContext> ENTRY_PROCESS =
        ProcessDefinition.define(FROM_LINE, 1, EntryInput.class, EntryOutput.class, ProcessContext.class, pb -> pb
            .description("Makes an open statement line into an entry, posts it and matches it to the line.")
            .permissions(FinancePermissions.BANK_RECONCILE)
            .contextFactory(BankAccountProcesses::withInput)
            .outputMapper(ctx -> ctx.get(OUTPUT, EntryOutput.class))
            .step("Load the line", QueryEntities.of(StatementEntities.LINE_DATASET, ctx -> EntityQuery.builder()
                .where(new QueryPredicate.In("lineId", List.of(ctx.get(INPUT, EntryInput.class).lineId())))
                .limit(1).build(), LINE))
            .step("Load the open lines", RunTemplate.of(MatchProcesses.STATEMENT_ITEMS, ctx -> MatchProcesses.params(
                bankOf(ctx), null), OPEN_LINES))
            .step("Load the bank account", QueryEntities.of(BankEntities.BANK_ACCOUNT_DATASET,
                ctx -> BankAccountProcesses.eq("bankCode", bankOf(ctx)), BANKS))
            .step("Load the rules", QueryEntities.of(MatchEntities.RULE_DATASET, ctx -> EntityQuery.builder()
                .where(new QueryPredicate.Eq("active", true)).orderBy("ruleCode", true).limit(200).build(), RULES))
            .step("Load the account", QueryEntities.of(GlEntities.ACCOUNT_DATASET, ctx -> {
                EntryInput input = ctx.get(INPUT, EntryInput.class);
                List<Object> codes = new ArrayList<>();
                if (trim(input.account()) != null) {
                    codes.add(trim(input.account()));
                }
                list(ctx, RULES).forEach(rule -> codes.add(rule.get("account")));
                return EntityQuery.builder().where(new QueryPredicate.In("accountCode", codes))
                    .limit(codes.size() + 1).build();
            }, ACCOUNTS))
            .compute("Choose the rule", (metadata, ctx) -> plan(ctx))
            // One count per prefix and month: the first is the bare prefix and month, as the sample has it.
            .step("Number the entry", AssignNumber.when(ctx -> ctx.contains(PLAN), NUMBERS,
                ctx -> ctx.get(PLAN, Plan.class).base(), NUMBER))
            .compute("Build the entry", (metadata, ctx) -> build(ctx))
            // The ledger checks that the source document exists: the entry is saved before it is booked.
            .step("Save the entry", SaveChanges.now())
            .step("Book it", CallProcess.when(ctx -> ctx.contains(SUB_INPUT), SubledgerPosting.POST, 1,
                ctx -> ctx.get(SUB_INPUT), SUB_OUTPUT))
            .step("Load the line's matches before", QueryEntities.of(MatchEntities.ITEM_DATASET, ctx -> EntityQuery
                .builder().where(new QueryPredicate.In("refId", List.of(String.valueOf(
                    ctx.get(INPUT, EntryInput.class).lineId())))).limit(MatchProcesses.MAX_ITEMS).build(), ITEMS))
            .compute("Match it to the line", (metadata, ctx) -> matchEntry(ctx)));

    static void saveRule(ProcessContext ctx) {
        RuleInput input = ctx.get(INPUT, RuleInput.class);
        String direction = code(input.direction());
        if (!List.of(MatchEntities.PAYMENTS, MatchEntities.DEPOSITS, MatchEntities.EITHER).contains(direction)) {
            ctx.reject(new Violation("direction", WRONG_RULE, "A rule takes PAYMENT, DEPOSIT or ANY lines",
                Map.of("value", String.valueOf(direction))));
        }
        List<String> keywords = keywords(input.keywords());
        if (keywords.isEmpty()) {
            ctx.reject(new Violation("keywords", WRONG_RULE, "A rule has words to look for", Map.of()));
        }
        String account = trim(input.account());
        EntityInstance found = list(ctx, ACCOUNTS).stream().findFirst().orElse(null);
        if (found == null || found.get("controlClass") != null) {
            ctx.reject(new Violation("account", WRONG_ACCOUNT, "An entry from a statement line posts to an account "
                + "that is no control account; " + account + " is not", Map.of("accountCode", account)));
        }
        if (ctx.hasViolations()) {
            return;
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("keywords", String.join(", ", keywords));
        values.put("direction", direction);
        values.put("account", account);
        values.put("documentPrefix", input.documentPrefix().trim().toUpperCase(Locale.ROOT));
        values.put("description", trim(input.description()));
        values.put("active", !Boolean.FALSE.equals(input.active()));
        EntityInstance current = list(ctx, RULES).stream().findFirst().orElse(null);
        if (current == null) {
            values.put("ruleCode", code(input.ruleCode()));
            Object id = ctx.changes().insert(MatchEntities.RULE, values);
            ctx.put(OUTPUT, new RuleOutput(String.valueOf(id), true));
            return;
        }
        Map<String, Object> changes = new LinkedHashMap<>();
        values.forEach((field, value) -> {
            if (!Objects.equals(current.get(field), value)) {
                changes.put(field, value);
            }
        });
        if (!changes.isEmpty()) {
            ctx.changes().update(MatchEntities.RULE, current.id(), current.version(), changes);
        }
        ctx.put(OUTPUT, new RuleOutput(String.valueOf(current.id()), false));
    }

    static void plan(ProcessContext ctx) {
        EntryInput input = ctx.get(INPUT, EntryInput.class);
        EntityInstance line = list(ctx, LINE).stream().findFirst().orElse(null);
        boolean open = line != null && MatchProcesses.rows(ctx, OPEN_LINES).stream()
            .anyMatch(r -> String.valueOf(line.id()).equals(String.valueOf(r.get("lineId"))));
        if (!open) {
            ctx.reject(new Violation("lineId", NOT_OPEN, "Line " + input.lineId() + " is not an open statement line",
                Map.of("lineId", String.valueOf(input.lineId()))));
            return;
        }
        EntityInstance bank = list(ctx, BANKS).stream().findFirst().orElse(null);
        BigDecimal amount = line.get("amount");
        EntityInstance rule = null;
        String ruleCode = code(input.ruleCode());
        if (ruleCode != null) {
            rule = list(ctx, RULES).stream().filter(r -> ruleCode.equals(r.get("ruleCode"))).findFirst().orElse(null);
            if (rule == null) {
                ctx.reject(new Violation("ruleCode", NO_RULE, "There is no active rule " + ruleCode,
                    Map.of("ruleCode", ruleCode)));
                return;
            }
        } else {
            rule = list(ctx, RULES).stream().filter(r -> applies(r, line.get("description"), amount)).findFirst()
                .orElse(null);
        }
        if (trim(input.account()) != null && !ctx.request().permissions().contains(FinancePermissions.BANK_SETTINGS)
            && !ctx.request().permissions().contains("*")) {
            ctx.reject(new Violation("account", FREE_ACCOUNT, "Only who keeps the bank entry rules chooses an "
                + "account; take a rule", Map.of()));
            return;
        }
        String account = trim(input.account()) != null ? trim(input.account()) : rule == null ? null
            : rule.get("account");
        if (account == null) {
            ctx.reject(new Violation("lineId", NO_RULE, "No rule takes the line, and no account is given",
                Map.of("lineId", String.valueOf(input.lineId()))));
            return;
        }
        String chosen = account;
        EntityInstance found = list(ctx, ACCOUNTS).stream().filter(a -> chosen.equals(a.get("accountCode")))
            .findFirst().orElse(null);
        if (found == null || found.get("controlClass") != null || bank == null
            || chosen.equals(bank.get("glAccount"))) {
            ctx.reject(new Violation("account", WRONG_ACCOUNT, "An entry from a statement line posts to an account "
                + "that is no control account; " + chosen + " is not", Map.of("accountCode", chosen)));
            return;
        }
        String prefix = rule == null ? DEFAULT_PREFIX : rule.get("documentPrefix");
        LocalDate day = line.get("valueDate");
        String description = trim(input.description()) != null ? trim(input.description())
            : rule != null && rule.get("description") != null ? rule.get("description") : line.get("description");
        ctx.put(PLAN, new Plan(line, bank, chosen, rule == null ? null : rule.get("ruleCode"),
            prefix + "-" + day.format(MONTH), description == null ? "Bank entry" : description));
    }

    static void build(ProcessContext ctx) {
        if (!ctx.contains(PLAN)) {
            return;
        }
        Plan plan = ctx.get(PLAN, Plan.class);
        String count = ctx.get(NUMBER, String.class);
        String number = "1".equals(count) ? plan.base() : plan.base() + "-" + count;
        BigDecimal amount = plan.line().get("amount");
        LocalDate day = plan.line().get("valueDate");
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("entryNo", number);
        values.put("bankCode", plan.bank().get("bankCode"));
        values.put("lineId", plan.line().id());
        values.put("entryDate", day);
        values.put("account", plan.account());
        values.put("amount", amount);
        values.put("description", cut(plan.description(), 500));
        values.put("ruleCode", plan.ruleCode());
        Object id = ctx.changes().insert(MatchEntities.ENTRY, values);
        ctx.put(NEW_ID, id);
        BigDecimal size = amount.abs();
        String cash = plan.bank().get("glAccount");
        String memo = cut(plan.description(), 200);
        // Money out debits the account (a fee, interest paid); money in credits it (interest earned).
        List<JournalProcesses.LineInput> lines = amount.signum() < 0
            ? List.of(new JournalProcesses.LineInput(plan.account(), size, null, memo, null, null),
                new JournalProcesses.LineInput(cash, null, size, memo, null, null))
            : List.of(new JournalProcesses.LineInput(cash, size, null, memo, null, null),
                new JournalProcesses.LineInput(plan.account(), null, size, memo, null, null));
        ctx.put(SUB_INPUT, new SubledgerPosting.PostInput("BANK", day, cut(number + " " + memo, 500), number,
            MatchEntities.ENTRY, String.valueOf(id), lines, List.of("BANK")));
    }

    static void matchEntry(ProcessContext ctx) {
        if (!ctx.contains(SUB_OUTPUT)) {
            return;
        }
        Plan plan = ctx.get(PLAN, Plan.class);
        SubledgerPosting.PostOutput posted = ctx.get(SUB_OUTPUT, SubledgerPosting.PostOutput.class);
        EntityInstance line = plan.line();
        BigDecimal amount = ((BigDecimal) line.get("amount")).setScale(2);
        String number = ((SubledgerPosting.PostInput) ctx.get(SUB_INPUT)).documentNo();
        Object matchId = MatchProcesses.write(ctx, plan.bank().get("bankCode"),
            List.of(new MatchProcesses.Side(MatchEntities.LINE, String.valueOf(line.id()), line.get("valueDate"),
                amount, MatchProcesses.label(line.get("bankReference"), line.get("description")))),
            List.of(new MatchProcesses.Side(MatchEntities.LEDGER, posted.transactionId(), line.get("valueDate"),
                amount, number)),
            MatchEntities.FROM_ENTRY, null, "Entry " + number + " made from the line", list(ctx, ITEMS));
        ctx.put(OUTPUT, new EntryOutput(String.valueOf(ctx.get(NEW_ID)), number, posted.glNo(),
            String.valueOf(matchId)));
    }

    /** Whether a rule takes a line: money the way it says, and one of its words in the description. */
    static boolean applies(EntityInstance rule, String description, BigDecimal amount) {
        String direction = rule.get("direction");
        if (MatchEntities.PAYMENTS.equals(direction) && amount.signum() >= 0
            || MatchEntities.DEPOSITS.equals(direction) && amount.signum() <= 0) {
            return false;
        }
        String text = description == null ? "" : description.toUpperCase(Locale.ROOT);
        return keywords(rule.get("keywords")).stream().anyMatch(text::contains);
    }

    static List<String> keywords(String text) {
        if (text == null) {
            return List.of();
        }
        return java.util.Arrays.stream(text.split(",")).map(w -> w.trim().replaceAll("\\s+", " ")
            .toUpperCase(Locale.ROOT)).filter(w -> !w.isEmpty()).distinct().toList();
    }

    private static String cut(String text, int length) {
        return text == null || text.length() <= length ? text : text.substring(0, length);
    }

    static com.jabiz.numbering.NumberSequence numbers() {
        // The count alone: the number is the prefix and month, and a count from the second on.
        return com.jabiz.numbering.NumberSequence.define(NUMBERS, s -> s.format("{n}").scoped());
    }

    private static String bankOf(ProcessContext ctx) {
        return list(ctx, LINE).stream().findFirst().map(l -> (String) l.get("bankCode")).orElse(null);
    }

    private BankEntryProcesses() {}
}
