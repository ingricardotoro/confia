package com.confia.shared.web.openapi;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import java.util.Objects;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.core.env.Environment;

/**
 * Names the document after the process that serves it (frontend-monorepo-and-contracts-pipeline,
 * design.md decision 1): the administrative and the portal documents must be told apart even
 * while neither has an operation yet.
 *
 * <p>The title comes from {@value #TITLE_PROPERTY}, which the launcher sets per process, and
 * <b>not</b> from a {@code @Bean} declared on each entry point. The three entry points share the
 * package {@code com.confia.bootstrap} and each scans it, so a bean declared on one of them could
 * be picked up by the others' contexts as well.
 */
public final class ProcessApiInfo implements OpenApiCustomizer {

    public static final String TITLE_PROPERTY = "confia.openapi.title";

    static final String API_VERSION = "v1";

    private final Environment environment;

    public ProcessApiInfo(Environment environment) {
        this.environment = Objects.requireNonNull(environment, "environment");
    }

    @Override
    public void customise(OpenAPI openApi) {
        openApi.info(new Info()
                .title(environment.getRequiredProperty(TITLE_PROPERTY))
                .version(API_VERSION));
    }
}
