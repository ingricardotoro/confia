# Propuesta: shell de `apps/admin-web` y vida completa de la sesión (cambio 13b-2 de F0)

- **Cambio:** `admin-web-shell-and-session-lifecycle`
- **Rama:** `change/admin-web-shell-and-session-lifecycle`
- **Fase del roadmap:** F0, cambio 13b-2 (`docs/09-roadmap-y-fases.md`:296-301: «el shell, la vida completa de la
  sesión y el aviso de inactividad»). El criterio de salida 8 ya está cerrado (nota fechada 2026-11-09); este cambio
  **no puede desmarcarlo** y debe mantener la puerta en verde sobre las pantallas nuevas.
- **Predecesores:** `admin-web-foundations-and-session` y `admin-web-staff-sign-in` (13b-1). Este cambio cumple la
  columna 13b-2 de su «Línea de la sesión» (`admin-web-foundations-and-session/proposal.md`:99-125) y las
  exclusiones con dueño 13b-2 (`proposal.md`:222-227).
- **Exploración:** `openspec/changes/admin-web-shell-and-session-lifecycle/exploration.md` (2026-11-10).
- **Estado:** propuesta, pendiente de aprobación del propietario (`openspec/config.yaml`, `rules.proposal`).

## Decisiones del propietario aplicadas (2026-11-10)

Son vinculantes y esta propuesta no las reabre.

| Id | Decisión | Consecuencia en esta propuesta |
|---|---|---|
| **D11** | Un solo cambio, de 11 a 13 tareas y de 5 a 7 PR apilados; se divide solo si `sdd-tasks` supera 15 tareas | Enfoque A de la exploración (§5.1). La salida a dos cambios queda como contingencia de la fase de tareas |
| **D1** | El menú de usuario muestra solo una etiqueta genérica y «Cerrar sesión»; sin endpoint de perfil | No se lee identidad. Nombre y rol llegan cuando el backend los exponga |
| **D3** | Solo barra superior: `Topbar`, `UserMenu`, `PageHeader` y enlace de salto | Sin `Sidebar`, sin migas y sin árbol de navegación; llegan con el primer módulo real |
| **D7** | Selector de tema de tres estados (sistema, claro, oscuro), solo en la aplicación autenticada, persistido en `localStorage` y aplicado con `data-theme` | Las pantallas de acceso no cambian: siguen el tema del sistema |
| **D2** | Panel vacío de inicio: mensaje de bienvenida sin acción | No se usa `EmptyState` con acción primaria |

## Intención

**Problema.** Tras iniciar sesión, el personal llega a una página mínima dentro de `ScreenLayout`, que es el
layout de las pantallas de acceso. La sesión no observa el paso del tiempo: el almacén descarta `idleExpiresAt` y
`sessionExpiresAt`, no hay detección de actividad, ni aviso de inactividad, ni aviso de vencimiento absoluto, y el
cierre de sesión en una pestaña no llega a las demás. La brecha quedó aceptada hasta 13b-2
(`proposal.md`:122-125 de 13b-1): una sesión vencida solo se descubre con la siguiente acción, y una persona que
trabaja sin tocar el servidor pierde lo que escribía sin aviso.

**Por qué ahora.** Toda pantalla de F1 necesita un shell autenticado donde vivir, y un sistema que maneja dinero en
equipos compartidos necesita que la sesión avise antes de vencer y que su cierre sea coherente entre pestañas.

**Cómo se ve el éxito.** Al iniciar sesión, la persona ve una barra superior con enlace de salto, su menú (etiqueta
genérica, selector de tema y «Cerrar sesión») y un panel de bienvenida. Mientras trabaja, la sesión se extiende sola;
cinco minutos antes de vencer por inactividad aparece un diálogo modal anunciado con prioridad `assertive` que permite
continuar o cerrar sesión; al acercarse el vencimiento absoluto recibe un aviso que no se puede extender. Cerrar sesión
en una pestaña cierra todas. Ningún temporizador hace peticiones de refresco, ningún secreto sale de la memoria, y la
puerta de axe sigue en verde sobre la matriz ampliada.

## Alcance

### Dentro de alcance

**Núcleo de sesión**

1. Metadatos de plazos no secretos (`idleExpiresAt`, `sessionExpiresAt`) conservados fuera del almacén de tokens; la
   regla `admin-token-store-is-private` no cambia.
2. Planificador puro con reloj y temporizadores inyectados, que calcula desde plazos absolutos y se reevalúa en
   `visibilitychange`, más la corrección del desfase de reloj a partir de la cabecera `Date` (requiere que
   `shared/api/http.ts` la exponga).
