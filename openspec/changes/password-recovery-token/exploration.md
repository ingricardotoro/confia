# Exploración: recuperación de contraseña con token de un solo uso

- **Cambio:** `password-recovery-token`, tercera de las cuatro partes del cambio 7 de F0
  (`openspec/changes/foundations-plan/exploration.md`, nota del tercer corte, 2026-09-27)
- **Precedentes archivados:** `2026-09-27-identity-module-and-password-authentication` y
  `2026-09-30-column-encryption-and-mfa-totp`. Sigue `session-tokens-and-web-layer`.
- **Fase:** explorar
- **Fecha:** 2026-09-30
- **Estado:** exploración terminada, pendiente de propuesta y de las decisiones de la sección 6

## 1. Estado actual verificado

**Requisitos que son de este cambio.** «Recuperación de contraseña con token de un solo uso y de
corta vida» (`openspec/specs/identity/spec.md`, línea 200, dos escenarios) y los dos escenarios de
recuperación de «Prohibición de enumeración de usuarios» (línea 223). La parte 2 los dejó intactos a
propósito (`.../2026-09-30-column-encryption-and-mfa-totp/proposal.md`, «Fuera de alcance»). La
parte 1 los marcó «Parcial» en su tabla de cobertura (`.../proposal.md`, filas 13 y 14), pero esa
cobertura era la uniformidad del **inicio de sesión**: hoy no existe ningún caso de uso de
recuperación, así que los cuatro escenarios están sin cubrir.

**Qué existe y se puede reutilizar** (rutas bajo `apps/api/app/src/main/java/com/confia/`):

| Pieza | Dónde | Hecho verificado en el código |
|---|---|---|
| Componente transaccional | `shared/security/TransactionRunner.java` | `READ COMMITTED` por omisión; reintenta solo `40001` y `40P01` (`isRetryable`), `DEFAULT_MAX_RETRIES = 3`; `newTemplate` no fija la propagación, así que rige `PROPAGATION_REQUIRED` |
| Escritura de auditoría | `shared/audit/AuditLogWriter.java`, `AuditEntry.java` | Dieciséis columnas; `sourceIp` y `userAgent` viajan en `null` porque no hay petición HTTP |
| Normalización del identificador | `identity/domain/LoginIdentifier.java` | `MIN_LENGTH = 3`, `MAX_LENGTH = 320` |
| Huella con llave | `identity/infrastructure/HmacLoginIdentifierFingerprinter.java` | HMAC-SHA-256 con subllave de la pimienta, etiqueta `confia.identity.login-identifier.v1` |
| Búsqueda de cuenta | `identity/application/StaffAccountRepository.java` | Un único método, `findBy(InstitutionId, LoginIdentifier)`; no hay búsqueda por id ni escritura del hash |
| Hash de contraseña | `identity/infrastructure/BouncyCastleArgon2PasswordHasher.java` | `hash(...)` solo tiene hoy un consumidor de producción: el señuelo del constructor |
| Institución previa a la autenticación | `identity/infrastructure/ConfiguredLoginInstitutionProvider.java` | Institución desde la configuración del proceso, nunca desde la solicitud |
| Retroceso | `identity/domain/BackoffPolicy.java` | `FIRST_DELAYED_ATTEMPT = 3`, `CAP = 900 s`, `COUNTER_WINDOW = 30 min` |
| Verificación TOTP | `identity/application/VerifyTotpCode.java` | Abre su propia transacción; instancia `new BackoffPolicy()`; devuelve `requiredDelay` |
| Código de recuperación de MFA | `identity/application/ConsumeRecoveryCode.java` | Abre su propia transacción; sin retroceso propio |
| `UPDATE` condicional de un solo uso | `identity/infrastructure/JooqRecoveryCodeRepository.java` (`markUsed`) | `WHERE id = ? AND used_at IS NULL`, victoria si `updatedRows > 0` |
| Disciplina de redacción | `identity/domain/PlainPassword.java`, `PlainRecoveryCode.java` | Clases finales con `toString()` redactado, nunca `record` |

**Esquema.** `V5__create_identity_staff_account.sql` y `V6__add_mfa_required_and_create_crypto_mfa_tables.sql`
fijan el patrón: prefijo `identity_` (ADR-0015, regla 3), clave `(institution_id, id)`, llave foránea
compuesta hacia `identity_staff_account` (como `identity_mfa_recovery_code_account_fk`), `ENABLE` y
`FORCE ROW LEVEL SECURITY`, política con `NULLIF(current_setting('app.institution_id', true), '')`,
`REVOKE ALL ... FROM PUBLIC` y después `SELECT, INSERT, UPDATE` para `confia_admin_app`, `SELECT` para
`confia_readonly`, nada para `confia_portal_app` y ningún `DELETE`. `identity_staff_account` **no
tiene columna de estado**: «cuenta activa», como dice el escenario publicado, no se puede modelar hoy.

