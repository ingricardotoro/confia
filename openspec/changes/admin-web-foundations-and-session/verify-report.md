# Informe de verificación: `admin-web-foundations-and-session`

- **Cambio:** `admin-web-foundations-and-session` (13b-1 de F0, parte 1 de 2)
- **Fecha:** 2026-11-10
- **Fase:** verificación (`sdd-verify`), diagnóstica; no condiciona el archivado
- **Árbol inspeccionado:** worktree `C:\dev\confia-frontend`, rama `docs/sdd-record-admin-web-ci-measure` (base `main` en `08e5143` más el commit de documentos `3fd8556`). Todo lo implementado ya está fusionado en `main`.
- **Modo de TDD:** estricto (`openspec/config.yaml`); los comandos por superficie son los de `tasks.md`.
- **Persistencia:** este archivo y Engram `sdd/admin-web-foundations-and-session/verify-report`.

## Veredicto

**PASS WITH WARNINGS.** Ningún hallazgo crítico. Las suites unitarias pasan en las cuatro superficies; en `admin-web` hizo falta un reintento por tiempos agotados bajo carga, y en `packages/ui` la suite no arranca con el puerto por defecto y quedó una prueba con tiempo agotado (sección 5). El CI de `main` está en verde en los trabajos del frontend, y los requisitos de mayor riesgo se cumplen en el código. Quedan abiertas dos subtareas del propietario (12.4 y 12.6) y un escenario sin ninguna evidencia (BX82).

## 1. Completitud de tareas

| Tarea | Estado observado |
|---|---|
| 1 a 11 | Marcadas `[x]`, con evidencia fechada en `tasks.md` y `apply-progress.md` |
| 12.1, 12.2, 12.3, 12.5 | Marcadas `[x]` |
| **12.4** Evidencia remota BX86 | **Sin marcar.** Razón registrada (`tasks.md:231-232`): el informe subido desde una ejecución en rojo exige provocarla en el CI, decisión del propietario. La medida del tiempo del trabajo (1,8 a 2,3 min frente a 20) sí está registrada |
| **12.6** Verificación final del propietario | **Sin marcar.** Razón registrada (`tasks.md:234`): es del propietario tras fusionar la cadena |

No se encontraron subtareas sin marcar ni razón. Observación: la evidencia de la tarea 2 (`tasks.md:146`) todavía dice «Pendiente: 2.4 ... la tarea 2 queda sin marcar», pero la tarea está marcada y la medida existe (`design.md:576`, `apply-progress.md:186-188`); el texto quedó desactualizado.

## 2. Cobertura por identificador

Método: se extrajeron los identificadores de los encabezados `Escenario: [ID]` de `specs/**` (224 identificadores más los tres escenarios conservados de TOTP sin identificador, 227 en total, igual que `tasks.md:257`) y se buscó cada uno, como palabra completa, en los 163 archivos de prueba versionados de `apps/admin-web`, `packages/ui`, `packages/config` y `tooling` (`*.test.*`, `*.e2e.ts`, `*.test-d.ts`, `a11y-matrix`, `a11y-report`). Nombrar un identificador en una prueba no demuestra por sí solo la aserción; se complementó con la lectura dirigida de la sección 3.

| Grupo | Escenarios | Nombrados en pruebas | Sin nombre en pruebas | Cómo se cubren los restantes |
|---|---|---|---|---|
| AW | 59 | 58 | AW10 | Comprobación de `git` en esta fase: ninguno de los 53 commits que tocan `apps/admin-web` toca `apps/api` |
| BX | 67 | 65 | BX82, BX89 | BX89: medida remota registrada en `design.md:576` (1 min 51 s y 2 min 11 s frente a 10 min). **BX82: sin prueba ni registro** en `apply-progress.md` |
| DS | 50 | 46 | DS65, DS70, DS87, DS97 | Comportamiento de la puerta de historias existente (trabajo `design system accessibility`, verde en CI); ninguna prueba los nombra y `apply-progress.md` solo menciona DS97 una vez |
| SS | 46 | 46 | Ninguno | |
| I | 2 con identificador + 3 conservados | 2 | Ninguno | Los tres conservados de TOTP viven en `apps/api`; el trabajo `backend verify` está en verde en `main` |
| **Total** | **227** | **217** | **7** | |

BX86 está nombrado en `tooling/src/ci-workflow.test.ts` (estructura del trabajo e `if: always()`), pero su cláusula remota, el informe subido desde una ejecución en rojo, **no está observada** (12.4).

## 3. Correctitud frente a las especificaciones (lectura dirigida)

