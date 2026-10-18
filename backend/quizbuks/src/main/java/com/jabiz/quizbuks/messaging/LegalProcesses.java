package com.jabiz.quizbuks.messaging;

import com.jabiz.approval.ContentHash;
import com.jabiz.context.RequestContext;
import com.jabiz.entity.Violation;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.process.StepSpec;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.identity.CallerChecks;
import com.jabiz.quizbuks.identity.QbAccounts;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.runtime.BusinessRuleViolationException;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.param.ParamEntities;
import com.jabiz.runtime.process.steps.HoldLock;
import com.jabiz.runtime.process.steps.LoadEntity;
import com.jabiz.runtime.process.steps.QueryEntities;
import com.jabiz.security.MfaRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Publishing legal texts and accepting them (docs/quizbuks/plans/Q7-messages-legal.md, D-Q7-4, D-Q7-5):
 * <ul>
 *   <li>{@code QB_LEGAL_PUBLISH}: a content administrator, with a second factor, publishes a kind's draft as its next
 *       version, now or from a later time (Q7-5, Q7-6). The version is written once; the draft stays editable. The
 *       platform audits both.</li>
 *   <li>{@code QB_LEGAL_WITHDRAW}: the same, with a second factor and a reason, withdraws a version announced for later
 *       before it takes effect (review M1); its number is not given again.</li>
 *   <li>{@code QB_CONSENT_ACCEPT}: a signed-in user accepts the versions in effect of the texts their front end asks
 *       for, after signing in (Q7-2): the takers' terms and the privacy policy in the app, the sponsors' terms and the
 *       privacy policy in the sponsor console.</li>
 * </ul>
 */
public final class LegalProcesses {

    public static final String PUBLISH = "QB_LEGAL_PUBLISH";
    public static final String WITHDRAW = "QB_LEGAL_WITHDRAW";
    public static final String ACCEPT = "QB_CONSENT_ACCEPT";

    static final String INPUT = "input";
    static final String DOC_ID = "docId";
    static final String DOC = "doc";
    static final String VERSIONS = "versions";
    static final String WITHDRAWALS = "withdrawals";
    static final String HORIZON = "maxAnnounceDays";
    static final String VERSION_KEY = "legalVersionId";
    static final String VERSION = "version";
    static final String OUTPUT = "output";

    private static final String UUID_PATTERN =
        "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";

    /**
     * @param docId           the draft ({@code QbLegalDoc}) to publish
     * @param effectiveAt     when the version takes effect: none for now, or a later time (announced until then), at
     *                        most {@code qb.legal.max-announce-days} ahead
     * @param requiresConsent whether users must accept it again (a change of substance); a rewording does not ask
     *                        anybody. The first version of a kind always does.
     * @param baseRevision    the draft's version the publisher read (required, review L1); no longer current: 422
     * @param announce        whether an announcement of the new version goes to the inboxes of those it concerns
     *                        (Q7c: the app's users for the takers' terms, the sponsors for theirs and the notice, both
     *                        for the privacy policy), sent by the minute's job
     */
    public record PublishInput(@NotBlank @Pattern(regexp = UUID_PATTERN) String docId, Instant effectiveAt,
        Boolean requiresConsent, @NotNull Long baseRevision, Boolean announce) {}

    /** @param announcements the announcements of the new version, scheduled now (none unless asked) */
    public record PublishOutput(String kind, String versionId, int versionNo, Instant effectiveAt,
        boolean requiresConsent, String contentHash, List<String> announcements) {}

    /**
     * @param legalVersionId the announced version ({@code QbLegalVersion}) to withdraw
     * @param reason         why (kept with the withdrawal, for the administrators)
     */
    public record WithdrawInput(@NotBlank @Pattern(regexp = UUID_PATTERN) String legalVersionId,
        @NotBlank @Size(max = QbLegal.REASON_LENGTH) String reason) {}

    public record WithdrawOutput(String kind, int versionNo, Instant withdrawnAt) {}

