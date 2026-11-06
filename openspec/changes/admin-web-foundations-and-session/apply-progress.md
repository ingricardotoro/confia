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
- **Estado:** código, pruebas y demostraciones hechos; **sin commit** a la espera de un PR completo en verde. **Excepción del propietario (2026-11-06):** un único PR de **833 líneas efectivas (833 adiciones y 0 eliminaciones frente a `335457b`)**, excepción explícita al límite de 800 de `docs/15` §3; sin corte. La propuesta 5a/5b de «Medida» queda descartada.

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

### Medida (regla de las 800 líneas)

`git diff --cached --numstat -M 335457b -- . ':(exclude)openspec' ':(exclude)pnpm-lock.yaml' ':(exclude)packages/contracts/src/generated'` con todo lo previsto en el índice: **833 líneas efectivas (833 adiciones, 0 eliminaciones)**. **Supera 800: no se compromete.** Desglose: `auth-operations.ts` 117, `auth-operations.test.ts` 274, `auth-operations-query.test.ts` 78, `contract-responses.ts` 157, `contract-responses.test.ts` 124, `msw-server.ts` 80, `package.json` 2, `pnpm-workspace.yaml` 1. La tarea no tiene corte predefinido (la estimación a 4x era 670). Propuesta de corte por archivos completos, con sus pruebas, para decisión del propietario: **5a** `contract-responses.ts` y su prueba, más el enlace de `@confia/contracts` (≈ 282); **5b** `msw-server.ts`, `auth-operations.ts` y sus dos pruebas, más `msw` en el catálogo y el archivo de bloqueo (≈ 551).

### Suites

- **U** (`pnpm --filter @confia/admin-web test`): una corrida en verde antes de mover AW20 a AW22 (21 archivos, 237 pasan y 1 `todo`, cobertura 100/97,82/100/100). Después, con la máquina sin memoria (1 a 2 GB libres), las corridas completas fallan por **tiempos de espera de 5 000 ms o por no poder arrancar procesos** (`main.test.tsx`, `vite.config.test.ts`, `App.test.tsx`, `i18n.test.tsx`, a veces `keys.check.test.ts`); los mismos archivos pasan solos (8 archivos, 41 pruebas), y con `--maxWorkers=2` pasaron 236 de 237 (un tiempo de espera en `vite.config.test.ts`). Los 39 casos de la tarea 5 pasan siempre. **El cierre con U y PR completos queda pendiente de una máquina descargada.**
- **PR:** la primera corrida detectó la violación de `admin-shared-never-imports-features` (desviación 2). Corregida, `tooling/src/dependency-rules.test.ts` y `eslint-app-rules.test.ts` pasan (112 y 3 `todo`); `lint` y `typecheck` de `admin-web` pasan. No se repitió el PR completo por la falta de memoria.
- Guarda de `*.spec.*` sobre `apps/` y AW10: ver el informe de cierre.

## Tarea 5: cierre (2026-11-06)

- **Tiempo límite de las pruebas de arranque.** La suite de PR falló dos veces por `Test timed out in 5000ms` en `main.test.tsx` («mounts the app into #root»), también con 4,7 GB libres. Causa observada: la prueba usa `vi.resetModules()` e importa `main.tsx` en frío, de modo que recompila todo el grafo de la app; sola, esa importación ya ocupa casi 1,7 s. Por decisión del propietario, `2ef41f5` da a ese `describe` y al del proxy de `vite.config.test.ts` un tiempo límite propio de 20 s, sin cambiar ninguna aserción.
- **Suites después del ajuste:** `pnpm turbo run lint typecheck test build --filter=!@confia/api --concurrency=1 --force`: 18 de 18 tareas; app 237 pruebas y 1 pendiente.
- **Commits:** `2ef41f5` (tiempo límite) y `fe64bed` (operaciones de acceso y respuestas de contrato). Medida del PR contra `335457b`: 841 líneas efectivas, 833 de la tarea más 8 del ajuste, dentro de la excepción aprobada.
