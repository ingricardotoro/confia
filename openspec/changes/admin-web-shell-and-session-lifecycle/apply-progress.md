# Progreso de aplicación: shell de `apps/admin-web` y vida completa de la sesión (13b-2)

- **Modo:** TDD estricto. Árbol de trabajo `C:\dev\confia-frontend`, rama `change/admin-web-shell-and-session-lifecycle-1-session-core`.
- **Estrategia de entrega:** `stacked-to-main` (confirmada por el propietario el 2026-11-10, sin excepción de tamaño).
- **Estado:** tarea 1 comprometida (`ae218a0`) con excepción de tamaño aceptada por el propietario. Tarea 2 partida y comprometida en dos commits apilados, 2a (`04f9b84`) y 2b (`e0c18b9`), por aprobación del propietario (ver «Corte aplicado de la tarea 2»).

## Tareas

| Tarea | Estado | Commit |
|---|---|---|
| 1 (1.1 a 1.7) | Hecha, verificada y comprometida | `ae218a0` |
| 2 (2.1 a 2.5) | Hecha, verificada y comprometida en dos partes | 2a `04f9b84`, 2b `e0c18b9` |

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
| 1 | `feat(admin-web): add session deadlines, server clock and lifecycle planner` `ae218a0` |

---

# Tarea 2: programador de un solo temporizador y operaciones nuevas de `AccessSession`

- **Rama:** `change/admin-web-shell-and-session-lifecycle-2-session-scheduler`, creada sobre `ae218a0` (PR 2 de la cadena renumerada: el antiguo PR 2 pasa a PR 3, y así los siguientes; no hizo falta tocar `tasks.md` más que para marcar subtareas).
- **Estado:** implementada, verificada y comprometida en dos partes (ver «Corte aplicado de la tarea 2»).

## Evidencia del ciclo TDD (tarea 2)

| Subtarea | Archivo de prueba | Capa | Red de seguridad | ROJO observado (árbol sin código de producción) | VERDE |
|---|---|---|---|---|---|
| 2.1 programador | `lifecycle/scheduler.test.ts` | Unidad | N/A (archivo nuevo) | `Failed to resolve import "./scheduler.ts"` | 25 pruebas |
| 2.2 `AccessSession` | `access-session.deadlines.test.ts` | Integración (operaciones y transporte reales sobre MSW) | 253 de la tarea 1 más lo posterior, en verde antes de modificar | 26 fallan: `session.getDeadlines is not a function` | 27 pruebas |
| 2.2 coordinador | `refresh-coordinator.test.ts` | Integración | 11 pruebas del archivo en verde | 2 fallan por aserción: el resultado `refreshed` no trae `serverDate` ni `receivedAt` | Sí |
| 2.3 SS19 precisado | `lifecycle/session-lifecycle.flow.test.ts` | Integración (sesión, programador y transporte reales, reloj y temporizadores de Vitest) | N/A (archivo nuevo) | `Failed to resolve import "./scheduler.ts"` | 7 pruebas |
| 2.4 superficie pública | `lifecycle/index.test.tsx` | Unidad | N/A (archivo nuevo) | `Failed to resolve import "./index.ts"`, observado apartando `index.ts` un momento (se escribió junto con `scheduler.ts`; lo declaro) | 3 pruebas |

### Notas honestas

