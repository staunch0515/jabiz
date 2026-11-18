package com.jabiz.quizbuks.sponsorpackage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.jabiz.quizbuks.sponsorpackage.PackageJson.isInteger;
import static com.jabiz.quizbuks.sponsorpackage.PackageJson.isObject;
import static com.jabiz.quizbuks.sponsorpackage.PackageJson.object;

/**
 * A publication's settings file (Q102, {@code quizbuks.publication-settings}) as the sponsor page reads it: the Java of
 * {@code publishing/settings.ts}'s {@code parseSettings} (F-LAUNCH Q1). Only the shapes are checked; values of the
 * wrong kind are left out rather than guessed, as the draft is saved through the same process as one typed by hand.
 * The result is the settings as a JSON object with the page's keys.
 */
public final class PublicationSettingsFile {

    public static final String FORMAT = "quizbuks.publication-settings";
    public static final int VERSION = 1;

    /** The page's lists, in its order ({@code publishing/api.ts}, {@code qb/api.ts}). */
    static final List<String> REWARD_METHODS = List.of("PLATFORM", "SPONSOR_CASH", "OFFLINE_REDEEM", "DIGITAL_PRIZE");
    static final List<String> DIGITAL_DELIVERIES = List.of("POOL", "SHARED", "SPONSOR_SENT");
    static final List<String> AUDIENCES = List.of("PUBLIC", "INVITED");
    static final List<String> REGIONS = List.of("GLOBAL", "JP", "CN", "US", "EUROPE", "ASIA", "NORTH_AMERICA",
        "SOUTH_AMERICA");
    static final List<String> REFERRER_REQUIREMENTS = List.of("NONE", "ANSWERED", "PASSED");
    static final List<String> REFEREE_RULES = List.of("NOT_REGULAR", "NEW_TO_PLATFORM", "MATCHES_AUDIENCE",
        "SAME_REGION_AS_REFERRER");
    static final List<String> CONTENT_LANGUAGES = List.of("ja", "zh", "en");
    static final List<String> TEXT_FIELDS = List.of("name", "redeemDescription", "digitalDescription",
        "referralDescription");

    /** Why a value is not a settings file this reads. */
    public enum Problem { NOT_SETTINGS, NEWER_VERSION }

    /** The settings read, or why not. */
    public record Read(Map<String, Object> settings, Problem problem) {}

    private PublicationSettingsFile() {}

