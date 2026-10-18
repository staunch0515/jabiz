package com.jabiz.quizbuks.content.ai;

import com.jabiz.external.ExternalJobException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** PDF materials as the job sends them (Q8b, A-5): counted, checked, sent as documents, charged when unanswered. */
class AiPdfsTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    static byte[] pdf(int pages, boolean encrypted) throws IOException {
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
        }
    }

    private static String code(Runnable run) {
        try {
            run.run();
            return null;
        } catch (ExternalJobException e) {
            return e.code();
        }
    }

    @Test
    void pagesAreCountedAndUnreadableOrLockedPdfsRefused() throws IOException {
        assertThat(AiPdfs.pages(pdf(3, false))).isEqualTo(3);
        assertThat(code(() -> AiPdfs.pages(new byte[0]))).isEqualTo(AiCodes.PDF_UNREADABLE);
        assertThat(code(() -> AiPdfs.pages("not a pdf".getBytes(StandardCharsets.US_ASCII))))
            .isEqualTo(AiCodes.PDF_UNREADABLE);
        byte[] locked = pdf(1, true);
        assertThat(code(() -> AiPdfs.pages(locked))).isEqualTo(AiCodes.PDF_UNREADABLE);
        // More pages than allowed, all documents together.
        List<ClaudeApi.Document> documents = List.of(new ClaudeApi.Document("Material 1", new byte[1], 3),
            new ClaudeApi.Document("Material 2", new byte[1], 3));
        assertThat(code(() -> AiPdfs.requireWithin(documents, 6))).isNull();
        assertThat(code(() -> AiPdfs.requireWithin(documents, 5))).isEqualTo(AiCodes.PDF_TOO_LONG);
    }

    @Test
    void theLimitsAreBounded() {
        assertThat(new AiPdfs.Limits(AiPdfs.DEFAULT_MAX_BYTES, AiPdfs.DEFAULT_MAX_PAGES).maxPages()).isEqualTo(100);
        assertThatThrownBy(() -> new AiPdfs.Limits(AiQuestionsJob.MAX_PDF_BYTES + 1, 10))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AiPdfs.Limits(1, AiPdfs.MAX_PAGES + 1))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AiPdfs.Limits(1, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void documentsGoFirstAndAreChargedByThePageWhenUnanswered() throws IOException {
        byte[] pdf = pdf(2, false);
        AiQuestionsJob.Input input = new AiQuestionsJob.Input(ClaudeService.OPUS, "medium", "en", 1, "EASY", "T", null,
            List.of(new AiQuestionsJob.Material(1, "PDF", "Paper", "About", null, UUID.randomUUID())), List.of(),
            "qb-key");
        String user = AiPrompt.user(input, JSON);
        assertThat(user).contains("\"document\":\"Material 1\"");
        assertThat(AiPrompt.SYSTEM).contains("titled \"Material <no>\"");
        ClaudeApi.Request request = new ClaudeApi.Request(ClaudeService.OPUS, "medium", ClaudeApi.maxTokens(1),
            AiPrompt.SYSTEM, user, "qb-key", AiPrompt.schema(), List.of(new ClaudeApi.Document("Material 1", pdf, 2)));
        JsonNode content = JSON.readTree(ClaudeApi.body(request, JSON)).get("messages").get(0).get("content");
        assertThat(content).hasSize(2);
        assertThat(content.get(0).get("type").asString()).isEqualTo("document");
        assertThat(content.get(0).get("title").asString()).isEqualTo("Material 1");
        assertThat(Base64.getDecoder().decode(content.get(0).get("source").get("data").asString())).isEqualTo(pdf);
        assertThat(content.get(1).get("text").asString()).isEqualTo(user);
        // The PDF's bytes are not the user message's: the cap is on the text only.
        assertThat(request.toString()).doesNotContain(Base64.getEncoder().encodeToString(pdf));

        ClaudeApi.Usage unanswered = ClaudeApi.unanswered(request);
        ClaudeApi.Usage withoutPages = ClaudeApi.unanswered(new ClaudeApi.Request(ClaudeService.OPUS, "medium",
            ClaudeApi.maxTokens(1), AiPrompt.SYSTEM, user, "qb-key"));
        assertThat(unanswered.inputTokens() - withoutPages.inputTokens())
            .isEqualTo(2L * ClaudeApi.TOKENS_PER_PDF_PAGE);
        // The estimate of a request with PDFs reserves the most pages a request may send, for every attempt.
        assertThat(AiLimits.estimate(3_000, ClaudeApi.maxTokens(1), 100) - AiLimits.estimate(3_000, 1))
            .isEqualTo(AiQuestionsJob.ATTEMPTS * 100L * ClaudeApi.TOKENS_PER_PDF_PAGE);
    }

    @Test
    void haikuLongPromptsArePricedAtTheirOwnPrice() {
        ClaudeApi.Usage usage = new ClaudeApi.Usage(300_000, 1_000);
        // 300,000 tokens in one attempt: above 100,000, so 0.50 / 2.50 per million.
        assertThat(AiCost.of(ClaudeService.HAIKU, null, usage, null, 1)).isEqualByComparingTo("0.152500");
        // The same over three attempts: 100,000 each, the short price.
        assertThat(AiCost.of(ClaudeService.HAIKU, null, usage, null, 3)).isEqualByComparingTo("0.030500");
        assertThat(AiCost.of(ClaudeService.OPUS, null, usage, null, 1)).isEqualByComparingTo(
            AiCost.of(ClaudeService.OPUS, null, usage, null));
    }
}
