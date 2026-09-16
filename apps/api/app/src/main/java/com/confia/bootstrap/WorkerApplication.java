package com.confia.bootstrap;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Background worker process entry point (ADR-0003, ADR-0013), started without an HTTP server
 * ({@link ConfiaApplication} forces {@code WebApplicationType.NONE}). {@code scanBasePackages}
 * stays explicit; no business module or background job is registered yet (db-scheduler tasks
 * arrive with the modules that need them, ADR-0016).
 */
@SpringBootApplication(scanBasePackages = "com.confia.bootstrap")
class WorkerApplication {
}