    public static Read parse(Object value) {
        if (!isObject(value) || !FORMAT.equals(object(value).get("format"))
            || !(object(value).get("version") instanceof Number)) {
            return new Read(null, Problem.NOT_SETTINGS);
        }
        Map<String, Object> v = object(value);
        Number version = (Number) v.get("version");
        if (version.doubleValue() > VERSION) {
            return new Read(null, Problem.NEWER_VERSION);
        }
        List<Object> tiers = new ArrayList<>();
        if (v.get("tiers") instanceof List<?> list) {
            for (Object tier : list) {
                if (!isObject(tier)) {
                    continue;
                }
                Object from = intOrNull(object(tier).get("fromScore"));
                Object to = intOrNull(object(tier).get("toScore"));
                Object reward = intOrNull(object(tier).get("reward"));
                if (from != null && to != null && reward != null) {
                    tiers.add(map("fromScore", from, "toScore", to, "reward", reward));
                }
            }
        }
        Object referral = null;
        if (isObject(v.get("referral"))) {
            Map<String, Object> r = object(v.get("referral"));
            List<Object> rules = new ArrayList<>();
            if (r.get("refereeRules") instanceof List<?> given) {
                REFEREE_RULES.stream().filter(given::contains).forEach(rules::add);
            }
            referral = map("quota", intOrNull(r.get("quota")),
                "method", oneOf(REWARD_METHODS, r.get("method"), "PLATFORM"),
                "amount", intOrNull(r.get("amount")),
                "description", textOrNull(r.get("description")),
                "referrerRequires", oneOf(REFERRER_REQUIREMENTS, r.get("referrerRequires"), "NONE"),
                "refereeRules", rules,
                "days", intOrNull(r.get("days")));
        }
        List<Object> languages = new ArrayList<>();
        if (v.get("languages") instanceof List<?> given) {
            CONTENT_LANGUAGES.stream().filter(given::contains).sorted((a, b) -> given.indexOf(a) - given.indexOf(b))
                .forEach(languages::add);
        }
        Map<String, Object> translations = new LinkedHashMap<>();
        if (isObject(v.get("translations"))) {
            for (String language : CONTENT_LANGUAGES) {
                Object texts = object(v.get("translations")).get(language);
                if (!isObject(texts)) {
                    continue;
                }
                Map<String, Object> kept = new LinkedHashMap<>();
                for (String field : TEXT_FIELDS) {
                    if (object(texts).get(field) instanceof String text) {
                        kept.put(field, text);
                    }
                }
                if (!kept.isEmpty()) {
                    translations.put(language, kept);
                }
            }
        }
        List<Object> conditions = new ArrayList<>();
        if (v.get("conditions") instanceof List<?> given) {
            for (Object c : given) {
                if (!isObject(c) || !(object(c).get("key") instanceof String key)) {
                    continue;
                }
                Map<String, Object> condition = new LinkedHashMap<>();
                condition.put("key", key);
                if (object(c).get("options") instanceof List<?> options) {
                    condition.put("options", options.stream().filter(o -> o instanceof String).toList());
                }
                if (object(c).get("min") instanceof Number min) {
                    condition.put("min", min);
                }
                if (object(c).get("max") instanceof Number max) {
                    condition.put("max", max);
                }
                conditions.add(condition);
            }
        }
        String audience = oneOf(AUDIENCES, v.get("audience"), "PUBLIC");
        boolean invited = "INVITED".equals(audience);
        List<Object> regions = new ArrayList<>();
        if (!invited && v.get("regions") instanceof List<?> given) {
            REGIONS.stream().filter(given::contains).forEach(regions::add);
        }
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("format", FORMAT);
        settings.put("version", version);
        settings.put("name", v.get("name") instanceof String name ? name : "");
        settings.put("fullScore", intOrNull(v.get("fullScore")));
        settings.put("durationDays", intOrNull(v.get("durationDays")));
        settings.put("timeLimitSec", intOrNull(v.get("timeLimitSec")));
        settings.put("passScore", intOrNull(v.get("passScore")));
        settings.put("pickCount", intOrNull(v.get("pickCount")));
        Object entryFee = intOrNull(v.get("entryFee"));
        settings.put("entryFee", entryFee == null ? 0 : entryFee);
        settings.put("budgetCap", intOrNull(v.get("budgetCap")));
        settings.put("maxWinners", intOrNull(v.get("maxWinners")));
        settings.put("tiers", tiers);
        settings.put("rewardMethod", oneOf(REWARD_METHODS, v.get("rewardMethod"), "PLATFORM"));
        settings.put("redeemDescription", textOrNull(v.get("redeemDescription")));
        settings.put("digitalDelivery", oneOf(DIGITAL_DELIVERIES, v.get("digitalDelivery"), null));
        settings.put("digitalDescription", textOrNull(v.get("digitalDescription")));
        settings.put("digitalSendDays", intOrNull(v.get("digitalSendDays")));
        settings.put("audience", audience);
        settings.put("regions", regions);
        settings.put("referral", referral);
        settings.put("languages", languages);
        settings.put("translations", translations);
        settings.put("conditions", invited ? new ArrayList<>() : conditions);
        return new Read(settings, null);
    }

