package com.jabiz.quizbuks.it;

import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.quizbuks.publishing.PublicationAdmin;
import com.jabiz.quizbuks.publishing.PublicationNotices;
import com.jabiz.quizbuks.publishing.PublicationOutput;
import com.jabiz.quizbuks.publishing.RollbackProbe;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A move of a publication whose transaction is rolled back announces nothing (Q7a review): the event that would mail
 * the sponsor is written in the move's transaction, so it goes with it, and no mail is ever recorded.
 */
@Import(NotificationRollbackIT.Probe.class)
class NotificationRollbackIT extends PlayItSupport {

    @TestConfiguration
    static class Probe {
        @Bean
        ProcessDefinition<PublicationAdmin.AdminInput, PublicationOutput, ProcessContext> qbTestEndThenFail() {
            return RollbackProbe.define();
        }
    }

    @Test
    void aRolledBackMovePublishesNothing() {
        String sponsor = newSponsor("s-mail-rollback");
        String publicationId = livePublication(sponsor, "Rolled back");
        assertThatThrownBy(() -> systemRun(RollbackProbe.NAME, Map.of("publicationId", publicationId,
            "reason", "Never written"))).hasMessageContaining("Rolled back on purpose");
        assertThat(status(publicationId)).isEqualTo("LIVE");
        assertThat(TopUpItSupport.outboxEvents(PublicationNotices.EVENT, "publicationId", publicationId)).isZero();
        deliverEvents();
        assertThat(templatesTo(sponsor)).doesNotContain("qb.publication.taken-down");
    }
}
