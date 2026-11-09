package com.jabiz.quizbuks.it;

import com.jabiz.job.JobDefinition;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.content.ForeignVideos;
import com.jabiz.quizbuks.publishing.PublicationVersions;
import com.jabiz.quizbuks.region.RegionCodes;
import com.jabiz.quizbuks.region.RegionRefusals;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.http.HttpHeaders;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.server.WebFilter;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-S11 (docs/quizbuks/plans/impl/F-S11.md): a publication with a video mainland China cannot play is not answered from
 * a mainland address, unless headquarters made an exception. The client's address is the test's own (a filter sets
 * the connection's address); the address database is the test's (documentation ranges: 192.0.2.0/24 is mainland
 * China, 198.51.100.0/24 Japan, 203.0.113.0/25 Hong Kong); a private address is unknown to it.
 */
@SpringBootTest(properties = {"jabiz.geoip.database=src/test/resources/geoip/test-countries.csv",
    "jabiz.security.trusted-proxies=10.0.0.0/8"})
class ForeignVideoIT extends PlayItSupport {

    static final String MAINLAND = "192.0.2.10";
    static final String JAPAN = "198.51.100.3";
    static final String HONG_KONG = "203.0.113.9";
    static final String UNKNOWN = "10.0.0.5";

    static final String YOUTUBE = "https://www.youtube.com/watch?v=dQw4w9WgXcQ";
    static final String BILIBILI = "https://www.bilibili.com/video/BV1GJ411x7h7/?spm_id_from=333.1007";

    /** The address of the test's next requests; none: the connection has no address (unknown). */
    static volatile String address = JAPAN;

    @Autowired
    @Qualifier("qbRegionRefusalsJob")
    JobDefinition<RegionRefusals.FlushInput> refusalsJob;

    /** The connection's address of every request: the test's {@link #address} (set first, before any filter). */
    @TestConfiguration
    static class Connection {
        @Bean
        @Order(Ordered.HIGHEST_PRECEDENCE)
        WebFilter itConnectionAddress() {
            return (exchange, chain) -> {
                String from = address;
                return chain.filter(from == null ? exchange : exchange.mutate().request(request -> request
                    .remoteAddress(new InetSocketAddress(from, 40000))).build());
            };
        }
    }

    @BeforeEach
    void fromJapan() {
        address = JAPAN;
    }

    @AfterEach
    void onlyInserted() {
        address = JAPAN;
        assertNothingUpdated();
    }

    /** Headquarters' content administrator as far as this rule goes. */
    private String regionAdmin() {
        return TestTokens.bearer(tokens, "region-admin", QbPermissions.ADMIN_REGION_READ,
            QbPermissions.ADMIN_REGION_WRITE, QbPermissions.ADMIN_REGION_MONITOR);
    }

    /** A complete quiz of the sponsor's with a video material of {@code url} (none when null), saved as a version. */
    private String videoVersion(String token, String title, String url) {
        String quizId = newQuiz(token, title);
        run("QB_QUIZ_SAVE", token, body("quizId", quizId, "title", title, "timeLimitSec", 300));
        if (url != null) {
            run("QB_MATERIAL_SAVE", token, body("quizId", quizId, "kind", "VIDEO_LINK", "title", "Film", "url", url));
        }
        run("QB_QUESTION_SAVE", token, body("quizId", quizId, "stem", "Capital of France?", "points", 2,
            "options", List.of(body("text", "Paris", "correct", true), body("text", "Lyon"))));
        run("QB_QUESTION_SAVE", token, body("quizId", quizId, "stem", "Capital of Japan?", "points", 3,
            "options", List.of(body("text", "Tokyo", "correct", true), body("text", "Osaka"))));
        return (String) run("QB_QUIZ_PUBLISH_VERSION", token, body("quizId", quizId)).get("versionId");
    }

    /** A publication of the sponsor's aimed at everybody, live now for a week, of a quiz with a video of {@code url}. */
    private String videoPublication(String sponsor, String name, String url) {
        String token = sponsorToken(sponsor);
        String versionId = videoVersion(token, name + " quiz", url);
        Instant now = clock.instant();
        Map<String, Object> save = complete(versionId, name, now, now.plus(Duration.ofDays(7)));
        save.put("maxWinners", 1000);
        save.put("regions", List.of("GLOBAL"));
        String publicationId = (String) run("QB_PUBLICATION_SAVE", token, save).get("publicationId");
        assertThat(run("QB_PUBLICATION_SUBMIT", token, body("publicationId", publicationId)).get("status"))
            .isEqualTo("LIVE");
        return publicationId;
    }

    private Object foreignOf(String publicationId) {
        return card(publicationId).get("foreign_video");
    }

    private List<Object> startRefused(String token, String publicationId) {
        return codes(refused("QB_ATTEMPT_START", token, body("publicationId", publicationId), 422));
    }

    @Test
    void theCardFreezesWhetherItsVersionHasAVideoMainlandChinaCannotPlay() {
        autoApprove(true);
        String sponsor = newSponsor("s-fs11-card");
        credit(sponsor, 10_000);
        assertThat(foreignOf(videoPublication(sponsor, "FS11 YouTube", YOUTUBE))).isEqualTo(true);
        assertThat(foreignOf(videoPublication(sponsor, "FS11 unknown site", "https://video.example/capitals")))
            .isEqualTo(true);
        String bilibili = videoPublication(sponsor, "FS11 Bilibili", BILIBILI);
        assertThat(foreignOf(bilibili)).isEqualTo(false);
        assertThat(foreignOf(videoPublication(sponsor, "FS11 no video", null))).isEqualTo(false);

        // A switch to a version with a foreign video (Q51) makes the card foreign.
        String token = sponsorToken(sponsor);
        String quizId = String.valueOf(latest("qb_quiz_version_version", "version_id", "version_id = '"
            + card(bilibili).get("version_id") + "'").getFirst().get("quiz_id"));
        run("QB_MATERIAL_SAVE", token, body("quizId", quizId, "kind", "VIDEO_LINK", "title", "More", "url",
            "https://vimeo.com/76979871"));
        String second = (String) run("QB_QUIZ_PUBLISH_VERSION", token, body("quizId", quizId)).get("versionId");
        run(PublicationVersions.SWITCH, token, body("publicationId", bilibili, "versionId", second));
        assertThat(card(bilibili)).containsEntry("version_id", java.util.UUID.fromString(second))
            .containsEntry("foreign_video", true);
    }

    @Test
    void aStartFromMainlandChinaIsRefusedWhateverTheProfileSays() {
        autoApprove(true);
        String sponsor = newSponsor("s-fs11-start");
        credit(sponsor, 10_000);
        String foreign = videoPublication(sponsor, "FS11 start", YOUTUBE);
        String mainlandOk = videoPublication(sponsor, "FS11 start Bilibili", BILIBILI);

        // A taker whose profile says Japan, from a mainland address: refused, with an explanation.
        String fromJapanProfile = taker(newTaker("t-fs11-jp-profile", "JP"));
        address = MAINLAND;
        Map<String, Object> refusal = refused("QB_ATTEMPT_START", fromJapanProfile, body("publicationId", foreign),
            422);
        assertThat(codes(refusal)).containsExactly(RegionCodes.REGION_VIDEO);
        assertThat(String.valueOf(violations(refusal).getFirst().get("message"))).contains("cannot be played");
        // A video mainland China plays is fine from there.
        assertThat(start(fromJapanProfile, mainlandOk)).containsEntry("resumed", false);
        // From Japan, the same taker starts it.
        address = JAPAN;
        assertThat(start(fromJapanProfile, foreign)).containsEntry("resumed", false);

        // A profile in mainland China does not matter: from Hong Kong or Japan the start goes ahead (A3-19).
        String chineseProfile = taker(newTaker("t-fs11-cn-profile", "CN"));
        address = HONG_KONG;
        assertThat(start(chineseProfile, foreign)).containsEntry("resumed", false);
        // The address behind a trusted proxy counts; the proxy's own does not.
        String behindProxy = taker(newTaker("t-fs11-proxy", "JP"));
        address = UNKNOWN;
        assertThat(codes(client.post().uri("/api/processes/QB_ATTEMPT_START/latest")
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
            .header(HttpHeaders.AUTHORIZATION, behindProxy).header("X-Forwarded-For", MAINLAND)
            .bodyValue(body("publicationId", foreign)).exchange().expectStatus().isEqualTo(422).expectBody(MAP)
            .returnResult().getResponseBody())).containsExactly(RegionCodes.REGION_VIDEO);
    }

    /**
     * F-S15: a video file or a page of another site is foreign unless headquarters lists its host in the controlled
     * parameter (four eyes): then its card says so and mainland China answers it; nothing else is built in.
     */
    @Test
    void aListedHostsVideoFileIsAnsweredFromMainlandChina() {
        autoApprove(true);
        String sponsor = newSponsor("s-fs15-host");
        credit(sponsor, 10_000);
        String file = "https://videos.quizbuks.test/quizbuks-demo-30s.mp4";
        String before = videoPublication(sponsor, "FS15 before", file);
        assertThat(foreignOf(before)).isEqualTo(true);
        Map<String, Object> change = run("CONTROL_CHANGE_PROPOSE", proposer(), body("targetEntity", "SysParam",
            "values", Map.of("paramKey", QbParams.VIDEO_MAINLAND_SITES, "value",
                ForeignVideos.DEFAULT_SITES + ",videos.quizbuks.test"), "reason", "Our own video server"));
        run("CONTROL_CHANGE_PUBLISH", financeAdmin(), body("changeId", change.get("changeId")));
        try {
            String listed = videoPublication(sponsor, "FS15 listed", file);
            String page = videoPublication(sponsor, "FS15 listed page", "https://videos.quizbuks.test/player/1");
            String elsewhere = videoPublication(sponsor, "FS15 elsewhere", "https://cdn.other.test/clip.mp4");
            assertThat(card(listed)).containsEntry("foreign_video", false).containsEntry("video", file);
            assertThat(foreignOf(page)).isEqualTo(false);
            assertThat(foreignOf(elsewhere)).isEqualTo(true);
            // Frozen: the card that went on before the host was listed stays as it was.
            assertThat(foreignOf(before)).isEqualTo(true);
            address = MAINLAND;
            assertThat(start(taker(newTaker("t-fs15-mainland", "CN")), listed)).containsEntry("resumed", false);
            assertThat(startRefused(taker(newTaker("t-fs15-mainland-2", "CN")), elsewhere))
                .containsExactly(RegionCodes.REGION_VIDEO);
        } finally {
            address = JAPAN;
            Map<String, Object> back = run("CONTROL_CHANGE_PROPOSE", proposer(), body("targetEntity", "SysParam",
                "values", Map.of("paramKey", QbParams.VIDEO_MAINLAND_SITES, "value", ForeignVideos.DEFAULT_SITES),
                "reason", "Back"));
            run("CONTROL_CHANGE_PUBLISH", financeAdmin(), body("changeId", back.get("changeId")));
        }
    }

    @Test
    void anUnknownAddressCountsAsTheParameterSays() {
        autoApprove(true);
        String sponsor = newSponsor("s-fs11-unknown");
        credit(sponsor, 10_000);
        String foreign = videoPublication(sponsor, "FS11 unknown", YOUTUBE);
        String plain = videoPublication(sponsor, "FS11 unknown plain", null);
        String taker = taker(newTaker("t-fs11-unknown", "JP"));
        // No address at all, or a private one: unknown, mainland China by default.
        address = null;
        assertThat(startRefused(taker, foreign)).containsExactly(RegionCodes.REGION_VIDEO);
        address = UNKNOWN;
        assertThat(startRefused(taker, foreign)).containsExactly(RegionCodes.REGION_VIDEO);
        // Not a foreign video: nothing to refuse, whatever the address.
        assertThat(start(taker, plain)).containsEntry("resumed", false);
        // Unknown counting as Japan (as the end-to-end tests set it): the start goes ahead.
        run("PARAM_SET", admin(), body("key", QbParams.REGION_UNKNOWN_IP_COUNTRY, "value", "JP"));
        try {
            assertThat(start(taker, foreign)).containsEntry("resumed", false);
        } finally {
            run("PARAM_SET", admin(), body("key", QbParams.REGION_UNKNOWN_IP_COUNTRY, "value", "CN"));
        }
    }

    @Test
    void headquartersExceptionsLetMainlandChinaAnswerUntilRevokedOrPastTheirLastDay() {
        autoApprove(true);
        String sponsor = newSponsor("s-fs11-exception");
        String other = newSponsor("s-fs11-exception-2");
        credit(sponsor, 10_000);
        credit(other, 10_000);
        String foreign = videoPublication(sponsor, "FS11 exception", YOUTUBE);
        String othersFirst = videoPublication(other, "FS11 sponsor exception", YOUTUBE);
        address = MAINLAND;
        assertThat(startRefused(taker(newTaker("t-fs11-x0", "JP")), foreign)).containsExactly(RegionCodes.REGION_VIDEO);

        // Wrong inputs: one target only, an existing one, a reason, a last day not past.
        String admin = regionAdmin();
        LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        assertThat(codes(refused("QB_REGION_EXCEPTION_ADD", admin, body("kind", "PUBLICATION", "publicationId",
            foreign, "sponsorId", sponsor, "reason", "Both"), 422))).containsExactly(RegionCodes.EXCEPTION_TARGET);
        assertThat(codes(refused("QB_REGION_EXCEPTION_ADD", admin, body("kind", "PUBLICATION", "publicationId",
            java.util.UUID.randomUUID().toString(), "reason", "Nobody's"), 422)))
            .containsExactly(RegionCodes.EXCEPTION_UNKNOWN_TARGET);
        assertThat(codes(refused("QB_REGION_EXCEPTION_ADD", admin, body("kind", "PUBLICATION", "publicationId",
            foreign, "reason", "Late", "expiresOn", today.minusDays(1).toString()), 422)))
            .containsExactly(RegionCodes.EXCEPTION_EXPIRY_PAST);
        refused("QB_REGION_EXCEPTION_ADD", admin, body("kind", "PUBLICATION", "publicationId", foreign,
            "reason", " "), 400);
        // Only headquarters: a taker, a sponsor or an operator holds no such permission.
        refused("QB_REGION_EXCEPTION_ADD", sponsorToken(sponsor), body("kind", "PUBLICATION", "publicationId",
            foreign, "reason", "Mine"), 403);

        // For the publication: mainland China answers it.
        String exceptionId = (String) run("QB_REGION_EXCEPTION_ADD", admin, body("kind", "PUBLICATION",
            "publicationId", foreign, "reason", "The video is mirrored in China")).get("exceptionId");
        assertThat(start(taker(newTaker("t-fs11-x1", "JP")), foreign)).containsEntry("resumed", false);
        // Revoked: no more; and a revoked one changes no more.
        run("QB_REGION_EXCEPTION_REVOKE", admin, body("exceptionId", exceptionId, "reason", "The mirror is gone"));
        assertThat(startRefused(taker(newTaker("t-fs11-x2", "JP")), foreign)).containsExactly(RegionCodes.REGION_VIDEO);
        assertThat(codes(refused("QB_REGION_EXCEPTION_EXPIRY_SET", admin, body("exceptionId", exceptionId,
            "reason", "Again"), 422))).containsExactly(RegionCodes.EXCEPTION_REVOKED);

        // For a sponsor, until today: every publication of theirs, the ones to come too, then no more.
        String sponsorException = (String) run("QB_REGION_EXCEPTION_ADD", admin, body("kind", "SPONSOR",
            "sponsorId", other, "reason", "Partner with a licence", "expiresOn", today.toString())).get("exceptionId");
        String othersSecond = videoPublication(other, "FS11 sponsor exception 2", YOUTUBE);
        assertThat(start(taker(newTaker("t-fs11-x3", "JP")), othersFirst)).containsEntry("resumed", false);
        assertThat(start(taker(newTaker("t-fs11-x3", "JP")), othersSecond)).containsEntry("resumed", false);
        Instant before = clock.instant();
        clock.set(before.plus(Duration.ofDays(1)));
        try {
            assertThat(startRefused(taker(newTaker("t-fs11-x4", "JP")), othersFirst))
                .containsExactly(RegionCodes.REGION_VIDEO);
            // A new last day brings it back.
            run("QB_REGION_EXCEPTION_EXPIRY_SET", regionAdmin(), body("exceptionId", sponsorException, "expiresOn",
                today.plusDays(10).toString(), "reason", "Licence extended"));
            assertThat(start(taker(newTaker("t-fs11-x4", "JP")), othersFirst)).containsEntry("resumed", false);
        } finally {
            clock.set(before);
        }

        // Headquarters' list says which are in force; every change went through the audit.
        List<Map<String, Object>> list = items("qb.admin.region-exceptions", admin, body("params",
            body("at", clock.instant().toString()), "limit", 50));
        assertThat(list).filteredOn(row -> row.get("exceptionId").equals(exceptionId)).singleElement()
            .satisfies(row -> assertThat(row).containsEntry("revoked", true).containsEntry("inForce", false)
                .containsEntry("revokeReason", "The mirror is gone").containsEntry("publicationName", "FS11 exception")
                .containsEntry("foreignVideo", true));
        assertThat(list).filteredOn(row -> row.get("exceptionId").equals(sponsorException)).singleElement()
            .satisfies(row -> assertThat(row).containsEntry("inForce", true).containsEntry("sponsorId", other)
                .containsEntry("reason", "Licence extended"));
        assertThat(query("SELECT action FROM sys_audit_record WHERE entity_type = 'QbRegionException' "
            + "AND entity_id IN (?, ?)", exceptionId, sponsorException)).hasSize(4);
    }

    /**
     * The requester, 2026-11-07: an operator sets the exceptions of its own sponsors (a publication's through its
     * sponsor), sees only theirs, and neither another operator's nor the platform-wide refusal counts; headquarters
     * sets and sees every sponsor's.
     */
    @Test
    void anOperatorSetsAndSeesItsOwnSponsorsExceptionsOnly() {
        autoApprove(true);
        String hq = operatorsAdmin();
        NewOperator north = newOperatorWithLead(hq, unique("FS11 North"), null);
        NewOperator south = newOperatorWithLead(hq, unique("FS11 South"), null);
        String mine = newSponsor(unique("s-fs11-op-mine"));
        String theirs = newSponsor(unique("s-fs11-op-theirs"));
        credit(mine, 10_000);
        credit(theirs, 10_000);
        run(com.jabiz.quizbuks.operator.OperatorProcesses.ASSIGN, hq, body("userId", mine, "operatorId",
            north.operatorId(), "reason", "F-S11"));
        run(com.jabiz.quizbuks.operator.OperatorProcesses.ASSIGN, hq, body("userId", theirs, "operatorId",
            south.operatorId(), "reason", "F-S11"));
        String minePublication = videoPublication(mine, "FS11 operator mine", YOUTUBE);
        String theirPublication = videoPublication(theirs, "FS11 operator theirs", YOUTUBE);
        String northSession = memberSession(north.leadId(), north.operatorId());
        String southSession = memberSession(south.leadId(), south.operatorId());

        // Its own sponsor's publication: added, and mainland China answers it.
        String forPublication = (String) run("QB_REGION_EXCEPTION_ADD", northSession, body("kind", "PUBLICATION",
            "publicationId", minePublication, "reason", "Licensed in China")).get("exceptionId");
        address = MAINLAND;
        assertThat(start(taker(newTaker("t-fs11-op-1", "JP")), minePublication)).containsEntry("resumed", false);
        address = JAPAN;
        String forSponsor = (String) run("QB_REGION_EXCEPTION_ADD", northSession, body("kind", "SPONSOR",
            "sponsorId", mine, "reason", "Partner")).get("exceptionId");
        assertThat(query("SELECT owner_id FROM qb_region_exception WHERE exception_id IN (?, ?)", forPublication,
            forSponsor)).extracting(row -> row.get("owner_id")).containsOnly(mine);

        // Another operator's sponsor, or its publication: not found, as if they did not exist.
        refused("QB_REGION_EXCEPTION_ADD", northSession, body("kind", "PUBLICATION", "publicationId",
            theirPublication, "reason", "Not mine"), 404);
        refused("QB_REGION_EXCEPTION_ADD", northSession, body("kind", "SPONSOR", "sponsorId", theirs,
            "reason", "Not mine"), 404);
        // A wrong input is told as such, before anything is read.
        assertThat(codes(refused("QB_REGION_EXCEPTION_ADD", northSession, body("kind", "SPONSOR",
            "reason", "Nobody"), 422))).containsExactly(RegionCodes.EXCEPTION_TARGET);
        // Headquarters' exception of the other sponsor: South sees and changes it, North does not.
        String hqException = (String) run("QB_REGION_EXCEPTION_ADD", regionAdmin(), body("kind", "SPONSOR",
            "sponsorId", theirs, "reason", "Headquarters' decision")).get("exceptionId");
        refused("QB_REGION_EXCEPTION_EXPIRY_SET", northSession, body("exceptionId", hqException, "reason", "Mine?"),
            404);
        refused("QB_REGION_EXCEPTION_REVOKE", northSession, body("exceptionId", hqException, "reason", "Mine?"), 404);
        refused("QB_REGION_EXCEPTION_REVOKE", southSession, body("exceptionId", forSponsor, "reason", "Mine?"), 404);
        run("QB_REGION_EXCEPTION_EXPIRY_SET", southSession, body("exceptionId", hqException, "expiresOn",
            LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC).plusDays(30).toString(), "reason", "A month"));
        run("QB_REGION_EXCEPTION_REVOKE", northSession, body("exceptionId", forPublication, "reason",
            "The sponsor's exception covers it"));

        // Each sees its own sponsors' only; headquarters every one.
        Map<String, Object> request = body("params", body("at", clock.instant().toString()), "limit", 200);
        assertThat(items("qb.admin.region-exceptions", northSession, request)).extracting(row -> row.get("exceptionId"))
            .contains(forPublication, forSponsor).doesNotContain(hqException);
        assertThat(items("qb.admin.region-exceptions", southSession, request)).extracting(row -> row.get("exceptionId"))
            .contains(hqException).doesNotContain(forPublication, forSponsor);
        assertThat(items("qb.admin.region-exceptions", regionAdmin(), request))
            .extracting(row -> row.get("exceptionId")).contains(forPublication, forSponsor, hqException);
        assertThat(items("qb.admin.region-exceptions", northSession, request))
            .filteredOn(row -> forPublication.equals(row.get("exceptionId"))).singleElement()
            .satisfies(row -> assertThat(row).containsEntry("ownerId", mine).containsEntry("revoked", true));
        // F-S1b: the platform filters by the sponsor's operator now.
        assertThat(items("qb.admin.region-exceptions", regionAdmin(), body("params", body("at",
            clock.instant().toString()), "filters", List.of(body("field", "operatorId", "op", "eq", "value",
            south.operatorId())), "limit", 200))).extracting(row -> row.get("exceptionId"))
            .contains(hqException).doesNotContain(forPublication, forSponsor);
        assertThat(rows(com.jabiz.quizbuks.region.QbRegion.defaultDataset("QbRegionException"), northSession))
            .extracting(row -> row.get("ownerId")).containsOnly(mine);
        // The refusal counts are platform-wide: headquarters' only.
        client.post().uri("/api/queries/qb.admin.region-refusals").header(HttpHeaders.AUTHORIZATION, northSession)
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON).bodyValue(body("params", body()))
            .exchange().expectStatus().isForbidden();
        assertThat(query("SELECT action FROM sys_audit_record WHERE entity_type = 'QbRegionException' "
            + "AND entity_id IN (?, ?, ?)", forPublication, forSponsor, hqException)).hasSize(5);
    }

    @Test
    void thePagesHideWhatMainlandChinaCannotAnswerAndSayWhy() {
        autoApprove(true);
        String sponsor = newSponsor("s-fs11-pages");
        credit(sponsor, 10_000);
        String foreign = videoPublication(sponsor, "FS11 pages", YOUTUBE);
        String plain = videoPublication(sponsor, "FS11 pages Bilibili", BILIBILI);
        String token = taker(newTaker("t-fs11-pages", "JP"));
        // The page says where the caller is; the templates go by that parameter only, never the address.
        address = JAPAN;
        assertThat(feed(token, null, Map.of("mainlandChina", true))).extracting(row -> row.get("publicationId"))
            .contains(plain).doesNotContain(foreign);
        assertThat(feed(token, null, Map.of())).extracting(row -> row.get("publicationId")).contains(plain, foreign);
        Map<String, Object> page = items("qb.app.publication", token, body("params", body("publicationId", foreign,
            "at", clock.instant().toString(), "mainlandChina", true))).getFirst();
        assertThat(page).containsEntry("regionBlocked", true).containsEntry("canStart", false);
        assertThat(items("qb.app.publication", token, body("params", body("publicationId", foreign,
            "at", clock.instant().toString()))).getFirst()).containsEntry("regionBlocked", false)
            .containsEntry("canStart", true);
        assertThat(publicRow(foreign, true)).containsEntry("regionBlocked", true);
        assertThat(publicRow(foreign, false)).containsEntry("regionBlocked", false);
        assertThat(publicRow(plain, true)).containsEntry("regionBlocked", false);

        // An exception shows it again.
        run("QB_REGION_EXCEPTION_ADD", regionAdmin(), body("kind", "SPONSOR", "sponsorId", sponsor, "reason",
            "Allowed"));
        assertThat(feed(token, null, Map.of("mainlandChina", true))).extracting(row -> row.get("publicationId"))
            .contains(plain, foreign);
        assertThat(publicRow(foreign, true)).containsEntry("regionBlocked", false);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> publicRow(String publicationId, boolean mainland) {
        List<Map<String, Object>> rows = (List<Map<String, Object>>) client.get().uri(b -> b
                .path("/api/public/queries/qb.public.publication").queryParam("p.publicationId", publicationId)
                .queryParam("p.mainlandChina", mainland).queryParam("p.at", clock.instant().toString()).build())
            .exchange().expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody().get("items");
        return rows.getFirst();
    }

    @Test
    void refusalsAreCountedByDayWithoutWhoOrWhere() {
        autoApprove(true);
        String sponsor = newSponsor("s-fs11-count");
        credit(sponsor, 10_000);
        String foreign = videoPublication(sponsor, "FS11 count", YOUTUBE);
        execute("UPDATE jabiz_shedlock SET lock_until = locked_at");
        jobRunner.run(refusalsJob, clock.instant());
        LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        BigDecimal before = refusedOn(today);
        address = MAINLAND;
        startRefused(taker(newTaker("t-fs11-count-1", "JP")), foreign);
        startRefused(taker(newTaker("t-fs11-count-2", "JP")), foreign);
        execute("UPDATE jabiz_shedlock SET lock_until = locked_at");
        jobRunner.run(refusalsJob, clock.instant().plusSeconds(1));
        assertThat(refusedOn(today)).isEqualByComparingTo(before.add(BigDecimal.valueOf(2)));
        assertThat(items("qb.admin.region-refusals", regionAdmin(), body("params", body("from", today.toString()))))
            .singleElement().satisfies(row -> assertThat(row).containsEntry("day", today.toString()));
    }

    private static BigDecimal refusedOn(LocalDate day) {
        List<Map<String, Object>> rows = query("SELECT refused FROM qb_region_refusal_day WHERE day = ?", day);
        return rows.isEmpty() ? BigDecimal.ZERO : (BigDecimal) rows.getFirst().get("refused");
    }

    /**
     * The migration's backfill: a card without the value gets its version's, by the migration's own SQL, which reads
     * links as {@code VideoEmbeds} does for the sites mainland China plays by default (every case of VideoEmbedsTest).
     */
    @Test
    void theBackfillReadsTheVersionsAsTheApplicationDoes() throws IOException {
        for (String url : LINKS) {
            boolean mainland = !ForeignVideos.foreign(List.of(url), ForeignVideos.sites(null));
            Object sql = query("SELECT quizbuks_mainland_video_link(?) AS ok", url).getFirst().get("ok");
            assertThat(sql).as(url).isEqualTo(mainland);
        }
        autoApprove(true);
        String sponsor = newSponsor("s-fs11-backfill");
        credit(sponsor, 10_000);
        String youtube = videoPublication(sponsor, "FS11 backfill YouTube", YOUTUBE);
        String bilibili = videoPublication(sponsor, "FS11 backfill Bilibili", BILIBILI);
        String none = videoPublication(sponsor, "FS11 backfill none", null);
        execute("UPDATE qb_publication_card SET foreign_video = NULL WHERE publication_id IN (?::uuid, ?::uuid, "
            + "?::uuid)", youtube, bilibili, none);
        execute(backfill());
        assertThat(foreignOf(youtube)).isEqualTo(true);
        assertThat(foreignOf(bilibili)).isEqualTo(false);
        assertThat(foreignOf(none)).isEqualTo(false);
    }

    /** The backfill statement of the migration (its number is not part of the test). */
    private static String backfill() throws IOException {
        Resource[] found = new PathMatchingResourcePatternResolver()
            .getResources("classpath*:db/migration/V*__quizbuks_foreign_videos.sql");
        assertThat(found).hasSize(1);
        String sql = found[0].getContentAsString(StandardCharsets.UTF_8);
        int from = sql.indexOf("UPDATE qb_publication_card c");
        String end = "WHERE c.foreign_video IS NULL;";
        return sql.substring(from, sql.indexOf(end, from) + end.length() - 1);
    }

    /** The links of VideoEmbedsTest, the players and the others, and a few more. */
    static final List<String> LINKS = List.of(
        "https://www.youtube.com/watch?v=dQw4w9WgXcQ", "http://youtu.be/dQw4w9WgXcQ?si=abc",
        "https://www.youtube.com/shorts/dQw4w9WgXcQ", "https://vimeo.com/76979871",
        "https://www.bilibili.com/video/BV1GJ411x7h7", "https://www.bilibili.com/video/BV1GJ411x7h7/?spm_id_from=333.1007",
        "https://m.bilibili.com/video/BV1GJ411x7h7", "HTTPS://WWW.BILIBILI.COM/video/BV1GJ411x7h7",
        "https://player.bilibili.com/player.html?bvid=BV1GJ411x7h7&autoplay=0", "https://www.nicovideo.jp/watch/sm9",
        "https://nico.ms/sm9", "https://www.dailymotion.com/video/x7tgad0", "https://dai.ly/x7tgad0",
        "https://v.youku.com/v_show/id_XNTk4MjQzMzQ0OA==.html?spm=a2h0c", "https://player.youku.com/embed/XNTk4MjQzMzQ0OA==",
        "https://v.qq.com/x/page/x0029zdtwz6.html", "https://v.qq.com/x/cover/mzc00200abcdefg/x0029zdtwz6.html",
        "https://v.qq.com/txp/iframe/player.html?vid=x0029zdtwz6", "  https://v.qq.com/x/page/x0029zdtwz6.html  ",
        "https://www.bilibili.com/", "https://www.bilibili.com/video/av170001", "https://www.bilibili.com/video/BV1GJ411x7h",
        "https://space.bilibili.com/BV1GJ411x7h7", "https://b23.tv/BV1GJ411x7h7",
        "https://player.bilibili.com/player.html?aid=170001", "https://www.bilibili.com/video/BV1GJ411x7h7/x",
        "https://v.youku.com/v_show/id_XNTk4MjQzMzQ0OA==", "https://v.youku.com/v_show/id_<x>.html",
        "https://v.qq.com/x/page/x0029zdtwz6", "https://v.qq.com/x/cover/mzc00200abcdefg.html",
        "https://v.qq.com.evil.example/x/page/x0029zdtwz6.html", "https://evil.example/txp/iframe/player.html?vid=x0029zdtwz6",
        "https://user@www.bilibili.com/video/BV1GJ411x7h7", "https://www.bilibili.com:8443/video/BV1GJ411x7h7",
        "ftp://www.bilibili.com/video/BV1GJ411x7h7", "https://video.example/capitals", "not a link");
}
