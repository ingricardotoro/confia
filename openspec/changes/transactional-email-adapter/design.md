# Diseño: adaptador de correo transaccional (cambio 14a de F0)

- **Cambio:** `transactional-email-adapter` (F0, cambio 14a; el 14b es `account-security-notices`)
- **Fase:** diseñar
- **Fecha:** 2026-11-09
- **Estado:** pendiente de aprobación del propietario
- **Entradas:** `proposal.md` (aprobada el 2026-11-09; P1 a P8 vinculantes y no se reabren),
  `exploration.md` (D1 a D11), los deltas `specs/notifications/spec.md` (`NT01` a `NT22`),
  `specs/identity/spec.md` (`I320` a `I332`) y `specs/build-integrity/spec.md` (`BI150` a `BI161`), ADR-0014,
  ADR-0016, ADR-0020, ADR-0022, ADR-0024, `docs/03` §4.7, `docs/05`, `docs/07` §3 y §9, `docs/08` §8.4 y el código
  de `origin/main` en `af20884`.
- **Modo de TDD:** estricto (`openspec/config.yaml`). Ejecutor: `./mvnw verify` en `apps/api`, con JDK 25 y
  Docker.

## 1. Enfoque técnico

Un módulo de negocio nuevo, `com.confia.notifications`, con `domain`, `application` e `infrastructure` (sin `web`,
ADR-0020), registrado solo en `WorkerApplication`. Expone una interfaz nombrada `api` con el caso de uso de envío
y sus tipos, y otra `infrastructure.wiring` con la configuración del trabajador. El transporte es
`JavaMailSenderImpl` de `spring-context-support` sobre Angus Mail, construido a mano desde propiedades propias:
sin el iniciador ni la autoconfiguración de correo de Spring Boot, para que ningún otro proceso pueda recibir un
emisor por accidente.

`identity` cambia en tres puntos: el puerto `PasswordResetLinkSender` recibe el contexto y la dirección; la
emisión compensa un envío fallido en una segunda transacción; y un adaptador nuevo,
`EmailPasswordResetLinkSender`, compone el enlace y llama a la API de `notifications`. Se retira
`UnavailablePasswordResetLinkSender`.

Dos migraciones aditivas: `V16` crea la bitácora de envío; `V17` añade el estado de anulación por entrega
fallida al token de recuperación.

## 2. Evidencia obtenida antes de esta fase

### 2.1 Hallazgos revalidados sobre `af20884`

| Hallazgo de la exploración | Estado en `af20884` | Consecuencia |
|---|---|---|
| «La última migración es `V9`» | **Falso ahora**: existen `V10` a `V14` (`V14__create_identity_staff_account_role.sql`) | Este cambio toma `V16` y `V17`. `V15` la reserva `rbac-permission-matrix-and-audit-integration`, parte 5b (`V15__drop_identity_staff_account_mfa_required.sql`), sin fusionar |
| `docker-compose` no existe | Sigue sin existir en la raíz; `docs/05` lo describe con `mailhog` | Solo se edita `docs/05`; el servicio es del cambio 11 (P5) |
| Cableado del trabajador | `IdentityWorkerConfiguration.passwordResetLinkSender()` devuelve `UnavailablePasswordResetLinkSender` | Se sustituye el bean en la tarea 10 |
| Ningún proceso tiene `MeterRegistry` | `WorkerHasNoMeterRegistryTest` lo exige en los tres procesos | Las métricas salen por un adaptador de registros, como `LogAuthorizationMetrics` (decisión 9) |
| `identity` no puede registrar (`IdentityScopeExclusionInventoryTest`) | Vigente | Los registros del envío viven en `notifications.infrastructure` |
| El trabajador usa `confia_admin_app` | `WorkerApplication`, Javadoc (decisión P7 del cambio 9) | Los privilegios de `V16` se conceden a ese rol; no existe un rol propio del trabajador (riesgo R6) |
| Escenarios en curso | `I278`-`I311` y `BI119`-`BI139` los ocupan deltas de rbac y de `admin-web-staff-sign-in` | Este cambio usa `I320`+ y `BI150`+ y deja `I312`-`I319` y `BI140`-`BI149` a `password-recovery-endpoints` |
| ADR | El último es ADR-0028 en todas las ramas locales | Este cambio redacta ADR-0029 (riesgo R7) |

