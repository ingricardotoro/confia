# Exploración: tokens, sesiones y endpoints de identidad (F0, cambio 7, parte 4b)

- **Cambio:** `session-tokens-and-web-layer`
- **Fase:** explorar
- **Fecha:** 2026-10-06
- **Línea base:** `main` en `2a599f5`
- **Estado:** exploración terminada, pendiente de propuesta y de las decisiones del propietario de la sección 4.
- **Procedencia:** solo lectura de código, especificaciones, ADR y documentos; ninguna sonda se ejecutó. Complementa la exploración compartida archivada `openspec/changes/archive/2026-10-06-web-edge-foundations/exploration.md` (Engram #581), que cubría la parte 4 completa. Sus cortes C4a a C8 y sus decisiones abiertas 1, 2, 7, 9, 10, 11, 12 y 13 siguen vigentes; aquí se contrastan con lo que la parte 4a entregó de verdad.
- **Espejo:** Engram, tema `sdd/session-tokens-and-web-layer/explore` (#578).

## 0. Resumen

La parte 4b es el primer cambio que expone endpoints de negocio, el primero que emite credenciales y el primero con acceso a base de datos desde HTTP. La parte 4a dejó el borde listo (cadena que deniega por defecto, Problem Details, origen de la petición, limitador por IP en memoria, materializador del retardo, borde de idempotencia) y dejó **seis casos de uso de identidad sin ruta**. Lo que falta es: núcleo de tokens, familias de refresco (migración V8), filtro de autenticación, adaptadores de `CurrentInstitutionProvider` y `SessionValidity`, nueve endpoints, CSRF si hay credencial en cookie, y el cierre de las condiciones duras H1 a H4 y de la condición 3.

Hallazgos que cambian el plan respecto de la exploración de 4a:

1. **Tamaño.** La parte 4a pronosticó unas 2 250 líneas para C1a a C3 y entregó 17 926 líneas efectivas en 25 PR (7,97 veces la cifra inicial; 1,4 a 1,6 veces el re-pronóstico intermedio de 11 000 a 12 700). El pronóstico nominal de 4b (5 950 líneas, 10 cortes) parte del mismo sesgo.
2. **Orden de H2.** El texto publicado en la especificación de identidad prohíbe fusionar *la emisión de tokens de refresco* sin la revocación al restablecer; el plan de cortes pone la revocación (C7a) después del inicio de sesión (C6a). Con PR encadenados a `main` los dos textos chocan (sección 2, H2).
3. **El endpoint de solicitud de restablecimiento no puede existir en producción hasta el cambio 9.** `RequestPasswordReset` no es bean porque su puerto de programación no tiene adaptador (decisión 3 de 4a).
4. **Fuga real de la regla 11 alcanzable con el primer endpoint.** `EnrollTotpSecondFactor` inserta sin comprobar existencia; una segunda inscripción produce una violación de unicidad de PostgreSQL que `ProblemExceptionHandler.unexpected` registra a `ERROR` con la excepción completa, incluida la línea `Detail: Key (...)=(...)`.
5. **Tensión de origen web en la documentación.** `docs/03` §8.4 (nginx) sirve el API bajo el mismo host, pero la CSP de §8.1 declara `connect-src https://api-admin...` y ADR-0003 prevé CORS con lista blanca. La CSRF de doble envío de §8.3 solo funciona con mismo origen.
6. **No existe `apps/admin-web`.** Las pruebas de navegador que pide ADR-0005 (atributos de cookie con Playwright) no pueden ejecutarse en este cambio.
7. **El retardo de hasta 900 s se materializa sobre una respuesta HTTP** que un proxy o Cloudflare puede cortar mucho antes (a verificar); el tope superior de `Delayed` (S3 de 4a) tiene aquí su dueño.

## 1. Estado actual (verificado en código)

### 1.1 Lo que entrega la parte 4a y esta parte consume

| Pieza | Clases (rutas bajo `apps/api/app/src/main/java/com/confia/`) | Estado |
|---|---|---|
| Cableado del proceso administrativo | `bootstrap/admin/AdminApplication` (importa `SharedPlatformConfiguration`, `IdentityConfiguration`, `WebEdgeConfiguration`, `AdminSecurityConfiguration`, `ObservabilityMetricsConfiguration`, `RateLimiterConfiguration`, `ThrottlingConfiguration`, `RequiredDelayConfiguration`, `IdempotencyEdgeConfiguration`); `shared/platform/infrastructure/SharedPlatformConfiguration` (`DSLContext` con `withExecuteLogging(false)`, `Clock`, `AuditLogWriter` decorado, cifrado de columnas); `shared/security/TransactionRunnerConfiguration` | Operativo; `DataSource` solo en administración (D5); portal y trabajador sin `DataSource` |
| Cadena de seguridad | `shared/web/edge/SecurityChains.denyByDefault` (CSRF **deshabilitado**, sin estado, `anyRequest().denyAll()` como última regla), `PublicEndpoints`/`PublicEndpoint` (lista blanca cerrada: solo springdoc en `local` y `preprod`), `AdminSecurityConfiguration`, `PortalSecurityConfiguration` | Sin ningún filtro de autenticación por credencial |
| Problem Details | `shared/web/problem/{ProblemCode, ProblemBody, FieldViolation, ProblemResponses, ProblemExceptionHandler, ProblemAuthenticationEntryPoint, ProblemAccessDeniedHandler, ProblemRequestRejectedHandler, CapacityExceededException}`, `shared/web/edge/ProblemErrorReportValve`; catálogo es-HN en `resources/i18n/problems.properties` | Códigos: `validation-failed`, `idempotency-key-missing`, `authentication-required`, `forbidden`, `resource-not-found`, `method-not-allowed`, `idempotency-conflict`, `unsupported-media-type`, `idempotency-payload-mismatch`, `too-many-requests`, `capacity-exceeded`, `internal-error` |
| Origen de la petición | `shared/web/request/{RequestContextFilter, SecurityHeadersFilter, ClientAddressResolver, TrustedProxies, CidrBlock, WebEdgeProperties, SensitiveLogGuard}`, `shared/security/{RequestOrigin (ScopedValue), ClientAddress, ClientKey}`, `shared/audit/RequestOriginAuditLogWriter` | El asiento de auditoría ya lleva `sourceIp` y `userAgent`; los comandos de identidad no lo reciben (brecha asignada a 4b) |
| Limitador | `shared/security/{RateLimiter, RateLimitPolicy, RateLimitDecision, InMemoryRateLimiter, RateLimiterConfiguration}`, `shared/web/ratelimit/{RateLimited, RateLimitInterceptor, RateLimiterRegistry, RateLimitPolicyCheck, RateLimitProperties, TooManyRequestsException, RateLimitMetrics}`, `shared/observability/metrics/LogRateLimitMetrics`, `shared/web/edge/ThrottlingConfiguration` | Política `admin-login` registrada y **sin ruta**. `RateLimitProperties` está fijada a un solo prefijo (`confia.web.rate-limit.admin-login`): una segunda política exige generalizar la configuración |
| Retardo | `shared/web/delay/{RequiredDelayMaterializer, Delayed, DelayTimer, DelayProperties}`, `shared/web/edge/RequiredDelayConfiguration` | Contrato: limitador, permiso del semáforo, caso de uso (confirma), `recordFailure`, espera en hilo virtual, respuesta. Sin tope superior de `requiredDelay` |
| Idempotencia en el borde | `shared/web/idempotency/{IdempotentWrite, IdempotencyKeyInterceptor, IdempotentRequestHandler, ...}`, `shared/web/edge/IdempotencyEdgeConfiguration` | Solo con controlador de pruebas; `IdempotencyNotInIdentityTest` (W5) prohíbe su uso en `identity` (D3) |
| Puerto sin adaptador | `shared/security/SessionValidity.isActive(UUID sessionId)` | Dueño: 4b (C5b), forma ajustable |
| Reglas de construcción | W1 a W5, `WebEdgeScopeExclusionInventoryTest`, `PublicRouteAllowListTest`, `PortalRouteMapSnapshotTest`, `ProcessBeanIsolationTest`/`ProcessBeanPolicy`, instantánea `apps/api/openapi/admin.openapi.json` | Varias son **ausencias que 4b debe invertir** (sección 2, O3) |
| Dependencias | `spring-boot-starter-security` (solo cadena de filtros); `bannedDependencies` prohíbe `spring-boot-starter-oauth2-resource-server` y `spring-security-oauth2-resource-server`; `bcprov-jdk18on` ya presente; Jackson 3 (`tools.jackson`); sin Nimbus, Tink ni Redis | |

Verificación final de 4a: Surefire 186 + 1 200, Failsafe 250, `verify` en 5 min 24 s (6 min 36 s con PIT), cobertura global 97,8 %.

### 1.2 Casos de uso de identidad que existen sin endpoint HTTP

| Caso de uso | Firma | Bean hoy | Qué falta para exponerlo |
|---|---|---|---|
| `AuthenticateWithPassword` | `execute(SecurityContext, AuthenticationCommand) -> AuthenticationDecision(result, requiredDelay)`; resultado sellado: `Authenticated`, `Rejected`, `SecondFactorRequired`, `SecondFactorEnrollmentRequired` | Sí | Controlador, traducción de resultado a token, `@RateLimited`, materializador. La institución del contexto debe coincidir con `LoginInstitutionProvider` (si no, `IllegalStateException`) |
| `VerifyTotpCode` | `execute(ctx, StaffAccountId, TotpCode) -> VerifyTotpCodeDecision(accepted, requiredDelay)` | Sí | Endpoint con token restringido; materializador |
| `ConsumeRecoveryCode` | `execute(ctx, StaffAccountId, PlainRecoveryCode) -> (accepted)`; sin retroceso propio (según la exploración de 4a) | Sí | Mismo endpoint de verificación; solo el limitador por IP lo frena (código de 10 caracteres sobre 31 símbolos, unos 49,5 bits) |
| `EnrollTotpSecondFactor` | `execute(ctx, StaffAccountId) -> EnrollTotpSecondFactorResult(secret, recoveryCodes)` | Sí | Endpoint con token restringido. **No comprueba existencia previa ni confirma con un primer código** |
| `ResetPasswordWithToken` | `execute(ctx, ResetPasswordCommand) -> ResetPasswordDecision(ResetOutcome, requiredDelay)`; desenlaces `Completed`, `TokenRejected`, `PasswordRejected`, `SecondFactorMissing`, `SecondFactorRejected` | Sí | Endpoint; **revocar familias en la misma transacción** (puerto nuevo y cambio de constructor) |
| `RequestPasswordReset` | `execute(ctx, RequestPasswordResetCommand) -> RequestPasswordResetDecision()` | **No**: su `PasswordResetIssuanceScheduler` no tiene adaptador hasta el cambio 9 | Cambio 9 (y 14 para que el enlace llegue) |
| `IssuePasswordResetToken` | worker | No | Cambios 9 y 14 |

### 1.3 Lo que no existe

Emisor y verificador de tokens, anillo de claves, token restringido de MFA, familias de refresco (la última migración es `V7__create_identity_password_reset_token.sql`), cookies, CSRF, filtro de autenticación, adaptador de `CurrentInstitutionProvider` (puerto en `organization/application`, sin adaptador de producción), adaptador de `SessionValidity`, `AuthenticatedActor`, tipos de error de autenticación, controlador de producción alguno, `apps/admin-web`. Tampoco hay configuración explícita de `fail-on-unknown-properties` de Jackson en `src` (no verificado cuál es el valor por omisión en Spring Boot 4); la skill de convenciones de API exige rechazar propiedades no declaradas.

### 1.4 Restricciones estructurales que condicionan el diseño

- W3: `com.confia.shared..` no puede depender de `com.confia.identity`. Por eso el filtro y el verificador viven en `shared` y la vigencia de `sid` llega por el puerto `SessionValidity` que implementa `identity`. Los controladores van en `com.confia.identity.web` (capa `web` del módulo).
- W1: una clase `web` no depende de `infrastructure`, `org.jooq` ni código generado; la configuración pública de `identity` vive en un paquete `infrastructure.wiring` con `@NamedInterface` (nota de 4a, tarea 1.2). `ProcessBeanPolicy` exige una línea por paquete nuevo y el portal sigue prohibiendo `com.confia.identity`.
- ADR-0005, verificación 13: ningún módulo fuera de `identity` y `shared.security` usa utilidades de firma o de hash.
- W4: ninguna espera fuera de `RequiredDelayMaterializer`. W5: ninguna idempotencia en `identity`.
- `JooqConfinedToInfrastructureTest` y `TransactionsOnlyInSharedSecurityTest`: jOOQ solo en `..infrastructure..`, transacciones solo en `shared.security`.

## 2. Condiciones duras y obligaciones heredadas

Dueño de todas las de esta sección: `session-tokens-and-web-layer`, salvo indicación. Los textos entre comillas están copiados de `openspec/changes/foundations-plan/exploration.md` (líneas indicadas) o de la especificación citada.

### H1: control por IP antes del inicio de sesión (2026-09-25, informe de seguridad previo a la fusión del corte C1 de `identity-module-and-password-authentication`; líneas 124 a 126)

> **`session-tokens-and-web-layer` NO DEBE fusionar un endpoint de inicio de sesión que escriba en `identity_login_backoff` sin que el control por dirección IP de `docs/03-seguridad.md` §4.4 —o un tope funcional equivalente— exista, esté probado y esté operativo.**

Aceptación del propietario (D2, 2026-10-04): el limitador por IP en memoria es el «tope funcional equivalente» hasta el cambio 11, con sus tres limitaciones declaradas (por proceso, se pierde al reiniciar, no compartido entre réplicas; falla cerrado con `503`). La condición conserva su texto y su dueño.

Cómo se satisface: `@RateLimited(policy = "admin-login")` sobre el manejador de inicio de sesión (el interceptor corre antes del caso de uso, antes de abrir transacción y antes de Argon2id); prueba de cierre del corte C6a: N fallos desde una IP topan en `429` y `identity_login_backoff` deja de crecer; `recordFailure` tras la confirmación del caso de uso. Reserva operativa: «operativo» exige `confia.web.trusted-proxies` correcto; detrás de nginx con la lista vacía todo el tráfico compartiría un solo cubo (el llenado es del cambio 11). Pendiente aparte, sin dueño de fase (propuesto: cambio 9): purga de `identity_login_backoff`.

### H2: revocar todas las familias al restablecer (2026-10-03, de `password-recovery-token`; líneas 146 a 152)

> **1. `session-tokens-and-web-layer` NO DEBE fusionar un endpoint de restablecimiento sin revocar todas las familias de tokens de refresco de la cuenta al completarse el restablecimiento** (`docs/03-seguridad.md` §4.5 y §4.7, ADR-0005 punto 4).

La especificación de identidad (requisito «Ausencia de revocación de sesiones al restablecer», líneas 1169 a 1190) es más estricta: «ese cambio NO DEBE fusionar la emisión de tokens de refresco sin revocar todas las familias de una cuenta cuando se restablece su contraseña», con una prueba de que un restablecimiento revoca todas las familias (escenario I24). El plan de cortes pone eso en C7a, después de C5a (emisión) y C6a (inicio de sesión). Con PR encadenados a `main`, C5a llegaría a `main` antes que la revocación. Para cumplir el texto más estricto, la revocación y su prueba tendrían que entrar en la misma rebanada que la primera emisión o antes de que cualquier endpoint entregue un refresco a un cliente (ver DA-1).

Cómo se satisface: puerto nuevo en `identity.application` (por ejemplo, revocar todas las familias de una cuenta con motivo `password_change`/reset) inyectado en `ResetPasswordWithToken` (cambia su constructor y todas sus pruebas), adaptador jOOQ sobre V8, revocación **en la misma transacción** que el consumo del token y el cambio de hash, prueba I24 de integración con PostgreSQL real, e inversión de `IdentityScopeExclusionInventoryTest.noClassOfIdentityReferencesSessionsOrRefreshTokens` (línea 318), que se vuelve falsa con la primera emisión.

### H3: límite de 10 solicitudes por hora por IP (2026-10-03; líneas 154 a 157)

> **2. `session-tokens-and-web-layer` NO DEBE fusionar el endpoint de solicitud de restablecimiento sin el límite de 10 solicitudes por hora por dirección IP** (`docs/03-seguridad.md` §4.7 y §10).

Cómo se satisface: política nueva (10 por 1 h) con el limitador de 4a. Dos puntos a confirmar: (a) `RateLimitProperties` solo conoce `admin-login` y hay que generalizarla; (b) la especificación de identidad (líneas 1237 a 1239) habla de «el aprovisionamiento de Redis del cambio 11, operativo y probado», mientras que D2 aceptó el limitador en memoria expresamente para H1. Hay que confirmar con el propietario que D2 cubre H3 (DA-13).

### H4: respuesta HTTP uniforme de la recuperación y puerta de tiempo (inventario de 4a; `docs/09` líneas 127 y 128; especificación de identidad líneas 250 a 258)

Texto de 4a: `202 Accepted` + mensaje del catálogo i18n + `Referrer-Policy: no-referrer` + puerta de tiempo de `docs/03` §4.6 (200 + 200 intentos, diferencia de medianas ≤ 50 ms) sobre HTTP; `LoginTimingReportIT` y `PasswordResetRequestTimingReportIT` lo delegan a este cambio.

Cómo se satisface: `202` uniforme con mensaje del catálogo es-HN, misma forma y cabeceras para cuenta existente e inexistente; puerta de tiempo de 200 + 200 intentos con identificadores distintos y, para no chocar con el limitador, política de prueba con límites altos (los valores son configurables hasta 10 000); la puerta no puede ser solo un reporte. Matiz: `docs/03` §4.7 pide `no-referrer` en la *página* de restablecimiento (HTML servido por la SPA o nginx), no en el JSON del API; la parte verificable en 4b es la cabecera en las respuestas de los endpoints (hoy `SecurityHeadersFilter` fija `strict-origin-when-cross-origin` en todas). Riesgo de presupuesto: `verify` ya dura 5 min 24 s frente al presupuesto de 8 minutos del README de `apps/api`.

### Condición 3: CSRF antes de la primera cookie (2026-10-04, de `web-edge-foundations`, revisión de seguridad del PR 4; líneas 165 a 167)

> **3. `session-tokens-and-web-layer` NO DEBE fusionar la primera credencial en cookie (la de refresco o la de sesión) sin activar la protección CSRF de doble envío con verificación de `Origin` y sin una prueba que demuestre que una petición mutadora con la cookie y sin `X-CSRF-Token` recibe `403`.**

Cómo se satisface: depende de DA-3 y DA-4. Hoy `SecurityChains` desactiva CSRF por diseño (decisión 5 de 4a: «ninguna credencial viaja en una cookie en este cambio; la parte 4b lo decide»). Con la credencial de refresco en cookie, la superficie CSRF son los endpoints que la leen (refresh, logout, logout-all); con ambas credenciales en cookie, toda petición mutadora. `docs/03` §8.3 exige además `Origin` en lista blanca (si falta y el método no es seguro, se rechaza) y vínculo HMAC del valor CSRF con la sesión. Las pruebas del corte C6c: cookie sin `X-CSRF-Token` da `403`; token CSRF válido de otra sesión da `403`.

### Obligaciones heredadas que no son condiciones duras, pero tienen a 4b como dueño

| Id | Obligación | Fuente |
|---|---|---|
| O1 | Corregir o acotar el registro `ERROR` de `ProblemExceptionHandler.unexpected` (excepción completa, con `Detail: Key (...)=(...)` de PostgreSQL) y filtrar mensajes de excepción: «Ambos quedan como condición del cambio de observabilidad y del primer endpoint que use la base de datos» | `apply-progress.md` de 4a, cierre 6.1, decisiones del propietario; Engram #596 |
| O2 | Tope superior de `requiredDelay`, por ejemplo en `Delayed`, «cuando exista el primer caso de uso que lo produzca» (S3 de la revisión de 4.1a) | `apply-progress.md` de 4a, PR 12 |
| O3 | Invertir las ausencias que 4b rompe: requisito «Ausencia de autenticación por credencial, tokens y endpoints de identidad» de `web-edge`; `WebEdgeScopeExclusionInventoryTest` (limitador y materializador sin uso fuera de pruebas, ninguna clase implementa `SessionValidity`, ninguna usa `jakarta.servlet.http.Cookie`); entrada `allowEmptyShould` de W2a en `EmptyShouldExceptionInventoryTest` (se retira con el primer controlador); `ProblemCatalogCoverageTest` (hoy afirma que no existen `authentication-failed`, `token-invalid`, `token-expired`, `institution-not-found` ni `institution-inactive`); `PublicRouteAllowListTest` y el enumerador de rutas | Especificaciones `web-edge` y `build-integrity` |
| O4 | Decidir `springdoc.override-with-generic-response` (el traductor global podría añadir respuestas no aprobadas a cada operación), aprobar la primera instantánea OpenAPI, declarar el miembro `errors` en el esquema `ProblemDetail`, regenerar `packages/contracts` con orval | Notas fechadas de `design.md` de 4a (PR 8a y decisión 9) |
| O5 | Cabecera `WWW-Authenticate` en el `401` (S4 de 2.1b); catalogar `406` si se vuelve alcanzable | `apply-progress.md` de 4a |
| O6 | Propagar el origen de la petición (IP y agente) a los comandos de identidad y aportar la IP para el aviso al titular que enviará el cambio 14 | Especificación `web-edge` (línea 578) y de identidad (línea 1218) |
| O7 | Adaptador de `CurrentInstitutionProvider` sobre el principal autenticado y la prueba de ADR-0009: ninguna cabecera, parámetro ni cuerpo influye en la institución resuelta | `docs/09` líneas 95 a 103; especificación `organization` líneas 300 a 308 |
| O8 | Adaptador de `SessionValidity` (C5b) y la comprobación de `sid` en operaciones sensibles (ADR-0005) | Decisión 14 de 4a |
| O9 | Redacción de credenciales en DTO propios y prueba de que un flujo completo de inicio de sesión, refresco y cierre no deja contraseña, token de acceso, token de refresco, secreto TOTP ni cabecera `Authorization` en ningún registro (ADR-0005, verificación 12) | Decisión 22 de 4a, C6c |
| O10 | «Todo cambio de contraseña o de MFA cierra las demás sesiones» | `docs/03` §4.5, «Revocación»; ADR-0005 punto 6 |
| O11 | Ninguna idempotencia en endpoints de identidad (D3), ya forzada por W5 | Decisión D3 de 4a |
| O12 | Verificación de arranque de claves: clave propia presente, clave privada del otro dominio ausente, el proceso aborta | ADR-0005, verificación 14 |
| O13 | Notas fechadas en `docs/03` §4.5 y `docs/05` (variables de las claves de firma) y cierre documental (C8) | Plan de cortes |

No son de 4b (aunque aparecen en la misma conversación): condición 4 de `foundations-plan` (cambio 9: la protección de registros del trabajador); derivar `mfa_required` del rol, matriz de roles, claim de permisos y guarda de arranque de RBAC (cambio 8, decisión D4); envío del enlace y aviso al titular (cambio 14); purga de tokens y de `identity_login_backoff` (cambio 9); el adaptador de Redis y el llenado de proxies de confianza (cambio 11); `@IdempotentWrite` obligatorio y actor en la clave de idempotencia (S2, S3 y S5 de 4a: el primer endpoint de dinero, no de identidad). El informe de archivo de 4a nombra todavía a `staff-authentication-mfa-sessions` como cambio 8 y le atribuye `POST /auth/session` y la primera prueba de `IdempotentRequestHandler` con base de datos: es un puntero obsoleto.

## 3. Alcance propuesto para la parte 4b

Cada capacidad se apoya en los documentos; las decisiones que la afectan se numeran en la sección 4. «Corte» usa los identificadores de la exploración de 4a.

| # | Capacidad | Fundamento | Entrega concreta | Corte |
|---|---|---|---|---|
| 1 | Núcleo de token | ADR-0005 (EdDSA, 10 min, `iss`/`aud`, `sub`, `jti`, `sid`, `tenant`, `amr`); `docs/03` §4.5 | Emisor y verificador, anillo de dos claves con `kid`, claves por variable de entorno, verificación de arranque (O12); sin datos personales en el token | C4a |
| 2 | Token restringido de MFA | Identidad (líneas 40 a 75); exploración de 4a | Vida unos 5 min, claim de uso (`mfa-verify` o `mfa-enroll`), sin familia ni refresco, `amr=[pwd]` | C4a |
| 3 | Filtro de autenticación y principal | `docs/03` §3 (denegación por defecto), `confia-security-checklist` §1 | Filtro solo en `AdminSecurityConfiguration`; distingue token completo y restringido por ruta; ningún controlador interpreta tokens | C4b |
| 4 | `CurrentInstitutionProvider` sobre el principal | ADR-0009; `docs/09` | Adaptador y prueba de que la institución solo sale del token | C4b |
| 5 | Almacén de sesiones | ADR-0005 (esquema conceptual) | `V8`: `identity_refresh_token_family` e `identity_refresh_token` (prefijo ADR-0015), RLS forzada, sin `DELETE`, matriz de privilegios, regeneración de jOOQ | C5a |
| 6 | Emisión y rotación | ADR-0005 «Rotación con familias» | Familia por inicio de sesión, refresco opaco de 32 bytes con SHA-256, rotación con `UPDATE` condicional, ventana de gracia de 10 s, reutilización que revoca la familia y audita, inactividad, vigencia absoluta, `SessionValidity` | C5a, C5b |
| 7 | Revocación | `docs/03` §4.5 y §4.7 | Cierre de la familia actual, cierre de todas, revocación al restablecer (misma transacción, H2), al cambiar MFA (O10) | C6c, C7a |
| 8 | Endpoints de sesión | `docs/03` §4.6, §10; ADR-0005 | `login`, `mfa/verify`, `mfa/enroll`, `refresh`, `logout`, `logout-all`, lectura de la sesión actual; cada uno con `@RateLimited`, `RequiredDelayMaterializer` donde aplique, DTO explícito, códigos Problem Details, auditoría | C4b, C6a a C6c |
| 9 | Endpoints de recuperación | `docs/03` §4.7 | `reset/confirm` (con `ResetPasswordWithToken`) y `reset/request` (sujeto a DA-6), respuesta `202` uniforme, `no-referrer`, 10 por hora por IP | C7b |
| 10 | CSRF y cookies | `docs/03` §8.3; ADR-0005 | Cookie `__Secure-` con `Path` propio, sin `Domain`, `HttpOnly`, `Secure`, `SameSite=Strict`; doble envío con HMAC y `Origin`; solo si hay credencial en cookie | C6c |
| 11 | Contrato | `confia-api-conventions` | Códigos nuevos y catálogo es-HN, instantánea OpenAPI, `errors` en el esquema, orval | transversal |
| 12 | Auditoría | `confia-audit-logging` §1 | Eventos de sesión: emisión, refresco, reutilización detectada, cierre, revocación | transversal |
| 13 | Cierre | O3, O13 | Inversiones de inventarios, notas fechadas, evidencia de H1 a H4 | C8 |

Fuera de alcance, heredado de decisiones del propietario: idempotencia en identidad (D3), matriz de roles y claim de permisos (D4), Redis (cambio 11), correo (cambio 14), purgas (cambio 9), restablecimiento administrativo (cambios 8 y 4b).

## 4. Decisiones abiertas para el propietario

Cada una lleva opciones con sus costos. **No se decide ninguna aquí.** «Abierta N de 4a» remite a la numeración de la sección 4 de la exploración archivada.

**DA-1. Partición y estrategia de entrega.**
- *a)* Un solo cambio (10 cortes, 25 o más tareas). Supera el límite de quince tareas de `openspec/changes/README.md`; es el precedente de 4a, que el propietario aceptó con 25 PR.
- *b)* Tres cambios secuenciales: S1 `C4a, C4b, C5a, C5b` (núcleo, filtro, V8, rotación); S2 `C6a, C6b, C6c` (inicio de sesión, MFA, refresco, cookie y CSRF; cierra H1 y la condición 3); S3 `C7a, C7b, C8` (restablecimiento; cierra H2, H3 y H4). Cada uno es revisable y verifica solo; hay que resolver el orden de H2 (la revocación llegaría antes del primer endpoint que entrega un refresco, por ejemplo con C7a dentro de S1 o S2).
- *c)* Dos cambios: S1 y luego S2 más S3.
- La estrategia `single-pr` de la preflight no es viable (la regla de `CLAUDE.md` y `docs/15` §3 parte todo lo que pase de 800 líneas efectivas); 4a la sustituyó por `auto-chain` con `stacked-to-main`. La preflight de esta sesión además declara un presupuesto de revisión de 400 líneas, distinto de las 800 del proyecto. No se decide aquí.

