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
