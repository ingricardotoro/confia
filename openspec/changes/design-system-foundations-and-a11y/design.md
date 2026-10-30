# Diseño: fundamentos del sistema de diseño y verificación de accesibilidad

- **Cambio:** `design-system-foundations-and-a11y` (F0, cambio 13a)
- **Fase:** diseñar
- **Fecha:** 2026-10-28
- **Estado:** pendiente de aprobación del propietario del producto
- **Entradas:** `proposal.md` (aprobada por el propietario el 2026-10-28), exploración
  `sdd/design-system-foundations-and-a11y/explore`. Las especificaciones `specs/design-system/` y
  `specs/build-integrity/` se escriben en paralelo; este documento usa los nombres de capacidad de la
  propuesta y la fase de tareas reconcilia la trazabilidad contra los escenarios finales.

## 1. Enfoque técnico

`packages/ui` nace como un paquete interno consumido **como código fuente** (igual que
`packages/contracts`: `exports` apunta a archivos `.ts`), sin paso de compilación propio. Las
aplicaciones del cambio 13b lo compilarán con su Vite. El paquete concentra cuatro cosas que hoy no
existen: los tokens con su tema, los textos del catálogo `es-HN`, los componentes P0 de la propuesta
y las puertas que impiden degradarlos.

Las puertas se reparten según lo que exige cada una, para que la máquina de un desarrollador y el
trabajo `frontend verify` existente sigan funcionando sin navegador:

```
                    ┌──────────── sin navegador (trabajo "frontend verify", sin cambios) ─────────┐
 tokens/*.css ──►  compilación de Tailwind en prueba ─► sin utilidades para primitivos
                   análisis del CSS ─────────────────► paridad oscuro/claro y contraste WCAG
 src/**/*.tsx ──►  ESLint ──────────────────────────► sin literales en JSX, sin toFixed,
                                                       sin primitivos, sin fetch
                   tsc ─────────────────────────────► clave de catálogo inexistente = error de tipos
                   Vitest (jsdom) ──────────────────► pruebas de componente + cobertura 80 %
                   dependency-cruiser ──────────────► reglas de capas de packages/ui
                   storybook build ─────────────────► el catálogo compila
                    └────────────────────────────────────────────────────────────────────────────┘
                    ┌──────────── con Chromium (trabajo nuevo "design system accessibility") ─────┐
 *.stories.tsx ─►  Vitest en modo navegador + axe-core ─► 0 violaciones críticas o serias,
                   (historias portables × tema claro/oscuro)  por historia y por tema
                    └────────────────────────────────────────────────────────────────────────────┘
```

## 2. Evidencia obtenida en esta fase

Leída en el árbol de la rama `change/design-system-foundations-and-a11y`, no supuesta:

- `pnpm-workspace.yaml`: catálogo con TypeScript 6.0.3, Vitest 5.0.2, ESLint 10.11.0,
  typescript-eslint 8.71.0, dependency-cruiser 18.4.0; `strictDepBuilds: true` y `allowBuilds` con
  `esbuild: false`. pnpm 12.8.1 (`package.json`).
- `packages/config/tsconfig.base.json`: `lib: ["ES2023"]`, sin `DOM` ni `jsx`; `module` y
  `moduleResolution` en `NodeNext`; `exactOptionalPropertyTypes` activo. `packages/ui` necesita
  sobrescribir `lib`, `jsx` y la resolución.
- `packages/config/vitest.js`: `include: ["src/**/*.test.ts"]`; no recoge `.test.tsx`.
- `packages/config/eslint.js`: `contractImportRestrictions` ya define `no-restricted-imports`. En
  configuración plana, una segunda entrada de la misma regla **reemplaza** las opciones de la
  primera; las restricciones de `packages/ui` deben componerse con las existentes, no añadirse.
- `.dependency-cruiser.cjs`: `tsPreCompilationDeps: true` (necesario para distinguir importaciones
  de solo tipos) y `extensions` sin `.tsx`: hay que añadirla o los componentes no se analizan.
- `tooling/src/dependency-rules.test.ts`: patrón establecido de fixture permanente por regla más
  árbol real con `totalCruised > 0`. `tooling/src/eslint-restrictions.test.ts`: patrón de
  `ESLint.lintText` sobre código en línea.
- `.github/workflows/ci.yml`: trabajos `backend`, `frontend` y `security`; acciones fijadas por SHA;
  `pnpm audit --audit-level high` bloquea sobre el árbol completo.
- `docs/ui-ux/01-tokens-de-diseno.md` §14: el bloque de referencia define los primitivos como
  `--color-neutral-*` y `--color-primary-*` **dentro de `@theme`**. En Tailwind v4 todo `--color-*`
  de `@theme` genera utilidades (`bg-neutral-500`, `bg-primary-600`), lo que haría utilizables los
  primitivos y contradice la regla dura del §1 del mismo documento. Además `--color-primary` y
  `--color-primary-600` comparten espacio de nombres. La decisión 3 lo resuelve.
- `docs/ui-ux/02` §5.1 describe `value: { amountMinor, currency }`, contrario a ADR-0011
  §Presentación (`amount` decimal en cadena, ya redondeado). La propuesta ya decide corregirlo.
- ADR-0011: existe **un solo catálogo** (`es-HN`); los mensajes usan **formato ICU**.
- ADR-0018 §1.5 y §4: ninguna exclusión de `dependency-cruiser` ni de ESLint sin ADR propio, y toda
  supresión lleva la cita del ADR junto al código.
- `docs/15-flujo-de-trabajo-git.md` §3: ochocientas líneas de cambio efectivo, y las pruebas y la
  documentación **nunca** se separan de su código.

**Limitación declarada.** La documentación actual de Storybook, Tailwind v4, shadcn/ui, i18next y
axe no pudo consultarse con context7 en esta fase (la herramienta no estuvo disponible en la
sesión). Por eso este diseño fija **familias de versión y criterios de compatibilidad**, no números
de parche, y deja la fijación exacta a la sonda S1, con el mismo tratamiento que tuvo el cambio 3
(«la versión exacta se fija en la fase de aplicación y queda registrada»). Ninguna afirmación de
comportamiento de esas bibliotecas se da por cierta: cada una tiene su sonda en la sección 7.

## 3. Decisiones de arquitectura

### Decisión 1: estructura de `packages/ui` por nivel atómico, un directorio por componente

```
packages/ui/
├── package.json            # @confia/ui, privado, "type": "module"
├── tsconfig.json           # extiende la base; DOM, jsx react-jsx, module Preserve + Bundler
├── vitest.config.ts        # proyectos "unit" (jsdom) y "a11y" (navegador)
├── eslint.config.js        # confiaEslint({ ui: true })
├── .storybook/
│   ├── main.ts             # @storybook/react-vite, stories: src/**/*.stories.tsx
│   └── preview.tsx         # tokens, i18n, decorador de tema, parámetros de axe
└── src/
    ├── index.ts            # único punto de entrada público
    ├── tokens/             # index.css, primitives.css, semantic.css, themes.css,
    │                       # components.css, base.css y sus pruebas
    ├── i18n/               # locales/es-HN/ui.json, resources.ts, create-ui-i18n.ts,
    │                       # use-ui-text.ts
    ├── format/             # money.ts: única utilidad de formato de importes
    ├── a11y/               # policy.ts, story-coverage.ts, la puerta y sus fixtures
    ├── lib/                # cn() (clsx + tailwind-merge)
    ├── hooks/              # utilidades de interfaz sin dominio (vacío hasta que se necesite)
    ├── atoms/<nombre>/     # Button, IconButton, Input, Textarea, Label, Checkbox,
    │                       # RadioGroup, Badge, Spinner, Separator, Skeleton
    ├── molecules/<nombre>/ # FormField (con FormError), EmptyState, ErrorState
    └── domain/<nombre>/    # MoneyAmount, StatusBadge
```

