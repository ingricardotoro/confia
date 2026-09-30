# Diseño: recuperación de contraseña con token de un solo uso

Cambio 7 de F0, **tercero de cuatro**. Implementa la propuesta aprobada el 2026-09-30, con sus ocho
respuestas del propietario (cinco de la exploración, §7, y tres de la ronda de la propuesta), y los
dos deltas aprobados el mismo día:
- `specs/identity/spec.md`: 21 requisitos, 46 escenarios (17 añadidos y 4 modificados);
- `specs/build-integrity/spec.md`: 2 requisitos, 7 escenarios.

- **Estado:** aprobado por el propietario el 2026-09-30, con la pregunta abierta 1 resuelta: la
  fuga de `TotpCode` se corrige en este cambio (decisión 13).

**Las especificaciones son el contrato.** Este diseño no encontró nada que obligue a reabrirlas
(sección 0).

Todo lo que sigue se verificó **por lectura del árbol en `main` @ `b94fedb`**:
- el código de `com.confia.identity`, `com.confia.shared.security`, `com.confia.shared.audit`,
  `com.confia.shared.infrastructure` y `com.confia.bootstrap`;
- las migraciones `V1` a `V6`;
- las pruebas de `schema`, `architecture` e `identity`;
- ADR-0015 y ADR-0016;
- `docs/03-seguridad.md` §4.2, §4.5, §4.6, §4.7, §6.1, §10 y §12.2;
- el diseño archivado de la parte 2.

**Aplicando la lección de la parte 2** (`archive/2026-09-30-column-encryption-and-mfa-totp/
archive-report.md`, «El patrón que se repitió»): toda afirmación de que algo se reutiliza o es «lo
mismo» cita la clase, la constante y su valor, y tiene una prueba que falla si dejara de serlo.
Lo que no se puede confirmar leyendo es una **sonda** de la sección 9, con su comando y su respaldo.

---

## 0. Los deltas no necesitan ajuste

Hay tres puntos de lectura dudosa. Los tres se resuelven dentro del diseño y no piden cambiar texto:

- **«El SHA-256 del token».** Se interpreta como el SHA-256 de los bytes ASCII del texto base64url
  que recibe el puerto de envío. El escenario «La base solo contiene el SHA-256 del token entregado»
  lo compara exactamente con eso (decisión 3).
- **«Dos emisiones NO DEBEN producir el mismo token».** Con 256 bits aleatorios la probabilidad de
  choque es despreciable. Además, la restricción `UNIQUE (institution_id, token_hash)` de la decisión
  1 convierte un choque imposible en un error visible y nunca en dos filas iguales.
- **Precedencia de motivos de rechazo.** El requisito de auditoría enumera cuatro motivos. Un token
  puede estar a la vez vencido y superado, así que el diseño fija el orden: usado, superado, vencido
  (decisión 4). El escenario «Los cuatro motivos…» usa tokens con un único motivo cada uno, así que
  el orden no lo contradice.

---

## 1. Enfoque técnico

Tres casos de uso en `com.confia.identity.application`, más los tipos de dominio y los adaptadores
que los sostienen. Van en **cinco cortes encadenados** dentro de este mismo cambio SDD, cada uno con
`./mvnw verify` en verde y TDD estricto (`openspec/config.yaml`):

| Corte | Contenido |
|---|---|
| **C1** | `V7`, puerto y adaptador jOOQ del token, ampliación de `StaffAccountRepository`, extensión de `MultiTenantSchemaIT`, `RolePrivilegeMatrixIT` e `IdentityRowSecurityIT`. Sondas S1 y S2 |
| **C2** | Dominio: token redactado, hash redactado, política del token (vigencia, ventana, motivos), política de longitud y `TotpCode` sin fuga (decisión 13). Sonda S4 |
| **C3** | `RequestPasswordReset` con el puerto de programación, `IssuePasswordResetToken` con el puerto de envío, concurrencia de emisión. Sonda S3 |
| **C4** | `ResetPasswordWithToken` con la composición del segundo factor, atomicidad, concurrencia de restablecimiento y contador TOTP compartido |
| **C5** | Redacción con control negativo, inventario de ausencias, notas de documentación y cierre de trazabilidad |

### 1.1 El problema que gobierna el diseño

El restablecimiento tiene **dos efectos que deben sobrevivir aunque el restablecimiento se rechace**:
el avance del contador TOTP y su asiento de auditoría. Y tiene **dos efectos que no deben ocurrir si
se rechaza**: el consumo del token y el cambio de contraseña.

La forma natural de escribirlo, con una excepción ante un código incorrecto, revierte la
transacción. Esa reversión se lleva el avance del contador y convierte el restablecimiento en un
oráculo para adivinar códigos TOTP sin retroceso.

`TransactionRunner.execute` confirma cuando el cuerpo **devuelve** y revierte cuando **lanza**:
`template.execute(status -> { applySecurityContext(context); return useCase.get(); })`, sin
`setRollbackOnly` en ninguna rama. Por eso el diseño hace que **todo rechazo de negocio sea un valor
devuelto, nunca una excepción** (decisión 7), igual que `VerifyTotpCode`, que devuelve
`VerifyTotpCodeDecision(false, requiredDelay)` y confirma.

### 1.2 Lo que este cambio no toca

- **Ningún bean de producción.** `AdminApplication`, `PortalApplication` y `WorkerApplication`
  declaran `scanBasePackages = "com.confia.bootstrap"` y excluyen `DataSourceAutoConfiguration`.
  Ninguna clase de este cambio lleva anotación de Spring, y ningún archivo de `com.confia.bootstrap`
  se modifica.
- **Registrar módulos de negocio en un proceso** exige antes `process-entry-point-isolation`, el
  cambio 15 (`foundations-plan/exploration.md`, nota de los dos cambios nuevos).
- **El adaptador de envío** es de `transactional-email-adapter`, el cambio 14.
- **El adaptador de programación** es del cambio 9.

---

## 2. Evidencia obtenida en esta fase y límites

