# Exploración: `admin-web-shell-and-session-lifecycle` (13b-2 de F0)

Fecha 2026-11-10. Exploración de solo lectura sobre `C:\dev\confia-frontend`, rama `main`. No se ejecutó nada.

## 1. Qué debe entregar 13b-2

`docs/09-roadmap-y-fases.md:296-301` asigna a 13b-2 «el shell, la vida completa de la sesión y el aviso de inactividad». El contrato detallado es `openspec/changes/admin-web-foundations-and-session/proposal.md`.

- **Fuera de alcance de 13b-1 y con dueño 13b-2 (`proposal.md:222-227`):** `Topbar`, `Sidebar`, `PageHeader`, migas, enlace de salto, menú de usuario y navegación; aviso de inactividad con `Dialog`; selector visible de tema; identidad en el shell y panel vacío (P4, P5, pendientes).
- **«Línea de la sesión» (`proposal.md:99-125`), columna 13b-2:** actividad como disparador de refresco; aviso a los 25 minutos con `Dialog`, región `assertive` y «Continuar»; cálculo con `idleExpiresAt` y corrección del desfase de reloj; avisos por vencimiento absoluto; sincronización del cierre de sesión entre pestañas; parámetro `redirect` y regreso a ruta profunda; «Mis sesiones activas»; cierre de sesión en el menú de usuario; se mantiene la prohibición de refrescos por temporizador.
- **Tema:** P10 (`proposal.md:72`; `specs/admin-web-foundation/spec.md:90-109`).

## 2. Estado actual (con evidencia)

### 2.1 Sesión (`apps/admin-web/src/features/identity/session/`)

| Hecho | Evidencia |
|---|---|
| `SessionView` sin metadatos: `restoring`, `restore-failed`, `anonymous`, `authenticated` | `access-session.ts:30-34` |
| El almacén guarda solo `token` y `expiresAt`; descarta `idleExpiresAt` y `sessionExpiresAt` de cada respuesta | `token-store.ts:36-47`; `packages/contracts/src/generated/admin/schemas.ts:9-14` |
| El refresco lo piden solo `boot`, `retryBoot` y una lectura con menos de 60 s de token; un fallo de red no se repite; cualquier otro rechazo termina con `session-expired` | `access-session.ts:88`, `:146-164`, `:167-189` |
| Promesa compartida por pestaña y Web Lock exclusivo entre pestañas; el cierre de sesión toma el mismo bloqueo | `refresh-coordinator.ts:39-71`; `access-session.ts:305-311` |
| «Sin temporizador» es invariante documentado y probado | `access-session.ts:100`; `refresh-coordinator.test.ts:166`; `session-secrets.flow.test.tsx:48`; e2e SS19 `e2e/specs/journeys/signed-in.e2e.ts:55` |
| `GET /sessions/current` es una consulta sin intervalo, sin refetch y sin reintento | `current-session-query.ts:16-25` |
| Ya cubierto: restaurar al recargar, `401` a `session-expired`, `DELETE` de cierre, limpieza de caché al quedar anónimo | `access-session.ts:146-164`, `:298-311`; `BootGate.tsx:34-36` |
| El cierre en una pestaña no avisa a las otras; el JWT de acceso vive hasta 10 min tras la revocación | SS126 `signed-in.e2e.ts:122`; `staff-sign-in/spec.md:437-443`; `docs/03-seguridad.md:515` |

### 2.2 Backend (instantánea `apps/api/openapi/admin.openapi.json`)

- Hay solo cinco rutas: `mfa/enrollments`, `mfa/verifications`, `sessions`, `sessions/current`, `sessions/refresh`. No hay perfil, lista de sesiones ni revocación de otras sesiones.
- `CurrentSessionResponse` trae `accountId`, `institutionId`, `sessionId`, `authenticationMethods` y tres vencimientos (`schemas.ts:20-28`). No trae nombre, correo ni rol.
- Los valores de política están solo en documentos, no en el contrato: inactividad 30 min, absoluta 12 h, token de acceso 10 min (`docs/03-seguridad.md:515-531`); aviso a los 25 min (`docs/ui-ux/04:612-617`).
- La inactividad se mide solo al rotar el refresco; `GET /current` no la mueve (`docs/ui-ux/04:619-627`).
- `ApiResult` no expone cabeceras (`shared/api/http.ts:7-17`), así que hoy no se puede medir el desfase de reloj con `Date`.
- No se confirma si la rama de backend en curso añade rutas (por ejemplo `GET /api/v1/identity/roles`); la instantánea de `main` no las tiene.

### 2.3 Interfaz y rutas