Cada directorio de componente contiene `<Nombre>.tsx`, `<Nombre>.stories.tsx`, `<Nombre>.test.tsx`
e `index.ts`. `layouts/` (`02` §3) no se crea: este cambio no tiene plantillas y un directorio vacío
no aporta nada.

`exports` publica exactamente tres rutas: `"."` → `src/index.ts`, `"./styles.css"` →
`src/tokens/index.css` y `"./i18n"` → `src/i18n/resources.ts`. Cualquier otra ruta no se resuelve.

**Alternativas descartadas.** Un archivo plano por nivel (`atoms/Button.tsx`): mezcla historias y
pruebas de componentes distintos y complica la comprobación de historia por componente. Compilar
`packages/ui` a `dist/`: añade un paso, mapas de fuente y un segundo `tsconfig` sin beneficio
mientras el único consumidor sea Vite dentro del mismo monorepo.

### Decisión 2: los tokens son CSS de Tailwind v4 dentro de `packages/ui`, no un preset en `packages/config`

En Tailwind v4 la configuración del tema **es** CSS (`@theme`). Ponerla en `packages/config`
separaría los tokens del único paquete que los define y contradiría `02` §3 y `01` §14, que los
ubican en `packages/ui`. Por eso la «parte de Tailwind de `packages/config`» que mencionan la
propuesta y el cambio 3 se cierra así: `packages/config` **no** recibe un preset; recibe las reglas
de ESLint de `packages/ui` y la base de Vitest ampliada a `.tsx`. Se deja dicho en el registro de
aplicación para que nadie lo busque.

`src/tokens/index.css` es el **único** punto de entrada de estilos, el que importarán las
aplicaciones como `@confia/ui/styles.css`:

1. `@import "tailwindcss";` (las aplicaciones no lo importan por su cuenta).
2. `@theme { --*: initial; }`: se borra **todo** el tema predeterminado de Tailwind. Ninguna utilidad
   de su paleta (`bg-red-500`) ni de sus escalas sobrevive; solo existe lo que define CONFIA.
3. `primitives.css`, `semantic.css`, `themes.css`, `components.css` y `base.css`, en ese orden.
4. `@source "../";`: la ruta es relativa a la hoja, así que una aplicación que importe el CSS hace
   que Tailwind analice las clases de `packages/ui` sin configurar nada.
5. `@custom-variant dark`, definido para seguir los dos mecanismos de tema (§6 de `01`). Los
   componentes **no** usan `dark:`: los tokens semánticos cambian solos de valor.

### Decisión 3: tres niveles de token; los primitivos en un espacio de nombres sin utilidades

| Nivel | Dónde | Nombre | ¿Genera utilidad? | ¿Lo usa un componente? |
|---|---|---|---|---|
| Primitivo | `primitives.css`, dentro de `@theme static` | `--palette-neutral-500`, `--palette-primary-600` | No: `palette` no es un espacio de nombres de Tailwind | Nunca |
| Semántico | `semantic.css`, dentro de `@theme static` | `--color-primary`, `--color-status-paid-fg`, `--text-sm`, `--radius-md`, `--spacing` | Sí (`bg-primary`, `text-status-paid-fg`) | Sí |
| Componente | `components.css`, en `:root` | `--button-primary-bg`, `--status-badge-paid-bg` | No; se consume con `bg-(--button-primary-bg)` | Sí, solo el suyo |

- El cambio frente a `01` §14 es solo de **nombre** del primitivo (`--color-neutral-500` pasa a
  `--palette-neutral-500`). Con eso la regla dura del §1 («un componente nunca consume un primitivo»)
  deja de depender de disciplina: la utilidad ni siquiera existe. Los valores y los semánticos no
  cambian. `01` §14 se actualiza con una nota fechada en este cambio, porque el documento quedaría
  describiendo un bloque que ya no es el real.
- Los semánticos de estado financiero y de retroalimentación conservan los hexadecimales literales
  de `01` §4 y §14, que es la fuente de verdad; no se inventan primitivos nuevos para ellos.
- `@theme static` garantiza que todas las variables se emitan aunque ninguna utilidad las use, para
  que las variables de componente y los bloques oscuros encuentren siempre su valor base.
- **El tema se fija solo en `<html>`.** Las variables de componente se declaran en `:root` y
  referencian semánticos; una variable con `var()` se resuelve en el elemento que la declara. Un
  `data-theme` en un subárbol no cambiaría los tokens de componente, así que no se admite: lo dice
  `01` §6 («en el elemento raíz») y Storybook renderiza cada tema por separado, nunca lado a lado.

**Pruebas (sin navegador):**

- `tokens.build.test.ts`: compila `index.css` con la API `compile()` de Tailwind y comprueba que
  `bg-primary` produce una regla, y que **la violación deliberada** `bg-palette-primary-600` y una
  utilidad de la paleta predeterminada (`bg-red-500`) no producen ninguna.
- Una regla de ESLint (decisión 6) rechaza cualquier cadena que contenga `--palette-` fuera de
  `src/tokens/`, con su violación deliberada; una prueba recorre los `.css` de `src/` fuera de
  `primitives.css` y `semantic.css` y falla ante `--palette-`.

**Alternativas descartadas.** Mantener `--color-neutral-*` en `@theme` y prohibir sus utilidades con
una regla de análisis: la utilidad seguiría existiendo para las aplicaciones de 13b, y una lista de
clases prohibidas es una heurística que envejece. Primitivos fuera de `@theme`, en `:root`: funciona,
pero contradice la letra de la propuesta («primitivos solo dentro de `@theme`»); queda como respaldo
si la sonda S2 demuestra que Tailwind no emite variables de un espacio de nombres propio.

### Decisión 4: modo oscuro por preferencia del sistema y por `data-theme`, con paridad verificada

`themes.css` contiene los dos bloques de `01` §14 tal cual: `@media (prefers-color-scheme: dark)`
sobre `:root:not([data-theme='light'])`, y `:root[data-theme='dark']`. CSS no permite unir una
consulta de medio y un selector en un solo bloque, así que los valores se duplican; la duplicación se
acepta y se vigila:

- `tokens.parity.test.ts` analiza el CSS y falla si los dos bloques oscuros no declaran **exactamente
  las mismas variables con los mismos valores**, o si alguna variable del bloque oscuro **no tiene
  valor base** en `semantic.css` o `components.css` (regla obligatoria de `01` §6).
- `tokens.contrast.test.ts` calcula con la fórmula de WCAG 2.1 cada par de `01` §5, en claro y en
  oscuro, resolviendo la cadena de `var()` desde el propio CSS, y falla por debajo de 4,5:1 (texto) o
  3:1 (componente). Es determinista, no necesita navegador y cubre el criterio 1.4.3 tal como lo
  plantea `03` §2 («automático sobre los tokens»). La exención de `--color-text-disabled` queda
  escrita en la tabla de pares, con su cita a `01` §5.1.
