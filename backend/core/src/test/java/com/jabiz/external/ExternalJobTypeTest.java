package com.jabiz.external;

import com.jabiz.security.Sensitive;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static java.time.Duration.ofMinutes;
import static java.time.Duration.ofSeconds;
import static org.assertj.core.api.Assertions.assertThat;

class ExternalJobTypeTest {

    record CopyInput(String productId, String language) {}

    record CopyResult(String text) {}

    record Nested(String name, @Sensitive String apiKey) {}

    record WithSecret(String productId, @Sensitive String password) {}

    record WithNestedSecret(String productId, List<Nested> parts, Map<String, Nested> byName) {}

    static final class CopyHandler implements ExternalJobHandler<CopyInput, CopyResult> {
        @Override
        public ExternalJobResult<CopyResult> run(CopyInput input, ExternalJobContext ctx) {
            return ExternalJobResult.of(new CopyResult("text"));
        }
    }

    static ExternalJobType<CopyInput, CopyResult> copy() {
        return ExternalJobType.define("commerce.product-copy", CopyInput.class, CopyResult.class, t -> t
            .handler(CopyHandler.class).services("demo-ai").timeout(ofSeconds(90))
            .retry(3, ofSeconds(10), ofMinutes(10)).maxConcurrency(2)
            .onCompleted("PRODUCT_COPY_APPLY").onFailed("PRODUCT_COPY_FAILED"));
    }

    @Test
    void aDeclarationKeepsWhatItSays() {
        ExternalJobType<CopyInput, CopyResult> type = copy();
        assertThat(type.name()).isEqualTo("commerce.product-copy");
        assertThat(type.inputType()).isEqualTo(CopyInput.class);
        assertThat(type.resultType()).isEqualTo(CopyResult.class);
        assertThat(type.handler()).isEqualTo(CopyHandler.class);
        assertThat(type.services()).containsExactly("demo-ai");
        assertThat(type.timeout()).isEqualTo(ofSeconds(90));
        assertThat(type.maxAttempts()).isEqualTo(3);
        assertThat(type.initialBackoff()).isEqualTo(ofSeconds(10));
        assertThat(type.maxBackoff()).isEqualTo(ofMinutes(10));
        assertThat(type.maxConcurrency()).isEqualTo(2);
        assertThat(type.onCompleted()).isEqualTo("PRODUCT_COPY_APPLY");
        assertThat(type.onFailed()).isEqualTo("PRODUCT_COPY_FAILED");
        assertThat(type.problems()).isEmpty();
        assertThat(type.toString()).contains("commerce.product-copy", "CopyHandler", "demo-ai");
    }

