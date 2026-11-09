package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.operator.OperatorProcesses;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.quizbuks.videouse.VideoUseCodes;
import com.jabiz.quizbuks.videouse.VideoUseFigures;
import com.jabiz.quizbuks.videouse.VideoUseProcesses;
import com.jabiz.quizbuks.videouse.VideoUseReminders;
import com.jabiz.quizbuks.videouse.VideoUseRules;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-S14 (docs/quizbuks/plans/impl/F-S14.md): the ledger of shared videos' uses and both sides' figures. The user of
 * another's shared video writes its use, every act a new version of an append-only table; the provider confirms the
 * revision it read or sends it back with a reason; the figures are counted every day from the counting tables, final
 * once the publication ended and always the same counted again; each figure below the minimum group is not shown; a
 * use waiting too long reminds both sides; the operators read their own sponsors' uses ({@link VideoUseErasureIT}: an
 * erased sponsor's free text goes, the use stays).
 */
class VideoUseIT extends VideoUseItSupport {

    /** The ledger is append-only: never updated or deleted (the guard, and the counters that confirm it). */
    @AfterEach
    void onlyInserted() {
        assertThat(query("SELECT coalesce(n_tup_upd, 0) + coalesce(n_tup_del, 0) AS changed "
            + "FROM pg_stat_user_tables WHERE schemaname = current_schema() AND relname = 'qb_video_use_version'"))
            .singleElement().satisfies(row -> assertThat(((Number) row.get("changed")).longValue()).isZero());
        assertThat(query("SELECT 1 AS guarded FROM pg_trigger t JOIN pg_class c ON c.oid = t.tgrelid "
            + "JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = current_schema() "
            + "AND c.relname = 'qb_video_use_version' AND NOT t.tgisinternal")).isNotEmpty();
    }

    // ---- the ledger -------------------------------------------------------------------------------------------------