### 2.2 Sondas que se ejecutan en la tarea 1, antes de cualquier código

| Sonda | Pregunta | Resultado esperado; plan B |
|---|---|---|
| S1 | Coordenadas de `spring-context-support` y `org.eclipse.angus:angus-mail` gestionadas por el BOM de Spring Boot 4.1, sin versión explícita | Ambas resuelven por el BOM; plan B: propiedad de versión en `apps/api/pom.xml` con comentario |
| S2 | Ninguna autoconfiguración de correo llega al classpath sin el iniciador | `dependency:tree` sin `spring-boot-mail`; plan B: exclusión explícita en los tres puntos de entrada, como db-scheduler |
| S3 | Mailpit admite STARTTLS obligatorio con certificado propio (`MP_SMTP_TLS_CERT`, `MP_SMTP_TLS_KEY`, `MP_SMTP_REQUIRE_STARTTLS`) y API REST `GET /api/v1/messages` | Confirmado con la imagen fijada; plan B: GreenMail en la JVM solo para los escenarios de TLS, Mailpit para el resto |
| S4 | Mailpit puede rechazar un destinatario con `550` (opción de rechazo por patrón o `MP_SMTP_REJECT...`) | Si no lo ofrece, el escenario `NT15` usa un servidor SMTP mínimo de prueba en la JVM que responde `550` |

## 3. Decisiones de arquitectura

### Decisión 1 — Módulo `notifications` mínimo, solo en el trabajador (P2)

- Paquete `com.confia.notifications`, módulo de Spring Modulith. `package-info.java` declara su propósito y sus dos
  interfaces nombradas.
- `notifications.application.api` (`@NamedInterface("api")`): `SendTransactionalEmail` (interfaz del caso de uso),
  `TransactionalEmail` (registro: plantilla, destinatario, parámetros), `TransactionalEmailTemplate` (enumeración
  cerrada con clave, versión y nombres de parámetros), `EmailRecipient` (dirección validada y con `toString()`
  redactado), `EmailDeliveryReceipt` y `EmailDeliveryFailedException` (solo `FailureClass`).
- `notifications.infrastructure.wiring` (`@NamedInterface("wiring")`): `NotificationsWorkerConfiguration`.
- Registro en dos ediciones del mismo PR: `@Import` en `WorkerApplication` y `com.confia.notifications` en la lista
  `WORKER` de `ProcessBeanPolicy` (skill de andamiaje, §4).
- **Descartadas:** paquete técnico `shared/mail` (contradice `docs/08` §8.4 y obliga a migrar en F6) y adaptador
  SMTP dentro de `identity.infrastructure` (cada módulo futuro repetiría el cliente).

### Decisión 2 — Dependencias sin el iniciador de correo

- `org.springframework:spring-context-support` y `org.eclipse.angus:angus-mail`, versiones del BOM (sondas S1 y
  S2). Ningún `spring-boot-starter-mail`.
- `JavaMailSenderImpl` se construye en `NotificationsWorkerConfiguration` desde `SmtpSettings`.
- **Descartada:** el iniciador con exclusión de `MailSenderAutoConfiguration` en los tres procesos. Funciona, pero
  deja un emisor a un `spring.mail.host` de distancia en el proceso del portal.

### Decisión 3 — Regla de confinamiento del cliente de correo (`BI150`)

`MailClientConfinementTest` (ArchUnit, mismo patrón que `DbSchedulerConfinementTest`): ninguna clase fuera de
`com.confia.notifications.infrastructure..` depende de `jakarta.mail..`, `org.eclipse.angus..` ni
`org.springframework.mail..`. Fixtures en `architecture/fixture`. `StarterAutoConfigurationExcludedTest` gana la
aserción de `BI151`.

