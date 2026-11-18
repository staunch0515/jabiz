package com.jabiz.runtime.firstlaunch;

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

class FirstLaunchChecksTest {

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

    private static FirstLaunchHandler handler(String name, Set<String> acting, Set<String> keys) {
        return new FirstLaunchHandler() {
            @Override
            public String name() {
                return name;
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
            public Set<String> publishableControlKeys() {
                return keys;
            }
        };
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(Class<T> type, List<T> beans) {
        Map<String, Object> named = new LinkedHashMap<>();
        for (int i = 0; i < beans.size(); i++) {
            named.put(type.getSimpleName() + i, beans.get(i));
        }
        return new StaticListableBeanFactory(named).getBeanProvider(type);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ProcessRegistry registry() {
        List<ProcessDefinition> definitions = List.of(
            process("OPEN_ONE", pb -> { }),
            process("SUB_ONE", pb -> pb.subProcessOnly()),
            process("INTERNAL_ONE", pb -> pb.internal()),
            process("ENTRY_ONE", pb -> pb.entryOnly()));
        Map<String, Object> named = new LinkedHashMap<>();
        definitions.forEach(definition -> named.put(definition.name(), definition));
        ObjectProvider beans = new StaticListableBeanFactory(named).getBeanProvider(ProcessDefinition.class);
        return new ProcessRegistry(beans);
    }

    @Test
    void actingProcessesExistAndAreOpenOrSubProcessesOnly() {
        FirstLaunchHandler app = handler("app", Set.of("OPEN_ONE", "SUB_ONE", "INTERNAL_ONE", "ENTRY_ONE", "MISSING"),
            Set.of("qb.key", " "));

        List<CheckProblem> problems = new FirstLaunchChecks(provider(FirstLaunchHandler.class, List.of(app)),
            registry()).check();

        assertThat(problems).allMatch(CheckProblem::isError);
        assertThat(problems).extracting(CheckProblem::message).containsExactlyInAnyOrder(
            "acting process INTERNAL_ONE is INTERNAL: a step runs open or sub-process-only processes as an account",
            "acting process ENTRY_ONE is ENTRY_ONLY: a step runs open or sub-process-only processes as an account",
            "acting process MISSING does not exist",
            "a publishable control has no key");
    }

    @Test
    void anApplicationHasOneHandler() {
        List<CheckProblem> problems = new FirstLaunchChecks(provider(FirstLaunchHandler.class,
            List.of(handler("a", Set.of(), Set.of()), handler("b", Set.of(), Set.of()))), registry()).check();

        assertThat(problems).extracting(CheckProblem::message)
            .containsExactly("2 first-launch handlers; an application has one");
    }

    @Test
    void noHandlerIsNoProblem() {
        assertThat(new FirstLaunchChecks(provider(FirstLaunchHandler.class, List.of()), registry()).check()).isEmpty();
    }
}
