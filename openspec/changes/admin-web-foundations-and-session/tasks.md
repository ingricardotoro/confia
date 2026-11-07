# Tareas: fundaciones de `apps/admin-web` y sesión (cambio 13b-1 de F0, parte 1 de 2)

- **Cambio:** `admin-web-foundations-and-session`. La parte 2, `admin-web-staff-sign-in`, empieza cuando esta esté fusionada.
- **Fase:** tareas
- **Fecha:** 2026-11-05
- **Estado:** pendiente de aprobación del propietario
- **Entradas aprobadas:** `proposal.md` (P1 a P3, P6 a P10, Q1 y Q2 vinculantes), `design.md` (D1 a D19, secciones 9 a 13) y los deltas `specs/admin-web-foundation`, `specs/staff-sign-in`,
  `specs/design-system`, `specs/build-integrity` y `specs/identity` (identificadores `AW`, `SS`, `DS`, `BX` e `I`)
- **Modo de TDD:** estricto. El `test_command` de `openspec/config.yaml` es solo del backend; esta lista fija el comando por superficie.
- **Criterio de salida 8:** esta parte **no lo cierra ni lo marca**. Lo cierra la parte 2.
- **Límite conocido:** ningún tiempo ni versión se midió aún. Las cifras son las del diseño, y las versiones se fijan en la tarea 2 (D19).

## Review Workload Forecast

| Field | Value |
|-------|-------|
| Estimated changed lines (additions + deletions, sin `openspec/`, archivo de bloqueo ni código generado) | **6 060 a 2x y 9 430 a 4x** (diseño §12: producción, configuración y documentos 2 690; pruebas e infraestructura de prueba 1 685). Se planea a 4x, porque las pruebas del cambio 8 superaron su estimación cerca de cuatro veces |
| 400-line budget risk | **High** frente a las 400 líneas de la preflight; **alto por PR** frente al presupuesto del proyecto de 800 en cinco rebanadas |
| Chained PRs recommended | Yes |
| Suggested split | **De 12 a 17 PR apilados.** 12 rebanadas, con cortes predefinidos en las tareas 1, 6, 9, 11 y 12. El corte de la tarea 4 se usa solo si la medición lo exige, y daría 18 |
| Delivery strategy | Cadena de PR apilados aprobada por el propietario (Q1); 800 líneas efectivas por PR (`docs/15-flujo-de-trabajo-git.md` §3). La decisión ya está tomada, por lo que se comporta como `auto-chain` |
| Chain strategy | `stacked-to-main`: cada PR parte de la rama del anterior y se fusiona a `main` en orden; antes de abrir el siguiente se actualiza su base |
| Presupuesto del proyecto por PR | 800 líneas de cambio efectivo; las pruebas y la documentación que el cambio invalida nunca se separan del código |

Decision needed before apply: No (Q1 resuelta el 2026-11-05; el diseño decía Yes antes de esa resolución)
Chained PRs recommended: Yes
Chain strategy: stacked-to-main
400-line budget risk: High

**Cuenta de tareas.** **12 tareas de nivel superior**, una por PR de la parte 1, frente al tope de 15 de `openspec/config.yaml`. Los cortes son subtareas condicionales de su tarea, no tareas nuevas: en el peor caso son 17 PR y siguen siendo 12 tareas.

### Tamaño y cortes por rebanada (líneas efectivas, estimación del diseño §12)

| Tarea | PR | Rama (sufijo de `change/admin-web-foundations-and-session-`) | P | T | P+2T | **P+4T** | Veredicto a 4x | Corte predefinido |
|---|---|---|---|---|---|---|---|---|
| 1 | 1 | `1-app-static-rules` | 220 | 150 | 520 | **820** | Se parte | **1a** `app-eslint-rules`: ESLint y sus pruebas / **1b** `app-dependency-and-suppression-rules`: dependency-cruiser, *fixtures* y escáner |
| 2 | 2 | `2-admin-web-package-and-router` | 290 | 90 | 470 | **650** | Cabe | Ninguno |
| 3 | 3 | `3-providers-i18n-and-voice` | 300 | 110 | 520 | **740** | Cabe | Ninguno |
| 4 | 4 | `4-api-transport-and-problem-map` | 270 | 130 | 530 | **790** | Justo | Solo si mide más de 800: **4a** transporte y CSRF / **4b** Problem y mapa de claves |
| 5 | 5 | `5-auth-operations-and-contract-mocks` | 230 | 110 | 450 | **670** | Cabe | Ninguno |
| 6 | 6 | `6-session-basics` | 325 | 170 | 665 | **1 005** | Se parte | **6a** `session-store-and-refresh`: almacén, coordinador y Web Locks / **6b** `session-operations`: `AccessSession` y consulta de la sesión |
| 7 | 7 | `7-ui-alert-and-fixes` | 160 | 90 | 340 | **520** | Cabe | Ninguno |
| 8 | 8 | `8-ui-form-error-summary` | 125 | 90 | 305 | **485** | Cabe | Ninguno |
| 9 | 9 | `9-boot-and-signed-in-page` | 380 | 150 | 680 | **980** | Se parte | **9a** `boot-guards-and-not-found`: `BootGate`, rutas, guardas, pantallas provisionales, no encontrada / **9b** `signed-in-page-and-sign-out`: página mínima, cierre y flujo jsdom |
| 10 | 10 | `10-a11y-policy-move-and-adr` | 210 | 15 | 240 | **270** | Cabe | Ninguno |
| 11 | 11a | `11a-simulated-api` | 60 | 340 | 740 | **1 420** | Se parte | **11a-1** `simulated-api-session-model`: modelo de sesión, TOTP y validación de contrato / **11a-2** `simulated-api-server`: servidor, escenarios, control y centinelas |
| 12 | 11b | `11b-route-gate-and-ci` | 120 | 240 | 600 | **1 080** | Se parte | **11b-1** `route-gate-and-ci`: Playwright, puerta, matriz, *fixtures*, turbo y CI / **11b-2** `boot-and-signed-in-journeys`: recorridos y `specs/build` |
| | | **Total** | **2 690** | **1 685** | **6 060** | **9 430** | | |

P es producción, configuración y documentos; T, pruebas e infraestructura de prueba, y el simulador cuenta como T. El PR 10 cuenta el traslado como renombre. **Son estimaciones, no mediciones.** Los nombres de los cortes los propone este desglose: el diseño fija dónde se parte, no por qué costura.

**Órdenes duros (proposal y diseño §12).** 1 antes de 2; 2, 3, 4, 5 y 6 en ese orden; 7 antes de 9; 3 y 6 antes de 9; 10 antes de 12; 9 antes de 12; 11 antes de 12. La cadena lineal 1 a 12 los cumple todos.

### Reglas comunes a todas las tareas

- **Regla de las 800 líneas: se detiene antes de comprometer.** Antes del primer commit de cada PR, y otra vez antes de cada commit siguiente, se mide:
  `git add -N . && git diff --numstat -M <base> -- . ':(exclude)openspec' ':(exclude)pnpm-lock.yaml' ':(exclude)packages/contracts/src/generated'`, sumando adiciones y eliminaciones. **Si supera 800, el PR no se compromete.** El ejecutor se detiene, registra la medida en `apply-progress.md` y aplica el corte predefinido de su tarea, por archivos completos y con las pruebas junto a su código. Vuelve a medir cada parte antes de comprometerla. Si la tarea no tiene corte predefinido, o una parte sigue por encima de 800, **no compromete y consulta al propietario**. Nunca se quitan pruebas, se minifica ni se borran comentarios o líneas en blanco para caber.
