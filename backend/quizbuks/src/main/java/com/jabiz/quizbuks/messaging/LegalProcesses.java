package com.jabiz.quizbuks.messaging;

import com.jabiz.approval.ContentHash;
import com.jabiz.context.RequestContext;
import com.jabiz.entity.Violation;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.identity.CallerChecks;
import com.jabiz.quizbuks.identity.QbAccounts;
import com.jabiz.runtime.BusinessRuleViolationException;
import com.jabiz.runtime.EntityInstance;
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

/**
 * Publishing legal texts and accepting them (docs/quizbuks/plans/Q7-messages-legal.md, D-Q7-4, D-Q7-5):
 * <ul>
 *   <li>{@code QB_LEGAL_PUBLISH}: a content administrator, with a second factor, publishes a kind's draft as its next
 *       version, now or from a later time (Q7-5, Q7-6). The version is written once; the draft stays editable. The
 *       platform audits both.</li>
 *   <li>{@code QB_CONSENT_ACCEPT}: a signed-in user accepts the versions in effect of the texts their front end asks
 *       for, after signing in (Q7-2): the takers' terms and the privacy policy in the app, the sponsors' terms and the
 *       privacy policy in the sponsor console.</li>
 * </ul>
 */
public final class LegalProcesses {

    public static final String PUBLISH = "QB_LEGAL_PUBLISH";
    public static final String ACCEPT = "QB_CONSENT_ACCEPT";

    static final String INPUT = "input";
    static final String DOC_ID = "docId";
    static final String DOC = "doc";
    static final String VERSIONS = "versions";
    static final String OUTPUT = "output";

    private static final String UUID_PATTERN =
        "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";

    /**
     * @param docId           the draft ({@code QbLegalDoc}) to publish
     * @param effectiveAt     when the version takes effect: none for now, or a later time (announced until then)
     * @param requiresConsent whether users must accept it again (a change of substance); a rewording does not ask
     *                        anybody. The first version of a kind always does.
     * @param baseRevision    the draft's version the publisher read; when given and no longer current, 422
     */
    public record PublishInput(@NotBlank @Pattern(regexp = UUID_PATTERN) String docId, Instant effectiveAt,
        Boolean requiresConsent, Long baseRevision) {}

    public record PublishOutput(String kind, int versionNo, Instant effectiveAt, boolean requiresConsent,
        String contentHash) {}

    /** One text accepted: its kind and the number of the version in effect the page showed. */
    public record Acceptance(@NotBlank @Size(max = 20) String kind, @NotNull @Positive Integer versionNo) {}

    public record AcceptInput(@NotEmpty @Size(max = 4) List<@Valid @NotNull Acceptance> items) {}

    /** @param recorded the kinds whose acceptance was recorded now (not those accepted before) */
    public record AcceptOutput(List<String> recorded) {}

    // ---- publish ---------------------------------------------------------------------------------------------------

    public static ProcessDefinition<PublishInput, PublishOutput, ProcessContext> publish() {
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
                .step("Lock the kind's publications", HoldLock.<ProcessContext>exclusive(
                    ctx -> QbLegal.lockName(ctx.get(DOC_ID))))
                .step("Load the draft", LoadEntity.by(QbLegal.defaultDataset(QbLegal.DOC), DOC_ID, DOC))
                .step("Load the kind's versions", QueryEntities.<ProcessContext>of(
                    QbLegal.defaultDataset(QbLegal.VERSION), ctx -> EntityQuery.builder()
                        .where(new QueryPredicate.Eq("kind", ctx.get(DOC, EntityInstance.class).get("kind")))
                        .limit(Integer.MAX_VALUE).build(), VERSIONS))
                .compute("Publish", (metadata, ctx) -> publish(ctx)));
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

    static void publish(ProcessContext ctx) {
        PublishInput input = ctx.get(INPUT, PublishInput.class);
        EntityInstance doc = ctx.get(DOC, EntityInstance.class);
        if (input.baseRevision() != null && input.baseRevision() != doc.version()) {
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
        if (!versions.isEmpty()) {
            for (String language : LegalRules.missingLanguages(summary, QbLegal.REQUIRED_LANGUAGES)) {
                problems.add(missing("changeSummary", language));
            }
        }
        Instant effectiveAt = input.effectiveAt() == null ? ctx.opTime() : input.effectiveAt();
        String hash = hash(title, body);
        problems.addAll(LegalRules.publishProblems(versions, hash, effectiveAt, ctx.opTime()));
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
        values.put("changeSummary", versions.isEmpty() ? null : summary);
        values.put("requiresConsent", requiresConsent);
        values.put("publishedAt", ctx.opTime());
        values.put("publishedBy", ctx.request().actorId());
        values.put("effectiveAt", effectiveAt);
        values.put("contentHash", hash);
        ctx.changes().insert(QbLegal.VERSION, values);
        Map<String, Object> draft = new LinkedHashMap<>();
        draft.put("latestVersionNo", BigDecimal.valueOf(number));
        draft.put("lastPublishedAt", ctx.opTime());
        ctx.changes().update(QbLegal.DOC, doc.id(), doc.version(), draft);
        ctx.put(OUTPUT, new PublishOutput(kind, number, effectiveAt, requiresConsent, hash));
    }

    private static Violation missing(String field, String language) {
        return new Violation(field, LegalCodes.TRANSLATION_MISSING, "The " + field + " in " + language
            + " is missing", Map.of("field", field, "lang", language));
    }

    @SuppressWarnings("unchecked")
    static List<LegalRules.Version> versions(ProcessContext ctx) {
        List<EntityInstance> rows = (List<EntityInstance>) ctx.get(VERSIONS);
        List<LegalRules.Version> versions = new ArrayList<>();
        for (EntityInstance row : rows == null ? List.<EntityInstance>of() : rows) {
            versions.add(new LegalRules.Version(ConsentChecks.number(row.get("versionNumber")),
                Boolean.TRUE.equals(row.get("requiresConsent")), instant(row.get("effectiveAt")),
                row.get("contentHash")));
        }
        return versions;
    }

    static Instant instant(Object value) {
        return value instanceof OffsetDateTime time ? time.toInstant()
            : value instanceof Instant time ? time : Instant.parse(String.valueOf(value));
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
