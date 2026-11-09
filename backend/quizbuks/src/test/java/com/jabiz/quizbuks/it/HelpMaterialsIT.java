package com.jabiz.quizbuks.it;

import com.jabiz.job.JobDefinition;
import com.jabiz.quizbuks.help.HelpCodes;
import com.jabiz.quizbuks.help.HelpManualDrafts;
import com.jabiz.quizbuks.help.HelpMaterialsCheck;
import com.jabiz.quizbuks.help.HelpMaterialsJob;
import com.jabiz.quizbuks.help.HelpMaterialsProcesses;
import com.jabiz.quizbuks.help.QbHelp;
import com.jabiz.quizbuks.help.QbHelpMaterials;
import com.jabiz.quizbuks.messaging.QbLegal;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.quizbuks.setup.QbRoles;
import com.jabiz.runtime.check.CheckProblem;
import com.jabiz.runtime.test.StubHttpServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-C10b (docs/quizbuks/plans/impl/F-C10.md §6): the AI help's materials. {@code QB_SETUP} creates the manuals' drafts
 * from the repository; a content administrator edits and publishes them (nobody else); publishing builds a snapshot per
 * entry and language, stored as a file by the job {@value HelpMaterialsJob#TYPE}; each question's job reads its entry's
 * and language's snapshot as its file and sends it as a cached system block (the user's words never in it); editing and
 * publishing again changes what the next question reads; materials above {@code qb.help.manual-max-bytes} are refused
 * at publishing, never stored by a build, and fail the startup check; the cache's tokens are counted.
 */
class HelpMaterialsIT extends HelpItSupport {

    @Autowired
    HelpMaterialsCheck materialsCheck;

    @Autowired
    @Qualifier("qbHelpMaterialsJobSchedule")
    JobDefinition<HelpMaterialsProcesses.BuildInput> buildJob;

    @BeforeEach
    void on() {
        helpOn();
    }

    String helpContent() {
        return tokenOf("help-content-admin", QbRoles.ADMIN_CONTENT);
    }

    static Map<String, Object> manual(String entry) {
        return latest("qb_help_manual_version", "manual_id", "entry = '" + entry + "' AND NOT is_deleted").getFirst();
    }

    static Map<String, Object> material(String entry, String language) {
        return query("SELECT * FROM qb_help_material WHERE entry = ? AND language = ?", entry, language).getFirst();
    }

    WebTestClient.ResponseSpec editManualAs(String token, String entry, Map<String, Object> body) {
        Map<String, Object> current = manual(entry);
        Map<String, Object> change = new LinkedHashMap<>();
        change.put("action", "UPDATE");
        change.put("id", String.valueOf(current.get("manual_id")));
        change.put("version", ((Number) current.get("version_no")).longValue());
        change.put("attributes", Map.of("body", body));
        return client.post().uri("/api/datasets/" + QbHelpMaterials.defaultDataset(QbHelpMaterials.MANUAL)
                + "/commit").contentType(MediaType.APPLICATION_JSON).header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(Map.of("changes", List.of(change))).exchange();
    }

    Map<String, Object> publishInput(String entry) {
        Map<String, Object> current = manual(entry);
        return body("manualId", String.valueOf(current.get("manual_id")), "baseRevision",
            ((Number) current.get("version_no")).longValue());
    }

    /** Edits the entry's manual (each language marked), publishes it and stores the snapshots. */
    Map<String, Object> publishManual(String entry, String mark) {
        editManualAs(helpContent(), entry, Map.of("zh", "手册 " + mark + "-zh", "ja", "マニュアル " + mark + "-ja",
            "en", "Manual " + mark + "-en")).expectStatus().isOk();
        Map<String, Object> output = run(HelpMaterialsProcesses.PUBLISH, helpContent(), publishInput(entry));
        storeSnapshots();
        return output;
    }

    /** Delivers the rebuild's event and runs the jobs that store the snapshots. */
    void storeSnapshots() {
        deliverEvents();
        runJobs();
    }

    Map<String, Object> askIn(String token, String entry, String language, String text) {
        Map<String, Object> input = askInput(null, entry, text, null);
        input.put("language", language);
        return run(com.jabiz.quizbuks.help.HelpProcesses.ASK, token, input);
    }

