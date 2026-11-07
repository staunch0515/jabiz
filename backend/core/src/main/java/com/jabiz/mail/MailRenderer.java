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
 * a link into {@code javascript:}. The subject is plain text on one line, cut to the configured longest subject
 * ({@code jabiz.mail.texts.max-subject-length}, {@value MailTextRules#DEFAULT_MAX_SUBJECT} characters by default).
 */
public final class MailRenderer {

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
        return render(subject, body, values, urlSafe, MailTextRules.DEFAULT_MAX_SUBJECT);
    }

    /**
     * As {@link #render(String, String, Map, Set)}, the subject cut to {@code maxSubject} characters (code points)
     * once its values are filled in.
     */
    public static Rendered render(String subject, String body, Map<String, String> values, Set<String> urlSafe,
        int maxSubject) {
        Objects.requireNonNull(values, "values must not be null");
        Objects.requireNonNull(urlSafe, "urlSafe must not be null");
        Node document = PARSER.parse(Objects.requireNonNull(body, "body must not be null"));
        document.accept(new Filler(values, urlSafe));
        String html = HTML.render(document);
        String text = TEXT.render(document);
        return new Rendered(subject(subject, values, maxSubject), text, html);
    }

    /**
     * A mail whose body is plain text (a task notification or a document mail as recorded): the HTML part is the text
     * escaped, its paragraphs and lines kept.
     */
    public static Rendered plain(String subject, String text) {
        Objects.requireNonNull(text, "text must not be null");
        StringBuilder html = new StringBuilder();
        for (String paragraph : text.strip().split("\\R\\s*\\R")) {
            if (!paragraph.isBlank()) {
                html.append("<p>").append(escape(paragraph.strip()).replaceAll("\\R", "<br>\n")).append("</p>\n");
            }
        }
        return new Rendered(Objects.requireNonNull(subject, "subject must not be null"), text, html.toString());
    }

    /**
     * Puts a rendered mail on a layout (decision D72): the HTML part becomes a complete document of one column (a
     * table of fixed width, inline styles only, no external styles, scripts or images but the layout's logo, which
     * {@link MailLayoutRules} keeps to the application's own); the plain-text part gets the display name above and the
     * footer under a signature separator. An empty layout leaves the mail as it is. The display name and the logo's
     * text are escaped; the footer is Markdown, rendered as a body is (its values never parsed).
     *
     * @param values the values of the footer's and logo's placeholders: {@code baseUrl} and the named entries'
     *               {@code entryUrl.<entry>}, from configuration
     */
    public static Rendered wrap(Rendered mail, MailLayout layout, Map<String, String> values) {
        Objects.requireNonNull(mail, "mail must not be null");
        Objects.requireNonNull(layout, "layout must not be null");
        Objects.requireNonNull(values, "values must not be null");
        if (layout.isEmpty()) {
            return mail;
        }
        Rendered footer = layout.footer().isBlank() ? null
            : render("", layout.footer(), values, Set.of(MailTemplate.BASE_URL));
        String name = layout.displayName().strip();
        String logo = layout.logoUrl().isBlank() ? "" : logoUrl(layout.logoUrl().strip(), values);

        StringBuilder text = new StringBuilder();
        if (!name.isEmpty()) {
            text.append(name).append("\n\n");
        }
        text.append(mail.text().strip()).append('\n');
        if (footer != null) {
            text.append("\n-- \n").append(footer.text().strip()).append('\n');
        }

        StringBuilder html = new StringBuilder(mail.html().length() + 2048);
        html.append("<!DOCTYPE html>\n<html><head><meta charset=\"utf-8\">")
            .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
            .append("<title>").append(escape(mail.subject())).append("</title></head>\n")
            .append("<body style=\"margin:0;padding:0;background-color:#f4f4f5;\">\n")
            .append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\"")
            .append(" style=\"background-color:#f4f4f5;\"><tr><td align=\"center\" style=\"padding:24px 12px;\">\n")
            .append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\"")
            .append(" style=\"max-width:600px;background-color:#ffffff;border-radius:8px;\">\n");
        if (!name.isEmpty() || !logo.isEmpty()) {
            html.append("<tr><td style=\"").append(FONT).append("padding:24px 24px 0;font-size:18px;font-weight:600;")
                .append("color:#18181b;\">");
            if (!logo.isEmpty()) {
                html.append("<img src=\"").append(escape(logo)).append("\" alt=\"")
                    .append(escape(layout.logoAlt().strip())).append("\" height=\"40\"")
                    .append(" style=\"display:block;height:40px;width:auto;border:0;margin-bottom:8px;\">");
            }
            html.append(escape(name)).append("</td></tr>\n");
        }
        html.append("<tr><td style=\"").append(FONT).append("padding:24px;font-size:15px;line-height:1.6;")
            .append("color:#18181b;\">\n").append(mail.html()).append("</td></tr>\n");
        if (footer != null) {
            html.append("<tr><td style=\"").append(FONT).append("padding:16px 24px 24px;font-size:12px;")
                .append("line-height:1.5;color:#52525b;border-top:1px solid #e4e4e7;\">\n").append(footer.html())
                .append("</td></tr>\n");
        }
        html.append("</table>\n</td></tr></table>\n</body></html>\n");
        return new Rendered(mail.subject(), text.toString(), html.toString());
    }

    private static final String FONT = "font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,'Helvetica Neue',"
        + "Arial,'Noto Sans','PingFang SC','Hiragino Sans','Microsoft YaHei',sans-serif;";

    /** The logo's address: {@code {baseUrl}} filled in, a bare path put under it; empty when not allowed. */
    static String logoUrl(String url, Map<String, String> values) {
        if (!MailLayoutRules.siteImage(url)) {
            return "";
        }
        String base = Objects.requireNonNullElse(values.get(MailTemplate.BASE_URL), "");
        String placeholder = "{" + MailTemplate.BASE_URL + "}";
        String filled = base + (url.startsWith(placeholder) ? url.substring(placeholder.length()) : url);
        return safeUrl(filled);
    }

    /** Text escaped for HTML, in elements and in quoted attributes. */
    static String escape(String text) {
        StringBuilder out = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    /** The subject with its values, on one line and cut to {@code max} characters (code points). */
    static String subject(String subject, Map<String, String> values, int max) {
        String filled = fill(Objects.requireNonNull(subject, "subject must not be null"), values, false, Set.of())
            .replaceAll("[\\r\\n\\t]+", " ").strip();
        // On characters, not UTF-16 units: a cut must not halve a surrogate pair (an emoji).
        return filled.codePointCount(0, filled.length()) > max
            ? filled.substring(0, filled.offsetByCodePoints(0, max)) : filled;
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
            } else if (inUrl && !urlSafe.contains(name) && !MailTemplate.namedEntryUrl(name)) {
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
