package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.setup.Brand;
import com.jabiz.quizbuks.setup.QbRoles;
import com.jabiz.runtime.mail.MailLayoutEntities;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The letterhead of every mail (F-C9, platform decision D72 item 6) as QuizBuks gives it: the default is the brand's
 * (name in each language, support address, site), a letterhead of its own is proposed by a content administrator and
 * published by a finance administrator and by nobody else, and a return to the default takes effect at once. The
 * platform's {@code MailLayoutIT} covers the mechanics; this checks that QuizBuks's roles and defaults fit them.
 */
class MailLayoutIT extends AccountItSupport {

    private static final String LAYOUTS = "/api/mail/layouts";

    private String content() {
        return tokenOf("fc9-content-admin", QbRoles.ADMIN_CONTENT);
    }

    private String finance() {
        return tokenOf("fc9-finance-admin", QbRoles.ADMIN_FINANCE);
    }

    private String superAdmin() {
        return tokenOf("fc9-super-admin", QbRoles.ADMIN_SUPER);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> language(String locale) {
        var result = client.get().uri(LAYOUTS).header(HttpHeaders.AUTHORIZATION, content()).exchange()
            .expectBody(MAP).returnResult();
        assertThat(result.getStatus().value()).as(String.valueOf(result.getResponseBody())).isEqualTo(200);
        return ((List<Map<String, Object>>) result.getResponseBody().get("languages")).stream()
            .filter(found -> locale.equals(found.get("locale"))).findFirst().orElseThrow();
    }

    private Map<String, Object> proposal(String displayName, String footer) {
        return body("targetEntity", MailLayoutEntities.ENTITY, "reason", "F-C9 letterhead", "values",
            Map.of("locale", "en", "displayName", displayName, "logoUrl", "", "logoAlt", "", "footer", footer));
    }

    @Test
    void theDefaultLetterheadIsTheBrandsInEveryLanguage() {
        for (String locale : List.of("en", "ja", "zh")) {
            Map<String, Object> found = language(locale);
            assertThat(found).containsEntry("state", "DEFAULT");
            @SuppressWarnings("unchecked")
            Map<String, Object> defaults = (Map<String, Object>) found.get("defaultLayout");
            assertThat((String) defaults.get("displayName")).isNotBlank();
            assertThat((String) defaults.get("footer")).contains(Brand.current().supportEmail());
            assertThat((String) defaults.get("logoUrl")).isEmpty();
        }
        var preview = client.post().uri(LAYOUTS + "/preview").header(HttpHeaders.AUTHORIZATION, content())
            .bodyValue(body("locale", "zh")).exchange().expectBody(MAP).returnResult();
        assertThat(preview.getStatus().value()).isEqualTo(200);
        assertThat((String) preview.getResponseBody().get("html")).contains(Brand.current().supportEmail());
    }

    @Test
    void aContentAdministratorProposesAFinanceAdministratorPublishesAndNobodyElseDoes() {
        String footer = "Sent by " + Brand.current().name() + " · [" + Brand.current().supportEmail() + "](mailto:"
            + Brand.current().supportEmail() + ")";
        // The super administrator holds neither side: the platform asks mail.text.write of whoever proposes.
        call("CONTROL_CHANGE_PROPOSE", superAdmin(), proposal("Letterhead", footer)).expectStatus().isForbidden();
        String changeId = (String) run("CONTROL_CHANGE_PROPOSE", content(), proposal("Letterhead", footer))
            .get("changeId");
        // Publishing needs control.publish, which content administrators and the super administrator lack.
        call("CONTROL_CHANGE_PUBLISH", content(), body("changeId", changeId)).expectStatus().isForbidden();
        call("CONTROL_CHANGE_PUBLISH", superAdmin(), body("changeId", changeId)).expectStatus().isForbidden();
        assertThat(language("en")).containsEntry("state", "DEFAULT");

        assertThat(run("CONTROL_CHANGE_PUBLISH", finance(), body("changeId", changeId)))
            .containsEntry("status", "PUBLISHED");
        Map<String, Object> custom = language("en");
        assertThat(custom).containsEntry("state", "CUSTOM");
        assertThat(custom.get("layout").toString()).contains("Letterhead");

        // Back to the default at once, by the content administrator alone.
        run("MAIL_LAYOUT_RESET", content(), body("locale", "en", "baseVersionNo", custom.get("versionNo")));
        assertThat(language("en")).containsEntry("state", "DEFAULT");
    }
}