    /** The system blocks the latest help question sent. */
    static JsonNode lastSystem() {
        List<JsonNode> requests = helpRequests();
        return requests.getLast().get("system");
    }

    static long materialsJobs() {
        return ((Number) query("SELECT count(*) AS n FROM sys_external_job WHERE job_type = ?", HelpMaterialsJob.TYPE)
            .getFirst().get("n")).longValue();
    }

    // ---- the drafts and who may publish ------------------------------------------------------------------------------

    @Test
    void setupCreatesTheDraftsFromTheRepositoryAndOnlyContentAdministratorsEditAndPublish() {
        for (String entry : QbHelp.ENTRIES) {
            // The first version, as QB_SETUP made it (the class's other tests edit them).
            Map<String, Object> first = query("SELECT * FROM qb_help_manual_version WHERE entry = ? AND version_no = 1",
                entry).getFirst();
            assertThat(first.get("published_body")).isNull();
            assertThat(first.get("source_hash")).isEqualTo(HelpManualDrafts.hash(HelpManualDrafts.of(entry)));
            assertThat(String.valueOf(first.get("body"))).contains(QbHelp.APP.equals(entry) ? "/taker/help"
                : "/sponsor/help");
        }
        String taker = taker(newTaker("t-fc10b-perm-" + UUID.randomUUID(), "JP"));
        refused(HelpMaterialsProcesses.PUBLISH, taker, publishInput(QbHelp.APP), 403);
        refused(HelpMaterialsProcesses.PUBLISH, sponsorToken(SPONSOR_A), publishInput(QbHelp.APP), 403);
        refused(HelpMaterialsProcesses.LOAD, taker, body("manualId", publishInput(QbHelp.APP).get("manualId"),
            "baseRevision", publishInput(QbHelp.APP).get("baseRevision")), 403);
        editManualAs(taker, QbHelp.APP, Map.of("en", "x")).expectStatus().isForbidden();
        // The published text and its time are the process's only.
        Map<String, Object> current = manual(QbHelp.APP);
        client.post().uri("/api/datasets/" + QbHelpMaterials.defaultDataset(QbHelpMaterials.MANUAL) + "/commit")
            .contentType(MediaType.APPLICATION_JSON).header(HttpHeaders.AUTHORIZATION, helpContent())
            .bodyValue(Map.of("changes", List.of(Map.of("action", "UPDATE", "id", String.valueOf(current.get(
                "manual_id")), "version", ((Number) current.get("version_no")).longValue(), "attributes",
                Map.of("publishedBody", Map.of("en", "forged")))))).exchange().expectStatus().is4xxClientError();
        // The snapshots' rows are the processes' only; the system's processes are not for callers.
        refusedAsInternal(HelpMaterialsProcesses.BUILD, helpContent(), body("scheduledTime", null));
    }

    // ---- publishing builds the snapshots, the questions read them -----------------------------------------------------

