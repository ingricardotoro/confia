# Delta para Integridad de la construcción

- **Estado:** actualizado el 2026-11-05 con los cambios de especificación que el propietario aprobó junto con el diseño
- **Cambio:** `admin-web-foundations-and-session` (F0, cambio 13b-1, parte 1 de 2). Los escenarios del criterio de salida 8 y de la matriz completa viven en `admin-web-staff-sign-in`.
- **Propuesta:** `proposal.md`, aprobada por el propietario el 2026-11-04 (P6: sin semilla ni CORS en `apps/api`; P8: Playwright
  con axe sobre rutas renderizadas bajo un ADR nuevo, con la política de bloqueo en `@confia/config`), con Q1 resuelta el 2026-11-05

**Convenciones de este delta.**

- Los escenarios nuevos se numeran `BX38` en adelante, a continuación del máximo de la serie frontend de 13a (`BX37`). La
  serie `BI` de los requisitos de backend (máximo `BI139` en el delta del cambio 8) no se toca. El prefijo `BX` no aparece en
  los encabezados de la especificación principal; sus identificadores `BX01` a `BX37` salen de `tasks.md` de 13a y de las
  pruebas.
- Este delta solo agrega requisitos: ninguna afirmación vigente de `build-integrity` deja de ser cierta. Los requisitos
  «Reglas de internacionalización y de formato en el análisis estático de `packages/ui`», «Aritmética y conversión a `number`
  de importes prohibidas en `packages/ui`», «Reglas de dependencia del frontend» y «La verificación de historias entra a la
  integración continua solo de forma aditiva» siguen diciendo la verdad sobre `packages/ui` y sobre el comando fijado del
  trabajo `frontend`.
- **Valores fijados por el diseño** (`design.md`, sección 4 y D2, D3, D14 a D16 y D19):
  - **ESLint:** `appSourceRules()` y `confiaEslint({ app: true })`; `uiSourceRules` conserva firma y mensajes.
  - **Carpetas:** presentación, `src/**/components/`; red, `src/shared/api/**` con `ignores` exacto.
  - **Interfaces de red prohibidas, además de las cuatro:** `Worker`, `SharedWorker` y `navigator.sendBeacon`.
  - **Fixtures:** ESLint, en línea en `tooling/src/eslint-app-rules.test.ts`; dependencias, `tooling/dependency-fixtures/apps/admin-web/src/`;
    claves, `apps/admin-web/src/i18n/__fixtures__/*.fixture`; supresiones, `tooling/src/__fixtures__/suppressions/`; puerta,
    `apps/admin-web/e2e/fixtures/a11y/*.html`.
  - **Informes:** `apps/admin-web/reports/a11y/report.json` y `reports/e2e/`; artefacto `admin-web-e2e-report`.
  - **Límite del trabajo nuevo:** 20 minutos. **ADR nuevo:** ADR-0028, en estado Propuesto.
  - **Versiones:** se fijan en el PR 2 (las de QR, en el PR 14), publicadas hace más de 14 días, con `pnpm audit --audit-level high`
    limpio y `pnpm why` sin duplicar `react` ni `axe-core`; `@playwright/test` exactamente 1.64.1.
- Toda regla de este delta lleva su violación deliberada y no aprueba sobre un conjunto vacío (ADR-0018).

## ADDED Requirements

### Requisito: Las reglas de texto, de formato, de claves y de aritmética de importes rigen también en `apps/admin-web`, y las de red y de capas siguen siendo exclusivas de `packages/ui`

El sistema DEBE romper el análisis estático del monorepo cuando el código de producción de `apps/admin-web` incurre en: (1) un
literal de texto en JSX, visible o de nombre accesible (`aria-label`, `title`, `alt`, `placeholder`); (2) una llamada a
`toFixed` o a `toLocaleString`; (3) una clave de traducción que no existe en el catálogo `admin` (`AdminKey`) o que no puede
resolverse estáticamente; o (4) una conversión a `number`, un redondeo o una operación aritmética sobre el importe de un `Money`
(`CLAUDE.md`, reglas 1 y 3). La configuración de ESLint del monorepo DEBE separar estas reglas comunes de las exclusivas de
`packages/ui` (la red prohibida y las capas), de modo que la aplicación reciba las primeras y no las segundas. Separarlas NO
DEBE debilitar a `packages/ui`. Cada regla DEBE tener su violación deliberada en `apps/admin-web` y NO DEBE aprobar sobre un
conjunto vacío.

