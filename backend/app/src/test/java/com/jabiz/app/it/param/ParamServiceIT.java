package com.jabiz.app.it.param;

import com.jabiz.app.it.fixture.SqlStatementLog;
import com.jabiz.context.RequestContext;
import com.jabiz.entity.ValidationException;
import com.jabiz.entity.Violation;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.runtime.BusinessRuleViolationException;
import com.jabiz.runtime.DatasetEntityManager;
import com.jabiz.runtime.EntityAction;
import com.jabiz.runtime.EntityChange;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.RebaseConflictException;
import com.jabiz.runtime.dataset.DatasetRegistry;
import com.jabiz.runtime.param.ParamEntities;
import com.jabiz.runtime.param.ParamProcesses;
import com.jabiz.runtime.param.ParamService;
import com.jabiz.runtime.process.ProcessExecutor;
import com.jabiz.runtime.security.JwtService;
import com.jabiz.runtime.test.PostgresIntegrationTest;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Business parameters (docs/design/04-temporal-append-only.md section 9, ROADMAP phase 8 items 1 and 2): values by
 * the time they are in effect, the maintenance processes, the kind check on every write path, permissions and the
 * append-only table. Keys are unique per test: parameters are temporal and cannot be cleaned up.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = "it.sql-log.enabled=true")
class ParamServiceIT extends PostgresIntegrationTest {

    private static final RequestContext ADMIN = new RequestContext("it-admin", null, Locale.ENGLISH, "it-param",
        Set.of(), Set.of("*"));
    private static final Map<String, Object> RATE = Map.of("type", "numeric", "precision", 5, "scale", 4);
    private static final Pattern MUTATION = Pattern.compile("^\\s*(UPDATE|DELETE|TRUNCATE)\\b",
        Pattern.CASE_INSENSITIVE);

    @Autowired
    ProcessExecutor executor;

    @Autowired
    ParamService params;

    @Autowired
    DatasetEntityManager entities;

    @Autowired
    DatasetRegistry datasets;

    @Autowired
    ApplicationContext context;

    @Autowired
    JwtService tokens;

    @Test
    void valuesFollowTheirEffectiveTimesWithoutAnyJob() {
        String key = key();
        run(ParamProcesses.CREATE, new ParamProcesses.CreateInput(key, RATE, "0.1", "rate"));
        Instant switchover = clock.instant().plus(Duration.ofDays(1));
        ParamProcesses.ParamOutput scheduled = run(ParamProcesses.SCHEDULE,
            new ParamProcesses.ScheduleInput(key, new BigDecimal("0.15"), switchover));
        assertThat(scheduled.value()).isEqualTo("0.1500");
        assertThat(scheduled.effectiveTime()).isEqualTo(switchover);

        assertThat(value(key, clock.instant())).isEqualByComparingTo("0.1");
        assertThat(value(key, switchover.minusMillis(1))).isEqualByComparingTo("0.1");
        assertThat(value(key, switchover)).isEqualByComparingTo("0.15");

        clock.advance(Duration.ofDays(2));
        assertThat(value(key, clock.instant())).isEqualByComparingTo("0.15");
        // The past stays readable at its time.
        assertThat(value(key, switchover.minusSeconds(60))).isEqualByComparingTo("0.1");

        ParamProcesses.ParamOutput set = run(ParamProcesses.SET, new ParamProcesses.SetInput(key, "0.2"));
        assertThat(set.effectiveTime()).isEqualTo(clock.instant());
        assertThat(value(key, clock.instant())).isEqualByComparingTo("0.2");
    }

    @Test
    void aScheduledChangeCanBeCancelledOnce() {
        String key = key();
        run(ParamProcesses.CREATE, new ParamProcesses.CreateInput(key, RATE, "0.1", null));
        Instant later = clock.instant().plus(Duration.ofDays(3));
        run(ParamProcesses.SCHEDULE, new ParamProcesses.ScheduleInput(key, "0.3", later));
        assertThat(value(key, later)).isEqualByComparingTo("0.3");

        run(ParamProcesses.CANCEL_SCHEDULED, new ParamProcesses.CancelInput(key, later));
        assertThat(value(key, later)).isEqualByComparingTo("0.1");

        assertThat(violations(() -> run(ParamProcesses.CANCEL_SCHEDULED,
            new ParamProcesses.CancelInput(key, later)))).extracting(Violation::ruleCode)
            .containsExactly("NOT_SCHEDULED");
    }

