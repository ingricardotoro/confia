# Exploración: endpoints de sesión, cookie y CSRF (F0, cambio 7, parte 4b, S2)

- **Cambio:** `session-endpoints-cookie-and-csrf`
- **Fase:** explorar
- **Fecha:** 2026-10-20
- **Línea base:** `main` en `dafabf3` (rama `change/session-endpoints-cookie-and-csrf`, árbol limpio)
- **Estado:** exploración terminada, pendiente de las decisiones del propietario de la sección 5 y de la propuesta.
- **Procedencia:** solo lectura de código, especificaciones principales, ADR-0005, `docs/03`, `docs/ui-ux` y del archivo de `session-tokens-and-web-layer` (S1). Ninguna sonda se ejecutó. Lo que no se verificó se marca como tal.
- **Archivo previsto:** `openspec/changes/session-endpoints-cookie-and-csrf/exploration.md`. Espejo en Engram: tema `sdd/session-endpoints-cookie-and-csrf/explore`.

## 0. Resumen

S2 es el primer cambio que entrega credenciales a un cliente: el inicio de sesión, la verificación e inscripción de MFA, el refresco, el cierre de sesión, la cookie y la protección CSRF. S1 dejó todo el mecanismo (tokens, familias, rotación, gracia, reutilización, `SessionLiveness`, filtro `Bearer`) sin ningún endpoint que lo use. Hallazgos que cambian el plan:

1. **El filtro actual no admite el token restringido de MFA en ninguna ruta.** `AccessTokenAuthenticationFilter` solo llama a `verifyAccess`; `verifyMfa` no tiene consumidor. Los endpoints de verificación e inscripción necesitan una clase de ruta nueva y un principal de otro tipo (no `AuthenticatedActor`), o un token restringido podría hacerse pasar por una sesión completa.
2. **`logout` y `mfa_change` no existen como motivos de revocación.** El `CHECK` de `V8` admite solo `reuse_detected` y `password_change`; la especificación de identidad lo exige y delega a S2 la migración que lo amplíe (`V9`). Tampoco existe un caso de uso que revoque una sola familia por petición del titular.
3. **La inscripción de MFA es de un solo paso y no confirma.** Si la respuesta con el secreto y los códigos de recuperación se pierde, la cuenta queda con un secreto que nadie tiene, y el restablecimiento de contraseña exige un código de ese secreto (`docs/03` §4.7): cierre de la cuenta sin salida hasta el cambio 8. Es la decisión de producto más delicada de S2 (DA-7).
4. **El JSON de auditoría se arma por concatenación en al menos 16 sitios de 8 clases**, no en los 3 que nombró el informe de archivo de S1.
5. **Hay contradicciones documentales que S2 debe resolver con notas fechadas:** `docs/05` define `SESSION_COOKIE_DOMAIN` mientras `docs/03` §4.5 prohíbe el atributo `Domain`; el `connect-src` de `docs/03` §8.1 apunta a `api-admin...` aunque el propietario fijó mismo origen; el catálogo de errores de `docs/ui-ux/04` tiene `session-expired` y `mfa-attempts-exceeded` que no existen en `ProblemCode`.
6. **Tamaño.** Pronóstico nominal de unas 3 300 líneas efectivas; con el factor 2,2 del historial de S1, unas 7 300 (rango 6 600 a 9 900) en 10 a 14 PR de 800 líneas como máximo. La estrategia `single-pr` de la preflight no es viable ante la regla de `CLAUDE.md` y `docs/15` §3; con la regla permanente del propietario («partir solo si hace falta») se resuelve como cadena de PR apilados (DA-1).

## 1. Estado actual (verificado en código)

Rutas bajo `apps/api/app/src/main/java/com/confia/`.

### 1.1 Lo que S1 y 4a entregan y S2 consume

