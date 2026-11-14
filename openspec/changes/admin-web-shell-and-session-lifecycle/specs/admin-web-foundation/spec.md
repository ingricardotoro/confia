# Delta para Fundación de `apps/admin-web`

- **Estado:** delta inicial del cambio, el 2026-11-10, tras la aprobación de la propuesta por el propietario
- **Fase:** F0 (cambio 13b-2)
- **Cambio:** `admin-web-shell-and-session-lifecycle`
- **Propuesta:** `proposal.md`, aprobada por el propietario el 2026-11-10

## Propósito

Ajusta la fundación de `apps/admin-web` a la llegada del shell: el tema deja de ser solo del sistema (el modo «sistema» pasa a ser el predeterminado de un
selector), el enrutador gana un layout protegido, el cliente de API expone la fecha del servidor para corregir el desfase de reloj, y la lista de ausencias
se reduce a lo que sigue sin dueño entregado. Registra además el resultado de la sonda de CSP que decide la implementación del diálogo.

## Convenciones de este delta

- **Cómo se fusiona este delta.** Se archiva después de `admin-web-foundations-and-session` y `admin-web-staff-sign-in`. Los requisitos bajo
  `## ADDED Requirements` son nuevos. Los requisitos bajo `## MODIFIED Requirements` traen el cuerpo completo y **todos** sus escenarios (los que no
  cambian se copian sin alteración) y reemplazan al requisito vigente. `RENAMED` precede a `MODIFIED` porque el requisito renombrado se modifica bajo su
  nombre nuevo.
- Los escenarios nuevos se numeran `AW65` en adelante, a continuación del máximo vigente (`AW64`). Los escenarios invertidos o precisados conservan su
  identificador (ADR-0018) y llevan una nota fechada con el texto anterior; ninguno se borra.
- Los valores fijados por el diseño son los de la especificación de 13b-1, incluida la CSP de los recorridos
  (`default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self' data: blob:; font-src 'self'; connect-src 'self'; form-action 'self';
  frame-ancestors 'none'; base-uri 'none'; object-src 'none'; worker-src 'self' blob:; manifest-src 'self'`).
- **Sonda de CSP (D12, resuelta).** Una sonda local y desechable en Chromium, con la CSP de `preview`, mostró que `Dialog` y `DropdownMenu` de Radix (por
  `react-remove-scroll-bar`) producen `securitypolicyviolation`. Por eso el aviso usa `<dialog>` nativo con `showModal()` y el menú de usuario es un botón de
  divulgación, sin relajar la política. El
  resultado se registra en `design.md`.

## ADDED Requirements

### Requisito: El cliente de API expone la fecha del servidor de cada respuesta para corregir el desfase de reloj

El sistema DEBE exponer, en el resultado que el cliente de `src/shared/api` devuelve para una respuesta recibida, el instante de la cabecera `Date` del
servidor como un valor ya interpretado o su ausencia. Una cabecera `Date` ausente o no interpretable como fecha HTTP DEBE producir la ausencia del valor,
nunca `NaN`, una fecha inválida ni un error. Esta exposición NO agrega cabeceras a las seis operaciones ni cambia las rutas, los cuerpos ni los esquemas.
El cliente sigue siendo el único lugar con `fetch`.

#### Escenario: [AW65] El resultado expone la fecha del servidor

- **DADO** una respuesta con la cabecera `Date: Tue, 10 Nov 2026 10:00:00 GMT`
- **CUANDO** el cliente la procesa
- **ENTONCES** el resultado expone el instante correspondiente a las 10:00:00 UTC de ese día

#### Escenario: [AW66] Sin `Date` la fecha del servidor está ausente

- **DADO** una respuesta sin cabecera `Date`
- **CUANDO** el cliente la procesa
- **ENTONCES** el resultado indica que no hay fecha del servidor, y el resto del resultado no cambia

#### Escenario: [AW67] Una `Date` inválida produce ausencia

- **DADO** respuestas con `Date: abc`, con `Date` vacía y con una fecha imposible
- **CUANDO** el cliente las procesa
- **ENTONCES** las tres indican que no hay fecha del servidor, sin excepción, sin `NaN` y sin mensaje de error en la consola

### Requisito: El diálogo y el menú del shell son compatibles con la CSP y la sonda queda registrada antes del diseño

