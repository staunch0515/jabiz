package com.jabiz.quizbuks.content.ai;

import com.jabiz.external.ExternalHttp;
import com.jabiz.external.ExternalJobContext;
import com.jabiz.external.ExternalJobException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The one call of Anthropic's Messages API QuizBuks makes ({@code POST /v1/messages}), through the platform's client of
 * the external service {@value ClaudeService#SERVICE} (no SDK, platform decision D38; docs/quizbuks/plans/Q8-ai.md,
 * D-Q8-1), written by hand as {@code StripeApi} is:
 * <ul>
 *   <li>the key in the {@code x-api-key} header only (the platform never prints header values), the API version
 *       pinned;</li>
 *   <li>adaptive thinking with the effort named explicitly (Opus 5.5 cannot switch thinking off; effort is what
 *       bounds its cost), the answer constrained to {@link AiPrompt#schema} (structured outputs), not streamed, at most
 *       {@value #MAX_TOKENS} output tokens;</li>
 *   <li>the provider's own fallback on a refusal ({@code fallbacks: "default"}, A-10), except on Haiku, which has
 *       none; the answer says which model served it;</li>
 *   <li>{@code metadata.user_id} the sponsor's opaque key (A-11), never their id, name or address;</li>
 *   <li>the user message at most {@value #MAX_INPUT_BYTES} bytes: a larger one is refused, never cut
 *       ({@link AiCodes#INPUT_TOO_LARGE}).</li>
 * </ul>
 * An answer other than 2xx fails the attempt as {@link ClaudeErrors} classifies it; a 2xx answer is returned whatever
 * its stop reason, so that its usage is recorded before the caller decides.
 */
public final class ClaudeApi {

    public static final int MAX_TOKENS = 16_000;
    /** The largest user message, in UTF-8 bytes: below the platform's 64 KiB job input, with room for the framing. */
    public static final int MAX_INPUT_BYTES = 60 * 1024;
    /** The beta of the provider's fallback on a refusal, by refusal category. */
    public static final String FALLBACK_BETA = "server-side-fallback-2026-07-01";

    private static final Pattern WORD = Pattern.compile("[a-z_]{1,40}");

    /**
     * What to ask.
     *
     * @param userKey the provider's opaque user id, or null to send none
     */
    public record Request(String model, String effort, String system, String user, String userKey) {}

    /** Tokens of one call, over every model that ran it (a fallback's included). */
    public record Usage(long inputTokens, long outputTokens) {}

    /**
     * A successful answer.
     *
     * @param servedModel     the model that produced the message
     * @param fellBack        whether the provider ran a fallback model
     * @param stopReason      {@code end_turn}, {@code refusal}, {@code max_tokens}, …
     * @param refusalCategory the refusal's category when it names one in plain words; null otherwise
     * @param text            the text of the answer (the JSON document), empty when there is none
     */
    public record Answer(String servedModel, boolean fellBack, Usage usage, String stopReason,
        String refusalCategory, String text) {}

    private final ExternalHttp http;
    private final String apiKey;
    private final JsonMapper json;

    ClaudeApi(ExternalHttp http, String apiKey, JsonMapper json) {
        this.http = http;
        this.apiKey = apiKey;
        this.json = json;
    }

    static ClaudeApi of(ExternalJobContext ctx, JsonMapper json) {
        return new ClaudeApi(ctx.http(ClaudeService.SERVICE), ctx.secret(ClaudeService.SERVICE, "apiKey"), json);
    }

    /** Sends the request; refused before sending when the user message is too large. */
    public Answer send(Request request) {
        int size = request.user().getBytes(StandardCharsets.UTF_8).length;
        if (size > MAX_INPUT_BYTES) {
            throw ExternalJobException.permanent(AiCodes.INPUT_TOO_LARGE, "The request is " + size
                + " bytes, more than " + MAX_INPUT_BYTES);
        }
        ExternalHttp.Request call = ExternalHttp.Request.post("/v1/messages")
            .header("x-api-key", apiKey)
            .header("anthropic-version", ClaudeService.API_VERSION)
            .json(body(request, json));
        if (fallsBack(request.model())) {
            call = call.header("anthropic-beta", FALLBACK_BETA);
        }
        ExternalHttp.Response response = http.send(call);
        return answer(response.status(), response.text(), json);
    }

    /** Whether the model has the provider's fallback on a refusal (Haiku 5.5 has none). */
    static boolean fallsBack(String model) {
        return !ClaudeService.HAIKU.equals(model);
    }

    /** The request's body. */
    public static String body(Request request, JsonMapper json) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", request.model());
        body.put("max_tokens", MAX_TOKENS);
        body.put("thinking", Map.of("type", "adaptive"));
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("effort", request.effort());
        output.put("format", Map.of("type", "json_schema", "schema", AiPrompt.schema()));
        body.put("output_config", output);
        body.put("system", request.system());
        body.put("messages", List.of(Map.of("role", "user", "content",
            List.of(Map.of("type", "text", "text", request.user())))));
        if (request.userKey() != null) {
            body.put("metadata", Map.of("user_id", request.userKey()));
        }
        if (fallsBack(request.model())) {
            body.put("fallbacks", "default");
        }
        return json.writeValueAsString(body);
    }

    /** A response read: a 2xx one as an {@link Answer}, any other as the failure it is. */
    public static Answer answer(int status, String text, JsonMapper json) {
        JsonNode root = parse(json, text);
        if (status < 200 || status >= 300) {
            JsonNode error = root == null ? null : root.get("error");
            String type = error != null && error.get("type") != null ? error.get("type").asString() : null;
            throw ClaudeErrors.failure(status, type);
        }
        if (root == null || !root.isObject()) {
            throw ClaudeErrors.badResponse("is not a JSON object");
        }
        String model = textOf(root.get("model"));
        String stop = textOf(root.get("stop_reason"));
        StringBuilder answer = new StringBuilder();
        boolean fellBack = false;
        JsonNode content = root.get("content");
        if (content != null && content.isArray()) {
            for (JsonNode block : content) {
                String type = textOf(block.get("type"));
                String part = textOf(block.get("text"));
                if ("text".equals(type) && part != null) {
                    answer.append(part);
                } else if ("fallback".equals(type)) {
                    fellBack = true;
                }
            }
        }
        Usage usage = usage(root.get("usage"));
        JsonNode iterations = root.get("usage") == null ? null : root.get("usage").get("iterations");
        if (iterations != null && iterations.isArray()) {
            for (JsonNode iteration : iterations) {
                if ("fallback_message".equals(textOf(iteration.get("type")))) {
                    fellBack = true;
                }
            }
        }
        String category = null;
        JsonNode details = root.get("stop_details");
        if (details != null && details.get("category") != null) {
            String value = textOf(details.get("category"));
            category = WORD.matcher(value).matches() ? value : null;
        }
        return new Answer(model, fellBack, usage, stop, category, answer.toString());
    }

    /**
     * The tokens of the call: with a fallback, {@code usage.iterations} lists every attempt the provider made (the top
     * level covers only the one that produced the message), so they are added up; cache writes and reads are input.
     */
    static Usage usage(JsonNode usage) {
        if (usage == null || !usage.isObject()) {
            return new Usage(0, 0);
        }
        JsonNode iterations = usage.get("iterations");
        if (iterations != null && iterations.isArray() && !iterations.isEmpty()) {
            long in = 0;
            long out = 0;
            for (JsonNode iteration : iterations) {
                in += input(iteration);
                out += count(iteration.get("output_tokens"));
            }
            return new Usage(in, out);
        }
        return new Usage(input(usage), count(usage.get("output_tokens")));
    }

    private static long input(JsonNode usage) {
        return count(usage.get("input_tokens")) + count(usage.get("cache_creation_input_tokens"))
            + count(usage.get("cache_read_input_tokens"));
    }

    private static long count(JsonNode node) {
        return node != null && node.isIntegralNumber() && node.asLong() > 0 ? node.asLong() : 0;
    }

    /** The candidates of an answer's text; null when it is not the document the schema asks for. */
    public static List<AiQuestionsJob.Candidate> candidates(String text, JsonMapper json) {
        JsonNode root = parse(json, text);
        JsonNode questions = root == null ? null : root.get("questions");
        if (questions == null || !questions.isArray()) {
            return null;
        }
        List<AiQuestionsJob.Candidate> candidates = new ArrayList<>();
        for (JsonNode question : questions) {
            if (!question.isObject()) {
                candidates.add(null);
                continue;
            }
            List<AiQuestionsJob.Option> options = new ArrayList<>();
            JsonNode given = question.get("options");
            if (given != null && given.isArray()) {
                for (JsonNode option : given) {
                    if (option.isObject()) {
                        JsonNode correct = option.get("correct");
                        options.add(new AiQuestionsJob.Option(textOf(option.get("text")),
                            correct != null && correct.isBoolean() && correct.asBoolean()));
                    }
                }
            }
            List<Integer> materials = new ArrayList<>();
            JsonNode numbers = question.get("materials");
            if (numbers != null && numbers.isArray()) {
                numbers.forEach(no -> {
                    if (no.isIntegralNumber() && no.canConvertToInt()) {
                        materials.add(no.asInt());
                    }
                });
            }
            JsonNode points = question.get("points");
            candidates.add(new AiQuestionsJob.Candidate(textOf(question.get("stem")), options,
                points != null && points.isIntegralNumber() && points.canConvertToInt() ? points.asInt() : null,
                materials));
        }
        return candidates;
    }

    private static String textOf(JsonNode node) {
        return node != null && node.isString() ? node.asString() : null;
    }

    private static JsonNode parse(JsonMapper json, String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return json.readTree(text);
        } catch (JacksonException e) {
            return null;
        }
    }

    @Override
    public String toString() {
        return "ClaudeApi[apiKey=***]";
    }
}
