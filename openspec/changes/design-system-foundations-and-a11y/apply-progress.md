# Progreso de aplicación: design-system-foundations-and-a11y

## Sondas (tarea 1.1), 2026-10-28

Resultado: S1, S2, S3, S4 y S5 cumplen su criterio. Tabla, versiones fijadas y notas en `design.md` §7 (nota fechada 2026-10-28).
Directorio de trabajo fuera del repositorio: `C:\Users\MARVIN\AppData\Local\Temp\claude\C--dev-confia\44248c6a-8b58-4eca-82e4-8594bfc7bebd\scratchpad\probes-13a\work`.
Tarea 1.1 marcada como terminada y confirmada en el corte 13a-1.

## Tarea 1.2: paquete `@confia/ui` (corte 13a-1), 2026-10-28

Rama `change/design-system-foundations-and-a11y-13a-1-package-and-tokens`. Línea base antes del cambio (turbo, `--force`): `config` 1 archivo y 4
pruebas, `contracts` 2 y 8, `tooling` 3 y 12. Después: idénticas; `ui` 3 archivos y 6 pruebas.

**ROJO** (contra el árbol sin código de producción, con la configuración compartida de `contracts` apuntando a `packages/ui`):

- `render-smoke.test.tsx`: `No test files found, exiting with code 1` con `include: src/**/*.test.ts`.
- `package-exports.test.ts`: `Error: ENOENT: no such file or directory, open 'C:\dev\confia-frontend\packages\ui\package.json'`.
- `tooling/src/workspace-guards.test.ts` no enumera paquetes del espacio de trabajo (fija ajustes de `pnpm-workspace.yaml`, `package.json` y `ci.yml`), así
  que no hay nada que ampliar; el tercer rojo previsto no aplica.

**VERDE**: `packages/ui` (`package.json`, `tsconfig.json`, `vitest.config.ts`, `eslint.config.js`, `src/index.ts`, `src/hooks/index.ts`,
`src/test/setup.ts`, `README.md`), `packages/config/vitest.js` (incluye `*.test.tsx`), catálogo de `pnpm-workspace.yaml` y `pnpm-lock.yaml`.

**REFACTOR**: resultados de `contracts`, `tooling` y `config` idénticos a la línea base (ver arriba).

**Demostraciones deliberadas.**

1. `src/lib/uncovered.ts` con una función sin prueba: `pnpm turbo run test --filter=@confia/ui` falla con
   `@confia/ui:test: ERROR: Coverage for lines (0%) does not meet global threshold (80%)` (igual para funciones, sentencias y ramas) y el archivo aparece
   en la tabla (`uncovered.ts | 0 | 0 | 0 | 0 | 2-5`), es decir, cuenta en el denominador [BX22, BX24]. Al quitarlo, la tarea vuelve a verde [BX23]. El
   archivo era nuevo: se eliminó y `packages/ui/src` quedó sin `lib/`.
2. Quitar `src/**/*.test.tsx` de `packages/config/vitest.js`: **no produjo «No test files found»** porque `package-exports.test.ts` sigue recogiéndose, y
   la ejecución salía en verde con la prueba de humo omitida en silencio (hallazgo real). Se añadió `src/test-collection.test.ts`, que afirma que todo
   `*.test.ts(x)` del disco coincide con el `include` compartido. Repetida la ruptura: falla por la aserción
   `AssertionError: expected [ 'src/render-smoke.test.tsx' ] to deeply equal []` [BX20]. Restaurado desde la copia: `cmp` sin salida.

**Cierre.** `pnpm install --frozen-lockfile`: `Lockfile is up to date`, sin cambios [BX21]. `pnpm turbo run lint typecheck test build --filter=!@confia/api`:
14 de 14 tareas correctas, con las cuatro tareas ejecutándose en `@confia/ui` (`--dry=json`) y 6 pruebas reales [BX20].

Desviaciones: (a) `packages/config/vitest.d.ts` y la condición `types` en `exports["./vitest"]` (necesarios para importar `confiaVitest` desde un
`vitest.config.ts` bajo `strict`); `packages/config/tsconfig.json` lo incluye para que ESLint lo analice. (b) `src/test-collection.test.ts` (ver
demostración 2). (c) La cobertura excluye también `src/test/**` (soporte de pruebas). (d) Con `src/index.ts` y `src/hooks/index.ts` vacíos la
cobertura es 0 de 0 y no falla; el umbral se vuelve efectivo con el primer componente.

## Tarea 1.3: tokens en tres niveles, temas e Inter (corte 13a-1), 2026-10-28

**ROJO** (pruebas y fixtures escritos sin `src/tokens/*.css` ni la ruta `./styles.css`): 11 de 17 pruebas fallan, por `ENOENT: no such file or
directory, open '...\packages\ui\src\tokens\index.css'` (6), `...\primitives.css` (5) y `...\semantic.css` (1), más
`AssertionError: expected { '.': './src/index.ts' } to deeply equal { '.': './src/index.ts', …(1) }` en `package-exports.test.ts` y
`AssertionError: expected [] to deeply equal ArrayContaining{…}` en `tokens.scan.test.ts`. Los siete archivos de pruebas fallan (los de módulo por
`ENOENT`).

**VERDE**: `index.css`, `primitives.css`, `semantic.css`, `themes.css`, `components.css`, `base.css`; `exports["./styles.css"]` →
`src/tokens/index.css`; dependencias `tailwindcss` 4.3.3 y `@fontsource-variable/inter` 5.3.0 en el catálogo; nota fechada en
`docs/ui-ux/01-tokens-de-diseno.md` §14. 34 pruebas del paquete en verde (28 en `src/tokens`). Tailwind admite `@import` después de `@theme` en `index.css`
(la compilación con `compile()` emite el CSS esperado, verificado en la salida).

**REFACTOR**: `__fixtures__/css.ts` es el único módulo de utilidades (lectura, análisis de reglas, resolución de `var()`, paridad, compilación) y lo
usan las seis pruebas. Lint y `tsc` en verde tras corregir dos plantillas con tipos no cadena.

