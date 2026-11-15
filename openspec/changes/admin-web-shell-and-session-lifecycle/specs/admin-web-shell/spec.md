# Capacidad: Shell autenticado de `apps/admin-web`

- **Identificador:** admin-web-shell
- **Estado:** especificación inicial del cambio, el 2026-11-10, tras la aprobación de la propuesta por el propietario
- **Fase:** F0 (cambio 13b-2)
- **Cambio:** `admin-web-shell-and-session-lifecycle`. La vida de la sesión (plazos, actividad, avisos y pestañas) vive en `staff-sign-in`; los
  componentes de `packages/ui` viven en `design-system`.
- **Propuesta:** `proposal.md`, aprobada por el propietario el 2026-11-10, con las decisiones D1, D2, D3, D7 y D11 vinculantes y los
  «Supuestos por confirmar» como valores de trabajo

## Propósito

Capacidad nueva. Especifica el comportamiento observable de la parte autenticada de la aplicación del panel administrativo: el layout de
la ruta protegida con su barra superior, el enlace de salto, el encabezado de página con el foco, el panel de bienvenida sin acción, el
menú de usuario con etiqueta genérica y «Cerrar sesión», el selector de tema de tres estados con su persistencia y su ausencia en las
pantallas de acceso, los textos del shell en el catálogo `admin`, y las exclusiones con dueño nombrado (barra lateral, migas, identidad).

NO le corresponde: el cálculo de plazos, el refresco por actividad, los avisos de inactividad y de vencimiento absoluto, ni la
sincronización entre pestañas (capacidad `staff-sign-in`); los componentes `Topbar`, `UserMenu`, `PageHeader`, `SkipLink`, `AppShell`, `Dialog` y
`AlertDialog` (sobre `<dialog>` nativo, decisión D12) como piezas reutilizables (capacidad `design-system`); la matriz de la puerta de accesibilidad (capacidad `build-integrity`); ni el
contrato de los endpoints, que no cambia.

Fuentes de verdad: `docs/ui-ux/01` (tema), `docs/ui-ux/02`, `docs/ui-ux/03` §2 a §4, `docs/ui-ux/04` §1 y §7 con su nota fechada, `docs/ui-ux/05`,
`docs/03-seguridad.md` §4, ADR-0006, ADR-0011, ADR-0018 y `CLAUDE.md`.

## Convenciones de esta especificación

- Los escenarios se numeran `SH01` en adelante. El prefijo `SH` no existe en ninguna otra especificación ni delta.
- Esta especificación describe comportamiento observable. Los nombres de carpeta, de módulo, de clave de almacenamiento y de clave de catálogo
  los fija el diseño.
- **Decisiones del propietario que rigen (2026-11-10):**
  - **D1:** el menú de usuario muestra solo una etiqueta genérica y «Cerrar sesión»; no se lee identidad.
  - **D2:** el panel de inicio es un mensaje de bienvenida sin acción; no se usa `EmptyState` con acción primaria.
  - **D3:** solo barra superior. Sin barra lateral, sin migas y sin árbol de navegación.
  - **D7:** selector de tema de tres estados (sistema, claro, oscuro), solo en la aplicación autenticada, persistido en `localStorage` y aplicado con
    el atributo `data-theme`. Las pantallas de acceso siguen el tema del sistema.
- **Supuesto de trabajo D7-u:** el selector de tema vive dentro del menú de usuario.
- Los textos de interfaz se leen del catálogo `admin` (trato de «usted»). Esta especificación fija un texto solo cuando un documento de
  `docs/ui-ux` ya lo fija.
- La lista de ausencias del último requisito es parte de la especificación: un comportamiento listado como ausente no debe agregarse sin
  modificar ese requisito.

## Requisitos

### Requisito: La ruta protegida tiene su propio layout con barra superior y su propio `main`, y `ScreenLayout` queda solo para el acceso

El sistema DEBE renderizar la ruta protegida dentro de un layout propio que contiene el enlace de salto, la barra superior (`Topbar`) y exactamente un
elemento `main`. `ScreenLayout`, el layout de las pantallas de acceso, NO DEBE usarse en la parte autenticada, y las pantallas de acceso NO DEBEN
mostrar la barra superior, el menú de usuario ni el selector de tema. El layout DEBE mostrarse mientras el contenido de la página lee la sesión y
cuando esa lectura falla: el contenido de la página es el que muestra los estados de carga y de error, no el layout.

