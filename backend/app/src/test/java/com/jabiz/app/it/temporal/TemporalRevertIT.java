package com.jabiz.app.it.temporal;

import com.jabiz.app.it.fixture.ItTemporalFixtures;
import com.jabiz.entity.ValidationException;
import com.jabiz.runtime.BusinessRuleViolationException;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.EntityNotFoundException;
import com.jabiz.runtime.PermissionDeniedException;
import com.jabiz.runtime.RevertConflictException;
import com.jabiz.runtime.operation.Operation;
import com.jabiz.runtime.operation.OperationRecord;
import com.jabiz.runtime.operation.OperationRecorder;
import com.jabiz.runtime.operation.OperationRequest;
import com.jabiz.runtime.operation.Operations;
import com.jabiz.runtime.storage.StorageAdapterRegistry;
import com.jabiz.runtime.storage.StorageEngine;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Reverting operations (decision D2, docs/design/04 section 6). */
class TemporalRevertIT extends TemporalItSupport {

    @Autowired
    OperationRecorder operations;

    @Autowired
    StorageAdapterRegistry storages;

    private static BigDecimal amount(EntityInstance instance) {
        return instance.get("amount");
    }

    private void tick() {
        advance(Duration.ofMinutes(1));
    }

    /** Acceptance: after a revert the state is restored, by a new operation pointing at the reverted one. */
    @Test
    void aRevertRestoresTheChangedFields() {
        EntityInstance price = newPrice(sku(), 100);
        tick();
        commit(update(price.id(), 1, attrs("amount", 150, "note", "raised"), null));
        long raise = lastOperation(price.id());
        tick();

        OperationRecord revert = revert(raise, "raised by mistake");

        assertThat(revert.revertsSeqId()).isEqualTo(raise);
        assertThat(revert.reason()).isEqualTo("raised by mistake");
        assertThat(revert.processName()).isEqualTo(OperationRequest.REVERT);
        EntityInstance current = read(price.id());
        assertThat(amount(current)).isEqualByComparingTo("100");
        assertThat(current.attributes()).containsEntry("note", null);
        assertThat(query("SELECT action, changed_fields::text AS fields FROM op_process_item WHERE process_seq_id = ?",
            revert.processSeqId())).containsExactly(Map.of("action", "REVERT", "fields", "{amount,note}"));
    }

    /** Acceptance: a later change of the same field blocks the revert and is listed. */
    @Test
    void aLaterChangeOfTheSameFieldBlocksTheRevert() {
        EntityInstance price = newPrice(sku(), 100);
        tick();
        commit(update(price.id(), 1, attrs("amount", 150), null));
        long raise = lastOperation(price.id());
        tick();
        commit(update(price.id(), 2, attrs("amount", 170), null));
        long again = lastOperation(price.id());

        assertThatThrownBy(() -> revert(raise, "undo"))
            .isInstanceOfSatisfying(RevertConflictException.class, e -> assertThat(e.blocking()).singleElement()
                .satisfies(b -> {
                    assertThat(b.processSeqId()).isEqualTo(again);
                    assertThat(b.fields()).containsExactly("amount");
                }));
        assertThat(amount(read(price.id()))).isEqualByComparingTo("170");

        // Reverting newest first works.
        revert(again, "undo the second raise");
        revert(raise, "undo the first raise");
        assertThat(amount(read(price.id()))).isEqualByComparingTo("100");
    }

    /** Acceptance: later changes of other fields do not block the revert and are kept. */
    @Test
    void laterChangesOfOtherFieldsDoNotBlock() {
        EntityInstance price = newPrice(sku(), 100);
        tick();
        commit(update(price.id(), 1, attrs("amount", 150), null));
        long raise = lastOperation(price.id());
        tick();
        commit(update(price.id(), 2, attrs("note", "keep me", "status", "ACTIVE"), null));

        revert(raise, "undo");

        EntityInstance current = read(price.id());
        assertThat(amount(current)).isEqualByComparingTo("100");
        assertThat(current.attributes()).containsEntry("note", "keep me").containsEntry("status", "ACTIVE");
    }

    /** Acceptance: reverting an insertion writes a tombstone; any later change blocks it. */
    @Test
    void revertingAnInsertionDeletes() {
        EntityInstance price = newPrice(sku(), 100);
        long insert = lastOperation(price.id());
        tick();

        revert(insert, "created by mistake");
        assertThat(read(price.id())).isNull();
        assertThat(versions(price.id())).extracting(r -> r.get("is_deleted")).containsExactly(false, true);

        EntityInstance other = newPrice(sku(), 100);
        long otherInsert = lastOperation(other.id());
        tick();
        commit(update(other.id(), 1, attrs("note", "touched"), null));
        assertThatThrownBy(() -> revert(otherInsert, "undo")).isInstanceOf(RevertConflictException.class);
    }

