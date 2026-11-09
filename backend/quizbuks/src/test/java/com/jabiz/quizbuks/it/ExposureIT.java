package com.jabiz.quizbuks.it;

import com.jabiz.job.JobDefinition;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.asset.AssetProcesses;
import com.jabiz.quizbuks.content.QuizTopics;
import com.jabiz.quizbuks.exposure.ExposureCheck;
import com.jabiz.quizbuks.exposure.ExposureCodes;
import com.jabiz.quizbuks.exposure.ExposureProcesses;
import com.jabiz.quizbuks.exposure.ExposureRules;
import com.jabiz.quizbuks.operator.OperatorProcesses;
import com.jabiz.quizbuks.publishing.PublicationExposure;
import com.jabiz.quizbuks.publishing.PublicationOutput;
import com.jabiz.quizbuks.publishing.PublicationSharedVideos;
import com.jabiz.quizbuks.reward.StaffProcesses;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.quizbuks.setup.QbRoles;
import com.jabiz.quizbuks.sharedvideo.SharedVideoProcesses;
import com.jabiz.quizbuks.topic.TopicProcesses;
import com.jabiz.quizbuks.topic.TopicRules;
import com.jabiz.runtime.process.steps.CallProcess;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-S13 (docs/quizbuks/plans/impl/F-S13.md): the exposures of other sponsors' shared videos and the pushes. A vertical
 * video counts once in the vertical mode, a horizontal one once on the start page, each for the seconds of the
 * parameter; one's own video, the publisher, the provider and their staff never count; the daily cap refuses and is
 * recorded for the operators; a push opened is not pushed again, and the pushes follow the feed's strict mode; the
 * purchased exposures (decision A: one sub-process for F-M8) end the publication when used up, through the minute's job,
 * and what goes beyond is recorded but not charged; the daily reconciliation.
 */
class ExposureIT extends PlayItSupport {

    /** A site mainland China plays (F-S11): the tests' unknown address counts as mainland China. */
    static final String VIDEO = "https://www.bilibili.com/video/BV1GJ411x7h7";

    /** A test-only root that runs the purchase's sub-process, as F-M8's purchase will. */
    static final String PURCHASE_RELAY = "IT_QB_EXPOSURE_PURCHASE_RELAY";

    @TestConfiguration
    static class Processes {

        @Bean
        ProcessDefinition<PublicationExposure.PurchasedInput, PublicationOutput, ProcessContext> itPurchaseRelay() {
            return ProcessDefinition.define(PURCHASE_RELAY, 1, PublicationExposure.PurchasedInput.class,
                PublicationOutput.class, ProcessContext.class, pb -> pb
                    .permissions(QbPermissions.PUBLICATION_SYSTEM)
                    .contextFactory((start, input) -> {
                        ProcessContext ctx = new ProcessContext(start);
                        ctx.put("in", input);
                        return ctx;
                    })
                    .outputMapper(ctx -> ctx.get("out", PublicationOutput.class))
                    .step("Buy", CallProcess.<ProcessContext>of(PublicationExposure.PURCHASED_SET, 1,
                        ctx -> ctx.get("in"), "out")));
        }
    }

    @Autowired
    @Qualifier("qbExposureEndsJob")
    JobDefinition<PublicationExposure.EndInput> endsJob;

    @Autowired
    @Qualifier("qbExposureCheckJob")
    JobDefinition<ExposureCheck.RunInput> checkJob;

    @AfterEach
    void onlyInserted() {
        assertNothingUpdated();
    }

    // ---- the world --------------------------------------------------------------------------------------------------

    String topicAdmin() {
        return TestTokens.bearer(tokens, "fs13-topic-admin", QbPermissions.ADMIN_TOPIC_READ,
            QbPermissions.ADMIN_TOPIC_WRITE);
    }

    String word(String code) {
        if (query("SELECT 1 FROM qb_topic WHERE topic_id = ?", code).isEmpty()) {
            run(TopicProcesses.ADD, topicAdmin(), body("topicId", code, "nameZh", code + " 中", "nameJa", code + " 日",
                "nameEn", code + " en"));
        }
        return code;
    }

