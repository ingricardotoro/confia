# ADR-0026: Storybook como catálogo de `packages/ui` y banco de la verificación de accesibilidad

- **Estado:** Propuesto
- **Fecha:** 2026-10-30
- **Decisores:** Propietario del producto y arquitecto
- **Contexto técnico:** `packages/ui` (catálogo de componentes y puerta de accesibilidad), trabajos de
  integración continua del frontend. Concreta `docs/ui-ux/02` §8 y `docs/ui-ux/03` §9 y §11, y amplía
  la tabla de stack de `CLAUDE.md` y de `docs/01-arquitectura.md` §3.3 y §3.4. Cambio SDD
  `design-system-foundations-and-a11y`.

## Contexto y problema

`docs/ui-ux/02` §8 exige un catálogo visual de componentes como sustituto de la revisión de diseño, y
`docs/ui-ux/03` §9 y §11 exigen que axe-core corra sobre cada componente y que una violación crítica o
seria rompa la construcción, mientras que una moderada o menor «advierte y se registra». Ninguno de los
dos documentos nombra la herramienta, y Storybook no figura en la tabla de stack. Tres hechos obligan a
decidir ahora, antes de que existan pantallas:

- Sin pantallas, la puerta de accesibilidad no tiene dónde ejecutarse salvo en los propios componentes
  del sistema de diseño. Si los componentes entran sin historia, la puerta nace vacía y no protege nada.
- jsdom no calcula contraste ni visibilidad: axe marca esas reglas como incompletas, y una puerta en
  jsdom pasaría en verde justo en lo que más importa a un sistema de tokens.
- El complemento de accesibilidad de Storybook falla ante cualquier violación, sin umbral por impacto,
  que es lo que `03` §11 no permite.

## Factores de decisión

| Factor | Peso | Justificación |
|---|---|---|
| Bloqueo real ante crítica y seria, registro de moderada y menor | Muy alto | Es el texto de `03` §11; una puerta que bloquea todo se desactiva, una que no bloquea nada no protege |
| Sin conjunto vacío (ADR-0018) | Muy alto | Una puerta que evalúa cero historias pasa siempre; debe fallar |
| Contraste evaluado en un navegador real, en dos temas | Alto | El contraste es la regla que más se rompe con tokens y la que jsdom no puede medir |
| Catálogo visual como sustituto de revisión de diseño | Alto | Un solo desarrollador: nadie más revisa el diseño de un componente antes de usarlo |
| Costo en integración continua | Medio | Un navegador se descarga y se ejecuta; debe vivir en un trabajo propio y medirse |
| Compatibilidad con el catálogo de versiones | Medio | Una sola versión de Vite, Vitest y Playwright en el árbol |

## Opciones consideradas

### Opción A: Storybook con historias portables y puerta propia en Chromium (elegida)

Un proyecto de Vitest en modo navegador con Playwright importa todas las historias, las compone con
`composeStories`, y por cada historia y cada tema (`light`, `dark`) renderiza, ejecuta `play`, corre
axe con las etiquetas `wcag2a`, `wcag2aa`, `wcag21a`, `wcag21aa` y `wcag22aa`, y separa el resultado con
una política propia (`src/a11y/policy.ts`). **Ventajas:** contraste real con el CSS real; umbral por
impacto; la misma política alimenta la puerta y el panel de Storybook; las historias son a la vez
catálogo y banco de pruebas. **Desventajas:** una política propia que mantener y un navegador en CI.

### Opción B: pruebas de componente con axe en jsdom, sin Storybook

Sin dependencias nuevas y sin navegador. **Desventajas:** `color-contrast` queda incompleta, así que la
puerta no cubre contraste; y se pierde el catálogo visual que `02` §8 exige.

### Opción C: Ladle

Más liviano que Storybook. **Desventajas:** sin complemento de accesibilidad equivalente ni historias
portables que reutilizar en Vitest.

### Opción D: `addon-vitest` de Storybook con `test: "error"`

Integración oficial. **Desventajas:** falla ante cualquier violación, sin umbral por impacto, y acopla
la puerta a que el complemento siga el ritmo de versiones de Vitest.

### Opción E: `test-runner` de Storybook

Jest más Playwright contra un Storybook servido. **Desventajas:** un servidor más que arrancar, un
segundo ejecutor de pruebas junto a Vitest, y el mismo problema de umbral que la opción D.

## Decisión