**DA-2. Firma y formato del JWT (abierta 1 de 4a).** ADR-0005 fija EdDSA; la exploración de 4a halló que `NimbusJwtEncoder` de Spring Security no admite curvas de Edwards (incidencia `spring-security#17098`, no reverificada) y la construcción prohíbe el servidor de recursos OAuth2.
- *A)* JWS compacto mínimo propio sobre `java.security.Signature("Ed25519")` (JDK 25 lo trae): `alg` fijo, `kid` obligatorio, cabeceras adicionales rechazadas, vectores de RFC 8037. Cero dependencias y sin la brecha PKIX local; a cambio es código criptográfico propio (confusión de algoritmo, `crit`, base64url estricto) y exige pruebas dedicadas.
- *B)* Nimbus JOSE directo más Tink. Biblioteca madura; Tink es pesada, puede fallar `dependencyConvergence` o Trivy, y la descarga nueva puede toparse con la brecha PKIX de Windows.
- *C)* Enmendar ADR-0005 a ES256 o RS256: soportado por JDK y Nimbus, pero cambia un ADR aceptado (que dice que el cambio sería «de configuración») y el requisito de `build-integrity` que cita EdDSA.

**DA-3. Transporte de credenciales en la web (abierta 2 de 4a).** ADR-0005 y `docs/03` §4.5 hablan de «cookie» sin decir qué token viaja en ella; §8.3 describe CSRF para toda solicitud mutadora con cookie.
- *A)* Refresco en cookie `HttpOnly` y acceso en el cuerpo como `Bearer` en memoria de la SPA. La superficie CSRF se reduce a los endpoints que leen la cookie; el token de acceso (10 min) queda al alcance de un XSS, mitigado por la CSP con nonce.
- *B)* Ambos en cookie. JavaScript no ve ningún secreto; toda petición mutadora necesita CSRF de doble envío, y el cliente móvil exige además aceptar `Authorization`.
- *C)* B para la web y aceptar también `Authorization` para móvil (el filtro admite las dos fuentes). Más código y más casos de prueba.

