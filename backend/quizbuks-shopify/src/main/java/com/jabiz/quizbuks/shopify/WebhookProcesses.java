package com.jabiz.quizbuks.shopify;

import com.jabiz.event.DomainEvent;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.inbound.InboundEvents;
import com.jabiz.runtime.inbound.LoadInboundEvent;
import com.jabiz.runtime.inbound.ReceivedEvent;
import com.jabiz.runtime.process.steps.HoldLock;
import com.jabiz.runtime.process.steps.QueryEntities;
import com.jabiz.runtime.security.SecurityEntities;
import jakarta.validation.constraints.NotBlank;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What QuizBuks for Shopify does with Shopify's webhooks (docs/quizbuks/shopify/S1-install.md section 5), each the
 * consumer of one topic of the inbound provider {@value Shopify#WEBHOOKS}, run by the system only ({@code internal()}).
 * The shop is the one the signed body names; the unsigned header {@value Shopify#SHOP_HEADER} must name the same, or
 * nothing is done (a privacy request is recorded as {@code MISMATCH}).
 *
 * <ul>
 *   <li>{@value #UNINSTALLED}: the shop is marked uninstalled and its access tokens are dropped; its members'
 *       sessions end at their next refresh (the sign-in guard). Its data is kept.</li>
 *   <li>{@value #SHOP_REDACT}: Shopify asks to erase the shop's data (48 hours after an uninstall): the shop, its
 *       members and tokens are deleted, the members' platform accounts are disabled and their Shopify identities
 *       unlinked. A shop that was installed again meanwhile is kept.</li>
 *   <li>{@value #DATA_REQUEST} and {@value #CUSTOMERS_REDACT}: the module keeps no data of a shop's customers, so the
 *       request is recorded as {@code NO_DATA}.</li>
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
    static final String USERS = "users";
    static final String LINKS = "links";
    static final String OUTPUT = "output";

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
            .compute("Mark the shop uninstalled", (metadata, ctx) -> uninstall(ctx)));
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
        ctx.put(OUTPUT, new Outcome(domain, QbsShops.UNINSTALLED));
    }

    static ProcessDefinition<InboundRef, Outcome, ProcessContext> shopRedact() {
        return ProcessDefinition.define(SHOP_REDACT, 1, InboundRef.class, Outcome.class, ProcessContext.class, pb -> pb
            .description("Shopify asks to erase an uninstalled shop's data: the shop, its members and tokens are"
                + " deleted, and the members' accounts disabled and unlinked.")
            .permissions(Shopify.SYSTEM)
            .internal()
            .contextFactory(ShopProcesses::context)
            .outputMapper(ctx -> ctx.get(OUTPUT, Outcome.class))
            .step("Load the event", LoadInboundEvent.of(Shopify.WEBHOOKS, QbsShops.SHOP_REDACT,
                ctx -> ctx.get(INPUT, InboundRef.class).inboundEventId(), EVENT))
            .compute("Read the shop", (metadata, ctx) -> ctx.put(DOMAIN, domain(ctx, "shop_domain")))
            .steps(WebhookProcesses::lockAndLoad)
            .step("Load the members' accounts and links", QueryEntities.all(
                QueryEntities.<ProcessContext>query(SecurityEntities.USER_DATASET,
                    ctx -> users(ctx, "userId"), USERS),
                QueryEntities.<ProcessContext>query(SecurityEntities.USER_IDENTITY_DATASET,
                    ctx -> users(ctx, "userId"), LINKS)))
            .compute("Erase the shop's data", (metadata, ctx) -> redact(ctx)));
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
            ctx.changes().delete(QbsShops.SHOP, shop.id(), shop.version());
            for (EntityInstance user : ShopProcesses.rows(ctx, USERS)) {
                if (Boolean.TRUE.equals(user.get("enabled"))) {
                    ctx.changes().update(SecurityEntities.USER, user.id(), user.version(), Map.of("enabled", false));
                }
            }
            // Only the links of this provider: the identity of the person within this shop.
            for (EntityInstance link : ShopProcesses.rows(ctx, LINKS)) {
                if (("ext:" + Shopify.PROVIDER).equals(link.get("provider"))) {
                    ctx.changes().delete(SecurityEntities.USER_IDENTITY, link.id(), link.version());
                }
            }
            outcome = QbsShops.DELETED;
        }
        record(ctx, QbsShops.SHOP_REDACT, domain, outcome);
    }

    static ProcessDefinition<InboundRef, Outcome, ProcessContext> dataRequest() {
        return customers(DATA_REQUEST, QbsShops.DATA_REQUEST,
            "Shopify asks for the data of a shop's customer: none is kept, which is recorded.");
    }

    static ProcessDefinition<InboundRef, Outcome, ProcessContext> customersRedact() {
        return customers(CUSTOMERS_REDACT, QbsShops.CUSTOMERS_REDACT,
            "Shopify asks to erase the data of a shop's customer: none is kept, which is recorded.");
    }

    private static ProcessDefinition<InboundRef, Outcome, ProcessContext> customers(String name, String topic,
        String description) {
        return ProcessDefinition.define(name, 1, InboundRef.class, Outcome.class, ProcessContext.class, pb -> pb
            .description(description)
            .permissions(Shopify.SYSTEM)
            .internal()
            .contextFactory(ShopProcesses::context)
            .outputMapper(ctx -> ctx.get(OUTPUT, Outcome.class))
            .step("Load the event", LoadInboundEvent.of(Shopify.WEBHOOKS, topic,
                ctx -> ctx.get(INPUT, InboundRef.class).inboundEventId(), EVENT))
            .compute("Record the request", (metadata, ctx) -> {
                String domain = domain(ctx, "shop_domain");
                record(ctx, topic, domain, domain == null ? QbsShops.MISMATCH : QbsShops.NO_DATA);
            }));
    }

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
        String header = Shopify.lower(header(event.metadata(), Shopify.SHOP_HEADER));
        if (body == null || !Shopify.isShopDomain(body) || !body.equals(header)) {
            log.warn("Shopify webhook {} ({}) names shop {} in its body and {} in its header: not acted on",
                event.inboundEventId(), event.eventType(), body, header);
            return null;
        }
        return body;
    }

    static String header(Map<String, String> metadata, String name) {
        if (metadata == null) {
            return null;
        }
        return metadata.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(name)).map(Map.Entry::getValue)
            .findFirst().orElse(null);
    }

    static EntityQuery users(ProcessContext ctx, String field) {
        List<Object> ids = ShopProcesses.rows(ctx, MEMBERS).stream().map(m -> (Object) m.get("userId")).toList();
        return EntityQuery.builder().where(new QueryPredicate.In(field, ids)).limit(ShopProcesses.MAX_MEMBERS * 4)
            .build();
    }

    static void record(ProcessContext ctx, String topic, String domain, String outcome) {
        ReceivedEvent event = ctx.get(EVENT, ReceivedEvent.class);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("inboundEventId", event.inboundEventId());
        values.put("topic", topic);
        values.put("shopDomain", domain);
        values.put("outcome", outcome);
        values.put("receivedAt", event.receivedTime());
        values.put("handledAt", ctx.opTime());
        ctx.changes().insert(QbsShops.COMPLIANCE, values);
        ctx.put(OUTPUT, new Outcome(domain, outcome));
    }
}
