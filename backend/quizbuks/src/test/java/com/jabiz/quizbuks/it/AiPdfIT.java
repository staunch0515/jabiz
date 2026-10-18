package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.content.QbContent;
import com.jabiz.quizbuks.content.ai.AiCodes;
import com.jabiz.quizbuks.content.ai.AiLimits;
import com.jabiz.quizbuks.content.ai.AiProcesses;
import com.jabiz.quizbuks.content.ai.ClaudeApi;
import com.jabiz.quizbuks.content.ai.QbAi;
import com.jabiz.runtime.test.FileSamples;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PDF materials sent to the AI (docs/quizbuks/plans/Q8-ai.md, Q8b, A-5; platform decision D50): the file itself as a
 * document of the request, read by the job through its context; the size checked by the request, the pages by the
 * job before anything is sent; a password or a broken file ends the job, nothing sent or charged. The tests run with
 * at most {@value #MAX_PAGES} pages and {@value #MAX_BYTES} bytes ({@link AiItSupport}).
 */
class AiPdfIT extends AiItSupport {

    static final int MAX_PAGES = AiItSupport.TEST_PDF_MAX_PAGES;
    static final long MAX_BYTES = AiItSupport.TEST_PDF_MAX_BYTES;

    @BeforeEach
    void freshDay() {
        aiOn();
        advance(Duration.ofDays(1));
    }

    /** A PDF of {@code pages} empty pages, encrypted with a user password when asked. */
    static byte[] pdf(int pages, boolean encrypted) {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (int i = 0; i < pages; i++) {
                document.addPage(new PDPage());
            }
            if (encrypted) {
                StandardProtectionPolicy policy = new StandardProtectionPolicy("owner", "user",
                    new AccessPermission());
                policy.setEncryptionKeyLength(128);
                document.protect(policy);
            }
            document.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The sponsor's PDF material of a new quiz with an article; returns {quizId, articleId, pdfMaterialId, fileId}. */
    private List<String> quizWithPdf(String token, byte[] pdf) {
        List<String> quiz = quizWithArticle(token, "PDF quiz " + UUID.randomUUID(), "Paris is in France.");
        String fileId = (String) upload(QbContent.PDF_POLICY, pdf, "material.pdf", "application/pdf", token)
            .expectStatus().isCreated().expectBody(MAP).returnResult().getResponseBody().get("fileId");
        String material = (String) run("QB_MATERIAL_SAVE", token, body("quizId", quiz.get(0), "kind", "PDF",
            "title", "The paper", "description", "A paper about rivers", "pdf", fileId)).get("itemId");
        return List.of(quiz.get(0), quiz.get(1), material, fileId);
    }

    @Test
    void aPdfMaterialGoesAsTheFileItself() {
        String token = sponsorFull(SPONSOR_A);
        byte[] pdf = pdf(2, false);
        List<String> quiz = quizWithPdf(token, pdf);
        Map<String, Object> requested = request(token, quiz.get(0), List.of(quiz.get(1), quiz.get(2)), 2);
        Object aiJobId = requested.get("aiJobId");
        Map<String, Object> job = job(aiJobId);
        // The pages are not known yet: the most a request may send is reserved.
        assertThat(((Number) job.get("estimated_tokens")).longValue()).isEqualTo(AiLimits.estimate(
            ((Number) job.get("input_bytes")).longValue(), ClaudeApi.maxTokens(2), MAX_PAGES));
        assertThat(String.valueOf(job.get("params"))).contains("\"pdfMaterialNos\":[2]");
        // The job reads the file (the platform checked the sponsor may), not its content in the input.
        assertThat(query("SELECT file_id FROM sys_external_job_file WHERE job_id = ? AND direction = 'INPUT'",
            String.valueOf(job.get("external_job_id")))).singleElement()
            .satisfies(row -> assertThat(row.get("file_id")).isEqualTo(quiz.get(3)));
        runJobs();

        assertThat(job(aiJobId)).containsEntry("status", QbAi.READY);
        JsonNode body = sent().getLast();
        List<JsonNode> documents = documents(body);
        assertThat(documents).singleElement().satisfies(document -> {
            assertThat(document.get("title").asString()).isEqualTo("Material 2");
            assertThat(document.get("source").get("media_type").asString()).isEqualTo("application/pdf");
            assertThat(Base64.getDecoder().decode(document.get("source").get("data").asString())).isEqualTo(pdf);
        });
        // The documents come first; the request's text names the material's document.
        JsonNode content = body.get("messages").get(0).get("content");
        assertThat(content.get(0).get("type").asString()).isEqualTo("document");
        assertThat(content.get(content.size() - 1).get("type").asString()).isEqualTo("text");
        assertThat(userText(body)).contains("\"document\":\"Material 2\"", "Paris is in France.");
    }

    @Test
    void tooLargeTooLongAndUnreadablePdfsAreRefused() {
        String token = sponsorFull(SPONSOR_A);
        // Larger than a request may send: refused at once, no job.
        byte[] large = new byte[(int) MAX_BYTES + 1024];
        byte[] head = FileSamples.pdf();
        System.arraycopy(head, 0, large, 0, head.length);
        List<String> big = quizWithPdf(token, large);
        long jobs = count("sys_external_job");
        assertThat(codes(refused(AiProcesses.REQUEST, token, requestInput(big.get(0), List.of(big.get(2)), 1), 422)))
            .contains(AiCodes.PDF_TOO_LARGE);
        assertThat(count("sys_external_job")).isEqualTo(jobs);
        // Without the PDF it is fine.
        request(token, big.get(0), List.of(big.get(1)), 1);
        runJobs();

        // More pages than a request may send: the job counts them and ends before sending anything.
        List<String> longOne = quizWithPdf(token, pdf(MAX_PAGES + 1, false));
        int calls = CLAUDE.requests(MESSAGES).size();
        Object tooLong = request(token, longOne.get(0), List.of(longOne.get(2)), 1).get("aiJobId");
        runJobs();
        assertThat(job(tooLong)).containsEntry("status", QbAi.FAILED).containsEntry("failure_code",
            AiCodes.PDF_TOO_LONG);
        assertThat(((Number) job(tooLong).get("input_tokens")).longValue()).isZero();
        assertThat(((Number) job(tooLong).get("attempts")).intValue()).isEqualTo(1);

        // A password: the same.
        List<String> locked = quizWithPdf(token, pdf(1, true));
        Object encrypted = request(token, locked.get(0), List.of(locked.get(2)), 1).get("aiJobId");
        runJobs();
        assertThat(job(encrypted)).containsEntry("status", QbAi.FAILED).containsEntry("failure_code",
            AiCodes.PDF_UNREADABLE);
        // No PDF of a PDF material that is no PDF at all (the platform's minimal sample has no pages tree).
        List<String> broken = quizWithPdf(token, FileSamples.pdf());
        Object unreadable = request(token, broken.get(0), List.of(broken.get(2)), 1).get("aiJobId");
        runJobs();
        assertThat(job(unreadable)).containsEntry("status", QbAi.FAILED).containsEntry("failure_code",
            AiCodes.PDF_UNREADABLE);
        assertThat(CLAUDE.requests(MESSAGES)).hasSize(calls);
    }
}