### Decisión 4 — Caso de uso `DeliverTransactionalEmail`

Implementa `SendTransactionalEmail.send(SecurityContext context, TransactionalEmail email) → EmailDeliveryReceipt`.

1. Valida parámetros contra la plantilla (nombres exactos; ningún `\r`, `\n` ni carácter de control: `NT05`).
2. Compone con `TemplateCatalog` (asunto y cuerpo).
3. Calcula `RecipientHash` (dominio, `NT13`, `NT14`).
4. Llama a `EmailTransport.send(OutgoingEmail)` **fuera de toda transacción**.
5. Aceptado: en una transacción del `TransactionRunner` con el contexto recibido, inserta la fila `sent`;
   registra la métrica y la línea; devuelve el recibo. Si la inserción falla: evento operativo
   `notifications.email.delivery_log_failed` y devuelve el recibo igual (`NT17`).
6. Fallido (`EmailTransportException` con `FailureClass`): inserta la fila `failed` (si la inserción falla, se
   ignora con el mismo evento operativo), métrica y línea, y lanza `EmailDeliveryFailedException(failureClass)`
   sin causa encadenada (`NT15`, `NT18`).

**Descartada:** fila `pending` antes del envío y actualización después. Exige `UPDATE` sobre la bitácora, que
`docs/08` y la skill de auditoría quieren de solo inserción.

### Decisión 5 — Transporte SMTP y su configuración

- `SmtpSettings` (registro, `toString()` redactado, `NT21`) enlazado con `@ConfigurationProperties("confia.notifications.mail")`;
  `application.yml` mapea `host: ${SMTP_HOST:}`, `port: ${SMTP_PORT:587}`, `username: ${SMTP_USER:}`,
  `password: ${SMTP_PASSWORD:}`, `from: ${MAIL_FROM:}`, `recipient-hash-key: ${NOTIFICATIONS_RECIPIENT_HASH_KEY:}`.
- Propiedades de Angus fijas en código: `mail.smtp.starttls.enable=true`, `mail.smtp.starttls.required=true`,
  `mail.smtp.ssl.checkserveridentity=true`, `mail.smtp.connectiontimeout=5000`, `mail.smtp.timeout=10000`,
  `mail.smtp.writetimeout=10000`, `mail.smtp.auth` según haya usuario. En el perfil local, STARTTLS no es
  obligatorio. Ningún `mail.smtp.ssl.trust=*` en ningún perfil.
- `SmtpSettingsValidator` corre al construir el bean: falla el arranque nombrando la propiedad (`NT10`). `MAIL_FROM`
  se valida con `InternetAddress(…, true)`.
- `SmtpEmailTransport` traduce: `MailConnectException` y `UnknownHostException` → `unavailable`;
  `SocketTimeoutException` → `timeout`; `SSLException` o ausencia de STARTTLS → `tls`;
  `AuthenticationFailedException` → `authentication`; `SMTPAddressFailedException`, `SMTPSendFailedException` →
  `rejected`; el resto → `unknown`. La excepción propia no encadena la causa ni copia el mensaje.
- El identificador del proveedor sale de la última respuesta `250` cuando la trae (SES: `250 Ok <id>`), recortado a
  `[A-Za-z0-9._@<>-]{1,255}`; si no, nulo.

### Decisión 6 — Plantillas de texto plano con catálogo `es-HN` (P4)

- `i18n/notifications.properties`, UTF-8, claves `email.<clave>.v<versión>.subject` y `.body`, con parámetros
  nombrados `{link}` y `{validityMinutes}` sustituidos por el renderizador propio (sin `MessageFormat`, para no
  depender de las reglas de apóstrofo).
- Primera plantilla: `PASSWORD_RESET_LINK("password-reset-link", 1, Set.of("link", "validityMinutes"))`.
- `TemplateCatalogCoverageTest` (`NT04`): toda plantilla tiene ambas claves y toda clave tiene plantilla.
- **Descartadas:** Thymeleaf en modo texto y HTML (una dependencia y una superficie más, sin necesidad en F0) y
  plantillas en base de datos (modelo de F6, `docs/02`).