    /** A video link of the provider's, shared (vertical or not) and approved; the shared video's key. */
    String approvedVideo(String provider, boolean vertical) {
        String token = sponsorToken(provider);
        String assetId = (String) run(AssetProcesses.SAVE, token, body("kind", "VIDEO_LINK", "name", "Cats",
            "language", "en", "url", VIDEO, "durationSec", 95)).get("assetId");
        Map<String, Object> shared = run(SharedVideoProcesses.SHARE, token, body("assetId", assetId,
            "titleZh", "猫 " + assetId.substring(0, 6), "titleJa", "猫の動画", "titleEn", "Cats " + assetId.substring(0, 6),
            "topics", List.of(word("fs13-pets")), "vertical", vertical));
        decide((String) shared.get("approvalRequestId"), "APPROVE", null);
        return (String) shared.get("sharedVideoId");
    }

    /** A draft of the version for everybody, live for a week once submitted, picking the shared video when given. */
    String draft(String sponsor, String versionId, String name, String sharedVideoId) {
        String token = sponsorToken(sponsor);
        Instant now = clock.instant();
        Map<String, Object> save = complete(versionId, name, now, now.plus(Duration.ofDays(7)));
        save.put("maxWinners", 1000);
        save.put("regions", List.of("GLOBAL"));
        String publicationId = (String) run("QB_PUBLICATION_SAVE", token, save).get("publicationId");
        if (sharedVideoId != null) {
            run(PublicationSharedVideos.SET, token, body("publicationId", publicationId, "sharedVideoId",
                sharedVideoId));
        }
        return publicationId;
    }

    String live(String sponsor, String name, String sharedVideoId) {
        String publicationId = draft(sponsor, version(sponsorToken(sponsor), name + " quiz"), name, sharedVideoId);
        assertThat(submit(sponsorToken(sponsor), publicationId).get("status")).isEqualTo("LIVE");
        return publicationId;
    }