| Pieza | Clases | Estado y brecha para S2 |
|---|---|---|
| Emisión de familia | `identity/application/IssueSession`, `IssueSessionCommand(accountId, secondFactorVerified, userAgent, clientAddress)`, `IssuedSession` | Operativo (bean registrado). `execute` firma el acceso después de confirmar. Ningún llamador de producción. Hay `runWithinTransaction` pensado para componerse con otra transacción |
| Rotación | `RotateRefreshToken`, `RotateRefreshTokenCommand(presentedToken, userAgent)`, `RefreshOutcome` (`Rotated(FRESH\|GRACE)` o `Rejected(RefreshRejection)`) | Operativo. La institución sale de `LoginInstitutionProvider`. **El comando no lleva IP**: la vinculación por IP (DA-8) lo ampliaría |
| Vigencia | `SessionLiveness` (implementa `SessionValidity`), `DescribeCurrentSession`, `SessionLifetimePolicy` | Operativo. Rutas `LIVE_SESSION` consultan la familia en cada petición |
| Revocación | `AccountSessionRevoker.revokeAllLive(institution, account, reason, now)` + `JooqAccountSessionRevoker`; `RefreshTokenFamilyRepository.revokeForReuse` | Solo `PASSWORD_CHANGE` y `REUSE_DETECTED`. **No existe revocar una sola familia por cierre de sesión ni «todas salvo la actual»** |
| Autenticación | `AuthenticateWithPassword.execute(SecurityContext, AuthenticationCommand) -> AuthenticationDecision(result, requiredDelay)`; resultado sellado `Authenticated`, `Rejected`, `SecondFactorRequired`, `SecondFactorEnrollmentRequired` | Operativo. Exige un `SecurityContext` con la institución de `LoginInstitutionProvider` (si no, `IllegalStateException`) |
| MFA | `VerifyTotpCode` (con retroceso propio), `ConsumeRecoveryCode` (sin retroceso), `EnrollTotpSecondFactor` -> `EnrollTotpSecondFactorResult(secret, recoveryCodes)` | Operativos. La inscripción inserta sin comprobar existencia ni confirmar; sin URI `otpauth://` (emisor y etiqueta son decisión de producto, según su Javadoc) |
| Tokens | `shared/security/token/*`: `AccessTokenIssuer.issueAccess/issueMfa`, `AccessTokenVerifier.verifyAccess/verifyMfa`, `MfaTokenClaims` (`aud=confia-admin-mfa`, 300 s, `purpose` = `mfa-verify` \| `mfa-enroll`, sin `sid`) | Operativos. `verifyMfa` sin consumidor |
| Filtro y principal | `shared/web/authentication/AccessTokenAuthenticationFilter`, `AuthenticatedEndpoints` (una entrada: `GET /api/v1/auth/sessions/current`, `LIVE_SESSION`), `AuthenticatedActor` (`ScopedValue`) | **Solo acepta token de acceso.** El restringido da `token-invalid` en toda ruta |
| Cadena | `shared/web/edge/SecurityChains.denyByDefault` (`csrf` deshabilitado, sin estado), `PublicEndpoints` (solo springdoc), `AdminSecurityConfiguration` | Login y refresco requieren entradas públicas nuevas; CSRF hay que activarlo de forma selectiva |
| Limitador | `RateLimiter` (puerto: `tryAcquire`, `recordFailure`), `InMemoryRateLimiter`, `RateLimiterConfiguration` (bean `admin-login`), `RateLimited`, `RateLimitInterceptor`, `RateLimitProperties` | Política `admin-login` registrada y **sin ruta**. **`RateLimitProperties` está fijada al prefijo `confia.web.rate-limit.admin-login`**: una segunda política exige generalizarla |
| Retardo | `RequiredDelayMaterializer.execute(Supplier<Delayed<T>>)`, `Delayed`, `RequiredDelayConfiguration` | Sin ruta. Exige hilo virtual y sin transacción activa. **Sin tope superior** del retardo (BackoffPolicy.CAP = 900 s) |
| Origen | `RequestOrigin` (`requestId`, `clientAddress`, `userAgent`), `RequestContextFilter`, `TrustedProxies` | Operativo. `confia.web.trusted-proxies` vacío por omisión |
| Errores | `ProblemCode` (14 códigos, incluye `token-invalid` y `token-expired`), `ProblemResponses`, `ProblemExceptionHandler` | Faltan `authentication-failed` y los de S2. El catálogo es-HN vive en `resources/i18n/problems.properties` |
| Cableado | `IdentityConfiguration` (`identity.infrastructure.wiring`), `IdentityWebConfiguration` (`identity.web.wiring`), `ProcessBeanPolicy` | Cada controlador y bean nuevo exige línea en `ProcessBeanPolicy` en el mismo PR (ADR-0024) |
| Esquema | `V8` (familia y token de refresco), `V6` (credencial TOTP: `last_accepted_counter` inicia en -1; `GRANT SELECT, INSERT, UPDATE`, sin `DELETE`) | `V8` no concede `UPDATE` sobre `ip_first_seen` ni la huella; el `CHECK` de motivos está cerrado |

### 1.2 Lo que no existe

Ningún controlador de identidad salvo `CurrentSessionController`; ninguna cookie (el uso de `jakarta.servlet.http.Cookie` está prohibido por `WebEdgeScopeExclusionInventoryTest`, y CSRF está deshabilitado por diseño); ninguna lista de orígenes permitidos (`WebEdgeProperties` solo tiene proxies y longitud del agente); ninguna clave de HMAC para CSRF; ninguna configuración explícita de `FAIL_ON_UNKNOWN_PROPERTIES` de Jackson (sonda S-5 de S1, nunca ejecutada); ningún `apps/admin-web` (la prueba de atributos de cookie con Playwright de ADR-0005 verificación 11 no es ejecutable).

### 1.3 Restricciones estructurales

- W3: `shared` no depende de `identity`. El manejo de cookie y de CSRF vive en `shared.web`; los controladores, en `identity.web`.
- W1: una clase `web` no depende de `infrastructure` ni de jOOQ. El controlador de login no puede tocar tipos de servlet si W2 lo veta (verificar en el diseño); un ayudante `SessionCookies` en `shared.web` resuelve ambos.
- W4: ninguna espera fuera de `RequiredDelayMaterializer`. W5: ninguna idempotencia en `identity` (D3).
- `TransactionsOnlyInSharedSecurityTest` y `JooqConfinedToInfrastructureTest`: toda escritura por `TransactionRunner`.
- `SigningAndHashingConfinementTest`: el HMAC de CSRF solo puede vivir en `shared.security` o `identity`, no en `shared.web`. El componente criptográfico va en `shared.security` y el filtro lo usa a través de un puerto o una clase de ese paquete.
- Inventarios que S2 invierte (O3): requisitos de ausencia de `web-edge` (WE29, WE30, WE31), `WebEdgeScopeExclusionInventoryTest` (cookie, uso del limitador y del materializador), `ProblemCatalogCoverageTest` y `ProblemCodeTest` (hoy niegan `authentication-failed`), `PublicRouteAllowListTest`, el requisito «Solo un token de acceso completo... el restringido se rechaza en toda ruta», y en `identity` los requisitos de ausencia de la dimensión IP, de la vinculación, de la revocación por MFA y del catálogo de motivos.

## 2. Condiciones duras y obligaciones heredadas

### H1: control por IP antes del inicio de sesión (2026-09-25)

Texto: S2 no debe fusionar un endpoint de inicio de sesión que escriba en `identity_login_backoff` sin que el control por IP de `docs/03` §4.4, o un tope funcional equivalente, exista, esté probado y esté operativo. D2 (2026-10-04) aceptó el limitador en memoria como equivalente hasta el cambio 11, con sus tres limitaciones declaradas (por proceso, se pierde al reiniciar, no se comparte; falla cerrado con `503`).

Cómo se satisface: `@RateLimited(policy = "admin-login")` sobre el manejador de login (el interceptor corre antes de abrir transacción y antes de Argon2id); `recordFailure(client)` tras confirmar el caso de uso cuando el resultado es `Rejected`; prueba de cierre: N fallos desde una IP topan en `429` y `identity_login_backoff` deja de crecer. Reserva de «operativo»: con `confia.web.trusted-proxies` vacío todo el tráfico detrás de nginx comparte un cubo; el llenado es del cambio 11.

Pregunta abierta: la fila «Por IP contra cuentas distintas» de §4.4 (5 cuentas en 10 minutos: bloqueo de 1 hora y alerta) no está implementada por el limitador de 4a (solo capas 1 y 2). El requisito de identidad «Ausencia de la dimensión por dirección IP» nombra las tres reglas y a S2 como responsable (DA-9).

