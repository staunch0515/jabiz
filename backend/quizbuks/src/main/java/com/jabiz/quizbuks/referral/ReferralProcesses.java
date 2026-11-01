package com.jabiz.quizbuks.referral;

import com.jabiz.entity.Violation;
import com.jabiz.runtime.ledger.LedgerProcesses;
import com.jabiz.param.ParamValues;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.audience.AudienceRows;
import com.jabiz.quizbuks.certification.Certifications;
import com.jabiz.quizbuks.certification.RoundHolders;
import com.jabiz.quizbuks.identity.AccountEditing;
import com.jabiz.quizbuks.identity.BanRules;
import com.jabiz.quizbuks.identity.CallerChecks;
import com.jabiz.quizbuks.identity.QbAccounts;
import com.jabiz.quizbuks.play.QbPlay;
import com.jabiz.quizbuks.publishing.DigitalDelivery;
import com.jabiz.quizbuks.publishing.PublicationRules;
import com.jabiz.quizbuks.publishing.PublisherKind;
import com.jabiz.quizbuks.publishing.QbPublications;
import com.jabiz.quizbuks.publishing.ReferralPolicy;
import com.jabiz.quizbuks.publishing.RefereeRules;
import com.jabiz.quizbuks.publishing.RewardMethod;
import com.jabiz.quizbuks.reward.CashRewardStatus;
import com.jabiz.quizbuks.reward.DigitalPrizeStatus;
import com.jabiz.quizbuks.reward.QbDigitalPrizes;
import com.jabiz.quizbuks.reward.QbRewards;
import com.jabiz.quizbuks.reward.RedemptionStatus;
import com.jabiz.quizbuks.reward.RewardSources;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.quizbuks.wallet.QbWallets;
import com.jabiz.runtime.BusinessRuleViolationException;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.EntityNotFoundException;
import com.jabiz.runtime.process.steps.CallProcess;
import com.jabiz.runtime.process.steps.HoldLock;
import com.jabiz.runtime.process.steps.LoadParams;
import com.jabiz.runtime.process.steps.PublishEvent;
import com.jabiz.runtime.process.steps.QueryEntities;
import com.jabiz.runtime.security.RandomBytesStep;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Referral advertising (Q70, docs/quizbuks/plans/Q70-referrals-and-rounds.md §5.2): a holder of the referrer
 * certification makes their link to a publication that welcomes referrals ({@value #LINK}); a friend who opens it
 * claims the referral ({@value #CLAIM}); the system pays the referral reward once the friend passes in time
 * ({@value #REWARD}, the job {@value #REWARD_JOB}) or ends it ({@value #EXPIRE}, the job {@value #EXPIRE_JOB}).
 * The answering path is not touched: a pass is found by the reward job afterwards. Every process takes the
 * publication's referral lock first ({@link QbReferrals#lockName}); after it only the code pool's lock and the
 * sponsor's wallet lock, never a publication's own lock or its start lock.
 */
public final class ReferralProcesses {

    public static final String LINK = "QB_REFERRAL_LINK";
    public static final String CLAIM = "QB_REFERRAL_CLAIM";
    public static final String REWARD = "QB_REFERRAL_REWARD";
    public static final String EXPIRE = "QB_REFERRAL_EXPIRE";
    public static final String VIEW = "QB_REFERRAL_VIEW";

    public static final String REWARD_JOB = "qb.referral-reward";
    public static final String EXPIRE_JOB = "qb.referral-expire";
    public static final String DUE_TEMPLATE = "qb.system.referrals-due";
    public static final String OVER_TEMPLATE = "qb.system.referrals-over";

    public static final String CLAIMED_EVENT = "qb.referral.claimed";
    public static final String REWARDED_EVENT = "qb.referral.rewarded";
    public static final String EXPIRED_EVENT = "qb.referral.expired";
    public static final String FUNDS_SHORT_EVENT = "qb.referral.funds-short";

    /** The length of a link's random key, in bytes (32 hex characters). */
    static final int KEY_BYTES = 16;
    /** At most so many of a referee's passes of the sponsor's other publications are read (one is enough). */
    static final int MAX_PASSES = 50;

    public record LinkInput(@NotNull UUID publicationId) {}

    /** The caller's link to the publication: its key (in {@code /p/<publication>?ref=<key>}) and the places left. */
    public record LinkOutput(String publicationId, String linkKey, int quotaLeft) {}

    public record ClaimInput(@NotBlank @Size(max = 64) String linkKey) {}

    /**
     * Whether the claim counted, and for the referee's page until when they have to pass; nothing else (why a claim
     * did not count is told to nobody, D-Q70-15).
     */
    public record ClaimOutput(String publicationId, boolean counted, Instant expiresAt) {}

    public record ReferralRef(@NotNull String referralId) {}

    public record ViewInput(@NotBlank @Size(max = 36) String referralId) {}

    /**
     * One of the caller's referrals as its referrer sees it (never the friend), with the record of its reward when the
     * sponsor settles it themselves: the same views as an attempt's result, so the App confirms a cash reward, shows
     * the code to collect a prize or opens a digital prize the same way (Q70b-2).
     */
    public record ReferralView(String referralId, String publicationId, String publicationName, String status,
        Instant referredAt, Instant expiresAt, String rewardMethod, BigDecimal amount, Instant rewardedAt,
        Instant endedAt, com.jabiz.quizbuks.play.AttemptResults.CashView cash,
        com.jabiz.quizbuks.play.AttemptResults.RedeemView redeem,
        com.jabiz.quizbuks.play.AttemptResults.DigitalView digital) {}

    /** What a reward or an expiry did: the referral's status after it, and whether it wrote anything. */
    public record ReferralOutput(String referralId, String status, boolean changed) {}

    static final String INPUT = "input";
    static final String OUTPUT = "output";
    static final String RANDOM = "random";
    static final String LINK_ROW = "link";
    static final String CARD = "card";
    static final String COUNTED = "counted";
    static final String MINE = "mine";
    static final String ATTEMPT = "attempt";
    static final String REFERRER_ROWS = "referrerRows";
    static final String STAFF = "staff";
    static final String SPONSOR_BAN = "sponsorBan";
    static final String PASSES = "passes";
    static final String PROFILES = "profiles";
    static final String CONDITIONS = "conditions";
    static final String ATTRIBUTES = "attributes";
    static final String PARAMS = "params";
    static final String BEFORE = "before";
    static final String REFERRAL_ROW = "referral";
    static final String POOL_CODE = "poolCode";
    static final String WALLET = "wallet";
    static final String PLAN = "plan";
    static final String POSTING = "posting";

    // ---- the referrer -----------------------------------------------------------------------------------------------

    public static ProcessDefinition<LinkInput, LinkOutput, ProcessContext> link() {
        return ProcessDefinition.define(LINK, 1, LinkInput.class, LinkOutput.class, ProcessContext.class, pb -> {
            pb.description("Makes the caller's referral link to a publication that welcomes referrals, or gives the "
                    + "one made before; a new one needs the referrer certification.")
                .permissions(QbPermissions.ME)
                .requiresVerifiedEmail()
                .steps(CallerChecks::refuseBanned)
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put(INPUT, input);
                    return ctx;
                })
                .outputMapper(ctx -> ctx.get(OUTPUT, LinkOutput.class))
                .step("Draw a key", RandomBytesStep.class, new RandomBytesStep.Metadata(RANDOM, KEY_BYTES))
                .step("Lock the publication's referrals", HoldLock.<ProcessContext>exclusive(
                    ctx -> QbReferrals.lockName(publication(ctx))))
                .step("Load the card, the caller's link and attempt, and the referrals counted", QueryEntities
                    .<ProcessContext>all(
                        card(ReferralProcesses::publication, CARD),
                        QueryEntities.query(QbReferrals.defaultDataset(QbReferrals.LINK), ctx -> EntityQuery.builder()
                            .where(new QueryPredicate.And(List.of(
                                new QueryPredicate.Eq("publicationId", String.valueOf(publication(ctx))),
                                new QueryPredicate.Eq("referrerId", ctx.request().actorId())))).limit(1).build(),
                            LINK_ROW),
                        attempt(ReferralProcesses::publication, ctx -> ctx.request().actorId(), ATTEMPT),
                        counted(ReferralProcesses::publication)));
            RoundHolders.loadHolding(pb, ctx -> first(ctx, LINK_ROW) == null ? Certifications.REFERRER : null);
            pb.compute("Make the link", (metadata, ctx) -> makeLink(ctx));
        });
    }

    static void makeLink(ProcessContext ctx) {
        EntityInstance card = first(ctx, CARD);
        ReferralPolicy policy = card == null ? null : ReferralPolicy.fromJson(card.get("referral"));
        if (card == null || !PublisherKind.sponsor(card.get("publisherKind"))) {
            throw new EntityNotFoundException(QbPublications.PUBLICATION + " " + publication(ctx) + " not found");
        }
        int left = policy == null ? 0 : Math.max(0, policy.quota() - list(ctx, COUNTED).size());
        EntityInstance existing = first(ctx, LINK_ROW);
        if (existing != null) {
            // D-Q70-9: a link made stays the referrer's, whatever became of their certification since.
            ctx.put(OUTPUT, new LinkOutput(String.valueOf(publication(ctx)), (String) existing.get("linkKey"), left));
            return;
        }
        if (policy == null || !accepting(card, ctx.opTime())) {
            throw refusal(ReferralCodes.NOT_OPEN, "The publication does not take referrals now");
        }
        if (ctx.request().actorId().equals(String.valueOf((Object) card.get("publisherId")))) {
            throw refusal(ReferralCodes.OWN, "A sponsor does not refer people to their own publication");
        }
        if (left == 0) {
            throw refusal(ReferralCodes.QUOTA_FULL, "The publication's referrals are used up");
        }
        if (RoundHolders.heldUntil(ctx, Certifications.REFERRER).isEmpty()) {
            throw refusal(ReferralCodes.NOT_REFERRER, "Pass the referrer certification first");
        }
        EntityInstance attempt = first(ctx, ATTEMPT);
        boolean completed = attempt != null && attempt.get("submittedAt") != null;
        if (!ReferralRules.referrerMeets(policy.referrerRequires(), completed,
            completed && Boolean.TRUE.equals(attempt.get("passed")))) {
            throw refusal(ReferralCodes.REQUIREMENT, "Answer the publication first", Map.of("referrerRequires",
                policy.referrerRequires()));
        }
        String key = HexFormat.of().formatHex(ctx.get(RANDOM, byte[].class));
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("publicationId", String.valueOf(publication(ctx)));
        values.put("referrerId", ctx.request().actorId());
        values.put("linkKey", key);
        values.put("createdAt", ctx.opTime());
        ctx.changes().insert(QbReferrals.LINK, values);
        ctx.put(OUTPUT, new LinkOutput(String.valueOf(publication(ctx)), key, left));
    }

    // ---- the referee ------------------------------------------------------------------------------------------------

    public static ProcessDefinition<ClaimInput, ClaimOutput, ProcessContext> claim() {
        return ProcessDefinition.define(CLAIM, 1, ClaimInput.class, ClaimOutput.class, ProcessContext.class, pb -> pb
            .description("Claims the referral of a link the caller opened: counted when the publication still takes "
                + "referrals and the caller is somebody it may count (the sponsor's rules); else nothing is recorded.")
            .permissions(QbPermissions.ME)
            .requiresVerifiedEmail()
            .steps(CallerChecks::refuseBanned)
            .contextFactory((start, input) -> {
                ProcessContext ctx = new ProcessContext(start);
                ctx.put(INPUT, input);
                return ctx;
            })
            .outputMapper(ctx -> ctx.get(OUTPUT, ClaimOutput.class))
            .step("Find the link", QueryEntities.<ProcessContext>of(QbReferrals.defaultDataset(QbReferrals.LINK),
                ctx -> EntityQuery.builder().where(new QueryPredicate.Eq("linkKey",
                    ctx.get(INPUT, ClaimInput.class).linkKey())).limit(1).build(), LINK_ROW))
            .compute("Refuse an unknown link", (metadata, ctx) -> {
                if (first(ctx, LINK_ROW) == null) {
                    throw new EntityNotFoundException(QbReferrals.LINK + " not found");
                }
            })
            .step("Load the parameters", LoadParams.<ProcessContext>of(ProcessContext::opTime, PARAMS,
                QbParams.REFERRAL_PER_REFERRER_DAILY, QbParams.REFERRAL_PER_REFERRER_TOTAL))
            .step("Lock the publication's referrals", HoldLock.<ProcessContext>exclusive(
                ctx -> QbReferrals.lockName(linkPublication(ctx))))
            .step("Load the card, the referrals and the caller", QueryEntities.<ProcessContext>all(
                card(ReferralProcesses::linkPublication, CARD),
                counted(ReferralProcesses::linkPublication),
                QueryEntities.query(QbReferrals.defaultDataset(QbReferrals.REFERRAL), ctx -> EntityQuery.builder()
                    .where(new QueryPredicate.And(List.of(
                        new QueryPredicate.Eq("publicationId", String.valueOf(linkPublication(ctx))),
                        new QueryPredicate.Eq("refereeId", ctx.request().actorId())))).limit(1).build(), MINE),
                QueryEntities.query(QbReferrals.defaultDataset(QbReferrals.REFERRAL), ctx -> EntityQuery.builder()
                    .where(new QueryPredicate.And(List.of(
                        new QueryPredicate.Eq("publicationId", String.valueOf(linkPublication(ctx))),
                        new QueryPredicate.Eq("referrerId", String.valueOf((Object) first(ctx, LINK_ROW)
                            .get("referrerId")))))).limit(ReferralPolicy.MAX_QUOTA).build(), REFERRER_ROWS),
                attempt(ReferralProcesses::linkPublication, ctx -> ctx.request().actorId(), ATTEMPT),
                QueryEntities.query(QbRewards.defaultDataset(QbRewards.STAFF), ctx -> EntityQuery.builder()
                    .where(new QueryPredicate.Eq("userId", ctx.request().actorId())).limit(1).build(), STAFF),
                QueryEntities.query(QbAccounts.defaultDataset(QbAccounts.PROFILE), ctx -> EntityQuery.builder()
                    .where(new QueryPredicate.In("userId", profileIds(ctx))).limit(2).build(), PROFILES),
                AudienceRows.conditions(ReferralProcesses::linkPublication, CONDITIONS),
                AudienceRows.callers(ATTRIBUTES)))
            .step("Load the sponsor's ban and the caller's passes with the sponsor", QueryEntities.<ProcessContext>all(
                ban(ctx -> cardValue(ctx, "publisherId")),
                passes(ctx -> ctx.request().actorId(), ctx -> cardValue(ctx, "publisherId"))))
            .compute("Record the referral", (metadata, ctx) -> recordClaim(ctx))
            .step("Tell the referrer", PublishEvent.<ProcessContext>when(
                ctx -> ctx.get(OUTPUT, ClaimOutput.class).counted() && ctx.contains(REFERRAL_ROW),
                CLAIMED_EVENT, ctx -> event(written(ctx)))));
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> written(ProcessContext ctx) {
        return (Map<String, Object>) ctx.get(REFERRAL_ROW, Map.class);
    }

    static void recordClaim(ProcessContext ctx) {
        EntityInstance link = first(ctx, LINK_ROW);
        EntityInstance card = first(ctx, CARD);
        Object publicationId = linkPublication(ctx);
        ReferralPolicy policy = card == null ? null : ReferralPolicy.fromJson(card.get("referral"));
        String me = ctx.request().actorId();
        String referrer = (String) link.get("referrerId");
        String publisher = card == null ? null : String.valueOf((Object) card.get("publisherId"));
        ParamValues params = ctx.get(PARAMS, ParamValues.class);
        EntityInstance mine = first(ctx, MINE);
        String refereeCountry = country(ctx, me);
        EntityInstance myProfile = profile(ctx, me);
        String refusal = ReferralRules.claimRefusal(new ReferralRules.Claim(policy,
            card != null && PublisherKind.sponsor(card.get("publisherKind")) && accepting(card, ctx.opTime()),
            BanRules.banned(attributes(first(ctx, SPONSOR_BAN))), publisher, referrer, me,
            first(ctx, STAFF) != null && publisher != null
                && publisher.equals(String.valueOf((Object) first(ctx, STAFF).get("sponsorId"))),
            first(ctx, ATTEMPT) != null, mine != null, list(ctx, COUNTED).size(),
            list(ctx, REFERRER_ROWS).stream().map(r -> instant(r.get("referredAt"))).toList(),
            QbParams.count(params, QbParams.REFERRAL_PER_REFERRER_DAILY),
            QbParams.count(params, QbParams.REFERRAL_PER_REFERRER_TOTAL),
            !list(ctx, PASSES).isEmpty(), myProfile == null ? null : instant(myProfile.get("createdAt")),
            AudienceRows.matches(list(ctx, CONDITIONS), list(ctx, ATTRIBUTES), ctx.opTime()),
            refereeCountry, country(ctx, referrer), ctx.opTime()));
        if (refusal != null) {
            Instant expires = mine != null && QbReferrals.REFERRED.equals(mine.get("status"))
                && referrer != null && referrer.equals(String.valueOf((Object) mine.get("referrerId")))
                ? instant(mine.get("expiresAt")) : null;
            // Opening the same link again shows the referral already counted; anything else counts nothing.
            ctx.put(OUTPUT, new ClaimOutput(String.valueOf(publicationId), expires != null, expires));
            return;
        }
        Instant expires = ctx.opTime().plus(Duration.ofDays(policy.days()));
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("publicationId", String.valueOf(publicationId));
        values.put("publisherKind", card.get("publisherKind"));
        values.put("publisherId", publisher);
        values.put("referrerId", referrer);
        values.put("refereeId", me);
        values.put("linkId", String.valueOf(link.id()));
        values.put("status", QbReferrals.REFERRED);
        values.put("referredAt", ctx.opTime());
        values.put("expiresAt", expires);
        values.put("rewardMethod", policy.method());
        values.put("amount", policy.amount());
        values.put("fundsShort", false);
        Object id = ctx.changes().insert(QbReferrals.REFERRAL, values);
        values.put("referralId", String.valueOf(id));
        ctx.put(REFERRAL_ROW, values);
        ctx.put(OUTPUT, new ClaimOutput(String.valueOf(publicationId), true, expires));
    }

    // ---- the system: rewards and expiries -----------------------------------------------------------------------------

    /** What the reward found to do: nothing, void the referral, or pay its reward the referral's way. */
    record Plan(EntityInstance referral, EntityInstance card, EntityInstance attempt, String voidReason) {
        String method() {
            return (String) referral.get("rewardMethod");
        }

        boolean paying() {
            return voidReason == null;
        }

        boolean pooled() {
            return paying() && RewardMethod.DIGITAL_PRIZE.equals(method())
                && DigitalDelivery.POOL.equals(DigitalDelivery.of(card.get("digitalDelivery")));
        }

        boolean fromWallet() {
            return paying() && RewardMethod.PLATFORM.equals(method());
        }
    }

    public static ProcessDefinition<ReferralRef, ReferralOutput, ProcessContext> reward() {
        return ProcessDefinition.define(REWARD, 1, ReferralRef.class, ReferralOutput.class, ProcessContext.class,
            pb -> pb
                .description("Pays a referral's reward once its referee passed in time, the publication's way; voids "
                    + "a referral that no longer counts; waits while the sponsor's wallet is short (system).")
                .permissions(QbPermissions.REFERRAL_SYSTEM)
                .internal()
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put(INPUT, input);
                    return ctx;
                })
                .outputMapper(ctx -> ctx.get(OUTPUT, ReferralOutput.class))
                .step("Find the referral", QueryEntities.<ProcessContext>all(referralRow(BEFORE)))
                .step("Load the periods", LoadParams.<ProcessContext>of(ProcessContext::opTime, PARAMS,
                    QbParams.CASH_PAY_DAYS, QbParams.REDEEM_DAYS, QbParams.DIGITAL_SEND_DAYS))
                .step("Lock the publication's referrals", HoldLock.<ProcessContext>exclusive(
                    ctx -> first(ctx, BEFORE) == null ? null : QbReferrals.lockName(first(ctx, BEFORE)
                        .get("publicationId"))))
                .step("Load the referral, the card and the referee's attempt", QueryEntities.<ProcessContext>all(
                    referralRow(REFERRAL_ROW),
                    card(ReferralProcesses::beforePublication, CARD),
                    attempt(ReferralProcesses::beforePublication, ctx -> beforeValue(ctx, "refereeId"), ATTEMPT),
                    ban(ctx -> beforeValue(ctx, "publisherId")),
                    passes(ctx -> beforeValue(ctx, "refereeId"), ctx -> beforeValue(ctx, "publisherId"))))
                .compute("Decide", (metadata, ctx) -> decide(ctx))
                // A digital prize from the pool: the pool's lock (after the referrals', before nothing else), then its
                // next code, oldest first, as an attempt's prize takes it.
                .step("Lock the code pool", HoldLock.<ProcessContext>exclusive(ctx -> plan(ctx) != null
                    && plan(ctx).pooled() ? QbDigitalPrizes.poolLockName(UUID.fromString(beforePublication(ctx)
                        .toString())) : null))
                .step("Pick a code", QueryEntities.<ProcessContext>of(
                    QbDigitalPrizes.defaultDataset(QbDigitalPrizes.DIGITAL_CODE), ctx -> EntityQuery.builder()
                        .where(new QueryPredicate.And(List.of(
                            new QueryPredicate.In("publicationId", plan(ctx) != null && plan(ctx).pooled()
                                ? List.of(UUID.fromString(beforePublication(ctx).toString())) : List.of()),
                            new QueryPredicate.Eq("status", DigitalPrizeStatus.CODE_AVAILABLE))))
                        .orderBy("seq", true).limit(1).build(), POOL_CODE))
                .step("Lock the sponsor's wallet", HoldLock.<ProcessContext>exclusive(ctx -> plan(ctx) != null
                    && plan(ctx).fromWallet() ? QbWallets.lockName(QbWallets.SPONSOR, beforeValue(ctx, "publisherId"))
                    : null))
                .step("Load the sponsor's wallet", QueryEntities.<ProcessContext>of(
                    QbWallets.defaultDataset(QbWallets.WALLET), ctx -> QbWallets.query(QbWallets.SPONSOR,
                        plan(ctx) != null && plan(ctx).fromWallet() ? beforeValue(ctx, "publisherId") : null), WALLET))
                .compute("Settle", (metadata, ctx) -> settle(ctx))
                .step("Post the reward", CallProcess.<ProcessContext>when(ctx -> ctx.contains(POSTING),
                    LedgerProcesses.POST, 1, ctx -> ctx.get(POSTING), "posted"))
                .compute("Record", (metadata, ctx) -> recordReward(ctx))
                .step("Tell the referrer", PublishEvent.<ProcessContext>when(
                    ctx -> QbReferrals.REWARDED.equals(ctx.get(OUTPUT, ReferralOutput.class).status())
                        && ctx.get(OUTPUT, ReferralOutput.class).changed(),
                    REWARDED_EVENT, ctx -> event(first(ctx, REFERRAL_ROW).attributes(), ctx)))
                .step("Tell the sponsor of the short wallet", PublishEvent.<ProcessContext>when(
                    ctx -> ctx.contains(FUNDS_SHORT_EVENT), FUNDS_SHORT_EVENT,
                    ctx -> event(first(ctx, REFERRAL_ROW).attributes(), ctx))));
    }

    static void decide(ProcessContext ctx) {
        EntityInstance referral = first(ctx, REFERRAL_ROW);
        if (referral == null || !QbReferrals.REFERRED.equals(referral.get("status"))) {
            return;
        }
        EntityInstance card = first(ctx, CARD);
        EntityInstance attempt = first(ctx, ATTEMPT);
        Instant referredAt = instant(referral.get("referredAt"));
        Instant expiresAt = instant(referral.get("expiresAt"));
        Instant submitted = attempt == null ? null : instant(attempt.get("submittedAt"));
        if (attempt == null || !ReferralRules.earned(referredAt, expiresAt, submitted,
            Boolean.TRUE.equals(attempt.get("passed")))) {
            // Not earned (yet): the expiry job ends it when its time is up.
            return;
        }
        String voidReason = null;
        if (BanRules.banned(attributes(first(ctx, SPONSOR_BAN)))) {
            voidReason = ReferralRules.SPONSOR_BANNED;
        } else if (card == null) {
            voidReason = ReferralRules.NOT_OPEN;
        } else {
            ReferralPolicy policy = ReferralPolicy.fromJson(card.get("referral"));
            if (policy != null && policy.refereeRules().contains(RefereeRules.NOT_REGULAR)) {
                // D-Q70-12: passed another of the sponsor's publications between the claim and this pass.
                Object publicationId = referral.get("publicationId");
                boolean earlier = list(ctx, PASSES).stream().anyMatch(p ->
                    !String.valueOf((Object) p.get("publicationId")).equals(String.valueOf(publicationId))
                        && instant(p.get("submittedAt")) != null && instant(p.get("submittedAt")).isBefore(submitted));
                if (earlier) {
                    voidReason = ReferralRules.REGULAR;
                }
            }
        }
        ctx.put(PLAN, new Plan(referral, card, attempt, voidReason));
    }

    static void settle(ProcessContext ctx) {
        Plan plan = plan(ctx);
        if (plan == null || !plan.paying()) {
            return;
        }
        EntityInstance referral = plan.referral();
        BigDecimal amount = money(referral.get("amount"));
        String publisherId = String.valueOf((Object) referral.get("publisherId"));
        String referrerId = (String) referral.get("referrerId");
        if (referrerId == null) {
            // The referrer's account was erased since: nobody to pay.
            ctx.put(PLAN, new Plan(referral, plan.card(), plan.attempt(), ReferralRules.SELF));
            return;
        }
        if (plan.fromWallet()) {
            EntityInstance wallet = first(ctx, WALLET);
            if (!QbWallets.pays(wallet, amount)) {
                // D-Q70-18: waits, the sponsor told once (the notice throttles), until its time is up.
                ctx.put("fundsShortNow", !Boolean.TRUE.equals(referral.get("fundsShort")));
                return;
            }
            QbWallets.change(ctx, wallet, QbWallets.SPONSOR, publisherId, amount.negate(), 0, BigDecimal.ZERO);
            ctx.put(POSTING, QbWallets.referralPosting(ctx.opTime(), String.valueOf(referral.id()), publisherId,
                referrerId, String.valueOf((Object) referral.get("publicationId")), amount));
            return;
        }
        ParamValues params = ctx.get(PARAMS, ParamValues.class);
        EntityInstance card = plan.card();
        ReferralPolicy policy = ReferralPolicy.fromJson(card.get("referral"));
        String description = policy == null || policy.description() == null ? "" : policy.description();
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("attemptId", String.valueOf(referral.id()));
        values.put("sourceKind", RewardSources.REFERRAL);
        values.put("publicationId", UUID.fromString(String.valueOf((Object) referral.get("publicationId"))));
        values.put("publisherKind", referral.get("publisherKind"));
        values.put("publisherId", publisherId);
        values.put("userId", referrerId);
        values.put("amount", amount);
        values.put("code", QbRewards.codeOf(referral.id()));
        values.put("dueAt", ctx.opTime());
        values.put("revision", BigDecimal.ONE);
        switch (plan.method()) {
            case RewardMethod.SPONSOR_CASH -> {
                values.put("status", CashRewardStatus.DUE);
                values.put("payBy", ctx.opTime().plus(Duration.ofDays(QbParams.days(params, QbParams.CASH_PAY_DAYS))));
                ctx.put("record", ctx.changes().insert(QbRewards.CASH_REWARD, values));
            }
            case RewardMethod.OFFLINE_REDEEM -> {
                Instant expires = ctx.opTime().plus(Duration.ofDays(QbParams.days(params, QbParams.REDEEM_DAYS)));
                Instant end = instant(card.get("endAt"));
                values.put("status", RedemptionStatus.DUE);
                values.put("description", description);
                values.put("expiresAt", end != null && end.isAfter(expires) ? end : expires);
                ctx.put("record", ctx.changes().insert(QbRewards.REDEMPTION, values));
            }
            default -> digital(ctx, plan, values, description, params);
        }
    }

    /** A digital prize for the referrer, delivered as the publication's are (D-Q70-19: the same pool). */
    static void digital(ProcessContext ctx, Plan plan, Map<String, Object> values, String description,
        ParamValues params) {
        EntityInstance card = plan.card();
        String delivery = DigitalDelivery.of(card.get("digitalDelivery"));
        values.remove("code");
        values.put("description", description);
        values.put("delivery", delivery == null ? DigitalDelivery.SPONSOR_SENT : delivery);
        EntityInstance code = DigitalDelivery.POOL.equals(delivery) ? first(ctx, POOL_CODE) : null;
        String sealed = code != null ? (String) code.get("sealedContent")
            : DigitalDelivery.SHARED.equals(delivery) ? (String) card.get("sealedDigitalContent") : null;
        if (sealed != null) {
            values.put("status", DigitalPrizeStatus.DELIVERED);
            values.put("sealedContent", sealed);
            values.put("hint", code != null ? code.get("hint") : null);
            values.put("deliveredAt", ctx.opTime());
            values.put("deliveredBy", DigitalPrizeStatus.BY_SYSTEM);
        } else {
            Object cardDays = card.get("digitalSendDays");
            int days = cardDays == null ? QbParams.days(params, QbParams.DIGITAL_SEND_DAYS)
                : new BigDecimal(cardDays.toString()).intValue();
            values.put("status", DigitalPrizeStatus.DUE);
            values.put("sendBy", ctx.opTime().plus(Duration.ofDays(days)));
        }
        ctx.put("record", ctx.changes().insert(QbDigitalPrizes.DIGITAL_PRIZE, values));
        if (code != null) {
            Map<String, Object> issued = new LinkedHashMap<>();
            issued.put("status", DigitalPrizeStatus.CODE_ISSUED);
            issued.put("issuedAttemptId", String.valueOf(plan.referral().id()));
            issued.put("issuedAt", ctx.opTime());
            ctx.changes().update(QbDigitalPrizes.DIGITAL_CODE, code.id(), code.version(), issued);
        }
    }

    static void recordReward(ProcessContext ctx) {
        EntityInstance found = first(ctx, REFERRAL_ROW);
        String id = ctx.get(INPUT, ReferralRef.class).referralId();
        Plan plan = plan(ctx);
        if (plan == null) {
            ctx.put(OUTPUT, new ReferralOutput(id, found == null ? null : (String) found.get("status"), false));
            return;
        }
        EntityInstance referral = plan.referral();
        Map<String, Object> values = new LinkedHashMap<>();
        if (!plan.paying()) {
            values.put("status", QbReferrals.VOID);
            values.put("voidReason", plan.voidReason());
            values.put("endedAt", ctx.opTime());
        } else if (ctx.contains("fundsShortNow")) {
            if (Boolean.TRUE.equals(ctx.get("fundsShortNow"))) {
                values.put("fundsShort", true);
                ctx.put(FUNDS_SHORT_EVENT, true);
            }
        } else {
            values.put("status", QbReferrals.REWARDED);
            values.put("attemptId", String.valueOf(plan.attempt().id()));
            values.put("rewardedAt", ctx.opTime());
            values.put("endedAt", ctx.opTime());
            values.put("fundsShort", false);
            if (ctx.contains("record")) {
                values.put("rewardRecordId", String.valueOf((Object) ctx.get("record")));
            }
            if (ctx.contains("posted")) {
                values.put("ledgerTransactionId", String.valueOf(
                    ctx.get("posted", LedgerProcesses.PostOutput.class).transactionId()));
            }
        }
        if (!values.isEmpty()) {
            ctx.changes().update(QbReferrals.REFERRAL, referral.id(), referral.version(), values);
        }
        String status = values.containsKey("status") ? (String) values.get("status") : (String) referral.get("status");
        ctx.put(OUTPUT, new ReferralOutput(id, status, values.containsKey("status")));
    }

    public static ProcessDefinition<ReferralRef, ReferralOutput, ProcessContext> expire() {
        return ProcessDefinition.define(EXPIRE, 1, ReferralRef.class, ReferralOutput.class, ProcessContext.class,
            pb -> pb
                .description("Ends a referral whose referee did not pass in time, or completed without passing; its "
                    + "place of the quota is free again (system).")
                .permissions(QbPermissions.REFERRAL_SYSTEM)
                .internal()
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put(INPUT, input);
                    return ctx;
                })
                .outputMapper(ctx -> ctx.get(OUTPUT, ReferralOutput.class))
                .step("Find the referral", QueryEntities.<ProcessContext>all(referralRow(BEFORE)))
                .step("Lock the publication's referrals", HoldLock.<ProcessContext>exclusive(
                    ctx -> first(ctx, BEFORE) == null ? null : QbReferrals.lockName(first(ctx, BEFORE)
                        .get("publicationId"))))
                .step("Load the referral and the referee's attempt", QueryEntities.<ProcessContext>all(
                    referralRow(REFERRAL_ROW),
                    attempt(ReferralProcesses::beforePublication, ctx -> beforeValue(ctx, "refereeId"), ATTEMPT)))
                .compute("End it", (metadata, ctx) -> {
                    String id = ctx.get(INPUT, ReferralRef.class).referralId();
                    EntityInstance referral = first(ctx, REFERRAL_ROW);
                    if (referral == null || !QbReferrals.REFERRED.equals(referral.get("status"))) {
                        ctx.put(OUTPUT, new ReferralOutput(id, referral == null ? null : (String) referral.get("status"),
                            false));
                        return;
                    }
                    EntityInstance attempt = first(ctx, ATTEMPT);
                    Instant submitted = attempt == null ? null : instant(attempt.get("submittedAt"));
                    if (!ReferralRules.over(instant(referral.get("referredAt")), instant(referral.get("expiresAt")),
                        submitted, attempt != null && Boolean.TRUE.equals(attempt.get("passed")), ctx.opTime())) {
                        ctx.put(OUTPUT, new ReferralOutput(id, (String) referral.get("status"), false));
                        return;
                    }
                    Map<String, Object> values = new LinkedHashMap<>();
                    values.put("status", QbReferrals.EXPIRED);
                    values.put("endedAt", ctx.opTime());
                    if (attempt != null) {
                        values.put("attemptId", String.valueOf(attempt.id()));
                    }
                    ctx.changes().update(QbReferrals.REFERRAL, referral.id(), referral.version(), values);
                    ctx.put(OUTPUT, new ReferralOutput(id, QbReferrals.EXPIRED, true));
                })
                .step("Tell the referrer", PublishEvent.<ProcessContext>when(
                    ctx -> ctx.get(OUTPUT, ReferralOutput.class).changed(), EXPIRED_EVENT,
                    ctx -> event(first(ctx, REFERRAL_ROW).attributes(), ctx))));
    }

    // ---- the referrer's view ----------------------------------------------------------------------------------------

    static final String CASH = "cash";
    static final String REDEEM = "redeem";
    static final String DIGITAL = "digital";
    static final String SPONSOR = "sponsor";

    public static ProcessDefinition<ViewInput, ReferralView, ProcessContext> view() {
        return ProcessDefinition.define(VIEW, 1, ViewInput.class, ReferralView.class, ProcessContext.class, pb -> pb
            .description("Reads one of the caller's referrals as its referrer: the quiz, what came of it and the "
                + "reward, with the record of a reward the sponsor settles themselves (read only).")
            .permissions(QbPermissions.ME)
            .contextFactory((start, input) -> {
                ProcessContext ctx = new ProcessContext(start);
                ctx.put(INPUT, input);
                return ctx;
            })
            .outputMapper(ctx -> ctx.get(OUTPUT, ReferralView.class))
            // Through the referrer's own dataset: another's referral is not found (404).
            .step("Load the referral", QueryEntities.<ProcessContext>of(QbReferrals.REFERRER_DATASET,
                ctx -> EntityQuery.builder().where(new QueryPredicate.In("referralId",
                    List.of(ctx.get(INPUT, ViewInput.class).referralId()))).limit(1).build(), REFERRAL_ROW))
            .compute("Refuse another's", (metadata, ctx) -> {
                if (first(ctx, REFERRAL_ROW) == null) {
                    throw new EntityNotFoundException(QbReferrals.REFERRAL + " not found");
                }
            })
            // One statement: the card and the reward's record (its attemptId is the referral, D-Q70-20), through
            // the winner's own datasets, and the sponsor's company.
            .step("Load the card and the reward", QueryEntities.<ProcessContext>all(
                card(ctx -> first(ctx, REFERRAL_ROW).get("publicationId"), CARD),
                QueryEntities.query(QbRewards.takerDataset(QbRewards.CASH_REWARD), ctx -> byReferral(ctx,
                    RewardMethod.SPONSOR_CASH), CASH),
                QueryEntities.query(QbRewards.takerDataset(QbRewards.REDEMPTION), ctx -> byReferral(ctx,
                    RewardMethod.OFFLINE_REDEEM), REDEEM),
                QueryEntities.query(QbDigitalPrizes.takerDataset(QbDigitalPrizes.DIGITAL_PRIZE), ctx -> byReferral(ctx,
                    RewardMethod.DIGITAL_PRIZE), DIGITAL),
                QueryEntities.query(QbAccounts.defaultDataset(QbAccounts.SPONSOR), ctx -> EntityQuery.builder()
                    .where(new QueryPredicate.In("userId", List.of(String.valueOf((Object) first(ctx, REFERRAL_ROW)
                        .get("publisherId"))))).limit(1).build(), SPONSOR)))
            .compute("Describe it", (metadata, ctx) -> {
                EntityInstance r = first(ctx, REFERRAL_ROW);
                EntityInstance card = first(ctx, CARD);
                EntityInstance sponsor = first(ctx, SPONSOR);
                String company = sponsor == null ? null : (String) sponsor.get("company");
                ctx.put(OUTPUT, new ReferralView(String.valueOf(r.id()), String.valueOf((Object) r.get("publicationId")),
                    card == null ? null : (String) card.get("name"), (String) r.get("status"),
                    instant(r.get("referredAt")), instant(r.get("expiresAt")), (String) r.get("rewardMethod"),
                    money(r.get("amount")), instant(r.get("rewardedAt")), instant(r.get("endedAt")),
                    com.jabiz.quizbuks.play.AttemptResults.cash(first(ctx, CASH), company),
                    com.jabiz.quizbuks.play.AttemptResults.redeem(first(ctx, REDEEM), company),
                    com.jabiz.quizbuks.play.AttemptResults.digital(first(ctx, DIGITAL), company)));
            }));
    }

    /** The reward record of the referral when its method is {@code method} (none otherwise: no statement). */
    static EntityQuery byReferral(ProcessContext ctx, String method) {
        EntityInstance r = first(ctx, REFERRAL_ROW);
        boolean wanted = method.equals(r.get("rewardMethod")) && QbReferrals.REWARDED.equals(r.get("status"));
        return EntityQuery.builder().where(new QueryPredicate.In("attemptId",
            wanted ? List.of(String.valueOf(r.id())) : List.of())).limit(1).build();
    }

    // ---- reads ----------------------------------------------------------------------------------------------------

    /** The referral the input names, read into {@code key}. */
    static QueryEntities.Metadata<ProcessContext> referralRow(String key) {
        return QueryEntities.query(QbReferrals.defaultDataset(QbReferrals.REFERRAL),
            ctx -> EntityQuery.builder().where(new QueryPredicate.In("referralId",
                List.of(ctx.get(INPUT, ReferralRef.class).referralId()))).limit(1).build(), key);
    }

    /** The card of a publication (none: nothing). */
    static QueryEntities.Metadata<ProcessContext> card(java.util.function.Function<ProcessContext, Object> publicationId,
        String key) {
        return QueryEntities.query(QbPublications.defaultDataset(QbPublications.CARD), ctx -> {
            Object id = publicationId.apply(ctx);
            return EntityQuery.builder().where(new QueryPredicate.In("publicationId",
                id == null ? List.of() : List.of(UUID.fromString(id.toString())))).limit(1).build();
        }, key);
    }

    /** {@code userId}'s attempt of the publication (one at most). */
    static QueryEntities.Metadata<ProcessContext> attempt(
        java.util.function.Function<ProcessContext, Object> publicationId,
        java.util.function.Function<ProcessContext, Object> userId, String key) {
        return QueryEntities.query(QbPublications.defaultDataset(QbPlay.ATTEMPT), ctx -> {
            Object publication = publicationId.apply(ctx);
            Object user = userId.apply(ctx);
            if (publication == null || user == null) {
                return EntityQuery.builder().where(new QueryPredicate.In("userId", List.of())).limit(1).build();
            }
            return EntityQuery.builder().where(new QueryPredicate.And(List.of(
                new QueryPredicate.Eq("userId", String.valueOf(user)),
                new QueryPredicate.In("publicationId", List.of(UUID.fromString(publication.toString()))))))
                .limit(1).build();
        }, key);
    }

    /** The publication's referrals that hold a place of its quota (D-Q70-15). */
    static QueryEntities.Metadata<ProcessContext> counted(
        java.util.function.Function<ProcessContext, Object> publicationId) {
        return QueryEntities.query(QbReferrals.defaultDataset(QbReferrals.REFERRAL), ctx -> {
            Object id = publicationId.apply(ctx);
            return EntityQuery.builder().where(new QueryPredicate.And(List.of(
                new QueryPredicate.In("publicationId", id == null ? List.of() : List.of(String.valueOf(id))),
                new QueryPredicate.In("status", List.copyOf(QbReferrals.COUNTED)))))
                .limit(ReferralPolicy.MAX_QUOTA).build();
        }, COUNTED);
    }

    static QueryEntities.Metadata<ProcessContext> ban(java.util.function.Function<ProcessContext, Object> userId) {
        return QueryEntities.query(QbAccounts.defaultDataset(QbAccounts.BAN), ctx -> EntityQuery.builder()
            .where(new QueryPredicate.In("userId", AccountEditing.uuids(userId.apply(ctx)))).limit(1).build(),
            SPONSOR_BAN);
    }

    /** {@code userId}'s passes of the sponsor's publications (D-Q70-12, {@link RefereeRules#NOT_REGULAR}). */
    static QueryEntities.Metadata<ProcessContext> passes(java.util.function.Function<ProcessContext, Object> userId,
        java.util.function.Function<ProcessContext, Object> publisherId) {
        return QueryEntities.query(QbPublications.defaultDataset(QbPlay.ATTEMPT), ctx -> {
            Object user = userId.apply(ctx);
            Object publisher = publisherId.apply(ctx);
            if (user == null || publisher == null) {
                return EntityQuery.builder().where(new QueryPredicate.In("userId", List.of())).limit(1).build();
            }
            return EntityQuery.builder().where(new QueryPredicate.And(List.of(
                new QueryPredicate.Eq("userId", String.valueOf(user)),
                new QueryPredicate.Eq("publisherId", String.valueOf(publisher)),
                new QueryPredicate.Eq("passed", true)))).limit(MAX_PASSES).build();
        }, PASSES);
    }

    static List<Object> profileIds(ProcessContext ctx) {
        Object referrer = first(ctx, LINK_ROW).get("referrerId");
        return referrer == null ? List.of(ctx.request().actorId()) : List.of(ctx.request().actorId(), referrer);
    }

    static EntityInstance profile(ProcessContext ctx, Object userId) {
        return userId == null ? null : list(ctx, PROFILES).stream()
            .filter(p -> String.valueOf(userId).equals(String.valueOf((Object) p.get("userId")))).findFirst()
            .orElse(null);
    }

    static String country(ProcessContext ctx, Object userId) {
        EntityInstance profile = profile(ctx, userId);
        return profile == null ? null : (String) profile.get("countryCode");
    }

    // ---- helpers --------------------------------------------------------------------------------------------------

    static Object publication(ProcessContext ctx) {
        return ctx.get(INPUT, LinkInput.class).publicationId();
    }

    static Object linkPublication(ProcessContext ctx) {
        EntityInstance link = first(ctx, LINK_ROW);
        return link == null ? null : link.get("publicationId");
    }

    static Object beforePublication(ProcessContext ctx) {
        return beforeValue(ctx, "publicationId");
    }

    static Object beforeValue(ProcessContext ctx, String field) {
        EntityInstance before = first(ctx, BEFORE);
        return before == null ? null : before.get(field);
    }

    static Object cardValue(ProcessContext ctx, String field) {
        EntityInstance card = first(ctx, CARD);
        return card == null ? null : card.get(field);
    }

    static Plan plan(ProcessContext ctx) {
        return ctx.contains(PLAN) ? ctx.get(PLAN, Plan.class) : null;
    }

    static boolean accepting(EntityInstance card, Instant now) {
        return PublicationRules.acceptsNewAttempts(card.get("status"), instant(card.get("startAt")),
            instant(card.get("endAt")), now);
    }

    static BusinessRuleViolationException refusal(String code, String message) {
        return refusal(code, message, Map.of());
    }

    static BusinessRuleViolationException refusal(String code, String message, Map<String, Object> params) {
        return new BusinessRuleViolationException(new Violation("publicationId", code, message, params));
    }

    /** The events' payload: the referral and its people (the consumers read the referral again). */
    static Map<String, Object> event(Map<String, Object> referral) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("referralId", String.valueOf(referral.get("referralId")));
        payload.put("publicationId", String.valueOf(referral.get("publicationId")));
        payload.put("referrerId", referral.get("referrerId"));
        payload.put("publisherId", referral.get("publisherId"));
        return payload;
    }

    static Map<String, Object> event(Map<String, Object> referral, ProcessContext ctx) {
        Map<String, Object> values = new LinkedHashMap<>(referral);
        values.put("referralId", ctx.get(INPUT, ReferralRef.class).referralId());
        return event(values);
    }

    static Map<String, Object> attributes(EntityInstance row) {
        return row == null ? null : row.attributes();
    }

    @SuppressWarnings("unchecked")
    static List<EntityInstance> list(ProcessContext ctx, String key) {
        Object found = ctx.contains(key) ? ctx.get(key) : null;
        return found instanceof List<?> rows ? (List<EntityInstance>) rows : List.of();
    }

    static EntityInstance first(ProcessContext ctx, String key) {
        List<EntityInstance> rows = list(ctx, key);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    static Instant instant(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Instant i) {
            return i;
        }
        if (value instanceof OffsetDateTime o) {
            return o.toInstant();
        }
        return Instant.parse(value.toString());
    }

    static BigDecimal money(Object value) {
        return value == null ? BigDecimal.ZERO : new BigDecimal(value.toString());
    }

    private ReferralProcesses() {}
}
