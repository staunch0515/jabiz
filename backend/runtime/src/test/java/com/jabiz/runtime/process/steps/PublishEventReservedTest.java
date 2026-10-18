package com.jabiz.runtime.process.steps;

import com.jabiz.process.ProcessContext;
import com.jabiz.process.StepDefinition;
import com.jabiz.process.StepSpec;
import com.jabiz.runtime.approval.ApprovalProcesses;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Event types beginning with {@code jabiz.} are the platform's: a step built anywhere else may not publish one (16r
 * review: an application's process posing as the approval that approved something).
 */
class PublishEventReservedTest {

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static List<String> problems(Object metadata) {
        GenericApplicationContext beans = new GenericApplicationContext();
        beans.refresh();
        try {
            return new PublishEvent<>(beans.getBeanProvider(EventPublisher.class))
                .problems((PublishEvent.Metadata) metadata);
        } finally {
            beans.close();
        }
    }

    @Test
    void anotherCodeBaseMayNotPublishThePlatformsEvents() {
        StepSpec<PublishEvent.Metadata<ProcessContext>, ProcessContext> forged =
            PublishEvent.of("jabiz.approval.approved", ctx -> "payload");
        assertThat(problems(forged.metadata())).anySatisfy(problem ->
            assertThat(problem).contains("reserved for the platform"));
        assertThat(PublishEvent.allowed(forged.metadata())).isFalse();
        // An equal step, built the same way, is no more allowed; an application's own name is.
        assertThat(PublishEvent.allowed(new PublishEvent.Metadata<ProcessContext>("jabiz.report.issued",
            ctx -> "payload", null))).isFalse();
        assertThat(PublishEvent.allowed(PublishEvent.<ProcessContext>when(ctx -> true, "logistics.closed",
            ctx -> "payload").metadata())).isTrue();
    }

    @Test
    void thePlatformsOwnStepsMay() {
        List<StepDefinition<?, ?>> announcing = new java.util.ArrayList<>();
        for (StepDefinition<?, ?> step : ApprovalProcesses.decide().steps()) {
            if (PublishEvent.class.equals(step.handlerClass())) {
                announcing.add(step);
            }
        }
        assertThat(announcing).hasSize(2);
        StepDefinition<?, ?> announce = announcing.getFirst();
        PublishEvent.Metadata<?> metadata = (PublishEvent.Metadata<?>) announce.metadata();
        assertThat(metadata.eventType()).isEqualTo(ApprovalProcesses.APPROVED_EVENT);
        assertThat(PublishEvent.allowed(metadata)).isTrue();
        assertThat(problems(metadata)).noneSatisfy(problem -> assertThat(problem).contains("reserved"));
    }
}
