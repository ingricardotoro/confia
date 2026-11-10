# Progreso de aplicación: adaptador de correo transaccional (cambio 14a de F0)

- **Cambio:** `transactional-email-adapter`
- **Modo:** TDD estricto. Ejecutor: `./mvnw -B` en `apps/api` (JDK 25, Docker en ejecución).
- **Rama del PR 1:** `change/transactional-email-adapter-01-notifications-module-and-mail-rule`, apilada sobre
  `change/transactional-email-adapter` (`1bab0d6`). Sin push.
- **Estado del PR 1 (2026-11-10):** tarea 1 hecha. Tarea 2 **parcial y bloqueada** por un conflicto de diseño con
  `SigningAndHashingConfinementTest` (ver «Tarea 2»); no se marca `[x]`.

## Entorno de ejecución (notas para quien repita los comandos)

- `JAVA_HOME` apuntaba a JDK 21 aunque `java` del `PATH` es JDK 25, y el enforcer exige `[25,26)`. Se ejecutó con
  `JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-25.0.3.9-hotspot"`.
- Con JDK 25 la descarga desde Maven Central falló con `PKIX path building failed` (un antivirus intercepta TLS y su
  raíz está en el almacén de Windows, no en el de JDK 25). Se resolvió solo para el proceso con
  `MAVEN_OPTS="-Djavax.net.ssl.trustStoreType=WINDOWS-ROOT"`. Ningún archivo del repositorio ni de `~/.m2` cambió.
- Un `-Dtest` acotado termina en `BUILD FAILURE` por el `jacoco:check` (cobertura 0 % porque casi no corre código);
  las pruebas pasan. Es el comportamiento que `tasks.md` ya advierte. El cierre es `verify` completo.

## Tareas

| Tarea | Estado | Evidencia |
|---|---|---|
| 1 | hecho | Sondas S1 a S4, rojo de compilación, verde, dos controles negativos revertidos con `cmp` |
| 2 | parcial | Todo salvo `RecipientHash` está en verde; `RecipientHash` está retenido (conflicto de diseño, abajo) |

## Sondas de la tarea 1 (diseño §2.2)

| Sonda | Resultado | Evidencia |
|---|---|---|
| S1. Coordenadas gestionadas por el BOM de Spring Boot 4.1.1 | **Confirmada, sin versión explícita** | `spring-boot-dependencies-4.1.1.pom` fija `angus-mail.version=2.0.5` y `spring-framework.version=7.0.9`; `dependency:tree` resuelve `spring-context-support:7.0.9`, `angus-mail:2.0.5`, `jakarta.mail-api:2.1.5`, `jakarta.activation-api:2.1.4` y `angus-activation:2.0.3`. No hace falta propiedad de versión (plan B descartado). `dependencyConvergence` del enforcer en verde |
| S2. Ninguna autoconfiguración de correo sin el iniciador | **Confirmada** | `dependency:tree` sin `spring-boot-mail` ni `spring-boot-starter-mail`. Control negativo: añadir `spring-boot-starter-mail` al `pom.xml` pone en rojo `noMailAutoConfigurationIsOnTheClassPath` con `org.springframework.boot.mail.autoconfigure.MailSenderAutoConfiguration` y `MailSenderValidatorAutoConfiguration` en la lista (plan B de exclusión descartado) |
| S3. Mailpit con STARTTLS obligatorio, certificado propio y API REST | **Confirmada** | Imagen `axllent/mailpit:v1.33.1`, digest `sha256:aef2aacc11f075ec0fb6874275ee4a421e688e11a96ebb296134d1735c7761ce` (el mismo de `latest` al 2026-11-09; `v1.29.7` tenía seis meses). Con `MP_SMTP_TLS_CERT`, `MP_SMTP_TLS_KEY` y `MP_SMTP_REQUIRE_STARTTLS=true` y un certificado autofirmado con `subjectAltName=DNS:smtp.confia.test`: STARTTLS y envío aceptados; sin STARTTLS el servidor responde `530 5.7.0 Must issue a STARTTLS command first`. `GET /api/v1/messages` devuelve `total` y `messages[].Subject`; `DELETE /api/v1/messages` responde `200`. GreenMail no hace falta (plan B descartado) |
| S4. Rechazo de destinatario con `550` | **Confirmada, con otro texto** | `MP_SMTP_ALLOWED_RECIPIENTS='^ok@'` hace que el destinatario `ana@x.test` reciba `550 5.1.0 Requested action not taken: mailbox unavailable`. Mailpit no repite la dirección ni ofrece el texto `5.1.1 <dirección>: user unknown` del escenario `NT15`; la prueba de la tarea 5 usa este texto y comprueba que ni la excepción ni su traza contienen la dirección ni el texto del servidor. Autenticación (`--smtp-auth-file`, `NT16`) figura en la ayuda de la imagen y se sondea en la tarea 5 |