### Decisión 7 — Bitácora de envío `notifications_email_delivery` (`V16`)

```sql
CREATE TABLE notifications_email_delivery (
    institution_id      UUID        NOT NULL,
    id                  UUID        NOT NULL,
    request_id          UUID        NOT NULL,
    attempted_at        TIMESTAMPTZ NOT NULL,
    channel             TEXT        NOT NULL,  -- CHECK = 'email'
    purpose             TEXT        NOT NULL,  -- CHECK = 'transactional'
    template_key        TEXT        NOT NULL,  -- CHECK ~ '^[a-z][a-z0-9-]{0,62}$'
    template_version    INTEGER     NOT NULL,  -- CHECK > 0
    recipient_hash      TEXT        NOT NULL,  -- CHECK ~ '^[0-9a-f]{64}$'
    status              TEXT        NOT NULL,  -- CHECK IN ('sent', 'failed')
    provider_message_id TEXT,
    failure_class       TEXT,                  -- CHECK IN (lista cerrada)
    CONSTRAINT notifications_email_delivery_pk PRIMARY KEY (institution_id, id),
    CONSTRAINT notifications_email_delivery_outcome_chk CHECK (
        (status = 'sent'   AND failure_class IS NULL) OR
        (status = 'failed' AND failure_class IS NOT NULL AND provider_message_id IS NULL))
);
CREATE INDEX notifications_email_delivery_recipient_idx
    ON notifications_email_delivery (institution_id, recipient_hash, attempted_at);
```

RLS habilitada y forzada con la política de aislamiento habitual; `REVOKE ALL FROM PUBLIC`; `SELECT, INSERT` a
`confia_admin_app`; `SELECT` a `confia_readonly`; nada a `confia_portal_app`. jOOQ se regenera en la misma
construcción. Pruebas: `V16SchemaDiffIT`, `MultiTenantSchemaIT`, `RolePrivilegeMatrixIT`,
`NotificationsRowSecurityIT`, `TableOwnershipByModuleTest`.

### Decisión 8 — Hash del destinatario

`RecipientHash.of(byte[] key, String address)` en `notifications.domain`: HMAC-SHA-256 de la dirección recortada y
en minúsculas (`Locale.ROOT`), hexadecimal en minúsculas, con la llave de `NOTIFICATIONS_RECIPIENT_HASH_KEY`
(Base64, al menos 32 bytes). Mismo patrón que `IdentifierFingerprint`, sin compartir la llave: una llave por
propósito. Rotarla rompe la correlación histórica por hash; se acepta y se documenta en el runbook.

### Decisión 9 — Observabilidad

- Puerto `EmailDeliveryMetrics` en `notifications.application`; adaptador provisional `LogEmailDeliveryMetrics` en
  `notifications.infrastructure` (el módulo puede registrar; `identity` no). Una línea por intento,
  `notifications.email.attempt`, con `institution`, `template`, `version`, `outcome`, `errorClass` y
  `recipientHash`, y el nombre de la métrica de `docs/07` (`confia_notifications_sent_total` o
  `confia_notifications_failed_total`) con `channel=email`.
- `NotificationsLogRedactionIT` (`NT20`) captura los registros con centinelas.
- Alertas `TaskFailed` (ya existente, cubre la tarea estacionada) y `NotificationFailureRateHigh` (de `docs/07`; se
  materializa cuando llegue el registro de métricas, nota fechada en `docs/07`).

### Decisión 10 — Puerto `PasswordResetLinkSender` ampliado

```java
void send(SecurityContext context, StaffAccountId accountId, LoginIdentifier recipient,
        PlainPasswordResetToken token);
```

El contexto es el `system` de la tarea; la dirección es el `LoginIdentifier` de la cuenta bloqueada en la
transacción de emisión (`Issuance` gana `tokenId` y `recipient`). `ensureAvailable()` conserva su versión por
defecto y el adaptador nuevo no la sobrescribe. El fallo del puerto es `PasswordResetLinkDeliveryException`
(`identity.application`), con un código cerrado; el adaptador traduce a él la excepción de `notifications`.

