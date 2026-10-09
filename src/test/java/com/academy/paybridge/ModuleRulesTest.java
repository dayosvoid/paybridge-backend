package com.academy.paybridge;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class ModuleRulesTest {

    private static final List<String> MODULES = List.of(
            "customer", "account", "ledger", "transfer", "fx",
            "remittance", "settlement", "compliance", "notification");

    @Test
    void modulesOnlyTouchEachOtherThroughApi() {
        JavaClasses classes = new ClassFileImporter().importPackages("com.academy.paybridge");

        for (String module : MODULES) {
            noClasses()
                    .that().resideOutsideOfPackage("..paybridge." + module + "..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "..paybridge." + module + ".domain..",
                            "..paybridge." + module + ".repository..",
                            "..paybridge." + module + ".service..",
                            "..paybridge." + module + ".web..",
                            "..paybridge." + module + ".client..")
                    .because("other modules must use " + module + ".api only")
                    .check(classes);
        }
    }
}