**Lo que no existe:** capa HTTP, Spring Security, almacén de sesiones, Redis, db-scheduler (ni la
dependencia en `apps/api/app/pom.xml` ni su tabla), tabla `event_publication`, adaptador de correo y
cualquier bean de producción: ningún puerto tiene adaptador registrado (Javadoc de `TransactionRunner`).

## 2. Lo que exigen las fuentes de verdad

| Fuente | Exigencia |
|---|---|
| `spec.md` l. 200 | Token de un solo uso, **30 minutos desde su emisión**; rechazado si se usó, expiró o fue **superado** por una solicitud posterior |
| `spec.md` l. 223 | Solicitud con correo existente e inexistente: misma forma, contenido y **tiempo observable**; en el inexistente, ningún correo |
| `docs/03` §4.7 | 32 bytes de un generador criptográfico, base64url; **solo el SHA-256** almacenado; el claro existe **únicamente dentro del correo** |
| `docs/03` §4.7 | Consumo marcado **en la misma transacción** que cambia la contraseña; un token nuevo invalida los anteriores |
| `docs/03` §4.7 | El token identifica la cuenta por su **identificador interno**, no por el correo en la URL |
| `docs/03` §4.7 | Efecto: cierra **todas las sesiones** y notifica al titular con IP y momento |
| `docs/03` §4.7 | **MFA:** con MFA activa, el restablecimiento exige además un código TOTP válido o un código de recuperación |
| `docs/03` §4.7 y §10 | 3 solicitudes por hora por cuenta y 10 por hora por IP; al exceder, «202 uniforme, pero sin enviar correo» |
| `docs/03` §4.7 | Restablecimiento administrativo: el Super Administrador dispara el flujo, **no fija** la contraseña; siempre auditado |
| `docs/03` §4.2 | Lista de contraseñas comprometidas **obligatoria en alta, cambio y restablecimiento**; mínimo 12 caracteres para personal, máximo 128 |
| `docs/03` §4.6 | Recuperación: siempre 202; prueba de medianas de 200 más 200 intentos con umbral de 50 ms |
| `docs/03` §12.2 | Auditar «cambio de contraseña» y «restablecimiento» |
| ADR-0005, «Recuperación de contraseña» | Revocar todas las familias de tokens; notificar; solicitar **no bloquea** la cuenta ni invalida la sesión actual |
| ADR-0016, reglas 3 y 7 | Una tarea no lleva datos personales en claro; el envío de correo va en tarea separada, nunca dentro de la transacción |
| `CLAUDE.md` | Prohibidos `@Async` y `@Scheduled`; regla 11: el token nunca en registros |
| `docs/08` l. 322 | Retención de tokens de recuperación: 30 minutos, **eliminación automática** |

## 3. Hallazgos que condicionan la propuesta

**H1. Sin adaptador de correo, el enlace no llega a nadie, y las fuentes casi fuerzan cómo se envía.**
Hay tres opciones. (A) El caso de uso genera el token y llama en el acto a un puerto de envío: el
camino de cuenta existente tardaría lo que tarde el proveedor SMTP (ADR-0014, SES por SMTP). Eso es
un canal lateral muy por encima de los 50 ms de §4.6, y el trabajo en segundo plano solo puede ir por
db-scheduler, porque `@Async` está prohibido. (B) La solicitud **programa la emisión** y el
manejador del trabajador genera el token, guarda su SHA-256 y lo entrega al puerto de envío en su
propia transacción. Así cumple a la vez ADR-0016 regla 3 (la tarea solo lleva identificadores) y
§4.7 («el claro existe únicamente dentro del correo»). (C) Devolver el token al llamador queda
descartada: el llamador de la solicitud es anónimo. **B** es la única opción que no contradice
ninguna fuente, pero depende del cambio 9 (`background-jobs-with-db-scheduler`), todavía sin
archivar. Lo que este cambio sí puede entregar es la solicitud, con un puerto de programación sin
adaptador de producción, y el caso de uso de emisión, que será el cuerpo del futuro manejador, con un
puerto de envío sin adaptador. Las pruebas de integración usarían dobles que capturan el token, con
el mismo precedente de «ningún adaptador» de `ConsumeRecoveryCodeIT`. **El roadmap no asigna en F0
ningún dueño al adaptador de correo.** La primera abstracción de canal es el entregable 5 de F6. La
parte 2 ya dejó el aviso de códigos bajos «sin destino identificado». Resultado: la recuperación del
personal no funcionaría de verdad antes de F6 si nadie la reclama.

