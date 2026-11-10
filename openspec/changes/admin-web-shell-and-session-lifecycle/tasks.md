# Tareas: shell de `apps/admin-web` y vida completa de la sesión (cambio 13b-2 de F0)

- **Cambio:** `admin-web-shell-and-session-lifecycle`
- **Rama base del cambio:** `change/admin-web-shell-and-session-lifecycle` (árbol de trabajo `C:\dev\confia-frontend`; nunca `C:\dev\confia`).
- **Fase:** tareas
- **Fecha:** 2026-11-10
- **Estado:** borrador para aprobación del propietario. La decisión de entrega (cadena de PR apilados) se confirma antes de aplicar.
- **Entradas:** `proposal.md` (aprobada el 2026-11-10, con sus «Supuestos por confirmar» como valores de trabajo), `design.md` (secciones 8, 11, 13 y 14; decisiones H1 a H17), `exploration.md` y los cinco deltas de `specs/`: `staff-sign-in` (87 escenarios), `admin-web-shell` (39), `design-system` (38), `admin-web-foundation` (19) y `build-integrity` (30).
- **Modo de TDD:** estricto. Cada comportamiento con prueba ejecutable se escribe primero en rojo (Vitest, Vitest en modo navegador para `packages/ui`, Playwright para los recorridos). El `test_command` de `openspec/config.yaml` es solo del backend; esta lista fija el comando por superficie.
- **Criterio de salida 8:** ya está marcado (nota fechada 2026-11-09). Este cambio no puede desmarcarlo; desde la tarea 8 cada tarea que agrega una pantalla o un estado agrega en el mismo PR su estado de la matriz y su fixture de violación (design §13).
- **Rutas:** relativas a `apps/admin-web/` salvo que se diga otra cosa. Las ramas de cada PR llevan el prefijo `change/admin-web-shell-and-session-lifecycle-`.
- **Dinero:** este cambio no muestra importes. `apps/api` no cambia.

## Review Workload Forecast

| Field | Value |
|-------|-------|
| Estimated changed lines (additions + deletions, con pruebas, sin `openspec/`, archivo de bloqueo ni código generado) | **3 950 a 5 250** (design §13); realista de la propuesta: 4 000 a 6 900. Ninguna cifra es una medición |
| 400-line budget risk | **High** frente a las 400 líneas de la preflight; **Medio por PR** frente al tope de 800 de `docs/15` §3 (los PR 3 y 6 son de riesgo alto y por eso tienen corte predefinido) |
| Chained PRs recommended | Yes |
| Suggested split | **7 PR base, 8 con el corte firme del PR 3 (3a y 3b) y 9 si se aplica el corte del PR 6 (6a y 6b)** |
| Delivery strategy | `single-pr` (preflight del propietario). Con un pronóstico muy superior a 400 líneas, `single-pr` exige una excepción `size:exception` explícita o sustituirla por la cadena; se propone la cadena |
| Chain strategy | `stacked-to-main` (convención del proyecto): cada PR parte de la rama del anterior y se fusiona a `main` en orden. **Propuesta, pendiente de confirmar por el propietario** |

Decision needed before apply: Yes
Chained PRs recommended: Yes
Chain strategy: stacked-to-main
400-line budget risk: High

**Decisión que se pide al propietario antes de aplicar.** (1) Confirmar `stacked-to-main` en lugar de `size:exception` sobre un solo PR. (2) Aceptar 8 a 9 PR frente al objetivo D11 de 5 a 7 (design §13 y §15 lo anticiparon: las particiones 3a/3b y 6a/6b son las que superan ese objetivo). Si el propietario prefiere no superar 7 PR, la alternativa es fusionar 3a y 3b y aceptar un PR 3 de 800 a 1 000 líneas con excepción de tamaño; no se recomienda.

**Cuenta de tareas.** **13 tareas** de nivel superior, dentro del objetivo D11 (11 a 13) y por debajo del tope de 15 de `openspec/config.yaml`, de modo que **no se activa la salida a dos cambios**. Las tareas pequeñas comparten PR; cada tarea cierra con su propio commit de unidad de trabajo.

### Cadena de PR propuesta (`stacked-to-main`)

| PR | Rama (sufijo) | Tareas | Contenido | Pronóstico (líneas) | Riesgo frente a 800 |
|---|---|---|---|---|---|
| 1 | `1-session-core` | 1, 2 | `serverDate`, plazos, reloj del servidor, planificador, programador, `extend` y `endByTimeout` | 650 a 800 | Medio |
| 2 | `2-activity-channel-mock` | 3, 4 | Detector de actividad, canal entre pestañas, API simulada (H14) | 600 a 800 | Medio |
| 3a | `3a-dialogs-and-overlay-rule` | 5 | `Dialog`, `AlertDialog`, regla ESLint de H16, ADR de H17 | 450 a 550 | Medio |
| 3b | `3b-shell-components` | 6 | `SkipLink`, `PageHeader`, `Topbar`, `UserMenu`, `AppShell`, exportaciones (DS02, DS88, DS89) | 350 a 450 | Bajo |
| 4 | `4-authenticated-shell` | 7, 8 | Mecanismo de la puerta para superposiciones y shell autenticado con el estado `user-menu-open` | 550 a 750 | Medio |
| 5 | `5-theme` | 9 | Tema de tres estados, H13, estados `theme-explicit-*` | 450 a 650 | Bajo |
| 6a | `6a-session-warnings` | 10, 11 | Avisos de sesión en la interfaz, recorridos del ciclo de vida, estados `idle-warning` y `absolute-warning` | 500 a 650 | Medio |
| 6b | `6b-tabs-journeys` | 12 | Recorridos de dos pestañas (SS126, SS177 a SS184). **Corte predefinido:** si 6a más 6b caben en 800 se entregan juntos como PR 6 | 250 a 300 | Bajo |
| 7 | `7-docs-notes` | 13 | Notas fechadas, `docs-notes.test.ts`, cierre del cambio | 150 a 300 | Bajo |
| | | **Total** | | **3 950 a 5 250** | |

**Órdenes duros (design §13).** El PR 4 necesita el 3 (3a y 3b); el 5 necesita el 4 (el selector vive en el menú); el 6 necesita el 1, el 2 y el 3. Los PR 1 y 2 no tocan la interfaz y pueden revisarse mientras se escribe el 3. La cadena lineal 1, 2, 3a, 3b, 4, 5, 6a, 6b, 7 cumple todos.

### Suggested Work Units

| Unit | Goal | Likely PR | Focused test command | Runtime harness | Rollback boundary |
|------|------|-----------|----------------------|-----------------|-------------------|
| 1 | Plazos, reloj corregido y planificador puro | PR 1 | `pnpm --filter @confia/admin-web exec vitest run src/shared/api src/features/identity/session` | N/A: lógica pura sin interfaz; lo cubre la suite U | `http.ts`, `deadline-store.ts`, `lifecycle/{policy,planner,server-clock}.ts` y sus pruebas; independiente de 2 |
| 2 | Programador y operaciones nuevas de `AccessSession` | PR 1 | `pnpm --filter @confia/admin-web exec vitest run src/features/identity/session` | N/A: sin interfaz todavía; el cierre por vencimiento se ve en el recorrido de la tarea 11 | `scheduler.ts`, `access-session.ts`, comentarios de invariante; exige revertir antes la tarea 10 |
| 3 | Detector de actividad y canal | PR 2 | `pnpm --filter @confia/admin-web exec vitest run src/features/identity/session/lifecycle` | N/A: se ejercita con doble de `BroadcastChannel`; recorrido en la tarea 12 | `activity-detector.ts`, `session-channel.ts`, conexión en `session/index.ts` |
| 4 | API simulada con inactividad y vencimiento absoluto | PR 2 | `pnpm --filter @confia/admin-web exec vitest run e2e/mock-api` | `pnpm --filter @confia/admin-web test:e2e` (los recorridos de 13b-1 siguen en verde con el token de 10 min) | `e2e/mock-api/**`; revertir exige revertir antes las tareas 11 y 12 |
| 5 | `Dialog`, `AlertDialog`, regla y ADR | PR 3a | `pnpm --filter @confia/ui test` | `pnpm --filter @confia/ui test:a11y` (puerta de historias) | `molecules/dialog/**`, hoja de estilos, regla ESLint, ADR |
| 6 | Componentes de shell y exportaciones | PR 3b | `pnpm --filter @confia/ui test` | `pnpm --filter @confia/ui test:a11y` | `atoms/skip-link`, `molecules/{page-header,topbar,user-menu}`, `layouts/app-shell`, `public-api.test.ts`; DS89 vuelve a aseverar la ausencia de `Dialog` |
| 7 | Mecanismo de la puerta para superposiciones | PR 4 | `pnpm --filter @confia/admin-web exec vitest run e2e/support` | `pnpm --filter @confia/admin-web test:e2e -- --grep gate` | `e2e/support/{a11y-matrix,a11y-report,a11y-gate,fixtures}.ts`; sin estados nuevos todavía, así que el criterio 8 no cambia |
| 8 | Shell autenticado y panel de bienvenida | PR 4 | `pnpm --filter @confia/admin-web exec vitest run src/features/shell src/features/identity` | `pnpm --filter @confia/admin-web test:e2e` (`shell.e2e.ts`, `signed-in.e2e.ts`, puerta) | `features/shell/**`, `routes.tsx`, restauración de `signed-in/**`; **si deja la página autenticada sin evaluar, desmarca el criterio 8 con nota fechada** |
| 9 | Tema de tres estados | PR 5 | `pnpm --filter @confia/admin-web exec vitest run src/features/shell/theme` | `pnpm --filter @confia/admin-web test:e2e -- --grep theme` | `features/shell/theme/**`, `main.tsx`, selector del menú; la clave `confia.admin.theme` es inocua tras revertir |
| 10 | Avisos de sesión en la interfaz | PR 6a | `pnpm --filter @confia/admin-web exec vitest run src/features/identity/session-warnings` | N/A hasta la tarea 11: son presentaciones y un contenedor con sesión falsa | `session-warnings/**` y su montaje en `AuthenticatedLayout`; el núcleo sigue cerrando al vencer sin aviso previo |
| 11 | Recorridos del ciclo de vida y estados de la puerta | PR 6a | `pnpm --filter @confia/admin-web exec vitest run e2e/support` | `pnpm --filter @confia/admin-web test:e2e -- --grep "session-lifecycle\|gate\|deliberate"` | `session-lifecycle.e2e.ts`, estados `idle-warning` y `absolute-warning`, fixtures; inversiones SS124 y SS125 vuelven a su texto |
| 12 | Recorridos de dos pestañas | PR 6b | `pnpm --filter @confia/admin-web exec vitest run e2e/specs/journeys` | `pnpm --filter @confia/admin-web test:e2e -- --grep tabs` | `tabs.e2e.ts`; SS126 vuelve a su texto |
| 13 | Notas fechadas y cierre | PR 7 | `pnpm --filter @confia/admin-web exec vitest run src/app/docs-notes.test.ts` | `pnpm turbo run lint typecheck test build --filter=!@confia/api` | `docs/09`, `docs/ui-ux/04`, `docs/ui-ux/01` y `docs-notes.test.ts`; solo agregados |

