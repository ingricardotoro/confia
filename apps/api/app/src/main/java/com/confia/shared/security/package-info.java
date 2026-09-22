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
 */
package com.confia.shared.security;
