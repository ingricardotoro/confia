# Capacidad: Notificaciones

- **Identificador:** notifications
- **Estado:** pendiente de aprobación del propietario del producto
- **Fase:** F0
- **Cambio:** `transactional-email-adapter` (F0, cambio 14a)
- **Propuesta:** `proposal.md`, aprobada por el propietario el 2026-11-09, con P1 a P8 vinculantes

**Convenciones de esta especificación.**

- Es una especificación nueva completa. Sus escenarios se numeran `NT01` en adelante.
- Describe comportamiento observable; los nombres de clases, paquetes, tablas y propiedades los fija el diseño y
  aparecen aquí solo cuando son parte del contrato operativo (variables de entorno, nombre de la tabla, nombres
  de las métricas de `docs/07`).
- «Trabajador» es el proceso `confia-worker`. «Servidor de prueba» es Mailpit en Testcontainers, fijado por
  digest. «Perfil local» es el perfil de Spring `local`; todo lo demás (preproducción y producción, y cualquier
  arranque sin perfil) es «fuera del perfil local».
- «Envío aceptado» significa que el servidor SMTP respondió con éxito al final de la transacción SMTP
  (`250` tras los datos del mensaje). «Envío fallido» es cualquier otro resultado.
- «Clase de fallo» es un valor de una lista cerrada: `unavailable` (no hubo conexión), `timeout`, `tls`
  (STARTTLS ausente o certificado inválido), `authentication`, `rejected` (el servidor rechazó el remitente, el
  destinatario o los datos) y `unknown`.
- El correo del destinatario es dato personal (`docs/08`). Solo existe en memoria durante el envío.

## Propósito

El sistema necesita enviar correo transaccional por SMTP (Amazon SES en producción, ADR-0014) desde el
trabajador, y `docs/08` §8.4 exige que ese envío pase por un único motor de notificaciones. Esta capacidad
define el primer corte de ese motor: un caso de uso público que envía un correo de propósito transaccional con
una plantilla de texto plano del catálogo `es-HN`, por un transporte cifrado y con tiempos acotados, que deja una
fila en la bitácora de envío sin datos personales en claro, traduce los fallos del proveedor a una clase cerrada
sin el mensaje original, y se observa por métricas y registros que nunca contienen la dirección.

Su primer consumidor es el enlace de recuperación de contraseña (capacidad `identity`). Los avisos al titular
llegan con `account-security-notices` (cambio 14b). Los rebotes asíncronos, la lista de supresión, la baja, el
consentimiento, el tope de frecuencia, el correo HTML y las plantillas versionadas en base de datos son de F6.

## Requisitos

### Requisito: El envío de correo vive en el módulo `notifications`, cargado solo en el trabajador

El sistema DEBE ofrecer el envío de correo transaccional únicamente mediante el caso de uso público del módulo
`notifications`, y DEBE cargar ese módulo solo en el trabajador. Los procesos administrativo y del portal NO
DEBEN contener ningún componente del módulo ni ningún cliente de correo. El caso de uso DEBE aceptar solo el
propósito transaccional y solo plantillas de una lista cerrada; NO DEBE ofrecer un envío con asunto o cuerpo
libres.

#### Escenario: [NT01] El trabajador contiene el caso de uso y los procesos web no

- **DADO** los tres procesos arrancados con su configuración de producción
- **CUANDO** se inspeccionan sus contextos
- **ENTONCES** el trabajador contiene el caso de uso de envío y su transporte SMTP
- **Y** ni el proceso administrativo ni el del portal contienen ningún componente de `notifications` ni ningún
  emisor de correo

#### Escenario: [NT02] No existe un envío con texto libre

- **DADO** la API pública del módulo
- **CUANDO** se inspeccionan sus operaciones
- **ENTONCES** la única operación de envío recibe una plantilla de la lista cerrada, un destinatario y los
  parámetros de esa plantilla
- **Y** ninguna operación recibe un asunto ni un cuerpo ya escritos

### Requisito: El correo se compone en texto plano desde el catálogo `es-HN`, con clave y versión de plantilla

El sistema DEBE componer el asunto y el cuerpo de cada correo desde un catálogo de mensajes del backend en el
español de Honduras, en texto plano UTF-8, sustituyendo solo los parámetros declarados por la plantilla. Cada
plantilla DEBE tener una clave estable y un número de versión. El remitente DEBE ser el único de `MAIL_FROM` y el
correo DEBE tener exactamente un destinatario. Toda plantilla de la lista cerrada DEBE tener su asunto y su
cuerpo en el catálogo, y el catálogo NO DEBE tener entradas de plantillas que no existan; si falta una, la
construcción DEBE fallar nombrando la clave.

