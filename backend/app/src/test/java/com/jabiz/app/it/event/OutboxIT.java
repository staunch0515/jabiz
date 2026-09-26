package com.jabiz.app.it.event;

import com.jabiz.app.it.fixture.ItEventFixtures;
import com.jabiz.context.RequestContext;
import com.jabiz.event.DomainEvent;
import com.jabiz.event.EventSubscription;
import com.jabiz.runtime.BusinessRuleViolationException;
import com.jabiz.runtime.DatasetEntityManager;
import com.jabiz.runtime.EntityAction;
import com.jabiz.runtime.EntityChange;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.dataset.DatasetRegistry;
import com.jabiz.runtime.event.OutboxDeliverer;
import com.jabiz.runtime.ledger.LedgerEntities;
import com.jabiz.runtime.process.ProcessExecutor;
import com.jabiz.runtime.test.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.jabiz.app.it.fixture.ItEventFixtures.CHANGE_CONSUMER;
import static com.jabiz.app.it.fixture.ItEventFixtures.NOTE_CONSUMER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ROADMAP phase 9 acceptance (docs/design/11-ledger-events-jobs.md section 2): no event without a committed
 * transaction; failed deliveries are retried; a consumer never processes an event twice, whether it is delivered
 * again later or by two deliverers at the same time. Every test uses its own ids; events of other tests may be
 * pending too, so assertions look at the test's own events only.
 */
class OutboxIT extends PostgresIntegrationTest {

    private static final RequestContext WRITER = new RequestContext("it-writer", null, Locale.ENGLISH, "it-outbox",
        Set.of(), Set.of("*"));

    @Autowired
    ProcessExecutor executor;

    @Autowired
    OutboxDeliverer deliverer;

    @Autowired
    DatasetEntityManager entities;

    @Autowired
    DatasetRegistry datasets;

    @Autowired
    List<EventSubscription<?>> subscriptions;

    @Autowired
    JsonMapper json;

    @BeforeEach
    void reset() {
        ItEventFixtures.CONSUMER_FAILURES_LEFT.set(0);
    }

    @Test
    void aCommittedProcessLeavesItsEventsInTheOutbox() {
        String id = id();
        createNote(id, false);

        List<Map<String, Object>> events = events(ItEventFixtures.NOTE_CREATED, "memoId", id);
        assertThat(events).hasSize(1);
        Map<String, Object> event = events.getFirst();
        assertThat(event.get("process_seq_id")).isNotNull();
        assertThat(event.get("created_time")).isNotNull();
        // Secrets never reach the outbox.
        assertThat(String.valueOf(event.get("payload"))).contains("\"password\": null").doesNotContain("never-stored");
        // The note publishes its change as well, in the same operation.
        List<Map<String, Object>> changes = query(
            "SELECT * FROM sys_outbox_event WHERE event_type = 'jabiz.entity-changed.ItMemo' AND entity_id = ?", id);
        assertThat(changes).hasSize(1);
        assertThat(changes.getFirst().get("process_seq_id")).isEqualTo(event.get("process_seq_id"));
        assertThat(String.valueOf(changes.getFirst().get("payload")))
            .contains("\"action\": \"INSERT\"", "\"changedFields\": [\"text\"]").doesNotContain("hello");
    }

    @Test
    void aRolledBackProcessLeavesNoEvent() {
        String id = id();
        assertThatThrownBy(() -> createNote(id, true)).isInstanceOf(BusinessRuleViolationException.class);

        assertThat(events(ItEventFixtures.NOTE_CREATED, "memoId", id)).isEmpty();
        assertThat(query("SELECT * FROM sys_outbox_event WHERE entity_id = ?", id)).isEmpty();
        assertThat(query("SELECT * FROM it_memo WHERE f_id = ?", id)).isEmpty();
    }

    @Test
    void aFailedDatasetCommitLeavesNoEntityChangeEvent() {
        String id = id();
        commitMemo(id, "first");
        // The second insert repeats a key and fails, and so does its whole batch, the first insert's event included.
        String other = id();
        assertThatThrownBy(() -> asRequest(WRITER, entities.commitBatch(dataset(), List.of(
            new EntityChange(EntityAction.INSERT, new EntityInstance(other, "ItMemo", 0, null,
                Map.of("memoId", other, "text", "y")), null),
            new EntityChange(EntityAction.INSERT, new EntityInstance(id, "ItMemo", 0, null,
                Map.of("memoId", id, "text", "again")), null)))).block()).isInstanceOf(RuntimeException.class);
        assertThat(query("SELECT * FROM sys_outbox_event WHERE entity_id = ?", other)).isEmpty();
        assertThat(query("SELECT * FROM sys_outbox_event WHERE entity_id = ?", id)).hasSize(1);

        // Updates and deletes publish the changed fields and the new version.
        EntityInstance stored = asRequest(WRITER, entities.findById(dataset(),
            entities().getOrThrow("ItMemo"), id)).block();
        commit(new EntityChange(EntityAction.UPDATE, new EntityInstance(id, "ItMemo", stored.version(), null,
            Map.of("text", "second")), null));
        commit(new EntityChange(EntityAction.DELETE, new EntityInstance(id, "ItMemo", stored.version() + 1, null,
            Map.of()), null));
        assertThat(query("SELECT payload FROM sys_outbox_event WHERE entity_id = ? ORDER BY event_seq", id))
            .extracting(row -> String.valueOf(row.get("payload")))
            .satisfiesExactly(
                insert -> assertThat(insert).contains("\"INSERT\"", "\"version\": 1"),
                update -> assertThat(update).contains("\"UPDATE\"", "\"version\": 2", "[\"text\"]"),
                delete -> assertThat(delete).contains("\"DELETE\"", "\"version\": 2"));
    }

