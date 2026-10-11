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
            .contains("POST", "/v1/moderate", "Authorization", "body=22 bytes");
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
