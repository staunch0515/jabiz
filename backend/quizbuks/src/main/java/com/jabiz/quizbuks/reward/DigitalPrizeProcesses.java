package com.jabiz.quizbuks.reward;

import com.jabiz.entity.Violation;
import com.jabiz.param.ParamValues;
import com.jabiz.process.NoMetadata;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.process.ProcessDefinitionBuilder;
import com.jabiz.process.StepSpec;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.identity.CallerChecks;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.quizbuks.operator.OperatorChecks;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.process.steps.HoldLock;
import com.jabiz.runtime.process.steps.LoadEntity;
import com.jabiz.runtime.process.steps.LoadParams;
import com.jabiz.runtime.process.steps.PublishEvent;
import com.jabiz.runtime.task.CloseTasks;
import com.jabiz.runtime.task.CreateTask;
import com.jabiz.runtime.task.TaskSpec;
import com.jabiz.security.Sensitive;
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
 * A digital prize's way from won to complete (Q33, docs/quizbuks/plans/Q33-digital-prize.md, D-Q33-6, D-Q33-7):
 * <ul>
 *   <li>{@code QB_DIGITAL_PRIZE_OPEN}: the winner reads the content (a code or link) of a delivered prize; the first
 *       time is recorded ({@code openedAt}). The content is in the output only, sensitive, never in the records.</li>
 *   <li>{@code QB_DIGITAL_PRIZE_CONFIRM}: the taker confirms the prize works: complete.</li>
 *   <li>{@code QB_DIGITAL_PRIZE_DISPUTE}: the taker says what is wrong (nothing arrived, the code is invalid); an
 *       administrator gets a task.</li>
 *   <li>{@code QB_DIGITAL_PRIZE_RESOLVE}: the administrator finds it delivered (confirmed), undelivered (owed again,
 *       the sponsor sends another) or void.</li>
 * </ul>
 * Each takes the prize's lock before reading it; the taker reads theirs through their own dataset (another's is
 * 404). Every change is announced by an event the inbox turns into a message for the other side.
 */
public final class DigitalPrizeProcesses {

    public static final String OPEN = "QB_DIGITAL_PRIZE_OPEN";
    public static final String CONFIRM = "QB_DIGITAL_PRIZE_CONFIRM";
    public static final String DISPUTE = "QB_DIGITAL_PRIZE_DISPUTE";
    public static final String RESOLVE = "QB_DIGITAL_PRIZE_RESOLVE";

    public static final String CONFIRMED_EVENT = "qb.digital-prize.confirmed";
    public static final String DISPUTED_EVENT = "qb.digital-prize.disputed";
    public static final String RESOLVED_EVENT = "qb.digital-prize.resolved";

    public record PrizeRef(@NotNull UUID digitalPrizeId) {}

    /** @param note what is wrong, as the taker says it; required */
    public record DisputeInput(@NotNull UUID digitalPrizeId, @NotBlank @Size(max = 500) String note) {}

    /** @param resolution {@link DigitalPrizeStatus#RESOLUTIONS}: delivered, undelivered (owed again) or void */
    public record ResolveInput(@NotNull UUID digitalPrizeId, @NotBlank @Size(max = 20) String resolution,
        @Size(max = 500) String note) {}

    public record PrizeOutput(UUID digitalPrizeId, String status, long revision) {}

    /**
     * @param prizeContent the code or link, for the winner's eyes: returned to them (this is the one place they read
     *                     it) and kept from every record, the output held for a replay included (platform 16zh)
     */
    public record OpenOutput(UUID digitalPrizeId, String status, String description,
        @Sensitive(returned = true) String prizeContent, Instant openedAt) {

        @Override
        public String toString() {
            return "OpenOutput[digitalPrizeId=" + digitalPrizeId + ", status=" + status + ", prizeContent=***]";
        }
    }

    static final String INPUT = "input";
    static final String ID = "digitalPrizeId";
    static final String PRIZE = "prize";
    static final String CONTENT = "content";
    static final String PARAMS = "params";
    static final String OUTPUT = "output";
    static final String CHANGED = "changed";

    private static ProcessContext start(com.jabiz.process.ProcessStart start, Object input, UUID id) {
        ProcessContext ctx = new ProcessContext(start);
        ctx.put(INPUT, input);
        ctx.put(ID, id);
        return ctx;
    }

    private static <I, O> void lockAndLoad(ProcessDefinitionBuilder<I, O, ProcessContext> pb, String dataset) {
        pb.step("Lock the prize", HoldLock.<ProcessContext>exclusive(
                ctx -> QbDigitalPrizes.prizeLockName(ctx.get(ID))))
            .step("Load the prize", LoadEntity.by(dataset, ID, PRIZE));
    }

