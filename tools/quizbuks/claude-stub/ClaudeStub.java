import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A stand-in for Anthropic's Messages API for local development and the end-to-end tests of QuizBuks's AI questions
 * (docs/quizbuks/plans/Q8-ai.md, Q8a and Q8b), matching the dev profile (application-dev.yml): {@code POST /v1/messages} on
 * http://localhost:8094. Nothing is called: the answer is made from the request alone. Run it with the JDK alone:
 *
 * <pre>java tools/quizbuks/claude-stub/ClaudeStub.java [port]</pre>
 *
 * <ul>
 *   <li>a request without an {@code x-api-key} header is answered 401, as the provider would;</li>
 *   <li>otherwise as many questions as the request's "Number of questions: n" asks, each with one correct and two
 *       wrong options, 10 points and the first material sent, in Japanese when the request asks for Japanese and in
 *       English otherwise, with a made-up usage (about a token per four characters of the request);</li>
 *   <li>a request for covers ("Number of covers: 3", Q8b) gets three designs of the catalogue, which the server
 *       draws; PDF materials (document blocks) are accepted and not read;</li>
 *   <li>{@code POST /v1/messages/count_tokens} (Q8b: requests with PDFs are counted before they are sent) answers
 *       {@code input_tokens}: a token per four characters of the request without its files (and without the output
 *       limit, user id and fallback, which the count does not take), and 1,500 a document; the paid call of the same
 *       request reports the same number as its input usage;</li>
 *   <li>a quiz whose title contains {@code [refuse]} is declined ({@code stop_reason: refusal}), one with
 *       {@code [busy]} is answered 529 (overloaded), so that the console's failure texts can be seen.</li>
 * </ul>
 * It listens on this machine only and keeps nothing. Never point anything but development or tests at it.
 */
public final class ClaudeStub {

    private static final Pattern COUNT = Pattern.compile("Number of questions: (\\d+)");
    private static final Pattern MATERIAL = Pattern.compile("\\\\\"no\\\\\":(\\d+)");
    private static final Pattern MODEL = Pattern.compile("^\\{\"model\":\"([a-z0-9-]+)\"");
    private static final Pattern DATA = Pattern.compile("\"data\":\"[A-Za-z0-9+/=]*\"");
    private static final Pattern DOCUMENT = Pattern.compile("\"type\":\"document\"");
    /** The fields a count does not take: the output limit, the user id and the fallback. */
    private static final Pattern NOT_COUNTED = Pattern.compile(
        "\"max_tokens\":\\d+,|,\"metadata\":\\{[^}]*\\}|,\"fallbacks\":\"[a-z]+\"");

    public static void main(String[] args) throws IOException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8094;
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
        server.createContext("/v1/messages", ClaudeStub::messages);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.start();
        System.out.println("Claude stub on http://localhost:" + port + "/v1/messages");
    }

    private static void messages(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!"POST".equals(exchange.getRequestMethod())) {
                send(exchange, 405, "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\"}}");
                return;
            }
            String key = exchange.getRequestHeaders().getFirst("x-api-key");
            if (key == null || key.isBlank()) {
                send(exchange, 401, "{\"type\":\"error\",\"error\":{\"type\":\"authentication_error\"}}");
                return;
            }
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (body.contains("[busy]")) {
                send(exchange, 529, "{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\"}}");
                return;
            }
            long in = tokens(body);
            if (exchange.getRequestURI().getPath().endsWith("/count_tokens")) {
                send(exchange, 200, "{\"input_tokens\":" + in + "}");
                return;
            }
            Matcher modelMatch = MODEL.matcher(body);
            String model = modelMatch.find() ? modelMatch.group(1) : "claude-opus-5-5";
            if (body.contains("[refuse]")) {
                send(exchange, 200, message(model, "refusal", null, in, 0));
                return;
            }
            if (body.contains("Number of covers: ")) {
                // Q8b: three designs of the catalogue; the server draws them.
                String text = japanese(body) ? "資料のクイズ" : "Quiz cover";
                String covers = "{\"covers\":["
                    + "{\"palette\":\"ocean\",\"layout\":\"center\",\"motif\":\"circles\",\"text\":\"" + text + " 1\"},"
                    + "{\"palette\":\"sunset\",\"layout\":\"band\",\"motif\":\"waves\",\"text\":\"" + text + " 2\"},"
                    + "{\"palette\":\"mint\",\"layout\":\"left\",\"motif\":\"grid\",\"text\":\"" + text + " 3\"}]}";
                send(exchange, 200, message(model, "end_turn", covers, in, 150));
                return;
            }
            Matcher countMatch = COUNT.matcher(body);
            int count = countMatch.find() ? Math.min(20, Integer.parseInt(countMatch.group(1))) : 1;
            Matcher materialMatch = MATERIAL.matcher(body);
            String material = materialMatch.find() ? materialMatch.group(1) : null;
            boolean japanese = japanese(body);
            List<String> questions = new ArrayList<>();
            for (int i = 1; i <= count; i++) {
                String stem = japanese ? "資料に基づく問題 " + i + "：正しいものはどれですか？"
                    : "Question " + i + " from the materials: which one is right?";
                String right = japanese ? "正しい答え " + i : "The right answer " + i;
                String wrong = japanese ? "誤った答え " + i : "A wrong answer " + i;
                questions.add("{\"stem\":\"" + stem + "\",\"points\":10,\"materials\":["
                    + (material == null ? "" : material) + "],\"options\":[{\"text\":\"" + right
                    + "\",\"correct\":true},{\"text\":\"" + wrong + " (a)\",\"correct\":false},{\"text\":\"" + wrong
                    + " (b)\",\"correct\":false}]}");
            }
            String text = "{\"questions\":[" + String.join(",", questions) + "]}";
            send(exchange, 200, message(model, "end_turn", text, in, 200L * count));
        }
    }

    /**
     * About a token per four characters of the request without its files, and 1,500 a document: of what the count
     * and the call share (not the output limit, the user id or the fallback), so a call is billed what it was counted.
     */
    private static long tokens(String body) {
        long documents = DOCUMENT.matcher(body).results().count();
        String counted = NOT_COUNTED.matcher(DATA.matcher(body).replaceAll("")).replaceAll("");
        return Math.max(1, counted.length() / 4) + 1_500 * documents;
    }

    private static boolean japanese(String body) {
        return body.contains("Language: Japanese");
    }

    private static String message(String model, String stop, String text, long in, long out) {
        String content = "{\"type\":\"thinking\",\"thinking\":\"\",\"signature\":\"stub\"}"
            + (text == null ? "" : ",{\"type\":\"text\",\"text\":" + quoted(text) + "}");
        return "{\"id\":\"msg_stub\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"" + model
            + "\",\"content\":[" + content + "],\"stop_reason\":\"" + stop + "\",\"usage\":{\"input_tokens\":" + in
            + ",\"output_tokens\":" + out + "}}";
    }

    /** {@code text} as a JSON string. */
    private static String quoted(String text) {
        StringBuilder quoted = new StringBuilder("\"");
        for (char c : text.toCharArray()) {
            switch (c) {
                case '"' -> quoted.append("\\\"");
                case '\\' -> quoted.append("\\\\");
                case '\n' -> quoted.append("\\n");
                default -> {
                    if (c < 0x20) {
                        quoted.append(String.format("\\u%04x", (int) c));
                    } else {
                        quoted.append(c);
                    }
                }
            }
        }
        return quoted.append('"').toString();
    }

    private static void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
