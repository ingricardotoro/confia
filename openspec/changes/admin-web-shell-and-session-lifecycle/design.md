# Diseño: shell de `apps/admin-web` y vida completa de la sesión (cambio 13b-2 de F0)

- **Cambio:** `admin-web-shell-and-session-lifecycle`
- **Fase:** diseñar
- **Fecha:** 2026-11-10
- **Estado:** borrador para revisión del propietario. La propuesta fue aprobada el 2026-11-10, incluidos sus «Supuestos por
  confirmar» como valores de trabajo; este documento los trata como vigentes.
- **Nota fechada 2026-11-10 (segunda conciliación con las especificaciones).** Este diseño se concilió con la versión final de
  `specs/`, que rige. Respecto de la versión anterior: el menú de usuario vuelve a ser un botón de divulgación sin `role="menu"`
  (H10, decisión del propietario, SH13 a SH17, SH38, SH39, DS110 a DS115); no existe `DropdownMenu`; las exportaciones nuevas de
  `packages/ui` son `Dialog`, `AlertDialog`, `SkipLink`, `PageHeader`, `Topbar`, `UserMenu` y `AppShell` (DS02, DS88, DS131); el
  aviso de vencimiento absoluto es un `AlertDialog` modal con «Entendido» y «Cerrar sesión ahora» (SS173, SS174); y la regla
  `ui-no-scroll-locking-overlays` cubre también `react-remove-scroll` y `react-remove-scroll-bar` (BX126 a BX130). Las
  decisiones conservan su identificador (H1 a H17).
- **Entradas:** `proposal.md`, `exploration.md` y `specs/` de este cambio (`staff-sign-in`, `admin-web-shell`,
  `admin-web-foundation`, `design-system`, `build-integrity`); `admin-web-foundations-and-session/design.md` (D1-D19) y
  `admin-web-staff-sign-in/design.md` (E1-E13), cuyas decisiones siguen vigentes salvo donde este documento las precise;
  `docs/03-seguridad.md`:515-531; `docs/ui-ux/02-sistema-de-diseno-y-componentes.md` §3; `docs/ui-ux/04-patrones-de-interaccion.md`:612-627;
  `docs/ui-ux/01` (tema); `CLAUDE.md`. Código leído en `C:\dev\confia-frontend`. Las rutas de este documento son relativas a
  `apps/admin-web/` salvo que se diga otra cosa.
- **Modo de TDD:** estricto, con las superficies de las partes anteriores (unidad con Vitest, Vitest en modo navegador para
  `packages/ui`, e2e con Playwright contra la API simulada).
- **Dinero:** este cambio no muestra importes.
- **Decisión de diálogo (D12):** **resuelta** por la sonda de CSP. Ver la sección 1.

## 1. Resultado de la sonda de CSP

Sonda local y desechable, ejecutada el 2026-11-10 en Chromium contra `vite preview` con la CSP real de `vite.config.ts`
(`script-src 'self'`, `style-src 'self'`). No es PR ni tarea; su único producto es este registro (AW70).

| Componente probado | Comportamiento funcional | Violaciones de CSP |
|---|---|---|
| Radix `Dialog` | Trampa de foco, Escape y retorno del foco correctos | **Una por cada apertura:** `style-src-elem`, `blockedURI=inline`, causada por el elemento `<style>` que inyecta `react-remove-scroll-bar` |
| Radix `DropdownMenu` | Correcto | **El mismo problema** (usa la misma cadena de bloqueo de desplazamiento) |
| `<dialog>` nativo con `showModal()` | Trampa de foco, Escape y retorno del foco correctos | **Cero** |

**Consecuencia.** AW12 hace fallar cualquier `securitypolicyviolation` en los recorridos (`e2e/support/scenario.ts`:27-34) y la
política no se relaja (AW71). Por tanto:

1. **D12 queda decidida: `<dialog>` nativo** para `Dialog` y `AlertDialog` (decisión H8).
2. **El menú de usuario no usa Radix `DropdownMenu`.** Es un botón de divulgación con `aria-expanded` y `aria-controls`, sin
   `role="menu"` (decisión H10).
3. La rama Radix queda como **alternativa rechazada** en H8 y H10, con este motivo.
4. La CSP **no se relajó**: la implementación elegida no necesita `'unsafe-inline'` ni amplía `style-src` ni `script-src`.
5. Como el cambio se aparta de «shadcn/ui sobre Radix» (`CLAUDE.md`, tabla de stack) para las superposiciones, se escribe un
   ADR nuevo (H17; BX126). Radix sigue siendo la base de los átomos que no bloquean el desplazamiento (`Checkbox`,
   `RadioGroup`), que ya pasan AW12 hoy (BX129).
6. `radix-ui` es dependencia directa solo de `packages/ui`, no de `apps/admin-web`; la regla de H16 que lo acota vive por tanto
   en `packages/ui`.

## 2. Enfoque técnico

El cambio tiene tres ejes que se entregan en bloques apilados (sección 13):

1. **Núcleo de vida de la sesión**, como módulo propio en `src/features/identity/session/lifecycle/`. Su centro es un
   planificador **puro**: recibe plazos locales y un instante, y devuelve la fase de la sesión y el próximo instante en que
   conviene despertar. Alrededor viven piezas finas e inyectables: un reloj corregido por la cabecera `Date`, un programador con
   un único temporizador inyectado, un detector de actividad con cadencia mínima y un canal entre pestañas validado por esquema.
   Ninguna de ellas pide un refresco por su cuenta: toda extensión pasa por `AccessSession.extend()`, que usa el coordinador
   existente (promesa compartida y Web Lock).
2. **Componentes de `packages/ui`**: los siete que fija `design-system` (`Dialog`, `AlertDialog`, `SkipLink`, `PageHeader`,
   `Topbar`, `UserMenu` y la plantilla `AppShell`), sin compañeros de composición públicos. Ninguno inyecta estilos.
3. **Shell y tema en `apps/admin-web`**: layout de ruta protegida sobre `AppShell`, panel de bienvenida, contenedor del menú de
   usuario con selector de tema y cierre de sesión, y los dos avisos de sesión sobre `AlertDialog`.

Invariantes que se conservan de las partes anteriores:

1. Los tokens siguen solo en `token-store.ts`; la regla `admin-token-store-is-private` no cambia. Los plazos no son secretos
   y viven en otro archivo (SS129).
2. Ninguna presentación hace peticiones (`admin-presentation-is-pure`).
3. Toda rotación de la cookie de refresco, y el `DELETE` de cierre, ocurren dentro del Web Lock `confia.admin.session.refresh`.
4. Ningún secreto sale de la memoria del módulo: ni al canal, ni a `localStorage`, ni a la URL. Los plazos tampoco se persisten.

El invariante «sin temporizador» (`access-session.ts`:100, `refresh-coordinator.ts`:50) **se precisa** a: *ningún
temporizador ni consulta de fondo pide un refresco* (SS19 precisado). Hay a lo sumo un temporizador pendiente, que solo cambia el
estado de la interfaz (SS141); la única petición que el paso del tiempo dispara es el `DELETE` de mejor esfuerzo al cerrar por
vencimiento (supuesto D5).

```
        respuestas con plazos (login, verify, activate, refresh, current)
                         │  + cabecera Date (http.ts, AW65-AW67)
                         ▼
  AccessSession ──► server-clock (último desfase válido) ──► deadline-store (plazos locales, no secretos)
     ▲    ▲                                                        │ subscribe
     │    │                                                        ▼
     │    │                              lifecycle/scheduler (un solo temporizador) ──► planner (puro)
     │    │                                 ▲ visibilitychange, pageshow                    │
     │    │                                 │                                       fase + wakeAt
     │    └── extend() ◄── activity-detector (T = 60 s por pestaña) ◄── tecla, puntero, desplazamiento
     │                 ◄── «Continuar» o Escape del aviso de inactividad
     │
     └── endLocally / endByTimeout ──► session-channel (BroadcastChannel) ──► otras pestañas
                                                     │
          AuthenticatedLayout ◄── fase ──► IdleWarning | AbsoluteExpiryWarning (ambos AlertDialog modal)
```

## 3. Estado actual que condiciona el diseño

| Hallazgo | Fuente | Consecuencia |
|---|---|---|
| El almacén guarda solo `token` y `expiresAt` y descarta `idleExpiresAt` y `sessionExpiresAt` | `src/features/identity/session/token-store.ts`:36-47 | Los plazos se guardan en un almacén aparte (H1); el almacén de tokens no cambia |
| Toda respuesta autenticada y la de la sesión actual traen los tres vencimientos | `packages/contracts/src/generated/admin/schemas.ts`:9-15, :20-28 | Basta con observar las respuestas existentes; no hace falta endpoint nuevo |
| `ApiResult` no expone cabeceras | `src/shared/api/http.ts`:7-14 | Se agrega `serverDate` a los resultados `ok` (H3; AW65 a AW67) |
| El refresco solo lo piden `boot`, `retryBoot` y una lectura con menos de 60 s de token | `access-session.ts`:88, :146-189 | Se agrega `extend()` sobre el mismo coordinador (H4) |
| `signOut` toma el mismo Web Lock que el refresco | `access-session.ts`:305-311 | El `DELETE` por vencimiento reutiliza ese camino (H6) |
| Una sola ruta protegida, `/`, que renderiza `SignedInContainer` dentro de `ScreenLayout` | `src/features/identity/routes.tsx`:47-54 | Layout protegido nuevo (H11); AW59 se actualiza y AW72 se agrega |
| `packages/ui` no tiene `Dialog` y DS89 lo asevera | `packages/ui/src/public-api.test.ts`:50 | DS89 se invierte y DS02 se precisa (sección 11) |
| `packages/ui` se organiza en `tokens/`, `atoms/`, `molecules/`, `domain/`, `layouts/` y `hooks/`, sin carpeta de organismos | `docs/ui-ux/02` §3 | Los componentes nuevos van a `atoms/`, `molecules/` y `layouts/` (sección 8) |
| La puerta conmuta tema con `page.emulateMedia` y nunca escribe `data-theme` | `e2e/support/a11y-gate.ts`:29 | Los estados de tema explícito se alcanzan con el selector (H15; BX116) |
| La matriz tiene 22 estados y las fixtures de violación son HTML servidos con `page.route` | `e2e/support/a11y-matrix.ts`:52-75; `e2e/support/fixtures.ts` | Estados y fixtures nuevos con el mismo mecanismo (H15) |
| La API simulada usa un token de acceso de 15 min y no modela inactividad ni vencimiento absoluto | `e2e/mock-api/session-model.ts`:27 | H14 |
| La prueba de secretos vuelca `localStorage` entero y busca centinelas | `e2e/specs/journeys/secrets.e2e.ts`:93-133 | Se acota con una lista cerrada de claves (H13) |
| `index.html` solo carga `main.tsx`; la CSP prohíbe scripts en línea | `index.html`:10; `vite.config.ts`:40-53 | El tema se aplica desde un módulo, nunca en línea (H12; SH30) |
| La lectura de la sesión actual da a la página autenticada sus estados de carga y error | `admin-web-shell`, SH03 | El panel de bienvenida la conserva (H11) |