La imagen **elegida** para `MailpitContainer` (tarea 5) es `axllent/mailpit:v1.33.1@sha256:aef2aacc11f075ec0fb6874275ee4a421e688e11a96ebb296134d1735c7761ce`.

## Evidencia de ciclo TDD

| Tarea | Archivo de prueba | Capa | ROJO | VERDE | Control negativo |
|---|---|---|---|---|---|
| 1 `BI150` | `architecture/MailClientConfinementTest` (4 pruebas) y dos fixtures en `architecture/fixture/mail` | Arquitectura | Compilación (abajo) | 4/4 | Cambiar `jakarta.mail..` por `jakarta.mailx..` pone en rojo `rejectsTheInfrastructureFixtureOfAnotherModule`; revertido con `cmp` |
| 1 `BI151` | `bootstrap/StarterAutoConfigurationExcludedTest` (6 pruebas, 1 nueva) | Arquitectura | La aserción de ausencia ya cumple sin el iniciador; su rojo es el control negativo | 6/6 | Añadir `spring-boot-starter-mail` la pone en rojo; revertido con `cmp` y `git diff --stat` (solo las 15 líneas de las dos dependencias) |

### Rojo 1 (la regla compila sin la dependencia; los fixtures no)

Comando: `./mvnw -B -q -pl app -am verify -DskipITs -Dsurefire.failIfNoSpecifiedTests=false -Dtest='MailClientConfinementTest'`
sin las dos dependencias en `app/pom.xml`. Resultado: `COMPILATION ERROR` solo en los fixtures:

```
ImportsJakartaMail.java:[3,29] package jakarta.mail.internet does not exist
ImportsSpringMail.java:[3,32] package org.springframework.mail does not exist
```

`MailClientConfinementTest` en sí compila porque la regla nombra los paquetes con cadenas. Es la causa esperada: sin
la biblioteca no hay fixture que la importe, y la regla sería vacía. Por eso la prueba `theMailClientIsOnTheClassPathSoTheRuleIsNotVacuous`
exige que `InternetAddress` y `SimpleMailMessage` se carguen y que existan los dos fixtures.

### Verde

`./mvnw -B -pl app -am verify -DskipITs -Dsurefire.failIfNoSpecifiedTests=false -Dtest='MailClientConfinementTest,StarterAutoConfigurationExcludedTest'`:
`Tests run: 4` (`MailClientConfinementTest`) y `Tests run: 6` (`StarterAutoConfigurationExcludedTest`), 0 fallos; el
`BUILD FAILURE` posterior es solo el `jacoco:check` de un `-Dtest` acotado.

## Desviaciones y observaciones de la tarea 1

- **Ruta de `StarterAutoConfigurationExcludedTest`.** El prompt de aplicación la situaba en `architecture/`; el archivo
  real (y la tabla de §5 del diseño) está en `app/src/test/java/com/confia/bootstrap/`. Se amplió allí.
- **Fixtures.** Se crearon en `architecture/fixture/mail/identity/infrastructure` y
  `architecture/fixture/mail/notifications/application`, como pide `BI150`.
- **Regla sin población previa.** A diferencia de `DbSchedulerConfinementTest`, no se exige que
  `notifications.infrastructure` tenga clases: en este PR está vacío (llega con la tarea 5). La no vacuidad se prueba
  con los fixtures y la carga de las clases del cliente.

## Tarea 2: dominio y API pública de `notifications` (parcial)

### Qué se entrega en este commit

