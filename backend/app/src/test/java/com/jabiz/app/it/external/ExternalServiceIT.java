package com.jabiz.app.it.external;

import com.jabiz.app.commerce.CommerceEntities;
import com.jabiz.app.commerce.ModerateText;
import com.jabiz.app.commerce.ProductRenaming;
import com.jabiz.app.it.fixture.StubAiServer;
import com.jabiz.context.RequestContext;
import com.jabiz.entity.SemanticKind;
import com.jabiz.external.ExternalCallException;
import com.jabiz.external.ExternalHttp;
import com.jabiz.external.ExternalService;
import com.jabiz.external.ExternalServices;
import com.jabiz.imports.ImportDefinition;
import com.jabiz.imports.ImportFormat;
import com.jabiz.process.BlockingStep;
import com.jabiz.process.NoMetadata;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.runtime.process.ExecutionOptions;
import com.jabiz.runtime.process.ProcessExecutor;
import com.jabiz.runtime.process.ProcessRegistry;
import com.jabiz.runtime.process.steps.CallProcess;
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

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
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
 * moderation of {@code PRODUCT_RENAME} refuses a flagged name, a failing or slow service answers 502, no request leaves
 * for a host the service does not allow or from anything but a blocking step, dry runs (sub-processes' and imports'
 * previews included) call only services that allow it, idempotency keys are the process's own, and every call is
 * observed with its service and outcome only.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class ExternalServiceIT extends PostgresIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {};
    private static final RequestContext CLERK = new RequestContext("it-clerk", null, Locale.ENGLISH, "it-external",
        Set.of(), Set.of("*"));
    private static final String BOUNDARY = "jabiz-it-external-7f2e";

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

    /** What the probe sends and to which service; a timeout of its own when given. */
    public record ProbeInput(String service, String target, String suffix, Integer timeoutMillis) {}

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
            if (input.timeoutMillis() != null) {
                request = request.timeout(Duration.ofMillis(input.timeoutMillis()));
            }
            ExternalHttp.Response response = services.http(input.service())
                .send(key == null ? request : request.idempotencyKey(key));
            ctx.put("out", new ProbeOutput(response.status(), key, ctx.processSeqId()));
        }
    }

    private static ProcessContext withInput(com.jabiz.process.ProcessStart start, Object input) {
        ProcessContext ctx = new ProcessContext(start);
        ctx.put("in", input);
        return ctx;
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
                    .contextFactory(ExternalServiceIT::withInput)
                    .outputMapper(ctx -> ctx.get("out", ProbeOutput.class))
                    .step("Probe", Probe.class, NoMetadata.INSTANCE));
        }

        /** Calls the probe as a sub-process: a dry run of the parent is one of the child too. */
        @Bean
        ProcessDefinition<ProbeInput, ProbeOutput, ProcessContext> parentProcess() {
            return ProcessDefinition.define("IT_EXTERNAL_PARENT", 1, ProbeInput.class, ProbeOutput.class,
                ProcessContext.class, pb -> pb
                    .permissions("it.external.probe")
                    .contextFactory(ExternalServiceIT::withInput)
                    .outputMapper(ctx -> ctx.get("child", ProbeOutput.class))
                    .step("Call the probe", CallProcess.of("IT_EXTERNAL_PROBE", 1, ctx -> ctx.get("in",
                        ProbeInput.class), "child")));
        }

        /** Tries to call from a computation, which runs on a non-blocking thread outside any blocking step. */
        @Bean
        ProcessDefinition<ProbeInput, ProbeOutput, ProcessContext> computeProbeProcess(ExternalServices services) {
            return ProcessDefinition.define("IT_EXTERNAL_FROM_COMPUTE", 1, ProbeInput.class, ProbeOutput.class,
                ProcessContext.class, pb -> pb
                    .permissions("it.external.probe")
                    .contextFactory(ExternalServiceIT::withInput)
                    .outputMapper(ctx -> ctx.get("out", ProbeOutput.class))
                    .compute("Call", (metadata, ctx) -> {
                        ExternalHttp.Response response = services.http("demo-ai")
                            .send(ExternalHttp.Request.get("/v1/moderate"));
                        ctx.put("out", new ProbeOutput(response.status(), null, ctx.processSeqId()));
                    }));
        }

        /** Each row probes its target through it-strict, which is not called in dry runs (previews). */
        @Bean
        ImportDefinition<ImportDefinition.NoParams> probeImport() {
            return ImportDefinition.define("it.external-probe", 1)
                .file("app.import", ImportFormat.csv())
                .field("target", new SemanticKind.Text(200, false), true, "Target")
                .perRow("IT_EXTERNAL_PROBE", 1, (row, params) -> new ProbeInput("it-strict", row.text("target"),
                    null, null))
                .permissions("it.external.import")
                .build();
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

        var call = STUB.requests("/v1/moderate").getFirst();
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
            assertThat(String.valueOf(v.get("message"))).contains(ModerateText.SERVICE, "its answer could not be used")
                .doesNotContain("test-only-demo-ai-key", "BAD_RESPONSE");
        });
        assertThat(productName(sku)).isEqualTo("Product " + sku);
        assertThat(CALLS).containsExactly(Map.of("service", ModerateText.SERVICE, "outcome", "5xx"));
    }

    @Test
    void aSlowServiceTimesOutTheProcessWith502() {
        STUB.script("/v1/slow", 200, "{}", Duration.ofSeconds(3));
        long started = System.nanoTime();
        Map<String, Object> problem = process("IT_EXTERNAL_PROBE", Map.of("service", "it-strict",
            "target", "/v1/slow", "timeoutMillis", 300)).expectStatus().isEqualTo(502).expectBody(MAP).returnResult()
            .getResponseBody();
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));
        assertThat(violations(problem)).singleElement().satisfies(v ->
            assertThat(String.valueOf(v.get("message"))).contains("it-strict", "no answer in time"));
        assertThat(CALLS).containsExactly(Map.of("service", "it-strict", "outcome", "timeout"));
    }

    @Test
    void requestsToHostsTheServiceDoesNotAllowAreNeverSent() {
        for (String target : List.of("http://localhost:" + STUB.port() + "/v1/moderate",
            "https://evil.example.com/v1", "//evil.example.com/v1/moderate")) {
            Map<String, Object> problem = process("IT_EXTERNAL_PROBE", Map.of("service", ModerateText.SERVICE,
                "target", target)).expectStatus().isEqualTo(502).expectBody(MAP).returnResult().getResponseBody();
            assertThat(violations(problem)).singleElement().satisfies(v -> {
                assertThat(v).containsEntry("ruleCode", "EXTERNAL_CALL_FAILED");
                assertThat(String.valueOf(v.get("message"))).contains(ModerateText.SERVICE,
                    "the call was not allowed");
            });
        }
        assertThat(STUB.requests()).isEmpty();
        assertThat(CALLS).extracting(tags -> tags.get("outcome")).containsOnly("refused").hasSize(3);
    }

    @Test
    void onlyBlockingStepsMayCall() {
        ProcessDefinition<ProbeInput, ProbeOutput, ProcessContext> fromCompute = definition("IT_EXTERNAL_FROM_COMPUTE");
        assertThatThrownBy(() -> asRequest(CLERK, executor.execute(fromCompute,
            new ProbeInput(ModerateText.SERVICE, "/v1/moderate", null, null))).block())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("call external services from a BlockingStep only");
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
        ProcessDefinition<ProbeInput, ProbeOutput, ProcessContext> probe = definition("IT_EXTERNAL_PROBE");
        ProbeInput strict = new ProbeInput("it-strict", "/v1/moderate", null, null);
        assertThatThrownBy(() -> asRequest(CLERK, executor.run(probe, strict, ExecutionOptions.DRY_RUN)).block())
            .isInstanceOfSatisfying(ExternalCallException.class,
                e -> assertThat(e.kind()).isEqualTo(ExternalCallException.Kind.REFUSED))
            .hasMessageContaining("dry run");
        assertThat(STUB.requests()).isEmpty();
        // Outside a dry run the same call goes out.
        assertThat(asRequest(CLERK, executor.execute(probe, strict)).block().status()).isEqualTo(200);
        assertThat(STUB.requests()).hasSize(1);
    }

    @Test
    void aDryRunReachesTheBlockingStepsOfSubProcesses() {
        ProcessDefinition<ProbeInput, ProbeOutput, ProcessContext> parent = definition("IT_EXTERNAL_PARENT");
        ProbeInput strict = new ProbeInput("it-strict", "/v1/moderate", null, null);
        assertThatThrownBy(() -> asRequest(CLERK, executor.run(parent, strict, ExecutionOptions.DRY_RUN)).block())
            .isInstanceOfSatisfying(ExternalCallException.class,
                e -> assertThat(e.kind()).isEqualTo(ExternalCallException.Kind.REFUSED))
            .hasMessageContaining("dry run");
        assertThat(STUB.requests()).isEmpty();
        assertThat(asRequest(CLERK, executor.execute(parent, strict)).block().status()).isEqualTo(200);
        assertThat(STUB.requests()).hasSize(1);
    }

    /**
     * An import's rows whose process calls a service are previewed as a dry run: a service that does not take part
     * in dry runs, or refuses the call, is a problem of the row (EXTERNAL_CALL_FAILED), not a failed import.
     */
    @Test
    void importsReportFailedCallsAsProblemsOfTheirRows() {
        String good = "/v1/moderate";
        String fileId = upload("Target\n" + good + "\nhttps://evil.example.com/x\n");

        Map<String, Object> preview = post("/api/imports/it.external-probe/preview", Map.of("fileId", fileId))
            .expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
        assertThat(preview).containsEntry("accepted", false);
        assertThat(issues(preview)).containsExactly("1:EXTERNAL_CALL_FAILED", "2:EXTERNAL_CALL_FAILED");
        assertThat(list(preview, "issues").getFirst().get("message").toString()).contains("it-strict");
        assertThat(STUB.requests()).isEmpty();

        // Committing calls for real: the refused row rejects the import as a whole.
        Map<String, Object> rejected = post("/api/imports/it.external-probe/commit", Map.of("fileId", fileId))
            .expectStatus().isEqualTo(422).expectBody(MAP).returnResult().getResponseBody();
        @SuppressWarnings("unchecked")
        Map<String, Object> report = (Map<String, Object>) rejected.get("report");
        assertThat(issues(report)).containsExactly("2:EXTERNAL_CALL_FAILED");
        assertThat(STUB.requests()).hasSize(1);

        STUB.reset();
        Map<String, Object> committed = post("/api/imports/it.external-probe/commit",
            Map.of("fileId", upload("Target\n" + good + "\n" + good + "?again\n"))).expectStatus().isOk()
            .expectBody(MAP).returnResult().getResponseBody();
        assertThat(committed).containsEntry("committed", true).containsEntry("processed", 2);
        assertThat(STUB.requests()).hasSize(2);
    }

    // ---------------------------------------------------------------- helpers

    @SuppressWarnings("unchecked")
    private ProcessDefinition<ProbeInput, ProbeOutput, ProcessContext> definition(String name) {
        return (ProcessDefinition<ProbeInput, ProbeOutput, ProcessContext>) processes.find(name, 1).orElseThrow();
    }

    private static String sku() {
        return "X" + UUID.randomUUID().toString().substring(0, 6).toUpperCase(Locale.ROOT);
    }

    private String admin() {
        return TestTokens.bearer(tokens, "it-admin", "*");
    }

    private WebTestClient.ResponseSpec post(String path, Object body) {
        return client.post().uri(path).header(HttpHeaders.AUTHORIZATION, admin())
            .contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange();
    }

    private WebTestClient.ResponseSpec process(String name, Object input) {
        return post("/api/processes/" + name + "/latest", input);
    }

    /** Uploads an import file (hand-built multipart, as ImportIT does). */
    private String upload(String text) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(("--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"targets.csv\""
            + "\r\nContent-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.writeBytes(text.getBytes(StandardCharsets.UTF_8));
        out.writeBytes(("\r\n--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.UTF_8));
        Map<String, Object> file = client.post().uri("/api/files?policy=app.import")
            .header(HttpHeaders.AUTHORIZATION, admin())
            .contentType(MediaType.parseMediaType("multipart/form-data; boundary=" + BOUNDARY))
            .bodyValue(out.toByteArray()).exchange()
            .expectStatus().isCreated().expectBody(MAP).returnResult().getResponseBody();
        return String.valueOf(file.get("fileId"));
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
        return list(page, "items");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> output(Map<String, Object> result) {
        return (Map<String, Object>) result.get("output");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Map<String, Object> map, String key) {
        return (List<Map<String, Object>>) map.get(key);
    }

    private static List<Map<String, Object>> violations(Map<String, Object> problem) {
        return list(problem, "violations");
    }

    private static List<String> issues(Map<String, Object> report) {
        return list(report, "issues").stream().map(i -> i.get("row") + ":" + i.get("code")).toList();
    }
}
