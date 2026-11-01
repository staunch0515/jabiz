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
 * Single-page applications in one application (ROADMAP phases 13a and 16c-2, docs/design/17-apps-and-branches.md
 * section 3.2): a public website at {@code /}, the admin frontend at {@code /admin/} and a PWA at {@code /portal/},
 * each falling back to its own index page and carrying its own Content-Security-Policy, through the full WebFlux chain
 * including security. The pages are test resources laid out as the jar lays out the built SPAs ({@code static/},
 * {@code static/admin/}, {@code static/portal/}); the PWA's index shows a value of the deployment (phase 17g). The PWA's
 * files have the cache headers and types decision D43 item 6
 * asks for, and only its declared Service Worker answers a request for a Service Worker script.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
    "spring.web.resources.static-locations=classpath:/multi-spa/",
    "jabiz.web.spa[0].path=/",
    "jabiz.web.spa[0].content-security-policy=" + MultiSpaIT.SITE_POLICY,
    "jabiz.web.spa[1].path=/admin",
    "jabiz.web.spa[1].entry=admin",
    "jabiz.web.spa[2].path=/portal",
    "jabiz.web.spa[2].entry=portal",
    "jabiz.web.spa[2].pwa=on",
    "jabiz.web.spa[2].index-values.application-name=Portal \"test\" & Co",
    "jabiz.web.native-apps[0].path=/portal",
    "jabiz.web.native-apps[0].ios[0]=ABCDE12345.com.example.portal",
    "jabiz.web.native-apps[0].android[0].package-name=com.example.portal",
    "jabiz.web.native-apps[0].android[0].fingerprints[0]=" + MultiSpaIT.FINGERPRINT,
})
class MultiSpaIT extends PostgresIntegrationTest {

