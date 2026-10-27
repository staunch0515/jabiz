package com.jabiz.quizbuks.shopify;

import java.util.List;

/** The rule codes of QuizBuks for Shopify; their texts are in {@code jabiz/quizbuks/shopify/messages_*.properties}. */
public final class ShopifyCodes {

    private ShopifyCodes() {}

    /** The operator is a member of another shop than the token's (one user, one shop). */
    public static final String OTHER_SHOP = "QBS_OTHER_SHOP";
    /** The operator has not joined a shop (open the app in Shopify first). */
    public static final String NOT_A_MEMBER = "QBS_NOT_A_MEMBER";
    /** Only the shop's admin may do this. */
    public static final String NOT_ADMIN = "QBS_NOT_ADMIN";
    /** The new admin must be another member of the same shop. */
    public static final String NOT_A_COLLEAGUE = "QBS_NOT_A_COLLEAGUE";
    /** The shop has uninstalled the app. */
    public static final String UNINSTALLED = "QBS_SHOP_UNINSTALLED";
    /** Shopify refused to give the shop an access token. */
    public static final String EXCHANGE_REFUSED = "QBS_EXCHANGE_REFUSED";
    /** The shop has no access token: connect first. */
    public static final String NOT_CONNECTED = "QBS_NOT_CONNECTED";
    /** The shop has as many members as it may ({@code ShopProcesses.MAX_MEMBERS}). */
    public static final String SHOP_FULL = "QBS_SHOP_FULL";

    public static final List<String> ALL = List.of(OTHER_SHOP, NOT_A_MEMBER, NOT_ADMIN, NOT_A_COLLEAGUE, UNINSTALLED,
        EXCHANGE_REFUSED, NOT_CONNECTED, SHOP_FULL);
}