| Pregunta | Resultado | Evidencia |
|---|---|---|
| ¿`TransactionRunner` confirma un cuerpo que devuelve normalmente? | **Sí.** Solo revierte si el cuerpo lanza; reintenta solo `40001` y `40P01`, `DEFAULT_MAX_RETRIES = 3`, `DEFAULT_BACKOFF_BASE = 20 ms` | `TransactionRunner.execute` e `isRetryable` |
| ¿Qué propagación tiene un `execute` anidado? | `PROPAGATION_REQUIRED`, porque `newTemplate` solo fija el aislamiento. Un `execute` anidado se uniría a la transacción exterior con su propio bucle de reintento dentro. Se evita (decisión 7) | `TransactionRunner.newTemplate` |
| ¿Son accesibles los cuerpos de `VerifyTotpCode` y `ConsumeRecoveryCode` sin abrir transacción? | **Sí.** Ambos exponen `runWithinTransaction(InstitutionId, StaffAccountId, String requestId, …)` con visibilidad de paquete, en `com.confia.identity.application`, el mismo paquete de los casos de uso nuevos | `VerifyTotpCode.java`, `ConsumeRecoveryCode.java` |
| ¿Qué regla de retroceso aplica `VerifyTotpCode`? | Instancia `new BackoffPolicy()` en su constructor: `FIRST_DELAYED_ATTEMPT = 3`, `CAP = Duration.ofSeconds(900)`, `COUNTER_WINDOW = Duration.ofMinutes(30)`. `PERIOD = Duration.ofSeconds(30)`; `TotpVerificationPolicy.TOLERANCE_WINDOW = 1` | `BackoffPolicy.java`, `VerifyTotpCode.java:42`, `TotpVerificationPolicy.java:19` |
| ¿Dónde vive el contador TOTP y cuál es su clave? | `identity_mfa_totp_backoff`, `PRIMARY KEY (institution_id, account_id)`. Una fila por cuenta, así que todo camino que use `TotpVerificationBackoffStore` con la misma cuenta comparte contador | `V6`, restricción `identity_mfa_totp_backoff_pk` |
| ¿`ConsumeRecoveryCode` tiene retroceso? | **No.** Umbral de aviso `LOW_RECOVERY_CODE_THRESHOLD = 3`; ningún almacén de retroceso. Riesgo aceptado por el propietario (proposal.md, respuesta 2) | `ConsumeRecoveryCode.java` |
| ¿Cómo decide el inicio de sesión si una cuenta pide segundo factor? | `mfaRequired` de `StaffAccount` y, si es `true`, `totpCredentials.findByAccountId(...).isPresent()` | `AuthenticateWithPassword.outcomeForValidCredentials` |
| ¿Existe precedente de `SELECT ... FOR UPDATE` con `confia_admin_app` sobre una tabla con `FORCE ROW LEVEL SECURITY` y privilegios `SELECT, INSERT, UPDATE`? | **Sí**: `JooqIdempotencyRecordStore.lockExisting` usa `.forUpdate()` sobre `shared_idempotency_key`, con esos mismos privilegios y seguridad forzada. `identity_staff_account` tiene exactamente ese trato | `JooqIdempotencyRecordStore.java:52-61`; `V4` líneas 64-65 y 83; `V5` |
| ¿Existe precedente de traducir `23505` por `SQLState` a través de la cadena de causas de jOOQ? | **Sí**: `JooqIdempotencyRecordStore.translate` compara `"23505".equals(sql.getSQLState())` | `JooqIdempotencyRecordStore.java:145-151` |
| ¿Una violación `23505` aborta la transacción en curso? | **Sí.** `IdempotentExecutor` lo documenta y abre otra transacción tras la colisión («T1 is already aborted by the 23505») | `IdempotentExecutor.java:106,159` |
| ¿`MultiTenantSchemaIT` acepta un índice único parcial que incluye `institution_id`? | **Sí, por precedente en `main`**: `shared_data_encryption_key_one_active_per_institution` es índice único parcial y `everyUniqueIndexOfABusinessTableIncludesTheInstitutionDiscriminator` pasa. La prueba lee `pg_index` con `indisunique` | `MultiTenantSchemaIT.java:84,344-349`; `V6` línea 30 |
| ¿Qué prefijo exige `TableOwnershipByModuleTest` a un adaptador de `com.confia.identity.infrastructure`? | `Identity`, el segmento anterior a `infrastructure`. La clase generada debe llamarse `IdentityPasswordResetToken` (sonda S2 confirma el nombre) | `TableOwnershipByModuleTest.java:89-97` |
| ¿Cómo se prueba hoy que la institución no viene de la solicitud? | Por la forma: `AuthenticationCommand` no tiene campo de institución. El restablecimiento sigue ese patrón | `LoginInstitutionIT.java:37-44` |
| ¿Existe un hasher SHA-256 reutilizable para el token? | **No.** `RequestPayloadHasher` hashea JSON canonicalizado con prefijo `FORMAT_VERSION = "confia.idempotency.v1"`, y aplicarlo a un token cambiaría su contrato | `RequestPayloadHasher.java` |
| ¿Cómo genera el módulo valores aleatorios? | `SecureRandom` inyectable por constructor, con uno por omisión | `EnrollTotpSecondFactor.java:53-70`; `BouncyCastleArgon2PasswordHasher` |
| ¿Qué límites tiene hoy `PlainPassword`? | NFKC, no vacía, `MAX_LENGTH = 1024` en unidades UTF-16, documentado como guarda técnica y no como política | `PlainPassword.java` |
| ¿Qué validación tiene `LoginIdentifier`? | `MIN_LENGTH = 3`, `MAX_LENGTH = 320`, NFKC y minúsculas con `Locale.ROOT` | `LoginIdentifier.java` |
| ¿Con qué se calcula la huella del identificador? | HMAC-SHA-256 con subllave derivada de la pimienta, `DOMAIN_SEPARATION_LABEL = "confia.identity.login-identifier.v1"` | `HmacLoginIdentifierFingerprinter.java` |
| ¿Existe ya la corrección de la premisa «no usa TOTP»? | **Sí**, fusionada el 2026-09-30 en `foundations-plan/exploration.md`, línea 182. Este cambio no la repite; solo añade las dos condiciones duras para la parte 4 (decisión 12) | `foundations-plan/exploration.md` en `ad1b63e` |
| ¿Cómo se comporta NFKC con los emoji de los escenarios? | **No verificado por lectura.** Sonda S4 | — |
| ¿Qué `SQLState` produce una inserción que choca con el índice único parcial mientras otra transacción la tiene sin confirmar? | **No verificado por lectura.** Sonda S1 | — |
| ¿Ve la segunda emisión, tras esperar el bloqueo, el token que la primera acaba de confirmar? | **Semántica documentada de `READ COMMITTED`** (instantánea por sentencia), sin precedente probado en el árbol. Sonda S3 | — |

---

## 3. Decisiones de arquitectura

### Decisión 1 — DDL de `V7`

```sql
-- Seventh migration (F0 change 7, part 3, cut C1; design.md decision 1;
-- specs/build-integrity/spec.md). Creates the password-reset token table.

CREATE TABLE identity_password_reset_token (
    institution_id UUID        NOT NULL,
    id             UUID        NOT NULL,
    account_id     UUID        NOT NULL,
    token_hash     TEXT        NOT NULL,
    issued_at      TIMESTAMPTZ NOT NULL,
    expires_at     TIMESTAMPTZ NOT NULL,
    consumed_at    TIMESTAMPTZ,
    superseded_at  TIMESTAMPTZ,
    CONSTRAINT identity_password_reset_token_pk         PRIMARY KEY (institution_id, id),
    CONSTRAINT identity_password_reset_token_account_fk FOREIGN KEY (institution_id, account_id)
        REFERENCES identity_staff_account (institution_id, id),
    CONSTRAINT identity_password_reset_token_hash_uq    UNIQUE (institution_id, token_hash),
    CONSTRAINT identity_password_reset_token_hash_chk   CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT identity_password_reset_token_window_chk CHECK (expires_at > issued_at),
    CONSTRAINT identity_password_reset_token_final_chk
        CHECK (consumed_at IS NULL OR superseded_at IS NULL)
);

CREATE UNIQUE INDEX identity_password_reset_token_one_open_per_account
    ON identity_password_reset_token (institution_id, account_id)
    WHERE consumed_at IS NULL AND superseded_at IS NULL;

CREATE INDEX identity_password_reset_token_issued_idx
    ON identity_password_reset_token (institution_id, account_id, issued_at);

ALTER TABLE identity_password_reset_token ENABLE ROW LEVEL SECURITY;
ALTER TABLE identity_password_reset_token FORCE  ROW LEVEL SECURITY;

CREATE POLICY identity_password_reset_token_institution_isolation ON identity_password_reset_token
    USING      (institution_id = NULLIF(current_setting('app.institution_id', true), '')::uuid)
    WITH CHECK (institution_id = NULLIF(current_setting('app.institution_id', true), '')::uuid);

REVOKE ALL ON identity_password_reset_token FROM PUBLIC;
GRANT SELECT, INSERT, UPDATE ON identity_password_reset_token TO confia_admin_app;
GRANT SELECT                  ON identity_password_reset_token TO confia_readonly;
-- confia_portal_app: no GRANT at all. No DELETE to any role: expired rows are retained until
-- change 9 decides the purge (owner, 2026-09-30).
```

Cada elemento, con su motivo:

- **Nombre `identity_password_reset_token`.** Prefijo de módulo de ADR-0015 regla 3. La clase
  generada debe ser `IdentityPasswordResetToken`, confirmado por la sonda S2.
- **`issued_at` y `expires_at` sin `DEFAULT`.** Los escribe el adaptador desde el `Clock` inyectado,
  igual que `identity_login_backoff.last_attempt_at` en `V5`. Eso permite probar el borde de 29:59 y
  30:00 con `Clock.fixed` y sin esperar.