    static Map<String, Object> report(String publicationId, String kind, String surface, Integer seconds,
        String timer) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("publicationId", publicationId);
        report.put("kind", kind);
        report.put("surface", surface);
        report.put("seconds", seconds);
        report.put("timer", timer);
        return report;
    }

    static Map<String, Object> watched(String publicationId, String surface, int seconds) {
        return report(publicationId, ExposureRules.EXPOSURE, surface, seconds, ExposureRules.PLAYER);
    }

    /** The outcomes of a batch, in order. */
    @SuppressWarnings("unchecked")
    List<Object> outcomes(String token, List<Map<String, Object>> reports) {
        Map<String, Object> output = run(ExposureProcesses.REPORT, token, body("reports", reports));
        return ((List<Map<String, Object>>) output.get("results")).stream().map(r -> r.get("outcome")).toList();
    }

    Object outcome(String token, Map<String, Object> report) {
        return outcomes(token, List.of(report)).getFirst();
    }

    static Map<String, Object> exposure(String publicationId, String userId) {
        List<Map<String, Object>> rows = query("SELECT * FROM qb_exposure WHERE publication_id = ? AND user_id = ?",
            publicationId, userId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    List<Object> pushes(String token) {
        return feed(token, "JP", Map.of("push", true)).stream().map(row -> row.get("publicationId")).toList();
    }

    Map<String, Object> figures(String sponsor, String publicationId) {
        List<Map<String, Object>> rows = items("qb.sponsor.exposure", sponsorToken(sponsor), body("params",
            body("publicationId", publicationId)));
        return rows.isEmpty() ? null : rows.getFirst();
    }

    Map<String, Object> purchase(String publicationId, Long purchased) {
        return run(PURCHASE_RELAY, TestTokens.bearer(tokens, "fs13-purchase", QbPermissions.PUBLICATION_SYSTEM),
            body("publicationId", publicationId, "purchased", purchased));
    }

    void runEnds() {
        execute("UPDATE jabiz_shedlock SET lock_until = locked_at");
        jobRunner.run(endsJob, clock.instant());
    }

    String exposureAdmin() {
        return TestTokens.bearer(tokens, "fs13-exposure-admin", QbPermissions.ADMIN_EXPOSURE_READ);
    }

    static long number(Object value) {
        return value == null ? 0 : ((Number) value).longValue();
    }

    /** A new verified taker in JP; their token. */
    String newTakerToken(String name) {
        return taker(newTaker(unique("t-fs13-" + name), "JP"));
    }

    // ---- counting ---------------------------------------------------------------------------------------------------

    @Test
    void aVerticalVideoCountsOnceInTheVerticalModeAndAPushOpenedIsNotPushedAgain() {
        String provider = newSponsor(unique("s-fs13-v-provider"));
        String user = newSponsor(unique("s-fs13-v-user"));
        credit(user, 100_000);
        String vertical = live(user, "FS13 vertical", approvedVideo(provider, true));
        String own = live(user, "FS13 own videos", null);
        String takerId = newTaker(unique("t-fs13-v"), "JP");
        String token = taker(takerId);

        // The push: another's vertical video, under every condition of the feed; the feed says which cards count.
        assertThat(pushes(token)).contains(vertical).doesNotContain(own);
        assertThat(feed(token, "JP", Map.of())).filteredOn(row -> vertical.equals(row.get("publicationId")))
            .singleElement().satisfies(row -> assertThat(row).containsEntry("exposed", true));
        assertThat(feed(token, "JP", Map.of())).filteredOn(row -> own.equals(row.get("publicationId")))
            .singleElement().satisfies(row -> assertThat(row).containsEntry("exposed", false));

        // Opened (however long watched): never pushed again; the home's shelf of shorts still shows it.
        assertThat(outcome(token, report(vertical, ExposureRules.OPEN, ExposureRules.VERTICAL, null, null)))
            .isEqualTo(ExposureRules.RECORDED);
        assertThat(pushes(token)).doesNotContain(vertical);
        assertThat(feed(token, "JP", Map.of("shorts", true))).extracting(row -> row.get("publicationId"))
            .contains(vertical);
        assertThat(exposure(vertical, takerId)).containsEntry("counted_at", null)
            .satisfies(row -> assertThat(row.get("opened_at")).isNotNull());
        // Another taker still gets it.
        assertThat(pushes(newTakerToken("v-other"))).contains(vertical);

        // Nine seconds do not count; ten do, once; the start page does not count a vertical video.
        assertThat(outcome(token, watched(vertical, ExposureRules.VERTICAL, 9))).isEqualTo(ExposureRules.TOO_SHORT);
        assertThat(outcome(token, watched(vertical, ExposureRules.START, 30))).isEqualTo(ExposureRules.WRONG_SURFACE);
        assertThat(outcomes(token, List.of(watched(vertical, ExposureRules.VERTICAL, 10),
            watched(vertical, ExposureRules.VERTICAL, 12), report(vertical, ExposureRules.GO, ExposureRules.VERTICAL,
                null, null)))).containsExactly(ExposureRules.COUNTED, ExposureRules.ALREADY, ExposureRules.RECORDED);
        assertThat(outcome(token, watched(vertical, ExposureRules.VERTICAL, 60))).isEqualTo(ExposureRules.ALREADY);
        assertThat(exposure(vertical, takerId)).containsEntry("surface", "VERTICAL").containsEntry("timer", "PLAYER")
            .containsEntry("publisher_id", user).containsEntry("provider_id", provider)
            .satisfies(row -> assertThat(number(row.get("counted_sec"))).isEqualTo(10))
            .satisfies(row -> assertThat(row.get("went_to_answer_at")).isNotNull());
        assertThat(count("qb_exposure WHERE user_id = '" + takerId + "'")).isEqualTo(1);
        // One's own videos never count.
        assertThat(outcome(token, watched(own, ExposureRules.VERTICAL, 30))).isEqualTo(ExposureRules.OWN_VIDEO);
        // A publication the App does not list.
        assertThat(outcome(token, watched(UUID.randomUUID().toString(), ExposureRules.VERTICAL, 30)))
            .isEqualTo(ExposureProcesses.UNKNOWN);

        // The sponsor reads the figures of their own publication: none bought is unlimited, all charged.
        assertThat(figures(user, vertical)).containsEntry("purchased", null)
            .satisfies(row -> assertThat(number(row.get("successes"))).isEqualTo(1))
            .satisfies(row -> assertThat(number(row.get("billable"))).isEqualTo(1))
            .satisfies(row -> assertThat(number(row.get("overshoot"))).isZero())
            .satisfies(row -> assertThat(number(row.get("opens"))).isEqualTo(1))
            .satisfies(row -> assertThat(number(row.get("goes"))).isEqualTo(1));
        assertThat(figures(user, own)).isNull();
        assertThat(figures(provider, vertical)).isNull();
    }

    @Test
    void aHorizontalVideoCountsOnTheStartPageOncePerTakerAndPublication() {
        String provider = newSponsor(unique("s-fs13-h-provider"));
        String user = newSponsor(unique("s-fs13-h-user"));
        credit(user, 100_000);
        String horizontal = live(user, "FS13 horizontal", approvedVideo(provider, false));
        String takerId = newTaker(unique("t-fs13-h"), "JP");
        String token = taker(takerId);
        // Not a push: the vertical mode shows vertical videos only.
        assertThat(pushes(token)).doesNotContain(horizontal);
        assertThat(outcome(token, watched(horizontal, ExposureRules.VERTICAL, 30)))
            .isEqualTo(ExposureRules.WRONG_SURFACE);
        assertThat(outcome(token, report(horizontal, ExposureRules.EXPOSURE, ExposureRules.START, 10,
            ExposureRules.SCREEN))).isEqualTo(ExposureRules.COUNTED);
        assertThat(outcome(token, report(horizontal, ExposureRules.EXPOSURE, ExposureRules.START, 40,
            ExposureRules.SCREEN))).isEqualTo(ExposureRules.ALREADY);
        assertThat(exposure(horizontal, takerId)).containsEntry("surface", "START").containsEntry("timer", "SCREEN")
            .containsEntry("opened_at", null);
        // Another taker counts once too.
        assertThat(outcome(newTakerToken("h-other"), watched(horizontal, ExposureRules.START, 11)))
            .isEqualTo(ExposureRules.COUNTED);
        assertThat(number(figures(user, horizontal).get("successes"))).isEqualTo(2);
    }

    @Test
    void thePublisherTheProviderTheirStaffAndUnverifiedTakersDoNotCount() {
        String provider = newSponsor(unique("s-fs13-x-provider"));
        String user = newSponsor(unique("s-fs13-x-user"));
        credit(user, 100_000);
        String shared = approvedVideo(provider, true);
        String publication = live(user, "FS13 excluded", shared);
        // The publisher and the provider themselves.
        assertThat(outcome(sponsorToken(user), watched(publication, ExposureRules.VERTICAL, 30)))
            .isEqualTo(ExposureRules.EXCLUDED);
        assertThat(outcome(sponsorToken(provider), watched(publication, ExposureRules.VERTICAL, 30)))
            .isEqualTo(ExposureRules.EXCLUDED);
        // The staff of either (Q39).
        String userStaff = newTaker(unique("t-fs13-x-staff-u"), "JP");
        String providerStaff = newTaker(unique("t-fs13-x-staff-p"), "JP");
        run(StaffProcesses.ADD, sponsorToken(user), body("userId", userStaff));
        run(StaffProcesses.ADD, sponsorToken(provider), body("userId", providerStaff));
        assertThat(outcome(taker(userStaff), watched(publication, ExposureRules.VERTICAL, 30)))
            .isEqualTo(ExposureRules.EXCLUDED);
        assertThat(outcome(taker(providerStaff), watched(publication, ExposureRules.VERTICAL, 30)))
            .isEqualTo(ExposureRules.EXCLUDED);
        // Another sponsor's staff counts.
        String otherSponsor = newSponsor(unique("s-fs13-x-other"));
        String otherStaff = newTaker(unique("t-fs13-x-staff-o"), "JP");
        run(StaffProcesses.ADD, sponsorToken(otherSponsor), body("userId", otherStaff));
        assertThat(outcome(taker(otherStaff), watched(publication, ExposureRules.VERTICAL, 30)))
            .isEqualTo(ExposureRules.COUNTED);
        // An unverified address reports nothing (D-Q40-4).
        String unverified = newTaker(unique("t-fs13-x-unverified"), "JP");
        refused(ExposureProcesses.REPORT, unverifiedTokenOf(unverified, QbRoles.TAKER),
            body("reports", List.of(watched(publication, ExposureRules.VERTICAL, 30))), 403);
        assertThat(exposure(publication, unverified)).isNull();
        // A batch is bounded.
        List<Map<String, Object>> tooMany = new ArrayList<>();
        for (int i = 0; i <= ExposureRules.MAX_BATCH; i++) {
            tooMany.add(watched(publication, ExposureRules.VERTICAL, 30));
        }
        refused(ExposureProcesses.REPORT, taker(otherStaff), body("reports", tooMany), 400);
        refused(ExposureProcesses.REPORT, taker(otherStaff), body("reports", List.of()), 400);
    }

    @Test
    void theDailyCapRefusesIsRecordedForTheOperatorsAndLetsTheNextDayCount() {
        NewOperator north = newOperatorWithLead(operatorsAdmin(), unique("FS13 North"), null);
        String provider = newSponsor(unique("s-fs13-cap-provider"));
        String user = newSponsor(unique("s-fs13-cap-user"));
        run(OperatorProcesses.ASSIGN, operatorsAdmin(), body("userId", user, "operatorId", north.operatorId(),
            "reason", "F-S13"));
        credit(user, 100_000);
        String shared = approvedVideo(provider, true);
        String first = live(user, "FS13 cap 1", shared);
        String second = live(user, "FS13 cap 2", shared);
        String takerId = newTaker(unique("t-fs13-cap"), "JP");
        String token = taker(takerId);
        run("PARAM_SET", admin(), body("key", QbParams.EXPOSURE_DAILY_CAP, "value", "1"));
        try {
            assertThat(outcomes(token, List.of(watched(first, ExposureRules.VERTICAL, 10),
                watched(second, ExposureRules.VERTICAL, 10))))
                .containsExactly(ExposureRules.COUNTED, ExposureRules.DAILY_CAP);
            LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
            assertThat(exposure(second, takerId)).containsEntry("counted_at", null)
                .satisfies(row -> assertThat(number(row.get("capped_days"))).isEqualTo(1))
                .satisfies(row -> assertThat(row.get("last_capped_on").toString()).isEqualTo(today.toString()));
            // Refused again the same day: one hit a day and publication.
            clock.set(clock.instant().plusSeconds(60));
            assertThat(outcome(token, watched(second, ExposureRules.VERTICAL, 10))).isEqualTo(ExposureRules.DAILY_CAP);
            assertThat(number(exposure(second, takerId).get("capped_days"))).isEqualTo(1);

            // The operators' page of suspicious inflation: the operator of the publisher and headquarters see it.
            String operatorSession = memberSession(north.leadId(), north.operatorId());
            for (String reader : List.of(exposureAdmin(), operatorSession)) {
                assertThat(items("qb.admin.exposure-suspicious", reader, body("params", body())))
                    .filteredOn(row -> takerId.equals(row.get("userId"))).singleElement()
                    .satisfies(row -> assertThat(number(row.get("hits"))).isEqualTo(1))
                    .satisfies(row -> assertThat(number(row.get("publications"))).isEqualTo(1))
                    .satisfies(row -> assertThat(number(row.get("successes"))).isEqualTo(1));
            }
            // Another operator does not; a sponsor or a taker cannot read it.
            NewOperator south = newOperatorWithLead(operatorsAdmin(), unique("FS13 South"), null);
            assertThat(items("qb.admin.exposure-suspicious", memberSession(south.leadId(), south.operatorId()),
                body("params", body()))).noneMatch(row -> takerId.equals(row.get("userId")));
            refusedQuery("qb.admin.exposure-suspicious", sponsorToken(user));
            refusedQuery("qb.admin.exposure-suspicious", token);

            // The next UTC day counts again (a new session: the clock moved past the old one's life).
            clock.set(clock.instant().plus(Duration.ofDays(1)));
            assertThat(outcome(taker(takerId), watched(second, ExposureRules.VERTICAL, 10)))
                .isEqualTo(ExposureRules.COUNTED);
        } finally {
            run("PARAM_SET", admin(), body("key", QbParams.EXPOSURE_DAILY_CAP, "value", "200"));
        }
    }

    void refusedQuery(String id, String token) {
        client.post().uri("/api/queries/{id}", id).contentType(org.springframework.http.MediaType.APPLICATION_JSON)
            .header(org.springframework.http.HttpHeaders.AUTHORIZATION, token).bodyValue(Map.of())
            .exchange().expectStatus().isForbidden();
    }

    // ---- the pushes -------------------------------------------------------------------------------------------------

    /**
     * The pushes are the feed's template: strict mode (F-S9) applies to them as {@link TopicRules#strictAllows} says
     * (the comparison); the blocks' comparison is {@code BlockIT}'s.
     */
    @Test
    void thePushesFollowStrictMode() {
        String provider = newSponsor(unique("s-fs13-strict-provider"));
        String user = newSponsor(unique("s-fs13-strict-user"));
        credit(user, 100_000);
        String shared = approvedVideo(provider, true);
        String travel = word("fs13-travel");
        String sport = word("fs13-sport");
        String token = sponsorToken(user);
        Map<String, List<String>> topicsOf = new LinkedHashMap<>();
        Map<String, String> publications = new LinkedHashMap<>();
        for (List<String> topics : List.of(List.of(travel), List.of(sport), List.<String>of())) {
            String name = "FS13 strict " + String.join("+", topics);
            String quizId = newQuiz(token, name + " quiz");
            run("QB_QUIZ_SAVE", token, body("quizId", quizId, "title", name + " quiz", "timeLimitSec", 300));
            run(QuizTopics.SET, token, body("quizId", quizId, "topics", topics, "customTopics", List.of()));
            run("QB_QUESTION_SAVE", token, body("quizId", quizId, "stem", "Capital of France?", "points", 2,
                "options", List.of(body("text", "Paris", "correct", true), body("text", "Lyon"))));
            run("QB_QUESTION_SAVE", token, body("quizId", quizId, "stem", "Capital of Japan?", "points", 3,
                "options", List.of(body("text", "Tokyo", "correct", true), body("text", "Osaka"))));
            String versionId = (String) run("QB_QUIZ_PUBLISH_VERSION", token, body("quizId", quizId))
                .get("versionId");
            String publicationId = draft(user, versionId, name, shared);
            assertThat(submit(token, publicationId).get("status")).isEqualTo("LIVE");
            publications.put(name, publicationId);
            topicsOf.put(publicationId, topics);
        }
        Map<String, TopicRules.Word> words = Map.of(travel, new TopicRules.Word(travel, true, null),
            sport, new TopicRules.Word(sport, true, null));
        int index = 0;
        for (List<String> liked : List.of(List.of(travel), List.of(sport, travel))) {
            for (boolean strict : List.of(false, true)) {
                String taker = newTakerToken("strict-" + index++);
                run("QB_SETTINGS_SAVE", taker, body("likedTopics", liked, "strictTopics", strict));
                List<Object> expected = topicsOf.entrySet().stream()
                    .filter(e -> TopicRules.strictAllows(strict, liked, e.getValue(), words))
                    .map(e -> (Object) e.getKey()).toList();
                assertThat(pushes(taker).stream().filter(topicsOf::containsKey).toList())
                    .as(liked + " strict " + strict).containsExactlyInAnyOrderElementsOf(expected);
            }
        }
    }

    // ---- the purchase, the end and what goes beyond -----------------------------------------------------------------

    @Test
    void thePurchaseIsASubProcessOnlyForDraftsAndPublicationsOnNotEndedOnes() {
        String provider = newSponsor(unique("s-fs13-buy-provider"));
        String user = newSponsor(unique("s-fs13-buy-user"));
        credit(user, 100_000);
        String shared = approvedVideo(provider, true);
        // Never started on its own, not by the sponsor nor an administrator.
        String draftId = draft(user, version(sponsorToken(user), "FS13 buy quiz"), "FS13 buy", shared);
        refused(PublicationExposure.PURCHASED_SET, admin(), body("publicationId", draftId, "purchased", 5), 403);
        refused(PublicationExposure.PURCHASED_SET, sponsorToken(user), body("publicationId", draftId, "purchased", 5),
            403);
        // A draft (F-M8 buys at the submission): on the publication; the card takes it when it goes on.
        purchase(draftId, 5L);
        assertThat(number(row(draftId).get("exposure_purchased"))).isEqualTo(5);
        assertThat(submit(sponsorToken(user), draftId).get("status")).isEqualTo("LIVE");
        assertThat(number(card(draftId).get("exposure_purchased"))).isEqualTo(5);
        // Topped up while on: the card follows; none is unlimited again.
        purchase(draftId, 8L);
        assertThat(number(row(draftId).get("exposure_purchased"))).isEqualTo(8);
        assertThat(number(card(draftId).get("exposure_purchased"))).isEqualTo(8);
        purchase(draftId, null);
        assertThat(card(draftId).get("exposure_purchased")).isNull();
        // At least one; a copy does not take it.
        refused(PURCHASE_RELAY, TestTokens.bearer(tokens, "fs13-purchase", QbPermissions.PUBLICATION_SYSTEM),
            body("publicationId", draftId, "purchased", 0), 400);
        purchase(draftId, 3L);
        String copy = (String) run("QB_PUBLICATION_COPY", sponsorToken(user), body("publicationId", draftId))
            .get("publicationId");
        assertThat(row(copy).get("exposure_purchased")).isNull();
        // An ended one is refused.
        run("QB_PUBLICATION_ADMIN_END", TestTokens.bearer(tokens, "fs13-content-admin",
            QbPermissions.ADMIN_CONTENT_WRITE), body("publicationId", draftId, "reason", "F-S13 test"));
        assertThat(codes(refused(PURCHASE_RELAY, TestTokens.bearer(tokens, "fs13-purchase",
            QbPermissions.PUBLICATION_SYSTEM), body("publicationId", draftId, "purchased", 10), 422)))
            .containsExactly(ExposureCodes.PUBLICATION_ENDED);
    }

    @Test
    void usedUpTheJobEndsThePublicationWhatGoesBeyondIsNotChargedAndAttemptsInProgressComplete() {
        String provider = newSponsor(unique("s-fs13-end-provider"));
        String user = newSponsor(unique("s-fs13-end-user"));
        credit(user, 100_000);
        String shared = approvedVideo(provider, false);
        String limited = live(user, "FS13 end limited", shared);
        String unlimited = live(user, "FS13 end unlimited", shared);
        purchase(limited, 2L);
        // One counted, not used up: the job leaves it.
        assertThat(outcome(newTakerToken("end-1"), watched(limited, ExposureRules.START, 10)))
            .isEqualTo(ExposureRules.COUNTED);
        runEnds();
        assertThat(status(limited)).isEqualTo("LIVE");
        // An attempt in progress before the end completes after it.
        String answering = newTakerToken("end-answering");
        Map<String, Object> started = start(answering, limited);
        // Two more count before the minute's job runs: three of two (concurrency's overshoot).
        assertThat(outcome(newTakerToken("end-2"), watched(limited, ExposureRules.START, 10)))
            .isEqualTo(ExposureRules.COUNTED);
        assertThat(outcome(newTakerToken("end-3"), watched(limited, ExposureRules.START, 10)))
            .isEqualTo(ExposureRules.COUNTED);
        for (int i = 0; i < 3; i++) {
            outcome(newTakerToken("end-unlimited-" + i), watched(unlimited, ExposureRules.START, 10));
        }

        runEnds();
        assertThat(row(limited)).containsEntry("status", "ENDED").containsEntry("end_reason", "EXPOSURE")
            .containsEntry("paused_by", null).containsEntry("admin_reason", null);
        assertThat(card(limited)).containsEntry("status", "ENDED");
        // None bought: unlimited, never ended for it.
        assertThat(status(unlimited)).isEqualTo("LIVE");
        // A second run finds nothing more.
        long versions = count("qb_publication_version");
        runEnds();
        assertThat(count("qb_publication_version")).isEqualTo(versions);
        // Ended publications no longer count, nor take new attempts; the attempt in progress completes.
        assertThat(outcome(newTakerToken("end-late"), watched(limited, ExposureRules.START, 10)))
            .isEqualTo(ExposureRules.NOT_LIVE);
        submit(answering, (String) started.get("attemptId"), answers(true, true));
        assertThat(attempt((String) started.get("attemptId")).get("submitted_at")).isNotNull();

        // Beyond the purchase: recorded, not charged (F-M8 settles min(successes, purchased)).
        assertThat(figures(user, limited))
            .satisfies(row -> assertThat(number(row.get("purchased"))).isEqualTo(2))
            .satisfies(row -> assertThat(number(row.get("successes"))).isEqualTo(3))
            .satisfies(row -> assertThat(number(row.get("billable"))).isEqualTo(2))
            .satisfies(row -> assertThat(number(row.get("overshoot"))).isEqualTo(1));
        assertThat(ExposureRules.billable(3, 2L)).isEqualTo(2);

        // The sponsor is told as of any end: the reason says the exposures are used up.
        deliverEvents();
        List<Map<String, Object>> mails = query("SELECT template, params::text AS params FROM sys_mail_message "
            + "WHERE user_id = ?::uuid AND template = 'qb.publication.ended'", user);
        assertThat(mails).singleElement().satisfies(mail -> assertThat(mail.get("params").toString())
            .containsAnyOf("are used up", "曝光已用完", "表示回数を使い切りました"));
    }

    @Test
    void theDailyReconciliationFindsWhatWentTooFarAndWhatCountedAfterTheEnd() {
        String provider = newSponsor(unique("s-fs13-check-provider"));
        String user = newSponsor(unique("s-fs13-check-user"));
        credit(user, 100_000);
        String shared = approvedVideo(provider, false);
        String within = live(user, "FS13 check within", shared);
        String beyond = live(user, "FS13 check beyond", shared);
        purchase(within, 1L);
        purchase(beyond, 1L);
        // Within the tolerance: 21 of 1 (max(20, 1)); beyond it: 22. Rows as concurrent reports would have left them.
        insertSuccesses(within, user, provider, 21, clock.instant());
        runCheck();
        assertThat(task()).isNull();
        insertSuccesses(beyond, user, provider, 22, clock.instant());
        runCheck();
        assertThat(task()).isNotNull();
        // Counted more than a minute after its end: a difference too (a new day's run).
        clock.set(clock.instant().plus(Duration.ofDays(1)));
        String ended = live(user, "FS13 check ended", shared);
        run("QB_PUBLICATION_ADMIN_END", TestTokens.bearer(tokens, "fs13-content-admin",
            QbPermissions.ADMIN_CONTENT_WRITE), body("publicationId", ended, "reason", "F-S13 test"));
        runCheckOnly(within, beyond);
        insertSuccesses(ended, user, provider, 1, clock.instant().plus(Duration.ofMinutes(2)));
        runCheck();
        assertThat(task()).isNotNull();
    }

    /** As {@link #runCheck}, with the earlier differences put right first (so only the new one is found). */
    void runCheckOnly(String... fixed) {
        for (String publicationId : fixed) {
            execute("DELETE FROM qb_exposure WHERE publication_id = ?", publicationId);
        }
        runCheck();
        assertThat(task()).isNull();
    }

    void insertSuccesses(String publicationId, String publisher, String provider, int n, Instant at) {
        for (int i = 0; i < n; i++) {
            execute("INSERT INTO qb_exposure (exposure_id, publication_id, user_id, publisher_id, provider_id, "
                + "counted_at, surface, counted_sec, timer, capped_days, created_at, version) VALUES (?, ?, ?, ?, ?, "
                + "?, 'START', 10, 'SCREEN', 0, ?, 1)", UUID.randomUUID().toString(), publicationId,
                "fs13-check-" + UUID.randomUUID(), publisher, provider, java.sql.Timestamp.from(at),
                java.sql.Timestamp.from(at));
        }
    }

    /** The runs of the check so far: each at a scheduled time of its own (a time that ran is not run again). */
    private int checks;

    void runCheck() {
        execute("UPDATE jabiz_shedlock SET lock_until = locked_at");
        jobRunner.run(checkJob, clock.instant().plusSeconds(checks++));
    }

    /** Today's reconciliation task, if any. */
    Object task() {
        List<Map<String, Object>> tasks = query("SELECT task_id FROM sys_task_version WHERE source_key = ?",
            ExposureCheck.source(clock.instant()));
        return tasks.isEmpty() ? null : tasks.getFirst().get("task_id");
    }
}