## 4. Decisiones de arquitectura: núcleo de sesión

### H1. Los plazos viven en un almacén propio, no secreto, separado del almacén de tokens

**Elección.** Archivo nuevo `session/deadline-store.ts`. Guarda `LocalDeadlines` (sección 9): `idleExpiresAt` y
`sessionExpiresAt` ya convertidos al reloj local (H3), y `lastRefreshAt`, el instante local del último refresco **de esta
pestaña** (lo usa la cadencia de H5). Se alimenta desde todas las respuestas que traen plazos: `restore`,
`accessTokenForRequest`, `signIn`, `verify`, `activate`, `readCurrentSession` y `extend()` (SS128); y desde los mensajes de
plazos del canal (H7). Se vacía en `endLocally`, que además cancela el temporizador y las escuchas de actividad (SS133). Expone
`snapshot()` (mismo objeto hasta que cambia) y `subscribe()`. Nunca escribe en almacenamiento web, la URL ni `history.state`
(SS129); tras recargar, los plazos salen de la respuesta del refresco de restauración (SS130).

**El servidor manda:** el plazo de la última respuesta **reemplaza** al anterior, aunque sea más temprano (SS131). El plazo
efectivo de cierre es el menor de los dos (SS132).

**Alternativas descartadas.** Guardar los plazos en `token-store.ts` (mezcla secretos con datos que la interfaz debe leer, y
obligaría a abrir la regla `admin-token-store-is-private`); guardarlos en `SessionView` (cada refresco cambiaría la vista y
volvería a renderizar todo lo que la consume, incluidas las guardas de ruta); un plazo de inactividad monótono (contradice SS131).

### H2. Planificador puro, sin DOM ni temporizadores

**Elección.** `session/lifecycle/planner.ts` exporta `planLifecycle(input, policy)` y no importa nada fuera de su carpeta.
Recibe los plazos, el instante y el `sessionExpiresAt` para el que la persona descartó el aviso absoluto, si lo hizo. Devuelve la
fase y `wakeAt`, el próximo instante en que la fase o su cuenta atrás visible pueden cambiar.

Reglas, en orden de precedencia (`I` es `idleExpiresAt`, `S` es `sessionExpiresAt`):

| Condición | Fase |
|---|---|
| Sin plazos | `inactive` |
| `now >= S` | `expired` con motivo `absolute` |
| `now >= I` | `expired` con motivo `idle` |
| `now >= S - ABSOLUTE_WARNING_LEAD_MS` y el aviso no se descartó para este `S` | `absolute-warning` |
| `now >= S - ABSOLUTE_WARNING_LEAD_MS` y el aviso ya se descartó para este `S` | `absolute-acknowledged` (sin diálogo) |
| `now >= I - IDLE_WARNING_LEAD_MS` | `idle-warning` |
| En otro caso | `running` |

- **Precedencia del absoluto.** Mientras la ventana del aviso absoluto está abierta, el aviso de inactividad no se muestra, aunque
  su ventana también lo esté, y tampoco después de que la persona descarte el absoluto (SS175). Ofrecer «Continuar» sería
  prometer algo que el servidor no puede cumplir (supuesto D6).
- **Un descarte vale para un plazo.** El aviso absoluto descartado no reaparece salvo que `S` cambie. El descarte se guarda en
  el programador, no en almacenamiento, y se borra con la sesión.
- **Plazo efectivo.** Si `I` llega antes que `S` mientras la ventana absoluta está abierta, la sesión termina por inactividad
  igual (SS132).
- En las fases con diálogo, `wakeAt` es además el siguiente segundo entero, para que el mismo temporizador actualice la cuenta
  atrás visible (H9; SS141).

Política, como constantes con nombre en `session/lifecycle/policy.ts` (supuesto D4: fijas en el cliente hasta que el contrato
las exponga):

| Constante | Valor | Origen |
|---|---|---|
| `IDLE_WARNING_LEAD_MS` | 5 min | `docs/ui-ux/04`:612-614; `staff-sign-in`, valores de trabajo |
| `ABSOLUTE_WARNING_LEAD_MS` | 5 min | `staff-sign-in`, valores de trabajo (D6) |
| `ACTIVITY_CADENCE_MS` | 60 s | `staff-sign-in`, valores de trabajo (R1) |

Los 30 minutos de inactividad y las 12 horas absolutas **no** son constantes del cliente: llegan en cada respuesta como
plazos. El cliente solo conoce las antelaciones y la cadencia.

**Alternativas descartadas.** Un `setInterval` que mire el reloj cada segundo (consume en segundo plano, es un sondeo y viola
SS141); calcular con duraciones relativas («quedan 25 minutos») en lugar de instantes absolutos (falla con suspensión y con
pestañas en segundo plano; SS135).

### H3. Corrección del desfase de reloj con la cabecera `Date`

**Elección.** `http.ts` agrega a todo resultado `ok` el campo `serverDate: number | undefined`, que es `Date.parse` de la
cabecera `Date` (o `undefined` si falta, está vacía o no es una fecha HTTP válida, sin `NaN` ni excepción; AW65 a AW67), y
`receivedAt`, el `Date.now()` local al recibir la respuesta. Los resultados `problem`, `network` y `unexpected-response` no
cambian, y las seis operaciones no cambian sus rutas, cuerpos ni cabeceras.

`session/lifecycle/server-clock.ts` guarda **el último desfase válido** de la pestaña:

```
offset = (serverDate + 500) - receivedAt      // solo si serverDate es un instante válido
localDeadline = Date.parse(serverDeadline) - offset
```

El `+ 500` centra la resolución de un segundo de la cabecera. Una respuesta sin `Date` o con una cabecera inválida **no toca**
el desfase: se conserva el de la última respuesta válida (SS140). Sin ninguna respuesta válida, el desfase es cero y los plazos se
usan tal como vienen, sin error (SS139). Los plazos se convierten al recibirlos; los mensajes del canal transportan instantes del
servidor y cada pestaña los convierte con su propio desfase (H7). SS137 y SS138 prueban el reloj adelantado y el atrasado.

Límite conocido: si la persona cambia la hora del sistema después de recibir los plazos, el aviso se desplaza hasta la
siguiente respuesta. Se acepta, porque el servidor sigue siendo la autoridad y cualquier petición posterior corrige.

**Alternativas descartadas.** Calcular el desfase de cada respuesta sin conservarlo (una cabecera inválida dejaría el desfase en
cero; contradice SS140); `performance.now()` monótono (se detiene durante la suspensión en algunos navegadores); ignorar el
desfase (SS137, SS138).

### H4. Un solo camino de extensión: `AccessSession.extend()`

**Elección.** Operación nueva `extend(): Promise<ExtendOutcome>` que llama `coordinator.refreshOnce()`, acepta el token en el
almacén, registra los plazos y `lastRefreshAt`, y difunde los plazos nuevos por el canal. Sus desenlaces:

| Desenlace del coordinador | Efecto |
|---|---|
| `refreshed` | Tokens y plazos nuevos; mensaje `deadlines` al canal |
| `network` | No se repite ni se muestra cuando lo pidió la actividad (SS148); la sesión se conserva |
| `rejected` o `no-session` | `endLocally("session-expired")` y mensaje `signed-out` con motivo `expired` (SS149, SS164) |

`extend()` es el único punto que usan el detector de actividad y «Continuar». No es una vía nueva hacia el servidor: es el
mismo `refreshOnce()` que ya usan `boot` y la lectura con menos de 60 s, así que una actividad y una petición de la persona
simultáneas comparten una sola petición (SS146), y una segunda activación de «Continuar» en vuelo tampoco emite otra (SS162).
Todo refresco correcto de la pestaña, venga de donde venga, difunde los plazos.

### H5. Detector de actividad con cadencia mínima por pestaña

**Elección.** `session/lifecycle/activity-detector.ts`, con objetivo de eventos, reloj y política inyectados.

- **Eventos que cuentan:** `keydown`, `pointerdown` (ratón, lápiz o toque), `wheel` y `scroll`, escuchados en `document` en
  fase de captura y pasivos (`scroll` no burbujea, pero la captura en `document` lo recibe de cualquier elemento desplazable)
  (SS142). `pointermove`, `mousemove` y `focus` **no** cuentan (SS143): el mero movimiento del puntero o un cambio de foco no son
  intención de la persona, y contarlos mantendría viva una estación de caja desatendida (`docs/ui-ux/04`:620-624).
