package com.jabiz.finance.gl;

import com.jabiz.entity.Violation;
import com.jabiz.finance.FinancePermissions;
import com.jabiz.finance.calc.Money;
import com.jabiz.finance.migration.MigrationEntities;
import com.jabiz.finance.migration.MigrationProcesses;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.ledger.LedgerEntities;
import com.jabiz.runtime.process.steps.CallProcess;
import com.jabiz.runtime.process.steps.QueryEntities;
import com.jabiz.runtime.process.steps.SaveChanges;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.jabiz.finance.gl.AccountProcesses.list;

/**
 * The opening of the books (FIN-PC-002, FIN-DI-002; docs/finance/00-design.md section 13).
 * <ul>
 *   <li>{@code FIN_OPENING_POST}: the one balanced opening entry, dated the day before the first fiscal year. It
 *       makes the opening period of that year (period 0, "2026-00") and posts into it; no other entry finds that
 *       period. The opening entry is the source of the opening balances of every account, the control accounts
 *       included: the subledgers' opening items, loaded with their phases, are not posted again but must add up to
 *       them. Legacy account codes are read through the recorded account decisions (FIN-DI-003). Once per set of
 *       books; refused while the books have a year before it.</li>
 *   <li>{@code FIN_OPENING_CLOSE}: closes the opening period for good once the migration is accepted.</li>
 * </ul>
 * Both need {@value FinancePermissions#MIGRATION}; the opening entry follows no approval rule: it is the migration's
 * own record, reconciled against the source in the migration report.
 */
public final class OpeningProcesses {

    public static final String POST_OPENING = "FIN_OPENING_POST";
    public static final String CLOSE_OPENING = "FIN_OPENING_CLOSE";

    public static final String NO_YEAR = "FIN_OPENING_NO_YEAR";
    public static final String NOT_FIRST = "FIN_OPENING_NOT_FIRST";
    public static final String EXISTS = "FIN_OPENING_EXISTS";
    public static final String NONE = "FIN_OPENING_NONE";
    public static final String BOOKS_IN_USE = "FIN_OPENING_BOOKS_IN_USE";
    public static final String NOT_POSTED = "FIN_OPENING_NOT_POSTED";

    /**
     * @param postingDate the day before the first day of the first fiscal year
     * @param lines       one per account (and dimension), each with a debit or a credit
     */
    public record OpeningInput(@NotNull LocalDate postingDate, @NotBlank @Size(max = 500) String description,
        @NotNull @Size(max = JournalValidator.MAX_LINES) List<JournalProcesses.@Valid @NotNull LineInput> lines) {}

    /** @param mappedAccounts the legacy account codes read as accounts of the chart, by the recorded decisions */
    public record OpeningOutput(String journalId, String journalNo, String periodKey, BigDecimal totalDebit,
        BigDecimal totalCredit, boolean posted, String glNo, String transactionId,
        Map<String, String> mappedAccounts) {}

    public record CloseInput(@Size(max = 500) String note) {}

    static final String INPUT = "input";
    static final String OUTPUT = "output";
    static final String YEARS = "years";
    static final String OPENINGS = "openings";
    static final String DECISIONS = "decisions";
    static final String POST_INPUT = "postInput";
    static final String POSTINGS = "postings";
    static final String NOT_OPEN = "notOpen";

