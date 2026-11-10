# Exploración: `password-recovery-endpoints` (S3 de la parte 4b del cambio 7 de F0)

Repositorio: rama `change/password-recovery-endpoints`, creada desde `main` local en `9154c45`.
Exploración de solo lectura del 2026-11-09. **Advertencia de base:** `origin/main` está en `af20884`,
159 commits por delante de esa base, con `apps/admin-web` y varias partes ya fusionadas de
`rbac-permission-matrix-and-audit-integration` (por ejemplo `PublicEndpoints` con la lista cerrada de
rutas y `RouteCoverageGuardTest`). Las rutas de código de este documento son de `9154c45` salvo que se
indique `origin/main`. La rama debe rebasarse sobre `origin/main` antes del diseño.

## 1. Estado actual

**Plan y condiciones heredadas.** El quinto corte de `foundations-plan/exploration.md` (líneas 275 a 299)
reparte la parte 4b en S1, S2, cambio 9 y S3. La fila de S3 (línea 286) dice: «`reset/request` y
`reset/confirm`, respuesta uniforme, límite de 10 por hora por IP y puerta de tiempo sobre HTTP», y le
asigna **H3** y **H4**. La línea 289 a 291 fija qué es cada una:

| Id | Texto original | Fuente |
|---|---|---|
| **H3** | «`session-tokens-and-web-layer` NO DEBE fusionar el endpoint de solicitud de restablecimiento sin el límite de 10 solicitudes por hora por dirección IP» (`docs/03` §4.7 y §10). El límite de tres emisiones por hora es por cuenta y no frena a quien recorre cuentas ajenas | `foundations-plan/exploration.md` líneas 154 a 157 |
| **H4** | La respuesta HTTP uniforme de la recuperación con su puerta de tiempo (inventario de `web-edge-foundations`): `202 Accepted` con el mensaje del catálogo, `Referrer-Policy: no-referrer` y la puerta de §4.6 (200 + 200 intentos, diferencia de medianas ≤ 50 ms) | `foundations-plan/exploration.md` líneas 289 a 291; `archive/2026-10-19-session-tokens-and-web-layer/exploration.md` líneas 94 a 98 |

Las dos siguen **abiertas y transferidas** a este cambio en cada archivo posterior:
`archive/2026-10-19-session-tokens-and-web-layer/archive-report.md` líneas 123, 124 y 160;
`archive/2026-10-24-session-endpoints-cookie-and-csrf/archive-report.md` líneas 109, 110 y 121;
nota del 2026-10-23 en `foundations-plan/exploration.md` líneas 309 a 315. El cambio 9 fijó además el
orden «R4 antes de S3» (`archive/2026-11-03-background-jobs-with-db-scheduler/design.md` línea 996), ya
cumplido: el adaptador de programación existe.

**Requisitos vigentes que nombran a este cambio** (`openspec/specs/`):

| Requisito | Ubicación | Qué exige de S3 |
|---|---|---|
| Prohibición de enumeración de usuarios | `identity/spec.md` líneas 252 a 267 | El `202 Accepted` con su mensaje y la medición del tiempo sobre respuestas HTTP |
| Ausencia del límite por IP y de la respuesta HTTP de la recuperación (brecha con destino: S3 y cambio 11) | `identity/spec.md` líneas 2331 a 2354 | «NO DEBE fusionar el endpoint de solicitud de recuperación sin ese límite por IP, **con el aprovisionamiento de Redis del cambio 11, operativo y probado**» (línea 2336 a 2337) |
| Ausencia del restablecimiento administrativo (brecha con destino: cambio 8 y S3) | `identity/spec.md` líneas 2356 a 2375 | El endpoint del restablecimiento administrativo |
| Ausencia de recuperación de contraseña por HTTP (brecha con destino: S3) | `web-edge/spec.md` líneas 2076 a 2099 | Las rutas; [WE85] fija hoy exactamente siete rutas de producción |

**Casos de uso ya entregados** (`apps/api/app/src/main/java/com/confia/identity/`):

- `application/RequestPasswordReset.java`: uniforme por construcción. Cuenta existente, identificador sin
  cuenta y cuenta en su límite por hora devuelven el mismo `RequestPasswordResetDecision` (un registro
  sin componentes) y exactamente un asiento `identity.password_reset.requested` con la huella con llave
  del identificador como entidad. Solo la cuenta existente programa la emisión. El asiento se escribe
  con `source_ip` y `user_agent` nulos (línea 98), porque `RequestPasswordResetCommand` solo lleva el
  identificador presentado.