    /** Acceptance: redo is the revert of a revert. */
    @Test
    void revertingARevertRedoes() {
        EntityInstance price = newPrice(sku(), 100);
        tick();
        commit(update(price.id(), 1, attrs("amount", 150), null));
        long raise = lastOperation(price.id());
        tick();
        OperationRecord undo = revert(raise, "undo");
        tick();

        // The raise is reverted already: reverting it again is blocked by the revert.
        assertThatThrownBy(() -> revert(raise, "again")).isInstanceOf(RevertConflictException.class);

        OperationRecord redo = revert(undo.processSeqId(), "redo");
        assertThat(redo.revertsSeqId()).isEqualTo(undo.processSeqId());
        assertThat(amount(read(price.id()))).isEqualByComparingTo("150");
    }

    @Test
    void revertingADeletionBringsTheEntityBack() {
        EntityInstance price = newPrice(sku(), 100);
        tick();
        commit(delete(price.id(), 1, null));
        long deletion = lastOperation(price.id());
        tick();

        revert(deletion, "deleted by mistake");

        assertThat(amount(read(price.id()))).isEqualByComparingTo("100");
    }

    /** Acceptance: sub-operations are reverted with their parent, in one operation tree. */
    @Test
    void subOperationsAreRevertedTogether() {
        EntityInstance a = newPrice(sku(), 100);
        EntityInstance b = newPrice(sku(), 200);
        tick();
        StorageEngine engine = storages.getEngine("default");
        Mono<Long> tree = operations.begin(engine, OperationRequest.named("it.parent", 1), ADMIN)
            .flatMap(parent -> child(engine, parent, a, 110)
                .then(child(engine, parent, b, 210))
                .thenReturn(parent.processSeqId()));
        long parent = asRequest(ADMIN, engine.inTransaction(tree)).block();
        assertThat(amount(read(a.id()))).isEqualByComparingTo("110");
        assertThat(amount(read(b.id()))).isEqualByComparingTo("210");
        tick();

        OperationRecord revert = revert(parent, "undo the batch");

        assertThat(amount(read(a.id()))).isEqualByComparingTo("100");
        assertThat(amount(read(b.id()))).isEqualByComparingTo("200");
        List<Map<String, Object>> children = query("SELECT reverts_seq_id FROM op_process WHERE parent_seq_id = ? "
            + "ORDER BY process_seq_id", revert.processSeqId());
        assertThat(children).hasSize(2);
        assertThat(query("SELECT count(*) AS n FROM op_process WHERE parent_seq_id = ?", parent).getFirst().get("n"))
            .isEqualTo(2L);
    }

    private Mono<Void> child(StorageEngine engine, Operation parent, EntityInstance price, int amount) {
        OperationRequest request = new OperationRequest("it.child", 1, null, parent.processSeqId(), null, null, null,
            parent.opTime());
        return operations.begin(engine, request, ADMIN).flatMap(child -> entities
            .commitBatch(dataset(ItTemporalFixtures.PRICE_DATASET),
                List.of(update(price.id(), price.version(), attrs("amount", amount), null)))
            .contextWrite(view -> Operations.with(view, child))).then();
    }

    @Test
    void revertsNeedThePermissionAReasonAndVersions() {
        EntityInstance price = newPrice(sku(), 100);
        long insert = lastOperation(price.id());

        assertThatThrownBy(() -> asRequest(TEST_REQUEST, reverts.revert(insert, "x")).block())
            .isInstanceOf(PermissionDeniedException.class);
        assertThatThrownBy(() -> revert(insert, " "))
            .isInstanceOfSatisfying(ValidationException.class, e -> assertThat(e.violations())
                .extracting(v -> v.ruleCode()).containsExactly("REASON_REQUIRED"));
        assertThatThrownBy(() -> revert(Long.MAX_VALUE, "x")).isInstanceOf(EntityNotFoundException.class);

        StorageEngine engine = storages.getEngine("default");
        long empty = asRequest(ADMIN, engine.inTransaction(
            operations.begin(engine, OperationRequest.named("it.empty", 1), ADMIN))).block().processSeqId();
        assertThatThrownBy(() -> revert(empty, "x"))
            .isInstanceOfSatisfying(BusinessRuleViolationException.class, e -> assertThat(e.violations())
                .extracting(v -> v.ruleCode()).containsExactly("NOTHING_TO_REVERT"));
    }
}