    static EntityInstance prize(ProcessContext ctx) {
        return ctx.get(PRIZE, EntityInstance.class);
    }

    // ---- open (taker) ----------------------------------------------------------------------------------------------

    public static ProcessDefinition<PrizeRef, OpenOutput, ProcessContext> open() {
        return ProcessDefinition.define(OPEN, 1, PrizeRef.class, OpenOutput.class, ProcessContext.class, pb -> {
            pb.description("Shows the winner the content of their digital prize (a code or a link).")
                .permissions(QbPermissions.ME)
                .requiresVerifiedEmail()
                .steps(CallerChecks::refuseBanned)
                .actsOn(QbDigitalPrizes.DIGITAL_PRIZE, "digitalPrizeId")
                .contextFactory((s, input) -> start(s, input, input.digitalPrizeId()))
                .outputMapper(ctx -> ctx.get(OUTPUT, OpenOutput.class));
            lockAndLoad(pb, QbDigitalPrizes.takerDataset(QbDigitalPrizes.DIGITAL_PRIZE));
            pb.compute("Check the prize", (metadata, ctx) -> {
                    EntityInstance prize = prize(ctx);
                    if (in(ctx, prize, DigitalPrizeStatus.DELIVERED, DigitalPrizeStatus.CONFIRMED,
                        DigitalPrizeStatus.DISPUTED) && prize.get("sealedContent") == null) {
                        ctx.reject(new Violation("digitalPrizeId", RewardCodes.DIGITAL_STATE, "The prize has no "
                            + "content yet", Map.of("status", String.valueOf((Object) prize.get("status")))));
                    }
                })
                .step("Open the content", StepSpec.of(OpenDigitalPrize.class, NoMetadata.INSTANCE))
                .compute("Note the opening", (metadata, ctx) -> {
                    if (ctx.hasViolations()) {
                        return;
                    }
                    EntityInstance prize = prize(ctx);
                    Instant openedAt = instant(prize.get("openedAt"));
                    if (openedAt == null) {
                        // The first time only: a version says when the winner first saw it, later reads add none.
                        write(ctx, prize, Map.of("openedAt", ctx.opTime()));
                        openedAt = ctx.opTime();
                    }
                    ctx.put(OUTPUT, new OpenOutput(uuid(prize.id()), prize.get("status"), prize.get("description"),
                        ctx.get(CONTENT, String.class), openedAt));
                });
        });
    }

    // ---- confirm, dispute (taker) ----------------------------------------------------------------------------------

    public static ProcessDefinition<PrizeRef, PrizeOutput, ProcessContext> confirm() {
        return ProcessDefinition.define(CONFIRM, 1, PrizeRef.class, PrizeOutput.class, ProcessContext.class, pb -> {
            pb.description("The taker confirms the digital prize works: it is complete.")
                .permissions(QbPermissions.ME)
                .requiresVerifiedEmail()
                .steps(CallerChecks::refuseBanned)
                .actsOn(QbDigitalPrizes.DIGITAL_PRIZE, "digitalPrizeId",
                    a -> a.whenField("status", DigitalPrizeStatus.DELIVERED))
                .contextFactory((s, input) -> start(s, input, input.digitalPrizeId()))
                .outputMapper(ctx -> ctx.get(OUTPUT, PrizeOutput.class));
            lockAndLoad(pb, QbDigitalPrizes.takerDataset(QbDigitalPrizes.DIGITAL_PRIZE));
            pb.compute("Confirm", (metadata, ctx) -> {
                    EntityInstance prize = prize(ctx);
                    if (in(ctx, prize, DigitalPrizeStatus.DELIVERED)) {
                        Map<String, Object> values = new LinkedHashMap<>();
                        values.put("status", DigitalPrizeStatus.CONFIRMED);
                        values.put("confirmedAt", ctx.opTime());
                        write(ctx, prize, values);
                    }
                })
                .step("Tell the sponsor", PublishEvent.<ProcessContext>when(DigitalPrizeProcesses::changed,
                    CONFIRMED_EVENT, DigitalPrizeProcesses::payload));
        });
    }