### Reglas comunes a todas las tareas

- **Regla de las 800 líneas: se mide antes de comprometer.** Antes del primer commit de cada PR, y otra vez antes de cada commit siguiente:
  `git add -N . && git diff --numstat -M <base> -- . ':(exclude)openspec' ':(exclude)pnpm-lock.yaml' ':(exclude)packages/contracts/src/generated'`, sumando adiciones y eliminaciones. Si un PR supera 800, no se compromete: se registra la medida en `apply-progress.md` y se aplica el corte de su fila (PR 3 ya cortado; PR 6 con corte 6a/6b; PR 1, 2, 4 y 5 se parten por tarea, porque cada una ya tiene commit propio). Un exceso pequeño (hasta 880) se consulta al propietario como excepción; por encima de 880 se corta. Nunca se quitan pruebas, se minifica ni se borran comentarios o líneas en blanco para caber.
- **Suite completa de cada superficie tocada.** Las órdenes focalizadas son solo para iterar. Cada tarea cierra con la suite de cada ruta que tocó y con la prueba de PR completo.

  | Ruta tocada | Suite completa |
  |---|---|
  | `apps/admin-web/src/**` | **U** `pnpm --filter @confia/admin-web test` |
  | `apps/admin-web/e2e/**` o cualquier cambio visible de la app | **E** `pnpm --filter @confia/admin-web test:e2e` |
  | `packages/ui/**` | **UI** `pnpm --filter @confia/ui test`, `pnpm --filter @confia/ui lint` y `pnpm --filter @confia/ui test:a11y` |
  | `tooling/**`, `.dependency-cruiser.cjs` | **T** `pnpm --filter ./tooling test` |
  | Siempre, al cerrar un PR | **PR** `pnpm turbo run lint typecheck test build --filter=!@confia/api` |

- **Disciplina de proceso.** Un solo comando pesado a la vez; los registros completos van a un archivo del directorio temporal (`> "$env:TEMP\<nombre>.log" 2>&1`) y se lee el final o las líneas que fallan; las suites de importación en frío fijan tiempos explícitos; un archivo de bloqueo editado a mano pasa `pnpm install --frozen-lockfile --prefer-offline` antes de comprometer; se mide el tamaño tras el verde de cada subtarea grande.
- **Rojo registrado.** Cada paso en rojo corre contra el árbol sin el código de producción y se registra en `apply-progress.md` con la causa observada. Un commit en rojo no entra al historial. Tras el verde, un paso de REFACTOR deja la suite en verde antes de comprometer. Un paso sin rojo posible (documentos, ADR, hojas de estilo sin comportamiento medible) se declara con su razón.
- **Demostración deliberada.** Cada tarea de código nombra rupturas temporales de producción que deben poner en rojo una prueba concreta por una aserción de resultado, nunca por tiempo de espera. Procedimiento: copiar el archivo al directorio temporal, aplicar la ruptura, observar el rojo, restaurar y confirmar con `cmp` que el archivo es idéntico a la copia y que `git diff` no muestra la ruptura. Una demostración sin revertir y confirmar bloquea el cierre.
- **Conjunto vacío (ADR-0018).** Lo que hoy no tiene archivos reales entra como `it.todo` y se convierte en prueba en la tarea que lo activa. Un verde sobre un conjunto vacío no se acepta.
- **Inversión de pruebas de ausencia (ADR-0018).** Se invierten o se precisan con una **nota fechada 2026-11-10 que cita este cambio**; nunca se borran y conservan su identificador. Cuando un archivo desaparece con su componente (`signed-in/**`), sus escenarios se trasladan al archivo nuevo con el mismo identificador.
- **Guardas de `apps/api` que miran `apps/`.** Cada cierre comprueba sin Java que `git ls-files -co --exclude-standard apps | grep -E '\.spec\.(ts|js)$'` no devuelve nada (los recorridos se llaman `*.e2e.ts`) y que `git diff --name-only <base> -- apps/api packages/contracts` está vacío.
- **Secretos y registros.** Ningún secreto en el repositorio (regla 13); las pruebas generan sus centinelas en ejecución. Nada de tokens, códigos ni `Authorization` en consola o informes (regla 11). El módulo del canal no llama a `console`.
- **Texto y voz.** Ninguna cadena embebida en componentes: todo texto sale del catálogo `admin` (aplicación) o `ui` (componentes genéricos, sin dirigirse a la persona), en «usted» donde corresponda. El único texto fijado por la documentación es el aviso de inactividad; los demás (aviso absoluto, «Entendido», «Cerrar sesión ahora», shell y tema) se proponen en el PR y esperan aprobación del propietario antes de fusionar (design §15).
- **Presentaciones.** Cada archivo nuevo de `components/` lleva su prueba con el comentario `// States.` y `AW57` que exige `src/app/presentation-states.test.ts`, y no importa Query, Router, Form ni `shared/api`. Las pruebas que dependen de inventarios (`presentation-states.test.ts`, `structure.test.ts`, `no-network`, `journeys-inventory.test.ts`) se revisan en el ROJO de su tarea.
- **Commits.** Conventional Commits en inglés, sin atribución de herramientas de IA, con pruebas y documentación en el mismo commit que el comportamiento. Empujar, abrir PR y fusionar son decisiones del propietario.
- **Revisión.** Lista aplicable de `docs/03` marcada en la descripción de cada PR, que recuerda que revertir una pantalla evaluada o la puerta desmarca el criterio 8 con nota fechada (BX108, design §14.6).
- **Pregunta abierta que alcanza a estas tareas.** El número del ADR de H17 se confirma al escribirlo en la tarea 5 (el último en este árbol es ADR-0028 y la rama de backend en curso también puede reservar números); `docs/adr/README.md` y el nombre `docs/adr/ADR-00NN-superposiciones-nativas-bajo-csp.md` usan el número libre que se encuentre entonces.
- **Sonda de CSP.** Ya está ejecutada y registrada en `design.md` §1 (AW70); no es PR ni tarea. La tarea 5 comprueba que el registro existe y no se relaja la política (AW71).

### Pruebas de ausencia que cambian de signo (design §11) y tarea que las invierte

