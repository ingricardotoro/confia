# Propuesta: adaptador de correo transaccional (cambio 14 de F0, primer corte)

- **Cambio:** `transactional-email-adapter`
- **Fase del roadmap:** F0, cambio 14 (`openspec/changes/foundations-plan/exploration.md`, nota del
  2026-09-30, fila 14, y nota del 2026-10-16). Esta propuesta recomienda partirlo en dos cambios
  secuenciales: **14a, este**, y **14b, `account-security-notices`** (identificador propuesto).
- **Depende de:** `background-jobs-with-db-scheduler` (cambio 9, archivado el 2026-11-03),
  `password-recovery-token` y `process-entry-point-isolation` (cambio 15).
- **Exploración:** `openspec/changes/transactional-email-adapter/exploration.md`.
- **Línea base:** `main` en `9154c45` al explorar; rebasada sobre `origin/main` en `af20884` antes de
  especificar (la última migración en esa base es `V14`).
- **Estado:** **aprobada por el propietario del producto el 2026-11-09**, con todas las
  recomendaciones aceptadas. Las decisiones P1 a P8 de la sección final son vinculantes y no se
  reabren en las fases siguientes.

## Intención

El sistema ya emite el token de recuperación de contraseña en `confia-worker`, pero no puede
entregarlo: el único adaptador del puerto `PasswordResetLinkSender` es
`UnavailablePasswordResetLinkSender`, que falla de forma explícita (decisión P4 del cambio 9). Por eso
la recuperación de contraseña no funciona de punta a punta, y tres avisos de seguridad que exigen
`docs/03-seguridad.md` y ADR-0005 (restablecimiento completado, códigos de recuperación bajos y
reutilización de refresco) solo se calculan y se auditan.

Este cambio entrega la capacidad de **enviar correo transaccional por SMTP** (SES en producción,
ADR-0014) desde el trabajador, con plantillas internacionalizadas, bitácora de envío sin datos
personales en claro, observabilidad y pruebas contra un servidor SMTP real de prueba, y la usa para su
primer consumidor: **el enlace de recuperación de contraseña**.

**Por qué ahora:** el cambio 9 ya ejecuta la emisión en el trabajador y `password-recovery-endpoints`
se planifica en paralelo; sin envío real, sus endpoints devolverían `202` sin que el enlace llegue
nunca.

**Cómo se ve el éxito:** una solicitud de recuperación produce, en el trabajador, exactamente un correo
con un enlace válido recibido por el servidor SMTP de prueba; un fallo SMTP no deja tokens entregables
huérfanos, no consume el límite por hora y la tarea reintenta con espera; ni el correo, ni el token, ni
el enlace aparecen en registros, excepciones, datos de tarea ni bitácoras.

## Alcance

### Dentro de alcance

**Módulo `notifications`, mínimo**

- Paquete de primer nivel `notifications` con sus cuatro capas, declarado en Spring Modulith, cargado
  **solo** en `WorkerApplication` (registro en `@Import` y en `ProcessBeanPolicy`).
- Caso de uso público para enviar un correo transaccional (propósito `transactional` únicamente),
  puerto de transporte en `application` y adaptador SMTP en `infrastructure`.
- Regla de ArchUnit: ningún paquete fuera de `notifications.infrastructure` depende de
  `jakarta.mail` ni del cliente de correo de Spring (`docs/08` §8.4).

**Adaptador SMTP**

- Dependencia del iniciador de correo de Spring Boot (versión y nombre del artefacto verificados en el
  diseño contra Spring Boot 4.1), sin SDK de SES (ADR-0014, cumplimiento 1).
- Configuración solo por variables de entorno: `SMTP_HOST`, `SMTP_PORT`, `SMTP_USER`,
  `SMTP_PASSWORD`, más `MAIL_FROM` y la llave del hash del destinatario. Propiedades con
  `toString()` redactado.
- Fuera del perfil local: STARTTLS obligatorio, verificación de identidad del servidor y tiempos de
  espera acotados; el arranque falla si la configuración lo contradice.
- Traducción de las excepciones del proveedor a errores propios sin mensaje original, para que ninguna
  dirección llegue a registros ni a `scheduled_tasks`.

**Plantillas e internacionalización (supuesto P4)**

