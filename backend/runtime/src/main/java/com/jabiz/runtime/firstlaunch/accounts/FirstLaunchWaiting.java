package com.jabiz.runtime.firstlaunch.accounts;

import com.jabiz.firstlaunch.FirstLaunchHandler;
import com.jabiz.i18n.MessageCatalog;
import com.jabiz.job.JobDefinition;
import com.jabiz.job.JobItems;
import com.jabiz.mail.MailCategory;
import com.jabiz.mail.MailRecipient;
import com.jabiz.mail.MailTemplate;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.query.QueryPredicate;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.firstlaunch.FirstLaunchProcesses;
import com.jabiz.runtime.firstlaunch.FirstLaunchRun;
import com.jabiz.runtime.firstlaunch.accounts.GrantPlan.Action;
import com.jabiz.runtime.mail.SendMail;
import com.jabiz.runtime.process.steps.CallProcess;
import com.jabiz.runtime.process.steps.QueryEntities;
import com.jabiz.runtime.security.InvitationProcesses;
import com.jabiz.runtime.security.Rbac;
import com.jabiz.runtime.security.SecurityEntities;
import com.jabiz.runtime.security.protect.ProtectedChangeProcesses;
import com.jabiz.runtime.storage.Rows;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The first launch's waiting phase (docs/design/23-first-launch.md section 8; plan F-LAUNCH sections 5, 6.5): the job
 * {@value #JOB} runs every minute while the run's content phase is done and it has not ended. Its items, each in a
 * transaction of its own ({@code FIRST_LAUNCH_ADVANCE}), are judged by what is there now ({@link GrantPlan}), so running
 * them again does no harm:
 * <ol>
 *   <li>per account (key: the user id): the invitation ({@code SEC_USER_INVITE}, once, then once more after
 *       {@code remind-after}), the configured roles that do not make an administrator ({@code SEC_USER_ROLE_ASSIGN}),
 *       the waiting role's withdrawal once a configured role is in effect;</li>
 *   <li>per account again (key: {@code x} and the user id's 32 hex digits): the roles that make an administrator
 *       (decision D51) - only once the operator confirmed the account's fingerprint
 *       ({@code jabiz.first-launch.confirm-admins}); until then the log names the account by its place and user id,
 *       with the fingerprint (once an hour);</li>
 *   <li>the end (key: {@code end}): once every role is in effect or handed over to the administrators' approval, the
 *       run ends {@code DONE}; past {@code jabiz.first-launch.deadline} it ends {@code EXPIRED}. Either way the waiting
 *       role is disabled for good and the report goes to the report accounts and the log.</li>
 * </ol>
 * A disabled account, or one whose address someone else changed before it accepted, gets nothing more: it is left to
 * people and listed in the report. The log, the report and the run's events name accounts by their place in the
 * configuration and their user id, never by key or name (the key is the user's name: personal data).
 * Not registered when restoring a backup for verification ({@code jabiz.ops.verify-restore.enabled}).
 */
@Configuration
@ConditionalOnProperty(name = "jabiz.ops.verify-restore.enabled", havingValue = "false", matchIfMissing = true)
public class FirstLaunchWaiting {

    private static final Logger log = LoggerFactory.getLogger(FirstLaunchWaiting.class);

    public static final String JOB = "jabiz.first-launch";
    static final String ITEMS = "jabiz.first_launch_items";
    public static final String REPORT_PROCESS_NAME = "FIRST_LAUNCH_REPORT";
    /** The report mail: {@code summary} its one parameter, written in the recipient's language. */
    public static final String REPORT_TEMPLATE = com.jabiz.runtime.mail.PlatformMailTemplates.FIRST_LAUNCH_REPORT;
    static final String END = "end";

    /** The reason recorded with the grants: the first launch, by its configuration's hash. */
    static final String REASON = "First launch configuration ";

    /** @param item the account's user id (36 characters), {@code x} and its 32 hex digits, or {@value #END} */
    public record AdvanceInput(@NotBlank @Size(max = 36) String item) {}

    public record AdvanceOutput(String item, int assigned, int invited, boolean withdrawn, String ended) {}

    /** @param kind how the run ended: {@code DONE} or {@code EXPIRED}; the report is written in the user's language */
    public record ReportInput(@NotNull UUID userId, @NotBlank String kind) {}

    public record ReportOutput(String userId) {}

    static final String ITEM = "item";
    static final String ROUND = "round";
    static final String ASSIGNMENTS = "waiting_assignments";
    static final String WAITING_ROLE = "waiting_role";

    /** What one item does. */
    record Round(List<ProtectedChangeProcesses.AssignRoleInput> assign, List<InvitationProcesses.InviteInput> invite,
        UUID withdrawFrom, String end, List<ReportInput> reports, FirstLaunchRun run, String detail) {

        static final Round NOTHING = new Round(List.of(), List.of(), null, null, List.of(), null, null);
    }

    public static final MailTemplate REPORT_MAIL = MailTemplate.define(REPORT_TEMPLATE,
        t -> t.category(MailCategory.TRANSACTIONAL).param("summary")
            .sample("summary", "The first launch is complete: the system is in regular operation."));

    /** Throttles the log lines repeated every minute: each at most once an hour per instance. */
    static final class Throttle {
        private final Map<String, Instant> last = new ConcurrentHashMap<>();

        boolean due(String key, Instant now) {
            Instant before = last.get(key);
            if (before != null && now.isBefore(before.plus(Duration.ofHours(1)))) {
                return false;
            }
            last.put(key, now);
            return true;
        }
    }

    static ProcessDefinition<AdvanceInput, AdvanceOutput, ProcessContext> advance(WaitingSettings settings,
        MessageCatalog messages, ObjectProvider<FirstLaunchHandler> handlers, Throttle throttle, ReadySteps ready) {
        return ProcessDefinition.define(FirstLaunchAccountProcesses.ADVANCE, 1, AdvanceInput.class,
            AdvanceOutput.class, ProcessContext.class, pb -> pb
                .description("One item of the first launch's waiting phase: an account's invitations and grants, its"
                    + " administrator's grants, or the run's end; run by the job jabiz.first-launch only.")
                .permissions(FirstLaunchProcesses.PERMISSION)
                .internal()
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put(ITEM, input.item());
                    return ctx;
                })
                .outputMapper(ctx -> {
                    Round round = round(ctx);
                    return new AdvanceOutput(String.valueOf(ctx.get(ITEM)), round.assign().size(),
                        round.invite().size(), round.withdrawFrom() != null, round.end());
                })
                // The application's steps for a ready account, in a sub-process whose steps are its children.
                .step("Run the application's steps", CallProcess.when(ctx -> isReadyItem(ctx),
                    FirstLaunchAccountProcesses.ACCOUNT_READY, 1,
                    ctx -> new ReadyInput(ReadySteps.fromHex(String.valueOf(ctx.get(ITEM)).substring(1))), null))
                .step("Load the state", LoadWaitingState.of())
                .compute("Plan the round", (metadata, ctx) -> ctx.put(ROUND,
                    plan(ctx, settings, messages, handlers, throttle, ready)))
                .step("Load the waiting role's assignment", QueryEntities.of(SecurityEntities.USER_ROLE_DATASET,
                    ctx -> Rbac.all(waitingAssignment(ctx), "userRoleId"), ASSIGNMENTS))
                .step("Load the waiting role", QueryEntities.of(SecurityEntities.ROLE_DATASET,
                    ctx -> Rbac.all(new QueryPredicate.Eq("roleCode", round(ctx).end() == null ? "-"
                        : WaitingRole.CODE), "roleCode"), WAITING_ROLE))
                .compute("Withdraw the waiting role, end it", (metadata, ctx) -> {
                    @SuppressWarnings("unchecked")
                    List<EntityInstance> assignments = (List<EntityInstance>) ctx.get(ASSIGNMENTS);
                    assignments.forEach(assignment -> ctx.changes().delete(SecurityEntities.USER_ROLE,
                        assignment.id(), assignment.version()));
                    @SuppressWarnings("unchecked")
                    List<EntityInstance> roles = (List<EntityInstance>) ctx.get(WAITING_ROLE);
                    // The run's end: nobody gives, or signs in with, the waiting role again.
                    roles.stream().filter(Rbac::enabled).forEach(role -> ctx.changes().update(SecurityEntities.ROLE,
                        role.id(), role.version(), Map.of("enabled", false)));
                })
                .step("Grant the roles", CallProcess.forEach(ProtectedChangeProcesses.ASSIGN_ROLE, 1,
                    ctx -> round(ctx).assign(), null))
                .step("Invite", CallProcess.forEach(InvitationProcesses.INVITE, 1, ctx -> round(ctx).invite(), null))
                .step("Record the end", CallProcess.when(ctx -> round(ctx).end() != null, FirstLaunchProcesses.RECORD,
                    1, ctx -> {
                        Round round = round(ctx);
                        return new FirstLaunchProcesses.RecordInput(round.run().runId().toString(), round.end(), null,
                            null, null, round.run().configHash(), round.detail());
                    }, null))
                .step("Send the report", CallProcess.forEach(REPORT_PROCESS_NAME, 1, ctx -> round(ctx).reports(),
                    null)));
    }

    /** @param userId the ready account's user */
    public record ReadyInput(@NotNull UUID userId) {}

    /** @param ran how many of the application's steps ran now */
    public record ReadyOutput(String userId, int ran) {}

    static ProcessDefinition<ReadyInput, ReadyOutput, ProcessContext> ready() {
        return ProcessDefinition.define(FirstLaunchAccountProcesses.ACCOUNT_READY, 1, ReadyInput.class,
            ReadyOutput.class, ProcessContext.class, pb -> pb
                .description("Runs the application's steps for a first launch account once it is ready, each once;"
                    + " run by the job jabiz.first-launch only.")
                .permissions(FirstLaunchProcesses.PERMISSION)
                .internal()
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put(RunReadySteps.USER, input.userId());
                    return ctx;
                })
                .outputMapper(ctx -> new ReadyOutput(String.valueOf(ctx.get(RunReadySteps.USER)),
                    (Integer) ctx.get(RunReadySteps.RAN)))
                .step("Load the state", LoadWaitingState.of())
                .step("Run the application's steps", RunReadySteps.of()));
    }

    static ProcessDefinition<ReportInput, ReportOutput, ProcessContext> report(WaitingSettings settings,
        MessageCatalog messages, ObjectProvider<FirstLaunchHandler> handlers, ReadySteps ready) {
        return ProcessDefinition.define(REPORT_PROCESS_NAME, 1, ReportInput.class, ReportOutput.class,
            ProcessContext.class, pb -> pb
                .description("Mails the first launch's report to a report account; run by the first launch only.")
                .permissions(FirstLaunchProcesses.PERMISSION)
                .internal()
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put("input", input);
                    return ctx;
                })
                .outputMapper(ctx -> new ReportOutput(String.valueOf(ctx.get("input", ReportInput.class).userId())))
                // Written here, from the state, not passed in: what the report says stays out of the input summary.
                .step("Load the state", LoadWaitingState.ofEnded())
                .compute("Write the report", (metadata, ctx) -> {
                    ReportInput input = ctx.get("input", ReportInput.class);
                    LoadWaitingState.State state = ctx.get(LoadWaitingState.STATE, LoadWaitingState.State.class);
                    FirstLaunchAccountStore.Facts facts = state.facts().get(input.userId());
                    Locale locale = facts == null || facts.locale() == null ? Locale.ENGLISH
                        : Locale.forLanguageTag(facts.locale());
                    ctx.put("summary", WaitingReport.summary(input.kind(), state, accounts(state),
                        remaining(settings, handlers, ready, state, messages, locale), messages, locale));
                })
                .step("Mail the report", SendMail.of(REPORT_MAIL,
                    ctx -> MailRecipient.user(ctx.get("input", ReportInput.class).userId()),
                    Map.of("summary", ctx -> ctx.get("summary")))));
    }

    /**
     * What is left for people beyond the grants: the application's steps for accounts that did not run (section 9),
     * then {@link FirstLaunchHandler#remaining}, from the startup's configuration.
     */
    static List<String> remaining(WaitingSettings settings, ObjectProvider<FirstLaunchHandler> handlers,
        ReadySteps ready, LoadWaitingState.State state, MessageCatalog messages, Locale locale) {
        List<String> remaining = new ArrayList<>();
        for (FirstLaunchAccountStore.Recorded recorded : state.accounts()) {
            if (!state.facts().containsKey(recorded.userId())) {
                continue;
            }
            List<String> notRun = ready.notRunDescribed(state, recorded);
            if (!notRun.isEmpty()) {
                remaining.add(messages.find("first-launch.report.ready-not-run", locale)
                    .orElse("{key} (user {user}): the application's steps did not run: {steps}")
                    .replace("{key}", recorded.label()).replace("{user}", recorded.userId().toString())
                    .replace("{steps}", String.join(", ", notRun)));
            }
        }
        settings.config().ifPresent(found -> handlers.orderedStream().forEach(handler -> remaining.addAll(
            handler.remaining(found))));
        return List.copyOf(remaining);
    }

    /** Whether the item is a ready account's: {@code r} and the user id's 32 hex digits. */
    static boolean isReadyItem(ProcessContext ctx) {
        return String.valueOf(ctx.get(ITEM)).startsWith("r");
    }

    static Round round(ProcessContext ctx) {
        return ctx.contains(ROUND) ? ctx.get(ROUND, Round.class) : Round.NOTHING;
    }

    /** The waiting role's live assignment to the user the round withdraws it from (none: matches nothing). */
    private static QueryPredicate waitingAssignment(ProcessContext ctx) {
        Round round = round(ctx);
        LoadWaitingState.State state = ctx.contains(LoadWaitingState.STATE)
            ? ctx.get(LoadWaitingState.STATE, LoadWaitingState.State.class) : null;
        if (round.withdrawFrom() == null || state == null || !state.roles().containsKey(WaitingRole.CODE)) {
            return new QueryPredicate.In("userId", List.of());
        }
        return new QueryPredicate.And(List.of(new QueryPredicate.Eq("userId", round.withdrawFrom()),
            new QueryPredicate.Eq("roleId", state.roles().get(WaitingRole.CODE).roleId())));
    }

    /** The accounts as {@link GrantPlan} sees them. */
    static List<GrantPlan.Account> accounts(LoadWaitingState.State state) {
        List<GrantPlan.Account> accounts = new ArrayList<>();
        for (FirstLaunchAccountStore.Recorded recorded : state.accounts()) {
            FirstLaunchAccountStore.Facts facts = state.facts().get(recorded.userId());
            if (facts == null || recorded.roles().isEmpty()) {
                continue;
            }
            Map<String, GrantPlan.RoleState> states = new LinkedHashMap<>();
            List<GrantPlan.Role> roles = new ArrayList<>();
            for (String code : recorded.roles()) {
                roles.add(new GrantPlan.Role(code, state.adminRoles().contains(code)));
                FirstLaunchAccountStore.Role role = state.roles().get(code);
                if (facts.roles().contains(code)) {
                    states.put(code, GrantPlan.RoleState.APPLIED);
                } else if (role != null && facts.pendingRoles().contains(role.roleId())) {
                    states.put(code, GrantPlan.RoleState.PENDING);
                } else if (role != null && facts.decidedRoles().contains(role.roleId())) {
                    states.put(code, GrantPlan.RoleState.DECIDED);
                }
            }
            accounts.add(new GrantPlan.Account(recorded.label(), recorded.userId(), roles, facts.acceptedAt(),
                facts.mfaConfirmedAt(), states, facts.roles().contains(WaitingRole.CODE), facts.firstInvited(),
                facts.invitations() > 1, facts.enabled(), state.invitable().contains(recorded.userId())));
        }
        return List.copyOf(accounts);
    }

    /** What this item does now. */
    static Round plan(ProcessContext ctx, WaitingSettings settings, MessageCatalog messages,
        ObjectProvider<FirstLaunchHandler> handlers, Throttle throttle, ReadySteps ready) {
        if (!ctx.contains(LoadWaitingState.STATE)) {
            return Round.NOTHING;
        }
        LoadWaitingState.State state = ctx.get(LoadWaitingState.STATE, LoadWaitingState.State.class);
        String item = String.valueOf(ctx.get(ITEM));
        Instant now = ctx.opTime();
        List<GrantPlan.Account> accounts = accounts(state);
        String runId = state.run().runId().toString();
        List<Action> actions = GrantPlan.next(runId, accounts, settings.confirmed(), now, settings.remindAfter());
        String reason = REASON + state.run().configHash();
        if (END.equals(item)) {
            return end(state, accounts, now, settings, messages, handlers, throttle, ready);
        }
        if (item.startsWith("r")) {
            return Round.NOTHING;
        }
        // A user id has 36 characters; the administrator's item is "x" (no hex digit) and its 32 hex digits.
        boolean administrators = item.startsWith("x");
        UUID user = administrators ? fromHex(item.substring(1)) : UUID.fromString(item);
        List<ProtectedChangeProcesses.AssignRoleInput> assign = new ArrayList<>();
        List<InvitationProcesses.InviteInput> invite = new ArrayList<>();
        UUID withdraw = null;
        for (Action action : actions) {
            if (!action.userId().equals(user)) {
                continue;
            }
            switch (action) {
                case Action.Assign granted -> {
                    if (state.adminRoles().contains(granted.role()) != administrators) {
                        continue;
                    }
                    FirstLaunchAccountStore.Role role = state.roles().get(granted.role());
                    if (role == null) {
                        if (throttle.due("missing:" + granted.role(), now)) {
                            log.error("First launch: the role {} of account {} (user {}) does not exist; it cannot be"
                                + " granted", granted.role(), granted.key(), user);
                        }
                        continue;
                    }
                    assign.add(new ProtectedChangeProcesses.AssignRoleInput(user, role.roleId(), null, null, null,
                        reason));
                    log.info("First launch: granting {} to account {} (user {})", granted.role(), granted.key(), user);
                }
                case Action.WithdrawWaitingRole withdrawn -> {
                    if (!administrators) {
                        withdraw = user;
                    }
                }
                case Action.Invite invited -> {
                    if (!administrators) {
                        invite.add(new InvitationProcesses.InviteInput(user, InvitationProcesses.DEFAULT_ENTRY));
                        log.info("First launch: inviting account {} (user {})", invited.key(), user);
                    }
                }
                case Action.Remind reminded -> {
                    if (!administrators) {
                        invite.add(new InvitationProcesses.InviteInput(user, InvitationProcesses.DEFAULT_ENTRY));
                        log.warn("First launch: account {} (user {}) has not accepted its invitation after {};"
                            + " invited once more", reminded.key(), user, settings.remindAfter());
                    }
                }
                case Action.AwaitConfirmation awaiting -> {
                    if (administrators && throttle.due("confirm:" + awaiting.fingerprint(), now)) {
                        FirstLaunchAccountStore.Facts facts = state.facts().get(user);
                        log.warn("First launch: account {} (user {}) accepted its invitation at {} and bound a second"
                            + " factor at {}. It gets an administrator's role only once the operator has confirmed"
                            + " it with the person: fingerprint {}. Add it to JABIZ_FIRST_LAUNCH_CONFIRM_ADMINS and"
                            + " restart.", awaiting.key(), user, facts.acceptedAt(), facts.mfaConfirmedAt(),
                            awaiting.fingerprint());
                    }
                }
            }
        }
        return new Round(List.copyOf(assign), List.copyOf(invite), withdraw, null, List.of(), state.run(), null);
    }

    /** The end item: the run ends, or the hourly line of what it waits for. */
    private static Round end(LoadWaitingState.State state, List<GrantPlan.Account> accounts, Instant now,
        WaitingSettings settings, MessageCatalog messages, ObjectProvider<FirstLaunchHandler> handlers,
        Throttle throttle, ReadySteps ready) {
        boolean expired = GrantPlan.expired(state.run().stateTime(), settings.deadline(), now);
        // An account whose user was deleted meanwhile is nobody's to wait for; the application's steps of a ready
        // account are, until they ran or failed.
        boolean stepsDone = state.accounts().stream().allMatch(recorded -> ready.waitingFor(state, recorded,
            accounts.stream().filter(account -> account.userId().equals(recorded.userId())).findFirst()
                .orElse(null)).isEmpty());
        boolean complete = GrantPlan.complete(accounts) && stepsDone;
        if (!expired && !complete) {
            if (throttle.due("waiting", now)) {
                GrantPlan.unfinished(accounts).forEach(account -> log.info("First launch: waiting for account {}"
                    + " (user {}): {}", account.key(), account.userId(), account.acceptedAt() == null ? "has not accepted its invitation (first"
                    + " sent " + account.invitedAt() + ")" : account.mfaConfirmedAt() == null
                    ? "has not bound a second factor" : "roles not given yet"));
            }
            return Round.NOTHING;
        }
        String kind = expired && !complete ? "EXPIRED" : "DONE";
        List<ReportInput> reports = new ArrayList<>();
        for (FirstLaunchAccountStore.Recorded recorded : state.accounts()) {
            if (recorded.report() && state.facts().containsKey(recorded.userId())) {
                reports.add(new ReportInput(recorded.userId(), kind));
            }
        }
        String summary = WaitingReport.summary(kind, state, accounts, remaining(settings, handlers, ready, state,
            messages, Locale.ENGLISH), messages, Locale.ENGLISH);
        if ("DONE".equals(kind)) {
            log.info(summary);
        } else {
            log.warn(summary);
        }
        return new Round(List.of(), List.of(), null, kind, List.copyOf(reports), state.run(),
            WaitingReport.detail(state.accounts(), accounts));
    }

    private static UUID fromHex(String hex) {
        return UUID.fromString(hex.substring(0, 8) + "-" + hex.substring(8, 12) + "-" + hex.substring(12, 16) + "-"
            + hex.substring(16, 20) + "-" + hex.substring(20));
    }

    // ---- beans ----------------------------------------------------------------------------------------------------

    @Bean
    Throttle firstLaunchWaitingThrottle() {
        return new Throttle();
    }

    @Bean
    ProcessDefinition<AdvanceInput, AdvanceOutput, ProcessContext> firstLaunchAdvanceProcess(WaitingSettings settings,
        MessageCatalog messages, ObjectProvider<FirstLaunchHandler> handlers, Throttle throttle, ReadySteps ready) {
        return advance(settings, messages, handlers, throttle, ready);
    }

    @Bean
    ProcessDefinition<ReadyInput, ReadyOutput, ProcessContext> firstLaunchAccountReadyProcess() {
        return ready();
    }

    @Bean
    ProcessDefinition<ReportInput, ReportOutput, ProcessContext> firstLaunchReportProcess(WaitingSettings settings,
        MessageCatalog messages, ObjectProvider<FirstLaunchHandler> handlers, ReadySteps ready) {
        return report(settings, messages, handlers, ready);
    }

    @Bean
    MailTemplate firstLaunchReportMail() {
        return REPORT_MAIL;
    }

    /** Every minute (unless {@code jabiz.first-launch.cron}); nothing is selected unless a run waits. */
    @Bean
    JobDefinition<AdvanceInput> firstLaunchJob(@Value("${jabiz.first-launch.cron:0 * * * * *}") String cron,
        ProcessDefinition<AdvanceInput, AdvanceOutput, ProcessContext> firstLaunchAdvanceProcess) {
        return JobDefinition.forEach(JOB, cron, ZoneOffset.UTC, JobItems.template(ITEMS, "itemKey"),
            firstLaunchAdvanceProcess, (scheduledTime, row) -> new AdvanceInput(Rows.string(row.get("itemKey"))));
    }

}