| Requisito de alto riesgo | Evidencia | Resultado |
|---|---|---|
| Tokens solo en memoria del módulo | `apps/admin-web/src/features/identity/session/token-store.ts:31-53` (variables del cierre `access` y `secondFactor`); `git grep` de `localStorage`, `sessionStorage` e `indexedDB` en `src/` sin pruebas: sin coincidencias | Cumple |
| Ningún secreto en almacenamiento, URL ni registros | Único `console.warn` en `src/shared/api/report-unknown-problem.ts:23`, sin cuerpo (AW55); `session-secrets.flow.test.tsx` serializa las cachés (SS06) | Cumple |
| Cookie CSRF solo leída, nunca escrita | Única lectura en `src/shared/api/csrf-cookie.ts:9`; regla `NO_COOKIE_WRITE` de `packages/config/eslint.js` | Cumple |
| Bloqueo de sesión entre pestañas | `src/features/identity/session/refresh-coordinator.ts:5` (`confia.admin.session.refresh`); `session/index.ts:10` usa `undefined` sin `navigator.locks` (SS18) | Cumple |
| CSP estricta sin `eval` | `apps/admin-web/vite.config.ts:39-52` (`default-src 'none'`, `script-src 'self'`, `style-src 'self'`, sin `unsafe-eval` ni `unsafe-inline`), servida en `preview` (`vite.config.ts:69`); `src/app/zod-config.ts:4-5` activa `jitless` para que Zod no intente compilar | Cumple. La CSP rige solo en `preview`, como pide D4; `dev` no la envía |
| Sin cadenas embebidas ni aritmética de dinero | Reglas `jsxTextControls` y `APP_MONEY` de `packages/config/eslint.js`, aplicadas por `lint` (verde en CI); `git grep` de `amount`, `toFixed` y `parseFloat` en `apps/admin-web/src` sin pruebas: sin coincidencias | Cumple |
| `apps/api` sin cambios (AW10) | Ver sección 2 | Cumple |
| Criterio de salida 8 | La parte 1 no lo marca; lo marca la parte 2 en `docs/09-roadmap-y-fases.md:294-306` | Cumple |

## 4. Coherencia con el diseño

Las desviaciones de `design.md` están registradas y justificadas en `apply-progress.md` por tarea: expresión de `admin-presentation-is-pure`, `cors: false`, `vite.config.ts` como función, `isRetryable` afinado, nombres por `operationId`, AW20 a AW22 en `src/test/`, claves nuevas aprobadas, `jitless` y cortes de tareas con excepciones a las 800 líneas aprobadas por el propietario. Desviaciones encontradas **sin registro explícito como desviación**:

1. La tarea 12 nombra `e2e/specs/access/boot-and-signed-in.e2e.ts`; el código tiene `e2e/specs/journeys/boot.e2e.ts` y `signed-in.e2e.ts`. `apply-progress.md:572` nombra los archivos, pero no lo declara desviación (la parte 2 sí registró la decisión del propietario sobre `journeys/`).
2. Evidencia de TDD: la parte 1 no usa la tabla estándar de evidencia del ciclo; registra rojo, demostraciones y `cmp` por tarea en prosa (29 menciones de `cmp`). En 6b las pruebas se escribieron después del código y el rojo se tomó contra el árbol sin producción, y está declarado (`tasks.md:183`). Esta verificación no reconstruye evidencia histórica.

## 5. Ejecución

Puertos 5173, 8099 y 4173: sin procesos a la escucha antes de correr. Cada suite se corrió sola, con el registro completo en `$CLAUDE_JOB_DIR/tmp/`.

