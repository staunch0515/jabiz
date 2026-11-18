package com.jabiz.runtime.firstlaunch.accounts;

import com.jabiz.context.RequestContext;
import com.jabiz.firstlaunch.FirstLaunchConfig;
import com.jabiz.runtime.context.RequestContexts;
import com.jabiz.runtime.firstlaunch.FirstLaunchProcesses;
import com.jabiz.runtime.firstlaunch.FirstLaunchRun;
import com.jabiz.runtime.firstlaunch.FirstLaunchRuns;
import com.jabiz.runtime.process.ProcessExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The one change the first launch takes from its configuration once the content phase is done (decision D83; plan
 * F-LAUNCH section 6.3): at a start in the waiting phase, when the configuration differs from the one the run
 * recorded only in the addresses of accounts that have not accepted their invitation, those accounts get the corrected
 * address ({@code FIRST_LAUNCH_EMAIL_REVISE}; the job {@code jabiz.first-launch} then invites the corrected address,
 * which was never invited: invitations count per address), and the run records {@code REVISED} with both hashes and the user ids. Any other difference does nothing
 * but warn: the rest is done in the administration as usual.
 */
@Component
public class FirstLaunchRevisions implements ApplicationListener<ApplicationReadyEvent> {

    private static final Logger log = LoggerFactory.getLogger(FirstLaunchRevisions.class);

    private final WaitingSettings settings;
    private final FirstLaunchRuns runs;
    private final FirstLaunchAccountStore store;
    private final ConfigShape shape;
    private final ProcessExecutor processes;
    private final JsonMapper json;
    private final Environment environment;
    private final Clock clock;
    private final org.springframework.beans.factory.ObjectProvider<com.jabiz.firstlaunch.FirstLaunchHandler> handlers;

    public FirstLaunchRevisions(WaitingSettings settings, FirstLaunchRuns runs, FirstLaunchAccountStore store,
        ConfigShape shape, ProcessExecutor processes,
        JsonMapper json, Environment environment, Clock clock,
        org.springframework.beans.factory.ObjectProvider<com.jabiz.firstlaunch.FirstLaunchHandler> handlers) {
        this.settings = settings;
        this.runs = runs;
        this.store = store;
        this.shape = shape;
        this.processes = processes;
        this.json = json;
        this.environment = environment;
        this.clock = clock;
        this.handlers = handlers;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        if (environment.getProperty("jabiz.ops.verify-restore.enabled", Boolean.class, false)) {
            return;
        }
        if (settings.config().isEmpty()) {
            Schedulers.boundedElastic().schedule(this::warnWithoutConfig);
            return;
        }
        Schedulers.boundedElastic().schedule(() -> {
            try {
                revise(settings.config().get());
            } catch (RuntimeException e) {
                log.error("First launch: correcting the accounts' addresses from the configuration failed", e);
            }
        });
    }

