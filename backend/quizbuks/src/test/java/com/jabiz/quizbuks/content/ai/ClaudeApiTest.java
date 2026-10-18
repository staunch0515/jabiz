package com.jabiz.quizbuks.content.ai;

import com.jabiz.external.ExternalHttp;
import com.jabiz.external.ExternalJobException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** How {@link ClaudeApi} builds the Messages API request and reads its answers (Q8a, D-Q8-1). */
class ClaudeApiTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static ClaudeApi.Request request(String model) {
        return new ClaudeApi.Request(model, "medium", ClaudeApi.maxTokens(2), AiPrompt.SYSTEM,
            "Number of questions: 2", "qb-0123");
    }

    @Test
    void theRequestNamesTheModelEffortThinkingAndSchema() {
        JsonNode body = JSON.readTree(ClaudeApi.body(request(ClaudeService.OPUS), JSON));
        assertThat(body.get("model").asString()).isEqualTo("claude-opus-5-5");
        // Scaled to the questions asked: 4,000 and 600 a question.
        assertThat(body.get("max_tokens").asInt()).isEqualTo(5_200);
        assertThat(body.get("thinking").get("type").asString()).isEqualTo("adaptive");
        assertThat(body.get("output_config").get("effort").asString()).isEqualTo("medium");
        JsonNode format = body.get("output_config").get("format");
        assertThat(format.get("type").asString()).isEqualTo("json_schema");
        JsonNode question = format.get("schema").get("properties").get("questions").get("items");
        assertThat(question.get("additionalProperties").asBoolean()).isFalse();
        assertThat(question.get("required").toString()).contains("stem", "options", "points", "materials");
        // No numeric or length bounds: the API refuses them (AiCandidates checks those).
        assertThat(format.toString()).doesNotContain("minimum", "maxLength", "minItems");
        assertThat(body.get("system").asString()).startsWith("You write multiple-choice questions");
        assertThat(body.get("messages").get(0).get("role").asString()).isEqualTo("user");
        assertThat(body.get("metadata").get("user_id").asString()).isEqualTo("qb-0123");
        assertThat(body.get("fallbacks").asString()).isEqualTo("default");
        // Never streamed, no sampling parameters, no thinking budget.
        assertThat(body.has("stream")).isFalse();
        assertThat(body.has("temperature")).isFalse();
        assertThat(body.get("thinking").has("budget_tokens")).isFalse();
    }

    @Test
    void haikuHasNoFallbackAndNoUserIdIsSentWhenNoneIsGiven() {
        JsonNode body = JSON.readTree(ClaudeApi.body(new ClaudeApi.Request(ClaudeService.HAIKU, "low", 4_600,
            AiPrompt.SYSTEM, "x", null), JSON));
        assertThat(body.has("fallbacks")).isFalse();
        assertThat(body.has("metadata")).isFalse();
        assertThat(ClaudeApi.fallsBack(ClaudeService.SONNET)).isTrue();
    }

    @Test
    void theKeyGoesInItsHeaderAndTheBetaWithTheFallback() {
        List<ExternalHttp.Request> sent = new ArrayList<>();
        ExternalHttp http = call -> {
            sent.add(call);
            return new ExternalHttp.Response(200, Map.of(), answer("end_turn", "{\"questions\":[]}", "")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        };
        new ClaudeApi(http, "sk-ant-test", JSON).send(request(ClaudeService.OPUS));
        ExternalHttp.Request call = sent.getFirst();
        assertThat(call.method()).isEqualTo("POST");
        assertThat(call.target()).isEqualTo("/v1/messages");
        assertThat(call.headers()).containsEntry("x-api-key", "sk-ant-test")
            .containsEntry("anthropic-version", "2023-06-01")
            .containsEntry("anthropic-beta", ClaudeApi.FALLBACK_BETA);
        // The request prints without the key.
        assertThat(call.toString()).doesNotContain("sk-ant-test");
        assertThat(new ClaudeApi(http, "sk-ant-test", JSON).toString()).doesNotContain("sk-ant-test");
    }

    @Test
    void aLargerUserMessageIsRefusedNeverCut() {
        ExternalHttp http = call -> {
            throw new AssertionError("nothing is sent");
        };
        String large = "a".repeat(ClaudeApi.MAX_INPUT_BYTES + 1);
        assertThatThrownBy(() -> new ClaudeApi(http, "k", JSON).send(new ClaudeApi.Request(ClaudeService.OPUS,
            "medium", 4_600, AiPrompt.SYSTEM, large, null)))
            .isInstanceOfSatisfying(ExternalJobException.class, e -> {
                assertThat(e.code()).isEqualTo(AiCodes.INPUT_TOO_LARGE);
                assertThat(e.isPermanent()).isTrue();
            });
        // Exactly the cap passes (three-byte characters count as three).
        String cap = "あ".repeat(ClaudeApi.MAX_INPUT_BYTES / 3);
        ExternalHttp answering = call -> new ExternalHttp.Response(200, Map.of(),
            answer("end_turn", "{}", "").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        new ClaudeApi(answering, "k", JSON).send(new ClaudeApi.Request(ClaudeService.OPUS, "medium", 4_600,
            AiPrompt.SYSTEM, cap, null));
    }

    private static String answer(String stop, String text, String extra) {
        return "{\"id\":\"msg_1\",\"type\":\"message\",\"model\":\"claude-opus-5-5\",\"stop_reason\":\"" + stop
            + "\",\"content\":[{\"type\":\"thinking\",\"thinking\":\"\",\"signature\":\"x\"}"
            + (text == null ? "" : ",{\"type\":\"text\",\"text\":" + JSON.writeValueAsString(text) + "}")
            + "],\"usage\":{\"input_tokens\":100,\"cache_read_input_tokens\":20,\"cache_creation_input_tokens\":5,"
            + "\"output_tokens\":40}" + extra + "}";
    }

    @Test
    void aNormalAnswerGivesItsTextAndUsage() {
        ClaudeApi.Answer answer = ClaudeApi.answer(200, answer("end_turn", "{\"questions\":[]}", ""), JSON);
        assertThat(answer.stopReason()).isEqualTo("end_turn");
        assertThat(answer.text()).isEqualTo("{\"questions\":[]}");
        assertThat(answer.servedModel()).isEqualTo("claude-opus-5-5");
        assertThat(answer.fellBack()).isFalse();
        // Cache writes and reads are input too.
        assertThat(answer.usage()).isEqualTo(new ClaudeApi.Usage(125, 40));
    }

    @Test
    void aFallbackCountsEveryAttemptsUsage() {
        String body = "{\"model\":\"claude-opus-5\",\"stop_reason\":\"end_turn\",\"content\":["
            + "{\"type\":\"fallback\",\"from\":{\"model\":\"claude-opus-5-5\"},\"to\":{\"model\":\"claude-opus-5\"}},"
            + "{\"type\":\"text\",\"text\":\"{}\"}],\"usage\":{\"input_tokens\":10,\"output_tokens\":5,"
            + "\"iterations\":[{\"type\":\"message\",\"input_tokens\":100,\"output_tokens\":0},"
            + "{\"type\":\"fallback_message\",\"input_tokens\":110,\"output_tokens\":30}]}}";
        ClaudeApi.Answer answer = ClaudeApi.answer(200, body, JSON);
        assertThat(answer.fellBack()).isTrue();
        assertThat(answer.servedModel()).isEqualTo("claude-opus-5");
        // The fallback model's part, priced at that model.
        assertThat(answer.usage()).isEqualTo(new ClaudeApi.Usage(210, 30, 110, 30));
    }

    @Test
    void theOutputAskedForFollowsTheCountWithinTheCeiling() {
        assertThat(ClaudeApi.maxTokens(1)).isEqualTo(4_600);
        assertThat(ClaudeApi.maxTokens(10)).isEqualTo(10_000);
        assertThat(ClaudeApi.maxTokens(20)).isEqualTo(ClaudeApi.MAX_TOKENS);
        assertThat(ClaudeApi.maxTokens(1_000)).isEqualTo(ClaudeApi.MAX_TOKENS);
        assertThat(ClaudeApi.maxTokens(0)).isEqualTo(4_600);
    }

    @Test
    void aCallWithoutAnAnswerIsChargedItsEstimateUnlessItWasNeverSent() {
        ClaudeApi.Request request = new ClaudeApi.Request(ClaudeService.OPUS, "medium", ClaudeApi.maxTokens(5),
            "s".repeat(300), "u".repeat(3_000), null);
        // The input at a token per three bytes and the framing; all the output asked for.
        assertThat(ClaudeApi.unanswered(request)).isEqualTo(new ClaudeApi.Usage(1_100 + AiLimits.FRAMING_TOKENS,
            7_000));
        String service = ClaudeService.SERVICE;
        assertThat(ClaudeApi.mayHaveBeenBilled(new com.jabiz.external.ExternalCallException(service,
            com.jabiz.external.ExternalCallException.Kind.TIMEOUT, "no answer within PT2M"))).isTrue();
        assertThat(ClaudeApi.mayHaveBeenBilled(new com.jabiz.external.ExternalCallException(service,
            com.jabiz.external.ExternalCallException.Kind.TOO_LARGE, "answer larger than 1048576 bytes"))).isTrue();
        assertThat(ClaudeApi.mayHaveBeenBilled(new com.jabiz.external.ExternalCallException(service,
            com.jabiz.external.ExternalCallException.Kind.UNAVAILABLE, "connection failed (IOException)"))).isTrue();
        assertThat(ClaudeApi.mayHaveBeenBilled(new com.jabiz.external.ExternalCallException(service,
            com.jabiz.external.ExternalCallException.Kind.UNAVAILABLE, "connection failed (ConnectException)")))
            .isFalse();
        assertThat(ClaudeApi.mayHaveBeenBilled(new com.jabiz.external.ExternalCallException(service,
            com.jabiz.external.ExternalCallException.Kind.REFUSED, "host not allowed"))).isFalse();
    }

    @Test
    void theRequestsOwnTimeoutIsPassedOn() {
        List<ExternalHttp.Request> sent = new ArrayList<>();
        ExternalHttp http = call -> {
            sent.add(call);
            return new ExternalHttp.Response(200, Map.of(), answer("end_turn", "{}", "")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        };
        new ClaudeApi(http, "k", JSON).send(request(ClaudeService.OPUS), java.time.Duration.ofSeconds(30));
        assertThat(sent.getFirst().timeout()).isEqualTo(java.time.Duration.ofSeconds(30));
    }

    @Test
    void refusalsAndCutAnswersAreReturnedWithTheirUsage() {
        ClaudeApi.Answer refusal = ClaudeApi.answer(200, answer("refusal", null,
            ",\"stop_details\":{\"type\":\"refusal\",\"category\":\"cyber\",\"explanation\":\"no\"}"), JSON);
        assertThat(refusal.stopReason()).isEqualTo("refusal");
        assertThat(refusal.refusalCategory()).isEqualTo("cyber");
        assertThat(refusal.text()).isEmpty();
        assertThat(refusal.usage().inputTokens()).isEqualTo(125);
        assertThat(refusal.recommendedModel()).isNull();
        ClaudeApi.Answer odd = ClaudeApi.answer(200, answer("refusal", null,
            ",\"stop_details\":{\"category\":\"<script>\",\"recommended_model\":\"<b>x\"}"), JSON);
        assertThat(odd.refusalCategory()).isNull();
        assertThat(odd.recommendedModel()).isNull();
        // The fallback could not run: the provider names a model to try directly.
        ClaudeApi.Answer busy = ClaudeApi.answer(200, answer("refusal", null,
            ",\"stop_details\":{\"category\":\"cyber\",\"recommended_model\":\"claude-opus-4-8\"}"), JSON);
        assertThat(busy.recommendedModel()).isEqualTo("claude-opus-4-8");
        ClaudeApi.Answer none = ClaudeApi.answer(200, answer("refusal", null,
            ",\"stop_details\":{\"category\":null}"), JSON);
        assertThat(none.refusalCategory()).isNull();
        assertThat(ClaudeApi.answer(200, answer("max_tokens", "{\"questions\":[{", ""), JSON).stopReason())
            .isEqualTo("max_tokens");
    }

    @Test
    void errorsAreClassifiedWithoutTheProvidersMessage() {
        String error = "{\"type\":\"error\",\"error\":{\"type\":\"%s\",\"message\":\"secret detail\"}}";
        assertFailure(429, error.formatted("rate_limit_error"), AiCodes.RATE_LIMITED, false);
        assertFailure(529, error.formatted("overloaded_error"), AiCodes.PROVIDER_UNAVAILABLE, false);
        assertFailure(500, "not json", AiCodes.PROVIDER_UNAVAILABLE, false);
        assertFailure(408, "", AiCodes.PROVIDER_UNAVAILABLE, false);
        assertFailure(401, error.formatted("authentication_error"), AiCodes.AUTH, true);
        assertFailure(403, error.formatted("permission_error"), AiCodes.AUTH, true);
        assertFailure(400, error.formatted("invalid_request_error"), AiCodes.REQUEST_INVALID, true);
        assertFailure(413, error.formatted("request_too_large"), AiCodes.REQUEST_INVALID, true);
        // A 200 that is no JSON object: tried again.
        assertFailure(200, "[1, 2]", AiCodes.BAD_RESPONSE, false);
        assertFailure(200, "", AiCodes.BAD_RESPONSE, false);
    }

    private static void assertFailure(int status, String body, String code, boolean permanent) {
        assertThatThrownBy(() -> ClaudeApi.answer(status, body, JSON))
            .isInstanceOfSatisfying(ExternalJobException.class, e -> {
                assertThat(e.code()).as(status + " " + body).isEqualTo(code);
                assertThat(e.isPermanent()).as(status + " " + body).isEqualTo(permanent);
                assertThat(e.getMessage()).doesNotContain("secret detail");
                if (status != 200) {
                    assertThat(e.getMessage()).contains(String.valueOf(status));
                }
            });
    }

    @Test
    void candidatesAreReadLeniently() {
        String text = "{\"questions\":[{\"stem\":\"Q?\",\"points\":5,\"materials\":[1,\"x\",2],"
            + "\"options\":[{\"text\":\"a\",\"correct\":true},{\"text\":\"b\",\"correct\":\"yes\"},7]},"
            + "\"not an object\",{\"stem\":null}]}";
        List<AiQuestionsJob.Candidate> candidates = ClaudeApi.candidates(text, JSON);
        assertThat(candidates).hasSize(3);
        AiQuestionsJob.Candidate first = candidates.getFirst();
        assertThat(first.stem()).isEqualTo("Q?");
        assertThat(first.points()).isEqualTo(5);
        assertThat(first.materials()).containsExactly(1, 2);
        // A correct flag that is not a boolean is not correct; a non-object option is dropped.
        assertThat(first.options()).containsExactly(new AiQuestionsJob.Option("a", true),
            new AiQuestionsJob.Option("b", false));
        assertThat(candidates.get(1)).isNull();
        assertThat(candidates.get(2).stem()).isNull();
        assertThat(ClaudeApi.candidates("{\"other\":1}", JSON)).isNull();
        assertThat(ClaudeApi.candidates("not json", JSON)).isNull();
    }
}
