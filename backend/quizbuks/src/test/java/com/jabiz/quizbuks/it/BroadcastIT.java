package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.messaging.BroadcastProcesses;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.quizbuks.setup.QbRoles;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sponsors' notices and the platform's announcements (docs/quizbuks/plans/Q7-messages-legal.md, Q7c; M-60, M-61, P-04;
 * Q7-9, Q7-10, Q7-14): who may write and send them, a sponsor's limits (their own scheduled or live publication, its
 * regions, no addresses, the number and the interval as parameters), scheduling by the minute's job and its
 * cancellation when the publication is gone, withdrawal by the sponsor and by an administrator (a reason and a second
 * factor), a ban withdrawing every notice, and the statistics (exact for the administrators, at least five for the
 * sponsor). The broadcasts' versions are only ever inserted into.
 */
class BroadcastIT extends InboxItSupport {

    /** A sponsor with a live publication aimed at Japan and Asia, its wallet able to pay many rewards. */
    private record Live(String sponsor, String publicationId) {}

    private Live live(String prefix) {
        String sponsor = newSponsor(unique(prefix));
        credit(sponsor, 20_000);
        return new Live(sponsor, livePublication(sponsor, "Notices " + prefix));
    }

    // ---- who ------------------------------------------------------------------------------------------------------

    @Test
    void onlySponsorsWriteNoticesAndOnlyContentAdministratorsAnnouncements() {
        Live live = live("s-who");
        Map<String, Object> notice = body("title", body("en", "Hi"), "body", body("en", "Play"), "publicationId",
            live.publicationId(), "regions", List.of("JP"));
        String taker = newTaker(unique("t-who"), "JP");
        call("QB_BROADCAST_SAVE", taker(taker), notice).expectStatus().isForbidden();
        call("QB_BROADCAST_SAVE", contentAdmin(), notice).expectStatus().isForbidden();
        call("QB_ADMIN_BROADCAST_SAVE", console(live.sponsor()), announcement("Mine")).expectStatus()
            .isForbidden();
        call("QB_ADMIN_BROADCAST_SAVE", finance(), announcement("Finance")).expectStatus().isForbidden();
        // A sponsor not yet approved.
        String applicant = user(unique("s-who-new"));
        assertThat(refusalCode(refused("QB_BROADCAST_SAVE", console(applicant), notice, 422)))
            .isEqualTo("QB_SPONSOR_NOT_APPROVED");
        // Sending an announcement to everyone asks for a second factor.
        Map<String, Object> saved = adminSave(announcement("Second factor"));
        call("QB_ADMIN_BROADCAST_SEND", TestTokens.withoutMfa(tokens, "content-admin-q7c",
            QbRoles.of(QbRoles.ADMIN_CONTENT).permissions().toArray(String[]::new)), body("broadcastId",
            saved.get("broadcastId"), "baseRevision", saved.get("revision"))).expectStatus().isForbidden();

        // A sponsor's notices are their own: another sponsor neither reads nor sends nor edits them.
        Map<String, Object> draft = noticeDraft(live.sponsor(), "title", body("en", "Mine"), "body",
            body("en", "Play"), "publicationId", live.publicationId(), "regions", List.of("JP"));
        String other = newSponsor(unique("s-who-other"));
        refused("QB_BROADCAST_SEND", console(other), body("broadcastId", draft.get("broadcastId"), "baseRevision",
            draft.get("revision")), 404);
        refused("QB_BROADCAST_SAVE", console(other), body("broadcastId", draft.get("broadcastId"), "baseRevision",
            draft.get("revision"), "title", body("en", "Taken")), 404);
        // Nor withdraws or cancels them, sent or not.
        refused("QB_BROADCAST_CANCEL", console(other), body("broadcastId", draft.get("broadcastId"),
            "baseRevision", draft.get("revision")), 404);
        String sentOne = notice(live.sponsor(), live.publicationId(), List.of("JP"));
        refused("QB_BROADCAST_WITHDRAW", console(other), body("broadcastId", sentOne, "baseRevision",
            ((Number) broadcast(sentOne).get("revision")).longValue()), 404);
        assertThat(broadcast(sentOne)).containsEntry("status", "SENT");
        assertThat(items("qb.sponsor.broadcasts", console(other), Map.of())).extracting(row -> row.get("broadcastId"))
            .doesNotContain(draft.get("broadcastId"));
        // Nor do the administrators write a sponsor's notice (they withdraw it).
        refused("QB_ADMIN_BROADCAST_SAVE", contentAdmin(), announcement("Over", "broadcastId",
            draft.get("broadcastId"), "baseRevision", draft.get("revision")), 404);
        // A notice names only one's own publication.
        Live theirs = live("s-who-theirs");
        assertThat(refusalCode(refused("QB_BROADCAST_SAVE", console(live.sponsor()), body("title", body("en", "X"),
            "body", body("en", "Y"), "publicationId", theirs.publicationId(), "regions", List.of("JP")), 422)))
            .isEqualTo("QB_BROADCAST_PUBLICATION_NOT_ON");
        // The system's processes are nobody's.
        refusedAsInternal(BroadcastProcesses.DISPATCH, admin(), body("broadcastId", draft.get("broadcastId")));
        assertThat(String.valueOf(refused(BroadcastProcesses.BAN_WITHDRAW, admin(), body("sponsorId",
            live.sponsor()), 403).get("violations"))).contains("PROCESS_INTERNAL");
    }

