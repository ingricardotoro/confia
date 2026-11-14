# Tareas: adaptador de correo transaccional (cambio 14a de F0)

- **Cambio:** `transactional-email-adapter` (F0, cambio 14a; 14b es `account-security-notices`)
- **Fase:** tareas
- **Fecha:** 2026-11-09
- **Estado:** pendiente de aprobación del propietario
- **Entradas aprobadas:** `proposal.md` (aprobada el 2026-11-09, P1 a P8 vinculantes), `design.md` (decisiones 1 a
  14) y los deltas `specs/notifications/spec.md` (`NT01`-`NT22`), `specs/identity/spec.md` (`I320`-`I332`) y
  `specs/build-integrity/spec.md` (`BI150`-`BI161`), más los escenarios sin número que cada tarea nombra por título.
- **Modo de TDD:** estricto. Ejecutor: `./mvnw verify` en `apps/api`, con JDK 25 y Docker en ejecución.
- **Línea base de la cadena:** `origin/main` en `af20884`.

## Review Workload Forecast

| Field | Value |
|-------|-------|
| Estimated changed lines (additions + deletions, sin `openspec/` ni código generado) | Nominal 1 600 a 1 900; realista 3 400 a 4 200 con el factor 2,2 del historial de F0 |
| 400-line budget risk | High frente a 400; medio por PR frente al presupuesto del proyecto de 800 |
| Chained PRs recommended | Yes |
| Suggested split | 7 PR encadenados (tabla siguiente), cada uno con su costura nombrada |
| Delivery strategy | Cadena apilada de 800 líneas efectivas por PR (`docs/15` §3), práctica fijada por el propietario |
| Chain strategy | `stacked-to-main` |

Decision needed before apply: No
Chained PRs recommended: Yes
Chain strategy: stacked-to-main
400-line budget risk: High

**Cuenta de tareas.** 13 tareas, por debajo del límite de 15. No hace falta partir el cambio.

### PR y costuras (líneas efectivas realistas, sin `openspec/`)

| PR | Tareas | Realista | Costura si supera 800 |
|---|---|---|---|
| **PR 1** `01-notifications-module-and-mail-rule` | 1, 2 | 550 a 700 | 1a dependencias y regla (1) / 1b dominio y API (2) |
| **PR 2** `02-email-delivery-log-table` | 3 | 450 a 600 | Ninguna; si pasa, migración y pruebas de esquema / adaptador jOOQ |
| **PR 3** `03-templates-and-smtp-transport` | 4, 5 | 650 a 800 | 3a catálogo (4) / 3b transporte y Mailpit (5) |
| **PR 4** `04-send-use-case-and-worker-wiring` | 6, 7 | 550 a 750 | 4a caso de uso (6) / 4b cableado y métricas (7) |
| **PR 5** `05-reset-token-delivery-failure` | 8, 9 | 600 a 800 | 5a `V17` y repositorio (8) / 5b compensación (9) |
| **PR 6** `06-password-reset-link-email` | 10, 11 | 650 a 800 | 6a adaptador y cableado (10) / 6b pruebas de extremo a extremo (11) |
| **PR 7** `07-docs-runbook-and-closing` | 12, 13 | 350 a 500 | Ninguna; solo documentación e inventarios |

**Orden duro.**

1. La tarea 1 (sondas S1 a S4 y regla de confinamiento en rojo) antes de cualquier código de `notifications`.
2. `V16` (tarea 3) antes que `V17` (tarea 8). `V1` a `V15` no se editan; si otro cambio fusiona `V16` o `V17` antes,
   se renumera al rebasar (diseño §7).
3. La tarea 9 (compensación) antes que la 10 (adaptador real): `main` nunca tiene un emisor real sin compensación.
4. La tarea 10 retira `UnavailablePasswordResetLinkSender` en el mismo PR que cablea el adaptador real.

### Reglas comunes

