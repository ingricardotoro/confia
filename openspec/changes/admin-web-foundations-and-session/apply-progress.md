# Avance de aplicación: `admin-web-foundations-and-session`

- **Cambio:** `admin-web-foundations-and-session` (13b-1 de F0, parte 1 de 2)
- **Modo:** TDD estricto. Comandos por superficie según `tasks.md` (pnpm y Vitest); el `test_command` de Maven no se usa.
- **Última actualización:** 2026-11-05
- **Entrega:** PR apilados (`stacked-to-main`), 800 líneas efectivas por PR (`docs/15` §3)

## Estado acumulado

| Tarea | Estado | Rama | Líneas efectivas |
|---|---|---|---|
| 1 `app-static-rules` | **Hecha** (sin corte) | `change/admin-web-foundations-and-session-1-app-static-rules` | 702 de 800 |
| 2 `admin-web-package-and-router` | **Hecha** (2.4 medida en #259 el 2026-11-05) | `change/admin-web-foundations-and-session-2-admin-web-package-and-router` | 673 de 800 |
| 3 a 12 | Pendientes | | |

## Tarea 1: `app-static-rules`

### Medida y corte (regla de las 800 líneas)

Medida antes del primer commit, con archivos nuevos incluidos (`git add -N .` y
`git diff --numstat -M e5dfc9d`, sin `openspec/`, el archivo de bloqueo ni código generado):

| Parte | Adiciones | Eliminaciones | Total |
|---|---|---|---|
| 1a, ESLint (`packages/config/eslint.js`, `eslint.d.ts`, `tooling/src/eslint-app-rules.test.ts`) | 461 | 21 | 482 |
| 1b, dependencias, *fixtures* y escáner | 218 | 2 | 220 |
| **Total de la rama** | **679** | **23** | **702** |

El total no supera 800, así que **no se aplicó el corte predefinido**: una sola rama, dos commits (uno por mensaje de la
tarea 1.4). El diseño estimaba 820 a 4x; la medición real fue 702, es decir, entre 2x (520) y 4x. Eso confirma que la cifra
de 4x era un techo y no una expectativa.

### Evidencia del ciclo TDD

| Paso | ROJO observado | Causa observada | VERDE | REFACTOR |
|---|---|---|---|---|
| 1.1 / 1.2 ESLint | Sí, 58 fallos de 70 | 57 × `TypeError: (0 , __vite_ssr_import_3__.appSourceRules) is not a function`; 1 × `AssertionError: expected [] to include 'Worker'` (`confiaEslint({ app: true })` ignoraba la opción) | `eslint-app-rules.test.ts`: 70 pasan, 3 `todo` | `syntaxEntry` y `jsxTextControls` extraídos; `uiSourceRules` sin cambio de firma ni de mensajes |
| 1.1 / 1.2 dependencias | Sí, 14 fallos | 4 × `AssertionError: expected [ …(10) ] to include '<regla>'` (la regla no existe en la salida de `cruise`) y 10 × `AssertionError: expected [] to include '<regla>'` por *fixture* | `dependency-rules.test.ts`: 40 pasan, 2 `todo` | La expresión de origen de `admin-presentation-is-pure` se partió en dos alternativas (ver desviaciones) |
| 1.1 / 1.2 escáner | Sí, 1 fallo | `AssertionError: expected [ …(4) ] to deeply equal [ 'admin-web/src/probe.ts' ]` (el escáner recorría `target`, `playwright-report` y `test-results`) | `suppression-citations.test.ts`: 6 pasan, 1 `todo` | Sin cambios |

**Pasos que no pasaron por rojo, dicho sin adornos:**

- **BX48** (12 pruebas de caracterización de los mensajes de `uiSourceRules`). Se escribieron y pasaron **antes** de tocar
  `eslint.js`, a propósito: fijan el comportamiento de 13a para que la separación no lo cambie. No son un rojo. Su
  protección se comprobó con la demostración 3, que sí las pone en rojo.
- **BX63 y BX64** (el escáner ya señalaba la directiva sin cita y aceptaba la citada). Pasaron en el primer intento porque el
  escáner es genérico; lo nuevo del escáner es solo qué carpetas omite, y eso sí tuvo rojo.
- **BX61 sobre *fixtures*** (los tres módulos aceptados) pasó en rojo porque, sin reglas, nada se viola. Una demostración
  adicional (abajo) comprobó que la prueba falla cuando la regla rechaza lo aceptado.
- Los seis `it.todo` (BX46, BX54, BX57, BX61, BX62, BX65) no tienen rojo por diseño: ADR-0018, conjunto vacío.

### Demostraciones deliberadas (tarea 1.3)

Procedimiento: copia a `C:\Users\MARVIN\AppData\Local\Temp\confia-scratch\t131`, ruptura, rojo por aserción, restauración
desde la copia y `cmp` sin salida.

| # | Ruptura | Rojo observado | `cmp` |
|---|---|---|---|
| 1 | Quitar `"no-restricted-globals": ["error", ...APP_NO_NETWORK]` de `appSourceRules` | 9 fallos: BX49, BX50, BX51, BX52, BX110 (×2), BX56, «applies to component files too» y el cableado de `confiaEslint({ app: true })`; p. ej. BX49: `expected [] to deeply equal [ StringContaining "fetch" ]` | idéntico |
| 2 | `ignores: ["src/shared/api*/**"]` | 1 fallo: BX56, `expected [] to deeply equal [ StringContaining "fetch" ]`; las otras 69 siguen en verde | idéntico |
| 3 | Quitar la entrada `no-restricted-globals` de `uiSourceRules` | 5 fallos: BX47 y los cuatro de «keeps the request ban on …» de BX48 (`expected [] to deeply equal [ Array(1) ]`) | idéntico |
| 4 | Quitar la regla `apps-use-ui-public-entry` de `.dependency-cruiser.cjs` | 3 fallos: la regla no rechaza su violación, y AW04 con `imports-ui-internal.ts` y con `imports-ui-subpath.ts` (`expected [] to include 'apps-use-ui-public-entry'`) | idéntico |
| extra | `^@confia/ui(` por `^@confia/uiX(` en `pathNot` | 1 fallo: `BX61: accepts uses-ui-public-entry.ts` (la aceptación del nombre sin resolver es real) | idéntico |

### Evidencia de la unidad de trabajo

| Evidencia | Valor |
|---|---|
| Prueba focalizada y resultado | `pnpm --filter ./tooling exec vitest run src/eslint-app-rules.test.ts`: 70 pasan, 3 `todo`; `src/dependency-rules.test.ts src/suppression-citations.test.ts`: 46 pasan, 3 `todo` |
| Arnés de ejecución | Se ejecuta el ESLint y el dependency-cruiser reales sobre código en línea y sobre el árbol de *fixtures*; el árbol real de `apps/` y `packages/` sigue sin violaciones |
| Límite de reversión | Parte 1a: `packages/config/eslint.js`, `eslint.d.ts`, `tooling/src/eslint-app-rules.test.ts`. Parte 1b: el resto. Sin tocar `packages/ui` |

### Suites de cierre (tarea 1.4)

Resultados de la última ejecución sobre el árbol final:

- **T** `pnpm --filter ./tooling test`: 9 archivos, 257 pasan, 6 `todo`.
- **C** `pnpm --filter @confia/config test`: 1 archivo, 4 pasan.
- **UI** `pnpm --filter @confia/ui test`: 52 archivos, 524 pasan; `test:a11y`: 8 archivos, 159 pasan. `pnpm --filter @confia/ui lint` limpio.
- **PR** `pnpm turbo run lint typecheck test build --filter=!@confia/api --concurrency=1`: 14 de 14 tareas, en verde
  (`@confia/ui` 524 pruebas y `@confia/tooling` 257 más 6 `todo`, ambas ejecutadas de nuevo; el resto desde caché de la
  corrida anterior). **La primera corrida, sin `--concurrency=1`, falló por tiempo de espera bajo carga, no por aserción:**
  `DS59` de `@confia/ui` tardó 5 223 ms contra el límite de 5 000, y en `@confia/tooling` «finds no violation in the real
  tree» y «does not cruise generated output under dist/» superaron los 30 000 ms mientras turbo corría `ui` con cobertura en
  paralelo. Las mismas pruebas pasan solas (T completa en 51 s, UI completa en 136 s). La máquina tiene poca memoria; la
  segunda corrida, con una tarea a la vez, no repitió el fallo. Conviene que el propietario sepa que el recorrido del árbol
  real tiene poco margen bajo carga; CI, que corre en un ejecutor dedicado, es la medida que cuenta.
- Guarda de `*.spec.*` sobre `apps/`: sin coincidencias. AW10: `git diff --name-only e5dfc9d -- apps/api` vacío.

### Desviaciones del diseño

1. **Expresión de origen de `admin-presentation-is-pure`.** D3 dice `apps/admin-web/src/(.+/)?components/`; el dependency-cruiser
   la rechaza como expresión insegura (repetición anidada) y aborta. Se escribió con dos alternativas equivalentes:
   `apps/admin-web/src/components/|apps/admin-web/src/.+/components/`. El comportamiento es el mismo. Conviene registrarlo en
   una nota fechada del diseño en el PR que lo toque.
2. **`APP_NO_NETWORK_PROPS` incluye `sendBeacon` sin objeto**, de modo que también atrapa `window.navigator.sendBeacon`. D2 solo
   nombraba `navigator.sendBeacon`; es un superconjunto.
3. **Mensajes propios de la aplicación** para formato manual, consulta por estructura y red: los de `ui` mencionan `src/format`
   y `*.a11y.test.tsx`, que no existen en la aplicación. Los de `ui` no cambian (BX48).
4. **`appSourceRules` incluye `contractImportRestrictions({ portal })`** para que el parámetro `portal` tenga efecto y quien use
   solo estas reglas conserve el espejo del contrato. En `confiaEslint` las reglas de la aplicación van antes que las de `ui`,
   así que, si alguien activa ambas, ganan las de `ui`.
5. **El cableado `app: true`** se prueba con `calculateConfigForFile` sobre la configuración real de `confiaEslint`; no se
   usó `lintText` porque exigiría el servicio de proyecto de TypeScript.

### Elementos abiertos para tareas posteriores

- **Escritura de `document.cookie` en pruebas.** `NO_COOKIE_WRITE` rige en todo `src/**/*.{ts,tsx}`, pruebas incluidas, como dice
  D2. La tarea 4 (`csrf-cookie.test.ts`, AW40) probablemente querrá fijar una cookie en jsdom. Cuando llegue, hay que decidir entre
  inyectar la cadena de cookies en `csrf-cookie.ts`, simular `document.cookie` con `Object.defineProperty`, o excluir los
  `*.test.ts` de esa regla. No se decidió aquí.
- Los `it.todo` esperan: BX54 y BX57 (tarea 4), BX65 (tarea 2), BX46, BX61 y BX62 (tarea 9).
- El selector de importes (`APP_MONEY`) no detecta alias (`const a = money.amount; Number(a)`), límite ya registrado en D2.
- El recorrido de `apps/` por el escáner es real solo cuando exista código en `apps/admin-web` (tarea 2).

## Tarea 2: `admin-web-package-and-router`

- **Ruta:** escritor delegado (un solo escritor, un solo árbol de trabajo). Rama `change/admin-web-foundations-and-session-2-admin-web-package-and-router`, base `1be0378`.
- **Subtarea 2.4 (medida de CI, BX89): pendiente.** Requiere la primera ejecución remota del PR, es decir, que el propietario empuje. No se marca y la nota de D16 no se escribió.

### Dependencias (2.1)

Versiones fijadas en el catálogo, todas publicadas hace más de 14 días y legibles por pnpm 12.8.1: `@tanstack/react-router` 1.170.45, `@tanstack/react-query` 5.104.5 (la última, 5.106.1, es de hoy), `@tanstack/react-form` 1.33.5, `@playwright/test` 1.64.1 (igual que `playwright`) y `@axe-core/playwright` 4.14.0 (depende de `axe-core ~4.14.0`). `msw`, `qrcode.react` y `jsqr` no entran aquí. `pnpm audit --audit-level high`: limpio (2 moderadas). `pnpm why`: una sola versión de `react` (19.3.0), `react-dom` y `axe-core` (4.14.0).

**Rojo verificado de 2.1:** en un proyecto de prueba fuera del repositorio, pnpm 12.8.1 con `minimumReleaseAge: 20160` rechazó `@playwright/test@1.65.0` (y `playwright` y `playwright-core` 1.65.0) y aceptó 1.64.1.

**Hallazgo (bloqueo y decisión del propietario, 2026-11-05).** Al activar `minimumReleaseAge`, el archivo de bloqueo heredado de `main` dejó de pasar la política: `browserslist@4.29.5` (2026-10-26) y otras nueve versiones transitivas tenían menos de 14 días. El propietario eligió la opción 1: un `overrides` fechado que fija `browserslist` en 4.29.4, con la nota correspondiente en D19. Como la política verifica también las demás, se regeneró el archivo de bloqueo para que pnpm eligiera versiones maduras. **Advertencia para el propietario:** la primera regeneración dejó `vite` sin sus pares opcionales (`esbuild`, `jiti`, `yaml`) y una prueba de `@confia/ui` (`run-gate.test.ts`, «restores the page… when the story throws») falló de forma determinista. Una segunda resolución (`pnpm install --no-prefer-frozen-lockfile`) restauró los pares y la prueba pasó. El archivo de bloqueo resultante cambia unas 600 líneas (excluidas de la medida) y mueve versiones transitivas maduras (por ejemplo `fast-uri`, `ignore`, `resolve`, `acorn`); las del catálogo no cambian. Conviene revisar el diff del bloqueo.

### Medida y corte (regla de las 800 líneas)

Ver «Medida final» al final de esta sección.

### Evidencia del ciclo TDD (2.2, 2.3)

| Superficie | ROJO observado | Causa observada | VERDE |
|---|---|---|---|
| `workspace-guards.test.ts` (BX92, BX93, BX94, BX91, D19, AW01, BX88) | Sí, 8 de 14 fallos | BX93: `actual value must be number or bigint, received "undefined"`; BX92, BX94, BX91 y AW01: `ENOENT apps/admin-web/package.json`; D19 Playwright: `expected undefined to be '1.64.1'`; D19 versión única: `expected [] to have a length of 1 but got +0`; BX88: `expected undefined to deeply equal { cache, persistent, dependsOn }` | 14 pasan |
| `suppression-citations.test.ts` (AW17, BX65) | Sí, 1 fallo (el `it.todo` pasó a prueba) | `expected 0 to be greater than 0` (ningún archivo real de `admin-web`) | pasa |
| `vite.config.test.ts` (AW07, AW08, AW11, `resolveApiTarget`) | Sí, el archivo no carga | `Cannot find module './vite.config.ts'` | 16 pasan |
| `App.test.tsx`, `vitest.config.test.ts` (AW01, BX97 a BX99) | Sí, no cargan | `Cannot find module './App.tsx'` y `'./vitest.config.ts'` | pasan |

**Rojos sorprendentes durante el verde, registrados sin adornos:**

- **AW11 (CORS).** Con la configuración de D4 tal cual, Vite respondió a la ruta proxificada con `access-control-allow-origin` (su `server.cors` por defecto acepta orígenes `localhost`): `expected [ 'access-control-allow-origin' ] to deeply equal []`. Se corrigió con `cors: false` en `server` y `preview`. **Desviación del diseño**, ver abajo.
- **BX97 con `main.tsx` sin prueba:** la cobertura bajó a 70 % de líneas, 63,63 % de sentencias y 25 % de ramas, y falló el umbral; es la demostración natural de BX99 (el archivo sin prueba cuenta en el denominador). Se agregó `main.test.tsx` y la cobertura quedó en 100 %.
- Primera ejecución de las pruebas del proxy: `ECONNREFUSED` porque el cliente forzaba IPv4 y `host: "localhost"` enlaza `::1`; se quitó `family: 4` de la prueba.

**Pasos sin rojo (sin adornos):** BX96 y AW62 (en `workspace-guards.test.ts`) y AW16 y AW62 (en `structure.test.ts`) pasaron en la primera ejecución: caracterizan un estado que ya era cierto (el paso de auditoría de `ci.yml`, `orval` solo con cliente `zod`, ausencia de árbol de rutas). Las demostraciones deliberadas de abajo no los cubren; protegen contra una regresión futura, no contra un defecto actual. El `AW01` de turbo se apoya en `turbo run --dry=json`.

### Demostraciones deliberadas (2.5)

Procedimiento: copia a un directorio temporal de la sesión, ruptura, rojo por aserción, restauración desde la copia y `cmp` sin salida.

| # | Ruptura | Rojo observado | `cmp` |
|---|---|---|---|
| 1 | `resolveApiTarget` devuelve el valor sin validar | 6 fallos, uno por destino no local (`expected [Function] to throw an error`) | idéntico |
| 2 | `changeOrigin: true` | 3 fallos: AW07 (`expected '127.0.0.1:62471' to be 'localhost:62472'`), AW08 con el mismo `Host`, y la guarda de fuente de D4 | idéntico |
| 3 | `minimumReleaseAge: 10080` | 1 fallo: BX93 `expected 10080 to be greater than or equal to 20160` | idéntico |

### Desviaciones del diseño

1. **`cors: false` en `server` y `preview`** (D4 no lo decía). Sin él, Vite agrega `Access-Control-Allow-Origin` a una petición con `Origin` local, lo que contradice AW11. La página es del mismo origen, así que no cambia el comportamiento del navegador. Conviene una nota fechada en D4 en un PR que toque el diseño.
2. **`vite.config.ts` exporta una función** (`defineConfig(() => …)`) y no un objeto, para leer `CONFIA_API_PROXY_TARGET` cuando Vite crea el servidor y poder probarlo con `createServer`.
3. **La app no importa aún `@confia/ui/styles.css` ni depende de `@confia/ui`.** Hacerlo ejecutaría `^build` de `@confia/ui` (Storybook) en cada `build` de la app y la tarea 2 no lo pide. AW03 pertenece a la tarea 12. `@tanstack/react-query` y `@tanstack/react-form` están declaradas pero sin uso hasta las tareas 3 y siguientes.
4. **`apps/admin-web/package.json`** fija `test` como `vitest run --coverage`, con los umbrales de `packages/ui` (80 %). `vitest.config.ts` incluye `src/**/*.test.{ts,tsx}` y `*.test.ts` de la raíz del paquete (`vite.config.test.ts` y `vitest.config.test.ts`).
5. **Archivo de bloqueo regenerado**, ver «Hallazgo» arriba.

### Elementos abiertos

- 2.4: medir `frontend verify` en la primera ejecución remota y anotarlo en D16. Si no cabe en 10 minutos, se detiene el PR (BX19, decisión del propietario). El trabajo de CI ahora ejecuta además `build` de `@confia/admin-web` y la verificación de la antigüedad al instalar.
- Retirar el `overrides` de `browserslist` hacia el 2026-11-09.
- Los `it.todo` restantes: BX54 y BX57 (tarea 4), BX46, BX61 y BX62 (tarea 9). BX57 estaba asignado a esta tarea en el diseño §10 (la fila «PR 2») pero `tasks.md` lo asigna a la tarea 4; se siguió `tasks.md`.

### Medida final y suites de cierre (2.6)

Medida con `git add -N .` y `git diff --numstat -M 1be0378` (sin `openspec/`, el archivo de bloqueo ni código generado): **673 líneas efectivas** (671 adiciones y 2 eliminaciones), por debajo de 800; no se aplicó corte. El diseño estimaba 650 a 4x. Dos commits: el paquete y su configuración, y la regla de antigüedad con su guarda y la nota de D19.

- **U** `pnpm --filter @confia/admin-web test`: 5 archivos, 25 pasan; cobertura 100 % (umbral 80 %).
- **T** `pnpm --filter ./tooling test`: 9 archivos, 268 pasan, 5 `todo` (BX54, BX57, BX46, BX61 y BX62; BX65 dejó de ser `todo`).
- **PR** `pnpm turbo run lint typecheck test build --filter=!@confia/api --concurrency=1`: 18 de 18 tareas, sin caché (`@confia/ui` 524, `@confia/tooling` 268, `@confia/admin-web` 25, `@confia/config` 4, `@confia/contracts` 8).
- Guarda de `*.spec.*` sobre `apps/`: sin coincidencias. AW10: `git diff --name-only 1be0378 -- apps/api` vacío.
- `pnpm audit --audit-level high`: sin altas ni críticas (2 moderadas).
- Una primera corrida de PR falló en `@confia/ui` (`run-gate.test.ts`) por el archivo de bloqueo regenerado una sola vez (ver «Hallazgo»); con el bloqueo corregido, pasa.


## Tarea 2: subtarea 2.4 cerrada (2026-11-05)

En el PR #259, `frontend verify` tardó 1 min 51 s y 2 min 11 s en las dos primeras ejecuciones remotas, dentro del límite de 10 minutos (BX19 sin cambios). La cifra quedó en `design.md` D16 con nota fechada, y la tarea 2 se marca terminada.


## Tarea 3: `providers-i18n-and-voice` (2026-11-05, excepción del propietario a las 800 líneas)

- **Ruta:** escritor delegado (un solo escritor, un solo árbol de trabajo). Rama `change/admin-web-foundations-and-session-3-providers-i18n-and-voice`, base `a6a9736`.
- **Estado:** hecho y verificado. Excede las 800 líneas por **excepción explícita del propietario** (ver «Excepción a la regla de las 800 líneas»).
- **Ubicación (decisión del propietario, 2026-11-05).** El plan decía `packages/config/{voice,text-keys}.ts` en la raíz del paquete; se implementó bajo `packages/config/src/`, porque `vitest.js` solo incluye `src/**/*.test.ts` y `tsconfig.json` solo incluye `src`, de modo que un archivo en la raíz no se probaría, no se tiparía y fallaría `eslint .`. Exportaciones `./voice` y `./text-keys` en `packages/config/package.json`.

### Dependencias

`apps/admin-web` declara ahora `@confia/ui` (`workspace:*`), `i18next`, `i18next-icu` y `react-i18next` (todas `catalog:`, versiones ya presentes en el archivo de bloqueo; `pnpm install --offline` solo agregó los enlaces, ninguna versión nueva). Consecuencia de turbo: `build` de la app ahora espera el `build` de `@confia/ui` (Storybook), que la tarea 2 había evitado a propósito (desviación 3 de esa sección). En la corrida de PR ese `build` ya se ejecuta una vez y turbo lo reutiliza; el costo medido está en «Suites».

### Evidencia del ciclo TDD (3.1, 3.2)

| Superficie | ROJO observado | Causa observada | VERDE |
|---|---|---|---|
| `packages/config/src/voice.test.ts` (AW29, AW30) | Sí, el archivo no carga | `Cannot find module './voice' imported from .../voice.test.ts` | 13 pasan |
| `packages/config/src/text-keys.test.ts` (BX42, BX43, AW26, AW27) | Sí, el archivo no carga | `Cannot find module './text-keys' imported from .../text-keys.test.ts` | 7 pasan |
| `src/app/query-client.test.ts`, `AppProviders.test.tsx` (AW23) | Sí, no cargan | `Failed to resolve import "./query-client.ts"` | pasan |
| `src/i18n/i18n.test.tsx`, `catalog.test.ts` (AW24, AW25, AW28, AW29, AW30, AW32) | Sí, no cargan | `Failed to resolve import "./use-admin-text.ts"` y `"./locales/es-HN/admin.json"` | pasan |
| `src/i18n/keys.check.test.ts` (BX42, BX43, AW27) | Sí, no carga | `Failed to resolve import "@confia/config/text-keys"` (el esperado `@confia/config/voice` aparece en `catalog.test.ts` como error de tipos) | pasa |
| `src/i18n/keys.test-d.ts` (AW26) | Sí, 2 fallos | `Unused '@ts-expect-error' directive` (sin módulo, la clave inexistente no falla) | pasa |
| `src/i18n/voice-note.test.ts` (AW31) | Sí, 2 de 3 fallos | `expected '' to match /Nota fechada 2026-11-05 .../` y `expected '' to match /«usted»/` | 3 pasan |

**Pasos sin rojo (sin adornos):** la primera aserción de AW31 (el texto original sigue siendo el inicio del archivo) pasó antes de escribir la nota: caracteriza el estado de partida y protege el futuro, no un defecto actual. El `it.todo` de AW28 («toda clave usada por un componente de `src/` existe») queda como `todo`: hoy ningún `.tsx` de `src/` llama a `t()`, y un verde sobre un conjunto vacío no se acepta (ADR-0018); lo activa la tarea 9.

**Rojos del verde, sin adornos:** (1) el hash de AW31 falló al principio porque la longitud pinneada era en bytes (15 533) y `String.slice` cuenta caracteres (15 297); se corrigió la constante, no la aserción. (2) `eslint` rechazó `fetchQuery` (obsoleto: se usa `queryClient.query`) y `unbound-method` sobre los métodos de `TextKeyChecker` (se declararon como propiedades de función). (3) Las importaciones relativas de las pruebas de `config` exigen `.js` por `module: NodeNext`.

### Demostraciones deliberadas (3.3)

Procedimiento: copia fuera del repositorio, ruptura, rojo por aserción, restauración y `cmp` sin salida.

| # | Ruptura | Rojo observado | `cmp` |
|---|---|---|---|
| 1 | `mutations: { retry: 1 }` | 2 fallos de AW23: `expected 1 to be +0` y `expected "vi.fn()" to be called 1 times, but got 2 times` | idéntico |
| 2 | `verificationInterrupted` empieza con «Inicia sesión de nuevo» | AW29: `["notice.verificationInterrupted: inicia (sentence-start)"]` (nombra la clave); además cae la prueba de textos exactos | idéntico |
| 3 | Borrar `signIn.contact` de `admin.json` | `tsc`: `Argument of type '"signIn.contact"' is not assignable to parameter of type 'AdminKey'` en `keys.test-d.ts(14,7)` y otros cuatro usos [AW26] | idéntico |

### Desviaciones del diseño

1. **Claves `notice.sessionExpired` y `problem.authenticationFailed`.** El diseño §8 no lista las claves de AW32; se eligieron esos nombres para los dos textos fijados por la documentación. Las demás claves y valores son los de §8, verbatim.
2. **`isRetryable` por forma del error.** D9 usa `isRetryable(error)` sin definirlo; el `ApiResult` llega en la tarea 4. Aquí se reconoce por la propiedad numérica `status`: sin ella (fallo de red) se reintenta, con `status` solo desde 500. La tarea 4 puede afinarlo.
3. **`appResources` y `createAdminI18n`** mezclan `uiResources["es-HN"]` con `admin` en una sola instancia, con `defaultNS: "admin"`.
4. **Prueba de AW31 por huella.** `voice-note.test.ts` fija la longitud y el SHA-256 del archivo base; la nota debe ir al final. Detecta cualquier línea cambiada sin depender de `git` ni de una rama `main` en CI, pero obliga a retirar la huella si otra tarea edita legítimamente el cuerpo de `docs/ui-ux/05`.
5. **`AppProviders`, `src/test/render.tsx` y el cableado de `main.tsx`** son los «proveedores» del título; `main.test.tsx` sigue pasando sin cambios.
6. **Dependencia de la app en `@confia/ui`** (ver «Dependencias»).

### Medida (regla de las 800 líneas) y suites

`git add -N . && git diff --numstat -M a6a9736` (sin `openspec/`, archivo de bloqueo ni código generado): **1 214 líneas efectivas** (1 099 adiciones y 115 eliminaciones). **Supera 800; el propietario aprobó un único PR (ver abajo).** El diseño estimaba 740 a 4x; las pruebas del paquete `config` (215), el verificador movido (125 más 112 eliminadas en `ui`) y el catálogo con sus pruebas pesaron más. Desglose: `packages/config` + wrapper de `ui` + nota de `docs/ui-ux/05` ≈ 547; `apps/admin-web` ≈ 667.

Suites (todas con el árbol sin commit): **U** 12 archivos, 66 pasan y 1 `todo`, cobertura 100/94,73/100/100; **C** 3 archivos, 24 pasan; **T** 9 archivos, 268 pasan y 5 `todo`; **UI** 52 archivos, 524 pasan, y `test:a11y` 8 archivos, 159 pasan; **PR** `pnpm turbo run lint typecheck test build --filter=!@confia/api --concurrency=1`: 18 de 18 tareas, 5 min 42 s (con el `build` de Storybook ahora delante del de la app). Guarda de `*.spec.*` sobre `apps/`: sin coincidencias. AW10: `git diff --name-only a6a9736 -- apps/api` vacío.

### Elementos abiertos

- Ninguno de esta tarea.

### Excepción a la regla de las 800 líneas

Medido: **1 214 líneas efectivas** (1 099 adiciones y 115 eliminaciones) frente a `a6a9736`; la estimación del diseño era 740 a 4x. La tarea no tiene corte predefinido, así que se consultó al propietario, que el 2026-11-05 **aprobó un único PR** como excepción al límite de 800 líneas de `docs/15` §3. No se partió ni se quitó ninguna prueba, comentario o aserción. La propuesta de corte 3a/3b (≈ 547 y ≈ 667 líneas) queda descartada.


## Tarea 4: `api-transport-and-problem-map` (2026-11-06, partida en 4a y 4b)

- **Ruta:** escritor delegado (un solo escritor, un solo árbol de trabajo). Base común `496573c`.
- **Ramas:** 4a `change/admin-web-foundations-and-session-4a-api-transport` (commit `12e45c4`, `feat(admin-web): add the api transport and the problem reader`) y 4b `change/admin-web-foundations-and-session-4b-problem-key-map` (parte de 4a).

### El corte y por qué se movió `problem.ts` a 4a

La tarea medía **1 091 líneas efectivas** (1 064 adiciones y 27 eliminaciones) frente a `496573c`, sobre las 800. El corte predefinido decía 4a (transporte y CSRF) / 4b (Problem y mapa). El propietario aprobó el 2026-11-06 un corte ajustado: `http.ts` llama a `readProblem` para toda respuesta de error, de modo que `problem.ts` y `report-unknown-problem.ts` no pueden ir después del transporte sin que 4a deje de compilar. Por eso **4a** lleva `http`, `csrf-cookie`, `api-failure`, `problem`, `report-unknown-problem`, `query-client` y sus pruebas, y **4b** lleva `problem-keys` (con `problem-keys.test-d.ts`), `admin.json` y BX54/BX57 en `tooling/src/eslint-app-rules.test.ts`.

### Medidas (regla de las 800 líneas)

- **4a: 948 líneas efectivas** (924 adiciones y 24 eliminaciones) frente a `496573c`. **Supera 800: excepción explícita del propietario a `docs/15` §3, aprobada el 2026-11-06** (opción A, un solo PR; la alternativa de un segundo corte 4a-1/4a-2 exigía mover `ApiResult` a un archivo nuevo y sumaba un PR). No se quitó ninguna prueba, comentario ni aserción.
- **4b: 143 líneas efectivas** (140 adiciones y 3 eliminaciones) frente a `12e45c4`.

### Evidencia del ciclo TDD (4.1, 4.2)

| Superficie | ROJO observado | Causa observada | VERDE |
|---|---|---|---|
| `csrf-cookie`, `problem`, `report-unknown-problem`, `http`, `problem-keys`, `api-failure` y `query-client.test.ts` | Sí, 7 archivos no cargan | `Failed to resolve import "./csrf-cookie.ts"` (y los demás módulos de `shared/api`; `../shared/api/api-failure.ts` desde `query-client.test.ts`) | pasan |
| `problem-keys.test-d.ts` (AW48) | Sí, `tsc` | `Cannot find module './problem.ts'` y `'./problem-keys.ts'`; `Unused '@ts-expect-error' directive` | sin errores de tipos |
| `tooling/src/eslint-app-rules.test.ts` (BX54, BX57) | Sí, 2 fallos | BX54: `src/shared/api/http.ts was linted: expected undefined to be defined`; BX57: `expected 0 to be greater than 0` | 72 pasan, 1 `todo` (BX46) |

Rojos del verde, sin adornos: `eslint` rechazó plantillas con `number` (`restrict-template-expressions`), flechas que devuelven `void` y un `void` sin efecto; se corrigieron en el código y las pruebas, no en las aserciones.

### Demostraciones deliberadas (4.3, todas de 4a)

Procedimiento: copia fuera del repositorio (`/tmp/demo`), ruptura, rojo por aserción, restauración desde la copia, `cmp` sin salida y `git diff` del archivo vacío.

| # | Ruptura | Rojo observado | `cmp` |
|---|---|---|---|
| 1 | `ProblemDetail.safeParse(body).success` completo antes de leer el código | 28 fallos de `problem.test.ts`: `expected 'unknown' to be 'authentication-failed'` y los otros catorce códigos de AW46 (por `instance` relativo), más errores y `Retry-After` | idéntico |
| 2 | Tiempo límite de 20 s | 2 fallos: AW44 `expected true to be false` (la señal se canceló a los 31 s) y el de 60 s | idéntico |
| 3 | `includes(CSRF_COOKIE_NAME)` en vez de nombre exacto | AW40: `expected 'falso' to be undefined` | idéntico |
| 4 | Pasar `detail` al reporte | AW55: `expected "vi.fn()" to be called once with arguments: [ { status: 500, … } ]` | idéntico |

### Desviaciones del diseño

1. **Tres claves nuevas en `admin.json`** (aprobadas por el propietario el 2026-11-06, textos tal cual): `problem.secondFactorInvalid`, `problem.rateLimited` y `problem.unexpected`. D7 exige un mapa exhaustivo de los quince códigos a `AdminKey` y §8 solo lista cinco claves `problem.*`. Los dos primeros son los textos fijados por `docs/ui-ux/05` y `docs/ui-ux/04`:729; `problem.unexpected` se deriva de `docs/ui-ux/04`:739 sin «Nada quedó registrado a medias» (falso ante un fallo de red) y sin `{{traceId}}` (el identificador va aparte).
2. **`isRetryable` afinado (D9).** `ApiFailure` e `isRetryableFailure` (`shared/api/api-failure.ts`): `retryRead` reintenta solo red y 5xx. Un error que no es `ApiFailure` ya **no** se reintenta (antes, sin `status`, sí). Las pruebas de `query-client.test.ts` usan `ApiFailure`.
3. **`http.ts`** exporta `request(options + schema)` y `requestEmpty(options)`; `schema` es la forma estructural de `safeParse`, sin depender de Zod. Una ruta que no empieza con `/api/` lanza `TypeError` (error del programador): es el único caso en que lanza.
4. **Mapa de claves:** los códigos sin texto propio (`csrf-invalid`, `unsupported-media-type`, `authentication-required`, `forbidden`, `resource-not-found`, `internal-error`, desconocido) apuntan a `problem.unexpected`; `token-*` a `notice.verificationInterrupted`; `session-expired` a `notice.sessionExpired`. Las pantallas de la parte 2 podrán elegir una clave más específica.

### Suites

- **4a (árbol solo con 4a):** **U** 17 archivos, 175 pasan y 1 `todo`, cobertura 100/97,56/100/100; **T** 9 archivos, 268 pasan y 5 `todo`; **PR** 18 de 18 tareas.
- **4b (árbol completo):** **U** 19 archivos, 192 pasan y 1 `todo`, cobertura 100/97,56/100/100; **T** 9 archivos, 270 pasan y 3 `todo` (BX54 y BX57 dejaron de ser `todo`; quedan BX46, BX61 y BX62); **PR** 18 de 18 tareas (`@confia/ui` 524, `@confia/tooling` 270, `@confia/admin-web` 192, `@confia/config` 24, `@confia/contracts` 8). Guarda de `*.spec.*` sobre `apps/`: sin coincidencias. AW10: `git diff --name-only 496573c -- apps/api` vacío.
- Hallazgo de 4b: la prueba de supresiones (AW17) exigió citar un ADR en el `@ts-expect-error` de `problem-keys.test-d.ts`; se citó ADR-0011, como en `keys.test-d.ts`.

### Elementos abiertos

- Ninguno de esta tarea. Las ramas 4a y 4b no se empujaron.


## Tarea 5: `auth-operations-and-contract-mocks` (2026-11-06, detenida antes del commit por la regla de las 800 líneas)

- **Ruta:** escritor delegado (un solo escritor, un solo árbol de trabajo). Rama `change/admin-web-foundations-and-session-5-auth-operations-and-contract-mocks`, base `335457b`.
- **Estado:** hecho y comprometido (`2ef41f5`, `fe64bed`, `cb6c00b`; ver «Tarea 5: cierre»). Al detenerse, el trabajo estaba sin commit a la espera de un PR completo en verde. **Excepción del propietario (2026-11-06):** un único PR de **833 líneas efectivas (833 adiciones y 0 eliminaciones frente a `335457b`)**, excepción explícita al límite de 800 de `docs/15` §3; sin corte. La propuesta 5a/5b de «Medida» queda descartada.

### Dependencias

- `msw` **3.0.3** al catálogo de `pnpm-workspace.yaml` y como `devDependency` de `@confia/admin-web` (publicada el 2026-10-10, 27 días antes de hoy; la 3.0.4 es del 2026-11-06 y no cumple los 14 días). `minimumReleaseAge: 20160` y el `override` de `browserslist` quedan intactos.
- `@confia/contracts` (`workspace:*`) como `devDependency` de la app: la tarea importa `@confia/contracts/admin` y el paquete no estaba declarado. Solo agrega el enlace.
- Archivo de bloqueo: `pnpm install` informó «+50 -6». Las 50 altas son el árbol de `msw` (`yargs`, `@inquirer/*`, `@mswjs/interceptors`, `cookie` 2.0.1, etc.); las 6 bajas son entradas de resolución de `vitest`, `@vitest/*` y `orval` que cambian de clave. Como `msw` es un par opcional de `vitest` y de `@orval/mock`, pnpm **reescribió el sufijo de pares** de esas entradas; ninguna versión existente se movió. Las políticas de la cadena de suministro (`minimumReleaseAge`) validaron el bloqueo.
- `pnpm audit --audit-level high`: sin altas ni críticas (2 moderadas, las de antes).

### Evidencia del ciclo TDD (5.1, 5.2)

| Superficie | ROJO observado | Causa observada | VERDE |
|---|---|---|---|
| `src/test/contract-responses.test.ts` | Sí, no carga | `Failed to resolve import "./contract-responses.ts"` | 12 pasan |
| `src/shared/api/auth-operations.test.ts` (AW33 a AW39, AW41, AW42, I310) | Sí, no carga | `Failed to resolve import "./auth-operations.ts"` (la causa esperada, con `msw-server` y `contract-responses` ya presentes) | 23 pasan |
| `src/test/auth-operations-query.test.ts` (AW20 a AW22) | Mismo rojo que el anterior mientras estuvo en ese archivo (ver desviación 2) | `Failed to resolve import "./auth-operations.ts"` | 4 pasan |

Pasos sin rojo (sin adornos): el rojo de `auth-operations` se observó con el archivo único; AW20 a AW22 se movieron de archivo después del verde, sin cambiar aserciones. La opción `onUnhandledRequest` de MSW 2 no existe en MSW 3 (`onUnhandledFrame`): `tsc` lo detectó tras el verde y se corrigió en `msw-server.ts`.

### Demostraciones deliberadas (5.3)

Procedimiento: copia fuera del repositorio (directorio temporal de la sesión), ruptura, rojo por aserción, restauración desde la copia y `cmp` sin salida. El archivo es nuevo (sin seguimiento de Git), por lo que `git diff` no aplica y el `cmp` es la comprobación.

| # | Ruptura | Rojo observado | `cmp` |
|---|---|---|---|
| 1 | `Authorization` en `endCurrentSession` | AW35: `expected true to be false` (la cabecera estaba presente); 1 fallo de 27 | idéntico |
| 2 | `Idempotency-Key` en `refreshSession` | AW39 de `refreshSession`: `expected true to be false`; 1 fallo de 27 | idéntico |
| 3 | `logIn` devuelve el cuerpo sin validar como `ok` | AW41: `expected { kind: 'ok', status: 200, … } to deeply equal { kind: 'unexpected-response', … }`; 1 fallo de 27 | idéntico |

### Desviaciones del diseño y de la lista de tareas

1. **Nombres de las operaciones**, por `operationId` del OpenAPI: `logIn`, `refreshSession`, `endCurrentSession`, `getCurrentSession`, `enrollSecondFactor`, `completeSecondFactor`. `refreshSession` exige el valor CSRF (`string`): sin cookie la sesión no hace petición (invariante 3); `endCurrentSession` lo acepta ausente (AW36). `completeSecondFactor` toma `{ kind: "code" | "recovery-code", value }` para que sea exactamente uno.
2. **AW20 a AW22 en `src/test/auth-operations-query.test.ts`**, no en `auth-operations.test.ts`. La regla `admin-shared-never-imports-features` impide que `shared/` importe `app/query-client.ts`; la prueba de PR (`tooling/src/dependency-rules.test.ts`) la detectó en la primera corrida. Las aserciones son las mismas.
3. **`contract-responses.ts`** devuelve `{ status, body, contentType }` (sin depender de MSW, para que el simulador de 11a lo use), valida cada cuerpo con el esquema generado y lanza `ContractViolation` (nombra la respuesta y el campo, nunca el valor). `problem()` valida con `ProblemDetail.omit({ instance: true })` y emite `instance` relativo, como el backend (D7). Los centinelas, el secreto base32 y los códigos de recuperación se generan en ejecución con `node:crypto`.
4. **`msw-server.ts`** es de uso opt-in por archivo (`installMswServer()`), no entra en `setup.ts`: las pruebas que reemplazan `fetch` con `vi.stubGlobal` no cambian.
5. **Conteo de pruebas de la base.** `U` en `335457b` mide hoy 198 pasan y 1 `todo` (la nota de la tarea 4 decía 192); con la tarea 5 son 237 pasan y 1 `todo` (+39), 21 archivos.

### Medida (regla de las 800 líneas), al detenerse; el estado vigente está en «Tarea 5: cierre»

`git diff --cached --numstat -M 335457b -- . ':(exclude)openspec' ':(exclude)pnpm-lock.yaml' ':(exclude)packages/contracts/src/generated'` con todo lo previsto en el índice: **833 líneas efectivas (833 adiciones, 0 eliminaciones)**. **Supera 800: no se compromete.** Desglose: `auth-operations.ts` 117, `auth-operations.test.ts` 274, `auth-operations-query.test.ts` 78, `contract-responses.ts` 157, `contract-responses.test.ts` 124, `msw-server.ts` 80, `package.json` 2, `pnpm-workspace.yaml` 1. La tarea no tiene corte predefinido (la estimación a 4x era 670). Propuesta de corte por archivos completos, con sus pruebas, para decisión del propietario: **5a** `contract-responses.ts` y su prueba, más el enlace de `@confia/contracts` (≈ 282); **5b** `msw-server.ts`, `auth-operations.ts` y sus dos pruebas, más `msw` en el catálogo y el archivo de bloqueo (≈ 551).

### Suites, antes del ajuste del tiempo límite; las vigentes están en «Tarea 5: cierre»

- **U** (`pnpm --filter @confia/admin-web test`): una corrida en verde antes de mover AW20 a AW22 (21 archivos, 237 pasan y 1 `todo`, cobertura 100/97,82/100/100). Después, con la máquina sin memoria (1 a 2 GB libres), las corridas completas fallan por **tiempos de espera de 5 000 ms o por no poder arrancar procesos** (`main.test.tsx`, `vite.config.test.ts`, `App.test.tsx`, `i18n.test.tsx`, a veces `keys.check.test.ts`); los mismos archivos pasan solos (8 archivos, 41 pruebas), y con `--maxWorkers=2` pasaron 236 de 237 (un tiempo de espera en `vite.config.test.ts`). Los 39 casos de la tarea 5 pasan siempre. **El cierre con U y PR completos queda pendiente de una máquina descargada.**
- **PR:** la primera corrida detectó la violación de `admin-shared-never-imports-features` (desviación 2). Corregida, `tooling/src/dependency-rules.test.ts` y `eslint-app-rules.test.ts` pasan (112 y 3 `todo`); `lint` y `typecheck` de `admin-web` pasan. No se repitió el PR completo por la falta de memoria.
- Guarda de `*.spec.*` sobre `apps/` y AW10: ver el informe de cierre.

## Tarea 5: cierre (2026-11-06)

- **Tiempo límite de las pruebas de arranque.** La suite de PR falló dos veces por `Test timed out in 5000ms` en `main.test.tsx` («mounts the app into #root»), también con 4,7 GB libres. Causa observada: la prueba usa `vi.resetModules()` e importa `main.tsx` en frío, de modo que recompila todo el grafo de la app; sola, esa importación ya ocupa casi 1,7 s. Por decisión del propietario, `2ef41f5` da a ese `describe` y al del proxy de `vite.config.test.ts` un tiempo límite propio de 20 s, sin cambiar ninguna aserción.
- **Suites después del ajuste:** `pnpm turbo run lint typecheck test build --filter=!@confia/api --concurrency=1 --force`: 18 de 18 tareas; app 237 pruebas y 1 pendiente.
- **Commits:** `2ef41f5` (tiempo límite) y `fe64bed` (operaciones de acceso y respuestas de contrato). Medida del PR contra `335457b`: 841 líneas efectivas, 833 de la tarea más 8 del ajuste, dentro de la excepción aprobada.


## Tarea 6: `session-basics` (2026-11-06, partida en 6a y 6b)

- **Ruta:** escritor delegado (un solo escritor, un solo árbol de trabajo). Corte predefinido de la tarea, aprobado por el propietario el 2026-11-06: **6a** `session-store-and-refresh` (almacén, coordinador, Web Locks; SS14 a SS16, SS18, invariante 3, prueba de almacén de SS59) sobre `87df591`; **6b** `access-session` (`AccessSession`, consulta de la sesión actual; SS21 a SS26) sobre el commit de 6a.

### Parte 6a: almacén y coordinador de refresco

- **Archivos.** `src/features/identity/session/{token-store,refresh-coordinator}.ts` y sus pruebas, más `src/test/fake-locks.ts` (cola exclusiva con el orden del navegador; registra nombre y modo). `index.ts` no entra en 6a: la instancia única se crea en 6b, donde existe `AccessSession`.
- **ROJO (6.1).** `token-store.test.ts` y `refresh-coordinator.test.ts` no cargan: `Failed to resolve import "./token-store.ts"` y `Failed to resolve import "./refresh-coordinator.ts"` (la causa esperada: el módulo no existe). 2 suites fallidas, 0 pruebas ejecutadas.
- **VERDE (6.2).** 17 pruebas (6 de almacén, 11 del coordinador). Primera corrida: 15 pasan y 2 por tiempo de espera de 5 000 ms, en las dos pruebas del invariante 3. Causa observada: **defecto de la prueba, no de producción**. El ayudante que retiene el bloqueo como «otra pestaña» capturaba `release` dentro de un callback que el bloqueo falso invoca de forma asíncrona, y la prueba lo llamaba antes (aún era una función vacía). Se corrigió el ayudante para esperar a tener el bloqueo; las aserciones no cambiaron.
- **Decisiones de diseño.**
  - `createRefreshCoordinator({ refresh, readCsrf, locks })` devuelve el desenlace como valor (`refreshed`, `no-session`, `network`, `rejected`); no lanza por lo que dijo el servidor, por lo que SS16 reparte el mismo valor a todos. Lo que hace con el desenlace (guardar el token, terminar la sesión local) es de `AccessSession` (6b). `readCsrf` se inyecta; en 6b recibe `readCsrfToken`.
  - `createTokenStore()` expone `accept(outcome)`, `accessToken()`, `secondFactorToken()` y `clear()`. `accept` recibe un `LoginOutcome` tal como llegó y guarda un solo tipo de token a la vez; no tiene entrada para el identificador (SS59). La prueba de SS59 de esta parte comprueba que ninguna lectura del almacén contiene el identificador y que su superficie son solo las cuatro operaciones; el requisito completo de pantalla sigue en la parte 2.
  - Sin temporizadores en producción. SS18 afirma que no se llama a `setInterval`; el temporizador de 60 s del transporte (`http.ts`) no es un sondeo y no se afirma ausente.
- **Demostraciones deliberadas (6.3), ambas del coordinador, que vive en 6a.** Procedimiento: copia fuera del repositorio, ruptura, rojo por aserción, restauración y `cmp` sin salida.

  | # | Ruptura | Rojo observado | `cmp` |
  |---|---|---|---|
  | 2 | `inFlight ??=` por `inFlight =` (sin compartir la promesa) | SS14: `expected [ … ] to have a length of 1 but got 3`; también SS16 y SS18 (`toBe` entre desenlaces); 3 fallos de 11 | idéntico |
  | 1 | Leer el valor CSRF antes de pedir el bloqueo | Invariante 3: `expected [ 'csrf-before' ] to deeply equal [ 'csrf-rotated-by-the-other-tab' ]` y `expected { kind: 'refreshed', … } to deeply equal { kind: 'no-session' }`; 2 fallos de 11 | idéntico |

  Las demostraciones (3) a (5) son de 6b, donde vive el código que rompen.
- **Suites (6a).** **U** 24 archivos, 254 pasan y 1 `todo`, cobertura 100/98,07/100/100; **T** 9 archivos, 270 pasan y 3 `todo`; **PR** 18 de 18 tareas. Guarda de `*.spec.*` sobre `apps/` y AW10 (`git diff --name-only 87df591 -- apps/api`): vacíos.
- **Medida (800 líneas).** 409 líneas efectivas (409 adiciones, 0 eliminaciones) frente a `87df591`: `refresh-coordinator.test.ts` 176, `refresh-coordinator.ts` 65, `token-store.test.ts` 89, `token-store.ts` 56, `fake-locks.ts` 23.

### Parte 6b: `AccessSession` y la consulta de la sesión actual

- **Archivos.** `src/features/identity/session/{access-session,current-session-query,index}.ts` y sus pruebas; `refresh-coordinator.ts` exporta ahora `withCrossTabLock` (la función que el diseño D8 muestra; `signOut` la usa), 11 adiciones y 5 eliminaciones sobre 6a.
- **Orden TDD (desviación, declarada).** Por un descuido del escritor, el código de producción de 6b se escribió **antes** de las pruebas. El rojo se obtuvo después, contra un árbol sin ese código: se sacaron `access-session.ts`, `current-session-query.ts` e `index.ts` y se revirtió el cambio al coordinador, y se corrieron las pruebas ya escritas. Por eso no es un rojo previo a la escritura; es un rojo real contra el árbol sin producción, y las demostraciones de abajo son la prueba de que las pruebas discriminan.
- **ROJO (6.1), causa observada.** 3 suites fallidas, 0 pruebas ejecutadas de ellas: `Failed to resolve import "./access-session.ts"` (en `access-session.test.ts` y en `current-session-query.test.ts`) y `Failed to resolve import "./index.ts"` (en `index.test.ts`); la causa esperada. Las 17 pruebas de 6a seguían en verde.
- **VERDE (6.2).** 48 pruebas en la carpeta (31 nuevas) a la primera corrida con el código restaurado. REFACTOR: `tsc` y ESLint limpios; `fetchQuery` (obsoleta en esta versión) se cambió por `query` en la prueba.
- **Demostraciones deliberadas (6.3).** Copia fuera del repositorio, ruptura, rojo, restauración y `cmp` sin salida. Las de la parte 6a (1 y 2) están arriba.

  | # | Ruptura | Rojo observado | `cmp` |
  |---|---|---|---|
  | 3 | `<` por `<=` en la frontera de 60 s (`access-session.ts`) | SS23: `expected [ 'POST /api/v1/auth/sessions', …(2) ] to deeply equal [ 'POST /api/v1/auth/sessions', …(1) ]`; 1 fallo de 48. La primera vez el rojo vino de una petición de refresco sin ruta (error de MSW), no de la aserción; se reforzó SS23 con una ruta de refresco y se repitió con el resultado de arriba | idéntico |
  | 4 | Reintentar el refresco ante red (`access-session.ts`) | SS24: `expected [ … ] to deeply equal [ …(1) ]` (dos refrescos) y, por la consulta, `to have a length of 1 but got 2`; 2 fallos de 48 | idéntico |
  | 5 | Refrescar ante un `401` (`access-session.ts`) | SS25: `expected [ 'POST /api/v1/auth/sessions', …(2) ] to deeply equal [ …(1) ]`; 1 fallo de 48 | idéntico |
  | extra | Quitar `retry: false` de `current-session-query.ts` | SS21 (`expected undefined to be false`) y SS24 por la consulta (`to have a length of 1 but got 3`); 2 fallos de 48 | idéntico |

  Las demostraciones (4) y (5) rompen `access-session.ts`, donde vive el código: es su hogar. Las cinco de 6.3 están hechas: (1) y (2) en 6a, (3) a (5) aquí.
- **Decisiones y desviaciones del diseño.**
  1. **La consulta no reintenta (`retry: false`).** El `retryRead` por defecto repetiría la lectura y, con ella, el refresco previo ante un error de red; una reutilización fuera de la gracia revoca la familia (SS24). SS21 solo nombra intervalo, foco y reconexión; `retry` es una adición, probada por la demostración extra.
  2. **Fallo sin respuesta del servidor.** Cuando la sesión terminó y no hay petición que hacer (sin token, sin cookie CSRF o refresco rechazado), `readCurrentSession` lanza `ApiFailure` con un `problem` sintético `401 session-expired`, la forma de la respuesta real, para que quien la use maneje un solo caso. Un fallo de red del refresco lanza `ApiFailure({ kind: "network" })` y conserva la sesión.
  3. **Sin cookie al arrancar.** `boot` sin cookie CSRF deja la vista `anonymous` **sin aviso**: llegar sin sesión no es una sesión vencida. Con la sesión abierta, perder la cookie sí muestra `session-expired`. El diseño dice «termina la sesión local» sin separar los casos.
  4. **El identificador no pasa por `AccessSession`.** `signIn` solo lo envía. El diseño lo dirige a `enrollment-store` (tarea 14) cuando el `status` es `second-factor-enrollment-required`; esa conexión pertenece al contenedor de la parte 2 (SS58, SS59), que ya tiene el valor escrito. La prueba de almacén de SS59 está en 6a.
  5. `ArrivalNotice` toma los cuatro motivos de SS60 (`session-expired`, `second-factor-interrupted`, `enrollment-interrupted`, `second-factor-already-enrolled`). `hasSecondFactorToken` y `verify`/`enroll` tratan un token restringido vencido como ausente (`no-token`). `enroll` devuelve el secreto y los códigos al llamador y no los guarda. `signOut` devuelve `failed` ante cualquier resultado que no sea `204` y conserva la sesión; la tarea 9 afina ese flujo.
  6. `index.ts` decide los bloqueos con `"locks" in navigator`: en jsdom no hay Web Locks y la degradación de SS18 es la ruta real de las pruebas de `index`.
  7. **Cláusula de pantalla de SS25 y SS26** (vaciar la caché con `queryClient.clear()` y navegar): `app/` ve la vista `anonymous` y lo hace en la tarea 9. 6b afirma el contenido del almacén y la vista.
- **Suites (6b).** **U** 27 archivos, 285 pasan y 1 `todo`, cobertura 99,2/97,41/97,36/99,55 (carpeta `session`: 98,36/96,82/94,44/99,03). **T** 9 archivos, 270 pasan y 3 `todo` (`admin-token-store-is-private` evalúa ya los archivos reales). **PR:** la primera corrida terminó con 15 de 16 tareas y un fallo en `vite.config.test.ts` («AW07: dev forwards the path and method with Origin and Host intact», dos líneas `FAIL`); **el texto del error no se capturó** (la salida estaba filtrada) y no se pudo comprobar que fuera un tiempo de espera de 5 000 ms, aunque es el mismo archivo y la misma prueba que fallaron así en la tarea 5 con poca memoria. Una única repetición completa dio **18 de 18 tareas**, sin fallos; no se relanzó más. U y T, que corren la misma prueba, pasaron en verde en este árbol. Guarda de `*.spec.*` y AW10 (`git diff --name-only 87df591 -- apps/api`): vacíos.
- **Medida (800 líneas).** 718 líneas efectivas (713 adiciones y 5 eliminaciones netas por archivo: `access-session.test.ts` 360, `access-session.ts` 220, `current-session-query.test.ts` 57, `current-session-query.ts` 25, `index.test.ts` 14, `index.ts` 26, `refresh-coordinator.ts` 11 y 5) frente al commit de 6a. Sin pasarse de 800.

## Tarea 11: `simulated-api` (2026-11-07, 11a-1 con excepción del propietario a las 800 líneas)

- **Estado.** Parte 11a-1 escrita y en el árbol de trabajo, comprometida tras la excepción. Rama `change/admin-web-foundations-and-session-11a1-session-model-and-totp` desde `453b4b6`.
- **Medida (800 líneas).** 809 líneas efectivas frente a `453b4b6` (`session-model.test.ts` 382, `session-model.ts` 263, `totp.test.ts` 79, `totp.ts` 72, `tsconfig.json` 2, `vitest.config.ts` 5, `packages/config/vitest.js` 6). Supera 800 por 9 líneas; no se recorta nada. Corte propuesto, por archivos completos: **11a-1a** `totp.ts`, su prueba y el comentario de `packages/config/vitest.js` más la inclusión de `e2e/` (unas 170 líneas; SS119) / **11a-1b** `session-model.ts` y su prueba (unas 645; SS120, SS121). **Decisión del propietario (2026-11-07): 11a-1 queda como un solo PR de 809 líneas efectivas frente a `453b4b6`, excepción explícita a las 800 líneas; sin más cortes.**
- **ROJO (11.1).** `totp.test.ts` y `session-model.test.ts` no cargan: `Cannot find module './totp.ts'` y `Cannot find module '../support/totp.ts'` (la causa esperada: el módulo no existe; el de `session-model.ts` quedó tapado por la primera importación). 2 suites fallidas, 0 pruebas ejecutadas. VERDE: `totp` 12 pruebas; con `session-model.ts`, 40 de 40.
- **Demostraciones (11.3), con copia fuera del repositorio y `cmp` sin salida.** (1) Gracia de 30 s: SS120 `expected 200 to be 401` (la primera versión de la prueba solo caía por la constante; se pasó a los literales 9 999 y 10 000 ms para que caiga por el resultado). (3) Recuperación con `mfa-enroll` aceptada: SS121 `expected 200 to be 401`. (4) SHA-256: SS119 `expected '247374' to be '287082'`.
- **Suite U** en el árbol de 11a-1: 29 archivos, 326 pasan y 1 `todo`. C, T, UI y PR no corridas (no se comprometió).
- **Sonda de `runnerImport` (primera prueba de 11.1).** Pendiente a 11a-2 (`start.mjs` y su prueba de arranque real); su forma en `globalSetup` de Playwright, a la tarea 12.
- **Nota.** `contract-responses.ts` importa `node:crypto`: válido para un simulador Node. `omit({ instance: true })` ya existe desde la tarea 5.

### Parte 11a-2 (2026-11-07): partida en 11a-2a y 11a-2b por decisión del propietario

- **Estado.** Cerrada. 11a-2 midió 854 líneas (por encima de 800) y el propietario aprobó el corte el 2026-11-07. **11a-2a** `552945c` (rama `change/admin-web-foundations-and-session-11a2-simulated-api-server`): servidor, escenarios, control, centinelas y sus pruebas, **755 líneas** frente a `53ca356`. **11a-2b** `3b535a0` (rama `change/admin-web-foundations-and-session-11a2b-simulated-api-start`): `start.mjs`, `start.test.ts`, el guion `mock:api` y el `ignores` de ESLint para `start.mjs`, **99 líneas** frente a 11a-2a. Suites U (32 archivos, 354 pasan y 1 `todo`) y PR (18 de 18) en verde en cada parte; guarda de `*.spec.*` y AW10 vacíos. Los archivos de 11a-2b se apartaron con `git stash` para correr 11a-2a sin ellos.
- **Medida antes del corte.** 854 líneas efectivas frente a `53ca356`: `server.test.ts` 336, `server.ts` 242, `scenarios.ts` 133, `start.test.ts` 61, `start.mjs` 28, `sentinels.test.ts` 28, `sentinels.ts` 16, `eslint.config.js` 7, `package.json` 3. Supera 800 por 54. Corte aprobado, por archivos completos (arriba).
- **ROJO.** `sentinels.test.ts`: `Cannot find module './sentinels.ts'` (`sentinels.ts` se había escrito antes de la prueba; para el rojo se sacó del árbol y se repuso). `server.test.ts`: `Cannot find module '../support/sentinels.ts'` (tapaba `./server.ts`). `start.test.ts`: `Cannot find module '...\start.mjs'` (el proceso termina con código 1). 
- **VERDE.** 68 de 68 pruebas en `e2e/`. La primera corrida falló una: `fields` repetía `secret` por el mínimo y el patrón del esquema; se deduplicaron. `tsc` y ESLint limpios; `start.mjs` queda fuera de ESLint tipado (no pertenece al proyecto TypeScript) con un `ignores` comentado.
- **Sonda de `runnerImport`.** `start.test.ts` arranca `start.mjs` como proceso aparte: **`runnerImport` no externaliza el paquete enlazado** y el simulador carga `@confia/contracts/admin`. Queda abierta solo la forma en `globalSetup` de Playwright (tarea 12).
- **Demostraciones (copia fuera del repositorio, `cmp` sin salida).** (2) Enviar sin validar: `expected 201 to be 500`. (5) El control responde fuera de `localhost` (`isLocal` devuelve `true`): `expected [ 'evil.example', 303, 2 ] to deeply equal [ 'evil.example', 403, +0 ]`.
- **Humo (repetido sobre 11a-2b: health 200, semilla 303 con las dos cookies, `Host: evil.example` 403, detenido).** `pnpm --filter @confia/admin-web mock:api`: escucha en `http://localhost:8099` (enlace a `::1`, no a todas las interfaces); `GET /__control/health` 200; `GET /__control/seed-session` 303 a `http://localhost:5173/` con las dos cookies de WE62 y WE63; con `Host: evil.example`, 403. Detenido después.

### Tarea 11: cierre y control de sesión sembrada (2026-11-07)

- **Cortes.** 11a-1 `53ca356`: **809 líneas** frente a `453b4b6`, una sola PR por **excepción explícita del propietario (aprobada el 2026-11-07)**. 11a-2 partida en 11a-2a `552945c` (755) y 11a-2b `3b535a0` (99).
- **Sesión de prueba sembrada (adición aprobada el 2026-11-05).** Ruta `GET|POST /__control/seed-session` del simulador, **solo local**: responde únicamente a un `Host` local (`localhost`, `127.0.0.1`, `[::1]`) desde un par de loopback, y escucha solo en `localhost`. Deja las dos cookies de WE62 y WE63 de una cuenta ficticia y redirige (303) a `http://localhost:5173/`. `?scenario=<id>` siembra otro escenario. Pasos: (1) `pnpm --filter @confia/admin-web mock:api`; (2) `CONFIA_API_PROXY_TARGET=http://localhost:8099 pnpm --filter @confia/admin-web dev`; (3) abrir `http://localhost:8099/__control/seed-session` en el navegador. No existe fuera del simulador, no se sirve desde el build y no es prueba de la pila real. Demostración: que el control responda fuera de `localhost` pone en rojo la prueba de `Host` ajeno.

## Tarea 7: `ui-alert-and-fixes` (2026-11-07)

- **Ruta.** Escritor delegado único, rama `change/admin-web-foundations-and-session-7-ui-alert-and-fixes` desde `d4297cc`. Commits: `1b1c299` `feat(ui): add the alert molecule` y `5968e3f` `fix(ui): keep the focus ring out of the button transition and never render empty labels`.
- **Superficies que faltaban en el plan (decisión del propietario, 2026-11-07).** La prueba de exportaciones del plan se llama `packages/ui/src/public-api.test.ts` (no existen `index.test.ts` ni `exports.test.ts`). Además hicieron falta `packages/ui/src/story-states.test.ts` (fila de `Alert` en DS63, con el estado `Destructive`, el único que le aplica: no tiene carga ni vacío) y `packages/ui/src/no-network.test.tsx` (DS61 exige un *fixture* por cada componente exportado; solo se importó `Alert` y se añadió su *fixture*).
- **Orden TDD.** Las pruebas se escribieron antes que el código de producción; no hay desviación.
- **ROJO (7.1), causa observada.**
  - `Alert.test.tsx` no carga: `Failed to resolve import "./Alert"`; `Alert.test-d.ts`: `Unused '@ts-expect-error'`; `Alert.a11y.test.tsx`: el mismo `Failed to resolve import "./Alert"`.
  - DS91: con la etiqueta vacía y con solo espacios, no existe el botón «Reintentar». DS92: `expected [ 'No pudimos cargar los cargos', '' ] to deeply equal [ 'No pudimos cargar los cargos' ]` (en `EmptyState`, `[ 'Sin cargos', '   ' ]`).
  - `public-api.test.ts`: DS02 y DS62/DS73 fallan por la lista de 18 componentes.
  - DS90 en Chromium (corrido solo con `atoms/button`): `expected [ 'color', 'background-color', …(8) ] to not include 'outline-color'` y `expected 'rgb(255, 255, 255)' to be 'rgb(59, 130, 246)'` (el anillo seguía a mitad de transición en el primer fotograma). Corrido junto con el directorio de `Alert`, la segunda aserción falló además por el *overlay* de error de Vite, no por la causa esperada.
- **VERDE y REFACTOR (7.2).** `Alert` con `cva`, iconos `Info`, `TriangleAlert` y `CircleAlert`, texto `alert.severity.*`, `tabIndex={-1}` y `ref` por propiedad; nueve *tokens* `--alert-*` hacia `--color-status-{pending,partial,overdue}-*`. Primera corrida unit: fallos de lint (la regla prohíbe `querySelector` fuera de `*.a11y.test.tsx`): las aserciones del icono pasaron a `Alert.a11y.test.tsx` y las de párrafo vacío usan `getAllByRole("paragraph")`. `tsc` y ESLint limpios.
- **Demostraciones deliberadas (7.3).** Copia fuera del repositorio, ruptura, rojo, restauración y `cmp` sin salida:

  | # | Ruptura | Rojo observado | `cmp` |
  |---|---|---|---|
  | 1 | `transition-colors` en `Button.tsx` | DS90: `expected [ 'color', 'background-color', …(8) ] to not include 'outline-color'` y el color del anillo `rgb(255, 255, 255)` frente a `rgb(59, 130, 246)`; 2 fallos de 2 | idéntico |
  | 2 | Sin `trim` en la etiqueta de `ErrorState` | DS91 (solo espacios): falla por el nombre accesible; 1 fallo de 13 | idéntico |
  | 3 | `p` siempre renderizado en `ErrorState` y `EmptyState` | DS92: `expected [ <p …(1)></p>, <p …(1)></p> ] to deeply equal [ <p …(1)></p> ]`; 6 fallos de 22 | idéntico |
  | 4 | `role="status"` en `destructive` (roles invertidos) | DS83 y otras 3: `Unable to find an accessible element with the role "status"`/`"alert"`; 4 fallos de 11 | idéntico |

- **Suites.** UI unit: 54 archivos, 544 pasan. UI `test:a11y`: 10 archivos, 175 pasan; las cinco historias de `Alert` pasan en ambos temas. Guarda de `*.spec.*` sobre `apps/` y AW10 (`git diff --name-only d4297cc -- apps/api`): vacíos.
- **Medida (800 líneas).** 421 líneas efectivas frente a `d4297cc` (414 adiciones, 7 eliminaciones): 327 en el commit de `Alert` y 94 en el de las correcciones. Sin corte.
- **Saltos de línea.** El repositorio fuerza LF (`eol=lf`); el aviso de CRLF de Git era solo informativo y `git diff --stat` coincide con `--ignore-cr-at-eol`: ningún archivo se reescribió entero.
- **Suite PR.** La primera corrida (`pnpm turbo run lint typecheck test build --filter=!@confia/api --concurrency=1`) terminó con dos fallos en `admin-web`: `src/main.test.tsx` («mounts the app into #root») y `e2e/mock-api/start.test.ts` («loads the TypeScript simulator…»). **El texto del error no se capturó** (la salida estaba filtrada); ambas pruebas pasan solas (3 de 3) y comparten el patrón de tiempo de espera bajo carga que ya se vio en las tareas 5 y 6. Una única repetición completa, con la salida a un archivo, dio **18 de 18 tareas** sin fallos; no se relanzó más.

## Tarea 9: `boot-and-signed-in-page` (2026-11-07, 9a con excepción del propietario a las 800 líneas)

- **Ruta.** Escritor delegado único, rama `change/admin-web-foundations-and-session-9a-boot-and-guards` desde `f950a8a`. Estado: 9a escrita y verde en el árbol de trabajo, **sin comprometer**; 9b sin empezar.
- **Medida 9a (800 líneas).** 875 líneas efectivas frente a `f950a8a` (850 adiciones y 25 eliminaciones): `BootGate.test.tsx` 151, `routes.test.tsx` 150, `routes.tsx` 123, `render-app.tsx` 48, `BootGate.tsx` 41, `router.tsx` 35, `NotFound.tsx` 31 y su prueba 21, `ScreenLayout.tsx` 30 y su prueba 32, `BootScreen.tsx` 28 y su prueba 28, `structure.test.ts` 27, `AppProviders.tsx` 19 y su prueba 14, `AccessPlaceholder.tsx` 16 y su prueba 22, `App.tsx` 14, `App.test.tsx` 18, `NotFoundContainer.tsx` 10, `use-session-view.ts` 7, `admin.json` 6, `main.tsx` 4. Supera 800 por 75; no se recorta nada.
- **Corte propuesto, por archivos completos.** **9a-2** `not-found-and-static-absences`: `NotFound.tsx`, `NotFound.test.tsx`, `NotFoundContainer.tsx`, la prueba de AW18 (pasa de `routes.test.tsx` a un archivo propio) y los tres casos de AW60, AW61 y AW63 de `structure.test.ts`, unas 100 líneas. Dejaría 9a en unas 775. Alternativa: excepción explícita del propietario a las 800 líneas, como en las tareas 3, 4a, 5 y 11a-1.
- **ROJO (9.1), causa observada.** Cuatro suites no cargan: `Failed to resolve import "./NotFound.tsx"`, `"./AccessPlaceholder.tsx"`, `"./ScreenLayout.tsx"` y `"./BootScreen.tsx"`. Las pruebas de la aplicación completa fallan con `TypeError: Cannot read properties of undefined (reading 'state')`: `createAppRouter` seguía recibiendo el historial y no el objeto `{ session, history }`. `AppProviders` (SS33): `expected '' to be 'es-HN'`. AW60, AW61 y AW63 pasan antes del código porque afirman ausencias; AW60 exige además que el conjunto de fuentes no esté vacío. 27 pruebas en rojo de 54. Las pruebas se escribieron antes que el código de producción.
- **VERDE.** Primera corrida con producción: 58 de 61; las 3 fallas eran una causa de diseño, no de las pruebas (ver desviación 1). Después, 61 de 61. Una prueba (SS34) falló una vez por un defecto de la propia prueba (`queryByLabelText` encontraba la etiqueta «Cargando» del indicador) y se quitó esa aserción.
- **Desviaciones del diseño.**
  1. **`BootGate` no inicia el arranque sin cookie CSRF.** Con `boot()` llamado siempre, el `no-session` del coordinador terminaba después de que una guarda hubiera puesto el aviso de llegada (o conservado el token restringido) y los borraba (`endLocally()` sin aviso). La vista queda en `restoring`, que ninguna guarda ni pantalla trata como sesión. SS07 y SS08 siguen afirmando cero refrescos.
  2. **La guarda de la raíz descarta solo si hay un token restringido.** `discardSecondFactor()` sin token vuelve a mostrar la vista `anonymous` sin aviso y habría borrado el aviso de SS11 al llegar a `/iniciar-sesion`.
  3. **Estilos.** `main.tsx` importa `@confia/ui/styles.css`; sin él la pantalla no tiene tokens. No estaba en la lista de archivos de la tarea.
  4. **`/` en 9a** es una página provisional (`ScreenLayout` con `signedIn.title` y `signedIn.message`); 9b la reemplaza por el contenedor real.
  5. **Claves nuevas del catálogo** (no estaban en la tabla de textos del diseño): `signIn.title`, `secondFactor.title`, `enrollment.title`, `boot.title`.
  6. **Pendiente fuera de la superficie permitida:** el `it.todo` de AW28 en `src/i18n/catalog.test.ts` («toda clave usada por un componente existe en el catálogo») no se pudo activar; `tsc` ya lo cubre por `AdminKey`.
- **Suites en el árbol sin comprometer.** `tsc` y ESLint limpios. **U**: 38 archivos, 389 pasan y 1 `todo`, cobertura 98,79/96,6/98,14/99,65.
- **Decisión del propietario (2026-11-07).** 9a queda como un solo PR de **875 líneas efectivas (850 adiciones y 25 eliminaciones frente a `f950a8a`), excepción explícita a las 800 líneas**; sin corte. La propuesta 9a-2 no se aplica.
- **Verificación explícita de la desviación 1.** Prueba nueva en `BootGate.test.tsx` (SS07, SS08, SS10): sin cookie, en `/` y en `/iniciar-sesion`, `boot()` no se llama, la vista queda en `restoring`, el primer render no muestra la pantalla de restauración, aparece el `h1` «Iniciar sesión» sin aviso y hay cero refrescos. Resultado: pasa; ninguna guarda ni pantalla trata `restoring` como bloqueo (las guardas solo comparan con `authenticated`; `SignInContainer` solo lee el aviso en `anonymous`). Sin defecto que corregir.
- **Demostraciones deliberadas (9.3) de 9a, con copia fuera del repositorio y `cmp` sin salida.**

  | # | Ruptura | Rojo observado | `cmp` |
  |---|---|---|---|
  | 1 | `BootGate` muestra la restauración aunque no haya cookie (se quita `&& hasCookie`) | SS07 en las 5 rutas y la prueba nueva: `expected document not to contain element, found <p ...>` (el mensaje de restauración); 8 fallos de 15. El rojo es por la pantalla de restauración: los cero refrescos los garantiza el coordinador (tarea 6), no la puerta | idéntico |
  | 4 | Se quita `heading.current?.focus()` de `ScreenLayout` | SS32 (`ScreenLayout.test` y `routes.test`): `expect(element).toHaveFocus()`; 2 fallos | idéntico |

### Cierre de 9a (2026-11-07)

- **Commit** `e29f779` `feat(identity): restore the session on boot and guard the access routes`, rama `change/admin-web-foundations-and-session-9a-boot-and-guards`. Medida final: **892 líneas efectivas (867 adiciones y 25 eliminaciones) frente a `f950a8a`**; la diferencia con las 875 aprobadas son las 17 líneas de la prueba explícita de la desviación 1 que pidió el coordinador. El propietario aprobó el PR único (excepción a las 800); la cifra final queda registrada aquí para que la confirme.
- **Suites sobre el árbol de 9a solo.** **U** 38 archivos, 391 pasan y 1 `todo`. **T** 9 archivos, 270 pasan y 3 `todo`. **PR** 18 de 18 tareas. Guarda de `*.spec.*` y AW10 (`git diff --name-only f950a8a -- apps/api`): vacíos. Registros completos en el directorio temporal del trabajo.

## Tarea 9, parte 9b: página mínima, cierre de sesión y flujo (2026-11-07)

- **Rama** `change/admin-web-foundations-and-session-9b-signed-in-page` desde `e29f779`.
- **Medida 9b (800 líneas).** 584 líneas efectivas (564 adiciones y 20 eliminaciones) frente a `e29f779`. Suites: **U** 42 archivos, 417 pasan y 1 `todo`, cobertura 98,06/95,25/98,26/99,01; **T** 9 archivos, 273 pasan, 0 `todo`; **PR** 18 de 18 (la primera corrida falló en `keys.check.test.ts` por tiempo de espera de 60 s del programa de TypeScript, con la máquina muy cargada: la suite duró 206 s; y mostró avisos de MSW por lecturas de `/sessions/current` sin ruta en pruebas de 9a, que ahora declaran la ruta; la repetición dio 18 de 18). Guarda de `*.spec.*` y AW10 vacíos.
- **ROJO (9.1), causa observada.** `SignedInPage.test.tsx` no carga: `Failed to resolve import "./SignedInPage.tsx"`. Con el `/` provisional de 9a, 13 de las 14 pruebas nuevas de `SignedInContainer`, `session-secrets.flow` y página fallan por la causa esperada (la página no lee la sesión ni ofrece «Cerrar sesión»: `Unable to find an accessible element with the role "button" and name "Cerrar sesión"`, `Unable to find role="alert"`, SS06 `expected '{"queries":…' to contain` el identificador). SS112 pasa antes del código, porque es comportamiento de 9a. SS18 falló por un defecto de la prueba (Testing Library sondea con su propio `setInterval` de 50 ms); la aserción pasó a «ningún intervalo de un segundo o más».
- **VERDE (9.2).** `SignedInContainer` (consulta de la sesión actual con `currentSessionQueryOptions`, `useMutation` solo para `signOut`, redirección por la vista), `SignedInPage` (carga, error, listo y cierre fallido) y la traducción del fallo con `PROBLEM_KEYS`. Cuatro claves nuevas: `signedIn.loading`, `signedIn.signOut`, `signedIn.signOutFailed`, `signedIn.retrySignOut`. Dos fallos de pruebas en la primera corrida: el botón ocupado se expresa con `loading` de `Button` (pone `aria-busy` y conserva el foco), y su nombre accesible incluye el indicador.
- **Pruebas de herramientas.** BX46, BX61 y BX62 dejan de ser `todo` (`T`: 273 pasan, 0 `todo`). BX61 lee la importación de la biblioteca del fuente porque la corrida de `dependency-cruiser` solo conserva archivos del repositorio. AW57 y AW58 se afirman por enumeración en `presentation-states.test.ts`.
- **Desviaciones.**
  1. **Centinelas de SS103 y SS06.** El escenario nombra «CENT-CUENTA», «CENT-INSTITUCION» y «CENT-SESION», pero el contrato exige UUID en `accountId`, `institutionId` y `sessionId`, así que los centinelas son UUID generados en ejecución.
  2. **Reintento del cierre.** El botón cambia su etiqueta a «Reintentar cierre de sesión» junto al `Alert`; no hay un segundo botón.
  3. **Tiempos explícitos** en SS103 y SS06 (primeras pruebas de su archivo): la primera corrida de la aplicación completa construye el grafo en frío y superó 1 s de `findBy` bajo la cobertura completa; falló dos veces seguidas en la suite completa, y se fijó el plazo de espera y de la prueba como en `main.test.tsx`. Ninguna aserción depende del tiempo.
- **Demostraciones deliberadas (9.3) de 9b, con copia fuera del repositorio y `cmp` sin salida.**

  | # | Ruptura | Rojo observado | `cmp` |
  |---|---|---|---|
  | 2 | `signOut` declara la sesión cerrada sin exigir `204` (`access-session.ts`) | SS109 (red y `403 csrf-invalid`): `Unable to find role="alert"`; 2 fallos de 11 | idéntico |
  | 3 | `readCurrentSession` agrega el token a los datos de la consulta (`access-session.ts`) | SS06: `expected '{"queries":{"mutations":[],"queries":…' not to contain 'access-…'`; 1 fallo de 4 | idéntico |
  | 5 | Selector de presentación sin archivos reales (`.dependency-cruiser.cjs`: `components/` por `presentation/`) | BX62: `the selector (^|/)apps/admin-web/src/presentation/|… matches no real file`, y 7 fallos más de las reglas de presentación; 8 de 115 | idéntico |

### Comprobación manual contra el simulador (9b, 2026-11-07)

Sin navegador: no se afirma ningún resultado visual. `pnpm --filter @confia/admin-web mock:api` escuchó en `http://localhost:8099` (`/__control/health` 200). `CONFIA_API_PROXY_TARGET=http://localhost:8099 pnpm --filter @confia/admin-web dev` sirvió `http://localhost:5173/` (200, `text/html`, `lang="es-HN"`) y `/src/main.tsx` (200). `GET http://localhost:8099/__control/seed-session` respondió 303 a `http://localhost:5173/` con las dos cookies (`__Secure-confia_admin_sid` con `HttpOnly` y `Path=/api/v1/auth/sessions`; `__Host-confia_csrf` con `Path=/`). Con ese frasco de cookies, a través del proxy del servidor de desarrollo: `POST /api/v1/auth/sessions/refresh` con `Origin` y `X-CSRF-Token` respondió 200 (`status: authenticated`), y `GET /api/v1/auth/sessions/current` con `Bearer` respondió 200 con los siete campos del contrato. Es lo que hace la aplicación al recargar con la sesión sembrada. Ambos procesos se detuvieron (ningún puerto 5173 ni 8099 escuchando).
- **Cierre de la tarea 9:** 9.1 a 9.4 marcadas. Pendiente para el propietario: ver la pantalla en un navegador (abrir primero `/__control/seed-session`).

## Tarea 8: `ui-form-error-summary` (2026-11-07)

- **Ruta.** Escritor delegado único, rama `change/admin-web-foundations-and-session-8-ui-form-error-summary` desde `43c07d0`. Commit `3a25633` `feat(ui): add the form error summary and let form field take its control id`.
- **Superficies.** La prueba de voz de `ui` no existía: se creó `packages/ui/src/i18n/voice.test.ts` (DS93, DS94), con las reglas de `ui` de D17 construidas sobre `ADMIN_VOICE_RULES`. También se tocaron `public-api.test.ts`, `no-network.test.tsx` y `story-states.test.ts`.
- **ROJO (8.1), causa observada.** `FormErrorSummary.test.tsx` y `FormField.test.tsx`: `Failed to resolve import "./FormErrorSummary"` y `"../form-error-summary"`; `tsc`: `controlId` no existe en el tipo de `FormField`. DS02, DS62/DS73, DS61 (fixture), DS63, DS95 y DS88 fallan por la lista de 18 y por la historia ausente; DS93 por `formErrorSummary` ausente del catálogo. 8 pruebas en rojo más 2 archivos que no cargan. Las pruebas se escribieron antes que el código de producción.
- **VERDE y REFACTOR (8.2).** `useSyncExternalStore` para los controles presentes, sin efecto; `<a href="#id">` con `preventDefault` y `focus()`; plural ICU. Ajustes: DS77 (el foco inicial de la prueba), la regla `no-confusing-void-expression` y `target-size` (serio) en la historia `Several`, resuelto con `min-h-6` en el enlace.
- **Demostraciones deliberadas (8.3), con copia fuera del repositorio y `cmp` sin salida.**

  | # | Ruptura | Rojo observado | `cmp` |
  |---|---|---|---|
  | 1 | Sin `preventDefault` | DS78: `expected 'http://localhost:3000/#b' to be 'http://localhost:3000/'`; 1 fallo de 10 | idéntico |
  | 2 | Una exportación más en `index.ts` | DS02: `expected [ 'Alert', 'Badge', 'Button', …(19) ] to deeply equal […(18) ]`; 1 fallo de 5 | idéntico |
  | 3 | `htmlFor` ignora `controlId` | DS98: `for="_r_1v_-control"` frente a `identifier`; 1 fallo de 53 | idéntico |
  | 4 | «Revise» en `ui.json` (`common.required`) | DS93: `common.required: revise (sentence-start)`; 1 fallo de 5 | idéntico |

- **Suites.** UI unit: 56 archivos, 566 pasan. UI `test:a11y`: 10 archivos, 178 pasan. PR: 18 de 18 tareas. Guarda de `*.spec.*` y AW10 vacíos.
- **Medida (800 líneas).** 434 líneas efectivas frente a `43c07d0` (430 adiciones, 4 eliminaciones). Sin corte.
- **Texto nuevo, pendiente de aprobación del propietario.** `formErrorSummary.heading`: `{count, plural, one {Hay # campo con error.} other {Hay # campos con errores.}}`. El texto de las historias va embebido en español de ejemplo, como en las demás historias.
- **Cierre de la tarea 8:** 8.1 a 8.4 marcadas.

## Tarea 10: `a11y-policy-move-and-adr` (2026-11-07)

- **Ruta.** Escritor delegado único, rama `change/admin-web-foundations-and-session-10-a11y-policy-move-and-adr` desde `7bf5212`. Commits `de5624c` `refactor(config): move the accessibility policy to the shared config` y `a54c360` `docs(adr): propose ADR-0028 on axe over rendered routes`.
- **Desviación de ubicación.** El plan dice `packages/config/{a11y.ts,a11y.test.ts}`; por la decisión del propietario de la tarea 3 (el paquete solo prueba y revisa tipos de `src/`) quedaron en `packages/config/src/a11y.ts` y `a11y.test.ts`, y la exportación `./a11y` apunta a `./src/a11y.ts` (`types` y `default`). `policy.ts` se movió con `git mv` y se detecta como renombre (`-M`). `policy.test.ts` se movió con `git mv` pero se reescribió tanto (importación, BX71, BX72, BX70, BX100, BX101, BX106, BX107) que Git lo cuenta como una eliminación y un archivo nuevo.
- **ROJO (10.1), causa observada.** `a11y.test.ts` escrito antes del código de producción, con `from "@confia/config/a11y"`. La prueba no carga: `Error: "./a11y" is not exported under the conditions ["node", "development", "import"] from package C:/dev/confia-frontend/packages/config`. Es la forma que toma `Cannot find module '@confia/config/a11y'` con la autorreferencia del paquete (la exportación no existía). El archivo entero fallaba, de modo que BX66 a BX72 y los documentales quedaron en rojo por esa sola causa.
- **VERDE y REFACTOR (10.2).** `git mv` de la política y cambio de una importación en `run-gate.ts` y en `.storybook/preview.tsx` (más el comentario de cabecera de `a11y.ts`, que ya no dice «de `@confia/ui`»). `package.json` de config: descripción, exportación `./a11y` y `axe-core: "catalog:"` en `devDependencies` (versión 4.14.0 ya del catálogo). Tras el traslado, los tests documentales seguían en rojo por la causa esperada (sin ADR-0028, sin nota en ADR-0026, sin mención en `CLAUDE.md` y `docs/01`: `ENOENT ... ADR-0028-...md`, `expected '' to match /Nota fechada 2026-11-07/`, `expected '| Pruebas del frontend | ...' to match /rutas renderizadas/`) y pasaron al agregar los documentos. ADR-0028 (Propuesto), fila en `docs/adr/README.md`, nota fechada solo agregada al final de ADR-0026 (el prefijo original de 8398 bytes se verifica por SHA-256), una frase en la fila de pruebas del frontend de `CLAUDE.md` y un punto nuevo en `docs/01` §3.
- **`pnpm-lock.yaml`.** `pnpm install --offline` reorganizó 77 líneas ajenas (variantes de `vitest` y entradas opcionales de `@vitest/browser`); se descartó y se editó a mano solo el bloque `axe-core` del importador `packages/config` (3 líneas). La instalación `--frozen-lockfile --offline` no se pudo comprobar sin red (`ERR_PNPM_NO_OFFLINE_META`); queda para CI.
- **Demostraciones deliberadas (10.3), con copia fuera del repositorio y `cmp` sin salida.**

  | # | Ruptura | Rojo observado | Restauración |
  |---|---|---|---|
  | 1 | `REPORTED_IMPACTS = ["minor"]` (`moderate` pasa a bloquear) | BX67: `expected { blocking: [ { …(7) }, …(2) ], …(1) } to deeply equal { …(2) }`; 4 fallos de 24 (también DS69, el orden mixto y la lista de impactos reportados) | `cmp` idéntico |
  | 2 | Copia de la política en `packages/ui/src/a11y/policy.ts` | BX71 y DS96 (misma prueba): `expected [ Array(1) ] to deeply equal []`; 1 fallo de 24 | archivo creado por la demostración y eliminado; `git status` sin él |
  | 3 | `export * from "@confia/config/a11y"` en `packages/ui/src/index.ts` | BX72: `expected '// The single public entry point of @…' not to match /a11y\|policy\|@confia\/config/`; 1 fallo de 24 | `cmp` idéntico |

- **Suites.** **C** 4 archivos, 48 pasan (más `tsc` y ESLint de config limpios). **UI** `test`: 55 archivos, 553 pasan; `test:a11y`: 10 archivos, 178 pasan (igual que antes). **T** 9 archivos, 273 pasan. **PR** 18 de 18 tareas. Guarda de `*.spec.*` y AW10 (`git diff --name-only 7bf5212 -- apps/api`) vacíos; `gate.a11y.test.tsx` sin cambios (DS97).
- **Falla observada y no reproducida después.** Tras el traslado, `run-gate.test.ts` > «restores the page, and removes the container, when the story throws» falló dos veces seguidas (en la suite completa de UI y en solitario): `expected [Function] to throw error including 'play failed' but got ''`. Con el árbol de `7bf5212` pasaba (15 de 15). Después, la misma prueba pasó tres veces en solitario y la suite completa de UI pasó (553 de 553), sin cambiar ningún archivo entre las corridas. Se sospecha de la primera carga en frío del módulo enlazado (`@confia/config/a11y` resuelto fuera de la raíz), sin confirmar. No se pudo reproducir; queda anotado por si reaparece en CI.
- **Medida (800 líneas).** 485 líneas efectivas frente a `7bf5212` (388 adiciones, 97 eliminaciones). Sin corte.
- **Otros.** El commit `de5624c` contiene `a11y.test.ts` sin el bloque documental, para que no entre en rojo; `a54c360` agrega ese bloque junto con los documentos. Este PR no marca el criterio 8 de la fase 0. `apps/api` no cambia.
- **Cierre de la tarea 10:** 10.1 a 10.4 marcadas. ADR-0028 queda en Propuesto, pendiente de aprobación del propietario.