- La página autenticada es mínima (título, mensaje y «Cerrar sesión») dentro de `ScreenLayout`, que es de pantallas de acceso (`SignedInPage.tsx:21-46`; `ScreenLayout.tsx:14-30`).
- Rutas por código con una sola ruta protegida, `/` (`routes.tsx:9-14,47-54`; `router.tsx:13-26`). **AW59** fija las únicas rutas y se declara falso con 13b-2 (`spec.md:525-531`). **AW19** prohíbe `redirect` (`spec.md:208-212`).
- `packages/ui` no tiene `Dialog`, `Topbar`, `Sidebar`, `UserMenu`, `PageHeader`, `Tooltip` ni `Toast`; su ausencia está asertada (DS89, `public-api.test.ts:50`).
- `radix-ui` es dependencia de `ui` (`package.json:26`), pero solo la usan `Checkbox` y `RadioGroup`. `react-remove-scroll` está en el lockfile (`pnpm-lock.yaml:3907`).
- El diseño objetivo del shell está en `docs/ui-ux/04:20-54`: barra de 56 px, lateral de 260/64 px con `localStorage` `confia.admin.sidebar.collapsed`, migas, menú de usuario. El árbol de navegación tiene más de 60 rutas de módulos que no existen.
- Reglas de accesibilidad: enlace de salto, retorno del foco, Escape, `assertive` para sesión por expirar (`docs/ui-ux/03:40,66-69,112`).
- i18n: existen `signedIn.*`, `notice.sessionExpired` y `boot.*` (`admin.json:46,51-58`). Faltan claves de shell, navegación, tema y aviso.

### 2.4 Tema

- Los tokens ya soportan `prefers-color-scheme` y `data-theme="light|dark"`, que gana sobre el sistema (`packages/ui/src/tokens/themes.css:1-8,65`; `docs/ui-ux/01:261-268`).
- La app no escribe `data-theme`, y AW05/AW06 exigen que no haya control hasta 13b-2.
- La puerta de axe conmuta tema con `page.emulateMedia` (`e2e/support/a11y-gate.ts:29`).
- `index.html` solo carga `main.tsx` (`:10`). La CSP de `preview` es `script-src 'self'` y `style-src 'self'` (`vite.config.ts:40-53`). No hay script en línea para aplicar el tema guardado antes del primer pintado.

### 2.5 Pruebas

- Los e2e fallan ante cualquier `securitypolicyviolation` (`e2e/support/scenario.ts:27-34`, AW12).
- La API simulada no modela inactividad ni vencimiento absoluto; su token de acceso es de 15 min frente a los 10 min reales (`session-model.ts:27`).
- La matriz de la puerta es de 22 estados en dos temas, 44 evaluaciones (`docs/09:302-304`).

## 3. Brechas

1. La sesión no conserva `idleExpiresAt` ni `sessionExpiresAt`.
2. No hay detección de actividad ni refresco por actividad.
3. No hay temporizador de aviso; el invariante debe precisarse a «sin temporizador que haga peticiones».
4. No hay corrección de desfase de reloj ni manejo de suspensión o pestañas en segundo plano.
5. No hay sincronización entre pestañas, ni del cierre ni de la extensión de inactividad.
6. Faltan `Dialog`/`AlertDialog`, menú desplegable y enlace de salto.
7. No hay shell ni destinos de navegación reales.
8. No hay identidad en ningún endpoint.
9. No hay selector ni persistencia de tema.
10. No hay retorno a ruta profunda, y solo existe una ruta protegida.
11. La API simulada y la puerta no cubren inactividad, vencimiento absoluto, diálogo abierto, menú abierto ni tema explícito.

## 4. Alcance candidato

**Dentro (candidato)**

- **Sesión:** metadatos de plazos no secretos, fuera del almacén de tokens; detector de actividad con cadencia mínima, siempre por el coordinador existente; temporizador solo de aviso, calculado desde plazos absolutos y reevaluado en `visibilitychange`; aviso de inactividad (diálogo modal, anuncio `assertive`, «Continuar» y «Cerrar sesión»); aviso de vencimiento absoluto, sin «Continuar»; `BroadcastChannel` sin secretos para cierre de sesión y plazos; cierre de sesión en el menú de usuario.
- **Shell y tema:** `Dialog`/`AlertDialog` y menú en `ui`; `Topbar`, `UserMenu`, `PageHeader` y enlace de salto (`Sidebar` y migas según D3); layout de ruta protegida con panel vacío; selector de tema con `data-theme` y persistencia; claves es-HN; historias en dos temas; puerta de axe ampliada; modelo de inactividad en la API simulada; notas fechadas.