- **Comandos.** Desde `apps/api`. Forma unitaria: `./mvnw -pl app -am verify -DskipITs
  -Dsurefire.failIfNoSpecifiedTests=false -Dtest='A,B'`. Forma de integración: `./mvnw -pl app -am verify
  -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false -Dit.test='X,Y' -Dfailsafe.failIfNoSpecifiedTests=false`.
  Un `-Dtest` acotado puede informar `BUILD FAILURE` por JaCoCo aunque las pruebas pasen. **Cada PR cierra con
  `./mvnw verify -Pmutation-gate` completo en verde.**
- **Rojo registrado.** Cada RED corre contra el árbol sin el código de producción y se registra en
  `apply-progress.md` con la causa observada; luego GREEN y REFACTOR con la suite en verde. Un commit en rojo no
  entra al historial.
- **Control negativo.** Cada tarea con pruebas de seguridad nombra una ruptura temporal que debe poner en rojo una
  aserción de resultado; se revierte y se confirma con `cmp` y `git diff` (procedimiento del cambio 9).
- **Datos y secretos.** Ninguna dirección, token, enlace, contraseña SMTP ni llave en registros, excepciones,
  `task_data`, asientos ni bitácora. Los secretos de prueba se generan en ejecución.
- **Lista de permitidos.** Cada paquete nuevo del trabajador entra en `ProcessBeanPolicy` en el mismo PR que su
  primer bean, con el rojo de `ProcessBeanIsolationTest` registrado.
- **Medición.** `git diff --numstat main...HEAD` sin `openspec/` ni código generado, registrada antes de abrir el PR.
- **Commits.** Conventional Commits en inglés, sin atribución de herramientas de IA, pruebas y documentación con el
  comportamiento. Rama por PR: `change/transactional-email-adapter-NN-<nombre>`.
- **Idioma.** Código en inglés; documentación y notas fechadas en español neutro profesional.

## Fase 1: módulo y reglas

- [x] **1. Sondas, dependencias y regla de confinamiento del cliente de correo** (diseño, decisiones 2 y 3; `BI150`,
  `BI151`)
  - **RED.** `MailClientConfinementTest` con sus dos fixtures (`identity.infrastructure` con `InternetAddress`,
    `notifications.application` con `SimpleMailMessage`): falla el fixture que debe fallar y la regla compila sin
    la dependencia (registrar). `StarterAutoConfigurationExcludedTest` con la aserción de ausencia de la
    autoconfiguración de correo.
  - **GREEN.** Sondas S1 a S4 registradas en `apply-progress.md`; `spring-context-support` y `angus-mail` en
    `app/pom.xml` sin versión (o con propiedad si S1 falla); imagen de Mailpit elegida y su digest anotado.
  - **Verificación.** `./mvnw -pl app -am verify -DskipITs -Dsurefire.failIfNoSpecifiedTests=false
    -Dtest='MailClientConfinementTest,StarterAutoConfigurationExcludedTest'` y `./mvnw -pl app dependency:tree`
    sin `spring-boot-mail`.

- [x] **2. Dominio y API pública de `notifications`** (decisiones 1, 4 y 8; `NT02`, `NT05`, `NT14`)
  - **RED.** `DigestsTest` (vectores 6 y 7 de RFC 4231, llave menor de 32 bytes rechazada, distinto del SHA-256 sin llave),
    `RecipientHashTest` (normalización y forma de 64 hexadecimales), `RecipientHasherTest` y `RecipientHasherProperties`
    (HMAC de la dirección normalizada, jqwik, distinto del SHA-256 sin llave), `TransactionalEmailTest` (parámetros exactos de la plantilla, caracteres de
    control rechazados, `toString()` de `EmailRecipient` redactado).
  - **GREEN.** `Digests.hmacSha256` en `shared.security`; `package-info.java` del módulo, `notifications.domain`
    (`RecipientHash` solo como tipo valor y normalización, `RenderedEmail`, `FailureClass`), `RecipientHasher` en
    `notifications.application` y `notifications.application.api` con `@NamedInterface("api")`. Corrección del 2026-11-10:
    el HMAC no puede vivir en el dominio (BI11 a BI13 y BI38; diseño, decisión 8).
  - **Verificación.** `-Dtest='DigestsTest,RecipientHashTest,RecipientHasherTest,TransactionalEmailTest,SpringModulithVerificationTest,NoTechnicalLayerPackageNamesTest,SigningAndHashingConfinementTest'`
    y PIT sobre `notifications.domain` y `shared.security` (≥ 80) en el `verify -Pmutation-gate` del PR.

