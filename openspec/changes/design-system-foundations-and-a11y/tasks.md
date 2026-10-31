# Tareas: fundamentos del sistema de diseño y verificación de accesibilidad (cambio 13a de F0)

- **Cambio:** `design-system-foundations-and-a11y` (F0, cambio 13a; entregable 10, criterio de salida 8 queda abierto y asignado a 13b)
- **Fase:** tareas
- **Fecha:** 2026-10-28
- **Estado:** pendiente de aprobación del propietario
- **Entradas aprobadas:** `proposal.md` (aprobada el 2026-10-28), `design.md` (decisiones 1 a 13, sondas S1 a S5 de la sección 7, división de la
  sección 8) y los deltas `specs/design-system/spec.md` y `specs/build-integrity/spec.md`. Los escenarios no traen identificador en el delta; este
  documento les asigna `DS01` a `DS74` (capacidad `design-system`) y `BX01` a `BX37` (capacidad `build-integrity`, en el orden en que aparecen,
  incluidos los tres escenarios previos del requisito modificado). El catálogo completo está en el Apéndice A.
- **Decisiones del propietario (2026-10-28) que este documento recoge:** seis PR encadenados apilados contra `main`, de 450 a 750 líneas cada uno;
  se conservan `Checkbox`, `RadioGroup` y `Textarea`; las sondas S1 a S5 van primero y detienen el cambio si fallan; el ADR es el 0026 (el 0025 es de
  `background-jobs-with-db-scheduler`); `ci.yml` solo cambia de forma aditiva; `apps/portal-web` queda fuera; el frontend corre en paralelo con la
  cadena del backend, así que no se toca `apps/api` y se minimizan los conflictos en `pnpm-lock.yaml` y `ci.yml`.
- **Modo de TDD:** estricto (`openspec/config.yaml`, `strict_tdd: true`). El ejecutor de este cambio no es Maven: es Vitest con pnpm, desde la raíz del
  worktree `C:\dev\confia-frontend`. Los PR se arman desde ese worktree; no se toca `C:\dev\confia`.
- **Línea base de la cadena:** `main` actualizado al abrir cada rama (el PR 1 sale del HEAD de la rama de artefactos
  `change/design-system-foundations-and-a11y`, que conserva `proposal.md`, `design.md`, los deltas y este archivo sin confirmar; se confirman en el
  primer commit del PR 1 como `docs(openspec): add the design-system-foundations-and-a11y change artifacts`).

## Review Workload Forecast

| Field | Value |
|-------|-------|
| Estimated changed lines (additions + deletions, sin `openspec/`, sin `pnpm-lock.yaml` y sin código generado) | **nominal unas 3 950 (diseño §8); realista entre 4 300 y 4 600** (cada componente con historia y prueba mide de 60 a 260; el código adaptado de shadcn/ui cuenta como línea de autor) |
| 400-line budget risk | **High** frente a las 400 líneas de la preflight; **medio a alto por PR** frente al presupuesto del proyecto de 800 y la banda del propietario de 450 a 750 |
| Chained PRs recommended | Yes |
| Suggested split | **10 PR apilados a `main`** (13a-1, 13a-1b, 13a-2a, 13a-2b, 13a-3a, 13a-3b, 13a-3c y 13a-4 a 13a-6), 15 tareas en 6 fases. Particiones autorizadas por el propietario: el séptimo PR (13a-1b) el 2026-10-29 al partir 1.3; el octavo (13a-2b) el 2026-10-30, porque 2.1 sola midió 761 líneas; el noveno (13a-3b) el 2026-10-30, porque 3.1 sola midió 538; y el décimo (13a-3c, tarea 3.3) el 2026-10-30, aceptando 13a-3b en 811 líneas efectivas como excepción de tamaño, porque las 132 líneas sobre 3.2 son correcciones de la revisión de 13a-3a |
| Delivery strategy | `auto-chain` con la estrategia fijada por el propietario el 2026-10-28 |
| Chain strategy | `stacked-to-main`: cada PR se fusiona a `main` en orden y el siguiente parte de `main` actualizado |
| Presupuesto por PR | Banda del propietario 450 a 750; límite duro del proyecto 800 de cambio efectivo (`docs/15-flujo-de-trabajo-git.md` §3). Las pruebas y la documentación nunca se separan de su código |

Decision needed before apply: No (el propietario fijó la cadena apilada de seis PR el 2026-10-28; la única decisión pendiente es la contingencia
de la Pregunta abierta 1 y solo se activa si la medición real la dispara)
Chained PRs recommended: Yes
Chain strategy: stacked-to-main
400-line budget risk: High

**Cuenta de tareas.** 15 tareas, exactamente el límite de `openspec/changes/README.md` («quince es el límite, no el objetivo»). No hay margen: **no se
puede añadir ninguna tarea sin partir el cambio**. Cualquier trabajo nuevo que surja en aplicar entra dentro de una tarea existente o se propone como
cambio aparte.

**Regla de partición.** Se mide cada PR con `git diff --numstat <base>...HEAD -- . ':!openspec' ':!pnpm-lock.yaml'` y se registra en
`apply-progress.md`. Si un PR supera **800**, el ejecutor lo parte por archivos completos en la costura nombrada de su fila, sin consultar, y deja una
nota fechada con la medida. Entre 750 y 800 se avisa y se continúa (límite del proyecto respetado). Solo se consulta al propietario si ninguna costura
resuelve el exceso o si la proyección total supera el umbral de la Pregunta abierta 1.

### Pronóstico y costuras por PR (líneas efectivas, sin `openspec/` ni archivo de bloqueo)

| PR (corte del diseño) | Tareas | Nominal (diseño) | Realista | Costura nombrada si supera 800 |
|---|---|---|---|---|
| **PR 1** `01-package-and-tokens` (13a-1) | 1.1, 1.2, 1.3 (parte A) | 700 | **738 medidas** | Partido el 2026-10-29: 13a-1 conserva 1.1, 1.2 y de 1.3 los archivos CSS, `tokens.build.test.ts` y el módulo de utilidades mínimo |
| **PR 1b** `01b-token-parity-and-contrast` (13a-1b) | 1.3 (parte B) | — | **470 medidas** | Pruebas de paridad, contraste, cadena, recorrido y fuentes con sus fixtures y las utilidades de lectura que solo ellas usan |
| **PR 2** `02-texts-and-static-analysis` (13a-2) | 2.1, 2.2 | 650 | 650 a 720 | **2a** 2.1 (catálogo y `formatMoney`) / **2b** 2.2 (reglas de ESLint y escáner de supresiones) |
| **PR 3** `03-storybook-and-axe-gate` (13a-3) | 3.1, 3.2, 3.3 | 700 | **750 a 810** | **3a** 3.1 (Storybook, política, `Spinner`) / **3b** 3.2 (puerta en Chromium y comprobación de historias) / **3c** 3.3 (CI, ADR-0026, documentación del stack; la parte documental, ~125 líneas, es la primera en moverse al inicio del PR 4) |
| **PR 4** `04-atoms-buttons-labels` (13a-4) | 4.1, 4.2 | 450 | 700 a 760 (4a **553 medidas**) | Costura movida el 2026-10-31 por aprobación del propietario (4.1 solo medía 836 líneas): **4a** 4.1 parte A (`Button`, `IconButton`, `focus-ring`, variables `--button-*`) / **4b** 4.1 parte B (`Label`, `Badge`, variables `--badge-*`; **289 medidas**) y 4.2 (`Separator`, `Skeleton`, `Input`) |
| **PR 5** `05-controls-form-and-states` (13a-5) | 5.1, 5.2 | 700 | **750 a 820** | **5a** 5.1 (`Textarea`, `Checkbox`, `RadioGroup`) / **5b** 5.2 (`FormField`, `EmptyState`, `ErrorState`); si 5b sola excede, `ErrorState` pasa al inicio del PR 6 |
| **PR 6** `06-money-status-and-layers` (13a-6) | 6.1, 6.2, 6.3 | 750 | 680 a 760 | **6a** 6.1 (`MoneyAmount`) / **6b** 6.2 y 6.3 (`StatusBadge`, reglas de capas, inventario, notas) |
| **Total** | 15 tareas | unas 3 950 | **4 300 a 4 600** | |

Los cortes 13a-3 y 13a-5 son los que proyectan por encima de 750. Se declaran aquí para que nadie los descubra al medir. Si la medición real de los
PR 3 y 4 proyecta un total por encima de 4 600, aplica la Pregunta abierta 1.

**Orden duro.**

1. **La tarea 1.1 (sondas) antes de cualquier otra.** Ninguna línea de `packages/ui` se escribe sin el resultado de S1 a S5. Si una sonda falla, el
   cambio **se detiene** y se informa al propietario (compuerta de sondas, más abajo).
2. **Cadena lineal de PR.** PR n+1 sale de `main` después de que PR n se fusionó. Dentro de un PR, las tareas son secuenciales.
3. **La puerta de axe (3.2) antes de cualquier componente real con historia propia más allá de `Spinner`.** Los componentes de las fases 4 a 6
   nacen con la puerta ya activa; ninguno se acepta con `a11y: { disable` ni `test: "off"` sin cita a un ADR.
4. **Las reglas de capas de dependency-cruiser (6.3) llegan en el corte que crea el primer componente de dominio**, de modo que cada regla pasa sobre
   un conjunto real y no hace falta ninguna excepción temporal (design §3, decisión 10). Mientras tanto, el espejo de ESLint (2.2) protege las capas.
5. **ADR-0026 se reconcilia contra `main` antes de fusionar el PR 3** (`git ls-tree origin/main docs/adr/`): si `0026` ya existe, se toma el número
   libre siguiente y se corrigen las referencias.

**Compuerta de sondas (tarea 1.1).** Cinco preguntas, todas antes de la primera línea de código:

| Sonda | Pregunta | Criterio de éxito | Si falla |
|---|---|---|---|
| **S1** | Versiones exactas (Storybook y su complemento de accesibilidad, i18next, shadcn/ui, `eslint-plugin-react-hooks`, Radix, Tailwind) frente a React 19, la Vite de Vitest 5.0.2, TypeScript 6.0.3 y ESLint 10. context7 no estuvo disponible en el diseño: se obtienen de los metadatos del registro (`pnpm view`) y de la documentación primaria | `pnpm install --frozen-lockfile` limpio, sin `overrides` de pares, y una historia mínima compila con `storybook build` | **Detener.** `react-hooks` puede quedar pendiente sin detener |
| **S2** | Tailwind v4 bajo `@theme static` con `--*: initial`: ¿emite `--palette-*` sin utilidades, y borra el tema predeterminado? | `bg-primary` genera regla; `bg-palette-primary-600` y `bg-red-500` no | **Detener.** Opción que se presentaría: primitivos en `:root` fuera de `@theme` (respaldo del diseño, decisión 3) |
| **S3** | `Intl.NumberFormat.format` con cadena decimal, en Node 24 y en Chromium, y su tipo en TypeScript 6.0 con `lib` ES2023 | `"12345678901234567.89"` sin pérdida en ambos motores; salida de `es-HN` para HNL y USD registrada; `signDisplay` para el cero negativo registrado | **Detener.** No hay formato aceptable que convierta a `number` |
| **S4** | Guiones de instalación bajo `strictDepBuilds` y `pnpm audit --audit-level high` con el árbol nuevo | Cada petición resuelta en `allowBuilds` con su motivo; auditoría sin altas | **Detener.** Se presentan `overrides` documentados uno por uno; la auditoría nunca se desactiva |
| **S5** | Historias portables en el modo navegador de Vitest 5.0.2 con Chromium y TypeScript 6.0; CSS real cargado; axe decide el contraste; ¿se emula `prefers-color-scheme`? | La historia inaccesible produce `color-contrast`, `image-alt` y `button-name`; se registra si la emulación es posible; `storybook build` pasa | **Detener.** Se presentaría, solo con aprobación, la puerta en jsdom con `color-contrast` declarada no cubierta |

Los respaldos de S2, S4 y S5 son **propuestas para el propietario**, no se adoptan en silencio (propuesta, Enfoque 1; design §3, decisión 8). Las
sondas S3 y S5 se ejecutan al principio en un directorio de trabajo fuera del repositorio; su confirmación dentro del repositorio llega con el ROJO
de 2.1 y de 3.2.

### Reglas comunes a todas las tareas

