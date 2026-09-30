package com.jabiz.app.it.security;

import com.jabiz.app.PriceAdjustment;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A deployment that turned {@code jabiz.security.mfa.administration} off: administration needs no second factor, but
 * operations that always require one still do (docs/design/10-security.md section 10).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = "jabiz.security.mfa.administration=false")
class MfaAdministrationOffIT extends SecurityItSupport {

    @Test
    void administrationRunsWithoutButAlwaysMeansAlways() {
        String noMfa = TestTokens.withoutMfa(tokens, "it-admin", "*");
        post("/api/processes/SEC_USER_CREATE/latest", noMfa,
            Map.of("userName", unique("x"), "password", "correct horse battery")).expectStatus().isOk();

        List<Map<String, Object>> catalog = get("/api/meta/processes", noMfa).expectStatus().isOk().expectBody(LIST)
            .returnResult().getResponseBody();
        assertThat(catalog).filteredOn(p -> "SEC_USER_CREATE".equals(p.get("name"))).singleElement()
            .satisfies(p -> assertThat(p).containsEntry("requiresMfa", false));
        assertThat(catalog).filteredOn(p -> PriceAdjustment.NAME.equals(p.get("name"))).singleElement()
            .satisfies(p -> assertThat(p).containsEntry("requiresMfa", true));

        assertThat(ruleCode(post("/api/processes/" + PriceAdjustment.NAME + "/latest", noMfa,
            Map.of("priceId", "00000000-0000-0000-0000-000000000000", "percent", "5"))
            .expectStatus().isForbidden().expectBody(MAP).returnResult().getResponseBody())).isEqualTo("MFA_REQUIRED");
    }
}
