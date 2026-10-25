/**
 * The sensitive log guard, which every process installs before it creates its first bean
 * (background-jobs-with-db-scheduler design.md decision 13; CLAUDE.md regla 11). It lived in {@code
 * com.confia.shared.web.request}, a web package the worker process may not hold; it is neutral now,
 * because the worker process receives a {@code DataSource} and needs the same protection as the
 * web processes.
 *
 * <p>This package carries no layer segment ({@code domain}, {@code application}, {@code
 * infrastructure} or {@code web}), like {@link com.confia.shared.security}.
 *
 * <p><b>{@code @NamedInterface} (ADR-0022), and who consumes it.</b> The only consumer is {@code
 * bootstrap}: {@code ConfiaApplication} adds {@link
 * com.confia.shared.logging.SensitiveLogGuardInitializer} to each of the three process entry points
 * so the guard exists before any bean does. No business module imports it.
 */
@org.springframework.modulith.NamedInterface
package com.confia.shared.logging;