`notifications/package-info.java`, `notifications.domain` (`FailureClass`, `RenderedEmail`) y
`notifications.application.api` con `@NamedInterface("api")` (`SendTransactionalEmail`, `TransactionalEmail`,
`TransactionalEmailTemplate`, `EmailRecipient`, `EmailDeliveryReceipt`, `EmailDeliveryFailedException`), con sus pruebas
(`FailureClassTest`, `RenderedEmailTest`, `TransactionalEmailTest`, `EmailRecipientTest`, `EmailDeliveryOutcomeTypesTest`,
`NotificationsModuleTest` para `NT02` y la forma del módulo).

### Rojo 2

`./mvnw -B -pl app -am verify -DskipITs -Dsurefire.failIfNoSpecifiedTests=false -Dtest='RecipientHashTest,TransactionalEmailTest'`
sin código de producción: `COMPILATION ERROR`, `cannot find symbol` sobre `RecipientHash`, `TransactionalEmail`,
`EmailRecipient` y el resto (200 líneas de error; causa esperada).

### Verde y controles

- Verde del lote (97 pruebas con `RecipientHash`): `Tests run: 97, Failures: 0`, incluidas `SpringModulithVerificationTest`,
  `NoTechnicalLayerPackageNamesTest`, `DomainPurityRulesTest` y `LayeredArchitectureTest`.
- Control negativo: quitar `toLowerCase(Locale.ROOT)` y la comprobación de caracteres de control de `TransactionalEmail`
  puso en rojo 11 pruebas (`anyIsoControlCharacterInAParameterIsRejected` x8, `theHeaderInjectionOfScenarioNt05...`,
  `lowerCasingDoesNotDependOnTheDefaultLocale` y otra de `RecipientHashTest`); revertido y confirmado con `cmp`.
- `./mvnw -B verify -Pmutation-gate` completo, **sin** `RecipientHash`: `BUILD SUCCESS`; 2 944 pruebas unitarias
  (3 omitidas previas) y 850 de integración sin fallos; `All coverage checks have been met`; PIT sobre `notifications.domain`:
  `RenderedEmail` 21 de 21 mutantes muertos (`FailureClass` no genera mutantes).

### Conflicto de diseño que bloquea `RecipientHash` (decisión 8)

La decisión 8 sitúa el HMAC-SHA-256 en `notifications.domain` con `javax.crypto.Mac`. El primer `verify` completo con esa
clase falló: `SigningAndHashingConfinementTest.productionCodeOutsideIdentityAndSharedSecurityNeitherSignsNorHashes` (BI11 a
BI13 y BI38, decisión 16 de `session-tokens-and-web-layer`) prohíbe `Mac`, `MessageDigest` y `org.bouncycastle..` fuera de
`identity` y `shared.security`, **sin lista de excepciones**, y manda usar `com.confia.shared.security.Digests`. Hoy `Digests`
solo ofrece `sha256`, y `DomainPurityRulesTest` impide que un `domain` dependa de `shared`. Las tres violaciones son las
llamadas a `Mac` de `RecipientHash.hmacSha256Hex`. Resolverlo exige editar `shared/security` (fuera de las superficies
autorizadas de este lote) o una regla; no se inventó una excepción.

Camino recomendado, para decisión del propietario: añadir `Digests.hmacSha256(byte[] key, byte[] data)` en `shared.security`
(con su prueba y el vector de RFC 4231, que PIT ya cubre en `shared.security.*`), dejar en `notifications.domain` solo el
tipo valor `RecipientHash` con la normalización (`strip` y `toLowerCase(Locale.ROOT)`) y su validación hex de 64, y calcular
el HMAC en `notifications.application` con `Digests`. La llave menor de 32 bytes se rechaza en esa clase de aplicación.

El trabajo hecho (clase, `RecipientHashTest` con el vector de RFC 4231, `RecipientHashProperties` con jqwik) está en la rama local
`wip/transactional-email-adapter-recipient-hash`, fuera de este PR.

### Presupuesto

Sin `RecipientHash`, el PR 1 mide 793 líneas en `notifications` más unas 150 de la tarea 1 (aprox. 940), por encima de 800
incluso antes de añadir `RecipientHash`; la costura de `tasks.md` es 1a (tarea 1) y 1b (tarea 2).