#### Escenario: [SH01] Puntos de referencia de la página autenticada

- **DADO** la página autenticada renderizada tras iniciar sesión
- **CUANDO** se enumeran sus puntos de referencia (*landmarks*)
- **ENTONCES** hay exactamente un `banner` (la barra superior) y exactamente un `main`, y ningún `main` anidado

#### Escenario: [SH02] Las pantallas de acceso no cambian de layout

- **DADO** el arranque, el inicio de sesión, la verificación, la inscripción y la ruta no encontrada
- **CUANDO** se renderiza cada una
- **ENTONCES** ninguna contiene la barra superior, el menú de usuario ni el selector de tema
- **Y** cada una conserva su único `main` y su único encabezado de nivel 1

#### Escenario: [SH03] El layout está presente en los estados de carga y de error de la lectura

- **DADO** la lectura de `GET /api/v1/auth/sessions/current` retenida por el servidor, y en otra ejecución fallida por red
- **CUANDO** se observa la página autenticada
- **ENTONCES** en los dos casos la barra superior y el menú de usuario están en el documento
- **Y** el estado de carga o `ErrorState` se muestra dentro del `main`

#### Escenario: [SH04] Cada página autenticada tiene título, un encabezado de nivel 1 y el foco en él

- **DADO** la página autenticada tras iniciar sesión o tras restaurar la sesión al recargar
- **CUANDO** aparece
- **ENTONCES** `document.title` es un texto del catálogo distinto del de las pantallas de acceso
- **Y** hay exactamente un `h1`, dentro de `main`, y es el elemento con el foco
- **Y** el encabezado procede de `PageHeader`

### Requisito: El enlace de salto es el primer elemento enfocable y lleva el foco al contenido principal

El sistema DEBE ofrecer en la parte autenticada un enlace «Saltar al contenido» como primer elemento enfocable del documento (`docs/ui-ux/03` §3,
WCAG 2.4.1). El enlace DEBE permanecer fuera de la vista hasta recibir el foco, DEBE mostrarse con el anillo de foco de los tokens cuando lo recibe, y
al activarse DEBE mover el foco al elemento `main` sin cambiar la URL ni agregar una entrada de historial. Un enlace de salto que no lleva el foco al
`main` es un defecto.

#### Escenario: [SH05] Primer elemento enfocable

- **DADO** la página autenticada recién renderizada
- **CUANDO** la persona pulsa `Tab` una vez
- **ENTONCES** el foco está en el enlace de salto y su nombre accesible es el texto del catálogo

#### Escenario: [SH06] Activarlo mueve el foco al `main` sin tocar la URL

- **DADO** el enlace de salto con el foco
- **CUANDO** la persona lo activa con `Enter`
- **ENTONCES** el elemento con el foco es el `main` (o su contenedor designado como destino)
- **Y** `location.href` y la longitud de `history` no cambian

#### Escenario: [SH07] Oculto sin foco, visible con foco

- **DADO** la página autenticada sin que el enlace de salto tenga el foco
- **CUANDO** se evalúa su caja visible y luego se le da el foco
- **ENTONCES** sin foco no ocupa área visible en la ventana, pero sigue en el árbol de accesibilidad
- **Y** con foco queda visible, completo y con el anillo de foco del token

### Requisito: La barra superior contiene la etiqueta de la aplicación y el menú de usuario, y nada más

El sistema DEBE mostrar una barra superior (`Topbar`, región `banner`) que contiene la etiqueta de la aplicación y el menú de usuario. NO DEBE contener
búsqueda global, selector de año lectivo, campana de notificaciones, indicador de caja ni atajos: son de cada módulo (decisión D3).

#### Escenario: [SH08] Contenido de la barra superior

- **DADO** la barra superior renderizada
- **CUANDO** se enumeran sus controles
- **ENTONCES** los únicos controles son el disparador del menú de usuario y, si el diseño la hace interactiva, la etiqueta de la aplicación
- **Y** no existe ningún campo de búsqueda, selector de año lectivo ni control de notificaciones

### Requisito: El panel de inicio es un mensaje de bienvenida sin acción y sin datos personales

