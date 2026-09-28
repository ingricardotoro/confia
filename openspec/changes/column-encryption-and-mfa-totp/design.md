# Diseño: cifrado de columna y MFA con TOTP

Cambio 7 de F0, **segundo de cuatro** en que se ejecuta el cambio 7 tras el tercer corte del
2026-09-27. Implementa la propuesta aprobada el 2026-09-28 (ocho decisiones D1-D8 resueltas) y los
dos deltas ya escritos: `specs/identity/spec.md` (12 requisitos, 21 escenarios) y
`specs/build-integrity/spec.md` (2 requisitos, 6 escenarios). **Las especificaciones son el
contrato**: donde este diseño necesite un ajuste de redacción sobre un delta ya escrito, lo dice de
forma explícita; en este cambio, a diferencia de la parte 1, **ningún ajuste resultó necesario**
(sección 0).

Decisiones del propietario que este diseño **implementa y no reabre**: D1 (llave maestra, precedente
literal de `Argon2Pepper`), D2 (rotación real de la DEK es brecha con destino en el cambio 9), D3
(motor puro en `kernel`, gestión de tabla de llaves en `com.confia.shared.crypto` con
`@NamedInterface`), D4 (ADR-0023, alcance exacto), D5 (puerto nuevo `RecoveryCodeHasher`, no se
reabre `PasswordHasher`), D6 (sin búsqueda determinista por HMAC, sin consumidor), D7 y D8
(límite de tasa TOTP y aviso de códigos bajos, ambos se añaden como requisito).

Todo lo que sigue se verificó por **lectura del árbol real**: código de
`apps/api/app/src/main/java/com/confia/identity/`, `com/confia/shared/security/`,
`com/confia/shared/audit/`, `com/confia/shared/infrastructure/`, `apps/api/app/src/test/java/com/
confia/architecture/`, las migraciones `V1` a `V5`, `docs/03-seguridad.md` (§4.1, §4.3, §4.6, §6.1,
§6.2, §7.3), ADR-0009, ADR-0017, ADR-0018, ADR-0019, ADR-0022, y el `design.md` archivado de la
parte 1. Esta fase **no dispuso de herramienta de ejecución de procesos** (ni Maven, ni Docker, ni
un compilador invocable fuera de lo que describo como sonda). Toda afirmación que dependa de
ejecutar algo está marcada como sonda en la sección 10, con su comando exacto; **nada se afirma como
comprobado sin haberlo leído o sin dejarlo como sonda**.

---

## 0. Los deltas no necesitan ningún ajuste

A diferencia de la parte 1 (que tuvo que corregir `specs/build-integrity/spec.md` de una tabla a
dos), este diseño no encontró ninguna discrepancia entre lo que los dos deltas ya escritos exigen y
lo que es implementable. El delta de `build-integrity` describe las cuatro tablas nuevas en
**lenguaje genérico** («la tabla de llaves de datos», «la tabla de credencial TOTP», «la tabla de
códigos de recuperación de MFA», «la tabla de retroceso de verificación TOTP», con sus dos
prefijos `shared_` e `identity_`) sin fijar un nombre literal — es exactamente lo que este diseño
existe para fijar (sección 3), sin que haga falta reabrir el delta para hacerlo encajar.

---

## 1. Enfoque técnico

De adentro hacia afuera, en **cinco cortes encadenados** dentro de este mismo cambio SDD (la
propuesta ya declaró que no hay más margen de dividir en otro cambio; sección 11). Cada corte deja
`./mvnw verify` en verde y sigue **TDD estricto** (`openspec/config.yaml`, ejecutor `./mvnw verify`
en `apps/api`): rojo observado y registrado antes de cada verde.

| Corte | Contenido |
|---|---|
| **C1** | ADR-0023; motor de cifrado puro en `kernel`; puerto y adaptador de la tabla de llaves en `com.confia.shared.crypto` / `com.confia.shared.infrastructure`; migración `V6` con las **cuatro** tablas y la columna `mfa_required`; extensión de `RolePrivilegeMatrixIT` y `MultiTenantSchemaIT` |
| **C2** | TOTP: secreto, algoritmo RFC 6238 puro, política de verificación con ventana ±1 y anti-repetición, cifrado del secreto, límite de tasa (D7) reutilizando `BackoffPolicy`/`BackoffState` |
| **C3** | Códigos de recuperación de MFA: `RecoveryCodeHasher` (D5), generación y consumo de un solo uso, aviso de códigos bajos (D8) |
| **C4** | Edición de `AuthenticationResult` (cuatro desenlaces), conversión del `switch` exhaustivo en `AuthenticateWithPassword`, columna `mfa_required` consumida, mecanismo de compilación fallida sobre fixture |
| **C5** | Notas editoriales fechadas (`docs/03` §4.3, §7.3, adenda a §6.1; `docs/09`), barrido final de redacción de secretos con control negativo, cierre de trazabilidad |

### 1.1 El problema que gobierna el resto del diseño

Cuatro secretos nuevos —el secreto TOTP en claro, el código de recuperación de MFA en claro, la
llave maestra (KEK) y la llave de datos (DEK) en claro— y **tres revisiones de la parte 1 ya
encontraron un hallazgo bloqueante cada una, y las tres fueron fugas de secretos** (proposal.md,
«Restricciones que este cambio no puede violar», punto 1). El más instructivo fue estructural, no de
descuido: un `record` de Java genera `toString()` sobre **todos** sus componentes, y la prueba
escrita para cazarlo no llamaba a `toString()` sobre el objeto que filtraba. Este diseño trata la
redacción de los cuatro secretos nuevos como una decisión de arquitectura con su propio mecanismo de
verificación (decisión 9), no como una nota de estilo.

El segundo problema que gobierna el diseño es el que la propuesta ya nombró en voz alta: el Javadoc
de `AuthenticationResult` promete una exhaustividad del compilador que hoy **no existe**, porque los
dos únicos consumidores de producción son `instanceof`. Este cambio construye esa garantía de
verdad, y **la demuestra fallando algo a propósito** (decisión 6): un fixture permanente cuya
compilación debe romperse, porque esa es literalmente la forma del escenario publicado («la
compilación de ese fixture falla, porque el switch deja de ser exhaustivo»).

---

## 2. Evidencia obtenida en esta fase y límites

| Pregunta | Resultado | Evidencia |
|---|---|---|
| ¿Los dos `instanceof` de `AuthenticateWithPassword` siguen sin ser un `switch`? | **Verificado: sí, siguen siendo `instanceof`** en `runWithinTransaction` (comparación de `result` en la línea del cálculo del estado nuevo de retroceso) y en `outcomeEntry` (comparación para decidir `action`/`outcome`/`afterValue`) | `AuthenticateWithPassword.java:155,175` (líneas reales tras lectura completa del archivo) |
| ¿`AuthenticationResult` sigue teniendo exactamente dos desenlaces? | **Verificado: sí** — `permits Authenticated, Rejected` | `AuthenticationResult.java:20` |
| ¿Existe hoy algún concepto de rol o de permiso en el árbol? | **Verificado: no.** `identity_staff_account` tiene cinco columnas: `institution_id`, `id`, `email`, `password_hash`, `created_at`. Ninguna de rol | `V5__create_identity_staff_account.sql`, listado del esquema |
| ¿Cuántas migraciones hay hoy? | **Verificado: cinco** (`V1`-`V5`). La nueva es `V6`, ya fijada por la propuesta | Listado de `db/migration/` |
| ¿`shared.security` y `shared.audit` ya llevan `@NamedInterface`? | **Verificado: sí, los dos**, por el cambio 7 parte 1 (ADR-0022) | `package-info.java` de ambos paquetes |
| ¿`com.confia.shared.crypto` existe ya? | **Verificado: no.** Es un paquete nuevo de este cambio | Listado de `com/confia/shared/` |
| ¿Qué prefijo de tabla exige `TableOwnershipByModuleTest` para una clase que consume jOOQ desde un paquete `com.confia.shared.infrastructure`? | **Verificado: `Shared`.** `moduleOf(...)` extrae el segmento **inmediatamente anterior** al primer segmento de capa (`domain`/`application`/`infrastructure`/`web`); para `com.confia.shared.infrastructure.JooqAuditLogWriter` ese segmento es `shared`, y el prefijo esperado es `Shared` — exactamente el que ya lleva `SharedAuditLog` y `SharedIdempotencyKey` | `TableOwnershipByModuleTest.java:89-97`; `JooqAuditLogWriter`/`JooqIdempotencyRecordStore`, ambos en `com.confia.shared.infrastructure` |
| ¿Y si el adaptador de la tabla de llaves viviera en `com.confia.shared.crypto.infrastructure` en vez de `com.confia.shared.infrastructure`? | **Verificado por lectura de la misma regla: el prefijo exigido pasaría a ser `Crypto`, no `Shared`.** El segmento inmediatamente anterior a `infrastructure` sería `crypto`, no `shared`. Esto **contradice** la decisión D3 de la propuesta, que fija el prefijo de tabla en `shared_` | Misma regla, misma extracción; decisión 2 de este documento resuelve la consecuencia |
| ¿`com.confia.shared.crypto` necesitaría capas propias (`domain`/`application`/`infrastructure`)? | **No, por diseño** (decisión 2): sigue el patrón exacto de `shared.security` y `shared.audit`, que no llevan segmento de capa y por tanto quedan fuera de `LayeredArchitectureTest` (design.md parte 1, decisión 12) | `shared/security/package-info.java:9-14`; `shared/audit/package-info.java:8-16` |
| ¿`javax.crypto` del JDK expone AES-256-GCM sin proveedor externo? | **No verificado por ejecución** (esta fase no puede compilar ni ejecutar). Verificado por lectura: la propuesta y la exploración lo anotan como hecho conocido de la JDK estándar (`Cipher.getInstance("AES/GCM/NoPadding")`, `GCMParameterSpec`), y **no aparece en la lista de dependencias prohibidas del enforcer** ni requiere una nueva. Queda como sonda **S1** con su comando exacto porque el detalle fino —si `doFinal` concatena la etiqueta al texto cifrado y hay que partirla para el formato de cinco partes— sí importa y no se puede confirmar leyendo | `apps/api/pom.xml:367-384` (lista de bloqueadas, no la incluye); sonda S1 |
| ¿`Mac.getInstance("HmacSHA1")` está disponible sin dependencia nueva? | **Verificado por precedente: sí.** El propio módulo ya usa `Mac.getInstance("HmacSHA256")` del JDK puro para la huella del identificador, sin ninguna biblioteca adicional; `HmacSHA1` es el mismo proveedor estándar (`SunJCE`) | `HmacLoginIdentifierFingerprinter.java:10-11,46` |
| ¿El patrón `INSERT ... ON CONFLICT DO UPDATE ... RETURNING` para reclamar y bloquear una fila ya está en producción? | **Verificado: sí**, en `JooqLoginBackoffStore.claim(...)`, con su sonda S3 de la parte 1 ya resuelta contra `postgres:18-alpine` (devuelve el estado previo y toma el bloqueo de fila) | `JooqLoginBackoffStore.java:47-65` |
| ¿El patrón `SELECT ... FOR UPDATE` ya está en producción? | **Verificado: sí**, en `JooqIdempotencyRecordStore.lockExisting(...)` (cambio 6) | `JooqIdempotencyRecordStore.java:52-63` |
| ¿`BackoffPolicy`/`BackoffState` son genéricos, reutilizables fuera del login? | **Verificado: sí.** Ninguno de los dos menciona contraseñas ni identificadores de sesión; operan sobre `consecutiveFailures` y `lastAttemptAt`, con el reloj inyectado desde fuera | `BackoffPolicy.java`, `BackoffState.java` |
| ¿ArchUnit (la versión fijada en este repo) puede detectar un `instanceof` sobre un tipo dado como una regla de arquitectura, para prohibir la regresión a `instanceof` sobre `AuthenticationResult`? | **NO verificado.** Las reglas existentes del árbol (`NoBlockingWaitInIdentityTest`, `TableOwnershipByModuleTest`) detectan **llamadas a método** y **dependencias de tipo generado**, nunca una comprobación `instanceof`. No hay precedente en el árbol de una regla que inspeccione ese patrón de bytecode. Sonda **S4**, no bloqueante — decisión 6 explica por qué el mecanismo principal no depende de que S4 resulte posible |
| ¿`ADR-0023` es el siguiente número libre? | **Verificado: sí.** `docs/adr/` contiene `ADR-0001` a `ADR-0022` sin huecos | Listado de `docs/adr/*.md` |
| ¿El catálogo cerrado de ADR-0017 admite una quinta excepción? | **Verificado: no, y no hace falta.** El catálogo tiene exactamente cuatro entradas y dice «ninguna otra tabla puede exceptuarse sin un ADR nuevo»; la tabla de llaves de datos no la necesita, porque **sigue la regla general** de ADR-0009 (confirmado también por la propuesta, sección «catálogo cerrado») | `ADR-0017-tablas-tecnicas-y-tabla-raiz.md:84-95` |
| ¿`docs/03-seguridad.md` §7.3 y §4.3 nombran una tabla `data_key`/`user_mfa` que no coincide con el prefijo de módulo? | **Verificado: sí.** §7.3 dice «Secreto TOTP \| `user_mfa` \|…» y «almacenada cifrada en tabla `data_key`». Ninguno de los dos nombres lleva prefijo de módulo | `docs/03-seguridad.md:899,913` |