    public static final ProcessDefinition<OpeningInput, OpeningOutput, ProcessContext> POST_PROCESS =
        ProcessDefinition.define(POST_OPENING, 1, OpeningInput.class, OpeningOutput.class, ProcessContext.class,
            pb -> pb
                .description("Posts the opening entry of the books, dated the day before the first fiscal year.")
                .permissions(FinancePermissions.MIGRATION)
                .contextFactory(AccountProcesses::withInput)
                .outputMapper(OpeningProcesses::output)
                .step("Load the first fiscal years", QueryEntities.of(GlEntities.FISCAL_YEAR_DATASET,
                    ctx -> EntityQuery.builder().where(new QueryPredicate.Lte("startDate",
                        input(ctx).postingDate().plusDays(1))).limit(2).build(), YEARS))
                .step("Load the opening of the books", QueryEntities.of(GlEntities.PERIOD_DATASET,
                    ctx -> PeriodProcesses.openings(), OPENINGS))
                // Books in use are opened no more: an opening then would change balances already reported on.
                .step("Load any posting", QueryEntities.of(JournalEntities.POSTING_DATASET,
                    ctx -> EntityQuery.builder().limit(1).build(), POSTINGS))
                .step("Load any period no longer open", QueryEntities.of(GlEntities.PERIOD_DATASET,
                    ctx -> EntityQuery.builder().where(new QueryPredicate.Ne("status", "OPEN")).limit(1).build(),
                    NOT_OPEN))
                .step("Load the account decisions", QueryEntities.of(MigrationEntities.DECISION_DATASET,
                    ctx -> MigrationProcesses.accountDecisions(rawCodes(ctx)), DECISIONS))
                .step("Load the accounts", QueryEntities.of(GlEntities.ACCOUNT_DATASET,
                    ctx -> JournalProcesses.byCodes("accountCode", codes(ctx)), JournalProcesses.FIN_ACCOUNTS))
                .step("Load the ledger accounts", QueryEntities.of(LedgerEntities.ACCOUNT_DATASET,
                    ctx -> JournalProcesses.byCodes("accountCode", codes(ctx)), JournalProcesses.LEDGER_ACCOUNTS))
                .step("Load the departments", QueryEntities.of(GlEntities.DEPARTMENT_DATASET,
                    ctx -> JournalProcesses.byCodes("departmentCode", dimensions(ctx, "department")),
                    JournalProcesses.DEPARTMENTS))
                .step("Load the locations", QueryEntities.of(GlEntities.LOCATION_DATASET,
                    ctx -> JournalProcesses.byCodes("locationCode", dimensions(ctx, "location")),
                    JournalProcesses.LOCATIONS))
                .compute("Make the opening period and entry", (metadata, ctx) -> open(ctx))
                .step("Save", SaveChanges.now())
                .step("Post it", CallProcess.when(ctx -> ctx.contains(POST_INPUT), JournalProcesses.POST, 1,
                    ctx -> ctx.get(POST_INPUT), JournalProcesses.POSTED_OUTPUT))
                .compute("Check it posted", (metadata, ctx) -> checkPosted(ctx)));

    public static final ProcessDefinition<CloseInput, PeriodProcesses.PeriodOutput, ProcessContext> CLOSE_PROCESS =
        ProcessDefinition.define(CLOSE_OPENING, 1, CloseInput.class, PeriodProcesses.PeriodOutput.class,
            ProcessContext.class, pb -> pb
                .description("Closes the opening period for good once the migration is accepted.")
                .permissions(FinancePermissions.MIGRATION)
                .contextFactory(AccountProcesses::withInput)
                .outputMapper(ctx -> ctx.get(OUTPUT, PeriodProcesses.PeriodOutput.class))
                .step("Load the opening of the books", QueryEntities.of(GlEntities.PERIOD_DATASET,
                    ctx -> PeriodProcesses.openings(), OPENINGS))
                .compute("Close it", (metadata, ctx) -> close(ctx)));

