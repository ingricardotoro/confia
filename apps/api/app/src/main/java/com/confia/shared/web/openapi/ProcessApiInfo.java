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
 * <p>The title comes from {@value #TITLE_PROPERTY}, which the launcher sets next to the choice of
 * process, so the title follows the process selection in a single place ({@code ConfiaApplication})
 * and not a {@code @Bean} repeated on each entry point.
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