    @Test
    void publishingStoresASnapshotPerEntryAndLanguageWhichEachQuestionReadsAsACachedBlock() {
        Map<String, Object> published = publishManual(QbHelp.APP, "M1");
        assertThat(published).containsEntry("entry", QbHelp.APP);
        assertThat((List<?>) published.get("sizes")).hasSize(6);
        for (String entry : QbHelp.ENTRIES) {
            for (String language : List.of("zh", "ja", "en")) {
                Map<String, Object> row = material(entry, language);
                assertThat(row.get("file_id")).as(entry + "/" + language).isNotNull();
                assertThat(row.get("content_hash")).isEqualTo(row.get("latest_hash"));
                assertThat(row.get("pending_hash")).isNull();
                Map<String, Object> file = query("SELECT * FROM sys_file WHERE file_id = ?", row.get("file_id"))
                    .getFirst();
                assertThat(file).containsEntry("policy", QbHelpMaterials.POLICY).containsEntry("content_type",
                    "text/plain").containsEntry("uploaded_by", "system");
                assertThat(new BigDecimal(file.get("size_bytes").toString())).isEqualByComparingTo(
                    new BigDecimal(row.get("bytes").toString()));
            }
        }
        assertThat(String.valueOf(manual(QbHelp.APP).get("published_body"))).contains("M1-zh", "M1-ja", "M1-en");

        String taker = taker(newTaker("t-fc10b-ask-" + UUID.randomUUID(), "JP"));
        Map<String, Object> asked = askIn(taker, QbHelp.APP, "zh", "我的问题 Q-SECRET-1");
        Object fileId = material(QbHelp.APP, "zh").get("file_id");
        // The job reads the snapshot as its file; its input does not copy it.
        String jobId = jobOf(asked.get("threadId"), asked.get("messageId"));
        assertThat(query("SELECT file_id, policy FROM sys_external_job_file WHERE job_id = ? AND direction = 'INPUT'",
            jobId)).singleElement().satisfies(f -> assertThat(f).containsEntry("file_id", String.valueOf(fileId))
            .containsEntry("policy", QbHelpMaterials.POLICY));
        String jobInput = String.valueOf(query("SELECT input FROM sys_external_job WHERE job_id = ?", jobId)
            .getFirst().get("input"));
        assertThat(jobInput).doesNotContain("M1-zh");
        // The reservation counts the snapshot's size (a token per three bytes at least).
        assertThat(number(message(asked.get("messageId")).get("reserved_input_tokens")))
            .isGreaterThan(number(material(QbHelp.APP, "zh").get("bytes")) / 3);
        runJobs();

        JsonNode system = lastSystem();
        assertThat(system.isArray()).isTrue();
        JsonNode materials = system.get(system.size() - 1);
        assertThat(materials.get("cache_control").get("type").asString()).isEqualTo("ephemeral");
        assertThat(materials.get("text").asString()).startsWith("<help_materials>").contains("M1-zh",
            "# Help materials for Takers (zh)").doesNotContain("M1-en", "M1-ja");
        // Nothing of the user in the cached blocks; the question only in the user's message.
        assertThat(system.toString()).doesNotContain("Q-SECRET-1");
        assertThat(userText(helpRequests().getLast())).contains("Q-SECRET-1").doesNotContain("M1-zh");
        assertThat(message(asked.get("messageId"))).containsEntry("job_status", QbHelp.ANSWERED);

        // A Sponsor in Japanese reads the Sponsors' snapshot, never the Takers' manual.
        askIn(sponsorToken(SPONSOR_A), QbHelp.SPONSOR, "ja", "質問");
        runJobs();
        String sponsorMaterials = lastSystem().get(lastSystem().size() - 1).get("text").asString();
        assertThat(sponsorMaterials).contains("# Help materials for Sponsors (ja)", "/sponsor/legal/sponsor-terms")
            .doesNotContain("M1-");
    }

    @Test
    void editingAndPublishingAgainChangesWhatTheNextQuestionReadsAndAnUnchangedBuildStoresNothing() {
        publishManual(QbHelp.APP, "E1");
        Object before = material(QbHelp.APP, "en").get("file_id");
        String taker = taker(newTaker("t-fc10b-edit-" + UUID.randomUUID(), "JP"));
        askIn(taker, QbHelp.APP, "en", "First?");
        runJobs();
        assertThat(lastSystem().toString()).contains("E1-en");

        publishManual(QbHelp.APP, "E2");
        assertThat(material(QbHelp.APP, "en").get("file_id")).isNotEqualTo(before);
        askIn(taker, QbHelp.APP, "en", "Second?");
        runJobs();
        assertThat(lastSystem().toString()).contains("E2-en").doesNotContain("E1-en");

        // An edited FAQ (no manual published): rebuilt on asking for it, without a manual.
        long jobs = materialsJobs();
        commit(QbLegal.defaultDataset(QbLegal.FAQ), "INSERT", null, null, Map.of("audience", QbLegal.AUDIENCE_APP,
            "category", "REWARDS", "question", Map.of("en", "FAQ-NEW question?"), "answer", Map.of("en",
            "FAQ-NEW answer."), "seq", 900, "published", true), null);
        Map<String, Object> refreshed = run(HelpMaterialsProcesses.PUBLISH, helpContent(), Map.of());
        assertThat(refreshed.get("manualId")).isNull();
        storeSnapshots();
        // The Takers' three snapshots changed; the Sponsors' did not.
        assertThat(materialsJobs()).isEqualTo(jobs + 3);
        askIn(taker, QbHelp.APP, "en", "Third?");
        runJobs();
        assertThat(lastSystem().toString()).contains("FAQ-NEW question?", "FAQ-NEW answer.", "E2-en");

        // Nothing changed: the hourly build stores nothing and writes nothing.
        long version = ((Number) material(QbHelp.SPONSOR, "en").get("version")).longValue();
        runDaily(buildJob);
        assertThat(materialsJobs()).isEqualTo(jobs + 3);
        assertThat(((Number) material(QbHelp.SPONSOR, "en").get("version")).longValue()).isEqualTo(version);
    }