    @Test
    void temporalEntitiesPublishTheirWrittenVersions() {
        // LedgerTransaction declares publishChanges(); postings are written as versions of an operation.
        String code = "OB" + id().substring(0, 6);
        commitAccounts(code);
        Map<String, Object> posted = asRequest(WRITER, executor.execute(
            com.jabiz.runtime.ledger.LedgerProcesses.post(new LedgerEntities.Settings("JPY", 0)),
            new com.jabiz.runtime.ledger.LedgerProcesses.PostInput(null, "outbox", null, List.of(
                new com.jabiz.runtime.ledger.LedgerProcesses.Line(code + "-A", com.jabiz.ledger.Direction.DEBIT,
                    java.math.BigDecimal.TEN),
                new com.jabiz.runtime.ledger.LedgerProcesses.Line(code + "-B", com.jabiz.ledger.Direction.CREDIT,
                    java.math.BigDecimal.TEN))))
            .map(out -> Map.<String, Object>of("id", out.transactionId()))).block();

        List<Map<String, Object>> events = query(
            "SELECT * FROM sys_outbox_event WHERE event_type = 'jabiz.entity-changed.LedgerTransaction' "
                + "AND entity_id = ?", posted.get("id"));
        assertThat(events).hasSize(1);
        assertThat(String.valueOf(events.getFirst().get("payload"))).contains("\"INSERT\"", "\"version\": 1",
            "effectiveTime");
    }

    @Test
    void failedDeliveriesAreRetriedAndTheEventIsProcessedOnce() {
        // Events other tests left pending go first, so that the failures below hit this test's event.
        deliver();
        String id = id();
        createNote(id, false);
        UUID eventId = eventId(id);
        ItEventFixtures.CONSUMER_FAILURES_LEFT.set(2);

        deliver();
        assertThat(attempts(eventId)).isEqualTo(1);
        assertThat(consumed(NOTE_CONSUMER, eventId)).isEmpty();
        // Not due yet: the next attempt waits for its backoff.
        deliver();
        assertThat(attempts(eventId)).isEqualTo(1);

        clock.advance(Duration.ofSeconds(5));
        deliver();
        assertThat(attempts(eventId)).isEqualTo(2);
        clock.advance(Duration.ofSeconds(10));
        deliver();
        assertThat(attempts(eventId)).isEqualTo(2);
        assertThat(consumed(NOTE_CONSUMER, eventId)).hasSize(1);
        // Rolled back twice, committed once: one log row and one consumption.
        assertThat(ItEventFixtures.CONSUMER_RUNS.get(eventId.toString()).get()).isEqualTo(3);
        assertThat(query("SELECT * FROM it_event_log WHERE f_id = ?", NOTE_CONSUMER + ":" + eventId)).hasSize(1);

        clock.advance(Duration.ofHours(2));
        deliver();
        assertThat(consumed(NOTE_CONSUMER, eventId)).hasSize(1);
        assertThat(query("SELECT * FROM it_event_log WHERE f_source = ?", eventId.toString())).hasSize(1);
        assertThat(ItEventFixtures.CONSUMER_RUNS.get(eventId.toString()).get()).isEqualTo(3);
    }

    @Test
    void theEntityChangeConsumerReceivesChangesToo() {
        String id = id();
        createNote(id, false);
        deliver();
        UUID changeEvent = (UUID) query("SELECT event_id FROM sys_outbox_event "
            + "WHERE event_type = 'jabiz.entity-changed.ItMemo' AND entity_id = ?", id).getFirst().get("event_id");
        assertThat(consumed(CHANGE_CONSUMER, changeEvent)).hasSize(1);
    }

    @Test
    void aRepeatedDeliveryDoesNotProcessTheEventAgain() {
        String id = id();
        createNote(id, false);
        DomainEvent event = domainEvent(eventId(id));
        EventSubscription<?> consumer = subscription(NOTE_CONSUMER);

        // As if the deliverer had lost track of the first delivery (for example, a crash before it noticed).
        assertThat(deliver(consumer, event)).isEqualTo(OutboxDeliverer.Outcome.CONSUMED);
        assertThat(deliver(consumer, event)).isEqualTo(OutboxDeliverer.Outcome.DUPLICATE);
        assertThat(consumed(NOTE_CONSUMER, event.eventId())).hasSize(1);
        assertThat(query("SELECT * FROM it_event_log WHERE f_id = ?", NOTE_CONSUMER + ":" + event.eventId()))
            .hasSize(1);
    }

