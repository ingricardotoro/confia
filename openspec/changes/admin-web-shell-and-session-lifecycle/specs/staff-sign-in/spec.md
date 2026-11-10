# Delta para Inicio de sesión del personal

- **Estado:** delta inicial del cambio, el 2026-11-10, tras la aprobación de la propuesta por el propietario
- **Fase:** F0 (cambio 13b-2)
- **Cambio:** `admin-web-shell-and-session-lifecycle`
- **Propuesta:** `proposal.md`, aprobada por el propietario el 2026-11-10, con las decisiones D1, D2, D3, D7 y D11 vinculantes y los «Supuestos por
  confirmar» como valores de trabajo

## Propósito

Cumple la columna 13b-2 de la «línea de la sesión» de 13b-1: lo que observa el paso del tiempo y lo anticipa ante la persona. Agrega los plazos de la
sesión como metadatos no secretos, el planificador con reloj corregido, la detección de actividad con refresco de cadencia mínima, el aviso de inactividad
con diálogo, el aviso de vencimiento absoluto, la sincronización entre pestañas, el modelo de inactividad de la API simulada y los recorridos nuevos.
Precisa el invariante de temporizadores, mueve el cierre de sesión al menú de usuario e invierte las ausencias SS124, SS125 y SS126.

## Convenciones de este delta

- **Cómo se fusiona este delta.** Se archiva después de `admin-web-foundations-and-session` y `admin-web-staff-sign-in`. Los requisitos bajo
  `## ADDED Requirements` son nuevos. Los requisitos bajo `## MODIFIED Requirements` traen el cuerpo completo y **todos** sus escenarios (los que no
  cambian se copian sin alteración), y reemplazan al requisito vigente al archivar.
- Los escenarios nuevos se numeran `SS128` en adelante, a continuación del máximo vigente (`SS127`). Los escenarios invertidos o precisados conservan
  su identificador (ADR-0018) y llevan una nota fechada con el texto anterior; ninguno se borra.
- **Orden de las secciones.** `RENAMED` precede a `MODIFIED` porque el requisito renombrado se modifica bajo su nombre nuevo.
- **Texto fijado por la documentación:** aviso de inactividad, «Su sesión se cerrará en 5 minutos por inactividad.» (`docs/ui-ux/04`:612). Sesión
  vencida, el de 13b-1: «Su sesión se cerró por seguridad. Vuelva a iniciar sesión.». El resto de los textos los fija el catálogo `admin`.
- **Valores de trabajo (supuestos de la propuesta; el diseño los fija como constantes con nombre):**
  - **Tiempos (D4):** inactividad 30 minutos; aviso 5 minutos antes (a los 25); vencimiento absoluto 12 horas; token de acceso 10 minutos
    (`docs/03-seguridad.md`:515). Fijos en el cliente hasta que el contrato de API los exponga.
  - **Antelación del aviso de vencimiento absoluto (D6):** 5 minutos. Supuesto de esta especificación: la propuesta no fija el valor.
  - **Cadencia mínima de refresco por actividad (R1), «T»:** 60 segundos por pestaña. Supuesto de esta especificación: la propuesta no fija el valor;
    DEBE ser mucho menor que la ventana de aviso de 5 minutos.
  - **Eventos que cuentan como actividad (D4):** pulsación de tecla, pulsación del puntero (ratón, lápiz o toque) y desplazamiento o rueda. El mero
    movimiento del puntero NO cuenta. Supuesto de esta especificación.
  - **Fin de la cuenta atrás (D5):** cierre local y `DELETE /api/v1/auth/sessions/current` de mejor esfuerzo.
  - **Mientras el aviso de inactividad está abierto**, la actividad de la persona NO extiende la sesión: solo «Continuar» (o `Escape`, equivalente) lo
    hace. Supuesto de esta especificación: evita extender la sesión por una tecla accidental.
  - **Canal entre pestañas (S1/D8):** `BroadcastChannel` con un mensaje cerrado de dos tipos, cierre de sesión (con motivo: por la persona o por
    vencimiento) y plazos (`idleExpiresAt`, `sessionExpiresAt`), validado por esquema. Jamás transporta un token.
  - **Web Lock:** el de 13b-1, `confia.admin.session.refresh`, exclusivo. Es el único camino de todo refresco y del `DELETE` de cierre.
- La lista de ausencias del último requisito es parte de la especificación: un comportamiento listado como ausente no debe agregarse sin modificar
  ese requisito.

## ADDED Requirements

### Requisito: Los plazos de la sesión son metadatos no secretos, viven fuera del almacén de tokens y el servidor manda en ellos

El sistema DEBE conservar `idleExpiresAt` y `sessionExpiresAt` de toda respuesta del servidor que los traiga (inicio de sesión con sesión completa,
verificación e inscripción correctas, refresco y lectura de la sesión actual) como metadatos no secretos, fuera del almacén de tokens: la regla
`admin-token-store-is-private` NO cambia y el almacén guarda solo secretos. Los plazos NO DEBEN persistirse en `localStorage`, `sessionStorage`, IndexedDB,
la URL ni `history.state`; tras recargar, se recuperan de la respuesta del refresco de restauración. El plazo de la última respuesta reemplaza al
anterior, aunque sea más temprano: el servidor manda. El plazo efectivo de cierre es el menor entre `idleExpiresAt` y `sessionExpiresAt`. Al terminar la
sesión local los plazos DEBEN borrarse y toda escucha y todo temporizador de la sesión DEBEN cancelarse. Leer `GET /api/v1/auth/sessions/current` no mueve la
inactividad en el servidor y no se usa para confirmar un aviso.

#### Escenario: [SS128] Cada respuesta con plazos los deja guardados

- **DADO** respuestas con `idleExpiresAt` «I» y `sessionExpiresAt` «S» de un inicio de sesión con sesión completa, de una verificación correcta, de una inscripción
  correcta, de un refresco y de una lectura de la sesión actual
- **CUANDO** la sesión procesa cada una
- **ENTONCES** en los cinco casos el módulo de plazos expone «I» y «S» de esa respuesta

#### Escenario: [SS129] Los plazos no están en el almacén de tokens ni en almacenamiento web

- **DADO** un recorrido de inicio de sesión con instantes de vencimiento reconocibles
- **CUANDO** termina cada pantalla
- **ENTONCES** el almacén de tokens exporta y guarda solo tokens, y la regla `admin-token-store-is-private` sigue en verde sin cambios
- **Y** ningún instante de vencimiento aparece, en ninguna forma de codificación habitual, en `localStorage`, `sessionStorage`, IndexedDB, la URL ni
  `history.state`

#### Escenario: [SS130] Al recargar, los plazos salen de la respuesta del refresco

- **DADO** una sesión viva, una cookie CSRF y un refresco de restauración cuya respuesta trae plazos «I2» y «S2»
- **CUANDO** se recarga la aplicación
- **ENTONCES** los plazos expuestos tras la restauración son «I2» y «S2», y no se leyó ninguno de un almacenamiento web

#### Escenario: [SS131] El servidor manda, también cuando adelanta el plazo

