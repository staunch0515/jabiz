package com.jabiz.runtime.security;

import com.jabiz.context.RequestContext;
import com.jabiz.param.ControlledParams;
import com.jabiz.param.ParamValues;
import com.jabiz.runtime.check.CheckProblem;
import com.jabiz.runtime.context.RequestContexts;
import com.jabiz.runtime.param.ParamService;
import com.jabiz.security.GuardParam;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reads the business parameters the sign-in and sign-up guards declare (docs/design/10-security.md section 15.17;
 * decision D79): once per attempt, every guard's keys together, as in effect at the attempt's time, as the system (the
 * signing-in user's permissions do not matter, as for the guards' loads). A key some guard requires and that has no
 * value then, or a read that fails, is an error, and the caller refuses the attempt (fail closed) without asking the
 * guards. Without declared parameters nothing is read.
 */
@Component
public class GuardParams {

    private final ParamService params;

    public GuardParams(ParamService params) {
        this.params = params;
    }

    /** The keys of {@code declared}, each required when any declaration requires it, in the order first declared. */
    static Map<String, Boolean> merge(List<List<GuardParam>> declared) {
        Map<String, Boolean> keys = new LinkedHashMap<>();
        declared.forEach(list -> list.forEach(param -> keys.merge(param.key(), param.required(), Boolean::logicalOr)));
        return keys;
    }

    /**
     * The values of {@code keys} (key to whether it is required) in effect at {@code time}; those that may be unset
     * and are have none. An error when a required key has no value then.
     */
    Mono<ParamValues> read(Map<String, Boolean> keys, Instant time) {
        if (keys.isEmpty()) {
            return Mono.just(new ParamValues(time, Map.of()));
        }
        return Mono.defer(() -> params.loadAvailable(keys.keySet(), time))
            // As the system, keeping the caller's request id (the logs of one sign-in stay together).
            .contextWrite(view -> RequestContexts.put(view, RequestContext.system(Locale.ENGLISH,
                view.<String>getOrDefault(RequestContexts.REQUEST_ID_KEY, "sign-in-guard"))))
            .map(values -> {
                List<String> missing = keys.entrySet().stream()
                    .filter(key -> key.getValue() && !values.has(key.getKey())).map(Map.Entry::getKey).toList();
                if (!missing.isEmpty()) {
                    throw new IllegalStateException("Guard parameter(s) " + missing + " have no value in effect at "
                        + time);
                }
                return values;
            });
    }

    /**
     * What is wrong with a guard's {@code declared} parameters: a malformed key (an error: no parameter can have it),
     * a key {@code seen} before as required by one guard and optional by another (a warning: it is read as required).
     */
    static List<CheckProblem> problems(String location, List<GuardParam> declared, Map<String, GuardParam> seen) {
        List<CheckProblem> problems = new ArrayList<>();
        for (GuardParam param : declared) {
            if (!ControlledParams.validKey(param.key())) {
                problems.add(CheckProblem.error(SessionChecks.CATEGORY, location, "parameter '" + param.key()
                    + "' is not a parameter key (lower case words of letters and digits joined by '.', '-' or '_')"));
                continue;
            }
            GuardParam earlier = seen.putIfAbsent(param.key(), param);
            if (earlier != null && earlier.required() != param.required()) {
                problems.add(CheckProblem.warning(SessionChecks.CATEGORY, location, "parameter " + param.key()
                    + " is required by one guard and optional for another; it is read as required"));
            }
        }
        return problems;
    }
}
