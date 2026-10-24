package com.jabiz.quizbuks.wallet;

import com.jabiz.approval.ContentHash;
import com.jabiz.entity.Violation;
import com.jabiz.ledger.Direction;
import com.jabiz.param.ParamValues;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.process.ProcessDefinitionBuilder;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.identity.CallerChecks;
import com.jabiz.quizbuks.messaging.ConsentChecks;
import com.jabiz.quizbuks.publishing.SponsorChecks;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.runtime.BusinessRuleViolationException;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.EntityNotFoundException;
import com.jabiz.runtime.approval.ApprovalCase;
import com.jabiz.runtime.approval.ApprovalEntities;
import com.jabiz.runtime.approval.ApprovalOutcome;
import com.jabiz.runtime.approval.RequireApproval;
import com.jabiz.runtime.ledger.LedgerProcesses;
import com.jabiz.runtime.numbering.AssignNumber;
import com.jabiz.runtime.process.steps.CallProcess;
import com.jabiz.runtime.process.steps.HoldLock;
import com.jabiz.runtime.process.steps.LoadEntity;
import com.jabiz.runtime.process.steps.LoadParams;
import com.jabiz.runtime.process.steps.PublishEvent;
import com.jabiz.runtime.process.steps.QueryEntities;
import com.jabiz.runtime.task.CloseTasks;
import com.jabiz.runtime.task.CreateTask;
import com.jabiz.runtime.task.TaskSpec;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.jabiz.quizbuks.wallet.TopUpCrediting.TOPUP;
import static com.jabiz.quizbuks.wallet.TopUpCrediting.WALLET;
import static com.jabiz.quizbuks.wallet.TopUpCrediting.first;

/**
 * Top-ups by bank transfer (docs/quizbuks/plans/Q13-bank-transfer.md, Q13a). Money is credited only when two people
 * agree that it arrived (B-1):
 * <ul>
 *   <li>{@value #START} (the sponsor): asks for a top-up of an amount and gets a code to put before the payer's name;
 *       the sponsor console shows the platform's receiving account ({@code qb.bank.receiving.*});</li>
 *   <li>{@value #REPORT} (the sponsor, optional): says the money was sent, by whom and when; finance gets a task;</li>
 *   <li>{@value #CANCEL} (the sponsor): withdraws a request no money was recorded against;</li>
 *   <li>{@value #RECORD} (finance): records what the bank statement shows (the amount received, the day, the
 *       statement's reference) and asks for another administrator's approval; nothing is posted;</li>
 *   <li>{@value #APPROVAL_RESULT} (the approval's events): an approval of the top-up's own request for what was recorded
 *       posts debit 1120 / credit 2110 (the sponsor) at the amount received, raises the wallet and marks it
 *       {@code PAID}, as a card top-up's arrival does (pending rewards, the mail, the receipt); a rejection sends it
 *       back to where it was, the record cleared;</li>
 *   <li>{@value #EXPIRE} (each item of the daily job): a request no money was recorded against within
 *       {@link BankTransfers#EXPIRES_AFTER} expires; money that comes later may still be recorded against it.</li>
 * </ul>
 * All but the start take the sponsor's wallet lock (its name from the record) and read the top-up again under it, as
 * the card top-ups' processes do; the start takes the sponsor's top-up lock alone.
 */
public final class BankTopUps {

    public static final String START = "QB_TOPUP_BANK_START";
    public static final String REPORT = "QB_TOPUP_BANK_REPORT";
    public static final String CANCEL = "QB_TOPUP_BANK_CANCEL";
    public static final String RECORD = "QB_TOPUP_BANK_RECORD";
    public static final String APPROVAL_RESULT = "QB_TOPUP_BANK_APPROVAL_RESULT";
    public static final String EXPIRE = "QB_TOPUP_BANK_EXPIRE";

    /** The approval subject of recorded bank top-ups, and its rule (proposed by {@code QB_SETUP}). */
    public static final String APPROVAL_SUBJECT = "qb.topup-bank";
    public static final String REVIEW_RULE = "qb.topup-bank.review";

    /** The daily job expiring requests no money came for, and its selection. */
    public static final String EXPIRE_JOB = "qb.topup-bank-expire";
    public static final String EXPIRE_TEMPLATE = "qb.system.topups-bank-expired";

    /** The task finance gets when a sponsor reports a transfer ({@code task.qb.topup-bank.reported}). */
    static final String TASK_TYPE = "qb.topup-bank";

    /** @param amount whole Kudos within {@code qb.topup.bank.min} – {@code qb.topup.bank.max} */
    public record StartInput(@NotNull @DecimalMin("1") @DecimalMax("1000000000000")
        @Digits(integer = 13, fraction = 0) BigDecimal amount) {}

