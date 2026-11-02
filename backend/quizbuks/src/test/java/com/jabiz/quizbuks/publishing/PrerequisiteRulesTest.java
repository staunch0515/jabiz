package com.jabiz.quizbuks.publishing;

import com.jabiz.entity.Violation;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Q76 (D-Q76-4): what a draft's prerequisites are refused for, every problem at once. */
class PrerequisiteRulesTest {

    private static final UUID SELF = UUID.randomUUID();

    private static PrerequisiteRules.Wanted passed(UUID id) {
        return new PrerequisiteRules.Wanted(id, QbPrerequisites.PASSED);
    }

    private static List<String> codes(List<Violation> problems) {
        return problems.stream().map(Violation::ruleCode).toList();
    }

    @Test
    void theSponsorsSubmittedPublicationsUpToFive() {
        Map<UUID, String> statuses = new HashMap<>();
        List<PrerequisiteRules.Wanted> wanted = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            UUID id = UUID.randomUUID();
            statuses.put(id, PublicationStatus.LIVE);
            wanted.add(passed(id));
        }
        assertThat(PrerequisiteRules.problems(SELF, wanted, statuses, Map.of())).isEmpty();
        UUID sixth = UUID.randomUUID();
        statuses.put(sixth, PublicationStatus.ENDED);
        wanted.add(passed(sixth));
        assertThat(codes(PrerequisiteRules.problems(SELF, wanted, statuses, Map.of())))
            .containsExactly(PublishingCodes.PREREQUISITE_TOO_MANY);
    }

    @Test
    void notItselfNotTwiceNotAnotherSponsorsNotADraft() {
        UUID live = UUID.randomUUID();
        UUID draft = UUID.randomUUID();
        Map<UUID, String> statuses = Map.of(live, PublicationStatus.LIVE, draft, PublicationStatus.DRAFT);
        List<Violation> problems = PrerequisiteRules.problems(SELF, List.of(passed(SELF), passed(live), passed(live),
            passed(UUID.randomUUID()), passed(draft)), statuses, Map.of());
        assertThat(codes(problems)).containsExactly(PublishingCodes.PREREQUISITE_SELF,
            PublishingCodes.PREREQUISITE_REPEATED, PublishingCodes.PREREQUISITE_NOT_OWN,
            PublishingCodes.PREREQUISITE_DRAFT);
        assertThat(problems).extracting(Violation::field).containsExactly("prerequisites[0].publicationId",
            "prerequisites[2].publicationId", "prerequisites[3].publicationId", "prerequisites[4].publicationId");
    }

    @Test
    void noCycleDirectOrThroughOthers() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        Map<UUID, String> statuses = Map.of(a, PublicationStatus.LIVE, b, PublicationStatus.ENDED);
        // a requires the draft directly; b through a.
        Map<UUID, List<UUID>> chain = Map.of(a, List.of(SELF), b, List.of(a));
        assertThat(codes(PrerequisiteRules.problems(SELF, List.of(passed(a)), statuses, chain)))
            .containsExactly(PublishingCodes.PREREQUISITE_CYCLE);
        assertThat(codes(PrerequisiteRules.problems(SELF, List.of(passed(b)), statuses, chain)))
            .containsExactly(PublishingCodes.PREREQUISITE_CYCLE);
        // A diamond is no cycle.
        UUID c = UUID.randomUUID();
        UUID d = UUID.randomUUID();
        assertThat(PrerequisiteRules.problems(SELF, List.of(passed(c)), Map.of(c, PublicationStatus.LIVE),
            Map.of(c, List.of(a, d), a, List.of(d)))).isEmpty();
        // A cycle elsewhere (stored before) is not followed round.
        assertThat(PrerequisiteRules.problems(SELF, List.of(passed(c)), Map.of(c, PublicationStatus.LIVE),
            Map.of(c, List.of(d), d, List.of(c)))).isEmpty();
    }

    @Test
    void aChainAtMostTwentyLong() {
        Map<UUID, List<UUID>> chain = new HashMap<>();
        UUID first = UUID.randomUUID();
        UUID next = first;
        // first requires 19 more: with the draft, a chain of 20.
        for (int i = 0; i < 19; i++) {
            UUID required = UUID.randomUUID();
            chain.put(next, List.of(required));
            next = required;
        }
        assertThat(PrerequisiteRules.problems(SELF, List.of(passed(first)), Map.of(first, PublicationStatus.LIVE),
            chain)).isEmpty();
        UUID longer = UUID.randomUUID();
        chain.put(longer, List.of(first));
        assertThat(codes(PrerequisiteRules.problems(SELF, List.of(passed(longer)),
            Map.of(longer, PublicationStatus.LIVE), chain))).containsExactly(PublishingCodes.PREREQUISITE_TOO_DEEP);
    }
}
