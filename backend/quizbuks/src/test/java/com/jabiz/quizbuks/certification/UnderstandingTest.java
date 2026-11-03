package com.jabiz.quizbuks.certification;

import com.jabiz.quizbuks.play.PrerequisiteCheck;
import com.jabiz.quizbuks.play.PrerequisiteCheck.Attempt;
import com.jabiz.quizbuks.play.PrerequisiteCheck.Prerequisite;
import com.jabiz.quizbuks.publishing.PrerequisiteRules;
import com.jabiz.quizbuks.publishing.PublishingCodes;
import com.jabiz.quizbuks.tree.TreeStates;
import com.jabiz.quizbuks.tree.TreeStates.State;
import com.jabiz.runtime.check.CheckProblem;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Q86 (docs/quizbuks/plans/Q86-agreement-quiz-tree.md): the understanding certifications in the catalog, a
 * certification as a prerequisite (its rules and whether it is met), the tree's state over it, and the startup
 * check that warns when a certification lags the legal text it stands for.
 */
class UnderstandingTest {

    private static final UUID DRAFT = UUID.randomUUID();
    private static final UUID BASICS = UUID.randomUUID();

    @Test
    void theCatalogNamesTheAgreementsEachCertificationStandsFor() {
        assertThat(Certifications.ALL).extracting(Certifications.Certification::key).containsExactly("SPONSOR_APPLY",
            "REFERRER", "TERMS_APP_UNDERSTOOD", "CONTEST_RULES_UNDERSTOOD", "TERMS_SPONSOR_UNDERSTOOD");
        assertThat(Certifications.legalKinds(Certifications.TERMS_APP_UNDERSTOOD)).containsExactly("TERMS_APP", "PRIVACY");
        assertThat(Certifications.legalKinds(Certifications.CONTEST_RULES_UNDERSTOOD)).containsExactly("CONTEST_RULES");
        assertThat(Certifications.legalKinds(Certifications.TERMS_SPONSOR_UNDERSTOOD)).containsExactly("TERMS_SPONSOR",
            "PRIVACY", "COMMERCE_NOTICE");
        assertThat(Certifications.legalKinds(Certifications.REFERRER)).isEmpty();
        // A sponsor's publication may require the takers' certifications only.
        assertThat(Certifications.forTakers("TERMS_APP_UNDERSTOOD")).isTrue();
        assertThat(Certifications.forTakers("REFERRER")).isTrue();
        assertThat(Certifications.forTakers("TERMS_SPONSOR_UNDERSTOOD")).isFalse();
        assertThat(Certifications.forTakers("NOPE")).isFalse();
    }

    @Test
    void aCertificationRequiredIsOneOfTheTakersOnceAndNotBesideAPublication() {
        List<PrerequisiteRules.Wanted> wanted = List.of(
            new PrerequisiteRules.Wanted(null, "CONTEST_RULES_UNDERSTOOD", "PASSED"),
            new PrerequisiteRules.Wanted(null, "CONTEST_RULES_UNDERSTOOD", "PASSED"),
            new PrerequisiteRules.Wanted(null, "TERMS_SPONSOR_UNDERSTOOD", "PASSED"),
            new PrerequisiteRules.Wanted(BASICS, "REFERRER", "PASSED"),
            new PrerequisiteRules.Wanted(null, null, "PASSED"));
        List<String> problems = PrerequisiteRules.problems(DRAFT, wanted, Map.of(), List.of()).stream()
            .map(v -> v.field() + ":" + v.ruleCode()).toList();
        assertThat(problems).containsExactly(
            "prerequisites[1]:" + PublishingCodes.PREREQUISITE_REPEATED,
            "prerequisites[2]:" + PublishingCodes.PREREQUISITE_CERTIFICATION,
            "prerequisites[3]:" + PublishingCodes.PREREQUISITE_ONE_OF,
            "prerequisites[4]:" + PublishingCodes.PREREQUISITE_ONE_OF);
        assertThat(PrerequisiteRules.problems(DRAFT, List.of(new PrerequisiteRules.Wanted(null, "NOPE", "PASSED")),
            Map.of(), List.of())).extracting(v -> v.ruleCode()).containsExactly(PublishingCodes.PREREQUISITE_CERTIFICATION);
        // A certification takes no part in the chain: no cycle through it.
        assertThat(PrerequisiteRules.problems(DRAFT, List.of(new PrerequisiteRules.Wanted(null, "REFERRER", "PASSED")),
            Map.of(), List.of(new PrerequisiteRules.Edge(BASICS, DRAFT)))).isEmpty();
    }

    @Test
    void aCertificationRequiredIsMetByHoldingItOnly() {
        List<Prerequisite> prerequisites = List.of(new Prerequisite(null, "REFERRER", "PASSED"),
            new Prerequisite(BASICS, "PASSED"));
        List<Attempt> passed = List.of(new Attempt(BASICS, "SUBMITTED", true));
        assertThat(PrerequisiteCheck.unmet(prerequisites, passed, Set.of("REFERRER"))).isEmpty();
        assertThat(PrerequisiteCheck.unmet(prerequisites, passed, Set.of("SPONSOR_APPLY")))
            .extracting(Prerequisite::certification).containsExactly("REFERRER");
        // Without holdings given, a certification counts as not held.
        assertThat(PrerequisiteCheck.unmet(prerequisites, passed)).extracting(Prerequisite::certification)
            .containsExactly("REFERRER");
        // The tree: locked until held, then free to start.
        UUID node = UUID.randomUUID();
        assertThat(TreeStates.of(node, true, prerequisites, passed, Set.of())).isEqualTo(State.LOCKED);
        assertThat(TreeStates.of(node, true, prerequisites, passed, Set.of("REFERRER"))).isEqualTo(State.UNLOCKED);
    }

    @Test
    void theCheckWarnsWhenACertificationLagsTheTextRequiringConsent() {
        assertThat(CertificationLegalCheck.problems(Map.of("TERMS_APP", 1, "PRIVACY", 1))).isEmpty();
        assertThat(CertificationLegalCheck.problems(Map.of())).isEmpty();
        List<CheckProblem> problems = CertificationLegalCheck.problems(Map.of("PRIVACY", 2, "CONTEST_RULES", 1));
        assertThat(problems).extracting(CheckProblem::location).containsExactly("certification:TERMS_APP_UNDERSTOOD",
            "certification:TERMS_SPONSOR_UNDERSTOOD");
        assertThat(problems).allSatisfy(problem -> {
            assertThat(problem.severity()).isEqualTo(CheckProblem.Severity.WARNING);
            assertThat(problem.message()).contains("version 2 of PRIVACY");
        });
    }
}