    /**
     * Compares {@code config} with the run's and applies the addresses it corrects.
     *
     * @return the accounts given a corrected address, by place in the configuration ({@code #1}, ...); empty when
     *         nothing was done
     */
    public List<String> revise(FirstLaunchConfig config) {
        Optional<FirstLaunchRun> found = asSystem(runs.current()).block();
        if (found.isEmpty() || found.get().state() != FirstLaunchRun.State.CONTENT_DONE) {
            return List.of();
        }
        FirstLaunchRun run = found.get();
        String applied = latestHash(run);
        if (config.hash().equals(applied)) {
            return List.of();
        }
        String recordedShape = asSystem(runs.detail(run.runId(), "CONTENT_DONE")).block()
            .map(text -> json.readTree(text).path("accounts").path("shape").asString(""))
            .orElse("");
        boolean otherDifferences = !shape.of(config).equals(recordedShape);
        List<FirstLaunchAccountStore.Recorded> accounts = asSystem(store.accounts(run.runId())).block();
        Map<UUID, FirstLaunchAccountStore.Facts> facts = asSystem(store.facts(accounts.stream()
            .map(FirstLaunchAccountStore.Recorded::userId).toList(), clock.instant()))
            .block();
        // Accounts are matched by their place in the configuration: the shape keeps the order and the keys.
        List<EmailRevision.Recorded> recorded = new ArrayList<>();
        for (FirstLaunchAccountStore.Recorded account : accounts) {
            FirstLaunchAccountStore.Facts user = facts.get(account.userId());
            if (user != null) {
                recorded.add(new EmailRevision.Recorded(account.label(), account.userId(), user.email(),
                    user.acceptedAt() != null));
            }
        }
        Map<String, String> emails = configuredEmails(config, accounts, facts.keySet());
        EmailRevision.Outcome outcome = EmailRevision.compare(recorded, emails, otherDifferences);
        if (!outcome.refusals().isEmpty()) {
            log.warn("First launch: the content phase is done; the configuration (hash {}) is not applied: {}. Only the"
                + " address of an account that has not accepted its invitation may still be corrected here; do the"
                + " rest in the administration as usual.", config.hash(), String.join("; ", outcome.refusals()));
            return List.of();
        }
        if (outcome.revisions().isEmpty()) {
            // Nothing to correct (another instance did, or only comments differ): nothing to record either.
            return List.of();
        }
        List<String> keys = new ArrayList<>();
        for (EmailRevision.Revision revision : outcome.revisions()) {
            String requestId = "first-launch-" + run.runId() + "-revise-" + revision.userId();
            asSystem(processes.execute(FirstLaunchAccountProcesses.REVISE_PROCESS,
                new FirstLaunchAccountProcesses.ReviseInput(revision.userId(), revision.email())), requestId).block();
            keys.add(revision.key());
            log.warn("First launch: account {} (user {}) now has the address of the configuration; the job"
                + " jabiz.first-launch invites it there within a minute, the earlier invitation no longer works",
                revision.key(), revision.userId());
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("previousHash", applied);
        detail.put("hash", config.hash());
        detail.put("users", outcome.revisions().stream().map(revision -> revision.userId().toString()).toList());
        asSystem(processes.execute(FirstLaunchProcesses.RECORD_PROCESS, new FirstLaunchProcesses.RecordInput(
            run.runId().toString(), "REVISED", null, null, null, config.hash(), json.writeValueAsString(detail))),
            "first-launch-" + run.runId() + "-revised").block();
        return List.copyOf(keys);
    }

    /**
     * A run still waits but the configuration is gone (or does not read): the application's steps for ready accounts
     * cannot run (section 9). Said at every start, and listed in the run's report.
     */
    private void warnWithoutConfig() {
        try {
            boolean waiting = asSystem(runs.current()).block()
                .map(run -> run.state() == FirstLaunchRun.State.CONTENT_DONE).orElse(false);
            if (waiting && handlers.orderedStream().anyMatch(handler -> !handler.accountReadyProcesses().isEmpty())) {
                log.warn("First launch: the run still waits for its accounts, but the first-launch configuration"
                    + " (JABIZ_FIRST_LAUNCH_CONFIG) is not there or does not read: the application's steps for ready"
                    + " accounts cannot run. Put the configuration back and restart; until then they are listed as"
                    + " left for people in the report.");
            }
        } catch (RuntimeException e) {
            log.error("First launch: reading the run failed", e);
        }
    }

    /**
     * The configuration's addresses by place ({@code #1}, ...), leaving out the places whose user was deleted meanwhile:
     * there is nobody left to correct, and the rest is compared as usual.
     */
    static Map<String, String> configuredEmails(FirstLaunchConfig config,
        List<FirstLaunchAccountStore.Recorded> accounts, java.util.Set<java.util.UUID> users) {
        java.util.Set<Integer> gone = new java.util.HashSet<>();
        accounts.stream().filter(account -> !users.contains(account.userId()))
            .forEach(account -> gone.add(account.position()));
        Map<String, String> emails = new LinkedHashMap<>();
        for (int i = 0; i < config.accounts().size(); i++) {
            if (!gone.contains(i)) {
                emails.put("#" + (i + 1), config.accounts().get(i).email());
            }
        }
        return emails;
    }

    /** The hash of the configuration last applied: the latest revision's, or the run's. */
    private String latestHash(FirstLaunchRun run) {
        return asSystem(runs.detail(run.runId(), "REVISED")).block()
            .map(text -> json.readTree(text).path("hash").asString(run.configHash()))
            .orElse(run.configHash());
    }

    private <T> Mono<T> asSystem(Mono<T> work) {
        return asSystem(work, "first-launch-revise");
    }

    private <T> Mono<T> asSystem(Mono<T> work, String requestId) {
        RequestContext system = RequestContext.system(Locale.ENGLISH, requestId);
        return work.contextWrite(view -> RequestContexts.put(view, system));
    }

}
