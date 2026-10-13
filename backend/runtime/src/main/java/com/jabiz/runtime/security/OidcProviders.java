package com.jabiz.runtime.security;

import com.jabiz.runtime.check.CheckProblem;
import com.jabiz.runtime.check.PlatformCheck;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The configured OpenID Connect providers (docs/design/10-security.md section 12) and their startup check: every
 * problem of every provider is reported at once; only providers without problems are offered.
 */
@Component
public class OidcProviders implements PlatformCheck {

    static final String PROPERTY = "jabiz.security.oidc.providers";

    private final List<OidcProvider> configured;
    private final Map<String, OidcProvider> usable = new LinkedHashMap<>();
    /** The sign-in entries, for the warning about providers that could open accounts nowhere; null: not checked. */
    private final java.util.function.Supplier<SignInEntries> entries;

    @org.springframework.beans.factory.annotation.Autowired
    public OidcProviders(Environment environment,
        org.springframework.beans.factory.ObjectProvider<SignInEntries> entries) {
        this(Binder.get(environment).bind(PROPERTY, Bindable.listOf(OidcProvider.class)).orElse(List.of()),
            entries::getIfAvailable);
    }

    OidcProviders(List<OidcProvider> configured) {
        this(configured, () -> null);
    }

    OidcProviders(List<OidcProvider> configured, java.util.function.Supplier<SignInEntries> entries) {
        this.entries = entries;
        this.configured = List.copyOf(configured);
        Set<String> ids = new HashSet<>();
        for (OidcProvider provider : this.configured) {
            if (provider.problems().isEmpty() && ids.add(provider.id())) {
                usable.put(provider.id(), provider);
            }
        }
    }

    public List<OidcProvider> all() {
        return List.copyOf(usable.values());
    }

    /** The providers offered to the sign-in entry {@code entry} (none: the administration): those with its callback. */
    public List<OidcProvider> offeredTo(String entry) {
        return usable.values().stream().filter(provider -> provider.offers(entry)).toList();
    }

    public Optional<OidcProvider> find(String id) {
        return Optional.ofNullable(id == null ? null : usable.get(id));
    }

    @Override
    public List<CheckProblem> check() {
        List<CheckProblem> problems = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < configured.size(); i++) {
            OidcProvider provider = configured.get(i);
            String location = PROPERTY + "[" + i + "]" + (provider.id() == null ? "" : " (" + provider.id() + ")");
            provider.problems().forEach(problem -> problems.add(CheckProblem.error(SessionChecks.CATEGORY, location,
                problem)));
            if (provider.id() != null && !ids.add(provider.id())) {
                problems.add(CheckProblem.error(SessionChecks.CATEGORY, location, "id is used more than once"));
            }
            SignInEntries known = entries.get();
            if (provider.autoProvision() && known != null && provisioningEntries(provider, known).isEmpty()) {
                problems.add(CheckProblem.warning(SessionChecks.CATEGORY, location, "auto-provision is on, but none "
                    + "of the entries offered the provider lets people register: it opens no accounts"));
            }
        }
        return problems;
    }

    /**
     * The entries where the provider opens accounts (decision D39 item 1): offered it (a callback there) and letting
     * people register. Never the administration, which cannot let people register (10 section 15.7).
     */
    static List<String> provisioningEntries(OidcProvider provider, SignInEntries entries) {
        return entries.all().stream()
            .filter(entry -> entry.selfRegistration() && provider.offers(entry.name()))
            .map(SignInEntries.Entry::name)
            .toList();
    }
}
