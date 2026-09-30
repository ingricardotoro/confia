# Propuesta: recuperación de contraseña con token de un solo uso

- **Cambio:** `password-recovery-token`
- **Fase del roadmap:** F0, cambio 7, **tercera de cuatro** partes (`docs/09-roadmap-y-fases.md` §3,
  entregable 3; `openspec/changes/foundations-plan/exploration.md`, nota del tercer corte del
  2026-09-27)
- **Depende de:** `identity-module-and-password-authentication` (archivado el 2026-09-27) y
  `column-encryption-and-mfa-totp` (archivado el 2026-09-30). La segunda dependencia es funcional y
  no solo de orden: ver «Corrección de una premisa», abajo.
- **Siguiente cambio de la secuencia:** `session-tokens-and-web-layer`, que hereda de este cambio dos
  condiciones duras de aceptación (sección «Dependencias»)
- **Exploración:** `openspec/changes/password-recovery-token/exploration.md`, con las cinco
  respuestas del propietario del 2026-09-30 en su sección 7
- **Estado:** pendiente de aprobación del propietario (`openspec/config.yaml`, `rules.proposal`)

## Intención

El requisito publicado «Recuperación de contraseña con token de un solo uso y de corta vida»
(`openspec/specs/identity/spec.md`, línea 200) y los dos escenarios de recuperación de «Prohibición
de enumeración de usuarios» (línea 223) **no tienen hoy ningún caso de uso detrás**. La parte 1 marcó
estos dos escenarios como «Parcial» en su tabla de cobertura. Esa marca describía la uniformidad del
inicio de sesión, no la de la recuperación, y los cuatro escenarios siguen sin cubrir (exploración,
§1).

Este cambio entrega el ciclo de vida completo del token de recuperación del **personal** como casos
de uso y persistencia: solicitud uniforme, emisión con sustitución del token anterior,
restablecimiento de un solo uso con el segundo factor que exige `docs/03-seguridad.md` §4.7, y la
primera política de longitud de contraseña del sistema. Lo que depende de piezas que todavía no
existen (borde HTTP, sesiones, db-scheduler, correo) se entrega como puerto sin adaptador o como
brecha con dueño nombrado, nunca como algo implícito.

Es además **el primer caso de uso que fija una contraseña elegida por una persona**. Hoy el único
consumidor de producción de `PasswordHasher.hash` es el hash señuelo que
`BouncyCastleArgon2PasswordHasher` construye en su constructor (exploración, H7).

### Corrección de una premisa

La nota del 2026-09-27 de `foundations-plan/exploration.md` y la propuesta archivada de la parte 2
justificaron el tercer corte diciendo que la recuperación de contraseña «no usa TOTP» y «no depende
de nada de lo anterior». **Eso contradice `docs/03` §4.7**, fila «MFA»: si la cuenta tiene MFA
activa, el restablecimiento exige además un código TOTP válido o un código de recuperación. El
propietario confirmó el 2026-09-30 que rige §4.7 (exploración, §7, respuesta 2). El corte sigue
siendo válido, porque la parte 2 ya está archivada, pero la frase es falsa. Su corrección fechada
forma parte de este cambio (punto 10 de «Dentro de alcance»).

## Alcance

### Dentro de alcance

1. **Tabla del token** con el prefijo de módulo de ADR-0015 regla 3 (nombre de trabajo:
   `identity_password_reset_token`, se fija en diseño). Guarda solo el SHA-256 del token en
   hexadecimal de 64 caracteres, más la cuenta por su identificador interno, la emisión, el
   vencimiento, el consumo y la sustitución. Lleva seguridad de fila habilitada y forzada, y
   privilegios idénticos al patrón de `V5` y `V6`: `SELECT, INSERT, UPDATE` para `confia_admin_app`,
   `SELECT` para `confia_readonly`, ninguno para `confia_portal_app` y **ningún `DELETE`**.
2. **Solicitud de recuperación uniforme.** Resuelve la cuenta con la institución de
   `LoginInstitutionProvider`. Si la cuenta existe, **solo programa la emisión** a través de un
   puerto de programación **sin adaptador de producción** (opción H1-B, aprobada). En ambas ramas
   escribe el mismo número de asientos de auditoría y produce el mismo desenlace. No bloquea la
   cuenta ni altera su estado (ADR-0005, «Recuperación de contraseña», punto 6).