## Fase 2: bitácora de envío

- [x] **3. Migración `V16` y adaptador de la bitácora** (decisión 7; `NT11`-`NT13`, `BI154`-`BI158`)
  - **RED.** `V16SchemaDiffIT`, `NotificationsRowSecurityIT` (`BI154`, `BI155`), filas nuevas de
    `RolePrivilegeMatrixIT` (`BI157`, `BI158`), `MultiTenantSchemaIT` (`BI156`), `JooqEmailDeliveryLogIT` (inserta
    `sent` y `failed`, ninguna columna con datos personales).
  - **GREEN.** `V16__create_notifications_email_delivery.sql`, regeneración de jOOQ, puerto `EmailDeliveryLog` y
    `JooqEmailDeliveryLog`.
  - **Verificación.** `-Dit.test='V16SchemaDiffIT,NotificationsRowSecurityIT,RolePrivilegeMatrixIT,MultiTenantSchemaIT,JooqEmailDeliveryLogIT'`
    y `-Dtest='TableOwnershipByModuleTest,JooqConfinedToInfrastructureTest'`.

## Fase 3: plantillas y transporte

- [ ] **4. Catálogo `es-HN` y composición de texto plano** (decisión 6; `NT03` en su parte de composición, `NT04`)
  - **RED.** `TemplateCatalogCoverageTest` (real y fixture sin cuerpo), `TemplateRendererTest` (sustitución de
    `{link}` y `{validityMinutes}`, parámetro desconocido rechazado, texto con apóstrofos intacto).
  - **GREEN.** `i18n/notifications.properties` con la plantilla `password-reset-link` v1, puerto `TemplateCatalog`
    y `PropertiesTemplateCatalog`.
  - **Verificación.** `-Dtest='TemplateCatalogCoverageTest,TemplateRendererTest'`.

- [ ] **5. Transporte SMTP con STARTTLS, tiempos acotados y traducción de fallos** (decisión 5; `NT06`-`NT10`,
  `NT15`, `NT16`, `NT21`, `NT22`)
  - **RED.** `SmtpEmailTransportIT` contra `MailpitContainer` (texto plano aceptado, STARTTLS válido, certificado
    de otro nombre, servidor sin STARTTLS, servidor detenido, `ServerSocket` mudo con límite de 15 s, `550`,
    autenticación inválida); `SmtpSettingsTest` (`toString()` redactado); `NotificationsStartupTest` (cinco
    configuraciones inválidas). Control negativo: quitar `mail.smtp.ssl.checkserveridentity` pone en rojo `NT07`.
  - **GREEN.** `SmtpSettings`, `SmtpSettingsValidator`, `SmtpEmailTransport`, `MailpitContainer` fijado por digest,
    mapeo de variables en `application.yml`.
  - **Verificación.** `-Dit.test='SmtpEmailTransportIT,NotificationsStartupTest'` y `-Dtest='SmtpSettingsTest'`;
    `git grep -nE "SMTP_PASSWORD=.+|NOTIFICATIONS_RECIPIENT_HASH_KEY=.+"` sin resultados fuera de `docs/` de ejemplo
    vacío (`NT22`).

## Fase 4: caso de uso y trabajador

