package com.jabiz.quizbuks.shopify;

import com.jabiz.runtime.check.CheckProblem;
import com.jabiz.runtime.check.PlatformCheck;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Which of Shopify's plans is which of ours (docs/quizbuks/shopify/S3-billing.md section 2), and where Shopify shows a
 * shop the plans. The plans are set up in Shopify's Partner Dashboard (Shopify App Pricing) under the names
 * {@code quizbuks.shopify.plans.<plan>.name} gives ({@code Starter}, {@code Growth}, {@code Pro} unless configured;
 * compared without regard to case); a free plan makes no subscription. Shopify's plan page is
 * {@code https://admin.shopify.com/store/<store>/charges/<app handle>/pricing_plans}, the handle being
 * {@code quizbuks.shopify.app-handle}. A blank or repeated name, or a handle that is none, fails the startup (category
 * {@value #CATEGORY}, at startup and in {@code platformCheck}).
 */
@Component
public class ShopifyPlans implements PlatformCheck {

    static final String CATEGORY = "QUIZBUKS";
    private static final Pattern HANDLE = Pattern.compile("[a-z0-9][a-z0-9-]*");

    private final Map<String, String> byName = new LinkedHashMap<>();
    private final List<CheckProblem> problems = new ArrayList<>();
    private final String appHandle;

    public ShopifyPlans(@Value("${quizbuks.shopify.plans.starter.name:Starter}") String starter,
        @Value("${quizbuks.shopify.plans.growth.name:Growth}") String growth,
        @Value("${quizbuks.shopify.plans.pro.name:Pro}") String pro,
        @Value("${quizbuks.shopify.app-handle:quizbuks}") String appHandle) {
        put(starter, QbsBilling.STARTER);
        put(growth, QbsBilling.GROWTH);
        put(pro, QbsBilling.PRO);
        if (appHandle == null || !HANDLE.matcher(appHandle).matches()) {
            problems.add(CheckProblem.error(CATEGORY, "quizbuks.shopify.app-handle", "'" + appHandle
                + "' is not a Shopify app's handle (lower-case letters, digits and -)"));
        }
        this.appHandle = appHandle;
    }

    private void put(String name, String plan) {
        String setting = "quizbuks.shopify.plans." + plan.toLowerCase(Locale.ROOT) + ".name";
        String key = key(name);
        if (key.isEmpty()) {
            problems.add(CheckProblem.error(CATEGORY, setting, "the name of the plan in Shopify is blank"));
        } else if (byName.putIfAbsent(key, plan) != null) {
            problems.add(CheckProblem.error(CATEGORY, setting, "'" + name + "' is the name of another plan too:"
                + " Shopify's plans must have names of their own"));
        }
    }

    private static String key(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }

    @Override
    public List<CheckProblem> check() {
        return List.copyOf(problems);
    }

    /** Our plan of a Shopify plan name; empty for a name that is none of ours. */
    public Optional<String> planOf(String shopifyName) {
        return Optional.ofNullable(byName.get(key(shopifyName)));
    }

    /** Shopify's page of the app's plans for a shop (opened at the top of the admin, not in the embedded frame). */
    public String pricingUrl(String shopDomain) {
        if (!Shopify.isShopDomain(shopDomain)) {
            throw new IllegalArgumentException("Not a shop's address: " + shopDomain);
        }
        String store = shopDomain.substring(0, shopDomain.length() - ".myshopify.com".length());
        return "https://admin.shopify.com/store/" + store + "/charges/" + appHandle + "/pricing_plans";
    }
}
