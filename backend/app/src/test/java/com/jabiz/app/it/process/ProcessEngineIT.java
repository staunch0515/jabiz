package com.jabiz.app.it.process;

import com.jabiz.app.it.fixture.ItProcessFixtures;
import com.jabiz.app.it.fixture.ItProcessFixtures.ChildInput;
import com.jabiz.app.it.fixture.ItProcessFixtures.ParentInput;
import com.jabiz.app.it.fixture.ItProcessFixtures.ParentOutput;
import com.jabiz.app.it.fixture.ItProcessFixtures.PriceFamilyInput;
import com.jabiz.app.it.fixture.ItProcessFixtures.PriceInput;
import com.jabiz.app.it.fixture.ItProcessFixtures.PriceOutput;
import com.jabiz.app.it.fixture.ItProcessFixtures.ReadsInput;
import com.jabiz.app.it.fixture.ItProcessFixtures.ReadsOutput;
import com.jabiz.app.it.fixture.ItProcessFixtures.ThreadsOutput;
import com.jabiz.app.it.fixture.ItProcessFixtures.TicketInput;
import com.jabiz.app.it.fixture.ItProcessFixtures.TicketOutput;
import com.jabiz.app.it.fixture.ItTemporalFixtures;
import com.jabiz.context.RequestContext;
import com.jabiz.entity.ValidationException;
import com.jabiz.entity.Violation;
import com.jabiz.query.EntityQuery;
import com.jabiz.runtime.BusinessRuleViolationException;
import com.jabiz.runtime.DatasetEntityManager;
import com.jabiz.runtime.IdempotencyConflictException;
import com.jabiz.runtime.RevertService;
import com.jabiz.runtime.dataset.DatasetRegistry;
import com.jabiz.runtime.process.ExecutionOptions;
import com.jabiz.runtime.process.ProcessExecutor;
import com.jabiz.runtime.process.ProcessResult;
import com.jabiz.runtime.temporal.TemporalPermissions;
import com.jabiz.runtime.test.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * The process engine against a real database (ROADMAP phase 6): one transaction and one operation per execution,
 * committed change sets, sub-processes, accumulated violations, blocking steps on virtual threads, after-commit steps
 * and idempotent replay. BlockHound is active, as in every test.
 */
class ProcessEngineIT extends PostgresIntegrationTest {

    private static final RequestContext ADMIN = new RequestContext("it-admin", null, Locale.ENGLISH, "it-admin-req",
        // A revert also needs the write permission of what it changes (docs/design/10-security.md section 5).
        Set.of(), Set.of(TemporalPermissions.REVERT, "it.write"));

    @Autowired
    ProcessExecutor executor;

    @Autowired
    RevertService reverts;

    @Autowired
    DatasetEntityManager entities;

    @Autowired
    DatasetRegistry datasets;

    @BeforeEach
    void resetProbes() {
        ItProcessFixtures.NOTIFIED.clear();
        ItProcessFixtures.NOTIFIER_FAILURES_LEFT.set(0);
        ItProcessFixtures.FlakyNotifier.committed = id -> !query("SELECT 1 FROM it_ticket WHERE f_id = ?", id)
            .isEmpty();
    }

    private static String id() {
        return "t-" + UUID.randomUUID();
    }

    private static long operations() {
        return ((Number) query("SELECT count(*) AS n FROM op_process").getFirst().get("n")).longValue();
    }

    private static boolean ticketExists(String id) {
        return !query("SELECT 1 FROM it_ticket WHERE f_id = ?", id).isEmpty();
    }

    private static List<String> codes(Throwable error) {
        assertThat(error).isInstanceOf(BusinessRuleViolationException.class);
        return ((BusinessRuleViolationException) error).violations().stream().map(Violation::ruleCode).toList();
    }

    // ---------------------------------------------------------------- one operation, one transaction

