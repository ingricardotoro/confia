# Capacidad: Identidad

- **Identificador:** identity
- **Estado:** Borrador
- **Fase:** F0

## Propósito

Autenticar y autorizar a las dos audiencias del sistema (personal administrativo y encargados de
pago) en dominios de identidad completamente separados, y decidir en el servidor qué puede hacer
cada sesión. Le corresponde: inicio de sesión con contraseña, segundo factor, bloqueo por fuerza
bruta, ciclo de vida del token de sesión y de refresco, recuperación de contraseña y evaluación de
permisos. NO le corresponde: la gestión de los datos del estudiante o del encargado como entidad
de negocio (capacidades `students` y `guardians`), ni la bitácora de auditoría en sí misma
(capacidad transversal `shared/audit`), aunque todo evento de esta capacidad se audite.

## Requisitos

### Requisito: Autenticación con contraseña

El sistema DEBE autenticar a un usuario únicamente con la combinación correcta de identificador
de cuenta y contraseña, y NO DEBE emitir una sesión válida ante una combinación incorrecta.

#### Escenario: Credenciales correctas

- **DADO** un usuario de personal con correo `maria.lopez@colegio.edu.hn` y contraseña vigente
- **CUANDO** inicia sesión con ese correo y esa contraseña
- **ENTONCES** el sistema emite un token de sesión válido para el dominio de personal
- **Y** registra un evento de autenticación exitosa en la bitácora de auditoría

#### Escenario: Contraseña incorrecta

- **DADO** el mismo usuario `maria.lopez@colegio.edu.hn`
- **CUANDO** inicia sesión con la contraseña `Incorrecta#2026`, distinta de la vigente
- **ENTONCES** el sistema rechaza el inicio de sesión con un error genérico de credenciales
  inválidas
- **Y** NO emite ningún token de sesión
- **Y** registra el intento fallido asociado a la cuenta

### Requisito: MFA obligatoria para roles con escritura financiera o de configuración

El sistema DEBE exigir un segundo factor de autenticación antes de emitir una sesión completa
para cualquier usuario con al menos un permiso de escritura financiera o de configuración, y NO
DEBE conceder a esa sesión acceso a operaciones protegidas mientras el segundo factor esté
pendiente.

#### Escenario: Cajero con MFA configurada

- **DADO** un usuario con el rol `cashier`, que incluye el permiso `payments:write`
- **Y** MFA por aplicación de autenticación ya configurada para esa cuenta
- **CUANDO** completa el inicio de sesión con contraseña correcta
- **ENTONCES** el sistema exige un código de un solo uso de seis dígitos antes de emitir el token
  de sesión completo
- **Y** solo tras un código válido, dentro de los treinta segundos de su ventana de vigencia,
  emite un token de sesión con los permisos del rol

#### Escenario: Rol administrativo sin MFA configurada aún

- **DADO** un usuario nuevo con el rol `administrator`, que incluye permisos de configuración
- **Y** ninguna MFA configurada todavía para esa cuenta
- **CUANDO** completa el inicio de sesión con contraseña correcta
- **ENTONCES** el sistema emite únicamente una sesión restringida cuya única acción permitida es
  configurar el segundo factor
- **Y** NO DEBE emitir un token con permisos de escritura financiera o de configuración hasta que
  la MFA quede configurada y verificada

### Requisito: Bloqueo por intentos fallidos con retroceso exponencial

El sistema DEBE bloquear temporalmente una cuenta tras cinco intentos fallidos consecutivos de
autenticación, con un tiempo de bloqueo que crece exponencialmente en cada ciclo adicional de
cinco fallos, y NO DEBE aceptar un intento de autenticación mientras el bloqueo esté vigente,
aunque las credenciales presentadas sean correctas.

#### Escenario: Primer bloqueo tras cinco fallos

- **DADO** la cuenta `carlos.ramirez@colegio.edu.hn` sin bloqueos previos
- **CUANDO** se registran cinco intentos fallidos consecutivos entre las 08:00:00 y las 08:02:00
  del 2026-03-10
- **ENTONCES** la cuenta queda bloqueada durante un minuto a partir del quinto fallo
- **Y** un sexto intento con la contraseña correcta a las 08:02:30 del mismo día es rechazado por
  bloqueo activo, no por credenciales inválidas

#### Escenario: Segundo ciclo de bloqueo con retroceso mayor

- **DADO** la misma cuenta, ya con un ciclo de bloqueo de un minuto cumplido
- **CUANDO** ocurren cinco intentos fallidos adicionales tras el desbloqueo
- **ENTONCES** el sistema aplica un segundo bloqueo de cinco minutos, mayor que el primero
- **Y** registra en la bitácora de auditoría cada ciclo de bloqueo con su duración

### Requisito: Rotación de token de refresco con detección de reutilización

El sistema DEBE emitir un token de refresco nuevo y de un solo uso en cada renovación de sesión,
invalidando el anterior, y DEBE revocar toda la familia de tokens derivados de una misma sesión
en cuanto detecta el reuso de un token de refresco ya invalidado.

#### Escenario: Renovación normal de sesión

- **DADO** un token de refresco `RT-1001` vigente, emitido a las 09:00:00 del 2026-04-02
- **CUANDO** el cliente lo usa para renovar la sesión a las 09:15:00
- **ENTONCES** el sistema emite un token de sesión nuevo y un token de refresco nuevo `RT-1002`
- **Y** invalida `RT-1001` de forma permanente

#### Escenario: Reuso de un token de refresco ya invalidado

