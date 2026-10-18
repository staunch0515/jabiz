package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.messaging.QbLegal;
import com.jabiz.runtime.test.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * {@code JABIZ_PUBLIC_ENABLED=false} (docs/quizbuks/operations.md): every public path is 404, the help centre's and
 * the legal pages' templates included, while the startup checks of the public datasets and templates still run.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@TestPropertySource(properties = "jabiz.public.enabled=false")
class PublicSwitchedOffIT extends PostgresIntegrationTest {

    @Autowired
    ApplicationContext context;

    @Test
    void everyPublicTemplateIsNotFound() {
        WebTestClient client = WebTestClient.bindToApplicationContext(context).build();
        for (String id : java.util.List.of(QbLegal.PUBLIC_FAQ_TEMPLATE + "?p.audience=APP",
            QbLegal.PUBLIC_LEGAL_HISTORY_TEMPLATE + "?p.kind=PRIVACY")) {
            client.get().uri("/api/public/queries/" + id).exchange().expectStatus().isNotFound();
        }
    }
}