- **Cadencia:** una actividad pide `extend()` solo si `now - lastRefreshAt >= ACTIVITY_CADENCE_MS` (60 s), donde
  `lastRefreshAt` es el del último refresco **de esta pestaña** (SS144, SS145). Cada pestaña lleva su propia cadencia; dos
  pestañas activas que piden refrescar a la vez se serializan con el Web Lock, que sigue siendo el único camino (SS147).
- **Condiciones:** solo con sesión completa (`authenticated`; SS151), solo con `document.visibilityState === "visible"`, y
  **nunca** mientras un aviso de sesión está abierto, sea el de inactividad o el absoluto (SS152, SS174). Con un aviso abierto
  solo sus acciones actúan. Tras descartar el aviso absoluto, la actividad vuelve a refrescar con su cadencia, lo que extiende la
  inactividad y nunca `sessionExpiresAt`.
- **Margen:** con T = 60 s, una persona activa tiene en el servidor un plazo de inactividad de al menos
  `última actividad - 60 s + 30 min`, así que el aviso (a 5 min del plazo) nunca aparece antes de 24 minutos sin actividad
  (SS166).

El detector se activa cuando la vista pasa a `authenticated` y retira sus escuchas al salir de ella.

**Alternativas descartadas.** R2 de la exploración (solo «Continuar» y las peticiones de la persona: quien escribe un formulario
largo sin tocar el servidor recibiría el aviso estando activo); una cadencia compartida entre pestañas a través del canal (agrega
un campo al mensaje y contradice la cadencia por pestaña).

### H6. Cierre por vencimiento: cierre local y `DELETE` de mejor esfuerzo

**Elección.** Operación nueva `endByTimeout(reason: "idle" | "absolute")`, que el programador invoca al entrar en la fase
`expired`:

1. `endLocally("session-expired")`: el aviso de llegada es el de 13b-1, «Su sesión se cerró por seguridad. Vuelva a iniciar
   sesión.» (SS136, SS176), y el foco va al encabezado del inicio de sesión (SS169). No se agregan valores a `ArrivalNotice`.
2. Mensaje `signed-out` con motivo `expired` al canal.
3. `DELETE /api/v1/auth/sessions/current` dentro del Web Lock, **sin esperar** la respuesta y sin repetirlo (SS167). Su fallo
   (red o `403 csrf-invalid`) no cambia nada visible (SS168). Recargar después no sondea (SS170).

El orden importa: la sesión local termina primero, para que ninguna interacción entre medio vea una sesión vencida.

**Alternativas descartadas.** Solo cierre local (la sesión seguiría viva en el servidor hasta su propio plazo); esperar al
`DELETE` antes de cerrar (deja la pantalla viva durante un fallo de red).

### H7. Canal entre pestañas con mensaje cerrado de dos tipos y sin secretos

**Elección.** `session/lifecycle/session-channel.ts`. `BroadcastChannel` de nombre `confia.admin.session`. El esquema es Zod
estricto (`.strict()`, sin claves extra), con versión `v: 1`:

| `type` | Campos | Efecto en una pestaña con sesión completa que lo recibe |
|---|---|---|
| `signed-out` | `reason: "person" \| "expired"` | `endLocally()` sin `DELETE` propio; con `expired`, el aviso de sesión vencida (SS178, SS179) |
| `deadlines` | `idleExpiresAt`, `sessionExpiresAt`: instantes ISO 8601 con desfase, como los del contrato | Se convierten con el desfase propio (H3) y **reemplazan** los suyos (H1); si el aviso de inactividad estaba abierto y la fase vuelve a `running`, se cierra; no se emite ningún refresco (SS180) |

- Una pestaña sin sesión completa ignora todos los mensajes (SS183).
- Una pestaña nunca reenvía lo que recibe (SS181): solo emite la pestaña que originó el evento (cierre por la persona, extensión
  o vencimiento).
- Un mensaje que no cumple el esquema (tipo desconocido, instante inválido, campo extra, valor que no es objeto) se descarta en
  silencio, sin excepción y sin registrar su contenido (SS182; regla 11 de `CLAUDE.md`).
- El canal **nunca** transmite tokens, códigos, identificadores de cuenta ni datos de la persona. La prueba lo asevera con
  centinelas y con la búsqueda de cadenas con forma de JWT (SS177).
- Sin `BroadcastChannel` en el navegador, el canal se degrada a nada: cada pestaña sigue sus propios plazos, sin error (SS184).

**Alternativas descartadas.** S2 de la exploración (comprobar la cookie CSRF al volver a primer plano: no sincroniza la
extensión y produce avisos falsos); el evento `storage` de `localStorage` (escribe la sesión en almacenamiento persistente y
choca con SH29); transmitir el token (descartado ya por la parte 1).

## 5. Decisiones de arquitectura: avisos e interfaz

### H8. `Dialog` y `AlertDialog` sobre `<dialog>` nativo

**Elección (resuelta por la sonda, sección 1).** `packages/ui/src/molecules/dialog/`, un núcleo interno `NativeDialog` y dos
exportaciones públicas, sin compañeros de composición:

- `Dialog` (rol implícito `dialog`) y `AlertDialog` (`role="alertdialog"` sobre el mismo elemento).
- Propiedades controladas `open`, `onDismiss`, `title`, `description` e `initialFocusRef`, más `children` (acciones). `title` y
  `description` son obligatorias en el tipo (DS100) y se enlazan con `aria-labelledby` y `aria-describedby` generados con
  `useId` (DS99).
- Apertura con `showModal()` y cierre con `close()` desde un efecto que sigue a `open`; el estado del consumidor es la fuente de
  verdad.
- **Escape:** el evento `cancel` se cancela con `preventDefault()` y se convierte en `onDismiss()`; el diálogo sigue abierto
  mientras el consumidor no cambie `open`. Chromium permite cancelar el primer Escape; un segundo Escape cierra de todos modos
  (*close watcher*), y el evento `close` también termina en `onDismiss()`, de modo que `open` nunca queda desincronizado
  (DS106, DS107).
- **Clic fuera:** `AlertDialog` lo ignora (DS107). `Dialog` no agrega cierre por clic fuera: la especificación no lo exige.
- **Foco inicial:** el elemento de `initialFocusRef` (DS102); sin él, el primer elemento interactivo que no sea un botón de
  cerrar (DS101). Un control destructivo nunca lo recibe por defecto.
- **Confinamiento:** el modo modal de `showModal()` confina el foco y vuelve inerte el resto de la página (DS103).
- **Retorno del foco:** se guarda `document.activeElement` antes de `showModal()`. Al cerrar, si el origen sigue conectado, se le
  devuelve; si ya no existe, el foco va al `h1` de la página (DS104, DS105). La sonda mostró que Chromium ya devuelve el foco; la
  copia explícita cubre los motores que no lo hacen.
- **Anuncio:** `AlertDialog` contiene una región `aria-live="assertive"` **dentro** del diálogo, porque `showModal()` vuelve
  inerte todo lo que está fuera y un lector de pantalla puede no anunciar una región inerte (DS108). Un texto que cambia cada
  segundo se marca `aria-hidden="true"`; el texto de las regiones activas cambia como máximo al abrir y al terminar (DS109).
- **Bloqueo de desplazamiento:** regla estática en la hoja de estilos empaquetada de `ui`,
  `html:has(dialog[open]:modal) { overflow: hidden; }`. No se añade ningún `<style>` ni atributo de estilo en línea (DS130).
- **Fondo:** `::backdrop` con un token de superposición en los dos temas.

**Alternativa rechazada.** Radix `Dialog`/`AlertDialog`. Funciona, pero cada apertura inyecta un `<style>` desde
`react-remove-scroll-bar` y produce `style-src-elem`; con AW12 rompe todos los recorridos, y relajar la política no es admisible
(AW71). También se descarta añadir un *nonce*: pertenece al cambio 11 y no cubre `vite preview`.

### H9. Aviso de inactividad y aviso de vencimiento absoluto, ambos modales

**Elección.** `src/features/identity/session-warnings/`: un contenedor, `SessionWarningsContainer`, que lee la fase del
programador con `useSyncExternalStore`, y dos presentaciones puras sobre `AlertDialog`.

| Aspecto | `IdleWarning` | `AbsoluteExpiryWarning` |
|---|---|---|
| Fase que lo muestra | `idle-warning` | `absolute-warning` |
| Texto | «Su sesión se cerrará en 5 minutos por inactividad.» (`docs/ui-ux/04`:612) y el tiempo restante (SS153) | Texto del catálogo y el tiempo restante (SS171) |
| Acciones | «Continuar» y «Cerrar sesión» | «Entendido» y «Cerrar sesión ahora»; ninguna de extensión (D6, SS171) |
| Foco inicial | «Continuar», nunca «Cerrar sesión» (SS155) | «Entendido» (SS173) |
| Escape | Equivale a «Continuar» (SS161) | Equivale a «Entendido» (SS173) |
| Acción principal | `extend()`: un solo refresco; con `200` cierra el aviso (SS159) | Descarta el aviso **sin petición** y sin cambiar `sessionExpiresAt`; no reaparece salvo que el plazo cambie (SS173) |
| Acción de cierre | `signOut()` normal (SS165) | `signOut()` normal |
| Al cerrar | El foco vuelve al origen (o al `h1`) y lo escrito se conserva (SS160) | Igual (SS173) |
| Actividad mientras se muestra | No refresca (SS152) | No refresca (SS174) |
| Al vencer | `endByTimeout("idle")` | `endByTimeout("absolute")` (SS176) |

