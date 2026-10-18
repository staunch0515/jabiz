package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.messaging.MessageProcesses;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.quizbuks.setup.QbRoles;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The taker's inbox (docs/quizbuks/plans/Q7-messages-legal.md, Q7c, D-Q7-7 – D-Q7-9; U-60 – U-63): personal messages
 * from each event and from the reminder job (once each, in every language, the replayed and the stale ones making
 * none), the matching of announcements (regions, no country, named users, promotions to those registered before,
 * promotions turned off, expiry, withdrawal), unread counts and "all read", the marks (only one's own items, the first
 * time only), the search and dates, and the limit on marks.
 */
class InboxIT extends InboxItSupport {

    // ---- messages from events --------------------------------------------------------------------------------------

    @Test
    void aPaidRewardMakesOneMessageInEveryLanguageLeadingToTheResult() {
        String sponsor = newSponsor(unique("s-inbox-paid"));
        credit(sponsor, 1_000);
        String publicationId = livePublication(sponsor, "Capitals", Map.of("regions", List.of("GLOBAL")));
        String taker = newTaker(unique("t-inbox-paid"), "JP");
        Map<String, Object> submitted = play(taker, publicationId, true, true);
        assertThat(messagesOf(taker)).isEmpty();
        deliverEvents();

        assertThat(messagesOf(taker)).singleElement().satisfies(row -> {
            assertThat(row).containsEntry("kind", "REWARD_PAID").containsEntry("type", "REWARD")
                .containsEntry("action", "RESULT").containsEntry("action_id", submitted.get("attemptId"));
            assertThat(String.valueOf(row.get("title"))).contains("\"en\": \"You received 100 Kudos\"",
                "100 Kudos を受け取りました", "你收到了 100 Kudos");
            assertThat(String.valueOf(row.get("body"))).contains("Capitals");
        });
        // In the app's inbox, unread, with its publication.
        assertThat(inbox(taker(taker))).filteredOn(item -> "MESSAGE".equals(item.get("itemKind"))).singleElement()
            .satisfies(item -> assertThat(item).containsEntry("category", "REWARD").containsEntry("unread", true)
                .containsEntry("publicationId", publicationId).containsEntry("kind", "REWARD_PAID"));

        // The same event again (a redelivery, a replay) makes no second message.
        Object again = systemRun(MessageProcesses.FROM_EVENT, body("kind", "REWARD_PAID", "sourceId",
            submitted.get("attemptId"), "publicationId", publicationId, "occurredAt", clock.instant().toString()));
        assertThat(((MessageProcesses.MessageOutput) again).messageId()).isNull();
        assertThat(messagesOf(taker)).hasSize(1);
    }

    @Test
    void aPendingRewardSaysSoAndItsLaterPaymentMakesItsOwnMessage() {
        String sponsor = newSponsor(unique("s-inbox-pending"));
        credit(sponsor, 100);
        String publicationId = livePublication(sponsor, "Pending", Map.of("regions", List.of("GLOBAL")));
        String first = newTaker(unique("t-inbox-p1"), "JP");
        String second = newTaker(unique("t-inbox-p2"), "JP");
        String a = (String) start(taker(first), publicationId).get("attemptId");
        String b = (String) start(taker(second), publicationId).get("attemptId");
        clock.advance(Duration.ofSeconds(1));
        submit(taker(first), a, answers(true, true));
        clock.advance(Duration.ofSeconds(1));
        submit(taker(second), b, answers(true, true));
        deliverEvents();
        assertThat(kindsOf(first)).containsExactly("REWARD_PAID");
        assertThat(kindsOf(second)).containsExactly("REWARD_PENDING");
        assertThat(String.valueOf(messagesOf(second).getFirst().get("title"))).contains("100 Kudos");

        credit(sponsor, 1_000);
        assertThat(kindsOf(second)).containsExactlyInAnyOrder("REWARD_PENDING", "REWARD_PAID");
        // A pending event that comes after the payment (a backlog) says nothing that no longer holds.
        Object late = systemRun(MessageProcesses.FROM_EVENT, body("kind", "REWARD_PENDING", "sourceId", b,
            "publicationId", publicationId, "occurredAt", clock.instant().toString()));
        assertThat(((MessageProcesses.MessageOutput) late).messageId()).isNull();
    }

