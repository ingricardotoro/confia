# Avance de aplicación: `admin-web-foundations-and-session`

- **Cambio:** `admin-web-foundations-and-session` (13b-1 de F0, parte 1 de 2)
- **Modo:** TDD estricto. Comandos por superficie según `tasks.md` (pnpm y Vitest); el `test_command` de Maven no se usa.
- **Última actualización:** 2026-11-05
- **Entrega:** PR apilados (`stacked-to-main`), 800 líneas efectivas por PR (`docs/15` §3)

## Estado acumulado

| Tarea | Estado | Rama | Líneas efectivas |
|---|---|---|---|
| 1 `app-static-rules` | **Hecha** (sin corte) | `change/admin-web-foundations-and-session-1-app-static-rules` | 702 de 800 |
| 2 a 12 | Pendientes | | |

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
