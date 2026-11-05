# Capacidad: Inicio de sesión del personal

- **Identificador:** staff-sign-in
- **Estado:** actualizada el 2026-11-05 con los cambios de especificación que el propietario aprobó junto con el diseño
- **Fase:** F0 (cambio 13b-1, parte 1 de 2)
- **Cambio:** `admin-web-foundations-and-session`. Las pantallas de acceso de esta capacidad viven en `admin-web-staff-sign-in`.
- **Propuesta:** `proposal.md`, aprobada por el propietario el 2026-11-04, con las decisiones P1 a P3 y P6 a P10 y la
  resolución de Q2 vinculantes, y con Q1 resuelta el 2026-11-05

## Propósito

Capacidad nueva. En esta parte especifica la sesión básica de una persona del personal (tokens solo en memoria, restauración
al recargar, refresco coordinado, cierre y vencimiento), la pantalla de arranque, las guardas de ruta, la página mínima autenticada con su
cierre de sesión y la API simulada de los recorridos. **Esta parte no cierra el criterio de salida 8 de F0**: lo cierra `admin-web-staff-sign-in`, que agrega
las pantallas de inicio de sesión, verificación e inscripción.

**Inicio de sesión provisional.** Hasta que llegue `admin-web-staff-sign-in`, la ruta `/iniciar-sesion` muestra una pantalla provisional sin formulario: un encabezado de nivel 1 y
el aviso de llegada que corresponda. Cuando un escenario de esta parte dice «se muestra el inicio de sesión», se refiere a ella. No
acepta credenciales y por eso, al terminar esta parte, una persona todavía no puede iniciar sesión.

La **línea de la sesión** entre este cambio y el 13b-2 es: este cambio entrega lo necesario para obtener, conservar,
recuperar y terminar una sesión; el 13b-2 entrega lo que observa el paso del tiempo y lo anticipa ante la persona.

NO le corresponde: el contrato de los endpoints (capacidades `identity` y `web-edge`, sin cambios); la fundación de la
aplicación, el cliente de API y la traducción de `ProblemDetail` (capacidad `admin-web-foundation`); los componentes
`FormErrorSummary` y `Alert` (capacidad `design-system`); las reglas estáticas y la puerta sobre rutas (capacidad
`build-integrity`); la prueba de extremo a extremo contra la pila real (capacidad `identity`, brecha con destino: cambio 11).

Fuentes de verdad: `docs/ui-ux/03` §3, §4, §5 y §9; `docs/ui-ux/04` §7 y §9 y sus notas fechadas; `docs/ui-ux/05` §4;
`docs/ui-ux/06` §1 y su nota fechada; `docs/03-seguridad.md` §4; `identity` (requisitos de sesión, MFA y cookies);
`CLAUDE.md` reglas 9, 11 y 15.

## Convenciones de esta especificación

- Los escenarios se numeran `SS01` en adelante. El prefijo `SS` no existe en ninguna otra especificación ni delta. La numeración tiene
  huecos porque los demás escenarios viven en `admin-web-staff-sign-in`.
- **Texto fijado por la documentación:** sesión vencida, «Su sesión se cerró por seguridad. Vuelva a iniciar sesión.»
  (`docs/ui-ux/04`:727, sin su tercera frase, que no aplica porque este cambio no tiene pantallas con datos capturados). El resto
  de los textos los fija el catálogo `admin`, con los valores de `design.md`, sección 8, aprobados por el propietario.
- **Valores fijados por el diseño** (`design.md`, sección 4 y D4, D8, D15):
  - **Rutas:** `/iniciar-sesion`, `/iniciar-sesion/verificacion`, `/iniciar-sesion/inscripcion`, `/` y `notFoundComponent`. El arranque no es una ruta.
  - **Web Locks:** `confia.admin.session.refresh`, exclusivo.
  - **Simulación de la API y de `Set-Cookie`:** servidor Node detrás del proxy de Vite, en el puerto `8099`, solo `localhost`.
  - **Matriz de la puerta:** la de `build-integrity`, 6 estados por 2 temas en esta parte.
- La lista de ausencias del último requisito es parte de la especificación: un comportamiento listado como ausente no
  debe agregarse sin modificar ese requisito.

## Requisitos

### Requisito: Los tokens, los secretos y los códigos viven solo en memoria de módulo

