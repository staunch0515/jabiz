package com.jabiz.quizbuks.wallet;

import com.jabiz.entity.Violation;
import com.jabiz.external.ExternalJobCompletion;
import com.jabiz.external.ExternalJobFailure;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.identity.CallerChecks;
import com.jabiz.quizbuks.identity.QbAccounts;
import com.jabiz.runtime.BusinessRuleViolationException;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.inbound.LoadInboundEvent;
import com.jabiz.runtime.inbound.ReceivedEvent;
import com.jabiz.runtime.process.steps.HoldLock;
import com.jabiz.runtime.process.steps.QueryEntities;
import com.jabiz.runtime.process.steps.StartExternalJob;
import com.jabiz.runtime.task.CreateTask;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.jabiz.quizbuks.wallet.TopUpCrediting.first;

/**
 * Payout accounts (docs/quizbuks/plans/Q6-payments.md, D-Q6-10): a taker opens a Stripe Express account to receive
 * transfers. QuizBuks keeps no bank details; Stripe collects them on its own pages.
 * <ul>
 *   <li>{@value #ONBOARD} (the taker): checks the caller (verified, not banned, of the deployment's country) and
 *       starts the job that creates the account (once per user) and a link to Stripe's onboarding.</li>
 *   <li>{@value #ONBOARD_READY} / {@value #ONBOARD_FAILED} (the job's result, under the user's account lock): records
 *       the account ({@code PENDING}) and the link; an account that belongs to another user, or a user who has another
 *       account, is a task and changes nothing.</li>
 *   <li>{@value #ACCOUNT_UPDATED} ({@code account.updated} of the Connect endpoint) and {@value #ACCOUNT_DEAUTHORIZED}
 *       ({@code account.application.deauthorized}): the account's state as Stripe reports it, the newest report only
 *       (events come in any order); a disconnected account stays so.</li>
 * </ul>
 * The account is found by Stripe's id, unique in the table: no event can attach an account to another user.
 */
public final class PayoutProcesses {

    public static final String ONBOARD = "QB_PAYOUT_ONBOARD";
    public static final String ONBOARD_READY = "QB_PAYOUT_ONBOARD_READY";
    public static final String ONBOARD_FAILED = "QB_PAYOUT_ONBOARD_FAILED";
    public static final String ACCOUNT_UPDATED = "QB_PAYOUT_ACCOUNT_UPDATED";
    public static final String ACCOUNT_DEAUTHORIZED = "QB_PAYOUT_ACCOUNT_DEAUTHORIZED";
    public static final String UPDATED_CONSUMER = "qb.payout-account";
    public static final String DEAUTHORIZED_CONSUMER = "qb.payout-account-deauth";

    /** The input of {@value #ONBOARD}: nothing, the taker is the caller. */
    public record OnboardRequest() {}

    /** @param accountStatus the payout account's status, null before the first onboarding */
    public record OnboardOutput(String jobId, String accountStatus) {}

    /** @param outcome {@code RECORDED}, {@code NOTHING} or {@code CHECK} */
    public record AccountOutput(String userId, String status, String outcome, String reason) {}

    static final String INPUT = "input";
    static final String PROFILE = "profile";
    static final String ACCOUNT = "account";
    static final String BY_EXTERNAL = "byExternal";
    static final String FOUND = "found";
    static final String ONBOARDING = "onboarding";
    static final String EVENT = "event";
    static final String REPORT = "report";
    static final String JOB_ID = "jobId";
    static final String OUTCOME = "outcome";
    static final String REASON = "reason";
    static final String STATUS_AFTER = "statusAfter";

    // ---- onboarding ------------------------------------------------------------------------------------------------

