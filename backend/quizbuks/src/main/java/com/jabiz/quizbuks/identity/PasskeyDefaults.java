package com.jabiz.quizbuks.identity;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * The passkeys' defaults of a QuizBuks deployment (docs/quizbuks/plans/Q97-passkeys.md, D-Q97-2, D-Q97-3): the relying
 * party and the origin are the deployment's own host, taken from its root address ({@code jabiz.mail.base-url}, which
 * every deployment sets) unless {@code QB_PASSKEY_RP_ID} and {@code QB_PASSKEY_ORIGINS} give them; the entries take
 * passkeys ({@code quizbuks.passkeys.default}) when there is a relying party. Only defaults, added last: what the
 * deployment sets wins, and {@code QB_PASSKEYS} turns the entries on or off whatever these say.
 *
 * <p>Without a host to take (a development run without mail), passkeys are simply off rather than the startup failing
 * on the platform's check of an entry that takes them without a relying party.
 */
public class PasskeyDefaults implements EnvironmentPostProcessor, Ordered {

    static final String SOURCE = "quizbuksPasskeyDefaults";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        environment.getPropertySources().addLast(new MapPropertySource(SOURCE, Map.copyOf(defaults(name -> {
            try {
                return environment.getProperty(name);
            } catch (IllegalArgumentException unresolvable) {
                return null;
            }
        }))));
    }

    /** After the configuration files are read, so that the root address is known. */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    /** The defaults given the deployment's settings ({@code property} reads one, null when not set). */
    static Map<String, String> defaults(UnaryOperator<String> property) {
        Map<String, String> defaults = new LinkedHashMap<>();
        String rpId = blankToNull(property.apply("QB_PASSKEY_RP_ID"));
        String origins = blankToNull(property.apply("QB_PASSKEY_ORIGINS"));
        boolean relyingParty = rpId != null && origins != null;
        if (rpId == null && origins == null) {
            String[] own = hostOf(blankToNull(property.apply("jabiz.mail.base-url")));
            if (own != null) {
                defaults.put("quizbuks.passkeys.rp-id", own[0]);
                defaults.put("quizbuks.passkeys.origins", own[1]);
                relyingParty = true;
            }
        }
        defaults.put("quizbuks.passkeys.default", String.valueOf(relyingParty));
        return defaults;
    }

    /**
     * The host and origin of a root address ({@code https://host[:port]}, a trailing slash allowed; {@code http} only
     * as the platform allows it, for localhost); null for anything else, which is no relying party to guess.
     */
    static String[] hostOf(String address) {
        if (address == null) {
            return null;
        }
        URI uri;
        try {
            uri = new URI(address);
        } catch (URISyntaxException e) {
            return null;
        }
        String scheme = uri.getScheme() == null ? null : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost() == null ? null : uri.getHost().toLowerCase(Locale.ROOT);
        String path = uri.getRawPath();
        if (host == null || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
            || (path != null && !path.isEmpty() && !"/".equals(path))
            || !("https".equals(scheme) || ("http".equals(scheme) && "localhost".equals(host)))) {
            return null;
        }
        String origin = scheme + "://" + host + (uri.getPort() == -1 ? "" : ":" + uri.getPort());
        return new String[] {host, origin};
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