**Consecuencia de método.** Donde una decisión depende de un comportamiento no comprobado por
lectura, este diseño nombra la sonda exacta en la sección 10 y dice qué corte bloquea.

---

## 3. Decisiones de arquitectura

### Decisión 1 — Los nombres literales de las cuatro tablas y la columna

| Tabla | Prefijo | Vive gestionada por | Fila clave |
|---|---|---|---|
| `shared_data_encryption_key` | `shared_` | `com.confia.shared.crypto` / adaptador en `com.confia.shared.infrastructure` | `(institution_id, id)` |
| `identity_mfa_totp_credential` | `identity_` | `com.confia.identity.application` / `com.confia.identity.infrastructure` | `(institution_id, account_id)` |
| `identity_mfa_recovery_code` | `identity_` | ídem | `(institution_id, id)` |
| `identity_mfa_totp_backoff` | `identity_` | ídem | `(institution_id, account_id)` |

Más la columna `identity_staff_account.mfa_required BOOLEAN NOT NULL`, ya fijada por la propuesta.

**Por qué `shared_data_encryption_key` y no `data_key`** (el nombre literal de `docs/03` §7.3).
`docs/03` usa un nombre conceptual sin prefijo de módulo, igual que llamó `user` a lo que se entregó
como `identity_staff_account` en la parte 1. La regla 3 de ADR-0015 exige el prefijo del módulo
propietario, y D3 de la propuesta ya fijó que la propietaria de la tabla es
`com.confia.shared.crypto` — de ahí `shared_`, no `crypto_`: **el módulo Spring Modulith es
`shared`**, `crypto` es solo un sub-paquete suyo (como `security` y `audit`), y ADR-0015 regla 3 dice
literalmente «el módulo `shared` es dueño del prefijo `Shared`». Esto exige una nota editorial sobre
§7.3 (decisión 10).

**Por qué `identity_mfa_totp_credential` y no `user_mfa`** (el nombre literal de `docs/03` §4.3 y
§7.3). Mismo razonamiento: `user_mfa` no lleva ningún prefijo de módulo, y el dato pertenece a
`identity`. Se elige `identity_mfa_totp_credential` sobre alternativas más cortas como
`identity_mfa_secret` porque la tabla no solo guarda el secreto: guarda también el contador de
anti-repetición (decisión 7), así que «credencial» describe mejor su contenido completo que
«secreto».

**Por qué `identity_mfa_recovery_code`, no `identity_mfa_recovery_token`.** El delta y la propuesta
llaman **código** al valor de diez caracteres, y reservan **token** para el mecanismo de
`password-recovery-token`, que es un cambio distinto con su propia decisión de hasheo. Usar «code»
en el nombre de la tabla evita que alguien confunda las dos tablas al leer el catálogo, incluso
antes de que `password-recovery-token` exista.

**Por qué `identity_mfa_totp_backoff`, no `identity_mfa_backoff` a secas.** El límite de tasa (D7)
es específico de la **verificación del código TOTP**, no de los códigos de recuperación de MFA — un
código de recuperación fallido no está sujeto a este control (el requisito publicado no lo exige, y
la propuesta no lo extiende). El nombre lo deja sin ambigüedad para cuando exista otra clase de
retroceso de MFA en el futuro.

**Regla 3 de ADR-0015 y regla general de ADR-0009, en las cuatro**: `institution_id NOT NULL`,
restricción única con ese discriminador (la clave primaria compuesta ya lo satisface en las cuatro),
seguridad de fila habilitada **y forzada**, `REVOKE ALL ... FROM PUBLIC` antes de cualquier `GRANT`.
Ninguna de las cuatro entra en el catálogo cerrado de ADR-0017 (confirmado en la sección 2, sin
decisión que tomar).

### Decisión 2 — Dónde vive cada pieza del cifrado, y por qué el adaptador jOOQ no vive en `shared.crypto`

**Elección**, con cuatro capas exactas:

1. **`com.confia.kernel`** — el motor de cifrado puro y el códec del formato de sobre. Sin acceso a
   base de datos, sin excepción a la pureza de JDK que `kernel` exige (`build-integrity`, «Pureza
   del módulo `kernel`»):
   - `AesGcmCipher` — bytes a bytes: `encrypt(key, iv, aad, plaintext)` y
     `decrypt(key, iv, aad, ciphertextWithTag)`, sobre `javax.crypto.Cipher` con
     `"AES/GCM/NoPadding"`.
   - `EncryptedColumnValue` — formatea y analiza la cadena `v1:<id_dek>:<iv_b64>:<ciphertext_b64>
     :<tag_b64>` de `docs/03` §7.3, sin conocer qué es una DEK ni de dónde sale: solo un
     identificador de texto, tres arreglos de bytes en base64 y el separador `:`.
2. **`com.confia.shared.crypto`** — paquete nuevo, **sin segmento de capa** (mismo patrón que
   `shared.security` y `shared.audit`, verificado en la sección 2: `LayeredArchitectureTest` lo trata
   como fuera de la arquitectura en capas por no llevar `domain`/`application`/`infrastructure`), con
   `@NamedInterface` en su `package-info` desde este mismo corte:
   - Puerto `DataEncryptionKeyRepository` — `findActiveOrCreate(InstitutionId)`,
     `findById(InstitutionId, DataEncryptionKeyId)`. Es el único punto que toca la tabla de llaves.
   - Objetos de valor con `toString()` redactado: `DataEncryptionKeyMaterial` (los 32 bytes de la
     DEK ya desenvuelta) y `ColumnEncryptionMasterKey` (la KEK, precedente literal de `Argon2Pepper`,
     D1).
   - `ColumnEncryptionService` — clase **final, concreta**, no una interfaz con adaptador, por la
     misma razón que `TransactionRunner` no tiene un segundo puerto: solo hay un algoritmo posible
     (AES-256-GCM, ADR-0023) y ninguna prueba necesita sustituirla por un doble — compone
     `DataEncryptionKeyRepository` con el motor puro de `kernel`. Expone `encryptForNewValue(table,
     column, institutionId, rowId, plaintext)` (resuelve la DEK activa) y `decrypt(table, column,
     institutionId, rowId, storedValue)` (analiza `id_dek` del valor y resuelve esa DEK exacta, activa
     o retirada).
3. **`com.confia.shared.infrastructure`** — el adaptador jOOQ, **directamente en este paquete
   existente, sin un sub-paquete `crypto.infrastructure` nuevo**. `JooqDataEncryptionKeyRepository`
   implementa `DataEncryptionKeyRepository`, usa `AesGcmCipher` para envolver/desenvolver la DEK bajo
   la KEK, y es el primer y único consumidor jOOQ de `shared_data_encryption_key`.
4. **`com.confia.identity.application`** y **`com.confia.identity.domain`** — los casos de uso
   nuevos consumen `ColumnEncryptionService` (composición directa, igual que consumen
   `TransactionRunner`) para cifrar el secreto TOTP antes de persistirlo y descifrarlo antes de
   verificar.