### Condición 3: CSRF antes de la primera cookie (2026-10-04)

Texto: no fusionar la primera credencial en cookie sin activar la protección CSRF de doble envío con verificación de `Origin`, y sin una prueba de que una petición mutadora con la cookie y sin `X-CSRF-Token` recibe `403`. Consecuencia de orden: el PR del mecanismo CSRF se fusiona **antes** que cualquier PR que emita `Set-Cookie`, con su prueba sobre un arnés (sección 6).

### Seguimientos transferidos por el archivo de S1

| Id | Seguimiento | Estado en código |
|---|---|---|
| F1a | Aserción directa de I24: ningún token de la cuenta queda vigente tras el restablecimiento | Hoy indirecta (`SessionLivenessIT:220`, `RotateRefreshTokenIT:161`) |
| F1b | Prueba del revocador entre instituciones | No existe. Necesita una segunda institución de fixture |
| F1c | Serializador común para el JSON de auditoría | **Al menos 16 sitios en 8 clases** (`AuthenticateWithPassword` 2, `ConsumeRecoveryCode` 1, `IssueSession` 1, `VerifyTotpCode` 1, `RequestPasswordReset` 2, `IssuePasswordResetToken` 2, `RotateRefreshToken` 4, `ResetPasswordWithToken` 3). Solo interpolan constantes y enteros, salvo `IssuePasswordResetToken` (instante) |
| F2 | 8 mutantes sobrevivientes en `ClaimReader` | Rango de `epochSecond` (`value < 0 \|\| value > LAST_EPOCH_SECOND`, línea 85): pruebas de época negativa, 0, `LAST_EPOCH_SECOND`, `LAST_EPOCH_SECOND + 1`, `Long.MAX_VALUE`, decimales y cadenas |
| F3 | `jti` en `SessionSecretsNotObservableTest` | El mapa `secrets` no incluye el `jti` ni el de los tokens restringidos |

### Obligaciones que S1 dejó a S2 (propuesta y diseño de S1)

| Id | Obligación | Fuente |
|---|---|---|
| O2 | Tope superior de `requiredDelay` en `Delayed` | S3 de la revisión de 4.1a (DA-11) |
| O6 | Propagar el origen (IP y agente) a los comandos de identidad | `IssueSessionCommand` ya lo lleva; faltan `AuthenticationCommand` y `RotateRefreshTokenCommand` en lo que corresponda |
| O9 | Prueba de que un flujo completo (login, MFA, refresco, cierre) no deja contraseña, token, secreto TOTP ni `Authorization` en ningún registro | ADR-0005 verificación 12 |
| O10 | «Todo cambio de contraseña o de MFA cierra las demás sesiones» | `docs/03` §4.5 (DA-12) |
| DA-12 de S1 | Reevaluar la vinculación por agente e IP con el refresco | Requisito de ausencia en identidad (DA-8) |
| DA-4 de S1 | Nota fechada que corrija el `connect-src` de `docs/03` §8.1 (mismo origen) | Propuesta de S1 |
| DA-13 de S1 | Límites de tasa de los endpoints sin fila en `docs/03` §10 | DA-10 |
| DA-14 de S1 | Vista de sesiones activas | DA-6 |
| O1 | La violación de unicidad de DA-8 ya no se registra con `Detail: Key` (corregido en S1, PR 7), pero seguiría respondiendo `500` | DA-7 |

## 3. Alcance propuesto y contrato HTTP

### 3.1 Endpoints (estilo de recursos heredado de S1; DA-14 de esta exploración fija los nombres definitivos)

| # | Método y ruta | Autenticación | Limitación | Respuestas principales |
|---|---|---|---|---|
| 1 | `POST /api/v1/auth/sessions` (inicio de sesión) | Ninguna; `Origin` permitido | `admin-login` + materializador | `200` sesión (cuerpo con acceso + `Set-Cookie` de sesión y CSRF), `200` segundo factor pendiente (token restringido en el cuerpo, sin cookies), `401 authentication-failed` uniforme, `400`, `403 csrf-invalid`, `415`, `429`, `503`, `500` |
| 2 | `POST /api/v1/auth/mfa/verifications` | `Bearer` restringido, `purpose=mfa-verify` | política MFA + materializador | `200` sesión (como 1), `401 second-factor-invalid`, `401 token-invalid/expired`, `429`, `503` |
| 3 | `POST /api/v1/auth/mfa/enrollments` | `Bearer` restringido, `purpose=mfa-enroll` | política MFA | `201` secreto en base32 + 10 códigos de recuperación (`no-store`), `409 mfa-already-enrolled`, `401`, `429` |
| 3b | (solo si DA-7 elige dos pasos) `POST /api/v1/auth/mfa/enrollments/current/confirmation` | `Bearer` restringido, `purpose=mfa-enroll` | política MFA + materializador | `200` sesión, `401 second-factor-invalid` |
| 4 | `POST /api/v1/auth/sessions/refresh` | Cookie de sesión + `X-CSRF-Token` + `Origin` | política de sesión | `200` acceso nuevo + cookies rotadas, `401 session-expired` uniforme (borra las cookies), `403 csrf-invalid` |
| 5 | `DELETE /api/v1/auth/sessions/current` | Cookie + CSRF + `Origin` (DA-5) | política de sesión | `204` siempre que la petición sea bien formada; borra las cookies |
| 6 | `DELETE /api/v1/auth/sessions` (cerrar todas) | `Bearer` acceso, `LIVE_SESSION` | política de sesión | `204`, borra las cookies; `401` |
| existente | `GET /api/v1/auth/sessions/current` | `Bearer`, `LIVE_SESSION` | ninguna | sin cambios |

Ninguna lleva `Idempotency-Key` (D3, W5). Todas devuelven `Cache-Control: no-store` (ya lo fija `SecurityHeadersFilter`).

### 3.2 Cuerpos

