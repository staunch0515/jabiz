package com.jabiz.runtime.web;

import com.jabiz.context.RequestContext;
import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.query.QueryPredicate;
import com.jabiz.query.SortOrder;
import com.jabiz.query.custom.AdvancedQueryDefinition;
import com.jabiz.query.custom.QueryParameter;
import com.jabiz.query.custom.SemanticRow;
import com.jabiz.resource.ResourceNotFoundException;
import com.jabiz.runtime.context.RequestContextWebFilter;
import com.jabiz.runtime.context.RequestContexts;
import com.jabiz.runtime.observability.PlatformObservations;
import com.jabiz.runtime.publicread.PublicProperties;
import com.jabiz.runtime.query.AdvancedQueryExecutor;
import com.jabiz.runtime.query.SqlTemplateRegistry;
import io.micrometer.common.KeyValues;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Anonymous execution of public templates (docs/design/15-public-access.md section 5; decision D17):
 * {@code GET/HEAD /api/public/queries/{id}?p.<name>=…&filter=<field>:<op>:<value>&sort=<field>:asc|desc&offset&limit&count}.
 *
 * <p>Runs as the anonymous visitor whatever credentials the request carries. Unknown and non-public templates are
 * both 404, so private templates cannot be discovered. Responses are cacheable ({@code Cache-Control: public}) with a
 * strong {@code ETag} (SHA-256 of the body); {@code If-None-Match} that matches is answered 304. Reads are not
 * operations and write no operation record. The switch, the methods and the rate limit are handled by
 * {@link com.jabiz.runtime.publicread.PublicAccessWebFilter}.
 */
@RestController
@RequestMapping("/api/public/queries")
class PublicQueryController {

    private static final int DEFAULT_LIMIT = 20;
    private static final Set<String> CONTROL = Set.of("filter", "sort", "offset", "limit", "count");
    private static final String PARAM_PREFIX = "p.";

    private final SqlTemplateRegistry templates;
    private final AdvancedQueryExecutor executor;
    private final PublicProperties properties;
    private final JsonMapper json;
    private final PlatformObservations observations;

    PublicQueryController(SqlTemplateRegistry templates, AdvancedQueryExecutor executor, PublicProperties properties,
        JsonMapper json, PlatformObservations observations) {
        this.templates = templates;
        this.executor = executor;
        this.properties = properties;
        this.json = json;
        this.observations = observations;
    }

    @RequestMapping(path = "/{queryId}", method = {RequestMethod.GET, RequestMethod.HEAD})
    Mono<ResponseEntity<byte[]>> run(@PathVariable String queryId, ServerWebExchange exchange) {
        if (!properties.on()) {
            // The filter answers first; this only guards against a path it did not recognise.
            throw new ResourceNotFoundException("Not found");
        }
        // Default deny: besides the startup check, every dataset it reads must be public right here.
        AdvancedQueryDefinition query = templates.find(queryId).filter(AdvancedQueryDefinition::publicAccess)
            .filter(q -> templates.datasetsOf(q).values().stream().allMatch(DatasetDefinition::isPublic))
            .orElseThrow(() -> new ResourceNotFoundException("No public query " + queryId));
        RequestContext started = RequestContextWebFilter.of(exchange);
        RequestContext anonymous = RequestContext.anonymous(started == null ? Locale.ROOT : started.locale(),
            started == null ? "public" : started.requestId());
        return observations.mono(PlatformObservations.PUBLIC_QUERY, "public query " + query.queryId(),
            KeyValues.of("template", query.queryId()),
            Mono.defer(() -> respond(query, exchange))
                .contextWrite(view -> RequestContexts.put(view, anonymous)));
    }

