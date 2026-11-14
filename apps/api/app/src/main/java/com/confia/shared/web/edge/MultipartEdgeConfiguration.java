package com.confia.shared.web.edge;

import com.confia.shared.web.problem.ProblemResponses;
import com.confia.shared.web.request.MultipartScopeFilter;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The multipart scope of the administrative process: {@code multipart/*} requests are accepted only on the routes that
 * take an upload, which today are the simulation and the application of the student import
 * ({@code /api/v1/imports/students/}), and are refused with 415 everywhere else before they are read. A new upload
 * route is a visible change to {@link #UPLOAD_PATHS}. Only {@code AdminApplication} loads it. The portal and the worker
 * exclude the multipart auto-configuration altogether, so their servlet has no multipart configuration and no
 * resolver, and no upload is ever parsed there.
 */
@Configuration(proxyBeanMethods = false)
public class MultipartEdgeConfiguration {

    /** The servlet-path prefixes that accept {@code multipart/*}. */
    static final List<String> UPLOAD_PATHS = List.of("/api/v1/imports/students/");

    @Bean
    MultipartScopeFilter multipartScopeFilter(ProblemResponses problems) {
        return new MultipartScopeFilter(problems, UPLOAD_PATHS);
    }
}