El sistema DEBE mantener el token de acceso, el token restringido, la contraseña, el secreto TOTP, el identificador
escrito durante la inscripción y los códigos de recuperación únicamente en memoria de módulo, en un almacén privado. NO
DEBEN aparecer en `localStorage`, `sessionStorage`, IndexedDB, la URL, el estado del historial (`history.state`), la caché
de consultas ni la de mutaciones de TanStack Query (incluidos sus datos, variables y errores), ninguna propiedad
enumerable de `window`, la consola ni ningún informe de errores (`docs/ui-ux/04` §7; `CLAUDE.md`, regla 11). La aplicación
NO DEBE escribir ninguna cookie: la cookie de sesión es de servidor y de acceso exclusivo HTTP, y la cookie CSRF solo se
lee. Los metadatos de la sesión que no son secretos pasan por TanStack Query.

#### Escenario: [SS06] Los metadatos de la sesión no contienen tokens

- **DADO** la sesión leída con `GET /api/v1/auth/sessions/current`, en un flujo en jsdom que monta la aplicación completa, porque
  producción no expone el `QueryClient`
- **CUANDO** se inspecciona lo que guarda la caché de consultas
- **ENTONCES** contiene los metadatos de la respuesta y ningún token

### Requisito: Al recargar, la sesión se restaura con un único refresco y solo si existe la cookie CSRF legible

El sistema DEBE restaurar la sesión al cargar la aplicación llamando una vez a `POST /api/v1/auth/sessions/refresh`
**solo si existe la cookie `__Host-confia_csrf` con valor no vacío**. Sin esa cookie NO DEBE emitir ninguna petición de
refresco: cada sondeo anónimo sería un `401` y contaría como fallo de la IP en la política `admin-session` (30 rechazos en
10 minutos), peligroso detrás de la NAT de una oficina. La restauración DEBE intentarse en cualquier ruta, incluida la de
inicio de sesión. Un `200` DEBE dejar al token de acceso en memoria y llevar a la página autenticada. Un `401
session-expired` DEBE vaciar la memoria y la caché de consultas y mostrar el inicio de sesión con el mensaje de sesión
vencida. Un error de red NO DEBE reintentarse de forma automática: DEBE mostrar el estado de error de arranque con un
reintento que ejecuta la persona.

#### Escenario: [SS07] Sin cookie CSRF no hay sondeo de refresco

- **DADO** una carga de la aplicación sin la cookie `__Host-confia_csrf`
- **CUANDO** termina de cargar, en cada una de las rutas de la aplicación
- **ENTONCES** el servidor simulado registra cero peticiones a `/api/v1/auth/sessions/refresh`
- **Y** se muestra el inicio de sesión sin pasar por la pantalla de restauración

#### Escenario: [SS08] Cookie CSRF vacía equivale a ausente

- **DADO** una cookie `__Host-confia_csrf` con valor vacío
- **CUANDO** la aplicación carga
- **ENTONCES** no emite ninguna petición de refresco

#### Escenario: [SS09] Con cookie CSRF se restaura con un solo refresco

- **DADO** una cookie `__Host-confia_csrf` y una sesión viva en el servidor simulado
- **CUANDO** se recarga la aplicación
- **ENTONCES** el servidor registra exactamente una petición `POST /api/v1/auth/sessions/refresh` con `X-CSRF-Token` igual a
  la cookie, sin `Authorization` y sin cuerpo
- **Y** la aplicación llega a la página autenticada

#### Escenario: [SS10] La restauración se intenta también en la ruta de inicio de sesión

- **DADO** una cookie CSRF y una sesión viva
- **CUANDO** se abre directamente la ruta de inicio de sesión
- **ENTONCES** se restaura la sesión y la persona llega a la página autenticada, no al formulario

#### Escenario: [SS11] Un refresco rechazado lleva al inicio de sesión con el mensaje de sesión vencida

- **DADO** una cookie CSRF cuya sesión ya no existe y un servidor que responde `401 session-expired`
- **CUANDO** se recarga la aplicación
- **ENTONCES** se muestra el inicio de sesión con el texto «Su sesión se cerró por seguridad. Vuelva a iniciar sesión.»
- **Y** la memoria de tokens y la caché de consultas quedan vacías

#### Escenario: [SS12] Un error de red al restaurar no se reintenta solo

- **DADO** una cookie CSRF y un transporte que falla por red en el refresco
- **CUANDO** la aplicación carga y pasan 60 segundos
- **ENTONCES** el servidor registra una sola petición de refresco
- **Y** se muestra el estado de error de arranque con un control de reintento