- `application/ResetPasswordWithToken.java`: un solo token de 32 bytes, consumo condicional, longitud
  de 12 a 128 caracteres, segundo factor cuando la MFA está activa (`mfaActive`, líneas 236 a 240, que
  hoy lee `mfa_required`), revocación de todas las familias en la misma transacción (H2, cerrada) y
  auditoría. Devuelve `ResetPasswordDecision(outcome, requiredDelay)` con cinco desenlaces sellados
  (`application/ResetOutcome.java`): `Completed`, `TokenRejected` (sin motivo: los cuatro motivos del
  token dan el mismo valor), `PasswordRejected`, `SecondFactorMissing` y `SecondFactorRejected`. El
  retardo solo es distinto de cero cuando se verificó un TOTP. `ResetPasswordCommand` tampoco lleva el
  origen de la petición.
- `infrastructure/wiring/IdentityConfiguration.java`: `ResetPasswordWithToken` ya es bean del proceso
  administrativo (líneas 331 a 340); `RequestPasswordReset` **no** lo es, por diseño: «its endpoint
  registers it» (línea 64). El adaptador de programación, `JobPasswordResetIssuanceScheduler`, sí está
  registrado (líneas 188 a 196).
- El puerto de envío, `application/PasswordResetLinkSender.java`, solo tiene en producción
  `UnavailablePasswordResetLinkSender`, que falla de forma explícita; `IdentityScopeExclusionInventoryTest`
  (líneas 281 a 300 del árbol de pruebas) lo vigila y su inversión es del cambio 14.

**Borde web disponible** (`com.confia.shared`):

- Limitador por IP en memoria (`security/InMemoryRateLimiter.java`) detrás del puerto `RateLimiter`, con
  dos capas: peticiones por ventana y fallos por ventana. Las políticas viven en
  `web/ratelimit/RateLimitProperties.java` (línea 24), un registro con **tres componentes fijos**
  (`admin-login`, `admin-mfa`, `admin-session`) e `ignoreUnknownFields = false`, y en `application.yml`
  (líneas 36 a 60). Una política nueva exige un componente nuevo y una línea de configuración.
- `@RateLimited(policy = ...)` hace que el interceptor responda `429` con `Retry-After` antes de leer el
  cuerpo; `RateLimitFailures.record(policy)` alimenta la capa 2. Precedente directo:
  `identity/web/LoginController.java`.
- `web/delay/RequiredDelayMaterializer.java` materializa el retardo, con tope de 30 s.
- `web/request/SecurityHeadersFilter.java` (línea 46) fija `Referrer-Policy:
  strict-origin-when-cross-origin` en toda respuesta.
- En `origin/main`, `shared/web/edge/PublicEndpoints.java` es la lista cerrada de rutas públicas (inicio
  de sesión, refresco, cierre actual), y `RouteCoverageGuardTest` exige que toda ruta esté cubierta.

**Pruebas que hoy afirman la ausencia y deben invertirse.**
`architecture/WebEdgeScopeExclusionInventoryTest.java` (línea 621, BI52, «No recovery of a password over
HTTP»), `bootstrap/PublicRouteAllowListTest.java` (líneas 563 a 590), la prueba de [WE85] (siete rutas)
y `OpenApiContractSnapshotTest`.

**Mediciones de tiempo existentes.** `identity/application/PasswordResetRequestTimingReportIT.java`
(línea 28) mide 25 + 25 llamadas al caso de uso e **informa sin bloquear**; su Javadoc dice que la puerta
sobre HTTP es de otro cambio. `identity/web/LoginTimingGateIT.java` es la puerta real del inicio de sesión
(200 + 200, límite de 50 ms o la mitad del costo de una verificación Argon2id, control negativo BI56,
límites del limitador elevados por propiedades de prueba). Es el patrón a seguir, con una diferencia
importante: la solicitud de recuperación no ejecuta Argon2id, así que la calibración por costo de
Argon2id no aplica (ver D6).