**H2. El token se hashea con SHA-256 sin llave, y Argon2id sería un error.** El token tiene 256 bits
de entropía: un ataque de diccionario o de fuerza bruta sobre el hash no es viable, así que un hash
lento no aporta nada. Además, la búsqueda por el token presentado exige un hash determinista, y
Argon2id con sal aleatoria obligaría a separar selector y verificador. También daría a cualquiera un
costo de CPU de 250 a 500 ms por cada token inventado, que es un vector de denegación de servicio.
§4.7 dice SHA-256, y §4.5 usa lo mismo para el token de refresco, lo que da coherencia a la parte 4.
Un HMAC con subllave (el patrón de `HmacLoginIdentifierFingerprinter`) solo protegería ante un volcado
de la base, que con 256 bits ya no es explotable, y se apartaría del texto. **Qué no se reutiliza tal
cual:** `RequestPayloadHasher` calcula SHA-256 sobre JSON canonicalizado con el prefijo
`FORMAT_VERSION = "confia.idempotency.v1"`, así que no sirve para un token sin cambiar su contrato. Se
guarda en hexadecimal `TEXT` con `CHECK (~ '^[0-9a-f]{64}$')`, como `identity_login_backoff.identifier_hash`.
El valor en claro necesita su propia clase final redactada: el `toString()` del `record`
`AuthenticationCommand` fue el bloqueante de C3 en la parte 1.

**H3. Un solo uso, vigencia y sustitución bajo concurrencia.** Tabla `identity_password_reset_token`
(nombre a confirmar en diseño, con el prefijo de ADR-0015): `institution_id`, `id`, `account_id` con
llave foránea compuesta, `token_hash`, `created_at`, `expires_at`, `consumed_at` y `superseded_at`,
con seguridad de fila y privilegios idénticos a la adenda de §6.1. El consumo es un único `UPDATE`
condicional con `RETURNING` (`consumed_at IS NULL AND superseded_at IS NULL AND expires_at > ?`), el
mismo patrón que `JooqRecoveryCodeRepository.markUsed`. Hay tres carreras que el diseño debe cerrar
con prueba de `CyclicBarrier`:
(1) dos consumos del mismo token: gana uno, por el bloqueo de fila;
(2) dos solicitudes concurrentes de la misma cuenta: en `READ COMMITTED`, cada una marca como
superadas solo las filas que ve y **pueden sobrevivir dos tokens vivos**. Se cierra con
`SELECT ... FOR UPDATE` sobre la fila de la cuenta o con un índice único parcial por cuenta
`WHERE consumed_at IS NULL AND superseded_at IS NULL`. El índice produciría `23505`, que
`TransactionRunner.isRetryable` **no** reintenta, así que habría que traducirlo;
(3) consumo contra una solicitud nueva: el bloqueo de fila las serializa.
El borde exacto de los 30:00 no lo fija la especificación y lo debe fijar el delta. En el consumo, la
institución sale de `LoginInstitutionProvider`, igual que en el inicio de sesión, porque no hay
sesión. **Retención:** `docs/08` pide eliminación automática a los 30 minutos, pero el patrón vigente
no concede `DELETE` a ningún rol y la purga necesita db-scheduler. Es la misma brecha que la purga de
`identity_login_backoff`, con dueño propuesto en el cambio 9.

**H4. No enumeración en la solicitud: el señuelo de la parte 1 no se traslada, el resto sí.** El hash
señuelo existe porque el inicio de sesión **verifica una contraseña** (`AuthenticateWithPassword`,
rama `else`: `passwordHasher.matches(password, passwordHasher.decoyHash())`). La solicitud de
recuperación no verifica nada en ninguna rama, así que añadir Argon2id a ambas solo costaría
recursos. Sí se trasladan: `LoginIdentifier`; la huella como `entity_id` de la auditoría, que además
correlaciona intentos de inicio de sesión y de recuperación del mismo correo; la etiqueta
`unknown-account`; y **el mismo número de asientos por rama**, con el motivo solo en la bitácora.
Queda una asimetría pequeña pero real: la rama existente escribe la sustitución y la programación, y
la inexistente no. La asimetría grande, el envío, desaparece con H1-B. El precedente es medir y
reportar sin convertirlo en puerta (`LoginTimingReportIT`, «informational, never a gate»), con la
puerta HTTP de §4.6 en la parte 4. El límite de 3 por hora por cuenta puede contarse sobre las filas
de la propia tabla de tokens: solo existen para cuentas reales, así que un atacante con correos
aleatorios no la hace crecer, y al exceder el desenlace sigue siendo el uniforme. El límite por IP es
de la parte 4, con Redis en el cambio 11. Detalle menor: la especificación («se envió un enlace de
recuperación»), §4.6 («se enviará un enlace») y ADR-0005 («recibirá un mensaje») dan tres textos
literales distintos. El texto es de la parte 4 y de su catálogo de internacionalización.

