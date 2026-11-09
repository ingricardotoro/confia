# ADR-0028: axe sobre rutas renderizadas

- **Estado:** Propuesto
- **Fecha:** 2026-11-07
- **Decisores:** Propietario del producto y arquitecto
- **Contexto técnico:** `apps/admin-web` (puerta de accesibilidad sobre pantallas), `packages/config`
  (política de accesibilidad compartida), `packages/ui` (puerta de historias), trabajos de integración
  continua del frontend. Concreta `docs/ui-ux/03` §9 y §11 para las pantallas, como ADR-0026 lo hizo para
  los componentes. Cambio SDD `admin-web-foundations-and-session`.

## Contexto y problema

ADR-0026 puso la puerta de axe en las historias de `packages/ui`, en Chromium y en los dos temas. Esa
puerta prueba componentes aislados; no prueba una pantalla ensamblada, con su enrutador, sus cookies, su
foco inicial y el orden real de sus regiones. `docs/ui-ux/03` §9 pide que cada pantalla se verifique con
axe, y con `apps/admin-web` ya existen pantallas. Tres hechos obligan a decidir ahora:

- Un proyecto de Vitest en modo navegador, como el de ADR-0026, no ejecuta el enrutador de la aplicación ni
  el ciclo de cookies de la sesión, de modo que no puede evaluar la pantalla tal como la recibe una persona.
- Si la política de bloqueo (etiquetas de WCAG, impactos que fallan, resultados incompletos de contraste)
  vive en `packages/ui`, la puerta de rutas tendría que importarla desde un paquete de presentación o
  copiarla, y dos copias pueden discrepar sobre qué bloquea.
- Una puerta que recorre una lista de rutas pasa en verde si la lista queda vacía.

## Factores de decisión

| Factor | Peso | Justificación |
|---|---|---|
| Evaluar la pantalla real, con enrutador, cookies y foco | Muy alto | Es lo que `03` §9 pide y lo que un proyecto de componentes no puede ver |
| Una sola política para las dos puertas | Muy alto | Dos criterios de bloqueo producen veredictos distintos para el mismo defecto |
| Un conjunto vacío no aprueba | Alto | Es la regla de ADR-0018 y el modo de fallo más silencioso de una puerta |
| Sin dependencias fuera del catálogo del repositorio | Alto | `minimumReleaseAge` y el desarrollador único limitan la superficie nueva |
| Mismo veredicto en local y en CI | Medio | Una puerta que solo corre en CI se descubre tarde |

## Opciones consideradas

### Opción A: ampliar el proyecto de Vitest en modo navegador
Reutiliza la infraestructura de ADR-0026. No ejecuta el enrutador de la aplicación ni las cookies, así que
evalúa montajes de componentes y no pantallas. Descartada.

### Opción B: Playwright con `@axe-core/playwright` sobre `vite preview`
Playwright ya es la herramienta de los recorridos de extremo a extremo y carga la aplicación construida con
su enrutador. `@axe-core/playwright` ejecuta el mismo `axe-core` sobre la página renderizada y permite
emular la preferencia de tema del sistema. Es la opción elegida.

### Opción C: axe en jsdom
jsdom no calcula contraste ni visibilidad, por las razones de ADR-0026. Descartada.

## Decisión

Se elige la opción B, con cuatro compromisos:

1. **Política única.** Las etiquetas, los impactos y el reparto de resultados viven en
   `@confia/config/a11y` (`packages/config/src/a11y.ts`) y dependen solo del tipo de `axe-core`. La puerta
   de historias, el panel de Storybook y la puerta de rutas importan ese mismo módulo. `packages/ui` no
   conserva copia ni la reexporta desde su punto de entrada público.
