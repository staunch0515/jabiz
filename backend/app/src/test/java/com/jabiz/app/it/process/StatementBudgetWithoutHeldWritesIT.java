package com.jabiz.app.it.process;

import org.springframework.test.context.TestPropertySource;

/**
 * The statement budgets with the emergency switch off ({@code jabiz.storage.write-batching.enabled=false}): exactly
 * phase 16p-1's numbers, each commit writing its rows when it ends (ROADMAP phase 16p-2, P-8).
 */
@TestPropertySource(properties = "jabiz.storage.write-batching.enabled=false")
class StatementBudgetWithoutHeldWritesIT extends StatementBudgetIT {
}