- **Suite completa de cada superficie tocada.** Las órdenes focalizadas son solo para iterar. Ninguna tarea se cierra con ellas: en el cambio 8 las corridas focalizadas no vieron una prueba de inventario que solo CI detectó. La tabla de abajo dice qué suite corresponde a cada ruta, y **cada** tarea cierra además con la prueba de PR completo.

  | Ruta tocada | Suite completa |
  |---|---|
  | `apps/admin-web/**` | **U** `pnpm --filter @confia/admin-web test` |
  | `apps/admin-web/e2e/**`, `playwright.config.ts`, o cualquier cambio de la app desde la tarea 12 | **E** `pnpm --filter @confia/admin-web test:e2e` |
  | `packages/config/**` | **C** `pnpm --filter @confia/config test`, más **T** y **UI**, porque ambos la consumen |
  | `tooling/**`, `.dependency-cruiser.cjs`, `.github/workflows/**`, `turbo.json`, `pnpm-workspace.yaml` | **T** `pnpm --filter ./tooling test` |
  | `packages/ui/**` | **UI** `pnpm --filter @confia/ui test` y `pnpm --filter @confia/ui test:a11y` |
  | Siempre, al cerrar | **PR** `pnpm turbo run lint typecheck test build --filter=!@confia/api` |

- **Guardas de `apps/api` que miran `apps/`.** `IdentityScopeExclusionInventoryTest.java:163` recorre todo `apps/` y falla ante un `*.spec.ts` o `*.spec.js`. Este cambio no ejecuta `backend verify` local (exige Docker y JDK), de modo que cada cierre comprueba sin Java lo mismo: `git ls-files -co --exclude-standard apps | grep -E '\.spec\.(ts|js)$'` no devuelve nada. Los recorridos se llaman `*.e2e.ts` (D18). El trabajo `backend verify` del PR confirma el resultado en CI.
- **`apps/api` no cambia (AW10).** `git diff --name-only <base> -- apps/api` vacío en cada cierre.
- **Rojo registrado.** Cada paso en rojo corre contra el árbol sin el código de producción y se registra en `apply-progress.md` con la causa **observada**. Si falla por otra causa, se detiene y se investiga. Un commit en rojo no entra al historial. Después del verde, un paso de **REFACTOR** deja la suite en verde antes de comprometer.
- **Demostración deliberada.** Cada tarea nombra rupturas temporales de producción que deben poner en rojo una prueba concreta **por una aserción de resultado**, nunca por tiempo de espera. Procedimiento fijo: (1) copiar el archivo fuera del repositorio, al directorio temporal de la sesión; (2) aplicar la ruptura y observar el rojo por la aserción nombrada; (3) restaurar desde la copia; (4) **confirmar con `cmp`** que el restaurado es idéntico a la copia (sin salida) y que `git diff` no muestra la ruptura; (5) registrar ruptura, aserción y resultado de `cmp`. Una demostración sin revertir y confirmar bloquea el cierre. Los *fixtures* de violación permanentes del diseño §4 no son demostraciones: se quedan.
- **Conjunto vacío (ADR-0018).** Lo que hoy no tiene archivos reales entra como `it.todo` y se convierte en prueba en la tarea que lo activa (D3). Un paso en verde sobre un conjunto vacío no se acepta.
- **Secretos y registros.** Ningún secreto en el repositorio (regla 13): las pruebas generan en ejecución sus centinelas. Nada de contraseñas, tokens ni `Authorization` en consola o informes (regla 11).
- **Texto.** Ninguna cadena embebida en componentes. El catálogo `admin` va en «usted», con los valores de `design.md` §8, aprobados por el propietario. El catálogo `ui` no se dirige a la persona.
- **Sin importes.** Este cambio no muestra dinero; las reglas 1 y 3 de `CLAUDE.md` rigen por análisis estático (D2).
- **Commits.** Conventional Commits en inglés, sin atribución de herramientas de IA, con pruebas y documentación en el mismo commit que el comportamiento. Una rama por PR, con el sufijo de la tabla: la primera parte de `change/admin-web-foundations-and-session` y cada una siguiente de la anterior.
- **Revisión.** Lista aplicable de `docs/03` marcada en la descripción de cada PR; empujar, abrir PR y fusionar son decisiones del propietario.
- **Idioma.** Código, identificadores y commits en inglés; documentación y notas fechadas en español neutro profesional, sin reescribir cuerpos existentes.

### Orden de ejecución (revisado el 2026-11-05)

El propietario pidió ver una pantalla funcional con valores de prueba lo antes posible y aprobó este orden para las tareas que quedan. Los números de tarea y de PR no cambian; solo cambia el orden en que se aplican y se apilan:

**2 → 3 → 4 → 5 → 6 → 11 (11a) → 7 → 9 → 8 → 10 → 12 (11b)**

- La tarea 11 depende solo de la 5, así que se adelanta sin romper dependencias.
- La tarea 9 sigue dependiendo de la 3, la 6 y la 7, y es la primera pantalla funcional: arranque, página mínima y cierre de sesión contra la API simulada.
- La tarea 8 no es dependencia de la 9 y pasa después de ella. La 12 sigue dependiendo de la 9, la 10 y la 11, y cierra la parte 1.
- Cada rama parte de la anterior **en este orden**. La tabla de tamaños conserva los sufijos de rama.

### Dependencias y paralelismo

| Tarea | Depende de | Paralelismo |
|---|---|---|
| 1 | Ninguna | Raíz de la cadena |
| 2 | 1 | Secuencial: la app nace bajo sus reglas |
| 3 | 2 | Secuencial |
| 4 | 3 | Secuencial: usa `AdminKey` para el mapa de códigos |
| 5 | 4 | Secuencial |
| 6 | 5 | Secuencial |
| 7 | Ninguna de la app | Archivos disjuntos, pero se fusiona en su lugar. Un solo escritor |
| 8 | 7 | Secuencial en `ui.json` e `index.ts` |
| 9 | 3, 6 y 7 | Secuencial |
| 10 | Ninguna de la app | Archivos disjuntos, pero se fusiona antes de la 12 |
| 11 | 5 | `contract-responses` viene de la 5 |
| 12 | 9, 10 y 11 | Cierra la parte 1 |

### Tabla de verificación (qué prueba cierra cada tarea)

| Tarea | Verde observable | Control deliberado (con `cmp`) | Suites |
|---|---|---|---|
| 1 | Reglas de app con violación por regla; `ui` igual que en 13a | Red sin regla, excepción ensanchada, regla de `ui` debilitada, entrada pública quitada | T, C, UI, PR |
| 2 | Paquete real en las cuatro tareas; proxy conserva `Origin`; destino no local aborta; antigüedad vigente | Destino abierto, `changeOrigin: true`, antigüedad reducida | U, T, PR |
| 3 | Catálogo tipado; voz; Query por defecto | Reintento de mutación, `networkMode` por defecto, clave quitada, tuteo en el catálogo | U, C, T, UI, PR |
| 4 | Tablas de Problem; `502` HTML; 31 s sin cancelar; nombre exacto de cookie | `safeParse` completo, límite de 20 s, `includes` en la cookie, `detail` registrado | U, PR |
| 5 | Cabeceras y cuerpos exactos; fuera de esquema, «inesperada» | `Authorization` en el cierre, `Idempotency-Key`, uso parcial de un cuerpo inválido | U, PR |
| 6 | Un refresco por pestaña; CSRF dentro del bloqueo; 59 frente a 60 s | CSRF fuera del bloqueo, sin promesa compartida, frontera `<=`, reintento, refresco reactivo | U, PR |
| 7 | `Alert` en dos temas; anillo sin transición; etiqueta y párrafo | `transition-colors`, sin `trim`, `p` vacío, rol invertido | UI, PR |
| 8 | Resumen con foco sin navegar; 21 exportaciones; `controlId` | Sin `preventDefault`, exportación extra, `controlId` ignorado, «Revise» en `ui.json` | UI, PR |
| 9 | Arranque, guardas, página mínima, cierre; cachés sin tokens | Sondeo sin cookie, cierre declarado sin `204`, token en caché, foco quitado | U, T, PR |
| 10 | Política en `@confia/config`, puerta de historias igual; ADR-0028 | Moderado bloquea, copia en `ui`, reexportación desde `ui` | C, UI, T, PR |
| 11 | RFC 6238; gracia a 5 y 30 s; fuera de contrato falla | Gracia de 30 s, sin validar, recuperación aceptada con `mfa-enroll`, SHA-256 | U, PR |
| 12 | Puerta 6 por 2 en verde; falla con cada *fixture* y con cero; CI aditivo | Sin etiquetas, sin espera del contenido, matriz vacía, sin `if: always()`, intervalo de refresco, foco activo | E, U, T, PR |