#### Escenario: [SS13] El reintento lo ejecuta la persona y hace una sola petición

- **DADO** el estado de error de arranque
- **CUANDO** la persona activa el reintento una vez
- **ENTONCES** el servidor registra exactamente una petición de refresco más

### Requisito: A lo sumo una petición de refresco a la vez, por pestaña y entre pestañas

El sistema DEBE emitir como máximo una petición de refresco en vuelo por pestaña, compartiendo su promesa entre todos los
que la disparen, y como máximo una a la vez entre pestañas del mismo navegador, usando Web Locks (`navigator.locks`) con el bloqueo exclusivo `confia.admin.session.refresh`. Dos
pestañas que restauran a la vez DEBEN serializar sus peticiones, de modo que la segunda use la cookie que reemitió la
primera; una reutilización fuera de la gracia de 10 segundos revocaría la familia de sesión (`identity`). Si el navegador no
ofrece Web Locks, la aplicación DEBE degradar a la promesa por pestaña, sin temporizadores ni sondeos y sin mostrar un
error.

#### Escenario: [SS14] Disparos simultáneos en una pestaña comparten una petición

- **DADO** tres llamadas a refrescar en el mismo instante en una pestaña
- **CUANDO** el servidor simulado responde
- **ENTONCES** el servidor registró una sola petición
- **Y** las tres llamadas reciben el mismo resultado

#### Escenario: [SS15] Una petición nueva tras terminar la anterior

- **DADO** un refresco terminado
- **CUANDO** se dispara otro después
- **ENTONCES** se emite una petición nueva y no se reutiliza la promesa anterior

#### Escenario: [SS16] Un rechazo llega a todos los que esperaban

- **DADO** dos llamadas a refrescar compartiendo la petición y un `401 session-expired`
- **CUANDO** responde el servidor
- **ENTONCES** las dos reciben el rechazo y la promesa compartida queda libre para un intento posterior

#### Escenario: [SS17] Dos pestañas restaurando a la vez no se solapan

- **DADO** dos pestañas del mismo contexto de navegador con la cookie CSRF y una sesión viva, y un servidor simulado que
  modela la rotación del refresco con su gracia
- **CUANDO** las dos se recargan en el mismo instante
- **ENTONCES** el servidor registra una concurrencia máxima de una petición de refresco
- **Y** las dos pestañas llegan a la página autenticada, la segunda con la cookie reemitida por la primera

#### Escenario: [SS18] Sin Web Locks la aplicación degrada sin error

- **DADO** un navegador sin `navigator.locks`
- **CUANDO** una pestaña restaura la sesión
- **ENTONCES** llega a la página autenticada con una sola petición
- **Y** no aparece ningún error ni se crea ningún temporizador de sondeo

### Requisito: Ningún refresco se dispara por temporizador ni por consulta de fondo, y ninguno se reintenta a ciegas

El sistema NO DEBE refrescar la sesión por un temporizador, por una consulta periódica, por recuperar el foco de la ventana
ni por reconectar la red: la inactividad se mide al rotar el refresco y un refresco periódico mantendría viva una
estación desatendida (`docs/ui-ux/04` §7, nota fechada 2026-10-19). El sistema DEBE refrescar antes de una petición con
`Bearer` iniciada por la persona cuando falten menos de 60 segundos para `accessTokenExpiresAt`, y no antes. Un refresco
que falla por red NO DEBE reintentarse, porque una reutilización revoca la familia. Una respuesta `401` a una petición con
`Bearer` NO DEBE producir un refresco reactivo ni un reintento: DEBE terminar la sesión local y mostrar el inicio de
sesión con el mensaje de sesión vencida. Cualquier resultado de un refresco distinto de `200` y de error de red
(`session-expired`, `csrf-invalid` u otro) DEBE terminar la sesión local del mismo modo.

#### Escenario: [SS19] Con el tiempo avanzado no hay peticiones

- **DADO** la página autenticada abierta y el reloj del navegador avanzado 60 minutos con el reloj controlado de la prueba
- **CUANDO** se cuentan las peticiones emitidas durante ese avance
- **ENTONCES** son cero

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

### Requisito: Las guardas de ruta, el foco y el título cuidan el recorrido de varias pantallas

