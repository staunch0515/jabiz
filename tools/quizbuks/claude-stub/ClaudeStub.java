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
 *   <li>a translation request ("Target language: ...", Q73d-1) gets "译文 n" for each numbered text, and a
 *       comparison ("Number of pairs: n", Q73d-2) finds every pair to mean the same;</li>
 *   <li>PDF materials (document blocks) are accepted and not read;</li>
 *   <li>{@code POST /v1/messages/count_tokens} (Q8b: requests with PDFs are counted before they are sent) answers
 *       {@code input_tokens}: a token per four characters of the request without its files (and without the output
 *       limit, user id and fallback, which the count does not take), and 1,500 a document; the paid call of the same
 *       request reports the same number as its input usage;</li>
 *   <li>an AI help question (F-C10, {@code <help_question>}) is answered from the help's materials sent with it
 *       ({@code <help_materials>}): when a FAQ heading ({@code ### ...}) is the question itself, its answer with the
 *       site's paths in it as the links (else the FAQ's page); otherwise "not sure", without links;</li>
 *   <li>a quiz whose title contains {@code [refuse]} is declined ({@code stop_reason: refusal}), one with
 *       {@code [busy]} is answered 529 (overloaded), so that the console's failure texts can be seen.</li>
 * </ul>
 * It listens on this machine only and keeps nothing. Never point anything but development or tests at it.
 */
public final class ClaudeStub {

    private static final Pattern COUNT = Pattern.compile("Number of questions: (\\d+)");
    /** A numbered text of a translation request's data, escaped once inside the request's JSON. */
    private static final Pattern TEXT_NO = Pattern.compile("\\\\\"no\\\\\":(\\d+)");
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
            if (body.contains("You read ONE company's website")) {
                send(exchange, 200, message(model, "end_turn", siteDraft(unescaped(body)), in, 3000));
                return;
            }
            if (body.contains("<help_question>")) {
                send(exchange, 200, message(model, "end_turn", helpAnswer(unescaped(body)), in, 120));
                return;
            }
            if (body.contains("Number of pairs: ")) {
                // Q73d-2: every pair compared means the same.
                Matcher numbers = TEXT_NO.matcher(body);
                List<String> verdicts = new ArrayList<>();
                while (numbers.find()) {
                    verdicts.add("{\"no\":" + numbers.group(1) + ",\"consistent\":true,\"note\":\"\"}");
                }
                String text = "{\"verdicts\":[" + String.join(",", verdicts) + "]}";
                send(exchange, 200, message(model, "end_turn", text, in, 50L * verdicts.size()));
                return;
            }
            if (body.contains("Target language: ")) {
                // Q73d-1: a translation of each numbered text (the numbers as the request's data gives them).
                Matcher numbers = TEXT_NO.matcher(body);
                List<String> translations = new ArrayList<>();
                while (numbers.find()) {
                    translations.add("{\"no\":" + numbers.group(1) + ",\"text\":\"译文 " + numbers.group(1) + "\"}");
                }
                String text = "{\"translations\":[" + String.join(",", translations) + "]}";
                send(exchange, 200, message(model, "end_turn", text, in, 100L * translations.size()));
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

    /** A site path in a help answer: the Takers' or the Sponsors' pages. */
    private static final Pattern SITE_PATH = Pattern.compile("/(?:taker|sponsor)(?:/[A-Za-z0-9._~?=&%-]+)*/?");

    /**
     * The help's answer (F-C10) as the job's JSON: the answer of the FAQ entry whose heading is the question, with the
     * site's paths it names (else the FAQ's page, which the heading's section names); "not sure" otherwise.
     */
    static String helpAnswer(String request) {
        String question = between(request, "<help_question>\n", "\n</help_question>", true);
        String materials = between(request, "<help_materials>", "</help_materials>", false);
        String answer = null;
        List<String> links = new ArrayList<>();
        if (question != null && materials != null) {
            String wanted = question.strip();
            String[] lines = materials.split("\n", -1);
            String section = null;
            for (int i = 0; i < lines.length && answer == null; i++) {
                if (lines[i].startsWith("## ")) {
                    Matcher path = SITE_PATH.matcher(lines[i]);
                    section = path.find() ? path.group() : null;
                } else if (lines[i].startsWith("### ") && lines[i].substring(4).strip().equalsIgnoreCase(wanted)) {
                    StringBuilder text = new StringBuilder();
                    for (int j = i + 1; j < lines.length && !lines[j].startsWith("#"); j++) {
                        if (!lines[j].isBlank()) {
                            text.append(text.isEmpty() ? "" : "\n").append(lines[j].strip());
                        }
                    }
                    answer = text.toString();
                    Matcher path = SITE_PATH.matcher(answer);
                    while (path.find()) {
                        links.add(path.group());
                    }
                    if (links.isEmpty() && section != null) {
                        links.add(section);
                    }
                }
            }
        }
        if (answer == null || answer.isBlank()) {
            answer = "I am not sure about this. A person can help: choose to ask a person.";
        }
        StringBuilder json = new StringBuilder("{\"answer\":").append(quoted(answer)).append(",\"links\":[");
        for (int i = 0; i < links.size(); i++) {
            json.append(i == 0 ? "" : ",").append(quoted(links.get(i)));
        }
        return json.append("],\"quiz_request\":false}").toString();
    }

    /** The text between two marks: the last such text, or the first; null when there is none. */
    private static String between(String text, String start, String end, boolean last) {
        int from = last ? text.lastIndexOf(start) : text.indexOf(start);
        if (from < 0) {
            return null;
        }
        int to = text.indexOf(end, from + start.length());
        return to < 0 ? null : text.substring(from + start.length(), to);
    }

    /** The request with its JSON strings' escapes undone (good enough to read the help's tags and texts). */
    static String unescaped(String body) {
        StringBuilder text = new StringBuilder(body.length());
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c != '\\' || i + 1 >= body.length()) {
                text.append(c);
                continue;
            }
            char next = body.charAt(++i);
            switch (next) {
                case 'n' -> text.append('\n');
                case 't' -> text.append('\t');
                case 'r' -> text.append('\r');
                case 'u' -> {
                    if (i + 4 < body.length()) {
                        text.append((char) Integer.parseInt(body.substring(i + 1, i + 5), 16));
                        i += 4;
                    }
                }
                default -> text.append(next);
            }
        }
        return text.toString();
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

    private static final Pattern SITE_HOST = Pattern.compile("\"website\":\"https://([a-z0-9.-]+)/\"");
    private static final Pattern SITE_LANGUAGES = Pattern.compile("\"languages\":\\[([^\\]]*)]");
    private static final Pattern SITE_NUMBER = Pattern.compile("\"(questions|options|materialsMax|maxProducts)\":(\\d+)");

    /**
     * A site draft (F-C7, {@code <request>} of the site job): a company named after the site's host, two products, two
     * articles and as many questions as asked, each text in the languages asked (the others "translated"). A host
     * starting {@code unreachable.}, {@code robots.} or {@code notext.} is answered with that failure; the company's
     * introduction holds a person's e-mail address and telephone number, which the job must take out.
     */
    private static String siteDraft(String body) {
        Matcher host = SITE_HOST.matcher(body);
        String name = host.find() ? host.group(1) : "stub.example";
        for (String failure : new String[] {"unreachable", "robots", "notext"}) {
            if (name.startsWith(failure + ".")) {
                return "{\"failure\":\"" + (failure.equals("notext") ? "NO_TEXT" : failure.toUpperCase()) + "\"}";
            }
        }
        Matcher languageMatch = SITE_LANGUAGES.matcher(body);
        List<String> languages = new ArrayList<>();
        if (languageMatch.find()) {
            for (String language : languageMatch.group(1).split(",")) {
                languages.add(language.replace("\"", "").strip());
            }
        }
        if (languages.isEmpty()) {
            languages.add("en");
        }
        int questions = 5;
        int options = 4;
        int materials = 5;
        Matcher numbers = SITE_NUMBER.matcher(body);
        while (numbers.find()) {
            int value = Integer.parseInt(numbers.group(2));
            switch (numbers.group(1)) {
                case "questions" -> questions = value;
                case "options" -> options = value;
                case "materialsMax" -> materials = Math.min(2, value);
                default -> { }
            }
        }
        String label = name.startsWith("www.") ? name.substring(4) : name;
        String handle = label.contains(".") ? label.substring(0, label.indexOf('.')) : label;
        String site = "https://" + name + "/";
        StringBuilder out = new StringBuilder("{\"failure\":null,\"package\":{");
        out.append("\"basics\":{\"company\":\"Stub Company " + handle + "\"")
            .append(",\"intro\":").append(texts(languages, "We make things. Write to taro@" + label
                + " or call 03-1234-5678, or use our contact form at " + site + "contact."))
            .append(",\"avatar\":{\"url\":\"" + site + "logo.png\"}},");
        out.append("\"page\":{\"handle\":\"" + handle + "\",\"headline\":")
            .append(texts(languages, "Made well, made here")).append(",\"website\":\"" + site + "\",\"cover\":{\"url\":\""
                + site + "hero.jpg\"}},");
        out.append("\"products\":[");
        out.append("{\"code\":\"STUB-1\",\"name\":").append(texts(languages, "Stub Kettle"))
            .append(",\"description\":").append(texts(languages, "A kettle that boils water."))
            .append(",\"price\":3200,\"currency\":\"JPY\",\"shopUrl\":\"" + site + "shop/kettle\"},");
        out.append("{\"name\":").append(texts(languages, "Stub Cup")).append(",\"price\":12.5,\"currency\":\"USD\","
            + "\"shopUrl\":\"" + site + "shop/cup\"}]},");
        out.append("\"sources\":{\"basics.company\":\"" + site + "about\",\"page.headline\":\"" + site
            + "\",\"products[0].price\":\"" + site + "shop/kettle\"},");
        out.append("\"notFound\":[\"page.cover\"],\"translated\":[");
        for (int i = 1; i < languages.size(); i++) {
            out.append(i > 1 ? "," : "").append("\"").append(languages.get(i)).append("\"");
        }
        out.append("],\"materials\":[");
        for (int i = 1; i <= materials; i++) {
            out.append(i > 1 ? "," : "").append("{\"title\":\"About us " + i + "\",\"body\":\"We have made stub things since "
                + (1990 + i) + ".\",\"url\":\"" + site + "about" + i + "\"}");
        }
        out.append("],\"questions\":[");
        for (int q = 1; q <= questions; q++) {
            out.append(q > 1 ? "," : "").append("{\"stem\":\"Since when have we made stub things (" + q
                + ")?\",\"options\":[");
            for (int o = 0; o < options; o++) {
                out.append(o > 0 ? "," : "").append("{\"text\":\"" + (o == 0 ? "Since " + (1991) : "Since " + (2001 + o))
                    + "\",\"correct\":" + (o == 0) + "}");
            }
            out.append("],\"materials\":[1]}");
        }
        return out.append("]}").toString();
    }

    private static String texts(List<String> languages, String base) {
        StringBuilder out = new StringBuilder("{");
        for (int i = 0; i < languages.size(); i++) {
            out.append(i > 0 ? "," : "").append("\"").append(languages.get(i)).append("\":\"")
                .append(i == 0 ? base : "[" + languages.get(i) + "] " + base).append("\"");
        }
        return out.append("}").toString();
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