- **DADO** plazos guardados con `idleExpiresAt` a las 10:30 y un refresco que responde `idleExpiresAt` a las 10:20
- **CUANDO** la sesión procesa la respuesta
- **ENTONCES** el plazo guardado es el de las 10:20 y no el mayor de los dos

#### Escenario: [SS132] El plazo efectivo es el menor de los dos

- **DADO** `idleExpiresAt` posterior a `sessionExpiresAt`, y en otra ejecución anterior
- **CUANDO** se calcula el plazo efectivo
- **ENTONCES** es el menor de los dos en ambos casos

#### Escenario: [SS133] Terminar la sesión borra los plazos y cancela los temporizadores

- **DADO** la página autenticada con plazos guardados, una escucha de actividad y un temporizador pendiente
- **CUANDO** la sesión local termina por cierre de sesión, por vencimiento o por un rechazo del servidor
- **ENTONCES** los plazos están vacíos, no queda ningún temporizador pendiente de la sesión y la actividad de la persona deja de emitir peticiones
- **Y** no aparece ningún aviso después

### Requisito: El planificador calcula desde plazos absolutos con el reloj del servidor y se reevalúa al volver a la pestaña

El sistema DEBE calcular cuándo mostrar cada aviso y cuándo terminar la sesión a partir de los plazos absolutos y de un reloj corregido por el desfase
medido con la cabecera `Date` de las respuestas del servidor, no por la cuenta de un temporizador. Un temporizador retrasado, una pestaña en segundo plano
o la suspensión del equipo NO DEBEN retrasar un aviso ni un cierre: el planificador DEBE reevaluar al recuperar la visibilidad (`visibilitychange`). Si no
hay cabecera `Date` utilizable, DEBE funcionar sin corrección y sin error. El planificador DEBE usar el menor número de temporizadores posible (uno, para la
próxima transición) y NO DEBE sondear. Un temporizador solo cambia el estado de la interfaz y, al cerrar la sesión, emite el `DELETE` de mejor esfuerzo;
nunca pide un refresco.

#### Escenario: [SS134] El aviso se programa a 5 minutos del plazo de inactividad

- **DADO** un `idleExpiresAt` a 30 minutos del reloj controlado de la prueba y ninguna actividad
- **CUANDO** el reloj llega a 24 minutos y 59 segundos, y luego a 25 minutos
- **ENTONCES** el aviso de inactividad no está en el documento en el primer instante y sí lo está en el segundo

#### Escenario: [SS135] Una suspensión no retrasa el aviso

- **DADO** el reloj del sistema adelantado 27 minutos de golpe (suspensión del equipo) con los temporizadores sin disparar
- **CUANDO** la pestaña recupera la visibilidad (`visibilitychange`)
- **ENTONCES** el aviso de inactividad aparece con el tiempo restante calculado desde el plazo absoluto (3 minutos) y no con 5

#### Escenario: [SS136] Volver con el plazo vencido termina la sesión sin mostrar el aviso

- **DADO** el reloj adelantado 31 minutos de golpe sin actividad
- **CUANDO** la pestaña recupera la visibilidad
- **ENTONCES** la sesión local termina, se muestra el inicio de sesión con el mensaje de sesión vencida y el aviso de inactividad nunca aparece

#### Escenario: [SS137] Un reloj del cliente adelantado se corrige con `Date`

- **DADO** un reloj del navegador 3 minutos adelantado respecto del servidor y una respuesta con cabecera `Date` del servidor
- **CUANDO** el aviso de inactividad se programa con un `idleExpiresAt` a 30 minutos del reloj del servidor
- **ENTONCES** el aviso aparece 25 minutos después, medidos en el reloj del servidor, y no 3 minutos antes

#### Escenario: [SS138] Un reloj del cliente atrasado se corrige con `Date`

- **DADO** un reloj del navegador 3 minutos atrasado respecto del servidor y una respuesta con cabecera `Date`
- **CUANDO** el aviso de inactividad se programa con un `idleExpiresAt` a 30 minutos del reloj del servidor
- **ENTONCES** el aviso aparece 25 minutos después, medidos en el reloj del servidor, y no 3 minutos tarde

#### Escenario: [SS139] Sin cabecera `Date` no hay corrección y no hay error

- **DADO** respuestas sin cabecera `Date`
- **CUANDO** el planificador calcula el aviso
- **ENTONCES** usa el reloj del navegador sin corrección, sin error visible ni mensaje de error en la consola

#### Escenario: [SS140] Una cabecera `Date` inválida se ignora

- **DADO** una respuesta con `Date: abc` tras otra con una cabecera válida
- **CUANDO** el planificador procesa la segunda
- **ENTONCES** conserva el desfase de la respuesta válida y no produce `NaN` ni un desfase nulo por error

#### Escenario: [SS141] Hay a lo sumo un temporizador de transición y ningún sondeo

- **DADO** la página autenticada con plazos guardados, sin actividad y con el reloj controlado de la prueba
- **CUANDO** se cuentan los temporizadores pendientes de la sesión durante 25 minutos de reloj simulado
- **ENTONCES** hay a lo sumo uno pendiente en cada instante y ninguno es un intervalo repetido que consulte al servidor

### Requisito: La actividad de la persona extiende la sesión con un refresco de cadencia mínima, siempre por el coordinador

El sistema DEBE detectar la actividad de la persona (pulsación de tecla, pulsación del puntero y desplazamiento o rueda; el mero movimiento del puntero no
cuenta) mientras hay sesión completa y, ante ella, pedir un refresco con una cadencia mínima de T (60 s) por pestaña: la actividad dentro de T desde el
último refresco no emite nada. Todo refresco por actividad DEBE pasar por el coordinador existente (promesa compartida por pestaña y Web Lock exclusivo
entre pestañas), de modo que como máximo hay una petición de refresco en vuelo a la vez. Un refresco por actividad que falla por red NO DEBE reintentarse
ni mostrar error; cualquier otro resultado distinto de `200` DEBE terminar la sesión local como en `staff-sign-in`. Sin actividad no DEBE haber refrescos.
Mientras el aviso de inactividad está abierto, la actividad NO DEBE emitir un refresco. Sin sesión completa, la actividad NO DEBE emitir peticiones.

#### Escenario: [SS142] Teclado, puntero y desplazamiento cuentan como actividad

- **DADO** la página autenticada con la última rotación hace más de T
- **CUANDO** la persona pulsa una tecla, y en otras ejecuciones pulsa el puntero y desplaza la rueda
- **ENTONCES** en cada caso el servidor registra exactamente una petición de refresco

#### Escenario: [SS143] El mero movimiento del puntero no cuenta

- **DADO** la página autenticada con la última rotación hace más de T
- **CUANDO** el puntero se mueve sin pulsar durante un minuto
- **ENTONCES** el servidor registra cero peticiones de refresco

#### Escenario: [SS144] Una ráfaga de actividad dentro de T hace un solo refresco

- **DADO** la página autenticada con la última rotación hace más de T
- **CUANDO** ocurren 100 eventos de actividad en los siguientes T menos 1 segundos
- **ENTONCES** el servidor registra exactamente una petición de refresco