3. **Emisión del token**, que será el cuerpo del futuro manejador del trabajador. Genera 32 bytes
   con un generador criptográfico, codificados en base64url. Guarda su SHA-256, marca como superado
   todo token vivo anterior de la cuenta y entrega el valor en claro a un puerto de envío **sin
   adaptador de producción**. Aplica el límite de **3 emisiones por hora por cuenta** de §4.7 y §10:
   al excederlo no emite, y el desenlace hacia quien solicitó sigue siendo el uniforme.
4. **Restablecimiento con el token.** Un único `UPDATE` condicional decide el consumo: sin consumir,
   sin superar y dentro de la vigencia. Exige el segundo factor cuando la cuenta tiene MFA activa,
   valida la contraseña nueva, escribe su hash Argon2id y audita, todo en **una sola transacción**
   (§4.7, «Uso»).
5. **Segundo factor en el restablecimiento**, reutilizando el cuerpo de `VerifyTotpCode` y el de
   `ConsumeRecoveryCode` dentro de la transacción del restablecimiento, sin anidar
   `TransactionRunner.execute`. Un segundo factor incorrecto **confirma** el avance del retroceso TOTP
   y su auditoría, **no consume** el token y devuelve el retardo exigible. Ver «Enfoque», punto 4.
6. **Política de longitud de la contraseña nueva de §4.2:** de 12 a 128 caracteres para el personal
   (exploración, §7, respuesta 3).
7. **Ampliación de `StaffAccountRepository`**, que hoy solo tiene `findBy(InstitutionId,
   LoginIdentifier)`: búsqueda por identificador interno y escritura del hash de contraseña.
8. **Eventos de auditoría** de `docs/03` §12.2, filas «cambio de contraseña» y «restablecimiento»:
   solicitud, emisión, emisión omitida por límite, restablecimiento completado y restablecimiento
   rechazado, con el motivo solo en la bitácora.
9. **Delta de la capacidad `identity`**, ver «Capacidades».
10. **Documentación, con notas fechadas y sin reescribir el cuerpo de ninguna sección:**
    - la corrección de la premisa «no usa TOTP» en `openspec/changes/foundations-plan/exploration.md`;
    - `docs/03-seguridad.md` §4.7 (tabla nueva, emisión en el trabajador, definición de MFA activa) y
      la adenda de §6.1 (privilegios de la tabla nueva);
    - `docs/08-datos-privacidad-y-retencion.md`, línea 322: filas retenidas hasta el cambio 9;
    - `docs/09-roadmap-y-fases.md` §3: pendientes heredados, incluido el cambio nuevo de correo que
      decidió el propietario.

### Fuera de alcance, con dueño nombrado

| Exclusión | Dueño |
|---|---|
| Endpoint, respuesta `202 Accepted` con el mensaje literal del catálogo de internacionalización, `Referrer-Policy: no-referrer` en la página de restablecimiento y puerta de tiempo sobre respuestas HTTP de §4.6 | `session-tokens-and-web-layer` |
| **Revocación de todas las sesiones al restablecer** (§4.5, §4.7, ADR-0005 punto 4) | `session-tokens-and-web-layer`, como **condición dura de aceptación** (exploración, §7, respuesta 4) |
| Límite de 10 solicitudes por hora por IP (§4.7, §10) | `session-tokens-and-web-layer`, con Redis del cambio 11 |
| Adaptador real del puerto de programación sobre db-scheduler | Cambio 9, `background-jobs-with-db-scheduler` |
| Purga de tokens vencidos (`docs/08`, línea 322) | Cambio 9. Hasta entonces las filas se retienen y no se concede `DELETE` (exploración, §7, respuesta 5) |
| Adaptador real del puerto de envío, y notificación al titular tras el restablecimiento con IP y momento | **Cambio propio de F0 posterior al cambio 9** (exploración, §7, respuesta 1), que sirve también al aviso de códigos de recuperación de MFA bajos. La IP la aporta además `session-tokens-and-web-layer` |
| Lista de contraseñas comprometidas en alta, cambio y restablecimiento (§4.2) | **Cambio propio** (exploración, §7, respuesta 3) |
| Restablecimiento forzado por el Super Administrador (§4.7) | Cambio 8, `rbac-permission-matrix-and-audit-integration`, más un endpoint de `session-tokens-and-web-layer` |
| Recuperación de contraseña de encargados, con mínimo de 10 caracteres | F8, portal de encargados |
| Cabecera `Idempotency-Key`: ninguno de estos casos de uso mueve dinero | Decisión ya asignada a `session-tokens-and-web-layer` |

