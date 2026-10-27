package com.jabiz.app.it.security;

import com.jabiz.context.RequestContext;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.security.AppProxyCaller;
import com.jabiz.security.AppProxySignature;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * App proxies (decision D60; docs/design/10-security.md section 15.13; ROADMAP phase 17d): a request another platform
 * forwards for a visitor of one of its tenants, signed in the query, runs a process declared {@code appProxy} as the
 * proxy's caller; anything that does not hold is refused alike before the process, which no other way reaches; the
 * rate limits count per tenant and per visitor key.
 */
@SpringBootTest(properties = {
    "it.sql-log.enabled=true",
    "jabiz.app-proxies.shop.secrets[0]=" + AppProxyIT.OLD_SECRET,
    "jabiz.app-proxies.shop.secrets[1]=" + AppProxyIT.SECRET,
    "jabiz.app-proxies.shop.tenant-pattern=[a-z0-9][a-z0-9-]*\\.shop\\.example",
    "jabiz.process.rate-limits.IT_PROXY_ECHO.per-visitor=2",
    "jabiz.process.rate-limits.IT_PROXY_ECHO.per-tenant=4"})
@Import(AppProxyIT.Processes.class)
class AppProxyIT extends SecurityItSupport {

    static final String SECRET = "it-app-proxy-secret-0123456789abcdef0123";
    static final String OLD_SECRET = "it-app-proxy-old-secret-0123456789abcdef";
    static final String ECHO = "IT_PROXY_ECHO";
    static final String PATH = "/api/proxy/shop/" + ECHO;

    @Autowired
    Clock clock;

    /** The query as the other platform forwards it: the visitor's own parameters, its own, signed. */
    String signed(String tenant, String customer, Consumer<Map<String, List<String>>> change, String secret) {
        Map<String, List<String>> query = new LinkedHashMap<>();
        query.put("visitor", List.of(UUID.randomUUID().toString()));
        query.put("shop", List.of(tenant));
        query.put("logged_in_customer_id", List.of(customer == null ? "" : customer));
        query.put("path_prefix", List.of("/apps/demo"));
        query.put("timestamp", List.of(String.valueOf(clock.instant().getEpochSecond())));
        change.accept(query);
        query.put("signature", List.of(AppProxySignature.sign(query, secret)));
        return encode(query);
    }

    String signed(String tenant, String customer) {
        return signed(tenant, customer, q -> { }, SECRET);
    }

    static String encode(Map<String, List<String>> query) {
        List<String> pairs = new ArrayList<>();
        query.forEach((name, values) -> values.forEach(value -> pairs.add(URLEncoder.encode(name,
            StandardCharsets.UTF_8) + "=" + URLEncoder.encode(value, StandardCharsets.UTF_8))));
        return String.join("&", pairs);
    }

    static String tenant(String prefix) {
        return unique(prefix).toLowerCase() + ".shop.example";
    }

    WebTestClient.ResponseSpec getProxy(String path, String query) {
        return client.get().uri(URI.create(path + "?" + query)).exchange();
    }

    WebTestClient.ResponseSpec postProxy(String path, String query, Object body) {
        return client.post().uri(URI.create(path + "?" + query)).contentType(MediaType.APPLICATION_JSON).bodyValue(body)
            .exchange();
    }

    Map<String, Object> answer(WebTestClient.ResponseSpec response) {
        return response.expectStatus().isOk().expectHeader().valueEquals("Cache-Control", "no-store")
            .expectBody(MAP).returnResult().getResponseBody();
    }

    @Test
    void aSignedRequestRunsTheProcessAsTheProxysCaller() {
        String tenant = tenant("echo");
        // An anonymous visitor; the path prefix with its '/' and a value with a space come as the platform signs them.
        Map<String, Object> visitor = answer(getProxy(PATH, signed(tenant, null, q -> q.put("note", List.of("a b+c")),
            SECRET)));
        assertThat(visitor).containsEntry("proxy", "shop").containsEntry("tenant", tenant)
            .containsEntry("customerId", null).containsEntry("actor", "app-proxy:shop");
        // A signed-in customer of the tenant, with a body (its input, not signed); an old secret still signs.
        Map<String, Object> customer = answer(postProxy(PATH, signed(tenant, "42", q -> { }, OLD_SECRET),
            Map.of("note", "hello")));
        assertThat(customer).containsEntry("customerId", "42").containsEntry("note", "hello")
            .containsEntry("actor", "app-proxy:shop:42");
        assertThat(query("SELECT actor_id FROM op_process WHERE process_name = ? ORDER BY process_seq_id", ECHO))
            .extracting(row -> row.get("actor_id")).contains("app-proxy:shop", "app-proxy:shop:42");
        // Credentials sent along change nothing.
        assertThat(answer(client.get().uri(URI.create(PATH + "?" + signed(tenant, null))).header("Authorization", "Bearer x")
            .exchange())).containsEntry("actor", "app-proxy:shop");
    }

