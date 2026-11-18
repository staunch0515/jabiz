package com.jabiz.runtime.firstlaunch;

import com.jabiz.context.RequestContext;
import com.jabiz.firstlaunch.FirstLaunchConfig;
import com.jabiz.firstlaunch.FirstLaunchHandler;
import com.jabiz.firstlaunch.FirstLaunchProblem;
import com.jabiz.firstlaunch.FirstLaunchResults;
import com.jabiz.firstlaunch.FirstLaunchStep;
import com.jabiz.i18n.MessageCatalog;
import com.jabiz.runtime.context.RequestContexts;
import com.jabiz.runtime.process.ProcessExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.RecordComponent;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Runs the first launch (docs/design/23-first-launch.md section 4).
 *
 * <p>Once every singleton exists, before the server accepts requests, it decides what this start does: nothing
 * (no configuration, a restore verification, a database that already had users, a run that has ended), stop the
 * startup (problems in the configuration, a failed run), or close the {@link FirstLaunchGate} and, once the
 * application is ready (event delivery and jobs started), do the content phase in the background or wait for the
 * instance that does it. The content phase runs the platform's steps and then the handler's, one
 * {@code FIRST_LAUNCH_STEP} each; any failure records the run as failed and ends the application, whose next starts
 * fail the same way until the database is emptied. There is no resuming.
 */
@Component
public class FirstLaunchRunner implements SmartInitializingSingleton, ApplicationListener<ApplicationReadyEvent> {

    private static final Logger log = LoggerFactory.getLogger(FirstLaunchRunner.class);

    /** What this start does once the application is ready. */
    enum Mode { NONE, RUN, WAIT_OTHER, DRY_RUN }

    private final FirstLaunchSettings settings;
    private final FirstLaunchRuns runs;
    private final FirstLaunchGate gate;
    private final ProcessExecutor processes;
    private final ObjectProvider<FirstLaunchHandler> handlers;
    private final ObjectProvider<FirstLaunchPlatformSteps> platformSteps;
    private final MessageCatalog messages;
    private final Clock clock;
    private final Environment environment;
    private final ObjectProvider<FirstLaunchExit> exits;
    private final JsonMapper json;
    private final FirstLaunchPowers powers;
    private final FirstLaunchFiles files;

    private volatile Mode mode = Mode.NONE;
    private volatile FirstLaunchConfig config;
    private volatile Locale locale = Locale.ENGLISH;

    public FirstLaunchRunner(Environment environment, FirstLaunchRuns runs, FirstLaunchGate gate,
        ProcessExecutor processes, ObjectProvider<FirstLaunchHandler> handlers,
        ObjectProvider<FirstLaunchPlatformSteps> platformSteps, MessageCatalog messages, Clock clock,
        ObjectProvider<FirstLaunchExit> exits, JsonMapper json, FirstLaunchPowers powers, FirstLaunchFiles files) {
        this.settings = FirstLaunchSettings.of(environment);
        this.environment = environment;
        this.runs = runs;
        this.gate = gate;
        this.processes = processes;
        this.handlers = handlers;
        this.platformSteps = platformSteps;
        this.messages = messages;
        this.clock = clock;
        this.exits = exits;
        this.json = json;
        this.powers = powers;
        this.files = files;
    }

    /** What this start does; for tests. */
    Mode mode() {
        return mode;
    }

