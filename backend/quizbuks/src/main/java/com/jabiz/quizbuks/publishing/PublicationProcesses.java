package com.jabiz.quizbuks.publishing;

import com.jabiz.entity.Violation;
import com.jabiz.i18n.MessageCatalog;
import com.jabiz.i18n.MessageTemplate;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.asset.AssetUses;
import com.jabiz.quizbuks.asset.QbAssets;
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
     * @param covers            Q98b: its own cover in languages it offers besides its base, by language (the whole
     *                          set, as the save writes the whole draft); none shows the cover chosen otherwise
     * @param assets            F-C1b: the assets of the sponsor's library the console copied parts of the draft from;
     *                          a part given none keeps its record as it is
     * @param treeId            F-S2b1: the knowledge graph of the node the publication is bound to (with
     *                          {@code treeNodeId}, or neither): a node in effect asking for a pass, of the sponsor's
     *                          own graph or, for an operator's direct Sponsor, of the platform's graph Takers light
     * @param treeNodeId        F-S2b1: that node; none binds the publication to no node
     * @param pickByDifficulty  F-S7: drawn by difficulty, how many questions of each ({@code EASY}, {@code MEDIUM},
     *                          {@code HARD}); {@code pickCount} is then their sum, whatever was given. None (or all
     *                          0): drawn from the whole version, or not at all
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
        @Size(max = 12) List<@NotNull @Size(max = 40) String> confirmed, Integer pickCount,
        @Size(max = 36) String benefitPurchaseId, BigDecimal entryFee,
        @Size(max = 3) Map<@NotNull @Size(max = 2) String, UUID> covers, PublicationAssets assets,
        @Size(max = 36) String treeId, @Size(max = 36) String treeNodeId,
        @Size(max = 3) Map<@NotNull @Size(max = 8) String, Integer> pickByDifficulty) {

        /** As before F-S7: no draw by difficulty. */
        public SaveInput(UUID publicationId, UUID versionId, String name, List<String> regions, Instant startAt,
            Instant endAt, Integer timeLimitSec, Integer passScore, BigDecimal budgetCap, Integer maxWinners,
            List<TierInput> tiers, Long baseRevision, String rewardMethod, String redeemDescription, String audience,
            String digitalDelivery, String digitalDescription, Integer digitalSendDays, String digitalContent,
            UUID cover, ReferralInput referral, List<String> languages,
            Map<String, Map<String, String>> translations, List<String> confirmed, Integer pickCount,
            String benefitPurchaseId, BigDecimal entryFee, Map<String, UUID> covers, PublicationAssets assets,
            String treeId, String treeNodeId) {
            this(publicationId, versionId, name, regions, startAt, endAt, timeLimitSec, passScore, budgetCap,
                maxWinners, tiers, baseRevision, rewardMethod, redeemDescription, audience, digitalDelivery,
                digitalDescription, digitalSendDays, digitalContent, cover, referral, languages, translations,
                confirmed, pickCount, benefitPurchaseId, entryFee, covers, assets, treeId, treeNodeId, null);
        }

        /** As before F-S2b1: bound to no node. */
        public SaveInput(UUID publicationId, UUID versionId, String name, List<String> regions, Instant startAt,
            Instant endAt, Integer timeLimitSec, Integer passScore, BigDecimal budgetCap, Integer maxWinners,
            List<TierInput> tiers, Long baseRevision, String rewardMethod, String redeemDescription, String audience,
            String digitalDelivery, String digitalDescription, Integer digitalSendDays, String digitalContent,
            UUID cover, ReferralInput referral, List<String> languages,
            Map<String, Map<String, String>> translations, List<String> confirmed, Integer pickCount,
            String benefitPurchaseId, BigDecimal entryFee, Map<String, UUID> covers, PublicationAssets assets) {
            this(publicationId, versionId, name, regions, startAt, endAt, timeLimitSec, passScore, budgetCap,
                maxWinners, tiers, baseRevision, rewardMethod, redeemDescription, audience, digitalDelivery,
                digitalDescription, digitalSendDays, digitalContent, cover, referral, languages, translations,
                confirmed, pickCount, benefitPurchaseId, entryFee, covers, assets, null, null);
        }

        /** As before F-C1b: nothing from the asset library. */
        public SaveInput(UUID publicationId, UUID versionId, String name, List<String> regions, Instant startAt,
            Instant endAt, Integer timeLimitSec, Integer passScore, BigDecimal budgetCap, Integer maxWinners,
            List<TierInput> tiers, Long baseRevision, String rewardMethod, String redeemDescription, String audience,
            String digitalDelivery, String digitalDescription, Integer digitalSendDays, String digitalContent,
            UUID cover, ReferralInput referral, List<String> languages,
            Map<String, Map<String, String>> translations, List<String> confirmed, Integer pickCount,
            String benefitPurchaseId, BigDecimal entryFee, Map<String, UUID> covers) {
            this(publicationId, versionId, name, regions, startAt, endAt, timeLimitSec, passScore, budgetCap,
                maxWinners, tiers, baseRevision, rewardMethod, redeemDescription, audience, digitalDelivery,
                digitalDescription, digitalSendDays, digitalContent, cover, referral, languages, translations,
                confirmed, pickCount, benefitPurchaseId, entryFee, covers, null);
        }

        /** As before Q98b: no covers per language. */
        public SaveInput(UUID publicationId, UUID versionId, String name, List<String> regions, Instant startAt,
            Instant endAt, Integer timeLimitSec, Integer passScore, BigDecimal budgetCap, Integer maxWinners,
            List<TierInput> tiers, Long baseRevision, String rewardMethod, String redeemDescription, String audience,
            String digitalDelivery, String digitalDescription, Integer digitalSendDays, String digitalContent,
            UUID cover, ReferralInput referral, List<String> languages,
            Map<String, Map<String, String>> translations, List<String> confirmed, Integer pickCount,
            String benefitPurchaseId, BigDecimal entryFee) {
            this(publicationId, versionId, name, regions, startAt, endAt, timeLimitSec, passScore, budgetCap,
                maxWinners, tiers, baseRevision, rewardMethod, redeemDescription, audience, digitalDelivery,
                digitalDescription, digitalSendDays, digitalContent, cover, referral, languages, translations,
                confirmed, pickCount, benefitPurchaseId, entryFee, null);
        }

        /** As before Q88b: no entry fee. */
        public SaveInput(UUID publicationId, UUID versionId, String name, List<String> regions, Instant startAt,
            Instant endAt, Integer timeLimitSec, Integer passScore, BigDecimal budgetCap, Integer maxWinners,
            List<TierInput> tiers, Long baseRevision, String rewardMethod, String redeemDescription, String audience,
            String digitalDelivery, String digitalDescription, Integer digitalSendDays, String digitalContent,
            UUID cover, ReferralInput referral, List<String> languages,
            Map<String, Map<String, String>> translations, List<String> confirmed, Integer pickCount,
            String benefitPurchaseId) {
            this(publicationId, versionId, name, regions, startAt, endAt, timeLimitSec, passScore, budgetCap,
                maxWinners, tiers, baseRevision, rewardMethod, redeemDescription, audience, digitalDelivery,
                digitalDescription, digitalSendDays, digitalContent, cover, referral, languages, translations,
                confirmed, pickCount, benefitPurchaseId, null);
        }

        /** As before Q89b-1: the prize is the sponsor's own. */
        public SaveInput(UUID publicationId, UUID versionId, String name, List<String> regions, Instant startAt,
            Instant endAt, Integer timeLimitSec, Integer passScore, BigDecimal budgetCap, Integer maxWinners,
            List<TierInput> tiers, Long baseRevision, String rewardMethod, String redeemDescription, String audience,
            String digitalDelivery, String digitalDescription, Integer digitalSendDays, String digitalContent,
            UUID cover, ReferralInput referral, List<String> languages,
            Map<String, Map<String, String>> translations, List<String> confirmed, Integer pickCount) {
            this(publicationId, versionId, name, regions, startAt, endAt, timeLimitSec, passScore, budgetCap,
                maxWinners, tiers, baseRevision, rewardMethod, redeemDescription, audience, digitalDelivery,
                digitalDescription, digitalSendDays, digitalContent, cover, referral, languages, translations,
                confirmed, pickCount, null, null);
        }

        /** As before Q81: every question of the version is asked. */
        public SaveInput(UUID publicationId, UUID versionId, String name, List<String> regions, Instant startAt,
            Instant endAt, Integer timeLimitSec, Integer passScore, BigDecimal budgetCap, Integer maxWinners,
            List<TierInput> tiers, Long baseRevision, String rewardMethod, String redeemDescription, String audience,
            String digitalDelivery, String digitalDescription, Integer digitalSendDays, String digitalContent,
            UUID cover, ReferralInput referral, List<String> languages,
            Map<String, Map<String, String>> translations, List<String> confirmed) {
            this(publicationId, versionId, name, regions, startAt, endAt, timeLimitSec, passScore, budgetCap,
                maxWinners, tiers, baseRevision, rewardMethod, redeemDescription, audience, digitalDelivery,
                digitalDescription, digitalSendDays, digitalContent, cover, referral, languages, translations,
                confirmed, null, null);
        }

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

    /**
     * F-C1b: the assets of the sponsor's library parts of a draft were copied from, each recorded as where the asset
     * is used: an image for the cover, a text of the matching use for each description, a prize card for the prize.
     */
    public record PublicationAssets(UUID coverAssetId, UUID redeemAssetId, UUID digitalAssetId,
        UUID referralAssetId, UUID prizeAssetId) {

        List<AssetUses.Pick> picks() {
            return List.of(
                new AssetUses.Pick("assets.coverAssetId", QbAssets.PUBLICATION_COVER, null, coverAssetId),
                new AssetUses.Pick("assets.redeemAssetId", QbAssets.PUBLICATION_REDEEM, null, redeemAssetId),
                new AssetUses.Pick("assets.digitalAssetId", QbAssets.PUBLICATION_DIGITAL, null, digitalAssetId),
                new AssetUses.Pick("assets.referralAssetId", QbAssets.PUBLICATION_REFERRAL, null, referralAssetId),
                new AssetUses.Pick("assets.prizeAssetId", QbAssets.PUBLICATION_PRIZE, null, prizeAssetId));
        }
    }

    /** The places of a publication an asset may fill. */
    static final List<String> ASSET_TARGETS = List.of(QbAssets.PUBLICATION_COVER, QbAssets.PUBLICATION_REDEEM,
        QbAssets.PUBLICATION_DIGITAL, QbAssets.PUBLICATION_REFERRAL, QbAssets.PUBLICATION_PRIZE);

    private static List<AssetUses.Pick> picks(ProcessContext ctx) {
        PublicationAssets assets = ctx.get(INPUT, SaveInput.class).assets();
        return assets == null ? List.of() : assets.picks();
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

    public static ProcessDefinition<SaveInput, SaveOutput, ProcessContext> save(MessageCatalog messages,
        boolean firstLaunch) {
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
            // F-S2b1: the node it is bound to, its graph, and whether the caller is an operator's direct Sponsor.
            .steps(b -> PublicationNodes.load(b, ctx -> {
                SaveInput input = ctx.get(INPUT, SaveInput.class);
                return PublicationNodes.binding(input.treeId(), input.treeNodeId());
            }))
            .steps(PublicationEditing::loadSponsor)
            .steps(b -> PublicationEditing.loadSponsorMethods(b, firstLaunch))
            // Q64: who uploaded the covers given (Q98b: in each language too), which must be the sponsor's own files.
            .steps(b -> QuizEditing.loadFiles(b, ctx -> {
                SaveInput input = ctx.get(INPUT, SaveInput.class);
                List<UUID> files = new ArrayList<>();
                if (input.cover() != null) {
                    files.add(input.cover());
                }
                if (input.covers() != null) {
                    input.covers().values().stream().filter(java.util.Objects::nonNull).forEach(files::add);
                }
                return files;
            }))
            .steps(b -> PublicationEditing.loadVersion(b, ctx -> {
                UUID given = ctx.get(INPUT, SaveInput.class).versionId();
                EntityInstance found = first(ctx, FOUND);
                return given != null || found == null ? given : found.get("versionId");
            }, true))
            // Q33: a shared digital prize's code or link, sealed for this sponsor before anything is written (the
            // step draws random bytes, so it is a blocking one); blank keeps the stored one.
            // Q89b-1: the purchase the prize is, as it is now (the draft keeps what it saw for the review).
            .step("Load the purchase", QueryEntities.<ProcessContext>of(
                com.jabiz.quizbuks.benefit.QbBenefits.defaultDataset(com.jabiz.quizbuks.benefit.QbBenefits.PURCHASE),
                ctx -> {
                    String purchaseId = ctx.get(INPUT, SaveInput.class).benefitPurchaseId();
                    return EntityQuery.builder().where(new QueryPredicate.In("purchaseId",
                        purchaseId == null || purchaseId.isBlank() ? List.of() : List.of(purchaseId))).limit(1).build();
                }, BENEFIT_PURCHASE))
            .step("Seal the shared prize", SealSecret.<ProcessContext>of(PublicationProcesses::sharedContent,
                ctx -> String.valueOf((Object) ctx.request().actorId()), SEALED_DIGITAL))
            // F-C1b: the assets parts of the draft were copied from, after the publication's lock.
            .steps(b -> AssetUses.load(b, ctx -> picks(ctx).stream().map(AssetUses.Pick::assetId)
                .filter(Objects::nonNull).toList(), ctx -> {
                    EntityInstance found = first(ctx, FOUND);
                    return found == null ? List.of() : picks(ctx).stream().filter(pick -> pick.assetId() != null)
                        .map(pick -> new AssetUses.Place(pick.targetKind(), found.id())).toList();
                }))
            .compute("Check the assets picked", (metadata, ctx) -> AssetUses.check(ctx, picks(ctx)))
            .compute("Save the publication", (metadata, ctx) -> save(ctx, messages)));
    }

    /** The context key of the purchase the save names (Q89b-1). */
    static final String BENEFIT_PURCHASE = "benefitPurchase";

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
        // F-S4c: a challenge pays nothing (its tiers are all 0), so no reward method is asked of it: a Sponsor whose
        // default methods are empty (F-S4b) publishes challenges too.
        if (!PublicationNodes.forSponsors(ctx)) {
            PublicationEditing.methodProblems(ctx, method).forEach(ctx::reject);
        }
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
        // Q98b: a cover per language, for the languages offered besides the version's base, each the sponsor's file.
        String coverBase = version == null ? null : String.valueOf((Object) version.get("language"));
        List<String> coverLanguages = input.languages() == null ? List.of() : input.languages();
        PublicationCovers.refusals(input.covers(), coverLanguages, coverBase).forEach(ctx::reject);
        Map<String, UUID> covers = input.covers() == null ? Map.of() : input.covers();
        covers.forEach((language, file) -> {
            String field = PublicationCovers.FIELDS.get(language);
            if (field != null && file != null) {
                QuizEditing.requireOwnFile(ctx, publication == null ? ctx.request().actorId()
                    : publication.get("publisherId"), "covers." + language, file, publication == null ? null
                    : publication.get(field));
            }
        });
        if (ctx.hasViolations()) {
            return;
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("name", input.name());
        values.put("cover", input.cover());
        PublicationCovers.FIELDS.forEach((language, field) -> values.put(field, covers.get(language)));
        values.put("regions", regions);
        values.put("audience", audience);
        values.put("rewardMethod", method);
        // Only a prize at the sponsor's place has a description; blank is none.
        values.put("redeemDescription", !RewardMethod.OFFLINE_REDEEM.equals(method)
            || input.redeemDescription() == null || input.redeemDescription().isBlank() ? null
            : input.redeemDescription().strip());
        // Q89b-1: before the texts' translations, which take the prize's description as written.
        benefit(ctx, input, method, values);
        if (ctx.hasViolations()) {
            return;
        }
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
        // Q89b-2: a digital product's prize says what it is, as the benefit does, when the sponsor wrote nothing.
        if (digital && values.get("digitalDescription") == null && values.get("benefitSnapshot") != null) {
            values.put("digitalDescription", com.jabiz.quizbuks.benefit.BenefitPublishing.defaultDescription(
                com.jabiz.quizbuks.benefit.BenefitPublishing.fromJson(values.get("benefitSnapshot")),
                PublishingLimits.REDEEM_DESCRIPTION_LENGTH));
        }
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
        // Q81: before the version's part, whose full score depends on it. F-S7: a draw by difficulty draws their sum.
        String quotas = com.jabiz.quizbuks.content.QuestionDifficulty.formatQuotas(input.pickByDifficulty());
        values.put("pickByDifficulty", quotas);
        Integer drawn = quotas == null ? input.pickCount() : Integer.valueOf(
            com.jabiz.quizbuks.content.QuestionDifficulty.total(
                com.jabiz.quizbuks.content.QuestionDifficulty.parseQuotas(quotas)));
        values.put("pickCount", decimal(drawn));
        // F-S2b1: the node it is bound to (checked with the problems below, and again on submission).
        boolean bound = input.treeNodeId() != null && !input.treeNodeId().isBlank();
        values.put("treeId", bound && input.treeId() != null ? input.treeId().strip() : null);
        values.put("treeNodeId", bound && input.treeId() != null ? input.treeNodeId().strip() : null);
        // F-S4a: a node of a map Sponsors light makes it a challenge (the map's kind never changes).
        values.put("forSponsors", PublicationNodes.forSponsors(ctx));
        // Q88b (D-Q88-9, D-Q88-14): the entry fee, none by default; set only while the switch is on.
        BigDecimal entryFee = input.entryFee() == null ? BigDecimal.ZERO : input.entryFee();
        if (entryFee.signum() > 0 && !PublicationEditing.entryFeeEnabled(ctx)) {
            ctx.reject(new Violation("entryFee", PublishingCodes.ENTRY_FEE_OFF, "Entry fees are not offered at the "
                + "moment", Map.of()));
        }
        values.put("entryFee", entryFee);
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
        for (AssetUses.Pick pick : picks(ctx)) {
            AssetUses.record(ctx, pick.targetKind(), publicationId, pick.assetId());
        }
        if (Audience.INVITED.equals(audience)) {
            list(ctx, PublicationAudience.CRITERIA).forEach(row ->
                ctx.changes().delete(com.jabiz.quizbuks.audience.QbAudience.CRITERION, row.id(), row.version()));
        }
        ctx.put(OUTPUT, output(ctx, messages, publicationId, revision, editedAt, values, tiers, version, tierIds));
    }

    /**
     * Q89b-1 (D-Q89-6): the purchase a prize collected at the provider's place is. Only a confirmed purchase of the
     * caller's own is taken (another's is not found); the draft keeps it as it is now (what the review approves, and
     * what the submission checks again), its provider hands the prizes over, and the prize's description is the
     * benefit's when the sponsor wrote none. Without one (or with another method), nothing of it.
     */
    static void benefit(ProcessContext ctx, SaveInput input, String method, Map<String, Object> values) {
        String purchaseId = input.benefitPurchaseId() == null || input.benefitPurchaseId().isBlank() ? null
            : input.benefitPurchaseId().strip();
        EntityInstance purchase = first(ctx, BENEFIT_PURCHASE);
        if (purchaseId == null || !BENEFIT_METHODS.contains(method)) {
            values.put("benefitPurchaseId", null);
            values.put("benefitSnapshot", null);
            values.put("fulfillerId", null);
            return;
        }
        if (purchase == null || !ctx.request().actorId().equals(String.valueOf((Object) purchase.get("sponsorId")))) {
            throw new EntityNotFoundException(com.jabiz.quizbuks.benefit.QbBenefits.PURCHASE + " " + purchaseId
                + " not found");
        }
        com.jabiz.quizbuks.benefit.BenefitPublishing.Snapshot snapshot =
            com.jabiz.quizbuks.benefit.BenefitPublishing.of(purchase.id(), purchase.attributes());
        // Goods and services are collected at the provider's place, a digital product is a digital prize (Q89b-2).
        boolean digital = com.jabiz.quizbuks.benefit.QbBenefits.DIGITAL.equals(snapshot.kind());
        if (!com.jabiz.quizbuks.benefit.PurchaseStatus.CONFIRMED.equals(purchase.get("status"))
            || digital != RewardMethod.DIGITAL_PRIZE.equals(method)) {
            ctx.reject(new Violation("benefitPurchaseId", com.jabiz.quizbuks.benefit.BenefitCodes.PURCHASE_NOT_USABLE,
                "Only a confirmed purchase: goods or a service collected at its provider's place, a digital product "
                    + "as a digital prize", Map.of("status", String.valueOf((Object) purchase.get("status")))));
            return;
        }
        values.put("benefitPurchaseId", String.valueOf(purchase.id()));
        values.put("benefitSnapshot", com.jabiz.quizbuks.benefit.BenefitPublishing.toJson(snapshot));
        values.put("fulfillerId", snapshot.providerId());
        if (!digital && values.get("redeemDescription") == null) {
            values.put("redeemDescription", com.jabiz.quizbuks.benefit.BenefitPublishing.defaultDescription(snapshot,
                PublishingLimits.REDEEM_DESCRIPTION_LENGTH));
        }
    }

    /** Q89b-1/-2: the reward methods a purchase can be: goods and services collected, digital products sent. */
    static final List<String> BENEFIT_METHODS = List.of(RewardMethod.OFFLINE_REDEEM, RewardMethod.DIGITAL_PRIZE);

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

    /**
     * The version's part of a publication: its quiz, label and full score (none without a version). The full score is
     * the pick's when {@code values} has one (Q81, D-Q81-2), so the pick is put first.
     */
    private static void putVersion(Map<String, Object> values, EntityInstance version) {
        values.put("versionId", version == null ? null : version.id());
        values.put("quizId", version == null ? null : version.get("quizId"));
        values.put("versionLabel", version == null ? null : version.get("label"));
        values.put("fullScore", PublicationEditing.fullScoreOf(values, version));
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
            ranges, PublicationEditing.snapshot(version), ctx.opTime(), PublicationEditing.maxRewardPerAttempt(ctx),
            values.get("treeNodeId") != null));
        // F-S2b1: the node it is bound to.
        problems.addAll(PublicationNodes.problems(ctx, ctx.request().actorId(), (String) values.get("treeId"),
            (String) values.get("treeNodeId")));
        // F-S4a: a challenge pays nothing and has none of the extras.
        if (Boolean.TRUE.equals(values.get("forSponsors"))) {
            problems.addAll(PublicationChallenges.problems(values, ranges));
        }
        // What submitting would also say: the caller is no approved sponsor, or is banned (D-Q2-6); the method is
        // one they may not use (Q22: a copy of a publication made before the method was taken away).
        problems.addAll(PublicationEditing.sponsorProblems(ctx));
        if (!Boolean.TRUE.equals(values.get("forSponsors"))) {
            problems.addAll(PublicationEditing.methodProblems(ctx, PublicationEditing.methodOf(values)));
        }
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
                // F-C1b: where its parts were filled from the asset library goes with it.
                .steps(b -> AssetUses.load(b, ctx -> List.of(), ctx -> ASSET_TARGETS.stream()
                    .map(kind -> new AssetUses.Place(kind, PublicationEditing.publication(ctx).id())).toList()))
                .compute("Delete the publication", (metadata, ctx) -> {
                    EntityInstance publication = PublicationEditing.publication(ctx);
                    if (!PublicationEditing.draft(ctx, publication) || !PublicationEditing.current(ctx,
                        publication, ctx.get(INPUT, PublicationRef.class).baseRevision())) {
                        return;
                    }
                    // Tiers before what they belong to: an instance still referred to cannot be deleted.
                    list(ctx, TIERS).forEach(tier -> ctx.changes().delete(QbPublications.TIER, tier.id(),
                        tier.version()));
                    // Q47: the conditions too, for the same reason.
                    list(ctx, PublicationAudience.CRITERIA).forEach(row -> ctx.changes().delete(
                        com.jabiz.quizbuks.audience.QbAudience.CRITERION, row.id(), row.version()));
                    ctx.changes().delete(QbPublications.PUBLICATION, publication.id(), publication.version());
                    ASSET_TARGETS.forEach(kind -> AssetUses.forget(ctx, kind, List.of(publication.id())));
                    ctx.put(OUTPUT, new PublicationOutput(uuid(publication.id()), PublicationStatus.DRAFT,
                        number(publication.get("revision")), null, null));
                }));
    }

    // ---- copy ------------------------------------------------------------------------------------------------------

    public static ProcessDefinition<CopyInput, SaveOutput, ProcessContext> copy(MessageCatalog messages,
        boolean firstLaunch) {
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
            // F-S2b1: the node the source is bound to, which the copy keeps (its problems say if no longer usable).
            .steps(b -> PublicationNodes.load(b, ctx -> PublicationNodes.binding(PublicationEditing.publication(ctx))))
            .steps(PublicationEditing::loadSponsor)
            .steps(b -> PublicationEditing.loadSponsorMethods(b, firstLaunch))
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
        for (String field : List.of("cover", "coverJa", "coverZh", "coverEn", "regions", "startAt", "endAt",
            "timeLimitSec", "passScore", "budgetCap",
            "maxWinners", "redeemDescription", "digitalDelivery", "digitalDescription", "digitalSendDays",
            "sealedDigitalContent", "referral", "languages", "translations", "pickCount", "pickByDifficulty",
            // F-S9: what it adds to and removes from its version's topics.
            "topicChanges",
            // F-S12: the shared video it picked (the copy's submission checks it is still in the catalog).
            "sharedVideoId",
            // Q89b-1: the purchase, as the source saw it; the copy's submission checks it again.

            "benefitPurchaseId", "benefitSnapshot", "fulfillerId",
            // Q88b: the entry fee (the copy keeps it whatever the switch says now: what was set stays set).
            "entryFee",
            // F-S2b1: the node (the copy's submission checks it again). F-S4a: and whether it is a challenge.
            "treeId", "treeNodeId", "forSponsors")) {
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