    /** @param transferCode what the sponsor puts before the payer's name */
    public record StartOutput(String topUpId, String transferCode, BigDecimal amount, String status) {}

    /**
     * @param payerName    the payer's name as the bank shows it (with the code before it, if the sponsor put it there)
     * @param transferDate the day the money was sent, in Japan
     */
    public record ReportInput(@NotBlank @Size(max = 36) String topUpId,
        @NotBlank @Size(max = BankTransfers.PAYER_NAME_LENGTH) String payerName, @NotNull LocalDate transferDate) {}

    /**
     * @param amount        what the statement shows received, in Kudos (one per yen)
     * @param receivedOn    the statement's day of the deposit, in Japan
     * @param bankReference the statement's reference of the deposit
     */
    public record RecordInput(@NotBlank @Size(max = 36) String topUpId,
        @NotNull @DecimalMin("1") @DecimalMax("1000000000000") @Digits(integer = 13, fraction = 0) BigDecimal amount,
        @NotNull LocalDate receivedOn, @NotBlank @Size(max = BankTransfers.REFERENCE_LENGTH) String bankReference) {}

    /** @param approvalRequestId the request another administrator decides, once recorded */
    public record BankOutput(String topUpId, String status, String approvalRequestId) {}

    static final String INPUT = "input";
    static final String PARAMS = "params";
    static final String TOPUP_ID = "topUpId";
    static final String OPEN = "open";
    static final String CODE = "code";
    static final String NEW_ID = "newId";
    static final String FOUND = "found";
    static final String RULES = "rules";
    static final String SAME_ENTRY = "sameEntry";
    static final String RECORDED = "recorded";
    static final String APPROVAL = "approval";
    static final String REQUEST = "request";
    static final String POST_INPUT = "postInput";
    static final String POSTING = "posting";
    static final String STATUS_AFTER = "statusAfter";
    static final String REPORTED_FIRST = "reportedFirst";
    static final String DECISIONS = "decisions";
    static final String SENT_BACK = "sentBack";
    /** More decisions than a request of a few levels has. */
    static final int MAX_DECISIONS = 20;

    // ---- the sponsor ------------------------------------------------------------------------------------------------

    public static ProcessDefinition<StartInput, StartOutput, ProcessContext> start() {
        return ProcessDefinition.define(START, 1, StartInput.class, StartOutput.class, ProcessContext.class, pb -> pb
            .description("Asks for a top-up of the sponsor wallet by bank transfer: the code to put before the "
                + "payer's name comes back.")
            .permissions(QbPermissions.TOPUP)
            .requiresVerifiedEmail()
            .steps(CallerChecks::refuseBanned)
            .steps(ConsentChecks.sponsor())
            .contextFactory(BankTopUps::context)
            .outputMapper(ctx -> new StartOutput((String) ctx.get(NEW_ID), (String) ctx.get(CODE),
                ctx.get(INPUT, StartInput.class).amount(), TopUpStatus.AWAITING))
            .steps(SponsorChecks::load)
            // Optional (platform 16zc): settings not yet published mean "not available yet", one message (Q18).
            .step("Load the limits and the account", LoadParams.<ProcessContext>optional(ProcessContext::opTime,
                PARAMS, params()))
            // A sponsor's starts take turns (the open ones are counted), as the card top-ups' do: the same lock.
            .step("Lock the sponsor's top-ups", HoldLock.<ProcessContext>exclusive(
                ctx -> TopUpProcesses.lockName(ctx.request().actorId())))
            .step("Load the open bank top-ups", QueryEntities.<ProcessContext>of(
                QbTopUps.defaultDataset(QbTopUps.TOPUP), ctx -> EntityQuery.builder()
                    .where(new QueryPredicate.And(List.of(
                        new QueryPredicate.Eq("sponsorId", ctx.request().actorId()),
                        new QueryPredicate.Eq("method", QbTopUps.BANK),
                        new QueryPredicate.In("status", new ArrayList<>(List.of(TopUpStatus.AWAITING,
                            TopUpStatus.REPORTED, TopUpStatus.REVIEW))))))
                    .limit(BankTransfers.MAX_OPEN).build(), OPEN))
            .compute("Check the request", (metadata, ctx) -> checkStart(ctx))
            // After every check that can refuse: a code drawn is kept (the count is rolled back with a failure).
            .step("Draw the transfer's code", AssignNumber.<ProcessContext>of(QbTopUps.TRANSFER_CODE_SEQUENCE, CODE))
            .compute("Record the request", (metadata, ctx) -> insert(ctx)));
    }

    private static String[] params() {
        List<String> keys = new ArrayList<>(List.of(QbParams.TOPUP_BANK_MIN, QbParams.TOPUP_BANK_MAX));
        keys.addAll(QbParams.BANK_RECEIVING);
        return keys.toArray(String[]::new);
    }