- **`window_chk` exige solo `expires_at > issued_at`, no «exactamente 30 minutos».** Poner los
  treinta minutos en el catálogo daría una segunda fuente de verdad del plazo, y la lección de la
  parte 2 es precisamente no tener dos. El plazo vive en una única constante de dominio (decisión 4),
  y la prueba de integración del borde es la que falla si el adaptador escribiera otro valor.
- **`final_chk`.** Un token no puede estar usado y superado a la vez.
- **`hash_uq` es restricción y no índice.** Es la búsqueda del restablecimiento y figura en
  `pg_constraint` con `institution_id`, como pide ADR-0009 punto 2.
- **`one_open_per_account` es la red de seguridad del invariante «a lo sumo un token abierto»**, no el
  mecanismo que lo garantiza, que es el bloqueo de la decisión 2. Cuenta como abierto un token
  vencido que nadie superó, así que la emisión supera todo token abierto, vencido o no (decisión 5).
- **`issued_idx`.** Sirve al conteo de la ventana de sesenta minutos (decisión 5). Tiene consumidor
  en este mismo cambio, así que no es un índice preparado para después.
- **Seguridad de fila y privilegios.** Mismo texto que las políticas de `V5` y `V6`
  (`NULLIF(current_setting('app.institution_id', true), '')::uuid` en `USING` y `WITH CHECK`) y mismo
  reparto de privilegios que la adenda de §6.1.

**Descartado:** una columna de estado enumerada (`live`, `consumed`, `superseded`). Las dos marcas de
tiempo ya llevan el estado, y además el instante que la auditoría y la retención necesitan.

### Decisión 2 — Serialización por cuenta: bloqueo de fila, con el índice parcial como red

**Elección.** La emisión y el restablecimiento toman primero `SELECT ... FOR UPDATE` sobre la fila de
la cuenta en `identity_staff_account`. Es el patrón de `JooqIdempotencyRecordStore.lockExisting`
(`.forUpdate()`), con el mismo rol y el mismo trato de privilegios y seguridad de fila (sección 2).

- **Con el bloqueo**, la segunda emisión concurrente espera. Cuando la primera confirma, la segunda
  cuenta, supera e inserta viendo ya la fila confirmada, gracias a la instantánea por sentencia de
  `READ COMMITTED` (sonda S3). El índice parcial nunca llega a dispararse.
- **Si alguna vez se dispara**, es un defecto. Una violación `23505` aborta la transacción
  (`IdempotentExecutor`, sección 2) y `TransactionRunner.isRetryable` no la reintenta, porque solo
  reconoce `40001` y `40P01`. **No se traduce a desenlace de negocio**: se propaga como excepción no
  comprobada, revierte la transacción entera y no deja asiento de auditoría. Un defecto de
  serialización no debe disfrazarse de «límite alcanzado» ni de «token rechazado». La prueba
  `PasswordResetConcurrencyIT` incluye un control negativo: con el bloqueo desactivado por un doble
  de prueba, dos emisiones concurrentes producen `23505`, con el `SQLState` que fije la sonda S1. Así
  se demuestra que la red existe y que el bloqueo es lo que la mantiene sin usar.

**Orden de bloqueos, para que no haya interbloqueo:**

| Camino | Orden |
|---|---|
| Emisión | cuenta → filas de token |
| Restablecimiento | lectura del token sin bloqueo → cuenta → contador TOTP → credencial TOTP o código de recuperación → token (actualización condicional) → cuenta (actualización del hash, ya bloqueada) |
| Verificación TOTP tras el inicio de sesión (parte 2, sin cambios) | contador TOTP → credencial TOTP |

Ningún par de recursos se toma en órdenes opuestos. Si aun así apareciera un `40P01`, `TransactionRunner`
lo reintenta hasta `DEFAULT_MAX_RETRIES = 3` veces.

**Descartadas:**
- **Solo el índice parcial, traduciendo `23505` y reintentando en una transacción nueva.** Añade
  código de recuperación para un caso que el bloqueo elimina, y el reintento tendría que volver a
  auditar.
- **Aislamiento `SERIALIZABLE`.** Queda como respaldo de la sonda S3, no como primera opción, porque
  convierte cada choque en un `40001` reintentado.

### Decisión 3 — Token, hash y su redacción

En `com.confia.identity.domain`:

- **`PlainPasswordResetToken`**, clase final con `toString()` redactado, nunca `record`. Es la
  lección de `AuthenticationCommand` en la parte 1.
  - `generate(SecureRandom)` produce 32 bytes codificados con `Base64.getUrlEncoder().withoutPadding()`:
    ⌈32·4/3⌉ = 43 caracteres.
  - `of(String)` exige exactamente 43 caracteres del alfabeto base64url. El mensaje de la excepción
    nunca interpola el valor recibido.
- **`PasswordResetTokenHash`**, también clase final redactada, porque el delta prohíbe que el
  SHA-256 sea observable.
  - `of(PlainPasswordResetToken)` calcula `MessageDigest.getInstance("SHA-256")` sobre los bytes
    ASCII del texto base64url y lo codifica en hexadecimal minúscula de 64 caracteres, igual que el
    `CHECK` de la decisión 1.

El hash vive en dominio y no detrás de un puerto porque es una función pura del JDK, sin secreto: no
hay pimienta, a diferencia de Argon2id. El dominio ya usa primitivas del JDK de la misma familia, por
ejemplo `TotpAlgorithm` con `Mac`.

**Descartados:**
- **Argon2id.** 256 bits de entropía no necesitan un hash lento, la búsqueda exige determinismo y
  regalaría CPU a quien inventa tokens (exploración, H2).
- **HMAC con subllave**, al estilo de `HmacLoginIdentifierFingerprinter`. Se aparta del texto de §4.7
  y no protege de nada que 256 bits no cubran ya.
- **Reutilizar `RequestPayloadHasher`.** Cambiaría su contrato (sección 2).

### Decisión 4 — Política del token en dominio: una sola fuente de cada constante

`PasswordResetTokenPolicy`, en dominio, sin reloj propio: recibe los instantes como argumento, igual
que `BackoffPolicy`.

- `VALIDITY = Duration.ofMinutes(30)`. `expiresAt(issuedAt) = issuedAt + VALIDITY`. Un token es
  vigente si `now.isBefore(expiresAt)`: el borde de 30:00 queda estrictamente fuera.
- `ISSUANCE_WINDOW = Duration.ofMinutes(60)` y `MAX_ISSUANCES_PER_WINDOW = 3`. La emisión está
  permitida si hay menos de 3 tokens con `issued_at > now − ISSUANCE_WINDOW`. Un token emitido
  exactamente sesenta minutos antes ya no cuenta, como dice el delta.
- `rejectionReasonOf(row, now)` devuelve un valor vacío si el token es aceptable, o el primer motivo
  en este orden: `CONSUMED`, `SUPERSEDED`, `EXPIRED`. `NOT_FOUND` lo decide el caso de uso cuando no
  hay fila.

El adaptador **no** calcula `expires_at`: lo recibe ya calculado por esta política. Así la constante
de treinta minutos existe una sola vez en el código.

### Decisión 5 — Puertos y adaptador del token

**`PasswordResetTokenRepository`** (en `application`), con su único adaptador
`JooqPasswordResetTokenRepository` (en `infrastructure`, `final`, constructor sobre `DSLContext`, sin
anotación). Métodos:

- `countIssuedSince(institution, account, Instant since)`: cuenta las emisiones posteriores a `since`.
- `supersedeOpen(institution, account, Instant at)`: marca como superados, con `superseded_at = at`,
  todos los tokens abiertos de la cuenta y devuelve cuántos fueron.
