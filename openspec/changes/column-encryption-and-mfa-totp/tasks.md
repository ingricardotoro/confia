# Tareas: cifrado de columna y MFA con TOTP

Cambio 7 de F0, **segundo de cuatro** en que se ejecuta el cambio 7 (`docs/09-roadmap-y-fases.md`
§3). Implementa `proposal.md` y `design.md`, ambos aprobados, y los dos deltas ya escritos:
`specs/identity/spec.md` (12 requisitos, **21** escenarios) y `specs/build-integrity/spec.md` (2
requisitos, **6** escenarios) — **27 escenarios en total**, contados aquí de nuevo contra los
propios archivos de delta, no copiados de la tabla de `design.md` §6.1, que en este caso **sí**
cuenta 21 y 6 correctamente (a diferencia de la parte 1, donde la tabla equivalente se desfasó).
Esta lista traza los 27 uno a uno más abajo.

## Discrepancias encontradas y reportadas

### 1. `design.md` §5 no enumera todas las clases `*IT`/`*Test` que su propia §6.1 exige por nombre

`design.md` §5 («Cambios de archivos») lista las clases de producción y un puñado de clases de
prueba (`ExhaustiveAuthenticationResultSwitchCompilationTest`, `LeakingMfaSecretFixture`,
`IdentitySecretRedactionIT`, `RolePrivilegeMatrixIT`, `MultiTenantSchemaIT`), pero **no** menciona
`ColumnEncryptionIT`, `DataEncryptionKeyRowSecurityIT`, `VerifyTotpCodeIT`,
`TotpVerificationBackoffIT`, `EnrollTotpSecondFactorIT` ni `ConsumeRecoveryCodeIT` — las seis
clases que la propia tabla de trazabilidad de §6.1 nombra como prueba de un escenario publicado.
No es una omisión que bloquee nada: §6.1 es la fuente de verdad para qué prueba cubre qué
escenario, y esta lista crea las seis, cada una en la tarea del corte que le corresponde por
contenido. Se reporta para que quien archive corrija §5 antes de darlo por cerrado.

### 2. `design.md` §1 (tabla de cortes) y §10 (resumen de secuencia) no coinciden sobre dónde nace `EnrollTotpSecondFactor`

`design.md` §1 asigna a **C2** «TOTP: secreto... cifrado del secreto» (sin mencionar la
generación de códigos) y a **C3** «Códigos de recuperación de MFA: ... **generación** y consumo
de un solo uso» — el verbo «generación» en la fila de C3 sugiere que la creación de los diez
códigos, y por tanto la parte de `EnrollTotpSecondFactor` que los genera, pertenece a C3. Pero
§10 lista literalmente `EnrollTotpSecondFactor` completo dentro de **C2**, sin ninguna
matización, y el flujo de datos de §4.1 (inscripción) es una única transacción que hace las dos
cosas —cifra el secreto TOTP **y** genera los diez códigos— con un solo evento de auditoría al
final. Construir `EnrollTotpSecondFactor` completo en C2, tal como dice §10 literalmente,
exigiría que C2 dependa de `RecoveryCodeHasher`, que `design.md` mismo asigna a C3 (decisión 5,
D5 de la propuesta). Esto es exactamente la misma clase de contradicción de orden que la lista de
tareas de `idempotency-key-infrastructure` ya encontró entre `design.md` §11 y §12 de aquel
cambio, y se resuelve aquí de la misma forma: **no se resuelve en silencio, se reporta, y esta
lista adopta la única partición que compila en cada corte.**

**Partición adoptada:** `EnrollTotpSecondFactor` se construye **entera, una sola vez, en C3**
(tarea 3.2), cuando ya existen tanto `TotpCredentialRepository` (C2) como `RecoveryCodeHasher` y
`RecoveryCodeRepository` (C3). En C2, las pruebas de verificación (`VerifyTotpCodeIT`,
`TotpVerificationBackoffIT`) siembran la credencial TOTP **directamente a través de
`JooqTotpCredentialRepository`** (el adaptador de C2), no a través de un caso de uso de
inscripción que todavía no puede existir completo. Esto es además más simple que dividir el
cuerpo de un mismo método entre dos cortes, y ningún escenario publicado exige que la inscripción
exista ya en C2: los dos escenarios de verificación (`identity`, «Un código válido...» y «Un
código ya aceptado...») solo presuponen que el secreto **ya está inscrito**, no dicen por qué
mecanismo. Quien archive corrige `design.md` §1/§10 para que dejen de contradecirse.

## Nota sobre la rama del cambio

La rama actual, `change/mfa-totp-and-password-recovery`, conserva el nombre anterior al tercer
corte del 2026-09-27 — `proposal.md` ya declara explícitamente que no se renombra en esta fase.
Esta lista no la renombra tampoco: continúa el corte C1 sobre ella (ya contiene los commits de
especificación y diseño de este cambio) y crea las ramas de los cortes siguientes con el
identificador correcto del cambio SDD (`column-encryption-and-mfa-totp`), encadenadas cada una a
la anterior, siguiendo el mismo patrón que la parte 1 aplicó cuando detectó la misma obsolescencia
de nombre a mitad de aplicación.

## Nota sobre la estrategia de entrega: el valor de sesión queda superado por la decisión ya
## registrada del propietario, y no se vuelve a plantear la pregunta

El preámbulo de esta sesión proyecta `Delivery strategy: single-pr` y un presupuesto de revisión
de 400 líneas. Es la **misma falsa alarma** que las listas de tareas de `identity-module-and-
password-authentication` e `idempotency-key-infrastructure` ya encontraron y no reabrieron: el
propietario confirmó por escrito, el **2026-09-28**, un presupuesto de **800 líneas de cambio
efectivo por pull request** (`docs/15-flujo-de-trabajo-git.md` §3, citado literalmente en el
encargo de esta fase) y **rechazó explícitamente subirlo**. `proposal.md` («Pronóstico de cortes y
tamaño») y `design.md` §11 van más allá de recomendar cadenas: **afirman que no hay ningún margen
para entregar esto en un solo pull request** — cuatro de los cinco cortes probablemente superan
800 líneas por sí solos, y la expectativa realista declarada es de **diez a quince pull requests
encadenados**, no cinco y desde luego no uno. Un `single-pr` con `size:exception` sobre un cambio
que su propio diseño describe así sería exactamente el error que `docs/15-flujo-de-trabajo-git.md`
existe para prevenir.

Esta lista adopta, por tanto, lo ya resuelto por el propietario en los artefactos aprobados:
**estrategia `auto-chain`, cadena `stacked-to-main`**, presupuesto de 800 líneas, sin excepción de
tamaño. No es una decisión nueva de esta fase: es la misma que gobernó los cambios 5 y 6 y las
tres partes del cambio 7 completo, aplicada aquí por continuidad y sin volver a preguntarla.

## Review Workload Forecast