**Descartada:** releer la dirección en el adaptador con otra transacción. Añade una lectura y una ventana en la
que la cuenta puede cambiar entre la emisión y el envío.

### Decisión 11 — Compensación en `IssuePasswordResetToken` (P3)

```text
ensureAvailable()
T1: lockById → countIssuedSince (sin anulados) → supersedeOpen → insert → audit issued → COMMIT
send(...) fuera de T1
  éxito → devuelve ISSUED
  fallo → T2: lockById → markDeliveryFailed(tokenId, now) (solo si sigue abierto)
              → si marcó: audit identity.password_reset.delivery_failed (error, {"failureClass": …})
          → COMMIT → relanza el fallo original
```

- T2 bloquea la cuenta primero, como toda escritura sobre sus tokens (decisión 2 de `password-recovery-token`).
- Si T2 falla, se relanza el fallo original con el de T2 como suprimido; el token queda abierto y sin entregar,
  cuenta para el límite y vence a los 30 minutos; el reintento lo supera. Es el residuo aceptado (riesgo R2).
- Cualquier `RuntimeException` del envío compensa: nunca se sabe si llegó. Un envío aceptado no lanza (decisión 4,
  paso 5), así que no anula un enlace entregado.
- El reintento lo hace db-scheduler con la política vigente de la tarea (5 intentos, 30 s a 5 min); los 7,5
  minutos de espera acumulada caben en la vigencia de 30 minutos de cada token.
- **Descartadas:** enviar dentro de T1 (viola ADR-0016 regla 7) y guardar el token cifrado para una tarea de
  entrega (viola `docs/03` §4.7). Superar los tokens anteriores solo tras el envío exitoso: exige una tercera
  transacción y dos tokens abiertos a la vez, contra el índice único; la solicitud nueva invalida los anteriores
  en todos los casos.

### Decisión 12 — Estado de anulación en el token (`V17`)

```sql
ALTER TABLE identity_password_reset_token ADD COLUMN delivery_failed_at TIMESTAMPTZ;
ALTER TABLE identity_password_reset_token DROP CONSTRAINT identity_password_reset_token_final_chk;
ALTER TABLE identity_password_reset_token ADD CONSTRAINT identity_password_reset_token_final_chk
    CHECK (num_nonnulls(consumed_at, superseded_at, delivery_failed_at) <= 1);
DROP INDEX identity_password_reset_token_one_open_per_account;
CREATE UNIQUE INDEX identity_password_reset_token_one_open_per_account
    ON identity_password_reset_token (institution_id, account_id)
    WHERE consumed_at IS NULL AND superseded_at IS NULL AND delivery_failed_at IS NULL;
```

`JooqPasswordResetTokenRepository`: `countIssuedSince`, `supersedeOpen`, `consume` y la búsqueda por hash añaden
`delivery_failed_at IS NULL`; método nuevo `markDeliveryFailed(InstitutionId, UUID, Instant) → boolean`. Sin
cambio de privilegios (`UPDATE` ya concedido). La purga de `V11` no cambia: borra por `expires_at`. Pruebas:
`V17SchemaDiffIT`, `JooqPasswordResetTokenRepositoryIT` ampliada.

**Descartada:** reutilizar `superseded_at`. Mezcla dos causas en un estado y obliga a una segunda columna para
excluirlo del límite.

### Decisión 13 — Adaptador `EmailPasswordResetLinkSender` y enlace (P7)

- `identity.infrastructure.EmailPasswordResetLinkSender` implementa el puerto con `SendTransactionalEmail`;
  compone `baseUrl + "#token=" + token.value()` y la vigencia en minutos de `PasswordResetTokenPolicy`.
- `PasswordResetLinkSettings` (`PASSWORD_RESET_LINK_BASE_URL`), validado al arrancar (`I323`): absoluta, con host,
  sin consulta ni fragmento, `https` fuera del perfil local.
