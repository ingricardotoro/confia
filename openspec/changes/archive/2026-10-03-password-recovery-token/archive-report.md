# Informe de archivado: `password-recovery-token`

- **Fecha de archivado:** 2026-10-03
- **Cambio 7 de F0, tercero de tres partes.** Resta pendiente `session-tokens-and-web-layer`.
- **Tareas:** 14 de 14 cerradas.
- **Entregado en seis pull requests** fusionados a `main`: #73 (C1a), #74 (C1b), #75 (C4c) y #76 (C5). El prefijo indica los cortes de C2, C3 y C4 que ya estaban fusionados.
- **Verificación:** no se ejecutó fase de verificación separada; todos los cortes pasaron `./mvnw verify` en verde con CI. La integración continua en PR #76 reportó `BUILD SUCCESS: 186 core / 399 unit / 225 integration tests`.

## Capacidades publicadas (delta specs)

Dos deltas de especificación archivados íntegros:

| Capacidad | Delta | Requisitos | Escenarios | Ubicación en archivo |
|---|---|---|---|---|
| `identity` | ADDED + MODIFIED | 17 new + 4 modified | 34 new + 12 modified | `specs/identity/spec.md` |
| `build-integrity` | ADDED | 2 new | 7 new | `specs/build-integrity/spec.md` |

**Composición de especificaciones:** Los deltas se han compuesto en las especificaciones principales (`openspec/specs/identity/spec.md` y `openspec/specs/build-integrity/spec.md`) siguiendo el precedente manual del cambio anterior (2026-09-30-column-encryption-and-mfa-totp). Se aplicaron las tres comprobaciones del precedente:

| Capacidad | Antes | Después | Delta aplicado | Verificaciones |
|---|---|---|---|---|
| `identity` | 29 req / 56 escen | **46 req / 95 escen** | 17 añadidos + 4 sustituidos | ✓ Prefijo intacto, ✓ Bytes concordantes, ✓ Conteos válidos |
| `build-integrity` | 51 req / 125 escen | **53 req / 132 escen** | 2 añadidos | ✓ Prefijo intacto, ✓ Bytes concordantes, ✓ Conteos válidos |

**Detalle de sustituciones (MODIFIED requirements):**
1. «Recuperación de contraseña con token de un solo uso y de corta vida» — precisión sobre vigencia desde emisión, borde estricto de 30:00, atomicidad del consumo y cambio.
2. «Prohibición de enumeración de usuarios» — extensión al nivel del caso de uso, resultado uniforme, asientos idénticos, motivos solo en bitácora.
3. «Ausencia de verificación contra contraseñas comprometidas y de rehash transparente» — reasignación del destino de la lista comprometida a cambio propio; extensión al restablecimiento.
4. «Ausencia de envío real del aviso de códigos de recuperación de MFA bajos» — asignación de destino a `transactional-email-adapter` (cambio 14); extensión a restablecimiento.

**Resultado:** Composición completada con diff verificado: `+609 líneas, -33 líneas` (neto +576 adiciones de requisitos y escenarios).

## Contenido del archivo

Todos los artefactos SDD de este cambio:

- ✓ **proposal.md** (27 KiB): propuesta aprobada por el propietario del producto el 2026-09-30, con respuestas a exploración y ronda de preguntas. Contiene el alcance, enfoque, riesgos y plan de reversión.
- ✓ **design.md** (57 KiB): diseño aprobado por el propietario el 2026-09-30, con 12 decisiones de arquitectura citadas en las pruebas, sondas S1-S4, flujo de datos y evidencia del árbol.
- ✓ **tasks.md** (38 KiB): 14 tareas en 5 cortes (C1-C5), con trazabilidad de los 53 escenarios especificados. Discrepancias reportadas durante especificación (5 puntos).
- ✓ **apply-progress.md** (33 KiB): registro de ejecución de cada tarea per SDD protocolo. Contiene observancias de implementación (comprobaciones de rojo/verde, TDD estricto) y un control negativo de segundo factor sin reversión.
- ✓ **exploration.md** (22 KiB): exploración previa a propuesta, con siete respuestas del propietario del 2026-09-30 y nueve hallazgos clave (H1-H9).
- ✓ **specs/identity/spec.md** (delta, línea 1-600): especificación de los requisitos nuevos y modificados de la identidad.
- ✓ **specs/build-integrity/spec.md** (delta, línea 1-86): especificación de los requisitos nuevos de integridad.