| Campo | Valor |
|---|---|
| Presupuesto de revisión de esta sesión (guardia literal de la fase) | 400 líneas |
| Presupuesto de revisión vigente para este cambio (propietario, 2026-09-28, no reabierto) | **800 líneas** de cambio efectivo por pull request — prevalece; ver nota arriba |
| Líneas de autor estimadas (código, pruebas, SQL, ADR, documentación; sin `openspec/`, sin código generado de jOOQ) | **~1 700 de una pasada, 2 550 a 5 100 con el multiplicador histórico de 1,5×-3× sin descontar** (`design.md` §11) |
| Riesgo frente al presupuesto de 400 (guardia literal de la fase) | **High** |
| Riesgo frente al presupuesto de 800 vigente | **High** — `design.md` §11 marca C1, C2 y C4 en riesgo «Alto», C3 «Medio», C5 «Bajo»; cuatro de los cinco cortes probablemente exceden 800 líneas por sí solos |
| Pull requests encadenados recomendados | **Sí** — ya decidido por el propietario; el propio diseño anticipa 10 a 15, no 5 |
| División sugerida | Cinco cortes de diseño (C1→C2→C3→C4→C5), cada uno con **al menos un** punto de medición de diff real que decide si se subdivide en 2-3 pull requests — ver la tabla de unidades de trabajo |
| Estrategia de entrega | `auto-chain` — superada de la sesión, resuelta por el propietario (ver nota arriba) |
| Estrategia de cadena | `stacked-to-main` — precedente de los cambios 5, 6 y de las dos partes anteriores del cambio 7 |

```text
Decision needed before apply: No
Chained PRs recommended: Yes
Chain strategy: stacked-to-main
400-line budget risk: High
```

### Nota sobre el límite de quince tareas

Esta lista tiene **15 tareas en total** (4 en C1, 3 en C2, 3 en C3, 3 en C4, 2 en C5) —
**exactamente en el límite** de `openspec/changes/README.md`, no por debajo de él. Es el resultado
de trazar los 27 escenarios y las cinco sondas bloqueantes sin comprimir ninguna tarea de forma
artificial: cada tarea agrupa exactamente lo que un mismo corte de `design.md` §1 asigna, ni una
tarea menos que las piezas que ese corte necesita para poder ejecutar `./mvnw verify` en verde por
sí solo. `proposal.md` ya declaró en voz alta que este cambio **puede superar quince** y que no
queda margen para dividirlo en otro cambio SDD — esta lista cae justo en el borde permitido, no lo
supera, pero tampoco tiene margen: si la aplicación real revela que alguna tarea (por ejemplo 1.4,
la más cargada de C1) necesita partirse en dos para caber en una sola sesión o en 800 líneas, **esa
partición debe declararse como sub-tareas de la misma tarea numerada (1.4a/1.4b) o como pull
requests adicionales dentro del mismo corte, nunca como una tarea nueva de nivel superior**, para
no cruzar el límite de quince que esta lista ya toca.

### Suggested Work Units

| Unit (corte) | Goal | Likely PR(s) | Focused test command | Runtime harness | Rollback boundary |
|---|---|---|---|---|---|
| **C1** | Sobre de llaves: motor puro, tabla de llaves, `ColumnEncryptionService`, migración `V6` con las cuatro tablas, ADR-0023 | PR C1a (`change/column-encryption-and-mfa-totp-c1-schema`, migración + motor puro, base `change/mfa-totp-and-password-recovery`) y probable PR C1b (`...-c1-crypto-service`, puerto/servicio/adaptador/ADR, base C1a) — **medir el diff real al cerrar la tarea 1.4** para confirmar o descartar la partición | `./mvnw -B -pl apps/api/app -am test -Dtest=RolePrivilegeMatrixIT,MultiTenantSchemaIT,DataEncryptionKeyRowSecurityIT,ColumnEncryptionIT -Dsurefire.failIfNoSpecifiedTests=false` | `./mvnw -B verify` en `apps/api`, JDK 25, Docker activo | Revertir el/los pull request(s) completo(s); ningún caso de uso de `identity` los consume todavía. No se revierte por separado de C2 una vez que C2 exista (`TotpCredentialRepository` de C2 no compila sin `ColumnEncryptionService`) |
| **C2** | TOTP: algoritmo RFC 6238 puro, política de verificación, backoff de verificación (D7), puerto/adaptador de credencial | PR C2 (`...-c2-totp`, base C1) — **medir el diff real al cerrar la tarea 2.3**; el propio diseño lo marca «Alto» riesgo, así que puede requerir partirse en `...-c2a-algorithm-and-policy` / `...-c2b-backoff-and-verify` | `./mvnw -B -pl apps/api/app -am test -Dtest=TotpAlgorithmTest,TotpVerificationPolicyTest,VerifyTotpCodeIT,TotpVerificationBackoffIT -Dsurefire.failIfNoSpecifiedTests=false` | `./mvnw -B verify` en `apps/api`, JDK 25, Docker activo | Revertir PR C2 sin fusionar deja C1 completo por sí solo (sobre de llaves sin ningún consumidor de `identity`) |
| **C3** | Códigos de recuperación de MFA (D5, D8): `RecoveryCodeHasher`, `EnrollTotpSecondFactor` completo, `ConsumeRecoveryCode`, aviso de códigos bajos | PR C3 (`...-c3-recovery-codes`, base C2) — **medir el diff real al cerrar la tarea 3.3** | `./mvnw -B -pl apps/api/app -am test -Dtest=EnrollTotpSecondFactorIT,ConsumeRecoveryCodeIT -Dsurefire.failIfNoSpecifiedTests=false` | `./mvnw -B verify` en `apps/api`, JDK 25, Docker activo | Revertir PR C3 sin fusionar deja C2 completo por sí solo (verificación TOTP probada, sin inscripción ni recuperación) |
| **C4** | Edición de `AuthenticationResult`/`AuthenticateWithPassword` (switch exhaustivo), fixture de compilación fallida con su gemelo de control positivo | PR C4 (`...-c4-exhaustive-switch`, base C3) — **medir el diff real al cerrar la tarea 4.3**; «Medio-alto» por el propio diseño, candidato a partirse en `...-c4a-result-and-switch` / `...-c4b-compilation-fixture` | `./mvnw -B -pl apps/api/app -am test -Dtest=ExhaustiveAuthenticationResultSwitchCompilationTest,AuthenticateWithPasswordIT,AuthenticationResultTest -Dsurefire.failIfNoSpecifiedTests=false` | `./mvnw -B verify` en `apps/api`, JDK 25, Docker activo | Revertir PR C4 sin fusionar deja C3 completo por sí solo (inscripción y recuperación probadas contra el `AuthenticationResult` de dos desenlaces de la parte 1, sin los cuatro todavía) |
| **C5** | Redacción de los cinco secretos con control negativo, notas editoriales de `docs/03` y `docs/09`, barrido final de trazabilidad | PR C5 (`...-c5-redaction-and-docs`, base C4) — «Bajo» riesgo, probablemente un único pull request | `./mvnw -B -pl apps/api/app -am test -Dtest=IdentitySecretRedactionIT,IdentityScopeExclusionInventoryTest -Dsurefire.failIfNoSpecifiedTests=false` | `./mvnw -B verify` en `apps/api`, JDK 25, Docker activo | Revertir PR C5 sin fusionar deja C4 completo por sí solo (mecanismo entero probado, sin el barrido final de redacción ni las notas editoriales) |

Ejecutor de todas las tareas: `./mvnw -B verify` en `apps/api`, con `JAVA_HOME` apuntando a JDK 25 y
`MAVEN_OPTS="-Djavax.net.ssl.trustStoreType=Windows-ROOT"` (`apps/api/README.md`; **esta variable
es un apaño local y nunca se compromete en ningún archivo del repositorio** — se documenta aquí
como nota de entorno, no como cambio de archivo). Docker debe estar activo para toda tarea que
ejecute una clase `*IT.java` real contra PostgreSQL o que dispare `generate-sources`; las tareas
que solo tocan ArchUnit, jqwik puro o inventario estático de texto **no** requieren Docker, y cada
tarea de abajo lo marca explícitamente. La integración continua en `ubuntu-latest` es la fuente de
verdad ante cualquier divergencia observada en Windows. Nunca se baja un umbral ni se omite una
prueba para pasar en local.