**Nada preparado para un cambio futuro.** Cada tabla, columna y puerto tiene consumidor dentro de
este mismo cambio. Los dos puertos sin adaptador de producción los consumen los casos de uso y las
pruebas de integración, con dobles que capturan la llamada. Es el mismo precedente de «ningún
adaptador» de `ConsumeRecoveryCodeIT` y de todos los puertos de la parte 1, ninguno de los cuales
está registrado todavía como bean. No se añade ninguna marca de versión de credencial para la parte
4 (exploración, H6-b, descartada).

## Capacidades

> Contrato con `/sdd-spec`. Investigado contra `openspec/specs/identity/spec.md` (29 requisitos, 56
> escenarios tras el archivado de la parte 2).

### Nuevas

**Ninguna.** Es un delta puro sobre `identity`, con el mismo razonamiento de las partes 1 y 2.

### Modificadas: `identity`

**`## MODIFIED Requirements`**

- **«Recuperación de contraseña con token de un solo uso y de corta vida».** Conserva el sentido
  publicado y lo precisa:
  - la vigencia de treinta minutos cuenta **desde la emisión**, que ocurre en el trabajador y no en
    la solicitud;
  - el borde es estricto: un token presentado exactamente a los 30:00 se rechaza;
  - la sustitución ocurre al **emitir** un token más reciente;
  - el consumo y el cambio de contraseña son atómicos.
  Los dos escenarios publicados se conservan, con datos adaptados a la emisión. Las etiquetas
  `PRT-88a1` y `PRT-88b2` pasan a ser nombres de escenario y no formato del token.
- **«Prohibición de enumeración de usuarios».** Se sustituye el requisito completo, conservando
  **íntegro** el texto que rige el inicio de sesión. Los dos escenarios de recuperación se reescriben
  al nivel del caso de uso: desenlace idéntico, mismo número de asientos de auditoría, y en la rama
  inexistente nada programado ni enviado. El `202 Accepted` y el mensaje literal quedan con destino
  en `session-tokens-and-web-layer`, igual que la parte 1 hizo con el `401`.
- **«Ausencia de verificación contra contraseñas comprometidas y de rehash transparente (brecha con
  destino: cambio 8 o un cambio de identidad posterior)».** La mitad de la lista comprometida cambia
  de destino al cambio propio que decidió el propietario, y alcanza también al restablecimiento. La
  mitad del rehash transparente conserva su destino.
- **«Ausencia de envío real del aviso de códigos de recuperación de MFA bajos (brecha sin destino
  identificado en el roadmap)».** Pasa a tener destino nombrado: el cambio de correo posterior al
  cambio 9.

**`## ADDED Requirements`**

- El token se almacena únicamente como SHA-256, y su valor en claro no es observable en registros,
  excepciones, `toString()` ni pruebas.
- A lo sumo un token vivo por cuenta. Dos consumos concurrentes del mismo token producen exactamente
  un restablecimiento, y dos emisiones concurrentes para la misma cuenta dejan exactamente un token
  vivo.
- Límite de tres emisiones por hora por cuenta, invisible para quien solicita.
- Segundo factor exigido en el restablecimiento cuando la cuenta tiene MFA activa. Una cuenta con
  `mfa_required = true` **sin** secreto inscrito restablece solo con el enlace, y en el siguiente
  inicio de sesión recibe `SecondFactorEnrollmentRequired` (exploración, §7, respuesta 2).
- Un segundo factor incorrecto en el restablecimiento no consume el token, avanza el **mismo**
  contador de retroceso TOTP que el inicio de sesión y devuelve el retardo exigible.