#### Escenario: [NT03] El servidor de prueba recibe un único correo de texto plano compuesto desde el catálogo

- **DADO** el trabajador con `MAIL_FROM=no-responder@confia.example` y el servidor de prueba
- **CUANDO** se envía la plantilla del enlace de recuperación a `ana.martinez@colegio.edu.hn` con el enlace
  `https://admin.confia.example/restablecer#token=SENTINEL-TOKEN` y una vigencia de 30 minutos
- **ENTONCES** el servidor de prueba recibe exactamente un correo, de `no-responder@confia.example`, con un solo
  destinatario, `Content-Type: text/plain; charset=UTF-8`, el asunto del catálogo y un cuerpo que contiene el
  enlace completo y la vigencia
- **Y** el correo no tiene partes HTML ni adjuntos

#### Escenario: [NT04] Una plantilla sin entrada en el catálogo rompe la construcción

- **DADO** la prueba de cobertura del catálogo
- **CUANDO** se ejecuta con el catálogo real y con un fixture al que le falta el cuerpo de una plantilla
- **ENTONCES** pasa con el real y falla con el fixture nombrando la clave ausente

#### Escenario: [NT05] Un parámetro no puede inyectar cabeceras

- **DADO** un parámetro de plantilla que contiene `\r\nBcc: intruso@example.com`
- **CUANDO** se compone y se envía el correo
- **ENTONCES** el envío se rechaza antes de abrir la conexión, como parámetro inválido
- **Y** el servidor de prueba no recibe ningún correo

### Requisito: El transporte fuera del perfil local exige STARTTLS, verifica la identidad del servidor y acota los tiempos

Fuera del perfil local, el sistema DEBE exigir STARTTLS en cada conexión SMTP, DEBE verificar el certificado y
que su nombre coincida con `SMTP_HOST`, y NO DEBE enviar nada por una conexión sin cifrar. Toda conexión DEBE
tener tiempos de espera acotados de conexión (5 segundos), de lectura (10 segundos) y de escritura (10
segundos). El trabajador NO DEBE arrancar si fuera del perfil local falta `SMTP_HOST`, `SMTP_PORT`, `MAIL_FROM` o
la llave del hash del destinatario, si `MAIL_FROM` no es una dirección válida, o si alguna configuración
desactiva STARTTLS o la verificación del certificado; el mensaje de arranque DEBE nombrar la propiedad, nunca su
valor. En el perfil local el sistema PUEDE conectar sin cifrar al servidor de prueba.

#### Escenario: [NT06] Un servidor sin STARTTLS no recibe el correo fuera del perfil local

- **DADO** el trabajador fuera del perfil local y un servidor de prueba que no ofrece STARTTLS
- **CUANDO** se envía un correo
- **ENTONCES** el envío falla con la clase `tls` y el servidor de prueba no recibe ningún correo

#### Escenario: [NT07] Un certificado con otro nombre de servidor se rechaza

- **DADO** un servidor de prueba con STARTTLS cuyo certificado es de confianza pero emitido para
  `otro.example`, alcanzado como `smtp.confia.test`
- **CUANDO** se envía un correo fuera del perfil local
- **ENTONCES** el envío falla con la clase `tls` y no se transmite ningún dato del mensaje

#### Escenario: [NT08] Con STARTTLS y un certificado válido el correo se entrega cifrado

- **DADO** un servidor de prueba que exige STARTTLS con un certificado válido para su nombre, generado al
  ejecutar la prueba
- **CUANDO** se envía un correo fuera del perfil local
- **ENTONCES** el envío es aceptado y el servidor de prueba recibe el correo

#### Escenario: [NT09] Un servidor que no responde no retiene el envío más allá de los tiempos acotados

- **DADO** un servidor que acepta la conexión TCP y nunca responde
- **CUANDO** se envía un correo
- **ENTONCES** el envío falla con la clase `timeout` en menos de 15 segundos

#### Escenario: [NT10] El trabajador no arranca con una configuración que contradice el transporte seguro

- **DADO** el trabajador fuera del perfil local
- **CUANDO** arranca, por separado, sin `SMTP_HOST`, sin `MAIL_FROM`, con `MAIL_FROM=no-es-un-correo`, sin la
  llave del hash y con STARTTLS desactivado
- **ENTONCES** en los cinco casos el arranque falla con un mensaje que nombra la propiedad y no contiene su
  valor

### Requisito: Cada intento de envío deja una fila en la bitácora de envío, sin la dirección, el asunto ni el cuerpo

