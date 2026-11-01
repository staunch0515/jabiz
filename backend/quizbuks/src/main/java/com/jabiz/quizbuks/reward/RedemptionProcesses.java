package com.jabiz.quizbuks.reward;

import com.jabiz.entity.Violation;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.process.ProcessDefinitionBuilder;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.publishing.PublisherKind;
import com.jabiz.quizbuks.identity.CallerChecks;
import com.jabiz.quizbuks.identity.QbAccounts;
import com.jabiz.quizbuks.publishing.QbPublications;
import com.jabiz.quizbuks.operator.OperatorChecks;
import com.jabiz.runtime.BusinessRuleViolationException;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.process.steps.HoldLock;
import com.jabiz.runtime.process.steps.LoadEntity;
import com.jabiz.runtime.process.steps.PublishEvent;
import com.jabiz.runtime.process.steps.QueryEntities;
import com.jabiz.runtime.security.RandomBytesStep;
import com.jabiz.security.Sensitive;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Collecting a prize at the sponsor's place (docs/quizbuks/plans/Q22-reward-methods.md, Q22b). The sponsor shows a
 * code the taker scans with the phone's camera; the code is an App link carrying a token of the sponsor's:
 * <ul>
 *   <li>{@code QB_REDEEM_CODE_ISSUE} (sponsor): a rotating token, good for {@link QbRewards#ROTATING_TOKEN_LIFE} (the
 *       console asks again every minute, so the code on the screen is always the sponsor's own now), or the sponsor's
 *       printed token, lasting until they replace it; the expired rotating ones are dropped along the way;</li>
 *   <li>{@code QB_REDEEM_TOKEN_CHECK} (taker): what the scanned code stands for: the sponsor's company and the
 *       caller's prizes to collect there;</li>
 *   <li>{@code QB_REDEMPTION_CLAIM} (taker): collects one of them. A rotating code is shown by the sponsor to the
 *       taker in person, so the prize is redeemed at once, a claim made by the printed code and still waiting
 *       included; a printed code could be scanned anywhere, so the claim waits for the sponsor ({@code CLAIMED},
 *       {@link QbRewards#PRINTED_CLAIM_LIFE});</li>
 *   <li>{@code QB_REDEMPTION_CONFIRM} / {@code QB_REDEMPTION_DECLINE} (sponsor): the claim is the person at the
 *       counter, or is not (due again);</li>
 *   <li>{@code QB_REDEMPTION_VOID} (administrator): a prize that should not stand;</li>
 *   <li>{@code QB_REDEMPTION_EXPIRE} and {@code QB_REDEMPTION_LAPSE}, items of the jobs {@value #EXPIRE_JOB} (daily:
 *       prizes past their time) and {@value #LAPSE_JOB} (every ten minutes: claims the sponsor did not confirm in
 *       time are due again). The processes do not wait for the jobs: a claim or confirmation past these times is
 *       refused as it would be after them ({@link #effectiveStatus}).</li>
 * </ul>
 * Each takes the redemption's lock before reading it; the sponsor's and the taker's read it through their own
 * datasets (another's is 404). The token's value is never read back through the datasets, only matched here.
 */
public final class RedemptionProcesses {

    public static final String ISSUE = "QB_REDEEM_CODE_ISSUE";
    public static final String CHECK = "QB_REDEEM_TOKEN_CHECK";
    public static final String CLAIM = "QB_REDEMPTION_CLAIM";
    public static final String CONFIRM = "QB_REDEMPTION_CONFIRM";
    public static final String DECLINE = "QB_REDEMPTION_DECLINE";
    public static final String VOID = "QB_REDEMPTION_VOID";
    public static final String EXPIRE = "QB_REDEMPTION_EXPIRE";
    public static final String LAPSE = "QB_REDEMPTION_LAPSE";

    public static final String EXPIRE_JOB = "qb.redemption-expire";
    public static final String LAPSE_JOB = "qb.redemption-lapse";
    /** The prizes still to collect whose time ran out before a cutoff. */
    public static final String EXPIRED_TEMPLATE = "qb.system.redemptions-expired";
    /** The claims from a printed code the sponsor did not confirm before a cutoff. */
    public static final String LAPSED_TEMPLATE = "qb.system.redemption-claims-lapsed";

    /** Events for the messages (Q7): the other side learns of each step. */
    public static final String CLAIMED_EVENT = "qb.redemption.claimed";
    public static final String REDEEMED_EVENT = "qb.redemption.redeemed";
    public static final String DECLINED_EVENT = "qb.redemption.declined";
    public static final String EXPIRED_EVENT = "qb.redemption.expired";
    public static final String VOIDED_EVENT = "qb.redemption.voided";

    /** @param kind ROTATING or PRINTED; @param renew true replaces the printed code (the old one stops working) */
    public record IssueInput(@NotBlank @Size(max = 10) String kind, Boolean renew) {}

    /**
     * @param redeemKey the secret in the code's link, returned to the sponsor's console alone (its name is not among
     *                  the masked ones, unlike the entity's and the inputs', so the answer carries it)
     * @param expiresAt when a rotating code stops working (null: printed)
     */
    public record IssueOutput(String redeemKey, String kind, Instant issuedAt, Instant expiresAt) {

        @Override
        public String toString() {
            return "IssueOutput[redeemKey=***, kind=" + kind + ", issuedAt=" + issuedAt + ", expiresAt=" + expiresAt
                + "]";
        }
    }

    /** @param scannedKey the secret from the code the taker scanned; masked in the operation's record */
    public record TokenInput(@Sensitive @NotBlank @Size(max = QbRewards.TOKEN_LENGTH) String scannedKey) {

        @Override
        public String toString() {
            return "TokenInput[scannedKey=***]";
        }
    }

    /**
     * A prize the caller may collect at the sponsor's place; {@code attemptId} leads to its result, unless
     * {@code sourceKind} says a referral earned it (Q70: then it is the referral's id, and there is no result).
     */
    public record Prize(UUID redemptionId, String attemptId, String code, BigDecimal amount, String description,
        String publicationName, String status, Instant expiresAt, String sourceKind) {}

    /** @param kind the code's kind: a rotating one redeems at once, a printed one makes a claim to confirm */
    public record CheckOutput(String sponsorCompany, String kind, List<Prize> prizes) {}

    public record ClaimInput(@Sensitive @NotBlank @Size(max = QbRewards.TOKEN_LENGTH) String scannedKey,
        @NotNull UUID redemptionId) {

        @Override
        public String toString() {
            return "ClaimInput[scannedKey=***, redemptionId=" + redemptionId + "]";
        }
    }

    public record RedemptionRef(@NotNull UUID redemptionId) {}

    public record VoidInput(@NotNull UUID redemptionId, @Size(max = 500) String note) {}

    public record RedemptionOutput(UUID redemptionId, String status, long revision) {}

    static final String INPUT = "input";
    static final String ID = "redemptionId";
    static final String REDEMPTION = "redemption";
    static final String TOKEN = "token";
    static final String TOKENS = "tokens";
    static final String PRINTED = "printed";
    static final String PRIZES = "prizes";
    static final String CARDS = "cards";
    static final String SPONSOR_ID = "sponsorId";
    static final String SPONSOR = "sponsor";
    static final String OUTPUT = "output";
    static final String CHANGED = "changed";

    static final String RANDOM = "random";
    static final int TOKEN_BYTES = 24;

    static ProcessContext start(com.jabiz.process.ProcessStart start, Object input, UUID id) {
        ProcessContext ctx = new ProcessContext(start);
        ctx.put(INPUT, input);
        if (id != null) {
            ctx.put(ID, id);
        }
        return ctx;
    }

    // ---- the sponsor's codes -----------------------------------------------------------------------------------------

    public static ProcessDefinition<IssueInput, IssueOutput, ProcessContext> issue() {
        return ProcessDefinition.define(ISSUE, 1, IssueInput.class, IssueOutput.class, ProcessContext.class, pb -> pb
            .description("Issues the code a sponsor shows or prints for takers to collect their prizes by.")
            .permissions(QbPermissions.PUBLICATION_WRITE)
            .requiresVerifiedEmail()
            .steps(CallerChecks::refuseBanned)
            .contextFactory((s, input) -> start(s, input, null))
            .outputMapper(ctx -> ctx.get(OUTPUT, IssueOutput.class))
            // The platform's blocking step: SecureRandom may wait for entropy, never on an event loop.
            .step("Draw the token", RandomBytesStep.class, new RandomBytesStep.Metadata(RANDOM, TOKEN_BYTES))
            .step("Lock the sponsor's codes", HoldLock.<ProcessContext>exclusive(
                ctx -> QbRewards.tokenLockName(ctx.request().actorId())))
            // The sponsor's codes that matter: the printed one in force (to return or replace) and the rotating ones
            // already expired (to drop); each asked for by itself, so neither hides behind a flood of the other.
            .step("Load the sponsor's codes", QueryEntities.<ProcessContext>all(
                QueryEntities.query(QbRewards.sponsorDataset(QbRewards.REDEEM_TOKEN), ctx -> EntityQuery.builder()
                    .where(new QueryPredicate.And(List.of(
                        new QueryPredicate.In("sponsorId", List.of(ctx.request().actorId())),
                        new QueryPredicate.Eq("kind", RedemptionStatus.KIND_PRINTED),
                        new QueryPredicate.IsNull("revokedAt"))))
                    .limit(10).build(), PRINTED),
                QueryEntities.query(QbRewards.sponsorDataset(QbRewards.REDEEM_TOKEN), ctx -> EntityQuery.builder()
                    .where(new QueryPredicate.And(List.of(
                        new QueryPredicate.In("sponsorId", List.of(ctx.request().actorId())),
                        new QueryPredicate.Eq("kind", RedemptionStatus.KIND_ROTATING),
                        new QueryPredicate.Lt("expiresAt", ctx.opTime()))))
                    .limit(500).build(), TOKENS)))
            .compute("Issue it", (metadata, ctx) -> issue(ctx, ctx.request().actorId())));
    }

    /**
     * Issues the code {@link #INPUT} asks for, as the sponsor {@code sponsorId}'s; the caller is recorded as its issuer
     * (the sponsor, or one of their staff, Q39). Drops the expired rotating codes loaded under {@link #TOKENS}.
     */
    static void issue(ProcessContext ctx, String sponsorId) {
        IssueInput input = ctx.get(INPUT, IssueInput.class);
        if (!RedemptionStatus.KINDS.contains(input.kind())) {
            ctx.reject(new Violation("kind", RewardCodes.REDEEM_TOKEN_KIND, "Unknown kind " + input.kind(),
                Map.of("kind", input.kind())));
            return;
        }
        Instant now = ctx.opTime();
        for (EntityInstance token : list(ctx, TOKENS)) {
            ctx.changes().delete(QbRewards.REDEEM_TOKEN, token.id(), token.version());
        }
        EntityInstance printed = first(ctx, PRINTED);
        if (RedemptionStatus.KIND_PRINTED.equals(input.kind()) && printed != null) {
            if (!Boolean.TRUE.equals(input.renew())) {
                ctx.put(OUTPUT, new IssueOutput(printed.get("tokenValue"), RedemptionStatus.KIND_PRINTED,
                    instant(printed.get("issuedAt")), null));
                return;
            }
            // Replaced: the posters carrying the old one stop working.
            ctx.changes().update(QbRewards.REDEEM_TOKEN, printed.id(), printed.version(), Map.of("revokedAt", now));
        }
        boolean rotating = RedemptionStatus.KIND_ROTATING.equals(input.kind());
        Instant expiresAt = rotating ? now.plus(QbRewards.ROTATING_TOKEN_LIFE) : null;
        String token = tokenOf(ctx.get(RANDOM, byte[].class));
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("sponsorId", sponsorId);
        values.put("tokenValue", token);
        values.put("kind", input.kind());
        values.put("issuedAt", now);
        values.put("expiresAt", expiresAt);
        values.put("issuedBy", ctx.request().actorId());
        ctx.changes().insert(QbRewards.REDEEM_TOKEN, values);
        ctx.put(OUTPUT, new IssueOutput(token, input.kind(), now, expiresAt));
    }

    /** 192 random bits, URL-safe: the secret in the QR's link. */
    static String tokenOf(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    // ---- the taker's scan --------------------------------------------------------------------------------------------

    /** Loads the scanned token (any sponsor's) under {@link #TOKEN}; the compute steps refuse one no longer good. */
    private static <I, O> void loadToken(ProcessDefinitionBuilder<I, O, ProcessContext> pb) {
        pb.step("Find the code scanned", QueryEntities.<ProcessContext>of(
            QbRewards.defaultDataset(QbRewards.REDEEM_TOKEN), ctx -> EntityQuery.builder()
                .where(new QueryPredicate.In("tokenValue", List.of(tokenOf(ctx)))).limit(1).build(), TOKEN));
    }

    private static String tokenOf(ProcessContext ctx) {
        Object input = ctx.get(INPUT);
        return input instanceof TokenInput check ? check.scannedKey() : ((ClaimInput) input).scannedKey();
    }

    /** The token scanned, if it is a sponsor's now; otherwise 422 {@code QB_REDEEM_TOKEN_INVALID}. */
    static EntityInstance validToken(ProcessContext ctx) {
        EntityInstance token = first(ctx, TOKEN);
        Instant expires = token == null ? null : instant(token.get("expiresAt"));
        if (token == null || token.get("revokedAt") != null || expires != null && !expires.isAfter(ctx.opTime())) {
            throw new BusinessRuleViolationException(new Violation("scannedKey", RewardCodes.REDEEM_TOKEN_INVALID,
                "The code is not a sponsor's now", Map.of()));
        }
        return token;
    }

    public static ProcessDefinition<TokenInput, CheckOutput, ProcessContext> check() {
        return ProcessDefinition.define(CHECK, 1, TokenInput.class, CheckOutput.class, ProcessContext.class, pb -> {
            pb.description("What a scanned code stands for: the sponsor and the caller's prizes to collect there.")
                .permissions(QbPermissions.ME)
                .requiresVerifiedEmail()
                .steps(CallerChecks::refuseBanned)
                .contextFactory((s, input) -> start(s, input, null))
                .outputMapper(ctx -> ctx.get(OUTPUT, CheckOutput.class));
            loadToken(pb);
            pb.compute("Check the code", (metadata, ctx) -> ctx.put(SPONSOR_ID, validToken(ctx).get("sponsorId")))
                // The caller's open prizes with that sponsor and the sponsor's company: one statement.
                .step("Load the prizes", QueryEntities.<ProcessContext>all(
                    QueryEntities.query(QbRewards.takerDataset(QbRewards.REDEMPTION), ctx -> EntityQuery.builder()
                        .where(new QueryPredicate.And(List.of(
                            new QueryPredicate.In("userId", List.of(ctx.request().actorId())),
                            new QueryPredicate.Eq("publisherKind", PublisherKind.SPONSOR),
                            new QueryPredicate.In("publisherId", List.of(ctx.get(SPONSOR_ID))),
                            new QueryPredicate.In("status", List.<Object>copyOf(RedemptionStatus.OPEN)))))
                        .limit(50).build(), PRIZES),
                    QueryEntities.query(QbAccounts.defaultDataset(QbAccounts.SPONSOR), ctx -> EntityQuery.builder()
                        .where(new QueryPredicate.In("userId", List.of(ctx.get(SPONSOR_ID)))).limit(1).build(),
                        SPONSOR)))
                .step("Load their publications", QueryEntities.<ProcessContext>of(
                    QbPublications.defaultDataset(QbPublications.CARD), ctx -> EntityQuery.builder()
                        .where(new QueryPredicate.In("publicationId", list(ctx, PRIZES).stream()
                            .map(prize -> (Object) uuid(prize.get("publicationId"))).distinct().toList()))
                        .limit(50).build(), CARDS))
                .compute("Describe them", (metadata, ctx) -> {
                    EntityInstance token = first(ctx, TOKEN);
                    EntityInstance sponsor = first(ctx, SPONSOR);
                    Map<String, String> names = new LinkedHashMap<>();
                    for (EntityInstance card : list(ctx, CARDS)) {
                        names.put(String.valueOf(card.id()), card.get("name"));
                    }
                    List<Prize> prizes = new ArrayList<>();
                    for (EntityInstance redemption : list(ctx, PRIZES)) {
                        // A claim nobody confirmed in time is due again, and a prize past its time is gone, whatever
                        // the jobs have done so far.
                        String status = effectiveStatus(redemption, ctx.opTime());
                        if (RedemptionStatus.EXPIRED.equals(status)) {
                            continue;
                        }
                        prizes.add(new Prize(uuid(redemption.id()), redemption.get("attemptId"), redemption.get("code"),
                            new BigDecimal(String.valueOf((Object) redemption.get("amount"))),
                            redemption.get("description"),
                            names.get(String.valueOf((Object) redemption.get("publicationId"))), status,
                            instant(redemption.get("expiresAt")),
                            RewardSources.referral(redemption.get("sourceKind")) ? RewardSources.REFERRAL
                                : RewardSources.ATTEMPT));
                    }
                    ctx.put(OUTPUT, new CheckOutput(sponsor == null ? null : sponsor.get("company"),
                        token.get("kind"), List.copyOf(prizes)));
                });
        });
    }

    /**
     * Where the redemption stands now, ahead of the jobs: an open prize past its time to collect is expired; a claim
     * past its time to confirm is due again.
     */
    static String effectiveStatus(EntityInstance redemption, Instant now) {
        String status = redemption.get("status");
        Instant expires = instant(redemption.get("expiresAt"));
        if (RedemptionStatus.OPEN.contains(status) && expires != null && !expires.isAfter(now)) {
            return RedemptionStatus.EXPIRED;
        }
        Instant confirmBy = instant(redemption.get("confirmBy"));
        return RedemptionStatus.CLAIMED.equals(status) && confirmBy != null && !confirmBy.isAfter(now)
            ? RedemptionStatus.DUE : status;
    }

    public static ProcessDefinition<ClaimInput, RedemptionOutput, ProcessContext> claim() {
        return ProcessDefinition.define(CLAIM, 1, ClaimInput.class, RedemptionOutput.class, ProcessContext.class,
            pb -> {
                pb.description("The taker collects a prize by the sponsor's code: redeemed at once by the console's "
                        + "code, claimed for the sponsor to confirm by a printed one.")
                    .permissions(QbPermissions.ME)
                    .requiresVerifiedEmail()
                    .steps(CallerChecks::refuseBanned)
                    .contextFactory((s, input) -> start(s, input, input.redemptionId()))
                    .outputMapper(ctx -> ctx.get(OUTPUT, RedemptionOutput.class));
                lockAndLoad(pb, QbRewards.takerDataset(QbRewards.REDEMPTION));
                loadToken(pb);
                pb.compute("Claim it", (metadata, ctx) -> claim(ctx))
                    .step("Tell the sponsor", PublishEvent.<ProcessContext>when(
                        ctx -> changed(ctx) && RedemptionStatus.CLAIMED.equals(status(ctx)), CLAIMED_EVENT,
                        RedemptionProcesses::payload))
                    .step("Tell the taker", PublishEvent.<ProcessContext>when(
                        ctx -> changed(ctx) && RedemptionStatus.REDEEMED.equals(status(ctx)), REDEEMED_EVENT,
                        RedemptionProcesses::payload));
            });
    }

    static void claim(ProcessContext ctx) {
        EntityInstance redemption = redemption(ctx);
        EntityInstance token = validToken(ctx);
        if (!PublisherKind.sponsor(redemption.get("publisherKind")) || !String.valueOf((Object) token.get("sponsorId"))
            .equals(String.valueOf((Object) redemption.get("publisherId")))) {
            ctx.reject(new Violation("scannedKey", RewardCodes.REDEEM_TOKEN_SPONSOR, "The prize is another sponsor's",
                Map.of()));
            return;
        }
        boolean rotating = RedemptionStatus.KIND_ROTATING.equals(token.get("kind"));
        // A rotating code redeems in the name of whoever asked for it (codes from before Q39: the sponsor).
        Object issuedBy = token.get("issuedBy");
        String redeemedBy = String.valueOf(issuedBy != null ? issuedBy : token.get("sponsorId"));
        if (rotating && redeemedBy.equals(ctx.request().actorId())) {
            // Q39: a staff member does not hand a prize to themselves.
            ctx.reject(new Violation("scannedKey", RewardCodes.STAFF_OWN_PRIZE,
                "The code is the caller's own: someone else hands this prize over", Map.of()));
            return;
        }
        // A rotating code proves the taker is at the counter: it also hands over a prize whose printed-code claim
        // still waits for the sponsor (the taker need not wait for its confirmation or its lapse).
        String status = effectiveStatus(redemption, ctx.opTime());
        if (rotating ? !in(ctx, status, RedemptionStatus.DUE, RedemptionStatus.CLAIMED)
            : !in(ctx, status, RedemptionStatus.DUE)) {
            return;
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("claimKind", token.get("kind"));
        values.put("claimedAt", ctx.opTime());
        if (rotating) {
            values.put("status", RedemptionStatus.REDEEMED);
            values.put("redeemedAt", ctx.opTime());
            values.put("redeemedBy", redeemedBy);
            values.put("confirmBy", null);
        } else {
            values.put("status", RedemptionStatus.CLAIMED);
            values.put("confirmBy", ctx.opTime().plus(QbRewards.PRINTED_CLAIM_LIFE));
        }
        write(ctx, redemption, values);
    }

    // ---- the sponsor's answer to a claim -----------------------------------------------------------------------------

    public static ProcessDefinition<RedemptionRef, RedemptionOutput, ProcessContext> confirm() {
        return ProcessDefinition.define(CONFIRM, 1, RedemptionRef.class, RedemptionOutput.class,
            ProcessContext.class, pb -> {
                pb.description("The sponsor confirms a claim made by their printed code: the prize is collected.")
                    .permissions(QbPermissions.PUBLICATION_WRITE)
                    .requiresVerifiedEmail()
                    .steps(CallerChecks::refuseBanned)
                    .actsOn(QbRewards.REDEMPTION, "redemptionId", a -> a.whenField("status", RedemptionStatus.CLAIMED))
                    .contextFactory((s, input) -> start(s, input, input.redemptionId()))
                    .outputMapper(ctx -> ctx.get(OUTPUT, RedemptionOutput.class));
                lockAndLoad(pb, QbRewards.sponsorDataset(QbRewards.REDEMPTION));
                pb.compute("Confirm", (metadata, ctx) -> confirm(ctx))
                    .step("Tell the taker", PublishEvent.<ProcessContext>when(RedemptionProcesses::changed,
                        REDEEMED_EVENT, RedemptionProcesses::payload));
            });
    }

    /** Confirms the claim loaded under {@link #REDEMPTION}: collected, by the caller. */
    static void confirm(ProcessContext ctx) {
        EntityInstance redemption = redemption(ctx);
        String status = effectiveStatus(redemption, ctx.opTime());
        if (RedemptionStatus.DUE.equals(status)) {
            // Claimed, but the sponsor took too long: the taker scans again.
            ctx.reject(new Violation("redemptionId", RewardCodes.REDEMPTION_CLAIM_LAPSED,
                "The claim lapsed: the taker scans again", Map.of()));
            return;
        }
        if (!in(ctx, status, RedemptionStatus.CLAIMED)) {
            return;
        }
        Instant confirmBy = instant(redemption.get("confirmBy"));
        if (confirmBy != null && !confirmBy.isAfter(ctx.opTime())) {
            ctx.reject(new Violation("redemptionId", RewardCodes.REDEMPTION_CLAIM_LAPSED,
                "The claim lapsed: the taker scans again", Map.of()));
            return;
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("status", RedemptionStatus.REDEEMED);
        values.put("redeemedAt", ctx.opTime());
        values.put("redeemedBy", ctx.request().actorId());
        write(ctx, redemption, values);
    }

    /** Declines the claim loaded under {@link #REDEMPTION}: the prize is due again. */
    static void decline(ProcessContext ctx) {
        EntityInstance redemption = redemption(ctx);
        if (in(ctx, redemption.get("status"), RedemptionStatus.CLAIMED)) {
            write(ctx, redemption, dueAgain());
        }
    }

    public static ProcessDefinition<RedemptionRef, RedemptionOutput, ProcessContext> decline() {
        return ProcessDefinition.define(DECLINE, 1, RedemptionRef.class, RedemptionOutput.class,
            ProcessContext.class, pb -> {
                pb.description("The sponsor declines a claim made by their printed code: the prize is due again.")
                    .permissions(QbPermissions.PUBLICATION_WRITE)
                    .requiresVerifiedEmail()
                    .steps(CallerChecks::refuseBanned)
                    .actsOn(QbRewards.REDEMPTION, "redemptionId", a -> a.whenField("status", RedemptionStatus.CLAIMED))
                    .contextFactory((s, input) -> start(s, input, input.redemptionId()))
                    .outputMapper(ctx -> ctx.get(OUTPUT, RedemptionOutput.class));
                lockAndLoad(pb, QbRewards.sponsorDataset(QbRewards.REDEMPTION));
                pb.compute("Decline", (metadata, ctx) -> decline(ctx))
                    .step("Tell the taker", PublishEvent.<ProcessContext>when(RedemptionProcesses::changed,
                        DECLINED_EVENT, RedemptionProcesses::payload));
            });
    }

    /** The values that undo a claim. */
    static Map<String, Object> dueAgain() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("status", RedemptionStatus.DUE);
        values.put("claimKind", null);
        values.put("claimedAt", null);
        values.put("confirmBy", null);
        return values;
    }

    // ---- void (administrator) ----------------------------------------------------------------------------------------

    public static ProcessDefinition<VoidInput, RedemptionOutput, ProcessContext> voidPrize() {
        return ProcessDefinition.define(VOID, 1, VoidInput.class, RedemptionOutput.class, ProcessContext.class,
            pb -> {
                pb.description("An administrator voids a prize that should not stand (not yet collected).")
                    .permissions(QbPermissions.REDEMPTION_VOID)
                    .requiresVerifiedEmail()
                    .actsOn(QbRewards.REDEMPTION, "redemptionId",
                        a -> a.whenField("status", RedemptionStatus.DUE, RedemptionStatus.CLAIMED))
                    .contextFactory((s, input) -> start(s, input, input.redemptionId()))
                    .outputMapper(ctx -> ctx.get(OUTPUT, RedemptionOutput.class));
                // Q48e-1: an operator's member is checked within the act, before the record's lock.
                OperatorChecks.memberWhenScoped(pb);
                lockAndLoad(pb, QbRewards.defaultDataset(QbRewards.REDEMPTION));
                // Q59c-2: an operator's member voids their own sponsors' prizes only.
                OperatorChecks.ownSponsor(pb, ctx -> redemption(ctx) == null ? null
                    : redemption(ctx).get("publisherId"));
                pb.compute("Void", (metadata, ctx) -> {
                        EntityInstance redemption = redemption(ctx);
                        if (in(ctx, redemption.get("status"), RedemptionStatus.DUE, RedemptionStatus.CLAIMED)) {
                            VoidInput input = ctx.get(INPUT, VoidInput.class);
                            Map<String, Object> values = new LinkedHashMap<>();
                            values.put("status", RedemptionStatus.VOID);
                            values.put("voidedBy", ctx.request().actorId());
                            values.put("voidNote", CashRewardProcesses.blankToNull(input.note()));
                            write(ctx, redemption, values);
                        }
                    })
                    .step("Tell the taker", PublishEvent.<ProcessContext>when(RedemptionProcesses::changed,
                        VOIDED_EVENT, RedemptionProcesses::payload));
            });
    }

    // ---- the jobs' items (system) ------------------------------------------------------------------------------------

    public static ProcessDefinition<RedemptionRef, RedemptionOutput, ProcessContext> expire() {
        return ProcessDefinition.define(EXPIRE, 1, RedemptionRef.class, RedemptionOutput.class,
            ProcessContext.class, pb -> {
                pb.description("Marks a prize expired: it was not collected in time.")
                    .permissions(QbPermissions.PLAY_SYSTEM)
                    .internal()
                    .contextFactory((s, input) -> start(s, input, input.redemptionId()))
                    .outputMapper(ctx -> ctx.get(OUTPUT, RedemptionOutput.class));
                lockAndLoad(pb, QbRewards.defaultDataset(QbRewards.REDEMPTION));
                pb.compute("Expire it", (metadata, ctx) -> {
                        // Collected meanwhile, or not yet past its time: left as it is (idempotent). A claim the
                        // sponsor never confirmed expires with the prize.
                        EntityInstance redemption = redemption(ctx);
                        Instant expires = instant(redemption.get("expiresAt"));
                        if (RedemptionStatus.OPEN.contains(redemption.get("status")) && expires != null
                            && expires.isBefore(ctx.opTime())) {
                            write(ctx, redemption, Map.of("status", RedemptionStatus.EXPIRED));
                        } else {
                            unchanged(ctx, redemption);
                        }
                    })
                    .step("Tell the taker", PublishEvent.<ProcessContext>when(RedemptionProcesses::changed,
                        EXPIRED_EVENT, RedemptionProcesses::payload));
            });
    }

    public static ProcessDefinition<RedemptionRef, RedemptionOutput, ProcessContext> lapse() {
        return ProcessDefinition.define(LAPSE, 1, RedemptionRef.class, RedemptionOutput.class,
            ProcessContext.class, pb -> {
                pb.description("Undoes a claim the sponsor did not confirm in time: the prize is due again.")
                    .permissions(QbPermissions.PLAY_SYSTEM)
                    .internal()
                    .contextFactory((s, input) -> start(s, input, input.redemptionId()))
                    .outputMapper(ctx -> ctx.get(OUTPUT, RedemptionOutput.class));
                lockAndLoad(pb, QbRewards.defaultDataset(QbRewards.REDEMPTION));
                pb.compute("Undo the claim", (metadata, ctx) -> {
                    EntityInstance redemption = redemption(ctx);
                    Instant confirmBy = instant(redemption.get("confirmBy"));
                    if (RedemptionStatus.CLAIMED.equals(redemption.get("status")) && confirmBy != null
                        && !confirmBy.isAfter(ctx.opTime())) {
                        write(ctx, redemption, dueAgain());
                    } else {
                        unchanged(ctx, redemption);
                    }
                });
            });
    }

    // ---- shared ------------------------------------------------------------------------------------------------------

    /** Locks the redemption and loads it through {@code dataset}: 404 unless the caller may see it. */
    static <I, O> void lockAndLoad(ProcessDefinitionBuilder<I, O, ProcessContext> pb, String dataset) {
        pb.step("Lock the redemption", HoldLock.<ProcessContext>exclusive(
                ctx -> QbRewards.redemptionLockName(ctx.get(ID))))
            .step("Load the redemption", LoadEntity.by(dataset, ID, REDEMPTION));
    }

    static EntityInstance redemption(ProcessContext ctx) {
        return ctx.get(REDEMPTION, EntityInstance.class);
    }

    /** Whether the redemption is in one of {@code from}; otherwise 422 {@code QB_REDEMPTION_STATE}. */
    static boolean in(ProcessContext ctx, String status, String... from) {
        if (!List.of(from).contains(status)) {
            ctx.reject(new Violation("redemptionId", RewardCodes.REDEMPTION_STATE, "The prize is " + status,
                Map.of("status", String.valueOf(status))));
            return false;
        }
        return true;
    }

    static void write(ProcessContext ctx, EntityInstance redemption, Map<String, Object> values) {
        long revision = CashRewardProcesses.number(redemption.get("revision")) + 1;
        Map<String, Object> next = new LinkedHashMap<>(values);
        next.put("revision", BigDecimal.valueOf(revision));
        ctx.changes().update(QbRewards.REDEMPTION, redemption.id(), redemption.version(), next);
        ctx.put(CHANGED, true);
        ctx.put(OUTPUT, new RedemptionOutput(uuid(redemption.id()),
            values.containsKey("status") ? (String) values.get("status") : redemption.get("status"), revision));
    }

    static void unchanged(ProcessContext ctx, EntityInstance redemption) {
        ctx.put(OUTPUT, new RedemptionOutput(uuid(redemption.id()), redemption.get("status"),
            CashRewardProcesses.number(redemption.get("revision"))));
    }

    static boolean changed(ProcessContext ctx) {
        return !ctx.hasViolations() && Boolean.TRUE.equals(ctx.get(CHANGED));
    }

    private static String status(ProcessContext ctx) {
        return ctx.get(OUTPUT, RedemptionOutput.class).status();
    }

    /** What the messages need of a redemption: who, which attempt, how much, which code, where it stands. */
    static Map<String, Object> payload(ProcessContext ctx) {
        EntityInstance redemption = redemption(ctx);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("redemptionId", String.valueOf(redemption.id()));
        payload.put("attemptId", redemption.get("attemptId"));
        payload.put("publicationId", String.valueOf((Object) redemption.get("publicationId")));
        payload.put("userId", redemption.get("userId"));
        payload.put("publisherKind", redemption.get("publisherKind"));
        payload.put("publisherId", redemption.get("publisherId"));
        payload.put("amount", redemption.get("amount"));
        payload.put("code", redemption.get("code"));
        payload.put("status", status(ctx));
        return payload;
    }

    @SuppressWarnings("unchecked")
    static List<EntityInstance> list(ProcessContext ctx, String key) {
        List<EntityInstance> found = (List<EntityInstance>) ctx.get(key);
        return found == null ? List.of() : found;
    }

    static EntityInstance first(ProcessContext ctx, String key) {
        List<EntityInstance> found = list(ctx, key);
        return found.isEmpty() ? null : found.getFirst();
    }

    static UUID uuid(Object value) {
        return CashRewardProcesses.uuid(value);
    }

    static Instant instant(Object value) {
        return CashRewardProcesses.instant(value);
    }

    private RedemptionProcesses() {}
}