- **Comandos** (todos desde `C:\dev\confia-frontend`, con Node de `.nvmrc` y pnpm de `packageManager`):
  - Unitario focalizado: `pnpm --filter @confia/ui exec vitest run --project unit <ruta>`.
  - Herramental focalizado: `pnpm --filter @confia/tooling exec vitest run src/<archivo>`.
  - Puerta en navegador (desde 3.2): `pnpm --filter @confia/ui test:a11y`, tras `pnpm --filter @confia/ui exec playwright install chromium`.
  - Cierre de cada PR: `pnpm install --frozen-lockfile` y `pnpm turbo run lint typecheck test build --filter=!@confia/api` en verde, más
    `pnpm audit --audit-level high` y, desde el PR 3, `pnpm --filter @confia/ui test:a11y`. Las órdenes focalizadas son solo para iterar.
- **Rojo registrado.** Cada paso en rojo corre contra el árbol sin el código de producción y se registra en
  `openspec/changes/design-system-foundations-and-a11y/apply-progress.md` con la causa **observada** (mensaje de la aserción o del compilador). Si falla
  por otra causa, se detiene y se investiga. Un commit en rojo no entra al historial: las pruebas en rojo se comprometen con su verde. Después del
  verde, un paso de **REFACTOR** deja la suite en verde antes de comprometer (nombres, duplicación), sin cambiar comportamiento.
- **Control negativo y demostración deliberada.** Cada tarea nombra una ruptura temporal de producción que debe poner en rojo una prueba concreta
  **por una aserción de resultado**, nunca por tiempo de espera. Procedimiento fijo: (1) copiar el archivo fuera del repositorio, en el directorio
  temporal de la sesión; (2) aplicar la ruptura y observar el rojo por la aserción nombrada; (3) restaurar desde la copia; (4) **confirmar con `cmp`**
  (sin salida) y con `git diff --exit-code -- <archivo>` que el archivo quedó idéntico; (5) registrar en `apply-progress.md` la ruptura, la aserción
  que falló y el resultado de `cmp`. Una demostración no revertida y confirmada bloquea el cierre de la tarea.
- **Violación deliberada permanente.** Toda regla nueva tiene su fixture o su código en línea que la rechaza, y su caso aceptado, y **ninguna pasa
  sobre un conjunto vacío** (ADR-0018): la prueba afirma `archivos evaluados > 0` sobre el árbol real.
- **Supresiones.** Prohibido `eslint-disable`, `@ts-expect-error`, `@ts-ignore`, `a11y: { disable` y `test: "off"` sin cita `ADR-NNNN` en la misma línea o
  la anterior (ADR-0018 §1.5 y §4). Las violaciones deliberadas de tipos citan `ADR-0018`; las de claves de catálogo, `ADR-0011`.
- **Adaptación de shadcn/ui (tareas 3.1, 4.x, 5.x).** El CLI no se ejecuta dentro del repositorio ni se declara como dependencia. Cada componente se
  genera en un directorio temporal fuera del repositorio con la versión fijada en S1; se copia y se **adapta**: clases a los semánticos de CONFIA
  (`text-primary-fg`, no `text-primary-foreground`), estados de `docs/ui-ux/02` §6, textos por props o por el catálogo `ui`. Cabecera de cada archivo:
  componente y versión de origen. Nunca se consume un primitivo ni se escribe un color literal.
- **Estructura por componente.** `src/<atoms|molecules|domain>/<nombre>/{<Nombre>.tsx,<Nombre>.stories.tsx,<Nombre>.test.tsx,index.ts}`; el ROJO de
  cada componente es su `.test.tsx` más su historia `Default`, y la historia exige los estados aplicables (carga, vacío, error, deshabilitado)
  (`docs/ui-ux/02` §8). Las pruebas consultan por rol, nombre accesible o texto visible; nunca por clase ni por estructura del DOM.
- **Dependencias y archivo de bloqueo.** Cada dependencia entra con su versión exacta en el `catalog:` de `pnpm-workspace.yaml` (S1) y en el PR de la
  tarea que la usa por primera vez, nunca antes. Al rebasar sobre `main`: `git checkout main -- pnpm-lock.yaml` y `pnpm install` regenera; **nunca se
  fusiona a mano**. `allowBuilds` solo recibe lo que S4 demuestre, cada entrada con su motivo en el PR. El archivo de bloqueo no cuenta como línea de
  autor.
- **`ci.yml` solo aditivo.** Antes de cerrar cada PR que lo toque: `git diff main -- .github/workflows/ci.yml` no contiene líneas eliminadas ni
  modificadas (`git diff main -- .github/workflows/ci.yml | Select-String '^-[^-]'` sin salida).
- **`apps/api` intacto.** Antes de cerrar cada PR: `git diff --stat main...HEAD -- apps/api` sin salida.
- **Textos.** Ninguna cadena visible ni nombre accesible embebido en componentes; todo por `useUiText()` o por props ya traducidas. Idioma de código,
  identificadores, comentarios técnicos y commits: inglés. Documentación y notas fechadas: español neutro profesional.
- **Registros y datos.** Nada de datos personales, de menores ni secretos en el repositorio, en pruebas, en historias ni en fixtures (reglas 11, 13 y 15
  de `CLAUDE.md`). Los importes de las historias son ficticios.
- **Commits.** Conventional Commits en inglés, sin atribución de herramientas de IA, con pruebas y documentación en el mismo commit que el
  comportamiento. Una rama por PR: `change/design-system-foundations-and-a11y-NN-<nombre>`.
- **Revisión de seguridad de cada PR.** Lista aplicable de `docs/03` marcada en la descripción del PR: dependencias nuevas auditadas, sin
  guiones de instalación sin motivo, sin CDN de terceros, sin MDX ejecutable, sin peticiones de red en `packages/ui`.
- **Medición antes de abrir el PR.** Registrada en `apply-progress.md` junto con la lista de cierre de la tarea. Abrir el PR y empujar la rama son
  decisiones del propietario; este documento no los presupone.

### Suggested Work Units

| Unidad | Objetivo | PR probable | Comando de prueba enfocado | Arnés de ejecución | Frontera de reversión |
|---|---|---|---|---|---|
| 1.1 | Sondas S1 a S5, versiones exactas y compuerta | PR 1 | N/A (no escribe código; produce evidencia) | Directorio de trabajo temporal fuera del repositorio con las versiones del catálogo; `pnpm install`, `storybook build`, Vitest en modo navegador y `node -e` sobre `Intl` | Se descartan la nota fechada y el directorio temporal; ningún cambio de código |
| 1.2 | Paquete `@confia/ui`, catálogo, cableado de Vitest, ESLint, tsconfig y cobertura | PR 1 / 1a | `pnpm --filter @confia/ui exec vitest run --project unit` | `pnpm turbo run lint typecheck test build --filter=!@confia/api`: las cuatro tareas se ejecutan en `@confia/ui` | Se elimina `packages/ui/`, las líneas del catálogo y el cambio de `packages/config/vitest.js`; sin dependientes |
| 1.3 | Tokens en tres niveles, temas, Inter y sus pruebas | PR 1 / 1b | `pnpm --filter @confia/ui exec vitest run --project unit src/tokens` | `compile()` de Tailwind sobre `index.css` real y análisis del CSS emitido | Se revierte el commit; solo `src/tokens/` y la nota de `docs/ui-ux/01` |
| 2.1 | Catálogo `es-HN` tipado, i18next con ICU y `formatMoney` | PR 2 / 2a | `pnpm --filter @confia/ui exec vitest run --project unit src/i18n src/format` y `pnpm --filter @confia/ui typecheck` | Node 24 con el `Intl` real y `tsc` sobre `keys.test-d.ts` | Se revierte el commit; no hay consumidores todavía |
| 2.2 | Reglas de ESLint de ADR-0011, de primitivos, de peticiones y escáner de supresiones | PR 2 / 2b | `pnpm --filter @confia/tooling exec vitest run src/eslint-ui-rules.test.ts src/suppression-citations.test.ts` | `ESLint.lintText` con la ruta de archivo que activa cada ámbito; `eslint .` sobre `packages/ui` real | Se revierte el commit; `confiaEslint` sin la opción `ui` vuelve a ser la actual |
| 3.1 | Storybook, política de axe, guardia de MDX, `cn` y `Spinner` | PR 3 / 3a | `pnpm --filter @confia/ui exec vitest run --project unit src/a11y src/lib src/atoms/spinner` | `pnpm --filter @confia/ui build` (`storybook build`) | Se revierte el commit; la puerta aún no existe |
| 3.2 | Puerta de axe en Chromium, fixtures inaccesibles y comprobación de historias | PR 3 / 3b | `pnpm --filter @confia/ui test:a11y` y `pnpm --filter @confia/ui exec vitest run --project unit src/a11y/story-coverage.test.ts` | Chromium real con el CSS real de `index.css`, historias en `light` y `dark` | Se revierte el commit; vuelve a existir Storybook sin puerta |
| 3.3 | Trabajo de CI aditivo, tarea `test:a11y`, ADR-0026 y filas de stack | PR 3 / 3c | `pnpm --filter @confia/tooling exec vitest run src/ci-workflow.test.ts` | `git diff main -- .github/workflows/ci.yml` solo con líneas agregadas; `pnpm turbo run test:a11y --filter=@confia/ui` | Se revierte el commit; ADR-0026 pasa a Rechazado si ya se fusionó |
| 4.1 | `cn`, `Button`, `IconButton`, `Label`, `Badge` | PR 4 / 4a | `pnpm --filter @confia/ui exec vitest run --project unit src/atoms` | `pnpm --filter @confia/ui test:a11y` sobre las historias nuevas | Se revierte el commit; los componentes son independientes entre sí salvo `IconButton` sobre `Button` |
| 4.2 | `Separator`, `Skeleton`, `Input` | PR 4 / 4b | `pnpm --filter @confia/ui exec vitest run --project unit src/atoms` | `pnpm --filter @confia/ui test:a11y` | Se revierte el commit |
| 5.1 | `Textarea`, `Checkbox`, `RadioGroup` | PR 5 / 5a | `pnpm --filter @confia/ui exec vitest run --project unit src/atoms` | `pnpm --filter @confia/ui test:a11y`; teclado con `user-event` | Se revierte el commit |
| 5.2 | `FormField` con `FormError`, `EmptyState`, `ErrorState` | PR 5 / 5b | `pnpm --filter @confia/ui exec vitest run --project unit src/molecules` | `pnpm --filter @confia/ui test:a11y` | Se revierte el commit; ninguna molécula la consume aún fuera de `packages/ui` |
| 6.1 | `MoneyAmount` y paridad del tipo `Money` | PR 6 / 6a | `pnpm --filter @confia/ui exec vitest run --project unit src/domain/money-amount` y `pnpm --filter @confia/ui typecheck` | `Intl` real en Node 24 y en Chromium (misma salida) | Se revierte el commit; `formatMoney` ya existía desde 2.1 |
| 6.2 | `StatusBadge` | PR 6 / 6b | `pnpm --filter @confia/ui exec vitest run --project unit src/domain/status-badge` | `pnpm --filter @confia/ui test:a11y` con 7 estados × 3 variantes × 2 temas | Se revierte el commit |
| 6.3 | Reglas de capas con fixtures, inventario público, sin red, notas documentales | PR 6 / 6b | `pnpm --filter @confia/tooling exec vitest run src/dependency-rules.test.ts` y `pnpm --filter @confia/ui exec vitest run --project unit src/public-api.test.ts` | `depcruise` sobre el árbol real con cada capa no vacía | Se revierte el commit; el espejo de ESLint (2.2) sigue protegiendo las capas |

### Dependencias y paralelismo

| Tarea | Depende de | Paralelismo |
|---|---|---|
| 1.1 | Ninguna | Secuencial: raíz de la cadena y compuerta de todo lo demás |
| 1.2 | 1.1 | Secuencial |
| 1.3 | 1.2 | Secuencial |
| 2.1 | 1.3 (PR 1 fusionado) | Secuencial; `formatMoney` usa el resultado de S3 |
| 2.2 | 2.1 | Secuencial: lee `src/format/money.ts` real para la prueba «código real sin infracciones» |
| 3.1 | 2.2 (PR 2 fusionado) | Secuencial |
| 3.2 | 3.1 | Secuencial |
| 3.3 | 3.2 | Secuencial; toca `ci.yml` y `turbo.json`, único escritor |
| 4.1 | 3.3 (PR 3 fusionado) | Secuencial |
| 4.2 | 4.1 | Puede redactarse en paralelo con 4.1 (archivos disjuntos); se compromete después |
| 5.1 | 4.2 (PR 4 fusionado) | Secuencial; `Checkbox` y `RadioGroup` usan `Label` |
| 5.2 | 5.1 | Secuencial; `FormField` usa `Label`, `Input` y `Textarea`; `EmptyState` y `ErrorState` usan `Button` |
| 6.1 | 5.2 (PR 5 fusionado) | Secuencial |
| 6.2 | 6.1 | Puede redactarse en paralelo con 6.1 (archivos disjuntos) |
| 6.3 | 6.1 y 6.2 | Cierra el cambio; requiere los diecisiete componentes |