El sistema DEBE proteger la página autenticada: sin sesión DEBE llevar al inicio de sesión. Las pantallas de verificación e
inscripción DEBEN exigir un token restringido en memoria: sin él DEBEN llevar al inicio de sesión con un aviso propio
(distinto para la inscripción). Una persona con sesión que abre el inicio de sesión, la verificación o la inscripción DEBE
llegar a la página autenticada. El token restringido y el estado de la inscripción DEBEN descartarse al salir de las
pantallas del segundo factor por cualquier camino que no sea una verificación correcta, incluidos el regreso y el avance
del navegador. Cada pantalla DEBE actualizar `document.title` con un texto del catálogo, tener un único encabezado de nivel
1 dentro de `main`, mover el foco a ese encabezado al aparecer, y el documento DEBE declarar `lang` igual al idioma activo.

#### Escenario: [SS27] Sin sesión, la página autenticada lleva al inicio de sesión

- **DADO** una persona sin sesión y sin cookie CSRF
- **CUANDO** abre la ruta de la página autenticada
- **ENTONCES** llega al inicio de sesión

#### Escenario: [SS30] Con sesión, las pantallas de acceso llevan a la página autenticada

- **DADO** una sesión restaurada
- **CUANDO** se navega al inicio de sesión, a la verificación y a la inscripción
- **ENTONCES** en los tres casos se llega a la página autenticada

#### Escenario: [SS32] Cada pantalla tiene título, un encabezado de nivel 1 y el foco en él

- **DADO** cada pantalla de la aplicación
- **CUANDO** aparece tras una navegación
- **ENTONCES** `document.title` es un texto del catálogo distinto por pantalla
- **Y** hay exactamente un `h1`, dentro de `main`, y es el elemento con el foco

#### Escenario: [SS33] El idioma del documento

- **DADO** el idioma activo `es-HN`
- **CUANDO** se lee el atributo `lang` del elemento raíz
- **ENTONCES** es el del idioma activo

### Requisito: La pantalla de arranque muestra la restauración y su error de red

El sistema DEBE mostrar, mientras restaura la sesión, un estado de carga con un mensaje en una región de cortesía
`polite`, sin mostrar el formulario ni otras pantallas. Si la restauración falla por red, DEBE mostrar `ErrorState` con
un mensaje y un reintento cuya etiqueta nunca es vacía. El estado vacío no aplica a esta pantalla.

#### Escenario: [SS34] Estado de restauración

- **DADO** una cookie CSRF y un refresco que el servidor simulado retiene
- **CUANDO** se observa la pantalla
- **ENTONCES** muestra un indicador de carga y un mensaje de restauración en una región `polite`
- **Y** no hay campos de formulario en el documento

#### Escenario: [SS35] Error de red con reintento

- **DADO** un refresco que falla por red
- **CUANDO** se observa la pantalla
- **ENTONCES** muestra `ErrorState` con el mensaje, anunciado por su región activa, y un control de reintento con nombre
  accesible no vacío

#### Escenario: [SS36] La restauración termina y la pantalla desaparece

- **DADO** el refresco que responde `200`
- **CUANDO** termina
- **ENTONCES** la pantalla de arranque ya no está en el documento

### Requisito: La página mínima autenticada confirma la sesión sin datos personales y ofrece el cierre de sesión

El sistema DEBE mostrar tras iniciar sesión una página mínima con un encabezado de nivel 1, un mensaje de confirmación y el
botón «Cerrar sesión», sin nombre, correo, rol, identificadores ni vencimientos de la persona. Al entrar DEBE leer
`GET /api/v1/auth/sessions/current` con `Bearer`; mientras tanto DEBE mostrar un estado de carga; un `200` DEBE guardar sus
metadatos en la caché de consultas sin mostrarlos; un error de red o `5xx` DEBE mostrar `ErrorState` con reintento; un `401`
DEBE llevar al inicio de sesión con el mensaje de sesión vencida. El estado vacío no aplica a esta página. El cierre de
sesión DEBE emitir `DELETE /api/v1/auth/sessions/current` sin `Bearer`; ante `204` DEBE vaciar la memoria de tokens y la caché
de consultas y llevar al inicio de sesión; ante cualquier otro resultado DEBE mostrar un error con reintento y NO declarar
la sesión cerrada, porque la cookie seguiría viva.

#### Escenario: [SS103] La página no muestra datos de la persona

- **DADO** una lectura de la sesión con los centinelas «CENT-CUENTA», «CENT-INSTITUCION» y «CENT-SESION» en `accountId`,
  `institutionId` y `sessionId`