**Por qué el punto 3 es una decisión y no un detalle de estilo.** La sección 2 ya verificó por
lectura que si el adaptador viviera en `com.confia.shared.crypto.infrastructure` (la ubicación que
uno escribiría primero, por paralelismo con `identity.infrastructure`), `TableOwnershipByModuleTest`
exigiría el prefijo `Crypto`, no `Shared` — porque `moduleOf(...)` extrae el segmento
**inmediatamente anterior** al primer segmento de capa, y ese segmento sería `crypto`, no `shared`.
Eso **contradiría D3 de la propuesta**, que ya fijó el prefijo `shared_` para esta tabla. La única
forma de que el adaptador jOOQ produzca el prefijo `Shared` que D3 exige es que viva directamente en
`com.confia.shared.infrastructure`, sin un sub-paquete de capa propio para `crypto` — exactamente
donde ya viven `JooqAuditLogWriter` (para el puerto de `shared.audit`) y
`JooqIdempotencyRecordStore` (para el puerto de `shared.security`). El patrón ya existía dos veces
en el árbol; este cambio es la tercera aplicación, no una excepción.

**Alternativas descartadas:**

| Opción | Por qué se descarta |
|---|---|
| Adaptador en `com.confia.shared.crypto.infrastructure` | Produce el prefijo `Crypto`, contradice D3. Habría que reabrir D3 o vivir con un nombre de tabla que no coincide con su prefijo exigido — ninguna de las dos es aceptable |
| Adaptador en `com.confia.identity.infrastructure` | La propia propuesta ya lo descartó: ata un mecanismo transversal a un solo módulo de negocio cuando `docs/03` §7.3 ya nombra consumidores futuros ajenos a `identity` |
| `DataEncryptionKeyRepository` con adaptador jOOQ **y** un puerto/adaptador separado para `ColumnEncryptionService` | Sobre-ingeniería: no hay ni un segundo algoritmo de cifrado que sustituir ni una prueba que necesite doblar la orquestación en sí misma, solo el acceso a la tabla. Se dobla `DataEncryptionKeyRepository`, no `ColumnEncryptionService` |

### Decisión 3 — DDL completo de `V6` y la matriz de privilegios

```sql
-- Sexta migración (F0 change 7, corte C1; design.md decisiones 1, 2 y 3;
-- specs/build-integrity/spec.md). Añade mfa_required y crea las cuatro tablas de este cambio.

ALTER TABLE identity_staff_account ADD COLUMN mfa_required BOOLEAN NOT NULL;
-- Sin DEFAULT: cada fila existente en el árbol de pruebas se siembra explícitamente (no hay
-- entorno desplegado ni dato real, ADR-0008), y una columna NOT NULL sin default sobre datos reales
-- exigiría backfill explícito que este cambio no tiene que resolver.

CREATE TABLE shared_data_encryption_key (
    institution_id UUID        NOT NULL,
    id             UUID        NOT NULL,
    status         TEXT        NOT NULL,
    wrapped_key    TEXT        NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT shared_data_encryption_key_pk         PRIMARY KEY (institution_id, id),
    CONSTRAINT shared_data_encryption_key_status_chk CHECK (status IN ('active', 'retired')),
    CONSTRAINT shared_data_encryption_key_wrapped_chk CHECK (wrapped_key ~ '^[A-Za-z0-9+/=]+:[A-Za-z0-9+/=]+:[A-Za-z0-9+/=]+$')
);

-- Como mucho una DEK activa por institución (soporta la creación perezosa idempotente de la
-- decisión 4, "ON CONFLICT (institution_id) WHERE status = 'active' DO NOTHING", sonda S5). Es un
-- ÍNDICE, no una restricción PRIMARY KEY/UNIQUE de pg_constraint: MultiTenantSchemaIT verifica
-- restricciones únicas por catálogo de pg_constraint (design.md parte 1, sección 2, fila sobre
-- índices de expresión) y es POSIBLE que no vea este índice parcial — se declara así en vez de
-- afirmar que la puerta lo cubre. No sustituye la restricción de la clave primaria, que ya incluye
-- institution_id y satisface ADR-0009 regla 2 por sí sola.
CREATE UNIQUE INDEX shared_data_encryption_key_one_active_per_institution
    ON shared_data_encryption_key (institution_id) WHERE status = 'active';

CREATE TABLE identity_mfa_totp_credential (
    institution_id        UUID        NOT NULL,
    account_id            UUID        NOT NULL,
    encrypted_secret      TEXT        NOT NULL,
    last_accepted_counter BIGINT      NOT NULL DEFAULT -1,
    enrolled_at           TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT identity_mfa_totp_credential_pk          PRIMARY KEY (institution_id, account_id),
    CONSTRAINT identity_mfa_totp_credential_account_fk  FOREIGN KEY (institution_id, account_id)
        REFERENCES identity_staff_account (institution_id, id),
    CONSTRAINT identity_mfa_totp_credential_secret_chk  CHECK (encrypted_secret LIKE 'v1:%'),
    CONSTRAINT identity_mfa_totp_credential_counter_chk CHECK (last_accepted_counter >= -1)
);

CREATE TABLE identity_mfa_recovery_code (
    institution_id UUID        NOT NULL,
    account_id     UUID        NOT NULL,
    id             UUID        NOT NULL,
    code_hash      TEXT        NOT NULL,
    used_at        TIMESTAMPTZ,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT identity_mfa_recovery_code_pk         PRIMARY KEY (institution_id, id),
    CONSTRAINT identity_mfa_recovery_code_account_fk FOREIGN KEY (institution_id, account_id)
        REFERENCES identity_staff_account (institution_id, id),
    CONSTRAINT identity_mfa_recovery_code_hash_chk   CHECK (code_hash LIKE '$argon2id$%')
);

CREATE INDEX identity_mfa_recovery_code_account_idx
    ON identity_mfa_recovery_code (institution_id, account_id) WHERE used_at IS NULL;
-- Índice parcial de lectura (no de unicidad): la consulta de "códigos sin usar de esta cuenta"
-- es el camino caliente de la inscripción y del consumo (decisión 8), y filtrar por used_at IS NULL
-- en el índice evita escanear códigos ya gastados de cuentas antiguas.

CREATE TABLE identity_mfa_totp_backoff (
    institution_id       UUID        NOT NULL,
    account_id           UUID        NOT NULL,
    consecutive_failures INTEGER     NOT NULL DEFAULT 0,
    last_attempt_at      TIMESTAMPTZ NOT NULL,
    CONSTRAINT identity_mfa_totp_backoff_pk          PRIMARY KEY (institution_id, account_id),
    CONSTRAINT identity_mfa_totp_backoff_account_fk  FOREIGN KEY (institution_id, account_id)
        REFERENCES identity_staff_account (institution_id, id),
    CONSTRAINT identity_mfa_totp_backoff_failures_chk CHECK (consecutive_failures >= 0)
);

ALTER TABLE shared_data_encryption_key   ENABLE ROW LEVEL SECURITY;
ALTER TABLE shared_data_encryption_key   FORCE  ROW LEVEL SECURITY;
ALTER TABLE identity_mfa_totp_credential ENABLE ROW LEVEL SECURITY;
ALTER TABLE identity_mfa_totp_credential FORCE  ROW LEVEL SECURITY;
ALTER TABLE identity_mfa_recovery_code   ENABLE ROW LEVEL SECURITY;
ALTER TABLE identity_mfa_recovery_code   FORCE  ROW LEVEL SECURITY;
ALTER TABLE identity_mfa_totp_backoff    ENABLE ROW LEVEL SECURITY;
ALTER TABLE identity_mfa_totp_backoff    FORCE  ROW LEVEL SECURITY;

CREATE POLICY shared_data_encryption_key_institution_isolation ON shared_data_encryption_key
    USING      (institution_id = NULLIF(current_setting('app.institution_id', true), '')::uuid)
    WITH CHECK (institution_id = NULLIF(current_setting('app.institution_id', true), '')::uuid);

CREATE POLICY identity_mfa_totp_credential_institution_isolation ON identity_mfa_totp_credential
    USING      (institution_id = NULLIF(current_setting('app.institution_id', true), '')::uuid)
    WITH CHECK (institution_id = NULLIF(current_setting('app.institution_id', true), '')::uuid);

CREATE POLICY identity_mfa_recovery_code_institution_isolation ON identity_mfa_recovery_code
    USING      (institution_id = NULLIF(current_setting('app.institution_id', true), '')::uuid)
    WITH CHECK (institution_id = NULLIF(current_setting('app.institution_id', true), '')::uuid);

CREATE POLICY identity_mfa_totp_backoff_institution_isolation ON identity_mfa_totp_backoff
    USING      (institution_id = NULLIF(current_setting('app.institution_id', true), '')::uuid)
    WITH CHECK (institution_id = NULLIF(current_setting('app.institution_id', true), '')::uuid);

REVOKE ALL ON shared_data_encryption_key   FROM PUBLIC;
REVOKE ALL ON identity_mfa_totp_credential FROM PUBLIC;
REVOKE ALL ON identity_mfa_recovery_code   FROM PUBLIC;
REVOKE ALL ON identity_mfa_totp_backoff    FROM PUBLIC;

GRANT SELECT, INSERT, UPDATE ON shared_data_encryption_key   TO confia_admin_app;
GRANT SELECT, INSERT, UPDATE ON identity_mfa_totp_credential TO confia_admin_app;
GRANT SELECT, INSERT, UPDATE ON identity_mfa_recovery_code   TO confia_admin_app;
GRANT SELECT, INSERT, UPDATE ON identity_mfa_totp_backoff    TO confia_admin_app;

GRANT SELECT ON shared_data_encryption_key   TO confia_readonly;
GRANT SELECT ON identity_mfa_totp_credential TO confia_readonly;
GRANT SELECT ON identity_mfa_recovery_code   TO confia_readonly;
GRANT SELECT ON identity_mfa_totp_backoff    TO confia_readonly;

-- confia_portal_app: ningún GRANT sobre ninguna de las cuatro. Son datos de personal y de
-- infraestructura de cifrado transversal; §6.1 ya excluye explícitamente al portal de "user" y de
-- shared_audit_log, y el mismo trato se extiende aquí.
-- confia_owner: propietario del esquema, sin BYPASSRLS.
-- confia_backup: lee por pg_read_all_data, que no omite seguridad de fila.
```