    @Test
    void defaultsApplyWhereNothingIsSaid() {
        ExternalJobType<CopyInput, CopyResult> type = ExternalJobType.define("x", CopyInput.class, CopyResult.class,
            t -> t.handler(CopyHandler.class).onCompleted("A").onFailed("B"));
        assertThat(type.timeout()).isEqualTo(ExternalJobType.DEFAULT_TIMEOUT);
        assertThat(type.maxAttempts()).isEqualTo(ExternalJobType.DEFAULT_MAX_ATTEMPTS);
        assertThat(type.initialBackoff()).isEqualTo(ExternalJobType.DEFAULT_INITIAL_BACKOFF);
        assertThat(type.maxBackoff()).isEqualTo(ExternalJobType.DEFAULT_MAX_BACKOFF);
        assertThat(type.maxConcurrency()).isEqualTo(ExternalJobType.DEFAULT_MAX_CONCURRENCY);
        assertThat(type.maxOpenPerRequester()).isEqualTo(ExternalJobType.DEFAULT_MAX_OPEN_PER_REQUESTER);
        assertThat(type.oneOpenPerReference()).isFalse();
        assertThat(type.services()).isEmpty();
        assertThat(type.problems()).isEmpty();
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void everyProblemIsReportedAtOnce() {
        ExternalJobType type = new ExternalJobType("Bad Name", String.class, Integer.class, null,
            java.util.Set.of("Not A Service"), Duration.ZERO, 0, ofSeconds(10), ofSeconds(5), 0, " ", null, 0, false);
        assertThat(type.problems()).containsExactlyInAnyOrder(
            "name: 'Bad Name' must match [a-z][a-z0-9._-]{0,99}",
            "inputType: must be a record",
            "resultType: must be a record",
            "handler: missing",
            "services: 'Not A Service' is not a service name",
            "timeout: must be positive and at most PT1H",
            "retry: attempts must be between 1 and 100",
            "retry: backoffs must be positive, the maximum at least the initial one",
            "maxConcurrency: must be between 1 and 1000",
            "maxOpenPerRequester: must be between 1 and 10000",
            "onCompleted: name the process that receives the result",
            "onFailed: name the process that hears of a failure");
        ExternalJobType tooLong = ExternalJobType.define("x", CopyInput.class, CopyResult.class, t -> t
            .handler((Class) CopyHandler.class).timeout(Duration.ofHours(2)).retry(101, ofSeconds(1), ofSeconds(1))
            .maxConcurrency(1001).onCompleted("A").onFailed("B"));
        assertThat(tooLong.problems()).containsExactlyInAnyOrder("timeout: must be positive and at most PT1H",
            "retry: attempts must be between 1 and 100", "maxConcurrency: must be between 1 and 1000");
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void inputsMayNotCarrySecretsAtAnyDepth() {
        ExternalJobType direct = ExternalJobType.define("x", WithSecret.class, CopyResult.class, t -> t
            .handler((Class) CopyHandler.class).onCompleted("A").onFailed("B"));
        assertThat(direct.problems()).singleElement().asString().startsWith("inputType: component password is");
        ExternalJobType nested = ExternalJobType.define("x", WithNestedSecret.class, CopyResult.class, t -> t
            .handler((Class) CopyHandler.class).onCompleted("A").onFailed("B"));
        List<String> problems = nested.problems();
        assertThat(problems).hasSize(2).allSatisfy(problem -> assertThat(problem).contains("apiKey"))
            .anySatisfy(problem -> assertThat(problem).contains("parts.apiKey"))
            .anySatisfy(problem -> assertThat(problem).contains("byName.apiKey"));
    }

    record SecretResult(String text, @Sensitive String token) {}

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void resultsMayNotCarrySecretsAndStartsCanBeLimited() {
        ExternalJobType secret = ExternalJobType.define("x", CopyInput.class, SecretResult.class, t -> t
            .handler((Class) CopyHandler.class).onCompleted("A").onFailed("B"));
        assertThat((List<String>) secret.problems()).singleElement().asString()
            .startsWith("resultType: component token is @Sensitive");
        ExternalJobType<CopyInput, CopyResult> limited = ExternalJobType.define("x", CopyInput.class,
            CopyResult.class, t -> t.handler(CopyHandler.class).maxOpenPerRequester(2).oneOpenPerReference()
                .onCompleted("A").onFailed("B"));
        assertThat(limited.maxOpenPerRequester()).isEqualTo(2);
        assertThat(limited.oneOpenPerReference()).isTrue();
        assertThat(limited.problems()).isEmpty();
        assertThat(limited.toString()).contains("maxOpenPerRequester=2", "oneOpenPerReference=true");
        ExternalJobType<CopyInput, CopyResult> tooMany = ExternalJobType.define("x", CopyInput.class,
            CopyResult.class, t -> t.handler(CopyHandler.class).maxOpenPerRequester(10_001)
                .onCompleted("A").onFailed("B"));
        assertThat(tooMany.problems()).containsExactly("maxOpenPerRequester: must be between 1 and 10000");
    }

    @Test
    void backoffDoublesFromTheInitialWaitUpToTheMaximum() {
        ExternalJobType<CopyInput, CopyResult> type = copy();
        assertThat(type.backoffAfter(0)).isEqualTo(Duration.ZERO);
        assertThat(type.backoffAfter(1)).isEqualTo(ofSeconds(10));
        assertThat(type.backoffAfter(2)).isEqualTo(ofSeconds(20));
        assertThat(type.backoffAfter(3)).isEqualTo(ofSeconds(40));
        assertThat(type.backoffAfter(6)).isEqualTo(ofSeconds(320));
        assertThat(type.backoffAfter(7)).isEqualTo(ofMinutes(10));
        assertThat(type.backoffAfter(Integer.MAX_VALUE)).isEqualTo(ofMinutes(10));
    }
}
