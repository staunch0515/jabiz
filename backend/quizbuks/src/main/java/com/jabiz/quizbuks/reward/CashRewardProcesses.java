package com.jabiz.quizbuks.reward;

import com.jabiz.entity.Violation;
import com.jabiz.param.ParamValues;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.process.ProcessDefinitionBuilder;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.identity.CallerChecks;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.process.steps.HoldLock;
import com.jabiz.runtime.process.steps.LoadEntity;
import com.jabiz.runtime.process.steps.LoadParams;
import com.jabiz.runtime.process.steps.PublishEvent;
import com.jabiz.runtime.task.CloseTasks;
import com.jabiz.runtime.task.CreateTask;
import com.jabiz.runtime.task.TaskSpec;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A cash reward's way from owed to complete (docs/quizbuks/plans/Q22-reward-methods.md §2):
 * <ul>
 *   <li>{@code QB_CASH_REWARD_MARK_PAID}: the sponsor says they handed the taker the cash (with a note, such as a
 *       receipt's number); the taker then has {@code qb.reward.cash.confirm-days} to confirm or dispute;</li>
 *   <li>{@code QB_CASH_REWARD_CONFIRM}: the taker confirms they got it: complete;</li>
 *   <li>{@code QB_CASH_REWARD_DISPUTE}: the taker says what is wrong (not paid, less than owed); an administrator
 *       gets a task;</li>
 *   <li>{@code QB_CASH_REWARD_RESOLVE}: the administrator finds it paid (confirmed), unpaid (owed again) or void;</li>
 *   <li>{@code QB_CASH_REWARD_OVERDUE}, an item of the hourly job {@value #OVERDUE_JOB}: a reward marked paid that the
 *       taker neither confirmed nor disputed in time is overdue, and the taker starts no new attempt until they do
 *       ({@link CashRewardChecks});</li>
 *   <li>{@code QB_CASH_REWARD_REMIND}, an item of the daily job {@value #REMIND_JOB}: a reward the sponsor has not
 *       marked paid {@code qb.reward.cash.pay-days} after the attempt gets the sponsor a task, once.</li>
 * </ul>
 * Each takes the reward's lock before reading it; the sponsor's and the taker's read it through their own datasets
 * (another's is 404). Every change is announced by an event the inbox turns into a message for the other side.
 */
public final class CashRewardProcesses {

    public static final String MARK_PAID = "QB_CASH_REWARD_MARK_PAID";
    public static final String CONFIRM = "QB_CASH_REWARD_CONFIRM";
    public static final String DISPUTE = "QB_CASH_REWARD_DISPUTE";
    public static final String RESOLVE = "QB_CASH_REWARD_RESOLVE";
    public static final String OVERDUE = "QB_CASH_REWARD_OVERDUE";
    public static final String REMIND = "QB_CASH_REWARD_REMIND";

    public static final String OVERDUE_JOB = "qb.cash-reward-overdue";
    public static final String REMIND_JOB = "qb.cash-reward-remind";
    /** The rewards marked paid whose time to confirm ran out before a cutoff. */
    public static final String OVERDUE_TEMPLATE = "qb.system.cash-rewards-overdue";
    /** The rewards still owed whose time to pay ran out before a cutoff and whose sponsor was not reminded yet. */
    public static final String UNPAID_TEMPLATE = "qb.system.cash-rewards-unpaid";

    /** Events for the messages (Q7): the other side learns of each step. */
    public static final String MARKED_PAID_EVENT = "qb.cash-reward.marked-paid";
    public static final String CONFIRMED_EVENT = "qb.cash-reward.confirmed";
    public static final String DISPUTED_EVENT = "qb.cash-reward.disputed";
    public static final String RESOLVED_EVENT = "qb.cash-reward.resolved";
    public static final String OVERDUE_EVENT = "qb.cash-reward.overdue";

    public record CashRewardRef(@NotNull UUID cashRewardId) {}

    /** @param note what the sponsor paid with or against (a receipt's number), for the record */
    public record MarkPaidInput(@NotNull UUID cashRewardId, @Size(max = QbRewards.NOTE_LENGTH) String note) {}

    /** @param note what is wrong, as the taker says it; required */
    public record DisputeInput(@NotNull UUID cashRewardId, @NotBlank @Size(max = 500) String note) {}

    /** @param resolution {@link CashRewardStatus#RESOLUTIONS}: paid (confirmed), unpaid (owed again) or void */
    public record ResolveInput(@NotNull UUID cashRewardId, @NotBlank @Size(max = 20) String resolution,
        @Size(max = 500) String note) {}

    public record CashRewardOutput(UUID cashRewardId, String status, long revision) {}

    static final String INPUT = "input";
    static final String ID = "cashRewardId";
    static final String REWARD = "reward";
    static final String PARAMS = "params";
    static final String OUTPUT = "output";
    static final String CHANGED = "changed";

    private static ProcessContext start(com.jabiz.process.ProcessStart start, Object input, UUID id) {
        ProcessContext ctx = new ProcessContext(start);
        ctx.put(INPUT, input);
        ctx.put(ID, id);
        return ctx;
    }

    /** Locks the reward and loads it through {@code dataset}: 404 unless the caller may see it. */
    private static <I, O> void lockAndLoad(ProcessDefinitionBuilder<I, O, ProcessContext> pb, String dataset) {
        pb.step("Lock the cash reward", HoldLock.<ProcessContext>exclusive(
                ctx -> QbRewards.cashLockName(ctx.get(ID))))
            .step("Load the cash reward", LoadEntity.by(dataset, ID, REWARD));
    }

    private static EntityInstance reward(ProcessContext ctx) {
        return ctx.get(REWARD, EntityInstance.class);
    }

    // ---- mark paid (sponsor) ---------------------------------------------------------------------------------------

    public static ProcessDefinition<MarkPaidInput, CashRewardOutput, ProcessContext> markPaid() {
        return ProcessDefinition.define(MARK_PAID, 1, MarkPaidInput.class, CashRewardOutput.class,
            ProcessContext.class, pb -> {
                pb.description("The sponsor records that they handed the taker the cash reward.")
                    .permissions(QbPermissions.PUBLICATION_WRITE)
                    .requiresVerifiedEmail()
                    .steps(CallerChecks::refuseBanned)
                    .actsOn(QbRewards.CASH_REWARD, "cashRewardId", a -> a.whenField("status", CashRewardStatus.DUE))
                    .contextFactory((s, input) -> start(s, input, input.cashRewardId()))
                    .outputMapper(ctx -> ctx.get(OUTPUT, CashRewardOutput.class))
                    .step("Load the days to confirm", LoadParams.<ProcessContext>of(ProcessContext::opTime, PARAMS,
                        QbParams.CASH_CONFIRM_DAYS));
                lockAndLoad(pb, QbRewards.sponsorDataset(QbRewards.CASH_REWARD));
                pb.compute("Mark it paid", (metadata, ctx) -> markPaid(ctx))
                    .step("Close the request to pay", CloseTasks.<ProcessContext>done(
                        ctx -> QbRewards.payTaskSource(ctx.get(ID))))
                    .step("Tell the taker", PublishEvent.<ProcessContext>when(CashRewardProcesses::changed,
                        MARKED_PAID_EVENT, CashRewardProcesses::payload));
            });
    }

    static void markPaid(ProcessContext ctx) {
        EntityInstance reward = reward(ctx);
        if (!in(ctx, reward, CashRewardStatus.DUE)) {
            return;
        }
        MarkPaidInput input = ctx.get(INPUT, MarkPaidInput.class);
        int days = days(ctx, QbParams.CASH_CONFIRM_DAYS);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("status", CashRewardStatus.MARKED_PAID);
        values.put("markedPaidAt", ctx.opTime());
        values.put("markedPaidNote", blankToNull(input.note()));
        values.put("confirmBy", ctx.opTime().plus(Duration.ofDays(days)));
        write(ctx, reward, values);
    }

    // ---- confirm, dispute (taker) ----------------------------------------------------------------------------------

    public static ProcessDefinition<CashRewardRef, CashRewardOutput, ProcessContext> confirm() {
        return ProcessDefinition.define(CONFIRM, 1, CashRewardRef.class, CashRewardOutput.class,
            ProcessContext.class, pb -> {
                pb.description("The taker confirms they received the cash reward: it is complete.")
                    .permissions(QbPermissions.ME)
                    .requiresVerifiedEmail()
                    .steps(CallerChecks::refuseBanned)
                    .actsOn(QbRewards.CASH_REWARD, "cashRewardId")
                    .contextFactory((s, input) -> start(s, input, input.cashRewardId()))
                    .outputMapper(ctx -> ctx.get(OUTPUT, CashRewardOutput.class));
                lockAndLoad(pb, QbRewards.takerDataset(QbRewards.CASH_REWARD));
                pb.compute("Confirm", (metadata, ctx) -> {
                        EntityInstance reward = reward(ctx);
                        if (in(ctx, reward, CashRewardStatus.MARKED_PAID, CashRewardStatus.OVERDUE)) {
                            Map<String, Object> values = new LinkedHashMap<>();
                            values.put("status", CashRewardStatus.CONFIRMED);
                            values.put("confirmedAt", ctx.opTime());
                            write(ctx, reward, values);
                        }
                    })
                    .step("Tell the sponsor", PublishEvent.<ProcessContext>when(CashRewardProcesses::changed,
                        CONFIRMED_EVENT, CashRewardProcesses::payload));
            });
    }

    public static ProcessDefinition<DisputeInput, CashRewardOutput, ProcessContext> dispute() {
        return ProcessDefinition.define(DISPUTE, 1, DisputeInput.class, CashRewardOutput.class,
            ProcessContext.class, pb -> {
                pb.description("The taker says the cash reward was not paid as owed; an administrator decides.")
                    .permissions(QbPermissions.ME)
                    .requiresVerifiedEmail()
                    .steps(CallerChecks::refuseBanned)
                    .actsOn(QbRewards.CASH_REWARD, "cashRewardId")
                    .contextFactory((s, input) -> start(s, input, input.cashRewardId()))
                    .outputMapper(ctx -> ctx.get(OUTPUT, CashRewardOutput.class));
                lockAndLoad(pb, QbRewards.takerDataset(QbRewards.CASH_REWARD));
                pb.compute("Dispute", (metadata, ctx) -> dispute(ctx))
                    .step("Ask an administrator to decide", CreateTask.<ProcessContext>when(
                        CashRewardProcesses::changed, ctx -> TaskSpec.forPermission(QbRewards.DISPUTE_TASK,
                                QbRewards.DISPUTE_TASK_TITLE, Map.of("code", reward(ctx).get("code"),
                                    "amount", String.valueOf((Object) reward(ctx).get("amount"))),
                                QbPermissions.CASH_RESOLVE)
                            .about(QbRewards.CASH_REWARD, reward(ctx).id())
                            .source(QbRewards.disputeTaskSource(reward(ctx).id()))))
                    .step("Tell the sponsor", PublishEvent.<ProcessContext>when(CashRewardProcesses::changed,
                        DISPUTED_EVENT, CashRewardProcesses::payload));
            });
    }

    static void dispute(ProcessContext ctx) {
        EntityInstance reward = reward(ctx);
        if (!in(ctx, reward, CashRewardStatus.DUE, CashRewardStatus.MARKED_PAID, CashRewardStatus.OVERDUE)) {
            return;
        }
        DisputeInput input = ctx.get(INPUT, DisputeInput.class);
        if (input.note().isBlank()) {
            ctx.reject(new Violation("note", RewardCodes.CASH_DISPUTE_NOTE, "Say what is wrong", Map.of()));
            return;
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("status", CashRewardStatus.DISPUTED);
        values.put("disputedAt", ctx.opTime());
        values.put("disputeNote", input.note().strip());
        write(ctx, reward, values);
    }

    // ---- resolve (administrator) -----------------------------------------------------------------------------------

    public static ProcessDefinition<ResolveInput, CashRewardOutput, ProcessContext> resolve() {
        return ProcessDefinition.define(RESOLVE, 1, ResolveInput.class, CashRewardOutput.class,
            ProcessContext.class, pb -> {
                pb.description("An administrator decides a disputed cash reward: paid, unpaid or void.")
                    .permissions(QbPermissions.CASH_RESOLVE)
                    .requiresVerifiedEmail()
                    .actsOn(QbRewards.CASH_REWARD, "cashRewardId",
                        a -> a.whenField("status", CashRewardStatus.DISPUTED))
                    .contextFactory((s, input) -> start(s, input, input.cashRewardId()))
                    .outputMapper(ctx -> ctx.get(OUTPUT, CashRewardOutput.class));
                pb.step("Load the days to pay", LoadParams.<ProcessContext>of(ProcessContext::opTime, PARAMS,
                    QbParams.CASH_PAY_DAYS));
                lockAndLoad(pb, QbRewards.defaultDataset(QbRewards.CASH_REWARD));
                pb.compute("Resolve", (metadata, ctx) -> resolve(ctx))
                    .step("Close the dispute's task", CloseTasks.<ProcessContext>done(
                        ctx -> QbRewards.disputeTaskSource(ctx.get(ID))))
                    .step("Close the request to pay", CloseTasks.<ProcessContext>done(
                        ctx -> QbRewards.payTaskSource(ctx.get(ID))))
                    .step("Tell both sides", PublishEvent.<ProcessContext>when(CashRewardProcesses::changed,
                        RESOLVED_EVENT, CashRewardProcesses::payload));
            });
    }

    static void resolve(ProcessContext ctx) {
        EntityInstance reward = reward(ctx);
        ResolveInput input = ctx.get(INPUT, ResolveInput.class);
        if (!CashRewardStatus.RESOLUTIONS.contains(input.resolution())) {
            ctx.reject(new Violation("resolution", RewardCodes.CASH_RESOLUTION, "Unknown resolution "
                + input.resolution(), Map.of("resolution", input.resolution())));
            return;
        }
        // Void is also the way out of a reward nobody disputes but that should not stand (a publication voided, a
        // taker gone); the other two decide a dispute.
        boolean voiding = CashRewardStatus.RESOLUTION_VOID.equals(input.resolution());
        if (voiding ? !in(ctx, reward, CashRewardStatus.DUE, CashRewardStatus.MARKED_PAID, CashRewardStatus.OVERDUE,
            CashRewardStatus.DISPUTED) : !in(ctx, reward, CashRewardStatus.DISPUTED)) {
            return;
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("status", switch (input.resolution()) {
            case CashRewardStatus.RESOLUTION_PAID -> CashRewardStatus.CONFIRMED;
            case CashRewardStatus.RESOLUTION_UNPAID -> CashRewardStatus.DUE;
            default -> CashRewardStatus.VOID;
        });
        if (CashRewardStatus.RESOLUTION_PAID.equals(input.resolution())) {
            values.put("confirmedAt", ctx.opTime());
        }
        if (CashRewardStatus.RESOLUTION_UNPAID.equals(input.resolution())) {
            // Owed again: the sponsor marks it paid once more, their time to pay and the reminder start afresh, and
            // so does the taker's time to confirm.
            values.put("markedPaidAt", null);
            values.put("markedPaidNote", null);
            values.put("confirmBy", null);
            values.put("overdueAt", null);
            values.put("remindedAt", null);
            values.put("payBy", ctx.opTime().plus(Duration.ofDays(days(ctx, QbParams.CASH_PAY_DAYS))));
        }
        values.put("resolvedAt", ctx.opTime());
        values.put("resolvedBy", ctx.request().actorId());
        values.put("resolution", input.resolution());
        values.put("resolveNote", blankToNull(input.note()));
        write(ctx, reward, values);
    }

    // ---- the jobs' items (system) ----------------------------------------------------------------------------------

    public static ProcessDefinition<CashRewardRef, CashRewardOutput, ProcessContext> overdue() {
        return ProcessDefinition.define(OVERDUE, 1, CashRewardRef.class, CashRewardOutput.class,
            ProcessContext.class, pb -> {
                pb.description("Marks a cash reward overdue: the taker neither confirmed nor disputed it in time.")
                    .permissions(QbPermissions.PLAY_SYSTEM)
                    .internal()
                    .contextFactory((s, input) -> start(s, input, input.cashRewardId()))
                    .outputMapper(ctx -> ctx.get(OUTPUT, CashRewardOutput.class));
                lockAndLoad(pb, QbRewards.defaultDataset(QbRewards.CASH_REWARD));
                pb.compute("Mark it overdue", (metadata, ctx) -> {
                        // Confirmed or disputed meanwhile, or not yet due: left as it is (the call is idempotent).
                        EntityInstance reward = reward(ctx);
                        Instant confirmBy = instant(reward.get("confirmBy"));
                        if (CashRewardStatus.MARKED_PAID.equals(reward.get("status")) && confirmBy != null
                            && confirmBy.isBefore(ctx.opTime())) {
                            Map<String, Object> values = new LinkedHashMap<>();
                            values.put("status", CashRewardStatus.OVERDUE);
                            values.put("overdueAt", ctx.opTime());
                            write(ctx, reward, values);
                        } else {
                            unchanged(ctx, reward);
                        }
                    })
                    .step("Tell the taker", PublishEvent.<ProcessContext>when(CashRewardProcesses::changed,
                        OVERDUE_EVENT, CashRewardProcesses::payload));
            });
    }

    public static ProcessDefinition<CashRewardRef, CashRewardOutput, ProcessContext> remind() {
        return ProcessDefinition.define(REMIND, 1, CashRewardRef.class, CashRewardOutput.class,
            ProcessContext.class, pb -> {
                pb.description("Reminds the sponsor, once, of a cash reward they have not paid in time.")
                    .permissions(QbPermissions.PLAY_SYSTEM)
                    .internal()
                    .contextFactory((s, input) -> start(s, input, input.cashRewardId()))
                    .outputMapper(ctx -> ctx.get(OUTPUT, CashRewardOutput.class));
                lockAndLoad(pb, QbRewards.defaultDataset(QbRewards.CASH_REWARD));
                pb.compute("Note the reminder", (metadata, ctx) -> {
                        EntityInstance reward = reward(ctx);
                        Instant payBy = instant(reward.get("payBy"));
                        if (CashRewardStatus.DUE.equals(reward.get("status")) && reward.get("remindedAt") == null
                            && payBy != null && payBy.isBefore(ctx.opTime())) {
                            Map<String, Object> values = new LinkedHashMap<>();
                            values.put("remindedAt", ctx.opTime());
                            write(ctx, reward, values);
                        } else {
                            unchanged(ctx, reward);
                        }
                    })
                    .step("Ask the sponsor to pay", CreateTask.<ProcessContext>when(CashRewardProcesses::changed,
                        ctx -> TaskSpec.forUser(QbRewards.PAY_TASK, QbRewards.PAY_TASK_TITLE,
                                Map.of("code", reward(ctx).get("code"),
                                    "amount", String.valueOf((Object) reward(ctx).get("amount"))),
                                reward(ctx).get("publisherId"))
                            .about(QbRewards.CASH_REWARD, reward(ctx).id())
                            .source(QbRewards.payTaskSource(reward(ctx).id()))));
            });
    }

    // ---- shared ----------------------------------------------------------------------------------------------------

    /** Whether the reward is in one of {@code from}; otherwise 422 {@code QB_CASH_REWARD_STATE}. */
    static boolean in(ProcessContext ctx, EntityInstance reward, String... from) {
        String status = reward.get("status");
        if (!List.of(from).contains(status)) {
            ctx.reject(new Violation("cashRewardId", RewardCodes.CASH_STATE, "The cash reward is " + status,
                Map.of("status", String.valueOf(status))));
            return false;
        }
        return true;
    }

    static void write(ProcessContext ctx, EntityInstance reward, Map<String, Object> values) {
        long revision = number(reward.get("revision")) + 1;
        Map<String, Object> next = new LinkedHashMap<>(values);
        next.put("revision", BigDecimal.valueOf(revision));
        ctx.changes().update(QbRewards.CASH_REWARD, reward.id(), reward.version(), next);
        ctx.put(CHANGED, true);
        ctx.put(OUTPUT, new CashRewardOutput(uuid(reward.id()),
            values.containsKey("status") ? (String) values.get("status") : reward.get("status"), revision));
    }

    static void unchanged(ProcessContext ctx, EntityInstance reward) {
        ctx.put(OUTPUT, new CashRewardOutput(uuid(reward.id()), reward.get("status"),
            number(reward.get("revision"))));
    }

    static boolean changed(ProcessContext ctx) {
        return !ctx.hasViolations() && Boolean.TRUE.equals(ctx.get(CHANGED));
    }

    /** What the messages need of a cash reward: who, which attempt, how much, which code. */
    static Map<String, Object> payload(ProcessContext ctx) {
        return payload(reward(ctx), ctx.get(OUTPUT, CashRewardOutput.class).status());
    }

    public static Map<String, Object> payload(EntityInstance reward, String status) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("cashRewardId", String.valueOf(reward.id()));
        payload.put("attemptId", reward.get("attemptId"));
        payload.put("publicationId", String.valueOf((Object) reward.get("publicationId")));
        payload.put("userId", reward.get("userId"));
        payload.put("publisherKind", reward.get("publisherKind"));
        payload.put("publisherId", reward.get("publisherId"));
        payload.put("amount", reward.get("amount"));
        payload.put("code", reward.get("code"));
        payload.put("status", status);
        return payload;
    }

    static int days(ProcessContext ctx, String key) {
        return QbParams.days(ctx.get(PARAMS, ParamValues.class), key);
    }

    static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text.strip();
    }

    static long number(Object value) {
        return value == null ? 0 : new BigDecimal(value.toString()).longValueExact();
    }

    static UUID uuid(Object value) {
        return value == null ? null : value instanceof UUID id ? id : UUID.fromString(value.toString());
    }

    static Instant instant(Object value) {
        return value == null ? null : value instanceof java.time.OffsetDateTime time ? time.toInstant()
            : value instanceof Instant time ? time : Instant.parse(value.toString());
    }

    private CashRewardProcesses() {}
}
