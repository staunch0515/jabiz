package com.jabiz.app.it.publicread;

import com.jabiz.runtime.test.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.net.URI;
import java.util.List;

/**
 * Public read access is off by default (docs/design/15-public-access.md section 5): every public path is 404,
 * whatever the method, while the startup checks of public datasets and templates still run.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class PublicSwitchedOffIT extends PostgresIntegrationTest {

    @Autowired
    ApplicationContext context;

    @Test
    void everyPublicPathIsNotFound() {
        WebTestClient client = WebTestClient.bindToApplicationContext(context).build();
        String catalog = "/api/public/queries/commerce.public.catalog";
        client.get().uri(catalog).exchange().expectStatus().isNotFound()
            .expectHeader().valueEquals(HttpHeaders.CACHE_CONTROL, "no-store");
        client.head().uri(catalog).exchange().expectStatus().isNotFound();
        client.post().uri(catalog).exchange().expectStatus().isNotFound();
        client.get().uri("/api/public/files/019c1347-d280-74b3-a3e9-25b7e53b7bac").exchange()
            .expectStatus().isNotFound();
    }

    /** Spellings that the router and Spring Security take for the public path are switched off too. */
    @Test
    void encodedAndMatrixSpellingsAreSwitchedOffToo() {
        WebTestClient client = WebTestClient.bindToApplicationContext(context).build();
        for (String path : List.of("/api/p%75blic/queries/commerce.public.catalog",
            "/api/public;x=1/queries/commerce.public.catalog", "/api/public/queries;x/commerce.public.catalog")) {
            client.get().uri(URI.create(path)).exchange().expectStatus().isNotFound();
        }
    }
}