- `IdentityWorkerConfiguration` declara el bean con su tipo concreto; se borra `UnavailablePasswordResetLinkSender`
  y su prueba. `IdentityScopeExclusionInventoryTest` pasa a exigir exactamente un adaptador, este.
- La página que lee el fragmento y llama a `POST /api/v1/auth/password-resets` no es de este cambio.

### Decisión 14 — Auditoría

- `identity.password_reset.delivery_failed`: actor `system`, etiqueta `password-reset-issuance`, entidad
  `identity.password_reset_token`, resultado `error`, `after_value` con solo `failureClass`, construido con
  `AuditJson`; en T2, la misma transacción que la anulación.
- El envío aceptado no se audita: su evidencia es la bitácora de envío (`docs/08`). La emisión ya se audita.
- Nunca dirección, enlace ni token en un asiento (`I331`).

## 4. Flujo de datos

```text
admin: RequestPasswordReset → programa identity.password-reset-issuance (solo ACC-7, en meta INS-1)
worker: PasswordResetIssuanceJobHandler
  → IssuePasswordResetToken.execute(system@INS-1, ACC-7)
      T1 (bloqueo de ACC-7): emite token, audita issued, COMMIT
      EmailPasswordResetLinkSender.send(ctx, ACC-7, ana@…, token)
        → SendTransactionalEmail.send(ctx, PASSWORD_RESET_LINK{link, 30}, ana@…)
            compone → hash → SMTP (STARTTLS, 5/10/10 s)
            aceptado → T(notifications): fila sent → métrica/línea → recibo
            fallido  → T(notifications): fila failed → métrica/línea → EmailDeliveryFailedException(class)
        ← fallo traducido a PasswordResetLinkDeliveryException(code)
      fallo → T2 (bloqueo de ACC-7): anula el token, audita delivery_failed, COMMIT → relanza
  → db-scheduler: reintento con espera (≤5) o estacionada en 9999-01-01
```

## 5. Archivos

| Archivo (bajo `apps/api/`) | Acción | Tarea |
|---|---|---|
| `pom.xml`, `app/pom.xml` | Dependencias (S1, S2) | 1 |
| `app/src/test/java/com/confia/architecture/MailClientConfinementTest.java` y fixtures | Crear | 1 |
| `app/src/test/java/com/confia/bootstrap/StarterAutoConfigurationExcludedTest.java` | Ampliar (`BI151`) | 1 |
| `app/src/main/java/com/confia/notifications/package-info.java` | Crear | 2 |
| `notifications/domain/RecipientHash.java`, `RenderedEmail.java`, `FailureClass.java` | Crear | 2 |
| `notifications/application/api/*` | Crear | 2 |
| `app/src/main/resources/db/migration/V16__create_notifications_email_delivery.sql` | Crear | 3 |
| `notifications/application/EmailDeliveryLog.java`, `infrastructure/JooqEmailDeliveryLog.java` | Crear | 3 |
| `test/.../schema/V16SchemaDiffIT.java`, `NotificationsRowSecurityIT.java`, `RolePrivilegeMatrixIT.java`, `MultiTenantSchemaIT.java` | Crear o ampliar | 3 |
| `app/src/main/resources/i18n/notifications.properties`, `notifications/application/TemplateCatalog.java`, `infrastructure/PropertiesTemplateCatalog.java` | Crear | 4 |
| `notifications/infrastructure/SmtpSettings.java`, `SmtpSettingsValidator.java`, `SmtpEmailTransport.java` | Crear | 5 |
| `test/.../support/MailpitContainer.java` | Crear (imagen fijada por digest) | 5 |
| `notifications/application/DeliverTransactionalEmail.java`, `EmailTransport.java`, `EmailDeliveryMetrics.java` | Crear | 6 |
| `notifications/infrastructure/LogEmailDeliveryMetrics.java`, `infrastructure/wiring/NotificationsWorkerConfiguration.java` | Crear | 7 |
| `bootstrap/worker/WorkerApplication.java`, `application.yml`, `test/.../bootstrap/ProcessBeanPolicy.java`, `TestProcessArguments.java` | Editar | 7 |
| `app/src/main/resources/db/migration/V17__add_identity_password_reset_token_delivery_failed.sql` | Crear | 8 |
| `identity/application/PasswordResetTokenRepository.java`, `identity/infrastructure/JooqPasswordResetTokenRepository.java` | Editar | 8 |
| `identity/application/IssuePasswordResetToken.java`, `PasswordResetLinkSender.java`, `PasswordResetLinkDeliveryException.java` | Editar o crear | 9 |
| `identity/infrastructure/EmailPasswordResetLinkSender.java`, `PasswordResetLinkSettings.java`, `wiring/IdentityWorkerConfiguration.java` | Crear o editar | 10 |
| `identity/infrastructure/UnavailablePasswordResetLinkSender.java` (y sus menciones en `WorkerProductionWiringTest`) | Borrar | 10 |
| `test/.../identity/IdentityScopeExclusionInventoryTest.java`, `ConsumeRecoveryCodeIT.java` | Editar | 10 |
| `test/.../identity/PasswordResetLinkDeliveryIT.java`, `PasswordResetDeliveryFailureIT.java`, `PasswordResetDeliveryRedactionIT.java` | Crear | 11 |
| `docs/03`, `docs/05`, `docs/07`, `docs/08`, `docs/runbooks/entrega-de-correo-transaccional.md`, `docs/runbooks/README.md`, `docs/adr/ADR-0029-*.md`, `docs/adr/README.md` | Editar o crear | 12 |