    @Test
    void everyActIsANewVersionAndTheProviderConfirmsOrSendsBackWithAReason() {
        World w = world("ledger");
        String user = sponsorToken(w.user());
        String provider = sponsorToken(w.provider());

        // Before any entry the user's page lists the publication, with no use; the provider sees nothing yet.
        assertThat(userRow(w.user(), w.publicationId())).containsEntry("videoUseId", null)
            .containsEntry("sharedVideoId", w.sharedVideoId()).containsKey("providerCompany");
        assertThat(providerRow(w.provider(), w.publicationId())).isNull();

        // Only the publication's sponsor writes it; a period backwards, an unknown price are refused.
        refused(VideoUseProcesses.SAVE, provider, entry(w.publicationId(), VideoUseRules.FIXED, 50_000), 404);
        Map<String, Object> backwards = entry(w.publicationId(), VideoUseRules.FIXED, 50_000);
        backwards.put("periodTo", LocalDate.parse(today()).minusDays(1).toString());
        assertThat(codes(refused(VideoUseProcesses.SAVE, user, backwards, 422))).containsExactly(VideoUseCodes.PERIOD);
        assertThat(codes(refused(VideoUseProcesses.SAVE, user, entry(w.publicationId(), VideoUseRules.FIXED, -1),
            422))).containsExactly(VideoUseCodes.PRICE);

        Map<String, Object> draft = run(VideoUseProcesses.SAVE, user, entry(w.publicationId(), VideoUseRules.FIXED,
            50_000));
        String id = (String) draft.get("videoUseId");
        assertThat(draft).containsEntry("status", VideoUseRules.DRAFT).containsEntry("revision", 1);
        // One use a publication.
        assertThat(codes(refused(VideoUseProcesses.SAVE, user, entry(w.publicationId(), VideoUseRules.FIXED, 1),
            422))).containsExactly(VideoUseCodes.EXISTS);
        // A draft never submitted is the user's own: the provider does not see it, nor answers it.
        assertThat(providerRow(w.provider(), w.publicationId())).isNull();
        assertThat(history("qb.sponsor.video-use-history-provided", id, provider)).isEmpty();
        assertThat(history("qb.sponsor.video-use-history", id, user)).hasSize(1);
        assertThat(codes(refused(VideoUseProcesses.CONFIRM, provider, body("videoUseId", id, "baseRevision", 1), 422)))
            .containsExactly(VideoUseCodes.STATE);

        assertThat(run(VideoUseProcesses.SUBMIT, user, body("videoUseId", id, "baseRevision", 1)))
            .containsEntry("status", VideoUseRules.SUBMITTED).containsEntry("revision", 2);
        assertThat(openTasks(w.provider())).singleElement().satisfies(task -> assertThat(task)
            .containsEntry("title_key", VideoUseProcesses.CONFIRM_TASK_TITLE)
            .containsEntry("source_key", "qb.video-use-confirm:" + id));
        // Waiting for the provider: the user cannot change it.
        assertThat(codes(refused(VideoUseProcesses.SAVE, user, body("videoUseId", id, "periodFrom", today(),
            "periodTo", today(), "pricing", VideoUseRules.FIXED, "price", 1), 422))).containsExactly(VideoUseCodes.STATE);

        // The provider sees it with the user's company; only the provider answers, only with a reason to send it back.
        assertThat(providerRow(w.provider(), w.publicationId())).containsEntry("videoUseId", id)
            .containsEntry("status", VideoUseRules.SUBMITTED).containsEntry("price", 50_000)
            .containsEntry("pricing", VideoUseRules.FIXED).containsEntry("note", "Agreed by mail")
            .containsKey("userCompany");
        refused(VideoUseProcesses.REJECT, user, body("videoUseId", id, "reason", "No", "baseRevision", 2), 404);
        refused(VideoUseProcesses.REJECT, provider, body("videoUseId", id, "reason", " ", "baseRevision", 2), 400);
        refused(VideoUseProcesses.CONFIRM, sponsorToken(newSponsor(unique("s-fs14-stranger"))),
            body("videoUseId", id, "baseRevision", 2), 404);
        assertThat(run(VideoUseProcesses.REJECT, provider, body("videoUseId", id, "reason",
            "We agreed 60,000 yen", "baseRevision", 2))).containsEntry("status", VideoUseRules.REJECTED)
            .containsEntry("revision", 3);
        assertThat(openTasks(w.provider())).isEmpty();
        assertThat(openTasks(w.user())).singleElement().satisfies(task -> assertThat(task)
            .containsEntry("title_key", VideoUseProcesses.RESUBMIT_TASK_TITLE));
        assertThat(userRow(w.user(), w.publicationId())).containsEntry("status", VideoUseRules.REJECTED)
            .containsEntry("rejectReason", "We agreed 60,000 yen");

        // Corrected: a draft again (the reason still there to correct by), then submitted and confirmed as read.
        Map<String, Object> corrected = entry(w.publicationId(), VideoUseRules.FIXED, 60_000);
        corrected.put("videoUseId", id);
        corrected.put("baseRevision", 3);
        assertThat(run(VideoUseProcesses.SAVE, user, corrected)).containsEntry("status", VideoUseRules.DRAFT)
            .containsEntry("revision", 4);
        assertThat(openTasks(w.user())).isEmpty();
        assertThat(userRow(w.user(), w.publicationId())).containsEntry("rejectReason", "We agreed 60,000 yen")
            .containsEntry("price", 60_000);
        run(VideoUseProcesses.SUBMIT, user, body("videoUseId", id, "baseRevision", 4));
        assertThat(userRow(w.user(), w.publicationId())).containsEntry("rejectReason", null);
        assertThat(codes(refused(VideoUseProcesses.CONFIRM, provider, body("videoUseId", id, "baseRevision", 4), 422)))
            .containsExactly(VideoUseCodes.CHANGED);
        assertThat(codes(refused(VideoUseProcesses.CONFIRM, provider, body("videoUseId", id), 422)))
            .containsExactly(VideoUseCodes.CHANGED);
        refused(VideoUseProcesses.CONFIRM, user, body("videoUseId", id, "baseRevision", 5), 404);
        assertThat(run(VideoUseProcesses.CONFIRM, provider, body("videoUseId", id, "baseRevision", 5)))
            .containsEntry("status", VideoUseRules.CONFIRMED).containsEntry("revision", 6);
        assertThat(userRow(w.user(), w.publicationId())).containsEntry("confirmedRevision", 5)
            .containsEntry("amount", 60_000);

        // A confirmed use corrected asks the provider again.
        corrected.put("baseRevision", 6);
        corrected.put("pricing", VideoUseRules.PER_MILLE);
        corrected.put("price", 2_000);
        assertThat(run(VideoUseProcesses.SAVE, user, corrected)).containsEntry("status", VideoUseRules.DRAFT)
            .containsEntry("revision", 7);

        // Every act is a version of its own, kept: the platform's audit has them too.
        assertThat(versions(id)).isEqualTo(7);
        assertThat(query("SELECT version_no, status, revision, price FROM qb_video_use_version WHERE video_use_id = "
            + "?::uuid ORDER BY version_no", id)).extracting(row -> row.get("status")).containsExactly("DRAFT",
            "SUBMITTED", "REJECTED", "DRAFT", "SUBMITTED", "CONFIRMED", "DRAFT");
        assertThat(query("SELECT 1 FROM sys_audit_record WHERE entity_id = ?", id)).hasSizeGreaterThanOrEqualTo(7);

        // Both sides read the history on their pages: who did what, when, with the reason sent back.
        List<Map<String, Object>> versions = history("qb.sponsor.video-use-history", id, sponsorToken(w.user()));
        assertThat(versions).extracting(v -> v.get("processName")).containsExactly(VideoUseProcesses.SAVE,
            VideoUseProcesses.SUBMIT, VideoUseProcesses.REJECT, VideoUseProcesses.SAVE, VideoUseProcesses.SUBMIT,
            VideoUseProcesses.CONFIRM, VideoUseProcesses.SAVE);
        assertThat(versions).extracting(v -> v.get("status")).containsExactly("DRAFT", "SUBMITTED", "REJECTED",
            "DRAFT", "SUBMITTED", "CONFIRMED", "DRAFT");
        assertThat(versions.get(2)).containsEntry("rejectReason", "We agreed 60,000 yen").containsEntry("revision", 3)
            .doesNotContainKey("actorId");
        assertThat(versions).allSatisfy(v -> assertThat(v.get("recordedAt")).isNotNull());
        assertThat(history("qb.sponsor.video-use-history-provided", id, provider)).hasSize(7);
        // Another sponsor reads none of it, on either side.
        String stranger = sponsorToken(newSponsor(unique("s-fs14-reader")));
        assertThat(history("qb.sponsor.video-use-history", id, stranger)).isEmpty();
        assertThat(history("qb.sponsor.video-use-history-provided", id, stranger)).isEmpty();
        // Headquarters reads who did each.
        assertThat(history("qb.admin.video-use-history", id, TestTokens.bearer(tokens, "fs14-hq",
            QbPermissions.ADMIN_VIDEO_USE_READ))).extracting(v -> v.get("actorId")).containsExactly(w.user(), w.user(),
            w.provider(), w.user(), w.user(), w.provider(), w.user());
    }

