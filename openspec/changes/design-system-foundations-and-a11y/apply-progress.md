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