Puntos que no son obvios:

1. **`mfa_required` sin `DEFAULT`.** No hay entorno desplegado ni dato real (plan de reversión de la
   propuesta), así que no hace falta un valor de relleno para filas existentes: cada prueba de
   integración siembra su propia cuenta con el valor explícito que su escenario necesita.
2. **`last_accepted_counter` empieza en `-1`, no en `0`.** El requisito exige rechazar «cualquier
   código cuyo contador sea menor **o igual** al último aceptado». Si el valor inicial fuera `0`, el
   contador legítimo `0` (el primer periodo TOTP posible) quedaría rechazado por indistinguible de
   «ya aceptado» desde el primer uso real. `-1` es un centinela que nunca es un contador TOTP válido
   (los contadores son `floor(tiempo_unix / 30)`, siempre no negativos desde 1970), así que el primer
   código real siempre supera el centinela.
3. **`wrapped_key` con una restricción de formato de tres partes separadas por `:`**, sin el prefijo
   `v1:<id_dek>:` del formato de columna: la DEK envuelta por la KEK no necesita un identificador de
   llave dentro de sí misma, porque solo existe una KEK configurada en este cambio (decisión 5). Es
   un formato **distinto** del de `docs/03` §7.3, deliberadamente: ese formato es para valores de
   columna de negocio, cifrados con una DEK que **sí** necesita poder identificarse entre varias.
4. **Índice parcial, no restricción, para «como mucho una DEK activa».** Declarado en voz alta en el
   propio comentario de la migración: si `MultiTenantSchemaIT` no lo detecta como restricción única
   (herencia del mismo punto ciego que la parte 1 ya documentó para índices de expresión), la
   protección real la sigue dando el índice en tiempo de ejecución — lo que se pierde es solo la
   comprobación automática de que **este** índice en particular lleva `institution_id`, que de todos
   modos ya lo lleva por construcción.
5. **`identity_mfa_totp_backoff` con clave foránea a la cuenta**, a diferencia de
   `identity_login_backoff` (que deliberadamente no la lleva, parte 1 decisión 3). La diferencia es
   correcta y no una regresión: el login se protege también para identificadores **sin** cuenta
   (para no filtrar existencia por temporización), mientras que la verificación TOTP ocurre siempre
   **después** de una contraseña ya verificada contra una cuenta que **existe** — no hay
   identificador huérfano que proteger aquí.
6. **Ningún `DELETE` a ningún rol, en ninguna de las cuatro.** Retirar una DEK es poner
   `status = 'retired'`, nunca borrar la fila (la rotación real es brecha, D2). Invalidar un código
   de recuperación es poner `used_at`, nunca borrarlo. Es el mismo patrón que
   `identity_login_backoff` y `shared_idempotency_key` ya establecieron.

### Decisión 4 — Creación perezosa de la primera DEK por institución, con concurrencia segura

**El problema.** `V6` crea el esquema de `shared_data_encryption_key`, pero ninguna migración
siembra datos (no hay dato real, decisión 3). La primera vez que una institución necesita cifrar un
secreto TOTP, tiene que existir ya una DEK activa para esa institución — y dos inscripciones
concurrentes de MFA en la misma institución, cuando todavía no existe ninguna DEK, no deben crear
dos DEK activas ni fallar de forma no determinista.

**Elección**: `DataEncryptionKeyRepository.findActiveOrCreate(institutionId)` intenta primero
`SELECT` la DEK activa; si no existe, genera 32 bytes aleatorios con `SecureRandom`, los envuelve
bajo la KEK con `AesGcmCipher`, e inserta con
`INSERT ... ON CONFLICT (institution_id) WHERE status = 'active' DO NOTHING`, apuntando
exactamente al índice parcial de la decisión 3 como objetivo de conflicto (sonda **S5**: confirmar
que PostgreSQL infiere ese índice parcial como destino válido de `ON CONFLICT` con la misma cláusula
`WHERE`). Tras el `INSERT` (haya insertado o no), un `SELECT` final devuelve la fila activa que
ganó — la propia, si no hubo colisión, o la de la sesión concurrente que llegó primero. El patrón es
el mismo espíritu que el reclamo de `identity_login_backoff` (parte 1, decisión 5): la sentencia
resuelve la creación, y el `SELECT` posterior resuelve qué fila ganó, sin una segunda ronda de
`SELECT ... FOR UPDATE`.

**Alternativa descartada**: sembrar la DEK activa desde la propia migración `V6`. Se descarta porque
generar una llave criptográfica dentro de una migración SQL exigiría o bien una función de
PostgreSQL con acceso a aleatoriedad criptográfica y a la KEK (que vive fuera de la base, D1, nunca
dentro), o bien fijar una DEK de prueba en el propio SQL versionado — exactamente la clase de secreto
en el repositorio que la regla 13 de `CLAUDE.md` prohíbe.

### Decisión 5 — El formato de valor cifrado, su códec, y cómo se elige la DEK

Exactamente `docs/03` §7.3, sin margen de reinterpretación (ya fijado por D4 de la propuesta):
`v1:<id_dek>:<iv_base64>:<ciphertext_base64>:<tag_base64>`, AAD `tabla|columna|id_de_fila`.

```java
// com.confia.kernel — bytes a bytes, sin conocer nada de DEK ni de tablas.
public final class AesGcmCipher {
    public static final int IV_LENGTH_BYTES = 12;   // 96 bits, docs/03 §7.3
    public static final int TAG_LENGTH_BITS = 128;  // docs/03 §7.3

    public byte[] encrypt(byte[] key, byte[] iv, byte[] aad, byte[] plaintext) { … }
    public byte[] decrypt(byte[] key, byte[] iv, byte[] aad, byte[] ciphertextWithTag)
            throws AeadIntegrityException { … }
}

// com.confia.kernel — el códec del formato de cinco partes, puro.
public record EncryptedColumnValue(String dekId, byte[] iv, byte[] ciphertext, byte[] tag) {
    public static EncryptedColumnValue parse(String stored) { … }   // valida el prefijo "v1:"
    public String format() { … }
}
```

**El detalle que hay que cuidar, marcado como sonda y no como supuesto (S1).** `Cipher.doFinal(...)`
para `"AES/GCM/NoPadding"` en la JDK devuelve, según la documentación estándar de la API, el texto
cifrado **con la etiqueta de autenticación concatenada al final**. Para que
`EncryptedColumnValue.format()` produzca los campos `<ciphertext_base64>` y `<tag_base64>` como
**partes separadas** (tal como `docs/03` §7.3 exige literalmente), `AesGcmCipher.encrypt(...)` debe
partir los últimos 16 bytes (128 bits) del resultado de `doFinal` como la etiqueta, y
`decrypt(...)` debe volver a concatenarlos antes de invocar `doFinal` de descifrado. Esta fase no
pudo ejecutar código para confirmarlo; es sonda **S1**, bloqueante del corte C1.

```java
// com.confia.shared.crypto — orquesta, no cifra por sí mismo.
public final class ColumnEncryptionService {
    private final DataEncryptionKeyRepository keys;
    private final AesGcmCipher cipher; // com.confia.kernel

    public String encryptForNewValue(String table, String column, InstitutionId institutionId,
            String rowId, byte[] plaintext) {
        DataEncryptionKeyMaterial dek = keys.findActiveOrCreate(institutionId);
        byte[] iv = randomIv();
        byte[] aad = aadOf(table, column, institutionId, rowId);
        byte[] ciphertextWithTag = cipher.encrypt(dek.rawKeyBytes(), iv, aad, plaintext);
        return new EncryptedColumnValue(dek.id().value(), iv, /* split */ …).format();
    }

    public byte[] decrypt(String table, String column, InstitutionId institutionId, String rowId,
            String storedValue) {
        EncryptedColumnValue parsed = EncryptedColumnValue.parse(storedValue);
        DataEncryptionKeyMaterial dek = keys.findById(institutionId, new DataEncryptionKeyId(parsed.dekId()));
        byte[] aad = aadOf(table, column, institutionId, rowId);
        return cipher.decrypt(dek.rawKeyBytes(), parsed.iv(), aad, /* concat */ …);
    }

    private static byte[] aadOf(String table, String column, InstitutionId institutionId, String rowId) {
        return (table + "|" + column + "|" + institutionId.value() + ":" + rowId)
                .getBytes(StandardCharsets.UTF_8);
    }
}
```

**`id_de_fila` en `identity_mfa_totp_credential` es `institutionId:accountId`, no solo
`accountId`.** La tabla no tiene una columna `id` de fila única propia — su clave primaria es
compuesta (`institution_id`, `account_id`) — así que el componente de fila del AAD codifica los dos,
unidos por `:` (separador distinto de `|`, que ya delimita tabla/columna/fila, evitando cualquier
ambigüedad de análisis; el AAD nunca se analiza de vuelta, solo se recalcula igual en cada operación,
así que no hace falta que sea inequívocamente parseable, solo estable). Esto es exactamente lo que
hace fallar el descifrado si alguien copia el valor cifrado de una fila a otra: cambiar `accountId`
cambia el AAD, y `Cipher` de GCM rechaza la etiqueta de autenticación calculada para un AAD distinto
— el escenario publicado «Un valor cifrado con los datos autenticados de una fila falla al
descifrarse con los de otra fila» se demuestra sustituyendo el `accountId` del AAD, no el
`institutionId` (que además nunca coincidiría por la política de fila antes de llegar aquí).

**Cómo se elige la DEK: siempre la activa para cifrar, la que el propio valor nombra para
descifrar.** `encryptForNewValue` nunca recibe un `id_dek` de fuera — lo resuelve internamente contra
`findActiveOrCreate`, que es exactamente lo que hace imposible cifrar un valor nuevo con una DEK
`retired` por error del llamador. `decrypt` nunca consulta cuál DEK está activa — usa el `id_dek`
embebido en el propio valor almacenado, que es lo que permite seguir leyendo un valor cifrado con una
DEK ya retirada (el escenario «ninguna DEK retirada se recifra automáticamente… el valor cifrado
sigue descifrándose correctamente»).