El sistema DEBE registrar cada intento de envío, aceptado o fallido, como una fila nueva de
`notifications_email_delivery` de la institución del contexto de seguridad, con el canal `email`, el propósito
`transactional`, la clave y la versión de la plantilla, el hash del destinatario, el estado (`sent` o `failed`),
el identificador de solicitud del contexto, el identificador del mensaje del proveedor cuando el envío fue
aceptado y el servidor lo informa, y la clase de fallo cuando falló. El hash del destinatario DEBE ser un
HMAC-SHA-256 en hexadecimal de la dirección normalizada (sin espacios en los extremos y en minúsculas) con una
llave propia del módulo. La fila NO DEBE contener la dirección, el asunto compuesto, el cuerpo, el enlace ni
ningún parámetro de la plantilla. Una fila nunca se actualiza ni se borra.

#### Escenario: [NT11] Un envío aceptado deja una fila `sent` con el hash y la plantilla

- **DADO** la institución `INS-1` y la plantilla del enlace de recuperación en su versión 1
- **CUANDO** se envía a `ana.martinez@colegio.edu.hn` y el servidor de prueba lo acepta
- **ENTONCES** existe exactamente una fila de `INS-1` con estado `sent`, la clave y la versión de la plantilla y
  como hash el HMAC-SHA-256 de `ana.martinez@colegio.edu.hn` con la llave configurada
- **Y** la clase de fallo es nula

#### Escenario: [NT12] Un envío fallido deja una fila `failed` con su clase

- **DADO** el servidor de prueba detenido
- **CUANDO** se envía el mismo correo
- **ENTONCES** existe una fila con estado `failed` y la clase `unavailable`, sin identificador del proveedor

#### Escenario: [NT13] La bitácora no contiene datos personales ni el contenido

- **DADO** un envío aceptado y otro fallido a `Ana.Martinez@Colegio.edu.hn ` con el enlace
  `https://admin.confia.example/restablecer#token=SENTINEL-TOKEN`
- **CUANDO** se lee cada columna de texto de sus filas
- **ENTONCES** ninguna contiene la dirección (en ninguna capitalización), `SENTINEL-TOKEN`, el enlace, el asunto
  ni el cuerpo
- **Y** el hash es el mismo que el de `ana.martinez@colegio.edu.hn`

#### Escenario: [NT14] El hash cambia con la llave y no se deriva sin ella

- **DADO** la misma dirección enviada con dos llaves de hash distintas
- **CUANDO** se comparan los hashes registrados
- **ENTONCES** son distintos, y ninguno es igual al SHA-256 sin llave de la dirección

### Requisito: Un fallo del proveedor se traduce a una clase cerrada, sin el mensaje original

El sistema DEBE traducir todo fallo del transporte a un fallo propio del módulo que lleva solo la clase de fallo.
Ese fallo NO DEBE contener el mensaje, la causa ni la respuesta textual del servidor ni de la biblioteca de correo,
porque pueden contener direcciones. El caso de uso DEBE propagar ese fallo al llamador después de registrar la
fila `failed`, para que el llamador decida si reintenta.

#### Escenario: [NT15] Un rechazo `550` del destinatario se traduce a `rejected`

- **DADO** un servidor de prueba configurado para rechazar el destinatario con `550 5.1.1 <ana.martinez@colegio.edu.hn>: user unknown`
- **CUANDO** se envía el correo
- **ENTONCES** el llamador recibe el fallo del módulo con la clase `rejected`
- **Y** ni su mensaje, ni su causa, ni su traza contienen la dirección ni el texto del servidor

#### Escenario: [NT16] Una credencial SMTP inválida se traduce a `authentication`

- **DADO** un servidor de prueba que exige autenticación y una contraseña incorrecta
- **CUANDO** se envía el correo
- **ENTONCES** el llamador recibe el fallo con la clase `authentication`, y ni el fallo ni ningún registro
  contienen el usuario ni la contraseña SMTP

### Requisito: Un envío aceptado cuya fila de bitácora no se puede escribir se informa como entregado

Si el servidor aceptó el envío y la escritura de la fila `sent` falla, el sistema DEBE informar al llamador que
el envío fue aceptado, NO DEBE reintentar el envío ni informar un fallo de entrega, y DEBE dejar un evento
operativo de nombre cerrado, sin la dirección, que permita reconciliar la bitácora.

#### Escenario: [NT17] La base caída tras la aceptación no convierte el envío en fallido

- **DADO** un envío que el servidor de prueba acepta y una bitácora que falla al insertar
- **CUANDO** termina el caso de uso
- **ENTONCES** el llamador recibe el resultado aceptado, el servidor de prueba tiene exactamente un correo y se
  registró un evento operativo `notifications.email.delivery_log_failed` con la institución y la plantilla

#### Escenario: [NT18] Un envío fallido nunca se informa como aceptado

- **DADO** un envío que el servidor rechaza y una bitácora que también falla al insertar
- **CUANDO** termina el caso de uso
- **ENTONCES** el llamador recibe el fallo de entrega con la clase `rejected`