| Id | Tarea | Resultado |
|---|---|---|
| SS19 | 2 (unidad), 11 (e2e) | Se precisa: sin actividad no hay refrescos; la única petición por tiempo es el `DELETE` del cierre por vencimiento |
| SS124, SS125 | 11 | Se invierten |
| SS126 | 3 (unidad), 12 (e2e) | Se invierte |
| SS103 a SS112 | 8 | Requisito renombrado: el panel confirma la sesión y el cierre vive en el menú |
| AW05, AW06 | 9 | AW05 se precisa; AW06 se invierte |
| AW19 | 8 | Se conserva con nota de que 13b-2 lo revisó |
| AW59, AW60 | 8 (y 6 para la parte de componentes de AW60) | AW59 se actualiza; AW60 se invierte en parte |
| DS02, DS88, DS89 | 6 | Veintiocho nombres; nueve componentes en DS88; DS89 se invierte |
| BX109 | 8, 9, 11 | De 22 a 27 estados y de 44 a 54 evaluaciones; `signed-in-ready` cambia su contenido esperado |
| Comentarios de invariante | 2 | Texto precisado en `access-session.ts` y `refresh-coordinator.ts` |
| `secrets.e2e.ts` | 9 | Se conserva y suma la lista cerrada de claves (BX123 a BX125) |
| `docs-notes.test.ts` | 13 | Suma las notas de 13b-2 sin quitar las de 13b-1 (BX122) |

---

## Fase 1: núcleo de sesión (PR 1)

- [x] 1 **PR 1, `1-session-core`: plazos, reloj corregido y planificador puro (H1, H2, H3, H16).**
  - **Archivos.** Crea `src/features/identity/session/deadline-store.ts` y su prueba, `src/features/identity/session/lifecycle/policy.ts`, `planner.ts`, `server-clock.ts` y sus pruebas, `tooling/dependency-fixtures/apps/admin-web/src/features/identity/session/lifecycle/planner-imports-react.ts` y `tooling/dependency-fixtures/apps/admin-web/src/features/shell/imports-lifecycle-internals.ts`. Modifica `src/shared/api/http.ts` y `http.test.ts` (`serverDate` y `receivedAt` en los resultados `ok`, sin cambiar rutas, cuerpos ni cabeceras), `src/shared/api/auth-operations.ts` (propaga los campos, sin cambiar firmas de entrada), `.dependency-cruiser.cjs`, `tooling/src/dependency-rules.test.ts` y `tooling/dependency-fixtures/README.md`.
  - **Especificaciones.** AW65, AW66, AW67 (`admin-web-foundation`); SS129, SS130, SS131, SS132, SS133 (almacén de plazos, el servidor manda, plazo efectivo); SS134 y SS175 (planificador y precedencia del absoluto); SS137, SS138, SS139, SS140 (desfase de reloj). SS128 (registro desde toda respuesta) y SS141 (un solo temporizador) las cierra la tarea 2.
  - **Inversiones.** Ninguna.
  - [x] 1.1 **ROJO, cliente HTTP.** Una cabecera `Date` válida, ausente, vacía, `abc` e imposible produce `serverDate` correcto o `undefined`, sin `NaN` ni excepción; `receivedAt` es el `Date.now()` local; los resultados `problem`, `network` y `unexpected-response` no cambian [AW65, AW66, AW67].
  - [x] 1.2 **ROJO, reloj del servidor.** Desfase positivo, negativo y nulo con la fórmula `(serverDate + 500) - receivedAt`; cabecera ausente o inválida **conserva** el último desfase válido; sin ninguna respuesta válida el desfase es cero y los plazos se usan tal como vienen [SS137 a SS140].
  - [x] 1.3 **ROJO, almacén de plazos.** `snapshot()` devuelve el mismo objeto hasta que cambia; el plazo de la última respuesta **reemplaza** al anterior aunque sea más temprano; el plazo efectivo es el menor de los dos; nada se escribe en `localStorage`, `sessionStorage`, la URL ni `history.state`; se vacía en `endLocally` [SS129 a SS133].
  - [x] 1.4 **ROJO, planificador.** Tabla de casos por cada fila de H2 en el límite exacto, 1 ms antes y 1 ms después: sin plazos, `expired` por absoluto, `expired` por inactividad, `absolute-warning`, `absolute-acknowledged`, `idle-warning`, `running`; precedencia con las dos ventanas abiertas; el descarte vale para un `S` y deja de valer si `S` cambia; `wakeAt` al siguiente segundo entero en las fases con diálogo [SS134, SS175].
  - [x] 1.5 **ROJO, reglas de dependencia.** `admin-session-lifecycle-planner-is-pure` y `admin-session-lifecycle-is-private` con su violación deliberada en `tooling/dependency-fixtures/` y su caso en `tooling/src/dependency-rules.test.ts` (H16).
  - [x] 1.6 **VERDE.** Implementar `http.ts`, `auth-operations.ts`, `server-clock.ts`, `deadline-store.ts`, `policy.ts` (`IDLE_WARNING_LEAD_MS` 5 min, `ABSOLUTE_WARNING_LEAD_MS` 5 min, `ACTIVITY_CADENCE_MS` 60 s) y `planner.ts`; agregar las dos reglas a `.dependency-cruiser.cjs`.
  - [x] 1.7 **REFACTOR.** Suites en verde. Confirmar cobertura de ramas completa del planificador, el reloj y el almacén.
  - **Demostración deliberada.** (a) `planner.ts` con `>` en lugar de `>=` en el límite de `expired` por inactividad: debe fallar la fila de límite exacto; (b) `server-clock.ts` que fija el desfase en cero ante una cabecera inválida: debe fallar SS140; (c) `planner.ts` importando `react`: debe fallar la regla `admin-session-lifecycle-planner-is-pure`. Procedimiento con `cmp`.
  - **Comprobaciones.** **U**, **E** (los recorridos de 13b-1 siguen en verde: el cambio de `http.ts` no altera comportamiento), **T**, **PR**; guardas de `apps/api` y de `*.spec.*`.
  - **Commit.** `feat(admin-web): add session deadlines, server clock and lifecycle planner`.

- [ ] 2 **PR 1: programador de un solo temporizador y operaciones nuevas de `AccessSession` (H1, H4, H6, SS19 precisado).**
  - **Archivos.** Crea `src/features/identity/session/lifecycle/scheduler.ts`, `scheduler.test.ts` y `lifecycle/index.ts` (superficie pública: `usePhase` y tipos). Modifica `src/features/identity/session/access-session.ts` y `access-session.test.ts` (`extend`, `endByTimeout`, `applyFromOtherTab`, `getDeadlines`, `subscribeDeadlines`; registro de plazos en `restore`, `accessTokenForRequest`, `signIn`, `verify`, `activate`, `readCurrentSession` y `extend`; `endLocally` cancela el temporizador y vacía los plazos; comentario del invariante «sin temporizador» precisado), `src/features/identity/session/refresh-coordinator.ts` y `refresh-coordinator.test.ts` (`refreshed` lleva `serverDate` y `receivedAt`; comentario precisado; línea 166 de la prueba), `src/features/identity/session/session-secrets.flow.test.tsx` (SS19 precisado) y `src/features/identity/session/index.ts`.
  - **Especificaciones.** SS128 (registro de plazos desde toda respuesta); SS135, SS136 (salto de tiempo y vencimiento directo); SS141 (a lo sumo un temporizador pendiente); SS146, SS148, SS149, SS150 (`extend` comparte la petición, red sin repetir, rechazo termina la sesión, sin actividad no hay `extend`); SS162, SS164 (segunda activación en vuelo, rechazo); SS167, SS168, SS169, SS170 (`DELETE` de mejor esfuerzo sin esperar, fallo invisible, aviso de llegada, sin sondeo al recargar); SS176 (cierre por absoluto); SS19 precisado (`staff-sign-in`, MODIFIED).
  - **Inversiones (nota fechada 2026-11-10 que cita este cambio).** **SS19** se precisa en `refresh-coordinator.test.ts`:166 y `session-secrets.flow.test.tsx`:48: sin actividad no hay refrescos; la única petición que dispara el tiempo es el `DELETE` del cierre por vencimiento. Los comentarios de invariante de `access-session.ts`:100 y `refresh-coordinator.ts`:50 pasan a «ningún temporizador ni consulta de fondo pide un refresco», con referencia a `design.md`.
  - [ ] 2.1 **ROJO, programador.** A lo sumo un temporizador pendiente; reprograma al cambiar plazos o vista; `visibilitychange` a `visible` y `pageshow` recalculan; tras un salto de 27 min muestra 3 min restantes y tras 31 min pasa directo a `expired` sin pasar por el aviso; sin actividad nunca llama a `extend`; la cuenta atrás se actualiza por segundo con el mismo temporizador; `dispose` lo cancela [SS135, SS136, SS141, SS150].
  - [ ] 2.2 **ROJO, `AccessSession`.** `extend` con los cuatro desenlaces del coordinador (`refreshed` registra plazos y `lastRefreshAt`; `network` conserva la sesión y no repite; `rejected` y `no-session` terminan la sesión local); dos `extend` simultáneos y uno de la persona comparten una sola petición; `endByTimeout` cierra localmente **antes** del `DELETE`, no lo espera ni lo repite, y su fallo no cambia nada visible; mostrar un aviso no llama a la API; los plazos de la última respuesta reemplazan [SS128, SS146, SS148, SS149, SS162, SS164, SS167 a SS170, SS176].
  - [ ] 2.3 **ROJO, SS19 precisado.** Con el reloj adelantado 60 min y sin actividad, no hay peticiones de refresco; la única petición permitida es el `DELETE` al terminar la cuenta atrás.
  - [ ] 2.4 **VERDE.** Implementar `scheduler.ts` con `Timers` inyectado, el descarte del aviso absoluto en el programador y `usePhase`; implementar las operaciones nuevas; conectar `extend` al coordinador existente (promesa compartida y Web Lock); precisar los dos comentarios de invariante.
  - [ ] 2.5 **REFACTOR.** Suites en verde. Revisar que `index.ts` no exponga más que la superficie de H16.
  - **Demostración deliberada.** (a) `scheduler.ts` con un `setInterval` en lugar de un temporizador único: debe fallar SS141; (b) `endByTimeout` que espera el `DELETE` antes de cerrar: debe fallar SS167; (c) un temporizador que llama a `extend`: debe fallar SS150 y SS19. Procedimiento con `cmp`.
  - **Comprobaciones.** **U**, **E**, **T**, **PR**; medir el tamaño del PR 1 (tareas 1 y 2) antes de comprometer; guardas de `apps/api`.
  - **Commit.** `feat(admin-web): add lifecycle scheduler, extend and timeout sign-out to the session`.