- En el navegador, la puerta de axe (decisión 8) renderiza cada historia con `data-theme="light"` y
  `data-theme="dark"`. La preferencia del sistema se ejercita además en una prueba de navegador que
  emula `prefers-color-scheme: dark` sin atributo, si la sonda S5 confirma que el proveedor lo
  permite; si no, la paridad estática es la garantía y se declara así.

**Descartado:** generar los dos bloques desde una sola fuente con un guion propio: añade un paso de
generación para ahorrar unas cien líneas que la prueba de paridad ya protege.

### Decisión 5: Inter servida desde el paquete

Dependencia `@fontsource-variable/inter` (archivos `woff2` con licencia OFL, sin guiones de
instalación), importada en `base.css` solo con los subconjuntos `latin` y `latin-ext`. La familia
`'Inter Variable'` se antepone a la pila de `01` §7.1. No hay CDN de terceros, ni en Storybook ni en
las aplicaciones. Cambiar la marca definitiva es editar `--font-sans` y los primitivos.

### Decisión 6: internacionalización con i18next, ICU y un catálogo tipado sin aumentar tipos globales

```
src/i18n/
├── locales/es-HN/ui.json   # espacio de nombres "ui"; claves en inglés con puntos (ADR-0011)
├── resources.ts            # export const uiResources = { "es-HN": { ui } } as const
├── create-ui-i18n.ts       # instancia para Storybook y pruebas: i18next + i18next-icu,
│                           # lng y fallbackLng "es-HN", supportedLngs ["es-HN"]
└── use-ui-text.ts          # useUiText(): t restringido a UiKey
```

- **Un solo catálogo.** ADR-0011 dice que existe uno solo; «inglés preparado» significa que añadir
  `locales/en/ui.json` y registrarlo en `supportedLngs` basta, no que exista hoy un archivo vacío. No
  hay selector de idioma.
- **Formato ICU** con `i18next-icu`, porque ADR-0011 lo exige para plurales.
- **Claves tipadas sin `CustomTypeOptions`.** `UiKey` se deriva del JSON de `es-HN` y `useUiText()`
  envuelve `useTranslation("ui")` con `t(key: UiKey)`. Aumentar `CustomTypeOptions` dentro de
  `packages/ui` chocaría con la declaración que cada aplicación hará para sus propios espacios de
  nombres en 13b: dos declaraciones de `resources` con tipos distintos no se fusionan. Con este
  diseño, **una clave inexistente es un error de `tsc`**, que es la verificación de análisis estático
  que pide ADR-0011, y su violación deliberada permanente es `src/i18n/keys.test-d.ts` con
  `// @ts-expect-error ADR-0011: ...`.
- Los textos de negocio de un componente (la acción de `EmptyState`, la etiqueta de `Button`) llegan
  por props desde el consumidor, ya traducidos. El catálogo `ui` solo contiene los textos propios del
  sistema de diseño: etiquetas de `StatusBadge`, «Reintentar» de `ErrorState`, el nombre accesible de
  `Spinner`, el estado de importe no válido de `MoneyAmount` y similares.
- Las aplicaciones integran el catálogo importando `uiResources` desde `@confia/ui/i18n`.

**Descartado:** `CustomTypeOptions` global (motivo anterior); formato nativo de i18next sin ICU
(contradice ADR-0011); un archivo `en` vacío (contradice «un solo catálogo»).

### Decisión 7: reglas de análisis estático de ADR-0011 con reglas propias de ESLint, sin complementos

`packages/config/eslint.js` exporta `uiSourceRules()` y `confiaEslint` acepta `ui: true`. Todas las
reglas usan reglas del núcleo de ESLint (`no-restricted-syntax`, `no-restricted-globals`,
`no-restricted-imports`), porque no dependen de que un complemento de terceros ya soporte ESLint 10:

| Control | Selector o regla | Ámbito |
|---|---|---|
| Sin texto en JSX | `JSXText[value=/\S/]` y `JSXAttribute[name.name=/^(aria-label\|aria-description\|title\|alt\|placeholder\|label)$/] > Literal` | `src/**/*.tsx` salvo historias y pruebas |
| Sin formato manual | `CallExpression[callee.property.name=/^(toFixed\|toLocaleString)$/]` | `src/**` salvo `src/format/**` |
| Sin primitivos | `Literal[value=/--palette-/]` y `TemplateElement[value.raw=/--palette-/]` | `src/**` salvo `src/tokens/**` |
| Sin aritmética ni conversión de importes | `Number()`, `parseFloat`, `parseInt`, `Math.*`, `+` unario, operadores `+ - * / %` | `src/format/**` y `src/domain/money-amount/**` |
| Sin peticiones | `no-restricted-globals`: `fetch`, `XMLHttpRequest`, `WebSocket`, `EventSource` | todo `packages/ui` |
| Capas y datos (espejo de dependency-cruiser) | `no-restricted-imports`, **compuesto** con `contractImportRestrictions` en una sola entrada por grupo de archivos | por directorio |

`no-restricted-syntax` tampoco se fusiona entre entradas de configuración: `uiSourceRules()` arma
**una** lista de selectores por grupo de archivos, y una prueba lo comprueba con el mismo
`ESLint.lintText` que ya usa `tooling/src/eslint-restrictions.test.ts`. Cada control tiene su
violación deliberada en `tooling/src/eslint-ui-rules.test.ts` (código en línea con la ruta de archivo
que activa el ámbito) y su caso aceptado.

**Supresiones.** `tooling/src/suppression-citations.test.ts` recorre `packages/ui` y falla ante
`eslint-disable`, `@ts-expect-error`, `@ts-ignore`, `a11y: { disable` o `test: "off"` sin una cita
`ADR-NNNN` en la misma línea o la anterior, o con un ADR que no exista en `docs/adr/` (ADR-0018 §4).

`eslint-plugin-react-hooks` se añade si la sonda S1 confirma su compatibilidad con ESLint 10; si no,
se registra como pendiente sin bloquear el cambio, porque ninguna regla de la propuesta depende de él.

### Decisión 8: la puerta de axe corre en Chromium real con historias portables y una política propia

**Mecanismo elegido:** un proyecto de Vitest en **modo navegador** con el proveedor de Playwright y
Chromium, que importa todas las historias con `import.meta.glob("../{atoms,molecules,domain}/**/*.stories.tsx")`,
las compone con `composeStories` (historias portables de Storybook), y por cada historia y cada tema
(`light`, `dark`):

1. fija `document.documentElement.dataset.theme`, renderiza la historia y ejecuta su `play` si la
   tiene (la verificación corre **después** de que el contenido cargó, `03` §9);
2. ejecuta `axe-core` sobre el contenedor con las etiquetas `wcag2a`, `wcag2aa`, `wcag21a`,
   `wcag21aa` y `wcag22aa`;
3. separa las violaciones con `partitionViolations()` de `src/a11y/policy.ts`: **crítica o seria
   falla la prueba**; moderada y menor se informan en `reports/a11y/report.json`, que el trabajo de CI
   sube como artefacto (`03` §11: «advierte y se registra»).

El proyecto falla además si descubre **cero historias** (ADR-0018). La hoja de estilos real se carga
con `@tailwindcss/vite` en el proyecto de navegador, así que la regla `color-contrast` de axe evalúa
colores reales.

