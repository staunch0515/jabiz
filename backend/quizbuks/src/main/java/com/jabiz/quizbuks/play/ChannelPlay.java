package com.jabiz.quizbuks.play;

import com.jabiz.entity.Violation;
import com.jabiz.i18n.MessageCatalog;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.country.Regions;
import com.jabiz.quizbuks.channel.Channel;
import com.jabiz.quizbuks.publishing.PublisherKind;
import com.jabiz.quizbuks.publishing.QbPublications;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.EntityNotFoundException;
import com.jabiz.runtime.process.steps.HoldLock;
import com.jabiz.runtime.process.steps.QueryEntities;
import com.jabiz.quizbuks.publishing.PublicationMoves;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.jabiz.quizbuks.play.PlaySteps.INPUT;
import static com.jabiz.quizbuks.play.PlaySteps.OUTPUT;
import static com.jabiz.quizbuks.play.PlaySteps.first;
import static com.jabiz.quizbuks.play.PlaySteps.ids;

/**
 * Answering a channel's publication (docs/quizbuks/shopify/S2-storefront.md section 1, S2-1 and S2-4): the taker is
 * not the caller (a storefront's requests all come as the shop proxy's caller) but a key the channel's module gives,
 * one per person it tells apart ({@code qbs:…}, never a user's id). Its module calls these by name as sub-processes;
 * they are {@code subProcessOnly()}.
 * <ul>
 *   <li>{@value #START}: as {@code QB_ATTEMPT_START} for that taker: one attempt each ({@code qb_attempt_once}),
 *       continued while in time; no regions, invitations, wallet or ban (a channel's publication is open to every
 *       visitor of the channel, and pays nothing);</li>
 *   <li>{@value #SUBMIT}: as {@code QB_ATTEMPT_SUBMIT} for that taker, and not sooner than
 *       {@link PlayLimits#CHANNEL_MIN_ANSWER} after the start; the reward method {@code CHANNEL} settles nothing, so
 *       the answer says only whether it passed, for the channel to reward.</li>
 * </ul>
 * Both refuse a publication or attempt that is not the named publisher's, or an attempt that is not the taker's, as
 * not found. Neither queues: the channel's root process declares any admission (platform decision D45).
 */
public final class ChannelPlay {

    public static final String START = Channel.START;
    public static final String SUBMIT = Channel.SUBMIT;

    static final String CHANNEL_INPUT = "channel.input";

    // ---- start -----------------------------------------------------------------------------------------------------