**Fuera (propuesto)**

| Exclusión | Dueño |
|---|---|
| Lista y revocación de sesiones activas | Backend o 8b |
| Perfil, nombre y rol | Backend o 8b (P4) |
| MFA voluntaria con sesión completa | 8b |
| Búsqueda global, año lectivo, campana, indicador de caja, atajos | Cada módulo |
| Pantallas de módulos y filtro por rol | Cambios de módulo y backend |
| nginx, nonce de CSP, regresión visual, e2e con pila real | Cambio 11 |
| Ruta profunda con retorno | Primer cambio con más de una ruta protegida |

## 5. Enfoques y compromisos

### 5.1 Estructura

| Enfoque | Pros | Contras |
|---|---|---|
| A. Un cambio, bloques en orden: sesión, ui, shell, tema, e2e | Respeta el nombre acordado; el aviso necesita `Dialog` y el cierre pasa al menú | Cerca del tope de 15 tareas; PR grandes |
| B. Dos cambios: 2a shell y tema, 2b sesión | Cada uno es revisable; la sesión se prueba sin UI | Dos ciclos SDD; hay que repartir `Dialog` y menú |

### 5.2 Refresco por actividad

| Enfoque | Pros | Contras |
|---|---|---|
| R1. Escuchas con cadencia mínima T que piden refresco (lo descrito en `docs/ui-ux/04:619-627`) | La inactividad del servidor sigue a la de la persona; no hay avisos falsos | Más rotaciones, a lo sumo una por T y por pestaña; más pruebas |
| R2. Solo «Continuar» y peticiones de la persona | Mínimo código | Quien trabaja sin tocar el servidor recibe el aviso estando activo; contradice la nota |

### 5.3 Diálogo bajo CSP

| Enfoque | Pros | Contras |
|---|---|---|
| D1. Radix `Dialog`/`AlertDialog` | Accesibilidad resuelta; coherente con `docs/ui-ux/03:53,90-95` | `react-remove-scroll` puede violar `style-src 'self'`; **no verificado**, requiere sonda |
| D2. `<dialog>` nativo con `showModal()` | Trampa de foco, `inert` y Escape nativos; sin estilos inyectados | Se aparta de «sobre Radix»; hay que cubrir el retorno del foco |

### 5.4 Pestañas

| Enfoque | Pros | Contras |
|---|---|---|
| S1. `BroadcastChannel` sin secretos | Inmediato; sincroniza también la extensión | Superficie nueva; la parte 1 descartó transmitir el token, no el evento |
| S2. Comprobar la cookie CSRF al volver a primer plano | Sin canal | No sincroniza la extensión; avisos falsos posibles |
| S3. Ninguna | Cero código | Incumple la línea de la sesión |

### 5.5 Tema

| Enfoque | Pros | Contras |
|---|---|---|
| T1. `data-theme` más `localStorage`, aplicado al arrancar | Cumple `docs/ui-ux/01`; la preferencia no es secreto | Posible destello de un cuadro; la puerta debe evaluar `data-theme` explícito |
| T2. Solo en memoria | Sin almacenamiento | Se pierde al recargar |
| T3. Solo el sistema | Ya existe | Incumple P10 |

## 6. Recomendación

1. **Estructura: enfoque A**, de 11 a 13 tareas y de 5 a 7 PR apilados. Salida: pasar a B si `sdd-tasks` supera 15 tareas. Orden: sonda de CSP y decisión D1/D2, núcleo de sesión, `ui`, shell, tema, e2e y documentos.
2. **Actividad: R1**, con T pequeña frente a la ventana de 5 minutos.
3. **Diálogo:** sonda antes del diseño. Si Radix viola la CSP, usar D2 sin relajar la política.
4. **Pestañas: S1**, con mensaje cerrado y validado por esquema, sin tokens.
5. **Tema: T1**, con la puerta evaluando también `data-theme` explícito.
6. **Núcleo de sesión como módulo propio**, con planificador puro de reloj y temporizadores inyectados. El almacén sigue guardando solo secretos, así que la regla `admin-token-store-is-private` no cambia.
7. `ScreenLayout` queda solo para acceso. El shell trae su propio `main` y su foco al `h1`.

## 7. Decisiones abiertas del propietario (no decididas aquí)

