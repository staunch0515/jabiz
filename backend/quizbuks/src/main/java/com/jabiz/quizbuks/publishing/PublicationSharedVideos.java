package com.jabiz.quizbuks.publishing;

import com.jabiz.entity.Violation;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.process.ProcessDefinitionBuilder;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.identity.CallerChecks;
import com.jabiz.quizbuks.sharedvideo.QbSharedVideos;
import com.jabiz.quizbuks.sharedvideo.SharedVideoCodes;
import com.jabiz.quizbuks.sharedvideo.SharedVideoRules;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.process.steps.QueryEntities;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

import static com.jabiz.quizbuks.publishing.PublicationEditing.INPUT;
import static com.jabiz.quizbuks.publishing.PublicationEditing.OUTPUT;
import static com.jabiz.quizbuks.publishing.PublicationEditing.PUBLICATION;

/**
 * A publication's shared video (F-S12, docs/quizbuks/plans/impl/F-S12.md): a draft picks a video of the catalog
 * (another sponsor's, approved), {@value #SET}; submitted, it is frozen with the policy and used until the publication
 * ends, whatever the provider does with the share afterwards. When the publication goes on (and at a switch of
 * version) its card shows the shared video ({@link #frozen}: the card's video, vertical or not, F-S10), counts it for
 * the foreign videos (F-S11) and freezes its provider (for blocking, F-S8, and statistics). A video to watch before
 * answering (Q75) stays the card's video: the shared one is then only counted as foreign.
 *
 * <p>When a shared video is taken down, the minute's job {@code qb.shared-video-ends} runs {@value #END} for each
 * publication on using it: it ends with the end reason {@value PublicationStatus#SHARED_VIDEO} (not a takedown of the
 * publication: the sponsor is not in breach), and the sponsor is mailed. The fees follow F-M8 (not charged here).
 */
public final class PublicationSharedVideos {

    public static final String SET = "QB_PUBLICATION_SHARED_VIDEO_SET";
    public static final String END = "QB_PUBLICATION_SHARED_VIDEO_END";
    /** The items of the job ending the publications whose shared video was taken down. */
    public static final String ENDS_TEMPLATE = "qb.system.shared-video-ends";

    static final String SHARED_VIDEO = "sharedVideo";

    /**
     * @param sharedVideoId the video picked from the catalog; none: no shared video
     * @param baseRevision  the revision the page edited; when given and no longer current, 422
     */
    public record SharedVideoInput(@NotNull UUID publicationId,
        @Size(max = QbSharedVideos.ID_LENGTH) String sharedVideoId, Long baseRevision) {}

    public record EndInput(@NotNull UUID publicationId) {}

    /** What the card takes of a shared video: its link, length, orientation and provider. */
    public record Frozen(String sharedVideoId, String url, Integer durationSec, boolean vertical, String providerId) {}

    private PublicationSharedVideos() {
    }

    // ---- set -------------------------------------------------------------------------------------------------------

    public static ProcessDefinition<SharedVideoInput, PublicationOutput, ProcessContext> set() {
        return ProcessDefinition.define(SET, 1, SharedVideoInput.class, PublicationOutput.class, ProcessContext.class,
            pb -> {
                pb.description("Picks a video other sponsors share for a draft publication, or none.")
                    .permissions(QbPermissions.PUBLICATION_WRITE)
                    .requiresVerifiedEmail()
                    .steps(CallerChecks::refuseBanned)
                    .actsOn(QbPublications.PUBLICATION, "publicationId", a -> a.whenField("status",
                        PublicationStatus.DRAFT))
                    .contextFactory((start, input) -> PublicationEditing.start(start, input, input.publicationId()))
                    .outputMapper(ctx -> ctx.get(OUTPUT, PublicationOutput.class));
                PublicationEditing.lockAndLoad(pb);
                pb.step("Load the shared video", QueryEntities.<ProcessContext>of(QbSharedVideos.DEFAULT_DATASET,
                    ctx -> byId(given(ctx)), SHARED_VIDEO));
                pb.compute("Set the shared video", (metadata, ctx) -> set(ctx));
            });
    }

    private static String given(ProcessContext ctx) {
        String id = ctx.get(INPUT, SharedVideoInput.class).sharedVideoId();
        return id == null || id.isBlank() ? null : id.strip();
    }