**DA-4. Topología de origen entre SPA y API.** `docs/03` §8.4 (nginx) sirve `/api/v1/` bajo el mismo host; la CSP de §8.1 permite `connect-src https://api-admin...`; ADR-0003 habla de CORS con lista blanca. 4a dejó CORS sin configurar (el `OPTIONS` previo recibe `401`) y el supuesto de mismo origen como asunto de 4b.
- *A)* Mismo origen (`/api/v1` detrás del host administrativo). CSRF de doble envío funciona como está escrito (`__Host-confia_csrf` exige `Path=/` y es de solo host); no hay CORS.
- *B)* Subdominio de API con CORS por lista blanca y credenciales. La cookie CSRF de solo host del API no es legible desde el origen de la SPA: §8.3 habría que cambiarlo a un token entregado en el cuerpo (patrón sincronizador).
- *C)* Mismo origen en producción y CORS solo para desarrollo local. Hay que mantener dos configuraciones y probar ambas.
- Consecuencia común: no existe `apps/admin-web`, así que la prueba de atributos de cookie con Playwright (ADR-0005, verificación 11) no es ejecutable; las pruebas de 4b verificarían las cabeceras `Set-Cookie` a nivel de servidor y dejarían la de navegador al primer cambio de interfaz.

**DA-5. Ventana de gracia con solo el hash almacenado (ADR-0005 punto 4; abierta como «requiere sonda» en 4a).** ADR-0005 pide devolver «el par emitido en el paso 3» si el mismo token vuelve a presentarse en 10 s desde el mismo dispositivo, pero solo se guarda el SHA-256, así que el texto del sucesor no es recuperable.
- *a)* Guardar el sucesor cifrado con `ColumnEncryptionService` hasta que venza la gracia. Cumple la letra; añade un secreto recuperable en base de datos y una columna cifrada con su fila-id.
- *b)* Derivar el refresco con HMAC de una llave del servidor (familia y secuencia) y recomputarlo. No guarda nada recuperable; se aparta de «aleatorio de 32 bytes» y añade una llave que custodiar.
- *c)* En la gracia emitir un segundo sucesor (hermano). Simple; deja dos tokens vivos por familia y complica la detección de reutilización.
- *d)* Sin gracia. Contradice el riesgo y la prueba 2 de ADR-0005.
- Requiere sonda antes del diseño.