| Id | Decisión |
|---|---|
| D1 | Identidad (P4): menú solo con «Cerrar sesión», o pedir endpoint de perfil antes |
| D2 | Contenido del panel vacío (P5); `EmptyState` exige una acción primaria |
| D3 | Navegación: qué muestra la barra lateral sin módulos, si se entrega solo barra superior, y si hay `Sidebar` colapsable |
| D4 | Tiempos 30/25/5 fijos en el cliente o desde el contrato; cadencia T; eventos que cuentan como actividad |
| D5 | Fin de la cuenta atrás: cierre local con o sin `DELETE`, texto del aviso, y si cualquier actividad cierra el diálogo |
| D6 | Vencimiento absoluto: antelación del aviso y botones, porque no se puede extender |
| D7 | Tema: dos o tres estados, ubicación del control, y si aplica a las pantallas de acceso |
| D8 | Aceptar `localStorage` para tema y colapso, y `BroadcastChannel` sin secretos |
| D9 | `redirect` frente a AW19: aplazar, o guardar el destino en memoria y nunca en la URL |
| D10 | «Mis sesiones activas»: fuera por falta de endpoint, o pedir la API |
| D11 | Un cambio o dos |
| D12 | Diálogo: Radix o `<dialog>` nativo si la sonda falla |

## 8. Riesgos

- **R1. CSP y Radix.** Una violación rompe los recorridos por AW12. Mitigación: sonda local en Chromium con la CSP de `preview`, y plan D2.
- **R2. Extensión involuntaria.** `readCurrentSession` refresca con menos de 60 s de token (`access-session.ts:176-181`); releer `/current` para confirmar el aviso podría mover la inactividad. El aviso debe calcularse con plazos guardados.
- **R3. Temporizadores.** Los `setTimeout` se retrasan en segundo plano y en suspensión: calcular con plazos absolutos y reevaluar en `visibilitychange`.
- **R4. Dos pestañas activas.** Un refresco por actividad en una pestaña rota la cookie de ambas, y la reutilización fuera de la gracia revoca la familia (`docs/03:518`). El Web Lock y la promesa compartida deben seguir como único camino.
- **R5. Pruebas de ausencia que cambian de signo:** AW05/06, AW19, AW59, DS88/89, SS19, SS124-126 y `docs-notes.test.ts`. Se invierten con nota fechada, no se borran (ADR-0018).
- **R6. La puerta de accesibilidad puede quedar ciega.** Faltan estados: shell, diálogo abierto, menú abierto, vencimiento absoluto y tema explícito claro y oscuro, con sus fixtures de violación. Debilitar la puerta desmarca el criterio 8 (`docs/09:307-308`).
- **R7. La API simulada no modela inactividad** y no es evidencia de la pila real (cambio 11).
- **R8. Foco.** Un diálogo que aparece mientras se escribe puede activarse con Enter: decidir el foco inicial (`docs/ui-ux/03:93`) y devolverlo al origen.
- **R9. `localStorage` para preferencias.** Hay que acotar la prueba de secretos para que siga detectando tokens y no preferencias.
- **R10. Tamaño.** Las pruebas multiplican el pronóstico por 1,5 a 2 (referencia de la parte 1).

## 9. Pronóstico de tamaño (líneas escritas, con pruebas, sin lockfile ni código generado)

| # | Bloque | Pronóstico | Realista |
|---|---|---|---|
| 1 | Sonda de CSP (desechable, solo registro en `design.md`) | 0 | 0 |
| 2 | Núcleo de sesión: plazos, `extend`, planificador, reloj, lectura de `Date` en `http.ts` | 450-650 | 700-1 100 |
| 3 | Actividad con cadencia, sincronización entre pestañas, vencimiento absoluto | 350-550 | 550-900 |
| 4 | `ui`: `Dialog`/`AlertDialog`, menú, `Topbar`, `PageHeader`, enlace de salto, historias, claves | 600-900 | 900-1 400 |
| 5 | Shell: layout, panel vacío, menú de usuario con cierre, navegación, migas, claves | 400-600 | 600-950 |
| 6 | Selector de tema y persistencia | 150-250 | 250-400 |
| 7 | Aviso de inactividad y de vencimiento absoluto en la interfaz | 250-400 | 400-650 |
| 8 | API simulada, e2e, matriz de la puerta, inversión de pruebas de ausencia | 500-800 | 800-1 300 |
| 9 | Specs, ADR si D12 lo exige, notas en `docs/09` y `docs/ui-ux/04` | 200-350 | 300-500 |

**Total:** 2 900-4 500 de pronóstico y 4 500-7 600 realista, de 6 a 10 PR.

- `Decision needed before apply: Yes`
- `Chained PRs recommended: Yes`
- `400-line budget risk: High`

## 10. Listo para la propuesta

Sí, con condiciones. El propietario debe responder D1 a D3, D7 y D11, y la sonda de CSP (R1) es un paso previo al diseño. D1 y D10 dependen de un cambio de backend fuera de este árbol.