    @Override
    public void afterSingletonsInstantiated() {
        if (environment.getProperty("jabiz.ops.verify-restore.enabled", Boolean.class, false)) {
            if (settings.configured()) {
                log.info(text("first-launch.restore", Map.of()));
            }
            return;
        }
        FirstLaunchConfigReader.Read read = settings.configured() ? new FirstLaunchConfigReader(settings).read() : null;
        if (read != null) {
            config = read.config();
            locale = language(config);
        }
        if (read != null && settings.dryRun()) {
            requireValid(read);
            mode = Mode.DRY_RUN;
            gate.close();
            return;
        }
        // The run's record decides whatever the configuration says: a failed run stops every start, and a run in its
        // content phase keeps every instance closed, also one started without the configuration.
        Optional<FirstLaunchRun> run = runs.current().contextWrite(this::asSystem).block();
        if (run.isPresent()) {
            FirstLaunchRun found = run.get();
            switch (found.state()) {
                case RUNNING -> {
                    if (stale(found)) {
                        String reason = text("first-launch.stale", Map.of("time", found.startedTime(),
                            "timeout", settings.contentTimeout()));
                        try {
                            record(found.runId(), found.configHash(), "FAILED", null, "STALE", reason, null).block();
                        } catch (RuntimeException e) {
                            // The instance that ran it may have recorded its own failure first: failed either way.
                            log.debug("The first launch's failure was recorded already", e);
                        }
                        throw failed(null, reason);
                    }
                    log.info(text("first-launch.waiting-other", Map.of()));
                    mode = Mode.WAIT_OTHER;
                    gate.close();
                }
                case FAILED -> throw failed(found.failedStep(), found.failedReason());
                case CONTENT_DONE -> { }
                case DONE, EXPIRED -> {
                    if (read != null) {
                        log.warn(text("first-launch.ended", Map.of("state", found.state(), "time",
                            found.stateTime(), "hash", config.hash())));
                    }
                }
            }
            return;
        }
        if (read == null) {
            return;
        }
        long users = runs.usersEver().contextWrite(this::asSystem).block();
        if (users > 0) {
            log.warn(text("first-launch.users-exist", Map.of("count", users, "hash", config.hash())));
            return;
        }
        requireValid(read);
        mode = Mode.RUN;
        gate.close();
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        switch (mode) {
            case RUN -> Schedulers.boundedElastic().schedule(this::runContentPhase);
            case WAIT_OTHER -> Schedulers.boundedElastic().schedule(this::waitForOther);
            case DRY_RUN -> {
                dryRun();
                exit(0);
            }
            case NONE -> { }
        }
    }

    /** Validates, failing the startup with every problem at once. */
    private void requireValid(FirstLaunchConfigReader.Read read) {
        List<FirstLaunchProblem> problems = new ArrayList<>(read.problems());
        problems.addAll(exclusions());
        List<FirstLaunchHandler> found = handlers.orderedStream().toList();
        if (found.size() > 1) {
            problems.add(new FirstLaunchProblem("(application)", "has " + found.size() + " first-launch handlers",
                "keep one FirstLaunchHandler bean"));
        }
        if (read.problems().isEmpty()) {
            platformSteps.orderedStream().forEach(steps -> problems.addAll(steps.validate(read.config())));
            found.forEach(handler -> problems.addAll(handler.validate(read.config())));
        }
        if (problems.isEmpty()) {
            problems.addAll(fileStepProblems(read.config()));
        }
        if (!problems.isEmpty()) {
            StringBuilder report = new StringBuilder(text("first-launch.problems", Map.of("count", problems.size())));
            problems.forEach(problem -> report.append(System.lineSeparator()).append("  ").append(problem.format()));
            log.error(report.toString());
            throw new FirstLaunchFailedException(report.toString());
        }
    }

    /** Every file step's problems, found before anything is written: its account, its policy, its file. */
    private List<FirstLaunchProblem> fileStepProblems(FirstLaunchConfig config) {
        List<FirstLaunchProblem> problems = new ArrayList<>();
        for (FirstLaunchStep step : plan(config)) {
            FirstLaunchStep.FileSource source = step.file();
            if (source == null) {
                continue;
            }
            String at = "step " + step.key() + " (" + source.path() + ")";
            if (step.asAccount() == null) {
                problems.add(new FirstLaunchProblem(at, "stores a file as no account",
                    "a file step stores the file as the account whose content uses it"));
            }
            if (!files.policies().contains(source.policy())) {
                problems.add(new FirstLaunchProblem(at, "uses the file policy " + source.policy()
                    + ", which the application does not name", "fix the application's handler"));
            }
            if (!config.files().exists(source.path())) {
                problems.add(new FirstLaunchProblem(at, "names a file that is not in the configuration's directory",
                    "put the file there, or fix the path"));
            }
        }
        return problems;
    }