#### Escenario: [BX38] Literal de texto en JSX de la aplicación

- **DADO** la violación deliberada de un componente de `apps/admin-web` con el texto «Entrar» escrito dentro del JSX
- **CUANDO** se ejecuta el análisis estático del monorepo
- **ENTONCES** falla nombrando el archivo y la regla de literales

#### Escenario: [BX39] Literal en un atributo de nombre accesible de la aplicación

- **DADO** la violación deliberada de un botón de `apps/admin-web` con `aria-label="Cerrar"` como literal
- **CUANDO** se ejecuta el análisis estático del monorepo
- **ENTONCES** falla nombrando el archivo y la regla de literales

#### Escenario: [BX40] `toFixed` en la aplicación

- **DADO** la violación deliberada de un archivo de `apps/admin-web` que invoca `toFixed(2)`
- **CUANDO** se ejecuta el análisis estático del monorepo
- **ENTONCES** falla nombrando el archivo y la regla de formato manual

#### Escenario: [BX41] `toLocaleString` en la aplicación

- **DADO** la violación deliberada de un archivo de `apps/admin-web` que invoca `toLocaleString`
- **CUANDO** se ejecuta el análisis estático del monorepo
- **ENTONCES** falla nombrando el archivo y la regla de formato manual

#### Escenario: [BX42] Clave inexistente del catálogo `admin`

- **DADO** la violación deliberada de un componente de `apps/admin-web` que usa una clave ausente del catálogo `es-HN` `admin`
- **CUANDO** se ejecuta la verificación de claves
- **ENTONCES** falla nombrando la clave y el archivo; el catálogo es el tipo `AdminKey`, derivado del archivo `es-HN` del
  catálogo `admin`

#### Escenario: [BX43] Clave del catálogo `admin` construida por concatenación

- **DADO** la violación deliberada de un componente de `apps/admin-web` que construye una clave por concatenación de cadenas
- **CUANDO** se ejecuta la verificación de claves
- **ENTONCES** falla nombrando el archivo e indicando que la clave no puede verificarse

#### Escenario: [BX44] Conversión del importe a `number` en la aplicación

- **DADO** la violación deliberada de un archivo de `apps/admin-web` que ejecuta `Number(money.amount)`
- **CUANDO** se ejecuta el análisis estático del monorepo
- **ENTONCES** falla nombrando el archivo y la regla incumplida

#### Escenario: [BX45] Aritmética sobre el importe en la aplicación

- **DADO** la violación deliberada de un archivo de `apps/admin-web` que suma o multiplica `money.amount`
- **CUANDO** se ejecuta el análisis estático del monorepo
- **ENTONCES** falla nombrando el archivo y la regla incumplida

#### Escenario: [BX46] Código real de la aplicación sin infracciones y sin conjunto vacío

- **DADO** el código real de `apps/admin-web`, excluidas las violaciones deliberadas
- **CUANDO** se ejecuta el análisis estático del monorepo
- **ENTONCES** las cuatro reglas pasan habiendo evaluado archivos reales de componentes y al menos una clave real del catálogo
  `admin`

#### Escenario: [BX47] Las reglas exclusivas de `packages/ui` no se aplican a la aplicación y siguen aplicándose a `ui`

- **DADO** la violación deliberada de un archivo de `packages/ui` que invoca `fetch`, y un archivo de
  `apps/admin-web/src/shared/api` que invoca `fetch`
- **CUANDO** se ejecuta el análisis estático del monorepo
- **ENTONCES** el primero falla por la regla de red de `ui` y el segundo no falla por esa regla

#### Escenario: [BX48] La separación no debilita a `packages/ui`

- **DADO** las violaciones deliberadas existentes de `packages/ui` de 13a (literal de texto, formato manual, clave inexistente
  o no resoluble, red, aritmética de importes y capas)
- **CUANDO** se ejecuta el análisis estático del monorepo tras separar la configuración
- **ENTONCES** cada una sigue fallando nombrando la misma regla