    /** One text accepted: its kind and the number of the version in effect the page showed. */
    public record Acceptance(@NotBlank @Size(max = 20) String kind, @NotNull @Positive Integer versionNo) {}

    public record AcceptInput(@NotEmpty @Size(max = 4) List<@Valid @NotNull Acceptance> items) {}

    /** @param recorded the kinds whose acceptance was recorded now (not those accepted before) */
    public record AcceptOutput(List<String> recorded) {}

    // ---- publish ---------------------------------------------------------------------------------------------------

    public static ProcessDefinition<PublishInput, PublishOutput, ProcessContext> publish(MessageTexts texts,
        MailValues values) {
        return ProcessDefinition.define(PUBLISH, 1, PublishInput.class, PublishOutput.class, ProcessContext.class,
            pb -> pb
                .description("Publishes the draft of a legal text as its next version, now or from a later time.")
                .permissions(QbPermissions.ADMIN_CONTENT_WRITE)
                .requiresVerifiedEmail()
                // Q7-5: a second factor every time; the platform audits the version written.
                .requiresMfa(MfaRequirement.ALWAYS)
                .actsOn(QbLegal.DOC, DOC_ID)
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put(INPUT, input);
                    ctx.put(DOC_ID, input.docId());
                    return ctx;
                })
                .outputMapper(ctx -> ctx.get(OUTPUT, PublishOutput.class))
                // The draft's kind never changes, so it is read before the kind's lock (review L3); its version is
                // checked against the publisher's and written back conditionally.
                .step("Load the draft", LoadEntity.by(QbLegal.defaultDataset(QbLegal.DOC), DOC_ID, DOC))
                .step("Lock the kind's versions", HoldLock.<ProcessContext>exclusive(
                    ctx -> QbLegal.lockName(ctx.get(DOC, EntityInstance.class).get("kind"))))
                .step("Load the kind's versions", loadKind(ctx -> ctx.get(DOC, EntityInstance.class).get("kind")))
                .compute("Publish", (metadata, ctx) -> publish(ctx, texts, values)));
    }

    /**
     * Under the kind's lock, in one statement: its versions, its withdrawals and the announcement horizon (read
     * without failing while it has no published value).
     */
    static StepSpec<QueryEntities.AllMetadata<ProcessContext>, ProcessContext> loadKind(
        Function<ProcessContext, String> kind) {
        return QueryEntities.<ProcessContext>all(
            QueryEntities.query(QbLegal.defaultDataset(QbLegal.VERSION), ctx -> EntityQuery.builder()
                .where(new QueryPredicate.Eq("kind", kind.apply(ctx))).limit(Integer.MAX_VALUE).build(), VERSIONS),
            QueryEntities.query(QbLegal.defaultDataset(QbLegal.WITHDRAWAL), ctx -> EntityQuery.builder()
                .where(new QueryPredicate.Eq("kind", kind.apply(ctx))).limit(Integer.MAX_VALUE).build(),
                WITHDRAWALS),
            QueryEntities.query(ParamEntities.DATASET, ctx -> EntityQuery.builder()
                .where(new QueryPredicate.Eq(ParamEntities.KEY, QbParams.LEGAL_MAX_ANNOUNCE_DAYS)).limit(1).build(),
                HORIZON));
    }

    /** The announcement horizon in days: the published value, else the default. */
    @SuppressWarnings("unchecked")
    static int maxAnnounceDays(ProcessContext ctx) {
        List<EntityInstance> rows = (List<EntityInstance>) ctx.get(HORIZON);
        if (rows == null || rows.isEmpty() || rows.getFirst().get(ParamEntities.VALUE) == null) {
            return QbParams.LEGAL_MAX_ANNOUNCE_DAYS_DEFAULT;
        }
        return new BigDecimal(String.valueOf((Object) rows.getFirst().get(ParamEntities.VALUE))).intValueExact();
    }

    /** What a version's content hash covers: its title and text in every language. */
    static String hash(Map<?, ?> title, Map<?, ?> body) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("title", sorted(title));
        content.put("body", sorted(body));
        return ContentHash.of(content);
    }

    private static Map<String, Object> sorted(Map<?, ?> text) {
        Map<String, Object> sorted = new java.util.TreeMap<>();
        if (text != null) {
            text.forEach((language, value) -> sorted.put(String.valueOf(language), value));
        }
        return sorted;
    }

    static void publish(ProcessContext ctx, MessageTexts texts, MailValues times) {
        PublishInput input = ctx.get(INPUT, PublishInput.class);
        EntityInstance doc = ctx.get(DOC, EntityInstance.class);
        if (input.baseRevision() != doc.version()) {
            throw new BusinessRuleViolationException(List.of(new Violation("baseRevision", LegalCodes.CHANGED,
                "The draft changed meanwhile: version " + doc.version() + ", not " + input.baseRevision(),
                Map.of("revision", doc.version(), "baseRevision", input.baseRevision()))));
        }
        String kind = doc.get("kind");
        Map<?, ?> title = doc.get("title");
        Map<?, ?> body = doc.get("body");
        Map<?, ?> summary = doc.get("changeSummary");
        List<LegalRules.Version> versions = versions(ctx);
        List<Violation> problems = new ArrayList<>();
        for (String language : LegalRules.missingLanguages(title, QbLegal.REQUIRED_LANGUAGES)) {
            problems.add(missing("title", language));
        }
        for (String language : LegalRules.missingLanguages(body, QbLegal.REQUIRED_LANGUAGES)) {
            problems.add(missing("body", language));
        }
        // After the first version, the summary of changes is what users read before accepting again (the consent
        // prompt) and what the history lists.
        if (LegalRules.latest(versions).isPresent()) {
            for (String language : LegalRules.missingLanguages(summary, QbLegal.REQUIRED_LANGUAGES)) {
                problems.add(missing("changeSummary", language));
            }
        }
        Instant effectiveAt = input.effectiveAt() == null ? ctx.opTime() : input.effectiveAt();
        String hash = hash(title, body);
        problems.addAll(LegalRules.publishProblems(versions, hash, effectiveAt, ctx.opTime(),
            LegalRules.horizon(ctx.opTime(), maxAnnounceDays(ctx))));
        if (!problems.isEmpty()) {
            throw new BusinessRuleViolationException(problems);
        }
        int number = LegalRules.nextNumber(versions);
        boolean requiresConsent = LegalRules.requiresConsent(versions,
            Boolean.TRUE.equals(input.requiresConsent()));
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("kind", kind);
        values.put("versionNumber", BigDecimal.valueOf(number));
        values.put("title", title);
        values.put("body", body);
        values.put("changeSummary", LegalRules.latest(versions).isEmpty() ? null : summary);
        values.put("requiresConsent", requiresConsent);
        values.put("publishedAt", ctx.opTime());
        values.put("publishedBy", ctx.request().actorId());
        values.put("effectiveAt", effectiveAt);
        values.put("contentHash", hash);
        Object versionId = ctx.changes().insert(QbLegal.VERSION, values);
        Map<String, Object> draft = new LinkedHashMap<>();
        draft.put("latestVersionNo", BigDecimal.valueOf(number));
        draft.put("lastPublishedAt", ctx.opTime());
        ctx.changes().update(QbLegal.DOC, doc.id(), doc.version(), draft);
        List<String> announcements = Boolean.TRUE.equals(input.announce())
            ? LegalAnnouncements.schedule(ctx, texts, times, kind, number, effectiveAt, versionId) : List.of();
        ctx.put(OUTPUT, new PublishOutput(kind, String.valueOf(versionId), number, effectiveAt, requiresConsent,
            hash, announcements));
    }

    private static Violation missing(String field, String language) {
        return new Violation(field, LegalCodes.TRANSLATION_MISSING, "The " + field + " in " + language
            + " is missing", Map.of("field", field, "lang", language));
    }

    /** The kind's versions as {@link #loadKind} read them, the withdrawn ones marked. */
    @SuppressWarnings("unchecked")
    static List<LegalRules.Version> versions(ProcessContext ctx) {
        List<EntityInstance> rows = (List<EntityInstance>) ctx.get(VERSIONS);
        List<EntityInstance> withdrawals = (List<EntityInstance>) ctx.get(WITHDRAWALS);
        Set<String> withdrawn = new java.util.HashSet<>();
        for (EntityInstance withdrawal : withdrawals == null ? List.<EntityInstance>of() : withdrawals) {
            withdrawn.add(String.valueOf((Object) withdrawal.get(VERSION_KEY)));
        }
        List<LegalRules.Version> versions = new ArrayList<>();
        for (EntityInstance row : rows == null ? List.<EntityInstance>of() : rows) {
            versions.add(version(row, withdrawn.contains(String.valueOf(row.id()))));
        }
        return versions;
    }

    static LegalRules.Version version(EntityInstance row, boolean withdrawn) {
        return new LegalRules.Version(ConsentChecks.number(row.get("versionNumber")),
            Boolean.TRUE.equals(row.get("requiresConsent")), instant(row.get("effectiveAt")), row.get("contentHash"),
            withdrawn);
    }

    static Instant instant(Object value) {
        return value instanceof OffsetDateTime time ? time.toInstant()
            : value instanceof Instant time ? time : Instant.parse(String.valueOf(value));
    }

    // ---- withdraw --------------------------------------------------------------------------------------------------

    public static ProcessDefinition<WithdrawInput, WithdrawOutput, ProcessContext> withdraw() {
        return ProcessDefinition.define(WITHDRAW, 1, WithdrawInput.class, WithdrawOutput.class,
            ProcessContext.class, pb -> pb
                .description("Withdraws a version of a legal text announced for later, before it takes effect.")
                .permissions(QbPermissions.ADMIN_CONTENT_WRITE)
                .requiresVerifiedEmail()
                .requiresMfa(MfaRequirement.ALWAYS)
                .actsOn(QbLegal.VERSION, VERSION_KEY)
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put(INPUT, input);
                    ctx.put(VERSION_KEY, input.legalVersionId());
                    return ctx;
                })
                .outputMapper(ctx -> ctx.get(OUTPUT, WithdrawOutput.class))
                // A version is written once: its kind and effective time are read before the kind's lock.
                .step("Load the version", LoadEntity.by(QbLegal.defaultDataset(QbLegal.VERSION), VERSION_KEY,
                    VERSION))
                .step("Lock the kind's versions", HoldLock.<ProcessContext>exclusive(
                    ctx -> QbLegal.lockName(ctx.get(VERSION, EntityInstance.class).get("kind"))))
                .step("Load the kind's versions", loadKind(ctx -> ctx.get(VERSION, EntityInstance.class).get("kind")))
                // Review L2: the version's announcements (at most two; their source never changes) go with it, under
                // their locks (after the kind's: qb.legal -> qb.broadcast), read again under them.
                .step("Find its announcements", QueryEntities.<ProcessContext>of(
                    QbInbox.defaultDataset(QbInbox.BROADCAST), ctx -> EntityQuery.builder()
                        .where(new QueryPredicate.Eq("sourceKey", LegalAnnouncements.sourceKey(
                            ctx.get(VERSION, EntityInstance.class).id()))).limit(10).build(), ANNOUNCED))
                .step("Lock the first", HoldLock.<ProcessContext>exclusive(ctx -> announcementLock(ctx, 0)))
                .step("Lock the second", HoldLock.<ProcessContext>exclusive(ctx -> announcementLock(ctx, 1)))
                .step("Load them again", QueryEntities.<ProcessContext>all(
                    QueryEntities.query(QbInbox.defaultDataset(QbInbox.BROADCAST), ctx -> announcements(ctx),
                        ANNOUNCED_NOW),
                    QueryEntities.query(QbInbox.defaultDataset(QbInbox.FEED), ctx -> announcements(ctx),
                        ANNOUNCED_FEED),
                    QueryEntities.query(QbInbox.defaultDataset(QbInbox.SCHEDULE), ctx -> announcements(ctx),
                        ANNOUNCED_DUE)))
                .compute("Withdraw", (metadata, ctx) -> {
                    withdraw(ctx);
                    withdrawAnnouncements(ctx);
                }));
    }

    private static final String ANNOUNCED = "legal.announced";
    private static final String ANNOUNCED_NOW = "legal.announcedNow";
    private static final String ANNOUNCED_FEED = "legal.announcedFeed";
    private static final String ANNOUNCED_DUE = "legal.announcedDue";

    @SuppressWarnings("unchecked")
    private static List<EntityInstance> rowsOf(ProcessContext ctx, String key) {
        Object rows = ctx.get(key);
        return rows == null ? List.of() : (List<EntityInstance>) rows;
    }

    /** The ids of the version's announcements, in order (the locks' order). */
    private static List<String> announcementIds(ProcessContext ctx) {
        return rowsOf(ctx, ANNOUNCED).stream().map(row -> String.valueOf(row.id())).sorted().toList();
    }

    private static String announcementLock(ProcessContext ctx, int index) {
        List<String> ids = announcementIds(ctx);
        return ids.size() > index ? QbInbox.broadcastLock(ids.get(index)) : null;
    }

    private static EntityQuery announcements(ProcessContext ctx) {
        List<Object> ids = new java.util.ArrayList<>();
        announcementIds(ctx).forEach(id -> ids.add(java.util.UUID.fromString(id)));
        return EntityQuery.builder().where(new QueryPredicate.In("broadcastId", ids)).limit(10).build();
    }

    /** Each scheduled or sent announcement of the withdrawn version withdrawn by the system, with a fixed code. */
    private static void withdrawAnnouncements(ProcessContext ctx) {
        Map<String, EntityInstance> feeds = new java.util.HashMap<>();
        rowsOf(ctx, ANNOUNCED_FEED).forEach(row -> feeds.put(String.valueOf(row.id()), row));
        Map<String, EntityInstance> dues = new java.util.HashMap<>();
        rowsOf(ctx, ANNOUNCED_DUE).forEach(row -> dues.put(String.valueOf(row.id()), row));
        for (EntityInstance row : rowsOf(ctx, ANNOUNCED_NOW)) {
            if (QbInbox.SCHEDULED.equals(row.get("status")) || QbInbox.SENT.equals(row.get("status"))) {
                BroadcastProcesses.withdrawn(ctx, row, feeds.get(String.valueOf(row.id())),
                    dues.get(String.valueOf(row.id())), BroadcastCodes.LEGAL_VERSION_WITHDRAWN, QbInbox.BY_SYSTEM);
            }
        }
    }

    static void withdraw(ProcessContext ctx) {
        WithdrawInput input = ctx.get(INPUT, WithdrawInput.class);
        EntityInstance row = ctx.get(VERSION, EntityInstance.class);
        boolean withdrawn = versions(ctx).stream().anyMatch(v -> v.isWithdrawn()
            && v.number() == ConsentChecks.number(row.get("versionNumber")));
        LegalRules.Version version = version(row, withdrawn);
        List<Violation> problems = LegalRules.withdrawProblems(version, ctx.opTime());
        if (!problems.isEmpty()) {
            throw new BusinessRuleViolationException(problems);
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(VERSION_KEY, row.id());
        values.put("kind", row.get("kind"));
        values.put("versionNumber", BigDecimal.valueOf(version.number()));
        values.put("withdrawnAt", ctx.opTime());
        values.put("withdrawnBy", ctx.request().actorId());
        values.put("reason", input.reason().strip());
        ctx.changes().insert(QbLegal.WITHDRAWAL, values);
        ctx.put(OUTPUT, new WithdrawOutput(row.get("kind"), version.number(), ctx.opTime()));
    }

    // ---- accept ----------------------------------------------------------------------------------------------------

    public static ProcessDefinition<AcceptInput, AcceptOutput, ProcessContext> accept() {
        return ProcessDefinition.define(ACCEPT, 1, AcceptInput.class, AcceptOutput.class, ProcessContext.class,
            pb -> {
                pb.description("Accepts the versions in effect of the terms and the privacy policy.")
                    .permissions(QbPermissions.ACCOUNT_READ)
                    .requiresVerifiedEmail()
                    .contextFactory((start, input) -> {
                        ProcessContext ctx = new ProcessContext(start);
                        ctx.put(INPUT, input);
                        return ctx;
                    })
                    .outputMapper(ctx -> ctx.get(OUTPUT, AcceptOutput.class))
                    // Two prompts at once (two tabs) record one acceptance: the second finds the first.
                    .step("Lock the account", HoldLock.<ProcessContext>exclusive(
                        ctx -> QbAccounts.lockName(ctx.request().actorId())))
                    .steps(CallerChecks::refuseBanned);
                ConsentChecks.load(pb, "Load the terms in effect", QbLegal.KINDS, ctx -> ctx.request().actorId());
                pb.compute("Accept", (metadata, ctx) -> accept(ctx));
            });
    }

    /**
     * The kinds a caller accepts: those of their sign-in entry (the app's or the sponsor console's); outside those
     * entries (the administration's tokens of tests and tools), those of the roles they hold.
     */
    static Set<String> kindsOf(RequestContext request) {
        if ("app".equals(request.entry())) {
            return Set.copyOf(QbLegal.APP_KINDS);
        }
        if ("sponsor".equals(request.entry())) {
            return Set.copyOf(QbLegal.SPONSOR_KINDS);
        }
        Set<String> kinds = new LinkedHashSet<>();
        if (request.permissions().contains(QbPermissions.PLAY)) {
            kinds.addAll(QbLegal.APP_KINDS);
        }
        if (request.permissions().contains(QbPermissions.SPONSOR_ME)) {
            kinds.addAll(QbLegal.SPONSOR_KINDS);
        }
        return kinds;
    }

    static void accept(ProcessContext ctx) {
        AcceptInput input = ctx.get(INPUT, AcceptInput.class);
        Set<String> allowed = kindsOf(ctx.request());
        Map<String, ConsentChecks.State> states = ConsentChecks.states(ctx);
        List<Violation> problems = new ArrayList<>();
        List<ConsentChecks.State> record = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Acceptance item : input.items()) {
            ConsentChecks.State state = states.get(item.kind());
            if (!allowed.contains(item.kind()) || !seen.add(item.kind())) {
                problems.add(new Violation("items", LegalCodes.CONSENT_KIND, item.kind() + " is not asked of you "
                    + "here", Map.of("kind", item.kind())));
            } else if (state == null || state.currentVersionNo() != item.versionNo()) {
                // Only the version in effect is accepted: an announced one is accepted once it takes effect.
                problems.add(new Violation("items", LegalCodes.TERMS_OUTDATED, "Version " + item.versionNo() + " of "
                    + item.kind() + " is not the one in effect", Map.of("kind", item.kind(),
                    "versionNo", state == null ? 0 : state.currentVersionNo())));
            } else if (state.accepted() < state.currentVersionNo()) {
                record.add(state);
            }
        }
        if (!problems.isEmpty()) {
            throw new BusinessRuleViolationException(problems);
        }
        ConsentChecks.record(ctx, ctx.request().actorId(), record, QbLegal.SOURCE_RECONSENT);
        ctx.put(OUTPUT, new AcceptOutput(record.stream().map(ConsentChecks.State::kind).toList()));
    }

    private LegalProcesses() {}
}
