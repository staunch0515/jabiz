package com.jabiz.quizbuks.content.ai;

import com.jabiz.entity.Violation;
import com.jabiz.quizbuks.content.ContentLimits;
import com.jabiz.quizbuks.page.ProfilePackage;
import com.jabiz.quizbuks.prospect.ProspectAnswers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads and cleans the site job's reply (F-C7). Nothing in it is trusted: the package goes through the same checks as
 * an imported one ({@link ProfilePackage#read}), and a field that fails is left out and listed with its reason, never
 * failing the whole result; a person's e-mail address or telephone number the reply carries is removed from every
 * text (P5: the company's public address goes into the introduction, a person's contact details nowhere); a source or
 * an article from a page outside the site is dropped; the quiz's questions meet the content rules of every candidate
 * ({@link AiCandidates}) and have the number of options asked, one of them correct. The sponsor's contact name is never
 * taken: the confirmation page fills in the account's.
 */
public final class SiteProfileAnswer {

    static final int MAX_NOT_FOUND = 40;
    static final int NOT_FOUND_LENGTH = 120;
    /** Passes of removing what the package's checks refuse: a removed required field refuses once more. */
    private static final int PASSES = 8;
    private static final String PLACEHOLDER = "-";

    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,}");
    // A telephone number: an optional country code, then groups of digits split by spaces, dots or hyphens
    // (03-1234-5678, +81 90 1234 5678, (03) 1234 5678). A date (2026-01-31) has no group of three digits or more after
    // its first two, so it is not taken.
    private static final Pattern PHONE = Pattern.compile(
        "(?<![\\w/.])(?:\\+\\d{1,3}[\\s.-]?)?\\(?0?\\d{1,4}\\)?[\\s.-]\\d{2,4}[\\s.-]\\d{3,4}(?![\\w.-]*\\d)");
    private static final Pattern LINK_TARGET = Pattern.compile("\\]\\((?:mailto|tel):[^)]*\\)", Pattern.CASE_INSENSITIVE);
    private static final Pattern PATH = Pattern.compile("([A-Za-z]+)(?:\\[(\\d+)])?");

    /**
     * The reply as a result, or null when it holds no JSON object (the attempt is tried again).
     *
     * @param text the reply's text
     */
    public static SiteProfileJob.Result parse(String servedModel, boolean fellBack, String text,
        SiteProfileJob.Input input, JsonMapper json) {
        JsonNode node = ProspectAnswers.lastObject(text, json);
        if (node == null) {
            return null;
        }
        String failure = node.path("failure").isString() ? node.path("failure").asString() : null;
        if (failure != null && SiteProfileJob.FAILURES.contains(failure)) {
            return SiteProfileJob.Result.failed(servedModel, fellBack, failure);
        }
        List<SiteProfileJob.Dropped> dropped = new ArrayList<>();
        Map<String, Object> profile = profile(node.get("package"), input, json, dropped);
        Map<String, String> sources = sources(node.get("sources"), profile, input);
        List<String> notFound = notFound(node.get("notFound"));
        List<String> translated = translated(node.get("translated"), input);
        List<SiteProfileJob.Material> materials = input.quiz() ? materials(node.get("materials"), input) : List.of();
        AiCandidates.Checked questions = input.quiz() && !materials.isEmpty()
            ? questions(node.get("questions"), materials.size(), input, json) : new AiCandidates.Checked(List.of(), 0);
        return new SiteProfileJob.Result(servedModel, fellBack, null, profile, sources, notFound, dropped, translated,
            materials, questions.valid(), questions.invalid());
    }

    // ---- the package -----------------------------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static Map<String, Object> profile(JsonNode node, SiteProfileJob.Input input, JsonMapper json,
        List<SiteProfileJob.Dropped> dropped) {
        Map<String, Object> given = node != null && node.isObject() ? json.convertValue(node, Map.class) : Map.of();
        Map<String, Object> check = new LinkedHashMap<>();
        check.put("format", ProfilePackage.FORMAT);
        check.put("schema", ProfilePackage.SCHEMA);
        check.put("languages", new ArrayList<>(input.languages()));
        // The sections not asked for, and the contact name (never taken), stand in with placeholders for the checks
        // only; they are not in the result.
        Map<String, Object> basics = input.profile() ? section(given.get("basics")) : new LinkedHashMap<>();
        Map<String, Object> page = input.profile() ? section(given.get("page")) : new LinkedHashMap<>();
        basics.remove("contactName");
        if (input.profile()) {
            basics.put("contactName", PLACEHOLDER);
        } else {
            basics.put("contactName", PLACEHOLDER);
            basics.put("company", PLACEHOLDER);
            page.put("handle", "placeholder");
            page.put("headline", text(input.base(), PLACEHOLDER));
        }
        List<Object> products = input.products() && given.get("products") instanceof List<?> list
            ? new ArrayList<>(list.subList(0, Math.min(list.size(), input.maxProducts()))) : new ArrayList<>();
        check.put("basics", basics);
        check.put("page", page);
        check.put("products", products);
        removePersonalDetails(check);
        for (int pass = 0; pass < PASSES; pass++) {
            ProfilePackage.Read read = ProfilePackage.read(check, input.maxProducts());
            if (read.problems().isEmpty()) {
                break;
            }
            boolean changed = false;
            // Products last-first, so that removing one does not shift the paths of the rest.
            List<Violation> problems = new ArrayList<>(read.problems());
            problems.sort((a, b) -> compare(b.field(), a.field()));
            for (Violation problem : problems) {
                changed |= remove(check, problem, dropped);
            }
            if (!changed) {
                break;
            }
        }
        if (!ProfilePackage.read(check, input.maxProducts()).problems().isEmpty()) {
            dropped.add(new SiteProfileJob.Dropped("package", "The package could not be made to meet the checks"));
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("format", ProfilePackage.FORMAT);
        result.put("schema", ProfilePackage.SCHEMA);
        result.put("languages", check.get("languages"));
        if (input.profile()) {
            Map<String, Object> shown = new LinkedHashMap<>((Map<String, Object>) check.get("basics"));
            shown.remove("contactName");
            result.put("basics", shown);
            result.put("page", check.get("page"));
        }
        result.put("products", check.get("products"));
        return result;
    }

    private static int compare(String a, String b) {
        // products[10] after products[2]: compare the index first
        int ia = productIndex(a);
        int ib = productIndex(b);
        return ia != ib ? Integer.compare(ia, ib) : String.valueOf(a).compareTo(String.valueOf(b));
    }

    private static int productIndex(String path) {
        if (path == null || !path.startsWith("products[")) {
            return -1;
        }
        return Integer.parseInt(path.substring(9, path.indexOf(']')));
    }

    private static Map<String, Object> text(String language, String value) {
        Map<String, Object> text = new LinkedHashMap<>();
        text.put(language, value);
        return text;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> section(Object value) {
        return value instanceof Map<?, ?> map ? new LinkedHashMap<>((Map<String, Object>) map) : new LinkedHashMap<>();
    }

    /**
     * Removes what a problem is about: a product with a problem goes whole; a field of the basics or the page goes by
     * itself (a required one makes the next pass refuse the section, which then fails as a whole). Whether anything
     * changed.
     */
    @SuppressWarnings("unchecked")
    private static boolean remove(Map<String, Object> check, Violation problem, List<SiteProfileJob.Dropped> dropped) {
        String path = problem.field();
        if (path == null) {
            return false;
        }
        Matcher product = Pattern.compile("^products\\[(\\d+)]").matcher(path);
        if (product.find()) {
            List<Object> products = (List<Object>) check.get("products");
            int index = Integer.parseInt(product.group(1));
            if (index < products.size()) {
                products.remove(index);
                dropped.add(new SiteProfileJob.Dropped("products[" + index + "]", problem.message()));
                return true;
            }
            return false;
        }
        String[] parts = path.split("\\.");
        if (parts.length >= 2 && (parts[0].equals("basics") || parts[0].equals("page"))) {
            Map<String, Object> section = (Map<String, Object>) check.get(parts[0]);
            String key = parts[1].replaceAll("\\[\\d+]$", "");
            if (!key.equals("contactName") && section.remove(key) != null) {
                dropped.add(new SiteProfileJob.Dropped(parts[0] + "." + key, problem.message()));
                return true;
            }
        }
        return false;
    }

    /** Removes a person's e-mail addresses and telephone numbers from every text of the package. */
    @SuppressWarnings("unchecked")
    private static void removePersonalDetails(Map<String, Object> check) {
        for (String key : List.of("basics", "page")) {
            scrub((Map<String, Object>) check.get(key));
        }
        for (Object product : (List<Object>) check.get("products")) {
            if (product instanceof Map<?, ?> map) {
                scrub((Map<String, Object>) map);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void scrub(Map<String, Object> map) {
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            String key = entry.getKey();
            if (key.equals("url") || key.equals("shopUrl") || key.equals("website") || key.equals("handle")
                || key.equals("code") || key.equals("currency")) {
                continue;
            }
            if (entry.getValue() instanceof String value) {
                entry.setValue(withoutPersonalDetails(value));
            } else if (entry.getValue() instanceof Map<?, ?> nested) {
                scrub((Map<String, Object>) nested);
            }
        }
    }

    /** A text without e-mail addresses, telephone numbers, and {@code mailto:} and {@code tel:} links. */
    public static String withoutPersonalDetails(String text) {
        if (text == null) {
            return null;
        }
        String value = LINK_TARGET.matcher(text).replaceAll("](#)");
        value = EMAIL.matcher(value).replaceAll("");
        value = PHONE.matcher(value).replaceAll("");
        return value.replaceAll("[ \\t]{2,}", " ").replaceAll("[ \\t]+([.,;:、。])", "$1").strip();
    }

    // ---- sources, articles and questions ---------------------------------------------------------------------------

    private static Map<String, String> sources(JsonNode node, Map<String, Object> profile, SiteProfileJob.Input input) {
        Map<String, String> sources = new LinkedHashMap<>();
        if (node == null || !node.isObject() || profile.isEmpty()) {
            return sources;
        }
        for (Map.Entry<String, JsonNode> entry : node.properties()) {
            String url = entry.getValue().isString() ? entry.getValue().asString() : null;
            if (sources.size() < 100 && exists(profile, entry.getKey()) && onSite(url, input.domains())) {
                sources.put(entry.getKey(), url);
            }
        }
        return sources;
    }

    /** Whether the path ({@code products[0].price}, {@code basics.company}) is in the package. */
    @SuppressWarnings("unchecked")
    private static boolean exists(Map<String, Object> profile, String path) {
        Object at = profile;
        for (String part : path.split("\\.")) {
            Matcher matcher = PATH.matcher(part);
            if (!matcher.matches() || !(at instanceof Map<?, ?> map) || !map.containsKey(matcher.group(1))) {
                return false;
            }
            at = ((Map<String, Object>) map).get(matcher.group(1));
            if (matcher.group(2) != null) {
                int index = Integer.parseInt(matcher.group(2));
                if (!(at instanceof List<?> list) || index >= list.size()) {
                    return false;
                }
                at = list.get(index);
            }
        }
        return true;
    }

    /** Whether the address is {@code https://} on one of the site's domains. */
    static boolean onSite(String url, List<String> domains) {
        if (url == null || url.length() > 300 || url.chars().anyMatch(Character::isWhitespace)) {
            return false;
        }
        try {
            URI uri = URI.create(url);
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getUserInfo() == null && uri.getPort() == -1
                && uri.getHost() != null && domains.contains(uri.getHost().toLowerCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static List<String> notFound(JsonNode node) {
        List<String> found = new ArrayList<>();
        if (node != null && node.isArray()) {
            for (JsonNode item : node) {
                if (item.isString() && found.size() < MAX_NOT_FOUND) {
                    String text = item.asString().strip();
                    if (!text.isEmpty()) {
                        found.add(text.substring(0, Math.min(text.length(), NOT_FOUND_LENGTH)));
                    }
                }
            }
        }
        return found;
    }

    private static List<String> translated(JsonNode node, SiteProfileJob.Input input) {
        List<String> found = new ArrayList<>();
        if (node != null && node.isArray()) {
            for (JsonNode item : node) {
                String language = item.isString() ? item.asString() : null;
                if (language != null && !language.equals(input.base()) && input.languages().contains(language)
                    && !found.contains(language)) {
                    found.add(language);
                }
            }
        }
        return found;
    }

    private static List<SiteProfileJob.Material> materials(JsonNode node, SiteProfileJob.Input input) {
        List<SiteProfileJob.Material> found = new ArrayList<>();
        if (node == null || !node.isArray()) {
            return found;
        }
        for (JsonNode item : node) {
            if (found.size() >= input.materialsMax()) {
                break;
            }
            String title = item.path("title").isString() ? withoutPersonalDetails(item.path("title").asString()) : null;
            String body = item.path("body").isString() ? withoutPersonalDetails(item.path("body").asString()) : null;
            String url = item.path("url").isString() ? item.path("url").asString() : null;
            if (title == null || title.isBlank() || body == null || body.isBlank() || !onSite(url, input.domains())) {
                continue;
            }
            found.add(new SiteProfileJob.Material(clip(title, ContentLimits.MATERIAL_TITLE_LENGTH),
                clip(body, ContentLimits.MATERIAL_BODY_LENGTH), url));
        }
        return found;
    }

    private static AiCandidates.Checked questions(JsonNode node, int materialCount, SiteProfileJob.Input input,
        JsonMapper json) {
        List<AiQuestionsJob.Candidate> raw = new ArrayList<>();
        int unreadable = 0;
        if (node != null && node.isArray()) {
            for (JsonNode item : node) {
                AiQuestionsJob.Candidate candidate = candidate(item, input);
                if (candidate == null) {
                    unreadable++;
                } else {
                    raw.add(candidate);
                }
            }
        }
        List<Integer> numbers = new ArrayList<>();
        for (int i = 1; i <= materialCount; i++) {
            numbers.add(i);
        }
        AiCandidates.Checked checked = AiCandidates.check(raw, input.questions(), numbers);
        return new AiCandidates.Checked(checked.valid(), checked.invalid() + unreadable);
    }

    /** One question as the job asks for it: the number of options asked, exactly one correct; null if it is not. */
    private static AiQuestionsJob.Candidate candidate(JsonNode item, SiteProfileJob.Input input) {
        if (item == null || !item.isObject() || !item.path("stem").isString() || !item.path("options").isArray()
            || item.path("options").size() != input.options()) {
            return null;
        }
        List<AiQuestionsJob.Option> options = new ArrayList<>();
        int correct = 0;
        for (JsonNode option : item.path("options")) {
            if (!option.path("text").isString() || !option.path("correct").isBoolean()) {
                return null;
            }
            boolean right = option.path("correct").asBoolean();
            correct += right ? 1 : 0;
            options.add(new AiQuestionsJob.Option(withoutPersonalDetails(option.path("text").asString()), right));
        }
        if (correct != 1) {
            return null;
        }
        List<Integer> materials = new ArrayList<>();
        for (JsonNode no : item.path("materials")) {
            if (no.isIntegralNumber()) {
                materials.add(no.asInt());
            }
        }
        return new AiQuestionsJob.Candidate(withoutPersonalDetails(item.path("stem").asString()), options, null,
            materials);
    }

    private static String clip(String text, int max) {
        return text.codePointCount(0, text.length()) <= max ? text : text.substring(0, text.offsetByCodePoints(0, max));
    }

    private SiteProfileAnswer() {}
}
