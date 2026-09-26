package com.jabiz.runtime.web;

import com.jabiz.context.RequestContext;
import com.jabiz.entity.SemanticKind;
import com.jabiz.query.custom.AdvancedQueryDefinition;
import com.jabiz.runtime.PermissionDeniedException;
import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Default deny of the template API (ROADMAP phase 5, requirement 9). */
class QueryControllerTest {

    private static AdvancedQueryDefinition query(String... permissions) {
        return AdvancedQueryDefinition.define("q", q -> q.fromEntities("E").returns("x", new SemanticKind.Bool())
            .permissions(permissions).sqlTemplate("SELECT true AS x"));
    }

    private static RequestContext caller(String... permissions) {
        return new RequestContext("alice", null, Locale.ENGLISH, "r", Set.of(), Set.of(permissions));
    }

    @Test
    void everyDeclaredPermissionIsNeeded() {
        assertThatCode(() -> QueryController.requirePermissions(query("a", "b"), caller("a", "b"), false))
            .doesNotThrowAnyException();
        assertThatThrownBy(() -> QueryController.requirePermissions(query("a", "b"), caller("a"), true))
            .isInstanceOf(PermissionDeniedException.class).hasMessageContaining("requires permission b");
    }

    @Test
    void aTemplateWithoutPermissionsRunsInTheDevProfileOnly() {
        assertThatThrownBy(() -> QueryController.requirePermissions(query(), caller("a"), false))
            .isInstanceOf(PermissionDeniedException.class).hasMessageContaining("declares no permissions");
        assertThatCode(() -> QueryController.requirePermissions(query(), caller(), true)).doesNotThrowAnyException();
    }
}