El sistema DEBE implementar el diálogo y el menú desplegable de modo que, bajo la CSP estricta de `preview` y sin `'unsafe-inline'` ni `'unsafe-eval'`, abrirlos,
usarlos y cerrarlos no dispare ni una vez el evento `securitypolicyviolation` y ninguna petición ni recurso quede bloqueado. NO DEBE relajarse la política
para acomodarlos. El resultado de la sonda de CSP DEBE quedar registrado en `design.md` antes de cerrar el diseño: la implementación elegida (`<dialog>` nativo y
divulgación en lugar de Radix `Dialog` y `DropdownMenu`), la evidencia que la sustenta y la referencia al ADR que la registra.

#### Escenario: [AW68] El diálogo abierto no viola la CSP

- **DADO** `vite preview` con la CSP descrita y el aviso de inactividad que se abre, se confirma con «Continuar» y se vuelve a abrir
- **CUANDO** corre el recorrido
- **ENTONCES** el evento `securitypolicyviolation` no se dispara ni una vez y ninguna petición queda bloqueada por la CSP

#### Escenario: [AW69] El menú de usuario abierto no viola la CSP

- **DADO** `vite preview` con la CSP descrita y el menú de usuario que se abre con el teclado y con el ratón, y se cierra con `Escape` y con un clic fuera
- **CUANDO** corre el recorrido
- **ENTONCES** el evento `securitypolicyviolation` no se dispara ni una vez

#### Escenario: [AW70] La sonda registra su resultado antes del diseño

- **DADO** `openspec/changes/admin-web-shell-and-session-lifecycle/design.md`
- **CUANDO** se lee
- **ENTONCES** contiene el resultado de la sonda de CSP: la implementación elegida (`<dialog>` nativo y divulgación), la evidencia (violaciones observadas de Radix) y la
  confirmación de que la CSP no se relajó

#### Escenario: [AW71] La política de los recorridos no se relaja

- **DADO** la configuración de `preview` y la de los recorridos tras el cambio
- **CUANDO** se compara la CSP con la de las convenciones de este delta
- **ENTONCES** es idéntica: sin `'unsafe-inline'`, sin `'unsafe-eval'` y sin ampliar `style-src` ni `script-src`

## RENAMED Requirements

### Requisito: La aplicación sigue el tema claro u oscuro del sistema operativo y no ofrece selector de tema → La aplicación sigue el tema del sistema por defecto y solo la parte autenticada ofrece un selector de tema

(Reason: el cambio 13b-2 entrega el selector visible de tema que P10 había reservado; «sistema» pasa a ser el estado predeterminado del selector.)
(Migration: AW05 se precisa y AW06 se invierte con nota fechada; el comportamiento del selector se especifica en `admin-web-shell`.)

### Requisito: Ausencia del shell, del selector de tema, de `Dialog` y de las demás exclusiones (brecha con destino: cambios 13b-2 y 11) → Ausencia de la barra lateral, las migas y de las demás exclusiones (brecha con destino: primer módulo y cambio 11)

(Reason: el shell con barra superior, el selector de tema y `Dialog` los entrega el cambio 13b-2; permanece ausente lo que tiene otro dueño.)
(Migration: AW59 y AW60 se actualizan con nota fechada; AW61 a AW63 se conservan sin cambios.)

## MODIFIED Requirements

### Requisito: La aplicación sigue el tema del sistema por defecto y solo la parte autenticada ofrece un selector de tema

El sistema DEBE aplicar el tema según la preferencia del sistema operativo (`prefers-color-scheme`), mediante los tokens de `@confia/ui`, mientras no haya una
elección explícita. La parte autenticada ofrece un selector de tres estados («sistema», «claro» y «oscuro»; decisión D7 del cambio 13b-2) cuyo estado
predeterminado es «sistema»: con «sistema» el atributo `data-theme` está ausente. Las pantallas de acceso NO DEBEN ofrecer el selector ni establecer
`data-theme`: siguen el tema del sistema. Una persona que cambia la preferencia del sistema con «sistema» seleccionado DEBE ver el otro tema sin recargar. El
comportamiento detallado del selector, su persistencia y su aplicación están en la capacidad `admin-web-shell`.
(Previously: la aplicación seguía solo el tema del sistema, no establecía `data-theme` y no ofrecía ningún control de tema; el selector visible se reservaba al cambio 13b-2 (decisión P10).)

#### Escenario: [AW05] El tema sigue la preferencia del sistema

- **DADO** la aplicación abierta en Chromium con la preferencia de color `light` emulada y luego `dark`, y el selector de tema, si está presente, en «sistema»
- **CUANDO** se lee el color de fondo calculado del documento en cada caso
- **ENTONCES** los dos colores son distintos y coinciden con el valor del token semántico de fondo de cada tema
- **Y** el elemento raíz no tiene el atributo `data-theme`

