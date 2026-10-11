package com.jabiz.runtime.external;

import com.jabiz.external.ExternalCallException;
import com.jabiz.external.ExternalHttp;
import com.jabiz.external.ExternalService;
import com.jabiz.runtime.observability.PlatformObservations;
import com.jabiz.runtime.test.StubHttpServer;
import com.jabiz.runtime.test.StubHttpServer.Answer;
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

import java.net.InetAddress;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The client of declared external services (decision D38) against a local HTTP server: the host list on every request,
 * no redirects, the size limit and the timeout, the thread and dry-run rules, and secrets kept out of every text.
 */
class JdkExternalHttpTest {

    private static final String SECRET = "sk-test-0123456789-secret";

    private StubHttpServer server;
    private final List<Map<String, String>> observed = new CopyOnWriteArrayList<>();
    private ExternalServicesImpl services;
    private int port;

    @BeforeEach
    void start() {
        server = StubHttpServer.start(request -> switch (request.path()) {
            case "/v1/echo" -> Answer.json(200, "{\"echo\":" + request.body().length() + "}");
            case "/v1/redirect" -> Answer.of(302, "").header("Location", "/v1/target");
            case "/v1/large" -> Answer.of(200, "x".repeat(2048));
            case "/v1/chunked" -> Answer.of(200, "y".repeat(4096)).chunked();
            case "/v1/slow" -> Answer.of(200, "late").after(Duration.ofSeconds(3));
            case "/v1/fail" -> Answer.of(503, "down");
            default -> Answer.of(404, "");
        });
        port = server.port();
        services = services(ExternalService.define("stub", s -> s.baseUrl(server.url("/v1/"))
            .allowedHosts("127.0.0.1").secret("apiKey", "STUB_KEY").requestTimeout(Duration.ofSeconds(1))
            .idempotencyHeader("Idempotency-Key").maxResponseSize(1024)),
            ExternalService.define("dry", s -> s.baseUrl(server.url("/v1/")).allowedHosts("127.0.0.1")
                .allowInDryRun()));
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
        server.close();
    }

    private static <T> T inStep(boolean dryRun, Callable<T> work) throws Exception {
        return ExternalCallScope.run(ExternalCallScope.of(42L, "Call the stub", dryRun), work);
    }

    private ExternalHttp.Response send(String service, ExternalHttp.Request request) throws Exception {
        return inStep(false, () -> services.http(service).send(request));
    }

    private static ExternalCallException.Kind kindOf(Throwable failure) {
        assertThat(failure).isInstanceOf(ExternalCallException.class);
        return ((ExternalCallException) failure).kind();
    }

