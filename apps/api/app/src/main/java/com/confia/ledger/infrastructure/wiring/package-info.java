/**
 * The administrative process's registration of the {@code ledger} module: {@link
 * com.confia.ledger.infrastructure.wiring.LedgerConfiguration} declares, explicitly, the jOOQ adapter and the services.
 *
 * <p><b>{@code @NamedInterface}, and who consumes it</b> (ADR-0022). The consumer is {@code
 * bootstrap.admin.AdminApplication}, which {@code @Import}s the configuration explicitly, with no component scan
 * (ADR-0024). The portal and the worker never load it.
 */
@org.springframework.modulith.NamedInterface
package com.confia.ledger.infrastructure.wiring;