---

## Fase 1: reglas y aplicación

- [x] 1 **PR 1 `app-static-rules`: reglas de ESLint separadas, cuatro reglas de dependencia y escáner sobre `apps/` (D2, D3).**
  - **Archivos.** Modifica `packages/config/eslint.js`, `eslint.d.ts`, `.dependency-cruiser.cjs`, `tooling/src/suppression-scanner.ts` (agrega `target`, `playwright-report`, `test-results`), `tooling/src/dependency-rules.test.ts`, `tooling/src/suppression-citations.test.ts`, `tooling/dependency-fixtures/README.md`. Crea `tooling/src/eslint-app-rules.test.ts`, `tooling/dependency-fixtures/apps/admin-web/src/**`, `tooling/src/__fixtures__/suppressions/*`.
  - [x] 1.1 **ROJO.** `eslint-app-rules.test.ts`, con código en línea: literal en JSX y `aria-label` [BX38, BX39]; `toFixed` y `toLocaleString` [BX40, BX41]; `Number(money.amount)` y `money.amount * 2` [BX44, BX45]; cada interfaz de red en `src/features` [BX49 a BX52, BX110], por `window` y `globalThis` [BX53]; `e2e` sin regla de red [BX55]; `src/shared/api-helpers` sin excepción [BX56]; `fetch` en `packages/ui` falla y en `src/shared/api` no [BX47]; las violaciones de 13a nombran la misma regla [BX48]; una escritura a `document.cookie` falla. `dependency-rules.test.ts`: `imports-ui-internal.ts` [AW04]; presentación con Query, `shared/api` relativo y por alias, y `import type` [BX58 a BX60]; router y Form en presentación; `admin-shared-never-imports-features` y `admin-token-store-is-private`. `suppression-citations.test.ts`: directiva sin ADR en `apps/` falla [BX63], con ADR no cuenta [BX64]. `it.todo`: BX46, BX54, BX57, BX61, BX62, BX65. **Rojo esperado:** `appSourceRules is not a function` y las reglas `apps-use-ui-public-entry` y `admin-presentation-is-pure` inexistentes en la salida de `cruise`.
  - [x] 1.2 **VERDE y REFACTOR.** `jsxTextControls("useAdminText().t")`, `APP_NO_NETWORK`, `APP_NO_NETWORK_PROPS`, `APP_MONEY`, `NO_COOKIE_WRITE`, `appSourceRules({ portal })` y `confiaEslint({ app: true })`; `uiSourceRules` conserva firma y mensajes. Cuatro reglas de D3. Escáner con `apps/` y sus exclusiones.
  - [x] 1.3 **Demostraciones deliberadas.** (1) Quitar `APP_NO_NETWORK` de `eslint.js` pone en rojo BX49. (2) Ensanchar el `ignores` a `src/shared/api*/**` pone en rojo BX56. (3) Quitar la red de `uiSourceRules` pone en rojo BX47 y BX48. (4) Quitar `apps-use-ui-public-entry` de `.dependency-cruiser.cjs` pone en rojo AW04. Se revierten y se confirman con `cmp`.
  - [x] 1.4 **Cierre.** Suites **T, C, UI, PR**, guarda de `*.spec.*` y AW10. Commits: `feat(tooling): separate the app static rules from the ui-only rules`, `feat(tooling): add the admin web dependency rules and scan apps for suppressions`. Tamaño: 820 a 4x. **Corte predefinido:** 1a (ESLint) / 1b (dependencias, *fixtures* y escáner). — Escenarios de la fila 1 de la trazabilidad.
  - **Evidencia (2026-11-05).** Medido: **702 líneas efectivas** (679 adiciones y 23 eliminaciones, con archivos nuevos), por debajo de 800; **no se aplicó el corte** y quedó una rama con dos commits (1a ESLint: 482; 1b dependencias, *fixtures* y escáner: 220). Rojo observado en las tres superficies (58 fallos de ESLint, 14 de dependencias, 1 del escáner) con las causas esperadas; los pasos sin rojo (BX48, BX63, BX64 y los aceptados de BX61) están declarados en `apply-progress.md`. Demostraciones 1 a 4 más una adicional, revertidas y confirmadas con `cmp`. Suites **T** (257 pasan, 6 `todo`), **C** (4), **UI** (524 y 159 de accesibilidad) y **PR** en verde; guarda de `*.spec.*` y AW10 sin coincidencias. Un `todo` por escenario sin archivos reales: BX46, BX54, BX57, BX61, BX62 y BX65. Detalle, desviaciones y elementos abiertos en `apply-progress.md`.

- [x] 2 **PR 2 `admin-web-package-and-router`: paquete, Vite, proxy, enrutador mínimo, antigüedad y medida de CI (D1, D4, D5, D16, D18, D19).**
  - **Archivos.** Crea `apps/admin-web/{package.json,index.html,tsconfig.json,eslint.config.js,vite.config.ts,vitest.config.ts}`, `src/main.tsx`, `src/app/{App,router}.tsx`, sus pruebas. Modifica `pnpm-workspace.yaml`, `pnpm-lock.yaml`, `turbo.json` (solo `dev`), `tooling/src/workspace-guards.test.ts`, `design.md` (nota fechada de BX89 en D16).
  - [x] 2.1 **Dependencias, primero.** Fijar versiones (D19): publicadas hace más de 14 días, `pnpm audit --audit-level high` limpio y `pnpm why` sin duplicar `react` ni `axe-core`; `@playwright/test` exactamente 1.64.1; `@axe-core/playwright` resuelve a `axe-core` 4.14.0. `qrcode.react` y `jsqr` no entran aquí: son del PR 14. **Rojo por verificar:** que pnpm 12.8.1 lea `minimumReleaseAge`. Si una versión queda bloqueada, se detiene y se reemplaza en el diseño, sin excepción.
  - [x] 2.2 **ROJO.** `workspace-guards.test.ts`: `minimumReleaseAge` existe y no es menor de 20 160 [BX93]; catálogo [BX92]; `devDependencies` [BX94]; `--frozen-lockfile` [BX91]; la vulnerabilidad alta rompe el escaneo [BX96]. `vite.config.test.ts`, con `createServer` y un servidor de prueba, sin Chromium: `dev` y `preview` reenvían ruta y método, con `Origin` y `Host` intactos [AW07, AW08]; `resolveApiTarget` aborta ante un host no local; sin CORS ni rama sin `Secure` [AW11]. Humo de la app [AW01]; sin `routeTree.gen` [AW16]; supresiones sobre archivos reales [AW17, BX65]; orval sin operaciones [AW62]; cobertura de 80 con archivos sin prueba en el denominador [BX97 a BX99]; `apps/api` sin cambios [AW10]. **Rojo esperado:** `Cannot find module vite.config`, `minimumReleaseAge` indefinido y `@confia/admin-web` sin tareas en turbo.
  - [x] 2.3 **VERDE y REFACTOR.** Paquete con `build`, `lint`, `typecheck`, `test`; `vite.config.ts` de D4 con `publicDir: false`, `assetsInlineLimit: 0`, puerto 5173 y CSP en `preview.headers`; `main.tsx`, `App` y `router` con una raíz; `minimumReleaseAge: 20160`; `dev` en `turbo.json`.
  - [x] 2.4 **Medida de CI [BX89].** En la primera ejecución remota del PR, medir `frontend verify` y agregar la cifra a `design.md` D16 con nota fechada. **Si no cabe en 10 minutos, se detiene el PR 2:** subir el límite cambia BX19 y lo decide el propietario. Queda pendiente mientras el propietario no empuje; la tarea no se marca sin la cifra.
  - [x] 2.5 **Demostraciones deliberadas.** (1) `resolveApiTarget` sin validar pone en rojo la prueba del destino. (2) `changeOrigin: true` pone en rojo la aserción de `Host` [AW07]. (3) `minimumReleaseAge: 10080` pone en rojo BX93. Se revierten y se confirman con `cmp`.
  - [x] 2.6 **Cierre.** Suites **U, T, PR**, guarda de `*.spec.*` y AW10. Commits: `build(admin-web): add the admin web package under the monorepo rules`, `build(deps): enforce the two week minimum release age`. Tamaño: 650 a 4x; sin corte predefinido. — Fila 2.
  - **Evidencia (2026-11-05).** Medido: **673 líneas efectivas** (sin corte). Rojo observado en cada superficie con su causa, en `apply-progress.md`. Demostraciones 1 a 3 revertidas y confirmadas con `cmp`. Suites **U** (25), **T** (268 y 5 `todo`) y **PR** (18 de 18) en verde; guarda de `*.spec.*` y AW10 sin coincidencias; `pnpm audit --audit-level high` sin altas. **Pendiente: 2.4** (medida de CI tras el primer empuje); por eso la tarea 2 queda sin marcar. Hallazgos: `minimumReleaseAge` bloqueó el bloqueo heredado (decisión del propietario: `overrides` de `browserslist`, nota en D19) y Vite añadía una cabecera de acceso entre orígenes (`cors: false`).