    // ---- refusals and the size limit ------------------------------------------------------------------------------

    @Test
    void publishingNeedsEveryLanguageAndRefusesMaterialsAboveTheLimitWhichTheBuildNeverStores() {
        publishManual(QbHelp.SPONSOR, "S1");
        editManualAs(helpContent(), QbHelp.SPONSOR, Map.of("ja", "only Japanese", "en", "and English"))
            .expectStatus().isOk();
        assertThat(codes(refused(HelpMaterialsProcesses.PUBLISH, helpContent(), publishInput(QbHelp.SPONSOR), 422)))
            .containsExactly(HelpCodes.MANUAL_LANGUAGE);
        Map<String, Object> stale = publishInput(QbHelp.SPONSOR);
        stale.put("baseRevision", ((Number) stale.get("baseRevision")).longValue() - 1);
        assertThat(codes(refused(HelpMaterialsProcesses.PUBLISH, helpContent(), stale, 422)))
            .containsExactly(HelpCodes.MANUAL_CHANGED);

        String big = "x".repeat(5_000);
        editManualAs(helpContent(), QbHelp.SPONSOR, Map.of("zh", big, "ja", big, "en", big)).expectStatus().isOk();
        // Below what every snapshot is now.
        setParam(QbParams.HELP_MANUAL_MAX_BYTES, "200");
        try {
            Map<String, Object> problem = refused(HelpMaterialsProcesses.PUBLISH, helpContent(),
                publishInput(QbHelp.SPONSOR), 422);
            assertThat(codes(problem)).hasSize(3).containsOnly(HelpCodes.MATERIALS_TOO_LARGE);
            assertThat(String.valueOf(manual(QbHelp.SPONSOR).get("published_body"))).contains("S1-en")
                .doesNotContain(big);

            // A build above the limit keeps the snapshot in use and fails the startup check.
            Object inUse = material(QbHelp.SPONSOR, "en").get("file_id");
            long jobs = materialsJobs();
            runDaily(buildJob);
            assertThat(materialsJobs()).isEqualTo(jobs);
            assertThat(material(QbHelp.SPONSOR, "en").get("file_id")).isEqualTo(inUse);
            assertThat(number(material(QbHelp.SPONSOR, "en").get("latest_bytes"))).isGreaterThan(200);
            List<CheckProblem> problems = materialsCheck.check();
            assertThat(problems).isNotEmpty().allSatisfy(p -> assertThat(p.severity())
                .isEqualTo(CheckProblem.Severity.ERROR));
            assertThat(problems).anySatisfy(p -> assertThat(p.location()).contains("sponsor/en"));
        } finally {
            setParam(QbParams.HELP_MANUAL_MAX_BYTES, QbParams.defaultOf(QbParams.HELP_MANUAL_MAX_BYTES));
        }
        runDaily(buildJob);
        storeSnapshots();
        assertThat(materialsCheck.check()).isEmpty();
    }

    // ---- loading the repository's manual ---------------------------------------------------------------------------

    @Test
    void loadingTheRepositorysManualNeverLosesEditsUnlessAskedTo() {
        editManualAs(helpContent(), QbHelp.APP, Map.of("zh", "改过", "ja", "編集", "en", "edited here"))
            .expectStatus().isOk();
        Map<String, Object> load = publishInput(QbHelp.APP);
        assertThat(codes(refused(HelpMaterialsProcesses.LOAD, helpContent(), load, 422)))
            .containsExactly(HelpCodes.MANUAL_EDITED);
        // Merged by hand: the draft is kept and recorded as holding the repository's text.
        load.put("keepDraft", true);
        assertThat(run(HelpMaterialsProcesses.LOAD, helpContent(), load)).containsEntry("replaced", false);
        String repository = HelpManualDrafts.hash(HelpManualDrafts.of(QbHelp.APP));
        assertThat(manual(QbHelp.APP)).containsEntry("source_hash", repository);
        assertThat(String.valueOf(manual(QbHelp.APP).get("body"))).contains("edited here");
        // Replaced when asked.
        Map<String, Object> replace = publishInput(QbHelp.APP);
        replace.put("replace", true);
        assertThat(run(HelpMaterialsProcesses.LOAD, helpContent(), replace)).containsEntry("replaced", true);
        assertThat(String.valueOf(manual(QbHelp.APP).get("body"))).doesNotContain("edited here")
            .contains("/taker/help");
        // A draft that is the repository's text is loaded again without being asked.
        assertThat(run(HelpMaterialsProcesses.LOAD, helpContent(), publishInput(QbHelp.APP)))
            .containsEntry("replaced", true);
    }

