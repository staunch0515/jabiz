package com.jabiz.app.it.temporal;

import com.jabiz.app.it.fixture.ItTemporalFixtures;
import com.jabiz.entity.ValidationException;
import com.jabiz.runtime.BusinessRuleViolationException;
import com.jabiz.runtime.ConcurrentUpdateException;
import com.jabiz.runtime.EntityAction;
import com.jabiz.runtime.EntityChange;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.EntityNotFoundException;
import com.jabiz.app.it.fixture.ItFixtures;
import com.jabiz.runtime.operation.OperationRecorder;
import com.jabiz.runtime.operation.OperationRequest;
import com.jabiz.runtime.storage.StorageAdapterRegistry;
import com.jabiz.runtime.storage.StorageEngine;
import com.jabiz.runtime.storage.UniqueKeyViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Writes of temporal entities append versions, one operation per commit (docs/design/04 section 3). */
class TemporalWriteIT extends TemporalItSupport {

    @Autowired
    OperationRecorder operations;

    @Autowired
    StorageAdapterRegistry storages;

    private static Instant instant(Object value) {
        return value instanceof OffsetDateTime o ? o.toInstant() : ((Timestamp) value).toInstant();
    }

    @Test
    void anInsertIsVersionOneOfARegisteredEntityWrittenByAnOperation() {
        EntityInstance created = newPrice(sku(), 100);
        UUID id = (UUID) created.id();

        assertThat(created.version()).isEqualTo(1);
        assertThat(created.attributes()).containsEntry("effectStartTime", START).containsEntry("createdTime", START)
            .containsEntry("deleted", false);
        Map<String, Object> row = versions(id).getFirst();
        assertThat(row).containsEntry("version_no", 1).containsEntry("is_deleted", false);
        assertThat(instant(row.get("effect_start_time"))).isEqualTo(START);
        long seq = ((Number) row.get("process_seq_id")).longValue();

        Map<String, Object> operation = query("SELECT * FROM op_process WHERE process_seq_id = ?", seq).getFirst();
        assertThat(operation).containsEntry("process_name", "jabiz.dataset.commit").containsEntry("actor_id", "it-admin")
            .containsEntry("request_id", "it-admin-request");
        assertThat(instant(operation.get("op_time"))).isEqualTo(START);
        assertThat(query("SELECT entity_type, created_seq_id FROM entity_registry WHERE entity_id = ?", id))
            .containsExactly(Map.of("entity_type", "ItPrice", "created_seq_id", seq));
        Map<String, Object> item = query("SELECT * FROM op_process_item WHERE process_seq_id = ?", seq).getFirst();
        assertThat(item).containsEntry("action", "INSERT").containsEntry("version_no", 1)
            .containsEntry("base_version_no", null).containsEntry("entity_type", "ItPrice");
        assertThat(query("SELECT output::text AS output FROM op_process_result WHERE process_seq_id = ?", seq)
            .getFirst().get("output").toString()).contains(id.toString());
    }