- **CUANDO** se muestra la página
- **ENTONCES** ninguno de los centinelas aparece en el documento
- **Y** hay un `h1`, un mensaje de confirmación y el botón «Cerrar sesión»

#### Escenario: [SS104] Estado de carga de la página

- **DADO** una lectura de la sesión que el servidor retiene
- **CUANDO** se observa la página
- **ENTONCES** muestra un estado de carga anunciado con cortesía y todavía no el mensaje de confirmación

#### Escenario: [SS105] Error de red en la lectura

- **DADO** una lectura de la sesión que falla por red
- **CUANDO** la página lo procesa
- **ENTONCES** muestra `ErrorState` con un reintento de etiqueta no vacía

#### Escenario: [SS106] La lectura recibe `401`

- **DADO** una lectura de la sesión con `401`
- **CUANDO** la página lo procesa
- **ENTONCES** lleva al inicio de sesión con «Su sesión se cerró por seguridad. Vuelva a iniciar sesión.»

#### Escenario: [SS107] Cierre de sesión correcto

- **DADO** la página autenticada y un servidor que responde `204` y borra las cookies
- **CUANDO** la persona activa «Cerrar sesión»
- **ENTONCES** el servidor registra un `DELETE` con `X-CSRF-Token` y sin `Authorization`
- **Y** la aplicación llega al inicio de sesión con la memoria de tokens y la caché de consultas vacías

#### Escenario: [SS108] Recargar tras cerrar sesión no sondea

- **DADO** el cierre de sesión terminado, con las cookies borradas por el servidor
- **CUANDO** se recarga la aplicación
- **ENTONCES** el servidor registra cero peticiones de refresco y se muestra el inicio de sesión

#### Escenario: [SS109] Cierre de sesión que falla

- **DADO** un cierre de sesión cuyo transporte falla por red, y en otra ejecución una respuesta `403 csrf-invalid`
- **CUANDO** la página lo procesa
- **ENTONCES** sigue en la página autenticada con un `Alert` de error y un reintento
- **Y** no se declaró la sesión cerrada

#### Escenario: [SS110] El botón de cierre marca la espera y no duplica la petición

- **DADO** un cierre de sesión que el servidor retiene
- **CUANDO** la persona activa el botón tres veces
- **ENTONCES** el servidor registra un solo `DELETE` y el botón expone `aria-busy="true"` sin estar deshabilitado

#### Escenario: [SS111] Cierre de sesión con la sesión ya vencida por inactividad

- **DADO** una sesión vencida por inactividad en el servidor y la página aún en pantalla
- **CUANDO** la persona activa «Cerrar sesión»
- **ENTONCES** el servidor responde `204` y la aplicación llega al inicio de sesión sin mensaje de error

#### Escenario: [SS112] Recargar con la sesión vencida por inactividad

- **DADO** una sesión vencida por inactividad y la cookie CSRF aún presente
- **CUANDO** se recarga la aplicación
- **ENTONCES** el refresco recibe `401 session-expired` y se muestra el inicio de sesión con el mensaje de sesión vencida

### Requisito: Los recorridos de navegador usan una API simulada que reproduce el contrato y se validan contra los esquemas

El sistema DEBE ofrecer recorridos de Playwright sobre `vite preview` contra una API simulada, un servidor Node detrás del proxy de Vite
(`e2e/mock-api`, puerto `8099`) que eligió la sonda del paso 0, sin un service worker en el resultado de producción. La API simulada DEBE reproducir, para las seis
operaciones, los códigos Problem, los estados y las cabeceras de `identity` y `web-edge`, y DEBE poder provocar por
configuración de la prueba: `401 authentication-failed`, `400 validation-failed` con `errors`, `429 too-many-requests` con
`Retry-After`, `503 capacity-exceeded`, una respuesta de 31 s, un token restringido vencido, `409 mfa-already-enrolled` y la
rotación del refresco con su gracia de 10 segundos. Cada respuesta simulada con cuerpo DEBE validarse con los esquemas de
`@confia/contracts/admin` antes de enviarse; una respuesta de error se valida sin su campo `instance`, que se emite como ruta relativa,
igual que el backend, hasta que el cambio 8b corrija el esquema. El cálculo del TOTP en las pruebas DEBE usar `node:crypto` según RFC 6238, sin
dependencia nueva. Antes de cerrar el diseño, la sonda DEBE registrar en `design.md` qué atributos de las cookies se
ejercitan de verdad y cuáles no; los recorridos simulados NO DEBEN presentarse como prueba de la pila real.