- Inicio de sesión: `{"identifier": string, "password": string}`. Validación de longitud máxima solamente (no se impone el mínimo de 12 caracteres al entrar, para no filtrar la política). Propiedades desconocidas rechazadas (depende de la sonda S-5).
- Respuesta de sesión: `{"status":"authenticated","accessToken":"...","accessTokenExpiresAt":"...","sessionExpiresAt":"...","idleExpiresAt":"..."}`; sin datos personales ni `sid`.
- Segundo factor pendiente: `{"status":"second-factor-required"|"second-factor-enrollment-required","secondFactorToken":"...","secondFactorTokenExpiresAt":"..."}`.
- Verificación: `{"code":"123456"}` o `{"recoveryCode":"..."}` (exactamente uno).
- Inscripción: respuesta `{"secret":"<base32>","recoveryCodes":["...", x10]}`; `otpauth://` queda fuera (decisión de producto de emisor y etiqueta, Javadoc de `EnrollTotpSecondFactorResult`).

### 3.3 Códigos de Problem Details nuevos (propuestos)

`authentication-failed` (401), `second-factor-invalid` (401), `session-expired` (401, ya catalogado en `docs/ui-ux/04`), `csrf-invalid` (403), `mfa-already-enrolled` (409). `mfa-attempts-exceeded` (429) del catálogo de interfaz **no encaja** con el diseño actual: el retroceso demora, no deniega (DA-11).

## 4. Análisis de enfoques

### 4.1 Cookie frente a `Bearer` en la SPA administrativa

Decidido por el propietario en S1 (DA-3): el refresco viaja en cookie `HttpOnly`, `Secure`, `SameSite=Strict`; el acceso (10 min) viaja en `Authorization: Bearer` y vive en memoria de la SPA. Mismo origen (DA-4 de S1), sin CORS. Lo que queda abierto es el detalle:

| Aspecto | Opción A | Opción B | Recomendación |
|---|---|---|---|
| `Path` de la cookie de sesión | `/api/v1/auth/sessions` (solo refresco y cierre la reciben) | `/api/v1/auth` o `/` | A: reduce la superficie CSRF a las rutas que la leen. No usar `__Host-` para esta cookie (exige `Path=/`; `docs/03` §4.5 ya lo explica) |
| Vida | Cookie de sesión del navegador (sin `Max-Age`) | Persistente hasta el vencimiento del refresco | A: estación de caja compartida; el servidor sigue limitando por vigencias (DA-2) |
| Contenido | El texto del refresco (43 caracteres) | Identificador de familia | El refresco: es lo que rota y se compara por hash |
| Nombre | `__Secure-confia_admin_sid` (ya escrito en `docs/03` §4.5) | — | Se conserva aunque «sid» no sea el `sid` del token |

Efecto de `SameSite=Strict`: una navegación desde un enlace externo llega sin cookie, pero el `fetch` de refresco que hace la SPA desde su propio origen sí la lleva, así que no rompe el arranque de la SPA (el costo que ADR-0005 documenta es para el portal).

Entorno local: `Secure` exige HTTPS. Chrome y Firefox aceptan cookies `Secure` en `http://localhost`; Safari no (no verificado). Un indicador `secure=false` por perfil debe ser imposible en producción (guardia de arranque).

### 4.2 CSRF de doble envío con `Origin`

| Enfoque | Ventajas | Costos |
|---|---|---|
| A. Filtro propio (en el estilo de `AccessTokenAuthenticationFilter`: sin bean, construido con `new`) con matcher de rutas que leen la cookie | Control total del vínculo HMAC; no depende de la semántica de `CsrfFilter`; mismas pruebas de ataque que el filtro de tokens | Código de seguridad propio |
| B. `CsrfFilter` de Spring con `CsrfTokenRepository` y `CsrfTokenRequestHandler` propios | Marco probado; `CsrfException` ya va al manejador de acceso denegado (`403`) | El repositorio perezoso, el enmascarado XOR y el vínculo HMAC obligan a sobrescribir buena parte; riesgo de configurar mal |
| C. Doble envío sin HMAC (solo igualdad cookie-cabecera) | Simple | Contradice `docs/03` §8.3 (fijación desde subdominio) |

Recomendación: A, con la comparación en tiempo constante y el HMAC calculado en `shared.security` (por la regla de confinamiento). Diseño:

- **Token:** `<aleatorio de 32 bytes en base64url>.<HMAC-SHA256(clave, aleatorio || SHA-256(texto del refresco))>`. Vincular al hash del refresco permite verificar sin consultar la base de datos; se reemite en cada rotación (login, MFA, refresco), junto con la cookie de sesión. Vincular al `sid` exigiría una lectura de la familia antes del filtro, o un token de acceso que el refresco no trae.
- **Cookie CSRF:** `__Host-confia_csrf`, sin `HttpOnly`, `Secure`, `Path=/`, sin `Domain`, `SameSite=Strict`, sin `Max-Age`.
- **Cabecera:** `X-CSRF-Token`. **Alcance:** el HMAC se exige en las rutas que leen la cookie de sesión (refresco, cierre actual). `Origin` se exige en todo método no seguro de la cadena administrativa, contra una lista nueva `confia.web.allowed-origins` validada al arrancar (vacía = ninguna petición mutadora pasa). La falta de `Origin` en un método no seguro se rechaza (`docs/03` §8.3).
- **Clave:** secreto nuevo `confia.security.csrf.key` (variable de entorno, leída como los demás secretos de `IdentityConfiguration`: falla al arrancar sin repetir el valor). Ausente en el portal y el trabajador.
- **Inicio de sesión (CSRF previo a la sesión):** no hay cookie que enviar. La defensa es `Origin` estricto, `Content-Type: application/json` obligatorio y `SameSite=Strict` de las cookies que emite. Un token CSRF anónimo previo (`GET`) es la alternativa más fuerte y más cara (DA-4).
- **Códigos:** `403 csrf-invalid` (no distingue entre «falta», «no coincide» y «origen no permitido»).

### 4.3 Dónde se sitúa el limitador por IP de H1 frente al retroceso por cuenta

