package com.jabiz.finance.gl;

import com.jabiz.approval.ContentHash;
import com.jabiz.entity.Violation;
import com.jabiz.finance.FinancePermissions;
import com.jabiz.finance.calc.BookingTime;
import com.jabiz.finance.calc.PeriodPolicy;
import com.jabiz.ledger.Direction;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.approval.ApprovalCase;
import com.jabiz.runtime.approval.ApprovalOutcome;
import com.jabiz.runtime.approval.RequireApproval;
import com.jabiz.runtime.approval.WithdrawApproval;
import com.jabiz.runtime.ledger.LedgerEntities;
import com.jabiz.runtime.ledger.LedgerProcesses;
import com.jabiz.runtime.numbering.AssignNumber;
import com.jabiz.runtime.process.steps.CallProcess;
import com.jabiz.runtime.process.steps.LoadEntity;
import com.jabiz.runtime.process.steps.QueryEntities;
import com.jabiz.runtime.process.steps.SaveChanges;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static com.jabiz.finance.gl.AccountProcesses.list;
import static com.jabiz.finance.gl.JournalEntities.APPROVED;
import static com.jabiz.finance.gl.JournalEntities.DRAFT;
import static com.jabiz.finance.gl.JournalEntities.JOURNAL;
import static com.jabiz.finance.gl.JournalEntities.JOURNAL_DATASET;
import static com.jabiz.finance.gl.JournalEntities.LINE;
import static com.jabiz.finance.gl.JournalEntities.LINE_DATASET;
import static com.jabiz.finance.gl.JournalEntities.POSTED;
import static com.jabiz.finance.gl.JournalEntities.REJECTED;
import static com.jabiz.finance.gl.JournalEntities.SUBMITTED;

/**
 * The life of a journal entry (FIN-GL-010 … 015, FIN-CT-001, 003; docs/finance/00-design.md section 6.3).
 * <ul>
 *   <li>{@code FIN_JOURNAL_SAVE}: a new draft, or a change of one; changing a submitted or approved entry returns it
 *       to draft and withdraws its approval request. Only the preparer changes an entry; a posted one never changes.
 *       </li>
 *   <li>{@code FIN_JOURNAL_DELETE}: a draft without a number goes (a numbered one stays: its number is used).</li>
 *   <li>{@code FIN_JOURNAL_SUBMIT}: all checks at once, then the entry number (JE-0001, per fiscal year), then the
 *       approval rules; an entry no rule stops posts at once, others wait for their approvers.</li>
 *   <li>{@code FIN_JOURNAL_APPROVAL_RESULT}: run on the platform's approval events; an approval of the content the
 *       entry still has posts it, a rejection marks it rejected.</li>
 *   <li>{@code FIN_JOURNAL_POST}: internal; numbers the posting (GJ-MAN-2026-000001, per fiscal year and source),
 *       posts the ledger transaction and records the posting with its period.</li>
 *   <li>{@code FIN_JOURNAL_GRANT_CONTROL_EXCEPTION}: the controller lets this content of an entry post to control
 *       accounts (FIN-GL-005); any change of the lines voids it.</li>
 *   <li>{@code FIN_JOURNAL_REVERSE}: a new entry with the opposite amounts that refers to the posted one, submitted
 *       like any other (FIN-GL-012).</li>
 * </ul>
 * The approval binds the content (dates, description, flags and lines) through its hash: a change after approval
 * makes the approval worthless, as the posting compares the hashes (FIN-CT-003).
 */
public final class JournalProcesses {

    public static final String SAVE = "FIN_JOURNAL_SAVE";
    public static final String DELETE = "FIN_JOURNAL_DELETE";
    public static final String SUBMIT = "FIN_JOURNAL_SUBMIT";
    public static final String POST = "FIN_JOURNAL_POST";
    public static final String APPROVAL_RESULT = "FIN_JOURNAL_APPROVAL_RESULT";
    public static final String GRANT_CONTROL_EXCEPTION = "FIN_JOURNAL_GRANT_CONTROL_EXCEPTION";
    public static final String REVERSE = "FIN_JOURNAL_REVERSE";

    /** The approval subject of journal entries and its facts. */
    public static final String SUBJECT = "fin.journal";
    /** Entry numbers JE-0001 …, per fiscal year (design section 4.5). */
    public static final String JOURNAL_NUMBERS = "fin.journal";
    /** General ledger numbers GJ-MAN-2026-000001 …, per source and fiscal year (FIN-GL-013). */
    public static final String GL_NUMBERS = "fin.gl";
    /** What a ledger transaction names as its source document. */
    public static final String SOURCE_ENTITY = JOURNAL;

    public static final String NOT_FOUND = "FIN_JOURNAL_NOT_FOUND";
    public static final String IS_POSTED = "FIN_JOURNAL_POSTED";
    public static final String NOT_PREPARER = "FIN_JOURNAL_NOT_PREPARER";
    public static final String NOT_SUBMITTABLE = "FIN_JOURNAL_NOT_SUBMITTABLE";
    public static final String NUMBERED = "FIN_JOURNAL_NUMBERED";
    public static final String OWN_EXCEPTION = "FIN_JOURNAL_OWN_EXCEPTION";
    public static final String NOT_POSTED = "FIN_JOURNAL_NOT_POSTED";
    public static final String ALREADY_REVERSED = "FIN_JOURNAL_ALREADY_REVERSED";
    public static final String OPENING_NOT_REVERSED = "FIN_JOURNAL_OPENING_NOT_REVERSED";
    public static final String NO_PERIOD = "FIN_JOURNAL_NO_PERIOD";
    public static final String AUTO_REVERSE_DATE = "FIN_JOURNAL_AUTO_REVERSE_DATE";
    public static final String ADJUSTMENT_PERIOD = "FIN_JOURNAL_ADJUSTMENT_PERIOD";
    public static final String REVERSAL_LINES = "FIN_JOURNAL_REVERSAL_LINES";

    // ---- inputs and outputs ---------------------------------------------------------------------------------------

    public record LineInput(String accountCode, BigDecimal debit, BigDecimal credit, @Size(max = 200) String memo,
        @Size(max = 20) String department, @Size(max = 20) String location) {}

    /**
     * @param journalId        absent: a new draft
     * @param documentDate     the posting date when absent
     * @param adjusting        an adjusting entry (it may go into a soft-closed period, FIN-PC-003)
     * @param adjustmentPeriod into period 13 of the year (with {@code adjusting}, dated in December)
     * @param autoReverseDate  when the entry is reversed automatically (FIN-GL-018)
     */
    public record JournalInput(UUID journalId, @NotNull LocalDate postingDate, LocalDate documentDate,
        @NotBlank @Size(max = 500) String description, Boolean adjusting, Boolean adjustmentPeriod,
        LocalDate autoReverseDate,
        @NotNull @Size(max = JournalValidator.MAX_LINES) List<@Valid @NotNull LineInput> lines) {}

