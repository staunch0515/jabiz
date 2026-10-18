package com.jabiz.quizbuks.publishing;

import com.jabiz.entity.Violation;
import com.jabiz.i18n.MessageCatalog;
import com.jabiz.i18n.MessageTemplate;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.identity.CallerChecks;
import com.jabiz.quizbuks.country.Regions;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.EntityNotFoundException;
import com.jabiz.runtime.process.steps.HoldLock;
import com.jabiz.runtime.process.steps.LoadEntity;
import com.jabiz.runtime.process.steps.QueryEntities;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static com.jabiz.quizbuks.publishing.PublicationEditing.FOUND;
import static com.jabiz.quizbuks.publishing.PublicationEditing.INPUT;
import static com.jabiz.quizbuks.publishing.PublicationEditing.OUTPUT;
import static com.jabiz.quizbuks.publishing.PublicationEditing.PUBLICATION;
import static com.jabiz.quizbuks.publishing.PublicationEditing.PUB_ID;
import static com.jabiz.quizbuks.publishing.PublicationEditing.TIERS;
import static com.jabiz.quizbuks.publishing.PublicationEditing.decimal;
import static com.jabiz.quizbuks.publishing.PublicationEditing.first;
import static com.jabiz.quizbuks.publishing.PublicationEditing.list;
import static com.jabiz.quizbuks.publishing.PublicationEditing.number;
import static com.jabiz.quizbuks.publishing.PublicationEditing.uuid;

/**
 * Drafts of publications (M-30, M-32, M-33, M-36; docs/quizbuks/plans/Q4-publishing.md):
 * <ul>
 *   <li>{@code QB_PUBLICATION_SAVE}: a new draft (without {@code publicationId}) or a draft changed: the version,
 *       the policy, the tiers. A draft may be incomplete; the output lists what submitting it would be refused for;</li>
 *   <li>{@code QB_PUBLICATION_DELETE}: deletes a draft with its tiers;</li>
 *   <li>{@code QB_PUBLICATION_COPY}: a new draft with the policy and tiers of any of the caller's publications,
 *       another version or other times if given.</li>
 * </ul>
 * Only a draft changes (D-Q4-2): once submitted, the content and the whole policy are frozen; to change them, copy.
 */
public final class PublicationProcesses {

    public static final String SAVE = "QB_PUBLICATION_SAVE";
    public static final String DELETE = "QB_PUBLICATION_DELETE";
    public static final String COPY = "QB_PUBLICATION_COPY";

    /** A tier: an existing one by {@code tierId}, a new one without. Its place is its place in the list. */
    public record TierInput(UUID tierId, @NotNull Integer fromScore, @NotNull Integer toScore,
        @NotNull BigDecimal reward) {}

    /**
     * A new draft (no {@code publicationId}) or a draft's version and policy. Every field is written as given: an
     * empty one clears it, except the time limit, which is the version's when not given. {@code tiers} is the whole
     * list (tiers left out are deleted); without it (null) the tiers stay as they are.
     *
     * @param regions      region codes ({@code country.Regions}); none until chosen
     * @param baseRevision the revision the caller edited; when given and no longer current, 422
     */
    public record SaveInput(UUID publicationId, UUID versionId, @NotNull String name, List<@NotNull String> regions,
        Instant startAt, Instant endAt, Integer timeLimitSec, Integer passScore, BigDecimal budgetCap,
        Integer maxWinners, @Size(max = PublishingLimits.MAX_TIERS) List<@Valid @NotNull TierInput> tiers,
        Long baseRevision) {}

    public record PublicationRef(@NotNull UUID publicationId, Long baseRevision) {}

    /**
     * @param versionId another version for the copy; the source's when not given
     * @param name      the copy's name; the source's with "(copy)" in the caller's language when not given
     */
    public record CopyInput(@NotNull UUID publicationId, UUID versionId, Instant startAt, Instant endAt,
        String name) {}

    /** The message of a copy's default name, with the parameter {@code name}. */
    public static final String COPY_NAME = "qb.publication.copy-name";

    // ---- save ------------------------------------------------------------------------------------------------------