3. Detector de actividad con cadencia mínima que pide el refresco **siempre** a través del coordinador existente
   (promesa compartida y Web Lock).
4. Aviso de vencimiento absoluto, sin opción de extender.
5. `BroadcastChannel` sin secretos para el cierre de sesión y la propagación de plazos, con mensaje cerrado y
   validado por esquema.
6. El invariante «sin temporizador» se precisa a «ningún temporizador ni consulta de fondo pide un refresco».

**Componentes de `packages/ui`**

7. `Dialog`/`AlertDialog` (implementación según la sonda de CSP), menú desplegable, `Topbar`, `UserMenu`,
   `PageHeader` y enlace de salto, con historias en los dos temas, pruebas y claves es-HN.

**Shell y tema en `apps/admin-web`**

8. Layout de ruta protegida con su propio `main` y foco al `h1`; `ScreenLayout` queda solo para acceso.
9. Panel de inicio con mensaje de bienvenida sin acción (D2), que reemplaza la página mínima de 13b-1.
10. Menú de usuario con etiqueta genérica, selector de tema y «Cerrar sesión» (D1); el botón de la página mínima
    desaparece.
11. Selector de tema de tres estados con `data-theme` y `localStorage`, aplicado al arrancar, solo en la parte
    autenticada (D7).
12. Aviso de inactividad: diálogo modal, región `assertive`, «Continuar» y «Cerrar sesión», con foco inicial
    deliberado y retorno del foco al origen.
13. Claves es-HN de shell, tema y avisos en el catálogo `admin`, con trato de «usted».

**Pruebas, puerta y documentos**

14. Modelo de inactividad y de vencimiento absoluto en la API simulada, con token de acceso de 10 minutos como en
    `docs/03-seguridad.md`:515.
15. Matriz de la puerta de axe ampliada: shell, diálogo abierto, menú abierto, aviso de vencimiento absoluto y tema
    explícito claro y oscuro, cada uno con su fixture de violación deliberada.
16. Recorridos e2e del aviso, de la extensión por actividad, de dos pestañas y del cierre sincronizado.
17. Inversión de las pruebas de ausencia (sección «Pruebas y escenarios que cambian de signo»).
18. Notas fechadas en `docs/09`, `docs/ui-ux/04` (shell solo con barra superior, política de actividad) y
    `docs/ui-ux/01` (selector de tema); `docs-notes.test.ts` las verifica.

### Fuera de alcance, con dueño nombrado

| Exclusión | Dueño |
|---|---|
| `Sidebar`, migas y árbol de navegación (D3) | Primer cambio que entregue un módulo real |
| Perfil, nombre y rol en el menú (D1, P4) | Backend o 8b, cuando exista un endpoint; luego un cambio de frontend |
| «Mis sesiones activas»: lista y revocación (D10, supuesto) | Backend o 8b; hoy no hay endpoint |
| Parámetro `redirect` y regreso a ruta profunda (D9, supuesto) | Primer cambio con más de una ruta protegida; AW19 se mantiene |
| Selector de tema en las pantallas de acceso (D7) | Sin dueño: decisión del propietario de no hacerlo |
| Tiempos de política desde el contrato de API (D4, supuesto) | Backend, cuando el contrato los exponga |
| MFA voluntaria con sesión completa | 8b |
| Búsqueda global, año lectivo, campana, indicador de caja y atajos | Cada módulo |
| Pantallas de módulos y filtro por rol | Cambios de módulo y backend |
| nginx, *nonce* de CSP, regresión visual y e2e con pila real | Cambio 11 |
| `apps/api` | Sin cambios |

## Capacidades

> Contrato con `/sdd-spec`. `openspec/specs/` publica doce capacidades; `admin-web-foundation` y `staff-sign-in`
> existen hoy solo en las carpetas de 13b-1 (`admin-web-foundations-and-session/specs/`,
> `admin-web-staff-sign-in/specs/`), que todavía no están archivadas.

### Nuevas

- **`admin-web-shell`:** layout autenticado, `Topbar`, `UserMenu` con etiqueta genérica y cierre de sesión,
  `PageHeader`, enlace de salto, panel de bienvenida, selector de tema de tres estados con persistencia y su
  ausencia en las pantallas de acceso.

### Modificadas

- **`staff-sign-in`:** vida completa de la sesión: plazos, actividad con cadencia, aviso de inactividad, aviso de
  vencimiento absoluto, desfase de reloj, sincronización entre pestañas e invariante precisado de temporizadores.
  Se invierten SS19 (precisado), SS124, SS125 y SS126.
