package com.jabiz.quizbuks.sharedvideo;

import com.jabiz.entity.Violation;
import com.jabiz.event.DomainEvent;
import com.jabiz.mail.MailRecipient;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.process.ProcessDefinitionBuilder;
import com.jabiz.process.ProcessStart;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.asset.QbAssets;
import com.jabiz.quizbuks.identity.AccountEditing;
import com.jabiz.quizbuks.identity.CallerChecks;
import com.jabiz.quizbuks.operator.OperatorChecks;
import com.jabiz.quizbuks.operator.QbOperators;
import com.jabiz.quizbuks.setup.ReviewCodes;
import com.jabiz.quizbuks.topic.TopicSteps;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.EntityNotFoundException;
import com.jabiz.runtime.approval.ApprovalCase;
import com.jabiz.runtime.approval.ApprovalEntities;
import com.jabiz.runtime.approval.ApprovalOutcome;
import com.jabiz.runtime.approval.ApprovalProcesses;
import com.jabiz.runtime.approval.RequireApproval;
import com.jabiz.runtime.approval.WithdrawApproval;
import com.jabiz.runtime.mail.SendMail;
import com.jabiz.runtime.process.steps.HoldLock;
import com.jabiz.runtime.process.steps.QueryEntities;
import com.jabiz.runtime.security.SecurityEntities;
import com.jabiz.security.MfaRequirement;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sharing videos with other sponsors (F-S12, docs/quizbuks/plans/impl/F-S12.md):
 * <ul>
 *   <li>{@value #SHARE}: the provider shares a video link of their library, with its title in every language, its
 *       topics and whether it is vertical; it goes to the review of their operator. One share of an asset stands at a
 *       time (in review or approved);</li>
 *   <li>{@value #APPROVAL_RESULT}: run for the approval events, as the system: approves or sends back the share whose
 *       own request was decided, still bound to it as stored;</li>
 *   <li>{@value #WITHDRAW}: the provider withdraws it (a review in progress is withdrawn too). Only new picks stop: the
 *       publications submitted with it keep it until they end (their card froze it);</li>
 *   <li>{@value #TAKEDOWN}: the provider's operator (for its own sponsors) or headquarters takes an illegal or
 *       infringing video down, with a reason the provider reads by mail; the job {@value #END_JOB} then ends every
 *       publication using it (not the user's breach).</li>
 * </ul>
 * Locks: the provider's sharing ({@code qb.shared-videos:<provider>}) before one video's ({@code qb.shared-video:<id>})
 * before the platform's approvals'; a takedown takes the caller's membership first (an operator's member), then the
 * video's. None of them takes a publication's lock: the job ends each publication in a transaction of its own.
 */
public final class SharedVideoProcesses {

    public static final String SHARE = "QB_SHARED_VIDEO_SHARE";
    public static final String APPROVAL_RESULT = "QB_SHARED_VIDEO_APPROVAL_RESULT";
    public static final String WITHDRAW = "QB_SHARED_VIDEO_WITHDRAW";
    public static final String TAKEDOWN = "QB_SHARED_VIDEO_TAKEDOWN";
    /** The minute's job that ends the publications using a video taken down (one transaction each). */
    public static final String END_JOB = "qb.shared-video-ends";

    /**
     * @param assetId  the provider's video link in their library
     * @param vertical whether it is a vertical video (a YouTube Shorts link is one anyway)
     * @param topics   codes of the word list (at least one)
     */
    public record ShareInput(@NotBlank @Size(max = QbSharedVideos.ID_LENGTH) String assetId,
        @Size(max = QbSharedVideos.TITLE_LENGTH) String titleZh,
        @Size(max = QbSharedVideos.TITLE_LENGTH) String titleJa,
        @Size(max = QbSharedVideos.TITLE_LENGTH) String titleEn,
        @Size(max = com.jabiz.quizbuks.topic.TopicRules.HARD_MAX)
        List<@NotNull @Size(max = com.jabiz.quizbuks.topic.TopicRules.CODE_LENGTH) String> topics,
        Boolean vertical) {}

    public record VideoRef(@NotBlank @Size(max = QbSharedVideos.ID_LENGTH) String sharedVideoId) {}

    /** @param reason why, as the provider will read it */
    public record TakedownInput(@NotBlank @Size(max = QbSharedVideos.ID_LENGTH) String sharedVideoId,
        @NotBlank @Size(max = QbSharedVideos.REASON_LENGTH) String reason) {}

    /** Input of {@value #APPROVAL_RESULT}, from an approval event of any subject. */
    public record ApprovalResultInput(@NotBlank String subject, @NotBlank String entityId, @NotBlank String status,
        String requestId, String preparerId, String contentHash, String reason) {}

    /**
     * @param approval the review's outcome when the process asked for one ({@code PENDING}, {@code APPROVED})
     */
    public record VideoOutput(String sharedVideoId, String status, String approval, String approvalRequestId) {}

    static final String INPUT = "input";
    static final String OUTPUT = "output";
    static final String ID = "sharedVideoId";
    static final String NEW_ID = "newSharedVideoId";
    static final String ASSET = "asset";
    static final String STANDING = "standing";
    static final String ASSIGNMENT = "assignment";
    static final String VIDEO = "video";
    static final String VALUES = "values";
    static final String CASE = "case";
    static final String APPROVAL = "approval";
    static final String WITHDRAW_REVIEW = "withdrawReview";
    static final String REQUEST = "request";
    static final String DECISIONS = "decisions";
    static final String PROVIDER = "provider";
    static final String TAKEN_DOWN_NOW = "takenDownNow";

    private SharedVideoProcesses() {
    }

    // ---- share -----------------------------------------------------------------------------------------------------

    public static ProcessDefinition<ShareInput, VideoOutput, ProcessContext> share() {
        return ProcessDefinition.define(SHARE, 1, ShareInput.class, VideoOutput.class, ProcessContext.class, pb -> {
            pb.description("Shares a video of the sponsor's library with other sponsors; their operator reviews it "
                    + "once.")
                .permissions(QbPermissions.CONTENT_WRITE)
                .requiresVerifiedEmail()
                .contextFactory((start, input) -> {
                    ProcessContext ctx = start(start, input, null);
                    ctx.put(NEW_ID, start.ids().assign(QbSharedVideos.VIDEO, new HashMap<>()));
                    return ctx;
                })
                .outputMapper(ctx -> ctx.get(OUTPUT, VideoOutput.class));
            CallerChecks.refuseBanned(pb);
            // The word list and the limit change nothing of the share: read before the lock.
            TopicSteps.loadParams(pb);
            TopicSteps.loadWords(pb, ctx -> SharedVideoRules.topics(ctx.get(INPUT, ShareInput.class).topics()));
            pb.step("Lock the sponsor's sharing", HoldLock.<ProcessContext>exclusive(
                ctx -> QbSharedVideos.providerLockName(ctx.request().actorId())));
            pb.step("Load the asset, its standing share and the operator", QueryEntities.<ProcessContext>all(
                QueryEntities.query(QbAssets.DEFAULT_DATASET, ctx -> EntityQuery.builder()
                    .where(new QueryPredicate.And(List.of(
                        new QueryPredicate.In("assetId", List.of(assetId(ctx))),
                        new QueryPredicate.Eq("sponsorId", ctx.request().actorId())))).limit(1).build(), ASSET),
                QueryEntities.query(QbSharedVideos.DEFAULT_DATASET, ctx -> EntityQuery.builder()
                    .where(new QueryPredicate.And(List.of(
                        new QueryPredicate.In("assetId", List.of(assetId(ctx))),
                        new QueryPredicate.In("status", new ArrayList<Object>(QbSharedVideos.STANDING)))))
                    .limit(1).build(), STANDING),
                QueryEntities.query(QbOperators.defaultDataset(QbOperators.ASSIGNMENT), ctx -> EntityQuery.builder()
                    .where(new QueryPredicate.In("userId", List.of(ctx.request().actorId()))).limit(1).build(),
                    ASSIGNMENT)));
            pb.compute("Check the share", (metadata, ctx) -> checkShare(ctx));
            pb.step("Ask for a review", RequireApproval.<ProcessContext>when(ctx -> ctx.contains(CASE),
                SharedVideoApprovals.SUBJECT, ctx -> ctx.get(CASE, ApprovalCase.class), APPROVAL));
            pb.compute("Share", (metadata, ctx) -> shared(ctx));
        });
    }

    private static String assetId(ProcessContext ctx) {
        return ctx.get(INPUT, ShareInput.class).assetId().strip();
    }

    static void checkShare(ProcessContext ctx) {
        ShareInput input = ctx.get(INPUT, ShareInput.class);
        EntityInstance asset = first(ctx, ASSET);
        if (asset == null) {
            // Not the caller's, or none: not found, as the library says.
            throw new EntityNotFoundException(QbAssets.ASSET + " " + assetId(ctx) + " not found");
        }
        if (first(ctx, STANDING) != null) {
            ctx.reject(new Violation("assetId", SharedVideoCodes.ALREADY_SHARED, "The asset is shared already: "
                + "withdraw it to share it again", Map.of()));
            return;
        }
        Integer duration = asset.get("durationSec") == null ? null
            : ((Number) asset.get("durationSec")).intValue();
        Map<String, String> titles = SharedVideoRules.titles(input.titleZh(), input.titleJa(), input.titleEn());
        List<String> topics = SharedVideoRules.topics(input.topics());
        SharedVideoRules.problems(new SharedVideoRules.Share(asset.get("kind"), asset.get("url"), duration, titles,
            topics, Boolean.TRUE.equals(input.vertical())), TopicSteps.words(ctx), TopicSteps.maxPerQuiz(ctx))
            .forEach(ctx::reject);
        if (ctx.hasViolations()) {
            return;
        }
        String url = String.valueOf((Object) asset.get("url")).strip();
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("sharedVideoId", ctx.get(NEW_ID));
        values.put("assetId", String.valueOf(asset.id()));
        values.put("providerId", ctx.request().actorId());
        values.put("url", url);
        values.put("durationSec", BigDecimal.valueOf(duration));
        values.put("vertical", SharedVideoRules.vertical(Boolean.TRUE.equals(input.vertical()), url));
        values.put("title", titles);
        values.put("topics", String.join(",", topics));
        ctx.put(VALUES, values);
        Map<String, Object> hashed = new LinkedHashMap<>(values);
        hashed.put("durationSec", String.valueOf(duration));
        ApprovalCase review = ApprovalCase.of(ctx.get(NEW_ID), SharedVideoApprovals.facts(),
            SharedVideoApprovals.content(hashed)).reference(String.join(" / ", new java.util.LinkedHashSet<>(titles.values())));
        // The review is the operator's who looks after the provider (F-S1: every sponsor has one).
        EntityInstance assignment = first(ctx, ASSIGNMENT);
        if (assignment == null) {
            throw new IllegalStateException("Sponsor " + ctx.request().actorId() + " has no operator");
        }
        ctx.put(CASE, review.scope(QbOperators.SCOPE, String.valueOf((Object) assignment.get("operatorId"))));
    }

    @SuppressWarnings("unchecked")
    static void shared(ProcessContext ctx) {
        if (!ctx.contains(APPROVAL)) {
            return;
        }
        ApprovalOutcome approval = ctx.get(APPROVAL, ApprovalOutcome.class);
        Map<String, Object> values = new LinkedHashMap<>((Map<String, Object>) ctx.get(VALUES));
        values.put("sharedAt", ctx.opTime());
        values.put("revision", BigDecimal.ONE);
        values.put("editedAt", ctx.opTime());
        switch (approval.status()) {
            case PENDING -> {
                values.put("status", QbSharedVideos.PENDING);
                values.put("approvalRequestId", approval.requestId());
            }
            case APPROVED -> {
                values.put("status", QbSharedVideos.APPROVED);
                values.put("approvalRequestId", approval.requestId());
                values.put("approvedAt", ctx.opTime());
            }
            // No rule asked for a review: the rule is missing or off. Every shared video is reviewed: refuse.
            case NOT_REQUIRED -> {
                ctx.reject(new Violation(null, ReviewCodes.REVIEW_NOT_CONFIGURED, "Shared videos need a review, but "
                    + "no review rule is in effect", Map.of("rule", SharedVideoApprovals.RULE)));
                return;
            }
            default -> throw new IllegalStateException("Unknown approval outcome " + approval.status());
        }
        ctx.changes().insert(QbSharedVideos.VIDEO, values);
        ctx.put(OUTPUT, new VideoOutput(String.valueOf(values.get("sharedVideoId")), (String) values.get("status"),
            approval.status().name(), (String) values.get("approvalRequestId")));
    }

    // ---- approval result -------------------------------------------------------------------------------------------

    /**
     * Continues after a review is decided (subscribed to both approval events, run as the system; the permission is
     * granted to no role). Only the share's own request counts, still bound to the share as stored; anything else
     * changes nothing.
     */
    public static ProcessDefinition<ApprovalResultInput, VideoOutput, ProcessContext> approvalResult() {
        return ProcessDefinition.define(APPROVAL_RESULT, 1, ApprovalResultInput.class, VideoOutput.class,
            ProcessContext.class, pb -> {
                pb.description("Approves or sends back a shared video after its review is decided.")
                    .permissions(QbPermissions.SHARED_VIDEO_SYSTEM)
                    .internal()
                    .contextFactory((start, input) -> start(start, input,
                        SharedVideoApprovals.SUBJECT.equals(input.subject()) ? input.entityId() : null))
                    .outputMapper(ctx -> ctx.contains(OUTPUT) ? ctx.get(OUTPUT, VideoOutput.class)
                        : new VideoOutput(id(ctx), null, null, null));
                lockAndLoad(pb);
                pb.step("Load the request", QueryEntities.<ProcessContext>of(ApprovalEntities.REQUEST_DATASET,
                    ctx -> EntityQuery.builder().where(new QueryPredicate.In("requestId",
                        ownRequest(ctx) ? AccountEditing.uuids(result(ctx).requestId()) : new ArrayList<>()))
                        .limit(1).build(), REQUEST));
                pb.step("Load the decisions", QueryEntities.<ProcessContext>of(ApprovalEntities.DECISION_DATASET,
                    ctx -> EntityQuery.builder().where(new QueryPredicate.In("requestId",
                        storedRequest(ctx) ? AccountEditing.uuids(result(ctx).requestId()) : new ArrayList<>()))
                        .limit(Integer.MAX_VALUE).build(), DECISIONS));
                pb.step("Check the approval", RequireApproval.<ProcessContext>when(
                    ctx -> boundRequest(ctx) && ApprovalEntities.APPROVED.equals(result(ctx).status()),
                    SharedVideoApprovals.SUBJECT, ctx -> storedCase(ctx).preparedBy(result(ctx).preparerId()),
                    APPROVAL));
                pb.compute("Approve or send back", (metadata, ctx) -> approvalResult(ctx));
            });
    }

    private static ApprovalResultInput result(ProcessContext ctx) {
        return ctx.get(INPUT, ApprovalResultInput.class);
    }

    /** Whether the event is about the request the video, still in review, waits for. */
    private static boolean ownRequest(ProcessContext ctx) {
        EntityInstance video = first(ctx, VIDEO);
        return video != null && QbSharedVideos.PENDING.equals(video.get("status"))
            && result(ctx).requestId() != null && result(ctx).requestId().equals(video.get("approvalRequestId"));
    }

    private static boolean storedRequest(ProcessContext ctx) {
        EntityInstance request = first(ctx, REQUEST);
        return ownRequest(ctx) && request != null && SharedVideoApprovals.SUBJECT.equals(request.get("subject"))
            && String.valueOf(first(ctx, VIDEO).id()).equals(request.get("entityId"));
    }

    private static boolean boundRequest(ProcessContext ctx) {
        return storedRequest(ctx) && SharedVideoApprovals.hash(storedCase(ctx).content())
            .equals(String.valueOf((Object) first(ctx, REQUEST).get("contentHash")).strip());
    }

    private static ApprovalCase storedCase(ProcessContext ctx) {
        EntityInstance video = first(ctx, VIDEO);
        return ApprovalCase.of(video.id(), SharedVideoApprovals.facts(), SharedVideoApprovals.content(video));
    }

    static void approvalResult(ProcessContext ctx) {
        EntityInstance video = first(ctx, VIDEO);
        if (!storedRequest(ctx)) {
            unchanged(ctx, video);
            return;
        }
        ApprovalResultInput input = result(ctx);
        Map<String, Object> values = new LinkedHashMap<>();
        if (ApprovalEntities.APPROVED.equals(input.status()) && boundRequest(ctx)) {
            ApprovalOutcome approval = ctx.get(APPROVAL, ApprovalOutcome.class);
            if (approval == null || approval.status() != ApprovalOutcome.Status.APPROVED
                || !input.requestId().equals(approval.requestId())) {
                unchanged(ctx, video);
                return;
            }
            values.put("status", QbSharedVideos.APPROVED);
            values.put("approvedAt", ctx.opTime());
        } else if (ApprovalEntities.REJECTED.equals(input.status())
            && ApprovalEntities.REJECTED.equals(first(ctx, REQUEST).get("status"))) {
            values.put("status", QbSharedVideos.REJECTED);
            values.put("rejectReason", rejectReason(ctx));
        } else {
            unchanged(ctx, video);
            return;
        }
        write(ctx, video, values);
    }

    /** The reviewer's reason for the rejection, from their decision; none when they gave none. */
    private static String rejectReason(ProcessContext ctx) {
        return list(ctx, DECISIONS).stream()
            .filter(decision -> ApprovalProcesses.REJECT.equals(decision.get("decision")))
            .map(decision -> (String) decision.get("reason")).filter(reason -> reason != null && !reason.isBlank())
            .findFirst().orElse(null);
    }

    /** The input of {@value #APPROVAL_RESULT} from an approval event. */
    static ApprovalResultInput resultInput(DomainEvent event) {
        Map<String, Object> payload = event.payload();
        return new ApprovalResultInput(text(payload, "subject"), text(payload, "entityId"), text(payload, "status"),
            text(payload, "requestId"), text(payload, "preparerId"), text(payload, "contentHash"),
            text(payload, "reason"));
    }

    // ---- withdraw --------------------------------------------------------------------------------------------------

    public static ProcessDefinition<VideoRef, VideoOutput, ProcessContext> withdraw() {
        return ProcessDefinition.define(WITHDRAW, 1, VideoRef.class, VideoOutput.class, ProcessContext.class, pb -> {
            pb.description("Withdraws a shared video: no new publication picks it; those submitted with it keep it "
                    + "until they end.")
                .permissions(QbPermissions.CONTENT_WRITE)
                .requiresVerifiedEmail()
                .actsOn(QbSharedVideos.VIDEO, "sharedVideoId", a -> a.whenField("status", QbSharedVideos.PENDING,
                    QbSharedVideos.APPROVED))
                .contextFactory((start, input) -> start(start, input, input.sharedVideoId()))
                .outputMapper(ctx -> ctx.get(OUTPUT, VideoOutput.class));
            pb.step("Lock the sponsor's sharing", HoldLock.<ProcessContext>exclusive(
                ctx -> QbSharedVideos.providerLockName(ctx.request().actorId())));
            lockAndLoad(pb);
            pb.compute("Withdraw", (metadata, ctx) -> {
                EntityInstance video = first(ctx, VIDEO);
                if (video == null || !ctx.request().actorId().equals(video.get("providerId"))) {
                    // Another sponsor's, or none: not found.
                    throw new EntityNotFoundException(QbSharedVideos.VIDEO + " " + id(ctx) + " not found");
                }
                if (!in(ctx, video, SharedVideoRules.withdrawable(video.get("status")))) {
                    return;
                }
                ctx.put(WITHDRAW_REVIEW, QbSharedVideos.PENDING.equals(video.get("status")));
                Map<String, Object> values = new LinkedHashMap<>();
                values.put("status", QbSharedVideos.WITHDRAWN);
                values.put("withdrawnAt", ctx.opTime());
                write(ctx, video, values);
            });
            pb.step("Withdraw the review", WithdrawApproval.<ProcessContext>when(
                ctx -> Boolean.TRUE.equals(ctx.get(WITHDRAW_REVIEW)), SharedVideoApprovals.SUBJECT,
                ctx -> first(ctx, VIDEO).id()));
        });
    }

    // ---- takedown --------------------------------------------------------------------------------------------------

    /**
     * {@value #TAKEDOWN}: the provider's operator (an operator's member, for a sponsor it looks after: another's is
     * not found) or headquarters takes down an approved video, also one withdrawn that publications still use. The
     * provider is told by mail, with the reason; the publications using it end by the job {@value #END_JOB}, each
     * user told (not their breach). The fees of those publications follow F-M8 (not charged here).
     */
    public static ProcessDefinition<TakedownInput, VideoOutput, ProcessContext> takedown() {
        return ProcessDefinition.define(TAKEDOWN, 1, TakedownInput.class, VideoOutput.class, ProcessContext.class,
            pb -> {
                pb.description("Takes down an illegal or infringing shared video: the publications using it end.")
                    .permissions(QbPermissions.ADMIN_SHARED_VIDEO_WRITE)
                    .requiresMfa(MfaRequirement.ALWAYS)
                    .requiresVerifiedEmail()
                    .actsOn(QbSharedVideos.VIDEO, "sharedVideoId", a -> a.whenField("status",
                        QbSharedVideos.APPROVED, QbSharedVideos.WITHDRAWN))
                    .contextFactory((start, input) -> start(start, input, input.sharedVideoId()))
                    .outputMapper(ctx -> ctx.get(OUTPUT, VideoOutput.class));
                // An operator's member is checked within the act (Q48e-1); headquarters passes.
                OperatorChecks.memberWhenScoped(pb);
                lockAndLoad(pb);
                // An operator only for a sponsor it looks after (another's, or none: 404).
                OperatorChecks.ownSponsor(pb, ctx -> {
                    EntityInstance video = first(ctx, VIDEO);
                    return video == null ? null : video.get("providerId");
                });
                pb.compute("Take down", (metadata, ctx) -> {
                    EntityInstance video = first(ctx, VIDEO);
                    if (video == null) {
                        throw new EntityNotFoundException(QbSharedVideos.VIDEO + " " + id(ctx) + " not found");
                    }
                    if (!in(ctx, video, SharedVideoRules.takeable(video.get("status")))) {
                        return;
                    }
                    Map<String, Object> values = new LinkedHashMap<>();
                    values.put("status", QbSharedVideos.TAKEN_DOWN);
                    values.put("takenDownAt", ctx.opTime());
                    values.put("takedownReason", ctx.get(INPUT, TakedownInput.class).reason().strip());
                    values.put("takedownOperatorId", ctx.contains(OperatorChecks.OPERATOR_ID)
                        ? OperatorChecks.operatorId(ctx) : null);
                    write(ctx, video, values);
                    ctx.put(TAKEN_DOWN_NOW, true);
                });
                pb.step("Load the provider", QueryEntities.<ProcessContext>of(SecurityEntities.USER_DATASET,
                    ctx -> EntityQuery.builder().where(new QueryPredicate.In("userId", AccountEditing.uuids(
                        Boolean.TRUE.equals(ctx.get(TAKEN_DOWN_NOW)) ? first(ctx, VIDEO).get("providerId") : null)))
                        .limit(1).build(), PROVIDER));
                pb.step("Tell the provider", SendMail.<ProcessContext>when(
                    ctx -> !ctx.hasViolations() && Boolean.TRUE.equals(ctx.get(TAKEN_DOWN_NOW)) && addressed(ctx),
                    SharedVideoMails.TAKEN_DOWN, ctx -> MailRecipient.user(String.valueOf((Object) first(ctx, VIDEO).get("providerId"))),
                    Map.of("title", SharedVideoProcesses::titleForMail,
                        "reason", ctx -> ctx.get(INPUT, TakedownInput.class).reason().strip())));
            });
    }

    /** Whether the provider has an address to mail. */
    private static boolean addressed(ProcessContext ctx) {
        EntityInstance user = first(ctx, PROVIDER);
        Object email = user == null ? null : user.get("email");
        return email != null && !email.toString().isBlank();
    }

    /** The video's title for the provider's mail: in their mail's language is the platform's; all three here. */
    @SuppressWarnings("unchecked")
    static String titleForMail(ProcessContext ctx) {
        Object title = first(ctx, VIDEO).get("title");
        if (title instanceof Map<?, ?> map) {
            Map<String, Object> names = (Map<String, Object>) map;
            List<String> parts = new ArrayList<>();
            for (String language : com.jabiz.quizbuks.content.QbContent.LANGUAGES) {
                Object text = names.get(language);
                if (text != null && !parts.contains(text.toString())) {
                    parts.add(text.toString());
                }
            }
            return String.join(" / ", parts);
        }
        return title == null ? "" : title.toString();
    }

    // ---- shared ----------------------------------------------------------------------------------------------------

    /** The video's lock, then the video (none for none). */
    private static <I, O> void lockAndLoad(ProcessDefinitionBuilder<I, O, ProcessContext> pb) {
        pb.step("Lock the shared video", HoldLock.<ProcessContext>exclusive(ctx -> QbSharedVideos.lockName(id(ctx))))
            .step("Load the shared video", QueryEntities.<ProcessContext>of(QbSharedVideos.DEFAULT_DATASET,
                ctx -> byId(id(ctx)), VIDEO));
    }

    static EntityQuery byId(Object id) {
        List<Object> ids = new ArrayList<>();
        if (id != null && !id.toString().isBlank()) {
            ids.add(id.toString().strip());
        }
        return EntityQuery.builder().where(new QueryPredicate.In("sharedVideoId", ids)).limit(1).build();
    }

    private static ProcessContext start(ProcessStart start, Object input, String id) {
        ProcessContext ctx = new ProcessContext(start);
        ctx.put(INPUT, input);
        if (id != null) {
            ctx.put(ID, id.strip());
        }
        return ctx;
    }

    static String id(ProcessContext ctx) {
        return ctx.contains(ID) ? ctx.get(ID, String.class) : null;
    }

    /** Whether the state allows the act; otherwise 422 {@code QB_SHARED_VIDEO_STATE}. */
    private static boolean in(ProcessContext ctx, EntityInstance video, boolean allowed) {
        if (allowed) {
            return true;
        }
        ctx.reject(new Violation("sharedVideoId", SharedVideoCodes.STATE, "The shared video is "
            + video.get("status"), Map.of("status", String.valueOf((Object) video.get("status")))));
        return false;
    }

    /** Writes the video's next revision and puts the output. */
    private static void write(ProcessContext ctx, EntityInstance video, Map<String, Object> values) {
        Map<String, Object> next = new LinkedHashMap<>(values);
        next.put("revision", BigDecimal.valueOf(AccountEditing.number(video.get("revision")) + 1));
        next.put("editedAt", ctx.opTime());
        ctx.changes().update(QbSharedVideos.VIDEO, video.id(), video.version(), next);
        ctx.put(OUTPUT, new VideoOutput(String.valueOf(video.id()), (String) next.get("status"), null,
            (String) video.get("approvalRequestId")));
    }

    private static void unchanged(ProcessContext ctx, EntityInstance video) {
        if (video != null) {
            ctx.put(OUTPUT, new VideoOutput(String.valueOf(video.id()), video.get("status"), null,
                video.get("approvalRequestId")));
        }
    }

    private static String text(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        return value == null ? null : String.valueOf(value);
    }

    @SuppressWarnings("unchecked")
    static List<EntityInstance> list(ProcessContext ctx, String key) {
        List<EntityInstance> found = ctx.contains(key) ? (List<EntityInstance>) ctx.get(key) : null;
        return found == null ? List.of() : found;
    }

    static EntityInstance first(ProcessContext ctx, String key) {
        List<EntityInstance> found = list(ctx, key);
        return found.isEmpty() ? null : found.getFirst();
    }
}
