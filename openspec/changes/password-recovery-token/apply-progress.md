# Progreso de aplicación: `password-recovery-token`

- **Corte en curso:** C5. C1a a C4c fusionados en `main` (#68 a #75).
- **Entorno:** OpenJDK 25.0.4 (paquete de Ubuntu 24.04), Maven Wrapper del repositorio, Docker 29.3.1
  con `postgres:18-alpine`. El `JAVA_HOME` del sistema apunta al JDK 21, así que toda invocación de
  Maven exporta `JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64` en la propia orden.
- **Línea base:** `./mvnw -B verify` sobre la rama del corte antes de tocar código: `BUILD SUCCESS`,
  186 pruebas del núcleo, 343 unitarias y 162 de integración, en 3 min 00 s.

---

## Tarea 1.1 — Sondas S1 y S2, migración `V7` y puertas de esquema

### Sonda S1 — segunda inserción contra el índice parcial con la primera sin confirmar

**PASA.** Tabla mínima con el índice parcial `identity_password_reset_token_one_open_per_account` en
un contenedor `postgres:18-alpine` propio. La sesión A abre la transacción, inserta y duerme 3 s; la
sesión B inserta la misma cuenta un segundo después.

```
A termina con COMMIT:   B espera 2,10 s y falla con
                        ERROR:  23505: duplicate key value violates unique constraint
                        "identity_password_reset_token_one_open_per_account"
A termina con ROLLBACK: B espera 2,11 s e inserta; queda 1 fila
```

El código observado es `23505`, el que el diseño suponía. La tarea 3.3 lo afirma en el control
negativo.

### Sonda S2 — nombre de la clase generada por jOOQ

**PASA, con una corrección de la orden.** `./mvnw -B -pl app generate-sources`, tal como la escriben
`design.md` §9 y la tarea 1.1, falla antes de generar nada: en esa fase el reactor aún no empaqueta
`confia-kernel` y la dependencia no se resuelve. Con `./mvnw -B -q -pl app -am compile`, que pasa por
la generación:

```
app/target/generated-sources/jooq/confia/generated/jooq/tables/IdentityPasswordResetToken.java
app/target/generated-sources/jooq/confia/generated/jooq/tables/records/IdentityPasswordResetTokenRecord.java
```

`TableOwnershipByModuleTest` pasa (2 pruebas). No hay que renombrar la tabla.

### ROJO

`RolePrivilegeMatrixIT` (6 pruebas nuevas), `MultiTenantSchemaIT` (1) e `IdentityRowSecurityIT` (4),
antes de que `V7` exista:

```
RolePrivilegeMatrixIT    Tests run: 34, Errors: 6    relation "identity_password_reset_token" does not exist
IdentityRowSecurityIT    Tests run: 10, Failures: 1, Errors: 3
MultiTenantSchemaIT      Tests run: 9,  Errors: 1
Total                    Tests run: 53, Failures: 1, Errors: 10
```

### VERDE

`V7__create_identity_password_reset_token.sql` con el DDL de la decisión 1, más comentarios:

```
RolePrivilegeMatrixIT       tests=34 failures=0 errors=0
MultiTenantSchemaIT         tests=9  failures=0 errors=0
IdentityRowSecurityIT       tests=10 failures=0 errors=0
TableOwnershipByModuleTest  tests=2  failures=0 errors=0
```

### Control negativo

Con `V7` debilitada a propósito (la restricción del hash cambiada a `length(token_hash) > 0` y
`DELETE` concedido a `confia_admin_app`) fallan exactamente las tres pruebas que protegen eso, y
ninguna otra:

```
IdentityRowSecurityIT.confiaAdminAppCanSelectInsertAndUpdateAPasswordResetTokenButNeverDeleteIt
MultiTenantSchemaIT.theDatabaseRejectsAPasswordResetTokenStoredInPlaceOfItsHash
RolePrivilegeMatrixIT.confiaAdminAppCanSelectInsertAndUpdateButNeverDeleteOnThePasswordResetTokenTable
```

`V7` se restauró y se comprobó byte a byte contra la versión en verde.

### Notas

- **Privilegios con sentencias reales.** `RolePrivilegeMatrixIT` sigue su precedente y lee
  `has_table_privilege`; las sentencias reales que piden los escenarios B6 y B7 viven en
  `IdentityRowSecurityIT`, la misma división que ya hacen `IdempotencyKeyPrivilegeIT` y las tablas de
  la parte 1.
- **La prueba del `CHECK` inserta antes un hash bien formado** para la misma cuenta y lo marca como
  superado, así el rechazo no puede venir de la llave foránea, de la política de fila ni del índice
  parcial.
- **Escenarios cubiertos:** B1 a B7.

---

## Tarea 1.2 — Puerto y adaptador jOOQ del token

### ROJO

`PasswordResetTokenHashTest` (7 pruebas) y `JooqPasswordResetTokenRepositoryIT` (10) antes de que
existan los tipos: `./mvnw -B -q -pl app -am test-compile` falla con 80 errores `cannot find symbol`
sobre `PasswordResetTokenHash`, `PasswordResetTokenRow`, `PasswordResetTokenRepository` y
`JooqPasswordResetTokenRepository`.

### VERDE

- `PasswordResetTokenHash`: clase final, constructor desde el hexadecimal, `toString()` redactado y
  mensaje de rechazo sin el valor recibido.
- `PasswordResetTokenRow`: `record` sin secretos; `consumedAt` y `supersededAt` nulos mientras el
  token está abierto.
- El puerto `PasswordResetTokenRepository`, con los cinco métodos de la decisión 5.
- `JooqPasswordResetTokenRepository`: `consume` es un único `UPDATE` condicional sobre
  `consumed_at IS NULL`, `superseded_at IS NULL` y `expires_at > now`.

```
PasswordResetTokenHashTest           tests=7  failures=0 errors=0
JooqPasswordResetTokenRepositoryIT   tests=10 failures=0 errors=0
```

### Control negativo

Sin las condiciones `superseded_at IS NULL` y `expires_at > now` en `consume` fallan tres pruebas:

```
anExpiredTokenIsStillStoredTwoHoursAfterItWasIssued
consumeRefusesATokenExactlyAtItsExpiryButAcceptsItOneSecondBefore
consumeRefusesASupersededToken    (rechazado entonces por identity_password_reset_token_final_chk)
```

La última muestra que `final_chk` es una segunda red bajo el predicado. El adaptador se restauró.

### Notas

- **Discrepancia 1 de `tasks.md` aplicada:** los dos tipos de dominio nacen aquí y no en C2. La fábrica
  `PasswordResetTokenHash.of(token)` llega en la tarea 2.2.
- **Escenarios cubiertos:** I31 e I32.

---

## Tarea 1.3 — Bloqueo y reescritura del hash de la cuenta

### ROJO

`JooqStaffAccountRepositoryIT` con cinco pruebas nuevas, y el doble `FakeStaffAccountRepository` de
`AuthenticateWithPasswordTest` con los dos métodos nuevos: `test-compile` falla porque ninguno de los
dos métodos existe en el puerto (`method does not override or implement a method from a supertype`,
`cannot find symbol`).

### VERDE

- `StaffAccountRepository` gana `lockById` y `replacePasswordHash`. `findBy` no cambia.
- `JooqStaffAccountRepository.lockById` es `SELECT ... FOR UPDATE` (`.forUpdate()`, el patrón de
  `JooqIdempotencyRecordStore.lockExisting`); `replacePasswordHash` es un `UPDATE` que devuelve si
  afectó una fila.
- El doble de `AuthenticateWithPasswordTest` lanza `UnsupportedOperationException` en los dos: el
  inicio de sesión nunca bloquea una cuenta ni reescribe su hash, y si alguna vez lo hiciera, esas
  pruebas fallarían.

```
AuthenticateWithPasswordTest   tests=7 failures=0 errors=0
JooqStaffAccountRepositoryIT   tests=8 failures=0 errors=0
```

**Cómo se observa la espera sin reloj.** Un hilo toma `lockById` y queda retenido con un
`CountDownLatch`; la segunda transacción fija `set local lock_timeout = '500ms'` y llama a
`lockById`. Solo puede fallar con `55P03` (lock_not_available) si tuvo que esperar. Al liberar la
primera, la misma llamada devuelve la cuenta.

### Control negativo

Sin `.forUpdate()` en `lockById` falla exactamente
`aSecondLockByIdOnTheSameAccountWaitsUntilTheFirstTransactionEnds`, y ninguna otra. El adaptador se
restauró.

### Escenarios

Ninguno directo; es la base de I7, I8 e I20 (tareas 3.3 y 4.3).

---

## Cierre de C1

- **`./mvnw -B clean verify`:** `BUILD SUCCESS` en 2 min 46 s. 186 pruebas del núcleo, 350 unitarias
  (343 de la línea base más 7) y 188 de integración (162 más 26).
- **Diff medido** con `git diff --numstat origin/main -- . ':(exclude)openspec' ':(exclude)**/generated/**'`:
  **1119 líneas**, por encima de las 800 de `docs/15-flujo-de-trabajo-git.md` §3. El corte se parte en
  dos pull requests encadenados, sin cambiar ninguna tarea de nivel superior:
  - **C1a** `change/password-recovery-token-c1a-schema`: tarea 1.1 (`V7` y sus puertas de esquema),
    unas 380 líneas, más las 7 de `docs/09` que traen los artefactos SDD ya aprobados.
  - **C1b** `change/password-recovery-token-c1b-token-repository`, sobre C1a: tareas 1.2 y 1.3, unas
    740 líneas.

---

## Tarea 2.1 — Sonda S4 y la regla de 12 a 128 caracteres

### Sonda S4

**PASA.** La primera ejecución en `jshell`, con los emoji escritos como escapes `\uD83C…` por la
entrada estándar, dio `17 17` y `18 18`: la consola de `jshell` no conserva los surrogados que
recibe por la entrada, así que esa lectura no vale. Repetida con un archivo Java en UTF-8 que lleva
los **literales copiados byte a byte del delta** (`specs/identity/spec.md`, línea 232):

```
casa azul🌋🌊    11 puntos de código, 13 unidades UTF-16, NFKC no lo cambia
casa azul 🌋🌊   12 puntos de código, 14 unidades UTF-16, NFKC no lo cambia
```

El escenario aprobado no necesita ajuste.

### ROJO

`StaffPasswordLengthPolicyTest` (8 casos y 2 propiedades de jqwik): `test-compile` falla con 46
errores `cannot find symbol`.

### VERDE

`StaffPasswordLengthPolicy` con `MIN_CODE_POINTS = 12`, `MAX_CODE_POINTS = 128`, NFKC y
`codePointCount`. Devuelve `Accepted(PlainPassword)`, `TooShort` o `TooLong`, sin lanzar salvo con
`null`.

```
StaffPasswordLengthPolicyTest   tests=10 failures=0 errors=0
```

La primera versión de la propiedad «todo valor de 12 a 128 es aceptado» se agotó (100 intentos, 92
descartes) porque un solo generador de 0 a 140 puntos de código producía pocos casos dentro del
rango. Se separaron los generadores: de 12 a 128 para esa propiedad y de 0 a 140 para la de rechazo.

### Control negativo

Contando con `length()` (unidades UTF-16) en vez de `codePointCount` fallan
`theScenarioLiteralsAreCountedInCodePointsNotUtf16Units` y la propiedad de rechazo. Se restauró.

### Escenarios

I15 (parte unitaria) e I16.

---

## Tarea 2.2 — Token en claro, fábrica del hash, política del token y motivos

### ROJO

`PlainPasswordResetTokenTest` (8), `PasswordResetTokenPolicyTest` (9) y dos casos nuevos en
`PasswordResetTokenHashTest`: `test-compile` falla con 72 errores `cannot find symbol`.

### VERDE

- `PlainPasswordResetToken`: clase final redactada; `generate(SecureRandom)` produce 32 bytes en 43
  caracteres base64url sin relleno; `of` exige exactamente ese alfabeto y esa longitud, sin repetir
  el valor en el mensaje.
- `PasswordResetTokenHash.of(token)`: SHA-256 de los bytes ASCII del texto, en hexadecimal
  minúscula. La prueba calcula el valor esperado por su cuenta con `MessageDigest` y `HexFormat`.
- `PasswordResetTokenPolicy`: `VALIDITY = 30 min`, `ISSUANCE_WINDOW = 60 min`,
  `MAX_ISSUANCES_PER_WINDOW = 3`, `expiresAt`, `issuanceWindowStart` (inicio exclusivo, el mismo
  predicado `issued_at > since` del adaptador), `allowsAnotherIssuance` y `rejectionReasonOf` con el
  orden usado, superado, vencido.
- `PasswordResetRejectionReason`: los ocho motivos de la decisión 9, cada uno con su código de
  auditoría.

```
PlainPasswordResetTokenTest    tests=8 failures=0 errors=0
PasswordResetTokenHashTest     tests=9 failures=0 errors=0
PasswordResetTokenPolicyTest   tests=9 failures=0 errors=0
```

La prueba de `generate` usa `SHA1PRNG` sembrado antes de su primer uso, que es determinista, y
comprueba además que los 32 bytes son exactamente los que entrega el `SecureRandom` recibido.

### Control negativo

Con el vencimiento evaluado primero y con borde no estricto (`now.isAfter(expiresAt)`) fallan tres
pruebas: el borde de 10:30:00, la precedencia de superado sobre vencido y la de usado sobre vencido.
Se restauró.

### Escenarios

I4, I6 e I37, en su parte unitaria.

---

## Tarea 2.3 — `TotpCode` sin fuga (decisión 13)

### ROJO

- `TotpCodeTest`, dos casos nuevos contra el código de `main`:
  `theRejectionMessageNeverCarriesThePresentedValue` (el mensaje de `new TotpCode("12a456")` lo
  contenía: `"… was 12a456"`) y `toStringNeverCarriesTheCode` (el `record` imprimía
  `TotpCode[value=005924]`). `Tests run: 6, Failures: 2`.
- `IdentitySecretRedactionIT`: el barrido de TOTP guarda el código presentado en una variable y
  revisa, en un texto propio, su `toString()`, la decisión rechazada y el mensaje de un código mal
  formado. Falla con «produced text unexpectedly contains a presented TOTP code (6 characters)», sin
  repetir el valor.

El barrido del código usa su propio texto y no el texto completo de las filas: seis dígitos son lo
bastante cortos como para aparecer por azar en una marca de tiempo o en un identificador.

### VERDE

El mensaje pasa a `"a TOTP code must be exactly 6 digits"` y `toString()` devuelve
`TotpCode[REDACTED]`. El patrón, `equals` y `hashCode` no cambian; ninguna prueba ni código dependía
del mensaje anterior.

```
TotpCodeTest                 tests=6 failures=0 errors=0
IdentitySecretRedactionIT    tests=3 failures=0 errors=0
VerifyTotpCodeIT             tests=2 failures=0 errors=0   (sin tocar)
TotpVerificationBackoffIT    tests=2 failures=0 errors=0   (sin tocar)
```

---

## Cierre de C2

- **`./mvnw -B clean verify`:** `BUILD SUCCESS` en 2 min 13 s. 186 pruebas del núcleo, 381
  unitarias y 188 de integración.
- **Diff medido** contra C1b, sin `openspec` ni código generado: **685 líneas**, dentro de las 800.
  Un solo pull request.

---

## Tarea 3.1 — `RequestPasswordReset` con el puerto de programación

### ROJO

`RequestPasswordResetTest` (4), `RequestPasswordResetIT` (5) y `PasswordResetRequestTimingReportIT`
(1): `test-compile` falla con 54 errores `cannot find symbol` sobre los cuatro tipos nuevos.

### VERDE

- `PasswordResetIssuanceScheduler`: `schedule(InstitutionId, StaffAccountId)`, solo identificadores.
  Sin adaptador de producción.
- `RequestPasswordResetCommand`: un solo campo, el identificador presentado.
- `RequestPasswordResetDecision`: un `record` sin componentes.
- `RequestPasswordReset`: guarda de institución antes de abrir conexión; dentro de la transacción,
  huella, búsqueda, programación si la cuenta existe y un asiento `identity.password_reset.requested`
  con la huella como entidad en las dos ramas.

```
RequestPasswordResetTest              tests=4 failures=0 errors=0
RequestPasswordResetIT                tests=5 failures=0 errors=0
PasswordResetRequestTimingReportIT    tests=1 failures=0 errors=0   (informativo, no puerta)
```

La prueba de la guarda usa un `TransactionRunner` sobre un `SimpleDriverDataSource` sin URL:
si la guarda no lanzara, abrir la transacción fallaría con otra excepción, nunca con
`IllegalStateException`.

### Control negativo

Con el asiento escrito solo cuando la cuenta existe, falla
`aNonexistentAddressGetsTheSameResultNothingScheduledAndOneEntryWithoutTheAddress`. Se restauró.

### Notas

- Docker se detuvo entre sesiones del contenedor y hubo que levantar `dockerd` de nuevo; ninguna
  prueba se vio afectada.

### Escenarios

I1, I2, I26, I29, I30, I39 e I40.

---

## Tarea 3.2 — `IssuePasswordResetToken` con el puerto de envío

### ROJO

`IssuePasswordResetTokenTest` (1) e `IssuePasswordResetTokenIT` (7): `test-compile` falla con 28
errores `cannot find symbol`.

### VERDE

- `PasswordResetLinkSender`: `send(InstitutionId, StaffAccountId, PlainPasswordResetToken)`, único
  lugar por donde sale el token en claro. Sin adaptador de producción.
- `IssuePasswordResetTokenDecision`: `ISSUED`, `SKIPPED` y `ACCOUNT_NOT_FOUND`, sin el token.
- `IssuePasswordResetToken`: dentro de la transacción, `lockById`, conteo en la ventana, omisión
  auditada con `{"reason":"rate-limit","issuedInWindow":n}`, o `supersedeOpen`, `insert` con
  `policy.expiresAt(now)` y asiento `issued` con el id de la fila, el vencimiento y
  `supersededCount`, nunca el hash. **El envío ocurre después de `TransactionRunner.execute`.**

```
IssuePasswordResetTokenTest   tests=1 failures=0 errors=0
IssuePasswordResetTokenIT     tests=7 failures=0 errors=0
```

La primera ejecución falló en la aserción sobre `after_value`: `shared_audit_log` lo guarda como
JSONB y lo devuelve con espacios normalizados (`"supersededCount": 0`). Las aserciones ahora leen el
JSON en vez de comparar texto.

### Desviación de la tarea

La tarea pedía comprobar con dobles, en `IssuePasswordResetTokenTest`, que el envío ocurre después de
confirmar y nunca tras una reversión. `TransactionRunner` necesita una conexión real para aplicar el
contexto de seguridad, así que esa comprobación vive en `IssuePasswordResetTokenIT`:

- el doble de envío lee la fila del token **desde otro hilo**, en una transacción propia, y exige
  verla ya confirmada;
- un escritor de auditoría que lanza fuerza la reversión, y después el doble no recibió nada y la
  tabla está vacía.

### Control negativo

Con el envío movido dentro de la transacción, la primera versión de la prueba de «después de
confirmar» **siguió pasando**: el doble leía desde el mismo hilo, y `TransactionRunner` se unía a la
transacción aún abierta (`PROPAGATION_REQUIRED`) y veía la fila sin confirmar. Solo falló la prueba
de reversión. Se corrigió la prueba para leer desde otro hilo y se repitió el control: fallan las dos
(`theLinkIsSentOnlyAfterTheIssuingTransactionCommitted` y
`aRolledBackIssuanceSendsNothingAndLeavesNoToken`). El caso de uso se restauró.

### Escenarios

I3, I4, I5, I6 e I41.

---

## Tarea 3.3 — Sonda S3 y concurrencia de emisión

### Sonda S3

**PASA.** Contenedor `postgres:18-alpine` propio con una tabla de cuentas y otra de tokens. La
sesión A toma `SELECT … FOR UPDATE` sobre la cuenta, inserta un token y duerme 3 s antes de
confirmar; la sesión B toma el mismo bloqueo y cuenta:

```
B esperó 2,11 s por el bloqueo
locked|1
count|1
```

Bajo `READ COMMITTED`, el conteo de B ve el token que A confirmó mientras B esperaba. No hace falta
el respaldo `SERIALIZABLE`.

### Prueba

`PasswordResetConcurrencyIT`, mitad de emisión (2 pruebas):

- dos emisiones para la misma cuenta, liberadas a la vez por un `CyclicBarrier`, terminan las dos en
  `ISSUED`, con exactamente un token abierto y el otro superado;
- **control negativo**: con un `lockById` que no bloquea y una barrera que retiene a las dos
  emisiones justo antes de insertar, cuando ambas ya contaron y superaron, una emisión termina en
  `ISSUED` y la otra lanza una excepción cuyo `SQLState` es **`23505`**, el de la sonda S1. La emisión
  fallida no envió nada y queda un solo token abierto.

```
PasswordResetConcurrencyIT   tests=2 failures=0 errors=0
```

**Sin rojo propio.** Las dos pruebas pasaron en su primera ejecución: el orden de la decisión 2
(bloquear la cuenta antes de contar, superar e insertar) ya quedó implementado en la tarea 3.2, y la
tarea 3.3 pedía en verde solo «lo que la prueba exija». La segunda prueba es el control negativo:
demuestra que el índice parcial es una red real y que el bloqueo es lo que evita que salte.

### Escenarios

I7.

---

## Cierre de C3

- **`./mvnw -B clean verify`:** `BUILD SUCCESS` en 2 min 11 s. 186 pruebas del núcleo, 386
  unitarias y 203 de integración.
- **Diff medido** contra `main`, sin `openspec` ni código generado: **1334 líneas**. Se parte en dos
  pull requests encadenados, sin cambiar ninguna tarea:
  - **C3a** `change/password-recovery-token-c3a-request`: tarea 3.1, 576 líneas.
  - **C3b** `change/password-recovery-token-c3b-issuance`, sobre C3a: tareas 3.2 y 3.3, 758 líneas.

---

## Tarea 4.1 — `ResetPasswordWithToken` sin segundo factor activo

### ROJO

`ResetPasswordWithTokenTest` (7), `ResetPasswordWithTokenIT` (13) y la base abstracta
`PasswordResetIntegrationTest`: `test-compile` falla con 172 errores `cannot find symbol` sobre
`ResetPasswordWithToken`, `ResetPasswordCommand`, `SecondFactorProof`, `ResetPasswordDecision` y
`ResetOutcome`.

### VERDE

- `SecondFactorProof`: sellado, con `None`, `Totp(TotpCode)` y `RecoveryCode(PlainRecoveryCode)`.
- `ResetPasswordCommand`: clase final redactada, sin campo de institución.
- `ResetOutcome`: sellado, con exactamente `Completed`, `TokenRejected`,
  `PasswordRejected(reason)`, `SecondFactorMissing` y `SecondFactorRejected`.
- `ResetPasswordDecision(outcome, requiredDelay)`.
- `ResetPasswordWithToken`: guarda de institución y, en una transacción, los pasos 1 a 4 y 6 a 9 de
  la decisión 7. Un token mal formado se trata como inexistente, sin repetir su texto. La rama de
  MFA activa devuelve por ahora `SecondFactorMissing` ante cualquier prueba; se completa en 4.2.

```
ResetPasswordWithTokenTest   tests=7  failures=0 errors=0
ResetPasswordWithTokenIT     tests=13 failures=0 errors=0
```

`PasswordResetIntegrationTest` es abstracta, así que la regla de nombres `*IT` no la alcanza. Obtiene
cada token vivo de la emisión real y comprueba cada contraseña con el inicio de sesión real.

La primera ejecución falló en `aCompletedResetOnlyConsumesChangesTheHashAndAuditsWithoutNotifyingAnyone`:
la prueba suponía que `JooqAuditLogReader.pageOf` devolvía los asientos del más nuevo al más viejo,
pero los ordena por `id` ascendente. Se corrigió la prueba.

### Control negativo

Con `lockById` llamado antes de comprobar la longitud falla
`aPasswordOfTheWrongLengthNeitherLocksNorSpendsASecondFactorAttempt`. Se restauró.

### Escenarios

I11, I12, I15 (parte de integración), I16 (literales), I17, I18, I19, I23 (primera mitad), I28, I34,
I35, I36, I37, I38 e I43.

---

## Tarea 4.2 — Composición del segundo factor y contador TOTP compartido

### ROJO

`ResetPasswordWithSecondFactorIT` (3) y `PasswordResetTotpBackoffSharingIT` (2) contra el caso de uso
de 4.1, cuya rama de MFA activa respondía `SecondFactorMissing` ante cualquier prueba: **fallan las
cinco** por comportamiento, no por compilación.

### VERDE

El paso 5 de la decisión 7: con MFA activa, `None` da `SecondFactorMissing`; `Totp` llama a
`VerifyTotpCode.runWithinTransaction` y `RecoveryCode` a `ConsumeRecoveryCode.runWithinTransaction`,
nunca a sus `execute`. Un rechazo se **devuelve** (`SecondFactorRejected`, con el retardo exigible de
la verificación TOTP), así que `TransactionRunner` confirma el avance del contador y los asientos. El
asiento `completed` lleva `{"secondFactor":"totp"|"recovery-code"|"none"}`.

```
ResetPasswordWithSecondFactorIT     tests=3  failures=0 errors=0
PasswordResetTotpBackoffSharingIT   tests=2  failures=0 errors=0
ResetPasswordWithTokenIT            tests=13 failures=0 errors=0   (sin cambios)
```

`PasswordResetTotpBackoffSharingIT` cita en su Javadoc `BackoffPolicy.FIRST_DELAYED_ATTEMPT = 3`,
`CAP = 900 s`, `COUNTER_WINDOW = 30 min` y la clave `(institution_id, account_id)` de
`identity_mfa_totp_backoff`. Los fallos de 09:01 y 09:02 por el inicio de sesión y el de 09:03 por el
restablecimiento dan 1 s; el de 09:04 por el inicio de sesión, 2 s; el de 09:35 por el
restablecimiento, 0 s. Tras un rechazo a las 14:02, `consecutive_failures` vale 1, existe el asiento
`identity.mfa.totp_verification.failed` y el token completa a las 14:03.

### Desviación de la tarea

Los casos con MFA activa van en una clase propia, `ResetPasswordWithSecondFactorIT`, en vez de
ampliar `ResetPasswordWithTokenIT`. Así cada una puede ir en su propio pull request sin partir un
archivo.

### Control negativo

Con una variante local que **lanza** `IllegalStateException` ante un código TOTP incorrecto, fallan
las dos pruebas de `PasswordResetTotpBackoffSharingIT`: la excepción escapa del restablecimiento y la
transacción se revierte, avance del contador incluido. La variante se descartó sin comprometerla y el
archivo se comprobó byte a byte contra la versión en verde.

### Escenarios

I9, I10, I13, I14 e I46.

---

## Tarea 4.3 — Atomicidad y concurrencia del restablecimiento

### Pruebas

- `PasswordResetAtomicityIT` (1): un `AuditLogWriter` que lanza en el asiento `completed`, que el
  caso de uso escribe después de consumir el token y reescribir el hash. Un espía sobre el
  repositorio de tokens confirma que el consumo **sí ocurrió** antes del fallo (`[true]`), así que el
  verde no puede venir de un fallo prematuro. Tras la reversión, el token sigue vivo, la contraseña
  anterior autentica y no queda ningún asiento de restablecimiento.
- `PasswordResetConcurrencyIT`, mitad de restablecimiento (1): dos restablecimientos con el mismo
  token y contraseñas distintas, liberados a la vez, dan un `Completed` y un `TokenRejected`; solo
  autentica la contraseña del ganador y hay un único asiento `completed`. La clase pasa a extender
  `PasswordResetIntegrationTest`, sin sus ayudantes duplicados.

```
PasswordResetAtomicityIT     tests=1 failures=0 errors=0
PasswordResetConcurrencyIT   tests=3 failures=0 errors=0
```

**Sin rojo propio**, igual que en 3.3: las dos pruebas pasaron en su primera ejecución, porque el
orden de la decisión 7 (una sola transacción, consumo condicional antes de Argon2id) ya quedó
implementado en 4.1, y la tarea pedía en verde solo «lo que las pruebas exijan».

### Control negativo

Con el caso de uso ignorando el resultado de `consume` falla
`twoConcurrentResetsWithTheSameTokenProduceExactlyOneChange`: los dos restablecimientos cambian la
contraseña. Se restauró y se comprobó byte a byte.

### Escenarios

I8 e I20.

---

## Cierre de C4

- **`./mvnw -B clean verify`:** `BUILD SUCCESS` en 2 min 41 s. 186 pruebas del núcleo, 393
  unitarias y 223 de integración.
- **Diff medido** contra `main`, sin `openspec` ni código generado: **1667 líneas**. La tarea 4.1
  sola midió 1182 al cerrarse, así que el corte se parte en tres pull requests encadenados, sin
  cambiar ninguna tarea de nivel superior:
  - **C4a** `change/password-recovery-token-c4a-reset-core`: los tipos y el caso de uso de 4.1 con
    su prueba unitaria, 714 líneas. `clean verify` propio: 186, 393 y 203 pruebas.
  - **C4b** `change/password-recovery-token-c4b-reset-integration`, sobre C4a: la base
    `PasswordResetIntegrationTest` y `ResetPasswordWithTokenIT`, 468 líneas. `clean verify` propio:
    186, 393 y 216 pruebas.
  - **C4c** `change/password-recovery-token-c4c-second-factor`, sobre C4b: 4.2 y 4.3, y este
    registro, 497 líneas. Su árbol es idéntico al de la rama en la que se aplicó el corte completo.

---

## Tarea 5.1 — Redacción con control negativo e inventario de ausencias

### Entorno y estado previo del árbol

- El `JAVA_HOME` del sistema sigue apuntando al JDK 21; toda orden de Maven exporta
  `JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-25.0.3.9-hotspot"`.
- `main` estaba en `0f565d3`, pero el árbol de trabajo traía archivos **sin seguimiento** de un intento
  anterior y descartado de otra herramienta (clases de `admin`, `portal`, `worker`, `JwtAuthenticationFilter`,
  `apps/admin-web`, `apps/portal-web`, `backend/` y otros). Impedían compilar el módulo
  (`org.springframework.security` no está en el classpath). Se apartaron sin borrarlos con
  `git stash push -u` (`gemini-c5-leftovers-untracked`); no forman parte de este cambio. Quedan sin
  seguimiento, sin tocar, `openspec/changes/archive/2026-10-02-*`, `openspec/specs/{admin-web,backend,bootstrap,portal-web}`,
  `packages/contracts/src/{admin,portal}` y `screenshot.png`.

### Pruebas

- `IdentitySecretRedactionIT` (+2): una emisión y un restablecimiento reales, más un segundo intento con
  el token ya consumido. Se barren los `toString()` del token, su hash, el comando, la contraseña nueva y
  su hash Argon2id, los mensajes de tres excepciones y los asientos de auditoría. El hash SHA-256 y el hash
  Argon2id se barren contra todo salvo la tabla que los guarda; el token y la contraseña en claro, también
  contra las filas de `identity_password_reset_token`. La segunda prueba es el control negativo permanente,
  contra `LeakingPasswordResetTokenFixture` (un `record` sin `toString()` redactado).
- `IdentityScopeExclusionInventoryTest` (+6): ninguna clase de producción de `com.confia` implementa
  `PasswordResetIssuanceScheduler` ni `PasswordResetLinkSender` (I25, I27); solo `ResetPasswordWithToken`
  llama a `StaffAccountRepository.replacePasswordHash` (I33); ninguna clase de `com.confia.identity`
  nombra sesiones ni tokens de refresco (I23, segunda mitad aparte). Cada regla tiene una prueba gemela contra
  un accesorio permanente de `PasswordRecoveryScopeViolationFixtures`, que la viola.
  Limitación documentada: la regla de los puertos ve clases que los implementan, no una lambda.

### ROJO

- Redacción: con las pruebas escritas y sin el accesorio, la compilación de pruebas falla
  (`cannot find symbol: class LeakingPasswordResetTokenFixture`, 3 errores). Es un rojo de compilación; el
  rojo de comportamiento es el control negativo de abajo.
- Inventario: las reglas no tienen rojo propio contra producción limpia, porque la ausencia ya es cierta.
  Su rojo es el control negativo.

### VERDE

Sin código de producción, como preveía la tarea.

```
IdentitySecretRedactionIT              tests=5  failures=0 errors=0
IdentityScopeExclusionInventoryTest    tests=12 failures=0 errors=0
```

### Controles negativos (variantes locales, descartadas sin comprometer)

1. **Redacción.** Con `PlainPasswordResetToken.toString()` devolviendo `"PlainPasswordResetToken[" + value + "]"`
   fallan dos pruebas: el barrido
   (`produced text unexpectedly contains the clear-text password-reset token (43 characters)`, sin imprimir
   el valor) y la comprobación de que el token real imprime redactado. Restaurado con `git checkout`.
2. **Inventario.** Una clase temporal de producción en `identity.infrastructure` que implementa los dos
   puertos, llama a `replacePasswordHash` y declara un método `revokeRefreshTokens` hace fallar exactamente
   las tres pruebas de producción (`noProductionClassImplementsTheIssuanceSchedulerOrTheLinkSender`,
   `onlyResetPasswordWithTokenCallsReplacePasswordHash`, `noClassOfIdentityReferencesSessionsOrRefreshTokens`),
   con 12 pruebas ejecutadas y 3 fallos. La clase se borró, incluido su `.class` en `target`.

### Escenarios

I21, I22, I23 (primera mitad), I25, I27 e I33.

---

## Tarea 5.2 — Documentación, pruebas existentes de las brechas y trazabilidad

**No hay rojo que observar en la parte documental**, y se dice así: son notas fechadas sin código. El
único cambio de código es el texto de un mensaje de aserción de 5.1 (el destino de la brecha se nombra
como «cuarta parte del cambio 7», no «cambio 15», que es `process-entry-point-isolation`).

### Notas fechadas (2026-10-03), sin reescribir el cuerpo de ninguna sección

- `openspec/changes/foundations-plan/exploration.md`: las dos condiciones duras de
  `session-tokens-and-web-layer` (revocar todas las familias al restablecer; no fusionar el endpoint de
  solicitud sin el límite de 10 por hora por IP), junto a la que ya existía para `identity_login_backoff`.
- `docs/03-seguridad.md` §4.7: tabla, emisión en el trabajador, definición de MFA activa, SHA-256 del texto
  base64url, retroceso compartido y pendientes con dueño. §6.1: adenda de privilegios de la tabla.
- `docs/08-datos-privacidad-y-retencion.md`: nota tras la tabla de la línea 322, filas retenidas hasta el
  cambio 9.
- `docs/09-roadmap-y-fases.md` §3: pendientes heredados de la parte 3, con los seis dueños.

### Pruebas existentes, sin tocarlas

```
AuthenticateWithPasswordTest#aSuccessfulLoginNeverRecalculatesTheStoredHash                      tests=1 failures=0  (I44)
IdentityScopeExclusionInventoryTest#noProductionClassOfIdentityDependsOnAnyNetworkClientLibrary  tests=1 failures=0  (I42)
ConsumeRecoveryCodeIT#consumingTheEighthCodeAuditsTheLowSignalWithoutSendingAnyEmail             tests=1 failures=0  (I45)
```

### I24, condición escrita

Sin prueba en este cambio. Su prueba, que el restablecimiento revoca las familias de refresco cuando
exista la primera emisión, es de `session-tokens-and-web-layer` y consta como condición dura en
`foundations-plan/exploration.md`. `IdentityScopeExclusionInventoryTest.noClassOfIdentityReferencesSessionsOrRefreshTokens`
(5.1) fallará ese día, a propósito.

### Trazabilidad

La tabla de «Cierre de la trazabilidad contra las clases reales» de `tasks.md` recoge las diferencias entre
lo que decía `design.md` §6.1 y las clases reales. Se comprobó por lectura que cada clase que §6.1 nombra
existe en el árbol de pruebas.
