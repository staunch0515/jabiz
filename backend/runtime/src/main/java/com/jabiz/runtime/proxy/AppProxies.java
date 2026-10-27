package com.jabiz.runtime.proxy;

import com.jabiz.process.ProcessAccess;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.runtime.check.CheckProblem;
import com.jabiz.runtime.check.PlatformCheck;
import com.jabiz.runtime.process.ProcessRegistry;
import com.jabiz.security.AppProxyCaller;
import com.jabiz.security.MfaRequirement;
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
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * The configured app proxies (decision D60) and their startup check, every problem at once: a proxy with problems is
 * not served (its requests are 404). Every process declared {@code appProxy(name)} must name a configured proxy and
 * hold that proxy's permission alone ({@link AppProxyCaller#permission}): its callers have no other.
 */
@Component
public class AppProxies implements PlatformCheck {

    static final String PROPERTY = "jabiz.app-proxies";
    static final String CATEGORY = "APP_PROXY";

    /** A proxy as configured; the name is the map's key. */
    public record Config(List<String> secrets, String tenantParameter, String tenantPattern,
        String customerParameter, String visitorParameter, Duration maxAge) {}

    private final Map<String, AppProxy> configured;
    private final Map<String, AppProxy> usable = new LinkedHashMap<>();
    /** The usable proxies' tenant patterns, compiled once. */
    private final Map<String, Pattern> tenants = new LinkedHashMap<>();
    private final Supplier<ProcessRegistry> processes;

    @Autowired
    public AppProxies(Environment environment, ObjectProvider<ProcessRegistry> processes) {
        this(Binder.get(environment).bind(PROPERTY, Bindable.mapOf(String.class, Config.class)).orElse(Map.of()),
            processes::getIfAvailable);
    }

    AppProxies(Map<String, Config> configs, Supplier<ProcessRegistry> processes) {
        this.processes = processes;
        Map<String, AppProxy> proxies = new TreeMap<>();
        configs.forEach((name, c) -> proxies.put(name, new AppProxy(name, c.secrets(), c.tenantParameter(),
            c.tenantPattern(), c.customerParameter(), c.visitorParameter(), c.maxAge())));
        this.configured = proxies;
        proxies.values().stream().filter(p -> p.problems().isEmpty()).forEach(p -> {
            usable.put(p.name(), p);
            tenants.put(p.name(), Pattern.compile(p.tenantPattern()));
        });
    }

    /** The proxy of that name, if it is configured without problems. */
    public Optional<AppProxy> find(String name) {
        return Optional.ofNullable(name == null ? null : usable.get(name));
    }

    /** Whether {@code tenant} is one of the usable proxy {@code proxy}'s tenants. */
    public boolean isTenant(String proxy, String tenant) {
        Pattern pattern = tenants.get(proxy);
        return pattern != null && tenant != null && pattern.matcher(tenant).matches();
    }

    /**
     * The process {@code process} as {@code proxy} runs it: the latest version, declared {@code appProxy(proxy)};
     * empty for any other process, so that the proxy says nothing about what else exists.
     */
    public Optional<ProcessDefinition<?, ?, ?>> process(String proxy, String process) {
        ProcessRegistry registry = processes.get();
        if (registry == null || process == null) {
            return Optional.empty();
        }
        return registry.findLatest(process).filter(definition -> definition.access() == ProcessAccess.APP_PROXY
            && definition.permissions().equals(Set.of(AppProxyCaller.permission(proxy))));
    }

    @Override
    public List<CheckProblem> check() {
        List<CheckProblem> problems = new ArrayList<>();
        configured.values().forEach(proxy -> proxy.problems().forEach(problem ->
            problems.add(CheckProblem.error(CATEGORY, PROPERTY + "." + proxy.name(), problem))));
        ProcessRegistry registry = processes.get();
        if (registry == null) {
            return problems;
        }
        for (ProcessDefinition<?, ?, ?> definition : registry.all()) {
            if (definition.access() != ProcessAccess.APP_PROXY) {
                continue;
            }
            String location = "process " + definition.name() + " v" + definition.version();
            List<String> proxies = definition.permissions().stream()
                .map(permission -> AppProxyCaller.proxyOf(permission).orElse(null)).toList();
            boolean badName = definition.permissions().stream().anyMatch(permission ->
                permission.startsWith(AppProxyCaller.PERMISSION_PREFIX) && AppProxyCaller.proxyOf(permission).isEmpty());
            if (badName) {
                problems.add(CheckProblem.error(CATEGORY, location, "appProxy() names no valid proxy (lower-case"
                    + " letters, digits and '-', at most 30, starting with a letter)"));
            } else if (proxies.size() != 1 || proxies.getFirst() == null) {
                problems.add(CheckProblem.error(CATEGORY, location, "an appProxy() process holds its proxy's"
                    + " permission alone: its callers have no other (decision D60)"));
            } else if (!configured.containsKey(proxies.getFirst())) {
                problems.add(CheckProblem.error(CATEGORY, location, "names no configured app proxy: "
                    + proxies.getFirst() + " (" + PROPERTY + ".<name>)"));
            }
            if (!definition.alternativePermissions().isEmpty() || definition.mfa() != MfaRequirement.NONE
                || definition.verifiedEmail()) {
                problems.add(CheckProblem.error(CATEGORY, location, "an appProxy() process asks nothing of a"
                    + " signed-in user (alternative permissions, a second factor, a verified address): its callers"
                    + " are another platform's visitors"));
            }
        }
        return problems;
    }
}