TDD estricto (`openspec/config.yaml`, `strict_tdd: true`): toda clase de prueba observa **ROJO**
antes de **VERDE**, y esa evidencia se registra en el repositorio — la línea correspondiente de
esta lista y `openspec/changes/column-encryption-and-mfa-totp/apply-progress.md` (crear el archivo
si no existe) —, nunca solo en Engram. **Nunca se inventa evidencia de ROJO.** Una tarea que no
puede observar rojo (una nota documental, una comprobación de que una puerta existente sigue en
verde sin tocarla) lo dice explícitamente en vez de fingirlo.

Todos los mensajes de commit son **convencionales, en inglés, sin ninguna atribución de
herramienta de IA** (`CLAUDE.md`). Cada tarea que produce comportamiento cierra con al menos un
commit de unidad de trabajo en la rama del corte.

---

## C1 — sobre de llaves, motor de cifrado, migración `V6`, ADR-0023

Rama `change/mfa-totp-and-password-recovery` (actual) para 1.1-1.3; posible rama
`change/column-encryption-and-mfa-totp-c1-crypto-service` (base la anterior) para 1.4 si el diff
de 1.1-1.3 ya se acerca a 800 líneas — decidir con la medición de la propia tarea 1.4.

- [ ] 1.1 **Sondas S1 y S5 (bloqueantes de C1, `design.md` §9), ejecutadas fuera del árbol.** No
  requiere Docker para S1 (programa mínimo o `jshell` con JDK 25 puro); requiere Docker para S5.
  **S1**: cifrar un texto conocido con `Cipher.getInstance("AES/GCM/NoPadding")`, una llave e IV
  fijos, y comparar `ciphertext.length` con `plaintext.length + 16`; confirmar que la diferencia es
  exactamente 16 bytes (la etiqueta de 128 bits concatenada al final de `doFinal(...)`). **S5**:
  contra `postgres:18-alpine`, crear la tabla `shared_data_encryption_key` y su índice parcial
  `shared_data_encryption_key_one_active_per_institution` con el DDL exacto de `design.md` decisión
  3, y ejecutar el `INSERT ... ON CONFLICT (institution_id) WHERE status = 'active' DO NOTHING` de
  la decisión 4 dos veces con el mismo `institution_id`; confirmar que la segunda ejecución no
  inserta una segunda fila y no lanza error de ambigüedad de índice. Registrar ambos resultados
  exactos en `apply-progress.md`. **Si S1 no diera 16 bytes exactos**, ajustar el diseño de
  `AesGcmCipher` a lo que la API realmente devuelve antes de la tarea 1.3, y reportar la
  desviación. **Si S5 fallara**, adoptar el respaldo de `design.md` decisión 4 (nombrar el índice
  explícitamente con `ON CONSTRAINT`, revisando la decisión 3 hacia una restricción `EXCLUDE`) y
  reportar la desviación antes de escribir la migración. — `design.md` §9 (S1, S5)

- [x] 1.2 **ROJO/VERDE — migración `V6`, sonda S2, y extensión de las puertas de esquema
  genéricas.** Requiere Docker. ROJO: extender
  `.../test/java/com/confia/schema/RolePrivilegeMatrixIT.java` con las filas de las cuatro tablas
  nuevas (`shared_data_encryption_key`, `identity_mfa_totp_credential`,
  `identity_mfa_recovery_code`, `identity_mfa_totp_backoff`) para los cinco roles, **incluidas las
  sentencias reales** de `SELECT`/`INSERT`/`UPDATE` permitidas y `DELETE` rechazado para
  `confia_admin_app`, y las cuatro operaciones rechazadas para `confia_portal_app` (`design.md`
  §6.1 traza los tres escenarios de «Permisos de acceso...» a esta misma clase); extender
  `.../test/java/com/confia/schema/MultiTenantSchemaIT.java` para que sus puertas genéricas
  recorran las cuatro tablas nuevas sin ninguna entrada de exclusión, y sembrar en su fixture una
  cuenta de personal con `mfa_required` fijado explícitamente en `true` y otra en `false` (cubre el
  escenario «La columna se fija al crear la cuenta, sin ningún dato de rol que la respalde»,
  `design.md` §6.1, fila «`mfa_required`»). Fallan porque `V6` no existe. **Sonda S2** (bloqueante,
  `design.md` §9): en cuanto `V6` compile como SQL pero antes de escribir cualquier adaptador,
  ejecutar `./mvnw -B -pl app generate-sources` y listar `target/generated-sources`, confirmando
  que jOOQ genera exactamente `SharedDataEncryptionKey`, `IdentityMfaTotpCredential`,
  `IdentityMfaRecoveryCode` e `IdentityMfaTotpBackoff`. Si algún nombre difiriera, ajustar el
  nombre de tabla en la migración antes de continuar y reportarlo — nunca escribir un adaptador
  contra un nombre generado distinto del que `TableOwnershipByModuleTest` exige. VERDE: crear
  `apps/api/app/src/main/resources/db/migration/V6__add_mfa_required_and_create_crypto_mfa_tables.sql`
  con el DDL exacto de `design.md` decisión 3 (columna `mfa_required` sin `DEFAULT`, las cuatro
  tablas, el índice parcial de la decisión 3/sonda S1 de esta misma tarea diferida a 1.1, seguridad
  de fila habilitada y forzada en las cuatro, `REVOKE ALL ... FROM PUBLIC` antes de los `GRANT`, sin
  `DELETE` para ningún rol de aplicación). Ejecutar las pruebas de la mitad ROJO: verde. Comprobar
  que ningún otro caso de `MultiTenantSchemaIT` se rompe. Commit convencional (`feat`) sobre la
  migración y (`test`) sobre la extensión de las dos puertas. — Especificación `build-integrity`,
  requisitos «Tablas nuevas de cifrado de columna y MFA...» (los tres escenarios) y «Permisos de
  acceso a las tablas nuevas...» (los tres escenarios); `identity`, requisito «Columna
  `mfa_required`...» (escenario «La columna se fija al crear la cuenta...»); `design.md` §9 (sonda
  S2) y decisiones 1, 3

- [x] 1.3 **ROJO/VERDE — el motor de cifrado puro en `kernel`.** No requiere Docker (JUnit y
  AssertJ puros, sin contenedor). ROJO: crear
  `.../kernel/src/test/java/com/confia/kernel/AesGcmCipherTest.java` (cifra y descifra de ida y
  vuelta con llave de 32 bytes e IV de 12 bytes; un dato adicional autenticado distinto en el
  descifrado lanza `AeadIntegrityException`; el resultado de `encrypt(...)` mide exactamente
  `plaintext.length + 16` más los 12 bytes de IV que el propio códec separa, confirmando en código
  el resultado ya observado por la sonda S1 de la tarea 1.1) y
  `.../kernel/src/test/java/com/confia/kernel/EncryptedColumnValueTest.java` (`format()` produce
  exactamente `v1:<id_dek>:<iv_b64>:<ciphertext_b64>:<tag_b64>`; `parse(...)` de ida y vuelta con
  `format()`; `parse(...)` sobre una cadena sin el prefijo `v1:` lanza una excepción explícita).
  Fallan porque `AesGcmCipher` y `EncryptedColumnValue` no existen. VERDE: crear
  `.../kernel/src/main/java/com/confia/kernel/AesGcmCipher.java` (`design.md` decisión 5, con la
  partición de los últimos 16 bytes de `doFinal(...)` como etiqueta, confirmada por S1) y
  `EncryptedColumnValue.java` (`record` puro, sin secreto que redactar: solo transporta bytes ya
  cifrados y un identificador de texto). Ejecutar ambas suites: verde. Commit convencional (`feat`)
  sobre el motor puro. — `design.md` decisión 5; ADR-0023 (alcance (a), aún sin redactar — lo hace
  la tarea 1.4)