    public static ProcessDefinition<SaveInput, SaveOutput, ProcessContext> save(MessageCatalog messages) {
        return ProcessDefinition.define(SAVE, 1, SaveInput.class, SaveOutput.class, ProcessContext.class, pb -> pb
            .description("Creates a draft publication or changes its version, policy or reward tiers.")
            .permissions(QbPermissions.PUBLICATION_WRITE)
            .requiresVerifiedEmail()
            .steps(CallerChecks::refuseBanned)
            .actsOn(QbPublications.PUBLICATION, "publicationId", a -> a.whenField("status", PublicationStatus.DRAFT))
            .contextFactory((start, input) -> PublicationEditing.start(start, input, input.publicationId()))
            .outputMapper(ctx -> ctx.get(OUTPUT, SaveOutput.class))
            .step("Lock the publication", HoldLock.<ProcessContext>exclusive(
                ctx -> PublicationEditing.lockName(ctx.get(PUB_ID))))
            // A new publication has no key: the query then finds nothing, and nothing is expected.
            .step("Load the publication", QueryEntities.<ProcessContext>of(
                QbPublications.sponsorDataset(QbPublications.PUBLICATION), ctx -> EntityQuery.builder()
                    .where(new QueryPredicate.In("publicationId", PublicationEditing.ids(ctx.get(PUB_ID))))
                    .limit(1).build(), FOUND))
            .steps(PublicationEditing::loadTiers)
            .steps(PublicationEditing::loadSponsor)
            .steps(b -> PublicationEditing.loadVersion(b, ctx -> {
                UUID given = ctx.get(INPUT, SaveInput.class).versionId();
                EntityInstance found = first(ctx, FOUND);
                return given != null || found == null ? given : found.get("versionId");
            }, true))
            .compute("Save the publication", (metadata, ctx) -> save(ctx, messages)));
    }

