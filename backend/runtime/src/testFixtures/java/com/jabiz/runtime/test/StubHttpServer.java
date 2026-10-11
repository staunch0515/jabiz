package com.jabiz.runtime.test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * A local stand-in for another system's HTTP service in tests of external services (decision D38), on a JDK
 * {@link HttpServer} bound to this machine: every request is recorded with its headers and body, and each path
 * answers from a script ({@link #script}, one answer per call, in order) or else from the fallback the stub was
 * started with. Answers may be delayed (timeouts) and sent in chunks without a length (size limits).
 */
public final class StubHttpServer implements AutoCloseable {

    /** One request received; header names are case-insensitive. */
    public record Received(String method, String path, String query, Map<String, String> headers, String body) {

        public String header(String name) {
            return headers.get(name);
        }
    }

    /**
     * One answer.
     *
     * @param delay   how long to wait before answering; null for none
     * @param streamed whether to send the body without a length, in pieces
     */
    public record Answer(int status, Map<String, String> headers, String body, Duration delay, boolean streamed) {

        public Answer {
            headers = headers == null ? Map.of() : Map.copyOf(headers);
            body = body == null ? "" : body;
        }

        public static Answer of(int status, String body) {
            return new Answer(status, Map.of(), body, null, false);
        }

        /** A JSON body with its content type. */
        public static Answer json(int status, String body) {
            return new Answer(status, Map.of("Content-Type", "application/json"), body, null, false);
        }

        public Answer header(String name, String value) {
            Map<String, String> more = new LinkedHashMap<>(headers);
            more.put(name, value);
            return new Answer(status, more, body, delay, streamed);
        }

        public Answer after(Duration wait) {
            return new Answer(status, headers, body, wait, streamed);
        }

        public Answer chunked() {
            return new Answer(status, headers, body, delay, true);
        }
    }

    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final List<Received> received = new CopyOnWriteArrayList<>();
    private final Map<String, Queue<Answer>> scripts = new ConcurrentHashMap<>();
    private final Function<Received, Answer> fallback;
    private final AtomicInteger concurrent = new AtomicInteger();
    private final AtomicInteger maxConcurrent = new AtomicInteger();

    private StubHttpServer(Function<Received, Answer> fallback) {
        this.fallback = fallback;
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.setExecutor(executor);
        server.createContext("/", this::handle);
    }

    /** Starts a stub that answers 404 to anything not scripted. */
    public static StubHttpServer start() {
        return start(request -> Answer.of(404, ""));
    }

    /** Starts a stub that answers what {@code fallback} makes of a request not scripted. */
    public static StubHttpServer start(Function<Received, Answer> fallback) {
        StubHttpServer stub = new StubHttpServer(fallback);
        stub.server.start();
        return stub;
    }

    public int port() {
        return server.getAddress().getPort();
    }

    /** {@code http://127.0.0.1:<port><path>}. */
    public String url(String path) {
        return "http://127.0.0.1:" + port() + path;
    }

    public List<Received> requests() {
        return List.copyOf(received);
    }

    public List<Received> requests(String path) {
        return received.stream().filter(r -> r.path().equals(path)).toList();
    }

    /** The next request to {@code path} gets {@code answer}. */
    public void script(String path, Answer answer) {
        scripts.computeIfAbsent(path, p -> new ConcurrentLinkedQueue<>()).add(answer);
    }

    /** Forgets the requests, the unused scripts and the highest concurrency seen. */
    public void reset() {
        received.clear();
        scripts.clear();
        maxConcurrent.set(0);
    }

    /** The most requests the stub was answering at the same time since it started or was reset. */
    public int maxConcurrent() {
        return maxConcurrent.get();
    }

    private void handle(HttpExchange exchange) throws IOException {
        maxConcurrent.accumulateAndGet(concurrent.incrementAndGet(), Math::max);
        AtomicBoolean left = new AtomicBoolean();
        Runnable leave = () -> {
            if (left.compareAndSet(false, true)) {
                concurrent.decrementAndGet();
            }
        };
        try {
            answer(exchange, leave);
        } finally {
            leave.run();
        }
    }

    private void answer(HttpExchange exchange, Runnable leave) throws IOException {
        Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        exchange.getRequestHeaders().forEach((name, values) -> headers.put(name, values.getFirst()));
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Received request = new Received(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
            exchange.getRequestURI().getRawQuery(), headers, body);
        received.add(request);
        Queue<Answer> scripted = scripts.get(request.path());
        Answer answer = scripted == null ? null : scripted.poll();
        if (answer == null) {
            answer = fallback.apply(request);
        }
        if (answer.delay() != null && answer.delay().isPositive()) {
            try {
                Thread.sleep(answer.delay());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        // Done answering before the bytes go out: the client may send its next request as soon as it reads them.
        leave.run();
        answer.headers().forEach((name, value) -> exchange.getResponseHeaders().add(name, value));
        byte[] bytes = answer.body().getBytes(StandardCharsets.UTF_8);
        try {
            if (answer.streamed()) {
                exchange.sendResponseHeaders(answer.status(), 0);
                try (OutputStream out = exchange.getResponseBody()) {
                    for (int at = 0; at < bytes.length; at += 64) {
                        out.write(bytes, at, Math.min(64, bytes.length - at));
                        out.flush();
                    }
                }
            } else {
                exchange.sendResponseHeaders(answer.status(), bytes.length == 0 ? -1 : bytes.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            }
        } catch (IOException e) {
            // The client gave up (a timeout or a size limit under test).
            exchange.close();
        }
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }
}
