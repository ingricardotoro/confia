package com.confia.organization.web.wiring;

import com.confia.organization.application.ManageAcademicYears;
import com.confia.organization.application.ManageGrades;
import com.confia.organization.application.ManageModalities;
import com.confia.organization.application.ManageSections;
import com.confia.organization.web.AcademicYearController;
import com.confia.organization.web.GradeController;
import com.confia.organization.web.ModalityController;
import com.confia.organization.web.SectionController;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The public configuration of the web layer of {@code organization} for the administrative process
 * (ADR-0024): it registers the four controllers of the academic structure and nothing else. The use
 * cases they take come from {@code AcademicStructureConfiguration}, which owns every use case of the
 * module; the controllers are registered here and not there because a controller is a class of the
 * {@code web} layer, and an {@code infrastructure} package must not depend on it.
 *
 * <p>The portal and the worker never load it: the isolation tests of the process beans fail if a
 * controller of this module reaches either context.
 */
@Configuration(proxyBeanMethods = false)
public class OrganizationWebConfiguration {

    @Bean
    AcademicYearController academicYearController(ManageAcademicYears years) {
        return new AcademicYearController(years);
    }

    @Bean
    ModalityController modalityController(ManageModalities modalities) {
        return new ModalityController(modalities);
    }

    @Bean
    GradeController gradeController(ManageGrades grades) {
        return new GradeController(grades);
    }

    @Bean
    SectionController sectionController(ManageSections sections) {
        return new SectionController(sections);
    }
}