- [ ] 1.4 **ROJO/VERDE — puerto y servicio de `shared.crypto`, adaptador jOOQ, creación perezosa de
  la DEK (decisión 4), y ADR-0023.** Requiere Docker. ROJO: crear
  `.../test/java/com/confia/schema/DataEncryptionKeyRowSecurityIT.java` (dos instituciones, cada
  una con al menos una DEK activa; la segunda, con su propio contexto de sesión y
  `confia_admin_app`, solo obtiene sus propias filas; sin `app.institution_id` establecido, la
  consulta devuelve cero filas, no un error de permiso — los dos escenarios de «Una institución no
  lee la llave de datos de otra» y «Sin contexto...» de `build-integrity`) y
  `.../test/java/com/confia/shared/crypto/ColumnEncryptionIT.java` (el secreto se cifra y se
  consulta con SQL crudo: el valor almacenado empieza por `v1:` y no contiene el secreto en claro
  en ninguna parte de la cadena; descifrar el mismo valor sustituyendo el identificador de fila de
  los datos adicionales autenticados por el de otra cuenta falla por fallo de autenticación de
  GCM — los dos escenarios de «Cifrado a nivel de columna...» de `identity`; y dos inscripciones
  concurrentes de la primera DEK de una institución, sincronizadas con `CyclicBarrier` justo antes
  del `INSERT ... ON CONFLICT`, terminan con exactamente una fila activa). Fallan porque
  `DataEncryptionKeyRepository`, `ColumnEncryptionService` y su adaptador no existen. VERDE: crear
  `.../shared/crypto/package-info.java` (`@NamedInterface`, sin segmento de capa, `design.md`
  decisión 2), `DataEncryptionKeyRepository.java`, `DataEncryptionKeyId.java`,
  `DataEncryptionKeyMaterial.java` (`toString()` redactado explícito, precedente de `Argon2Pepper`)
  y `ColumnEncryptionMasterKey.java` (`toString()` redactado, 32 bytes exactos por constructor,
  `design.md` decisión 2/D1) y `ColumnEncryptionService.java` (`encryptForNewValue`/`decrypt`,
  AAD `tabla|columna|institución:fila`, `design.md` decisión 5); crear
  `.../shared/infrastructure/JooqDataEncryptionKeyRepository.java` (directamente en
  `com.confia.shared.infrastructure`, **sin** sub-paquete `crypto.infrastructure` — `design.md`
  decisión 2 explica por qué el prefijo `Shared` de `TableOwnershipByModuleTest` lo exige así) con
  `findActiveOrCreate(...)` sobre el `INSERT ... ON CONFLICT ... DO NOTHING` más `SELECT` final de
  la decisión 4, ya confirmado por la sonda S5 de la tarea 1.1. Crear
  `docs/adr/ADR-0023-sobre-de-llaves-para-cifrado-de-columna.md` con el alcance exacto de D4 de la
  propuesta (algoritmo y formato de sobre, esquema de la tabla de llaves, fuente de la KEK por
  precedente de D1, ubicación del motor puro y del puerto de gestión de llaves por D3; fuera de
  alcance: ejecución de la rotación real y búsqueda determinista por HMAC). Ejecutar las dos
  suites de la mitad ROJO: verde. **Medir el diff real de este corte** con
  `git diff --numstat change/mfa-totp-and-password-recovery...HEAD -- . ':(exclude)openspec' ':(exclude)docs/adr' ':(exclude)**/generated/**'`
  (o desde la base de PR C1a si 1.4 vive en su propia rama); si supera 800 líneas, detener y
  reportar los puntos de corte candidatos medidos entre 1.2/1.3 y 1.4, verificando cada mitad por
  separado con `./mvnw -B verify` antes de proponerla — no decidir el corte en silencio. Verificar
  en checkout limpio con `./mvnw -B verify`; empujar la rama y confirmar la integración continua en
  verde. Commit convencional (`feat`) sobre el puerto/servicio/adaptador y (`docs`) sobre el ADR. —
  Especificación `build-integrity`, requisito «Tablas nuevas...» (escenarios de aislamiento por
  institución); `identity`, requisito «Cifrado a nivel de columna...» (ambos escenarios);
  `design.md` decisiones 2, 4 y ADR-0023 completo

---

## C2 — TOTP: algoritmo, política de verificación, límite de tasa (D7)

Rama `change/column-encryption-and-mfa-totp-c2-totp`, base C1.

- [ ] 2.1 **ROJO/VERDE — `TotpAlgorithm` y `TotpCode` contra los seis vectores de RFC 6238
  derivados.** No requiere Docker (JUnit y AssertJ puros). ROJO: crear
  `.../test/java/com/confia/identity/domain/TotpAlgorithmTest.java` con los seis vectores del
  apéndice de `exploration.md` (secreto compartido ASCII `12345678901234567890`, SHA-1), **citando
  el valor de ocho dígitos del RFC** (`94287082`, `07081804`, `14050471`, `89005924`, `69279037`,
  `65353130`) en un comentario o constante junto a cada aserción de seis dígitos, para que quede
  escrito que el valor de seis está **derivado**, no transcrito del RFC; el caso del tiempo
  `1234567890` afirma explícitamente que `TotpAlgorithm.generate(...)` devuelve la cadena
  `"005924"` — **nunca** un entero ni una cadena sin el cero a la izquierda — comparando con
  `isEqualTo("005924")` y no con una comparación numérica, para que el aviso de `exploration.md`
  (apéndice, «un entero... pasaría comparando 5924») quede demostrado y no solo citado; y
  `.../test/java/com/confia/identity/domain/TotpCodeTest.java` afirma que el constructor rechaza
  cualquier cadena que no tenga exactamente 6 dígitos, incluida una de 4 dígitos sin relleno.
  Fallan porque `TotpAlgorithm` y `TotpCode` no existen. VERDE: crear
  `.../identity/domain/TotpAlgorithm.java` (`counterFor`, `generate`, con `String.format("%06d",
  ...)` — nunca una conversión numérica sin relleno) y `TotpCode.java` (`design.md` decisión 7).
  Ejecutar ambas suites: verde. Commit convencional (`feat`) sobre el algoritmo puro. —
  `exploration.md`, apéndice (los dos avisos); `design.md` decisión 7