- Correo en texto plano, con asunto y cuerpo en un catálogo de mensajes del backend, `es-HN` por
  defecto. Cada plantilla tiene clave y versión, que se registran en la bitácora de envío.
- Plantilla del enlace de recuperación.

**Bitácora de envío**

- Migración Flyway nueva con la tabla de envíos del módulo (prefijo `notifications_`), con
  `institution_id`, `ROW LEVEL SECURITY` forzada, `REVOKE ALL` de partida, solo `INSERT` y `SELECT`
  para el rol del trabajador, `recipient_hash` (HMAC-SHA-256 con llave propia), clave y versión de
  plantilla, estado, identificador del mensaje del proveedor y clase del error. Nunca el correo, el
  asunto renderizado ni el cuerpo.

**Entrega del enlace de recuperación (supuesto P3)**

- Adaptador de `PasswordResetLinkSender` en `identity.infrastructure` que carga el correo de la
  cuenta, compone el enlace con `PASSWORD_RESET_LINK_BASE_URL` (token en el fragmento de la URL) y
  llama a la API pública de `notifications`. Sustituye a `UnavailablePasswordResetLinkSender` en el
  cableado del trabajador.
- Compensación ante fallo del envío: en una transacción propia, el token no entregado queda anulado,
  no cuenta para el límite de tres emisiones por hora y se audita
  `identity.password_reset.delivery_failed`; la excepción se relanza y la tarea reintenta con su
  política actual (5 intentos, de 30 s a 5 min). Requiere una migración sobre
  `identity_password_reset_token`.

**Observabilidad**

- Métricas `confia_notifications_sent_total` y `confia_notifications_failed_total` con
  `channel="email"` (`docs/07`, líneas 481-482); registros con `recipient_hash`, clave de plantilla y
  resultado, nunca la dirección, el enlace ni el token.

**Pruebas**

- Prueba de integración del adaptador contra **Mailpit** en Testcontainers, fijado por digest
  (ADR-0014, cumplimiento 5; supuesto P5).
- Prueba de extremo a extremo en el trabajador: tarea de emisión, correo recibido por Mailpit, token
  del enlace aceptado por `ResetPasswordWithToken`.
- Pruebas de fallo: SMTP caído o rechazo `5xx` produce compensación, reintento y ningún token
  entregable huérfano; agotar los intentos estaciona la tarea.
- Pruebas de redacción: ni el correo, ni el token, ni el enlace en registros, excepciones, datos de
  tarea, bitácora de envío ni auditoría.
- Seguridad de fila de la tabla nueva con el rol restringido.

**Especificaciones y documentación**

- Delta de `identity`: se reescribe el requisito de ausencia de la línea 1175 (el enlace se entrega;
  la notificación al titular pasa a 14b) y sus escenarios I265, I266, I267 e I277; se actualiza
  `IdentityScopeExclusionInventoryTest`.
- Capacidad nueva `notifications`.
- Delta de `build-integrity`: la regla de dependencia del cliente de correo y la tabla nueva.
- `docs/05` (Mailpit en lugar de MailHog, variables nuevas), notas editoriales en `docs/03` §4.7,
  `docs/07` y `docs/08`, y un runbook de entrega de correo (fallo del proveedor, acceso de producción
  de SES, rotación de credenciales SMTP).
- ADR nuevo que registre la ubicación del módulo, la elección de texto plano y de Mailpit, y la
  compensación del envío del enlace.

### Fuera de alcance, con dueño nombrado

| Tema | Dueño |
|---|---|
| Avisos al titular: restablecimiento completado con IP y momento, códigos de recuperación de MFA bajos y reutilización de refresco detectada | **14b, `account-security-notices`** (propuesto), posterior a este cambio y, para la IP, a `password-recovery-endpoints` |
| Endpoints `reset/request` y `reset/confirm`, respuesta uniforme, límite por IP, propagación del origen HTTP | `password-recovery-endpoints` |
| Página de restablecimiento del panel administrativo que lee el token del fragmento | `password-recovery-endpoints` o el primer cambio de `apps/admin-web` que la construya |
| Servicio de correo de prueba en `docker-compose` | Cambio 11, `containerization-and-cicd-pipeline` (este cambio deja escrita la definición en `docs/05`) |
| Rebotes asíncronos de SES por SNS, lista de supresión, baja, consentimiento, tope de frecuencia por destinatario, abstracción de canal, plantillas versionadas en base de datos y correo HTML | F6 (`docs/09`, entregables 5 y 7) |
| Purga de la bitácora de envío a los 24 meses | F6; ninguna fila llega a esa edad antes |
| Verificación del dominio (SPF, DKIM, DMARC) y acceso de producción de SES | Cambio 11 y operación, con el runbook de este cambio |