    @Test
    void sendsTheRequestAndReturnsTheAnswer() throws Exception {
        ExternalHttp.Response response = inStep(false, () -> services.http("stub").send(
            ExternalHttp.Request.post("echo").header("Authorization", "Bearer " + services.secret("stub", "apiKey"))
                .json("{\"text\":\"hi\"}").idempotencyKey(services.idempotencyKey("moderate"))));
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.text()).isEqualTo("{\"echo\":13}");
        StubHttpServer.Received request = server.requests().getFirst();
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.path()).isEqualTo("/v1/echo");
        assertThat(request.headers()).containsEntry("Authorization", "Bearer " + SECRET)
            .containsEntry("Idempotency-Key", "jabiz-42-moderate").containsEntry("Content-Type", "application/json");
        assertThat(observed).containsExactly(Map.of("@name", "jabiz.external.call", "service", "stub",
            "outcome", "2xx"));
    }

    @Test
    void absoluteAddressesOnTheBaseSchemeAndPortAreAccepted() throws Exception {
        assertThat(send("stub", ExternalHttp.Request.get(server.url("/v1/fail"))).status()).isEqualTo(503);
        assertThat(observed).extracting(tags -> tags.get("outcome")).containsExactly("5xx");
    }

    @Test
    void otherHostsPortsSchemesAndAddressesWithCredentialsAreRefusedBeforeAnythingIsSent() throws Exception {
        int otherPort;
        try (var socket = new java.net.ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            otherPort = socket.getLocalPort();
        }
        for (String target : List.of("http://localhost:" + port + "/v1/echo", "//evil.example.com/v1/echo",
            "https://evil.example.com/steal?key=" + SECRET, "http://user:" + SECRET + "@127.0.0.1:" + port + "/v1/echo",
            "ftp://127.0.0.1/x", "http://127.0.0.1:" + otherPort + "/v1/echo", "https://127.0.0.1:" + port + "/v1/echo",
            "http://127.0.0.1/v1/echo")) {
            Throwable refused = catchThrowable(() -> send("stub", ExternalHttp.Request.get(target)));
            assertThat(refused).as(target).hasMessageNotContaining(SECRET)
                .hasMessageStartingWith("External service stub: ");
            assertThat(kindOf(refused)).as(target).isEqualTo(ExternalCallException.Kind.REFUSED);
        }
        assertThat(server.requests()).isEmpty();
        assertThat(observed).extracting(tags -> tags.get("outcome")).containsOnly("refused");
    }

    /** What the JDK client itself refuses is a refused call (502), not a defect; its message is not passed on. */
    @Test
    void requestsTheClientRefusesAreRefusedCalls() {
        Throwable refused = catchThrowable(() -> send("stub", ExternalHttp.Request.of("CONNECT", "echo")));
        assertThat(kindOf(refused)).isEqualTo(ExternalCallException.Kind.REFUSED);
        assertThat(refused).hasNoCause().hasMessageContaining("method CONNECT");
        assertThat(server.requests()).isEmpty();
    }

    @Test
    void redirectsAreNotFollowed() throws Exception {
        ExternalHttp.Response response = send("stub", ExternalHttp.Request.get("redirect"));
        assertThat(response.status()).isEqualTo(302);
        assertThat(response.header("location")).contains("/v1/target");
        assertThat(server.requests()).extracting(StubHttpServer.Received::path).containsExactly("/v1/redirect");
        assertThat(observed).extracting(tags -> tags.get("outcome")).containsExactly("3xx");
    }

    @Test
    void answersLargerThanTheLimitFail() {
        for (String path : List.of("large", "chunked")) {
            assertThat(kindOf(catchThrowable(() -> send("stub", ExternalHttp.Request.get(path))))).as(path)
                .isEqualTo(ExternalCallException.Kind.TOO_LARGE);
        }
        assertThat(observed).extracting(tags -> tags.get("outcome")).containsExactly("error", "error");
    }

    @Test
    void slowAnswersTimeOut() {
        long started = System.nanoTime();
        Throwable timedOut = catchThrowable(() -> send("stub", ExternalHttp.Request.get("slow?token=" + SECRET)
            .header("Authorization", "Bearer " + SECRET).timeout(Duration.ofMillis(200))));
        assertThat(kindOf(timedOut)).isEqualTo(ExternalCallException.Kind.TIMEOUT);
        assertThat(timedOut).hasMessageNotContaining(SECRET).hasNoCause();
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
        assertThat(observed).extracting(tags -> tags.get("outcome")).containsExactly("timeout");
        // A request may not wait longer than its service allows.
        assertThatThrownBy(() -> send("stub", ExternalHttp.Request.get("echo").timeout(Duration.ofSeconds(5))))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aClosedPortIsUnavailable() throws Exception {
        int unused;
        try (var socket = new java.net.ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            unused = socket.getLocalPort();
        }
        ExternalServicesImpl closed = services(ExternalService.define("gone", s -> s
            .baseUrl("http://127.0.0.1:" + unused).allowedHosts("127.0.0.1")));
        try {
            assertThat(kindOf(catchThrowable(() -> inStep(false,
                () -> closed.http("gone").send(ExternalHttp.Request.get("/x"))))))
                .isEqualTo(ExternalCallException.Kind.UNAVAILABLE);
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
        assertThat(server.requests()).isEmpty();
    }

    /** Only the platform's executors open scopes: nothing else can pass for a blocking step or a real run. */
    @Test
    void scopesCannotBeOpenedByAnyoneElse() {
        assertThatThrownBy(() -> ExternalCallScope.open(1L, "forged", false, () -> "x"))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining(JdkExternalHttpTest.class.getName());
        assertThat(ExternalCallScope.current()).isEmpty();
    }

    @Test
    void theScopeIsClosedWhenTheStepFails() {
        assertThatThrownBy(() -> inStep(true, () -> {
            assertThat(ExternalCallScope.current()).isPresent();
            throw new IllegalStateException("the step failed");
        })).hasMessage("the step failed");
        assertThat(ExternalCallScope.current()).isEmpty();
    }

    @Test
    void dryRunsMakeNoCallsUnlessTheServiceAllowsThem() throws Exception {
        Throwable refused = catchThrowable(() -> inStep(true,
            () -> services.http("stub").send(ExternalHttp.Request.get("echo"))));
        assertThat(kindOf(refused)).isEqualTo(ExternalCallException.Kind.REFUSED);
        assertThat(refused).hasMessageContaining("dry run");
        assertThat(server.requests()).isEmpty();
        assertThat(inStep(true, () -> services.http("dry").send(ExternalHttp.Request.get("echo"))).status())
            .isEqualTo(200);
        assertThat(server.requests()).hasSize(1);
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

    /**
     * A job's handler calls only its type's services, from the job's scope; its idempotency keys are the job's own,
     * the same for every attempt; and only the platform's executor opens such a scope.
     */
    @Test
    void aJobsHandlerCallsOnlyItsTypesServices() throws Exception {
        ExternalCallScope job = ExternalCallScope.ofJob("0190f0e2-aaaa-7bbb-8ccc-000000000001", "it.copy",
            Set.of("stub"));
        assertThat(job.jobId()).contains("0190f0e2-aaaa-7bbb-8ccc-000000000001");
        assertThat(job.toString()).contains("it.copy").doesNotContain("processSeqId");
        ExternalHttp.Response response = ExternalCallScope.run(job, () -> services.http("stub")
            .send(ExternalHttp.Request.post("echo").json("{}")));
        assertThat(response.status()).isEqualTo(200);
        assertThat(ExternalCallScope.run(job, () -> services.secret("stub", "apiKey"))).isEqualTo(SECRET);
        assertThat(ExternalCallScope.run(job, () -> services.idempotencyKey("part-1")))
            .isEqualTo("jabiz-job-0190f0e2-aaaa-7bbb-8ccc-000000000001-part-1");

        server.reset();
        assertThatThrownBy(() -> ExternalCallScope.run(job, () -> services.http("dry")
            .send(ExternalHttp.Request.get("echo"))))
            .isInstanceOfSatisfying(ExternalCallException.class,
                e -> assertThat(e.kind()).isEqualTo(ExternalCallException.Kind.REFUSED))
            .hasMessageContaining("not among the services of the running external job's type");
        assertThatThrownBy(() -> ExternalCallScope.run(job, () -> services.secret("dry", "apiKey")))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("job it.copy");
        assertThat(server.requests()).isEmpty();
        assertThat(ExternalCallScope.current()).isEmpty();

        assertThatThrownBy(() -> ExternalCallScope.openJob("forged", "it.copy", Set.of("stub"), () -> "x"))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining(JdkExternalHttpTest.class.getName());
    }
}