    public static ProcessDefinition<OnboardRequest, OnboardOutput, ProcessContext> onboard(StripeSettings stripe,
        PayoutSettings settings) {
        return ProcessDefinition.define(ONBOARD, 1, OnboardRequest.class, OnboardOutput.class, ProcessContext.class,
            pb -> pb
                .description("Opens the taker's payout account at Stripe: the onboarding link is ready shortly after.")
                .permissions(QbPermissions.PAYOUT_ONBOARD)
                .requiresVerifiedEmail()
                .steps(CallerChecks::refuseBanned)
                .contextFactory(PayoutProcesses::context)
                .outputMapper(ctx -> {
                    EntityInstance account = first(ctx, ACCOUNT);
                    return new OnboardOutput((String) ctx.get(JOB_ID), account == null ? null : account.get("status"));
                })
                .step("Load the profile", QueryEntities.<ProcessContext>of(QbAccounts.defaultDataset(QbAccounts.PROFILE),
                    ctx -> byUser(ctx.request().actorId()), PROFILE))
                .step("Load the payout account", QueryEntities.<ProcessContext>of(
                    QbPayouts.defaultDataset(QbPayouts.ACCOUNT), ctx -> byUser(ctx.request().actorId()), ACCOUNT))
                .compute("Check the taker", (metadata, ctx) -> checkOnboarding(ctx, settings))
                .step("Open the account", StartExternalJob.<ProcessContext>of(PayoutJobs.CONNECT_ONBOARD, ctx -> {
                        EntityInstance account = first(ctx, ACCOUNT);
                        return new PayoutJobs.OnboardInput(ctx.request().actorId(), settings.connectCountry(),
                            account == null ? null : account.get("externalAccountId"), settings.returnUrl("done"),
                            settings.returnUrl("refresh"));
                    })
                    .reference(ctx -> ctx.request().actorId())
                    .outputKey(JOB_ID)));
    }

    static void checkOnboarding(ProcessContext ctx, PayoutSettings settings) {
        List<Violation> problems = new ArrayList<>();
        EntityInstance profile = first(ctx, PROFILE);
        String country = profile == null ? null : profile.get("countryCode");
        if (!settings.connectCountry().equals(country)) {
            problems.add(new Violation(null, WalletCodes.PAYOUT_COUNTRY_UNSUPPORTED, "Payout accounts are opened in "
                + settings.connectCountry() + " only", Map.of("country", settings.connectCountry())));
        }
        if (settings.appLink().isEmpty()) {
            problems.add(new Violation(null, WalletCodes.PAYOUT_UNAVAILABLE, "Payout accounts are not configured",
                Map.of()));
        }
        EntityInstance account = first(ctx, ACCOUNT);
        if (account != null && PayoutAccountStatus.DEAUTHORIZED.equals(account.get("status"))) {
            problems.add(new Violation(null, WalletCodes.PAYOUT_ACCOUNT_CLOSED, "The payout account was disconnected",
                Map.of()));
        }
        if (!problems.isEmpty()) {
            throw new BusinessRuleViolationException(problems);
        }
    }

    public static ProcessDefinition<ExternalJobCompletion, AccountOutput, ProcessContext> onboardReady(
        StripeSettings stripe, PayoutSettings settings) {
        return ProcessDefinition.define(ONBOARD_READY, 1, ExternalJobCompletion.class, AccountOutput.class,
            ProcessContext.class, pb -> pb
                .description("Records a taker's Stripe account and onboarding link.")
                .permissions(QbPermissions.PAYOUT_SYSTEM)
                .internal()
                .contextFactory(PayoutProcesses::context)
                .outputMapper(PayoutProcesses::output)
                .step("Lock the account", HoldLock.<ProcessContext>exclusive(
                    ctx -> QbAccounts.lockName(jobUser(ctx))))
                .step("Load the payout account", QueryEntities.<ProcessContext>of(
                    QbPayouts.defaultDataset(QbPayouts.ACCOUNT), ctx -> byUser(jobUser(ctx)), ACCOUNT))
                .step("Load the Stripe account's owner", QueryEntities.<ProcessContext>of(
                    QbPayouts.defaultDataset(QbPayouts.ACCOUNT), ctx -> byExternal(result(ctx) == null ? null
                        : result(ctx).accountId()), BY_EXTERNAL))
                .step("Load the link", QueryEntities.<ProcessContext>of(
                    QbPayouts.defaultDataset(QbPayouts.ONBOARDING), ctx -> byUser(jobUser(ctx)), ONBOARDING))
                .compute("Record the account", (metadata, ctx) -> ready(ctx, stripe, settings))
                .step("Ask a person", CreateTask.<ProcessContext>when(ctx -> ctx.contains(REASON), ctx -> {
                    ExternalJobCompletion job = ctx.get(INPUT, ExternalJobCompletion.class);
                    EntityInstance account = first(ctx, ACCOUNT);
                    return PayoutTasks.account((String) ctx.get(REASON), jobUser(ctx),
                        result(ctx) == null ? null : result(ctx).accountId(),
                        account == null ? null : String.valueOf(account.id()),
                        PayoutTasks.accountSource(String.valueOf(jobUser(ctx)), job.jobId()), null);
                })));
    }

