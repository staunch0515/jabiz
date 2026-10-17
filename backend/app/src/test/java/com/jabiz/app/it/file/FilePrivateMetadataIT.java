package com.jabiz.app.it.file;

import com.jabiz.app.commerce.CommerceEntities;
import com.jabiz.app.commerce.CommerceFiles;
import com.jabiz.app.it.fixture.ItFileFixtures;
import com.jabiz.runtime.test.FileSamples;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Private file metadata (docs/design/14-files.md section 5.1; decision D47): a policy declaring
 * {@code privateMetadata()} shows the uploaded name, the uploader and the exact upload time to the uploader and to
 * holders of {@code file.read} only. Other readers of the policy get no name and no uploader, the day of the upload,
 * and neutral download names ({@code <fileId>[-<variant>].<ext>}); public files always have neutral names.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = "jabiz.public.enabled=true")
class FilePrivateMetadataIT extends FileItSupport {

    private static final String UPLOADER = "it-private-uploader";

    private String uploader() {
        return bearer(UPLOADER, "it.private.upload", "it.private.read");
    }

    private String reader() {
        return bearer("it-private-reader", "it.private.read");
    }

    private String admin() {
        return bearer("it-private-admin", "file.read");
    }

    private Map<String, Object> meta(String id, String authorization) {
        return get("/api/files/" + id, authorization).expectStatus().isOk().expectBody(MAP).returnResult()
            .getResponseBody();
    }

    private String downloadName(String path, String authorization) {
        String header = get(path, authorization).expectStatus().isOk().returnResult(byte[].class)
            .getResponseHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION);
        assertThat(header).isNotNull();
        return ContentDisposition.parse(header).getFilename();
    }

    @Test
    void theUploaderAndFileAdministratorsSeeEverythingOtherReadersNeitherNameNorUploader() {
        String id = (String) uploaded(ItFileFixtures.PRIVATE, FileSamples.jpeg(400, 300), "山田さんの写真.jpg",
            "image/jpeg", uploader()).get("fileId");

        for (String sees : List.of(uploader(), admin())) {
            Map<String, Object> full = meta(id, sees);
            assertThat(full).containsEntry("originalName", "山田さんの写真.jpg").containsEntry("uploadedBy", UPLOADER)
                .containsEntry("variants", List.of("w64"));
            // The tests' clock is fixed at a time of day other than midnight: the exact time shows.
            Instant exact = Instant.parse((String) full.get("uploadedTime"));
            assertThat(exact).isNotEqualTo(exact.truncatedTo(ChronoUnit.DAYS));
            assertThat(downloadName("/api/files/" + id + "/content", sees)).isEqualTo("山田さんの写真.jpg");
            assertThat(downloadName("/api/files/" + id + "/content/w64", sees)).isEqualTo("山田さんの写真-w64.jpg");
        }

        Map<String, Object> kept = meta(id, reader());
        assertThat(kept.get("originalName")).isNull();
        assertThat(kept.get("uploadedBy")).isNull();
        assertThat(kept).containsEntry("fileId", id).containsEntry("policy", ItFileFixtures.PRIVATE)
            .containsEntry("contentType", "image/jpeg").containsEntry("width", 400).containsEntry("height", 300)
            .containsEntry("variants", List.of("w64"));
        Instant day = Instant.parse((String) kept.get("uploadedTime"));
        assertThat(day).isEqualTo(day.truncatedTo(ChronoUnit.DAYS));
        assertThat(Instant.parse((String) meta(id, uploader()).get("uploadedTime")).truncatedTo(ChronoUnit.DAYS))
            .isEqualTo(day);
        assertThat(downloadName("/api/files/" + id + "/content", reader())).isEqualTo(id + ".jpg");
        assertThat(downloadName("/api/files/" + id + "/content/w64", reader())).isEqualTo(id + "-w64.jpg");
    }

    @Test
    void documentsGetTheirTypesExtension() {
        String id = (String) uploaded(ItFileFixtures.PRIVATE, FileSamples.pdf(), "給与明細 2026-10.pdf",
            "application/pdf", uploader()).get("fileId");
        assertThat(downloadName("/api/files/" + id + "/content", reader())).isEqualTo(id + ".pdf");
        assertThat(downloadName("/api/files/" + id + "/content", uploader())).isEqualTo("給与明細 2026-10.pdf");
        assertThat(meta(id, reader()).get("originalName")).isNull();
    }

    @Test
    void anotherUploaderOfThePolicyIsJustAReader() {
        String id = (String) uploaded(ItFileFixtures.PRIVATE, FileSamples.pdf(), "mine.pdf", "application/pdf",
            uploader()).get("fileId");
        String colleague = bearer("it-private-colleague", "it.private.upload", "it.private.read");
        Map<String, Object> seen = meta(id, colleague);
        assertThat(seen.get("originalName")).isNull();
        assertThat(seen.get("uploadedBy")).isNull();
        assertThat(downloadName("/api/files/" + id + "/content", colleague)).isEqualTo(id + ".pdf");
    }

    @Test
    void policiesWithoutPrivateMetadataShowItToEveryReader() {
        String id = (String) uploaded(CommerceFiles.DOCUMENT, FileSamples.pdf(), "contract.pdf", "application/pdf",
            bearer("it-private-docs", "commerce.document.upload", "commerce.document.read")).get("fileId");
        String reader = bearer("it-private-doc-reader", "commerce.document.read");
        assertThat(meta(id, reader)).containsEntry("originalName", "contract.pdf")
            .containsEntry("uploadedBy", "it-private-docs");
        assertThat(downloadName("/api/files/" + id + "/content", reader)).isEqualTo("contract.pdf");
    }

    @Test
    void publicFilesAlwaysHaveNeutralNames() {
        String photo = (String) uploaded(CommerceFiles.IMAGE, FileSamples.jpeg(400, 300), "私の写真.jpg", "image/jpeg",
            photographer()).get("fileId");
        String sku = "PRV" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT);
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("sku", sku);
        attributes.put("productName", "Product " + sku);
        attributes.put("unitPrice", 100);
        attributes.put("active", true);
        attributes.put("imageFileId", photo);
        post("/api/datasets/" + CommerceEntities.PRODUCT_DATASET + "/commit", bearer("it-private-admin", "*"),
            Map.of("changes", List.of(Map.of("action", "INSERT", "attributes", attributes))))
            .expectStatus().isOk();

        for (String variant : List.of("", "/w160")) {
            String header = client.get().uri("/api/public/files/" + photo + variant).exchange()
                .expectStatus().isOk().returnResult(byte[].class).getResponseHeaders()
                .getFirst(HttpHeaders.CONTENT_DISPOSITION);
            assertThat(ContentDisposition.parse(header).getFilename())
                .isEqualTo(photo + (variant.isEmpty() ? "" : "-w160") + ".jpg");
        }
        // The photographer still sees the uploaded name through the authenticated endpoint.
        assertThat(downloadName("/api/files/" + photo + "/content", photographer())).isEqualTo("私の写真.jpg");
    }
}