**Por qué navegador real.** En jsdom axe no puede calcular contraste ni visibilidad, y marca esas
reglas como incompletas: la puerta pasaría en verde justo en lo que más importa a un sistema de
tokens. La prueba de contraste por token (decisión 4) cubre los pares declarados, pero no las
combinaciones reales de un componente.

**Por qué una política propia y no `parameters.a11y.test = "error"` del complemento.** El complemento
falla ante **cualquier** violación, sin umbral por impacto; `03` §11 exige bloquear crítica y seria
y solo registrar moderada y menor. Las etiquetas y los impactos viven en un único módulo,
`src/a11y/policy.ts`, que importan tanto la puerta como `.storybook/preview.tsx` (donde configuran el
panel del complemento de accesibilidad para quien revisa a mano). La política tiene pruebas unitarias
propias en jsdom.

**Violación deliberada permanente.** `src/a11y/__fixtures__/Inaccessible.stories.tsx` (fuera del
patrón de descubrimiento) contiene una imagen sin texto alternativo, un botón sin nombre accesible y
un texto con contraste insuficiente. `gate.a11y.test.tsx` pasa esas historias por la misma función de
la puerta y afirma que **rechaza** las tres, con `color-contrast` entre ellas. Eso prueba a la vez que
la puerta funciona y que el CSS está cargado.

**¿Exige navegadores de Playwright antes del cambio 11?** Sí, solo Chromium y solo para este trabajo.
Se propone así:

- Trabajo **nuevo** `design system accessibility` en `ci.yml`, sin tocar los existentes:
  `checkout`, `pnpm/action-setup` y `actions/setup-node` con los mismos SHA, instalación con
  `--frozen-lockfile`, `pnpm --filter @confia/ui exec playwright install --with-deps chromium`,
  `pnpm turbo run test:a11y --filter=@confia/ui` y subida de `packages/ui/reports/a11y/` con
  `if: always()`. `timeout-minutes: 15`.
- Sin caché de navegadores en este cambio: evita fijar una acción nueva y medir primero. La versión
  de Chromium la determina la de `playwright` del catálogo, así que es reproducible.
- `playwright` se declara como dependencia de desarrollo de `packages/ui`. El cambio 11 la reutiliza
  desde el catálogo; no hay dos versiones.
- `test:a11y` es una tarea **separada** de `test` en `turbo.json`: `pnpm turbo run lint typecheck
  test build` sigue funcionando en una máquina limpia sin navegador (criterio de aceptación 1). En
  local se ejecuta tras `pnpm --filter @confia/ui exec playwright install chromium`, documentado en
  `packages/ui/README.md`.
- No se crea ningún `*.spec.ts`: la puerta usa el sufijo `.a11y.test.tsx`, y nada vive bajo `apps/`,
  así que `IdentityScopeExclusionInventoryTest` sigue siendo cierta.

**Respaldo, solo con aprobación.** Si la sonda S1 o S5 demuestra que el modo navegador de Vitest 5 o
las historias portables no funcionan con las versiones del catálogo, el cambio **se detiene y se
informa** (propuesta, Enfoque 1). El respaldo que se presentaría es la misma puerta en jsdom, con
`color-contrast` declarada como no cubierta y compensada por la prueba de contraste por token; nunca
se adopta en silencio.

**Descartados:** el `test-runner` de Storybook (Jest más Playwright contra un Storybook servido),
sustituido por la integración con Vitest y con un servidor más que arrancar; el complemento
`addon-vitest` con `test: "error"` (sin umbral por impacto, y acopla la puerta a que el complemento
siga el ritmo de versiones de Vitest); Ladle (sin complemento de accesibilidad equivalente ni
historias portables); axe solo en pruebas de componente sin Storybook (pierde el catálogo visual que
`02` §8 exige como sustituto de revisión de diseño).

### Decisión 9: «un componente sin historia rompe la construcción»

`src/a11y/story-coverage.ts` exporta `findComponentsWithoutStory(root)`: un componente es un
directorio bajo `atoms/`, `molecules/` o `domain/` que contiene `<Nombre>.tsx`; debe existir
`<Nombre>.stories.tsx` en el mismo directorio y exportar una historia `Default`. La prueba
`story-coverage.test.ts`, en el proyecto `unit` (y por tanto en `frontend verify`):

- sobre `src/a11y/__fixtures__/tree/` (con un componente huérfano deliberado) afirma que lo detecta;
- sobre `src/` real afirma **cero huérfanos y más de cero componentes**.

«Historia verificada» queda cubierto porque la puerta de la decisión 8 descubre todos los archivos de
historias por patrón: una historia no puede existir sin pasar por axe. La única forma de escapar
sería `a11y: { disable: true }` o `test: "off"`, y el escáner de supresiones de la decisión 7 la
rechaza sin cita a un ADR. Los estados obligatorios de cada historia (carga, vacío, error,
deshabilitado cuando aplique) se revisan en el pull request; no se automatiza su presencia, porque
«cuando aplique» es un juicio.

### Decisión 10: reglas de capas de `packages/ui` en dependency-cruiser, espejadas en ESLint

| Regla | Desde | Hacia | Violación deliberada |
|---|---|---|---|
| `ui-atoms-know-only-tokens` | `packages/ui/src/atoms/` | `packages/ui/src/(molecules\|domain)/` | `tooling/dependency-fixtures/packages/ui/src/atoms/imports-a-molecule.ts` |
| `ui-molecules-never-import-domain` | `packages/ui/src/molecules/` | `packages/ui/src/domain/` | `.../src/molecules/imports-domain.ts` |
| `ui-never-fetches-data` | `packages/ui/` | `@tanstack/(react-)?query`, `axios`, `ky`, `swr` (resuelto o sin resolver) | `.../src/domain/imports-react-query.ts` |
| `ui-contracts-type-only` | `packages/ui/` | `packages/contracts/`, con `dependencyTypesNot: ["type-only"]` | `.../src/domain/imports-contract-value.ts` |

- La regla existente `contracts-only-through-public-entry` ya obliga a entrar por `admin` o
  `portal`. `MoneyAmount` importa `import type { Money } from "@confia/contracts/portal"`: el
  contrato menos privilegiado, utilizable por las dos aplicaciones. Una prueba de tipos
  (`money-contract.test-d.ts`) afirma que el `Money` de `admin` y el de `portal` son asignables al
  tipo que recibe `MoneyAmount`, para que una divergencia entre documentos rompa `typecheck`.
- `.dependency-cruiser.cjs` añade `.tsx` a `extensions`. `tooling/src/dependency-rules.test.ts`
  amplía `RULES` y añade una aserción: el árbol real contiene **al menos un módulo** en cada uno de
  `atoms/`, `molecules/` y `domain/`, para que ninguna de las reglas nuevas pase sobre un conjunto
  vacío (ADR-0018). Por eso estas reglas llegan en el corte que crea el primer componente de dominio
  (sección 8), y no antes: así no hace falta ninguna excepción temporal.
- `no-restricted-globals` (decisión 7) cubre las peticiones que no son importaciones.

### Decisión 11: componentes sobre shadcn/ui y Radix, adaptados a los tokens de CONFIA

- El CLI de shadcn/ui **no** se ejecuta dentro del repositorio ni se declara como dependencia:
  reescribiría la hoja de estilos con sus propias variables (`--background`, `--primary`) y
  añadiría dependencias por su cuenta. Se genera cada componente en un directorio temporal fuera del
  repositorio con la versión fijada en S1, se copia el código y se **adapta**: clases a los
  semánticos de CONFIA (`text-primary-fg`, no `text-primary-foreground`), estados de `02` §6, textos
  por props o catálogo. Cada archivo lleva una cabecera con el componente y la versión de origen. El
  código cuenta como línea de autor, como dice la propuesta.