    @Test
    void oldEventsMakeNoMessageAsANewSubscriptionsReplayWouldHandThem() {
        String sponsor = newSponsor(unique("s-inbox-old"));
        credit(sponsor, 1_000);
        String publicationId = livePublication(sponsor, "Old news", Map.of("regions", List.of("GLOBAL")));
        String taker = newTaker(unique("t-inbox-old"), "JP");
        play(taker, publicationId, true, true);
        // Delivered only after more than a week (a subscription new since, a long backlog): nothing.
        clock.advance(MessageProcesses.MAX_AGE.plusMinutes(1));
        deliverEvents();
        assertThat(messagesOf(taker)).isEmpty();
        // Within the week it would have.
        Object fresh = systemRun(MessageProcesses.FROM_EVENT, body("kind", "REWARD_PAID", "sourceId",
            query("SELECT attempt_id FROM qb_attempt WHERE user_id = ?", taker).getFirst().get("attempt_id"),
            "publicationId", publicationId, "occurredAt", clock.instant().minus(Duration.ofDays(6)).toString()));
        assertThat(((MessageProcesses.MessageOutput) fresh).messageId()).isNotNull();
    }

    @Test
    void transfersAndAHeldBackPayoutAccountMakeTheirMessages() {
        param(QbParams.TRANSFER_FEE, "0");
        param(QbParams.TRANSFER_REVIEW_ABOVE, "50000");
        transfersOn();
        String taker = payee(unique("t-inbox-transfer"));
        String accountId = enabledPayee(taker);
        earn(taker, 1500);
        runTransfers();
        runJobs();
        assertThat(kindsOf(taker)).containsExactly("TRANSFER_SENT");
        assertThat(String.valueOf(messagesOf(taker).getFirst().get("title"))).contains("1,500 Kudos");
        assertThat(messagesOf(taker).getFirst()).containsEntry("action", "WALLET").containsEntry("type", "SYSTEM");

        String other = payee(unique("t-inbox-failed"));
        enabledPayee(other);
        earn(other, 2000);
        runTransfers();
        String transferId = (String) transfersOf(other).getLast().get("transfer_id");
        StripeSim.target = "qb-" + transferId;
        StripeSim.REFUSE.set(1);
        runJobs();
        assertThat(kindsOf(other)).containsExactly("TRANSFER_FAILED");
        assertThat(String.valueOf(messagesOf(other).getFirst().get("body")))
            .contains("Stripe declined the transfer to your payout account");

        advance(Duration.ofSeconds(5));
        deliverConnect(connectEvent("account.updated", accountId, account(accountId, taker, false)));
        assertThat(kindsOf(taker)).containsExactly("TRANSFER_SENT", "PAYOUT_ACTION_NEEDED");
        // Held back again the same day: one message a day.
        advance(Duration.ofSeconds(5));
        deliverConnect(connectEvent("account.updated", accountId, account(accountId, taker, true)));
        advance(Duration.ofSeconds(5));
        deliverConnect(connectEvent("account.updated", accountId, account(accountId, taker, false)));
        assertThat(kindsOf(taker)).containsExactly("TRANSFER_SENT", "PAYOUT_ACTION_NEEDED");
    }

