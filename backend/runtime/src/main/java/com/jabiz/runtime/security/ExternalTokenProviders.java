package com.jabiz.runtime.security;

import com.jabiz.runtime.check.CheckProblem;
import com.jabiz.runtime.check.PlatformCheck;
import com.jabiz.runtime.web.JabizWebProperties;
import com.jabiz.runtime.web.SpaEmbedding;
import com.jabiz.security.ExternalTokenRules;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Supplier;

/**
 * The configured external token providers (decision D59; docs/design/10-security.md section 15.12) and their startup
 * check: every problem at once; only providers without problems are used. The entries and the SPAs naming a provider
 * are checked here too: it must exist, and an SPA it embeds signs in at an entry that takes its tokens. Who may frame
 * such an SPA ({@link SpaEmbedding}): the tenant named by the page's tenant parameter, at its {@code dest} origin, and
 * the provider's other {@code frame-ancestors}.
 */
@Component
public class ExternalTokenProviders implements PlatformCheck, SpaEmbedding {

    static final String PROPERTY = "jabiz.security.external-tokens";

    /** A provider as configured; the name is the map's key. */
    public record Config(List<String> secrets, String audience, String tenantPattern, String issuerFormat,
        String destinationFormat, Duration skew, Boolean autoProvision, List<String> frameAncestors,
        String tenantParameter) {}

    private final Map<String, ExternalTokenProvider> configured;
    private final Map<String, ExternalTokenProvider> usable = new LinkedHashMap<>();
    private final Supplier<SignInEntries> entries;
    private final Supplier<JabizWebProperties> web;

    @Autowired
    public ExternalTokenProviders(Environment environment, ObjectProvider<SignInEntries> entries,
        ObjectProvider<JabizWebProperties> web) {
        this(Binder.get(environment).bind(PROPERTY, Bindable.mapOf(String.class, Config.class)).orElse(Map.of()),
            entries::getIfAvailable, web::getIfAvailable);
    }

    ExternalTokenProviders(Map<String, Config> configs, Supplier<SignInEntries> entries,
        Supplier<JabizWebProperties> web) {
        this.entries = entries;
        this.web = web;
        Map<String, ExternalTokenProvider> providers = new TreeMap<>();
        configs.forEach((name, c) -> providers.put(name, new ExternalTokenProvider(name, c.secrets(), c.audience(),
            c.tenantPattern(), c.issuerFormat(), c.destinationFormat(), c.skew(),
            Boolean.TRUE.equals(c.autoProvision()), c.frameAncestors(), c.tenantParameter())));
        this.configured = providers;
        providers.values().stream().filter(p -> p.problems().isEmpty()).forEach(p -> usable.put(p.name(), p));
    }

    public Optional<ExternalTokenProvider> find(String name) {
        return Optional.ofNullable(name == null ? null : usable.get(name));
    }

    @Override
    public Optional<String> tenantParameter(String provider) {
        return find(provider).map(ExternalTokenProvider::tenantParameter);
    }

    @Override
    public Optional<String> frameAncestors(String provider, String tenant) {
        return find(provider).filter(p -> ExternalTokenRules.isTenant(p.settings(), tenant))
            .flatMap(p -> p.frameAncestorsOf(tenant));
    }

    @Override
    public List<CheckProblem> check() {
        List<CheckProblem> problems = new ArrayList<>();
        configured.values().forEach(provider -> provider.problems().forEach(problem -> problems.add(
            CheckProblem.error(SessionChecks.CATEGORY, PROPERTY + "." + provider.name(), problem))));
        SignInEntries signInEntries = entries.get();
        if (signInEntries != null) {
            signInEntries.all().stream().filter(entry -> entry.externalToken() != null).forEach(entry -> {
                if (!configured.containsKey(entry.externalToken())) {
                    problems.add(CheckProblem.error(SessionChecks.CATEGORY, SignInEntries.PROPERTY + "."
                        + entry.name() + ".external-token", "names no configured provider: " + entry.externalToken()));
                }
            });
        }
        JabizWebProperties properties = web.get();
        if (properties != null) {
            for (int i = 0; i < properties.spa().size(); i++) {
                JabizWebProperties.Spa spa = properties.spa().get(i);
                if (spa.embedding() == null) {
                    continue;
                }
                String where = "jabiz.web.spa[" + i + "].embedding";
                SignInEntries.Entry entry = signInEntries == null || spa.entry() == null ? null
                    : signInEntries.find(spa.entry()).orElse(null);
                if (!configured.containsKey(spa.embedding())) {
                    problems.add(CheckProblem.error(SessionChecks.CATEGORY, where,
                        "names no configured provider: " + spa.embedding()));
                } else if (entry == null || !spa.embedding().equals(entry.externalToken())) {
                    problems.add(CheckProblem.error(SessionChecks.CATEGORY, where, "the SPA's entry ("
                        + spa.entry() + ") must take the tokens of " + spa.embedding()
                        + " (jabiz.security.entries.<entry>.external-token)"));
                }
            }
        }
        return problems;
    }
}
