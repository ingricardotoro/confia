# Delta para Identidad

- **Estado:** pendiente de aprobación del propietario del producto
- **Cambio:** `transactional-email-adapter` (F0, cambio 14a)
- **Propuesta:** `proposal.md`, aprobada el 2026-11-09, con P1 a P8 vinculantes (P1: división en 14a y 14b; P3:
  compensación ante fallo del envío; P7: enlace con el token en el fragmento)

**Convenciones de este delta.**

- Los escenarios nuevos se numeran `I320` en adelante. La especificación vigente termina en `I277`, pero
  `rbac-permission-matrix-and-audit-integration` y `admin-web-staff-sign-in` ya ocupan números hasta `I311` en sus
  deltas sin archivar; este cambio deja libre el tramo `I312` a `I319` para `password-recovery-endpoints`, que se
  planifica en paralelo, y usa `I320` a `I339`.
- Un escenario marcado «(solo documental)» no tiene aserción automática.
- El comportamiento del envío (plantilla, transporte, bitácora de envío, clases de fallo, métricas) está en la
  capacidad nueva `notifications`. Este delta describe solo lo que observa `identity`.
- «Token anulado por entrega fallida» es un token cuya entrega falló y que la compensación cerró. No es un token
  abierto, no se puede usar y no cuenta para el límite de tres emisiones por hora.
- **Frontera con `password-recovery-endpoints`.** Ese cambio es dueño de las rutas `reset/request` y
  `POST /api/v1/auth/password-resets`, de la respuesta uniforme y de la propagación del origen. Este delta es dueño
  del puerto de envío, su adaptador, la forma del enlace y la compensación. Ninguno de los dos edita los
  requisitos que el otro modifica; el escenario «Un restablecimiento completado no notifica a nadie» sigue
  siendo cierto al cerrar ambos cambios, y solo `account-security-notices` (14b) lo invierte.

## ADDED Requirements

### Requisito: El enlace de recuperación se entrega por correo desde el trabajador, después de confirmar la emisión

El sistema DEBE entregar, por el caso de uso público de `notifications` y con la plantilla del enlace de
recuperación, un correo a la dirección de la cuenta por cada token emitido, **después** de confirmar la
transacción de la emisión y fuera de ella. La dirección DEBE leerse de la cuenta bloqueada dentro de la
transacción de la emisión, nunca de la tarea. El enlace DEBE ser exactamente `PASSWORD_RESET_LINK_BASE_URL`
seguido de `#token=` y del token en claro: el token viaja solo en el fragmento, nunca en la ruta ni en la
consulta. El correo DEBE indicar la vigencia del token en minutos tomada de la misma política que fija su
vencimiento. La página que recibe el enlace envía el token en el cuerpo de `POST /api/v1/auth/password-resets`
(`password-recovery-endpoints`); este cambio no la construye.

`PASSWORD_RESET_LINK_BASE_URL` DEBE ser una URL absoluta, sin consulta ni fragmento. Fuera del perfil local DEBE
usar el esquema `https`; en el perfil local PUEDE usar `http`. El trabajador NO DEBE arrancar si falta o si
incumple alguna de esas reglas, con un mensaje que nombra la propiedad y no su valor.

#### Escenario: [I320] La tarea de emisión entrega un correo cuyo enlace restablece la contraseña

- **DADO** la cuenta `ana.martinez@colegio.edu.hn` (`ACC-7`) de la institución `INS-1`, el trabajador con
  `PASSWORD_RESET_LINK_BASE_URL=https://admin.confia.example/restablecer` y el servidor de prueba
- **CUANDO** el trabajador ejecuta la tarea de emisión de `ACC-7`
- **ENTONCES** el servidor de prueba recibe exactamente un correo para `ana.martinez@colegio.edu.hn`
- **Y** su cuerpo contiene un único enlace con la forma `https://admin.confia.example/restablecer#token=<token>`
- **Y** el token extraído de ese enlace restablece la contraseña de `ACC-7` con el caso de uso de
  restablecimiento

#### Escenario: [I321] El envío ocurre después de confirmar, nunca con el bloqueo tomado

- **DADO** la tarea de emisión de `ACC-7` y un transporte de prueba que, al recibir el correo, intenta bloquear la
  fila de `ACC-7` desde otra conexión con un tiempo de espera de bloqueo de un segundo
- **CUANDO** el trabajador ejecuta la tarea
- **ENTONCES** el bloqueo desde la otra conexión se obtiene sin esperar, y el token ya está confirmado y visible
  desde esa conexión

