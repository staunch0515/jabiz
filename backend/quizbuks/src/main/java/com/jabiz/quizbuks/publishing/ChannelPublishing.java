package com.jabiz.quizbuks.publishing;

import com.jabiz.entity.Violation;
import com.jabiz.process.ProcessContext;
import com.jabiz.quizbuks.channel.Channel;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.content.ContentLimits;
import com.jabiz.quizbuks.content.MaterialKind;
import com.jabiz.quizbuks.content.QbContent;
import com.jabiz.quizbuks.content.QuizCompleteness;
import com.jabiz.quizbuks.content.QuizSnapshot;
import com.jabiz.quizbuks.content.QuizSnapshots;
import com.jabiz.quizbuks.content.VersionLabels;
import com.jabiz.quizbuks.content.VideoEmbeds;
import com.jabiz.quizbuks.country.Regions;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.EntityNotFoundException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.jabiz.quizbuks.publishing.PublicationEditing.OUTPUT;
import static com.jabiz.quizbuks.publishing.PublicationEditing.TIERS;
import static com.jabiz.quizbuks.publishing.PublicationEditing.VERSIONS;

/**
 * Publishing for a merchant system of its own (docs/quizbuks/shopify/S2-storefront.md section 1, S2-1 – S2-3): a
 * publisher that is not a sponsor ({@link PublisherKind#SHOPIFY_SHOP}) publishes a quiz of its own on its own channel
 * (a shop's storefront). Its module calls these processes by name as sub-processes, naming the publisher; they are
 * {@code subProcessOnly()}, so no caller reaches them otherwise.
 * <ul>
 *   <li>{@value #PUBLISH}: the quiz, its one version and the publication in one go, live at once: no review (the
 *       publisher shows its own content on its own channel), no wallet (reward method {@link RewardMethod#CHANNEL}:
 *       the engine scores, the channel rewards), one tier of no reward, open to every visitor until
 *       {@link PublishingLimits#MAX_RUNNING} from now;</li>
 *   <li>{@value #MOVE}: pauses, resumes or ends such a publication; nothing for one already so, or ended.</li>
 * </ul>
 * A sponsor's processes never see these publications (their datasets are fixed to {@link PublisherKind#SPONSOR}), and
 * these never touch a sponsor's.
 */
public final class ChannelPublishing {

    public static final String PUBLISH = Channel.PUBLISH;
    public static final String MOVE = Channel.MOVE;

    static final String SNAPSHOT = "channel.snapshot";

