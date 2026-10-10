package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.QuizbuksApp;
import com.jabiz.runtime.test.scenario.ScenarioReplay;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.util.stream.Stream;

/**
 * Replays every QuizBuks scenario under {@code scenarios/} against its snapshot (platform docs/design/07-quality.md
 * section 3); each replay runs in a fresh application on a schema of its own.
 */
class ScenarioTest {

    @TestFactory
    Stream<DynamicTest> scenarios() {
        return ScenarioReplay.resources("scenarios").stream()
            .map(resource -> DynamicTest.dynamicTest(resource,
                () -> ScenarioReplay.verify(QuizbuksApp.class, resource)));
    }
}