**Paralelismo con la cadena del backend.** Los puntos de contacto con la cadena de `background-jobs-with-db-scheduler` son tres y se tratan así:
`pnpm-lock.yaml` (regenerado tras cada rebase, nunca fusionado a mano), `pnpm-workspace.yaml` (solo líneas de catálogo añadidas en orden alfabético,
sin tocar las existentes) y `.github/workflows/ci.yml` (solo el PR 3, solo un trabajo nuevo al final de `jobs:`). El número de ADR se reconcilia
contra `main` antes del PR 3.

---

## Fase 1: sondas, paquete y tokens (PR 1, corte 13a-1)

- [x] 1.1 **Compuerta de sondas S1 a S5 (design §7; riesgos de la propuesta). No escribe código del repositorio.**
  - **Paso 0, directorio de trabajo.** Crear fuera del repositorio (en el directorio temporal de la sesión) un proyecto pnpm mínimo con una copia de
    `pnpm-workspace.yaml` (catálogo con TypeScript 6.0.3, Vitest 5.0.2, ESLint 10.11.0; `strictDepBuilds: true`; `allowBuilds` vigente) y de `.nvmrc`.
    Prohibido cambiar `pnpm-workspace.yaml` del repositorio en esta tarea.
  - **S1 (versiones y pares).** Para cada paquete de la tabla de design §3, decisión 13, consultar la última versión estable con `pnpm view <paquete>
    version` y sus pares con `pnpm view <paquete>@<versión> peerDependencies`; contrastar con la documentación primaria (context7 si está disponible;
    si no, el sitio oficial, y se anota la fuente). Instalar el conjunto con `pnpm install --frozen-lockfile` tras una primera instalación limpia; el
    criterio es cero `overrides` de pares y una historia mínima que compila con `storybook build` bajo TypeScript 6.0.3. Registrar versión exacta y
    pares de **cada** paquete, y si `eslint-plugin-react-hooks` admite ESLint 10.
  - **S2 (Tailwind).** Reproducir `index.css` con `@import "tailwindcss"; @theme { --*: initial; }`, `@theme static` con `--palette-primary-600` y
    `--color-primary`, y `@custom-variant dark`. Compilar con `compile()` y afirmar: `bg-primary` produce regla; `bg-palette-primary-600` y `bg-red-500`
    no producen ninguna; `--palette-primary-600` se emite como variable aunque nada la use.
  - **S3 (`Intl`).** En Node 24 y en Chromium (Playwright) formatear con `es-HN`, estilo `currency`, `HNL` y `USD`, dos decimales, las cadenas
    `"12345678901234567.89"`, `"123456789012345678.90"`, `"-0.00"`, `"-0.0000"`, `"0.00"`, `"3200.5"`, `"9999999999.99"` y su negativo. Registrar la salida
    literal de cada motor, la salida de `currencyDisplay: "name"`, y el efecto de `signDisplay` (`auto`, `exceptZero`, `negative`) sobre el cero negativo.
    Comprobar con `tsc` bajo `lib: ["ES2023"]` que `format(string)` es aceptado por el tipo; si no, anotar la firma que sí lo es.
  - **S4 (`strictDepBuilds` y auditoría).** Con las dependencias de S1 instaladas, listar toda petición de guion de instalación (`pnpm ignored-builds`
    o el error de `strictDepBuilds`) y decidir cada una con su motivo; ejecutar `pnpm audit --audit-level high`. Registrar la lista resuelta.
  - **S5 (navegador).** Con `vitest` 5.0.2, `@vitest/browser-playwright`, `playwright` y `storybook` fijados por S1: instalar Chromium
    (`playwright install chromium`, sin guion de instalación), componer una historia con `composeStories`, cargar un `index.css` mínimo con
    `@tailwindcss/vite`, y ejecutar `axe-core` con las etiquetas `wcag2a`, `wcag2aa`, `wcag21a`, `wcag21aa` y `wcag22aa` sobre tres historias
    deliberadamente inaccesibles (imagen sin alternativa, botón sin nombre, contraste insuficiente). Afirmar que aparecen `image-alt`, `button-name` y
    `color-contrast` como violaciones y no como incompletas. Probar `page.emulateMedia({ colorScheme: "dark" })` (o el equivalente del proveedor) y
    registrar si la emulación funciona. Probar el comando personalizado del proveedor que escribirá `reports/a11y/report.json`.
  - **Cierre.** Si **alguna** sonda no cumple su criterio: **detener el cambio**, marcar esta tarea como bloqueada, escribir la nota fechada con el
    resultado observado y la opción que se presentaría (columna «Si falla» de la compuerta) e informar al propietario; no se avanza a 1.2. Si todas
    cumplen: añadir una **nota fechada** en `design.md` §7 con la tabla de resultados (versiones exactas, pares, salidas de `Intl`, lista de
    `allowBuilds` con motivo, emulación de `prefers-color-scheme` sí/no) y una entrada «Sondas» en `apply-progress.md`. Commit (solo documentación):
    `docs(openspec): record the design-system probe results`. Tamaño: 0 líneas efectivas (todo bajo `openspec/`). — Cubre S1 a S5 y habilita
    DS29 a DS39, DS43 a DS46 y DS66 a DS71.

- [x] 1.2 **PR 1 / 1a `01-package-and-tokens`: paquete `@confia/ui`, catálogo de versiones, cableado de Vitest, ESLint, tsconfig y cobertura (decisiones 1 y 13).**
  - **Paso 0.** Crear la rama `change/design-system-foundations-and-a11y-01-package-and-tokens` desde el HEAD de la rama de artefactos y confirmar los
    artefactos del cambio como primer commit (`docs(openspec): add the design-system-foundations-and-a11y change artifacts`).
  - **ROJO.** (1) `packages/ui/src/render-smoke.test.tsx`: renderiza un componente trivial con Testing Library en jsdom y afirma su rol; el rojo
    observado es **«No test files found»** porque `packages/config/vitest.js` solo incluye `src/**/*.test.ts` (design §2). (2)
    `packages/ui/src/package-exports.test.ts`: `exports` de `package.json` contiene exactamente la clave `"."` y su destino existe (las claves
    `"./styles.css"` y `"./i18n"` las añaden 1.3 y 2.1 con su prueba; el conjunto exacto de tres se afirma en 6.3). (3) Confirmar que
    `tooling/src/workspace-guards.test.ts` (leído en el rojo; si enumera paquetes del espacio de trabajo, se amplía para incluir `@confia/ui`) falla
    hasta que el paquete exista. **Rojos esperados:** «No test files found»; `ENOENT packages/ui/package.json`; el guardián de espacio de trabajo no
    reconoce `@confia/ui`.
  - **VERDE.** Crear `packages/ui/package.json` (`@confia/ui`, privado, `"type": "module"`, `exports` con `"."` → `src/index.ts`; scripts `lint:
    eslint .`, `typecheck: tsc --noEmit`, `test: vitest run --project unit --coverage`, `build: tsc --noEmit` —provisional y real, no omitido; 3.1 lo
    reemplaza por `storybook build`—), `tsconfig.json` (extiende la base de `packages/config`; `lib: ["ES2023","DOM","DOM.Iterable"]`, `jsx:
    "react-jsx"`, `module: "Preserve"`, `moduleResolution: "Bundler"`, `types` de Vitest y `jest-dom`; conserva `exactOptionalPropertyTypes`),
    `vitest.config.ts` (proyecto `unit` con jsdom y `setupFiles` de `jest-dom`; cobertura V8 con `include: ["src/**/*.{ts,tsx}"]` y exclusiones solo
    de pruebas, historias, fixtures y `.d.ts`, umbral global **80** en líneas, ramas, funciones y sentencias, `passWithNoTests: false`; el proyecto
    `a11y` llega en 3.2), `eslint.config.js` (`confiaEslint({ tsconfigRootDir })`; la opción `ui` llega en 2.2), `src/index.ts` (`export {};`),
    `src/hooks/index.ts` (`export {};` con un comentario que cita `docs/ui-ux/02` §3, para que la carpeta exista en git), `README.md` (qué es el
    paquete, cómo correr sus tareas; la sección del navegador llega en 3.3). Editar `packages/config/vitest.js` para que `include` admita
    `src/**/*.test.ts` y `src/**/*.test.tsx` sin cambiar el comportamiento de los demás paquetes. Editar `pnpm-workspace.yaml` (catálogo: `react`,
    `react-dom`, `@types/react`, `@types/react-dom`, `jsdom`, `@testing-library/react`, `@testing-library/user-event`, `@testing-library/jest-dom`,
    `@vitest/coverage-v8`, con las versiones exactas de S1; `allowBuilds` solo con lo que S4 resolvió) y regenerar `pnpm-lock.yaml`.
  - **REFACTOR.** Comparar el número de pruebas y el resultado de `contracts`, `tooling` y `config` antes y después del cambio de
    `packages/config/vitest.js`: idénticos.
  - **Demostraciones deliberadas.** (1) Añadir `src/lib/uncovered.ts` con una función sin ninguna prueba: `pnpm --filter @confia/ui test` falla
    **nombrando `@confia/ui` y el porcentaje medido** por debajo de 80, y el archivo cuenta en el denominador [BX22, BX24]; al quitarlo vuelve a verde
    [BX23]. (2) Quitar `src/**/*.test.tsx` de `packages/config/vitest.js`: la prueba de humo deja de recogerse y `test` falla con «No test files
    found», no pasa en silencio [BX20]. Se revierten y se confirman con `cmp`.
  - **Cierre.** `pnpm install --frozen-lockfile` (el archivo de bloqueo no cambia) [BX21] y `pnpm turbo run lint typecheck test build
    --filter=!@confia/api` en verde, con las cuatro tareas ejecutándose en `@confia/ui` y al menos una prueba real [BX20]. Commit: `build(ui): add the
    @confia/ui workspace package with its test, lint and coverage wiring`. Tamaño: nominal 150, realista 150. — Escenarios BX20 a BX24 y DS01 (parcial:
    la importación desde otra ruta del espacio de trabajo se cierra en 6.3).

