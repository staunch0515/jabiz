package com.jabiz.runtime.external;

import com.jabiz.external.ExternalCallException;
import com.jabiz.external.ExternalHttp;
import com.jabiz.external.ExternalService;
import com.jabiz.runtime.observability.PlatformObservations;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The client of declared external services (decision D38) against a local HTTP server: the host list on every request,
 * no redirects, the size limit and the timeout, the thread and dry-run rules, and secrets kept out of every text.
 */
class JdkExternalHttpTest {

    private static final String SECRET = "sk-test-0123456789-secret";

    /** One request the stub received. */
    record Received(String method, String path, Map<String, String> headers, String body) {}

    private HttpServer server;
    private final List<Received> received = new CopyOnWriteArrayList<>();
    private final List<Map<String, String>> observed = new CopyOnWriteArrayList<>();
    private ExternalServicesImpl services;
    private int port;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", exchange -> {
            Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            exchange.getRequestHeaders().forEach((name, values) -> headers.put(name, values.getFirst()));
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String path = exchange.getRequestURI().getPath();
            received.add(new Received(exchange.getRequestMethod(), path, headers, body));
            switch (path) {
                case "/v1/echo" -> answer(exchange, 200, "{\"echo\":" + body.length() + "}");
                case "/v1/redirect" -> {
                    exchange.getResponseHeaders().add("Location", "/v1/target");
                    answer(exchange, 302, "");
                }
                case "/v1/large" -> answer(exchange, 200, "x".repeat(2048));
                case "/v1/chunked" -> {
                    exchange.sendResponseHeaders(200, 0);
                    try (OutputStream out = exchange.getResponseBody()) {
                        for (int i = 0; i < 64; i++) {
                            out.write("y".repeat(64).getBytes(StandardCharsets.UTF_8));
                            out.flush();
                        }
                    }
                }
                case "/v1/slow" -> {
                    try {
                        Thread.sleep(3000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    answer(exchange, 200, "late");
                }
                case "/v1/fail" -> answer(exchange, 503, "down");
                default -> answer(exchange, 404, "");
            }
        });
        server.start();
        port = server.getAddress().getPort();
        services = services(ExternalService.define("stub", s -> s.baseUrl("http://127.0.0.1:" + port + "/v1/")
            .allowedHosts("127.0.0.1").secret("apiKey", "STUB_KEY").requestTimeout(Duration.ofSeconds(1))
            .idempotencyHeader("Idempotency-Key").maxResponseSize(1024)),
            ExternalService.define("dry", s -> s.baseUrl("http://127.0.0.1:" + port + "/v1/")
                .allowedHosts("127.0.0.1").allowInDryRun()));
    }

    private ExternalServicesImpl services(ExternalService... declared) {
        ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(new ObservationHandler<>() {
            @Override
            public void onStop(Observation.Context context) {
                Map<String, String> tags = new TreeMap<>();
                for (KeyValue kv : context.getLowCardinalityKeyValues()) {
                    tags.put(kv.getKey(), kv.getValue());
                }
                tags.put("@name", context.getName());
                observed.add(tags);
            }

            @Override
            public boolean supportsContext(Observation.Context context) {
                return true;
            }
        });
        return new ExternalServicesImpl(new ExternalServiceRegistry(List.of(declared),
            new MockEnvironment().withProperty("STUB_KEY", SECRET)), new PlatformObservations(registry));
    }

    @AfterEach
    void stop() {
        services.close();
        server.stop(0);
    }

    private static void answer(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static <T> T inStep(boolean dryRun, Callable<T> work) throws Exception {
        return new ExternalCallScope(42L, "Call the stub", dryRun).call(work);
    }

    private ExternalHttp.Response send(String service, ExternalHttp.Request request) throws Exception {
        return inStep(false, () -> services.http(service).send(request));
    }

    @Test
    void sendsTheRequestAndReturnsTheAnswer() throws Exception {
        ExternalHttp.Response response = inStep(false, () -> services.http("stub").send(
            ExternalHttp.Request.post("echo").header("Authorization", "Bearer " + services.secret("stub", "apiKey"))
                .json("{\"text\":\"hi\"}").idempotencyKey(services.idempotencyKey("moderate"))));
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.text()).isEqualTo("{\"echo\":13}");
        Received request = received.getFirst();
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.path()).isEqualTo("/v1/echo");
        assertThat(request.headers()).containsEntry("Authorization", "Bearer " + SECRET)
            .containsEntry("Idempotency-Key", "jabiz-42-moderate").containsEntry("Content-Type", "application/json");
        assertThat(observed).containsExactly(Map.of("@name", "jabiz.external.call", "service", "stub",
            "outcome", "2xx"));
    }

    @Test
    void absoluteAddressesOnAnAllowedHostAreAccepted() throws Exception {
        assertThat(send("stub", ExternalHttp.Request.get("http://127.0.0.1:" + port + "/v1/fail")).status())
            .isEqualTo(503);
        assertThat(observed).extracting(tags -> tags.get("outcome")).containsExactly("5xx");
    }

    @Test
    void otherHostsAndAddressesWithCredentialsAreRefusedBeforeAnythingIsSent() {
        for (String target : List.of("http://localhost:" + port + "/v1/echo", "//evil.example.com/v1/echo",
            "https://evil.example.com/steal?key=" + SECRET, "http://user:" + SECRET + "@127.0.0.1:" + port + "/v1/echo",
            "ftp://127.0.0.1/x")) {
            Throwable refused = catchThrowable(() -> send("stub", ExternalHttp.Request.get(target)));
            assertThat(refused).as(target).isInstanceOf(ExternalCallException.class)
                .hasMessageNotContaining(SECRET).hasMessageStartingWith("External service stub: ");
            assertThat(((ExternalCallException) refused).kind()).isEqualTo(ExternalCallException.Kind.REFUSED);
        }
        assertThat(received).isEmpty();
        assertThat(observed).extracting(tags -> tags.get("outcome")).containsOnly("refused");
    }

    @Test
    void redirectsAreNotFollowed() throws Exception {
        ExternalHttp.Response response = send("stub", ExternalHttp.Request.get("redirect"));
        assertThat(response.status()).isEqualTo(302);
        assertThat(response.header("location")).contains("/v1/target");
        assertThat(received).extracting(Received::path).containsExactly("/v1/redirect");
        assertThat(observed).extracting(tags -> tags.get("outcome")).containsExactly("3xx");
    }

    @Test
    void answersLargerThanTheLimitFail() {
        for (String path : List.of("large", "chunked")) {
            assertThatThrownBy(() -> send("stub", ExternalHttp.Request.get(path))).as(path)
                .isInstanceOfSatisfying(ExternalCallException.class,
                    e -> assertThat(e.kind()).isEqualTo(ExternalCallException.Kind.TOO_LARGE));
        }
        assertThat(observed).extracting(tags -> tags.get("outcome")).containsExactly("error", "error");
    }

    @Test
    void slowAnswersTimeOut() {
        long started = System.nanoTime();
        assertThatThrownBy(() -> send("stub", ExternalHttp.Request.get("slow?token=" + SECRET)
            .header("Authorization", "Bearer " + SECRET).timeout(Duration.ofMillis(200))))
            .isInstanceOfSatisfying(ExternalCallException.class,
                e -> assertThat(e.kind()).isEqualTo(ExternalCallException.Kind.TIMEOUT))
            .hasMessageNotContaining(SECRET).hasNoCause();
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
        assertThat(observed).extracting(tags -> tags.get("outcome")).containsExactly("timeout");
        // A request may not wait longer than its service allows.
        assertThatThrownBy(() -> send("stub", ExternalHttp.Request.get("echo").timeout(Duration.ofSeconds(5))))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aClosedPortIsUnavailable() throws Exception {
        ExternalServicesImpl closed;
        try (var socket = new java.net.ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            int unused = socket.getLocalPort();
            socket.close();
            closed = services(ExternalService.define("gone", s -> s.baseUrl("http://127.0.0.1:" + unused)
                .allowedHosts("127.0.0.1")));
        }
        try {
            assertThatThrownBy(() -> inStep(false, () -> closed.http("gone").send(ExternalHttp.Request.get("/x"))))
                .isInstanceOfSatisfying(ExternalCallException.class,
                    e -> assertThat(e.kind()).isEqualTo(ExternalCallException.Kind.UNAVAILABLE));
        } finally {
            closed.close();
        }
    }

    @Test
    void callsOnNonBlockingThreadsOrOutsideABlockingStepAreRefused() {
        ExternalHttp http = services.http("stub");
        assertThatThrownBy(() -> Mono.fromCallable(() -> http.send(ExternalHttp.Request.get("echo")))
            .subscribeOn(Schedulers.parallel()).block())
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("non-blocking thread");
        assertThatThrownBy(() -> http.send(ExternalHttp.Request.get("echo")))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("blocking step");
        assertThatThrownBy(() -> services.secret("stub", "apiKey")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> services.idempotencyKey("x")).isInstanceOf(IllegalStateException.class);
        assertThat(received).isEmpty();
    }

    @Test
    void dryRunsMakeNoCallsUnlessTheServiceAllowsThem() throws Exception {
        assertThatThrownBy(() -> inStep(true, () -> services.http("stub").send(ExternalHttp.Request.get("echo"))))
            .isInstanceOfSatisfying(ExternalCallException.class,
                e -> assertThat(e.kind()).isEqualTo(ExternalCallException.Kind.REFUSED))
            .hasMessageContaining("dry run");
        assertThat(received).isEmpty();
        assertThat(inStep(true, () -> services.http("dry").send(ExternalHttp.Request.get("echo"))).status())
            .isEqualTo(200);
        assertThat(received).hasSize(1);
    }

    @Test
    void undeclaredServicesSecretsAndHeadersAreRefused() {
        assertThatThrownBy(() -> services.http("nope")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> inStep(false, () -> services.secret("stub", "nope")))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> inStep(false, () -> services.secret("dry", "apiKey")))
            .isInstanceOf(IllegalArgumentException.class);
        // "dry" declares no idempotency header.
        assertThatThrownBy(() -> send("dry", ExternalHttp.Request.get("echo").idempotencyKey("k")))
            .isInstanceOf(ExternalCallException.class).hasMessageContaining("idempotency");
        assertThatThrownBy(() -> inStep(false, () -> services.idempotencyKey("bad suffix")))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aMissingSecretFailsWhenUsedWithoutNamingItsValue() {
        ExternalServicesImpl unset = new ExternalServicesImpl(new ExternalServiceRegistry(List.of(
            ExternalService.define("unset", s -> s.baseUrl("https://x.example.com").allowedHosts("x.example.com")
                .secret("apiKey", "UNSET_KEY"))), new MockEnvironment()), PlatformObservations.NOOP);
        try {
            assertThatThrownBy(() -> inStep(false, () -> unset.secret("unset", "apiKey")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("UNSET_KEY");
        } finally {
            unset.close();
        }
    }

    @Test
    void secretsAppearInNoText() throws Exception {
        assertThat(services.toString()).doesNotContain(SECRET);
        assertThat(services.http("stub").toString()).doesNotContain(SECRET).contains("stub");
        ExternalHttp.Request request = ExternalHttp.Request.get("fail?key=" + SECRET)
            .header("Authorization", "Bearer " + SECRET);
        assertThat(request.toString()).doesNotContain(SECRET);
        ExternalHttp.Response response = send("stub", request);
        assertThat(response.toString()).doesNotContain(SECRET);
    }
}
