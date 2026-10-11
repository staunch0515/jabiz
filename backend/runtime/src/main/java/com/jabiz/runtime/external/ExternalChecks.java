package com.jabiz.runtime.external;

import com.jabiz.external.ExternalService;
import com.jabiz.runtime.check.CheckProblem;
import com.jabiz.runtime.check.PlatformCheck;
import com.jabiz.runtime.web.OutboundAddresses;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Startup self-check {@code EXTERNAL} (decision D38), every problem at once: each external service has a valid,
 * unique name, at least one allowed host, a base address the platform may send to ({@link OutboundAddresses}: https,
 * http only to this machine, a host of its own list), bounded timeouts and size limit, and its secrets in the
 * environment (see {@link ExternalServiceRegistry} for what a missing one is); the environment's settings can be read
 * and name no service that is not declared. A declaration repeated under the same name is reported with its own
 * problems too.
 */
@Component
public class ExternalChecks implements PlatformCheck {

    public static final String CATEGORY = "EXTERNAL";

    private final ExternalServiceRegistry registry;

    public ExternalChecks(ExternalServiceRegistry registry) {
        this.registry = registry;
    }

    @Override
    public List<CheckProblem> check() {
        List<CheckProblem> problems = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (ExternalService service : registry.declared()) {
            String at = String.valueOf(service.name());
            if (service.name() != null && !names.add(service.name())) {
                problems.add(CheckProblem.error(CATEGORY, at, "declared twice"));
            }
            service.problems().forEach(problem -> problems.add(CheckProblem.error(CATEGORY, at, problem)));
            if (service.baseUrl() != null) {
                String refused = OutboundAddresses.refusal(service.baseUrl(), service.allowedHosts(),
                    "the allowed hosts of external service " + at);
                if (refused != null) {
                    problems.add(CheckProblem.error(CATEGORY, at, "baseUrl: " + refused));
                }
            }
            service.secrets().forEach((secret, variable) -> {
                if (variable == null || registry.isSet(variable)
                    || registry.missingSecrets() == ExternalServiceRegistry.MissingSecrets.IGNORE) {
                    return;
                }
                String message = "secret " + secret + ": the environment variable " + variable + " is not set";
                problems.add(registry.missingSecrets() == ExternalServiceRegistry.MissingSecrets.WARNING
                    ? CheckProblem.warning(CATEGORY, at, message + "; calls that use the secret will fail")
                    : CheckProblem.error(CATEGORY, at, message + " (set it, or run with the dev profile)"));
            });
        }
        registry.settingProblems().forEach(problem ->
            problems.add(CheckProblem.error(CATEGORY, problem.location(), problem.message())));
        registry.overrides().keySet().stream().sorted()
            .filter(name -> registry.find(name).isEmpty())
            .forEach(name -> problems.add(CheckProblem.error(CATEGORY, ExternalServiceRegistry.OVERRIDES + "." + name,
                "no external service of this name is declared")));
        return problems;
    }
}