2. **Matriz fija.** La puerta evalúa una matriz de pantalla, estado y tema de `apps/admin-web` renderizada
   con `vite preview`. Cada combinación se evalúa en tema claro y en tema oscuro, y solo después de que el
   contenido esperado de su estado sea visible. La matriz mínima de esta parte cubre cada pantalla que
   existe al terminarla, con 6 estados por 2 temas, es decir, doce evaluaciones:

   | Pantalla | Estados mínimos |
   |---|---|
   | Arranque | restaurando; error de red con reintento |
   | Página autenticada | cargando; confirmación; error de lectura |
   | Ruta no encontrada | única |

   Las pantallas de acceso entran a la matriz con el cambio `admin-web-staff-sign-in`.
3. **Falla con cero rutas.** Una matriz vacía o una ejecución que evalúa cero rutas rompe la puerta en
   lugar de aprobar (ADR-0018). También falla ante una violación crítica o seria, ante una ruta
   deliberadamente inaccesible que solo existe en pruebas, y por tiempo de espera si el contenido esperado
   nunca aparece. Una violación moderada o menor se reporta sin fallar.
4. **Informe como artefacto.** El informe de violaciones se conserva como artefacto de la ejecución, y la
   misma puerta corre en local con el mismo veredicto.

## Consecuencias

**Positivas:** las pantallas se verifican tal como las recibe una persona; hay una sola definición de qué
bloquea; la puerta de rutas no puede aprobar un conjunto vacío.

**Negativas y costos aceptados:** una dependencia nueva de desarrollo (`@axe-core/playwright`, ya fijada en
el catálogo) y un trabajo más de integración continua; cada pantalla nueva exige ampliar la matriz.
`packages/config` pasa a exportar código, no solo configuración, y declara `axe-core` como dependencia
solo de tipo.

**Riesgos y mitigaciones:**

| Riesgo | Mitigación |
|---|---|
| Se analiza un esqueleto de carga y se obtiene un falso verde | Cada combinación espera su contenido esperado; una prueba con un estado que nunca llega exige el fallo por tiempo de espera |
| La matriz se vacía sin que nadie lo note | La puerta falla con cero rutas evaluadas y hay una prueba con la matriz vacía |
| El simulador de la API oculta un defecto de la pila real | Ver el límite de abajo |
| La política se copia de nuevo en `packages/ui` | Una prueba falla si `packages/ui` define un nombre de la política o conserva `policy.ts` |

**Límite reconocido.** El simulador no es una prueba de la pila real: la API simulada que alimenta la matriz
fija las respuestas que la pantalla recibe, pero no ejercita el backend, la base de datos ni el proxy de
producción. Este ADR no afirma que la puerta pruebe la integración con el servidor; eso queda para las
pruebas de extremo a extremo contra la pila real y no se da por cubierto aquí.

## Cumplimiento y verificación

1. **`a11y.test.ts` en `packages/config`.** Las etiquetas, el reparto por impacto, el impacto desconocido,
   el resultado incompleto de contraste, la importación de solo tipo, la ausencia de copia en
   `packages/ui` y de la política en su punto de entrada.
2. **`gate.a11y.test.tsx` en `packages/ui`.** Sin cambios: la puerta de historias sigue en verde con las
   historias reales y falla con cada una de las deliberadamente inaccesibles.
3. **La puerta de rutas** (tarea 12 del cambio). Matriz, ambos temas, cero rutas, ruta inaccesible y
   tiempo de espera del contenido esperado; las dos puertas importan el mismo módulo de `@confia/config/a11y`.
4. **Revisión.** Este ADR queda en Propuesto hasta que el propietario lo apruebe.

## Reversión

Si se revierte tras fusionar, este ADR pasa a **Rechazado**; no se borra y su número no se reutiliza. La
puerta de rutas se retira y la verificación de pantallas vuelve a depender de la revisión manual, lo que
`docs/ui-ux/03` §9 no permite, así que la reversión exige un ADR sucesor. La política compartida puede
permanecer en `@confia/config`, porque la puerta de historias de ADR-0026 también la usa.