### Requisito: La red solo se abre desde `src/shared/api` y `e2e` en `apps/admin-web`

El sistema DEBE romper el análisis estático cuando el código de `apps/admin-web` usa `fetch`, `XMLHttpRequest`, `WebSocket`,
`EventSource`, `Worker`, `SharedWorker` o `navigator.sendBeacon`, directamente o mediante `window`, `globalThis` o `self`, fuera de `src/shared/api/**` y de `e2e/**` (los recorridos de
navegador usan la red por diseño). La excepción DEBE limitarse a esas dos rutas exactas: una carpeta de nombre parecido NO
DEBE heredarla. Cada interfaz DEBE tener su violación deliberada, y la regla NO DEBE aprobar sobre un conjunto vacío.

#### Escenario: [BX49] `fetch` fuera de `shared/api`

- **DADO** la violación deliberada de un archivo de `apps/admin-web/src/features` que invoca `fetch`
- **CUANDO** se ejecuta el análisis estático del monorepo
- **ENTONCES** falla nombrando el archivo y la regla de red de la aplicación

#### Escenario: [BX50] `XMLHttpRequest` fuera de `shared/api`

- **DADO** la violación deliberada de un archivo de la aplicación que crea un `XMLHttpRequest`
- **CUANDO** se ejecuta el análisis estático del monorepo
- **ENTONCES** falla nombrando el archivo y la regla de red de la aplicación

#### Escenario: [BX51] `WebSocket` fuera de `shared/api`

- **DADO** la violación deliberada de un archivo de la aplicación que crea un `WebSocket`
- **CUANDO** se ejecuta el análisis estático del monorepo
- **ENTONCES** falla nombrando el archivo y la regla de red de la aplicación

#### Escenario: [BX52] `EventSource` fuera de `shared/api`

- **DADO** la violación deliberada de un archivo de la aplicación que crea un `EventSource`
- **CUANDO** se ejecuta el análisis estático del monorepo
- **ENTONCES** falla nombrando el archivo y la regla de red de la aplicación

#### Escenario: [BX53] `fetch` por `window` o `globalThis`

- **DADO** las violaciones deliberadas de archivos de la aplicación que invocan `window.fetch` y `globalThis.fetch`
- **CUANDO** se ejecuta el análisis estático del monorepo
- **ENTONCES** las dos fallan nombrando la regla de red de la aplicación

#### Escenario: [BX110] `Worker`, `SharedWorker` y `navigator.sendBeacon` fuera de `shared/api`

- **DADO** las violaciones deliberadas de archivos de la aplicación que crean un `Worker` y un `SharedWorker` e invocan `navigator.sendBeacon`
- **CUANDO** se ejecuta el análisis estático del monorepo
- **ENTONCES** las tres fallan nombrando la regla de red de la aplicación

#### Escenario: [BX54] `fetch` dentro de `src/shared/api`

- **DADO** el código real del cliente de API en `apps/admin-web/src/shared/api`
- **CUANDO** se ejecuta el análisis estático del monorepo
- **ENTONCES** no falla por la regla de red

#### Escenario: [BX55] La red en `e2e`

- **DADO** un archivo de `apps/admin-web/e2e` que usa `fetch` o el cliente de peticiones de Playwright
- **CUANDO** se ejecuta el análisis estático del monorepo
- **ENTONCES** no falla por la regla de red

#### Escenario: [BX56] Una carpeta de nombre parecido no hereda la excepción

- **DADO** la violación deliberada de un archivo en `apps/admin-web/src/shared/api-helpers` que invoca `fetch`
- **CUANDO** se ejecuta el análisis estático del monorepo
- **ENTONCES** falla nombrando la regla de red de la aplicación

#### Escenario: [BX57] La regla de red evalúa archivos reales

- **DADO** el código real de `apps/admin-web`
- **CUANDO** se ejecuta el análisis estático del monorepo
- **ENTONCES** la regla de red pasa habiendo evaluado archivos reales dentro y fuera de `src/shared/api`

### Requisito: La presentación de `apps/admin-web` no importa `@tanstack/react-query` ni `shared/api`