**DA-6. Endpoints de recuperación frente a los cambios 9 y 14.** `RequestPasswordReset` no puede ser bean sin un adaptador del puerto de programación (decisión 3 de 4a rechazó un adaptador falso y `IdentityScopeExclusionInventoryTest` lo vigila). Sin cambio 14 el enlace nunca llega al correo.
- *a)* 4b entrega solo `reset/confirm`; `reset/request` espera al cambio 9. H3 se cumple por ausencia; H4 se parte en dos.
- *b)* Adelantar el cambio 9 (según el plan depende solo de 5 y 6) antes de la parte de recuperación de 4b.
- *c)* Entregar ambos con un adaptador de producción mínimo en 4b. Contradice la decisión 3 de 4a.
- En todos los casos la recuperación no funciona de punta a punta hasta el cambio 14.

**DA-7. Autorización de las rutas autenticadas sin permiso de rol.** La cadena termina en `anyRequest().denyAll()` y la guarda de arranque de RBAC es del cambio 8 (D4), pero 4b trae rutas autenticadas sin rol: cierre de sesión, sesiones, lectura de la sesión, MFA propia, inscripción con token restringido.
- *A)* Lista cerrada de rutas autenticadas, paralela a `PublicEndpoints`, antes de `denyAll()`.
- *B)* Anotación por controlador con prueba de recorrido de rutas.
- *C)* Esperar al cambio 8, que obliga a entregar solo rutas públicas en 4b, y deja sin sentido la sesión.

