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
 *       {@link #maxTokens} output tokens: scaled to the questions asked, never above {@value #MAX_TOKENS};</li>
 *   <li>the provider's own fallback on a refusal ({@code fallbacks: "default"}, A-10), except on Haiku, which has
 *       none, and except on a request with PDF documents (Q8b re-review M-a): a fallback runs the input again on
 *       another model, so the paid call would bill more than the count it was checked by; without it every paid PDF
 *       call is exactly the counted request, and a refusal is tried again on the next attempt (counted again); the
 *       answer says which model served it;</li>
 *   <li>{@code metadata.user_id} the sponsor's opaque key (A-11), never their id, name or address;</li>
 *   <li>the user message at most {@value #MAX_INPUT_BYTES} bytes: a larger one is refused, never cut
 *       ({@link AiCodes#INPUT_TOO_LARGE}).</li>
 * </ul>
 * An answer other than 2xx fails the attempt as {@link ClaudeErrors} classifies it; a 2xx answer is returned whatever
 * its stop reason, so that its usage is recorded before the caller decides. No answer at all (a time-out, a connection
 * lost) is the platform's {@link com.jabiz.external.ExternalCallException}: the caller charges the attempt's estimate
 * ({@link #unanswered}), since the provider may have billed a whole answer nobody read.
 */
public final class ClaudeApi {

    /** The most output tokens a request may ask for (thinking included), whatever the count. */
    public static final int MAX_TOKENS = 16_000;
    /** Output tokens asked for: this much for thinking and framing, and {@link #TOKENS_PER_QUESTION} per question. */
    public static final int BASE_TOKENS = 4_000;
    public static final int TOKENS_PER_QUESTION = 600;
    /**
     * The largest user message, in UTF-8 bytes: below the job's input limit ({@link AiQuestionsJob#MAX_JOB_INPUT_BYTES},
     * 256 KiB since Q8b, platform decision D50), with room for the framing. PDF materials are not part of it: they go
     * as documents ({@link Document}).
     */
    public static final int MAX_INPUT_BYTES = 250 * 1024;
    /**
     * Input tokens one PDF page may come to: Anthropic counts each page's text (1,500 to 3,000 tokens for a dense page)
     * and the page as an image (about 1,600 tokens at the size it is read). Errs high, for the limits.
     */
    public static final int TOKENS_PER_PDF_PAGE = 5_000;
    /** The beta of the provider's fallback on a refusal, by refusal category. */
    public static final String FALLBACK_BETA = "server-side-fallback-2026-07-01";

    private static final Pattern WORD = Pattern.compile("[a-z_]{1,40}");
    private static final Pattern MODEL = Pattern.compile("[a-z0-9][a-z0-9.-]{0,59}");

    /**
     * A PDF sent with the request as a document block (Q8b), before the request's text, titled so that the text can
     * name it ({@code Material 3}). Nothing reads the PDF here (Q8b review H1, H2: the sponsor's file is never parsed
     * on the server); the provider counts its tokens ({@link #countTokens}) and reads it.
     *
     * @param base64 the file, encoded once and used as it is by the count and the call
     */
    public record Document(String title, String base64) {

        /** The document of a file's bytes. */
        public static Document of(String title, byte[] pdf) {
            return new Document(title, java.util.Base64.getEncoder().encodeToString(pdf));
        }

        @Override
        public String toString() {
            return "Document[title=" + title + ", base64=" + (base64 == null ? 0 : base64.length()) + " chars]";
        }
    }

    /**
     * What to ask.
     *
     * @param userKey   the provider's opaque user id, or null to send none
     * @param schema    the JSON schema the answer must meet (structured outputs)
     * @param documents PDFs sent with the request, in order; none when empty
     * @param countedInputTokens the provider's count of the request's input ({@link #countTokens}); null when not
     *                  counted (a request without documents, whose text is estimated from its size)
     */
    public record Request(String model, String effort, int maxTokens, String system, String user, String userKey,
        Map<String, Object> schema, List<Document> documents, Long countedInputTokens) {

        public Request {
            documents = documents == null ? List.of() : List.copyOf(documents);
        }

        public Request(String model, String effort, int maxTokens, String system, String user, String userKey,
            Map<String, Object> schema, List<Document> documents) {
            this(model, effort, maxTokens, system, user, userKey, schema, documents, null);
        }

        /** A request for candidate questions without documents (Q8a). */
        public Request(String model, String effort, int maxTokens, String system, String user, String userKey) {
            this(model, effort, maxTokens, system, user, userKey, AiPrompt.schema(), List.of(), null);
        }

        /** This request with the provider's count of its input. */
        public Request counted(long inputTokens) {
            return new Request(model, effort, maxTokens, system, user, userKey, schema, documents, inputTokens);
        }

        /** The documents' sizes only: the request's text and files may be personal. */
        @Override
        public String toString() {
            return "Request[model=" + model + ", effort=" + effort + ", maxTokens=" + maxTokens + ", user="
                + user.length() + " chars, documents=" + documents + ", countedInputTokens=" + countedInputTokens + "]";
        }
    }

    /**
     * Tokens of one call, over every model that ran it; the {@code fallback} ones are those of the provider's fallback
     * model (part of the totals, billed at that model's price); the {@code longPrompt} ones those of calls the provider
     * prices at its long-prompt rate (part of the totals; {@link AiCost}).
     */
    public record Usage(long inputTokens, long outputTokens, long fallbackInputTokens, long fallbackOutputTokens,
        long longPromptInputTokens, long longPromptOutputTokens) {

        public Usage(long inputTokens, long outputTokens, long fallbackInputTokens, long fallbackOutputTokens) {
            this(inputTokens, outputTokens, fallbackInputTokens, fallbackOutputTokens, 0, 0);
        }

        public Usage(long inputTokens, long outputTokens) {
            this(inputTokens, outputTokens, 0, 0);
        }

        /**
         * This usage with the requested model's part priced at its long-prompt rate. The fallback model's part keeps
         * the fallback's price: it is never in the long part too, so no token is priced twice (Q8b re-review L-b;
         * {@link AiCost#of} prices the three parts apart).
         */
        public Usage asLongPrompt() {
            return new Usage(inputTokens, outputTokens, fallbackInputTokens, fallbackOutputTokens,
                Math.max(0, inputTokens - fallbackInputTokens), Math.max(0, outputTokens - fallbackOutputTokens));
        }
    }

    /**
     * A successful answer.
     *
     * @param servedModel     the model that produced the message
     * @param fellBack        whether the provider ran a fallback model
     * @param stopReason      {@code end_turn}, {@code refusal}, {@code max_tokens}, …
     * @param refusalCategory the refusal's category when it names one in plain words; null otherwise
     * @param recommendedModel on a refusal, the model the provider suggests trying directly: present only when its
     *                         fallback could not run (rate-limited or overloaded), so the refusal is transient
     * @param text            the text of the answer (the JSON document), empty when there is none
     */
    public record Answer(String servedModel, boolean fellBack, Usage usage, String stopReason,
        String refusalCategory, String recommendedModel, String text) {}

    /** The output tokens asked for {@code count} questions. */
    public static int maxTokens(int count) {
        return (int) Math.min(MAX_TOKENS, BASE_TOKENS + (long) TOKENS_PER_QUESTION * Math.max(1, count));
    }

    /**
     * What an attempt that got no answer may have cost: its input (the provider's count when the request was counted,
     * otherwise what the job reserved for an attempt's input, or, for a job that reserved nothing, one token per three
     * bytes and the framing), and all the output it asked for. Charged by the caller, since the provider bills an
     * answer that was cut off on the way. An attempt whose instance died before it could record anything is charged
     * on the same basis, from the job's stored parameters (the reservation and {@code maxTokens}; {@link #unrecorded}),
     * so that both ways of losing an answer cost the same.
     */
    public static Usage unanswered(Request request, Long reservedInputTokens) {
        long input;
        if (request.countedInputTokens() != null) {
            input = request.countedInputTokens();
        } else if (reservedInputTokens != null) {
            input = reservedInputTokens;
        } else {
            long bytes = request.user().getBytes(StandardCharsets.UTF_8).length
                + request.system().getBytes(StandardCharsets.UTF_8).length;
            input = AiLimits.inputEstimate(bytes);
        }
        return new Usage(input, request.maxTokens());
    }

    /** {@link #unanswered(Request, Long)} of a job that reserved nothing (one token per three bytes and the framing). */
    public static Usage unanswered(Request request) {
        return unanswered(request, null);
    }

    /**
     * What one attempt that ended without recording its usage is charged (platform decision D52 item 5, W-6): as
     * {@link #unanswered}, the input reserved for an attempt and all the output it asked for. Its count by the provider
     * (a request with documents) is not known, so the reservation, which no count may exceed, stands for it.
     */
    public static Usage unrecorded(long reservedInputTokens, int maxTokens) {
        return new Usage(reservedInputTokens, maxTokens);
    }

    /**
     * Whether a call that got no usable answer may still have been billed: a time-out, a lost connection or an answer
     * too large to read may have been answered in full. A call the platform refused to send, or a connection that was
     * never made, was not.
     */
    public static boolean mayHaveBeenBilled(com.jabiz.external.ExternalCallException e) {
        return switch (e.kind()) {
            case TIMEOUT, TOO_LARGE -> true;
            case UNAVAILABLE -> e.getMessage() == null || !e.getMessage().contains("(ConnectException)");
            case REFUSED, BAD_RESPONSE -> false;
        };
    }

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

    /**
     * The provider's count of the request's input tokens ({@code POST /v1/messages/count_tokens}: free, its own rate
     * limit), with exactly what {@link #send} sends but the output limit, the user id and the fallback, which the
     * count does not take. A document the provider cannot read is refused here, before anything is billed
     * ({@link ClaudeErrors}).
     */
    public long countTokens(Request request, java.time.Duration timeout) {
        requireSmallEnough(request);
        ExternalHttp.Request call = ExternalHttp.Request.post("/v1/messages/count_tokens")
            .header("x-api-key", apiKey)
            .header("anthropic-version", ClaudeService.API_VERSION)
            .body("application/json", json.writeValueAsBytes(content(request, json)));
        if (timeout != null) {
            call = call.timeout(timeout);
        }
        ExternalHttp.Response response = http.send(call);
        String text = response.text();
        JsonNode root = parse(json, text);
        if (response.status() < 200 || response.status() >= 300) {
            throw failure(response.status(), root, !request.documents().isEmpty());
        }
        JsonNode count = root == null ? null : root.get("input_tokens");
        if (count == null || !count.isIntegralNumber() || count.asLong() < 0) {
            throw ClaudeErrors.badResponse("count has no input_tokens");
        }
        return count.asLong();
    }

    private static void requireSmallEnough(Request request) {
        int size = request.user().getBytes(StandardCharsets.UTF_8).length;
        if (size > MAX_INPUT_BYTES) {
            throw ExternalJobException.permanent(AiCodes.INPUT_TOO_LARGE, "The request is " + size
                + " bytes, more than " + MAX_INPUT_BYTES);
        }
    }

    /** Sends the request; refused before sending when the user message is too large. */
    public Answer send(Request request) {
        return send(request, null);
    }

    /**
     * Sends the request, waiting at most {@code timeout} (null: the service's own, two minutes) for the whole answer.
     */
    public Answer send(Request request, java.time.Duration timeout) {
        requireSmallEnough(request);
        ExternalHttp.Request call = ExternalHttp.Request.post("/v1/messages")
            .header("x-api-key", apiKey)
            .header("anthropic-version", ClaudeService.API_VERSION)
            .body("application/json", json.writeValueAsBytes(message(request, json)));
        if (timeout != null) {
            call = call.timeout(timeout);
        }
        if (fallsBack(request.model())) {
            call = call.header("anthropic-beta", FALLBACK_BETA);
        }
        ExternalHttp.Response response = http.send(call);
        return answer(response.status(), response.text(), json, !request.documents().isEmpty());
    }

    /** Whether the model has the provider's fallback on a refusal (Haiku 5.5 has none). */
    static boolean fallsBack(String model) {
        return !ClaudeService.HAIKU.equals(model);
    }

    /** The request's body, as text (for tests and logs of shapes; {@link #send} writes the bytes directly). */
    public static String body(Request request, JsonMapper json) {
        return json.writeValueAsString(message(request, json));
    }

    /** The body of the token count: the request as sent, without what the count does not take. */
    public static String countBody(Request request, JsonMapper json) {
        return json.writeValueAsString(content(request, json));
    }

    /** The message's body: the content the count takes, and the output limit, the user id and the fallback. */
    private static Map<String, Object> message(Request request, JsonMapper json) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", request.model());
        body.put("max_tokens", request.maxTokens());
        content(request, json).forEach((key, value) -> {
            if (!"model".equals(key)) {
                body.put(key, value);
            }
        });
        if (request.userKey() != null) {
            body.put("metadata", Map.of("user_id", request.userKey()));
        }
        // Not with documents: the counted request is exactly what is billed (Q8b re-review M-a).
        if (fallsBack(request.model()) && request.documents().isEmpty()) {
            body.put("fallbacks", "default");
        }
        return body;
    }

    /** What the count and the message share: the model, thinking, output, system prompt and the user's content. */
    private static Map<String, Object> content(Request request, JsonMapper json) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", request.model());
        body.put("thinking", Map.of("type", "adaptive"));
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("effort", request.effort());
        output.put("format", Map.of("type", "json_schema", "schema",
            request.schema() == null ? AiPrompt.schema() : request.schema()));
        body.put("output_config", output);
        body.put("system", request.system());
        // The documents first, then the request's text that names them (as the provider advises).
        List<Map<String, Object>> content = new ArrayList<>();
        for (Document document : request.documents()) {
            Map<String, Object> block = new LinkedHashMap<>();
            block.put("type", "document");
            block.put("source", Map.of("type", "base64", "media_type", "application/pdf", "data",
                document.base64()));
            block.put("title", document.title());
            content.add(block);
        }
        content.add(Map.of("type", "text", "text", request.user()));
        body.put("messages", List.of(Map.of("role", "user", "content", content)));
        return body;
    }

    /** A response read: a 2xx one as an {@link Answer}, any other as the failure it is. */
    public static Answer answer(int status, String text, JsonMapper json) {
        return answer(status, text, json, false);
    }

    /**
     * A response read; {@code documents}: whether the request sent PDFs, so that the provider's refusal of one is
     * told apart from other refusals ({@link ClaudeErrors#failure(int, String, String, boolean)}).
     */
    public static Answer answer(int status, String text, JsonMapper json, boolean documents) {
        JsonNode root = parse(json, text);
        if (status < 200 || status >= 300) {
            throw failure(status, root, documents);
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
        String recommended = null;
        JsonNode details = root.get("stop_details");
        if (details != null && details.get("category") != null) {
            String value = textOf(details.get("category"));
            category = value != null && WORD.matcher(value).matches() ? value : null;
        }
        if (details != null && details.get("recommended_model") != null) {
            String value = textOf(details.get("recommended_model"));
            recommended = value != null && MODEL.matcher(value).matches() ? value : null;
        }
        return new Answer(model, fellBack, usage, stop, category, recommended, answer.toString());
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
            long fallbackIn = 0;
            long fallbackOut = 0;
            for (JsonNode iteration : iterations) {
                in += input(iteration);
                out += count(iteration.get("output_tokens"));
                if ("fallback_message".equals(textOf(iteration.get("type")))) {
                    fallbackIn += input(iteration);
                    fallbackOut += count(iteration.get("output_tokens"));
                }
            }
            return new Usage(in, out, fallbackIn, fallbackOut);
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

    private static ExternalJobException failure(int status, JsonNode root, boolean documents) {
        JsonNode error = root == null ? null : root.get("error");
        String type = error != null && error.get("type") != null ? error.get("type").asString() : null;
        String message = error != null && error.get("message") != null ? error.get("message").asString() : null;
        return ClaudeErrors.failure(status, type, message, documents);
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