El sistema DEBE romper la verificación de dependencias cuando un archivo de presentación de `apps/admin-web` (las carpetas
`components` de cada funcionalidad) importa `@tanstack/react-query` o cualquier módulo de `src/shared/api`, incluso solo como
tipo, por ruta relativa o por alias. Los contenedores, que no están en esas carpetas, SÍ pueden importarlos
(`CLAUDE.md`, sección «Frontend»). La regla DEBE tener una violación deliberada por cada importación prohibida y NO DEBE
aprobar sobre un conjunto vacío.

#### Escenario: [BX58] La presentación importa TanStack Query

- **DADO** la violación deliberada de un archivo de presentación que importa `@tanstack/react-query`
- **CUANDO** se ejecuta la verificación de dependencias del monorepo
- **ENTONCES** falla y nombra la regla violada

#### Escenario: [BX59] La presentación importa `shared/api`

- **DADO** las violaciones deliberadas de un archivo de presentación que importa `shared/api` por ruta relativa y de otro que
  lo importa por alias
- **CUANDO** se ejecuta la verificación de dependencias del monorepo
- **ENTONCES** las dos fallan y nombran la regla violada

#### Escenario: [BX60] La presentación importa solo un tipo de `shared/api`

- **DADO** la violación deliberada de un archivo de presentación con `import type` desde `shared/api`
- **CUANDO** se ejecuta la verificación de dependencias del monorepo
- **ENTONCES** falla y nombra la regla violada

#### Escenario: [BX61] Un contenedor sí puede importarlos

- **DADO** el código real de un contenedor de `apps/admin-web` que importa `@tanstack/react-query` y `shared/api`
- **CUANDO** se ejecuta la verificación de dependencias del monorepo
- **ENTONCES** esas importaciones no violan ninguna regla

#### Escenario: [BX62] La regla evalúa presentación real

- **DADO** una regla cuyo selector de archivos de presentación coincide con cero archivos reales de la aplicación
- **CUANDO** se ejecuta la verificación de dependencias del monorepo
- **ENTONCES** la verificación falla por conjunto vacío en lugar de aprobar en silencio

### Requisito: El escáner de supresiones cubre también `apps/`

El sistema DEBE ejecutar el escáner de supresiones (`eslint-disable`, `@ts-nocheck`, `@ts-ignore`, `@ts-expect-error` y
equivalentes) sobre `apps/` además de `packages/ui`, y DEBE romper la verificación ante una supresión sin la cita de un ADR
(requisito «Ninguna regla se desactiva sin un ADR»). El escáner NO DEBE aprobar sobre un conjunto vacío de archivos de `apps/`.

#### Escenario: [BX63] Supresión sin ADR en la aplicación

- **DADO** la violación deliberada de un archivo de `apps/admin-web` con `eslint-disable` sin cita de ADR
- **CUANDO** se ejecuta el escáner de supresiones
- **ENTONCES** falla nombrando el archivo y la directiva

#### Escenario: [BX64] Supresión con la cita de un ADR en la aplicación

- **DADO** un archivo de `apps/admin-web` con una directiva y la cita del ADR que la autoriza
- **CUANDO** se ejecuta el escáner de supresiones
- **ENTONCES** no la cuenta como violación

#### Escenario: [BX65] El escáner evalúa archivos reales de `apps/`

- **DADO** el código real de `apps/`
- **CUANDO** se ejecuta el escáner de supresiones
- **ENTONCES** termina en verde habiendo evaluado archivos reales de `apps/admin-web`, no un conjunto vacío

### Requisito: La política de bloqueo de accesibilidad tiene una única fuente en `@confia/config`

El sistema DEBE ofrecer la política de bloqueo de axe en `@confia/config`, en la exportación `./a11y`, como única fuente
consumida por la puerta de historias de `packages/ui`, por el panel de accesibilidad de Storybook y por la puerta sobre rutas
renderizadas. La política DEBE conservar su comportamiento de 13a: las etiquetas `wcag2a`, `wcag2aa`, `wcag21a`, `wcag21aa`
y `wcag22aa`; bloquean los impactos `critical` y `serious`; solo se reportan `moderate` y `minor`; un impacto desconocido,
nulo o ausente bloquea; un resultado `incomplete` de `color-contrast` bloquea y el de cualquier otra regla se reporta, nunca
se ignora. La política DEBE depender solo del tipo de axe-core y DEBE llevar sus pruebas al mismo paquete. Moverla NO DEBE
cambiar el punto de entrada único de `@confia/ui` (exportarla desde `ui` lo rompería).

