# Delta para Sistema de diseño

- **Estado:** delta inicial del cambio, el 2026-11-10, tras la aprobación de la propuesta por el propietario
- **Cambio:** `admin-web-shell-and-session-lifecycle` (F0, cambio 13b-2)
- **Propuesta:** `proposal.md`, aprobada por el propietario el 2026-11-10 (D1: el menú de usuario no lee identidad; D3: solo barra superior; D12, resuelta por
  la sonda de CSP: `Dialog` y `AlertDialog` sobre `<dialog>` nativo con `showModal()` y menú de usuario como botón de divulgación; Radix `Dialog`, `AlertDialog` y
  `DropdownMenu` quedan descartados porque inyectan un `<style>` que viola `style-src 'self'`)

**Convenciones de este delta.**

- Los escenarios nuevos se numeran `DS99` en adelante, a continuación del máximo vigente (`DS98`). Los escenarios `DS02`, `DS88` y `DS89` conservan su
  identificador (ADR-0018): `DS88` se actualiza, `DS89` se invierte y `DS02` se precisa, todos con nota fechada. Ninguno se borra.
- **Cómo se fusiona este delta.** Se archiva después de `admin-web-foundations-and-session`. Los requisitos bajo `## ADDED Requirements` son nuevos. El requisito
  bajo `## MODIFIED Requirements` trae el cuerpo completo y **todos** sus escenarios (los que no cambian se copian sin alteración).
- Este delta agrega `Dialog`, `AlertDialog`, `SkipLink`, `PageHeader`, `Topbar`, `UserMenu` y `AppShell` (la plantilla del shell), los siete nombres exactos de
  `design.md` (secciones H8 y H10). `Dialog` y `AlertDialog` se construyen sobre `<dialog>` nativo con `showModal()` y `UserMenu` como botón de divulgación
  (decisión D12, resuelta por la sonda de CSP). NO existen `DropdownMenu` ni ningún menú con `role="menu"`, y ningún componente de `packages/ui` DEBE inyectar
  estilos ni relajar la CSP.
- **Compañeros de composición.** Los siete nombres son todo el conjunto nuevo: ninguno exporta compañeros de composición públicos (`NativeDialog` es interno).
  Un compañero adicional cambia el conjunto exacto de exportaciones y exige modificar este delta antes de implementarse, y `public-api.test.ts` fija el
  conjunto exacto.
- **Valores de trabajo:** el menú de usuario y el selector de tema no son componentes de `packages/ui` con conocimiento de la aplicación: `UserMenu` recibe su
  contenido por propiedades; el selector de tema y el cierre de sesión son de `apps/admin-web`. El catálogo `ui` es compartido por el panel y el portal y no se
  dirige a la persona (P1 de 13b-1).
- Tamaño mínimo de objetivo: 24 por 24 píxeles (`docs/ui-ux/03`, 2.5.8).

## ADDED Requirements

### Requisito: `Dialog` y `AlertDialog` son modales, confinan el foco, lo devuelven al origen y exigen nombre y descripción

El sistema DEBE ofrecer `Dialog` y `AlertDialog` como componentes de `packages/ui`, controlados (`open`, `onDismiss`, `title`, `description`, `initialFocusRef`) y
construidos sobre el elemento `<dialog>` nativo abierto con `showModal()`, nunca sobre Radix. Ambos DEBEN ser modales: confinar el foco con `Tab` y `Shift+Tab`, dejar
el resto de la página inerte, y bloquear el desplazamiento del fondo con CSS empaquetado, sin inyectar ningún `<style>` ni atributo de estilo en línea (compatibilidad con la CSP). Ambos
DEBEN exigir por tipos un nombre accesible (título) y una descripción: omitirlos es un error de la verificación de tipos. Al abrirse, el foco inicial DEBE ir al
primer elemento interactivo del contenido, no a un botón de cerrar (`docs/ui-ux/03` §3); un consumidor puede designar otro control inicial, pero un control
destructivo NO DEBE recibirlo por defecto. Al cerrarse, el foco DEBE volver al elemento que lo tenía al abrirse, o al `h1` de la página si ese elemento ya no
existe. En ambos, `Escape` DEBE invocar `onDismiss` del consumidor en lugar de cerrar el diálogo por su cuenta: el estado del consumidor es la fuente de verdad. `AlertDialog`
DEBE tener `role="alertdialog"`, anunciarse con prioridad `assertive` (región activa `assertive` dentro del propio diálogo, porque el resto de la página queda inerte) y NO DEBE
cerrarse con un clic fuera. Ninguno de los dos hace peticiones ni importa del cliente de red (contenedor y presentación, `CLAUDE.md`). Un estado de carga, de vacío y de error no
corresponden al diálogo en sí: son del contenido que aloja el consumidor.