    @Test
    void updatesAndDeletionsAppendVersions() {
        EntityInstance created = newPrice(sku(), 100);
        advance(Duration.ofMinutes(5));

        EntityInstance updated = commit(update(created.id(), 1, attrs("amount", 120), null)).getFirst();
        assertThat(updated.version()).isEqualTo(2);
        assertThat(updated.<BigDecimal>get("amount")).isEqualByComparingTo("120");
        assertThat(read(created.id()).version()).isEqualTo(2);

        // An update without a real change writes nothing.
        assertThat(commit(update(created.id(), 2, attrs("amount", 120), null)).getFirst().version()).isEqualTo(2);

        advance(Duration.ofMinutes(5));
        assertThat(commit(delete(created.id(), 2, null))).isEmpty();
        assertThat(read(created.id())).isNull();
        assertThat(versions(created.id())).extracting(r -> r.get("version_no"), r -> r.get("is_deleted"))
            .containsExactly(org.assertj.core.groups.Tuple.tuple(1, false), org.assertj.core.groups.Tuple.tuple(2, false),
                org.assertj.core.groups.Tuple.tuple(3, true));
        assertThat(query("SELECT action FROM op_process_item WHERE entity_id = ? ORDER BY version_no",
            created.id())).extracting(r -> r.get("action")).containsExactly("INSERT", "UPDATE", "DELETE");
        // The deleted entity can no longer be changed.
        assertThatThrownBy(() -> commit(update(created.id(), 3, attrs("amount", 1), null)))
            .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void theRulesOfOrdinaryWritesStillApply() {
        EntityInstance created = newPrice(sku(), 100);

        assertThatThrownBy(() -> commit(update(created.id(), 1, attrs("status", "RETIRED"), null)))
            .isInstanceOfSatisfying(BusinessRuleViolationException.class, e -> assertThat(e.violations())
                .extracting(v -> v.ruleCode()).containsExactly("ILLEGAL_TRANSITION"));
        assertThatThrownBy(() -> commit(update(created.id(), 1, attrs("amount", -1), null)))
            .isInstanceOfSatisfying(ValidationException.class, e -> assertThat(e.violations())
                .extracting(v -> v.ruleCode()).containsExactly("NON_NEGATIVE_AMOUNT"));
        assertThatThrownBy(() -> commit(insert(attrs("sku", sku(), "region", "JP", "priceId", "P-1"))))
            .isInstanceOfSatisfying(ValidationException.class, e -> assertThat(e.violations())
                .extracting(v -> v.ruleCode()).containsExactly("INVALID_VALUE"));
        assertThatThrownBy(() -> commit(insert(UUID.randomUUID(), attrs("region", "JP"), null)))
            .isInstanceOf(ValidationException.class);
        // An existing id cannot be inserted again.
        assertThatThrownBy(() -> commit(insert((UUID) created.id(), attrs("sku", sku(), "region", "JP"), null)))
            .isInstanceOf(ConcurrentUpdateException.class);
    }

    /** Acceptance: two updates based on the same version, one succeeds and the other gets 409. */
    @Test
    void ofTwoConcurrentUpdatesOfTheSameVersionOneFails() {
        EntityInstance created = newPrice(sku(), 100);
        advance(Duration.ofMinutes(1));

        List<Object> outcomes = Flux.just(110, 120)
            .flatMap(amount -> asRequest(ADMIN, entities.commitBatch(dataset(ItTemporalFixtures.PRICE_DATASET),
                    List.of(update(created.id(), 1, attrs("amount", amount), null)), null))
                .map(result -> (Object) result.getFirst())
                .onErrorResume(error -> Mono.just(error))
                .subscribeOn(Schedulers.parallel()))
            .collectList()
            .block();

        assertThat(outcomes).hasSize(2);
        assertThat(outcomes).filteredOn(EntityInstance.class::isInstance).hasSize(1);
        assertThat(outcomes).filteredOn(ConcurrentUpdateException.class::isInstance).hasSize(1);
        assertThat(versions(created.id())).hasSize(2);
    }

    /** Acceptance: all rows of one operation carry its time and are found through op_process_item. */
    @Test
    void everyRowOfAnOperationHasItsTimeAndItsItem() {
        EntityInstance a = newPrice(sku(), 100);
        advance(Duration.ofMinutes(1));
        // A scheduled change of a, so that the next write of a is rebased.
        commit(update(a.id(), 1, attrs("amount", 150), now().plus(Duration.ofDays(1))));
        advance(Duration.ofMinutes(1));
        Instant opTime = now();

        commit(update(a.id(), 1, attrs("note", "n"), null),
            insert(UUID.randomUUID(), attrs("sku", sku(), "region", "US", "status", "DRAFT"), null),
            insert(UUID.randomUUID(), attrs("sku", sku(), "region", "JP", "status", "DRAFT"), null));

        long seq = lastOperation(a.id());
        List<Map<String, Object>> rows = query("SELECT price_id, version_no, created_time FROM it_price "
            + "WHERE process_seq_id = ?", seq);
        assertThat(rows).hasSize(4); // update, its rebased copy, two inserts
        assertThat(rows).allSatisfy(row -> assertThat(instant(row.get("created_time"))).isEqualTo(opTime));
        List<Map<String, Object>> items = query("SELECT entity_id AS price_id, version_no FROM op_process_item "
            + "WHERE process_seq_id = ?", seq);
        assertThat(items).containsExactlyInAnyOrderElementsOf(rows.stream()
            .map(row -> Map.of("price_id", row.get("price_id"), "version_no", row.get("version_no"))).toList());
        assertThat(instant(query("SELECT op_time FROM op_process WHERE process_seq_id = ?", seq)
            .getFirst().get("op_time"))).isEqualTo(opTime);
    }

    @Test
    void effectiveTimesAreForTemporalEntitiesOnly() {
        EntityChange change = new EntityChange(EntityAction.INSERT, new EntityInstance("T-9", "ItTicket", 0, null,
            Map.of("ticketId", "T-9", "title", "t")), now());
        assertThatThrownBy(() -> commit(ItFixtures.TICKET_DATASET, ADMIN, null, change))
            .isInstanceOfSatisfying(ValidationException.class, e -> assertThat(e.violations())
                .extracting(v -> v.ruleCode()).containsExactly("NOT_TEMPORAL"));
        assertThat(query("SELECT count(*) AS n FROM it_ticket WHERE f_id = 'T-9'").getFirst().get("n")).isEqualTo(0L);
    }

    @Test
    void entitiesThatDoNotAllowSchedulingRejectLaterTimes() {
        EntityChange note = new EntityChange(EntityAction.INSERT, new EntityInstance(null, "ItNote", 0, null,
            Map.of("noteId", UUID.randomUUID(), "body", "later")), now().plus(Duration.ofDays(1)));
        assertThatThrownBy(() -> commit(ItTemporalFixtures.NOTE_DATASET, ADMIN, null, note))
            .isInstanceOfSatisfying(BusinessRuleViolationException.class, e -> assertThat(e.violations())
                .extracting(v -> v.ruleCode()).containsExactly("SCHEDULING_NOT_ALLOWED"));
    }

    @Test
    void referencesToTemporalEntitiesAreCheckedAndProtected() {
        EntityInstance price = newPrice(sku(), 100);
        UUID noteId = UUID.randomUUID();
        commit(ItTemporalFixtures.NOTE_DATASET, ADMIN, null, new EntityChange(EntityAction.INSERT,
            new EntityInstance(noteId, "ItNote", 0, null, Map.of("noteId", noteId, "priceRef", price.id()))));

        assertThatThrownBy(() -> commit(ItTemporalFixtures.NOTE_DATASET, ADMIN, null, new EntityChange(
            EntityAction.INSERT, new EntityInstance(null, "ItNote", 0, null,
                Map.of("noteId", UUID.randomUUID(), "priceRef", UUID.randomUUID())))))
            .isInstanceOfSatisfying(ValidationException.class, e -> assertThat(e.violations())
                .extracting(v -> v.ruleCode()).containsExactly("REFERENCE_NOT_FOUND"));
        assertThatThrownBy(() -> commit(delete(price.id(), 1, null)))
            .isInstanceOfSatisfying(BusinessRuleViolationException.class, e -> assertThat(e.violations())
                .extracting(v -> v.ruleCode()).containsExactly("STILL_REFERENCED"));
    }

    /** Decision D4: the output of an operation is kept in op_process_result and found by its idempotency key. */
    @Test
    void operationOutputsAreFoundByTheirIdempotencyKey() {
        StorageEngine engine = storages.getEngine("default");
        OperationRequest request = new OperationRequest("it.keyed", 1, null, null, null, null, "key-1", null);
        asRequest(ADMIN, engine.inTransaction(operations.begin(engine, request, ADMIN)
            .flatMap(op -> operations.recordResult(engine, op.processSeqId(), "{\"answer\": 42}")))).block();

        assertThat(asRequest(ADMIN, operations.findResult(engine, "it-admin", "key-1")).block())
            .isEqualTo("{\"answer\": 42}");
        assertThat(asRequest(ADMIN, operations.findResult(engine, "someone-else", "key-1")).block()).isNull();
        // The key is unique per actor.
        assertThatThrownBy(() -> asRequest(ADMIN, engine.inTransaction(operations.begin(engine, request, ADMIN))).block())
            .isInstanceOf(UniqueKeyViolationException.class);
    }
}
