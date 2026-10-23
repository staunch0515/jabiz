package com.jabiz.quizbuks.it;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.GreenMailUtil;
import com.icegreen.greenmail.util.ServerSetup;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A publication's share link (docs/quizbuks/plans/Q15-share-link.md): anybody reads a live publication's page through
 * the public template, with a whitelist of its fields and the sponsor's company only, and nothing while it is
 * scheduled, once it is paused, or when it is unknown; the app's entry signs in by a mailed link (platform decision D54) that leads back to the
 * publication's page, opening a verified taker's account for an address nobody uses.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
    "jabiz.mail.enabled=true", "jabiz.mail.from=quizbuks@example.com",
    "jabiz.mail.base-url=https://quizbuks.example.com",
    "spring.mail.host=localhost", "spring.mail.port=3047",
    "jabiz.security.entries.app.self-registration=true"})
class ShareLinkIT extends PlayItSupport {

    @RegisterExtension
    static final GreenMailExtension MAIL = new GreenMailExtension(new ServerSetup(3047, null,
        ServerSetup.PROTOCOL_SMTP));

    static final String TEMPLATE = "qb.public.publication";

    static final Pattern LINK = Pattern.compile(
        "https://quizbuks\\.example\\.com/auth/email-link\\?entry=app&token=([A-Za-z0-9_-]+)&to=([^)\\s]*)");

    /** Anonymous: the public template's answer (no token). */
    private WebTestClient.ResponseSpec publicQuery(String query) {
        return client.get().uri("/api/public/queries/" + TEMPLATE + "?" + query).exchange();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> page(String publicationId) {
        return (List<Map<String, Object>>) publicQuery("p.publicationId=" + publicationId).expectStatus().isOk()
            .expectBody(MAP).returnResult().getResponseBody().get("items");
    }

    @Test
    void aLivePublicationShowsAnybodyItsWhitelistOnly() {
        String publicationId = livePublication(sponsorA(), "Shared");
        assertThat(page(publicationId)).singleElement().satisfies(row -> {
            assertThat(row).containsOnlyKeys("publicationId", "name", "quizTitle", "kind", "startAt", "endAt",
                "timeLimitSec", "questionCount", "fullScore", "passScore", "maxReward", "sponsorCompany");
            assertThat(row).containsEntry("publicationId", publicationId).containsEntry("name", "Shared")
                .containsEntry("kind", "QUIZ").containsEntry("questionCount", 2);
            assertThat(row.get("sponsorCompany")).isNotNull();
        });
        // Nothing of the sponsor or the regions in the answer, whatever is asked.
        String raw = publicQuery("p.publicationId=" + publicationId)
            .expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody();
        assertThat(raw).doesNotContain("sponsorId").doesNotContain("regions").doesNotContain("GLOBAL")
            .doesNotContain("tiers").doesNotContain("budget");

        // Neither the sponsor's id nor the regions can be filtered or sorted on: they are not results.
        publicQuery("p.publicationId=" + publicationId + "&filter=sponsorId:eq:" + sponsorA()).expectStatus()
            .isBadRequest();
        publicQuery("p.publicationId=" + publicationId + "&sort=sponsorId").expectStatus().isBadRequest();
        // Scheduled, unknown, or paused: no row.
        String later = livePublication(sponsorA(), "Later",
            Map.of("startAt", clock.instant().plus(Duration.ofDays(1))));
        assertThat(page(later)).isEmpty();
        assertThat(page(UUID.randomUUID().toString())).isEmpty();
        run("QB_PUBLICATION_PAUSE", sponsorToken(sponsorA()), body("publicationId", publicationId));
        assertThat(page(publicationId)).isEmpty();
    }

    @Test
    void aShareLinkOpensATakersAccountAndLeadsBackToThePublication() {
        String publicationId = livePublication(sponsorA(), "Linked");
        Map<String, Object> entry = client.get().uri("/api/auth/entries/app").exchange().expectStatus().isOk()
            .expectBody(MAP).returnResult().getResponseBody();
        assertThat(entry).containsEntry("emailLink", true);
        assertThat(client.get().uri("/api/auth/entries/sponsor").exchange().expectStatus().isOk()
            .expectBody(MAP).returnResult().getResponseBody()).containsEntry("emailLink", false);

        String email = unique("shared").toLowerCase() + "@example.com";
        client.post().uri("/api/auth/email-link").contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("entry", "app", "email", email, "to", "/p/" + publicationId)).exchange()
            .expectStatus().isAccepted();
        deliverEvents();
        Matcher link = LINK.matcher(textTo(email));
        assertThat(link.find()).as("a sign-in link").isTrue();
        assertThat(link.group(2)).isEqualTo("%2Fp%2F" + publicationId);

        Map<String, Object> session = client.post().uri("/api/auth/email-link/confirm")
            .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("token", link.group(1))).exchange()
            .expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
        assertThat(session).containsEntry("status", "SIGNED_IN");
        Map<String, Object> me = client.get().uri("/api/auth/me")
            .header("Authorization", "Bearer " + session.get("accessToken")).exchange().expectStatus().isOk()
            .expectBody(MAP).returnResult().getResponseBody();
        // A verified taker of the app: the welcome step (country and terms) comes next, then the publication.
        assertThat(me).containsEntry("emailVerified", true).containsEntry("entry", "app");
        assertThat(me.get("roles")).isEqualTo(List.of("QB_TAKER"));
    }

    /** The text of the newest mail to an address. */
    private static String textTo(String address) {
        List<MimeMessage> mails = Arrays.stream(MAIL.getReceivedMessages()).filter(m -> {
            try {
                return m.getAllRecipients()[0].toString().equalsIgnoreCase(address);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }).toList();
        assertThat(mails).as("mail to " + address).isNotEmpty();
        return GreenMailUtil.getBody(mails.getLast()).replace("=\r\n", "").replace("=3D", "=");
    }
}
