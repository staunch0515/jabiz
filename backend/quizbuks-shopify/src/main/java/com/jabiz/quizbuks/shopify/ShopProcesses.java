package com.jabiz.quizbuks.shopify;

import com.jabiz.entity.Violation;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.process.steps.HoldLock;
import com.jabiz.runtime.process.steps.QueryEntities;
import com.jabiz.runtime.security.VerifyExternalToken;
import com.jabiz.security.Sensitive;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A shop's staff joining it and handing on its admin role (docs/quizbuks/shopify/S1-install.md section 3).
 *
 * <p>{@value #JOIN} runs each time a staff member opens the embedded pages, after signing in with the session token:
 * it checks a fresh token of the operator (the shop and the staff member it names are the operator's identity), then,
 * under the shop's lock, opens the shop's row the first time, makes it active again after an uninstall (a session token
 * is only issued to an installed app, so it was installed again), and adds the operator as a member: the shop's admin
 * when it has none, otherwise a member. Running it again changes nothing. Its answer is what the embedded pages show
 * of the shop (the shop, its connection and its members).
 *
 * <p>{@value #ADMIN_TRANSFER}: the admin hands the role to another member of the shop, both rows in one go.
 */
public final class ShopProcesses {

    private ShopProcesses() {}

    public static final String JOIN = "QBS_SHOP_JOIN";
    public static final String ADMIN_TRANSFER = "QBS_SHOP_ADMIN_TRANSFER";

    static final int MAX_TOKEN_LENGTH = 4096;
    /**
     * The most members a shop has: every write of a shop (an uninstall, an erasure) stays within one batch of the
     * datasets, and Shopify's plans give far fewer staff accounts than this.
     */
    public static final int MAX_MEMBERS = 100;

    /** A fresh session token of the operator, from App Bridge. */
    public record JoinInput(@Sensitive @NotBlank @Size(max = MAX_TOKEN_LENGTH) String sessionToken) {
        @Override
        public String toString() {
            return "JoinInput[sessionToken=***]";
        }
    }

    /**
     * What the embedded pages show (no template reads the shop: a member's view of the shop is this answer).
     *
     * @param role        the operator's role
     * @param joined      whether the operator became a member just now
     * @param reinstalled whether the shop had uninstalled the app and is active again now
     * @param members     the shop's members, the admin first, then by joining
     */
    public record JoinOutput(String shopId, String shopDomain, String shopName, String connection, String role,
        boolean joined, boolean reinstalled, List<Member> members) {
        public JoinOutput {
            members = List.copyOf(members);
        }
    }

    /** A member as the shop's staff see them: the platform user, the Shopify staff id, the role. */
    public record Member(String userId, String staffId, String role, java.time.Instant joinedAt) {}

    public record TransferInput(@NotBlank @Size(max = QbsShops.USER_ID_LENGTH) String toUserId) {}

    public record TransferOutput(String shopId, String adminUserId) {}

    static final String INPUT = "input";
    static final String VERIFIED = "verified";
    static final String SHOPS = "shops";
    static final String MEMBERS = "members";
    static final String MINE = "mine";
    static final String TARGET = "target";
    static final String OUTPUT = "output";

    static ProcessDefinition<JoinInput, JoinOutput, ProcessContext> join() {
        return ProcessDefinition.define(JOIN, 1, JoinInput.class, JoinOutput.class, ProcessContext.class, pb -> pb
            .description("A staff member opens the app in Shopify: the shop is opened or active again, and the staff"
                + " member joins it (the first one as its admin).")
            .permissions(Shopify.USE)
            .contextFactory(ShopProcesses::context)
            .outputMapper(ctx -> ctx.get(OUTPUT, JoinOutput.class))
            .steps(VerifyExternalToken.of(Shopify.PROVIDER, ctx -> ctx.get(INPUT, JoinInput.class).sessionToken(),
                VERIFIED))
            .step("Lock the shop", HoldLock.<ProcessContext>exclusive(ctx -> Shopify.shopLock(verified(ctx).tenant())))
            .step("Load the shop and the operator's membership", QueryEntities.all(
                QueryEntities.<ProcessContext>query(QbsShops.defaultDataset(QbsShops.SHOP),
                    ctx -> eq("shopDomain", verified(ctx).tenant(), 1), SHOPS),
                QueryEntities.<ProcessContext>query(QbsShops.defaultDataset(QbsShops.MEMBER),
                    ctx -> eq("userId", ctx.request().actorId(), 1), MINE)))
            .step("Load the shop's members", QueryEntities.<ProcessContext>of(QbsShops.defaultDataset(QbsShops.MEMBER),
                ctx -> membersOf(first(ctx, SHOPS)), MEMBERS))
            .compute("Join the shop", (metadata, ctx) -> join(ctx)));
    }

    static void join(ProcessContext ctx) {
        if (ctx.hasViolations()) {
            return;
        }
        VerifyExternalToken.Verified token = verified(ctx);
        String domain = token.tenant();
        EntityInstance shop = first(ctx, SHOPS);
        EntityInstance mine = first(ctx, MINE);
        if (mine != null && !domain.equals(mine.get("shopDomain"))) {
            ctx.reject(new Violation("sessionToken", ShopifyCodes.OTHER_SHOP,
                "The operator is a member of another shop", Map.of()));
            return;
        }
        List<EntityInstance> members = rows(ctx, MEMBERS);
        Object shopId;
        boolean reinstalled = false;
        if (shop == null) {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("shopDomain", domain);
            values.put("status", QbsShops.ACTIVE);
            values.put("installedAt", ctx.opTime());
            values.put("connection", QbsShops.NONE);
            shopId = ctx.changes().insert(QbsShops.SHOP, values);
        } else {
            shopId = shop.id();
            if (QbsShops.UNINSTALLED.equals(shop.get("status"))) {
                // Only a token issued after the uninstall shows the app installed again: one App Bridge gave out
                // just before it (still valid for its minute) must not undo the uninstall.
                java.time.Instant issued = issuedAt(token.token());
                java.time.Instant uninstalled = shop.get("uninstalledAt");
                if (issued == null || uninstalled == null || !issued.isAfter(uninstalled)) {
                    ctx.reject(new Violation("sessionToken", ShopifyCodes.UNINSTALLED,
                        "The shop has uninstalled the app", Map.of()));
                    return;
                }
                reinstalled = true;
                Map<String, Object> values = new HashMap<>();
                values.put("status", QbsShops.ACTIVE);
                values.put("uninstalledAt", null);
                ctx.changes().update(QbsShops.SHOP, shop.id(), shop.version(), values);
                for (EntityInstance member : members) {
                    ctx.changes().update(QbsShops.MEMBER, member.id(), member.version(),
                        Map.of("shopStatus", QbsShops.ACTIVE));
                }
            }
        }
        List<Member> view = new java.util.ArrayList<>(members.stream()
            .map(m -> new Member(m.get("userId"), m.get("staffId"), m.get("role"), m.get("joinedAt"))).toList());
        String role;
        if (mine == null && members.size() >= MAX_MEMBERS) {
            ctx.reject(new Violation(null, ShopifyCodes.SHOP_FULL, "The shop has the most members it may have",
                Map.of("max", MAX_MEMBERS)));
            return;
        }
        if (mine == null) {
            role = members.stream().anyMatch(m -> QbsShops.ADMIN.equals(m.get("role"))) ? QbsShops.MEMBER_ROLE
                : QbsShops.ADMIN;
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("shopId", String.valueOf(shopId));
            values.put("shopDomain", domain);
            values.put("userId", ctx.request().actorId());
            values.put("staffId", token.subject());
            values.put("role", role);
            values.put("shopStatus", QbsShops.ACTIVE);
            values.put("joinedAt", ctx.opTime());
            ctx.changes().insert(QbsShops.MEMBER, values);
            view.add(new Member(ctx.request().actorId(), token.subject(), role, ctx.opTime()));
        } else {
            role = mine.get("role");
        }
        view.sort(java.util.Comparator.comparing((Member m) -> !QbsShops.ADMIN.equals(m.role()))
            .thenComparing(Member::joinedAt).thenComparing(Member::userId));
        ctx.put(OUTPUT, new JoinOutput(String.valueOf(shopId), domain, shop == null ? null : shop.get("shopName"),
            shop == null ? QbsShops.NONE : shop.get("connection"), role, mine == null, reinstalled, view));
    }

    static ProcessDefinition<TransferInput, TransferOutput, ProcessContext> adminTransfer() {
        return ProcessDefinition.define(ADMIN_TRANSFER, 1, TransferInput.class, TransferOutput.class,
            ProcessContext.class, pb -> pb
                .description("The shop's admin hands the admin role to another member of the shop.")
                .permissions(Shopify.USE)
                .contextFactory(ShopProcesses::context)
                .outputMapper(ctx -> ctx.get(OUTPUT, TransferOutput.class))
                .step("Find the operator's shop", QueryEntities.<ProcessContext>of(
                    QbsShops.defaultDataset(QbsShops.MEMBER), ctx -> eq("userId", ctx.request().actorId(), 1), MINE))
                .step("Lock the shop", HoldLock.<ProcessContext>exclusive(ShopProcesses::lockOfMine))
                .step("Load the shop and both members", QueryEntities.all(
                    QueryEntities.<ProcessContext>query(QbsShops.defaultDataset(QbsShops.MEMBER),
                        ctx -> eq("userId", ctx.request().actorId(), 1), MINE),
                    QueryEntities.<ProcessContext>query(QbsShops.defaultDataset(QbsShops.MEMBER),
                        ctx -> eq("userId", ctx.get(INPUT, TransferInput.class).toUserId(), 1), TARGET),
                    QueryEntities.<ProcessContext>query(QbsShops.defaultDataset(QbsShops.SHOP),
                        ctx -> eq("shopDomain", domainOfMine(ctx), 1), SHOPS)))
                .compute("Hand on the admin role", (metadata, ctx) -> transfer(ctx)));
    }

    static void transfer(ProcessContext ctx) {
        if (ctx.hasViolations()) {
            return;
        }
        EntityInstance mine = first(ctx, MINE);
        EntityInstance target = first(ctx, TARGET);
        EntityInstance shop = first(ctx, SHOPS);
        if (!requireAdminOfActiveShop(ctx, mine, shop)) {
            return;
        }
        if (target == null || target.id().equals(mine.id()) || !mine.get("shopId").equals(target.get("shopId"))) {
            ctx.reject(new Violation("toUserId", ShopifyCodes.NOT_A_COLLEAGUE,
                "The new admin must be another member of the shop", Map.of()));
            return;
        }
        // The old admin first: the index allows one ADMIN per shop at any moment.
        ctx.changes().update(QbsShops.MEMBER, mine.id(), mine.version(), Map.of("role", QbsShops.MEMBER_ROLE));
        ctx.changes().update(QbsShops.MEMBER, target.id(), target.version(), Map.of("role", QbsShops.ADMIN));
        ctx.put(OUTPUT, new TransferOutput(String.valueOf(shop.id()), target.get("userId")));
    }

    /**
     * Rejects (and says false) unless the operator is a member of a shop, the shop is active and the operator is its
     * admin.
     */
    static boolean requireAdminOfActiveShop(ProcessContext ctx, EntityInstance mine, EntityInstance shop) {
        if (!requireMemberOfActiveShop(ctx, mine, shop)) {
            return false;
        }
        if (!QbsShops.ADMIN.equals(mine.get("role"))) {
            ctx.reject(new Violation(null, ShopifyCodes.NOT_ADMIN, "Only the shop's admin may do this", Map.of()));
            return false;
        }
        return true;
    }

    /** Rejects (and says false) unless the operator is a member of a shop that is active. */
    static boolean requireMemberOfActiveShop(ProcessContext ctx, EntityInstance mine, EntityInstance shop) {
        if (mine == null || shop == null) {
            ctx.reject(new Violation(null, ShopifyCodes.NOT_A_MEMBER, "The operator has not joined a shop", Map.of()));
            return false;
        }
        if (!QbsShops.ACTIVE.equals(shop.get("status"))) {
            ctx.reject(new Violation(null, ShopifyCodes.UNINSTALLED, "The shop has uninstalled the app", Map.of()));
            return false;
        }
        return true;
    }

    static ProcessContext context(com.jabiz.process.ProcessStart start, Object input) {
        ProcessContext ctx = new ProcessContext(start);
        ctx.put(INPUT, input);
        return ctx;
    }

    static VerifyExternalToken.Verified verified(ProcessContext ctx) {
        return ctx.get(VERIFIED, VerifyExternalToken.Verified.class);
    }

    /** The lock of the operator's shop; none (null) when the operator is a member of none, which then fails. */
    static String lockOfMine(ProcessContext ctx) {
        String domain = domainOfMine(ctx);
        return domain == null ? null : Shopify.shopLock(domain);
    }

    static String domainOfMine(ProcessContext ctx) {
        EntityInstance mine = first(ctx, MINE);
        return mine == null ? null : mine.get("shopDomain");
    }

    static EntityQuery eq(String field, Object value, int limit) {
        QueryPredicate where = value == null ? new QueryPredicate.In(field, List.of())
            : new QueryPredicate.Eq(field, value);
        return EntityQuery.builder().where(where).limit(limit).build();
    }

    static EntityQuery membersOf(EntityInstance shop) {
        return eq("shopId", shop == null ? null : String.valueOf(shop.id()), MAX_MEMBERS);
    }

    @SuppressWarnings("unchecked")
    static List<EntityInstance> rows(ProcessContext ctx, String key) {
        List<EntityInstance> rows = (List<EntityInstance>) ctx.get(key);
        return rows == null ? List.of() : rows;
    }

    static EntityInstance first(ProcessContext ctx, String key) {
        List<EntityInstance> rows = rows(ctx, key);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    /**
     * When the token (verified already) was issued, by its {@code iat}; null when it has none. Read from the token
     * itself, which the platform has verified: the time is the issuer's word, as the token's other claims.
     */
    static java.time.Instant issuedAt(String token) {
        try {
            String payload = token.split("\\.")[1];
            tools.jackson.databind.JsonNode iat = CLAIMS.readTree(java.util.Base64.getUrlDecoder().decode(payload))
                .path("iat");
            return iat.canConvertToExactIntegral() && iat.asLong() > 0
                ? java.time.Instant.ofEpochSecond(iat.asLong()) : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static final tools.jackson.databind.json.JsonMapper CLAIMS =
        tools.jackson.databind.json.JsonMapper.builder().build();
}