- [x] 3 **PR 3 `providers-i18n-and-voice`: proveedores, catálogo `admin`, voz, Query por defecto y nota de `docs/ui-ux/05` (D9, D17, sección 8).**
  - **Archivos.** Crea `src/i18n/{create-admin-i18n,resources,use-admin-text}.ts`, `src/i18n/locales/es-HN/admin.json`, `src/i18n/__fixtures__/*.fixture`, `src/app/query-client.ts`, `packages/config/{voice.ts,voice.test.ts,text-keys.ts}`, `src/test/text-keys.ts`. Modifica `packages/config/package.json`, `packages/ui/src/test/text-keys.ts` (cambia su importación), `docs/ui-ux/05-guia-de-contenido-y-voz.md` (nota fechada).
  - [x] 3.1 **ROJO.** Clave ausente y construida en `missing-key.fixture` y `built-key.fixture` [BX42, BX43, AW26, AW27]; catálogo evaluado no vacío [AW28]; texto del catálogo y `en` sin traducción [AW24, AW25]; «Inicia sesión de nuevo» falla nombrando la clave [AW30]; `admin.json` sin tuteo [AW29]; los dos textos fijados, exactos [AW32]; `docs/ui-ux/05` sin líneas eliminadas [AW31]; opciones de Query [AW23], con `networkMode: "always"` y `refetchOnWindowFocus: false`. **Rojo esperado:** `Cannot find module '@confia/config/voice'` y `query-client` inexistente.
  - [x] 3.2 **VERDE y REFACTOR.** `findVoiceViolations(catalog, rules)` sin dependencias, con la lista `admin` de D17; `createTextKeyChecker(packageRoot)`; `createAdminI18n` con `supportedLngs: ["es-HN"]`, `fallbackLng`, ICU; `AdminKey = Leaves<typeof admin>`; `retryRead` y `retryDelay` de D9; nota fechada: «usted» para el personal y sin cambios en el tono del portal.
  - [x] 3.3 **Demostraciones deliberadas.** (1) `mutations.retry: 1` pone en rojo AW23. (2) «Inicia sesión» en `admin.json` pone en rojo AW29 nombrando la clave. (3) Borrar una clave usada rompe `tsc` [AW26]. Se revierten y se confirman con `cmp`.
  - [x] 3.4 **Cierre.** Suites **U, C, T, UI, PR**, guarda y AW10. Commits: `feat(admin-web): add the admin catalog and the query defaults`, `docs(ui-ux): record the formal address for staff`. Tamaño: 740 a 4x; sin corte predefinido. — Fila 3.
  - **Nota fechada 2026-11-05.** Medido: 1 214 líneas efectivas, sobre las 800. Excepción explícita del propietario a `docs/15` §3: un único PR, sin partir. Detalle en `apply-progress.md`.

## Fase 2: cliente de API y sesión

- [x] 4 **PR 4 `api-transport-and-problem-map`: transporte, cookie CSRF, analizador de Problem y mapa de claves (D6, D7).**
  - **Archivos.** Crea `src/shared/api/{http,csrf-cookie,problem,problem-keys,report-unknown-problem}.ts` y sus pruebas. Modifica `tooling/src/*.test.ts` para convertir BX54 y BX57 de `todo` en prueba.
  - [x] 4.1 **ROJO.** `problem.test.ts`: tabla de quince [AW46], con `instance` relativo como lo emite el backend; algo nuevo, sin `type` y otro anfitrión [AW47]; `errors` y lista vacía [AW50]; `Retry-After` 15, ausente, `0`, `abc`, fecha HTTP y 99999 [AW52, AW53]; `502` y `503` HTML [AW43, AW56]; registro sin cuerpo y con `type` sin consulta ni fragmento, de a lo sumo 200 caracteres [AW55]. `problem-keys.test-d.ts`: código sin clave no compila [AW48]. `csrf-cookie.test.ts`: nombre parecido [AW40]. `http.test.ts`: red sin estado [AW43]; 31 s con temporizador simulado sin cancelar [AW44]; a los 60 s, «red», sin reintento; rutas relativas [AW45]. BX54 y BX57 sobre archivos reales. **Rojo esperado:** `Cannot find module './problem'`.
  - [x] 4.2 **VERDE y REFACTOR.** `ApiResult<T>` de D6, sin lanzar, único `fetch`; esquema angosto campo por campo, sin `ProblemDetail.safeParse`; `title`, `detail` e `instance` fuera del tipo.
  - [x] 4.3 **Demostraciones deliberadas.** (1) `ProblemDetail.safeParse` completo pone en rojo la tabla de AW46 por `instance`. (2) Tiempo límite de 20 s pone en rojo AW44. (3) `includes` en lugar de nombre exacto pone en rojo AW40. (4) Registrar `detail` pone en rojo AW55. Se revierten y se confirman con `cmp`.
  - [x] 4.4 **Cierre.** Suites **U, T, PR**, guarda y AW10. Commit: `feat(admin-web): add the api transport and the problem map`. Tamaño: 790 a 4x. **Corte solo si mide más de 800:** 4a (transporte y CSRF) / 4b (Problem y mapa). — Fila 4.
  - **Nota fechada 2026-11-06 (corte y excepción).** Medido: 1 091 líneas efectivas, sobre las 800. El propietario aprobó un corte ajustado: **4a** `api-transport` (`http`, `csrf-cookie`, `api-failure`, `problem`, `report-unknown-problem`, `query-client` y sus pruebas; commit `12e45c4`) y **4b** `problem-key-map` (`problem-keys`, `admin.json`, BX54 y BX57; commit `247d94c`). `problem.ts` pasó a 4a porque `http.ts` lo necesita. **4a mide 948 líneas (924 adiciones y 24 eliminaciones) frente a `496573c`, una excepción explícita del propietario a `docs/15` §3, aprobada el 2026-11-06**; 4b mide 143 frente a `12e45c4`. Con el corte la cadena pasa a 18 PR. Las cuatro demostraciones de 4.3 se hicieron en 4a y se revirtieron con `cmp`; las tres claves nuevas de `admin.json` y el cambio de `isRetryable` están en `apply-progress.md`.

