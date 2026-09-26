package com.jabiz.runtime.process;

import com.jabiz.process.BlockingStep;
import com.jabiz.process.ComputeStep;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.process.StepDefinition;
import com.jabiz.runtime.check.CheckProblem;
import com.jabiz.runtime.check.PlatformCheck;
import com.jabiz.runtime.process.steps.CallProcess;
import com.jabiz.runtime.process.steps.CheckedStep;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Startup checks of the registered processes (docs/design/06-process.md section 9), category {@code PROCESS}:
 * <ul>
 *   <li>every step is a {@link StepHandler}, {@link ComputeStep} or {@link BlockingStep}, and every step class that
 *       is not declared in place has exactly one bean;</li>
 *   <li>references of platform steps resolve (datasets, templates, called processes, the event publisher);</li>
 *   <li>the call graph is acyclic; calls of deprecated versions are warned about;</li>
 *   <li>every process declares permissions (a warning in the {@code dev} profile);</li>
 *   <li>the input form schema of every listed process describes all its input (a warning otherwise).</li>
 * </ul>
 */
@Component
public class ProcessChecks implements PlatformCheck {

    public static final String CATEGORY = "PROCESS";

    private final ProcessRegistry registry;
    private final ApplicationContext beans;
    private final boolean development;

    public ProcessChecks(ProcessRegistry registry, ApplicationContext beans, Environment environment) {
        this.registry = registry;
        this.beans = beans;
        this.development = environment.acceptsProfiles(Profiles.of("dev"));
    }

    @Override
    public List<CheckProblem> check() {
        List<CheckProblem> problems = new ArrayList<>();
        Map<String, Set<String>> calls = new LinkedHashMap<>();
        for (ProcessDefinition<?, ?, ?> definition : registry.all()) {
            String process = key(definition);
            calls.put(process, new LinkedHashSet<>());
            if (definition.permissions().isEmpty()) {
                String message = "declares no permissions";
                problems.add(development
                    ? CheckProblem.warning(CATEGORY, process, message + " (allowed in the dev profile only)")
                    : CheckProblem.error(CATEGORY, process, message));
            }
            for (StepDefinition<?, ?> step : definition.steps()) {
                checkStep(definition, step, problems, calls.get(process));
            }
            if (!definition.internal()) {
                // Clients build the process form from this schema; what it cannot describe is entered as raw JSON.
                for (String unsupported : ProcessInputSchemas.of(definition.inputType()).problems()) {
                    problems.add(CheckProblem.warning(CATEGORY, process,
                        "input form cannot describe " + unsupported + " (entered as raw JSON)"));
                }
            }
        }
        findCycle(calls).ifPresent(cycle -> problems.add(CheckProblem.error(CATEGORY, cycle.getFirst(),
            "call cycle: " + String.join(" -> ", cycle))));
        return problems;
    }

    private void checkStep(ProcessDefinition<?, ?, ?> definition, StepDefinition<?, ?> step,
        List<CheckProblem> problems, Set<String> calls) {
        String location = key(definition) + " step '" + step.stepName() + "'";
        Class<?> type = step.handlerClass();
        boolean supported = StepHandler.class.isAssignableFrom(type) || ComputeStep.class.isAssignableFrom(type)
            || BlockingStep.class.isAssignableFrom(type);
        if (!supported) {
            problems.add(CheckProblem.error(CATEGORY, location, type.getName()
                + " is neither a StepHandler, a ComputeStep nor a BlockingStep"));
            return;
        }
        if (step.isInline()) {
            return;
        }
        String[] names = beans.getBeanNamesForType(type);
        if (names.length != 1) {
            problems.add(CheckProblem.error(CATEGORY, location, "expected exactly one bean of type "
                + type.getName() + " but found " + names.length));
            return;
        }
        Object bean = beans.getBean(names[0]);
        if (bean instanceof CheckedStep<?> checked) {
            for (String problem : problemsOf(checked, step.metadata())) {
                problems.add(CheckProblem.error(CATEGORY, location, problem));
            }
        }
        if (step.metadata() instanceof CallProcess.Metadata<?> call) {
            CallProcess.resolve(registry, call).ifPresent(target -> {
                calls.add(key(target));
                if (target.deprecated()) {
                    problems.add(CheckProblem.warning(CATEGORY, location, "calls deprecated process " + key(target)));
                }
            });
        }
    }

    @SuppressWarnings("unchecked")
    private static <M> List<String> problemsOf(CheckedStep<M> step, Object metadata) {
        // The builder binds a step class to its metadata type, so the metadata is an M.
        return step.problems((M) metadata);
    }

    /** One cycle of the call graph as a path whose first and last node are the same, if there is one. */
    private static Optional<List<String>> findCycle(Map<String, Set<String>> calls) {
        Map<String, Integer> state = new HashMap<>();
        for (String start : calls.keySet()) {
            List<String> path = new ArrayList<>();
            List<String> cycle = visit(start, calls, state, path, new HashSet<>());
            if (cycle != null) {
                return Optional.of(cycle);
            }
        }
        return Optional.empty();
    }

    private static final int VISITING = 1;
    private static final int DONE = 2;

    private static List<String> visit(String node, Map<String, Set<String>> calls, Map<String, Integer> state,
        List<String> path, Set<String> onPath) {
        Integer current = state.get(node);
        if (current != null && current == DONE) {
            return null;
        }
        if (onPath.contains(node)) {
            List<String> cycle = new ArrayList<>(path.subList(path.indexOf(node), path.size()));
            cycle.add(node);
            return cycle;
        }
        state.put(node, VISITING);
        path.add(node);
        onPath.add(node);
        for (String next : calls.getOrDefault(node, Set.of())) {
            List<String> cycle = visit(next, calls, state, path, onPath);
            if (cycle != null) {
                return cycle;
            }
        }
        path.removeLast();
        onPath.remove(node);
        state.put(node, DONE);
        return null;
    }

    private static String key(ProcessDefinition<?, ?, ?> definition) {
        return definition.name() + "@" + definition.version();
    }
}
