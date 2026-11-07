package com.jabiz.runtime.approval;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The configurable limit of APPROVAL_SCOPE_MOVE and its startup check (decision D76, ROADMAP 17zy). */
class ApprovalScopeMoveSettingsTest {

    @Test
    void theLimitIsReadAndCheckedForItsRange() {
        assertThat(new ApprovalScopeMoveSettings(ApprovalScopeMove.DEFAULT_MAX_ITEMS).maxItems()).isEqualTo(500);
        assertThat(new ApprovalScopeMoveSettings(1).check()).isEmpty();
        assertThat(new ApprovalScopeMoveSettings(ApprovalScopeMove.MAX_LIMIT).check()).isEmpty();
        assertThat(new ApprovalScopeMoveSettings(0).check()).singleElement()
            .satisfies(p -> assertThat(p.location()).isEqualTo("jabiz.approval.scope-move.max-items"));
        assertThat(new ApprovalScopeMoveSettings(ApprovalScopeMove.MAX_LIMIT + 1).check()).hasSize(1);
    }
}