#### Escenario: [DS99] Importación y apertura

- **DADO** una aplicación del espacio de trabajo que declara `packages/ui` como dependencia
- **CUANDO** importa `Dialog` y `AlertDialog` del punto de entrada público y los renderiza abiertos
- **ENTONCES** la importación compila y cada uno se renderiza como un elemento `<dialog>` abierto en modo modal, con su título y su descripción enlazados por `aria-labelledby` y `aria-describedby`

#### Escenario: [DS100] Omitir el nombre o la descripción es un error de tipos

- **DADO** una prueba de tipos que renderiza `Dialog` sin título y `AlertDialog` sin descripción
- **CUANDO** se ejecuta la verificación de tipos
- **ENTONCES** falla señalando la propiedad ausente en cada caso

#### Escenario: [DS101] El foco inicial es el primer elemento interactivo y no el botón de cerrar

- **DADO** un `Dialog` con un botón de cerrar y dos acciones, que se abre
- **CUANDO** se lee el elemento con el foco
- **ENTONCES** es la primera acción del contenido y no el botón de cerrar

#### Escenario: [DS102] El consumidor puede designar el control inicial

- **DADO** un `AlertDialog` con las acciones «Continuar» y «Cerrar sesión» y «Continuar» designado como control inicial
- **CUANDO** se abre
- **ENTONCES** el foco está en «Continuar»
- **Y** sin designación, «Cerrar sesión» no recibe el foco por defecto si es la acción destructiva

#### Escenario: [DS103] El foco queda confinado y el fondo inerte

- **DADO** un diálogo abierto con tres controles
- **CUANDO** se pulsa `Tab` y `Shift+Tab` más veces que controles hay
- **ENTONCES** el foco cicla entre los controles del diálogo sin salir
- **Y** el contenido de la página detrás es inerte o oculto a las tecnologías de asistencia

#### Escenario: [DS104] El foco vuelve al origen al cerrar

- **DADO** un diálogo abierto desde un botón con el foco
- **CUANDO** se cierra
- **ENTONCES** el foco vuelve a ese botón

#### Escenario: [DS105] Si el origen ya no existe, el foco va al `h1`

- **DADO** un diálogo abierto desde un elemento que se eliminó del documento antes de cerrar
- **CUANDO** se cierra
- **ENTONCES** el foco está en el encabezado de nivel 1 de la página

#### Escenario: [DS106] `Dialog` delega `Escape` en `onDismiss`

- **DADO** un `Dialog` abierto con un `onDismiss`
- **CUANDO** se pulsa `Escape`
- **ENTONCES** `onDismiss` se invoca una vez y el diálogo sigue abierto mientras el consumidor no cambie `open`
- **Y** un segundo `Escape` o el cierre por el navegador también termina en una llamada a `onDismiss`, nunca en un estado de `open` desincronizado

#### Escenario: [DS107] `AlertDialog` no se cierra con un clic fuera y `Escape` invoca `onDismiss`

- **DADO** un `AlertDialog` abierto con un `onDismiss`
- **CUANDO** se hace clic en el fondo (`::backdrop`), y en otra ejecución se pulsa `Escape`
- **ENTONCES** el clic fuera no cierra ni invoca nada
- **Y** `Escape` invoca `onDismiss` una vez y no cierra el diálogo por sí solo

#### Escenario: [DS108] `AlertDialog` se anuncia con prioridad `assertive`

- **DADO** un `AlertDialog` que se abre
- **CUANDO** se inspecciona el árbol de accesibilidad
- **ENTONCES** tiene `role="alertdialog"` y contiene dentro de sí una región `aria-live="assertive"` con el texto del aviso, y está en modo modal

#### Escenario: [DS130] El bloqueo de desplazamiento no inyecta estilos

- **DADO** un diálogo que se abre y se cierra tres veces bajo la CSP estricta de `preview`
- **CUANDO** se cuentan los elementos `<style>` añadidos al documento y los eventos `securitypolicyviolation`
- **ENTONCES** no se añade ningún `<style>` y no se dispara ninguna violación
- **Y** mientras hay un diálogo abierto el desplazamiento del documento está bloqueado por una regla de la hoja de estilos empaquetada