- `insert(institution, id, account, hash, issuedAt, expiresAt)`.
- `findByHash(institution, hash)`: devuelve la fila si existe.
- `consume(institution, id, Instant at)`: un único `UPDATE ... SET consumed_at = ? WHERE
  institution_id = ? AND id = ? AND consumed_at IS NULL AND superseded_at IS NULL AND expires_at > ?`.
  Devuelve si ganó según `updatedRows > 0`, el patrón de `JooqRecoveryCodeRepository.markUsed`.

**`StaffAccountRepository`** gana dos métodos, implementados en `JooqStaffAccountRepository`:

- `lockById(institution, account)`: `SELECT ... FOR UPDATE`, que devuelve la cuenta si existe.
- `replacePasswordHash(institution, account, StoredPasswordHash)`: un `UPDATE`, que devuelve si
  afectó una fila.

`findBy(InstitutionId, LoginIdentifier)` no cambia.

### Decisión 6 — Los tres casos de uso y sus dos puertos sin adaptador

Ninguno lleva anotación de Spring. Todos siguen el patrón de `AuthenticateWithPassword`: `execute`
delega en `TransactionRunner.execute`, y existe un `runWithinTransaction` de paquete para las pruebas
con dobles.

**`RequestPasswordReset`**
- Recibe `SecurityContext` y `RequestPasswordResetCommand(presentedIdentifier)`, un `record` con un
  único campo: no tiene campo de institución ni de contraseña.
- **Guarda de institución:** si la institución del contexto difiere de
  `LoginInstitutionProvider.loginInstitutionId()`, lanza `IllegalStateException` antes de abrir
  conexión. Es la decisión 11 de la parte 1, literal.
- Dentro de la transacción:
  1. `LoginIdentifier.of(...)` y la huella del identificador.
  2. `accounts.findBy(...)`.
  3. Si la cuenta existe, `PasswordResetIssuanceScheduler.schedule(institution, accountId)`.
  4. Un único asiento de solicitud (decisión 9).
- Devuelve `RequestPasswordResetDecision`, un tipo sin campos que representa «solicitud recibida».
  El resultado es idéntico por construcción, porque no hay nada que pueda variar.

**`PasswordResetIssuanceScheduler`** (puerto)
- `schedule(InstitutionId, StaffAccountId)`. Solo identificadores, como exige ADR-0016 regla 3. Se
  invoca dentro de la transacción de la solicitud, como exige ADR-0016 regla 2.
- **Sin adaptador de producción**: el adaptador sobre db-scheduler es del cambio 9.

**`IssuePasswordResetToken`**
- Es el cuerpo del futuro manejador del trabajador. Recibe `SecurityContext` con `actorKind`
  `system` e institución tomada de la tarea (ADR-0016 regla 4), más un `StaffAccountId`. No aplica la
  guarda de `LoginInstitutionProvider`: la institución llega de la tarea y la seguridad de fila la
  acota.
- Dentro de la transacción:
  1. `lockById`. Si la cuenta no existe, no hace nada; no puede ocurrir, porque no hay `DELETE`.
  2. `countIssuedSince(now − ISSUANCE_WINDOW)`. Si es 3 o más, escribe el asiento de emisión omitida
     y devuelve `Skipped`.
  3. Si no, `PlainPasswordResetToken.generate(secureRandom)`, `supersedeOpen(now)`,
     `insert(expiresAt = policy.expiresAt(now))` y el asiento de emisión.
- **Tras confirmar, y fuera de la transacción**, llama a `PasswordResetLinkSender.send(institution,
  accountId, token)`. Así el envío no ocurre con el bloqueo tomado (ADR-0016 regla 7) y nunca se
  envía un token cuya transacción se revirtió.
- Devuelve `Issued` o `Skipped`, sin el token.

**`PasswordResetLinkSender`** (puerto)
- `send(InstitutionId, StaffAccountId, PlainPasswordResetToken)`. Es el único lugar por donde sale el
  token en claro.
- **Sin adaptador de producción**: es de `transactional-email-adapter`, cambio 14.
- Un fallo del envío deja un token emitido y no entregado. La persona repite la solicitud, y la nueva
  emisión supera la anterior. El reintento o encolado del envío es contrato del cambio 14.

**`ResetPasswordWithToken`**
- Decisión 7.

### Decisión 7 — Restablecimiento: orden de validación y composición del segundo factor

**Entrada.** `ResetPasswordCommand`, clase final redactada, porque carga el token y la contraseña
nueva. Lleva el token presentado, la contraseña nueva en bruto y un `SecondFactorProof` sellado con
tres variantes: `None`, `Totp(TotpCode)` y `RecoveryCode(PlainRecoveryCode)`. No tiene campo de
institución (patrón de `LoginInstitutionIT`). Aplica la misma guarda de institución que la solicitud.

**Colaboradores.** El caso de uso recibe por constructor las instancias de `VerifyTotpCode` y
`ConsumeRecoveryCode` e invoca sus `runWithinTransaction` de paquete, nunca su `execute`, para no
anidar transacciones (sección 2).

**Transacción única, en este orden:**

```
TransactionRunner.execute(context, READ COMMITTED, ...)
  1  hash = PasswordResetTokenHash.of(token)                      (sin E/S)
  2  row  = tokens.findByHash(institution, hash)                  (sin bloqueo)
       sin fila           -> audit rejected{token-not-found}   -> TokenRejected
       policy.rejectionReasonOf(row, now) presente
                          -> audit rejected{token-<motivo>}    -> TokenRejected
  3  length = StaffPasswordLengthPolicy.evaluate(raw)             (decisión 8, sin Argon2id)
       fuera de rango     -> audit rejected{password-too-short|too-long}
                          -> PasswordRejected(motivo)
  4  account = accounts.lockById(institution, row.accountId)      (FOR UPDATE)
  5  mfaActive = account.mfaRequired()
                 && totpCredentials.findByAccountId(...).isPresent()
       (misma expresión que AuthenticateWithPassword.outcomeForValidCredentials)
     si mfaActive:
       None         -> audit rejected{second-factor-missing}    -> SecondFactorMissing
       Totp(c)      -> d = verifyTotp.runWithinTransaction(...)  (avanza o limpia el contador,
                       escribe su propio asiento y el ciclo de retroceso si lo hay)
                       !d.accepted() -> audit rejected{second-factor-invalid}
                                     -> SecondFactorRejected(d.requiredDelay())
       RecoveryCode -> r = consumeRecoveryCode.runWithinTransaction(...)
                       !r.accepted() -> audit rejected{second-factor-invalid}
                                     -> SecondFactorRejected(Duration.ZERO)
     si no mfaActive: se ignora cualquier prueba presentada y no se invoca ningún verificador
  6  tokens.consume(institution, row.id, now)
       0 filas (otro consumo ganó) -> audit rejected{token-consumed} -> TokenRejected
  7  newHash = passwordHasher.hash(plainPassword)                  (Argon2id, solo aquí)
  8  accounts.replacePasswordHash(institution, account.id, newHash)
  9  audit completed{secondFactor: totp|recovery-code|none}
     -> Completed(requiredDelay de d si hubo TOTP, si no ZERO)
```

`ResetPasswordDecision(ResetOutcome outcome, Duration requiredDelay)`. `ResetOutcome` es un tipo
sellado con exactamente `Completed`, `TokenRejected`, `PasswordRejected(reason)`,
`SecondFactorMissing` y `SecondFactorRejected`. Todo consumidor usa un `switch` exhaustivo sin
`default`, como en la decisión 6 de la parte 2. `TokenRejected` no lleva motivo: los cuatro motivos
y el caso de otra institución producen el mismo valor.

**Por qué este orden:**

- **El token va antes que todo.** Un token inventado no cuesta más que un SHA-256 y una lectura por
  índice único: ni Argon2id, ni bloqueo, ni verificación TOTP.
- **La longitud va antes que el bloqueo y el segundo factor.** Una contraseña de 11 caracteres no
  debe gastar un intento de TOTP ni un código de recuperación de la persona.