    // ---- a sponsor's limits ----------------------------------------------------------------------------------------

    @Test
    void aSponsorsNoticeIsPlainTextInThePublicationsRegionsBeforeItsEnd() {
        Live live = live("s-rules");
        Map<String, Object> saved = noticeDraft(live.sponsor(), "title", body("en", "Visit www.example.com"), "body",
            body("ja", "詳しくは info@example.jp まで"), "publicationId", live.publicationId(), "regions",
            List.of("US"), "scheduledAt", clock.instant().plus(Duration.ofDays(8)).toString());
        assertThat(problemCodes(saved)).contains("QB_BROADCAST_LINK", "QB_BROADCAST_REGIONS_OUTSIDE",
            "QB_BROADCAST_AFTER_END");
        // Sending refuses with the same problems, all of them.
        Map<String, Object> problem = refused("QB_BROADCAST_SEND", console(live.sponsor()), body("broadcastId",
            saved.get("broadcastId"), "baseRevision", saved.get("revision")), 422);
        assertThat(codes(problem)).contains("QB_BROADCAST_LINK", "QB_BROADCAST_REGIONS_OUTSIDE",
            "QB_BROADCAST_AFTER_END");
        // Fixed, it goes; its expiry is the publication's end.
        Map<String, Object> fixed = noticeDraft(live.sponsor(), "broadcastId", saved.get("broadcastId"),
            "baseRevision", saved.get("revision"), "title", body("en", "New quiz"), "body", body("ja", "遊んでね"),
            "publicationId", live.publicationId(), "regions", List.of("JP"));
        assertThat(problemCodes(fixed)).isEmpty();
        assertThat(Instant.parse((String) fixed.get("expiresAt"))).isEqualTo(instant(card(live.publicationId()).get("end_at")));
        Map<String, Object> sent = run("QB_BROADCAST_SEND", console(live.sponsor()), body("broadcastId",
            fixed.get("broadcastId"), "baseRevision", fixed.get("revision")));
        assertThat(sent).containsEntry("status", "SENT");
        // A sent notice is not edited again.
        assertThat(refusalCode(refused("QB_BROADCAST_SAVE", console(live.sponsor()), body("broadcastId",
            fixed.get("broadcastId"), "baseRevision", sent.get("revision"), "title", body("en", "Again")), 422)))
            .isEqualTo("QB_BROADCAST_NOT_DRAFT");
        // An old revision is refused.
        Map<String, Object> draft = noticeDraft(live.sponsor(), "title", body("en", "A"), "body", body("en", "B"),
            "publicationId", live.publicationId(), "regions", List.of("JP"));
        noticeDraft(live.sponsor(), "broadcastId", draft.get("broadcastId"), "baseRevision", draft.get("revision"),
            "title", body("en", "A2"), "body", body("en", "B"), "publicationId", live.publicationId(), "regions",
            List.of("JP"));
        assertThat(refusalCode(refused("QB_BROADCAST_SEND", console(live.sponsor()), body("broadcastId",
            draft.get("broadcastId"), "baseRevision", draft.get("revision")), 422))).isEqualTo("QB_BROADCAST_CHANGED");
    }

