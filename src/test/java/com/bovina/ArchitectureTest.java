package com.bovina;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.*;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.junit.jupiter.api.Test;

class ArchitectureTest {
  private static final JavaClasses CODE =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .importPackages("com.bovina");

  @Test
  void coreDoesNotDependOnHttpSecurityOrSerialization() {
    noClasses()
        .that()
        .resideInAnyPackage("..domain..", "..application..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(
            "org.springframework.web..",
            "org.springframework.http..",
            "org.springframework.security..",
            "jakarta.servlet..",
            "com.fasterxml.jackson..",
            "tools.jackson..")
        .allowEmptyShould(true)
        .check(CODE);
  }

  @Test
  void apiDoesNotOwnPersistenceOrTransactions() {
    noClasses()
        .that()
        .resideInAPackage("..api..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(
            "org.springframework.data..",
            "jakarta.persistence..",
            "org.hibernate..",
            "org.springframework.transaction..",
            "jakarta.transaction..",
            "..infrastructure.persistence..")
        .allowEmptyShould(true)
        .check(CODE);
  }

  @Test
  void boundedContextsHaveNoCycles() {
    slices().matching("com.bovina.(*)..").should().beFreeOfCycles().check(CODE);
  }

  @Test
  void crossModuleAccessUsesApplicationContracts() {
    classes()
        .should(
            new ArchCondition<JavaClass>("use another module only through application contracts") {
              @Override
              public void check(JavaClass source, ConditionEvents events) {
                var sourceParts = source.getPackageName().split("\\.");
                if (sourceParts.length < 3) return; // Application composition root.
                for (var dependency : source.getDirectDependenciesFromSelf()) {
                  var target = dependency.getTargetClass();
                  var targetParts = target.getPackageName().split("\\.");
                  if (!target.getPackageName().startsWith("com.bovina.") || targetParts.length < 3)
                    continue;
                  boolean sameModule = sourceParts[2].equals(targetParts[2]);
                  boolean sharedPlatform = targetParts[2].equals("platform");
                  boolean applicationContract =
                      targetParts.length >= 4 && targetParts[3].equals("application");
                  if (!sameModule && !sharedPlatform && !applicationContract) {
                    events.add(SimpleConditionEvent.violated(source, dependency.getDescription()));
                  }
                }
              }
            })
        .check(CODE);
  }
}