    @Test
    void anExecutionIsOneOperationWithItsOutputAndCommittedChanges() {
        String id = id();
        TicketOutput out = asTestRequest(executor.execute(ItProcessFixtures.CREATE_TICKET,
            new TicketInput(id, "first", 5L))).block();

        assertThat(out.id()).isEqualTo(id);
        assertThat(out.version()).isEqualTo(1L);
        Map<String, Object> operation = query("SELECT * FROM op_process WHERE process_seq_id = ?", out.processSeqId())
            .getFirst();
        assertThat(operation).containsEntry("process_name", "IT_CREATE_TICKET").containsEntry("process_version", 1)
            .containsEntry("actor_id", "it-user").containsEntry("request_id", "it-request");
        assertThat(((Timestamp) operation.get("op_time")).toInstant()).isEqualTo(START);
        assertThat(operation.get("parent_seq_id")).isNull();
        // Without an idempotency key there is nothing to replay, so the output is not kept.
        assertThat(query("SELECT 1 FROM op_process_result WHERE process_seq_id = ?", out.processSeqId())).isEmpty();
        assertThat(query("SELECT f_title, f_owner FROM it_ticket WHERE f_id = ?", id).getFirst())
            .containsEntry("f_title", "first").containsEntry("f_owner", "it-owner");
    }

    @Test
    void temporalChangesAreVersionsOfTheProcessOperation() {
        String sku = "P-" + UUID.randomUUID().toString().substring(0, 8);
        Instant later = START.plus(Duration.ofDays(3));
        PriceOutput out = asTestRequest(executor.execute(ItProcessFixtures.PRICE,
            new PriceInput(sku, 100L, later, 150L))).block();

        List<Map<String, Object>> items = query("""
            SELECT action, version_no FROM op_process_item WHERE process_seq_id = ? ORDER BY version_no""",
            out.processSeqId());
        assertThat(items).extracting(row -> row.get("action")).containsExactly("INSERT", "UPDATE");
        List<Map<String, Object>> versions = query("""
            SELECT amount, created_time, effect_start_time, process_seq_id FROM it_price WHERE sku = ?
            ORDER BY version_no""", sku);
        assertThat(versions).allSatisfy(row -> {
            assertThat(((Timestamp) row.get("created_time")).toInstant()).isEqualTo(START);
            assertThat(((Number) row.get("process_seq_id")).longValue()).isEqualTo(out.processSeqId());
        });
        assertThat(((Timestamp) versions.get(1).get("effect_start_time")).toInstant()).isEqualTo(later);
    }

    // ---------------------------------------------------------------- sub-processes

    @Test
    void aSubProcessIsAChildOperationInTheSameTransactionAndTime() {
        String parentTicket = id();
        String childTicket = id();
        clock.advance(Duration.ofMinutes(7));
        ParentOutput out = asTestRequest(executor.execute(ItProcessFixtures.PARENT,
            new ParentInput(parentTicket, childTicket, false))).block();

        Map<String, Object> child = query("SELECT * FROM op_process WHERE process_seq_id = ?",
            out.child().processSeqId()).getFirst();
        assertThat(((Number) child.get("parent_seq_id")).longValue()).isEqualTo(out.processSeqId());
        assertThat(child).containsEntry("process_name", "IT_CHILD");
        assertThat(((Timestamp) child.get("op_time")).toInstant()).isEqualTo(START.plus(Duration.ofMinutes(7)));
        assertThat(ticketExists(parentTicket)).isTrue();
        assertThat(ticketExists(childTicket)).isTrue();
    }

    /** Acceptance 2: a failing sub-process rolls the parent back as a whole; op_process keeps no record. */
    @Test
    void aFailingSubProcessRollsBackTheParentAndLeavesNoOperation() {
        String parentTicket = id();
        String childTicket = id();
        long before = operations();

        assertThatThrownBy(() -> asTestRequest(executor.execute(ItProcessFixtures.PARENT,
            new ParentInput(parentTicket, childTicket, true))).block())
            .satisfies(error -> assertThat(codes(error)).containsExactly("IT_CHILD_REFUSED"));

        assertThat(operations()).isEqualTo(before);
        // The parent's ticket had been saved before the call: it is rolled back too.
        assertThat(ticketExists(parentTicket)).isFalse();
        assertThat(ticketExists(childTicket)).isFalse();
    }

    @Test
    void violationsCollectedBeforeASaveAreReportedOnce() {
        assertThatThrownBy(() -> asTestRequest(executor.execute(ItProcessFixtures.REJECT_THEN_SAVE,
            new TicketInput(id(), "t", 1L))).block())
            .satisfies(error -> assertThat(codes(error)).containsExactly("IT_REFUSED"));
    }