#### Escenario: [DS109] Texto que cambia por segundo no se anuncia

- **DADO** un diálogo cuyo contenido incluye un tiempo restante que cambia cada segundo y un observador de mutaciones sobre las regiones activas
- **CUANDO** transcurren 60 segundos
- **ENTONCES** el texto de las regiones activas cambia como máximo dos veces: al abrir y al terminar

### Requisito: `UserMenu` es un botón de divulgación operable con teclado, sin `role="menu"` y sin Radix

El sistema DEBE ofrecer `UserMenu` como componente de `packages/ui` que sigue el patrón de divulgación: un `button` con `aria-expanded` y `aria-controls` que
muestra u oculta un panel no modal, posicionado con CSS estático, sin `role="menu"` ni `aria-haspopup="menu"`, y sin depender de ninguna primitiva de Radix que bloquee
el desplazamiento. El panel DEBE contener la etiqueta genérica del usuario, un grupo de opciones excluyentes para el tema (`radiogroup`) y un botón de cierre de sesión,
todos recibidos por propiedades. DEBE abrirse y cerrarse con `Enter` y `Espacio` en el disparador (al abrir, el foco va al primer control del panel); mover el foco entre
las paradas con flecha abajo, flecha arriba, `Inicio` y `Fin` (el grupo de opciones es una sola parada y cambia de opción con las flechas); cerrarse con `Escape`,
devolviendo el foco al disparador; y cerrarse con `Tab` hacia fuera o con un clic fuera, sin mover el foco. NO DEBE atrapar el foco ni bloquear el desplazamiento. El botón de
cierre de sesión NO DEBE usar `disabled` para indicar una espera: usa `aria-busy` en el disparador. Es de presentación: NO DEBE hacer peticiones.

#### Escenario: [DS110] Apertura por teclado y estado del disparador

- **DADO** el disparador de un `UserMenu` con el foco
- **CUANDO** se pulsa `Enter`, y en otra ejecución `Espacio`
- **ENTONCES** en ambos casos el panel se abre, el disparador expone `aria-expanded="true"` y `aria-controls` hacia el panel, y el foco va al primer control del panel
- **Y** el disparador no expone `aria-haspopup="menu"`

#### Escenario: [DS111] Navegación con flechas, `Inicio` y `Fin`

- **DADO** el panel abierto con tres paradas (grupo de tema, y botón de cierre) y el foco en la primera
- **CUANDO** se pulsa flecha abajo, `Fin`, `Inicio` y flecha arriba
- **ENTONCES** el foco pasa, en ese orden, por la segunda parada, la última, la primera y la última (sin salir del panel)

#### Escenario: [DS112] `Escape` devuelve el foco al disparador

- **DADO** el panel abierto con el foco dentro
- **CUANDO** se pulsa `Escape`
- **ENTONCES** el panel se cierra y el foco está en el disparador

#### Escenario: [DS113] `Tab` y el clic fuera cierran sin mover el foco

- **DADO** el panel abierto
- **CUANDO** se pulsa `Tab` hacia fuera del panel, y en otra ejecución se hace clic en un campo fuera
- **ENTONCES** en ambos casos el panel se cierra, el foco queda donde el navegador lo lleva (el siguiente elemento o el campo) y no queda atrapado

#### Escenario: [DS114] El grupo de tema expone su estado y no usa roles de menú

- **DADO** un grupo de tres opciones de tema con la segunda seleccionada
- **CUANDO** se pulsa flecha abajo sobre el grupo
- **ENTONCES** el consumidor recibe el valor de la tercera, la opción seleccionada expone `aria-checked="true"` y las demás `aria-checked="false"`
- **Y** el panel no contiene ningún elemento con rol `menu`, `menuitem` ni `menuitemradio`

#### Escenario: [DS115] El menú no hace peticiones

- **DADO** un `UserMenu` renderizado solo con propiedades, en un entorno que falla ante cualquier petición
- **CUANDO** se abre, se navega y se cierra
- **ENTONCES** no se emite ninguna petición

### Requisito: `Topbar`, `UserMenu`, `PageHeader` y `SkipLink` componen el shell sin conocer la aplicación

