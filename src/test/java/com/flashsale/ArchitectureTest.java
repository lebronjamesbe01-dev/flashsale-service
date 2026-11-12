package com.flashsale;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import jakarta.persistence.Entity;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Executable architecture rules. Breaking a module boundary or a layer direction fails the build,
 * so the structure stays clean without relying on code review alone.
 *
 * <p>Rules on feature packages allow an empty match while those packages are still being created;
 * each phase that adds a module's classes makes its rules bite.
 */
class ArchitectureTest {

    private static final String ROOT = "com.flashsale";
    private static final List<String> MODULES =
            List.of("auth", "wallet", "catalog", "inventory", "flashsale", "order", "outbox");

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes =
                new ClassFileImporter()
                        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                        .importPackages(ROOT);
    }

    @Test
    void domainDependsOnNothingAboveIt() {
        noClasses()
                .that()
                .resideInAPackage(ROOT + "..domain..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        ROOT + "..api..", ROOT + "..application..", ROOT + "..infrastructure..")
                .allowEmptyShould(true)
                .check(classes);
    }

    @Test
    void domainDoesNotKnowAboutHttp() {
        noClasses()
                .that()
                .resideInAPackage(ROOT + "..domain..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("org.springframework.web..", "jakarta.servlet..")
                .allowEmptyShould(true)
                .check(classes);
    }

    @Test
    void applicationDoesNotDependOnApiOrInfrastructure() {
        noClasses()
                .that()
                .resideInAPackage(ROOT + "..application..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(ROOT + "..api..", ROOT + "..infrastructure..")
                .allowEmptyShould(true)
                .check(classes);
    }

    @Test
    void apiNeverTouchesEntities() {
        noClasses()
                .that()
                .resideInAPackage(ROOT + "..api..")
                .should()
                .dependOnClassesThat()
                .areAnnotatedWith(Entity.class)
                .allowEmptyShould(true)
                .check(classes);
    }

    @Test
    void sharedDoesNotDependOnFeatureModules() {
        String[] featurePackages =
                MODULES.stream().map(m -> ROOT + "." + m + "..").toArray(String[]::new);
        noClasses()
                .that()
                .resideInAPackage(ROOT + ".shared..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(featurePackages)
                .check(classes);
    }

    @Test
    void modulesTalkToEachOtherOnlyThroughApplication() {
        for (String module : MODULES) {
            String base = ROOT + "." + module;
            noClasses()
                    .that()
                    .resideOutsideOfPackage(base + "..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAnyPackage(
                            base + ".api..", base + ".domain..", base + ".infrastructure..")
                    .as("other modules may use '" + module + "' only via its application package")
                    .allowEmptyShould(true)
                    .check(classes);
        }
    }
}
