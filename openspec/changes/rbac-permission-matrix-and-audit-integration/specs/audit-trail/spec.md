# Delta para Bitácora de auditoría encadenada

- **Estado:** pendiente de aprobación del propietario del producto
- **Cambio:** `rbac-permission-matrix-and-audit-integration` (F0, cambio 8)
- **Propuesta:** `proposal.md`, aprobada el 2026-11-03, con O1 a O10 y Q1 a Q5 vinculantes

**Convenciones de este delta.**

- Los escenarios nuevos se numeran `AT32` en adelante, a continuación del máximo de la especificación vigente (`AT31`).
- La política de qué denegaciones se auditan, el tope y el fallo de la escritura están en la capacidad `authorization`;
  este delta describe la forma del asiento en la bitácora.
- Valores fijados por el diseño (`design.md` §3, D9 y D10): `entity_type` `authorization.permission`; detalle del asiento
  completo `{"routeTemplate":"..."}`; acción del resumen `authorization.access.denied_summary` con `outcome` `denied` y
  detalle `{"omittedCount":n,"windowStart":"...","windowSeconds":300}` (cuenta en `actor_id` y permiso en `entity_id`);
  el agente de usuario acotado permanece en el asiento completo y está ausente en el resumen; el resumen se emite de
  forma perezosa (al cerrar la ventana, en la siguiente denegación de cualquier clave, y al apagar).

## ADDED Requirements

### Requisito: El asiento de denegación por permiso tiene una forma fija y no contiene la petición

El sistema DEBE escribir cada denegación por permiso como un asiento de la institución del token con `action` igual a
`authorization.access.denied`, `outcome` igual a `denied`, `actor_kind` igual a `staff`, `actor_id` igual al
identificador de la cuenta, `actor_label` igual al identificador de la cuenta (nunca el correo), `entity_id` igual al
código del permiso y `request_id` igual al `traceId` de la respuesta. Su detalle DEBE construirse con el constructor
tipado de `com.confia.shared.audit` y contener solo la plantilla de ruta de la lista de rutas con permiso. El asiento
NO DEBE contener la ruta cruda, la consulta, el cuerpo, las cabeceras, valores de variables de ruta ni el correo. El
asiento DEBE escribirse en su propia transacción y encadenarse con el mismo mecanismo que los demás.

#### Escenario: [AT32] La forma del asiento de denegación

- **DADO** la cuenta `ACC-6` de `INS-1` sin el permiso `identity:read`
- **CUANDO** solicita `GET /api/v1/identity/roles` y es denegada
- **ENTONCES** existe un asiento de `INS-1` con `action` `authorization.access.denied`, `outcome` `denied`,
  `actor_kind` `staff`, `actor_id` y `actor_label` iguales al identificador de `ACC-6`, `entity_type`
  `authorization.permission`, `entity_id` `identity:read` y `request_id` igual al `traceId`
- **Y** su detalle es exactamente `{"routeTemplate":"/api/v1/identity/roles"}`, construido con el constructor tipado

#### Escenario: [AT33] El asiento no contiene la petición

- **DADO** una denegación con variable de ruta, consulta, cuerpo y cabeceras con centinelas distintivos
- **CUANDO** se lee el asiento completo
- **ENTONCES** no contiene ninguno de los centinelas ni el token

#### Escenario: [AT34] El asiento se encadena con la institución del token

- **DADO** denegaciones de dos cuentas de dos instituciones
- **CUANDO** se invoca el verificador de cada cadena
- **ENTONCES** cada asiento pertenece a la cadena de la institución de su token y ambas cadenas son íntegras

### Requisito: El asiento de resumen de denegaciones tiene una forma fija y no contiene la petición

El sistema DEBE escribir el asiento de resumen de denegaciones, cuando se supera el tope y se cierra la ventana, como un
asiento de la institución del token con `action` igual a `authorization.access.denied_summary`, `outcome` igual a
`denied`, la cuenta en `actor_id`, el código del permiso en `entity_id` y el detalle
`{"omittedCount":n,"windowStart":"...","windowSeconds":300}`, construido con el constructor tipado. El asiento de resumen
NO DEBE contener la ruta, la consulta, el cuerpo, las cabeceras, el agente de usuario ni el correo, y se distingue de un
asiento completo por su acción. La suma de los asientos completos y de los conteos de los resúmenes de una
ventana DEBE ser igual al número de denegaciones por permiso de esa cuenta y permiso en esa ventana.