    @Test
    void concurrentDeliveriesProcessTheEventOnce() {
        for (int round = 0; round < 5; round++) {
            String id = id();
            createNote(id, false);
            DomainEvent event = domainEvent(eventId(id));
            EventSubscription<?> consumer = subscription(NOTE_CONSUMER);

            List<OutboxDeliverer.Outcome> outcomes = Flux.range(0, 4)
                .flatMap(i -> Mono.defer(() -> deliverer.deliver(consumer, event, 1)), 4)
                .collectList().block();

            assertThat(outcomes).containsOnly(OutboxDeliverer.Outcome.CONSUMED, OutboxDeliverer.Outcome.DUPLICATE)
                .filteredOn(outcome -> outcome == OutboxDeliverer.Outcome.CONSUMED).hasSize(1);
            assertThat(consumed(NOTE_CONSUMER, event.eventId())).hasSize(1);
            assertThat(query("SELECT * FROM it_event_log WHERE f_id = ?", NOTE_CONSUMER + ":" + event.eventId()))
                .hasSize(1);
        }
    }

    @Test
    void outboxTablesAreAppendOnly() {
        String id = id();
        createNote(id, false);
        deliver();
        assertThatThrownBy(() -> execute("UPDATE sys_outbox_event SET event_type = 'x' WHERE entity_id = ?", id))
            .hasMessageContaining("append-only table");
        assertThatThrownBy(() -> execute("DELETE FROM sys_event_consumption"))
            .hasMessageContaining("append-only table");
        assertThatThrownBy(() -> execute("TRUNCATE sys_outbox_attempt")).hasMessageContaining("append-only table");
    }

    // ================= helpers =================

    private static String id() {
        return "n-" + UUID.randomUUID();
    }

    private void createNote(String id, boolean fail) {
        asRequest(WRITER, executor.execute(ItEventFixtures.NOTE_CREATE,
            new ItEventFixtures.NoteInput(id, "hello", fail))).block();
    }

    private com.jabiz.dataset.DatasetDefinition dataset() {
        return datasets.findById("urn:jabiz:dataset:it:ItMemo").orElseThrow();
    }

    @Autowired
    com.jabiz.runtime.entity.EntityDefinitionRegistry entityRegistry;

    private com.jabiz.runtime.entity.EntityDefinitionRegistry entities() {
        return entityRegistry;
    }

    private void commitMemo(String id, String text) {
        commit(new EntityChange(EntityAction.INSERT, new EntityInstance(id, "ItMemo", 0, null,
            Map.of("memoId", id, "text", text)), null));
    }

    private void commit(EntityChange change) {
        asRequest(WRITER, entities.commitBatch(dataset(), List.of(change))).block();
    }

    private void commitAccounts(String code) {
        List<EntityChange> accounts = List.of("A", "B").stream().map(suffix -> {
            String accountId = UUID.randomUUID().toString();
            return new EntityChange(EntityAction.INSERT, new EntityInstance(accountId, LedgerEntities.ACCOUNT, 0, null,
                Map.of("accountId", accountId, "accountCode", code + "-" + suffix, "accountName", "Account " + suffix,
                    "accountType", "ASSET", "enabled", true)), null);
        }).toList();
        asRequest(WRITER, entities.commitBatch(datasets.findById(LedgerEntities.ACCOUNT_DATASET).orElseThrow(),
            accounts)).block();
    }

    private void deliver() {
        deliverer.deliverPending().block();
    }

    private OutboxDeliverer.Outcome deliver(EventSubscription<?> subscription, DomainEvent event) {
        return deliverer.deliver(subscription, event, 1).block();
    }

    private EventSubscription<?> subscription(String consumer) {
        return subscriptions.stream().filter(s -> s.consumer().equals(consumer)).findFirst().orElseThrow();
    }

    private List<Map<String, Object>> events(String type, String payloadKey, String value) {
        return query("SELECT * FROM sys_outbox_event WHERE event_type = ? AND payload ->> ? = ?", type, payloadKey,
            value);
    }

    private UUID eventId(String noteId) {
        return (UUID) events(ItEventFixtures.NOTE_CREATED, "memoId", noteId).getFirst().get("event_id");
    }

    private DomainEvent domainEvent(UUID eventId) {
        Map<String, Object> row = query("SELECT * FROM sys_outbox_event WHERE event_id = ?", eventId).getFirst();
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = json.readValue(String.valueOf(row.get("payload")), Map.class);
        return new DomainEvent(eventId, String.valueOf(row.get("event_type")), payload,
            (Long) row.get("process_seq_id"), ((java.sql.Timestamp) row.get("created_time")).toInstant());
    }

    private static int attempts(UUID eventId) {
        return query("SELECT * FROM sys_outbox_attempt WHERE consumer = ? AND event_id = ?", NOTE_CONSUMER, eventId)
            .size();
    }

    private static List<Map<String, Object>> consumed(String consumer, UUID eventId) {
        return query("SELECT * FROM sys_event_consumption WHERE consumer = ? AND event_id = ?", consumer, eventId);
    }
}
