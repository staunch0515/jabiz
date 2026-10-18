package com.jabiz.quizbuks.publishing;

import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinitionBuilder;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.process.steps.PublishEvent;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The moves of a publication its sponsor is told of by mail (Q7a, docs/quizbuks/plans/Q7-messages-legal.md D-Q7-2):
 * {@link PublicationEditing#write} notes the kind of a move worth telling ({@link #kindOf}), and the processes that
 * make such moves without the sponsor's own hand announce it as {@value #EVENT}, in their transaction: the review's
 * result, the minute's tick, a completion's move, an underfunded publication put back (or ended), and an
 * administrator's pause, end and resume. The sponsor's own submit, withdraw, pause and resume announce nothing (they
 * see the result on their page), nor does a ban's pause (a ban is not mailed, Q2-11).
 *
 * <p>The event is published once per move, in the move's transaction (a rolled back move announces nothing); the
 * mail itself is sent by the consumer {@code qb.publication-notify}, outside the publication's lock.
 */
public final class PublicationNotices {

    /** Published for every move worth telling the sponsor. */
    public static final String EVENT = "qb.publication.status";

    /** Its review approved it: it is scheduled or live. */
    public static final String APPROVED = "APPROVED";
    /** Its reviewer sent it back to its draft, with a reason. */
    public static final String REJECTED = "REJECTED";
    /** An administrator paused or ended it (with the reason the sponsor reads). */
    public static final String TAKEN_DOWN = "TAKEN_DOWN";
    /** The sponsor's wallet could not pay a reward: it takes no new attempts until topped up. */
    public static final String FUNDS_SHORT = "FUNDS_SHORT";
    /** It ended by its end time, its winners or its budget. */
    public static final String ENDED = "ENDED";

    public static final List<String> KINDS = List.of(APPROVED, REJECTED, TAKEN_DOWN, FUNDS_SHORT, ENDED);

    static final String NOTICE = "publicationNotice";

    private PublicationNotices() {}

    /**
     * The kind of notice a move of a publication from {@code from} by {@code values} makes, or null. An
     * administrator's pause (also of one its sponsor had paused) or end is a takedown, a ban's pause (its fixed reason
     * {@value PublicationAdmin#ACCOUNT_SUSPENDED}) is not; a reviewer's rejection has a reason, while a withdrawal, a
     * review that ran out of time and a ban during the review have none.
     */
    public static String kindOf(String from, Map<String, Object> values) {
        Object to = values.get("status");
        if (PublicationStatus.ADMIN.equals(values.get("endReason"))) {
            return TAKEN_DOWN;
        }
        if (PublicationStatus.BY_ADMIN.equals(values.get("pausedBy"))) {
            return PublicationAdmin.ACCOUNT_SUSPENDED.equals(values.get("adminReason")) ? null : TAKEN_DOWN;
        }
        if (to == null) {
            return null;
        }
        if (PublicationStatus.PENDING_REVIEW.equals(from)) {
            if (PublicationStatus.SCHEDULED.equals(to) || PublicationStatus.LIVE.equals(to)) {
                return APPROVED;
            }
            Object reason = values.get("rejectReason");
            return PublicationStatus.DRAFT.equals(to) && reason != null && !reason.toString().isBlank() ? REJECTED
                : null;
        }
        if (PublicationStatus.LIVE.equals(from) && PublicationStatus.FUNDS_SHORT.equals(to)) {
            return FUNDS_SHORT;
        }
        if (PublicationStatus.ENDED.equals(to) && List.of(PublicationStatus.EXPIRED, PublicationStatus.QUOTA,
            PublicationStatus.BUDGET).contains(values.get("endReason"))) {
            return ENDED;
        }
        return null;
    }

    /** Notes the notice the move makes, if any (called by {@link PublicationEditing#write} once it wrote). */
    static void note(ProcessContext ctx, EntityInstance publication, Map<String, Object> values) {
        String from = publication.get("status");
        String kind = kindOf(from, values);
        if (kind == null) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("publicationId", String.valueOf(publication.id()));
        payload.put("sponsorId", publication.get("sponsorId"));
        payload.put("from", from);
        Object to = values.get("status");
        payload.put("to", to == null ? from : to.toString());
        payload.put("kind", kind);
        Object reason = switch (kind) {
            case REJECTED -> values.get("rejectReason");
            case TAKEN_DOWN -> values.get("adminReason");
            case ENDED -> values.get("endReason");
            default -> null;
        };
        if (reason != null) {
            payload.put("reason", reason.toString());
        }
        payload.put("name", publication.get("name"));
        Instant startAt = PublicationEditing.instant(publication.get("startAt"));
        if (startAt != null) {
            payload.put("startAt", startAt.toString());
        }
        ctx.put(NOTICE, payload);
    }

    /** Announces the notice the process's move made, if any; the last step of a process that may make one. */
    static <I, O> void announce(ProcessDefinitionBuilder<I, O, ProcessContext> pb) {
        pb.step("Tell the sponsor", PublishEvent.<ProcessContext>when(ctx -> ctx.contains(NOTICE), EVENT,
            ctx -> ctx.get(NOTICE)));
    }
}
