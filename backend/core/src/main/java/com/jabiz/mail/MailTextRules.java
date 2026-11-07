package com.jabiz.mail;

import com.jabiz.entity.Violation;
import com.jabiz.i18n.PlatformErrorCodes;
import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.Code;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.HtmlBlock;
import org.commonmark.node.HtmlInline;
import org.commonmark.node.Image;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.Link;
import org.commonmark.node.LinkReferenceDefinition;
import org.commonmark.node.Text;
import org.commonmark.parser.Parser;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Whether the subject and body of a template's mail are acceptable (docs/design/18-numbering-approvals-tasks.md
 * section 5.6; decision D72 item 2), every problem at once. One rule set for the texts in the code (the startup check
 * {@code MAIL}) and for those administrators write ({@code MAIL_TEXT_SAVE}, the controlled change {@code mail-text},
 * the delivery, which falls back to the default rather than send a text that fails them):
 * <ol>
 *   <li>neither is empty; the subject is one line; both are within {@link Limits} (characters, not UTF-16 units);</li>
 *   <li>placeholders are those of {@link MailTemplate#placeholders()} or {@code {entryUrl.<entry>}}
 *       ({@value PlatformErrorCodes#MAIL_TEXT_UNKNOWN_PLACEHOLDER});</li>
 *   <li>every declared parameter and token is used, and a notification's body has its unsubscribe link
 *       ({@value PlatformErrorCodes#MAIL_TEXT_REQUIRED_PLACEHOLDER});</li>
 *   <li>tokens, the sign-in link and the unsubscribe link ({@link MailTemplate#secretPlaceholders()}) stand only in
 *       link targets starting with {@code {baseUrl}}, {@code {entryUrl}} or {@code {entryUrl.<entry>}} (or being the
 *       unsubscribe link), never in text or the subject ({@value PlatformErrorCodes#MAIL_TEXT_SECRET_PLACEMENT});</li>
 *   <li>link targets are relative or {@code http}, {@code https} or {@code mailto}
 *       ({@value PlatformErrorCodes#MAIL_TEXT_LINK_NOT_ALLOWED}), and absolute web links lead to the application's own
 *       host or to one of {@link Limits#allowedLinkHosts()} ({@value PlatformErrorCodes#MAIL_TEXT_LINK_HOST});</li>
 *   <li>no images, which could be tracking pixels ({@value PlatformErrorCodes#MAIL_TEXT_IMAGE}), and no raw HTML,
 *       which the renderer escapes anyway ({@value PlatformErrorCodes#MAIL_TEXT_HTML}).</li>
 * </ol>
 * Whether the entries of {@code {entryUrl.<entry>}} exist is configuration, checked by the runtime.
 */
public final class MailTextRules {

    /** {@code jabiz.mail.texts.max-subject-length} when not configured. */
    public static final int DEFAULT_MAX_SUBJECT = 300;
    /** {@code jabiz.mail.texts.max-body-length} when not configured. */
    public static final int DEFAULT_MAX_BODY = 20_000;

    /** Fields of the violations. */
    public static final String SUBJECT = "subject";
    public static final String BODY = "body";

    private static final Parser PARSER = Parser.builder().build();
    private static final Pattern SCHEME = Pattern.compile("^([A-Za-z][A-Za-z0-9+.-]*):");
    private static final Set<String> WEB = Set.of("http", "https");
    /**
     * An address written out in text, which mail clients turn into a link of their own: also inside a word
     * ({@code Loginhttps://…}) and without a scheme ({@code www.…}), as linkifiers find them.
     */
    private static final Pattern BARE_URL = Pattern.compile("(?i)(?:https?:|www\\.)[^\\s<>()\\[\\]\"']+");

    /**
     * The configured limits.
     *
     * @param maxSubjectLength longest subject, in characters, as written (before values are filled in)
     * @param maxBodyLength    longest body, in characters, as written
     * @param siteHosts        the hosts of the application itself ({@code jabiz.mail.base-url}), lower case
     * @param allowedLinkHosts other hosts links may lead to ({@code jabiz.mail.texts.allowed-link-hosts}), lower case
     */
    public record Limits(int maxSubjectLength, int maxBodyLength, Set<String> siteHosts, Set<String> allowedLinkHosts) {

        public static final Limits DEFAULT = new Limits(DEFAULT_MAX_SUBJECT, DEFAULT_MAX_BODY, Set.of(), Set.of());

        public Limits {
            if (maxSubjectLength <= 0 || maxBodyLength <= 0) {
                throw new IllegalArgumentException("Mail text limits must be positive");
            }
            siteHosts = lower(siteHosts);
            allowedLinkHosts = lower(allowedLinkHosts);
        }

        private static Set<String> lower(Set<String> hosts) {
            Set<String> lower = new LinkedHashSet<>();
            (hosts == null ? Set.<String>of() : hosts).stream().filter(Objects::nonNull).map(String::strip)
                .filter(host -> !host.isEmpty()).forEach(host -> lower.add(host.toLowerCase(Locale.ROOT)));
            return Set.copyOf(lower);
        }

        /** Whether an absolute link may lead to {@code host}. */
        public boolean allows(String host) {
            String lower = host.toLowerCase(Locale.ROOT);
            return siteHosts.contains(lower) || allowedLinkHosts.contains(lower);
        }
    }

    private MailTextRules() {}

    /** The problems of a subject and body of {@code template}; empty when they may be sent. */
    public static List<Violation> check(MailTemplate template, String subject, String body, Limits limits) {
        Objects.requireNonNull(template, "template must not be null");
        Objects.requireNonNull(limits, "limits must not be null");
        String s = subject == null ? "" : subject;
        String b = body == null ? "" : body;
        List<Violation> problems = new ArrayList<>();
        if (s.isBlank()) {
            problems.add(violation(SUBJECT, PlatformErrorCodes.MAIL_TEXT_EMPTY, "The subject is empty", Map.of()));
        }
        if (b.isBlank()) {
            problems.add(violation(BODY, PlatformErrorCodes.MAIL_TEXT_EMPTY, "The body is empty", Map.of()));
        }
        if (s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0) {
            problems.add(violation(SUBJECT, PlatformErrorCodes.MAIL_TEXT_SUBJECT_LINE,
                "The subject is more than one line", Map.of()));
        }
        if (s.codePointCount(0, s.length()) > limits.maxSubjectLength()) {
            problems.add(violation(SUBJECT, PlatformErrorCodes.MAIL_TEXT_TOO_LONG, "The subject is longer than "
                + limits.maxSubjectLength() + " characters", Map.of("limit", limits.maxSubjectLength())));
        }
        if (b.codePointCount(0, b.length()) > limits.maxBodyLength()) {
            problems.add(violation(BODY, PlatformErrorCodes.MAIL_TEXT_TOO_LONG, "The body is longer than "
                + limits.maxBodyLength() + " characters", Map.of("limit", limits.maxBodyLength())));
        }
        placeholders(template, s, b, problems);
        Set<String> secrets = template.secretPlaceholders();
        for (String name : MailPlaceholders.of(s)) {
            if (secrets.contains(name)) {
                problems.add(secretPlacement(SUBJECT, name, "the subject"));
            }
        }
        problems.addAll(markdown(BODY, b, secrets, limits));
        return List.copyOf(problems);
    }

    /**
     * The problems of a Markdown text other than a template's body (the footer of the mail layout,
     * {@link MailLayoutRules}) by the rules of items 4 to 6: secrets only in site links, allowed links and hosts, no
     * images, no raw HTML. Placeholders are not checked here.
     *
     * @param field the field of the violations
     */
    static List<Violation> markdown(String field, String markdown, Set<String> secrets, Limits limits) {
        Body parsed = new Body(field, secrets, limits);
        PARSER.parse(markdown == null ? "" : markdown).accept(parsed);
        return List.copyOf(parsed.problems.values());
    }

    /** The problems as one line each, for the startup check and logs. */
    public static List<String> describe(List<Violation> problems) {
        return problems.stream().map(problem -> problem.field() + ": " + problem.message()).toList();
    }

    /**
     * SHA-256 (hex) of a subject and body: recorded with an override as the hash of the default it was written
     * against, so that a changed default can be pointed out.
     */
    public static String hash(String subject, String body) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(Objects.requireNonNullElse(subject, "").getBytes(StandardCharsets.UTF_8));
            // A separator neither text can hold in a way that shifts one into the other: the subject is one line.
            digest.update((byte) '\n');
            digest.update((byte) 0);
            digest.update(Objects.requireNonNullElse(body, "").getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static void placeholders(MailTemplate template, String subject, String body, List<Violation> problems) {
        Set<String> allowed = template.placeholders();
        Set<String> inSubject = MailPlaceholders.of(subject);
        Set<String> inBody = MailPlaceholders.of(body);
        Set<String> used = new LinkedHashSet<>(inSubject);
        used.addAll(inBody);
        for (String name : used) {
            String field = inSubject.contains(name) ? SUBJECT : BODY;
            if (MailTemplate.ENTRY_URL.equals(name) && !allowed.contains(name)) {
                problems.add(violation(field, PlatformErrorCodes.MAIL_TEXT_UNKNOWN_PLACEHOLDER, "uses {" + name
                    + "}, which needs the parameter " + MailTemplate.ENTRY_PARAM + " (or name the entry: {"
                    + MailTemplate.NAMED_ENTRY_URL_PREFIX + "<entry>})", Map.of("placeholder", name)));
            } else if (!allowed.contains(name) && !MailTemplate.namedEntryUrl(name)) {
                problems.add(violation(field, PlatformErrorCodes.MAIL_TEXT_UNKNOWN_PLACEHOLDER, "uses {" + name
                    + "}, which the template does not declare", Map.of("placeholder", name)));
            }
        }
        List<String> declared = new ArrayList<>(template.params());
        declared.addAll(template.tokens().keySet());
        for (String name : declared) {
            if (!used.contains(name)) {
                problems.add(violation(BODY, PlatformErrorCodes.MAIL_TEXT_REQUIRED_PLACEHOLDER,
                    "does not use the declared {" + name + "}", Map.of("placeholder", name)));
            }
        }
        if (template.unsubscribable() && !inBody.contains(MailTemplate.UNSUBSCRIBE_URL)) {
            problems.add(violation(BODY, PlatformErrorCodes.MAIL_TEXT_REQUIRED_PLACEHOLDER,
                "is a notification but its body has no {" + MailTemplate.UNSUBSCRIBE_URL + "}",
                Map.of("placeholder", MailTemplate.UNSUBSCRIBE_URL)));
        }
    }

    /**
     * Whether a link target leads to the application itself by one of the platform's own placeholders. The base
     * address has no trailing slash, so after {@code {baseUrl}} only a path, query or fragment may follow: anything
     * else would extend its host ({@code {baseUrl}.evil.example}) or make it a user name ({@code {baseUrl}@evil}).
     * The entry addresses end with a slash, so whatever follows them is a path.
     */
    static boolean siteLink(String destination) {
        Matcher placeholder = MailPlaceholders.PLACEHOLDER.matcher(destination);
        if (!placeholder.lookingAt()) {
            return false;
        }
        String name = placeholder.group(1);
        if (MailTemplate.BASE_URL.equals(name)) {
            String rest = destination.substring(placeholder.end());
            return rest.isEmpty() || "/?#".indexOf(rest.charAt(0)) >= 0 && !rest.startsWith("//");
        }
        return MailTemplate.ENTRY_URL.equals(name) || MailTemplate.UNSUBSCRIBE_URL.equals(name)
            || MailTemplate.namedEntryUrl(name);
    }

    private static Violation secretPlacement(String field, String name, String where) {
        return violation(field, PlatformErrorCodes.MAIL_TEXT_SECRET_PLACEMENT, "{" + name + "} stands in " + where
            + "; it may only stand in a link target starting with {" + MailTemplate.BASE_URL + "} or {"
            + MailTemplate.ENTRY_URL + "…}", Map.of("placeholder", name));
    }

    private static Violation violation(String field, String code, String message, Map<String, Object> params) {
        return new Violation(field, code, message, params);
    }

    /** Walks the parsed body; one problem per kind and detail. */
    private static final class Body extends AbstractVisitor {

        private final String field;
        private final Set<String> secrets;
        private final Limits limits;
        private final Map<String, Violation> problems = new LinkedHashMap<>();

        Body(String field, Set<String> secrets, Limits limits) {
            this.field = field;
            this.secrets = secrets;
            this.limits = limits;
        }

        private void add(String key, Violation violation) {
            problems.putIfAbsent(key, violation);
        }

        private void text(String literal, String where) {
            text(literal, where, true);
        }

        /** @param written whether the literal is shown as text, where mail clients link the addresses in it */
        private void text(String literal, String where, boolean written) {
            if (literal == null) {
                return;
            }
            for (String name : MailPlaceholders.of(literal)) {
                if (secrets.contains(name)) {
                    add("secret:" + name, secretPlacement(field, name, where));
                }
            }
            // Mail clients link addresses written out in the text: they follow the same rule as links.
            Matcher bare = BARE_URL.matcher(literal);
            while (written && bare.find()) {
                String found = bare.group();
                checkTarget(found.regionMatches(true, 0, "www.", 0, 4) ? "https://" + found : found);
            }
        }

        private void destination(String destination) {
            String target = destination == null ? "" : destination;
            boolean site = siteLink(target);
            if (!site) {
                for (String name : MailPlaceholders.of(target)) {
                    if (secrets.contains(name)) {
                        add("secret:" + name, secretPlacement(field, name, "the link target " + target));
                    }
                }
                checkTarget(target);
            }
        }

        /** A target not made by the platform: its scheme and, for web links, its host. */
        private void checkTarget(String target) {
            StringBuilder visible = new StringBuilder();
            target.codePoints().filter(c -> c > 0x20 && c != 0x7f).forEach(visible::appendCodePoint);
            String url = visible.toString();
            if (!target.isEmpty() && MailRenderer.safeUrl(target).isEmpty()) {
                add("link:" + url, violation(field, PlatformErrorCodes.MAIL_TEXT_LINK_NOT_ALLOWED, "The link " + url
                    + " is not http, https, mailto or relative", Map.of("url", url)));
                return;
            }
            // Browsers read a backslash as a slash in web addresses; a link has no need of one.
            if (url.indexOf('\\') >= 0) {
                add("link:" + url, violation(field, PlatformErrorCodes.MAIL_TEXT_LINK_NOT_ALLOWED, "The link " + url
                    + " contains a backslash", Map.of("url", url)));
                return;
            }
            // A target that begins with a placeholder and is no link to the site: the value decides where it leads
            // ({baseUrl}@evil.example, a parameter holding an address).
            if (url.startsWith("{")) {
                add("host:" + url, violation(field, PlatformErrorCodes.MAIL_TEXT_LINK_HOST, "The link " + url
                    + " does not lead to this application: after {" + MailTemplate.BASE_URL
                    + "} only a path, query or fragment may follow", Map.of("host", url)));
                return;
            }
            Matcher scheme = SCHEME.matcher(url);
            String authority;
            if (scheme.find()) {
                if (!WEB.contains(scheme.group(1).toLowerCase(Locale.ROOT))) {
                    return;
                }
                // http:host and https:/host lead to the host as http://host does (WHATWG URL parsing).
                authority = url.substring(scheme.end()).replaceFirst("^/*", "");
            } else if (url.startsWith("//")) {
                authority = url.substring(2);
            } else {
                return;
            }
            String host = host(authority);
            if (host == null || !limits.allows(host)) {
                String shown = host == null ? url : host;
                add("host:" + shown, violation(field, PlatformErrorCodes.MAIL_TEXT_LINK_HOST, "The link " + url
                    + " leads to " + shown + ", which is neither this application nor an allowed host"
                    + " (jabiz.mail.texts.allowed-link-hosts)", Map.of("host", shown)));
            }
        }

        /** The host of an authority (up to the path, query or fragment); null when it is not a plain host name. */
        private static String host(String afterSlashes) {
            int end = afterSlashes.length();
            for (int i = 0; i < afterSlashes.length(); i++) {
                if ("/?#\\".indexOf(afterSlashes.charAt(i)) >= 0) {
                    end = i;
                    break;
                }
            }
            String authority = afterSlashes.substring(0, end);
            int at = authority.lastIndexOf('@');
            String host = at >= 0 ? authority.substring(at + 1) : authority;
            int colon = host.lastIndexOf(':');
            if (colon >= 0 && host.indexOf(']') < colon) {
                host = host.substring(0, colon);
            }
            // A placeholder in the host would let a value choose where the link leads.
            if (host.isEmpty() || host.contains("{") || host.contains("}") || host.contains("%")) {
                return null;
            }
            return host.endsWith(".") ? host.substring(0, host.length() - 1) : host;
        }

        @Override
        public void visit(Text text) {
            text(text.getLiteral(), "the text");
        }

        @Override
        public void visit(Code code) {
            text(code.getLiteral(), "the text");
        }

        @Override
        public void visit(FencedCodeBlock block) {
            text(block.getLiteral(), "the text");
        }

        @Override
        public void visit(IndentedCodeBlock block) {
            text(block.getLiteral(), "the text");
        }

        @Override
        public void visit(HtmlInline html) {
            add("html", violation(field, PlatformErrorCodes.MAIL_TEXT_HTML, "The body contains raw HTML ("
                + html.getLiteral() + ")", Map.of()));
            text(html.getLiteral(), "the text");
        }

        @Override
        public void visit(HtmlBlock html) {
            add("html", violation(field, PlatformErrorCodes.MAIL_TEXT_HTML, "The body contains raw HTML", Map.of()));
            text(html.getLiteral(), "the text");
        }

        @Override
        public void visit(Image image) {
            add("image", violation(field, PlatformErrorCodes.MAIL_TEXT_IMAGE, "The body contains an image",
                Map.of()));
            text(image.getDestination(), "an image", false);
            text(image.getTitle(), "an image");
            visitChildren(image);
        }

        @Override
        public void visit(Link link) {
            destination(link.getDestination());
            text(link.getTitle(), "a link title");
            visitChildren(link);
        }

        @Override
        public void visit(LinkReferenceDefinition definition) {
            destination(definition.getDestination());
            text(definition.getTitle(), "a link title");
        }
    }
}
