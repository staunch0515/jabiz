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
            .containsEntry("appPath", "/").containsEntry("sessionPersistence", "device");
        java.util.Map<String, Object> sponsor = client.get().uri("/api/auth/entries/sponsor").exchange().expectStatus()
            .isOk().expectBody(java.util.Map.class).returnResult().getResponseBody();
        assertThat(sponsor).containsEntry("requireVerifiedEmail", true).containsEntry("appPath", "/sponsor/")
            .containsEntry("sessionPersistence", "tab");
        java.util.Map<String, Object> admin = client.get().uri("/api/auth/entries/admin").exchange().expectStatus()
            .isOk().expectBody(java.util.Map.class).returnResult().getResponseBody();
        assertThat(admin).containsEntry("selfRegistration", false).containsEntry("appPath", "/admin/");
        client.get().uri("/api/auth/entries/portal").exchange().expectStatus().isNotFound();
    }

    /**
     * The three front ends come from the jar (jabizApp writes them as the defaults of jabiz.web.spa;
     * docs/quizbuks/plans/QP-pages.md section 2): the app at / as a PWA signing in to "app", the sponsor console at
     * /sponsor and the administration at /admin, each with its own entry. application.yml no longer replaces the list.
     */
    @Test
    void servesTheThreeFrontEndsOfTheJar() {
        assertThat(web.spa()).extracting(JabizWebProperties.Spa::path, JabizWebProperties.Spa::entry,
                JabizWebProperties.Spa::pwa)
            .containsExactlyInAnyOrder(
                org.assertj.core.groups.Tuple.tuple("/", "app", JabizWebProperties.Pwa.ON),
                org.assertj.core.groups.Tuple.tuple("/sponsor", "sponsor", JabizWebProperties.Pwa.OFF),
                org.assertj.core.groups.Tuple.tuple("/admin", "admin", JabizWebProperties.Pwa.OFF));
    }
}
