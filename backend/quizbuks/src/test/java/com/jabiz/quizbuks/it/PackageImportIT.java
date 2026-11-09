package com.jabiz.quizbuks.it;

import com.jabiz.job.JobDefinition;
import com.jabiz.quizbuks.content.ContentCodes;
import com.jabiz.quizbuks.sponsorpackage.PackageImportCodes;
import com.jabiz.quizbuks.sponsorpackage.PackageImportProcesses;
import com.jabiz.quizbuks.tree.TreeProcesses;
import com.jabiz.runtime.job.JobRunner;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Importing a sponsor package (F-C4, docs/quizbuks/plans/impl/F-C4.md): the record of each import, the steps run as the
 * pages' processes in the same transaction as their record (going on never makes anything twice), only the profile
 * part for an account that submitted a publication, one open import at a time, giving up removing the drafts made,
 * {@code intoQuizId} only for the caller's own quiz without publications, and the records' retention.
 */
class PackageImportIT extends TreeItSupport {

    private static final String HASH_A = "a".repeat(64);
    private static final String HASH_B = "b".repeat(64);

    @Autowired
    @Qualifier("qbPackageImportExpireJob")
    JobDefinition<PackageImportProcesses.ExpireInput> expireJob;

    /** A quiz file (Q16, version 1) of the questions given, without files. */
    private static Map<String, Object> quizFile(String title, String... stems) {
        List<Object> questions = new ArrayList<>();
        for (String stem : stems) {
            questions.add(body("stem", stem, "points", 1, "options", List.of(body("text", "Yes", "correct", true),
                body("text", "No"))));
        }
        return body("format", "quizbuks.quiz", "formatVersion", 1, "exportedAt", "2026-11-09T00:00:00Z",
            "quiz", body("kind", "QUIZ", "title", title, "timeLimitSec", 300), "materials", List.of(),
            "questions", questions, "files", body());
    }

    private Map<String, Object> start(String token, String hash, String mode, int steps) {
        return run(PackageImportProcesses.START, token, body("packageHash", hash, "formatVersion", 2, "mode", mode,
            "batchSize", 2, "stepsTotal", steps));
    }

    private Map<String, Object> step(String token, Object importId, String step, String action, Object input) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("importId", importId);
        body.put("step", step);
        body.put(action, input);
        return run(PackageImportProcesses.STEP, token, body);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> result(Map<String, Object> stepOutput) {
        return (Map<String, Object>) stepOutput.get("result");
    }

    private static Map<String, Object> record(Object importId) {
        return query("SELECT * FROM qb_package_import WHERE import_id = ?", String.valueOf(importId)).getFirst();
    }

    private static long quizzesOf(String sponsor) {
        return ((Number) query("SELECT count(DISTINCT quiz_id) AS n FROM qb_quiz_version WHERE owner_id = ?", sponsor)
            .getFirst().get("n")).longValue();
    }

    private static List<String> stemsOf(Object quizId) {
        return latest("qb_question_version", "question_id", "quiz_id = '" + quizId + "'").stream()
            .filter(row -> !Boolean.TRUE.equals(row.get("is_deleted")))
            .sorted(java.util.Comparator.comparing(row -> ((Number) row.get("seq")).intValue()))
            .map(row -> String.valueOf(row.get("stem"))).toList();
    }

    /** The graph's step: two nodes named by their keys in the package. */
    private static Map<String, Object> treeStep() {
        return body("create", body("ownerKind", "SPONSOR", "name", name("Coffee")), "nodes", List.of(
            node(null, "src-basics", "Basics", all(passed())),
            node(null, "src-advanced", "Advanced", all(lit("src-basics", true), passed()))));
    }