    static void ready(ProcessContext ctx, StripeSettings stripe, PayoutSettings settings) {
        String userId = jobUser(ctx);
        PayoutObjects.Onboarding result = result(ctx);
        if (userId == null) {
            return;
        }
        String problem = PayoutChecks.onboardingProblem(result, stripe.livemode(), settings.connectUrlPrefix());
        if (problem != null) {
            check(ctx, problem);
            return;
        }
        EntityInstance own = first(ctx, ACCOUNT);
        EntityInstance owner = first(ctx, BY_EXTERNAL);
        if (owner != null && !userId.equals(owner.get("userId"))) {
            // The Stripe account is someone else's: never give it to this user, nor them the link.
            check(ctx, PayoutChecks.ACCOUNT_TAKEN);
            return;
        }
        if (own != null && !result.accountId().equals(own.get("externalAccountId"))) {
            check(ctx, PayoutChecks.ACCOUNT_CHANGED);
            return;
        }
        if (own == null) {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("userId", userId);
            values.put("provider", QbPayouts.PROVIDER);
            values.put("externalAccountId", result.accountId());
            values.put("country", StripeIds.is(java.util.regex.Pattern.compile("[A-Z]{2}"), result.accountCountry())
                ? result.accountCountry() : null);
            values.put("status", PayoutAccountStatus.PENDING);
            values.put("payoutsEnabled", false);
            values.put("detailsSubmitted", false);
            values.put("livemode", stripe.livemode());
            values.put("createdAt", ctx.opTime());
            ctx.changes().insert(QbPayouts.ACCOUNT, values);
            ctx.put(STATUS_AFTER, PayoutAccountStatus.PENDING);
        }
        Map<String, Object> link = new LinkedHashMap<>();
        link.put("url", result.url());
        link.put("expiresAt", Instant.ofEpochSecond(result.expiresAt()));
        EntityInstance onboarding = first(ctx, ONBOARDING);
        if (onboarding == null) {
            link.put("userId", userId);
            ctx.changes().insert(QbPayouts.ONBOARDING, link);
        } else {
            ctx.changes().update(QbPayouts.ONBOARDING, onboarding.id(), onboarding.version(), link);
        }
        ctx.put(OUTCOME, "RECORDED");
    }

    /** The taker asks again: nothing to record (the job's attempts are kept by the platform). */
    public static ProcessDefinition<ExternalJobFailure, AccountOutput, ProcessContext> onboardFailed() {
        return ProcessDefinition.define(ONBOARD_FAILED, 1, ExternalJobFailure.class, AccountOutput.class,
            ProcessContext.class, pb -> pb
                .description("Notes that a taker's payout account could not be opened; they may try again.")
                .permissions(QbPermissions.PAYOUT_SYSTEM)
                .internal()
                .contextFactory(PayoutProcesses::context)
                .outputMapper(ctx -> {
                    ExternalJobFailure job = ctx.get(INPUT, ExternalJobFailure.class);
                    return new AccountOutput(job.reference(), null, "NOTHING", job.errorCode());
                })
                .compute("Nothing to record", (metadata, ctx) -> { }));
    }

    // ---- Stripe's account events -----------------------------------------------------------------------------------

    public static ProcessDefinition<TopUpEvents.InboundRef, AccountOutput, ProcessContext> accountUpdated(
        StripeSettings stripe) {
        return accountConsumer(ACCOUNT_UPDATED, StripeService.ACCOUNT_UPDATED,
            "Records a payout account's state as Stripe reports it.", stripe, false);
    }

    public static ProcessDefinition<TopUpEvents.InboundRef, AccountOutput, ProcessContext> accountDeauthorized(
        StripeSettings stripe) {
        return accountConsumer(ACCOUNT_DEAUTHORIZED, StripeService.ACCOUNT_DEAUTHORIZED,
            "Records that a taker disconnected their payout account.", stripe, true);
    }