#### Escenario: [SS145] Pasada la cadencia, la actividad vuelve a refrescar

- **DADO** un refresco por actividad hecho en el instante 0
- **CUANDO** hay actividad en el instante T menos 1 segundos y de nuevo en el instante T
- **ENTONCES** la primera no emite nada y la segunda emite exactamente un refresco más

#### Escenario: [SS146] El refresco por actividad comparte la petición con cualquier otro disparo

- **DADO** una petición de la persona con `Bearer` que requiere refrescar antes por tener menos de 60 segundos de token y, en el mismo instante, un evento
  de actividad
- **CUANDO** el servidor simulado responde
- **ENTONCES** el servidor registró una sola petición de refresco y ambos disparos recibieron el mismo resultado

#### Escenario: [SS147] Dos pestañas activas no se solapan

- **DADO** dos pestañas con la misma sesión, un servidor simulado que modela la rotación del refresco con su gracia de 10 segundos y actividad simultánea en las dos
- **CUANDO** ambas pasan la cadencia y piden refrescar en el mismo instante
- **ENTONCES** el servidor registra una concurrencia máxima de una petición de refresco
- **Y** ninguna recibe `401 session-expired` por reutilización de la cookie y las dos siguen en la página autenticada

#### Escenario: [SS148] Un error de red en el refresco por actividad no se reintenta ni se muestra

- **DADO** un refresco por actividad cuyo transporte falla por red
- **CUANDO** pasan 60 segundos sin nueva actividad
- **ENTONCES** el servidor registra un solo intento, no se muestra ningún error y la sesión local sigue
- **Y** una actividad posterior, pasada la cadencia, puede emitir un intento nuevo

#### Escenario: [SS149] Un rechazo del refresco por actividad termina la sesión local

- **DADO** un refresco por actividad cuya respuesta es `401 session-expired`
- **CUANDO** la aplicación la procesa
- **ENTONCES** vacía la memoria de tokens y la caché de consultas, y muestra el inicio de sesión con el mensaje de sesión vencida, sin un segundo intento

#### Escenario: [SS150] Sin actividad no hay refrescos

- **DADO** la página autenticada y 20 minutos de reloj simulado sin ningún evento de actividad, con pérdida y recuperación del foco, cambio de visibilidad y
  paso a `offline` y a `online`
- **CUANDO** se cuentan las peticiones de refresco
- **ENTONCES** son cero

#### Escenario: [SS151] Sin sesión completa la actividad no emite peticiones

- **DADO** el inicio de sesión visible, y en otra ejecución la sesión recién cerrada
- **CUANDO** la persona escribe, pulsa y desplaza
- **ENTONCES** el servidor registra cero peticiones por la actividad

#### Escenario: [SS152] Con el aviso abierto, la actividad no refresca

- **DADO** el aviso de inactividad abierto
- **CUANDO** la persona pulsa teclas y desplaza fuera de los controles del diálogo
- **ENTONCES** el servidor registra cero peticiones de refresco y el plazo guardado no cambia

### Requisito: Cinco minutos antes del plazo de inactividad aparece un diálogo modal de aviso que permite continuar o cerrar sesión

El sistema DEBE mostrar, 5 minutos antes del plazo efectivo de inactividad, un diálogo modal con el texto fijado «Su sesión se cerrará en 5 minutos por
inactividad.», el tiempo restante calculado desde el plazo absoluto y dos acciones: «Continuar» y «Cerrar sesión». El diálogo DEBE tener nombre accesible y
descripción, anunciarse con prioridad `assertive` (`docs/ui-ux/03` §4), confinar el foco con el resto de la página inerte, y NO DEBE anunciar cada segundo de la
cuenta atrás (como máximo cambia el texto de las regiones activas al aparecer y al terminar). El foco inicial DEBE estar dentro del diálogo, en el primer
elemento interactivo («Continuar»), y NUNCA en «Cerrar sesión». `Escape` DEBE equivaler a «Continuar». Al cerrarse, el foco DEBE volver al elemento que lo tenía al
abrirse (o al `h1` si ese elemento ya no existe) y NO DEBE perderse lo que la persona escribía. Mostrar el aviso NO DEBE emitir peticiones, ni siquiera
la lectura de `/current`. «Continuar» DEBE pedir un único refresco por el coordinador; con `200` guarda los plazos nuevos y cierra el aviso; ante un error
de red mantiene el aviso con un `Alert` de error y un reintento que ejecuta la persona, sin reintentar solo y sin detener la cuenta atrás; ante otro rechazo
termina la sesión local. «Cerrar sesión» DEBE ejecutar el cierre de sesión normal. Si el plazo de inactividad ya no es el efectivo porque se acerca antes el
vencimiento absoluto, DEBE aplicarse el requisito del aviso de vencimiento absoluto y no este.

#### Escenario: [SS153] El aviso aparece con el texto fijado y sus dos acciones

- **DADO** la página autenticada con un plazo de inactividad a 30 minutos y ninguna actividad
- **CUANDO** el reloj controlado llega a 25 minutos
- **ENTONCES** aparece un diálogo modal con el texto «Su sesión se cerrará en 5 minutos por inactividad.», un tiempo restante, y los botones «Continuar» y
  «Cerrar sesión» con nombre accesible no vacío
- **Y** el diálogo tiene nombre accesible y descripción

#### Escenario: [SS154] El aviso se anuncia con prioridad `assertive`

- **DADO** un observador de regiones activas y el aviso que aparece
- **CUANDO** se inspecciona el árbol de accesibilidad
- **ENTONCES** el aviso es un diálogo de alerta o contiene una región `assertive` con su mensaje, y no una región `polite`

#### Escenario: [SS155] El foco inicial es «Continuar» y nunca «Cerrar sesión»

- **DADO** el aviso que aparece mientras la persona escribe en un campo
- **CUANDO** se abre
- **ENTONCES** el elemento con el foco es «Continuar»
- **Y** «Cerrar sesión» no tiene el foco en ningún momento de la apertura

#### Escenario: [SS156] El foco queda confinado y el fondo inerte

- **DADO** el aviso abierto
- **CUANDO** la persona pulsa `Tab` y `Shift+Tab` más veces que controles hay
- **ENTONCES** el foco cicla entre los controles del diálogo sin salir de él
- **Y** el contenido de la página detrás es inerte o está oculto a las tecnologías de asistencia

#### Escenario: [SS157] Mostrar el aviso no hace peticiones

- **DADO** la página autenticada con el registro de todas las peticiones
- **CUANDO** aparece el aviso y la persona no hace nada durante los 5 minutos de la cuenta atrás, hasta un segundo antes de terminar
- **ENTONCES** el servidor registra cero peticiones desde la aparición del aviso, incluida la lectura de `GET /api/v1/auth/sessions/current`

#### Escenario: [SS158] La cuenta atrás no se anuncia cada segundo

- **DADO** el aviso abierto y un observador de mutaciones sobre todas las regiones activas
- **CUANDO** transcurre la cuenta atrás completa
- **ENTONCES** el texto de las regiones activas cambia como máximo dos veces: al aparecer el aviso y al terminar