    @Test
    @SuppressWarnings("unchecked")
    void aWholePackageImportsStepByStepAndGoingOnNeverDoesAStepTwice() {
        String sponsor = newSponsor(unique("s-fc4-whole"));
        String token = sponsorToken(sponsor);
        // A quiz draft of the account's own (F-C7's "about us") does not keep it from importing the whole package.
        newQuiz(token, "About us");
        Map<String, Object> started = start(token, HASH_A, "FULL", 6);
        Object importId = started.get("importId");
        assertThat(started).containsEntry("status", "RUNNING").containsEntry("resumed", false)
            .containsEntry("stepsDone", 0).containsEntry("batchSize", 2);

        // 1. The graph: created as a draft with new node ids, given by the package's keys.
        Map<String, Object> tree = step(token, importId, "tree:t1", "tree", treeStep());
        Map<String, String> nodeIds = (Map<String, String>) result(tree).get("nodeIds");
        assertThat(nodeIds).containsOnlyKeys("src-basics", "src-advanced");
        String treeId = (String) result(tree).get("treeId");
        assertThat(query("SELECT status, current_version_id FROM qb_tree WHERE tree_id = ?", treeId).getFirst())
            .containsEntry("status", "ACTIVE").containsEntry("current_version_id", null);

        // 2. The first version's content (a batch of two) with its topics; 3. a further batch; 4. the version.
        Map<String, Object> content = run(PackageImportProcesses.STEP, token, body("importId", importId,
            "step", "quiz:q1:v:v1:content", "quiz", body("document", quizFile("Coffee quiz", "One?", "Two?"),
                "files", body()), "quizTopics", body("topics", List.of(), "customTopics", List.of("coffee"))));
        String quizId = (String) result(content).get("quizId");
        Map<String, Object> batch = step(token, importId, "quiz:q1:v:v1:batch:2", "questions", body("quizId", quizId,
            "document", quizFile("Coffee quiz", "Three?")));
        assertThat(result(batch)).containsEntry("questions", 1);
        Map<String, Object> version = step(token, importId, "quiz:q1:v:v1:version", "version", body("quizId", quizId));
        String versionId = (String) result(version).get("versionId");
        assertThat(stemsOf(quizId)).containsExactly("One?", "Two?", "Three?");
        assertThat(String.valueOf(query("SELECT custom_topics FROM qb_quiz_version_version WHERE version_id = ?::uuid",
            versionId).getFirst().get("custom_topics"))).contains("coffee");

        // Going on after a break: a step done answers what it made and does nothing again.
        long quizzes = quizzesOf(sponsor);
        Map<String, Object> again = step(token, importId, "quiz:q1:v:v1:content", "quiz",
            body("document", quizFile("Coffee quiz", "One?", "Two?"), "files", body()));
        assertThat(again).containsEntry("repeated", true);
        assertThat(result(again)).containsEntry("quizId", quizId);
        assertThat(quizzesOf(sponsor)).isEqualTo(quizzes);
        Map<String, Object> resumed = start(token, HASH_A, "FULL", 6);
        assertThat(resumed).containsEntry("resumed", true).containsEntry("importId", importId)
            .containsEntry("stepsDone", 4);
        assertThat((Map<String, Object>) resumed.get("done")).containsKeys("tree:t1", "quiz:q1:v:v1:content",
            "quiz:q1:v:v1:batch:2", "quiz:q1:v:v1:version");

        // 5. The current content replaces the version's (intoQuizId); the version keeps its three questions.
        step(token, importId, "quiz:q1:c:content", "quiz", body("document", quizFile("Coffee quiz", "Current?"),
            "files", body(), "intoQuizId", quizId));
        assertThat(stemsOf(quizId)).containsExactly("Current?");
        assertThat(query("SELECT question_count FROM qb_quiz_version_version WHERE version_id = ?::uuid", versionId)
            .getFirst().get("question_count")).isEqualTo(new java.math.BigDecimal("3"));

        // 6. The publication: a draft on the version, bound to the imported node, with a topic of its own; the last
        // planned step finishes the import.
        Instant now = clock.instant();
        Map<String, Object> save = bound(versionId, "Coffee", treeId, nodeIds.get("src-basics"));
        save.put("startAt", now.plus(Duration.ofHours(1)).toString());
        Map<String, Object> publication = step(token, importId, "publication:1", "publication", body("save", save,
            "topics", body("customAdd", List.of("latte"))));
        assertThat(publication).containsEntry("status", "DONE");
        String publicationId = (String) result(publication).get("publicationId");
        Map<String, Object> row = row(publicationId);
        assertThat(row).containsEntry("status", "DRAFT").containsEntry("tree_id", treeId)
            .containsEntry("tree_node_id", nodeIds.get("src-basics"));
        assertThat(String.valueOf(row.get("topic_changes"))).contains("latte");
        assertThat(record(importId)).containsEntry("status", "DONE").containsEntry("mode", "FULL")
            .containsEntry("last_step", "publication:1");

        // A step after the last is refused; another Sponsor finds no such import.
        assertThat(codes(refused(PackageImportProcesses.STEP, token, body("importId", importId, "step", "late",
            "version", body("quizId", quizId)), 422))).containsExactly(PackageImportCodes.CLOSED);
        refused(PackageImportProcesses.ABANDON, sponsorToken(newSponsor(unique("s-fc4-other"))),
            body("importId", importId), 404);
    }