**Se adopta la opción A.** Storybook 10 con `@storybook/react-vite` es el catálogo de `packages/ui`, y
la verificación de accesibilidad es un proyecto de Vitest (`a11y`) en Chromium, separado de las pruebas
unitarias.

**Restricción permanente.** Todo componente de `packages/ui` lleva una historia con un export `Default`
y una prueba propia, y toda historia pasa por axe en los dos temas. Crítica y seria bloquean; un
resultado incompleto de `color-contrast` bloquea también, porque equivale a no haber medido; moderada y
menor se escriben en `reports/a11y/report.json`, que CI sube como artefacto. Un impacto que la política
no reconoce bloquea. La puerta falla si descubre cero historias. Storybook solo admite CSF en archivos
`.stories.tsx`: MDX es código ejecutable y no existe en el paquete. Los fixtures inaccesibles viven en
`src/a11y/__fixtures__/`, fuera de todos los patrones de descubrimiento, de modo que nunca aparecen en
el catálogo publicado.

Chromium entra en integración continua antes del cambio 11, solo en un trabajo nuevo y aditivo
(`design system accessibility`); los trabajos existentes no cambian y `pnpm turbo run lint typecheck test
build` sigue en verde en una máquina sin navegador, porque `test:a11y` es una tarea aparte de `test`.

## Consecuencias

**Positivas:**

- La puerta mide contraste real en los dos temas desde el primer componente, y falla cerrada: sin
  historias, sin tema oscuro o ante una medición incompleta de contraste no hay verde.
- El catálogo visual existe antes que las pantallas, y es el sustituto de revisión de diseño.
- El cambio 11 reutiliza `playwright` del catálogo; no hay dos versiones.

**Negativas y costos aceptados:**

- Dependencias de Storybook, Playwright y `@vitest/browser-playwright` en el árbol, con su superficie de
  auditoría (`pnpm audit --audit-level high` sigue siendo la puerta).
- Un trabajo de CI más, con descarga de Chromium en cada ejecución (sin caché de navegadores por ahora;
  se mide primero).
- Una política propia (`policy.ts`) y una comprobación de historias que mantener al ritmo de Storybook.

**Riesgos y mitigaciones:**

| Riesgo | Mitigación |
|---|---|
| Una historia se salta la puerta con `a11y: { disable: true }` o `test: "off"` | El escáner de supresiones rechaza la supresión sin cita a un ADR |
| La puerta se vuelve vacía por un patrón que ya no coincide | `assertStoriesDiscovered` falla con cero historias; hay una prueba con el conjunto vacío |
| El tema oscuro deja de evaluarse | Una historia deliberada que solo falla en oscuro debe ser rechazada |
| Un componente nuevo entra sin historia o sin prueba | `story-coverage.test.ts` corre en el trabajo `frontend verify` y nombra el componente |
| Los fixtures inaccesibles aparecen en el catálogo publicado | El patrón de Storybook se limita a `atoms/`, `molecules/` y `domain/`, igual que el de la puerta |

## Cumplimiento y verificación

1. **`gate.a11y.test.tsx`.** Pasa los fixtures por la misma función que las historias reales y exige que
   rechace `image-alt`, `button-name` y `color-contrast`, que rechace el fixture que solo falla en oscuro
   y que solo informe el de impacto moderado.
2. **`stories.a11y.test.tsx`.** Recorre todas las historias en los dos temas, escribe el informe y falla
   con cero historias.
3. **`story-coverage.test.ts`.** Cero componentes sin historia `Default` y cero sin prueba, sobre un
   conjunto que no está vacío, con un árbol de fixtures que contiene un infractor de cada tipo.
4. **`policy.test.ts` y `storybook-config.test.ts`.** La política y la ausencia de MDX.
5. **`ci-workflow.test.ts`.** El trabajo nuevo existe con sus acciones fijadas por SHA y sus pasos, y los
   trabajos existentes no cambian.
6. **`suppression-citations.test.ts`.** Un `a11y: { disable: true }` o un `test: "off"` sin cita a un ADR que
   exista rompe la construcción.

## Reversión

Si se revierte tras fusionar, este ADR pasa a **Rechazado**; no se borra, y su número no se reutiliza. La
reversión retira el trabajo de CI, la tarea `test:a11y` y las dependencias de Storybook; el sistema de
diseño queda sin catálogo ni puerta de accesibilidad, lo que `docs/ui-ux/03` §11 no permite, así que la
reversión exige un ADR sucesor.