- [x] 1.3 **PR 1 (parte A) y PR 1b (parte B) `01-package-and-tokens`: tokens en tres niveles, temas claro y oscuro, Inter y sus pruebas (decisiones 2 a 5).**
  - **ROJO.** Crear, en `packages/ui/src/tokens/`: `tokens.build.test.ts` (compila `index.css` con `compile()` de Tailwind v4: `bg-primary` genera
    regla; **violación deliberada** `bg-palette-primary-600` y `bg-red-500` no generan ninguna; los tokens de espaciado, radio y tamaño de texto de
    `docs/ui-ux/01` resuelven) [DS04, DS06]; `tokens.parity.test.ts` (los dos bloques oscuros —`@media (prefers-color-scheme: dark)` sobre
    `:root:not([data-theme='light'])` y `:root[data-theme='dark']`— declaran **exactamente** las mismas variables con los mismos valores; toda variable
    del bloque oscuro tiene valor base; violación deliberada en `__fixtures__/dark-only.css` con un token solo en el bloque oscuro: falla nombrando el
    token [DS11]; árbol real: todos con valor base y al menos una variable evaluada [DS12]; el atributo gana sobre la preferencia: con `data-theme='light'`
    ningún bloque oscuro aplica, con `data-theme='dark'` aplica el segundo [DS08, DS09, DS10]); `tokens.contrast.test.ts` (fórmula de WCAG 2.1 por cada
    par de `docs/ui-ux/01` §5 resolviendo la cadena `var()` desde el CSS, en claro y en oscuro: 4,5:1 texto, 3:1 componente; la exención de
    `--color-text-disabled` escrita en la tabla de pares con su cita a `01` §5.1; fixture con un par insuficiente que debe fallar nombrando el par);
    `tokens.chain.test.ts` (`--color-primary` se resuelve por referencia a un primitivo `--palette-primary-*` y `--button-primary-bg` por referencia a
    `--color-primary` [DS07]); `tokens.scan.test.ts` (recorre los `.css` de `src/` fuera de `primitives.css` y `semantic.css`: ninguno contiene
    `--palette-`; el recorrido evalúa al menos `components.css` y `base.css`); `tokens.fonts.test.ts` (la pila de fuentes declarada empieza por
    `'Inter Variable'` y `Inter`, seguida de `ui-sans-serif`, `system-ui` y genéricas de respaldo; `base.css` importa `@fontsource-variable/inter` solo
    con `latin` y `latin-ext`, sin ninguna URL `http`) [DS14, DS13 parcial]. **Rojos esperados:** `ENOENT index.css` en todas.
  - **VERDE.** Crear `src/tokens/{index,primitives,semantic,themes,components,base}.css` con el contenido de `docs/ui-ux/01` §14 y §4 a §7, con el
    **único cambio de nombre** de los primitivos a `--palette-neutral-*` y `--palette-primary-*` y los hexadecimales de estado financiero y de
    retroalimentación literales de `01` §4 (design §3, decisión 3). `index.css` en el orden de la decisión 2 (`@import "tailwindcss"`, `@theme {
    --*: initial; }`, los cinco archivos, `@source "../"`, `@custom-variant dark`). `base.css` importa Inter y antepone `'Inter Variable'` a la pila;
    **no** define aún la clase `money-amount` (llega en 6.1). `package.json`: dependencias `tailwindcss` y `@fontsource-variable/inter` en el
    catálogo; `exports` añade `"./styles.css"` → `src/tokens/index.css` con su prueba en `package-exports.test.ts`. Nota fechada en
    `docs/ui-ux/01-tokens-de-diseno.md` §14: primitivos en `--palette-*`, ruta real `packages/ui/src/tokens/`, y el motivo (la regla dura del §1 deja de
    depender de disciplina). Regenerar `pnpm-lock.yaml`.
  - **REFACTOR.** Un solo módulo de utilidades de prueba (`__fixtures__/css.ts`) para leer, resolver `var()` y parsear los bloques, compartido por las
    cuatro pruebas.
  - **Demostraciones deliberadas.** (1) Renombrar `--palette-primary-600` a `--color-primary-600` en `primitives.css` pone en rojo `tokens.build.test.ts`
    por la aserción de que `bg-primary-600` no debe generar regla [DS04]. (2) Borrar el valor base de `--color-bg` en `semantic.css` dejando el del
    bloque oscuro pone en rojo `tokens.parity.test.ts` nombrando `--color-bg` [DS11]. (3) Bajar el contraste de `--color-text-secondary` en el tema
    claro pone en rojo `tokens.contrast.test.ts` nombrando el par. Se revierten y se confirman con `cmp`.
  - **Partición (2026-10-29, decisión del propietario).** 1.3 midió 1 008 líneas y el PR 1 completo 1 202, por encima de 800; el propietario autorizó
    un séptimo PR. **Parte A (13a-1, esta rama):** los seis archivos CSS, `tokens.build.test.ts`, `__fixtures__/css.ts` reducido a `tokensDirectory`,
    `readToken`, `readFile` y `buildCss`, `exports["./styles.css"]` con su prueba y la nota de `docs/ui-ux/01`. **Parte B (13a-1b):**
    `tokens.parity.test.ts`, `tokens.contrast.test.ts`, `tokens.chain.test.ts`, `tokens.scan.test.ts`, `tokens.fonts.test.ts`, sus fixtures
    (`dark-only.css`, `dark-drift.css`, `low-contrast.css`, `palette-leak.css`) y el resto de `__fixtures__/css.ts` (análisis de reglas, `var()`,
    paridad, recorrido). Se movieron las cinco pruebas y no solo paridad y contraste porque con la costura inicial la parte A seguía en unas 850
    líneas; las utilidades viajan con la prueba que las usa. Las demostraciones 2 y 3 y los rojos de paridad, contraste, cadena, recorrido y fuentes
    pertenecen a la parte B. La tarea se marca hecha en esta rama (13a-1b), con las dos partes presentes.
  - **Cierre.** `pnpm turbo run lint typecheck test build --filter=!@confia/api` y `pnpm audit --audit-level high` en verde; medición del PR 1. Commit:
    `feat(ui): add the three-level design tokens with light and dark themes`. Tamaño: nominal 550, realista 580. — Escenarios DS04, DS06 a DS14 (DS08 a
    DS10 y DS13 se confirman en navegador en 3.2).

## Fase 2: textos, formato de importes y análisis estático (PR 2, corte 13a-2)

- [x] 2.1 **PR 2 / 2a `02-texts-and-static-analysis`: catálogo `es-HN` tipado con ICU y `formatMoney` sin aritmética (decisiones 6 y 12; sonda S3).**
  - **Precondición.** PR 1 fusionado en `main`; rama nueva desde `main` actualizado.
  - **ROJO, catálogo.** `src/i18n/i18n.test.tsx`: con la instancia de `createUiI18n()`, `useUiText().t("common.status.paid")` devuelve «Pagado» [DS57];
    con `lng: "en"` (sin traducciones reales) devuelve el texto de `es-HN` y no la clave cruda [DS58]; un mensaje ICU con plural se formatea (ADR-0011
    exige ICU); el entorno de pruebas inicializa el idioma predeterminado mediante `src/test/render.tsx` (`renderWithI18n`) y un texto renderizado no
    es una clave ni está vacío [DS60]; **todas** las claves usadas en `src/**/*.tsx` (recorrido con la API del compilador de TypeScript sobre
    `t("…")`) existen en `ui.json`, con al menos una clave real evaluada [DS59]. `src/i18n/keys.test-d.ts`: `t("common.status.inexistente")` con
    `// @ts-expect-error ADR-0011: deliberate violation, the key is not in the es-HN catalog` (un `@ts-expect-error` sin error lo rompe `tsc`) y una
    clave construida por concatenación, con el mismo comentario [BX06, BX07]. `src/i18n/keys.check.test.ts`: construye un programa de TypeScript sobre
    un fixture de violación y afirma que los diagnósticos nombran la **clave** y el **archivo** [BX06, BX07]. **Rojos esperados:** módulos
    inexistentes; `@ts-expect-error` sin uso.
  - **ROJO, formato** (cierra la sonda S3 dentro del repositorio). `src/format/money.test.ts`, tabla de casos con la salida literal registrada en
    1.1 para `es-HN`: HNL `"3200.00"` → `L 3,200.00` [DS29, DS39]; USD → mismo número con indicador distinto [DS30]; cero `"0.00"` → `0.00` [DS31];
    `"-0.00"` y `"-0.0000"` → `0.00` sin menos [DS32]; `"-1500.00"` → con menos y `1,500.00` [DS33]; contable → paréntesis [DS34];
    `"9999999999.99"` y su negativo [DS35]; `"123456789012345678.90"` → `123,456,789,012,345,678.90` sin pérdida [DS36]; `"3200.0000"`, `"3200.1000"`,
    `"3200.500"` → dos decimales por ceros sobrantes [DS37]; `"3200"` y `"3200.5"` → dos decimales [DS38]; **inválidos** (`kind: "invalid"`):
    `"3200.005"` y `"3200.125"` [DS43]; `"abc"`, `""`, `" 12.00"`, `"1e3"`, `"1,500.00"`, `"+5.00"`, `"12."` [DS44]; `amount` numérico en tiempo de
    ejecución [DS45]; monedas `"hnl"`, `"HN"`, `"HNLL"`, `""` y una de tres letras fuera de `Intl.supportedValuesOf("currency")` [DS46]; `spoken` con
    `currencyDisplay: "name"` incluye la cantidad y el nombre de la moneda [DS49]; `sign: "always"` da `+` solo a positivos, ni al cero ni a negativos
    [DS51]; ninguna entrada válida pasa por `number` (la prueba espía `Number`, `parseFloat`, `parseInt` y `Math.round` y afirma cero llamadas). Cobertura
    de `src/format/**` con umbral propio de **95**. **Rojo esperado:** `Cannot find module './money'`.
  - **VERDE.** `package.json`/catálogo: `i18next`, `react-i18next`, `i18next-icu`, `intl-messageformat`; `exports` añade `"./i18n"` →
    `src/i18n/resources.ts`. Crear `src/i18n/locales/es-HN/ui.json` con claves en inglés con puntos (ADR-0011): `common.status.{paid,pending,partial,
    overdue,void,promised,refunded,unknown}` con las etiquetas de `docs/ui-ux/01` §4 (por ejemplo «Pagado», «Vencido»), `common.loading`,
    `common.retry`, `common.required`, `money.unavailable`; `resources.ts` (`as const`); `create-ui-i18n.ts` (i18next + `i18next-icu`, `lng` y
    `fallbackLng` `"es-HN"`); `use-ui-text.ts` (`t` restringido a `UiKey` derivado del JSON, **sin** `CustomTypeOptions` global); `src/test/render.tsx`.
    Crear `src/format/money.ts` según design §3, decisión 12: validación por patrón `^-?\d+(\.\d+)?$` con sobrantes ceros y moneda `^[A-Z]{3}$` más
    `Intl.supportedValuesOf`, **sin convertir a `number`**; la cadena se entrega a `Intl.NumberFormat.prototype.format` como cadena; opciones fijas de
    dos decimales; `signDisplay` según el comportamiento registrado en S3 para el cero negativo. `vitest.config.ts` añade el umbral por glob
    `src/format/**`. Regenerar `pnpm-lock.yaml`.
  - **REFACTOR.** Una sola tabla de casos compartida entre `money.test.ts` y, más adelante, las pruebas de `MoneyAmount` (6.1) para que no diverjan.
  - **Demostraciones deliberadas.** (1) Cambiar `maximumFractionDigits` a `0` en `money.ts` pone en rojo `money.test.ts` por la aserción del importe en
    lempiras [DS29]. (2) Eliminar la comprobación de sobrantes no nulos hace que `"3200.005"` se formatee redondeado y pone en rojo la aserción
    `kind === "invalid"` [DS43] (es el redondeo silencioso que ADR-0011 prohíbe). (3) Borrar `common.status.paid` de `ui.json` pone en rojo `tsc`
    y `i18n.test.tsx` [DS57, DS59]. Se revierten y se confirman con `cmp`.
  - **Cierre.** `pnpm turbo run lint typecheck test build --filter=!@confia/api` y `pnpm audit --audit-level high` en verde. Commit:
    `feat(ui): add the typed es-HN catalog and the exact money formatter`. Tamaño: nominal 300, realista 370. — Escenarios DS29 a DS39 (nivel de
    función), DS43 a DS46, DS49, DS51 (nivel de función), DS57 a DS60, BX06 y BX07. Desviación a registrar: el diagnóstico de `tsc` nombra la clave y
    el archivo; el catálogo `es-HN` se nombra a través del tipo `UiKey` derivado de `locales/es-HN/ui.json` (ver Apéndice B, R-1).

