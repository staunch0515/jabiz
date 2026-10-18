package com.jabiz.quizbuks.messaging;

import com.jabiz.entity.Violation;
import com.jabiz.quizbuks.country.Regions;
import com.jabiz.quizbuks.publishing.PublicationStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The rules of sending announcements and notices (docs/quizbuks/plans/Q7-messages-legal.md, Q7c; Q7-9, Q7-10), in
 * plain Java so that they are tested on their own and report every problem at once. Saving a draft answers with the
 * problems sending it would meet; sending refuses with them.
 * <ul>
 *   <li>Every announcement has a title and a text; the platform's need English (the fallback of every language). It
 *       goes now or at a later time (at most {@link #MAX_AHEAD} ahead) and expires after it was sent: the platform's
 *       when the administrator says, at most {@link #MAX_AHEAD} after the sending, by default
 *       {@code qb.broadcast.expires-days} after it.</li>
 *   <li>The app's announcements go to everyone, to regions or to named users (at most {@link #MAX_TARGETS}); the
 *       sponsor console's to every sponsor or to named ones.</li>
 *   <li>A sponsor's notice (Q7-10) goes to the takers of one of their own scheduled or live publications, in the
 *       publication's regions or some of them, before it ends, and expires when it ends. It is plain text without any
 *       address (the only way out is its quiz). A publication has at most
 *       {@code qb.broadcast.sponsor.max-per-publication} notices (withdrawn ones count), each at least
 *       {@code qb.broadcast.sponsor.min-interval-hours} from the others. Not reviewed before: an administrator
 *       withdraws one, and a ban withdraws them all.</li>
 * </ul>
 */
public final class BroadcastRules {

    /** At most this many named users per announcement (Q7-9). */
    public static final int MAX_TARGETS = 1_000;
    /** How far ahead an announcement may be scheduled, and how long after its sending it may stay. */
    public static final Duration MAX_AHEAD = Duration.ofDays(365);
    /** The longest text of a sponsor's notice. */
    public static final int SPONSOR_BODY_LENGTH = 1_000;
    /** The language every announcement of the platform has: the fallback of the others. */
    public static final String FALLBACK_LANGUAGE = "en";

    /** The statistics a sponsor reads show no count below this (one reader of a region is not shown as one). */
    public static final int MIN_GROUP = 5;

    /** The defaults of the parameters, while they have no value. */
    public static final int DEFAULT_MAX_PER_PUBLICATION = 3;
    public static final int DEFAULT_MIN_INTERVAL_HOURS = 24;
    public static final int DEFAULT_EXPIRES_DAYS = 30;

    /**
     * Anything that reads as an address or a way to reach someone off the platform (review M3: matched by form, not
     * by a list of domains): a scheme (also {@code javascript:} and its kind), {@code www.}, an e-mail address, any
     * {@code label.label} host whose last label is two or more Latin or Cyrillic letters or an IDN ({@code xn--}), an
     * IPv4 address, a {@code LINE ID}, an {@code @handle} or a telephone-length string of digits. Matched on the text
     * {@link #normalized} first. Plain text is never turned into a link by the pages either; this keeps phishing text
     * out.
     */
    static final Pattern ADDRESS = Pattern.compile("(?iu)(\\b[a-z][a-z0-9+.-]*://"
        + "|\\b(?:javascript|vbscript|data|mailto|tel|sms|file):"
        + "|\\bwww\\d*\\."
        + "|[\\p{L}\\p{N}._%+-]+@[\\p{L}\\p{N}-]+\\.[\\p{L}\\p{N}.-]+"
        + "|(?<![\\p{L}\\p{N}_-])(?:[\\p{L}\\p{N}](?:[\\p{L}\\p{N}-]*[\\p{L}\\p{N}])?\\.)+"
        + "(?:\\p{IsLatin}{2,24}|\\p{IsCyrillic}{2,24})(?![\\p{L}\\p{N}])"
        + "|\\b\\d{1,3}(?:\\.\\d{1,3}){3}\\b"
        + "|\\bline\\s*id\\b"
        + "|(?<![\\p{L}\\p{N}._%+-])@[\\p{L}\\p{N}_.]{3,}"
        + "|\\d(?:[-\u2010\u2013\u2212]?\\d){9,})");

    /** Invisible characters (zero-width, soft hyphens, joiners, every format character) that split an address. */
    private static final Pattern INVISIBLE = Pattern.compile("[\\p{Cf}\u00AD\u034F\u115F\u1160\u3164\uFFA0]");
    /** {@code [.]}, {@code (.)}, {@code [dot]} and {@code [:]}: the usual ways of writing an address unlinked. */
    private static final Pattern BRACKETED_DOT = Pattern.compile("(?i)\\s*[\\[(]\\s*(?:\\.|dot)\\s*[\\])]\\s*");
    private static final Pattern BRACKETED_COLON = Pattern.compile("\\s*\\[\\s*:\\s*\\]\\s*");
    /** An ideographic full stop between Latin letters or digits ({@code evil。com}); not in Japanese sentences. */
    private static final Pattern WIDE_DOT = Pattern.compile("(?<=[\\p{IsLatin}\\p{N}])\\s*\u3002\\s*(?=[\\p{IsLatin}\\p{N}])");
    /** {@code evil . com}, {@code evil dot com}: a dot or the word with spaces on both sides. */
    private static final Pattern SPOKEN_DOT = Pattern.compile(
        "(?i)(?<=[\\p{L}\\p{N}])\\s+(?:\\.|dot)\\s+(?=[\\p{L}\\p{N}])");
    /** Letters spaced out after a dot ({@code evil.c o m}). */
    private static final Pattern SPACED_OUT = Pattern.compile("(?<=\\.)\\p{IsLatin}(?: \\p{IsLatin})+(?![\\p{L}\\p{N}])");

    /** Whether a text reads as an address or a contact ({@link #ADDRESS} on {@link #normalized}). */
    public static boolean hasAddress(String text) {
        return text != null && ADDRESS.matcher(normalized(text)).find();
    }

    /**
     * The text as an address check reads it: NFKC (full-width letters, digits and dots become ASCII), invisible
     * characters removed, and the written-out dots and colons made real.
     */
    static String normalized(String text) {
        String s = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFKC);
        s = INVISIBLE.matcher(s).replaceAll("");
        s = BRACKETED_DOT.matcher(s).replaceAll(".");
        s = BRACKETED_COLON.matcher(s).replaceAll(":");
        s = WIDE_DOT.matcher(s).replaceAll(".");
        s = SPOKEN_DOT.matcher(s).replaceAll(".");
        return SPACED_OUT.matcher(s).replaceAll(match -> match.group().replace(" ", ""));
    }

    /** The limits of the sponsors' notices and the platform's default expiry, from the parameters. */
    public record Limits(int maxPerPublication, Duration minInterval, Duration defaultExpiry) {
        public static Limits defaults() {
            return new Limits(DEFAULT_MAX_PER_PUBLICATION, Duration.ofHours(DEFAULT_MIN_INTERVAL_HOURS),
                Duration.ofDays(DEFAULT_EXPIRES_DAYS));
        }
    }

    /**
     * What is to be sent.
     *
     * @param title       by language
     * @param regions     the written set ({@link Regions#TARGETS}), or null
     * @param targetCount the named users
     * @param expiresAt   the platform's chosen expiry, or null for the default
     */
    public record Draft(String senderKind, String audience, String type, Map<String, ?> title, Map<String, ?> body,
        String targetMode, String regions, int targetCount, String publicationId, Instant scheduledAt,
        Instant expiresAt) {
        public boolean sponsors() {
            return QbInbox.SENDER_SPONSOR.equals(senderKind);
        }
    }

    /** A sponsor's publication as its card has it. */
    public record Publication(String publicationId, String sponsorId, String status, String regions,
        Instant startAt, Instant endAt) {}

    /** Another notice of the sponsor's: its publication, state and when it goes or went out. */
    public record Other(String broadcastId, String publicationId, String status, Instant sendsAt) {}

    /** When the announcement goes out: its scheduled time, or now. */
    public static Instant sendsAt(Draft draft, Instant now) {
        return draft.scheduledAt() == null ? now : draft.scheduledAt();
    }

    /** When it expires: a sponsor's when its publication ends, the platform's as chosen or by default. */
    public static Instant expiresAt(Draft draft, Instant sendsAt, Publication publication, Limits limits) {
        if (draft.sponsors()) {
            return publication == null ? null : publication.endAt();
        }
        return draft.expiresAt() == null ? sendsAt.plus(limits.defaultExpiry()) : draft.expiresAt();
    }

    /**
     * Every problem sending {@code draft} at {@code now} meets (none: it may be sent).
     *
     * @param publication the card of a sponsor's publication, when it is the sponsor's own; null otherwise
     * @param others      the sponsor's other notices (ignored for the platform's)
     */
    public static List<Violation> sendProblems(Draft draft, Instant now, Publication publication, List<Other> others,
        Limits limits) {
        List<Violation> problems = new ArrayList<>();
        if (!hasText(draft.title())) {
            problems.add(violation("title", BroadcastCodes.TITLE_REQUIRED, "A title is needed", Map.of()));
        }
        if (!hasText(draft.body())) {
            problems.add(violation("body", BroadcastCodes.BODY_REQUIRED, "A text is needed", Map.of()));
        }
        Instant sendsAt = sendsAt(draft, now);
        if (draft.scheduledAt() != null) {
            if (!draft.scheduledAt().isAfter(now)) {
                problems.add(violation("scheduledAt", BroadcastCodes.SCHEDULE_PAST,
                    "The scheduled time must be in the future", Map.of()));
            } else if (draft.scheduledAt().isAfter(now.plus(MAX_AHEAD))) {
                problems.add(violation("scheduledAt", BroadcastCodes.SCHEDULE_TOO_LATE, "Scheduled too far ahead",
                    Map.of("days", MAX_AHEAD.toDays())));
            }
        }
        if (draft.sponsors()) {
            sponsorProblems(draft, sendsAt, publication, others, limits, problems);
        } else {
            platformProblems(draft, sendsAt, problems);
        }
        return List.copyOf(problems);
    }

    private static void platformProblems(Draft draft, Instant sendsAt, List<Violation> problems) {
        if (hasText(draft.title()) && blank(draft.title().get(FALLBACK_LANGUAGE))) {
            problems.add(violation("title", BroadcastCodes.TRANSLATION_MISSING, "The title needs an English text",
                Map.of("field", "title", "lang", FALLBACK_LANGUAGE)));
        }
        if (hasText(draft.body()) && blank(draft.body().get(FALLBACK_LANGUAGE))) {
            problems.add(violation("body", BroadcastCodes.TRANSLATION_MISSING, "The text needs an English text",
                Map.of("field", "body", "lang", FALLBACK_LANGUAGE)));
        }
        if (draft.expiresAt() != null && (!draft.expiresAt().isAfter(sendsAt)
            || draft.expiresAt().isAfter(sendsAt.plus(MAX_AHEAD)))) {
            problems.add(violation("expiresAt", BroadcastCodes.EXPIRY,
                "It must expire after it is sent, within a year", Map.of("days", MAX_AHEAD.toDays())));
        }
        targetProblems(draft, problems);
    }

    private static void targetProblems(Draft draft, List<Violation> problems) {
        switch (draft.targetMode()) {
            case QbInbox.TARGET_REGIONS -> {
                if (QbInbox.AUDIENCE_SPONSOR.equals(draft.audience())) {
                    problems.add(violation("targetMode", BroadcastCodes.TARGET_MODE,
                        "Sponsors are reached all together or by name", Map.of()));
                } else if (Regions.parse(draft.regions()).isEmpty()) {
                    problems.add(violation("regions", BroadcastCodes.REGIONS_REQUIRED, "Choose the regions",
                        Map.of()));
                }
            }
            case QbInbox.TARGET_USERS -> {
                if (draft.targetCount() == 0) {
                    problems.add(violation("userIds", BroadcastCodes.USERS_REQUIRED, "Name the users", Map.of()));
                } else if (draft.targetCount() > MAX_TARGETS) {
                    problems.add(tooManyUsers());
                }
            }
            default -> { }
        }
    }

    static Violation tooManyUsers() {
        return violation("userIds", BroadcastCodes.TOO_MANY_USERS, "At most " + MAX_TARGETS + " users",
            Map.of("max", MAX_TARGETS));
    }

    private static void sponsorProblems(Draft draft, Instant sendsAt, Publication publication, List<Other> others,
        Limits limits, List<Violation> problems) {
        for (Map.Entry<String, ?> text : texts(draft)) {
            if (text.getValue() != null && hasAddress(String.valueOf(text.getValue()))) {
                problems.add(violation(text.getKey(), BroadcastCodes.LINK,
                    "A notice is plain text without addresses", Map.of()));
                break;
            }
        }
        if (draft.body() != null && draft.body().values().stream().filter(Objects::nonNull)
            .anyMatch(text -> String.valueOf(text).codePointCount(0, String.valueOf(text).length())
                > SPONSOR_BODY_LENGTH)) {
            problems.add(violation("body", BroadcastCodes.BODY_TOO_LONG, "The text is too long",
                Map.of("max", SPONSOR_BODY_LENGTH)));
        }
        if (draft.publicationId() == null) {
            problems.add(violation("publicationId", BroadcastCodes.PUBLICATION_REQUIRED,
                "Choose one of your publications", Map.of()));
            return;
        }
        if (publication == null || !Set.of(PublicationStatus.SCHEDULED, PublicationStatus.LIVE)
            .contains(publication.status())) {
            problems.add(violation("publicationId", BroadcastCodes.PUBLICATION_NOT_ON,
                "The publication is not scheduled or live", Map.of("status",
                    publication == null ? "" : publication.status())));
            return;
        }
        if (!sendsAt.isBefore(publication.endAt())) {
            problems.add(violation("scheduledAt", BroadcastCodes.AFTER_END,
                "The notice would go out after the publication ends", Map.of("endAt", publication.endAt().toString())));
        }
        List<String> regions = Regions.parse(draft.regions());
        if (regions.isEmpty()) {
            problems.add(violation("regions", BroadcastCodes.REGIONS_REQUIRED, "Choose the regions", Map.of()));
        } else if (!within(regions, publication.regions())) {
            problems.add(violation("regions", BroadcastCodes.REGIONS_OUTSIDE,
                "The regions must be among the publication's", Map.of("regions", publication.regions())));
        }
        List<Other> counted = others.stream()
            .filter(other -> draft.publicationId().equals(other.publicationId()))
            .filter(other -> Set.of(QbInbox.SCHEDULED, QbInbox.SENT, QbInbox.WITHDRAWN).contains(other.status()))
            .toList();
        if (counted.size() + 1 > limits.maxPerPublication()) {
            problems.add(violation("publicationId", BroadcastCodes.TOO_MANY,
                "The publication had all the notices it may have", Map.of("max", limits.maxPerPublication())));
        }
        Optional<Other> close = counted.stream().filter(other -> other.sendsAt() != null)
            .filter(other -> Duration.between(other.sendsAt(), sendsAt).abs().compareTo(limits.minInterval()) < 0)
            .min(Comparator.comparing(Other::sendsAt));
        close.ifPresent(other -> problems.add(violation("scheduledAt", BroadcastCodes.TOO_SOON,
            "Another notice of the publication goes out within the interval", Map.of("hours",
                limits.minInterval().toHours(), "at", other.sendsAt().plus(limits.minInterval()).toString()))));
    }

    /** Whether every region of a notice is among its publication's (any, when the publication is aimed at all). */
    public static boolean within(List<String> regions, String publicationRegions) {
        List<String> theirs = Regions.parse(publicationRegions);
        return theirs.contains(Regions.GLOBAL) || theirs.containsAll(regions);
    }

    private static List<Map.Entry<String, ?>> texts(Draft draft) {
        List<Map.Entry<String, ?>> texts = new ArrayList<>();
        if (draft.title() != null) {
            draft.title().values().forEach(value -> texts.add(Map.entry("title", value == null ? "" : value)));
        }
        if (draft.body() != null) {
            draft.body().values().forEach(value -> texts.add(Map.entry("body", value == null ? "" : value)));
        }
        return texts;
    }

    /** Whether some language has a text that is not blank. */
    public static boolean hasText(Map<String, ?> text) {
        return text != null && text.values().stream().anyMatch(value -> !blank(value));
    }

    private static boolean blank(Object value) {
        return value == null || String.valueOf(value).isBlank();
    }

    /** The texts without blank languages, in a stable order. */
    public static Map<String, String> clean(Map<String, ?> text) {
        Map<String, String> clean = new LinkedHashMap<>();
        if (text != null) {
            new java.util.TreeMap<String, Object>(text).forEach((language, value) -> {
                if (!blank(value)) {
                    clean.put(language, String.valueOf(value).strip());
                }
            });
        }
        return clean;
    }

    /**
     * A sponsor's count as their statistics show it: none below {@link #MIN_GROUP} (shown as "fewer than"), so that a
     * notice to a small region does not tell who of its few takers read it.
     */
    public static Long shown(long count) {
        return count < MIN_GROUP ? null : count;
    }

    static Violation violation(String field, String code, String message, Map<String, ?> params) {
        return new Violation(field, code, message, new LinkedHashMap<String, Object>(params));
    }

    private BroadcastRules() {}
}
