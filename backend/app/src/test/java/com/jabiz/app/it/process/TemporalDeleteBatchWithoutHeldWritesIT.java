package com.jabiz.app.it.process;

import org.springframework.test.context.TestPropertySource;

/**
 * {@link TemporalDeleteBatchIT} with the emergency switch {@code jabiz.storage.write-batching.enabled=false} (ROADMAP
 * 17zz21): nothing is read ahead, and every deletion, refusal and self-reference comes out as it does deleting one at a
 * time.
 */
@TestPropertySource(properties = "jabiz.storage.write-batching.enabled=false")
class TemporalDeleteBatchWithoutHeldWritesIT extends TemporalDeleteBatchIT {
}