- [x] 2.2 **PR 2 / 2b `02-texts-and-static-analysis`: reglas de ESLint de ADR-0011, de primitivos y literales visuales, de peticiones y de capas (espejo), y escáner de supresiones (decisión 7).**
  - **ROJO.** Crear `tooling/src/eslint-ui-rules.test.ts` con `ESLint.lintText` y la ruta de archivo que activa cada ámbito, siguiendo el patrón de
    `tooling/src/eslint-restrictions.test.ts`; cada control con su violación deliberada **y** su caso aceptado:
    (1) texto en JSX: `<button>Guardar</button>` en `packages/ui/src/atoms/button/Bad.tsx` falla con la regla de literales [BX01]; `aria-label="Cerrar"`
    falla [BX02]; `title`, `alt`, `placeholder` y `label` literales fallan; aceptado: `aria-label={t("…")}` y el mismo código en `*.stories.tsx` y
    `*.test.tsx` (ámbito exento). (2) `x.toFixed(2)` [BX03] y `x.toLocaleString()` [BX04] fallan fuera de `src/format/**`; dentro de `src/format/` el uso
    de `Intl.NumberFormat` no falla [BX05]. (3) importes: `Number(money.amount)`, `parseFloat`, `parseInt`, `+money.amount`, `Math.round`, `.toFixed` y los
    operadores `+ - * / %` sobre `amount` fallan en `src/format/**` y `src/domain/money-amount/**` [BX09, BX10, DS40, DS41]; aceptado: la plantilla de
    texto y el patrón `RegExp.test`. (4) primitivos y literales visuales: cualquier cadena con `--palette-` fuera de `src/tokens/`, el color
    `#2563EB`, `rgb(…)`, `hsl(…)`, un valor arbitrario de Tailwind con color (`bg-[#2563EB]`), `--color-blue-600`, y clases de la paleta predeterminada
    (`bg-blue-600`, `text-red-500`) fallan nombrando el archivo y el literal [DS04, DS05]; aceptado: `bg-primary`, `text-status-paid-fg`,
    `bg-(--button-primary-bg)`; ámbito exento: `src/tokens/**`, `__fixtures__/**`. (5) peticiones: `fetch`, `XMLHttpRequest`, `WebSocket`,
    `EventSource` fallan en todo `packages/ui` [BX33]. (6) **composición:** un mismo archivo con `import "@tanstack/react-query"` y una ruta interna
    de `@confia/contracts` produce **dos** errores (la regla existente `contractImportRestrictions` no queda reemplazada por la nueva) [BX32 y BX34 en
    espejo]; los átomos no importan `../../molecules` ni `../../domain`, las moléculas no importan `../../domain` [BX28 a BX30 en espejo]. Nota: la
    distinción valor/tipo de `@confia/contracts` [BX35, BX36] no se puede expresar con `no-restricted-imports` de núcleo; la hace
    dependency-cruiser en 6.3 (ver Apéndice B, R-2). (7) sobre el árbol real: `eslint .` en `packages/ui` termina sin errores y la prueba afirma
    `archivos evaluados > 0` incluyendo `src/format/money.ts` y `src/i18n/*.ts` [BX08, BX11 parcial]. Crear `tooling/src/suppression-citations.test.ts`:
    recorre `packages/ui` y falla ante `eslint-disable`, `@ts-expect-error`, `@ts-ignore`, `a11y: { disable` o `test: "off"` sin `ADR-NNNN` en la
    misma línea o la anterior, o con un ADR que no existe en `docs/adr/`; fixture de violación en `tooling/src/__fixtures__/`; afirma que el
    recorrido ve al menos `keys.test-d.ts` (que ya cita `ADR-0011`). **Rojos esperados:** `uiSourceRules is not a function`; la opción `ui` no
    existe; los literales no se detectan.
  - **VERDE.** `packages/config/eslint.js`: `uiSourceRules()` que arma **una** lista de selectores de `no-restricted-syntax` por grupo de archivos y
    **compone** `no-restricted-imports` con `contractImportRestrictions` en una sola entrada por grupo (en configuración plana una segunda entrada
    reemplaza las opciones de la primera; design §2); `confiaEslint` acepta `ui: true`; `packages/config/eslint.d.ts` actualizado.
    `packages/ui/eslint.config.js` pasa a `confiaEslint({ tsconfigRootDir, ui: true })`. Selectores según design §3, decisión 7, más los de literales
    visuales del punto (4). Solo reglas del núcleo de ESLint (`no-restricted-syntax`, `no-restricted-globals`, `no-restricted-imports`); ningún
    complemento de terceros. `eslint-plugin-react-hooks` se añade solo si S1 confirmó compatibilidad con ESLint 10; si no, se anota el pendiente en
    `apply-progress.md`.
  - **REFACTOR.** Nombrar cada selector con una constante y un comentario que cita ADR-0011 o `CLAUDE.md` (reglas 1 y 3); sin cambiar comportamiento.
  - **Demostraciones deliberadas.** (1) Quitar `toLocaleString` del selector de formato manual pone en rojo el caso [BX04]. (2) Ampliar la excepción
    de formato de `src/format/**` a todo `src/**` pone en rojo el caso `toFixed` fuera de las utilidades [BX03]. (3) Retirar `contractImportRestrictions`
    de la entrada compuesta pone en rojo el caso de dos errores [BX34]. Se revierten y se confirman con `cmp`.
  - **Cierre.** `pnpm turbo run lint typecheck test build --filter=!@confia/api` y `pnpm audit --audit-level high` en verde; medición del PR 2.
    Commit: `feat(config): enforce the ADR-0011 and design-token rules on packages/ui`. Tamaño: nominal 350, realista 330. — Escenarios BX01 a BX05,
    BX08 a BX11 (el de `MoneyAmount` real se re-ejecuta en 6.1), BX28 a BX30 y BX32 a BX34 en espejo, DS04, DS05, DS40, DS41.

## Fase 3: Storybook, puerta de axe y CI (PR 3, corte 13a-3)

- [x] 3.1 **PR 3 / 3a `03-storybook-and-axe-gate`: Storybook, política de axe, guardia de MDX, `cn` y `Spinner` como primer componente real (decisiones 8, 9 y 11).**
  - **Precondición.** PR 2 fusionado; rama nueva desde `main`. Sondas S1 y S5 con resultado favorable en 1.1.
  - **ROJO.** `src/a11y/policy.test.ts`: `A11Y_TAGS` es exactamente `wcag2a, wcag2aa, wcag21a, wcag21aa, wcag22aa`; `partitionViolations` bloquea
    `critical` y `serious` y solo informa `moderate` y `minor` [DS69]; **un resultado `incomplete` de `color-contrast` cuenta como bloqueante, nunca como
    aprobado** (requisito «Ninguna historia tiene violaciones críticas o serias»; ver Apéndice B, R-3); lista vacía → sin bloqueantes.
    `src/a11y/storybook-config.test.ts` (**amenaza: MDX ejecutable**, design §10): ningún archivo `*.mdx` existe bajo `packages/ui` y el patrón
    `stories` de `.storybook/main.ts` solo admite `*.stories.tsx`; violación deliberada con un `.mdx` temporal que debe hacer fallar la prueba.
    `src/lib/cn.test.ts` (`extendTailwindMerge` con las escalas propias: `cn("text-sm", "text-status-paid-fg")` conserva ambas clases, y
    `cn("p-2","p-4")` resuelve al último). `src/atoms/spinner/Spinner.test.tsx` (`role="status"`, nombre accesible desde `common.loading`, sin cadena
    embebida) y `Spinner.stories.tsx` (`Default`, tamaños). **Rojos esperados:** módulos inexistentes; `.storybook/main.ts` inexistente.
  - **VERDE.** Dependencias (catálogo, versiones de S1): `storybook`, `@storybook/react-vite`, `@storybook/addon-a11y`, `vite`, `@vitejs/plugin-react`,
    `@tailwindcss/vite`, `axe-core`, `class-variance-authority`, `clsx`, `tailwind-merge`, `lucide-react`. `.storybook/main.ts` (`@storybook/react-vite`,
    `stories: ["../src/**/*.stories.tsx"]`, `@tailwindcss/vite`), `.storybook/preview.tsx` (importa `src/tokens/index.css`, inicializa i18n con
    `createUiI18n`, decorador de tema que fija `document.documentElement.dataset.theme` con una barra de herramientas `light`/`dark`, y parámetros de
    axe tomados de `src/a11y/policy.ts`), `src/a11y/policy.ts`, `src/lib/cn.ts`, `src/atoms/spinner/{Spinner.tsx,index.ts}` (propio, `02` §4),
    `src/index.ts` exporta `Spinner`. `package.json`: `build` pasa a `storybook build --output-dir dist/storybook`. `turbo.json`: `build.outputs`
    añade `dist/storybook/**` (cambio aditivo en el archivo). `.gitignore` añade `packages/ui/dist/` y `packages/ui/reports/` si no están cubiertos.
    Regenerar `pnpm-lock.yaml`.
  - **REFACTOR.** `policy.ts` es el único lugar donde viven etiquetas e impactos; la importan la puerta (3.2) y `preview.tsx`.
  - **Demostraciones deliberadas.** (1) Incluir `moderate` en `BLOCKING_IMPACTS` pone en rojo `policy.test.ts` [DS69]. (2) Tratar `incomplete` como
    aprobado pone en rojo el caso del contraste incompleto [DS70 en espíritu]. (3) Añadir un `.mdx` a `src/` pone en rojo `storybook-config.test.ts`
    (amenaza MDX). Se revierten y se confirman con `cmp`.
  - **Cierre.** `pnpm turbo run lint typecheck test build --filter=!@confia/api` en verde, con `storybook build` dentro del `build` de `@confia/ui`;
    registrar el tiempo del trabajo `frontend verify` local (el trabajo existente tiene `timeout-minutes: 10` y no se puede modificar; ver Pregunta
    abierta 2 si el `build` de Storybook se acerca al límite). Commit: `feat(ui): add Storybook, the accessibility policy and the Spinner atom`.
    Tamaño: nominal 250, realista 310. — Escenarios DS69 (nivel de política), DS70 (parcial), DS62 (parcial) y la amenaza de MDX.

- [x] 3.2 **PR 3 / 3b `03-storybook-and-axe-gate`: puerta de axe en Chromium con historias portables, fixtures inaccesibles y comprobación de historias (decisiones 8 y 9; sonda S5).**
  - **ROJO, la puerta primero en rojo** (propuesta, Enfoque 4). Antes de que exista la puerta, crear `src/a11y/__fixtures__/Inaccessible.stories.tsx`
    (fuera del patrón de descubrimiento): una imagen sin texto alternativo, un botón sin nombre accesible y un texto con contraste insuficiente;
    `DarkOnlyLowContrast.stories.tsx` (contraste suficiente en claro, insuficiente en oscuro, usando tokens semánticos redefinidos solo para el
    fixture) y `ModerateOnly.stories.tsx` (solo una violación de impacto moderado). `src/a11y/gate.a11y.test.tsx` pasa esas historias por la **misma
    función** de la puerta y afirma: rechaza las tres violaciones nombrando historia y regla, con `image-alt`, `button-name` y **`color-contrast`**
    [DS66, DS67, BX12]; rechaza la historia solo-oscuro [DS68]; el fixture moderado solo se informa [DS69, BX16]. `src/a11y/stories.a11y.test.tsx`:
    descubre con `import.meta.glob("../{atoms,molecules,domain}/**/*.stories.tsx")`, compone con `composeStories`, y por cada historia y tema
    (`light`, `dark`) fija `document.documentElement.dataset.theme`, renderiza, ejecuta `play` si lo hay, corre axe con `A11Y_TAGS`, separa con
    `partitionViolations` y escribe `reports/a11y/report.json` por el comando personalizado verificado en S5 (advertencias y menores incluidas)
    [DS70, BX14, BX16]. `assertStoriesDiscovered(modules)`: lanza si hay **cero** historias, probado con `{}` [DS71, BX15]. Prueba de fuentes: ninguna
    petición de recursos a un origen distinto del local (`performance.getEntriesByType("resource")`) y la fuente se obtiene del propio paquete [DS13].
    Prueba de preferencia del sistema: si S5 confirmó la emulación, `colorScheme: "dark"` sin atributo da valores oscuros; si no, se registra que la
    paridad estática de 1.3 es la garantía [DS08]. `src/a11y/story-coverage.test.ts` (proyecto `unit`, corre en `frontend verify`):
    `findComponentsWithoutStory(root)` sobre `__fixtures__/tree/` con un componente huérfano deliberado lo detecta nombrándolo [BX13]; sobre `src/` real
    afirma **cero huérfanos y más de cero componentes** [DS62]; la misma comprobación extendida a `<Nombre>.test.tsx` (`findComponentsWithoutTest`)
    [DS73]. **Rojos esperados:** `Cannot find module '../a11y/run-gate'`; el proyecto `a11y` de Vitest no existe.
  - **VERDE.** `vitest.config.ts` añade el proyecto `a11y` (modo navegador, proveedor Playwright, Chromium sin cabeza, `@tailwindcss/vite`, `include:
    ["src/**/*.a11y.test.tsx"]`, sin cobertura); `package.json`: `test:a11y` = `vitest run --project a11y`, `playwright` y `@vitest/browser-playwright`
    en el catálogo (devDependencies; una sola versión de `playwright`, que el cambio 11 reutilizará); `src/a11y/run-gate.ts` con
    `checkStoryAccessibility(story, theme)`; `src/a11y/story-coverage.ts` (`findComponentsWithoutStory`, `findComponentsWithoutTest`: un componente es un
    directorio bajo `atoms/`, `molecules/` o `domain/` con `<Nombre>.tsx`; debe existir `<Nombre>.stories.tsx` con una historia `Default`). `Spinner` ya
    cuenta como el primer componente real. `packages/ui/README.md`: instalación local de Chromium y `pnpm --filter @confia/ui test:a11y`. Regenerar
    `pnpm-lock.yaml`.
  - **REFACTOR.** Una sola función de puerta compartida por la prueba de historias reales y la de fixtures.
  - **Demostraciones deliberadas.** (1) Quitar `color-contrast` de las reglas evaluadas pone en rojo `gate.a11y.test.tsx` por la aserción de que el
    fixture de bajo contraste es rechazado [DS67]. (2) Eliminar `dark` del recorrido de temas pone en rojo la aserción del fixture solo-oscuro [DS68].
    (3) Cambiar el patrón de descubrimiento por uno que no coincide con ningún archivo pone en rojo la prueba por «cero historias» [DS71]. (4) Mover
    `Inaccessible.stories.tsx` a `src/atoms/` hace que `pnpm --filter @confia/ui test:a11y` termine con código distinto de cero nombrando historia y
    regla (equivalente local del rojo en CI) [BX12]; se devuelve a `__fixtures__/`. (5) Borrar `Spinner.stories.tsx` pone en rojo
    `story-coverage.test.ts` nombrando `Spinner` [BX13]. Se revierten y se confirman con `cmp`.
  - **Cierre.** `pnpm turbo run lint typecheck test build --filter=!@confia/api` y `pnpm --filter @confia/ui test:a11y` en verde, ambos con el
    mismo veredicto que dará CI sobre el mismo código [BX17]. Commit: `test(ui): gate every story with axe in Chromium in both themes`. Tamaño:
    nominal 350, realista 350. — Escenarios DS62, DS66 a DS71, DS73 (parcial), BX12 a BX17 (la parte remota se observa tras el empuje que decide el
    propietario), DS08 y DS13 (en navegador).