#### Escenario: [AT35] El resumen lleva el conteo y no la petición

- **DADO** un tope de prueba `T` igual a 3, un reloj inyectado y 5 denegaciones de `ACC-6` por `identity:read`
- **CUANDO** el reloj inyectado supera la ventana y se produce la siguiente denegación de cualquier clave
- **ENTONCES** existe un asiento `authorization.access.denied_summary` con `omittedCount` igual a 2, `windowSeconds`
  igual a 300 (la ventana de producción), `actor_id` igual a `ACC-6` y `entity_id` `identity:read`, sin ruta, consulta,
  cuerpo, cabeceras ni agente de usuario

#### Escenario: [AT36] La suma de filas y conteos iguala las denegaciones

- **DADO** el escenario anterior
- **CUANDO** se suman los asientos completos (3) y el conteo del resumen (2)
- **ENTONCES** la suma es 5, el número de denegaciones de la ventana

#### Escenario: [AT37] La cadena verifica tras los asientos de resumen

- **DADO** una institución con asientos de denegación y de resumen escritos
- **CUANDO** se invoca el verificador de cadena
- **ENTONCES** reporta la cadena íntegra

### Requisito: Solo las denegaciones por permiso producen el asiento `authorization.access.denied`

El sistema NO DEBE escribir un asiento con la acción `authorization.access.denied` ante una ruta sin mapear, un rechazo
de CSRF o de `Origin`, un `404` a nivel de objeto, una respuesta `401`, un rechazo por falta de `otp` ni un fallo de
infraestructura de la resolución de permisos.

#### Escenario: [AT38] Los casos no auditados no dejan asiento de denegación

- **DADO** una ruta sin mapear, un rechazo de CSRF, un `404` a nivel de objeto, una respuesta `401`, un rechazo por
  falta de `otp` y un fallo de resolución de permisos
- **CUANDO** se producen
- **ENTONCES** no existe ningún asiento `authorization.access.denied` por ninguno

## MODIFIED Requirements

### Requisito: Criterio de fila de la bitácora limitado a la institución, sin filtro por `audit:read` (cambios 7 y 8)

`docs/03-seguridad.md` §6.3 declara que el criterio de fila de `shared_audit_log` es solo la
institución; el permiso `audit:read` de `docs/03-seguridad.md` §5.2 se aplica en la capa `web`, no
en la política de fila. El cambio 8 incorpora `audit:read` al catálogo de permisos, concedido solo a Super Admin y a
Auditor, pero NO construye ningún controlador, caso de uso ni endpoint que lea `shared_audit_log`: el endpoint de
lectura con `audit:read` es del cambio 8b. La política de fila que se entrega no conoce el permiso `audit:read`.
(Previously: el permiso dependía del control de acceso por roles de los cambios 7 y 8 y ninguno construía el endpoint;
ahora el permiso existe y el endpoint pasa al cambio 8b.)

#### Escenario: La política de fila filtra solo por institución, sin verificar ningún permiso

- **DADO** dos instituciones con filas propias en `shared_audit_log`
- **CUANDO** una sesión con el contexto de la primera institución consulta la tabla con
  `confia_admin_app`
- **ENTONCES** recibe las filas de su propia institución sin ninguna verificación adicional del
  permiso `audit:read`, porque la política de fila no lo conoce

#### Escenario: Ningún endpoint expone la bitácora con el permiso `audit:read` — cierto tras el cambio 8, falso cuando el cambio 8b lo entregue

- **DADO** el inventario de rutas de OpenAPI que la aplicación administrativa expone al cerrar
  este cambio
- **CUANDO** se busca una ruta que exponga `shared_audit_log` o el módulo `audit` con el permiso
  `audit:read`
- **ENTONCES** ninguna existe, aunque el permiso `audit:read` exista en el catálogo; este resultado deja de ser cierto
  el día que el cambio 8b agregue el controlador y la verificación de ese permiso
