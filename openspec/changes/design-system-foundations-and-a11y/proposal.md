# Propuesta: fundamentos del sistema de diseño y verificación de accesibilidad

- **Cambio:** `design-system-foundations-and-a11y`
- **Fase del roadmap:** F0, cambio 13a (división del cambio 13 decidida por el propietario el
  2026-10-28)
- **Depende de:** cambio 3 (`frontend-monorepo-and-contracts-pipeline`, archivado el 2026-09-30)
- **Corre en paralelo con:** la cadena del backend. Este cambio no toca `apps/api`.
- **Exploración:** Engram `sdd/design-system-foundations-and-a11y/explore`
- **Estado:** propuesta, pendiente de aprobación del propietario del producto

## Intención

El cliente necesita ver interfaces mientras la cadena del backend avanza, pero la primera pantalla
no puede llegar antes que el sistema que la gobierna. `docs/ui-ux/` fija tokens en tres niveles,
modo oscuro, un inventario de componentes P0 y una puerta de accesibilidad que **bloquea la
fusión** ante violaciones críticas o serias (`03-accesibilidad.md` §11). **Nada de eso existe:** el
monorepo solo tiene `packages/config` y `packages/contracts`; no hay `packages/ui`, ni Tailwind, ni
shadcn/ui, ni i18next, ni Storybook, ni ninguna verificación de axe.

Si la primera pantalla llegara antes, sus colores, textos y formatos de importe se escribirían en
línea, y cada uno de esos atajos es justo lo que las reglas 3 y 10 de `CLAUDE.md` y ADR-0011
prohíben: importes formateados a mano, cadenas embebidas y colores sin token. Este cambio instala
esa maquinaria antes de la primera pantalla, igual que el cambio 3 instaló el canal de contratos
antes del primer endpoint.

Cubre el **entregable 10 de F0** («sistema de diseño base en `packages/ui` con tokens, átomos y
verificación de accesibilidad automatizada»). **No cierra el criterio de salida 8** («la
verificación de accesibilidad no reporta violaciones críticas en las pantallas existentes»): sin
pantallas, ese criterio pasaría sobre un conjunto vacío, y ADR-0018 prohíbe tratar un conjunto
vacío como verde. Este cambio deja el mecanismo listo; el criterio se cierra con la primera
pantalla real, en 13b.

## Alcance

### Dentro de alcance

1. **`packages/ui` como paquete del espacio de trabajo**, con la estructura de
   `docs/ui-ux/02-sistema-de-diseno-y-componentes.md` §3 (`tokens/`, `atoms/`, `molecules/`,
   `domain/`, `hooks/`), React 19 y su punto de entrada público único.
2. **Tokens en tres niveles con Tailwind CSS v4** (`01-tokens-de-diseno.md` §1): primitivos solo
   dentro de `@theme`, semánticos y de componente como única superficie utilizable, y la parte de
   Tailwind de `packages/config` que el cambio 3 dejó pendiente. **Modo claro y oscuro** por
   preferencia del sistema operativo y por atributo `data-theme` (§6), sin ningún color definido
   únicamente en el bloque oscuro.
3. **Marca provisional y reemplazable:** la propuesta v1.0 de `docs/ui-ux/` (Inter, primario
   `#2563EB`), concentrada en los primitivos y en la familia tipográfica, de modo que cambiar la
   marca definitiva sea editar tokens, nunca componentes. Inter se sirve desde el propio paquete,
   sin CDN de terceros.
4. **Átomos P0 sobre shadcn/ui y Radix** (`02` §4): `Button`, `IconButton`, `Input`, `Textarea`,
   `Label`, `Checkbox`, `RadioGroup`, `Badge`, `Spinner`, `Separator` y `Skeleton`.
5. **Moléculas:** `FormField` (etiqueta, control, descripción y error como una unidad, con
   `FormError`), `EmptyState` (siempre con acción principal) y `ErrorState` (siempre con
   reintento).
6. **Moléculas de dominio:**
   - `MoneyAmount`: recibe el `Money` del contrato generado, `{ amount: string, currency: string }`,
     ya redondeado por el servidor, y lo formatea **solo** con `Intl.NumberFormat`: sin aritmética,
     sin redondeo, sin conversión a `number`, siempre dos decimales, numeración tabular y moneda
     visible y legible por lector de pantalla.
   - `StatusBadge`: estado financiero siempre con color, icono y texto a la vez (`02` §5.2).
