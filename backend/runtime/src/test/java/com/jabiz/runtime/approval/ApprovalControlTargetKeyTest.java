package com.jabiz.runtime.approval;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** What a change of an approval rule, limit or SoD rule is listed by (ROADMAP phase 17zn). */
class ApprovalControlTargetKeyTest {

    @Test
    void aRuleIsListedByItsCodeALimitByItsSubject() {
        assertThat(ApprovalControlTarget.key(Map.of("ruleCode", "PAYMENT_OVER_1M", "subject", "payment"), null))
            .isEqualTo("PAYMENT_OVER_1M");
        assertThat(ApprovalControlTarget.key(Map.of("subject", "payment"), null)).isEqualTo("payment");
        assertThat(ApprovalControlTarget.key(Map.of("ruleCode", " "), null)).isNull();
        assertThat(ApprovalControlTarget.key(Map.of(), null)).isNull();
    }
}
