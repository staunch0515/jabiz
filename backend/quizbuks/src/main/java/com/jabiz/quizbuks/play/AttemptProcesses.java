package com.jabiz.quizbuks.play;

import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.content.QuizSnapshot;
import com.jabiz.quizbuks.identity.CallerChecks;
import com.jabiz.quizbuks.invitation.InvitationStatus;
import com.jabiz.quizbuks.audience.AudienceCodes;
import com.jabiz.quizbuks.audience.AudienceRows;
import com.jabiz.quizbuks.invitation.Invitations;
import com.jabiz.quizbuks.invitation.QbInvitations;
import com.jabiz.quizbuks.reward.CashRewardChecks;
import com.jabiz.quizbuks.country.Regions;
import com.jabiz.quizbuks.publishing.PublicationMoves;
import com.jabiz.quizbuks.publishing.PublicationRules;
import com.jabiz.quizbuks.publishing.PublisherKind;
import com.jabiz.quizbuks.publishing.QbPublications;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.quizbuks.wallet.QbPayouts;
import com.jabiz.quizbuks.wallet.QbWallets;
import com.jabiz.quizbuks.wallet.TakerWallets;
import com.jabiz.quizbuks.wallet.TransferStatus;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.EntityNotFoundException;
import com.jabiz.runtime.ledger.LedgerProcesses;
import com.jabiz.runtime.process.steps.CallProcess;
import com.jabiz.runtime.process.steps.HoldLock;
import com.jabiz.runtime.process.steps.LoadEntity;
import com.jabiz.runtime.process.steps.QueryEntities;
import com.jabiz.runtime.process.steps.RunTemplate;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.jabiz.quizbuks.play.PlaySteps.INPUT;
import static com.jabiz.quizbuks.play.PlaySteps.OUTPUT;
import static com.jabiz.quizbuks.play.PlaySteps.first;
import static com.jabiz.quizbuks.play.PlaySteps.ids;

/**
 * A taker's way into a quiz (U-20 – U-23, U-30 – U-31, U-41, 5.3-3, 5.3-5, 5.3-6; docs/quizbuks/plans/
 * Q5-answering.md, D-Q5-1, D-Q5-3, D-Q5-5, D-Q5-6):
 * <ul>
 *   <li>{@code QB_VIEW}: records the first time a taker opens a publication they can see;</li>
 *   <li>{@code QB_ATTEMPT_START}: starts the one attempt a taker has of a publication, or returns the one in progress
 *       (continuing it), with the questions and their options in the attempt's order and never the correct ones;</li>
 *   <li>{@code QB_ATTEMPT_SAVE}: saves the current question and some answers, before the deadline.</li>
 * </ul>
 * Every one is the caller's own (the user is the caller, never the input), needs a verified address and refuses a
 * banned caller first. Only a new start takes a lock: the publication's start lock {@code qb.pub-start:<publication>}
 * (Q10a4), around the starts' side of the counters it reserves on; every start queues for it before its transaction
 * (platform decision D45). The completions hold the publication's lock {@code qb.pub:<publication>}, so the two run
 * side by side (docs/quizbuks/plans/Q10-lock-split.md).
 */
public final class AttemptProcesses {

    public static final String VIEW = "QB_VIEW";
    public static final String START = "QB_ATTEMPT_START";
    public static final String SAVE = "QB_ATTEMPT_SAVE";

    /**
     * @param language Q73b: the language to answer in when starting, one the publication offers (422
     *                 {@code QB_ATTEMPT_LANGUAGE} otherwise); its main language when not given. A view and a continued
     *                 attempt take none (an attempt keeps the language it started in).
     * @param playback Q75: what the App read from the video's player (seconds watched, whether it ended, or that it
     *                 could not read it); a card whose video must be watched refuses a new start without enough of
     *                 it ({@link WatchRules}, 422 {@code QB_ATTEMPT_WATCH_REQUIRED}). Recorded on the attempt.
     */
    public record PublicationRef(@NotNull UUID publicationId, @jakarta.validation.constraints.Pattern(
        regexp = "ja|zh|en") String language, @Valid WatchRules.Playback playback) {

        public PublicationRef(UUID publicationId) {
            this(publicationId, null, null);
        }

        public PublicationRef(UUID publicationId, String language) {
            this(publicationId, language, null);
        }
    }

    /** @param firstView whether this view was the caller's first of the publication (and was recorded) */
    public record ViewOutput(UUID publicationId, boolean firstView) {}

    /** @param no the option's position in this attempt, 1..k in the order shown (Q10b, M1): what answers name */
    public record OptionView(int no, String text, UUID image) {}

    /**
     * @param selection {@code SINGLE} or {@code MULTIPLE}: a survey's question as set (Q12), a scored one by how many of
     *                  its options are correct ({@link QuizSnapshot.Question#choice}), so that a single choice is shown
     *                  with radio buttons
     */
    public record QuestionView(int no, String stem, UUID image, int points, List<OptionView> options,
        String selection) {}