    @Test
    void aPublicationHasAtMostSoManyNoticesSoFarApartAsTheParametersSay() {
        Live live = live("s-limits");
        notice(live.sponsor(), live.publicationId(), List.of("JP"));
        // Another now: too soon (24 hours apart).
        Map<String, Object> soon = noticeDraft(live.sponsor(), "title", body("en", "Again"), "body",
            body("en", "Play"), "publicationId", live.publicationId(), "regions", List.of("JP"));
        assertThat(problemCodes(soon)).containsExactly("QB_BROADCAST_TOO_SOON");
        Instant now = clock.instant();
        notice(live.sponsor(), live.publicationId(), List.of("JP"), "scheduledAt",
            now.plus(Duration.ofHours(25)).toString());
        notice(live.sponsor(), live.publicationId(), List.of("ASIA"), "scheduledAt",
            now.plus(Duration.ofHours(50)).toString());
        Map<String, Object> fourth = noticeDraft(live.sponsor(), "title", body("en", "Fourth"), "body",
            body("en", "Play"), "publicationId", live.publicationId(), "regions", List.of("JP"), "scheduledAt",
            now.plus(Duration.ofHours(75)).toString());
        assertThat(problemCodes(fourth)).containsExactly("QB_BROADCAST_TOO_MANY");
        // The numbers are parameters (Q7-10): four allowed, 12 hours apart.
        run("PARAM_SET", admin(), body("key", QbParams.BROADCAST_SPONSOR_MAX, "value", "4"));
        run("PARAM_SET", admin(), body("key", QbParams.BROADCAST_SPONSOR_INTERVAL, "value", "12"));
        Map<String, Object> again = noticeDraft(live.sponsor(), "broadcastId", fourth.get("broadcastId"),
            "baseRevision", fourth.get("revision"), "title", body("en", "Fourth"), "body", body("en", "Play"),
            "publicationId", live.publicationId(), "regions", List.of("JP"), "scheduledAt",
            now.plus(Duration.ofHours(13)).toString());
        assertThat(problemCodes(again)).isEmpty();
        run("PARAM_SET", admin(), body("key", QbParams.BROADCAST_SPONSOR_MAX, "value", "3"));
        run("PARAM_SET", admin(), body("key", QbParams.BROADCAST_SPONSOR_INTERVAL, "value", "24"));
    }