- **Foco inicial en la acción segura.** El diálogo puede aparecer mientras la persona escribe (R8). Con el foco en la acción
  inocua, un Intro accidental nunca cierra la sesión; Escape sigue la misma lógica.
- **Lo escrito no se pierde.** El diálogo no desmonta el contenido del `main`; el valor de los campos se conserva y el foco vuelve
  al campo de origen.
- **Anuncios.** Los dos se anuncian con prioridad `assertive` (SS154, SS172) y el texto de sus regiones activas cambia como máximo
  dos veces: al aparecer y al terminar (SS158, SS172). La cuenta atrás visible lleva `aria-hidden="true"`.
- **Mostrar un aviso no hace peticiones**, ni siquiera la lectura de `/current` (SS157, SS174). El contenedor solo lee plazos
  guardados.
- **Cuenta atrás con un solo temporizador.** No hay `setInterval`. En las fases con diálogo el planificador devuelve como
  `wakeAt` el siguiente segundo entero (H2), y el programador expone el tiempo restante junto con la fase (SS141).
- **«Continuar» con fallo de red.** El diálogo sigue abierto, muestra un `Alert` de error del catálogo y un reintento que ejecuta
  la persona; no reintenta solo y la cuenta atrás no se detiene (SS163). Otro rechazo termina la sesión local (SS164).
- **Recepción de plazos de otra pestaña.** Si el plazo nuevo devuelve la fase a `running`, el aviso de inactividad se cierra con
  el retorno del foco normal (SS180). Si cambia `S`, el descarte del aviso absoluto deja de valer.

**Alternativa descartada.** Aviso absoluto no modal, como banda bajo la barra superior (una versión anterior de este diseño y de
la especificación): no atrapa el foco, pero reparte los avisos de sesión entre dos mecanismos y la especificación final lo
resolvió como diálogo de alerta modal (nota de SS173).

### H10. `UserMenu` como botón de divulgación, sin `role="menu"` y sin Radix

**Elección (decisión del propietario).** `UserMenu` en `packages/ui/src/molecules/user-menu/` sigue el patrón de divulgación de
las APG: un `button` con `aria-expanded` y `aria-controls` que muestra u oculta un panel no modal (`div` con `hidden` cuando está
cerrado), posicionado con CSS estático bajo el disparador. **No** usa `role="menu"`, `menuitem`, `menuitemradio` ni
`aria-haspopup="menu"` (SH13, SH38, DS110, DS114). No atrapa el foco ni bloquea el desplazamiento (SH39).

`UserMenu` recibe por propiedades todo su contenido (DS117): el nombre accesible genérico del disparador, la etiqueta genérica del
usuario (texto, D1), el grupo de tema y el texto y la acción de «Cerrar sesión». El grupo de tema es el átomo `RadioGroup`
existente (`role="radiogroup"`, opciones con `aria-checked`; SH38, DS114), que ya pasa AW12. El selector de tema y el cierre de
sesión son de `apps/admin-web` (`UserMenuContainer`); `UserMenu` no conoce la aplicación.

Teclado (SH14, SH16, SH17, DS110 a DS113):

| Tecla | Efecto |
|---|---|
| Intro o Espacio en el disparador | Abre (el foco va al primer control del panel) o cierra |
| Flecha abajo y flecha arriba | Mueven el foco entre las paradas del panel; el grupo de tema es una sola parada |
| Flechas dentro del grupo de tema | Cambian de opción, como en `RadioGroup` (DS114) |
| Inicio y Fin | Primera y última parada |
| Escape | Cierra y devuelve el foco al disparador |
| Tab fuera del panel, o clic fuera | Cierra **sin** mover el foco: queda donde el navegador lo lleva |

La espera del cierre de sesión se expresa con `aria-busy` en el disparador, nunca con `disabled` en el botón. El menú no hace
peticiones (SH18, DS115). Cada control mide al menos 24 por 24 píxeles (DS122).

**Alternativas rechazadas.**

- Radix `DropdownMenu`: misma violación de `style-src-elem` que `Dialog` (sección 1).
- Un `DropdownMenu` propio con `role="menu"` y `menuitemradio` (una versión anterior de este diseño): compatible con la CSP, pero
  exige el modelo completo de teclado de menú para tres opciones y un botón, los lectores de pantalla cambian de modo dentro de
  un menú, y el propietario eligió la divulgación; la especificación final prohíbe esos roles (SH38, DS114) y la exportación
  (DS02).
- API `popover` nativa: resuelve la capa superior y el cierre ligero, pero su posición respecto del disparador requiere
  *anchor positioning* de CSS (sin soporte uniforme en los navegadores objetivo) o posicionamiento por script, y su retorno del
  foco varía según cómo se invoque. Un panel no modal en la barra superior no necesita la capa superior.

### H11. Layout de ruta protegida sobre `AppShell` y panel de bienvenida

**Elección.** En `routes.tsx` la ruta `/` pasa a ser hija de una ruta de layout sin segmento (`id: "authenticated"`) que
contiene la guarda en `beforeLoad` que hoy tiene `/`; sin sesión, la guarda lleva al inicio de sesión antes de renderizar el
shell (AW72). El layout, `src/features/shell/AuthenticatedLayout.tsx`, usa la plantilla `AppShell` de `packages/ui`, que compone
`SkipLink`, `Topbar` y un único `main` con identificador de destino y `tabIndex={-1}`, recibiendo sus partes por propiedades
(DS131):

```
AuthenticatedLayout
└── AppShell
    ├── SkipLink → #main-content        (primer elemento enfocable; SH05 a SH07)
    ├── Topbar (banner; SH08)
    │   ├── etiqueta de la aplicación (catálogo)
    │   └── UserMenuContainer → UserMenu
    └── main#main-content (tabIndex -1)
        └── <Outlet/> → WelcomeContainer → PageHeader (h1) + mensaje de bienvenida
+ SessionWarningsContainer → IdleWarning | AbsoluteExpiryWarning (fuera del main)
```

- Al llegar a la página autenticada, el foco va al `h1` del `main` y `document.title` es un texto propio del catálogo (SH04).
- `ScreenLayout` queda solo para las pantallas de acceso; no cambia (SH02).
- `SignedInPage` y `SignedInContainer` se retiran. El panel de bienvenida (`src/features/shell/welcome/`) conserva la lectura de
  la sesión actual que ya existía, sin mostrar identidad (D1; SH11, SS103): da a la pantalla sus estados de carga, error y listo
  dentro del `main`, con el layout presente en los tres (SH03, SS104, SS105), y la matriz de la puerta ya los evalúa. El panel no
  tiene acción ni `EmptyState` (D2; SH10, SH12).
- El parámetro `redirect` sigue prohibido (AW19, conservado con nota; supuesto D9).

**Alternativa descartada.** Quitar la lectura de `GET /sessions/current` del panel: reduce una petición, pero contradice SH03 y
deja la matriz con tres estados menos.

### H12. Tema de tres estados con `data-theme` y `localStorage`, solo en la página autenticada

**Elección.** `src/features/shell/theme/`:

- `theme-preference.ts`: clave `confia.admin.theme`, valores `system`, `light` y `dark`. Un valor ausente o desconocido es
  `system` (SH20, SH25). Lectura y escritura envueltas en `try`; si `localStorage` lanza, la preferencia vive solo en memoria
  mientras dure la página, sin error visible ni en la consola (SH26).
- `apply-theme.ts`: `light` y `dark` escriben `data-theme` en `document.documentElement`; `system` lo **quita**, para que los
  tokens sigan `prefers-color-scheme` (`packages/ui/src/tokens/themes.css`:1-8, :65; SH21 a SH23).
- `bind-theme-to-session.ts`: una suscripción a `AccessSession` creada en `main.tsx` **antes** de `createRoot`. Aplica la
  preferencia **solo** cuando la vista es `authenticated` y quita `data-theme` en cualquier otra vista (`restoring`,
  `restore-failed`, `anonymous`). Así el arranque, el inicio de sesión, la verificación, la inscripción y la ruta no encontrada
  siguen siempre el tema del sistema (SH27), y tras cerrar sesión la preferencia sigue guardada y se vuelve a aplicar al iniciar
  sesión de nuevo (SH28). Escucha además el evento `storage` para seguir un cambio de tema hecho en otra pestaña.
- El selector vive en `UserMenuContainer` y solo existe en la parte autenticada (SH31, AW06).

**Primer pintado.** Como ninguna pantalla de arranque aplica la preferencia, el primer pintado siempre sigue el sistema y no hay
destello que evitar en él. El cambio al tema guardado ocurre en la transición de la restauración a la página autenticada, que ya
es un cambio de pantalla (SH24). Para que no se pinte un cuadro de la página autenticada con el tema del sistema, la suscripción
de `bind-theme-to-session` se registra antes que la de React: escribe `data-theme` en el mismo aviso de cambio de vista, antes de
que React confirme el render nuevo. Ningún código es un script en línea (SH30, AW13).

**Alternativas descartadas.**

- Aplicar la preferencia en `main.tsx` antes de montar React cuando existe la cookie CSRF: aplicaría el tema guardado a la
  pantalla de restauración, que es una pantalla de acceso (SH27).
- Script clásico externo y bloqueante en `<head>` (por ejemplo `public/theme-boot.js`): compatible con `script-src 'self'`, pero no
  aporta nada, porque el primer pintado debe seguir el sistema; además duplicaría la clave y la validación fuera de TypeScript.
- Tema solo en memoria (T2) o solo el del sistema (T3): incumplen D7 y P10.

### H13. La preferencia de tema no es un secreto, y la prueba de secretos lo sabe