- [x] 3.3 **PR 3 / 3c `03-storybook-and-axe-gate`: trabajo de CI aditivo, tarea `test:a11y` de Turbo, ADR-0026 y Storybook en el stack (decisión 8, sección 11).**
  - **ROJO.** `tooling/src/ci-workflow.test.ts`: lee `.github/workflows/ci.yml` (con el analizador de YAML ya presente en el árbol; si no hay uno,
    aserciones sobre el texto sin añadir dependencia) y afirma: los trabajos `backend`, `frontend` y `security` siguen con los mismos nombres,
    `timeout-minutes` y pasos (en particular el `run` de `frontend` es `pnpm turbo run lint typecheck test build --filter=!@confia/api`) [BX19]; existe
    un trabajo nuevo `design system accessibility` con `checkout`, `pnpm/action-setup` y `setup-node` en los **mismos SHA** que `frontend`, instalación
    con `--frozen-lockfile`, `playwright install --with-deps chromium`, `pnpm turbo run test:a11y --filter=@confia/ui`, subida de
    `packages/ui/reports/a11y/` con `if: always()` y `timeout-minutes: 15` [BX14, BX17]; toda acción usada está fijada por SHA de 40 hexadecimales.
    `turbo.json` define `test:a11y` con `dependsOn: ["^generate", "generate"]` y `outputs: ["reports/a11y/**"]`, separada de `test` (afirmado por
    una prueba que lee el JSON). **Rojo esperado:** no existe el trabajo nuevo ni la tarea.
  - **VERDE.** Editar `.github/workflows/ci.yml` **solo con líneas añadidas** al final de `jobs:` (design §3, decisión 8; sin caché de navegadores en
    este cambio) y `turbo.json`. Crear `docs/adr/ADR-0026-storybook-y-verificacion-de-accesibilidad.md` en estado **Propuesto** con el esquema de
    design §11 (título, contexto, factores, opciones A a E, decisión y restricción permanente, consecuencias, cumplimiento, reversión) y la fila de
    `docs/adr/README.md`. Añadir la fila de Storybook a la tabla de stack de `CLAUDE.md` y a `docs/01-arquitectura.md` §3.3 y §3.4. Reconciliar el
    número de ADR contra `main` (orden duro 5).
  - **REFACTOR.** Comentarios del trabajo nuevo que citan ADR-0026 y design §3, decisión 8.
  - **Demostraciones deliberadas.** (1) Quitar `if: always()` del paso de subida pone en rojo `ci-workflow.test.ts` [BX14]. (2) Cambiar el SHA de
    `setup-node` del trabajo nuevo pone en rojo la aserción de SHA idénticos. Se revierten y se confirman con `cmp`.
  - **Cierre.** `git diff main -- .github/workflows/ci.yml | Select-String '^-[^-]'` sin salida [BX18]; `pnpm turbo run test:a11y
    --filter=@confia/ui` en verde en local; `pnpm turbo run lint typecheck test build --filter=!@confia/api` en verde sin navegador instalado
    (los trabajos existentes siguen intactos [BX19]); ADR-0026 existe en estado Propuesto y está en el índice; medición del PR 3. La observación del
    resultado en el remoto y la demostración remota [BX12] requieren un empuje que decide el propietario. Commits: `ci(ui): add the design system
    accessibility job` y `docs(adr): add ADR-0026 on Storybook and the accessibility gate`. Tamaño: nominal 200, realista 185. — Escenarios BX12, BX14,
    BX17 a BX19.

## Fase 4: átomos de presentación (PR 4, corte 13a-4)

- [x] 4.1 **PR 4 / 4a y 4b `04-atoms-buttons-labels`** (parte A `Button` e `IconButton` en 4a, rama `…-13a-4a-buttons`, 553 líneas; parte B `Label` y `Badge` en 4b, donde se cierra): `Button`, `IconButton`, `Label` y `Badge` (decisión 11; `docs/ui-ux/02` §4 y §6).**
  - **Precondición.** PR 3 fusionado; rama nueva desde `main`. Dependencias de Radix y `lucide-react` con las versiones de S1.
  - **ROJO.** Por componente, con prueba y historia antes del código: `Button.test.tsx` (variantes de `02` §4; foco por teclado muestra el indicador
    basado en `--color-border-focus` y la clase no contiene `outline-none` sin reemplazo visible [DS16]; **carga**: sigue enfocable, `aria-busy="true"`,
    muestra un `Spinner`, no tiene el atributo `disabled` y activarlo con teclado y ratón no invoca de nuevo el manejador [DS17]; deshabilitado real no
    es enfocable); `Button.stories.tsx` con `Default`, cada variante, `Loading` y `Disabled`. `IconButton.test.tsx` y `IconButton.test-d.ts`
    (`// @ts-expect-error ADR-0018: deliberate violation, the accessible label prop is required`) [DS15]. `Label.test.tsx` (asocia por `htmlFor`,
    nombre accesible del control). `Badge.test.tsx` (variantes, sin color como único portador cuando lleva estado) y sus historias. Prueba de
    consumo: la regla de ESLint de 2.2 corre sobre cada archivo nuevo sin errores, con el conjunto de archivos evaluados > 0 [DS06]. **Rojos
    esperados:** módulos inexistentes; el `@ts-expect-error` sin uso hasta que `IconButton` exija la etiqueta.
  - **VERDE.** Adaptar de shadcn/ui (regla de adaptación): `src/atoms/{button,icon-button,label,badge}/` con su componente, historia, prueba e
    índice; clases a semánticos de CONFIA y a las variables de componente de `components.css` (`--button-*`, `--badge-*`); el anillo de foco con
    `--color-border-focus`; textos por props o catálogo (`common.loading`); `src/index.ts` exporta los cuatro. Si `components.css` carece de
    una variable de componente necesaria, se añade con su valor base y, si cambia en oscuro, su valor oscuro en **ambos** bloques (la prueba de
    paridad de 1.3 lo vigila). Regenerar `pnpm-lock.yaml` si entra una dependencia.
  - **REFACTOR.** Variantes con `class-variance-authority` y `cn()`; eliminar duplicación de clases de foco en una utilidad compartida.
  - **Demostraciones deliberadas.** (1) Añadir `disabled` al `Button` en carga pone en rojo la aserción de que sigue enfocable [DS17]. (2) Quitar la
    etiqueta obligatoria de las propiedades de `IconButton` pone en rojo `tsc` por el `@ts-expect-error` sin uso [DS15]. (3) Sustituir un token por
    `bg-blue-600` en `Badge.tsx` pone en rojo la regla de ESLint de literales y primitivos [DS04]. Se revierten y se confirman con `cmp`.
  - **Cierre.** `pnpm turbo run lint typecheck test build --filter=!@confia/api`, `pnpm --filter @confia/ui test:a11y` (cero violaciones críticas o
    serias en `light` y `dark` para las historias nuevas) y `pnpm audit --audit-level high` en verde. Commit: `feat(ui): add Button, IconButton, Label
    and Badge`. Tamaño: nominal 300, realista 490 (incluye `cn`, que 3.1 ya aportó, y las cuatro historias). — Escenarios DS15 a DS17, DS62, DS65,
    DS70, DS73, DS74 y DS06 (con componentes reales).

- [ ] 4.2 **PR 4 / 4b `04-atoms-buttons-labels`: `Separator`, `Skeleton` e `Input` (decisión 11).**
  - **ROJO.** `Separator.test.tsx` (decorativo con `role="none"` y semántico con `role="separator"` y orientación). `Skeleton.test.tsx` (oculto a las
    tecnologías de asistencia; historia `Loading` como su estado de carga). `Input.test.tsx`: **solo lectura frente a deshabilitado** [DS18] — el de
    solo lectura es enfocable y su valor seleccionable y copiable; el deshabilitado no es enfocable; anillo de foco [DS16]; `aria-invalid` cuando se
    le pasa; sus historias `Default`, `ReadOnly`, `Disabled`, `Invalid`. **Rojos esperados:** módulos inexistentes.
  - **VERDE.** `src/atoms/{separator,skeleton,input}/` adaptados de shadcn/ui; `src/index.ts` exporta los tres; variables de componente en
    `components.css` si faltan.
  - **REFACTOR.** Estados de campo (`02` §6) en una utilidad de clases compartida con `Textarea` (5.1).
  - **Demostraciones deliberadas.** (1) Dar `disabled` al `Input` de solo lectura pone en rojo la aserción de que es enfocable [DS18]. (2) Quitar el
    `aria-hidden` del `Skeleton` pone en rojo su aserción de accesibilidad. Se revierten y se confirman con `cmp`.
  - **Cierre.** `pnpm turbo run lint typecheck test build --filter=!@confia/api`, `pnpm --filter @confia/ui test:a11y` y
    `pnpm audit --audit-level high` en verde; medición del PR 4. Commit: `feat(ui): add Separator, Skeleton and Input`. Tamaño: nominal 150,
    realista 240. — Escenarios DS16, DS18, DS62, DS63 (`Input`, `Skeleton`), DS65, DS70, DS73, DS74.

## Fase 5: controles, campo de formulario y estados (PR 5, corte 13a-5)

- [ ] 5.1 **PR 5 / 5a `05-controls-form-and-states`: `Textarea`, `Checkbox` y `RadioGroup` (decisión 11).**
  - **Precondición.** PR 4 fusionado; rama nueva desde `main`.
  - **ROJO.** `Textarea.test.tsx` (solo lectura enfocable y seleccionable; deshabilitado no; `aria-invalid`; anillo de foco) [DS16, DS18].
    `Checkbox.test.tsx` con `user-event`: la barra espaciadora alterna el control [DS19]; nombre accesible por su `Label`; estado `indeterminate`
    anunciado; anillo de foco [DS16]. `RadioGroup.test.tsx`: tres opciones, las flechas mueven la selección y cada opción se anuncia con su etiqueta
    [DS19]; deshabilitado. Historias `Default`, `Disabled`, `Invalid` (y `Checked`/`Indeterminate`) en ambos temas. **Rojos esperados:** módulos
    inexistentes.
  - **VERDE.** `src/atoms/{textarea,checkbox,radio-group}/` adaptados de shadcn/ui sobre Radix (paquetes de S1); `src/index.ts` exporta los tres;
    variables de componente si faltan.
  - **REFACTOR.** Reutilizar la utilidad de estados de campo de 4.2.
  - **Demostraciones deliberadas.** (1) Anular el manejador de `onCheckedChange` pone en rojo la aserción de alternancia por barra espaciadora
    [DS19]. (2) Quitar `role="radiogroup"` pone en rojo la aserción de navegación y anuncio [DS19]. Se revierten y se confirman con `cmp`.
  - **Cierre.** `pnpm turbo run lint typecheck test build --filter=!@confia/api`, `pnpm --filter @confia/ui test:a11y` y
    `pnpm audit --audit-level high` en verde. Commit: `feat(ui): add Textarea, Checkbox and RadioGroup`. Tamaño: nominal 300, realista 350. —
    Escenarios DS16, DS18, DS19, DS62, DS63, DS65, DS70, DS73, DS74.

