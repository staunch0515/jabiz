package com.jabiz.quizbuks.publishing;

import com.jabiz.entity.Violation;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.security.MfaRequirement;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static com.jabiz.quizbuks.publishing.PublicationEditing.OUTPUT;
import static com.jabiz.quizbuks.publishing.PublicationStatus.ENDED;
import static com.jabiz.quizbuks.publishing.PublicationStatus.FUNDS_SHORT;
import static com.jabiz.quizbuks.publishing.PublicationStatus.LIVE;
import static com.jabiz.quizbuks.publishing.PublicationStatus.PAUSED;
import static com.jabiz.quizbuks.publishing.PublicationStatus.SCHEDULED;

/**
 * An administrator takes a publication down (Q10b, review M2; docs/quizbuks/plans/Q10b-security-fixes.md):
 * <ul>
 *   <li>{@code QB_PUBLICATION_ADMIN_PAUSE}: a scheduled, live or underfunded publication stops taking attempts, and
 *       one its sponsor paused stays paused: either way only an administrator resumes it. Banning a sponsor runs it
 *       for each of their running publications, in the ban's transaction;</li>
 *   <li>{@code QB_PUBLICATION_ADMIN_RESUME}: resumes a publication an administrator paused, to where its times say;</li>
 *   <li>{@code QB_PUBLICATION_ADMIN_END}: ends a running publication for good (end reason {@code ADMIN}).</li>
 * </ul>
 * Each needs {@code qb.admin.content.write} and a recent second factor (always, whatever
 * {@code jabiz.security.mfa.administration} says), and a reason, which the sponsor reads with the publication. The
 * attempts in progress complete as they would after the end time. Locks as every process that changes the state of a
 * publication that went on: {@code qb.pub:<p>} then {@code qb.pub-start:<p>}, before the reads.
 */
public final class PublicationAdmin {

    public static final String PAUSE = "QB_PUBLICATION_ADMIN_PAUSE";
    public static final String RESUME = "QB_PUBLICATION_ADMIN_RESUME";
    public static final String END = "QB_PUBLICATION_ADMIN_END";

    /** @param reason why, as the sponsor will read it */
    public record AdminInput(@NotNull UUID publicationId,
        @NotBlank @Size(max = PublishingLimits.REJECT_REASON_LENGTH) String reason) {}

    private PublicationAdmin() {}

    private static ProcessDefinition<AdminInput, PublicationOutput, ProcessContext> define(String name,
        String description, String[] from, java.util.function.BiConsumer<ProcessContext, AdminInput> compute) {
        return ProcessDefinition.define(name, 1, AdminInput.class, PublicationOutput.class, ProcessContext.class,
            pb -> pb
                .description(description)
                .permissions(QbPermissions.ADMIN_CONTENT_WRITE)
                .requiresMfa(MfaRequirement.ALWAYS)
                .requiresVerifiedEmail()
                .actsOn(QbPublications.PUBLICATION, "publicationId", a -> a.whenField("status", from))
                .contextFactory((start, input) -> PublicationEditing.start(start, input, input.publicationId()))
                .outputMapper(ctx -> ctx.get(OUTPUT, PublicationOutput.class))
                .steps(PublicationEditing::lockStatesAndLoadAny)
                .compute("Act", (metadata, ctx) -> compute.accept(ctx,
                    ctx.get(PublicationEditing.INPUT, AdminInput.class))));
    }

    public static ProcessDefinition<AdminInput, PublicationOutput, ProcessContext> pause() {
        return define(PAUSE, "Pauses a publication as an administrator: only an administrator can resume it.",
            new String[] {SCHEDULED, LIVE, FUNDS_SHORT, PAUSED}, PublicationAdmin::pause);
    }

    static void pause(ProcessContext ctx, AdminInput input) {
        EntityInstance publication = PublicationEditing.publication(ctx);
        String status = publication.get("status");
        Map<String, Object> values = new LinkedHashMap<>();
        if (PAUSED.equals(status)) {
            if (PublicationStatus.BY_ADMIN.equals(publication.get("pausedBy"))) {
                // Paused by an administrator already (a second ban, say): nothing to do.
                PublicationEditing.unchanged(ctx, publication);
                return;
            }
            // The sponsor paused it: it stays paused, now as the administrator's.
        } else if (PublicationEditing.in(ctx, publication, PAUSED, SCHEDULED, LIVE, FUNDS_SHORT)) {
            values.put("status", PAUSED);
            values.put("pausedAt", ctx.opTime());
        } else {
            return;
        }
        values.put("pausedBy", PublicationStatus.BY_ADMIN);
        values.put("adminReason", input.reason());
        PublicationEditing.write(ctx, publication, values);
    }

    public static ProcessDefinition<AdminInput, PublicationOutput, ProcessContext> resume() {
        return define(RESUME, "Resumes a publication an administrator paused: scheduled before its start, live "
            + "within its time, ended after its end.", new String[] {PAUSED}, PublicationAdmin::resume);
    }

    static void resume(ProcessContext ctx, AdminInput input) {
        EntityInstance publication = PublicationEditing.publication(ctx);
        if (PAUSED.equals(publication.get("status"))
            && !PublicationStatus.BY_ADMIN.equals(publication.get("pausedBy"))) {
            ctx.reject(new Violation("publicationId", PublishingCodes.NOT_ADMIN_PAUSED, "Its sponsor paused this "
                + "publication; they resume it", Map.of()));
            return;
        }
        PublicationLifecycle.resumed(ctx, publication, input.reason());
    }

    public static ProcessDefinition<AdminInput, PublicationOutput, ProcessContext> end() {
        return define(END, "Ends a publication as an administrator: it takes no new attempts, for good.",
            new String[] {SCHEDULED, LIVE, PAUSED, FUNDS_SHORT}, PublicationAdmin::end);
    }

    static void end(ProcessContext ctx, AdminInput input) {
        EntityInstance publication = PublicationEditing.publication(ctx);
        if (PublicationEditing.in(ctx, publication, ENDED, SCHEDULED, LIVE, PAUSED, FUNDS_SHORT)) {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("status", ENDED);
            values.put("endedAt", ctx.opTime());
            values.put("endReason", PublicationStatus.ADMIN);
            values.put("adminReason", input.reason());
            PublicationEditing.write(ctx, publication, values);
        }
    }
}