    static void checkStart(ProcessContext ctx) {
        List<Violation> problems = new ArrayList<>(SponsorChecks.problems(ctx));
        BigDecimal amount = ctx.get(INPUT, StartInput.class).amount();
        ParamValues params = ctx.get(PARAMS, ParamValues.class);
        BigDecimal min = params.find(QbParams.TOPUP_BANK_MIN, BigDecimal.class).orElse(null);
        BigDecimal max = params.find(QbParams.TOPUP_BANK_MAX, BigDecimal.class).orElse(null);
        boolean accountSet = QbParams.BANK_RECEIVING.stream()
            .allMatch(key -> params.find(key, String.class).filter(value -> !value.isBlank()).isPresent());
        if (min == null || max == null || !accountSet) {
            problems.add(new Violation(null, WalletCodes.TOPUP_BANK_UNAVAILABLE,
                "Bank top-ups are not set up yet", Map.of()));
        } else if (amount.compareTo(min) < 0 || amount.compareTo(max) > 0) {
            problems.add(new Violation("amount", WalletCodes.TOPUP_AMOUNT_RANGE, "A bank top-up is between " + min
                + " and " + max + " Kudos", Map.of("min", min.toPlainString(), "max", max.toPlainString())));
        }
        if (TopUpCrediting.list(ctx, OPEN).size() >= BankTransfers.MAX_OPEN) {
            problems.add(new Violation(null, WalletCodes.TOPUP_TOO_MANY_OPEN, "Finish or withdraw the open bank "
                + "top-ups first", Map.of("max", BankTransfers.MAX_OPEN)));
        }
        if (!problems.isEmpty()) {
            throw new BusinessRuleViolationException(problems);
        }
    }

    static void insert(ProcessContext ctx) {
        BigDecimal amount = ctx.get(INPUT, StartInput.class).amount();
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("sponsorId", ctx.request().actorId());
        values.put("method", QbTopUps.BANK);
        values.put("transferCode", ctx.get(CODE));
        values.put("requestedAmount", amount);
        values.put("amount", amount);
        values.put("status", TopUpStatus.AWAITING);
        values.put("createdAt", ctx.opTime());
        values.put("refundedAmount", BigDecimal.ZERO);
        values.put("refundShortfall", BigDecimal.ZERO);
        values.put("disputeClawback", BigDecimal.ZERO);
        values.put("disputeShortfall", BigDecimal.ZERO);
        values.put("disputeHeld", BigDecimal.ZERO);
        ctx.put(NEW_ID, String.valueOf(ctx.changes().insert(QbTopUps.TOPUP, values)));
    }

    public static ProcessDefinition<ReportInput, BankOutput, ProcessContext> report() {
        return ProcessDefinition.define(REPORT, 1, ReportInput.class, BankOutput.class, ProcessContext.class, pb -> pb
            .description("Tells finance that the money of a bank top-up was sent: by whom and on which day.")
            .permissions(QbPermissions.TOPUP)
            .requiresVerifiedEmail()
            .actsOn(QbTopUps.TOPUP, TOPUP_ID)
            .steps(CallerChecks::refuseBanned)
            .contextFactory((start, input) -> {
                ProcessContext ctx = context(start, input);
                ctx.put(TOPUP_ID, input.topUpId());
                return ctx;
            })
            .outputMapper(BankTopUps::output)
            .steps(BankTopUps::loadOwn)
            .compute("Record the report", (metadata, ctx) -> {
                EntityInstance topUp = waiting(ctx);
                ReportInput input = ctx.get(INPUT, ReportInput.class);
                List<Violation> problems = new ArrayList<>();
                Violation name = BankTransfers.payerNameProblem(input.payerName());
                if (name != null) {
                    problems.add(name);
                }
                Violation future = BankTransfers.futureProblem("transferDate", input.transferDate(), ctx.opTime());
                if (future != null) {
                    problems.add(future);
                }
                if (!problems.isEmpty()) {
                    throw new BusinessRuleViolationException(problems);
                }
                // Finance hears of the first report; a corrected one changes the record the task points to.
                if (TopUpStatus.AWAITING.equals(topUp.get("status"))) {
                    ctx.put(REPORTED_FIRST, true);
                }
                Map<String, Object> values = new LinkedHashMap<>();
                values.put("status", TopUpStatus.REPORTED);
                values.put("payerName", input.payerName().strip());
                values.put("transferDate", input.transferDate());
                values.put("reportedAt", ctx.opTime());
                ctx.put(STATUS_AFTER, TopUpStatus.REPORTED);
                ctx.changes().update(QbTopUps.TOPUP, topUp.id(), topUp.version(), values);
            })
            .step("Tell finance", CreateTask.<ProcessContext>when(ctx -> ctx.contains(REPORTED_FIRST),
                ctx -> task(first(ctx, TOPUP)))));
    }