- [ ] 5 **PR 5 `auth-operations-and-contract-mocks`: las seis operaciones y las respuestas de contrato (D6, D15).**
  - **Archivos.** Crea `src/shared/api/auth-operations.ts`, `src/test/{contract-responses,msw-server}.ts`, sus pruebas.
  - [ ] 5.1 **ROJO.** `auth-operations.test.ts`, con MSW en Node: método, ruta, cabeceras y cuerpo exactos de las seis [AW33 a AW38]; sin `Idempotency-Key` [AW39]; `200` fuera de esquema [AW41]; `status` tipado [AW42]; `EnrollmentResponse` con exactamente `secret` y `recoveryCodes` [I310]. Mutación con error de red, una petición [AW20]; lectura ante `503`, tres con 1 000 y 2 000 ms, y ante `401` y `404`, una [AW21, AW22]. Los tres escenarios conservados de TOTP no cambian de aserción ni tienen trabajo aquí: siguen en `apps/api`. **Rojo esperado:** `Cannot find module './auth-operations'`.
  - [ ] 5.2 **VERDE y REFACTOR.** Operaciones con tokens y CSRF por parámetro: `shared/api` no conoce la sesión. `contract-responses.ts` valida antes de devolver.
  - [ ] 5.3 **Demostraciones deliberadas.** (1) `Authorization` en el cierre pone en rojo AW35. (2) `Idempotency-Key` en una operación pone en rojo AW39. (3) Devolver un cuerpo inválido como `ok` pone en rojo AW41. Se revierten y se confirman con `cmp`.
  - [ ] 5.4 **Cierre.** Suites **U, PR**, guarda y AW10. Commit: `feat(admin-web): add the access operations and the contract responses`. Tamaño: 670 a 4x; sin corte predefinido. — Fila 5.

- [ ] 6 **PR 6 `session-basics`: almacén en memoria, coordinador de refresco y operaciones de sesión (D8).**
  - **Archivos.** Crea `src/features/identity/session/{index,access-session,token-store,refresh-coordinator,current-session-query}.ts` y sus pruebas.
  - [ ] 6.1 **ROJO, con reloj y bloqueo inyectados.** Tres disparos, una petición [SS14]; petición nueva al terminar [SS15]; rechazo compartido y promesa libre [SS16]; sin `navigator.locks` [SS18]; **CSRF leído dentro del bloqueo y sin cookie al entrar, cero peticiones** (invariante 3); opciones de la consulta [SS21]; 59 s refresca y 60 s no [SS22, SS23]; red sin reintento y la petición no sale [SS24]; `401` con `Bearer`, sin refresco [SS25]; `403 csrf-invalid` termina la sesión [SS26]. Prueba de almacén que prepara SS59, cuyo hogar está en la parte 2: con `authenticated` y `second-factor-required` no se conserva el identificador. **Rojo esperado:** `Cannot find module './access-session'`.
  - [ ] 6.2 **VERDE y REFACTOR.** `createAccessSession({ api, locks, now })` y la instancia de `session/index.ts`; `SessionView` sin tokens; `withCrossTabLock` con el bloqueo `confia.admin.session.refresh`; sin temporizadores. La cláusula de pantalla de SS18, SS25 y SS26 se vuelve a afirmar en el flujo de la tarea 9, sin cambiar su hogar.
  - [ ] 6.3 **Demostraciones deliberadas.** (1) Leer CSRF antes del bloqueo pone en rojo la prueba de la invariante 3. (2) Quitar `inFlight` pone en rojo SS14. (3) Cambiar `<` por `<=` en la frontera pone en rojo SS23. (4) Reintentar ante red pone en rojo SS24. (5) Refrescar ante un `401` pone en rojo SS25. Se revierten y se confirman con `cmp`.
  - [ ] 6.4 **Cierre.** Suites **U, T, PR**, guarda y AW10; T porque `admin-token-store-is-private` evalúa ya archivos reales. Commit: `feat(identity): keep the session in module memory with one refresh at a time`. Tamaño: 1 005 a 4x. **Corte predefinido:** 6a (almacén, coordinador, Web Locks; SS14 a SS16, SS18) / 6b (`AccessSession`, consulta; SS21 a SS26). — Fila 6.

## Fase 3: componentes y primera pantalla

- [ ] 7 **PR 7 `ui-alert-and-fixes`: `Alert`, W2 y W3 (D13).**
  - **Archivos.** Crea `packages/ui/src/molecules/alert/**`. Modifica `atoms/button/Button.tsx`, `molecules/{error-state,empty-state}/**`, `tokens/components.css`, `i18n/locales/es-HN/ui.json`, `index.ts`, la prueba de exportaciones y sus historias.
  - [ ] 7.1 **ROJO.** Tres canales por variante en dos temas [DS82]; roles [DS83]; texto por propiedades [DS84]; contenido obligatorio [DS85]; foco programático sin parada [DS86]; `Alert` en la puerta de historias [DS87]. W2: estilo calculado en Chromium sin propiedades de contorno ni `all` [DS90]. W3: etiqueta vacía y solo espacios, «Reintentar» [DS91]; sin `p` vacío [DS92]. Conservados como regresión: DS15 a DS19 y DS25 a DS28. **Rojo esperado:** `Cannot find module './Alert'`, y `transition-property` con `outline-color` en DS90.
  - [ ] 7.2 **VERDE y REFACTOR.** Molécula con `--alert-{info,warning,destructive}-{bg,fg,border}` hacia `--color-status-{pending,partial,overdue}-*`; `transition-[color,background-color,border-color]`; `retryLabel?.trim()`. La prueba de exportaciones sube a 20 nombres; DS02 fija los 21 en la tarea 8.
  - [ ] 7.3 **Demostraciones deliberadas.** (1) Volver a `transition-colors` pone en rojo DS90. (2) Quitar `trim` pone en rojo DS91. (3) Renderizar siempre el `p` pone en rojo DS92. (4) `role="status"` en `destructive` pone en rojo DS83. Se revierten y se confirman con `cmp`.
  - [ ] 7.4 **Cierre.** Suites **UI, PR**, guarda y AW10. Commits: `feat(ui): add the alert molecule`, `fix(ui): keep the focus ring out of the button transition and never render empty labels`. Tamaño: 520 a 4x; sin corte predefinido. — Fila 7.

- [ ] 8 **PR 8 `ui-form-error-summary`: `FormErrorSummary`, `controlId` de `FormField` y voz de `ui` (D13, D17).**
  - **Archivos.** Crea `packages/ui/src/molecules/form-error-summary/**`. Modifica `molecules/form-field/FormField.tsx` y su prueba, `ui.json`, `index.ts`, la prueba de exportaciones, la prueba de voz de `ui`.
  - [ ] 8.1 **ROJO.** Encabezado y enlaces [DS75]; singular [DS76]; foco sin parada [DS77]; foco sin navegar y URL intacta [DS78]; vacío [DS79]; sin control, texto [DS80]; orden [DS81]; `controlId` en control y etiqueta, con el resumen enlazando [DS98]; exportaciones exactas, 21 [DS01 a DS03, DS88, DS89]; voz de `ui` [DS93, DS94]; historias [DS95]. Conservados como regresión: DS57 a DS60 y DS62 a DS65. **Rojo esperado:** `Cannot find module './FormErrorSummary'` y `controlId` no existe en el tipo de `FormField`.
  - [ ] 8.2 **VERDE y REFACTOR.** `useSyncExternalStore` para saber qué controles existen, sin efecto con `setState`; `<a href="#id">` con `preventDefault` y `focus()`; plural ICU en `formErrorSummary.heading`.
  - [ ] 8.3 **Demostraciones deliberadas.** (1) Sin `preventDefault` pone en rojo DS78. (2) Una exportación más pone en rojo DS02. (3) Ignorar `controlId` en la etiqueta pone en rojo DS98. (4) «Revise» en `ui.json` pone en rojo DS93 nombrando la clave. Se revierten y se confirman con `cmp`.
  - [ ] 8.4 **Cierre.** Suites **UI, PR**, guarda y AW10. Commit: `feat(ui): add the form error summary and let form field take its control id`. Tamaño: 485 a 4x; sin corte predefinido. — Fila 8.