- [ ] 5.2 **PR 5 / 5b `05-controls-form-and-states`: `FormField` con `FormError`, `EmptyState` y `ErrorState` (decisión 11; `docs/ui-ux/03` §5).**
  - **ROJO.** `FormField.test.tsx`: el control se consulta por su nombre accesible y es el texto de la etiqueta [DS20]; con error, el control tiene
    `aria-invalid="true"`, su `aria-describedby` referencia el elemento del mensaje y el mensaje es texto presente (no solo color) [DS21]; con
    descripción y error, `aria-describedby` referencia ambos [DS22]; obligatorio marcado con texto disponible para lectores de pantalla desde
    `common.required`, no solo asterisco [DS23]; sin error, no hay `aria-invalid="true"` ni elemento de error vacío referenciado [DS24]; las pruebas
    localizan por rol y nombre, nunca por clase [DS74]. La forma exacta de asociar el control (función hija con las propiedades de asociación o
    `Slot` de Radix) se decide en el rojo y se registra en `apply-progress.md`. `EmptyState.test.tsx` y `EmptyState.test-d.ts`: omitir `action` es
    error de tipos (`@ts-expect-error ADR-0018`) [DS25]; la acción es un control con nombre accesible y se invoca exactamente una vez por activación
    [DS26]. `ErrorState.test.tsx` y `ErrorState.test-d.ts`: omitir `onRetry` es error de tipos [DS27]; el mensaje está en una región activa y el
    reintento, operable por teclado, se invoca exactamente una vez [DS28]; `retryLabel` por defecto desde `common.retry`. Historias de estado:
    `FormField` (`Default`, `WithError`, `Required`, `Disabled`), `EmptyState` (`Default`), `ErrorState` (`Default`) [DS63]. **Rojos esperados:**
    módulos inexistentes; `@ts-expect-error` sin uso.
  - **VERDE.** `src/molecules/{form-field,empty-state,error-state}/` (`FormField` exporta `FormError` desde su propio índice); identificadores con
    `useId`; `src/index.ts` exporta `FormField`, `FormError`, `EmptyState`, `ErrorState`; `EmptyState` y `ErrorState` reciben título, descripción y
    textos ya traducidos por props, salvo el reintento por defecto del catálogo.
  - **REFACTOR.** Una sola utilidad de asociación `aria-*` si `FormField` y otros la comparten.
  - **Demostraciones deliberadas.** (1) Quitar la referencia al error de `aria-describedby` pone en rojo la aserción [DS21]. (2) Hacer `onRetry`
    opcional pone en rojo `tsc` por el `@ts-expect-error` sin uso [DS27]. (3) Cambiar `role="alert"` por un `div` sin región activa pone en rojo la
    aserción de anuncio [DS28]. Se revierten y se confirman con `cmp`.
  - **Cierre.** `pnpm turbo run lint typecheck test build --filter=!@confia/api`, `pnpm --filter @confia/ui test:a11y` y
    `pnpm audit --audit-level high` en verde; medición del PR 5 (si supera 800, costura 5a/5b). Commit: `feat(ui): add FormField, EmptyState and
    ErrorState`. Tamaño: nominal 400, realista 450. — Escenarios DS20 a DS28, DS62, DS63, DS65, DS70, DS73, DS74.

## Fase 6: dominio, capas e inventario (PR 6, corte 13a-6)

- [ ] 6.1 **PR 6 / 6a `06-money-status-and-layers`: `MoneyAmount` (decisión 12; ADR-0011 «Presentación»; reglas 1 y 3 de `CLAUDE.md`).**
  - **Precondición.** PR 5 fusionado; rama nueva desde `main`.
  - **ROJO.** `MoneyAmount.test.tsx` reutiliza la tabla de casos de `money.test.ts` (REFACTOR de 2.1) y la verifica **a través del componente**: una
    prueba por cada caso límite [DS72] — HNL [DS29], USD distinto de HNL [DS30], cero [DS31], cero negativo `-0.00` y `-0.0000` [DS32], negativo
    [DS33], contable con paréntesis [DS34], máximo del contrato y su negativo [DS35], más allá de la precisión de `number` [DS36], cuatro decimales y
    ceros sobrantes [DS37], menos de dos decimales [DS38], formato regional idéntico a `new Intl.NumberFormat("es-HN", { style: "currency", currency:
    "HNL" }).format("1500.00")` [DS39]; **inválidos** que muestran el texto de `money.unavailable` y no lanzan: `"3200.005"` [DS43], las siete cadenas
    no decimales [DS44], `amount` numérico [DS45], las cuatro monedas mal formadas [DS46], y que el texto proviene de una clave del catálogo [DS47];
    numeración tabular siempre [DS48]; nombre accesible con cantidad y nombre de moneda de `Intl` para HNL y USD (la forma visual con `aria-hidden` y
    el texto `sr-only`) [DS49]; `colorBySign` solo si se pide y conserva el signo [DS50]; `showPositiveSign` solo en positivos [DS51]. Estáticos: la
    regla de ESLint de 2.2 corre sobre el código real de `src/domain/money-amount/` y de `src/format/` y pasa con archivos evaluados > 0 [DS42, BX11],
    mientras la violación deliberada `Number(money.amount)` en línea sigue fallando [DS40, DS41, BX09, BX10]. `money-contract.test-d.ts`: el `Money`
    de `@confia/contracts/admin` y el de `@confia/contracts/portal` son asignables al tipo que recibe `MoneyAmount`, de modo que una divergencia entre
    documentos rompe `typecheck`. Historias: HNL, USD, `Zero`, `Negative`, `Accounting`, `Large`, `Invalid` en ambos temas [DS64, DS65]. **Rojos
    esperados:** módulos inexistentes.
  - **VERDE.** `src/domain/money-amount/{MoneyAmount.tsx,index.ts}` con `import type { Money } from "@confia/contracts/portal"` (el contrato menos
    privilegiado), `formatMoney` de `src/format/money.ts` y `useUiText()` para `money.unavailable`; `base.css` añade la clase `money-amount` con
    numeración tabular que el consumidor no puede desactivar (`01` §7.5); `src/index.ts` exporta `MoneyAmount`. Sin aritmética, sin redondeo y sin
    conversión a `number` en ningún archivo.
  - **REFACTOR.** Eliminar toda lógica duplicada entre componente y utilidad; el componente solo compone.
  - **Demostraciones deliberadas.** (1) Sustituir en `MoneyAmount.tsx` la llamada a `formatMoney` por `Number(value.amount).toFixed(2)` pone en rojo
    `MoneyAmount.test.tsx` en el caso de la precisión de `number` **y** la regla de ESLint nombrando archivo y regla [DS36, DS40, DS41]. (2) Mostrar
    `0` cuando el importe es inválido pone en rojo la aserción de `money.unavailable` [DS43]. (3) Quitar `colorBySign` del control opcional y aplicar
    color siempre pone en rojo la aserción [DS50]. Se revierten y se confirman con `cmp`.
  - **Cierre.** `pnpm turbo run lint typecheck test build --filter=!@confia/api` y `pnpm --filter @confia/ui test:a11y` en verde; la salida de
    `Intl` en Chromium coincide con la de Node 24 en los casos registrados en S3. Commit: `feat(ui): add MoneyAmount`. Tamaño: nominal 300, realista
    260. — Escenarios DS29 a DS39, DS40 a DS42, DS43 a DS47, DS48 a DS51, DS64, DS65, DS70, DS72, DS73, BX09 a BX11.

- [ ] 6.2 **PR 6 / 6b `06-money-status-and-layers`: `StatusBadge` con color, icono y texto a la vez (design §3; `docs/ui-ux/01` §4 y `02` §5.2).**
  - **ROJO.** `StatusBadge.test.tsx`: por cada uno de los siete estados, icono propio, texto de la etiqueta `es-HN` (por ejemplo «Vencido» para
    `overdue`) y el token de color de ese estado (`--color-status-{estado}` por clase semántica) [DS52]; `iconOnly`: nombre accesible y título «Vencido»,
    texto no visible a la vista, icono decorativo [DS53]; en modo normal el estado se anuncia una sola vez y el icono está oculto a las tecnologías de
    asistencia [DS54]; estado desconocido en tiempo de ejecución (evadiendo tipos) → texto neutro `common.status.unknown` con icono, sin los siete
    estados ni color de estado, sin lanzar excepción [DS55]; texto desde el catálogo, «Pagado» para `paid` [DS57]. Historias: siete estados ×
    `soft`/`solid`/`outline` × `light`/`dark`, con cero violaciones críticas o serias, contraste incluido [DS56, DS65, DS70]. **Rojos esperados:**
    módulos inexistentes.
  - **VERDE.** `src/domain/status-badge/{StatusBadge.tsx,index.ts}` sobre `Badge` (4.1), con iconos de `lucide-react` distintos por estado,
    variables `--status-badge-*` de `components.css` (existentes desde 1.3 o añadidas con su valor oscuro en ambos bloques), textos por
    `useUiText()`; `src/index.ts` exporta `StatusBadge`.
  - **REFACTOR.** Tabla única estado → {icono, clave de texto, token} que consumen componente, historias y pruebas.
  - **Demostraciones deliberadas.** (1) Quitar el icono del estado `partial` pone en rojo la aserción de los tres canales [DS52]. (2) Mostrar el
    estado desconocido con el color de `overdue` pone en rojo la aserción [DS55]. (3) Quitar `aria-hidden` del icono pone en rojo la aserción de
    anuncio único [DS54]. Se revierten y se confirman con `cmp`.
  - **Cierre.** `pnpm turbo run lint typecheck test build --filter=!@confia/api` y `pnpm --filter @confia/ui test:a11y` en verde. Commit:
    `feat(ui): add StatusBadge`. Tamaño: nominal 200, realista 200. — Escenarios DS52 a DS56, DS57, DS62, DS65, DS70, DS73.

