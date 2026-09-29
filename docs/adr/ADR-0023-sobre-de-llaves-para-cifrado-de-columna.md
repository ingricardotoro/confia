# ADR-0023: Sobre de llaves (envelope encryption) para el cifrado a nivel de columna

- **Estado:** Aceptado
- **Fecha:** 2026-09-28
- **Decisores:** Propietario del producto y arquitecto
- **Contexto técnico:** Motor de cifrado puro en `com.confia.kernel`, puerto y orquestador en
  `com.confia.shared.crypto`, adaptador jOOQ en `com.confia.shared.infrastructure`, migración `V6`.
  Concreta la decisión D1 y D4 de la propuesta de `column-encryption-and-mfa-totp` sin reemplazarlas.

## Contexto y problema

Este cambio introduce el primer dato cifrado a nivel de columna de CONFIA: el secreto TOTP en
claro nunca puede llegar a disco sin cifrar (`docs/03-seguridad.md` §7.3), y los cambios futuros
que `docs/03` ya anticipa en esa misma sección (otros secretos de negocio) reutilizarán el mismo
mecanismo. Hace falta fijar, con una decisión escrita, exactamente qué algoritmo, qué formato de
sobre, qué esquema de tabla de llaves y qué origen de llave maestra usa este mecanismo — y qué
queda deliberadamente fuera de su alcance, para que nadie asuma que este cambio entrega rotación
real o búsqueda determinista sobre un valor cifrado.

## Alcance de esta decisión

**Dentro de alcance:**

1. **Algoritmo y formato del sobre.** AES-256-GCM (`javax.crypto`, `"AES/GCM/NoPadding"`, sin
   dependencia nueva — confirmado por la sonda S1, `apply-progress.md`), con IV de 96 bits (12
   bytes) generado aleatoriamente por cifrado y etiqueta de autenticación de 128 bits. El valor de
   columna se almacena como `v1:<id_dek>:<iv_b64>:<ciphertext_b64>:<tag_b64>`
   (`docs/03-seguridad.md` §7.3), con los datos adicionales autenticados (AAD)
   `<tabla>|<columna>|<institution_id>:<row_id>` — lo que hace que copiar un valor cifrado de una
   fila a otra falle al descifrar por fallo de autenticación de GCM (`AeadIntegrityException`,
   `com.confia.kernel`).
2. **Esquema de la tabla de llaves.** `shared_data_encryption_key`
   (`institution_id, id, status, wrapped_key, created_at`), con como mucho una llave `active` por
   institución (índice único parcial `... WHERE status = 'active'`), seguridad de fila forzada, y
   los mismos privilegios de rol que `identity_login_backoff` (sin `DELETE` para ningún rol —
   retirar una llave es un `UPDATE` a `status = 'retired'`, nunca un borrado).
3. **Fuente de la KEK (llave maestra).** Precedente literal de `Argon2Pepper` (D1 de la
   propuesta): `ColumnEncryptionMasterKey.fromBase64(...)` valida que el valor decodifica a
   exactamente 32 bytes y falla en construcción, no en el primer uso. Cómo llega ese valor en
   Base64 — variable de entorno, secreto montado, o un gestor de secretos real — es
   deliberadamente ajeno a esta decisión: se difiere a la fase que declare una configuración de
   producción real, el mismo diferimiento que `Argon2Pepper` ya aplica a la suya.
4. **Ubicación del motor puro y del puerto de gestión de llaves** (D3 de la propuesta). El motor
   de cifrado (`AesGcmCipher`, `EncryptedColumnValue`) vive en `com.confia.kernel`, sin ninguna
   excepción a la pureza de JDK que ese módulo exige. El puerto (`DataEncryptionKeyRepository`),
   el orquestador (`ColumnEncryptionService`) y los objetos de valor con secreto
   (`ColumnEncryptionMasterKey`, `DataEncryptionKeyMaterial`) viven en `com.confia.shared.crypto`,
   un paquete nuevo sin segmento de capa, con `@org.springframework.modulith.NamedInterface` desde
   este mismo corte (mismo patrón que `shared.security` y `shared.audit`, ADR-0022). El adaptador
   jOOQ (`JooqDataEncryptionKeyRepository`) vive directamente en `com.confia.shared.infrastructure`
   — **no** en un sub-paquete `shared.crypto.infrastructure` — porque `TableOwnershipByModuleTest`
   deriva el prefijo de tabla exigido del segmento inmediatamente anterior al primer segmento de
   capa, y ese segmento debe seguir siendo `shared` para que la tabla conserve el prefijo `shared_`
   que la regla 3 de ADR-0015 exige.

**Fuera de alcance de este ADR y de este cambio:**

- **Ejecución real de la rotación de una DEK.** Retirar una llave activa y volver a cifrar los
  valores existentes bajo una nueva es una brecha con destino nombrado en un cambio futuro (F0
  cambio 9, D2 de la propuesta). Este cambio solo garantiza que un valor cifrado bajo una DEK ya
  retirada sigue descifrándose correctamente (`decrypt(...)` resuelve siempre la DEK que el propio
  valor nombra, nunca la activa).
- **Búsqueda determinista por HMAC sobre un valor cifrado.** D6 de la propuesta: sin consumidor en
  este cambio, no se construye.

## Factores de decisión

| Factor | Peso | Justificación |
|---|---|---|
| Sin dependencia criptográfica nueva | Muy alto | `AES/GCM/NoPadding` ya vive en el JDK (`SunJCE`), confirmado por sonda S1 |
| Aislamiento de fila por institución, igual que el resto del esquema | Muy alto | ADR-0009, regla general; ninguna excepción justificada para la tabla de llaves |
| Reutilización de un precedente ya probado para el secreto de la KEK | Alto | `Argon2Pepper` ya resolvió la forma de un secreto de configuración validado en construcción |
| Compatibilidad con `TableOwnershipByModuleTest` sin reabrir la regla | Alto | El adaptador debe vivir donde el prefijo de tabla exigido coincida con el ya fijado por D3 |