- [ ] **6. Caso de uso `DeliverTransactionalEmail`** (decisión 4; `NT11`, `NT12`, `NT17`, `NT18`)
  - **RED.** `DeliverTransactionalEmailTest` con dobles (aceptado, fallido, bitácora caída tras aceptar y tras
    fallar, ninguna causa encadenada) y `DeliverTransactionalEmailIT` con PostgreSQL y Mailpit.
  - **GREEN.** `DeliverTransactionalEmail`, puertos `EmailTransport` y `EmailDeliveryMetrics`.
  - **Verificación.** `-Dtest='DeliverTransactionalEmailTest'` y `-Dit.test='DeliverTransactionalEmailIT'`.

- [ ] **7. Cableado en el trabajador, métricas y registros** (decisiones 1 y 9; `NT01`, `NT19`, `NT20`, `BI152`,
  `BI153`)
  - **RED.** `ProcessBeanIsolationTest` falla con `not in the allow-list` al importar la configuración (registrar);
    `NotificationsLogRedactionIT` con centinelas; `WorkerProductionWiringTest` exige el caso de uso; control
    negativo: una línea que incluye la dirección pone en rojo `NT20`.
  - **GREEN.** `LogEmailDeliveryMetrics`, `NotificationsWorkerConfiguration` con `@NamedInterface("wiring")`,
    `@Import` en `WorkerApplication`, `ProcessBeanPolicy` y argumentos de prueba de los procesos.
  - **Verificación.** `-Dtest='ProcessBeanIsolationTest,WorkerProductionWiringTest,SpringModulithVerificationTest,WorkerHasNoMeterRegistryTest'`
    y `-Dit.test='NotificationsLogRedactionIT'`.

## Fase 5: compensación en `identity`

- [ ] **8. Migración `V17` y repositorio de tokens** (decisión 12; `BI159`-`BI161`, `I327`, `I329`, `I330` en su parte
  de repositorio)
  - **RED.** `V17SchemaDiffIT`; `JooqPasswordResetTokenRepositoryIT` ampliada (`markDeliveryFailed` solo sobre
    abierto, conteo sin anulados, `consume` y búsqueda por hash rechazan anulados, índice único parcial);
    `RetentionPurgeFunctionsIT` sigue en verde con filas anuladas.
  - **GREEN.** `V17__add_identity_password_reset_token_delivery_failed.sql`, jOOQ regenerado, repositorio.
  - **Verificación.** `-Dit.test='V17SchemaDiffIT,JooqPasswordResetTokenRepositoryIT,RetentionPurgeFunctionsIT,MultiTenantSchemaIT'`.

- [ ] **9. Compensación en `IssuePasswordResetToken`** (decisiones 10, 11 y 14; `I324`, `I328`)
  - **RED.** `IssuePasswordResetTokenTest` (fallo de envío → T2 anula, audita `identity.password_reset.delivery_failed`
    con solo `failureClass` y relanza; token ya no abierto → sin asiento; T2 falla → fallo original con suprimido;
    éxito → sin T2) y `IssuePasswordResetTokenIT` con un emisor de prueba que falla. Control negativo: omitir T2
    pone en rojo el conteo de tokens abiertos.
  - **GREEN.** Puerto ampliado, `PasswordResetLinkDeliveryException`, `Issuance` con `tokenId` y `recipient`, T2.
    `UnavailablePasswordResetLinkSender` se adapta a la firma nueva y sigue cableado.
  - **Verificación.** `-Dtest='IssuePasswordResetTokenTest,AuditJsonOnlySourceTest'` y
    `-Dit.test='IssuePasswordResetTokenIT,PasswordResetIssuanceHandlerIT,PasswordResetIssuanceDoubleExecutionIT'`.

## Fase 6: entrega real del enlace

