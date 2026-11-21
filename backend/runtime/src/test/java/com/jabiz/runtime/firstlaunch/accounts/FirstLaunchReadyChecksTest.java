package com.jabiz.runtime.firstlaunch.accounts;

import com.jabiz.firstlaunch.FirstLaunchConfig;
import com.jabiz.firstlaunch.FirstLaunchHandler;
import com.jabiz.firstlaunch.FirstLaunchProblem;
import com.jabiz.firstlaunch.FirstLaunchStep;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.runtime.check.CheckProblem;
import com.jabiz.runtime.process.ProcessRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/** The processes a ready account's steps may run (docs/design/23-first-launch.md section 9). */
class FirstLaunchReadyChecksTest {

    public record In(String text) {}

    private static ProcessDefinition<In, In, ProcessContext> process(String name,
        Consumer<com.jabiz.process.ProcessDefinitionBuilder<In, In, ProcessContext>> access) {
        return ProcessDefinition.define(name, 1, In.class, In.class, ProcessContext.class, pb -> {
            pb.permissions("test").contextFactory((start, input) -> new ProcessContext(start))
                .compute("Nothing", (metadata, ctx) -> { })
                .outputMapper(ctx -> new In("x"));
            access.accept(pb);
        });
    }

    private static FirstLaunchHandler handler(Set<String> acting, Set<String> ready) {
        return new FirstLaunchHandler() {
            @Override
            public String name() {
                return "app";
            }

            @Override
            public List<FirstLaunchProblem> validate(FirstLaunchConfig config) {
                return List.of();
            }

            @Override
            public List<FirstLaunchStep> plan(FirstLaunchConfig config) {
                return List.of();
            }

            @Override
            public Set<String> actingProcesses() {
                return acting;
            }

            @Override
            public Set<String> accountReadyProcesses() {
                return ready;
            }
        };
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ProcessRegistry registry() {
        List<ProcessDefinition> definitions = List.of(
            process("OPEN_ONE", pb -> { }),
            process("SUB_ONE", pb -> pb.subProcessOnly()),
            process("SUB_TWO", pb -> pb.subProcessOnly()),
            process("INTERNAL_ONE", pb -> pb.internal()),
            process("ENTRY_ONE", pb -> pb.entryOnly()),
            process("FIRST_LAUNCH_SOMETHING", pb -> pb.internal()));
        Map<String, Object> named = new LinkedHashMap<>();
        definitions.forEach(definition -> named.put(definition.name(), definition));
        ObjectProvider beans = new StaticListableBeanFactory(named).getBeanProvider(ProcessDefinition.class);
        return new ProcessRegistry(beans);
    }

    private static List<CheckProblem> check(FirstLaunchHandler handler) {
        return new FirstLaunchReadyChecks(new StaticListableBeanFactory(Map.of("handler", handler))
            .getBeanProvider(FirstLaunchHandler.class), registry()).check();
    }

    @Test
    void readyProcessesExistAreSubProcessesOrInternalNotThePlatformsAndNotActing() {
        List<CheckProblem> problems = check(handler(Set.of("SUB_TWO"), Set.of("SUB_ONE", "INTERNAL_ONE", "OPEN_ONE",
            "ENTRY_ONE", "MISSING", "FIRST_LAUNCH_SOMETHING", "SUB_TWO")));
        assertThat(problems).allMatch(CheckProblem::isError);
        assertThat(problems).extracting(CheckProblem::message).containsExactlyInAnyOrder(
            "account-ready process OPEN_ONE is OPEN: only sub-process-only or internal processes",
            "account-ready process ENTRY_ONE is ENTRY_ONLY: only sub-process-only or internal processes",
            "account-ready process MISSING does not exist",
            "account-ready process FIRST_LAUNCH_SOMETHING is the platform's own (FIRST_LAUNCH)",
            "account-ready process SUB_TWO is an acting process too: the two lists are apart");
    }

    @Test
    void noneIsNoProblem() {
        assertThat(check(handler(Set.of("SUB_ONE"), Set.of()))).isEmpty();
    }
}
