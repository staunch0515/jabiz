package com.jabiz.quizbuks.content.ai;

import com.jabiz.external.ExternalJobException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PDF materials as the job sends them (Q8b, A-5; review H1/H2): never parsed on the server, sent as documents, counted
 * by the provider with exactly what is sent, and charged by that count when unanswered.
 */
class AiPdfsTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** Any bytes: nothing here reads them. */
    private static final byte[] PDF = "%PDF-1.4\n/Type /Pages /Count 1\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);

    private static ClaudeApi.Request request(String user, List<ClaudeApi.Document> documents) {
        return new ClaudeApi.Request(ClaudeService.OPUS, "medium", ClaudeApi.maxTokens(1), AiPrompt.SYSTEM, user,
            "qb-key", AiPrompt.schema(), documents);
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
    void documentsGoFirstAndTheCountTakesExactlyWhatIsSent() {
        AiQuestionsJob.Input input = new AiQuestionsJob.Input(ClaudeService.OPUS, "medium", "en", 1, "EASY", "T", null,
            List.of(new AiQuestionsJob.Material(1, "PDF", "Paper", "About", null, UUID.randomUUID())), List.of(),
            "qb-key");
        String user = AiPrompt.user(input, JSON);
        assertThat(user).contains("\"document\":\"Material 1\"");
        assertThat(AiPrompt.SYSTEM).contains("titled \"Material <no>\"");
        ClaudeApi.Document document = ClaudeApi.Document.of("Material 1", PDF);
        // Encoded once; the record does not print the file.
        assertThat(document.base64()).isEqualTo(Base64.getEncoder().encodeToString(PDF));
        assertThat(document.toString()).doesNotContain(document.base64());
        ClaudeApi.Request request = request(user, List.of(document));
        JsonNode body = JSON.readTree(ClaudeApi.body(request, JSON));
        JsonNode content = body.get("messages").get(0).get("content");
        assertThat(content).hasSize(2);
        assertThat(content.get(0).get("type").asString()).isEqualTo("document");
        assertThat(content.get(0).get("title").asString()).isEqualTo("Material 1");
        assertThat(Base64.getDecoder().decode(content.get(0).get("source").get("data").asString())).isEqualTo(PDF);
        assertThat(content.get(1).get("text").asString()).isEqualTo(user);
        assertThat(request.toString()).doesNotContain(document.base64()).doesNotContain(user);

        // The count: the same content, without what count_tokens does not take.
        JsonNode count = JSON.readTree(ClaudeApi.countBody(request, JSON));
        assertThat(count.get("messages")).isEqualTo(body.get("messages"));
        assertThat(count.get("system")).isEqualTo(body.get("system"));
        assertThat(count.get("output_config")).isEqualTo(body.get("output_config"));
        assertThat(count.get("model")).isEqualTo(body.get("model"));
        assertThat(count.has("max_tokens")).isFalse();
        assertThat(count.has("metadata")).isFalse();
        assertThat(count.has("fallbacks")).isFalse();
    }

    @Test
    void anUnansweredAttemptIsChargedTheProvidersCount() {
        ClaudeApi.Request request = request("Some text", List.of(ClaudeApi.Document.of("Material 1", PDF)));
        // Not counted (a request without documents): the text's estimate.
        assertThat(ClaudeApi.unanswered(request).inputTokens()).isEqualTo(AiLimits.inputEstimate(
            "Some text".getBytes(StandardCharsets.UTF_8).length
                + AiPrompt.SYSTEM.getBytes(StandardCharsets.UTF_8).length));
        ClaudeApi.Usage counted = ClaudeApi.unanswered(request.counted(41_234));
        assertThat(counted.inputTokens()).isEqualTo(41_234);
        assertThat(counted.outputTokens()).isEqualTo(ClaudeApi.maxTokens(1));
        // The reservation: the most pages a request may send, for every attempt.
        assertThat(AiLimits.estimate(3_000, ClaudeApi.maxTokens(1), 100) - AiLimits.estimate(3_000, 1))
            .isEqualTo(AiQuestionsJob.ATTEMPTS * 100L * ClaudeApi.TOKENS_PER_PDF_PAGE);
        assertThat(AiLimits.reservedInput(3_000, 100)).isEqualTo(AiLimits.inputEstimate(3_000)
            + 100L * ClaudeApi.TOKENS_PER_PDF_PAGE);
    }

    @Test
    void aProviderRefusingADocumentIsUnreadableOnlyWhenItSaysSo() {
        assertThat(ClaudeErrors.failure(400, "invalid_request_error", "The PDF specified was not valid.", true)
            .code()).isEqualTo(AiCodes.PDF_UNREADABLE);
        assertThat(ClaudeErrors.failure(400, "invalid_request_error", "Could not process document", true).code())
            .isEqualTo(AiCodes.PDF_UNREADABLE);
        // Without documents, or another error of a request with them: what it was.
        assertThat(ClaudeErrors.failure(400, "invalid_request_error", "The PDF specified was not valid.", false)
            .code()).isEqualTo(AiCodes.REQUEST_INVALID);
        assertThat(ClaudeErrors.failure(400, "invalid_request_error", "max_tokens: too large", true).code())
            .isEqualTo(AiCodes.REQUEST_INVALID);
        ExternalJobException busy = ClaudeErrors.failure(529, "overloaded_error", "Overloaded", true);
        assertThat(busy.isPermanent()).isFalse();
    }

    @Test
    void longPromptsArePricedPerCall() {
        // One call of 300,000 tokens in: above 100,000, so the whole call at 0.50 / 2.50 per million.
        ClaudeApi.Usage one = new ClaudeApi.Usage(300_000, 1_000).asLongPrompt();
        assertThat(AiCost.of(ClaudeService.HAIKU, null, one, null)).isEqualByComparingTo("0.152500");
        // Three calls of 100,000 each: the short price.
        assertThat(AiCost.of(ClaudeService.HAIKU, null, new ClaudeApi.Usage(300_000, 1_000), null))
            .isEqualByComparingTo("0.030500");
        // The larger models have one rate however long the prompt.
        assertThat(AiCost.hasLongPromptRate(ClaudeService.OPUS)).isFalse();
        assertThat(AiCost.hasLongPromptRate(ClaudeService.SONNET)).isFalse();
        assertThat(AiCost.hasLongPromptRate(ClaudeService.HAIKU)).isTrue();
    }
}
