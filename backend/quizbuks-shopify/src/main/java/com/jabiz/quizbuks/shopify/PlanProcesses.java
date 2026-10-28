package com.jabiz.quizbuks.shopify;

import com.jabiz.entity.Violation;
import com.jabiz.external.ExternalCallException;
import com.jabiz.param.ParamValues;
import com.jabiz.process.BlockingStep;
import com.jabiz.process.NoMetadata;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.process.ProcessDefinitionBuilder;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.inbound.LoadInboundEvent;
import com.jabiz.runtime.inbound.ReceivedEvent;
import com.jabiz.runtime.process.steps.HoldLock;
import com.jabiz.runtime.process.steps.LoadParams;
import com.jabiz.runtime.process.steps.QueryEntities;
import com.jabiz.runtime.security.secret.SecretSealer;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * A shop's plan and its use (docs/quizbuks/shopify/S3-billing.md sections 2 and 3).
 *
 * <p>The plan is read from Shopify ({@code currentAppInstallation.activeSubscriptions}, with the shop's access token,
 * under its token lock as every use of the token) and kept in {@value QbsBilling#PLAN}:
 * <ul>
 *   <li>{@value #SYNC}: by the daily job {@value #JOB}, for each installed, connected shop;</li>
 *   <li>{@value #SUBSCRIPTIONS_UPDATE}: when Shopify reports a change of a shop's subscription (webhook
 *       {@value #TOPIC}). The body names no shop's address, so the shop is the one the webhook's header names; the
 *       body is only a hint, what Shopify answers is the plan (a header naming another shop only has that shop's
 *       plan read again);</li>
 *   <li>{@value #GET}: when a member opens the plan page, at most every {@link #READ_AGAIN} unless asked to.</li>
 * </ul>
 * What cannot be read (no tokens, Shopify fails) leaves the plan as it was: a shop never read is on the free plan,
 * which works, so the storefront never stops because Shopify could not be asked.
 *
 * <p>The use: each attempt the storefront completes counts once ({@link #count}, in the submission's transaction under
 * the shop's usage lock), in the period it falls in; the storefront starts no new attempt once the period's count
 * reached the plan's limit ({@link #overLimit}).
 */
public final class PlanProcesses {

    private static final Logger log = LoggerFactory.getLogger(PlanProcesses.class);

    private PlanProcesses() {}

    public static final String SYNC = "QBS_PLAN_SYNC";
    public static final String GET = "QBS_PLAN_GET";
    public static final String SUBSCRIPTIONS_UPDATE = "QBS_SHOPIFY_APP_SUBSCRIPTIONS_UPDATE";
    public static final String TOPIC = "app_subscriptions/update";
    public static final String JOB = "qbs.plan-sync";
    public static final String SHOPS_TEMPLATE = "qbs.system.plan-shops";

    /** How long what was read of a shop's plan is shown before the plan page reads it again by itself. */
    public static final Duration READ_AGAIN = Duration.ofHours(1);

    public record SyncInput(@NotBlank @Size(max = Shopify.SHOP_DOMAIN_LENGTH) String shopDomain) {}

    /** The shop, its plan now, and whether Shopify was read. */
    public record SyncOutput(String shopDomain, String plan, boolean read) {}

    /** @param refresh read Shopify now (back from its plan page) */
    public record GetInput(boolean refresh) {}

    /**
     * A shop's plan and its use, as the plan page shows them.
     *
     * @param plan        the plan in force (a higher one kept after a move down, until {@code planUntil})
     * @param nextPlan    the plan that follows {@code plan} at {@code planUntil}; null when it is not kept
     * @param shopifyPlan Shopify's name of the subscription, if any
     * @param unknownPlan whether Shopify's plan is none of ours (then counted as free; a configuration to fix)
     * @param test        whether Shopify charges nothing for it (a development store)
     * @param used        the attempts the storefront completed in the period
     * @param limit       the most the plan completes in a period; the storefront starts none at it
     * @param pricingUrl  Shopify's page of the plans (opened at the top of the admin)
     */
    public record PlanOutput(String plan, String nextPlan, Instant planUntil, String shopifyPlan, boolean unknownPlan,
        boolean test, Instant trialEndsAt, Instant periodStart, Instant periodEnd, long used, long limit,
        Instant checkedAt, String connection, String pricingUrl) {}

    /** The plan a reading found: ours, and the subscription it came from (none on the free plan). */
    record Found(String plan, ShopifyApi.Subscription subscription) {}

    static final String INPUT = ShopProcesses.INPUT;
    static final String SHOPS = ShopProcesses.SHOPS;
    static final String MINE = ShopProcesses.MINE;
    static final String TOKENS = ConnectionProcesses.TOKENS;
    static final String EVENT = "event";
    static final String DOMAIN = "domain";
    static final String PLANS = "plans";
    static final String SEEN = "planSeen";
    static final String READING = "reading";
    static final String FOUND = "found";
    static final String NOW = "planNow";
    static final String PERIOD = "period";
    static final String USAGES = "usages";
    static final String LIMITS = "limits";
    static final String OUTPUT = ShopProcesses.OUTPUT;

    // ---- reading Shopify -------------------------------------------------------------------------------------------

    static ProcessDefinition<SyncInput, SyncOutput, ProcessContext> sync() {
        return ProcessDefinition.define(SYNC, 1, SyncInput.class, SyncOutput.class, ProcessContext.class, pb -> pb
            .description("Reads a shop's plan from Shopify again (the daily job).")
            .permissions(Shopify.SYSTEM)
            .internal()
            .contextFactory(ShopProcesses::context)
            .outputMapper(ctx -> ctx.get(OUTPUT, SyncOutput.class))
            .compute("Read the shop", (metadata, ctx) -> ctx.put(DOMAIN,
                Shopify.lower(ctx.get(INPUT, SyncInput.class).shopDomain())))
            .steps(PlanProcesses::readSteps)
            .compute("Answer", (metadata, ctx) -> ctx.put(OUTPUT, syncOutput(ctx))));
    }

    static ProcessDefinition<WebhookProcesses.InboundRef, WebhookProcesses.Outcome, ProcessContext>
        subscriptionsUpdate() {
        return ProcessDefinition.define(SUBSCRIPTIONS_UPDATE, 1, WebhookProcesses.InboundRef.class,
            WebhookProcesses.Outcome.class, ProcessContext.class, pb -> pb
                .description("Shopify reports that a shop's subscription changed: its plan is read again.")
                .permissions(Shopify.SYSTEM)
                .internal()
                .contextFactory(ShopProcesses::context)
                .outputMapper(ctx -> ctx.get(OUTPUT, WebhookProcesses.Outcome.class))
                .step("Load the event", LoadInboundEvent.of(Shopify.WEBHOOKS, TOPIC,
                    ctx -> ctx.get(INPUT, WebhookProcesses.InboundRef.class).inboundEventId(), EVENT))
                .compute("Read the shop", (metadata, ctx) -> ctx.put(DOMAIN, headerShop(ctx)))
                .steps(PlanProcesses::readSteps)
                .compute("Answer", (metadata, ctx) -> {
                    SyncOutput sync = syncOutput(ctx);
                    ctx.put(OUTPUT, new WebhookProcesses.Outcome(sync.shopDomain(), sync.shopDomain() == null
                        ? QbsShops.MISMATCH : ShopProcesses.first(ctx, SHOPS) == null ? QbsShops.NOT_FOUND
                        : sync.plan()));
                }));
    }

    /** The shop the webhook's header names; null (nothing done) when it names none. */
    static String headerShop(ProcessContext ctx) {
        ReceivedEvent event = ctx.get(EVENT, ReceivedEvent.class);
        String header = Shopify.lower(event.metadata() == null ? null : event.metadata().get(Shopify.SHOP_HEADER));
        if (!Shopify.isShopDomain(header)) {
            log.warn("Shopify webhook {} ({}) names no shop in its header: not acted on", event.inboundEventId(),
                event.eventType());
            return null;
        }
        return header;
    }

    /** Reads the plan of the shop {@link #DOMAIN} names (none: nothing), under its token lock, and keeps it. */
    static void readSteps(ProcessDefinitionBuilder<?, ?, ProcessContext> pb) {
        pb.step("Lock the shop's tokens", HoldLock.<ProcessContext>exclusive(ctx -> {
                String domain = ctx.get(DOMAIN, String.class);
                return domain == null ? null : Shopify.tokenLock(domain);
            }))
            .step("Load the shop and its plan", QueryEntities.all(
                QueryEntities.<ProcessContext>query(QbsShops.defaultDataset(QbsShops.SHOP),
                    ctx -> ShopProcesses.eq("shopDomain", ctx.get(DOMAIN, String.class), 1), SHOPS),
                planQuery(ctx -> ctx.get(DOMAIN, String.class))))
            .step("Load the shop's tokens", QueryEntities.<ProcessContext>of(
                QbsShops.defaultDataset(QbsShops.TOKEN), ConnectionProcesses::tokensOfShop, TOKENS))
            .compute("Decide", (metadata, ctx) -> ctx.put(READING, readable(ctx)))
            .steps(PlanProcesses::readAndKeep);
    }

    /** Whether Shopify can be asked about the shop: installed, connected, with its tokens. */
    static boolean readable(ProcessContext ctx) {
        EntityInstance shop = ShopProcesses.first(ctx, SHOPS);
        return !ctx.hasViolations() && shop != null && QbsShops.ACTIVE.equals(shop.get("status"))
            && QbsShops.CONNECTED.equals(shop.get("connection")) && ShopProcesses.first(ctx, TOKENS) != null;
    }

    static void readAndKeep(ProcessDefinitionBuilder<?, ?, ProcessContext> pb) {
        pb.step("Read Shopify", ReadPlan.class, NoMetadata.INSTANCE)
            .step("Seal the access token", ConnectionProcesses.sealAccess())
            .step("Seal the refresh token", ConnectionProcesses.sealRefresh())
            .compute("Keep", (metadata, ctx) -> keep(ctx));
    }

    static SyncOutput syncOutput(ProcessContext ctx) {
        return new SyncOutput(ctx.get(DOMAIN, String.class),
            QbsBilling.plan(ctx.get(NOW, EntityInstance.class), ctx.opTime()), ctx.get(FOUND) != null);
    }

    /**
     * Asks Shopify for the shop's subscriptions when {@link #READING}, with its access token (refreshed when about to
     * end, dropped when refused, as {@link ConnectionProcesses} does), and puts the plan they make ({@link #FOUND}). A
     * call that fails leaves nothing found: the plan stays as it was.
     */
    @Component
    public static class ReadPlan implements BlockingStep<NoMetadata, ProcessContext> {

        private final ShopifyApi api;
        private final SecretSealer sealer;
        private final ShopifyPlans plans;

        public ReadPlan(ShopifyApi api, SecretSealer sealer, ShopifyPlans plans) {
            this.api = api;
            this.sealer = sealer;
            this.plans = plans;
        }

        @Override
        public void run(NoMetadata metadata, ProcessContext ctx) {
            if (!Boolean.TRUE.equals(ctx.get(READING)) || ctx.hasViolations()) {
                return;
            }
            String domain = ConnectionProcesses.shopDomain(ctx);
            ShopifyApi.Answer<List<ShopifyApi.Subscription>> answer;
            try {
                String access = ConnectionProcesses.access(ctx, api, sealer, domain);
                if (access == null) {
                    return;
                }
                answer = api.activeSubscriptions(domain, access);
            } catch (ExternalCallException e) {
                // A pair refreshed before is still kept; the plan stays as it was and is read again later.
                log.warn("Plan of {} not read: {}", domain, e.getMessage());
                return;
            }
            if (answer.refused()) {
                ctx.put(ConnectionProcesses.DROPPED, true);
                ctx.put(ConnectionProcesses.OBTAINED, null);
                return;
            }
            ctx.put(FOUND, found(answer.value(), domain));
        }

        /** The highest of our plans among the active subscriptions; free without one, or with none of ours. */
        Found found(List<ShopifyApi.Subscription> subscriptions, String domain) {
            List<ShopifyApi.Subscription> active = subscriptions.stream()
                .filter(s -> QbsBilling.ACTIVE.equalsIgnoreCase(s.status())).toList();
            Optional<Found> ours = active.stream()
                .flatMap(s -> plans.planOf(s.name()).map(plan -> new Found(plan, s)).stream())
                .max(Comparator.comparingInt(f -> QbsBilling.PLANS.indexOf(f.plan())));
            if (ours.isPresent()) {
                return ours.get();
            }
            if (!active.isEmpty()) {
                log.warn("Shop {} is on Shopify plan '{}', which is none of QuizBuks's: counted as free", domain,
                    active.getFirst().name());
                return new Found(QbsBilling.FREE, active.getFirst());
            }
            return new Found(QbsBilling.FREE, null);
        }
    }

    /**
     * Keeps what the reading brought: the tokens (as every use of them) and the plan. A move to a lower plan keeps
     * the higher one until the end of the period it was paid for; any other change ends what was kept. Puts the
     * plan's row as it is now ({@link #NOW}).
     */
    static void keep(ProcessContext ctx) {
        EntityInstance row = ShopProcesses.first(ctx, PLANS);
        ctx.put(NOW, row);
        if (!Boolean.TRUE.equals(ctx.get(READING)) || ctx.hasViolations()) {
            return;
        }
        ConnectionProcesses.keepTokens(ctx);
        Found found = ctx.get(FOUND, Found.class);
        if (found == null) {
            return;
        }
        Instant now = ctx.opTime();
        ShopifyApi.Subscription subscription = found.subscription();
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("plan", found.plan());
        values.put("subscriptionId", subscription == null ? null : clip(subscription.id(), QbsBilling.GID_LENGTH));
        values.put("subscriptionName", subscription == null ? null
            : clip(subscription.name(), QbsBilling.NAME_LENGTH));
        values.put("test", subscription != null && subscription.test());
        values.put("trialEndsAt", subscription == null || subscription.trialDays() <= 0
            || subscription.createdAt() == null ? null
            : subscription.createdAt().plus(Duration.ofDays(subscription.trialDays())));
        values.put("periodEnd", subscription == null ? null : subscription.currentPeriodEnd());
        String before = QbsBilling.plan(row, now);
        if (!QbsBilling.FREE.equals(before) && QbsBilling.above(before, found.plan())) {
            // The period as it was counted: its use stays in the same row.
            QbsBilling.Period paid = QbsBilling.period(row, now);
            values.put("keptPlan", before);
            values.put("keptFrom", paid.start());
            values.put("keptUntil", paid.end());
        } else {
            values.put("keptPlan", null);
            values.put("keptFrom", null);
            values.put("keptUntil", null);
        }
        values.put("checkedAt", now);
        EntityInstance shop = ShopProcesses.first(ctx, SHOPS);
        if (row == null) {
            values.put("shopId", String.valueOf(shop.id()));
            values.put("shopDomain", shop.get("shopDomain"));
            ctx.changes().insert(QbsBilling.PLAN, values);
            ctx.put(NOW, new EntityInstance(shop.id(), QbsBilling.PLAN, 0, null, values));
        } else {
            ctx.changes().update(QbsBilling.PLAN, row.id(), row.version(), values);
            Map<String, Object> merged = new LinkedHashMap<>(row.attributes());
            merged.putAll(values);
            ctx.put(NOW, new EntityInstance(row.id(), QbsBilling.PLAN, row.version() + 1, null, merged));
        }
    }

    static String clip(String value, int length) {
        return value.length() > length ? value.substring(0, length) : value;
    }

    // ---- the plan page ---------------------------------------------------------------------------------------------

    static ProcessDefinition<GetInput, PlanOutput, ProcessContext> get(ShopifyPlans plans) {
        return ProcessDefinition.define(GET, 1, GetInput.class, PlanOutput.class, ProcessContext.class, pb -> pb
            .description("A member reads the shop's plan and what its storefront used of it this period.")
            .permissions(Shopify.USE)
            .contextFactory(ShopProcesses::context)
            .outputMapper(ctx -> ctx.get(OUTPUT, PlanOutput.class))
            .step("Find the operator's shop and its plan", QueryEntities.<ProcessContext>of(
                QbsShops.defaultDataset(QbsShops.MEMBER),
                ctx -> ShopProcesses.eq("userId", ctx.request().actorId(), 1), MINE))
            .step("See when the plan was read", QueryEntities.<ProcessContext>of(
                QbsShops.defaultDataset(QbsBilling.PLAN),
                ctx -> ShopProcesses.eq("shopDomain", ShopProcesses.domainOfMine(ctx), 1), SEEN))
            .compute("Decide", (metadata, ctx) -> {
                ctx.put(DOMAIN, ShopProcesses.domainOfMine(ctx));
                EntityInstance row = ShopProcesses.first(ctx, SEEN);
                ctx.put(READING, ctx.get(INPUT, GetInput.class).refresh() || row == null
                    || QbsBilling.instant(row.get("checkedAt")).isBefore(ctx.opTime().minus(READ_AGAIN)));
            })
            .step("Lock the shop's tokens", HoldLock.<ProcessContext>exclusive(ctx -> {
                String domain = ctx.get(DOMAIN, String.class);
                return domain == null || !Boolean.TRUE.equals(ctx.get(READING)) ? null : Shopify.tokenLock(domain);
            }))
            // Read again under the lock: what is kept is written by the version read here.
            .step("Load the shop, its plan and its tokens", QueryEntities.all(
                QueryEntities.<ProcessContext>query(QbsShops.defaultDataset(QbsShops.SHOP),
                    ctx -> ShopProcesses.eq("shopDomain", ctx.get(DOMAIN, String.class), 1), SHOPS),
                planQuery(ctx -> ctx.get(DOMAIN, String.class)),
                QueryEntities.<ProcessContext>query(QbsShops.defaultDataset(QbsShops.TOKEN), ctx -> {
                    EntityInstance mine = ShopProcesses.first(ctx, MINE);
                    return ShopProcesses.eq("shopId", mine == null || !Boolean.TRUE.equals(ctx.get(READING)) ? null
                        : mine.get("shopId"), 1);
                }, TOKENS)))
            .compute("Check the operator", (metadata, ctx) -> {
                if (ShopProcesses.requireMemberOfActiveShop(ctx, ShopProcesses.first(ctx, MINE),
                    ShopProcesses.first(ctx, SHOPS))) {
                    ctx.put(READING, Boolean.TRUE.equals(ctx.get(READING)) && readable(ctx));
                }
            })
            .steps(PlanProcesses::readAndKeep)
            .compute("Find the period", (metadata, ctx) -> ctx.put(PERIOD,
                QbsBilling.period(ctx.get(NOW, EntityInstance.class), ctx.opTime())))
            .step("Load the period's use", QueryEntities.<ProcessContext>of(QbsShops.defaultDataset(QbsBilling.USAGE),
                ctx -> usage(ctx.hasViolations() ? null : ctx.get(DOMAIN, String.class), ctx.get(PERIOD,
                    QbsBilling.Period.class)), USAGES))
            .step("Load the plans' limits", LoadParams.<ProcessContext>optional(ProcessContext::opTime, LIMITS,
                QbsBilling.LIMIT_PARAMS.toArray(String[]::new)))
            .compute("Answer", (metadata, ctx) -> {
                if (!ctx.hasViolations()) {
                    ctx.put(OUTPUT, output(ctx, plans));
                }
            }));
    }

    static PlanOutput output(ProcessContext ctx, ShopifyPlans plans) {
        Instant now = ctx.opTime();
        EntityInstance row = ctx.get(NOW, EntityInstance.class);
        EntityInstance shop = ShopProcesses.first(ctx, SHOPS);
        QbsBilling.Period period = ctx.get(PERIOD, QbsBilling.Period.class);
        String plan = QbsBilling.plan(row, now);
        String own = row == null ? QbsBilling.FREE : row.get("plan");
        boolean kept = !plan.equals(own);
        String shopifyPlan = row == null ? null : row.get("subscriptionName");
        return new PlanOutput(plan, kept ? own : null, kept ? QbsBilling.instant(row.get("keptUntil")) : null,
            shopifyPlan, shopifyPlan != null && plans.planOf(shopifyPlan).isEmpty(),
            row != null && Boolean.TRUE.equals(row.get("test")),
            row == null ? null : QbsBilling.instant(row.get("trialEndsAt")), period.start(), period.end(),
            QbsBilling.completed(ShopProcesses.first(ctx, USAGES)),
            QbsBilling.limit(ctx.get(LIMITS, ParamValues.class), plan),
            row == null ? null : QbsBilling.instant(row.get("checkedAt")),
            Boolean.TRUE.equals(ctx.get(ConnectionProcesses.DROPPED)) ? QbsShops.RECONNECT : shop.get("connection"),
            plans.pricingUrl(shop.get("shopDomain")));
    }

    // ---- the storefront's use --------------------------------------------------------------------------------------

    /** The query of the plan's row of the shop {@code domain} gives (none: no statement), for a batch of queries. */
    static QueryEntities.Metadata<ProcessContext> planQuery(Function<ProcessContext, String> domain) {
        return QueryEntities.query(QbsShops.defaultDataset(QbsBilling.PLAN),
            ctx -> ShopProcesses.eq("shopDomain", domain.apply(ctx), 1), PLANS);
    }

    /**
     * Loads, for the shop {@code domain} gives (none: nothing is read), the period now of the plan {@link #planQuery}
     * read and its use in it: what {@link #overLimit} and {@link #count} need, with the plans' limits loaded by the
     * caller ({@link QbsBilling#LIMIT_PARAMS}).
     */
    static void loadUse(ProcessDefinitionBuilder<?, ?, ProcessContext> pb, Function<ProcessContext, String> domain) {
        pb.compute("Find the period", (metadata, ctx) -> ctx.put(PERIOD,
                QbsBilling.period(ShopProcesses.first(ctx, PLANS), ctx.opTime())))
            .step("Load the period's use", QueryEntities.<ProcessContext>of(QbsShops.defaultDataset(QbsBilling.USAGE),
                ctx -> usage(domain.apply(ctx), ctx.get(PERIOD, QbsBilling.Period.class)), USAGES));
    }

    static EntityQuery usage(String domain, QbsBilling.Period period) {
        if (domain == null) {
            return ShopProcesses.eq("shopDomain", null, 1);
        }
        return EntityQuery.builder().where(new QueryPredicate.And(List.of(
            new QueryPredicate.Eq("shopDomain", domain),
            new QueryPredicate.Eq("periodStart", period.start())))).limit(1).build();
    }

    /** The plan in force, as {@link #planQuery} read it. */
    static String plan(ProcessContext ctx) {
        return QbsBilling.plan(ShopProcesses.first(ctx, PLANS), ctx.opTime());
    }

    /**
     * Whether the period's completed attempts reached the plan's limit, as {@link #loadUse} read them, with the limits
     * loaded at {@code params}.
     */
    static boolean overLimit(ProcessContext ctx, String params) {
        return QbsBilling.completed(ShopProcesses.first(ctx, USAGES))
            >= QbsBilling.limit(ctx.get(params, ParamValues.class), plan(ctx));
    }

    /** Refuses a new attempt once the period's limit is reached. */
    static void refuseOverLimit(ProcessContext ctx, String params) {
        if (!ctx.hasViolations() && overLimit(ctx, params)) {
            ctx.reject(new Violation(null, ShopifyCodes.PLAN_LIMIT, "The shop's plan has no attempts left this"
                + " period", Map.of()));
        }
    }

    /** Counts one completed attempt in the period, as {@link #loadUse} read it (under the shop's usage lock). */
    static void count(ProcessContext ctx, String domain) {
        EntityInstance usage = ShopProcesses.first(ctx, USAGES);
        if (usage != null) {
            ctx.changes().update(QbsBilling.USAGE, usage.id(), usage.version(),
                Map.of("completed", BigDecimal.valueOf(QbsBilling.completed(usage) + 1)));
            return;
        }
        QbsBilling.Period period = ctx.get(PERIOD, QbsBilling.Period.class);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("shopDomain", domain);
        values.put("periodStart", period.start());
        values.put("periodEnd", period.end());
        values.put("completed", BigDecimal.ONE);
        ctx.changes().insert(QbsBilling.USAGE, values);
    }
}