El sistema DEBE mostrar tras iniciar sesión un panel de inicio con un `PageHeader` (encabezado de nivel 1) y un mensaje de bienvenida, sin ninguna acción
primaria ni control interactivo dentro del `main` (decisión D2). NO DEBE usar `EmptyState` con acción primaria. NO DEBE mostrar nombre, correo, rol,
identificadores ni vencimientos de la persona (decisión D1). El panel reemplaza a la página mínima de 13b-1, y el botón «Cerrar sesión» de aquella página
desaparece del `main` (el cierre vive en el menú de usuario). La lectura `GET /api/v1/auth/sessions/current`, su estado de carga, su error con reintento
y su `401` siguen el comportamiento de `staff-sign-in`.

#### Escenario: [SH09] Contenido del panel

- **DADO** la lectura de la sesión terminada con éxito
- **CUANDO** se muestra el panel
- **ENTONCES** contiene un `h1` y un mensaje de bienvenida con texto del catálogo

#### Escenario: [SH10] El panel no tiene acciones

- **DADO** el panel de inicio en su estado normal (lectura de la sesión terminada y sin error de cierre de sesión)
- **CUANDO** se buscan botones, enlaces, campos y cualquier otro control interactivo dentro del `main`
- **ENTONCES** no existe ninguno
- **Y** no existe ninguno que se llame «Cerrar sesión» dentro del `main`

#### Escenario: [SH11] El panel no muestra datos de la persona

- **DADO** una lectura de la sesión con los centinelas «CENT-CUENTA», «CENT-INSTITUCION» y «CENT-SESION» en `accountId`, `institutionId` y `sessionId`
  y con vencimientos reconocibles
- **CUANDO** se muestra el shell completo con el menú de usuario abierto
- **ENTONCES** ningún centinela ni ningún instante de vencimiento aparece en el documento ni en un atributo accesible

#### Escenario: [SH12] No se usa `EmptyState` con acción primaria

- **DADO** el panel de inicio
- **CUANDO** se inspecciona su subárbol
- **ENTONCES** no contiene un `EmptyState` ni ningún botón de acción primaria

### Requisito: El menú de usuario ofrece una etiqueta genérica, el selector de tema y «Cerrar sesión», y no lee identidad

El sistema DEBE mostrar en la barra superior un menú de usuario construido como botón de divulgación (decisión D12 tras la sonda de CSP: no usa Radix
`DropdownMenu` ni `role="menu"`, porque Radix inyecta un `<style>` que viola la CSP): un botón disparador con nombre accesible genérico del catálogo
(por ejemplo «Menú de usuario»), `aria-expanded` y `aria-controls`, que muestra u oculta un panel no modal. El panel DEBE contener únicamente la etiqueta
genérica del usuario, el selector de tema (supuesto D7-u) y la acción «Cerrar sesión», sin nombre, correo ni rol de la persona. DEBE operarse con teclado:
abrirse y cerrarse con `Enter` o `Espacio` en el disparador (al abrir, el foco va al primer control del panel); moverse entre las paradas del panel con flecha
abajo, flecha arriba, `Inicio` y `Fin` (el selector de tema es una sola parada y sus opciones cambian con las flechas); cerrarse con `Escape`, devolviendo el foco al
disparador; y cerrarse con `Tab` hacia fuera del panel o con un clic fuera, sin mover el foco. El panel NO DEBE atrapar el foco ni bloquear el desplazamiento.
Abrir o cerrar el menú NO DEBE emitir peticiones. El sistema NO DEBE emitir ninguna petición de perfil ni de identidad (decisión D1): no hay endpoint, y
nombre y rol llegan cuando el backend los exponga.

#### Escenario: [SH13] Disparador con nombre genérico

- **DADO** el menú de usuario cerrado
- **CUANDO** se consulta el disparador por rol y nombre accesible
- **ENTONCES** es un botón con `aria-expanded="false"`, `aria-controls` que apunta al panel y un nombre accesible del catálogo que no contiene datos de la persona
- **Y** no tiene `aria-haspopup="menu"`

#### Escenario: [SH14] Se abre con el teclado

