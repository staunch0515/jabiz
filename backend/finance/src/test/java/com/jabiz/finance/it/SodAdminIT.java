package com.jabiz.finance.it;

import com.jabiz.finance.setup.FinanceRoles;
import com.jabiz.finance.setup.SetupProcesses;
import com.jabiz.runtime.security.SecurityEntities;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FIN-CT-001 (ROADMAP F10a): a user who keeps users and roles and also prepares the books' documents, made before
 * {@code FIN-SOD-ADMIN-POST} is published, is named by the conflict report once it is (prevention refuses such a
 * grant afterwards: ControlsIT).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class SodAdminIT extends FinanceItSupport {

    @Test
    void theConflictReportNamesAnAdministratorWhoPosts() {
        Map<String, String> held = openBooksHolding(SetupProcesses.SOD_ADMIN_POST);
        String admin = as("admin", "*");
        String userId = (String) ok("SEC_USER_CREATE", admin, Map.of("userName", "ct-dual", "displayName", "ct-dual",
            "password", "password-123")).get("userId");
        for (String role : List.of(FinanceRoles.SYSTEM_ADMINISTRATOR, FinanceRoles.ACCOUNTANT)) {
            post("/api/datasets/" + SecurityEntities.USER_ROLE_DATASET + "/commit", admin, Map.of("changes",
                List.of(Map.of("action", "INSERT", "attributes", Map.of("userId", userId, "roleId",
                    find(SecurityEntities.ROLE_DATASET, "roleCode", role).getFirst().get("roleId"))))))
                .expectStatus().isOk();
        }
        ok("CONTROL_CHANGE_PUBLISH", as("controller-2", "control.publish"),
            Map.of("changeId", held.get(SetupProcesses.SOD_ADMIN_POST)));
        List<Map<String, Object>> conflicts = get("/api/sod/conflicts", as("reviewer", "sod.read")).expectStatus()
            .isOk().expectBody(LIST).returnResult().getResponseBody();
        assertThat(conflicts).filteredOn(r -> "ct-dual".equals(r.get("userName"))).extracting(r -> r.get("ruleCode"))
            .containsExactly(SetupProcesses.SOD_ADMIN_POST);
    }
}