## Capacidades

### Nuevas

- `notifications`: envío de correo transaccional, bitácora de envío, redacción del destinatario.

### Modificadas

- `identity`: entrega real del enlace con compensación ante fallo.
- `build-integrity`: regla de dependencia del cliente de correo, tabla nueva con seguridad de fila.

## Enfoque

1. ADR y dependencias primero; regla de ArchUnit en rojo antes de crear el módulo.
2. Módulo `notifications` con su caso de uso, puerto y adaptador SMTP, probado contra Mailpit.
3. Migraciones (bitácora de envío; estado de entrega del token).
4. Adaptador del enlace en `identity` con compensación, y cambio de cableado del trabajador.
5. Pruebas de extremo a extremo, de fallo y de redacción; deltas de especificación; documentación.

El orden respeta el desarrollo guiado por pruebas: cada comportamiento observable empieza con su
prueba en rojo.

## Áreas afectadas

- `apps/api/app/src/main/java/com/confia/notifications/` (nuevo).
- `apps/api/app/src/main/java/com/confia/identity/infrastructure/` (adaptador del enlace, cableado del
  trabajador) y `identity/application/IssuePasswordResetToken.java` (compensación).
- `apps/api/app/src/main/java/com/confia/bootstrap/worker/WorkerApplication.java` y
  `ProcessBeanPolicy`.
- `apps/api/app/src/main/resources/db/migration/` (dos migraciones nuevas, `V16` y `V17` sobre
  `af20884`; `V15` la reserva la parte 5b de `rbac-permission-matrix-and-audit-integration`).
- `apps/api/app/pom.xml`, reglas de `maven-enforcer-plugin` y de ArchUnit.
- `openspec/specs/identity`, `openspec/specs/build-integrity`, `openspec/specs/notifications` (nueva).
- `docs/03`, `docs/05`, `docs/07`, `docs/08`, `docs/adr/`, `docs/runbooks/`.

## Riesgos

| Riesgo | Mitigación |
|---|---|
| Una excepción de Jakarta Mail con direcciones llega a registros | Traducción a errores propios en el adaptador, saneamiento existente de fallos de tareas, prueba de redacción |
| SES en modo de pruebas no entrega a destinatarios no verificados | Runbook y solicitud anticipada de acceso de producción |
| Un SMTP lento bloquea el trabajador | Tiempos de espera acotados y probados |
| Correo duplicado por reintento | El reintento solo ocurre tras un fallo; un enlace previo queda anulado y no es utilizable |
| Conflicto con `password-recovery-endpoints` y con la rama RBAC en `identity/spec.md`, `IdentityScopeExclusionInventoryTest` y numeración de migraciones | Frontera escrita en esta propuesta; numeración al rebasar; el requisito de la línea 1175 se divide por mensaje |
| Transferencia internacional a SES sin base legal | Ningún dato real antes de la confirmación legal (`docs/08`, ADR-0014) |

## Plan de reversión

- Volver a cablear `UnavailablePasswordResetLinkSender` en el trabajador: la recuperación vuelve a
  fallar de forma explícita y visible, sin tokens huérfanos.
- El módulo `notifications` sin consumidores no tiene efecto.
- Las migraciones son aditivas (tabla nueva y columna nueva); no se revierten en producción, se dejan
  sin uso.

## Dependencias

- Cambio 9 archivado (ejecutor de tareas en el trabajador).
- Coordinación con `password-recovery-endpoints` sobre el formato del enlace y la página de
  restablecimiento; no bloquea la fusión de este cambio.
- 14b depende de este cambio.

## Criterios de éxito

- [ ] Una tarea de emisión ejecutada en el trabajador entrega exactamente un correo a Mailpit con un
      enlace cuyo token restablece la contraseña.
