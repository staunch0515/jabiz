package com.jabiz.runtime.param;

import com.jabiz.entity.Violation;
import com.jabiz.param.ControlledParams;
import com.jabiz.runtime.approval.ControlChanges;
import com.jabiz.runtime.check.CheckProblem;
import com.jabiz.runtime.operation.Operation;
import com.jabiz.temporal.EntityVersion;
import com.jabiz.temporal.Timeline;
import com.jabiz.temporal.VersionAction;
import com.jabiz.temporal.VersionPlanner.PlannedVersion;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** The startup check and the write guard of controlled parameters (decision D40). */
class ControlledParamsChecksTest {

    private static final Instant NOW = Instant.parse("2026-10-10T00:00:00Z");

    private static ControlledParamRegistry registry(ControlledParams... declarations) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        for (int i = 0; i < declarations.length; i++) {
            beans.addBean("controlled" + i, declarations[i]);
        }
        return new ControlledParamRegistry(beans.getBeanProvider(ControlledParams.class));
    }

    @Test
    void everyMalformedKeyOfEveryDeclarationIsReportedAtOnce() {
        ControlledParamRegistry registry = registry(ControlledParams.of("quiz.fee", "Quiz.Fee"),
            ControlledParams.of("quiz payout", "quiz.payout"));
        assertThat(new ParamChecks(registry).check()).extracting(CheckProblem::category, CheckProblem::isError)
            .containsExactly(org.assertj.core.groups.Tuple.tuple("PARAM", true),
                org.assertj.core.groups.Tuple.tuple("PARAM", true));
        // Keys without a parameter yet are fine: nothing reads the database.
        assertThat(new ParamChecks(registry(ControlledParams.of("not.created.yet"))).check()).isEmpty();
        assertThat(new ParamChecks(registry()).check()).isEmpty();
    }

    @Test
    void controlledKeysAreKnownAcrossDeclarations() {
        ControlledParamRegistry registry = registry(ControlledParams.of("a.one"), ControlledParams.of("b.two"));
        assertThat(registry.controls("a.one")).isTrue();
        assertThat(registry.controls("b.two")).isTrue();
        assertThat(registry.controls("c.three")).isFalse();
        assertThat(registry.controls(null)).isFalse();
    }

    @Test
    void onlyAPublishedControlChangeWritesAControlledParameter() {
        ControlledParamGuard guard = new ControlledParamGuard(registry(ControlledParams.of("quiz.fee")));
        Timeline stored = Timeline.of(List.of(version("quiz.fee")));
        List<PlannedVersion> update = List.of(planned("quiz.fee"));

        List<Violation> refused = guard.check(ParamEntities.SYS_PARAM, stored, update, operation("PARAM_SET"));
        assertThat(refused).singleElement().satisfies(v -> {
            assertThat(v.ruleCode()).isEqualTo("PARAM_CONTROLLED");
            assertThat(v.params()).containsEntry("key", "quiz.fee");
        });
        assertThat(guard.check(ParamEntities.SYS_PARAM, stored, update, operation(ControlChanges.PUBLISH))).isEmpty();
        // A creation names the key in the written version only; the dataset API writes as DATASET_COMMIT.
        assertThat(guard.check(ParamEntities.SYS_PARAM, Timeline.of(List.of()), update,
            operation("DATASET_COMMIT"))).hasSize(1);
        // Other keys and other entities pass.
        assertThat(guard.check(ParamEntities.SYS_PARAM, Timeline.of(List.of(version("quiz.other"))),
            List.of(planned("quiz.other")), operation("PARAM_SET"))).isEmpty();
    }

    private static EntityVersion version(String key) {
        return new EntityVersion(1, NOW, NOW, false, Map.of(ParamEntities.KEY, key, ParamEntities.VALUE, "1"),
            Set.of(), VersionAction.INSERT, null, 1);
    }

    private static PlannedVersion planned(String key) {
        return new PlannedVersion(2, NOW, false, Map.of(ParamEntities.KEY, key, ParamEntities.VALUE, "2"),
            Set.of(ParamEntities.VALUE), VersionAction.UPDATE, 1L);
    }

    private static Operation operation(String process) {
        return new Operation(7, NOW, process, 1, "someone", null, null, null);
    }
}