    @Test
    void onlyAPublicationOfTheCallersUsingAnothersSharedVideoHasAUse() {
        String user = newSponsor(unique("s-fs14-own-user"));
        credit(user, 100_000);
        // Its own video (a shared video of its own counts as its own).
        String own = live(user, "FS14 own", approvedVideo(user));
        String none = live(user, "FS14 none", null);
        for (String publicationId : List.of(own, none)) {
            assertThat(codes(refused(VideoUseProcesses.SAVE, sponsorToken(user), entry(publicationId,
                VideoUseRules.FIXED, 1), 422))).containsExactly(VideoUseCodes.NOT_SHARED);
            assertThat(userRow(user, publicationId)).isNull();
        }
        // A publication nobody knows.
        refused(VideoUseProcesses.SAVE, sponsorToken(user), entry(UUID.randomUUID().toString(), VideoUseRules.FIXED,
            1), 404);
    }

    // ---- the figures ------------------------------------------------------------------------------------------------

    @Test
    void theFiguresAreCountedDailyFinalisedAtTheEndShownFromTheMinimumGroupAndReconciled() {
        World w = world("figures");
        String user = sponsorToken(w.user());
        String id = (String) run(VideoUseProcesses.SAVE, user, entry(w.publicationId(), VideoUseRules.PER_TAKER, 100))
            .get("videoUseId");
        run(VideoUseProcesses.SUBMIT, user, body("videoUseId", id, "baseRevision", 1));
        minGroup("3");
        try {
            // Before the job, no figures; it counts what the counting tables hold.
            assertThat(userRow(w.user(), w.publicationId())).containsEntry("refreshedAt", null)
                .containsEntry("successes", null);
            Instant now = clock.instant();
            exposures(w.publicationId(), w.user(), w.provider(), 3, true, true, false, now.minusSeconds(60));
            exposures(w.publicationId(), w.user(), w.provider(), 2, false, true, true, now.minusSeconds(60));
            // Two takers who went on to answer, one of them passed (a real attempt): below the minimum group of 3.
            String passer = newTaker(unique("t-fs14-passer"), "JP");
            play(passer, w.publicationId(), true, true);
            assertThat(query("SELECT passed FROM qb_attempt WHERE user_id = ? AND publication_id = ?::uuid", passer,
                w.publicationId())).singleElement().satisfies(row -> assertThat(row).containsEntry("passed", true));
            exposure(w.publicationId(), passer, w.user(), w.provider(), false, false, true, now.minusSeconds(30));
            clock.set(now.plusSeconds(60));
            runFigures();
            Map<String, Object> stored = query("SELECT * FROM qb_video_use_figures WHERE publication_id = ?",
                w.publicationId()).getFirst();
            assertThat(stored).containsEntry("finalised_at", null).containsEntry("sponsor_id", w.user())
                .containsEntry("provider_id", w.provider());
            assertThat(number(stored.get("opens"))).isEqualTo(3);
            assertThat(number(stored.get("successes"))).isEqualTo(5);
            assertThat(number(stored.get("goes"))).isEqualTo(3);
            assertThat(number(stored.get("qualified"))).isEqualTo(1);
            // Both sides see the same, each below the minimum group not shown; the amount is by takers (3 x 100).
            for (Map<String, Object> row : List.of(userRow(w.user(), w.publicationId()),
                providerRow(w.provider(), w.publicationId()))) {
                assertThat(row).containsEntry("opens", 3).containsEntry("successes", 5).containsEntry("goes", 3)
                    .containsEntry("qualified", null).containsEntry("minGroup", 3).containsEntry("amount", 300)
                    .containsEntry("finalisedAt", null);
            }
            minGroup("4");
            assertThat(userRow(w.user(), w.publicationId())).containsEntry("opens", null).containsEntry("goes", null)
                .containsEntry("successes", 5).containsEntry("amount", null);
            minGroup("1");
            assertThat(userRow(w.user(), w.publicationId())).as("never below 3").containsEntry("qualified", null)
                .containsEntry("minGroup", 3);
            minGroup("3");

            // New counts come in with the next day's run, not before.
            exposures(w.publicationId(), w.user(), w.provider(), 1, false, true, false, clock.instant());
            assertThat(userRow(w.user(), w.publicationId())).containsEntry("successes", 5);
            clock.set(clock.instant().plus(Duration.ofDays(1)));
            runFigures();
            assertThat(userRow(w.user(), w.publicationId())).containsEntry("successes", 6);

            // Ended: the next run finalises up to the end; what is counted afterwards changes nothing.
            run("QB_PUBLICATION_ADMIN_END", TestTokens.bearer(tokens, "fs14-content-admin",
                QbPermissions.ADMIN_CONTENT_WRITE), body("publicationId", w.publicationId(), "reason", "F-S14 test"));
            Instant end = clock.instant();
            exposures(w.publicationId(), w.user(), w.provider(), 2, true, true, true, end.plusSeconds(30));
            clock.set(end.plus(Duration.ofHours(2)));
            runFigures();
            Map<String, Object> finalised = query("SELECT * FROM qb_video_use_figures WHERE publication_id = ?",
                w.publicationId()).getFirst();
            assertThat(finalised.get("finalised_at")).isNotNull();
            assertThat(((java.sql.Timestamp) finalised.get("cutoff_at")).toInstant()).isBeforeOrEqualTo(end);
            assertThat(number(finalised.get("successes"))).isEqualTo(6);
            assertThat(number(finalised.get("opens"))).isEqualTo(3);
            clock.set(clock.instant().plus(Duration.ofDays(1)));
            runFigures();
            assertThat(query("SELECT finalised_at, successes, refreshed_at FROM qb_video_use_figures WHERE "
                + "publication_id = ?", w.publicationId()).getFirst())
                .containsEntry("refreshed_at", finalised.get("refreshed_at"))
                .containsEntry("finalised_at", finalised.get("finalised_at"));
            assertThat(userRow(w.user(), w.publicationId()).get("finalisedAt")).isNotNull();

            // Reconciled: the finalised figures are what the counting tables give up to the end.
            Map<String, Object> recount = items("qb.system.video-use-count", system(), body("params", body(
                "publicationId", w.publicationId(), "cutoff", ((java.sql.Timestamp) finalised.get("cutoff_at"))
                    .toInstant().toString()))).getFirst();
            for (String figure : List.of("opens", "successes", "goes", "qualified")) {
                assertThat(number(recount.get(figure))).as(figure).isEqualTo(number(finalised.get(figure)));
            }
            runCheck();
            assertThat(checkTask()).isNull();
            // A count changed behind the finalised figures (a back-dated row): the check asks headquarters.
            exposures(w.publicationId(), w.user(), w.provider(), 1, false, true, false, end.minusSeconds(5));
            clock.set(clock.instant().plus(Duration.ofDays(1)));
            runCheck();
            assertThat(checkTask()).isNotNull();
        } finally {
            minGroup("5");
        }
    }

