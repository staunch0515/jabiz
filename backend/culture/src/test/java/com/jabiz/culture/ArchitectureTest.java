package com.jabiz.culture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/** Business modules write no reactive code (CLAUDE.md section 3, docs/design/01-core-vs-runtime.md section 7). */
class ArchitectureTest {

    private static final JavaClasses CLASSES = new ClassFileImporter()
        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
        .importPackages("com.jabiz.culture");

    @Test
    void cultureIsFreeOfReactiveCode() {
        noClasses().that().resideInAPackage("com.jabiz.culture..")
            .should().dependOnClassesThat().resideInAnyPackage("reactor..", "org.reactivestreams..", "io.r2dbc..",
                "org.springframework.r2dbc..")
            .check(CLASSES);
    }
}