    public static ProcessDefinition<DisputeInput, PrizeOutput, ProcessContext> dispute() {
        return ProcessDefinition.define(DISPUTE, 1, DisputeInput.class, PrizeOutput.class, ProcessContext.class,
            pb -> {
                pb.description("The taker says the digital prize did not arrive or does not work; an administrator "
                        + "decides.")
                    .permissions(QbPermissions.ME)
                    .requiresVerifiedEmail()
                    .steps(CallerChecks::refuseBanned)
                    .actsOn(QbDigitalPrizes.DIGITAL_PRIZE, "digitalPrizeId")
                    .contextFactory((s, input) -> start(s, input, input.digitalPrizeId()))
                    .outputMapper(ctx -> ctx.get(OUTPUT, PrizeOutput.class));
                lockAndLoad(pb, QbDigitalPrizes.takerDataset(QbDigitalPrizes.DIGITAL_PRIZE));
                // Q59c-2: the dispute is decided by the sponsor's operator (a taker has no scope: the check passes).
                OperatorChecks.ownSponsor(pb, ctx -> prize(ctx) == null ? null : prize(ctx).get("publisherId"));
                pb.compute("Dispute", (metadata, ctx) -> dispute(ctx))
                    .step("Ask an administrator to decide", CreateTask.<ProcessContext>when(
                        DigitalPrizeProcesses::changed, ctx -> TaskSpec.forPermission(QbDigitalPrizes.DISPUTE_TASK,
                                QbDigitalPrizes.DISPUTE_TASK_TITLE, Map.of("amount",
                                    String.valueOf((Object) prize(ctx).get("amount")), "hint",
                                    prize(ctx).get("hint") == null ? "" : String.valueOf((Object) prize(ctx).get("hint"))),
                                QbPermissions.DIGITAL_RESOLVE)
                            .about(QbDigitalPrizes.DIGITAL_PRIZE, prize(ctx).id())
                            .source(QbDigitalPrizes.disputeTaskSource(prize(ctx).id()))
                            .scope(OperatorChecks.sponsorScope(ctx))))
                    .step("Tell the sponsor", PublishEvent.<ProcessContext>when(DigitalPrizeProcesses::changed,
                        DISPUTED_EVENT, DigitalPrizeProcesses::payload));
            });
    }