## 6. Estrategia de pruebas

| Nivel | Qué | Pruebas |
|---|---|---|
| Unidad (95 % y PIT ≥ 80 en `notifications.domain`) | Hash, validación de parámetros, composición | `RecipientHashTest` (vector conocido de RFC 4231 para el HMAC, propiedad jqwik de normalización), `TransactionalEmailTest`, `TemplateRendererTest` |
| Unidad con dobles | Caso de uso y compensación | `DeliverTransactionalEmailTest` (aceptado, fallido, bitácora caída tras aceptar y tras fallar), `IssuePasswordResetTokenTest` (compensa, no compensa si ya no está abierto, relanza, T2 falla con suprimido) |
| Arquitectura | Confinamiento, procesos, módulos | `MailClientConfinementTest`, `ProcessBeanIsolationTest`, `SpringModulithVerificationTest`, `TableOwnershipByModuleTest`, `TemplateCatalogCoverageTest` |
| Integración con PostgreSQL | Esquema, RLS, privilegios, repositorios | `V16SchemaDiffIT`, `V17SchemaDiffIT`, `NotificationsRowSecurityIT`, `RolePrivilegeMatrixIT`, `JooqEmailDeliveryLogIT`, `JooqPasswordResetTokenRepositoryIT` |
| Integración con Mailpit (Testcontainers, imagen por digest) | Transporte real | `SmtpEmailTransportIT`: texto plano, STARTTLS con certificado generado en la prueba (válido y con otro nombre), sin STARTTLS, servidor detenido, servidor mudo (`ServerSocket` que no responde), `550`, autenticación inválida |
| Extremo a extremo en el trabajador | Tarea → correo → restablecimiento | `PasswordResetLinkDeliveryIT` (`I320`-`I322`), `PasswordResetDeliveryFailureIT` (`I324`-`I330`, con `MutableClock` y el reloj de db-scheduler, sin esperas reales), `PasswordResetDeliveryRedactionIT` (`I331`, `I332`) |
| Arranque | Configuración insegura | `NotificationsStartupTest` (`NT10`), `PasswordResetLinkSettingsStartupTest` (`I323`) |

El contenedor de Mailpit se comparte por JVM, como el de PostgreSQL; cada prueba borra sus mensajes con
`DELETE /api/v1/messages`. Las pruebas de TLS generan la CA y el certificado en ejecución (Bouncy Castle ya es
dependencia de prueba); ningún material criptográfico se versiona.

## 7. Migraciones y numeración

- `V16__create_notifications_email_delivery.sql` y `V17__add_identity_password_reset_token_delivery_failed.sql`
  sobre `af20884`.
