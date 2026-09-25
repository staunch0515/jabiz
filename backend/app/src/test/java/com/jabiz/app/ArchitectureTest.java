package com.jabiz.app;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.syntax.elements.GivenClassesConjunction;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;

/**
 * Layering rules of docs/design/01-core-vs-runtime.md section 7 and docs/design/07-quality.md section 4.
 *
 * <p>Layers are told apart by package: jabiz-core is {@code com.jabiz..} outside {@code com.jabiz.runtime..}
 * and the business module {@code com.jabiz.app..}. Rules whose subject is empty fail (ArchUnit's default),
 * so a package rename cannot silently turn a rule into a no-op.
 */
class ArchitectureTest {

    private static final String RUNTIME = "com.jabiz.runtime..";
    private static final String BUSINESS = "com.jabiz.app..";

    private static final JavaClasses CLASSES = new ClassFileImporter()
        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
        .importPackages("com.jabiz");

    private static GivenClassesConjunction coreClasses() {
        return noClasses().that().resideInAPackage("com.jabiz..").and().resideOutsideOfPackages(RUNTIME, BUSINESS);
    }

    @Test
    void coreIsFreeOfReactiveAndWebFrameworks() {
        ArchRule rule = coreClasses().should().dependOnClassesThat().resideInAnyPackage(
            "reactor..", "org.reactivestreams..", "io.r2dbc..", "org.springframework.r2dbc..",
            "org.springframework.web..", "org.springframework..");
        rule.check(CLASSES);
    }

    @Test
    void coreDependsNeitherOnRuntimeNorOnBusinessModules() {
        coreClasses().should().dependOnClassesThat().resideInAnyPackage(RUNTIME, BUSINESS).check(CLASSES);
    }

    @Test
    void runtimeContainsNoBusinessConcepts() {
        noClasses().that().resideInAPackage(RUNTIME)
            .should().dependOnClassesThat().resideInAPackage(BUSINESS)
            .check(CLASSES);
    }

    @Test
    void businessModuleDoesNotUseReactor() {
        noClasses().that().resideInAPackage(BUSINESS)
            .should().dependOnClassesThat().resideInAnyPackage("reactor..", "org.reactivestreams..")
            .check(CLASSES);
    }

    /** Extension points (RulePredicate, StepImplementation, and every later one in core) are synchronous. */
    @Test
    void coreAndBusinessMethodsNeverReturnReactiveTypes() {
        noMethods().that().areDeclaredInClassesThat().resideInAPackage("com.jabiz..")
            .and().areDeclaredInClassesThat().resideOutsideOfPackage(RUNTIME)
            .should().haveRawReturnType(JavaClass.Predicates.resideInAnyPackage("reactor..", "org.reactivestreams.."))
            .check(CLASSES);
    }

    /** Business time comes from the injected Clock only. */
    @Test
    void coreAndBusinessNeverReadTheSystemClock() {
        noClasses().that().resideInAPackage("com.jabiz..").and().resideOutsideOfPackage(RUNTIME)
            .should().callMethod(Instant.class, "now")
            .orShould().callMethod(LocalDateTime.class, "now")
            .orShould().callMethod(LocalDate.class, "now")
            .orShould().callMethod(OffsetDateTime.class, "now")
            .orShould().callMethod(ZonedDateTime.class, "now")
            .orShould().callMethod(System.class, "currentTimeMillis")
            .orShould().callMethod(Clock.class, "systemUTC")
            .orShould().callMethod(Clock.class, "systemDefaultZone")
            .check(CLASSES);
    }
}
