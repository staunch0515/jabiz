package com.jabiz.quizbuks.publishing;

import com.jabiz.entity.Violation;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.operator.OperatorChecks;
import com.jabiz.quizbuks.operator.OperatorCodes;
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
 *   <li>{@code QB_PUBLICATION_ADMIN_RESUME}: resumes a publication an administrator paused, to where its times say;
 *       not while its sponsor is banned (review L9);</li>
 *   <li>{@code QB_PUBLICATION_ADMIN_END}: ends a running publication for good (end reason {@code ADMIN});</li>
 *   <li>{@code QB_PUBLICATION_BAN_PAUSE} (only a sub-process of {@code QB_USER_BAN}): pauses one of a banned
 *       sponsor's publications as an administrator's, with the fixed reason {@value #ACCOUNT_SUSPENDED} (the ban's
 *       own reason is internal, review L3); one that ended or went back to its draft since the ban read it is left
 *       as it is (review L2).</li>
 * </ul>
 * A publication an administrator paused (and did not resume) or ended is taken down ({@link #takenDown}), also once it
 * ended otherwise: it is not copied, and every later publication of its quiz is reviewed by a person (review M-d).
 * Each needs {@code qb.admin.content.write} and a recent second factor (always, whatever
 * {@code jabiz.security.mfa.administration} says), and a reason, which the sponsor reads with the publication. The
 * attempts in progress complete as they would after the end time. Locks as every process that changes the state of a
 * publication that went on: {@code qb.pub:<p>} then {@code qb.pub-start:<p>}, before the reads.
 *
 * <p>An operator's members take publications down the same way (Q48a, D-Q48-4: {@code QB_OPERATOR_PUBLICATION_*},
 * {@code qb.operator.use}, after their membership is checked and held): the publication records the operator
 * ({@code takedownOperatorId}; null for headquarters), an operator resumes only its own takedowns and does not end
 * one headquarters made, and headquarters' pause of an operator's takedown makes it headquarters' (D-Q48-5).
 */
public final class PublicationAdmin {

    public static final String PAUSE = "QB_PUBLICATION_ADMIN_PAUSE";
    public static final String RESUME = "QB_PUBLICATION_ADMIN_RESUME";
    public static final String END = "QB_PUBLICATION_ADMIN_END";
    public static final String BAN_PAUSE = "QB_PUBLICATION_BAN_PAUSE";
    /** Q69: ends a publication of a sponsor whose account was erased (a sub-process of {@code QB_SPONSOR_ERASED}). */
    public static final String ERASED_END = "QB_PUBLICATION_ERASED_END";
    public static final String OPERATOR_PAUSE = "QB_OPERATOR_PUBLICATION_PAUSE";
    public static final String OPERATOR_RESUME = "QB_OPERATOR_PUBLICATION_RESUME";
    public static final String OPERATOR_END = "QB_OPERATOR_PUBLICATION_END";

    /**
     * The reason a ban's pause records, in place of the ban's own (review L3): a code, which the sponsor's console and
     * the administration show in the reader's language ({@code messages_*.properties} has it too).
     */
    public static final String ACCOUNT_SUSPENDED = "QB_ACCOUNT_SUSPENDED";
    /** The reason an erased sponsor's publications end with (Q69), a code shown in the reader's language. */
    public static final String ACCOUNT_DELETED = "QB_ACCOUNT_DELETED";

    /**
     * Whether an administrator took the publication down: ended it, or paused it (a ban's pause included) and did not
     * resume it, whatever became of it since: a pause that runs into its end time or its quota ends with
     * {@code pausedBy} kept (verification of review M-d). Only an administrator's resume clears {@code pausedBy}.
     */
    public static boolean takenDown(EntityInstance publication) {
        return publication != null && (PublicationStatus.BY_ADMIN.equals(publication.get("pausedBy"))
            || PublicationStatus.ADMIN.equals(publication.get("endReason")));
    }

    /** @param reason why, as the sponsor will read it */
    public record AdminInput(@NotNull UUID publicationId,
        @NotBlank @Size(max = PublishingLimits.REJECT_REASON_LENGTH) String reason) {}

    /**
     * Input of {@value #BAN_PAUSE}: the banned sponsor's publication, and the operator whose member banned them (null:
     * headquarters).
     */
    public record BanPauseInput(@NotNull UUID publicationId, String operatorId) {
        public BanPauseInput(UUID publicationId) {
            this(publicationId, null);
        }
    }

    private PublicationAdmin() {}

    private static ProcessDefinition<AdminInput, PublicationOutput, ProcessContext> define(String name,
        String description, String[] from, boolean operator,
        java.util.function.BiConsumer<ProcessContext, AdminInput> compute) {
        return define(name, description, from, false, operator, compute);
    }

    /**
     * @param readBan whether to read the sponsor's ban too ({@link PublicationEditing#sponsorBanned}), under the
     *                sponsor's account lock taken before the publication's (a resume and a ban take turns)
     */
    /**
     * @param operator whether an operator's member runs it ({@code qb.operator.use}, the membership checked and held
     *                 first: lock order {@code qb.operator-member} before the account's and the publication's)
     */
    private static ProcessDefinition<AdminInput, PublicationOutput, ProcessContext> define(String name,
        String description, String[] from, boolean readBan, boolean operator,
        java.util.function.BiConsumer<ProcessContext, AdminInput> compute) {
        return ProcessDefinition.define(name, 1, AdminInput.class, PublicationOutput.class, ProcessContext.class,
            pb -> pb
                .description(description)
                .permissions(operator ? QbPermissions.OPERATOR_USE : QbPermissions.ADMIN_CONTENT_WRITE)
                .requiresMfa(MfaRequirement.ALWAYS)
                .requiresVerifiedEmail()
                .actsOn(QbPublications.PUBLICATION, "publicationId", a -> a.whenField("status", from))
                .contextFactory((start, input) -> PublicationEditing.start(start, input, input.publicationId()))
                .outputMapper(ctx -> ctx.get(OUTPUT, PublicationOutput.class))
                .steps(b -> {
                    if (operator) {
                        OperatorChecks.member(b);
                    }
                })
                .steps(b -> {
                    if (readBan) {
                        PublicationEditing.lockSponsorAccount(b);
                    }
                })
                .steps(PublicationEditing::lockStatesAndLoadAny)
                .steps(b -> {
                    if (operator) {
                        // Q59c-2: only a publication of a sponsor the caller's operator looks after.
                        OperatorChecks.ownSponsor(b, ctx -> {
                            EntityInstance publication = PublicationEditing.publication(ctx);
                            return publication == null ? null : publication.get("publisherId");
                        });
                    }
                })
                .steps(b -> {
                    if (readBan) {
                        PublicationEditing.loadSponsorBan(b, PublicationEditing::publication);
                    }
                })
                .compute("Act", (metadata, ctx) -> compute.accept(ctx,
                    ctx.get(PublicationEditing.INPUT, AdminInput.class)))
                .steps(PublicationNotices::announce));
    }

    public static ProcessDefinition<AdminInput, PublicationOutput, ProcessContext> pause() {
        return define(PAUSE, "Pauses a publication as an administrator: only an administrator can resume it.",
            new String[] {SCHEDULED, LIVE, FUNDS_SHORT, PAUSED}, false, PublicationAdmin::pause);
    }

    /** {@value #OPERATOR_PAUSE}: an operator's member pauses it; only the operator or headquarters resumes it. */
    public static ProcessDefinition<AdminInput, PublicationOutput, ProcessContext> operatorPause() {
        return define(OPERATOR_PAUSE, "Pauses a publication for the operator: only the operator or headquarters can "
            + "resume it.", new String[] {SCHEDULED, LIVE, FUNDS_SHORT, PAUSED}, true, PublicationAdmin::pause);
    }

    static void pause(ProcessContext ctx, AdminInput input) {
        pause(ctx, input.reason(), false, operatorOf(ctx));
    }

    /** The caller's operator when an operator's member runs the process; null for headquarters. */
    private static String operatorOf(ProcessContext ctx) {
        return ctx.contains(OperatorChecks.OPERATOR_ID) ? OperatorChecks.operatorId(ctx) : null;
    }

    /**
     * @param ifRunning  whether a publication that no longer runs is left as it is (a ban's) or refused
     * @param operatorId the operator whose member pauses it; null for headquarters
     */
    private static void pause(ProcessContext ctx, String reason, boolean ifRunning, String operatorId) {
        EntityInstance publication = PublicationEditing.publication(ctx);
        if (ifRunning && !RUNNING.contains(String.valueOf((Object) publication.get("status")))) {
            // Ended, or back to its draft (or in review), since the ban read it: nothing to pause (review L2).
            PublicationEditing.unchanged(ctx, publication);
            return;
        }
        String status = publication.get("status");
        Map<String, Object> values = new LinkedHashMap<>();
        if (PAUSED.equals(status)) {
            if (PublicationStatus.BY_ADMIN.equals(publication.get("pausedBy"))) {
                if (operatorId != null || publication.get("takedownOperatorId") == null) {
                    // Paused by an administrator already (a second ban, say): nothing to do.
                    PublicationEditing.unchanged(ctx, publication);
                    return;
                }
                // An operator's takedown that headquarters pauses becomes headquarters' (D-Q48-5).
            }
            // The sponsor paused it: it stays paused, now as the administrator's.
        } else if (PublicationEditing.in(ctx, publication, PAUSED, SCHEDULED, LIVE, FUNDS_SHORT)) {
            values.put("status", PAUSED);
            values.put("pausedAt", ctx.opTime());
        } else {
            return;
        }
        values.put("pausedBy", PublicationStatus.BY_ADMIN);
        values.put("adminReason", reason);
        values.put("takedownOperatorId", operatorId);
        PublicationEditing.write(ctx, publication, values);
    }

    private static final java.util.Set<String> RUNNING = java.util.Set.of(PAUSED, SCHEDULED, LIVE, FUNDS_SHORT);

    /**
     * {@value #BAN_PAUSE}: the ban's pause of one publication, as {@code QB_PUBLICATION_ADMIN_PAUSE} with the fixed
     * reason, and nothing to do for one that no longer runs. Only a sub-process: the ban (its root) declares the
     * permission and is the one checked at the entry.
     */
    public static ProcessDefinition<BanPauseInput, PublicationOutput, ProcessContext> banPause() {
        return ProcessDefinition.define(BAN_PAUSE, 1, BanPauseInput.class, PublicationOutput.class,
            ProcessContext.class, pb -> pb
                .description("Pauses a banned sponsor's publication (part of the ban only).")
                .permissions(QbPermissions.PUBLICATION_SYSTEM)
                .subProcessOnly()
                .contextFactory((start, input) -> PublicationEditing.start(start, input, input.publicationId()))
                .outputMapper(ctx -> ctx.get(OUTPUT, PublicationOutput.class))
                .steps(PublicationEditing::lockStatesAndLoadAny)
                .compute("Pause", (metadata, ctx) -> pause(ctx, ACCOUNT_SUSPENDED, true,
                    ctx.get(PublicationEditing.INPUT, BanPauseInput.class).operatorId())));
    }

    /**
     * {@value #ERASED_END} (Q69; docs/quizbuks/plans/Q69-sponsor-erasure.md): ends a publication of a sponsor whose
     * account was erased, as an administrator's end with the reason {@value #ACCOUNT_DELETED}: it takes no new attempts
     * and leaves the takers' pages; its records stay. One that ended or went back to its draft meanwhile is left as it
     * is.
     */
    public static ProcessDefinition<BanPauseInput, PublicationOutput, ProcessContext> erasedEnd() {
        return ProcessDefinition.define(ERASED_END, 1, BanPauseInput.class, PublicationOutput.class,
            ProcessContext.class, pb -> pb
                .description("Ends a publication of a sponsor whose account was erased (part of the erasure only).")
                .permissions(QbPermissions.PUBLICATION_SYSTEM)
                .subProcessOnly()
                .contextFactory((start, input) -> PublicationEditing.start(start, input, input.publicationId()))
                .outputMapper(ctx -> ctx.get(OUTPUT, PublicationOutput.class))
                .steps(PublicationEditing::lockStatesAndLoadAny)
                .compute("End", (metadata, ctx) -> endErased(ctx)));
    }

    static void endErased(ProcessContext ctx) {
        EntityInstance publication = PublicationEditing.publication(ctx);
        if (!ENDABLE.contains(String.valueOf((Object) publication.get("status")))) {
            // Ended, or back to its draft, since the erasure read it: nothing to end.
            PublicationEditing.unchanged(ctx, publication);
            return;
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("status", ENDED);
        values.put("endedAt", ctx.opTime());
        values.put("endReason", PublicationStatus.ADMIN);
        values.put("adminReason", ACCOUNT_DELETED);
        PublicationEditing.write(ctx, publication, values);
    }

    /** The states an erased sponsor's publication is ended from (Q69). */
    public static final java.util.Set<String> ENDABLE = java.util.Set.of(SCHEDULED, LIVE, PAUSED, FUNDS_SHORT);

    public static ProcessDefinition<AdminInput, PublicationOutput, ProcessContext> resume() {
        return define(RESUME, "Resumes a publication an administrator paused: scheduled before its start, live "
            + "within its time, ended after its end.", new String[] {PAUSED}, true, false, PublicationAdmin::resume);
    }

    /** {@value #OPERATOR_RESUME}: an operator's member resumes a publication their operator took down. */
    public static ProcessDefinition<AdminInput, PublicationOutput, ProcessContext> operatorResume() {
        return define(OPERATOR_RESUME, "Resumes a publication the operator paused: scheduled before its start, live "
            + "within its time, ended after its end.", new String[] {PAUSED}, true, true, PublicationAdmin::resume);
    }

    static void resume(ProcessContext ctx, AdminInput input) {
        EntityInstance publication = PublicationEditing.publication(ctx);
        String operatorId = operatorOf(ctx);
        if (operatorId != null && PublicationStatus.BY_ADMIN.equals(publication.get("pausedBy"))
            && !operatorId.equals(publication.get("takedownOperatorId"))) {
            ctx.reject(new Violation("publicationId", OperatorCodes.HQ_TAKEDOWN, "Headquarters took this "
                + "publication down: the operator does not resume it", Map.of()));
            return;
        }
        if (PAUSED.equals(publication.get("status"))
            && !PublicationStatus.BY_ADMIN.equals(publication.get("pausedBy"))) {
            ctx.reject(new Violation("publicationId", PublishingCodes.NOT_ADMIN_PAUSED, "Its sponsor paused this "
                + "publication; they resume it", Map.of()));
            return;
        }
        if (PublicationEditing.sponsorBanned(ctx)) {
            // Review L9: the ban paused it; lift the ban first.
            ctx.reject(new Violation("publicationId", PublishingCodes.SPONSOR_BANNED, "The publication's sponsor is "
                + "banned: lift the ban before resuming it", Map.of()));
            return;
        }
        PublicationLifecycle.resumed(ctx, publication, input.reason());
    }

    public static ProcessDefinition<AdminInput, PublicationOutput, ProcessContext> end() {
        return define(END, "Ends a publication as an administrator: it takes no new attempts, for good.",
            new String[] {SCHEDULED, LIVE, PAUSED, FUNDS_SHORT}, false, PublicationAdmin::end);
    }

    /** {@value #OPERATOR_END}: an operator's member ends a publication, not one headquarters took down. */
    public static ProcessDefinition<AdminInput, PublicationOutput, ProcessContext> operatorEnd() {
        return define(OPERATOR_END, "Ends a publication for the operator: it takes no new attempts, for good.",
            new String[] {SCHEDULED, LIVE, PAUSED, FUNDS_SHORT}, true, PublicationAdmin::end);
    }

    static void end(ProcessContext ctx, AdminInput input) {
        EntityInstance publication = PublicationEditing.publication(ctx);
        String operatorId = operatorOf(ctx);
        if (operatorId != null && PublicationStatus.BY_ADMIN.equals(publication.get("pausedBy"))
            && !operatorId.equals(publication.get("takedownOperatorId"))) {
            ctx.reject(new Violation("publicationId", OperatorCodes.HQ_TAKEDOWN, "Headquarters took this "
                + "publication down: the operator does not end it", Map.of()));
            return;
        }
        if (PublicationEditing.in(ctx, publication, ENDED, SCHEDULED, LIVE, PAUSED, FUNDS_SHORT)) {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("status", ENDED);
            values.put("endedAt", ctx.opTime());
            values.put("endReason", PublicationStatus.ADMIN);
            values.put("adminReason", input.reason());
            values.put("takedownOperatorId", operatorId);
            PublicationEditing.write(ctx, publication, values);
        }
    }
}