### Decisión 6 — El `switch` exhaustivo y su fixture que debe fallar al compilar

**El problema, con cuidado de diseño explícito.** Una prueba normal no puede afirmar que algo *no
compila* — solo puede afirmar sobre el comportamiento de algo que ya compiló. El escenario publicado
exige exactamente eso: «se elimina, en un fixture de prueba permanente, la rama que maneja
`SecondFactorRequired`... la compilación de ese fixture falla».

**Mecanismo elegido: compilación en memoria con la API del compilador de la JDK
(`javax.tools.JavaCompiler`), contra un fixture guardado como recurso de texto, nunca como archivo
`.java` real del árbol de pruebas.**

```java
// apps/api/app/src/test/java/com/confia/architecture/ExhaustiveAuthenticationResultSwitchCompilationTest.java
class ExhaustiveAuthenticationResultSwitchCompilationTest {

    @Test
    void aSwitchMissingOneOutcomeFailsToCompile() {
        CompilationResult result = compile(readFixture("non-exhaustive-switch.java.txt"));
        assertThat(result.success()).isFalse();
        assertThat(result.diagnostics())
                .anyMatch(d -> d.getKind() == Diagnostic.Kind.ERROR
                        && d.getMessage(null).toLowerCase(Locale.ROOT).contains("exhaustive"));
    }

    /** Control negativo del mecanismo mismo (ADR-0018, misma disciplina de "conjunto vacío"
     * aplicada aquí a "el compilador de prueba nunca aceptó nada"): el switch completo, con las
     * cuatro ramas, debe compilar limpio. Si este test empezara a fallar, sería la propia
     * herramienta de compilación en memoria la que estaría rota, no el código de producción. */
    @Test
    void theRealFourBranchSwitchCompilesCleanly() {
        CompilationResult result = compile(readFixture("exhaustive-switch.java.txt"));
        assertThat(result.success()).isTrue();
        assertThat(result.diagnostics()).noneMatch(d -> d.getKind() == Diagnostic.Kind.ERROR);
    }
}
```

Los dos recursos —`non-exhaustive-switch.java.txt` y `exhaustive-switch.java.txt`— viven bajo
`src/test/resources/architecture-fixtures/`, **con extensión `.txt` y no `.java`**, precisamente
para que Maven **nunca** intente compilarlos como parte de la construcción normal: si el primero
viviera como `.java` real en el árbol de pruebas, rompería `./mvnw verify` para todo el mundo, no
solo para esta prueba. El `JavaCompiler` en memoria compila el texto del recurso contra el
`classpath` de la propia ejecución de pruebas (`System.getProperty("java.class.path")`), que ya
incluye `AuthenticationResult` y sus cuatro variantes compiladas por el corte anterior — así el
fixture es un `switch` real sobre el tipo real, no una imitación.

**Esto reemplaza, no complementa, una regla de ArchUnit contra `instanceof`.** Se evaluó
explícitamente (sección 2, sonda **S4**): ArchUnit en este árbol nunca ha demostrado poder detectar
un `instanceof` como violación — sus reglas existentes detectan llamadas a método
(`NoBlockingWaitInIdentityTest`) y dependencias de tipo generado
(`TableOwnershipByModuleTest`), nunca una comprobación de tipo en tiempo de ejecución. Escribir esa
regla sin confirmar que la API lo permite sería inventar un mecanismo no verificado para una garantía
que el compilador ya da mejor: si `AuthenticateWithPassword` alguna vez volviera a usar `instanceof`
en vez de `switch`, ningún compilador se quejaría — pero tampoco cumpliría el requisito publicado, que
exige literalmente el `switch`. La regla de negocio real («no avanza el retroceso, no se audita como
fallo») se prueba con el segundo escenario del mismo requisito (`AuthenticateWithPasswordIT`,
sección 7), sobre el código de producción real, no sobre un doble.

**Alternativas descartadas:**

| Opción | Por qué se descarta |
|---|---|
| Regla de ArchUnit que prohíba `instanceof` sobre `AuthenticationResult` | Sonda S4 sin resolver: no hay precedente de que la API lo soporte en este árbol. Se declara fuera de este cambio, no se inventa sin verificar |
| Un módulo Maven separado que compila el fixture roto como parte del `reactor`, y se espera que falle | Rompería la construcción completa cada vez que alguien ejecutara `./mvnw verify` sin filtrar ese módulo — es exactamente el problema que la extensión `.txt` evita, con más ceremonia |
| Aserción de comportamiento únicamente (sin demostrar la no-compilación) | No cumple el escenario publicado, que pide explícitamente la compilación fallida, no solo el comportamiento correcto de las cuatro ramas que sí se manejan |

**Sonda S3** (sección 10) confirma que el mensaje de diagnóstico de `javac` para un `switch`
sin `default` sobre un tipo sellado, con una rama de patrón faltante, contiene efectivamente la
palabra «exhaustive» en JDK 25 — la aserción de arriba no fija la frase completa a propósito, para no
quedar acoplada a un texto de mensaje que el JDK puede reformular entre versiones menores.

### Decisión 7 — TOTP: parámetros, algoritmo puro, y los vectores ya verificados

**Parámetros**, literales de `docs/03` §4.3, ya verificados en la exploración: RFC 6238, SHA-1, 6
dígitos, periodo de 30 segundos, ventana ±1. Secreto de 20 bytes aleatorios (`SecureRandom`).

```java
// com.confia.identity.domain — puro, sin reloj propio, sin E/S.
public final class TotpAlgorithm {
    public static long counterFor(Instant instant, Duration period) {
        return Math.floorDiv(instant.getEpochSecond(), period.toSeconds());
    }

    /** Nunca un int ni un long: el vector de RFC 6238 para el tiempo 1234567890 es "005924", con
     * dos ceros a la izquierda — un entero que se compare como 5924 pasaría esta prueba y fallaría
     * contra cualquier aplicación de autenticación real (exploration.md, apéndice, "el detalle que
     * hay que cuidar"). */
    public static TotpCode generate(byte[] secret, long counter) {
        byte[] hmac = hmacSha1(secret, counterBytes(counter));
        int offset = hmac[hmac.length - 1] & 0x0F;
        int binary = ((hmac[offset] & 0x7F) << 24) | ((hmac[offset + 1] & 0xFF) << 16)
                | ((hmac[offset + 2] & 0xFF) << 8) | (hmac[offset + 3] & 0xFF);
        int truncated = binary % 1_000_000;
        return new TotpCode(String.format(Locale.ROOT, "%06d", truncated));
    }
}

public record TotpCode(String value) {
    public TotpCode {
        if (!value.matches("^[0-9]{6}$")) {
            throw new IllegalArgumentException("a TOTP code must be exactly 6 digits, was " + value);
        }
    }
}
```

**Los vectores de prueba, exactamente los que el apéndice de `exploration.md` ya verificó contra
`rfc-editor.org`**, con sus dos avisos respetados:

| Tiempo (s) | TOTP SHA-1, seis dígitos (**derivado**, no citado del RFC) |
|---|---|
| 59 | `287082` |
| 1111111109 | `081804` |
| 1111111111 | `050471` |
| **1234567890** | **`005924`** — el caso con ceros a la izquierda que la prueba debe afirmar explícitamente |
| 2000000000 | `279037` |
| 20000000000 | `353130` |

`Argon2PhcCodecTest` de la parte 1 es el precedente literal de «vector de un RFC comparado byte a
byte»; `TotpAlgorithmTest` de este cambio sigue el mismo patrón, con
`Clock.fixed(Instant.ofEpochSecond(1234567890), UTC)` y una aserción explícita de que el valor
devuelto es la cadena `"005924"` — nunca un entero — para que el aviso del apéndice quede
demostrado, no solo citado.

**Verificación con ventana ±1 y anti-repetición por contador, en el dominio:**

```java
public final class TotpVerificationPolicy {
    private static final int TOLERANCE_WINDOW = 1;

    /** Prueba, en orden, los contadores [actual-1, actual, actual+1] contra secret, y devuelve el
     * primer contador candidato que (a) coincide con el código presentado y (b) es mayor que
     * lastAcceptedCounter. Optional.empty() si ninguno cumple ambas condiciones — incluido el caso
     * de un código matemáticamente válido pero de un contador ya aceptado (specs/identity/spec.md,
     * "Un código ya aceptado no puede reutilizarse"). */
    public Optional<Long> matchingCounter(byte[] secret, long lastAcceptedCounter, Instant now,
            Duration period, TotpCode presented) {
        long expected = TotpAlgorithm.counterFor(now, period);
        for (long candidate = expected - TOLERANCE_WINDOW; candidate <= expected + TOLERANCE_WINDOW; candidate++) {
            if (candidate <= lastAcceptedCounter) {
                continue;
            }
            TotpCode generated = TotpAlgorithm.generate(secret, candidate);
            if (MessageDigest.isEqual(generated.value().getBytes(UTF_8), presented.value().getBytes(UTF_8))) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }
}
```

**Comparación con `MessageDigest.isEqual`, no con `String.equals`.** Mismo razonamiento que
`BouncyCastleArgon2PasswordHasher.matches(...)` ya aplica: comparar cadenas de 6 caracteres con
`==`/`equals` filtra por temporización cuántos dígitos iniciales coinciden. El costo de tiempo
constante sobre 6 caracteres es insignificante comparado con la ganancia de no reabrir el mismo tipo
de canal lateral que la parte 1 ya cerró para Argon2id.

**Persistencia del contador aceptado: `UPDATE` condicional, no `SELECT` más `UPDATE`.**

```sql
UPDATE identity_mfa_totp_credential
   SET last_accepted_counter = ?
 WHERE institution_id = ? AND account_id = ? AND last_accepted_counter < ?
RETURNING account_id;
```

Cero filas devueltas significa que, entre la lectura del secreto y este `UPDATE`, otra verificación
concurrente ya aceptó un contador igual o mayor — la misma señal de derrota que la sonda de la parte
1 ya demostró para `identity_login_backoff` (`ON CONFLICT ... RETURNING`), aplicada aquí a un
`UPDATE` simple porque la fila **siempre existe ya** (se crea una vez, en la inscripción; no hay
caso de «cuenta sin fila» que resolver, a diferencia del login). No hace falta `SELECT ... FOR
UPDATE` primero: el predicado del propio `UPDATE` ya sirve de punto de serialización.

