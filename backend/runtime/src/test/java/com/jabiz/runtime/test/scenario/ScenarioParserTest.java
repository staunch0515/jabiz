package com.jabiz.runtime.test.scenario;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScenarioParserTest {

    private static final String FULL = """
        name: Sample
        clock: 2026-01-31T09:00:00Z
        actor: { id: admin, tenant: t1, roles: [ADMIN], permissions: ["*"] }
        steps:
          - process: ORDER_CREATE@latest
            input: { customerId: C001 }
            save: { orderId: $.orderId }
            expectOutput: { status: NEW }
          - note: a day later
            advanceClock: P1D
          - setClock: 2026-02-01T00:00:00+09:00
          - expect: { query: order.by_customer, params: { customerId: C001 }, rows: 1, values: [{ total: 10 }] }
          - expect: { entity: Order, id: "${orderId}", asOf: 2026-02-01T00:00:00Z, fields: { status: NEW } }
          - expect: { param: tax.rate, value: 0.1 }
          - expectError:
              process: ORDER_CANCEL@1
              input: { orderId: "${orderId}" }
              status: 422
              ruleCode: ORDER_ALREADY_SETTLED
              field: orderId
              actor: { id: clerk, permissions: [order.cancel] }
        snapshot:
          entities: [Order]
          asOf: end
        """;

    @Test
    void readsJobAndEventSteps() {
        Scenario scenario = Scenario.parse("""
            name: Jobs
            clock: 2026-01-31T09:00:00Z
            actor: { id: admin }
            steps:
              - runJob: month-close
              - runJob: { job: month-close, at: 2026-02-01T00:05:00+09:00, outcome: REPLAYED }
              - deliverEvents: true
            """, "scenarios/jobs.yml");

        assertThat(scenario.steps()).containsExactly(
            new Scenario.RunJob(1, "month-close", null, "SUCCEEDED"),
            new Scenario.RunJob(2, "month-close", Instant.parse("2026-01-31T15:05:00Z"), "REPLAYED"),
            new Scenario.DeliverEvents(3));
        assertThatThrownBy(() -> Scenario.parse("""
            name: Bad
            clock: 2026-01-31T09:00:00Z
            actor: { id: admin }
            steps:
              - runJob: { job: x, when: now }
            """, "scenarios/bad.yml")).hasMessageContaining("when");
        assertThatThrownBy(() -> Scenario.parse("""
            name: Bad
            clock: 2026-01-31T09:00:00Z
            actor: { id: admin }
            steps:
              - deliverEvents: false
            """, "scenarios/bad.yml")).hasMessageContaining("deliverEvents must be true");
    }

    @Test
    void readsEveryKindOfStep() {
        Scenario scenario = Scenario.parse(FULL, "scenarios/sample.yml");
        assertThat(scenario.name()).isEqualTo("Sample");
        assertThat(scenario.clock()).isEqualTo(Instant.parse("2026-01-31T09:00:00Z"));
        assertThat(scenario.actor().permissions()).containsExactly("*");
        assertThat(scenario.actor().tenant()).isEqualTo("t1");
        assertThat(scenario.steps()).hasSize(7);
        assertThat(scenario.steps().get(0)).isInstanceOfSatisfying(Scenario.ProcessStep.class, p -> {
            assertThat(p.process()).isEqualTo("ORDER_CREATE@latest");
            assertThat(p.save()).containsEntry("orderId", "$.orderId");
            assertThat(p.number()).isEqualTo(1);
            assertThat(p.actor()).isNull();
        });
        assertThat(scenario.steps().get(1)).isEqualTo(new Scenario.AdvanceClock(2, "P1D"));
        assertThat(scenario.steps().get(2)).isEqualTo(new Scenario.SetClock(3, Instant.parse("2026-01-31T15:00:00Z")));
        assertThat(((Scenario.Expect) scenario.steps().get(3)).expectation())
            .isEqualTo(new Scenario.QueryExpectation("order.by_customer", Map.of("customerId", "C001"), 1,
                List.of(Map.of("total", 10))));
        assertThat(((Scenario.Expect) scenario.steps().get(4)).expectation())
            .isInstanceOf(Scenario.EntityExpectation.class);
        assertThat(((Scenario.Expect) scenario.steps().get(5)).expectation())
            .isEqualTo(new Scenario.ParamExpectation("tax.rate", null, 0.1));
        assertThat(scenario.steps().get(6)).isEqualTo(new Scenario.ExpectError(7, "ORDER_CANCEL@1",
            Map.of("orderId", "${orderId}"), 422, "ORDER_ALREADY_SETTLED", "orderId",
            new Scenario.Actor("clerk", null, Set.of(), Set.of("order.cancel"))));
        assertThat(scenario.snapshot()).isEqualTo(new Scenario.SnapshotSpec(List.of("Order"), null));
    }

    @Test
    void unknownKeysAndMalformedStepsAreErrors() {
        String header = "name: X\nclock: 2026-01-01T00:00:00Z\nactor: { id: a }\n";
        assertThatThrownBy(() -> Scenario.parse(header + "steps:\n  - expect: { query: q, rowz: 1 }\n", "x.yml"))
            .hasMessageContaining("x.yml").hasMessageContaining("unknown key 'rowz'");
        assertThatThrownBy(() -> Scenario.parse(header + "steps:\n  - advanceClock: P1D\n    setClock: x\n", "x.yml"))
            .hasMessageContaining("exactly one");
        assertThatThrownBy(() -> Scenario.parse(header + "steps:\n  - jump: 1\n", "x.yml"))
            .hasMessageContaining("unknown step");
        assertThatThrownBy(() -> Scenario.parse(header + "steps:\n  - expect: { nothing: 1 }\n", "x.yml"))
            .hasMessageContaining("needs query, entity or param");
        assertThatThrownBy(() -> Scenario.parse(header + "steps:\n  - expectError: { process: P }\n", "x.yml"))
            .hasMessageContaining("needs 'status'");
        assertThatThrownBy(() -> Scenario.parse("name: X\nclock: yesterday\nactor: { id: a }\nsteps: []\n", "x.yml"))
            .hasMessageContaining("ISO-8601");
        assertThatThrownBy(() -> Scenario.parse(header + "steps: []\ncolour: red\n", "x.yml"))
            .hasMessageContaining("unknown key 'colour'");
        assertThatThrownBy(() -> Scenario.parse("", "x.yml")).hasMessageContaining("x.yml");
        assertThatThrownBy(() -> Scenario.parse("a: [", "x.yml")).hasMessageContaining("not valid YAML");
    }

    @Test
    void theClockAdvancesByDurationsAndPeriods() {
        Instant start = Instant.parse("2026-01-31T09:00:00Z");
        assertThat(ScenarioRunner.advance(start, "PT2H")).isEqualTo(Instant.parse("2026-01-31T11:00:00Z"));
        assertThat(ScenarioRunner.advance(start, "P1D")).isEqualTo(Instant.parse("2026-02-01T09:00:00Z"));
        assertThat(ScenarioRunner.advance(start, "P1M")).isEqualTo(Instant.parse("2026-02-28T09:00:00Z"));
        assertThatThrownBy(() -> ScenarioRunner.advance(start, "soon")).isInstanceOf(RuntimeException.class);
    }
}