**DA-8. Inscripción de MFA.** `EnrollTotpSecondFactor` inserta sin comprobar existencia (una segunda llamada produce una violación de unicidad, un `500` y el registro con `Detail: Key (...)`) y no confirma con un primer código.
- *a)* Mantener el comportamiento y arreglar solo el borde: error de dominio para «ya inscrito» (`409`), exclusivo de la sesión restringida de inscripción; la sesión completa se emite tras `mfa/verify`.
- *b)* Cambiar el caso de uso a dos pasos (pendiente y confirmado, con columna nueva en una tabla archivada). Evita bloqueos por una inscripción nunca confirmada; amplía el alcance.
- *c)* Como *a)* y diferir inscripción y baja voluntarias con sesión completa (la matriz de `docs/03` §5.2 las da a todos los roles) a otro cambio. O10 aplica de todos modos.

**DA-9. Techo del retardo materializado (O2, S3 de 4a).** `BackoffPolicy.CAP` es 900 s. La respuesta HTTP se retiene hasta esa duración; nginx (`proxy_read_timeout` por omisión de 60 s) y Cloudflare (que según `docs/01` está delante; su corte por omisión de 100 s en planes no Enterprise no está verificado aquí) pueden cortar antes, con lo que «demora, no deniega» se vuelve «niega por tiempo agotado». Además cada espera retiene un permiso del semáforo (200 por omisión).
- *a)* Dejar el diseño de 4a y documentar que un cliente ve el corte del proxy.
- *b)* Acotar la espera materializada con un tope configurable inferior (por ejemplo 30 s) en `Delayed`, a costa de la uniformidad frente a enumeración para cuentas con más fallos.
- *c)* Para retardos mayores que el tope, responder `429` uniforme con `Retry-After`, lo que contradice el requisito «no rechazar por causa del retroceso un intento con credenciales correctas».