    public static ProcessDefinition<TopUpProcesses.TopUpRef, BankOutput, ProcessContext> cancel() {
        return ProcessDefinition.define(CANCEL, 1, TopUpProcesses.TopUpRef.class, BankOutput.class,
            ProcessContext.class, pb -> pb
                .description("Withdraws a bank top-up no money was recorded against.")
                .permissions(QbPermissions.TOPUP)
                .requiresVerifiedEmail()
                .actsOn(QbTopUps.TOPUP, TOPUP_ID)
                .contextFactory((start, input) -> {
                    ProcessContext ctx = context(start, input);
                    ctx.put(TOPUP_ID, input.topUpId());
                    return ctx;
                })
                .outputMapper(BankTopUps::output)
                .steps(BankTopUps::loadOwn)
                .compute("Withdraw it", (metadata, ctx) -> {
                    EntityInstance topUp = waiting(ctx);
                    ctx.put(STATUS_AFTER, TopUpStatus.CANCELLED);
                    ctx.changes().update(QbTopUps.TOPUP, topUp.id(), topUp.version(),
                        Map.of("status", TopUpStatus.CANCELLED));
                })
                .step("Close finance's task", CloseTasks.<ProcessContext>cancel(ctx -> taskSource(ctx.get(TOPUP_ID)))));
    }

    /**
     * The sponsor's own top-up (another's is not found: 404) through their dataset, then the wallet lock and the record
     * read again under it.
     */
    private static <I, O> void loadOwn(ProcessDefinitionBuilder<I, O, ProcessContext> pb) {
        pb.step("Load the top-up", LoadEntity.by(QbTopUps.SPONSOR_TOPUPS, TOPUP_ID, FOUND))
            .step("Lock the sponsor's wallet", HoldLock.<ProcessContext>exclusive(
                ctx -> QbWallets.lockName(QbWallets.SPONSOR, ctx.request().actorId())))
            .step("Read the top-up again", QueryEntities.<ProcessContext>of(QbTopUps.defaultDataset(QbTopUps.TOPUP),
                ctx -> TopUpCrediting.byId((String) ctx.get(TOPUP_ID)), TOPUP));
    }

    /** The top-up read under the lock, a bank one still waiting for money; otherwise refused. */
    private static EntityInstance waiting(ProcessContext ctx) {
        EntityInstance topUp = first(ctx, TOPUP);
        if (topUp == null || !QbTopUps.BANK.equals(topUp.get("method"))) {
            throw new EntityNotFoundException(QbTopUps.TOPUP + " " + ctx.get(TOPUP_ID) + " not found");
        }
        if (!TopUpStatus.BANK_WAITING.contains(topUp.get("status"))) {
            throw new BusinessRuleViolationException(List.of(stateProblem(topUp)));
        }
        return topUp;
    }

    private static Violation stateProblem(EntityInstance topUp) {
        return new Violation(null, WalletCodes.TOPUP_BANK_STATE, "The bank top-up is " + topUp.get("status"),
            Map.of("status", String.valueOf((Object) topUp.get("status"))));
    }

    // ---- finance ----------------------------------------------------------------------------------------------------

    public static ProcessDefinition<RecordInput, BankOutput, ProcessContext> record() {
        return ProcessDefinition.define(RECORD, 1, RecordInput.class, BankOutput.class, ProcessContext.class, pb -> pb
            .description("Records the money of a bank top-up found on the bank statement, for another "
                + "administrator's approval; nothing is credited before it.")
            .permissions(QbPermissions.TOPUP_BANK_RECORD)
            .requiresVerifiedEmail()
            .actsOn(QbTopUps.TOPUP, TOPUP_ID)
            .contextFactory((start, input) -> {
                ProcessContext ctx = context(start, input);
                ctx.put(TOPUP_ID, input.topUpId());
                return ctx;
            })
            .outputMapper(BankTopUps::output)
            .steps(b -> TopUpCrediting.lockAndLoad(b, ctx -> (String) ctx.get(TOPUP_ID)))
            .step("Load the review rule", QueryEntities.<ProcessContext>of(ApprovalEntities.RULE_DATASET,
                ctx -> EntityQuery.builder().where(new QueryPredicate.And(List.of(
                    new QueryPredicate.Eq("subject", APPROVAL_SUBJECT),
                    new QueryPredicate.Eq("enabled", true)))).limit(1).build(), RULES))
            .step("Look for the statement's entry", QueryEntities.<ProcessContext>of(
                QbTopUps.defaultDataset(QbTopUps.TOPUP), ctx -> {
                    RecordInput input = ctx.get(INPUT, RecordInput.class);
                    return EntityQuery.builder().where(new QueryPredicate.And(List.of(
                        new QueryPredicate.Eq("receivedOn", input.receivedOn()),
                        new QueryPredicate.Eq("bankReference", BankTransfers.reference(input.bankReference()))))).limit(1)
                        .build();
                }, SAME_ENTRY))
            .compute("Check the record", (metadata, ctx) -> checkRecord(ctx))
            .step("Ask for another administrator's approval", RequireApproval.<ProcessContext>of(APPROVAL_SUBJECT,
                ctx -> approvalCase(ctx.get(RECORDED, Recorded.class)), APPROVAL))
            .compute("Record it", (metadata, ctx) -> writeRecord(ctx))
            .step("Close finance's task", CloseTasks.<ProcessContext>done(ctx -> taskSource(ctx.get(TOPUP_ID)))));
    }