| Suite | Comando | Resultado |
|---|---|---|
| U | `pnpm --filter @confia/admin-web test` | 1.ª corrida: **salida 1**, 69 archivos, 749 pasan, **5 fallan**, 2 `todo`, 544,6 s. Los cinco son tiempos agotados, no aserciones: `src/i18n/keys.check.test.ts` (BX43, AW27; 60 s), `SignInContainer.test.tsx` (SS58; 30 s), `SignInContainer.states.test.tsx` (AW49; 30 s), `EnrollmentContainer.test.tsx` (token de inscripción terminado; 30 s) y `e2e/mock-api/start.test.ts` (`ConnectTimeoutError` hacia `localhost`). Coincidió con una búsqueda en disco paralela de esta verificación. **Reintento, sola: salida 0**, 69 archivos, **754 pasan**, 2 `todo`, 167 s; cobertura 98,73 % instrucciones, 95,76 % ramas, 99,18 % funciones, 99,41 % líneas |
| T | `pnpm --filter ./tooling test` | Salida 0: 9 archivos, **286 pasan**, 53,5 s |
| C | `pnpm --filter @confia/config test` | Salida 0: 4 archivos, **48 pasan**, 1,4 s |
| UI | `pnpm --filter @confia/ui test` | 1.ª corrida y reintento: **salida 1 sin ejecutar pruebas**, `listen EACCES ::1:51730`. El puerto por defecto (`packages/ui/src/test/api-port.ts:2`) cae en un intervalo que Windows excluye en esta máquina (51641 a 51740, según `netsh interface ipv6 show excludedportrange protocol=tcp`). Con la variable que prevé el repositorio, `CONFIA_VITEST_API_PORT=52730`: salida 1, 55 archivos, **552 pasan y 1 falla** por tiempo agotado de 5 s en `src/i18n/i18n.test.tsx:51` (DS59), no por aserción |
| E y PR | No se corrieron, por instrucción | CI de `main` (`gh run list --branch main --limit 3`): la ejecución `42829082254` (fusión de #293, `08e5143`) tiene `admin-web e2e and accessibility` en verde (2 min 2 s), y también `frontend verify`, `design system accessibility`, `backend verify` y `security scanning`; `backend mutation` seguía en curso. La `42746986352` (fusión de #291) terminó en verde; la `42742545097` (#292, documentos del backend) se canceló |

No se verificó en esta fase: la puerta de historias en Chromium (`test:a11y`), Playwright ni la prueba de PR completo; para ellas se cita el CI.

## 6. Elementos abiertos para el propietario

- **12.4 / BX86:** falta provocar una ejecución en rojo en el CI y comprobar que se sube el artefacto `admin-web-e2e-report`. No se marca como observado.
- **12.6:** verificación final del propietario (Definición de terminado de `CLAUDE.md`).
- **BX82:** no hay registro de que la puerta local dé el mismo veredicto que el CI. En esta máquina la suite UI ni siquiera arranca con el puerto por defecto.
- **Brecha de la pila real:** todos los recorridos corren contra la API simulada; la prueba contra la pila real es del cambio 11 (brecha fechada 2026-11-04).
- **Inestabilidad conocida:** SS17 se corrigió en #293 (verde en CI). Siguen sensibles a la carga los tiempos de importación en frío de las suites jsdom (lección ya registrada en `tasks.md` de la parte 2).
- **Anulación de `browserslist`:** `pnpm-workspace.yaml:93-96` conserva `"browserslist@4.29.5": 4.29.4`; `apply-progress.md` pedía retirarla hacia el 2026-11-09, y esa fecha ya pasó.

## 7. Hallazgos

### CRITICAL

Ninguno.

### WARNING

1. **BX86 sin evidencia remota** (12.4 abierta) y **12.6 pendiente**. El archivo puede registrarlos como pendientes; no deben presentarse como observados.
2. **BX82 sin ninguna cobertura:** no hay prueba ni registro manual o remoto de que la ejecución local y el CI den el mismo veredicto.
3. **Puerto por defecto de Vitest en `packages/ui` dentro de un intervalo reservado:** `DEFAULT_API_PORT = 51730` (`packages/ui/src/test/api-port.ts:2`) cae en 51641 a 51740 en esta máquina, y `pnpm --filter @confia/ui test` no arranca sin `CONFIA_VITEST_API_PORT`. Windows cambia esos intervalos entre arranques, así que un puerto fijo no garantiza nada.
4. **Tiempos agotados bajo carga en las suites locales:** cinco pruebas de `admin-web` (30 s y 60 s) en la primera corrida y DS59 en `packages/ui` (5 s por defecto). El reintento de `admin-web` pasó completo, y ninguna falla fue por aserción, pero la suite completa no es determinista en una máquina cargada.

### SUGGESTION

1. Actualizar los registros desactualizados: «Estado acumulado» de `apply-progress.md:8-14` (dice «3 a 12 pendientes»), `apply-progress.md:565` y `tasks.md:235` (dicen que BX89 no está observado, aunque `design.md:576` lo registra) y `tasks.md:146` (dice que la tarea 2 queda sin marcar).
2. Nombrar DS65, DS70, DS87 y DS97 en las pruebas de la puerta de historias que ya los cubren, para que la trazabilidad no dependa de la lectura.
3. Registrar como desviación el cambio de `e2e/specs/access/boot-and-signed-in.e2e.ts` a `e2e/specs/journeys/{boot,signed-in}.e2e.ts`.
4. Retirar la anulación de `browserslist` ahora que la versión cumple la antigüedad mínima.

## 8. Lo que el archivador debe saber

- La implementación está completa y fusionada; solo quedan abiertas 12.4 (BX86) y 12.6, ambas del propietario.
- Esta parte se archiva antes que la parte 2: los deltas `MODIFIED` de la parte 2 **suman** escenarios a lo que archive esta parte (`tasks.md:279`), no los reemplazan.
- BX82, BX86 y la brecha de la pila real deben quedar como pendientes visibles en el archivo, no como observados.
