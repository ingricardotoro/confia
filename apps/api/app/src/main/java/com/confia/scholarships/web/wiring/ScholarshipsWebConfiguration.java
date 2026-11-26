package com.confia.scholarships.web.wiring;

import com.confia.scholarships.application.DecideAward;
import com.confia.scholarships.application.ManageScholarships;
import com.confia.scholarships.application.RequestAward;
import com.confia.scholarships.application.ViewAwards;
import com.confia.scholarships.web.ScholarshipAwardController;
import com.confia.scholarships.web.ScholarshipController;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The public configuration of the web layer of {@code scholarships} for the administrative process (ADR-0024): it registers the
 * controllers of the scholarships and of their awards and nothing else. The use cases it takes come from {@code
 * ScholarshipsConfiguration}; the controllers are registered here and not there because a controller is a class of the {@code web}
 * layer, and an {@code infrastructure} package must not depend on it.
 *
 * <p>The portal and the worker never load it: the isolation tests of the process beans fail if a controller of this module reaches
 * either context.
 */
@Configuration(proxyBeanMethods = false)
public class ScholarshipsWebConfiguration {

    @Bean
    ScholarshipController scholarshipController(ManageScholarships manageScholarships) {
        return new ScholarshipController(manageScholarships);
    }

    @Bean
    ScholarshipAwardController scholarshipAwardController(RequestAward requestAward, DecideAward decideAward,
            ViewAwards viewAwards) {
        return new ScholarshipAwardController(requestAward, decideAward, viewAwards);
    }
}