**Elección.** `secrets.e2e.ts` no se debilita: sigue volcando `localStorage` y buscando centinelas. Se agrega una aserción de
**lista cerrada**: tras cada recorrido, la única clave de la aplicación en `localStorage` es `confia.admin.theme`, y su valor
pertenece a `system | light | dark` (SH29, BX123). Un token en `localStorage` sigue fallando (BX124), y un valor de tema fuera de
los tres estados también (BX125). `sessionStorage` e IndexedDB siguen sin claves de la aplicación.

## 6. Decisiones: API simulada, puerta y reglas

### H14. Modelo de inactividad y de vencimiento absoluto en la API simulada

**Elección.** En `e2e/mock-api/session-model.ts`:

| Elemento | Hoy | Con este cambio |
|---|---|---|
| Token de acceso | 15 min | **10 min** (`docs/03-seguridad.md`:515; SS188) |
| Inactividad | No existe | Cada familia guarda `lastUsedAt`; un refresco con `now - lastUsedAt >= 30 min` responde `401 session-expired` y revoca la familia (SS185). Solo el refresco mueve `lastUsedAt`; la lectura de `/current` no (SS186) |
| Absoluta | No existe | `createdAt + 12 h`, que ninguna rotación desplaza; vencida, el refresco responde `401 session-expired` (SS187) |
| `idleExpiresAt` y `sessionExpiresAt` | Fijos | Derivados de `lastUsedAt` y `createdAt` en toda respuesta, validados con `@confia/contracts/admin` (SS189) |
| Cabecera `Date` | La de Node (tiempo real) | La del reloj inyectado del modelo, para que el desfase sea controlable (SS189) |

Controles nuevos en `e2e/mock-api/server.ts`, con el mismo estilo que `__control/seed-session`:

- `POST /__control/clock` con `{ advanceMs }`: adelanta el reloj del modelo.
- `POST /__control/clock` con `{ skewMs }`: desplaza la cabecera `Date` y los plazos, para SS137 y SS138; con
  `{ dateHeader: "omit" | "invalid" }`, para SS139 y SS140.
- `seed-session?absoluteInMs=...`: siembra una sesión cuyo vencimiento absoluto llega pronto, para alcanzar el aviso absoluto sin
  simular 12 horas.
- Registro de peticiones por escenario (ya existe para SS19), que las pruebas usan para contar refrescos y `DELETE`.

En los recorridos, el reloj del navegador se mueve con `page.clock` (o `context.clock` con dos pestañas) y el del modelo con el
control; ningún recorrido espera 30 segundos o más de tiempo real (SS191). El modelo sigue declarándose simulación: no es evidencia
de la pila real (SS123, cambio 11).

### H15. Matriz de la puerta: 27 estados, 54 evaluaciones, con fixture de violación por estado nuevo

**Elección.** La matriz pasa de 22 a **27 estados**, cada uno evaluado con la preferencia del sistema emulada como `light` y
como `dark`: **54 evaluaciones** (BX109 precisado). Desglose:

| Pantalla | Estados | Cuenta |
|---|---|---|
| Arranque | `boot-restoring`, `boot-failed` | 2 |
| Página autenticada (existentes) | `signed-in-loading`, `signed-in-ready`, `signed-in-error` | 3 |
| Ruta no encontrada | `not-found` | 1 |
| Inicio de sesión | `initial`, `error-summary`, `credentials-rejected`, `rate-limited-countdown`, `service-busy`, `sending`, `password-visible`, `with-notice` | 8 |
| Verificación | `totp`, `recovery`, `code-rejected` | 3 |
| Inscripción | `loading`, `secret`, `recovery-codes`, `first-code`, `network-error` | 5 |
| **Subtotal 13b-1** | | **22** |
| Página autenticada (nuevos) | `user-menu-open`, `idle-warning`, `absolute-warning`, `theme-explicit-light`, `theme-explicit-dark` | **5** |
| **Total** | | **27** |

Los estados existentes `signed-in-*` **conservan su identificador**; solo cambia el contenido esperado de `signed-in-ready` (del
botón «Cerrar sesión» al `h1` del panel), porque el botón pasa al menú. El enlace de salto no es un estado propio de la matriz:
su visibilidad con foco y su tamaño los cubren la historia de `SkipLink` en la puerta de Storybook (DS120, DS122) y el recorrido
del shell (SH05 a SH07).

Cómo se alcanzan los estados nuevos:

| Estado | Arreglo | Contenido esperado antes de analizar |
|---|---|---|
| `user-menu-open` | Sembrar sesión y abrir el menú con el disparador | El panel visible con su `radiogroup` de tema (BX118 si nunca aparece) |
| `idle-warning` | `page.clock` instalado, sembrar sesión, avanzar el reloj controlado a 5 min del plazo, nunca con espera real (BX117) | `alertdialog` visible y fondo inerte |
| `absolute-warning` | `seed-session?absoluteInMs=...`, avanzar el reloj controlado a la antelación | `alertdialog` del vencimiento absoluto visible y fondo inerte |
| `theme-explicit-light` | Sembrar sesión, abrir el menú, elegir «claro» en el selector, cerrar el menú (BX116) | `data-theme="light"` en la raíz y fondo calculado igual al token claro, también con el sistema emulado en `dark` (BX114) |
| `theme-explicit-dark` | Igual, eligiendo «oscuro» | `data-theme="dark"` y fondo del token oscuro, también con el sistema emulado en `light` (BX114) |

`checkCombination` gana una comprobación opcional de fondo calculado antes de analizar, que usan los dos estados de tema
explícito. El tema explícito se establece **por el selector**, no escribiendo el atributo ni `localStorage` desde la prueba.

Fixtures de violación nuevas en `e2e/fixtures/a11y/`, una por estado nuevo (BX111), cada una con su prueba en
`deliberate-failures.e2e.ts`:

| Fixture | Estado que representa | Violación deliberada | Regla esperada |
|---|---|---|---|
| `disclosure-unnamed.html` | `user-menu-open` | Panel de divulgación abierto con un botón sin nombre accesible | `button-name` |
| `idle-dialog-unnamed.html` | `idle-warning` | `<dialog>` modal con `role="alertdialog"` sin nombre accesible | `aria-dialog-name` |
| `absolute-dialog-low-contrast.html` | `absolute-warning` | `<dialog>` modal de alerta con nombre, pero con texto de contraste insuficiente | `color-contrast` |
| `explicit-light-contrast.html` | `theme-explicit-light` | Contraste que falla solo con `data-theme="light"`, con el sistema en oscuro | `color-contrast` |
| `explicit-dark-contrast.html` | `theme-explicit-dark` | Contraste suficiente bajo el claro explícito e insuficiente bajo el oscuro explícito (BX115) | `color-contrast` |

Las dos últimas prueban que la puerta evalúa el atributo explícito y no solo la consulta de medios. La prueba de inventario
(`MINIMUM` en `a11y-matrix.ts`) suma los cinco estados, y la verificación de completitud hace fallar la puerta si un estado
declarado no se evalúa (BX112). Los cinco estados reales pasan en verde (BX113), y la puerta sigue fallando con cero rutas
(BX77). El criterio 8 sigue marcado (BX119).

En `packages/ui`, la puerta de historias evalúa además el diálogo abierto, el diálogo de alerta abierto y el panel del menú de
usuario abierto en los dos temas, cada uno con su historia de violación deliberada (DS126 a DS129).

### H16. Reglas de dependencia nuevas

- `admin-session-lifecycle-planner-is-pure` (dependency-cruiser): `session/lifecycle/planner.ts` y `policy.ts` no importan
  nada fuera de su carpeta; ni React, ni `shared/api`, ni el DOM.
- `admin-session-lifecycle-is-private`: fuera de `src/features/identity/` solo se importa `session/lifecycle/index.ts`.
- `ui-no-scroll-locking-overlays` (ESLint `no-restricted-imports` en `packages/ui`): prohíbe importar de `radix-ui` los
  primitivos que bloquean el desplazamiento (`Dialog`, `AlertDialog`, `DropdownMenu`, `Popover`, `Select`, `ContextMenu`,
  `Menubar`, `NavigationMenu`; BX127) y prohíbe importar `react-remove-scroll` y `react-remove-scroll-bar` (BX128), con un mensaje
  que cita el ADR de H17. `Checkbox` y `RadioGroup` siguen permitidos y la regla evalúa archivos reales (BX129). Una búsqueda en
  el código real de `packages/ui` y en el resultado de compilación de producción de `apps/admin-web` confirma que no hay
  referencias a esos primitivos ni a `react-remove-scroll` (BX130).

Cada regla nueva lleva su violación deliberada: las dos de dependency-cruiser en `tooling/dependency-fixtures/` con su caso en
`tooling/src/dependency-rules.test.ts`, como las existentes; la de ESLint con un archivo de violación por cada primitivo prohibido
y por cada paquete de bloqueo de desplazamiento, excluido de la construcción.

### H17. ADR de superposiciones nativas bajo CSP estricta

**Elección.** ADR nuevo en `docs/adr/` (próximo número libre al escribirlo; hoy el último en este árbol es ADR-0028), indexado en
`docs/adr/README.md`, estado «Aceptado» tras la revisión del propietario: las superposiciones modales y los menús de
`packages/ui` se construyen sobre elementos nativos (`<dialog>` y divulgación) mientras la CSP sea `style-src 'self'` sin
*nonce*; Radix sigue para los átomos que no inyectan estilos. Cita la evidencia de la sonda de la sección 1. Se revisa cuando el
cambio 11 introduzca el *nonce*. ADR-0026 y ADR-0028 no se modifican (BX126).

## 7. Flujo de datos

**Extensión por actividad.**

