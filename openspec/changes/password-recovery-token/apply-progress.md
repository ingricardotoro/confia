# Progreso de aplicación: `password-recovery-token`

- **Corte en curso:** C1.
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
