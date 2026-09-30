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

    @org.springframework.beans.factory.annotation.Autowired
    public OidcProviders(Environment environment) {
        this(Binder.get(environment).bind(PROPERTY, Bindable.listOf(OidcProvider.class)).orElse(List.of()));
    }

    OidcProviders(List<OidcProvider> configured) {
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
        }
        return problems;
    }
}