El sistema DEBE ofrecer en `packages/ui` los componentes siguientes, todos de presentación (sin peticiones ni acceso al enrutador). `Topbar` DEBE ser la
región `banner` con una zona para la etiqueta de la aplicación y otra para las acciones, y NO DEBE incluir barra lateral, migas ni búsqueda. `UserMenu` DEBE recibir por
propiedades el nombre accesible genérico de su disparador y su contenido, y NO DEBE exigir ni mostrar nombre, correo ni rol de la persona. `PageHeader` DEBE
renderizar el encabezado de nivel 1 con un título obligatorio, una descripción opcional y una zona de acciones opcional, y DEBE poder recibir el foco programático
(`tabIndex={-1}` y `ref` por propiedad, como `FormErrorSummary`) sin agregar una parada de tabulación. `SkipLink` DEBE ser un enlace que recibe como propiedades el texto y el
identificador del destino, permanece fuera de la vista hasta recibir el foco, se muestra con el anillo de foco del token al recibirlo y, al activarse, mueve el foco al
destino sin cambiar la URL ni el historial. `AppShell` DEBE ser la plantilla que compone el `SkipLink`, el `Topbar` y un único `main` con identificador de destino
y `tabIndex={-1}`, recibiendo sus partes por propiedades. Todos DEBEN cumplir el tamaño mínimo de objetivo y los tokens de ambos temas.

#### Escenario: [DS116] `Topbar` es la región `banner` y solo aloja etiqueta y acciones

- **DADO** un `Topbar` con etiqueta y un `UserMenu`
- **CUANDO** se inspecciona
- **ENTONCES** tiene el rol `banner` y contiene la etiqueta y el disparador del menú, sin barra lateral, migas ni campo de búsqueda

#### Escenario: [DS117] `UserMenu` toma su nombre del consumidor y no muestra datos de la persona

- **DADO** un `UserMenu` con el nombre accesible del disparador «Menú de usuario», la etiqueta genérica y el texto de «Cerrar sesión»
- **CUANDO** se abre
- **ENTONCES** el disparador se llama «Menú de usuario» y el panel contiene solo la etiqueta genérica, el grupo de tema y el botón de cierre de sesión recibidos
- **Y** el componente no define propiedades de nombre, correo ni rol de la persona

#### Escenario: [DS118] `PageHeader` renderiza el `h1` y puede recibir el foco programático

- **DADO** un `PageHeader` con título, descripción y acciones
- **CUANDO** se renderiza y se le da el foco desde código
- **ENTONCES** hay exactamente un `h1` con el título y recibe el foco
- **Y** no agrega una parada de tabulación

#### Escenario: [DS119] `PageHeader` sin descripción ni acciones no deja huecos

- **DADO** un `PageHeader` solo con título
- **CUANDO** se renderiza
- **ENTONCES** no hay párrafo vacío ni contenedor de acciones vacío en el documento

#### Escenario: [DS120] `SkipLink` está oculto sin foco y visible con foco

- **DADO** un `SkipLink` con destino «contenido»
- **CUANDO** se evalúa su caja visible sin foco y luego con foco
- **ENTONCES** sin foco no ocupa área visible y sigue en el árbol de accesibilidad
- **Y** con foco queda visible, completo y con el anillo de foco del token

#### Escenario: [DS121] `SkipLink` mueve el foco al destino sin tocar la URL

- **DADO** un `SkipLink` con el foco y un destino con `tabIndex={-1}`
- **CUANDO** se activa con `Enter`
- **ENTONCES** el foco está en el destino y `location.href` y la longitud del historial no cambian

#### Escenario: [DS122] Tamaño de objetivo

- **DADO** el disparador de `UserMenu`, los controles de su panel y el `SkipLink` con foco
- **CUANDO** se mide su caja
- **ENTONCES** cada uno mide al menos 24 por 24 píxeles

#### Escenario: [DS123] Los componentes del shell no hacen peticiones ni importan red

- **DADO** el código de `Topbar`, `UserMenu`, `PageHeader`, `SkipLink`, `AppShell`, `Dialog` y `AlertDialog`
- **CUANDO** se ejecuta el análisis estático de red de `packages/ui`
- **ENTONCES** no encuentra `fetch`, `XMLHttpRequest`, `WebSocket`, `EventSource`, `Worker`, `SharedWorker` ni `navigator.sendBeacon` en ninguno

#### Escenario: [DS131] `AppShell` compone enlace de salto, barra superior y un único `main`

- **DADO** un `AppShell` con una barra superior, un enlace de salto y contenido
- **CUANDO** se renderiza
- **ENTONCES** el enlace de salto es el primer elemento enfocable, hay exactamente un `banner` y un `main`, y el destino del enlace de salto es ese `main`

