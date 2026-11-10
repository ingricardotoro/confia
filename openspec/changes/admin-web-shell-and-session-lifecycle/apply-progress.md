# Progreso de aplicación: shell de `apps/admin-web` y vida completa de la sesión (13b-2)

- **Modo:** TDD estricto. Árbol de trabajo `C:\dev\confia-frontend`, rama `change/admin-web-shell-and-session-lifecycle-1-session-core`.
- **Estrategia de entrega:** `stacked-to-main` (confirmada por el propietario el 2026-11-10, sin excepción de tamaño).
- **Estado:** tarea 1 comprometida con excepción de tamaño aceptada por el propietario (ver «Excepción de tamaño»); tarea 2 en curso en su propio PR apilado.

## Tareas

| Tarea | Estado | Commit |
|---|---|---|
| 1 (1.1 a 1.7) | Hecha, verificada y comprometida | ver «Commits» |
| 2 (2.1 a 2.5) | No empezada | ninguno |

## Evidencia del ciclo TDD (tarea 1)

| Subtarea | Archivo de prueba | Capa | Red de seguridad | ROJO observado (árbol sin código de producción) | VERDE | Refactor |
|---|---|---|---|---|---|---|
| 1.1 cliente HTTP | `src/shared/api/http.test.ts` | Unidad | 16 archivos, 253 pruebas en verde antes de tocar nada | `vitest run src/shared/api/http.test.ts`: 4 fallan, 39 pasan. Fallan AW65 (tres casos) y AW66 por aserción: el resultado `ok` no trae `serverDate` ni `receivedAt` | Sí | Ver nota 3 |
| 1.2 reloj del servidor | `lifecycle/server-clock.test.ts` | Unidad | N/A (archivo nuevo) | `Failed to resolve import "./server-clock.ts"` | Sí | Sin cambios |
| 1.3 almacén de plazos | `deadline-store.test.ts` | Unidad | N/A (archivo nuevo) | `Failed to resolve import "./deadline-store.ts"` | Sí | Sin cambios |
| 1.4 planificador | `lifecycle/planner.test.ts` | Unidad | N/A (archivo nuevo) | `Failed to resolve import "./planner.ts"` | Sí | Sin cambios |
| 1.5 reglas de dependencia | `tooling/src/dependency-rules.test.ts` | Unidad (cruiser) | 241 pruebas de la suite T en verde | 4 fallan: las dos reglas rechazan su violación (2) y los dos casos H16 de fixture (2); `Tests 4 failed \| 45 passed (49)` | Sí | Ver nota 2 |
| 1.6 / 1.7 | las anteriores | | | | Focalizadas: 19 archivos, 311 pruebas | Lint y typecheck del paquete en verde |

### Notas honestas

1. **Casos de caracterización, no de rojo.** En 1.1 pasan antes de implementar los casos AW67 con cabecera `abc` y vacía y los de `problem`, `network` y `unexpected-response`, porque la ausencia ya era el comportamiento actual. Están como guarda de no regresión, no como rojo.
2. **Hallazgo de la primera pasada de VERDE.** Mi primera implementación de `serverDateOf` usaba `Date.parse`, que normaliza el 31 de febrero a marzo y acepta ISO. Tres casos de AW67 (día imposible, ISO, y la variante de hora imposible ya cubierta por `NaN`) lo mostraron en rojo. Se corrigió con un lector de la fecha IMF-fixdate que relee las partes y exige que coincidan. También falló mi prueba de política (un plazo de inactividad anterior a la ventana absoluta, error del dato de prueba) y se corrigió el dato, no la aserción.
3. **Aserciones de valor conservadas.** Seis `toEqual` sobre resultados `ok` (`http.test.ts` líneas 43, 81 y 296; `auth-operations.test.ts` líneas 148 y 162) pasaron a `toMatchObject` con los mismos valores esperados, autorizado por el coordinador. No se debilitó ningún valor.
4. **Sin rojo posible para `auth-operations.ts`.** El archivo ya devolvía el `ApiResult` del transporte; solo recibió un comentario y una prueba de propagación (AW65) que pasa en cuanto `http.ts` cambia. Declarado: no hay rojo propio.
5. **Límite de dependency-cruiser.** El cruce no registra dependencias hacia `node_modules` en el árbol real (ya lo documenta BX61). `import "react"` solo se rechaza donde no resuelve, o sea, en las fixtures. Para que H16 proteja el árbol real, se agregó una prueba de inventario sobre los especificadores de `planner.ts` y `policy.ts` en `tooling/src/dependency-rules.test.ts`, con una comprobación de que el lector sí encuentra un paquete (`planner.test.ts` importa `vitest`).
6. **Dos decisiones del planificador** que el diseño no fija: (a) en `absolute-warning` y `absolute-acknowledged`, `endsAt` es el menor de los dos plazos (SS132), porque es el que termina la sesión; (b) `wakeAt` en las fases con diálogo es el siguiente segundo entero **de la cuenta atrás** (alineado a `endsAt`), nunca posterior a `endsAt`.

