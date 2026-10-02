package com.jabiz.finance.bank;

import com.jabiz.entity.Violation;
import com.jabiz.finance.FinancePermissions;
import com.jabiz.finance.calc.StatementCheck;
import com.jabiz.finance.gl.JournalEntities;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.process.steps.QueryEntities;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static com.jabiz.finance.bank.BankAccountProcesses.code;
import static com.jabiz.finance.bank.BankAccountProcesses.list;
import static com.jabiz.finance.bank.BankAccountProcesses.trim;

/**
 * Statements and the cutover's outstanding items (FIN-BK-003, FIN-DI-001):
 * <ul>
 *   <li>{@code FIN_BANK_STATEMENT_RECORD}: one statement of one bank account, as an import hands it over. It is
 *       refused whole when its lines do not add up to its closing balance, when a line lies outside its days, when it
 *       names another account (by the last four characters of the number) or currency, when it overlaps a statement
 *       already recorded or comes before one (statements are recorded in order), or when its opening balance is not
 *       the previous statement's closing balance (or, for the first, the statement balance at the cutover). An account
 *       takes statements only once its cutover is recorded: before that there is nothing to follow on from. Internal:
 *       statements come from the bank's files, which the import keeps with its record. Sent again, in this layout or another, a statement adds
 *       nothing: it is refused as recorded already, and a line stored before (the same bank reference, or the same
 *       day, amount and description where the bank gives none) refuses the statement that repeats it, naming it. The
 *       import's report carries refusals, not what a process skipped, so refusing is how the user is told.</li>
 *   <li>{@code FIN_BANK_OPENING_ITEMS}: where an account's reconciliation starts. The statement balance at the cutover
 *       plus the outstanding items (signed as the bank will show them: a check not yet cashed is negative) is the
 *       cash account's balance in the opening entry, or nothing is recorded; once per account, before its first
 *       statement.</li>
 * </ul>
 */
public final class StatementProcesses {

    public static final String RECORD = "FIN_BANK_STATEMENT_RECORD";
    public static final String OPENING_ITEMS = "FIN_BANK_OPENING_ITEMS";

    public static final int MAX_LINES = 5000;

    public static final String UNKNOWN_BANK = "FIN_BANK_STATEMENT_UNKNOWN_BANK";
    public static final String WRONG_ACCOUNT = "FIN_BANK_STATEMENT_ACCOUNT";
    public static final String WRONG_CURRENCY = "FIN_BANK_STATEMENT_CURRENCY";
    public static final String WRONG_FORMAT = "FIN_BANK_STATEMENT_FORMAT";
    public static final String OVERLAP = "FIN_BANK_STATEMENT_OVERLAP";
    public static final String DIFFERENT = "FIN_BANK_STATEMENT_DIFFERENT";
    public static final String RECORDED = "FIN_BANK_STATEMENT_RECORDED";
    public static final String LINE_STORED = "FIN_BANK_STATEMENT_LINE_STORED";
    public static final String GAP = "FIN_BANK_STATEMENT_GAP";
    public static final String OUT_OF_ORDER = "FIN_BANK_STATEMENT_ORDER";
    public static final String NO_CUTOVER = "FIN_BANK_STATEMENT_NO_CUTOVER";
    public static final String OPENING_LATE = "FIN_BANK_OPENING_AFTER_STATEMENTS";
    public static final String OPENING_DONE = "FIN_BANK_OPENING_DONE";
    public static final String OPENING_NONE = "FIN_BANK_OPENING_NO_ENTRY";
    public static final String OPENING_TOTAL = "FIN_BANK_OPENING_TOTAL";
    public static final String OPENING_ITEM = "FIN_BANK_OPENING_ITEM";

    /**
     * A statement line; the amount signed as the bank sees it.
     *
     * @param typeCode the bank's code of the kind of transaction, if it gives one
     */
    public record LineInput(@NotNull LocalDate valueDate, @Size(max = 60) String bankReference,
        @Size(max = 500) String description, @NotNull @Digits(integer = 13, fraction = 2) BigDecimal amount,
        @Size(max = 40) String typeCode) {}

    /**
     * @param format       {@code CSV}, {@code BAI2} or {@code CAMT053}: the layout it came in
     * @param accountLast4 the last four characters of the account number the file names, if it names one
     * @param currency     the currency the file names, if it names one
     */
    public record StatementInput(@NotBlank @Size(max = 20) String bankCode, @NotBlank @Size(max = 10) String format,
        @NotNull LocalDate fromDate, @NotNull LocalDate toDate,
        @NotNull @Digits(integer = 13, fraction = 2) BigDecimal openingBalance,
        @NotNull @Digits(integer = 13, fraction = 2) BigDecimal closingBalance, @Size(max = 4) String accountLast4,
        @Size(max = 3) String currency, @NotNull @Size(max = MAX_LINES) List<@Valid @NotNull LineInput> lines) {}