- **DADO** el disparador con el foco
- **CUANDO** la persona pulsa `Enter`, y en otra ejecución `Espacio`
- **ENTONCES** en los dos casos el panel se abre, `aria-expanded` pasa a `true` y el foco va al primer control del panel
- **Y** una segunda pulsación sobre el disparador cierra el panel y `aria-expanded` vuelve a `false`

#### Escenario: [SH15] Contenido exacto del panel

- **DADO** el panel abierto
- **CUANDO** se enumeran sus elementos
- **ENTONCES** son la etiqueta genérica del usuario, el selector de tema de tres opciones y la acción «Cerrar sesión», y ningún otro
- **Y** ningún elemento muestra nombre, correo, rol, perfil ni «Mis sesiones activas»

#### Escenario: [SH16] `Escape` cierra el panel y devuelve el foco al disparador

- **DADO** el panel abierto con el foco dentro de él
- **CUANDO** la persona pulsa `Escape`
- **ENTONCES** el panel se cierra, `aria-expanded` vuelve a `false` y el foco está en el disparador

#### Escenario: [SH17] Flechas, `Inicio` y `Fin` recorren las paradas y `Tab` cierra sin mover el foco

- **DADO** el panel abierto
- **CUANDO** la persona pulsa flecha abajo, flecha arriba, `Fin` e `Inicio`
- **ENTONCES** el foco recorre las paradas del panel (el selector de tema cuenta como una) y `Inicio` y `Fin` van a la primera y a la última
- **Y** `Tab` hacia fuera del panel lo cierra y el foco continúa en el orden del documento, sin quedar atrapado

#### Escenario: [SH38] El panel no usa el rol de menú

- **DADO** el menú de usuario abierto
- **CUANDO** se inspecciona su árbol de accesibilidad
- **ENTONCES** no existe ningún elemento con rol `menu`, `menuitem` ni `menuitemradio`
- **Y** el selector de tema es un grupo de opciones excluyentes (`radiogroup`)

#### Escenario: [SH39] Un clic fuera cierra el panel sin mover el foco y sin bloquear la página

- **DADO** el panel abierto
- **CUANDO** la persona hace clic en un campo de texto fuera del panel
- **ENTONCES** el panel se cierra, el foco está en ese campo y el desplazamiento de la página no estaba bloqueado en ningún momento

#### Escenario: [SH18] El menú no emite peticiones y no lee identidad

- **DADO** la página autenticada con el registro de todas las peticiones
- **CUANDO** se abre y se cierra el menú tres veces y se completa el montaje del shell
- **ENTONCES** el menú no emitió ninguna petición
- **Y** las únicas peticiones del shell son las de la sesión que ya existían en 13b-1 (restauración y lectura de `/current`); no hay petición a un perfil ni a un endpoint de identidad

#### Escenario: [SH19] «Cerrar sesión» del menú termina la sesión

- **DADO** el menú abierto en la página autenticada
- **CUANDO** la persona activa «Cerrar sesión»
- **ENTONCES** se ejecuta el cierre de sesión descrito en `staff-sign-in` (escenario SS107)

### Requisito: El selector de tema tiene tres estados, aplica `data-theme`, persiste en `localStorage` y solo existe en la parte autenticada

El sistema DEBE ofrecer en la aplicación autenticada un selector de tema con tres estados: «sistema», «claro» y «oscuro» (decisión D7). El estado
predeterminado, sin preferencia guardada, es «sistema»: el atributo `data-theme` está ausente y la aplicación sigue `prefers-color-scheme`. Elegir
«claro» u «oscuro» DEBE establecer `data-theme="light"` o `data-theme="dark"` en el elemento raíz, que prevalece sobre la preferencia del sistema, y
elegir «sistema» DEBE quitarlo. La elección DEBE aplicarse sin recargar y conservarse al recargar. El selector DEBE exponer su estado de forma accesible
(opción seleccionada) con nombres del catálogo. La preferencia es la única información que la aplicación guarda en `localStorage`: un valor de uno de los tres
estados, nunca un secreto. Un valor guardado inválido DEBE tratarse como «sistema», y si `localStorage` no está disponible o falla, el selector DEBE
funcionar solo en memoria, sin error visible. Las pantallas de acceso (arranque, inicio de sesión, verificación, inscripción y ruta no encontrada) NO
DEBEN aplicar la preferencia guardada ni ofrecer el selector: siguen el tema del sistema. El código que aplica la preferencia NO DEBE ser un script en línea
(compatibilidad con la CSP de `admin-web-foundation`).

