package com.jabiz.quizbuks.publishing;

import com.jabiz.entity.Violation;
import com.jabiz.quizbuks.publishing.PrerequisiteRules.Edge;
import com.jabiz.quizbuks.publishing.PrerequisiteRules.Target;
import com.jabiz.quizbuks.publishing.PrerequisiteRules.Wanted;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PrerequisiteRulesTest {

    private static final UUID DRAFT = UUID.randomUUID();
    private static final UUID A = UUID.randomUUID();
    private static final UUID B = UUID.randomUUID();
    private static final UUID C = UUID.randomUUID();

    private static Map<UUID, Target> live(UUID... ids) {
        Map<UUID, Target> targets = new HashMap<>();
        for (UUID id : ids) {
            targets.put(id, new Target(id, PublicationStatus.LIVE));
        }
        return targets;
    }

    private static List<String> codes(List<Violation> problems) {
        return problems.stream().map(Violation::ruleCode).toList();
    }

    @Test
    void nothingWantedIsFine() {
        assertThat(PrerequisiteRules.problems(DRAFT, List.of(), live(), List.of())).isEmpty();
        assertThat(PrerequisiteRules.problems(DRAFT, List.of(new Wanted(A, QbPrerequisites.PASSED),
            new Wanted(B, QbPrerequisites.ANSWERED)), live(A, B), List.of())).isEmpty();
    }

    @Test
    void everyProblemIsNamedAtItsPlace() {
        UUID other = UUID.randomUUID();
        Map<UUID, Target> targets = live(A);
        targets.put(B, new Target(B, PublicationStatus.DRAFT));
        List<Violation> problems = PrerequisiteRules.problems(DRAFT, List.of(
            new Wanted(A, QbPrerequisites.PASSED), new Wanted(A, QbPrerequisites.ANSWERED), new Wanted(DRAFT,
                QbPrerequisites.PASSED), new Wanted(other, QbPrerequisites.PASSED), new Wanted(B,
                QbPrerequisites.PASSED)), targets, List.of());
        assertThat(codes(problems)).containsExactly(PublishingCodes.PREREQUISITE_REPEATED,
            PublishingCodes.PREREQUISITE_SELF, PublishingCodes.PREREQUISITE_NOT_OWN, PublishingCodes.PREREQUISITE_DRAFT);
        assertThat(problems).extracting(Violation::field).containsExactly("prerequisites[1]", "prerequisites[2]",
            "prerequisites[3]", "prerequisites[4]");
    }

    @Test
    void moreThanTheLimitIsRefused() {
        List<Wanted> wanted = new ArrayList<>();
        Map<UUID, Target> targets = new HashMap<>();
        for (int i = 0; i <= QbPrerequisites.MAX_PREREQUISITES; i++) {
            UUID id = UUID.randomUUID();
            wanted.add(new Wanted(id, QbPrerequisites.PASSED));
            targets.put(id, new Target(id, PublicationStatus.ENDED));
        }
        assertThat(codes(PrerequisiteRules.problems(DRAFT, wanted, targets, List.of())))
            .containsExactly(PublishingCodes.PREREQUISITE_TOO_MANY);
    }

    @Test
    void aCycleHoweverFarRoundIsRefusedAndTheDraftsOwnEdgesAreReplaced() {
        // A requires B, B requires the draft: the draft requiring A closes the circle; the draft's stored edge to C
        // is what is being replaced and does not count.
        List<Edge> edges = List.of(new Edge(A, B), new Edge(B, DRAFT), new Edge(DRAFT, C));
        assertThat(codes(PrerequisiteRules.problems(DRAFT, List.of(new Wanted(A, QbPrerequisites.PASSED)), live(A),
            edges))).containsExactly(PublishingCodes.PREREQUISITE_CYCLE);
        assertThat(PrerequisiteRules.problems(DRAFT, List.of(new Wanted(C, QbPrerequisites.PASSED)), live(C), edges))
            .isEmpty();
        // Requiring itself is named as such, not as a cycle.
        assertThat(codes(PrerequisiteRules.problems(DRAFT, List.of(new Wanted(DRAFT, QbPrerequisites.PASSED)),
            live(DRAFT), List.of()))).containsExactly(PublishingCodes.PREREQUISITE_SELF);
    }

    @Test
    void aChainLongerThanTheDepthIsRefused() {
        List<Edge> edges = new ArrayList<>();
        UUID head = UUID.randomUUID();
        UUID current = head;
        for (int i = 0; i < QbPrerequisites.MAX_DEPTH + 1; i++) {
            UUID next = UUID.randomUUID();
            edges.add(new Edge(current, next));
            current = next;
        }
        assertThat(codes(PrerequisiteRules.problems(DRAFT, List.of(new Wanted(head, QbPrerequisites.PASSED)),
            live(head), edges))).containsExactly(PublishingCodes.PREREQUISITE_TOO_DEEP);
        // A chain within the depth is fine, and a shared middle is walked once (no blow-up).
        List<Edge> diamond = List.of(new Edge(A, B), new Edge(A, C), new Edge(B, DRAFT), new Edge(C, DRAFT));
        assertThat(codes(PrerequisiteRules.problems(DRAFT, List.of(new Wanted(A, QbPrerequisites.PASSED)), live(A),
            diamond))).containsExactly(PublishingCodes.PREREQUISITE_CYCLE);
        assertThat(PrerequisiteRules.problems(DRAFT, List.of(new Wanted(A, QbPrerequisites.PASSED)), live(A),
            List.of(new Edge(A, B), new Edge(A, C), new Edge(B, C)))).isEmpty();
    }
}
