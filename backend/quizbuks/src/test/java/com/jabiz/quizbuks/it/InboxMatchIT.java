package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.country.Regions;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The matching of D-Q7-9 (Q7-9, Q7-11) as a table of cases: {@link #visible} says in plain Java who sees an
 * announcement, and the three templates that write it in SQL must agree for every reader and announcement of the
 * table: the inbox ({@code qb.app.inbox}), its unread count ({@code qb.app.inbox-unread}) and the audience counted
 * when it is sent ({@code qb.system.broadcast-audience}, over every profile of the schema). A class of its own: only
 * its own announcements are in the inboxes.
 */
class InboxMatchIT extends InboxItSupport {

    /** A reader as the matching sees them. */
    record Reader(String name, String userId, String regions, Instant registeredAt, boolean promoOff,
        boolean complete) {}

    /** An announcement as the feed has it. */
    record Feed(String id, String type, String targetMode, String regions, List<String> named, Instant sentAt,
        Instant expiresAt, boolean live) {}

    /**
     * Whether {@code reader} sees {@code feed} now: the rules of D-Q7-9. Its time is the feed's: a row is live from
     * its sending until it is withdrawn or the minute's job finds it expired (review H1, L1).
     */
    static boolean visible(Feed feed, Reader reader) {
        if (!feed.live()) {
            return false;
        }
        boolean aimed = switch (feed.targetMode()) {
            case "ALL" -> true;
            case "REGIONS" -> Regions.overlaps(reader.regions() == null ? Regions.GLOBAL : reader.regions(),
                feed.regions());
            default -> feed.named().contains(reader.userId());
        };
        return aimed && (!"PROMO".equals(feed.type()) || !reader.promoOff() && reader.registeredAt() != null
            && !reader.registeredAt().isAfter(feed.sentAt()));
    }

    /**
     * Whether the audience counted at the sending includes the reader: named ones, else those with a country by then
     * (the profiles there were when it was sent).
     */
    static boolean counted(Feed feed, Reader reader) {
        if ("USERS".equals(feed.targetMode())) {
            return feed.named().contains(reader.userId());
        }
        return reader.complete() && !reader.registeredAt().isAfter(feed.sentAt()) && visible(new Feed(feed.id(), feed.type(), feed.targetMode(), feed.regions(),
            feed.named(), feed.sentAt(), feed.expiresAt(), true), reader);
    }

    private Reader reader(String name, String userId) {
        Map<String, Object> profile = profileOf(userId);
        String country = profile == null ? null : (String) profile.get("country_code");
        String regions = country == null ? null : (String) latest("qb_country_version", "country_id",
            "code = '" + country + "'").getFirst().get("regions");
        List<Map<String, Object>> states = query("SELECT promo_off FROM qb_inbox_state WHERE user_id = ?", userId);
        return new Reader(name, userId, regions, profile == null ? null : instant(profile.get("created_at")),
            !states.isEmpty() && Boolean.TRUE.equals(states.getFirst().get("promo_off")), country != null);
    }

    private Feed feed(String id, List<String> named) {
        Map<String, Object> row = query("SELECT * FROM qb_broadcast_feed WHERE broadcast_id = ?::uuid", id)
            .getFirst();
        return new Feed(id, (String) row.get("type"), (String) row.get("target_mode"), (String) row.get("regions"),
            named, instant(row.get("sent_at")), instant(row.get("expires_at")), Boolean.TRUE.equals(row.get("live")));
    }

    @Test
    void theTemplatesAgreeWithTheTableOfCases() {
        String jp = newTaker(unique("m-jp"), "JP");
        String us = newTaker(unique("m-us"), "US");
        String fr = newTaker(unique("m-fr"), "FR");
        String none = user(unique("m-none"));
        String off = newTaker(unique("m-off"), "JP");
        run("QB_INBOX_SETTINGS_SAVE", taker(off), body("promoOff", true));
        clock.advance(Duration.ofMinutes(1));

        Map<String, List<String>> sent = new LinkedHashMap<>();
        sent.put(announce(announcement("All system")), List.of());
        sent.put(announce(announcement("Japan", "targetMode", "REGIONS", "regions", List.of("JP"))), List.of());
        sent.put(announce(announcement("Globe promo", "type", "PROMO", "targetMode", "REGIONS", "regions",
            List.of("GLOBAL"))), List.of());
        sent.put(announce(announcement("Named notice", "type", "NOTICE", "targetMode", "USERS", "userIds",
            List.of(us, none))), List.of(us, none));
        sent.put(announce(announcement("All promo", "type", "PROMO")), List.of());
        sent.put(announce(announcement("Asia and Europe", "type", "NOTICE", "targetMode", "REGIONS", "regions",
            List.of("EUROPE", "ASIA"))), List.of());
        sent.put(announce(announcement("Named promo", "type", "PROMO", "targetMode", "USERS", "userIds",
            List.of(off, jp))), List.of(off, jp));
        String expiring = announce(announcement("Expiring", "expiresAt",
            clock.instant().plus(Duration.ofMinutes(30)).toString()));
        sent.put(expiring, List.of());
        String withdrawn = announce(announcement("Withdrawn"));
        run("QB_ADMIN_BROADCAST_WITHDRAW", contentAdmin(), body("broadcastId", withdrawn, "reason", "Test"));
        sent.put(withdrawn, List.of());
        clock.advance(Duration.ofMinutes(1));
        String late = newTaker(unique("m-late"), "JP");

        List<Reader> readers = new ArrayList<>();
        for (Map.Entry<String, String> named : Map.of("jp", jp, "us", us, "fr", fr, "none", none, "off", off,
            "late", late).entrySet()) {
            readers.add(reader(named.getKey(), named.getValue()));
        }
        for (Instant at : List.of(clock.instant(), clock.instant().plus(Duration.ofHours(1)))) {
            clock.set(at);
            runExpire();
            for (Reader reader : readers) {
                List<Object> shown = itemIds(inbox(taker(reader.userId())));
                long expectedCount = 0;
                for (Map.Entry<String, List<String>> entry : sent.entrySet()) {
                    Feed feed = feed(entry.getKey(), entry.getValue());
                    boolean expected = visible(feed, reader);
                    assertThat(shown.contains(entry.getKey())).as(reader.name() + " sees " + entry.getKey() + " at "
                        + at).isEqualTo(expected);
                    expectedCount += expected ? 1 : 0;
                }
                assertThat(count(unread(taker(reader.userId())), "broadcasts")).as(reader.name() + " unread at " + at)
                    .isEqualTo(expectedCount);
            }
        }

        // The audience counted at the sending, over every profile of the schema.
        List<Reader> everyone = new ArrayList<>();
        for (Map<String, Object> row : latest("qb_profile_version", "profile_id", "NOT is_deleted")) {
            everyone.add(reader(String.valueOf(row.get("user_id")), String.valueOf(row.get("user_id"))));
        }
        for (Map.Entry<String, List<String>> entry : sent.entrySet()) {
            Feed feed = feed(entry.getKey(), entry.getValue());
            long expected = everyone.stream().filter(reader -> counted(feed, reader)).count();
            if ("USERS".equals(feed.targetMode())) {
                expected = feed.named().size();
            }
            assertThat(((Number) broadcast(entry.getKey()).get("audience_count")).longValue())
                .as("audience of " + entry.getKey()).isEqualTo(expected);
        }
        // The expiring one left the feed with the job, an hour on.
        assertThat(feed(expiring, List.of()).live()).isFalse();
    }
}
