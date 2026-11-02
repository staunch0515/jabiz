package com.jabiz.quizbuks.conversation;

import com.jabiz.entity.Violation;
import com.jabiz.quizbuks.messaging.BroadcastRules;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The rules of the conversations (Q82, D-Q82-3 to D-Q82-6), pure Java: what a message may say, how many a side sends
 * in a day, who may open one, when a side may write, and what is unread. The processes decide with these and nothing
 * else; the templates' "unread" is {@link #unread}, written once more in SQL.
 */
public final class ConversationRules {

    /** The longest message, in characters (as a sponsor's notice). */
    public static final int BODY_LENGTH = BroadcastRules.SPONSOR_BODY_LENGTH;
    /** The window of the daily cap: 24 hours back from now, not a calendar day. */
    static final Duration DAY = Duration.ofDays(1);

    private ConversationRules() {}

    /** The problems of a message's text: empty, too long, or with an address or a contact (as a notice's, Q7c). */
    public static List<Violation> bodyProblems(String body) {
        List<Violation> problems = new ArrayList<>();
        String text = body == null ? "" : body.strip();
        if (text.isEmpty()) {
            problems.add(new Violation("body", ConversationCodes.BODY_REQUIRED, "Write a message", Map.of()));
            return problems;
        }
        if (text.codePointCount(0, text.length()) > BODY_LENGTH) {
            problems.add(new Violation("body", ConversationCodes.BODY_TOO_LONG, "The message is too long",
                Map.of("max", BODY_LENGTH)));
        }
        if (BroadcastRules.hasAddress(text)) {
            problems.add(new Violation("body", ConversationCodes.ADDRESS,
                "A message is plain text without addresses, phone numbers or other accounts", Map.of()));
        }
        return problems;
    }

    /** The text as stored: without the spaces around it. */
    public static String stored(String body) {
        return body == null ? null : body.strip();
    }

    /** Whether a side that sent {@code sent} (their messages' times in this conversation) reached {@code cap}. */
    public static boolean overDailyCap(List<Instant> sent, Instant now, int cap) {
        Instant since = now.minus(DAY);
        return sent.stream().filter(time -> time != null && time.isAfter(since)).count() >= Math.max(cap, 0);
    }

    /**
     * What a taker opening a conversation about a publication meets, all known before anything is written.
     *
     * @param sponsorPublication the publication is a sponsor's (a shop's storefront quiz has no conversation)
     * @param completed          the taker completed an attempt of it
     * @param referrer           the taker referred people to it (Q70)
     * @param ownPublication     the taker is its sponsor
     * @param blocked            the taker blocked its sponsor
     * @param sponsorBanned      its sponsor's account is banned
     */
    public record Opening(boolean sponsorPublication, boolean completed, boolean referrer, boolean ownPublication,
        boolean blocked, boolean sponsorBanned) {}

    /** Why a taker cannot open a conversation, or null when they can. */
    public static String openRefusal(Opening opening) {
        if (!opening.sponsorPublication() || opening.ownPublication()
            || !opening.completed() && !opening.referrer()) {
            return ConversationCodes.NOT_ELIGIBLE;
        }
        if (opening.blocked()) {
            return ConversationCodes.BLOCKED;
        }
        if (opening.sponsorBanned()) {
            return ConversationCodes.UNAVAILABLE;
        }
        return null;
    }

    /**
     * Why a side cannot write in a conversation now, or null when it can: a closed one takes nothing (the taker
     * opens it again first), a blocked sponsor's nothing, and a banned other side nothing.
     */
    public static String sendRefusal(String status, boolean blocked, boolean otherBanned) {
        if (!QbConversations.OPEN.equals(status)) {
            return ConversationCodes.CLOSED;
        }
        if (blocked) {
            return ConversationCodes.BLOCKED;
        }
        if (otherBanned) {
            return ConversationCodes.UNAVAILABLE;
        }
        return null;
    }

    /**
     * Whether {@code side} has something unread: the other side wrote last, after {@code side} last read (never read
     * counts as before).
     */
    public static boolean unread(String side, String lastSender, Instant lastMessageAt, Instant readAt) {
        return lastSender != null && !lastSender.equals(side) && lastMessageAt != null
            && (readAt == null || readAt.isBefore(lastMessageAt));
    }

    /**
     * Whether a conversation's last message is so old that the expiry closes it: no message for {@code days} days.
     */
    public static boolean due(String status, Instant lastMessageAt, Instant now, int days) {
        return QbConversations.OPEN.equals(status) && lastMessageAt != null && days > 0
            && !lastMessageAt.plus(Duration.ofDays(days)).isAfter(now);
    }

    /** The side of the caller: the taker's, the sponsor's (of a sponsor's publication), or null for neither. */
    public static String sideOf(String actorId, Object takerId, Object publisherKind, Object publisherId) {
        if (actorId == null) {
            return null;
        }
        if (actorId.equals(String.valueOf(takerId))) {
            return QbConversations.TAKER;
        }
        if ("SPONSOR".equals(String.valueOf(publisherKind)) && actorId.equals(String.valueOf(publisherId))) {
            return QbConversations.SPONSOR;
        }
        return null;
    }
}