## Fase 2: actividad, canal y API simulada (PR 2)

- [ ] 3 **PR 2, `2-activity-channel-mock`: detector de actividad y canal entre pestañas (H5, H7).**
  - **Archivos.** Crea `src/features/identity/session/lifecycle/activity-detector.ts`, `session-channel.ts` y sus pruebas. Modifica `src/features/identity/session/index.ts` (crea y conecta programador, detector y canal con la sesión de la pestaña), `src/features/identity/session/lifecycle/index.ts` y `src/features/identity/session/access-session.ts` (difusión de `deadlines` tras todo refresco correcto y de `signed-out` al cerrar; `applyFromOtherTab` consume mensajes validados).
  - **Especificaciones.** SS142, SS143, SS144, SS145, SS147, SS151, SS152, SS174 (qué cuenta como actividad, cadencia por pestaña, solo con sesión completa, visible y sin aviso abierto); SS177, SS178, SS179, SS180, SS181, SS182, SS183, SS184 (canal sin secretos, cierre sincronizado, plazos compartidos, sin reenvío, descarte silencioso, pestaña sin sesión, sin `BroadcastChannel`); SS166 se verifica por cálculo del margen en unidad (la prueba de extremo a extremo vive en la tarea 11).
  - **Inversiones (nota fechada 2026-11-10 que cita este cambio).** **SS126** (parte de unidad): la prueba que afirmaba que no hay sincronización entre pestañas ni detección de actividad pasa a afirmar lo contrario; el escenario conserva su identificador en `access-session.test.ts` y el recorrido de dos pestañas lo cierra la tarea 12.
  - [ ] 3.1 **ROJO, detector.** Cuentan `keydown`, `pointerdown`, `wheel` y `scroll` en captura y pasivos; `pointermove`, `mousemove` y `focus` no cuentan; una ráfaga dentro de 60 s hace un solo `extend` y a los 60 s otro; nada en segundo plano, sin sesión completa, ni con un aviso de sesión abierto; vuelve a refrescar tras descartar el aviso absoluto [SS142 a SS145, SS147, SS151, SS152, SS174].
  - [ ] 3.2 **ROJO, canal.** Acepta los dos tipos; rechaza tipo desconocido, instante inválido, campo extra y valor que no es objeto, sin excepción y sin registrar el contenido; no reenvía lo recibido; una pestaña sin sesión completa ignora todo; `signed-out` con `expired` lleva al aviso de sesión vencida y con `person` sin aviso; `deadlines` reemplaza los plazos propios convertidos con el desfase propio y cierra el aviso de inactividad si la fase vuelve a `running`, sin emitir refresco; ningún mensaje producido contiene un centinela ni forma de JWT; sin `BroadcastChannel` degrada a nada sin error [SS177 a SS184].
  - [ ] 3.3 **VERDE.** Implementar el detector (objetivo de eventos, reloj y política inyectados), el canal (Zod `.strict()`, `v: 1`, nombre `confia.admin.session`) y la conexión en `session/index.ts`.
  - [ ] 3.4 **REFACTOR.** Suites en verde; confirmar que el módulo del canal no llama a `console`.
  - **Demostración deliberada.** (a) El detector cuenta `pointermove`: debe fallar SS143; (b) el canal reenvía lo recibido: debe fallar SS181; (c) el esquema sin `.strict()`: debe fallar SS182 por campo extra. Procedimiento con `cmp`.
  - **Comprobaciones.** **U**, **E**, **T**, **PR**; guardas de `apps/api`.
  - **Commit.** `feat(admin-web): extend the session on activity and share session events between tabs`.

- [ ] 4 **PR 2: API simulada con inactividad, vencimiento absoluto y reloj controlable (H14).**
  - **Archivos.** Modifica `e2e/mock-api/session-model.ts` y `session-model.test.ts`, `e2e/mock-api/server.ts` y `server.test.ts`, `e2e/mock-api/scenarios.ts`.
  - **Especificaciones.** SS185, SS186, SS187, SS188, SS189 (inactividad de 30 min solo movida por el refresco, absoluta de 12 h que ninguna rotación desplaza, token de 10 min, plazos derivados validados con `@confia/contracts/admin`, cabecera `Date` del reloj inyectado); SS191 (ningún recorrido espera 30 s o más de tiempo real; los controles lo hacen posible); AW12 se conserva.
  - **Inversiones.** Ninguna (el token de 15 a 10 min lo absorben los recorridos de 13b-1 sin cambiar identificadores; si alguno depende del valor, se registra una nota fechada 2026-11-10 en esa prueba).
  - [ ] 4.1 **ROJO, modelo.** Un refresco con `now - lastUsedAt >= 30 min` responde `401 session-expired` y revoca la familia; la lectura de `/current` no mueve `lastUsedAt`; `createdAt + 12 h` vencido responde `401 session-expired` y ninguna rotación lo desplaza; el token de acceso dura 10 min; `idleExpiresAt` y `sessionExpiresAt` salen de `lastUsedAt` y `createdAt` y pasan el esquema del contrato; la cabecera `Date` sigue el reloj del modelo [SS185 a SS189].
  - [ ] 4.2 **ROJO, controles.** `POST /__control/clock` con `{ advanceMs }`, con `{ skewMs }` y con `{ dateHeader: "omit" | "invalid" }`; `seed-session?absoluteInMs=`; el registro de peticiones por escenario cuenta refrescos y `DELETE` [SS189, SS191].
  - [ ] 4.3 **VERDE.** Implementar el modelo y los controles con el mismo estilo que `__control/seed-session`; el modelo sigue declarándose simulación y no evidencia de la pila real (SS123, cambio 11).
  - [ ] 4.4 **REFACTOR.** Suites en verde.
  - **Demostración deliberada.** (a) La lectura de `/current` mueve `lastUsedAt`: debe fallar SS186; (b) una rotación que desplaza el vencimiento absoluto: debe fallar SS187. Procedimiento con `cmp`.
  - **Comprobaciones.** **U**, **E** (todos los recorridos de 13b-1 en verde con el token de 10 min), **PR**; medir el tamaño del PR 2 (tareas 3 y 4) antes de comprometer; guardas de `apps/api`.
  - **Commit.** `test(admin-web): model idle and absolute expiry and a controllable clock in the mock api`.

## Fase 3: componentes de `packages/ui` (PR 3a y 3b)