#### Escenario: [I322] El token no aparece en la ruta ni en la consulta del enlace

- **DADO** el correo del escenario [I320]
- **CUANDO** se analiza el enlace como URI
- **ENTONCES** la ruta es `/restablecer`, la consulta está vacía y el token aparece solo en el fragmento

#### Escenario: [I323] El trabajador no arranca con una URL base insegura o mal formada

- **DADO** el trabajador fuera del perfil local
- **CUANDO** arranca, por separado, sin `PASSWORD_RESET_LINK_BASE_URL`, con
  `http://admin.confia.example/restablecer`, con `https://admin.confia.example/restablecer?x=1`, con
  `https://admin.confia.example/restablecer#a` y con `restablecer`
- **ENTONCES** en los cinco casos el arranque falla con un mensaje que nombra la propiedad y no contiene su valor
- **Y** en el perfil local `http://localhost:5173/restablecer` se acepta

### Requisito: Un fallo del envío del enlace anula el token no entregado, se audita y hace reintentar la tarea

Si el envío del enlace falla, el sistema DEBE, en una transacción nueva que bloquea primero la fila de la cuenta,
anular el token recién emitido como «entrega fallida» (solo si sigue abierto), escribir en la misma transacción
el asiento `identity.password_reset.delivery_failed` con el actor `system`, la entidad
`identity.password_reset_token` y el identificador del token, el resultado `error` y, como detalle, solo la clase
de fallo; y después DEBE relanzar el fallo para que la tarea de emisión reintente con su política (5 intentos,
espera de 30 segundos a 5 minutos). Cada reintento emite un token nuevo. Agotados los intentos, la tarea DEBE
quedar estacionada y visible, y la cuenta NO DEBE tener ningún token abierto emitido por esa tarea. Un envío
aceptado NO DEBE anular el token.

Los tokens que la emisión supera al emitir siguen superados aunque el envío del nuevo falle: la solicitud de un
enlace nuevo invalida los anteriores en todos los casos.

#### Escenario: [I324] El servidor caído anula el token, lo audita y la tarea reintenta

- **DADO** la cuenta `ACC-7` sin tokens y el servidor de prueba detenido
- **CUANDO** el trabajador ejecuta el primer intento de la tarea de emisión
- **ENTONCES** existe un token de `ACC-7` anulado por entrega fallida, ningún token abierto y un asiento
  `identity.password_reset.delivery_failed` con resultado `error` y la clase `unavailable`
- **Y** la tarea queda programada para su segundo intento 30 segundos después, con `consecutive_failures` igual a 1

#### Escenario: [I325] Un fallo transitorio se recupera con un único token utilizable

- **DADO** el servidor de prueba detenido en el primer intento y en marcha desde el segundo
- **CUANDO** el trabajador ejecuta los dos intentos
- **ENTONCES** la cuenta tiene un token anulado por entrega fallida y un único token abierto
- **Y** el servidor de prueba recibió un solo correo, cuyo enlace lleva el token abierto
- **Y** la tarea terminó completada

#### Escenario: [I326] Cinco fallos estacionan la tarea sin dejar tokens utilizables

- **DADO** el servidor de prueba detenido durante toda la prueba y un máximo de 5 intentos
- **CUANDO** el trabajador ejecuta la tarea hasta agotarla
- **ENTONCES** hubo exactamente 5 intentos, con esperas de 30, 60, 120 y 240 segundos
- **Y** la tarea sigue en `scheduled_tasks` con `consecutive_failures` igual a 5 y su ejecución estacionada en
  `9999-01-01T00:00:00Z`
- **Y** existen 5 tokens anulados por entrega fallida, 5 asientos `identity.password_reset.delivery_failed` y
  ningún token abierto

#### Escenario: [I327] Un token anulado por entrega fallida se rechaza al restablecer

- **DADO** el token anulado por entrega fallida de [I324], cuyo valor en claro capturó un transporte de prueba
- **CUANDO** se presenta al caso de uso de restablecimiento antes de su vencimiento
- **ENTONCES** se rechaza con el mismo resultado que un token inexistente, sin cambiar la contraseña

#### Escenario: [I328] La anulación no toca un token que ya no está abierto

- **DADO** un token emitido cuyo envío falla, y que entre la emisión y la compensación otra emisión de la misma
  cuenta superó
- **CUANDO** corre la compensación
- **ENTONCES** el token conserva su estado de superado, no se marca como anulado por entrega fallida y el asiento
  de compensación no se escribe
- **Y** el fallo de envío se relanza igual

