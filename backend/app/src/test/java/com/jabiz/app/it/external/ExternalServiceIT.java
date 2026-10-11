package com.jabiz.app.it.external;

import com.jabiz.app.commerce.CommerceEntities;
import com.jabiz.app.commerce.ModerateText;
import com.jabiz.app.commerce.ProductRenaming;
import com.jabiz.app.it.fixture.StubAiServer;
import com.jabiz.context.RequestContext;
import com.jabiz.external.ExternalCallException;
import com.jabiz.external.ExternalHttp;
import com.jabiz.external.ExternalService;
import com.jabiz.external.ExternalServices;
import com.jabiz.process.BlockingStep;
import com.jabiz.process.NoMetadata;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.runtime.process.ExecutionOptions;
import com.jabiz.runtime.process.ProcessExecutor;
import com.jabiz.runtime.process.ProcessRegistry;
import com.jabiz.runtime.security.JwtService;
import com.jabiz.runtime.test.PostgresIntegrationTest;
import com.jabiz.runtime.test.TestTokens;
import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Calls of declared external services from blocking steps (decision D38) against a local stub of {@code demo-ai}: the
 * moderation of {@code PRODUCT_RENAME} refuses a flagged name, a failing service answers 502, no request leaves for a
 * host the service does not allow or from anything but a blocking step, dry runs call only services that allow it,
 * idempotency keys are the process's own, and every call is observed with its service and outcome only.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class ExternalServiceIT extends PostgresIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {};
    private static final RequestContext CLERK = new RequestContext("it-clerk", null, Locale.ENGLISH, "it-external",
        Set.of(), Set.of("*"));

    static final StubAiServer STUB = StubAiServer.start();
    static final List<Map<String, String>> CALLS = new CopyOnWriteArrayList<>();

    @DynamicPropertySource
    static void demoAi(DynamicPropertyRegistry registry) {
        registry.add("jabiz.external-services.demo-ai.base-url", STUB::baseUrl);
        registry.add("jabiz.external-services.demo-ai.allowed-hosts", () -> "127.0.0.1");
    }

    @AfterAll
    static void stopStub() {
        STUB.close();
    }

    /** What the probe sends and to which service. */
    public record ProbeInput(String service, String target, String suffix) {}

    public record ProbeOutput(int status, String idempotencyKey, long processSeqId) {}

    /** Sends one GET through a declared service from a blocking step. */
    public static class Probe implements BlockingStep<NoMetadata, ProcessContext> {
        private final ExternalServices services;

        Probe(ExternalServices services) {
            this.services = services;
        }

        @Override
        public void run(NoMetadata metadata, ProcessContext ctx) {
            ProbeInput input = ctx.get("in", ProbeInput.class);
            String key = input.suffix() == null ? null : services.idempotencyKey(input.suffix());
            ExternalHttp.Request request = ExternalHttp.Request.get(input.target());
            ExternalHttp.Response response = services.http(input.service())
                .send(key == null ? request : request.idempotencyKey(key));
            ctx.put("out", new ProbeOutput(response.status(), key, ctx.processSeqId()));
        }
    }

    @TestConfiguration
    static class Fixtures {

        /** Like demo-ai, but not allowed in dry runs. */
        @Bean
        ExternalService strictStub() {
            return ExternalService.define("it-strict", s -> s.baseUrl(STUB.baseUrl()).allowedHosts("127.0.0.1")
                .idempotencyHeader("Idempotency-Key").requestTimeout(Duration.ofSeconds(5)));
        }

        @Bean
        Probe probe(ExternalServices services) {
            return new Probe(services);
        }

        @Bean
        ProcessDefinition<ProbeInput, ProbeOutput, ProcessContext> probeProcess() {
            return ProcessDefinition.define("IT_EXTERNAL_PROBE", 1, ProbeInput.class, ProbeOutput.class,
                ProcessContext.class, pb -> pb
                    .permissions("it.external.probe")
                    .contextFactory((start, in) -> {
                        ProcessContext ctx = new ProcessContext(start);
                        ctx.put("in", in);
                        return ctx;
                    })
                    .outputMapper(ctx -> ctx.get("out", ProbeOutput.class))
                    .step("Probe", Probe.class, NoMetadata.INSTANCE));
        }

        /** Tries to call from a computation, which runs on a non-blocking thread outside any blocking step. */
        @Bean
        ProcessDefinition<ProbeInput, ProbeOutput, ProcessContext> computeProbeProcess(ExternalServices services) {
            return ProcessDefinition.define("IT_EXTERNAL_FROM_COMPUTE", 1, ProbeInput.class, ProbeOutput.class,
                ProcessContext.class, pb -> pb
                    .permissions("it.external.probe")
                    .contextFactory((start, in) -> {
                        ProcessContext ctx = new ProcessContext(start);
                        ctx.put("in", in);
                        return ctx;
                    })
                    .outputMapper(ctx -> ctx.get("out", ProbeOutput.class))
                    .compute("Call", (metadata, ctx) -> {
                        ExternalHttp.Response response = services.http("demo-ai")
                            .send(ExternalHttp.Request.get("/v1/moderate"));
                        ctx.put("out", new ProbeOutput(response.status(), null, ctx.processSeqId()));
                    }));
        }

        @Bean
        ObservationHandler<Observation.Context> externalCalls() {
            return new ObservationHandler<>() {
                @Override
                public void onStop(Observation.Context context) {
                    if (context.getName().equals("jabiz.external.call")) {
                        Map<String, String> tags = new TreeMap<>();
                        for (KeyValue kv : context.getLowCardinalityKeyValues()) {
                            tags.put(kv.getKey(), kv.getValue());
                        }
                        CALLS.add(tags);
                    }
                }

                @Override
                public boolean supportsContext(Observation.Context context) {
                    return true;
                }
            };
        }
    }

    @Autowired
    ApplicationContext context;

    @Autowired
    JwtService tokens;

    @Autowired
    ProcessExecutor executor;

    @Autowired
    ProcessRegistry processes;

    WebTestClient client;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToApplicationContext(context).build();
        STUB.reset();
        CALLS.clear();
    }

    @Test
    void aNameTheModerationServiceAcceptsIsSaved() {
        String sku = sku();
        String productId = product(sku);
        Map<String, Object> result = process(ProductRenaming.PRODUCT_RENAME, Map.of("productId", productId,
            "productName", "Fresh apples")).expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
        assertThat(output(result)).containsEntry("productName", "Fresh apples").containsEntry("sku", sku);
        assertThat(productName(sku)).isEqualTo("Fresh apples");

        StubAiServer.Received call = STUB.requests("/v1/moderate").getFirst();
        assertThat(STUB.requests()).hasSize(1);
        assertThat(call.method()).isEqualTo("POST");
        assertThat(call.body()).contains("Fresh apples");
        assertThat(call.header("Authorization")).isEqualTo("Bearer test-only-demo-ai-key");
        assertThat(call.header("Idempotency-Key")).isEqualTo("jabiz-" + result.get("processSeqId") + "-moderate");
        assertThat(CALLS).containsExactly(Map.of("service", ModerateText.SERVICE, "outcome", "2xx"));
    }

    @Test
    void aNameTheServiceFlagsIsRefusedAndNothingChanges() {
        String sku = sku();
        String productId = product(sku);
        Map<String, Object> problem = process(ProductRenaming.PRODUCT_RENAME, Map.of("productId", productId,
            "productName", "Cheap BADWORD deals")).expectStatus().isEqualTo(422).expectBody(MAP).returnResult()
            .getResponseBody();
        assertThat(violations(problem)).singleElement().satisfies(v -> {
            assertThat(v).containsEntry("ruleCode", ModerateText.INAPPROPRIATE).containsEntry("field", "productName");
            assertThat(String.valueOf(v.get("message"))).contains("harassment");
        });
        assertThat(productName(sku)).isEqualTo("Product " + sku);
    }

    @Test
    void aFailingServiceAnswers502WithTheServiceAndTheKindOnly() {
        String sku = sku();
        String productId = product(sku);
        STUB.script("/v1/moderate", 503, "{\"error\":\"overloaded\"}", null);
        Map<String, Object> problem = process(ProductRenaming.PRODUCT_RENAME, Map.of("productId", productId,
            "productName", "Fine name")).expectStatus().isEqualTo(502).expectBody(MAP).returnResult()
            .getResponseBody();
        assertThat(violations(problem)).singleElement().satisfies(v -> {
            assertThat(v).containsEntry("ruleCode", "EXTERNAL_CALL_FAILED");
            assertThat(String.valueOf(v.get("message"))).contains(ModerateText.SERVICE)
                .doesNotContain("test-only-demo-ai-key");
        });
        assertThat(productName(sku)).isEqualTo("Product " + sku);
        assertThat(CALLS).containsExactly(Map.of("service", ModerateText.SERVICE, "outcome", "5xx"));
    }

    @Test
    void requestsToHostsTheServiceDoesNotAllowAreNeverSent() {
        String port = STUB.baseUrl().replaceAll(".*:(\\d+)/v1$", "$1");
        for (String target : List.of("http://localhost:" + port + "/v1/moderate", "https://evil.example.com/v1",
            "//evil.example.com/v1/moderate")) {
            Map<String, Object> problem = process("IT_EXTERNAL_PROBE", Map.of("service", ModerateText.SERVICE,
                "target", target)).expectStatus().isEqualTo(502).expectBody(MAP).returnResult().getResponseBody();
            assertThat(violations(problem)).singleElement().satisfies(v -> {
                assertThat(v).containsEntry("ruleCode", "EXTERNAL_CALL_FAILED");
                assertThat(String.valueOf(v.get("message"))).contains(ModerateText.SERVICE, "(REFUSED)");
            });
        }
        assertThat(STUB.requests()).isEmpty();
        assertThat(CALLS).extracting(tags -> tags.get("outcome")).containsOnly("refused").hasSize(3);
    }

    @Test
    void onlyBlockingStepsMayCall() {
        process("IT_EXTERNAL_FROM_COMPUTE", Map.of("service", ModerateText.SERVICE, "target", "/v1/moderate"))
            .expectStatus().isEqualTo(500);
        assertThat(STUB.requests()).isEmpty();
    }

    @Test
    void theIdempotencyKeyIsTheProcesssOwn() {
        Map<String, Object> result = process("IT_EXTERNAL_PROBE", Map.of("service", "it-strict",
            "target", "/v1/moderate", "suffix", "probe")).expectStatus().isOk().expectBody(MAP).returnResult()
            .getResponseBody();
        Map<String, Object> output = output(result);
        String expected = "jabiz-" + result.get("processSeqId") + "-probe";
        assertThat(output).containsEntry("idempotencyKey", expected).containsEntry("status", 200);
        assertThat(STUB.requests().getFirst().header("Idempotency-Key")).isEqualTo(expected);
    }

    @Test
    void dryRunsCallOnlyTheServicesThatAllowThem() {
        String sku = sku();
        String productId = product(sku);
        ProductRenaming.RenameOutput renamed = asRequest(CLERK, executor.run(ProductRenaming.RENAME_PROCESS,
            new ProductRenaming.RenameInput(UUID.fromString(productId), "Preview name"), ExecutionOptions.DRY_RUN))
            .block().output();
        assertThat(renamed.productName()).isEqualTo("Preview name");
        assertThat(STUB.requests("/v1/moderate")).hasSize(1);
        assertThat(productName(sku)).isEqualTo("Product " + sku);

        STUB.reset();
        @SuppressWarnings("unchecked")
        ProcessDefinition<ProbeInput, ProbeOutput, ProcessContext> probe =
            (ProcessDefinition<ProbeInput, ProbeOutput, ProcessContext>) processes.find("IT_EXTERNAL_PROBE", 1)
                .orElseThrow();
        assertThatThrownBy(() -> asRequest(CLERK, executor.run(probe, new ProbeInput("it-strict", "/v1/moderate",
            null), ExecutionOptions.DRY_RUN)).block())
            .isInstanceOfSatisfying(ExternalCallException.class,
                e -> assertThat(e.kind()).isEqualTo(ExternalCallException.Kind.REFUSED))
            .hasMessageContaining("dry run");
        assertThat(STUB.requests()).isEmpty();
        // Outside a dry run the same call goes out.
        assertThat(asRequest(CLERK, executor.execute(probe, new ProbeInput("it-strict", "/v1/moderate", null)))
            .block().status()).isEqualTo(200);
        assertThat(STUB.requests()).hasSize(1);
    }

    // ---------------------------------------------------------------- helpers

    private static String sku() {
        return "X" + UUID.randomUUID().toString().substring(0, 6).toUpperCase(Locale.ROOT);
    }

    private WebTestClient.ResponseSpec post(String path, Object body) {
        return client.post().uri(path).header(HttpHeaders.AUTHORIZATION, TestTokens.bearer(tokens, "it-admin", "*"))
            .contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange();
    }

    private WebTestClient.ResponseSpec process(String name, Object input) {
        return post("/api/processes/" + name + "/latest", input);
    }

    private String product(String sku) {
        post("/api/datasets/" + CommerceEntities.PRODUCT_DATASET + "/commit", Map.of("changes", List.of(Map.of(
            "action", "INSERT", "attributes", Map.of("sku", sku, "productName", "Product " + sku, "unitPrice", 100,
                "active", true))))).expectStatus().isOk();
        return String.valueOf(products(sku).getFirst().get("id"));
    }

    private String productName(String sku) {
        @SuppressWarnings("unchecked")
        Map<String, Object> attributes = (Map<String, Object>) products(sku).getFirst().get("attributes");
        return String.valueOf(attributes.get("productName"));
    }

    private List<Map<String, Object>> products(String sku) {
        Map<String, Object> page = post("/api/datasets/" + CommerceEntities.PRODUCT_DATASET + "/query",
            Map.of("filters", List.of(Map.of("field", "sku", "op", "eq", "value", sku)))).expectStatus().isOk()
            .expectBody(MAP).returnResult().getResponseBody();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) page.get("items");
        return items;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> output(Map<String, Object> result) {
        return (Map<String, Object>) result.get("output");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> violations(Map<String, Object> problem) {
        return (List<Map<String, Object>>) problem.get("violations");
    }
}
