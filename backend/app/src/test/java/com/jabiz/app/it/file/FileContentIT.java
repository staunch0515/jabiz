package com.jabiz.app.it.file;

import com.jabiz.app.commerce.CommerceFiles;
import com.jabiz.app.it.fixture.ItFileFixtures;
import com.jabiz.runtime.test.FileSamples;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reading files behind authentication (docs/design/14-files.md section 5): the file's policy decides who may read,
 * responses never let the browser guess the type or run the content, PDF is a download, audio can be sought.
 */
class FileContentIT extends FileItSupport {

    private String documents() {
        return bearer("it-docs", "commerce.document.upload", "commerce.document.read");
    }

    @Test
    void metadataAndContentNeedAReadPermission() {
        Map<String, Object> pdf = uploaded(CommerceFiles.DOCUMENT, FileSamples.pdf(), "契約 2026.pdf",
            "application/pdf", documents());
        String id = (String) pdf.get("fileId");

        Map<String, Object> meta = get("/api/files/" + id, documents()).expectStatus().isOk().expectBody(MAP)
            .returnResult().getResponseBody();
        assertThat(meta).containsEntry("policy", CommerceFiles.DOCUMENT).containsEntry("originalName", "契約 2026.pdf")
            .containsEntry("uploadedBy", "it-docs").containsEntry("sizeBytes", FileSamples.pdf().length);
        // file.read reads every policy.
        get("/api/files/" + id, bearer("it-admin", "file.read")).expectStatus().isOk();
        // No read permission at all: refused before anything is looked up.
        get("/api/files/" + id, bearer("it-nobody", "commerce.document.upload")).expectStatus().isForbidden();
        get("/api/files/" + id + "/content", bearer("it-nobody")).expectStatus().isForbidden();
        // Another policy's read permission: the file is not visible.
        get("/api/files/" + id, bearer("it-photos", "commerce.media.read")).expectStatus().isNotFound();
        get("/api/files/" + id + "/content", bearer("it-photos", "commerce.media.read")).expectStatus().isNotFound();
        get("/api/files/not-a-uuid", documents()).expectStatus().isNotFound();
        get("/api/files/019c1347-d280-74b3-a3e9-25b7e53b7bac", documents()).expectStatus().isNotFound();
        client.get().uri("/api/files/" + id).exchange().expectStatus().isUnauthorized();
    }

    @Test
    void pdfIsADownloadThatRunsNothing() {
        byte[] content = FileSamples.pdf();
        String id = (String) uploaded(CommerceFiles.DOCUMENT, content, "契約 2026.pdf", "application/pdf",
            documents()).get("fileId");
        byte[] body = get("/api/files/" + id + "/content", documents())
            .expectStatus().isOk()
            .expectHeader().contentType("application/pdf")
            .expectHeader().valueEquals("X-Content-Type-Options", "nosniff")
            .expectHeader().valueEquals("Content-Security-Policy", "default-src 'none'; sandbox")
            .expectHeader().valueEquals(HttpHeaders.CACHE_CONTROL, "private, no-store")
            .expectHeader().value(HttpHeaders.CONTENT_DISPOSITION, value -> assertThat(value)
                .startsWith("attachment;").contains("filename*=UTF-8''"))
            .expectHeader().contentLength(content.length)
            .expectBody(byte[].class).returnResult().getResponseBody();
        assertThat(body).isEqualTo(content);
    }

