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

    /** S2: the pass score is above the number of questions. */
    public static final String PASS_SCORE = "QBS_PASS_SCORE";
    /** S2: only a draft campaign can be edited or published. */
    public static final String CAMPAIGN_NOT_DRAFT = "QBS_CAMPAIGN_NOT_DRAFT";
    /** S2: the draft was changed since the editor read it. */
    public static final String CAMPAIGN_CHANGED = "QBS_CAMPAIGN_CHANGED";
    /** S2: another campaign is running (live or paused): end it first. */
    public static final String CAMPAIGN_RUNNING = "QBS_CAMPAIGN_RUNNING";
    /** S2: the campaign cannot make that move (resume a live one, end an ended one). */
    public static final String CAMPAIGN_MOVE = "QBS_CAMPAIGN_MOVE";
    /** S2: a visitor who is not signed in must send the key the storefront block keeps. */
    public static final String VISITOR_REQUIRED = "QBS_VISITOR_REQUIRED";
    /** S2: the discount code cannot be opened (sealed under another key). */
    public static final String CODE_UNREADABLE = "QBS_CODE_UNREADABLE";

    /** S3a: the shop's plan has no attempts left this period (the storefront shows nothing). */
    public static final String PLAN_LIMIT = "QBS_PLAN_LIMIT";

    public static final List<String> ALL = List.of(OTHER_SHOP, NOT_A_MEMBER, NOT_ADMIN, NOT_A_COLLEAGUE, UNINSTALLED,
        EXCHANGE_REFUSED, NOT_CONNECTED, SHOP_FULL, PASS_SCORE, CAMPAIGN_NOT_DRAFT, CAMPAIGN_CHANGED, CAMPAIGN_RUNNING,
        CAMPAIGN_MOVE, VISITOR_REQUIRED, CODE_UNREADABLE, PLAN_LIMIT);
}
