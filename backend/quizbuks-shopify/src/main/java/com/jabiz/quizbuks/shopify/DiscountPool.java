package com.jabiz.quizbuks.shopify;

import com.jabiz.process.BlockingStep;
import com.jabiz.process.NoMetadata;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.process.steps.HoldLock;
import com.jabiz.runtime.process.steps.QueryEntities;
import com.jabiz.runtime.process.steps.RunTemplate;
import com.jabiz.runtime.security.secret.SecretSealer;
import com.jabiz.security.SealedSecrets;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A shop's pool of discount codes (docs/quizbuks/shopify/S2-storefront.md section 2), kept by the job
 * {@value #JOB}: every ten minutes, for each shop the template {@value #SHOPS_TEMPLATE} selects, {@value #REFILL} (one
 * transaction each, as the system) does what the shop's batches need:
 * <ul>
 *   <li>a batch Shopify is still adding codes to ({@code CREATING}) becomes {@code READY} once Shopify is done, its
 *       codes Shopify did not add {@code FAILED};</li>
 *   <li>a ready batch with {@link #MIN_LEFT} or less left is {@code RETIRED}: no more codes are handed out of it, so a
 *       code always has at least that long to be used;</li>
 *   <li>a retired batch whose end passed is deleted from the shop (its codes no longer work) and here with its codes
 *       ({@code DELETED}): a shop holds about two weeks of codes;</li>
 *   <li>for a live campaign, when fewer than {@link #LOW} codes can be handed out and no batch is being created, a new
 *       batch: a Shopify discount of the campaign's percentage off everything, once per customer, each code once,
 *       for {@link #LIFETIME}, with {@link #BATCH_CODES} codes made here and sealed under the shop's address.</li>
 * </ul>
 * Shopify is called under the shop's token lock only (its pair is refreshed there when it is about to end, as
 * {@link ConnectionProcesses} does); the storefront hands codes out under the shop's code lock meanwhile, and reads only
 * ready batches with more than {@link #MIN_LEFT} left, which this changes no code of. A batch Shopify created but whose
 * transaction failed is unknown here: the next run creates another, and the lost discount ends by itself.
 */
public final class DiscountPool {

    private DiscountPool() {}

    public static final String REFILL = "QBS_DISCOUNT_REFILL";
    public static final String JOB = "qbs.discount-refill";
    public static final String SHOPS_TEMPLATE = "qbs.system.refill-shops";

    /** A batch's codes: as many as Shopify adds in one bulk creation, the first one with the discount itself. */
    public static final int BATCH_CODES = 250;
    /** Below this many codes to hand out, a new batch is made. */
    public static final int LOW = 50;
    /** How long a batch's discount runs. */
    public static final Duration LIFETIME = Duration.ofDays(14);
    /** The least time a handed-out code has left: a batch with this or less left is retired. */
    public static final Duration MIN_LEFT = CampaignProcesses.Pool.MIN_DAYS_LEFT;
    /** The most batches of a shop read at once: about six live at a time (two weeks, two or three days each). */
    static final int MAX_BATCHES = 100;

    public record RefillInput(@NotBlank @Size(max = Shopify.SHOP_DOMAIN_LENGTH) String shopDomain) {}

    /** What was done: batches finished, retired, deleted, and whether one was created. */
    public record RefillOutput(String shopDomain, int finished, int retired, int deleted, boolean created) {}

    /** What the run decided before calling Shopify. */
    record Plan(List<EntityInstance> finish, List<EntityInstance> retire, List<EntityInstance> delete,
        EntityInstance campaign, boolean useApi) {}

    /** A new batch as Shopify created it, its codes sealed. */
    record Created(ShopifyApi.CreatedBatch batch, List<String> sealed, List<String> hints, int percent,
        Instant endsAt) {}

    static final String INPUT = ShopProcesses.INPUT;
    static final String SHOPS = ShopProcesses.SHOPS;
    static final String TOKENS = ConnectionProcesses.TOKENS;
    static final String CAMPAIGN = "campaign";
    static final String BATCHES = "batches";
    static final String STATS = "stats";
    static final String PLAN = "plan";
    static final String CODES = "codes";
    static final String DONE = "done";
    static final String FAILED_CODES = "failedCodes";
    static final String GONE = "gone";
    static final String CREATED = "created";
    static final String OUTPUT = ShopProcesses.OUTPUT;

    static ProcessDefinition<RefillInput, RefillOutput, ProcessContext> refill() {
        return ProcessDefinition.define(REFILL, 1, RefillInput.class, RefillOutput.class, ProcessContext.class,
            pb -> pb
                .description("Keeps a shop's pool of discount codes: finishes, retires and deletes its batches, and"
                    + " makes a new one when the live campaign runs low.")
                .permissions(Shopify.SYSTEM)
                .internal()
                .contextFactory(ShopProcesses::context)
                .outputMapper(ctx -> ctx.get(OUTPUT, RefillOutput.class))
                .step("Lock the shop's tokens", HoldLock.<ProcessContext>exclusive(
                    ctx -> Shopify.tokenLock(domain(ctx))))
                .step("Load the shop, its live campaign and its batches", QueryEntities.all(
                    QueryEntities.<ProcessContext>query(QbsShops.defaultDataset(QbsShops.SHOP),
                        ctx -> ShopProcesses.eq("shopDomain", domain(ctx), 1), SHOPS),
                    QueryEntities.<ProcessContext>query(QbsShops.defaultDataset(QbsStorefront.CAMPAIGN),
                        ctx -> EntityQuery.builder().where(new QueryPredicate.And(List.of(
                            new QueryPredicate.Eq("shopDomain", domain(ctx)),
                            new QueryPredicate.Eq("status", QbsStorefront.LIVE)))).limit(1).build(), CAMPAIGN),
                    QueryEntities.<ProcessContext>query(QbsShops.defaultDataset(QbsStorefront.BATCH),
                        ctx -> EntityQuery.builder().where(new QueryPredicate.And(List.of(
                            new QueryPredicate.Eq("shopDomain", domain(ctx)),
                            new QueryPredicate.In("status", List.of(QbsStorefront.CREATING, QbsStorefront.READY,
                                QbsStorefront.RETIRED))))).limit(MAX_BATCHES).build(), BATCHES)))
                .step("Load the shop's tokens", QueryEntities.<ProcessContext>of(
                    QbsShops.defaultDataset(QbsShops.TOKEN), ConnectionProcesses::tokensOfShop, TOKENS))
                .step("Count the pool", RunTemplate.<ProcessContext>of(CampaignProcesses.STATS_TEMPLATE,
                    ctx -> {
                        Map<String, Object> params = new LinkedHashMap<>();
                        params.put("shopDomain", domain(ctx));
                        params.put("usableUntil", ctx.opTime().plus(MIN_LEFT));
                        params.put("dayStart", ctx.opTime());
                        params.put("weekStart", ctx.opTime());
                        return params;
                    }, STATS))
                .compute("Plan", (metadata, ctx) -> plan(ctx))
                .step("Load the codes of the batches to finish or delete", QueryEntities.<ProcessContext>of(
                    QbsShops.defaultDataset(QbsStorefront.CODE), ctx -> {
                        Plan plan = ctx.get(PLAN, Plan.class);
                        List<Object> batches = new ArrayList<>();
                        plan.finish().forEach(batch -> batches.add(String.valueOf(batch.id())));
                        plan.delete().forEach(batch -> batches.add(String.valueOf(batch.id())));
                        return EntityQuery.builder().where(new QueryPredicate.In("batchId", batches))
                            .limit(MAX_BATCHES * BATCH_CODES).build();
                    }, CODES))
                .step("Call Shopify", CallShopify.class, NoMetadata.INSTANCE)
                .step("Seal the access token", ConnectionProcesses.sealAccess())
                .step("Seal the refresh token", ConnectionProcesses.sealRefresh())
                .compute("Keep", (metadata, ctx) -> keep(ctx)));
    }

    static String domain(ProcessContext ctx) {
        return Shopify.lower(ctx.get(INPUT, RefillInput.class).shopDomain());
    }

    static void plan(ProcessContext ctx) {
        Instant now = ctx.opTime();
        EntityInstance shop = ShopProcesses.first(ctx, SHOPS);
        List<EntityInstance> finish = new ArrayList<>();
        List<EntityInstance> retire = new ArrayList<>();
        List<EntityInstance> delete = new ArrayList<>();
        boolean creating = false;
        for (EntityInstance batch : ShopProcesses.rows(ctx, BATCHES)) {
            Instant ends = CampaignProcesses.instant(batch.get("endsAt"));
            switch ((String) batch.get("status")) {
                case QbsStorefront.CREATING -> {
                    creating = true;
                    finish.add(batch);
                }
                case QbsStorefront.READY -> {
                    if (!ends.isAfter(now.plus(MIN_LEFT))) {
                        retire.add(batch);
                    }
                }
                default -> {
                    if (!ends.isAfter(now)) {
                        delete.add(batch);
                    }
                }
            }
        }
        boolean usable = shop != null && QbsShops.ACTIVE.equals(shop.get("status"))
            && QbsShops.CONNECTED.equals(shop.get("connection")) && ShopProcesses.first(ctx, TOKENS) != null;
        EntityInstance campaign = ShopProcesses.first(ctx, CAMPAIGN);
        boolean low = campaign != null && !creating && CampaignProcesses.pool(ctx.get(STATS)).available() < LOW;
        ctx.put(PLAN, new Plan(finish, retire, delete, low ? campaign : null,
            usable && (!finish.isEmpty() || !delete.isEmpty() || low)));
    }

    /**
     * Calls Shopify for what the plan needs, with the shop's access token (refreshed when about to end; dropped when
     * Shopify refuses it, and then nothing else is called): the state of each batch being created, the deletion of
     * each ended discount, and a new batch.
     */
    @Component
    public static class CallShopify implements BlockingStep<NoMetadata, ProcessContext> {

        private static final SecureRandom RANDOM = new SecureRandom();
        /** Letters and digits nobody mistakes for one another. */
        private static final String ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";

        private final ShopifyApi api;
        private final SecretSealer sealer;

        public CallShopify(ShopifyApi api, SecretSealer sealer) {
            this.api = api;
            this.sealer = sealer;
        }

        @Override
        public void run(NoMetadata metadata, ProcessContext ctx) {
            Plan plan = ctx.get(PLAN, Plan.class);
            if (!plan.useApi()) {
                return;
            }
            String domain = domain(ctx);
            String access = ConnectionProcesses.access(ctx, api, sealer, domain);
            if (access == null) {
                return;
            }
            Map<Object, Set<String>> done = new LinkedHashMap<>();
            for (EntityInstance batch : plan.finish()) {
                String bulk = batch.get("bulkCreationId");
                if (bulk == null) {
                    done.put(batch.id(), Set.of());
                    continue;
                }
                ShopifyApi.Answer<ShopifyApi.BulkStatus> status = api.bulkStatus(domain, access, bulk);
                if (status.refused()) {
                    ctx.put(ConnectionProcesses.DROPPED, true);
                    return;
                }
                if (status.value().done()) {
                    done.put(batch.id(), status.value().failed());
                }
            }
            ctx.put(DONE, done);
            ctx.put(FAILED_CODES, failedRows(ctx, done, domain));
            List<Object> gone = new ArrayList<>();
            for (EntityInstance batch : plan.delete()) {
                ShopifyApi.Answer<Boolean> deleted = api.deleteDiscount(domain, access, batch.get("discountId"));
                if (deleted.refused()) {
                    ctx.put(ConnectionProcesses.DROPPED, true);
                    return;
                }
                gone.add(batch.id());
            }
            ctx.put(GONE, gone);
            EntityInstance campaign = plan.campaign();
            if (campaign != null) {
                int percent = CampaignProcesses.integer(campaign.get("percent"));
                Instant endsAt = ctx.opTime().plus(LIFETIME);
                List<String> codes = new ArrayList<>();
                Set<String> unique = new LinkedHashSet<>();
                while (unique.size() < BATCH_CODES) {
                    unique.add(code());
                }
                codes.addAll(unique);
                ShopifyApi.Answer<ShopifyApi.CreatedBatch> created = api.createBatch(domain, access,
                    title(campaign), percent, ctx.opTime(), endsAt, codes);
                if (created.refused()) {
                    ctx.put(ConnectionProcesses.DROPPED, true);
                    return;
                }
                List<String> sealed = new ArrayList<>();
                List<String> hints = new ArrayList<>();
                for (String code : codes) {
                    sealed.add(sealer.seal(code, domain));
                    hints.add(hint(code));
                }
                ctx.put(CREATED, new Created(created.value(), sealed, hints, percent, endsAt));
            }
        }

        /** The rows of the codes Shopify did not add, matched by opening the finished batches' codes. */
        private List<EntityInstance> failedRows(ProcessContext ctx, Map<Object, Set<String>> done, String domain) {
            List<EntityInstance> failed = new ArrayList<>();
            for (EntityInstance code : ShopProcesses.rows(ctx, CODES)) {
                Set<String> ofBatch = done.entrySet().stream()
                    .filter(entry -> String.valueOf(entry.getKey()).equals(code.get("batchId")))
                    .map(Map.Entry::getValue).findFirst().orElse(Set.of());
                if (ofBatch.isEmpty()) {
                    continue;
                }
                try {
                    if (ofBatch.contains(sealer.open(code.get("sealedCode"), domain))) {
                        failed.add(code);
                    }
                } catch (SealedSecrets.UnreadableSecretException e) {
                    // Sealed under another key: it can never be handed out, as if Shopify had not added it.
                    failed.add(code);
                }
            }
            return failed;
        }

        static String code() {
            StringBuilder code = new StringBuilder("QB-");
            for (int i = 0; i < 10; i++) {
                code.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
            }
            return code.toString();
        }
    }

    /** The discount's title in the shop's admin: the campaign's name, so the staff know where it comes from. */
    static String title(EntityInstance campaign) {
        String title = "QuizBuks: " + campaign.get("name");
        return title.length() > 255 ? title.substring(0, 255) : title;
    }

    /** What the winner and the staff see of a code before it is opened: its last four characters. */
    static String hint(String code) {
        return "…" + code.substring(code.length() - 4);
    }

    @SuppressWarnings("unchecked")
    static void keep(ProcessContext ctx) {
        Plan plan = ctx.get(PLAN, Plan.class);
        EntityInstance shop = ShopProcesses.first(ctx, SHOPS);
        if (shop != null && ShopProcesses.first(ctx, TOKENS) != null) {
            ConnectionProcesses.keepTokens(ctx);
        }
        Instant now = ctx.opTime();
        Map<Object, Set<String>> done = (Map<Object, Set<String>>) ctx.get(DONE);
        int finished = 0;
        if (done != null) {
            for (EntityInstance batch : plan.finish()) {
                if (done.containsKey(batch.id())) {
                    ctx.changes().update(QbsStorefront.BATCH, batch.id(), batch.version(),
                        Map.of("status", QbsStorefront.READY));
                    finished++;
                }
            }
            for (EntityInstance code : (List<EntityInstance>) ctx.get(FAILED_CODES)) {
                ctx.changes().update(QbsStorefront.CODE, code.id(), code.version(),
                    Map.of("status", QbsStorefront.FAILED));
            }
        }
        for (EntityInstance batch : plan.retire()) {
            ctx.changes().update(QbsStorefront.BATCH, batch.id(), batch.version(),
                Map.of("status", QbsStorefront.RETIRED));
        }
        // Deleted from the shop just now, or, when the shop cannot be called (uninstalled, not connected), here only:
        // the discount ended either way.
        List<Object> gone = (List<Object>) ctx.get(GONE);
        int deleted = 0;
        if (gone != null || !plan.useApi()) {
            for (EntityInstance batch : plan.delete()) {
                if (gone != null && !gone.contains(batch.id())) {
                    continue;
                }
                for (EntityInstance code : ShopProcesses.rows(ctx, CODES)) {
                    if (String.valueOf(batch.id()).equals(code.get("batchId"))) {
                        ctx.changes().delete(QbsStorefront.CODE, code.id(), code.version());
                    }
                }
                ctx.changes().update(QbsStorefront.BATCH, batch.id(), batch.version(),
                    Map.of("status", QbsStorefront.DELETED));
                deleted++;
            }
        }
        Created created = ctx.get(CREATED, Created.class);
        if (created != null) {
            Map<String, Object> batch = new LinkedHashMap<>();
            batch.put("shopDomain", domain(ctx));
            batch.put("campaignId", String.valueOf(plan.campaign().id()));
            batch.put("percent", BigDecimal.valueOf(created.percent()));
            batch.put("discountId", created.batch().discountId());
            batch.put("bulkCreationId", created.batch().bulkCreationId());
            batch.put("status", created.batch().bulkCreationId() == null ? QbsStorefront.READY
                : QbsStorefront.CREATING);
            batch.put("codeCount", BigDecimal.valueOf(created.sealed().size()));
            batch.put("createdAt", now);
            batch.put("endsAt", created.endsAt());
            Object batchId = ctx.changes().insert(QbsStorefront.BATCH, batch);
            for (int i = 0; i < created.sealed().size(); i++) {
                Map<String, Object> code = new LinkedHashMap<>();
                code.put("batchId", String.valueOf(batchId));
                code.put("shopDomain", domain(ctx));
                code.put("seq", BigDecimal.valueOf(i + 1));
                code.put("sealedCode", created.sealed().get(i));
                code.put("hint", created.hints().get(i));
                code.put("status", QbsStorefront.AVAILABLE);
                ctx.changes().insert(QbsStorefront.CODE, code);
            }
        }
        ctx.put(OUTPUT, new RefillOutput(domain(ctx), finished, plan.retire().size(), deleted, created != null));
    }
}
