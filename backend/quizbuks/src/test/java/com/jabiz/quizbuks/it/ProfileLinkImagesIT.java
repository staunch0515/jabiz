package com.jabiz.quizbuks.it;

import com.jabiz.net.PublicAddresses;
import com.jabiz.quizbuks.identity.AccountCodes;
import com.jabiz.quizbuks.page.ProfilePackage;
import com.jabiz.quizbuks.page.ProfileTransferProcesses;
import com.jabiz.quizbuks.page.QbSponsorPages;
import com.jabiz.runtime.file.RemoteFetchNetwork;
import com.jabiz.runtime.test.FileSamples;
import com.jabiz.runtime.test.HttpsStubServer;
import com.jabiz.runtime.test.HttpsStubServer.Answer;
import io.netty.handler.ssl.SslContextBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import javax.net.ssl.SSLException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Linked images in a profile package (F-C6, decision D75): the plan names the policy each is downloaded under, the
 * page has the server download it ({@code POST /api/files/fetch}, outside the import's transaction) and passes the file
 * like an uploaded one, and a link given no file leaves the image there is. The network is the test's: names under
 * {@code fetch.test} reach an HTTPS stub on this machine, the platform's own pipeline (type by content, re-encoding)
 * is the real one.
 */
class ProfileLinkImagesIT extends PlayItSupport {

    static final InetAddress STUB_ADDRESS = address("127.0.0.1");
    static final HttpsStubServer STUB = HttpsStubServer.start(STUB_ADDRESS);

    @DynamicPropertySource
    static void fetching(DynamicPropertyRegistry registry) {
        registry.add("jabiz.files.fetch.allowed-ports", () -> "443," + STUB.port());
    }

    @AfterAll
    static void stopStub() {
        STUB.close();
    }

    @TestConfiguration
    static class Network {

        /** The stub's address counts as public here (never in production: no property changes the platform's rule). */
        @Bean
        @Primary
        RemoteFetchNetwork testNetwork() throws SSLException {
            return new RemoteFetchNetwork(host -> {
                if (host.endsWith(".fetch.test")) {
                    return List.of(STUB_ADDRESS);
                }
                throw new UnknownHostException(host);
            }, address -> address.equals(STUB_ADDRESS) || PublicAddresses.isPublic(address),
                SslContextBuilder.forClient().trustManager(HttpsStubServer.certificate()).build());
        }
    }

    private static InetAddress address(String literal) {
        try {
            return InetAddress.getByName(literal);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException(literal, e);
        }
    }

    /** A link to an image the stub serves (JPEG with a camera's metadata, which must not survive). */
    private static String linkToImage() {
        String path = "/" + UUID.randomUUID() + "/photo.jpg";
        // The stub answers each request of a path from its script, in order: enough for a few downloads of one link.
        for (int i = 0; i < 4; i++) {
            STUB.script(path, Answer.of(200, FileSamples.jpegWithExif(40, 20, 6)).header("Content-Type", "image/jpeg"));
        }
        return "https://h" + UUID.randomUUID().toString().replace("-", "").substring(0, 12) + ".fetch.test:"
            + STUB.port() + path;
    }

    private WebTestClient.ResponseSpec fetch(String policy, String link, String token) {
        return client.post().uri("/api/files/fetch?policy=" + policy).contentType(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.AUTHORIZATION, token).bodyValue(Map.of("url", link)).exchange();
    }

    private static Map<String, Object> packageWithProductImage(String handle, Object image) {
        Map<String, Object> product = body("code", "FC6-1", "name", body("ja", "緑茶"), "price", "1200", "currency", "JPY",
            "shopUrl", "https://shop.example/" + UUID.randomUUID(), "image", image);
        return body("format", ProfilePackage.FORMAT, "schema", ProfilePackage.SCHEMA, "languages", List.of("ja"),
            "basics", body("contactName", "Ann", "company", "茶舗"),
            "page", body("handle", handle, "headline", body("ja", "おいしいお茶"), "cover", body("url", linkToImage())),
            "products", List.of(product));
    }

    @SuppressWarnings("unchecked")
    @Test
    void aLinkedImageIsDownloadedForTheSponsorAndImportedLikeAnUploadedOne() {
        String sponsor = newSponsor(unique("s-fc6-link"));
        String token = sponsorToken(sponsor);
        String productLink = linkToImage();
        Map<String, Object> document = packageWithProductImage("fc6-" + UUID.randomUUID().toString().substring(0, 8),
            body("url", productLink));

        // The check lists each link with the policy to download it under, and writes nothing.
        Map<String, Object> plan = (Map<String, Object>) run(ProfileTransferProcesses.CHECK, token,
            body("document", document)).get("plan");
        assertThat((List<Map<String, Object>>) plan.get("links"))
            .extracting(link -> link.get("path") + "=" + link.get("policy"))
            .containsExactly("page.cover=" + QbSponsorPages.IMAGE_POLICY,
                "products[0].image=" + QbSponsorPages.IMAGE_POLICY);
        assertThat((List<Map<String, Object>>) plan.get("files")).isEmpty();

        // The page has the server download each one, under the caller's own name.
        Map<String, Object> cover = fetch(QbSponsorPages.IMAGE_POLICY,
            (String) ((List<Map<String, Object>>) plan.get("links")).get(0).get("url"), token)
            .expectStatus().isCreated().expectBody(MAP).returnResult().getResponseBody();
        Map<String, Object> product = fetch(QbSponsorPages.IMAGE_POLICY, productLink, token)
            .expectStatus().isCreated().expectBody(MAP).returnResult().getResponseBody();
        assertThat(query("SELECT uploaded_by, policy FROM sys_file WHERE file_id = ?::uuid", cover.get("fileId")))
            .singleElement().satisfies(file -> {
                assertThat(file.get("uploaded_by")).isEqualTo(sponsor);
                assertThat(file.get("policy")).isEqualTo(QbSponsorPages.IMAGE_POLICY);
            });

        run(ProfileTransferProcesses.IMPORT, token, body("document", document,
            "files", body("page.cover", cover.get("fileId"), "products[0].image", product.get("fileId"))));
        assertThat(query("SELECT image FROM qb_sponsor_product WHERE user_id = ?", sponsor))
            .singleElement().satisfies(row -> assertThat(String.valueOf(row.get("image")))
                .isEqualTo(product.get("fileId")));
        assertThat(query("SELECT cover FROM qb_sponsor_page WHERE user_id = ?", sponsor))
            .singleElement().satisfies(row -> assertThat(String.valueOf(row.get("cover")))
                .isEqualTo(cover.get("fileId")));
    }

    @SuppressWarnings("unchecked")
    @Test
    void aLinkWithoutAFileLeavesTheImageThereIsAndAnotherSponsorsFileIsRefused() {
        String sponsor = newSponsor(unique("s-fc6-keep"));
        String token = sponsorToken(sponsor);
        String handle = "fc6-" + UUID.randomUUID().toString().substring(0, 8);
        String link = linkToImage();
        Map<String, Object> document = packageWithProductImage(handle, body("url", link));

        // First with its image; then again with the links but no files: the images stay.
        String own = (String) fetch(QbSponsorPages.IMAGE_POLICY, link, token).expectStatus().isCreated()
            .expectBody(MAP).returnResult().getResponseBody().get("fileId");
        run(ProfileTransferProcesses.IMPORT, token, body("document", document, "files",
            body("products[0].image", own)));
        run(ProfileTransferProcesses.IMPORT, token, body("document", document));
        assertThat(query("SELECT image FROM qb_sponsor_product WHERE user_id = ?", sponsor))
            .singleElement().satisfies(row -> assertThat(String.valueOf(row.get("image"))).isEqualTo(own));

        // A file of another sponsor's is no more welcome for a link than for an uploaded image.
        String other = sponsorToken(newSponsor(unique("s-fc6-other")));
        String foreign = (String) fetch(QbSponsorPages.IMAGE_POLICY, link, other).expectStatus().isCreated()
            .expectBody(MAP).returnResult().getResponseBody().get("fileId");
        Map<String, Object> refusal = refused(ProfileTransferProcesses.IMPORT, token, body("document", document,
            "files", body("products[0].image", foreign)), 422);
        assertThat(codes(refusal)).contains(AccountCodes.FILE_NOT_OWN);
    }
}