7. **Internacionalización con i18next:** catálogo `es-HN` como idioma predeterminado, inglés
   preparado sin traducir (ADR-0011), y los tres controles de ADR-0011 activos en el análisis
   estático: sin literales de texto en JSX, sin `toFixed` ni `toLocaleString` fuera de las
   utilidades de formato, y una verificación que falla si una clave usada no existe en el catálogo.
8. **Storybook con el complemento de accesibilidad:** una historia por componente con sus estados
   (por defecto, carga, vacío, error y deshabilitado cuando aplique) en modo claro y oscuro, y las
   violaciones críticas y serias de axe configuradas como **error** (`03` §9). Un componente de
   `packages/ui` sin historia verificada rompe la construcción (`03` §11).
9. **Pruebas con Vitest y Testing Library:** pruebas de componente puramente con props, con los
   casos límite de `MoneyAmount` que exige ADR-0011 (cero, negativo, importes grandes, monedas
   distintas, entrada fuera del patrón del contrato), y la cobertura mínima del ochenta por ciento
   sobre `packages/ui`.
10. **Reglas de dependencia de `packages/ui`** con `dependency-cruiser` y ESLint, cada una con su
    violación deliberada (ADR-0018): los átomos no importan moléculas ni dominio; las moléculas
    genéricas no importan dominio; ningún archivo de `packages/ui` importa TanStack Query ni hace
    peticiones (la presentación no obtiene datos, `02` §2); y `packages/ui` llega a
    `packages/contracts` solo por su punto de entrada público y solo para tipos.
11. **Integración continua, solo de forma aditiva:** la verificación de historias con axe entra
    en el trabajo `frontend verify` o en un trabajo nuevo, sin modificar los pasos ni los trabajos
    existentes de `.github/workflows/ci.yml`.
12. **Documentación del stack:**
    - Fila nueva de Storybook en la tabla de stack de `CLAUDE.md` y en `docs/01-arquitectura.md`
      §3.3 y §3.4.
    - **ADR-0026 nuevo, en estado Propuesto:** Storybook como catálogo de `packages/ui` y banco de
      la verificación de accesibilidad. La convención de `docs/adr/README.md` lo exige porque la
      decisión introduce una restricción permanente («todo componente lleva historia verificada»)
      y se eligió entre alternativas razonables (solo pruebas de componente con axe, Ladle).
    - Alinear `docs/ui-ux/02` §5.1: hoy describe `value: { amountMinor, currency }` en unidades
      menores, lo que contradice el contrato vigente (`Money.amount`, cadena decimal ya
      redondeada; ADR-0011 §Presentación). Gana el documento de arquitectura; el documento de UI se
      corrige en este cambio.
    - Nota fechada en `docs/09-roadmap-y-fases.md` sobre el entregable 10 y el estado del
      criterio de salida 8.

### Fuera de alcance

- **13b `admin-web-shell-and-staff-login`:** `apps/admin-web`, su enrutado, el inicio de sesión
  real con MFA, la semilla de cuenta de personal y CORS. Cierra el criterio de salida 8 con su
  primera pantalla.
- **13c `clickable-prototype-screens`:** pantallas de prototipo etiquetadas con MSW. **Sigue
  pendiente de autorización del propietario** bajo la regla dura de SDD; esta propuesta no la
  presupone.
- **`apps/portal-web`** en cualquier forma (ver pregunta 1).
- El resto del inventario P0: `FormErrorSummary`, `CurrencyInput`, `DateInput`, `Select`,
  `Combobox`, `SearchInput`, `Tooltip`, `Popover`, `DropdownMenu`, `Breadcrumb`, `Pagination`,
  `Alert`, `Banner`, `Toast`, `DateDisplay`, `StudentCard`, todos los organismos y todas las
  plantillas. Llegan con la pantalla que los necesite, según el criterio de promoción de `02` §3.
- **Regresión visual con Playwright** (`docs/06-estrategia-de-testing.md` §10): exige un contenedor
  Linux fijo, que llega con el cambio 11.
- **axe en extremo a extremo con Playwright** sobre pantallas: llega con la primera pantalla y con
  la infraestructura de extremo a extremo del cambio 11.
- **Marca definitiva** (logotipo, color y tipografía finales): decisión pendiente del cliente; este
  cambio solo la deja reemplazable.
- **Cualquier archivo de `apps/api`** y cualquier cambio no aditivo en `ci.yml`.
- **Publicación de Storybook** fuera de la integración continua.

## Capacidades

### Nuevas