- **`admin-web-foundation`:** AW05 se precisa (el modo «sistema» sigue siendo el predeterminado), AW06 se invierte,
  AW59 se actualiza al nuevo árbol de rutas, AW19 se conserva con nota de que sigue vigente.
- **`design-system`:** el conjunto exacto de exportaciones crece (DS88) y DS89 se invierte.
- **`build-integrity`:** la matriz de la puerta crece con sus fixtures de violación y sigue fallando con cero rutas.

`identity` y `web-edge` no cambian: ningún comportamiento del backend se modifica.

## Enfoque

1. **Primero la sonda, luego el diseño (D12, supuesto).** Una sonda local y desechable en Chromium, con la CSP de
   `preview`, comprueba si Radix `Dialog` y `react-remove-scroll` producen `securitypolicyviolation`. Si pasa, se usa
   Radix; si falla, `<dialog>` nativo con `showModal()`, sin relajar la política. El resultado se registra en
   `design.md`; no es PR ni tarea.
2. **Orden de bloques (enfoque A):** sonda, núcleo de sesión, `ui`, shell, tema, avisos en la interfaz, e2e y
   documentos. El aviso necesita `Dialog` y el cierre pasa al menú, por eso es un solo cambio.
3. **El servidor manda en los plazos.** El aviso se calcula con los plazos guardados de la última respuesta, nunca
   releyendo `GET /sessions/current`, para no extender la sesión sin querer.
4. **Un solo camino de refresco.** Actividad, «Continuar» y peticiones de la persona pasan por el coordinador con
   Web Lock; ningún temporizador pide un refresco.
5. **Las pestañas comparten eventos, nunca secretos.** El canal transmite tipo de evento y plazos, validados por
   esquema; jamás tokens.
6. **El tema es preferencia, no secreto.** La prueba de secretos en almacenamiento se acota para que siga detectando
   tokens y admita la clave de preferencia de tema.
7. **TDD estricto.** Cada comportamiento con prueba ejecutable se escribe primero en rojo; el planificador puro se
   prueba con reloj falso, y los recorridos con `page.clock`.

## Áreas afectadas

| Área | Cambio |
|---|---|
| `apps/admin-web/src/features/identity/session/` | Plazos, planificador, actividad, canal entre pestañas, aviso absoluto |
| `apps/admin-web/src/shared/api/http.ts` | Exponer la cabecera `Date` para el desfase de reloj |
| `apps/admin-web/src/app/` (`routes.tsx`, `router.tsx`, layouts) | Layout protegido, panel de bienvenida, tema al arrancar; sale la página mínima |
| `apps/admin-web/src/app/docs-notes.test.ts` | Nuevas notas fechadas |
| `apps/admin-web/e2e/` (`mock-api/`, `specs/`, `support/a11y-gate.ts`) | Modelo de inactividad, recorridos nuevos, matriz ampliada |
| Catálogo `admin` (`admin.json`) | Claves de shell, tema y avisos |
| `packages/ui/` | `Dialog`/`AlertDialog`, menú, `Topbar`, `UserMenu`, `PageHeader`, enlace de salto; `index.ts`, `public-api.test.ts`, claves `ui` |
| `openspec/specs/` (al archivar) | `admin-web-shell` nueva; deltas de `staff-sign-in`, `admin-web-foundation`, `design-system`, `build-integrity` |
| Documentación | Notas fechadas en `docs/09`, `docs/ui-ux/04` y `docs/ui-ux/01`; ADR solo si la sonda obliga a apartarse de «sobre Radix» |
| `apps/api/` | Sin cambios |

## Pruebas y escenarios que cambian de signo

**Regla (ADR-0018):** se invierten o se precisan con nota fechada que cite este cambio; **no se borran**. Cada
inversión conserva el identificador.