    private static ProcessDefinition<TopUpEvents.InboundRef, AccountOutput, ProcessContext> accountConsumer(
        String name, String eventType, String description, StripeSettings stripe, boolean deauthorized) {
        return ProcessDefinition.define(name, 1, TopUpEvents.InboundRef.class, AccountOutput.class,
            ProcessContext.class, pb -> pb
                .description(description)
                .permissions(QbPermissions.PAYOUT_SYSTEM)
                .internal()
                .contextFactory(PayoutProcesses::context)
                .outputMapper(PayoutProcesses::output)
                .step("Load the event", LoadInboundEvent.of(StripeService.PROVIDER_CONNECT, eventType,
                    ctx -> ctx.get(INPUT, TopUpEvents.InboundRef.class).inboundEventId(), EVENT))
                .compute("Read the account", (metadata, ctx) -> ctx.put(REPORT, report(ctx, deauthorized)))
                .step("Find the account", QueryEntities.<ProcessContext>of(QbPayouts.defaultDataset(QbPayouts.ACCOUNT),
                    ctx -> byExternal(report(ctx).accountId()), FOUND))
                .step("Lock the account", HoldLock.<ProcessContext>exclusive(ctx -> {
                    EntityInstance found = first(ctx, FOUND);
                    return found == null ? null : QbAccounts.lockName(found.get("userId"));
                }))
                .step("Read the account again", QueryEntities.<ProcessContext>of(
                    QbPayouts.defaultDataset(QbPayouts.ACCOUNT), ctx -> byExternal(report(ctx).accountId()), ACCOUNT))
                .compute("Record the state", (metadata, ctx) -> updated(ctx, stripe, deauthorized))
                .step("Ask a person", CreateTask.<ProcessContext>when(ctx -> ctx.contains(REASON), ctx -> {
                    EntityInstance account = first(ctx, ACCOUNT);
                    ReceivedEvent event = event(ctx);
                    return PayoutTasks.account((String) ctx.get(REASON), account == null ? null
                            : account.get("userId"), report(ctx).accountId(),
                        account == null ? null : String.valueOf(account.id()),
                        TopUpTasks.eventSource(event.externalEventId()), event.inboundEventId());
                })));
    }

    /**
     * What an account event reports.
     *
     * @param accountId the account the event is about (the event's own {@code account}, which must agree with the
     *                  object's id when both are given); null when it is not one
     */
    record Report(String accountId, PayoutObjects.Account account, boolean consistent) {}

    static Report report(ProcessContext ctx, boolean deauthorized) {
        ReceivedEvent event = event(ctx);
        String eventAccount = StripeIds.valid(StripeIds.ACCOUNT, event.at("account"));
        if (deauthorized) {
            return new Report(eventAccount, null, eventAccount != null);
        }
        PayoutObjects.Account account = PayoutObjects.account(event.at("data.object"));
        String objectAccount = StripeIds.valid(StripeIds.ACCOUNT, account.id());
        boolean consistent = objectAccount != null && (eventAccount == null || eventAccount.equals(objectAccount));
        return new Report(objectAccount, account, consistent);
    }

    static Report report(ProcessContext ctx) {
        return ctx.get(REPORT, Report.class);
    }

