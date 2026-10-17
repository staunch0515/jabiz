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
 *       {@value #DIRECT_EXPOSURE} declares that there is no proxy. A range of every address (prefix 0, such as
 *       {@code 0.0.0.0/0} or {@code ::/0}) lets anybody choose their address: always an error.</li>
 *   <li>Administration without a second factor ({@code jabiz.security.mfa.administration=false}, for the end-to-end
 *       tests and the local demonstration): an error in live mode.</li>
 *   <li>The {@code dev} profile (actor headers: anybody is anybody) only listening on a loopback address
 *       ({@code server.address}) and with mail links to this machine ({@code jabiz.mail.base-url}).</li>
 * </ul>
 */
@Component
class DeploySafetyCheck implements PlatformCheck {

    static final String TRUSTED_PROXIES = "jabiz.security.trusted-proxies";
    static final String DIRECT_EXPOSURE = "quizbuks.deploy.direct-exposure";
    static final String MFA_ADMINISTRATION = "jabiz.security.mfa.administration";
    static final String SERVER_ADDRESS = "server.address";
    static final String MAIL_BASE_URL = "jabiz.mail.base-url";

    private static final Pattern IPV4 = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");

    private final List<String> trustedProxies;
    private final boolean directExposure;
    private final boolean mfaAdministration;
    private final boolean dev;
    private final String serverAddress;
    private final String mailBaseUrl;
    private final boolean live;

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
    }

    @Override
    public List<CheckProblem> check() {
        List<CheckProblem> problems = new ArrayList<>(proxyProblems(trustedProxies, directExposure, live));
        problems.addAll(mfaProblems(mfaAdministration, live));
        problems.addAll(devProblems(dev, serverAddress, mailBaseUrl));
        return problems;
    }

    static List<CheckProblem> proxyProblems(List<String> proxies, boolean directExposure, boolean live) {
        List<String> given = proxies.stream().map(String::strip).filter(proxy -> !proxy.isEmpty()).toList();
        List<String> everyone = given.stream().filter(DeploySafetyCheck::everyAddress).toList();
        if (!everyone.isEmpty()) {
            return List.of(CheckProblem.error(CountryCatalog.CATEGORY, TRUSTED_PROXIES, "The trusted proxies include "
                + "every address (" + String.join(", ", everyone) + ", JABIZ_TRUSTED_PROXIES): anybody could choose "
                + "the address the sign-in limits and records see; list the reverse proxy's own addresses"));
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

    /** A range with prefix 0 ({@code 0.0.0.0/0}, {@code ::/0}, or any address over 0 bits). */
    static boolean everyAddress(String proxy) {
        int slash = proxy.indexOf('/');
        return slash >= 0 && proxy.substring(slash + 1).strip().matches("0+");
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