    /**
     * An attempt as its taker answers it: the questions in order, each one's options in the attempt's order and
     * without saying which are correct; the answers so far and the current question (index from 0).
     *
     * @param deadline   when it must be submitted, by the server's clock ({@code serverTime} is that clock now)
     * @param resumed    whether the attempt was already in progress (continuing it)
     * @param kind       {@code QUIZ} or {@code SURVEY} (Q12): a survey is submitted only with every question answered
     */
    public record StartOutput(String attemptId, UUID publicationId, String status, Instant startedAt,
        Instant deadline, Instant serverTime, int timeLimitSec, int currentIndex, boolean resumed,
        List<QuestionView> questions, List<Answers.Given> answers, String kind) {}

    /**
     * @param answers      answers of some questions, each replacing that question's (an empty list clears it)
     * @param currentIndex the question the taker is on, from 0; unchanged when not given
     */
    public record SaveInput(@NotBlank @Size(max = 36) String attemptId, Integer currentIndex,
        @Size(max = PlayLimits.MAX_PER_ATTEMPT_TECH) List<Answers.@Valid @NotNull Given> answers) {}

    public record SaveOutput(String attemptId, int currentIndex, List<Answers.Given> answers, Instant deadline,
        Instant serverTime) {}

    static final String PUB_KEY = "play.publicationId";
    static final String ATTEMPT_KEY = "play.attemptId";
    static final String MINE = "play.mine";
    static final String MINE_AGAIN = "play.mineAgain";
    static final String CARD_BEFORE = "play.cardBefore";
    /** The publication, read only so that the new attempt's reference to it needs no lookup under the lock. */
    static final String PUBLICATION = "play.publication";
    static final String CARD = "play.card";
    static final String COUNTER = "play.counter";
    static final String START_COUNTER = "play.startCounter";
    static final String WALLET = "play.wallet";
    static final String CONDITIONS = "play.conditions";
    static final String ATTRIBUTES = "play.attributes";
    static final String VIEWS = "play.views";
    static final String ATTEMPT = "play.attempt";
    static final String NEW = "play.new";
    /**
     * Q88b: a publication charging an entry fee reads, under the taker's wallet lock, their wallet row, the hold
     * period's parameter and their transferable balance; the posting of the fee, once the attempt is written.
     */
    static final String FEE_WALLET = "play.feeWallet";
    static final String FEE_PARAM = "play.feeParam";
    static final String FEE_REVIEW = "play.feeReview";
    /** The fee the check found covered: the start charges it, and only it. */
    static final String FEE_CHECKED = "play.feeChecked";
    static final String TRANSFERABLE = "play.transferable";
    static final String FEE_POSTING = "play.feePosting";
    static final String FEE_POSTED = "play.feePosted";

    // ---- view ------------------------------------------------------------------------------------------------------