#### Escenario: [BX66] Las etiquetas de la política

- **DADO** la política importada de `@confia/config/a11y`
- **CUANDO** se leen sus etiquetas
- **ENTONCES** son exactamente `wcag2a`, `wcag2aa`, `wcag21a`, `wcag21aa` y `wcag22aa`

#### Escenario: [BX67] Bloquean los impactos crítico y serio; moderado y menor solo se reportan

- **DADO** violaciones de impacto `critical`, `serious`, `moderate` y `minor`
- **CUANDO** la política las reparte
- **ENTONCES** las dos primeras quedan como bloqueantes y las dos últimas como reportadas

#### Escenario: [BX68] Un impacto desconocido bloquea

- **DADO** violaciones con impacto ausente, nulo y con una cadena que la política no reconoce
- **CUANDO** la política las reparte
- **ENTONCES** las tres quedan como bloqueantes

#### Escenario: [BX69] Un resultado incompleto de contraste bloquea y el de otra regla se reporta

- **DADO** un resultado `incomplete` de la regla `color-contrast` y otro de otra regla
- **CUANDO** la política los reparte
- **ENTONCES** el de `color-contrast` queda como bloqueante, sea cual sea su impacto, y el otro como reportado

#### Escenario: [BX70] La política solo depende del tipo de axe-core

- **DADO** el archivo de la política en `@confia/config`
- **CUANDO** se inspeccionan sus importaciones
- **ENTONCES** la única importación es de tipo desde `axe-core`

#### Escenario: [BX71] La política salió de `packages/ui`

- **DADO** el árbol de `packages/ui`
- **CUANDO** se busca una copia de la política de bloqueo
- **ENTONCES** no existe ninguna y `packages/ui` la importa de `@confia/config/a11y`

#### Escenario: [BX72] El punto de entrada de `@confia/ui` no cambia por el traslado

- **DADO** el punto de entrada público de `packages/ui` tras mover la política
- **CUANDO** se enumeran sus exportaciones
- **ENTONCES** no contiene la política ni ningún nombre de ella, y el conjunto es el del requisito de `design-system`

#### Escenario: [BX73] La puerta sobre rutas usa la misma política

- **DADO** la puerta sobre rutas renderizadas y la puerta de historias
- **CUANDO** se inspeccionan sus importaciones de la política
- **ENTONCES** las dos importan el mismo módulo de `@confia/config/a11y`

### Requisito: La puerta de accesibilidad se ejecuta sobre rutas renderizadas de `apps/admin-web`, en los dos temas, y falla con cero rutas

El sistema DEBE ejecutar axe con Playwright y `@axe-core/playwright` sobre una matriz de ruta, estado y tema de
`apps/admin-web` renderizada con `vite preview` (`docs/ui-ux/03` §9; ADR-0028, «axe sobre rutas renderizadas»; ADR-0018). Cada
combinación DEBE evaluarse en tema claro y en tema oscuro, emulando la preferencia del sistema, y DEBE analizarse solo después
de que el contenido esperado de su estado sea visible; analizar durante un esqueleto de carga produce un falso verde. La matriz mínima de esta parte cubre cada pantalla que existe al terminarla, en estos estados, 6 estados por 2 temas
(las pantallas de acceso entran con `admin-web-staff-sign-in`):

| Pantalla | Estados mínimos |
|---|---|
| Arranque | restaurando; error de red con reintento |
| Página autenticada | cargando; confirmación; error de lectura |
| Ruta no encontrada | única |

La puerta DEBE fallar ante una violación crítica o seria según la política de `@confia/config`, ante cero rutas evaluadas y
ante una ruta deliberadamente inaccesible que solo existe en pruebas. El informe de violaciones DEBE conservarse como artefacto
de la ejecución y la misma puerta DEBE poder correr en local con el mismo veredicto. Una violación moderada DEBE reportarse sin
fallar.

#### Escenario: [BX74] Cada combinación mínima se evalúa en ambos temas