    /** @param lines the lines stored */
    public record StatementOutput(String statementId, String bankCode, LocalDate toDate, int lines) {}

    /** An outstanding item at the cutover; the amount signed as the bank will show it. */
    public record ItemInput(@NotNull LocalDate itemDate, @NotBlank @Size(max = 40) String reference,
        @Size(max = 500) String description, @NotNull @Digits(integer = 13, fraction = 2) BigDecimal amount) {}

    public record OpeningInput(@NotBlank @Size(max = 20) String bankCode,
        @NotNull @Digits(integer = 13, fraction = 2) BigDecimal statementBalance,
        @NotNull @Size(max = MAX_LINES) List<@Valid @NotNull ItemInput> items) {}

    public record OpeningOutput(String openingId, String bankCode, LocalDate cutoverDate, BigDecimal statementBalance,
        BigDecimal bookBalance, int items) {}

    static final String INPUT = "input";
    static final String OUTPUT = "output";
    static final String BANKS = "banks";
    static final String LATER = "later";
    static final String PREVIOUS = "previous";
    static final String OPENINGS = "openings";
    static final String EXISTING = "existing";
    static final String JOURNALS = "journals";
    static final String JOURNAL_LINES = "journalLines";
    static final String STATEMENTS = "statements";

    public static final ProcessDefinition<StatementInput, StatementOutput, ProcessContext> RECORD_PROCESS =
        ProcessDefinition.define(RECORD, 1, StatementInput.class, StatementOutput.class, ProcessContext.class, pb -> pb
            .description("Records a bank statement of one account.")
            .permissions(FinancePermissions.BANK_STATEMENT_IMPORT)
            .internal()
            .contextFactory(BankAccountProcesses::withInput)
            .outputMapper(ctx -> ctx.get(OUTPUT, StatementOutput.class))
            .step("Load the bank account", QueryEntities.of(BankEntities.BANK_ACCOUNT_DATASET,
                ctx -> BankAccountProcesses.eq("bankCode", code(statement(ctx).bankCode())), BANKS))
            .step("Load the statements of these days and later", QueryEntities.of(StatementEntities.STATEMENT_DATASET,
                ctx -> EntityQuery.builder().where(new QueryPredicate.And(List.of(
                    new QueryPredicate.Eq("bankCode", String.valueOf(code(statement(ctx).bankCode()))),
                    new QueryPredicate.Gte("toDate", statement(ctx).fromDate())))).orderBy("toDate", true).limit(2)
                    .build(), LATER))
            .step("Load the previous statement", QueryEntities.of(StatementEntities.STATEMENT_DATASET,
                ctx -> EntityQuery.builder().where(new QueryPredicate.And(List.of(
                    new QueryPredicate.Eq("bankCode", String.valueOf(code(statement(ctx).bankCode()))),
                    new QueryPredicate.Lt("toDate", statement(ctx).fromDate())))).orderBy("toDate", false).limit(1)
                    .build(), PREVIOUS))
            .step("Load the cutover", QueryEntities.of(StatementEntities.OPENING_DATASET,
                ctx -> BankAccountProcesses.eq("bankCode", code(statement(ctx).bankCode())), OPENINGS))
            .step("Look for lines stored before", QueryEntities.of(StatementEntities.LINE_DATASET, ctx -> {
                List<Object> keys = new ArrayList<>(new LinkedHashSet<>(StatementCheck.keys(lines(statement(ctx)))));
                return EntityQuery.builder().where(new QueryPredicate.And(List.of(
                    new QueryPredicate.Eq("bankCode", String.valueOf(code(statement(ctx).bankCode()))),
                    new QueryPredicate.In("lineKey", keys)))).limit(keys.size() + 1).build();
            }, EXISTING))
            .compute("Record the statement", (metadata, ctx) -> record(ctx)));