    @Test
    void twoNoticesSentAtOnceKeepThePublicationsLimit() throws Exception {
        Live live = live("s-race");
        List<Map<String, Object>> drafts = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            drafts.add(noticeDraft(live.sponsor(), "title", body("en", "Race " + i), "body", body("en", "Play"),
                "publicationId", live.publicationId(), "regions", List.of("JP")));
        }
        // Both drafts are fine alone; sent at once, the sponsor's account lock lets one go and the other meets the
        // interval under it.
        List<java.util.concurrent.CompletableFuture<Integer>> sends = new ArrayList<>();
        for (Map<String, Object> draft : drafts) {
            assertThat(problemCodes(draft)).isEmpty();
            sends.add(java.util.concurrent.CompletableFuture.supplyAsync(() -> call("QB_BROADCAST_SEND",
                console(live.sponsor()), body("broadcastId", draft.get("broadcastId"), "baseRevision",
                    draft.get("revision"))).returnResult(String.class).getStatus().value()));
        }
        List<Integer> statuses = new ArrayList<>();
        for (java.util.concurrent.CompletableFuture<Integer> send : sends) {
            statuses.add(send.get(60, java.util.concurrent.TimeUnit.SECONDS));
        }
        assertThat(statuses).containsExactlyInAnyOrder(200, 422);
        assertThat(drafts.stream().map(draft -> broadcast(draft.get("broadcastId")).get("status")).toList())
            .containsExactlyInAnyOrder("SENT", "DRAFT");
    }

    // ---- scheduling ------------------------------------------------------------------------------------------------

    @Test
    void aScheduledNoticeIsSentByTheJobAtItsTimeOrCancelledWhenItsPublicationEnded() {
        Live live = live("s-schedule");
        String taker = newTaker(unique("t-schedule"), "JP");
        Instant at = clock.instant().plus(Duration.ofHours(1));
        String id = notice(live.sponsor(), live.publicationId(), List.of("JP"), "scheduledAt", at.toString());
        assertThat(broadcast(id)).containsEntry("status", "SCHEDULED");
        runDispatch();
        assertThat(broadcast(id)).containsEntry("status", "SCHEDULED");
        assertThat(itemIds(inbox(taker(taker)))).doesNotContain(id);

        clock.set(at.plusSeconds(30));
        runDispatch();
        Map<String, Object> sent = broadcast(id);
        assertThat(sent).containsEntry("status", "SENT");
        assertThat(instant(sent.get("sent_at"))).isEqualTo(at);
        assertThat(((Number) sent.get("audience_count")).longValue()).isPositive();
        assertThat(itemIds(inbox(taker(taker)))).contains(id);
        assertThat(inbox(taker(taker))).filteredOn(item -> id.equals(item.get("itemId"))).singleElement()
            .satisfies(item -> assertThat(item).containsEntry("sender", "SPONSOR").containsEntry("markdown", false)
                .containsEntry("publicationId", live.publicationId()).containsEntry("category", "PROMO"));
        assertThat(query("SELECT 1 FROM qb_broadcast_schedule WHERE broadcast_id = ?::uuid", id)).isEmpty();

        // Taken back to its draft before its time, and discarded.
        String later = notice(live.sponsor(), live.publicationId(), List.of("JP"), "scheduledAt",
            clock.instant().plus(Duration.ofDays(2)).toString());
        Map<String, Object> back = run("QB_BROADCAST_CANCEL", console(live.sponsor()), body("broadcastId", later,
            "baseRevision", ((Number) broadcast(later).get("revision")).longValue()));
        assertThat(back).containsEntry("status", "DRAFT");
        assertThat(query("SELECT 1 FROM qb_broadcast_schedule WHERE broadcast_id = ?::uuid", later)).isEmpty();
        run("QB_BROADCAST_CANCEL", console(live.sponsor()), body("broadcastId", later, "baseRevision",
            ((Number) broadcast(later).get("revision")).longValue()));
        assertThat(broadcast(later)).containsEntry("status", "CANCELLED");
        assertThat(items("qb.sponsor.broadcasts", console(live.sponsor()), Map.of()))
            .extracting(row -> row.get("broadcastId")).doesNotContain(later);

        // Scheduled for a publication that the administrators end meanwhile: cancelled, with the reason.
        String orphan = notice(live.sponsor(), live.publicationId(), List.of("JP"), "scheduledAt",
            clock.instant().plus(Duration.ofDays(3)).toString());
        run("QB_PUBLICATION_ADMIN_END", TestTokens.bearer(tokens, "content-admin-end",
            QbPermissions.ADMIN_CONTENT_WRITE), body("publicationId", live.publicationId(), "reason", "Spam"));
        // A notice sent before stays sent, but leaves the inboxes with its publication.
        assertThat(itemIds(inbox(taker(taker)))).doesNotContain(id);
        clock.advance(Duration.ofDays(3).plusMinutes(1));
        runDispatch();
        assertThat(broadcast(orphan)).containsEntry("status", "CANCELLED")
            .containsEntry("cancel_reason", "QB_BROADCAST_PUBLICATION_ENDED");
    }

    // ---- withdrawal ------------------------------------------------------------------------------------------------

    @Test
    void sentNoticesAreWithdrawnByTheSponsorOrAnAdministratorWithAReasonTheSponsorReads() {
        Live live = live("s-withdraw");
        String taker = newTaker(unique("t-withdraw"), "JP");
        String own = notice(live.sponsor(), live.publicationId(), List.of("JP"));
        assertThat(itemIds(inbox(taker(taker)))).contains(own);
        run("QB_BROADCAST_WITHDRAW", console(live.sponsor()), body("broadcastId", own, "baseRevision",
            ((Number) broadcast(own).get("revision")).longValue()));
        assertThat(itemIds(inbox(taker(taker)))).doesNotContain(own);
        assertThat(sponsorRow(live.sponsor(), own)).containsEntry("status", "WITHDRAWN")
            .containsEntry("withdrawnByAdmin", false);

        clock.advance(Duration.ofHours(25));
        String bad = notice(live.sponsor(), live.publicationId(), List.of("JP"));
        assertThat(itemIds(inbox(taker(taker)))).contains(bad);
        // An administrator gives a reason and passes a second factor.
        assertThat(refusalCode(refused("QB_ADMIN_BROADCAST_WITHDRAW", contentAdmin(), body("broadcastId", bad),
            422))).isEqualTo("QB_BROADCAST_REASON_REQUIRED");
        call("QB_ADMIN_BROADCAST_WITHDRAW", TestTokens.withoutMfa(tokens, "content-admin-q7c",
            QbPermissions.ADMIN_CONTENT_WRITE), body("broadcastId", bad, "reason", "Misleading"))
            .expectStatus().isForbidden();
        run("QB_ADMIN_BROADCAST_WITHDRAW", contentAdmin(), body("broadcastId", bad, "reason", "Misleading"));
        assertThat(itemIds(inbox(taker(taker)))).doesNotContain(bad);
        // Review L3: the sponsor's dataset says an administrator withdrew it, not which one (the audit knows).
        assertThat(rows("urn:jabiz:dataset:sponsor:QbBroadcast", console(live.sponsor())))
            .filteredOn(row -> bad.equals(String.valueOf(row.get("broadcastId")))).singleElement()
            .satisfies(row -> {
                assertThat(row).containsEntry("withdrawnBy", "ADMIN");
                assertThat(String.valueOf(row)).doesNotContain("content-admin-q7c");
            });
        assertThat(broadcast(own)).containsEntry("withdrawn_by", "SENDER");
        assertThat(sponsorRow(live.sponsor(), bad)).containsEntry("status", "WITHDRAWN")
            .containsEntry("withdrawnByAdmin", true).containsEntry("withdrawReason", "Misleading");
        // Withdrawn ones count against the publication's limit.
        assertThat(((Number) items("qb.sponsor.broadcast-publications", console(live.sponsor()), Map.of("params",
            Map.of("at", clock.instant().toString()))).stream().filter(row -> live.publicationId().equals(
                row.get("publicationId"))).findFirst().orElseThrow().get("notices")).intValue()).isEqualTo(2);
        // Not twice.
        assertThat(refusalCode(refused("QB_ADMIN_BROADCAST_WITHDRAW", contentAdmin(), body("broadcastId", bad,
            "reason", "Again"), 422))).isEqualTo("QB_BROADCAST_NOT_SENT");
    }

    @Test
    void banningASponsorWithdrawsTheirSentAndScheduledNotices() {
        Live live = live("s-ban");
        String taker = newTaker(unique("t-ban"), "JP");
        String sent = notice(live.sponsor(), live.publicationId(), List.of("JP"));
        String scheduled = notice(live.sponsor(), live.publicationId(), List.of("JP"), "scheduledAt",
            clock.instant().plus(Duration.ofDays(2)).toString());
        Live other = live("s-ban-other");
        String untouched = notice(other.sponsor(), other.publicationId(), List.of("JP"));
        assertThat(itemIds(inbox(taker(taker)))).contains(sent, untouched);

        run("QB_USER_BAN", TestTokens.bearer(tokens, "banner", QbPermissions.ADMIN_USERS_BAN), body("userId",
            live.sponsor(), "reason", "Fraud"));
        assertThat(broadcast(sent)).containsEntry("status", "WITHDRAWN")
            .containsEntry("withdraw_reason", "QB_ACCOUNT_SUSPENDED");
        assertThat(broadcast(scheduled)).containsEntry("status", "WITHDRAWN");
        assertThat(broadcast(untouched)).containsEntry("status", "SENT");
        assertThat(itemIds(inbox(taker(taker)))).contains(untouched).doesNotContain(sent);
        assertThat(query("SELECT 1 FROM qb_broadcast_schedule WHERE broadcast_id = ?::uuid", scheduled)).isEmpty();
        // Banned, the sponsor writes no more notices.
        assertThat(refusalCode(refused("QB_BROADCAST_SAVE", console(live.sponsor()), body("title", body("en", "X"),
            "body", body("en", "Y"), "publicationId", live.publicationId(), "regions", List.of("JP")), 422)))
            .isEqualTo("QB_USER_BANNED");
        // The ban's own reason stays internal.
        assertThat(String.valueOf(sponsorRow(live.sponsor(), sent))).doesNotContain("Fraud");
    }

    // ---- statistics ------------------------------------------------------------------------------------------------

    @Test
    void theStatisticsCountReadingsClicksAndStartsAfterAClickTheSponsorSeesAtLeastFive() {
        Live live = live("s-stats");
        List<String> takers = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            takers.add(newTaker(unique("t-stats-" + i), "JP"));
        }
        // One who started before the notice: a later click is no conversion.
        String early = takers.getFirst();
        start(taker(early), live.publicationId());
        clock.advance(Duration.ofMinutes(1));
        String id = notice(live.sponsor(), live.publicationId(), List.of("JP"));
        long audience = ((Number) broadcast(id).get("audience_count")).longValue();
        assertThat(audience).isGreaterThanOrEqualTo(6);
        for (String taker : takers) {
            mark(taker(taker), "BROADCAST", id, "READ");
        }
        for (String taker : takers.subList(0, 5)) {
            clock.advance(Duration.ofSeconds(1));
            mark(taker(taker), "BROADCAST", id, "CLICK");
        }
        for (String taker : takers.subList(1, 4)) {
            clock.advance(Duration.ofSeconds(1));
            start(taker(taker), live.publicationId());
        }
        // "All read" is no reading.
        run("QB_INBOX_READ_ALL", taker(newTaker(unique("t-stats-all"), "JP")), body());

        Map<String, Object> admin = adminRow(id);
        assertThat(((Number) admin.get("reads")).longValue()).isEqualTo(6);
        assertThat(((Number) admin.get("clicks")).longValue()).isEqualTo(5);
        assertThat(((Number) admin.get("conversions")).longValue()).isEqualTo(3);
        assertThat(((Number) admin.get("audienceCount")).longValue()).isEqualTo(audience);
        assertThat(admin).containsEntry("senderKind", "SPONSOR").containsEntry("sponsorCompany",
            "Company of " + live.sponsor());

        // Review M2: the sponsor sees the day's snapshot only, never the live counts beside their reward rows.
        Map<String, Object> before = sponsorRow(live.sponsor(), id);
        assertThat(before.get("reads")).isNull();
        assertThat(before.get("snapshotAt")).isNull();
        assertThat(runStats().items().succeeded()).isPositive();
        Map<String, Object> sponsor = sponsorRow(live.sponsor(), id);
        assertThat(Instant.parse(String.valueOf(sponsor.get("snapshotAt")))).isEqualTo(clock.instant());
        assertThat(((Number) sponsor.get("reads")).longValue()).isEqualTo(6);
        // Fewer than 50 reached (the parameter): no clicks or conversions at all; the reach rounded down to tens.
        assertThat(sponsor.get("clicks")).isNull();
        assertThat(sponsor.get("conversions")).isNull();
        Object reach = sponsor.get("audienceCount");
        if (audience < 10) {
            assertThat(reach).isNull();
        } else {
            assertThat(((Number) reach).longValue()).isEqualTo(audience / 10 * 10);
        }
        run("PARAM_SET", admin(), body("key", QbParams.BROADCAST_STATS_MIN_AUDIENCE, "value", "5"));
        sponsor = sponsorRow(live.sponsor(), id);
        assertThat(((Number) sponsor.get("clicks")).longValue()).isEqualTo(5);
        // Three conversions: fewer than five, not shown to the sponsor.
        assertThat(sponsor.get("conversions")).isNull();
        // A click after the snapshot shows the next day only; the administrators' figures are live.
        String late = takers.get(5);
        clock.advance(Duration.ofMinutes(1));
        mark(taker(late), "BROADCAST", id, "CLICK");
        assertThat(((Number) sponsorRow(live.sponsor(), id).get("clicks")).longValue()).isEqualTo(5);
        assertThat(((Number) adminRow(id).get("clicks")).longValue()).isEqualTo(6);
        assertThat(runStats().items().succeeded()).isPositive();
        assertThat(((Number) sponsorRow(live.sponsor(), id).get("clicks")).longValue()).isEqualTo(6);
        // Unchanged counts are not written again.
        assertThat(runStats().items().succeeded()).isZero();
        run("PARAM_SET", admin(), body("key", QbParams.BROADCAST_STATS_MIN_AUDIENCE, "value", "50"));
        // The snapshot is the system's.
        refusedAsInternal(BroadcastProcesses.STATS, admin(), body("broadcastId", id, "reads", 1, "clicks", 1,
            "conversions", 1));
        // Counts only: nobody's identity in either list.
        for (String taker : takers) {
            assertThat(String.valueOf(admin)).doesNotContain(taker);
            assertThat(String.valueOf(sponsor)).doesNotContain(taker);
        }
    }

    // ---- the platform's announcements ------------------------------------------------------------------------------

    @Test
    void anAnnouncementIsCheckedSavedScheduledAndTheBroadcastsAreOnlyInsertedInto() {
        Map<String, Object> saved = adminSave(announcement("Checked", "title", body("ja", "日本語だけ"), "targetMode",
            "USERS", "userIds", List.of(newTaker(unique("t-checked"), "JP")), "scheduledAt",
            clock.instant().minusSeconds(60).toString()));
        assertThat(problemCodes(saved)).contains("QB_BROADCAST_TRANSLATION_MISSING", "QB_BROADCAST_SCHEDULE_PAST");
        assertThat(codes(refused("QB_ADMIN_BROADCAST_SAVE", contentAdmin(), announcement("Unknown", "targetMode",
            "USERS", "userIds", List.of("00000000-0000-7000-8000-000000000000")), 422)))
            .containsExactly("QB_BROADCAST_UNKNOWN_USERS");
        List<String> many = new ArrayList<>();
        for (int i = 0; i <= 1000; i++) {
            many.add(String.format("00000000-0000-7000-8000-%012d", i));
        }
        assertThat(codes(refused("QB_ADMIN_BROADCAST_SAVE", contentAdmin(), announcement("Many", "targetMode",
            "USERS", "userIds", many), 422))).containsExactly("QB_BROADCAST_TOO_MANY_USERS");

        String taker = newTaker(unique("t-scheduled"), "JP");
        Instant at = clock.instant().plus(Duration.ofMinutes(10));
        String id = announce(announcement("Later", "scheduledAt", at.toString()));
        assertThat(broadcast(id)).containsEntry("status", "SCHEDULED");
        assertThat(itemIds(inbox(taker(taker)))).doesNotContain(id);
        clock.set(at.plusSeconds(5));
        runDispatch();
        assertThat(broadcast(id)).containsEntry("status", "SENT");
        assertThat(itemIds(inbox(taker(taker)))).contains(id);
        assertThat(inbox(taker(taker))).filteredOn(item -> id.equals(item.get("itemId"))).singleElement()
            .satisfies(item -> assertThat(item).containsEntry("markdown", true).containsEntry("sender", "PLATFORM"));

        assertThat(query("SELECT coalesce(n_tup_upd, 0) + coalesce(n_tup_del, 0) AS changed "
            + "FROM pg_stat_user_tables WHERE schemaname = current_schema() AND relname = 'qb_broadcast_version'"))
            .singleElement().satisfies(row -> assertThat(((Number) row.get("changed")).longValue()).isZero());
        assertThat(query("SELECT 1 AS guarded FROM pg_trigger t JOIN pg_class c ON c.oid = t.tgrelid "
            + "JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = current_schema() "
            + "AND c.relname = 'qb_broadcast_version' AND NOT t.tgisinternal")).isNotEmpty();
    }
}