| Capa | Ubicación | Qué ve | Efecto |
|---|---|---|---|
| Limitador por IP (`InMemoryRateLimiter`) | Interceptor MVC, antes del controlador, sin transacción ni Argon2id | Dirección del cliente resuelta por proxies de confianza | `429` + `Retry-After`; tabla acotada falla cerrada con `503` |
| Retroceso por cuenta (`identity_login_backoff`) | Dentro de la transacción del caso de uso (`claim`/`save`) | Huella HMAC del identificador | Calcula el retardo; el borde lo materializa después de confirmar; no deniega |
| Materializador | Borde, tras confirmar | `Delayed` | Espera en hilo virtual con semáforo |

Orden dentro de una petición: filtro de cabeceras, filtro de origen de la petición, **verificación de `Origin`/CSRF (una petición rechazada aquí no consume cupo)**, filtro de token, interceptor del limitador, controlador (materializador -> caso de uso -> `recordFailure`). Los fallos que cuentan en la capa 2 son los `Rejected`; los resultados `SecondFactorRequired` y `SecondFactorEnrollmentRequired` no cuentan (la credencial era correcta). La capa por IP es la única cota real de tasa (`docs/03` §4.4: el retardo por cuenta no es un limitador); el efecto sobre una oficina detrás de NAT se trata en riesgos.

### 4.4 Inscripción de MFA y elevación de privilegio (step-up)

El token restringido no es de un solo uso: vive 300 s y no hay almacén de `jti`. Consecuencias: la inscripción debe ser segura ante repetición (hoy no lo es), y la verificación de TOTP ya lo es (contador anti-repetición).

| Opción de inscripción | Ventajas | Costos |
|---|---|---|
| a) Un paso (como está) con `409 mfa-already-enrolled` | Sin cambios de dominio | Bloqueo si la respuesta se pierde o el usuario no escanea; el restablecimiento exige un código del secreto |
| b) Dos pasos: secreto pendiente + confirmación con un primer código | Cierra el bloqueo; reutiliza `VerifyTotpCode` | Columna o tabla nueva sobre estructura archivada; cambia `AuthenticateWithPassword`, la definición de «MFA activa» del restablecimiento y sus requisitos (modifica especificaciones archivadas) |
| c) Re-inscribir mientras `last_accepted_counter = -1` (nunca verificada) | Sin migración (`V6` concede `UPDATE`); cierra el bloqueo | `AuthenticateWithPassword` debe devolver `EnrollmentRequired` para una credencial nunca verificada; los códigos de recuperación previos deben quedar inutilizados (marcar como usados); definir «MFA activa» como secreto verificado (modifica el restablecimiento). No verificado: que no exista un disparador que prohíba actualizar `encrypted_secret` |

**Step-up y MFA voluntaria.** El claim `amr` ya distingue `[pwd]` de `[pwd, otp]` y la familia lo fija al crearse (`second_factor_verified` no es actualizable). Elevar una sesión `[pwd]` exigiría una familia nueva. La política de qué rutas exigen `otp` es del cambio 8 («cobertura de MFA por ruta»). En S2, MFA solo ocurre al iniciar sesión (cuentas con `mfa_required`). La inscripción y la baja voluntarias con sesión completa, y cualquier step-up, se difieren al cambio 8 (DA-12). Al completar una inscripción se revocan las demás familias vivas con motivo `mfa_change` (O10).

### 4.5 Cierre de sesión: una familia o todas

| Variante | Autenticación | Efecto | Notas |
|---|---|---|---|
| Cierre actual por cookie | Cookie + CSRF + `Origin` | Revoca la familia a la que pertenece el refresco presentado (aunque ya esté consumido), motivo `logout`; borra cookies | Funciona con el acceso vencido; uniforme (`204`) ante refresco desconocido; requiere puerto nuevo «familia por hash de token» |
| Cierre actual por `Bearer` | Acceso `LIVE_SESSION` | Revoca la familia `sid` | No necesita CSRF; falla si el acceso venció (la SPA refresca antes) |
| Cerrar todas | `Bearer` `LIVE_SESSION` | Revoca todas las familias vivas de la cuenta (incluida la actual), motivo `logout` | Reutiliza `AccountSessionRevoker` con motivo nuevo |
| Cerrar las demás | `Bearer` | Todas salvo `sid` | Es la semántica del cambio de contraseña/MFA de ADR-0005; no hay productor en S2 salvo `mfa_change` |

Orden de bloqueos: cuenta, luego familias por `id` (el revocador ya lo hace); el cierre por familia toma solo la familia, igual que la rotación.

### 4.6 Vinculación por agente de usuario e IP

La familia guarda la huella del agente de creación y `ip_first_seen` (inmutables); cada token consumido guarda la huella del agente que lo presentó. «Cambian ambos al refrescar» podría compararse con los valores de creación. Falsos positivos probables: roaming de IP en portátiles (poco en la red institucional de personal), actualización de navegador en una sesión de 12 horas (cambia el agente, pero no la IP). Aplicarla exige IP en `RotateRefreshTokenCommand`, un motivo de revocación nuevo (`binding_changed`) en `V9` y la decisión de si es rechazo con revocación o solo con aviso (el aviso depende del cambio 14) (DA-8).

## 5. Decisiones para el propietario

Cada una lleva opciones y costos. **No se decide ninguna aquí.**

**DA-1. Estrategia de entrega y partición.** La preflight eligió `single-pr`. Con 6 600 a 9 900 líneas efectivas pronosticadas, un solo PR contradice `CLAUDE.md` y `docs/15` §3 (800 líneas efectivas; la preflight declara además 400, distinto del proyecto).
- *a)* Un cambio, cadena de PR apilados sobre `main` (`stacked-to-main`), con la regla permanente «partir solo si hace falta, sin preguntar». Precedente: S1 (15 PR).
- *b)* Partir S2 en dos cambios (por ejemplo, borde y login; MFA, refresco y cierre) si `tasks.md` supera 15 tareas.
- *c)* Un solo PR. No viable.
- **Recomendación: *a)*, con la decisión de partir por tareas (b) tomada en la fase de tareas si superan 15.**

**DA-2. Cookie de sesión: ámbito y vida.** (4.1)
- *a)* `Path=/api/v1/auth/sessions`, cookie de sesión del navegador (sin `Max-Age`).
- *b)* `Path=/api/v1/auth`, persistente hasta el vencimiento del refresco.
- *c)* `Path=/`.
- **Recomendación: *a)*.** Estaciones de caja compartidas y superficie CSRF mínima. El costo es volver a iniciar sesión al cerrar el navegador.