**Frontend.** En `9154c45` no existe `apps/admin-web`. En `origin/main` sí existe, y la propuesta de
`admin-web-foundations-and-session` (P3) decidió «texto de contacto, sin enlace de contraseña olvidada»,
y afirma que «el autoservicio por HTTP no existe y hoy no tiene cambio asignado» (línea 230 de esa
propuesta). La segunda mitad de esa frase es inexacta: el dueño del autoservicio por HTTP es este cambio.
La pantalla, en cambio, no tiene dueño.

## 2. Qué debe entregar este cambio

| # | Entregable | Origen |
|---|---|---|
| E1 | Ruta pública de solicitud, `202 Accepted` uniforme con mensaje del catálogo es-HN | H4; `identity/spec.md` 252 a 267 |
| E2 | Límite de 10 solicitudes por hora por IP sobre esa ruta, aplicado antes del caso de uso | H3; `docs/03` §4.7 y §10 |
| E3 | Respuesta idéntica en código, cabeceras y cuerpo (salvo `traceId`) para cuenta existente, inexistente y en su límite por hora | H4; `docs/03` §4.6 |
| E4 | Puerta de tiempo sobre HTTP: 200 + 200 intentos, diferencia de medianas ≤ 50 ms, con control negativo | H4; `docs/03` §4.6 |
| E5 | `Referrer-Policy: no-referrer` en las respuestas de ambas rutas | H4; `docs/03` §4.7 y §8.2 |
| E6 | Ruta pública de confirmación que traduce los cinco desenlaces de `ResetOutcome` a respuestas aprobadas, con el retardo del TOTP materializado | Fila de S3; `docs/09` línea 128 |
| E7 | Límite de tasa propio de la confirmación (no hay fila en `docs/03` §10) | DA-13 de S1, sin resolver para esta ruta |
| E8 | Origen de la petición (IP y agente saneado) propagado a los dos comandos y a sus asientos | O6 de S2 (solo cubrió inicio de sesión y refresco); `identity/spec.md` línea 1189: el aviso del cambio 14 necesita la IP |
| E9 | Ninguna contraseña, token ni código de segundo factor en registros, excepciones ni cuerpos de error del recorrido HTTP | Regla 11; precedente O9 de S2 |
| E10 | Instantánea OpenAPI, `packages/contracts` regenerado, inversión de las pruebas de ausencia | `confia-api-conventions` §9 |
| E11 | Cierre documental de H3 y H4 con notas fechadas, sin reescribir el texto original | Precedente de S1 y S2 |

## 3. Condiciones duras y prohibiciones

- **H3 y H4 se cierran aquí** o el cambio no se fusiona.
- **Tensión con el texto vigente de H3 en la especificación.** `identity/spec.md` línea 2337 exige el
  límite «con el aprovisionamiento de Redis del cambio 11, operativo y probado». D2 (2026-10-04) aceptó
  el limitador en memoria como equivalente **solo para H1**. La exploración de S1 ya lo marcó como
  pendiente de confirmar (`archive/2026-10-19-session-tokens-and-web-layer/exploration.md` línea 92,
  DA-13), y ni S1 ni S2 lo resolvieron: el DA-13 de S2 es otro asunto (serializador de auditoría). El
  cambio 11 no tiene todavía propuesta en ninguna rama. **Sin una decisión del propietario que extienda
  D2 a H3, este cambio queda bloqueado detrás del cambio 11** (pregunta P1).
- Sin `Idempotency-Key` en endpoints de identidad (D3 de `web-edge-foundations`).
- Sin adaptador de envío de correo, ni falso ni real: el puerto de envío es del cambio 14.
- Sin lógica nueva en los casos de uso salvo la propagación del origen: la uniformidad del caso de uso
  ya está probada.

## 4. Áreas afectadas