- [ ] Un fallo SMTP anula el token no entregado, no consume el límite por hora, se audita y la tarea
      reintenta; agotados los 5 intentos, queda estacionada y visible.
- [ ] Ningún correo, token ni enlace aparece en registros, excepciones, `scheduled_tasks`, bitácora de
      envío ni auditoría.
- [ ] Solo `notifications.infrastructure` depende del cliente de correo; el módulo solo se carga en el
      trabajador.
- [ ] La tabla de envíos tiene seguridad de fila forzada, probada con el rol restringido.
- [ ] Fuera del perfil local no se arranca sin STARTTLS obligatorio.
- [ ] Especificaciones, ADR, `docs/05` y runbook actualizados.

## Pronóstico de tamaño y entrega

- **Estimación: 11 a 12 tareas** para este corte (14a), dentro del límite de 15. El cambio 14 completo
  se estima en 16 a 18 tareas, por eso se propone la división; 14b se estima en 6 a 7 tareas.
- Líneas efectivas estimadas: 1.400 a 1.800, entregadas en dos o tres pull requests encadenados por
  debajo de 800 líneas (`docs/15-flujo-de-trabajo-git.md` §3): módulo y adaptador SMTP; migraciones y
  entrega del enlace; documentación y especificaciones.

## Decisiones del propietario (2026-11-09)

El propietario aprobó la propuesta el 2026-11-09 y aceptó todas las recomendaciones. Los supuestos P1 a
P8 pasan a ser decisiones vinculantes:

1. **P1. División del cambio 14.** Se parte en **14a, `transactional-email-adapter`** (este cambio) y
   **14b, `account-security-notices`**, posterior a este. 14b es el dueño nombrado de los tres avisos al
   titular (restablecimiento completado con IP y momento, códigos de recuperación de MFA bajos y
   reutilización de refresco detectada). El seguimiento queda registrado en una nota fechada de
   `openspec/changes/foundations-plan/exploration.md` y en una nota fechada de
   `docs/09-roadmap-y-fases.md`, F0.
2. **P2. Módulo `notifications` mínimo, solo en el trabajador.** Con una regla de ArchUnit que impide a
   cualquier paquete distinto de `notifications.infrastructure` depender del cliente de correo
   (`docs/08` §8.4).
3. **P3. Compensación ante fallo del envío del enlace.** El token no entregado queda anulado, no cuenta
   para el límite de tres emisiones por hora, el fallo se audita y la tarea reintenta con su política.
   Requiere una migración sobre `identity_password_reset_token`.
4. **P4. Texto plano con catálogo `es-HN`.** El correo HTML, las plantillas versionadas en base de datos,
   los rebotes asíncronos de SES, la lista de supresión y la baja pasan a F6 como brechas registradas.
5. **P5. Mailpit sustituye a MailHog** en las pruebas y en local, fijado por digest; se actualiza
   `docs/05`. El servicio de `docker-compose` sigue siendo del cambio 11, que otra sesión planifica junto
   con el 12; este cambio no edita los archivos de esos cambios.
6. **P6 y P7. Enlace.** Se construye con `PASSWORD_RESET_LINK_BASE_URL`, solo `https` fuera del perfil
   local, y el token viaja en el fragmento de la URL (`#`). Ruta acordada con
   `password-recovery-endpoints`, planificado en paralelo: la página de restablecimiento envía el token
   en el cuerpo de `POST /api/v1/auth/password-resets`. Los rebotes, la supresión y la baja quedan en F6
   (P6).
7. **P8. Un remitente por despliegue** (`MAIL_FROM`).

**Corrección editorial añadida.** El ejemplo de despacho de `docs/08` §8.4 está escrito en TypeScript,
anterior a ADR-0013; se corrige con una nota fechada que lo expresa en Java, como tarea documental de
este cambio.

### Decisiones posteriores al diseño (2026-11-09)

8. **D1. Rol de base de datos propio del trabajador: brecha registrada.** Hoy `confia-worker` se conecta
   como `confia_admin_app`. Este cambio no lo corrige; queda anotado como pendiente con dueño en el
   cambio 11 (`containerization-and-cicd-pipeline`).
9. **D2. Formato del fragmento del enlace (`#token=<valor>`): pendiente de confirmar.** Se confirma con
   `password-recovery-endpoints` antes de la tarea 10 de `tasks.md`.