**DA-3. Respuesta del inicio de sesión cuando falta el segundo factor.** (3.2)
- *a)* `200` con `status` discriminador y token restringido en el cuerpo.
- *b)* `202 Accepted` con el mismo cuerpo.
- *c)* `401` con código `second-factor-required` y el token en el cuerpo.
- **Recomendación: *a)*.** La credencial fue correcta, no es un fallo; el `401` mezclaría estados y los contadores de fallos de los clientes.

**DA-4. Alcance de CSRF y de `Origin`.** (4.2)
- *a)* `Origin` en todo método no seguro de la cadena administrativa; HMAC de doble envío solo en rutas que leen la cookie; el inicio de sesión protegido por `Origin` estricto y `Content-Type` JSON.
- *b)* Además, token CSRF anónimo previo (`GET /api/v1/auth/csrf`) para el inicio de sesión.
- *c)* CSRF de doble envío en todo método no seguro (lectura literal de `docs/03` §8.3).
- **Recomendación: *a)*.** Lo que el navegador envía solo (cookie) es lo que se protege; las rutas `Bearer` no son vulnerables. *b)* añade una ruta y un estado para cerrar el CSRF de inicio de sesión, que `Origin` y `SameSite=Strict` ya cubren en navegadores vigentes. Costo de *a)*: clientes no navegador deben enviar un `Origin` permitido (aceptable hasta la app móvil).

**DA-5. Semántica del cierre de sesión.** (4.5)
- *a)* Actual por cookie (funciona con el acceso vencido) y «cerrar todas» por `Bearer`, incluyendo la actual.
- *b)* Actual por `Bearer` y todas por `Bearer`.
- *c)* *a)* más «cerrar las demás».
- **Recomendación: *a)*.** Un usuario con el acceso vencido debe poder terminar su sesión; el cierre forzado por CSRF es de impacto bajo y está cubierto por el mecanismo del refresco.

**DA-6. Vista de sesiones activas.** ADR-0005 y `docs/ui-ux/04` la prevén (`GET`/`DELETE` de sesiones). `V8` no guarda etiqueta de dispositivo (solo huella hash del agente y la IP inicial): mostrar «dispositivo» exige columna nueva y decidir qué dato personal se conserva (`docs/08`).
- *a)* Incluirla en S2 (columna `device_label` en `V9`, dos rutas, pruebas).
- *b)* Diferirla a la primera pantalla de `apps/admin-web`, con su propio cambio.
- **Recomendación: *b)*.** Sin pantalla que la consuma, el contrato se fijaría a ciegas y S2 ya es grande.

**DA-7. Inscripción de MFA.** (4.4)
- *a)* Un paso, con `409` si ya existe.
- *b)* Dos pasos con confirmación.
- *c)* Re-inscripción mientras la credencial nunca se verificó.
- **Recomendación: *c)*, sujeta a comprobar que `V6` no tiene un disparador que impida actualizar el secreto; si lo tiene, *b)*.** Evita el bloqueo sin migración sobre una tabla archivada. *a)* deja la cuenta sin salida hasta el cambio 8 si una respuesta se pierde.

**DA-8. Vinculación por agente e IP.** (4.6)
- *a)* Solo registrar (sin cambio respecto de S1) y emitir un evento de auditoría de discrepancia.
- *b)* Si cambian ambos respecto de la creación, rechazar el refresco y revocar la familia (`binding_changed`).
- *c)* *b)* con aviso al titular (depende del cambio 14).
- **Recomendación: *b)*.** Es lo que `docs/03` §4.5 exige («exige reautenticación»); en la red institucional el roaming de IP es raro y el falso positivo cuesta un inicio de sesión. Se declara el límite: CGNAT o cambio de red más actualización de navegador en la misma sesión.

**DA-9. Alcance del control por IP de H1.** (sección 2)
- *a)* Capas 1 y 2 del limitador en memoria (equivalente aceptado por D2) y nada más.
- *b)* *a)* más la fila «5 cuentas distintas en 10 minutos» como **señal** (evento `WARN` estructurado y asiento de auditoría), sin bloqueo.
- *c)* *a)* más esa fila con el bloqueo de 1 hora de §4.4.
- **Recomendación: *b)*.** El bloqueo de 1 hora sobre una IP compartida (oficina detrás de NAT, o todo el tráfico del proxy mientras la lista de confianza esté vacía) es una autodenegación; el bloqueo real espera a Redis y a la lista de proxies (cambio 11).

**DA-10. Límites de tasa de los endpoints sin fila en `docs/03` §10.** Hoy solo existe «Administración, inicio de sesión: 10 por minuto por IP». Las cifras de «Verificación de MFA» (20 por hora por IP; 5 por 15 minutos por cuenta) son del portal. Aplicarlas tal cual a una oficina con 50 cajeros detrás de una IP en el arranque del turno los bloquearía.
- *a)* Valores propios por endpoint definidos en la propuesta.
- *b)* Tres políticas: `admin-login` (existente), `admin-mfa` (verificación e inscripción), `admin-session` (refresco y cierres). Valores de partida a validar: `admin-mfa` 20 peticiones por minuto por IP y umbral de fallos 10 en 10 minutos; `admin-session` 120 por minuto por IP.
- *c)* Solo login y diferir el resto.
- **Recomendación: *b)*,** con los valores en configuración (`confia.web.rate-limit.<política>.*`) y la nota fechada en `docs/03` §10. Exige generalizar `RateLimitProperties`.