#### Escenario: [SS118] Una respuesta simulada fuera del contrato falla la prueba

- **DADO** una respuesta simulada de inscripción cuyo secreto no cumple el patrón base32 de 32 caracteres
- **CUANDO** el servidor simulado intenta enviarla
- **ENTONCES** la validación con el esquema falla y la prueba termina en rojo
- **Y** una respuesta de error simulada se valida con `ProblemDetail` sin el campo `instance`, que se emite como ruta relativa

#### Escenario: [SS119] El cálculo del TOTP de las pruebas coincide con RFC 6238

- **DADO** la clave ASCII «12345678901234567890» y el instante de 59 segundos
- **CUANDO** el ayudante de pruebas calcula el código de 6 dígitos con SHA-1
- **ENTONCES** es «287082»
- **Y** el ayudante no importa ninguna dependencia fuera de `node:crypto`

#### Escenario: [SS120] La rotación del refresco con gracia se modela

- **DADO** el servidor simulado con un refresco rotado hace 5 segundos y otro hace 30 segundos
- **CUANDO** se presenta cada refresco anterior
- **ENTONCES** el primero se acepta por la gracia y el segundo recibe `401 session-expired`

#### Escenario: [SS121] Un token restringido de inscripción rechaza un código de recuperación

- **DADO** una verificación simulada con un token de uso `mfa-enroll` y un `recoveryCode`
- **CUANDO** se envía
- **ENTONCES** responde `401 second-factor-invalid`, igual que el backend (`identity`)

#### Escenario: [SS122] La sonda registra su resultado antes del diseño

- **DADO** `openspec/changes/admin-web-foundations-and-session/design.md`
- **CUANDO** se lee
- **ENTONCES** contiene el resultado de la sonda del paso 0: el mecanismo de simulación elegido, los atributos de cookie
  ejercitados con él y los no ejercitados

### Requisito: Ausencia de lo que pertenece a los cambios 13b-2 y 8b (brechas con destino)

El sistema NO DEBE contener en este cambio: la detección de actividad de la persona como disparador de refresco, el aviso
de inactividad a los 25 minutos con `Dialog`, el cálculo con `idleExpiresAt` y la corrección del desfase de reloj, los
avisos por vencimiento absoluto, la sincronización del cierre de sesión entre pestañas, el parámetro `redirect` y el regreso
a una ruta profunda, y «Mis sesiones activas»: son del **cambio 13b-2** (o del cambio que el propietario asigne con la
decisión P4). NO DEBE contener la inscripción ni la baja voluntarias del segundo factor con sesión completa, la creación del
primer Super Admin ni el restablecimiento administrativo de contraseña: son del **cambio 8b**. La brecha aceptada hasta
13b-2 es que la página mínima no detecta por sí sola el vencimiento por inactividad: sigue en pantalla, sin datos
personales, y una recarga lleva al inicio de sesión con el mensaje de sesión vencida.

#### Escenario: [SS124] La página mínima no detecta el vencimiento por sí sola

- **DADO** una sesión vencida por inactividad en el servidor y la página autenticada abierta
- **CUANDO** pasa el tiempo sin que la persona haga nada
- **ENTONCES** la página sigue en pantalla, no muestra datos personales y no emite ninguna petición
- **Y** este escenario deja de ser cierto el día que el cambio 13b-2 entregue el aviso de inactividad

#### Escenario: [SS125] No existe aviso de inactividad ni `Dialog`

- **DADO** la aplicación con la página autenticada abierta
- **CUANDO** se busca un diálogo, una región `assertive` de aviso de sesión por expirar o un cálculo con `idleExpiresAt`
- **ENTONCES** no existe ninguno
- **Y** este escenario deja de ser cierto el día que el cambio 13b-2 lo entregue

#### Escenario: [SS126] No hay sincronización entre pestañas ni detección de actividad

- **DADO** dos pestañas con sesión y el cierre de sesión en una de ellas
- **CUANDO** se observa la otra
- **ENTONCES** sigue en la página autenticada, y ninguna escucha de actividad de la persona (teclado, ratón, toque) dispara un
  refresco
- **Y** este escenario deja de ser cierto el día que el cambio 13b-2 entregue la sincronización y la detección