    @Test
    void theDailyJobRemindsATakerWithoutAPayoutAccountOnceInThirtyDays() {
        String taker = payee(unique("t-inbox-remind"));
        String rich = payee(unique("t-inbox-rich"));
        String poor = payee(unique("t-inbox-poor"));
        earn(taker, 1_200);
        earn(rich, 5_000);
        enabledPayee(rich);
        earn(poor, 999);
        runReminders();
        assertThat(kindsOf(taker)).containsExactly("PAYOUT_REMINDER");
        assertThat(messagesOf(taker).getFirst()).containsEntry("type", "REMINDER").containsEntry("action", "PAYOUT");
        assertThat(String.valueOf(messagesOf(taker).getFirst().get("body"))).contains("1,200 Kudos", "1,000 Kudos");
        // An enabled account, a balance below the threshold: none.
        assertThat(kindsOf(rich)).isEmpty();
        assertThat(kindsOf(poor)).isEmpty();
        // Not again within 30 days; again after.
        clock.advance(Duration.ofDays(29));
        runReminders();
        assertThat(kindsOf(taker)).hasSize(1);
        clock.advance(Duration.ofDays(2));
        runReminders();
        assertThat(kindsOf(taker)).containsExactly("PAYOUT_REMINDER", "PAYOUT_REMINDER");
        // Run as the item of its job only: nobody starts it.
        refusedAsInternal(MessageProcesses.PAYOUT_REMINDER, admin(), body("userId", taker));
    }

    // ---- matching --------------------------------------------------------------------------------------------------

    @Test
    void announcementsMatchByRegionCountryAndName() {
        String japan = newTaker(unique("t-match-jp"), "JP");
        String usa = newTaker(unique("t-match-us"), "US");
        String none = user(unique("t-match-none"));
        String named = newTaker(unique("t-match-named"), "US");
        String toJapan = announce(announcement("To Japan", "targetMode", "REGIONS", "regions", List.of("JP")));
        String toAsia = announce(announcement("To Asia", "targetMode", "REGIONS", "regions", List.of("ASIA")));
        String toGlobal = announce(announcement("To the globe", "targetMode", "REGIONS", "regions",
            List.of("GLOBAL")));
        String toAll = announce(announcement("To everyone"));
        String toNamed = announce(announcement("To one", "targetMode", "USERS", "userIds", List.of(named)));

        assertThat(itemIds(inbox(taker(japan)))).contains(toJapan, toAsia, toGlobal, toAll).doesNotContain(toNamed);
        assertThat(itemIds(inbox(taker(usa)))).contains(toGlobal, toAll).doesNotContain(toJapan, toAsia, toNamed);
        // Without a country: everyone's and the globe's only.
        assertThat(itemIds(inbox(taker(none)))).contains(toGlobal, toAll).doesNotContain(toJapan, toAsia, toNamed);
        assertThat(itemIds(inbox(taker(named)))).contains(toNamed, toAll, toGlobal);
        // The page cannot choose another country: the profile's is used, and there is no such parameter.
        client.post().uri("/api/queries/qb.app.inbox").contentType(org.springframework.http.MediaType.APPLICATION_JSON)
            .header(org.springframework.http.HttpHeaders.AUTHORIZATION, taker(usa))
            .bodyValue(Map.of("params", Map.of("at", clock.instant().toString(), "country", "JP"))).exchange()
            .expectStatus().isBadRequest();
        // The sponsor console does not show the app's announcements.
        assertThat(itemIds(consoleInbox(console(sponsorA())))).doesNotContain(toAll);
        // audienceCount: everyone with a profile in a region aimed at, named users by their list.
        assertThat(((Number) adminRow(toNamed).get("audienceCount")).longValue()).isEqualTo(1);
        long japanese = ((Number) query("SELECT count(*) AS n FROM (SELECT DISTINCT ON (profile_id) * FROM "
            + "qb_profile_version ORDER BY profile_id, effect_start_time DESC, version_no DESC) p "
            + "WHERE country_code = 'JP'").getFirst().get("n")).longValue();
        assertThat(((Number) adminRow(toJapan).get("audienceCount")).longValue()).isEqualTo(japanese);
    }