#### Escenario: [SS159] «Continuar» extiende la sesión con un solo refresco

- **DADO** el aviso abierto y un servidor que responde `200` al refresco con plazos nuevos
- **CUANDO** la persona activa «Continuar»
- **ENTONCES** el servidor registra exactamente un `POST /api/v1/auth/sessions/refresh` con `X-CSRF-Token`, sin `Authorization` y sin cuerpo
- **Y** el aviso se cierra y los plazos guardados son los de la respuesta

#### Escenario: [SS160] El foco vuelve al origen y lo escrito se conserva

- **DADO** la persona escribiendo «CENTINELA-TEXTO» en un campo cuando aparece el aviso
- **CUANDO** activa «Continuar»
- **ENTONCES** el foco vuelve a ese campo y su valor sigue siendo «CENTINELA-TEXTO»
- **Y** si el elemento de origen ya no existe, el foco queda en el `h1`

#### Escenario: [SS161] `Escape` equivale a «Continuar»

- **DADO** el aviso abierto
- **CUANDO** la persona pulsa `Escape`
- **ENTONCES** se emite exactamente un refresco y, con `200`, el aviso se cierra

#### Escenario: [SS162] Una segunda activación en vuelo no emite una segunda petición

- **DADO** «Continuar» activado con un servidor que retiene la respuesta
- **CUANDO** la persona lo activa tres veces más con teclado y con ratón
- **ENTONCES** el servidor registra una sola petición de refresco
- **Y** el botón expone `aria-busy="true"` sin estar deshabilitado

#### Escenario: [SS163] Un error de red al continuar mantiene el aviso y no reintenta solo

- **DADO** el aviso abierto y un refresco que falla por red
- **CUANDO** la persona activa «Continuar»
- **ENTONCES** el aviso sigue abierto con un `Alert` de error y un reintento de etiqueta no vacía, y la cuenta atrás continúa
- **Y** el servidor no registra un segundo intento hasta que la persona activa el reintento

#### Escenario: [SS164] Un rechazo al continuar termina la sesión

- **DADO** el aviso abierto y un refresco que responde `401 session-expired`
- **CUANDO** la persona activa «Continuar»
- **ENTONCES** la sesión local termina y se muestra el inicio de sesión con el mensaje de sesión vencida

#### Escenario: [SS165] «Cerrar sesión» del aviso ejecuta el cierre normal

- **DADO** el aviso abierto y un servidor que responde `204` y borra las cookies
- **CUANDO** la persona activa «Cerrar sesión»
- **ENTONCES** el servidor registra un `DELETE` con `X-CSRF-Token` y sin `Authorization`, y se llega al inicio de sesión como en el escenario SS107

#### Escenario: [SS166] Con la actividad regular el aviso nunca aparece

- **DADO** la página autenticada y una pulsación de tecla cada 2 minutos durante 60 minutos de reloj simulado
- **CUANDO** se observa el documento
- **ENTONCES** el aviso de inactividad no aparece en ningún momento y la sesión sigue viva
- **Y** el servidor registra a lo sumo un refresco por cada T de reloj simulado

### Requisito: Al terminar la cuenta atrás la sesión se cierra localmente y se envía un `DELETE` de mejor esfuerzo

El sistema DEBE, al llegar al plazo efectivo de cierre sin que la persona haya continuado ni cerrado sesión, terminar la sesión local (vaciar la memoria de
tokens, la caché de consultas, los plazos y las escuchas), cerrar el diálogo, mostrar el inicio de sesión con el mensaje de sesión vencida y emitir un único
`DELETE /api/v1/auth/sessions/current` de mejor esfuerzo, por el mismo bloqueo que el cierre de sesión. Un fallo de ese `DELETE` NO DEBE mostrarse ni
reintentarse: la sesión local ya está cerrada. El cierre por vencimiento NO DEBE emitir un refresco.

#### Escenario: [SS167] Cierre local y un solo `DELETE` al vencer

- **DADO** el aviso abierto y ninguna acción de la persona
- **CUANDO** el reloj controlado llega al plazo de inactividad
- **ENTONCES** la memoria de tokens y la caché de consultas están vacías y se muestra el inicio de sesión con «Su sesión se cerró por seguridad. Vuelva a iniciar sesión.»
- **Y** el servidor registra exactamente un `DELETE` con `X-CSRF-Token` y sin `Authorization`, y cero refrescos
- **Y** el diálogo ya no está en el documento

#### Escenario: [SS168] Un fallo del `DELETE` de mejor esfuerzo no se muestra ni se reintenta

- **DADO** el vencimiento por inactividad y un `DELETE` que falla por red, y en otra ejecución responde `403 csrf-invalid`
- **CUANDO** pasan 60 segundos
- **ENTONCES** el servidor registra un solo intento en cada caso, no aparece ningún error y se sigue mostrando el inicio de sesión

#### Escenario: [SS169] El inicio de sesión recibe el foco en su encabezado tras el cierre por vencimiento

- **DADO** el cierre por vencimiento con el foco dentro del diálogo
- **CUANDO** aparece el inicio de sesión
- **ENTONCES** hay exactamente un `h1` y es el elemento con el foco, y el aviso de sesión vencida está en una región `polite` (escenario SS60)

#### Escenario: [SS170] Recargar tras el cierre por vencimiento no sondea

- **DADO** el cierre por vencimiento terminado y el `DELETE` respondido con `204` que borra las cookies
- **CUANDO** se recarga la aplicación
- **ENTONCES** el servidor registra cero peticiones de refresco y se muestra el inicio de sesión

### Requisito: Cerca del vencimiento absoluto aparece un aviso que no se puede extender y, al llegar, la sesión se cierra

El sistema DEBE mostrar, 5 minutos antes de `sessionExpiresAt`, un aviso de vencimiento absoluto con el tiempo restante, anunciado con prioridad `assertive`
y sin anunciar cada segundo, que NO DEBE ofrecer «Continuar» ni ninguna acción de extensión (decisión D6: el servidor no permite extender el límite
absoluto). Es un diálogo de alerta modal, como el de inactividad (`<dialog>` nativo, decisión D12), con dos acciones: «Entendido», que recibe el foco inicial y
descarta el aviso sin extender nada (`Escape` equivale a «Entendido»), y «Cerrar sesión ahora». Mostrarlo NO DEBE emitir peticiones. La
actividad de la persona NO DEBE cambiar `sessionExpiresAt`, y el aviso descartado NO DEBE reaparecer salvo que el plazo cambie. Si el plazo absoluto cae dentro de la ventana de aviso de inactividad, solo
DEBE mostrarse el aviso de vencimiento absoluto. Al llegar `sessionExpiresAt`, DEBE aplicarse el cierre local con el `DELETE` de mejor esfuerzo y el mensaje
de sesión vencida.

#### Escenario: [SS171] El aviso aparece 5 minutos antes y no ofrece «Continuar»

