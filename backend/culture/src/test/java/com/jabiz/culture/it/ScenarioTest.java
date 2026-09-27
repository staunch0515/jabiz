package com.jabiz.culture.it;

import com.jabiz.culture.CultureApp;
import com.jabiz.runtime.test.scenario.ScenarioReplay;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.util.stream.Stream;

/**
 * Replays every scenario under {@code scenarios/} and compares it with its snapshot (docs/design/07-quality.md
 * section 3). Each replay runs in a fresh application on a schema of its own.
 */
class ScenarioTest {

    @TestFactory
    Stream<DynamicTest> scenarios() {
        return ScenarioReplay.resources("scenarios").stream()
            .map(resource -> DynamicTest.dynamicTest(resource, () -> ScenarioReplay.verify(CultureApp.class, resource)));
    }
}
