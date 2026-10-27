package com.jabiz.security;

import com.jabiz.context.RequestContext;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The signature of app proxy requests and their caller (decision D60). */
class AppProxySignatureTest {

    static final Duration AGE = Duration.ofMinutes(5);

    /**
     * A query as Shopify forwards one, with the secret "hush", and its signature as Shopify's own library computes it
     * (@shopify/shopify-api 15.0.1, {@code generateLocalHmac(...)(query, 'appProxy')}: the same steps run under Node).
     */
    static Map<String, List<String>> shopifyExample() {
        Map<String, List<String>> query = new LinkedHashMap<>();
        query.put("extra", List.of("1", "2"));
        query.put("shop", List.of("shop-name.myshopify.com"));
        query.put("logged_in_customer_id", List.of("1"));
        query.put("path_prefix", List.of("/apps/awesome_reviews"));
        query.put("timestamp", List.of("1317327555"));
        query.put("signature", List.of("4c68c8624d737112c91818c11017d24d334b524cb5c2b8ba08daa056f7395ddb"));
        return query;
    }

    @Test
    void aQueryIsSignedAsShopifySignsIt() {
        Map<String, List<String>> query = shopifyExample();
        assertThat(AppProxySignature.message(query)).isEqualTo("extra=1,2logged_in_customer_id=1"
            + "path_prefix=/apps/awesome_reviewsshop=shop-name.myshopify.comtimestamp=1317327555");
        assertThat(AppProxySignature.sign(query, "hush")).isEqualTo(query.get("signature").getFirst());
        Instant signedAt = Instant.ofEpochSecond(1317327555);
        assertThat(AppProxySignature.verify(query, List.of("another-secret", "hush"), signedAt.plusSeconds(60), AGE))
            .isEqualTo(AppProxySignature.Result.VALID);
    }

    @Test
    void everyChangeOrAgeFails() {
        Instant signedAt = Instant.ofEpochSecond(1317327555);
        Map<String, List<String>> changed = shopifyExample();
        changed.put("shop", List.of("other.myshopify.com"));
        assertThat(AppProxySignature.verify(changed, List.of("hush"), signedAt, AGE))
            .isEqualTo(AppProxySignature.Result.NO_MATCH);
        Map<String, List<String>> added = shopifyExample();
        added.put("visitor", List.of("v1"));
        assertThat(AppProxySignature.verify(added, List.of("hush"), signedAt, AGE))
            .isEqualTo(AppProxySignature.Result.NO_MATCH);
        assertThat(AppProxySignature.verify(shopifyExample(), List.of("wrong"), signedAt, AGE))
            .isEqualTo(AppProxySignature.Result.NO_MATCH);
        assertThat(AppProxySignature.verify(shopifyExample(), List.of("hush"), signedAt.plus(AGE).plusSeconds(1), AGE))
            .isEqualTo(AppProxySignature.Result.STALE);
        assertThat(AppProxySignature.verify(shopifyExample(), List.of("hush"), signedAt.minus(AGE).minusSeconds(1),
            AGE)).isEqualTo(AppProxySignature.Result.STALE);

        Map<String, List<String>> none = shopifyExample();
        none.remove("signature");
        assertThat(AppProxySignature.verify(none, List.of("hush"), signedAt, AGE))
            .isEqualTo(AppProxySignature.Result.MISSING);
        Map<String, List<String>> twice = shopifyExample();
        twice.put("signature", List.of("a".repeat(64), "b".repeat(64)));
        assertThat(AppProxySignature.verify(twice, List.of("hush"), signedAt, AGE))
            .isEqualTo(AppProxySignature.Result.MALFORMED);
        Map<String, List<String>> upper = shopifyExample();
        upper.put("signature", List.of(upper.get("signature").getFirst().toUpperCase(Locale.ROOT)));
        assertThat(AppProxySignature.verify(upper, List.of("hush"), signedAt, AGE))
            .isEqualTo(AppProxySignature.Result.MALFORMED);
        Map<String, List<String>> badTime = shopifyExample();
        badTime.put("timestamp", List.of("-1"));
        assertThat(AppProxySignature.verify(badTime, List.of("hush"), signedAt, AGE))
            .isEqualTo(AppProxySignature.Result.MALFORMED);
    }

    @Test
    void theCallerIsReadFromTheRequestOnlyWithItsProxysPermission() {
        AppProxyCaller visitor = new AppProxyCaller("shop", "a.myshopify.com", null);
        AppProxyCaller customer = new AppProxyCaller("shop", "a.myshopify.com", "42");
        assertThat(visitor.actorId()).isEqualTo("app-proxy:shop");
        assertThat(customer.actorId()).isEqualTo("app-proxy:shop:42");
        assertThat(AppProxyCaller.permission("shop")).isEqualTo("app-proxy.shop");
        assertThat(AppProxyCaller.proxyOf("app-proxy.shop")).contains("shop");
        assertThat(AppProxyCaller.proxyOf("qb.me")).isEmpty();
        assertThatThrownBy(() -> AppProxyCaller.permission("Bad Name")).isInstanceOf(IllegalArgumentException.class);

        assertThat(AppProxyCaller.of(context(customer.actorId(), "a.myshopify.com", "app-proxy.shop")))
            .contains(customer);
        assertThat(AppProxyCaller.of(context(visitor.actorId(), "a.myshopify.com", "app-proxy.shop"))).contains(visitor);
        // Without the proxy's permission, without a tenant, or any other actor: not a proxy's caller.
        assertThat(AppProxyCaller.of(context(visitor.actorId(), "a.myshopify.com", "app-proxy.other"))).isEmpty();
        assertThat(AppProxyCaller.of(context(visitor.actorId(), null, "app-proxy.shop"))).isEmpty();
        assertThat(AppProxyCaller.of(context("user-1", "a.myshopify.com", "app-proxy.shop"))).isEmpty();
        assertThat(AppProxyCaller.of(context("app-proxy:shop:bad id", "a.myshopify.com", "app-proxy.shop"))).isEmpty();
        // The longest actor id fits the actor columns.
        assertThat(new AppProxyCaller("a".repeat(30), "t", "1".repeat(40)).actorId()).hasSizeLessThanOrEqualTo(100);
        assertThat(AppProxyCaller.of(null)).isEmpty();
    }

    static RequestContext context(String actor, String tenant, String permission) {
        return new RequestContext(actor, tenant, Locale.ENGLISH, "r", Set.of(), Set.of(permission));
    }
}