### Requisito: Un token anulado por entrega fallida no cuenta para el límite por hora ni como token abierto

El sistema NO DEBE contar los tokens anulados por entrega fallida en el límite de tres emisiones por cuenta en la
ventana móvil de 60 minutos, y NO DEBE considerarlos abiertos: no impiden emitir otro token, la emisión no los
supera y no se pueden consumir. Un token tiene como máximo un estado final entre consumido, superado y anulado
por entrega fallida.

#### Escenario: [I329] Tres entregas exitosas siguen limitando aunque haya fallos en la hora

- **DADO** la cuenta `ACC-7` con 4 tokens anulados por entrega fallida y 3 tokens entregados en los últimos 60
  minutos
- **CUANDO** el trabajador ejecuta otra tarea de emisión
- **ENTONCES** la emisión se omite por límite con `issuedInWindow` igual a 3, y no se envía ningún correo

#### Escenario: [I330] Los fallos de entrega no consumen el límite

- **DADO** la cuenta `ACC-7` con 5 tokens anulados por entrega fallida en los últimos 10 minutos y ninguno
  entregado
- **CUANDO** el trabajador ejecuta otra tarea de emisión con el servidor de prueba en marcha
- **ENTONCES** se emite un token nuevo y se entrega

### Requisito: El fallo del envío del enlace no revela el token, el enlace ni el correo

Ningún registro, excepción, fila de `scheduled_tasks`, asiento de auditoría ni fila de la bitácora de envío
DEBE contener el token en claro, el enlace ni el correo de la cuenta, ni en un envío aceptado ni en uno fallido ni
en la compensación.

#### Escenario: [I331] Los centinelas no aparecen en ningún almacén ni registro

- **DADO** la cuenta `centinela.correo@colegio.edu.hn` y un generador de tokens de prueba que produce
  `SENTINEL-TOKEN`, con un primer intento fallido por servidor detenido y un segundo aceptado
- **CUANDO** se capturan los registros del trabajador y se leen `scheduled_tasks`, los asientos de auditoría de la
  institución y `notifications_email_delivery`
- **ENTONCES** ninguno contiene `centinela.correo@colegio.edu.hn`, `SENTINEL-TOKEN` ni el enlace

#### Escenario: [I332] La prueba de centinelas detecta una fuga

- **DADO** la prueba del escenario anterior
- **CUANDO** se ejecuta contra una variante de control cuyo fallo de envío lleva la dirección en su mensaje
- **ENTONCES** la prueba falla, lo que demuestra que alcanza el caso peligroso

## REMOVED Requirements

(Ninguno. La ausencia del envío real del enlace se reescribe por división; ver RENAMED y MODIFIED.)

## RENAMED Requirements

### Requisito: Ausencia de envío real del enlace y de la notificación al titular (brecha con destino: `transactional-email-adapter`, cambio 14 de F0) → Ausencia de notificación al titular tras un restablecimiento (brecha con destino: `account-security-notices`, cambio 14b de F0)

(Reason: este cambio entrega el envío real del enlace; la notificación al titular pasa a 14b por la decisión P1
del propietario del 2026-11-09.)
(Migration: la parte del enlace se sustituye por los requisitos añadidos «El enlace de recuperación se entrega
por correo desde el trabajador, después de confirmar la emisión», «Un fallo del envío del enlace anula el token
no entregado, se audita y hace reintentar la tarea», «Un token anulado por entrega fallida no cuenta para el
límite por hora ni como token abierto» y «El fallo del envío del enlace no revela el token, el enlace ni el
correo». Los escenarios «El puerto de envío solo tiene el emisor que falla de forma explícita», [I265], [I266],
[I277] y [I267] se retiran: los sustituyen [I320] a [I332] y la comprobación de inventario de
`IdentityScopeExclusionInventoryTest`, que pasa a exigir exactamente un adaptador del puerto, el que entrega por
`notifications`.)

### Requisito: Ausencia de envío real del aviso de códigos de recuperación de MFA bajos (brecha con destino: `transactional-email-adapter`, cambio 14 de F0) → Ausencia de envío real del aviso de códigos de recuperación de MFA bajos (brecha con destino: `account-security-notices`, cambio 14b de F0)

(Reason: decisión P1 del propietario del 2026-11-09; el aviso al titular es de 14b.)
(Migration: sin cambio de prueba; `ConsumeRecoveryCodeIT` [I45] conserva su aserción y su comentario cambia de
destino.)

