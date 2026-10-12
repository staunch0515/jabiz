package com.jabiz.quizbuks;

import com.jabiz.runtime.test.PlatformArchRules;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/** Business modules write no reactive code (CLAUDE.md section 3, docs/design/01-core-vs-runtime.md section 7). */
class ArchitectureTest {

    private static final JavaClasses CLASSES = new ClassFileImporter()
        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
        .importPackages("com.jabiz.quizbuks");

    @Test
    void quizbuksIsFreeOfReactiveCode() {
        noClasses().that().resideInAPackage("com.jabiz.quizbuks..")
            .should().dependOnClassesThat().resideInAnyPackage("reactor..", "org.reactivestreams..", "io.r2dbc..",
                "org.springframework.r2dbc..")
            .check(CLASSES);
    }

    /**
     * Stripe is reached through the declared external service only, by the jobs' handlers, which stay out of the
     * database (decision D38; docs/quizbuks/plans/Q6-payments.md, D-Q6-14).
     */
    @Test
    void quizbuksCallsOtherSystemsOnlyThroughThePlatform() {
        PlatformArchRules.noDirectNetworkAccess("com.jabiz.quizbuks..").check(CLASSES);
        PlatformArchRules.noPlatformInternals("com.jabiz.quizbuks..").check(CLASSES);
        PlatformArchRules.handlersStayOutOfTheDatabase("com.jabiz.quizbuks..").check(CLASSES);
    }

    /** QuizBuks starts no process and passes for nobody: the platform does, by the declarations (decision D41). */
    @Test
    void quizbuksStaysOutOfTheProcessMachinery() {
        PlatformArchRules.noProcessMachinery("com.jabiz.quizbuks..").check(CLASSES);
    }
}