    private Mono<ResponseEntity<byte[]>> respond(AdvancedQueryDefinition query, ServerWebExchange exchange) {
        MultiValueMap<String, String> raw = exchange.getRequest().getQueryParams();
        Map<String, Object> params = new LinkedHashMap<>();
        List<ListRequests.Filter> filters = new ArrayList<>();
        List<ListRequests.Sort> sorts = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : raw.entrySet()) {
            String key = entry.getKey();
            List<String> values = entry.getValue();
            if (key.startsWith(PARAM_PREFIX) && key.length() > PARAM_PREFIX.length()) {
                String name = key.substring(PARAM_PREFIX.length());
                params.put(name, parameterValue(query, name, values));
            } else if (key.equals("filter")) {
                values.forEach(value -> filters.add(filter(value)));
            } else if (key.equals("sort")) {
                values.forEach(value -> sorts.add(sort(value)));
            } else if (!CONTROL.contains(key)) {
                throw ListRequests.invalid(key, "unknown query parameter " + key
                    + "; template parameters are written p.<name>");
            } else if (values.size() > 1) {
                throw ListRequests.invalid(key, key + " is given more than once");
            }
        }
        int offset = integer(raw.getFirst("offset"), "offset", 0);
        int limit = Math.min(integer(raw.getFirst("limit"), "limit", DEFAULT_LIMIT), properties.maxLimit());
        if (offset < 0) {
            throw ListRequests.invalid("offset", "offset must not be negative");
        }
        if (limit <= 0) {
            throw ListRequests.invalid("limit", "limit must be positive");
        }
        boolean count = bool(raw.getFirst("count"));
        return executor.page(query, params, predicate(filters), sortOrders(sorts), offset, limit, count,
                properties.maxTimeout())
            .map(page -> {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("items", page.items().stream().map(PublicQueryController::values).toList());
                body.put("total", page.total());
                body.put("offset", page.offset());
                body.put("limit", page.limit());
                return cacheable(json.writeValueAsBytes(body), cacheSeconds(query), exchange);
            })
            .flatMap(response -> PlatformObservations.tag(PlatformObservations.RESULT,
                    response.getStatusCode() == HttpStatus.NOT_MODIFIED ? "not_modified" : "ok")
                .thenReturn(response));
    }

    private int cacheSeconds(AdvancedQueryDefinition query) {
        return query.cacheSeconds() == null ? properties.defaultCacheSeconds() : query.cacheSeconds();
    }

    /** 200 with the body, or 304 when the client already holds it. */
    private ResponseEntity<byte[]> cacheable(byte[] body, int maxAge, ServerWebExchange exchange) {
        String etag = "\"" + sha256(body) + "\"";
        HttpHeaders headers = new HttpHeaders();
        headers.setCacheControl("public, max-age=" + maxAge);
        headers.setVary(List.of(HttpHeaders.ACCEPT_LANGUAGE));
        headers.setETag(etag);
        if (matches(exchange.getRequest().getHeaders().getIfNoneMatch(), etag)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).headers(headers).build();
        }
        headers.setContentType(MediaType.APPLICATION_JSON);
        return ResponseEntity.ok().headers(headers).body(body);
    }

    /** {@code If-None-Match} uses the weak comparison (RFC 9110 section 13.1.2): {@code W/} is ignored. */
    static boolean matches(List<String> ifNoneMatch, String etag) {
        for (String candidate : ifNoneMatch) {
            String tag = candidate.trim();
            if (tag.equals("*")) {
                return true;
            }
            if (tag.startsWith("W/")) {
                tag = tag.substring(2);
            }
            if (tag.equals(etag)) {
                return true;
            }
        }
        return false;
    }

    private static String sha256(byte[] body) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    /** A list parameter takes every value given; any other parameter exactly one. */
    private static Object parameterValue(AdvancedQueryDefinition query, String name, List<String> values) {
        boolean list = query.parameters().stream().filter(p -> p.name().equals(name)).findFirst()
            .map(QueryParameter::list).orElse(false);
        if (list) {
            return List.copyOf(values);
        }
        if (values.size() > 1) {
            throw ListRequests.invalid(name, "parameter " + name + " is given more than once");
        }
        return values.getFirst();
    }

    /** {@code field:op[:value]}; {@code in} takes comma-separated values, {@code between} {@code from,to}. */
    static ListRequests.Filter filter(String text) {
        String[] parts = text.split(":", 3);
        if (parts.length < 2 || parts[0].isBlank() || parts[1].isBlank()) {
            throw ListRequests.invalid("filter", "a filter is written field:op:value");
        }
        String field = parts[0];
        String op = parts[1];
        String value = parts.length == 3 ? parts[2] : null;
        return switch (op) {
            case "isnull", "isnotnull" -> new ListRequests.Filter(field, op, null, null, null, null);
            case "in" -> new ListRequests.Filter(field, op, null, List.copyOf(split(value, "in")), null, null);
            case "between" -> {
                List<Object> range = split(value, "between");
                if (range.size() != 2) {
                    throw ListRequests.invalid("filter", "between takes from,to");
                }
                yield new ListRequests.Filter(field, op, null, null, range.get(0), range.get(1));
            }
            default -> {
                if (value == null) {
                    throw ListRequests.invalid("filter", "filter " + field + ":" + op + " needs a value");
                }
                yield new ListRequests.Filter(field, op, value, null, null, null);
            }
        };
    }

    private static List<Object> split(String value, String op) {
        if (value == null || value.isEmpty()) {
            throw ListRequests.invalid("filter", op + " needs values");
        }
        return List.of((Object[]) value.split(",", -1));
    }

    static ListRequests.Sort sort(String text) {
        String[] parts = text.split(":", -1);
        if (parts.length > 2 || parts[0].isBlank()) {
            throw ListRequests.invalid("sort", "a sort is written field:asc or field:desc");
        }
        if (parts.length == 1 || parts[1].equals("asc")) {
            return new ListRequests.Sort(parts[0], true);
        }
        if (parts[1].equals("desc")) {
            return new ListRequests.Sort(parts[0], false);
        }
        throw ListRequests.invalid("sort", "a sort is written field:asc or field:desc");
    }

    private static int integer(String value, String name, int fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw ListRequests.invalid(name, name + " must be a whole number");
        }
    }

    private static boolean bool(String value) {
        if (value == null || value.isBlank() || value.equals("true")) {
            return true;
        }
        if (value.equals("false")) {
            return false;
        }
        throw ListRequests.invalid("count", "count must be true or false");
    }

    private static QueryPredicate predicate(List<ListRequests.Filter> filters) {
        if (filters.isEmpty()) {
            return null;
        }
        List<QueryPredicate> parts = filters.stream().map(ListRequests::toPredicate).toList();
        return parts.size() == 1 ? parts.getFirst() : new QueryPredicate.And(parts);
    }

    private static List<SortOrder> sortOrders(List<ListRequests.Sort> sorts) {
        return sorts.stream().map(s -> new SortOrder(s.field(), s.asc())).toList();
    }

    private static Map<String, Object> values(SemanticRow row) {
        Map<String, Object> values = new LinkedHashMap<>();
        row.getAllColumns().forEach((name, value) -> values.put(name, value.value()));
        return values;
    }
}