    @Test
    void schedulesMustLieInTheFuture() {
        String key = key();
        run(ParamProcesses.CREATE, new ParamProcesses.CreateInput(key, RATE, "0.1", null));
        assertThat(violations(() -> run(ParamProcesses.SCHEDULE,
            new ParamProcesses.ScheduleInput(key, "0.2", clock.instant())))).singleElement()
            .satisfies(v -> {
                assertThat(v.ruleCode()).isEqualTo("EFFECTIVE_TIME_NOT_FUTURE");
                assertThat(v.field()).isEqualTo("effectiveTime");
            });
    }

    @Test
    void valuesAreCheckedAgainstTheKindByTheProcesses() {
        String key = key();
        run(ParamProcesses.CREATE, new ParamProcesses.CreateInput(key, RATE, "0.1", null));
        assertThat(violations(() -> run(ParamProcesses.SET, new ParamProcesses.SetInput(key, "12.5"))))
            .extracting(Violation::ruleCode).containsExactly("PARAM_VALUE_INVALID");
        assertThat(violations(() -> run(ParamProcesses.SET, new ParamProcesses.SetInput(key, "0.12345"))))
            .extracting(Violation::ruleCode).containsExactly("PARAM_VALUE_INVALID");
        // A kind parameters cannot have is malformed input: 400, as on the dataset path.
        assertThatThrownBy(() -> run(ParamProcesses.CREATE, new ParamProcesses.CreateInput(key(),
            Map.of("type", "reference", "targetEntity", "Price"), "x", null)))
            .isInstanceOf(ValidationException.class)
            .satisfies(e -> assertThat(((ValidationException) e).violations())
                .extracting(Violation::field).containsExactly("valueKind"));
        assertThatThrownBy(() -> run(ParamProcesses.CREATE, new ParamProcesses.CreateInput(key(),
            Map.of("type", "code", "dictUrn", "urn:x"), "A", null)))
            .isInstanceOf(ValidationException.class);
        assertThat(value(key, clock.instant())).isEqualByComparingTo("0.1");
    }

    @Test
    void theKindIsCheckedOnTheDatasetPathToo() {
        String key = key();
        // Through the dataset, without any process: the entity's own check applies (docs/design/02 section 4.1).
        EntityInstance created = commit(insert(Map.of("paramKey", key, "valueKind", RATE, "value", "0.5000")))
            .getFirst();
        assertThat(value(key, clock.instant())).isEqualByComparingTo("0.5");
        // Stored text is canonical whatever the path: 0.6 must be written as "0.6000".
        assertThatThrownBy(() -> commit(new EntityChange(EntityAction.UPDATE, new EntityInstance(created.id(),
            ParamEntities.ENTITY, created.version(), null, Map.of("value", "0.6")))))
            .isInstanceOf(BusinessRuleViolationException.class)
            .satisfies(e -> assertThat(((BusinessRuleViolationException) e).violations())
                .extracting(Violation::ruleCode).containsExactly("PARAM_VALUE_INVALID"));

        assertThatThrownBy(() -> commit(new EntityChange(EntityAction.UPDATE, new EntityInstance(created.id(),
            ParamEntities.ENTITY, created.version(), null, Map.of("value", "much")))))
            .isInstanceOf(BusinessRuleViolationException.class)
            .satisfies(e -> assertThat(((BusinessRuleViolationException) e).violations())
                .extracting(Violation::ruleCode).containsExactly("PARAM_VALUE_INVALID"));
        assertThatThrownBy(() -> commit(insert(Map.of("paramKey", key(), "valueKind", RATE, "value", "abc"))))
            .isInstanceOf(BusinessRuleViolationException.class);
        assertThatThrownBy(() -> commit(insert(Map.of("paramKey", key(), "valueKind", Map.of("type", "version"),
            "value", "1"))))
            .isInstanceOf(ValidationException.class);
        // The kind cannot change after the fact.
        assertThatThrownBy(() -> commit(new EntityChange(EntityAction.UPDATE, new EntityInstance(created.id(),
            ParamEntities.ENTITY, created.version(), null, Map.of("valueKind", Map.of("type", "bool"))))))
            .isInstanceOf(BusinessRuleViolationException.class)
            .satisfies(e -> assertThat(((BusinessRuleViolationException) e).violations())
                .extracting(Violation::ruleCode).containsExactly("IMMUTABLE_FIELD"));
    }

