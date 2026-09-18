package com.confia.bootstrap;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Guardian- and student-facing portal process entry point (ADR-0003, ADR-0013). {@code
 * scanBasePackages} stays explicit so this process never auto-discovers an administrative
 * component by accident; no business module is registered yet.
 *
 * <p><strong>An administrative module must never be scanned here.</strong> That barrier is what
 * guarantees invoicing, cashbox, administrative users and the full audit trail are not even
 * loaded in memory in the process exposed to the open internet.
 */
@SpringBootApplication(scanBasePackages = "com.confia.bootstrap")
class PortalApplication {
}