- [ ] 2.2 **ROJO/VERDE — `TotpVerificationPolicy`, `PlainTotpSecret`, y el puerto/adaptador de
  credencial TOTP.** Requiere Docker para la mitad del adaptador; no lo requiere para la política
  (unitaria pura). ROJO: crear
  `.../test/java/com/confia/identity/domain/TotpVerificationPolicyTest.java` (un código dentro de
  la ventana ±1 se acepta; el mismo código, ya con un contador aceptado igual o mayor, se rechaza
  aunque sea matemáticamente válido para ese contador — anti-repetición) y
  `.../test/java/com/confia/identity/infrastructure/JooqTotpCredentialRepositoryIT.java`,
  extendiendo `CommittingPostgresIntegrationTest`: inserta una credencial con el secreto ya cifrado
  por `ColumnEncryptionService` (de C1) y `last_accepted_counter = -1`; lee la fila y confirma que
  el secreto se descifra de vuelta al valor original; un `UPDATE` condicional
  (`WHERE last_accepted_counter < ?`) sobre un contador ya avanzado por otra sesión devuelve cero
  filas. Fallan porque `TotpVerificationPolicy`, `PlainTotpSecret`, `TotpCredentialRepository` y su
  adaptador no existen. VERDE: crear `.../identity/domain/TotpVerificationPolicy.java`
  (`matchingCounter`, comparación con `MessageDigest.isEqual`, nunca `String.equals`, `design.md`
  decisión 7) y `PlainTotpSecret.java` (`toString()` redactado explícito, 20 bytes, `design.md`
  decisión 9); crear `.../identity/application/TotpCredentialRepository.java` (puerto) y
  `.../identity/infrastructure/JooqTotpCredentialRepository.java` (adaptador, prefijo `Identity`).
  Ejecutar las tres suites: verde. Commit convencional (`feat`) sobre la política y (`feat`) sobre
  el puerto/adaptador de credencial. — Especificación `identity`, requisito «Inscripción y
  verificación del segundo factor TOTP...» (fundamento de la política; los dos escenarios
  completos se prueban en 2.3, sobre `VerifyTotpCode`); `design.md` decisiones 5 (AAD por fila,
  reutilizado aquí) y 7

- [ ] 2.3 **ROJO/VERDE — `TotpVerificationBackoffStore` (D7), y `VerifyTotpCode` completo con
  límite de tasa.** Requiere Docker. ROJO: crear
  `.../test/java/com/confia/identity/infrastructure/JooqTotpVerificationBackoffStoreIT.java` (el
  `claim(...)` de `design.md` decisión 8 reproduce el `INSERT ... ON CONFLICT DO UPDATE ...
  RETURNING` de `JooqLoginBackoffStore`, con `StaffAccountId` como clave) y
  `.../test/java/com/confia/identity/application/VerifyTotpCodeIT.java`, extendiendo
  `CommittingPostgresIntegrationTest`, con una credencial TOTP sembrada **directamente vía
  `JooqTotpCredentialRepository`** (sin pasar por `EnrollTotpSecondFactor`, que todavía no existe —
  ver la discrepancia 2 resuelta arriba): un código válido en la ventana ±1 se acepta; el mismo
  código, ya aceptado, se rechaza aunque siga siendo matemáticamente válido; y
  `.../test/java/com/confia/identity/application/TotpVerificationBackoffIT.java`: el sexto intento
  fallido de verificación en la misma ventana de 15 minutos activa el retroceso exponencial antes
  de evaluar ese código, y el ciclo de retroceso queda auditado con su duración
  (`identity.mfa.totp_verification.backoff_applied`). Fallan porque `TotpVerificationBackoffStore`
  y `VerifyTotpCode` no existen. VERDE: crear `.../identity/application/TotpVerificationBackoffStore.java`
  (puerto) y `.../identity/infrastructure/JooqTotpVerificationBackoffStore.java` (adaptador,
  reutilizando `BackoffPolicy`/`BackoffState` sin nueva constante, `design.md` decisión 8); crear
  `.../identity/application/VerifyTotpCode.java` (orquesta `claim` → `SELECT` credencial →
  `ColumnEncryptionService.decrypt` → `TotpVerificationPolicy.matchingCounter` → `UPDATE`
  condicional del contador → `save` del backoff → auditoría de éxito/fallo, `design.md` §4.2).
  Ejecutar las tres suites: verde. **Medir el diff real de este corte** con
  `git diff --numstat <base-de-C1>...HEAD -- . ':(exclude)openspec' ':(exclude)docs/adr' ':(exclude)**/generated/**'`;
  si supera 800, detener y reportar los puntos de corte candidatos medidos entre 2.1-2.2 y 2.3.
  Verificar en checkout limpio con `./mvnw -B verify`; empujar la rama y confirmar la integración
  continua en verde. Commit convencional (`feat`) sobre el backoff de verificación y (`feat`) sobre
  `VerifyTotpCode`. — Especificación `identity`, requisitos «Inscripción y verificación del segundo
  factor TOTP...» (los dos escenarios completos) y «Límite de tasa sobre la verificación de código
  TOTP» (su escenario); `design.md` decisión 8 completa; §4.2

---

## C3 — códigos de recuperación de MFA (D5, D8), `EnrollTotpSecondFactor` completo

Rama `change/column-encryption-and-mfa-totp-c3-recovery-codes`, base C2.

- [ ] 3.1 **ROJO/VERDE — `RecoveryCodeHasher` (D5) y sus objetos de valor.** No requiere Docker
  (JUnit, AssertJ y Bouncy Castle puros, sin contenedor). ROJO: crear
  `.../test/java/com/confia/identity/infrastructure/BouncyCastleRecoveryCodeHasherTest.java`: un
  código de recuperación de MFA se hashea y verifica de ida y vuelta con el mismo perfil Argon2id
  y códec `$argon2id$` que `Argon2PhcCodec`/`Argon2Profile` ya prueban para contraseñas (reutilizado,
  no reabierto — `design.md` D5); un código distinto no verifica contra el mismo hash. Falla
  porque `RecoveryCodeHasher`, `PlainRecoveryCode` y `StoredRecoveryCodeHash` no existen. VERDE:
  crear `.../identity/domain/PlainRecoveryCode.java` (10 caracteres, `toString()` redactado
  explícito) y `StoredRecoveryCodeHash.java` (`toString()` redactado, mismo trato que
  `StoredPasswordHash` aunque cargue un hash, `design.md` decisión 9); crear
  `.../identity/application/RecoveryCodeHasher.java` (puerto propio, **no** reutiliza
  `PasswordHasher`, `design.md` D5) y
  `.../identity/infrastructure/BouncyCastleRecoveryCodeHasher.java` (adaptador sobre el mismo
  `Argon2Profile`/`Argon2PhcCodec` de la parte 1). Ejecutar la suite: verde. Commit convencional
  (`feat`) sobre el puerto y el adaptador. — `design.md` decisión 5 (D5) completa; nota de §10 de
  `docs/03-seguridad.md` §4.3 (redactada en la tarea 5.2)