- `design-system`: comportamiento observable de `packages/ui`. Tokens en tres niveles con modo
  claro y oscuro; formato de importes de `MoneyAmount` sin aritmética en el navegador; estado
  financiero con tres canales en `StatusBadge`; acción obligatoria en `EmptyState` y reintento
  obligatorio en `ErrorState`; textos solo desde catálogos `es-HN`; y ausencia de violaciones
  críticas o serias de axe en todas las historias.

### Modificadas

- `build-integrity`: nuevos requisitos verificables. Una violación de las reglas de capas de
  `packages/ui` rompe la construcción; un literal de texto en JSX, un `toFixed` o `toLocaleString`
  fuera de las utilidades y una clave de catálogo inexistente rompen el análisis estático; una
  violación crítica o seria de axe en una historia, o un componente sin historia, rompe la
  integración continua.

## Entregable y criterio de salida de F0

- **Cierra el entregable 10.**
- **El criterio de salida 8 queda abierto**, con nota fechada que lo asigna a 13b (ver Intención).

## Enfoque

De adentro hacia afuera, igual que los cambios 1 y 3:

1. **Sondas, antes de cualquier otra tarea.** (a) Compatibilidad de Storybook y su complemento de
   accesibilidad con React 19, Vite, TypeScript 6.0 y Vitest 5, que son las versiones fijadas en el
   catálogo de `pnpm-workspace.yaml`. (b) El mecanismo para ejecutar axe sobre las historias en la
   integración continua, sea en navegador real o en jsdom, y qué exige en CI. (c) Que
   `Intl.NumberFormat` formatee la cadena decimal del contrato sin pasar por `number`, en Node 24 y
   en los navegadores objetivo. (d) Que Tailwind CSS v4 y el CLI de shadcn/ui funcionen con
   `strictDepBuilds` sin aprobar guiones de instalación innecesarios. Si alguna falla, el cambio se
   detiene y se informa.
2. **Paquete, tokens y tema:** `packages/ui`, Tailwind v4, tokens en tres niveles, modo oscuro e
   Inter, con una prueba que demuestra que ningún componente consume un primitivo.
3. **Internacionalización y análisis estático:** i18next, catálogo `es-HN` y las reglas de
   ADR-0011, cada una con su violación deliberada.
4. **Storybook y la puerta de axe**, primero en rojo con una historia deliberadamente inaccesible.
5. **Componentes**, con prueba primero: átomos, moléculas genéricas y, al final, las de dominio.
6. **Reglas de capas** de `packages/ui`, cada una con su violación deliberada.
7. **Integración continua y documentación**, cuando todo pasa en local.

Previsión de tamaño: entre nueve y diez tareas, por debajo del límite de quince. El código que
genera el CLI de shadcn/ui se adapta y se revisa, así que cuenta como línea de autor; si el PR
supera las ochocientas líneas efectivas, se divide en dos cortes: fundamentos (tokens, i18next,
Storybook, puerta de axe y reglas) y componentes. Las versiones exactas y el mecanismo concreto de
axe en CI **se fijan en la fase de diseño**.

## Áreas afectadas

| Área | Impacto | Descripción |
|---|---|---|
| `packages/ui/` | Nueva | Tokens, átomos, moléculas, dominio, catálogo `es-HN`, historias y pruebas |
| `packages/config/` | Modificada | Configuración compartida de Tailwind y reglas de ESLint de ADR-0011 |
| `.dependency-cruiser.cjs`, `tooling/dependency-fixtures/` | Modificada | Reglas de capas de `packages/ui` y sus violaciones deliberadas |
| `pnpm-workspace.yaml`, `pnpm-lock.yaml`, `turbo.json`, `package.json` | Modificada | Catálogo de versiones, tareas de Storybook y de verificación de historias |
| `.github/workflows/ci.yml` | Modificada, solo aditiva | Verificación de historias con axe |
| `CLAUDE.md`, `docs/01-arquitectura.md` | Modificada | Storybook en la tabla de stack |
| `docs/adr/ADR-0026-*.md`, `docs/adr/README.md` | Nueva y modificada | ADR de Storybook en estado Propuesto e índice |
| `docs/ui-ux/02-sistema-de-diseno-y-componentes.md` | Modificada | `MoneyAmount` alineado con el contrato `Money` |
| `docs/09-roadmap-y-fases.md` | Modificada | Nota del entregable 10 y del criterio de salida 8 |
| `apps/api/` | Sin cambios | Fuera de alcance por decisión del propietario |

## Criterios de aceptación

- [ ] `pnpm install --frozen-lockfile` y `pnpm turbo run lint typecheck test build` terminan en
      verde en una máquina limpia, con `packages/ui` incluido.