- Dependencias de ejecución: `react`, `react-dom`, Radix (el paquete que genere la versión fijada),
  `class-variance-authority`, `clsx`, `tailwind-merge` y `lucide-react`. `cn()` usa
  `extendTailwindMerge` con las escalas propias de CONFIA (`text-xs` a `text-5xl` frente a
  `text-status-*-fg`), porque si no `tailwind-merge` puede descartar una clase de color al creer que
  choca con una de tamaño.
- `Spinner` es propio (`02` §4), con nombre accesible desde el catálogo y `role="status"`.

### Decisión 12: `MoneyAmount` formatea, valida y nunca calcula

`src/format/money.ts` es la única utilidad de formato de importes:

```ts
export type MoneyValue = Money; // import type desde @confia/contracts/portal

export type FormattedMoney =
  | { kind: "formatted"; visual: string; spoken: string }
  | { kind: "invalid" };

export function formatMoney(
  value: MoneyValue,
  locale: string,
  options: { sign: "auto" | "always" | "accounting"; hideCurrency: boolean },
): FormattedMoney;
```

- La cadena llega a `Intl.NumberFormat.prototype.format` **como cadena**: los motores con
  `Intl.NumberFormat` v3 la interpretan como decimal exacto, sin pasar por `number` (sonda S3).
  Opciones fijas: `style: "currency"` (o `"decimal"` con `hideCurrency`), la moneda del propio
  importe, `minimumFractionDigits: 2`, `maximumFractionDigits: 2`; `signDisplay: "exceptZero"` para
  `always`, y `currencySign: "accounting"` para el contexto contable.
- **Validación antes de formatear.** `amount` debe cumplir `^-?\d+(\.\d+)?$` y, si tiene más de dos
  decimales, los sobrantes deben ser ceros (`"3200.0000"` es válido; `"3200.125"` no). `currency`
  debe ser `^[A-Z]{3}$` y estar en `Intl.supportedValuesOf("currency")`. Formatear `"3200.125"` con
  dos decimales sería **redondear en el navegador**, que ADR-0011 prohíbe: la función devuelve
  `invalid` y `MoneyAmount` muestra el texto `money.unavailable` del catálogo, nunca una cifra. El
  cero válido se muestra como `0.00`, nunca vacío.
- `spoken` se obtiene con `currencyDisplay: "name"` y va en un texto oculto visualmente; la forma
  visual lleva `aria-hidden`. Así el lector de pantalla lee la moneda completa (`03` §10).
- Numeración tabular forzada por la clase `money-amount` de `base.css`; el consumidor no la puede
  desactivar (`01` §7.5). Alineación a la derecha en tablas: responsabilidad de la celda, no del
  componente.
- Las reglas de ESLint de la decisión 7 prohíben en este archivo y en `domain/money-amount/` toda
  conversión a número y toda aritmética. Umbral de cobertura propio del 95 % para `src/format/**`,
  porque es la única regla financiera del frontend.

### Decisión 13: versiones y compatibilidad

Familias objetivo, a confirmar en S1 contra las fijadas en el catálogo (TypeScript 6.0.3, Vitest
5.0.2, ESLint 10.11.0, Node 24, pnpm 12.8.1). Cada versión exacta se añade al `catalog:` de
`pnpm-workspace.yaml` y se registra en el registro de aplicación con sus dependencias pares:

| Paquete | Familia objetivo | Criterio de S1 |
|---|---|---|
| `react`, `react-dom`, `@types/react`, `@types/react-dom` | 19.x | Sin conflictos de pares con Radix ni Storybook |
| `tailwindcss`, `@tailwindcss/vite` | 4.x, la misma para ambos | `compile()` disponible para la prueba de tokens |
| `vite`, `@vitejs/plugin-react` | la familia de Vite que trae Vitest 5.0.2 | Una sola versión de Vite en el árbol |
| `storybook`, `@storybook/react-vite`, `@storybook/addon-a11y` | la última mayor estable, la misma para los tres | Acepta React 19, esa Vite y TypeScript 6.0 |
| `@vitest/browser-playwright`, `@vitest/coverage-v8` | 5.0.2, igual que `vitest` | Modo navegador con Chromium |
| `playwright` | la que exija el proveedor de Vitest | `install chromium` sin guion de instalación |
| `axe-core` | 4.x | Etiquetas `wcag22aa` disponibles |
| `i18next`, `react-i18next`, `i18next-icu`, `intl-messageformat` | estables actuales | Tipos compatibles con TypeScript 6.0 |
| `@testing-library/react`, `@testing-library/user-event`, `@testing-library/jest-dom`, `jsdom` | estables actuales | Compatibles con Vitest 5 |
| Radix, `class-variance-authority`, `clsx`, `tailwind-merge`, `lucide-react`, `@fontsource-variable/inter` | las que use la versión de shadcn/ui fijada | `tailwind-merge` con soporte de Tailwind v4 |

Ninguna versión se degrada en silencio para encajar: si una familia no admite TypeScript 6.0 o
Vitest 5, se detiene y se informa.

## 4. Flujo de datos

```
 contrato generado               consumidor (13b: contenedor)           packages/ui (presentación)
 Money {amount, currency} ──►  useQuery ... data.balance ──props──►  MoneyAmount
                                                                         │ formatMoney(value, locale)
                                                                         │   valida patrón y escala
                                                                         │   Intl.NumberFormat(string)
                                                                         ▼
                                                       <span aria-hidden>L 3,200.00</span>
                                                       <span class="sr-only">3,200.00 lempiras…</span>

 ui.json (es-HN) ──► uiResources ──► i18next (app o Storybook) ──► useUiText().t(UiKey) ──► texto
 tokens/*.css ──► @confia/ui/styles.css ──► Tailwind (Vite) ──► utilidades semánticas ──► componentes
 <html data-theme> o prefers-color-scheme ──► themes.css cambia semánticos ──► componentes sin cambio
```

La presentación nunca obtiene datos: recibe props y callbacks (`02` §2).

## 5. Cambios de archivos