| Id | Hoy | Con este cambio |
|---|---|---|
| AW05 | El tema sigue la preferencia del sistema | Se precisa: «sistema» es el estado predeterminado del selector |
| AW06 | No existe un control de tema | Se invierte: existe en la aplicación autenticada y no en las pantallas de acceso |
| AW19 | No hay parámetro `redirect` | **Se conserva** (D9, supuesto), con nota de que 13b-2 lo revisó y lo mantiene |
| AW59 | Las únicas rutas son las de 13b-1 | Se actualiza al árbol con layout protegido y panel de bienvenida |
| DS88 | Lista exacta de exportaciones | Crece con los componentes nuevos |
| DS89 | `Dialog` no existe (destino 13b-2) | Se invierte: existe y se importa del punto de entrada público |
| SS19 | Con el tiempo avanzado no hay peticiones | Se precisa: sin actividad no hay refrescos; la única petición por tiempo es el `DELETE` de mejor esfuerzo al terminar la cuenta atrás (D5, supuesto) |
| SS124 | La página mínima no detecta el vencimiento por sí sola | Se invierte: el aviso aparece y la sesión termina al vencer |
| SS125 | No existe aviso de inactividad ni `Dialog` | Se invierte |
| SS126 | No hay sincronización entre pestañas ni detección de actividad | Se invierte |
| `docs-notes.test.ts` | Verifica las notas de 13b-1 | Suma las notas de 13b-2 sin quitar las anteriores |

## Riesgos

| Riesgo | Probabilidad | Mitigación |
|---|---|---|
| **CSP y Radix:** `react-remove-scroll` u otra dependencia inyecta estilos y viola `style-src 'self'`; por AW12 cualquier violación rompe los recorridos | Media | Sonda antes del diseño; plan `<dialog>` nativo sin relajar la política |
| **Extensión silenciosa de la sesión:** releer `/current` o el refresco previo a una petición (menos de 60 s de token) mueve la inactividad sin que la persona actúe | Media | Aviso calculado solo con plazos guardados; prueba que afirma que abrir o mostrar el aviso no hace peticiones |
| **Puerta de accesibilidad ciega:** el shell, el diálogo, el menú y el tema explícito no entran en la matriz, y el criterio 8 queda en verde sin evaluarlos | Media | Matriz ampliada con fixture de violación por estado nuevo; un estado no evaluado rompe la prueba |
| Temporizadores retrasados en segundo plano o en suspensión | Alta | Cálculo con plazos absolutos y reevaluación en `visibilitychange` |
| Dos pestañas activas que rotan la cookie y revocan la familia fuera de la gracia (`docs/03`:518) | Media | El Web Lock y la promesa compartida siguen siendo el único camino; prueba con dos pestañas |
| Diálogo que aparece mientras se escribe y se acepta con Enter | Media | Foco inicial deliberado y retorno al origen, fijados en el diseño |
| Destello del tema antes del primer pintado, sin script en línea por la CSP | Media | Aplicar `data-theme` en `main.tsx` antes de montar React; se acepta a lo sumo un cuadro |
| La API simulada no es evidencia de la pila real | Alta | Brecha fechada del cambio 11; este cambio no declara probada la pila real |
| Tamaño: las pruebas multiplican el pronóstico de 1,5 a 2 veces | Alta | PR apilados con tope de 800 líneas; la decisión de entrega se toma en `sdd-tasks` |

## Plan de reversión

No hay entorno desplegado ni datos reales, y el cambio no toca `apps/api` ni migraciones.

1. Cada PR se revierte con su commit de fusión, en orden inverso de la cadena.
2. Revertir el shell restaura la página mínima de 13b-1 y su botón de cierre; las inversiones de AW06, AW59, SS124 a
   SS126 y DS89 vuelven a su texto anterior con nota fechada, sin borrar los escenarios.
3. Revertir el núcleo de sesión exige revertir antes los avisos en la interfaz que lo consumen.
4. **Si la reversión deja una pantalla autenticada sin evaluar por la puerta, el criterio 8 se desmarca en `docs/09`**
   con nota fechada en el mismo commit.
5. La clave de tema en `localStorage` es inocua tras una reversión: la app vuelve a seguir el tema del sistema.

## Dependencias

- **Consume:** el almacén, el coordinador de refresco, el cliente, la API simulada y la puerta sobre rutas de 13b-1;
  los esquemas de `@confia/contracts/admin` (`CurrentSessionResponse` y respuestas de refresco con sus tres
  vencimientos); `radix-ui`, ya dependencia de `packages/ui`.
- **Archivo previo:** `admin-web-foundations-and-session` y `admin-web-staff-sign-in` deben archivarse antes que este
  cambio, porque sus deltas se aplican sobre `admin-web-foundation` y `staff-sign-in`.
- Node 24, pnpm y Chromium. Sin Docker ni JDK.

## Criterios de éxito

- [ ] `pnpm turbo run lint typecheck test build --filter=!@confia/api` en verde.
- [ ] Tras iniciar sesión se ve barra superior, enlace de salto que lleva al `main`, menú con etiqueta genérica,
      selector de tema y «Cerrar sesión», y panel de bienvenida sin acción.
