package com.jabiz.app.it.security;

import com.jabiz.context.RequestContext;
import com.jabiz.entity.Violation;
import com.jabiz.runtime.BusinessRuleViolationException;
import com.jabiz.runtime.erasure.AccountErasure;
import com.jabiz.runtime.erasure.ErasurePurge;
import com.jabiz.runtime.process.ProcessExecutor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Without a maintenance connection (decision D62 item 3) the account is erased all the same, but its purge refuses
 * and waits: nothing overwrites append-only rows through the application's own login.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class ErasureWithoutMaintenanceIT extends SecurityItSupport {

    @Autowired
    ProcessExecutor executor;

    @Test
    void thePurgeRefusesWithoutAMaintenanceConnection() {
        Map<String, Object> input = new HashMap<>();
        String name = unique("nomaint");
        input.put("userName", name);
        input.put("password", "nomaint-password-1");
        input.put("email", name + "@example.com");
        @SuppressWarnings("unchecked")
        String userId = (String) ((Map<String, Object>) post("/api/processes/SEC_USER_CREATE/latest", admin(), input)
            .expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody().get("output")).get("userId");
        RequestContext system = RequestContext.system(Locale.ENGLISH, "it-no-maintenance");
        AccountErasure.EraseOutput erased = asRequest(system, executor.execute(AccountErasure.ERASE,
            new AccountErasure.EraseInput(UUID.fromString(userId), null))).block();
        assertThat(query("SELECT requested_by FROM sys_erasure WHERE erasure_id = ?::uuid", erased.erasureId()))
            .extracting(row -> row.get("requested_by")).containsExactly(RequestContext.SYSTEM_ACTOR);

        assertThatThrownBy(() -> asRequest(system, executor.execute(ErasurePurge.PURGE,
                new ErasurePurge.PurgeInput(UUID.fromString(erased.erasureId())))).block())
            .isInstanceOfSatisfying(BusinessRuleViolationException.class, e -> assertThat(e.violations())
                .extracting(Violation::ruleCode).containsExactly("ERASURE_PURGE_UNAVAILABLE"));
        assertThat(query("SELECT * FROM sys_erasure_purge WHERE erasure_id = ?::uuid", erased.erasureId())).isEmpty();
        // The history still holds the address until a purge can run.
        assertThat(query("SELECT count(*) AS n FROM sec_user_version WHERE user_id = ?::uuid AND email IS NOT NULL",
            userId).getFirst().get("n")).isEqualTo(1L);
    }
}