### Decisión 8 — Límite de tasa de verificación TOTP (D7): reutilización exacta de `BackoffPolicy`

**Elección: `identity_mfa_totp_backoff` con el mismo par `BackoffState`/`BackoffPolicy` del módulo,
sin duplicar la regla**, con `StaffAccountId` como clave en vez de `IdentifierFingerprint`.

```java
public interface TotpVerificationBackoffStore {
    BackoffState claim(InstitutionId institutionId, StaffAccountId accountId, Instant now);
    void save(InstitutionId institutionId, StaffAccountId accountId, BackoffState state);
}
```

`JooqTotpVerificationBackoffStore` reproduce el `claim(...)` de `JooqLoginBackoffStore` (`INSERT ...
ON CONFLICT DO UPDATE SET consecutive_failures = ... RETURNING`) columna por columna, cambiando solo
la clave. **Por qué no hace falta una huella (fingerprint) aquí**, a diferencia del login: la cuenta
ya existe y ya se autenticó con contraseña — no hay identificador ajeno a una cuenta real que
proteger de un oráculo de enumeración, así que `entity_id` en la auditoría puede ser directamente el
identificador de la cuenta, sin hashear.

Los parámetros de `docs/03` §4.3 («5 intentos por 15 minutos, con retroceso exponencial posterior»)
son literalmente los mismos umbrales que `BackoffPolicy.FIRST_DELAYED_ATTEMPT` y
`BackoffPolicy.COUNTER_WINDOW` ya codifican para el login — se reutiliza la misma clase de dominio
sin nueva constante, y una prueba explícita (`TotpVerificationBackoffIT`) confirma el sexto intento
en la misma ventana de 15 minutos aplicando el ciclo de retroceso, con su duración auditada
(`identity.mfa.totp_verification.backoff_applied`).

### Decisión 9 — Los cuatro secretos: clases finales redactadas, y su control negativo

**Los cuatro objetos de valor**, todos clases finales con `toString()` sobrescrito explícito, nunca
`record`s sin más (regla 11 de `CLAUDE.md`, restricción 1 de la propuesta):

| Clase | Paquete | Qué carga |
|---|---|---|
| `PlainTotpSecret` | `identity.domain` | los 20 bytes del secreto TOTP en claro |
| `PlainRecoveryCode` | `identity.domain` | el código de recuperación de MFA en claro (10 caracteres) |
| `StoredRecoveryCodeHash` | `identity.domain` | el hash Argon2id del código (mismo trato que `StoredPasswordHash`, aunque no es el texto en claro: un hash sigue sin imprimirse, por el mismo precedente) |
| `ColumnEncryptionMasterKey` | `shared.crypto` | la KEK, precedente literal de `Argon2Pepper` (D1) |
| `DataEncryptionKeyMaterial` | `shared.crypto` | los 32 bytes de la DEK ya desenvuelta |

**El control negativo que el requisito publicado exige** («eso se verifica por inspección del texto
producido, nunca por confianza en el diseño») necesita, además de la prueba positiva (los cinco
objetos de arriba no filtran nada), una prueba que demuestre que **el propio mecanismo de barrido
sabría detectar una fuga si existiera** — es exactamente el hallazgo de la parte 1: la prueba
original no llamaba a `toString()` sobre el objeto que filtraba, así que pasaba sin haber mirado
nada.

```java
// apps/api/app/src/test/java/com/confia/identity/testsupport/fixture/LeakingMfaSecretFixture.java
/** Fixture permanente de fuga deliberada (nunca de producción): un record SIN toString()
 * sobrescrito, exactamente la forma que causó el tercer hallazgo bloqueante de la parte 1. Prueba
 * que el barrido de redacción es capaz de detectar una fuga real, no solo de no encontrar ninguna
 * porque nunca miró (mismo espíritu que ADR-0018 exige para las reglas de ArchUnit: una regla que
 * nunca rechaza nada no protege nada). */
public record LeakingMfaSecretFixture(String fakeSecretForTestingOnly) { }
```

```java
@Test
void theRedactionSweepDetectsARealLeak() {
    var fixture = new LeakingMfaSecretFixture("not-a-real-secret-just-proves-the-sweep-works");
    assertThat(fixture.toString()).contains("not-a-real-secret-just-proves-the-sweep-works");
    // Si esta aserción alguna vez fallara, sería la prueba (o el propio record) la que cambió de
    // forma, no una garantía sobre el código de producción.
}
```

`IdentitySecretRedactionIT` (ya existente desde la parte 1) se extiende con: (a) los cinco objetos de
arriba, recogiendo `toString()`, mensajes de excepción forzados (incluida una manipulación
deliberada del AAD para forzar un fallo de autenticación de GCM, sección 8) y el contenido de las
filas escritas en las cuatro tablas nuevas y en `shared_audit_log`; (b) la prueba de control
negativo de arriba, en un método distinto, para que un fallo de (a) y un fallo de (b) nunca se
confundan en el reporte de la construcción.

### Decisión 10 — Notas editoriales fechadas sobre `docs/03-seguridad.md`

Dos notas nuevas, siguiendo el formato literal que la parte 1 ya estableció en §4.1 y §6.1:

**Sobre §7.3** (tras la tabla de gestión y rotación de llaves): la tabla que esta sección llama
`data_key` se entrega como `shared_data_encryption_key`, con el prefijo de módulo que exige la regla
3 de ADR-0015 — su gestión vive en `com.confia.shared.crypto` (ADR-0023), no en un módulo de
negocio.

**Sobre §4.3** (tras la tabla de roles y MFA): la tabla que esta sección llama `user_mfa` se entrega
como `identity_mfa_totp_credential`; los códigos de recuperación viven en una tabla separada,
`identity_mfa_recovery_code`, y el límite de tasa de verificación en `identity_mfa_totp_backoff`. La
frase «almacenados con el mismo Argon2id que las contraseñas» se cumple con el **mismo perfil,
códec PHC y hasher de bajo nivel**, pero detrás de un puerto propio (`RecoveryCodeHasher`, D5 de la
propuesta), no reutilizando `PasswordHasher`.

**Adenda a la nota ya existente de §6.1**: las cuatro tablas de este cambio reciben el mismo trato
que la nota de la parte 1 ya dio a `identity_login_backoff` — `SELECT`, `INSERT`, `UPDATE` para
`confia_admin_app`, sin `DELETE`; `SELECT` para `confia_readonly`; ningún privilegio para
`confia_portal_app`.

`docs/09-roadmap-y-fases.md` necesita además una corrección de nombre, no solo una adenda: el texto
actual (líneas 104-110) sigue nombrando `mfa-totp-and-password-recovery` como si fuera un cambio
único; debe pasar a nombrar `column-encryption-and-mfa-totp` (este cambio) y
`password-recovery-token` (el tercero de la secuencia) por separado, seguido de
`session-tokens-and-web-layer` como el cuarto. Es una tarea de aplicación, no de este diseño: se deja
señalada aquí para que no se olvide, con la cita exacta de las líneas afectadas.

---

## 4. Flujo de datos

### 4.1 Inscripción del segundo factor

```
        caller (hoy: prueba · mañana: session-tokens-and-web-layer)
             │  EnrollTotpSecondFactor.execute(context, accountId)
             ▼
   TransactionRunner.execute(context, READ COMMITTED, …)
   ┌─────────────────────────────────────────────────────────────────┐
   │ 1 secreto TOTP nuevo: SecureRandom, 20 bytes                    │
   │ 2 ColumnEncryptionService.encryptForNewValue(...)                │
   │     └─ DataEncryptionKeyRepository.findActiveOrCreate(...)      │
   │           └─ crea la DEK si es la primera vez (decisión 4)      │
   │ 3 INSERT identity_mfa_totp_credential (secreto cifrado, -1)     │
   │ 4 diez PlainRecoveryCode.generate(); RecoveryCodeHasher.hash(...)│
   │ 5 INSERT × 10 identity_mfa_recovery_code                        │
   │ 6 AuditLogWriter.append(identity.mfa.enrolled)                  │
   └─────────────────────────────────────────────────────────────────┘
             │  commit
             ▼
        devuelve los diez códigos EN CLARO, una única vez — nunca se
        vuelven a poder leer tras este retorno
```

### 4.2 Verificación de un código TOTP tras `SecondFactorRequired`

```
   TransactionRunner.execute(context, READ COMMITTED, …)
   ┌─────────────────────────────────────────────────────────────────┐
   │ 1 TotpVerificationBackoffStore.claim(...) → estado previo + lock │
   │ 2 SELECT identity_mfa_totp_credential (secreto cifrado, contador)│
   │ 3 ColumnEncryptionService.decrypt(...) → secreto en claro        │
   │ 4 TotpVerificationPolicy.matchingCounter(...)                    │
   │ 5a si hay match: UPDATE ... WHERE last_accepted_counter < ?      │
   │     RETURNING → 0 filas = derrota concurrente, se trata como     │
   │     rechazo aunque el código fuera válido                       │
   │ 5b si no hay match, o 5a devolvió 0 filas: rechazo               │
   │ 6 BackoffStore.save(nuevo estado)                                │
   │ 7 AuditLogWriter.append(succeeded | failed)                      │
   │ 8 AuditLogWriter.append(backoff_applied) si requiredDelay > 0    │
   └─────────────────────────────────────────────────────────────────┘
```

### 4.3 Consumo de un código de recuperación de MFA

```
   TransactionRunner.execute(context, READ COMMITTED, …)
   ┌─────────────────────────────────────────────────────────────────┐
   │ 1 SELECT id, code_hash FROM identity_mfa_recovery_code           │
   │     WHERE account_id = ? AND used_at IS NULL                    │
   │ 2 para cada fila: RecoveryCodeHasher.matches(presentado, hash)   │
   │ 3 si hay match: UPDATE ... SET used_at = now()                  │
   │     WHERE id = ? AND used_at IS NULL RETURNING id                │
   │     0 filas = otro consumo concurrente ya ganó: rechazo          │
   │ 4 si no hay match: rechazo                                       │
   │ 5 si aceptado: SELECT COUNT(*) restantes sin usar                │
   │ 6 AuditLogWriter.append(identity.mfa.recovery_code.used)         │
   │ 7 si restantes < 3: AuditLogWriter.append(recovery_codes.low)    │
   └─────────────────────────────────────────────────────────────────┘
```

