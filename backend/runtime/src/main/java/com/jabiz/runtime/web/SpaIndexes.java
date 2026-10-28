package com.jabiz.runtime.web;

import com.jabiz.runtime.check.CheckProblem;
import com.jabiz.runtime.check.PlatformCheck;
import com.jabiz.runtime.web.JabizWebProperties.Spa;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.web.WebProperties;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Index pages that show values of the deployment (phase 17g, docs/design/17-apps-and-branches.md section 3.2): an SPA
 * whose {@code jabiz.web.spa[i].index-values} names values gets its index page with each attribute value written
 * {@code ="{{jabiz:<name>}}"} replaced by that value, HTML-escaped. One build then serves every environment: a public
 * key another platform's script reads from a {@code meta} element ({@code <meta name="shopify-api-key"
 * content="{{jabiz:shopify-api-key}}">}) comes from the environment the jar runs in, not from the build.
 *
 * <p>A placeholder stands only for a whole attribute value in double quotes within a tag, where an escaped value is
 * plain text; anywhere else (in text, a script or style, a single-quoted or unquoted attribute, part of a value) it is
 * an error. The rendered page has a strong entity tag, so a revalidation is answered 304. The pages are read and
 * rendered once at startup, from the first static location that holds them; every problem is reported by the startup
 * check (category {@value SpaConfigCheck#CATEGORY}): a placeholder in another form or without a value is an error, a
 * value no placeholder uses or an index the jar does not hold is a warning (the SPA is then served as built). An SPA
 * without values is served as before, its page untouched.
 */
@Component
public class SpaIndexes implements PlatformCheck {

    /** A value's name. */
    static final Pattern NAME = Pattern.compile("[a-z][a-z0-9-]{0,39}");
    /** The longest value. */
    static final int MAX_VALUE = 2000;

    private static final String OPEN = "{{jabiz:";
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{jabiz:([^}]*)}}");

    /** A rendered index page and its entity tag. */
    record Page(byte[] body, String etag) {}

    /** The rendered index pages, by SPA path. */
    private final Map<String, Page> pages;
    private final List<CheckProblem> problems;

    @Autowired
    SpaIndexes(JabizWebProperties properties, ObjectProvider<WebProperties> web) {
        this(properties, locations(web.getIfAvailable()), new DefaultResourceLoader());
    }

    SpaIndexes(JabizWebProperties properties, List<String> locations, ResourceLoader loader) {
        this(properties, index -> read(locations, loader, index));
    }

    /** With the index pages given by their index path (empty: not in the jar), for tests. */
    SpaIndexes(JabizWebProperties properties, Function<String, Optional<String>> indexes) {
        Map<String, Page> rendered = new HashMap<>();
        List<CheckProblem> found = new ArrayList<>();
        for (int i = 0; i < properties.spa().size(); i++) {
            Spa spa = properties.spa().get(i);
            if (spa.indexValues().isEmpty() || spa.path() == null) {
                continue;
            }
            String where = "jabiz.web.spa[" + i + "]";
            Optional<String> page;
            try {
                page = indexes.apply(spa.index());
            } catch (java.io.UncheckedIOException e) {
                found.add(CheckProblem.error(SpaConfigCheck.CATEGORY, where + ".index-values", "the index page "
                    + spa.index() + " cannot be read: " + e.getCause().getMessage()));
                continue;
            }
            if (page.isEmpty()) {
                found.add(CheckProblem.warning(SpaConfigCheck.CATEGORY, where + ".index-values", "the index page "
                    + spa.index() + " is not in the application: the values are not shown (it is served as built)"));
                continue;
            }
            Render render = render(page.get(), spa.indexValues());
            render.problems().forEach(problem -> found.add(CheckProblem.error(SpaConfigCheck.CATEGORY,
                where + ".index-values", spa.index() + ": " + problem)));
            Set<String> unused = new LinkedHashSet<>(spa.indexValues().keySet());
            unused.removeAll(render.mentioned());
            unused.forEach(name -> found.add(CheckProblem.warning(SpaConfigCheck.CATEGORY,
                where + ".index-values." + name, "no attribute of " + spa.index() + " says {{jabiz:" + name + "}}")));
            if (render.problems().isEmpty()) {
                byte[] bytes = render.page().getBytes(StandardCharsets.UTF_8);
                rendered.put(spa.path(), new Page(bytes, etag(bytes)));
            }
        }
        this.pages = Map.copyOf(rendered);
        this.problems = List.copyOf(found);
    }

    /** No SPA shows values. */
    static SpaIndexes none() {
        return new SpaIndexes(new JabizWebProperties(null), index -> Optional.empty());
    }

    @Override
    public List<CheckProblem> check() {
        return problems;
    }

    /** The index page of {@code spa} with its values, if it shows any; empty for one served as built. */
    Optional<Page> page(Spa spa) {
        return Optional.ofNullable(pages.get(spa.path()));
    }

    /**
     * A page with its values: what it became, the names written into it, the names it mentions (in any form) and what
     * is wrong with it.
     */
    record Render(String page, Set<String> used, Set<String> mentioned, List<String> problems) {}

    static Render render(String page, Map<String, String> values) {
        StringBuilder out = new StringBuilder(page.length());
        Set<String> used = new LinkedHashSet<>();
        Set<String> mentioned = new LinkedHashSet<>();
        List<String> problems = new ArrayList<>();
        Matcher matcher = PLACEHOLDER.matcher(page);
        int last = 0;
        int found = 0;
        while (matcher.find()) {
            found++;
            String name = matcher.group(1);
            mentioned.add(name);
            boolean attribute = matcher.start() >= 2 && page.startsWith("=\"", matcher.start() - 2)
                && page.startsWith("\"", matcher.end()) && inTag(page, matcher.start());
            String value = values.get(name);
            out.append(page, last, matcher.start());
            if (!attribute) {
                problems.add("{{jabiz:" + name + "}} must be a whole attribute value in double quotes (=\"{{jabiz:"
                    + name + "}}\"), where nothing else can be written");
                out.append(matcher.group());
            } else if (value == null) {
                problems.add("{{jabiz:" + name + "}} has no value (jabiz.web.spa[i].index-values." + name + ")");
                out.append(matcher.group());
            } else {
                used.add(name);
                out.append(escape(value));
            }
            last = matcher.end();
        }
        out.append(page, last, page.length());
        // An opening the pattern did not take (unclosed): never left for a reader to guess at.
        if (occurrences(page, OPEN) > found) {
            problems.add("{{jabiz: is opened but not closed");
        }
        return new Render(out.toString(), Set.copyOf(used), Set.copyOf(mentioned), List.copyOf(problems));
    }

    /**
     * Whether {@code at} lies within a tag's attributes: after a {@code <} with no {@code >} since, and not in the text
     * of a {@code script} or {@code style} element (whose {@code <} need not open a tag).
     */
    static boolean inTag(String page, int at) {
        String before = page.substring(0, at);
        if (before.lastIndexOf('<') <= before.lastIndexOf('>')) {
            return false;
        }
        String lower = before.toLowerCase(java.util.Locale.ROOT);
        for (String element : List.of("script", "style")) {
            if (lower.lastIndexOf("<" + element) > lower.lastIndexOf("</" + element)) {
                return false;
            }
        }
        return true;
    }

    private static int occurrences(String text, String part) {
        int count = 0;
        for (int at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + part.length())) {
            count++;
        }
        return count;
    }

    /** A strong entity tag of a page: it changes only when the page does. */
    static String etag(byte[] body) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(body);
            return "\"" + java.util.HexFormat.of().formatHex(digest, 0, 16) + "\"";
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** {@code value} as attribute text: nothing in it can end the attribute or start markup. */
    static String escape(String value) {
        return org.springframework.web.util.HtmlUtils.htmlEscape(value, StandardCharsets.UTF_8.name());
    }

    private static List<String> locations(WebProperties web) {
        String[] locations = web == null ? new String[] {"classpath:/static/"} : web.getResources().getStaticLocations();
        return List.of(locations);
    }

    private static Optional<String> read(List<String> locations, ResourceLoader loader, String index) {
        for (String location : locations) {
            String base = location.endsWith("/") ? location : location + "/";
            Resource resource = loader.getResource(base + index.substring(1));
            if (!resource.exists() || !resource.isReadable()) {
                continue;
            }
            try (InputStream in = resource.getInputStream()) {
                return Optional.of(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new java.io.UncheckedIOException("Could not read " + base + index.substring(1), e);
            }
        }
        return Optional.empty();
    }
}