## Demostraciones deliberadas (tarea 1)

Procedimiento: copia del archivo, ruptura, ejecución, restauración y `cmp` idéntico. `git diff` no muestra la ruptura.

| | Ruptura | Rojo observado (por aserción de resultado) | `cmp` |
|---|---|---|---|
| a | `planner.ts`: `now >= idle` pasa a `now > idle` | `planner.test.ts`: 2 fallan, «is expired exactly at the idle deadline» y «takes the idle reason when only the idle deadline has passed inside the absolute window» | idéntico |
| b | `server-clock.ts`: una cabecera inválida fija el desfase en cero | `server-clock.test.ts`: falla SS140, `expected 1794306600000 to be 1794306780000` | idéntico |
| c | `planner.ts` con `import { useState } from "react"` | `dependency-rules.test.ts`: falla «H16: planner.ts imports only files of its own folder», `expected [ 'react' ] to deeply equal []` | idéntico |

La parte de (c) que pedía que fallara la regla del cruiser sobre el árbol real **no es observable** por el límite de la nota 5. La regla `admin-session-lifecycle-planner-is-pure` se demuestra sobre la fixture (paso 1.5), y el árbol real lo cubre la prueba de inventario.

## Suites completas (tarea 1)

| Suite | Comando | Resultado |
|---|---|---|
| U | `pnpm --filter @confia/admin-web test` | 73 archivos, 815 pruebas en verde, 2 `todo` preexistentes, cero errores de tipos. `planner.ts`, `server-clock.ts`, `deadline-store.ts` y `policy.ts` al 100 % |
| E | `pnpm --filter @confia/admin-web test:e2e` | 96 en verde (los recorridos de 13b-1, incluido SS19, siguen en verde con el cambio de `http.ts`) |
| T | `pnpm --filter ./tooling test` | 9 archivos, 293 pruebas en verde |
| Lint y tipos del paquete | `pnpm --filter @confia/admin-web lint` y `typecheck` | sin errores |
| PR | `pnpm turbo run lint typecheck test build --filter=!@confia/api` | 18 de 18 tareas de turbo correctas, 0 fallos; 11 salieron de la caché de turbo y 7 se ejecutaron; no verifiqué cuáles |

Guardas: `git ls-files -co --exclude-standard apps | grep -E '\.spec\.(ts|js)$'` devuelve 0 archivos; `git diff --name-only d733277 -- apps/api packages/contracts` está vacío. Los puertos 5173, 8099 y 4173 estaban libres antes de E.

## Medida de tamaño

Comando: `git add -N . && git diff --numstat -M d733277 -- . ':(exclude)openspec' ':(exclude)pnpm-lock.yaml' ':(exclude)packages/contracts/src/generated'`.

| Archivo | Adiciones | Eliminaciones |
|---|---|---|
| `.dependency-cruiser.cjs` | 25 | 0 |
| `deadline-store.test.ts` / `deadline-store.ts` | 136 / 62 | 0 |
| `lifecycle/planner.test.ts` / `planner.ts` | 187 / 66 | 0 |
| `lifecycle/policy.test.ts` / `policy.ts` | 23 / 15 | 0 |
| `lifecycle/server-clock.test.ts` / `server-clock.ts` | 82 / 24 | 0 |
| `auth-operations.test.ts` / `auth-operations.ts` | 13 / 2 | 2 / 1 |
| `http.test.ts` / `http.ts` | 99 / 43 | 3 / 3 |
| `dependency-fixtures/README.md` y dos fixtures | 6, 5, 5 | 0 |
| `tooling/src/dependency-rules.test.ts` | 28 | 0 |
| **Total** | **821** | **9** → **830** |

**La tarea 1 sola ya supera las 800 líneas del PR (830).** Es un exceso pequeño (hasta 880), así que la regla común pide consultar al propietario como excepción y **no comprometer** hasta que decida. Aproximadamente 330 de esas líneas son pruebas con los casos límite que exigen las tareas (tabla de H2 en el límite exacto, 1 ms antes y 1 ms después), y no se recortan. La tarea 2 añadiría cientos de líneas más, así que el PR 1 como `1 + 2` no cabe de ningún modo: la tarea 2 pasa a ser su propio PR apilado.

## Excepción de tamaño

El propietario aceptó el 2026-11-10 una excepción de tamaño (`size:exception`) para la tarea 1: **830 líneas** (821 adiciones y 9 eliminaciones) frente al tope de 800, dentro del margen de consulta de 880. No se recortaron pruebas ni comentarios. La tarea 1 es el PR 1 de la cadena. La tarea 2 va en su propio PR apilado (PR 2 de la cadena renumerada: el antiguo PR 2 pasa a PR 3, y así los siguientes).

## Commits

| Tarea | Commit |
|---|---|
| 1 | `feat(admin-web): add session deadlines, server clock and lifecycle planner` (hash en el mensaje de cierre de la tarea 2) |
