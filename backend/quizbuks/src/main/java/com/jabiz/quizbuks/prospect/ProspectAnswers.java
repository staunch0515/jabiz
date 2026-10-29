package com.jabiz.quizbuks.prospect;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Claude's answers to prospecting, read and checked (Q48c): the JSON object a reply ends with, field by field. A field
 * that is not what it should be is dropped (a candidate, a product, a reason) or makes the whole draft unusable (the
 * trial quiz, the mail): a business is never written to with a quiz or a mail the checks did not pass. Pure Java: no
 * database, no network.
 */
public final class ProspectAnswers {

    static final int COMPANY_LENGTH = 200;
    static final int PRODUCT_NAME_LENGTH = 200;
    static final int PRODUCT_DESCRIPTION_LENGTH = 500;
    static final int MAX_PRODUCTS = 5;
    static final int REASON_LENGTH = 300;
    static final int MAX_REASONS = 8;
    static final int TITLE_LENGTH = 120;
    static final int INTRO_LENGTH = 300;
    static final int STEM_LENGTH = 300;
    static final int OPTION_LENGTH = 150;
    static final int SUBJECT_LENGTH = 120;
    static final int BODY_LENGTH = 1500;

    /** A host name of at least two labels, letters, digits and hyphens (IDNs in their ASCII form). */
    private static final Pattern DOMAIN = Pattern.compile(
        "(?=.{4,253}$)([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z][a-z0-9-]{1,62}");
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]{1,64}@" + "[A-Za-z0-9.-]{1,253}");
    private static final Pattern LANGUAGE = Pattern.compile("[a-z]{2}");
    private static final Pattern COUNTRY = Pattern.compile("[A-Z]{2}");
    /** Hosts that are never a business's own site. */
    private static final Set<String> PLATFORMS = Set.of("amazon.co.jp", "amazon.com", "rakuten.co.jp",
        "facebook.com", "instagram.com", "x.com", "twitter.com", "tiktok.com", "youtube.com", "linkedin.com",
        "tabelog.com", "yelp.com", "google.com", "wikipedia.org", "note.com", "ameblo.jp", "line.me", "taobao.com",
        "tmall.com", "jd.com", "etsy.com", "ebay.com", "mercari.com", "yahoo.co.jp");

    /** The last JSON object of a reply (the one it ends with); null when there is none. */
    public static JsonNode lastObject(String text, JsonMapper json) {
        if (text == null) {
            return null;
        }
        int end = text.lastIndexOf('}');
        while (end >= 0) {
            // Each '{' before the last '}' is a possible start; the outermost that parses wins.
            for (int start = text.indexOf('{'); start >= 0 && start < end; start = text.indexOf('{', start + 1)) {
                try {
                    JsonNode node = json.readTree(text.substring(start, end + 1));
                    if (node != null && node.isObject()) {
                        return node;
                    }
                } catch (JacksonException e) {
                    // not this start
                }
            }
            end = text.lastIndexOf('}', end - 1);
        }
        return null;
    }

    /**
     * The candidates of a search: own sites only, at most {@code max}, none of {@code excluded}, each domain once.
     * Null when the reply holds no {@code candidates} list (the attempt is tried again).
     */
    public static ProspectJobs.FindResult find(String servedModel, String text, int max, Set<String> excluded,
        JsonMapper json) {
        JsonNode root = lastObject(text, json);
        JsonNode list = root == null ? null : root.get("candidates");
        if (list == null || !list.isArray()) {
            return null;
        }
        Set<String> seen = new LinkedHashSet<>();
        List<ProspectJobs.Candidate> candidates = new ArrayList<>();
        int invalid = 0;
        for (JsonNode node : list) {
            ProspectJobs.Candidate candidate = candidate(node);
            if (candidate == null || excluded.contains(candidate.domain()) || !seen.add(candidate.domain())
                || candidates.size() >= max) {
                invalid++;
                continue;
            }
            candidates.add(candidate);
        }
        return new ProspectJobs.FindResult(servedModel, candidates, invalid);
    }

    static ProspectJobs.Candidate candidate(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        String url = text(node.get("url"), 500);
        String domain = QbProspects.domain(text(node.get("domain"), 253));
        String host = url == null ? null : host(url);
        if (domain == null && host != null) {
            domain = host;
        }
        if (domain == null || !validDomain(domain) || (host != null && !host.equals(domain)
            && !host.endsWith("." + domain))) {
            return null;
        }
        if (url == null || host == null) {
            url = "https://" + domain + "/";
        }
        return new ProspectJobs.Candidate(domain, url, text(node.get("company"), COMPANY_LENGTH));
    }

    /** Whether a domain may be a business's own site: a well-formed host that is no shared platform. */
    public static boolean validDomain(String domain) {
        if (domain == null || !DOMAIN.matcher(domain).matches()) {
            return false;
        }
        for (String platform : PLATFORMS) {
            if (domain.equals(platform) || domain.endsWith("." + platform)) {
                return false;
            }
        }
        return true;
    }

    /** The host of an http(s) address, as {@link QbProspects#domain} keeps it; null for anything else. */
    static String host(String url) {
        try {
            URI uri = URI.create(url.strip());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!scheme.equals("https") && !scheme.equals("http") || uri.getHost() == null || uri.getUserInfo() != null) {
                return null;
            }
            return QbProspects.domain(uri.getHost());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * The research of a reply; null when it holds no usable object (the attempt is tried again). The trial and the
     * mail are kept only when both are complete and the business may be written to ({@link #trial}, {@link #mail}).
     *
     * @param domain the business's domain: a published address must be of it (or a subdomain of it)
     */
    public static ProspectJobs.ResearchResult research(String servedModel, String text, String domain,
        JsonMapper json) {
        JsonNode root = lastObject(text, json);
        if (root == null || root.get("score") == null || !root.get("score").isNumber()) {
            return null;
        }
        int score = (int) Math.max(0, Math.min(100, Math.round(root.get("score").asDouble())));
        String language = text(root.get("language"), 5);
        language = language == null ? null : language.toLowerCase(Locale.ROOT);
        if (language != null && !LANGUAGE.matcher(language).matches()) {
            language = null;
        }
        String country = text(root.get("country"), 2);
        country = country == null ? null : country.toUpperCase(Locale.ROOT);
        if (country != null && !COUNTRY.matcher(country).matches()) {
            country = null;
        }
        String email = businessEmail(text(root.get("publicEmail"), QbProspects.EMAIL_LENGTH), domain);
        boolean refuses = root.get("refusesMarketing") != null && root.get("refusesMarketing").asBoolean(false);
        List<String> reasons = new ArrayList<>();
        JsonNode given = root.get("reasons");
        if (given != null && given.isArray()) {
            for (JsonNode reason : given) {
                String value = text(reason, REASON_LENGTH);
                if (value != null && reasons.size() < MAX_REASONS) {
                    reasons.add(value);
                }
            }
        }
        List<ProspectJobs.Product> products = new ArrayList<>();
        JsonNode items = root.get("products");
        if (items != null && items.isArray()) {
            for (JsonNode item : items) {
                String name = item.isObject() ? text(item.get("name"), PRODUCT_NAME_LENGTH) : null;
                if (name != null && products.size() < MAX_PRODUCTS) {
                    products.add(new ProspectJobs.Product(name, text(item.get("description"),
                        PRODUCT_DESCRIPTION_LENGTH)));
                }
            }
        }
        ProspectJobs.Trial trial = trial(root.get("trial"));
        String subject = text(root.get("mailSubject"), SUBJECT_LENGTH);
        String body = mailBody(text(root.get("mailBody"), BODY_LENGTH));
        if (trial == null || subject == null || body == null) {
            trial = null;
            subject = null;
            body = null;
        }
        return new ProspectJobs.ResearchResult(servedModel, text(root.get("company"), COMPANY_LENGTH), language,
            country, bool(root.get("sellsOnline")), bool(root.get("shopify")), email, refuses, score, reasons,
            products, trial, subject, body);
    }

    /**
     * A published business address, kept only when it is an address of the business's own domain (or a subdomain):
     * an address of a free mail service or of somebody else is no business address of this site.
     */
    static String businessEmail(String value, String domain) {
        if (value == null || !EMAIL.matcher(value).matches()) {
            return null;
        }
        String host = QbProspects.domain(value.substring(value.indexOf('@') + 1));
        if (domain == null || !(host.equals(domain) || host.endsWith("." + domain))) {
            return null;
        }
        return value.substring(0, value.indexOf('@') + 1).toLowerCase(Locale.ROOT) + host;
    }

    /**
     * A trial quiz, or null unless it has a title and exactly {@value ProspectJobs#TRIAL_QUESTIONS} questions of
     * exactly {@value ProspectJobs#TRIAL_OPTIONS} distinct options with exactly one correct.
     */
    public static ProspectJobs.Trial trial(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        String title = text(node.get("title"), TITLE_LENGTH);
        JsonNode list = node.get("questions");
        if (title == null || list == null || !list.isArray() || list.size() != ProspectJobs.TRIAL_QUESTIONS) {
            return null;
        }
        List<ProspectJobs.TrialQuestion> questions = new ArrayList<>();
        for (JsonNode question : list) {
            ProspectJobs.TrialQuestion checked = question(question);
            if (checked == null) {
                return null;
            }
            questions.add(checked);
        }
        return new ProspectJobs.Trial(title, text(node.get("intro"), INTRO_LENGTH), questions);
    }

    static ProspectJobs.TrialQuestion question(JsonNode node) {
        String stem = node != null && node.isObject() ? text(node.get("stem"), STEM_LENGTH) : null;
        JsonNode list = stem == null ? null : node.get("options");
        if (list == null || !list.isArray() || list.size() != ProspectJobs.TRIAL_OPTIONS) {
            return null;
        }
        List<ProspectJobs.TrialOption> options = new ArrayList<>();
        Set<String> texts = new LinkedHashSet<>();
        int correct = 0;
        for (JsonNode option : list) {
            String text = option.isObject() ? text(option.get("text"), OPTION_LENGTH) : null;
            if (text == null || !texts.add(text.toLowerCase(Locale.ROOT))) {
                return null;
            }
            boolean right = option.get("correct") != null && option.get("correct").isBoolean()
                && option.get("correct").asBoolean();
            correct += right ? 1 : 0;
            options.add(new ProspectJobs.TrialOption(text, right));
        }
        return correct == 1 ? new ProspectJobs.TrialQuestion(stem, options) : null;
    }

    /** A trial quiz edited on the approval page, checked as Claude's is; null when it does not pass. */
    public static ProspectJobs.Trial edited(ProspectJobs.Trial trial) {
        if (trial == null) {
            return null;
        }
        return trial(JsonMapper.builder().build().valueToTree(trial));
    }

    /**
     * A mail's body as it may go out: plain text, without links (the quiz's link and the unsubscribe link are the
     * template's own) or anything that reads as markup; null when it has any.
     */
    public static String mailBody(String value) {
        if (value == null) {
            return null;
        }
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.contains("http://") || lower.contains("https://") || lower.contains("www.") || value.contains("<")
            || value.contains("](") || value.contains("{")) {
            return null;
        }
        return value;
    }

    /** Text of at most {@code max} characters, trimmed; null when absent, blank or longer. */
    static String text(JsonNode node, int max) {
        if (node == null || !node.isString()) {
            return null;
        }
        String value = node.asString().strip();
        return value.isEmpty() || value.length() > max || value.chars().anyMatch(c -> c < 0x20 && c != '\n')
            ? null : value;
    }

    private static Boolean bool(JsonNode node) {
        return node != null && node.isBoolean() ? node.asBoolean() : null;
    }

    private ProspectAnswers() {}
}
