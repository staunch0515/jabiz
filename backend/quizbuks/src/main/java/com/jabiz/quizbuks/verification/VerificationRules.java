package com.jabiz.quizbuks.verification;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The rules of a company verification (F-M3), pure Java: what a domain is, which are public mail services, when an
 * address is on a domain, and when a Sponsor is verified. One definition each; the processes and the templates follow
 * them.
 */
public final class VerificationRules {

    /** One label of a host name: letters, digits and inner hyphens (punycode, {@code xn--}, is one such). */
    private static final Pattern LABEL = Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?");
    /** The top level: letters only (or punycode), at least two. */
    private static final Pattern TOP = Pattern.compile("[a-z]{2,63}|xn--[a-z0-9-]{1,59}");

    /**
     * Mail services anybody may have an address of, so that an address there proves nothing about a company. A
     * domain is refused when it is one of these or under one ({@code mail.google.com} too). The list is extended here.
     */
    public static final Set<String> PUBLIC_MAIL_DOMAINS = Set.of(
        "gmail.com", "googlemail.com", "google.com",
        "outlook.com", "outlook.jp", "hotmail.com", "hotmail.co.jp", "live.com", "live.jp", "msn.com",
        "yahoo.com", "yahoo.co.jp", "ymail.com", "rocketmail.com",
        "icloud.com", "me.com", "mac.com",
        "aol.com", "gmx.com", "gmx.net", "mail.com", "zoho.com", "proton.me", "protonmail.com", "pm.me",
        "yandex.com", "yandex.ru", "fastmail.com", "tutanota.com", "hey.com",
        "qq.com", "foxmail.com", "163.com", "126.com", "yeah.net", "sina.com", "sina.cn", "sohu.com", "139.com",
        "189.cn", "aliyun.com", "tom.com",
        "docomo.ne.jp", "ezweb.ne.jp", "au.com", "softbank.ne.jp", "i.softbank.jp", "nifty.com", "biglobe.ne.jp",
        "so-net.ne.jp", "ocn.ne.jp", "excite.co.jp", "livedoor.com");

    /** How a domain is proven. */
    public static final String ACCOUNT = "ACCOUNT";
    public static final String LINK = "LINK";

    private VerificationRules() {
    }

    /**
     * A domain as given, normalized: trimmed, lower case, a leading {@code www.} and a trailing dot left out; empty
     * when it is not a host name of at least two labels (no scheme, path, port, user or IP address; not parsed as a
     * URL). Non-ASCII names are given as punycode.
     */
    public static Optional<String> domain(String given) {
        if (given == null) {
            return Optional.empty();
        }
        String domain = given.strip().toLowerCase(Locale.ROOT);
        if (domain.endsWith(".")) {
            domain = domain.substring(0, domain.length() - 1);
        }
        if (domain.startsWith("www.")) {
            domain = domain.substring(4);
        }
        if (domain.isEmpty() || domain.length() > QbVerifications.DOMAIN_LENGTH) {
            return Optional.empty();
        }
        String[] labels = domain.split("\\.", -1);
        if (labels.length < 2) {
            return Optional.empty();
        }
        for (String label : labels) {
            if (!LABEL.matcher(label).matches()) {
                return Optional.empty();
            }
        }
        return TOP.matcher(labels[labels.length - 1]).matches() ? Optional.of(domain) : Optional.empty();
    }

    /** Whether a (normalized) domain is a public mail service's, or under one. */
    public static boolean publicMail(String domain) {
        if (domain == null) {
            return false;
        }
        for (String service : PUBLIC_MAIL_DOMAINS) {
            if (onDomain(domain, service)) {
                return true;
            }
        }
        return false;
    }

    /** Whether a host is the domain or under it ({@code tokyo.example.co.jp} is on {@code example.co.jp}). */
    public static boolean onDomain(String host, String domain) {
        if (host == null || domain == null) {
            return false;
        }
        String h = host.toLowerCase(Locale.ROOT);
        String d = domain.toLowerCase(Locale.ROOT);
        return h.equals(d) || h.endsWith("." + d);
    }

    /**
     * Whether an address's host is the domain or under it (the controller, 2026-11-10: a subdomain counts). Only a
     * plain {@code local@host} counts: one {@code @}, no spaces.
     */
    public static boolean addressOn(String address, String domain) {
        if (address == null) {
            return false;
        }
        String a = address.strip();
        int at = a.indexOf('@');
        if (at <= 0 || at != a.lastIndexOf('@') || a.chars().anyMatch(Character::isWhitespace)) {
            return false;
        }
        Optional<String> host = domain(a.substring(at + 1));
        return host.isPresent() && onDomain(host.get(), domain);
    }

    /** An address as compared: trimmed, lower case. */
    public static String address(String address) {
        return address == null ? null : address.strip().toLowerCase(Locale.ROOT);
    }

    /** Whether a Sponsor whose submissions have these statuses is verified now: one of them is approved. */
    public static boolean verified(Collection<?> statuses) {
        return statuses != null && statuses.contains(QbVerifications.APPROVED);
    }

    /** Whether a submission in this state may be withdrawn by an administrator: only a verification in effect. */
    public static boolean revocable(Object status) {
        return QbVerifications.APPROVED.equals(status);
    }

    /** The statuses that end when the Sponsor submits again: one in progress is withdrawn, one approved superseded. */
    public static String endedBy(Object status) {
        if (QbVerifications.OPEN.contains(String.valueOf(status))) {
            return QbVerifications.WITHDRAWN;
        }
        return QbVerifications.APPROVED.equals(status) ? QbVerifications.SUPERSEDED : null;
    }

    /** The states of a submission that stands (in progress, or in effect). */
    public static final List<String> STANDING = List.of(QbVerifications.LINK_SENT, QbVerifications.REVIEW,
        QbVerifications.APPROVED);
}