    Object checkTask() {
        List<Map<String, Object>> tasks = query("SELECT task_id FROM sys_task_version WHERE source_key = ?",
            VideoUseFigures.checkSource(clock.instant(), TOKYO));
        return tasks.isEmpty() ? null : tasks.getFirst().get("task_id");
    }

    // ---- the reminder -----------------------------------------------------------------------------------------------

    @Test
    void aUseWaitingLongerThanTheParameterRemindsBothSidesOnce() {
        World w = world("remind");
        String id = (String) run(VideoUseProcesses.SAVE, sponsorToken(w.user()), entry(w.publicationId(), VideoUseRules.FIXED, 1))
            .get("videoUseId");
        run(VideoUseProcesses.SUBMIT, sponsorToken(w.user()), body("videoUseId", id, "baseRevision", 1));
        String source = VideoUseRules.reminderSource(id, 2);
        // Six days: not yet (the default is seven).
        clock.set(clock.instant().plus(Duration.ofDays(6)));
        runReminders();
        assertThat(query("SELECT 1 FROM sys_task_version WHERE source_key = ?", source)).isEmpty();
        clock.set(clock.instant().plus(Duration.ofDays(1)).plusSeconds(60));
        runReminders();
        assertThat(openTasks(w.user())).filteredOn(task -> source.equals(task.get("source_key"))).singleElement()
            .satisfies(task -> assertThat(task).containsEntry("title_key", VideoUseReminders.SPONSOR_TASK_TITLE));
        assertThat(openTasks(w.provider())).filteredOn(task -> source.equals(task.get("source_key"))).singleElement()
            .satisfies(task -> assertThat(task).containsEntry("title_key", VideoUseReminders.PROVIDER_TASK_TITLE));
        // Once per submitted revision.
        clock.set(clock.instant().plus(Duration.ofDays(1)));
        runReminders();
        assertThat(query("SELECT DISTINCT task_id FROM sys_task_version WHERE source_key = ?", source)).hasSize(2);
        // Answered: the reminders and the provider's task are done.
        run(VideoUseProcesses.CONFIRM, sponsorToken(w.provider()), body("videoUseId", id, "baseRevision", 2));
        assertThat(openTasks(w.user())).isEmpty();
        assertThat(openTasks(w.provider())).isEmpty();

        // The days are a parameter.
        try {
            setParam(QbParams.VIDEO_USE_REMIND_DAYS, "1");
            Map<String, Object> corrected = entry(w.publicationId(), VideoUseRules.FIXED, 2);
            corrected.put("videoUseId", id);
            run(VideoUseProcesses.SAVE, sponsorToken(w.user()), corrected);
            run(VideoUseProcesses.SUBMIT, sponsorToken(w.user()), body("videoUseId", id, "baseRevision", 4));
            clock.set(clock.instant().plus(Duration.ofDays(1)).plusSeconds(60));
            runReminders();
            assertThat(query("SELECT 1 FROM sys_task_version WHERE source_key = ?",
                VideoUseRules.reminderSource(id, 5))).hasSize(2);
        } finally {
            setParam(QbParams.VIDEO_USE_REMIND_DAYS, "7");
        }
    }

