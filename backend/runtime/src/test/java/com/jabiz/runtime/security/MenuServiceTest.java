package com.jabiz.runtime.security;

import com.jabiz.runtime.EntityInstance;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MenuServiceTest {

    private static EntityInstance menu(String code, String parent, int order, Map<String, String> labels) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("menuCode", code);
        attributes.put("parentCode", parent);
        attributes.put("sortOrder", BigDecimal.valueOf(order));
        attributes.put("labels", labels);
        attributes.put("path", "/" + code);
        return new EntityInstance(code, SecurityEntities.MENU, 1, null, attributes);
    }

    @Test
    void entriesFormATreeInOrderWithLabelsOfTheLanguage() {
        List<MenuService.MenuItem> tree = MenuService.tree(List.of(
            menu("orders", null, 2, Map.of("en", "Orders", "ja", "注文")),
            menu("admin", null, 1, Map.of("en", "Admin")),
            menu("users", "admin", 2, Map.of()),
            menu("roles", "admin", 1, Map.of("ja", "ロール"))), "ja");

        assertThat(tree).extracting(MenuService.MenuItem::code).containsExactly("admin", "orders");
        assertThat(tree.get(0).label()).isEqualTo("Admin");
        assertThat(tree.get(1).label()).isEqualTo("注文");
        assertThat(tree.get(0).children()).extracting(MenuService.MenuItem::label).containsExactly("ロール", "users");
    }

    @Test
    void entriesUnderAHiddenParentAreHidden() {
        List<MenuService.MenuItem> tree = MenuService.tree(List.of(menu("users", "admin", 1, Map.of())), "en");
        assertThat(tree).isEmpty();
    }
}