**H5. MFA: las fuentes no callan, y la premisa del corte era falsa.** §4.7 exige TOTP o código de
recuperación si la cuenta tiene MFA activa. Sin eso, quien controle el buzón se salta el segundo
factor. La nota del 2026-09-27 de `foundations-plan/exploration.md` y la propuesta de la parte 2
justificaron el corte diciendo que la recuperación «no usa TOTP». **Eso contradice §4.7.** El corte
sigue siendo válido porque la parte 2 ya está archivada, pero este cambio **depende** de ella. Hay
tres puntos que decidir:
(a) **Qué es «MFA activa»:** las fuentes no dicen qué hacer con `mfa_required = true` sin secreto
inscrito. Es pregunta para el propietario.
(b) **La composición transaccional:** `VerifyTotpCode.execute` y `ConsumeRecoveryCode.execute` abren
cada uno su transacción. Anidados, se unirían a la exterior por `PROPAGATION_REQUIRED`, pero con un
bucle de reintento interno dentro de una transacción ajena. Habrá que reutilizar sus
`runWithinTransaction`, que son de paquete y viven en el mismo paquete `identity.application`.
(c) **El orden, que es crítico:** un código TOTP incorrecto **no** debe consumir el token, y tampoco
debe revertir la transacción. Si la revirtiera, el avance de `identity_mfa_totp_backoff` se perdería
y el restablecimiento se volvería un oráculo para adivinar TOTP sin retroceso. El caso de uso debe
confirmar el retroceso y su auditoría y devolver un rechazo con el `requiredDelay` de
`VerifyTotpCodeDecision`, sin lanzar.
**Aplicando la lección de la parte 2:** reutilizar el cuerpo de `VerifyTotpCode` significa usar
literalmente la misma regla, porque ese cuerpo instancia `new BackoffPolicy()`
(`FIRST_DELAYED_ATTEMPT = 3`, `CAP = 900 s`, `COUNTER_WINDOW = 30 min`), y el mismo contador por
cuenta que el inicio de sesión con TOTP, de modo que el atacante no duplica su cupo.
`ConsumeRecoveryCode`, en cambio, **no tiene retroceso**, y abrirle una segunda puerta de entrada lo hace más visible. La
tarea debe incluir un escenario que falle si las dos rutas dejaran de compartir contador.

**H6. Las sesiones no existen, así que «cerrar todas las sesiones» no se puede cumplir aquí.** §4.5,
§4.7 y ADR-0005 piden revocar todas las familias de tokens de refresco al restablecer. No hay nada que
revocar. Hay tres opciones. (a) Un requisito de brecha con destino en
`session-tokens-and-web-layer`, más una **condición dura de aceptación**: la parte 4 no fusiona
tokens de refresco sin revocación por cambio de contraseña, con el mismo precedente que la condición
de `identity_login_backoff`. (b) Una marca de versión de credencial en `identity_staff_account` que
la parte 4 lea. El restablecimiento la escribiría, pero nadie la leería en este cambio, y el
repositorio ya rechazó «nada preparado para» (propuesta de la parte 2). (c) Un evento de dominio: no
hay `event_publication`. Se recomienda (a). La notificación al titular con IP y momento necesita
correo y borde HTTP, así que se difiere con H1.

**H7. Es el primer caso de uso que fija una contraseña elegida por una persona.** Hoy
`PasswordHasher.hash` solo hashea el señuelo, y las cuentas de prueba se siembran por SQL.
`PlainPassword` acepta de 1 a 1024 caracteres (`MAX_LENGTH = 1024`, documentado como guarda técnica
y no como política). Sin más, un restablecimiento aceptaría una contraseña de un carácter, contra el
mínimo de 12 de §4.2. La verificación contra listas comprometidas está declarada como brecha con
destino «cambio 8 o posterior» en `spec.md` (l. 452), y su segunda capa, la de k-anonimato, choca con
`IdentityScopeExclusionInventoryTest.noProductionClassOfIdentityDependsOnAnyNetworkClientLibrary`.
Además, el hash de la contraseña nueva cuesta de 250 a 500 ms con la fila bloqueada: el diseño debe
validar el token, que es barato, antes de pagar Argon2id, para no dar un amplificador de CPU a quien
inventa tokens.

