package com.jabiz.quizbuks.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.jabiz.ledger.LedgerDimension;
import com.jabiz.quizbuks.wallet.QbLedger;
import com.jabiz.runtime.ledger.LedgerDimensionRegistry;
import com.jabiz.runtime.test.PostgresIntegrationTest;
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
}