```
keydown ─► activity-detector ─(authenticated, visible, sin aviso abierto, now-lastRefreshAt >= 60 s)─► extend()
                                                                                   │
                       coordinator.refreshOnce() ─(Web Lock)─► POST /sessions/refresh
                                                                                   │ 200 + Date
   token-store.accept ◄── server-clock.observe(Date) ──► deadline-store.replace(...) ┘
                                          │
                       scheduler.replan() ┤──► session-channel.post({ type: "deadlines", ... })
```

**Aviso de inactividad y cierre por vencimiento.**

```
scheduler: wakeAt alcanzado ─► planner ─► idle-warning ─► IdleWarning (AlertDialog.showModal())
  (sin petición)                                              │
                     «Continuar» o Escape ─► extend() ─► running (diálogo se cierra, foco al origen)
                     reloj en el plazo ─► planner ─► expired ─► endByTimeout("idle")
                                                                 ├─► endLocally("session-expired")
                                                                 ├─► channel: signed-out (expired)
                                                                 └─► DELETE (mejor esfuerzo, sin esperar)
```

**Aviso absoluto.**

```
planner ─► absolute-warning ─► AbsoluteExpiryWarning (AlertDialog.showModal())
              «Entendido» o Escape ─► scheduler.dismissAbsolute(S) ─► absolute-acknowledged (sin petición)
              «Cerrar sesión ahora» ─► signOut()
              reloj en S ─► expired ─► endByTimeout("absolute")
```

**Reevaluación.** El programador vuelve a planificar en: cambio del almacén de plazos, cambio de la vista, `visibilitychange`
a `visible` y `pageshow`. Un temporizador retrasado por el segundo plano o por la suspensión no importa: al volver, el plan se
recalcula con instantes absolutos; si el plazo ya pasó, la fase es `expired` directamente y el aviso nunca aparece (SS135,
SS136).

**Cierre en otra pestaña.** Pestaña A: `signOut()` correcto ─► `signed-out` (`person`) al canal. Pestaña B con sesión completa:
esquema válido ─► `endLocally()` ─► `BootGate` limpia la caché de consultas ─► la guarda lleva al inicio de sesión sin aviso. La
pestaña B no emite `DELETE` ni reenvía el mensaje.

## 8. Archivos

Ubicación en `packages/ui` según `docs/ui-ux/02` §3: componentes genéricos sin dominio en `molecules/`, el enlace de salto en
`atoms/` y la plantilla en `layouts/`.

| Archivo | Acción | Descripción |
|---|---|---|
| `src/shared/api/http.ts` | Modifica | `serverDate` y `receivedAt` en los resultados `ok` (AW65 a AW67) |
| `src/shared/api/auth-operations.ts` | Modifica | Propaga los campos nuevos sin cambiar firmas de entrada |
| `src/features/identity/session/deadline-store.ts` | Crea | Plazos locales no secretos y `lastRefreshAt` (H1) |
| `src/features/identity/session/access-session.ts` | Modifica | `extend`, `endByTimeout`, `applyFromOtherTab`, `getDeadlines`, `subscribeDeadlines`; registro de plazos en toda respuesta; comentario del invariante precisado |
| `src/features/identity/session/refresh-coordinator.ts` | Modifica | `refreshed` lleva `serverDate` y `receivedAt`; comentario del invariante precisado |
| `src/features/identity/session/index.ts` | Modifica | Crea y conecta el programador, el detector y el canal con la sesión de la pestaña |
| `src/features/identity/session/lifecycle/policy.ts` | Crea | Constantes con nombre (H2) |
| `src/features/identity/session/lifecycle/planner.ts` | Crea | Planificador puro (H2) |
| `src/features/identity/session/lifecycle/server-clock.ts` | Crea | Último desfase válido y conversión de plazos (H3) |
| `src/features/identity/session/lifecycle/scheduler.ts` | Crea | Un solo temporizador con `timers` inyectados, descarte del aviso absoluto y reevaluación (sección 7) |
| `src/features/identity/session/lifecycle/activity-detector.ts` | Crea | H5 |
| `src/features/identity/session/lifecycle/session-channel.ts` | Crea | `BroadcastChannel` y esquema estricto de dos tipos (H7) |
| `src/features/identity/session/lifecycle/index.ts` | Crea | Superficie pública del módulo (`usePhase`, tipos) |
| `src/features/identity/session-warnings/SessionWarningsContainer.tsx` | Crea | H9 |
| `src/features/identity/session-warnings/components/{IdleWarning,AbsoluteExpiryWarning}.tsx` | Crea | Presentaciones puras con sus pruebas de estados (`// States.`, AW57) |
| `src/features/identity/routes.tsx` | Modifica | Layout `authenticated`, índice con el panel; AW59, AW72 |
| `src/features/identity/signed-in/**` | Elimina | Reemplazado por el shell; sus escenarios se trasladan (sección 11) |
| `src/features/shell/AuthenticatedLayout.tsx` | Crea | Usa `AppShell`, conecta menú y avisos; foco al `h1` |
| `src/features/shell/welcome/{WelcomeContainer,components/WelcomePanel}.tsx` | Crea | Panel sin acción (D2) con sus estados de lectura |
| `src/features/shell/user-menu/UserMenuContainer.tsx` | Crea | Etiqueta genérica, selector de tema y cierre de sesión |
| `src/features/shell/theme/{theme-preference,apply-theme,bind-theme-to-session}.ts` | Crea | H12 |
| `src/main.tsx` | Modifica | `bindThemeToSession(accessSession)` antes de `createRoot` |
| `src/app/structure.test.ts` | Modifica | La capacidad `shell` en la lista de carpetas de primer nivel |
| `src/app/docs-notes.test.ts` | Modifica | Notas de 13b-2 sin quitar las anteriores (BX122) |
| `src/i18n/locales/es-HN/admin.json` | Modifica | Claves `shell.*`, `theme.*` y `sessionWarning.*`, en «usted» (SH32 a SH34) |
| `packages/ui/src/molecules/dialog/{NativeDialog,Dialog,AlertDialog}.tsx` | Crea | H8, con historias y pruebas |
| `packages/ui/src/molecules/user-menu/{UserMenu,use-disclosure}.ts(x)` | Crea | H10 |
| `packages/ui/src/molecules/page-header/PageHeader.tsx` | Crea | `h1` con `tabIndex -1` y `ref` por propiedad, descripción y acciones opcionales (DS118, DS119) |
| `packages/ui/src/molecules/topbar/Topbar.tsx` | Crea | Región `banner` con zona de etiqueta y zona de acciones (DS116) |
| `packages/ui/src/atoms/skip-link/SkipLink.tsx` | Crea | Oculto sin foco, visible con foco; mueve el foco sin tocar la URL (DS120, DS121) |
| `packages/ui/src/layouts/app-shell/AppShell.tsx` | Crea | Plantilla: enlace de salto, barra y un único `main` (DS131) |
| `packages/ui/src/styles.css` (o la hoja de tokens) | Modifica | Bloqueo de desplazamiento con `:has`, `::backdrop`, foco del enlace de salto |
| `packages/ui/src/index.ts`, `public-api.test.ts` | Modifica | DS02 y DS88 con el conjunto exacto; DS89 se invierte |
| `packages/ui/src/i18n/**` | Modifica | Claves genéricas de componente, sin dirigirse a la persona (DS124, DS125) |
| `packages/ui/eslint.config.*` y archivos de violación deliberada | Modifica / Crea | `ui-no-scroll-locking-overlays` (H16) |
| `.dependency-cruiser.cjs`, `tooling/dependency-fixtures/**`, `tooling/src/dependency-rules.test.ts` | Modifica | H16 |
| `e2e/mock-api/{session-model,server,scenarios}.ts` y sus pruebas | Modifica | H14 |
| `e2e/support/{a11y-matrix,a11y-report,a11y-gate,fixtures}.ts` | Modifica | H15 |
| `e2e/fixtures/a11y/*.html` | Crea | Cinco fixtures de H15 |
| `e2e/specs/a11y/deliberate-failures.e2e.ts` | Modifica | Una prueba por fixture nueva |
| `e2e/specs/journeys/{signed-in,boot,secrets}.e2e.ts` | Modifica | Inversiones (sección 11) y H13 |
| `e2e/specs/journeys/{shell,session-lifecycle,tabs,theme}.e2e.ts` | Crea | Recorridos nuevos de SS190 (sección 12) |
| `e2e/specs/journeys/journeys-inventory.test.ts` | Modifica | Inventario con los archivos nuevos |
| `docs/09-roadmap-y-fases.md`, `docs/ui-ux/04-patrones-de-interaccion.md`, `docs/ui-ux/01-*.md` | Modifica | Notas fechadas (BX120, BX121) |
| `docs/adr/ADR-00NN-superposiciones-nativas-bajo-csp.md`, `docs/adr/README.md` | Crea / Modifica | H17 |
| `apps/api/**` | Sin cambios | |

**Conjunto de exportaciones de `packages/ui` tras el cambio** (DS02): los diecinueve componentes P0 anteriores, los siete nuevos
(`Dialog`, `AlertDialog`, `SkipLink`, `PageHeader`, `Topbar`, `UserMenu`, `AppShell`) y los dos compañeros anteriores
(`RadioGroupItem`, `buttonVariants`): veintiocho nombres. Los componentes nuevos no tienen compañeros de composición públicos;
`NativeDialog` y `use-disclosure` son internos. `DropdownMenu` no se exporta.

## 9. Interfaces