    public static ProcessDefinition<PublicationRef, ViewOutput, ProcessContext> view() {
        return ProcessDefinition.define(VIEW, 1, PublicationRef.class, ViewOutput.class, ProcessContext.class,
            pb -> pb
                .description("Records the first time the caller opens a publication's page.")
                .permissions(QbPermissions.PLAY)
                .requiresVerifiedEmail()
                .steps(CallerChecks::refuseBanned)
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put(INPUT, input);
                    ctx.put(PUB_KEY, input.publicationId());
                    return ctx;
                })
                .outputMapper(ctx -> ctx.get(OUTPUT, ViewOutput.class))
                .steps(b -> PlaySteps.loadCard(b, "Load the card", ctx -> ctx.get(PUB_KEY), CARD))
                .step("Load the caller's attempt", QueryEntities.<ProcessContext>of(QbPlay.TAKER_ATTEMPTS,
                    ctx -> mine(ctx.get(PUB_KEY)), MINE))
                .steps(b -> PlaySteps.loadRegions(b, ctx -> ctx.request().actorId()))
                // Q25: an invited card only (no statement otherwise): the caller's invitation, by account or address.
                .steps(b -> Invitations.loadForTaker(b,
                    ctx -> PlaySteps.invited(first(ctx, CARD)) ? ctx.get(PUB_KEY) : null))
                .step("Load an earlier view", QueryEntities.<ProcessContext>of(QbPlay.defaultDataset(QbPlay.VIEW),
                    ctx -> EntityQuery.builder().where(new QueryPredicate.And(List.of(
                        new QueryPredicate.Eq("userId", ctx.request().actorId()),
                        new QueryPredicate.Eq("publicationId", ctx.get(PUB_KEY))))).limit(1).build(), VIEWS))
                .compute("Record the view", (metadata, ctx) -> view(ctx)));
    }

    static void view(ProcessContext ctx) {
        UUID publicationId = ctx.get(PUB_KEY, UUID.class);
        EntityInstance card = first(ctx, CARD);
        String userId = ctx.request().actorId();
        // S2: a channel's publication (a shop's storefront quiz) is no App taker's: as good as none.
        if (card == null || !PublisherKind.sponsor(card.get("publisherKind"))
            || !(first(ctx, MINE) != null || shown(ctx, card))) {
            throw new EntityNotFoundException(QbPublications.PUBLICATION + " " + publicationId + " not found");
        }
        boolean first = first(ctx, VIEWS) == null;
        if (first) {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("userId", userId);
            values.put("publicationId", publicationId);
            values.put("firstViewedAt", ctx.opTime());
            ctx.changes().insert(QbPlay.VIEW, values);
        }
        // Q25: the view claims an invitation addressed to the caller's verified address, so that the page (which reads
        // the invitations of the caller's account) shows the publication from now on.
        EntityInstance invitation = PlaySteps.invited(card) ? Invitations.mine(ctx) : null;
        if (invitation != null) {
            Map<String, Object> claim = Invitations.claim(ctx, invitation);
            if (!claim.isEmpty()) {
                ctx.changes().update(QbInvitations.INVITATION, invitation.id(), invitation.version(), claim);
            }
        }
        ctx.put(OUTPUT, new ViewOutput(publicationId, first));
    }

    /**
     * Whether the publication's page is shown to the caller, as {@code qb.app.publication} decides (an attempt of
     * theirs aside): live, within its time and aimed at a region of their country, or, for an invited publication
     * (Q25), the caller invited. Its winners, budget and sponsor's funds decide only whether an attempt can start.
     */
    private static boolean shown(ProcessContext ctx, EntityInstance card) {
        return PublicationRules.acceptsNewAttempts(card.get("status"), PlaySteps.instant(card.get("startAt")),
            PlaySteps.instant(card.get("endAt")), ctx.opTime())
            && (PlaySteps.invited(card) ? Invitations.mine(ctx) != null
                : Regions.overlaps(PlaySteps.userRegions(ctx), card.get("regions")));
    }

    private static Object sponsorOf(EntityInstance card) {
        return card == null ? null : card.get("publisherId");
    }

    // ---- start -----------------------------------------------------------------------------------------------------

    /** @param shuffle the order of each attempt's options, keyed by the server (Q10b, review M-a) */
    public static ProcessDefinition<PublicationRef, StartOutput, ProcessContext> start(OptionShuffle shuffle,
        QuestionPick pick, com.jabiz.quizbuks.region.RegionRefusals refusals) {
        return ProcessDefinition.define(START, 1, PublicationRef.class, StartOutput.class, ProcessContext.class,
            pb -> pb
                .description("Starts the caller's attempt of a publication, or continues the one in progress.")
                .permissions(QbPermissions.PLAY)
                .requiresVerifiedEmail()
                // Queues for the publication's start lock before the transaction (platform decision D45, Q10a;
                // Q10a4: its own family, apart from the completions'): a hot publication's waiters hold no
                // connection. A continuing start queues too (16o A-7): it learns only from its reads that it takes
                // no lock.
                .admission(PublicationMoves.START_LOCK_FAMILY, PublicationRef::publicationId)
                .steps(CallerChecks::refuseBanned)
                // Q22: a taker with a cash reward they neither confirmed nor disputed in time starts no new attempt.
                .steps(CashRewardChecks::refuseOverdue)
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put(INPUT, input);
                    ctx.put(PUB_KEY, input.publicationId());
                    return ctx;
                })
                .outputMapper(ctx -> ctx.get(OUTPUT, StartOutput.class))
                // Outside the lock: the caller's attempt, the frozen part of the card, the regions and the version.
                // The publication too, in the same statement: the new attempt references it, and the platform checks
                // a reference against what this transaction read through the target's default dataset instead of
                // looking it up again (platform decision D46 rule 3), so that lookup leaves the lock (Q10a3). Only a
                // draft can be deleted and a draft has no card (no start), so the read before the lock answers it.
                .step("Load the caller's attempt, the card and the publication", QueryEntities.<ProcessContext>all(
                    QueryEntities.query(QbPlay.TAKER_ATTEMPTS, ctx -> mine(ctx.get(PUB_KEY)), MINE),
                    PlaySteps.card(ctx -> ctx.get(PUB_KEY), CARD_BEFORE),
                    QueryEntities.query(QbPublications.defaultDataset(QbPublications.PUBLICATION),
                        ctx -> EntityQuery.builder().where(new QueryPredicate.In("publicationId",
                            ids(ctx.get(PUB_KEY)))).limit(1).build(), PUBLICATION),
                    // Q47: the publication's conditions and the caller's attributes, in the same statement (the
                    // statement budget stays); conditions are frozen with the publication, so not read again.
                    AudienceRows.conditions(ctx -> ctx.get(PUB_KEY), CONDITIONS),
                    AudienceRows.callers(ATTRIBUTES),
                    // F-S8: whether the caller has any block (one row at most), in the same statement.
                    com.jabiz.quizbuks.block.BlockStart.any()))
                .steps(b -> PlaySteps.loadRegions(b, ctx -> ctx.request().actorId()))
                // Q51: an attempt keeps the version it started with, whichever the publication has switched to since;
                // a new one takes the card's (checked again against the card read under the lock).
                .steps(b -> PlaySteps.loadVersion(b, ctx -> {
                    EntityInstance mine = first(ctx, MINE);
                    if (mine != null) {
                        return mine.get("versionId");
                    }
                    EntityInstance card = first(ctx, CARD_BEFORE);
                    return card == null ? null : card.get("versionId");
                }))
                .compute("Refuse a channel's publication", (metadata, ctx) -> {
                    // S2: a shop's storefront quiz is answered on the storefront only (ChannelPlay): an App taker
                    // would read its correct options in their result. As good as none.
                    EntityInstance card = first(ctx, CARD_BEFORE);
                    if (card != null && !PublisherKind.sponsor(card.get("publisherKind"))) {
                        throw new EntityNotFoundException(QbPublications.PUBLICATION + " " + ctx.get(PUB_KEY)
                            + " not found");
                    }
                })
                .compute("Continue an attempt in progress", (metadata, ctx) -> resume(ctx))
                // F-S8: a new start of a publication the caller blocked (its publication, quiz, sponsor or video's
                // provider) is refused, whatever it pays. A caller without blocks sends nothing more (the first read
                // said so); with blocks, one statement before any lock reads those covering the card.
                .steps(b -> com.jabiz.quizbuks.block.BlockStart.load(b, ctx -> first(ctx, CARD_BEFORE),
                    AttemptProcesses::starting))
                .compute("Refuse a blocked publication", (metadata, ctx) -> com.jabiz.quizbuks.block.BlockStart
                    .refuse(ctx, first(ctx, CARD_BEFORE), starting(ctx)))
                // F-S11: a new start of a card with a foreign video, from mainland China (by the address; an unknown
                // one by the parameter), is refused unless headquarters made an exception. Before any lock; an
                // ordinary start reads nothing more (the statement budget stays). Invited publications alike.
                .steps(b -> com.jabiz.quizbuks.region.RegionStart.load(b, ctx -> first(ctx, CARD_BEFORE),
                    AttemptProcesses::starting))
                .compute("Refuse a foreign video in mainland China", (metadata, ctx) -> com.jabiz.quizbuks.region
                    .RegionStart.refuse(ctx, first(ctx, CARD_BEFORE), ctx.get(PUB_KEY), starting(ctx), refusals))
                // Q75 (D-Q75-4): a card whose video must be watched refuses a new start without the App's report of
                // enough watching; the card read outside the lock says so and how long the video is. A continued
                // attempt is not asked again. No read: nothing changes in the statement budget (D-Q75-7).
                .compute("Refuse an unwatched video", (metadata, ctx) -> refuseUnwatched(ctx))
                // F-S2b1: a publication bound to a knowledge graph's node reads the node's row with the caller's lit
                // records and revocations, one statement more before the lock (an ordinary publication's queries are
                // empty and send nothing); the nodes to be lit before answering must be.
                .steps(b -> TreeGate.load(b, ctx -> starting(ctx) ? first(ctx, CARD_BEFORE) : null))
                .compute("Refuse a node not unlocked", (metadata, ctx) -> {
                    if (starting(ctx)) {
                        TreeGate.refuse(ctx, "publicationId", first(ctx, CARD_BEFORE));
                    }
                })
                // Q88b (D-Q88-10, D-Q88-12): a publication charging an entry fee takes the caller's wallet lock
                // before the publication's start lock (nobody takes them the other way round: completions take the
                // publication's and the sponsor's wallet's, syncs and transfers the taker's alone) and reads under it
                // the wallet row with the hold period's parameter and a transfer in review (one statement) and the
                // transferable balance (the transfers' template); not enough is refused before the start lock.
                // A transfer in review is not posted: what it sets aside is not the fee's (its approval re-checks).
                // Unfinished transfers are one per taker (a partial unique index), so one row at most. An ordinary publication takes no
                // lock and sends nothing here: its queries are empty and the template's condition does not hold.
                .step("Lock the taker's wallet for the entry fee", HoldLock.<ProcessContext>exclusive(
                    ctx -> charging(ctx) ? QbWallets.lockName(QbWallets.TAKER, ctx.request().actorId()) : null))
                .step("Read the taker's wallet, the hold period and a transfer in review",
                    QueryEntities.<ProcessContext>all(
                    QueryEntities.query(QbWallets.defaultDataset(QbWallets.WALLET), ctx -> QbWallets.query(
                        QbWallets.TAKER, charging(ctx) ? ctx.request().actorId() : null), FEE_WALLET),
                    QueryEntities.query(QbWallets.PARAMS_NOW, ctx -> EntityQuery.builder()
                        .where(new QueryPredicate.In("paramKey", charging(ctx)
                            ? List.of(QbParams.TRANSFER_HOLD_DAYS) : List.of())).limit(1).build(), FEE_PARAM),
                    QueryEntities.query(QbPayouts.defaultDataset(QbPayouts.TRANSFER), ctx -> EntityQuery.builder()
                        .where(new QueryPredicate.And(List.of(
                            new QueryPredicate.In("userId", charging(ctx)
                                ? List.of(String.valueOf(ctx.request().actorId())) : List.of()),
                            new QueryPredicate.In("status", List.of(TransferStatus.REVIEW))))).limit(1).build(),
                        FEE_REVIEW)))
                .step("Read the taker's transferable balance", RunTemplate.<ProcessContext>when(
                    AttemptProcesses::charging, TakerWallets.TRANSFERABLE_TEMPLATE, ctx -> {
                        Map<String, Object> params = new LinkedHashMap<>();
                        params.put("userId", String.valueOf(ctx.request().actorId()));
                        params.put("at", ctx.opTime());
                        params.put("holdDays", EntryFees.holdDays(PlaySteps.list(ctx, FEE_PARAM))
                            .orElse(BigDecimal.ZERO));
                        return params;
                    }, TRANSFERABLE))
                .compute("Refuse an entry fee not covered", (metadata, ctx) -> refuseUnpaidFee(ctx))
                // Without the wallet's lock (D-Q5-5): a soft check, so before the publication's lock too (Q10a: one
                // read less while it is held); a reward the sponsor cannot pay at the end is pending.
                .step("Read the sponsor's wallet", QueryEntities.<ProcessContext>of(
                    QbWallets.defaultDataset(QbWallets.WALLET), ctx -> QbWallets.query(QbWallets.SPONSOR,
                        starting(ctx) ? sponsorOf(first(ctx, CARD_BEFORE)) : null), WALLET))
                // A new attempt only: under the publication's start lock (Q10a4: not the publication's lock, which
                // the completions hold), the attempt, the state and both sides of the counters again.
                .step("Lock the publication's starts", HoldLock.<ProcessContext>exclusive(
                    ctx -> starting(ctx) ? PublicationMoves.startLockName(ctx.get(PUB_KEY)) : null))
                // One statement (platform decision D46, Q10a3): each round trip under the lock is ~0.8 ms of it.
                // The starts' side is exact (only starts write it, under this lock); the completions' side is as of
                // the statement, which only makes the check of the limits stricter (Q10-lock-split.md 2.3).
                .step("Load the caller's attempt, the card and the counters again", QueryEntities.<ProcessContext>all(
                    QueryEntities.query(QbPlay.TAKER_ATTEMPTS, ctx -> mine(starting(ctx) ? ctx.get(PUB_KEY) : null),
                        MINE_AGAIN),
                    PlaySteps.card(ctx -> starting(ctx) ? ctx.get(PUB_KEY) : null, CARD),
                    PlaySteps.startCounter(ctx -> starting(ctx) ? ctx.get(PUB_KEY) : null, START_COUNTER),
                    PlaySteps.counter(ctx -> starting(ctx) ? ctx.get(PUB_KEY) : null, COUNTER)))
                // Q25: an invited card only (no statement otherwise): the caller's invitation, under the start lock
                // (the start writes it), by their account or their verified address.
                .steps(b -> Invitations.loadForTaker(b,
                    ctx -> starting(ctx) && PlaySteps.invited(first(ctx, CARD_BEFORE)) ? ctx.get(PUB_KEY) : null))
                .compute("Start the attempt", (metadata, ctx) -> start(ctx, shuffle, pick))
                // Q88b: the fee's posting, with the attempt (a start without a fee posts nothing). The posting's
                // source is the attempt, which the ledger finds through its dataset: saved first, only then.
                .step("Save the attempt for its fee's posting", com.jabiz.runtime.process.steps.SaveChanges
                    .<ProcessContext>when(ctx -> !ctx.hasViolations() && ctx.contains(FEE_POSTING)))
                .step("Post the entry fee", CallProcess.<ProcessContext>when(
                    ctx -> !ctx.hasViolations() && ctx.contains(FEE_POSTING), LedgerProcesses.POST, 1,
                    ctx -> ctx.get(FEE_POSTING), FEE_POSTED)));
    }

    /** Q88b: whether this start charges an entry fee (a new attempt of a card that charges one). */
    static boolean charging(ProcessContext ctx) {
        return starting(ctx) && EntryFees.charges(first(ctx, CARD_BEFORE));
    }

    /** Q88b (D-Q88-10): under the taker's wallet lock, before the publication's: the fee must be covered. */
    static void refuseUnpaidFee(ProcessContext ctx) {
        if (!charging(ctx)) {
            return;
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) ctx.get(TRANSFERABLE);
        Map<String, Object> row = rows == null || rows.isEmpty() ? Map.of() : rows.getFirst();
        // An unusable hold period refuses (nothing is known to be free of it): nothing counts as available.
        BigDecimal available = EntryFees.holdDays(PlaySteps.list(ctx, FEE_PARAM)).isPresent()
            ? PlaySteps.moneyOrNull(row.get("available")) : BigDecimal.ZERO;
        BigDecimal fee = EntryFees.fee(first(ctx, CARD_BEFORE));
        EntryFees.shortfall(available, PlaySteps.moneyOrNull(row.get("held")),
            EntryFees.inReview(PlaySteps.list(ctx, FEE_REVIEW)), first(ctx, FEE_WALLET), fee)
            .ifPresentOrElse(ctx::reject, () -> ctx.put(FEE_CHECKED, fee));
    }

    static EntityQuery mine(Object publicationId) {
        return EntityQuery.builder().where(new QueryPredicate.In("publicationId", ids(publicationId))).limit(1)
            .build();
    }

    static boolean starting(ProcessContext ctx) {
        return Boolean.TRUE.equals(ctx.get(NEW)) && !ctx.hasViolations();
    }

    /** The App's report of the playback, when the input is the App's (a channel's start has none). */
    static WatchRules.Playback playback(ProcessContext ctx) {
        return ctx.get(INPUT) instanceof PublicationRef ref ? ref.playback() : null;
    }

    static void refuseUnwatched(ProcessContext ctx) {
        if (!starting(ctx)) {
            return;
        }
        EntityInstance card = first(ctx, CARD_BEFORE);
        if (!WatchRules.required(card)) {
            return;
        }
        Integer duration = WatchRules.duration(card);
        WatchRules.Playback playback = playback(ctx);
        if (!WatchRules.satisfied(duration, playback)) {
            ctx.reject(new com.jabiz.entity.Violation("playback", PlayCodes.WATCH_REQUIRED, "The video must be "
                + "watched before answering", Map.of("shortfallSec", WatchRules.shortfallSec(duration, playback),
                "durationSec", duration == null ? 0 : duration)));
        }
    }

    /** An attempt of the caller's already exists: continue it while in time, else refuse; or note a new start. */
    static void resume(ProcessContext ctx) {
        EntityInstance mine = first(ctx, MINE);
        if (mine != null) {
            continueOrRefuse(ctx, mine);
            return;
        }
        if (first(ctx, CARD_BEFORE) == null) {
            // Never went on (or does not exist): not a publication anybody can answer.
            throw new EntityNotFoundException(QbPublications.PUBLICATION + " " + ctx.get(PUB_KEY) + " not found");
        }
        ctx.put(NEW, true);
    }

    private static void continueOrRefuse(ProcessContext ctx, EntityInstance attempt) {
        if (!AttemptStatus.finished(attempt.get("status")) && !sameVersion(ctx, attempt.get("versionId"))) {
            // Another start of the caller's went first, on a version this one did not read (Q51): start again.
            versionChanged(ctx);
            return;
        }
        if (AttemptStatus.finished(attempt.get("status"))) {
            ctx.reject(new com.jabiz.entity.Violation("publicationId", PlayCodes.ALREADY_TAKEN, "The caller has "
                + "already answered this publication", Map.of("attemptId", String.valueOf(attempt.id()))));
            return;
        }
        Instant deadline = PlaySteps.instant(attempt.get("deadline"));
        if (AttemptRules.pastDeadline(deadline, ctx.opTime())) {
            ctx.reject(new com.jabiz.entity.Violation("publicationId", PlayCodes.EXPIRED, "The time of the "
                + "attempt is over", Map.of("deadline", deadline.toString())));
            return;
        }
        ctx.put(OUTPUT, output(ctx, attempt, PlaySteps.snapshot(ctx), true));
    }

    /** Whether {@code versionId} is the version this start loaded ({@link PlaySteps#VERSION}). */
    private static boolean sameVersion(ProcessContext ctx, Object versionId) {
        EntityInstance version = first(ctx, PlaySteps.VERSION);
        return version != null && versionId != null && String.valueOf(version.id()).equals(String.valueOf(versionId));
    }

    private static void versionChanged(ProcessContext ctx) {
        ctx.reject(new com.jabiz.entity.Violation("publicationId", PlayCodes.VERSION_CHANGED, "The publication "
            + "switched to another version meanwhile: start again", Map.of("publicationId",
            String.valueOf(ctx.get(PUB_KEY)))));
    }

    static void start(ProcessContext ctx, OptionShuffle shuffle, QuestionPick pick) {
        start(ctx, shuffle, pick, ctx.request().actorId(), PlaySteps.userRegions(ctx));
    }

    /**
     * Starts the attempt of {@code userId}, whose country is in {@code userRegions}: the caller for a taker of the
     * App, the channel's taker for a channel's publication ({@link ChannelPlay}, S2).
     */
    static void start(ProcessContext ctx, OptionShuffle shuffle, QuestionPick pick, String userId,
        String userRegions) {
        if (!starting(ctx)) {
            return;
        }
        // Another start of the caller's went first while this one waited for the lock: continue that one.
        EntityInstance mine = first(ctx, MINE_AGAIN);
        if (mine != null) {
            continueOrRefuse(ctx, mine);
            return;
        }
        EntityInstance card = first(ctx, CARD);
        EntityInstance starts = first(ctx, START_COUNTER);
        EntityInstance counter = first(ctx, COUNTER);
        if (card == null || starts == null || counter == null) {
            throw new IllegalStateException("A publication that went on has a card and both sides of its counters: "
                + ctx.get(PUB_KEY));
        }
        EntityInstance wallet = first(ctx, WALLET);
        EntityInstance invitation = PlaySteps.invited(card) ? Invitations.mine(ctx) : null;
        String refusal = AttemptRules.startRefusal(userId, userRegions, invitation != null,
            PlaySteps.cardOf(card), PlaySteps.counts(starts, counter), QbWallets.available(wallet),
            QbWallets.pendingCount(wallet), ctx.opTime());
        if (refusal != null) {
            ctx.reject(new com.jabiz.entity.Violation("publicationId", refusal, "The attempt cannot start: "
                + refusal, Map.of("publicationId", String.valueOf(ctx.get(PUB_KEY)))));
            return;
        }
        // Q47: an invited publication takes its list; a sponsor's public one only the takers whose profile meets it.
        // A channel's publication has no conditions and its takers no profile (ChannelPlay reads neither): never asked.
        if (PublisherKind.sponsor(card.get("publisherKind")) && !PlaySteps.invited(card) && !AudienceRows.matches(PlaySteps.list(ctx, CONDITIONS),
            PlaySteps.list(ctx, ATTRIBUTES), ctx.opTime())) {
            ctx.reject(new com.jabiz.entity.Violation("publicationId", AudienceCodes.NOT_MATCHED, "The caller's "
                + "profile does not meet the publication's conditions", Map.of("publicationId",
                String.valueOf(ctx.get(PUB_KEY)))));
            return;
        }
        if (!sameVersion(ctx, card.get("versionId"))) {
            // The publication switched its version between the read before the lock and this one (Q51).
            versionChanged(ctx);
            return;
        }
        // Q73b: the language asked for, one the card offers; else the card's main one.
        List<String> offered = com.jabiz.quizbuks.publishing.PublicationLanguages.offered(card.get("languages"),
            card.get("language"));
        String asked = ctx.get(INPUT) instanceof PublicationRef ref ? ref.language() : null;
        if (asked != null && !offered.contains(asked)) {
            ctx.reject(new com.jabiz.entity.Violation("language", PlayCodes.LANGUAGE, "The publication is not "
                + "offered in " + asked, Map.of("language", asked)));
            return;
        }
        String language = asked != null ? asked : offered.getFirst();
        QuizSnapshot snapshot = PlaySteps.snapshot(ctx);
        Object publicationId = ctx.get(PUB_KEY);
        Instant deadline = AttemptRules.deadline(ctx.opTime(), PlaySteps.integer(card.get("timeLimitSec")));
        // Q81 (D-Q81-3, D-Q81-4): a publication drawing k questions gives this taker their set, in the version's order;
        // from here on the attempt's snapshot is that subset (the language first: its texts are keyed by the
        // version's numbers).
        Integer pickCount = PlaySteps.integerOrNull(card.get("pickCount"));
        List<Integer> questionSet = pickCount == null ? null
            : pick.pick(userId, publicationId, snapshot.questions(), pickCount);
        QuizSnapshot given = questionSet == null ? snapshot : snapshot.subset(questionSet);
        List<List<Integer>> order = shuffle.order(userId, publicationId, given.questions());
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("userId", userId);
        values.put("publicationId", publicationId);
        values.put("publisherKind", card.get("publisherKind"));
        values.put("publisherId", card.get("publisherId"));
        values.put("versionId", card.get("versionId"));
        values.put("language", language);
        values.put("startedAt", ctx.opTime());
        values.put("deadline", deadline);
        values.put("status", AttemptStatus.IN_PROGRESS);
        values.put("currentIndex", BigDecimal.ZERO);
        values.put("optionOrder", Answers.toJson(order));
        values.put("answers", Answers.toJson(Answers.none(order.size())));
        values.put("questionSet", QuestionPick.toJson(questionSet));
        // Q75 (D-Q75-9): the App's report of the playback, in the same insert (the budget stays).
        values.putAll(WatchRules.values(card, playback(ctx)));
        // Q88b: the entry fee paid at this start (0 for a card charging none, and for a channel's taker).
        BigDecimal entryFee = ctx.contains(FEE_CHECKED) ? (BigDecimal) ctx.get(FEE_CHECKED) : BigDecimal.ZERO;
        values.put("entryFee", entryFee);
        Object attemptId = ctx.changes().insert(QbPlay.ATTEMPT, values);
        if (entryFee.signum() > 0) {
            // The wallet row read under its lock, reduced at that version (D-Q88-12); the cache follows the ledger
            // otherwise by events, which this keeps in step. The posting goes with the attempt (the step after).
            EntityInstance feeWallet = first(ctx, FEE_WALLET);
            QbWallets.change(ctx, feeWallet, QbWallets.TAKER, userId, entryFee.negate(), 0, BigDecimal.ZERO);
            ctx.put(FEE_POSTING, EntryFees.posting(ctx.opTime(), String.valueOf(attemptId), userId,
                String.valueOf((Object) card.get("publisherId")), String.valueOf(publicationId), entryFee));
        }
        BigDecimal maxReward = PlaySteps.money(card.get("maxReward"));
        // Only the starts' side (Q10a4): the completions' side is the publication's lock's.
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("starts", BigDecimal.valueOf(PlaySteps.number(starts.get("starts")) + 1));
        counts.put("reservedTotal", PlaySteps.money(starts.get("reservedTotal")).add(maxReward));
        ctx.changes().update(QbPublications.START_COUNTER, starts.id(), starts.version(), counts);
        if (invitation != null) {
            // Q25: the invitation carries the attempt from now on (its answer is recorded on it when it finishes).
            Map<String, Object> claimed = new LinkedHashMap<>(Invitations.claim(ctx, invitation));
            claimed.put("status", InvitationStatus.STARTED);
            claimed.put("attemptId", String.valueOf(attemptId));
            claimed.put("startedAt", ctx.opTime());
            ctx.changes().update(QbInvitations.INVITATION, invitation.id(), invitation.version(), claimed);
        }
        ctx.put(OUTPUT, new StartOutput(String.valueOf(attemptId), PlaySteps.uuid(publicationId),
            AttemptStatus.IN_PROGRESS, ctx.opTime(), deadline, ctx.opTime(),
            PlaySteps.integer(card.get("timeLimitSec")), 0, false,
            questions(questionSet == null ? com.jabiz.quizbuks.content.QuizSnapshots.in(snapshot, language)
                : com.jabiz.quizbuks.content.QuizSnapshots.in(snapshot, language).subset(questionSet), order),
            List.of(), snapshot.kind()));
    }

    /** The output of an attempt in progress. */
    private static StartOutput output(ProcessContext ctx, EntityInstance attempt, QuizSnapshot snapshot,
        boolean resumed) {
        List<List<Integer>> order = Answers.fromJson(attempt.get("optionOrder"));
        // Q73b: the questions in the attempt's language (the version's base for one made before); Q81: only the
        // ones it was given.
        snapshot = PlaySteps.forAttempt(snapshot, attempt);
        Instant startedAt = PlaySteps.instant(attempt.get("startedAt"));
        Instant deadline = PlaySteps.instant(attempt.get("deadline"));
        return new StartOutput(String.valueOf(attempt.id()), PlaySteps.uuid(attempt.get("publicationId")),
            attempt.get("status"), startedAt, deadline, ctx.opTime(),
            (int) java.time.Duration.between(startedAt, deadline).toSeconds(),
            PlaySteps.integer(attempt.get("currentIndex")), resumed, questions(snapshot, order),
            Answers.given(Answers.fromJson(attempt.get("answers"))), snapshot.kind());
    }

    /**
     * The questions as the taker sees them: every question of the attempt's snapshot in order, numbered by its
     * position (Q81, D-Q81-5: the version's number stays in the attempt, as the options' positions do, Q10b M1), with
     * its options in {@code order}, numbered by their position (never by the version's number, which follows the
     * author's order), without the options' correctness (design section 9: the correct answers appear only once the
     * attempt is finished).
     */
    static List<QuestionView> questions(QuizSnapshot snapshot, List<List<Integer>> order) {
        List<QuestionView> questions = new ArrayList<>();
        for (int i = 0; i < snapshot.questions().size(); i++) {
            QuizSnapshot.Question question = snapshot.questions().get(i);
            Map<Integer, QuizSnapshot.Option> byNo = new LinkedHashMap<>();
            question.options().forEach(option -> byNo.put(option.no(), option));
            List<Integer> shown = i < order.size() ? order.get(i) : List.copyOf(byNo.keySet());
            List<OptionView> options = new ArrayList<>();
            for (Integer no : shown) {
                QuizSnapshot.Option option = byNo.get(no);
                options.add(new OptionView(options.size() + 1, option.text(), option.image()));
            }
            questions.add(new QuestionView(i + 1, question.stem(), question.image(), question.points(),
                List.copyOf(options), question.choice()));
        }
        return List.copyOf(questions);
    }

    // ---- save ------------------------------------------------------------------------------------------------------

    public static ProcessDefinition<SaveInput, SaveOutput, ProcessContext> save() {
        return ProcessDefinition.define(SAVE, 1, SaveInput.class, SaveOutput.class, ProcessContext.class, pb -> pb
            .description("Saves the current question and answers of the caller's attempt in progress.")
            .permissions(QbPermissions.PLAY)
            .requiresVerifiedEmail()
            .steps(CallerChecks::refuseBanned)
            .contextFactory((start, input) -> {
                ProcessContext ctx = new ProcessContext(start);
                ctx.put(INPUT, input);
                ctx.put(ATTEMPT_KEY, input.attemptId());
                return ctx;
            })
            .outputMapper(ctx -> ctx.get(OUTPUT, SaveOutput.class))
            // No lock: the attempt's own version guards it (a save racing the submission is 409).
            .step("Load the attempt", LoadEntity.by(QbPlay.TAKER_ATTEMPTS, ATTEMPT_KEY, ATTEMPT))
            .compute("Save the answers", (metadata, ctx) -> save(ctx)));
    }

    static void save(ProcessContext ctx) {
        SaveInput input = ctx.get(INPUT, SaveInput.class);
        EntityInstance attempt = ctx.get(ATTEMPT, EntityInstance.class);
        if (!AttemptStatus.IN_PROGRESS.equals(attempt.get("status"))) {
            ctx.reject(new com.jabiz.entity.Violation("attemptId", PlayCodes.NOT_IN_PROGRESS, "The attempt is "
                + "finished", Map.of("status", String.valueOf((Object) attempt.get("status")))));
            return;
        }
        Instant deadline = PlaySteps.instant(attempt.get("deadline"));
        if (AttemptRules.pastDeadline(deadline, ctx.opTime())) {
            ctx.reject(new com.jabiz.entity.Violation("attemptId", PlayCodes.EXPIRED, "The time of the attempt is "
                + "over", Map.of("deadline", deadline.toString())));
            return;
        }
        List<List<Integer>> order = Answers.fromJson(attempt.get("optionOrder"));
        List<com.jabiz.entity.Violation> problems = Answers.check(order, input.answers(), input.currentIndex());
        if (!problems.isEmpty()) {
            problems.forEach(ctx::reject);
            return;
        }
        List<List<Integer>> answers = Answers.merge(Answers.fromJson(attempt.get("answers")), order.size(),
            input.answers());
        int currentIndex = input.currentIndex() != null ? input.currentIndex()
            : PlaySteps.integer(attempt.get("currentIndex"));
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("answers", Answers.toJson(answers));
        values.put("currentIndex", BigDecimal.valueOf(currentIndex));
        ctx.changes().update(QbPlay.ATTEMPT, attempt.id(), attempt.version(), values);
        ctx.put(OUTPUT, new SaveOutput(String.valueOf(attempt.id()), currentIndex, Answers.given(answers), deadline,
            ctx.opTime()));
    }

    private AttemptProcesses() {}
}
