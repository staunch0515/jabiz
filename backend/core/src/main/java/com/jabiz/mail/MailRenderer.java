package com.jabiz.mail;

import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.Code;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.Image;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.Link;
import org.commonmark.node.Node;
import org.commonmark.node.Text;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.commonmark.renderer.html.UrlSanitizer;
import org.commonmark.renderer.text.TextContentRenderer;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Renders a template's texts into a mail (docs/design/18-numbering-approvals-tasks.md section 5.6; decision D35
 * item 1). The body is Markdown, parsed before any value is filled in: values are put into the parsed text, never
 * parsed themselves, so a value cannot add a link, emphasis or markup. The HTML part escapes values (as all text) and
 * any raw HTML of the body; the plain-text part has them as they are. Link targets are limited to {@code http},
 * {@code https} and {@code mailto} (or relative), and parameters in them are percent-encoded, so a value cannot turn
 * a link into {@code javascript:}. The subject is plain text on one line, at most {@value #MAX_SUBJECT} characters.
 */
public final class MailRenderer {

    /** Longest subject (as the notifications of tasks). */
    public static final int MAX_SUBJECT = 300;

    private static final Pattern SCHEME = Pattern.compile("^([A-Za-z][A-Za-z0-9+.-]*):");
    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https", "mailto");
    private static final Parser PARSER = Parser.builder().build();
    private static final UrlSanitizer SANITIZER = new UrlSanitizer() {
        @Override
        public String sanitizeLinkUrl(String url) {
            return safeUrl(url);
        }

        @Override
        public String sanitizeImageUrl(String url) {
            return safeUrl(url);
        }
    };
    private static final HtmlRenderer HTML = HtmlRenderer.builder()
        .escapeHtml(true)
        .sanitizeUrls(true)
        .urlSanitizer(SANITIZER)
        .build();
    private static final TextContentRenderer TEXT = TextContentRenderer.builder().build();

    /** A rendered mail: one-line subject, plain-text and HTML body. */
    public record Rendered(String subject, String text, String html) {
        public Rendered {
            Objects.requireNonNull(subject, "subject must not be null");
            Objects.requireNonNull(text, "text must not be null");
            Objects.requireNonNull(html, "html must not be null");
        }
    }

    private MailRenderer() {}

    /**
     * @param subject the subject message, with placeholders
     * @param body    the Markdown body message, with placeholders
     * @param values  the values of the placeholders; a placeholder without one stays as it is
     * @param urlSafe placeholders whose values go into link targets as they are (see
     *                {@link MailTemplate#urlSafePlaceholders()}); the others are percent-encoded there
     */
    public static Rendered render(String subject, String body, Map<String, String> values, Set<String> urlSafe) {
        Objects.requireNonNull(values, "values must not be null");
        Objects.requireNonNull(urlSafe, "urlSafe must not be null");
        Node document = PARSER.parse(Objects.requireNonNull(body, "body must not be null"));
        document.accept(new Filler(values, urlSafe));
        String html = HTML.render(document);
        String text = TEXT.render(document);
        return new Rendered(subject(subject, values), text, html);
    }

    /** The subject with its values, on one line and cut to {@value #MAX_SUBJECT} characters. */
    static String subject(String subject, Map<String, String> values) {
        String filled = fill(Objects.requireNonNull(subject, "subject must not be null"), values, false, Set.of())
            .replaceAll("[\\r\\n\\t]+", " ").strip();
        return filled.length() > MAX_SUBJECT ? filled.substring(0, MAX_SUBJECT) : filled;
    }

    /** Fills the placeholders of a text; in a link target, values not known to be URL-safe are percent-encoded. */
    static String fill(String text, Map<String, String> values, boolean inUrl, Set<String> urlSafe) {
        if (text == null || text.indexOf('{') < 0) {
            return text;
        }
        Matcher matcher = MailPlaceholders.PLACEHOLDER.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String name = matcher.group(1);
            String value = values.get(name);
            String replacement;
            if (value == null) {
                replacement = matcher.group();
            } else if (inUrl && !urlSafe.contains(name)) {
                replacement = URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
            } else {
                replacement = value;
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /**
     * The URL if its scheme is allowed or it has none; otherwise empty. Characters a browser ignores (controls and
     * spaces) do not hide a scheme.
     */
    static String safeUrl(String url) {
        if (url == null) {
            return "";
        }
        StringBuilder visible = new StringBuilder();
        url.codePoints().filter(c -> c > 0x20 && c != 0x7f).forEach(visible::appendCodePoint);
        Matcher scheme = SCHEME.matcher(visible);
        if (scheme.find()) {
            return ALLOWED_SCHEMES.contains(scheme.group(1).toLowerCase(Locale.ROOT)) ? visible.toString() : "";
        }
        // A colon before any path, query or fragment that is no well-formed scheme: refused rather than guessed at.
        int colon = visible.indexOf(":");
        int end = firstOf(visible, "/?#");
        if (colon >= 0 && (end < 0 || colon < end)) {
            return "";
        }
        return visible.toString();
    }

    private static int firstOf(CharSequence text, String chars) {
        for (int i = 0; i < text.length(); i++) {
            if (chars.indexOf(text.charAt(i)) >= 0) {
                return i;
            }
        }
        return -1;
    }

    /** Puts the values into the parsed document. */
    private static final class Filler extends AbstractVisitor {

        private final Map<String, String> values;
        private final Set<String> urlSafe;

        Filler(Map<String, String> values, Set<String> urlSafe) {
            this.values = values;
            this.urlSafe = urlSafe;
        }

        @Override
        public void visit(Text text) {
            text.setLiteral(fill(text.getLiteral(), values, false, urlSafe));
        }

        @Override
        public void visit(Code code) {
            code.setLiteral(fill(code.getLiteral(), values, false, urlSafe));
        }

        @Override
        public void visit(FencedCodeBlock block) {
            block.setLiteral(fill(block.getLiteral(), values, false, urlSafe));
        }

        @Override
        public void visit(IndentedCodeBlock block) {
            block.setLiteral(fill(block.getLiteral(), values, false, urlSafe));
        }

        @Override
        public void visit(Link link) {
            link.setDestination(safeUrl(fill(link.getDestination(), values, true, urlSafe)));
            link.setTitle(fill(link.getTitle(), values, false, urlSafe));
            visitChildren(link);
        }

        @Override
        public void visit(Image image) {
            image.setDestination(safeUrl(fill(image.getDestination(), values, true, urlSafe)));
            image.setTitle(fill(image.getTitle(), values, false, urlSafe));
            visitChildren(image);
        }
    }
}
