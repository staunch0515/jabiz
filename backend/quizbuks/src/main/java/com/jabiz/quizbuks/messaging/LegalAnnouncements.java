package com.jabiz.quizbuks.messaging;

import com.jabiz.process.ProcessContext;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The in-app notice of a new version of a legal text (Q7c; the item Q7b left for it, Q7-12: no mass mail): published
 * with {@code announce}, {@code QB_LEGAL_PUBLISH} writes a system announcement to those the text concerns, scheduled
 * for now, which the minute's job sends (counting its audience) as any other. Its texts are the messages
 * {@code qb.broadcast.legal.title|body} in every language, with the text's name and when it takes effect (Japan time).
 */
final class LegalAnnouncements {

    private LegalAnnouncements() {}

    /** Who a kind of text concerns: the app's users, the sponsors, or both (the privacy policy). */
    static List<String> audiences(String kind) {
        return switch (kind) {
            case QbLegal.TERMS_APP, QbLegal.CONTEST_RULES -> List.of(QbInbox.AUDIENCE_APP);
            case QbLegal.PRIVACY -> List.of(QbInbox.AUDIENCE_APP, QbInbox.AUDIENCE_SPONSOR);
            default -> List.of(QbInbox.AUDIENCE_SPONSOR);
        };
    }

    /** What the announcements of a version are found by when it is withdrawn (review L2). */
    static String sourceKey(Object versionId) {
        return "legal:" + versionId;
    }

    /** Writes the announcements of a version, scheduled now; their ids. */
    static List<String> schedule(ProcessContext ctx, MessageTexts texts, MailValues times, String kind, int number,
        Instant effectiveAt, Object versionId) {
        Map<String, MessageTexts.Value> params = new LinkedHashMap<>();
        params.put("document", texts.message("qb.broadcast.legal.kind." + kind));
        params.put("versionNo", MessageTexts.Value.of(number));
        params.put("date", MessageTexts.Value.of(times.time(effectiveAt)));
        Map<String, String> title = texts.render("qb.broadcast.legal.title", params);
        Map<String, String> body = texts.render("qb.broadcast.legal.body", params);
        Instant from = effectiveAt.isAfter(ctx.opTime()) ? effectiveAt : ctx.opTime();
        Instant expiresAt = from.plus(Duration.ofDays(BroadcastRules.DEFAULT_EXPIRES_DAYS));
        List<String> ids = new ArrayList<>();
        for (String audience : audiences(kind)) {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("senderKind", QbInbox.SENDER_PLATFORM);
            values.put("senderId", ctx.request().actorId());
            values.put("audience", audience);
            values.put("type", QbInbox.BROADCAST_SYSTEM);
            values.put("title", title);
            values.put("body", body);
            values.put("targetMode", QbInbox.TARGET_ALL);
            values.put("scheduledAt", ctx.opTime());
            values.put("expiresAt", expiresAt);
            values.put("status", QbInbox.SCHEDULED);
            values.put("sourceKey", sourceKey(versionId));
            values.put("revision", BigDecimal.ONE);
            values.put("editedAt", ctx.opTime());
            Object id = ctx.changes().insert(QbInbox.BROADCAST, values);
            Map<String, Object> due = new LinkedHashMap<>();
            // The key as the entity has it (a UUID), not as the change set handed it back.
            due.put("broadcastId", java.util.UUID.fromString(String.valueOf(id)));
            due.put("dueAt", ctx.opTime());
            ctx.changes().insert(QbInbox.SCHEDULE, due);
            ids.add(String.valueOf(id));
        }
        return List.copyOf(ids);
    }
}
