package com.jabiz.quizbuks.shopify;

import com.jabiz.event.DomainEvent;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.inbound.InboundEvents;
import com.jabiz.runtime.inbound.LoadInboundEvent;
import com.jabiz.runtime.inbound.ReceivedEvent;
import com.jabiz.quizbuks.channel.Channel;
import com.jabiz.runtime.process.steps.CallProcess;
import com.jabiz.runtime.process.steps.HoldLock;
import com.jabiz.runtime.process.steps.QueryEntities;
import jakarta.validation.constraints.NotBlank;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What QuizBuks for Shopify does with Shopify's webhooks (docs/quizbuks/shopify/S1-install.md section 5), each the
 * consumer of one topic of the inbound provider {@value Shopify#WEBHOOKS}, run by the system only ({@code internal()}).
 * The shop is the one the signed body names; the unsigned header {@value Shopify#SHOP_HEADER} must name the same, or
 * nothing is done (a privacy request is recorded as {@code MISMATCH}). (A change of a subscription, whose body names
 * no shop's address, is {@link PlanProcesses#SUBSCRIPTIONS_UPDATE}.)
 *
 * <ul>
 *   <li>{@value #UNINSTALLED}: the shop is marked uninstalled and its access tokens are dropped; its members'
 *       sessions end at their next refresh (the sign-in guard); its live campaign is paused (S2); it is back on
 *       the free plan (S3a: Shopify cancels the subscription). Its data is kept.</li>
 *   <li>{@value #SHOP_REDACT}: Shopify asks to erase the shop's data (48 hours after an uninstall): the shop, its
 *       members and tokens are deleted, and (S2) its campaigns, signed-in customers, batches and codes, and (S3a) its
 *       plan and use; a campaign still
 *       running ends on the engine. A shop that was installed again meanwhile is kept. The members' platform
 *       accounts stay as the platform keeps them (its user records are append-only, and linking and unlinking are the
 *       platform's): a staff member who opens the app after a new install signs in as before and joins a new shop.</li>
 *   <li>{@value #DATA_REQUEST} and {@value #CUSTOMERS_REDACT} (S2): a customer who answered the storefront's quiz
 *       while signed in has a row; their attempts and codes are reported, or the row is deleted and the codes
 *       unlinked, and what earlier requests recorded of them is deleted (the erasure's own record names nobody); a
 *       customer without one is recorded as {@code NO_DATA}.</li>
 * </ul>
 */
public final class WebhookProcesses {

    private static final Logger log = LoggerFactory.getLogger(WebhookProcesses.class);

    private WebhookProcesses() {}

    public static final String UNINSTALLED = "QBS_SHOPIFY_APP_UNINSTALLED";
    public static final String SHOP_REDACT = "QBS_SHOPIFY_SHOP_REDACT";
    public static final String DATA_REQUEST = "QBS_SHOPIFY_CUSTOMERS_DATA_REQUEST";
    public static final String CUSTOMERS_REDACT = "QBS_SHOPIFY_CUSTOMERS_REDACT";

    /** The webhook to consume. */
    public record InboundRef(@NotBlank String inboundEventId) {}

    /** The shop the webhook is about, and what was done. */
    public record Outcome(String shopDomain, String outcome) {}

    static final String INPUT = "input";
    static final String EVENT = "event";
    static final String DOMAIN = "domain";
    static final String SHOPS = ShopProcesses.SHOPS;
    static final String MEMBERS = ShopProcesses.MEMBERS;
    static final String TOKENS = ConnectionProcesses.TOKENS;
    static final String OUTPUT = "output";
    static final String CAMPAIGNS = "campaigns";
    static final String TAKERS = "takers";
    static final String BATCHES = "batches";
    static final String CODES = "codes";
    static final String ATTEMPTS = "attempts";
    static final String CUSTOMER = "customer";
    static final String EARLIER_REQUESTS = "earlierRequests";
    static final String REPORT = "report";
    static final String PLANS = PlanProcesses.PLANS;
    static final String USAGES = PlanProcesses.USAGES;
    /** S3a: the most periods of a shop that {@code shop/redact} deletes (a row a month: decades). */
    static final int MAX_USAGES = 1_000;
    static final String PAUSING = "pausing";
    static final String ENDING = "ending";

    static InboundRef input(DomainEvent event) {
        return new InboundRef(InboundEvents.ref(event).inboundEventId());
    }

    static ProcessDefinition<InboundRef, Outcome, ProcessContext> uninstalled() {
        return ProcessDefinition.define(UNINSTALLED, 1, InboundRef.class, Outcome.class, ProcessContext.class, pb -> pb
            .description("Shopify reports that a shop uninstalled the app: the shop is marked uninstalled and its"
                + " access tokens are dropped; its data is kept.")
            .permissions(Shopify.SYSTEM)
            .internal()
            .contextFactory(ShopProcesses::context)
            .outputMapper(ctx -> ctx.get(OUTPUT, Outcome.class))
            .step("Load the event", LoadInboundEvent.of(Shopify.WEBHOOKS, QbsShops.UNINSTALL,
                ctx -> ctx.get(INPUT, InboundRef.class).inboundEventId(), EVENT))
            // The uninstalled shop's own object: its Shopify address is myshopify_domain.
            .compute("Read the shop", (metadata, ctx) -> ctx.put(DOMAIN, domain(ctx, "myshopify_domain")))
            .steps(WebhookProcesses::lockAndLoad)
            .step("Load the running campaign and the batches", QueryEntities.all(
                QueryEntities.<ProcessContext>query(QbsShops.defaultDataset(QbsStorefront.CAMPAIGN),
                    ctx -> CampaignProcesses.running(ctx.get(DOMAIN, String.class)), CAMPAIGNS),
                QueryEntities.<ProcessContext>query(QbsShops.defaultDataset(QbsStorefront.BATCH),
                    ctx -> com.jabiz.query.EntityQuery.builder().where(new com.jabiz.query.QueryPredicate.And(List.of(
                        CampaignProcesses.domainIs(ctx.get(DOMAIN, String.class)),
                        new com.jabiz.query.QueryPredicate.In("status", List.of(QbsStorefront.CREATING,
                            QbsStorefront.READY))))).limit(DiscountPool.MAX_BATCHES).build(), BATCHES),
                QueryEntities.<ProcessContext>query(QbsShops.defaultDataset(QbsBilling.PLAN),
                    ctx -> ShopProcesses.eq("shopDomain", ctx.get(DOMAIN, String.class), 1), PLANS)))
            .compute("Mark the shop uninstalled", (metadata, ctx) -> uninstall(ctx))
            // S2: its storefront takes no more attempts (its codes stop working anyway: Shopify deletes the app's
            // discounts); a member resumes the campaign after installing it again.
            .step("Pause the publication", CallProcess.<ProcessContext>when(ctx -> ctx.contains(PAUSING),
                Channel.MOVE, 1, ctx -> ctx.get(PAUSING), null)));
    }

    static void uninstall(ProcessContext ctx) {
        String domain = ctx.get(DOMAIN, String.class);
        EntityInstance shop = ShopProcesses.first(ctx, SHOPS);
        if (domain == null || shop == null || QbsShops.UNINSTALLED.equals(shop.get("status"))) {
            ctx.put(OUTPUT, new Outcome(domain, domain == null ? QbsShops.MISMATCH
                : shop == null ? QbsShops.NOT_FOUND : QbsShops.UNINSTALLED));
            return;
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("status", QbsShops.UNINSTALLED);
        values.put("uninstalledAt", ctx.opTime());
        values.put("connection", QbsShops.NONE);
        ctx.changes().update(QbsShops.SHOP, shop.id(), shop.version(), values);
        for (EntityInstance member : ShopProcesses.rows(ctx, MEMBERS)) {
            ctx.changes().update(QbsShops.MEMBER, member.id(), member.version(),
                Map.of("shopStatus", QbsShops.UNINSTALLED));
        }
        EntityInstance tokens = ShopProcesses.first(ctx, TOKENS);
        if (tokens != null) {
            ctx.changes().delete(QbsShops.TOKEN, tokens.id(), tokens.version());
        }
        // S2: Shopify deletes the app's discounts with the uninstall, so no batch's code works any more: none is handed
        // out (after a new install the refill makes new ones).
        for (EntityInstance batch : ShopProcesses.rows(ctx, BATCHES)) {
            ctx.changes().update(QbsStorefront.BATCH, batch.id(), batch.version(),
                Map.of("status", QbsStorefront.RETIRED));
        }
        // S3a: Shopify cancels the app's subscription with the uninstall: the shop is on the free plan until a new
        // install has its plan read again (its use is kept).
        EntityInstance plan = ShopProcesses.first(ctx, PLANS);
        if (plan != null) {
            ctx.changes().delete(QbsBilling.PLAN, plan.id(), plan.version());
        }
        EntityInstance campaign = ShopProcesses.first(ctx, CAMPAIGNS);
        if (campaign != null && QbsStorefront.LIVE.equals(campaign.get("status"))) {
            Map<String, Object> paused = new LinkedHashMap<>();
            paused.put("status", QbsStorefront.PAUSED);
            paused.put("editedAt", ctx.opTime());
            paused.put("revision", BigDecimal.valueOf(CampaignProcesses.number(campaign.get("revision")) + 1));
            ctx.changes().update(QbsStorefront.CAMPAIGN, campaign.id(), campaign.version(), paused);
            ctx.put(PAUSING, new Channel.MoveInput(CampaignProcesses.publisher(shop),
                UUID.fromString(campaign.get("publicationId")), QbsStorefront.PAUSED));
        }
        ctx.put(OUTPUT, new Outcome(domain, QbsShops.UNINSTALLED));
    }

    static ProcessDefinition<InboundRef, Outcome, ProcessContext> shopRedact() {
        return ProcessDefinition.define(SHOP_REDACT, 1, InboundRef.class, Outcome.class, ProcessContext.class, pb -> pb
            .description("Shopify asks to erase an uninstalled shop's data: the shop, its members and tokens are"
                + " deleted; the members' platform accounts stay as the platform keeps them.")
            .permissions(Shopify.SYSTEM)
            .internal()
            .contextFactory(ShopProcesses::context)
            .outputMapper(ctx -> ctx.get(OUTPUT, Outcome.class))
            .step("Load the event", LoadInboundEvent.of(Shopify.WEBHOOKS, QbsShops.SHOP_REDACT,
                ctx -> ctx.get(INPUT, InboundRef.class).inboundEventId(), EVENT))
            .compute("Read the shop", (metadata, ctx) -> ctx.put(DOMAIN, domain(ctx, "shop_domain")))
            .steps(WebhookProcesses::lockAndLoad)
            .step("Load the storefront's rows", QueryEntities.all(
                QueryEntities.<ProcessContext>query(QbsShops.defaultDataset(QbsStorefront.CAMPAIGN),
                    ctx -> ofShop(ctx, CampaignProcesses.MAX_CAMPAIGNS), CAMPAIGNS),
                QueryEntities.<ProcessContext>query(QbsShops.defaultDataset(QbsStorefront.TAKER),
                    ctx -> ofShop(ctx, ShopifyConfig.MAX_TAKERS), TAKERS),
                QueryEntities.<ProcessContext>query(QbsShops.defaultDataset(QbsStorefront.BATCH),
                    ctx -> ofShop(ctx, DiscountPool.MAX_BATCHES), BATCHES),
                QueryEntities.<ProcessContext>query(QbsShops.defaultDataset(QbsStorefront.CODE),
                    ctx -> ofShop(ctx, DiscountPool.MAX_BATCHES * DiscountPool.BATCH_CODES), CODES),
                QueryEntities.<ProcessContext>query(QbsShops.defaultDataset(QbsBilling.PLAN),
                    ctx -> ofShop(ctx, 1), PLANS),
                QueryEntities.<ProcessContext>query(QbsShops.defaultDataset(QbsBilling.USAGE),
                    ctx -> ofShop(ctx, MAX_USAGES), USAGES)))
            .compute("Erase the shop's data", (metadata, ctx) -> redact(ctx))
            // S2: the engine's publication of a campaign still running (paused at the uninstall) ends.
            .step("End the publication", CallProcess.<ProcessContext>when(ctx -> ctx.contains(ENDING),
                Channel.MOVE, 1, ctx -> ctx.get(ENDING), null)));
    }

    /** The rows of the shop being erased (none while it is not being erased: no statement). */
    static com.jabiz.query.EntityQuery ofShop(ProcessContext ctx, int limit) {
        EntityInstance shop = ShopProcesses.first(ctx, SHOPS);
        boolean erasing = shop != null && QbsShops.UNINSTALLED.equals(shop.get("status"));
        return ShopProcesses.eq("shopDomain", erasing ? ctx.get(DOMAIN, String.class) : null, limit);
    }

    static void redact(ProcessContext ctx) {
        String domain = ctx.get(DOMAIN, String.class);
        EntityInstance shop = ShopProcesses.first(ctx, SHOPS);
        String outcome;
        if (domain == null) {
            outcome = QbsShops.MISMATCH;
        } else if (shop == null) {
            outcome = QbsShops.NOT_FOUND;
        } else if (QbsShops.ACTIVE.equals(shop.get("status"))) {
            // Installed again before the request came: the shop is in use, nothing is erased.
            outcome = QbsShops.KEPT_REINSTALLED;
        } else {
            EntityInstance tokens = ShopProcesses.first(ctx, TOKENS);
            if (tokens != null) {
                ctx.changes().delete(QbsShops.TOKEN, tokens.id(), tokens.version());
            }
            for (EntityInstance member : ShopProcesses.rows(ctx, MEMBERS)) {
                ctx.changes().delete(QbsShops.MEMBER, member.id(), member.version());
            }
            // S2: the storefront's rows. The engine keeps its attempts (by keys that name nobody once the takers are
            // gone) and the shop's publications, quizzes and versions (append-only; docs/quizbuks/shopify/
            // S2-storefront.md, Q-S2-2).
            for (String key : List.of(CODES, BATCHES, TAKERS, PLANS, USAGES, CAMPAIGNS)) {
                String entity = switch (key) {
                    case CODES -> QbsStorefront.CODE;
                    case BATCHES -> QbsStorefront.BATCH;
                    case TAKERS -> QbsStorefront.TAKER;
                    case PLANS -> QbsBilling.PLAN;
                    case USAGES -> QbsBilling.USAGE;
                    default -> QbsStorefront.CAMPAIGN;
                };
                for (EntityInstance row : ShopProcesses.rows(ctx, key)) {
                    ctx.changes().delete(entity, row.id(), row.version());
                    if (QbsStorefront.CAMPAIGN.equals(entity) && row.get("publicationId") != null
                        && List.of(QbsStorefront.LIVE, QbsStorefront.PAUSED).contains(row.get("status"))) {
                        ctx.put(ENDING, new Channel.MoveInput(CampaignProcesses.publisher(shop),
                            UUID.fromString(row.get("publicationId")), QbsStorefront.ENDED));
                    }
                }
            }
            ctx.changes().delete(QbsShops.SHOP, shop.id(), shop.version());
            outcome = QbsShops.DELETED;
        }
        record(ctx, QbsShops.SHOP_REDACT, domain, outcome);
    }

    static ProcessDefinition<InboundRef, Outcome, ProcessContext> dataRequest() {
        return customers(DATA_REQUEST, QbsShops.DATA_REQUEST,
            "Shopify asks for the data of a shop's customer: their attempts and codes are reported for the shop's"
                + " staff to pass on.");
    }

    static ProcessDefinition<InboundRef, Outcome, ProcessContext> customersRedact() {
        return customers(CUSTOMERS_REDACT, QbsShops.CUSTOMERS_REDACT,
            "Shopify asks to erase the data of a shop's customer: the row that links them to their attempts and codes"
                + " is deleted.");
    }

    /**
     * Both of a customer's requests (S2): the customer's row of the shop ({@link QbsStorefront#TAKER}), if they ever
     * answered while signed in. A data request reports their attempts (read from the engine's records by their key)
     * and codes; an erasure deletes the row and unlinks the codes, after which nothing names them: the engine's
     * attempts keep a key nobody can trace back. A visitor who was not signed in is unknown to Shopify's requests.
     */
    private static ProcessDefinition<InboundRef, Outcome, ProcessContext> customers(String name, String topic,
        String description) {
        boolean erase = QbsShops.CUSTOMERS_REDACT.equals(topic);
        return ProcessDefinition.define(name, 1, InboundRef.class, Outcome.class, ProcessContext.class, pb -> pb
            .description(description)
            .permissions(Shopify.SYSTEM)
            .internal()
            .contextFactory(ShopProcesses::context)
            .outputMapper(ctx -> ctx.get(OUTPUT, Outcome.class))
            .step("Load the event", LoadInboundEvent.of(Shopify.WEBHOOKS, topic,
                ctx -> ctx.get(INPUT, InboundRef.class).inboundEventId(), EVENT))
            .compute("Read the shop and the customer", (metadata, ctx) -> {
                ctx.put(DOMAIN, domain(ctx, "shop_domain"));
                Object customer = ctx.get(EVENT, ReceivedEvent.class).at("customer.id");
                // Shopify sends the id as a JSON number; as the app proxy names a customer: its digits.
                String id = customer instanceof Number number ? new BigDecimal(number.toString()).toPlainString()
                    : customer instanceof String text ? text : null;
                if (id != null && !com.jabiz.security.AppProxyCaller.CUSTOMER.matcher(id).matches()) {
                    id = null;
                }
                ctx.put(CUSTOMER, id);
            })
            .step("Lock the shop's codes", HoldLock.<ProcessContext>exclusive(ctx -> {
                String domain = ctx.get(DOMAIN, String.class);
                return domain == null ? null : Shopify.codesLock(domain);
            }))
            .step("Load the customer", QueryEntities.<ProcessContext>of(QbsShops.defaultDataset(QbsStorefront.TAKER),
                ctx -> {
                    String domain = ctx.get(DOMAIN, String.class);
                    String customer = ctx.get(CUSTOMER, String.class);
                    return com.jabiz.query.EntityQuery.builder().where(new com.jabiz.query.QueryPredicate.And(List.of(
                        new com.jabiz.query.QueryPredicate.Eq("shopDomain", domain == null ? "-" : domain),
                        customer == null ? new com.jabiz.query.QueryPredicate.In("customerId", List.of())
                            : new com.jabiz.query.QueryPredicate.Eq("customerId", customer)))).limit(1).build();
                }, TAKERS))
            .step("Load their codes, attempts and earlier requests", QueryEntities.all(
                QueryEntities.<ProcessContext>query(QbsShops.defaultDataset(QbsStorefront.CODE),
                    ctx -> ShopProcesses.eq("taker", takerKey(ctx), MAX_CODES_OF_CUSTOMER), CODES),
                // An erasure removes what the earlier requests recorded of the customer (a data request's report).
                QueryEntities.<ProcessContext>query(QbsShops.defaultDataset(QbsShops.COMPLIANCE), ctx -> {
                    String domain = ctx.get(DOMAIN, String.class);
                    String customer = erase ? ctx.get(CUSTOMER, String.class) : null;
                    return com.jabiz.query.EntityQuery.builder().where(new com.jabiz.query.QueryPredicate.And(List.of(
                        new com.jabiz.query.QueryPredicate.Eq("shopDomain", domain == null ? "-" : domain),
                        customer == null ? new com.jabiz.query.QueryPredicate.In("customerId", List.of())
                            : new com.jabiz.query.QueryPredicate.Eq("customerId", customer))))
                        .limit(ShopProcesses.MAX_MEMBERS).build();
                }, EARLIER_REQUESTS),
                QueryEntities.<ProcessContext>query(ENGINE_ATTEMPTS,
                    ctx -> ShopProcesses.eq("userId", erase ? null : takerKey(ctx), MAX_CODES_OF_CUSTOMER),
                    ATTEMPTS)))
            .compute("Act on the request", (metadata, ctx) -> {
                String domain = ctx.get(DOMAIN, String.class);
                if (domain == null) {
                    record(ctx, topic, null, QbsShops.MISMATCH);
                    return;
                }
                if (erase) {
                    // What earlier requests recorded of the customer goes, and this one records nobody.
                    for (EntityInstance earlier : ShopProcesses.rows(ctx, EARLIER_REQUESTS)) {
                        ctx.changes().delete(QbsShops.COMPLIANCE, earlier.id(), earlier.version());
                    }
                    ctx.put(CUSTOMER, null);
                }
                EntityInstance taker = ShopProcesses.first(ctx, TAKERS);
                if (taker == null) {
                    // Nothing held of them: nor is their id, in the record either.
                    ctx.put(CUSTOMER, null);
                    record(ctx, topic, domain, QbsShops.NO_DATA);
                    return;
                }
                if (erase) {
                    for (EntityInstance code : ShopProcesses.rows(ctx, CODES)) {
                        Map<String, Object> unlinked = new LinkedHashMap<>();
                        unlinked.put("taker", null);
                        ctx.changes().update(QbsStorefront.CODE, code.id(), code.version(), unlinked);
                    }
                    ctx.changes().delete(QbsStorefront.TAKER, taker.id(), taker.version());
                    record(ctx, topic, domain, QbsShops.REDACTED);
                    return;
                }
                ctx.put(REPORT, report(ctx));
                record(ctx, topic, domain, QbsShops.REPORTED);
            }));
    }

    /** The most codes or attempts of one customer a request reads: one attempt per campaign. */
    static final int MAX_CODES_OF_CUSTOMER = 1_000;

    /** The engine's attempts, read by the customer's key only (the engine names its default datasets so). */
    static final String ENGINE_ATTEMPTS = "urn:jabiz:dataset:default:QbAttempt";

    static String takerKey(ProcessContext ctx) {
        EntityInstance taker = ShopProcesses.first(ctx, TAKERS);
        return taker == null ? null : QbsStorefront.customerTaker(taker.id());
    }

    /** What is held of the customer, as JSON: their attempts (scores, not answers) and codes (hints, not codes). */
    static String report(ProcessContext ctx) {
        List<Map<String, Object>> attempts = new ArrayList<>();
        for (EntityInstance attempt : ShopProcesses.rows(ctx, ATTEMPTS)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("publicationId", String.valueOf((Object) attempt.get("publicationId")));
            row.put("startedAt", String.valueOf((Object) attempt.get("startedAt")));
            row.put("status", attempt.get("status"));
            row.put("score", attempt.get("score") == null ? null : attempt.get("score").toString());
            row.put("passed", attempt.get("passed"));
            attempts.add(row);
        }
        List<Map<String, Object>> codes = new ArrayList<>();
        for (EntityInstance code : ShopProcesses.rows(ctx, CODES)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("hint", code.get("hint"));
            row.put("issuedAt", String.valueOf((Object) code.get("issuedAt")));
            codes.add(row);
        }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("customerId", ctx.get(CUSTOMER));
        report.put("attempts", attempts);
        report.put("discountCodes", codes);
        return REPORT_JSON.writeValueAsString(report);
    }

    private static final tools.jackson.databind.json.JsonMapper REPORT_JSON =
        tools.jackson.databind.json.JsonMapper.builder().build();

    /** The shop's lock and the token lock, then the shop, its members and its tokens. */
    static void lockAndLoad(com.jabiz.process.ProcessDefinitionBuilder<InboundRef, Outcome, ProcessContext> pb) {
        pb.step("Lock the shop", HoldLock.<ProcessContext>exclusive(ctx -> {
                String domain = ctx.get(DOMAIN, String.class);
                return domain == null ? null : Shopify.shopLock(domain);
            }))
            .step("Lock the shop's tokens", HoldLock.<ProcessContext>exclusive(ctx -> {
                String domain = ctx.get(DOMAIN, String.class);
                return domain == null ? null : Shopify.tokenLock(domain);
            }))
            .step("Load the shop", QueryEntities.<ProcessContext>of(QbsShops.defaultDataset(QbsShops.SHOP),
                ctx -> ShopProcesses.eq("shopDomain", ctx.get(DOMAIN, String.class), 1), SHOPS))
            .step("Load the members and the tokens", QueryEntities.all(
                QueryEntities.<ProcessContext>query(QbsShops.defaultDataset(QbsShops.MEMBER),
                    ctx -> ShopProcesses.membersOf(ShopProcesses.first(ctx, SHOPS)), MEMBERS),
                QueryEntities.<ProcessContext>query(QbsShops.defaultDataset(QbsShops.TOKEN),
                    ConnectionProcesses::tokensOfShop, TOKENS)));
    }

    /**
     * The shop the signed body names in {@code field}, when the unsigned header names the same and it is a shop's
     * address; otherwise null (and the event is not acted on).
     */
    static String domain(ProcessContext ctx, String field) {
        ReceivedEvent event = ctx.get(EVENT, ReceivedEvent.class);
        Object named = event.at(field);
        String body = named instanceof String text ? Shopify.lower(text) : null;
        // The platform's metadata map ignores the case of header names.
        String header = Shopify.lower(event.metadata() == null ? null : event.metadata().get(Shopify.SHOP_HEADER));
        if (body == null || !Shopify.isShopDomain(body) || !body.equals(header)) {
            log.warn("Shopify webhook {} ({}) names shop {} in its body and {} in its header: not acted on",
                event.inboundEventId(), event.eventType(), body, header);
            return null;
        }
        return body;
    }

    static void record(ProcessContext ctx, String topic, String domain, String outcome) {
        ReceivedEvent event = ctx.get(EVENT, ReceivedEvent.class);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("inboundEventId", event.inboundEventId());
        values.put("topic", topic);
        values.put("shopDomain", domain);
        values.put("outcome", outcome);
        values.put("receivedAt", event.receivedTime());
        values.put("customerId", ctx.get(CUSTOMER));
        values.put("report", ctx.get(REPORT));
        values.put("handledAt", ctx.opTime());
        ctx.changes().insert(QbsShops.COMPLIANCE, values);
        ctx.put(OUTPUT, new Outcome(domain, outcome));
    }
}