- La contraseña nueva tiene entre 12 y 128 caracteres Unicode tras la normalización NFKC.
- La institución del restablecimiento proviene de la configuración del proceso, como en el inicio
  de sesión.

**Requisitos de ausencia con destino nombrado** (`## ADDED Requirements`, con el formato «brecha con
destino» de las partes 1 y 2):

| Requisito de ausencia | Destino |
|---|---|
| Revocación de sesiones tras el restablecimiento | `session-tokens-and-web-layer` (condición dura) |
| Programación real de la emisión | Cambio 9 |
| Envío real del enlace y notificación al titular | Cambio de correo posterior al cambio 9 |
| Límite por dirección IP de la solicitud | `session-tokens-and-web-layer` y cambio 11 |
| Purga de tokens vencidos | Cambio 9 |
| Restablecimiento administrativo | Cambio 8 y `session-tokens-and-web-layer` |

No se toca `build-integrity`: no entra ninguna regla de arquitectura nueva.

## Enfoque

1. **Tres casos de uso en `identity.application`** con nombres de trabajo: solicitud, emisión y
   restablecimiento. Cada uno delega su transacción en `TransactionRunner.execute`, con el patrón
   «decidir dentro, confirmar y devolver» de `AuthenticateWithPassword`. Ninguno espera: lo impone
   `NoBlockingWaitInIdentityTest`.
2. **Hash del token.** Se usa SHA-256 sin llave, como dice §4.7 y como usará el token de refresco de
   §4.5. No se usa Argon2id, porque no aporta nada frente a 256 bits de entropía, impide la búsqueda
   determinista y regala CPU a quien inventa tokens. **No se reutiliza `RequestPayloadHasher`**: su
   contrato es SHA-256 sobre JSON canonicalizado con prefijo `FORMAT_VERSION =
   "confia.idempotency.v1"`, y aplicarlo a un token cambiaría ese contrato (exploración, H2). El valor
   en claro vive en una clase final con `toString()` redactado, nunca en un `record`, por la lección
   de `AuthenticationCommand` en la parte 1.
3. **Concurrencia.** El consumo es un `UPDATE` condicional con `RETURNING`, el mismo patrón de
   `JooqRecoveryCodeRepository.markUsed` (`WHERE ... used_at IS NULL`, victoria si `updatedRows >
   0`). La sustitución en la emisión se serializa por cuenta. El diseño elige entre
   `SELECT ... FOR UPDATE` sobre la fila de `identity_staff_account` y un índice único parcial por
   cuenta. Si elige el índice, debe traducir `23505`, porque `TransactionRunner.isRetryable` reintenta
   solo `40001` y `40P01` (`DEFAULT_MAX_RETRIES = 3`). Ambas carreras se prueban con `CyclicBarrier`,
   nunca con esperas por reloj.
4. **Segundo factor sin reversión.** El cuerpo de `VerifyTotpCode` se reutiliza **tal cual**:
   instancia `new BackoffPolicy()` con `FIRST_DELAYED_ATTEMPT = 3`, `CAP = 900 s` y
   `COUNTER_WINDOW = 30 min`; `PERIOD` de 30 s; `TotpVerificationPolicy.TOLERANCE_WINDOW = 1`; y
   escribe en `identity_mfa_totp_backoff`, cuya clave primaria es `(institution_id, account_id)`.
   Por eso el contador es **el mismo** que el del inicio de sesión con TOTP, y el atacante no
   duplica su cupo. `ConsumeRecoveryCode` se reutiliza también tal cual, con
   `LOW_RECOVERY_CODE_THRESHOLD = 3`. Los dos se invocan por su `runWithinTransaction`, que es de
   paquete y vive en el mismo paquete `identity.application`. `TransactionRunner.newTemplate` no fija
   propagación, así que un `execute` anidado se uniría a la transacción exterior con su propio bucle
   de reintento dentro, y eso se evita. **Aplicando la lección de la parte 2:** la fase de tareas
   incluye un escenario que falla si las dos rutas dejaran de compartir contador, y otro que falla si
   un segundo factor incorrecto revirtiera el retroceso.