| Área | Cambio previsto |
|---|---|
| `identity/web/` | Dos controladores nuevos, sus DTO de entrada con Bean Validation y su traducción de desenlaces |
| `identity/application/RequestPasswordResetCommand.java`, `ResetPasswordCommand.java`, los dos casos de uso | Campos de origen (IP y agente), con `toString()` redactado, y su escritura en `source_ip` y `user_agent` |
| `identity/infrastructure/wiring/IdentityConfiguration.java` | Registro de `RequestPasswordReset`; corrección del Javadoc de la línea 64 |
| `shared/web/ratelimit/RateLimitProperties.java`, `application.yml` | Dos políticas nuevas |
| `shared/web/edge/PublicEndpoints.java` (en `origin/main`) y la guarda de cobertura de rutas | Dos entradas públicas |
| `shared/web/request/SecurityHeadersFilter.java` | `no-referrer` en esas dos rutas |
| Catálogo de Problem Details y catálogo es-HN | Códigos nuevos de la confirmación y el mensaje del `202` |
| Pruebas | Inversión de BI52, [WE85] y de la lista de rutas públicas; integración de H3, H4, confirmación y redacción; puerta de tiempo; instantánea OpenAPI |
| `packages/contracts` | Regenerado con orval |
| `docs/03` §4.6, §4.7 y §10; `docs/05`; `docs/09`; `foundations-plan/exploration.md` | Notas fechadas, sin reescribir cuerpos |
| `openspec/specs/identity`, `web-edge`, `build-integrity` | Deltas: requisitos positivos nuevos y retiro de las tres ausencias |

## 5. Puntos de diseño abiertos

### D1. ¿Cubre D2 a H3? (bloqueante, decisión del propietario)

Opciones: *a)* extender D2 con nota fechada: el limitador en memoria es el tope equivalente de H3 hasta
el cambio 11, con las mismas tres limitaciones declaradas, y el requisito de la línea 2331 se modifica
para que el destino del estado compartido sea solo el cambio 11; *b)* esperar al cambio 11. La opción
*a* mantiene el precedente de H1; F0 no tiene entorno desplegado, así que el estado por proceso no
expone a nadie hoy. Recomendación: *a*.

### D2. Respuesta al exceder el límite por IP de la solicitud

`docs/03` §10, fila «Portal, Recuperación de contraseña», dice al exceder: «202 uniforme, pero sin
enviar correo». La fila es del portal y mezcla el límite por cuenta con el límite por IP. El límite por
cuenta **debe** ser silencioso (ya lo es: se decide en la emisión). El límite por IP no revela nada sobre
ninguna cuenta, porque depende solo del origen. Opciones: *a)* `429` con `Retry-After` del interceptor
existente, más una nota fechada en §10 que separe las dos dimensiones; *b)* `202` silencioso, que exige un
camino nuevo en el borde (el interceptor solo sabe responder `429`) y deja a un usuario legítimo tras NAT
sin correo y sin saber por qué. Recomendación: *a*.

### D3. Límite de la confirmación

No hay fila en §10. El token tiene 256 bits, así que el límite no protege el token: protege el costo de
Argon2id del ganador y el segundo factor, que ya tiene retroceso por cuenta. Opciones: *a)* política
`admin-password-reset` con 20 por minuto por IP y capa 2 de 10 fallos en 10 minutos, donde cuentan como
fallo el token rechazado y el segundo factor rechazado; *b)* reutilizar `admin-mfa`, que mezclaría
contadores de dos superficies. Recomendación: *a*, con nota en §10.

### D4. Nombres de ruta

La fila del plan dice `reset/request` y `reset/confirm`, que son verbos en la ruta, contra
`confia-api-conventions` §1. El estilo de S2 es de recursos (`/api/v1/auth/sessions`,
`/api/v1/auth/mfa/verifications`). Propuesta: `POST /api/v1/auth/password-reset-requests` (`202`) y
`POST /api/v1/auth/password-resets` (`204`).

### D5. Contrato de la confirmación

Traducción propuesta de `ResetOutcome`, cerrada y exhaustiva:

| Desenlace | Respuesta |
|---|---|
| `Completed` | `204 No Content`, sin cookie y sin sesión: el titular inicia sesión después |
| `TokenRejected` | `400` con un único código (`password-reset-token-invalid`) para los cuatro motivos |
| `PasswordRejected` | `422` con `password-too-short` o `password-too-long` |
| `SecondFactorMissing` | `422` con `second-factor-required` |
| `SecondFactorRejected` | `422` con `second-factor-invalid`, después de materializar el retardo |

`SecondFactorMissing` revela que la cuenta tiene MFA activa, pero solo a quien ya posee un token vivo,
que identifica la cuenta. El token viaja en el cuerpo JSON, nunca en la ruta ni en la consulta. Los
códigos exactos y los estados son del diseño; esta tabla es la posición de partida.

### D6. Discriminación de la puerta de tiempo