1. **El ROJO de 2.1 y 2.3 es de importación**, no de aserción, porque el módulo no existía. Las aserciones de resultado se demuestran con las rupturas deliberadas de abajo.
2. **Mi primera prueba de SS19 no habría detectado un refresco** (hallazgo de la demostración c): sin una ruta registrada para `REFRESH`, MSW rechaza la petición sin grabarla y el contador daba cero. Se registró la ruta en la prueba de flujo para que cualquier refresco quede grabado, y la demostración pasó a rojo.
3. **Dos errores de mis pruebas, corregidos sin tocar aserciones de valor:** leí la sesión actual tras `extend` (esa respuesta reemplaza los plazos, por diseño), y los programadores de pruebas anteriores seguían escuchando el `document` global; ahora cada uno se dispone con su prueba.
4. **Lint y tipos:** reglas tipadas del repo obligaron a usar propiedades de función en `LifecycleScheduler` (se entrega a React sin `this`), una función `isAuthenticated` para que el estrechamiento de `view` no sobreviva a un `await`, y `String(...)` en la clave de la instantánea.
5. **Desviación de alcance: `applyFromOtherTab` no se implementó en esta tarea.** `tasks.md` lo lista en los archivos de la tarea 2, pero sus especificaciones (SS177 a SS184) y su tipo `SessionChannelMessage` son de la tarea 3, que lo cita expresamente («`applyFromOtherTab` consume mensajes validados»). Se hará allí.
6. **El programador no se conecta todavía a la aplicación** (`session/index.ts` solo reexporta el tipo `ExtendOutcome`). La conexión es de la tarea 3. Conectarlo ahora haría que los recorridos de 13b-1 SS19, SS124 y SS125 (`signed-in.e2e.ts`, que invierte la tarea 11 y que no puedo tocar) fallaran. **Aviso para la tarea 3:** al conectarlo, esos tres recorridos van a fallar hasta que se inviertan, así que la tarea 3 no podrá cerrar con **E** en verde sin resolver ese orden.
7. **Decisión de diseño:** `extend` devuelve `ended` sin enviar nada cuando la sesión no está completa, y devuelve `ended` sin aceptar el token cuando la sesión terminó mientras el refresco estaba en vuelo (una respuesta tardía no resucita la sesión).
8. **Cobertura:** `scheduler.ts` queda con 96,55 % de sentencias y 93,75 % de ramas. La rama sin cubrir (línea 107) es la guarda `deadlines === undefined` de `dismissAbsoluteWarning`, inalcanzable con un aviso absoluto abierto; se deja como defensa. El resto de la tarea no tiene un umbral por archivo.

## Demostraciones deliberadas (tarea 2)

Procedimiento: copia, ruptura, ejecución, restauración y `cmp` idéntico (todas verificadas con `cmp`).

| | Ruptura | Rojo observado (por aserción de resultado) |
|---|---|---|
| a | `scheduler.ts` deja de cancelar el temporizador pendiente antes de planificar (varios temporizadores a la vez; el programador no usa `setInterval` porque su `Timers` no lo ofrece, así que esta es la forma real de violar SS141) | `scheduler.test.ts` y el flujo: 9 fallan, entre ellas «SS141: at no moment … more than one timer pending» con `expected 2 to be 1`, `expected 4 to be 1` |
| b | `endByTimeout` espera el `DELETE` antes de cerrar la sesión local | `access-session.deadlines.test.ts`: 5 fallan, entre ellas SS167 (`expected { status: 'authenticated' } to deeply equal { status: 'anonymous', … }`) y «does not wait for the DELETE» |
| c | un temporizador llama a `extend()` en cada planificación | `session-lifecycle.flow.test.ts`: 5 fallan, entre ellas SS19 precisado con `expected [ … ] to have a length of +0 but got 603` y SS150 con `[ 'POST', 'POST', … ]` frente a `[ 'POST' ]` |

## Suites completas (tarea 2)

| Suite | Resultado |
|---|---|
| U `pnpm --filter @confia/admin-web test` | 77 archivos, 872 pruebas en verde, 2 `todo` preexistentes, cero errores de tipos |
| E `pnpm --filter @confia/admin-web test:e2e` | 96 en verde (la aplicación no cambió de comportamiento: el programador no está conectado) |
| T `pnpm --filter ./tooling test` | 9 archivos, 293 pruebas en verde |
| Lint y tipos del paquete | sin errores |
| PR `pnpm turbo run lint typecheck test build --filter=!@confia/api` | ver el cierre más abajo |

Guardas: 0 archivos `*.spec.*`; sin cambios en `apps/api` ni en `packages/contracts` respecto de `ae218a0`.

## Medida de tamaño (tarea 2 sola, respecto de `ae218a0`)

| Archivo | Adiciones | Eliminaciones |
|---|---|---|
| `session/access-session.deadlines.test.ts` | 392 | 0 |
| `session/lifecycle/scheduler.test.ts` | 331 | 0 |
| `session/lifecycle/session-lifecycle.flow.test.ts` | 171 | 0 |
| `session/lifecycle/scheduler.ts` | 128 | 0 |
| `session/access-session.ts` | 82 | 3 |
| `session/lifecycle/index.test.tsx` | 57 | 0 |
| `session/refresh-coordinator.test.ts` / `refresh-coordinator.ts` | 24 / 14 | 0 / 4 |
| `session/lifecycle/index.ts` | 13 | 0 |
| `session-secrets.flow.test.tsx` | 4 | 0 |
| `session/index.ts` | 1 | 0 |
| **Total** | **1 217** | **7** → **1 224** |

Unas 979 de esas líneas son pruebas con los casos que exigen las especificaciones; no se recortan. **Supera 800 y también el margen de consulta de 880, así que la regla obliga a cortar, no a una excepción.** No se comprometió.