5. **Definición de MFA activa.** Se toma la misma que usa el inicio de sesión desde la parte 2:
   `mfa_required = true` **y** secreto TOTP inscrito (`AuthenticateWithPassword`, método
   `outcomeForValidCredentials`). Con `mfa_required = true` sin secreto rige la respuesta 2 del
   propietario. El caso `mfa_required = false` con secreto inscrito queda en la ronda de preguntas.
6. **No enumeración.** Se reutilizan tal cual `LoginIdentifier` (`MIN_LENGTH = 3`, `MAX_LENGTH =
   320`) y la huella de `HmacLoginIdentifierFingerprinter` (etiqueta
   `confia.identity.login-identifier.v1`) como `entity_id` de la auditoría, más la etiqueta
   `unknown-account` de `AuthenticateWithPassword`. El hash señuelo **no** se traslada, porque la
   solicitud no verifica ninguna contraseña (exploración, H4). La diferencia de tiempo residual se
   mide y se reporta sin ser puerta, con el precedente de `LoginTimingReportIT`.
7. **Contraseña nueva.** La longitud de 12 a 128 caracteres es una regla de dominio que se mide en
   puntos de código tras NFKC, porque §4.2 admite emoji. Convive con la guarda técnica de
   `PlainPassword` (`MAX_LENGTH = 1024`, en unidades UTF-16), que sigue gobernando el inicio de
   sesión. Solo se paga Argon2id cuando el token y el segundo factor ya son válidos.
8. **Pruebas.** Unitarias de dominio con reloj inyectado. Integración real contra PostgreSQL con el
   rol de aplicación. Aislamiento de fila de la tabla nueva a nivel de esquema y de adaptador (la
   parte 1 dejó sin hacer este segundo nivel para `identity_login_backoff`). Redacción con control
   negativo. TDD estricto con `./mvnw verify` en `apps/api`.

## Áreas afectadas

| Área | Cambio |
|---|---|
| `apps/api/app/src/main/resources/db/migration/` | `V7` nueva: tabla del token, política de fila y privilegios |
| `com.confia.identity.domain` | Token en claro redactado, hash del token, regla de vigencia y sustitución, política de longitud |
| `com.confia.identity.application` | Tres casos de uso, puerto de repositorio del token, puertos de programación y de envío, ampliación de `StaffAccountRepository` |
| `com.confia.identity.infrastructure` | Adaptador jOOQ del token y ampliación de `JooqStaffAccountRepository` |
| Pruebas | `MultiTenantSchemaIT`, `RolePrivilegeMatrixIT`, `IdentityScopeExclusionInventoryTest` (ausencia de adaptadores), pruebas nuevas de concurrencia, uniformidad y redacción |
| `openspec/changes/password-recovery-token/specs/identity/spec.md` | Delta |
| Documentación | `docs/03` §4.7 y §6.1, `docs/08` línea 322, `docs/09` §3, `foundations-plan/exploration.md` |

## Criterios de aceptación

- [ ] `./mvnw verify` en `apps/api` termina en verde, con la puerta de mutación incluida.
- [ ] La tabla del token tiene seguridad de fila habilitada y forzada. `RolePrivilegeMatrixIT`
      confirma `SELECT, INSERT, UPDATE` para `confia_admin_app`, `SELECT` para `confia_readonly`,
      ninguno para `confia_portal_app` y ningún `DELETE`. Una prueba demuestra que una institución no
      lee los tokens de otra.
- [ ] Una consulta SQL directa sobre la tabla nunca encuentra el token en claro, solo un hexadecimal
      de 64 caracteres.
- [ ] La solicitud con correo existente y con correo inexistente produce el mismo desenlace y el
      mismo número de asientos de auditoría. En la rama inexistente, el doble del puerto de
      programación no recibe ninguna llamada.
- [ ] Un token se acepta a los 29:59 de su emisión y se rechaza a los 30:00, con reloj fijo.
- [ ] Emitir un segundo token deja el primero rechazado. Un segundo uso del mismo token se rechaza.
- [ ] Dos consumos concurrentes del mismo token, sincronizados con `CyclicBarrier`, producen
      exactamente un restablecimiento. Dos emisiones concurrentes dejan exactamente un token vivo.