- **DADO** un `sessionExpiresAt` a 12 horas de la restauración y actividad regular que mantiene viva la inactividad
- **CUANDO** el reloj controlado llega a 11 horas y 55 minutos
- **ENTONCES** aparece el aviso de vencimiento absoluto con un tiempo restante
- **Y** no existe en él ningún botón ni enlace «Continuar» ni de extensión

#### Escenario: [SS172] El aviso se anuncia con prioridad `assertive` y sin anuncio por segundo

- **DADO** el aviso de vencimiento absoluto y un observador de mutaciones sobre las regiones activas
- **CUANDO** transcurren sus 5 minutos
- **ENTONCES** el aviso se anuncia con región `assertive` y el texto de las regiones activas cambia como máximo dos veces

#### Escenario: [SS173] «Entendido» descarta el aviso sin extender y devuelve el foco

- **DADO** el aviso de vencimiento absoluto abierto sobre un campo con el foco previo, y «Entendido» con el foco inicial
- **CUANDO** la persona activa «Entendido», y en otra ejecución pulsa `Escape`
- **ENTONCES** en ambos casos el aviso se cierra, el foco vuelve al campo y su valor se conserva
- **Y** el servidor registra cero peticiones y `sessionExpiresAt` guardado no cambia

> **Nota (decisión D12, tras el diseño).** Texto previo de este escenario: «El aviso no bloquea el trabajo» (aviso no modal). El diseño lo resolvió como diálogo de alerta modal.

#### Escenario: [SS174] Mostrarlo o tener actividad no hace peticiones de extensión

- **DADO** el aviso de vencimiento absoluto abierto
- **CUANDO** pasan 4 minutos con una pulsación de tecla cada 30 segundos dentro del diálogo, sin activar ninguna de sus acciones
- **ENTONCES** `sessionExpiresAt` guardado no cambia y el aviso sigue visible
- **Y** el servidor registra cero peticiones, porque con un aviso abierto la actividad no refresca (escenario SS152) y mostrarlo no hace peticiones

#### Escenario: [SS175] Si ambos plazos están dentro de la ventana, solo se muestra el absoluto

- **DADO** un `idleExpiresAt` y un `sessionExpiresAt` que vencen con 3 minutos de diferencia dentro de los últimos 5 minutos de la sesión
- **CUANDO** el reloj controlado entra en esa ventana
- **ENTONCES** se muestra el aviso de vencimiento absoluto, no hay diálogo de inactividad y no existe «Continuar» en el documento

#### Escenario: [SS176] Al llegar el plazo absoluto la sesión se cierra

- **DADO** el aviso de vencimiento absoluto abierto
- **CUANDO** el reloj controlado llega a `sessionExpiresAt`
- **ENTONCES** se muestra el inicio de sesión con el mensaje de sesión vencida, las memorias están vacías y el servidor registra un `DELETE` de mejor esfuerzo y cero refrescos

### Requisito: Las pestañas comparten eventos de sesión por un canal sin secretos con mensaje cerrado y validado

El sistema DEBE sincronizar las pestañas del mismo navegador con un `BroadcastChannel` cuyo mensaje es una unión cerrada de dos tipos: cierre de sesión
(con motivo: por la persona o por vencimiento) y plazos (`idleExpiresAt` y `sessionExpiresAt`). Todo mensaje recibido DEBE validarse con un esquema antes de
usarse; uno que no lo cumpla, o que traiga campos de más, DEBE ignorarse sin error. El canal NO DEBE transportar jamás un token, un secreto, un código ni un
dato de la persona. Al recibir un cierre de sesión, la pestaña con sesión completa DEBE terminar su sesión local sin emitir su propio `DELETE` y mostrar el
inicio de sesión (con el mensaje de sesión vencida solo si el motivo fue el vencimiento). Al recibir plazos, la pestaña con sesión completa DEBE reemplazar
los suyos, cerrar el aviso de inactividad si está abierto y NO DEBE emitir ningún refresco por esa recepción. Una pestaña sin sesión completa DEBE ignorar los
mensajes. La recepción de un mensaje NO DEBE provocar que la pestaña lo reenvíe. Cuando la persona cierra la sesión, extiende o vence, la pestaña que
originó el evento DEBE emitir el mensaje que corresponde. Sin `BroadcastChannel`, cada pestaña DEBE funcionar por separado sin error.

#### Escenario: [SS177] Los mensajes del canal cumplen el esquema y no llevan secretos

- **DADO** un recorrido de inicio de sesión, extensión, cierre de sesión y vencimiento con centinelas únicos de secretos y la captura de todo mensaje del canal
- **CUANDO** termina
- **ENTONCES** cada mensaje cumple el esquema cerrado de dos tipos
- **Y** ninguno contiene un centinela, un token ni una cadena con forma de JWT

#### Escenario: [SS178] Cerrar sesión en una pestaña lleva a las otras al inicio de sesión

- **DADO** dos pestañas con sesión completa y la página autenticada en ambas
- **CUANDO** la persona cierra sesión en la primera
- **ENTONCES** la segunda muestra el inicio de sesión sin mensaje de sesión vencida, con la memoria de tokens y la caché de consultas vacías
- **Y** el servidor registró un único `DELETE`, el de la primera pestaña

#### Escenario: [SS179] El vencimiento en una pestaña lleva a las otras al inicio de sesión con el mensaje

- **DADO** dos pestañas con sesión completa y el cierre por vencimiento en la primera
- **CUANDO** la primera termina su sesión local
- **ENTONCES** la segunda muestra el inicio de sesión con «Su sesión se cerró por seguridad. Vuelva a iniciar sesión.»
- **Y** la segunda no emite su propio `DELETE` ni un refresco

#### Escenario: [SS180] Extender en una pestaña actualiza los plazos de las otras

- **DADO** dos pestañas con el aviso de inactividad abierto en ambas
- **CUANDO** la persona activa «Continuar» en la primera
- **ENTONCES** en la segunda el aviso se cierra y sus plazos son los nuevos
- **Y** la segunda no emite ninguna petición por esa recepción

#### Escenario: [SS181] Una pestaña no reenvía lo que recibe

- **DADO** dos pestañas y un cierre de sesión que originó la primera
- **CUANDO** la segunda lo recibe
- **ENTONCES** el canal registra un solo mensaje de cierre de sesión en total, el de la primera

#### Escenario: [SS182] Un mensaje que no cumple el esquema se ignora

- **DADO** una pestaña autenticada y mensajes inyectados al canal: uno con un tipo desconocido, uno con `idleExpiresAt` que no es un instante, uno con un campo
  extra `token` y uno que no es un objeto
- **CUANDO** los recibe
- **ENTONCES** ninguno cambia sus plazos ni su sesión, no se lanza ninguna excepción y no se emite ninguna petición

#### Escenario: [SS183] Una pestaña sin sesión completa ignora los mensajes

- **DADO** una pestaña en el inicio de sesión, y otra en la restauración
- **CUANDO** reciben un mensaje de plazos y uno de cierre de sesión válidos
- **ENTONCES** ninguna cambia de pantalla ni emite peticiones

#### Escenario: [SS184] Sin `BroadcastChannel` la aplicación degrada sin error