- [ ] 9 **PR 9 `boot-and-signed-in-page`: arranque, guardas, pantallas provisionales, página mínima y cierre de sesión (D5, D9).**
  - **Archivos.** Crea `src/app/BootGate.tsx`, `src/app/components/NotFound.tsx`, `src/features/identity/{routes.tsx,boot/**,signed-in/**,components/**}`, `src/features/identity/session-secrets.flow.test.tsx`, y los componentes provisionales de inicio de sesión, verificación e inscripción. Modifica `src/app/{App,router}.tsx`, `admin.json`, `tooling/src/*.test.ts` (BX46, BX61 y BX62 dejan de ser `todo`).
  - [ ] 9.1 **ROJO.** Jsdom con MSW: sin cookie, cero refrescos en cada ruta [SS07], vacía [SS08]; con cookie, uno [SS09]; en `/iniciar-sesion` [SS10]; rechazado, texto exacto y vacío [SS11]; red, sin reintento a los 60 s [SS12]; reintento, una más [SS13]. Guardas [SS27, SS30, AW19]; título, `h1`, foco [SS32]; `lang` [SS33]. Arranque [SS34 a SS36]. No encontrada [AW18]. Página mínima sin centinelas [SS103], carga [SS104], red [SS105], `401` [SS106]; cierre [SS107], recarga [SS108], fallo [SS109], tres clics [SS110], vencida [SS111, SS112]. `session-secrets.flow.test.tsx`: monta `App` con centinelas y serializa las dos cachés [SS06]. Presentación sin proveedor [AW57], estados declarados [AW58]; rutas exactas [AW59]; sin shell, portal ni nonce [AW60, AW61, AW63]; reglas sobre archivos reales [BX46, BX61, BX62]; repite la cláusula de pantalla de SS18, SS25 y SS26. **Rojo esperado:** `Cannot find module './BootGate'` y rutas no definidas.
  - [ ] 9.2 **VERDE y REFACTOR.** `BootGate` por encima de `RouterProvider`; sin cookie, enrutador en el primer render; `beforeLoad` de D5, con `discardSecondFactor` en la raíz; `ScreenLayout`; `useMutation` solo para `signOut`; `queryClient.clear()` al terminar la sesión; `SignedInContainer` traduce el fallo con `shared/api/problem-keys`.
    **Pantallas provisionales.** `/iniciar-sesion` muestra `ScreenLayout`, un `h1` y el aviso de llegada, sin formulario ni operación de credenciales. Verificación e inscripción tienen un componente provisional detrás de su guarda. La parte 2 los reemplaza. Ninguna expone un campo.
  - [ ] 9.3 **Demostraciones deliberadas.** (1) Refrescar sin comprobar la cookie pone en rojo SS07. (2) Declarar cerrada la sesión sin `204` pone en rojo SS109. (3) Guardar el token en los datos de la consulta pone en rojo SS06. (4) Quitar el foco del `h1` pone en rojo SS32. (5) Un selector de presentación sin archivos reales pone en rojo BX62. Se revierten y se confirman con `cmp`.
  - [ ] 9.4 **Cierre.** Suites **U, T, PR**, guarda y AW10. Commits: `feat(identity): restore the session on boot and guard the access routes`, `feat(identity): add the signed in page with sign out`. Tamaño: 980 a 4x, más unas 25 líneas de pantallas provisionales que el diseño no estimó. **Corte predefinido:** 9a (`BootGate`, rutas, guardas, provisionales, no encontrada; SS07 a SS13, SS27, SS30, SS32 a SS36, AW18, AW19, AW59 a AW61, AW63) / 9b (página mínima, cierre, flujo; SS06, SS103 a SS112, AW57, AW58, BX46, BX61, BX62). — Fila 9.

## Fase 4: política, simulador, puerta y CI

- [ ] 10 **PR 10 `a11y-policy-move-and-adr`: política hacia `@confia/config` y ADR-0028 (D14).**
  - **Archivos.** Mueve con `git mv` `packages/ui/src/a11y/{policy.ts,policy.test.ts}` a `packages/config/{a11y.ts,a11y.test.ts}`. Modifica `packages/config/package.json` (exportación `./a11y`, descripción y `axe-core` solo de tipo), `packages/ui/src/a11y/run-gate.ts`, `.storybook/preview.tsx`, `docs/adr/README.md`, `docs/adr/ADR-0026-*.md` (solo nota), `CLAUDE.md`, `docs/01-arquitectura.md`. Crea `docs/adr/ADR-0028-*.md`.
  - [ ] 10.1 **ROJO.** `a11y.test.ts` importado de `@confia/config/a11y`: etiquetas [BX66], reparto [BX67], desconocido [BX68], incompleto [BX69], solo tipo [BX70]. `ui` sin copia e importa de `config` [BX71, DS96]; punto de entrada sin la política [BX72]; `gate.a11y.test.tsx` sin cambios [DS97]. Conservados como regresión: DS66 a DS71. ADR Propuesto e indexado [BX100], ADR-0026 solo con nota [BX101], `CLAUDE.md` y `docs/01` [BX106], exportación [BX107]. **Rojo esperado:** `Cannot find module '@confia/config/a11y'`.
  - [ ] 10.2 **VERDE y REFACTOR.** Traslado sin cambiar comportamiento; `run-gate.ts` y `preview.tsx` cambian una importación. ADR-0028 registra la matriz fija y la falla con cero rutas, y deja fuera que el simulador pruebe la pila real. **Este PR no marca el criterio 8.**
  - [ ] 10.3 **Demostraciones deliberadas.** (1) `moderate` bloqueante pone en rojo BX67. (2) Dejar una copia en `packages/ui/src/a11y/policy.ts` pone en rojo BX71 y DS96. (3) Reexportarla desde `ui` pone en rojo BX72. Se revierten y se confirman con `cmp`.
  - [ ] 10.4 **Cierre.** Suites **C, UI, T, PR**, guarda y AW10. Commits: `refactor(config): move the accessibility policy to the shared config`, `docs(adr): propose ADR-0028 on axe over rendered routes`. Tamaño: 270 a 4x, con el traslado medido como renombre (`-M`); sin corte predefinido. — Fila 10.