**H8. Queda fuera por dependencia y no por elección.** El restablecimiento administrativo de §4.7
necesita el rol de Super Administrador (cambio 8) y un endpoint (parte 4). `StaffAccountRepository`
necesita búsqueda por id y escritura condicional del hash. Las acciones de auditoría nuevas
(solicitud, emisión, restablecimiento, rechazo) siguen la convención `identity.<área>.<evento>`.

## 4. Tamaño

| Bloque | Tareas |
|---|---|
| Migración `V7`, seguridad de fila, privilegios y extensión de `MultiTenantSchemaIT` y `RolePrivilegeMatrixIT` | 2 |
| Dominio: token redactado, hash, regla de vigencia y sustitución con reloj inyectado | 2 |
| Adaptadores jOOQ: tokens y ampliación de `StaffAccountRepository` | 2 |
| Solicitud uniforme, límite por cuenta y puerto de programación; emisión con puerto de envío | 2 |
| Restablecimiento con composición MFA, sin reversión del retroceso | 2 |
| Pruebas de concurrencia (H3), uniformidad y redacción | 2 |
| Delta de especificación, brechas con destino y notas fechadas en `docs/03` §4.7 y §6.1 | 1 |
| **Subtotal** | **13** |
| Política de longitud de §4.2, de 12 a 128 caracteres, si se aprueba | +1 |
| Lista local de 100 000 contraseñas comprometidas, si se aprueba | +2 |

Entre 13 y 16 tareas. Con el multiplicador histórico de 1,5 a 3 veces las líneas, que la parte 2
pidió no descontar, cabe **sin** la lista comprometida y la rebasa **con** ella. Si el propietario la
quiere, conviene un cambio propio, `password-policy-and-breached-list`, que también sirva al alta de
cuentas cuando exista.

## 5. Recomendación

Un solo cambio SDD, delta de la capacidad `identity`, **sin capa web**, demostrado con pruebas de
integración contra el caso de uso, como las dos partes anteriores. Alcance: tabla y ciclo de vida del
token con SHA-256 (H2 y H3); solicitud uniforme que **programa** la emisión a través de un puerto sin
adaptador y caso de uso de emisión con un puerto de envío sin adaptador (H1-B); restablecimiento con
MFA obligatoria según §4.7, que confirma el retroceso TOTP ante un fallo (H5); y longitud de 12 a 128
caracteres para la contraseña nueva (H7). Como brechas con destino nombrado: revocación de sesiones
(parte 4, condición dura, H6), límite por IP y respuesta `202` literal (parte 4 y cambio 11), purga y
programación real (cambio 9), envío real y notificación al titular (dueño por decidir), lista
comprometida (cambio propio o cambio 8) y restablecimiento administrativo (cambio 8 y parte 4).
Además, una corrección fechada de la frase «no usa TOTP» en `foundations-plan/exploration.md`.

## 6. Preguntas para el propietario

1. **Entrega del enlace.** ¿Se aprueba que la solicitud solo programe la emisión y que el token se
   genere y se envíe en el trabajador (H1-B), aunque la recuperación no funcione de punta a punta
   hasta que existan db-scheduler (cambio 9) y un adaptador de correo? ¿Qué cambio es dueño de ese
   adaptador antes de salir a producción, dado que el roadmap solo lo trae en F6?
2. **MFA en el restablecimiento.** ¿Se confirma que rige §4.7 (TOTP o código de recuperación) y que
   este cambio depende de la parte 2? Para una cuenta con `mfa_required = true` **sin** secreto
   inscrito, ¿se permite restablecer y la inscripción se exige en el siguiente inicio de sesión, o se
   rechaza el restablecimiento?
3. **Política de la contraseña nueva.** ¿Se exige ya el mínimo de 12 y el máximo de 128 de §4.2, y la
   lista de contraseñas comprometidas se lleva a un cambio propio, o todo espera al cambio 8?
4. **Sesiones.** ¿Se escribe como condición dura de aceptación de `session-tokens-and-web-layer` que
   no fusione tokens de refresco sin revocación por restablecimiento (H6-a)?
5. **Retención.** Para cumplir la eliminación automática de `docs/08`, ¿se concede `DELETE` sobre la
   tabla de tokens al rol del trabajador, como excepción documentada al patrón de «ningún `DELETE`»,
   o se acepta retener las filas vencidas hasta decidir la purga en el cambio 9?
