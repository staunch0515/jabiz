package com.jabiz.quizbuks.content.ai;

import com.jabiz.external.ExternalCallException;
import com.jabiz.external.ExternalHttp;
import com.jabiz.external.ExternalJobContext;
import com.jabiz.external.ExternalJobException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Claude reading the web for a job (docs/quizbuks/plans/Q48-operators.md, Q48c: AI prospecting), through the same
 * external service and hand-written client as {@link ClaudeApi} (platform decision D38): one request with Anthropic's
 * server tools, web search and web fetch, and the conversation continued while the provider pauses its turn.
 * <ul>
 *   <li>the tools are {@value #SEARCH_TOOL} and {@value #FETCH_TOOL} (dynamic filtering; the results Claude's code
 *       consumed are left out of the answer, {@code response_inclusion: excluded}), each with its most uses a request,
 *       a fetch at most {@value #MAX_CONTENT_TOKENS} tokens of a page. The provider fetches only addresses that were in
 *       the conversation (the request's text or a search's results), and refuses pages a site's {@code robots.txt}
 *       keeps out ({@code url_not_allowed});</li>
 *   <li>no structured output: web search always cites, which the provider's output format does not take; the system
 *       prompt asks for one JSON object at the end, which the caller checks field by field;</li>
 *   <li>a turn the provider paused ({@code pause_turn}) is sent again with the assistant's content as it came, at most
 *       {@value #MAX_CONTINUATIONS} times; each call's usage is recorded as soon as it is known (tokens, and searches
 *       at {@link #SEARCH_PRICE_USD} each), an unanswered call charged as {@link ClaudeApi#unanswered};</li>
 *   <li>a refusal (after the provider's own fallback), an answer cut at the token limit or one that is not text ends
 *       the attempt as {@link ClaudeCalls} does.</li>
 * </ul>
 */
public final class ClaudeWebResearch {

    public static final String SEARCH_TOOL = "web_search_20260318";
    public static final String FETCH_TOOL = "web_fetch_20260318";
    /** Usage metric: the searches the provider ran (billed apart from tokens). */
    public static final String SEARCHES = "webSearches";
    /** What one search costs (the provider's price: ten dollars per thousand searches). */
    public static final BigDecimal SEARCH_PRICE_USD = new BigDecimal("0.01");
    /** The most of a fetched page that reaches Claude. */
    public static final int MAX_CONTENT_TOKENS = 20_000;
    /** The most times a paused turn is continued. */
    public static final int MAX_CONTINUATIONS = 3;
    /** The output asked for (thinking included): the research's JSON is a few thousand tokens. */
    public static final int MAX_TOKENS = 16_000;
    /** The input one call may come to, for the charge of an unanswered one: the fetched pages are most of it. */
    public static final long RESERVED_INPUT_TOKENS = 120_000;

    /**
     * What to ask.
     *
     * @param searches the most searches (0: no web search)
     * @param fetches  the most fetches (0: no web fetch)
     * @param country  where the search should look from (ISO 3166-1), or null
     */
    public record Request(String model, String effort, String system, String user, String userKey, int searches,
        int fetches, String country) {

        @Override
        public String toString() {
            return "Request[model=" + model + ", effort=" + effort + ", user=" + user.length() + " chars, searches="
                + searches + ", fetches=" + fetches + "]";
        }
    }

    /** The final answer: who served it, its text, and what all its calls used. */
    public record Answer(String servedModel, boolean fellBack, String text, ClaudeApi.Usage usage, long searches) {}

    private final ExternalHttp http;
    private final String apiKey;
    private final JsonMapper json;

    ClaudeWebResearch(ExternalHttp http, String apiKey, JsonMapper json) {
        this.http = http;
        this.apiKey = apiKey;
        this.json = json;
    }

    public static ClaudeWebResearch of(ExternalJobContext ctx, JsonMapper json) {
        return new ClaudeWebResearch(ctx.http(ClaudeService.SERVICE), ctx.secret(ClaudeService.SERVICE, "apiKey"),
            json);
    }

    /** What a call that got no answer may have cost: the input it could have read and all the output it asked for. */
    public static ClaudeApi.Usage unanswered(Request request) {
        long text = (request.system().getBytes(StandardCharsets.UTF_8).length
            + request.user().getBytes(StandardCharsets.UTF_8).length) / 3 + 1_000;
        return new ClaudeApi.Usage(Math.max(text, RESERVED_INPUT_TOKENS), MAX_TOKENS);
    }

    /** What the search tools cost, in dollars. */
    public static BigDecimal searchCost(long searches) {
        return SEARCH_PRICE_USD.multiply(BigDecimal.valueOf(Math.max(0, searches)));
    }

    /**
     * Asks, continuing a paused turn, and records every call's usage on the attempt; returns the final answer, or
     * throws the attempt's failure.
     */
    public Answer ask(ExternalJobContext ctx, Request request, Duration timeout) {
        List<Object> messages = new ArrayList<>();
        messages.add(Map.of("role", "user", "content", request.user()));
        long in = 0;
        long out = 0;
        long fallbackIn = 0;
        long fallbackOut = 0;
        long searches = 0;
        for (int call = 0; call <= MAX_CONTINUATIONS; call++) {
            JsonNode root;
            try {
                root = send(request, messages, timeout);
            } catch (ExternalCallException e) {
                if (ClaudeApi.mayHaveBeenBilled(e)) {
                    record(ctx, request.model(), unanswered(request), 0);
                }
                throw e;
            }
            ClaudeApi.Answer read = ClaudeApi.answer(200, json.writeValueAsString(root), json);
            long callSearches = searches(root.get("usage"));
            record(ctx, request.model(), read.usage(), callSearches);
            in += read.usage().inputTokens();
            out += read.usage().outputTokens();
            fallbackIn += read.usage().fallbackInputTokens();
            fallbackOut += read.usage().fallbackOutputTokens();
            searches += callSearches;
            String stop = read.stopReason();
            if ("pause_turn".equals(stop)) {
                // The provider resumes from the assistant's content as it came (the trailing server tool use).
                messages.add(Map.of("role", "assistant", "content", root.get("content")));
                continue;
            }
            if ("refusal".equals(stop)) {
                String message = "Claude declined the request"
                    + (read.refusalCategory() == null ? "" : " (" + read.refusalCategory() + ")");
                throw read.recommendedModel() != null
                    ? ExternalJobException.retryable(AiCodes.REFUSED, message + "; its fallback was busy")
                    : ExternalJobException.permanent(AiCodes.REFUSED, message);
            }
            if ("max_tokens".equals(stop)) {
                throw ExternalJobException.permanent(AiCodes.TRUNCATED, "Claude's answer reached the token limit");
            }
            if (!"end_turn".equals(stop)) {
                throw ClaudeErrors.badResponse("ended with an unexpected reason");
            }
            return new Answer(read.servedModel(), read.fellBack(), read.text(),
                new ClaudeApi.Usage(in, out, fallbackIn, fallbackOut), searches);
        }
        throw ExternalJobException.retryable(AiCodes.TRUNCATED, "Claude paused its turn more than "
            + MAX_CONTINUATIONS + " times");
    }

    private JsonNode send(Request request, List<Object> messages, Duration timeout) {
        ExternalHttp.Request call = ExternalHttp.Request.post("/v1/messages")
            .header("x-api-key", apiKey)
            .header("anthropic-version", ClaudeService.API_VERSION)
            .body("application/json", json.writeValueAsBytes(body(request, messages)));
        if (timeout != null) {
            call = call.timeout(timeout);
        }
        if (ClaudeApi.fallsBack(request.model())) {
            call = call.header("anthropic-beta", ClaudeApi.FALLBACK_BETA);
        }
        ExternalHttp.Response response = http.send(call);
        String text = response.text();
        JsonNode root = parse(text);
        if (response.status() < 200 || response.status() >= 300) {
            ClaudeApi.answer(response.status(), text, json);
            throw ClaudeErrors.badResponse("failed without an error");
        }
        if (root == null || !root.isObject()) {
            throw ClaudeErrors.badResponse("is not a JSON object");
        }
        return root;
    }

    /** The body of one call. */
    public static Map<String, Object> body(Request request, List<Object> messages) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", request.model());
        body.put("max_tokens", MAX_TOKENS);
        body.put("thinking", Map.of("type", "adaptive"));
        body.put("output_config", Map.of("effort", request.effort()));
        body.put("system", request.system());
        List<Object> tools = new ArrayList<>();
        if (request.searches() > 0) {
            Map<String, Object> search = new LinkedHashMap<>();
            search.put("type", SEARCH_TOOL);
            search.put("name", "web_search");
            search.put("max_uses", request.searches());
            search.put("response_inclusion", "excluded");
            if (request.country() != null) {
                search.put("user_location", Map.of("type", "approximate", "country", request.country()));
            }
            tools.add(search);
        }
        if (request.fetches() > 0) {
            Map<String, Object> fetch = new LinkedHashMap<>();
            fetch.put("type", FETCH_TOOL);
            fetch.put("name", "web_fetch");
            fetch.put("max_uses", request.fetches());
            fetch.put("max_content_tokens", MAX_CONTENT_TOKENS);
            fetch.put("response_inclusion", "excluded");
            tools.add(fetch);
        }
        if (!tools.isEmpty()) {
            body.put("tools", tools);
        }
        body.put("messages", messages);
        if (request.userKey() != null) {
            body.put("metadata", Map.of("user_id", request.userKey()));
        }
        if (ClaudeApi.fallsBack(request.model())) {
            body.put("fallbacks", "default");
        }
        return body;
    }

    private static long searches(JsonNode usage) {
        JsonNode tools = usage == null ? null : usage.get("server_tool_use");
        JsonNode requests = tools == null ? null : tools.get("web_search_requests");
        return requests != null && requests.isIntegralNumber() && requests.asLong() > 0 ? requests.asLong() : 0;
    }

    /** Records one call's tokens (priced as {@link ClaudeCalls#record} prices them) and its searches. */
    private static void record(ExternalJobContext ctx, String model, ClaudeApi.Usage usage, long searches) {
        ClaudeCalls.record(ctx, model, usage, null);
        if (searches > 0) {
            ctx.recordUsage(SEARCHES, searches);
        }
    }

    private JsonNode parse(String text) {
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
        return "ClaudeWebResearch[apiKey=***]";
    }
}
