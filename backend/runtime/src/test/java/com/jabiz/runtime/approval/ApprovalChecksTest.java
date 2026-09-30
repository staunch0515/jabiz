package com.jabiz.runtime.approval;

import com.jabiz.approval.ApprovalSubject;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.process.ProcessContext;
import com.jabiz.runtime.check.CheckProblem;
import com.jabiz.runtime.entity.EntityDefinitionRegistry;
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

    private static final EntityDefinitionRegistry ENTITIES = entities(EntityDefinition.define("FinJournal", eb -> {
        eb.physicalTable("fin_journal");
        eb.primaryKey("journalId");
        eb.field("journalId", f -> f.physicalColumn("journal_id").asText(36));
    }));

    private static EntityDefinitionRegistry entities(EntityDefinition... definitions) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        for (EntityDefinition definition : definitions) {
            beans.addBean(definition.name, definition);
        }
        return new EntityDefinitionRegistry(beans.getBeanProvider(EntityDefinition.class));
    }

    @Test
    void aSubjectDeclaredTwiceIsReported() {
        ApprovalSubjectRegistry subjects = registry(JOURNAL, PAYMENT,
            ApprovalSubject.define("fin.payment", s -> s.text("x")));
        assertThat(new ApprovalChecks(subjects, ENTITIES).check())
            .extracting(CheckProblem::location, CheckProblem::message)
            .containsExactly(tuple("ApprovalSubject fin.payment", "declared more than once"));
        assertThat(new ApprovalChecks(registry(JOURNAL, PAYMENT), ENTITIES).check()).isEmpty();
    }

    @Test
    void theEntityOfASubjectMustBeDeclared() {
        ApprovalSubjectRegistry subjects = registry(
            ApprovalSubject.define("fin.journal", s -> s.entity("FinJournal").number("amount")),
            ApprovalSubject.define("fin.payment", s -> s.entity("FinPayment").number("amount")));
        assertThat(new ApprovalChecks(subjects, ENTITIES).check())
            .extracting(CheckProblem::location, CheckProblem::message)
            .containsExactly(tuple("ApprovalSubject fin.payment", "entity FinPayment is not declared"));
    }

    @Test
    void stepsMustNameADeclaredSubject() {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        RequireApproval<ProcessContext> require = new RequireApproval<>(registry(JOURNAL), null,
            beans.getBeanProvider(com.jabiz.runtime.process.steps.EventPublisher.class), null);
        assertThat(require.problems(RequireApproval.<ProcessContext>of("fin.nothing",
            ctx -> ApprovalCase.of("1", Map.of(), Map.of()), "a").metadata()))
            .containsExactly("approval subject fin.nothing is not declared",
                "requests approvals but no EventPublisher is configured");

        WithdrawApproval<ProcessContext> withdraw = new WithdrawApproval<>(registry(JOURNAL), null, null);
        assertThat(withdraw.problems(WithdrawApproval.<ProcessContext>of("fin.journal", ctx -> "1").metadata()))
            .isEmpty();
        assertThat(withdraw.problems(WithdrawApproval.<ProcessContext>of("fin.x", ctx -> "1").metadata()))
            .containsExactly("approval subject fin.x is not declared");
    }
}
