/**
 * The single transactional component of ADR-0015 rule 7: "En {@code shared/security} vive el único
 * componente que abre transacciones. Recibe el nivel de aislamiento requerido (por defecto {@code
 * READ COMMITTED}, {@code SERIALIZABLE} donde ADR-0010 lo exige), establece el contexto de
 * seguridad a nivel de fila como primera sentencia con parámetros vinculados ({@code docs/03}
 * sección 6.2), ejecuta el caso de uso y reintenta un número acotado de veces ante errores de
 * serialización o de interbloqueo."
 *
 * <p>This package carries no layer segment ({@code domain}, {@code application}, {@code
 * infrastructure} or {@code web}): {@link com.confia.architecture.LayeredArchitectureTest} treats a
 * package with no layer segment as outside the layered architecture entirely, so its dependencies
 * in and out are not evaluated by that rule at all (design.md, decision 1). {@link
 * com.confia.architecture.TransactionsOnlyInSharedSecurityTest} is the rule that actually confines
 * transaction-opening code to this package (R3).
 *
 * <p>{@link com.confia.shared.security.IdempotentExecutor} is this package's second inhabitant
 * (idempotency-key-infrastructure, design.md, decision 1 and decision 12, point 1). It satisfies R3
 * by composition, not by a second exception carved out for it: it never opens a transaction of its
 * own, delegating every write to {@link com.confia.shared.security.TransactionRunner#execute}, the
 * same single transactional component {@code TransactionsOnlyInSharedSecurityTest} already confines
 * to this package.
 *
 * <p><b>{@code @NamedInterface} (ADR-0022).</b> {@code identity-module-and-password-authentication}
 * is the first change with a production class in one business module — {@code identity} — that
 * imports a class from another module's nested package, here {@link
 * com.confia.shared.security.TransactionRunner}. Spring Modulith's {@code
 * ApplicationModules.verify()} treats every direct sub-package of {@code com.confia} as its own
 * module and rejects a cross-module import of a non-API internal package (sonda S1, design.md,
 * decision 12): this annotation is what makes this package that API, narrowly, without opening the
 * whole {@code shared} module.
 */
@org.springframework.modulith.NamedInterface
package com.confia.shared.security;
