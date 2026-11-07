package com.jabiz.app.it.security;

import com.jabiz.app.it.fixture.SqlStatementLog;
import com.jabiz.context.ClientCountry;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.runtime.process.steps.LoadClientCountry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.server.WebFilter;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The country a request comes from (decision D74; ROADMAP 17zl), from the client address behind the trusted proxies
 * and the local address database of the test (documentation ranges given countries): the region endpoint, never
 * cached and open to everyone, and the process step, which runs no statement and leaves the address nowhere.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {"it.sql-log.enabled=true",
    "jabiz.geoip.database=src/test/resources/geoip/test-countries.csv", "jabiz.geoip.required=true",
    "jabiz.security.trusted-proxies=10.0.0.0/8"})
class ClientCountryIT extends SecurityItSupport {

    private static final String CONNECTION = "X-It-Connection";
    private static final String FORWARDED = "X-Forwarded-For";

    record CountryInput(String note) {}

    record CountryOutput(String country, boolean mainlandChina) {}

    @TestConfiguration
    static class Processes {

        /** A process that says where its caller is, as a business process would read it in a compute step. */
        @Bean
        ProcessDefinition<CountryInput, CountryOutput, ProcessContext> itClientCountryProcess() {
            return ProcessDefinition.define("IT_CLIENT_COUNTRY", 1, CountryInput.class, CountryOutput.class,
                ProcessContext.class, pb -> pb
                    .permissions("it.country")
                    .contextFactory((start, input) -> new ProcessContext(start))
                    .step("Where from", LoadClientCountry.into("country"))
                    .outputMapper(ctx -> {
                        ClientCountry country = ctx.get("country", ClientCountry.class);
                        return new CountryOutput(country.code(), country.mainlandChina());
                    }));
        }
    }

    @BeforeEach
    @Override
    protected void client() {
        WebFilter connection = (exchange, chain) -> {
            String address = exchange.getRequest().getHeaders().getFirst(CONNECTION);
            return chain.filter(address == null ? exchange : exchange.mutate().request(request -> request
                .remoteAddress(new InetSocketAddress(address, 40000))).build());
        };
        client = WebTestClient.bindToApplicationContext(context).webFilter(connection).build();
    }

    private Map<String, Object> region(String connection, String forwardedFor, String bearer) {
        WebTestClient.RequestHeadersSpec<?> request = client.get().uri("/api/client/region").header(CONNECTION, connection);
        if (forwardedFor != null) {
            request = request.header(FORWARDED, forwardedFor);
        }
        if (bearer != null) {
            request = request.header(HttpHeaders.AUTHORIZATION, bearer);
        }
        return request.exchange().expectStatus().isOk()
            .expectHeader().value(HttpHeaders.CACHE_CONTROL, value -> assertThat(value).contains("no-store"))
            .expectBody(MAP).returnResult().getResponseBody();
    }

    @Test
    void theRegionIsTheCallersOwnBehindTheTrustedProxiesAndNeverCached() {
        // Anonymous, straight from a mainland address.
        assertThat(region("192.0.2.10", null, null)).containsEntry("country", "CN").containsEntry("mainlandChina", true)
            .containsEntry("known", true);
        // Through a trusted proxy: the forwarded client counts; IPv6 too.
        assertThat(region("10.0.0.5", "198.51.100.3", null)).containsEntry("country", "JP")
            .containsEntry("mainlandChina", false);
        assertThat(region("10.0.0.5", "2001:db8::7", null)).containsEntry("country", "CN");
        // Hong Kong is not the mainland.
        assertThat(region("203.0.113.9", null, null)).containsEntry("country", "HK")
            .containsEntry("mainlandChina", false);
        // An untrusted connection cannot claim a country by a header.
        assertThat(region("198.51.100.3", "192.0.2.10", null)).containsEntry("country", "JP");
        // Not listed, or a private address: unknown. A stale token does not matter.
        assertThat(region("10.0.0.5", null, "Bearer garbage")).containsEntry("known", false)
            .containsEntry("mainlandChina", false);
        assertThat(region("2001:db8:2::1", null, null).get("country")).isNull();
    }

    @Test
    void aProcessReadsTheCountryWithoutAStatementAndLeavesTheAddressNowhere() {
        String bearer = bearer("it.country");
        SqlStatementLog.STATEMENTS.clear();
        Map<String, Object> body = client.post().uri("/api/processes/IT_CLIENT_COUNTRY/latest")
            .contentType(MediaType.APPLICATION_JSON).header(HttpHeaders.AUTHORIZATION, bearer)
            .header(CONNECTION, "10.0.0.5").header(FORWARDED, "2001:db8:1::9")
            .bodyValue(Map.of("note", "x")).exchange().expectStatus().isOk().expectBody(MAP).returnResult()
            .getResponseBody();
        @SuppressWarnings("unchecked")
        Map<String, Object> output = (Map<String, Object>) body.get("output");
        assertThat(output).containsEntry("country", "US").containsEntry("mainlandChina", false);
        // No statement reads the address database; none mentions the address.
        assertThat(SqlStatementLog.STATEMENTS).noneSatisfy(sql -> assertThat(sql).contains("2001:db8:1::9"));
        Number seq = (Number) body.get("processSeqId");
        List<Map<String, Object>> process = query("SELECT input_summary::text AS summary FROM op_process "
            + "WHERE process_seq_id = ?", seq.longValue());
        assertThat(process).singleElement().satisfies(row -> assertThat(String.valueOf(row.get("summary")))
            .doesNotContain("2001:db8").doesNotContain("US"));

        // From the mainland.
        Map<String, Object> mainland = client.post().uri("/api/processes/IT_CLIENT_COUNTRY/latest")
            .contentType(MediaType.APPLICATION_JSON).header(HttpHeaders.AUTHORIZATION, bearer)
            .header(CONNECTION, "192.0.2.77").bodyValue(Map.of("note", "y")).exchange().expectStatus().isOk()
            .expectBody(MAP).returnResult().getResponseBody();
        assertThat(mainland.get("output")).asString().contains("mainlandChina=true");
    }
}
