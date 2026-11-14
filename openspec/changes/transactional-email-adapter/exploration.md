# Exploración: `transactional-email-adapter` (cambio 14 de F0)

- **Línea base:** `main` en `9154c45`, worktree `C:\dev\confia-wt-email`, rama
  `change/transactional-email-adapter`.
- **Fecha:** 2026-11-09.
- **Origen del cambio:** respuestas del propietario a `password-recovery-token` (fila 14 de la nota
  del 2026-09-30 en `openspec/changes/foundations-plan/exploration.md`, líneas 255-262) y nota del
  2026-10-16 del mismo archivo (líneas 264-273), que añade el aviso al titular por reutilización
  detectada.
- **Depende de:** cambio 9, `background-jobs-with-db-scheduler` (archivado el 2026-11-03): el envío
  corre en `confia-worker`.

## 1. Estado actual

### 1.1 Qué existe en el código

| Pieza | Evidencia | Estado |
|---|---|---|
| Puerto de envío del enlace | `apps/api/app/src/main/java/com/confia/identity/application/PasswordResetLinkSender.java:19-31` | `ensureAvailable()` (por defecto no hace nada) y `send(InstitutionId, StaffAccountId, PlainPasswordResetToken)`. Su Javadoc (líneas 12-17) asigna al cambio 14 el adaptador real **y el contrato de reintento o encolado** |
| Único adaptador de producción | `identity/infrastructure/UnavailablePasswordResetLinkSender.java:50-61` | Falla de forma explícita en `ensureAvailable()` y en `send()` (decisión P4 del propietario, cambio 9) |
| Cableado en el trabajador | `identity/infrastructure/wiring/IdentityWorkerConfiguration.java:58-76` | El bean devuelve el tipo concreto `UnavailablePasswordResetLinkSender` y se inyecta en `IssuePasswordResetToken` |
| Emisión del token | `identity/application/IssuePasswordResetToken.java:75-81` | `ensureAvailable()` antes de la transacción; `send()` **después** de confirmar y fuera de ella |
| Tarea de emisión | `identity/application/PasswordResetIssuanceJobs.java:45-47`, `PasswordResetIssuanceJobHandler.java:27-30` | `identity.password-reset-issuance`, 5 intentos, espera exponencial de 30 s a 5 min; solo una excepción provoca reintento |
| Señal de códigos de recuperación bajos | `identity/application/ConsumeRecoveryCode.java:42-47, 97-99, 123-130` | Umbral `< 3`; se calcula y se audita (`identity.mfa.recovery_codes.low`), sin envío |
| Reutilización de refresco | `identity/application/RotateRefreshToken.java:63-64, 85, 299-308` | Revoca la familia y audita `identity.session.reuse_detected`; «notifies nobody (the notice to the holder is change 14's)» |
| Programación desde el proceso administrativo | `bootstrap/admin/AdminApplication.java:71-77` (importa `JobSchedulingConfiguration`), `identity/infrastructure/JobPasswordResetIssuanceScheduler.java` | El proceso administrativo ya puede programar tareas dentro de la transacción del negocio |
| Saneamiento de fallos de tareas | `shared/jobs/infrastructure/JobExecutionFailedException.java:11-31` | Solo conserva la clase de la excepción original y el `SQLState`: el mensaje de una excepción de Jakarta Mail (que suele contener direcciones) no llega a `scheduled_tasks` |
| Huella con llave | `identity/domain/IdentifierFingerprint.java:4-22` | HMAC-SHA-256 en hexadecimal; precedente reutilizable para el hash del destinatario |
| Correo del personal | `db/migration/V5__create_identity_staff_account.sql:13-19` | `email TEXT NOT NULL`, único por institución, en minúsculas; en claro (no cifrado) |
| Migraciones | `db/migration/` | La última es `V9`; este cambio tomaría `V10` o posterior |
| Dependencias de correo | `apps/api/pom.xml`, `apps/api/app/pom.xml` | **Ninguna**: no hay `spring-boot-starter-mail`, motor de plantillas ni servidor de correo de prueba |
| `docker-compose` | búsqueda en el repositorio | **No existe** todavía; lo entrega el cambio 11 (`containerization-and-cicd-pipeline`) |
| Módulo `notifications` | `docs/01-arquitectura.md:202` | Previsto en el árbol de módulos, **no existe** en el código |

### 1.2 Qué dicen las especificaciones vigentes (brechas con destino en este cambio)

`openspec/specs/identity/spec.md` contiene tres requisitos de ausencia que este cambio debe retirar o
reescribir:

1. Línea 833: «Ausencia de envío real del aviso de códigos de recuperación de MFA bajos», escenario
   «El aviso queda calculado y auditado, sin ningún correo enviado» (línea 846).
2. Línea 1175: «Ausencia de envío real del enlace y de la notificación al titular», con los escenarios
   «El puerto de envío solo tiene el emisor que falla de forma explícita», «Un restablecimiento
   completado no notifica a nadie» (línea 1200) e I265, I266, I267 e I277. La línea 1189 añade que
   **la IP de la notificación la aporta `session-endpoints-cookie-and-csrf`** con la propagación del
   origen de la petición a los comandos de identidad.
3. Línea 2245: «Ausencia de aviso al titular por reutilización detectada», escenario «Una reutilización
   detectada no notifica a nadie» (línea 2251), que declara que deja de ser cierto cuando el cambio 14
   entregue el aviso.

Las pruebas de inventario que fijan esas ausencias están en
`apps/api/app/src/test/java/com/confia/identity/IdentityScopeExclusionInventoryTest.java:286-320` y en
`ConsumeRecoveryCodeIT#consumingTheEighthCodeAuditsTheLowSignalWithoutSendingAnyEmail` (I45).

### 1.3 Qué dicen los documentos de referencia

| Fuente | Contenido relevante |
|---|---|
| ADR-0014, líneas 56-65, 134, 336, 359-363 | Correo transaccional **por SMTP** (SES en producción, cualquier SMTP fuera de AWS). Solo se admite el SDK de AWS de S3; configuración por variables de entorno; compatibilidad SMTP probada «contra el servidor de correo de prueba». SES arranca en modo de pruebas y exige pedir acceso de producción con anticipación |
| ADR-0016, reglas 3, 5, 6 y 7 (líneas 147-162) | Datos de tarea sin correos; idempotencia «un envío por notificación»; reintentos acotados y alerta al agotarse; el envío de correo nunca con el bloqueo tomado |
| `docs/03-seguridad.md` §4.7 (líneas 604-613, 643-645) | El token en claro existe **únicamente dentro del correo**; el restablecimiento notifica al titular **con IP y momento**; nota editorial que deja el envío real a este cambio |
| `docs/03-seguridad.md`, línea 1045 y 1433 | Toda conexión saliente a correo verifica el certificado |
| `docs/05-infraestructura-y-despliegue.md`, líneas 27, 407-413, 773, 812-815 | Variables `SMTP_HOST`, `SMTP_PORT`, `SMTP_USER`, `SMTP_PASSWORD`; servidor local **MailHog** `v1.0.1` fijado por digest; ausente en producción |
| `docs/07-observabilidad-y-operaciones.md`, líneas 121, 131, 219, 481-482, 650-652 | Prohibido registrar el correo en claro; se registra `recipient_hash`; métricas `confia_notifications_sent_total` y `confia_notifications_failed_total` con `channel="email"`; alertas `TaskFailed` y `NotificationFailureRateHigh` |
| `docs/08-datos-privacidad-y-retencion.md`, líneas 92, 102, 316, 435, 515-560, 578 | El correo es dato personal; `notification_log.recipient_hash` con hash, retención de 24 meses; §8.4: **ningún módulo distinto de `notifications` depende de un cliente de correo** (regla de ArchUnit), lista de supresión, restricción de tratamiento, consentimiento y tope de frecuencia dentro del motor; SES como encargado fuera de Honduras |
| `docs/02-modelo-de-dominio.md`, líneas 51, 199, 311 | `NotificationTemplate` versionada por canal e idioma; `NotificationLog` con `recipientHash`, `status`, `providerMessageId`, `errorCode` |
| `docs/09-roadmap-y-fases.md`, líneas 125, 506-518 | F0: el cambio 14 entrega el enlace y el aviso tras el restablecimiento con IP y momento, y el aviso de códigos bajos. F6: abstracción de canal, bitácora de entrega, rebotes, baja, SPF/DKIM/DMARC |

## 2. Qué debe entregar el cambio 14 (inventario heredado)

| # | Obligación | Evidencia | Observación |
|---|---|---|---|
| E1 | Envío real del enlace de recuperación | spec línea 1175; `PasswordResetLinkSender.java:12-17` | Incluye el contrato de reintento |
| E2 | Aviso al titular tras el restablecimiento, con IP y momento | `docs/03` §4.7 «Efecto»; spec línea 1175 y 1189 | La IP depende de la propagación del origen al restablecimiento (ver §5, D8) |
| E3 | Aviso de códigos de recuperación de MFA bajos | spec línea 833; `ConsumeRecoveryCode.java:97-99` | Se dispara en el proceso administrativo (inicio de sesión o restablecimiento) |
| E4 | Aviso al titular por reutilización detectada | `foundations-plan/exploration.md:264-273`; spec línea 2245 | Se dispara en el proceso administrativo, en la rotación del refresco |
| E5 | Prueba de compatibilidad SMTP contra el servidor de correo de prueba | ADR-0014, cumplimiento 5 | Requiere elegir el servidor de prueba |

## 3. Frontera con el trabajo en curso

- **`password-recovery-endpoints` (S3, planificado en paralelo; rama local sin commits a la fecha).**
  Ese cambio es dueño de `reset/request` y `reset/confirm`, de la respuesta uniforme `202`, del límite
  de 10 por hora por IP, de la puerta de tiempo y de la propagación del origen HTTP al comando de
  restablecimiento. **Este cambio** es dueño del puerto y adaptador de envío, de las plantillas, de la
  internacionalización del texto de los correos, de los reintentos y de la observabilidad del envío.
  Puntos de contacto:
  1. La IP de E2 solo existe si S3 propaga `RequestOrigin` al restablecimiento. Hoy
     `ResetPasswordWithToken.execute(SecurityContext, ResetPasswordCommand)` no la recibe y
     `SecurityContext` (`shared/security/SecurityContext.java:15`) no tiene IP.
  2. La URL del enlace debe apuntar a la página de restablecimiento que consume `reset/confirm`.
     El formato (ruta y si el token viaja en el fragmento) se acuerda entre ambos cambios.
  3. Ambos cambios editan el mismo requisito de ausencia de la línea 1175 de `identity/spec.md` y
     `IdentityScopeExclusionInventoryTest`: riesgo de conflicto de fusión en el delta.
- **Sesión en `C:\dev\confia`, rama `change/rbac-permission-matrix-and-audit-integration-5a-mfa-policy-derivation`.**
  Toca `identity` (derivación de MFA desde `MfaPolicy`). Riesgo de conflicto en `identity/spec.md`,
  en el cableado de `identity` y en la numeración de migraciones si añade alguna.

## 4. Puntos de diseño abiertos

### D1. Dónde vive el envío de correo

- **A. Módulo nuevo `notifications`, mínimo** (solo canal correo, solo propósito transaccional),
  cargado únicamente en el trabajador. Cumple `docs/01:202` y la regla de `docs/08` §8.4 (solo
  `notifications` depende de un cliente de correo). `identity` mantiene sus puertos y su adaptador en
  `identity.infrastructure` llama a la API pública de `notifications`.
- **B. Paquete técnico `shared/mail`.** Más rápido, pero contradice §8.4 y obliga a migrarlo en F6.
- **C. Adaptador dentro de `identity.infrastructure`.** Acopla un cliente SMTP a identidad; cada
  módulo futuro repetiría el cliente.

**Recomendación: A.** Es la ubicación que fija la arquitectura y F6 la extiende sin moverla.

### D2. Semántica del envío del enlace ante un fallo transitorio

El token en claro no puede persistirse (`docs/03` §4.7), así que una tarea de entrega separada no
puede llevarlo ni recuperarlo.

- **A. Envío tras confirmar, sin compensación (comportamiento previsto hoy).** Un fallo SMTP deja un
  token emitido y no entregado; el reintento emite otro y consume el límite de 3 por hora; al cuarto
  intento la emisión se omite y la tarea **termina completada sin entrega**. Es exactamente el hueco
  silencioso que la decisión P4 quiso evitar (spec línea 1180-1186).
- **B. Envío tras confirmar, con compensación.** Si el envío falla, una segunda transacción marca el
  token como no entregado (supera o anula ese token, que **no cuenta** para el límite por hora),
  audita el fallo y relanza para que la tarea reintente con espera. Requiere una migración en
  `identity_password_reset_token` y ajustar el conteo del límite.
- **C. Envío dentro de la transacción de emisión.** Viola ADR-0016 regla 7 y la decisión 6 del diseño
  de `password-recovery-token` (nunca con el bloqueo tomado). Descartada.
- **D. Guardar el token cifrado para una tarea de entrega.** Viola §4.7. Descartada.

**Recomendación: B.** Mantiene «nunca se descarta en silencio» y la vida de 30 minutos cubre los 5
intentos (espera acumulada de unos 7,5 minutos).

### D3. Avisos E2, E3 y E4: cómo se programan

Se disparan en el proceso administrativo, dentro de la transacción del caso de uso. Una tarea nueva
`notifications.security-notice` (o una por aviso) con datos solo de identificadores (institución,
cuenta, tipo de aviso, identificador del asiento de auditoría o de la petición como clave de
idempotencia). El manejador del trabajador carga el correo de la cuenta, renderiza, envía y registra
el envío con una restricción única por clave de aviso (ADR-0016 regla 5). La entrega es «al menos una
vez»: una caída entre la aceptación del servidor SMTP y el registro puede duplicar un correo; se
acepta y se declara.

### D4. Plantillas e internacionalización

- **A. Texto plano con catálogo de mensajes** (`MessageSource`, `es-HN` por defecto), sin motor de
  plantillas. Mínimo, sin riesgo de inyección HTML, suficiente para avisos de seguridad.
- **B. Thymeleaf en modo texto y HTML**, con escape automático e i18n por catálogo. Más presentación,
  una dependencia más y una superficie más para revisar.
- **C. Plantillas versionadas en base de datos (`NotificationTemplate`).** Es el modelo de F6;
  prematuro en F0.

**Recomendación: A en F0**, con la clave de plantilla y su versión registradas para que F6 migre a C.

### D5. Servidor SMTP de prueba y entorno local

`docs/05` fija MailHog `v1.0.1`, sin publicaciones desde 2020. Mailpit es su sustituto mantenido, con
los mismos puertos (1025 SMTP, 8025 web) y una API REST para que las pruebas lean lo recibido.

- **A. Mailpit en Testcontainers** para las pruebas de integración y como servidor local (se actualiza
  `docs/05`; el servicio de `docker-compose` lo crea el cambio 11).
- **B. GreenMail en la JVM** para pruebas, MailHog en local. Sin contenedor, pero el servidor de prueba
  difiere del local.
- **C. Mantener MailHog** en ambos.

**Recomendación: A.** La imagen se fija por digest, como el resto.

### D6. Rebotes y lista de supresión

SES informa rebotes y quejas por SNS, lo que exige un webhook firmado (`confia-security-checklist` §4).
Un rechazo síncrono `5xx` del servidor SMTP sí es observable en el envío. **Recomendación:** registrar
el rechazo síncrono como envío fallido y dejar rebotes asíncronos, lista de supresión, baja y tope de
frecuencia en F6 (`docs/09:506-518`), como brecha con destino.

### D7. Secretos y transporte

`SMTP_USER` y `SMTP_PASSWORD` por variables de entorno (credenciales SMTP de SES, no de la API de
AWS); propiedades con `toString()` redactado; nunca en registros. En producción, STARTTLS obligatorio,
verificación de identidad del servidor y tiempos de espera de conexión, lectura y escritura acotados
para que un SMTP lento no bloquee el trabajador. Remitente (`MAIL_FROM`) por variable de entorno. La
verificación de dominio (SPF, DKIM, DMARC) y el acceso de producción de SES son operación (cambio 11
y runbook), no código.

### D8. IP y momento del aviso tras el restablecimiento

- **A.** El manejador lee IP y momento del asiento `identity.password_reset.completed` referido por la
  tarea (lectura cruzada a la bitácora de auditoría).
- **B.** El caso de uso guarda IP y momento en una fila propia de aviso pendiente al programar la tarea.
- **C.** Entregar E2 sin IP hasta que S3 la propague.

Depende de lo que entregue `password-recovery-endpoints`. **Recomendación:** B si S3 propaga el origen
antes de este cambio; si no, mover E2 al segundo corte (ver §6).

### D9. Hash del destinatario

`docs/08:435` exige hash desde el origen. **Recomendación:** HMAC-SHA-256 con llave propia de
`notifications` (variable de entorno), mismo patrón que `IdentifierFingerprint`, de modo que el hash no
se pueda revertir por diccionario de correos.

### D10. Bitácora de envío

Tabla `notifications_email_delivery` (o `notifications_log`) con `institution_id`, seguridad de fila
forzada, clave de plantilla y versión, `recipient_hash`, estado, identificador del mensaje del
proveedor y clase del error; solo inserción por intento, sin `UPDATE` ni `DELETE` al rol de
aplicación. Retención de 24 meses: la purga puede diferirse a F6 porque ninguna fila llega a esa edad
antes, declarándolo como brecha.

### D11. Auditoría

La emisión del token ya se audita. **Recomendación:** auditar la compensación por envío fallido del
enlace (`identity.password_reset.delivery_failed`), porque cambia el estado del token; el envío exitoso
de cualquier correo queda en la bitácora de envío, que `docs/08` declara como evidencia de notificación.

## 5. Enfoques y esfuerzo

| Enfoque | Contenido | Tareas estimadas |
|---|---|---|
| Un solo cambio | D1-A, D2-B, D3, D4-A, D5-A, D6, D7, D9, D10, los cuatro mensajes | 16-18: supera el límite de 15 |
| **Dos cambios (recomendado)** | **14a `transactional-email-adapter`:** módulo `notifications` mínimo, adaptador SMTP, bitácora de envío, plantillas en texto plano, Mailpit, entrega del enlace con compensación (E1, E5). **14b `account-security-notices`:** tarea de aviso y los avisos E2, E3 y E4 | 14a: 11-12; 14b: 6-7 |

El corte separa la infraestructura (que solo necesita un consumidor, el enlace, sin tarea nueva) de
los avisos, que comparten una tarea nueva programada desde el proceso administrativo y dependen, para
E2, de la IP que aporta S3.

## 6. Riesgos

| Riesgo | Mitigación |
|---|---|
| Un mensaje de excepción de Jakarta Mail contiene direcciones y llega a registros | Saneamiento existente de fallos de tareas (`JobExecutionFailedException`); además, el adaptador traduce a errores propios sin mensaje del proveedor, con prueba de redacción |
| SES en modo de pruebas solo entrega a destinatarios verificados | Solicitar acceso de producción con anticipación (ADR-0014, línea 336); runbook |
| Fallo SMTP prolongado agota los 5 intentos de emisión | Tarea estacionada y alerta `TaskFailed`; la persona repite la solicitud |
| Correo duplicado por la semántica «al menos una vez» | Aceptado y declarado; restricción única por clave de aviso reduce la ventana |
| Conflicto de fusión con S3 y con la rama RBAC en `identity/spec.md` y migraciones | Acordar la frontera de §3, numerar migraciones al rebasar |
| El enlace con el token viaja en la URL | Token en el fragmento (`#`), página con `Referrer-Policy: no-referrer` (§4.7) |
| Transferencia internacional a SES sin base legal | Ningún dato real antes de la confirmación legal de `docs/08` (ADR-0014) |

## 7. Hallazgos colaterales

- `docs/08` §8.4 muestra el caso de uso de despacho en TypeScript
  (`dispatch-notification.use-case.ts`), anterior a ADR-0013: el backend es Java. Corrección editorial
  recomendada en este cambio o en F6.
- `docs/05` fija MailHog, sin mantenimiento; ver D5.

## 8. Preguntas para el propietario

1. ¿Se parte el cambio 14 en 14a y 14b? Recomendación: sí.
2. ¿Módulo `notifications` mínimo (D1)? Recomendación: A.
3. ¿Compensación ante fallo del envío del enlace (D2)? Recomendación: B.
4. ¿Plantillas en texto plano con catálogo `es-HN` en F0 (D4)? Recomendación: A.
5. ¿Mailpit sustituye a MailHog (D5)? Recomendación: sí.
6. ¿Rebotes, supresión y baja quedan en F6 (D6)? Recomendación: sí, como brecha con destino.
7. ¿Formato del enlace? Recomendación: variable `PASSWORD_RESET_LINK_BASE_URL` del panel
   administrativo, solo `https` fuera de local, token en el fragmento; ruta acordada con S3.
8. ¿Un remitente por despliegue (`MAIL_FROM`) o por institución? Recomendación: por despliegue en F0.

## 9. Listo para la propuesta

Sí, con las recomendaciones anteriores como supuestos hasta la respuesta del propietario.