- [ ] 3.2 **ROJO/VERDE — `RecoveryCodeRepository`, y `EnrollTotpSecondFactor` completo (secreto
  TOTP + diez códigos de recuperación, un solo evento de auditoría).** Requiere Docker. ROJO:
  crear `.../test/java/com/confia/identity/application/EnrollTotpSecondFactorIT.java`, extendiendo
  `CommittingPostgresIntegrationTest`: inscribir el segundo factor de una cuenta genera un secreto
  TOTP de 20 bytes cifrado y una credencial con `last_accepted_counter = -1`, **y** exactamente diez
  códigos de recuperación de MFA distintos, cada uno hasheado, devueltos **en claro una única vez**
  al llamador — ninguna consulta posterior a `identity_mfa_recovery_code` puede recuperarlos en
  claro —, con un único evento `identity.mfa.enrolled` auditado (cubre «Los diez códigos se generan
  y se muestran una única vez» de `identity`). Falla porque `RecoveryCodeRepository` y
  `EnrollTotpSecondFactor` no existen. VERDE: crear
  `.../identity/application/RecoveryCodeRepository.java` (puerto) y
  `.../identity/infrastructure/JooqRecoveryCodeRepository.java` (adaptador, prefijo `Identity`);
  crear `.../identity/application/EnrollTotpSecondFactor.java` que compone, dentro de una única
  `TransactionRunner.execute(...)`: `SecureRandom` de 20 bytes → `ColumnEncryptionService
  .encryptForNewValue(...)` → `INSERT` de la credencial (vía `TotpCredentialRepository` de C2) →
  diez `PlainRecoveryCode` generados → `RecoveryCodeHasher.hash(...)` de cada uno → `INSERT ×10`
  (vía `RecoveryCodeRepository`) → `AuditLogWriter.append(identity.mfa.enrolled)` → devuelve los
  diez códigos en claro (`design.md` §4.1). Crear también
  `.../test/java/com/confia/identity/application/ConsumeRecoveryCodeIT.java`: usar uno de los diez
  códigos lo invalida (`used_at` fijado) sin afectar a los nueve restantes, y un segundo intento con
  el mismo código se rechaza (cubre «Usar un código lo invalida...»). Falla porque
  `ConsumeRecoveryCode` no existe. VERDE: crear `.../identity/application/ConsumeRecoveryCode.java`
  (`SELECT` de códigos sin usar de la cuenta → `RecoveryCodeHasher.matches(...)` por cada fila →
  `UPDATE ... SET used_at = now() WHERE id = ? AND used_at IS NULL RETURNING id`, cero filas =
  derrota concurrente, `design.md` §4.3). Ejecutar las tres suites: verde. Commit convencional
  (`feat`) sobre el puerto/adaptador de códigos, (`feat`) sobre `EnrollTotpSecondFactor` y (`feat`)
  sobre `ConsumeRecoveryCode`. — Especificación `identity`, requisitos «Diez códigos de
  recuperación de MFA...» (los dos escenarios); `design.md` §4.1, §4.3

- [ ] 3.3 **ROJO/VERDE — aviso al quedar con menos de tres códigos de recuperación (D8).** Requiere
  Docker (ejecuta sobre `ConsumeRecoveryCode` de 3.2). ROJO: extender
  `ConsumeRecoveryCodeIT` con: una cuenta con diez códigos, siete ya usados, que al usar el octavo
  dispara la señal de aviso auditada con el conteo de dos restantes (cubre «Consumir el octavo
  código deja el aviso activado»); y una cuenta con diez códigos, seis ya usados, que al usar el
  séptimo (quedando en exactamente tres) **no** dispara ninguna señal de aviso, porque el umbral
  exige quedar **por debajo** de tres, no en tres (cubre «Quedar con exactamente tres códigos no
  activa el aviso»); y una aserción explícita de que ningún adaptador de envío de correo se invoca
  —no existe ninguno en este cambio— cubriendo «El aviso queda calculado y auditado, sin ningún
  correo enviado». Fallan porque `ConsumeRecoveryCode` no calcula todavía el conteo restante ni
  audita el aviso. VERDE: extender `ConsumeRecoveryCode.java` con
  `SELECT COUNT(*)` de códigos restantes sin usar tras el consumo, y
  `AuditLogWriter.append(identity.mfa.recovery_codes.low)` con el conteo cuando ese número queda
  por debajo de tres (`design.md` §4.3, D8). Ejecutar la suite extendida: verde. **Medir el diff
  real de este corte** con
  `git diff --numstat <base-de-C2>...HEAD -- . ':(exclude)openspec' ':(exclude)docs/adr' ':(exclude)**/generated/**'`;
  si supera 800, detener y reportar los puntos de corte candidatos entre 3.1, 3.2 y 3.3. Verificar
  en checkout limpio con `./mvnw -B verify`; empujar la rama y confirmar la integración continua en
  verde. Commit convencional (`feat`) sobre el aviso de códigos bajos. — Especificación `identity`,
  requisitos «Aviso al quedar con menos de tres códigos...» (los dos escenarios de umbral) y
  «Ausencia de envío real del aviso...» (su escenario); `design.md` decisión D8, §4.3

---

## C4 — `AuthenticationResult` de cuatro desenlaces, `switch` exhaustivo, fixture de compilación

Rama `change/column-encryption-and-mfa-totp-c4-exhaustive-switch`, base C3. **Modifica código ya
archivado y verificado de la parte 1** (`AuthenticationResult.java`, `AuthenticateWithPassword.java`),
tal como `proposal.md` («La costura con la parte 1») declara explícitamente que es seguro y
necesario.

- [ ] 4.1 **ROJO/VERDE — edición de `AuthenticationResult` y `switch` exhaustivo en
  `AuthenticateWithPassword`, con lectura de `mfa_required` y del estado de inscripción TOTP.**
  Requiere Docker para la mitad de integración; no lo requiere para la reflexión de
  `AuthenticationResultTest`. ROJO: extender
  `.../test/java/com/confia/identity/domain/AuthenticationResultTest.java` (ya existente de la
  parte 1) para afirmar que la cláusula `permits` contiene exactamente `Authenticated`, `Rejected`,
  `SecondFactorRequired` y `SecondFactorEnrollmentRequired` — falla porque hoy solo hay dos.
  **Sonda S4 (no bloqueante, `design.md` §9)**: ejecutar
  `./mvnw -B -pl app dependency:tree -Dincludes=com.tngtech.archunit` para fijar la versión exacta
  de ArchUnit y revisar su documentación de `JavaClass`/`Dependency` en busca de un tipo de acceso
  equivalente a `INSTANCEOF`; registrar el resultado en `apply-progress.md`. Si existe, valorar una
  regla de regresión de refuerzo (no sustituye el mecanismo principal de 4.2); si no existe,
  declararlo sin construir — no bloquea esta tarea ni ninguna otra. VERDE: editar
  `.../identity/domain/AuthenticationResult.java` para añadir `SecondFactorRequired` y
  `SecondFactorEnrollmentRequired` al `permits` (cada uno con `StaffAccountId` e `InstitutionId`,
  sin campo de alcance de autorización); editar
  `.../identity/application/AuthenticateWithPassword.java` para convertir los dos `instanceof
  Authenticated` (líneas 155 y 175 de la parte 1) en un `switch` exhaustivo sin `default`, e
  insertar, tras la verificación de contraseña y antes de devolver el resultado, la lectura de
  `mfa_required` y del estado de inscripción TOTP de la cuenta (tabla de `proposal.md`, «Cómo se
  determina qué cuenta necesita segundo factor»): `mfa_required = false` → `Authenticated` sin
  cambios; `true` con secreto inscrito → `SecondFactorRequired`; `true` sin secreto inscrito →
  `SecondFactorEnrollmentRequired`; ninguna de las dos ramas nuevas avanza el contador de
  retroceso ni audita como fallo. Ejecutar `AuthenticationResultTest`: verde (el resto de
  `AuthenticateWithPassword` se prueba de extremo a extremo en la tarea 4.3). Commit convencional
  (`feat`) sobre la edición de `AuthenticationResult` y `AuthenticateWithPassword`. —
  Especificación `identity`, requisitos «Resultado tipado de la autenticación con cuatro
  desenlaces» (escenario «Los cuatro desenlaces son exactamente...») y «Exhaustividad forzada por
  el compilador...» (fundamento, sin el fixture de compilación fallida todavía — eso es 4.2);
  `proposal.md`, «La costura con la parte 1» y «Cómo se determina...»; `design.md` §9 (sonda S4,
  no bloqueante)

