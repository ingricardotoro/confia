package com.confia.architecture.fixture.entrypoints.admin;

import org.springframework.context.annotation.ComponentScan;

/**
 * Deliberate violation fixture (ADR-0024): an entry point that scans components, proving the
 * "no entry point scans components" rule of {@code BootstrapEntryPointRulesTest} rejects it. It
 * is deliberately not a {@code @SpringBootConfiguration}, so no Spring test that walks up the
 * packages can ever discover it as an application.
 */
@ComponentScan("com.confia.architecture.fixture.entrypoints")
public class ScanningEntryPoint {
}