    /**
     * The input of {@code QB_PUBLICATION_SAVE} for a new draft made from read settings (the page's
     * {@code inputOf(formOfSettings(settings, …))} with the file's own name and no full score to rescale to): on the
     * version given (null: none yet), from {@code startAt} to {@code endAt}, bound to no node.
     *
     * @param settings as {@link #parse} reads them
     */
    public static Map<String, Object> saveInput(Map<String, Object> settings, String versionId, String startAt,
        String endAt) {
        String method = (String) settings.get("rewardMethod");
        String audience = (String) settings.get("audience");
        boolean digital = "DIGITAL_PRIZE".equals(method);
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("publicationId", null);
        input.put("versionId", versionId);
        input.put("name", PackageJson.jsTrim((String) settings.get("name")));
        input.put("cover", null);
        input.put("regions", "INVITED".equals(audience) ? new ArrayList<>() : settings.get("regions"));
        input.put("startAt", startAt);
        input.put("endAt", endAt);
        input.put("timeLimitSec", settings.get("timeLimitSec"));
        input.put("passScore", settings.get("passScore"));
        input.put("pickCount", settings.get("pickCount"));
        input.put("entryFee", settings.get("entryFee"));
        input.put("budgetCap", settings.get("budgetCap"));
        input.put("maxWinners", settings.get("maxWinners"));
        input.put("exposureRequested", null);
        List<Object> tiers = new ArrayList<>();
        for (Object tier : PackageJson.items(settings.get("tiers"))) {
            Map<String, Object> t = object(tier);
            tiers.add(map("tierId", null, "fromScore", t.get("fromScore"), "toScore", t.get("toScore"), "reward",
                t.get("reward")));
        }
        input.put("tiers", tiers);
        input.put("baseRevision", null);
        input.put("rewardMethod", method);
        input.put("redeemDescription", "OFFLINE_REDEEM".equals(method) ? trimmedOrNull(settings.get("redeemDescription"))
            : null);
        if (digital) {
            input.put("digitalDelivery", settings.get("digitalDelivery"));
            input.put("digitalDescription", trimmedOrNull(settings.get("digitalDescription")));
            input.put("digitalSendDays", "SPONSOR_SENT".equals(settings.get("digitalDelivery"))
                ? settings.get("digitalSendDays") : null);
        }
        input.put("audience", audience);
        Object referral = null;
        if (isObject(settings.get("referral"))) {
            Map<String, Object> r = object(settings.get("referral"));
            referral = map("quota", r.get("quota"), "method", r.get("method"), "amount", r.get("amount"),
                "description", trimmedOrNull(r.get("description")), "referrerRequires", r.get("referrerRequires"),
                "refereeRules", r.get("refereeRules"), "days", r.get("days") == null ? 7 : r.get("days"));
        }
        input.put("referral", referral);
        input.put("treeId", null);
        input.put("treeNodeId", null);
        List<Object> languages = PackageJson.items(settings.get("languages"));
        if (!languages.isEmpty()) {
            input.put("languages", languages);
            Map<String, Object> translations = new LinkedHashMap<>();
            Map<String, Object> given = isObject(settings.get("translations")) ? object(settings.get("translations"))
                : Map.of();
            for (Object language : languages) {
                if (!isObject(given.get((String) language))) {
                    continue;
                }
                Map<String, Object> texts = new LinkedHashMap<>();
                object(given.get((String) language)).forEach((field, text) -> {
                    if (text instanceof String t && !PackageJson.jsTrim(t).isEmpty()) {
                        texts.put(field, PackageJson.jsTrim(t));
                    }
                });
                translations.put((String) language, texts);
            }
            input.put("translations", translations);
            input.put("covers", new LinkedHashMap<>());
        }
        return input;
    }

    /** The text trimmed, or null when blank or none (the page's {@code value.trim() || null}). */
    private static String trimmedOrNull(Object value) {
        String text = value instanceof String t ? PackageJson.jsTrim(t) : "";
        return text.isEmpty() ? null : text;
    }

    /** A whole number at least 0, else null. */
    static Object intOrNull(Object value) {
        return isInteger(value) && ((Number) value).doubleValue() >= 0 ? value : null;
    }

    /** A string with something besides blanks, else null. */
    static String textOrNull(Object value) {
        return value instanceof String text && !PackageJson.jsTrim(text).isEmpty() ? text : null;
    }

    private static String oneOf(List<String> values, Object value, String otherwise) {
        return value instanceof String text && values.contains(text) ? text : otherwise;
    }

    private static Map<String, Object> map(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], pairs[i + 1]);
        }
        return map;
    }
}