    static void set(ProcessContext ctx) {
        SharedVideoInput input = ctx.get(INPUT, SharedVideoInput.class);
        EntityInstance publication = ctx.get(PUBLICATION, EntityInstance.class);
        if (!PublicationEditing.draft(ctx, publication)
            || !PublicationEditing.current(ctx, publication, input.baseRevision())) {
            return;
        }
        String wanted = given(ctx);
        if (Objects.equals(wanted, publication.get("sharedVideoId"))) {
            PublicationEditing.unchanged(ctx, publication);
            return;
        }
        if (wanted != null) {
            EntityInstance video = PublicationEditing.first(ctx, SHARED_VIDEO);
            if (video == null || !SharedVideoRules.available(video.get("status"))) {
                ctx.reject(unavailable());
                return;
            }
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("sharedVideoId", wanted);
        PublicationEditing.write(ctx, publication, values);
    }

    private static Violation unavailable() {
        return new Violation("sharedVideoId", SharedVideoCodes.UNAVAILABLE, "The shared video is not in the catalog "
            + "(withdrawn, taken down or not approved)", Map.of());
    }

    // ---- what the other processes read -----------------------------------------------------------------------------

    /**
     * Loads the shared video the publication {@code publication} names (none: no statement). Read where a publication
     * is submitted, goes on, switches its version and ends because of it.
     */
    static <I, O> void load(ProcessDefinitionBuilder<I, O, ProcessContext> pb,
        Function<ProcessContext, EntityInstance> publication) {
        pb.step("Load the publication's shared video", QueryEntities.<ProcessContext>of(
            QbSharedVideos.DEFAULT_DATASET, ctx -> {
                EntityInstance found = publication.apply(ctx);
                return byId(found == null ? null : found.get("sharedVideoId"));
            }, SHARED_VIDEO));
    }

    /** A submission's problem: the video picked is no longer in the catalog (withdrawn, taken down). */
    static List<Violation> submitProblems(ProcessContext ctx, EntityInstance publication) {
        if (publication.get("sharedVideoId") == null) {
            return List.of();
        }
        EntityInstance video = video(ctx, publication);
        return video != null && SharedVideoRules.available(video.get("status")) ? List.of() : List.of(unavailable());
    }

    /** The shared video {@link #load} read for the publication; none when it has none. */
    static EntityInstance video(ProcessContext ctx, EntityInstance publication) {
        Object id = publication == null ? null : publication.get("sharedVideoId");
        if (id == null || !ctx.contains(SHARED_VIDEO)) {
            return null;
        }
        EntityInstance video = PublicationEditing.first(ctx, SHARED_VIDEO);
        return video != null && String.valueOf(video.id()).equals(String.valueOf(id)) ? video : null;
    }

    /**
     * What the card freezes of the publication's shared video; none when it has none. Once submitted, whatever the
     * share's state: the provider's withdrawal does not touch a publication submitted with it, and one taken down is
     * ended by the job.
     */
    static Frozen frozen(ProcessContext ctx, EntityInstance publication) {
        EntityInstance video = video(ctx, publication);
        if (video == null) {
            return null;
        }
        Object duration = video.get("durationSec");
        return new Frozen(String.valueOf(video.id()), video.get("url"),
            duration == null ? null : ((Number) duration).intValue(), Boolean.TRUE.equals(video.get("vertical")),
            video.get("providerId"));
    }

    /** The card's shared video and its provider (both none without one). */
    static void writeTo(Map<String, Object> card, Frozen shared) {
        card.put("sharedVideoId", shared == null ? null : shared.sharedVideoId());
        card.put("videoProviderId", shared == null ? null : shared.providerId());
    }

    // ---- end -------------------------------------------------------------------------------------------------------

    /**
     * {@value #END}: one item of the job, as the system: ends a publication on whose shared video was taken down,
     * under its locks (as every move of a publication on). One that ended or went back to its draft meanwhile, or whose
     * video is not taken down, is left as it is.
     */
    public static ProcessDefinition<EndInput, PublicationOutput, ProcessContext> end() {
        return ProcessDefinition.define(END, 1, EndInput.class, PublicationOutput.class, ProcessContext.class,
            pb -> pb
                .description("Ends a publication whose shared video was taken down.")
                .permissions(QbPermissions.PUBLICATION_SYSTEM)
                .internal()
                .contextFactory((start, input) -> PublicationEditing.start(start, input, input.publicationId()))
                .outputMapper(ctx -> ctx.contains(OUTPUT) ? ctx.get(OUTPUT, PublicationOutput.class)
                    : new PublicationOutput(ctx.get(PublicationEditing.INPUT, EndInput.class).publicationId(), null, 0,
                        null, null))
                .steps(PublicationEditing::lockStatesAndLoadAny)
                .steps(b -> load(b, PublicationEditing::publication))
                .compute("End", (metadata, ctx) -> ended(ctx))
                .steps(PublicationNotices::announce));
    }

    static void ended(ProcessContext ctx) {
        EntityInstance publication = PublicationEditing.publication(ctx);
        EntityInstance video = video(ctx, publication);
        if (video == null || !QbSharedVideos.TAKEN_DOWN.equals(video.get("status"))
            || !PublicationAdmin.ENDABLE.contains(String.valueOf((Object) publication.get("status")))) {
            PublicationEditing.unchanged(ctx, publication);
            return;
        }
        // F-M8: what the publication was charged for the shared video (exposure) is settled by its end; this ends it
        // only, as any end, and charges nothing here.
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("status", PublicationStatus.ENDED);
        values.put("endedAt", ctx.opTime());
        values.put("endReason", PublicationStatus.SHARED_VIDEO);
        PublicationEditing.write(ctx, publication, values);
    }

    static EntityQuery byId(Object id) {
        List<Object> ids = new ArrayList<>();
        if (id != null && !id.toString().isBlank()) {
            ids.add(id.toString().strip());
        }
        return EntityQuery.builder().where(new QueryPredicate.In("sharedVideoId", ids)).limit(1).build();
    }
}
