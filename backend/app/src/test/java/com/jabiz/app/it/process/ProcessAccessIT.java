package com.jabiz.app.it.process;

import com.jabiz.context.RequestContext;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.runtime.PermissionDeniedException;
import com.jabiz.runtime.file.GeneratedFileProcesses;
import com.jabiz.runtime.process.ExecutionOptions;
import com.jabiz.runtime.process.ProcessExecutor;
import com.jabiz.runtime.process.ProcessInternalException;
import com.jabiz.runtime.process.ProcessRegistry;
import com.jabiz.runtime.process.ProcessResult;
import com.jabiz.runtime.process.steps.CallProcess;
import com.jabiz.runtime.security.JwtService;
import com.jabiz.runtime.test.PostgresIntegrationTest;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Who may start a process (decision D41): {@code internal()} processes run as the system only, also as sub-processes;
 * {@code entryOnly()} ones only from their platform entry point, as the real caller; {@code subProcessOnly()} ones
 * only under another process. An administrator holding every permission is refused like anyone else, and none of
 * them is in the process catalog.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class ProcessAccessIT extends PostgresIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST =
        new ParameterizedTypeReference<>() {};

    record Note(String text) {}

    @TestConfiguration
    static class Processes {

        /** A hand-off point, as an event consumer's or a job's process is. */
        @Bean
        ProcessDefinition<Note, Note, ProcessContext> itHandOffProcess() {
            return ProcessDefinition.single("IT_D41_HAND_OFF", 1, Note.class, Note.class, (in, ctx) -> in)
                .withPermissions("it.d41").asInternal();
        }

        /** An ordinary process that passes its note on to the hand-off point. */
        @Bean
        ProcessDefinition<Note, Note, ProcessContext> itRelayProcess() {
            return ProcessDefinition.define("IT_D41_RELAY", 1, Note.class, Note.class, ProcessContext.class, pb -> pb
                .permissions("it.d41")
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put("in", input);
                    return ctx;
                })
                .outputMapper(ctx -> ctx.get("out", Note.class))
                .step("Hand off", CallProcess.<ProcessContext>of("IT_D41_HAND_OFF", 1,
                    ctx -> ctx.get("in", Note.class), "out")));
        }

        /** Run by a platform entry point of its own, as the caller. */
        @Bean
        ProcessDefinition<Note, Note, ProcessContext> itEntryProcess() {
            return ProcessDefinition.define("IT_D41_ENTRY", 1, Note.class, Note.class, ProcessContext.class, pb -> pb
                .permissions("it.d41")
                .entryOnly()
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put("in", input);
                    return ctx;
                })
                .outputMapper(ctx -> ctx.get("in", Note.class))
                .compute("Note", (metadata, ctx) -> { }));
        }
    }

    @Autowired
    ApplicationContext context;

    @Autowired
    JwtService tokens;

    @Autowired
    ProcessExecutor executor;

    @Autowired
    ProcessRegistry registry;

    WebTestClient client;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToApplicationContext(context).build();
    }

    @Test
    void anAdministratorHoldingEveryPermissionCannotRunInternalOrEntryOnlyProcessesThroughTheProcessApi() {
        for (String process : List.of("IT_D41_HAND_OFF", "MAIL_SEND", "INBOUND_RECEIVE", "WEBHOOK_DELIVER",
            "SEC_BOOTSTRAP_ADMIN", "IT_D41_ENTRY", "ADD_ENTITY", "UPDATE_ENTITY", "DELETE_ENTITY", "SPONSOR_SIGN_IN",
            "SPONSOR_MFA_VERIFY", "SPONSOR_OIDC_SIGN_IN", "SPONSOR_SIGN_UP", "SEC_MFA_ENROLL_BEGIN",
            "SEC_USER_SET_LOCALE", "SEC_MAIL_PREFERENCE_SET", "FILE_REGISTER", "IMPORT_RUN",
            GeneratedFileProcesses.ARCHIVE)) {
            Map<String, Object> problem = run(process, Map.of(), admin()).expectStatus().isForbidden()
                .expectBody(MAP).returnResult().getResponseBody();
            assertThat(String.valueOf(problem.get("violations"))).as(process).contains("PROCESS_INTERNAL")
                .contains(process);
        }
        // Not in the catalog either; the ordinary process is.
        List<Map<String, Object>> catalog = client.get().uri("/api/meta/processes")
            .header(HttpHeaders.AUTHORIZATION, admin()).exchange().expectStatus().isOk().expectBody(LIST)
            .returnResult().getResponseBody();
        assertThat(catalog).extracting(entry -> entry.get("name"))
            .contains("IT_D41_RELAY")
            .doesNotContain("IT_D41_HAND_OFF", "IT_D41_ENTRY", "ADD_ENTITY", "MAIL_SEND", "SPONSOR_SIGN_IN",
                GeneratedFileProcesses.ARCHIVE);
        // Every declared process that is not listed is one of the three kinds.
        assertThat(registry.all()).filteredOn(definition -> !definition.listed())
            .allSatisfy(definition -> assertThat(definition.actsOn()).isNull());
    }

    @Test
    void theEntityApiRunsTheGenericEntityProcessAsTheCaller() {
        String id = "att-" + UUID.randomUUID();
        client.post().uri("/api/entities/ItAttachment").header(HttpHeaders.AUTHORIZATION,
                bearer("it-d41-writer", "it.read", "it.write", "entity.write"))
            .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("attachmentId", id, "title", "kept"))
            .exchange().expectStatus().isCreated();

        assertThat(query("SELECT o.process_name, o.actor_id FROM sys_audit_record a "
            + "JOIN op_process o ON o.process_seq_id = a.process_seq_id WHERE a.entity_id = ?", id))
            .singleElement().satisfies(row -> assertThat(row).containsEntry("process_name", "ADD_ENTITY")
                .containsEntry("actor_id", "it-d41-writer"));
        assertThat(query("SELECT actor_id FROM sys_audit_record WHERE entity_id = ?", id))
            .singleElement().satisfies(row -> assertThat(row).containsEntry("actor_id", "it-d41-writer"));
    }

    @Test
    void anEntryOnlyProcessRunsOnlyWithItsOwnEntryAndAsTheCaller() {
        ProcessDefinition<Note, Note, ProcessContext> entry = process("IT_D41_ENTRY");
        RequestContext user = new RequestContext("it-d41-user", null, Locale.ENGLISH, "it", Set.of(), Set.of());

        assertThatThrownBy(() -> asRequest(user, executor.run(entry, new Note("a"), ExecutionOptions.NONE)).block())
            .isInstanceOf(ProcessInternalException.class);
        // The entry option of another process does not open this one; the system needs the entry as well.
        assertThatThrownBy(() -> asRequest(user, executor.run(entry, new Note("b"),
            ExecutionOptions.NONE.atEntryOf(process("IT_D41_RELAY")))).block())
            .isInstanceOf(ProcessInternalException.class);
        assertThatThrownBy(() -> asRequest(system(), executor.execute(entry, new Note("c"))).block())
            .isInstanceOf(ProcessInternalException.class);

        ProcessResult<Note> result = asRequest(user, executor.run(entry, new Note("d"),
            ExecutionOptions.NONE.atEntryOf(entry))).block();
        assertThat(result.output()).isEqualTo(new Note("d"));
        assertThat(query("SELECT actor_id FROM op_process WHERE process_seq_id = ?", result.processSeqId()))
            .singleElement().satisfies(row -> assertThat(row).containsEntry("actor_id", "it-d41-user"));
    }

    @Test
    void anInternalSubProcessRunsOnlyUnderAProcessTheSystemRuns() {
        int before = query("SELECT process_seq_id FROM op_process WHERE process_name = 'IT_D41_RELAY'").size();

        // An administrator holding every permission cannot reach the hand-off point through an ordinary process.
        Map<String, Object> problem = run("IT_D41_RELAY", Map.of("text", "forged"), admin()).expectStatus()
            .isForbidden().expectBody(MAP).returnResult().getResponseBody();
        assertThat(String.valueOf(problem.get("violations"))).contains("PROCESS_INTERNAL").contains("IT_D41_HAND_OFF");
        assertThat(query("SELECT process_seq_id FROM op_process WHERE process_name = 'IT_D41_RELAY'")).hasSize(before);

        // The system runs the hand-off point directly and through the ordinary process.
        assertThat(asRequest(system(), executor.execute(process("IT_D41_HAND_OFF"), new Note("direct"))).block())
            .isEqualTo(new Note("direct"));
        assertThat(asRequest(system(), executor.execute(process("IT_D41_RELAY"), new Note("relayed"))).block())
            .isEqualTo(new Note("relayed"));
        assertThat(query("SELECT c.actor_id FROM op_process c JOIN op_process p ON p.process_seq_id = c.parent_seq_id "
            + "WHERE c.process_name = 'IT_D41_HAND_OFF' AND p.process_name = 'IT_D41_RELAY'"))
            .singleElement().satisfies(row -> assertThat(row).containsEntry("actor_id", RequestContext.SYSTEM_ACTOR));
    }

    @Test
    void aSubProcessOnlyProcessNeverRunsOnItsOwnEvenAsTheSystem() {
        ProcessDefinition<GeneratedFileProcesses.ArchiveInput, ?, ?> archive = process(GeneratedFileProcesses.ARCHIVE);
        GeneratedFileProcesses.ArchiveInput input = new GeneratedFileProcesses.ArchiveInput("x.txt", "text/plain",
            new byte[] {1}, List.of("it.bank.file"), null, null);
        assertThatThrownBy(() -> asRequest(system(), executor.execute(archive, input)).block())
            .isInstanceOf(ProcessInternalException.class)
            .isInstanceOf(PermissionDeniedException.class);
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private <I, O, C extends ProcessContext> ProcessDefinition<I, O, C> process(String name) {
        return (ProcessDefinition<I, O, C>) registry.findLatest(name).orElseThrow();
    }

    private static RequestContext system() {
        return RequestContext.system(Locale.ENGLISH, "it-d41");
    }

    private WebTestClient.ResponseSpec run(String name, Object input, String token) {
        return client.post().uri("/api/processes/" + name + "/latest").header(HttpHeaders.AUTHORIZATION, token)
            .contentType(MediaType.APPLICATION_JSON).bodyValue(input).exchange();
    }

    private String admin() {
        return TestTokens.bearer(tokens, "it-d41-admin", "*");
    }

    private String bearer(String actor, String... permissions) {
        return TestTokens.bearer(tokens, actor, permissions);
    }
}