- **El segundo factor va antes del consumo.** Un fallo deja el token vivo, y como el caso de uso
  devuelve en vez de lanzar, `TransactionRunner` confirma el avance del contador
  (`identity_mfa_totp_backoff`) y los asientos. Eso es el requisito «El fallo queda confirmado y el
  token sigue sirviendo».
- **El consumo va antes de Argon2id.** Solo el ganador de la carrera paga el hash. El perdedor recibe
  cero filas en el paso 6 y sale sin coste.
- **Argon2id se calcula con la fila de la cuenta bloqueada.** Son entre 250 y 500 ms según §4.1, y se
  acepta: el bloqueo es por cuenta y solo lo alcanza quien tiene un token vivo y, si hay MFA, un
  segundo factor válido.

**Descartados:**
- **Calcular Argon2id en una transacción previa.** Obligaría a validar el token dos veces y
  complicaría la atomicidad que §4.7 exige entre consumo y cambio.
- **Una excepción para el segundo factor incorrecto.** Revertiría el contador (sección 1.1).

**Mismo contador, citado por constante.** El camino TOTP del restablecimiento es literalmente
`VerifyTotpCode.runWithinTransaction`, que usa el mismo almacén `TotpVerificationBackoffStore`, con
clave `(institution_id, account_id)` en `identity_mfa_totp_backoff`, y la misma instancia de regla
`new BackoffPolicy()`: `FIRST_DELAYED_ATTEMPT = 3`, `CAP = 900 s`, `COUNTER_WINDOW = 30 min`. La
prueba `PasswordResetTotpBackoffSharingIT` reproduce el escenario del delta. Si el restablecimiento
tuviera contador propio, el tercer fallo daría 0 s en vez de 1 s y la prueba fallaría. Si la ventana
dejara de ser de 30 minutos, el intento de las 09:35 no empezaría ciclo nuevo y también fallaría.

### Decisión 8 — La regla de 12 a 128 caracteres

`StaffPasswordLengthPolicy`, en dominio, con `MIN_CODE_POINTS = 12` y `MAX_CODE_POINTS = 128`
(`docs/03` §4.2, personal):

- normaliza el valor en bruto con NFKC, el mismo `Normalizer.Form.NFKC` que usa `PlainPassword.of`;
- cuenta con `codePointCount`, porque §4.2 admite emoji y un emoji fuera del plano básico ocupa dos
  unidades UTF-16;
- devuelve `Accepted(PlainPassword)`, `TooShort` o `TooLong`, **sin lanzar**.

Como 128 puntos de código ocupan como mucho 256 unidades UTF-16, por debajo de
`PlainPassword.MAX_LENGTH = 1024`, `PlainPassword.of` nunca lanza sobre un valor ya aceptado.

**Ámbito.** La regla vive solo en la contraseña nueva. `PlainPassword` sigue siendo la guarda técnica
del inicio de sesión, sin mínimo, para no rechazar contraseñas ya existentes. La cota de tamaño del
cuerpo HTTP es de la validación del borde de `session-tokens-and-web-layer`.

No hay reglas de composición. La lista de contraseñas comprometidas es de su cambio propio.

### Decisión 9 — Acciones de auditoría

Todas se escriben con `AuditLogWriter.append` dentro de la transacción de su efecto.
`auditRequestId` sigue el precedente: `requestId.isBlank() ? UUID.randomUUID() :
UUID.fromString(requestId)`.

| Acción | Tipo de entidad / identificador | `actor_kind` / etiqueta | `after_value` |
|---|---|---|---|
| `identity.password_reset.requested` | `identity.staff_account` / **huella** del identificador, en ambas ramas | `staff` / el correo si la cuenta existe, `unknown-account` si no (precedente de `AuthenticateWithPassword`) | `{"outcome":"issuance-scheduled"}` o `{"outcome":"account-not-found"}` |
| `identity.password_reset.issued` | `identity.password_reset_token` / id de la fila | `system` / `password-reset-issuance` | `{"expiresAt":…, "supersededCount":n}`. **Nunca el hash** |
| `identity.password_reset.issuance_skipped` | `identity.staff_account` / id de la cuenta | `system` / ídem | `{"reason":"rate-limit","issuedInWindow":3}` |
| `identity.password_reset.completed` | `identity.staff_account` / id de la cuenta | `staff` / id de la cuenta | `{"secondFactor":"totp"\|"recovery-code"\|"none"}` |
| `identity.password_reset.rejected` | `identity.password_reset_token` / id de la fila, o la etiqueta `unknown-token` si no hay fila; `identity.staff_account` para rechazos de contraseña y de segundo factor | `staff` / ídem | `{"reason":"token-not-found"\|"token-expired"\|"token-superseded"\|"token-consumed"\|"password-too-short"\|"password-too-long"\|"second-factor-missing"\|"second-factor-invalid"}` |

La verificación TOTP y el consumo de un código de recuperación escriben además sus propios asientos
de la parte 2 (`identity.mfa.totp_verification.*`, `identity.mfa.recovery_code.used`,
`identity.mfa.recovery_codes.low`), porque se reutilizan sus cuerpos. **Ninguna acción nueva contiene
`notif`, `email`, `mail` ni `sent`**, que es lo que el escenario «Un restablecimiento completado no
notifica a nadie» comprueba, con el mismo criterio de `ConsumeRecoveryCodeIT`.

### Decisión 10 — Redacción de los secretos nuevos

Cuatro valores no deben ser observables: el token en claro, su hash, la contraseña nueva y su hash
Argon2id.

- `PlainPasswordResetToken`, `PasswordResetTokenHash` y `ResetPasswordCommand` son clases finales
  con `toString()` redactado. La contraseña nueva viaja en `PlainPassword` y su hash en
  `StoredPasswordHash`, los dos ya redactados.
- `IdentitySecretRedactionIT` se extiende con los cuatro valores. Como control negativo se añade el
  accesorio de prueba `LeakingPasswordResetTokenFixture`, una clase que expone el token en
  `toString()`: la prueba debe fallar contra ella, que es el segundo escenario del requisito.

### Decisión 11 — Inventario de ausencias

`IdentityScopeExclusionInventoryTest` gana reglas que fallan el día que llegue su dueño:

- **Ningún adaptador de producción** implementa `PasswordResetIssuanceScheduler` ni
  `PasswordResetLinkSender`. Se comprueban con reglas de ArchUnit sobre implementaciones de
  interfaz; `NoBlockingWaitInIdentityTest` es el precedente de reglas sobre llamadas.
- **Solo `ResetPasswordWithToken` invoca `StaffAccountRepository.replacePasswordHash`.** Es el
  escenario «El único camino para cambiar una contraseña es el token».
- **Ninguna clase de `com.confia.identity` referencia sesiones ni tokens de refresco.** Es el primer
  escenario de la ausencia de revocación. El segundo, que la brecha se cierra, lo prueba
  `session-tokens-and-web-layer` por condición dura (decisión 12). Aquí queda solo escrito, y así lo
  dice la tabla de trazabilidad.

### Decisión 12 — Documentación

- **Dos condiciones duras para `session-tokens-and-web-layer`**, escritas en
  `foundations-plan/exploration.md` junto a la que ya existe para `identity_login_backoff`:
  - revocar todas las familias de una cuenta al restablecer su contraseña;
  - no fusionar el endpoint de solicitud sin el límite por IP de §4.7 y §10.
- **`docs/03-seguridad.md` §4.7**, nota fechada:
  - tabla `identity_password_reset_token`;
  - emisión en el trabajador;
  - definición de MFA activa;
  - SHA-256 del texto base64url.
- **`docs/03-seguridad.md` §6.1**, adenda de privilegios de la tabla.
- **`docs/08-datos-privacidad-y-retencion.md`, línea 322**, nota: filas retenidas hasta el cambio 9.
- **`docs/09-roadmap-y-fases.md` §3**, pendientes heredados de la parte 3.
- **Ningún texto de interfaz.** El mensaje del `202` va al catálogo de internacionalización en la
  parte 4.