- [ ] La cuarta emisión dentro de una hora para la misma cuenta no ocurre, y el desenlace de la
      solicitud no cambia.
- [ ] Una cuenta con MFA activa no restablece sin un código TOTP o de recuperación válido. Un código
      incorrecto deja el token utilizable, avanza el contador de `identity_mfa_totp_backoff` y ese
      avance sobrevive a la transacción.
- [ ] Un escenario falla si el retroceso TOTP del restablecimiento y el del inicio de sesión dejaran
      de ser el mismo contador.
- [ ] Una cuenta con `mfa_required = true` sin secreto restablece con el enlace solo, y el siguiente
      inicio de sesión devuelve `SecondFactorEnrollmentRequired`.
- [ ] Una contraseña nueva de 11 caracteres se rechaza, una de 12 se acepta, una de 128 se acepta y
      una de 129 se rechaza. Una frase con espacios y emoji dentro del rango se acepta.
- [ ] Tras restablecer, la contraseña anterior ya no autentica y la nueva sí, con
      `AuthenticateWithPassword` sin modificar.
- [ ] Ningún token en claro, contraseña nueva ni hash aparece en registros, excepciones, `toString()`
      ni salida de pruebas, verificado por inspección del texto y con control negativo.
- [ ] Cada efecto y su asiento de auditoría se confirman o se revierten juntos.
- [ ] Una prueba de inventario declara que los dos puertos nuevos no tienen adaptador de producción,
      y falla el día que alguno lo tenga sin retirar la prueba.
- [ ] Las notas fechadas de `foundations-plan/exploration.md`, `docs/03`, `docs/08` y `docs/09`
      existen, y ninguna reescribe el cuerpo de su sección.
- [ ] La cobertura de `com.confia.identity.domain` sigue en 95 % con JaCoCo y la mutación en 80 con
      PIT.

## Riesgos

| Riesgo | Probabilidad | Mitigación |
|---|---|---|
| El token en claro se filtra por un `toString()` generado o por un mensaje de excepción | Alta si no se diseña: la parte 1 tuvo tres bloqueantes de esta familia | Clase final redactada y prueba de redacción con control negativo |
| Un segundo factor incorrecto revierte la transacción y el restablecimiento se vuelve un oráculo de TOTP sin retroceso | Media: es la forma natural de escribirlo | Enfoque 4, con un escenario que falla si ocurre |
| Dos tokens vivos por emisiones concurrentes | Media en `READ COMMITTED` | Serialización por cuenta y prueba con `CyclicBarrier` |
| El camino de códigos de recuperación de MFA no tiene retroceso, y el restablecimiento le abre una segunda entrada: cada intento cuesta hasta diez verificaciones Argon2id | Media. Exige además un token válido, es decir, acceso al buzón | Ronda de preguntas, punto 2. El límite por IP de la parte 4 acota el volumen |
| Riesgo aceptado por el propietario: con `mfa_required = true` y sin secreto, quien controle el buzón obtiene la contraseña | Aceptado el 2026-09-30 | La inscripción sigue exigida en el siguiente inicio de sesión |
| La recuperación no funciona de punta a punta hasta el cambio 9 y el cambio de correo | Cierta, aceptada con H1-B | Brechas con destino nombrado y prueba de inventario de ausencia |
| Crecimiento de filas vencidas sin purga | Baja: solo cuentas reales generan filas y el límite es de 3 por hora | Purga con dueño en el cambio 9 |
| Subestimación histórica de 1,5 a 3 veces en líneas | Alta, declarada sin descontar | Cortes de pull request encadenados; ver «Tamaño» |

## Plan de reversión

No existe ningún entorno desplegado ni dato real: la base vive solo en contenedores de prueba.

1. Cada corte de pull request se revierte con su commit de fusión, en orden inverso.
2. `V7` se revierte junto con el corte que la introduce, porque nunca se aplicó en producción. Una
   vez aplicada en producción no se edita (`CLAUDE.md`, convenciones de Git).
3. Las notas fechadas de documentación no arrastran código.
4. Revertir este cambio no afecta a las partes 1 y 2: solo añade puertos y amplía
   `StaffAccountRepository`, sin modificar el comportamiento de `AuthenticateWithPassword`,
   `VerifyTotpCode` ni `ConsumeRecoveryCode`.