- **DADO** un navegador sin `BroadcastChannel`
- **CUANDO** se inicia sesión, se extiende y se cierra sesión
- **ENTONCES** todo funciona en la pestaña sin error visible ni mensaje de error en la consola

### Requisito: La API simulada modela la inactividad y el vencimiento absoluto, y los recorridos nuevos corren con reloj controlado

El sistema DEBE ampliar la API simulada (`e2e/mock-api`) para modelar la inactividad (vence a los 30 minutos de la última rotación del refresco; la lectura de
`/current` no la mueve, `docs/ui-ux/04` §7), el vencimiento absoluto (12 horas desde el inicio de la sesión, que ninguna rotación extiende) y un token de
acceso de 10 minutos (`docs/03-seguridad.md`:515, en lugar de los 15 minutos de 13b-1). Toda respuesta simulada con plazos DEBE validarse con los esquemas de
`@confia/contracts/admin` y DEBE llevar cabecera `Date`. Los recorridos nuevos DEBEN usar el reloj controlado de Playwright (`page.clock`) y NO DEBEN esperar
tiempo real de minutos. Los recorridos simulados NO DEBEN presentarse como prueba de la pila real (escenario SS123).

#### Escenario: [SS185] La inactividad vence a los 30 minutos de la última rotación

- **DADO** el servidor simulado con un refresco rotado hace 29 minutos y otro hace 31 minutos
- **CUANDO** se presenta cada cookie a un refresco
- **ENTONCES** el primero se acepta y el segundo recibe `401 session-expired`

#### Escenario: [SS186] La lectura de la sesión no mueve la inactividad en el simulador

- **DADO** una sesión simulada rotada hace 20 minutos y 10 lecturas de `GET /api/v1/auth/sessions/current` en esos 20 minutos
- **CUANDO** a los 31 minutos desde la rotación se presenta la cookie a un refresco
- **ENTONCES** recibe `401 session-expired`

#### Escenario: [SS187] El vencimiento absoluto lo fija el inicio y no lo extiende la rotación

- **DADO** el servidor simulado con una sesión iniciada hace 11 horas y 59 minutos y rotaciones cada 10 minutos
- **CUANDO** la respuesta de la rotación trae `sessionExpiresAt`
- **ENTONCES** es el mismo instante que en las respuestas anteriores y no se desplaza
- **Y** a las 12 horas el refresco recibe `401 session-expired`

#### Escenario: [SS188] El token de acceso vence a los 10 minutos

- **DADO** una respuesta simulada de refresco
- **CUANDO** se lee `accessTokenExpiresAt` frente al reloj del servidor simulado
- **ENTONCES** la diferencia es de 10 minutos

#### Escenario: [SS189] Las respuestas con plazos llevan `Date` y cumplen el contrato

- **DADO** respuestas simuladas de inicio de sesión, verificación, refresco y lectura de la sesión actual
- **CUANDO** el servidor simulado intenta enviarlas
- **ENTONCES** cada una cumple su esquema de `@confia/contracts/admin` y lleva una cabecera `Date` válida
- **Y** una respuesta con `idleExpiresAt` que no es un instante hace fallar la prueba

#### Escenario: [SS190] Existen los recorridos nuevos exigidos

- **DADO** la suite de recorridos de `apps/admin-web`
- **CUANDO** se enumeran
- **ENTONCES** incluye, además de los siete de SS117, el aviso de inactividad con «Continuar», el aviso que termina con cierre local, la extensión por
  actividad, el aviso de vencimiento absoluto, la extensión con dos pestañas, el cierre de sesión sincronizado entre dos pestañas y el tema elegido que se
  conserva al recargar

#### Escenario: [SS191] Los recorridos de tiempo usan reloj controlado

- **DADO** los recorridos nuevos de tiempo
- **CUANDO** se inspeccionan sus esperas
- **ENTONCES** todos avanzan el tiempo con el reloj controlado de Playwright y ninguno espera 30 segundos o más de tiempo real

## RENAMED Requirements

### Requisito: La página mínima autenticada confirma la sesión sin datos personales y ofrece el cierre de sesión → El panel de inicio confirma la sesión sin datos personales y el cierre de sesión vive en el menú de usuario

(Reason: con el shell de 13b-2 la página deja de ser mínima: es el panel de bienvenida dentro del layout protegido y el botón «Cerrar sesión» pasa al menú de usuario.)
(Migration: las pruebas y referencias a SS103 a SS112 conservan sus identificadores; el texto de cada escenario se actualiza en la sección `MODIFIED` y se agrega una nota fechada.)

## MODIFIED Requirements

### Requisito: El panel de inicio confirma la sesión sin datos personales y el cierre de sesión vive en el menú de usuario

El sistema DEBE mostrar tras iniciar sesión el panel de inicio de la capacidad `admin-web-shell` (encabezado de nivel 1 y mensaje de bienvenida), sin nombre,
correo, rol, identificadores ni vencimientos de la persona. Al entrar DEBE leer `GET /api/v1/auth/sessions/current` con `Bearer`; mientras tanto DEBE mostrar un
estado de carga en el área de contenido, con el shell ya visible; un `200` DEBE guardar sus metadatos en la caché de consultas sin mostrarlos; un error de
red o `5xx` DEBE mostrar `ErrorState` con reintento; un `401` DEBE llevar al inicio de sesión con el mensaje de sesión vencida. El estado vacío no aplica a
este panel. El cierre de sesión DEBE ofrecerse como la acción «Cerrar sesión» del menú de usuario y DEBE emitir `DELETE /api/v1/auth/sessions/current` sin
`Bearer`; ante `204` DEBE vaciar la memoria de tokens, la caché de consultas y los plazos, avisar a las demás pestañas y llevar al inicio de sesión; ante
cualquier otro resultado DEBE mostrar un error con reintento y NO declarar la sesión cerrada, porque la cookie seguiría viva.
(Previously: la página mínima mostraba un botón «Cerrar sesión» propio dentro de `ScreenLayout`, sin shell, menú ni aviso a otras pestañas.)

> **Nota fechada 2026-11-10 (cambio `admin-web-shell-and-session-lifecycle`, 13b-2).** Los escenarios SS103 a SS112 conservan su identificador. El
> «botón «Cerrar sesión» de la página» de 13b-1 pasa a ser la acción «Cerrar sesión» del menú de usuario. Los cambios de texto de cada escenario se indican
> en su propia nota. Ninguno se borra.

#### Escenario: [SS103] El panel no muestra datos de la persona

- **DADO** una lectura de la sesión con los centinelas «CENT-CUENTA», «CENT-INSTITUCION» y «CENT-SESION» en `accountId`,
  `institutionId` y `sessionId`
- **CUANDO** se muestra el panel
- **ENTONCES** ninguno de los centinelas aparece en el documento
- **Y** hay un `h1` y un mensaje de bienvenida, y el menú de usuario ofrece «Cerrar sesión» (el botón ya no está en el `main`)

> **Nota fechada 2026-11-10 (13b-2).** Texto anterior: «hay un `h1`, un mensaje de confirmación y el botón «Cerrar sesión»».