| Archivo | Acción | Descripción |
|---|---|---|
| `packages/ui/package.json`, `tsconfig.json`, `vitest.config.ts`, `eslint.config.js`, `README.md` | Crear | Paquete, proyectos `unit` y `a11y`, cobertura 80 % (95 % en `src/format/**`), instalación local de Chromium |
| `packages/ui/.storybook/main.ts`, `preview.tsx` | Crear | Storybook con `@tailwindcss/vite`, i18n, tema y política de axe |
| `packages/ui/src/index.ts` | Crear | Único punto de entrada |
| `packages/ui/src/tokens/{index,primitives,semantic,themes,components,base}.css` | Crear | Decisiones 2 a 5 |
| `packages/ui/src/tokens/tokens.{build,parity,contrast}.test.ts` | Crear | Pruebas de tokens |
| `packages/ui/src/i18n/**` | Crear | Decisión 6, incluido `keys.test-d.ts` |
| `packages/ui/src/format/money.ts`, `money.test.ts` | Crear | Decisión 12 |
| `packages/ui/src/a11y/{policy,story-coverage}.ts`, sus pruebas, `stories.a11y.test.tsx`, `gate.a11y.test.tsx`, `__fixtures__/**` | Crear | Decisiones 8 y 9 |
| `packages/ui/src/lib/cn.ts` | Crear | `clsx` más `tailwind-merge` extendido |
| `packages/ui/src/atoms/*/**` (11), `molecules/*/**` (3), `domain/*/**` (2) | Crear | Componente, historia, prueba e índice |
| `packages/ui/src/domain/money-amount/money-contract.test-d.ts` | Crear | Paridad del `Money` de `admin` y `portal` |
| `packages/config/eslint.js`, `eslint.d.ts` | Modificar | `uiSourceRules()`, opción `ui`, composición de `no-restricted-imports` |
| `packages/config/vitest.js` | Modificar | Admitir `src/**/*.test.tsx` además de `*.test.ts`, sin cambiar el comportamiento de los paquetes existentes |
| `.dependency-cruiser.cjs` | Modificar | Cuatro reglas nuevas y `.tsx` en `extensions` |
| `tooling/dependency-fixtures/packages/ui/src/**` | Crear | Una violación por regla y los módulos mínimos que importan |
| `tooling/src/dependency-rules.test.ts` | Modificar | Reglas nuevas y aserción de capa no vacía |
| `tooling/src/eslint-ui-rules.test.ts`, `suppression-citations.test.ts` | Crear | Violaciones deliberadas de la decisión 7 |
| `pnpm-workspace.yaml`, `pnpm-lock.yaml` | Modificar | Catálogo; `allowBuilds` solo con lo que S4 demuestre, cada uno con su motivo |
| `turbo.json` | Modificar | Tarea `test:a11y`; salida `dist/storybook/**` del `build` de `packages/ui` |
| `.github/workflows/ci.yml` | Modificar, solo aditiva | Trabajo `design system accessibility` |
| `docs/adr/ADR-0026-storybook-y-verificacion-de-accesibilidad.md`, `docs/adr/README.md` | Crear y modificar | ADR en estado Propuesto e índice |
| `CLAUDE.md`, `docs/01-arquitectura.md` §3.3 y §3.4 | Modificar | Storybook en la tabla de stack |
| `docs/ui-ux/01-tokens-de-diseno.md` §14 | Modificar | Nota fechada: primitivos en `--palette-*`, ruta real `src/tokens/` |
| `docs/ui-ux/02-sistema-de-diseno-y-componentes.md` §5.1 | Modificar | `MoneyAmount` con el contrato `Money` vigente |
| `docs/09-roadmap-y-fases.md` | Modificar | Entregable 10 cerrado; criterio de salida 8 asignado a 13b |
| `apps/api/**` | Sin cambios | Fuera de alcance |

El `build` de `packages/ui` es `storybook build --output-dir dist/storybook`: el trabajo
`frontend verify` comprueba sin cambios propios que el catálogo compila.

## 6. Interfaces

```ts
// src/a11y/policy.ts
export const A11Y_TAGS = ["wcag2a", "wcag2aa", "wcag21a", "wcag21aa", "wcag22aa"] as const;
export const BLOCKING_IMPACTS = ["critical", "serious"] as const;
export function partitionViolations(violations: readonly axe.Result[]): {
  blocking: axe.Result[];
  reported: axe.Result[];
};

// src/a11y/story-coverage.ts
export function findComponentsWithoutStory(root: string): {
  components: number;
  orphans: string[];
};

// src/domain/money-amount/MoneyAmount.tsx
export interface MoneyAmountProps {
  value: Money; // { amount: string; currency: string }, ya redondeado por el servidor
  emphasis?: "normal" | "strong" | "muted";
  size?: "sm" | "md" | "lg" | "xl";
  colorBySign?: boolean;      // solo variaciones
  showPositiveSign?: boolean; // solo variaciones
  accounting?: boolean;       // negativos entre paréntesis
  hideCurrency?: boolean;     // solo en tablas que ya declaran la moneda
}

// src/domain/status-badge/StatusBadge.tsx
export type FinancialStatus =
  | "paid" | "pending" | "partial" | "overdue" | "void" | "promised" | "refunded";
export interface StatusBadgeProps {
  status: FinancialStatus;
  variant?: "soft" | "solid" | "outline";
  size?: "sm" | "md";
  iconOnly?: boolean; // el texto sigue disponible para lectores de pantalla
}

// src/molecules/empty-state y error-state: la acción y el reintento son obligatorios por tipo
export interface EmptyStateProps {
  title: string;
  description?: string;
  action: { label: string; onAction: () => void };
}
export interface ErrorStateProps {
  title: string;
  description?: string;
  onRetry: () => void;
  retryLabel?: string; // por defecto, la clave del catálogo
}

// packages/config/eslint.js
export function uiSourceRules(): import("eslint").Linter.Config[];
export function confiaEslint(options: {
  tsconfigRootDir: string; ignores?: string[]; portal?: boolean; ui?: boolean;
}): import("eslint").Linter.Config[];
```

## 7. Sondas, antes de la tarea que depende de cada una

| # | Pregunta | Criterio de éxito | Si falla | Bloquea |
|---|---|---|---|---|
| **S1** | ¿Qué versiones de Storybook, su complemento de accesibilidad, i18next, shadcn/ui y `eslint-plugin-react-hooks` admiten React 19, la Vite de Vitest 5.0.2, TypeScript 6.0.3 y ESLint 10? | `pnpm install --frozen-lockfile` limpio, sin `overrides` de pares, y una historia mínima compila con `storybook build` | Se detiene y se informa; `react-hooks` puede quedar pendiente | Corte 13a-1 |
| **S2** | ¿Emite Tailwind v4 las variables de un espacio de nombres propio (`--palette-*`) bajo `@theme static`, sin utilidades, y borra el tema con `--*: initial`? | `tokens.build.test.ts` en verde, con la violación deliberada sin regla generada | Primitivos en `:root` fuera de `@theme` (respaldo de la decisión 3), registrado | Corte 13a-1 |
| **S3** | ¿Formatea `Intl.NumberFormat` una cadena decimal sin pasar por `number`, igual en Node 24 y en Chromium, y la acepta el tipo de `format` en TypeScript 6.0 con `lib` ES2023? | `"12345678901234567.89"` se formatea sin pérdida en ambos motores; salida de `es-HN` registrada | Se detiene y se informa: no hay formato aceptable que convierta a `number` | Corte 13a-2 |
| **S4** | ¿Qué dependencias nuevas piden guiones de instalación bajo `strictDepBuilds`, y pasa `pnpm audit --audit-level high`? | Cada petición resuelta en `allowBuilds` con su motivo (por ejemplo, `@tailwindcss/oxide: false` si sus binarios llegan como paquetes opcionales); auditoría sin altas | `overrides` documentados uno por uno; la auditoría nunca se desactiva | Corte 13a-1 |
| **S5** | ¿Funcionan las historias portables en el modo navegador de Vitest 5 con Chromium, carga el CSS real y detecta axe el contraste? | La historia inaccesible deliberada produce `color-contrast`, `image-alt` y `button-name`; se registra si el proveedor permite emular `prefers-color-scheme` | Se detiene y se informa, con el respaldo en jsdom de la decisión 8 como propuesta | Corte 13a-3 |

