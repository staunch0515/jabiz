package com.jabiz.quizbuks.wallet;

import com.jabiz.quizbuks.country.CountryCatalog;
import com.jabiz.runtime.check.CheckProblem;
import com.jabiz.runtime.check.PlatformCheck;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The deployment's safety settings (Q10b, reviews M6, L1 and L5; deploy/quizbuks/README.md, "密钥与部署"), at
 * startup and in {@code platformCheck} (category {@code QUIZBUKS}). Live means Stripe's live mode or a live key, as for
 * {@link TransferScheduleCheck}.
 * <ul>
 *   <li>Trusted proxies ({@code jabiz.security.trusted-proxies}, {@code JABIZ_TRUSTED_PROXIES}): behind a reverse proxy
 *       the platform takes the client's address from the proxy's headers only when the proxy is listed, so sign-in
 *       limits and records see the real address. None listed is an error in live mode and a warning otherwise, unless
 *       {@value #DIRECT_EXPOSURE} declares that there is no proxy. A range wider than any proxy's lets anybody choose
 *       their address: always an error (review L6): an IPv4 prefix below /8, an IPv6 prefix below /32, and an IPv6
 *       range that holds a whole translation of IPv4 ({@code ::ffff:0:0/96}, {@code ::/96}, {@code 64:ff9b::/96},
 *       {@code 64:ff9b:1::/48}). The prefix is read as the platform reads it ({@code Integer.parseInt}: {@code +0} and
 *       {@code 00} are 0); one that is not a number in range is an error too.</li>
 *   <li>Administration without a second factor ({@code jabiz.security.mfa.administration=false}, for the end-to-end
 *       tests and the local demonstration): an error in live mode.</li>
 *   <li>The {@code dev} profile (actor headers: anybody is anybody) only listening on a loopback address
 *       ({@code server.address}) and with mail links to this machine ({@code jabiz.mail.base-url}).</li>
 *   <li>Secrets the image's entrypoint generated ({@value #SECRETS_GENERATED}{@code =true}, review L5: a local
 *       demonstration's, kept in a volume): an error in live mode.</li>
 * </ul>
 */
@Component
class DeploySafetyCheck implements PlatformCheck {

    static final String TRUSTED_PROXIES = "jabiz.security.trusted-proxies";
    static final String DIRECT_EXPOSURE = "quizbuks.deploy.direct-exposure";
    static final String MFA_ADMINISTRATION = "jabiz.security.mfa.administration";
    static final String SERVER_ADDRESS = "server.address";
    static final String MAIL_BASE_URL = "jabiz.mail.base-url";
    /** Set by deploy/quizbuks/entrypoint.sh when it let the platform's keys or the first password be generated. */
    static final String SECRETS_GENERATED = "QB_SECRETS_GENERATED";

    /** The narrowest IPv4 and IPv6 prefixes that are still wider than any proxy's. */
    static final int MIN_IPV4_PREFIX = 8;
    static final int MIN_IPV6_PREFIX = 32;

    /** An IPv6 range, as its 16 bytes and its prefix length. */
    private record Range(byte[] network, int prefix) {}

    /** IPv6 ranges that translate the whole IPv4 space: mapped, compatible, NAT64 and its local use. */
    private static final List<Range> IPV4_TRANSLATIONS = List.of(
        new Range(bytes(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xff, 0xff), 96),
        new Range(bytes(), 96),
        new Range(bytes(0, 0x64, 0xff, 0x9b), 96),
        new Range(bytes(0, 0x64, 0xff, 0x9b, 0, 1), 48));

    /** 16 bytes: the given ones, then zeros. */
    private static byte[] bytes(int... leading) {
        byte[] all = new byte[16];
        for (int i = 0; i < leading.length; i++) {
            all[i] = (byte) leading[i];
        }
        return all;
    }

    private static final Pattern IPV4 = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");

    private final List<String> trustedProxies;
    private final boolean directExposure;
    private final boolean mfaAdministration;
    private final boolean dev;
    private final String serverAddress;
    private final String mailBaseUrl;
    private final boolean live;
    private final boolean secretsGenerated;

    DeploySafetyCheck(StripeSettings settings, @Value("${" + StripeService.API_KEY + ":}") String apiKey,
        Environment environment) {
        this.trustedProxies = Binder.get(environment).bind(TRUSTED_PROXIES, Bindable.listOf(String.class))
            .orElse(List.of());
        this.directExposure = environment.getProperty(DIRECT_EXPOSURE, Boolean.class, false);
        this.mfaAdministration = environment.getProperty(MFA_ADMINISTRATION, Boolean.class, true);
        this.dev = environment.acceptsProfiles(Profiles.of("dev"));
        this.serverAddress = environment.getProperty(SERVER_ADDRESS);
        this.mailBaseUrl = environment.getProperty(MAIL_BASE_URL);
        this.live = settings.livemode() || apiKey != null && StripeModeCheck.LIVE_KEY.matcher(apiKey).lookingAt();
        this.secretsGenerated = "true".equalsIgnoreCase(String.valueOf(environment.getProperty(SECRETS_GENERATED,
            "false")).strip());
    }

    @Override
    public List<CheckProblem> check() {
        List<CheckProblem> problems = new ArrayList<>(proxyProblems(trustedProxies, directExposure, live));
        problems.addAll(mfaProblems(mfaAdministration, live));
        problems.addAll(devProblems(dev, serverAddress, mailBaseUrl));
        problems.addAll(secretProblems(secretsGenerated, live));
        return problems;
    }

    static List<CheckProblem> secretProblems(boolean generated, boolean live) {
        if (!generated || !live) {
            return List.of();
        }
        return List.of(CheckProblem.error(CountryCatalog.CATEGORY, SECRETS_GENERATED, "The platform's keys or the "
            + "first administrator's password were generated by the image's entrypoint (QB_LOCAL_SECRETS=true, for a "
            + "local demonstration) next to Stripe's live mode or a live key: give every secret from the secret store"));
    }

    static List<CheckProblem> proxyProblems(List<String> proxies, boolean directExposure, boolean live) {
        List<String> given = proxies.stream().map(String::strip).filter(proxy -> !proxy.isEmpty()).toList();
        List<String> everyone = given.stream().filter(DeploySafetyCheck::tooWide).toList();
        if (!everyone.isEmpty()) {
            return List.of(CheckProblem.error(CountryCatalog.CATEGORY, TRUSTED_PROXIES, "The trusted proxies include "
                + "ranges wider than a proxy's, or that are not ranges (" + String.join(", ", everyone)
                + ", JABIZ_TRUSTED_PROXIES; at least /" + MIN_IPV4_PREFIX + " for IPv4 and /" + MIN_IPV6_PREFIX
                + " for IPv6, no whole IPv4 translation): anybody could choose the address the sign-in limits and "
                + "records see; list the reverse proxy's own addresses"));
        }
        if (!given.isEmpty() || directExposure) {
            return List.of();
        }
        String message = "No trusted proxy is listed (JABIZ_TRUSTED_PROXIES): behind a reverse proxy every client "
            + "looks like the proxy to the sign-in limits and records. List the proxy's addresses, or set "
            + DIRECT_EXPOSURE + "=true (QB_DIRECT_EXPOSURE) when no proxy is in front";
        return List.of(live ? CheckProblem.error(CountryCatalog.CATEGORY, TRUSTED_PROXIES, message)
            : CheckProblem.warning(CountryCatalog.CATEGORY, TRUSTED_PROXIES, message));
    }

    /**
     * Whether an entry is a range wider than any proxy's, or not a range the platform reads (see the class): parsed as
     * {@code ClientAddresses} parses it, the address a literal only (never a lookup).
     */
    static boolean tooWide(String proxy) {
        String value = proxy.strip();
        int slash = value.indexOf('/');
        String host = slash < 0 ? value : value.substring(0, slash).strip();
        InetAddress address = literal(host);
        if (address == null) {
            return true;
        }
        boolean v4 = address.getAddress().length == 4;
        int bits = v4 ? 32 : 128;
        int prefix = bits;
        if (slash >= 0) {
            try {
                prefix = Integer.parseInt(value.substring(slash + 1).strip());
            } catch (NumberFormatException e) {
                return true;
            }
        }
        if (v4 && host.contains(":")) {
            // An IPv4-mapped address (::ffff:a.b.c.d): its prefix may count the 96 bits before the IPv4 part.
            prefix = prefix > 32 ? prefix - 96 : prefix;
        }
        if (prefix < 0 || prefix > bits) {
            return true;
        }
        if (v4) {
            return prefix < MIN_IPV4_PREFIX;
        }
        if (prefix < MIN_IPV6_PREFIX) {
            return true;
        }
        byte[] network = address.getAddress();
        for (Range translation : IPV4_TRANSLATIONS) {
            if (prefix <= translation.prefix() && samePrefix(network, translation.network(), prefix)) {
                return true;
            }
        }
        return false;
    }

    /** Whether the first {@code bits} bits of two addresses are the same. */
    private static boolean samePrefix(byte[] a, byte[] b, int bits) {
        for (int i = 0; i < bits; i++) {
            int mask = 0x80 >>> (i % 8);
            if ((a[i / 8] & mask) != (b[i / 8] & mask)) {
                return false;
            }
        }
        return true;
    }

    /** An IP address literal, or null: IPv4 dotted quads and IPv6 with a colon, as the platform reads them. */
    private static InetAddress literal(String text) {
        String plain = text.startsWith("[") && text.endsWith("]") ? text.substring(1, text.length() - 1) : text;
        boolean v4 = IPV4.matcher(plain).matches();
        if (!v4 && !(plain.contains(":") && plain.matches("[0-9A-Fa-f:.]+"))) {
            return null;
        }
        if (v4) {
            for (String octet : plain.split("\\.")) {
                if (Integer.parseInt(octet) > 255) {
                    return null;
                }
            }
        }
        try {
            return InetAddress.getByName(plain);
        } catch (UnknownHostException e) {
            return null;
        }
    }

    static List<CheckProblem> mfaProblems(boolean mfaAdministration, boolean live) {
        if (mfaAdministration || !live) {
            return List.of();
        }
        return List.of(CheckProblem.error(CountryCatalog.CATEGORY, MFA_ADMINISTRATION, "Administration without a "
            + "second factor (JABIZ_SECURITY_MFA_ADMINISTRATION=false) next to Stripe's live mode or a live key: it is "
            + "for the end-to-end tests and the local demonstration only"));
    }

    static List<CheckProblem> devProblems(boolean dev, String serverAddress, String mailBaseUrl) {
        if (!dev) {
            return List.of();
        }
        List<CheckProblem> problems = new ArrayList<>();
        if (serverAddress == null || serverAddress.isBlank() || !loopback(serverAddress)) {
            problems.add(CheckProblem.error(CountryCatalog.CATEGORY, SERVER_ADDRESS, "The dev profile lets every "
                + "request name its actor: it listens on a loopback address only (server.address is "
                + (serverAddress == null || serverAddress.isBlank() ? "every address" : serverAddress) + ")"));
        }
        if (mailBaseUrl != null && !mailBaseUrl.isBlank() && !loopbackUrl(mailBaseUrl)) {
            problems.add(CheckProblem.error(CountryCatalog.CATEGORY, MAIL_BASE_URL, "The dev profile sends mail "
                + "links to this machine only (jabiz.mail.base-url is " + mailBaseUrl + ")"));
        }
        return problems;
    }

    private static boolean loopbackUrl(String url) {
        try {
            String host = URI.create(url.strip()).getHost();
            return host != null && loopback(host);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** Whether the host is this machine: {@code localhost}, or a loopback address written as one (no lookup). */
    static boolean loopback(String host) {
        String plain = host.strip();
        if (plain.startsWith("[") && plain.endsWith("]")) {
            plain = plain.substring(1, plain.length() - 1);
        }
        if (plain.toLowerCase(Locale.ROOT).equals("localhost")) {
            return true;
        }
        if (!IPV4.matcher(plain).matches() && !plain.contains(":")) {
            return false;
        }
        try {
            return InetAddress.getByName(plain).isLoopbackAddress();
        } catch (UnknownHostException e) {
            return false;
        }
    }
}