**DA-10. Fuga de `ProblemExceptionHandler.unexpected` (O1).**
- *a)* Corrección mínima en 4b: registrar solo la clase, el `SQLState` y el `requestId` para excepciones de acceso a datos, sin mensaje ni traza.
- *b)* Esperar al cambio 10 (filtro de mensajes de excepción en el registro estructurado) y mientras tanto no exponer ninguna ruta que pueda producir una violación (no controlable: DA-8 muestra una).
- *c)* Ambas: corrección mínima ahora y filtro central después.

**DA-11. Inactividad de 30 minutos (ADR-0005 y `docs/03` §4.5).** Con un acceso de 10 min sin estado, el servidor solo observa actividad al refrescar.
- *a)* Imponerla en el refresco con `last_used_at` de la familia; la granularidad real es el ciclo de refresco (hasta unos 10 min más).
- *b)* Además actualizar `last_used_at` por petición autenticada (una escritura por petición; contradice «autenticación sin consulta de estado»).
- *c)* Dejarla a la interfaz (`docs/ui-ux/04`) y aplicar solo la vigencia absoluta y la de refresco en el servidor.
- Aclarar también la lectura conjunta de «refresco de 8 h», «absoluta de 12 h» y «30 min de inactividad».

**DA-12. Vinculación a agente de usuario e IP (abierta 11 de 4a).** ADR-0005: «un cambio de ambos simultáneo exige reautenticación».
- *a)* Solo registrar huella e IP en la familia.
- *b)* Aplicar al refrescar: si cambian ambos, revocar la familia.
- *c)* Aplicar con aviso al titular (depende del cambio 14).
- La IP es dato personal (retención de `docs/08`) y detrás de CGNAT cambia a menudo; el agente cambia menos.