    static void checkRecord(ProcessContext ctx) {
        EntityInstance topUp = first(ctx, TOPUP);
        if (topUp == null || !QbTopUps.BANK.equals(topUp.get("method"))) {
            throw new EntityNotFoundException(QbTopUps.TOPUP + " " + ctx.get(TOPUP_ID) + " not found");
        }
        RecordInput input = ctx.get(INPUT, RecordInput.class);
        List<Violation> problems = new ArrayList<>();
        if (!TopUpStatus.BANK_RECORDABLE.contains(topUp.get("status"))) {
            problems.add(stateProblem(topUp));
        }
        if (ctx.request().actorId().equals(topUp.get("sponsorId"))) {
            problems.add(new Violation(null, WalletCodes.TOPUP_BANK_OWN, "One's own top-up is recorded by another "
                + "administrator", Map.of()));
        }
        Violation future = BankTransfers.futureProblem("receivedOn", input.receivedOn(), ctx.opTime());
        if (future != null) {
            problems.add(future);
        }
        if (first(ctx, SAME_ENTRY) != null) {
            problems.add(new Violation("bankReference", WalletCodes.TOPUP_BANK_REFERENCE_USED, "The statement's entry "
                + "is recorded against another top-up", Map.of()));
        }
        if (first(ctx, RULES) == null) {
            problems.add(noReview());
        }
        if (!problems.isEmpty()) {
            throw new BusinessRuleViolationException(problems);
        }
        ctx.put(RECORDED, new Recorded(String.valueOf(topUp.id()), topUp.get("sponsorId"), topUp.get("transferCode"),
            QbTopUps.money(topUp.get("requestedAmount")), input.amount(), input.receivedOn().toString(),
            BankTransfers.reference(input.bankReference()), ctx.request().actorId(),
            Recorded.recordedAt(ctx.opTime())));
    }

    private static Violation noReview() {
        return new Violation(null, WalletCodes.TOPUP_BANK_NO_REVIEW, "No approval rule asks for a second person on "
            + "bank top-ups", Map.of());
    }

    static void writeRecord(ProcessContext ctx) {
        ApprovalOutcome approval = ctx.get(APPROVAL, ApprovalOutcome.class);
        if (approval.status() != ApprovalOutcome.Status.PENDING) {
            // The rule's condition was changed so that it no longer applies: never credited by one person (B-1).
            throw new BusinessRuleViolationException(List.of(noReview()));
        }
        EntityInstance topUp = first(ctx, TOPUP);
        Recorded recorded = ctx.get(RECORDED, Recorded.class);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("status", TopUpStatus.REVIEW);
        values.put("amount", recorded.received());
        values.put("receivedOn", LocalDate.parse(recorded.receivedOn()));
        values.put("bankReference", recorded.bankReference());
        values.put("recordedBy", recorded.recordedBy());
        values.put("recordedAt", ctx.opTime());
        values.put("reviewFrom", topUp.get("status"));
        values.put("approvalRequestId", approval.requestId());
        ctx.put(STATUS_AFTER, TopUpStatus.REVIEW);
        ctx.changes().update(QbTopUps.TOPUP, topUp.id(), topUp.version(), values);
    }

    /** What finance recorded: the content another administrator approves (its hash binds the approval). */
    record Recorded(String topUpId, String sponsorId, String transferCode, BigDecimal requested, BigDecimal received,
        String receivedOn, String bankReference, String recordedBy, String recordedAt) {

        Recorded {
            // Whole Kudos, so that the content's hash is the same from the input and from the stored row.
            requested = requested.setScale(0);
            received = received.setScale(0);
        }

        static Recorded of(EntityInstance topUp) {
            Object receivedOn = topUp.get("receivedOn");
            return new Recorded(String.valueOf(topUp.id()), topUp.get("sponsorId"), topUp.get("transferCode"),
                QbTopUps.money(topUp.get("requestedAmount")), QbTopUps.money(topUp.get("amount")),
                receivedOn == null ? null : receivedOn.toString(), topUp.get("bankReference"),
                topUp.get("recordedBy"), recordedAt(QbTopUps.instant(topUp.get("recordedAt"))));
        }

        /**
         * When it was recorded, to the millisecond (what the database keeps of it): each recording is its own case, so
         * the same entry recorded again after a rejection is asked about again rather than taking the old decision.
         */
        static String recordedAt(Instant time) {
            return time == null ? null : time.truncatedTo(java.time.temporal.ChronoUnit.MILLIS).toString();
        }
    }

