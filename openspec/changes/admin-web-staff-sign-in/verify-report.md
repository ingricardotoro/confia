# Informe de verificación: `admin-web-staff-sign-in`

- **Cambio:** `admin-web-staff-sign-in` (13b-1 de F0, parte 2 de 2)
- **Fecha:** 2026-11-10
- **Fase:** verificación (`sdd-verify`), diagnóstica; no condiciona el archivado
- **Árbol inspeccionado:** worktree `C:\dev\confia-frontend`, rama `docs/sdd-record-admin-web-ci-measure` (base `main` en `08e5143` más `3fd8556`). Todo lo implementado ya está fusionado en `main`.
- **Modo de TDD:** estricto; comandos por superficie de `tasks.md`.
- **Persistencia:** este archivo y Engram `sdd/admin-web-staff-sign-in/verify-report`.
- **Relación:** comparte las suites y el CI con `admin-web-foundations-and-session`; las corridas son las mismas y se detallan en su informe.

## Veredicto

**PASS WITH WARNINGS.** Ningún hallazgo crítico. Los 98 escenarios están nombrados en pruebas, los requisitos de mayor riesgo se cumplen en el código y el criterio de salida 8 está marcado con la nota exigida. Queda abierta la verificación final del propietario (9.4), y hay un riesgo de código menor sin decisión (`startEnrollment` sin captura de rechazos).

## 1. Completitud de tareas

| Tarea | Estado observado |
|---|---|
| 1 a 8 | Marcadas `[x]` con nota de cierre fechada; 4.4 cerrada el 2026-11-10 con la medida del CI (1,8 a 2,3 min frente a 20, `tasks.md:189`) |
| 9.1, 9.2, 9.3, 9.5 | Marcadas `[x]` |
| **9.4** Verificación final del propietario | **Sin marcar.** Razón registrada (`tasks.md:253`): es del propietario tras fusionar la cadena; incluye las evidencias remotas pendientes de la parte 1 (BX86) |

No hay subtareas sin marcar ni razón. Los cortes y las excepciones a las 800 líneas (2a con 848, 5 con 856) están aprobados por el propietario y registrados con fecha.

## 2. Cobertura por identificador

Método: el mismo de la parte 1 (encabezados `Escenario: [ID]` de `specs/**`, búsqueda por palabra completa en los 163 archivos de prueba versionados).

| Grupo | Escenarios | Nombrados en pruebas | Sin nombre | Observación |
|---|---|---|---|---|
| SS | 81 | 81 | Ninguno | Los de navegador (SS48, SS81 a SS85, SS100, SS102, SS113 a SS116) viven en `e2e/specs/journeys/*.e2e.ts` y corren en el trabajo de CI `admin-web e2e and accessibility` |
| AW | 5 | 5 | Ninguno | AW09 en `sign-in.e2e.ts` |
| BX | 6 | 6 | Ninguno | BX102 a BX105 y BX108 en `src/app/docs-notes.test.ts`; BX109 en `e2e/support/a11y-report.test.ts` |
| I | 6 | 6 | Ninguno | I309 es diferido al cambio 11 y solo figura en el inventario, como pide la especificación |
| **Total** | **98** | **98** | **0** | |

Que un identificador figure en una prueba no demuestra por sí solo la aserción; la sección 3 lo complementa con lectura dirigida.

## 3. Correctitud frente a las especificaciones (lectura dirigida)