**DA-13. Límites de tasa de los endpoints sin fila en `docs/03` §10.** Solo existe la fila de inicio de sesión de administración (10 por minuto por IP). No tienen valor documentado: refresco, verificación de MFA, inscripción, confirmación del restablecimiento. Además hay que generalizar `RateLimitProperties`, hoy fijada a un solo prefijo, y confirmar que D2 cubre H3 (sección 2). Opciones: *a)* valores propios por endpoint definidos en la propuesta; *b)* una política común «autenticación» para todos; *c)* solo inicio de sesión y solicitud de restablecimiento, y diferir el resto con la limitación declarada.

**DA-14. Sesiones activas (abierta 9 de 4a).** ADR-0005 exige una vista con revocación individual.
- *a)* `GET` y `DELETE` de sesiones en 4b.
- *b)* Solo cierre actual y cierre de todas; la vista, con la primera pantalla.

**DA-15. Cuenta desactivada (abierta 10 de 4a).** `identity_staff_account` no tiene columna de estado; el motivo `admin_revoke` no tiene productor.
- *a)* Diferir al cambio 8 (con el restablecimiento administrativo).
- *b)* Columna mínima en 4b (migración sobre una tabla archivada).

**DA-16. Endpoint JWKS (abierta 7 de 4a).** ADR-0005 y `docs/03` dicen «publicadas por JWKS interno»; hoy el único verificador es el propio proceso administrativo.
- *a)* Diferir (anillo en memoria).
- *b)* Publicarlo ahora como ruta pública de solo lectura (añade una entrada a `PublicEndpoints` y a la instantánea).
- *c)* Publicarlo restringido por red (depende del cambio 11).

**DA-17. Alcance del portal (abierta 5 de 4a).**
- *a)* El portal sigue deniegando todo; solo se verifica que su entorno no contiene la clave privada administrativa.
- *b)* Registrar también el par de claves del portal y su verificación de arranque sin ninguna ruta.

**DA-18. Contrato de rutas y del token.** (i) Nombres: las convenciones piden recursos en plural y sin verbos; el ejemplo de nginx de `docs/03` usa el prefijo `/api/v1/auth/` y 4a habló de `/auth/session`. Opciones: estilo de recursos (`POST .../auth/sessions`, `DELETE .../auth/sessions/current`) o estilo de acciones (`login`, `logout`) bajo el mismo prefijo. (ii) Claim `permissions`: vacío o ausente hasta el cambio 8. (iii) Si `sid` se comprueba contra base de datos en toda petición o solo en operaciones sensibles (ADR-0005).

## 5. Enfoques

### 5.1 Arquitectura de sesión
ADR-0005 ya decidió la opción C (JWT de 10 min más refresco opaco rotativo con familias en PostgreSQL), frente a sesiones en Redis y a un proveedor externo. No se reabre. La implicación para 4b: Redis no entra en el camino de autenticación y la tabla de refrescos crece (la purga es del cambio 9).

### 5.2 Firma del JWT (DA-2)

| Enfoque | Ventajas | Costos | Esfuerzo |
|---|---|---|---|
| A. JWS propio sobre JDK | Cero dependencias, sin PKIX, cumple EdDSA | Código criptográfico propio; pruebas de vectores y de ataques de confusión | Medio |
| B. Nimbus y Tink | Madura | Peso, convergencia, descargas, nueva dependencia crítica | Medio a alto |
| C. ES256 o RS256 | Soporte nativo en Spring | Enmienda de ADR y de un requisito publicado | Bajo a medio |

### 5.3 Transporte (DA-3 y DA-4)

| Enfoque | Superficie CSRF | Exposición a XSS | Efecto en móvil |
|---|---|---|---|
| A. Refresco en cookie, acceso Bearer | Solo refresh y logout | Acceso de 10 min legible por la página | Natural |
| B. Ambos en cookie | Toda mutación | Ninguna | Requiere `Authorization` aparte |
| C. Cookie web y Bearer aceptado | Toda mutación web | Ninguna | Natural |

### 5.4 Gracia (DA-5)
Cifrar el sucesor, derivar el refresco, emitir un hermano o prescindir de la gracia: ver DA-5; requiere sonda.

## 6. Pronóstico de tamaño y costuras

Pronóstico nominal de la exploración de 4a (líneas efectivas sin `openspec` ni código generado):

| Corte | Contenido | Nominal |
|---|---|---|
| C4a | Núcleo del token, anillo de claves, restringido, verificación de arranque | 600 |
| C4b | Filtro, `CurrentInstitutionProvider`, lectura de sesión, prueba de ADR-0009 | 500 |
| C5a | V8, repositorios, emisión, revocación | 700 |
| C5b | Rotación, gracia, reutilización, inactividad, vigencia, `sid` | 750 |
| C6a | Inicio de sesión; cierra H1; puerta de tiempo | 700 |
| C6b | MFA verify y enroll | 600 |
| C6c | refresh, logout, logout-all, cookie, CSRF, redacción | 700 |
| C7a | Revocación al restablecer, inversión del inventario (H2) | 450 |
| C7b | Endpoints de recuperación (H3, H4) | 650 |
| C8 | Cierre documental | 300 |
| **Total** | | **5 950** |

**Evidencia de subestimación.** 4a: 2 250 nominales para C1a a C3, 17 926 efectivas en 25 PR (todos entre 399 y 983 líneas; siete con excepción de tamaño aprobada); 26 tareas frente al límite de 15; el re-pronóstico de mitad de camino (11 000 a 12 700) también se quedó corto. Causas visibles: las revisiones de seguridad añadieron pruebas y endurecimiento, y cada hallazgo se cerró dentro del cambio. 4b es más sensible aún (criptografía, concurrencia, cookie, puertas de tiempo con Argon2id).

**Pronóstico realista.** Rango de planeación de 12 000 a 18 000 líneas efectivas (2 a 3 veces el nominal), con cola hasta unas 25 000 si se repite el patrón de 4a; de 16 a 25 PR de 800 líneas como máximo; 25 o más tareas. Es una estimación, no una medición. Cada puerta de tiempo añade minutos a `verify`.

**Costuras naturales** (ordenadas por dependencia):
1. `C4a` núcleo de token: sin base de datos ni rutas; verificable con vectores de RFC 8037.
2. `C5a`/`C5b` V8 y rotación: cierran solos con pruebas de concurrencia contra PostgreSQL real.
3. `C4b` filtro y adaptador de institución: necesita `C4a` y una ruta de lectura de sesión.
4. `C7a` revocación al restablecer: depende de `C5a`; por el texto de H2 conviene antes del primer endpoint que entrega un refresco.
5. `C6a` inicio de sesión: depende de `C2` de 4a, `C4` y `C5`; cierra H1.
6. `C6b`, `C6c`: MFA; refresco, cierre, cookie y CSRF (cierra la condición 3).
7. `C7b`: recuperación; depende de H2 y de la resolución de DA-6.
8. `C8`: cierre documental.