- **DADO** la matriz de la puerta
- **CUANDO** se enumeran sus combinaciones
- **ENTONCES** contiene, para cada pantalla y estado mínimos de la tabla, una evaluación en tema claro y otra en tema oscuro

#### Escenario: [BX75] Una violación seria rompe la puerta nombrando ruta, estado, tema y regla

- **DADO** una violación seria en una combinación de la matriz
- **CUANDO** corre la puerta
- **ENTONCES** falla nombrando la ruta, el estado, el tema, la regla de axe y su impacto

#### Escenario: [BX76] Una ruta deliberadamente inaccesible falla

- **DADO** la ruta de prueba con un control sin nombre accesible
- **CUANDO** corre la puerta sobre ella
- **ENTONCES** falla, lo que demuestra que la puerta evalúa de verdad

#### Escenario: [BX77] Cero rutas evaluadas falla

- **DADO** una configuración de la puerta cuya matriz queda vacía
- **CUANDO** corre
- **ENTONCES** falla en lugar de aprobar

#### Escenario: [BX78] No se analiza durante la carga

- **DADO** una combinación cuyo contenido esperado nunca aparece y que permanece en su estado de carga
- **CUANDO** corre la puerta
- **ENTONCES** falla por tiempo de espera del contenido esperado y no analiza el estado de carga

#### Escenario: [BX79] Una violación solo en el tema oscuro falla

- **DADO** la ruta de prueba cuyo contraste es suficiente en el tema claro e insuficiente en el oscuro
- **CUANDO** corre la puerta
- **ENTONCES** falla, porque cada combinación se evalúa en ambos temas

#### Escenario: [BX80] Una violación moderada se reporta sin fallar

- **DADO** una combinación cuya única violación es de impacto moderado
- **CUANDO** corre la puerta
- **ENTONCES** no falla por esa causa y la advertencia queda en el informe

#### Escenario: [BX81] Rutas reales sin violaciones bloqueantes

- **DADO** las combinaciones reales de la matriz, sin la ruta de prueba
- **CUANDO** corre la puerta
- **ENTONCES** termina en verde con el informe conservado como artefacto

#### Escenario: [BX82] Ejecución local con el mismo veredicto

- **DADO** una máquina de desarrollo con las dependencias y Chromium instalados
- **CUANDO** se ejecuta localmente la puerta
- **ENTONCES** produce el mismo veredicto que la integración continua sobre el mismo código

### Requisito: El trabajo `admin-web e2e and accessibility` entra a la integración continua solo de forma aditiva

El sistema DEBE agregar a `.github/workflows/ci.yml` un trabajo nuevo `admin-web e2e and accessibility` que instale Chromium,
sirva la aplicación con `vite preview`, ejecute los recorridos y la puerta sobre rutas contra la API simulada, con las acciones
fijadas por SHA como los trabajos existentes, y suba el informe como artefacto aun si falla. El diff de `ci.yml` NO DEBE
modificar, reordenar ni eliminar trabajos o pasos existentes. El comando del trabajo `frontend`, `pnpm turbo run lint typecheck
test build --filter=!@confia/api`, DEBE seguir idéntico, y `admin-web` DEBE entrar a él solo con sus propias tareas `build`,
`lint`, `typecheck` y `test`. El trabajo nuevo NO DEBE requerir Docker, JDK ni API real. `turbo.json` DEBE declarar la tarea
`test:e2e` sin caché y la tarea `dev`. La prueba `ci-workflow.test.ts` DEBE afirmar la existencia y las propiedades del trabajo
nuevo. El trabajo `frontend verify` NO DEBE superar su límite de 10 minutos con `admin-web` incluido, y el tiempo medido se
registra en `design.md`, D16, al ejecutar el PR 2.

#### Escenario: [BX83] El diff de `ci.yml` solo agrega

- **DADO** el diff de `.github/workflows/ci.yml` de este cambio frente a `main`
- **CUANDO** se inspecciona
- **ENTONCES** no contiene líneas eliminadas ni modificadas de trabajos o pasos existentes, solo líneas agregadas

#### Escenario: [BX84] El comando del trabajo `frontend` no cambia