| Requisito de alto riesgo | Evidencia | Resultado |
|---|---|---|
| Tokens y borrador solo en memoria | `session/access-session.ts:238-257` guarda la promesa por token en el cierre de `createAccessSession`; `session/enrollment-draft.ts` no guarda tokens; sin `localStorage`, `sessionStorage` ni `indexedDB` en `src/` | Cumple |
| Códigos de recuperación mostrados una sola vez | `session/enrollment-draft.ts:56-60`: al avanzar desde `secret` o `recovery-codes`, `recoveryCodes` pasa a `undefined` (SS93); probado en `enrollment-draft.test.ts` y en el contenedor | Cumple |
| QR solo con `QRCodeSVG` y sin `style` | `enrollment/components/TotpQrCode.tsx:16`: `QRCodeSVG` con `level="M"`, `marginSize={4}`, `role="img"` y `title` del catálogo, sin `style` ni `imageSettings` (SS83) | Cumple. Tamaño por defecto de la biblioteca, 128 px |
| Ningún secreto en almacenamiento, URL, historial, consola ni peticiones | `e2e/specs/journeys/secrets.e2e.ts` con centinelas generados en ejecución y búsqueda por token completo (`findTokenLeaks`); `session-secrets.flow.test.tsx` en jsdom | Cumple (en CI) |
| CSP estricta sin `eval` | La misma de la parte 1 (`apps/admin-web/vite.config.ts:39-52`); el fixture `page` falla ante cualquier `securitypolicyviolation`, lo que cubre el QR en Chromium | Cumple |
| Sin cadenas embebidas | Claves nuevas en `admin.json`, todas de `design.md` §8 según `apply-progress.md`; regla de ESLint de JSX en verde en CI | Cumple |
| Sin aritmética de dinero | El cambio no muestra importes; sin coincidencias de `amount`, `toFixed` ni `parseFloat` en `src/` | Cumple |
| Criterio de salida 8 | `docs/09-roadmap-y-fases.md:294-308`: marcado con nota fechada 2026-11-09 que cita la puerta (22 estados por 2 temas, 44 evaluaciones), el cambio y que los recorridos son simulados y la pila real es del cambio 11; la línea 308 dice que revertir la puerta o una pantalla evaluada lo desmarca con nota fechada | Cumple (BX102 a BX104, BX108) |

## 4. Coherencia con el diseño

Las desviaciones de `design.md` y de `tasks.md` están registradas con su razón en `apply-progress.md`, por tarea: memorización de la promesa fuera del borrador, `liveMessage` en lugar de `waiting` y `countdown.done`, región `aria-live` sin `role="status"`, aviso de la puerta por `refresh` rechazado, `SecondFactorEntry` dentro del contenedor, defecto de mayúsculas del simulador, `dist.e2e.ts` agregado con `git add -f` (ya resuelto: `.gitignore:14` tiene la excepción), `SecretStep.tsx` sin cambios, guarda por ubicación tras activar, `MINIMUM` escrito a mano con los 22 estados y orden de los commits de documentos en la tarea 9. Las advertencias de revisión R3-001 de la tarea 5, las dos de 8a y R3-001 y R3-002 de 9a se corrigieron con prueba primero; R3-003 (espera fija de 2 s en `enrollment.e2e.ts`) quedó aceptada sin cambio.

**No se encontraron desviaciones sin registrar** en la lectura dirigida. La evidencia de TDD se registra en tablas por tarea con el rojo observado y las demostraciones con `cmp` (24 menciones); en la tarea 9 dos superficies no tenían rojo posible y se declaró, con pruebas que plantan un valor en cada superficie para evitar un verde vacío.

## 5. Ejecución

Las mismas corridas del informe de la parte 1 (`openspec/changes/admin-web-foundations-and-session/verify-report.md`, sección 5), porque las suites son compartidas:

| Suite | Resultado |
|---|---|
| U `pnpm --filter @confia/admin-web test` | 1.ª corrida: salida 1, 749 pasan y 5 fallan por tiempo agotado bajo carga; tres son de este cambio: SS58 (`SignInContainer.test.tsx`), AW49 (`SignInContainer.states.test.tsx`) y el token de inscripción terminado (`EnrollmentContainer.test.tsx`). Reintento, sola: **salida 0, 69 archivos, 754 pasan, 2 `todo`** |
| T `pnpm --filter ./tooling test` | Salida 0, 286 pasan |
| C `pnpm --filter @confia/config test` | Salida 0, 48 pasan (este cambio no toca `packages/config`) |
| UI `pnpm --filter @confia/ui test` | No arranca con el puerto por defecto (`EACCES ::1:51730`); con `CONFIA_VITEST_API_PORT=52730`, 552 pasan y 1 falla por tiempo agotado (DS59). Este cambio no toca `packages/ui` |
| E y PR | No se corrieron, por instrucción. CI de `main`: `admin-web e2e and accessibility` en verde en la fusión de #293 (2 min 2 s) y en la de #291; medida histórica de 1,8 a 2,3 min en 8 ejecuciones |

## 6. Elementos abiertos para el propietario