- [ ] 5 **PR 3a, `3a-dialogs-and-overlay-rule`: `Dialog`, `AlertDialog`, regla de superposiciones y ADR (H8, H16, H17).**
  - **Archivos.** Crea `packages/ui/src/molecules/dialog/NativeDialog.tsx`, `Dialog.tsx`, `AlertDialog.tsx`, sus pruebas en modo navegador y sus historias (con la historia de violación deliberada de cada uno), archivos de violación de la regla ESLint (uno por primitivo prohibido de Radix y uno por cada paquete `react-remove-scroll` y `react-remove-scroll-bar`, excluidos de la construcción), `docs/adr/ADR-00NN-superposiciones-nativas-bajo-csp.md` (número por confirmar al escribirlo) y la entrada en `docs/adr/README.md`. Modifica `packages/ui/src/styles.css` o la hoja de tokens (`html:has(dialog[open]:modal) { overflow: hidden; }`, `::backdrop` con token de superposición en los dos temas), `packages/ui/eslint.config.*` (`ui-no-scroll-locking-overlays`) y las claves genéricas de `packages/ui/src/i18n/**`.
  - **Especificaciones.** DS99, DS100, DS101, DS102, DS103, DS104, DS105, DS106, DS107, DS108, DS109, DS130 (`Dialog` y `AlertDialog` modales, foco, retorno, Escape, anuncio, sin estilos en línea); DS124, DS125 (parte de claves de estos componentes); DS126, DS127 (historias de diálogo abierto en los dos temas dentro de la puerta de historias); BX126, BX127, BX128, BX129, BX130 (ADR, regla ESLint con su violación por primitivo y por paquete, `Checkbox` y `RadioGroup` siguen permitidos, búsqueda en el código real y en el resultado de producción); AW70, AW71 (la sonda está registrada y la política no se relaja).
  - **Inversiones.** Ninguna en esta tarea; la exportación pública de `Dialog` y la inversión de DS89 las hace la tarea 6.
  - [ ] 5.1 **ROJO, ESLint.** Cada archivo de violación (`radix-ui` `Dialog`, `AlertDialog`, `DropdownMenu`, `Popover`, `Select`, `ContextMenu`, `Menubar`, `NavigationMenu`; `react-remove-scroll`; `react-remove-scroll-bar`) falla con el mensaje que cita el ADR, y uno de `Checkbox` o `RadioGroup` pasa [BX127 a BX129].
  - [ ] 5.2 **ROJO, navegador (Chromium).** Apertura con `showModal()`; foco inicial en `initialFocusRef` o en el primer interactivo que no sea un botón de cerrar; confinamiento y fondo inerte; retorno del foco al origen conectado o al `h1`; Escape cancelado y convertido en `onDismiss` con `open` siempre sincronizado, incluido el segundo Escape; `AlertDialog` ignora el clic fuera; nombre y descripción obligatorios en el tipo y enlazados con `useId`; `role="alertdialog"` y región `aria-live="assertive"` dentro del diálogo; texto cambiante marcado `aria-hidden`; ningún `<style>` ni atributo de estilo en línea [DS99 a DS109, DS130].
  - [ ] 5.3 **ROJO, puerta de historias.** El diálogo y el diálogo de alerta abiertos se evalúan en los dos temas y su historia de violación deliberada hace fallar la puerta [DS126, DS127].
  - [ ] 5.4 **ROJO, búsqueda en el código y la compilación.** Ninguna referencia a `react-remove-scroll` ni a los primitivos prohibidos en `packages/ui` ni en el resultado de producción de `apps/admin-web` [BX130]. Se declara como prueba de inventario con la razón de que el conjunto real es vacío hoy.
  - [ ] 5.5 **VERDE.** Implementar `NativeDialog`, `Dialog` y `AlertDialog`, la hoja de estilos estática, la regla ESLint y las claves; escribir el ADR con el próximo número libre, citando la evidencia de la sonda (`design.md` §1), y sin modificar ADR-0026 ni ADR-0028 [BX126].
  - [ ] 5.6 **REFACTOR.** Suites en verde. Sin paso de rojo para el ADR ni para la hoja de estilos sin comportamiento medible: se verifican por lectura y por las pruebas de 5.2.
  - **Demostración deliberada.** (a) `NativeDialog` que no devuelve el foco al origen: debe fallar DS104; (b) `AlertDialog` que cierra con clic fuera: debe fallar DS107; (c) importar `Dialog` de `radix-ui` en un archivo real: debe fallar la regla. Procedimiento con `cmp`.
  - **Comprobaciones.** **UI** completa, **PR**; el barrido de `apps/admin-web` no cambia en esta tarea (no hay consumidor todavía); guardas de `apps/api`.
  - **Commit.** `feat(ui): add native dialog and alert dialog and forbid scroll-locking overlays`.

- [ ] 6 **PR 3b, `3b-shell-components`: `SkipLink`, `PageHeader`, `Topbar`, `UserMenu`, `AppShell` y exportaciones (H10, DS02, DS88, DS89).**
  - **Archivos.** Crea `packages/ui/src/atoms/skip-link/SkipLink.tsx`, `packages/ui/src/molecules/page-header/PageHeader.tsx`, `packages/ui/src/molecules/topbar/Topbar.tsx`, `packages/ui/src/molecules/user-menu/UserMenu.tsx` y `use-disclosure.ts` (interno), `packages/ui/src/layouts/app-shell/AppShell.tsx`, con pruebas en modo navegador e historias en los dos temas (cada una con su historia de violación deliberada). Modifica `packages/ui/src/index.ts`, `packages/ui/src/public-api.test.ts`, `packages/ui/src/i18n/**`, la hoja de estilos (foco del enlace de salto, posición del panel) y el requisito de carpetas de `design-system` (nota fechada que agrega `layouts/`, design §15).
  - **Especificaciones.** DS110, DS111, DS112, DS113, DS114, DS115 (`UserMenu` como botón de divulgación sin `role="menu"` y sin Radix, teclado de H10, `aria-busy`); DS116, DS117, DS118, DS119, DS120, DS121, DS122, DS123, DS131 (`Topbar`, `PageHeader`, `SkipLink`, `AppShell`, destinos de 24 por 24 px); DS124, DS125 (claves `ui` genéricas); DS128, DS129 (panel del menú abierto y enlace de salto con foco en la puerta de historias); DS01, DS02, DS03, DS88, DS89 (conjunto exacto de exportaciones); BX127 a BX129 siguen cumpliéndose.
  - **Inversiones (nota fechada 2026-11-10 que cita este cambio).** **DS02**: de veintiún a veintiocho nombres (`Dialog`, `AlertDialog`, `SkipLink`, `PageHeader`, `Topbar`, `UserMenu`, `AppShell`); `DropdownMenu` no se exporta. **DS88**: nueve componentes con los siete nuevos. **DS89** (`public-api.test.ts`:50): se invierte, `Dialog` existe y se importa del punto de entrada público; `Sidebar` y `Breadcrumb` siguen ausentes. **AW60** (parte de componentes): existen `Topbar`, `UserMenu`, `PageHeader` y `Dialog`; `Sidebar` y `Breadcrumb` siguen ausentes; se registra aquí la mitad de `ui` y la otra mitad en la tarea 8.
  - [ ] 6.1 **ROJO, exportaciones.** `public-api.test.ts` con el conjunto exacto de veintiocho nombres y `DS89` invertido; los componentes nuevos no exponen compañeros de composición; `NativeDialog` y `use-disclosure` no se exportan [DS01 a DS03, DS88, DS89].
  - [ ] 6.2 **ROJO, `UserMenu`.** Intro y Espacio abren y cierran, con el foco al primer control; flechas, Inicio y Fin recorren las paradas; el grupo de tema es una sola parada y sus flechas cambian de opción; Escape cierra y devuelve el foco al disparador; Tab fuera o clic fuera cierran sin mover el foco; sin `role="menu"`, `menuitem`, `menuitemradio` ni `aria-haspopup="menu"`; `aria-expanded` y `aria-controls`; `aria-busy` en el disparador y nunca `disabled`; sin peticiones [DS110 a DS115].
  - [ ] 6.3 **ROJO, el resto.** `SkipLink` oculto sin foco y visible con foco, que mueve el foco sin tocar la URL; `PageHeader` con `h1` y `tabIndex -1`; `Topbar` con región `banner`; `AppShell` con enlace de salto, barra y un único `main` con el identificador de destino; destinos de 24 por 24 px; claves sin dirigirse a la persona [DS116 a DS125, DS131]. Historias en los dos temas y de violación deliberada [DS128, DS129].
  - [ ] 6.4 **VERDE.** Implementar los cinco componentes y las exportaciones.
  - [ ] 6.5 **REFACTOR.** Suites en verde; un archivo por componente y sin estilos en línea.
  - **Demostración deliberada.** (a) `UserMenu` con `role="menu"`: debe fallar DS110 y DS114; (b) `UserMenu` que mueve el foco en Tab fuera: debe fallar DS113; (c) exportar `DropdownMenu`: debe fallar DS02. Procedimiento con `cmp`.
  - **Comprobaciones.** **UI** completa, **PR**; medir el tamaño de 3a y 3b por separado; guardas de `apps/api`.
  - **Commit.** `feat(ui): add skip link, page header, topbar, user menu and app shell`.

## Fase 4: shell autenticado (PR 4)

