package com.jabiz.external;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExternalHttpTest {

    @Test
    void requestsAreBuiltStepByStep() {
        ExternalHttp.Request request = ExternalHttp.Request.post("/v1/moderate")
            .header("Authorization", "Bearer s3cr3t-value")
            .json("{\"text\":\"hello\"}")
            .timeout(Duration.ofSeconds(3))
            .idempotencyKey("jabiz-12-a");
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.target()).isEqualTo("/v1/moderate");
        assertThat(request.headers()).containsEntry("Authorization", "Bearer s3cr3t-value")
            .containsEntry("Content-Type", "application/json");
        assertThat(new String(request.body(), StandardCharsets.UTF_8)).isEqualTo("{\"text\":\"hello\"}");
        assertThat(request.timeout()).isEqualTo(Duration.ofSeconds(3));
        assertThat(request.idempotencyKey()).isEqualTo("jabiz-12-a");
        assertThat(ExternalHttp.Request.get("/x").method()).isEqualTo("GET");
        assertThat(ExternalHttp.Request.of("put", "/x").method()).isEqualTo("PUT");
    }

    @Test
    void neitherHeaderValuesNorTheBodyArePrinted() {
        ExternalHttp.Request request = ExternalHttp.Request.post("/v1/moderate?token=abc")
            .header("Authorization", "Bearer s3cr3t-value").json("{\"password\":\"hunter2\"}");
        assertThat(request.toString()).doesNotContain("s3cr3t", "hunter2", "token=abc")
            .contains("POST /v1/…", "Authorization", "body=22 bytes").doesNotContain("moderate");
        ExternalHttp.Response response = new ExternalHttp.Response(200,
            Map.of("Set-Cookie", List.of("session=s3cr3t")), "{\"key\":\"s3cr3t\"}".getBytes(StandardCharsets.UTF_8));
        assertThat(response.toString()).doesNotContain("s3cr3t").contains("200");
    }

    @Test
    void requestsRefuseWhatIsNotHttp() {
        assertThatThrownBy(() -> ExternalHttp.Request.of("GE T", "/x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ExternalHttp.Request.get("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ExternalHttp.Request.get("/x").header("Bad Name", "v"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ExternalHttp.Request.get("/x").header("X-A", "v\r\nX-B: w"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ExternalHttp.Request.get("/x").timeout(Duration.ZERO))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ExternalHttp.Request.get("/x").idempotencyKey("with space"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void targetsArePrintedToTheirFirstSegmentOnly() {
        assertThat(ExternalHttp.Request.masked("/v1/users/42/cards?pan=4111")).isEqualTo("/v1/…");
        assertThat(ExternalHttp.Request.masked("moderate?token=x")).isEqualTo("moderate?…");
        assertThat(ExternalHttp.Request.masked("https://user:pw@api.example.com:8443/v1/secret/path"))
            .isEqualTo("https://api.example.com:8443/v1/…");
        assertThat(ExternalHttp.Request.masked("//evil.example.com")).isEqualTo("//evil.example.com");
        assertThat(ExternalHttp.Request.masked("/")).isEqualTo("/");
    }

    @Test
    void headerValuesTheClientWouldRefuseAreRefusedWithoutBeingRepeated() {
        for (String value : List.of("名前", "a\u0001b", "caf\u00e9", "x\u007f")) {
            assertThatThrownBy(() -> ExternalHttp.Request.get("/x").header("X-Name", value))
                .isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining(value)
                .hasMessageContaining("X-Name");
        }
        assertThat(ExternalHttp.Request.get("/x").header("X-Name", "a b\tc~").headers())
            .containsEntry("X-Name", "a b\tc~");
    }

    @Test
    void headersTheClientSetsCannotBeSet() {
        for (String name : List.of("Host", "connection", "Content-Length", "Expect", "Upgrade", "Transfer-Encoding",
            "TE", "Trailer", "Keep-Alive", "Proxy-Authorization")) {
            assertThatThrownBy(() -> ExternalHttp.Request.get("/x").header(name, "v")).as(name)
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void headerNamesIgnoreCase() {
        ExternalHttp.Request request = ExternalHttp.Request.post("/x").header("content-type", "text/plain")
            .json("{}").header("X-A", "1").header("x-a", "2");
        assertThat(request.headers()).containsExactly(Map.entry("Content-Type", "application/json"),
            Map.entry("x-a", "2"));
    }

    @Test
    void anIllegalCharsetIsReadAsUtf8() {
        ExternalHttp.Response response = new ExternalHttp.Response(200,
            Map.of("Content-Type", List.of("text/plain; charset=bad:name")), "日本".getBytes(StandardCharsets.UTF_8));
        assertThat(response.text()).isEqualTo("日本");
        assertThat(new ExternalHttp.Response(200, Map.of("Content-Type", List.of("text/plain; charset=x-unknown-cs")),
            "ok".getBytes(StandardCharsets.UTF_8)).text()).isEqualTo("ok");
    }

    @Test
    void responsesReadAsTextAndHeaders() {
        ExternalHttp.Response response = new ExternalHttp.Response(201,
            Map.of("Content-Type", List.of("application/json; charset=utf-8")),
            "{\"ok\":\"日本\"}".getBytes(StandardCharsets.UTF_8));
        assertThat(response.isSuccess()).isTrue();
        assertThat(response.text()).isEqualTo("{\"ok\":\"日本\"}");
        assertThat(response.header("content-type")).contains("application/json; charset=utf-8");
        assertThat(response.header("missing")).isEmpty();
        assertThat(new ExternalHttp.Response(302, Map.of(), new byte[0]).isSuccess()).isFalse();
        byte[] body = {1, 2};
        ExternalHttp.Response copy = new ExternalHttp.Response(200, Map.of(), body);
        body[0] = 9;
        assertThat(copy.body()[0]).isEqualTo((byte) 1);
    }

    @Test
    void failuresNameTheServiceAndTheKindOnly() {
        ExternalCallException failure = new ExternalCallException("demo-ai", ExternalCallException.Kind.TIMEOUT,
            "no answer within PT30S");
        assertThat(failure.service()).isEqualTo("demo-ai");
        assertThat(failure.kind()).isEqualTo(ExternalCallException.Kind.TIMEOUT);
        assertThat(failure.getMessage()).isEqualTo("External service demo-ai: no answer within PT30S");
        assertThat(ExternalCallException.badResponse("demo-ai", 503).getMessage())
            .isEqualTo("External service demo-ai: answered 503");
    }
}