## Corte propuesto de la tarea 2 (decisión pendiente del propietario)

Dos commits y dos PR apilados, ambos por debajo de 800; el segundo depende del primero porque el programador lee `getView`, `getDeadlines`, `subscribeDeadlines` y `endByTimeout` de `AccessSession`.

| Parte | Contenido | Líneas aprox. |
|---|---|---|
| 2a | `access-session.ts` (`extend`, `endByTimeout`, `getDeadlines`, `subscribeDeadlines`, registro de plazos, comentario de invariante), `refresh-coordinator.ts`, sus pruebas, nota de `session-secrets.flow.test.tsx` y `session/index.ts` | 524 |
| 2b | `scheduler.ts`, `lifecycle/index.ts`, sus pruebas y el flujo SS19 | 700 |

## Resultado de PR (tarea 2)

`pnpm turbo run lint typecheck test build --filter=!@confia/api`: 18 de 18 tareas de turbo correctas, 0 fallos. 14 salieron de la caché de turbo y 4 se ejecutaron; no verifiqué cuáles.

## Corte aplicado de la tarea 2

El propietario aprobó el corte 2a/2b el 2026-11-10. Ramas apiladas, sin empujar: `change/admin-web-shell-and-session-lifecycle-2a-session-operations` sobre `ae218a0` y `change/admin-web-shell-and-session-lifecycle-2b-session-scheduler` sobre 2a. La rama previa `...-2-session-scheduler` quedó en `ae218a0` y no tiene trabajo propio (se puede descartar).

| Parte | Commit | Contenido | Líneas (adiciones, eliminaciones, suma) |
|---|---|---|---|
| 2a | `04f9b84` `feat(admin-web): add extend and timeout sign-out to the access session` | `access-session.ts`, `refresh-coordinator.ts`, `index.ts`, sus pruebas y la nota de SS19 en `session-secrets.flow.test.tsx` | 517, 7, **524** |
| 2b | `e0c18b9` `feat(admin-web): add the lifecycle scheduler` | `scheduler.ts`, `lifecycle/index.ts`, sus pruebas y el flujo SS19 | 700, 0, **700** |

Ambas por debajo de 800 (medidas con `git add -N . && git diff --numstat -M <base>` con las exclusiones de la regla, 2a contra `ae218a0` y 2b contra 2a). La suma, 1 224, coincide con la medida de la tarea 2 antes del corte.

### Verificación del corte
- **Unión idéntica:** los 11 archivos de código de 2a más 2b se compararon con `cmp` contra la copia del árbol previo al corte y son idénticos; el conjunto de archivos coincide.
- **2a sola, en verde:** lint y tipos sin errores; U 74 archivos y 844 pruebas; T 293. Sin el programador, `access-session.deadlines.test.ts` y la prueba del coordinador no lo necesitan.
- **2b, en verde:** lint y tipos sin errores; U 77 archivos y 872 pruebas.
- **Final (sobre `e0c18b9`):** T 293 en verde; E 96 en verde; PR según el cierre de más abajo.
- No se repitieron E ni PR sobre 2a sola; se corrieron en el final, como pidió el coordinador. La demostración deliberada se hizo antes del corte, sobre el mismo código, y sus tres rupturas quedaron restauradas con `cmp`.

### Corrección de lo anterior
La «Medida de tamaño» y el «Corte propuesto» de la tarea 2 de arriba describen el estado previo al corte. La única diferencia con lo aplicado es que la documentación va en un commit aparte de `docs(sdd)` sobre 2b.

### Resultado de PR del cierre (sobre `e0c18b9`), con sus dos asteriscos
- La primera ejecución fue 18 de 18 **desde la caché de turbo** (el árbol era byte a byte el ya verificado), así que no la cuento como evidencia nueva.
- Una ejecución **forzada** (`--force`, 0 en caché, 3 min 54 s) falló en `@confia/ui#test`: `DS59: every key used by a component in src/ exists in the catalog` tardó 5 786 ms frente al límite de 5 s, con todo corriendo en paralelo en frío. Esta serie de commits no toca nada en `packages/` (0 archivos). Aislado, `src/i18n/i18n.test.tsx` pasa (13 de 13). Lo trato como un tiempo de espera por carga, **no verificado como causa raíz**, y queda como fallo observado.
- Una ejecución normal posterior dio 18 de 18 correctas, pero **también desde la caché**: `@confia/ui#test` (55 archivos, 553 pruebas) es el resultado de una ejecución anterior con las mismas entradas, no una nueva. Las otras 17 tareas sí se ejecutaron de verdad en la ejecución forzada.