La puerta del inicio de sesión calibra su límite con la mitad del costo de Argon2id, porque la omisión
que debe detectar es la del señuelo. La solicitud de recuperación no tiene Argon2id: los dos caminos
hacen una huella HMAC, una lectura y un asiento, y la cuenta existente además inserta la tarea en
`scheduled_tasks`. Un control negativo que responda al instante solo se distinguiría por unos pocos
milisegundos, por debajo de 50 ms, y la puerta pasaría de vacío. El diseño debe elegir un control
negativo con poder discriminante real (por ejemplo, una variante que omita la escritura del asiento o
que añada un trabajo medible solo en un camino) y un límite calibrado y nunca mayor de 50 ms, con el
mismo cuidado que BI56. Riesgo de presupuesto: `verify` ya era de 5 min 24 s frente a 8 min.

### D7. Propagación del origen

`IssueSessionCommand` y `AuthenticationCommand` ya llevan dirección y agente. Los dos comandos de
recuperación no. Sin la IP en el asiento del restablecimiento, el aviso al titular «con IP y momento» del
cambio 14 no tiene fuente: la carga de una tarea solo admite identificadores (`JobPayloadAllowListTest`).
Este cambio escribe el origen en `source_ip` y `user_agent`; cómo lo lee el cambio 14 (por el
`request_id` del asiento o por otra vía) es de ese cambio.

### D8. Institución y contexto de seguridad de una ruta pública

Igual que el inicio de sesión: la institución sale de la configuración del proceso
(`LoginInstitutionProvider`), nunca del cliente; los dos casos de uso ya rechazan un contexto con otra
institución.

## 6. Frontera con `transactional-email-adapter` (cambio 14)

| Asunto | Dueño |
|---|---|
| Rutas HTTP, límites, uniformidad, puerta de tiempo, `no-referrer` en las respuestas del API | **Este cambio** |
| Origen (IP y agente) en los comandos y en los asientos de recuperación | **Este cambio** |
| Adaptador SMTP que implementa `PasswordResetLinkSender` y retira `UnavailablePasswordResetLinkSender`; inversión de `IdentityScopeExclusionInventoryTest` (I25 e I27) y del escenario «El puerto de envío solo tiene el emisor que falla» | **Cambio 14** |
| Construcción del enlace (URL base configurable, plantilla del correo) | **Cambio 14**, con una restricción que fija este cambio: el token viaja en el fragmento de la URL (`#`), nunca en la ruta ni en la consulta, y la página lo envía en el cuerpo de la confirmación |
| Aviso al titular tras el restablecimiento, con IP y momento; aviso de códigos de MFA bajos; aviso por reutilización de refresco | **Cambio 14** |
| La pantalla que recibe el enlace y su `no-referrer` como página | Ninguno de los dos (P5) |

Ninguno depende del otro para fusionarse. El escenario «Un restablecimiento completado no notifica a
nadie» sigue siendo cierto al cerrar este cambio. Hasta que se fusionen los dos, la recuperación no
funciona de punta a punta: la solicitud responde `202` y la tarea de emisión agota sus cinco intentos
contra el emisor que falla.

## 7. Conflictos con trabajo en curso

1. **`rbac-permission-matrix-and-audit-integration`, parte 5a (derivación de MFA).** Su propuesta
   (punto 13) sustituye «toda lectura de `mfa_required` en el inicio de sesión, el segundo factor y el
   restablecimiento de contraseña» por `MfaPolicy`, y su punto 14 retira la columna y reescribe unas 96
   apariciones en 45 archivos de pruebas. Toca `ResetPasswordWithToken.mfaActive` y los fixtures que
   este cambio usaría. El commit `bca0330` de la rama `...-5a-mfa-policy-derivation` ya lo hace. Con
   «toda cuenta del personal lleva MFA obligatoria», la confirmación sin segundo factor solo procede con
   una credencial sin verificar.
2. **Restablecimiento administrativo.** La misma propuesta lo asigna a **8b** y la de
   `admin-web-foundations-and-session` lo repite. El requisito vigente (línea 2356) lo reparte entre el
   cambio 8 y este. Este cambio debe retirarse del destino de esa ausencia y dejar a 8b como único dueño.
3. **Base desactualizada.** 159 commits entre `9154c45` y `origin/main`, incluidos `PublicEndpoints`,
   la guarda de cobertura de rutas y la autorización en el filtro de rbac. Las rutas públicas nuevas
   deben entrar en esa lista cerrada.