    // ---- the prompt cache's tokens -----------------------------------------------------------------------------------

    @Test
    void theMonthlyCapCountsCacheReadsByTheirWeight() {
        String taker = taker(newTaker("t-fc10b-cap-" + UUID.randomUUID(), "JP"));
        askIn(taker, QbHelp.APP, "en", "Warm up?");
        runJobs();
        Map<String, Object> everybody = helpUsage(QbHelp.EVERYBODY);
        long in = number(everybody.get("input_tokens"));
        long out = number(everybody.get("output_tokens"));
        // Pretend most of the month's input was read from the cache (the outcomes write it so).
        long read = in - 1;
        execute("UPDATE qb_help_usage SET cache_read_tokens = ? WHERE usage_id = ?", BigDecimal.valueOf(read),
            everybody.get("usage_id"));
        long weighted = in - read + (read + 9) / 10 + out;
        // Below the cap by its weighted count, though the raw tokens are above it: asked.
        param(QbParams.HELP_GLOBAL_MONTHLY_TOKENS, String.valueOf(weighted + 1));
        try {
            askIn(taker, QbHelp.APP, "en", "Within the cap?");
            // A cache read counting fully: the raw tokens reach the cap, refused.
            param(QbParams.HELP_CACHE_READ_WEIGHT, "1");
            assertThat(codes(refused(com.jabiz.quizbuks.help.HelpProcesses.ASK, taker, askIn("en", "Over?"), 422)))
                .containsExactly(HelpCodes.MONTHLY_LIMIT);
        } finally {
            param(QbParams.HELP_CACHE_READ_WEIGHT, "0.10");
            param(QbParams.HELP_GLOBAL_MONTHLY_TOKENS, "20000000");
        }
        runJobs();
    }

    Map<String, Object> askIn(String language, String text) {
        Map<String, Object> input = askInput(null, QbHelp.APP, text, null);
        input.put("language", language);
        return input;
    }

    @Test
    void theCachesTokensAreCountedApartAndTheirWritesPricedAbove() {
        String takerId = newTaker("t-fc10b-cache-" + UUID.randomUUID(), "JP");
        String taker = taker(takerId);
        answering = request -> {
            JsonNode body = JSON.readTree(request.body());
            if (!userText(body).contains("<help_question>")) {
                return null;
            }
            Map<String, Object> usage = Map.of("input_tokens", 100, "cache_creation_input_tokens", 2_000,
                "cache_read_input_tokens", 30_000, "output_tokens", 50);
            return StubHttpServer.Answer.json(200, JSON.writeValueAsString(Map.of("id", "msg", "type", "message",
                "role", "assistant", "model", body.get("model").asString(), "stop_reason", "end_turn",
                "content", List.of(Map.of("type", "text", "text", ANSWER)), "usage", usage)));
        };
        Map<String, Object> before = helpUsage(QbHelp.EVERYBODY);
        askIn(taker, QbHelp.APP, "en", "Cache?");
        runJobs();
        Map<String, Object> mine = helpUsage(takerId);
        assertThat(number(mine.get("input_tokens"))).isEqualTo(32_100);
        assertThat(number(mine.get("cache_write_tokens"))).isEqualTo(2_000);
        assertThat(number(mine.get("cache_read_tokens"))).isEqualTo(30_000);
        // Haiku: every input token at $0.10 a million and the output at $0.50, and a quarter more for the writes.
        assertThat(new BigDecimal(mine.get("cost_usd").toString())).isEqualByComparingTo("0.003285");
        Map<String, Object> everybody = helpUsage(QbHelp.EVERYBODY);
        assertThat(number(everybody.get("cache_read_tokens")) - number(before.get("cache_read_tokens")))
            .isEqualTo(30_000);
    }
}
