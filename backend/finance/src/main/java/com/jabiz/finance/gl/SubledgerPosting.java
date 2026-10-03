package com.jabiz.finance.gl;

import com.jabiz.entity.Violation;
import com.jabiz.finance.FinancePermissions;
import com.jabiz.finance.calc.BookingTime;
import com.jabiz.finance.calc.PeriodPolicy;
import com.jabiz.ledger.Direction;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.ledger.LedgerEntities;
import com.jabiz.runtime.ledger.LedgerProcesses;
import com.jabiz.runtime.numbering.AssignNumber;
import com.jabiz.runtime.process.steps.CallProcess;
import com.jabiz.runtime.process.steps.QueryEntities;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * {@code FIN_SUBLEDGER_POST}: a subledger document's entry in the general ledger (FIN-GL-021; docs/finance/00-design.md
 * section 5), posted in the document's own transaction when the document is posted. The lines are built by the
 * subledger's process from its settings, so control accounts take them (a subledger is what posts to its control
 * account, FIN-GL-005); everything else the ledger needs is checked as for a journal entry: balanced, accounts that
 * take postings, valid dimensions, a period open for the general ledger and for the subledger. The entry gets a
 * general ledger number of the subledger's own series ({@code GJ-AR-2026-000001}), its ledger transaction refers to
 * the document, and a {@code FinPosting} records it for drill-down from every line to the document. A refusal fails
 * the calling process, so the document is not posted either. Internal: only subledger processes call it.
 */
public final class SubledgerPosting {

    public static final String POST = "FIN_SUBLEDGER_POST";
    public static final String REVERSE = "FIN_SUBLEDGER_REVERSE";

    public static final String REFUSED = "FIN_SUBLEDGER_POSTING_REFUSED";
    public static final String CONTROL_ACCOUNT = "FIN_SUBLEDGER_CONTROL_ACCOUNT";

    /**
     * @param source       {@code AR}, {@code AP}, {@code BANK} or {@code FA}: the subledger, its period state and its
     *                     series of general ledger numbers
     * @param documentNo   the document's number, the ledger transaction's reference ("INV-1004")
     * @param sourceEntity the document's entity ("FinInvoice"), with {@code sourceId} its identity
     * @param controlClasses the control accounts the document may post to: its subledger's own ({@code AR}), never
     *                     another's
     */
    public record PostInput(@NotBlank String source, @NotNull LocalDate postingDate,
        @NotBlank @Size(max = 500) String description, @NotBlank @Size(max = 40) String documentNo,
        @NotBlank String sourceEntity, @NotBlank String sourceId,
        @NotEmpty List<JournalProcesses.@Valid @NotNull LineInput> lines, @NotNull List<String> controlClasses) {}

    public record PostOutput(String glNo, String transactionId, String periodKey) {}

    /**
     * A posted document's entry reversed on {@code reverseDate} (a void, FIN-AR-004): the ledger's own reversal of
     * the transaction, with a number of the subledger's series and a {@code FinPosting} that refers to the document.
     */
    public record ReverseInput(@NotBlank String source, @NotBlank String transactionId, @NotNull LocalDate reverseDate,
        @NotBlank @Size(max = 500) String reason, @NotBlank @Size(max = 40) String documentNo,
        @NotBlank String sourceEntity, @NotBlank String sourceId) {}

    static final String INPUT = JournalProcesses.INPUT;
    static final String OUTPUT = JournalProcesses.OUTPUT;
    static final String READY = "ready";
    static final String GL_NO = "glNo";
    static final String LEDGER_OUTPUT = "ledgerOutput";

    record Ready(LedgerProcesses.PostInput ledger, String glScope, EntityInstance period) {}

