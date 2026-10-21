package com.jabiz.quizbuks.it;

import com.jabiz.runtime.test.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * The health check decides whether the server keeps a deployment (deploy/quizbuks/DEPLOY.md): a mail server that does
 * not answer must not make the application unhealthy and roll a release back.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = {"spring.mail.host=127.0.0.1", "spring.mail.port=9"})
class HealthWithoutMailIT extends PostgresIntegrationTest {

    @Autowired
    ApplicationContext context;

    @Test
    void isHealthyWhileTheMailServerDoesNotAnswer() {
        WebTestClient.bindToApplicationContext(context).build()
            .get().uri("/actuator/health").exchange().expectStatus().isOk()
            .expectBody().jsonPath("$.status").isEqualTo("UP");
    }
}
