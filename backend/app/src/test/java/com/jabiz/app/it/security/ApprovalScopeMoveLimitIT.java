package com.jabiz.app.it.security;

import com.jabiz.app.it.fixture.ItApprovalFixtures;
import com.jabiz.app.it.fixture.ItApprovalScopeFixtures;
import com.jabiz.app.commerce.CommerceEntities;
import com.jabiz.runtime.approval.ApprovalPermissions;
import com.jabiz.runtime.approval.ApprovalScopeMoveSettings;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The limit of one {@code APPROVAL_SCOPE_MOVE} call is a setting (decision D76, ROADMAP 17zy): here 2. More cases or
 * more task source keys are refused as the input's own size constraint refuses them (400, constraint Size), before
 * anything is read; applications read the limit from {@link ApprovalScopeMoveSettings}.
 */
@SpringBootTest(properties = "jabiz.approval.scope-move.max-items=2")
class ApprovalScopeMoveLimitIT extends ApprovalItSupport {

    @Autowired
    ApprovalScopeMoveSettings settings;

    private static Map<String, Object> move(int cases, int taskKeys) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("scopeName", CommerceEntities.WAREHOUSE_SCOPE);
        input.put("toValue", "south");
        input.put("requests", IntStream.range(0, cases).mapToObj(i -> Map.of("subject", ItApprovalFixtures.SUBJECT,
            "entityId", "P-limit-" + i)).toList());
        input.put("taskSourceKeys", IntStream.range(0, taskKeys).mapToObj(i -> "limit:" + i).toList());
        input.put("reason", "The sponsor moved to another operator");
        return input;
    }

    private String mover() {
        return as("it-limit-mover", ItApprovalScopeFixtures.PERMISSION, ApprovalPermissions.SCOPE_MOVE);
    }

    @Test
    void moreThanTheConfiguredLimitIsRefusedAsTheSizeConstraintWould() {
        assertThat(settings.maxItems()).isEqualTo(2);
        Map<String, Object> problem = refused(ItApprovalScopeFixtures.REASSIGN, mover(), move(3, 3), 400);
        assertThat(violations(problem)).extracting(v -> v.get("field"), v -> v.get("ruleCode"))
            .containsExactly(org.assertj.core.groups.Tuple.tuple("requests", "INVALID_VALUE"),
                org.assertj.core.groups.Tuple.tuple("taskSourceKeys", "INVALID_VALUE"));
        // At the limit: accepted (nothing open to move, so nothing moves).
        Map<String, Object> output = run(ItApprovalScopeFixtures.REASSIGN, mover(), move(2, 2));
        assertThat(output).isNotNull();
    }
}