    @Test
    void promotionsReachOnlyThoseRegisteredBeforeAndNotThoseWhoTurnedThemOff() {
        String before = newTaker(unique("t-promo-before"), "JP");
        String off = newTaker(unique("t-promo-off"), "JP");
        run("QB_INBOX_SETTINGS_SAVE", taker(off), body("promoOff", true));
        clock.advance(Duration.ofMinutes(1));
        String promo = announce(announcement("A promotion", "type", "PROMO"));
        String system = announce(announcement("A system notice"));
        clock.advance(Duration.ofMinutes(1));
        String after = newTaker(unique("t-promo-after"), "JP");

        assertThat(itemIds(inbox(taker(before)))).contains(promo, system);
        assertThat(inbox(taker(before))).filteredOn(item -> promo.equals(item.get("itemId"))).singleElement()
            .satisfies(item -> assertThat(item).containsEntry("category", "PROMO"));
        // Registered after it was sent: the system one only.
        assertThat(itemIds(inbox(taker(after)))).contains(system).doesNotContain(promo);
        // Promotions off: promotions only; on again, back.
        assertThat(itemIds(inbox(taker(off)))).contains(system).doesNotContain(promo);
        Map<String, Object> state = run("QB_INBOX_SETTINGS_SAVE", taker(off), body("promoOff", false));
        assertThat(state).containsEntry("promoOff", false);
        assertThat(itemIds(inbox(taker(off)))).contains(system, promo);
        // Only promotions can be turned off: system messages and rewards have no switch (the only setting).
        assertThat(rows("urn:jabiz:dataset:me:QbInboxState", taker(off))).singleElement()
            .satisfies(row -> assertThat(row).containsEntry("promoOff", false));
    }

    @Test
    void announcementsExpireAndWithdrawnOnesLeaveEveryInbox() {
        String taker = newTaker(unique("t-expiry"), "JP");
        String shortLived = announce(announcement("Short-lived", "expiresAt",
            clock.instant().plus(Duration.ofHours(2)).toString()));
        String withdrawn = announce(announcement("To be withdrawn"));
        String defaultLife = announce(announcement("Default life"));
        assertThat(itemIds(inbox(taker(taker)))).contains(shortLived, withdrawn, defaultLife);
        assertThat(broadcast(defaultLife).get("expires_at")).isNotNull();
        assertThat(instant(broadcast(defaultLife).get("expires_at")))
            .isEqualTo(instant(broadcast(defaultLife).get("sent_at")).plus(Duration.ofDays(30)));

        run("QB_ADMIN_BROADCAST_WITHDRAW", contentAdmin(), body("broadcastId", withdrawn, "reason", "Mistake"));
        assertThat(itemIds(inbox(taker(taker)))).contains(shortLived).doesNotContain(withdrawn);
        clock.advance(Duration.ofHours(3));
        assertThat(itemIds(inbox(taker(taker)))).contains(defaultLife).doesNotContain(shortLived, withdrawn);
        // An item that left the inbox is not marked: 404.
        refused("QB_MESSAGE_MARK", taker(taker), body("kind", "BROADCAST", "id", withdrawn, "action", "READ"), 404);
        clock.advance(Duration.ofDays(31));
        assertThat(itemIds(inbox(taker(taker)))).doesNotContain(defaultLife);
    }

    // ---- unread, marks and search ----------------------------------------------------------------------------------

