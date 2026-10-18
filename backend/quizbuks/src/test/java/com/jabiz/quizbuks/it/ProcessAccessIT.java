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
 * items of the jobs) run as the system only, the release of a removed quiz's files only
 * as a sub-process of its removal. No caller starts them through the process API, whatever their permissions.
 */
class ProcessAccessIT extends QbItSupport {

    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST =
        new ParameterizedTypeReference<>() {};

    /** The QuizBuks processes not open to callers, by kind; every other QuizBuks process is open. */
    private static final Map<String, ProcessAccess> CLOSED = Map.ofEntries(
        Map.entry("QB_SPONSOR_APPROVAL_RESULT", ProcessAccess.INTERNAL),
        Map.entry("QB_PUBLICATION_APPROVAL_RESULT", ProcessAccess.INTERNAL),
        Map.entry("QB_PUBLICATION_BAN_PAUSE", ProcessAccess.SUB_PROCESS_ONLY),
        Map.entry("QB_PUBLICATION_COMPLETION_MOVE", ProcessAccess.SUB_PROCESS_ONLY),
        Map.entry("QB_PUBLICATION_ADVANCE", ProcessAccess.INTERNAL),
        Map.entry("QB_QUIZ_RELEASE_FILES", ProcessAccess.SUB_PROCESS_ONLY),
        // A part of a journal export's detail (Q9d review H1): only the export runs it, in its transaction.
        Map.entry("QB_JOURNAL_DETAIL_PART", ProcessAccess.SUB_PROCESS_ONLY),
        // Answering (Q5): the expiry of an attempt (the items of a job), the settlement of pending rewards (an
        // event; Q6's top-up confirmation as the system), underfunded publications back on (an event), the
        // statistics (a job).
        Map.entry("QB_ATTEMPT_EXPIRE", ProcessAccess.INTERNAL),
        Map.entry("QB_PENDING_SETTLE", ProcessAccess.INTERNAL),
        Map.entry("QB_PENDING_PAY", ProcessAccess.INTERNAL),
        Map.entry("QB_PUBLICATION_FUNDS_RESTORED", ProcessAccess.INTERNAL),
        Map.entry("QB_PUBLICATION_FUNDED", ProcessAccess.INTERNAL),
        Map.entry("QB_PUBLICATION_STATS_REFRESH", ProcessAccess.INTERNAL),
        Map.entry("QB_PUBLICATION_STATS_CHUNK", ProcessAccess.INTERNAL),
        // The sponsors' analytics (Q9e): the items of the job (review M3: one transaction each).
        Map.entry("QB_ANALYTICS_ITEM", ProcessAccess.INTERNAL),
        // Top-ups and reconciliation (Q6a): the results of the jobs that call Stripe, Stripe's events, the items of
        // the stale top-ups' job and the daily reconciliation.
        Map.entry("QB_TOPUP_OPENED", ProcessAccess.INTERNAL),
        Map.entry("QB_TOPUP_OPEN_FAILED", ProcessAccess.INTERNAL),
        Map.entry("QB_TOPUP_CONFIRM", ProcessAccess.INTERNAL),
        Map.entry("QB_TOPUP_EXPIRE", ProcessAccess.INTERNAL),
        Map.entry("QB_TOPUP_CHECK", ProcessAccess.INTERNAL),
        Map.entry("QB_TOPUP_CHECKED", ProcessAccess.INTERNAL),
        Map.entry("QB_TOPUP_CHECK_FAILED", ProcessAccess.INTERNAL),
        Map.entry("QB_TOPUP_FEE_RECORD", ProcessAccess.INTERNAL),
        Map.entry("QB_TOPUP_FEE_FAILED", ProcessAccess.INTERNAL),
        Map.entry("QB_TOPUP_REFUNDED", ProcessAccess.INTERNAL),
        Map.entry("QB_TOPUP_DISPUTE_OPENED", ProcessAccess.INTERNAL),
        Map.entry("QB_TOPUP_DISPUTE_CLOSED", ProcessAccess.INTERNAL),
        Map.entry("QB_RECONCILE_RUN", ProcessAccess.INTERNAL),
        Map.entry("QB_PAYOUT_ONBOARD_READY", ProcessAccess.INTERNAL),
        Map.entry("QB_PAYOUT_ONBOARD_FAILED", ProcessAccess.INTERNAL),
        Map.entry("QB_PAYOUT_ACCOUNT_UPDATED", ProcessAccess.INTERNAL),
        Map.entry("QB_PAYOUT_ACCOUNT_DEAUTHORIZED", ProcessAccess.INTERNAL),
        Map.entry("QB_TAKER_WALLET_SYNC", ProcessAccess.INTERNAL),
        Map.entry("QB_TAKER_WALLET_RESYNC", ProcessAccess.INTERNAL),
        Map.entry("QB_TRANSFER_CREATE", ProcessAccess.INTERNAL),
        Map.entry("QB_TRANSFER_APPROVAL_RESULT", ProcessAccess.INTERNAL),
        Map.entry("QB_TRANSFER_SENT", ProcessAccess.INTERNAL),
        Map.entry("QB_TRANSFER_SEND_FAILED", ProcessAccess.INTERNAL),
        Map.entry("QB_TRANSFER_CHECK", ProcessAccess.INTERNAL),
        Map.entry("QB_TRANSFER_CHECKED", ProcessAccess.INTERNAL),
        Map.entry("QB_TRANSFER_CHECK_FAILED", ProcessAccess.INTERNAL),
        Map.entry("QB_TRANSFER_REVERSED", ProcessAccess.INTERNAL),
        Map.entry("QB_STRIPE_PAYOUT_RECORD", ProcessAccess.INTERNAL),
        Map.entry("QB_STRIPE_PAYOUT_FAILED", ProcessAccess.INTERNAL));

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
                    QbPermissions.CONTENT_SYSTEM, QbPermissions.PUBLICATION_SYSTEM, QbPermissions.WALLET_SYSTEM,
                    QbPermissions.PLAY_SYSTEM)).toArray(String[]::new)),
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
            .contains("QB_SETUP", "QB_QUIZ_DELETE", "QB_PUBLICATION_SUBMIT", "QB_TOPUP_START")
            .doesNotContainAnyElementsOf(CLOSED.keySet());
    }

    private String tokenOf(String userId, String roleCode) {
        return TestTokens.bearer(tokens, userId, QbRoles.of(roleCode).permissions().toArray(String[]::new));
    }
}