    static void dispute(ProcessContext ctx) {
        EntityInstance prize = prize(ctx);
        if (!in(ctx, prize, DigitalPrizeStatus.DUE, DigitalPrizeStatus.DELIVERED, DigitalPrizeStatus.OVERDUE)) {
            return;
        }
        DisputeInput input = ctx.get(INPUT, DisputeInput.class);
        if (input.note().isBlank()) {
            ctx.reject(new Violation("note", RewardCodes.DIGITAL_DISPUTE_NOTE, "Say what is wrong", Map.of()));
            return;
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("status", DigitalPrizeStatus.DISPUTED);
        values.put("disputedAt", ctx.opTime());
        values.put("disputeNote", input.note().strip());
        write(ctx, prize, values);
    }

    // ---- resolve (administrator) -----------------------------------------------------------------------------------

    public static ProcessDefinition<ResolveInput, PrizeOutput, ProcessContext> resolve() {
        return ProcessDefinition.define(RESOLVE, 1, ResolveInput.class, PrizeOutput.class, ProcessContext.class,
            pb -> {
                pb.description("An administrator decides a disputed digital prize: delivered, undelivered or void.")
                    .permissions(QbPermissions.DIGITAL_RESOLVE)
                    .requiresVerifiedEmail()
                    .actsOn(QbDigitalPrizes.DIGITAL_PRIZE, "digitalPrizeId",
                        a -> a.whenField("status", DigitalPrizeStatus.DISPUTED))
                    .contextFactory((s, input) -> start(s, input, input.digitalPrizeId()))
                    .outputMapper(ctx -> ctx.get(OUTPUT, PrizeOutput.class));
                pb.step("Load the days to send", LoadParams.<ProcessContext>of(ProcessContext::opTime, PARAMS,
                    QbParams.DIGITAL_SEND_DAYS));
                lockAndLoad(pb, QbDigitalPrizes.defaultDataset(QbDigitalPrizes.DIGITAL_PRIZE));
                // Q59c-2: an operator's member decides their own sponsors' disputes only.
                OperatorChecks.ownSponsor(pb, ctx -> prize(ctx) == null ? null : prize(ctx).get("publisherId"));
                pb.compute("Resolve", (metadata, ctx) -> resolve(ctx))
                    .step("Close the dispute's task", CloseTasks.<ProcessContext>done(
                        ctx -> QbDigitalPrizes.disputeTaskSource(ctx.get(ID))))
                    .step("Tell both sides", PublishEvent.<ProcessContext>when(DigitalPrizeProcesses::changed,
                        RESOLVED_EVENT, DigitalPrizeProcesses::payload));
            });
    }

    static void resolve(ProcessContext ctx) {
        EntityInstance prize = prize(ctx);
        ResolveInput input = ctx.get(INPUT, ResolveInput.class);
        if (!DigitalPrizeStatus.RESOLUTIONS.contains(input.resolution())) {
            ctx.reject(new Violation("resolution", RewardCodes.DIGITAL_RESOLUTION, "Unknown resolution "
                + input.resolution(), Map.of("resolution", input.resolution())));
            return;
        }
        boolean voiding = DigitalPrizeStatus.RESOLUTION_VOID.equals(input.resolution());
        if (voiding ? !in(ctx, prize, DigitalPrizeStatus.DUE, DigitalPrizeStatus.DELIVERED,
            DigitalPrizeStatus.OVERDUE, DigitalPrizeStatus.DISPUTED) : !in(ctx, prize, DigitalPrizeStatus.DISPUTED)) {
            return;
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("status", switch (input.resolution()) {
            case DigitalPrizeStatus.RESOLUTION_DELIVERED -> DigitalPrizeStatus.CONFIRMED;
            case DigitalPrizeStatus.RESOLUTION_UNDELIVERED -> DigitalPrizeStatus.DUE;
            default -> DigitalPrizeStatus.VOID;
        });
        if (DigitalPrizeStatus.RESOLUTION_DELIVERED.equals(input.resolution())) {
            values.put("confirmedAt", ctx.opTime());
        }
        if (DigitalPrizeStatus.RESOLUTION_UNDELIVERED.equals(input.resolution())) {
            // Owed again: whatever was sent is dropped, the sponsor sends another within the days to send.
            values.put("sealedContent", null);
            values.put("hint", null);
            values.put("deliveredAt", null);
            values.put("deliveredBy", null);
            values.put("openedAt", null);
            values.put("confirmBy", null);
            values.put("overdueAt", null);
            values.put("remindedAt", null);
            values.put("sendBy", ctx.opTime().plus(Duration.ofDays(QbParams.days(ctx.get(PARAMS, ParamValues.class),
                QbParams.DIGITAL_SEND_DAYS))));
        }
        values.put("resolvedAt", ctx.opTime());
        values.put("resolvedBy", ctx.request().actorId());
        values.put("resolution", input.resolution());
        values.put("resolveNote", blankToNull(input.note()));
        write(ctx, prize, values);
    }

    // ---- shared ----------------------------------------------------------------------------------------------------

    /** Whether the prize is in one of {@code from}; otherwise 422 {@code QB_DIGITAL_PRIZE_STATE}. */
    static boolean in(ProcessContext ctx, EntityInstance prize, String... from) {
        String status = prize.get("status");
        if (!List.of(from).contains(status)) {
            ctx.reject(new Violation("digitalPrizeId", RewardCodes.DIGITAL_STATE, "The prize is " + status,
                Map.of("status", String.valueOf(status))));
            return false;
        }
        return true;
    }

    static void write(ProcessContext ctx, EntityInstance prize, Map<String, Object> values) {
        long revision = number(prize.get("revision")) + 1;
        Map<String, Object> next = new LinkedHashMap<>(values);
        next.put("revision", BigDecimal.valueOf(revision));
        ctx.changes().update(QbDigitalPrizes.DIGITAL_PRIZE, prize.id(), prize.version(), next);
        ctx.put(CHANGED, true);
        ctx.put(OUTPUT, new PrizeOutput(uuid(prize.id()),
            values.containsKey("status") ? (String) values.get("status") : prize.get("status"), revision));
    }

    static boolean changed(ProcessContext ctx) {
        return !ctx.hasViolations() && Boolean.TRUE.equals(ctx.get(CHANGED));
    }

    /** What the messages need of a prize: who, which attempt, how much; never its content. */
    static Map<String, Object> payload(ProcessContext ctx) {
        return payload(prize(ctx), ctx.get(OUTPUT, PrizeOutput.class).status());
    }

    public static Map<String, Object> payload(EntityInstance prize, String status) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("digitalPrizeId", String.valueOf(prize.id()));
        payload.put("attemptId", prize.get("attemptId"));
        payload.put("publicationId", String.valueOf((Object) prize.get("publicationId")));
        payload.put("userId", prize.get("userId"));
        payload.put("publisherKind", prize.get("publisherKind"));
        payload.put("publisherId", prize.get("publisherId"));
        payload.put("amount", prize.get("amount"));
        payload.put("status", status);
        return payload;
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

    private DigitalPrizeProcesses() {}
}
