package com.jabiz.quizbuks.shopify;

import com.jabiz.entity.Violation;
import com.jabiz.process.BlockingStep;
import com.jabiz.process.NoMetadata;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.process.steps.HoldLock;
import com.jabiz.runtime.process.steps.QueryEntities;
import com.jabiz.runtime.security.VerifyExternalToken;
import com.jabiz.runtime.security.secret.SealSecret;
import com.jabiz.runtime.security.secret.SecretSealer;
import com.jabiz.security.SealedSecrets;
import com.jabiz.security.Sensitive;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A shop's Shopify access tokens, obtained when first needed (docs/quizbuks/shopify/S1-install.md section 4).
 *
 * <p>{@value #CONNECT}: a member hands over a fresh session token; it is exchanged for the shop's expiring offline token
 * pair, which is sealed (for the shop's address) and kept, and the shop's name is read with it.
 *
 * <p>{@value #CHECK}: a member checks the connection: the access token is refreshed when it ends within
 * {@link #REFRESH_BEFORE} (a refresh makes the old pair useless, so only under the shop's token lock), then the shop's
 * name is read. When Shopify no longer takes the tokens, they are dropped and the shop must connect again
 * ({@code RECONNECT}); that is saved, not refused.
 *
 * <p>Both call Shopify inside the transaction, under the lock: a second or two, and only when connecting or refreshing.
 * A pair obtained by a transaction that then fails is simply obtained again by the next one.
 */
public final class ConnectionProcesses {

    private ConnectionProcesses() {}

    public static final String CONNECT = "QBS_SHOP_CONNECT";
    public static final String CHECK = "QBS_SHOP_CHECK";

    /** How long before its end an access token is refreshed. */
    public static final Duration REFRESH_BEFORE = Duration.ofMinutes(5);

    public record ConnectInput(@Sensitive @NotBlank @Size(max = ShopProcesses.MAX_TOKEN_LENGTH) String sessionToken) {
        @Override
        public String toString() {
            return "ConnectInput[sessionToken=***]";
        }
    }

    public record CheckInput() {}

    /** The shop's connection now ({@code CONNECTED} or {@code RECONNECT}) and its name. */
    public record ConnectionOutput(String connection, String shopName) {}

    static final String SHOPS = ShopProcesses.SHOPS;
    static final String MINE = ShopProcesses.MINE;
    static final String TOKENS = "tokens";
    static final String OBTAINED = "obtained";
    static final String NAME = "shopName";
    static final String DROPPED = "dropped";
    static final String SEALED_ACCESS = "sealedAccess";
    static final String SEALED_REFRESH = "sealedRefresh";
    static final String OUTPUT = ShopProcesses.OUTPUT;

    static ProcessDefinition<ConnectInput, ConnectionOutput, ProcessContext> connect() {
        return ProcessDefinition.define(CONNECT, 1, ConnectInput.class, ConnectionOutput.class, ProcessContext.class,
            pb -> pb
                .description("A member connects the shop: the session token is exchanged for the shop's access"
                    + " tokens, which are kept sealed.")
                .permissions(Shopify.USE)
                .contextFactory(ShopProcesses::context)
                .outputMapper(ctx -> ctx.get(OUTPUT, ConnectionOutput.class))
                .steps(VerifyExternalToken.of(Shopify.PROVIDER,
                    ctx -> ctx.get(ShopProcesses.INPUT, ConnectInput.class).sessionToken(), ShopProcesses.VERIFIED))
                .step("Lock the shop", HoldLock.<ProcessContext>shared(
                    ctx -> Shopify.shopLock(ShopProcesses.verified(ctx).tenant())))
                .step("Lock the shop's tokens", HoldLock.<ProcessContext>exclusive(
                    ctx -> Shopify.tokenLock(ShopProcesses.verified(ctx).tenant())))
                .step("Load the shop and the operator's membership", QueryEntities.all(
                    QueryEntities.<ProcessContext>query(QbsShops.defaultDataset(QbsShops.SHOP),
                        ctx -> ShopProcesses.eq("shopDomain", ShopProcesses.verified(ctx).tenant(), 1), SHOPS),
                    QueryEntities.<ProcessContext>query(QbsShops.defaultDataset(QbsShops.MEMBER),
                        ctx -> ShopProcesses.eq("userId", ctx.request().actorId(), 1), MINE)))
                .step("Load the shop's tokens", QueryEntities.<ProcessContext>of(
                    QbsShops.defaultDataset(QbsShops.TOKEN), ConnectionProcesses::tokensOfShop, TOKENS))
                .compute("Check the operator", (metadata, ctx) -> checkConnecting(ctx))
                .step("Exchange the session token", ExchangeToken.class, NoMetadata.INSTANCE)
                .step("Seal the access token", sealAccess())
                .step("Seal the refresh token", sealRefresh())
                .compute("Keep the tokens", (metadata, ctx) -> keep(ctx)));
    }

    static void checkConnecting(ProcessContext ctx) {
        EntityInstance mine = ShopProcesses.first(ctx, MINE);
        EntityInstance shop = ShopProcesses.first(ctx, SHOPS);
        if (!ShopProcesses.requireMemberOfActiveShop(ctx, mine, shop)) {
            return;
        }
        if (!shop.id().toString().equals(mine.get("shopId"))) {
            ctx.reject(new Violation("sessionToken", ShopifyCodes.OTHER_SHOP,
                "The operator is a member of another shop", Map.of()));
        }
    }

    static ProcessDefinition<CheckInput, ConnectionOutput, ProcessContext> check() {
        return ProcessDefinition.define(CHECK, 1, CheckInput.class, ConnectionOutput.class, ProcessContext.class,
            pb -> pb
                .description("A member checks the shop's connection: the access token is refreshed when it is about"
                    + " to end, and the shop's name is read.")
                .permissions(Shopify.USE)
                .contextFactory(ShopProcesses::context)
                .outputMapper(ctx -> ctx.get(OUTPUT, ConnectionOutput.class))
                .step("Find the operator's shop", QueryEntities.<ProcessContext>of(
                    QbsShops.defaultDataset(QbsShops.MEMBER),
                    ctx -> ShopProcesses.eq("userId", ctx.request().actorId(), 1), MINE))
                .step("Lock the shop", HoldLock.<ProcessContext>shared(ShopProcesses::lockOfMine))
                .step("Lock the shop's tokens", HoldLock.<ProcessContext>exclusive(ctx -> {
                    String domain = ShopProcesses.domainOfMine(ctx);
                    return domain == null ? null : Shopify.tokenLock(domain);
                }))
                .step("Load the shop", QueryEntities.<ProcessContext>of(QbsShops.defaultDataset(QbsShops.SHOP),
                    ctx -> ShopProcesses.eq("shopDomain", ShopProcesses.domainOfMine(ctx), 1), SHOPS))
                .step("Load the shop's tokens", QueryEntities.<ProcessContext>of(
                    QbsShops.defaultDataset(QbsShops.TOKEN), ConnectionProcesses::tokensOfShop, TOKENS))
                .compute("Check the operator and the tokens", (metadata, ctx) -> checkChecking(ctx))
                .step("Refresh and read the shop", RefreshAndRead.class, NoMetadata.INSTANCE)
                .step("Seal the access token", sealAccess())
                .step("Seal the refresh token", sealRefresh())
                .compute("Keep the outcome", (metadata, ctx) -> keep(ctx)));
    }

    static void checkChecking(ProcessContext ctx) {
        EntityInstance mine = ShopProcesses.first(ctx, MINE);
        EntityInstance shop = ShopProcesses.first(ctx, SHOPS);
        if (!ShopProcesses.requireMemberOfActiveShop(ctx, mine, shop)) {
            return;
        }
        if (ShopProcesses.first(ctx, TOKENS) == null) {
            ctx.reject(new Violation(null, ShopifyCodes.NOT_CONNECTED, "The shop is not connected", Map.of()));
        }
    }

    static com.jabiz.query.EntityQuery tokensOfShop(ProcessContext ctx) {
        EntityInstance shop = ShopProcesses.first(ctx, SHOPS);
        return ShopProcesses.eq("shopId", shop == null ? null : String.valueOf(shop.id()), 1);
    }

    static com.jabiz.process.StepSpec<SealSecret.Metadata<ProcessContext>, ProcessContext> sealAccess() {
        return SealSecret.of(ctx -> obtained(ctx) == null ? null : obtained(ctx).accessToken(),
            ConnectionProcesses::shopDomain, SEALED_ACCESS);
    }

    static com.jabiz.process.StepSpec<SealSecret.Metadata<ProcessContext>, ProcessContext> sealRefresh() {
        return SealSecret.of(ctx -> obtained(ctx) == null ? null : obtained(ctx).refreshToken(),
            ConnectionProcesses::shopDomain, SEALED_REFRESH);
    }

    /** The sealing context of a shop's tokens: its address (unknown when the checks failed: nothing is sealed). */
    static String shopDomain(ProcessContext ctx) {
        EntityInstance shop = ShopProcesses.first(ctx, SHOPS);
        return shop == null ? "-" : shop.get("shopDomain");
    }

    static ShopifyApi.Tokens obtained(ProcessContext ctx) {
        return ctx.hasViolations() ? null : ctx.get(OBTAINED, ShopifyApi.Tokens.class);
    }

    /** Saves what the call brought: a new pair, the shop's name, or that the tokens are gone. */
    static void keep(ProcessContext ctx) {
        if (ctx.hasViolations()) {
            return;
        }
        EntityInstance shop = ShopProcesses.first(ctx, SHOPS);
        EntityInstance stored = ShopProcesses.first(ctx, TOKENS);
        if (Boolean.TRUE.equals(ctx.get(DROPPED))) {
            if (stored != null) {
                ctx.changes().delete(QbsShops.TOKEN, stored.id(), stored.version());
            }
            ctx.changes().update(QbsShops.SHOP, shop.id(), shop.version(), Map.of("connection", QbsShops.RECONNECT));
            ctx.put(OUTPUT, new ConnectionOutput(QbsShops.RECONNECT, shop.get("shopName")));
            return;
        }
        ShopifyApi.Tokens tokens = ctx.get(OBTAINED, ShopifyApi.Tokens.class);
        if (tokens != null) {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("sealedAccessToken", ctx.get(SEALED_ACCESS, String.class));
            values.put("accessExpiresAt", tokens.accessExpiresAt());
            values.put("sealedRefreshToken", ctx.get(SEALED_REFRESH, String.class));
            values.put("refreshExpiresAt", tokens.refreshExpiresAt());
            values.put("scope", tokens.scope());
            values.put("obtainedAt", ctx.opTime());
            if (stored == null) {
                values.put("shopId", String.valueOf(shop.id()));
                ctx.changes().insert(QbsShops.TOKEN, values);
            } else {
                ctx.changes().update(QbsShops.TOKEN, stored.id(), stored.version(), values);
            }
        }
        String name = ctx.get(NAME, String.class);
        Map<String, Object> shopValues = new LinkedHashMap<>();
        shopValues.put("connection", QbsShops.CONNECTED);
        if (name != null) {
            shopValues.put("shopName", name.length() > QbsShops.NAME_LENGTH ? name.substring(0, QbsShops.NAME_LENGTH)
                : name);
        }
        ctx.changes().update(QbsShops.SHOP, shop.id(), shop.version(), shopValues);
        ctx.put(OUTPUT, new ConnectionOutput(QbsShops.CONNECTED, name));
    }

    /** Exchanges the operator's session token for the shop's token pair and reads the shop's name with it. */
    @Component
    public static class ExchangeToken implements BlockingStep<NoMetadata, ProcessContext> {

        private final ShopifyApi api;

        public ExchangeToken(ShopifyApi api) {
            this.api = api;
        }

        @Override
        public void run(NoMetadata metadata, ProcessContext ctx) {
            if (ctx.hasViolations()) {
                return;
            }
            VerifyExternalToken.Verified token = ShopProcesses.verified(ctx);
            ShopifyApi.Answer<ShopifyApi.Tokens> pair = api.exchange(token.tenant(), token.token(), ctx.opTime());
            if (pair.refused()) {
                ctx.reject(new Violation("sessionToken", ShopifyCodes.EXCHANGE_REFUSED,
                    "Shopify refused to give the shop an access token", Map.of("status", pair.refusedStatus())));
                return;
            }
            ShopifyApi.Answer<String> name = api.shopName(token.tenant(), pair.value().accessToken());
            if (name.refused()) {
                ctx.reject(new Violation("sessionToken", ShopifyCodes.EXCHANGE_REFUSED,
                    "Shopify refused the access token it just gave", Map.of("status", name.refusedStatus())));
                return;
            }
            ctx.put(OBTAINED, pair.value());
            ctx.put(NAME, name.value());
        }
    }

    /**
     * Opens the shop's tokens, refreshes them when the access token is about to end, and reads the shop's name. A
     * refresh or a read Shopify refuses drops the tokens ({@link #DROPPED}): the shop connects again.
     */
    @Component
    public static class RefreshAndRead implements BlockingStep<NoMetadata, ProcessContext> {

        private final ShopifyApi api;
        private final SecretSealer sealer;

        public RefreshAndRead(ShopifyApi api, SecretSealer sealer) {
            this.api = api;
            this.sealer = sealer;
        }

        @Override
        public void run(NoMetadata metadata, ProcessContext ctx) {
            if (ctx.hasViolations()) {
                return;
            }
            String domain = shopDomain(ctx);
            EntityInstance stored = ShopProcesses.first(ctx, TOKENS);
            String access;
            String refresh;
            try {
                access = sealer.open(stored.get("sealedAccessToken"), domain);
                refresh = sealer.open(stored.get("sealedRefreshToken"), domain);
            } catch (SealedSecrets.UnreadableSecretException e) {
                // Sealed under another key: useless, so the shop connects again.
                ctx.put(DROPPED, true);
                return;
            }
            java.time.Instant accessEnds = stored.get("accessExpiresAt");
            if (!ctx.opTime().plus(REFRESH_BEFORE).isBefore(accessEnds)) {
                ShopifyApi.Answer<ShopifyApi.Tokens> pair = api.refresh(domain, refresh, ctx.opTime());
                if (pair.refused()) {
                    ctx.put(DROPPED, true);
                    return;
                }
                ctx.put(OBTAINED, pair.value());
                access = pair.value().accessToken();
            }
            ShopifyApi.Answer<String> name = api.shopName(domain, access);
            if (name.refused()) {
                ctx.put(DROPPED, true);
                ctx.put(OBTAINED, null);
                return;
            }
            ctx.put(NAME, name.value());
        }
    }
}