## Dependencias

- **Consume:** de la parte 1, `TransactionRunner`, `AuditLogWriter`, `LoginIdentifier`,
  `HmacLoginIdentifierFingerprinter`, `LoginInstitutionProvider`, `PasswordHasher` y
  `StaffAccountRepository`. De la parte 2, `VerifyTotpCode`, `ConsumeRecoveryCode`,
  `TotpCredentialRepository` y la columna `mfa_required`.
- **Impone a `session-tokens-and-web-layer` dos condiciones duras de aceptación:** no fusiona tokens
  de refresco sin revocar todas las familias de una cuenta al restablecer su contraseña; y no fusiona
  el endpoint de solicitud sin el límite por IP de §4.7, por la misma lógica que la condición ya
  escrita para `identity_login_backoff`.
- **Deja al cambio 9:** el adaptador del puerto de programación y la purga de tokens vencidos.
- **Deja al cambio de correo posterior al cambio 9:** el adaptador del puerto de envío y la
  notificación al titular.

## Tamaño

| Bloque | Tareas |
|---|---|
| `V7`, seguridad de fila, privilegios, pruebas de esquema y aislamiento | 2 |
| Dominio: token redactado, hash, vigencia y sustitución, política de longitud | 2 |
| Adaptadores jOOQ del token y ampliación de `StaffAccountRepository` | 2 |
| Solicitud uniforme con el puerto de programación | 1 |
| Emisión con el puerto de envío, límite por cuenta y sustitución concurrente | 2 |
| Restablecimiento con segundo factor sin reversión y contraseña nueva | 2 |
| Pruebas transversales: concurrencia, uniformidad, redacción e inventario de ausencias | 2 |
| Delta de especificación y notas de documentación | 1 a 2 |
| **Total** | **14 a 15** |

El cambio **toca el límite de quince tareas** de `openspec/changes/README.md`. Si la fase de tareas
lo supera, el corte natural es por la frontera de la emisión: primero la tabla, la solicitud y la
emisión, y después el restablecimiento con el segundo factor y la contraseña nueva. Ninguna de las
dos mitades expone nada mientras no exista la capa web, así que la primera no abre un atajo sin
segundo factor. Con el multiplicador histórico sin descontar, se anticipan de cuatro a seis cortes de
pull request encadenados bajo el presupuesto de ochocientas líneas (`docs/15-flujo-de-trabajo-git.md`
§3).

## Ronda de preguntas de propuesta

1. **Cuenta con `mfa_required = false` y secreto TOTP inscrito.** El inicio de sesión no le pide
   segundo factor. Esta propuesta aplica la misma regla al restablecimiento, así que tampoco se lo
   pediría. ¿Se confirma, o §4.7 debe leerse como «secreto inscrito», con independencia de la
   columna?
2. **Códigos de recuperación de MFA sin retroceso.** ¿Se acepta como riesgo, acotado por el límite
   por IP de la parte 4, o se añade en este cambio un retroceso sobre el consumo de códigos? Añadirlo
   supone una tarea más y rebasaría el límite de quince.
3. **Nombre del cambio de correo.** ¿Qué identificador y qué número recibe en
   `foundations-plan/exploration.md` y en `docs/09`, para que los requisitos de ausencia lo nombren
   literalmente?

**Respuestas del propietario (2026-09-30):**

1. Se confirma: la regla del restablecimiento es la misma que la del inicio de sesión. Una cuenta con
   `mfa_required = false` no presenta segundo factor al restablecer, aunque tenga un secreto
   inscrito.
2. Se acepta el riesgo de que los códigos de recuperación de MFA no tengan retroceso, acotado por el
   límite por IP que exige la parte 4. No se añade retroceso en este cambio.
3. El cambio de correo se llama `transactional-email-adapter` y recibe el número **14 de F0**, el
   siguiente libre tras los trece de `foundations-plan/exploration.md`. Va después del cambio 9 y
   sirve también al aviso de códigos de recuperación bajos. Los requisitos de ausencia lo nombran
   literalmente.
