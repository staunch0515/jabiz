package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.content.QbContent;
import com.jabiz.quizbuks.setup.QbRoles;
import com.jabiz.runtime.test.FileSamples;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Content files keep their metadata private (Q10b item 12; platform decision D47, {@code privateMetadata()}): a taker
 * reading a quiz's image, PDF or sound gets neither the sponsor's file name nor who uploaded it, and a neutral
 * download name; the uploading sponsor and administrators holding {@code file.read} still see both.
 */
class FileNamesIT extends PublicationItSupport {

    private String takerToken() {
        return TestTokens.bearer(tokens, "t-file-names", QbRoles.of(QbRoles.TAKER).permissions()
            .toArray(String[]::new));
    }

    private String fileAdmin() {
        return TestTokens.bearer(tokens, "file-admin", "file.read");
    }

    private Map<String, Object> meta(String id, String token) {
        return client.get().uri("/api/files/" + id).header(HttpHeaders.AUTHORIZATION, token).exchange()
            .expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
    }

    private String downloadName(String path, String token) {
        String header = client.get().uri(path).header(HttpHeaders.AUTHORIZATION, token).exchange()
            .expectStatus().isOk().returnResult(byte[].class).getResponseHeaders()
            .getFirst(HttpHeaders.CONTENT_DISPOSITION);
        assertThat(header).as(path).isNotNull();
        return ContentDisposition.parse(header).getFilename();
    }

    @Test
    void takersSeeNeitherTheNameNorTheUploaderOfContentFiles() {
        String image = fileId(QbContent.IMAGE_POLICY, FileSamples.jpeg(400, 300), "secret-answer-paris.jpg",
            "image/jpeg");
        String pdf = fileId(QbContent.PDF_POLICY, FileSamples.pdf(), "Acme internal notes.pdf", "application/pdf");
        String sound = fileId(QbContent.AUDIO_POLICY, FileSamples.mp3(), "answer is B.mp3", "audio/mpeg");
        Map<String, String> names = Map.of(image, "secret-answer-paris.jpg", pdf, "Acme internal notes.pdf", sound,
            "answer is B.mp3");
        Map<String, String> extensions = Map.of(image, ".jpg", pdf, ".pdf", sound, ".mp3");

        names.forEach((id, name) -> {
            Map<String, Object> seen = meta(id, takerToken());
            assertThat(seen.get("originalName")).as(name).isNull();
            assertThat(seen.get("uploadedBy")).as(name).isNull();
            assertThat(downloadName("/api/files/" + id + "/content", takerToken())).isEqualTo(id + extensions.get(id));

            for (String sees : java.util.List.of(sponsorA(), fileAdmin())) {
                Map<String, Object> full = meta(id, sees);
                assertThat(full).containsEntry("originalName", name).containsEntry("uploadedBy", "sponsor-a");
                assertThat(downloadName("/api/files/" + id + "/content", sees)).isEqualTo(name);
            }
        });
        // An image's variants too.
        assertThat(downloadName("/api/files/" + image + "/content/w320", takerToken())).isEqualTo(image + "-w320.jpg");
    }
}
