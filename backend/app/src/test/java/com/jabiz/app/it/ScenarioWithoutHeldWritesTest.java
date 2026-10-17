package com.jabiz.app.it;

import com.jabiz.app.App;
import com.jabiz.runtime.test.scenario.ScenarioReplay;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.util.Map;
import java.util.stream.Stream;

/**
 * Every scenario replayed with the emergency switch {@code jabiz.storage.write-batching.enabled=false} (ROADMAP phase
 * 16p-2, test 13): the snapshots are the very ones {@link ScenarioTest} compares with, so holding the transaction's
 * writes changes nothing a scenario records, field by field. (It never updates a snapshot: run {@link ScenarioTest} for
 * that.)
 */
class ScenarioWithoutHeldWritesTest {

    @TestFactory
    Stream<DynamicTest> scenarios() {
        return ScenarioReplay.resources("scenarios").stream()
            .map(resource -> DynamicTest.dynamicTest(resource, () -> ScenarioReplay.verify(App.class, resource,
                Map.of("jabiz.storage.write-batching.enabled", "false"))));
    }
}