- **DADO** el trabajo `frontend` tras el cambio
- **CUANDO** se lee su comando de verificación
- **ENTONCES** es, exactamente, `pnpm turbo run lint typecheck test build --filter=!@confia/api`
- **Y** la prueba [BX19] de `ci-workflow.test.ts` sigue en verde sin modificarse

#### Escenario: [BX85] El trabajo nuevo existe con sus propiedades

- **DADO** `ci.yml` y la prueba `ci-workflow.test.ts`
- **CUANDO** se leen
- **ENTONCES** existe el trabajo `admin-web e2e and accessibility`, con Chromium, `vite preview`, un límite de tiempo de 20 minutos y
  acciones fijadas por SHA, y la prueba falla si el trabajo desaparece o pierde una de esas propiedades

#### Escenario: [BX86] El informe se conserva aunque el trabajo falle

- **DADO** una ejecución del trabajo nuevo con una violación de accesibilidad
- **CUANDO** termina en rojo
- **ENTONCES** el informe de violaciones quedó subido como artefacto `admin-web-e2e-report` de la ejecución y el resultado es visible en el remoto

#### Escenario: [BX87] El trabajo nuevo no necesita Docker, JDK ni API real

- **DADO** el trabajo nuevo
- **CUANDO** se inspeccionan sus pasos
- **ENTONCES** no instala ni arranca Docker, ni JDK, ni el backend

#### Escenario: [BX88] Tareas de Turborepo nuevas

- **DADO** `turbo.json`
- **CUANDO** se leen sus tareas
- **ENTONCES** `test:e2e` está declarada sin caché y `dev` está declarada, y la tarea `test:a11y` existente no cambió

#### Escenario: [BX89] `admin-web` cabe en el límite del trabajo `frontend`

- **DADO** una ejecución del trabajo `frontend` con `admin-web` y `packages/ui` incluidos
- **CUANDO** termina
- **ENTONCES** su duración es menor que el límite de 10 minutos
- **Y** el tiempo se registra en `design.md`, D16, con nota fechada, al ejecutar el PR 2

#### Escenario: [BX90] El trabajo de historias de `packages/ui` sigue intacto

- **DADO** el trabajo `design-system-accessibility` tras el cambio
- **CUANDO** corre
- **ENTONCES** conserva sus pasos y su límite de tiempo y termina en verde

#### Escenario: [BX91] Instalación reproducible con las dependencias nuevas

- **DADO** el archivo de bloqueo actualizado junto con las dependencias nuevas
- **CUANDO** la integración continua ejecuta `pnpm install --frozen-lockfile`
- **ENTONCES** la instalación termina sin modificar el archivo de bloqueo

### Requisito: Las dependencias nuevas del frontend pasan por el catálogo, la regla de dos semanas, la auditoría y el resultado de compilación

El sistema DEBE declarar las dependencias nuevas (`@tanstack/*`, `msw`, `@playwright/test`, `@axe-core/playwright` y, cuando lleguen
en el PR 14 de la parte 2, `qrcode.react` y `jsqr`) en el catálogo de `pnpm-workspace.yaml`, sujetas a la regla de antigüedad de dos
semanas, que este cambio hace cumplir agregando `minimumReleaseAge: 20160`. Las bibliotecas de prueba (`msw`, `@playwright/test`,
`@axe-core/playwright` y `jsqr`) DEBEN ser dependencias de desarrollo y NO DEBEN llegar al resultado de compilación de
producción. El escaneo de dependencias de pnpm y Trivy DEBEN seguir rompiendo la construcción ante severidad alta o crítica.
Una dependencia bloqueada DEBE reemplazarse en el diseño, no excepcionarse.

#### Escenario: [BX92] Las dependencias nuevas salen del catálogo

- **DADO** los `package.json` de `apps/admin-web` y `packages/config`
- **CUANDO** se leen las versiones de las dependencias nuevas
- **ENTONCES** todas son del catálogo (`catalog:`) y ninguna lleva una versión propia

#### Escenario: [BX93] La regla de antigüedad no se debilita

- **DADO** la configuración `minimumReleaseAge` de `pnpm-workspace.yaml`
- **CUANDO** se lee
- **ENTONCES** el valor existe y no es menor de 20 160 minutos
- **Y** no hay excepciones nuevas para las dependencias de este cambio

#### Escenario: [BX94] Las bibliotecas de prueba son de desarrollo

