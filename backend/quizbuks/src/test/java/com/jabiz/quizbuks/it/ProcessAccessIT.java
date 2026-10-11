package com.jabiz.quizbuks.it;

import com.jabiz.process.ProcessAccess;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.setup.QbRoles;
import com.jabiz.runtime.process.ProcessRegistry;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Who may start the QuizBuks processes (platform decision D41): the system's hand-off points (approval results, the
 * publication tick and its per-publication step) run as the system only, the release of a removed quiz's files only
 * as a sub-process of its removal. No caller starts them through the process API, whatever their permissions.
 */
class ProcessAccessIT extends QbItSupport {

    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST =
        new ParameterizedTypeReference<>() {};

    /** The QuizBuks processes not open to callers, by kind; every other QuizBuks process is open. */
    private static final Map<String, ProcessAccess> CLOSED = Map.of(
        "QB_SPONSOR_APPROVAL_RESULT", ProcessAccess.INTERNAL,
        "QB_PUBLICATION_APPROVAL_RESULT", ProcessAccess.INTERNAL,
        "QB_PUBLICATION_TICK", ProcessAccess.INTERNAL,
        "QB_PUBLICATION_ADVANCE", ProcessAccess.INTERNAL,
        "QB_QUIZ_RELEASE_FILES", ProcessAccess.SUB_PROCESS_ONLY);

    @Autowired
    ProcessRegistry registry;

    @Test
    void theClosedProcessesAreDeclaredSo() {
        Map<String, ProcessAccess> declared = registry.all().stream()
            .filter(definition -> definition.name().startsWith("QB_"))
            .filter(definition -> definition.access() != ProcessAccess.OPEN)
            .collect(Collectors.toMap(ProcessDefinition::name, ProcessDefinition::access));
        assertThat(declared).isEqualTo(CLOSED);
    }

    @Test
    void noCallerStartsThemThroughTheProcessApi() {
        List<String> callers = List.of(
            tokenOf("taker", QbRoles.TAKER),
            tokenOf("sponsor", QbRoles.SPONSOR),
            tokenOf("content-admin", QbRoles.ADMIN_CONTENT),
            // Holding the processes' own permissions, which no role grants, does not open them either.
            TestTokens.bearer(tokens, "sponsor-with-system", Stream.concat(
                QbRoles.of(QbRoles.SPONSOR).permissions().stream(), Stream.of(QbPermissions.SPONSOR_SYSTEM,
                    QbPermissions.CONTENT_SYSTEM, QbPermissions.PUBLICATION_SYSTEM)).toArray(String[]::new)),
            admin());
        for (String process : CLOSED.keySet()) {
            for (String caller : callers) {
                var result = client.post().uri("/api/processes/" + process + "/latest")
                    .contentType(MediaType.APPLICATION_JSON).header(HttpHeaders.AUTHORIZATION, caller)
                    .bodyValue(Map.of()).exchange().expectBody(MAP).returnResult();
                assertThat(result.getStatus().value()).as(process + " answered " + result.getResponseBody())
                    .isEqualTo(403);
                assertThat(String.valueOf(result.getResponseBody().get("violations"))).as(process)
                    .contains("PROCESS_INTERNAL");
            }
        }
        assertThat(query("SELECT 1 FROM op_process WHERE process_name LIKE 'QB\\_%'")).isEmpty();
    }

    @Test
    void theyAreNotInTheCatalog() {
        List<Map<String, Object>> catalog = client.get().uri("/api/meta/processes")
            .header(HttpHeaders.AUTHORIZATION, admin()).exchange().expectStatus().isOk().expectBody(LIST)
            .returnResult().getResponseBody();
        assertThat(catalog).extracting(entry -> entry.get("name"))
            .contains("QB_SETUP", "QB_QUIZ_DELETE", "QB_PUBLICATION_SUBMIT")
            .doesNotContainAnyElementsOf(CLOSED.keySet());
    }

    private String tokenOf(String userId, String roleCode) {
        return TestTokens.bearer(tokens, userId, QbRoles.of(roleCode).permissions().toArray(String[]::new));
    }
}
