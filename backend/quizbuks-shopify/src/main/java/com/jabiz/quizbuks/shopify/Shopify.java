package com.jabiz.quizbuks.shopify;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The names QuizBuks for Shopify shares between its parts (docs/quizbuks/shopify/S1-install.md): the platform's
 * external token provider, sign-in entry and inbound webhook provider it is configured as, its permissions, roles and
 * locks, and the form of a shop's address.
 */
public final class Shopify {

    private Shopify() {}

    /** The external token provider ({@code jabiz.security.external-tokens.shopify}) and the external service. */
    public static final String PROVIDER = "shopify";
    /** The sign-in entry of the embedded pages. */
    public static final String ENTRY = "shopify";
    /** The inbound webhook provider ({@code /api/inbound/shopify}). */
    public static final String WEBHOOKS = "shopify";
    /** The metadata header naming the shop of a webhook (not signed: checked against the signed body). */
    public static final String SHOP_HEADER = "X-Shopify-Shop-Domain";

    /** The role of every staff member of a shop (entry {@value #ENTRY}). */
    public static final String ROLE = "QBS_SHOP";
    /** What the staff of a shop may do in the embedded pages. */
    public static final String USE = "qbs.shop.use";
    /** The webhooks' processes and the platform-only datasets: granted to no role. */
    public static final String SYSTEM = "qbs.system";
    /** Every permission of the module, for the setup's role list. */
    public static final List<String> PERMISSIONS = List.of(USE, SYSTEM);

    /** A shop's own address: the external token's tenant ({@code demo.myshopify.com}). */
    public static final Pattern SHOP_DOMAIN = Pattern.compile("[a-z0-9][a-z0-9-]*\\.myshopify\\.com");
    public static final int SHOP_DOMAIN_LENGTH = 255;

    /** Whether {@code value} is a shop's address, lower case. */
    public static boolean isShopDomain(String value) {
        return value != null && value.length() <= SHOP_DOMAIN_LENGTH && SHOP_DOMAIN.matcher(value).matches();
    }

    /** {@code value} in lower case, as shops' addresses are compared; null stays null. */
    public static String lower(String value) {
        return value == null ? null : value.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * The lock of a shop (its row, its members and the admin role); always taken before {@link #tokenLock}. Named by
     * the address, which exists before the shop's row does.
     */
    public static String shopLock(String shopDomain) {
        return "qbs.shop:" + shopDomain;
    }

    /**
     * The lock of a shop's access tokens: only its holder exchanges or refreshes them, since a refresh makes the
     * previous refresh token useless.
     */
    public static String tokenLock(String shopDomain) {
        return "qbs.shop-token:" + shopDomain;
    }
}