> **Nota fechada 2026-11-10 (cambio `admin-web-shell-and-session-lifecycle`, 13b-2, escenario precisado).** Se agrega «y el selector de tema, si está presente, en «sistema»» y
> «sistema» queda como el estado predeterminado del selector. El comportamiento sin elección explícita no cambia. La aplicación autenticada con elección explícita se cubre en
> `admin-web-shell` (SH21 a SH23).

#### Escenario: [AW06] El control de tema existe en la parte autenticada y no en las pantallas de acceso

- **DADO** cada pantalla de `apps/admin-web` renderizada
- **CUANDO** se busca un control cuyo nombre accesible se refiera al tema, al modo claro o al modo oscuro
- **ENTONCES** existe exactamente uno, dentro del menú de usuario, en la página autenticada
- **Y** no existe ninguno en el arranque, el inicio de sesión, la verificación, la inscripción ni la ruta no encontrada

> **Nota fechada 2026-11-10 (13b-2, escenario invertido).** Texto anterior: «CUANDO se busca un control cuyo nombre accesible se refiera al tema, al modo claro o al modo oscuro; ENTONCES no existe
> ninguno; Y este escenario deja de ser cierto el día que el cambio 13b-2 entregue el selector». Dejó de ser cierto con este cambio. La ausencia en las pantallas de acceso se conserva.

### Requisito: El enrutamiento es TanStack Router con rutas definidas por código y guardas en `beforeLoad`

El sistema DEBE definir las rutas en código (`createRoute`), un módulo de rutas por funcionalidad, sin archivo de árbol
generado y sin supresiones de ESLint ni de TypeScript que el escáner de supresiones rechazaría (ADR-0006; se reevalúa con
más de 15 rutas). Las guardas de acceso DEBEN ejecutarse en `beforeLoad`. La parte autenticada DEBE colgar de un layout de ruta protegida (sin segmento de
ruta propio) que aloja el shell de `admin-web-shell` y cuya guarda lleva al inicio de sesión si no hay sesión. Una ruta inexistente DEBE mostrar un estado de
ruta no encontrada con texto del catálogo y una acción para ir al inicio de sesión, no el texto por defecto de la
biblioteca. En este cambio NO DEBE existir parámetro `redirect` ni regreso a una ruta profunda: solo hay una ruta protegida con contenido (decisión D9,
supuesto; se revisa con el primer cambio que entregue más de una).
(Previously: «solo hay una ruta protegida», sin layout protegido; el cuerpo es el mismo salvo por el layout y la referencia a la decisión D9.)

#### Escenario: [AW16] No existe un archivo de árbol de rutas generado

- **DADO** el árbol de `apps/admin-web`
- **CUANDO** se busca un archivo de rutas generado (por ejemplo `routeTree.gen.ts`) o una tarea de generación previa al lint
- **ENTONCES** no existe

#### Escenario: [AW17] El código de la aplicación no necesita supresiones

- **DADO** el código de `apps/admin-web`
- **CUANDO** se ejecuta el escáner de supresiones sobre `apps/`
- **ENTONCES** no encuentra ninguna directiva `eslint-disable`, `@ts-nocheck`, `@ts-ignore` ni `@ts-expect-error` sin la cita de un ADR

#### Escenario: [AW18] Ruta inexistente

- **DADO** la aplicación abierta
- **CUANDO** se navega a una ruta que no existe
- **ENTONCES** se muestra un estado de ruta no encontrada con un encabezado de nivel 1 y un enlace al inicio de sesión, con
  textos del catálogo `admin`
- **Y** no aparece ningún texto en inglés de la biblioteca de enrutamiento

#### Escenario: [AW19] No hay parámetro `redirect`

- **DADO** una persona sin sesión que abre la ruta de la página autenticada
- **CUANDO** la guarda la lleva al inicio de sesión
- **ENTONCES** la URL resultante no contiene un parámetro `redirect` ni el destino original

> **Nota fechada 2026-11-10 (13b-2, escenario conservado).** El cambio 13b-2 revisó este escenario y lo mantiene vigente (decisión D9, supuesto): solo hay una ruta protegida con
> contenido, de modo que no hay ruta profunda a la que regresar y un parámetro `redirect` solo añadiría riesgo de redirección abierta. Deja de ser cierto el día que el primer cambio con más
> de una ruta protegida entregue el regreso.

#### Escenario: [AW72] La guarda del layout protegido lleva al inicio de sesión