- **DADO** que `RT-1001` ya fue usado y sustituido por `RT-1002`, como en el escenario anterior
- **CUANDO** alguien intenta usar `RT-1001` a las 09:20:00 del mismo día
- **ENTONCES** el sistema rechaza la solicitud
- **Y** revoca de inmediato toda la familia de tokens derivada de la sesión original, incluido
  `RT-1002`, forzando un nuevo inicio de sesión
- **Y** registra el evento como una posible fuga de token en la bitácora de auditoría

### Requisito: Separación total entre el dominio de identidad de personal y el de encargados

El sistema NO DEBE aceptar como válido, en la API administrativa, un token emitido para el
dominio de encargados, ni aceptar como válido en la API del portal un token emitido para el
dominio de personal, aunque ambos tokens estén vigentes en su propio dominio.

#### Escenario: Token de encargado presentado a la API administrativa

- **DADO** un encargado de pago con sesión activa y un token de sesión válido en el dominio
  `portal`
- **CUANDO** ese mismo token se presenta a un endpoint de la API administrativa, por ejemplo
  `GET /api/v1/cashbox/sessions`
- **ENTONCES** el sistema rechaza la solicitud con un error de autenticación
- **Y** NO evalúa ningún permiso administrativo para ese token

#### Escenario: Token de personal presentado a la API del portal

- **DADO** un usuario de personal con sesión activa y un token de sesión válido en el dominio
  `staff`
- **CUANDO** ese token se presenta a un endpoint de la API del portal, por ejemplo
  `GET /api/v1/portal/account-statement`
- **ENTONCES** el sistema rechaza la solicitud con un error de autenticación
- **Y** el rechazo ocurre por audiencia de token inválida, sin distinguir si el usuario de
  personal tendría o no el permiso equivalente

### Requisito: Recuperación de contraseña con token de un solo uso y de corta vida

El sistema DEBE emitir, al solicitar recuperación de contraseña, un token de un solo uso válido
por treinta minutos desde su emisión, y NO DEBE aceptar ese token una vez usado, expirado, o
después de que se solicite un token de recuperación más reciente para la misma cuenta.

#### Escenario: Recuperación dentro de la ventana de vigencia

- **DADO** una solicitud de recuperación de contraseña para `ana.martinez@colegio.edu.hn` a las
  10:00:00 del 2026-05-14, que emite el token `PRT-88a1`
- **CUANDO** el usuario establece una contraseña nueva con `PRT-88a1` a las 10:20:00 del mismo día
- **ENTONCES** el sistema acepta el cambio y activa la contraseña nueva
- **Y** invalida `PRT-88a1` de inmediato, de modo que un segundo uso sea rechazado

#### Escenario: Token expirado o superado por uno más reciente

- **DADO** el token `PRT-88a1` emitido a las 10:00:00 del 2026-05-14
- **Y** una segunda solicitud de recuperación para la misma cuenta a las 10:25:00, que emite
  `PRT-88b2`
- **CUANDO** el usuario intenta usar `PRT-88a1` a las 10:35:00
- **ENTONCES** el sistema rechaza `PRT-88a1` por haber sido superado por una solicitud posterior
- **Y** solo `PRT-88b2`, dentro de su propia ventana de treinta minutos, puede completar el cambio

### Requisito: Prohibición de enumeración de usuarios

El sistema NO DEBE revelar, a través de ninguna respuesta de autenticación o de recuperación de
contraseña, si una cuenta con un identificador dado existe o no. La respuesta DEBE ser idéntica
en forma, contenido y tiempo observable de respuesta en ambos casos.

#### Escenario: Solicitud de recuperación con correo existente

- **DADO** que `maria.lopez@colegio.edu.hn` existe como cuenta activa
- **CUANDO** se solicita recuperación de contraseña para ese correo
- **ENTONCES** el sistema responde `202 Accepted` con el mensaje "si la cuenta existe, se envió un
  enlace de recuperación"

#### Escenario: Solicitud de recuperación con correo inexistente

- **DADO** que `nadie.registrado@colegio.edu.hn` no corresponde a ninguna cuenta
- **CUANDO** se solicita recuperación de contraseña para ese correo
- **ENTONCES** el sistema responde `202 Accepted` con el mismo mensaje "si la cuenta existe, se
  envió un enlace de recuperación"
- **Y** NO se envía ningún correo, pero la respuesta HTTP es indistinguible de la del escenario
  anterior

### Requisito: Autorización basada en permisos evaluada en el servidor

El sistema DEBE evaluar en el servidor, en cada solicitud, si el permiso requerido por la
operación está presente en la sesión autenticada, y NO DEBE conceder una operación protegida
basándose en una afirmación de permiso enviada por el cliente.

#### Escenario: Permiso presente en la sesión

- **DADO** una sesión de personal con el permiso `invoicing:issue` asignado por su rol
- **CUANDO** solicita `POST /api/v1/invoicing/invoices` para emitir una factura
- **ENTONCES** el sistema evalúa el permiso contra los datos de la sesión almacenados en el
  servidor y autoriza la operación

#### Escenario: Cliente afirma un permiso que la sesión no tiene

- **DADO** una sesión de personal con el rol `cashier`, que NO incluye `invoicing:void`
- **CUANDO** solicita `POST /api/v1/invoicing/invoices/{id}/void` incluyendo en la solicitud un
  campo `claimedPermission: "invoicing:void"` fabricado por el cliente
- **ENTONCES** el sistema ignora por completo ese campo, evalúa únicamente los permisos
  registrados en el servidor para esa sesión, y rechaza la operación con `403 Forbidden`