    @Test
    void keysAreUnique() {
        String key = key();
        run(ParamProcesses.CREATE, new ParamProcesses.CreateInput(key, RATE, "0.1", null));
        assertThatThrownBy(() -> run(ParamProcesses.CREATE, new ParamProcesses.CreateInput(key, RATE, "0.2", null)))
            .isInstanceOf(ValidationException.class)
            .satisfies(e -> assertThat(((ValidationException) e).violations())
                .extracting(Violation::ruleCode).containsExactly("UNIQUE_VIOLATION"));
    }

    @Test
    void everySupportedKindReadsBackAsItsCanonicalValue() {
        Map<Map<String, Object>, Object> kinds = Map.of(
            Map.of("type", "text", "maxLength", 20), "hello",
            Map.of("type", "monetary", "currency", "JPY", "scale", 0), "1500",
            Map.of("type", "bool"), "true",
            Map.of("type", "temporal", "role", "EVENT_TIME"), "2026-04-01T00:00:00+09:00",
            Map.of("type", "code", "dictUrn", "urn:it:mode", "allowedValues", List.of("FAST", "SLOW")), "FAST");
        kinds.forEach((kind, raw) -> {
            String key = key();
            run(ParamProcesses.CREATE, new ParamProcesses.CreateInput(key, kind, raw, null));
            Object value = asTestRequest(params.get(key, clock.instant())).block();
            switch (String.valueOf(kind.get("type"))) {
                case "monetary" -> assertThat((BigDecimal) value).isEqualByComparingTo("1500");
                case "bool" -> assertThat(value).isEqualTo(true);
                case "temporal" -> assertThat(value).isEqualTo(Instant.parse("2026-03-31T15:00:00Z"));
                default -> assertThat(value).isEqualTo(raw);
            }
        });
    }

    @Test
    void missingParametersAreReportedTogether() {
        String present = key();
        run(ParamProcesses.CREATE, new ParamProcesses.CreateInput(present, RATE, "0.1", null));
        // Not yet in effect before it was created.
        assertThatThrownBy(() -> asTestRequest(params.load(List.of(present, "it.nope-1", "it.nope-2"),
            clock.instant().minusSeconds(1))).block())
            .isInstanceOf(BusinessRuleViolationException.class)
            .satisfies(e -> assertThat(((BusinessRuleViolationException) e).violations())
                .extracting(v -> v.params().get("key")).containsExactly(present, "it.nope-1", "it.nope-2"));
    }

    @Test
    void theProcessApiNeedsTheDeclaredPermissions() {
        String key = key();
        run(ParamProcesses.CREATE, new ParamProcesses.CreateInput(key, RATE, "0.1", null));
        WebTestClient client = WebTestClient.bindToApplicationContext(context).build();
        client.post().uri("/api/processes/PARAM_SET/latest").contentType(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.AUTHORIZATION, TestTokens.bearer(tokens, "it-reader", "platform.param.read"))
            .bodyValue(Map.of("key", key, "value", "0.2")).exchange().expectStatus().isForbidden();
        client.post().uri("/api/processes/PARAM_CREATE/latest").contentType(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.AUTHORIZATION, TestTokens.bearer(tokens, "it-reader", "platform.param.read"))
            .bodyValue(Map.of("key", key(), "valueKind", RATE, "value", "0.2")).exchange()
            .expectStatus().isForbidden();
        client.post().uri("/api/processes/PARAM_SET/latest").contentType(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.AUTHORIZATION, TestTokens.bearer(tokens, "it-writer", "platform.param.write"))
            .bodyValue(Map.of("key", key, "value", "0.2")).exchange().expectStatus().isOk();
        client.post().uri("/api/datasets/{id}/query", ParamEntities.DATASET).contentType(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.AUTHORIZATION, TestTokens.bearer(tokens, "it-writer", "platform.param.write"))
            .bodyValue(Map.of()).exchange().expectStatus().isForbidden();
        assertThat(value(key, clock.instant())).isEqualByComparingTo("0.2");
    }