- **DADO** `apps/admin-web/package.json`
- **CUANDO** se leen sus secciones de dependencias
- **ENTONCES** `msw`, `@playwright/test`, `@axe-core/playwright` y, cuando llegue en el PR 14, `jsqr` están solo en
  `devDependencies`

#### Escenario: [BX95] Ninguna biblioteca de prueba llega a producción

- **DADO** el directorio de salida de la compilación de producción
- **CUANDO** se buscan referencias a `msw`, a `@playwright/test` y a `@axe-core/playwright`
- **ENTONCES** no hay ninguna

#### Escenario: [BX96] Vulnerabilidad alta en una dependencia nueva

- **DADO** una dependencia nueva con una vulnerabilidad de severidad alta conocida
- **CUANDO** corre el escaneo de dependencias
- **ENTONCES** la construcción falla y nombra la dependencia

### Requisito: Cobertura mínima de `apps/admin-web`

El sistema DEBE romper la verificación de pruebas del monorepo si la cobertura de `apps/admin-web` cae por debajo de ochenta por
ciento (`CLAUDE.md`, sección «Pruebas»), contando en el denominador todo archivo de código de producción de la aplicación,
con o sin pruebas.

#### Escenario: [BX97] Cobertura por debajo del umbral

- **DADO** `apps/admin-web` con cobertura inferior a 80 %
- **CUANDO** se ejecuta la tarea de pruebas con cobertura
- **ENTONCES** falla señalando `apps/admin-web` y el porcentaje medido

#### Escenario: [BX98] Cobertura en el umbral o por encima

- **DADO** `apps/admin-web` con cobertura igual o superior a 80 %
- **CUANDO** se ejecuta la tarea de pruebas con cobertura
- **ENTONCES** no falla por esta causa

#### Escenario: [BX99] Un archivo sin pruebas baja la cobertura

- **DADO** un archivo nuevo de `apps/admin-web` sin pruebas
- **CUANDO** se mide la cobertura
- **ENTONCES** el código de ese archivo cuenta en el denominador y no queda excluido de la medición

### Requisito: Un ADR nuevo y notas fechadas dejan escrita la puerta sobre rutas, y el criterio de salida 8 se marca solo cuando la puerta pasa

El sistema DEBE registrar la decisión de «axe sobre rutas renderizadas» en ADR-0028, en
`docs/adr/`, indexado en `docs/adr/README.md` y en estado Propuesto. ADR-0026 NO DEBE reescribirse: DEBE recibir solo una nota
fechada sobre el traslado de la política. `CLAUDE.md` y `docs/01-arquitectura.md` §3 DEBEN sumar la puerta
sobre rutas a la fila de pruebas del frontend, y `packages/config/package.json` DEBE actualizar su descripción y declarar la
exportación `./a11y`.
El criterio de salida 8 NO DEBE marcarse en este cambio: se marca en `admin-web-staff-sign-in`, con su nota de `docs/09-roadmap-y-fases.md`.

#### Escenario: [BX100] El ADR nuevo existe, está indexado y es Propuesto

- **DADO** `docs/adr/`
- **CUANDO** se busca el ADR de «axe sobre rutas renderizadas»
- **ENTONCES** existe con el número 0028, aparece en `docs/adr/README.md` y su estado es Propuesto

#### Escenario: [BX101] ADR-0026 conserva su texto

- **DADO** el diff de ADR-0026 frente a `main`
- **CUANDO** se inspecciona
- **ENTONCES** no contiene líneas eliminadas ni modificadas, solo una nota fechada agregada sobre el traslado de la política

#### Escenario: [BX106] `CLAUDE.md` y `docs/01` mencionan la puerta sobre rutas

- **DADO** la fila de pruebas del frontend de `CLAUDE.md` y de `docs/01-arquitectura.md` §3
- **CUANDO** se leen
- **ENTONCES** las dos mencionan la puerta de axe sobre rutas renderizadas con Playwright

#### Escenario: [BX107] `@confia/config` declara la exportación `./a11y`

- **DADO** `packages/config/package.json`
- **CUANDO** se lee
- **ENTONCES** su descripción menciona la política de accesibilidad y su mapa de exportaciones incluye `./a11y`