    public static ProcessDefinition<PostInput, PostOutput, ProcessContext> postProcess(BookingTime booking) {
        return ProcessDefinition.define(POST, 1, PostInput.class, PostOutput.class, ProcessContext.class, pb -> pb
            .description("Posts a subledger document's entry to the general ledger; run by the subledger processes.")
            .permissions(FinancePermissions.SUBLEDGER_POST)
            .internal()
            .contextFactory(AccountProcesses::withInput)
            .outputMapper(ctx -> ctx.get(OUTPUT, PostOutput.class))
            .step("Load the period", QueryEntities.of(GlEntities.PERIOD_DATASET,
                ctx -> JournalProcesses.periodsOf(input(ctx).postingDate()), JournalProcesses.PERIODS))
            .steps(b -> PeriodLocks.share(b, ctx -> JournalProcesses.periodsOf(input(ctx).postingDate())))
            .step("Load the accounts", QueryEntities.of(GlEntities.ACCOUNT_DATASET,
                ctx -> JournalProcesses.byCodes("accountCode", values(ctx, "account")), JournalProcesses.FIN_ACCOUNTS))
            .step("Load the ledger accounts", QueryEntities.of(LedgerEntities.ACCOUNT_DATASET,
                ctx -> JournalProcesses.byCodes("accountCode", values(ctx, "account")),
                JournalProcesses.LEDGER_ACCOUNTS))
            .step("Load the departments", QueryEntities.of(GlEntities.DEPARTMENT_DATASET,
                ctx -> JournalProcesses.byCodes("departmentCode", values(ctx, "department")),
                JournalProcesses.DEPARTMENTS))
            .step("Load the locations", QueryEntities.of(GlEntities.LOCATION_DATASET,
                ctx -> JournalProcesses.byCodes("locationCode", values(ctx, "location")), JournalProcesses.LOCATIONS))
            .compute("Check the entry", (metadata, ctx) -> check(ctx, booking))
            .step("Number the posting", AssignNumber.when(ctx -> ctx.contains(READY), JournalProcesses.GL_NUMBERS,
                ctx -> ctx.get(READY, Ready.class).glScope(), GL_NO))
            .step("Post to the ledger", CallProcess.when(ctx -> ctx.contains(READY), LedgerProcesses.POST, 1,
                ctx -> ctx.get(READY, Ready.class).ledger(), LEDGER_OUTPUT))
            .compute("Record the posting", (metadata, ctx) -> record(ctx)));
    }

    public static ProcessDefinition<ReverseInput, PostOutput, ProcessContext> reverseProcess(BookingTime booking) {
        return ProcessDefinition.define(REVERSE, 1, ReverseInput.class, PostOutput.class, ProcessContext.class,
            pb -> pb
                .description("Reverses a subledger document's entry; run by the subledger processes.")
                .permissions(FinancePermissions.SUBLEDGER_POST)
                .internal()
                .contextFactory(AccountProcesses::withInput)
                .outputMapper(ctx -> ctx.get(OUTPUT, PostOutput.class))
                .step("Load the period", QueryEntities.of(GlEntities.PERIOD_DATASET,
                    ctx -> JournalProcesses.periodsOf(reverseInput(ctx).reverseDate()), JournalProcesses.PERIODS))
                .steps(b -> PeriodLocks.share(b, ctx -> JournalProcesses.periodsOf(reverseInput(ctx).reverseDate())))
                .step("Load the posting", QueryEntities.of(JournalEntities.POSTING_DATASET,
                    ctx -> com.jabiz.query.EntityQuery.builder().where(new com.jabiz.query.QueryPredicate.Eq(
                        "documentNo", reverseInput(ctx).documentNo())).limit(50).build(), POSTINGS))
                .compute("Check the period", (metadata, ctx) -> {
                    ReverseInput input = reverseInput(ctx);
                    // Only the document's own entry: what it reverses is what it posted.
                    boolean own = AccountProcesses.list(ctx, POSTINGS).stream().anyMatch(p ->
                        input.transactionId().equals(String.valueOf((Object) p.get("transactionId")))
                            && input.sourceEntity().equals(p.get("sourceEntity"))
                            && input.sourceId().equals(p.get("sourceId")));
                    if (!own) {
                        ctx.reject(new Violation("transactionId", REFUSED, "The transaction is not the document's",
                            Map.of("reason", "the transaction is not the document's")));
                        return;
                    }
                    PeriodPolicy.Source source = source(ctx, input.source());
                    EntityInstance period = source == null ? null : open(ctx, source, input.reverseDate());
                    if (period == null) {
                        return;
                    }
                    ctx.put(READY, new Ready(null, postingSource(source) + "-"
                        + period.<BigDecimal>get("fiscalYear").intValueExact(), period));
                    ctx.put(REVERSE_INPUT, new LedgerProcesses.ReverseInput(input.transactionId(), input.reason(),
                        booking.of(input.reverseDate())));
                })
                .step("Number the posting", AssignNumber.when(ctx -> ctx.contains(READY),
                    JournalProcesses.GL_NUMBERS, ctx -> ctx.get(READY, Ready.class).glScope(), GL_NO))
                .step("Reverse in the ledger", CallProcess.when(ctx -> ctx.contains(READY), LedgerProcesses.REVERSE, 1,
                    ctx -> ctx.get(REVERSE_INPUT), LEDGER_OUTPUT))
                .compute("Record the posting", (metadata, ctx) -> {
                    if (!ctx.contains(LEDGER_OUTPUT)) {
                        return;
                    }
                    ReverseInput input = reverseInput(ctx);
                    String transactionId = ctx.get(LEDGER_OUTPUT, LedgerProcesses.ReverseOutput.class)
                        .transactionId();
                    recordPosting(ctx, input.source(), input.reverseDate(), input.documentNo(), input.sourceEntity(),
                        input.sourceId(), transactionId);
                }));
    }