**DA-11. Techo de la espera materializada (O2).** `BackoffPolicy.CAP` es 900 s; la respuesta HTTP se retiene hasta esa duración. nginx (`proxy_read_timeout`, 60 s por omisión) y Cloudflare (corte por omisión de 100 s en planes no Enterprise) pueden cortar antes; ambos valores no se verificaron aquí. Cada espera retiene un permiso del semáforo (200 por omisión), así que un atacante que empuja el contador de una cuenta objetivo puede agotar los permisos y producir `503` a todo inicio de sesión.
- *a)* Dejar 900 s y documentarlo.
- *b)* Tope configurable en `Delayed` (valor de partida 30 s). El contador y la auditoría conservan el retardo de la política; solo se acota la espera materializada.
- *c)* Sobre el tope, `429` uniforme con `Retry-After` (contradice «el retroceso demora, no deniega»).
- **Recomendación: *b)*.** La cota real de tasa es la capa por IP, no la espera (quien cierra la conexión no espera). Además hay que alinear dos textos de interfaz: `docs/ui-ux/06` habla de bloqueo a los 5 intentos y de «tiempo restante», y el catálogo de `docs/ui-ux/04` tiene `mfa-attempts-exceeded` (429); ninguno corresponde a un retardo sin rechazo.

**DA-12. Alcance de MFA en S2: inscripción y baja voluntarias, step-up.** (4.4)
- *a)* Solo MFA al iniciar sesión (verificación e inscripción obligatorias); revocar las demás familias con `mfa_change` al completar una inscripción; voluntaria, baja y step-up al cambio 8.
- *b)* Incluir inscripción voluntaria con sesión completa.
- *c)* Incluir además baja y step-up.
- **Recomendación: *a)*.** `mfa_required` aún no se deriva del rol (cambio 8, D4), y la política de qué rutas exigen `otp` también es suya. *b)* y *c)* fijarían contratos sin la matriz.

**DA-13. Serializador común del JSON de auditoría (F1c).** Sitios afectados: al menos 16 (sección 2).
- *a)* Constructor tipado en `shared.audit`, usado por todos los sitios nuevos y migrando los existentes en una rebanada mecánica protegida por las pruebas actuales.
- *b)* Solo para los asientos nuevos de S2.
- *c)* Diferirlo.
- **Recomendación: *a)*.** Cuesta unas 350 líneas efectivas y evita que S2 agregue sitios nuevos al problema. Se verifica que los bytes escritos sean iguales (la cadena de auditoría no cambia).

**DA-14. Nombres de rutas y códigos de error.** (3.1 y 3.3)
- *a)* Estilo de recursos donde es natural (`POST /auth/sessions`, `DELETE /auth/sessions/current`, `POST /auth/mfa/verifications`, `POST /auth/mfa/enrollments`) y una subruta de acción solo para el refresco (`POST /auth/sessions/refresh`).
- *b)* Todo en estilo de acciones (`/auth/login`, `/auth/refresh`, `/auth/logout`).
- *c)* Todo en estilo de recursos estricto (`POST /auth/sessions/current/tokens` para refrescar).
- Códigos: `authentication-failed`, `second-factor-invalid`, `session-expired`, `csrf-invalid`, `mfa-already-enrolled`; textos es-HN en el catálogo.
- **Recomendación: *a)* y los cinco códigos.** `session-expired` ya está catalogado para la interfaz; separar `second-factor-invalid` de `authentication-failed` no es un oráculo porque solo se llega a él con la contraseña correcta.

**DA-15. Ventana de primera inscripción por quien conoce la contraseña.** Una cuenta con `mfa_required` y sin secreto entrega un token restringido de inscripción a quien presente la contraseña correcta: quien la conozca primero fija el segundo factor. Es consecuencia del diseño del desenlace `SecondFactorEnrollmentRequired`.
- *a)* Aceptar el riesgo, escribirlo y auditar la inscripción (evento ya existente `identity.mfa.enrolled`).
- *b)* Exigir un código de inscripción entregado fuera de banda (depende del cambio 8, administración de usuarios, y del cambio 14, correo).
- **Recomendación: *a)*.** El mismo riesgo aceptado ya está escrito para el restablecimiento de contraseñas sin MFA activa; se revisa en el cambio 8.

## 6. Pronóstico de tamaño y rebanadas

Líneas efectivas de autor (código, pruebas y documentación; sin `openspec/` ni código generado). Factor central 2,2 del historial de S1 (nominal 4 450, realizado de 2 a 3 veces) y 4a (7,97 veces).

| # | Rebanada | Contenido | Nominal | Realista |
|---|---|---|---|---|
| 1 | `csrf-and-origin-protection` | Filtro CSRF + `Origin`, HMAC en `shared.security`, clave y guardia de arranque, `confia.web.allowed-origins`, `csrf-invalid`, matcher, prueba de la condición 3 sobre un arnés con cookie | 330 | 730 |
| 2 | `edge-policies-and-delay-cap` | Generalización de `RateLimitProperties` y tres políticas, tope de `Delayed` (O2), `SessionCookies`, sonda S-5 de Jackson | 280 | 620 |
| 3 | `session-revocation-v9` | `V9` (motivos `logout`, `mfa_change`, `binding_changed`), puerto por familia y por hash, casos de uso de cierre, F1a, F1b | 400 | 880 (2 rebanadas) |
| 4 | `login-endpoint` (cierra H1) | Controlador, DTO, validación, `authentication-failed`, limitador y materializador aplicados, emisión de sesión y de token restringido, cookies, O6, `PublicEndpoints`, OpenAPI | 600 | 1 320 (2 rebanadas) |
| 5 | `second-factor-endpoints` | Clase de ruta restringida, principal propio, verificación (TOTP y código de recuperación), inscripción (DA-7), O10 | 650 | 1 430 (2 rebanadas) |
| 6 | `refresh-and-logout-endpoints` | Refresco con cookie y CSRF, vinculación (DA-8), cierres, borrado de cookies | 550 | 1 210 (2 rebanadas) |
| 7 | `audit-json-serializer` | Constructor tipado y migración de los sitios (DA-13) | 150 | 330 |
| 8 | `closing-and-follow-ups` | F2, F3, puerta de tiempo del inicio de sesión (200 + 200), inversiones de WE29 a WE31 e inventarios, instantánea OpenAPI y `packages/contracts`, notas en `docs/03` (§4.4, §4.5, §8.1, §8.3, §10), `docs/05`, `docs/ui-ux/04` y `foundations-plan`, ADR-0005 | 350 | 770 |
| | **Total** | | **3 310** | **7 290** (rango 6 600 a 9 900) |