- [ ] **10. Adaptador `EmailPasswordResetLinkSender` y retiro del emisor que falla** (decisión 13; `I320`-`I323`;
  inversión del escenario «El puerto de envío solo tiene el emisor que falla de forma explícita»)
  - **Nota (2026-11-10).** `EmailDeliveryFailedException.failureClass()` devuelve `FailureClass` (`notifications.domain`,
    fuera de la interfaz `api`). Antes del adaptador: exponer `FailureClass` por `api` o devolver el código como `String`.
  - **RED.** `EmailPasswordResetLinkSenderTest` (enlace `base#token=…`, vigencia de la política, traducción del fallo),
    `PasswordResetLinkSettingsStartupTest` (cinco URL inválidas y `http` local aceptada),
    `IdentityScopeExclusionInventoryTest` exige exactamente `EmailPasswordResetLinkSender` (falla mientras exista el
    emisor que falla).
  - **GREEN.** Adaptador, `PasswordResetLinkSettings`, bean en `IdentityWorkerConfiguration`, borrado de
    `UnavailablePasswordResetLinkSender` y de sus menciones en `WorkerProductionWiringTest`; `ConsumeRecoveryCodeIT` (I45) cambia el destino en su
    comentario.
  - **Verificación.** `-Dtest='EmailPasswordResetLinkSenderTest,IdentityScopeExclusionInventoryTest,SpringModulithVerificationTest'`
    y `-Dit.test='PasswordResetLinkSettingsStartupTest,WorkerProductionWiringTest'`.

- [ ] **11. Pruebas de extremo a extremo en el trabajador** (`I320`-`I322`, `I324`-`I326`, `I329`-`I332`)
  - **RED** (contra el árbol con el emisor real pero sin compensación conectada al reintento, o con el control
    negativo indicado): `PasswordResetLinkDeliveryIT` (tarea → Mailpit → token del enlace aceptado por
    `ResetPasswordWithToken`; `I321` con bloqueo desde otra conexión), `PasswordResetDeliveryFailureIT` (servidor
    detenido, recuperación transitoria, cinco intentos con el reloj de db-scheduler, límite por hora),
    `PasswordResetDeliveryRedactionIT` (centinelas, con control negativo que añade la dirección al mensaje del
    fallo).
  - **GREEN.** Sin código de producción nuevo salvo ajustes que las pruebas revelen, registrados.
  - **Verificación.** `-Dit.test='PasswordResetLinkDeliveryIT,PasswordResetDeliveryFailureIT,PasswordResetDeliveryRedactionIT'`.

## Fase 7: documentación y cierre

- [ ] **12. Documentación, runbook y ADR** (diseño §5; P5; corrección editorial de `docs/08` §8.4)
  - Notas fechadas, sin reescribir cuerpos: `docs/05` (Mailpit fijado por digest en lugar de MailHog en la
    definición local y en la fila de herramientas; variables `MAIL_FROM`, `NOTIFICATIONS_RECIPIENT_HASH_KEY`,
    `PASSWORD_RESET_LINK_BASE_URL`; el servicio de `docker-compose` sigue siendo del cambio 11), `docs/08` §8.4 (el
    ejemplo en Java, ADR-0013; qué existe en F0 y qué llega en F6), `docs/03` §4.7 (el enlace se entrega; el aviso al
    titular es de 14b), `docs/07` (métricas por adaptador de registros); runbook
    `docs/runbooks/entrega-de-correo-transaccional.md` (fallo del proveedor, tarea estacionada, acceso de producción
    de SES, rotación de credenciales SMTP y de la llave del hash) y su fila en `docs/runbooks/README.md`; ADR-0029
    pasa a Aceptado si el propietario lo aprueba.
  - **Verificación.** `-Dtest='DocumentationNotesTest,SuppressionCitesAdrTest'`.

- [ ] **13. Inventarios, especificación y cierre del cambio**
  - `CurrentAbsencesTest` y los inventarios de ausencias reflejan los destinos nuevos (`account-security-notices`
    para los avisos, F6 para rebotes y purga de la bitácora); `apply-progress.md` con mediciones y sondas;
    `./mvnw verify -Pmutation-gate` completo; lista de seguridad de la skill marcada en la descripción del PR
    (integración externa, cambio de esquema, tarea en segundo plano).
  - **Verificación.** `./mvnw verify -Pmutation-gate` en `apps/api`, en verde.