- [ ] 4.2 **Sonda S3 (bloqueante, en cuanto exista el `switch` real de 4.1) y ROJO/VERDE — el
  fixture de compilación fallida con su gemelo de control positivo.** No requiere Docker
  (`javax.tools.JavaCompiler` en memoria, sin PostgreSQL). ROJO: crear
  `.../test/java/com/confia/architecture/ExhaustiveAuthenticationResultSwitchCompilationTest.java`
  con los dos métodos de `design.md` decisión 6 (`aSwitchMissingOneOutcomeFailsToCompile` y
  `theRealFourBranchSwitchCompilesCleanly`, el gemelo de control positivo — **las dos mitades van
  en la misma tarea: sin el control positivo, un arnés de compilación roto que siempre reportara
  fallo pasaría igual**), y los dos recursos de texto
  `.../test/resources/architecture-fixtures/non-exhaustive-switch.java.txt` (el `switch` real
  **sin** la rama de `SecondFactorRequired`) y `.../exhaustive-switch.java.txt` (el `switch`
  completo con las cuatro ramas), ambos con extensión `.txt` y **nunca** `.java`, para que Maven no
  intente compilarlos como parte de la construcción normal. Falla al compilar porque la clase de
  prueba no existe. **Sonda S3**: ejecutar
  `./mvnw -B -pl app test -Dtest=ExhaustiveAuthenticationResultSwitchCompilationTest` en cuanto el
  fixture y el `switch` real existan, y confirmar que la compilación del recurso no exhaustivo
  falla con algún diagnóstico `ERROR` que contiene la palabra «exhaustive» (sin distinguir
  mayúsculas); registrar el mensaje exacto en `apply-progress.md`. Si el texto exacto difiriera,
  ajustar la aserción a lo que `javac` realmente produce en JDK 25 — **nunca** relajarla a solo
  «falla sin más». VERDE: confirmar que ambos métodos pasan con el fixture ya escrito (no hay
  código de producción adicional que escribir: el mecanismo es la propia clase de prueba). Commit
  convencional (`test`) sobre el fixture de compilación fallida. — Especificación `identity`,
  requisito «Exhaustividad forzada por el compilador...» (escenario «Un desenlace no manejado rompe
  la compilación...»); `design.md` decisión 6 completa, §9 (sonda S3)

- [ ] 4.3 **ROJO/VERDE — `AuthenticateWithPasswordIT` extendido con los cuatro desenlaces
  completos, y cierre de este corte.** Requiere Docker. ROJO: extender
  `.../test/java/com/confia/identity/application/AuthenticateWithPasswordIT.java` (ya existente de
  la parte 1) con: una cuenta `mfa_required = true` con secreto TOTP ya inscrito produce
  `SecondFactorRequired`, sin avanzar el contador de retroceso ni auditar un intento fallido (cubre
  «Cuenta con `mfa_required` en `true` y secreto TOTP ya inscrito» y «`SecondFactorRequired` no
  avanza el contador de retroceso ni se audita como fallo»); una cuenta `mfa_required = true` sin
  secreto inscrito produce `SecondFactorEnrollmentRequired`, misma garantía sobre el retroceso
  (cubre «Cuenta con `mfa_required` en `true` sin ningún secreto TOTP inscrito»); una cuenta
  sembrada con `mfa_required = false` sigue produciendo `Authenticated` sin ningún cambio de
  comportamiento respecto a la parte 1 (regresión, «Contraseña correcta produce el desenlace
  `Authenticated`...»); y una cuenta hipotética con `mfa_required = false` fijado explícitamente
  por quien la creó, sin que ningún proceso de este cambio lo corrija a `true` aunque su rol futuro
  incluyera un permiso financiero (cubre «Este cambio no deriva `mfa_required` de ningún permiso
  todavía»). Fallan porque `AuthenticateWithPassword` de 4.1 aún no se probó de extremo a extremo
  contra PostgreSQL real con cuentas de MFA sembradas. VERDE: confirmar en verde sin cambios de
  producción adicionales — 4.1 ya entrega el comportamiento completo; si el reloj o la
  concurrencia revelaran un caso no cubierto, corregir `AuthenticateWithPassword` y volver a
  ejecutar. Ejecutar `AuthenticationResultTest` de nuevo (regresión sobre `Rejected` sin cambios).
  **Medir el diff real de este corte** con
  `git diff --numstat <base-de-C3>...HEAD -- . ':(exclude)openspec' ':(exclude)docs/adr' ':(exclude)**/generated/**'`;
  si supera 800, detener y reportar los puntos de corte candidatos entre 4.1, 4.2 y 4.3. Verificar
  en checkout limpio con `./mvnw -B verify`; empujar la rama y confirmar la integración continua en
  verde. Commit convencional (`test`) sobre la extensión de `AuthenticateWithPasswordIT`. —
  Especificación `identity`, requisitos «MFA obligatoria para cuentas marcadas con
  `mfa_required`» (los dos escenarios), «Resultado tipado... cuatro desenlaces» (los dos
  escenarios de regresión) y «Columna `mfa_required`...» (escenario «Este cambio no deriva...»)

---

## C5 — redacción de los cinco secretos, notas editoriales, cierre de trazabilidad

Rama `change/column-encryption-and-mfa-totp-c5-redaction-and-docs`, base C4.

- [ ] 5.1 **ROJO/VERDE — extensión de `IdentitySecretRedactionIT` con los cinco secretos nuevos, y
  el control negativo de la propia herramienta de barrido.** Requiere Docker. ROJO: extender
  `.../test/java/com/confia/identity/IdentitySecretRedactionIT.java` (ya existente de la parte 1)
  para recoger, sobre una inscripción TOTP real completa (secreto, cifrado con KEK/DEK, diez
  códigos de recuperación) y una verificación fallida deliberada (AAD manipulado para forzar un
  fallo de autenticación de GCM): `toString()` de `PlainTotpSecret`, `PlainRecoveryCode`,
  `StoredRecoveryCodeHash`, `ColumnEncryptionMasterKey` y `DataEncryptionKeyMaterial`; los mensajes
  de las excepciones lanzadas (incluida `AeadIntegrityException`); y el contenido de las filas
  escritas en las cuatro tablas nuevas y en `shared_audit_log`. Ninguno de esos textos puede
  contener el secreto TOTP en claro, un código de recuperación en claro, la KEK ni una DEK en
  claro. Crear, en el mismo commit, el fixture de control negativo:
  `.../identity/testsupport/fixture/LeakingMfaSecretFixture.java` (un `record` **sin**
  `toString()` sobrescrito, la misma forma que causó el tercer hallazgo bloqueante de la parte 1) y
  su prueba `theRedactionSweepDetectsARealLeak` en un método distinto, que afirma que el propio
  fixture **sí** filtra el valor de prueba por su `toString()` generado — demostrando que el
  barrido de redacción sabría detectar una fuga real, no solo que no encontró ninguna porque nunca
  miró (`design.md` decisión 9). Fallan porque los cinco objetos de valor nuevos y el fixture aún
  no están cubiertos por esta clase. VERDE: confirmar en verde sin ningún cambio de producción —
  los cinco objetos de valor ya llevan `toString()` redactado desde las tareas 1.4, 2.2 y 3.1; si
  alguno filtrara, corregirlo aquí antes de continuar, nunca relajar la aserción. Commit
  convencional (`test`) sobre la extensión de redacción y el control negativo. — Especificación
  `identity`, requisito «Ningún secreto nuevo de este cambio es observable...» (su escenario);
  `design.md` decisión 9 completa