    /**
     * The case: the rule's facts, and the content approved (whose money, how much against how much asked for, which
     * entry of the statement). The recorder is the preparer, so the platform refuses their own approval.
     */
    static ApprovalCase approvalCase(Recorded recorded) {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("bank", true);
        facts.put("amount", recorded.received());
        facts.put("sponsorId", recorded.sponsorId());
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("topUpId", recorded.topUpId());
        content.put("sponsorId", recorded.sponsorId());
        content.put("transferCode", recorded.transferCode());
        content.put("requestedAmount", recorded.requested());
        content.put("amount", recorded.received());
        content.put("receivedOn", recorded.receivedOn());
        content.put("bankReference", recorded.bankReference());
        content.put("recordedAt", recorded.recordedAt());
        return ApprovalCase.of(recorded.topUpId(), facts, content).preparedBy(recorded.recordedBy())
            .reference(recorded.transferCode());
    }

    // ---- the approval -----------------------------------------------------------------------------------------------

    /**
     * Continues after the approval is decided (subscribed to both approval events, run as the system). Only the
     * top-up's own request counts: an approval posts only when {@link RequireApproval} finds that request approved for
     * what is recorded now, a rejection only when the stored request is rejected for it.
     */
    public static ProcessDefinition<TransferProcesses.ApprovalResultInput, BankOutput, ProcessContext>
        approvalResult() {
        return ProcessDefinition.define(APPROVAL_RESULT, 1, TransferProcesses.ApprovalResultInput.class,
            BankOutput.class, ProcessContext.class, pb -> pb
                .description("Credits a recorded bank top-up once another administrator approved it, or sends it "
                    + "back when they rejected it.")
                .permissions(QbPermissions.WALLET_SYSTEM)
                .internal()
                .contextFactory(BankTopUps::context)
                .outputMapper(BankTopUps::output)
                .steps(b -> TopUpCrediting.lockAndLoad(b, ctx -> {
                    TransferProcesses.ApprovalResultInput input = approvalInput(ctx);
                    return APPROVAL_SUBJECT.equals(input.subject()) ? input.entityId() : null;
                }))
                .step("Load the request", QueryEntities.<ProcessContext>of(ApprovalEntities.REQUEST_DATASET,
                    ctx -> EntityQuery.builder().where(new QueryPredicate.In("requestId", ownRequest(ctx)
                        ? new ArrayList<>(List.of(approvalInput(ctx).requestId())) : new ArrayList<>()))
                        .limit(1).build(), REQUEST))
                .step("Check the approval", RequireApproval.<ProcessContext>when(
                    ctx -> ownRequest(ctx) && ApprovalEntities.APPROVED.equals(approvalInput(ctx).status()),
                    APPROVAL_SUBJECT, ctx -> approvalCase(Recorded.of(first(ctx, TOPUP)))
                        .preparedBy(approvalInput(ctx).preparerId()), APPROVAL))
                // Who approved: a sponsor who is also an administrator never approves their own money in.
                .step("Load the decisions", QueryEntities.<ProcessContext>of(ApprovalEntities.DECISION_DATASET,
                    ctx -> EntityQuery.builder().where(new QueryPredicate.In("requestId", ownRequest(ctx)
                        ? new ArrayList<>(List.of(approvalInput(ctx).requestId())) : new ArrayList<>()))
                        .limit(MAX_DECISIONS).build(), DECISIONS))
                .compute("Credit or send back", (metadata, ctx) -> decided(ctx))
                .step("Post the top-up", CallProcess.<ProcessContext>when(ctx -> ctx.contains(POST_INPUT),
                    LedgerProcesses.POST, 1, ctx -> ctx.get(POST_INPUT), POSTING))
                .compute("Credit the wallet", (metadata, ctx) -> credit(ctx))
                .step("Ask for the pending rewards to be paid", PublishEvent.<ProcessContext>when(
                    ctx -> ctx.contains(POSTING), QbWallets.SETTLE_EVENT,
                    ctx -> Map.of("sponsorId", first(ctx, TOPUP).get("sponsorId"))))
                .step("Announce the top-up", PublishEvent.<ProcessContext>when(ctx -> ctx.contains(POSTING),
                    QbTopUps.PAID_EVENT, ctx -> {
                        EntityInstance topUp = first(ctx, TOPUP);
                        Map<String, Object> payload = new LinkedHashMap<>();
                        payload.put("topUpId", String.valueOf(topUp.id()));
                        payload.put("sponsorId", topUp.get("sponsorId"));
                        payload.put("amount", QbTopUps.money(topUp.get("amount")));
                        return payload;
                    }))
                .step("Tell finance it is back", CreateTask.<ProcessContext>when(ctx -> ctx.contains(SENT_BACK),
                    ctx -> backTask(first(ctx, TOPUP), (String) ctx.get(SENT_BACK)))));
    }

