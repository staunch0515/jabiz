package com.jabiz.app.it;

import com.jabiz.runtime.test.PostgresIntegrationTest;
import com.jabiz.runtime.web.JabizWebProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two single-page applications in one application (ROADMAP phase 13a, docs/design/17-apps-and-branches.md section
 * 3.2): a public website at {@code /} and the admin frontend at {@code /admin/}, each falling back to its own index
 * page and carrying its own Content-Security-Policy, through the full WebFlux chain including security. The pages
 * are test resources laid out as the jar lays out the built SPAs ({@code static/}, {@code static/admin/}).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
    "spring.web.resources.static-locations=classpath:/multi-spa/",
    "jabiz.web.spa[0].path=/",
    "jabiz.web.spa[0].content-security-policy=" + MultiSpaIT.SITE_POLICY,
    "jabiz.web.spa[1].path=/admin",
})
class MultiSpaIT extends PostgresIntegrationTest {

    static final String SITE_POLICY = "default-src 'self'; frame-src https://www.youtube-nocookie.com";
    private static final String CSP = "Content-Security-Policy";

    @Autowired
    ApplicationContext context;

    private WebTestClient client;

    @BeforeEach
    void client() {
        client = WebTestClient.bindToApplicationContext(context).build();
    }

    @Test
    void clientRoutesOfTheSiteFallBackToTheSiteIndex() {
        for (String path : new String[] {"/", "/works/42", "/administrator"}) {
            client.get().uri(path).exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_HTML)
                .expectHeader().valueEquals(CSP, SITE_POLICY)
                .expectBody(String.class).value(body -> assertThat(body).as(path).contains("id=\"site\""));
        }
    }

    @Test
    void clientRoutesOfTheAdminFallBackToTheAdminIndex() {
        for (String path : new String[] {"/admin", "/admin/", "/admin/data/carrier", "/admin/login"}) {
            client.get().uri(path).exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals(CSP, JabizWebProperties.ADMIN_CONTENT_SECURITY_POLICY)
                .expectBody(String.class).value(body -> assertThat(body).as(path).contains("id=\"root\""));
        }
    }

    @Test
    void filesAreServedAsFilesWithTheirSpasPolicy() {
        client.get().uri("/admin/assets/app.js").exchange()
            .expectStatus().isOk()
            .expectHeader().valueEquals(CSP, JabizWebProperties.ADMIN_CONTENT_SECURITY_POLICY)
            .expectBody(String.class).value(body -> assertThat(body).contains("admin"));
        client.get().uri("/assets/site.js").exchange()
            .expectStatus().isOk()
            .expectHeader().valueEquals(CSP, SITE_POLICY)
            .expectBody(String.class).value(body -> assertThat(body).contains("site"));
        client.get().uri("/admin/assets/missing.js").exchange().expectStatus().isNotFound();
    }

    @Test
    void theApiIsUntouched() {
        client.get().uri("/api/meta/datasets").exchange()
            .expectStatus().isEqualTo(HttpStatus.UNAUTHORIZED)
            .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
            .expectHeader().doesNotExist(CSP);
        client.get().uri("/actuator/health").exchange()
            .expectStatus().isOk()
            .expectHeader().doesNotExist(CSP);
    }
}
