package com.jabiz.quizbuks.sponsorpackage;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The import record's rules (F-C4): the shapes, the steps done and what an import made. */
class PackageImportRulesTest {

    @Test
    void theShapesOfAHashAndAStep() {
        assertThat(PackageImportRules.HASH.matcher("0123456789abcdef".repeat(4)).matches()).isTrue();
        assertThat(PackageImportRules.HASH.matcher("ABCDEF".repeat(10) + "abcd").matches()).isFalse();
        assertThat(PackageImportRules.STEP.matcher("quiz:q1:v:q1v2:batch:3").matches()).isTrue();
        assertThat(PackageImportRules.STEP.matcher("publication 1").matches()).isFalse();
        assertThat(PackageImportRules.STEP.matcher("x".repeat(201)).matches()).isFalse();
    }

    @Test
    void theStepsDoneAreReadWrittenAndKeptOnce() {
        assertThat(PackageImportRules.done(null)).isEmpty();
        assertThat(PackageImportRules.done("  ")).isEmpty();
        assertThat(PackageImportRules.done("not json")).isEmpty();
        Map<String, Object> made = new LinkedHashMap<>();
        made.put("quizId", "q-1");
        made.put("basics", null);
        Map<String, Map<String, Object>> done = PackageImportRules.with(Map.of(), "quiz:q1:v:v1:content", made);
        assertThat(done.get("quiz:q1:v:v1:content")).containsExactly(Map.entry("quizId", "q-1"));
        // A step done keeps its first result.
        done = PackageImportRules.with(done, "quiz:q1:v:v1:content", Map.of("quizId", "q-2"));
        assertThat(done.get("quiz:q1:v:v1:content")).containsEntry("quizId", "q-1");
        String json = PackageImportRules.json(done);
        assertThat(PackageImportRules.done(json)).isEqualTo(done);
    }

    @Test
    void whatAnImportMadeIsNamedByTheStepsThatMakeIt() {
        Map<String, Map<String, Object>> done = new LinkedHashMap<>();
        done.put("profile", Map.of("basics", "SAVE"));
        done.put("tree:t1", Map.of("treeId", "t-1", "nodeIds", Map.of()));
        done.put("quiz:q1:v:v1:content", Map.of("quizId", "q-1"));
        done.put("quiz:q1:v:v1:version", Map.of("quizId", "q-1", "versionId", "v-1"));
        done.put("quiz:q2:c:content", Map.of("quizId", "q-2"));
        done.put("publication:1", Map.of("publicationId", "p-1"));
        assertThat(PackageImportRules.made(done, PackageImportRules.QUIZ_ID)).containsExactly("q-1", "q-2");
        assertThat(PackageImportRules.made(done, PackageImportRules.TREE_ID)).containsExactly("t-1");
        assertThat(PackageImportRules.made(done, PackageImportRules.PUBLICATION_ID)).containsExactly("p-1");
        assertThat(PackageImportRules.made(done, PackageImportRules.VERSION_ID)).isEmpty();
        assertThat(PackageImportRules.finished(3, 3)).isTrue();
        assertThat(PackageImportRules.finished(2, 3)).isFalse();
    }

    @Test
    void aClearingStepNamesTheContentItGoesBefore() {
        assertThat(PackageImportRules.contentOfClear("quiz:q1:v:v2:clear:3")).isEqualTo("quiz:q1:v:v2:content");
        assertThat(PackageImportRules.contentOfClear("quiz:q1:c:clear:12")).isEqualTo("quiz:q1:c:content");
        assertThat(PackageImportRules.contentOfClear("quiz:q1:c:content")).isNull();
        assertThat(PackageImportRules.contentOfClear("quiz:q1:c:clear:")).isNull();
        assertThat(PackageImportRules.contentOfClear("quiz:q1:c:clear:x")).isNull();
    }
}