### Requisito: Ausencia de aviso al titular por reutilización detectada (brecha con destino: cambio 14) → Ausencia de aviso al titular por reutilización detectada (brecha con destino: `account-security-notices`, cambio 14b de F0)

(Reason: decisión P1 del propietario del 2026-11-09.)
(Migration: sin cambio de prueba; solo cambia el dueño de la brecha.)

## MODIFIED Requirements

### Requisito: Ausencia de notificación al titular tras un restablecimiento (brecha con destino: `account-security-notices`, cambio 14b de F0)

El sistema NO DEBE enviar, en este cambio, ninguna notificación al titular tras un restablecimiento completado
(`docs/03-seguridad.md` §4.7, «Efecto»; ADR-0005, «Recuperación de contraseña», punto 5). El único correo que
`identity` envía es el del enlace de recuperación. El destino de la notificación, con IP y momento, es
**`account-security-notices`**, cambio 14b de F0, posterior a este cambio; la IP y el momento los aporta
`password-recovery-endpoints`, con la propagación del origen de la petición al restablecimiento.
(Previously: el requisito prohibía también el envío del enlace, exigía como único adaptador del puerto un emisor
que falla de forma explícita y tenía como destino este mismo cambio.)

#### Escenario: Un restablecimiento completado no notifica a nadie

- **DADO** un restablecimiento completado para `ana.martinez@colegio.edu.hn`
- **CUANDO** se inspeccionan los asientos de auditoría que produjo y la bitácora de envío
- **ENTONCES** ningún asiento nombra una notificación, un correo ni un intento de entrega, y no existe ninguna fila
  de envío posterior al restablecimiento para esa cuenta
- **Y** este escenario deja de ser cierto el día que `account-security-notices` entregue el aviso

### Requisito: Ausencia de envío real del aviso de códigos de recuperación de MFA bajos (brecha con destino: `account-security-notices`, cambio 14b de F0)

> **Sustituye al requisito vigente** «Ausencia de envío real del aviso de códigos de recuperación de
> MFA bajos (brecha con destino: `transactional-email-adapter`, cambio 14 de F0)». El propietario partió el
> cambio 14 el 2026-11-09.

El sistema NO DEBE enviar ningún correo ni notificación real al quedar una cuenta con menos de tres
códigos de recuperación de MFA sin usar, tanto si el código se consumió en la verificación posterior
al inicio de sesión como en un restablecimiento de contraseña. El sistema entrega únicamente el dato
calculado y auditado —ver el requisito «Aviso al quedar con menos de tres códigos de recuperación de
MFA sin usar»—. El destino del envío real es **`account-security-notices`**, cambio 14b de F0, posterior a
`transactional-email-adapter`.
(Previously: el destino era `transactional-email-adapter`, cambio 14 de F0.)

#### Escenario: El aviso queda calculado y auditado, sin ningún correo enviado

- **DADO** una cuenta que consume su octavo código de recuperación de MFA, dejando dos sin usar
- **CUANDO** el sistema calcula la señal de aviso
- **ENTONCES** audita el evento con el conteo de códigos restantes y la señal de «por debajo de
  tres»
- **Y** no programa ningún envío ni invoca ningún caso de uso de `notifications`, que el proceso administrativo
  no carga

#### Escenario: Consumir un código en un restablecimiento también calcula el aviso

- **DADO** la cuenta `sofia.mejia@colegio.edu.hn`, con MFA activa, tres códigos de recuperación sin
  usar y un token vivo
- **CUANDO** restablece su contraseña presentando uno de esos códigos
- **ENTONCES** el sistema audita la señal de aviso con dos códigos restantes, igual que si lo hubiera
  consumido tras el inicio de sesión
- **Y** no se envía ningún correo

### Requisito: Ausencia de aviso al titular por reutilización detectada (brecha con destino: `account-security-notices`, cambio 14b de F0)

El sistema NO DEBE enviar, en este cambio, ningún correo ni notificación al titular cuando se detecta la
reutilización de un token de refresco. El destino es **`account-security-notices`**, cambio 14b de F0. El
sistema entrega solo la revocación y su asiento de auditoría.
(Previously: el destino era el cambio 14.)

#### Escenario: Una reutilización detectada no notifica a nadie

- **DADO** una reutilización detectada para `maria.lopez@colegio.edu.hn`
- **CUANDO** se inspeccionan los asientos que produjo y los adaptadores de envío
- **ENTONCES** ningún asiento nombra una notificación, un correo ni un intento de entrega, y ningún
  adaptador de envío fue invocado
- **Y** este escenario deja de ser cierto el día que `account-security-notices` entregue el aviso