    public record JournalId(@NotNull UUID journalId) {}

    public record ExceptionInput(@NotNull UUID journalId, @NotBlank @Size(max = 500) String reason) {}

    /** @param description of the reversal; "Reversal of JE-0001" when absent */
    public record ReverseInput(@NotNull UUID journalId, @NotNull LocalDate postingDate,
        @Size(max = 500) String description) {}

    /**
     * @param approval   {@code NOT_REQUIRED}, {@code PENDING} or {@code APPROVED}; null until submitted
     * @param glNo       once posted
     */
    public record JournalOutput(String journalId, String journalNo, String status, BigDecimal totalDebit,
        BigDecimal totalCredit, String approval, String approvalRequestId, String glNo, String transactionId) {}

    /** Input of the internal posting: the entry and the content its approval was given for. */
    public record PostInput(@NotNull UUID journalId, @NotBlank String contentHash) {}

    /** @param reason why it was not posted, when it was not */
    public record PostOutput(String journalId, boolean posted, String glNo, String transactionId, String reason) {}

    /** The platform's approval decision, as its events carry it. */
    public record ApprovalResultInput(String subject, String entityId, String status, String contentHash,
        String requestId) {}

    // ---- context keys ---------------------------------------------------------------------------------------------

    static final String INPUT = "input";
    static final String JOURNAL_ID = "journalId";
    static final String JOURNALS = "journals";
    static final String JOURNAL_KEY = "journal";
    static final String LINES = "lines";
    static final String FIN_ACCOUNTS = "finAccounts";
    static final String LEDGER_ACCOUNTS = "ledgerAccounts";
    static final String DEPARTMENTS = "departments";
    static final String LOCATIONS = "locations";
    static final String PERIODS = "periods";
    static final String REVERSALS = "reversals";
    static final String PREPARED = "prepared";
    static final String JE_NO = "journalNo";
    static final String APPROVAL = "approval";
    static final String POST_INPUT = "postInput";
    static final String POSTED_OUTPUT = "postedOutput";
    static final String SUBMIT_OUTPUT = "submitOutput";
    static final String READY = "ready";
    static final String GL_NO = "glNo";
    static final String LEDGER_INPUT = "ledgerInput";
    static final String LEDGER_OUTPUT = "ledgerOutput";
    static final String OUTPUT = "output";

    /** Most lines a query of one entry reads: every line of the largest entry, and one more. */
    static final int LINE_LIMIT = JournalValidator.MAX_LINES + 1;

    // ---- the processes --------------------------------------------------------------------------------------------

    public static final ProcessDefinition<JournalInput, JournalOutput, ProcessContext> SAVE_PROCESS =
        ProcessDefinition.define(SAVE, 1, JournalInput.class, JournalOutput.class, ProcessContext.class, pb -> pb
            .description("Saves a journal entry as a draft; a change of a submitted entry returns it to draft.")
            .permissions(FinancePermissions.JOURNAL_PREPARE)
            .contextFactory((start, input) -> {
                ProcessContext ctx = new ProcessContext(start);
                ctx.put(INPUT, input);
                return ctx;
            })
            .outputMapper(ctx -> ctx.get(OUTPUT, JournalOutput.class))
            .step("Load the entry", QueryEntities.of(JOURNAL_DATASET,
                ctx -> byIds(ctx.get(INPUT, JournalInput.class).journalId()), JOURNALS))
            .step("Load its lines", QueryEntities.of(LINE_DATASET,
                ctx -> linesOf(ctx.get(INPUT, JournalInput.class).journalId()), LINES))
            .compute("Save the draft", (metadata, ctx) -> save(ctx))
            // A pending approval of the old content is of no use any more; its tasks go too (FIN-GL-014).
            .step("Withdraw the approval request", WithdrawApproval.of(SUBJECT, JournalProcesses::approvalCase)));

    public static final ProcessDefinition<JournalId, JournalOutput, ProcessContext> DELETE_PROCESS =
        ProcessDefinition.define(DELETE, 1, JournalId.class, JournalOutput.class, ProcessContext.class, pb -> pb
            .description("Deletes a draft journal entry that has no number yet.")
            .permissions(FinancePermissions.JOURNAL_PREPARE)
            .contextFactory(JournalProcesses::withId)
            .outputMapper(ctx -> ctx.get(OUTPUT, JournalOutput.class))
            .step("Load the entry", LoadEntity.by(JOURNAL_DATASET, JOURNAL_ID, JOURNAL_KEY))
            .step("Load its lines", QueryEntities.of(LINE_DATASET, ctx -> linesOf(ctx.get(JOURNAL_ID)), LINES))
            .compute("Delete it", (metadata, ctx) -> delete(ctx))
            .step("Withdraw the approval request", WithdrawApproval.of(SUBJECT, JournalProcesses::approvalCase)));

    public static ProcessDefinition<JournalId, JournalOutput, ProcessContext> submitProcess(BookingTime booking) {
        return ProcessDefinition.define(SUBMIT, 1, JournalId.class, JournalOutput.class, ProcessContext.class, pb -> pb
            .description("Submits a journal entry: checks it, numbers it and posts it, or sends it for approval.")
            .permissions(FinancePermissions.JOURNAL_PREPARE)
            .actsOn(JOURNAL, "journalId", a -> a.whenField("status", DRAFT, REJECTED))
            .contextFactory(JournalProcesses::withId)
            .outputMapper(JournalProcesses::submitOutput)
            .step("Load the entry", LoadEntity.by(JOURNAL_DATASET, JOURNAL_ID, JOURNAL_KEY))
            .step("Load its lines", QueryEntities.of(LINE_DATASET, ctx -> linesOf(ctx.get(JOURNAL_ID)), LINES))
            .step("Load the accounts", QueryEntities.of(GlEntities.ACCOUNT_DATASET,
                ctx -> byCodes("accountCode", accountCodes(ctx)), FIN_ACCOUNTS))
            .step("Load the ledger accounts", QueryEntities.of(LedgerEntities.ACCOUNT_DATASET,
                ctx -> byCodes("accountCode", accountCodes(ctx)), LEDGER_ACCOUNTS))
            .step("Load the departments", QueryEntities.of(GlEntities.DEPARTMENT_DATASET,
                ctx -> byCodes("departmentCode", dimensionValues(ctx, "department")), DEPARTMENTS))
            .step("Load the locations", QueryEntities.of(GlEntities.LOCATION_DATASET,
                ctx -> byCodes("locationCode", dimensionValues(ctx, "location")), LOCATIONS))
            .step("Load the period", QueryEntities.of(GlEntities.PERIOD_DATASET,
                ctx -> periodsOf(ctx.get(JOURNAL_KEY, EntityInstance.class).get("postingDate")), PERIODS))
            .compute("Check the entry", (metadata, ctx) -> checkSubmission(ctx, booking))
            // Numbered only once all checks passed: a refused entry uses no number (FIN-GL-013).
            .step("Number the entry", AssignNumber.when(
                ctx -> ctx.contains(PREPARED) && ctx.get(PREPARED, Prepared.class).journalNo() == null,
                JOURNAL_NUMBERS, ctx -> String.valueOf((Object) ctx.get(PREPARED, Prepared.class).fiscalYear()), JE_NO))
            .step("Apply the approval rules", RequireApproval.when(ctx -> ctx.contains(PREPARED), SUBJECT,
                ctx -> ctx.get(PREPARED, Prepared.class).approvalCase(), APPROVAL))
            .compute("Record the submission", (metadata, ctx) -> recordSubmission(ctx))
            .step("Save", SaveChanges.now())
            .step("Post it", CallProcess.when(ctx -> ctx.contains(POST_INPUT), POST, 1,
                ctx -> ctx.get(POST_INPUT), POSTED_OUTPUT)));
    }

