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
import com.jabiz.runtime.BusinessRuleViolationException;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.EntityNotFoundException;
import com.jabiz.runtime.process.steps.HoldLock;
import com.jabiz.runtime.process.steps.PublishEvent;
import com.jabiz.runtime.process.steps.QueryEntities;
import com.jabiz.runtime.security.RandomBytesStep;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A sponsor's staff (docs/quizbuks/plans/Q39-sponsor-staff.md): a taker registers in the App as anyone does, gives the
 * sponsor their ID (the App's account page shows it), and the sponsor names them; from then on they hand out the
 * sponsor's prizes at the counter in the App, and nothing more.
 * <ul>
 *   <li>{@value #FIND} (sponsor): who an ID is, before naming them; {@value #ADD} names them, {@value #REMOVE} takes
 *       them off. A person is on one sponsor's staff at most ({@link QbRewards#MAX_STAFF} each), is a taker (has a
 *       profile and no sponsor account), and is not the sponsor;</li>
 *   <li>{@value #LEAVE} (staff): the staff member steps down themselves;</li>
 *   <li>{@value #ISSUE} (staff): the sponsor's rotating code, issued in the staff member's name (the printed code
 *       stays the sponsor's);</li>
 *   <li>{@value #CONFIRM} / {@value #DECLINE} (staff): the answer to a claim made by the sponsor's printed code, as
 *       the sponsor's {@code QB_REDEMPTION_CONFIRM} / {@code QB_REDEMPTION_DECLINE}; another sponsor's prize is 404.</li>
 * </ul>
 * The staff's own processes need nothing beyond the taker's permission: each reads the caller's place on a staff under
 * the shared {@link QbRewards#staffLockName staff lock}, which naming and removing take exclusively, so a removal
 * takes effect at the next act. A staff member does not hand over a prize of their own.
 */
public final class StaffProcesses {

    public static final String FIND = "QB_SPONSOR_STAFF_FIND";
    public static final String ADD = "QB_SPONSOR_STAFF_ADD";
    public static final String REMOVE = "QB_SPONSOR_STAFF_REMOVE";
    public static final String LEAVE = "QB_STAFF_LEAVE";
    public static final String ISSUE = "QB_STAFF_REDEEM_CODE_ISSUE";
    public static final String CONFIRM = "QB_STAFF_REDEMPTION_CONFIRM";
    public static final String DECLINE = "QB_STAFF_REDEMPTION_DECLINE";

    /** The ID the taker gave the sponsor: their user id. */
    public record StaffRef(@NotBlank @Size(max = 128) String userId) {}

    /** Who the ID is: their name in the App, and whether they are on the sponsor's staff already. */
    public record Candidate(String userId, String displayName, boolean onStaff) {}

    /** The staff's own processes act on the caller's place: nothing to give. */
    public record StaffInput() {}

    /** @param changed false when nothing had to change (named already; not on the staff) */
    public record StaffOutput(String userId, boolean changed) {}

    static final String INPUT = RedemptionProcesses.INPUT;
    static final String TARGET = "target";
    static final String SPONSOR_ID = "staffSponsorId";
    static final String PROFILE = "profile";
    static final String SPONSOR_ACCOUNT = "sponsorAccount";
    static final String PLACE = "place";
    static final String STAFF = "staff";
    static final String OWN_TOKENS = "ownTokens";
    static final String OUTPUT = RedemptionProcesses.OUTPUT;

    // ---- the sponsor names and removes ------------------------------------------------------------------------------

    public static ProcessDefinition<StaffRef, Candidate, ProcessContext> find() {
        return ProcessDefinition.define(FIND, 1, StaffRef.class, Candidate.class, ProcessContext.class, pb -> {
            pb.description("Who a taker's ID is, before the sponsor names them to their staff.")
                .permissions(QbPermissions.PUBLICATION_WRITE)
                .requiresVerifiedEmail()
                .steps(CallerChecks::refuseBanned)
                .contextFactory(StaffProcesses::sponsorStart)
                .outputMapper(ctx -> ctx.get(OUTPUT, Candidate.class));
            loadCandidate(pb);
            pb.compute("Describe them", (metadata, ctx) -> {
                EntityInstance place = candidate(ctx);
                EntityInstance profile = RedemptionProcesses.first(ctx, PROFILE);
                ctx.put(OUTPUT, new Candidate(target(ctx), profile.get("displayName"), place != null));
            });
        });
    }

    public static ProcessDefinition<StaffRef, StaffOutput, ProcessContext> add() {
        return ProcessDefinition.define(ADD, 1, StaffRef.class, StaffOutput.class, ProcessContext.class, pb -> {
            pb.description("The sponsor names a taker to their staff: they hand out the sponsor's prizes at the counter.")
                .permissions(QbPermissions.PUBLICATION_WRITE)
                .requiresVerifiedEmail()
                .steps(CallerChecks::refuseBanned)
                .contextFactory(StaffProcesses::sponsorStart)
                .outputMapper(ctx -> ctx.get(OUTPUT, StaffOutput.class))
                .step("Lock the staff list", HoldLock.<ProcessContext>exclusive(
                    ctx -> QbRewards.staffListLockName(ctx.request().actorId())))
                .step("Lock the person's place", HoldLock.<ProcessContext>exclusive(
                    ctx -> QbRewards.staffLockName(target(ctx))));
            loadCandidate(pb);
            pb.step("Load the staff", QueryEntities.<ProcessContext>of(QbRewards.defaultDataset(QbRewards.STAFF),
                    ctx -> EntityQuery.builder().where(new QueryPredicate.In("sponsorId",
                        List.of(ctx.request().actorId()))).limit(QbRewards.MAX_STAFF + 1).build(), STAFF))
                .compute("Name them", (metadata, ctx) -> {
                    if (candidate(ctx) != null) {
                        ctx.put(OUTPUT, new StaffOutput(target(ctx), false));
                        return;
                    }
                    if (RedemptionProcesses.list(ctx, STAFF).size() >= QbRewards.MAX_STAFF) {
                        ctx.reject(new Violation("userId", RewardCodes.STAFF_FULL, "The staff is full",
                            Map.of("max", QbRewards.MAX_STAFF)));
                        return;
                    }
                    Map<String, Object> values = new LinkedHashMap<>();
                    values.put("sponsorId", ctx.request().actorId());
                    values.put("userId", target(ctx));
                    values.put("addedAt", ctx.opTime());
                    values.put("addedBy", ctx.request().actorId());
                    ctx.changes().insert(QbRewards.STAFF, values);
                    ctx.put(OUTPUT, new StaffOutput(target(ctx), true));
                });
        });
    }

    public static ProcessDefinition<StaffRef, StaffOutput, ProcessContext> remove() {
        return ProcessDefinition.define(REMOVE, 1, StaffRef.class, StaffOutput.class, ProcessContext.class, pb -> {
            pb.description("The sponsor takes a person off their staff; the rotating codes they showed stop working.")
                .permissions(QbPermissions.PUBLICATION_WRITE)
                .requiresVerifiedEmail()
                .steps(CallerChecks::refuseBanned)
                .contextFactory(StaffProcesses::sponsorStart)
                .outputMapper(ctx -> ctx.get(OUTPUT, StaffOutput.class))
                .step("Lock the staff list", HoldLock.<ProcessContext>exclusive(
                    ctx -> QbRewards.staffListLockName(ctx.request().actorId())))
                .step("Lock the person's place", HoldLock.<ProcessContext>exclusive(
                    ctx -> QbRewards.staffLockName(target(ctx))))
                .step("Load the person's place", QueryEntities.<ProcessContext>of(
                    QbRewards.defaultDataset(QbRewards.STAFF), ctx -> EntityQuery.builder().where(new QueryPredicate.And(
                        List.of(new QueryPredicate.In("sponsorId", List.of(ctx.request().actorId())),
                            new QueryPredicate.In("userId", List.of(target(ctx)))))).limit(1).build(), PLACE))
                .compute("Whose codes", (metadata, ctx) -> {
                    // Nobody to remove: no codes to drop, no lock to take.
                    if (RedemptionProcesses.first(ctx, PLACE) != null) {
                        ctx.put(SPONSOR_ID, ctx.request().actorId());
                    }
                });
            dropPlace(pb);
        });
    }

    // ---- the staff member -------------------------------------------------------------------------------------------

    public static ProcessDefinition<StaffInput, StaffOutput, ProcessContext> leave() {
        return ProcessDefinition.define(LEAVE, 1, StaffInput.class, StaffOutput.class, ProcessContext.class, pb -> {
            pb.description("A staff member steps down from the sponsor's staff.")
                .permissions(QbPermissions.ME)
                .requiresVerifiedEmail()
                .steps(CallerChecks::refuseBanned)
                .contextFactory((s, input) -> {
                    ProcessContext ctx = new ProcessContext(s);
                    ctx.put(TARGET, s.request().actorId());
                    return ctx;
                })
                .outputMapper(ctx -> ctx.get(OUTPUT, StaffOutput.class))
                .step("Lock the caller's place", HoldLock.<ProcessContext>exclusive(
                    ctx -> QbRewards.staffLockName(ctx.request().actorId())));
            loadPlace(pb, PLACE);
            pb.compute("Whose codes", (metadata, ctx) -> {
                EntityInstance place = RedemptionProcesses.first(ctx, PLACE);
                ctx.put(SPONSOR_ID, place == null ? null : place.get("sponsorId"));
            });
            dropPlace(pb);
        });
    }

    public static ProcessDefinition<StaffInput, RedemptionProcesses.IssueOutput, ProcessContext> issue() {
        return ProcessDefinition.define(ISSUE, 1, StaffInput.class, RedemptionProcesses.IssueOutput.class,
            ProcessContext.class, pb -> {
                pb.description("A staff member shows the sponsor's rotating code: a prize collected by it is handed "
                        + "over by them.")
                    .permissions(QbPermissions.ME)
                    .requiresVerifiedEmail()
                    .steps(CallerChecks::refuseBanned)
                    .contextFactory((s, input) -> RedemptionProcesses.start(s,
                        new RedemptionProcesses.IssueInput(RedemptionStatus.KIND_ROTATING, null), null))
                    .outputMapper(ctx -> ctx.get(OUTPUT, RedemptionProcesses.IssueOutput.class))
                    .step("Draw the token", RandomBytesStep.class,
                        new RandomBytesStep.Metadata(RedemptionProcesses.RANDOM, RedemptionProcesses.TOKEN_BYTES));
                staffPlace(pb);
                pb.step("Lock the sponsor's codes", HoldLock.<ProcessContext>exclusive(
                        ctx -> QbRewards.tokenLockName(ctx.get(SPONSOR_ID))))
                    .step("Load the expired rotating codes", QueryEntities.<ProcessContext>of(
                        QbRewards.defaultDataset(QbRewards.REDEEM_TOKEN), ctx -> EntityQuery.builder()
                            .where(new QueryPredicate.And(List.of(
                                new QueryPredicate.In("sponsorId", List.of(ctx.get(SPONSOR_ID))),
                                new QueryPredicate.Eq("kind", RedemptionStatus.KIND_ROTATING),
                                new QueryPredicate.Lt("expiresAt", ctx.opTime()))))
                            .limit(500).build(), RedemptionProcesses.TOKENS))
                    .compute("Issue it", (metadata, ctx) -> RedemptionProcesses.issue(ctx, ctx.get(SPONSOR_ID,
                        String.class)));
            });
    }

    public static ProcessDefinition<RedemptionProcesses.RedemptionRef, RedemptionProcesses.RedemptionOutput,
        ProcessContext> confirm() {
        return answer(CONFIRM, "A staff member confirms a claim made by the sponsor's printed code: the prize is "
            + "collected.", true);
    }

    public static ProcessDefinition<RedemptionProcesses.RedemptionRef, RedemptionProcesses.RedemptionOutput,
        ProcessContext> decline() {
        return answer(DECLINE, "A staff member declines a claim made by the sponsor's printed code: the prize is due "
            + "again.", false);
    }

    private static ProcessDefinition<RedemptionProcesses.RedemptionRef, RedemptionProcesses.RedemptionOutput,
        ProcessContext> answer(String name, String description, boolean confirm) {
        return ProcessDefinition.define(name, 1, RedemptionProcesses.RedemptionRef.class,
            RedemptionProcesses.RedemptionOutput.class, ProcessContext.class, pb -> {
                pb.description(description)
                    .permissions(QbPermissions.ME)
                    .requiresVerifiedEmail()
                    .steps(CallerChecks::refuseBanned)
                    .contextFactory((s, input) -> RedemptionProcesses.start(s, input, input.redemptionId()))
                    .outputMapper(ctx -> ctx.get(OUTPUT, RedemptionProcesses.RedemptionOutput.class));
                staffPlace(pb);
                RedemptionProcesses.lockAndLoad(pb, QbRewards.defaultDataset(QbRewards.REDEMPTION));
                pb.compute(confirm ? "Confirm" : "Decline", (metadata, ctx) -> {
                        EntityInstance redemption = RedemptionProcesses.redemption(ctx);
                        if (!PublisherKind.sponsor(redemption.get("publisherKind")) || !String.valueOf(
                            (Object) redemption.get("publisherId")).equals(ctx.get(SPONSOR_ID, String.class))) {
                            // Another sponsor's prize: as if there were none.
                            throw new EntityNotFoundException(QbRewards.REDEMPTION + " " + redemption.id()
                                + " not found");
                        }
                        if (ctx.request().actorId().equals(redemption.get("userId"))) {
                            ctx.reject(new Violation("redemptionId", RewardCodes.STAFF_OWN_PRIZE,
                                "The prize is the caller's own: someone else hands it over", Map.of()));
                            return;
                        }
                        if (confirm) {
                            RedemptionProcesses.confirm(ctx);
                        } else {
                            RedemptionProcesses.decline(ctx);
                        }
                    })
                    .step("Tell the taker", PublishEvent.<ProcessContext>when(RedemptionProcesses::changed,
                        confirm ? RedemptionProcesses.REDEEMED_EVENT : RedemptionProcesses.DECLINED_EVENT,
                        RedemptionProcesses::payload));
            });
    }

    // ---- shared -----------------------------------------------------------------------------------------------------

    private static ProcessContext sponsorStart(com.jabiz.process.ProcessStart start, StaffRef input) {
        ProcessContext ctx = new ProcessContext(start);
        ctx.put(INPUT, input);
        ctx.put(TARGET, canonical(input.userId()));
        return ctx;
    }

    /** A user id as the platform writes it (a UUID in lower case); anything else names nobody. */
    static String canonical(String id) {
        try {
            return UUID.fromString(id.strip()).toString();
        } catch (IllegalArgumentException e) {
            return id.strip();
        }
    }

    static String target(ProcessContext ctx) {
        return ctx.get(TARGET, String.class);
    }

    /**
     * Loads the person the sponsor names (their profile, any sponsor account, their place on a staff) and refuses one
     * who cannot be named; the place stays under {@link #PLACE} (this sponsor's, or null).
     */
    private static <I, O> void loadCandidate(ProcessDefinitionBuilder<I, O, ProcessContext> pb) {
        pb.step("Load the person", QueryEntities.<ProcessContext>all(
            QueryEntities.query(QbAccounts.defaultDataset(QbAccounts.PROFILE), ctx -> byUser(target(ctx)), PROFILE),
            QueryEntities.query(QbAccounts.defaultDataset(QbAccounts.SPONSOR), ctx -> byUser(target(ctx)),
                SPONSOR_ACCOUNT),
            QueryEntities.query(QbRewards.defaultDataset(QbRewards.STAFF), ctx -> byUser(target(ctx)), PLACE)));
    }

    /** The person's place on this sponsor's staff (null if none), once they are known to be one who can be named. */
    private static EntityInstance candidate(ProcessContext ctx) {
        String sponsorId = ctx.request().actorId();
        if (target(ctx).equals(sponsorId)) {
            throw new BusinessRuleViolationException(new Violation("userId", RewardCodes.STAFF_SELF,
                "A sponsor is not their own staff", Map.of()));
        }
        if (RedemptionProcesses.first(ctx, PROFILE) == null || RedemptionProcesses.first(ctx, SPONSOR_ACCOUNT) != null) {
            throw new BusinessRuleViolationException(new Violation("userId", RewardCodes.STAFF_NOT_A_TAKER,
                "The ID is not a taker's", Map.of()));
        }
        EntityInstance place = RedemptionProcesses.first(ctx, PLACE);
        if (place != null && !sponsorId.equals(place.get("sponsorId"))) {
            throw new BusinessRuleViolationException(new Violation("userId", RewardCodes.STAFF_ELSEWHERE,
                "The person is on another sponsor's staff", Map.of()));
        }
        return place;
    }

    private static EntityQuery byUser(String userId) {
        return EntityQuery.builder().where(new QueryPredicate.In("userId", List.of(userId))).limit(1).build();
    }

    private static <I, O> void loadPlace(ProcessDefinitionBuilder<I, O, ProcessContext> pb, String key) {
        pb.step("Load the caller's place", QueryEntities.<ProcessContext>of(QbRewards.defaultDataset(QbRewards.STAFF),
            ctx -> byUser(ctx.request().actorId()), key));
    }

    /**
     * The caller's place on a staff, held for the process (the shared lock): the sponsor's id under
     * {@link #SPONSOR_ID}; otherwise 422 {@code QB_NOT_STAFF}; 422 {@code QB_STAFF_SPONSOR_BANNED} when the sponsor is
     * banned.
     */
    private static <I, O> void staffPlace(ProcessDefinitionBuilder<I, O, ProcessContext> pb) {
        pb.step("Hold the caller's place", HoldLock.<ProcessContext>shared(
            ctx -> QbRewards.staffLockName(ctx.request().actorId())));
        loadPlace(pb, PLACE);
        pb.compute("Check the caller's place", (metadata, ctx) -> {
            EntityInstance place = RedemptionProcesses.first(ctx, PLACE);
            if (place == null) {
                throw new BusinessRuleViolationException(new Violation(null, RewardCodes.NOT_STAFF,
                    "The caller is on no sponsor's staff", Map.of()));
            }
            ctx.put(SPONSOR_ID, place.get("sponsorId"));
        });
        // A banned sponsor's counter is closed, as the sponsor's own processes are.
        CallerChecks.refuseBannedUser(pb, ctx -> ctx.get(SPONSOR_ID), new Violation(null,
            RewardCodes.STAFF_SPONSOR_BANNED, "The sponsor is banned: their counter is closed", Map.of()));
    }

    /**
     * Drops the place loaded under {@link #PLACE} and the rotating codes its holder asked for (they would hand over
     * prizes in the name of someone no longer on the staff), under the sponsor's code lock.
     */
    private static <I, O> void dropPlace(ProcessDefinitionBuilder<I, O, ProcessContext> pb) {
        pb.step("Lock the sponsor's codes", HoldLock.<ProcessContext>exclusive(
                ctx -> ctx.get(SPONSOR_ID) == null ? null : QbRewards.tokenLockName(ctx.get(SPONSOR_ID))))
            .step("Load their rotating codes", QueryEntities.<ProcessContext>of(
                QbRewards.defaultDataset(QbRewards.REDEEM_TOKEN), ctx -> EntityQuery.builder()
                    .where(new QueryPredicate.And(List.of(
                        new QueryPredicate.In("sponsorId", sponsorIds(ctx)),
                        new QueryPredicate.In("issuedBy", List.of(target(ctx))),
                        new QueryPredicate.Eq("kind", RedemptionStatus.KIND_ROTATING))))
                    .limit(500).build(), OWN_TOKENS))
            .compute("Drop the place", (metadata, ctx) -> {
                EntityInstance place = RedemptionProcesses.first(ctx, PLACE);
                if (place == null) {
                    ctx.put(OUTPUT, new StaffOutput(target(ctx), false));
                    return;
                }
                for (EntityInstance token : RedemptionProcesses.list(ctx, OWN_TOKENS)) {
                    ctx.changes().delete(QbRewards.REDEEM_TOKEN, token.id(), token.version());
                }
                ctx.changes().delete(QbRewards.STAFF, place.id(), place.version());
                ctx.put(OUTPUT, new StaffOutput(target(ctx), true));
            });
    }

    private static List<Object> sponsorIds(ProcessContext ctx) {
        Object sponsorId = ctx.get(SPONSOR_ID);
        return sponsorId == null ? List.of() : List.of(sponsorId);
    }

    private StaffProcesses() {}
}