- **Riesgo de colisión:** `V15` la toma la parte 5b de rbac (en curso en `C:\dev\confia`). Si rbac añade otra
  migración antes de que este cambio fusione (por ejemplo la parte 8b), se renumera al rebasar: renombrar el
  archivo, su `V<n>SchemaDiffIT` y las menciones de los escenarios. Nunca se edita una migración fusionada.
- Ambas son aditivas; sin datos reales en ningún entorno.

## 8. Riesgos

| # | Riesgo | Mitigación |
|---|---|---|
| R1 | Una excepción de Angus Mail con direcciones llega a registros o a `scheduled_tasks` | Traducción sin causa (decisión 5), saneamiento de `JobExecutionFailedException`, `I331`/`I332` con control negativo |
| R2 | T2 falla y deja un token abierto sin entregar | Cuenta para el límite y vence a los 30 minutos; el reintento lo supera; residuo declarado |
| R3 | Correo duplicado: el servidor aceptó pero la respuesta no llegó | El reintento anula el token del primer correo y entrega otro; el primer enlace queda inservible. «Al menos una vez» declarado |
| R4 | SMTP prolongadamente caído agota los 5 intentos | Tarea estacionada y alerta `TaskFailed`; la persona repite la solicitud; runbook |
| R5 | SES en modo de pruebas no entrega a destinatarios no verificados | Runbook: solicitud anticipada de acceso de producción (ADR-0014) |
| R6 | El trabajador comparte `confia_admin_app`, que también puede insertar en la bitácora desde el proceso administrativo | Ninguna clase del proceso administrativo tiene el repositorio (`ProcessBeanIsolationTest`); un rol propio del trabajador es pregunta abierta para el cambio 11 |
| R7 | Colisión de ADR-0029 o de números de escenario con cambios paralelos | Tramos reservados (`I312`-`I319`, `BI140`-`BI149`); el ADR se renumera al rebasar si otro lo toma antes |
| R8 | Conflicto de fusión con `password-recovery-endpoints` y rbac 5b en `identity` (`StaffAccount`, `IssuePasswordResetToken`, `identity/spec.md`) | Frontera escrita en el delta; este cambio no toca `mfaRequired`; rebasar sobre rbac 5b si fusiona antes |
| R9 | `docs/05` también lo edita la sesión de los cambios 11 y 12 | Este cambio solo toca la fila de variables, el bloque de entorno local y la definición de `mailhog`, con nota fechada |
| R10 | Transferencia internacional a SES sin base legal | Ningún dato real antes de la confirmación legal (`docs/08`, ADR-0014) |

## 9. Matriz de amenazas (resumen)

| Amenaza | Control | Prueba |
|---|---|---|
| Robo del token por la URL (registros de servidor, `Referer`) | Token en el fragmento; la página aplica `no-referrer` (fuera de alcance) | `I322` |
| Degradación a texto plano en el transporte | STARTTLS obligatorio y verificación del nombre | `NT06`, `NT07` |
| Inyección de cabeceras | Parámetros sin caracteres de control; asunto solo del catálogo | `NT05` |
| Correlación de destinatarios por la bitácora | HMAC con llave propia | `NT14` |
| Fuga de dirección o token por excepciones | Clase cerrada sin causa | `NT15`, `I331` |
| Envío desde un proceso expuesto | Módulo solo en el trabajador, regla de ArchUnit | `NT01`, `BI150`, `BI152` |

## 10. Plan de entrega

Siete PR encadenados, `stacked-to-main`, cada uno por debajo de 800 líneas efectivas (`docs/15` §3); detalle en
`tasks.md`.

## 11. Preguntas abiertas

1. **Rol de base de datos propio del trabajador.** Hoy usa `confia_admin_app` (R6). Recomendación: registrarlo como
   brecha con destino el cambio 11, sin bloquear este cambio.
2. **Forma exacta del fragmento** (`#token=<valor>`). Recomendación: confirmarla con `password-recovery-endpoints`
   y con el cambio de frontend que construya la página, antes de la tarea 10.
