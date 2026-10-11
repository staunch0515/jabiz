import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A stand-in for the demo AI service {@code demo-ai} (decision D38) for manual demonstrations with the dev profile,
 * which points the service at http://localhost:8091/v1. No provider is called. Run it with the JDK alone:
 *
 * <pre>java tools/demo/ai-stub/AiStub.java [port]</pre>
 *
 * {@code POST /v1/moderate} flags texts containing "badword". It listens on this machine only.
 */
public final class AiStub {

    private static final Pattern TEXT = Pattern.compile("\"text\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

    public static void main(String[] args) throws IOException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8091;
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/v1/moderate", AiStub::moderate);
        server.createContext("/", exchange -> answer(exchange, 404, "{\"error\":\"not found\"}"));
        server.start();
        System.out.println("demo-ai stub on http://localhost:" + port + "/v1 (Ctrl+C to stop)");
    }

    private static void moderate(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equals("POST")) {
            answer(exchange, 405, "{\"error\":\"POST only\"}");
            return;
        }
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Matcher text = TEXT.matcher(body);
        boolean flagged = text.find() && text.group(1).toLowerCase(Locale.ROOT).contains("badword");
        System.out.println("moderate: " + (flagged ? "flagged" : "accepted"));
        answer(exchange, 200, flagged ? "{\"flagged\":true,\"categories\":[\"harassment\"]}"
            : "{\"flagged\":false,\"categories\":[]}");
    }

    private static void answer(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