    @Test
    void unreadCountsFallWithReadingAndWithAllRead() {
        String taker = newTaker(unique("t-unread"), "JP");
        run("QB_INBOX_READ_ALL", taker(taker), body());
        clock.advance(Duration.ofSeconds(1));
        assertThat(count(unread(taker(taker)), "messages")).isZero();
        assertThat(count(unread(taker(taker)), "broadcasts")).isZero();
        String first = announce(announcement("Unread one", "targetMode", "USERS", "userIds", List.of(taker)));
        String second = announce(announcement("Unread two", "targetMode", "USERS", "userIds", List.of(taker)));
        assertThat(count(unread(taker(taker)), "broadcasts")).isEqualTo(2);

        assertThat(mark(taker(taker), "BROADCAST", first, "READ")).containsEntry("changed", true);
        assertThat(count(unread(taker(taker)), "broadcasts")).isEqualTo(1);
        // The first reading only: again, nothing changes.
        Object readAt = query("SELECT read_at FROM qb_broadcast_receipt WHERE broadcast_id = ?::uuid", first)
            .getFirst().get("read_at");
        clock.advance(Duration.ofMinutes(1));
        assertThat(mark(taker(taker), "BROADCAST", first, "READ")).containsEntry("changed", false);
        assertThat(query("SELECT read_at FROM qb_broadcast_receipt WHERE broadcast_id = ?::uuid", first).getFirst()
            .get("read_at")).isEqualTo(readAt);

        // "All read": nothing unread, and no receipt (it is no reading).
        clock.advance(Duration.ofSeconds(1));
        run("QB_INBOX_READ_ALL", taker(taker), body());
        assertThat(count(unread(taker(taker)), "broadcasts")).isZero();
        assertThat(query("SELECT 1 FROM qb_broadcast_receipt WHERE broadcast_id = ?::uuid", second)).isEmpty();
        assertThat(inbox(taker(taker), "unread", true)).isEmpty();
        clock.advance(Duration.ofSeconds(1));
        String third = announce(announcement("Unread three", "targetMode", "USERS", "userIds", List.of(taker)));
        assertThat(itemIds(inbox(taker(taker), "unread", true))).containsExactly(third);
    }

    @Test
    void onlyOnesOwnItemsAreMarkedAndAClickCountsOnce() {
        String sponsor = newSponsor(unique("s-inbox-mark"));
        credit(sponsor, 1_000);
        String publicationId = livePublication(sponsor, "Marks", Map.of("regions", List.of("GLOBAL")));
        String owner = newTaker(unique("t-mark-owner"), "JP");
        String stranger = newTaker(unique("t-mark-stranger"), "JP");
        play(owner, publicationId, true, true);
        deliverEvents();
        String messageId = (String) messagesOf(owner).getFirst().get("message_id");
        String named = announce(announcement("For the owner", "targetMode", "USERS", "userIds", List.of(owner)));

        // Another's message, an announcement for another: as if there were none.
        refused("QB_MESSAGE_MARK", taker(stranger), body("kind", "MESSAGE", "id", messageId, "action", "READ"), 404);
        refused("QB_MESSAGE_MARK", taker(stranger), body("kind", "BROADCAST", "id", named, "action", "READ"), 404);
        assertThat(messagesOf(owner).getFirst().get("read_at")).isNull();

        mark(taker(owner), "MESSAGE", messageId, "CLICK");
        Map<String, Object> row = messagesOf(owner).getFirst();
        assertThat(row.get("read_at")).isNotNull();
        assertThat(row.get("clicked_at")).isNotNull();
        clock.advance(Duration.ofMinutes(1));
        assertThat(mark(taker(owner), "MESSAGE", messageId, "CLICK")).containsEntry("changed", false);
        assertThat(messagesOf(owner).getFirst().get("clicked_at")).isEqualTo(row.get("clicked_at"));
        // Unverified, banned or anonymous callers mark nothing.
        call("QB_MESSAGE_MARK", unverifiedTokenOf(owner, QbRoles.TAKER), body("kind", "MESSAGE", "id", messageId,
            "action", "READ")).expectStatus().isForbidden();
        // A broadcast without a quiz is only read by a click.
        mark(taker(owner), "BROADCAST", named, "CLICK");
        assertThat(query("SELECT read_at, clicked_at FROM qb_broadcast_receipt WHERE broadcast_id = ?::uuid", named))
            .singleElement().satisfies(r -> {
                assertThat(r.get("read_at")).isNotNull();
                assertThat(r.get("clicked_at")).isNull();
            });
    }