    static void save(ProcessContext ctx, MessageCatalog messages) {
        SaveInput input = ctx.get(INPUT, SaveInput.class);
        EntityInstance publication = first(ctx, FOUND);
        if (input.publicationId() != null && publication == null) {
            throw new EntityNotFoundException(QbPublications.PUBLICATION + " " + input.publicationId() + " not found");
        }
        if (publication != null && (!PublicationEditing.draft(ctx, publication)
            || !PublicationEditing.current(ctx, publication, input.baseRevision()))) {
            return;
        }
        Object versionId = input.versionId() != null || publication == null ? input.versionId()
            : publication.get("versionId");
        EntityInstance version = versionId == null ? null : PublicationEditing.requireVersion(ctx, versionId);
        String regions = regions(ctx, input.regions());
        List<TierPlan> tiers = tierPlan(ctx, input.tiers(), PublicationEditing.ordered(list(ctx, TIERS)));
        if (ctx.hasViolations()) {
            return;
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("name", input.name());
        values.put("regions", regions);
        values.put("startAt", input.startAt());
        values.put("endAt", input.endAt());
        Integer limit = input.timeLimitSec() == null && version != null
            ? PublicationEditing.integer(version.get("timeLimitSec")) : input.timeLimitSec();
        values.put("timeLimitSec", decimal(limit));
        values.put("passScore", decimal(input.passScore()));
        values.put("budgetCap", input.budgetCap());
        values.put("maxWinners", decimal(input.maxWinners()));
        putVersion(values, version);
        putDerived(values, tiers);
        UUID publicationId;
        long revision;
        Instant editedAt;
        List<UUID> tierIds = new ArrayList<>();
        if (publication == null) {
            values.put("sponsorId", ctx.request().actorId());
            values.put("status", PublicationStatus.DRAFT);
            values.put("revision", BigDecimal.ONE);
            values.put("editedAt", ctx.opTime());
            publicationId = uuid(ctx.changes().insert(QbPublications.PUBLICATION, values));
            writeTiers(ctx, publicationId, ctx.request().actorId(), List.of(), tiers, tierIds);
            revision = 1;
            editedAt = ctx.opTime();
        } else {
            publicationId = uuid(publication.id());
            boolean tiersChanged = writeTiers(ctx, publicationId, publication.get("sponsorId"),
                list(ctx, TIERS), tiers, tierIds);
            Map<String, Object> changed = PublicationEditing.differences(publication, values);
            if (changed.isEmpty() && !tiersChanged) {
                revision = number(publication.get("revision"));
                editedAt = PublicationEditing.instant(publication.get("editedAt"));
            } else {
                revision = number(publication.get("revision")) + 1;
                editedAt = ctx.opTime();
                changed.put("revision", BigDecimal.valueOf(revision));
                changed.put("editedAt", editedAt);
                ctx.changes().update(QbPublications.PUBLICATION, publication.id(), publication.version(), changed);
            }
        }
        ctx.put(OUTPUT, output(ctx, messages, publicationId, revision, editedAt, values, tiers, version, tierIds));
    }

    /** The regions in their written form; null when none are chosen yet. */
    private static String regions(ProcessContext ctx, List<String> given) {
        if (given == null || given.isEmpty()) {
            return null;
        }
        try {
            return Regions.targets(given);
        } catch (IllegalArgumentException e) {
            ctx.reject(new Violation("regions", PublishingCodes.REGIONS_FORMAT, e.getMessage(),
                Map.of("regions", String.join(",", given))));
            return null;
        }
    }

    /** The version's part of a publication: its quiz, label and full score (none without a version). */
    private static void putVersion(Map<String, Object> values, EntityInstance version) {
        values.put("versionId", version == null ? null : version.id());
        values.put("quizId", version == null ? null : version.get("quizId"));
        values.put("versionLabel", version == null ? null : version.get("label"));
        values.put("fullScore", version == null ? null : version.get("fullScore"));
    }

    /** The values derived from the tiers and the money policy (D-Q4-3). */
    private static void putDerived(Map<String, Object> values, List<TierPlan> tiers) {
        BigDecimal maxReward = TierCoverage.maxReward(tiers.stream().map(TierPlan::tier).toList());
        values.put("maxReward", maxReward);
        values.put("maxSpend", TierCoverage.maxSpend((BigDecimal) values.get("budgetCap"),
            PublicationEditing.integer(values.get("maxWinners")), maxReward));
        values.put("tierCount", BigDecimal.valueOf(tiers.size()));
    }

    /** A tier as it will be: the existing row it keeps (null for a new one) and its values. */
    private record TierPlan(EntityInstance existing, TierCoverage.Tier tier) {}

    /**
     * The tiers as they will be: the input's when given (each {@code tierId} must be one of the publication's, once:
     * 422 {@code QB_TIER_NOT_IN_PUBLICATION} otherwise), the existing ones when not.
     */
    private static List<TierPlan> tierPlan(ProcessContext ctx, List<TierInput> given, List<EntityInstance> existing) {
        if (given == null) {
            return existing.stream().map(row -> new TierPlan(row, PublicationEditing.tiersOf(List.of(row))
                .getFirst())).toList();
        }
        Map<String, EntityInstance> byId = new LinkedHashMap<>();
        existing.forEach(row -> byId.put(String.valueOf(row.id()), row));
        Set<String> seen = new HashSet<>();
        List<TierPlan> plan = new ArrayList<>();
        for (int i = 0; i < given.size(); i++) {
            TierInput tier = given.get(i);
            EntityInstance row = null;
            if (tier.tierId() != null) {
                row = byId.get(tier.tierId().toString());
                if (row == null || !seen.add(tier.tierId().toString())) {
                    ctx.reject(new Violation("tiers[" + i + "].tierId", PublishingCodes.TIER_NOT_IN_PUBLICATION,
                        "There is no tier " + tier.tierId() + " in this publication",
                        Map.of("tierId", tier.tierId().toString())));
                    continue;
                }
            }
            plan.add(new TierPlan(row, new TierCoverage.Tier(tier.fromScore(), tier.toScore(), tier.reward())));
        }
        return plan;
    }

    /**
     * Writes the tiers as planned, in their order: existing ones left out are deleted, changed ones updated, new ones
     * inserted. Returns whether anything changed; {@code ids} receives the tiers' keys in their order.
     */
    private static boolean writeTiers(ProcessContext ctx, UUID publicationId, Object sponsorId,
        List<EntityInstance> existing, List<TierPlan> plan, List<UUID> ids) {
        boolean changed = false;
        Set<String> kept = new HashSet<>();
        plan.stream().map(TierPlan::existing).filter(Objects::nonNull).forEach(row -> kept.add(String.valueOf(row.id())));
        for (EntityInstance row : existing) {
            if (!kept.contains(String.valueOf(row.id()))) {
                ctx.changes().delete(QbPublications.TIER, row.id(), row.version());
                changed = true;
            }
        }
        for (int i = 0; i < plan.size(); i++) {
            TierPlan tier = plan.get(i);
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("seq", BigDecimal.valueOf(i + 1L));
            values.put("fromScore", BigDecimal.valueOf(tier.tier().fromScore()));
            values.put("toScore", BigDecimal.valueOf(tier.tier().toScore()));
            values.put("reward", tier.tier().reward());
            if (tier.existing() == null) {
                values.put("publicationId", publicationId);
                values.put("sponsorId", sponsorId);
                ids.add(uuid(ctx.changes().insert(QbPublications.TIER, values)));
                changed = true;
            } else {
                Map<String, Object> differences = PublicationEditing.differences(tier.existing(), values);
                if (!differences.isEmpty()) {
                    ctx.changes().update(QbPublications.TIER, tier.existing().id(), tier.existing().version(),
                        differences);
                    changed = true;
                }
                ids.add(uuid(tier.existing().id()));
            }
        }
        return changed;
    }

    private static SaveOutput output(ProcessContext ctx, MessageCatalog messages, UUID publicationId, long revision,
        Instant editedAt, Map<String, Object> values, List<TierPlan> tiers, EntityInstance version,
        List<UUID> tierIds) {
        List<TierCoverage.Tier> ranges = tiers.stream().map(TierPlan::tier).toList();
        List<Violation> problems = new ArrayList<>(PublicationReadiness.check(PublicationEditing.draftOf(values),
            ranges, PublicationEditing.snapshot(version), ctx.opTime()));
        // What submitting would also say: the caller is no approved sponsor, or is banned (D-Q2-6).
        problems.addAll(PublicationEditing.sponsorProblems(ctx));
        return new SaveOutput(publicationId, PublicationStatus.DRAFT, revision, editedAt,
            PublicationEditing.integer(values.get("fullScore")), (BigDecimal) values.get("maxReward"),
            (BigDecimal) values.get("maxSpend"), tierIds, PublicationEditing.problems(problems, messages, ctx));
    }

    // ---- delete ----------------------------------------------------------------------------------------------------

    public static ProcessDefinition<PublicationRef, PublicationOutput, ProcessContext> delete() {
        return ProcessDefinition.define(DELETE, 1, PublicationRef.class, PublicationOutput.class,
            ProcessContext.class, pb -> pb
                .description("Deletes a draft publication with its reward tiers.")
                .permissions(QbPermissions.PUBLICATION_WRITE)
                .requiresVerifiedEmail()
                .steps(CallerChecks::refuseBanned)
                .actsOn(QbPublications.PUBLICATION, "publicationId",
                    a -> a.whenField("status", PublicationStatus.DRAFT))
                .contextFactory((start, input) -> PublicationEditing.start(start, input, input.publicationId()))
                .outputMapper(ctx -> ctx.get(OUTPUT, PublicationOutput.class))
                .steps(PublicationEditing::lockAndLoad)
                .steps(PublicationEditing::loadTiers)
                .compute("Delete the publication", (metadata, ctx) -> {
                    EntityInstance publication = PublicationEditing.publication(ctx);
                    if (!PublicationEditing.draft(ctx, publication) || !PublicationEditing.current(ctx,
                        publication, ctx.get(INPUT, PublicationRef.class).baseRevision())) {
                        return;
                    }
                    // Tiers before what they belong to: an instance still referred to cannot be deleted.
                    list(ctx, TIERS).forEach(tier -> ctx.changes().delete(QbPublications.TIER, tier.id(),
                        tier.version()));
                    ctx.changes().delete(QbPublications.PUBLICATION, publication.id(), publication.version());
                    ctx.put(OUTPUT, new PublicationOutput(uuid(publication.id()), PublicationStatus.DRAFT,
                        number(publication.get("revision")), null, null));
                }));
    }

    // ---- copy ------------------------------------------------------------------------------------------------------

    public static ProcessDefinition<CopyInput, SaveOutput, ProcessContext> copy(MessageCatalog messages) {
        return ProcessDefinition.define(COPY, 1, CopyInput.class, SaveOutput.class, ProcessContext.class, pb -> pb
            .description("Copies a publication's policy and reward tiers into a new draft, with another version or "
                + "other times if given.")
            .permissions(QbPermissions.PUBLICATION_WRITE)
            .requiresVerifiedEmail()
            .steps(CallerChecks::refuseBanned)
            .actsOn(QbPublications.PUBLICATION, "publicationId")
            .contextFactory((start, input) -> PublicationEditing.start(start, input, input.publicationId()))
            .outputMapper(ctx -> ctx.get(OUTPUT, SaveOutput.class))
            .step("Load the publication", LoadEntity.by(QbPublications.sponsorDataset(QbPublications.PUBLICATION),
                PUB_ID, PUBLICATION))
            .steps(PublicationEditing::loadTiers)
            .steps(PublicationEditing::loadSponsor)
            .steps(b -> PublicationEditing.loadVersion(b, ctx -> {
                UUID given = ctx.get(INPUT, CopyInput.class).versionId();
                return given != null ? given : PublicationEditing.publication(ctx).get("versionId");
            }, true))
            .compute("Copy the publication", (metadata, ctx) -> copy(ctx, messages)));
    }

    static void copy(ProcessContext ctx, MessageCatalog messages) {
        CopyInput input = ctx.get(INPUT, CopyInput.class);
        EntityInstance source = PublicationEditing.publication(ctx);
        if (PublicationAdmin.takenDown(source)) {
            // Review M-d: what an administrator took down is not put up again as a copy; a new publication of the
            // quiz goes to a person's review (PublicationLifecycle.submit).
            ctx.reject(new Violation("publicationId", PublishingCodes.TAKEN_DOWN, "An administrator took this "
                + "publication down: it cannot be copied", Map.of()));
            return;
        }
        Object versionId = input.versionId() != null ? input.versionId() : source.get("versionId");
        EntityInstance version = versionId == null ? null : PublicationEditing.requireVersion(ctx, versionId);
        if (ctx.hasViolations()) {
            return;
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("name", input.name() == null || input.name().isBlank() ? copyName(messages, ctx,
            source.get("name")) : input.name());
        for (String field : List.of("regions", "startAt", "endAt", "timeLimitSec", "passScore", "budgetCap",
            "maxWinners")) {
            values.put(field, source.get(field));
        }
        if (input.startAt() != null) {
            values.put("startAt", input.startAt());
        }
        if (input.endAt() != null) {
            values.put("endAt", input.endAt());
        }
        putVersion(values, version);
        List<TierPlan> tiers = PublicationEditing.ordered(list(ctx, TIERS)).stream()
            .map(row -> new TierPlan(null, PublicationEditing.tiersOf(List.of(row)).getFirst())).toList();
        putDerived(values, tiers);
        values.put("sponsorId", ctx.request().actorId());
        values.put("status", PublicationStatus.DRAFT);
        values.put("revision", BigDecimal.ONE);
        values.put("editedAt", ctx.opTime());
        values.put("copiedFrom", String.valueOf(source.id()));
        UUID publicationId = uuid(ctx.changes().insert(QbPublications.PUBLICATION, values));
        List<UUID> tierIds = new ArrayList<>();
        writeTiers(ctx, publicationId, ctx.request().actorId(), List.of(), tiers, tierIds);
        ctx.put(OUTPUT, output(ctx, messages, publicationId, 1, ctx.opTime(), values, tiers, version, tierIds));
    }

    /** "Name (copy)" in the caller's language, the source's name shortened to fit the name's length. */
    static String copyName(MessageCatalog messages, ProcessContext ctx, String source) {
        return copyName(messages.find(COPY_NAME, ctx.request().locale()).orElse("{name} (copy)"), source);
    }

    /**
     * {@code template} with the source's name, shortened by whole characters (code points, as the name's length is
     * counted) so that the result fits; a character is never cut in half.
     */
    static String copyName(String template, String source) {
        String suffix = MessageTemplate.format(template, Map.of("name", ""));
        int room = Math.max(PublishingLimits.NAME_LENGTH - suffix.codePointCount(0, suffix.length()), 0);
        String name = source;
        if (source.codePointCount(0, source.length()) > room) {
            name = source.substring(0, source.offsetByCodePoints(0, room)).strip();
        }
        return MessageTemplate.format(template, Map.of("name", name));
    }

    private PublicationProcesses() {}
}
