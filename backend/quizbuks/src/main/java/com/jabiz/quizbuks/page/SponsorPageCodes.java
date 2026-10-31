package com.jabiz.quizbuks.page;

import java.util.List;

/** The error codes of the sponsors' pages (Q60a); each has a message in every language. */
public final class SponsorPageCodes {

    public static final String HANDLE_FORMAT = "QB_SPONSOR_PAGE_HANDLE_FORMAT";
    public static final String HANDLE_TAKEN = "QB_SPONSOR_PAGE_HANDLE_TAKEN";
    public static final String HEADLINE_BLANK = "QB_SPONSOR_PAGE_HEADLINE_BLANK";
    public static final String WEBSITE_FORMAT = "QB_SPONSOR_PAGE_WEBSITE_FORMAT";
    /** The page changed since the caller read it (another tab, another member). */
    public static final String CHANGED = "QB_SPONSOR_PAGE_CHANGED";
    /** Only a sponsor whose onboarding is approved publishes a page. */
    public static final String SPONSOR_NOT_APPROVED = "QB_SPONSOR_PAGE_SPONSOR_NOT_APPROVED";
    /** The page is not in a state that allows the act (submitting a published page, taking down a draft). */
    public static final String STATE = "QB_SPONSOR_PAGE_STATE";
    /** A page taken down goes back only after a person's review, which no rule asks for. */
    public static final String REVIEW_REQUIRED = "QB_SPONSOR_PAGE_REVIEW_REQUIRED";

    // Q60b: products.
    public static final String PRODUCT_NAME_BLANK = "QB_PRODUCT_NAME_BLANK";
    /** A sponsor has at most {@value QbSponsorProducts#MAX_PRODUCTS} products. */
    public static final String PRODUCT_LIMIT = "QB_PRODUCT_LIMIT";
    /** The price has more decimals than its currency (none for the yen, two for the others), or is negative. */
    public static final String PRODUCT_PRICE = "QB_PRODUCT_PRICE";
    public static final String PRODUCT_CHANGED = "QB_PRODUCT_CHANGED";
    /** The product is not in a state that allows the act (taking down one taken down, restoring a listed one). */
    public static final String PRODUCT_STATE = "QB_PRODUCT_STATE";
    /** Too many products for the quiz, a question number twice or out of range, or a product not one's own. */
    public static final String QUIZ_PRODUCTS = "QB_QUIZ_PRODUCTS";

    public static final List<String> ALL = List.of(HANDLE_FORMAT, HANDLE_TAKEN, HEADLINE_BLANK, WEBSITE_FORMAT,
        CHANGED, SPONSOR_NOT_APPROVED, STATE, REVIEW_REQUIRED, PRODUCT_NAME_BLANK, PRODUCT_LIMIT, PRODUCT_PRICE,
        PRODUCT_CHANGED, PRODUCT_STATE, QUIZ_PRODUCTS);

    private SponsorPageCodes() {
    }
}