- [ ] 6.3 **PR 6 / 6b `06-money-status-and-layers`: reglas de capas de dependency-cruiser con fixtures, inventario público exacto, sin red y notas documentales (decisión 10; cierre del cambio).**
  - **ROJO.** Ampliar `tooling/src/dependency-rules.test.ts` (patrón de fixture permanente por regla más árbol real con `totalCruised > 0`) con las
    cuatro reglas de design §3, decisión 10, cada una con su violación deliberada bajo `tooling/dependency-fixtures/packages/ui/src/…`:
    `ui-atoms-know-only-tokens` (`atoms/imports-a-molecule.ts` y `atoms/imports-domain.ts`) [BX28, BX29]; `ui-molecules-never-import-domain`
    (`molecules/imports-domain.ts`) [BX30]; `ui-never-fetches-data` (`domain/imports-react-query.ts`, resuelto o sin resolver) [BX32];
    `ui-contracts-type-only` (`domain/imports-contract-value.ts` con un valor de ejecución por el punto público, y `…/imports-contract-internal.ts` con
    una ruta interna, esta además rechazada por la regla existente `contracts-only-through-public-entry`) [BX34, BX35]. Caso aceptado: el código
    real de `domain/` que importa de `atoms/` y `molecules/` no viola ninguna regla [BX31], y `import type { Money }` por el punto público no viola
    [BX36]. **Aserción de capa no vacía:** el árbol real contiene al menos un módulo en cada uno de `atoms/`, `molecules/` y `domain/`; una regla
    cuyo selector coincide con cero archivos reales hace fallar la verificación [BX37]. `packages/ui/src/public-api.test.ts`: el conjunto de
    exportaciones de componentes de `src/index.ts` es **exactamente** los diecisiete (`Button`, `IconButton`, `Input`, `Textarea`, `Label`, `Checkbox`,
    `RadioGroup`, `Badge`, `Spinner`, `Separator`, `Skeleton`, `FormField`, `FormError`, `EmptyState`, `ErrorState`, `MoneyAmount`, `StatusBadge`) y
    ninguna exportación es un token primitivo [DS02]; `exports` de `package.json` tiene exactamente las tres rutas `"."`, `"./styles.css"` y
    `"./i18n"`; la importación de `MoneyAmount` por el nombre del paquete compila (auto-referencia al nombre `@confia/ui`) [DS01];
    `public-api.test-d.ts`: importar `DataTable` o `Select` es error de tipos (`@ts-expect-error ADR-0018`) [DS03]; el conjunto exportado coincide con
    el de componentes con historia [DS62] y con el de componentes con archivo de pruebas [DS73]. `story-states.test.ts`: las historias exportadas de
    `Button`, `Input`, `Skeleton`, `EmptyState`, `ErrorState` y `FormField` incluyen cada estado aplicable (`Loading`, `Disabled`, `Empty`, `Error`) [DS63]
    y las de `MoneyAmount` incluyen cero, negativo, grande, `HNL` y `USD` [DS64]. `no-network.test.tsx`: con `fetch`, `XMLHttpRequest`, `WebSocket` y
    `EventSource` sustituidos por funciones que lanzan, se renderizan los diecisiete componentes solo con propiedades y **sin proveedor de TanStack Query**;
    todos renderizan sin error [DS61]. Regla de pruebas: un selector de ESLint en el ámbito `*.test.tsx` rechaza `querySelector`,
    `querySelectorAll` y `getByTestId` y las consultas por clase CSS, con su violación deliberada en `eslint-ui-rules.test.ts` [DS74]. **Rojos
    esperados:** las reglas nuevas no existen en `.dependency-cruiser.cjs`; el conjunto exportado difiere si falta alguno; los módulos de fixture no
    existen.
  - **VERDE.** `.dependency-cruiser.cjs`: añadir `.tsx` a `extensions` y las cuatro reglas (sin tocar las existentes);
    `tooling/dependency-fixtures/packages/ui/src/**` (violaciones y módulos mínimos que importan); `tooling/src/dependency-rules.test.ts` actualizado
    con la aserción de capa no vacía; selector de pruebas en `uiSourceRules()` (2.2). Documentación del cambio: `docs/ui-ux/02` §5.1 describe
    `MoneyAmount` con `value: Money` (`{ amount: string, currency: string }`, ya redondeado por el servidor) y retira `{ amountMinor, currency }`;
    nota fechada en `docs/09-roadmap-y-fases.md`: entregable 10 cerrado y criterio de salida 8 abierto y asignado a 13b (ADR-0018).
  - **REFACTOR.** Nombres de reglas y mensajes de `depcruise` que citan ADR-0018 y `docs/ui-ux/02` §2.
  - **Demostraciones deliberadas.** (1) Quitar `.tsx` de `extensions` hace que `domain/` quede sin analizar y pone en rojo la aserción de capa no
    vacía [BX37]. (2) Retirar `ui-never-fetches-data` pone en rojo su fixture [BX32]. (3) Marcar `import type` como valor en
    `ui-contracts-type-only` (quitar `dependencyTypesNot`) hace fallar el caso aceptado [BX36]. (4) Quitar `MoneyAmount` de `src/index.ts` pone en
    rojo `public-api.test.ts` nombrando el faltante [DS02]. Se revierten y se confirman con `cmp`.
  - **Cierre del cambio.** (1) `pnpm install --frozen-lockfile` y `pnpm turbo run lint typecheck test build --filter=!@confia/api` en verde sobre una
    copia limpia [BX20, BX21]; (2) `pnpm --filter @confia/ui test:a11y` en verde [BX14, BX17]; (3) `pnpm audit --audit-level high` en verde;
    (4) cobertura de `@confia/ui` ≥ 80 y de `src/format/**` ≥ 95; (5) `git diff main -- .github/workflows/ci.yml | Select-String '^-[^-]'` sin
    salida y `git diff --stat main -- apps/api` sin salida [BX18]; (6) ADR-0026 en estado Propuesto y reconciliado con `main`; (7) criterios de
    aceptación de la propuesta marcados uno por uno en `apply-progress.md`; (8) medición del PR 6 y de la cadena completa. Commit: `build(ui):
    enforce the packages/ui layer rules and the public component inventory`. Tamaño: nominal 250, realista 260 (con las notas documentales). —
    Escenarios BX25 a BX37, DS01 a DS03, DS61, DS62 a DS65, DS73, DS74. Siguiente paso: `sdd-verify`.

---

## Apéndice A: catálogo de identificadores de escenario

**`DS` (capacidad `design-system`).**

| Requisito | Identificadores y títulos | Tarea que los cierra |
|---|---|---|
| Entrada pública | DS01 importación desde el punto de entrada; DS02 el conjunto exportado coincide; DS03 componente fuera de alcance | 6.3 |
| Tokens en tres niveles | DS04 componente consume un primitivo; DS05 color literal; DS06 componentes reales solo semánticos; DS07 cambiar la marca no toca componentes | 1.3, 2.2, 4.1, 6.1 |
| Claro y oscuro | DS08 preferencia oscura sin atributo; DS09 el atributo gana; DS10 atributo oscuro sobre sistema claro; DS11 color solo en bloque oscuro; DS12 todos con valor base | 1.3 (estático), 3.2 (navegador) |
| Marca y tipografía | DS13 sin peticiones de fuentes a terceros; DS14 respaldo tipográfico | 1.3, 3.2 |
| Átomos | DS15 `IconButton` sin etiqueta; DS16 anillo de foco; DS17 `Button` en carga; DS18 solo lectura frente a deshabilitado; DS19 `Checkbox` y `RadioGroup` con teclado | 4.1, 4.2, 5.1 |
| `FormField` | DS20 etiqueta asociada; DS21 campo con error; DS22 descripción y error; DS23 obligatorio en texto; DS24 sin error | 5.2 |
| Estados | DS25 `EmptyState` sin acción; DS26 con acción; DS27 `ErrorState` sin reintento; DS28 anuncia y permite reintentar | 5.2 |
| `MoneyAmount` formatea | DS29 lempiras; DS30 dólares; DS31 cero; DS32 cero negativo; DS33 negativo; DS34 contable; DS35 máximo del contrato; DS36 más allá de `number`; DS37 cuatro decimales; DS38 menos de dos decimales; DS39 formato regional | 2.1, 6.1 |
| Sin aritmética | DS40 conversión a `number`; DS41 redondeo manual; DS42 código real cumple | 2.2, 6.1 |
| Rechazo visible | DS43 más de dos decimales; DS44 cadena no decimal; DS45 importe no cadena; DS46 moneda mal formada; DS47 texto del catálogo | 2.1, 6.1 |
| Lector de pantalla | DS48 tabular; DS49 nombre accesible con moneda; DS50 color por signo opcional; DS51 signo positivo | 2.1, 6.1 |
| `StatusBadge` | DS52 tres canales; DS53 `iconOnly`; DS54 icono sin anuncio doble; DS55 estado desconocido; DS56 variantes y temas | 6.2 |
| Catálogo de textos | DS57 texto por defecto; DS58 idioma sin traducción; DS59 catálogo completo; DS60 pruebas con el proveedor | 2.1 |
| Sin datos | DS61 renderizado sin proveedor de consultas ni red | 6.3 |
| Historias | DS62 cada componente con historia; DS63 historias de estado; DS64 límites de `MoneyAmount`; DS65 ambos temas | 3.2, 4.x a 6.x, 6.3 |
| axe en historias | DS66 historia inaccesible; DS67 contraste insuficiente; DS68 violación solo en oscuro; DS69 violación moderada; DS70 historias reales en verde; DS71 cero historias | 3.1, 3.2 |
| Pruebas de componente | DS72 casos límite de `MoneyAmount`; DS73 cada componente con prueba; DS74 comportamiento y no estructura | 3.2, 6.1, 6.3 |

**`BX` (capacidad `build-integrity`, delta de este cambio).**

| Requisito | Identificadores y títulos | Tarea que los cierra |
|---|---|---|
| Reglas de i18n y formato | BX01 literal en JSX; BX02 literal en atributo accesible; BX03 `toFixed`; BX04 `toLocaleString`; BX05 formato dentro de las utilidades; BX06 clave inexistente; BX07 clave no resoluble; BX08 código real sin infracciones | 2.1, 2.2 |
| Aritmética prohibida | BX09 conversión a `number`; BX10 aritmética sobre el importe; BX11 código real sin aritmética | 2.2, 6.1 |
| axe en CI | BX12 violación crítica o seria; BX13 componente sin historia; BX14 historias reales en verde; BX15 verificación vacía; BX16 violación moderada; BX17 ejecución local | 3.2, 3.3 |
| CI aditiva | BX18 el diff solo agrega; BX19 trabajos existentes intactos; BX20 las tareas no omiten `packages/ui`; BX21 instalación reproducible | 1.2, 3.3, 6.3 |
| Cobertura | BX22 por debajo del umbral; BX23 en el umbral; BX24 un componente sin pruebas baja la cobertura | 1.2 |
| Reglas de dependencia (modificado) | BX25 paquete importa de aplicación; BX26 portal importa el contrato administrativo; BX27 código que respeta las reglas; BX28 átomo importa molécula; BX29 átomo importa dominio; BX30 molécula importa dominio; BX31 molécula de dominio compone átomos y moléculas; BX32 TanStack Query; BX33 petición de red; BX34 ruta interna de contratos; BX35 valor de contratos; BX36 solo tipos; BX37 regla sin archivos | 2.2 (espejo), 6.3; BX25 a BX27 sin cambios, se re-ejecutan en cada cierre |

## Apéndice B: reconciliación de la especificación con el diseño (desviaciones a registrar en `apply-progress.md`)

| # | Diferencia | Decisión de este documento |
|---|---|---|
| R-1 | BX06 y BX07 piden que el fallo nombre «la clave, el archivo y el catálogo»; el diseño detecta la clave inexistente con `tsc` (tipo `UiKey`) | La prueba `keys.check.test.ts` afirma clave y archivo en el diagnóstico; el catálogo queda identificado por el tipo `UiKey` derivado de `locales/es-HN/ui.json`. Se registra como desviación menor de redacción del escenario, sin cambiar el comportamiento |
| R-2 | BX35 y BX36 distinguen valor de tipo en las importaciones de `packages/contracts`; el diseño usa solo reglas de núcleo de ESLint, que no distinguen | La distinción la hace dependency-cruiser (`tsPreCompilationDeps: true`, `dependencyTypesNot: ["type-only"]`) en 6.3; el espejo de ESLint de 2.2 cubre solo la ruta interna y TanStack Query. No se añade ningún complemento |
| R-3 | La especificación exige que una regla «incompleta» de axe no cuente como aprobada; el diseño no lo dice | La política (3.1) trata `incomplete` de `color-contrast` como bloqueante, con prueba |
| R-4 | DS04 y DS05 exigen rechazar `bg-blue-600` y `#2563EB` en componentes; el diseño solo cubre `--palette-` | Se añaden a las reglas de ESLint de 2.2 los selectores de literales visuales (hexadecimal, `rgb()`, `hsl()`, valores arbitrarios con color, clases de la paleta predeterminada) |
| R-5 | DS14 nombra `--font-family-sans`; `docs/ui-ux/01` define la variable de Tailwind como `--font-sans` en `@theme` (§14) y como `--font-family-sans` en la tabla de §7.1 | La prueba (1.3) verifica la pila de la variable que efectivamente está en `@theme`; se anota la equivalencia con el escenario |
| R-6 | DS63 y DS73 piden verificar historias de estado y archivos de pruebas por componente; el diseño solo automatiza la historia `Default` | `findComponentsWithoutTest` (3.2) y `story-states.test.ts` (6.3) automatizan ambos |
| R-7 | El diseño §8 sitúa `Button` en el corte 13a-3 | `Button` pasa a 13a-4 (4.1) y 13a-3 usa `Spinner` como primer componente real: es el más pequeño y mantiene el PR 3 más cerca de la banda; el criterio del diseño (ninguna comprobación vacía) se cumple |
| R-8 | La propuesta preveía de nueve a diez tareas | Son 15 por el tamaño real de seis PR; coincide con el límite y está declarado arriba |

## Preguntas abiertas

1. **Contingencia de tamaño.** La estimación realista (4 300 a 4 600 líneas) ocupa casi toda la capacidad de seis PR de 800 líneas, y los cortes 13a-3
   y 13a-5 proyectan por encima de la banda de 750. Las costuras nombradas absorben un exceso aislado, pero no dos a la vez. **¿Autoriza un séptimo PR
   de contingencia (sin tarea nueva: partiendo 3c o 5b en su propio PR) si la medición real de los PR 3 y 4 proyecta más de 4 600 líneas, o prefiere
   que se mantengan seis PR aceptando hasta 800 en los cortes 3 y 5?** Mientras no se dispare, el plan sigue sin consultar.
2. **Tiempo del trabajo `frontend verify`.** El trabajo existente tiene `timeout-minutes: 10` y ahora ejecutará `storybook build` dentro de `build`.
   Modificar ese valor no es aditivo. Se mide en 3.1; solo si el tiempo local se acerca al límite se consulta al propietario.