#### Escenario: [SS104] Estado de carga del panel

- **DADO** una lectura de la sesión que el servidor retiene
- **CUANDO** se observa el panel
- **ENTONCES** muestra un estado de carga anunciado con cortesía y todavía no el mensaje de bienvenida
- **Y** la barra superior y el menú de usuario ya están en el documento

> **Nota fechada 2026-11-10 (13b-2).** Texto anterior: «todavía no el mensaje de confirmación»; la cláusula sobre el shell visible es nueva.

#### Escenario: [SS105] Error de red en la lectura

- **DADO** una lectura de la sesión que falla por red
- **CUANDO** el panel lo procesa
- **ENTONCES** muestra `ErrorState` con un reintento de etiqueta no vacía

#### Escenario: [SS106] La lectura recibe `401`

- **DADO** una lectura de la sesión con `401`
- **CUANDO** el panel lo procesa
- **ENTONCES** lleva al inicio de sesión con «Su sesión se cerró por seguridad. Vuelva a iniciar sesión.»

#### Escenario: [SS107] Cierre de sesión correcto

- **DADO** la página autenticada y un servidor que responde `204` y borra las cookies
- **CUANDO** la persona abre el menú de usuario y activa «Cerrar sesión»
- **ENTONCES** el servidor registra un `DELETE` con `X-CSRF-Token` y sin `Authorization`
- **Y** la aplicación llega al inicio de sesión con la memoria de tokens, la caché de consultas y los plazos vacíos

> **Nota fechada 2026-11-10 (13b-2).** Texto anterior: «CUANDO la persona activa «Cerrar sesión»» (botón de la página mínima); la cláusula de los plazos es nueva.

#### Escenario: [SS108] Recargar tras cerrar sesión no sondea

- **DADO** el cierre de sesión terminado, con las cookies borradas por el servidor
- **CUANDO** se recarga la aplicación
- **ENTONCES** el servidor registra cero peticiones de refresco y se muestra el inicio de sesión

#### Escenario: [SS109] Cierre de sesión que falla

- **DADO** un cierre de sesión cuyo transporte falla por red, y en otra ejecución una respuesta `403 csrf-invalid`
- **CUANDO** el shell lo procesa
- **ENTONCES** sigue en la página autenticada con un `Alert` de error y un control de reintento de etiqueta no vacía
- **Y** no se declaró la sesión cerrada ni se avisó a otras pestañas

> **Nota fechada 2026-11-10 (13b-2).** Texto anterior: «CUANDO la página lo procesa; ENTONCES sigue en la página autenticada con un `Alert` de error y un reintento». La
> cláusula sobre las otras pestañas es nueva.

#### Escenario: [SS110] El cierre de sesión marca la espera y no duplica la petición

- **DADO** un cierre de sesión que el servidor retiene
- **CUANDO** la persona lo activa desde el menú y lo intenta activar tres veces más por cualquier vía
- **ENTONCES** el servidor registra un solo `DELETE`
- **Y** el disparador del menú de usuario expone `aria-busy="true"` mientras la petición está en vuelo, y la acción de cierre no usa el atributo `disabled`

> **Nota fechada 2026-11-10 (13b-2).** Texto anterior: «CUANDO la persona activa el botón tres veces; ENTONCES el servidor registra un solo `DELETE` y el botón expone `aria-busy="true"`
> sin estar deshabilitado». El menú se cierra al activar la acción, de modo que la señal de espera pasa al disparador.

#### Escenario: [SS111] Cierre de sesión con la sesión ya vencida por inactividad

- **DADO** una sesión vencida por inactividad en el servidor y la página aún en pantalla (por ejemplo, sin que el planificador haya podido actuar)
- **CUANDO** la persona activa «Cerrar sesión» desde el menú
- **ENTONCES** el servidor responde `204` y la aplicación llega al inicio de sesión sin mensaje de error

#### Escenario: [SS112] Recargar con la sesión vencida por inactividad

- **DADO** una sesión vencida por inactividad y la cookie CSRF aún presente
- **CUANDO** se recarga la aplicación
- **ENTONCES** el refresco recibe `401 session-expired` y se muestra el inicio de sesión con el mensaje de sesión vencida

### Requisito: Ningún refresco se dispara por temporizador ni por consulta de fondo, y ninguno se reintenta a ciegas

El sistema NO DEBE refrescar la sesión por un temporizador, por una consulta periódica, por recuperar el foco de la ventana, por reconectar la red ni por
cambiar la visibilidad de la pestaña: la inactividad se mide al rotar el refresco y un refresco periódico mantendría viva una estación desatendida
(`docs/ui-ux/04` §7, nota fechada 2026-10-19). **Ningún temporizador ni consulta de fondo pide un refresco.** Los únicos disparadores de un refresco son:
la restauración al cargar y su reintento por la persona, una petición con `Bearer` iniciada por la persona cuando falten menos de 60 segundos para
`accessTokenExpiresAt`, la actividad de la persona con cadencia mínima, y «Continuar» en el aviso de inactividad; todos por el coordinador. La única petición
que un temporizador puede emitir es el `DELETE /api/v1/auth/sessions/current` de mejor esfuerzo al terminar la cuenta atrás o al llegar el vencimiento
absoluto. Un refresco que falla por red NO DEBE reintentarse, porque una reutilización revoca la familia. Una respuesta `401` a una petición con `Bearer` NO
DEBE producir un refresco reactivo ni un reintento: DEBE terminar la sesión local y mostrar el inicio de sesión con el mensaje de sesión vencida. Cualquier
resultado de un refresco distinto de `200` y de error de red (`session-expired`, `csrf-invalid` u otro) DEBE terminar la sesión local del mismo modo.
(Previously: «No se dispara ningún refresco por temporizador» sin excepción, con el refresco previo a una petición como único disparador y sin la actividad ni «Continuar».)

#### Escenario: [SS19] Con el tiempo avanzado y sin actividad no hay refrescos; solo el `DELETE` del cierre

- **DADO** la página autenticada abierta, sin ningún evento de actividad, y el reloj del navegador avanzado 60 minutos con el reloj controlado de la prueba
- **CUANDO** se cuentan las peticiones emitidas durante ese avance
- **ENTONCES** los refrescos son cero
- **Y** la única petición es un `DELETE /api/v1/auth/sessions/current`, emitido una sola vez al terminar la cuenta atrás, tras lo cual se muestra el inicio de sesión

> **Nota fechada 2026-11-10 (13b-2, decisión D5).** Se precisa SS19. Texto anterior: «CUANDO se cuentan las peticiones emitidas durante ese avance; ENTONCES son cero». Con el aviso
> de inactividad, el tiempo ya no es inerte: sigue sin haber refrescos, pero el cierre por vencimiento emite un `DELETE` de mejor esfuerzo.

#### Escenario: [SS20] Recuperar el foco o reconectar no emite peticiones

- **DADO** la página autenticada abierta
- **CUANDO** la ventana pierde y recupera el foco, y el navegador pasa a `offline` y de nuevo a `online`
- **ENTONCES** el servidor registra cero peticiones nuevas