**Órdenes duros:** 1 antes de cualquier PR que emita `Set-Cookie` (condición 3); 2 antes de 4; 3 antes de 6 (el cierre necesita los motivos y los puertos) y de 5 si se revoca por `mfa_change`; 4 antes de 5 (el login emite el token restringido) y de 6; la rebanada 4 con cookie solo después de 1. **Con costuras:** 10 a 14 PR de 800 como máximo, y 12 a 16 tareas de `tasks.md`; si pasan de 15 se aplica DA-1 *b)*.

**`verify` con puerta de mutación:** 13 min 35 s en S1; 8 minutos es el presupuesto del `README` de `apps/api` para `verify` sin mutación. La puerta de tiempo del inicio de sesión añade 400 verificaciones Argon2id.

## 7. Riesgos

**Seguridad**
- **Token restringido tomado por una sesión completa.** Si el principal de las rutas restringidas fuera `AuthenticatedActor`, `TokenCurrentInstitutionProvider` y `SessionValidity` lo tratarían como sesión. Mitigación: tipo distinto, `ScopedValue` distinto, prueba de que un token restringido nunca puebla `AuthenticatedActor.current()`.
- **Ventana de primera inscripción (DA-15)** y **bloqueo por inscripción perdida (DA-7).**
- **Códigos de recuperación sin retroceso propio.** Solo los frena el limitador por IP. Considerar que los fallos de código de recuperación sumen al contador de `identity_mfa_totp_backoff` (cambio de `ConsumeRecoveryCode`).
- **Clave de CSRF nueva** en variable de entorno (mismo riesgo declarado para las claves de firma hasta el cambio 11); rotarla invalida los CSRF vigentes, que se reemiten en el siguiente refresco.
- **Enumeración:** `429` es por IP y no revela cuentas. La respuesta de `401 authentication-failed` debe ser idéntica (cuerpo, estado, cabeceras) para cuenta existente e inexistente, también con retardo cero.
- **Rechazo de propiedades desconocidas** de Jackson 3 sin sonda (S-5): la primera prueba en rojo del PR del login.
- **Cookies en desarrollo local** sin HTTPS.

**Disponibilidad**
- **Autodenegación por IP compartida:** una oficina detrás de NAT comparte 10 inicios de sesión por minuto y el umbral de fallos de la capa 2; con `trusted-proxies` vacío, todo el tráfico detrás de nginx comparte un cubo (cambio 11).
- **Agotamiento del semáforo del materializador** (DA-11).
- **Refresco concurrente desde dos pestañas:** la ventana de gracia devuelve el mismo refresco; las dos pestañas reescriben las mismas cookies. La cookie CSRF debe reemitirse con el refresco de gracia (mismo vínculo HMAC) para no desincronizar.
- **Cierre frente a rotación:** ambos toman el bloqueo de la familia; el cierre por hash debe localizar la familia sin bloquear antes que la rotación.

**Entrega y proceso**
- Subestimación de tamaño (historial: 2,2 a 7,97 veces).
- Presupuesto de `verify` (5,5 min sin mutación; 13,6 min con ella).
- Contradicciones documentales (sección 0, punto 5) y la diferencia 400/800 líneas entre la preflight y el proyecto.
- Ausencia de `apps/admin-web`: sin prueba de navegador de los atributos de cookie; las pruebas verifican `Set-Cookie` a nivel de servidor.
- Dependencias de otros cambios: cambio 9 (purga de `identity_login_backoff` y de familias; sin él las tablas crecen), cambio 11 (Redis, proxies de confianza, tiempos de proxy), cambio 14 (avisos de reutilización y de vinculación), cambio 8 (rol -> `mfa_required`, baja de MFA, step-up).

## 8. Recomendación y preparación para la propuesta

- La arquitectura de sesión está fijada por ADR-0005 y por las decisiones de S1; este cambio es de entrega, no de arquitectura. El camino recomendado: cadena de PR apilados en el orden de la sección 6, con CSRF y `Origin` primero (condición 3), políticas y tope de espera después, revocación `V9`, inicio de sesión (H1), segundo factor, refresco y cierres, serializador de auditoría y cierre documental.
- Decisiones que bloquean la propuesta: DA-1, DA-2, DA-3, DA-4, DA-5, DA-7 y DA-12. El resto (DA-6, DA-8 a DA-11, DA-13 a DA-15) se pueden resolver en la propuesta o el diseño con respuesta del propietario.
- Sondas recomendadas antes del diseño: `FAIL_ON_UNKNOWN_PROPERTIES` de Jackson 3 en Spring Boot 4; disparadores de `V6` sobre `identity_mfa_totp_credential`; comportamiento de `Secure`/`__Host-` en `http://localhost` en los navegadores usados; tiempos de corte de nginx y Cloudflare frente a un retardo largo; mediciones de `verify`.
- **Listo para propuesta: Sí, con condiciones.** Pedir al propietario las decisiones bloqueantes antes de redactarla.

**Nota (2026-10-20, decisiones del propietario).** El propietario acepta las recomendaciones de las siete decisiones que bloqueaban la propuesta:

| Decisión | Opción |
|---|---|
| DA-1 | *a)*, cadena de PR apilados con la regla «partir solo si hace falta» |
| DA-2 | *a)*, `Path=/api/v1/auth/sessions` y cookie de sesión del navegador |
| DA-3 | *a)*, `200` con `status` y token restringido en el cuerpo |
| DA-4 | *a)*, `Origin` en todo método no seguro y HMAC de doble envío solo en rutas que leen la cookie |
| DA-5 | *a)*, cierre actual por cookie y «cerrar todas» por `Bearer` |
| DA-7 | *c)*, re-inscripción mientras la credencial nunca se verificó |
| DA-12 | *a)*, MFA solo al iniciar sesión |

Para DA-1, la partición en dos cambios se decide en la fase de tareas si superan 15. Para DA-7, la condición queda comprobada: `V6__add_mfa_required_and_create_crypto_mfa_tables.sql` no define ningún disparador sobre `identity_mfa_totp_credential`; la única restricción sobre `encrypted_secret` es `CHECK (encrypted_secret LIKE 'v1:%')`. Las decisiones DA-6, DA-8 a DA-11 y DA-13 a DA-15 quedan abiertas para la propuesta.
