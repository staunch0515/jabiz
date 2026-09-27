package com.jabiz.culture.it;

import com.jabiz.runtime.test.PostgresIntegrationTest;
import com.jabiz.runtime.web.JabizWebProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The public site at {@code /} and the admin at {@code /admin/}, with the site's own Content-Security-Policy from
 * {@code application.yml} (docs/culture/00-design.md section 9.4). The pages are test resources laid out as the jar
 * lays out the built SPAs.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = "spring.web.resources.static-locations=classpath:/spa/")
class CultureSpaIT extends PostgresIntegrationTest {

    private static final String CSP = "Content-Security-Policy";

    @Autowired
    ApplicationContext context;

    private WebTestClient client;

    @BeforeEach
    void client() {
        client = WebTestClient.bindToApplicationContext(context).build();
    }

    @Test
    void theSiteIsServedAtTheRootWithItsOwnPolicy() {
        for (String path : new String[] {"/", "/en/stories/home", "/ja/themes"}) {
            client.get().uri(path).exchange().expectStatus().isOk()
                .expectHeader().value(CSP, policy -> assertThat(policy)
                    .contains("frame-src https://www.youtube-nocookie.com https://player.vimeo.com")
                    .contains("script-src 'self'").contains("frame-ancestors 'none'"))
                .expectBody(String.class).value(body -> assertThat(body).as(path).contains("id=\"site\""));
        }
    }

    @Test
    void theAdminIsServedUnderItsPrefixWithThePlatformPolicy() {
        client.get().uri("/admin/data").exchange().expectStatus().isOk()
            .expectHeader().valueEquals(CSP, JabizWebProperties.ADMIN_CONTENT_SECURITY_POLICY)
            .expectBody(String.class).value(body -> assertThat(body).contains("id=\"root\""));
    }
}