    @Test
    void anythingThatDoesNotHoldIsRefusedAlike() {
        String tenant = tenant("bad");
        long now = clock.instant().getEpochSecond();
        int runs = query("SELECT 1 FROM op_process WHERE process_name = ?", ECHO).size();
        List<String> refused = List.of(
            signed(tenant, null, q -> { }, "another-secret-of-forty-characters-0123"),
            signed(tenant, null, q -> q.put("timestamp", List.of(String.valueOf(now - 600))), SECRET),
            signed("evil.example", null),
            signed(tenant, "not an id"),
            signed(tenant, null, q -> q.put("shop", List.of(tenant, tenant("other"))), SECRET),
            signed(tenant, null).replace("visitor=", "visitor=x"),
            signed(tenant, null).replaceAll("&signature=[0-9a-f]+", ""),
            "shop=" + tenant,
            "");
        for (String query : refused) {
            Map<String, Object> problem = getProxy(PATH, query).expectStatus().isUnauthorized().expectBody(MAP)
                .returnResult().getResponseBody();
            assertThat(ruleCode(problem)).as(query).isEqualTo("APP_PROXY_REFUSED");
            // The same answer whatever failed.
            assertThat(problem.get("detail")).as(query).isEqualTo("Authentication failed");
        }
        // Refused before the process: nothing ran.
        assertThat(query("SELECT 1 FROM op_process WHERE process_name = ?", ECHO)).hasSize(runs);
    }

    @Test
    void onlyAProxysOwnProcessesRunThroughItAndOnlyThere() {
        String tenant = tenant("only");
        // Another proxy, another process (open, or another kind), a version path, other methods: not here.
        getProxy("/api/proxy/none/" + ECHO, signed(tenant, null)).expectStatus().isNotFound();
        getProxy("/api/proxy/shop/IT_PROXY_OPEN", signed(tenant, null)).expectStatus().isNotFound();
        getProxy("/api/proxy/shop/NO_SUCH_PROCESS", signed(tenant, null)).expectStatus().isNotFound();
        getProxy("/api/proxy/shop/" + ECHO + "/latest", signed(tenant, null)).expectStatus().isNotFound();
        client.put().uri(URI.create(PATH + "?" + signed(tenant, null))).exchange().expectStatus().isEqualTo(405);
        // The process API does not run it, whoever asks.
        Map<String, Object> problem = post("/api/processes/" + ECHO + "/latest",
            bearer(AppProxyCaller.permission("shop")), Map.of()).expectStatus().isForbidden().expectBody(MAP)
            .returnResult().getResponseBody();
        assertThat(ruleCode(problem)).isEqualTo("PROCESS_INTERNAL");
    }

    @Test
    void theLimitsCountPerVisitorAndPerTenant() {
        String tenant = tenant("limits");
        Consumer<Map<String, List<String>>> same = q -> q.put("visitor", List.of("v-1"));
        answer(getProxy(PATH, signed(tenant, null, same, SECRET)));
        answer(getProxy(PATH, signed(tenant, null, same, SECRET)));
        Map<String, Object> limited = getProxy(PATH, signed(tenant, null, same, SECRET)).expectStatus()
            .isEqualTo(429).expectBody(MAP).returnResult().getResponseBody();
        assertThat(ruleCode(limited)).isEqualTo("RATE_LIMITED");
        // Another visitor of the tenant has their own allowance, within the tenant's.
        answer(getProxy(PATH, signed(tenant, null, q -> q.put("visitor", List.of("v-2")), SECRET)));
        answer(getProxy(PATH, signed(tenant, null, q -> q.put("visitor", List.of("v-3")), SECRET)));
        getProxy(PATH, signed(tenant, null, q -> q.put("visitor", List.of("v-4")), SECRET)).expectStatus()
            .isEqualTo(429);
        // Another tenant is counted apart.
        answer(getProxy(PATH, signed(tenant("limits-other"), null, same, SECRET)));
    }

    record EchoInput(@Size(max = 100) String note) {}

    /** What the process saw of its caller. */
    record EchoOutput(String proxy, String tenant, String customerId, String note, String actor) {}

    record OpenInput() {}

    @TestConfiguration
    static class Processes {

        @Bean
        ProcessDefinition<EchoInput, EchoOutput, ProcessContext> itProxyEcho() {
            return ProcessDefinition.define(ECHO, 1, EchoInput.class, EchoOutput.class, ProcessContext.class, pb -> pb
                .appProxy("shop")
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put("in", input);
                    return ctx;
                })
                .compute("Read the caller", (metadata, ctx) -> ctx.put("caller",
                    AppProxyCaller.of(ctx.request()).orElseThrow()))
                .outputMapper(ctx -> {
                    RequestContext request = ctx.request();
                    AppProxyCaller caller = ctx.get("caller", AppProxyCaller.class);
                    String note = ctx.get("in", EchoInput.class).note();
                    return new EchoOutput(caller.proxy(), caller.tenant(), caller.customerId(), note,
                        request.actorId());
                }));
        }

        @Bean
        ProcessDefinition<OpenInput, OpenInput, ProcessContext> itProxyOpen() {
            return ProcessDefinition.define("IT_PROXY_OPEN", 1, OpenInput.class, OpenInput.class,
                ProcessContext.class, pb -> pb
                    .permissions(AppProxyCaller.permission("shop"))
                    .contextFactory((start, input) -> new ProcessContext(start))
                    .compute("Nothing", (metadata, ctx) -> { })
                    .outputMapper(ctx -> new OpenInput()));
        }
    }
}