    static final String SITE_POLICY = "default-src 'self'; frame-src https://www.youtube-nocookie.com";
    static final String FINGERPRINT = "14:6D:E9:83:C5:73:06:50:D8:EE:B9:95:2F:34:FC:64:16:A0:83:42:E6:1D:BE:A8:8A:04:96:"
        + "B2:3F:CF:44:E5";
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
                .expectHeader().valueEquals(CSP, JabizWebProperties.DEFAULT_CONTENT_SECURITY_POLICY)
                .expectBody(String.class).value(body -> assertThat(body).as(path).contains("id=\"root\""));
        }
    }

    @Test
    void filesAreServedAsFilesWithTheirSpasPolicy() {
        client.get().uri("/admin/assets/app.js").exchange()
            .expectStatus().isOk()
            .expectHeader().valueEquals(CSP, JabizWebProperties.DEFAULT_CONTENT_SECURITY_POLICY)
            .expectBody(String.class).value(body -> assertThat(body).contains("admin"));
        client.get().uri("/assets/site.js").exchange()
            .expectStatus().isOk()
            .expectHeader().valueEquals(CSP, SITE_POLICY)
            .expectBody(String.class).value(body -> assertThat(body).contains("site"));
        client.get().uri("/admin/assets/missing.js").exchange().expectStatus().isNotFound();
    }

    @Test
    void thePwasFilesHaveTheirCacheHeadersAndTypes() {
        // The index page (also as the fallback of a client route), the Service Worker and the manifest are
        // revalidated on every use, overriding the no-store Spring Security writes; Pragma and Expires go with it.
        for (String path : new String[] {"/portal/", "/portal/orders", "/portal/index.html", "/portal/sw.js",
            "/portal/manifest.webmanifest"}) {
            client.get().uri(path).exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("Cache-Control", "no-cache")
                .expectHeader().doesNotExist("Pragma")
                .expectHeader().doesNotExist("Expires")
                .expectHeader().valueEquals(CSP, JabizWebProperties.DEFAULT_CONTENT_SECURITY_POLICY)
                .expectHeader().doesNotExist("Service-Worker-Allowed");
        }
        // Its index shows this deployment's value (phase 17g), escaped, with the same headers as any index page.
        client.get().uri("/portal/orders").exchange()
            .expectHeader().contentType("text/html;charset=UTF-8")
            .expectBody(String.class).value(body -> assertThat(body).contains("id=\"portal\"")
                .contains("content=\"Portal &quot;test&quot; &amp; Co\"").doesNotContain("{{jabiz:"));
        client.get().uri("/portal/manifest.webmanifest").exchange()
            .expectHeader().contentType("application/manifest+json");
        // Content-hashed files never change.
        client.get().uri("/portal/assets/index-abc123.js").exchange()
            .expectStatus().isOk()
            .expectHeader().valueEquals("Cache-Control", "public, max-age=31536000, immutable")
            .expectHeader().contentTypeCompatibleWith("text/javascript");
        // A file that is missing is not cached for a year.
        client.get().uri("/portal/assets/missing.js").exchange()
            .expectStatus().isNotFound()
            .expectHeader().value("Cache-Control", value -> assertThat(value).doesNotContain("immutable"));
        // The admin frontend's index page is revalidated too.
        client.get().uri("/admin/data").exchange()
            .expectHeader().valueEquals("Cache-Control", "no-cache");
    }

    @Test
    void onlyTheDeclaredServiceWorkerAnswersAServiceWorkerScriptRequest() {
        client.get().uri("/portal/sw.js").header("Service-Worker", "script").exchange()
            .expectStatus().isOk()
            .expectHeader().valueEquals("Cache-Control", "no-cache")
            .expectBody(String.class).value(body -> assertThat(body).contains("addEventListener"));
        // Any other script of the origin, even one that exists, the API and the SPAs without a PWA: refused.
        for (String path : new String[] {"/portal/assets/uploaded.js", "/portal/assets/index-abc123.js", "/sw.js",
            "/admin/sw.js", "/api/files/x", "/api/public/files/1", "/api/meta/datasets", "/actuator/health"}) {
            client.get().uri(path).header("Service-Worker", "script").exchange()
                .expectStatus().isNotFound();
        }
        // Fetched as an ordinary file, it is served as one.
        client.get().uri("/portal/assets/uploaded.js").exchange().expectStatus().isOk();
    }

    @Test
    void mailedLinksOfEitherFormReachAFrontend() {
        // The administration's landing pages answer the links sent before the portal had its own (decision D43
        // item 4); the portal's own pages answer the new ones.
        client.get().uri("/auth/verify-email?entry=portal&token=abc").exchange()
            .expectStatus().isOk()
            .expectBody(String.class).value(body -> assertThat(body).contains("id=\"site\""));
        client.get().uri("/portal/auth/verify-email?entry=portal&token=abc").exchange()
            .expectStatus().isOk()
            .expectHeader().valueEquals("Cache-Control", "no-cache")
            .expectBody(String.class).value(body -> assertThat(body).contains("id=\"portal\""));
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

    /**
     * The store apps' association files (phase 17l, decision D68): at the root, public, JSON, cached for an hour,
     * neither the site's page nor redirected, whatever the site at {@code /} would answer; Apple's opens the portal's
     * addresses only.
     */
    @Test
    void theStoreAppsAssociationFilesAreServedAtTheRoot() {
        client.get().uri("/.well-known/apple-app-site-association").exchange()
            .expectStatus().isOk()
            .expectHeader().contentType(MediaType.APPLICATION_JSON)
            .expectHeader().valueEquals("Cache-Control", "max-age=3600, public")
            .expectHeader().doesNotExist(CSP)
            .expectBody()
            .jsonPath("$.applinks.details[0].appIDs[0]").isEqualTo("ABCDE12345.com.example.portal")
            .jsonPath("$.applinks.details[0].components[0]['/']").isEqualTo("/portal/*");
        client.get().uri("/.well-known/assetlinks.json").header("Authorization", "Bearer stale").exchange()
            .expectStatus().isOk()
            .expectHeader().contentType(MediaType.APPLICATION_JSON)
            .expectBody()
            .jsonPath("$[0].relation[0]").isEqualTo("delegate_permission/common.handle_all_urls")
            .jsonPath("$[0].target.package_name").isEqualTo("com.example.portal")
            .jsonPath("$[0].target.sha256_cert_fingerprints[0]").isEqualTo(FINGERPRINT);
        // Other well-known addresses stay the site's.
        client.get().uri("/.well-known/other").exchange().expectStatus().isNotFound();
    }
}