    static final String REVERSE_INPUT = "reverseInput";
    static final String POSTINGS = "postings";

    /** The subledger named, or null with the refusal recorded. */
    private static PeriodPolicy.Source source(ProcessContext ctx, String name) {
        PeriodPolicy.Source source;
        try {
            source = PeriodPolicy.Source.valueOf(name);
        } catch (IllegalArgumentException e) {
            source = null;
        }
        if (source == null || source == PeriodPolicy.Source.GL) {
            ctx.reject(new Violation("source", REFUSED, "Not a subledger: " + name,
                Map.of("reason", "not a subledger: " + name)));
            return null;
        }
        return source;
    }

    /** The period holding {@code date}, if it is open for the subledger; otherwise null with the refusal recorded. */
    private static EntityInstance open(ProcessContext ctx, PeriodPolicy.Source source, LocalDate date) {
        EntityInstance period = JournalProcesses.period(ctx, false, false);
        if (period == null) {
            ctx.reject(new Violation("postingDate", JournalProcesses.NO_PERIOD, "No fiscal period holds " + date,
                Map.of("date", date.toString())));
            return null;
        }
        var refusal = PeriodPolicy.check(JournalProcesses.state(period, source), source, false, false);
        if (refusal.isPresent()) {
            ctx.reject(new Violation("postingDate", refusal.get().code(), refusal.get().message(),
                Map.of("periodKey", (Object) period.get("periodKey"))));
            return null;
        }
        return period;
    }

    static void check(ProcessContext ctx, BookingTime booking) {
        PostInput input = input(ctx);
        PeriodPolicy.Source source = source(ctx, input.source());
        if (source == null) {
            return;
        }
        for (Violation problem : JournalProcesses.dollarsOnly(input.lines())) {
            ctx.reject(problem);
        }
        List<JournalValidator.Line> lines = input.lines().stream().map(JournalProcesses::line).toList();
        Map<String, JournalValidator.Account> accounts = JournalProcesses.accounts(ctx);
        for (Violation problem : JournalValidator.checkForPosting(lines, accounts, JournalProcesses.dimensions(ctx),
            true)) {
            ctx.reject(problem);
        }
        for (int i = 0; i < lines.size(); i++) {
            JournalValidator.Account account = accounts.get(lines.get(i).accountCode());
            if (account != null && account.controlClass() != null
                && !input.controlClasses().contains(account.controlClass())) {
                ctx.reject(new Violation("lines[" + i + "].accountCode", CONTROL_ACCOUNT, "Account "
                    + account.code() + " is a " + account.controlClass() + " control account: a " + input.source()
                    + " document does not post to it", Map.of("accountCode", account.code(),
                    "controlClass", account.controlClass())));
            }
        }
        EntityInstance period = open(ctx, source, input.postingDate());
        if (ctx.hasViolations() || period == null) {
            return;
        }
        List<LedgerProcesses.Line> entries = new ArrayList<>();
        for (JournalValidator.Line line : lines) {
            boolean debit = line.debit() != null && line.debit().signum() != 0;
            Map<String, String> dimensions = new LinkedHashMap<>();
            if (line.department() != null) {
                dimensions.put("department", line.department());
            }
            if (line.location() != null) {
                dimensions.put("location", line.location());
            }
            entries.add(new LedgerProcesses.Line(line.accountCode(), debit ? Direction.DEBIT : Direction.CREDIT,
                debit ? line.debit() : line.credit(), line.memo(), dimensions.isEmpty() ? null : dimensions));
        }
        LedgerProcesses.PostInput ledger = new LedgerProcesses.PostInput(booking.of(input.postingDate()),
            input.description(), input.documentNo(), entries, input.sourceEntity(), input.sourceId());
        int fiscalYear = period.<BigDecimal>get("fiscalYear").intValueExact();
        ctx.put(READY, new Ready(ledger, postingSource(source) + "-" + fiscalYear, period));
    }