    public static ProcessDefinition<PostInput, PostOutput, ProcessContext> postProcess(BookingTime booking) {
        return ProcessDefinition.define(POST, 1, PostInput.class, PostOutput.class, ProcessContext.class, pb -> pb
            .description("Posts an approved journal entry to the general ledger; run by the journal processes.")
            .permissions(FinancePermissions.JOURNAL_POST)
            .internal()
            .contextFactory((start, input) -> {
                ProcessContext ctx = new ProcessContext(start);
                ctx.put(INPUT, input);
                ctx.put(JOURNAL_ID, input.journalId());
                return ctx;
            })
            .outputMapper(ctx -> ctx.get(OUTPUT, PostOutput.class))
            .step("Load the entry", LoadEntity.by(JOURNAL_DATASET, JOURNAL_ID, JOURNAL_KEY))
            .step("Load its lines", QueryEntities.of(LINE_DATASET, ctx -> linesOf(ctx.get(JOURNAL_ID)), LINES))
            .step("Load the period", QueryEntities.of(GlEntities.PERIOD_DATASET,
                ctx -> periodsOf(ctx.get(JOURNAL_KEY, EntityInstance.class).get("postingDate")), PERIODS))
            // The accounts may have changed since submission: what the ledger would refuse is not posted.
            .step("Load the accounts", QueryEntities.of(GlEntities.ACCOUNT_DATASET,
                ctx -> byCodes("accountCode", accountCodes(ctx, LINES)), FIN_ACCOUNTS))
            .step("Load the ledger accounts", QueryEntities.of(LedgerEntities.ACCOUNT_DATASET,
                ctx -> byCodes("accountCode", accountCodes(ctx, LINES)), LEDGER_ACCOUNTS))
            .step("Load the departments", QueryEntities.of(GlEntities.DEPARTMENT_DATASET,
                ctx -> byCodes("departmentCode", dimensionValues(ctx, LINES, "department")), DEPARTMENTS))
            .step("Load the locations", QueryEntities.of(GlEntities.LOCATION_DATASET,
                ctx -> byCodes("locationCode", dimensionValues(ctx, LINES, "location")), LOCATIONS))
            .compute("Check it is still the approved entry", (metadata, ctx) -> checkPosting(ctx, booking))
            .step("Number the posting", AssignNumber.when(ctx -> ctx.contains(READY), GL_NUMBERS,
                ctx -> ctx.get(READY, Ready.class).glScope(), GL_NO))
            .step("Post to the ledger", CallProcess.when(ctx -> ctx.contains(READY), LedgerProcesses.POST, 1,
                ctx -> ctx.get(READY, Ready.class).ledger(), LEDGER_OUTPUT))
            .compute("Record the posting", (metadata, ctx) -> recordPosting(ctx)));
    }

