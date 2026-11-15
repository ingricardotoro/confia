# ADR-0029: Correo transaccional en el módulo `notifications`, en texto plano, probado con Mailpit y con compensación del enlace de recuperación

- **Estado:** Propuesto
- **Fecha:** 2026-11-09
- **Decisores:** Propietario del producto y arquitecto
- **Contexto técnico:** módulo nuevo `com.confia.notifications` de `apps/api/app`, proceso `confia-worker`,
  módulo `identity` (emisión del token de recuperación), migraciones `V16` y `V17`, pruebas con Testcontainers.
  Concreta ADR-0014 (correo por SMTP) y ADR-0016 (trabajos en segundo plano) sin reemplazarlos.

## Contexto y problema

El token de recuperación de contraseña se emite en el trabajador, pero no se entrega: el único adaptador del
puerto de envío falla de forma explícita (decisión P4 de `background-jobs-with-db-scheduler`). ADR-0014 fija el
transporte (SMTP, SES en producción) y exige probar la compatibilidad contra un servidor de correo de prueba, pero
no decide dónde vive el envío, cómo se componen los mensajes, con qué servidor se prueba ni qué ocurre con un
token emitido cuyo correo no salió. `docs/08` §8.4 exige además que ningún módulo distinto de `notifications`
dependa de un cliente de correo. Sin decidirlo, cada consumidor futuro (avisos de seguridad, recibos, cobranza)
añadiría su propio cliente, y un fallo SMTP dejaría tokens emitidos sin entregar que consumen el límite por hora
hasta que la tarea termina «completada» sin haber enviado nada.

## Factores de decisión

1. Una sola puerta de envío, verificable en integración continua (`docs/08` §8.4).
2. El proceso expuesto a internet no carga ningún cliente de correo (ADR-0003).
3. Ningún fallo silencioso: un correo no enviado se ve, se audita y se reintenta.
4. El token en claro solo existe dentro del correo (`docs/03` §4.7).
5. Superficie mínima para un solo desarrollador en F0, extensible en F6 sin mover código.
6. Servidor de prueba mantenido, igual en pruebas y en local.

## Opciones consideradas

### Opción A: módulo `notifications` mínimo, solo en el trabajador

Caso de uso público con plantillas cerradas, transporte SMTP en `infrastructure`, bitácora de envío con hash del
destinatario, regla de ArchUnit de confinamiento. Ventajas: cumple §8.4 desde el primer consumidor, F6 lo
extiende en el mismo lugar. Desventajas: un módulo más de Spring Modulith con dos interfaces nombradas.

### Opción B: paquete técnico `shared/mail`

Ventajas: menos ceremonia. Desventajas: contradice §8.4, un paquete técnico compartido sin dueño de negocio y una
migración obligada en F6.

### Opción C: adaptador SMTP dentro de `identity.infrastructure`

Ventajas: el cambio más pequeño. Desventajas: acopla identidad a un cliente de correo y cada módulo futuro
repetiría el cliente y sus controles de TLS.

Para las plantillas se consideraron texto plano con catálogo, Thymeleaf en modo texto y HTML, y plantillas
versionadas en base de datos. Para el servidor de prueba, Mailpit en Testcontainers, GreenMail en la JVM y el
MailHog que fijaba `docs/05`, sin publicaciones desde 2020. Para el fallo del envío del enlace, enviar sin
compensar, compensar anulando el token, enviar dentro de la transacción y guardar el token cifrado para una
tarea de entrega.

## Decisión

Se elige la **opción A**, con estas concreciones:

1. **Ubicación.** `com.confia.notifications`, cargado solo en `WorkerApplication`, sin paquete `web` (ADR-0020),
   con las interfaces nombradas `api` y `wiring` (ADR-0022). Solo `notifications.infrastructure` depende de
   `jakarta.mail`, `org.eclipse.angus` u `org.springframework.mail`. Sin el iniciador de correo de Spring Boot:
   el emisor se construye a mano.
2. **Plantillas.** Texto plano UTF-8 desde `i18n/notifications.properties` (`es-HN`), con clave y versión
   registradas en cada envío. HTML y plantillas en base de datos son de F6.
3. **Transporte.** STARTTLS obligatorio y verificación del nombre del servidor fuera del perfil local, tiempos de
   5, 10 y 10 segundos, configuración solo por variables de entorno y un remitente por despliegue (`MAIL_FROM`).
4. **Bitácora.** `notifications_email_delivery`, solo inserción, con HMAC-SHA-256 del destinatario y sin dirección,
   asunto ni cuerpo.
5. **Servidor de prueba.** Mailpit, fijado por digest, en Testcontainers y en local; sustituye a MailHog.
6. **Compensación del enlace.** Si el envío falla, una segunda transacción anula el token no entregado, que deja de
   contar para el límite de tres por hora, audita `identity.password_reset.delivery_failed` y relanza para que la
   tarea reintente. Enviar dentro de la transacción viola ADR-0016 regla 7; guardar el token viola `docs/03` §4.7.

## Consecuencias

**Positivas:** una sola puerta de envío comprobada por la construcción; la recuperación de contraseña funciona de
punta a punta; ningún token entregable queda huérfano; el envío es observable sin datos personales.

**Negativas y costos aceptados:** dos migraciones; una dependencia de transporte; la entrega es «al menos una
vez» (un correo duplicado tras una respuesta SMTP perdida lleva un enlace ya anulado); si la transacción de
compensación falla, queda un token abierto y sin entregar hasta su vencimiento; rotar la llave del hash rompe la
correlación histórica de la bitácora.

**Riesgos y mitigaciones:** una excepción de la biblioteca con direcciones se traduce a una clase cerrada sin
causa, con prueba de centinelas; SES en modo de pruebas se mitiga con el runbook de entrega de correo; rebotes,
supresión y baja quedan como brecha con destino F6.

## Cumplimiento y verificación

- `MailClientConfinementTest` (ArchUnit) y la aserción de `StarterAutoConfigurationExcludedTest` sobre la
  autoconfiguración de correo.
- `ProcessBeanIsolationTest`: `com.confia.notifications` solo en la lista del trabajador.
- `TemplateCatalogCoverageTest`, `SmtpEmailTransportIT` contra Mailpit (incluidos STARTTLS y nombre de servidor),
  `NotificationsStartupTest`.
- `V16SchemaDiffIT`, `V17SchemaDiffIT`, `NotificationsRowSecurityIT`, `RolePrivilegeMatrixIT`.
- `PasswordResetLinkDeliveryIT`, `PasswordResetDeliveryFailureIT` y `PasswordResetDeliveryRedactionIT`.

## Referencias

- ADR-0003, ADR-0014, ADR-0016, ADR-0020, ADR-0022, ADR-0024.
- `docs/03-seguridad.md` §4.7; `docs/07-observabilidad-y-operaciones.md` §9; `docs/08-datos-privacidad-y-retencion.md`
  §8.4.
- `openspec/changes/transactional-email-adapter/` (propuesta, especificaciones y diseño).
