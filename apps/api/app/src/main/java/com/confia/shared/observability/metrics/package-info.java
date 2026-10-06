/**
 * The adapters that carry what the code reports as metrics toward the place that stores it
 * (docs/07-observabilidad-y-operaciones.md section 1.1). Business and edge code declares a port and
 * never imports a metrics library; the adapter lives here, so the provider can change without
 * touching it.
 *
 * <p><b>{@code @NamedInterface}, and who consumes it</b> (ADR-0022: every new named interface of
 * {@code shared} names the real consumer that justifies it). The consumer is the administrative
 * entry point, {@code bootstrap.admin.AdminApplication}, which {@code @Import}s {@link
 * com.confia.shared.observability.metrics.ObservabilityMetricsConfiguration} explicitly, with no
 * component scan (ADR-0024). The portal and the worker do not load it.
 */
@org.springframework.modulith.NamedInterface
package com.confia.shared.observability.metrics;