    static void record(ProcessContext ctx) {
        if (!ctx.contains(LEDGER_OUTPUT)) {
            return;
        }
        PostInput input = input(ctx);
        String transactionId = ctx.get(LEDGER_OUTPUT, LedgerProcesses.PostOutput.class).transactionId();
        recordPosting(ctx, input.source(), input.postingDate(), input.documentNo(), input.sourceEntity(),
            input.sourceId(), transactionId);
    }

    private static void recordPosting(ProcessContext ctx, String source, LocalDate date, String documentNo,
        String sourceEntity, String sourceId, String transactionId) {
        Ready ready = ctx.get(READY, Ready.class);
        String glNo = ctx.get(GL_NO, String.class);
        Map<String, Object> posting = new LinkedHashMap<>();
        posting.put("transactionId", UUID.fromString(transactionId));
        posting.put("postingDate", date);
        posting.put("fiscalYear", ready.period().get("fiscalYear"));
        posting.put("periodNo", ready.period().get("periodNo"));
        posting.put("periodKey", ready.period().get("periodKey"));
        posting.put("source", postingSource(PeriodPolicy.Source.valueOf(source)));
        posting.put("glNo", glNo);
        posting.put("documentNo", documentNo);
        posting.put("sourceEntity", sourceEntity);
        posting.put("sourceId", sourceId);
        ctx.changes().insert(JournalEntities.POSTING, posting);
        ctx.put(OUTPUT, new PostOutput(glNo, transactionId, ready.period().get("periodKey")));
    }

    /** The periods holding {@code date}, for {@link #periodRefusal}. */
    public static com.jabiz.query.EntityQuery periodsOn(LocalDate date) {
        return JournalProcesses.periodsOf(date);
    }

    /**
     * Why a subledger document may not be dated {@code date}, given the periods holding it: no period, or the general
     * ledger or the subledger closed for it. Empty when it may (FIN-PC-003).
     */
    public static java.util.Optional<Violation> periodRefusal(List<EntityInstance> periods, String source,
        LocalDate date, String field) {
        EntityInstance period = periods.stream()
            .filter(p -> !Boolean.TRUE.equals(p.get("opening")) && !Boolean.TRUE.equals(p.get("adjustment")))
            .findFirst().orElse(null);
        if (period == null) {
            return java.util.Optional.of(new Violation(field, JournalProcesses.NO_PERIOD, "No fiscal period holds "
                + date, Map.of("date", date.toString())));
        }
        PeriodPolicy.Source subledger = PeriodPolicy.Source.valueOf(source);
        return PeriodPolicy.check(JournalProcesses.state(period, subledger), subledger, false, false)
            .map(r -> new Violation(field, r.code(), r.message(),
                Map.of("periodKey", (Object) period.get("periodKey"))));
    }

    /** The general ledger series of a subledger (design section 4.5). */
    static String postingSource(PeriodPolicy.Source source) {
        return switch (source) {
            case AR -> "AR";
            case AP -> "AP";
            case BANK -> "BK";
            case FA -> "FA";
            case GL -> "MAN";
        };
    }

    private static Set<String> values(ProcessContext ctx, String what) {
        Set<String> values = new HashSet<>();
        for (JournalProcesses.LineInput line : input(ctx).lines()) {
            String value = switch (what) {
                case "account" -> line.accountCode();
                case "department" -> line.department();
                default -> line.location();
            };
            if (value != null && !value.isBlank()) {
                values.add(value.trim());
            }
        }
        return values;
    }

    private static PostInput input(ProcessContext ctx) {
        return ctx.get(INPUT, PostInput.class);
    }

    private static ReverseInput reverseInput(ProcessContext ctx) {
        return ctx.get(INPUT, ReverseInput.class);
    }

    private SubledgerPosting() {}
}