### Requisito: Los textos de los componentes nuevos salen del catálogo `ui` sin dirigirse a la persona

El sistema DEBE definir en el catálogo `ui` (`es-HN`) las claves de los textos propios de los componentes nuevos (por ejemplo el nombre accesible del botón de cerrar de
`Dialog`), sin dirigirse a la persona en segunda persona (decisión P1 de 13b-1), y NO DEBE embeber cadenas de interfaz en ellos. Los textos que dependen de la
aplicación (título del aviso, «Continuar», «Cerrar sesión», nombre del menú, texto del enlace de salto) los entrega el consumidor desde el catálogo `admin`.

#### Escenario: [DS124] Las claves nuevas del catálogo `ui` pasan la prueba de voz

- **DADO** todos los valores del catálogo `es-HN` `ui`, incluidos los de los componentes nuevos, y la violación deliberada de un valor «Cierra el diálogo»
- **CUANDO** corre la prueba de voz del catálogo `ui` (escenarios DS93 y DS94)
- **ENTONCES** ninguno de los valores reales coincide con las listas de segunda persona
- **Y** la violación deliberada falla nombrando la clave

#### Escenario: [DS125] Ninguna cadena de interfaz embebida en los componentes nuevos

- **DADO** los componentes nuevos
- **CUANDO** corre el análisis estático de texto de `packages/ui`
- **ENTONCES** no encuentra literales de texto en JSX ni en atributos de nombre accesible

### Requisito: Cada componente nuevo tiene historias en modo claro y oscuro, incluidos los estados superpuestos abiertos, y la puerta de historias los evalúa

El sistema DEBE entregar historias de Storybook para `Dialog`, `AlertDialog`, `Topbar`, `UserMenu`, `PageHeader`, `SkipLink` y `AppShell`, en el tema claro y en el
oscuro, que incluyan los estados superpuestos **abiertos** (diálogo abierto, diálogo de alerta abierto, panel del menú de usuario abierto) y el enlace de salto con foco. La puerta de
historias de accesibilidad (`@confia/config`, escenarios DS96 y DS97) DEBE evaluar esas historias abiertas, y DEBE existir una historia con una violación deliberada
por cada estado superpuesto nuevo que la haga fallar.

#### Escenario: [DS126] Historias de los componentes nuevos

- **DADO** los siete componentes nuevos
- **CUANDO** se listan sus historias
- **ENTONCES** cada uno tiene historias en el tema claro y en el oscuro, y entre ellas están el diálogo abierto, el diálogo de alerta abierto, el panel del menú de usuario abierto y el
  enlace de salto con foco

#### Escenario: [DS127] La puerta de historias evalúa los estados abiertos

- **DADO** la historia del diálogo abierto, la del diálogo de alerta abierto y la del panel del menú de usuario abierto
- **CUANDO** corre la puerta de historias de accesibilidad
- **ENTONCES** las tres se evalúan, en los dos temas, y el informe las lista

#### Escenario: [DS128] Cada estado abierto tiene su violación deliberada

- **DADO** una historia deliberadamente inaccesible por cada uno de los tres estados abiertos (por ejemplo, un diálogo sin nombre accesible o un botón del panel del menú
  de usuario sin nombre)
- **CUANDO** corre la puerta de historias de accesibilidad sobre ellas
- **ENTONCES** falla en cada una nombrando la historia, la regla de axe y su impacto
- **Y** la puerta no aprueba sobre un conjunto vacío

#### Escenario: [DS129] La puerta de historias sigue en verde sobre las historias reales nuevas

- **DADO** las historias reales de los componentes nuevos, sin las de violación deliberada
- **CUANDO** corre la puerta
- **ENTONCES** termina en verde con ninguna violación crítica ni seria en los dos temas

## MODIFIED Requirements

### Requisito: `packages/ui` expone un único punto de entrada público y solo el inventario de esta entrega