- [ ] 11 **PR 11a `simulated-api`: simulador Node, modelo de sesión y TOTP de prueba (D15).**
  - **Archivos.** Crea `apps/admin-web/e2e/mock-api/{start.mjs,server.ts,scenarios.ts,session-model.ts}`, `e2e/support/{totp,sentinels}.ts` y sus pruebas. Modifica `apps/admin-web/package.json` (`mock:api`), `packages/config/vitest.js` (solo el comentario sobre `*.spec.ts`), `contract-responses.ts` si hace falta `omit({ instance: true })`.
  - [ ] 11.1 **ROJO.** Secreto fuera del patrón lanza en prueba unitaria [SS118]; error validado sin `instance`, emitido relativo [SS118, I306]; violación en ejecución queda en `/__control/violations` [I306]. RFC 6238 con «12345678901234567890» a los 59 s da «287082», solo con `node:crypto` [SS119]. Rotado hace 5 s se acepta y hace 30 s recibe `401` [SS120]. `mfa-enroll` con `recoveryCode`, `401 second-factor-invalid` [SS121]. **Primera prueba:** que `runnerImport` no externalice un paquete enlazado; la salida escrita es arrancar en `globalSetup` de Playwright. **Rojo esperado:** `Cannot find module './session-model'` y `./totp`.
  - **Adición aprobada el 2026-11-05: sesión de prueba sembrada.** Para ver la página mínima de la tarea 9 sin formulario de inicio de sesión, el simulador expone un control solo local en `/__control/*` que siembra una sesión de prueba: deja la cookie de refresco con los mismos atributos que el resto del simulador y una cuenta ficticia. No existe fuera del simulador, no se sirve desde el build de producción, no usa datos reales y nada lo presenta como prueba de la pila real. Lleva su prueba en rojo antes del código, y una demostración deliberada en la que el control responde fuera de `localhost` y pone esa prueba en rojo.
  - [ ] 11.2 **VERDE y REFACTOR.** Puerto 8099, solo `localhost`; escenario por `x-confia-mock-scenario`; control en `/__control/*`; familia, rotación, gracia de 10 s, `Origin` permitido, doble envío CSRF y cookies con los atributos de WE62 y WE63. Los recorridos que usa son simulados y nada los presenta como prueba de la pila real.
  - [ ] 11.3 **Demostraciones deliberadas.** (1) Gracia de 30 s pone en rojo SS120. (2) Enviar sin validar pone en rojo SS118. (3) Aceptar recuperación con `mfa-enroll` pone en rojo SS121. (4) SHA-256 pone en rojo SS119. Se revierten y se confirman con `cmp`.
  - [ ] 11.4 **Cierre.** Suites **U, C, T, UI, PR** (la C, T y UI porque se toca `packages/config`), guarda y AW10. Commit: `test(admin-web): add the simulated api with its session model`. Tamaño: 1 420 a 4x. **Corte predefinido:** 11a-1 (modelo, TOTP, validación; SS119 a SS121) / 11a-2 (servidor, escenarios, control, centinelas; SS118, I306). Con 1 420, si una parte sigue sobre 800 aplica la regla de detención. — Fila 11.

- [ ] 12 **PR 11b `route-gate-and-ci`: Playwright, puerta sobre rutas, recorridos de arranque, CI y turbo (D15, D16, D18).**
  - **Archivos.** Crea `apps/admin-web/playwright.config.ts`, `e2e/support/{fixtures,a11y-gate,a11y-matrix}.ts`, `e2e/fixtures/a11y/{inaccessible,dark-only-contrast,moderate-only,stuck-loading}.html`, `e2e/specs/{a11y,build,access/boot-and-signed-in}.e2e.ts`. Modifica `.github/workflows/ci.yml` (solo agrega), `turbo.json` (`test:e2e`), `tooling/src/ci-workflow.test.ts` (bloque nuevo y línea 70 a `slice(0, 5)`; BX19 no se toca), `apps/admin-web/package.json`.
  - [ ] 12.1 **ROJO, *fixtures* antes que las rutas reales.** Primero, aislamiento por cabecera con `fullyParallel`; la salida escrita es `workers: 1` (R7). Sin `--pass-with-no-tests`. Matriz de 6 estados por 2 temas [BX74]; violación seria nombra ruta, estado, tema, regla e impacto [BX75]; `inaccessible.html` [BX76]; matriz vacía [BX77]; `stuck-loading.html` falla por el contenido esperado [BX78]; `dark-only-contrast.html` [BX79]; `moderate-only.html` [BX80]; misma política que las historias [BX73]. Rutas reales [BX81], local [BX82]. `ci-workflow.test.ts`: diff aditivo, `frontend` igual y BX19 sin tocar, trabajo de 20 minutos, SHA, `if: always()` del informe, sin Docker ni JDK, `design-system-accessibility` igual [BX83 a BX88, BX90]. `specs/build`: `dist` sin marcador de *fixture*, MSW, `@playwright/test` ni axe, y HTML sin código en línea [AW13 a AW15, BX95]. Recorridos, con `addCookies` para el estado inicial: arranque sin errores [AW02]; tokens y textos [AW03]; tema [AW05, AW06]; sin violaciones de CSP [AW12]; dos pestañas [SS17]; 60 minutos con `page.clock` [SS19]; foco y `setOffline` [SS20]; ausencias [SS124 a SS126]. SS122 comprueba la sección 2 del diseño. **Rojo esperado:** `No tests found` hasta crear las pruebas, y luego cero combinaciones en `assertCoversMinimum`.
  - [ ] 12.2 **VERDE y REFACTOR.** `checkCombination` de D15; `webServer` con `vite build && vite preview` y `reuseExistingServer: !CI`; informes en `reports/a11y/report.json` y `reports/e2e/`; trabajo `admin-web e2e and accessibility` y artefacto `admin-web-e2e-report`; `test:e2e` con `cache: false`, `dependsOn: ["^generate", "generate"]`, `outputs: ["reports/**"]`, `passThroughEnv: ["CI"]`. Los fallos de red de las pruebas usan `page.route` con `abort`, porque el proxy convierte un corte en `500`.
  - [ ] 12.3 **Demostraciones deliberadas.** (1) Hacer que `checkCombination` no reparta las violaciones con la política pone en rojo BX76 y BX79. (2) Quitar la espera del contenido esperado pone en rojo BX78. (3) Vaciar la matriz pone en rojo BX77. (4) Quitar `if: always()` pone en rojo BX85. (5) Un `<style>` en línea servido con la cabecera dispara `securitypolicyviolation`: el detector funciona [AW12]. (6) `refetchOnWindowFocus: true` pone en rojo SS20. (7) Un `setInterval` de refresco pone en rojo SS19. Se revierten y se confirman con `cmp`.
  - [ ] 12.4 **Evidencia remota [BX86].** Un informe subido desde una ejecución en rojo exige empujar: la ejecución la autoriza el propietario. Sin ella, queda registrada como pendiente, sin marcar el escenario como observado. Con ella, el límite de 20 minutos se ajusta con la medida.
  - [ ] 12.5 **Cierre.** Suites **E, U, T, PR**, guarda y AW10. **`docs/09-roadmap-y-fases.md` sin cambios en toda la parte 1** (`git diff --name-only main... -- docs/09-roadmap-y-fases.md` vacío): el criterio 8 sigue abierto. Commits: `test(admin-web): run axe over rendered routes with its deliberate failures`, `ci: add the admin web e2e and accessibility job`, `test(admin-web): cover boot and the signed in page in the browser`. Tamaño: 1 080 a 4x. **Corte predefinido:** 11b-1 (Playwright, puerta, matriz, *fixtures*, turbo y CI; BX73 a BX90, AW13 a AW15, BX95) / 11b-2 (recorridos; AW02, AW03, AW05, AW06, AW12, SS17, SS19, SS20, SS122, SS124 a SS126). — Fila 12.
  - [ ] 12.6 **Verificación final de la parte (propietario).** Después de fusionar la cadena: lista de «Definición de terminado» de `CLAUDE.md` marcada en lo aplicable, con los estados de carga, vacío y error de la página mínima y del arranque; todas las demostraciones con su `cmp` registrado. Después, `sdd-verify` y `sdd-archive`, antes de archivar la parte 2.

---

## Trazabilidad: escenario a tarea (cada uno en exactamente una)

Los tres conservados sin identificador de `identity` son «La inscripción devuelve el secreto en base32 una única vez», «Un código válido dentro de la ventana de tolerancia se acepta» y «Un código ya aceptado no puede reutilizarse, aunque siga siendo matemáticamente válido». «Conservado» significa regresión ya existente, que se mantiene en verde.

