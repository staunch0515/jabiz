package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-M14 (docs/quizbuks/plans/impl/F-M14.md §5, review M4): an installation set up before the reconciliation's two
 * thresholds became controlled has them as ordinary parameters with its own values (here 5 days and 48 hours). Its next
 * {@code QB_SETUP} proposes nothing for them: the values stand, a direct change is refused (422
 * {@code PARAM_CONTROLLED}) and a controlled change makes it. A class of its own: the parameters are written as the
 * earlier setup wrote them, before any of them is published.
 */
class SetupExistingThresholdsIT extends QbItSupport {

    @Test
    void anInstallationKeepsItsValuesAndChangesThemWithFourEyes() {
        // The earlier setup ran (its records are there); its two parameters were ordinary rows with this
        // installation's values. Nothing of this run's proposals is published.
        setup();
        ordinaryRow(QbParams.PREPAYMENT_RELEASE_OVERDUE_DAYS, "5");
        ordinaryRow(QbParams.AUDIENCE_REFUND_MISSING_AFTER_HOURS, "48");

        Map<String, Object> again = setup();
        assertThat(String.valueOf(again.get("paramsProposed"))).doesNotContain(QbParams.PREPAYMENT_RELEASE_OVERDUE_DAYS)
            .doesNotContain(QbParams.AUDIENCE_REFUND_MISSING_AFTER_HOURS);
        assertThat(value(QbParams.PREPAYMENT_RELEASE_OVERDUE_DAYS)).isEqualTo("5");
        assertThat(value(QbParams.AUDIENCE_REFUND_MISSING_AFTER_HOURS)).isEqualTo("48");

        for (String key : List.of(QbParams.PREPAYMENT_RELEASE_OVERDUE_DAYS,
            QbParams.AUDIENCE_REFUND_MISSING_AFTER_HOURS)) {
            Map<String, Object> refused = client.post().uri("/api/processes/PARAM_SET/latest")
                .contentType(MediaType.APPLICATION_JSON).header(HttpHeaders.AUTHORIZATION, admin())
                .bodyValue(Map.of("key", key, "value", "7")).exchange().expectStatus().isEqualTo(422)
                .expectBody(MAP).returnResult().getResponseBody();
            assertThat(String.valueOf(refused)).as(key).contains("PARAM_CONTROLLED");
        }
        assertThat(value(QbParams.PREPAYMENT_RELEASE_OVERDUE_DAYS)).isEqualTo("5");

        // With four eyes it changes.
        @SuppressWarnings("unchecked")
        Map<String, Object> proposed = (Map<String, Object>) client.post()
            .uri("/api/processes/CONTROL_CHANGE_PROPOSE/latest").contentType(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.AUTHORIZATION, TestTokens.bearer(tokens, "fm14-proposer", "control.propose"))
            .bodyValue(Map.of("targetEntity", "SysParam", "values", Map.of("paramKey",
                QbParams.PREPAYMENT_RELEASE_OVERDUE_DAYS, "value", "7"), "reason", "F-M14 test"))
            .exchange().expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody().get("output");
        client.post().uri("/api/processes/CONTROL_CHANGE_PUBLISH/latest").contentType(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.AUTHORIZATION, TestTokens.bearer(tokens, "fm14-publisher", "control.publish"))
            .bodyValue(Map.of("changeId", proposed.get("changeId"))).exchange().expectStatus().isOk();
        assertThat(value(QbParams.PREPAYMENT_RELEASE_OVERDUE_DAYS)).isEqualTo("7");
    }

    /** A parameter's row as an earlier QB_SETUP created it (an ordinary one), with this value. */
    void ordinaryRow(String key, String value) {
        QbParams.Param param = QbParams.ALL.stream().filter(p -> p.key().equals(key)).findFirst().orElseThrow();
        String id = UUID.randomUUID().toString();
        // Made by the same operation as another ordinary parameter of the setup.
        Map<String, Object> other = query("SELECT process_seq_id, effect_start_time, created_time FROM sys_param_version "
            + "WHERE param_key = ? ORDER BY version_no LIMIT 1", QbParams.VERIFICATION_LINK_HOURS).getFirst();
        execute("INSERT INTO entity_registry (entity_id, entity_type, created_seq_id) VALUES (?::uuid, 'SysParam', ?)",
            id, other.get("process_seq_id"));
        execute("INSERT INTO sys_param_version (param_id, version_no, effect_start_time, created_time, process_seq_id, "
            + "param_key, value_kind, param_value, description) VALUES (?::uuid, 1, ?, ?, ?, ?, ?::jsonb, ?, ?)", id,
            other.get("effect_start_time"), other.get("created_time"), other.get("process_seq_id"), key,
            json(param.valueKind()), value, param.description());
    }

    static String value(String key) {
        List<Map<String, Object>> rows = query("SELECT param_value FROM sys_param_version WHERE param_key = ? "
            + "ORDER BY effect_start_time DESC, version_no DESC LIMIT 1", key);
        return rows.isEmpty() ? null : String.valueOf(rows.getFirst().get("param_value"));
    }

    static String json(Map<String, Object> kind) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(kind);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