    @Test
    void anAccountThatSubmittedAPublicationImportsOnlyTheProfilePart() {
        String sponsor = newSponsor(unique("s-fc4-submitted"));
        String token = sponsorToken(sponsor);
        credit(sponsor, 20_000);
        submit(token, draft(token, version(token, "Submitted quiz"), "Submitted"));
        assertThat(codes(refused(PackageImportProcesses.START, token, body("packageHash", HASH_A, "formatVersion", 2,
            "mode", "FULL", "batchSize", 200, "stepsTotal", 3), 422))).containsExactly(PackageImportCodes.SUBMITTED);
        Map<String, Object> started = start(token, HASH_A, "PROFILE", 1);
        assertThat(started).containsEntry("mode", "PROFILE");
        // A profile-only import takes no other step.
        assertThat(codes(refused(PackageImportProcesses.STEP, token, body("importId", started.get("importId"), "step",
            "quiz:q1:c:content", "quiz", body("document", quizFile("No", "One?"), "files", body())), 422)))
            .containsExactly(PackageImportCodes.STEP_INVALID);
        Map<String, Object> profile = step(token, started.get("importId"), "profile", "profile", body("document",
            body("format", "quizbuks-profile", "schema", 1, "languages", List.of("en"),
                "basics", body("contactName", "Ann", "company", "Package Tea", "intro", body("en", "Tea.")),
                "products", List.of()), "files", body()));
        assertThat(profile).containsEntry("status", "DONE");
        assertThat(latest("qb_sponsor_version", "onboarding_id", "user_id = '" + sponsor + "'").getFirst())
            .containsEntry("company", "Package Tea");
    }

    @Test
    void oneImportIsOpenAtATimeAndTheSamePackageGoesOn() {
        String token = sponsorToken(newSponsor(unique("s-fc4-open")));
        Object importId = start(token, HASH_A, "FULL", 3).get("importId");
        assertThat(codes(refused(PackageImportProcesses.START, token, body("packageHash", HASH_B, "formatVersion", 2,
            "mode", "FULL", "batchSize", 2, "stepsTotal", 3), 422))).containsExactly(PackageImportCodes.OPEN);
        assertThat(start(token, HASH_A, "FULL", 3)).containsEntry("importId", importId).containsEntry("resumed", true);
        // A step naming two actions is refused, as one naming none.
        assertThat(codes(refused(PackageImportProcesses.STEP, token, body("importId", importId, "step", "x",
            "version", body("quizId", java.util.UUID.randomUUID()), "tree", treeStep()), 422)))
            .containsExactly(PackageImportCodes.STEP_INVALID);
        assertThat(codes(refused(PackageImportProcesses.STEP, token, body("importId", importId, "step", "x"), 422)))
            .containsExactly(PackageImportCodes.STEP_INVALID);
    }

    @Test
    @SuppressWarnings("unchecked")
    void theGraphGoesToReviewAndGivingUpRemovesWhatTheImportMade() {
        String sponsor = newSponsor(unique("s-fc4-abandon"));
        String token = sponsorToken(sponsor);
        Object importId = start(token, HASH_A, "FULL", 10).get("importId");
        Map<String, Object> tree = step(token, importId, "tree:t1", "tree", treeStep());
        String treeId = (String) result(tree).get("treeId");
        String quizId = (String) result(step(token, importId, "quiz:q1:v:v1:content", "quiz",
            body("document", quizFile("Tea quiz", "One?"), "files", body()))).get("quizId");
        String versionId = (String) result(step(token, importId, "quiz:q1:v:v1:version", "version",
            body("quizId", quizId))).get("versionId");
        Map<String, String> nodeIds = (Map<String, String>) result(tree).get("nodeIds");
        String publicationId = (String) result(step(token, importId, "publication:1", "publication", body("save",
            bound(versionId, "Tea", treeId, nodeIds.get("src-basics"))))).get("publicationId");

        // The imported graph is a draft reviewed as any: submitted, it waits for the operator's other member.
        Map<String, Object> treeRow = query("SELECT revision FROM qb_tree WHERE tree_id = ?", treeId).getFirst();
        Map<String, Object> submitted = run(TreeProcesses.SUBMIT, token, body("treeId", treeId, "baseRevision",
            treeRow.get("revision")));
        assertThat(submitted).containsEntry("status", "REVIEW");
        assertThat(submitted.get("approvalRequestId")).isNotNull();
        // A publication bound to it cannot be submitted before the graph is in effect (skipped by "submit all").
        assertThat(codes(refused("QB_PUBLICATION_SUBMIT", token, body("publicationId", publicationId), 422)))
            .contains("QB_PUBLICATION_NODE_UNKNOWN");

        Map<String, Object> abandoned = run(PackageImportProcesses.ABANDON, token, body("importId", importId));
        assertThat(abandoned).containsEntry("status", "ABANDONED");
        assertThat((List<Object>) abandoned.get("retiredTrees")).containsExactly(treeId);
        assertThat((List<Object>) abandoned.get("deletedPublications")).containsExactly(publicationId);
        assertThat((List<Object>) abandoned.get("removedQuizzes")).containsExactly(quizId);
        assertThat(query("SELECT status FROM qb_tree WHERE tree_id = ?", treeId).getFirst())
            .containsEntry("status", "RETIRED");
        assertThat(latest("qb_publication_version", "publication_id", "publication_id = '" + publicationId + "'")
            .getFirst().get("is_deleted")).isEqualTo(true);
        assertThat(latest("qb_quiz_version", "quiz_id", "quiz_id = '" + quizId + "'").getFirst())
            .containsEntry("removed", true);
        assertThat(record(importId)).containsEntry("status", "ABANDONED");
        assertThat(codes(refused(PackageImportProcesses.ABANDON, token, body("importId", importId), 422)))
            .containsExactly(PackageImportCodes.CLOSED);
        // A new import may start now.
        assertThat(start(token, HASH_B, "FULL", 1)).containsEntry("resumed", false);
    }