    @Test
    void changingAValueThatHasAPendingScheduleConflicts() {
        String key = key();
        run(ParamProcesses.CREATE, new ParamProcesses.CreateInput(key, RATE, "0.1", null));
        run(ParamProcesses.SCHEDULE, new ParamProcesses.ScheduleInput(key, "0.2",
            clock.instant().plus(Duration.ofDays(1))));
        // Both change the value: the later schedule would silently undo the change (decision D1), so it is refused.
        assertThatThrownBy(() -> run(ParamProcesses.SET, new ParamProcesses.SetInput(key, "0.15")))
            .isInstanceOf(RebaseConflictException.class);
        assertThat(value(key, clock.instant())).isEqualByComparingTo("0.1");
    }

    @Test
    void parametersAreOnlyEverInserted() {
        SqlStatementLog.STATEMENTS.clear();
        String key = key();
        run(ParamProcesses.CREATE, new ParamProcesses.CreateInput(key, RATE, "0.1", null));
        Instant later = clock.instant().plus(Duration.ofDays(1));
        run(ParamProcesses.SCHEDULE, new ParamProcesses.ScheduleInput(key, "0.2", later));
        run(ParamProcesses.CANCEL_SCHEDULED, new ParamProcesses.CancelInput(key, later));
        run(ParamProcesses.SET, new ParamProcesses.SetInput(key, "0.15"));

        List<String> statements = List.copyOf(SqlStatementLog.STATEMENTS);
        assertThat(statements).anyMatch(sql -> sql.toLowerCase(Locale.ROOT).startsWith("insert into sys_param_version"));
        assertThat(statements).noneMatch(sql -> MUTATION.matcher(sql).find());
        assertThat(query("SELECT version_no FROM sys_param_version WHERE param_key = ?", key))
            .hasSizeGreaterThanOrEqualTo(4);
        assertThat(value(key, later)).isEqualByComparingTo("0.15");
        assertThatThrownBy(() -> execute("UPDATE sys_param_version SET param_value = '1' WHERE param_key = ?", key))
            .hasMessageContaining("append-only table");
    }

    /** Review finding (phase 14p, decision D32): more keys than a page of the dataset (100) are all found. */
    @Test
    void moreKeysThanAPageAreAllLoaded() {
        List<String> keys = new ArrayList<>();
        for (int i = 0; i < 101; i++) {
            String key = key();
            run(ParamProcesses.CREATE, new ParamProcesses.CreateInput(key, RATE, "0." + (i % 10), "rate"));
            keys.add(key);
        }
        assertThat(asTestRequest(params.load(keys, clock.instant())).block().values()).hasSize(101);
    }

    private static String key() {
        return "it.param-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private BigDecimal value(String key, Instant asOf) {
        return (BigDecimal) asTestRequest(params.get(key, asOf)).block();
    }

    private <I, O> O run(ProcessDefinition<I, O, ProcessContext> definition, I input) {
        return asRequest(ADMIN, executor.execute(definition, input)).block();
    }

    private List<EntityInstance> commit(EntityChange change) {
        return asRequest(ADMIN, entities.commitBatch(datasets.findById(ParamEntities.DATASET).orElseThrow(),
            List.of(change))).block();
    }

    /** An insert through the dataset; the caller supplies the generated key, as the dataset API does. */
    private static EntityChange insert(Map<String, Object> attributes) {
        String id = UUID.randomUUID().toString();
        Map<String, Object> withId = new java.util.LinkedHashMap<>(attributes);
        withId.put("paramId", id);
        return new EntityChange(EntityAction.INSERT, new EntityInstance(id, ParamEntities.ENTITY, 0, null, withId));
    }

    private static List<Violation> violations(Runnable action) {
        try {
            action.run();
        } catch (BusinessRuleViolationException e) {
            return e.violations();
        }
        throw new AssertionError("expected a business rule violation");
    }
}
