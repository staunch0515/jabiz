package com.jabiz.culture.it;

import com.jabiz.culture.CultureSetup;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.jabiz.culture.Culture.*;
import static org.assertj.core.api.Assertions.assertThat;

/** {@code CULTURE_SETUP} (docs/culture/00-design.md section 6.6) and what it gives editors and correspondents. */
class CultureSetupIT extends CultureItSupport {

    private static final ParameterizedTypeReference<List<Map<String, Object>>> MENUS =
        new ParameterizedTypeReference<>() {};

    @Test
    void setupCreatesRolesMenusAndSwitchesOnceOnly() {
        // setUpCulture ran it once already; a second run adds nothing.
        assertThat(ok(CultureSetup.SETUP, admin(), Map.of())).containsEntry("roles", 0).containsEntry("permissions", 0)
            .containsEntry("menus", 0).containsEntry("params", 0);

        assertThat(query("SELECT DISTINCT role_code FROM sec_role_version WHERE role_code IN ('CURATOR', 'CORRESPONDENT')"))
            .hasSize(2);
        List<Map<String, Object>> correspondent = query("SELECT DISTINCT p.permission FROM sec_role_permission_version p "
            + "JOIN sec_role_version r ON r.role_id = p.role_id WHERE r.role_code = 'CORRESPONDENT'");
        assertThat(correspondent).extracting(row -> row.get("permission"))
            .containsExactlyInAnyOrderElementsOf(CultureSetup.CORRESPONDENT_PERMISSIONS);
        assertThat(query("SELECT DISTINCT param_key FROM sys_param_version WHERE param_key IN (?, ?)",
            REVIEW_REQUIRED, GUARDIAN_REQUIRED)).hasSize(2);
        // One version per row: nothing was written twice.
        assertThat(query("SELECT menu_code FROM sec_menu_version WHERE menu_code LIKE 'culture.%' "
            + "GROUP BY menu_code HAVING count(*) > 1")).isEmpty();
    }

    @Test
    void setupNeedsTheAdministratorsPermissions() {
        run(CultureSetup.SETUP, curator(), Map.of()).expectStatus().isForbidden();
    }

    @Test
    void editorsAndCorrespondentsSeeTheirOwnMenus() {
        assertThat(menuCodes(curator())).contains("culture.content.stories", "culture.people.consents",
            "culture.settings.switches").doesNotContain("culture.mine.drafts");
        assertThat(menuCodes(correspondent("cu-jp-01"))).contains("culture.mine.profile", "culture.mine.drafts",
            "culture.mine.contributions", "culture.mine.media").doesNotContain("culture.content.stories",
            "culture.people.consents");
    }

    private List<String> menuCodes(String bearer) {
        List<Map<String, Object>> tree = get("/api/auth/menus", bearer).expectStatus().isOk().expectBody(MENUS)
            .returnResult().getResponseBody();
        List<String> codes = new ArrayList<>();
        collect(tree, codes);
        return codes;
    }

    @SuppressWarnings("unchecked")
    private static void collect(List<Map<String, Object>> items, List<String> codes) {
        for (Map<String, Object> item : items) {
            codes.add((String) item.get("code"));
            collect((List<Map<String, Object>>) item.get("children"), codes);
        }
    }
}