**Nota fechada 2026-10-28, resultado de las sondas (tarea 1.1): S1 a S5 cumplen su criterio.** Se ejecutaron en un directorio fuera del repositorio
con Node 24.22.1 y pnpm 12.8.1, `strictDepBuilds: true`, `allowBuilds` vigente y el catálogo actual. context7 no estuvo disponible; las versiones salen
del registro de npm (`pnpm view`, solo publicadas hasta el 2026-10-14, al menos dos semanas) y los tipos de los propios paquetes.

| Sonda | Resultado | Evidencia breve |
|---|---|---|
| S1 | Cumple | `pnpm install` limpio y luego `--frozen-lockfile` sin avisos de pares, sin `overrides`; una sola Vite (8.3.4); `storybook build` correcto; `tsc` correcto con la base del repositorio (`skipLibCheck: true`, `exactOptionalPropertyTypes`); `eslint-plugin-react-hooks` 7.1.1 admite ESLint 10 y rechaza un gancho condicional |
| S2 | Cumple | `bg-primary` emite regla; `bg-palette-primary-600` y `bg-red-500` no; `--palette-primary-600` se emite aunque nada la use; control: sin `--*: initial`, `bg-red-500` sí se emite |
| S3 | Cumple | `"12345678901234567.89"` y `"123456789012345678.90"` sin pérdida; Node 24 y Chromium 156 producen salida idéntica; ver notas |
| S4 | Cumple | Ninguna dependencia pidió guiones de instalación; `allowBuilds` no cambia (`esbuild: false`); `pnpm audit --audit-level high`: 0 altas, 4 moderadas (ver notas) |
| S5 | Cumple | `image-alt`, `button-name` y `color-contrast` como violaciones (no incompletas); CSS real cargado; `colorScheme` emulable; comando personalizado escribe `reports/a11y/*.json` |

Versiones fijadas (todas con al menos dos semanas, salvo `vitest` que ya estaba en el catálogo): `react` y `react-dom` 19.3.0; `@types/react` y
`@types/react-dom` 19.3.0; `tailwindcss` y `@tailwindcss/vite` 4.3.3; `vite` 8.3.4; `@vitejs/plugin-react` 6.1.2; `storybook`, `@storybook/react-vite` y
`@storybook/addon-a11y` 10.6.2; `vitest`, `@vitest/browser-playwright` y `@vitest/coverage-v8` 5.0.2; `playwright` 1.64.1; `axe-core` 4.14.0;
`i18next` 26.4.4; `react-i18next` 17.0.16; `i18next-icu` 2.5.0; `intl-messageformat` 12.1.3; `@testing-library/react` 16.3.3;
`@testing-library/dom` 10.4.2; `@testing-library/user-event` 14.6.8; `@testing-library/jest-dom` 7.0.1; `jsdom` 30.1.2; `eslint-plugin-react-hooks` 7.1.1;
`@fontsource-variable/inter` 5.3.0; `tailwind-merge` 3.7.0; `clsx` 2.1.1; `class-variance-authority` 0.7.1; `lucide-react` 1.57.0; `radix-ui` 1.8.1;
CLI `shadcn` 4.21.4 (solo para generar fuera del repositorio).

Notas que afectan a tareas posteriores:

- **Pares.** `@vitejs/plugin-react` 6 exige `vite ^8`; Vitest 5.0.2 admite Vite 6.4, 7 y 8, y `@tailwindcss/vite` 4.3.3 hasta 8. `@vitest/coverage-v8`
  pide `@vitest/browser` como par (pnpm lo instala solo). `@testing-library/jest-dom` 7 pide `@testing-library/dom` 10 (se declara explícito).
  `react-i18next` 17 pide `i18next >=26.2`.
- **`compile()` de Tailwind (1.3).** Basta `import { compile } from "tailwindcss"` con un `loadStylesheet` que lea `tailwindcss/index.css`; no hace falta
  `@tailwindcss/node`.
- **`Intl` (2.1, 6.1).** `es-HN` da `L 3,200.00` con espacio no separable (U+00A0) y `USD 3,200.00` (no `US$`); el negativo sale `-L 1,500.00`;
  `currencySign: "accounting"` en `es-HN` **no** da paréntesis (`-L 1,500.00`), así que DS34 (contable con paréntesis) debe construirse sin depender de
  `Intl` o replantearse; `currencyDisplay: "name"` da `3,200.50 lempiras hondureños` y `3,200.50 dólares estadounidenses`. Cero negativo
  (`"-0.00"`, `"-0.0000"`): `signDisplay: "auto"` y `"always"` dan `-L 0.00`; `"exceptZero"` y `"negative"` dan `L 0.00` (se usa `"negative"`, o
  `"exceptZero"` con `sign: "always"`). `"3200.005"` y `"3200.125"` se redondean en silencio (`L 3,200.01`, `L 3,200.13`): la validación previa es
  obligatoria. `Intl.supportedValuesOf("currency")` tiene 162 entradas en Node y 159 en Chromium (HNL y USD en ambos): la lista blanca de monedas no
  puede compararse por cantidad entre motores.
- **Tipos (2.1).** `lib.es2023.intl.d.ts` declara `format(value: number | bigint | StringNumericLiteral)`: acepta literales como
  `"12345678901234567.89"` pero rechaza un `string` genérico; tras validar por patrón se estrecha con un guardián de tipo a `` `${number}` ``.
- **Vitest en navegador.** En Vitest 5 el puerto de la API (`test.api.port`) se fija en la raíz; `test.browser.api` está obsoleto. En esta máquina el
  puerto predeterminado 63315 cae en un rango excluido de Windows (`EACCES`), por lo que la configuración debe poder fijar otro. Las historias con
  `import "./index.css"` necesitan `types: ["vite/client"]` en `tsconfig`. `page.emulateMedia` no existe en `vitest/browser`: se hace con un comando
  personalizado (`defineBrowserCommand` y `provider.getPage(sessionId).emulateMedia`).
- **Auditoría.** Las 4 moderadas son de Vitest 5.0.2 (`@vitest/mocker` <5.0.3, GHSA-qxg6-89mg-rqv8 y GHSA-xp26-3454-mjgp; `@vitest/browser` y
  `@vitest/ui` <5.0.5, GHSA-p458-7748-mcp3, que sirve archivos privados desde el servidor de desarrollo). Corregidas en 5.0.5 (publicada el
  2026-10-15, aún sin las dos semanas). No bloquean (el umbral es `high`); se propone subir el catálogo a 5.0.5 como decisión del propietario cuando
  cumpla la antigüedad.
- **Chromium.** `playwright install chromium` no ejecutó guiones; se probó con `PLAYWRIGHT_BROWSERS_PATH` fuera del repositorio.

## 8. División del pull request

Las pruebas y la documentación viajan con su código (`docs/15` §3), así que cuentan dentro de las
ochocientas líneas. La estimación honesta del cambio completo ronda las **3.800 líneas de autor**
(los bloques de tokens de `01` §14 ya suman unas 330 de CSS, y cada componente con su historia y su
prueba entre 60 y 200). Caben en **seis cortes encadenados**, no en los dos que anticipó la propuesta.
Cada uno compila, pasa sus pruebas y tiene sentido por sí solo; el archivo de bloqueo no cuenta como
línea de autor.

