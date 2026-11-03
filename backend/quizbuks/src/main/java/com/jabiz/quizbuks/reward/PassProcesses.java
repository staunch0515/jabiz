package com.jabiz.quizbuks.reward;

import com.jabiz.entity.Violation;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.process.ProcessDefinitionBuilder;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.identity.CallerChecks;
import com.jabiz.quizbuks.identity.QbAccounts;
import com.jabiz.quizbuks.publishing.PublisherKind;
import com.jabiz.quizbuks.publishing.QbPublications;
import com.jabiz.runtime.BusinessRuleViolationException;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.EntityNotFoundException;
import com.jabiz.runtime.process.steps.HoldLock;
import com.jabiz.runtime.process.steps.LoadEntity;
import com.jabiz.runtime.process.steps.PublishEvent;
import com.jabiz.runtime.process.steps.QueryEntities;
import com.jabiz.runtime.security.RandomBytesStep;
import com.jabiz.security.Sensitive;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The taker's pass (Q89c, docs/quizbuks/plans/Q89-sponsored-benefits.md, D-Q89-10): the second way to collect a prize
 * at the counter, for every prize collected at a place, the other way round from the sponsor's codes: the taker shows,
 * the counter scans.
 * <ul>
 *   <li>{@value #ISSUE} (taker): a pass for one of their prizes still to collect: a QR whose link carries a random key
 *       and six digits to read out, good for {@link QbRewards#PASS_LIFE} and once; asking again replaces it;</li>
 *   <li>{@value #CHECK} (sponsor) / {@value #STAFF_CHECK} (staff): what a scanned pass or typed digits stand for: the
 *       prize, its publication, the taker's name. Only the caller's counter's passes are found (the prize's hander,
 *       {@link QbRewards#handlerOf}); the digits are looked up among them alone, and the checks are rate-limited;</li>
 *   <li>{@value #REDEEM} (sponsor) / {@value #STAFF_REDEEM} (staff): the prize is handed over: {@code REDEEMED} with
 *       the claim kind {@code PASS}, by the caller.</li>
 * </ul>
 * Issuing and redeeming take the redemption's lock, as every other way of collecting does, so a pass and a sponsor's
 * code collecting the same prize at once succeed once. The key and the digits are never stored, only their hashes.
 */
public final class PassProcesses {

    public static final String ISSUE = "QB_REDEMPTION_PASS_ISSUE";
    public static final String CHECK = "QB_REDEMPTION_PASS_CHECK";
    public static final String REDEEM = "QB_REDEMPTION_PASS_REDEEM";
    public static final String STAFF_CHECK = "QB_STAFF_REDEMPTION_PASS_CHECK";
    public static final String STAFF_REDEEM = "QB_STAFF_REDEMPTION_PASS_REDEEM";

    /** The digits a person reads out at the counter. */
    public static final int DIGITS = 6;
    static final int KEY_BYTES = 24;
    static final int RANDOM_BYTES = KEY_BYTES + 4;

    public record IssueInput(@NotNull UUID redemptionId) {}

    /**
     * @param passKey    the key in the QR's link ({@code /pass/<key>}), returned to the taker alone
     * @param passDigits the six digits to read out instead
     */
    public record IssueOutput(UUID redemptionId, @Sensitive(returned = true) String passKey,
        @Sensitive(returned = true) String passDigits, Instant expiresAt) {

        @Override
        public String toString() {
            return "IssueOutput[redemptionId=" + redemptionId + ", passKey=***, passDigits=***, expiresAt=" + expiresAt
                + "]";
        }
    }

    /**
     * One of the two: the key from the scanned QR, or the digits the taker read out. Not named as the issue's output
     * ({@code passDigits}): a name masked anywhere is masked everywhere, the stricter rule winning.
     */
    public record CheckInput(@Sensitive @Size(max = QbRewards.TOKEN_LENGTH) String scannedPass,
        @Sensitive @Size(max = 12) String typedDigits) {

        @Override
        public String toString() {
            return "CheckInput[scannedPass=***, typedDigits=***]";
        }
    }

    /** What the counter sees before handing the prize over. */
    public record CheckOutput(UUID passId, UUID redemptionId, String publicationName, String description,
        BigDecimal amount, String takerName, String status, Instant passExpiresAt) {}

    public record RedeemInput(@NotNull UUID passId) {}

    static final String INPUT = RedemptionProcesses.INPUT;
    static final String ID = RedemptionProcesses.ID;
    static final String RANDOM = "random";
    static final String PASS = "pass";
    static final String PASSES = "passes";
    static final String PASS_ID = "passId";
    static final String SCANNED = "scanned";
    static final String TYPED = "typed";
    static final String HANDLER = "handlerId";
    static final String CARDS = "cards";
    static final String PROFILE = "profile";
    static final String OUTPUT = RedemptionProcesses.OUTPUT;

    // ---- the taker's pass --------------------------------------------------------------------------------------------

    public static ProcessDefinition<IssueInput, IssueOutput, ProcessContext> issue() {
        return ProcessDefinition.define(ISSUE, 1, IssueInput.class, IssueOutput.class, ProcessContext.class, pb -> {
            pb.description("The taker's pass for a prize to collect: a QR and six digits the counter scans or types, "
                    + "good for a minute and once.")
                .permissions(QbPermissions.ME)
                .requiresVerifiedEmail()
                .steps(CallerChecks::refuseBanned)
                .contextFactory((s, input) -> RedemptionProcesses.start(s, input, input.redemptionId()))
                .outputMapper(ctx -> ctx.get(OUTPUT, IssueOutput.class))
                // The platform's blocking step: SecureRandom may wait for entropy, never on an event loop.
                .step("Draw the key", RandomBytesStep.class, new RandomBytesStep.Metadata(RANDOM, RANDOM_BYTES));
            // The caller's own prize (another's is 404), under its lock: a replaced pass stops at once.
            RedemptionProcesses.lockAndLoad(pb, QbRewards.takerDataset(QbRewards.REDEMPTION));
            pb.step("Load its pass", QueryEntities.<ProcessContext>of(QbRewards.defaultDataset(QbRewards.PASS),
                    ctx -> EntityQuery.builder().where(new QueryPredicate.In("redemptionId",
                        List.of(String.valueOf((Object) ctx.get(ID))))).limit(1).build(), PASS))
                .compute("Issue it", (metadata, ctx) -> issue(ctx));
        });
    }

    static void issue(ProcessContext ctx) {
        EntityInstance redemption = RedemptionProcesses.redemption(ctx);
        if (!PublisherKind.sponsor(redemption.get("publisherKind"))) {
            throw new EntityNotFoundException(QbRewards.REDEMPTION + " " + redemption.id() + " not found");
        }
        String status = RedemptionProcesses.effectiveStatus(redemption, ctx.opTime());
        if (!RedemptionProcesses.in(ctx, status, RedemptionStatus.DUE, RedemptionStatus.CLAIMED)) {
            return;
        }
        byte[] random = ctx.get(RANDOM, byte[].class);
        String key = RedemptionProcesses.tokenOf(Arrays.copyOfRange(random, 0, KEY_BYTES));
        String digits = digitsOf(Arrays.copyOfRange(random, KEY_BYTES, RANDOM_BYTES));
        String handler = QbRewards.handlerOf(redemption.attributes());
        Instant now = ctx.opTime();
        Instant expiresAt = now.plus(QbRewards.PASS_LIFE);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("passHash", hash(key));
        values.put("digitsHash", digitsHash(handler, digits));
        values.put("issuedAt", now);
        values.put("expiresAt", expiresAt);
        values.put("usedAt", null);
        values.put("usedBy", null);
        EntityInstance pass = RedemptionProcesses.first(ctx, PASS);
        if (pass == null) {
            values.put("redemptionId", String.valueOf(redemption.id()));
            values.put("userId", redemption.get("userId"));
            values.put("handlerId", handler);
            ctx.changes().insert(QbRewards.PASS, values);
        } else {
            // The same prize, asked again: the earlier pass stops working.
            ctx.changes().update(QbRewards.PASS, pass.id(), pass.version(), values);
        }
        ctx.put(OUTPUT, new IssueOutput(RedemptionProcesses.uuid(redemption.id()), key, digits, expiresAt));
    }

    /** Six digits, evenly enough from 32 random bits (the bias of the modulus is below one in four thousand). */
    static String digitsOf(byte[] four) {
        long value = ((four[0] & 0xffL) << 24) | ((four[1] & 0xffL) << 16) | ((four[2] & 0xffL) << 8)
            | (four[3] & 0xffL);
        return String.format("%06d", value % 1_000_000L);
    }

    static String hash(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The digits' hash is bound to the counter: the same digits at another counter are another hash. */
    static String digitsHash(String handler, String digits) {
        return hash(handler + ":" + digits);
    }

    // ---- the counter -------------------------------------------------------------------------------------------------

    public static ProcessDefinition<CheckInput, CheckOutput, ProcessContext> check() {
        return checkProcess(CHECK, false);
    }

    public static ProcessDefinition<CheckInput, CheckOutput, ProcessContext> staffCheck() {
        return checkProcess(STAFF_CHECK, true);
    }

    public static ProcessDefinition<RedeemInput, RedemptionProcesses.RedemptionOutput, ProcessContext> redeem() {
        return redeemProcess(REDEEM, false);
    }

    public static ProcessDefinition<RedeemInput, RedemptionProcesses.RedemptionOutput, ProcessContext> staffRedeem() {
        return redeemProcess(STAFF_REDEEM, true);
    }

    /**
     * The counter the caller stands at: the sponsor's own ({@code staff} false; sponsors' permission), or the
     * sponsor whose staff the caller is (the taker's permission, the place held under the shared staff lock).
     */
    private static <I, O> void counter(ProcessDefinitionBuilder<I, O, ProcessContext> pb, boolean staff) {
        pb.permissions(staff ? QbPermissions.ME : QbPermissions.PUBLICATION_WRITE)
            .requiresVerifiedEmail()
            .steps(CallerChecks::refuseBanned);
        if (staff) {
            StaffProcesses.staffPlace(pb);
            pb.compute("The sponsor's counter", (metadata, ctx) -> ctx.put(HANDLER,
                ctx.get(StaffProcesses.SPONSOR_ID, String.class)));
        } else {
            pb.compute("The caller's counter", (metadata, ctx) -> ctx.put(HANDLER, ctx.request().actorId()));
        }
    }

    private static ProcessDefinition<CheckInput, CheckOutput, ProcessContext> checkProcess(String name,
        boolean staff) {
        return ProcessDefinition.define(name, 1, CheckInput.class, CheckOutput.class, ProcessContext.class, pb -> {
            pb.description(staff ? "A staff member reads a taker's pass: the prize it is for, before handing it over."
                    : "The sponsor reads a taker's pass: the prize it is for, before handing it over.")
                .contextFactory((s, input) -> RedemptionProcesses.start(s, input, null))
                .outputMapper(ctx -> ctx.get(OUTPUT, CheckOutput.class));
            counter(pb, staff);
            pb.compute("Read what the counter gave", (metadata, ctx) -> readInput(ctx))
                .step("Find the pass", QueryEntities.<ProcessContext>of(QbRewards.defaultDataset(QbRewards.PASS),
                    PassProcesses::passQuery, PASSES))
                .compute("Check the pass", (metadata, ctx) -> {
                    EntityInstance pass = foundPass(ctx);
                    ctx.put(ID, UUID.fromString(String.valueOf((Object) pass.get("redemptionId"))));
                    ctx.put(PASS, pass);
                })
                .step("Load the prize", LoadEntity.by(QbRewards.defaultDataset(QbRewards.REDEMPTION), ID,
                    RedemptionProcesses.REDEMPTION))
                // Its publication's name and the taker's name in the App: one statement.
                .step("Load what it is", QueryEntities.<ProcessContext>all(
                    QueryEntities.query(QbPublications.defaultDataset(QbPublications.CARD), ctx -> EntityQuery.builder()
                        .where(new QueryPredicate.In("publicationId", List.of(RedemptionProcesses.uuid(
                            RedemptionProcesses.redemption(ctx).get("publicationId"))))).limit(1).build(), CARDS),
                    QueryEntities.query(QbAccounts.defaultDataset(QbAccounts.PROFILE), ctx -> EntityQuery.builder()
                        .where(new QueryPredicate.In("userId", List.of(String.valueOf((Object)
                            RedemptionProcesses.redemption(ctx).get("userId"))))).limit(1).build(), PROFILE)))
                .compute("Describe it", (metadata, ctx) -> {
                    EntityInstance redemption = RedemptionProcesses.redemption(ctx);
                    refuseNotHere(ctx, redemption, staff);
                    String status = RedemptionProcesses.effectiveStatus(redemption, ctx.opTime());
                    if (!RedemptionProcesses.in(ctx, status, RedemptionStatus.DUE, RedemptionStatus.CLAIMED)) {
                        return;
                    }
                    EntityInstance pass = ctx.get(PASS, EntityInstance.class);
                    EntityInstance card = RedemptionProcesses.first(ctx, CARDS);
                    EntityInstance profile = RedemptionProcesses.first(ctx, PROFILE);
                    ctx.put(OUTPUT, new CheckOutput(RedemptionProcesses.uuid(pass.id()),
                        RedemptionProcesses.uuid(redemption.id()), card == null ? null : card.get("name"),
                        redemption.get("description"),
                        new BigDecimal(String.valueOf((Object) redemption.get("amount"))),
                        profile == null ? null : profile.get("displayName"), status,
                        RedemptionProcesses.instant(pass.get("expiresAt"))));
                });
        });
    }

    /** The scanned key or the typed digits (spaces dropped), exactly one of them; otherwise 422. */
    static void readInput(ProcessContext ctx) {
        CheckInput input = ctx.get(INPUT, CheckInput.class);
        String scanned = blank(input.scannedPass()) ? null : input.scannedPass().strip();
        String digits = blank(input.typedDigits()) ? null : input.typedDigits().replaceAll("\\s", "");
        if ((scanned == null) == (digits == null)) {
            throw new BusinessRuleViolationException(new Violation("typedDigits", RewardCodes.PASS_INPUT,
                "Give the scanned pass or its digits", Map.of()));
        }
        if (scanned != null) {
            ctx.put(SCANNED, scanned);
        } else {
            ctx.put(TYPED, digits);
        }
    }

    /** The counter's live pass by the scanned key or by the typed digits (at most two: to tell a clash). */
    static EntityQuery passQuery(ProcessContext ctx) {
        String scanned = ctx.get(SCANNED, String.class);
        String digits = ctx.get(TYPED, String.class);
        if (scanned != null) {
            return EntityQuery.builder().where(new QueryPredicate.In("passHash", List.of(hash(scanned)))).limit(1)
                .build();
        }
        return EntityQuery.builder().where(new QueryPredicate.And(List.of(
                new QueryPredicate.In("handlerId", List.of(ctx.get(HANDLER, String.class))),
                new QueryPredicate.In("digitsHash", List.of(digitsHash(ctx.get(HANDLER, String.class), digits))),
                new QueryPredicate.IsNull("usedAt"),
                new QueryPredicate.Gt("expiresAt", ctx.opTime()))))
            .limit(2).build();
    }

    /**
     * The pass found, if it is this counter's, unused and in time; another counter's is none (404), anything else
     * 422 {@code QB_REDEMPTION_PASS_INVALID}; two by the same digits 422 {@code QB_REDEMPTION_PASS_AMBIGUOUS}.
     */
    static EntityInstance foundPass(ProcessContext ctx) {
        List<EntityInstance> passes = RedemptionProcesses.list(ctx, PASSES);
        if (passes.size() > 1) {
            throw new BusinessRuleViolationException(new Violation("typedDigits", RewardCodes.PASS_AMBIGUOUS,
                "The digits match more than one pass: scan the QR", Map.of()));
        }
        EntityInstance pass = passes.isEmpty() ? null : passes.getFirst();
        if (pass != null && !ctx.get(HANDLER, String.class).equals(pass.get("handlerId"))) {
            throw new EntityNotFoundException(QbRewards.PASS + " " + pass.id() + " not found");
        }
        if (pass == null || !usable(pass, ctx.opTime())) {
            throw new BusinessRuleViolationException(new Violation("typedDigits", RewardCodes.PASS_INVALID,
                "The pass is not good now: the taker shows a new one", Map.of()));
        }
        return pass;
    }

    static boolean usable(EntityInstance pass, Instant now) {
        Instant expires = RedemptionProcesses.instant(pass.get("expiresAt"));
        return pass.get("usedAt") == null && expires != null && expires.isAfter(now);
    }

    /**
     * The prize is this counter's to hand over (its hander; the pass says the same, but the prize decides), and a
     * staff member does not hand over their own.
     */
    static void refuseNotHere(ProcessContext ctx, EntityInstance redemption, boolean staff) {
        if (!PublisherKind.sponsor(redemption.get("publisherKind"))
            || !ctx.get(HANDLER, String.class).equals(QbRewards.handlerOf(redemption.attributes()))) {
            throw new EntityNotFoundException(QbRewards.REDEMPTION + " " + redemption.id() + " not found");
        }
        if (staff && ctx.request().actorId().equals(redemption.get("userId"))) {
            throw new BusinessRuleViolationException(new Violation("passId", RewardCodes.STAFF_OWN_PRIZE,
                "The prize is the caller's own: someone else hands it over", Map.of()));
        }
    }

    private static ProcessDefinition<RedeemInput, RedemptionProcesses.RedemptionOutput, ProcessContext> redeemProcess(
        String name, boolean staff) {
        return ProcessDefinition.define(name, 1, RedeemInput.class, RedemptionProcesses.RedemptionOutput.class,
            ProcessContext.class, pb -> {
                pb.description(staff ? "A staff member hands over the prize a taker's pass is for."
                        : "The sponsor hands over the prize a taker's pass is for.")
                    .contextFactory((s, input) -> {
                        ProcessContext ctx = RedemptionProcesses.start(s, input, null);
                        // The pass's key is text (an ordinary table's generated id): read it as written.
                        ctx.put(PASS_ID, input.passId().toString());
                        return ctx;
                    })
                    .outputMapper(ctx -> ctx.get(OUTPUT, RedemptionProcesses.RedemptionOutput.class));
                counter(pb, staff);
                // The pass names its prize; the prize's lock is taken before both are read again, so the pass the
                // taker replaced meanwhile, or a prize collected another way, is seen.
                pb.step("Find the pass", LoadEntity.by(QbRewards.defaultDataset(QbRewards.PASS), PASS_ID, PASS))
                    .compute("Its prize", (metadata, ctx) -> {
                        EntityInstance pass = ctx.get(PASS, EntityInstance.class);
                        if (!ctx.get(HANDLER, String.class).equals(pass.get("handlerId"))) {
                            throw new EntityNotFoundException(QbRewards.PASS + " " + pass.id() + " not found");
                        }
                        ctx.put(ID, UUID.fromString(String.valueOf((Object) pass.get("redemptionId"))));
                    })
                    .step("Lock the redemption", HoldLock.<ProcessContext>exclusive(
                        ctx -> QbRewards.redemptionLockName(ctx.get(ID))))
                    .step("Load both", QueryEntities.<ProcessContext>all(
                        QueryEntities.query(QbRewards.defaultDataset(QbRewards.REDEMPTION), ctx -> EntityQuery.builder()
                            .where(new QueryPredicate.In("redemptionId", List.of(ctx.get(ID)))).limit(1).build(),
                            RedemptionProcesses.REDEMPTION + "s"),
                        QueryEntities.query(QbRewards.defaultDataset(QbRewards.PASS), ctx -> EntityQuery.builder()
                            .where(new QueryPredicate.In("passId", List.of(String.valueOf((Object) ctx.get(PASS_ID)))))
                            .limit(1).build(), PASSES)))
                    .compute("Hand it over", (metadata, ctx) -> redeem(ctx, staff))
                    .step("Tell the taker", PublishEvent.<ProcessContext>when(RedemptionProcesses::changed,
                        RedemptionProcesses.REDEEMED_EVENT, RedemptionProcesses::payload));
            });
    }

    static void redeem(ProcessContext ctx, boolean staff) {
        EntityInstance redemption = RedemptionProcesses.first(ctx, RedemptionProcesses.REDEMPTION + "s");
        EntityInstance pass = RedemptionProcesses.first(ctx, PASSES);
        if (redemption == null || pass == null) {
            throw new EntityNotFoundException(QbRewards.PASS + " " + ctx.get(PASS_ID) + " not found");
        }
        ctx.put(RedemptionProcesses.REDEMPTION, redemption);
        refuseNotHere(ctx, redemption, staff);
        if (!usable(pass, ctx.opTime())) {
            ctx.reject(new Violation("passId", RewardCodes.PASS_INVALID,
                "The pass is not good now: the taker shows a new one", Map.of()));
            return;
        }
        String status = RedemptionProcesses.effectiveStatus(redemption, ctx.opTime());
        if (!RedemptionProcesses.in(ctx, status, RedemptionStatus.DUE, RedemptionStatus.CLAIMED)) {
            return;
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("status", RedemptionStatus.REDEEMED);
        values.put("claimKind", RedemptionStatus.KIND_PASS);
        values.put("claimedAt", ctx.opTime());
        values.put("redeemedAt", ctx.opTime());
        values.put("redeemedBy", ctx.request().actorId());
        values.put("confirmBy", null);
        RedemptionProcesses.write(ctx, redemption, values);
        ctx.changes().update(QbRewards.PASS, pass.id(), pass.version(),
            Map.of("usedAt", ctx.opTime(), "usedBy", ctx.request().actorId()));
    }

    private static boolean blank(String text) {
        return text == null || text.isBlank();
    }

    private PassProcesses() {}
}