- [ ] Ningún componente consume un token primitivo, y una violación deliberada lo demuestra.
- [ ] Cada componente renderiza correctamente en modo claro y oscuro, por preferencia del sistema y
      por `data-theme`.
- [ ] `MoneyAmount` formatea `{ amount: "3200.00", currency: "HNL" }` como importe con moneda y dos
      decimales mediante `Intl.NumberFormat`, muestra el cero como `0.00`, y su código no contiene
      aritmética, redondeo ni conversión a `number` del importe.
- [ ] `StatusBadge` comunica cada estado con color, icono y texto, incluido el modo `iconOnly` para
      lectores de pantalla.
- [ ] Un literal de texto en JSX, un `toFixed` fuera de las utilidades y una clave de catálogo
      inexistente rompen el análisis estático, cada uno con su violación deliberada.
- [ ] Cada regla de capas de `packages/ui` rechaza su violación deliberada.
- [ ] Una historia con una violación crítica o seria de axe rompe la verificación, y un componente
      sin historia también; las historias reales terminan en verde.
- [ ] La integración continua ejecuta la verificación de historias con axe, y los trabajos
      existentes quedan sin modificar.
- [ ] `CLAUDE.md` y `docs/01-arquitectura.md` listan Storybook, ADR-0026 existe en estado
      Propuesto, y `docs/ui-ux/02` §5.1 describe el contrato `Money` vigente.

## Riesgos

| Riesgo | Probabilidad | Mitigación |
|---|---|---|
| Storybook aún no es compatible con TypeScript 6.0 o Vitest 5, fijados en el catálogo | Media | Sonda (a) antes de cualquier tarea; si falla, se detiene y se informa, sin degradar versiones en silencio |
| axe en navegador real exige navegadores de Playwright en CI, que hoy llegan con el cambio 11 | Media | Sonda (b); la diferencia entre navegador real y jsdom se fija en diseño y se declara, nunca se oculta |
| `Intl.NumberFormat` con cadena decimal se comporta distinto entre motores | Baja | Sonda (c) y pruebas de valores límite en `MoneyAmount` |
| El CLI de shadcn/ui o una dependencia exige guiones de instalación bajo `strictDepBuilds` | Media | Sonda (d); cada aprobación en `allowBuilds` lleva su motivo en el PR |
| El escaneo de pnpm reporta vulnerabilidades altas en las dependencias de Storybook | Media | Se triagean una por una con `overrides` documentados; el bloqueo nunca se desactiva |
| Colisión del número de ADR con la cadena del backend, que trabaja en paralelo | Media | ADR-0025 ya está reservado por `background-jobs-with-db-scheduler` (decisión D-N3, funciones de purga); este cambio usa ADR-0026 y lo reconcilia contra `main` antes de fusionar |
| Conflictos en `pnpm-lock.yaml`, `pnpm-workspace.yaml` o `ci.yml` con la rama del backend | Media | Cambios solo aditivos; el archivo de bloqueo se regenera tras rebasar, nunca se fusiona a mano |
| El PR supera las ochocientas líneas efectivas | Alta | División prevista en dos cortes (ver Enfoque) |
| La marca provisional se asienta como definitiva | Media | Marca concentrada en primitivos; ADR-0026 y la nota del roadmap la declaran provisional |

## Plan de reversión

No hay base de datos, esquema, datos, endpoints ni despliegue. Revertir es descartar la rama, o
revertir el PR, que solo añade `packages/ui` y un paso nuevo de CI. Ninguna aplicación consume
todavía `packages/ui`, así que retirarlo no rompe nada. Si se revierte después de fusionar, el
ADR-0026 se marca como Rechazado, nunca se borra.

## Dependencias

- Cambio 3, archivado: espacio de trabajo pnpm, `packages/config`, `packages/contracts` con el
  esquema `Money` y `dependency-cruiser`.
- Node 24 y pnpm según `.nvmrc` y `packageManager`.
- Aprobación del propietario de este documento y, después, de ADR-0026.

## Preguntas de propuesta

1. **¿Quién crea el esqueleto de `apps/portal-web`?** El 2026-09-30 el propietario asignó al
   cambio 13 el esqueleto de ambas aplicaciones. Con la división, 13b crea `apps/admin-web`, pero
   ningún cambio recoge el portal. **Recomendación:** que no lo cree 13a, porque no lo necesita
   para verificar accesibilidad (las historias de Storybook cumplen esa función), y que se asigne
   al primer cambio que entregue una pantalla del portal.