El sistema DEBE ofrecer `packages/ui` como paquete del espacio de trabajo con un único punto de
entrada público, organizado en `tokens/`, `atoms/`, `molecules/`, `domain/` y `hooks/`
(`docs/ui-ux/02` §3). El punto de entrada DEBE exportar exactamente los veintiséis componentes de esta entrega: los diecinueve componentes P0 anteriores
(`Button`, `IconButton`, `Input`, `Textarea`, `Label`, `Checkbox`, `RadioGroup`, `Badge`,
`Spinner`, `Separator`, `Skeleton`, `FormField`, `FormError`, `EmptyState`, `ErrorState`,
`MoneyAmount`, `StatusBadge`, `FormErrorSummary` y `Alert`) más los siete del shell de 13b-2: `Dialog`, `AlertDialog`, `SkipLink`, `PageHeader`, `Topbar`, `UserMenu` y
`AppShell`; más dos compañeros de composición anteriores: `RadioGroupItem`, sin el cual `RadioGroup` no es utilizable, y `buttonVariants`, la receta de clases
de `Button` para enlaces con apariencia de botón. Los componentes nuevos no tienen compañeros de composición públicos (`NativeDialog` es interno). NO DEBE
exportar componentes del resto del inventario P0 (por ejemplo `Select`, `Toast`, `DropdownMenu`, `Sidebar`, `Breadcrumb` o
`DataTable`) ni ningún token primitivo. Cualquier compañero de composición adicional cambia el conjunto exacto y exige modificar este requisito antes de implementarse.
(Previously: diecinueve componentes P0 y veintiún nombres exportados, sin los siete del shell; `Dialog` figuraba como no exportado.)

#### Escenario: [DS01] Importación desde el punto de entrada público

- **DADO** una aplicación del espacio de trabajo que declara `packages/ui` como dependencia
- **CUANDO** importa `MoneyAmount` desde el punto de entrada público del paquete
- **ENTONCES** la importación compila y resuelve el componente

#### Escenario: [DS02] El conjunto exportado coincide con el inventario

- **DADO** el punto de entrada público de `packages/ui`
- **CUANDO** una prueba enumera sus exportaciones de componentes
- **ENTONCES** el conjunto es exactamente el de los veintiséis componentes listados más `RadioGroupItem` y `buttonVariants`, veintiocho nombres, sin uno más ni uno menos
- **Y** `DropdownMenu` no está entre ellos
- **Y** ninguna exportación es un token primitivo

> **Nota fechada 2026-11-10 (cambio `admin-web-shell-and-session-lifecycle`, 13b-2, escenario precisado).** Texto anterior: «el conjunto es exactamente el de los diecinueve componentes listados más
> `RadioGroupItem` y `buttonVariants`, veintiún nombres, sin uno más ni uno menos». Crece con los siete componentes del shell de `design.md` (sin compañeros públicos).

#### Escenario: [DS03] Componente fuera del alcance de esta entrega

- **DADO** el punto de entrada público de `packages/ui`
- **CUANDO** una aplicación intenta importar `DataTable` o `Select`
- **ENTONCES** la verificación de tipos falla porque esas exportaciones no existen

#### Escenario: [DS88] Los componentes nuevos se importan del punto de entrada público

- **DADO** una aplicación del espacio de trabajo que declara `packages/ui` como dependencia
- **CUANDO** importa `FormErrorSummary`, `Alert`, `Dialog`, `AlertDialog`, `SkipLink`, `PageHeader`, `Topbar`, `UserMenu` y `AppShell` desde el punto de entrada público del paquete
- **ENTONCES** la importación compila y resuelve los nueve componentes

> **Nota fechada 2026-11-10 (13b-2, escenario actualizado).** Texto anterior: «importa `FormErrorSummary` y `Alert` desde el punto de entrada público del paquete; ENTONCES la importación compila y resuelve los dos componentes». Crece con los siete componentes del shell.

#### Escenario: [DS89] `Dialog` existe y se importa del punto de entrada público; `Sidebar` y `Breadcrumb` siguen ausentes

- **DADO** el punto de entrada público de `packages/ui`
- **CUANDO** una aplicación importa `Dialog`, y en otra comprobación intenta importar `Sidebar` o `Breadcrumb`
- **ENTONCES** la importación de `Dialog` compila y resuelve el componente
- **Y** la verificación de tipos falla para `Sidebar` y `Breadcrumb` porque esas exportaciones no existen (decisión D3)
- **Y** la ausencia de `Sidebar` y `Breadcrumb` deja de ser cierta el día que el primer cambio con un módulo real los entregue

> **Nota fechada 2026-11-10 (13b-2, escenario invertido).** Texto anterior: «CUANDO una aplicación intenta importar `Dialog`; ENTONCES la verificación de tipos falla porque esa exportación no existe; Y este escenario deja
> de ser cierto el día que el cambio 13b-2 entregue `Dialog`». Dejó de ser cierto con este cambio.
