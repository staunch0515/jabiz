package com.jabiz.runtime.approval;

import com.jabiz.approval.ApprovalSubject;
import com.jabiz.process.ProcessContext;
import com.jabiz.runtime.check.CheckProblem;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/** Startup checks of approval subjects and of the steps that name them. */
class ApprovalChecksTest {

    private static final ApprovalSubject JOURNAL = ApprovalSubject.define("fin.journal", s -> s.number("amount"));
    private static final ApprovalSubject PAYMENT = ApprovalSubject.define("fin.payment", s -> s.number("amount"));

    private static ApprovalSubjectRegistry registry(ApprovalSubject... subjects) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        for (int i = 0; i < subjects.length; i++) {
            beans.addBean("subject" + i, subjects[i]);
        }
        return new ApprovalSubjectRegistry(beans.getBeanProvider(ApprovalSubject.class));
    }

    @Test
    void aSubjectDeclaredTwiceIsReported() {
        ApprovalSubjectRegistry subjects = registry(JOURNAL, PAYMENT,
            ApprovalSubject.define("fin.payment", s -> s.text("x")));
        assertThat(new ApprovalChecks(subjects).check()).extracting(CheckProblem::location, CheckProblem::message)
            .containsExactly(tuple("ApprovalSubject fin.payment", "declared more than once"));
        assertThat(new ApprovalChecks(registry(JOURNAL, PAYMENT)).check()).isEmpty();
    }

    @Test
    void stepsMustNameADeclaredSubject() {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        RequireApproval<ProcessContext> require = new RequireApproval<>(registry(JOURNAL), null,
            beans.getBeanProvider(com.jabiz.runtime.process.steps.EventPublisher.class));
        assertThat(require.problems(RequireApproval.<ProcessContext>of("fin.nothing",
            ctx -> ApprovalCase.of("1", Map.of(), Map.of()), "a").metadata()))
            .containsExactly("approval subject fin.nothing is not declared",
                "requests approvals but no EventPublisher is configured");

        WithdrawApproval<ProcessContext> withdraw = new WithdrawApproval<>(registry(JOURNAL), null);
        assertThat(withdraw.problems(WithdrawApproval.<ProcessContext>of("fin.journal", ctx -> "1").metadata()))
            .isEmpty();
        assertThat(withdraw.problems(WithdrawApproval.<ProcessContext>of("fin.x", ctx -> "1").metadata()))
            .containsExactly("approval subject fin.x is not declared");
    }
}