### Decisión 13 — `TotpCode` deja de exponer el valor presentado

Añadida el 2026-09-30 por decisión del propietario sobre la pregunta abierta 1.

**Hallazgo, verificado en `main` @ `b94fedb`.** `TotpCode.java:18-19` lanza
`IllegalArgumentException("a TOTP code must be exactly 6 digits, was " + value)`. Además, como
`TotpCode` es un `record`, su `toString()` implícito imprime `TotpCode[value=…]`. En el
restablecimiento, el código viaja en el mismo comando que el token; cualquier registro de la
excepción o del comando copiaría el valor presentado.

**Decisión.**
- El mensaje de la excepción conserva la regla y deja de interpolar el valor:
  `"a TOTP code must be exactly 6 digits"`.
- `TotpCode` sobrescribe `toString()` con un texto fijo sin el valor, igual que las clases
  redactadas de la decisión 3. `equals` y `hashCode` del `record` no cambian: `VerifyTotpCode`
  compara con `TotpAlgorithm.generate`, que no depende de ellos.
- La validación no cambia: el patrón `^[0-9]{6}$` sigue igual y los cuatro casos de
  `TotpCodeTest` siguen valiendo.

**Pruebas.** `TotpCodeTest` gana dos casos que primero fallan en rojo contra el código actual: el
mensaje de un valor rechazado (por ejemplo `"12a456"`) no contiene ese valor, y el `toString()` de
un código válido no contiene sus seis dígitos. `IdentitySecretRedactionIT` añade `TotpCode` a la
lista de valores que no pueden aparecer en registros (decisión 10).

**Alcance.** Toca código archivado de la parte 2, pero solo en una clase de dominio, sin cambio de
contrato ni de comportamiento observable salvo el texto. Es una tarea más en C2.

---

## 4. Flujo de datos

### 4.1 Solicitud

```
RequestPasswordReset.execute(ctx, cmd)
  guarda: ctx.institutionId == LoginInstitutionProvider.loginInstitutionId(), si no IllegalStateException
  TransactionRunner.execute(ctx, READ COMMITTED)
  ┌─────────────────────────────────────────────────────────────────────┐
  │ 1 id = LoginIdentifier.of(cmd.presentedIdentifier)                   │
  │ 2 fp = fingerprinter.fingerprintOf(id)                               │
  │ 3 acc = accounts.findBy(institution, id)                             │
  │ 4 acc presente -> scheduler.schedule(institution, acc.id)            │
  │ 5 audit identity.password_reset.requested (1 asiento, ambas ramas)   │
  └─────────────────────────────────────────────────────────────────────┘
  -> RequestPasswordResetDecision (sin campos)
```

### 4.2 Emisión

```
IssuePasswordResetToken.execute(ctx{system, institución de la tarea}, accountId)
  TransactionRunner.execute(ctx, READ COMMITTED)
  ┌─────────────────────────────────────────────────────────────────────┐
  │ 1 accounts.lockById(institution, accountId)            FOR UPDATE    │
  │ 2 n = tokens.countIssuedSince(institution, accountId, now - 60 min)  │
  │ 3 n >= 3 -> audit issuance_skipped -> Skipped                        │
  │ 4 token = PlainPasswordResetToken.generate(secureRandom)             │
  │ 5 s = tokens.supersedeOpen(institution, accountId, now)              │
  │ 6 tokens.insert(..., hash(token), now, policy.expiresAt(now))        │
  │ 7 audit identity.password_reset.issued{supersededCount: s}           │
  └─────────────────────────────────────────────────────────────────────┘
  confirmada -> sender.send(institution, accountId, token)   (fuera de la transacción)
  -> Issued | Skipped
```

### 4.3 Restablecimiento

Es el diagrama de la decisión 7, dentro de una única `TransactionRunner.execute(ctx, READ
COMMITTED, …)`, precedida por la guarda de institución.

---

## 5. Cambios de archivos

| Archivo | Acción | Descripción |
|---|---|---|
| `db/migration/V7__create_identity_password_reset_token.sql` | Crear | Decisión 1 |
| `identity/domain/PlainPasswordResetToken.java`, `PasswordResetTokenHash.java` | Crear | Decisión 3 |
| `identity/domain/PasswordResetTokenPolicy.java`, `PasswordResetTokenRow.java`, `PasswordResetRejectionReason.java` | Crear | Decisión 4 |
| `identity/domain/StaffPasswordLengthPolicy.java` | Crear | Decisión 8 |
| `identity/domain/TotpCode.java` | Modificar | Decisión 13: mensaje y `toString()` sin el valor |
| `identity/application/PasswordResetTokenRepository.java`, `PasswordResetIssuanceScheduler.java`, `PasswordResetLinkSender.java` | Crear | Puertos; los dos últimos sin adaptador |
| `identity/application/RequestPasswordReset.java`, `RequestPasswordResetCommand.java`, `RequestPasswordResetDecision.java` | Crear | Decisión 6 |
| `identity/application/IssuePasswordResetToken.java`, `IssuePasswordResetTokenDecision.java` | Crear | Decisión 6 |
| `identity/application/ResetPasswordWithToken.java`, `ResetPasswordCommand.java`, `SecondFactorProof.java`, `ResetPasswordDecision.java`, `ResetOutcome.java` | Crear | Decisión 7 |
| `identity/application/StaffAccountRepository.java` | Modificar | `lockById`, `replacePasswordHash` |
| `identity/infrastructure/JooqPasswordResetTokenRepository.java` | Crear | Decisión 5 |
| `identity/infrastructure/JooqStaffAccountRepository.java` | Modificar | Los dos métodos nuevos |
| `VerifyTotpCode.java`, `ConsumeRecoveryCode.java` | **Sin cambios** | Se reutilizan sus `runWithinTransaction` tal cual |
| `com.confia.bootstrap/**` | **Sin cambios** | Ningún bean (sección 1.2) |
| Pruebas, ver sección 6 | Crear o modificar | — |
| `docs/03`, `docs/08`, `docs/09`, `foundations-plan/exploration.md` | Modificar | Decisión 12, solo notas fechadas |

---

## 6. Estrategia de pruebas y trazabilidad

| Capa | Qué | Cómo |
|---|---|---|
| Unitaria de dominio | `PasswordResetTokenPolicy` (borde de 30:00, ventana de 60 minutos, precedencia), `StaffPasswordLengthPolicy` (11, 12, 128, 129, puntos de código), token y hash (43 caracteres, 32 bytes, hexadecimal) | JUnit, AssertJ, jqwik para la longitud. Puerta de 95 % de cobertura y 80 de mutación |
| Unitaria de aplicación | Los tres `runWithinTransaction` con dobles, incluida la ignorancia de la prueba de segundo factor sin MFA activa | Dobles escritos a mano, como `AuthenticateWithPasswordTest` |
| Integración | Todo lo demás, con `confia_admin_app` y `confia_portal_app`, nunca con el propietario | `CommittingPostgresIntegrationTest` para confirmación real; `CyclicBarrier` para la concurrencia, nunca esperas por reloj |
| Arquitectura | Inventario de ausencias; único escritor del hash | `IdentityScopeExclusionInventoryTest` |

### 6.1 Trazabilidad de los 53 escenarios

**`identity`, requisitos añadidos (34 escenarios)**

