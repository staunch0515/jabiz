package com.jabiz.quizbuks.videouse;

import com.jabiz.entity.Violation;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.process.ProcessDefinitionBuilder;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.identity.AccountEditing;
import com.jabiz.quizbuks.identity.CallerChecks;
import com.jabiz.quizbuks.publishing.PublisherKind;
import com.jabiz.quizbuks.publishing.QbPublications;
import com.jabiz.runtime.BusinessRuleViolationException;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.EntityNotFoundException;
import com.jabiz.runtime.process.steps.HoldLock;
import com.jabiz.runtime.process.steps.QueryEntities;
import com.jabiz.runtime.task.CloseTasks;
import com.jabiz.runtime.task.CreateTask;
import com.jabiz.runtime.task.TaskSpec;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * The ledger of shared videos' uses (F-S14, docs/quizbuks/plans/impl/F-S14.md): what the user of another sponsor's
 * shared video (the publication's sponsor) says of the use, and the provider's answer. The platform records both and
 * judges neither: no money moves on it, and nothing goes into its books.
 * <ul>
 *   <li>{@value #SAVE}: the user writes the period, the pricing, the price and a note of a publication that went on with
 *       another's shared video (one use each); a correction is a new version and a draft again;</li>
 *   <li>{@value #SUBMIT}: a draft goes to the provider, who gets a task;</li>
 *   <li>{@value #CONFIRM} / {@value #REJECT}: the provider confirms the revision it read, or sends it back with a
 *       reason (the user gets a task to correct and submit it again).</li>
 * </ul>
 * Every act takes the publication's turn of its use ({@link QbVideoUses#lockName}), never a publication's, start or
 * wallet lock. A use another caller is not a side of is not found.
 */
public final class VideoUseProcesses {

    public static final String SAVE = "QB_VIDEO_USE_SAVE";
    public static final String SUBMIT = "QB_VIDEO_USE_SUBMIT";
    public static final String CONFIRM = "QB_VIDEO_USE_CONFIRM";
    public static final String REJECT = "QB_VIDEO_USE_REJECT";

    /** The provider's task to answer a use, and the user's to correct one sent back. */
    public static final String CONFIRM_TASK = "qb.video-use.confirm";
    public static final String CONFIRM_TASK_TITLE = "task.qb.video-use.confirm";
    public static final String RESUBMIT_TASK = "qb.video-use.resubmit";
    public static final String RESUBMIT_TASK_TITLE = "task.qb.video-use.resubmit";

    static final String INPUT = "input";
    static final String OUTPUT = "output";
    static final String FOUND = "found";
    static final String USES = "uses";
    static final String CARD = "card";
    static final String ASKED = "askProvider";
    static final String SENT_BACK = "sentBack";
    /** The revision of the use before this act (its reminders' key). */
    static final String BEFORE = "revisionBefore";

    /**
     * @param videoUseId   the use to correct; none for a new one
     * @param publicationId the publication (a new use only; a correction keeps its publication)
     * @param periodFrom   the period's first day
     * @param periodTo     its last day
     * @param pricing      {@code FIXED}, {@code PER_MILLE} or {@code PER_TAKER}
     * @param price        the fixed price, or the price per thousand exposures or per taker who went on to answer
     * @param baseRevision the revision the caller edited; when given and no longer current, 400
     */
    public record SaveInput(@Size(max = QbVideoUses.ID_LENGTH) String videoUseId, UUID publicationId,
        @NotNull LocalDate periodFrom, @NotNull LocalDate periodTo,
        @NotNull @Pattern(regexp = "FIXED|PER_MILLE|PER_TAKER") String pricing, @NotNull BigDecimal price,
        @Size(max = QbVideoUses.NOTE_LENGTH) String note, Long baseRevision) {}

    /** @param baseRevision the revision the caller read (required for the provider's answer) */
    public record UseRef(@NotBlank @Size(max = QbVideoUses.ID_LENGTH) String videoUseId, Long baseRevision) {}

    public record RejectInput(@NotBlank @Size(max = QbVideoUses.ID_LENGTH) String videoUseId,
        @NotBlank @Size(max = QbVideoUses.REASON_LENGTH) String reason, @NotNull Long baseRevision) {}

    public record VideoUseOutput(String videoUseId, String status, long revision) {}

    private VideoUseProcesses() {}

    /** Every act: its permission, a verified address, not banned. */
    private static <I> void common(ProcessDefinitionBuilder<I, VideoUseOutput, ProcessContext> pb, String permission,
        String description) {
        pb.description(description)
            .permissions(permission)
            .requiresVerifiedEmail()
            .contextFactory((start, input) -> {
                ProcessContext ctx = new ProcessContext(start);
                ctx.put(INPUT, input);
                return ctx;
            })
            .outputMapper(ctx -> ctx.get(OUTPUT, VideoUseOutput.class));
        CallerChecks.refuseBanned(pb);
    }

    // ---- save ------------------------------------------------------------------------------------------------------

    public static ProcessDefinition<SaveInput, VideoUseOutput, ProcessContext> save() {
        return ProcessDefinition.define(SAVE, 1, SaveInput.class, VideoUseOutput.class, ProcessContext.class, pb -> {
            common(pb, QbPermissions.PUBLICATION_WRITE, "Writes or corrects the ledger's use of another Sponsor's "
                + "shared video by a publication of the caller's: the period, the pricing and the price.");
            lockAndLoad(pb, ctx -> saveInput(ctx).videoUseId(), QbVideoUses.SPONSOR,
                ctx -> saveInput(ctx).publicationId() == null ? null : saveInput(ctx).publicationId().toString());
            pb.compute("Write the use", (metadata, ctx) -> saved(ctx));
            pb.step("Close the request to correct it", CloseTasks.<ProcessContext>done(ctx -> resubmitSource(
                ctx.contains(OUTPUT) ? ctx.get(OUTPUT, VideoUseOutput.class).videoUseId() : "new")));
        });
    }

    static void saved(ProcessContext ctx) {
        SaveInput input = saveInput(ctx);
        EntityInstance use = current(ctx);
        EntityInstance card = first(ctx, CARD);
        if (use == null) {
            if (input.videoUseId() != null) {
                throw notFound(input.videoUseId());
            }
            if (input.publicationId() == null || card == null || !PublisherKind.sponsor(card.get(PublisherKind.KIND_FIELD))
                || !ctx.request().actorId().equals(String.valueOf((Object) card.get(PublisherKind.ID_FIELD)))) {
                throw new EntityNotFoundException(QbPublications.PUBLICATION + " " + input.publicationId()
                    + " not found");
            }
            if (!VideoUseRules.othersVideo(card.get("sharedVideoId"), card.get("videoProviderId"),
                card.get(PublisherKind.ID_FIELD))) {
                throw refusal(VideoUseCodes.NOT_SHARED, "The publication uses no other Sponsor's shared video",
                    Map.of());
            }
            if (!list(ctx, USES).isEmpty()) {
                throw refusal(VideoUseCodes.EXISTS, "The publication has its use already",
                    Map.of("videoUseId", String.valueOf(list(ctx, USES).getFirst().id())));
            }
        } else {
            requireStatus(use, VideoUseRules.savable(use.get("status")));
            if (!AccountEditing.current(ctx, use, input.baseRevision(), VideoUseCodes.CHANGED)) {
                return;
            }
        }
        VideoUseRules.problems(new VideoUseRules.Entry(input.periodFrom(), input.periodTo(), input.pricing(),
            input.price())).forEach(ctx::reject);
        if (ctx.hasViolations()) {
            return;
        }
        Map<String, Object> values = new LinkedHashMap<>();
        if (use == null) {
            values.put("publicationId", input.publicationId().toString());
            values.put("sharedVideoId", String.valueOf((Object) card.get("sharedVideoId")));
            values.put("sponsorId", ctx.request().actorId());
            values.put("providerId", String.valueOf((Object) card.get("videoProviderId")));
        }
        values.put("periodFrom", input.periodFrom());
        values.put("periodTo", input.periodTo());
        values.put("pricing", input.pricing());
        values.put("price", input.price().setScale(0));
        values.put("note", blankToNull(input.note()));
        values.put("status", VideoUseRules.afterSave());
        write(ctx, use, values);
    }

    // ---- submit ----------------------------------------------------------------------------------------------------

    public static ProcessDefinition<UseRef, VideoUseOutput, ProcessContext> submit() {
        return ProcessDefinition.define(SUBMIT, 1, UseRef.class, VideoUseOutput.class, ProcessContext.class, pb -> {
            common(pb, QbPermissions.PUBLICATION_WRITE, "Sends the ledger's use of a shared video to its provider to "
                + "confirm.");
            lockAndLoad(pb, ctx -> ref(ctx).videoUseId(), QbVideoUses.SPONSOR, ctx -> null);
            pb.compute("Send it", (metadata, ctx) -> {
                EntityInstance use = current(ctx);
                requireStatus(use, VideoUseRules.submittable(use.get("status")));
                if (!AccountEditing.current(ctx, use, ref(ctx).baseRevision(), VideoUseCodes.CHANGED)) {
                    return;
                }
                Map<String, Object> values = new LinkedHashMap<>();
                values.put("status", VideoUseRules.SUBMITTED);
                values.put("submittedAt", ctx.opTime());
                // A new submission: the provider's last reason was answered by the correction.
                values.put("rejectedAt", null);
                values.put("rejectReason", null);
                ctx.put(ASKED, true);
                write(ctx, use, values);
            });
            pb.step("Ask the provider to confirm", CreateTask.<ProcessContext>when(ctx -> ctx.contains(ASKED), ctx -> {
                EntityInstance use = current(ctx);
                return TaskSpec.forUser(CONFIRM_TASK, CONFIRM_TASK_TITLE, Map.of("name", name(ctx)),
                        String.valueOf((Object) use.get("providerId")))
                    .about(QbVideoUses.USE, use.id())
                    .source(confirmSource(use.id()));
            }));
            pb.step("Close the request to correct it", CloseTasks.<ProcessContext>done(
                ctx -> resubmitSource(ref(ctx).videoUseId())));
        });
    }

    // ---- the provider's answer -------------------------------------------------------------------------------------

    public static ProcessDefinition<UseRef, VideoUseOutput, ProcessContext> confirm() {
        return ProcessDefinition.define(CONFIRM, 1, UseRef.class, VideoUseOutput.class, ProcessContext.class, pb -> {
            common(pb, QbPermissions.CONTENT_WRITE, "Confirms the ledger's use of the caller's shared video as read.");
            lockAndLoad(pb, ctx -> ref(ctx).videoUseId(), QbVideoUses.PROVIDER, ctx -> null);
            pb.compute("Confirm it", (metadata, ctx) -> {
                EntityInstance use = current(ctx);
                requireStatus(use, VideoUseRules.answerable(use.get("status")));
                if (ref(ctx).baseRevision() == null) {
                    ctx.reject(new Violation("baseRevision", VideoUseCodes.CHANGED, "Confirm the revision you read",
                        Map.of("revision", AccountEditing.number(use.get("revision")))));
                    return;
                }
                if (!AccountEditing.current(ctx, use, ref(ctx).baseRevision(), VideoUseCodes.CHANGED)) {
                    return;
                }
                Map<String, Object> values = new LinkedHashMap<>();
                values.put("status", VideoUseRules.CONFIRMED);
                values.put("confirmedAt", ctx.opTime());
                values.put("confirmedBy", ctx.request().actorId());
                values.put("confirmedRevision", use.get("revision"));
                write(ctx, use, values);
            });
            closeProviderTasks(pb, ctx -> ref(ctx).videoUseId());
        });
    }

    public static ProcessDefinition<RejectInput, VideoUseOutput, ProcessContext> reject() {
        return ProcessDefinition.define(REJECT, 1, RejectInput.class, VideoUseOutput.class, ProcessContext.class,
            pb -> {
                common(pb, QbPermissions.CONTENT_WRITE, "Sends the ledger's use of the caller's shared video back to "
                    + "its user, with a reason.");
                lockAndLoad(pb, ctx -> ctx.get(INPUT, RejectInput.class).videoUseId(), QbVideoUses.PROVIDER,
                    ctx -> null);
                pb.compute("Send it back", (metadata, ctx) -> {
                    RejectInput input = ctx.get(INPUT, RejectInput.class);
                    EntityInstance use = current(ctx);
                    requireStatus(use, VideoUseRules.answerable(use.get("status")));
                    if (!AccountEditing.current(ctx, use, input.baseRevision(), VideoUseCodes.CHANGED)) {
                        return;
                    }
                    Map<String, Object> values = new LinkedHashMap<>();
                    values.put("status", VideoUseRules.REJECTED);
                    values.put("rejectedAt", ctx.opTime());
                    values.put("rejectReason", input.reason().strip());
                    ctx.put(SENT_BACK, true);
                    write(ctx, use, values);
                });
                closeProviderTasks(pb, ctx -> ctx.get(INPUT, RejectInput.class).videoUseId());
                pb.step("Ask the user to correct it", CreateTask.<ProcessContext>when(ctx -> ctx.contains(SENT_BACK),
                    ctx -> {
                        EntityInstance use = current(ctx);
                        return TaskSpec.forUser(RESUBMIT_TASK, RESUBMIT_TASK_TITLE, Map.of("name", name(ctx)),
                                String.valueOf((Object) use.get("sponsorId")))
                            .about(QbVideoUses.USE, use.id())
                            .source(resubmitSource(use.id()));
                    }));
            });
    }

    // ---- shared ----------------------------------------------------------------------------------------------------

    /**
     * Finds the use (none asked: a new one of {@code newPublication}), refuses one the caller is not {@code side} of as
     * not found, takes the publication's turn and reads the use, the publication's uses and its card again.
     */
    private static <I, O> void lockAndLoad(ProcessDefinitionBuilder<I, O, ProcessContext> pb,
        Function<ProcessContext, String> videoUseId, String side, Function<ProcessContext, String> newPublication) {
        pb.step("Find the use", QueryEntities.<ProcessContext>of(QbVideoUses.defaultDataset(QbVideoUses.USE),
                ctx -> byId("videoUseId", videoUseId.apply(ctx)), FOUND))
            .compute("Refuse a use not the caller's", (metadata, ctx) -> {
                if (videoUseId.apply(ctx) == null) {
                    return;
                }
                EntityInstance found = first(ctx, FOUND);
                if (found == null || !side.equals(VideoUseRules.sideOf(ctx.request().actorId(), found.get("sponsorId"),
                    found.get("providerId")))) {
                    throw notFound(videoUseId.apply(ctx));
                }
            })
            .step("Take the publication's turn", HoldLock.<ProcessContext>exclusive(ctx -> {
                Object publication = publicationOf(ctx, newPublication);
                return publication == null ? null : QbVideoUses.lockName(publication);
            }))
            .step("Load the publication's use and its card", QueryEntities.<ProcessContext>all(
                QueryEntities.query(QbVideoUses.defaultDataset(QbVideoUses.USE), ctx -> EntityQuery.builder()
                    .where(new QueryPredicate.In("publicationId", publicationOf(ctx, newPublication) == null
                        ? List.of() : List.of(String.valueOf(publicationOf(ctx, newPublication))))).limit(2).build(),
                    USES),
                QueryEntities.query(QbPublications.defaultDataset(QbPublications.CARD), ctx -> EntityQuery.builder()
                    .where(new QueryPredicate.In("publicationId", uuids(publicationOf(ctx, newPublication))))
                    .limit(1).build(), CARD)));
    }

    /** The publication the act is about: the use's found, else the new use's. */
    private static Object publicationOf(ProcessContext ctx, Function<ProcessContext, String> newPublication) {
        EntityInstance found = first(ctx, FOUND);
        return found != null ? found.get("publicationId") : newPublication.apply(ctx);
    }

    /** The provider answered: its task, and the reminders of the revision it answered, are done. */
    private static <I, O> void closeProviderTasks(ProcessDefinitionBuilder<I, O, ProcessContext> pb,
        Function<ProcessContext, String> videoUseId) {
        pb.step("Close the provider's task", CloseTasks.<ProcessContext>done(
                ctx -> confirmSource(videoUseId.apply(ctx))))
            .step("Close the reminders", CloseTasks.<ProcessContext>done(
                ctx -> VideoUseRules.reminderSource(videoUseId.apply(ctx), ctx.contains(BEFORE) ? ctx.get(BEFORE)
                    : 0)));
    }

    static String confirmSource(Object videoUseId) {
        return "qb.video-use-confirm:" + videoUseId;
    }

    static String resubmitSource(Object videoUseId) {
        return "qb.video-use-resubmit:" + videoUseId;
    }

    /** The use read under the lock (none: a new one). */
    private static EntityInstance current(ProcessContext ctx) {
        EntityInstance found = first(ctx, FOUND);
        if (found == null) {
            return null;
        }
        return list(ctx, USES).stream().filter(use -> String.valueOf(use.id()).equals(String.valueOf(found.id())))
            .findFirst().orElse(null);
    }

    /** The publication's name for the tasks' titles. */
    private static String name(ProcessContext ctx) {
        EntityInstance card = first(ctx, CARD);
        return card == null ? "" : String.valueOf((Object) card.get("name"));
    }

    private static void requireStatus(EntityInstance use, boolean allowed) {
        if (!allowed) {
            String status = use.get("status");
            throw refusal(VideoUseCodes.STATE, "The use is " + status, Map.of("status", status));
        }
    }

    private static void write(ProcessContext ctx, EntityInstance use, Map<String, Object> values) {
        long before = use == null ? 0 : AccountEditing.number(use.get("revision"));
        long revision = before + 1;
        ctx.put(BEFORE, before);
        Map<String, Object> next = new LinkedHashMap<>(values);
        next.put("revision", BigDecimal.valueOf(revision));
        next.put("editedAt", ctx.opTime());
        Object id;
        if (use == null) {
            id = ctx.changes().insert(QbVideoUses.USE, next);
        } else {
            id = use.id();
            ctx.changes().update(QbVideoUses.USE, use.id(), use.version(), next);
        }
        String status = next.containsKey("status") ? (String) next.get("status") : use.get("status");
        ctx.put(OUTPUT, new VideoUseOutput(String.valueOf(id), status, revision));
    }

    private static EntityNotFoundException notFound(Object videoUseId) {
        return new EntityNotFoundException(QbVideoUses.USE + " " + videoUseId + " not found");
    }

    static BusinessRuleViolationException refusal(String code, String message, Map<String, Object> params) {
        return new BusinessRuleViolationException(new Violation(null, code, message, params));
    }

    static EntityQuery byId(String field, Object id) {
        return EntityQuery.builder().where(new QueryPredicate.In(field, id == null ? List.of()
            : List.of(String.valueOf(id)))).limit(1).build();
    }

    /** {@code value} as a one-element list of UUIDs, or none when it is no UUID. */
    static List<Object> uuids(Object value) {
        if (value == null) {
            return List.of();
        }
        try {
            return List.of(UUID.fromString(String.valueOf(value)));
        } catch (IllegalArgumentException e) {
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    static List<EntityInstance> list(ProcessContext ctx, String key) {
        Object value = ctx.contains(key) ? ctx.get(key) : null;
        return value == null ? List.of() : (List<EntityInstance>) value;
    }

    static EntityInstance first(ProcessContext ctx, String key) {
        List<EntityInstance> found = list(ctx, key);
        return found.isEmpty() ? null : found.getFirst();
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text.strip();
    }

    private static SaveInput saveInput(ProcessContext ctx) {
        return ctx.get(INPUT, SaveInput.class);
    }

    private static UseRef ref(ProcessContext ctx) {
        return ctx.get(INPUT, UseRef.class);
    }
}
