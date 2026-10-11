package com.jabiz.runtime.external;

import com.jabiz.external.ExternalService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The declared external services as this environment runs them (decision D38): each {@link ExternalService} bean with
 * the address and hosts of {@code jabiz.external-services.<name>.base-url | allowed-hosts} where they are set, and
 * the secrets read once from the environment. Nothing is refused here: {@link ExternalChecks} reports every problem
 * at startup, and the application does not start with an error.
 *
 * <p>A missing secret is an error, except in the {@code dev} profile and with {@code jabiz.external.missing-secrets
 * = warning} (the static check, which runs without the deployment's environment): there it is a warning and the call
 * that needs it fails.
 */
@Component
public class ExternalServiceRegistry {

    static final String OVERRIDES = "jabiz.external-services";
    static final String MISSING_SECRETS = "jabiz.external.missing-secrets";

    /** One service's settings of the environment. */
    record Settings(URI baseUrl, List<String> allowedHosts) {}

    /** A secret's value: printed as {@code ***}. */
    private record Secret(String value) {
        @Override
        public String toString() {
            return "***";
        }
    }

    private final List<ExternalService> declared;
    private final Map<String, ExternalService> byName;
    private final Map<String, Settings> overrides;
    private final Map<String, Map<String, Secret>> secrets;
    private final boolean missingSecretsAreWarnings;

    @Autowired
    public ExternalServiceRegistry(ObjectProvider<ExternalService> services, Environment environment) {
        this(services.orderedStream().toList(), environment);
    }

    public ExternalServiceRegistry(List<ExternalService> services, Environment environment) {
        this.overrides = Binder.get(environment)
            .bind(OVERRIDES, Bindable.mapOf(String.class, Settings.class))
            .map(Map::copyOf).orElse(Map.of());
        List<ExternalService> effective = new ArrayList<>();
        Map<String, ExternalService> named = new LinkedHashMap<>();
        Map<String, Map<String, Secret>> values = new HashMap<>();
        for (ExternalService service : services) {
            Settings override = service.name() == null ? null : overrides.get(service.name());
            ExternalService applied = override == null ? service
                : service.withOverrides(override.baseUrl(), override.allowedHosts());
            effective.add(applied);
            if (applied.name() != null && !named.containsKey(applied.name())) {
                named.put(applied.name(), applied);
                Map<String, Secret> read = new LinkedHashMap<>();
                applied.secrets().forEach((secret, variable) -> {
                    String value = variable == null ? null : environment.getProperty(variable);
                    if (value != null && !value.isBlank()) {
                        read.put(secret, new Secret(value));
                    }
                });
                values.put(applied.name(), Collections.unmodifiableMap(read));
            }
        }
        this.declared = List.copyOf(effective);
        this.byName = Collections.unmodifiableMap(named);
        this.secrets = Map.copyOf(values);
        this.missingSecretsAreWarnings = environment.acceptsProfiles(Profiles.of("dev"))
            || "warning".equalsIgnoreCase(environment.getProperty(MISSING_SECRETS, "error").trim());
    }

    /** Every declaration as this environment runs it, duplicates included, in bean order. */
    List<ExternalService> declared() {
        return declared;
    }

    /** The environment's settings, by service name, including names no service declares. */
    Map<String, Settings> overrides() {
        return overrides;
    }

    boolean missingSecretsAreWarnings() {
        return missingSecretsAreWarnings;
    }

    /** The service of that name (the first declaration of it). */
    public Optional<ExternalService> find(String name) {
        return Optional.ofNullable(byName.get(name));
    }

    /** A secret's value; empty when the service or the secret is not declared, or the environment lacks it. */
    Optional<String> secret(String service, String name) {
        Map<String, Secret> values = secrets.get(service);
        Secret secret = values == null ? null : values.get(name);
        return secret == null ? Optional.empty() : Optional.of(secret.value());
    }

    @Override
    public String toString() {
        return "ExternalServiceRegistry" + byName.keySet();
    }
}