    public static final ProcessDefinition<OpeningInput, OpeningOutput, ProcessContext> OPENING_PROCESS =
        ProcessDefinition.define(OPENING_ITEMS, 1, OpeningInput.class, OpeningOutput.class, ProcessContext.class,
            pb -> pb
                .description("Brings over a bank account's outstanding items at the cutover; with the statement "
                    + "balance they add up to the opening cash balance.")
                .permissions(FinancePermissions.MIGRATION)
                .contextFactory(BankAccountProcesses::withInput)
                .outputMapper(ctx -> ctx.get(OUTPUT, OpeningOutput.class))
                .step("Load the bank account", QueryEntities.of(BankEntities.BANK_ACCOUNT_DATASET,
                    ctx -> BankAccountProcesses.eq("bankCode", code(opening(ctx).bankCode())), BANKS))
                .step("Load the cutover", QueryEntities.of(StatementEntities.OPENING_DATASET,
                    ctx -> BankAccountProcesses.eq("bankCode", code(opening(ctx).bankCode())), OPENINGS))
                .step("Look for statements", QueryEntities.of(StatementEntities.STATEMENT_DATASET,
                    ctx -> BankAccountProcesses.eq("bankCode", code(opening(ctx).bankCode())), STATEMENTS))
                .step("Load the opening entry", QueryEntities.of(JournalEntities.JOURNAL_DATASET,
                    ctx -> EntityQuery.builder().where(new QueryPredicate.Eq("source", JournalEntities.OPENING))
                        .limit(1).build(), JOURNALS))
                .step("Load its cash lines", QueryEntities.of(JournalEntities.LINE_DATASET, ctx -> {
                    List<EntityInstance> journals = list(ctx, JOURNALS);
                    List<EntityInstance> banks = list(ctx, BANKS);
                    if (journals.isEmpty() || banks.isEmpty()) {
                        return EntityQuery.builder().where(new QueryPredicate.In("journalId", List.of())).limit(1)
                            .build();
                    }
                    return EntityQuery.builder().where(new QueryPredicate.And(List.of(
                        new QueryPredicate.Eq("journalId", journals.getFirst().id()),
                        new QueryPredicate.Eq("accountCode", banks.getFirst().get("glAccount"))))).limit(500).build();
                }, JOURNAL_LINES))
                .compute("Bring the items over", (metadata, ctx) -> openingItems(ctx)));

