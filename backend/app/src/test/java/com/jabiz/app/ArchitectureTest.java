package com.jabiz.app;

import com.jabiz.app.archfixture.DirectNetworkCalls;
import com.jabiz.app.archfixture.ProcessMachinery;
import com.jabiz.runtime.process.StepHandler;
import com.jabiz.runtime.test.PlatformArchRules;
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
import java.util.List;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;

/**
 * Layering rules of docs/design/01-core-vs-runtime.md section 7 and docs/design/07-quality.md section 4.
 *
 * <p>Layers are told apart by package: jabiz-core is {@code com.jabiz..} outside {@code com.jabiz.runtime..}
 * and the business module {@code com.jabiz.app..}; extension modules such as ext-geo ({@code com.jabiz.ext..})
 * obey the same rules as the core, which they extend. Rules whose subject is empty fail (ArchUnit's default),
 * so a package rename cannot silently turn a rule into a no-op.
 */
class ArchitectureTest {

    private static final String RUNTIME = "com.jabiz.runtime..";
    private static final String BUSINESS = "com.jabiz.app..";
    private static final String EXTENSIONS = "com.jabiz.ext..";

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

    /** Extensions plug into the core through its SPIs; the platform works (and compiles) without them. */
    @Test
    void coreAndRuntimeDoNotDependOnExtensions() {
        noClasses().that().resideInAPackage("com.jabiz..").and().resideOutsideOfPackages(EXTENSIONS, BUSINESS)
            .should().dependOnClassesThat().resideInAPackage(EXTENSIONS)
            .check(CLASSES);
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

    /**
     * Business steps are ComputeStep or BlockingStep (synchronous); the reactive StepHandler is for the platform's own
     * I/O steps only (docs/design/06-process.md section 2).
     */
    @Test
    void businessStepsAreNeverStepHandlers() {
        noClasses().that().resideInAPackage(BUSINESS)
            .should().implement(StepHandler.class)
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

    /**
     * Business code reaches other systems only through the declared external services of a blocking step (decision
     * D38): the platform's shared rule, which application branches use the same way.
     */
    @Test
    void businessCodeUsesNoNetworkClient() {
        PlatformArchRules.noDirectNetworkAccess(BUSINESS).check(CLASSES);
    }

    /** The rule is not vacuous: each way around the external services fails it. */
    @Test
    void theNetworkRuleCatchesEveryDirectClient() {
        ArchRule rule = PlatformArchRules.noDirectNetworkAccess(BUSINESS);
        for (Class<?> offender : List.of(DirectNetworkCalls.JdkClient.class, DirectNetworkCalls.RawSocket.class,
            DirectNetworkCalls.UrlStream.class, DirectNetworkCalls.UrlConnection.class,
            DirectNetworkCalls.UrlContent.class, DirectNetworkCalls.SpringClients.class,
            DirectNetworkCalls.SpringRequestFactory.class, DirectNetworkCalls.SpringUrlResource.class,
            DirectNetworkCalls.Netty.class, DirectNetworkCalls.DomFromAddress.class,
            DirectNetworkCalls.SaxFromAddress.class, DirectNetworkCalls.ReaderFromAddress.class)) {
            assertThat(rule.evaluate(new ClassFileImporter().importClasses(offender)).hasViolation())
                .as(offender.getSimpleName()).isTrue();
        }
        for (Class<?> fine : List.of(ArchitectureTest.class, DirectNetworkCalls.DomFromText.class)) {
            assertThat(rule.evaluate(new ClassFileImporter().importClasses(fine)).hasViolation())
                .as(fine.getSimpleName()).isFalse();
        }
    }

    /** Business code uses ExternalServices only, never the platform's machinery behind it (decision D38). */
    @Test
    void businessCodeStaysOutOfThePlatformsExternalCallMachinery() {
        PlatformArchRules.noPlatformInternals(BUSINESS).check(CLASSES);
        ArchRule rule = PlatformArchRules.noPlatformInternals(BUSINESS);
        assertThat(rule.evaluate(new ClassFileImporter().importClasses(DirectNetworkCalls.ForgedScope.class))
            .hasViolation()).isTrue();
        assertThat(rule.evaluate(new ClassFileImporter().importClasses(ArchitectureTest.class)).hasViolation())
            .isFalse();
    }

    /** Business code starts no process and passes for nobody: the platform does, by the declarations (decision D41). */
    @Test
    void businessCodeStaysOutOfTheProcessMachinery() {
        PlatformArchRules.noProcessMachinery(BUSINESS).check(CLASSES);
        ArchRule rule = PlatformArchRules.noProcessMachinery(BUSINESS);
        for (Class<?> offender : List.of(ProcessMachinery.Executor.class, ProcessMachinery.EntryOption.class,
            ProcessMachinery.ActsAsSomeoneElse.class, ProcessMachinery.PassesForTheSystem.class)) {
            assertThat(rule.evaluate(new ClassFileImporter().importClasses(offender)).hasViolation())
                .as(offender.getSimpleName()).isTrue();
        }
        assertThat(rule.evaluate(new ClassFileImporter().importClasses(ProcessMachinery.ReadsTheActor.class))
            .hasViolation()).isFalse();
    }
}