    private static boolean ownRequest(ProcessContext ctx) {
        EntityInstance topUp = first(ctx, TOPUP);
        TransferProcesses.ApprovalResultInput input = approvalInput(ctx);
        return topUp != null && QbTopUps.BANK.equals(topUp.get("method"))
            && TopUpStatus.REVIEW.equals(topUp.get("status")) && input.requestId() != null
            && input.requestId().equals(topUp.get("approvalRequestId"));
    }

    static void decided(ProcessContext ctx) {
        if (!ownRequest(ctx)) {
            return;
        }
        EntityInstance topUp = first(ctx, TOPUP);
        TransferProcesses.ApprovalResultInput input = approvalInput(ctx);
        if (ApprovalEntities.APPROVED.equals(input.status())) {
            ApprovalOutcome approval = ctx.get(APPROVAL, ApprovalOutcome.class);
            if (approval.status() != ApprovalOutcome.Status.APPROVED
                || !input.requestId().equals(approval.requestId())) {
                return;
            }
            if (TopUpCrediting.list(ctx, DECISIONS).stream()
                .anyMatch(decision -> topUp.get("sponsorId").equals(decision.get("approverId")))) {
                // Approved by the sponsor themselves (an administrator with a sponsor account): not credited. The
                // record goes back to finance, to be recorded and approved by someone else.
                sendBack(ctx, topUp, "approved-by-sponsor");
                return;
            }
            // Booked when approved (the received day stays on the record): a late approval never posts into a
            // period the journal export has closed.
            BigDecimal amount = QbTopUps.money(topUp.get("amount"));
            ctx.put(POST_INPUT, new LedgerProcesses.PostInput(ctx.opTime(), "Bank top-up",
                topUp.get("transferCode"), List.of(
                    new LedgerProcesses.Line(QbLedger.BANK, Direction.DEBIT, amount),
                    new LedgerProcesses.Line(QbLedger.SPONSOR_PREPAYMENTS, Direction.CREDIT, amount, null,
                        Map.of(QbLedger.PARTY, topUp.get("sponsorId")))), QbTopUps.TOPUP,
                String.valueOf(topUp.id())));
        } else if (ApprovalEntities.REJECTED.equals(input.status()) && rejected(ctx, topUp)) {
            // The reviewer's note stays with the approval.
            sendBack(ctx, topUp, "rejected");
        }
    }

    /**
     * Back to where it was, the record cleared, and a task for finance (keyed by the top-up and the request): the right
     * entry is recorded again, and approved by someone other than the recorder and the sponsor.
     */
    private static void sendBack(ProcessContext ctx, EntityInstance topUp, String why) {
        {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("status", topUp.get("reviewFrom"));
            values.put("amount", QbTopUps.money(topUp.get("requestedAmount")));
            values.put("receivedOn", null);
            values.put("bankReference", null);
            values.put("recordedBy", null);
            values.put("recordedAt", null);
            values.put("reviewFrom", null);
            values.put("approvalRequestId", null);
            ctx.put(STATUS_AFTER, topUp.get("reviewFrom"));
            ctx.put(SENT_BACK, why);
            ctx.changes().update(QbTopUps.TOPUP, topUp.id(), topUp.version(), values);
        }
    }

    /** Whether the stored request (not the event) says that the record, as it is, was rejected. */
    private static boolean rejected(ProcessContext ctx, EntityInstance topUp) {
        EntityInstance request = first(ctx, REQUEST);
        if (request == null) {
            return false;
        }
        String hash = ContentHash.of(approvalCase(Recorded.of(topUp)).content());
        return ApprovalEntities.REJECTED.equals(request.get("status"))
            && APPROVAL_SUBJECT.equals(request.get("subject"))
            && String.valueOf(topUp.id()).equals(request.get("entityId"))
            && hash.equals(String.valueOf((Object) request.get("contentHash")).strip());
    }

    static void credit(ProcessContext ctx) {
        if (!ctx.contains(POSTING)) {
            return;
        }
        EntityInstance topUp = first(ctx, TOPUP);
        BigDecimal amount = QbTopUps.money(topUp.get("amount"));
        QbWallets.change(ctx, first(ctx, WALLET), QbWallets.SPONSOR, topUp.get("sponsorId"), amount, 0,
            BigDecimal.ZERO);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("status", TopUpStatus.PAID);
        values.put("paidAt", ctx.opTime());
        values.put("confirmedBy", "approval:" + approvalInput(ctx).requestId());
        values.put("ledgerTransactionId", ctx.get(POSTING, LedgerProcesses.PostOutput.class).transactionId());
        ctx.put(STATUS_AFTER, TopUpStatus.PAID);
        ctx.changes().update(QbTopUps.TOPUP, topUp.id(), topUp.version(), values);
    }