    @Test
    void imagesAndTheirVariantsAreShownInline() {
        String photographer = photographer();
        Map<String, Object> info = uploaded(CommerceFiles.IMAGE, FileSamples.jpeg(800, 600), "shelf.jpg",
            "image/jpeg", photographer);
        String id = (String) info.get("fileId");
        assertThat(info.get("variants")).isEqualTo(List.of("w160", "w640"));
        get("/api/files/" + id + "/content/w160", photographer).expectStatus().isOk()
            .expectHeader().contentType("image/jpeg")
            .expectHeader().value(HttpHeaders.CONTENT_DISPOSITION, value -> assertThat(value)
                .startsWith("inline;").contains("shelf-w160.jpg"));
        get("/api/files/" + id + "/content/original", photographer).expectStatus().isOk();
        get("/api/files/" + id + "/content/w1280", photographer).expectStatus().isNotFound();
        // Never a path: refused by the firewall or as an unknown variant.
        get("/api/files/" + id + "/content/..%2F..%2Fetc", photographer).expectStatus().is4xxClientError();
        get("/api/files/" + id + "/content/.part", photographer).expectStatus().isNotFound();
    }

    @Test
    void audioCanBeReadInRanges() {
        String listener = bearer("it-listener", "it.audio.upload", "it.audio.read");
        byte[] mp3 = FileSamples.mp3();
        String id = (String) uploaded(ItFileFixtures.AUDIO, mp3, "song.mp3", "audio/mpeg", listener).get("fileId");
        String content = "/api/files/" + id + "/content";

        byte[] first = client.get().uri(content).header(HttpHeaders.AUTHORIZATION, listener)
            .header(HttpHeaders.RANGE, "bytes=0-9").exchange()
            .expectStatus().isEqualTo(206)
            .expectHeader().valueEquals(HttpHeaders.CONTENT_RANGE, "bytes 0-9/" + mp3.length)
            .expectHeader().valueEquals(HttpHeaders.ACCEPT_RANGES, "bytes")
            .expectBody(byte[].class).returnResult().getResponseBody();
        assertThat(first).isEqualTo(Arrays.copyOfRange(mp3, 0, 10));

        byte[] tail = client.get().uri(content).header(HttpHeaders.AUTHORIZATION, listener)
            .header(HttpHeaders.RANGE, "bytes=-100").exchange()
            .expectStatus().isEqualTo(206)
            .expectBody(byte[].class).returnResult().getResponseBody();
        assertThat(tail).isEqualTo(Arrays.copyOfRange(mp3, mp3.length - 100, mp3.length));

        byte[] rest = client.get().uri(content).header(HttpHeaders.AUTHORIZATION, listener)
            .header(HttpHeaders.RANGE, "bytes=1000-").exchange()
            .expectStatus().isEqualTo(206)
            .expectBody(byte[].class).returnResult().getResponseBody();
        assertThat(rest).isEqualTo(Arrays.copyOfRange(mp3, 1000, mp3.length));

        client.get().uri(content).header(HttpHeaders.AUTHORIZATION, listener)
            .header(HttpHeaders.RANGE, "bytes=" + mp3.length + "-").exchange()
            .expectStatus().isEqualTo(416)
            .expectHeader().valueEquals(HttpHeaders.CONTENT_RANGE, "bytes */" + mp3.length);
        // Several ranges: the whole file.
        client.get().uri(content).header(HttpHeaders.AUTHORIZATION, listener)
            .header(HttpHeaders.RANGE, "bytes=0-1,5-6").exchange()
            .expectStatus().isOk().expectHeader().contentLength(mp3.length);
    }

    @Test
    @SuppressWarnings("unchecked")
    void theExportDescribesTheUploadControl() {
        Map<String, Object> product = get("/api/meta/entities/Product", bearer("it-meta", "commerce.product.read"))
            .expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
        Map<String, Object> image = ((List<Map<String, Object>>) product.get("fields")).stream()
            .filter(field -> field.get("name").equals("imageFileId")).findFirst().orElseThrow();
        assertThat(image).containsEntry("type", "custom").containsEntry("kindId", "jabiz.file")
            .containsEntry("policy", CommerceFiles.IMAGE).containsEntry("maxBytes", 10 * 1024 * 1024)
            .containsEntry("image", true).containsEntry("variants", List.of("w160", "w640", "w1280"))
            .containsEntry("accept", List.of("image/jpeg", "image/png", ".jpg", ".jpeg", ".png"));
    }
}
