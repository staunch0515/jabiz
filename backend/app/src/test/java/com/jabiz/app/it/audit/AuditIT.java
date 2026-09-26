package com.jabiz.app.it.audit;

import com.jabiz.context.RequestContext;
import com.jabiz.ledger.Direction;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.runtime.DatasetEntityManager;
import com.jabiz.runtime.EntityAction;
import com.jabiz.runtime.EntityChange;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.dataset.DatasetRegistry;
import com.jabiz.runtime.ledger.LedgerEntities;
import com.jabiz.runtime.ledger.LedgerProcesses;
import com.jabiz.runtime.process.ProcessExecutor;
import com.jabiz.runtime.process.ProcessRegistry;
import com.jabiz.runtime.security.JwtService;
import com.jabiz.runtime.test.PostgresIntegrationTest;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The audit view (docs/design/11-ledger-events-jobs.md section 3, ROADMAP phase 9 item 4): operations by actor, by
 * time and by entity, newest first, paged; it needs {@code audit.read}. Every test uses actors of its own.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class AuditIT extends PostgresIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {};

    @Autowired
    ProcessExecutor executor;

    @Autowired
    ProcessRegistry processes;

    @Autowired
    DatasetEntityManager entities;

    @Autowired
    DatasetRegistry datasets;

    @Autowired
    ApplicationContext context;

    @Autowired
    JwtService tokens;

    @Test
    void operationsAreFoundByActorTimeProcessAndEntity() {
        String alice = actor();
        String bob = actor();
        String prefix = "A" + UUID.randomUUID().toString().substring(0, 6) + "-";
        openAccounts(alice, prefix);
        Instant first = clock.instant();
        clock.advance(Duration.ofHours(1));
        LedgerProcesses.PostOutput sale = post(alice, prefix, 100);
        clock.advance(Duration.ofHours(1));
        LedgerProcesses.PostOutput other = post(bob, prefix, 200);
        clock.advance(Duration.ofHours(1));
        post(alice, prefix, 300);

        // By actor, newest first, with the versions each operation wrote.
        Map<String, Object> byAlice = get(uri -> uri.queryParam("actorId", alice));
        assertThat(byAlice.get("total")).isEqualTo(3);
        List<Map<String, Object>> items = items(byAlice);
        assertThat(items).extracting(item -> item.get("processName"))
            .containsExactly("LEDGER_POST", "LEDGER_POST", "jabiz.dataset.commit");
        assertThat(items.get(1).get("opTime")).isEqualTo(first.plus(Duration.ofHours(1)).toString());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> written = (List<Map<String, Object>>) items.get(1).get("items");
        assertThat(written).extracting(item -> item.get("entityType"))
            .containsOnly(LedgerEntities.TRANSACTION, LedgerEntities.ENTRY).hasSize(3);
        assertThat(written).allSatisfy(item -> assertThat(item).containsEntry("action", "INSERT")
            .containsKeys("entityId", "versionNo", "changedFields"));

        // By time: [from, to).
        Map<String, Object> window = get(uri -> uri.queryParam("actorId", alice)
            .queryParam("from", first.plus(Duration.ofMinutes(30)).toString())
            .queryParam("to", first.plus(Duration.ofHours(2)).toString()));
        assertThat(items(window)).extracting(item -> item.get("processSeqId")).containsExactly(
            items.get(1).get("processSeqId"));

        // By entity: who wrote this transaction; by entity type and process.
        Map<String, Object> byEntity = get(uri -> uri.queryParam("entityType", LedgerEntities.TRANSACTION)
            .queryParam("entityId", other.transactionId()));
        assertThat(items(byEntity)).singleElement().satisfies(item -> assertThat(item).containsEntry("actorId", bob));
        Map<String, Object> byProcess = get(uri -> uri.queryParam("processName", "LEDGER_POST")
            .queryParam("entityType", LedgerEntities.ENTRY).queryParam("actorId", alice));
        assertThat(byProcess.get("total")).isEqualTo(2);
        assertThat(sale.transactionId()).isNotBlank();

        // Paging keeps the order and the total.
        Map<String, Object> page = get(uri -> uri.queryParam("actorId", alice).queryParam("offset", "1")
            .queryParam("limit", "1"));
        assertThat(page).containsEntry("total", 3).containsEntry("offset", 1).containsEntry("limit", 1);
        assertThat(items(page)).extracting(item -> item.get("processSeqId"))
            .containsExactly(items.get(1).get("processSeqId"));
    }

    @Test
    void theAuditViewNeedsItsPermissionAndValidFilters() {
        WebTestClient client = WebTestClient.bindToApplicationContext(context).build();
        client.get().uri("/api/audit/operations").exchange().expectStatus().isUnauthorized();
        client.get().uri("/api/audit/operations")
            .header(HttpHeaders.AUTHORIZATION, TestTokens.bearer(tokens, "it-reader", "operation.read"))
            .exchange().expectStatus().isForbidden();
        client.get().uri(uri -> uri.path("/api/audit/operations").queryParam("from", "yesterday")
                .queryParam("entityId", "not-a-uuid").queryParam("limit", "501").build())
            .header(HttpHeaders.AUTHORIZATION, TestTokens.bearer(tokens, "it-auditor", "audit.read"))
            .exchange().expectStatus().isBadRequest()
            .expectBody(MAP).value(body -> assertThat(body.toString())
                .contains("field=from", "field=entityId", "field=limit"));
    }

    // ================= helpers =================

    private static String actor() {
        return "audit-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static RequestContext as(String actor) {
        return new RequestContext(actor, null, Locale.ENGLISH, "it-audit", Set.of(), Set.of("*"));
    }

    private void openAccounts(String actor, String prefix) {
        List<EntityChange> changes = List.of("1100", "4100").stream().map(code -> {
            String id = UUID.randomUUID().toString();
            return new EntityChange(EntityAction.INSERT, new EntityInstance(id, LedgerEntities.ACCOUNT, 0, null,
                Map.of("accountId", id, "accountCode", prefix + code, "accountName", code, "accountType", "ASSET",
                    "enabled", true)), null);
        }).toList();
        asRequest(as(actor), entities.commitBatch(datasets.findById(LedgerEntities.ACCOUNT_DATASET).orElseThrow(),
            changes)).block();
    }

    @SuppressWarnings("unchecked")
    private LedgerProcesses.PostOutput post(String actor, String prefix, long amount) {
        ProcessDefinition<LedgerProcesses.PostInput, LedgerProcesses.PostOutput, ProcessContext> post =
            (ProcessDefinition<LedgerProcesses.PostInput, LedgerProcesses.PostOutput, ProcessContext>)
                processes.find(LedgerProcesses.POST, 1).orElseThrow();
        return asRequest(as(actor), executor.execute(post, new LedgerProcesses.PostInput(null, "audit", null,
            List.of(new LedgerProcesses.Line(prefix + "1100", Direction.DEBIT, BigDecimal.valueOf(amount)),
                new LedgerProcesses.Line(prefix + "4100", Direction.CREDIT, BigDecimal.valueOf(amount)))))).block();
    }

    private Map<String, Object> get(Function<org.springframework.web.util.UriBuilder,
        org.springframework.web.util.UriBuilder> filters) {
        return WebTestClient.bindToApplicationContext(context).build().get()
            .uri(uri -> filters.apply(uri.path("/api/audit/operations")).build())
            .header(HttpHeaders.AUTHORIZATION, TestTokens.bearer(tokens, "it-auditor", "audit.read"))
            .exchange().expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(Map<String, Object> page) {
        return (List<Map<String, Object>>) page.get("items");
    }
}