    public static ProcessDefinition<Channel.PublishInput, Channel.PublishOutput, ProcessContext> publish() {
        return ProcessDefinition.define(PUBLISH, 1, Channel.PublishInput.class, Channel.PublishOutput.class, ProcessContext.class,
            pb -> pb
                .description("A channel's publisher publishes a quiz of its own on its channel, live at once.")
                .permissions(QbPermissions.PUBLICATION_SYSTEM)
                .subProcessOnly()
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put(PublicationEditing.INPUT, input);
                    return ctx;
                })
                .outputMapper(ctx -> ctx.get(OUTPUT, Channel.PublishOutput.class))
                .compute("Check the quiz", (metadata, ctx) -> check(ctx))
                .compute("Publish", (metadata, ctx) -> publish(ctx)));
    }

    static void check(ProcessContext ctx) {
        Channel.PublishInput input = ctx.get(PublicationEditing.INPUT, Channel.PublishInput.class);
        if (!channel(ctx, input.publisher())) {
            return;
        }
        QuizSnapshot snapshot = snapshot(input);
        List<Violation> problems = new ArrayList<>(QuizCompleteness.check(snapshot));
        if (input.video() != null && !input.video().isBlank() && VideoEmbeds.player(input.video()).isEmpty()) {
            problems.add(new Violation("video", PublishingCodes.CHANNEL_VIDEO, "Only a YouTube or Vimeo video can be "
                + "watched", Map.of()));
        }
        if (input.passScore() > snapshot.fullScore()) {
            problems.add(new Violation("passScore", PublishingCodes.SCORE_RANGE, "The pass score is above the "
                + "quiz's full score", Map.of("fullScore", snapshot.fullScore())));
        }
        if (QuizSnapshots.size(QuizSnapshots.toJson(snapshot)) > ContentLimits.MAX_SNAPSHOT_BYTES) {
            problems.add(new Violation(null, PublishingCodes.CHANNEL_TOO_LARGE, "The quiz is too large", Map.of()));
        }
        if (!problems.isEmpty()) {
            problems.forEach(ctx::reject);
            return;
        }
        ctx.put(SNAPSHOT, snapshot);
    }

    /** Whether the publisher is a channel's; a sponsor publishes through their own processes only. */
    static boolean channel(ProcessContext ctx, Channel.Publisher publisher) {
        if (PublisherKind.sponsor(publisher.kind()) || !PublisherKind.ALL.contains(publisher.kind())) {
            ctx.reject(new Violation("publisher.kind", PublishingCodes.CHANNEL_PUBLISHER, "Not a channel's publisher",
                Map.of("kind", String.valueOf(publisher.kind()))));
            return false;
        }
        return true;
    }

    /** The quiz as its one version keeps it: every question worth a point, the video to watch first if any. */
    static QuizSnapshot snapshot(Channel.PublishInput input) {
        List<QuizSnapshot.Question> questions = new ArrayList<>();
        for (Channel.QuestionInput question : input.questions()) {
            List<QuizSnapshot.Option> options = new ArrayList<>();
            for (Channel.OptionInput option : question.options()) {
                options.add(new QuizSnapshot.Option(options.size() + 1, option.text().strip(), null,
                    option.correct()));
            }
            questions.add(new QuizSnapshot.Question(questions.size() + 1, question.stem().strip(), null, 1, options,
                null));
        }
        List<QuizSnapshot.Material> materials = input.video() == null || input.video().isBlank() ? List.of()
            : List.of(new QuizSnapshot.Material(1, MaterialKind.VIDEO_LINK.name(), input.name().strip(), null, null,
                input.video().strip(), null, null, List.of(), true));
        return new QuizSnapshot(input.name().strip(), null, null, input.timeLimitSec(), false, materials, questions);
    }

    static void publish(ProcessContext ctx) {
        if (ctx.hasViolations()) {
            return;
        }
        Channel.PublishInput input = ctx.get(PublicationEditing.INPUT, Channel.PublishInput.class);
        QuizSnapshot snapshot = ctx.get(SNAPSHOT, QuizSnapshot.class);
        String owner = owner(input.publisher());
        String json = QuizSnapshots.toJson(snapshot);
        BigDecimal one = BigDecimal.ONE;

        Map<String, Object> quiz = new LinkedHashMap<>();
        quiz.put("ownerId", owner);
        quiz.put("kind", QbContent.KIND_QUIZ);
        quiz.put("title", snapshot.title());
        quiz.put("timeLimitSec", BigDecimal.valueOf(input.timeLimitSec()));
        quiz.put("status", QbContent.VERSIONED);
        quiz.put("removed", false);
        quiz.put("aiGenerated", false);
        quiz.put("latestVersionNo", one);
        quiz.put("versionLabel", VersionLabels.label(1));
        quiz.put("revision", one);
        quiz.put("editedAt", ctx.opTime());
        quiz.put("changedSinceVersion", false);
        quiz.put("questionCount", BigDecimal.valueOf(snapshot.questions().size()));
        quiz.put("materialCount", BigDecimal.valueOf(snapshot.materials().size()));
        // The keys as the shared steps read them from the database (UUIDs): the card and the counters are keyed by the
        // publication's.
        Object quizId = PublicationEditing.uuid(ctx.changes().insert(QbContent.QUIZ, quiz));

        Map<String, Object> version = new LinkedHashMap<>();
        version.put("quizId", quizId);
        version.put("ownerId", owner);
        version.put("versionNumber", one);
        version.put("label", VersionLabels.label(1));
        version.put("title", snapshot.title());
        version.put("timeLimitSec", BigDecimal.valueOf(input.timeLimitSec()));
        version.put("questionCount", BigDecimal.valueOf(snapshot.questions().size()));
        version.put("materialCount", BigDecimal.valueOf(snapshot.materials().size()));
        version.put("fullScore", BigDecimal.valueOf(snapshot.fullScore()));
        version.put("content", json);
        version.put("contentHash", QuizSnapshots.hash(snapshot));
        version.put("versionedAt", ctx.opTime());
        Object versionId = PublicationEditing.uuid(ctx.changes().insert(QbContent.VERSION, version));

        Map<String, Object> publication = new LinkedHashMap<>();
        publication.put("publisherKind", input.publisher().kind());
        publication.put("publisherId", input.publisher().id());
        publication.put("quizId", quizId);
        publication.put("versionId", versionId);
        publication.put("versionLabel", VersionLabels.label(1));
        publication.put("name", input.name().strip());
        publication.put("regions", Regions.GLOBAL);
        publication.put("audience", Audience.PUBLIC);
        publication.put("startAt", ctx.opTime());
        publication.put("endAt", ctx.opTime().plus(PublishingLimits.MAX_RUNNING));
        publication.put("timeLimitSec", BigDecimal.valueOf(input.timeLimitSec()));
        publication.put("passScore", BigDecimal.valueOf(input.passScore()));
        publication.put("rewardMethod", RewardMethod.CHANNEL);
        publication.put("fullScore", BigDecimal.valueOf(snapshot.fullScore()));
        publication.put("maxReward", BigDecimal.ZERO);
        publication.put("maxSpend", BigDecimal.ZERO);
        publication.put("tierCount", one);
        publication.put("status", PublicationStatus.LIVE);
        publication.put("submittedAt", ctx.opTime());
        publication.put("approvedAt", ctx.opTime());
        publication.put("revision", one);
        publication.put("editedAt", ctx.opTime());
        Object publicationId = PublicationEditing.uuid(ctx.changes().insert(QbPublications.PUBLICATION, publication));

        Map<String, Object> tier = new LinkedHashMap<>();
        tier.put("publicationId", publicationId);
        tier.put("publisherKind", input.publisher().kind());
        tier.put("publisherId", input.publisher().id());
        tier.put("seq", one);
        tier.put("fromScore", BigDecimal.ZERO);
        tier.put("toScore", BigDecimal.valueOf(snapshot.fullScore()));
        tier.put("reward", BigDecimal.ZERO);
        Object tierId = PublicationEditing.uuid(ctx.changes().insert(QbPublications.TIER, tier));

        // The card, the counters and the schedule as a sponsor's publication has them once it went on: the attempts,
        // the expiry job and the minute's tick read them alike.
        EntityInstance publicationRow = row(QbPublications.PUBLICATION, publicationId, publication, "publicationId");
        EntityInstance versionRow = row(QbContent.VERSION, versionId, version, "versionId");
        EntityInstance tierRow = row(QbPublications.TIER, tierId, tier, "tierId");
        ctx.put(VERSIONS, List.of(versionRow));
        ctx.put(TIERS, List.of(tierRow));
        PublicationEditing.goingOn(ctx, publicationRow, null);
        PublicationEditing.schedule(ctx, publicationRow, PublicationStatus.LIVE);
        ctx.put(OUTPUT, new Channel.PublishOutput(PublicationEditing.uuid(publicationId), PublicationEditing.uuid(versionId),
            PublicationStatus.LIVE, snapshot.fullScore(), snapshot.questions().size(),
            input.video() == null ? null : VideoEmbeds.player(input.video().strip()).orElse(null)));
    }

    /** A row just inserted, as the shared steps read one (the card is made from it in this transaction). */
    private static EntityInstance row(String entity, Object id, Map<String, Object> values, String key) {
        Map<String, Object> attributes = new LinkedHashMap<>(values);
        attributes.put(key, id);
        return new EntityInstance(id, entity, 1L, null, attributes);
    }

    /** The owner of a channel's content: its kind and key, never a user's id. */
    public static String owner(Channel.Publisher publisher) {
        return publisher.kind().toLowerCase(java.util.Locale.ROOT) + ":" + publisher.id();
    }

    public static ProcessDefinition<Channel.MoveInput, Channel.MoveOutput, ProcessContext> move() {
        return ProcessDefinition.define(MOVE, 1, Channel.MoveInput.class, Channel.MoveOutput.class,
            ProcessContext.class,
            pb -> pb
                .description("A channel's publisher pauses, resumes or ends a publication of its own.")
                .permissions(QbPermissions.PUBLICATION_SYSTEM)
                .subProcessOnly()
                .contextFactory((start, input) -> PublicationEditing.start(start, input, input.publicationId()))
                .outputMapper(ctx -> {
                    PublicationOutput output = ctx.get(OUTPUT, PublicationOutput.class);
                    return new Channel.MoveOutput(output.publicationId(), output.status());
                })
                // Both locks (Q10a4): no start gets in once the change commits.
                .steps(PublicationEditing::lockStatesAndLoadAny)
                .compute("Move", (metadata, ctx) -> move(ctx)));
    }

    static void move(ProcessContext ctx) {
        Channel.MoveInput input = ctx.get(PublicationEditing.INPUT, Channel.MoveInput.class);
        EntityInstance publication = PublicationEditing.publication(ctx);
        if (!channel(ctx, input.publisher())) {
            return;
        }
        if (!input.publisher().kind().equals(publication.get("publisherKind"))
            || !input.publisher().id().equals(publication.get("publisherId"))) {
            // Another publisher's is as good as none.
            throw new EntityNotFoundException(QbPublications.PUBLICATION + " " + input.publicationId() + " not found");
        }
        // An ended publication (by its time, by an administrator) or one already where it is asked to be: nothing to do,
        // so that the channel's uninstall or erasure, which only wants it stopped, never fails on it.
        String status = publication.get("status");
        if (PublicationStatus.ENDED.equals(status) || input.to().equals(status)) {
            PublicationEditing.unchanged(ctx, publication);
            return;
        }
        Map<String, Object> values = new LinkedHashMap<>();
        switch (input.to()) {
            case PublicationStatus.PAUSED -> {
                if (!PublicationEditing.in(ctx, publication, PublicationStatus.PAUSED, PublicationStatus.LIVE,
                    PublicationStatus.SCHEDULED)) {
                    return;
                }
                values.put("status", PublicationStatus.PAUSED);
                values.put("pausedAt", ctx.opTime());
                values.put("pausedBy", PublicationStatus.BY_SPONSOR);
            }
            case PublicationStatus.LIVE -> {
                PublicationLifecycle.resumed(ctx, publication, null);
                return;
            }
            default -> {
                if (!PublicationEditing.in(ctx, publication, PublicationStatus.ENDED,
                    PublicationStatus.RUNNING.toArray(String[]::new))) {
                    return;
                }
                values.put("status", PublicationStatus.ENDED);
                values.put("endedAt", ctx.opTime());
                values.put("endReason", PublicationStatus.PUBLISHER);
            }
        }
        PublicationEditing.write(ctx, publication, values);
    }

    private ChannelPublishing() {}
}
