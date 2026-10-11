package com.jabiz.app.it.fixture;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A local stand-in for the demo AI service {@code demo-ai} (decision D38) on a JDK {@link HttpServer}: no provider is
 * called by the tests. {@code POST /v1/moderate} flags texts containing {@value #FLAGGED_WORD}; any path can be made to
 * answer with a given status, body and delay instead, once per {@link #script} call. Every request is recorded with
 * its headers (the idempotency key and the credentials sent).
 */
public final class StubAiServer implements AutoCloseable {

    /** The word {@code /v1/moderate} flags. */
    public static final String FLAGGED_WORD = "badword";

    private static final Pattern TEXT = Pattern.compile("\"text\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

    /** One request received. */
    public record Received(String method, String path, Map<String, String> headers, String body) {

        public String header(String name) {
            return headers.get(name);
        }
    }

    /** One scripted answer. */
    public record Answer(int status, String body, Duration delay) {}

    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final List<Received> received = new CopyOnWriteArrayList<>();
    private final Map<String, Queue<Answer>> scripts = new ConcurrentHashMap<>();

    private StubAiServer() {
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.setExecutor(executor);
        server.createContext("/", this::handle);
    }

    public static StubAiServer start() {
        StubAiServer stub = new StubAiServer();
        stub.server.start();
        return stub;
    }

    /** {@code http://127.0.0.1:<port>/v1}: the base address to configure for {@code demo-ai}. */
    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    public List<Received> requests() {
        return List.copyOf(received);
    }

    public List<Received> requests(String path) {
        return received.stream().filter(r -> r.path().equals(path)).toList();
    }

    /** The next request to {@code path} gets this answer instead of the default one. */
    public void script(String path, int status, String body, Duration delay) {
        scripts.computeIfAbsent(path, p -> new ConcurrentLinkedQueue<>()).add(new Answer(status, body, delay));
    }

    public void reset() {
        received.clear();
        scripts.clear();
    }

    private void handle(HttpExchange exchange) throws IOException {
        Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        exchange.getRequestHeaders().forEach((name, values) -> headers.put(name, values.getFirst()));
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String path = exchange.getRequestURI().getPath();
        received.add(new Received(exchange.getRequestMethod(), path, headers, body));
        Queue<Answer> scripted = scripts.get(path);
        Answer answer = scripted == null ? null : scripted.poll();
        if (answer == null) {
            answer = defaultAnswer(path, body);
        }
        if (answer.delay() != null && answer.delay().isPositive()) {
            try {
                Thread.sleep(answer.delay());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        byte[] bytes = answer.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        try {
            exchange.sendResponseHeaders(answer.status(), bytes.length == 0 ? -1 : bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        } catch (IOException e) {
            // The client gave up (a timeout under test).
            exchange.close();
        }
    }

    private static Answer defaultAnswer(String path, String body) {
        if (path.equals("/v1/moderate")) {
            Matcher text = TEXT.matcher(body);
            boolean flagged = text.find() && text.group(1).toLowerCase(Locale.ROOT).contains(FLAGGED_WORD);
            return new Answer(200, flagged ? "{\"flagged\":true,\"categories\":[\"harassment\"]}"
                : "{\"flagged\":false,\"categories\":[]}", null);
        }
        return new Answer(404, "{\"error\":\"not found\"}", null);
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }
}