## Requisitos entregados

### Identidad (21 nuevos o modificados)

**ADDED (17 requisitos, 34 escenarios):**

1. La solicitud de recuperación solo programa la emisión del token
2. El token se almacena únicamente como su SHA-256
3. Límite de tres emisiones por hora por cuenta
4. A lo sumo un token vivo por cuenta, también bajo concurrencia
5. Segundo factor exigido en el restablecimiento cuando la cuenta tiene MFA activa
6. Una cuenta sin MFA activa restablece solo con el enlace
7. Un segundo factor incorrecto en el restablecimiento avanza el mismo retroceso TOTP que el inicio de sesión
8. La contraseña nueva tiene entre 12 y 128 caracteres
9. La institución del restablecimiento proviene de la configuración del proceso
10. Auditoría del ciclo de recuperación, atómica con su efecto
11. Ningún secreto del ciclo de recuperación es observable en registros, excepciones, toString() ni pruebas
12. Ausencia de revocación de sesiones al restablecer (brecha con destino: `session-tokens-and-web-layer`, condición dura de aceptación)
13. Ausencia de programación real de la emisión (brecha con destino: cambio 9)
14. Ausencia de envío real del enlace y de la notificación al titular (brecha con destino: `transactional-email-adapter`, cambio 14 de F0)
15. Ausencia del límite por dirección IP y de la respuesta HTTP de la recuperación (brecha con destino: `session-tokens-and-web-layer` y cambio 11)
16. Ausencia de purga de tokens vencidos (brecha con destino: cambio 9)
17. Ausencia del restablecimiento administrativo (brecha con destino: cambio 8 y `session-tokens-and-web-layer`)

**MODIFIED (4 requisitos, 12 escenarios):**

1. Recuperación de contraseña con token de un solo uso y de corta vida (vigencia desde emisión, borde estricto de 30:00, consumo y cambio atómicos)
2. Prohibición de enumeración de usuarios (resultado uniforme al nivel del caso de uso, mismo número de asientos, motivo solo en bitácora)
3. Ausencia de verificación contra contraseñas comprometidas y de rehash transparente (destino de lista comprometida reassignado a cambio propio; rehash mantiene destino)
4. Ausencia de envío real del aviso de códigos de recuperación de MFA bajos (destino reassignado a `transactional-email-adapter`, cambio 14)

### Integridad de construcción (2 nuevos)

**ADDED (2 requisitos, 7 escenarios):**

1. Tabla nueva de tokens de recuperación de contraseña, con `institution_id` y seguridad de fila forzada (migración V7, prefijo `identity_`, SRY habilitado/forzado, política de institución, índices únicos parciales, restricción de formato del hash)
2. Permisos de acceso a la tabla de tokens de recuperación por rol de base de datos (SELECT, INSERT, UPDATE para `confia_admin_app`; SELECT para `confia_readonly`; sin DELETE; sin privilegios para `confia_portal_app`)

## Estado de tareas

Las 14 tareas se cerraron conforme al plan (tasks.md):

| Corte | Tareas | Resultado |
|---|---|---|
| C1 | Migración V7, puerto y adaptador del token, bloqueo de la cuenta | ✓ 3/3 completas |
| C2 | Dominio: token, política del token, longitud, TotpCode sin fuga | ✓ 3/3 completas |
| C3 | Solicitud de recuperación y emisión | ✓ 3/3 completas |
| C4 | Restablecimiento sin segundo factor, con segundo factor, concurrencia | ✓ 3/3 completas |
| C5 | Redacción con control negativo, inventario de ausencias, documentación | ✓ 2/2 completas |

**Total:** 14 de 14 tareas cerradas.

## Cobertura de pruebas

Per apply-progress.md (estado final de C5) y CI en PR #76:

- **Unitarias:** JUnit con AssertJ; cobertura JaCoCo y mutación PIT según criterios de `openspec/config.yaml`
- **Integración:** Todas las pruebas `*IT` contra PostgreSQL real en contenedor
- **Arquitectura:** ArchUnit, inventario de ausencias, trazabilidad de escenarios
- **Redacción:** Control negativo en `IdentitySecretRedactionIT`, accesorio `LeakingPasswordResetTokenFixture`

El último corte C5 pasó `./mvnw -B clean verify` con BUILD SUCCESS en checkout limpio. CI en PR #76 confirmó: 186 core tests, 399 unit tests, 225 integration tests.

## Observaciones de final-state (jerarquía de autoridad)

Fuentes de verdad en orden decreciente de autoridad (per SKILL.md):

1. **Tareas persisted (ranking 1):** `tasks.md` archivado muestra 14/14 completadas. Todas las subtareas fueron observadas en ROJO/VERDE dentro de su tarea padre.
2. **Hechos finales del lanzamiento (ranking 2):** "All five cuts are merged into main: C1a/C1b, C2, C3a/C3b, C4a (#73), C4b (#74), C4c (#75), C5 (#76, merge commit 4e18ef8, 2026-10-04). PR #76 CI green."
3. **apply-progress.md (ranking 3, snapshot intermedia):** Registra estado al cierre de cada tarea; válido para ese momento; puede rebasarse por commits posteriores. No reportar su "pending" como estado final si las tareas muestran "done".

**Resolución de disparidades:** No se encontraron contradicciones entre rangos. La tarea final (5.2) reportó seis escenarios sin implementación en este cambio (I24, I42, I44, I45: pruebas existentes; I23 segunda mitad, I46: cobertura en cambio siguiente). El estado final concuerda: tareas al 100 %, especificación completa, brechas con destino nombrado, pruebas existentes citadas.

## Verificación y resultados

**Verificación:**
- ✗ No se ejecutó `sdd-verify` como fase separada (verificación es opcional per SKILL.md).
- ✓ Todos los cortes pasaron `./mvnw -B verify` en verde localmente.
- ✓ PR #76 (corte C5) pasó CI: `backend-verify`, `frontend-verify`, `security-scanning` (todos green).
- ✓ Integración continua confirmó BUILD SUCCESS: 186 core tests, 399 unit tests, 225 integration tests.

**Findings:**
- Ningún bloqueo al final.
- Discrepancias en especificación reportadas y resueltas durante design.md (5 puntos en tasks.md).
- Un control negativo intencional en C4: segundo factor sin reversión se verificó contra una variante que lanza excepción; la prueba falla correctamente contra la variante, validando la detección.

## Próximos pasos

**Para completar el ciclo SDD:**

1. Componer deltas en specs principales (`openspec/specs/identity/spec.md` y `openspec/specs/build-integrity/spec.md`) siguiendo el precedente manual del cambio anterior. Ver sección "Capacidades publicadas" para instrucciones.
2. Cambio `session-tokens-and-web-layer` (cambio 7 de F0, cuarta parte): hereda dos condiciones duras de aceptación (revocar todas las familias al restablecer, no fusionar endpoint sin límite por IP). Véase proposal.md sección "Dependencias".

**Cambios que dependen de este (cambio 9 en adelante):**
- Cambio 9 (`background-jobs-with-db-scheduler`): adaptador del puerto de programación, purga de tokens vencidos.
- Cambio 14 (`transactional-email-adapter`): adaptador del puerto de envío, notificación al titular.

## Artefactos de observación en Engram

Si se persisten en Engram:
- Proposal: `sdd/password-recovery-token/proposal`
- Spec: `sdd/password-recovery-token/spec`
- Design: `sdd/password-recovery-token/design`
- Tasks: `sdd/password-recovery-token/tasks`
- Apply-progress: `sdd/password-recovery-token/apply-progress` (snapshot intermedia)
- Este archive-report: `sdd/password-recovery-token/archive-report`

---

**Archivado:** 2026-10-03
**Estado:** Cambio 7 completo en sus tres partes primeras; ciclo SDD cerrado salvo composición de especificaciones.
