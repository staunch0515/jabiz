package com.jabiz.quizbuks.play;

import com.jabiz.i18n.MessageCatalog;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.country.Regions;
import com.jabiz.quizbuks.identity.CallerChecks;
import com.jabiz.quizbuks.identity.QbAccounts;
import com.jabiz.quizbuks.operator.QbOperators;
import com.jabiz.quizbuks.publishing.PublicationMoves;
import com.jabiz.quizbuks.publishing.QbPublications;
import com.jabiz.quizbuks.publishing.SponsorChecks;
import com.jabiz.runtime.BusinessRuleViolationException;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.EntityNotFoundException;
import com.jabiz.runtime.process.steps.HoldLock;
import com.jabiz.runtime.process.steps.LoadEntity;
import com.jabiz.runtime.process.steps.QueryEntities;

import java.util.List;

import static com.jabiz.quizbuks.play.PlaySteps.INPUT;
import static com.jabiz.quizbuks.play.PlaySteps.OUTPUT;
import static com.jabiz.quizbuks.play.PlaySteps.first;
import static com.jabiz.quizbuks.play.PlaySteps.ids;

/**
 * A Sponsor answering a challenge in the Sponsor app (F-S4a, docs/quizbuks/plans/impl/F-S4.md section 2): a
 * publication bound to a node of its operator's copy of a platform map ({@code forSponsors} on its card), answered by
 * the operator's Sponsors so that the node is lit for the Sponsor ({@code QbTreeLit} with a SPONSOR subject). The
 * Taker's processes refuse it ({@code QB_ATTEMPT_FOR_SPONSORS}); these are the same steps as theirs ({@link PlaySteps},
 * {@link AttemptProcesses}, {@link AttemptFinishing}, {@link AttemptResults}) for the caller as the Sponsor:
 * <ul>
 *   <li>{@value #START}: an approved, not banned Sponsor of the challenge's operator starts (or continues) their
 *       attempt, which records them in {@code onBehalfOf}; no profile, audience, invitation, region, block or video is
 *       read (a challenge has none of a Taker's), and it pays nothing (all of its tiers are 0);</li>
 *   <li>{@value #SAVE}, {@value #SUBMIT}, {@value #RESULT}: as the Taker's, for a Sponsor's attempt only.</li>
 * </ul>
 * Another operator's challenge, or a publication that is no challenge, is as good as none (404): a Sponsor learns of
 * no other operator's. The person answers as themselves: staff hold no {@link QbPermissions#SPONSOR_CHALLENGE}. The
 * locks, admission and statements are the Taker's.
 */
public final class SponsorPlay {

    public static final String START = "QB_SPONSOR_ATTEMPT_START";
    public static final String SAVE = "QB_SPONSOR_ATTEMPT_SAVE";
    public static final String SUBMIT = "QB_SPONSOR_ATTEMPT_SUBMIT";
    public static final String RESULT = "QB_SPONSOR_ATTEMPT_RESULT";

    static final String SPONSOR = "play.sponsor.onboarding";
    static final String OPERATOR = "play.sponsor.operator";

    // ---- start -----------------------------------------------------------------------------------------------------

