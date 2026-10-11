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
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The declared external services as this environment runs them (decision D38): each {@link ExternalService} bean with
 * the address and hosts of {@code jabiz.external-services.<name>.base-url | allowed-hosts} where they are set, and
 * the secrets read once from the environment. Nothing is refused here: settings are read as text and converted here,
 * and {@link ExternalChecks} reports every problem at startup, so one bad setting does not hide the others.
 *
 * <p>{@code jabiz.external.missing-secrets} says what a missing secret is: {@code error} (the default), {@code warning}
 * (the default of the {@code dev} profile; the call that needs it fails) or {@code ignore} (the static check, which
 * runs without the deployment's environment).
 */
@Component
public class ExternalServiceRegistry {

    static final String OVERRIDES = "jabiz.external-services";
    static final String MISSING_SECRETS = "jabiz.external.missing-secrets";

    /** What a missing secret is. */
    enum MissingSecrets { ERROR, WARNING, IGNORE }

    /** One service's settings of the environment, as written. */
    record Settings(String baseUrl, List<String> allowedHosts) {}

    /** A setting that could not be read or converted: where, and why. */
    record SettingProblem(String location, String message) {}

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
    private final List<SettingProblem> settingProblems = new ArrayList<>();
    private final Map<String, Map<String, Secret>> secrets;
    private final Set<String> presentVariables;
    private final MissingSecrets missingSecrets;

    @Autowired
    public ExternalServiceRegistry(ObjectProvider<ExternalService> services, Environment environment) {
        this(services.orderedStream().toList(), environment);
    }

    public ExternalServiceRegistry(List<ExternalService> services, Environment environment) {
        this.overrides = bindOverrides(environment);
        this.missingSecrets = missingSecrets(environment);
        List<ExternalService> effective = new ArrayList<>();
        Map<String, ExternalService> named = new LinkedHashMap<>();
        Map<String, Map<String, Secret>> values = new HashMap<>();
        Set<String> present = new HashSet<>();
        for (ExternalService service : services) {
            Settings settings = service.name() == null ? null : overrides.get(service.name());
            ExternalService applied = settings == null ? service
                : service.withOverrides(baseUrl(service.name(), settings.baseUrl()), settings.allowedHosts());
            effective.add(applied);
            applied.secrets().values().forEach(variable -> {
                String value = variable == null ? null : environment.getProperty(variable);
                if (value != null && !value.isBlank()) {
                    present.add(variable);
                }
            });
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
        this.presentVariables = Set.copyOf(present);
    }

    private Map<String, Settings> bindOverrides(Environment environment) {
        try {
            return Binder.get(environment).bind(OVERRIDES, Bindable.mapOf(String.class, Settings.class))
                .map(Map::copyOf).orElse(Map.of());
        } catch (RuntimeException e) {
            settingProblems.add(new SettingProblem(OVERRIDES, "cannot be read: " + rootMessage(e)));
            return Map.of();
        }
    }

    private MissingSecrets missingSecrets(Environment environment) {
        String setting = environment.getProperty(MISSING_SECRETS);
        if (setting == null || setting.isBlank()) {
            return environment.acceptsProfiles(Profiles.of("dev")) ? MissingSecrets.WARNING : MissingSecrets.ERROR;
        }
        try {
            return MissingSecrets.valueOf(setting.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            settingProblems.add(new SettingProblem(MISSING_SECRETS, "must be error, warning or ignore"));
            return MissingSecrets.ERROR;
        }
    }

    /** The configured address; null (the declared one is kept) when absent or not an address, which is reported. */
    private URI baseUrl(String service, String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            URI url = new URI(raw.trim());
            if (url.isAbsolute()) {
                return url;
            }
        } catch (URISyntaxException e) {
            // reported below
        }
        // The value is not repeated: an address may carry credentials.
        settingProblems.add(new SettingProblem(OVERRIDES + "." + service + ".base-url",
            "not an absolute address; the declared one is kept"));
        return null;
    }

    private static String rootMessage(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getClass().getSimpleName();
    }

    /** Every declaration as this environment runs it, duplicates included, in bean order. */
    List<ExternalService> declared() {
        return declared;
    }

    /** The environment's settings, by service name, including names no service declares. */
    Map<String, Settings> overrides() {
        return overrides;
    }

    /** Settings that could not be read or converted. */
    List<SettingProblem> settingProblems() {
        return List.copyOf(settingProblems);
    }

    MissingSecrets missingSecrets() {
        return missingSecrets;
    }

    /** Whether the environment sets this variable (to something not blank). */
    boolean isSet(String variable) {
        return presentVariables.contains(variable);
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