**Demostraciones deliberadas** (copias en el directorio temporal de la sesión; `cmp` sin salida en las tres):

1. `--palette-primary-600` renombrado a `--color-primary-600` en `primitives.css`: `tokens.build.test.ts` falla con
   `AssertionError: expected '/*! tailwindcss v4.3.3 | MIT License …' not to match /\.bg-primary-600/` [DS04]. Fallan además otras tres pruebas
   (la variable `--palette-primary-600` ya no se emite y la cadena `--color-primary` queda sin valor).
2. Borrado el valor base de `--color-bg-canvas` en `semantic.css` (el diseño dice `--color-bg`, que no existe; el token real es `--color-bg-canvas`):
   `tokens.parity.test.ts` falla con `"--color-bg-canvas has no base value outside the dark blocks"` [DS11].
3. `--color-text-secondary` pasado a `--palette-neutral-400` en el tema claro: `tokens.contrast.test.ts` falla con
   `"--color-text-secondary on --color-bg-surface: 2.56:1, needs 4.5:1"`.

**Cierre.** `pnpm install --frozen-lockfile` sin cambios; `pnpm turbo run lint typecheck test build --filter=!@confia/api`: 14 de 14 tareas correctas
(`ui` 9 archivos y 34 pruebas; `config` 4, `contracts` 8, `tooling` 12, idénticos a la línea base).

**`pnpm audit --audit-level high`: FALLA, por causas previas a este corte.** 6 altas: `vite` 8.3.1 (GHSA-587h-7wmj-h5pm, vía `config > vitest`),
`zod` 4.6.5 (GHSA-mrhx-c7xc-m3w5, corregida en 4.6.6, vía `contracts`) y `undici` 7.30.0 (GHSA-xjj6-f33q-929c, -4w7w-5xrc-9h26, -2m5v-pch6-74hx,
-qrxm-fqhp-jwvm, corregidas en 7.30.1, vía `contracts > orval`). Las mismas versiones están en el archivo de bloqueo de `main` y ningún camino pasa por
`@confia/ui` ni por las dependencias nuevas: son avisos publicados después de fijar el catálogo. No se corrigen en este corte; decisión del propietario
(subir `zod`, ajustar el `overrides` de `undici` y subir `vitest`/`vite`, cada uno documentado).

Desviaciones: (a) los cuatro valores oscuros que seguían a `--color-primary-*` en `01` §14 pasan por alias `--dark-*` de `semantic.css`, porque la
prueba de recorrido prohíbe `--palette-` en `themes.css`; (b) `--font-sans` se declara en `semantic.css` y `base.css` solo contiene los `@font-face` y la
regla de `html`; los `@font-face` apuntan a `files/*.woff2` del paquete porque sus hojas declaran los siete subconjuntos (bare `url()` verificado con
Vite 8.3.4 en el directorio de sondas); (c) tokens de componente: solo `button-primary` y `status-badge` (siete estados), los que consumen 4.1 y 6.2;
(d) la guarda de contraste añade `--color-primary-fg` sobre `--color-primary` y sobre cada `-solid-bg`, pares de §5.3 no listados tal cual.

## Partición del corte 13a-1 en 13a-1 y 13a-1b, 2026-10-29

Medido con `git diff --shortstat main...HEAD -- . ':!openspec' ':!pnpm-lock.yaml'`, el corte completo (1.1, 1.2, 1.3) sumaba 1 202 líneas (1.2: 206;
1.3: 1 008), por encima del límite de 800. El propietario autorizó un séptimo PR el 2026-10-29. Referencia de respaldo con el estado previo:
`backup/13a-1-before-split` (`4841870`).

La costura propuesta (solo paridad y contraste con sus fixtures) dejaba 13a-1 en unas 980 líneas, porque esas dos pruebas pesan unas 220 con sus
fixtures. Se usó una costura más amplia, por archivos completos y con las pruebas junto a lo que verifican: 13a-1 conserva el paquete, los CSS,
`tokens.build.test.ts` y las utilidades mínimas de `css.ts`; 13a-1b recibe las cinco pruebas restantes y las utilidades que solo ellas usan.
Medida: 13a-1 con 738 líneas (734 añadidas y 4 borradas); 13a-1b con 470 (total 1 208). En 13a-1 la tarea 1.3 queda abierta
(parte A hecha); se cierra en 13a-1b.

En 13a-1b se cierra la tarea 1.3 (`[x]`): `pnpm turbo run lint typecheck test build --filter=!@confia/api` en verde, 14 de 14 tareas, `ui` 34 pruebas. Los tokens de paridad, contraste, cadena, recorrido y fuentes se verifican aquí.

## Corrección de la revisión de 13a-1, 2026-10-29

Revisión de confiabilidad (aprobada con observaciones), tres correcciones en 13a-1; la guarda de contraste de la cuarta va en 13a-1b.

1. `--color-primary-soft-bg` no se redefinía en modo oscuro y heredaba `primary-50` bajo el texto `primary-300` (cerca de 1,5:1). El documento `01` no definía
   un valor oscuro; se fija en `primary-900` mediante el alias `--dark-primary-soft-bg` de `semantic.css`, conectado en los dos bloques oscuros, y queda
   anotado con fecha en `docs/ui-ux/01` (nota de §14). El contraste lo mide la guarda de 13a-1b.