    // ---- the operators ----------------------------------------------------------------------------------------------

    @Test
    void theOperatorsOfBothSidesReadTheUseAndItsHistoryAndAThirdDoesNot() {
        NewOperator north = newOperatorWithLead(operatorsAdmin(), unique("FS14 North"), null);
        NewOperator east = newOperatorWithLead(operatorsAdmin(), unique("FS14 East"), null);
        World w = world("operators");
        run(OperatorProcesses.ASSIGN, operatorsAdmin(), body("userId", w.user(), "operatorId", north.operatorId(),
            "reason", "F-S14"));
        run(OperatorProcesses.ASSIGN, operatorsAdmin(), body("userId", w.provider(), "operatorId", east.operatorId(),
            "reason", "F-S14"));
        String id = (String) run(VideoUseProcesses.SAVE, sponsorToken(w.user()), entry(w.publicationId(),
            VideoUseRules.PER_MILLE, 3_000)).get("videoUseId");
        String headquarters = TestTokens.bearer(tokens, "fs14-hq", QbPermissions.ADMIN_VIDEO_USE_READ);
        String northSession = memberSession(north.leadId(), north.operatorId());
        String eastSession = memberSession(east.leadId(), east.operatorId());
        // The user's operator and headquarters read it at once (the users' page).
        for (String reader : List.of(headquarters, northSession)) {
            assertThat(items("qb.admin.video-uses", reader, body("params", body())))
                .filteredOn(row -> id.equals(row.get("videoUseId"))).singleElement()
                .satisfies(row -> assertThat(row).containsEntry("sponsorId", w.user())
                    .containsEntry("providerId", w.provider()).containsEntry("pricing", VideoUseRules.PER_MILLE));
        }
        assertThat(items("qb.admin.video-uses", eastSession, body("params", body())))
            .noneMatch(row -> id.equals(row.get("videoUseId")));
        // The provider's operator, once the user sent it (the providers' page; a draft never sent is the user's own).
        assertThat(items("qb.admin.video-uses-provided", eastSession, body("params", body())))
            .noneMatch(row -> id.equals(row.get("videoUseId")));
        run(VideoUseProcesses.SUBMIT, sponsorToken(w.user()), body("videoUseId", id, "baseRevision", 1));
        for (String reader : List.of(headquarters, eastSession)) {
            assertThat(items("qb.admin.video-uses-provided", reader, body("params", body())))
                .filteredOn(row -> id.equals(row.get("videoUseId"))).singleElement()
                .satisfies(row -> assertThat(row).containsEntry("providerId", w.provider())
                    .containsEntry("name", "FS14 operators").containsEntry("status", VideoUseRules.SUBMITTED));
        }
        assertThat(items("qb.admin.video-uses-provided", northSession, body("params", body())))
            .noneMatch(row -> id.equals(row.get("videoUseId")));
        // Each reads the history through its own path; a third operator reads neither the pages nor the history.
        assertThat(history("qb.admin.video-use-history", id, northSession)).hasSize(2);
        assertThat(history("qb.admin.video-use-history-provided", id, eastSession)).hasSize(2)
            .extracting(v -> v.get("actorId")).containsExactly(w.user(), w.user());
        assertThat(history("qb.admin.video-use-history", id, headquarters)).hasSize(2);
        assertThat(history("qb.admin.video-use-history-provided", id, headquarters)).hasSize(2);
        assertThat(history("qb.admin.video-use-history", id, eastSession)).isEmpty();
        assertThat(history("qb.admin.video-use-history-provided", id, northSession)).isEmpty();
        NewOperator south = newOperatorWithLead(operatorsAdmin(), unique("FS14 South"), null);
        String southSession = memberSession(south.leadId(), south.operatorId());
        for (String template : List.of("qb.admin.video-uses", "qb.admin.video-uses-provided")) {
            assertThat(items(template, southSession, body("params", body())))
                .noneMatch(row -> id.equals(row.get("videoUseId")));
        }
        assertThat(history("qb.admin.video-use-history", id, southSession)).isEmpty();
        assertThat(history("qb.admin.video-use-history-provided", id, southSession)).isEmpty();
        // Read only: the sponsors cannot read the pages, nor anyone write the table.
        for (String template : List.of("qb.admin.video-uses", "qb.admin.video-uses-provided")) {
            client.post().uri("/api/queries/{id}", template)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .header(org.springframework.http.HttpHeaders.AUTHORIZATION, sponsorToken(w.user()))
                .bodyValue(Map.of()).exchange().expectStatus().isForbidden();
        }
    }

    /** The period's longest length is a parameter. */
    @Test
    void theLongestPeriodIsAParameter() {
        World w = world("period");
        Map<String, Object> year = entry(w.publicationId(), VideoUseRules.FIXED, 1);
        year.put("periodTo", LocalDate.parse(today()).plusDays(31).toString());
        try {
            setParam(QbParams.VIDEO_USE_MAX_PERIOD_DAYS, "31");
            Map<String, Object> problem = refused(VideoUseProcesses.SAVE, sponsorToken(w.user()), year, 422);
            assertThat(codes(problem)).containsExactly(VideoUseCodes.PERIOD);
            assertThat(String.valueOf(violations(problem).getFirst().get("message"))).contains("31");
            setParam(QbParams.VIDEO_USE_MAX_PERIOD_DAYS, "32");
            run(VideoUseProcesses.SAVE, sponsorToken(w.user()), year);
        } finally {
            setParam(QbParams.VIDEO_USE_MAX_PERIOD_DAYS, "3660");
        }
    }

    /** The versions of a use through one of the four history templates, as {@code reader}, oldest first. */
    List<Map<String, Object>> history(String template, String videoUseId, String reader) {
        return items(template, reader, body("params", body("videoUseId", videoUseId), "limit", 100));
    }
}
