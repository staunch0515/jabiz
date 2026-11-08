package com.jabiz.quizbuks.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.jabiz.ledger.LedgerDimension;
import com.jabiz.quizbuks.wallet.QbLedger;
import com.jabiz.runtime.ledger.LedgerDimensionRegistry;
import com.jabiz.runtime.test.PostgresIntegrationTest;
import com.jabiz.runtime.web.JabizWebProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * QuizBuks starts on a freshly migrated database with the platform's startup checks passing, is healthy, and keeps
 * the platform's default: the API refuses callers without a token.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class QuizbuksAppIT extends PostgresIntegrationTest {

    @Autowired
    ApplicationContext context;

    @Autowired
    LedgerDimensionRegistry dimensions;

    @Autowired
    JabizWebProperties web;

    private WebTestClient client;

    @BeforeEach
    void client() {
        client = WebTestClient.bindToApplicationContext(context).build();
    }

    @Test
    void isHealthy() {
        client.get().uri("/actuator/health").exchange().expectStatus().isOk()
            .expectBody().jsonPath("$.status").isEqualTo("UP");
    }

    @Test
    void refusesAnonymousApiCalls() {
        client.get().uri("/api/meta/datasets").exchange().expectStatus().isUnauthorized();
    }

    /** The wallets' party is dimension 1, valued by user ids (accepted by the platform's check since phase 16h). */
    @Test
    void walletLinesCarryThePartyByUserId() {
        assertThat(dimensions.all()).extracting(LedgerDimension::position, LedgerDimension::name)
            .contains(org.assertj.core.groups.Tuple.tuple(1, QbLedger.PARTY));
        assertThat(dimensions.idValued()).contains(QbLedger.PARTY);
    }

    /**
     * The three sign-in entries are served (D-Q2-1): the app takes takers without a verified address, the sponsor
     * console only with one; the tests' configuration turns registration off. A startup check error (SECURITY among
     * them) would have kept the application from starting.
     */
    @Test
    @SuppressWarnings("unchecked")
    void servesTheThreeSignInEntries() {
        java.util.Map<String, Object> app = client.get().uri("/api/auth/entries/app").exchange().expectStatus().isOk()
            .expectBody(java.util.Map.class).returnResult().getResponseBody();
        assertThat(app).containsEntry("requireVerifiedEmail", false).containsEntry("selfRegistration", false)
            .containsEntry("appPath", "/taker/").containsEntry("sessionPersistence", "device");
        java.util.Map<String, Object> sponsor = client.get().uri("/api/auth/entries/sponsor").exchange().expectStatus()
            .isOk().expectBody(java.util.Map.class).returnResult().getResponseBody();
        assertThat(sponsor).containsEntry("requireVerifiedEmail", false).containsEntry("appPath", "/sponsor/")
            .containsEntry("sessionPersistence", "tab");
        java.util.Map<String, Object> admin = client.get().uri("/api/auth/entries/admin").exchange().expectStatus()
            .isOk().expectBody(java.util.Map.class).returnResult().getResponseBody();
        assertThat(admin).containsEntry("selfRegistration", false).containsEntry("appPath", "/admin/");
        client.get().uri("/api/auth/entries/portal").exchange().expectStatus().isNotFound();
    }

    /**
     * The front ends (docs/quizbuks/plans/QP-pages.md section 2, Q30): the app at /taker as a PWA signing in to "app",
     * the sponsor console at /sponsor and the administration at /admin, each with its own entry, and the business site
     * at /biz with no entry. application.yml writes the list as the jar has it, for the App's own content security
     * policy (Q19).
     */
    @Test
    void servesTheFrontEndsOfTheJar() {
        assertThat(web.spa()).extracting(JabizWebProperties.Spa::path, JabizWebProperties.Spa::entry,
                JabizWebProperties.Spa::pwa)
            .containsExactlyInAnyOrder(
                org.assertj.core.groups.Tuple.tuple("/taker", "app", JabizWebProperties.Pwa.ON),
                org.assertj.core.groups.Tuple.tuple("/sponsor", "sponsor", JabizWebProperties.Pwa.OFF),
                org.assertj.core.groups.Tuple.tuple("/admin", "admin", JabizWebProperties.Pwa.OFF),
                // Q48a: the operators' back office, the admin frontend signing in to the entry operator.
                org.assertj.core.groups.Tuple.tuple("/operator", "operator", JabizWebProperties.Pwa.OFF),
                // S1: QuizBuks for Shopify's embedded pages, framed by the shop that opened them.
                org.assertj.core.groups.Tuple.tuple("/shopify", "shopify", JabizWebProperties.Pwa.OFF),
                org.assertj.core.groups.Tuple.tuple("/biz", null, JabizWebProperties.Pwa.OFF),
                // S4a: its public pages (landing, privacy, support), static with no entry.
                org.assertj.core.groups.Tuple.tuple("/shopify-app", null, JabizWebProperties.Pwa.OFF));
        assertThat(web.spa()).filteredOn(spa -> "/shopify".equals(spa.path())).singleElement()
            .satisfies(spa -> {
                assertThat(spa.embedding()).isEqualTo("shopify");
                // S4a: the app key and support address come from the deployment (17g), not the build.
                assertThat(spa.indexValues()).containsOnlyKeys("shopify-api-key", "support-email")
                    .containsEntry("shopify-api-key", "test-only-quizbuks-shopify-key");
            });
        // The bare address goes to the business site (Q30).
        assertThat(web.rootRedirect()).isEqualTo("/biz/");
    }

    /**
     * Q19: the App frames the two video players it shows (YouTube without cookies, Vimeo); Q75: the sponsor console
     * frames the same two (the material dialog's preview reads the video's duration from the player). Google AdSense in
     * the App, the console and the business site: its loader and requests from Google's ad hosts, its frames, ad images
     * from any https host. The administration keeps the platform's default. F-S15: the App and the console frame and
     * play video links on any https address.
     */
    @Test
    void theAppFramesOnlyTheVideoPlayersAndGooglesAds() {
        // The policy each SPA's responses carry (SpaFallbackFilter); the test has no built pages to fetch.
        java.util.Map<String, String> policies = web.spa().stream().collect(java.util.stream.Collectors.toMap(
            JabizWebProperties.Spa::path, JabizWebProperties.Spa::contentSecurityPolicy));
        String adScripts = "https://pagead2.googlesyndication.com https://*.googlesyndication.com https://*.doubleclick.net "
            + "https://*.google.com https://*.google.co.jp https://*.gstatic.com https://*.adtrafficquality.google";
        String adFrames = "https://*.doubleclick.net https://*.googlesyndication.com https://*.google.com "
            + "https://*.adtrafficquality.google";
        String adRequests = "https://*.googlesyndication.com https://*.doubleclick.net https://*.google.com "
            + "https://*.adtrafficquality.google";
        String withAds = "default-src 'self'; script-src 'self' " + adScripts + "; img-src 'self' blob: data: https:; "
            + "style-src 'self' 'unsafe-inline'; connect-src 'self' " + adRequests + "; frame-ancestors 'none'; "
            + "base-uri 'self'; object-src 'none'; form-action 'self'; worker-src 'self'; manifest-src 'self'; frame-src ";
        // F-S15: a video link may be on any https address: frames (the sites' players, other pages in a sandbox, the
        // ads) and video files from any https address.
        String anyVideo = "https:; media-src 'self' https:";
        assertThat(policies.get("/taker")).isEqualTo(withAds + anyVideo);
        assertThat(policies.get("/sponsor")).isEqualTo(withAds + anyVideo);
        assertThat(policies.get("/biz")).isEqualTo(withAds + adFrames);
        assertThat(policies.get("/admin")).isEqualTo(JabizWebProperties.DEFAULT_CONTENT_SECURITY_POLICY);
        // Never scripts other than the origin's and Google's ad hosts, never framed.
        for (String path : java.util.List.of("/taker", "/sponsor", "/biz")) {
            assertThat(policies.get(path)).doesNotContain("'unsafe-eval'").contains("frame-ancestors 'none'")
                .doesNotContain("script-src 'self' 'unsafe-inline'");
        }
    }

    /**
     * F-S15: the policy is the header of the App's and the console's responses (an entry page, a client route), not
     * only the configuration; the business site and the administration play no video and keep theirs.
     */
    @Test
    void theAppAndTheConsoleFrameAndPlayVideosOnAnyHttpsAddress() {
        for (String path : java.util.List.of("/taker/", "/taker/p/1", "/sponsor/")) {
            client.get().uri(path).exchange().expectHeader().value("Content-Security-Policy",
                policy -> assertThat(policy).contains("frame-src https:; media-src 'self' https:")
                    .contains("script-src 'self' https://pagead2").contains("frame-ancestors 'none'"));
        }
        for (String path : java.util.List.of("/biz/", "/admin/")) {
            client.get().uri(path).exchange().expectHeader().value("Content-Security-Policy",
                policy -> assertThat(policy).doesNotContain("media-src").doesNotContain("frame-src https:;"));
        }
    }
}