    static void open(ProcessContext ctx) {
        OpeningInput input = input(ctx);
        LocalDate firstDay = input.postingDate().plusDays(1);
        List<EntityInstance> years = list(ctx, YEARS);
        EntityInstance year = years.stream().filter(y -> firstDay.equals(y.get("startDate"))).findFirst()
            .orElse(null);
        if (year == null) {
            ctx.reject(new Violation("postingDate", NO_YEAR, "No fiscal year starts on " + firstDay
                + ": the opening entry is dated the day before the first year", Map.of("firstDay",
                firstDay.toString())));
        } else if (years.size() > 1) {
            ctx.reject(new Violation("postingDate", NOT_FIRST, "Fiscal year " + year.<BigDecimal>get("fiscalYear")
                .toPlainString() + " is not the first year of the books", Map.of("fiscalYear",
                year.<BigDecimal>get("fiscalYear"))));
        }
        if (!list(ctx, OPENINGS).isEmpty()) {
            EntityInstance opening = list(ctx, OPENINGS).getFirst();
            ctx.reject(new Violation("postingDate", EXISTS, "The books were opened already (period "
                + opening.get("periodKey") + ")", Map.of("periodKey", opening.<String>get("periodKey"))));
        } else if (!list(ctx, POSTINGS).isEmpty() || !list(ctx, NOT_OPEN).isEmpty()) {
            ctx.reject(new Violation("postingDate", BOOKS_IN_USE, "The books are in use (entries are posted or a "
                + "period is closed): they are no longer opened", Map.of()));
        }
        Map<String, String> mapped = effectiveMap(ctx);
        List<JournalValidator.Line> lines = lines(input, mapped);
        for (Violation problem : JournalValidator.checkForPosting(lines, JournalProcesses.accounts(ctx),
            JournalProcesses.dimensions(ctx), true)) {
            ctx.reject(problem);
        }
        if (ctx.hasViolations()) {
            return;
        }
        int fiscalYear = year.<BigDecimal>get("fiscalYear").intValueExact();
        String periodKey = fiscalYear + "-00";
        Map<String, Object> period = new LinkedHashMap<>();
        period.put("fiscalYearId", year.id());
        period.put("fiscalYear", BigDecimal.valueOf(fiscalYear));
        period.put("periodNo", BigDecimal.ZERO);
        period.put("periodKey", periodKey);
        period.put("adjustment", false);
        period.put("opening", true);
        period.put("startDate", input.postingDate());
        period.put("endDate", input.postingDate());
        period.put("status", "OPEN");
        PeriodProcesses.SUBLEDGER_FIELDS.values().forEach(field -> period.put(field, "OPEN"));
        ctx.changes().insert(GlEntities.PERIOD, period);

        JournalValidator.Totals totals = JournalValidator.totals(lines);
        String journalNo = "OPENING-" + fiscalYear;
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("postingDate", input.postingDate());
        header.put("documentDate", input.postingDate());
        header.put("description", input.description().trim());
        header.put("adjusting", false);
        header.put("adjustmentPeriod", false);
        String hash = JournalProcesses.contentHash(header, JournalEntities.OPENING, JournalProcesses.lineMaps(lines));
        header.put("source", JournalEntities.OPENING);
        header.put("status", JournalEntities.APPROVED);
        header.put("preparer", ctx.request().actorId());
        header.put("totalDebit", totals.debit());
        header.put("totalCredit", totals.credit());
        header.put("journalNo", journalNo);
        header.put("periodKey", periodKey);
        header.put("fiscalYear", BigDecimal.valueOf(fiscalYear));
        header.put("contentHash", hash);
        Object id = ctx.changes().insert(JournalEntities.JOURNAL, header);
        JournalProcesses.insertLines(ctx, id, lines);
        ctx.put(POST_INPUT, new JournalProcesses.PostInput(UUID.fromString(String.valueOf(id)), hash));
        Map<String, String> used = new LinkedHashMap<>();
        for (JournalProcesses.LineInput line : input.lines()) {
            String code = trim(line.accountCode());
            if (mapped.containsKey(code)) {
                used.put(code, mapped.get(code));
            }
        }
        ctx.put(OUTPUT, new OpeningOutput(String.valueOf(id), journalNo, periodKey, totals.debit(), totals.credit(),
            false, null, null, Map.copyOf(used)));
    }

    /**
     * The decisions that apply: a legacy code that is (now) an account of the chart is read as itself, whatever was
     * decided while it was not.
     */
    private static Map<String, String> effectiveMap(ProcessContext ctx) {
        Map<String, String> mapped = new LinkedHashMap<>(MigrationProcesses.accountMap(list(ctx, DECISIONS)));
        for (EntityInstance account : list(ctx, JournalProcesses.FIN_ACCOUNTS)) {
            mapped.remove(account.<String>get("accountCode"));
        }
        return mapped;
    }

