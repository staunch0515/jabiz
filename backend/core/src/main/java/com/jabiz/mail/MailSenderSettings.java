package com.jabiz.mail;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * An SMTP server a mail is sent through instead of the platform's (docs/design/18-numbering-approvals-tasks.md
 * section 5.6, decision D56): a merchant's own server, say, so that their invitations leave in their name. An
 * application keeps these as an entity of its own and hands them to the platform through a {@link MailSenderSource};
 * the password is stored sealed ({@code SealedSecrets}) and opened by the delivery alone.
 *
 * <p>{@link #problems} says what the platform refuses to send through: a host that is no public name, a port other
 * than the mail ports, no TLS, no credentials, or a sender address that is none. A lenient check (tests only) allows
 * local hosts and any port.
 *
 * @param host           the server's host name (a public DNS name; never an address)
 * @param port           one of {@link #PORTS}
 * @param tls            how the connection is secured; required
 * @param username       the login
 * @param sealedPassword the password as {@code SealedSecrets} stored it; never the password itself
 * @param fromAddress    the address the mail is from
 * @param fromName       the name shown with it; may be null
 */
public record MailSenderSettings(String host, int port, Tls tls, String username, String sealedPassword,
    String fromAddress, String fromName) {

    /** How the connection is secured: TLS from the start, or upgraded with STARTTLS (required, never optional). */
    public enum Tls { SMTPS, STARTTLS }

    /** The ports a mail server is reached on. */
    public static final Set<Integer> PORTS = Set.of(25, 465, 587, 2525);

    private static final Pattern HOST = Pattern.compile("(?=.{1,253}$)([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}");
    private static final Pattern LOCAL_HOST = Pattern.compile("localhost|[a-z0-9.-]*\\.(local|localhost|internal|lan|home|test|example|invalid)");
    /** Any host name, with or without a domain: what a lenient check (tests) accepts. */
    private static final Pattern ANY_HOST = Pattern.compile("[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)*");
    private static final Pattern ADDRESS = Pattern.compile("[^\\s@<>()\\[\\],;:\"]{1,64}@[a-z0-9.-]{1,253}");
    public static final int MAX_NAME = 100;

    public MailSenderSettings {
        host = host == null ? null : host.strip().toLowerCase(java.util.Locale.ROOT);
        username = username == null ? null : username.strip();
        fromAddress = fromAddress == null ? null : fromAddress.strip();
        fromName = fromName == null || fromName.isBlank() ? null : fromName.strip();
    }

    /**
     * Why the platform will not send through these settings; none when it will.
     *
     * @param lenient whether a local host and any port (tests against a mail server on this machine) pass
     */
    public List<String> problems(boolean lenient) {
        List<String> problems = new ArrayList<>();
        if (host == null || host.isBlank()) {
            problems.add("host is required");
        } else if (lenient ? !ANY_HOST.matcher(host).matches()
            : !HOST.matcher(host).matches() || LOCAL_HOST.matcher(host).matches()) {
            problems.add("host must be a public host name, not " + host);
        }
        if (lenient ? port < 1 || port > 65535 : !PORTS.contains(port)) {
            problems.add("port must be one of " + PORTS.stream().sorted().toList() + ", not " + port);
        }
        if (tls == null) {
            problems.add("tls is required: SMTPS or STARTTLS");
        }
        if (username == null || username.isBlank()) {
            problems.add("username is required");
        }
        if (sealedPassword == null || sealedPassword.isBlank()) {
            problems.add("password is required");
        }
        if (fromAddress == null || !ADDRESS.matcher(fromAddress.toLowerCase(java.util.Locale.ROOT)).matches()) {
            problems.add("fromAddress must be an e-mail address");
        }
        if (fromName != null && fromName.length() > MAX_NAME) {
            problems.add("fromName is longer than " + MAX_NAME + " characters");
        }
        return List.copyOf(problems);
    }

    /** As {@link #problems(boolean)} without leniency. */
    public List<String> problems() {
        return problems(false);
    }

    /** The sealed password is a secret of the delivery: shown as such. */
    @Override
    public String toString() {
        return "MailSenderSettings[host=" + host + ", port=" + port + ", tls=" + tls + ", username=" + username
            + ", sealedPassword=***, fromAddress=" + fromAddress + ", fromName=" + fromName + "]";
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof MailSenderSettings that && Objects.equals(host, that.host) && port == that.port
            && tls == that.tls && Objects.equals(username, that.username)
            && Objects.equals(sealedPassword, that.sealedPassword) && Objects.equals(fromAddress, that.fromAddress)
            && Objects.equals(fromName, that.fromName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(host, port, tls, username, sealedPassword, fromAddress, fromName);
    }
}