Ordenes duros ya escritos: C2 antes de C6a; C5 antes de C7a; C7a antes de C7b; C4 antes de todo endpoint autenticado. Corte sugerido de partición (DA-1 *b*): S1 (1 a 3), S2 (5 y 6, con 4 dentro de S1 o S2), S3 (7 y 8).

## 7. Riesgos

**Seguridad**
- Código criptográfico propio si se elige DA-2 *A*: confusión de algoritmo, cabeceras `crit`, comparación de firma, base64url laxo.
- Concurrencia de la rotación: dos refrescos simultáneos del mismo token, la ventana de gracia y la detección de reutilización exigen pruebas con dos transacciones reales (`confia-testing-playbook` §5).
- Enumeración: respuesta, cabeceras y tiempo idénticos en inicio de sesión y recuperación; el limitador y el retardo no deben abrir un canal (`429` frente a `401`).
- `X-Forwarded-For` y proxies de confianza: con la lista vacía el limitador por IP no distingue clientes detrás de nginx (cambio 11).
- Claves privadas en variables de entorno hasta el cambio 11.
- `ConsumeRecoveryCode` sin retroceso propio: solo lo frena el limitador por IP.
- Fuga de la regla 11 por `unexpected` (DA-8, DA-10) y por DTO con credenciales (`toString` de registros).
- El token restringido se puede reutilizar en su vida; solo el retroceso por cuenta limita el adivinado de TOTP.
- Rechazo de propiedades no declaradas en JSON no configurado explícitamente.

**Tamaño y entrega**
- Pronóstico de 12 000 a 18 000 líneas y 25 o más tareas contra el límite de 15 tareas y la regla de 800 líneas por PR. La estrategia `single-pr` de la preflight es incompatible con la regla del proyecto (`CLAUDE.md`, `docs/15` §3); no se decide aquí.
- Presupuesto de `verify` (5 min 24 s hoy) con dos puertas de tiempo de 400 intentos con Argon2id.
- Riesgo de «verde por la razón equivocada» al cambiar firmas de casos de uso con muchas pruebas (cambio de constructor de `ResetPasswordWithToken`).
- Instantánea OpenAPI: la primera operación puede traer respuestas genéricas no aprobadas (O4).

**Dependencias**
- Cambio 9 (trabajador): adaptador de programación de la emisión (sin él no hay `reset/request`), purga de `identity_login_backoff` y de las tablas de refresco, ejecutor de db-scheduler frente a hilos virtuales, condición 4.
- Cambio 10 (registros): filtro de mensajes de excepción, registro estructurado, métricas de `429` y de reutilización, semántica de `traceId`.
- Cambio 11 (Redis y contenedores): limitador compartido, llenado de proxies, cabeceras de proxy (HSTS, COOP/COEP), tiempos de lectura frente al retardo, claves.
- Cambio 14 (correo): aviso al titular por reutilización detectada, por restablecimiento y por MFA; el enlace de recuperación.
- Cambio 8: `mfa_required` desde el rol, claim de permisos, guarda de arranque, restablecimiento administrativo.

## 8. Recomendación y preparación para la propuesta

- La arquitectura de sesión está fijada por ADR-0005; las decisiones que bloquean la propuesta son DA-1 (partición y entrega), DA-2 (firma), DA-3 y DA-4 (transporte y origen), DA-5 (gracia, con sonda) y DA-6 (recuperación frente al cambio 9).
- El resto (DA-7 a DA-18) pueden resolverse en la propuesta o en el diseño con respuesta del propietario.
- Sondas recomendadas antes del diseño: vectores Ed25519 de RFC 8037 con `Signature("Ed25519")` en JDK 25; reentrada de la gracia con solo el hash (DA-5); comportamiento de `spring.jackson` frente a propiedades desconocidas en Spring Boot 4; efecto de `springdoc.override-with-generic-response`; tiempos de corte de nginx y de Cloudflare frente a un retardo largo.
- **Listo para propuesta: Sí, con condiciones.** Pedir al propietario DA-1 a DA-6 antes de redactarla.

## 9. Decisiones del propietario (2026-10-06)

| Id | Decisión |
|---|---|
| DA-1 | **Tres cambios secuenciales.** S1: núcleo de token, filtro de autenticación, migración V8, rotación y la revocación al restablecer (H2 se cumple antes de cualquier endpoint que entregue un refresco). S2: inicio de sesión, MFA, refresco, cookie y CSRF (cierra H1 y la condición 3). S3: endpoints de recuperación y cierre (H3 y H4). Cada uno se verifica y archiva por separado. |
| DA-2 | **JWS compacto mínimo propio sobre `java.security.Signature("Ed25519")` del JDK.** `alg` fijo, `kid` obligatorio, cualquier otra cabecera rechazada, base64url estricto, vectores de RFC 8037 y pruebas de confusión de algoritmo. Sin dependencias nuevas. |
| DA-3 | **Refresco en cookie y acceso como `Bearer`.** El refresco viaja en una cookie `HttpOnly`, `Secure`, `SameSite=Strict` con `Path` propio. El acceso (10 min) viaja en `Authorization: Bearer` y se guarda en memoria de la SPA. La protección CSRF cubre solo los endpoints que leen la cookie. |
| DA-4 | **Mismo origen.** El API vive en `/api/v1` bajo el host administrativo (`docs/03` §8.4), sin CORS. En desarrollo local, el proxy de Vite da el mismo origen. El `connect-src` de la CSP de §8.1 se corrige con una nota fechada. |
| DA-5 | **Sucesor cifrado.** Durante la ventana de gracia se guarda el sucesor cifrado con `ColumnEncryptionService` y se vacía al vencer. |
| DA-6 | **El cambio 9 va antes del tercero.** El orden queda S1, S2, cambio 9 (`background-jobs-with-db-scheduler`) y S3. S3 entrega la solicitud y la confirmación del restablecimiento con el límite de 10 por hora por IP (H3), sin adaptadores falsos. El enlace llega por correo cuando se fusione el cambio 14. |

Las decisiones DA-7 a DA-18 se resuelven en la propuesta o el diseño del cambio al que pertenezcan.