    @Test
    void forEachCallsTheSubProcessOncePerInputInOrder() {
        String parent = id();
        List<String> children = List.of(id(), id(), id());
        ItProcessFixtures.FamilyOutput out = asTestRequest(executor.execute(ItProcessFixtures.FAMILY,
            new ItProcessFixtures.FamilyInput(parent, children, null))).block();

        assertThat(out.children()).hasSize(3);
        assertThat(children).allSatisfy(child -> assertThat(ticketExists(child)).isTrue());
        List<Map<String, Object>> calls = query(
            "SELECT process_seq_id FROM op_process WHERE parent_seq_id = ? ORDER BY process_seq_id",
            out.processSeqId());
        assertThat(calls).extracting(row -> ((Number) row.get("process_seq_id")).longValue())
            .containsExactlyElementsOf(out.children().stream().map(TicketOutput::processSeqId).toList());

        // No inputs: no calls, and an empty list of outputs.
        ItProcessFixtures.FamilyOutput none = asTestRequest(executor.execute(ItProcessFixtures.FAMILY,
            new ItProcessFixtures.FamilyInput(id(), List.of(), null))).block();
        assertThat(none.children()).isEmpty();
        assertThat(query("SELECT 1 FROM op_process WHERE parent_seq_id = ?", none.processSeqId())).isEmpty();
    }

    @Test
    void aFailingCallOfForEachRollsBackTheOthersAndTheCaller() {
        String parent = id();
        List<String> children = List.of(id(), id(), id());
        assertThatThrownBy(() -> asTestRequest(executor.execute(ItProcessFixtures.FAMILY,
            new ItProcessFixtures.FamilyInput(parent, children, children.get(1)))).block())
            .satisfies(error -> assertThat(codes(error)).containsExactly("IT_CHILD_REFUSED"));
        assertThat(ticketExists(parent)).isFalse();
        assertThat(children).allSatisfy(child -> assertThat(ticketExists(child)).isFalse());
    }

    @Test
    void revertingAnOperationRevertsItsSubOperations() {
        String parentSku = "F-" + UUID.randomUUID().toString().substring(0, 8);
        String childSku = "C-" + UUID.randomUUID().toString().substring(0, 8);
        PriceOutput out = asTestRequest(executor.execute(ItProcessFixtures.PRICE_FAMILY,
            new PriceFamilyInput(parentSku, childSku))).block();
        assertThat(skus()).contains(parentSku, childSku);

        clock.advance(Duration.ofMinutes(1));
        asRequest(ADMIN, reverts.revert(out.processSeqId(), "entered by mistake")).block();

        assertThat(skus()).doesNotContain(parentSku, childSku);
    }

    private List<String> skus() {
        return asTestRequest(entities.query(datasets.findById(ItTemporalFixtures.PRICE_DATASET).orElseThrow(),
                ItTemporalFixtures.PRICE, EntityQuery.builder().limit(1000).build())
            .map(instance -> (String) instance.get("sku")).collectList()).block();
    }

    // ---------------------------------------------------------------- violations

    /** Acceptance 5: violations of several steps are reported together, and nothing is written. */
    @Test
    void violationsOfSeveralStepsAreReportedTogetherAndNothingIsWritten() {
        String id = id();
        long before = operations();

        assertThatThrownBy(() -> asTestRequest(executor.execute(ItProcessFixtures.MULTI_VIOLATION,
            new TicketInput(id, "t", 1L))).block())
            .satisfies(error -> assertThat(codes(error)).containsExactly("IT_TITLE_TAKEN", "IT_AMOUNT_TOO_HIGH"));

        assertThat(ticketExists(id)).isFalse();
        assertThat(operations()).isEqualTo(before);
    }

    // ---------------------------------------------------------------- platform steps

    @Test
    void platformStepsLoadQueryRunTemplatesAndSaveMidway() {
        String id = id();
        String owner = "o-" + UUID.randomUUID().toString().substring(0, 8);
        execute("INSERT INTO it_ticket (f_id, f_title, f_amount, f_status, f_owner, f_created_at) VALUES "
            + "(?, 'loaded', 1, 'OPEN', ?, now()), (?, 'other', 1, 'OPEN', ?, now())", id, owner, id(), owner);
        execute("INSERT INTO it_soft (f_id, f_name) VALUES (?, 'soft')", id());

        ReadsOutput out = asTestRequest(executor.execute(ItProcessFixtures.READS, new ReadsInput(id, owner))).block();

        assertThat(out.loadedTitle()).isEqualTo("loaded");
        assertThat(out.sameOwner()).isEqualTo(2);
        assertThat(out.softNames()).isPositive();
        assertThat(out.savedVersion()).isEqualTo(2L);
        assertThat(query("SELECT f_title FROM it_ticket WHERE f_id = ?", id).getFirst())
            .containsEntry("f_title", "renamed");
    }