    /** Settings the first launch cannot work with (section 5). */
    private List<FirstLaunchProblem> exclusions() {
        List<FirstLaunchProblem> problems = new ArrayList<>();
        if (!environment.getProperty("jabiz.security.bootstrap-admin.user-name", "").isBlank()) {
            problems.add(new FirstLaunchProblem("JABIZ_BOOTSTRAP_ADMIN_USER", "is set together with the first-launch"
                + " configuration", "unset JABIZ_BOOTSTRAP_ADMIN_USER and JABIZ_BOOTSTRAP_ADMIN_PASSWORD: the first"
                + " launch creates the accounts itself"));
        }
        if (environment.getProperty("jabiz.security.protected-grants.always", Boolean.class, false)) {
            problems.add(new FirstLaunchProblem("jabiz.security.protected-grants.always", "is true: there is no setup"
                + " window in which the first launch could give the configured roles",
                "leave it false for the first launch"));
        }
        return problems;
    }

    /** The plan: the platform's steps, then the handler's. */
    List<FirstLaunchStep> plan(FirstLaunchConfig config) {
        List<FirstLaunchStep> steps = new ArrayList<>();
        platformSteps.orderedStream().forEach(part -> steps.addAll(part.plan(config)));
        handlers.orderedStream().forEach(handler -> steps.addAll(handler.plan(config)));
        Set<String> keys = new HashSet<>();
        for (FirstLaunchStep step : steps) {
            if (!keys.add(step.key())) {
                throw new IllegalStateException("The first launch's plan repeats the step key " + step.key());
            }
        }
        return steps;
    }

    private void dryRun() {
        StringBuilder report = new StringBuilder(text("first-launch.dry-run", Map.of("hash", config.hash())));
        int number = 1;
        for (FirstLaunchStep step : plan(config)) {
            report.append(System.lineSeparator()).append("  ").append(text("first-launch.dry-run-step",
                Map.of("number", number++, "step", step.describe(), "process", step.process(),
                    "actor", step.asAccount() == null ? "system" : step.asAccount())));
        }
        log.info(report.toString());
    }

    /** The content phase: begins the run, runs every step, records the phase as done and opens the gate. */
    void runContentPhase() {
        FirstLaunchProcesses.StartOutput started = processes.execute(FirstLaunchProcesses.START_PROCESS,
                new FirstLaunchProcesses.StartInput(config.hash()))
            .contextWrite(this::asSystem).block();
        if (!started.started()) {
            if (started.usersEver() > 0) {
                log.warn(text("first-launch.users-exist", Map.of("count", started.usersEver(),
                    "hash", config.hash())));
                gate.open();
                return;
            }
            // Another instance began the run first.
            log.info(text("first-launch.waiting-other", Map.of()));
            waitForOther();
            return;
        }
        UUID runId = UUID.fromString(started.runId());
        log.info(text("first-launch.started", Map.of("run", runId, "hash", config.hash())));
        Map<String, Object> outputs = new LinkedHashMap<>();
        Results results = new Results(outputs);
        Instant deadline = clock.instant().plus(settings.contentTimeout());
        FirstLaunchStep current = null;
        try {
            for (FirstLaunchStep step : plan(config)) {
                current = step;
                if (clock.instant().isAfter(deadline)) {
                    throw new IllegalStateException("The content phase did not finish within "
                        + settings.contentTimeout() + " (jabiz.first-launch.content-timeout)");
                }
                Object output = run(runId, step, results, deadline);
                if (output != null) {
                    outputs.put(step.key(), output);
                }
                log.info(text("first-launch.step-done", Map.of("step", step.describe())));
            }
            record(runId, config.hash(), "CONTENT_DONE", null, null, null, contentDoneDetail(results)).block();
        } catch (RuntimeException e) {
            String step = current == null ? null : current.key();
            String reason = reason(e);
            log.error(text("first-launch.failed", Map.of("step", step == null ? "-" : current.describe(),
                "reason", reason)), e);
            try {
                record(runId, config.hash(), "FAILED", step, code(e), reason, null).block();
            } catch (RuntimeException recording) {
                log.error("Could not record the first launch's failure", recording);
            }
            exit(1);
            return;
        }
        gate.open();
        log.info(text("first-launch.content-done", Map.of()));
    }

