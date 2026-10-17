package com.jabiz.app.it.process;

import org.springframework.test.context.TestPropertySource;

/** {@link HeldWritesIT} with the emergency switch off: the same answers, each commit writing its rows (phase 16p-1). */
@TestPropertySource(properties = "jabiz.storage.write-batching.enabled=false")
class HeldWritesWithoutHeldWritesIT extends HeldWritesIT {
}