- [ ] 7 **PR 4, `4-authenticated-shell`: mecanismo de la puerta para estados superpuestos y temas explícitos (H15, BX111 a BX118).**
  - **Archivos.** Modifica `e2e/support/a11y-matrix.ts` (sin estados nuevos; el `MINIMUM` y la verificación de completitud), `e2e/support/a11y-report.ts`, `e2e/support/a11y-gate.ts` (`checkCombination` con comprobación opcional del fondo calculado antes de analizar, y análisis de superposiciones solo cuando son visibles y con el fondo inerte), `e2e/support/fixtures.ts` y las pruebas de soporte que ya existen. Modifica `e2e/specs/a11y/deliberate-failures.e2e.ts` solo con la estructura que reutilizan las pruebas de fixtures nuevas.
  - **Especificaciones.** BX112 (un estado declarado y no evaluado rompe la puerta), BX114 (el tema explícito prevalece durante la evaluación y el fondo calculado se comprueba), BX116 (el tema explícito se establece por el selector, nunca escribiendo el atributo ni `localStorage` desde la prueba), BX117 (los estados superpuestos se analizan solo cuando son visibles y con el fondo inerte, con reloj controlado y sin espera real), BX118 (un estado cuyo contenido esperado nunca aparece falla); BX77 sigue vigente (la puerta falla con cero rutas). BX111, BX113 y BX115 se cierran junto con cada estado real en las tareas 8, 9 y 11.
  - **Inversiones.** Ninguna (no hay estado nuevo todavía; el criterio 8 y BX109 no cambian).
  - [ ] 7.1 **ROJO.** Un estado declarado sin evaluar rompe la puerta; un estado con contenido esperado ausente falla; `checkCombination` con fondo calculado distinto del token esperado falla; un superpuesto no visible o con el fondo no inerte no se analiza como bueno [BX112, BX114, BX117, BX118]. Se prueban con estados sintéticos, sin tocar la matriz de 22.
  - [ ] 7.2 **VERDE.** Implementar el mecanismo; la matriz sigue en 22 estados y 44 evaluaciones.
  - [ ] 7.3 **REFACTOR.** Suites en verde; la puerta sigue fallando con cero rutas [BX77].
  - **Demostración deliberada.** (a) La verificación de completitud desactivada: debe fallar BX112; (b) la comprobación de fondo calculado que ignora `data-theme`: debe fallar BX114. Procedimiento con `cmp`.
  - **Comprobaciones.** **E** (puerta completa en verde sobre los 22 estados), **U**, **PR**; guardas de `apps/api`.
  - **Commit.** `test(admin-web): let the accessibility gate evaluate overlays and explicit themes`.

- [ ] 8 **PR 4: layout protegido sobre `AppShell`, panel de bienvenida y menú de usuario (H11, H10).**
  - **Archivos.** Crea `src/features/shell/AuthenticatedLayout.tsx` y su prueba, `src/features/shell/welcome/WelcomeContainer.tsx` y `src/features/shell/welcome/components/WelcomePanel.tsx` con sus pruebas (`// States.`, AW57), `src/features/shell/user-menu/UserMenuContainer.tsx` y su prueba, `e2e/specs/journeys/shell.e2e.ts`, `e2e/fixtures/a11y/disclosure-unnamed.html`. Modifica `src/features/identity/routes.tsx` y `routes.test.tsx` (ruta de layout sin segmento `id: "authenticated"` con la guarda en `beforeLoad`; índice con el panel; comentario de la línea 8), `src/app/structure.test.ts` (capacidad `shell`), `src/i18n/locales/es-HN/admin.json` (claves `shell.*`), `e2e/support/a11y-matrix.ts` (estado `user-menu-open`; `signed-in-ready` cambia su contenido esperado), `e2e/specs/a11y/deliberate-failures.e2e.ts`, `e2e/specs/journeys/signed-in.e2e.ts`, `e2e/specs/journeys/journeys-inventory.test.ts`. Elimina `src/features/identity/signed-in/**` (sus escenarios se trasladan con el mismo identificador).
  - **Especificaciones.** SH01, SH02, SH03, SH04 (layout propio, `ScreenLayout` solo para el acceso, estados de carga, error y listo con el layout presente, foco al `h1` y título de documento); SH05, SH06, SH07 (enlace de salto); SH08 (barra con la etiqueta y el menú y nada más); SH09, SH10, SH11, SH12 (panel de bienvenida sin acción ni identidad ni `EmptyState`); SH13, SH14, SH15, SH16, SH17, SH38, SH39, SH18, SH19 (menú de usuario con etiqueta genérica y «Cerrar sesión», sin roles de menú, no modal, sin peticiones, sin leer identidad); SH32, SH33 (textos `shell.*` en «usted»); SH35, SH36, SH37 (ausencias: barra lateral, migas, identidad); SS103 a SS112 (el panel confirma la sesión y el cierre vive en el menú); AW16, AW17, AW18, AW19, AW59, AW60, AW61, AW62, AW63, AW72 (guarda antes de renderizar el shell, sin `redirect`, árbol de rutas, ausencias); BX109 (estado `user-menu-open`, 23 estados), BX111 y BX113 para `user-menu-open` y su fixture `disclosure-unnamed.html` (regla esperada `button-name`); AW12, AW68, AW69 en el recorrido.
  - **Inversiones (nota fechada 2026-11-10 que cita este cambio).** **SS103 a SS112**: el requisito se renombra; sus pruebas de `signed-in/**` y `signed-in.e2e.ts` se trasladan al panel y al menú con el mismo identificador (SS109 y SS110 sobre el cierre pasan al menú). **AW19** (`routes.test.tsx`): se conserva con nota de que 13b-2 revisó el parámetro `redirect` y lo mantiene prohibido (supuesto D9). **AW59** (`routes.test.tsx`:81 y comentario de `routes.tsx`:8): se actualiza al árbol con layout protegido sin segmento y el panel en `/`. **AW60** (parte de aplicación): `Topbar`, `UserMenu` y `PageHeader` existen en el shell; `Sidebar` y `Breadcrumb` siguen ausentes. **BX109**: `signed-in-ready` cambia su contenido esperado del botón «Cerrar sesión» al `h1` del panel; la matriz pasa a 23 estados y 46 evaluaciones.
  - [ ] 8.1 **ROJO, unidad y componentes.** Sin sesión, la guarda lleva al inicio de sesión antes de renderizar el shell; el layout tiene un único `main` con el foco al `h1` y título propio; el panel conserva la lectura de la sesión actual con sus tres estados y el layout presente en los tres, sin acción ni identidad; el contenedor del menú solo muestra la etiqueta genérica y «Cerrar sesión», con `aria-busy` durante la espera; no hay `redirect`; `structure.test.ts` admite `shell`; `presentation-states.test.ts` ve las presentaciones nuevas [SH01 a SH04, SH08 a SH19, SH38, SH39, SS103 a SS112, AW19, AW59, AW72].
  - [ ] 8.2 **ROJO, recorrido.** `shell.e2e.ts`: puntos de referencia, enlace de salto como primer enfocable que lleva el foco al `main`, menú con teclado y clic fuera, cierre de sesión desde el menú que lleva al inicio de sesión [SH01 a SH19, SH38, SH39]; `user-menu-open` en la matriz con su fixture que falla por `button-name` y su caso en `deliberate-failures.e2e.ts` [BX109, BX111, BX113].
  - [ ] 8.3 **VERDE.** Implementar el layout, el panel, el contenedor del menú, la ruta, las claves `shell.*` y retirar `signed-in/**`; trasladar sus escenarios.
  - [ ] 8.4 **REFACTOR.** Suites en verde; revisar que ninguna presentación haga peticiones (`admin-presentation-is-pure`).
  - **Demostración deliberada.** (a) La guarda movida después del render del shell: debe fallar AW72; (b) un `redirect` aceptado en la ruta: debe fallar AW19; (c) el menú con `role="menu"`: debe fallar SH38. Procedimiento con `cmp`.
  - **Comprobaciones.** **U**, **E** (puerta con 23 estados), **UI** (si se tocó `packages/ui`), **T**, **PR**; medir el tamaño del PR 4 (tareas 7 y 8) antes de comprometer; guardas de `apps/api` y de `*.spec.*`.
  - **Commit.** `feat(admin-web): add the authenticated shell with topbar, user menu and welcome panel`.

## Fase 5: tema (PR 5)

