package com.jabiz.runtime.proxy;

import com.jabiz.context.RequestContext;
import com.jabiz.i18n.PlatformErrorCodes;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.runtime.AuthenticationFailedException;
import com.jabiz.runtime.EntityNotFoundException;
import com.jabiz.runtime.context.RequestContexts;
import com.jabiz.runtime.observability.PlatformObservations;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.process.ExecutionOptions;
import com.jabiz.runtime.process.ProcessExecutor;
import com.jabiz.runtime.process.ProcessResult;
import com.jabiz.runtime.process.ProcessInputs;
import com.jabiz.runtime.security.SensitiveDataMasker;
import com.jabiz.runtime.web.ProcessRateLimits;
import com.jabiz.security.AppProxyCaller;
import com.jabiz.security.AppProxySignature;
import io.micrometer.common.KeyValues;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Runs a process for a visitor of another platform's tenant, through an app proxy (decision D60;
 * docs/design/10-security.md section 15.13): {@code GET|POST /api/proxy/{proxy}/{process}}, the other platform's
 * signature in the query ({@link AppProxySignature}). In order, before anything reads the database:
 * <ol>
 *   <li>the query is read as the other platform signs it (decoded as a form: '+' is a space);</li>
 *   <li>the signature, its time and the tenant (once, matching the proxy's pattern) must hold, and the customer, if
 *       any, be an id: otherwise 401 {@code APP_PROXY_REFUSED}, whatever failed (the reason is logged and tagged);</li>
 *   <li>the process must be the latest version of one declared {@code appProxy(proxy)}: otherwise 404;</li>
 *   <li>its rate limits count per caller, tenant and visitor key ({@link ProcessRateLimits}): 429 beyond them.</li>
 * </ol>
 * The process then runs from this entry as {@link AppProxyCaller}: actor {@code app-proxy:<proxy>[:<customer>]}, the
 * tenant as the request's tenant, the proxy's permission alone. The body (POST, JSON) is its input and is not signed:
 * it is the visitor's, as anything a visitor sends. The answer is the process's output, never cached. No cookie is
 * read or set; nothing here opens CORS (the other platform calls from its own domain).
 */
@RestController
class AppProxyController {

    static final String PATH = "/api/proxy/{proxy}/{process}";

    private static final Logger log = LoggerFactory.getLogger(AppProxyController.class);

    private final AppProxies proxies;
    private final ProcessExecutor executor;
    private final ProcessInputs inputs;
    private final ProcessRateLimits rateLimits;
    private final SensitiveDataMasker masker;
    private final PlatformObservations observations;
    private final Clock clock;

    AppProxyController(AppProxies proxies, ProcessExecutor executor, ProcessInputs inputs, ProcessRateLimits rateLimits,
        SensitiveDataMasker masker, PlatformObservations observations, Clock clock) {
        this.proxies = proxies;
        this.executor = executor;
        this.inputs = inputs;
        this.rateLimits = rateLimits;
        this.masker = masker;
        this.observations = observations;
        this.clock = clock;
    }

    /** A request without input: the process's input is empty. */
    @GetMapping(PATH)
    Mono<ResponseEntity<Object>> get(@PathVariable String proxy, @PathVariable String process,
        ServerHttpRequest http) {
        return run(proxy, process, null, http);
    }

    /** A request whose JSON body is the process's input. */
    @PostMapping(PATH)
    Mono<ResponseEntity<Object>> post(@PathVariable String proxy, @PathVariable String process,
        @RequestBody(required = false) Map<String, Object> body, ServerHttpRequest http) {
        return run(proxy, process, body, http);
    }

    private Mono<ResponseEntity<Object>> run(String proxy, String process, Map<String, Object> body,
        ServerHttpRequest http) {
        return RequestContexts.current().flatMap(base -> {
            AppProxy app = proxies.find(proxy).orElseThrow(() -> new EntityNotFoundException("Not found"));
            Map<String, List<String>> query = query(http.getURI().getRawQuery());
            AppProxyCaller caller = caller(app, query, proxies.isTenant(proxy, tenantOf(app, query)));
            ProcessDefinition<?, ?, ?> definition = proxies.process(proxy, process)
                .orElseThrow(() -> new EntityNotFoundException("Not found"));
            RequestContext context = new RequestContext(caller.actorId(), caller.tenant(), base.locale(),
                base.requestId(), Set.of(), Set.of(AppProxyCaller.permission(proxy)));
            rateLimits.check(definition, context, http, visitorOf(app, query));
            Map<String, Object> input = body == null ? Map.of() : body;
            return execute(definition, input)
                .contextWrite(view -> RequestContexts.put(view, context))
                .map(result -> {
                    // An instance is masked as every read API shows it.
                    Object output = result.output() instanceof EntityInstance instance ? masker.hide(instance)
                        : result.output();
                    return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                        .body(masker.toJsonWithoutSecrets(output));
                });
        });
    }

    private <I, O, C extends ProcessContext> Mono<ProcessResult<O>> execute(ProcessDefinition<I, O, C> definition,
        Map<String, Object> body) {
        return Mono.defer(() -> executor.run(definition, inputs.convert(definition.inputType(), body),
            ExecutionOptions.NONE.atEntryOf(definition)));
    }

    /** The tenant the query names, if it names exactly one. */
    private static String tenantOf(AppProxy app, Map<String, List<String>> query) {
        List<String> tenants = query == null ? null : query.get(app.tenantParameter());
        return tenants == null || tenants.size() != 1 ? null : tenants.getFirst();
    }

    /**
     * The visitor's own key, for the per-visitor limits only: one, and short and plain (anything else counts as no
     * key, so a visitor cannot fill the limiter's memory with long keys).
     */
    private static String visitorOf(AppProxy app, Map<String, List<String>> query) {
        List<String> visitors = query.get(app.visitorParameter());
        return visitors == null || visitors.size() != 1 || !AppProxyCaller.CUSTOMER.matcher(visitors.getFirst())
            .matches() ? null : visitors.getFirst();
    }

    /** The caller the signed query names; 401 for anything that does not hold. */
    private AppProxyCaller caller(AppProxy app, Map<String, List<String>> query, boolean tenantMatches) {
        if (query == null) {
            throw refused(app, "MALFORMED");
        }
        AppProxySignature.Result result = AppProxySignature.verify(query, app.secrets(), clock.instant(),
            app.maxAge());
        if (result != AppProxySignature.Result.VALID) {
            throw refused(app, result.name());
        }
        if (!tenantMatches) {
            throw refused(app, "TENANT");
        }
        List<String> customers = query.get(app.customerParameter());
        String customer = customers == null || customers.isEmpty() || customers.getFirst().isEmpty() ? null
            : customers.getFirst();
        if (customers != null && customers.size() > 1
            || customer != null && !AppProxyCaller.CUSTOMER.matcher(customer).matches()) {
            throw refused(app, "CUSTOMER");
        }
        return new AppProxyCaller(app.name(), tenantOf(app, query), customer);
    }

    private AuthenticationFailedException refused(AppProxy app, String reason) {
        // Debug only: anybody can send unsigned requests, which the counter shows (the log is not to be flooded).
        log.debug("App proxy {} request refused: {}", app.name(), reason);
        observations.event(PlatformObservations.APP_PROXY_REFUSED, KeyValues.of("proxy", app.name(), "reason",
            reason.toLowerCase(Locale.ROOT)));
        return new AuthenticationFailedException(PlatformErrorCodes.APP_PROXY_REFUSED, "App proxy request refused");
    }

    /**
     * The query as the other platform signs it: names and values decoded as a form ('+' a space), the values of a
     * repeated name in order; null when it cannot be decoded.
     */
    static Map<String, List<String>> query(String raw) {
        Map<String, List<String>> query = new LinkedHashMap<>();
        if (raw == null || raw.isEmpty()) {
            return query;
        }
        try {
            for (String pair : raw.split("&", -1)) {
                if (pair.isEmpty()) {
                    continue;
                }
                int equals = pair.indexOf('=');
                String name = URLDecoder.decode(equals < 0 ? pair : pair.substring(0, equals), StandardCharsets.UTF_8);
                String value = equals < 0 ? "" : URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8);
                query.computeIfAbsent(name, n -> new ArrayList<>()).add(value);
            }
        } catch (IllegalArgumentException e) {
            return null;
        }
        return query;
    }
}