    @Test
    void theInboxIsSearchedByTextInTheLanguageOrEnglishAndByDateAndKind() {
        String taker = newTaker(unique("t-search"), "JP");
        Instant start = clock.instant();
        String apples = announce(announcement("Apples are ripe", "targetMode", "USERS", "userIds", List.of(taker)));
        clock.advance(Duration.ofHours(2));
        String pears = announce(announcement("Pears are sweet", "type", "PROMO", "targetMode", "USERS", "userIds",
            List.of(taker)));

        assertThat(itemIds(inbox(taker(taker), "q", "apples"))).containsExactly(apples);
        assertThat(itemIds(inbox(taker(taker), "q", "（日本語）", "lang", "ja"))).contains(apples, pears);
        assertThat(itemIds(inbox(taker(taker), "q", "%"))).isEmpty();
        assertThat(itemIds(inbox(taker(taker), "from", start.plus(Duration.ofHours(1)).toString())))
            .contains(pears).doesNotContain(apples);
        assertThat(itemIds(inbox(taker(taker), "to", start.plus(Duration.ofHours(1)).toString())))
            .contains(apples).doesNotContain(pears);
        assertThat(itemIds(inbox(taker(taker), "category", "PROMO"))).contains(pears).doesNotContain(apples);
        assertThat(itemIds(inbox(taker(taker), "itemKind", "BROADCAST", "itemId", apples))).containsExactly(apples);
    }

    @Test
    void marksAreLimitedPerUser() {
        String taker = newTaker(unique("t-mark-limit"), "JP");
        String one = announce(announcement("Limited", "targetMode", "USERS", "userIds", List.of(taker)));
        int refusedAt = -1;
        for (int i = 0; i < 200; i++) {
            int status = call("QB_MESSAGE_MARK", taker(taker), body("kind", "BROADCAST", "id", one, "action",
                "READ")).expectBody(MAP).returnResult().getStatus().value();
            if (status == 429) {
                refusedAt = i;
                break;
            }
            assertThat(status).isEqualTo(200);
        }
        // 120 a minute; the bucket refills meanwhile (real time), a little.
        assertThat(refusedAt).isBetween(120, 140);
    }

    @Test
    void sponsorsReadThePlatformsAnnouncementsToThemInTheirConsole() {
        String sponsor = newSponsor(unique("s-console"));
        String other = newSponsor(unique("s-console-other"));
        String toSponsors = announce(announcement("To sponsors", "audience", "SPONSOR"));
        String toOne = announce(announcement("To one sponsor", "audience", "SPONSOR", "targetMode", "USERS",
            "userIds", List.of(sponsor)));
        assertThat(itemIds(consoleInbox(console(sponsor)))).contains(toSponsors, toOne);
        assertThat(itemIds(consoleInbox(console(other)))).contains(toSponsors).doesNotContain(toOne);
        // Not in the app.
        String taker = newTaker(unique("t-console"), "JP");
        assertThat(itemIds(inbox(taker(taker)))).doesNotContain(toSponsors);
        // Read in the console (its entry), found in the console's inbox only.
        mark(atEntry(sponsor, QbRoles.SPONSOR, "sponsor"), "BROADCAST", toOne, "READ");
        assertThat(consoleInbox(console(sponsor))).filteredOn(item -> toOne.equals(item.get("itemId")))
            .singleElement().satisfies(item -> assertThat(item).containsEntry("unread", false));
        refused("QB_MESSAGE_MARK", atEntry(taker, QbRoles.TAKER, "app"), body("kind", "BROADCAST", "id", toSponsors,
            "action", "READ"), 404);
        // Regions are no target of the sponsor console.
        Map<String, Object> saved = adminSave(announcement("By region", "audience", "SPONSOR", "targetMode",
            "REGIONS", "regions", List.of("JP")));
        assertThat(problemCodes(saved)).contains("QB_BROADCAST_TARGET_MODE");
    }
}
