package com.jabiz.quizbuks.messaging;

import com.jabiz.entity.Violation;
import com.jabiz.i18n.MessageCatalog;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.process.ProcessDefinitionBuilder;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.country.Regions;
import com.jabiz.quizbuks.identity.BanRules;
import com.jabiz.quizbuks.identity.QbAccounts;
import com.jabiz.quizbuks.publishing.QbPublications;
import com.jabiz.quizbuks.publishing.SponsorChecks;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.runtime.BusinessRuleViolationException;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.EntityNotFoundException;
import com.jabiz.runtime.process.steps.HoldLock;
import com.jabiz.runtime.process.steps.QueryEntities;
import com.jabiz.runtime.process.steps.RunTemplate;
import com.jabiz.runtime.security.SecurityEntities;
import com.jabiz.security.MfaRequirement;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Announcements of the platform and sponsors' notices (docs/quizbuks/plans/Q7-messages-legal.md, Q7c, D-Q7-8; P-04,
 * M-60). A sponsor's processes act on their own notices only and take their account's lock first (the lock a ban
 * holds while it withdraws them, so a notice is never sent past a ban); the administrators' act on every one. Each
 * writer takes the broadcast's own lock ({@link QbInbox#broadcastLock}) before reading it again.
 * <ul>
 *   <li>{@value #SAVE} / {@value #ADMIN_SAVE}: write a draft; the output lists every problem sending it would meet
 *       ({@link BroadcastRules}), so the pages show the server's rules, never their own;</li>
 *   <li>{@value #SEND} / {@value #ADMIN_SEND}: send a draft now, or schedule it for its time (the minute's job
 *       {@value QbInbox#DISPATCH_JOB} sends it then);</li>
 *   <li>{@value #CANCEL} / {@value #ADMIN_CANCEL}: a scheduled one back to its draft, a draft discarded;</li>
 *   <li>{@value #WITHDRAW} / {@value #ADMIN_WITHDRAW}: take a sent one out of every inbox (the administrators', of any
 *       announcement or notice, with a reason the sponsor reads and a second factor every time);</li>
 *   <li>{@value #DISPATCH}: the job's item, {@code internal()}: a due one is sent, or cancelled when its sponsor was
 *       banned or its publication is no longer on;</li>
 *   <li>{@value #BAN_WITHDRAW}: the sub-process of {@code QB_USER_BAN}: every scheduled and sent notice of the banned
 *       sponsor is withdrawn in the ban's transaction.</li>
 * </ul>
 * Sending writes the announcement once into {@code QbBroadcastFeed} with the number of people it matched then; the
 * inbox matches it to each reader when they read it (never a row per reader).
 */
public final class BroadcastProcesses {

    public static final String SAVE = "QB_BROADCAST_SAVE";
    public static final String SEND = "QB_BROADCAST_SEND";
    public static final String CANCEL = "QB_BROADCAST_CANCEL";
    public static final String WITHDRAW = "QB_BROADCAST_WITHDRAW";
    public static final String ADMIN_SAVE = "QB_ADMIN_BROADCAST_SAVE";
    public static final String ADMIN_SEND = "QB_ADMIN_BROADCAST_SEND";
    public static final String ADMIN_CANCEL = "QB_ADMIN_BROADCAST_CANCEL";
    public static final String ADMIN_WITHDRAW = "QB_ADMIN_BROADCAST_WITHDRAW";
    public static final String DISPATCH = "QB_BROADCAST_DISPATCH";
    public static final String BAN_WITHDRAW = "QB_BROADCAST_BAN_WITHDRAW";

    /** At most this many scheduled announcements one run of the job sends. */
    public static final int DISPATCH_BATCH = 500;

    /**
     * A sponsor's notice: a title and plain text (in the language they wrote it in), one of their publications, some
     * of its regions, and when it goes out (now when none).
     *
     * @param baseRevision the draft's revision as the page read it; required to change a draft
     */
    public record SponsorSaveInput(UUID broadcastId, Long baseRevision, Map<String, String> title,
        Map<String, String> body, UUID publicationId, List<@NotNull String> regions, Instant scheduledAt) {}

    /**
     * An announcement of the platform.
     *
     * @param userIds   the named users ({@code USERS}); given: the complete list
     * @param expiresAt none: {@code qb.broadcast.expires-days} after it is sent
     */
    public record AdminSaveInput(UUID broadcastId, Long baseRevision, @NotBlank String audience,
        @NotBlank String type, Map<String, String> title, Map<String, String> body, @NotBlank String targetMode,
        List<@NotNull String> regions, @Size(max = BroadcastRules.MAX_TARGETS + 1) List<@NotNull String> userIds,
        Instant scheduledAt, Instant expiresAt) {}

    /** A draft or scheduled one, at the revision the page read. */
    public record BroadcastRef(@NotNull UUID broadcastId, @NotNull Long baseRevision) {}

    /** The administrators' withdrawal: with the reason the sponsor reads. */
    public record WithdrawInput(@NotNull UUID broadcastId, @Size(max = QbInbox.REASON_LENGTH) String reason) {}

    /** The job's item. */
    public record DispatchInput(@NotNull UUID broadcastId) {}

    /** The ban's sub-process: the sponsor banned. */
    public record SponsorRef(@NotBlank @Size(max = 128) String sponsorId) {}

    /** One problem sending would meet, the message in the caller's language. */
    public record Problem(String field, String code, String message, Map<String, Object> params) {}

    /**
     * @param sendsAt   when it goes out if sent now (its scheduled time, or now)
     * @param expiresAt when it would expire
     * @param problems  empty when it can be sent
     */
    public record SaveOutput(UUID broadcastId, String status, long revision, Instant sendsAt, Instant expiresAt,
        int targetCount, List<Problem> problems) {
        public SaveOutput {
            problems = List.copyOf(problems);
        }
    }

    public record BroadcastOutput(UUID broadcastId, String status, long revision, Instant sentAt,
        Long audienceCount) {}

    /** @param withdrawn the notices withdrawn */
    public record BanOutput(int withdrawn) {}

    static final String INPUT = "input";
    static final String ID = "broadcastId";
    static final String BEFORE = "before";
    static final String OWN = "own";
    static final String FOUND = "found";
    static final String CARD = "card";
    static final String PARAMS = "params";
    static final String TARGETS = "targets";
    static final String USERS = "users";
    static final String FEED = "feed";
    static final String SCHEDULE_ROW = "schedule";
    static final String BAN = "ban";
    static final String SENDING = "sending";
    static final String AUDIENCE = "audience";
    static final String OUTPUT = "output";

    private static final List<String> LIMIT_PARAMS = List.of(QbParams.BROADCAST_SPONSOR_MAX,
        QbParams.BROADCAST_SPONSOR_INTERVAL, QbParams.BROADCAST_EXPIRES_DAYS);

    private BroadcastProcesses() {}

    // ---- saving ----------------------------------------------------------------------------------------------------

    public static ProcessDefinition<SponsorSaveInput, SaveOutput, ProcessContext> sponsorSave(
        MessageCatalog messages) {
        return ProcessDefinition.define(SAVE, 1, SponsorSaveInput.class, SaveOutput.class, ProcessContext.class,
            pb -> {
                pb.description("Saves the draft of a notice to the takers of one of the sponsor's publications.")
                    .permissions(QbPermissions.BROADCAST_WRITE)
                    .requiresVerifiedEmail()
                    .contextFactory((start, input) -> start(start, input, input.broadcastId()))
                    .outputMapper(ctx -> ctx.get(OUTPUT, SaveOutput.class));
                sponsorReads(pb, ctx -> {
                    UUID given = ctx.get(INPUT, SponsorSaveInput.class).publicationId();
                    return given;
                });
                pb.compute("Save the notice", (metadata, ctx) -> sponsorSave(ctx, messages));
            });
    }

    /**
     * A sponsor's reads, under their account's lock and the broadcast's: their onboarding and ban (refused unless
     * approved and not banned), every notice of theirs with the parameters (one statement), then the card of the
     * publication {@code publication} names.
     */
    private static <I, O> void sponsorReads(ProcessDefinitionBuilder<I, O, ProcessContext> pb,
        java.util.function.Function<ProcessContext, Object> publication) {
        pb.step("Lock the sponsor's account", HoldLock.<ProcessContext>exclusive(
                ctx -> QbAccounts.lockName(ctx.request().actorId())))
            .steps(SponsorChecks::load)
            .compute("Refuse a sponsor not approved or banned", (metadata, ctx) -> {
                List<Violation> problems = SponsorChecks.problems(ctx);
                if (!problems.isEmpty()) {
                    throw new BusinessRuleViolationException(problems);
                }
            })
            .step("Lock the notice", HoldLock.<ProcessContext>exclusive(ctx -> QbInbox.broadcastLock(ctx.get(ID))))
            .step("Load the sponsor's notices", QueryEntities.<ProcessContext>all(
                QueryEntities.query(QbInbox.SPONSOR_BROADCASTS, ctx -> EntityQuery.builder()
                    .limit(Integer.MAX_VALUE).build(), OWN),
                ParamReads.query(PARAMS, LIMIT_PARAMS)))
            .compute("Find the notice", (metadata, ctx) -> find(ctx))
            .step("Load the publication's card", QueryEntities.<ProcessContext>of(
                QbPublications.defaultDataset(QbPublications.CARD), ctx -> MessageProcesses.byId("publicationId",
                    publication.apply(ctx)), CARD));
    }

    /** The broadcast the input names among the sponsor's own (404 when it is not one). */
    private static void find(ProcessContext ctx) {
        Object id = ctx.contains(ID) ? ctx.get(ID) : null;
        if (id == null) {
            return;
        }
        EntityInstance found = list(ctx, OWN).stream().filter(row -> String.valueOf(row.id())
            .equals(String.valueOf(id))).findFirst().orElseThrow(() -> notFound(id));
        ctx.put(FOUND, List.of(found));
    }

    static void sponsorSave(ProcessContext ctx, MessageCatalog messages) {
        SponsorSaveInput input = ctx.get(INPUT, SponsorSaveInput.class);
        EntityInstance found = first(ctx, FOUND);
        requireDraft(found, input.baseRevision());
        if (input.publicationId() != null && publication(ctx, ctx.request().actorId()) == null) {
            // Only one's own publications are named: another's is not told apart from none.
            throw new BusinessRuleViolationException(List.of(new Violation("publicationId",
                BroadcastCodes.PUBLICATION_NOT_ON, "Not one of your scheduled or live publications",
                Map.of("status", ""))));
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("audience", QbInbox.AUDIENCE_APP);
        values.put("type", QbInbox.BROADCAST_PROMO);
        values.put("title", BroadcastRules.clean(input.title()));
        values.put("body", BroadcastRules.clean(input.body()));
        values.put("targetMode", QbInbox.TARGET_REGIONS);
        values.put("regions", regions(input.regions()));
        values.put("targetCount", null);
        values.put("publicationId", input.publicationId());
        values.put("scheduledAt", input.scheduledAt());
        values.put("expiresAt", null);
        write(ctx, found, QbInbox.SENDER_SPONSOR, values);
        ctx.put(OUTPUT, saveOutput(ctx, messages, QbInbox.SENDER_SPONSOR, values, 0));
    }

    public static ProcessDefinition<AdminSaveInput, SaveOutput, ProcessContext> adminSave(MessageCatalog messages) {
        return ProcessDefinition.define(ADMIN_SAVE, 1, AdminSaveInput.class, SaveOutput.class, ProcessContext.class,
            pb -> pb
                .description("Saves the draft of an announcement of the platform.")
                .permissions(QbPermissions.ADMIN_CONTENT_WRITE)
                .requiresVerifiedEmail()
                .contextFactory((start, input) -> start(start, input, input.broadcastId()))
                .outputMapper(ctx -> ctx.get(OUTPUT, SaveOutput.class))
                .step("Lock the announcement", HoldLock.<ProcessContext>exclusive(
                    ctx -> QbInbox.broadcastLock(ctx.get(ID))))
                // One statement: the draft, its named users and the parameters.
                .step("Load the announcement", QueryEntities.<ProcessContext>all(
                    QueryEntities.query(QbInbox.defaultDataset(QbInbox.BROADCAST), ctx -> MessageProcesses.byId(
                        ID, ctx.get(ID)), FOUND),
                    QueryEntities.query(QbInbox.defaultDataset(QbInbox.TARGET), ctx -> EntityQuery.builder()
                        .where(new QueryPredicate.In(ID, ids(ctx.get(ID)))).limit(Integer.MAX_VALUE).build(),
                        TARGETS),
                    ParamReads.query(PARAMS, LIMIT_PARAMS)))
                // The named users must be the platform's (deleted ones are not).
                .step("Load the named users", QueryEntities.<ProcessContext>of(SecurityEntities.USER_DATASET,
                    ctx -> EntityQuery.builder().where(new QueryPredicate.In("userId", userUuids(
                        ctx.get(INPUT, AdminSaveInput.class).userIds()))).limit(BroadcastRules.MAX_TARGETS + 1)
                        .build(), USERS))
                .compute("Save the announcement", (metadata, ctx) -> adminSave(ctx, messages)));
    }

    static void adminSave(ProcessContext ctx, MessageCatalog messages) {
        AdminSaveInput input = ctx.get(INPUT, AdminSaveInput.class);
        if (input.broadcastId() != null && first(ctx, FOUND) == null) {
            throw notFound(input.broadcastId());
        }
        EntityInstance found = first(ctx, FOUND);
        if (found != null && !QbInbox.SENDER_PLATFORM.equals(found.get("senderKind"))) {
            // A sponsor's notice is the sponsor's to write; the administrators only withdraw it.
            throw notFound(input.broadcastId());
        }
        requireDraft(found, input.baseRevision());
        List<Violation> problems = new ArrayList<>();
        code(problems, "audience", input.audience(), QbInbox.AUDIENCES);
        code(problems, "type", input.type(), QbInbox.BROADCAST_TYPES);
        code(problems, "targetMode", input.targetMode(), QbInbox.TARGET_MODES);
        boolean users = QbInbox.TARGET_USERS.equals(input.targetMode());
        List<String> userIds = users && input.userIds() != null ? canonicalUsers(input.userIds()) : List.of();
        if (userIds.size() > BroadcastRules.MAX_TARGETS) {
            problems.add(BroadcastRules.tooManyUsers());
        } else if (users && input.userIds() != null) {
            Set<String> known = new LinkedHashSet<>();
            list(ctx, USERS).forEach(user -> known.add(String.valueOf(user.id())));
            List<String> unknown = userIds.stream().filter(id -> !known.contains(id)).toList();
            if (!unknown.isEmpty()) {
                problems.add(new Violation("userIds", BroadcastCodes.UNKNOWN_USERS, "Not users: " + unknown,
                    Map.of("users", String.join(", ", unknown.stream().limit(10).toList()))));
            }
        }
        if (!problems.isEmpty()) {
            throw new BusinessRuleViolationException(problems);
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("audience", input.audience());
        values.put("type", input.type());
        values.put("title", BroadcastRules.clean(input.title()));
        values.put("body", BroadcastRules.clean(input.body()));
        values.put("targetMode", input.targetMode());
        values.put("regions", QbInbox.TARGET_REGIONS.equals(input.targetMode()) ? regions(input.regions()) : null);
        values.put("publicationId", null);
        values.put("scheduledAt", input.scheduledAt());
        values.put("expiresAt", input.expiresAt());
        int targets = users ? (input.userIds() == null ? list(ctx, TARGETS).size() : userIds.size()) : 0;
        values.put("targetCount", users ? BigDecimal.valueOf(targets) : null);
        Object id = write(ctx, found, QbInbox.SENDER_PLATFORM, values);
        if (input.userIds() != null || !users) {
            writeTargets(ctx, id, list(ctx, TARGETS), users ? userIds : List.of());
        }
        ctx.put(OUTPUT, saveOutput(ctx, messages, QbInbox.SENDER_PLATFORM, values, targets));
    }

    /** Replaces the named users: deletes those no longer named, inserts the new ones. */
    private static void writeTargets(ProcessContext ctx, Object broadcastId, List<EntityInstance> existing,
        List<String> userIds) {
        Set<String> wanted = new LinkedHashSet<>(userIds);
        for (EntityInstance target : existing) {
            if (!wanted.remove(String.valueOf((Object) target.get("userId")))) {
                ctx.changes().delete(QbInbox.TARGET, target.id(), target.version());
            }
        }
        for (String userId : wanted) {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put(ID, broadcastId);
            values.put("userId", userId);
            ctx.changes().insert(QbInbox.TARGET, values);
        }
    }

    /** Inserts a new draft or updates the one found (only what changed); its id. */
    private static Object write(ProcessContext ctx, EntityInstance found, String senderKind,
        Map<String, Object> values) {
        if (found == null) {
            Map<String, Object> row = new LinkedHashMap<>(values);
            row.put("senderKind", senderKind);
            row.put("senderId", ctx.request().actorId());
            row.put("status", QbInbox.DRAFT);
            row.put("revision", BigDecimal.ONE);
            row.put("editedAt", ctx.opTime());
            Object id = ctx.changes().insert(QbInbox.BROADCAST, row);
            ctx.put(ID, id);
            ctx.put("revision", 1L);
            return id;
        }
        Map<String, Object> changed = new LinkedHashMap<>();
        values.forEach((field, value) -> {
            if (!same(found.get(field), value)) {
                changed.put(field, value);
            }
        });
        long revision = number(found.get("revision"));
        if (!changed.isEmpty()) {
            revision++;
            changed.put("revision", BigDecimal.valueOf(revision));
            changed.put("editedAt", ctx.opTime());
            ctx.changes().update(QbInbox.BROADCAST, found.id(), found.version(), changed);
        }
        ctx.put("revision", revision);
        return found.id();
    }

    private static SaveOutput saveOutput(ProcessContext ctx, MessageCatalog messages, String senderKind,
        Map<String, Object> values, int targetCount) {
        Object id = ctx.get(ID);
        BroadcastRules.Draft draft = draftOf(senderKind, values, targetCount);
        BroadcastRules.Limits limits = limits(ctx);
        BroadcastRules.Publication publication = publication(ctx, ctx.request().actorId());
        List<Violation> problems = BroadcastRules.sendProblems(draft, ctx.opTime(), publication,
            others(ctx, id), limits);
        Instant sendsAt = BroadcastRules.sendsAt(draft, ctx.opTime());
        return new SaveOutput(uuid(id), QbInbox.DRAFT, (Long) ctx.get("revision"), sendsAt,
            BroadcastRules.expiresAt(draft, sendsAt, publication, limits), targetCount, problems.stream()
                .map(v -> new Problem(v.field(), v.ruleCode(), messages.message(v, ctx.request().locale()),
                    v.params())).toList());
    }

    // ---- sending ---------------------------------------------------------------------------------------------------

    public static ProcessDefinition<BroadcastRef, BroadcastOutput, ProcessContext> sponsorSend() {
        return ProcessDefinition.define(SEND, 1, BroadcastRef.class, BroadcastOutput.class, ProcessContext.class,
            pb -> {
                pb.description("Sends a notice now, or schedules it for its time.")
                    .permissions(QbPermissions.BROADCAST_WRITE)
                    .requiresVerifiedEmail()
                    .contextFactory((start, input) -> start(start, input, input.broadcastId()))
                    .outputMapper(ctx -> ctx.get(OUTPUT, BroadcastOutput.class));
                sponsorReads(pb, ctx -> {
                    EntityInstance found = first(ctx, FOUND);
                    return found == null ? null : found.get("publicationId");
                });
                pb.compute("Check the notice", (metadata, ctx) -> send(ctx));
                sending(pb);
            });
    }

    public static ProcessDefinition<BroadcastRef, BroadcastOutput, ProcessContext> adminSend() {
        return ProcessDefinition.define(ADMIN_SEND, 1, BroadcastRef.class, BroadcastOutput.class,
            ProcessContext.class, pb -> {
                pb.description("Sends an announcement of the platform now, or schedules it for its time.")
                    .permissions(QbPermissions.ADMIN_CONTENT_WRITE)
                    .requiresVerifiedEmail()
                    // To every user at once: a second factor, as other administration.
                    .requiresMfa(MfaRequirement.ADMINISTRATION)
                    .contextFactory((start, input) -> start(start, input, input.broadcastId()))
                    .outputMapper(ctx -> ctx.get(OUTPUT, BroadcastOutput.class))
                    .step("Lock the announcement", HoldLock.<ProcessContext>exclusive(
                        ctx -> QbInbox.broadcastLock(ctx.get(ID))))
                    .step("Load the announcement", QueryEntities.<ProcessContext>all(
                        QueryEntities.query(QbInbox.defaultDataset(QbInbox.BROADCAST), ctx -> MessageProcesses.byId(
                            ID, ctx.get(ID)), FOUND),
                        ParamReads.query(PARAMS, LIMIT_PARAMS)))
                    .compute("Check the announcement", (metadata, ctx) -> {
                        EntityInstance found = first(ctx, FOUND);
                        if (found == null || !QbInbox.SENDER_PLATFORM.equals(found.get("senderKind"))) {
                            throw notFound(ctx.get(ID));
                        }
                        send(ctx);
                    });
                sending(pb);
            });
    }

    /**
     * A draft at the page's revision, without problems, goes out now or is scheduled: scheduled, its due time is
     * written here; now, {@link #SENDING} tells the next steps to count its audience and send it.
     */
    static void send(ProcessContext ctx) {
        BroadcastRef input = ctx.get(INPUT, BroadcastRef.class);
        EntityInstance found = first(ctx, FOUND);
        requireDraft(found, input.baseRevision());
        BroadcastRules.Draft draft = draftOf(found);
        BroadcastRules.Publication publication = publication(ctx, found.get("senderId"));
        List<Violation> problems = BroadcastRules.sendProblems(draft, ctx.opTime(), publication,
            others(ctx, found.id()), limits(ctx));
        if (!problems.isEmpty()) {
            throw new BusinessRuleViolationException(problems);
        }
        if (draft.scheduledAt() != null) {
            long revision = number(found.get("revision")) + 1;
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("status", QbInbox.SCHEDULED);
            values.put("revision", BigDecimal.valueOf(revision));
            values.put("editedAt", ctx.opTime());
            ctx.changes().update(QbInbox.BROADCAST, found.id(), found.version(), values);
            Map<String, Object> due = new LinkedHashMap<>();
            due.put(ID, found.id());
            due.put("dueAt", draft.scheduledAt());
            ctx.changes().insert(QbInbox.SCHEDULE, due);
            ctx.put(OUTPUT, new BroadcastOutput(uuid(found.id()), QbInbox.SCHEDULED, revision, null, null));
            return;
        }
        ctx.put(SENDING, sending(found, ctx.opTime(), publication, limits(ctx)));
    }

    /** What sending writes: the broadcast now, with when it expires. */
    record Sending(EntityInstance broadcast, Instant sentAt, Instant expiresAt) {}

    private static Sending sending(EntityInstance broadcast, Instant at, BroadcastRules.Publication publication,
        BroadcastRules.Limits limits) {
        BroadcastRules.Draft draft = draftOf(broadcast);
        return new Sending(broadcast, at, BroadcastRules.expiresAt(draft, at, publication, limits));
    }

    /**
     * The steps of sending now, when {@link #SENDING} says so: the audience counted
     * ({@value QbInbox#AUDIENCE_TEMPLATE}; a single row, the count skipped without sending), then the broadcast
     * written as sent and its row of the feed.
     */
    private static <I, O> void sending(ProcessDefinitionBuilder<I, O, ProcessContext> pb) {
        pb.step("Count the audience", RunTemplate.<ProcessContext>of(QbInbox.AUDIENCE_TEMPLATE,
                BroadcastProcesses::audienceParams, AUDIENCE))
            .compute("Send", (metadata, ctx) -> {
                if (ctx.contains(SENDING)) {
                    sent(ctx, ctx.get(SENDING, Sending.class));
                }
            });
    }

    static Map<String, Object> audienceParams(ProcessContext ctx) {
        Map<String, Object> params = new HashMap<>();
        Sending sending = ctx.contains(SENDING) ? ctx.get(SENDING, Sending.class) : null;
        EntityInstance broadcast = sending == null ? null : sending.broadcast();
        params.put("run", sending != null);
        params.put("audience", broadcast == null ? QbInbox.AUDIENCE_APP : broadcast.get("audience"));
        params.put("type", broadcast == null ? QbInbox.BROADCAST_SYSTEM : broadcast.get("type"));
        params.put("targetMode", broadcast == null ? QbInbox.TARGET_ALL : broadcast.get("targetMode"));
        params.put("regions", broadcast == null ? null : broadcast.get("regions"));
        params.put("broadcastId", broadcast == null ? null : uuid(broadcast.id()));
        params.put("senderId", broadcast == null || !QbInbox.SENDER_SPONSOR.equals(broadcast.get("senderKind"))
            ? null : broadcast.get("senderId"));
        params.put("at", sending == null ? ctx.opTime() : sending.sentAt());
        return params;
    }

    /** Writes a broadcast sent at {@link Sending#sentAt}: its state, count and expiry, and its row of the feed. */
    static void sent(ProcessContext ctx, Sending sending) {
        EntityInstance broadcast = sending.broadcast();
        long count = audienceCount(ctx);
        long revision = number(broadcast.get("revision")) + 1;
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("status", QbInbox.SENT);
        values.put("sentAt", sending.sentAt());
        values.put("expiresAt", sending.expiresAt());
        values.put("audienceCount", BigDecimal.valueOf(count));
        values.put("revision", BigDecimal.valueOf(revision));
        values.put("editedAt", ctx.opTime());
        ctx.changes().update(QbInbox.BROADCAST, broadcast.id(), broadcast.version(), values);
        Map<String, Object> feed = new LinkedHashMap<>();
        feed.put(ID, broadcast.id());
        for (String field : List.of("senderKind", "senderId", "audience", "type", "targetMode", "regions",
            "publicationId", "title", "body")) {
            feed.put(field, broadcast.get(field));
        }
        feed.put("sentAt", sending.sentAt());
        feed.put("expiresAt", sending.expiresAt());
        feed.put("live", true);
        ctx.changes().insert(QbInbox.FEED, feed);
        ctx.put(OUTPUT, new BroadcastOutput(uuid(broadcast.id()), QbInbox.SENT, revision, sending.sentAt(), count));
    }

    @SuppressWarnings("unchecked")
    private static long audienceCount(ProcessContext ctx) {
        List<Map<String, Object>> rows = (List<Map<String, Object>>) ctx.get(AUDIENCE);
        Object count = rows == null || rows.isEmpty() ? null : rows.getFirst().get("audienceCount");
        return count == null ? 0 : new BigDecimal(String.valueOf(count)).longValue();
    }

    // ---- the minute's job ------------------------------------------------------------------------------------------

    public static ProcessDefinition<DispatchInput, BroadcastOutput, ProcessContext> dispatch() {
        return ProcessDefinition.define(DISPATCH, 1, DispatchInput.class, BroadcastOutput.class,
            ProcessContext.class, pb -> {
                pb.description("Sends a scheduled announcement or notice once its time has come.")
                    .permissions(QbPermissions.MESSAGING_SYSTEM)
                    .internal()
                    .contextFactory((start, input) -> start(start, input, input.broadcastId()))
                    .outputMapper(ctx -> ctx.contains(OUTPUT) ? ctx.get(OUTPUT, BroadcastOutput.class)
                        : new BroadcastOutput(input(ctx), null, 0, null, null));
                // Its sender never changes: read before the locks, for the sponsor's account lock (the ban's).
                senderLocks(pb);
                pb.step("Load the scheduled one", QueryEntities.<ProcessContext>all(
                        QueryEntities.query(QbInbox.defaultDataset(QbInbox.BROADCAST), ctx -> MessageProcesses.byId(
                            ID, ctx.get(ID)), FOUND),
                        QueryEntities.query(QbInbox.defaultDataset(QbInbox.SCHEDULE), ctx -> MessageProcesses.byId(
                            ID, ctx.get(ID)), SCHEDULE_ROW),
                        QueryEntities.query(QbAccounts.defaultDataset(QbAccounts.BAN), ctx -> EntityQuery.builder()
                            .where(new QueryPredicate.In("userId", userUuids(sponsorOf(ctx)))).limit(1).build(),
                            BAN),
                        ParamReads.query(PARAMS, LIMIT_PARAMS)))
                    .step("Load the publication's card", QueryEntities.<ProcessContext>of(
                        QbPublications.defaultDataset(QbPublications.CARD), ctx -> {
                            EntityInstance found = first(ctx, FOUND);
                            return MessageProcesses.byId("publicationId", found == null ? null
                                : found.get("publicationId"));
                        }, CARD))
                    .compute("Decide", (metadata, ctx) -> dispatch(ctx));
                sending(pb);
            });
    }

    private static UUID input(ProcessContext ctx) {
        Object id = ctx.get(ID);
        return uuid(id);
    }

    /** Reads the broadcast before the locks, then takes its sponsor's account lock (none for the platform's) and its own. */
    private static <I, O> void senderLocks(ProcessDefinitionBuilder<I, O, ProcessContext> pb) {
        pb.step("Load the sender", QueryEntities.<ProcessContext>of(QbInbox.defaultDataset(QbInbox.BROADCAST),
                ctx -> MessageProcesses.byId(ID, ctx.get(ID)), BEFORE))
            .step("Lock the sponsor's account", HoldLock.<ProcessContext>exclusive(
                ctx -> QbAccounts.lockName(sponsorOf(ctx))))
            .step("Lock the broadcast", HoldLock.<ProcessContext>exclusive(
                ctx -> QbInbox.broadcastLock(ctx.get(ID))));
    }

    /** The sponsor of the broadcast read before the locks; null for the platform's or none. */
    private static Object sponsorOf(ProcessContext ctx) {
        EntityInstance before = first(ctx, BEFORE);
        return before != null && QbInbox.SENDER_SPONSOR.equals(before.get("senderKind")) ? before.get("senderId")
            : null;
    }

    static void dispatch(ProcessContext ctx) {
        EntityInstance broadcast = first(ctx, FOUND);
        EntityInstance due = first(ctx, SCHEDULE_ROW);
        if (broadcast == null || due == null) {
            return;
        }
        if (!QbInbox.SCHEDULED.equals(broadcast.get("status"))) {
            // Left behind by a cancellation or a withdrawal: nothing to send.
            ctx.changes().delete(QbInbox.SCHEDULE, due.id(), due.version());
            return;
        }
        Instant dueAt = MessageProcesses.instant(due.get("dueAt"));
        if (dueAt.isAfter(ctx.opTime())) {
            return;
        }
        ctx.changes().delete(QbInbox.SCHEDULE, due.id(), due.version());
        String cancel = null;
        if (QbInbox.SENDER_SPONSOR.equals(broadcast.get("senderKind"))) {
            EntityInstance ban = first(ctx, BAN);
            BroadcastRules.Publication publication = publication(ctx, broadcast.get("senderId"));
            if (ban != null && BanRules.banned(ban.attributes())) {
                cancel = BroadcastCodes.CANCEL_SPONSOR_BANNED;
            } else if (publication == null
                || !Set.of(com.jabiz.quizbuks.publishing.PublicationStatus.SCHEDULED,
                    com.jabiz.quizbuks.publishing.PublicationStatus.LIVE).contains(publication.status())
                || !ctx.opTime().isBefore(publication.endAt())) {
                cancel = BroadcastCodes.CANCEL_PUBLICATION_ENDED;
            }
        }
        if (cancel != null) {
            long revision = number(broadcast.get("revision")) + 1;
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("status", QbInbox.CANCELLED);
            values.put("cancelReason", cancel);
            values.put("revision", BigDecimal.valueOf(revision));
            values.put("editedAt", ctx.opTime());
            ctx.changes().update(QbInbox.BROADCAST, broadcast.id(), broadcast.version(), values);
            ctx.put(OUTPUT, new BroadcastOutput(uuid(broadcast.id()), QbInbox.CANCELLED, revision, null, null));
            return;
        }
        // Sent as of its time (not the job's): late runs neither shorten its life nor change who it matched.
        Instant sentAt = dueAt;
        ctx.put(SENDING, sending(broadcast, sentAt, publication(ctx, broadcast.get("senderId")), limits(ctx)));
    }

    // ---- cancelling ------------------------------------------------------------------------------------------------

    public static ProcessDefinition<BroadcastRef, BroadcastOutput, ProcessContext> sponsorCancel() {
        return ProcessDefinition.define(CANCEL, 1, BroadcastRef.class, BroadcastOutput.class, ProcessContext.class,
            pb -> pb
                .description("Takes a scheduled notice back to its draft, or discards a draft.")
                .permissions(QbPermissions.BROADCAST_WRITE)
                .requiresVerifiedEmail()
                .contextFactory((start, input) -> start(start, input, input.broadcastId()))
                .outputMapper(ctx -> ctx.get(OUTPUT, BroadcastOutput.class))
                .step("Lock the sponsor's account", HoldLock.<ProcessContext>exclusive(
                    ctx -> QbAccounts.lockName(ctx.request().actorId())))
                .step("Lock the notice", HoldLock.<ProcessContext>exclusive(
                    ctx -> QbInbox.broadcastLock(ctx.get(ID))))
                .step("Load the notice", QueryEntities.<ProcessContext>all(
                    QueryEntities.query(QbInbox.SPONSOR_BROADCASTS, ctx -> MessageProcesses.byId(ID, ctx.get(ID)),
                        FOUND),
                    QueryEntities.query(QbInbox.defaultDataset(QbInbox.SCHEDULE), ctx -> MessageProcesses.byId(ID,
                        ctx.get(ID)), SCHEDULE_ROW)))
                .compute("Cancel", (metadata, ctx) -> cancel(ctx)));
    }

    public static ProcessDefinition<BroadcastRef, BroadcastOutput, ProcessContext> adminCancel() {
        return ProcessDefinition.define(ADMIN_CANCEL, 1, BroadcastRef.class, BroadcastOutput.class,
            ProcessContext.class, pb -> pb
                .description("Takes a scheduled announcement back to its draft, or discards a draft.")
                .permissions(QbPermissions.ADMIN_CONTENT_WRITE)
                .requiresVerifiedEmail()
                .contextFactory((start, input) -> start(start, input, input.broadcastId()))
                .outputMapper(ctx -> ctx.get(OUTPUT, BroadcastOutput.class))
                .step("Lock the announcement", HoldLock.<ProcessContext>exclusive(
                    ctx -> QbInbox.broadcastLock(ctx.get(ID))))
                .step("Load the announcement", QueryEntities.<ProcessContext>all(
                    QueryEntities.query(QbInbox.defaultDataset(QbInbox.BROADCAST), ctx -> MessageProcesses.byId(ID,
                        ctx.get(ID)), FOUND),
                    QueryEntities.query(QbInbox.defaultDataset(QbInbox.SCHEDULE), ctx -> MessageProcesses.byId(ID,
                        ctx.get(ID)), SCHEDULE_ROW)))
                .compute("Cancel", (metadata, ctx) -> {
                    EntityInstance found = first(ctx, FOUND);
                    if (found == null || !QbInbox.SENDER_PLATFORM.equals(found.get("senderKind"))) {
                        throw notFound(ctx.get(ID));
                    }
                    cancel(ctx);
                }));
    }

    static void cancel(ProcessContext ctx) {
        BroadcastRef input = ctx.get(INPUT, BroadcastRef.class);
        EntityInstance found = first(ctx, FOUND);
        if (found == null) {
            throw notFound(input.broadcastId());
        }
        String status = found.get("status");
        String next;
        if (QbInbox.SCHEDULED.equals(status)) {
            next = QbInbox.DRAFT;
        } else if (QbInbox.DRAFT.equals(status)) {
            next = QbInbox.CANCELLED;
        } else {
            throw new BusinessRuleViolationException(List.of(new Violation(ID, BroadcastCodes.NOT_SCHEDULED,
                "Only a draft or a scheduled one is cancelled", Map.of("status", status))));
        }
        current(found, input.baseRevision());
        long revision = number(found.get("revision")) + 1;
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("status", next);
        values.put("revision", BigDecimal.valueOf(revision));
        values.put("editedAt", ctx.opTime());
        ctx.changes().update(QbInbox.BROADCAST, found.id(), found.version(), values);
        EntityInstance due = first(ctx, SCHEDULE_ROW);
        if (due != null) {
            ctx.changes().delete(QbInbox.SCHEDULE, due.id(), due.version());
        }
        ctx.put(OUTPUT, new BroadcastOutput(uuid(found.id()), next, revision, null, null));
    }

    // ---- withdrawing -----------------------------------------------------------------------------------------------

    public static ProcessDefinition<BroadcastRef, BroadcastOutput, ProcessContext> sponsorWithdraw() {
        return ProcessDefinition.define(WITHDRAW, 1, BroadcastRef.class, BroadcastOutput.class, ProcessContext.class,
            pb -> pb
                .description("Withdraws a sent notice from the takers' inboxes.")
                .permissions(QbPermissions.BROADCAST_WRITE)
                .requiresVerifiedEmail()
                .contextFactory((start, input) -> start(start, input, input.broadcastId()))
                .outputMapper(ctx -> ctx.get(OUTPUT, BroadcastOutput.class))
                .step("Lock the sponsor's account", HoldLock.<ProcessContext>exclusive(
                    ctx -> QbAccounts.lockName(ctx.request().actorId())))
                .step("Lock the notice", HoldLock.<ProcessContext>exclusive(
                    ctx -> QbInbox.broadcastLock(ctx.get(ID))))
                .step("Load the notice", withdrawReads(QbInbox.SPONSOR_BROADCASTS))
                .compute("Withdraw", (metadata, ctx) -> {
                    current(first(ctx, FOUND), ctx.get(INPUT, BroadcastRef.class).baseRevision());
                    withdraw(ctx, null);
                }));
    }

    public static ProcessDefinition<WithdrawInput, BroadcastOutput, ProcessContext> adminWithdraw() {
        return ProcessDefinition.define(ADMIN_WITHDRAW, 1, WithdrawInput.class, BroadcastOutput.class,
            ProcessContext.class, pb -> {
                pb.description("Withdraws an announcement or a sponsor's notice from every inbox, with a reason.")
                    .permissions(QbPermissions.ADMIN_CONTENT_WRITE)
                    .requiresVerifiedEmail()
                    .requiresMfa(MfaRequirement.ALWAYS)
                    .actsOn(QbInbox.BROADCAST, ID)
                    .contextFactory((start, input) -> start(start, input, input.broadcastId()))
                    .outputMapper(ctx -> ctx.get(OUTPUT, BroadcastOutput.class));
                senderLocks(pb);
                pb.step("Load the broadcast", withdrawReads(QbInbox.defaultDataset(QbInbox.BROADCAST)))
                    .compute("Withdraw", (metadata, ctx) -> {
                        String reason = ctx.get(INPUT, WithdrawInput.class).reason();
                        if (reason == null || reason.isBlank()) {
                            throw new BusinessRuleViolationException(List.of(new Violation("reason",
                                BroadcastCodes.REASON_REQUIRED, "A reason is needed", Map.of())));
                        }
                        withdraw(ctx, reason.strip());
                    });
            });
    }

    /** One statement: the broadcast (through {@code dataset}), its row of the feed and its due time. */
    private static com.jabiz.process.StepSpec<QueryEntities.AllMetadata<ProcessContext>, ProcessContext>
        withdrawReads(String dataset) {
        return QueryEntities.<ProcessContext>all(
            QueryEntities.query(dataset, ctx -> MessageProcesses.byId(ID, ctx.get(ID)), FOUND),
            QueryEntities.query(QbInbox.defaultDataset(QbInbox.FEED), ctx -> MessageProcesses.byId(ID, ctx.get(ID)),
                FEED),
            QueryEntities.query(QbInbox.defaultDataset(QbInbox.SCHEDULE), ctx -> MessageProcesses.byId(ID,
                ctx.get(ID)), SCHEDULE_ROW));
    }

    /** A sent or scheduled broadcast withdrawn: out of every inbox (the feed's row off), its due time removed. */
    static void withdraw(ProcessContext ctx, String reason) {
        EntityInstance found = first(ctx, FOUND);
        if (found == null) {
            throw notFound(ctx.get(ID));
        }
        String status = found.get("status");
        if (!QbInbox.SENT.equals(status) && !QbInbox.SCHEDULED.equals(status)) {
            throw new BusinessRuleViolationException(List.of(new Violation(ID, BroadcastCodes.NOT_SENT,
                "Only a sent or scheduled one is withdrawn", Map.of("status", status))));
        }
        ctx.put(OUTPUT, withdrawn(ctx, found, first(ctx, FEED), first(ctx, SCHEDULE_ROW), reason));
    }

    private static BroadcastOutput withdrawn(ProcessContext ctx, EntityInstance broadcast, EntityInstance feed,
        EntityInstance due, String reason) {
        long revision = number(broadcast.get("revision")) + 1;
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("status", QbInbox.WITHDRAWN);
        values.put("withdrawnAt", ctx.opTime());
        values.put("withdrawnBy", ctx.request().actorId());
        values.put("withdrawReason", reason);
        values.put("revision", BigDecimal.valueOf(revision));
        values.put("editedAt", ctx.opTime());
        ctx.changes().update(QbInbox.BROADCAST, broadcast.id(), broadcast.version(), values);
        if (feed != null && Boolean.TRUE.equals(feed.get("live"))) {
            ctx.changes().update(QbInbox.FEED, feed.id(), feed.version(), Map.of("live", false));
        }
        if (due != null) {
            ctx.changes().delete(QbInbox.SCHEDULE, due.id(), due.version());
        }
        return new BroadcastOutput(uuid(broadcast.id()), QbInbox.WITHDRAWN, revision,
            MessageProcesses.instant(broadcast.get("sentAt")), null);
    }

    // ---- a ban -----------------------------------------------------------------------------------------------------

    /**
     * Withdraws every scheduled and sent notice of a banned sponsor, in the ban's transaction and under its account
     * lock (which every writer of a sponsor's notices takes first), with the fixed reason {@code QB_ACCOUNT_SUSPENDED}
     * (the ban's own is internal). Lifting the ban sends nothing again.
     */
    public static ProcessDefinition<SponsorRef, BanOutput, ProcessContext> banWithdraw() {
        return ProcessDefinition.define(BAN_WITHDRAW, 1, SponsorRef.class, BanOutput.class, ProcessContext.class,
            pb -> pb
                .description("Withdraws a banned sponsor's scheduled and sent notices.")
                .permissions(QbPermissions.MESSAGING_SYSTEM)
                .subProcessOnly()
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put(INPUT, input);
                    return ctx;
                })
                .outputMapper(ctx -> ctx.get(OUTPUT, BanOutput.class))
                .step("Load the sponsor's notices", QueryEntities.<ProcessContext>all(
                    QueryEntities.query(QbInbox.defaultDataset(QbInbox.BROADCAST), ctx -> EntityQuery.builder()
                        .where(new QueryPredicate.Eq("senderId", ctx.get(INPUT, SponsorRef.class).sponsorId()))
                        .limit(Integer.MAX_VALUE).build(), OWN),
                    QueryEntities.query(QbInbox.defaultDataset(QbInbox.FEED), ctx -> EntityQuery.builder()
                        .where(new QueryPredicate.And(List.of(
                            new QueryPredicate.Eq("senderId", ctx.get(INPUT, SponsorRef.class).sponsorId()),
                            new QueryPredicate.Eq("live", true)))).limit(Integer.MAX_VALUE).build(), FEED)))
                .step("Load their due times", QueryEntities.<ProcessContext>of(
                    QbInbox.defaultDataset(QbInbox.SCHEDULE), ctx -> {
                        List<Object> ids = new ArrayList<>();
                        list(ctx, OWN).stream().filter(row -> QbInbox.SCHEDULED.equals(row.get("status")))
                            .forEach(row -> ids.add(row.id()));
                        return EntityQuery.builder().where(new QueryPredicate.In(ID, ids))
                            .limit(Integer.MAX_VALUE).build();
                    }, SCHEDULE_ROW))
                .compute("Withdraw them", (metadata, ctx) -> {
                    Map<String, EntityInstance> feeds = new HashMap<>();
                    list(ctx, FEED).forEach(row -> feeds.put(String.valueOf(row.id()), row));
                    Map<String, EntityInstance> dues = new HashMap<>();
                    list(ctx, SCHEDULE_ROW).forEach(row -> dues.put(String.valueOf(row.id()), row));
                    int count = 0;
                    for (EntityInstance row : list(ctx, OWN)) {
                        if (QbInbox.SENDER_SPONSOR.equals(row.get("senderKind"))
                            && Set.of(QbInbox.SENT, QbInbox.SCHEDULED).contains(row.get("status"))) {
                            withdrawn(ctx, row, feeds.get(String.valueOf(row.id())),
                                dues.get(String.valueOf(row.id())), BroadcastCodes.CANCEL_SPONSOR_BANNED);
                            count++;
                        }
                    }
                    ctx.put(OUTPUT, new BanOutput(count));
                }));
    }

    // ---- shared ----------------------------------------------------------------------------------------------------

    private static ProcessContext start(com.jabiz.process.ProcessStart start, Object input, UUID id) {
        ProcessContext ctx = new ProcessContext(start);
        ctx.put(INPUT, input);
        if (id != null) {
            ctx.put(ID, id);
        }
        return ctx;
    }

    /** A draft at the given revision (a new one when none was found). */
    private static void requireDraft(EntityInstance found, Long baseRevision) {
        if (found == null) {
            return;
        }
        if (!QbInbox.DRAFT.equals(found.get("status"))) {
            throw new BusinessRuleViolationException(List.of(new Violation(ID, BroadcastCodes.NOT_DRAFT,
                "Only a draft is changed or sent", Map.of("status", String.valueOf((Object) found.get("status"))))));
        }
        current(found, baseRevision);
    }

    /** The page read the broadcast's current revision. */
    private static void current(EntityInstance found, Long baseRevision) {
        if (found == null) {
            return;
        }
        long revision = number(found.get("revision"));
        if (baseRevision == null || baseRevision != revision) {
            throw new BusinessRuleViolationException(List.of(new Violation("baseRevision", BroadcastCodes.CHANGED,
                "The broadcast changed meanwhile", Map.of("revision", revision, "baseRevision",
                    baseRevision == null ? 0 : baseRevision))));
        }
    }

    static BroadcastRules.Draft draftOf(EntityInstance broadcast) {
        return draftOf(broadcast.get("senderKind"), broadcast.attributes(), (int) number(broadcast.get("targetCount")));
    }

    private static BroadcastRules.Draft draftOf(String senderKind, Map<String, Object> values, int targetCount) {
        return new BroadcastRules.Draft(senderKind, text(values.get("audience")), text(values.get("type")),
            map(values.get("title")), map(values.get("body")), text(values.get("targetMode")),
            text(values.get("regions")), targetCount, values.get("publicationId") == null ? null
                : String.valueOf(values.get("publicationId")), MessageProcesses.instant(values.get("scheduledAt")),
            MessageProcesses.instant(values.get("expiresAt")));
    }

    private static BroadcastRules.Limits limits(ProcessContext ctx) {
        Map<String, Object> params = ParamReads.values(ctx, PARAMS);
        return new BroadcastRules.Limits(
            ParamReads.days(params.get(QbParams.BROADCAST_SPONSOR_MAX), BroadcastRules.DEFAULT_MAX_PER_PUBLICATION),
            Duration.ofHours(ParamReads.days(params.get(QbParams.BROADCAST_SPONSOR_INTERVAL),
                BroadcastRules.DEFAULT_MIN_INTERVAL_HOURS)),
            Duration.ofDays(ParamReads.days(params.get(QbParams.BROADCAST_EXPIRES_DAYS),
                BroadcastRules.DEFAULT_EXPIRES_DAYS)));
    }

    /**
     * The card read, when it is a publication of {@code sponsorId}'s (the caller of a sponsor's process, the sender of
     * a notice the job sends); none otherwise: another sponsor's publication is as good as none.
     */
    private static BroadcastRules.Publication publication(ProcessContext ctx, Object sponsorId) {
        EntityInstance card = first(ctx, CARD);
        if (card == null || sponsorId == null || !String.valueOf(sponsorId).equals(card.get("sponsorId"))) {
            return null;
        }
        return new BroadcastRules.Publication(String.valueOf(card.id()), card.get("sponsorId"), card.get("status"),
            card.get("regions"), MessageProcesses.instant(card.get("startAt")),
            MessageProcesses.instant(card.get("endAt")));
    }

    /** The sponsor's other notices (not {@code id}). */
    private static List<BroadcastRules.Other> others(ProcessContext ctx, Object id) {
        List<BroadcastRules.Other> others = new ArrayList<>();
        for (EntityInstance row : list(ctx, OWN)) {
            if (id != null && String.valueOf(row.id()).equals(String.valueOf(id))) {
                continue;
            }
            Instant sendsAt = MessageProcesses.instant(row.get("sentAt"));
            if (sendsAt == null) {
                sendsAt = MessageProcesses.instant(row.get("scheduledAt"));
            }
            others.add(new BroadcastRules.Other(String.valueOf(row.id()), row.get("publicationId") == null ? null
                : String.valueOf((Object) row.get("publicationId")), row.get("status"), sendsAt));
        }
        return others;
    }

    private static String regions(List<String> given) {
        if (given == null || given.isEmpty()) {
            return null;
        }
        try {
            return Regions.targets(given);
        } catch (IllegalArgumentException e) {
            throw new BusinessRuleViolationException(List.of(new Violation("regions", BroadcastCodes.REGIONS_FORMAT,
                e.getMessage(), Map.of("regions", String.join(",", given)))));
        }
    }

    private static void code(List<Violation> problems, String field, String value, List<String> allowed) {
        if (!allowed.contains(value)) {
            problems.add(new Violation(field, BroadcastCodes.INVALID_VALUE, field + " must be one of " + allowed,
                Map.of("field", field, "value", String.valueOf(value))));
        }
    }

    /** The named users in their canonical form (UUIDs in lower case), each once; what is no UUID kept as given. */
    private static List<String> canonicalUsers(List<String> given) {
        Set<String> ids = new LinkedHashSet<>();
        for (String id : given) {
            UUID uuid = uuid(id == null ? null : id.strip());
            ids.add(uuid == null ? String.valueOf(id).strip() : uuid.toString());
        }
        return List.copyOf(ids);
    }

    private static List<Object> userUuids(Object given) {
        List<Object> ids = new ArrayList<>();
        if (given instanceof List<?> list) {
            for (Object id : list) {
                UUID uuid = uuid(id);
                if (uuid != null && ids.size() <= BroadcastRules.MAX_TARGETS) {
                    ids.add(uuid);
                }
            }
        } else if (given != null && uuid(given) != null) {
            ids.add(uuid(given));
        }
        return ids;
    }

    private static List<Object> ids(Object id) {
        List<Object> ids = new ArrayList<>();
        if (id != null) {
            ids.add(id);
        }
        return ids;
    }

    private static EntityNotFoundException notFound(Object id) {
        return new EntityNotFoundException(QbInbox.BROADCAST + " " + id + " not found");
    }

    private static boolean same(Object a, Object b) {
        if (a instanceof BigDecimal x && b instanceof BigDecimal y) {
            return x.compareTo(y) == 0;
        }
        if (a instanceof OffsetDateTime || b instanceof OffsetDateTime || a instanceof Instant || b instanceof Instant) {
            Instant x = MessageProcesses.instant(a);
            Instant y = MessageProcesses.instant(b);
            return Objects.equals(x, y);
        }
        if (a == null || b == null) {
            return a == b;
        }
        return Objects.equals(String.valueOf(a), String.valueOf(b));
    }

    private static long number(Object value) {
        return value == null ? 0 : new BigDecimal(String.valueOf(value)).longValue();
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, ?> map(Object value) {
        return value instanceof Map<?, ?> m ? (Map<String, ?>) m : null;
    }

    static UUID uuid(Object id) {
        return MessageProcesses.uuid(id);
    }

    @SuppressWarnings("unchecked")
    static List<EntityInstance> list(ProcessContext ctx, String key) {
        Object rows = ctx.contains(key) ? ctx.get(key) : null;
        return rows == null ? List.of() : (List<EntityInstance>) rows;
    }

    static EntityInstance first(ProcessContext ctx, String key) {
        List<EntityInstance> rows = list(ctx, key);
        return rows.isEmpty() ? null : rows.getFirst();
    }
}