    static void record(ProcessContext ctx) {
        StatementInput input = statement(ctx);
        String bankCode = code(input.bankCode());
        EntityInstance bank = list(ctx, BANKS).stream().findFirst().orElse(null);
        if (bank == null) {
            ctx.reject(new Violation("bankCode", UNKNOWN_BANK, "There is no bank account " + bankCode,
                Map.of("bankCode", String.valueOf(bankCode))));
            return;
        }
        String format = code(input.format());
        if (!List.of(BankEntities.CSV, BankEntities.BAI2, BankEntities.CAMT053).contains(format)) {
            ctx.reject(new Violation("format", WRONG_FORMAT, "A statement format is CSV, BAI2 or CAMT053",
                Map.of("value", String.valueOf(format))));
        }
        String last4 = trim(input.accountLast4());
        if (last4 != null && !last4.equalsIgnoreCase(StatementCheck.last4(bank.get("accountNumber")))) {
            // The file is another account's: its lines would be reconciled against the wrong books.
            ctx.reject(new Violation("accountLast4", WRONG_ACCOUNT, "The file is of an account ending in " + last4
                + ", not of bank account " + bankCode, Map.of("bankCode", bankCode)));
        }
        String currency = code(input.currency());
        if (currency != null && !currency.equals(bank.get("currency"))) {
            ctx.reject(new Violation("currency", WRONG_CURRENCY, "The statement is in " + currency + ", bank account "
                + bankCode + " in " + bank.get("currency"), Map.of("currency", currency)));
        }
        List<StatementCheck.Line> lines = lines(input);
        for (StatementCheck.Problem problem : StatementCheck.check(input.fromDate(), input.toDate(),
            input.openingBalance(), lines, input.closingBalance())) {
            ctx.reject(new Violation(problem.line() == null ? "lines" : "lines[" + problem.line() + "]",
                problem.code(), problem.message(), Map.of()));
        }
        if (ctx.hasViolations()) {
            return;
        }
        List<String> keys = StatementCheck.keys(lines);
        Set<String> stored = new LinkedHashSet<>();
        list(ctx, EXISTING).forEach(line -> stored.add(line.get("lineKey")));
        List<String> repeated = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            if (stored.contains(keys.get(i))) {
                repeated.add(label(lines.get(i)));
            }
        }
        EntityInstance sameDay = list(ctx, LATER).stream()
            .filter(s -> input.toDate().equals(s.get("toDate"))).findFirst().orElse(null);
        if (sameDay != null) {
            boolean same = sameDay.<BigDecimal>get("openingBalance").compareTo(input.openingBalance()) == 0
                && sameDay.<BigDecimal>get("closingBalance").compareTo(input.closingBalance()) == 0
                && repeated.size() == lines.size();
            if (same) {
                ctx.reject(new Violation("toDate", RECORDED, "The statement of " + bankCode + " to " + input.toDate()
                    + " is recorded already; nothing was added", Map.of("bankCode", bankCode,
                    "toDate", input.toDate().toString())));
            } else {
                ctx.reject(new Violation("toDate", DIFFERENT, "A different statement of " + bankCode + " to "
                    + input.toDate() + " is recorded", Map.of("toDate", input.toDate().toString())));
            }
            return;
        }
        if (!repeated.isEmpty()) {
            ctx.reject(new Violation("lines", LINE_STORED, "Lines stored before with another statement: "
                + String.join(", ", repeated.subList(0, Math.min(20, repeated.size())))
                + (repeated.size() > 20 ? " and " + (repeated.size() - 20) + " more" : ""),
                Map.of("lines", String.join(", ", repeated.subList(0, Math.min(20, repeated.size()))),
                    "count", repeated.size())));
            return;
        }
        if (!list(ctx, LATER).isEmpty()) {
            EntityInstance other = list(ctx, LATER).getFirst();
            if (other.<java.time.LocalDate>get("fromDate").isAfter(input.toDate())) {
                ctx.reject(new Violation("toDate", OUT_OF_ORDER, "Statements are recorded in order; the statement of "
                    + bankCode + " from " + other.get("fromDate") + " is recorded already",
                    Map.of("fromDate", String.valueOf((Object) other.get("fromDate")))));
                return;
            }
            ctx.reject(new Violation("fromDate", OVERLAP, "The statement of " + bankCode + " from "
                + other.get("fromDate") + " to " + other.get("toDate") + " covers some of these days",
                Map.of("toDate", String.valueOf((Object) other.get("toDate")))));
            return;
        }
        EntityInstance previous = list(ctx, PREVIOUS).stream().findFirst().orElse(null);
        EntityInstance opening = list(ctx, OPENINGS).stream().findFirst().orElse(null);
        if (opening == null) {
            ctx.reject(new Violation("bankCode", NO_CUTOVER, "The outstanding items of " + bankCode + " at the "
                + "cutover are not brought over yet; its statements follow on from them", Map.of("bankCode", bankCode)));
            return;
        }
        BigDecimal expected;
        String after;
        if (previous != null) {
            expected = previous.get("closingBalance");
            after = "the statement to " + previous.get("toDate");
        } else {
            expected = opening.get("statementBalance");
            after = "the cutover on " + opening.get("cutoverDate");
            if (!input.fromDate().isAfter(opening.get("cutoverDate"))) {
                ctx.reject(new Violation("fromDate", OVERLAP, "The statement starts on or before the cutover, "
                    + opening.get("cutoverDate"), Map.of("cutoverDate", String.valueOf((Object) opening.get(
                    "cutoverDate")))));
                return;
            }
        }
        if (expected.compareTo(input.openingBalance()) != 0) {
            // A statement missing in between, or one of another account: either way the balances would not follow.
            ctx.reject(new Violation("openingBalance", GAP, "The opening balance " + input.openingBalance()
                .toPlainString() + " is not the balance " + expected.toPlainString() + " after " + after,
                Map.of("expected", expected, "opening", input.openingBalance())));
            return;
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("bankCode", bankCode);
        values.put("fromDate", input.fromDate());
        values.put("toDate", input.toDate());
        values.put("openingBalance", input.openingBalance());
        values.put("closingBalance", input.closingBalance());
        values.put("lineCount", BigDecimal.valueOf(lines.size()));
        values.put("format", format);
        Object statementId = ctx.changes().insert(StatementEntities.STATEMENT, values);
        for (int i = 0; i < lines.size(); i++) {
            LineInput line = input.lines().get(i);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("statementId", statementId);
            row.put("bankCode", bankCode);
            row.put("lineNo", BigDecimal.valueOf(i + 1));
            row.put("valueDate", line.valueDate());
            row.put("bankReference", trim(line.bankReference()));
            row.put("description", trim(line.description()));
            row.put("amount", line.amount());
            row.put("typeCode", trim(line.typeCode()));
            row.put("lineKey", keys.get(i));
            ctx.changes().insert(StatementEntities.LINE, row);
        }
        ctx.put(OUTPUT, new StatementOutput(String.valueOf(statementId), bankCode, input.toDate(), lines.size()));
    }

