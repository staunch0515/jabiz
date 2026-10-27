package com.jabiz.quizbuks.shopify;

import com.jabiz.entity.Violation;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.quizbuks.channel.Channel;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.EntityNotFoundException;
import com.jabiz.runtime.process.steps.CallProcess;
import com.jabiz.runtime.process.steps.HoldLock;
import com.jabiz.runtime.process.steps.QueryEntities;
import com.jabiz.runtime.process.steps.RunTemplate;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A shop's quiz campaigns, as its staff manage them in the embedded pages (docs/quizbuks/shopify/S2-storefront.md
 * section 2). Every process is a member's of an installed shop, under the shop's lock.
 * <ul>
 *   <li>{@value #SAVE}: creates or edits a draft: the video to watch, the questions, the pass score, the time and the
 *       discount;</li>
 *   <li>{@value #PUBLISH}: puts a draft on the storefront: the engine's publication of the shop (live at once); only
 *       one campaign runs per shop, and only a connected shop publishes (its codes come from Shopify);</li>
 *   <li>{@value #MOVE}: pauses, resumes or ends the running campaign (a draft is ended without the engine);</li>
 *   <li>{@value #LIST}: the shop's campaigns, the latest first, with the pool's figures.</li>
 * </ul>
 * A published campaign does not change: to change it, end it and publish another (its codes keep their discount).
 */
public final class CampaignProcesses {

    private CampaignProcesses() {}

    public static final String SAVE = "QBS_CAMPAIGN_SAVE";
    public static final String PUBLISH = "QBS_CAMPAIGN_PUBLISH";
    public static final String MOVE = "QBS_CAMPAIGN_MOVE";
    public static final String LIST = "QBS_CAMPAIGN_LIST";

    /** The most campaigns the list shows. */
    public static final int LIST_LIMIT = 50;
    /** The figures of the pool: codes issued today (UTC) and over the last seven days. */
    public static final String STATS_TEMPLATE = "qbs.system.pool-stats";

    /**
     * @param campaignId   the draft to edit; none to create one
     * @param baseRevision the draft's revision as the editor read it (409 when it changed since); none to create
     * @param percent      the discount off the whole order, 1 to 90 %
     */
    public record SaveInput(@Size(max = QbsShops.ID_LENGTH) String campaignId, Long baseRevision,
        @NotBlank @Size(max = QbsStorefront.NAME_LENGTH) String name,
        @Size(max = QbsStorefront.VIDEO_LENGTH) String video,
        @NotNull @Min(Channel.MIN_TIME_LIMIT_SEC) @Max(Channel.MAX_TIME_LIMIT_SEC) Integer timeLimitSec,
        @NotNull @Min(1) @Max(Channel.MAX_QUESTIONS) Integer passScore,
        @NotNull @Min(1) @Max(90) Integer percent,
        @NotNull @Size(min = 1, max = Channel.MAX_QUESTIONS) List<Channel.@Valid @NotNull QuestionInput> questions) {}

    public record CampaignRef(@NotBlank @Size(max = QbsShops.ID_LENGTH) String campaignId) {}

    public record MoveInput(@NotBlank @Size(max = QbsShops.ID_LENGTH) String campaignId,
        @NotNull @Pattern(regexp = "PAUSED|LIVE|ENDED") String to) {}

    public record ListInput() {}

    /** A campaign as the staff see it. */
    public record Campaign(String campaignId, String status, String name, String video, int timeLimitSec,
        int passScore, int percent, List<Channel.QuestionInput> questions, String publicationId, long revision,
        Instant createdAt, Instant editedAt, Instant publishedAt, Instant endedAt) {}

    /**
     * @param available   codes the storefront can hand out (at least {@link Pool#MIN_DAYS_LEFT} days left)
     * @param issuedToday codes handed out today (UTC), against {@code dailyCap}
     */
    public record Pool(long available, long issuedToday, long issuedWeek) {
        public static final Duration MIN_DAYS_LEFT = Duration.ofDays(7);
    }

    public record ListOutput(List<Campaign> campaigns, Pool pool) {}

    static final String INPUT = ShopProcesses.INPUT;
    static final String MINE = ShopProcesses.MINE;
    static final String SHOPS = ShopProcesses.SHOPS;
    static final String OUTPUT = ShopProcesses.OUTPUT;
    static final String CAMPAIGN = "campaign";
    static final String RUNNING = "running";
    static final String CAMPAIGNS = "campaigns";
    static final String STATS = "stats";
    static final String PUBLISHED = "published";
    static final String MOVED = "moved";

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<List<Channel.QuestionInput>> QUESTIONS = new TypeReference<>() {};

    // ---- save ------------------------------------------------------------------------------------------------------

    static ProcessDefinition<SaveInput, Campaign, ProcessContext> save() {
        return ProcessDefinition.define(SAVE, 1, SaveInput.class, Campaign.class, ProcessContext.class, pb -> pb
            .description("A member creates or edits a draft campaign of the shop's storefront quiz.")
            .permissions(Shopify.USE)
            .contextFactory(ShopProcesses::context)
            .outputMapper(ctx -> ctx.get(OUTPUT, Campaign.class))
            .steps(b -> lockShopOfMine(b))
            .step("Load the campaign", QueryEntities.<ProcessContext>of(
                QbsShops.defaultDataset(QbsStorefront.CAMPAIGN),
                ctx -> ShopProcesses.eq("campaignId", ctx.get(INPUT, SaveInput.class).campaignId(), 1), CAMPAIGN))
            .compute("Save the draft", (metadata, ctx) -> save(ctx)));
    }

    static void save(ProcessContext ctx) {
        if (!memberOfActiveShop(ctx)) {
            return;
        }
        SaveInput input = ctx.get(INPUT, SaveInput.class);
        EntityInstance shop = ShopProcesses.first(ctx, SHOPS);
        if (input.passScore() > input.questions().size()) {
            ctx.reject(new Violation("passScore", ShopifyCodes.PASS_SCORE, "The pass score is above the number of "
                + "questions", Map.of("questions", input.questions().size())));
            return;
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("name", input.name().strip());
        values.put("video", input.video() == null || input.video().isBlank() ? null : input.video().strip());
        values.put("timeLimitSec", BigDecimal.valueOf(input.timeLimitSec()));
        values.put("passScore", BigDecimal.valueOf(input.passScore()));
        values.put("percent", BigDecimal.valueOf(input.percent()));
        values.put("questions", JSON.writeValueAsString(input.questions()));
        values.put("editedAt", ctx.opTime());
        if (input.campaignId() == null) {
            values.put("shopId", String.valueOf(shop.id()));
            values.put("shopDomain", shop.get("shopDomain"));
            values.put("status", QbsStorefront.DRAFT);
            values.put("revision", BigDecimal.ONE);
            values.put("createdAt", ctx.opTime());
            Object id = ctx.changes().insert(QbsStorefront.CAMPAIGN, values);
            ctx.put(OUTPUT, view(id, values));
            return;
        }
        EntityInstance campaign = ofShop(ctx, input.campaignId());
        if (campaign == null) {
            return;
        }
        if (!QbsStorefront.DRAFT.equals(campaign.get("status"))) {
            ctx.reject(new Violation("campaignId", ShopifyCodes.CAMPAIGN_NOT_DRAFT, "Only a draft can be edited",
                Map.of("status", String.valueOf((Object) campaign.get("status")))));
            return;
        }
        long revision = number(campaign.get("revision"));
        if (input.baseRevision() != null && input.baseRevision() != revision) {
            ctx.reject(new Violation("baseRevision", ShopifyCodes.CAMPAIGN_CHANGED, "The draft was changed meanwhile",
                Map.of("revision", revision)));
            return;
        }
        values.put("revision", BigDecimal.valueOf(revision + 1));
        ctx.changes().update(QbsStorefront.CAMPAIGN, campaign.id(), campaign.version(), values);
        Map<String, Object> merged = new LinkedHashMap<>(campaign.attributes());
        merged.putAll(values);
        ctx.put(OUTPUT, view(campaign.id(), merged));
    }

    // ---- publish ---------------------------------------------------------------------------------------------------

    static ProcessDefinition<CampaignRef, Campaign, ProcessContext> publish() {
        return ProcessDefinition.define(PUBLISH, 1, CampaignRef.class, Campaign.class, ProcessContext.class, pb -> pb
            .description("A member puts a draft campaign on the shop's storefront.")
            .permissions(Shopify.USE)
            .contextFactory(ShopProcesses::context)
            .outputMapper(ctx -> ctx.get(OUTPUT, Campaign.class))
            .steps(b -> lockShopOfMine(b))
            .step("Load the campaign and the running one", QueryEntities.all(
                QueryEntities.<ProcessContext>query(QbsShops.defaultDataset(QbsStorefront.CAMPAIGN),
                    ctx -> ShopProcesses.eq("campaignId", ctx.get(INPUT, CampaignRef.class).campaignId(), 1),
                    CAMPAIGN),
                QueryEntities.<ProcessContext>query(QbsShops.defaultDataset(QbsStorefront.CAMPAIGN),
                    ctx -> running(ShopProcesses.domainOfMine(ctx)), RUNNING)))
            .compute("Check the draft", (metadata, ctx) -> checkPublishing(ctx))
            .step("Publish on the engine", CallProcess.<ProcessContext>when(ctx -> !ctx.hasViolations(),
                Channel.PUBLISH, 1, CampaignProcesses::publishInput, PUBLISHED))
            .compute("Put it live", (metadata, ctx) -> published(ctx)));
    }

    static void checkPublishing(ProcessContext ctx) {
        if (!memberOfActiveShop(ctx)) {
            return;
        }
        EntityInstance shop = ShopProcesses.first(ctx, SHOPS);
        EntityInstance campaign = ofShop(ctx, ctx.get(INPUT, CampaignRef.class).campaignId());
        if (campaign == null) {
            return;
        }
        if (!QbsStorefront.DRAFT.equals(campaign.get("status"))) {
            ctx.reject(new Violation("campaignId", ShopifyCodes.CAMPAIGN_NOT_DRAFT, "Only a draft can be published",
                Map.of("status", String.valueOf((Object) campaign.get("status")))));
            return;
        }
        if (ShopProcesses.first(ctx, RUNNING) != null) {
            ctx.reject(new Violation("campaignId", ShopifyCodes.CAMPAIGN_RUNNING, "Another campaign is running: end "
                + "it first", Map.of()));
            return;
        }
        if (!QbsShops.CONNECTED.equals(shop.get("connection"))) {
            ctx.reject(new Violation(null, ShopifyCodes.NOT_CONNECTED, "The shop is not connected", Map.of()));
            return;
        }
        ctx.put(CAMPAIGN, List.of(campaign));
    }

    static Channel.PublishInput publishInput(ProcessContext ctx) {
        EntityInstance campaign = ShopProcesses.first(ctx, CAMPAIGN);
        return new Channel.PublishInput(publisher(ShopProcesses.first(ctx, SHOPS)), campaign.get("name"),
            campaign.get("video"), integer(campaign.get("timeLimitSec")), integer(campaign.get("passScore")),
            questions(campaign.get("questions")));
    }

    static void published(ProcessContext ctx) {
        if (ctx.hasViolations()) {
            return;
        }
        EntityInstance campaign = ShopProcesses.first(ctx, CAMPAIGN);
        Channel.PublishOutput publication = ctx.get(PUBLISHED, Channel.PublishOutput.class);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("status", QbsStorefront.LIVE);
        values.put("publicationId", publication.publicationId().toString());
        values.put("player", publication.video());
        values.put("publishedAt", ctx.opTime());
        values.put("editedAt", ctx.opTime());
        values.put("revision", BigDecimal.valueOf(number(campaign.get("revision")) + 1));
        ctx.changes().update(QbsStorefront.CAMPAIGN, campaign.id(), campaign.version(), values);
        Map<String, Object> merged = new LinkedHashMap<>(campaign.attributes());
        merged.putAll(values);
        ctx.put(OUTPUT, view(campaign.id(), merged));
    }

    // ---- move ------------------------------------------------------------------------------------------------------

    static ProcessDefinition<MoveInput, Campaign, ProcessContext> move() {
        return ProcessDefinition.define(MOVE, 1, MoveInput.class, Campaign.class, ProcessContext.class, pb -> pb
            .description("A member pauses, resumes or ends the shop's running campaign, or ends a draft.")
            .permissions(Shopify.USE)
            .contextFactory(ShopProcesses::context)
            .outputMapper(ctx -> ctx.get(OUTPUT, Campaign.class))
            .steps(b -> lockShopOfMine(b))
            .step("Load the campaign", QueryEntities.<ProcessContext>of(
                QbsShops.defaultDataset(QbsStorefront.CAMPAIGN),
                ctx -> ShopProcesses.eq("campaignId", ctx.get(INPUT, MoveInput.class).campaignId(), 1), CAMPAIGN))
            .compute("Check the move", (metadata, ctx) -> checkMove(ctx))
            .step("Move the publication", CallProcess.<ProcessContext>when(
                ctx -> !ctx.hasViolations() && ShopProcesses.first(ctx, CAMPAIGN).get("publicationId") != null,
                Channel.MOVE, 1, ctx -> {
                    EntityInstance campaign = ShopProcesses.first(ctx, CAMPAIGN);
                    return new Channel.MoveInput(publisher(ShopProcesses.first(ctx, SHOPS)),
                        UUID.fromString(campaign.get("publicationId")), ctx.get(INPUT, MoveInput.class).to());
                }, MOVED))
            .compute("Move the campaign", (metadata, ctx) -> moved(ctx)));
    }

    static void checkMove(ProcessContext ctx) {
        if (!memberOfActiveShop(ctx)) {
            return;
        }
        MoveInput input = ctx.get(INPUT, MoveInput.class);
        EntityInstance campaign = ofShop(ctx, input.campaignId());
        if (campaign == null) {
            return;
        }
        String from = campaign.get("status");
        boolean allowed = switch (input.to()) {
            case QbsStorefront.PAUSED -> QbsStorefront.LIVE.equals(from);
            case QbsStorefront.LIVE -> QbsStorefront.PAUSED.equals(from);
            default -> !QbsStorefront.ENDED.equals(from);
        };
        if (!allowed) {
            ctx.reject(new Violation("to", ShopifyCodes.CAMPAIGN_MOVE, "The campaign cannot go from " + from + " to "
                + input.to(), Map.of("from", from, "to", input.to())));
            return;
        }
        ctx.put(CAMPAIGN, List.of(campaign));
    }

    static void moved(ProcessContext ctx) {
        if (ctx.hasViolations()) {
            return;
        }
        EntityInstance campaign = ShopProcesses.first(ctx, CAMPAIGN);
        String to = ctx.get(INPUT, MoveInput.class).to();
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("status", to);
        values.put("editedAt", ctx.opTime());
        values.put("revision", BigDecimal.valueOf(number(campaign.get("revision")) + 1));
        if (QbsStorefront.ENDED.equals(to)) {
            values.put("endedAt", ctx.opTime());
        }
        ctx.changes().update(QbsStorefront.CAMPAIGN, campaign.id(), campaign.version(), values);
        Map<String, Object> merged = new LinkedHashMap<>(campaign.attributes());
        merged.putAll(values);
        ctx.put(OUTPUT, view(campaign.id(), merged));
    }

    // ---- list ------------------------------------------------------------------------------------------------------

    static ProcessDefinition<ListInput, ListOutput, ProcessContext> list() {
        return ProcessDefinition.define(LIST, 1, ListInput.class, ListOutput.class, ProcessContext.class, pb -> pb
            .description("A member reads the shop's campaigns and the figures of its pool of codes.")
            .permissions(Shopify.USE)
            .contextFactory(ShopProcesses::context)
            .outputMapper(ctx -> ctx.get(OUTPUT, ListOutput.class))
            .step("Find the operator's shop", QueryEntities.<ProcessContext>of(
                QbsShops.defaultDataset(QbsShops.MEMBER),
                ctx -> ShopProcesses.eq("userId", ctx.request().actorId(), 1), MINE))
            .step("Load the shop and its campaigns", QueryEntities.all(
                QueryEntities.<ProcessContext>query(QbsShops.defaultDataset(QbsShops.SHOP),
                    ctx -> ShopProcesses.eq("shopDomain", ShopProcesses.domainOfMine(ctx), 1), SHOPS),
                QueryEntities.<ProcessContext>query(QbsShops.defaultDataset(QbsStorefront.CAMPAIGN),
                    ctx -> EntityQuery.builder().where(domainIs(ShopProcesses.domainOfMine(ctx)))
                        .orderBy("createdAt", false).limit(LIST_LIMIT).build(), CAMPAIGNS)))
            .step("Count the pool", RunTemplate.<ProcessContext>of(STATS_TEMPLATE, CampaignProcesses::statsParams,
                STATS))
            .compute("List", (metadata, ctx) -> {
                if (!memberOfActiveShop(ctx)) {
                    return;
                }
                List<Campaign> campaigns = new ArrayList<>();
                for (EntityInstance row : ShopProcesses.rows(ctx, CAMPAIGNS)) {
                    campaigns.add(view(row.id(), row.attributes()));
                }
                ctx.put(OUTPUT, new ListOutput(List.copyOf(campaigns), pool(ctx.get(STATS))));
            }));
    }

    /** The parameters of {@link #STATS_TEMPLATE}: the shop, the codes' least end, the day's and the week's start. */
    static Map<String, Object> statsParams(ProcessContext ctx) {
        String domain = ShopProcesses.domainOfMine(ctx);
        Instant now = ctx.opTime();
        Instant day = now.atOffset(ZoneOffset.UTC).toLocalDate().atStartOfDay().toInstant(ZoneOffset.UTC);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("shopDomain", domain == null ? "-" : domain);
        params.put("usableUntil", now.plus(Pool.MIN_DAYS_LEFT));
        params.put("dayStart", day);
        params.put("weekStart", day.minus(Duration.ofDays(6)));
        return params;
    }

    /** The pool's figures from the rows of {@link #STATS_TEMPLATE}. */
    @SuppressWarnings("unchecked")
    static Pool pool(Object stats) {
        List<Map<String, Object>> rows = (List<Map<String, Object>>) stats;
        Map<String, Object> row = rows == null || rows.isEmpty() ? Map.of() : rows.getFirst();
        return new Pool(number(row.get("available")), number(row.get("issuedToday")), number(row.get("issuedWeek")));
    }

    // ---- shared ----------------------------------------------------------------------------------------------------

    /** Finds the operator's shop, takes its lock and loads it (the membership again, under the lock). */
    static <I, O> void lockShopOfMine(com.jabiz.process.ProcessDefinitionBuilder<I, O, ProcessContext> pb) {
        pb.step("Find the operator's shop", QueryEntities.<ProcessContext>of(QbsShops.defaultDataset(QbsShops.MEMBER),
                ctx -> ShopProcesses.eq("userId", ctx.request().actorId(), 1), MINE))
            .step("Lock the shop", HoldLock.<ProcessContext>exclusive(ShopProcesses::lockOfMine))
            .step("Load the shop", QueryEntities.<ProcessContext>of(QbsShops.defaultDataset(QbsShops.SHOP),
                ctx -> ShopProcesses.eq("shopDomain", ShopProcesses.domainOfMine(ctx), 1), SHOPS));
    }

    static boolean memberOfActiveShop(ProcessContext ctx) {
        return !ctx.hasViolations() && ShopProcesses.requireMemberOfActiveShop(ctx, ShopProcesses.first(ctx, MINE),
            ShopProcesses.first(ctx, SHOPS));
    }

    /** The campaign read as {@link #CAMPAIGN}, if it is the operator's shop's; else 404 (another's is as none). */
    static EntityInstance ofShop(ProcessContext ctx, String campaignId) {
        EntityInstance campaign = ShopProcesses.first(ctx, CAMPAIGN);
        EntityInstance shop = ShopProcesses.first(ctx, SHOPS);
        if (campaign == null || !shop.get("shopDomain").equals(campaign.get("shopDomain"))) {
            throw new EntityNotFoundException(QbsStorefront.CAMPAIGN + " " + campaignId + " not found");
        }
        return campaign;
    }

    /** The shop as the engine's publisher: its record's key (S0, D-S0-1). */
    static Channel.Publisher publisher(EntityInstance shop) {
        return new Channel.Publisher(Channel.SHOPIFY_SHOP, String.valueOf(shop.id()));
    }

    static EntityQuery running(String shopDomain) {
        return EntityQuery.builder().where(new QueryPredicate.And(List.of(domainIs(shopDomain),
            new QueryPredicate.In("status", List.of(QbsStorefront.LIVE, QbsStorefront.PAUSED))))).limit(1).build();
    }

    static QueryPredicate domainIs(String shopDomain) {
        return shopDomain == null ? new QueryPredicate.In("shopDomain", List.of())
            : new QueryPredicate.Eq("shopDomain", shopDomain);
    }

    static List<Channel.QuestionInput> questions(Object json) {
        return json == null ? List.of() : JSON.readValue(json.toString(), QUESTIONS);
    }

    static Campaign view(Object id, Map<String, Object> values) {
        return new Campaign(String.valueOf(id), (String) values.get("status"), (String) values.get("name"),
            (String) values.get("video"), integer(values.get("timeLimitSec")), integer(values.get("passScore")),
            integer(values.get("percent")), questions(values.get("questions")), (String) values.get("publicationId"),
            number(values.get("revision")), instant(values.get("createdAt")), instant(values.get("editedAt")),
            instant(values.get("publishedAt")), instant(values.get("endedAt")));
    }

    static int integer(Object value) {
        return value == null ? 0 : new BigDecimal(value.toString()).intValueExact();
    }

    static long number(Object value) {
        return value == null ? 0 : new BigDecimal(value.toString()).longValueExact();
    }

    static Instant instant(Object value) {
        return value == null ? null : value instanceof Instant time ? time
            : value instanceof java.time.OffsetDateTime time ? time.toInstant() : Instant.parse(value.toString());
    }
}