- [ ] Con actividad, la sesión se extiende con a lo sumo un refresco por cadencia y por pestaña; sin actividad, el
      aviso aparece cinco minutos antes del vencimiento por inactividad, anunciado con `assertive`.
- [ ] «Continuar» extiende la sesión; al terminar la cuenta atrás la sesión se cierra localmente y se envía el
      `DELETE` de mejor esfuerzo.
- [ ] El aviso de vencimiento absoluto aparece y no ofrece «Continuar».
- [ ] Cerrar sesión en una pestaña lleva a las otras al inicio de sesión; ningún mensaje del canal contiene un token.
- [ ] Ningún temporizador ni consulta de fondo pide un refresco; mostrar el aviso no hace peticiones.
- [ ] El tema elegido se conserva al recargar; las pantallas de acceso siguen el tema del sistema.
- [ ] La puerta de axe no reporta violaciones críticas ni serias en la matriz ampliada, en los dos temas, y cada
      estado nuevo tiene su fixture de violación que la hace fallar.
- [ ] Ninguna violación de CSP en los recorridos; el build no contiene scripts en línea.
- [ ] Las pruebas de ausencia listadas están invertidas o precisadas con nota fechada, sin borrar ninguna.
- [ ] Ningún archivo de `apps/api` cambió y ningún PR supera 800 líneas efectivas.

## Pronóstico de tamaño y entrega

Líneas escritas con pruebas, sin lockfile ni código generado. Ninguna cifra es una medición. Se parte de la tabla de
la exploración (§9) y se descuentan `Sidebar`, migas, navegación e identidad (D1, D3).

- **Pronóstico:** de 2 600 a 4 100 líneas.
- **Realista (el que manda):** de 4 000 a 6 900 líneas.

La preparación de entrega se hizo con `single-pr`, pero el pronóstico supera con claridad el presupuesto de 400
líneas y el tope de 800 de `docs/15` §3. **En la fase de tareas se necesitará una cadena de PR apilados hacia `main`
(convención del proyecto, `stacked-to-main`) o una excepción de tamaño explícita**; las pruebas nunca se separan del
código que prueban.

- `Decision needed before apply: Yes`
- `Chained PRs recommended: Yes`
- `400-line budget risk: High`

Objetivo de D11: de 11 a 13 tareas y de 5 a 7 PR. Si `sdd-tasks` supera 15 tareas (`openspec/config.yaml`), el
cambio se divide en dos (shell y tema; vida de la sesión), según el enfoque B de la exploración.

## Supuestos por confirmar

Valores predeterminados recomendados para los puntos abiertos de la exploración. Rigen el diseño mientras el
propietario no diga otra cosa; ninguno está decidido.

| Id | Supuesto | Compromiso |
|---|---|---|
| 5.1 | Enfoque A: un cambio en bloques ordenados | Coherente con D11, pero con PR grandes y poco margen bajo el tope de 15 tareas |
| R1 | Refresco disparado por actividad con cadencia mínima | La inactividad del servidor sigue a la de la persona, a cambio de más rotaciones y más pruebas |
| D12 | Sonda de CSP primero; Radix `Dialog` si pasa, `<dialog>` nativo si falla | Mantiene «sobre Radix» cuando es posible; el plan nativo obliga a cubrir a mano el retorno del foco |
| S1/D8 | `BroadcastChannel` sin secretos para cierre y plazos | Sincronización inmediata a cambio de una superficie nueva que debe validarse por esquema |
| D4 | Tiempos 30/25/5 min fijos en el cliente como constantes con nombre | Simple ahora; una desviación con el servidor exige un cambio de código hasta que el contrato los exponga |
| D5 | Al terminar la cuenta atrás: cierre local y `DELETE` de mejor esfuerzo | Cierra también en el servidor, pero obliga a precisar SS19 porque el tiempo dispara una petición |
| D6 | Aviso de vencimiento absoluto sin «Continuar» | Honesto con lo que el servidor permite, aunque la persona no puede evitar el cierre |
| D9 | `redirect` aplazado; AW19 se mantiene | Sin riesgo de redirección abierta, pero sin regreso a ruta profunda mientras haya una sola ruta |
| D10 | «Mis sesiones activas» fuera de alcance | No depende de backend, pero la línea de la sesión de 13b-1 queda con ese punto sin cumplir y con dueño nombrado |
| D7-u | El selector de tema vive dentro del `UserMenu` | Mantiene la barra superior mínima, a cambio de un paso más para cambiar el tema |
