package com.jabiz.security;

import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.process.NoMetadata;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The values of sign-in guards and of verified e-mail addresses (decision D36). */
class SignInGuardTypesTest {

    @Test
    void anAddressIsVerifiedWhenTheProvenOneIsTheCurrentOneRegardlessOfCase() {
        assertThat(EmailVerification.verified("Ann@Example.com", "ann@example.com")).isTrue();
        assertThat(EmailVerification.verified("ann@example.com", "ann@example.com")).isTrue();
        // An administrator changed the address: the old proof does not count.
        assertThat(EmailVerification.verified("ann@other.com", "ann@example.com")).isFalse();
        assertThat(EmailVerification.verified(null, "ann@example.com")).isFalse();
        assertThat(EmailVerification.verified(" ", " ")).isFalse();
        assertThat(EmailVerification.verified("ann@example.com", null)).isFalse();
        assertThat(EmailVerification.normalize("A@X.COM")).isEqualTo("a@x.com");
        assertThat(EmailVerification.normalize(null)).isNull();
    }

    @Test
    void loadsAreValidated() {
        SignInLoad load = SignInLoad.of("customers", "urn:jabiz:dataset:default:Customer", "userId");
        assertThat(load.name()).isEqualTo("customers");
        assertThatThrownBy(() -> SignInLoad.of("1x", "d", "f")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SignInLoad.of(null, "d", "f")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> SignInLoad.of("x", " ", "f")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SignInLoad.of("x", "d", null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void attemptsCopyTheirData() {
        Map<String, Object> row = new HashMap<>();
        row.put("blocked", null);
        Map<String, List<Map<String, Object>>> data = new HashMap<>();
        data.put("customers", List.of(row));
        data.put("none", null);
        SignInAttempt attempt = new SignInAttempt("u", "ann", "portal", Set.of("CUSTOMER"), true,
            SignInAttempt.Factor.PASSWORD, Instant.EPOCH, data);
        data.clear();

        assertThat(attempt.rows("customers")).hasSize(1);
        assertThat(attempt.rows("none")).isEmpty();
        assertThat(attempt.rows("missing")).isEmpty();
        assertThat(new SignInAttempt("u", null, "admin", null, false, SignInAttempt.Factor.REFRESH, Instant.EPOCH,
            null).roles()).isEmpty();
        assertThatThrownBy(() -> new SignInAttempt("u", null, null, null, false, SignInAttempt.Factor.REFRESH,
            Instant.EPOCH, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void decisionsAndDefaults() {
        assertThat(SignInDecision.ALLOW.allowed()).isTrue();
        assertThat(SignInDecision.refuse("blocked")).isEqualTo(new SignInDecision(false, "blocked"));
        assertThat(SignInDecision.refuse(null).reason()).isEqualTo("refused");
        SignInGuard guard = attempt -> SignInDecision.ALLOW;
        assertThat(guard.loads()).isEmpty();
    }

    @Test
    void processesAndDatasetsDeclareTheRequirement() {
        ProcessDefinition<String, String, ProcessContext> plain = ProcessDefinition.single("P", 1, String.class,
            String.class, (in, ctx) -> in);
        assertThat(plain.verifiedEmail()).isFalse();
        assertThat(plain.withVerifiedEmail(true).verifiedEmail()).isTrue();
        assertThat(plain.withVerifiedEmail(true).withPermissions("p").verifiedEmail()).isTrue();
        ProcessDefinition<String, String, ProcessContext> declared = ProcessDefinition.define("Q", 1, String.class,
            String.class, ProcessContext.class, pb -> pb.requiresVerifiedEmail()
                .compute("noop", (NoMetadata m, ProcessContext ctx) -> { })
                .outputMapper(ctx -> "")
                .contextFactory((start, in) -> new ProcessContext(start)));
        assertThat(declared.verifiedEmail()).isTrue();

        DatasetDefinition dataset = DatasetDefinition.define("urn:jabiz:dataset:test:X", d -> d
            .targetEntityType("X").asDefault().permissions("r", "w")
            .policy(p -> p.requiresVerifiedEmail())
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef("default")));
        assertThat(dataset.policy().writeVerifiedEmail()).isTrue();
    }
}
