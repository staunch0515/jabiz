package com.jabiz.runtime.security;

import com.jabiz.context.RequestContext;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.runtime.DatasetEntityManager;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.dataset.DatasetRegistry;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The menu of the current actor (docs/design/10-security.md section 3): the enabled entries whose permission the actor
 * holds, as a tree ordered by {@code sortOrder}, labelled in the language of the request. Default deny: an entry whose
 * parent is not shown is not shown either.
 */
@Component
public class MenuService {

    /** One menu entry as returned to the client. */
    public record MenuItem(String code, String label, String path, String icon, List<MenuItem> children) {}

    private final DatasetEntityManager entities;
    private final DatasetRegistry datasets;

    public MenuService(DatasetEntityManager entities, DatasetRegistry datasets) {
        this.entities = entities;
        this.datasets = datasets;
    }

    public Mono<List<MenuItem>> menuOf(RequestContext request) {
        EntityQuery query = Rbac.all(new QueryPredicate.Eq("enabled", true), "menuCode");
        return entities.query(datasets.findById(SecurityEntities.MENU_DATASET).orElseThrow(),
                SecurityEntities.SEC_MENU, query)
            .collectList()
            .map(Rbac::complete)
            .map(menu -> tree(menu.stream().filter(entry -> request.hasPermission(entry.get("permission"))).toList(),
                request.locale().getLanguage()));
    }

    static List<MenuItem> tree(List<EntityInstance> visible, String language) {
        Set<String> codes = visible.stream().map(e -> e.<String>get("menuCode")).collect(Collectors.toSet());
        Map<String, List<EntityInstance>> byParent = new LinkedHashMap<>();
        for (EntityInstance entry : visible) {
            String parent = entry.get("parentCode");
            if (parent != null && !codes.contains(parent)) {
                continue;
            }
            byParent.computeIfAbsent(parent == null ? "" : parent, p -> new ArrayList<>()).add(entry);
        }
        return children("", byParent, language);
    }

    private static List<MenuItem> children(String parent, Map<String, List<EntityInstance>> byParent,
        String language) {
        return byParent.getOrDefault(parent, List.of()).stream()
            .sorted(Comparator.comparing((EntityInstance e) -> order(e.get("sortOrder")))
                .thenComparing(e -> e.<String>get("menuCode")))
            .map(entry -> {
                String code = entry.get("menuCode");
                return new MenuItem(code, label(entry.get("labels"), language, code), entry.get("path"),
                    entry.get("icon"), children(code, byParent, language));
            })
            .toList();
    }

    private static BigDecimal order(Object value) {
        return value instanceof BigDecimal decimal ? decimal : new BigDecimal(String.valueOf(value));
    }

    /** Request language, then English, then the code itself. */
    private static String label(Object labels, String language, String code) {
        if (labels instanceof Map<?, ?> map) {
            Object label = map.get(language);
            if (label == null) {
                label = map.get("en");
            }
            if (label != null) {
                return label.toString();
            }
        }
        return code;
    }
}