    public static ProcessDefinition<AttemptProcesses.PublicationRef, AttemptProcesses.StartOutput, ProcessContext>
        start(OptionShuffle shuffle, QuestionPick pick) {
        return ProcessDefinition.define(START, 1, AttemptProcesses.PublicationRef.class,
            AttemptProcesses.StartOutput.class, ProcessContext.class, pb -> pb
                .description("Starts the calling Sponsor's attempt of a challenge of their operator, or continues it.")
                .permissions(QbPermissions.SPONSOR_CHALLENGE)
                .requiresVerifiedEmail()
                .admission(PublicationMoves.START_LOCK_FAMILY, AttemptProcesses.PublicationRef::publicationId)
                .steps(CallerChecks::refuseBanned)
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put(INPUT, input);
                    ctx.put(AttemptProcesses.PUB_KEY, input.publicationId());
                    return ctx;
                })
                .outputMapper(ctx -> ctx.get(OUTPUT, AttemptProcesses.StartOutput.class))
                // One statement, as the Taker's: the caller's attempt, the card and the publication, with the
                // caller's onboarding and operator.
                .step("Load the caller's attempt, the card, the publication, the onboarding and the operator",
                    QueryEntities.<ProcessContext>all(
                        QueryEntities.query(QbPlay.defaultDataset(QbPlay.ATTEMPT),
                            ctx -> ChannelPlay.takers(ctx.request().actorId(), ctx.get(AttemptProcesses.PUB_KEY)),
                            AttemptProcesses.MINE),
                        PlaySteps.card(ctx -> ctx.get(AttemptProcesses.PUB_KEY), AttemptProcesses.CARD_BEFORE),
                        QueryEntities.query(QbPublications.defaultDataset(QbPublications.PUBLICATION),
                            ctx -> EntityQuery.builder().where(new QueryPredicate.In("publicationId",
                                ids(ctx.get(AttemptProcesses.PUB_KEY)))).limit(1).build(),
                            AttemptProcesses.PUBLICATION),
                        QueryEntities.query(QbAccounts.defaultDataset(QbAccounts.SPONSOR), ctx -> callers(ctx),
                            SPONSOR),
                        QueryEntities.query(QbOperators.defaultDataset(QbOperators.ASSIGNMENT), ctx -> callers(ctx),
                            OPERATOR)))
                .steps(b -> PlaySteps.loadVersion(b, ctx -> {
                    EntityInstance mine = first(ctx, AttemptProcesses.MINE);
                    if (mine != null) {
                        return mine.get("versionId");
                    }
                    EntityInstance card = first(ctx, AttemptProcesses.CARD_BEFORE);
                    return card == null ? null : card.get("versionId");
                }))
                .compute("Check the Sponsor and the challenge", (metadata, ctx) -> check(ctx))
                .compute("Continue an attempt in progress", (metadata, ctx) -> AttemptProcesses.resume(ctx))
                .step("Lock the publication's starts", HoldLock.<ProcessContext>exclusive(
                    ctx -> AttemptProcesses.starting(ctx)
                        ? PublicationMoves.startLockName(ctx.get(AttemptProcesses.PUB_KEY)) : null))
                .step("Load the caller's attempt, the card and the counters again",
                    QueryEntities.<ProcessContext>all(
                        QueryEntities.query(QbPlay.defaultDataset(QbPlay.ATTEMPT),
                            ctx -> ChannelPlay.takers(ctx.request().actorId(), AttemptProcesses.starting(ctx)
                                ? ctx.get(AttemptProcesses.PUB_KEY) : null), AttemptProcesses.MINE_AGAIN),
                        PlaySteps.card(ctx -> AttemptProcesses.starting(ctx) ? ctx.get(AttemptProcesses.PUB_KEY)
                            : null, AttemptProcesses.CARD),
                        PlaySteps.startCounter(ctx -> AttemptProcesses.starting(ctx)
                            ? ctx.get(AttemptProcesses.PUB_KEY) : null, AttemptProcesses.START_COUNTER),
                        PlaySteps.counter(ctx -> AttemptProcesses.starting(ctx) ? ctx.get(AttemptProcesses.PUB_KEY)
                            : null, AttemptProcesses.COUNTER)))
                .compute("Start the attempt", (metadata, ctx) -> {
                    ctx.put(AttemptProcesses.ON_BEHALF_OF, ctx.request().actorId());
                    // No region of a Sponsor's: the card's own (a challenge is its operator's, wherever it is aimed).
                    EntityInstance card = first(ctx, AttemptProcesses.CARD);
                    Object regions = card == null ? null : card.get("regions");
                    AttemptProcesses.start(ctx, shuffle, pick, ctx.request().actorId(),
                        regions == null ? Regions.GLOBAL : String.valueOf(regions));
                }));
    }

    /** The caller's rows (an account's onboarding and operator assignment are keyed by its user). */
    private static EntityQuery callers(ProcessContext ctx) {
        return EntityQuery.builder().where(new QueryPredicate.Eq("userId", ctx.request().actorId())).limit(1).build();
    }

    /**
     * 422 for a Sponsor whose onboarding is not approved; 404 unless the card is a challenge of the caller's operator
     * (its card keeps the operator of its publisher, the operator's direct Sponsor, PublicationNodes#mayBind).
     */
    static void check(ProcessContext ctx) {
        EntityInstance sponsor = first(ctx, SPONSOR);
        List<com.jabiz.entity.Violation> problems = SponsorChecks.check(
            sponsor == null ? null : sponsor.attributes(), null);
        if (!problems.isEmpty()) {
            throw new BusinessRuleViolationException(problems.getFirst());
        }
        EntityInstance card = first(ctx, AttemptProcesses.CARD_BEFORE);
        EntityInstance operator = first(ctx, OPERATOR);
        if (card == null || operator == null || !Boolean.TRUE.equals(card.get("forSponsors"))
            || !String.valueOf((Object) operator.get("operatorId")).equals(String.valueOf((Object) card.get("operatorId")))) {
            throw new EntityNotFoundException(QbPublications.PUBLICATION + " " + ctx.get(AttemptProcesses.PUB_KEY)
                + " not found");
        }
    }

    /** 404 unless the attempt is one the caller answers as a Sponsor (a Taker's attempt is not one). */
    static void requireSponsors(EntityInstance attempt, Object attemptId) {
        if (attempt == null || attempt.get("onBehalfOf") == null) {
            throw new EntityNotFoundException(QbPlay.ATTEMPT + " " + attemptId + " not found");
        }
    }

    // ---- save ------------------------------------------------------------------------------------------------------

    public static ProcessDefinition<AttemptProcesses.SaveInput, AttemptProcesses.SaveOutput, ProcessContext> save() {
        return ProcessDefinition.define(SAVE, 1, AttemptProcesses.SaveInput.class, AttemptProcesses.SaveOutput.class,
            ProcessContext.class, pb -> pb
                .description("Saves the current question and answers of the calling Sponsor's challenge attempt.")
                .permissions(QbPermissions.SPONSOR_CHALLENGE)
                .requiresVerifiedEmail()
                .steps(CallerChecks::refuseBanned)
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put(INPUT, input);
                    ctx.put(AttemptProcesses.ATTEMPT_KEY, input.attemptId());
                    return ctx;
                })
                .outputMapper(ctx -> ctx.get(OUTPUT, AttemptProcesses.SaveOutput.class))
                // The caller's own attempts (another person's is not found), and of those a Sponsor's only.
                .step("Load the attempt", LoadEntity.by(QbPlay.TAKER_ATTEMPTS, AttemptProcesses.ATTEMPT_KEY,
                    AttemptProcesses.ATTEMPT))
                .compute("Save the answers", (metadata, ctx) -> {
                    requireSponsors(ctx.get(AttemptProcesses.ATTEMPT, EntityInstance.class),
                        ctx.get(AttemptProcesses.ATTEMPT_KEY));
                    AttemptProcesses.save(ctx);
                }));
    }

    // ---- submit ----------------------------------------------------------------------------------------------------

    public static ProcessDefinition<AttemptFinishing.SubmitInput, AttemptFinishing.SubmitOutput, ProcessContext> submit(
        MessageCatalog messages) {
        return ProcessDefinition.define(SUBMIT, 1, AttemptFinishing.SubmitInput.class,
            AttemptFinishing.SubmitOutput.class, ProcessContext.class, pb -> {
                pb.description("Submits the calling Sponsor's challenge attempt: scores it; a pass lights its node "
                        + "for the Sponsor.")
                    .permissions(QbPermissions.SPONSOR_CHALLENGE)
                    .requiresVerifiedEmail()
                    .admission(PublicationMoves.LOCK_FAMILY, AttemptFinishing.SubmitInput::publicationId)
                    .steps(CallerChecks::refuseBanned)
                    .contextFactory((start, input) -> {
                        ProcessContext ctx = new ProcessContext(start);
                        ctx.put(INPUT, input);
                        ctx.put(AttemptFinishing.ATTEMPT_KEY, input.attemptId());
                        if (input.publicationId() != null) {
                            ctx.put(AttemptFinishing.GIVEN_PUBLICATION, input.publicationId());
                        }
                        return ctx;
                    })
                    .outputMapper(ctx -> ctx.get(OUTPUT, AttemptFinishing.SubmitOutput.class))
                    .step("Load the attempt", QueryEntities.<ProcessContext>of(QbPlay.TAKER_ATTEMPTS,
                        ctx -> AttemptFinishing.byId(ctx.get(AttemptFinishing.ATTEMPT_KEY)), AttemptFinishing.FOUND))
                    .compute("Check the attempt", (metadata, ctx) -> {
                        EntityInstance attempt = first(ctx, AttemptFinishing.FOUND);
                        requireSponsors(attempt, ctx.get(AttemptFinishing.ATTEMPT_KEY));
                        if (AttemptFinishing.mismatched(ctx, attempt)) {
                            return;
                        }
                        if (AttemptStatus.finished(attempt.get("status"))) {
                            ctx.reject(AttemptFinishing.finished(attempt));
                        } else {
                            ctx.put(AttemptFinishing.ACTIVE, true);
                        }
                    });
                AttemptFinishing.steps(pb, false, messages);
            });
    }

    // ---- result ----------------------------------------------------------------------------------------------------

    public static ProcessDefinition<AttemptResults.ResultInput, AttemptResults.ResultOutput, ProcessContext> result() {
        return ProcessDefinition.define(RESULT, 1, AttemptResults.ResultInput.class,
            AttemptResults.ResultOutput.class, ProcessContext.class, pb -> pb
                .description("Reads the result of one of the calling Sponsor's challenge attempts.")
                .permissions(QbPermissions.SPONSOR_CHALLENGE)
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put(INPUT, input);
                    ctx.put(AttemptResults.ATTEMPT_KEY, input.attemptId());
                    return ctx;
                })
                .outputMapper(ctx -> ctx.get(OUTPUT, AttemptResults.ResultOutput.class))
                .step("Load the attempt", LoadEntity.by(QbPlay.TAKER_ATTEMPTS, AttemptResults.ATTEMPT_KEY,
                    AttemptResults.ATTEMPT))
                .compute("Check the attempt", (metadata, ctx) -> requireSponsors(
                    ctx.get(AttemptResults.ATTEMPT, EntityInstance.class), ctx.get(AttemptResults.ATTEMPT_KEY)))
                .steps(AttemptResults::readSteps));
    }

    private SponsorPlay() {}
}