    // ---------------------------------------------------------------- blocking steps

    /** Acceptance 3: blocking steps run on virtual threads; BlockHound (always on) does not object to the sleep. */
    @Test
    void blockingStepsRunOnVirtualThreadsAndComputationsDoNot() {
        ThreadsOutput out = asTestRequest(executor.execute(ItProcessFixtures.THREADS, "go")).block();

        assertThat(out.blockingOnVirtualThread()).isTrue();
        assertThat(out.computeOnVirtualThread()).isFalse();
    }

    // ---------------------------------------------------------------- after commit

    /** After-commit steps run on their own after the response; waits until the given number of attempts is logged. */
    private static List<Map<String, Object>> awaitAttempts(long processSeqId, int count) {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        List<Map<String, Object>> attempts;
        do {
            attempts = query("""
                SELECT attempt, succeeded, error FROM op_process_after_commit WHERE process_seq_id = ?
                ORDER BY attempt""", processSeqId);
            if (attempts.size() >= count) {
                return attempts;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        } while (System.nanoTime() < deadline);
        return attempts;
    }

    @Test
    void afterCommitStepsRunOnceCommittedAndAreRetriedWithEveryAttemptRecorded() {
        String id = id();
        ItProcessFixtures.NOTIFIER_FAILURES_LEFT.set(2);

        TicketOutput out = asTestRequest(executor.execute(ItProcessFixtures.NOTIFY, new ChildInput(id, false)))
            .block();

        assertThat(awaitAttempts(out.processSeqId(), 3))
            .extracting(row -> row.get("attempt"), row -> row.get("succeeded"))
            .containsExactly(tuple(1, false), tuple(2, false),
                tuple(3, true));
        assertThat(ItProcessFixtures.NOTIFIED).containsExactly(id + ":committed", id + ":committed",
            id + ":committed");
        assertThat(query("SELECT error FROM op_process_after_commit WHERE process_seq_id = ? AND attempt = 1",
            out.processSeqId()).getFirst().get("error").toString()).contains("notification service unavailable");
    }

    @Test
    void anAfterCommitStepThatKeepsFailingDoesNotFailTheCommittedProcess() {
        String id = id();
        ItProcessFixtures.NOTIFIER_FAILURES_LEFT.set(10);

        TicketOutput out = asTestRequest(executor.execute(ItProcessFixtures.NOTIFY, new ChildInput(id, false)))
            .block();

        assertThat(ticketExists(id)).isTrue();
        assertThat(awaitAttempts(out.processSeqId(), 3))
            .hasSize(3).allSatisfy(row -> assertThat(row.get("succeeded")).isEqualTo(false));
    }

    @Test
    void anAfterCommitStepThatRegistersChangesFailsWithoutRetryAndWritesNothing() {
        String id = id();

        TicketOutput out = asTestRequest(executor.execute(ItProcessFixtures.LATE_CHANGE, new ChildInput(id, false)))
            .block();

        List<Map<String, Object>> attempts = awaitAttempts(out.processSeqId(), 1);
        assertThat(attempts).singleElement().satisfies(row -> {
            assertThat(row.get("succeeded")).isEqualTo(false);
            assertThat(row.get("error").toString()).contains("registered changes");
        });
        assertThat(ticketExists(id)).isFalse();
    }

    @Test
    void afterCommitStepsDoNotRunWhenTheTransactionRollsBack() {
        String id = id();

        long before = operations();
        assertThatThrownBy(() -> asTestRequest(executor.execute(ItProcessFixtures.NOTIFY, new ChildInput(id, true)))
            .block()).hasMessageContaining("fails after the after-commit step was declared");
        assertThat(operations()).isEqualTo(before);

        assertThat(ItProcessFixtures.NOTIFIED).isEmpty();
        assertThat(ticketExists(id)).isFalse();
    }

    // ---------------------------------------------------------------- idempotency

    @Test
    void aRepeatedIdempotentRequestReplaysTheFirstResult() {
        String key = "k-" + UUID.randomUUID();
        TicketInput input = new TicketInput(id(), "once", 1L);
        int runs = ItProcessFixtures.COUNTED_RUNS.get();

        ProcessResult<TicketOutput> first = asTestRequest(executor.run(ItProcessFixtures.COUNTED, input,
            ExecutionOptions.idempotent(key))).block();
        ProcessResult<TicketOutput> second = asTestRequest(executor.run(ItProcessFixtures.COUNTED, input,
            ExecutionOptions.idempotent(key))).block();

        assertThat(first.replayed()).isFalse();
        assertThat(String.valueOf(query("SELECT output FROM op_process_result WHERE process_seq_id = ?",
            first.processSeqId()).getFirst().get("output"))).contains(input.id());
        assertThat(second.replayed()).isTrue();
        assertThat(second.processSeqId()).isEqualTo(first.processSeqId());
        assertThat(second.output()).isEqualTo(first.output());
        assertThat(ItProcessFixtures.COUNTED_RUNS.get()).isEqualTo(runs + 1);
        // Keys are per actor: another actor runs the process anew.
        RequestContext other = new RequestContext("it-other", null, Locale.ENGLISH, "r", Set.of(), Set.of());
        ProcessResult<TicketOutput> third = asRequest(other, executor.run(ItProcessFixtures.COUNTED,
            new TicketInput(id(), "other actor", 1L), ExecutionOptions.idempotent(key))).block();
        assertThat(third.replayed()).isFalse();
        assertThat(third.processSeqId()).isNotEqualTo(first.processSeqId());
    }

    /** Acceptance 4: concurrent duplicates wait for the first execution and replay it; the process runs once. */
    @Test
    void concurrentIdempotentRequestsRunTheProcessOnce() {
        String key = "k-" + UUID.randomUUID();
        TicketInput input = new TicketInput(id(), "race", 1L);
        int runs = ItProcessFixtures.COUNTED_RUNS.get();
        long before = operations();

        Mono<ProcessResult<TicketOutput>> call = asTestRequest(executor.run(ItProcessFixtures.COUNTED, input,
            ExecutionOptions.idempotent(key))).subscribeOn(Schedulers.parallel());
        List<ProcessResult<TicketOutput>> results = Mono.zip(call, call, List::of).block();

        assertThat(results).extracting(ProcessResult::processSeqId).containsOnly(results.getFirst().processSeqId());
        assertThat(results).extracting(ProcessResult::replayed).containsExactlyInAnyOrder(false, true);
        assertThat(ItProcessFixtures.COUNTED_RUNS.get()).isEqualTo(runs + 1);
        assertThat(operations()).isEqualTo(before + 1);
    }

    @Test
    void anIdempotencyKeyCannotBeReusedForAnotherProcessOrBeMalformed() {
        String key = "k-" + UUID.randomUUID();
        asTestRequest(executor.run(ItProcessFixtures.CREATE_TICKET, new TicketInput(id(), "a", 1L),
            ExecutionOptions.idempotent(key))).block();

        assertThatThrownBy(() -> asTestRequest(executor.run(ItProcessFixtures.COUNTED, new TicketInput(id(), "b", 1L),
            ExecutionOptions.idempotent(key))).block())
            .isInstanceOf(IdempotencyConflictException.class);
        assertThatThrownBy(() -> asTestRequest(executor.run(ItProcessFixtures.CREATE_TICKET,
            new TicketInput(id(), "c", 1L), ExecutionOptions.idempotent("has spaces"))).block())
            .isInstanceOf(ValidationException.class);
    }

    @Test
    void subProcessesOnlyRunFromARunningProcess() {
        assertThatThrownBy(() -> asTestRequest(executor.executeChild(ItProcessFixtures.CHILD,
            new ChildInput(id(), false))).block())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("sub-process");
    }

    @Test
    void executionsNeedARequestContext() {
        assertThatThrownBy(() -> executor.execute(ItProcessFixtures.CREATE_TICKET, new TicketInput(id(), "x", 1L))
            .block())
            .hasMessageContaining("No RequestContext");
    }
}