#### Escenario: [SH20] Tres estados y «sistema» por defecto

- **DADO** el menú de usuario abierto sin preferencia guardada
- **CUANDO** se consulta el selector de tema
- **ENTONCES** ofrece exactamente tres opciones, «sistema», «claro» y «oscuro», con nombres del catálogo
- **Y** «sistema» está seleccionada y el elemento raíz no tiene `data-theme`

#### Escenario: [SH21] Elegir «oscuro» aplica el tema sin recargar

- **DADO** la preferencia del sistema emulada como `light`
- **CUANDO** la persona elige «oscuro»
- **ENTONCES** el elemento raíz tiene `data-theme="dark"`
- **Y** el color de fondo calculado del documento es el del token semántico de fondo del tema oscuro
- **Y** la opción «oscuro» queda seleccionada de forma accesible

#### Escenario: [SH22] Elegir «claro» prevalece sobre un sistema oscuro

- **DADO** la preferencia del sistema emulada como `dark`
- **CUANDO** la persona elige «claro»
- **ENTONCES** el elemento raíz tiene `data-theme="light"` y el color de fondo calculado es el del token claro

#### Escenario: [SH23] Elegir «sistema» devuelve el control al sistema operativo

- **DADO** «oscuro» elegido y luego «sistema» elegido, con la preferencia del sistema emulada como `light`
- **CUANDO** se lee el elemento raíz y luego se cambia la emulación a `dark` sin recargar
- **ENTONCES** el elemento raíz no tiene `data-theme`, el fondo es el claro con la primera emulación y el oscuro tras el cambio

#### Escenario: [SH24] La preferencia se conserva al recargar

- **DADO** «oscuro» elegido con la preferencia del sistema emulada como `light` y una cookie CSRF con sesión viva
- **CUANDO** se recarga la aplicación y se restaura la sesión
- **ENTONCES** la página autenticada se muestra con `data-theme="dark"` y «oscuro» seleccionada

#### Escenario: [SH25] Un valor guardado inválido equivale a «sistema»

- **DADO** `localStorage` con un valor de preferencia que no es ninguno de los tres estados, y la sesión restaurada
- **CUANDO** se muestra la página autenticada
- **ENTONCES** «sistema» está seleccionada, el elemento raíz no tiene `data-theme` y no hay error en la consola

#### Escenario: [SH26] Sin `localStorage` el selector funciona en memoria

- **DADO** un navegador donde leer o escribir `localStorage` lanza una excepción
- **CUANDO** la persona elige «oscuro»
- **ENTONCES** el elemento raíz tiene `data-theme="dark"` mientras dure la página, sin error visible ni mensaje de error en la consola

#### Escenario: [SH27] Las pantallas de acceso ignoran la preferencia guardada

- **DADO** «oscuro» guardado, la preferencia del sistema emulada como `light` y ninguna sesión
- **CUANDO** se muestra el inicio de sesión, la verificación, la inscripción y la ruta no encontrada
- **ENTONCES** en todas el elemento raíz no tiene `data-theme` y el fondo es el del tema claro del sistema

#### Escenario: [SH28] Cerrar sesión devuelve las pantallas de acceso al tema del sistema

- **DADO** la página autenticada con `data-theme="dark"` y la preferencia del sistema emulada como `light`
- **CUANDO** la persona cierra sesión
- **ENTONCES** en el inicio de sesión el elemento raíz no tiene `data-theme` y el fondo es el claro
- **Y** la preferencia sigue guardada y se vuelve a aplicar al iniciar sesión de nuevo

#### Escenario: [SH29] La preferencia es lo único que se guarda en `localStorage`

- **DADO** un recorrido completo de inicio de sesión, elección de tema y cierre de sesión con centinelas únicos de secretos
- **CUANDO** termina
- **ENTONCES** `localStorage` contiene a lo sumo una clave de la aplicación, cuyo valor es uno de los tres estados
- **Y** `sessionStorage` e IndexedDB no contienen claves de la aplicación ni ningún centinela

#### Escenario: [SH30] El tema no requiere código en línea