| Requisito | Escenario | Prueba |
|---|---|---|
| La solicitud solo programa la emisión | Cuenta existente: programa una emisión y no crea ningún token | `RequestPasswordResetIT` |
| | No bloquea la cuenta ni cambia su contraseña | `RequestPasswordResetIT` (inicio de sesión real tras la solicitud) |
| Solo SHA-256 | La base solo contiene el SHA-256 del token entregado | `IssuePasswordResetTokenIT` (SQL crudo contra el doble de envío) |
| | 32 bytes y no se repite | `IssuePasswordResetTokenIT` más `PlainPasswordResetTokenTest` |
| Tres emisiones por hora | La cuarta se omite | `IssuePasswordResetTokenIT` |
| | La ventana es móvil (11:00:01) | `IssuePasswordResetTokenIT` más `PasswordResetTokenPolicyTest` (borde exacto de 60 minutos) |
| A lo sumo un token vivo | Dos emisiones concurrentes | `PasswordResetConcurrencyIT`, más el control negativo de la decisión 2 |
| | Dos restablecimientos concurrentes | `PasswordResetConcurrencyIT` |
| Segundo factor con MFA activa | Sin código se rechaza; con TOTP válido se acepta | `ResetPasswordWithTokenIT` |
| | Un código de recuperación sustituye y queda consumido | `ResetPasswordWithTokenIT` |
| Sin MFA activa | `true` sin secreto: el siguiente inicio de sesión devuelve `SecondFactorEnrollmentRequired` | `ResetPasswordWithTokenIT` (con `AuthenticateWithPassword` real) |
| | `false` con secreto: no se registra verificación TOTP | `ResetPasswordWithTokenIT` |
| Mismo retroceso TOTP | Los fallos suman en el mismo contador (1 s, 2 s, ciclo nuevo a las 09:35) | `PasswordResetTotpBackoffSharingIT` |
| | El fallo queda confirmado y el token sigue sirviendo | `PasswordResetTotpBackoffSharingIT` (lee el contador y la bitácora tras confirmar) |
| 12 a 128 caracteres | Bordes 11, 12, 128 y 129 | `StaffPasswordLengthPolicyTest` más `ResetPasswordWithTokenIT` (el token sigue vivo) |
| | Puntos de código y no unidades UTF-16 | `StaffPasswordLengthPolicyTest` (sonda S4) |
| Institución del proceso | Se ignora la institución declarada | `ResetPasswordWithTokenIT`: el comando no tiene campo de institución (patrón de `LoginInstitutionIT`) |
| | Un token de otra institución no se encuentra | `ResetPasswordWithTokenIT` (dos instituciones) |
| Auditoría atómica | Los cuatro motivos dan el mismo resultado | `ResetPasswordWithTokenIT` |
| | Un restablecimiento que no confirma no deja nada | `PasswordResetAtomicityIT` (fallo determinista antes de confirmar) |
| Ningún secreto observable | Ni token ni contraseña en ningún texto | `IdentitySecretRedactionIT` |
| | La prueba detecta una fuga reintroducida | `IdentitySecretRedactionIT` contra `LeakingPasswordResetTokenFixture` |
| Ausencia de revocación | El restablecimiento solo cambia la contraseña | `ResetPasswordWithTokenIT` (efectos enumerados) más `IdentityScopeExclusionInventoryTest` |
| | La brecha se cierra con la primera emisión de refresco | **Sin prueba en este cambio**: condición dura escrita (decisión 12). La prueba la escribe `session-tokens-and-web-layer` |
| Ausencia de programación real | El puerto no tiene adaptador | `IdentityScopeExclusionInventoryTest` |
| | Una solicitud no produce un token por sí sola | `RequestPasswordResetIT` |
| Ausencia de envío | El puerto no tiene adaptador | `IdentityScopeExclusionInventoryTest` |
| | Un restablecimiento completado no notifica | `ResetPasswordWithTokenIT` (acciones de auditoría) |
| Ausencia del límite por IP y del HTTP | Once solicitudes sin límite | `RequestPasswordResetIT` |
| | El resultado es del caso de uso | `RequestPasswordResetTest` (el tipo del resultado no tiene campos) |
| Ausencia de purga | Un token vencido sigue almacenado | `JooqPasswordResetTokenRepositoryIT` |
| | Usados y superados se conservan | `JooqPasswordResetTokenRepositoryIT` |
| Ausencia del restablecimiento administrativo | El único camino es el token | `IdentityScopeExclusionInventoryTest` (único llamador de `replacePasswordHash`) |
| | Sin token válido no cambia nada | `ResetPasswordWithTokenIT` |

**`identity`, requisitos modificados (12 escenarios)**

| Requisito | Escenario | Prueba |
|---|---|---|
| Recuperación de un solo uso | Dentro de la ventana | `ResetPasswordWithTokenIT` |
| | Expirado o superado (a las 10:26, por sustitución) | `ResetPasswordWithTokenIT` (el motivo auditado es `token-superseded`, no `token-expired`) |
| | Borde estricto de 30:00 | `ResetPasswordWithTokenIT` más `PasswordResetTokenPolicyTest` |
| | Solicitud aún no emitida | `ResetPasswordWithTokenIT` (el doble de programación registra sin ejecutar) |
| Prohibición de enumeración | Correo existente | `RequestPasswordResetIT` |
| | Correo inexistente | `RequestPasswordResetIT` |
| | Cuenta en el límite indistinguible de una inexistente | `RequestPasswordResetIT` más `IssuePasswordResetTokenIT` |
| Ausencia de lista comprometida y de rehash | Autenticación sin verificación | `AuthenticateWithPasswordIT`, existente y sin cambios |
| | `123456789012` se acepta en el restablecimiento | `ResetPasswordWithTokenIT` |
| | El hash no se recalcula | La prueba existente de la parte 1, sin cambios |
| Ausencia de envío del aviso de códigos bajos | Calculado y auditado, sin correo | `ConsumeRecoveryCodeIT`, existente |
| | También al consumir en un restablecimiento | `ResetPasswordWithTokenIT` |

**`build-integrity` (7 escenarios)**

| Requisito | Escenario | Prueba |
|---|---|---|
| Tabla nueva | Puertas genéricas sin exclusión | `MultiTenantSchemaIT` |
| | Una institución no lee los tokens de otra | `IdentityRowSecurityIT`, extendida |
| | Sin contexto, cero filas | `IdentityRowSecurityIT`, extendida |
| | El catálogo rechaza un hash que no es hexadecimal de 64 | `IdentityRowSecurityIT` o una prueba de esquema propia, según el precedente del RTN en `MultiTenantSchemaIT` |
| Permisos | La matriz cubre la tabla para los cinco roles | `RolePrivilegeMatrixIT` |
| | `confia_admin_app` no borra | `RolePrivilegeMatrixIT` |
| | `confia_portal_app` sin privilegios | `RolePrivilegeMatrixIT` |

**Informativo, no puerta:** `PasswordResetRequestTimingReportIT` mide la mediana de la solicitud con
cuenta existente e inexistente, como `LoginTimingReportIT`. La puerta de 50 ms sobre HTTP es de la
parte 4.

---

## 7. Matriz de amenazas

| Frontera | Tratamiento |
|---|---|
| Enumeración por la solicitud | Resultado sin campos, un asiento por rama, huella en vez de correo (decisiones 6 y 9); la medición del tiempo es informativa |
| Adivinación de tokens | 256 bits; búsqueda por índice único; rechazo uniforme sin Argon2id ni bloqueo (decisión 7) |
| Oráculo de TOTP a través del restablecimiento | Rechazo devuelto y no lanzado; mismo contador que el inicio de sesión (decisión 7), con prueba |
| Doble consumo o doble token vivo | `UPDATE` condicional; bloqueo por cuenta; índice parcial como red (decisiones 2 y 5) |
| Token en registros o en la base | Solo el SHA-256 en la base; clases redactadas; control negativo (decisiones 3 y 10) |
| Token enviado de una transacción revertida | El envío ocurre tras confirmar (decisión 6) |
| Institución inyectada por quien llama | Comandos sin campo de institución más la guarda de `LoginInstitutionProvider` |
| Códigos de recuperación de MFA sin retroceso | **Riesgo aceptado** por el propietario; lo acota el límite por IP de la parte 4 |
| Cuenta con `mfa_required = true` sin secreto | **Riesgo aceptado**: quien controle el buzón obtiene la contraseña; la inscripción se exige en el siguiente inicio de sesión |
| Mensaje de excepción y `toString()` de `TotpCode` | Ninguno de los dos contiene ya el valor (decisión 13), con prueba en `TotpCodeTest` e `IdentitySecretRedactionIT` |
| Dependencias nuevas | Ninguna: SHA-256, `SecureRandom`, base64url y NFKC son JDK |