### Requisito: El envío se observa por métricas y registros que no contienen la dirección

El sistema DEBE contar los envíos aceptados en `confia_notifications_sent_total` con `channel="email"` y
`status="sent"`, y los fallidos en `confia_notifications_failed_total` con `channel="email"` y la etiqueta
`error_class`, ambas con `institution_id` (`docs/07`). Mientras el backend no tenga registro de métricas, ambas
DEBEN salir por el adaptador provisional de registros, como las demás métricas del sistema. Cada intento DEBE
dejar exactamente una línea de registro con la institución, la clave de la plantilla, el resultado, la clase de
fallo si la hay y el hash del destinatario. Ningún registro, excepción ni línea de métrica DEBE contener la
dirección, el asunto, el cuerpo, el enlace, ningún parámetro de plantilla, el usuario ni la contraseña SMTP.

#### Escenario: [NT19] Un envío aceptado y uno fallido dejan su métrica y su línea

- **DADO** un envío aceptado y otro fallido por `timeout` de la institución `INS-1`
- **CUANDO** se leen los registros del trabajador
- **ENTONCES** hay una línea de `confia_notifications_sent_total` con `channel=email` y `status=sent`, y una de
  `confia_notifications_failed_total` con `channel=email` y `error_class=timeout`, ambas con `INS-1`
- **Y** cada intento dejó una sola línea de resultado con el hash del destinatario

#### Escenario: [NT20] Los registros no contienen ningún centinela

- **DADO** los envíos del escenario anterior con la dirección `centinela.correo@colegio.edu.hn`, el enlace con
  `SENTINEL-TOKEN` y la contraseña SMTP `SENTINEL-SMTP-PASSWORD`
- **CUANDO** se capturan todos los registros del trabajador durante los envíos
- **ENTONCES** ninguna línea contiene ninguno de los tres centinelas

### Requisito: La configuración del correo llega por variables de entorno y sus secretos se redactan

El sistema DEBE leer la configuración del transporte solo de las variables de entorno `SMTP_HOST`, `SMTP_PORT`,
`SMTP_USER`, `SMTP_PASSWORD` y `MAIL_FROM`, y la llave del hash de `NOTIFICATIONS_RECIPIENT_HASH_KEY` (al menos
32 bytes en Base64). Ningún archivo del repositorio DEBE contener un valor de `SMTP_PASSWORD` ni de la llave. La
representación textual de los objetos que los guardan DEBE redactarlos.

#### Escenario: [NT21] La representación textual de la configuración no revela los secretos

- **DADO** la configuración con `SMTP_PASSWORD=SENTINEL-SMTP-PASSWORD` y una llave de hash generada al ejecutar la
  prueba
- **CUANDO** se obtiene la representación textual de la configuración
- **ENTONCES** no contiene la contraseña ni la llave, en claro ni en Base64

#### Escenario: [NT22] Ningún archivo del repositorio fija la contraseña SMTP ni la llave del hash

- **DADO** el árbol del repositorio
- **CUANDO** se buscan asignaciones de `SMTP_PASSWORD`, `spring.mail.password` y
  `NOTIFICATIONS_RECIPIENT_HASH_KEY` con un valor no vacío fuera de las pruebas que lo generan en ejecución
- **ENTONCES** no existe ninguna

### Requisito: Ausencia de rebotes asíncronos, supresión, baja, consentimiento, tope de frecuencia, HTML, plantillas en base de datos y purga de la bitácora (brecha con destino: F6)

El sistema NO DEBE, en este cambio, recibir avisos de rebote o queja de SES, mantener una lista de supresión,
ofrecer baja, evaluar consentimiento, aplicar un tope de frecuencia por destinatario, componer correo HTML,
leer plantillas desde la base de datos ni purgar `notifications_email_delivery`. Los únicos fallos que observa
son los síncronos de la transacción SMTP. El destino es F6 (`docs/09`, entregables 5 y 7); la purga a los 24
meses de `docs/08` también, porque ninguna fila alcanza esa edad antes. El servicio de correo de prueba de
`docker-compose` es del cambio 11.

#### Escenario: Un rebote posterior a la aceptación no se registra

- **DADO** un envío aceptado por el servidor
- **CUANDO** el proveedor informaría después un rebote
- **ENTONCES** no existe ninguna ruta, tarea ni tabla que lo reciba, y la fila del envío sigue `sent`
- **Y** este escenario deja de ser cierto cuando F6 entregue los rebotes asíncronos

#### Escenario: No existe ninguna tarea que purgue la bitácora de envío

- **DADO** el catálogo de tareas del trabajador
- **CUANDO** se buscan tareas sobre `notifications_email_delivery`
- **ENTONCES** no existe ninguna