    /** The platform parts' details for the waiting phase, as one JSON object ({@code null} when none gives any). */
    private String contentDoneDetail(Results results) {
        Map<String, Object> detail = new LinkedHashMap<>();
        platformSteps.orderedStream().forEach(part -> {
            Object value = part.contentDoneDetail(config, results);
            if (value != null) {
                detail.put(part.detailKey(), value);
            }
        });
        return detail.isEmpty() ? null : json.writeValueAsString(detail);
    }

    /** Runs one step: once, or again and again while it waits. Returns its output. */
    private Object run(UUID runId, FirstLaunchStep step, Results results, Instant deadline) {
        if (step.file() != null) {
            // A file step (D82): stored as the account through the upload pipeline, registered under a step.
            if (step.asAccount() == null) {
                throw new IllegalStateException("File step " + step.key() + " names no account to store it as");
            }
            String actor = results.userId(step.asAccount());
            return files.store(runId, config, step, actor,
                FirstLaunchActing.as(actor, locale, requestId(runId, step))).block();
        }
        if (step.asAccount() != null && !powers.actingProcesses().contains(step.process())) {
            throw new IllegalStateException("Step " + step.key() + " runs " + step.process() + " as account "
                + step.asAccount() + ", but the application's handler does not name it among its acting processes");
        }
        while (true) {
            Object input = step.input().apply(results);
            // As the system, or acting as the account (decision D82): no roles, no permissions, no session.
            RequestContext system = step.asAccount() == null
                ? RequestContext.system(locale, requestId(runId, step))
                : FirstLaunchActing.as(results.userId(step.asAccount()), locale, requestId(runId, step));
            FirstLaunchProcesses.StepOutput output = processes.executeAtEntry(FirstLaunchProcesses.STEP_PROCESS,
                    new FirstLaunchProcesses.StepInput(runId.toString(), step.key(), step.process(), input))
                .contextWrite(view -> RequestContexts.put(view, system))
                .block();
            Object value = output == null ? null : output.output();
            if (!step.await() || Boolean.TRUE.equals(value)) {
                return value;
            }
            if (clock.instant().isAfter(deadline)) {
                throw new IllegalStateException("Step " + step.key() + " was still waiting after "
                    + settings.contentTimeout() + " (jabiz.first-launch.content-timeout)");
            }
            sleep(settings.awaitInterval());
        }
    }

    /**
     * {@code first-launch-<run>-<step>}: labels every operation of the first launch in the record (section 6). Only a
     * label: the first launch's powers go by the operation's lineage, never by this id.
     */
    static String requestId(UUID runId, FirstLaunchStep step) {
        return "first-launch-" + runId + "-" + step.key();
    }

    /** Waits for another instance's content phase; opens the gate when it is done, exits when it failed. */
    void waitForOther() {
        while (true) {
            Optional<FirstLaunchRun> run = runs.current().contextWrite(this::asSystem).block();
            FirstLaunchRun.State state = run.map(FirstLaunchRun::state).orElse(FirstLaunchRun.State.RUNNING);
            if (state == FirstLaunchRun.State.FAILED) {
                log.error(text("first-launch.failed", Map.of("step", run.get().failedStep() == null ? "-"
                    : run.get().failedStep(), "reason", String.valueOf(run.get().failedReason()))));
                exit(1);
                return;
            }
            if (state != FirstLaunchRun.State.RUNNING) {
                gate.open();
                log.info(text("first-launch.content-done", Map.of()));
                return;
            }
            if (run.isPresent() && stale(run.get())) {
                String reason = text("first-launch.stale", Map.of("time", run.get().startedTime(),
                    "timeout", settings.contentTimeout()));
                try {
                    record(run.get().runId(), run.get().configHash(), "FAILED", null, "STALE", reason, null).block();
                } catch (RuntimeException e) {
                    log.debug("Another instance recorded the run first", e);
                }
                log.error(text("first-launch.failed", Map.of("step", "-", "reason", reason)));
                exit(1);
                return;
            }
            sleep(settings.awaitInterval());
        }
    }

