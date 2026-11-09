package com.jabiz.app.it.security;

import com.jabiz.app.commerce.CommerceEntities;
import com.jabiz.runtime.check.CheckProblem;
import com.jabiz.runtime.dataset.DatasetRegistry;
import com.jabiz.runtime.entity.EntityDefinitionRegistry;
import com.jabiz.runtime.security.GuardLoadIndexCheck;
import com.jabiz.runtime.security.SignInGuards;
import com.jabiz.security.EntryScopeResolver;
import com.jabiz.security.GuardParam;
import com.jabiz.security.SignInGuard;
import com.jabiz.security.SignInLoad;
import com.jabiz.security.SignUpAttempt;
import com.jabiz.security.SignUpDecision;
import com.jabiz.security.SignUpGuard;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.r2dbc.core.DatabaseClient;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Decision D80 (docs/design/10-security.md section 15.18): the startup checks of a guard's loads with conditions on
 * business parameters, against the application's real datasets and tables.
 */
@org.springframework.boot.test.context.SpringBootTest(webEnvironment = org.springframework.boot.test.context.SpringBootTest.WebEnvironment.MOCK)
class GuardLoadChecksIT extends SecurityItSupport {

    private static final String PRODUCTS = CommerceEntities.PRODUCT_DATASET;
    private static final String KEY = "it.launch-product";

    @Autowired
    SignInGuards signInGuards;

    @Autowired
    DatasetRegistry datasets;

    @Autowired
    EntityDefinitionRegistry definitions;

    @Autowired
    DatabaseClient db;

    private List<String> problems(SignInLoad... loads) {
        return signInGuards.loadProblems("it", List.of(loads), new LinkedHashMap<>(), Set.of(KEY)).stream()
            .map(CheckProblem::message).toList();
    }

    @Test
    void conditionsAreCheckedAgainstTheGuardsParametersAndTheEntitysFields() {
        assertThat(problems(SignInLoad.global("launch", PRODUCTS).whereParam("sku", KEY))).isEmpty();
        assertThat(problems(SignInLoad.global("all", PRODUCTS)))
            .singleElement().asString().contains("is global and has no condition");
        assertThat(problems(SignInLoad.global("launch", PRODUCTS).whereParam("sku", "it.undeclared")))
            .singleElement().asString().contains("parameter it.undeclared")
            .contains("does not declare");
        assertThat(problems(SignInLoad.global("launch", PRODUCTS).whereParam("skew", KEY)))
            .singleElement().asString().contains("has no field skew");
        // A global load is not asked for a user field; a user's load still is.
        assertThat(problems(SignInLoad.of("mine", PRODUCTS, "unitPrice").whereParam("sku", KEY)))
            .isNotEmpty();
    }

    @Test
    void theSameLoadDeclaredTwiceWithFunctionsIsOneLoadAndAnotherShapeIsNot() {
        SignInLoad one = SignInLoad.global("launch", PRODUCTS).whereParam("sku", KEY, value -> value);
        SignInLoad two = SignInLoad.global("launch", PRODUCTS).whereParam("sku", KEY, value -> value);
        var seen = new LinkedHashMap<String, SignInLoad>();
        assertThat(signInGuards.loadProblems("a", List.of(one), seen, Set.of(KEY))).isEmpty();
        assertThat(signInGuards.loadProblems("b", List.of(two), seen, Set.of(KEY))).isEmpty();
        SignInLoad other = SignInLoad.global("launch", PRODUCTS).whereParam("productName", KEY);
        assertThat(signInGuards.loadProblems("c", List.of(other), seen, Set.of(KEY))).extracting(CheckProblem::message)
            .singleElement().asString().contains("declared with another dataset or field elsewhere");
    }

    /** A sign-up guard declaring {@code loads}, on the parameter {@link #KEY}. */
    private static SignUpGuard guard(SignInLoad... loads) {
        return new SignUpGuard() {
            @Override
            public List<GuardParam> params() {
                return List.of(GuardParam.optional(KEY));
            }

            @Override
            public List<SignInLoad> loads() {
                return List.of(loads);
            }

            @Override
            public SignUpDecision check(SignUpAttempt attempt) {
                return SignUpDecision.ALLOW;
            }
        };
    }

    private List<CheckProblem> indexProblems(SignUpGuard guard) {
        StaticListableBeanFactory factory = new StaticListableBeanFactory();
        factory.addBean("guard", guard);
        return new GuardLoadIndexCheck(factory.getBeanProvider(SignInGuard.class),
            factory.getBeanProvider(SignUpGuard.class), factory.getBeanProvider(EntryScopeResolver.class), datasets,
            definitions, db).check();
    }

    @Test
    void aLoadNoneOfWhoseColumnsLeadsAnIndexIsAWarning() {
        // product_version_sku_idx leads with sku.
        assertThat(indexProblems(guard(SignInLoad.global("bySku", PRODUCTS).whereParam("sku", KEY)))).isEmpty();
        // Nothing leads with product_name: the read would scan the table.
        assertThat(indexProblems(guard(SignInLoad.global("byName", PRODUCTS).whereParam("productName", KEY))))
            .singleElement().satisfies(problem -> {
                assertThat(problem.severity()).isEqualTo(CheckProblem.Severity.WARNING);
                assertThat(problem.location()).isEqualTo("load byName");
                assertThat(problem.message()).contains("[product_name]").contains("product_version");
            });
        // One indexed column among the compared ones is enough.
        assertThat(indexProblems(guard(SignInLoad.global("both", PRODUCTS).whereParam("productName", KEY)
            .whereParam("sku", KEY)))).isEmpty();
    }
}