- **DADO** una persona sin sesión y sin cookie CSRF
- **CUANDO** abre la ruta del panel de inicio, que cuelga del layout protegido
- **ENTONCES** la guarda en `beforeLoad` la lleva al inicio de sesión antes de renderizar el shell
- **Y** el shell, la barra superior y el menú de usuario nunca aparecen en el documento

### Requisito: Ausencia de la barra lateral, las migas y de las demás exclusiones (brecha con destino: primer módulo y cambio 11)

El sistema NO DEBE contener en este cambio: la barra lateral (`Sidebar`), las migas de pan y el árbol de navegación (decisión D3 del cambio 13b-2): son del
**primer cambio que entregue un módulo real**. NO DEBE contener la identidad de la persona en el shell, que es del backend o del cambio 8b, cuando exista un endpoint
(decisión D1). NO DEBE contener el *nonce* de la CSP de producción, el despliegue de `admin-web` en nginx ni la regresión visual: son del **cambio 11**. NO DEBE
contener `apps/portal-web`, que llega con el primer cambio que entregue una pantalla del portal. NO DEBE contener un generador de operaciones de orval, que se
reevalúa con el primer endpoint de negocio. El shell con barra superior, el enlace de salto, el menú de usuario, el selector de tema y `Dialog` los entrega el
cambio 13b-2 (capacidades `admin-web-shell` y `design-system`).
(Previously: la lista incluía el shell completo (`Topbar`, `Sidebar`, `PageHeader`, migas, enlace de salto, menú de usuario y navegación), el selector de tema, `Dialog` y el panel vacío como ausentes con destino 13b-2.)

#### Escenario: [AW59] Las únicas rutas son las de este cambio

- **DADO** el árbol de rutas de `apps/admin-web`
- **CUANDO** se enumeran sus rutas
- **ENTONCES** son únicamente las de inicio de sesión, verificación e inscripción; el layout protegido sin segmento de ruta con una única ruta hija, el
  panel de inicio, en `/`; y la ruta no encontrada, más la pantalla de arranque, que no es una ruta
- **Y** este escenario deja de ser cierto el día que el primer cambio con un módulo real agregue rutas protegidas

> **Nota fechada 2026-11-10 (13b-2, escenario actualizado).** Texto anterior: «son únicamente las de inicio de sesión, verificación, inscripción, página autenticada y ruta no encontrada, más la
> pantalla de arranque, que no es una ruta; Y este escenario deja de ser cierto el día que el cambio 13b-2 entregue el shell». La «página autenticada» pasa a ser el panel de inicio dentro del layout protegido.

#### Escenario: [AW60] No existen la barra lateral ni las migas; sí existen los componentes del shell de 13b-2

- **DADO** el código de `apps/admin-web` y el punto de entrada de `@confia/ui`
- **CUANDO** se buscan `Sidebar` y `Breadcrumb`, y en otra comprobación `Topbar`, `UserMenu`, `PageHeader` y `Dialog`
- **ENTONCES** `Sidebar` y `Breadcrumb` no existen
- **Y** `Topbar`, `UserMenu`, `PageHeader` y `Dialog` existen y se importan del punto de entrada público
- **Y** este escenario deja de ser cierto, para `Sidebar` y `Breadcrumb`, el día que el primer cambio con un módulo real los entregue

> **Nota fechada 2026-11-10 (13b-2, escenario invertido en parte).** Texto anterior: «CUANDO se buscan `Topbar`, `Sidebar`, `PageHeader`, `Breadcrumb` y `Dialog`; ENTONCES no existe ninguno; Y este
> escenario deja de ser cierto el día que el cambio 13b-2 los entregue». Dejó de ser cierto para `Topbar`, `PageHeader` y `Dialog` (y `UserMenu`) con este cambio; `Sidebar` y `Breadcrumb` siguen ausentes (decisión D3).

#### Escenario: [AW61] No existe `apps/portal-web`

- **DADO** el directorio `apps/`
- **CUANDO** se listan sus paquetes
- **ENTONCES** `portal-web` no existe
- **Y** este escenario deja de ser cierto el día que el primer cambio con pantalla del portal lo cree

#### Escenario: [AW62] Ningún generador de operaciones de orval

- **DADO** `packages/contracts/orval.config.ts`
- **CUANDO** se inspeccionan sus clientes
- **ENTONCES** sigue generando solo esquemas Zod, sin rutas ni cabeceras de operaciones

#### Escenario: [AW63] La CSP de producción no se entrega

- **DADO** el repositorio tras este cambio
- **CUANDO** se busca una configuración de nginx de `admin-web` o un *nonce* de CSP
- **ENTONCES** no existe ninguno
- **Y** este escenario deja de ser cierto el día que el cambio 11 los entregue