    static void updated(ProcessContext ctx, StripeSettings stripe, boolean deauthorized) {
        ReceivedEvent event = event(ctx);
        Report report = report(ctx);
        EntityInstance account = first(ctx, ACCOUNT);
        if (account == null || !report.consistent()) {
            Instant received = event.receivedTime() == null ? ctx.opTime() : event.receivedTime();
            if (report.consistent() && ctx.opTime().isBefore(received.plus(WalletLimits.UNKNOWN_PAYMENT_GRACE))) {
                // The account may not be recorded yet (its onboarding's result comes by another way): try again.
                throw new IllegalStateException("No payout account has this Stripe account yet");
            }
            check(ctx, PayoutChecks.UNKNOWN_ACCOUNT);
            return;
        }
        Boolean objectLivemode = report.account() == null ? event.livemode() : report.account().livemode();
        if (!Boolean.valueOf(stripe.livemode()).equals(event.livemode())
            || !Boolean.valueOf(stripe.livemode()).equals(objectLivemode)) {
            check(ctx, PayoutChecks.LIVEMODE);
            return;
        }
        if (PayoutAccountStatus.DEAUTHORIZED.equals(account.get("status"))) {
            ctx.put(OUTCOME, "NOTHING");
            return;
        }
        Instant happened = TopUpEvents.happened(event);
        Instant last = account.get("stripeUpdatedAt");
        Map<String, Object> values = new LinkedHashMap<>();
        if (deauthorized) {
            values.put("status", PayoutAccountStatus.DEAUTHORIZED);
            values.put("payoutsEnabled", false);
            values.put("stripeUpdatedAt", last == null || happened.isAfter(last) ? happened : last);
        } else {
            PayoutObjects.Account reported = report.account();
            if (reported.metadataUserId() != null && !reported.metadataUserId().equals(account.get("userId"))) {
                check(ctx, PayoutChecks.ACCOUNT_MISMATCH);
                return;
            }
            String status = PayoutAccountStatus.of(reported);
            if (!PayoutAccountStatus.replaces(account.get("status"), last, status, happened)) {
                // An older report than the one recorded (events come in any order), or one of the same second that
                // would hold the account back less.
                ctx.put(OUTCOME, "NOTHING");
                return;
            }
            values.put("status", status);
            values.put("payoutsEnabled", Boolean.TRUE.equals(reported.payoutsEnabled()));
            values.put("detailsSubmitted", Boolean.TRUE.equals(reported.detailsSubmitted()));
            values.put("requirementsDue", requirements(reported.requirementsDue()));
            values.put("stripeUpdatedAt", happened);
        }
        ctx.put(STATUS_AFTER, values.get("status"));
        ctx.put(OUTCOME, "RECORDED");
        ctx.changes().update(QbPayouts.ACCOUNT, account.id(), account.version(), values);
    }

    /** Stripe's requirement names, comma-separated, within the column (printable only). */
    static String requirements(List<String> due) {
        StringBuilder text = new StringBuilder();
        for (String name : due) {
            String shown = TopUpTasks.shown(name);
            if (text.length() + shown.length() + 1 > QbPayouts.REQUIREMENTS_LENGTH) {
                break;
            }
            if (!text.isEmpty()) {
                text.append(',');
            }
            text.append(shown);
        }
        return text.isEmpty() ? null : text.toString();
    }

    // ---- shared ----------------------------------------------------------------------------------------------------

    private static void check(ProcessContext ctx, String reason) {
        ctx.put(OUTCOME, "CHECK");
        ctx.put(REASON, reason);
    }

    static EntityQuery byUser(Object userId) {
        List<Object> ids = new ArrayList<>();
        if (userId != null) {
            ids.add(String.valueOf(userId));
        }
        return EntityQuery.builder().where(new QueryPredicate.In("userId", ids)).limit(1).build();
    }

    static EntityQuery byExternal(String accountId) {
        List<Object> ids = new ArrayList<>();
        if (StripeIds.is(StripeIds.ACCOUNT, accountId)) {
            ids.add(accountId);
        }
        return EntityQuery.builder().where(new QueryPredicate.In("externalAccountId", ids)).limit(1).build();
    }

    /** The user a job is about: its reference, as the onboarding process gave it. */
    private static String jobUser(ProcessContext ctx) {
        String reference = ctx.get(INPUT, ExternalJobCompletion.class).reference();
        return reference == null || reference.isBlank() || reference.length() > 128 ? null : reference;
    }

    private static PayoutObjects.Onboarding result(ProcessContext ctx) {
        return ctx.get(INPUT, ExternalJobCompletion.class).result(PayoutObjects.Onboarding.class);
    }

    private static ReceivedEvent event(ProcessContext ctx) {
        return ctx.get(EVENT, ReceivedEvent.class);
    }

    private static ProcessContext context(com.jabiz.process.ProcessStart start, Object input) {
        ProcessContext ctx = new ProcessContext(start);
        ctx.put(INPUT, input);
        return ctx;
    }

    private static AccountOutput output(ProcessContext ctx) {
        EntityInstance account = first(ctx, ACCOUNT);
        String status = ctx.contains(STATUS_AFTER) ? (String) ctx.get(STATUS_AFTER)
            : account == null ? null : account.get("status");
        return new AccountOutput(account == null ? null : account.get("userId"), status,
            ctx.contains(OUTCOME) ? (String) ctx.get(OUTCOME) : "NOTHING", (String) ctx.get(REASON));
    }

    private PayoutProcesses() {}
}