#### Escenario: [SS21] Las consultas de la sesión no tienen intervalo ni refresco automático

- **DADO** las opciones de la consulta de la sesión actual
- **CUANDO** se leen
- **ENTONCES** `refetchInterval`, `refetchOnWindowFocus` y `refetchOnReconnect` están desactivados

#### Escenario: [SS22] Faltan menos de 60 segundos: se refresca antes de la petición

- **DADO** un token de acceso cuyo `accessTokenExpiresAt` está a 59 segundos del reloj de la prueba
- **CUANDO** la persona provoca una petición con `Bearer`
- **ENTONCES** el servidor registra primero un refresco y después la petición, esta con el token nuevo

#### Escenario: [SS23] Faltan 60 segundos o más: no se refresca

- **DADO** un token de acceso cuyo `accessTokenExpiresAt` está a 60 segundos del reloj de la prueba
- **CUANDO** la persona provoca una petición con `Bearer`
- **ENTONCES** el servidor registra solo la petición, con el token actual

#### Escenario: [SS24] Un refresco con error de red no se reintenta

- **DADO** un refresco previo a una petición que falla por red
- **CUANDO** pasan 60 segundos
- **ENTONCES** el servidor registra un solo intento y la petición de la persona no se emitió

#### Escenario: [SS25] Un `401` a una petición con `Bearer` termina la sesión local sin refrescar

- **DADO** la página autenticada y un servidor que responde `401 token-expired` a la lectura de la sesión
- **CUANDO** se ejecuta la lectura
- **ENTONCES** el servidor registra cero refrescos y cero reintentos de la lectura
- **Y** se muestra el inicio de sesión con el mensaje de sesión vencida

#### Escenario: [SS26] Un refresco que no es `200` termina la sesión local

- **DADO** un refresco cuya respuesta es `403 csrf-invalid`
- **CUANDO** la aplicación la procesa
- **ENTONCES** vacía la memoria de tokens y la caché de consultas, y muestra el inicio de sesión con el mensaje de sesión
  vencida
- **Y** no hay un segundo intento

### Requisito: Ausencia de lo que pertenece a los cambios futuros y al 8b (brechas con destino)

El sistema NO DEBE contener en este cambio: el parámetro `redirect` y el regreso a una ruta profunda (aplazados hasta el primer cambio con más de una ruta
protegida; decisión D9, supuesto), y «Mis sesiones activas» con su lista y su revocación (D10, supuesto; son del backend o del cambio 8b, cuando exista un
endpoint, y luego de un cambio de frontend, o del cambio que el propietario asigne con la decisión P4). NO DEBE contener la inscripción ni la baja
voluntarias del segundo factor con sesión completa, la creación del primer Super Admin ni el restablecimiento administrativo de contraseña: son del
**cambio 8b**. El cambio 13b-2 entrega lo que 13b-1 listaba como suyo: la detección de actividad como disparador de refresco, el aviso de inactividad con
`Dialog`, el cálculo con `idleExpiresAt` y la corrección del desfase de reloj, los avisos por vencimiento absoluto y la sincronización del cierre de
sesión entre pestañas. La brecha aceptada de 13b-1 (la página no detectaba el vencimiento por inactividad) queda cerrada.
(Previously: la lista incluía la detección de actividad, el aviso de inactividad, el cálculo con `idleExpiresAt`, los avisos por vencimiento absoluto y la sincronización entre pestañas como ausentes con destino 13b-2.)

#### Escenario: [SS124] El aviso aparece y la sesión termina al vencer

- **DADO** una sesión con plazo de inactividad a 30 minutos y la página autenticada abierta
- **CUANDO** pasa el tiempo sin que la persona haga nada
- **ENTONCES** a los 25 minutos aparece el aviso de inactividad y, al llegar a los 30 minutos, la sesión local termina y se muestra el inicio de sesión con
  el mensaje de sesión vencida
- **Y** ningún dato personal aparece en el aviso ni en el documento

> **Nota fechada 2026-11-10 (13b-2, escenario invertido).** Texto anterior: «CUANDO pasa el tiempo sin que la persona haga nada; ENTONCES la página sigue en pantalla, no muestra datos
> personales y no emite ninguna petición; Y este escenario deja de ser cierto el día que el cambio 13b-2 entregue el aviso de inactividad». Dejó de ser cierto con este cambio.

#### Escenario: [SS125] Existen el aviso de inactividad, el diálogo y el cálculo con `idleExpiresAt`

- **DADO** la aplicación con la página autenticada abierta y plazos guardados
- **CUANDO** el reloj controlado llega a 5 minutos antes del plazo de inactividad
- **ENTONCES** existe un diálogo modal de aviso, anunciado con prioridad `assertive`, cuyo momento de aparición se calculó con `idleExpiresAt`
- **Y** el diálogo se importó del punto de entrada público de `@confia/ui` (capacidad `design-system`)

> **Nota fechada 2026-11-10 (13b-2, escenario invertido).** Texto anterior: «CUANDO se busca un diálogo, una región `assertive` de aviso de sesión por expirar o un cálculo con `idleExpiresAt`; ENTONCES no existe
> ninguno; Y este escenario deja de ser cierto el día que el cambio 13b-2 lo entregue». Dejó de ser cierto con este cambio.

#### Escenario: [SS126] Hay sincronización entre pestañas y detección de actividad

- **DADO** dos pestañas con sesión y el cierre de sesión en una de ellas
- **CUANDO** se observa la otra
- **ENTONCES** muestra el inicio de sesión, y la pulsación de una tecla, del puntero o el desplazamiento en la página autenticada dispara un refresco con la
  cadencia mínima (escenarios SS142 y SS178)

> **Nota fechada 2026-11-10 (13b-2, escenario invertido).** Texto anterior: «ENTONCES sigue en la página autenticada, y ninguna escucha de actividad de la persona (teclado, ratón, toque) dispara un
> refresco; Y este escenario deja de ser cierto el día que el cambio 13b-2 entregue la sincronización y la detección». Dejó de ser cierto con este cambio.

#### Escenario: [SS127] No existen las funcionalidades del cambio 8b

- **DADO** la aplicación y su cliente de API
- **CUANDO** se buscan pantallas u operaciones de inscripción o baja voluntarias del segundo factor, de creación del
  primer Super Admin y de restablecimiento administrativo de contraseña
- **ENTONCES** no existe ninguna
- **Y** este escenario deja de ser cierto el día que el cambio 8b las entregue

#### Escenario: [SS192] No existen «Mis sesiones activas» ni el regreso a una ruta profunda

- **DADO** la aplicación, su cliente de API y su enrutador
- **CUANDO** se buscan una lista de sesiones activas, una revocación de otras sesiones y un parámetro `redirect` o un destino guardado para regresar a una ruta profunda
- **ENTONCES** no existe ninguno (el parámetro `redirect` se cubre además con el escenario AW19)
- **Y** este escenario deja de ser cierto el día que el backend exponga las sesiones y un cambio de frontend las consuma, o el primer cambio con más de una ruta
  protegida entregue el regreso a la ruta profunda