    /** A posting the ledger refused leaves nothing behind: the whole opening is refused with its reason. */
    static void checkPosted(ProcessContext ctx) {
        if (!ctx.contains(JournalProcesses.POSTED_OUTPUT)) {
            return;
        }
        JournalProcesses.PostOutput posted = ctx.get(JournalProcesses.POSTED_OUTPUT, JournalProcesses.PostOutput.class);
        if (!posted.posted()) {
            ctx.reject(new Violation("lines", NOT_POSTED, "The opening entry was not posted: " + posted.reason(),
                Map.of("reason", String.valueOf(posted.reason()))));
        }
    }

    static void close(ProcessContext ctx) {
        List<EntityInstance> found = list(ctx, OPENINGS);
        if (found.isEmpty()) {
            ctx.reject(new Violation("periodKey", NONE, "The books have no opening period", Map.of()));
            return;
        }
        EntityInstance period = found.getFirst();
        Map<String, Object> closed = new LinkedHashMap<>();
        closed.put("status", "CLOSED");
        PeriodProcesses.SUBLEDGER_FIELDS.values().forEach(field -> closed.put(field, "CLOSED"));
        boolean change = closed.entrySet().stream().anyMatch(e -> !e.getValue().equals(period.get(e.getKey())));
        if (change) {
            ctx.changes().update(GlEntities.PERIOD, period.id(), period.version(), closed);
        }
        ctx.put(OUTPUT, new PeriodProcesses.PeriodOutput(period.get("periodKey"), "CLOSED", "CLOSED", "CLOSED",
            "CLOSED", "CLOSED", change));
    }

    /** The lines as posted: legacy codes read through the decisions, amounts in cents as the ledger keeps them. */
    static List<JournalValidator.Line> lines(OpeningInput input, Map<String, String> mapped) {
        List<JournalValidator.Line> lines = new ArrayList<>();
        for (JournalProcesses.LineInput line : input.lines()) {
            String code = trim(line.accountCode());
            lines.add(new JournalValidator.Line(mapped.getOrDefault(code, code), cents(line.debit()),
                cents(line.credit()), line.memo(), trim(line.department()), trim(line.location())));
        }
        return lines;
    }

    private static BigDecimal cents(BigDecimal amount) {
        // An amount with more decimals stays as given: the line check refuses it rather than rounding.
        return amount == null || !Money.fits(amount, Money.USD_SCALE) ? amount : Money.usd(amount);
    }

    private static OpeningOutput output(ProcessContext ctx) {
        OpeningOutput made = ctx.get(OUTPUT, OpeningOutput.class);
        if (!ctx.contains(JournalProcesses.POSTED_OUTPUT)) {
            return made;
        }
        JournalProcesses.PostOutput posted = ctx.get(JournalProcesses.POSTED_OUTPUT, JournalProcesses.PostOutput.class);
        return new OpeningOutput(made.journalId(), made.journalNo(), made.periodKey(), made.totalDebit(),
            made.totalCredit(), posted.posted(), posted.glNo(), posted.transactionId(), made.mappedAccounts());
    }

    private static OpeningInput input(ProcessContext ctx) {
        return ctx.get(INPUT, OpeningInput.class);
    }

    private static Set<String> rawCodes(ProcessContext ctx) {
        Set<String> codes = new LinkedHashSet<>();
        for (JournalProcesses.LineInput line : input(ctx).lines()) {
            String code = trim(line.accountCode());
            if (code != null) {
                codes.add(code);
            }
        }
        return codes;
    }

    private static Set<String> codes(ProcessContext ctx) {
        Map<String, String> mapped = MigrationProcesses.accountMap(list(ctx, DECISIONS));
        // The raw codes too: one that is an account of the chart is read as itself.
        Set<String> codes = new LinkedHashSet<>(rawCodes(ctx));
        for (String code : rawCodes(ctx)) {
            codes.add(mapped.getOrDefault(code, code));
        }
        return codes;
    }

    private static Set<String> dimensions(ProcessContext ctx, String dimension) {
        Set<String> values = new LinkedHashSet<>();
        for (JournalProcesses.LineInput line : input(ctx).lines()) {
            String value = trim("department".equals(dimension) ? line.department() : line.location());
            if (value != null) {
                values.add(value);
            }
        }
        return values;
    }

    private static String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private OpeningProcesses() {}
}
