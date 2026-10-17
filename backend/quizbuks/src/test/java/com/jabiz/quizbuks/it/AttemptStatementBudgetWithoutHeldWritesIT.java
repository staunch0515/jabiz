package com.jabiz.quizbuks.it;

import org.springframework.test.context.TestPropertySource;

/**
 * The answering budgets with the platform's emergency switch off ({@code jabiz.storage.write-batching.enabled=false}):
 * exactly the numbers before platform phase 16p-2 (Q10a3, Q10a4), each commit writing its rows as it ends. The other
 * side of the load test's A/B (docs/quizbuks/perf/Q10a-load-test.md §11).
 */
@TestPropertySource(properties = "jabiz.storage.write-batching.enabled=false")
class AttemptStatementBudgetWithoutHeldWritesIT extends AttemptStatementBudgetIT {
}