---

## 8. Migración y despliegue

Una sola migración nueva, `V7`, sin datos. No hay entorno desplegado ni dato real. La generación de
código de jOOQ aplica `V7` en el contenedor de construcción, así que `IdentityPasswordResetToken`
existe antes de escribir el adaptador (sonda S2). Una vez aplicada en producción, `V7` no se edita.

---

## 9. Sondas, a ejecutar antes de la tarea que depende de cada una

| # | Pregunta | Comando exacto | Criterio de éxito y respaldo | Bloquea |
|---|---|---|---|---|
| **S1** | ¿Qué `SQLState` recibe una segunda inserción que choca con `identity_password_reset_token_one_open_per_account` mientras la primera no ha confirmado, y cuándo lo recibe? | `docker run --rm -d --name prt-s1 -e POSTGRES_PASSWORD=probe postgres:18-alpine`, crear la tabla mínima con el índice de la decisión 1 y abrir dos sesiones con `docker exec -it prt-s1 psql -U postgres -v VERBOSITY=verbose`. En la A: `BEGIN; INSERT …;`. En la B: el mismo `INSERT` (debe quedar esperando). En la A: `COMMIT;` | **Éxito:** la sesión B espera y, tras el `COMMIT` de la A, falla con `SQLSTATE 23505`; si la A hace `ROLLBACK`, la B inserta. **Respaldo:** si el código fuera otro, el control negativo de la decisión 2 afirma el código observado. El diseño no cambia, porque el índice es solo red y el `23505` nunca se traduce a desenlace | C1 |
| **S2** | ¿jOOQ genera `IdentityPasswordResetToken` con ese nombre exacto tras `V7`? | `cd apps/api && ./mvnw -B -pl app generate-sources` y `find app/target/generated-sources -name 'IdentityPasswordResetToken*.java'` | **Éxito:** existe y `TableOwnershipByModuleTest` pasa. **Respaldo:** ajustar el nombre de la tabla antes de escribir el adaptador | C1 |
| **S3** | Tras esperar el `FOR UPDATE` de la cuenta, ¿ven el conteo y el `UPDATE` de la segunda emisión el token que la primera acaba de confirmar, en `READ COMMITTED`? | Con el contenedor de S1, más `identity_staff_account` mínima: en A `BEGIN; SELECT … FOR UPDATE; INSERT token;`; en B `BEGIN; SELECT … FOR UPDATE;` (espera); en A `COMMIT;`; en B `SELECT count(*) …;` | **Éxito:** B cuenta 1. **Respaldo:** ejecutar la emisión con `TransactionRunner.execute(context, IsolationLevel.SERIALIZABLE, …)`, que reintenta `40001` hasta `DEFAULT_MAX_RETRIES = 3`, y registrar la desviación. La prueba `PasswordResetConcurrencyIT` confirma el resultado en cualquier caso | C3 |
| **S4** | ¿NFKC deja intactos `casa azul🌋🌊` y `casa azul 🌋🌊`, con 11 y 12 puntos de código y 13 y 14 unidades UTF-16? | `jshell`, y dentro: `var a = java.text.Normalizer.normalize("casa azul🌋🌊", java.text.Normalizer.Form.NFKC); a.codePointCount(0, a.length()) + " " + a.length()`, y lo mismo para el segundo valor | **Éxito:** `11 13` y `12 14`. **Respaldo:** si NFKC cambiara alguno, se informa al propietario antes de C2, porque el escenario del delta lleva esos literales y habría que ajustarlo con su aprobación | C2 |

No son sondas, porque se verificaron por lectura o por precedente en `main` (sección 2):

- `FOR UPDATE` con `confia_admin_app` bajo seguridad de fila forzada;
- la aceptación del índice parcial por `MultiTenantSchemaIT`;
- la traducción de `SQLState` por la cadena de causas;
- la confirmación de un cuerpo que devuelve;
- el acceso a los `runWithinTransaction` de paquete.

---

## 10. Secuencia de aplicación con TDD estricto

- **C1.** Sondas S1 y S2 primero.
  - Rojo: `RolePrivilegeMatrixIT`, `MultiTenantSchemaIT` e `IdentityRowSecurityIT` con la tabla
    nueva, más `JooqPasswordResetTokenRepositoryIT` y los dos métodos nuevos de
    `JooqStaffAccountRepositoryIT`.
  - Verde: `V7`, el puerto y los adaptadores.
- **C2.** Sonda S4.
  - Rojo y verde de `PasswordResetTokenPolicyTest`, `StaffPasswordLengthPolicyTest`,
    `PlainPasswordResetTokenTest` y `PasswordResetTokenHashTest`.
  - Rojo y verde de los dos casos nuevos de `TotpCodeTest` (decisión 13).
- **C3.** Sonda S3.
  - `RequestPasswordReset` y `IssuePasswordResetToken` con sus pruebas unitarias e
    `RequestPasswordResetIT`, `IssuePasswordResetTokenIT`.
  - La mitad de emisión de `PasswordResetConcurrencyIT`, con su control negativo.
  - `PasswordResetRequestTimingReportIT`.
- **C4.** `ResetPasswordWithToken`, con su prueba unitaria y con `ResetPasswordWithTokenIT`,
  `PasswordResetTotpBackoffSharingIT`, `PasswordResetAtomicityIT` y la mitad de restablecimiento de
  `PasswordResetConcurrencyIT`.
  - Control negativo registrado en `apply-progress.md`: con una variante que lanza ante un segundo
    factor incorrecto, `PasswordResetTotpBackoffSharingIT` falla.
- **C5.** `IdentitySecretRedactionIT` con su accesorio de fuga; las reglas nuevas de
  `IdentityScopeExclusionInventoryTest`; las notas de la decisión 12; el cierre de trazabilidad de
  los 53 escenarios.

---

## 11. Pronóstico de tareas y tamaño, sin descontar el multiplicador histórico

| Corte | Tareas | Líneas en una pasada | Con 1,5× a 3× | Riesgo de superar 800 líneas |
|---|---|---|---|---|
| C1 | 3 | ~450 | 675-1350 | Alto |
| C2 | 3 | ~400 | 600-1200 | Medio |
| C3 | 3 | ~450 | 675-1350 | Alto |
| C4 | 3 a 4 | ~600 | 900-1800 | **Muy alto**: es el corte con más pruebas de integración |
| C5 | 2 | ~250 | 375-750 | Bajo |
| **Total** | **14 a 15** | **~2150** | **3225-6450** | — |

Quedan en el límite de quince o una por debajo. La propuesta estimó de 14 a 15; el diseño
recortó una tarea porque la corrección de la premisa ya está fusionada (sección 2), y la decisión 13
la devuelve. Si la fase de tareas llega a dieciséis, se consulta al propietario antes de aplicar el corte de abajo. Con el historial
de la parte 1 (nueve pull requests para doce tareas) y de la parte 2 (trece para quince), la
expectativa realista es **de nueve a doce pull requests encadenados**, no cinco. Si la fase de tareas
superara quince, el corte previsto en la propuesta sigue siendo válido: C1 a C3 por un lado y C4 y C5
por otro.

---

## 12. Preguntas abiertas

- [x] **`TotpCode` interpola el valor rechazado en su mensaje de excepción.** Resuelta el
      2026-09-30: el propietario decidió corregirlo en este cambio (decisión 13).
- [ ] La sonda S4 es la única que podría obligar a ajustar el texto de un escenario ya aprobado.
      Una comprobación previa con la normalización NFKC de Node dio `11 13` y `12 14`; la prueba en
      Java de C2 sigue siendo la que cuenta.
- [ ] Ninguna otra pregunta bloquea C1.