- **9.4:** verificación final del propietario (Definición de terminado de `CLAUDE.md`, estados de carga, vacío y error de las tres pantallas).
- **BX86** de la parte 1 sigue sin evidencia remota; 9.4 la incluye.
- **Brecha de la pila real:** los siete recorridos corren contra la API simulada; la prueba contra la pila real es del cambio 11 (I307, I308, brecha fechada 2026-11-04). El criterio 8 se cerró sobre el simulador y la nota de `docs/09` lo dice.
- **`startEnrollment` sin captura de rechazos** (ver W2).
- **Tamaño del QR:** 128 px por defecto de la biblioteca (`apply-progress.md:425`), decisión pendiente del propietario si lo quiere mayor; cambiarlo exige revisar SS83, que compara con la salida de la biblioteca.
- **Inestabilidad conocida:** SS17 se corrigió en #293. El fallo transitorio de `code-rejected` (12,2 s una vez, `apply-progress.md:365`) y la espera fija de 2 s de SS82 son puntos sensibles.

## 7. Hallazgos

### CRITICAL

Ninguno.

### WARNING

1. **9.4 pendiente y BX86 heredado sin evidencia remota.** El archivo debe registrarlos como pendientes, no como observados.
2. **`startEnrollment` no captura un rechazo** (`apps/admin-web/src/features/identity/session/access-session.ts:238-257`). La promesa se memoriza en `starting` antes de resolverse, y solo se olvida cuando `enrollSecondFactor` devuelve un resultado distinto de `ok`. Si la llamada **lanza** (por ejemplo, un `TypeError` del transporte o un fallo dentro de `changeDraft`), `starting` conserva una promesa rechazada para ese token: cada reintento de la persona devuelve el mismo rechazo y no emite la petición siguiente, lo que contradice el espíritu de SS87 por esa vía. El riesgo es bajo, porque `shared/api` devuelve resultados en lugar de lanzar, y está registrado como decisión abierta (`apply-progress.md:478`), sin prueba. **Necesita una decisión del propietario.**
3. **Brecha de la pila real.** Todo el comportamiento de navegador de este cambio (inicio de sesión, verificación, inscripción y ausencia de secretos) se observó solo contra la API simulada. Está registrada y asignada al cambio 11, pero el criterio 8 queda cerrado sobre esa base.
4. **Tiempos agotados bajo carga** en tres suites jsdom de este cambio (SS58, AW49 y el token de inscripción terminado) en la primera corrida local; el reintento pasó completo. Las suites de importación en frío siguen sensibles a la carga a pesar de los tiempos explícitos.

### SUGGESTION

1. **Tamaño del QR (128 px):** confirmar con el propietario si basta para la lectura con el teléfono; cualquier cambio debe mantener SS83 (sin `style`).
2. **SS78 compara el portapapeles en texto** (`apply-progress.md:517`): si falla, el informe imprime el secreto ficticio de la ejecución. Compararlo como booleano, como ya hacen SS81 y SS100, alinea la prueba con la regla 11.
3. **Espera fija de 2 s en SS82** (`enrollment.e2e.ts`, R3-003 aceptada): vigilarla como posible fuente de inestabilidad o de falso verde.
4. **Registros desactualizados:** «Estado acumulado» de `apply-progress.md:8-17` dice «6 a 9 pendientes», y `apply-progress.md:572` dice que 4.4 y BX89 siguen pendientes, aunque 4.4 se cerró el 2026-11-10 (`tasks.md:189`) y BX89 está medido en el diseño de la parte 1 (`design.md:576`).

## 8. Lo que el archivador debe saber

- La implementación está completa y fusionada; solo queda abierta 9.4, del propietario.
- **Orden:** archivar primero `admin-web-foundations-and-session`. Los deltas `MODIFIED` de este cambio traen solo los escenarios que agregan y deben **sumarse** a lo que archive la parte 1, no reemplazarlo (`tasks.md:253` de esta parte y `tasks.md:279` de la parte 1).
- El criterio 8 está marcado en `docs/09`; los recorridos son simulados y la brecha de la pila real (cambio 11) debe quedar visible en el archivo.
- W2 (`startEnrollment`) no bloquea el archivado, pero debe quedar como elemento abierto con dueño.