- **DADO** el HTML compilado y `vite preview` con la CSP de `admin-web-foundation`, con «oscuro» guardado
- **CUANDO** se carga la aplicación y se llega a la página autenticada
- **ENTONCES** el HTML compilado no contiene `<script>` sin `src` (escenario AW13)
- **Y** el evento `securitypolicyviolation` no se dispara ni una vez

#### Escenario: [SH31] El selector no existe fuera de la parte autenticada

- **DADO** cada pantalla de acceso renderizada
- **CUANDO** se busca un control cuyo nombre accesible se refiera al tema, al modo claro, al modo oscuro o al sistema
- **ENTONCES** no existe ninguno (escenario AW06)

### Requisito: Todo texto del shell, del tema y de los avisos de sesión proviene del catálogo `admin`, en «usted»

El sistema DEBE definir en el catálogo `admin` (`es-HN`) las claves de la barra superior, el enlace de salto, el panel de bienvenida, el menú de usuario,
el selector de tema y los avisos de sesión, redactadas en «usted» según `admin-web-foundation`, y NO DEBE embeber cadenas de interfaz en los componentes.
El idioma `en` sin traducciones reales DEBE mostrar los textos de `es-HN` y ninguna clave cruda.

#### Escenario: [SH32] Las claves nuevas existen y la verificación de claves las evalúa

- **DADO** el catálogo `es-HN` `admin` y las claves de shell, tema y avisos que usa la aplicación
- **CUANDO** se ejecuta la verificación de claves
- **ENTONCES** todas existen y la verificación evalúa al menos una clave de cada una de esas tres familias

#### Escenario: [SH33] La prueba de voz alcanza las claves nuevas

- **DADO** todos los valores del catálogo `es-HN` `admin`, incluidos los de shell, tema y avisos, y la violación deliberada de un valor «Cierre su sesión o elija tu tema»
- **CUANDO** corre la prueba de voz de `admin-web-foundation`
- **ENTONCES** ninguno de los valores reales coincide con las listas de tuteo y de voseo
- **Y** la violación deliberada falla nombrando la clave

#### Escenario: [SH34] Sin traducción, el shell muestra `es-HN`

- **DADO** el idioma activo `en`
- **CUANDO** se renderiza la página autenticada con el menú abierto
- **ENTONCES** todo texto visible y todo nombre accesible es el de `es-HN` y no hay claves crudas

### Requisito: Ausencia de la barra lateral, las migas, la identidad y de lo que tiene dueño nombrado (brechas con destino)

El sistema NO DEBE contener en este cambio: la barra lateral (`Sidebar`), las migas de pan y el árbol de navegación (decisión D3; son del primer cambio que
entregue un módulo real); el nombre, el correo, el rol o el perfil de la persona en el shell (decisión D1; son del backend o del cambio 8b, cuando exista un
endpoint, y luego de un cambio de frontend); «Mis sesiones activas», su lista y la revocación de otras sesiones (D10, supuesto; mismo dueño); el selector de
tema en las pantallas de acceso (decisión D7; sin dueño: decisión del propietario de no hacerlo); y la búsqueda global, el año lectivo, la campana, el
indicador de caja y los atajos (son de cada módulo).

#### Escenario: [SH35] No existen la barra lateral, las migas ni la navegación

- **DADO** la página autenticada y el punto de entrada de `@confia/ui`
- **CUANDO** se buscan `Sidebar`, `Breadcrumb`, una región `navigation` con enlaces a módulos y las claves de catálogo de navegación
- **ENTONCES** no existe ninguno
- **Y** este escenario deja de ser cierto el día que el primer cambio con un módulo real entregue la navegación

#### Escenario: [SH36] No existen identidad ni «Mis sesiones activas»

- **DADO** la aplicación y su cliente de API
- **CUANDO** se buscan una operación de perfil, una lista de sesiones, una revocación de sesiones y un elemento de menú con esos nombres
- **ENTONCES** no existe ninguno
- **Y** este escenario deja de ser cierto el día que el backend o el cambio 8b expongan los endpoints y un cambio de frontend los consuma

#### Escenario: [SH37] No existen búsqueda global, año lectivo, campana, indicador de caja ni atajos

- **DADO** el shell renderizado
- **CUANDO** se buscan esos cinco elementos
- **ENTONCES** no existe ninguno
- **Y** este escenario deja de ser cierto el día que el módulo que los necesita los entregue