- [ ] 9 **PR 5, `5-theme`: selector de tema de tres estados, persistencia y estados explícitos de la puerta (H12, H13).**
  - **Archivos.** Crea `src/features/shell/theme/theme-preference.ts`, `apply-theme.ts`, `bind-theme-to-session.ts` y sus pruebas, `e2e/specs/journeys/theme.e2e.ts`, `e2e/fixtures/a11y/explicit-light-contrast.html` y `explicit-dark-contrast.html`. Modifica `src/main.tsx` (`bindThemeToSession(accessSession)` antes de `createRoot`), `src/features/shell/user-menu/UserMenuContainer.tsx` (grupo `RadioGroup` de tema), `src/i18n/locales/es-HN/admin.json` (claves `theme.*`), `e2e/specs/journeys/boot.e2e.ts`, `e2e/specs/journeys/secrets.e2e.ts`, `e2e/support/a11y-matrix.ts` (estados `theme-explicit-light` y `theme-explicit-dark`), `e2e/specs/a11y/deliberate-failures.e2e.ts`, `e2e/specs/journeys/journeys-inventory.test.ts`.
  - **Especificaciones.** SH20, SH21, SH22, SH23, SH24, SH25, SH26, SH27, SH28, SH29, SH30, SH31 (tres estados, `data-theme`, `system` quita el atributo, valor desconocido es `system`, `localStorage` que lanza, solo la vista `authenticated` lo aplica, persistencia tras cerrar sesión, sin script en línea, ausencia en el acceso); AW05, AW06 (renombrados y modificados); AW13 (sin scripts en línea); BX123, BX124, BX125 (lista cerrada de claves); BX109 (25 estados, 50 evaluaciones); BX111, BX113, BX114, BX115, BX116 para los dos estados explícitos.
  - **Inversiones (nota fechada 2026-11-10 que cita este cambio).** **AW05** (`boot.e2e.ts`:32): se precisa; sin preferencia guardada y en las pantallas de acceso no hay `data-theme`, y «sistema» es el estado predeterminado del selector. **AW06** (`boot.e2e.ts`:56): se invierte; existe un control de tema en la aplicación autenticada y no en las pantallas de acceso. **`secrets.e2e.ts`** (`:93-133`): se conserva y suma la aserción de lista cerrada; un token en `localStorage` sigue fallando [BX124] y un valor de tema fuera de los tres estados también [BX125]. **BX109**: de 23 a 25 estados y de 46 a 50 evaluaciones.
  - [ ] 9.1 **ROJO, unidad.** Valores válidos e inválidos; `localStorage` que lanza no produce error visible ni en consola y la preferencia vive en memoria; `light` y `dark` escriben `data-theme` en la raíz y `system` lo quita; toda vista distinta de `authenticated` lo quita; tras cerrar sesión la preferencia sigue guardada y se reaplica al iniciar de nuevo; el evento `storage` sincroniza el cambio de otra pestaña; ningún script en línea [SH20 a SH28, SH30].
  - [ ] 9.2 **ROJO, recorridos.** `theme.e2e.ts`: persiste al recargar; ni aparece ni se aplica en el arranque, inicio de sesión, verificación, inscripción y ruta no encontrada; la única clave de la aplicación en `localStorage` es `confia.admin.theme` con valor de los tres estados [SH20 a SH31, BX123 a BX125]. Estados `theme-explicit-light` y `theme-explicit-dark` en la matriz, alcanzados por el selector y con fondo calculado igual al token, también con el sistema emulado en el tema contrario; cada uno con su fixture que falla por `color-contrast` y su caso en `deliberate-failures.e2e.ts` [BX111, BX113 a BX116].
  - [ ] 9.3 **VERDE.** Implementar la preferencia, la aplicación del tema y el enlace con la sesión (suscripción registrada antes de `createRoot` y antes de la de React); agregar el selector al menú; claves `theme.*`.
  - [ ] 9.4 **REFACTOR.** Suites en verde.
  - **Demostración deliberada.** (a) `bind-theme-to-session` que aplica el tema también en `anonymous`: debe fallar SH27; (b) escribir el token en `localStorage`: debe fallar BX124; (c) un valor de tema no validado: debe fallar BX125. Procedimiento con `cmp`.
  - **Comprobaciones.** **U**, **E** (puerta con 25 estados), **PR**; guardas de `apps/api` y de `*.spec.*`.
  - **Commit.** `feat(admin-web): add a three-state theme selector to the authenticated user menu`.

## Fase 6: avisos de sesión y recorridos (PR 6a y 6b)

- [ ] 10 **PR 6a, `6a-session-warnings`: avisos de inactividad y de vencimiento absoluto en la interfaz (H9).**
  - **Archivos.** Crea `src/features/identity/session-warnings/SessionWarningsContainer.tsx` y su prueba, `src/features/identity/session-warnings/components/IdleWarning.tsx` y `AbsoluteExpiryWarning.tsx` con sus pruebas (`// States.`, AW57). Modifica `src/features/shell/AuthenticatedLayout.tsx` (monta el contenedor fuera del `main`), `src/i18n/locales/es-HN/admin.json` (claves `sessionWarning.*`) y `src/app/structure.test.ts` solo si el inventario lo exige.
  - **Especificaciones.** SS153, SS154, SS155, SS156, SS157, SS158, SS159, SS160, SS161, SS162, SS163, SS164, SS165 (aviso de inactividad: texto y tiempo restante, `assertive`, foco inicial en «Continuar», sin peticiones al mostrarse, anuncios que cambian a lo sumo dos veces, «Continuar» extiende con un solo refresco, lo escrito se conserva, Escape equivale a «Continuar», fallo de red con reintento de la persona, cierre de sesión normal); SS171, SS172, SS173, SS174, SS175, SS176 (aviso absoluto modal con «Entendido» y «Cerrar sesión ahora», sin extensión, foco en «Entendido», Escape equivale a «Entendido», no reaparece salvo que `S` cambie, precedencia sobre el de inactividad); SH32, SH33, SH34 (textos en el catálogo `admin`, en «usted»); AW57.
  - **Inversiones.** Ninguna directa; SS124 y SS125 se invierten en el recorrido de la tarea 11, y los textos nuevos esperan aprobación del propietario.
  - [ ] 10.1 **ROJO, presentaciones.** Cada una con sus estados declarados (`// States.`): abierta con cuenta atrás, con error de red y reintento, y en espera; sin peticiones; foco inicial en la acción segura, nunca en «Cerrar sesión» [SS155, SS173].
  - [ ] 10.2 **ROJO, contenedor con sesión falsa.** Muestra el aviso según la fase del programador con `useSyncExternalStore`; «Continuar» llama una sola vez a `extend` y con `200` cierra el aviso y devuelve el foco al origen; Escape equivale a «Continuar»; el fallo de red deja el diálogo abierto, muestra un `Alert` de error y no reintenta solo; otro rechazo termina la sesión local; «Entendido» descarta sin petición y sin cambiar `sessionExpiresAt`; con la ventana absoluta abierta no se muestra el de inactividad; una actividad no refresca mientras hay un aviso [SS153 a SS165, SS171 a SS175].
  - [ ] 10.3 **VERDE.** Implementar las presentaciones, el contenedor y el montaje en el layout; claves `sessionWarning.*` (el texto de inactividad es el de `docs/ui-ux/04`:612, los demás se proponen al propietario).
  - [ ] 10.4 **REFACTOR.** Suites en verde; el contenedor solo lee plazos guardados y nunca llama a `GET /sessions/current`.
  - **Demostración deliberada.** (a) El foco inicial en «Cerrar sesión»: debe fallar SS155; (b) mostrar el aviso llama a la API: debe fallar SS157; (c) «Entendido» que pide `extend`: debe fallar SS173. Procedimiento con `cmp`.
  - **Comprobaciones.** **U**, **E** (todos los recorridos anteriores en verde: el aviso solo aparece con reloj controlado), **PR**; guardas de `apps/api`.
  - **Commit.** `feat(admin-web): warn before idle and absolute session expiry with modal alert dialogs`.

- [ ] 11 **PR 6a: recorridos del ciclo de vida y estados `idle-warning` y `absolute-warning` de la puerta (H14, H15).**
  - **Archivos.** Crea `e2e/specs/journeys/session-lifecycle.e2e.ts`, `e2e/fixtures/a11y/idle-dialog-unnamed.html` y `absolute-dialog-low-contrast.html`. Modifica `e2e/specs/journeys/signed-in.e2e.ts`, `e2e/support/a11y-matrix.ts` (estados `idle-warning` y `absolute-warning`), `e2e/specs/a11y/deliberate-failures.e2e.ts`, `e2e/specs/journeys/journeys-inventory.test.ts`.
  - **Especificaciones.** SS19 (e2e precisado), SS124, SS125, SS166 (el aviso nunca aparece antes de 24 min sin actividad), SS167 a SS170 (un solo `DELETE`, fallo invisible, aviso de llegada y foco en el encabezado), SS176, SS190, SS191 (reloj controlado, sin esperar 30 s de tiempo real); SS135, SS136 (suspensión simulada); SS137 a SS140 (desfase de reloj en el navegador); SS144 a SS147 (a lo sumo un refresco por cadencia); BX109 (27 estados, 54 evaluaciones), BX111, BX113, BX117, BX118 y BX119 (el criterio 8 sigue marcado); AW12, AW68, AW69 en todos los recorridos.
  - **Inversiones (nota fechada 2026-11-10 que cita este cambio).** **SS124** (`signed-in.e2e.ts`:96): se invierte, el aviso aparece y la sesión termina al vencer. **SS125** (`signed-in.e2e.ts`:112): se invierte, existe un diálogo con región `assertive`. **SS19** (`signed-in.e2e.ts`:55): se precisa en el recorrido, sin actividad no hay refrescos y el `DELETE` es la única petición por tiempo. **BX109**: de 25 a 27 estados y de 50 a 54 evaluaciones.
  - [ ] 11.1 **ROJO.** `session-lifecycle.e2e.ts` con `page.clock` y los controles de H14: aviso de inactividad con «Continuar» y con Escape; cierre al vencer con un solo `DELETE`; aviso absoluto con «Entendido» y sin «Continuar»; precedencia con las dos ventanas abiertas; actividad con a lo sumo un refresco por cadencia; suspensión simulada; desfase adelantado, atrasado, con `Date` omitida y con `Date` inválida; mostrar el aviso sin peticiones [SS19, SS124, SS125, SS135 a SS140, SS144 a SS147, SS166 a SS170, SS176, SS190, SS191]. Estados `idle-warning` y `absolute-warning` con sus fixtures y casos de violación deliberada [BX109, BX111, BX113, BX117, BX118].
  - [ ] 11.2 **VERDE.** Ajustar solo lo que los recorridos revelen (el código de las tareas 1 a 10 ya existe); completar la matriz de 27 estados.
  - [ ] 11.3 **REFACTOR.** Suites en verde; ningún recorrido espera 30 s o más de tiempo real y ninguno viola la CSP (AW12).
  - **Demostración deliberada.** (a) Un aviso que no entra en la matriz: la completitud debe fallar [BX112]; (b) la fixture `idle-dialog-unnamed.html` sin `aria-dialog-name`: la prueba de violación deliberada debe fallar; (c) quitar el `DELETE` de `endByTimeout`: debe fallar SS167. Procedimiento con `cmp`.
  - **Comprobaciones.** **U**, **E** (puerta con 27 estados y 54 evaluaciones), **PR**; medir el tamaño de 6a (tareas 10 y 11) antes de comprometer; si 6a más el recorrido de pestañas cabe en 800 se entregan en un solo PR 6; guardas de `apps/api` y de `*.spec.*`.
  - **Commit.** `test(admin-web): cover the session warnings, timeout sign-out and clock skew end to end`.