## Opciones consideradas

### Opción A (adoptada): sobre de llaves de dos niveles (KEK envuelve DEK, DEK cifra columna)

**Ventajas.** Separa el radio de exposición: comprometer una DEK expone solo los valores cifrados
con ella, nunca la KEK ni las DEK de otra institución. Permite, en un cambio futuro, rotar DEKs sin
tocar la KEK. Sigue el patrón estándar de cifrado de sobre (AWS KMS, Google Cloud KMS, Vault
Transit) sin reinventar un esquema propio.

**Desventajas.** Dos niveles de indirección (KEK→DEK→valor) en vez de uno; una operación más
(desenvolver la DEK) en cada cifrado/descifrado de columna.

### Opción B: una única llave maestra cifra directamente cada columna, sin DEK intermedia

**Ventajas.** Un nivel menos de indirección.

**Desventajas.** Descartada: rotar la única llave exigiría re-cifrar cada fila cifrada de todo el
sistema en una sola operación atómica, sin ningún radio de exposición limitado por institución.
Contradice el propio D2 de la propuesta, que ya anticipa rotación por DEK, no por KEK.

### Opción C: cifrado determinista (mismo IV siempre) para permitir búsqueda por igualdad

**Ventajas.** Permitiría un índice de igualdad sobre el valor cifrado.

**Desventajas.** Descartada explícitamente por D6 de la propuesta: reutilizar el IV en AES-GCM
para el mismo par (llave, AAD) rompe la confidencialidad del esquema (ataque de dos-tiempo sobre
el flujo de cifrado). Sin ningún consumidor que necesite búsqueda por igualdad en este cambio, el
costo de seguridad no tiene contrapartida.

## Decisión

**Se adopta la opción A.** Sobre de llaves de dos niveles: una KEK configurada fuera del
repositorio (D1, precedente `Argon2Pepper`) envuelve una DEK de 32 bytes por institución
(`shared_data_encryption_key`, creada perezosamente y de forma segura bajo concurrencia con
`INSERT ... ON CONFLICT ... WHERE status = 'active' DO NOTHING`, confirmado por sonda S5); la DEK
activa cifra cada valor de columna nuevo con AES-256-GCM y datos adicionales autenticados atados a
la fila exacta.

## Consecuencias

**Positivas:**

- El secreto TOTP (y cualquier columna futura que este mecanismo cifre) nunca llega a disco en
  claro, con un formato de sobre auditable y verificado por `ColumnEncryptionIT`.
- Copiar un valor cifrado de una fila a otra falla al descifrar, sin ninguna comprobación de
  aplicación adicional: la propia autenticación de GCM lo impide.
- Ningún cambio futuro que necesite cifrar otra columna de negocio reinventa el mecanismo: reutiliza
  `ColumnEncryptionService` tal cual.

**Negativas y costos aceptados:**

- Dos niveles de indirección en cada cifrado/descifrado de columna (KEK→DEK→valor).
- La rotación real de una DEK queda como brecha nombrada: un valor cifrado con una DEK retirada
  sigue siendo legible, pero nada en este cambio vuelve a cifrarlo bajo la DEK activa.

**Riesgos y mitigaciones:**

| Riesgo | Mitigación |
|---|---|
| La KEK se pierde o se rota fuera de banda sin plan de re-envolver las DEK existentes | Fuera de alcance de este cambio; señalado aquí para que el cambio que declare la configuración real de la KEK lo resuelva explícitamente |
| Alguien reintroduce un IV fijo o reutilizado por conveniencia de pruebas | `AesGcmCipherTest` y `ColumnEncryptionIT` fijan IV aleatorio por operación; una regresión a IV fijo en producción no tiene ningún test que la proteja hoy más allá de la revisión humana |

## Cumplimiento y verificación

1. **`ColumnEncryptionIT`.** El valor almacenado empieza por `v1:`, nunca contiene el secreto en
   claro, y falla al descifrar con el AAD de otra fila (`AeadIntegrityException`); dos
   inscripciones concurrentes de la primera DEK de una institución terminan con exactamente una
   fila activa.
2. **`DataEncryptionKeyRowSecurityIT`.** Una institución no lee la llave de datos de otra; sin
   contexto de institución, la consulta devuelve cero filas, nunca un error de permiso.
3. **`RolePrivilegeMatrixIT`, `MultiTenantSchemaIT`.** La matriz de privilegios y las puertas
   genéricas de esquema cubren `shared_data_encryption_key` sin ninguna exclusión.
4. **`TableOwnershipByModuleTest`, `SpringModulithVerificationTest`.** El adaptador jOOQ conserva
   el prefijo `Shared` exigido; `com.confia.shared.crypto` expone exactamente lo que
   `identity` necesita, sin abrir el módulo `shared` entero.

## Referencias

- ADR-0009: aislamiento multi-institución
- ADR-0015: acceso a datos con jOOQ, reglas 3, 4 y 7
- ADR-0022: interfaz nombrada de Spring Modulith para paquetes de `shared`
- `openspec/changes/column-encryption-and-mfa-totp/proposal.md`, decisiones D1, D2, D3, D4, D6
- `openspec/changes/column-encryption-and-mfa-totp/design.md`, decisiones 1, 2, 3, 4, 5
- `openspec/changes/column-encryption-and-mfa-totp/apply-progress.md`, sondas S1 y S5
- `docs/03-seguridad.md` §7.3
