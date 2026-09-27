package com.jabiz.app.it.file;

import com.jabiz.app.commerce.CommerceFiles;
import com.jabiz.app.it.fixture.ItFileFixtures;
import com.jabiz.runtime.test.FileSamples;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.BodyInserters;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Limits of uploads (docs/design/14-files.md section 5; ROADMAP 13b acceptance 2): an upload over the policy's limit
 * is abandoned before the rest of the request is read, a declared length over the request limit is refused at once,
 * and uploads are rate limited per actor.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = "jabiz.files.upload-rate-per-minute=3")
class FileLimitsIT extends FileItSupport {

    private static final int CHUNK = 16 * 1024;
    /** What the client would send if nobody stopped it: far more than any limit. */
    private static final long ENDLESS = 512L * 1024 * 1024;

    private String tiny(String actor) {
        return bearer(actor, "it.tiny.upload", "it.tiny.read");
    }

    @Test
    void anUploadOverThePolicyLimitIsAbandonedWhileStreaming() {
        AtomicLong sent = new AtomicLong();
        byte[] head = ("--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"big.pdf\"\r\n"
            + "Content-Type: application/pdf\r\n\r\n%PDF-1.4\n").getBytes(StandardCharsets.UTF_8);
        byte[] filler = new byte[CHUNK];
        Arrays.fill(filler, (byte) 'x');
        Flux<DataBuffer> body = Flux.concat(
            Flux.just(DefaultDataBufferFactory.sharedInstance.wrap(head)),
            Flux.<DataBuffer>generate(sink -> {
                if (sent.addAndGet(CHUNK) > ENDLESS) {
                    sink.complete();
                } else {
                    sink.next(DefaultDataBufferFactory.sharedInstance.wrap(filler.clone()));
                }
            }));
        List<String> before = storedKeys();

        Map<String, Object> problem = client.post().uri("/api/files?policy=" + ItFileFixtures.TINY)
            .header(HttpHeaders.AUTHORIZATION, tiny("it-streamer"))
            .contentType(multipartType())
            .body(BodyInserters.fromDataBuffers(body))
            .exchange()
            .expectStatus().isEqualTo(413)
            .expectBody(MAP).returnResult().getResponseBody();

        assertThat(ruleCodes(problem)).containsExactly("FILE_TOO_LARGE");
        // Stopped right after the limit (64 KB), give or take the buffers in flight; nowhere near the whole body.
        assertThat(sent.get()).isLessThan(4L * 1024 * 1024);
        assertThat(storedKeys()).isEqualTo(before);
        assertThat(uploadLeftovers()).isEmpty();
    }

    @Test
    void aDeclaredLengthOverTheRequestLimitIsRefusedAtOnce() {
        Map<String, Object> problem = client.post().uri("/api/files?policy=" + ItFileFixtures.TINY)
            .header(HttpHeaders.AUTHORIZATION, tiny("it-declarer"))
            .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(200L * 1024 * 1024))
            .contentType(multipartType())
            // A stream, so that the client keeps the declared length rather than computing its own.
            .body(BodyInserters.fromDataBuffers(Flux.just(DefaultDataBufferFactory.sharedInstance.wrap(
                multipart(new Part("file", "a.pdf", "application/pdf", FileSamples.pdf()))))))
            .exchange()
            .expectStatus().isEqualTo(413)
            .expectBody(MAP).returnResult().getResponseBody();
        assertThat(ruleCodes(problem)).containsExactly("FILE_TOO_LARGE");
    }

    @Test
    void justUnderTheLimitIsAccepted() {
        byte[] pdf = new byte[(int) ItFileFixtures.TINY_MAX_BYTES];
        byte[] start = FileSamples.pdf();
        System.arraycopy(start, 0, pdf, 0, start.length);
        uploaded(ItFileFixtures.TINY, pdf, "exact.pdf", "application/pdf", tiny("it-exact"));
        byte[] over = Arrays.copyOf(pdf, pdf.length + 1);
        upload(ItFileFixtures.TINY, over, "over.pdf", "application/pdf", tiny("it-exact"))
            .expectStatus().isEqualTo(413);
    }

    @Test
    void uploadsAreRateLimitedPerActor() {
        String greedy = bearer("it-greedy", "commerce.document.upload");
        for (int i = 0; i < 3; i++) {
            upload(CommerceFiles.DOCUMENT, FileSamples.pdf(), "a.pdf", "application/pdf", greedy)
                .expectStatus().isCreated();
        }
        Map<String, Object> problem = upload(CommerceFiles.DOCUMENT, FileSamples.pdf(), "a.pdf", "application/pdf",
            greedy)
            .expectStatus().isEqualTo(429)
            .expectHeader().value(HttpHeaders.RETRY_AFTER, value -> assertThat(Long.parseLong(value)).isPositive())
            .expectBody(MAP).returnResult().getResponseBody();
        assertThat(ruleCodes(problem)).containsExactly("RATE_LIMITED");
        // Another actor is not affected.
        upload(CommerceFiles.DOCUMENT, FileSamples.pdf(), "a.pdf", "application/pdf",
            bearer("it-patient", "commerce.document.upload")).expectStatus().isCreated();
    }
}