    static void openingItems(ProcessContext ctx) {
        OpeningInput input = opening(ctx);
        String bankCode = code(input.bankCode());
        EntityInstance bank = list(ctx, BANKS).stream().findFirst().orElse(null);
        if (bank == null) {
            ctx.reject(new Violation("bankCode", UNKNOWN_BANK, "There is no bank account " + bankCode,
                Map.of("bankCode", String.valueOf(bankCode))));
            return;
        }
        if (!list(ctx, OPENINGS).isEmpty()) {
            ctx.reject(new Violation("bankCode", OPENING_DONE, "The outstanding items of " + bankCode + " were "
                + "brought over already", Map.of("bankCode", bankCode)));
            return;
        }
        if (!list(ctx, STATEMENTS).isEmpty()) {
            ctx.reject(new Violation("bankCode", OPENING_LATE, "Statements of " + bankCode + " are recorded already; "
                + "the cutover comes before them", Map.of("bankCode", bankCode)));
            return;
        }
        List<EntityInstance> journals = list(ctx, JOURNALS);
        if (journals.isEmpty() || !"POSTED".equals(journals.getFirst().get("status"))) {
            ctx.reject(new Violation("items", OPENING_NONE, "The opening entry is not posted yet", Map.of()));
            return;
        }
        LocalDate cutover = journals.getFirst().get("postingDate");
        Set<String> references = new LinkedHashSet<>();
        for (int i = 0; i < input.items().size(); i++) {
            ItemInput item = input.items().get(i);
            String reference = item.reference().trim().toUpperCase(Locale.ROOT);
            if (!references.add(reference)) {
                ctx.reject(new Violation("items[" + i + "].reference", OPENING_ITEM, "Item " + item.reference()
                    + " is in the file twice", Map.of("value", item.reference())));
            }
            if (item.itemDate().isAfter(cutover)) {
                ctx.reject(new Violation("items[" + i + "].itemDate", OPENING_ITEM, "An outstanding item is dated on "
                    + "or before the cutover, " + cutover, Map.of("value", item.itemDate().toString())));
            }
            if (item.amount().signum() == 0) {
                ctx.reject(new Violation("items[" + i + "].amount", OPENING_ITEM, "An outstanding item has an "
                    + "amount", Map.of()));
            }
        }
        if (ctx.hasViolations()) {
            return;
        }
        BigDecimal book = BigDecimal.ZERO;
        for (EntityInstance line : list(ctx, JOURNAL_LINES)) {
            BigDecimal debit = line.get("debit");
            BigDecimal credit = line.get("credit");
            book = book.add(debit == null ? BigDecimal.ZERO : debit).subtract(credit == null ? BigDecimal.ZERO
                : credit);
        }
        BigDecimal items = input.items().stream().map(ItemInput::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal adjusted = input.statementBalance().add(items);
        if (adjusted.compareTo(book) != 0) {
            ctx.reject(new Violation("items", OPENING_TOTAL, "The statement balance " + input.statementBalance()
                .toPlainString() + " and the outstanding items " + items.toPlainString() + " come to "
                + adjusted.toPlainString() + "; the opening balance of " + bank.get("glAccount") + " is "
                + book.toPlainString(), Map.of("adjusted", adjusted, "book", book,
                "difference", adjusted.subtract(book).abs())));
            return;
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("bankCode", bankCode);
        values.put("cutoverDate", cutover);
        values.put("statementBalance", input.statementBalance());
        values.put("bookBalance", book);
        values.put("itemCount", BigDecimal.valueOf(input.items().size()));
        Object openingId = ctx.changes().insert(StatementEntities.OPENING, values);
        for (ItemInput item : input.items()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("openingId", openingId);
            row.put("bankCode", bankCode);
            row.put("itemDate", item.itemDate());
            row.put("reference", item.reference().trim().toUpperCase(Locale.ROOT));
            row.put("description", trim(item.description()));
            row.put("amount", item.amount());
            ctx.changes().insert(StatementEntities.OPENING_ITEM, row);
        }
        ctx.put(OUTPUT, new OpeningOutput(String.valueOf(openingId), bankCode, cutover, input.statementBalance(), book,
            input.items().size()));
    }

    static List<StatementCheck.Line> lines(StatementInput input) {
        return input.lines().stream().map(l -> new StatementCheck.Line(l.valueDate(), l.bankReference(),
            l.description(), l.amount())).toList();
    }

    private static String label(StatementCheck.Line line) {
        String reference = trim(line.bankReference());
        return reference != null ? reference : line.valueDate() + " " + line.amount().toPlainString();
    }

    private static StatementInput statement(ProcessContext ctx) {
        return ctx.get(INPUT, StatementInput.class);
    }

    private static OpeningInput opening(ProcessContext ctx) {
        return ctx.get(INPUT, OpeningInput.class);
    }

    private StatementProcesses() {}
}
