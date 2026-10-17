package com.jabiz.app.it.ledger;

import org.springframework.test.context.TestPropertySource;

/**
 * {@link LedgerIT} with the emergency switch {@code jabiz.storage.write-batching.enabled=false} (ROADMAP phase 16p-2,
 * P-8): every guarantee holds the same way when each commit writes its rows as it ends (phase 16p-1).
 */
@TestPropertySource(properties = "jabiz.storage.write-batching.enabled=false")
class LedgerWithoutHeldWritesIT extends LedgerIT {
}