    private boolean stale(FirstLaunchRun run) {
        return run.startedTime() != null
            && run.startedTime().plus(settings.contentTimeout()).isBefore(clock.instant());
    }

    private Mono<FirstLaunchProcesses.RecordOutput> record(UUID runId, String hash, String kind, String step,
        String code, String reason, String detail) {
        return processes.execute(FirstLaunchProcesses.RECORD_PROCESS, new FirstLaunchProcesses.RecordInput(
                runId.toString(), kind, step, code, reason, hash, detail))
            .contextWrite(this::asSystem);
    }

    private reactor.util.context.Context asSystem(reactor.util.context.Context view) {
        return RequestContexts.put(view, RequestContext.system(locale, "first-launch"));
    }

    private FirstLaunchFailedException failed(String step, String reason) {
        String message = text("first-launch.failed", Map.of("step", step == null ? "-" : step,
            "reason", reason == null ? "-" : reason));
        log.error(message);
        return new FirstLaunchFailedException(message);
    }

    private void exit(int code) {
        FirstLaunchExit exit = exits.getIfAvailable();
        if (exit != null) {
            exit.exit(code);
        } else {
            System.exit(code);
        }
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting", e);
        }
    }

    /** The reason of a failure, in words, without the stack. */
    private static String reason(RuntimeException e) {
        Throwable cause = e;
        while (cause.getCause() != null && cause.getMessage() == null) {
            cause = cause.getCause();
        }
        String message = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
        return message.length() > 2000 ? message.substring(0, 2000) + "…" : message;
    }

    private static String code(RuntimeException e) {
        return e.getClass().getSimpleName();
    }

    private Locale language(FirstLaunchConfig config) {
        if (config.language() == null || config.language().isBlank()) {
            return messages.defaultLocale();
        }
        return messages.supported(Locale.forLanguageTag(config.language()));
    }

    private String text(String key, Map<String, ?> params) {
        String template = messages.find(key, locale).orElse(key);
        for (Map.Entry<String, ?> param : params.entrySet()) {
            template = template.replace("{" + param.getKey() + "}", String.valueOf(param.getValue()));
        }
        return template;
    }

    /** The results so far of the content phase. */
    private record Results(Map<String, Object> outputs) implements FirstLaunchResults {

        @Override
        public Optional<Object> output(String stepKey) {
            return Optional.ofNullable(outputs.get(stepKey));
        }

        @Override
        public String userId(String accountKey) {
            Object output = output(FirstLaunchStep.accountStepKey(accountKey)).orElseThrow(() ->
                new IllegalStateException("The account " + accountKey + " has not been created"));
            if (output instanceof CharSequence id) {
                return id.toString();
            }
            if (output.getClass().isRecord()) {
                for (RecordComponent component : output.getClass().getRecordComponents()) {
                    if (component.getName().equals("userId")) {
                        try {
                            return String.valueOf(component.getAccessor().invoke(output));
                        } catch (ReflectiveOperationException e) {
                            throw new IllegalStateException(e);
                        }
                    }
                }
            }
            throw new IllegalStateException("The output of " + FirstLaunchStep.accountStepKey(accountKey)
                + " names no userId");
        }
    }

    /** The first launch stops the startup. */
    public static final class FirstLaunchFailedException extends RuntimeException {
        FirstLaunchFailedException(String message) {
            super(message);
        }
    }
}
