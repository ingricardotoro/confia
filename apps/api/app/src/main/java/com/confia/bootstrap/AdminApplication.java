package com.confia.bootstrap;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Administrative process entry point (ADR-0003, ADR-0013). {@code scanBasePackages} stays
 * explicit so this process never auto-discovers a portal-only or worker-only component by
 * accident; no business module is registered yet.
 *
 * <p><strong>A module built for the portal must never be scanned here, and vice versa.</strong>
 * That separation is what keeps administrative code out of the process exposed to guardians.
 */
@SpringBootApplication(scanBasePackages = "com.confia.bootstrap")
class AdminApplication {
}
