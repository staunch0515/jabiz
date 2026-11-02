package com.jabiz.quizbuks.publishing;

import com.jabiz.entity.Violation;
import com.jabiz.i18n.MessageCatalog;
import com.jabiz.i18n.MessageTemplate;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.content.QuizEditing;
import com.jabiz.quizbuks.identity.CallerChecks;
import com.jabiz.quizbuks.country.Regions;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.EntityNotFoundException;
import com.jabiz.runtime.process.steps.HoldLock;
import com.jabiz.runtime.security.secret.SealSecret;
import com.jabiz.security.Sensitive;
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
     * "Referrals welcome" (Q70, D-Q70-10, {@link ReferralPolicy}); the save writes it whole, none (null) for not welcome.
     *
     * @param days how long a referee has to pass after claiming; {@value ReferralPolicy#DEFAULT_DAYS} when not given
     */
    public record ReferralInput(Integer quota, @Size(max = 20) String method, BigDecimal amount,
        @Size(max = PublishingLimits.REDEEM_DESCRIPTION_LENGTH) String description,
        @Size(max = 10) String referrerRequires, @Size(max = 10) List<@NotNull @Size(max = 40) String> refereeRules,
        Integer days) {

        ReferralPolicy policy() {
            return new ReferralPolicy(quota == null ? 0 : quota, method == null || method.isBlank() ? null : method,
                amount, description == null || description.isBlank() ? null : description.strip(), referrerRequires,
                refereeRules, days == null ? ReferralPolicy.DEFAULT_DAYS : days);
        }
    }

    /**
     * A new draft (no {@code publicationId}) or a draft's version and policy. Every field is written as given: an
     * empty one clears it, except the time limit, which is the version's when not given. {@code tiers} is the whole
     * list (tiers left out are deleted); without it (null) the tiers stay as they are.
     *
     * @param regions           region codes ({@code country.Regions}); none until chosen
     * @param rewardMethod      how the reward reaches the taker (Q22, {@link RewardMethod}); {@code PLATFORM} when not
     *                          given; one the sponsor may use, or 422 {@code QB_REWARD_METHOD_NOT_ALLOWED}
     * @param redeemDescription for {@code OFFLINE_REDEEM}, what the prize is and where to collect it
     * @param baseRevision      the revision the caller edited; when given and no longer current, 422
     * @param audience          who may answer (Q25, {@link Audience}): {@code PUBLIC} when not given; {@code INVITED}
     *                          needs no regions
     * @param cover             the publication's own cover (Q64), a file of the policy {@code qb.content.image}
     *                          uploaded by the sponsor; none shows the version's cover
     */
    public record SaveInput(UUID publicationId, UUID versionId, @NotNull String name, List<@NotNull String> regions,
        Instant startAt, Instant endAt, Integer timeLimitSec, Integer passScore, BigDecimal budgetCap,
        Integer maxWinners, @Size(max = PublishingLimits.MAX_TIERS) List<@Valid @NotNull TierInput> tiers,
        Long baseRevision, @Size(max = 20) String rewardMethod,
        @Size(max = PublishingLimits.REDEEM_DESCRIPTION_LENGTH) String redeemDescription,
        @Size(max = 10) String audience, @Size(max = 20) String digitalDelivery,
        @Size(max = PublishingLimits.REDEEM_DESCRIPTION_LENGTH) String digitalDescription, Integer digitalSendDays,
        @Sensitive @Size(max = PublishingLimits.DIGITAL_CONTENT_LENGTH) String digitalContent, UUID cover,
        @Valid ReferralInput referral, @Size(max = 3) List<@NotNull @Size(max = 2) String> languages,
        @Size(max = 3) Map<@NotNull @Size(max = 2) String, @Size(max = 4) Map<@NotNull @Size(max = 30) String,
            @Size(max = PublishingLimits.REDEEM_DESCRIPTION_LENGTH + 1) String>> translations,
        @Size(max = 12) List<@NotNull @Size(max = 40) String> confirmed) {

        /**
         * As before the confirmation of translations (Q73c): {@code confirmed} lists the translations still right for a
         * changed base text ({@code <language>.<field>}), which then take the present base text's hash.
         */
        public SaveInput(UUID publicationId, UUID versionId, String name, List<String> regions, Instant startAt,
            Instant endAt, Integer timeLimitSec, Integer passScore, BigDecimal budgetCap, Integer maxWinners,
            List<TierInput> tiers, Long baseRevision, String rewardMethod, String redeemDescription,
            String audience, String digitalDelivery, String digitalDescription, Integer digitalSendDays,
            String digitalContent, UUID cover, ReferralInput referral, List<String> languages,
            Map<String, Map<String, String>> translations) {
            this(publicationId, versionId, name, regions, startAt, endAt, timeLimitSec, passScore, budgetCap,
                maxWinners, tiers, baseRevision, rewardMethod, redeemDescription, audience, digitalDelivery,
                digitalDescription, digitalSendDays, digitalContent, cover, referral, languages, translations, null);
        }

        /**
         * As before Q73b: the version's base language only.
         *
         * <p>Q73b: {@code languages} are the languages offered, some of the version's, the main one first (none: the
         * version's base only); {@code translations} the publication's texts ({@link PublicationLanguages#FIELDS}) in
         * each offered language other than the base, the whole set (as the save writes the whole draft).
         */
        public SaveInput(UUID publicationId, UUID versionId, String name, List<String> regions, Instant startAt,
            Instant endAt, Integer timeLimitSec, Integer passScore, BigDecimal budgetCap, Integer maxWinners,
            List<TierInput> tiers, Long baseRevision, String rewardMethod, String redeemDescription,
            String audience, String digitalDelivery, String digitalDescription, Integer digitalSendDays,
            String digitalContent, UUID cover, ReferralInput referral) {
            this(publicationId, versionId, name, regions, startAt, endAt, timeLimitSec, passScore, budgetCap,
                maxWinners, tiers, baseRevision, rewardMethod, redeemDescription, audience, digitalDelivery,
                digitalDescription, digitalSendDays, digitalContent, cover, referral, null, null, null);
        }

        /** As before Q70: no referrals. */
        public SaveInput(UUID publicationId, UUID versionId, String name, List<String> regions, Instant startAt,
            Instant endAt, Integer timeLimitSec, Integer passScore, BigDecimal budgetCap, Integer maxWinners,
            List<TierInput> tiers, Long baseRevision, String rewardMethod, String redeemDescription,
            String audience, String digitalDelivery, String digitalDescription, Integer digitalSendDays,
            String digitalContent, UUID cover) {
            this(publicationId, versionId, name, regions, startAt, endAt, timeLimitSec, passScore, budgetCap,
                maxWinners, tiers, baseRevision, rewardMethod, redeemDescription, audience, digitalDelivery,
                digitalDescription, digitalSendDays, digitalContent, cover, null);
        }

        /** As before Q25: everybody in the regions. */
        public SaveInput(UUID publicationId, UUID versionId, String name, List<String> regions, Instant startAt,
            Instant endAt, Integer timeLimitSec, Integer passScore, BigDecimal budgetCap, Integer maxWinners,
            List<TierInput> tiers, Long baseRevision, String rewardMethod, String redeemDescription) {
            this(publicationId, versionId, name, regions, startAt, endAt, timeLimitSec, passScore, budgetCap,
                maxWinners, tiers, baseRevision, rewardMethod, redeemDescription, null, null, null, null, null, null);
        }

        /** As before Q33: no digital prize. */
        public SaveInput(UUID publicationId, UUID versionId, String name, List<String> regions, Instant startAt,
            Instant endAt, Integer timeLimitSec, Integer passScore, BigDecimal budgetCap, Integer maxWinners,
            List<TierInput> tiers, Long baseRevision, String rewardMethod, String redeemDescription,
            String audience) {
            this(publicationId, versionId, name, regions, startAt, endAt, timeLimitSec, passScore, budgetCap,
                maxWinners, tiers, baseRevision, rewardMethod, redeemDescription, audience, null, null, null, null,
                null);
        }

        /** As before Q64: no cover of its own. */
        public SaveInput(UUID publicationId, UUID versionId, String name, List<String> regions, Instant startAt,
            Instant endAt, Integer timeLimitSec, Integer passScore, BigDecimal budgetCap, Integer maxWinners,
            List<TierInput> tiers, Long baseRevision, String rewardMethod, String redeemDescription,
            String audience, String digitalDelivery, String digitalDescription, Integer digitalSendDays,
            String digitalContent) {
            this(publicationId, versionId, name, regions, startAt, endAt, timeLimitSec, passScore, budgetCap,
                maxWinners, tiers, baseRevision, rewardMethod, redeemDescription, audience, digitalDelivery,
                digitalDescription, digitalSendDays, digitalContent, null);
        }

        /** The shared code or link, only ever shown masked. */
        @Override
        public String toString() {
            return "SaveInput[publicationId=" + publicationId + ", versionId=" + versionId + ", name=" + name
                + ", rewardMethod=" + rewardMethod + ", digitalDelivery=" + digitalDelivery + ", cover=" + cover
                + ", digitalContent=" + (digitalContent == null ? "null" : "***") + "]";
        }
    }

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
            // Q47: an invited publication takes its list; switching to it clears the matching conditions.
            .steps(PublicationAudience::loadCriteria)
            .steps(PublicationEditing::loadSponsor)
            .steps(PublicationEditing::loadSponsorMethods)
            // Q64: who uploaded the cover given, which must be the sponsor's own file.
            .steps(b -> QuizEditing.loadFiles(b, ctx -> {
                UUID cover = ctx.get(INPUT, SaveInput.class).cover();
                return cover == null ? List.of() : List.of(cover);
            }))
            .steps(b -> PublicationEditing.loadVersion(b, ctx -> {
                UUID given = ctx.get(INPUT, SaveInput.class).versionId();
                EntityInstance found = first(ctx, FOUND);
                return given != null || found == null ? given : found.get("versionId");
            }, true))
            // Q33: a shared digital prize's code or link, sealed for this sponsor before anything is written (the
            // step draws random bytes, so it is a blocking one); blank keeps the stored one.
            .step("Seal the shared prize", SealSecret.<ProcessContext>of(PublicationProcesses::sharedContent,
                ctx -> String.valueOf((Object) ctx.request().actorId()), SEALED_DIGITAL))
            .compute("Save the publication", (metadata, ctx) -> save(ctx, messages)));
    }

    /** The context key of the shared content sealed, when the save gives one. */
    static final String SEALED_DIGITAL = "sealedDigital";

    static String sharedContent(ProcessContext ctx) {
        SaveInput input = ctx.get(INPUT, SaveInput.class);
        return RewardMethod.DIGITAL_PRIZE.equals(input.rewardMethod())
            && DigitalDelivery.SHARED.equals(DigitalDelivery.of(input.digitalDelivery())) ? input.digitalContent()
            : null;
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
        String method = input.rewardMethod() == null || input.rewardMethod().isBlank() ? RewardMethod.PLATFORM
            : input.rewardMethod();
        PublicationEditing.methodProblems(ctx, method).forEach(ctx::reject);
        // Q70: the referral reward's method is one the sponsor may use too (the rest is a problem of the draft).
        ReferralPolicy referral = input.referral() == null ? null : input.referral().policy();
        if (referral != null && referral.method() != null && RewardMethod.ALL.contains(referral.method())) {
            PublicationEditing.methodProblems(ctx, referral.method()).forEach(v -> ctx.reject(new Violation(
                "referral.method", v.ruleCode(), v.message(), v.params())));
        }
        String audience = Audience.of(input.audience());
        if (!Audience.ALL.contains(audience)) {
            ctx.reject(new Violation("audience", PublishingCodes.AUDIENCE_INVALID, "Unknown audience " + audience,
                Map.of("audience", audience)));
        }
        // Q73b: languages of the platform's, once each; texts within their fields.
        PublicationLanguages.refusals(input.languages(), input.translations()).forEach(ctx::reject);
        // Q64: the cover is a file the sponsor uploaded (one kept from before stays, whoever reads it now).
        QuizEditing.requireOwnFile(ctx, publication == null ? ctx.request().actorId()
            : publication.get("publisherId"), "cover", input.cover(), publication == null ? null
            : publication.get("cover"));
        if (ctx.hasViolations()) {
            return;
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("name", input.name());
        values.put("cover", input.cover());
        values.put("regions", regions);
        values.put("audience", audience);
        values.put("rewardMethod", method);
        // Only a prize at the sponsor's place has a description; blank is none.
        values.put("redeemDescription", !RewardMethod.OFFLINE_REDEEM.equals(method)
            || input.redeemDescription() == null || input.redeemDescription().isBlank() ? null
            : input.redeemDescription().strip());
        // Q33: a digital prize's delivery and description; the shared content sealed, kept when the save leaves it
        // blank; nothing of them for the other methods.
        boolean digital = RewardMethod.DIGITAL_PRIZE.equals(method);
        boolean deliveryGiven = input.digitalDelivery() != null && !input.digitalDelivery().isBlank();
        String delivery = digital && deliveryGiven ? DigitalDelivery.of(input.digitalDelivery()) : null;
        if (digital && deliveryGiven && delivery == null) {
            // A name nobody knows is refused; none at all is a problem of the draft (PublicationReadiness).
            ctx.reject(new Violation("digitalDelivery", PublishingCodes.DIGITAL_DELIVERY_INVALID, "A digital prize "
                + "is delivered from a pool, shared, or sent by the sponsor", Map.of("digitalDelivery",
                    String.valueOf(input.digitalDelivery()))));
            return;
        }
        values.put("digitalDelivery", delivery);
        values.put("digitalDescription", !digital || input.digitalDescription() == null
            || input.digitalDescription().isBlank() ? null : input.digitalDescription().strip());
        values.put("digitalSendDays", digital && DigitalDelivery.SPONSOR_SENT.equals(delivery)
            ? decimal(input.digitalSendDays()) : null);
        String sealed = ctx.contains(SEALED_DIGITAL) ? ctx.get(SEALED_DIGITAL, String.class) : null;
        values.put("sealedDigitalContent", !digital || !DigitalDelivery.SHARED.equals(delivery) ? null
            : sealed != null ? sealed : publication == null ? null : publication.get("sealedDigitalContent"));
        values.put("referral", ReferralPolicy.toJson(referral));
        // Q73b: the languages offered and the texts in the other ones, each keeping the base hash it was made from
        // while it is unchanged (a changed base text leaves its translations out of date).
        String base = version == null ? null : String.valueOf((Object) version.get("language"));
        List<String> offered = input.languages() == null || input.languages().isEmpty() ? List.of()
            : input.languages().stream().distinct().toList();
        values.put("languages", com.jabiz.quizbuks.content.ContentLanguages.joinList(offered));
        values.put("translations", PublicationLanguages.toJson(PublicationLanguages.merge(input.translations(),
            PublicationLanguages.parse(publication == null ? null : publication.get("translations")),
            PublicationLanguages.sources((String) values.get("name"), (String) values.get("redeemDescription"),
                (String) values.get("digitalDescription"), referral),
            offered.isEmpty() && base != null ? List.of(base) : offered, base, input.confirmed())));
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
            // The sponsors' console publishes as a sponsor (S0): the publisher is the caller.
            values.put("publisherKind", PublisherKind.SPONSOR);
            values.put("publisherId", ctx.request().actorId());
            values.put("status", PublicationStatus.DRAFT);
            values.put("revision", BigDecimal.ONE);
            values.put("editedAt", ctx.opTime());
            publicationId = uuid(ctx.changes().insert(QbPublications.PUBLICATION, values));
            writeTiers(ctx, publicationId, PublisherKind.SPONSOR, ctx.request().actorId(), List.of(), tiers, tierIds);
            revision = 1;
            editedAt = ctx.opTime();
        } else {
            publicationId = uuid(publication.id());
            boolean tiersChanged = writeTiers(ctx, publicationId, publication.get("publisherKind"),
                publication.get("publisherId"), list(ctx, TIERS), tiers, tierIds);
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
        if (Audience.INVITED.equals(audience)) {
            list(ctx, PublicationAudience.CRITERIA).forEach(row ->
                ctx.changes().delete(com.jabiz.quizbuks.audience.QbAudience.CRITERION, row.id(), row.version()));
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
        BigDecimal spend = TierCoverage.maxSpend((BigDecimal) values.get("budgetCap"),
            PublicationEditing.integer(values.get("maxWinners")), maxReward);
        // Q70 (D-Q70-13): plus the referral rewards at most, quota times amount (not counted in the budget).
        ReferralPolicy referral = ReferralPolicy.fromJson(values.get("referral"));
        values.put("maxSpend", spend == null || referral == null ? spend
            : spend.add(referral.maxSpend()).min(PublishingLimits.MAX_MONEY));
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
    private static boolean writeTiers(ProcessContext ctx, UUID publicationId, Object publisherKind, Object publisherId,
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
                values.put("publisherKind", publisherKind);
                values.put("publisherId", publisherId);
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
            ranges, PublicationEditing.snapshot(version), ctx.opTime(), PublicationEditing.maxRewardPerAttempt(ctx)));
        // What submitting would also say: the caller is no approved sponsor, or is banned (D-Q2-6); the method is
        // one they may not use (Q22: a copy of a publication made before the method was taken away).
        problems.addAll(PublicationEditing.sponsorProblems(ctx));
        problems.addAll(PublicationEditing.methodProblems(ctx, PublicationEditing.methodOf(values)));
        problems.addAll(PublicationEditing.languageProblems(values, version));
        ReferralPolicy referral = ReferralPolicy.fromJson(values.get("referral"));
        if (referral != null && RewardMethod.ALL.contains(referral.method())) {
            PublicationEditing.methodProblems(ctx, referral.method()).forEach(v -> problems.add(
                new Violation("referral.method", v.ruleCode(), v.message(), v.params())));
        }
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
                .steps(PublicationAudience::loadCriteria)
                .steps(PublicationPrerequisites::loadForDeletion)
                .compute("Delete the publication", (metadata, ctx) -> {
                    EntityInstance publication = PublicationEditing.publication(ctx);
                    if (!PublicationEditing.draft(ctx, publication) || !PublicationEditing.current(ctx,
                        publication, ctx.get(INPUT, PublicationRef.class).baseRevision())) {
                        return;
                    }
                    // Q76: its prerequisites first; a draft that another publication requires (one that went back
                    // to draft after that one was submitted) stays.
                    if (!PublicationPrerequisites.delete(ctx)) {
                        return;
                    }
                    // Tiers before what they belong to: an instance still referred to cannot be deleted.
                    list(ctx, TIERS).forEach(tier -> ctx.changes().delete(QbPublications.TIER, tier.id(),
                        tier.version()));
                    // Q47: the conditions too, for the same reason.
                    list(ctx, PublicationAudience.CRITERIA).forEach(row -> ctx.changes().delete(
                        com.jabiz.quizbuks.audience.QbAudience.CRITERION, row.id(), row.version()));
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
            .steps(PublicationAudience::loadCriteria)
            // Q76: the prerequisites travel with the copy too.
            .steps(PublicationPrerequisites::load)
            .steps(PublicationEditing::loadSponsor)
            .steps(PublicationEditing::loadSponsorMethods)
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
        // Q64: the cover too, the copier's own file as the source's was (both are the caller's publications).
        for (String field : List.of("cover", "regions", "startAt", "endAt", "timeLimitSec", "passScore", "budgetCap",
            "maxWinners", "redeemDescription", "digitalDelivery", "digitalDescription", "digitalSendDays",
            "sealedDigitalContent", "referral", "languages", "translations")) {
            values.put(field, source.get(field));
        }
        values.put("rewardMethod", PublicationEditing.methodOf(source.attributes()));
        // Q25: the audience is copied, the invitation list is not (a copy invites its own people).
        values.put("audience", Audience.of(source.get("audience")));
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
        values.put("publisherKind", PublisherKind.SPONSOR);
        values.put("publisherId", ctx.request().actorId());
        values.put("status", PublicationStatus.DRAFT);
        values.put("revision", BigDecimal.ONE);
        values.put("editedAt", ctx.opTime());
        values.put("copiedFrom", String.valueOf(source.id()));
        UUID publicationId = uuid(ctx.changes().insert(QbPublications.PUBLICATION, values));
        List<UUID> tierIds = new ArrayList<>();
        writeTiers(ctx, publicationId, PublisherKind.SPONSOR, ctx.request().actorId(), List.of(), tiers, tierIds);
        // Q47: the conditions travel with the copy, as its policy does (an invited one has none).
        (Audience.INVITED.equals(source.get("audience")) ? List.<EntityInstance>of()
            : list(ctx, PublicationAudience.CRITERIA)).forEach(row -> {
            Map<String, Object> condition = new LinkedHashMap<>(PublicationAudience.copyOf(row));
            condition.put("publicationId", publicationId);
            condition.put("publisherKind", PublisherKind.SPONSOR);
            condition.put("publisherId", ctx.request().actorId());
            ctx.changes().insert(com.jabiz.quizbuks.audience.QbAudience.CRITERION, condition);
        });
        PublicationPrerequisites.copy(ctx, publicationId, PublisherKind.SPONSOR, ctx.request().actorId());
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