- [ ] 12 **PR 6b, `6b-tabs-journeys`: recorridos de dos pestañas (S1/D8).**
  - **Archivos.** Crea `e2e/specs/journeys/tabs.e2e.ts`. Modifica `e2e/specs/journeys/signed-in.e2e.ts`, `e2e/specs/journeys/secrets.e2e.ts` (mensajes capturados del canal sin token) y `e2e/specs/journeys/journeys-inventory.test.ts`.
  - **Especificaciones.** SS126 (sincronización entre pestañas y detección de actividad), SS177 a SS184 (extremo a extremo: cierre sincronizado con y sin vencimiento, plazos compartidos que cierran el aviso, pestaña sin sesión ignora, sin reenvío), SS147 (dos pestañas activas se serializan con el Web Lock), SS190, SS191; AW12, AW68, AW69 en los dos contextos.
  - **Inversiones (nota fechada 2026-11-10 que cita este cambio).** **SS126** (`signed-in.e2e.ts`:122): se invierte; hay sincronización entre pestañas y detección de actividad. Conserva el identificador.
  - [ ] 12.1 **ROJO.** `tabs.e2e.ts` con `context.clock` y dos pestañas: cerrar sesión en A lleva a B al inicio de sesión sin aviso y sin `DELETE` propio de B; el cierre por vencimiento en A lleva a B al aviso de sesión vencida; la extensión en A cierra el aviso de inactividad abierto en B sin emitir un refresco de B; un mensaje capturado nunca contiene un token, un código, un identificador de cuenta ni forma de JWT; dos pestañas activas piden refrescar a la vez y el servidor recibe peticiones serializadas sin revocar la familia [SS126, SS147, SS177 a SS184].
  - [ ] 12.2 **VERDE.** Ajustar solo lo que los recorridos revelen.
  - [ ] 12.3 **REFACTOR.** Suites en verde.
  - **Demostración deliberada.** (a) El canal que transmite el token de acceso: debe fallar SS177 por centinela; (b) la pestaña B que reenvía `signed-out`: debe fallar SS181. Procedimiento con `cmp`.
  - **Comprobaciones.** **U**, **E**, **PR**; guardas de `apps/api` y de `*.spec.*`.
  - **Commit.** `test(admin-web): cover cross-tab sign-out and deadline sharing end to end`.

## Fase 7: documentos y cierre (PR 7)

- [ ] 13 **PR 7, `7-docs-notes`: notas fechadas en `docs/`, `docs-notes.test.ts` y verificación de cierre (BX119 a BX122).**
  - **Archivos.** Modifica `docs/09-roadmap-y-fases.md`, `docs/ui-ux/04-patrones-de-interaccion.md`, `docs/ui-ux/01-*.md` y `src/app/docs-notes.test.ts`. Solo se agregan notas fechadas; los cuerpos existentes no se reescriben.
  - **Especificaciones.** BX119 (el criterio 8 sigue marcado), BX120 (`docs/ui-ux/04`: shell solo con barra superior y política de actividad), BX121 (`docs/ui-ux/01`: selector de tema), BX122 (`docs-notes.test.ts` suma las notas de 13b-2 sin quitar las de 13b-1); SS127 y SS192 (ausencias con dueño: lo que pertenece a futuros cambios y al 8b), y SH35 a SH37 y AW60 a AW63 (ausencias de barra lateral, migas e identidad) se revisan como inventario final.
  - **Inversiones (nota fechada 2026-11-10 que cita este cambio).** **`docs-notes.test.ts`**: suma las notas de 13b-2 con la técnica de `src/i18n/voice-note.test.ts` (texto original fijado por longitud y digest SHA-256 en forma LF; el resto debe ser solo la nota nueva). **`docs/09`**: nota fechada con el resultado de 13b-2 sin desmarcar el criterio 8.
  - [ ] 13.1 **ROJO.** `docs-notes.test.ts` falla por las tres notas ausentes y por la pérdida del criterio 8 si se desmarcara; las notas de 13b-1 siguen verificadas [BX119 a BX122].
  - [ ] 13.2 **VERDE.** Escribir las tres notas fechadas 2026-11-10 en español neutro profesional, con referencia a este cambio y al ADR de H17 (con su número confirmado).
  - [ ] 13.3 **Verificación de cierre de la cadena.** En el árbol final: **PR** completo; la puerta de axe sin violaciones críticas ni serias en los 27 estados en los dos temas; cada estado nuevo con su fixture que falla; ninguna violación de CSP en los recorridos (AW12); `git diff --name-only <main> -- apps/api` vacío; ningún PR de la cadena supera 800 líneas efectivas; cada prueba de ausencia de la tabla anterior invertida o precisada con nota fechada, ninguna borrada.
  - **Comprobaciones.** **U**, **E**, **T**, **PR**; guardas de `apps/api` y de `*.spec.*`.
  - **Commit.** `docs(admin-web): record the shell and session lifecycle notes of change 13b-2`.

---

## Verificación del cambio (criterios de éxito de la propuesta)

| Criterio | Tarea que lo cierra |
|---|---|
| `pnpm turbo run lint typecheck test build --filter=!@confia/api` en verde | Todas; 13 lo verifica al final |
| Barra superior, enlace de salto, menú con etiqueta genérica, tema y «Cerrar sesión», panel sin acción | 6, 8, 9 |
| Con actividad, a lo sumo un refresco por cadencia y por pestaña; sin actividad, aviso 5 min antes, anunciado con `assertive` | 3, 10, 11 |
| «Continuar» extiende; al terminar la cuenta atrás, cierre local y `DELETE` de mejor esfuerzo | 2, 10, 11 |
| El aviso absoluto aparece y no ofrece «Continuar» | 10, 11 |
| Cerrar sesión en una pestaña lleva a las otras al inicio de sesión; ningún mensaje del canal contiene un token | 3, 12 |
| Ningún temporizador ni consulta de fondo pide un refresco; mostrar el aviso no hace peticiones | 2, 10, 11 |
| El tema se conserva al recargar; el acceso sigue el sistema | 9 |
| Puerta de axe verde en 27 estados y dos temas, con fixture de violación por estado nuevo | 7, 8, 9, 11 |
| Ninguna violación de CSP; el build sin scripts en línea | 5, 9, 11 |
| Pruebas de ausencia invertidas o precisadas con nota fechada | 2, 3, 6, 8, 9, 11, 12, 13 |
| Ningún archivo de `apps/api` cambió y ningún PR supera 800 líneas efectivas | Cada cierre; 13 lo verifica |

## Preguntas abiertas (design §15)

- [ ] Número del ADR de H17: se confirma el próximo número libre al escribirlo en la tarea 5 (hoy el último en este árbol es ADR-0028; la rama de backend en curso puede reservar números).
- [ ] Textos nuevos (aviso absoluto, «Entendido», «Cerrar sesión ahora», shell y tema): se proponen en los PR 4, 5 y 6a y esperan aprobación del propietario.
- [ ] Con 8 o 9 PR, la entrega supera el objetivo D11 de 5 a 7 PR: decisión del propietario antes de aplicar (ver el Review Workload Forecast). Las tareas son 13 y no activan la salida a dos cambios.
- [ ] La nota fechada que agrega `layouts/` al requisito de carpetas de `design-system` (la hace la tarea 6).