**Por qué no hace falta `SELECT ... FOR UPDATE` antes del paso 1 en 4.3.** El paso 3 ya es un
`UPDATE` con predicado de estado (`used_at IS NULL`) y `RETURNING` como señal de victoria o derrota
— exactamente el patrón que el cambio 6 ya estableció para `identity_login_backoff`/
`shared_idempotency_key`. Bloquear las diez filas por adelantado añadiría contención sin comprar
ninguna garantía que el `UPDATE` condicional no compre ya.

**Ninguna de las tres transacciones abre más de una vez `TransactionRunner.execute(...)`**, y
ninguna espera dentro de la transacción: cada una confirma antes de que el llamador reciba su
resultado, siguiendo exactamente el patrón que la parte 1 ya estableció para
`AuthenticateWithPassword` (decisión 9 del diseño de la parte 1). Estas tres transacciones **no**
reutilizan la guarda de `LoginInstitutionProvider` de `AuthenticateWithPassword`: esa guarda existe
porque, en el primer paso de contraseña, no hay todavía ninguna sesión de la que derivar la
institución con confianza (D4 de la parte 1). Aquí, el llamador ya recibió `institutionId` y
`accountId` dentro de `SecondFactorRequired`/`SecondFactorEnrollmentRequired` — la seguridad de fila
de PostgreSQL, no una guarda de aplicación adicional, es lo que impide que un contexto con la
institución equivocada lea o escriba la fila de otra.

---

## 5. Cambios de archivos

| Archivo | Acción | Descripción |
|---|---|---|
| `.../kernel/AesGcmCipher.java`, `EncryptedColumnValue.java` | Crear | Motor puro y códec (decisión 2, 5) |
| `.../shared/crypto/package-info.java` | Crear | `@NamedInterface`, sin segmento de capa (decisión 2) |
| `.../shared/crypto/DataEncryptionKeyRepository.java`, `DataEncryptionKeyMaterial.java`, `DataEncryptionKeyId.java`, `ColumnEncryptionMasterKey.java`, `ColumnEncryptionService.java` | Crear | Puerto, valores y orquestador (decisión 2) |
| `.../shared/infrastructure/JooqDataEncryptionKeyRepository.java` | Crear | Adaptador jOOQ, prefijo `Shared` (decisión 2) |
| `.../identity/domain/TotpAlgorithm.java`, `TotpCode.java`, `TotpVerificationPolicy.java`, `PlainTotpSecret.java` | Crear | RFC 6238 puro (decisión 7) |
| `.../identity/domain/PlainRecoveryCode.java`, `StoredRecoveryCodeHash.java` | Crear | Códigos de recuperación (decisión 9, D5) |
| `.../identity/application/EnrollTotpSecondFactor.java`, `VerifyTotpCode.java`, `ConsumeRecoveryCode.java` | Crear | Los tres casos de uso nuevos |
| `.../identity/application/TotpCredentialRepository.java`, `TotpVerificationBackoffStore.java`, `RecoveryCodeRepository.java`, `RecoveryCodeHasher.java` | Crear | Puertos nuevos |
| `.../identity/infrastructure/JooqTotpCredentialRepository.java`, `JooqTotpVerificationBackoffStore.java`, `JooqRecoveryCodeRepository.java`, `BouncyCastleRecoveryCodeHasher.java` | Crear | Adaptadores, prefijo `Identity` |
| `.../identity/domain/AuthenticationResult.java` | Modificar | Añade `SecondFactorRequired`, `SecondFactorEnrollmentRequired` a `permits` |
| `.../identity/application/AuthenticateWithPassword.java` | Modificar | `switch` exhaustivo en los dos puntos que hoy son `instanceof`; lectura de `mfa_required` y del estado de inscripción |
| `.../architecture/ExhaustiveAuthenticationResultSwitchCompilationTest.java` + dos recursos `.txt` | Crear | Mecanismo de compilación fallida (decisión 6) |
| `.../identity/testsupport/fixture/LeakingMfaSecretFixture.java` | Crear | Control negativo de redacción (decisión 9) |
| `.../test/.../IdentitySecretRedactionIT.java` | Modificar | Extensión con los cinco secretos nuevos y el control negativo |
| `.../test/.../RolePrivilegeMatrixIT.java`, `MultiTenantSchemaIT.java` | Modificar | Filas de las cuatro tablas nuevas |
| `db/migration/V6__add_mfa_required_and_create_crypto_mfa_tables.sql` | Crear | Columna y cuatro tablas (decisión 3) |
| `docs/adr/ADR-0023-sobre-de-llaves-para-cifrado-de-columna.md` | Crear | Alcance exacto de D4 |
| `docs/03-seguridad.md` | Modificar | Notas editoriales §4.3, §7.3, adenda §6.1 (decisión 10) |
| `docs/09-roadmap-y-fases.md` | Modificar | Corrección de nombre de cambio (decisión 10) |
| `openspec/changes/.../specs/**` | **Sin cambios** | Ningún ajuste resultó necesario (sección 0) |

---

## 6. Estrategia de pruebas y trazabilidad

| Capa | Qué se prueba | Cómo |
|---|---|---|
| Unitaria (`kernel`) | `AesGcmCipher` de ida y vuelta; AAD distinto falla; `EncryptedColumnValue` analiza y formatea | JUnit, AssertJ, sin contenedor |
| Unitaria (`domain`) | `TotpAlgorithm` contra los seis vectores derivados (con el caso `005924` explícito); `TotpVerificationPolicy` (ventana, anti-repetición); `BackoffPolicy` ya cubierto por la parte 1, reutilizado sin nueva prueba | JUnit, AssertJ. Puerta 95 %/PIT 80 |
| Arquitectura | Fixture de compilación fallida y su control negativo (decisión 6); redacción de los cinco secretos y su control negativo (decisión 9) | `javax.tools.JavaCompiler` en memoria; `IdentitySecretRedactionIT` |
| Integración (`*IT`) | Política de fila y privilegios de las cuatro tablas; creación perezosa concurrente de la primera DEK; enrolamiento completo; verificación con ventana y anti-repetición; límite de tasa TOTP; consumo de código de recuperación con concurrencia; aviso de códigos bajos | Testcontainers, conectando como `confia_admin_app` y `confia_portal_app`, nunca como propietario |

### 6.1 Trazabilidad de los 27 escenarios

**`identity` (21 escenarios)**

| Requisito | Escenario | Prueba |
|---|---|---|
| Cifrado de columna | Se almacena con formato `v1:` | `ColumnEncryptionIT` |
| | AAD de otra fila falla al descifrar | `ColumnEncryptionIT` |
| TOTP | Código válido en ventana ±1 se acepta | `VerifyTotpCodeIT` |
| | Código ya aceptado no se reutiliza | `VerifyTotpCodeIT` |
| Límite de tasa TOTP | Sexto intento activa retroceso | `TotpVerificationBackoffIT` |
| 10 códigos de recuperación | Se generan y se muestran una vez | `EnrollTotpSecondFactorIT` |
| | Usar uno no afecta a los otros nueve | `ConsumeRecoveryCodeIT` |
| Aviso de códigos bajos | Octavo código deja el aviso activo | `ConsumeRecoveryCodeIT` |
| | Con exactamente tres no se activa | `ConsumeRecoveryCodeIT` |
| `mfa_required` | Se fija al crear la cuenta, sin rol | `MultiTenantSchemaIT`/siembra de prueba |
| | Este cambio no deriva de ningún permiso | `AuthenticateWithPasswordIT` (ausencia de derivación) |
| Exhaustividad del compilador | Un desenlace no manejado rompe la compilación | `ExhaustiveAuthenticationResultSwitchCompilationTest` |
| | `SecondFactorRequired` no avanza el retroceso ni se audita como fallo | `AuthenticateWithPasswordIT` |
| Ningún secreto observable | Ninguno de los cuatro secretos nuevos es observable | `IdentitySecretRedactionIT` + control negativo |
| Ausencia de rotación real | Ninguna DEK retirada se recifra | `IdentityScopeExclusionInventoryTest` |
| Ausencia de envío del aviso | Se audita, no se envía correo | `ConsumeRecoveryCodeIT` |
| MFA obligatoria (modificado) | Cuenta con secreto inscrito → `SecondFactorRequired` | `AuthenticateWithPasswordIT` |
| | Cuenta sin secreto inscrito → `SecondFactorEnrollmentRequired` | `AuthenticateWithPasswordIT` |
| Resultado tipado, cuatro desenlaces (modificado) | Contraseña correcta sin MFA → `Authenticated` | `AuthenticateWithPasswordIT` (ya cubierto, sin cambio de comportamiento) |
| | Toda causa de rechazo → `Rejected` | Ídem |
| | Los cuatro desenlaces son exactamente los del `permits` | `AuthenticationResultTest` (reflexión) |

**`build-integrity` (6 escenarios)**

| Requisito | Escenario | Prueba |
|---|---|---|
| Tablas nuevas | Las puertas genéricas pasan sin exclusión | `MultiTenantSchemaIT` |
| | Una institución no lee la DEK de otra | `DataEncryptionKeyRowSecurityIT` |
| | Sin contexto, cero filas | `DataEncryptionKeyRowSecurityIT` |
| Privilegios | La matriz cubre las cuatro tablas para los cinco roles | `RolePrivilegeMatrixIT` |
| | `confia_admin_app` lee/inserta/actualiza, no borra | `RolePrivilegeMatrixIT` |
| | `confia_portal_app` sin ningún privilegio | `RolePrivilegeMatrixIT` |

---

## 7. Matriz de amenazas

No aplica en el sentido de `references/threat-matrix.md`: sin enrutamiento, subprocesos,
automatización de Git/PR ni clasificación de archivos ejecutables. Fronteras de seguridad tratadas
como decisiones con verificación:

| Frontera | Tratamiento |
|---|---|
| Secreto TOTP, código de recuperación, KEK, DEK en registros/excepciones/`toString()` | Decisión 9, con control negativo |
| Copia de un valor cifrado entre filas | AAD `tabla|columna|institución:fila` (decisión 5) |
| Reutilización de código TOTP dentro de su ventana | `UPDATE` condicional con predicado de contador (decisión 7) |
| Reutilización de un código de recuperación | `UPDATE` condicional con predicado `used_at IS NULL` (sección 4.3) |
| Fuerza bruta contra la verificación TOTP | Reutilización de `BackoffPolicy` con clave de cuenta (decisión 8) |
| Doble DEK activa por institución bajo concurrencia | Índice parcial + `ON CONFLICT ... WHERE ... DO NOTHING` (decisión 4), sonda S5 |
| `switch` no exhaustivo tratando un desenlace nuevo como fallo | Decisión 6, demostrado con fixture de compilación fallida |
| Aislamiento por institución de las cuatro tablas nuevas | Política `USING`/`WITH CHECK`, `FORCE ROW LEVEL SECURITY`, probado con el rol de aplicación |
| Dependencia nueva | Ninguna prevista: AES-256-GCM y HMAC-SHA-1 son JDK puro (sección 2) |

---

## 8. Migración y despliegue

Sin entorno desplegado ni dato real (igual que la parte 1). Un matiz propio: **la primera DEK de
cada institución nace perezosamente**, no por migración (decisión 4) — el contenedor de generación
de código de jOOQ también aplica `V6`, así que los tipos generados existen antes de escribir
adaptadores (sonda S2), pero ninguna fila de `shared_data_encryption_key` existe hasta el primer
`findActiveOrCreate`.

---

## 9. Sondas, a ejecutar antes de la tarea que depende de cada una

| # | Pregunta | Comando exacto | Criterio de éxito y respaldo | Bloquea |
|---|---|---|---|---|
| **S1** | ¿`Cipher.getInstance("AES/GCM/NoPadding")` en JDK 25 concatena la etiqueta de 128 bits al final de `doFinal(...)`, exigiendo partir los últimos 16 bytes para separar `<ciphertext_base64>` de `<tag_base64>`? | Programa mínimo fuera del árbol (o `jshell`): cifrar un texto conocido con una llave e IV fijos, comparar `ciphertext.length` con `plaintext.length + 16` | La diferencia es exactamente 16 bytes. Si no, se ajusta `AesGcmCipher` a lo que la API realmente devuelve, y se reporta la desviación | **C1** |
| **S2** | ¿jOOQ 3.21 genera `SharedDataEncryptionKey`, `IdentityMfaTotpCredential`, `IdentityMfaRecoveryCode`, `IdentityMfaTotpBackoff` con esos nombres exactos, tras `V6`? | `./mvnw -B -pl app generate-sources` y listar `target/generated-sources` | Los cuatro nombres coinciden con los prefijos exigidos por `TableOwnershipByModuleTest` (decisión 1, 2). Si no, se ajusta el nombre de tabla antes de escribir cualquier adaptador | **C1** |
| **S3** | ¿El diagnóstico de `javac` para un `switch` de patrones sin `default` que deja de cubrir un permiso de un tipo sellado contiene la palabra «exhaustive» en JDK 25? | Escribir el fixture de la decisión 6 y ejecutar `./mvnw -B -pl app test -Dtest=ExhaustiveAuthenticationResultSwitchCompilationTest` en cuanto exista el `switch` real de C4 | La compilación falla y algún diagnóstico de tipo `ERROR` contiene «exhaustive» (sin distinguir mayúsculas). Si el texto exacto difiere, se ajusta la aserción a lo que `javac` realmente produce, nunca se relaja a solo «falla sin más» | **C4** |
| **S4** | ¿La versión de ArchUnit fijada en este repositorio expone algún mecanismo para detectar una comprobación `instanceof` sobre un tipo dado como violación de una regla? | `./mvnw -B -pl app dependency:tree -Dincludes=com.tngtech.archunit` para fijar la versión exacta; revisar su documentación de `JavaClass`/`Dependency` para un tipo de acceso equivalente a `INSTANCEOF` | Si existe, se evalúa añadir una regla de regresión como refuerzo del mecanismo principal (decisión 6), sin sustituirlo. Si no existe, se declara sin construir, tal como decisión 6 ya anticipa — no bloquea ningún corte | Ninguno (no bloqueante) |
| **S5** | ¿PostgreSQL 18 infiere el índice parcial `shared_data_encryption_key_one_active_per_institution` como destino de `ON CONFLICT (institution_id) WHERE status = 'active' DO NOTHING`? | Contra `postgres:18-alpine` fuera del árbol: crear la tabla y el índice de la decisión 3, ejecutar el `INSERT ... ON CONFLICT` de la decisión 4 dos veces con el mismo `institution_id` | La segunda ejecución no inserta una segunda fila y no lanza error de ambigüedad de índice. Respaldo si fallara: nombrar el índice explícitamente con `ON CONSTRAINT` en vez de inferencia por columnas — requiere que el índice parcial sea, además, una restricción `EXCLUDE`, lo que exigiría revisar la decisión 3 | **C1** |
| ~~S6~~ | ~~¿`shared.crypto` necesita una entrada nueva en el inventario de caducidad de ADR-0018?~~ | **Resuelto por lectura, ya no es una sonda.** `EmptyShouldExceptionInventoryTest.java:44-49` enumera un único `ExpiringException` vigente, `Marker.OPTIONAL_LAYER` sobre la capa `web` (ADR-0020) — el inventario trata excepciones de **capa**, no de conteo de módulos de negocio. `shared.crypto` no lleva segmento de capa, exactamente como `shared.security` y `shared.audit`, y ninguno de los dos tiene entrada en ese inventario. No hace falta ninguna entrada nueva | — |

---

## 10. Secuencia de aplicación con TDD estricto (resumen por corte)

**C1** — Sondas S1, S2, S5 primero. Rojo: `RolePrivilegeMatrixIT`/`MultiTenantSchemaIT` con las
filas de las cuatro tablas. Verde: `V6`, `AesGcmCipher`, `EncryptedColumnValue`,
`DataEncryptionKeyRepository` + adaptador, `ColumnEncryptionService`, ADR-0023.

**C2** — `TotpAlgorithm` contra los seis vectores derivados (con `005924` explícito). Rojo/verde de
`TotpVerificationPolicy`, `EnrollTotpSecondFactor`, `VerifyTotpCode`,
`TotpVerificationBackoffStore` + adaptador.

**C3** — `RecoveryCodeHasher` + adaptador reutilizando `Argon2Profile`/`Argon2PhcCodec`. `Consume
RecoveryCode`, aviso de códigos bajos.

**C4** — Edición de `AuthenticationResult` y de `AuthenticateWithPassword`. Sonda S3 en cuanto exista
el `switch` real. Fixture de compilación fallida y su gemelo de control positivo.

**C5** — Notas editoriales de `docs/03` y `docs/09`. Extensión de `IdentitySecretRedactionIT` con el
control negativo. Barrido final de trazabilidad de los 27 escenarios.

---

## 11. Pronóstico de cortes y tamaño, sin descontar el historial de subestimación

La propuesta ya fijó 12-17 tareas en cinco bloques. Este diseño los mapea a los cinco cortes de
arriba y añade su propio pronóstico de líneas de cambio efectivo, con el multiplicador histórico de
1,5× a 3× **sin descuento** (la parte 1 se pronosticó en 12-13 tareas y terminó en 12 tareas, pero
con **cuatro cortes y nueve pull requests**, todos por encima del presupuesto de 800 líneas).

| Corte | Tareas (propuesta) | Líneas «de una pasada» estimadas | Con multiplicador 1,5×-3× | Riesgo de superar 800 líneas |
|---|---|---|---|---|
| C1 — cifrado, ADR-0023, migración | 3-4 | ~450 | 675-1350 | **Alto**: el DDL de cuatro tablas más el motor de cifrado y su adaptador ya se acerca solo al piso |
| C2 — TOTP | 3-4 | ~400 | 600-1200 | **Alto**: seis vectores de prueba más el algoritmo, la política y dos casos de uso |
| C3 — recuperación de MFA | 2-3 | ~300 | 450-900 | Medio |
| C4 — `AuthenticationResult`/`switch`/fixture de compilación | 2-3 | ~350 | 525-1050 | Medio-alto: el mecanismo de compilación en memoria no es trivial |
| C5 — documentación y redacción | 2-3 | ~200 | 300-600 | Bajo |
| **Total** | **12-17** | **~1700** | **2550-5100** | — |

**Lectura honesta.** Incluso en el extremo bajo del multiplicador, **cuatro de los cinco cortes
probablemente superan el presupuesto de 800 líneas por pull request** (`docs/15-flujo-de-trabajo-
git.md` §3) por sí solos, no solo en conjunto. La mitigación no es dividir más estos cinco cortes en
más cortes SDD — la propuesta ya declaró que no queda margen ahí —, sino que **cada uno de estos
cinco cortes probablemente necesita, a su vez, dos o tres pull requests encadenados dentro de sí
mismo**, exactamente como la parte 1 necesitó nueve pull requests para sus tres cortes declarados.
Este diseño no fija de antemano cuántos, porque eso es trabajo de la fase de tareas con el
presupuesto de líneas real de cada archivo — pero deja escrito que **la expectativa realista no es
"cinco pull requests", es "entre diez y quince"**, y que tratarlo como sorpresa a mitad de la
aplicación repetiría el mismo patrón que ya costó caro en la parte 1.

---

## 12. Preguntas abiertas

- [ ] Sonda S1 (formato exacto de `doFinal` para GCM en JDK 25) es la única que, de fallar, obligaría
      a rediseñar `AesGcmCipher` antes de escribir cualquier otro código de C1.
- [ ] Sonda S4 (si ArchUnit puede detectar `instanceof`) no bloquea ningún corte; su resultado solo
      decide si se añade una regla de regresión de refuerzo en C4 o se documenta como brecha sin
      mecanismo automático, apoyada solo en revisión de código.
- [ ] Ninguna otra pregunta bloquea el inicio de C1: la antigua sonda S6 quedó resuelta por lectura
      directa de `EmptyShouldExceptionInventoryTest.java` (ver tabla de sondas) y no sobrevive como
      pregunta abierta.