    // ---- expiry -----------------------------------------------------------------------------------------------------

    public static ProcessDefinition<TopUpProcesses.TopUpRef, BankOutput, ProcessContext> expire() {
        return ProcessDefinition.define(EXPIRE, 1, TopUpProcesses.TopUpRef.class, BankOutput.class,
            ProcessContext.class, pb -> pb
                .description("Expires a bank top-up no money was recorded against in time.")
                .permissions(QbPermissions.WALLET_SYSTEM)
                .internal()
                .contextFactory(BankTopUps::context)
                .outputMapper(BankTopUps::output)
                .steps(b -> TopUpCrediting.lockAndLoad(b,
                    ctx -> ctx.get(INPUT, TopUpProcesses.TopUpRef.class).topUpId()))
                // A reported one keeps finance's task: the money may still be on its way, and can be recorded.
                .compute("Expire it", (metadata, ctx) -> {
                    EntityInstance topUp = first(ctx, TOPUP);
                    if (!due(topUp, ctx.opTime())) {
                        return;
                    }
                    ctx.put(STATUS_AFTER, TopUpStatus.EXPIRED);
                    ctx.changes().update(QbTopUps.TOPUP, topUp.id(), topUp.version(),
                        Map.of("status", TopUpStatus.EXPIRED));
                }));
    }

    /** A bank top-up still waiting for money {@link BankTransfers#EXPIRES_AFTER} after it was asked for. */
    static boolean due(EntityInstance topUp, Instant now) {
        if (topUp == null || !QbTopUps.BANK.equals(topUp.get("method"))
            || !TopUpStatus.BANK_WAITING.contains(topUp.get("status"))) {
            return false;
        }
        Instant created = QbTopUps.instant(topUp.get("createdAt"));
        return !created.plus(BankTransfers.EXPIRES_AFTER).isAfter(now);
    }

    // ---- shared -----------------------------------------------------------------------------------------------------

    /** Finance's task about a reported transfer (one per top-up: the key is the top-up). */
    static TaskSpec task(EntityInstance topUp) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("code", TopUpTasks.shown(topUp.get("transferCode")));
        params.put("amount", QbTopUps.money(topUp.get("requestedAmount")).toPlainString());
        return TaskSpec.forPermission(TASK_TYPE, "task.qb.topup-bank.reported", params,
                QbPermissions.TOPUP_BANK_RECORD)
            .source(taskSource(String.valueOf(topUp.id()))).about(QbTopUps.TOPUP, String.valueOf(topUp.id()));
    }

    /** Finance's task about a record sent back ({@code task.qb.topup-bank.rejected|approved-by-sponsor}). */
    static TaskSpec backTask(EntityInstance topUp, String why) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("code", TopUpTasks.shown(topUp.get("transferCode")));
        params.put("amount", QbTopUps.money(topUp.get("requestedAmount")).toPlainString());
        return TaskSpec.forPermission(TASK_TYPE, "task.qb.topup-bank." + why, params, QbPermissions.TOPUP_BANK_RECORD)
            .source(taskSource(topUp.id()) + ":" + topUp.get("approvalRequestId"))
            .about(QbTopUps.TOPUP, String.valueOf(topUp.id()));
    }

    static String taskSource(Object topUpId) {
        return "topup-bank:" + topUpId;
    }

    private static TransferProcesses.ApprovalResultInput approvalInput(ProcessContext ctx) {
        return ctx.get(INPUT, TransferProcesses.ApprovalResultInput.class);
    }

    private static ProcessContext context(com.jabiz.process.ProcessStart start, Object input) {
        ProcessContext ctx = new ProcessContext(start);
        ctx.put(INPUT, input);
        return ctx;
    }

    private static BankOutput output(ProcessContext ctx) {
        EntityInstance topUp = first(ctx, TOPUP);
        String status = ctx.contains(STATUS_AFTER) ? (String) ctx.get(STATUS_AFTER)
            : topUp == null ? null : topUp.get("status");
        ApprovalOutcome approval = ctx.contains(APPROVAL) ? ctx.get(APPROVAL, ApprovalOutcome.class) : null;
        String requestId = approval != null && approval.status() == ApprovalOutcome.Status.PENDING
            ? approval.requestId() : topUp == null ? null : topUp.get("approvalRequestId");
        return new BankOutput(topUp == null ? null : String.valueOf(topUp.id()), status, requestId);
    }

    private BankTopUps() {}
}