4. **`admin-web-staff-sign-in`.** Sin enlace de contraseña olvidada (P3). No choca en código, pero su
   texto de contacto queda como la única salida del usuario hasta que exista la pantalla.
5. **Cambio 14.** Posible solapamiento en las notas de `docs/03` §4.7, `docs/09` y `foundations-plan`.
   Se mitiga con notas fechadas separadas, sin reescribir cuerpos.

## 7 bis. Enfoques

| Enfoque | Descripción | Ventajas | Desventajas |
|---|---|---|---|
| **A. Un cambio, solo backend** (recomendado) | Las dos rutas, los dos límites, la uniformidad, la puerta y el origen, con la pantalla fuera | Cierra H3 y H4 a la vez; contrato estable para el frontend y para el cambio 14 | Unas 12 o 13 tareas, cerca del límite de 15; 3 o 4 PR encadenados |
| B. Partir en S3a (solicitud, H3 y H4) y S3b (confirmación) | Dos ciclos SDD | Ciclos más cortos; H3 y H4 se cierran en S3a | Un ciclo SDD completo más de ceremonia para un contrato pequeño; la confirmación sin solicitud no sirve a nadie |
| C. Backend y pantalla juntos | Incluye las pantallas de `admin-web` | Recorrido completo visible | Supera 15 tareas; mezcla dos pilas y dos revisiones; la pantalla depende de cerrar `admin-web-staff-sign-in` |

## 8. Tamaño y división en PR (≤ 800 líneas efectivas)

| PR | Contenido | Líneas estimadas |
|---|---|---|
| 1 | Políticas de tasa, origen en los comandos y asientos, registro de `RequestPasswordReset` | 300 a 450 |
| 2 | Ruta de solicitud, H3, uniformidad, `no-referrer`, inversión de ausencias, OpenAPI | 550 a 750 |
| 3 | Puerta de tiempo con control negativo | 250 a 400 |
| 4 | Ruta de confirmación, desenlaces, retardo, redacción, OpenAPI y contratos | 550 a 750 |
| 5 | Cierre documental | 150 a 250 |

## 9. Preguntas para el propietario

1. **P1.** ¿Extiende D2 a H3, con el limitador en memoria hasta el cambio 11? Recomendado: sí.
2. **P2.** ¿`429` al exceder el límite por IP de la solicitud? Recomendado: sí, con nota en §10.
3. **P3.** ¿Política `admin-password-reset` de 20 por minuto por IP y 10 fallos en 10 minutos para la
   confirmación? Recomendado: sí.
4. **P4.** ¿Rutas `password-reset-requests` y `password-resets`? Recomendado: sí.
5. **P5.** ¿Pantallas de solicitud y de restablecimiento fuera de alcance, en un cambio de frontend
   nuevo posterior a `admin-web-staff-sign-in`? Recomendado: sí.
6. **P6.** ¿El restablecimiento administrativo queda solo en 8b? Recomendado: sí.
7. **P7.** ¿La aplicación espera a que la parte 5a de rbac (derivación de MFA y retiro de la columna)
   esté en `main`? Recomendado: sí; mientras tanto, propuesta, especificación y diseño avanzan.

## 10. Riesgos

| Riesgo | Probabilidad | Mitigación |
|---|---|---|
| Autodenegación: con `trusted-proxies` vacío, detrás de un proxy toda la institución comparte 10 solicitudes por hora | Alta en producción; nula hoy (no hay despliegue) | Declarado; llenado de proxies y Redis del cambio 11; valores en configuración |
| Puerta de tiempo sin poder discriminante | Media | Control negativo calibrado (D6) |
| Conflicto de fusión con rbac 5a en `ResetPasswordWithToken` y fixtures | Alta si se aplica en paralelo | P7 |
| Presupuesto de `verify` por la nueva puerta de 400 peticiones | Media | Medir en el PR 3; reutilizar el proceso arrancado |
| Fuga del token o la contraseña por registros del marco o por el cuerpo de un `400` de validación | Baja | Prueba de centinelas sobre todo el recorrido (E9) |
| Recuperación que no funciona de punta a punta hasta el cambio 14 | Cierta | Declarada; la tarea agota sus intentos y queda visible |

## 11. Listo para la propuesta

Sí. La recomendación es el enfoque A, condicionado a P1.