| Tarea (PR) | Escenarios | Prueba principal | N |
|---|---|---|---|
| 1 (PR 1) | AW04; BX38, BX39, BX40, BX41, BX44, BX45, BX47 a BX53, BX55, BX56, BX58, BX59, BX60, BX63, BX64, BX110 | `eslint-app-rules.test.ts`, `dependency-rules.test.ts`, `suppression-citations.test.ts` | 22 |
| 2 (PR 2) | AW01, AW07, AW08, AW10, AW11, AW16, AW17, AW62; BX65, BX89, BX91 a BX94, BX96 a BX99 | `vite.config.test.ts`, `workspace-guards.test.ts` | 18 |
| 3 (PR 3) | AW23 a AW32; BX42, BX43 | `voice.test.ts`, `text-keys`, `query-client.test.ts` | 12 |
| 4 (PR 4) | AW40, AW43 a AW48, AW50, AW52, AW53, AW55, AW56; BX54, BX57 | `problem.test.ts`, `http.test.ts`, `csrf-cookie.test.ts` | 14 |
| 5 (PR 5) | AW20, AW21, AW22, AW33 a AW39, AW41, AW42; I310; conservados: los tres de TOTP | `auth-operations.test.ts` | 16 |
| 6 (PR 6) | SS14, SS15, SS16, SS18, SS21 a SS26 | `refresh-coordinator.test.ts`, `access-session.test.ts` | 10 |
| 7 (PR 7) | DS82 a DS87, DS90, DS91, DS92; conservados: DS15 a DS19, DS25 a DS28 | `Alert.test.tsx`, `Button` en Chromium, `ErrorState.test.tsx` | 18 |
| 8 (PR 8) | DS01, DS02, DS03, DS75 a DS81, DS88, DS89, DS93, DS94, DS95, DS98; conservados: DS57 a DS60, DS62 a DS65 | `FormErrorSummary.test.tsx`, `FormField.test.tsx`, prueba de exportaciones y de voz | 24 |
| 9 (PR 9) | SS06, SS07 a SS13, SS27, SS30, SS32 a SS36, SS103 a SS112; AW18, AW19, AW57 a AW61, AW63; BX46, BX61, BX62 | `BootGate.test.tsx`, `routes.test.tsx`, `SignedIn*.test.tsx`, `session-secrets.flow.test.tsx` | 36 |
| 10 (PR 10) | BX66 a BX72, BX100, BX101, BX106, BX107; DS96, DS97; conservados: DS66 a DS71 | `a11y.test.ts`, `gate.a11y.test.tsx` | 19 |
| 11 (PR 11a) | SS118 a SS121; I306 | `session-model.test.ts`, `totp.test.ts`, `server.test.ts` | 5 |
| 12 (PR 11b) | BX73, BX74 a BX88, BX90, BX95; AW02, AW03, AW05, AW06, AW12 a AW15; SS17, SS19, SS20, SS122, SS124, SS125, SS126 | `specs/a11y`, `specs/build`, `specs/access/boot-and-signed-in`, `ci-workflow.test.ts` | 33 |
| | **Total** (AW 59, BX 67, DS 50, SS 46, I 5) | | **227** |

**Escenarios sin tarea:** ninguno. Los otros 98 viven en `openspec/changes/admin-web-staff-sign-in/specs/`.

## Contradicciones y observaciones

1. **La parte 1 no tiene pantalla de inicio de sesión, pero diez de sus escenarios terminan en ella:** SS07, SS11, SS25, SS26, SS27, SS106, SS107, SS108, SS111 y SS112. El diseño no la previó, porque el PR 12 venía justo después. Se resolvió con una **pantalla provisional sin formulario** (propósito de `staff-sign-in`, tarea 9.2). Son unas 25 líneas no estimadas. **El propietario puede revisarlo.**
2. **AW09 pasó a la parte 2, contra el diseño §10.** Exige «un recorrido completo de inicio de sesión», que no existe en el PR 11b. AW12 y los demás recorridos sí se quedan.
3. **AW41 se partió.** Su cláusula «la pantalla muestra el mensaje genérico» es sobre `LoginResponse` y necesita el formulario, así que el resultado del cliente se queda en la parte 1 y la pantalla pasa al escenario nuevo **AW64** de la parte 2.
4. **La matriz de BX74 se partió.** La tabla pedía 22 estados, que la parte 1 no puede cumplir. La parte 1 conserva arranque, página autenticada y no encontrada (6 estados, 12 evaluaciones), y el escenario nuevo **BX109** exige los 22 en la parte 2.
5. **Escenario nuevo BX110.** El diseño prohíbe `Worker`, `SharedWorker` y `sendBeacon`, y el requisito exige una violación por interfaz, sin escenario que la pidiera.
6. **Hogares distintos de la tabla del diseño §10,** todos porque el escenario pide algo real que aún no existe, o porque el diseño lo lista dos veces:
   - BX57 y BX54 pasan de los PR 2 y 1 a la **tarea 4**: piden archivos reales de `src/shared/api`, que nace en el PR 4.
   - BX61 pasa del PR 1 a la **tarea 9**: pide un contenedor real.
   - BX73 pasa del PR 10 a la **tarea 12**: la puerta sobre rutas no existe antes.
   - SS06 pasa del PR 6 a la **tarea 9**: el cambio 2 de §13 exige la aplicación completa, y el propio §10 lo pone en el flujo de `App`.
   - BX42 y BX43 figuran en los PR 1 y 3: se asignan a la **tarea 3**, donde existe `AdminKey`.
   - BX88 figura en los PR 2 y 11b: se asigna a la **tarea 12**, porque exige `test:e2e`.
   - AW37 figura en los PR 5 y 6: se asigna a la **tarea 5**.
   - SS59 figura en los PR 6 y 12: su requisito completo es de pantalla y se asigna a la **parte 2**. La tarea 6 deja la prueba del almacén.
7. **Escenarios conservados sin fila en §10.** DS15 a DS19, DS25 a DS28, DS57 a DS60, DS62 a DS65 y DS66 a DS71, más los tres de TOTP, se asignan a la tarea que toca su requisito.
8. **Requisitos con un solo escenario.** `openspec/changes/README.md` pide al menos dos. Quedan en uno, en esta parte, «Los tokens...» (SS06, que la parte 2 completa) y el requisito agregado de `identity` para I306. Ese requisito nuevo existe porque la afirmación de que no hay prueba de extremo a extremo del inicio de sesión sigue siendo cierta al terminar la parte 1, y por eso su reemplazo es de la parte 2.
9. **Fusión de la parte 2.** Sus `MODIFIED` traen solo los escenarios que agregan, para que ningún escenario viva en dos cambios. Es distinto de los deltas anteriores, que repetían los conservados: el archivado de la parte 2 debe sumar, no reemplazar. Lo dice la cabecera de cada delta.
10. **`design.md` no se editó,** porque este paso no lo pedía: conserva el nombre antiguo, «pendiente de aprobación» y la frase «Ninguno se editó» de §13. SS72 está en el rango del PR 13 de §10 aunque el primer código llega en el PR 15: lo resuelve el `tasks.md` de la parte 2.
11. **`docs/09` queda desactualizado entre las dos partes.** Sigue nombrando al antiguo 13b hasta la nota de la parte 2 (BX104). El criterio sigue abierto, así que no afirma nada falso.
12. **`openspec/config.yaml` está desactualizado.** Dice que las aplicaciones de frontend no existen. Sin efecto en este desglose; no se toca.
13. **BX89 y BX86 dependen de una ejecución remota,** y empujar es decisión del propietario. Las tareas 2.4 y 12.4 lo dejan como pendientes visibles.
14. **BX92 y BX94** se verifican en el PR 2 sin `qrcode.react` ni `jsqr`, que llegan en el PR 14. La guarda recorre lo declarado, y la parte 2 los hace entrar bajo la misma prueba.
15. **El corte de la tarea 4 superaría el rango de 12 a 17 PR** si llegara a usarse (18). Su estimación es de 790 a 4x.
16. **Base de la cadena.** La rama lleva 26 commits frente a `main` local (`9154c45`). La tarea 1 confirma con `git log` si ya están en el `main` remoto; los que sean de `openspec/` no cuentan en la medición.