    @Test
    void intoQuizIdOnlyReplacesTheCallersOwnQuizWithoutPublications() {
        String sponsor = newSponsor(unique("s-fc4-into"));
        String token = sponsorToken(sponsor);
        String quizId = newQuiz(token, "Into");
        question(token, quizId, "Old?");
        Map<String, Object> out = run("QB_QUIZ_IMPORT", token, body("document", quizFile("Replaced", "New one?",
            "New two?"), "files", body(), "intoQuizId", quizId));
        assertThat(out).containsEntry("quizId", quizId);
        assertThat(stemsOf(quizId)).containsExactly("New one?", "New two?");
        assertThat(latest("qb_quiz_version", "quiz_id", "quiz_id = '" + quizId + "'").getFirst())
            .containsEntry("title", "Replaced").containsEntry("shuffle_questions", true);

        // F-S7: a file whose questions keep their order replaces the content with that setting, and back.
        Map<String, Object> fixed = quizFile("Fixed", "First?", "Second?");
        fixed.put("formatVersion", 3);
        @SuppressWarnings("unchecked")
        Map<String, Object> heading = (Map<String, Object>) fixed.get("quiz");
        heading.put("shuffleQuestions", false);
        run("QB_QUIZ_IMPORT", token, body("document", fixed, "files", body(), "intoQuizId", quizId));
        assertThat(latest("qb_quiz_version", "quiz_id", "quiz_id = '" + quizId + "'").getFirst())
            .containsEntry("shuffle_questions", false);
        run("QB_QUIZ_IMPORT", token, body("document", quizFile("Shuffled", "One?"), "files", body(),
            "intoQuizId", quizId));
        assertThat(latest("qb_quiz_version", "quiz_id", "quiz_id = '" + quizId + "'").getFirst())
            .containsEntry("shuffle_questions", true);

        // Another's quiz: not found.
        String other = sponsorToken(newSponsor(unique("s-fc4-into-other")));
        refused("QB_QUIZ_IMPORT", other, body("document", quizFile("Mine", "One?"), "files", body(),
            "intoQuizId", quizId), 404);

        // A quiz with a publication (a draft too): refused.
        String versionId = (String) run("QB_QUIZ_PUBLISH_VERSION", token, body("quizId", quizId)).get("versionId");
        draft(token, versionId, "Into's draft");
        assertThat(codes(refused("QB_QUIZ_IMPORT", token, body("document", quizFile("Again", "One?"), "files", body(),
            "intoQuizId", quizId), 422))).containsExactly(ContentCodes.IMPORT_HAS_PUBLICATIONS);
    }

    @Test
    void recordsPastTheirRetentionAreDeleted() {
        String old = sponsorToken(newSponsor(unique("s-fc4-old")));
        String fresh = sponsorToken(newSponsor(unique("s-fc4-fresh")));
        Object oldId = start(old, HASH_A, "FULL", 1).get("importId");
        Object freshId = start(fresh, HASH_A, "FULL", 1).get("importId");
        execute("UPDATE qb_package_import SET updated_at = ?::timestamptz WHERE import_id = ?",
            clock.instant().minus(Duration.ofDays(91)).toString(), String.valueOf(oldId));
        assertThat(jobRunner.run(expireJob, clock.instant())).isEqualTo(JobRunner.Outcome.SUCCEEDED);
        assertThat(query("SELECT 1 FROM qb_package_import WHERE import_id = ?", String.valueOf(oldId))).isEmpty();
        assertThat(query("SELECT 1 FROM qb_package_import WHERE import_id = ?", String.valueOf(freshId))).hasSize(1);
    }
}