```ts
// shared/api/http.ts
export type ApiResult<T> =
  | { readonly kind: "ok"; readonly status: number; readonly data: T;
      /** Date.parse of the Date header; undefined when absent, empty or not a valid HTTP date. */
      readonly serverDate: number | undefined;
      /** Local Date.now() when the response arrived. */
      readonly receivedAt: number }
  | { readonly kind: "problem"; readonly problem: ApiProblem }
  | { readonly kind: "network" }
  | { readonly kind: "unexpected-response"; readonly status: number };

// session/lifecycle/server-clock.ts
export interface ServerClock {
  /** Updates the offset only when serverDate is a valid instant (SS140). */
  observe(serverDate: number | undefined, receivedAt: number): void;
  /** Server ISO instant to local milliseconds, with the last valid offset (zero before any). */
  toLocal(serverInstant: string): number;
}

// session/deadline-store.ts
export interface LocalDeadlines {
  readonly idleExpiresAt: number;    // local ms
  readonly sessionExpiresAt: number; // local ms
  readonly lastRefreshAt: number | undefined; // local ms of this tab's last refresh
}

// session/lifecycle/planner.ts
export interface LifecyclePolicy {
  readonly idleWarningLeadMs: number;     // 5 min
  readonly absoluteWarningLeadMs: number; // 5 min
}
export interface PlanInput {
  readonly deadlines: LocalDeadlines | undefined;
  readonly now: number;
  /** The sessionExpiresAt for which the person dismissed the absolute warning, if any. */
  readonly absoluteDismissedFor: number | undefined;
}
export type LifecyclePhase =
  | { readonly kind: "inactive" }
  | { readonly kind: "running" }
  | { readonly kind: "idle-warning"; readonly endsAt: number }
  | { readonly kind: "absolute-warning"; readonly endsAt: number }
  | { readonly kind: "absolute-acknowledged"; readonly endsAt: number }
  | { readonly kind: "expired"; readonly reason: "idle" | "absolute" };
export function planLifecycle(
  input: PlanInput, policy: LifecyclePolicy,
): { readonly phase: LifecyclePhase; readonly wakeAt: number | undefined };

// session/lifecycle/scheduler.ts
export interface Timers {
  setTimeout(callback: () => void, ms: number): number;
  clearTimeout(handle: number): void;
}
export interface LifecycleScheduler {
  subscribe(listener: () => void): () => void;
  /** Same object until the phase or the visible remaining second changes. */
  getSnapshot(): { readonly phase: LifecyclePhase; readonly remainingMs: number | undefined };
  dismissAbsoluteWarning(): void;
  replan(): void;
  dispose(): void;
}

// session/lifecycle/session-channel.ts
export type SessionChannelMessage =
  | { readonly v: 1; readonly type: "signed-out"; readonly reason: "person" | "expired" }
  | { readonly v: 1; readonly type: "deadlines"; readonly idleExpiresAt: string; readonly sessionExpiresAt: string };

// session/access-session.ts (additions)
export type ExtendOutcome = { readonly kind: "extended" } | { readonly kind: "network" } | { readonly kind: "ended" };
export interface AccessSession {
  // ...existing operations unchanged
  extend(): Promise<ExtendOutcome>;
  endByTimeout(reason: "idle" | "absolute"): void;
  /** Applies a validated channel message when the session is complete; never sends a request nor re-posts. */
  applyFromOtherTab(message: SessionChannelMessage): void;
  getDeadlines(): LocalDeadlines | undefined;
  subscribeDeadlines(listener: () => void): () => void;
}

// packages/ui
export interface DialogProps {
  readonly open: boolean;
  readonly onDismiss: () => void;
  readonly title: string;       // required (DS100)
  readonly description: string; // required (DS100)
  readonly initialFocusRef?: React.RefObject<HTMLElement | null>;
  readonly children: React.ReactNode;
}
export type AlertDialogProps = DialogProps;

export interface UserMenuProps {
  readonly triggerLabel: string;     // generic accessible name (D1, DS117)
  readonly userLabel: string;        // generic label shown as text
  readonly themeGroup: React.ReactNode; // a RadioGroup supplied by the app
  readonly signOutLabel: string;
  readonly onSignOut: () => void;
  readonly signingOut: boolean;      // aria-busy on the trigger, never disabled
}

export interface AppShellProps {
  readonly skipLinkLabel: string;
  readonly mainId: string;
  readonly topbar: React.ReactNode;
  readonly children: React.ReactNode; // main content
}
```

## 10. Seguridad y amenazas

**Matriz de amenazas de `sdd-design`:** N/A. El cambio no toca comandos de consola, subprocesos, automatización de control de
versiones ni de PR, clasificación de ejecutables ni integración de procesos; las rutas que cambian son rutas de cliente de
TanStack Router, no enrutamiento de procesos.

Consideraciones propias del cambio, cada una con su prueba (sección 12):

| Superficie | Riesgo | Control |
|---|---|---|
| `BroadcastChannel` | Fuga de token o de datos personales; mensaje falsificado desde otra pestaña del mismo origen | Esquema estricto de dos tipos sin campos de secreto; centinelas y búsqueda de forma JWT (SS177); un mensaje falsificado solo puede cerrar la sesión local o cambiar plazos locales, nunca abrir ni extender la sesión en el servidor |
| `localStorage` | Que se use para algo más que el tema | Lista cerrada de claves (H13) |
| Temporizadores | Refresco sin intención de la persona | Ningún temporizador llama a `extend()`; SS19 y SS150 con reloj controlado sin actividad |
| CSP | Estilos o scripts inyectados | Superposiciones nativas (H8, H10), regla H16, DS130, AW68, AW69 y AW12 en todos los recorridos |
| Registro | Contenido del canal en la consola | El canal descarta en silencio; ninguna llamada a `console` en el módulo |

## 11. Pruebas y escenarios que cambian de signo

Regla de ADR-0018: se invierten o se precisan con nota fechada que cite este cambio; **no se borran** los escenarios ni sus
identificadores. Cuando un archivo de prueba desaparece con su componente (`signed-in/**`), sus escenarios se trasladan al
archivo nuevo con el mismo identificador.

| Id | Dónde vive hoy | Hoy | Con este cambio | PR |
|---|---|---|---|---|
| SS19 | `e2e/specs/journeys/signed-in.e2e.ts`:55; `refresh-coordinator.test.ts`:166; `session-secrets.flow.test.tsx`:48 | Con 60 min de reloj no hay peticiones | **Se precisa:** sin actividad no hay refrescos; la única petición por tiempo es el `DELETE` del cierre por vencimiento | 1 (unidad), 6 (e2e) |
| SS124 | `signed-in.e2e.ts`:96 | La página no detecta el vencimiento sola | **Se invierte:** aparece el aviso y la sesión termina al vencer | 6 |
| SS125 | `signed-in.e2e.ts`:112 | No hay diálogo ni región `assertive` | **Se invierte** | 6 |
| SS126 | `signed-in.e2e.ts`:122 | La otra pestaña sigue con sesión; no hay refresco por actividad | **Se invierte:** hay sincronización entre pestañas y detección de actividad | 2 (unidad), 6 (e2e) |
| SS103 a SS112 | `signed-in/**` y `signed-in.e2e.ts` | Página mínima con botón de cierre | Requisito renombrado: el panel confirma la sesión y el cierre vive en el menú | 4 |
| AW05 | `e2e/specs/journeys/boot.e2e.ts`:32 | Sigue al sistema, sin `data-theme` en la raíz | **Se precisa:** sin preferencia guardada y en las pantallas de acceso no hay `data-theme`; «sistema» es el estado predeterminado | 5 |
| AW06 | `boot.e2e.ts`:56 | No hay control de tema | **Se invierte:** existe en la aplicación autenticada y no en las pantallas de acceso | 5 |
| AW19 | `routes.test.tsx` | No hay `redirect` | **Se conserva**, con nota de que 13b-2 lo revisó | 4 |
| AW59 | `routes.test.tsx`:81; comentario de `routes.tsx`:8 | Las únicas rutas son las de 13b-1 | **Se actualiza** al árbol con layout protegido sin segmento y el panel en `/` | 4 |
| AW60 | Pruebas de ausencia de 13b-1 | No existen `Topbar`, `PageHeader` ni `Dialog` | **Se invierte en parte:** existen `Topbar`, `UserMenu`, `PageHeader` y `Dialog`; `Sidebar` y `Breadcrumb` siguen ausentes | 3, 4 |
| DS02 | `packages/ui/src/public-api.test.ts` | Veintiún nombres | **Se precisa:** veintiocho nombres; `DropdownMenu` no está | 3 |
| DS88 | `public-api.test.ts` | Importa `FormErrorSummary` y `Alert` | **Se actualiza:** nueve componentes, con los siete nuevos | 3 |
| DS89 | `public-api.test.ts`:50 | `Dialog` no existe | **Se invierte:** `Dialog` existe; `Sidebar` y `Breadcrumb` siguen ausentes | 3 |
| BX109 | `e2e/support/a11y-matrix.ts`:52 | 22 estados, 44 evaluaciones | **Se precisa:** 27 estados, 54 evaluaciones; `signed-in-ready` cambia su contenido esperado | 4, 5, 6 |
| Comentarios de invariante | `access-session.ts`:100; `refresh-coordinator.ts`:50 | «Sin temporizador» | Texto precisado, con referencia a este diseño | 1 |
| `secrets.e2e.ts` | `:93-133` | Vuelca `localStorage` | Se conserva y suma la lista cerrada de claves (H13; BX123 a BX125) | 5 |
| `docs-notes.test.ts` | `src/app/` | Notas de 13b-1 | Suma las de 13b-2 (BX122) | 7 |

## 12. Estrategia de pruebas