    public static ProcessDefinition<Channel.StartInput, Channel.StartOutput, ProcessContext> start(
        OptionShuffle shuffle) {
        return ProcessDefinition.define(START, 1, Channel.StartInput.class, Channel.StartOutput.class,
            ProcessContext.class, pb -> pb
                .description("Starts a channel's taker's attempt of a channel's publication, or continues it.")
                .permissions(QbPermissions.PLAY_SYSTEM)
                .subProcessOnly()
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put(INPUT, input);
                    ctx.put(CHANNEL_INPUT, input);
                    ctx.put(AttemptProcesses.PUB_KEY, input.publicationId());
                    return ctx;
                })
                .outputMapper(ctx -> started(ctx.get(OUTPUT, AttemptProcesses.StartOutput.class)))
                .step("Load the taker's attempt, the card and the publication", QueryEntities.<ProcessContext>all(
                    QueryEntities.query(QbPlay.defaultDataset(QbPlay.ATTEMPT),
                        ctx -> takers(ctx.get(CHANNEL_INPUT, Channel.StartInput.class).takerId(),
                            ctx.get(AttemptProcesses.PUB_KEY)), AttemptProcesses.MINE),
                    PlaySteps.card(ctx -> ctx.get(AttemptProcesses.PUB_KEY), AttemptProcesses.CARD_BEFORE),
                    QueryEntities.query(QbPublications.defaultDataset(QbPublications.PUBLICATION),
                        ctx -> EntityQuery.builder().where(new QueryPredicate.In("publicationId",
                            ids(ctx.get(AttemptProcesses.PUB_KEY)))).limit(1).build(), AttemptProcesses.PUBLICATION)))
                .steps(b -> PlaySteps.loadVersion(b, ctx -> {
                    EntityInstance card = first(ctx, AttemptProcesses.CARD_BEFORE);
                    return card == null ? null : card.get("versionId");
                }))
                .compute("Check the publisher", (metadata, ctx) -> {
                    Channel.StartInput input = ctx.get(CHANNEL_INPUT, Channel.StartInput.class);
                    requirePublisher(ctx, input.publisher(), first(ctx, AttemptProcesses.CARD_BEFORE),
                        input.publicationId());
                })
                .compute("Continue an attempt in progress", (metadata, ctx) -> AttemptProcesses.resume(ctx))
                .step("Lock the publication's starts", HoldLock.<ProcessContext>exclusive(
                    ctx -> AttemptProcesses.starting(ctx)
                        ? PublicationMoves.startLockName(ctx.get(AttemptProcesses.PUB_KEY)) : null))
                .step("Load the taker's attempt, the card and the counters again",
                    QueryEntities.<ProcessContext>all(
                        QueryEntities.query(QbPlay.defaultDataset(QbPlay.ATTEMPT),
                            ctx -> takers(ctx.get(CHANNEL_INPUT, Channel.StartInput.class).takerId(),
                                AttemptProcesses.starting(ctx) ? ctx.get(AttemptProcesses.PUB_KEY) : null),
                            AttemptProcesses.MINE_AGAIN),
                        PlaySteps.card(ctx -> AttemptProcesses.starting(ctx) ? ctx.get(AttemptProcesses.PUB_KEY)
                            : null, AttemptProcesses.CARD),
                        PlaySteps.startCounter(ctx -> AttemptProcesses.starting(ctx)
                            ? ctx.get(AttemptProcesses.PUB_KEY) : null, AttemptProcesses.START_COUNTER),
                        PlaySteps.counter(ctx -> AttemptProcesses.starting(ctx) ? ctx.get(AttemptProcesses.PUB_KEY)
                            : null, AttemptProcesses.COUNTER)))
                // Every region: a channel's publication is open to all of its visitors (its card says GLOBAL).
                .compute("Start the attempt", (metadata, ctx) -> AttemptProcesses.start(ctx, shuffle,
                    ctx.get(CHANNEL_INPUT, Channel.StartInput.class).takerId(), Regions.GLOBAL)));
    }

    /** The taker's attempts of a publication (none for no publication: no statement). */
    static EntityQuery takers(String takerId, Object publicationId) {
        return EntityQuery.builder().where(new QueryPredicate.And(List.of(
            new QueryPredicate.Eq("userId", takerId),
            new QueryPredicate.In("publicationId", ids(publicationId))))).limit(1).build();
    }

    /** 404 unless the card is the named channel publisher's: another publisher's is as good as none. */
    static void requirePublisher(ProcessContext ctx, Channel.Publisher publisher, EntityInstance card,
        Object publicationId) {
        if (PublisherKind.sponsor(publisher.kind()) || card == null
            || !publisher.kind().equals(card.get("publisherKind")) || !publisher.id().equals(card.get("publisherId"))) {
            throw new EntityNotFoundException(QbPublications.PUBLICATION + " " + publicationId + " not found");
        }
    }

    // ---- submit ----------------------------------------------------------------------------------------------------

    public static ProcessDefinition<Channel.SubmitInput, Channel.SubmitOutput, ProcessContext> submit(
        MessageCatalog messages) {
        return ProcessDefinition.define(SUBMIT, 1, Channel.SubmitInput.class, Channel.SubmitOutput.class,
            ProcessContext.class, pb -> {
                pb.description("Submits a channel's taker's attempt: scores it; the channel rewards a pass.")
                    .permissions(QbPermissions.PLAY_SYSTEM)
                    .subProcessOnly()
                    .contextFactory((start, input) -> {
                        ProcessContext ctx = new ProcessContext(start);
                        // The shared steps read the App's input: the same attempt, answers and publication.
                        ctx.put(INPUT, new AttemptFinishing.SubmitInput(input.attemptId(), input.currentIndex(),
                            input.answers() == null ? null : input.answers().stream()
                                .map(answer -> new Answers.Given(answer.questionNo(), answer.selected())).toList(),
                            input.publicationId()));
                        ctx.put(CHANNEL_INPUT, input);
                        ctx.put(AttemptFinishing.ATTEMPT_KEY, input.attemptId());
                        ctx.put(AttemptFinishing.GIVEN_PUBLICATION, input.publicationId());
                        return ctx;
                    })
                    .outputMapper(ctx -> {
                        AttemptFinishing.SubmitOutput output = ctx.get(OUTPUT, AttemptFinishing.SubmitOutput.class);
                        return new Channel.SubmitOutput(output.attemptId(), output.status(), output.score(),
                            output.fullScore(), output.passScore(), output.passed());
                    })
                    .step("Load the attempt", QueryEntities.<ProcessContext>of(QbPlay.defaultDataset(QbPlay.ATTEMPT),
                        ctx -> AttemptFinishing.byId(ctx.get(AttemptFinishing.ATTEMPT_KEY)), AttemptFinishing.FOUND))
                    .compute("Check the attempt", (metadata, ctx) -> check(ctx));
                AttemptFinishing.steps(pb, false, messages);
            });
    }

    static void check(ProcessContext ctx) {
        Channel.SubmitInput input = ctx.get(CHANNEL_INPUT, Channel.SubmitInput.class);
        EntityInstance attempt = first(ctx, AttemptFinishing.FOUND);
        if (attempt == null || !input.takerId().equals(attempt.get("userId"))
            || PublisherKind.sponsor(input.publisher().kind())
            || !input.publisher().kind().equals(attempt.get("publisherKind"))
            || !input.publisher().id().equals(attempt.get("publisherId"))) {
            throw new EntityNotFoundException(QbPlay.ATTEMPT + " " + input.attemptId() + " not found");
        }
        if (AttemptFinishing.mismatched(ctx, attempt)) {
            return;
        }
        if (AttemptStatus.finished(attempt.get("status"))) {
            ctx.reject(AttemptFinishing.finished(attempt));
            return;
        }
        Instant startedAt = PlaySteps.instant(attempt.get("startedAt"));
        if (Duration.between(startedAt, ctx.opTime()).compareTo(PlayLimits.CHANNEL_MIN_ANSWER) < 0) {
            ctx.reject(new Violation("attemptId", PlayCodes.TOO_FAST, "Submitted sooner than anybody can answer",
                Map.of("seconds", PlayLimits.CHANNEL_MIN_ANSWER.toSeconds())));
            return;
        }
        ctx.put(AttemptFinishing.ACTIVE, true);
    }

    /** The attempt as the channel shows it: no images (a channel's quiz has none), never the correct options. */
    static Channel.StartOutput started(AttemptProcesses.StartOutput output) {
        return new Channel.StartOutput(output.attemptId(), output.publicationId(), output.startedAt(),
            output.deadline(), output.serverTime(), output.timeLimitSec(), output.currentIndex(), output.resumed(),
            output.questions().stream().map(question -> new Channel.Question(question.no(), question.stem(),
                question.points(), question.options().stream().map(option -> new Channel.Option(option.no(),
                    option.text())).toList())).toList(),
            output.answers().stream().map(answer -> new Channel.Answer(answer.questionNo(), answer.selected()))
                .toList());
    }

    private ChannelPlay() {}
}