- [ ] 5.2 **Notas editoriales fechadas, corrección de `docs/09`, barrido final de trazabilidad de
  los 27 escenarios, y verificación final del cambio completo.** Requiere Docker para la
  verificación final y para extender `IdentityScopeExclusionInventoryTest`; no lo requiere para las
  notas documentales. Sin evidencia de ROJO propia para las notas: son ediciones de texto, no
  comportamiento nuevo — se dice así en vez de fingirlo. Extender
  `.../test/java/com/confia/identity/IdentityScopeExclusionInventoryTest.java` (ya existente de la
  parte 1) con una cuarta afirmación de conjunto no vacío: una DEK marcada `retired` con al menos
  un valor cifrado que la referencia sigue descifrándose correctamente, y ningún trabajo programado
  la recifra con la DEK activa (cubre «Ninguna DEK retirada se recifra automáticamente»,
  `identity`, requisito «Ausencia de ejecución real de la rotación...»); esta es una prueba, sin
  evidencia de ROJO propia sobre código de producción, porque el requisito es una ausencia
  declarada, no un comportamiento a construir. Añadir en `docs/03-seguridad.md` la nota editorial
  fechada 2026-09-28 **sobre §7.3** (tras la tabla de gestión y rotación de llaves): la tabla que
  esa sección llama `data_key` se entrega como `shared_data_encryption_key`, con el prefijo de
  módulo que exige la regla 3 de ADR-0015, gestionada por `com.confia.shared.crypto` (ADR-0023); y
  la nota **sobre §4.3** (tras la tabla de roles y MFA): la tabla que esa sección llama `user_mfa`
  se entrega como `identity_mfa_totp_credential`, los códigos de recuperación viven en
  `identity_mfa_recovery_code`, y el límite de tasa en `identity_mfa_totp_backoff`, con los códigos
  de recuperación hasheados con el mismo perfil Argon2id pero detrás del puerto propio
  `RecoveryCodeHasher` (D5), no de `PasswordHasher` (`design.md` decisión 10). Añadir la adenda a
  la nota ya existente de §6.1: las cuatro tablas de este cambio reciben el mismo trato que
  `identity_login_backoff` — `SELECT`/`INSERT`/`UPDATE` para `confia_admin_app` sin `DELETE`,
  `SELECT` para `confia_readonly`, ningún privilegio para `confia_portal_app`. Corregir
  `docs/09-roadmap-y-fases.md` (líneas 104-110 citadas por `design.md` decisión 10): el texto
  actual sigue nombrando `mfa-totp-and-password-recovery` como un cambio único; pasa a nombrar
  `column-encryption-and-mfa-totp` (este cambio) y `password-recovery-token` (el tercero de la
  secuencia) por separado, seguidos de `session-tokens-and-web-layer` como el cuarto. **Barrido
  final de trazabilidad**: confirmar, releyendo `specs/identity/spec.md` y
  `specs/build-integrity/spec.md`, que los 21 y los 6 escenarios (27 en total) quedan cada uno
  cubierto por al menos una prueba real ya ejecutada en verde en algún corte anterior — usar la
  tabla de trazabilidad de esta misma lista (introducción) como lista de verificación, no la de
  `design.md` §6.1 sin volver a contarla contra los archivos. **Medir el diff real de este corte**
  con `git diff --numstat <base-de-C4>...HEAD -- . ':(exclude)openspec' ':(exclude)docs/adr' ':(exclude)**/generated/**'`.
  Verificación final en checkout limpio, con `JAVA_HOME` en JDK 25, `MAVEN_OPTS` con el almacén de
  confianza `Windows-ROOT` y Docker activo: `./mvnw -B verify` en `apps/api`, incluida la puerta de
  cobertura JaCoCo (95 % en `com.confia.*.domain`) y mutación PIT (80) sobre los paquetes `domain`
  nuevos de este cambio. Empujar la rama y confirmar que la integración continua termina en verde.
  Commit convencional (`docs`) sobre las notas editoriales y `docs/09`, y (`test`) sobre la
  extensión de `IdentityScopeExclusionInventoryTest`. — `design.md` decisión 10 completa;
  Criterios de éxito de `proposal.md` (todos)

---

## Trazabilidad de los 27 escenarios, contada contra los archivos de delta

**`identity` (21 escenarios, `specs/identity/spec.md`)**

| Requisito | Escenario | Tarea | Prueba |
|---|---|---|---|
| Cifrado de columna | Formato `v1:` sin secreto en claro | 1.4 | `ColumnEncryptionIT` |
| | AAD de otra fila falla al descifrar | 1.4 | `ColumnEncryptionIT` |
| TOTP RFC 6238 | Código válido en ventana ±1 se acepta | 2.3 | `VerifyTotpCodeIT` |
| | Código ya aceptado no se reutiliza | 2.3 | `VerifyTotpCodeIT` |
| Límite de tasa TOTP | Sexto intento activa retroceso | 2.3 | `TotpVerificationBackoffIT` |
| 10 códigos de recuperación | Se generan y se muestran una vez | 3.2 | `EnrollTotpSecondFactorIT` |
| | Usar uno no afecta a los otros nueve | 3.2 | `ConsumeRecoveryCodeIT` |
| Aviso de códigos bajos | Octavo código deja el aviso activo | 3.3 | `ConsumeRecoveryCodeIT` |
| | Con exactamente tres no se activa | 3.3 | `ConsumeRecoveryCodeIT` |
| `mfa_required` | Se fija al crear la cuenta, sin rol | 1.2 | `MultiTenantSchemaIT` (siembra) |
| | No se deriva de ningún permiso todavía | 4.3 | `AuthenticateWithPasswordIT` |
| Exhaustividad del compilador | Desenlace no manejado rompe la compilación | 4.2 | `ExhaustiveAuthenticationResultSwitchCompilationTest` |
| | `SecondFactorRequired` no avanza retroceso ni audita fallo | 4.3 | `AuthenticateWithPasswordIT` |
| Ningún secreto observable | Los cinco secretos nuevos, no observables | 5.1 | `IdentitySecretRedactionIT` + control negativo |
| Ausencia de rotación real | Ninguna DEK retirada se recifra | 5.2 | `IdentityScopeExclusionInventoryTest` |
| Ausencia de envío del aviso | Se audita, no se envía correo | 3.3 | `ConsumeRecoveryCodeIT` |
| MFA obligatoria (modificado) | Secreto inscrito → `SecondFactorRequired` | 4.3 | `AuthenticateWithPasswordIT` |
| | Sin secreto → `SecondFactorEnrollmentRequired` | 4.3 | `AuthenticateWithPasswordIT` |
| Resultado tipado, 4 desenlaces (modificado) | Sin MFA → `Authenticated` (regresión) | 4.3 | `AuthenticateWithPasswordIT` |
| | Rechazo → `Rejected` (regresión) | 4.3 | `AuthenticateWithPasswordIT` |
| | Los cuatro desenlaces son exactamente el `permits` | 4.1 | `AuthenticationResultTest` |

**`build-integrity` (6 escenarios, `specs/build-integrity/spec.md`)**

| Requisito | Escenario | Tarea | Prueba |
|---|---|---|---|
| Tablas nuevas | Puertas genéricas pasan sin exclusión | 1.2 | `MultiTenantSchemaIT` |
| | Una institución no lee la DEK de otra | 1.4 | `DataEncryptionKeyRowSecurityIT` |
| | Sin contexto, cero filas | 1.4 | `DataEncryptionKeyRowSecurityIT` |
| Privilegios | Matriz cubre 4 tablas × 5 roles | 1.2 | `RolePrivilegeMatrixIT` |
| | `confia_admin_app` lee/inserta/actualiza, no borra | 1.2 | `RolePrivilegeMatrixIT` |
| | `confia_portal_app` sin ningún privilegio | 1.2 | `RolePrivilegeMatrixIT` |

Ningún escenario del delta queda sin tarea asignada.