| Corte | Contenido | Estimación |
|---|---|---|
| **13a-1 Paquete y tokens** | S1, S2, S4; `packages/ui` mínimo; catálogo; tokens en tres niveles, temas, Inter; pruebas de compilación, paridad y contraste | ~700 |
| **13a-2 Textos y análisis estático** | S3; i18n tipado; `formatMoney` y sus casos límite; reglas de ESLint de ADR-0011, de primitivos y de peticiones con sus violaciones; escáner de supresiones | ~650 |
| **13a-3 Storybook y puerta de axe** | S5; Storybook; política, puerta y fixture inaccesible; comprobación de historias; `Button` como primer componente real (para que ninguna comprobación quede vacía); tarea `test:a11y`; trabajo de CI; ADR-0026; Storybook en `CLAUDE.md` y `docs/01` | ~700 |
| **13a-4 Átomos de presentación** | `IconButton`, `Label`, `Badge`, `Spinner`, `Separator`, `Skeleton` | ~450 |
| **13a-5 Controles y campo de formulario** | `Input`, `Textarea`, `Checkbox`, `RadioGroup`, `FormField` con `FormError` | ~700 |
| **13a-6 Estados, dominio y capas** | `EmptyState`, `ErrorState`, `MoneyAmount`, `StatusBadge`; reglas de capas con sus fixtures y la aserción de capa no vacía; `docs/ui-ux/02` §5.1; nota de `docs/09` | ~750 |

Cada corte apunta al anterior. 13a-4 y 13a-5 son los de mayor riesgo de crecer: si uno se acerca al
límite, `RadioGroup` o `Checkbox` pasan al corte siguiente sin reordenar los demás.

## 9. Estrategia de pruebas

| Capa | Qué | Cómo |
|---|---|---|
| Tokens | Sin utilidades de primitivos ni de la paleta predeterminada; paridad de temas; contraste de cada par de `01` §5 | Vitest en Node sobre el CSS y la API `compile()` |
| Análisis estático | Literal en JSX, `toFixed`, `--palette-`, `fetch`, aritmética en importes, supresión sin ADR, clave inexistente | `ESLint.lintText` con violación y caso aceptado; `@ts-expect-error` citado en `keys.test-d.ts` |
| Capas | Cuatro reglas de `packages/ui` | dependency-cruiser sobre fixtures y sobre el árbol real con cada capa no vacía |
| Formato de importes | Cero, negativo, contable, signo explícito, importe grande (17 cifras), `HNL` y `USD`, moneda inexistente, más de dos decimales no nulos, ceros sobrantes, cadena no numérica | Vitest en Node con salidas registradas en S3; una comprobación en Chromium de que la salida coincide |
| Componentes | Props, estados, nombres accesibles, `iconOnly`, acción y reintento obligatorios | Testing Library en jsdom, solo con props; cobertura 80 % global y 95 % en `src/format/**` |
| Historias | Cero violaciones críticas o serias por historia y por tema; la fixture inaccesible rechazada; cero historias = fallo | Vitest en modo navegador, Chromium, axe-core |
| Catálogo | Todo componente con historia `Default`; la fixture huérfana detectada | Vitest en Node |
| Extremo a extremo | No aplica | Llega con la primera pantalla (13b) y con el cambio 11 |

Desarrollo con prueba primero: cada regla y cada componente se escriben después de observar en rojo
su prueba o su violación deliberada.

## 10. Matriz de amenazas

| Frontera | Aplicabilidad | Respuesta de diseño | Prueba en rojo prevista |
|---|---|---|---|
| Rutas de apariencia documental (MDX ejecutable) | **Aplica**: Storybook admite MDX, que es código | Solo historias CSF en `.stories.tsx`; `main.ts` no incluye `*.mdx` | Prueba que falla si existe un `.mdx` bajo `packages/ui` o si el patrón de `main.ts` lo admite |
| Selección de repositorio Git | N/A: el cambio no ejecuta Git | — | — |
| Estado de confirmación | N/A: sin automatización de commits | — | — |
| Estado de empuje | N/A: sin automatización de empuje | — | — |
| Órdenes de pull request | N/A: sin automatización de PR | — | — |

Fronteras de cadena de suministro, tratadas como en el cambio 3: dependencias con guiones de
instalación (`strictDepBuilds`, S4), vulnerabilidades (`pnpm audit` existente cubre los paquetes
nuevos), descarga de Chromium (versión fijada por `playwright` del catálogo, solo en el trabajo
nuevo) y fuentes de terceros (Inter servida desde el paquete, sin CDN).

## 11. ADR-0026: esquema de contenido

- **Título:** «Storybook como catálogo de `packages/ui` y banco de la verificación de accesibilidad».
- **Estado:** Propuesto. **Número:** 0026, porque 0025 está reservado por
  `background-jobs-with-db-scheduler`; se reconcilia contra `main` antes de fusionar 13a-3.
- **Contexto:** `02` §8 y `03` §9 y §11 exigen Storybook y axe, pero Storybook no figura en la tabla
  de stack; sin pantallas, la puerta de accesibilidad no tiene dónde ejecutarse; jsdom no evalúa
  contraste.
- **Factores:** bloqueo real ante crítica y seria; sin conjunto vacío (ADR-0018); catálogo visual
  como sustituto de revisión de diseño con un solo desarrollador; costo en CI; compatibilidad con el
  catálogo de versiones.
- **Opciones:** A, Storybook con historias portables y puerta propia en Chromium (elegida); B,
  pruebas de componente con axe en jsdom, sin Storybook; C, Ladle; D, `addon-vitest` con
  `test: "error"`; E, `test-runner` de Storybook.
- **Decisión y restricción permanente:** todo componente de `packages/ui` lleva historia, y toda
  historia pasa por axe en dos temas; crítica y seria bloquean, moderada y menor se registran.
  Chromium entra en CI antes del cambio 11, solo en el trabajo de la puerta.
- **Consecuencias:** dependencias de Storybook en el árbol; un trabajo de CI más; política propia que
  mantener; el cambio 11 reutiliza `playwright` del catálogo.
- **Cumplimiento:** comprobación de historias, puerta con fixture inaccesible, escáner de
  supresiones, aserción de cero historias.
- **Reversión:** si se revierte tras fusionar, el ADR pasa a Rechazado; no se borra.

## 12. Migración y reversión

No hay base de datos, esquema, datos, endpoints ni despliegue. Ninguna aplicación consume aún
`packages/ui`.

- Cada corte se revierte por sí solo, en orden inverso, con `git revert` de su PR; el archivo de
  bloqueo se regenera, nunca se edita a mano.
- Revertir 13a-3 retira el trabajo de CI nuevo sin tocar los demás, y marca ADR-0026 como Rechazado
  si ya se fusionó.
- Revertir 13a-1 exige revertir antes los cortes posteriores, porque todos dependen del paquete.
- Las notas de `docs/ui-ux/01`, `02` y `docs/09` se revierten con su corte; el contenido previo queda
  en el historial.

## 13. Preguntas abiertas

- [ ] **Para el propietario:** la estimación real pide seis cortes encadenados, no los dos que
      anticipó la propuesta. Recomendación: mantener el alcance aprobado y encadenar los seis. La
      alternativa es sacar `Checkbox`, `RadioGroup` y `Textarea` del cambio y dejarlos para la
      primera pantalla que los necesite (criterio de promoción de `02` §3), lo que ahorra un corte a
      cambio de un inventario P0 incompleto en 13b.
- [ ] Técnica, se resuelve en S1: versiones exactas de la sección 3, decisión 13.
- [ ] Técnica, se resuelve en S5: si la emulación de `prefers-color-scheme` en el navegador entra en
      la puerta o queda cubierta solo por la prueba de paridad.
