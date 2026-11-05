package com.acme.insurance.pas.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/** ADR 0001 D1: module dependency rules, checked on the compiled classes of every PAS module. */
@AnalyzeClasses(
    packages = "com.acme.insurance.pas",
    importOptions = ImportOption.DoNotIncludeTests.class)
class ModuleDependencyRulesTest {

  static final String DOMAIN = "com.acme.insurance.pas.domain..";
  static final String RATING = "com.acme.insurance.pas.rating..";
  static final String UNDERWRITING = "com.acme.insurance.pas.underwriting..";
  static final String PERSISTENCE = "com.acme.insurance.pas.persistence..";
  static final String EVENTS = "com.acme.insurance.pas.events..";
  static final String POLICY_SERVICE = "com.acme.insurance.pas.policyservice..";
  static final String BATCH = "com.acme.insurance.pas.batch..";

  @ArchTest
  static final ArchRule moduleLayout =
      layeredArchitecture()
          .consideringOnlyDependenciesInLayers()
          .withOptionalLayers(true)
          .layer("Domain")
          .definedBy(DOMAIN)
          .layer("Rating")
          .definedBy(RATING)
          .layer("Underwriting")
          .definedBy(UNDERWRITING)
          .layer("Persistence")
          .definedBy(PERSISTENCE)
          .layer("Events")
          .definedBy(EVENTS)
          .layer("PolicyService")
          .definedBy(POLICY_SERVICE)
          .layer("Batch")
          .definedBy(BATCH)
          .whereLayer("Rating")
          .mayOnlyBeAccessedByLayers("PolicyService", "Batch")
          .whereLayer("Underwriting")
          .mayOnlyBeAccessedByLayers("PolicyService", "Batch")
          .whereLayer("Persistence")
          .mayOnlyBeAccessedByLayers("PolicyService", "Batch")
          .whereLayer("Events")
          .mayOnlyBeAccessedByLayers("PolicyService", "Batch")
          .whereLayer("PolicyService")
          .mayNotBeAccessedByAnyLayer()
          .whereLayer("Batch")
          .mayNotBeAccessedByAnyLayer()
          .because(
              "libraries depend on pas-domain only, applications never depend on each other"
                  + " (ADR 0001 D1)");

  @ArchTest
  static final ArchRule domainHasNoFrameworkOrModuleDependencies =
      noClasses()
          .that()
          .resideInAPackage(DOMAIN)
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "org.springframework..",
              "jakarta.persistence..",
              "org.hibernate..",
              RATING,
              UNDERWRITING,
              PERSISTENCE,
              EVENTS,
              POLICY_SERVICE,
              BATCH)
          .allowEmptyShould(true)
          .because("pas-domain depends on nothing (ADR 0001 D1)");

  @ArchTest
  static final ArchRule batchNeverDependsOnPolicyService =
      noClasses()
          .that()
          .resideInAPackage(BATCH)
          .should()
          .dependOnClassesThat()
          .resideInAPackage(POLICY_SERVICE)
          .because("pas-batch may use every library module except pas-policy-service");

  @ArchTest
  static final ArchRule everyClassLivesInAModulePackage =
      classes()
          .that()
          .resideInAPackage("com.acme.insurance.pas..")
          .should()
          .resideInAnyPackage(
              DOMAIN, RATING, UNDERWRITING, PERSISTENCE, EVENTS, POLICY_SERVICE, BATCH)
          .because("the module dependency rules are expressed per module package");
}