| Capa | Qué se prueba | Cómo |
|---|---|---|
| Unidad, cliente HTTP | `Date` válida, ausente, vacía, `abc` e imposible (AW65 a AW67) | Vitest con respuestas falsas |
| Unidad, planificador | Cada fila de la tabla de H2 en el límite exacto, 1 ms antes y 1 ms después; precedencia del absoluto con las dos ventanas abiertas (SS175); descarte que vale para un `S` y deja de valer si cambia; sin plazos; `wakeAt` por segundo en las fases con diálogo | Vitest, tablas de casos, sin temporizadores |
| Unidad, reloj del servidor | Desfase positivo, negativo y nulo; cabecera ausente; cabecera inválida tras una válida conserva el desfase (SS137 a SS140) | Vitest, funciones puras |
| Unidad, programador | A lo sumo un temporizador pendiente (SS141); reprograma al cambiar plazos; `visibilitychange` y `pageshow` recalculan; tras un salto de 27 min muestra 3 min restantes y tras 31 min pasa directo a `expired` (SS135, SS136); sin actividad no llama a `extend` | `vi.useFakeTimers` y `timers` inyectados |
| Unidad, detector | Cuentan tecla, puntero, rueda y desplazamiento; el movimiento no cuenta; ráfaga dentro de 60 s hace un refresco; a los 60 s otro; nada en segundo plano, sin sesión completa ni con un aviso abierto (SS142 a SS152, SS174) | Objetivo de eventos falso |
| Unidad, canal | Acepta los dos tipos; rechaza tipo desconocido, instante inválido, campo extra y no objeto, sin excepción; no reenvía; pestaña sin sesión ignora; ningún mensaje producido contiene un centinela ni forma de JWT (SS177 a SS184) | Esquema Zod; `BroadcastChannel` falso |
| Unidad, `AccessSession` | `extend` con los cuatro desenlaces del coordinador; `endByTimeout` cierra antes del `DELETE` y no lo espera; los plazos de la última respuesta reemplazan aunque sean más tempranos (SS128 a SS133); mostrar un aviso no llama a la API | Doble de `AccessApi` y bloqueos falsos, como las pruebas actuales |
| Unidad, tema | Valores válidos e inválidos; `localStorage` que lanza; `system` quita el atributo; toda vista distinta de `authenticated` lo quita | jsdom |
| Componentes, `packages/ui` | `Dialog` y `AlertDialog` (DS99 a DS109, DS130); `UserMenu` con la tabla de teclado de H10 y sin roles de menú (DS110 a DS115); `Topbar`, `PageHeader`, `SkipLink`, `AppShell` (DS116 a DS123, DS131) | **Vitest en modo navegador (Chromium)**, porque jsdom no implementa `showModal()`; historias en los dos temas dentro de la puerta de Storybook (DS126 a DS129) |
| Componentes, `apps/admin-web` | Presentaciones de avisos y panel con sus estados declarados (`// States.`, AW57); contenedores con sesión falsa | Testing Library |
| Reglas de dependencia y lint | Las tres reglas de H16 con su violación deliberada (BX127 a BX130) | `tooling/src/dependency-rules.test.ts` y ESLint sobre archivos de violación |
| API simulada | Inactividad, absoluta, 10 min de token, `Date` del reloj inyectado, contrato (SS185 a SS189) | `session-model.test.ts`, `server.test.ts` |
| E2E | Shell: puntos de referencia, enlace de salto, menú con teclado y clic fuera, cierre de sesión desde el menú (SH01 a SH19, SH38, SH39). Vida de la sesión: aviso de inactividad con «Continuar» y Escape, cierre al vencer con un solo `DELETE`, aviso absoluto con «Entendido» y sin «Continuar», precedencia, actividad con a lo sumo un refresco por cadencia, suspensión simulada, desfase. Pestañas: cierre sincronizado con y sin vencimiento, plazos compartidos que cierran el aviso, sin token en los mensajes capturados. Tema: persiste al recargar, no aparece ni se aplica en acceso (SH20 a SH31). CSP: AW68, AW69 y AW12 en todos (SS190, SS191) | Playwright, `page.clock`/`context.clock`, controles de H14 |
| Puerta de accesibilidad | 27 estados en dos emulaciones (54 evaluaciones) y cinco fixtures nuevas que fallan (BX109, BX111 a BX118) | `gate.e2e.ts`, `deliberate-failures.e2e.ts` |

Cobertura: el umbral global de 80 % se mantiene; el planificador, el reloj del servidor y el canal son lógica pura y se espera
cobertura completa de ramas, aunque no son paquete `domain` del backend.

## 13. Secuencia de entrega

Cadena de PR apilados hacia `main` (`stacked-to-main`, convención del proyecto), con tope de 800 líneas efectivas por PR
(`docs/15` §3) y presupuesto de revisión de 400. Las cifras son pronósticos, no mediciones; incluyen pruebas y excluyen
lockfile y código generado. La decisión de entrega final la toma `sdd-tasks`.

| PR | Contenido | Pronóstico | Riesgo frente a 800 |
|---|---|---|---|
| 1 | `http.ts` con `serverDate`; `deadline-store`; `server-clock`; `planner`; `policy`; `scheduler`; `extend` y `endByTimeout` en `AccessSession`; SS19 precisado en unidad | 650-800 | Medio |
| 2 | `activity-detector`; `session-channel`; conexión en `session/index.ts`; API simulada (H14) con sus pruebas | 600-800 | Medio |
| 3 | `packages/ui`: `Dialog`, `AlertDialog`, `SkipLink`, `PageHeader`, `Topbar`, `UserMenu`, `AppShell`, historias con sus violaciones deliberadas, pruebas en navegador, claves `ui`; DS02, DS88, DS89; regla ESLint y archivos de violación de H16; ADR de H17 | 800-1 000 | **Alto**: se parte de antemano en 3a (diálogos, regla ESLint, ADR) y 3b (`SkipLink`, `PageHeader`, `Topbar`, `UserMenu`, `AppShell`) |
| 4 | Shell: layout `authenticated` sobre `AppShell`, panel de bienvenida, menú con cierre de sesión, retiro de `signed-in/**`, AW19, AW59, AW60, AW72, estado `user-menu-open` con su fixture, claves `shell.*` | 550-750 | Medio |
| 5 | Tema: preferencia, `bind-theme-to-session`, selector en el menú, AW05, AW06, H13, estados `theme-explicit-*` con sus fixtures, `theme.e2e.ts` | 450-650 | Bajo |
| 6 | Avisos: contenedor y presentaciones, conexión con el programador, SS124 a SS126 y SS19 e2e, estados `idle-warning` y `absolute-warning` con sus fixtures, `session-lifecycle.e2e.ts` y `tabs.e2e.ts` | 750-950 | **Alto**: si excede, los recorridos de pestañas pasan a un PR 6b |
| 7 | Notas fechadas en `docs/09`, `docs/ui-ux/04`, `docs/ui-ux/01`; `docs-notes.test.ts` | 150-300 | Bajo |

**Total:** de 3 950 a 5 250 líneas, dentro del rango realista de la propuesta. Con la partición de 3 son 8 PR, y 9 si también se
parte el 6; hay que contarlo frente al objetivo D11 (de 5 a 7 PR) y al tope de 15 tareas.

Dependencias del orden: el PR 6 necesita el 1, el 2 y el 3; el 5 necesita el 4 (el selector vive en el menú); el 4 necesita
el 3. Los PR 1 y 2 no tocan la interfaz y pueden revisarse mientras se escribe el 3.

**Criterio 8.** Desde el PR 4, cada PR que agrega una pantalla o un estado agrega en el mismo PR su estado en la matriz y su
fixture. Ningún PR deja una pantalla autenticada sin evaluar.

- `Decision needed before apply: Yes`
- `Chained PRs recommended: Yes`
- `400-line budget risk: High`

## 14. Migración y reversión

No hay migración de datos, ni entorno desplegado, ni cambios en `apps/api`.

1. Cada PR se revierte con su commit de fusión, en orden inverso de la cadena.
2. Revertir el PR 6 deja el núcleo funcionando sin interfaz: el programador sigue cerrando la sesión al vencer, pero sin aviso
   previo. SS124 a SS126 vuelven a su texto anterior con nota fechada.
3. Revertir el PR 4 restaura `signed-in/**` y el botón de cierre de la página mínima; AW59, AW60 y `signed-in-ready` vuelven a su
   texto. Exige revertir antes el 5 y el 6.
4. Revertir los PR 1 y 2 exige revertir antes el 6.
5. Revertir el PR 3 exige revertir antes el 4, el 5 y el 6; DS89 vuelve a aseverar la ausencia de `Dialog` y DS02 vuelve a
   veintiún nombres.
6. **Si una reversión deja una pantalla autenticada sin evaluar por la puerta, el criterio 8 se desmarca en `docs/09` con nota
   fechada en el mismo commit.**
7. La clave `confia.admin.theme` en `localStorage` es inocua tras revertir el PR 5: la aplicación vuelve a seguir el sistema y
   nadie lee la clave. El canal `confia.admin.session` deja de tener emisores y oyentes.

## 15. Preguntas abiertas

- [ ] Número del ADR de H17: confirmar el próximo número libre al escribirlo, porque la rama de backend en curso también puede
      reservar números.
- [ ] Textos nuevos (aviso absoluto, «Entendido», «Cerrar sesión ahora», shell y tema): los fija el catálogo `admin` en «usted»;
      solo el del aviso de inactividad está fijado por la documentación.
- [ ] Con 8 o 9 PR, la entrega supera el objetivo D11 de 5 a 7; `sdd-tasks` debe decidir si se acepta o si se divide el cambio.
- [ ] El requisito MODIFIED de `design-system` enumera las carpetas `tokens/`, `atoms/`, `molecules/`, `domain/` y `hooks/`, sin
      `layouts/`, que sí figura en `docs/ui-ux/02` §3. `AppShell` va a `layouts/` según el documento; conviene que la
      especificación agregue la carpeta en una nota fechada.
