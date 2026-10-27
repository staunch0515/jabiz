package com.jabiz.quizbuks.shopify;

import com.jabiz.entity.Violation;
import com.jabiz.param.ParamValues;
import com.jabiz.process.BlockingStep;
import com.jabiz.process.NoMetadata;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.quizbuks.channel.Channel;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.EntityNotFoundException;
import com.jabiz.runtime.process.steps.CallProcess;
import com.jabiz.runtime.process.steps.HoldLock;
import com.jabiz.runtime.process.steps.LoadParams;
import com.jabiz.runtime.process.steps.QueryEntities;
import com.jabiz.runtime.process.steps.RunTemplate;
import com.jabiz.runtime.security.secret.SecretSealer;
import com.jabiz.security.AppProxyCaller;
import com.jabiz.security.SealedSecrets;
import com.jabiz.security.Sensitive;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The storefront's quiz (docs/quizbuks/shopify/S2-storefront.md section 3): the product page's block calls these
 * through the shop's app proxy ({@code /apps/quizbuks} → {@code /api/proxy/shopify/<process>}, platform decision D60),
 * as the proxy's caller of the shop that forwarded the request (its {@code tenant}), for a signed-in customer of it or
 * an anonymous visitor (the key the block keeps in the browser, sent in the body). They run nothing of another shop.
 * <ul>
 *   <li>{@value #CAMPAIGN}: the shop's live campaign as the block shows it, or none;</li>
 *   <li>{@value #START}: starts (or continues) the person's attempt of it: one each, ever;</li>
 *   <li>{@value #SUBMIT}: scores it; when it passed, the campaign is live and the shop has handed out fewer than the
 *       day's limit ({@value Shopify#DAILY_CODES}), the next code of the pool is the person's, in the answer.
 *       Asked again for the same attempt, it answers the same code.</li>
 * </ul>
 * The limits per shop and per visitor key are configured for them ({@code jabiz.process.rate-limits}); the day's limit
 * bounds what a script that keeps changing its key can take.
 */
public final class StorefrontProcesses {

    private StorefrontProcesses() {}

    public static final String CAMPAIGN = "QBS_SF_CAMPAIGN";
    public static final String START = "QBS_SF_START";
    public static final String SUBMIT = "QBS_SF_SUBMIT";

    /** What the storefront's block keeps in the browser: random, made by the block. */
    public static final String VISITOR_PATTERN = "[A-Za-z0-9_-]{16,64}";

    /** How a submission ended for the code: issued now, issued before, none left today, none at all, not passed. */
    public static final String ISSUED = "ISSUED";
    public static final String AGAIN = "AGAIN";
    public static final String DAY_FULL = "DAY_FULL";
    public static final String NONE_LEFT = "NONE_LEFT";
    public static final String NOT_PASSED = "NOT_PASSED";
    public static final String CAMPAIGN_OVER = "CAMPAIGN_OVER";

    public record CampaignInput() {}

    /**
     * The live campaign as the block shows it; {@code live} false (and nothing else) when the shop has none.
     *
     * @param video      the player to show (YouTube or Vimeo), none for a quiz alone
     * @param codesToday whether the shop may still hand out a code today
     */
    public record CampaignOutput(boolean live, String publicationId, String name, String video, int questionCount,
        int timeLimitSec, int passScore, int percent, boolean codesToday) {

        static CampaignOutput none() {
            return new CampaignOutput(false, null, null, null, 0, 0, 0, 0, false);
        }
    }

    /** @param visitor the block's key; ignored for a signed-in customer */
    public record StartInput(@NotNull UUID publicationId, @Pattern(regexp = VISITOR_PATTERN) String visitor) {}

    public record SubmitInput(@NotNull UUID publicationId, @Pattern(regexp = VISITOR_PATTERN) String visitor,
        @NotBlank @Size(max = 36) String attemptId, Integer currentIndex,
        @Size(max = Channel.MAX_QUESTIONS) List<Channel.@Valid @NotNull Answer> answers) {}

    /**
     * The result: the score (none when the code was asked for again) and the discount code, if any. The code goes to
     * the person only: it is masked in the records (a component name of its own, as every {@code @Sensitive} one is
     * masked by name).
     *
     * @param outcome   what became of the code: {@value #ISSUED}, {@value #AGAIN}, {@value #DAY_FULL},
     *                  {@value #NONE_LEFT}, {@value #NOT_PASSED}, {@value #CAMPAIGN_OVER}
     * @param expiresAt when the code stops working (at least seven days from when it was issued)
     */
    public record SubmitOutput(String attemptId, Integer score, Integer fullScore, Integer passScore, boolean passed,
        String outcome, @Sensitive(returned = true) String discountCode, Integer percent, Instant expiresAt) {
        @Override
        public String toString() {
            return "SubmitOutput[attemptId=" + attemptId + ", passed=" + passed + ", outcome=" + outcome
                + ", discountCode=***]";
        }
    }

    static final String INPUT = ShopProcesses.INPUT;
    static final String SHOPS = ShopProcesses.SHOPS;
    static final String CAMPAIGNS = "campaigns";
    static final String TAKERS = "takers";
    static final String TAKER = "taker";
    static final String PARAMS = "params";
    static final String STATS = "stats";
    static final String STARTED = "started";
    static final String EARLIER = "earlier";
    static final String SUBMITTED = "submitted";
    static final String BATCHES = "batches";
    static final String NEXT = "next";
    static final String ISSUE = "issue";
    static final String OPENED = "opened";
    static final String OUTPUT = ShopProcesses.OUTPUT;

    // ---- campaign --------------------------------------------------------------------------------------------------

    static ProcessDefinition<CampaignInput, CampaignOutput, ProcessContext> campaign() {
        return ProcessDefinition.define(CAMPAIGN, 1, CampaignInput.class, CampaignOutput.class, ProcessContext.class,
            pb -> pb
                .description("The storefront's block reads the shop's live quiz campaign.")
                .appProxy(Shopify.PROXY)
                .contextFactory(ShopProcesses::context)
                .outputMapper(ctx -> ctx.get(OUTPUT, CampaignOutput.class))
                .steps(b -> loadShopAndCampaign(b, ctx -> null))
                .step("Load the day's limit", LoadParams.<ProcessContext>optional(ProcessContext::opTime, PARAMS,
                    Shopify.DAILY_CODES))
                .step("Count the pool", RunTemplate.<ProcessContext>of(CampaignProcesses.STATS_TEMPLATE,
                    StorefrontProcesses::statsParams, STATS))
                .compute("Show the campaign", (metadata, ctx) -> {
                    EntityInstance campaign = liveCampaign(ctx, null);
                    if (campaign == null) {
                        ctx.put(OUTPUT, CampaignOutput.none());
                        return;
                    }
                    CampaignProcesses.Pool pool = CampaignProcesses.pool(ctx.get(STATS));
                    ctx.put(OUTPUT, new CampaignOutput(true, campaign.get("publicationId"), campaign.get("name"),
                        campaign.get("player"), CampaignProcesses.questions(
                            campaign.get("questions")).size(), CampaignProcesses.integer(campaign.get("timeLimitSec")),
                        CampaignProcesses.integer(campaign.get("passScore")),
                        CampaignProcesses.integer(campaign.get("percent")),
                        pool.available() > 0 && pool.issuedToday() < dailyCodes(ctx)));
                }));
    }

    // ---- start -----------------------------------------------------------------------------------------------------

    static ProcessDefinition<StartInput, Channel.StartOutput, ProcessContext> start() {
        return ProcessDefinition.define(START, 1, StartInput.class, Channel.StartOutput.class, ProcessContext.class,
            pb -> pb
                .description("A person starts (or continues) the storefront's quiz.")
                .appProxy(Shopify.PROXY)
                .contextFactory(ShopProcesses::context)
                .outputMapper(ctx -> ctx.get(OUTPUT, Channel.StartOutput.class))
                .steps(b -> loadShopAndCampaign(b, ctx -> ctx.get(INPUT, StartInput.class).publicationId()))
                .compute("Find the person", (metadata, ctx) -> {
                    StartInput input = ctx.get(INPUT, StartInput.class);
                    if (liveCampaign(ctx, input.publicationId()) == null) {
                        throw new EntityNotFoundException("Campaign of publication " + input.publicationId()
                            + " not found");
                    }
                    ctx.put(TAKER, taker(ctx, input.visitor(), true));
                })
                .step("Start on the engine", CallProcess.<ProcessContext>when(ctx -> !ctx.hasViolations(),
                    Channel.START, 1, ctx -> new Channel.StartInput(CampaignProcesses.publisher(
                        ShopProcesses.first(ctx, SHOPS)), ctx.get(INPUT, StartInput.class).publicationId(),
                        ctx.get(TAKER, String.class)), OUTPUT)));
    }

    // ---- submit ----------------------------------------------------------------------------------------------------

    static ProcessDefinition<SubmitInput, SubmitOutput, ProcessContext> submit() {
        return ProcessDefinition.define(SUBMIT, 1, SubmitInput.class, SubmitOutput.class, ProcessContext.class,
            pb -> pb
                .description("A person submits the storefront's quiz; a pass gets the next discount code of the"
                    + " shop's pool while the day's codes last.")
                .appProxy(Shopify.PROXY)
                .contextFactory(ShopProcesses::context)
                .outputMapper(ctx -> ctx.get(OUTPUT, SubmitOutput.class))
                .steps(b -> loadShopAndCampaign(b, ctx -> ctx.get(INPUT, SubmitInput.class).publicationId()))
                .compute("Find the person", (metadata, ctx) -> {
                    SubmitInput input = ctx.get(INPUT, SubmitInput.class);
                    if (campaignOf(ctx, input.publicationId()) == null) {
                        throw new EntityNotFoundException("Campaign of publication " + input.publicationId()
                            + " not found");
                    }
                    ctx.put(TAKER, taker(ctx, input.visitor(), false));
                })
                .step("Load the code issued to the attempt", QueryEntities.<ProcessContext>of(
                    QbsShops.defaultDataset(QbsStorefront.CODE),
                    ctx -> ShopProcesses.eq("attemptId", ctx.get(INPUT, SubmitInput.class).attemptId(), 1), EARLIER))
                .compute("Check an earlier code", (metadata, ctx) -> {
                    EntityInstance earlier = ShopProcesses.first(ctx, EARLIER);
                    if (earlier != null && (!ctx.get(TAKER, String.class).equals(earlier.get("taker"))
                        || !domain(ctx).equals(earlier.get("shopDomain")))) {
                        // Another person's attempt is as good as none.
                        throw new EntityNotFoundException("Attempt " + ctx.get(INPUT, SubmitInput.class).attemptId()
                            + " not found");
                    }
                })
                .step("Submit on the engine", CallProcess.<ProcessContext>when(
                    ctx -> !ctx.hasViolations() && ShopProcesses.first(ctx, EARLIER) == null, Channel.SUBMIT, 1,
                    ctx -> {
                        SubmitInput input = ctx.get(INPUT, SubmitInput.class);
                        return new Channel.SubmitInput(CampaignProcesses.publisher(ShopProcesses.first(ctx, SHOPS)),
                            input.publicationId(), ctx.get(TAKER, String.class), input.attemptId(),
                            input.currentIndex(), input.answers());
                    }, SUBMITTED))
                // Last of the locks (after the engine's publication lock): the day's count and the next code.
                .step("Lock the shop's codes", HoldLock.<ProcessContext>exclusive(
                    ctx -> issuing(ctx) ? Shopify.codesLock(domain(ctx)) : null))
                .step("Load the day's limit", LoadParams.<ProcessContext>optional(ProcessContext::opTime, PARAMS,
                    Shopify.DAILY_CODES))
                .step("Count the day's codes", RunTemplate.<ProcessContext>of(CampaignProcesses.STATS_TEMPLATE,
                    StorefrontProcesses::statsParams, STATS))
                // To hand out of: the shop's ready ones; for the code handed out before, its own.
                .step("Load the batches", QueryEntities.<ProcessContext>of(
                    QbsShops.defaultDataset(QbsStorefront.BATCH), StorefrontProcesses::batches, BATCHES))
                .step("Load the next code", QueryEntities.<ProcessContext>of(
                    QbsShops.defaultDataset(QbsStorefront.CODE), ctx -> EntityQuery.builder()
                        .where(new QueryPredicate.And(List.of(
                            new QueryPredicate.In("batchId", usableBatches(ctx)),
                            new QueryPredicate.Eq("status", QbsStorefront.AVAILABLE))))
                        .orderBy("seq", true).limit(1).build(), NEXT))
                .compute("Hand out the code", (metadata, ctx) -> issue(ctx))
                .step("Open the code", OpenCode.class, NoMetadata.INSTANCE)
                .compute("Answer", (metadata, ctx) -> answer(ctx)));
    }

    /** Whether the submission passed now on a live campaign, so that a code may be handed out. */
    static boolean issuing(ProcessContext ctx) {
        Channel.SubmitOutput submitted = ctx.get(SUBMITTED, Channel.SubmitOutput.class);
        return !ctx.hasViolations() && submitted != null && submitted.passed()
            && liveCampaign(ctx, ctx.get(INPUT, SubmitInput.class).publicationId()) != null;
    }

    static EntityQuery batches(ProcessContext ctx) {
        EntityInstance earlier = ShopProcesses.first(ctx, EARLIER);
        if (earlier != null && !ctx.hasViolations()) {
            return ShopProcesses.eq("batchId", earlier.get("batchId"), 1);
        }
        return EntityQuery.builder().where(new QueryPredicate.And(List.of(
                CampaignProcesses.domainIs(issuing(ctx) ? domain(ctx) : null),
                new QueryPredicate.Eq("status", QbsStorefront.READY))))
            .limit(DiscountPool.MAX_BATCHES).build();
    }

    /** The ready batches with more than {@link DiscountPool#MIN_LEFT} left, oldest first. */
    static List<Object> usableBatches(ProcessContext ctx) {
        Instant until = ctx.opTime().plus(DiscountPool.MIN_LEFT);
        return ShopProcesses.rows(ctx, BATCHES).stream()
            .filter(batch -> CampaignProcesses.instant(batch.get("endsAt")).isAfter(until))
            .sorted(java.util.Comparator.comparing(batch -> CampaignProcesses.instant(batch.get("createdAt"))))
            .<Object>map(batch -> String.valueOf(batch.id())).toList();
    }

    static void issue(ProcessContext ctx) {
        if (!issuing(ctx)) {
            return;
        }
        if (CampaignProcesses.pool(ctx.get(STATS)).issuedToday() >= dailyCodes(ctx)) {
            ctx.put(ISSUE, DAY_FULL);
            return;
        }
        EntityInstance code = ShopProcesses.first(ctx, NEXT);
        if (code == null) {
            ctx.put(ISSUE, NONE_LEFT);
            return;
        }
        SubmitInput input = ctx.get(INPUT, SubmitInput.class);
        EntityInstance campaign = liveCampaign(ctx, input.publicationId());
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("status", QbsStorefront.ISSUED);
        values.put("campaignId", String.valueOf(campaign.id()));
        values.put("taker", ctx.get(TAKER, String.class));
        values.put("attemptId", input.attemptId());
        values.put("issuedAt", ctx.opTime());
        ctx.changes().update(QbsStorefront.CODE, code.id(), code.version(), values);
        ctx.put(ISSUE, ISSUED);
    }

    /** Opens the code the answer gives: the one handed out now, or the one handed out to the attempt before. */
    @Component
    public static class OpenCode implements BlockingStep<NoMetadata, ProcessContext> {

        private final SecretSealer sealer;

        public OpenCode(SecretSealer sealer) {
            this.sealer = sealer;
        }

        @Override
        public void run(NoMetadata metadata, ProcessContext ctx) {
            if (ctx.hasViolations()) {
                return;
            }
            EntityInstance code = ShopProcesses.first(ctx, EARLIER);
            if (code == null && ISSUED.equals(ctx.get(ISSUE))) {
                code = ShopProcesses.first(ctx, NEXT);
            }
            if (code == null) {
                return;
            }
            try {
                ctx.put(OPENED, sealer.open(code.get("sealedCode"), domain(ctx)));
            } catch (SealedSecrets.UnreadableSecretException e) {
                ctx.reject(new Violation(null, ShopifyCodes.CODE_UNREADABLE, "The code cannot be opened",
                    Map.of()));
            }
        }
    }

    static void answer(ProcessContext ctx) {
        if (ctx.hasViolations()) {
            return;
        }
        SubmitInput input = ctx.get(INPUT, SubmitInput.class);
        EntityInstance campaign = campaignOf(ctx, input.publicationId());
        Integer percent = CampaignProcesses.integer(campaign.get("percent"));
        EntityInstance earlier = ShopProcesses.first(ctx, EARLIER);
        if (earlier != null) {
            ctx.put(OUTPUT, new SubmitOutput(input.attemptId(), null, null, null, true, AGAIN,
                ctx.get(OPENED, String.class), percent, endsAt(ctx, earlier)));
            return;
        }
        Channel.SubmitOutput submitted = ctx.get(SUBMITTED, Channel.SubmitOutput.class);
        String outcome = !submitted.passed() ? NOT_PASSED
            : ctx.get(ISSUE) == null ? CAMPAIGN_OVER : ctx.get(ISSUE, String.class);
        String code = ISSUED.equals(outcome) ? ctx.get(OPENED, String.class) : null;
        ctx.put(OUTPUT, new SubmitOutput(submitted.attemptId(), submitted.score(), submitted.fullScore(),
            submitted.passScore(), submitted.passed(), outcome, code, code == null ? null : percent,
            code == null ? null : endsAt(ctx, ShopProcesses.first(ctx, NEXT))));
    }

    /** When a code stops working: its batch's end (the batch is read with the usable ones, or found again). */
    static Instant endsAt(ProcessContext ctx, EntityInstance code) {
        return ShopProcesses.rows(ctx, BATCHES).stream()
            .filter(batch -> String.valueOf(batch.id()).equals(code.get("batchId")))
            .map(batch -> CampaignProcesses.instant(batch.get("endsAt"))).findFirst().orElse(null);
    }

    // ---- shared ----------------------------------------------------------------------------------------------------

    /** The shop the request came from and its running campaign (all of the publication's, when one is named). */
    static void loadShopAndCampaign(com.jabiz.process.ProcessDefinitionBuilder<?, ?, ProcessContext> pb,
        java.util.function.Function<ProcessContext, UUID> publication) {
        pb.step("Load the shop and its campaign", QueryEntities.all(
            QueryEntities.<ProcessContext>query(QbsShops.defaultDataset(QbsShops.SHOP),
                ctx -> ShopProcesses.eq("shopDomain", domain(ctx), 1), SHOPS),
            QueryEntities.<ProcessContext>query(QbsShops.defaultDataset(QbsStorefront.CAMPAIGN), ctx -> {
                UUID publicationId = publication.apply(ctx);
                return publicationId == null ? CampaignProcesses.running(domain(ctx))
                    : ShopProcesses.eq("publicationId", publicationId.toString(), 1);
            }, CAMPAIGNS),
            // A signed-in customer's row, if they have one (none is read for a visitor).
            QueryEntities.<ProcessContext>query(QbsShops.defaultDataset(QbsStorefront.TAKER), ctx -> {
                String customer = AppProxyCaller.of(ctx.request()).map(AppProxyCaller::customerId).orElse(null);
                return EntityQuery.builder().where(new QueryPredicate.And(List.of(
                    new QueryPredicate.Eq("shopDomain", domain(ctx)),
                    customer == null ? new QueryPredicate.In("customerId", List.of())
                        : new QueryPredicate.Eq("customerId", customer)))).limit(1).build();
            }, TAKERS)));
    }

    /** The shop the request came from: the app proxy's signed tenant. */
    static String domain(ProcessContext ctx) {
        return AppProxyCaller.of(ctx.request()).map(AppProxyCaller::tenant)
            .orElseThrow(() -> new IllegalStateException("Not an app proxy's request"));
    }

    /** The campaign read, if it is the shop's (and the named publication's), whatever its state. */
    static EntityInstance campaignOf(ProcessContext ctx, UUID publicationId) {
        EntityInstance shop = ShopProcesses.first(ctx, SHOPS);
        EntityInstance campaign = ShopProcesses.first(ctx, CAMPAIGNS);
        if (shop == null || campaign == null || !domain(ctx).equals(campaign.get("shopDomain"))
            || publicationId != null && !publicationId.toString().equals(campaign.get("publicationId"))) {
            return null;
        }
        return campaign;
    }

    /** The campaign read, if the shop is installed and it is live. */
    static EntityInstance liveCampaign(ProcessContext ctx, UUID publicationId) {
        EntityInstance campaign = campaignOf(ctx, publicationId);
        EntityInstance shop = ShopProcesses.first(ctx, SHOPS);
        return campaign != null && QbsShops.ACTIVE.equals(shop.get("status"))
            && QbsStorefront.LIVE.equals(campaign.get("status")) ? campaign : null;
    }

    /**
     * The person's key on the engine: a signed-in customer's row (made at their first start), or a digest of the
     * visitor's key. A visitor without a key, or a customer without a row on a submission, is refused.
     */
    static String taker(ProcessContext ctx, String visitor, boolean starting) {
        AppProxyCaller caller = AppProxyCaller.of(ctx.request()).orElseThrow();
        String domain = domain(ctx);
        if (caller.customerId() == null) {
            if (visitor == null) {
                ctx.reject(new Violation("visitor", ShopifyCodes.VISITOR_REQUIRED, "A visitor's key is required",
                    Map.of()));
                return null;
            }
            return QbsStorefront.visitorTaker(domain, visitor);
        }
        EntityInstance row = ShopProcesses.first(ctx, TAKERS);
        if (row != null) {
            return QbsStorefront.customerTaker(row.id());
        }
        if (!starting) {
            throw new EntityNotFoundException("Attempt " + ctx.get(INPUT, SubmitInput.class).attemptId()
                + " not found");
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("shopDomain", domain);
        values.put("customerId", caller.customerId());
        values.put("createdAt", ctx.opTime());
        return QbsStorefront.customerTaker(ctx.changes().insert(QbsStorefront.TAKER, values));
    }

    static Map<String, Object> statsParams(ProcessContext ctx) {
        Instant now = ctx.opTime();
        Instant day = now.atOffset(ZoneOffset.UTC).toLocalDate().atStartOfDay().toInstant(ZoneOffset.UTC);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("shopDomain", domain(ctx));
        params.put("usableUntil", now.plus(DiscountPool.MIN_LEFT));
        params.put("dayStart", day);
        params.put("weekStart", day);
        return params;
    }

    /** The day's limit: the parameter as in effect now, its declared value before the setup declared it. */
    static long dailyCodes(ProcessContext ctx) {
        ParamValues params = ctx.get(PARAMS, ParamValues.class);
        return params == null ? Shopify.DAILY_CODES_DEFAULT : params.find(Shopify.DAILY_CODES, BigDecimal.class)
            .map(BigDecimal::longValue).orElse((long) Shopify.DAILY_CODES_DEFAULT);
    }
}