    public static final ProcessDefinition<ApprovalResultInput, PostOutput, ProcessContext> APPROVAL_RESULT_PROCESS =
        ProcessDefinition.define(APPROVAL_RESULT, 1, ApprovalResultInput.class, PostOutput.class,
            ProcessContext.class, pb -> pb
                .description("Posts a journal entry its approvers approved, or marks it rejected.")
                .permissions(FinancePermissions.JOURNAL_POST)
                .internal()
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put(INPUT, input);
                    return ctx;
                })
                .outputMapper(ctx -> ctx.contains(POSTED_OUTPUT) ? ctx.get(POSTED_OUTPUT, PostOutput.class)
                    : ctx.get(OUTPUT, PostOutput.class))
                .step("Load the entry", QueryEntities.of(JOURNAL_DATASET, ctx -> {
                    ApprovalResultInput input = ctx.get(INPUT, ApprovalResultInput.class);
                    // Approvals of other subjects are none of this process's business.
                    return SUBJECT.equals(input.subject()) ? byIds(uuid(input.entityId())) : byIds(null);
                }, JOURNALS))
                .compute("Apply the decision", (metadata, ctx) -> applyDecision(ctx))
                .step("Save", SaveChanges.now())
                .step("Post it", CallProcess.when(ctx -> ctx.contains(POST_INPUT), POST, 1,
                    ctx -> ctx.get(POST_INPUT), POSTED_OUTPUT)));

    public static final ProcessDefinition<ExceptionInput, JournalOutput, ProcessContext> EXCEPTION_PROCESS =
        ProcessDefinition.define(GRANT_CONTROL_EXCEPTION, 1, ExceptionInput.class, JournalOutput.class,
            ProcessContext.class, pb -> pb
                .description("Lets this content of a journal entry post to control accounts, with the reason.")
                .permissions(FinancePermissions.JOURNAL_CONTROL_EXCEPTION)
                .actsOn(JOURNAL, "journalId", a -> a.whenField("status", DRAFT, REJECTED))
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put(INPUT, input);
                    ctx.put(JOURNAL_ID, input.journalId());
                    return ctx;
                })
                .outputMapper(ctx -> ctx.get(OUTPUT, JournalOutput.class))
                .step("Load the entry", LoadEntity.by(JOURNAL_DATASET, JOURNAL_ID, JOURNAL_KEY))
                .step("Load its lines", QueryEntities.of(LINE_DATASET, ctx -> linesOf(ctx.get(JOURNAL_ID)),
                    LINES))
                .compute("Grant the exception", (metadata, ctx) -> grantException(ctx)));

    public static final ProcessDefinition<ReverseInput, JournalOutput, ProcessContext> REVERSE_PROCESS =
        ProcessDefinition.define(REVERSE, 1, ReverseInput.class, JournalOutput.class, ProcessContext.class,
            pb -> pb
                .description("Reverses a posted journal entry with an entry of opposite amounts that refers to it.")
                .permissions(FinancePermissions.JOURNAL_PREPARE)
                .actsOn(JOURNAL, "journalId", a -> a.whenField("status", POSTED))
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put(INPUT, input);
                    ctx.put(JOURNAL_ID, input.journalId());
                    return ctx;
                })
                .outputMapper(ctx -> ctx.get(SUBMIT_OUTPUT, JournalOutput.class))
                .step("Load the entry", LoadEntity.by(JOURNAL_DATASET, JOURNAL_ID, JOURNAL_KEY))
                .step("Load its lines", QueryEntities.of(LINE_DATASET, ctx -> linesOf(ctx.get(JOURNAL_ID)),
                    LINES))
                .step("Look for a reversal", QueryEntities.of(JOURNAL_DATASET, ctx -> EntityQuery.builder()
                    .where(new QueryPredicate.Eq("reversesJournalId", ctx.get(JOURNAL_ID))).limit(1).build(),
                    REVERSALS))
                .compute("Draft the reversal", (metadata, ctx) -> {
                    ReverseInput input = ctx.get(INPUT, ReverseInput.class);
                    EntityInstance original = ctx.get(JOURNAL_KEY, EntityInstance.class);
                    if (!POSTED.equals(original.get("status"))) {
                        ctx.reject(refusal("journalId", NOT_POSTED, "Only a posted entry is reversed", original));
                        return;
                    }
                    if (JournalEntities.OPENING.equals(original.get("source"))) {
                        // The opening balances are the migration's record, reconciled to the source; a mistake in
                        // them is corrected by an adjusting entry in the first year, never by undoing the opening.
                        ctx.reject(refusal("journalId", OPENING_NOT_REVERSED, "The opening entry is not reversed; "
                            + "correct it with an entry in the first year", original));
                        return;
                    }
                    if (!list(ctx, REVERSALS).isEmpty()) {
                        ctx.reject(refusal("journalId", ALREADY_REVERSED, "Entry " + original.get("journalNo")
                            + " is reversed already", original));
                        return;
                    }
                    String description = input.description() != null && !input.description().isBlank()
                        ? input.description().trim() : "Reversal of " + original.get("journalNo");
                    Object id = insertReversal(ctx, original, list(ctx, LINES), JournalEntities.REVERSING,
                        input.postingDate(), description, null, DRAFT, ctx.request().actorId());
                    ctx.changes().update(JOURNAL, original.id(), original.version(), Map.of("reversedById", id));
                    ctx.put(POST_INPUT, new JournalId(UUID.fromString(String.valueOf(id))));
                })
                .step("Save", SaveChanges.now())
                .step("Submit it", CallProcess.when(ctx -> ctx.contains(POST_INPUT), SUBMIT, 1,
                    ctx -> ctx.get(POST_INPUT), SUBMIT_OUTPUT)));

    // ---- save and delete ------------------------------------------------------------------------------------------

    static void save(ProcessContext ctx) {
        JournalInput input = ctx.get(INPUT, JournalInput.class);
        List<JournalValidator.Line> lines = input.lines().stream().map(JournalProcesses::line).toList();
        for (Violation problem : JournalValidator.checkLines(lines)) {
            ctx.reject(problem);
        }
        if (input.autoReverseDate() != null && !input.autoReverseDate().isAfter(input.postingDate())) {
            ctx.reject(new Violation("autoReverseDate", AUTO_REVERSE_DATE, "An entry is reversed after its posting "
                + "date " + input.postingDate(), Map.of("postingDate", input.postingDate().toString())));
        }
        if (Boolean.TRUE.equals(input.adjustmentPeriod()) && !Boolean.TRUE.equals(input.adjusting())) {
            ctx.reject(new Violation("adjustmentPeriod", ADJUSTMENT_PERIOD, "Only an adjusting entry goes into "
                + "the adjustment period", Map.of()));
        }
        EntityInstance journal = null;
        if (input.journalId() != null) {
            journal = list(ctx, JOURNALS).isEmpty() ? null : list(ctx, JOURNALS).getFirst();
            if (journal == null) {
                ctx.reject(new Violation("journalId", NOT_FOUND, "There is no entry " + input.journalId(),
                    Map.of()));
                return;
            }
            checkChangeable(ctx, journal);
            // A reversal mirrors its original: its dates and description may change, its lines may not.
            if (journal.get("reversesJournalId") != null && !sameLines(lines,
                list(ctx, LINES).stream().map(JournalProcesses::line).toList())) {
                ctx.reject(refusal("lines", REVERSAL_LINES, "The lines of a reversal are those of the entry it "
                    + "reverses, on the opposite sides", journal));
            }
        }
        if (ctx.hasViolations()) {
            return;
        }
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("postingDate", input.postingDate());
        header.put("documentDate", input.documentDate() != null ? input.documentDate() : input.postingDate());
        header.put("description", input.description().trim());
        header.put("adjusting", Boolean.TRUE.equals(input.adjusting()));
        header.put("adjustmentPeriod", Boolean.TRUE.equals(input.adjustmentPeriod()));
        header.put("autoReverseDate", input.autoReverseDate());
        JournalValidator.Totals totals = JournalValidator.totals(lines);
        header.put("totalDebit", totals.debit());
        header.put("totalCredit", totals.credit());
        header.put("status", DRAFT);
        Object id;
        if (journal == null) {
            header.put("source", JournalEntities.MANUAL);
            header.put("preparer", ctx.request().actorId());
            id = ctx.changes().insert(JOURNAL, header);
        } else {
            id = journal.id();
            String source = journal.get("source");
            // The approval and the exception were given for the old content; they do not carry over.
            header.put("contentHash", null);
            header.put("approvalRequestId", null);
            Map<String, Object> hashed = new LinkedHashMap<>(header);
            hashed.put("reversesJournalId", journal.get("reversesJournalId"));
            String newHash = contentHash(hashed, source, lineMaps(lines));
            if (!newHash.equals(journal.get("exceptionHash"))) {
                header.put("exceptionBy", null);
                header.put("exceptionTime", null);
                header.put("exceptionReason", null);
                header.put("exceptionHash", null);
            }
            ctx.changes().update(JOURNAL, id, journal.version(), header);
            for (EntityInstance old : list(ctx, LINES)) {
                ctx.changes().delete(LINE, old.id(), old.version());
            }
        }
        insertLines(ctx, id, lines);
        ctx.put(JOURNAL_ID, id);
        ctx.put(OUTPUT, new JournalOutput(String.valueOf(id), journal == null ? null : journal.get("journalNo"),
            DRAFT, totals.debit(), totals.credit(), null, null, null, null));
    }

    static void delete(ProcessContext ctx) {
        EntityInstance journal = ctx.get(JOURNAL_KEY, EntityInstance.class);
        checkChangeable(ctx, journal);
        if (journal.get("journalNo") != null) {
            ctx.reject(refusal("journalId", NUMBERED, "Entry " + journal.get("journalNo")
                + " has its number and cannot be deleted; change and submit it again instead", journal));
        }
        if (ctx.hasViolations()) {
            return;
        }
        for (EntityInstance line : list(ctx, LINES)) {
            ctx.changes().delete(LINE, line.id(), line.version());
        }
        ctx.changes().delete(JOURNAL, journal.id(), journal.version());
        ctx.put(OUTPUT, output(journal, journal.get("status"), null, null, null));
    }

    /** Only the preparer changes an entry, and never once it is posted. */
    private static void checkChangeable(ProcessContext ctx, EntityInstance journal) {
        if (POSTED.equals(journal.get("status"))) {
            ctx.reject(refusal("journalId", IS_POSTED, "Entry " + journal.get("journalNo")
                + " is posted and never changes; reverse it or post an adjusting entry", journal));
        } else if (!Objects.equals(journal.get("preparer"), ctx.request().actorId())) {
            ctx.reject(refusal("journalId", NOT_PREPARER, "Only the preparer changes this entry", journal));
        }
    }

    // ---- submit ---------------------------------------------------------------------------------------------------

    /** What the submission checked and goes on with. */
    record Prepared(EntityInstance journal, String journalNo, int fiscalYear, String periodKey, String contentHash,
        ApprovalCase approvalCase) {}

    static void checkSubmission(ProcessContext ctx, BookingTime booking) {
        EntityInstance journal = ctx.get(JOURNAL_KEY, EntityInstance.class);
        String status = journal.get("status");
        if (!DRAFT.equals(status) && !REJECTED.equals(status)) {
            ctx.reject(refusal("journalId", NOT_SUBMITTABLE, "Entry " + journal.get("journalNo") + " is " + status
                + ": only a draft or a rejected entry is submitted", journal));
            return;
        }
        if (!Objects.equals(journal.get("preparer"), ctx.request().actorId())) {
            ctx.reject(refusal("journalId", NOT_PREPARER, "Only the preparer submits this entry", journal));
            return;
        }
        List<EntityInstance> lineRows = list(ctx, LINES);
        List<JournalValidator.Line> lines = lineRows.stream().map(JournalProcesses::line).toList();
        String hash = contentHash(journal, lineRows);
        boolean exception = hash.equals(journal.get("exceptionHash"));
        for (Violation problem : JournalValidator.checkForPosting(lines, accounts(ctx), dimensions(ctx), exception)) {
            ctx.reject(problem);
        }
        boolean adjusting = Boolean.TRUE.equals(journal.get("adjusting"));
        EntityInstance period = period(ctx, Boolean.TRUE.equals(journal.get("adjustmentPeriod")));
        if (period == null) {
            String date = String.valueOf((Object) journal.get("postingDate"));
            ctx.reject(new Violation("postingDate", NO_PERIOD, "No fiscal period holds " + date
                + (Boolean.TRUE.equals(journal.get("adjustmentPeriod")) ? " as an adjustment period" : ""),
                Map.of("postingDate", date)));
        } else {
            PeriodPolicy.check(state(period, PeriodPolicy.Source.GL), PeriodPolicy.Source.GL, adjusting,
                ctx.request().hasPermission(FinancePermissions.PERIOD_CLOSE))
                .ifPresent(refusal -> ctx.reject(new Violation("postingDate", refusal.code(), refusal.message(),
                    Map.of("periodKey", period.<String>get("periodKey")))));
        }
        if (ctx.hasViolations()) {
            return;
        }
        int fiscalYear = period.<BigDecimal>get("fiscalYear").intValueExact();
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("amount", journal.get("totalDebit"));
        facts.put("source", journal.get("source"));
        facts.put("manual", !JournalEntities.AUTO_REVERSING.equals(journal.get("source")));
        // Recorded after the period ended: the rules may ask more of a late entry (FIN-GL-015).
        LocalDate recorded = booking.dateOf(ctx.opTime());
        facts.put("afterPeriodEnd", recorded.isAfter(period.get("endDate")));
        ApprovalCase approvalCase = ApprovalCase.of(journal.id(), facts, content(journal, lineRows));
        ctx.put(PREPARED, new Prepared(journal, journal.get("journalNo"), fiscalYear, period.get("periodKey"), hash,
            approvalCase));
    }

    static void recordSubmission(ProcessContext ctx) {
        if (!ctx.contains(PREPARED)) {
            return;
        }
        Prepared prepared = ctx.get(PREPARED, Prepared.class);
        ApprovalOutcome approval = ctx.get(APPROVAL, ApprovalOutcome.class);
        String journalNo = prepared.journalNo() != null ? prepared.journalNo() : ctx.get(JE_NO, String.class);
        String status = approval.mayProceed() ? APPROVED : SUBMITTED;
        Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("status", status);
        changes.put("journalNo", journalNo);
        changes.put("periodKey", prepared.periodKey());
        changes.put("fiscalYear", BigDecimal.valueOf(prepared.fiscalYear()));
        changes.put("contentHash", prepared.contentHash());
        changes.put("approvalRequestId", approval.requestId());
        EntityInstance journal = prepared.journal();
        ctx.changes().update(JOURNAL, journal.id(), journal.version(), changes);
        if (approval.mayProceed()) {
            ctx.put(POST_INPUT, new PostInput(UUID.fromString(String.valueOf(journal.id())), prepared.contentHash()));
        }
        ctx.put(OUTPUT, new JournalOutput(String.valueOf(journal.id()), journalNo, status, journal.get("totalDebit"),
            journal.get("totalCredit"), approval.status().name(), approval.requestId(), null, null));
    }

    private static JournalOutput submitOutput(ProcessContext ctx) {
        JournalOutput submitted = ctx.get(OUTPUT, JournalOutput.class);
        if (!ctx.contains(POSTED_OUTPUT)) {
            return submitted;
        }
        PostOutput posted = ctx.get(POSTED_OUTPUT, PostOutput.class);
        return new JournalOutput(submitted.journalId(), submitted.journalNo(),
            posted.posted() ? POSTED : submitted.status(), submitted.totalDebit(), submitted.totalCredit(),
            submitted.approval(), submitted.approvalRequestId(), posted.glNo(), posted.transactionId());
    }

    // ---- post -----------------------------------------------------------------------------------------------------

    /** A posting about to be made: its ledger input, the scope of its number and its period. */
    record Ready(LedgerProcesses.PostInput ledger, String glScope, EntityInstance period) {}

    static void checkPosting(ProcessContext ctx, BookingTime booking) {
        PostInput input = ctx.get(INPUT, PostInput.class);
        EntityInstance journal = ctx.get(JOURNAL_KEY, EntityInstance.class);
        List<EntityInstance> lines = list(ctx, LINES);
        String status = journal.get("status");
        String reason = null;
        if (POSTED.equals(status)) {
            reason = "posted already";
        } else if (!APPROVED.equals(status)) {
            reason = "it is " + status;
        } else if (!input.contentHash().equals(journal.get("contentHash"))
            || !input.contentHash().equals(contentHash(journal, lines))) {
            // Changed since it was approved: the approval was for other content (FIN-CT-003).
            reason = "it changed since it was approved";
        }
        if (reason == null) {
            // The control-account rule belongs to submission; here only what the ledger would refuse.
            List<Violation> problems = JournalValidator.checkForPosting(
                lines.stream().map(JournalProcesses::line).toList(), accounts(ctx), dimensions(ctx), true);
            if (!problems.isEmpty()) {
                reason = String.join("; ", problems.stream().map(Violation::message).toList());
            }
        }
        EntityInstance period = period(ctx, Boolean.TRUE.equals(journal.get("adjustmentPeriod")),
            JournalEntities.OPENING.equals(journal.get("source")));
        if (reason == null && period == null) {
            reason = "no fiscal period holds its date";
        }
        if (reason == null) {
            // The soft-close rule was applied at submission, where the preparer's permission is known.
            reason = PeriodPolicy.check(state(period, PeriodPolicy.Source.GL), PeriodPolicy.Source.GL, true, true)
                .map(PeriodPolicy.Refusal::message).orElse(null);
        }
        if (reason != null) {
            Object transactionId = journal.get("transactionId");
            ctx.put(OUTPUT, new PostOutput(String.valueOf(journal.id()), false, journal.get("glNo"),
                transactionId == null ? null : String.valueOf(transactionId), reason));
            return;
        }
        List<LedgerProcesses.Line> entries = new ArrayList<>();
        for (EntityInstance line : lines) {
            BigDecimal debit = line.get("debit");
            Map<String, String> dimensions = new LinkedHashMap<>();
            putIfPresent(dimensions, "department", line.get("department"));
            putIfPresent(dimensions, "location", line.get("location"));
            entries.add(new LedgerProcesses.Line(line.get("accountCode"),
                debit != null ? Direction.DEBIT : Direction.CREDIT, debit != null ? debit : line.get("credit"),
                line.get("memo"), dimensions.isEmpty() ? null : dimensions));
        }
        LedgerProcesses.PostInput ledger = new LedgerProcesses.PostInput(booking.of(journal.get("postingDate")),
            journal.get("description"), journal.get("journalNo"), entries, SOURCE_ENTITY,
            String.valueOf(journal.id()));
        int fiscalYear = period.<BigDecimal>get("fiscalYear").intValueExact();
        ctx.put(READY, new Ready(ledger, JournalEntities.postingSource(journal.get("source")) + "-" + fiscalYear,
            period));
    }

    static void recordPosting(ProcessContext ctx) {
        if (!ctx.contains(READY)) {
            return;
        }
        Ready ready = ctx.get(READY, Ready.class);
        EntityInstance journal = ctx.get(JOURNAL_KEY, EntityInstance.class);
        String glNo = ctx.get(GL_NO, String.class);
        String transactionId = ctx.get(LEDGER_OUTPUT, LedgerProcesses.PostOutput.class).transactionId();
        ctx.changes().update(JOURNAL, journal.id(), journal.version(), Map.of("status", POSTED, "glNo", glNo,
            "transactionId", UUID.fromString(transactionId)));
        Map<String, Object> posting = new LinkedHashMap<>();
        posting.put("transactionId", UUID.fromString(transactionId));
        posting.put("postingDate", journal.get("postingDate"));
        posting.put("fiscalYear", ready.period().get("fiscalYear"));
        posting.put("periodNo", ready.period().get("periodNo"));
        posting.put("periodKey", ready.period().get("periodKey"));
        posting.put("source", JournalEntities.postingSource(journal.get("source")));
        posting.put("glNo", glNo);
        posting.put("documentNo", journal.get("journalNo"));
        posting.put("sourceEntity", SOURCE_ENTITY);
        posting.put("sourceId", String.valueOf(journal.id()));
        ctx.changes().insert(JournalEntities.POSTING, posting);
        ctx.put(OUTPUT, new PostOutput(String.valueOf(journal.id()), true, glNo, transactionId, null));
    }

    // ---- approval results -----------------------------------------------------------------------------------------

    static void applyDecision(ProcessContext ctx) {
        ApprovalResultInput input = ctx.get(INPUT, ApprovalResultInput.class);
        List<EntityInstance> found = list(ctx, JOURNALS);
        EntityInstance journal = found.isEmpty() ? null : found.getFirst();
        String reason = null;
        if (journal == null) {
            reason = "not a journal entry";
        } else if (!SUBMITTED.equals(journal.get("status"))) {
            reason = "it is " + journal.get("status");
        } else if (!Objects.equals(input.requestId(), String.valueOf((Object) journal.get("approvalRequestId")))) {
            // A late decision of an earlier request: the entry waits for its current one.
            reason = "the decision was for another request";
        } else if (!Objects.equals(input.contentHash(), journal.get("contentHash"))) {
            reason = "the decision was for other content";
        }
        if (reason != null) {
            ctx.put(OUTPUT, new PostOutput(input.entityId(), false, null, null, reason));
            return;
        }
        boolean approved = APPROVED.equals(input.status());
        ctx.changes().update(JOURNAL, journal.id(), journal.version(),
            Map.of("status", approved ? APPROVED : REJECTED));
        if (approved) {
            ctx.put(POST_INPUT, new PostInput(UUID.fromString(String.valueOf(journal.id())), input.contentHash()));
        } else {
            ctx.put(OUTPUT, new PostOutput(String.valueOf(journal.id()), false, null, null, "rejected"));
        }
    }

    // ---- control-account exception --------------------------------------------------------------------------------

    static void grantException(ProcessContext ctx) {
        ExceptionInput input = ctx.get(INPUT, ExceptionInput.class);
        EntityInstance journal = ctx.get(JOURNAL_KEY, EntityInstance.class);
        String status = journal.get("status");
        if (!DRAFT.equals(status) && !REJECTED.equals(status)) {
            ctx.reject(refusal("journalId", NOT_SUBMITTABLE, "Entry " + journal.get("journalNo") + " is " + status
                + "; an exception is granted before it is submitted", journal));
            return;
        }
        // The controller who prepared the entry does not grant its exception (FIN-CT-001).
        if (Objects.equals(journal.get("preparer"), ctx.request().actorId())) {
            ctx.reject(refusal("journalId", OWN_EXCEPTION, "The preparer does not grant the exception of their "
                + "own entry", journal));
            return;
        }
        Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("exceptionBy", ctx.request().actorId());
        changes.put("exceptionTime", ctx.opTime());
        changes.put("exceptionReason", input.reason().trim());
        changes.put("exceptionHash", contentHash(journal, list(ctx, LINES)));
        ctx.changes().update(JOURNAL, journal.id(), journal.version(), changes);
        ctx.put(OUTPUT, output(journal, status, null, null, null));
    }

    // ---- shared helpers -------------------------------------------------------------------------------------------

    /**
     * Inserts the reversal of {@code original}: the same lines on the opposite sides, referring to it (FIN-GL-012).
     */
    static Object insertReversal(ProcessContext ctx, EntityInstance original, List<EntityInstance> lines,
        String source, LocalDate postingDate, String description, String journalNo, String status, String preparer) {
        return insertReversal(ctx, original, lines, source, postingDate, description, journalNo, status, preparer,
            Map.of());
    }

    /** As above, with further header fields (the period of an entry that skips submission). */
    static Object insertReversal(ProcessContext ctx, EntityInstance original, List<EntityInstance> lines,
        String source, LocalDate postingDate, String description, String journalNo, String status, String preparer,
        Map<String, Object> extra) {
        List<JournalValidator.Line> reversed = new ArrayList<>();
        for (EntityInstance line : lines.stream()
            .sorted(java.util.Comparator.comparing(l -> l.<BigDecimal>get("lineNo"))).toList()) {
            reversed.add(new JournalValidator.Line(line.get("accountCode"), line.get("credit"), line.get("debit"),
                line.get("memo"), line.get("department"), line.get("location")));
        }
        JournalValidator.Totals totals = JournalValidator.totals(reversed);
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("postingDate", postingDate);
        header.put("documentDate", postingDate);
        header.put("description", description);
        header.put("source", source);
        header.put("status", status);
        header.put("preparer", preparer);
        header.put("adjusting", Boolean.TRUE.equals(original.get("adjusting")));
        header.put("adjustmentPeriod", false);
        header.put("reversesJournalId", original.id());
        header.put("totalDebit", totals.debit());
        header.put("totalCredit", totals.credit());
        header.put("journalNo", journalNo);
        header.putAll(extra);
        if (journalNo != null) {
            header.put("contentHash", contentHash(header, source, lineMaps(reversed)));
        }
        Object id = ctx.changes().insert(JOURNAL, header);
        insertLines(ctx, id, reversed);
        return id;
    }

    /**
     * A new draft made by a process rather than typed (an import): the header as {@code FIN_JOURNAL_SAVE} would
     * store it, with its source, its external reference and, if given, the number it keeps; {@code exceptionReason}
     * records a standing control-account exception for exactly this content (FIN-GL-005). Returns its key.
     */
    public static Object insertDraft(ProcessContext ctx, LocalDate postingDate, LocalDate documentDate,
        String description, String source, String externalRef, String journalNo, List<JournalValidator.Line> lines,
        String exceptionBy, String exceptionReason) {
        JournalValidator.Totals totals = JournalValidator.totals(lines);
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("postingDate", postingDate);
        header.put("documentDate", documentDate != null ? documentDate : postingDate);
        header.put("description", description.trim());
        header.put("adjusting", false);
        header.put("adjustmentPeriod", false);
        if (exceptionReason != null) {
            header.put("exceptionHash", contentHash(header, source, lineMaps(lines)));
            header.put("exceptionBy", exceptionBy);
            header.put("exceptionTime", ctx.opTime());
            header.put("exceptionReason", exceptionReason);
        }
        header.put("source", source);
        header.put("status", DRAFT);
        header.put("preparer", ctx.request().actorId());
        header.put("totalDebit", totals.debit());
        header.put("totalCredit", totals.credit());
        header.put("externalRef", externalRef);
        header.put("journalNo", journalNo);
        Object id = ctx.changes().insert(JOURNAL, header);
        insertLines(ctx, id, lines);
        return id;
    }

    /** The journal entries imported from this document, if any (at most one). */
    public static EntityQuery byExternalRef(String externalRef) {
        return EntityQuery.builder().where(new QueryPredicate.Eq("externalRef", externalRef)).limit(1).build();
    }

    static void insertLines(ProcessContext ctx, Object journalId, List<JournalValidator.Line> lines) {
        for (int i = 0; i < lines.size(); i++) {
            JournalValidator.Line line = lines.get(i);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("journalId", journalId);
            row.put("lineNo", BigDecimal.valueOf(i + 1L));
            row.put("accountCode", trim(line.accountCode()));
            row.put("debit", zeroToNull(line.debit()));
            row.put("credit", zeroToNull(line.credit()));
            row.put("memo", blankToNull(line.memo()));
            row.put("department", blankToNull(line.department()));
            row.put("location", blankToNull(line.location()));
            ctx.changes().insert(LINE, row);
        }
    }

    /** The content an approval and an exception are given for, from the stored entry. */
    static Map<String, Object> content(EntityInstance journal, List<EntityInstance> lines) {
        Map<String, Object> header = new LinkedHashMap<>();
        for (String field : List.of("postingDate", "documentDate", "description", "adjusting", "adjustmentPeriod",
            "autoReverseDate", "reversesJournalId")) {
            header.put(field, journal.get(field));
        }
        return content(header, journal.get("source"),
            lineMaps(lines.stream().sorted(java.util.Comparator.comparing(l -> l.<BigDecimal>get("lineNo")))
                .map(JournalProcesses::line).toList()));
    }

    static String contentHash(EntityInstance journal, List<EntityInstance> lines) {
        return ContentHash.of(content(journal, lines));
    }

    static String contentHash(Map<String, Object> header, String source, List<Map<String, Object>> lines) {
        return ContentHash.of(content(header, source, lines));
    }

    private static Map<String, Object> content(Map<String, Object> header, String source,
        List<Map<String, Object>> lines) {
        Map<String, Object> content = new LinkedHashMap<>();
        for (String field : List.of("postingDate", "documentDate", "description", "adjusting", "adjustmentPeriod",
            "autoReverseDate", "reversesJournalId")) {
            Object value = header.get(field);
            content.put(field, value == null ? null : String.valueOf(value));
        }
        content.put("source", source);
        content.put("lines", lines);
        return content;
    }

    static List<Map<String, Object>> lineMaps(List<JournalValidator.Line> lines) {
        List<Map<String, Object>> maps = new ArrayList<>();
        for (JournalValidator.Line line : lines) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("accountCode", trim(line.accountCode()));
            map.put("debit", zeroToNull(line.debit()));
            map.put("credit", zeroToNull(line.credit()));
            map.put("memo", blankToNull(line.memo()));
            map.put("department", blankToNull(line.department()));
            map.put("location", blankToNull(line.location()));
            maps.add(map);
        }
        return maps;
    }

    /** Whether two sets of lines are the same in any order, amounts compared by value. */
    static boolean sameLines(List<JournalValidator.Line> a, List<JournalValidator.Line> b) {
        return comparable(lineMaps(a)).equals(comparable(lineMaps(b)));
    }

    private static List<String> comparable(List<Map<String, Object>> lines) {
        List<String> result = new ArrayList<>();
        for (Map<String, Object> line : lines) {
            Map<String, Object> copy = new LinkedHashMap<>(line);
            copy.replaceAll((key, value) -> value instanceof BigDecimal amount
                ? amount.stripTrailingZeros().toPlainString() : value);
            result.add(copy.toString());
        }
        result.sort(null);
        return result;
    }

    static JournalValidator.Line line(LineInput input) {
        return new JournalValidator.Line(trim(input.accountCode()), input.debit(), input.credit(), input.memo(),
            trim(input.department()), trim(input.location()));
    }

    static JournalValidator.Line line(EntityInstance row) {
        return new JournalValidator.Line(row.get("accountCode"), row.get("debit"), row.get("credit"),
            row.get("memo"), row.get("department"), row.get("location"));
    }

    /** The accounts loaded under {@code FIN_ACCOUNTS} and {@code LEDGER_ACCOUNTS}, by code. */
    static Map<String, JournalValidator.Account> accounts(ProcessContext ctx) {
        Map<String, EntityInstance> ledger = new HashMap<>();
        for (EntityInstance account : list(ctx, LEDGER_ACCOUNTS)) {
            ledger.put(account.get("accountCode"), account);
        }
        Map<String, JournalValidator.Account> accounts = new HashMap<>();
        for (EntityInstance fin : list(ctx, FIN_ACCOUNTS)) {
            EntityInstance account = ledger.get(fin.<String>get("accountCode"));
            if (account != null) {
                accounts.put(fin.get("accountCode"), new JournalValidator.Account(fin.get("accountCode"),
                    Boolean.TRUE.equals(account.get("enabled")), Boolean.TRUE.equals(account.get("summary")),
                    fin.get("controlClass"), fin.get("requiredDimension")));
            }
        }
        return accounts;
    }

    /** The active dimension values loaded under {@code DEPARTMENTS} and {@code LOCATIONS}. */
    static JournalValidator.Dimensions dimensions(ProcessContext ctx) {
        Set<String> departments = new HashSet<>();
        for (EntityInstance value : list(ctx, DEPARTMENTS)) {
            if (Boolean.TRUE.equals(value.get("active"))) {
                departments.add(value.get("departmentCode"));
            }
        }
        Set<String> locations = new HashSet<>();
        for (EntityInstance value : list(ctx, LOCATIONS)) {
            if (Boolean.TRUE.equals(value.get("active"))) {
                locations.add(value.get("locationCode"));
            }
        }
        return new JournalValidator.Dimensions(departments, locations);
    }

    /** The regular period holding the posting date, or the adjustment period when the entry asks for it. */
    static EntityInstance period(ProcessContext ctx, boolean adjustmentPeriod) {
        return period(ctx, adjustmentPeriod, false);
    }

    /**
     * As above; the opening period holds only the opening entry, so no other entry finds it, even on its day
     * (FIN-PC-002).
     */
    static EntityInstance period(ProcessContext ctx, boolean adjustmentPeriod, boolean opening) {
        return list(ctx, PERIODS).stream()
            .filter(p -> Boolean.TRUE.equals(p.get("opening")) == opening)
            .filter(p -> Boolean.TRUE.equals(p.get("adjustment")) == adjustmentPeriod)
            .findFirst().orElse(null);
    }

    static PeriodPolicy.State state(EntityInstance period, PeriodPolicy.Source source) {
        String field = switch (source) {
            case GL -> null;
            case AR -> "arStatus";
            case AP -> "apStatus";
            case BANK -> "bankStatus";
            case FA -> "faStatus";
        };
        return new PeriodPolicy.State(period.get("periodKey"),
            PeriodPolicy.Status.valueOf(period.<String>get("status")),
            field != null && "CLOSED".equals(period.get(field)));
    }

    static EntityQuery periodsOf(LocalDate date) {
        return EntityQuery.builder().where(new QueryPredicate.And(List.of(
                new QueryPredicate.Lte("startDate", date), new QueryPredicate.Gte("endDate", date))))
            .limit(4).build();
    }

    static EntityQuery byIds(Object id) {
        return EntityQuery.builder()
            .where(new QueryPredicate.In("journalId", id == null ? List.of() : List.of(id))).limit(1).build();
    }

    static EntityQuery linesOf(Object journalId) {
        return EntityQuery.builder()
            .where(new QueryPredicate.In("journalId", journalId == null ? List.of() : List.of(journalId)))
            .limit(LINE_LIMIT).build();
    }

    static EntityQuery byCodes(String field, Set<String> codes) {
        return EntityQuery.builder().where(new QueryPredicate.In(field, new ArrayList<>(codes)))
            .limit(Math.max(1, codes.size())).build();
    }

    private static Set<String> accountCodes(ProcessContext ctx) {
        return accountCodes(ctx, LINES);
    }

    /** The account codes of the line rows under {@code linesKey}. */
    static Set<String> accountCodes(ProcessContext ctx, String linesKey) {
        Set<String> codes = new HashSet<>();
        for (EntityInstance line : list(ctx, linesKey)) {
            codes.add(line.get("accountCode"));
        }
        return codes;
    }

    private static Set<String> dimensionValues(ProcessContext ctx, String dimension) {
        return dimensionValues(ctx, LINES, dimension);
    }

    /** The values of a dimension on the line rows under {@code linesKey}. */
    static Set<String> dimensionValues(ProcessContext ctx, String linesKey, String dimension) {
        Set<String> values = new HashSet<>();
        for (EntityInstance line : list(ctx, linesKey)) {
            String value = line.get(dimension);
            if (value != null) {
                values.add(value);
            }
        }
        return values;
    }

    /**
     * The approval case of the entry; a refused new draft has none, and no request is ever filed under "none".
     */
    private static Object approvalCase(ProcessContext ctx) {
        return ctx.contains(JOURNAL_ID) ? ctx.get(JOURNAL_ID) : "none";
    }

    private static ProcessContext withId(com.jabiz.process.ProcessStart start, JournalId input) {
        ProcessContext ctx = new ProcessContext(start);
        ctx.put(INPUT, input);
        ctx.put(JOURNAL_ID, input.journalId());
        return ctx;
    }

    static JournalOutput output(EntityInstance journal, String status, String approval, String glNo,
        String transactionId) {
        return new JournalOutput(String.valueOf(journal.id()), journal.get("journalNo"), status,
            journal.get("totalDebit"), journal.get("totalCredit"), approval, journal.get("approvalRequestId"), glNo,
            transactionId);
    }

    static Violation refusal(String field, String code, String message, EntityInstance journal) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("journalNo", journal.get("journalNo") == null ? "" : journal.get("journalNo"));
        return new Violation(field, code, message, params);
    }

    private static void putIfPresent(Map<String, String> map, String key, String value) {
        if (value != null && !value.isBlank()) {
            map.put(key, value);
        }
    }

    private static UUID uuid(String value) {
        try {
            return value == null ? null : UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static BigDecimal zeroToNull(BigDecimal value) {
        return value == null || value.signum() == 0 ? null : value;
    }

    private JournalProcesses() {}
}