2. `--shadow-md` y `--shadow-xl` pasan a `--shadow-dark-md` y `--shadow-dark-xl` en los dos bloques oscuros (`01` §9.3 pide sombras propias en modo oscuro).
3. La prueba de la variante `dark:` era vacua (`themes.css` ya emite ambos selectores). Ahora compila `dark:bg-bg-canvas` y exige la regla acotada por
   `@custom-variant`. Rojo observado: sin el valor de los bloques oscuros fallaron 3 pruebas nuevas (`--color-primary-soft-bg`, `--shadow-md`,
   `--shadow-xl` en ambos bloques). Ruptura deliberada: borrar `@custom-variant dark` hace fallar la prueba ("the dark:bg-bg-canvas utility was not
   generated"); `index.css` restaurado y verificado con `cmp`.
4. `test-collection.test.ts` lee el `include` del proyecto `unit` de `vitest.config.ts` del paquete. Ruptura deliberada (`include` solo con `*.test.ts`)
   hace fallar la guarda; restaurado con `cmp`.

Cierre: `pnpm turbo run lint typecheck test build --filter=!@confia/api` 14 de 14; `ui` 4 archivos y 16 pruebas.

## Corrección de la revisión en 13a-1b, 2026-10-29

Tras fusionar 13a-1 (`e514310`), `tokens.contrast.test.ts` mide ahora el par `--color-primary-soft-fg` sobre `--color-primary-soft-bg` en el tema claro y en
los dos bloques oscuros (`themeDeclarations` acepta `"dark-media"`, además de `"dark"`). `primary-300` sobre `primary-900` da 5,74:1. Pares añadidos:
`--color-text-primary`, `-secondary`, `-tertiary` y `--color-text-link` sobre `bg-canvas` y `bg-surface-elevated` (la superficie ya estaba, salvo el enlace);
`--color-feedback-success|warning|danger|info` sobre la superficie (3:1); `--color-primary-fg` sobre `--color-primary-hover` y `-active`. Sin cubrir a propósito:
`--color-text-inverse` (no tiene fondo propio en `semantic.css`), `--color-text-disabled` y `--color-border-subtle` (exentos, `01` §5).
Rojo observado: quitar el alias `--dark-primary-soft-bg` de los dos bloques falla las pruebas con `"--color-primary-soft-fg on --color-primary-soft-bg:
1.66:1, needs 4.5:1"`; quitarlo solo del bloque de `prefers-color-scheme` falla únicamente la prueba `dark-media`. `themes.css` restaurado y verificado con `cmp`.
La prueba de paridad sigue en verde con el nuevo valor en ambos bloques.

Cierre: `pnpm turbo run lint typecheck test build --filter=!@confia/api` 14 de 14; `ui` 9 archivos y 39 pruebas.

## Tarea 2.1, catálogo `es-HN` tipado y `formatMoney` (13a-2, parte 2a), 2026-10-29

Rama `change/design-system-foundations-and-a11y-13a-2-catalog-and-lint-rules`, apilada sobre 13a-1b (`6b967d2`). Commit:
`feat(ui): add the typed es-HN catalog and the exact money formatter`. Medida: 761 líneas efectivas (757 añadidas y 4 borradas, sin `openspec` ni
`pnpm-lock.yaml`); la tabla del PR planeaba 300 a 370 para 2.1, así que 2.2 (reglas de ESLint) deberá ir en su propia porción.

**Catálogo.** `i18next` 26.4.4, `react-i18next` 17.0.16, `i18next-icu` 2.5.0 e `intl-messageformat` 12.1.3 en el `catalog:` y en `@confia/ui`;
`exports` añade `"./i18n"`. `ui.json` anidado, claves en inglés con puntos; `UiKey` se deriva del JSON; `useUiText().t` está restringido a `UiKey`, sin
`CustomTypeOptions`. `createUiI18n()` inicializa de forma síncrona (`initAsync: false`). `renderWithI18n` en `src/test/render.tsx`.

**`formatMoney`.** Un solo patrón `^-?\d+(?:\.\d{1,2}0*)?$` valida la cadena (como máximo dos decimales significativos, sobrantes ceros) y es el guardián de
tipo hacia `` `${number}` ``; la moneda debe cumplir `^[A-Z]{3}$` y estar en `Intl.supportedValuesOf("currency")`. Ningún camino llama a `Number`,
`parseFloat`, `parseInt` ni `Math.round` (la prueba lo espía). El cero negativo usa `signDisplay: "negative"` (`"exceptZero"` con `sign: "always"`).
DS34: como `es-HN` ignora `currencySign: "accounting"`, el negativo contable se arma como `(` + formato del valor con `signDisplay: "never"` + `)`,
solo por manipulación de cadenas, y solo si la cadena empieza por `-` y no es cero (`^-?[0.]+$`). Entrada fuera del contrato, objeto ausente o
configuración regional mal formada devuelven `{ kind: "invalid" }` sin lanzar excepción.

**Rojo observado** (`pnpm exec vitest run --project unit src/i18n src/format`, antes de crear los módulos): 5 archivos fallidos, 5 pruebas fallidas;
`Cannot find module './money'`, `'./create-ui-i18n'`, `'./resources'`, `'./use-ui-text'` y `Unused '@ts-expect-error' directive` en `keys.test-d.ts:17` y `:22`.
**Verde:** 5 archivos, 81 pruebas; luego `ui` 14 archivos y 121 pruebas, cobertura 100 % y umbral propio de 95 en `src/format/**`.

**Demostraciones deliberadas**, todas revertidas y verificadas con `cmp`:
1. `maximumFractionDigits: 0` (con el mínimo en 2, `Intl` lanza `RangeError` y el `catch` lo vuelve `invalid`): 30 pruebas en rojo, entre ellas DS29.
2. Patrón `(?:\.\d+)?` (sin la comprobación de sobrantes): rojo `DS43` en `"3200.005"`, `"3200.125"`, `"-3200.001"` («expected { kind: 'formatted' } to deeply equal
   { kind: 'invalid' }») y «never rounds».
3. Borrar `common.status.paid` de `ui.json`: 7 pruebas de Vitest en rojo (DS57, DS58, DS59 y otras) y `tsc` con TS2345 en `i18n.test.tsx` (28, 34, 40).
4. Extra: una llamada a `Number(amount)` en `money.ts` pone en rojo la prueba del espía (`[25, 0, 0, 0]`).

**Desviaciones.** (a) La prueba del plural ICU vive en `icu.test.ts` y no en `i18n.test.tsx`, para que el recorrido de DS59 (solo `*.tsx`, claves literales) no vea la
clave de prueba. (b) Los fixtures de violación son archivos `.fixture` verificados como archivo virtual con la API del compilador (`src/test/text-keys.ts`), de modo
que no entran en el `tsc` del paquete. (c) El diagnóstico de `tsc` nombra la clave y el archivo cuando TypeScript puede conocer la clave (literal o plantilla con
constante) y nombra `UiKey` y el archivo cuando se concatena con un `string` (Apéndice B, R-1).

Cierre: `pnpm install --frozen-lockfile` y `pnpm turbo run lint typecheck test build --filter=!@confia/api` 14 de 14; `pnpm audit --audit-level high` sin altas
(2 bajas y 2 moderadas).

## Tarea 2.2, reglas de ESLint de ADR-0011 y escáner de supresiones (13a-2, parte 2b), 2026-10-29

Rama `change/design-system-foundations-and-a11y-13a-2b-lint-rules-and-suppressions`, apilada sobre 13a-2a (`155512d`). Commit:
`feat(config): enforce the ADR-0011 and design-token rules on packages/ui`. Sin dependencias nuevas (solo reglas del núcleo de ESLint).

**Reglas** (`packages/config/eslint.js`, `uiSourceRules()`; `confiaEslint({ ui: true })` las activa en `packages/ui/eslint.config.js`):
- `no-restricted-syntax`, una lista completa de selectores por grupo de archivos (una entrada posterior reemplaza a la anterior): texto en JSX y atributos accesibles
  (`aria-label`, `aria-description`, `title`, `alt`, `placeholder`, `label`, también dentro de `{}`), `toFixed`/`toLocaleString` fuera de `src/format/**`, aritmética y
  conversión de importes en `src/format/**` y `src/domain/money-amount/**` (`Number`, `parseFloat`, `parseInt`, `Math.*`, `+` unario, `- * / % **` y sus asignaciones, `++`),
  cadenas `--palette-` fuera de `src/tokens/**` y literales visuales (hexadecimal, `rgb()`/`hsl()`/…, `--color-<paleta>-N`, clases de la paleta predeterminada).
- `no-restricted-globals`: `fetch`, `XMLHttpRequest`, `WebSocket`, `EventSource` en todo `packages/ui`.
- `no-restricted-imports`, **compuesta** con `contractImportRestrictions` en una entrada por grupo (`src/**`, `src/atoms/**`, `src/molecules/**`): bibliotecas de datos
  (`@tanstack/react-query`, `@tanstack/query-core`, `axios`, `ky`, `swr`) y espejo de capas (átomos sin moléculas ni dominio; moléculas sin dominio).
- `tooling/src/suppression-scanner.ts`: `eslint-disable`, `@ts-expect-error`, `@ts-ignore`, `@ts-nocheck`, `a11y: { disable`, `test: "off"` sin `ADR-NNNN` en la misma
  línea o la anterior, o con un ADR que no existe en `docs/adr/`.

**Rojo observado** (`pnpm --filter @confia/tooling exec vitest run src/eslint-ui-rules.test.ts src/suppression-citations.test.ts`, antes de crear nada): 2 archivos
fallidos; `eslint-ui-rules.test.ts` 72 pruebas en rojo y 1 en verde (`TypeError: uiSourceRules is not a function`); `suppression-citations.test.ts`
`Cannot find module './suppression-scanner'`.
**Verde:** 2 archivos, 77 pruebas; luego `tooling` 5 archivos y 89 pruebas. El recorrido real de `packages/ui` evalúa más de un archivo, incluye `src/format/money.ts` y
`src/i18n/*.ts`, y termina sin errores; el escáner ve `src/i18n/keys.test-d.ts` con su cita `ADR-0011`.

**Demostraciones deliberadas**, revertidas y verificadas con `cmp` contra una copia:
1. Quitar `toLocaleString` del selector de formato manual: rojo `BX04`.
2. Ampliar la excepción de formato de `src/format/**` a todo `src/**`: rojo `BX03` y `BX04`.
3. Quitar `contractPatterns` de la entrada compuesta: rojo `BX34` (dos errores esperados).

**Desviaciones.**
(a) Las reglas de núcleo no pueden imprimir el literal: el hallazgo nombra el archivo y apunta a línea y columna del literal con un mensaje por control; la prueba afirma
línea y columna.
(b) Una entrada de `no-restricted-syntax` con solo `["error"]` hereda las opciones de la anterior; un grupo sin controles se escribe `"off"` (fixtures y pruebas de `src/tokens`).
(c) Las pruebas de `src/tokens/**` (`tokens.contrast.test.ts` usa `toFixed(2)` en sus diagnósticos de contraste) quedan exentas de la regla de formato; el código de
`src/tokens/**` no. No se editó ningún archivo de `packages/ui/src`.
(d) Las pruebas, historias y `__fixtures__` quedan fuera de las reglas de aritmética de importes y de texto en JSX; los fixtures, de todas las de sintaxis.
(e) Pendiente: `eslint-plugin-react-hooks` 7.1.1 (S1 confirmó ESLint 10) no se añade todavía: `packages/ui/src/hooks/index.ts` está vacío y ninguna regla de la propuesta
depende de él; se incorpora con el primer componente que use ganchos (3.1).
(f) `window.fetch` y `globalThis.fetch` no se cubren (la regla de globales solo ve la referencia directa); queda como posible endurecimiento con `no-restricted-properties`.

Cierre: ver la entrada de cierre del commit; `pnpm turbo run lint typecheck test build --filter=!@confia/api` 14 de 14 y `pnpm audit --audit-level high` sin altas.

### Corrección de la revisión nativa en 13a-2b, 2026-10-29

Dos huecos reales de la puerta, corregidos con rojo previo. (1) Un `.tsx` no de prueba bajo `src/format/` perdía `JSX_TEXT` y `JSX_ACCESSIBLE_TEXT` porque las entradas de
`src/format` reemplazaban a la de `src/**/*.tsx`; rojo observado: el código con texto y `aria-label` literales devolvía `[]`. Ahora hay una entrada `src/format/**/*.tsx` que
los compone. (2) `importRules` fijaba `contractPatterns(false)`: con `confiaEslint({ portal: true, ui: true })` se perdía la prohibición del contrato administrativo en
`src/**`; rojo observado: la prueba con `{ portal: true }` no devolvió ningún hallazgo. `uiSourceRules({ portal })` lo propaga. Opcionales hechos: la aserción de columna usa
la posición real del literal en todos los casos, y la cobertura de importes añade `new Number`, `toPrecision`, `-` sobre un no literal, `++`, `**` y `/=`, cada una con el
mensaje propio del control. Cierre: `tooling` 92 pruebas en las dos suites de la tarea; `pnpm turbo run lint typecheck test build --filter=!@confia/api` 14 de 14.

## Tarea 3.1, Storybook, política de axe, guardia de MDX, `cn` y `Spinner` (13a-3, parte 3a), 2026-10-30

Rama `change/design-system-foundations-and-a11y-13a-3-storybook-and-a11y-gate`, desde `main` 8ad309a. Commit:
`feat(ui): add Storybook, the accessibility policy and the Spinner atom`. Medida: 538 líneas efectivas (526 añadidas y 12 borradas, sin `openspec` ni
`pnpm-lock.yaml`), por encima de las ~400 previstas; la costura natural si hay que partir es Storybook y política frente a `Spinner`.

**Implementado.** `.storybook/main.ts` (solo `../src/**/*.stories.tsx`, solo `addon-a11y`, telemetría desactivada, `@tailwindcss/vite` cargado en `viteFinal`),
`.storybook/preview.tsx` (tokens, i18n, barra de herramientas `light`/`dark` sobre `data-theme`, `axeRunOptions`), `src/a11y/policy.ts`, `src/lib/cn.ts`
(`extendTailwindMerge` con `font-weight` y `ease` propios), `Spinner` con `cva`, `build` = `storybook build --output-dir dist/storybook`. Catálogo: `storybook`,
`@storybook/react-vite`, `@storybook/addon-a11y` 10.6.2, `vite` 8.3.4, `@vitejs/plugin-react` 6.1.2, `@tailwindcss/vite` 4.3.3, `axe-core` 4.14.0,
`class-variance-authority`, `clsx`, `tailwind-merge`, `lucide-react`, `eslint-plugin-react-hooks` 7.1.1 (activo con la opción nueva `react` de `confiaEslint`;
verificado con un gancho condicional que da `react-hooks/rules-of-hooks`).

**Rojo observado** (`vitest run --project unit src/a11y src/lib src/atoms/spinner src/tokens/tokens.motion.test.ts`, con las dependencias ya instaladas): 4 archivos
fallidos por `Failed to resolve import "./policy"`, `"../../.storybook/main"`, `"./cn"` y `"./Spinner"`; `tokens.motion.test.ts` falla con
`expected 0 to be greater than 0`. **Verde:** `ui` 19 archivos y 151 pruebas, cobertura 100 %.

**Demostraciones deliberadas**, revertidas y verificadas con `cmp` (sin salida):
1. `"moderate"` en `BLOCKING_IMPACTS`: 3 pruebas en rojo ("blocks critical and serious impacts only", DS69 y "splits a mixed list").
2. `incomplete` tratado como aprobado (se elimina su recorrido): 3 pruebas en rojo, entre ellas "blocks an incomplete color-contrast result".
3. `src/Intro.mdx`: rojo en "has no MDX file anywhere in the package" (`expected [ 'src/Intro.mdx' ] to deeply equal []`); el archivo se borró.

**Medidas.** `storybook build` 9 s en local (límite del trabajo `frontend verify`: 10 min). `pnpm audit --audit-level high`: sin altas (2 bajas, 2 moderadas).

**Regresión hallada y corregida.** `dist/storybook` entraba en el recorrido de dependency-cruiser (69 a 85 módulos) y el caso "finds no violation in the real tree"
pasó de 2,9 s a 5,3 y 5,9 s bajo `turbo`, superando el límite de 5 s (falló 3 de 3; sin `dist` pasa en 2,9 s). Rojo: `does not cruise generated output under
dist/|coverage/|reports/` con `expected 17 to be +0`, `expected 4 to be +0` y `expected 1 to be +0`. Corrección: `.dependency-cruiser.cjs` excluye `dist`, `coverage` y
`reports` (solo exclusión, sin cambio de reglas). Con ella el caso real tarda 3,9 a 4,0 s bajo carga total; el margen frente a 5 s es de 1 s.

**Desviaciones.**
(a) Se eliminó el override `vite@>=8.3.0 <8.3.3` (de #211, GHSA-587h-7wmj-h5pm): `vite` es ahora dependencia directa en 8.3.4 y el override convertía los pares de
Vitest, Storybook y Tailwind en `8.3.3` exacto (`pnpm peers check` fallaba). El lockfile contiene una sola `vite`, 8.3.4, y `pnpm audit` no reporta `vite`.
(b) `createUiI18n` clona los recursos (`structuredClone`): i18next muta el objeto recibido y las instancias se contaminaban.
(c) `--spinner-animation` y `@keyframes confia-spin` en `components.css`: el tema reseteado no trae `animate-spin`; los documentos no dan valor (se eligió 1 s lineal).
(d) Bloque `prefers-reduced-motion` de `docs/ui-ux/01` §10 añadido a `base.css` (faltaba), con `tokens.motion.test.ts`.
(e) `partitionViolations(violations, incomplete = [])`: un impacto desconocido bloquea; solo `color-contrast` incompleto bloquea (Apéndice B, R-3).
(f) `turbo.json` no se modificó: `dist/**` ya cubre `dist/storybook`. `.gitignore` ya ignora `dist/` y `reports/`.
(g) `tsconfig` añade `vite/client` y `.storybook/**/*` (el patrón `.storybook` solo no lo encuentra el servicio de proyecto de ESLint).
(h) Pendiente para 3.2: el patrón `../src/**/*.stories.tsx` mostrará en Storybook los fixtures de `__fixtures__`.

Cierre: `pnpm install --frozen-lockfile` y `pnpm turbo run lint typecheck test build --filter=!@confia/api --force` 14 de 14, tres veces seguidas.

## Tarea 3.2, puerta de axe en Chromium, fixtures inaccesibles y comprobación de historias (13a-3, parte 3b), 2026-10-30

Rama `change/design-system-foundations-and-a11y-13a-3b-a11y-gate-and-ci`, desde 13a-3a (`e53657f`). Commits: `fix(ui): fail closed on unknown axe impacts and stop the cruise probe writing into packages`
(correcciones de la revisión de 13a-3a, ver abajo) y `test(ui): gate every story with axe in Chromium in both themes`.

**Implementado.** Proyecto `a11y` de Vitest (modo navegador, `@vitest/browser-playwright` 5.0.5 y `playwright` 1.64.1 en el catálogo, Chromium sin cabeza, `@tailwindcss/vite`),
`test:a11y` = `vitest run --project a11y`; `src/a11y/run-gate.ts` (`checkStoryAccessibility`, `checkStoryInAllThemes`, `describeBlocking`, `summarize`),
`discovery.ts` (`assertStoriesDiscovered`), `story-coverage.ts`, comandos personalizados `writeA11yReport` y `emulateColorScheme` (`browser-commands.d.ts`), fixtures
`Inaccessible`, `DarkOnlyLowContrast` y `ModerateOnly`, y el árbol `__fixtures__/tree` con un infractor de cada tipo. Pruebas: `gate.a11y.test.tsx`, `stories.a11y.test.tsx` (historias,
DS13 y DS08), `story-coverage.test.ts`, `discovery.test.ts`, `run-gate.test.ts` (jsdom).

**Rojo observado** (antes de crear el código): `story-coverage.test.ts` y `discovery.test.ts`: `Failed to resolve import "./story-coverage"` y `"./discovery"`;
`vitest run --project a11y`: `No projects were found ... The filter matched no projects: a11y`.
**Verde:** `test:a11y` 12 pruebas en Chromium; el informe `reports/a11y/report.json` lista 4 resultados (`Spinner` `Default` y `Sizes`, cada una en `light` y `dark`), sin bloqueantes
ni informados. `ui` unit: 22 archivos y 177 pruebas, cobertura sobre el umbral.

**Hallazgo útil.** En jsdom, texto sin estilo da `color-contrast` incompleto y la política lo bloquea (`run-gate.test.ts`): confirma por qué la puerta corre en Chromium y que lo
incompleto nunca pasa.

**Demostraciones deliberadas**, revertidas y verificadas con `cmp` (sin salida):
1. `color-contrast` desactivada en la ejecución de axe: rojo en «rejects insufficient contrast» y en «rejects a story that fails only in the dark theme» (`expected [] to include 'color-contrast'`) [DS67].
2. `THEMES = ["light"]`: rojo en «evaluates the light and the dark theme» (`expected [ 'light' ] to deeply equal [ 'light', 'dark' ]`) y en el fixture solo-oscuro [DS68].
3. Patrón de descubrimiento que no coincide con nada: `Found zero stories under atoms/, molecules/ and domain/ ... (ADR-0018)`, 1 archivo fallido [DS71].
4. `Inaccessible.stories.tsx` copiado a `src/atoms/`: `test:a11y` sale con código 1 y nombra `fixtures-inaccessible--missing-image-alt [light]: image-alt (critical)`, `...--low-contrast-text [light]: color-contrast (serious)` y `button-name` [BX12]; el archivo se borró.
5. `Spinner.stories.tsx` borrado: `story-coverage.test.ts` falla con `expected [ 'atoms/spinner/Spinner' ] to deeply equal []` [BX13].

**Decisiones.** (a) Los fixtures nunca llegan a Storybook: el patrón de `.storybook/main.ts` pasa a `../src/{atoms,molecules,domain}/**/*.stories.tsx` (el mismo que la puerta), con prueba en
`storybook-config.test.ts` que falló antes del cambio; el `storybook build` no contiene los fixtures. (b) El puerto de la API de Vitest, 63315, da `EACCES` en esta máquina: `api.port` = 51730,
con `CONFIA_VITEST_API_PORT` para cambiarlo (`port: 0` se ignora y vuelve al predeterminado). (c) El proyecto `unit` excluye `*.a11y.test.tsx` y `__fixtures__`;
`test-collection.test.ts` ahora exige que cada archivo de prueba lo recoja exactamente un proyecto. (d) `playwright install chromium` tarda más de 3 minutos en una descarga en frío (Chromium 1248).

**Correcciones de la revisión de 13a-3a** (commit aparte). R3-probe-dir-race: la sonda de salida generada ahora cruza un árbol temporal del sistema (`tooling/src/cruise-probe.ts`) y ya no crea ni borra
`packages/ui/dist|coverage|reports`; rojo: `Failed to resolve import "./cruise-probe"`; demostración: quitar `dist` de la exclusión de `.dependency-cruiser.cjs` pone en rojo
«does not cruise generated output under dist/». R3-unknown-impact-string: `REPORTED_IMPACTS = ["moderate","minor"]` como lista permitida; rojo: `REPORTED_IMPACTS is not iterable` y
`expected { blocking: [], reported: [ …(2) ] } to deeply equal { …(2) }`. R3-spinner-img-assertion: la prueba afirma `aria-hidden="true"` en el svg; no es protectora por sí sola, porque
lucide-react añade `aria-hidden` por defecto (quitarlo del componente no la pone en rojo).

**Medidas.** `storybook build` 4,8 s; `pnpm turbo run lint typecheck test build --filter=!@confia/api --force` 14 de 14 en 42 s; `test:a11y` 6 s con Chromium instalado;
`pnpm test` verde con `PLAYWRIGHT_BROWSERS_PATH` apuntando a una carpeta vacía (sin navegador). `pnpm audit --audit-level high`: sin altas (2 bajas, 2 moderadas).
**Tamaño:** supera el tope de 800 líneas efectivas desde 13a-3a (ver el informe); 3.3 no se inició.

## Tarea 3.3, trabajo de CI aditivo, tarea `test:a11y`, ADR-0026 y Storybook en el stack (13a-3, parte 3c), 2026-10-30

Rama `change/design-system-foundations-and-a11y-13a-3c-ci-adr-and-stack`, desde 13a-3b (`eec9f02`, PR #224). Commits: `fix(ui): leave the page as the accessibility gate found it and stop expanding excludes on disk`
(`2291bf9`, correcciones de la revisión de 13a-3b), `ci(ui): add the design system accessibility job` (`5c5e5e8`), `docs(adr): add ADR-0026 on Storybook and the accessibility gate` (`f00c424`) y
`fix(tooling): stop ESLint walking generated output and give the tooling tests a loaded-runner timeout` (`b7cb5a0`).

**Implementado.** Trabajo `design-system-accessibility` (`design system accessibility`, 15 min) añadido al final de `jobs:` con 36 líneas añadidas y 0 quitadas: `checkout`, `pnpm/action-setup` y `setup-node`
en los mismos SHA que `frontend`, `pnpm install --frozen-lockfile`, `pnpm --filter @confia/ui exec playwright install --with-deps chromium`, `pnpm turbo run test:a11y --filter=@confia/ui` y subida de
`packages/ui/reports/a11y/` con `if: always()` (`upload-artifact` con el SHA que ya usa `backend`). Tarea `test:a11y` en `turbo.json` (`dependsOn: ["^generate","generate"]`, `outputs: ["reports/a11y/**"]`).
`tooling/src/ci-workflow.test.ts` (11 pruebas). ADR-0026 en estado Propuesto, fila en `docs/adr/README.md`, fila de catálogo de componentes en `CLAUDE.md` y en `docs/01-arquitectura.md` §3.3, y línea de accesibilidad en §3.4.

**Rojo observado** (`ci-workflow.test.ts` antes de editar `ci.yml` y `turbo.json`): 6 de 11 en rojo, entre ellas `expected [ 'backend', 'backend-mutation', …(2) ] to deeply equal [ …(3) ]` (falta el trabajo),
`expected [] to deeply equal [ …(3) ]` (sin comandos del trabajo) y `expected undefined to deeply equal [ '^generate', 'generate' ]` (falta la tarea). Los tres trabajos existentes pasaron desde el inicio (BX19).
**Verde:** 11 de 11.

**Demostraciones deliberadas**, revertidas y confirmadas con `cmp` (sin salida) contra una copia: (1) quitar `if: always()` del paso de subida: rojo en «uploads the report folder even when the gate fails»
(`expected undefined to be 'always()'`) [BX14]; (2) cambiar el SHA de `setup-node` solo en el trabajo nuevo: rojo en «checks out, sets up pnpm and Node with the same action SHAs as the frontend job».
`git diff eec9f02 -- .github/workflows/ci.yml | grep -c '^-[^-]'` = 0 y los bytes previos del archivo son idénticos [BX18]. (`git diff main` da 9 líneas quitadas porque `main` local está atrasado respecto a la base de esta rama; no es una edición de este cambio.)

**ADR-0026.** El borrador se revisó contra design §11 y lo construido en 3.1 y 3.2 (etiquetas y política de `policy.ts`, incompleto de `color-contrast` que bloquea, impacto desconocido que bloquea, cero historias,
sin MDX, fixtures fuera del patrón, `story-coverage.test.ts` en `frontend verify`). Único cambio: se añadió al cumplimiento el escáner de supresiones (`suppression-citations.test.ts`), que design §11 lista y el borrador omitía
en esa sección. 0025 no se crea ni se renumera (pertenece a `background-jobs-with-db-scheduler`).

**Correcciones de la revisión de 13a-3b.** (1) `checkStoryAccessibility`: `GateStory.load?()` libera la raíz de React (las historias portables guardan el desmontaje en una lista global que solo corre en el siguiente `run` o
`load`) y un `finally` restaura `data-theme` y quita el contenedor; rojo: `expected [ 'mounted' ] to deeply equal [ 'mounted', 'unmounted' ]` (Chromium) y 4 rojos en jsdom
(`expected 'light' to be 'dark'`, `expected true to be false`, `expected 'dark' to be 'light'`, `expected [] to deeply equal [ 'load-while-attached' ]`). Costo aceptado: `load()` vuelve a ejecutar cargadores y `beforeEach` de la historia,
y sus limpiezas quedan pendientes hasta el siguiente `run`; ninguna historia actual los usa. (2) DS08 con `try/finally`: sin rojo, porque solo se observa si la afirmación falla a mitad; se verificó que la prueba sigue en verde.
(3) `cruise-probe.test.ts` compara con `realpathSync(tmpdir())`; rojo reproducido con `TEMP` apuntando a una unión de directorios (`expected false to be true`), verde con ella y sin ella. (4) `src/test/api-port.ts` (`parseApiPort`):
15 pruebas; vacío, NaN, 0, negativo, 65536, decimal y hexadecimal lanzan `CONFIA_VITEST_API_PORT must be a whole number from 1 to 65535`; rojo: `Failed to resolve import "./api-port"`; `vitest.config.ts` importa con extensión `.ts`
(`allowImportingTsExtensions` en el `tsconfig` de `ui`) para que el cargador nativo de Vite no avise. (5) `src/test/collection.ts` (`collectedFiles`) compara con `path.matchesGlob` contra la lista de candidatos y ya no expande exclusiones con `globSync`;
rojo: `Failed to resolve import "./collection"`.

**Hallazgos de carrera, corregidos en `b7cb5a0`** (aparecieron al repetir `turbo run lint typecheck test build` con la máquina cargada): (a) `tooling` agotaba los 5 s de Vitest en `dependency-rules.test.ts` y en `cruise-probe.test.ts`
(la primera tarda 0,8 s sola); `tooling/vitest.config.js` fija `testTimeout: 30_000`. (b) `eslint .` de `ui` abortó una vez con `ENOENT ... packages/ui/coverage/src/i18n` porque `test --coverage` reescribía `coverage/` durante el recorrido;
`packages/config/eslint.js` ignora ahora `coverage/**` y `reports/**`, con `tooling/src/eslint-ignores.test.ts` (rojo: 2 de 5 antes del cambio). Después de ambos cambios: 3 ejecuciones forzadas seguidas en verde, sin navegador.

**Dependencia.** `tooling` declara `yaml` (catálogo, 2.9.1) para leer el flujo de trabajo: el paquete ya estaba en el lockfile como dependencia transitiva de Vite, así que el `pnpm-lock.yaml` solo gana las 6 líneas de la declaración y ningún paquete nuevo.
`pnpm install --frozen-lockfile` en verde.

**Medidas.** `pnpm turbo run lint typecheck test build --filter=!@confia/api --force` con `PLAYWRIGHT_BROWSERS_PATH` en una carpeta vacía: 14 de 14 en 38 a 41 s. `pnpm turbo run test:a11y --filter=@confia/ui --force`: 3 archivos, 13 pruebas, 6,4 s con Chromium
instalado. `pnpm audit --audit-level high`: código 0 (2 bajas, 2 moderadas). **Tamaño:** 608 inserciones y 37 eliminaciones = 645 líneas efectivas sobre 13a-3b (sin `openspec` ni lockfile), bajo el tope de 800.
**Pendiente del propietario:** el empuje; la observación del trabajo nuevo en el remoto y la demostración remota [BX12] requieren que el trabajo corra en GitHub. El tiempo de la descarga en frío de Chromium en CI sigue sin medirse.

## Tarea 4.1 parte A, `Button` e `IconButton` (13a-4a), 2026-10-31

**Costura.** El trabajo de 4.1 completo midió 836 líneas efectivas (> 800). El propietario aprobó el 2026-10-31 mover la costura del PR 4 en lugar de añadir un PR: 4a = `Button` + `IconButton`; 4b = `Label` + `Badge` + 4.2. 4.1 no se marca `[x]` aquí: se cierra en 4b.

**Implementado.** `src/atoms/button` (variantes primary, secondary, subtle, destructive y link; tamaños sm, md y lg; `loading` enfocable con `aria-busy` y `Spinner`, sin `disabled`, sin segunda invocación ni segundo envío), `src/atoms/icon-button` (cuadrado; `label` obligatorio, `aria-label`/`aria-labelledby` omitidos del tipo, ADR-0018), `src/lib/focus-ring.ts` (anillo `--color-border-focus`, compartido), 10 variables `--button-*` en `components.css` (referencian semánticos; sin valor oscuro propio) y `atoms/atoms.lint.test.ts` (DS06: ESLint sobre los átomos presentes, conjunto > 0 e incluye cada componente).

**ROJO.** `vitest run --project unit src/atoms`: `6 failed | 1 passed` (`Cannot find module ./IconButton`, entre otros); `tsc`: TS2307 y dos `TS2578 Unused @ts-expect-error directive` en `IconButton.test-d.ts` (líneas 11 y 20).

**Demostraciones** (revertidas, `cmp` sin diferencias contra copias): (1) `disabled={loading || rest.disabled}` en `Button`: 2 pruebas DS17 en rojo; (2) `label?` opcional en `IconButton`: `tsc` TS2578 sobre el `@ts-expect-error` sin uso; (3) `bg-blue-600` en `Badge.tsx` (aplicada con el conjunto completo; la regla es la misma): ESLint `Visual literal` [DS04]; se repite en 4b con `Badge`.

**Verificación en 4a aislada** (sin `Label` ni `Badge` en el árbol): `pnpm install --frozen-lockfile` sin cambios; `pnpm turbo run lint typecheck test build --filter=!@confia/api`: 14 de 14; ui: 29 archivos y 236 pruebas; `pnpm --filter @confia/ui test:a11y`: 3 archivos y 29 pruebas, 36 entradas (18 historias x 2 temas), cero violaciones; `pnpm audit --audit-level high`: código 0. **Tamaño:** 553 inserciones (sin `openspec` ni lockfile).

## Tarea 4.1 parte B, `Label` y `Badge` (13a-4b), 2026-10-31

**Implementado.** `src/atoms/label` (`<label>` nativo con `htmlFor`; sin `radix-ui`, que aún no está en el catálogo y solo aportaría evitar la selección por doble clic; entra con 5.1) y `src/atoms/badge` (variantes neutral, primary y outline; tamaños sm y md; ranura `icon` decorativa; siempre texto; `StatusBadge` de 6.2 aportará sus `--status-badge-*` por `className`), 8 variables `--badge-*` en `components.css`, exportes en `src/index.ts` y `atoms.lint.test.ts` ampliada a los cuatro componentes. El hover del destructivo (4a) usa `brightness` por no existir token oscuro de hover rojo. Sin textos de interfaz nuevos.

**Demostración 3** (revertida, `cmp` sin diferencias): `bg-blue-600` en `Badge.tsx`: ESLint `Visual literal ... (design.md decision 6)` en la línea 11 [DS04].

**Verificación en 4b:** `pnpm turbo run lint typecheck test build --filter=!@confia/api`: 14 de 14; ui: 31 archivos y 254 pruebas; `test:a11y`: 3 archivos y 37 pruebas, 52 entradas (26 historias x 2 temas), cero violaciones; `pnpm audit --audit-level high`: código 0. **Tamaño de 4b:** 286 inserciones y 3 eliminaciones (289 efectivas) sobre 4a; el total de 4.1 es de 836 inserciones (553 + 283 netas). Con esto 4.1 queda cerrada.
