# Diseño: endpoints de sesión, cookie y CSRF (parte 4b, S2)

- **Cambio:** `session-endpoints-cookie-and-csrf` (F0, cambio 7, parte 4b, segundo cambio de tres)
- **Fase:** diseñar
- **Fecha:** 2026-10-20
- **Estado:** pendiente de aprobación del propietario (sección 13)
- **Entradas:** `proposal.md` (aprobada el 2026-10-20, con DA-6 *b*, DA-8 *b*, DA-9 *b*, DA-10 *b*, DA-11 *b*,
  DA-13 *a*, DA-14 *a*, DA-15 *a* y la precisión de DA-7 *c*), `exploration.md` (DA-1 a DA-5, DA-7 y DA-12),
  el diseño archivado `openspec/changes/archive/2026-10-19-session-tokens-and-web-layer/design.md`,
  ADR-0005, `docs/03-seguridad.md` §4.3 a §4.7 y §8.3, y el código de la rama
  `change/session-endpoints-cookie-and-csrf` en `6a8c61f`. La especificación corre en paralelo; este diseño
  le entrega precisiones en la sección 10.
- **Modo de TDD:** estricto (`openspec/config.yaml`). Ejecutor: `./mvnw verify` en `apps/api`, con JDK 25 y
  Docker.

## 1. Enfoque técnico

S2 conecta al borde HTTP las piezas que S1 dejó probadas y sin llamador. El criterio de ubicación es el de
S1 y no cambia: **lo que el borde necesita para reconocer una credencial vive en `shared`** (W3), **lo que
decide la vida de una sesión vive en `identity`**, y **ningún controlador interpreta un token ni calcula un
retardo**. Tres restricciones de ArchUnit, leídas en el código, fijan la forma del diseño:

1. `WebLayerTokenIsolationTest`: ninguna clase `web` salvo `AccessTokenAuthenticationFilter` y
   `AdminSecurityConfiguration` depende de `shared.security.token`. Por eso **el token restringido se
   verifica en el filtro existente** y **los casos de uso devuelven vistas sin tipos de token**.
2. `LayeredArchitectureTest`: `web` solo accede a `application`. Un controlador no puede tocar
   `PlainRefreshToken` (`identity.domain`), así que las vistas de aplicación llevan texto con
   `toString()` redactado.
3. `SigningAndHashingConfinementTest`: `Mac` y `MessageDigest` solo en `identity` y `shared.security`. El
   HMAC de CSRF vive en `shared.security.csrf`, **fuera** del paquete `token` para que el filtro CSRF de
   `shared.web` pueda usarlo sin ampliar la exención de la regla anterior.

```
petición
 ─► SecurityHeadersFilter (servlet)                  cabeceras base, no-store
 ─► RequestContextFilter (servlet)                   requestId, RequestOrigin (IP, agente) en ScopedValue
 ─► FilterChainProxy (cadena administrativa)
      CsrfOriginFilter            (shared.web.csrf, nuevo, sin bean)
        método no seguro: Origin ∈ allowed-origins, si no 403 csrf-invalid
        ruta de cookie con cookie de sesión presente: doble envío HMAC, si no 403 csrf-invalid
      AccessTokenAuthenticationFilter (ampliado)
        ruta de segundo factor: verifyMfa + purpose  ─► PendingSecondFactor (ScopedValue propio)
        cualquier otra ruta:    verifyAccess (+ LIVE) ─► AuthenticatedActor (como hoy)
      autorización: públicas permitAll │ autenticadas y de segundo factor authenticated │ denyAll
 ─► DispatcherServlet
      RateLimitInterceptor.preHandle   admin-login │ admin-mfa │ admin-session   (429 / 503)
      controlador (identity.web)
        RequiredDelayMaterializer.execute( caso de uso (transacción) ─► recordFailure si falla )
        espera min(retardo, tope)  ─►  SessionCookies (Set-Cookie)  ─►  DTO
```

Una petición rechazada por `Origin` o CSRF no llega al limitador, no abre transacción y no ejecuta
Argon2id. El limitador corre antes del controlador y, por tanto, antes de la transacción y de Argon2id (H1).

## 2. Evidencia obtenida antes de esta fase

### 2.1 Sondas

Las sondas se hicieron por lectura de código y documentación. Las que exigen ejecución se convierten en la
**primera prueba en rojo** de su rebanada; si el resultado real difiere del esperado, la rebanada se
detiene y este diseño se corrige con una nota fechada.

| Sonda | Resultado de la lectura | Consecuencia en el diseño |
|---|---|---|
| S-5, `FAIL_ON_UNKNOWN_PROPERTIES` de Jackson 3 en Spring Boot 4.1.1 | **No hay ninguna configuración `spring.jackson` en el repositorio** (`application*.yml`). Las clases que analizan JSON propio (`CompactJws`, `IdempotentExecutor`, `DefaultAuditChainVerifier`) construyen su `JsonMapper` con opciones explícitas; el convertidor de Spring MVC no tiene ninguna. El valor por omisión del convertidor en Spring Boot 4.1.1 no se puede afirmar sin ejecutar | Decisión 9: se fija de forma explícita y se demuestra con una prueba en rojo en la rebanada 3, antes del primer cuerpo de petición |
| `Secure` y `__Host-` en `http://localhost` | No verificable sin navegador. Comportamiento conocido, **no verificado aquí**: Chromium y Firefox tratan `http://localhost` como origen de confianza y aceptan cookies `Secure` y con prefijo; Safari no. Un prefijo `__Host-` o `__Secure-` **exige** `Secure`: un indicador que apague `Secure` obligaría además a cambiar los nombres de las cookies | Decisión 5: sin indicador; las cookies son siempre `Secure` y con prefijo. Decisión nueva D-N1 para el propietario |
| Tiempos de corte de nginx y Cloudflare | **No existe `infra/` ni ninguna configuración de nginx en el repositorio**; los valores solo aparecen en documentos. Valores por omisión de los productos (no verificados aquí): `proxy_read_timeout` de nginx, 60 s; corte de Cloudflare en planes no Enterprise, 100 s | Decisión 8: tope de partida de 30 s, por debajo de ambos. Los valores reales son del cambio 11 |
| Reglas de ArchUnit que limitan dónde vive el código de cookie y CSRF | W1 (`WebLayerDependencyRulesTest`): `web` sin `infrastructure` ni jOOQ. W2a y W2b (`WebExposedTypesRuleTest`): ningún tipo de `domain` ni de jOOQ expuesto por `web`. W3 (`SharedBoundaryRulesTest`): `shared` solo depende de `shared` y `kernel`. W4 (`BlockingWaitConfinementTest`): ninguna espera fuera del materializador. W5 (`IdempotencyNotInIdentityTest`). `LayeredArchitectureTest`: `web` solo accede a `application`. `WebLayerTokenIsolationTest`: solo el filtro y `AdminSecurityConfiguration` dependen de `shared.security.token`. `SigningAndHashingConfinementTest`: `Mac`, `MessageDigest`, `Signature`, `KeyFactory`, `KeyGenerator` y Bouncy Castle solo en `identity..` y `shared.security..`. `TransactionsOnlyInSharedSecurityTest`: toda transacción por `TransactionRunner`. `WebEdgeScopeExclusionInventoryTest`: prohíbe `jakarta.servlet.http.Cookie` en toda clase, el limitador y el materializador fuera de `shared.web.{ratelimit,delay,edge}` y `shared.security`, y toda regla de ruta distinta de `permitAll`, `authenticated` y `denyAll` | Decisiones 1, 2, 3, 5, 6, 10 y 20 |
| Privilegios de `V6` sobre los códigos de recuperación (confirmada por el propietario) | `GRANT SELECT, INSERT, UPDATE` sobre `identity_mfa_totp_credential` e `identity_mfa_recovery_code` a `confia_admin_app`; sin disparadores; el comentario de `V6` dice «invalidar un código es fijar `used_at`» | Decisión 13: la reinscripción se hace sin migración |
| Privilegios de `V8` para la revocación | `UPDATE (last_used_at, revoked_at, revoked_reason)` sobre la familia | Decisión 16: `V9` no concede nada |

### 2.2 Código leído que condiciona el diseño

- `AccessTokenAuthenticationFilter` solo llama a `verifyAccess`; `verifyMfa` no tiene consumidor.
  `AuthenticatedEndpoints` tiene una sola entrada (`GET /api/v1/auth/sessions/current`, `LIVE_SESSION`).
- `SecurityChains.denyByDefault` deshabilita el `CsrfFilter` de Spring y termina en `denyAll()`. Se
  conserva deshabilitado: el filtro CSRF de S2 es propio (DA-4 *a*).
- `RateLimitInterceptor` es un interceptor de MVC: corre después de la cadena de seguridad y antes del
  controlador. Falla cerrado con `503` si no hay origen o dirección.
- `RateLimitProperties` está fijada a `confia.web.rate-limit.admin-login` con valores por omisión en
  anotaciones; `ThrottlingConfiguration` registra la política y `RateLimiterConfiguration` el limitador
  con el nombre de la política.
- `RequiredDelayMaterializer` toma el permiso antes del caso de uso, exige hilo virtual y ausencia de
  transacción, y espera el retardo completo sin tope.
- `AuthenticateWithPassword.runWithinTransaction`, `IssueSession.runWithinTransaction`,
  `VerifyTotpCode.runWithinTransaction`, `ConsumeRecoveryCode.runWithinTransaction` y
  `EnrollTotpSecondFactor.runWithinTransaction` son de paquete y están pensados para componerse dentro de
  una sola transacción de `identity.application`.
- `IssueSession.runWithinTransaction` bloquea la cuenta (`accounts.lockById`) antes de escribir: orden
  cuenta, después familias. `RotateRefreshToken` solo bloquea la familia. `JooqAccountSessionRevoker`
  bloquea las familias vivas con `ORDER BY id FOR UPDATE`.
- `RotateRefreshToken` ya ofrece lo necesario para el cierre por cookie: `tokens.findByHash` (sin
  bloqueo, devuelve también tokens consumidos) y `families.lockById`. Falta solo una revocación con motivo.
- `RefreshTokenFamilyState` no lleva la huella del agente de creación ni `ip_first_seen`.
  `ClientAddress.canonical()` escribe IPv6 con ocho grupos; PostgreSQL devuelve `INET` comprimido. La
  comparación se hace con `ClientAddress.parseLiteral` sobre el texto leído, nunca por texto.
- `EnrollTotpSecondFactor` inserta sin comprobar existencia. `AuthenticateWithPassword` y
  `ResetPasswordWithToken.mfaActive` tratan como «MFA activa» la mera existencia del secreto.
- El JSON de auditoría se arma por concatenación en **16 sitios de 8 clases** de `identity.application`
  (`AuthenticateWithPassword` 2, `ConsumeRecoveryCode` 1, `IssueSession` 1, `VerifyTotpCode` 1,
  `RequestPasswordReset` 2 constantes, `IssuePasswordResetToken` 2, `ResetPasswordWithToken` 3,
  `RotateRefreshToken` 4, contando `reasonJson` y `rejectionReasonJson` como un sitio cada uno). Los JSON de claims y de
  cabecera de `shared.security.token` no son de auditoría y quedan fuera. `after_value` es `JSONB`, y la
  cadena de hash de `V3` usa `shared_audit_canonical_json(after_value)`.
- `ProblemResponses` fija `WWW-Authenticate` por código: `Bearer` para `authentication-required` y
  `Bearer error="invalid_token"` para `token-invalid` y `token-expired`.

## 3. Decisiones de arquitectura

### Decisión 1 — Paquetes y dirección de las dependencias

| Paquete | Contenido nuevo | ¿Bean? | Rebanada |
|---|---|---|---|
| `com.confia.shared.security.csrf` (nuevo) | `CsrfTokens` (HMAC), `CsrfKeys` (carga y guardia), `CsrfConfiguration` | Sí (`CsrfTokens`, configuración) | 1 |
| `com.confia.shared.web.csrf` (nuevo) | `CsrfOriginFilter`, `AllowedOrigins`, `CookieCredentialRoute` | **No**: el filtro se construye con `new` en `AdminSecurityConfiguration`, como el de tokens | 1 |
| `com.confia.shared.audit` | `AuditJson` | No (utilidad estática) | 2 |
| `com.confia.shared.web.ratelimit` | Propiedades generalizadas, `RateLimitFailures` | Sí (`RateLimitFailures`) | 3 |
| `com.confia.shared.web.delay` | Tope de la espera en `DelayProperties` y en el materializador | — | 3 |
| `com.confia.shared.web.session` (nuevo) | `SessionCookies` | Sí, registrado por `SessionCookieConfiguration` en `shared.web.edge` | 3 |
| `com.confia.shared.security` | `PendingSecondFactor` (principal restringido), `SecondFactorPurpose` | No | 6 |
| `com.confia.shared.web.authentication` | `SecondFactorEndpoint`, `SecondFactorAuthentication` | No | 6 |
| `com.confia.identity.{domain,application,infrastructure}` | Casos de uso de inicio de sesión, segundo factor, refresco y cierres; señal de cinco cuentas; vinculación | Sí (casos de uso y adaptadores) | 4 a 7 |
| `com.confia.identity.web` | `LoginController`, `SecondFactorController`, `SessionRefreshController`, `SessionEndController` y sus DTO | Sí (controladores) | 5 a 7 |

- `shared` no depende de `identity` (W3): `SessionCookies` recibe el texto del refresco como `String`, no un
  tipo de `identity`.
- `identity.web` depende de `identity.application` (vistas y casos de uso), de `shared.security`
  (principales), de `shared.web.session`, `shared.web.ratelimit`, `shared.web.delay` y `shared.web.problem`.
  Nunca de `shared.security.token` ni de `identity.domain`.

**Descartado:** el HMAC en `shared.security.token`: obligaría a ampliar la exención de
`WebLayerTokenIsolationTest` a una tercera clase, y la regla existe precisamente para que solo el filtro
de autenticación toque ese paquete. **Descartado:** el HMAC en `shared.web`: lo prohíbe
`SigningAndHashingConfinementTest`.

### Decisión 2 — Filtro CSRF y `Origin`: posición y comportamiento (condición 3, DA-4 *a*)

**Posición.** `AdminSecurityConfiguration` añade primero el filtro de tokens (como hoy, antes de
`AnonymousAuthenticationFilter`) y después `addFilterBefore(csrfOriginFilter,
AccessTokenAuthenticationFilter.class)`. Queda así después de `RequestContextFilter` (que es un filtro de
servlet anterior a toda la cadena) y antes del filtro de tokens, de la autorización y del interceptor del
limitador. Una prueba lee `SecurityFilterChain.getFilters()` del contexto real y afirma el índice relativo
de los dos filtros: el orden no se supone, se prueba.

**Reglas, en este orden; la primera que falla decide y responde `403 csrf-invalid`, escrito con
`ProblemResponses.write` sin seguir la cadena:**

| Paso | Condición | Alcance |
|---|---|---|
| 1 | Método seguro (`GET`, `HEAD`, `OPTIONS`, `TRACE`) | Pasa sin comprobación |
| 2 | Exactamente una cabecera `Origin`, cuyo valor pertenece a `AllowedOrigins` por igualdad exacta | Todo método no seguro de la cadena administrativa. Falta, `null`, repetida o no permitida: rechazo |
| 3 | La petición coincide con una `CookieCredentialRoute` (`POST /api/v1/auth/sessions/refresh`, `DELETE /api/v1/auth/sessions/current`) **y** trae la cookie de sesión | Doble envío: exactamente una cookie `__Host-confia_csrf`, exactamente una cabecera `X-CSRF-Token`, ambas iguales en tiempo constante, y `CsrfTokens.verify(valor, refresco)` verdadero |
| 4 | Ruta de cookie **sin** cookie de sesión | Pasa: no hay credencial ambiental que proteger. El controlador responde (`401 session-expired` en el refresco, `204` en el cierre) |
| 5 | Cookie de sesión **repetida** (dos cookies con el mismo nombre, por ejemplo por inyección desde un subdominio) | Rechazo en el paso 3: nunca se elige una |

- El paso 4 es una precisión de este diseño (sección 10, P-3): sin cookie de sesión no hay nada que un
  sitio ajeno pueda hacer que el navegador envíe solo, y exigir un token CSRF en ese caso daría `403` al
  cliente que ya cerró sesión.
- `403 csrf-invalid` no distingue falta, discrepancia ni origen no permitido. El filtro no registra nada:
  ni cabecera, ni cookie, ni origen.
- **El inicio de sesión** (`POST /api/v1/auth/sessions`) no es ruta de cookie: lo protegen el paso 2
  (`Origin` estricto), `consumes = application/json` (un formulario HTML entre sitios no puede enviar ese
  tipo sin una petición previa que el mismo origen no autoriza) y `SameSite=Strict` de las cookies que
  emite. Sin token CSRF anónimo previo (DA-4 *a*).
- **Cerrar todas** (`DELETE /api/v1/auth/sessions`) usa `Bearer` y solo pasa el paso 2.
- El filtro recibe la lista de rutas de cookie por constructor; producción usa
  `CookieCredentialRoute.forAdmin()` y los arneses añaden las suyas, como en `AuthenticatedEndpoints`.

**Descartado:** el `CsrfFilter` de Spring con repositorio y manejador propios (exploración 4.2, B): el
repositorio perezoso, el enmascarado XOR y el vínculo HMAC obligan a sobrescribir casi todo, y su
`CsrfException` iría al manejador de acceso denegado, que no conoce el código `csrf-invalid`.
**Descartado:** el filtro después del de tokens: una petición con `Bearer` válido y `Origin` ajeno
verificaría una firma Ed25519 que de todos modos se va a rechazar.

### Decisión 3 — HMAC de CSRF, clave, rotación y guardia de arranque

```java
// com.confia.shared.security.csrf
public final class CsrfTokens {
    public String issue(String refreshToken);                        // "<r>.<mac>", r = 32 bytes
    public boolean verify(String presented, String refreshToken);    // tiempo constante
}
```

- **Formato:** `B64U(r) "." B64U(HMAC-SHA256(k, r || SHA-256(UTF-8(refreshToken))))`, con `r` de 32
  bytes de `SecureRandom`, base64url sin relleno (43 caracteres cada parte). `Digests.sha256` calcula el
  hash del refresco; `Mac` se obtiene por llamada (no es seguro entre hilos).
- **Verificación:** longitud exacta, alfabeto base64url estricto y canónico (la misma regla que
  `Base64Url` de `shared.security.token`, reimplementada aquí sin depender de ese paquete), cálculo del
  HMAC con la clave actual y, si falla, con la anterior; comparación con `MessageDigest.isEqual`. Todo
  rechazo es `false`, sin excepción ni mensaje.
- **Vínculo al hash del refresco** y no al `sid`: se verifica sin leer la base de datos y sin token de
  acceso, que el refresco no trae. Cada emisión, rotación o gracia reemite el token CSRF con el refresco
  que se entrega.
- **Clave:** `confia.security.csrf.key` (variable `CONFIA_SECURITY_CSRF_KEY`), Base64 estándar de al
  menos 32 bytes, obligatoria en administración. **Clave anterior opcional**
  `confia.security.csrf.previous-key`, solo para verificar. Se leen con `Environment.getProperty` (nunca
  `@ConfigurationProperties` ni `@Value`, por el analizador de fallos), igual que las claves de firma; toda
  falla lanza `IllegalStateException` que nombra la propiedad, no repite el valor y no encadena la causa.
  Ambas distintas entre sí.
- **Por qué la clave anterior:** la propuesta afirma que rotar la clave invalida los CSRF vigentes «que se
  reemiten en el siguiente refresco». Sin clave anterior eso no es posible: el refresco mismo exige un CSRF
  válido y respondería `403`. Con la anterior, el siguiente refresco verifica con ella y reemite con la
  nueva; la anterior se retira pasada la vida absoluta de una sesión (12 h). Coste: unas 20 líneas.
- **Guardia de arranque en el portal y el trabajador:** `SigningKeyPlacementGuard` (ya se ejecuta antes
  de crear el contexto en los tres procesos) añade `confia.security.csrf.key` y
  `confia.security.csrf.previous-key` a su lista de propiedades prohibidas fuera de administración.
  `ProcessBeanPolicy` prohíbe `com.confia.shared.security.csrf` en ambos.
- **Pruebas:** `TestProcessArguments` genera la clave en tiempo de ejecución, como la pimienta. Ninguna
  clave en el repositorio.

**Descartado:** derivar la clave CSRF de la pimienta o de la llave maestra: mezclaría custodias y rotaciones
distintas (`docs/03` §7). **Descartado:** comparar solo cookie y cabecera sin HMAC: `docs/03` §8.3 lo
descarta por la fijación desde subdominio.

### Decisión 4 — `confia.web.allowed-origins`

- Nuevo componente `allowedOrigins` en `WebEdgeProperties` (prefijo `confia.web`, variable
  `CONFIA_WEB_ALLOWEDORIGINS`, lista separada por comas, vacía por omisión y sin valor en ningún archivo
  del repositorio salvo `application-local.yml`, que fija `http://localhost:5173`).
- `AllowedOrigins.parse(List<String>)` valida al arrancar y detiene el proceso nombrando la propiedad y la
  posición de la entrada inválida, sin repetir el valor: cada entrada es un origen serializado de RFC 6454
  (`esquema://host[:puerto]`), en minúsculas, sin ruta, sin barra final, sin comodines, sin usuario; el
  esquema es `https`, salvo `http` con host `localhost` o `127.0.0.1`. Entradas repetidas, rechazo.
- **Lista vacía:** el proceso arranca y ninguna petición no segura pasa el paso 2. Es el fallo cerrado
  que pide la propuesta; un despliegue sin la variable no puede iniciar sesión, lo que se detecta en el
  primer intento.
- El portal enlaza las mismas propiedades pero no instala el filtro (no tiene rutas no seguras).

### Decisión 5 — `SessionCookies`: emitir, rotar, borrar y leer

`com.confia.shared.web.session.SessionCookies`, bean del proceso administrativo, única clase de producción
que conoce nombres y atributos de cookie y única que depende de `jakarta.servlet.http.Cookie` (solo para
leer). La escritura usa `org.springframework.http.ResponseCookie`, que serializa `SameSite`.

| Cookie | Atributos |
|---|---|
| `__Secure-confia_admin_sid` | valor: el texto del refresco (43 caracteres); `HttpOnly`; `Secure`; `SameSite=Strict`; `Path=/api/v1/auth/sessions`; sin `Domain`; sin `Max-Age` ni `Expires` (DA-2 *a*) |
| `__Host-confia_csrf` | valor: `CsrfTokens.issue(refresco)`; **sin** `HttpOnly`; `Secure`; `SameSite=Strict`; `Path=/`; sin `Domain`; sin `Max-Age` |

```java
public final class SessionCookies {
    public void issue(HttpServletResponse response, String refreshToken);   // emitir y rotar
    public void clear(HttpServletResponse response);                        // Max-Age=0, mismos Path
    public SessionCookie read(HttpServletRequest request);                  // ABSENT | PRESENT(text) | DUPLICATED
}
```

- **Emitir y rotar son la misma operación:** dos `Set-Cookie`, siempre juntos, con el refresco recibido y
  un CSRF recién calculado para ese refresco. Se usa en el inicio de sesión completo, en la verificación de
  segundo factor, en la rotación fresca y en la gracia.
- **Borrar:** los dos nombres con valor vacío, `Max-Age=0` y los mismos `Path`, `Secure`, `HttpOnly` y
  `SameSite` (un navegador solo sustituye una cookie con el mismo nombre, dominio y ruta). Lo usan todo
  rechazo del refresco, el cierre actual y cerrar todas.
- **Leer:** `DUPLICATED` cuando llegan dos cookies con el nombre de sesión; el filtro CSRF lo rechaza y el
  refresco lo trata como rechazo.
- **Sin indicador para desactivar `Secure`** (sección 2.1). Los nombres con prefijo exigen `Secure`; un
  indicador obligaría a nombres alternativos y a una guardia de producción. El desarrollo local usa
  Chromium o Firefox sobre `http://localhost` (el servidor de Vite actúa como proxy del mismo origen) o
  HTTPS local. Es la decisión D-N1 para el propietario; si la rechaza, la alternativa está escrita allí.
- La lista de rutas de cookie (`CookieCredentialRoute.forAdmin()`) vive en `shared.web.csrf` y comparte con
  `SessionCookies` el prefijo `/api/v1/auth/sessions` como constante única.

**Descartado:** `jakarta.servlet.http.Cookie` para escribir: no expone `SameSite` sin atributos genéricos y
reparte la serialización. **Descartado:** analizar la cabecera `Cookie` a mano: más código de análisis
propio sin ganancia.

### Decisión 6 — Clase de ruta de segundo factor y principal restringido

```java
// com.confia.shared.security
public enum SecondFactorPurpose { VERIFY /* "mfa-verify" */, ENROLL /* "mfa-enroll" */ }
public record PendingSecondFactor(UUID accountId, UUID institutionId, UUID tokenId,
        SecondFactorPurpose purpose, Instant issuedAt, Instant expiresAt) {
    public static final ScopedValue<PendingSecondFactor> CURRENT = ScopedValue.newInstance();
    public static Optional<PendingSecondFactor> current();
    public SecurityContext securityContext(String requestId);   // actorKind "staff"
}
// com.confia.shared.web.authentication
public record SecondFactorEndpoint(HttpMethod method, String pattern,
        Set<SecondFactorPurpose> purposes) { }
```

- `AuthenticatedEndpoints` gana una segunda lista, `secondFactorEndpoints()`. Producción:
  `POST /api/v1/auth/mfa/verifications` con `{VERIFY, ENROLL}` (precisión de DA-7 *c*) y
  `POST /api/v1/auth/mfa/enrollments` con `{ENROLL}`.
- **Filtro:** si la petición coincide con una ruta de segundo factor, usa **solo** `verifyMfa`; un token de
  acceso falla por audiencia (`401 token-invalid`). Un `purpose` fuera de los admitidos por la ruta,
  `401 token-invalid`. Éxito: `SecondFactorAuthentication` (autenticada, sin autoridades) en el contexto de
  Spring y `PendingSecondFactor.CURRENT` ligado durante el resto de la cadena. En cualquier otra ruta, el
  comportamiento actual sin cambios: `verifyAccess`, y el token restringido falla por audiencia.
- Así **un token restringido nunca puebla `AuthenticatedActor.current()`**, y
  `TokenCurrentInstitutionProvider` y `SessionValidity` nunca lo ven: solo leen `AuthenticatedActor`.
- `SecurityChains` autoriza las rutas de segundo factor con `authenticated()`; el inventario de reglas de
  ruta no cambia (`permitAll`, `authenticated`, `denyAll`).
- El filtro sigue siendo la única clase `web` que lee un token; la exención de `WebLayerTokenIsolationTest`
  no crece. El mapeo de `MfaPurpose` (paquete `token`) a `SecondFactorPurpose` ocurre dentro del filtro.

**Descartado:** un segundo filtro para el token restringido: sería una tercera clase exenta de la regla de
aislamiento. **Descartado:** reutilizar `AuthenticatedActor` con un campo `purpose`: cualquier consumidor que
olvidara mirarlo trataría el token restringido como sesión (riesgo principal de la propuesta).

### Decisión 7 — Tres políticas de tasa y registro de fallos

- `RateLimitProperties` pasa a `@ConfigurationProperties(prefix = "confia.web.rate-limit",
  ignoreUnknownFields = false)` con tres componentes anidados del mismo tipo `PolicyValues`:
  `adminLogin`, `adminMfa`, `adminSession` (claves `admin-login`, `admin-mfa`, `admin-session`). Los
  **valores por omisión pasan a `application.yml`**, porque una anotación `@DefaultValue` no puede dar
  valores distintos a tres componentes del mismo tipo. Un componente ausente detiene el arranque nombrando
  la propiedad. La validación y la traducción de nombres de `asProperties` se conservan, con el prefijo de
  cada política.

| Política | Capa 1 | Capa 2 (fallos) | Rutas | Qué cuenta como fallo |
|---|---|---|---|---|
| `admin-login` | 10 por minuto | 10 en 10 min, 1 por minuto, tope 1 h (sin cambios) | `POST /auth/sessions` | Desenlace `Rejected` |
| `admin-mfa` | 20 por minuto | 10 en 10 min, 1 por minuto, tope 1 h | verificación e inscripción | `second-factor-invalid` |
| `admin-session` | 120 por minuto | 30 en 10 min, 1 por minuto, tope 1 h | refresco, cierre actual, cerrar todas | Rechazo del refresco (`session-expired`) |

- `RateLimiterConfiguration` declara tres limitadores con el nombre de su política, y
  `ThrottlingConfiguration` tres beans de política. `RateLimitPolicyCheck` ya impide arrancar con una
  política desconocida.
- **`RateLimitFailures`** (`shared.web.ratelimit`, bean): `record(String policy)` toma el cliente de
  `RequestOrigin.current()` y llama a `recordFailure` del limitador de esa política. Los controladores lo
  usan y nunca nombran el puerto `RateLimiter`. Sin origen o sin dirección no hace nada: el interceptor ya
  habría respondido `503` antes.
- Nota fechada en `docs/03` §10 con las filas de DA-10.

### Decisión 8 — Tope de la espera materializada (O2, DA-11 *b*)

- `DelayProperties` gana `max-wait` (`confia.web.delay.max-wait`, 30 s por omisión, positivo y como máximo
  60 s, que se valida al arrancar).
- `RequiredDelayMaterializer` espera `min(requiredDelay, maxWait)`. El tope vive **en el materializador**,
  único lugar que espera (W4), y no en `Delayed`: `Delayed` sigue llevando el retardo de la política, que es
  el que cuentan el contador y la auditoría (`identity.login.backoff_applied` conserva `delaySeconds`).
- El semáforo no cambia (200 permisos). Con el tope, una espera retiene un permiso como máximo 30 s.

### Decisión 9 — Rechazo de propiedades desconocidas (sonda S-5)

- `application.yml` fija `spring.jackson.deserialization.fail-on-unknown-properties: true` para los
  procesos web. El portal no tiene cuerpos; el efecto es neutro allí.
- **Prueba en rojo antes del controlador** (rebanada 3): un controlador del arnés con un DTO de registro
  recibe `{"a":"x","b":"y"}` con `b` no declarado y debe responder `400 validation-failed`. Si la propiedad
  no la honra el autoconfigurador de Spring Boot 4.1.1, el mismo PR la sustituye por un bean
  `JsonMapperBuilderCustomizer` en `WebEdgeConfiguration` y deja escrito el resultado en una nota fechada.
- Las anotaciones `@JsonIgnoreProperties(ignoreUnknown = false)` no sirven: su valor `false` es el de
  omisión y no fuerza el fallo cuando la opción global está apagada.

### Decisión 10 — Vistas de aplicación sin tipos de token ni de dominio

Los casos de uso nuevos devuelven tipos de `identity.application` que la capa `web` puede leer:

```java
public final class SessionGrant {          // toString() redactado
    public String accessToken();  public Instant accessTokenExpiresAt();
    public String refreshToken(); public Instant sessionExpiresAt(); public Instant idleExpiresAt();
}
public final class SecondFactorChallenge { // toString() redactado
    public enum Kind { VERIFICATION_REQUIRED, ENROLLMENT_REQUIRED }
    public Kind kind(); public String token(); public Instant expiresAt();
}
```

Se construyen a partir de `IssuedSession` y de `AccessToken` dentro de `identity.application`. Nunca son
`record` (llevan secretos, la lección de `ResetPasswordCommand`). `F3`: `SessionSecretsNotObservableTest`
los incluye.

### Decisión 11 — Inicio de sesión (H1)

Caso de uso nuevo `LogIn` en `identity.application`, que compone en **una** transacción
`AuthenticateWithPassword.runWithinTransaction` y, solo si el desenlace es `Authenticated`,
`IssueSession.runWithinTransaction`.

```java
public record LoginCommand(String identifier, String password, ClientAddress clientAddress,
        String userAgent, String requestId) { /* toString redacta password */ }
public record LoginDecision(LoginOutcome outcome, Duration requiredDelay) { }
public sealed interface LoginOutcome {
    record Started(SessionGrant session) implements LoginOutcome { }
    record ChallengeIssued(SecondFactorChallenge challenge) implements LoginOutcome { }
    record Failed() implements LoginOutcome { }
}
```

**Secuencia completa** (diagrama exigido por `openspec/config.yaml` para flujos complejos):

```
cliente        CsrfOriginFilter   RateLimitInterceptor   LoginController     Materializer        LogIn (TransactionRunner)
  │ POST /auth/sessions│                  │                    │                  │                       │
  │──────────────────►│ Origin permitido │                    │                  │                       │
  │                   │─────────────────►│ tryAcquire(IP)     │                  │                       │
  │                   │                  │  429 / 503 ◄───────┤ (fin, sin tx)    │                       │
  │                   │                  │───────────────────►│ @Valid JSON      │                       │
  │                   │                  │                    │─────────────────►│ toma permiso (o 503)  │
  │                   │                  │                    │                  │──────────────────────►│ BEGIN
  │                   │                  │                    │                  │  claim backoff; Argon2id (o señuelo)
  │                   │                  │                    │                  │  Rejected → señal 5 cuentas (DA-9)
  │                   │                  │                    │                  │  Authenticated → IssueSession (familia)
  │                   │                  │                    │                  │  save backoff; auditoría │ COMMIT
  │                   │                  │                    │                  │  firma acceso o token restringido
  │                   │                  │                    │◄─── (supplier) ──│◄──────────────────────│
  │                   │                  │                    │ Failed → RateLimitFailures.record("admin-login")
  │                   │                  │                    │                  │ espera min(retardo, 30 s); libera permiso
  │                   │                  │                    │ Started → SessionCookies.issue; 200 authenticated
  │                   │                  │                    │ ChallengeIssued → 200 second-factor-*, sin cookies
  │◄──────────────────────────────────────────────────────────│ Failed → 401 authentication-failed
```

- **Limitador:** antes de abrir transacción y antes de Argon2id; el caso de uso nunca corre si se rechaza.
- **Materializador:** envuelve el caso de uso; toma el permiso antes y espera después de confirmar.
- **`recordFailure`:** dentro del proveedor del materializador, **después de que el caso de uso confirmó y
  antes de la espera**. Así cuenta aunque la espera se interrumpa o el cliente se vaya. Solo cuenta
  `Failed`; `ChallengeIssued` y `Started` no cuentan (la credencial era correcta).
- **Firma:** acceso o token restringido se firman **después** de confirmar (patrón de `IssueSession`), con
  `AccessTokenIssuer.issueMfa(account, institution, VERIFY | ENROLL)`.
- **Institución:** `LogIn` construye el `SecurityContext` con `LoginInstitutionProvider` (actor vacío,
  `actorKind = "staff"`, el `requestId` del origen), la misma convención que el restablecimiento. La guardia
  de institución de `AuthenticateWithPassword.execute` no se pierde: `LogIn` la repite antes de abrir la
  transacción.
- **O6:** `AuthenticationCommand` gana `clientAddress` y `userAgent` (con su `toString` redactado), que
  `LogIn` pasa también a `IssueSessionCommand`.
- **Uniformidad:** `401 authentication-failed` idéntico para cuenta existente e inexistente (mismo cuerpo
  salvo `traceId`, mismo estado, mismas cabeceras salvo `Date`), también con retardo cero. El desenlace
  interno (`INVALID_PASSWORD`, `ACCOUNT_NOT_FOUND`) solo va a la auditoría, como hoy.
- **DTO de entrada:** `LoginRequest(identifier, password)`, ambos `@NotNull` y con longitud máxima solamente
  (el mínimo de 12 no se impone al entrar, para no filtrar la política); propiedades desconocidas
  rechazadas (decisión 9); `consumes = application/json` (`415` en otro caso, antes del limitador porque lo
  decide el mapeo de rutas).
- **Respuestas `200`:** `{"status":"authenticated","accessToken","accessTokenExpiresAt","sessionExpiresAt",
  "idleExpiresAt"}` con `Set-Cookie` de sesión y CSRF; o `{"status":"second-factor-required" |
  "second-factor-enrollment-required","secondFactorToken","secondFactorTokenExpiresAt"}` sin cookies
  (DA-3 *a*). En OpenAPI, `oneOf` con discriminador `status`.
- `PublicEndpoints.forAdmin` añade `POST /api/v1/auth/sessions`.
- **Inicio de sesión con una cookie anterior presente:** la ruta no lee la cookie (no es ruta de cookie) y
  la sobrescribe al emitir. La familia anterior sigue viva hasta su vencimiento; se declara en riesgos.

**Descartado:** autenticar en una transacción y emitir la sesión en otra: un fallo de emisión dejaría
auditado un inicio de sesión exitoso sin sesión. **Descartado:** `recordFailure` después de la espera: una
espera interrumpida dejaría el fallo sin contar.

### Decisión 12 — Señal de «5 cuentas distintas en 10 minutos» (DA-9 *b*)

- **Puerto** `LoginFailureSpread` en `identity.application`:
  `boolean recordFailure(ClientAddress client, IdentifierFingerprint identifier, Instant now)`, que
  devuelve verdadero solo cuando el número de identificadores distintos fallidos desde esa dirección en los
  últimos 10 minutos **llega** al umbral (5) en esa llamada.
- **Lógica pura** en `identity.domain.FailureSpreadWindow` (objetivo de PIT): conjunto de huellas con su
  último instante, ventana deslizante, umbral; la misma huella repetida no suma (idempotente ante el
  reintento de `TransactionRunner`).
- **Adaptador** `InMemoryLoginFailureSpread` en `identity.infrastructure`: mapa acotado por dirección
  (clave `ClientAddress.rateLimitKey()`, la misma del limitador), con tope de entradas configurable; lleno,
  no registra y no señala (es una señal, no un control; el control es el limitador). Las mismas tres
  limitaciones declaradas del limitador en memoria (por proceso, se pierde al reiniciar, no se comparte).
- **Dónde corre:** dentro de la transacción de `LogIn`, solo con desenlace `Rejected`. Cuando devuelve
  verdadero, en la misma transacción se escribe `identity.login.failure_spread_detected` (entidad
  `identity.client_address`, identificador: la dirección canónica; `afterValue` `{"distinctIdentifiers":5,
  "windowSeconds":600}`), y después de confirmar `LogIn` emite un `WARN` estructurado con el `requestId`
  y el nombre del evento, **sin** dirección ni identificador (ya están en la bitácora).
- Sin bloqueo: el bloqueo de 1 hora es del cambio 11 (DA-9).

**Descartado:** contar desde la bitácora (`shared_audit_log` por `source_ip` y `entity_id`): compartido
entre réplicas, pero exige un índice nuevo sobre una tabla de otro módulo, una consulta en cada fallo y un
puerto de lectura de `shared.audit` desde `identity`. Se reevalúa con Redis en el cambio 11.

### Decisión 13 — Segundo factor: definición de «MFA activa», inscripción y verificación (DA-7 *c*, O10)

**«MFA activa» = secreto verificado.** `TotpCredential.isVerified()` es `lastAcceptedCounter >= 0`. Cambian
dos puntos, en la misma rebanada:

- `AuthenticateWithPassword.outcomeForValidCredentials`: `SecondFactorRequired` solo con secreto
  verificado; sin secreto **o con secreto sin verificar**, `SecondFactorEnrollmentRequired`.
- `ResetPasswordWithToken.mfaActive`: `mfaRequired && credencial verificada`. Una cuenta con secreto sin
  verificar restablece sin segundo factor (el mismo riesgo ya escrito para la cuenta sin secreto).

**Inscripción** (`POST /api/v1/auth/mfa/enrollments`, `purpose = ENROLL`), `EnrollTotpSecondFactor` cambia
a un resultado sellado `Enrolled(secret, codes, replacedUnverified)` | `AlreadyEnrolled`:

1. `accounts.lockById` (orden cuenta → credencial → familias, el mismo que la emisión y el restablecimiento).
2. Sin credencial: inserta, como hoy.
3. Credencial sin verificar: `credentials.replaceUnverifiedSecret(institution, account, cifrado, now)`
   (`UPDATE ... SET encrypted_secret, enrolled_at WHERE ... AND last_accepted_counter = -1`, que debe afectar
   una fila bajo el bloqueo) y `recoveryCodes.invalidateUnused(institution, account, now)` (`UPDATE ... SET
   used_at = now WHERE ... AND used_at IS NULL`, devuelve cuántos). El AAD del secreto no cambia (fila
   `institution:account`), y `encryptForNewValue` cifra con la DEK activa.
4. Credencial verificada: `AlreadyEnrolled` → `409 mfa-already-enrolled`, sin escribir nada salvo el asiento
   `identity.mfa.enrollment_rejected`.
5. Asiento `identity.mfa.enrolled`; con reinscripción, `afterValue`
   `{"replacedUnverified":true,"invalidatedRecoveryCodes":n}`; en la primera inscripción se conserva `null`
   (igualdad con el asiento actual).
6. Respuesta `201` `{"secret":"<base32>","recoveryCodes":[10]}` con `Cache-Control: no-store` (ya global).

**Verificación** (`POST /api/v1/auth/mfa/verifications`), caso de uso nuevo `CompleteSecondFactor`, en una
transacción:

1. `accounts.lockById`; lee la credencial. Sin credencial: rechazo.
2. Según `purpose` y estado:

| `purpose` | Credencial | Pruebas aceptadas | Efecto del éxito |
|---|---|---|---|
| `VERIFY` | verificada | TOTP o código de recuperación | Emite sesión con `amr = [pwd, otp]` |
| `ENROLL` | sin verificar | **solo TOTP** | Activa la credencial (el contador aceptado deja de ser -1), revoca las demás familias con `mfa_change` (O10) y emite sesión |
| `ENROLL` | verificada | ninguna | Rechazo `second-factor-invalid` (la inscripción ya se completó) |
| `VERIFY` | sin verificar | ninguna | Imposible por construcción (el inicio de sesión emite `ENROLL`); rechazo |

3. TOTP: `VerifyTotpCode.runWithinTransaction` (retroceso propio, anti-repetición).
4. Código de recuperación: **sus fallos suman al mismo retroceso de `identity_mfa_totp_backoff`**
   (claim antes, save después, como la verificación TOTP), y después `ConsumeRecoveryCode.runWithinTransaction`.
   Cierra el riesgo «códigos de recuperación sin retroceso propio» en la ruta de inicio de sesión; el
   restablecimiento no cambia (S3).
5. Éxito de primera verificación: `AccountSessionRevoker.revokeAllLive(..., MFA_CHANGE, now)` **antes** de
   `IssueSession.runWithinTransaction`, de modo que la familia nueva nunca se revoca; asiento
   `identity.session.revoked` `{"reason":"mfa_change","families":n}` también con `n = 0`, y
   `identity.mfa.activated`.
6. El retardo calculado viaja en `Delayed` y lo materializa el controlador; `second-factor-invalid` llama a
   `RateLimitFailures.record("admin-mfa")` dentro del proveedor, como el inicio de sesión.

**Por qué un código de recuperación no activa una credencial sin verificar:** la primera verificación
prueba que el usuario tiene el secreto en su aplicación; un código de recuperación solo prueba que vio la
respuesta de la inscripción. Es la decisión D-N3.

### Decisión 14 — Refresco: vinculación (DA-8 *b*), O6 y gracia

- `RotateRefreshTokenCommand` gana `clientAddress` y `requestId`, y `RotateRefreshToken` un método
  `execute(command)` que construye el contexto con `LoginInstitutionProvider` (la guardia actual se
  conserva). Una fachada `RefreshSession` traduce `RefreshOutcome` a `Refreshed(SessionGrant, kind)` |
  `Rejected` para la capa `web`.
- `RefreshTokenFamilyState` gana `creationFingerprint` (`user_agent_fingerprint`) e `ipFirstSeen` (texto de
  `ip_first_seen`, puede ser `null`); `JooqRefreshTokenFamilyRepository.stateQuery` añade ambas columnas.
- **Comparación de dirección** en `identity.application`: `ClientAddress.parseLiteral(ipFirstSeen)` contra
  la dirección del comando, por igualdad de `ClientAddress` (normaliza IPv6 comprimido y mapeado). Si
  cualquiera de las dos falta, la dirección **no cuenta como cambiada** (la regla exige que cambien ambas).
- **`RotationDecision`** recibe `addressChanged` y gana `REJECT_BINDING_CHANGED`. Orden nuevo, fijo:

| Orden | Condición | Decisión | Efecto |
|---|---|---|---|
| 1 | Familia revocada | `REJECT_FAMILY_REVOKED` | Ninguno |
| 2 | Familia vencida o inactiva | `REJECT_FAMILY_EXPIRED` | Vacía sucesores |
| 3 | Token consumido y no apto para gracia | `REUSE_DETECTED` | Revoca con `reuse_detected` |
| 4 | Huella del presentador distinta de la de creación **y** dirección cambiada | `REJECT_BINDING_CHANGED` | Revoca con `binding_changed`, vacía sucesores |
| 5 | Token consumido apto para gracia | `GRACE_REPLAY` | Como hoy |
| 6 | Token vencido | `REJECT_TOKEN_EXPIRED` | Ninguno |
| 7 | En otro caso | `ROTATE` | Como hoy |

  Gracia y reutilización eran particiones disjuntas del token consumido; separarlas por la vinculación no
  cambia ninguno de sus resultados actuales cuando la vinculación no cambió. Un token robado y ya consumido
  sigue siendo reutilización (señal más precisa); la vinculación solo decide cuando el token entregaría
  credenciales (rotación o gracia).
- `RefreshRejection.BINDING_CHANGED` (`auditCode` `binding_changed`); asiento
  `identity.session.binding_changed` con `{"familyRevoked":true}`, sin huella ni dirección.
- **Respuesta:** `Refreshed` → `SessionCookies.issue(response, refresco)` (también en la gracia: mismo
  refresco sucesor, CSRF nuevo ligado a él) y `200` con el DTO de sesión. Todo `Rejected` o cookie
  ausente → `SessionCookies.clear`, `RateLimitFailures.record("admin-session")` y
  `401 session-expired` uniforme. Una cookie de sesión repetida no llega al controlador: el filtro la
  rechaza antes con `403 csrf-invalid` (decisión 2, regla 5; corrección del 2026-10-20 al alinear la
  especificación).

**Descartado:** la vinculación antes de la reutilización: un token robado presentado desde otro equipo y
otra red se auditaría como cambio de vinculación en lugar de reutilización.

### Decisión 15 — Cierre actual por cookie y cerrar todas

**Cierre actual** (`DELETE /api/v1/auth/sessions/current`, cookie + CSRF + `Origin`), caso de uso
`EndCurrentSession`:

1. Texto mal formado o sin token: nada que hacer.
2. `tokens.findByHash` **sin bloqueo** (encuentra también un token consumido) → `families.lockById` (solo la
   familia, como la rotación) → si está viva, `families.revoke(institution, family, LOGOUT, now)` (nuevo,
   condicional a `revoked_at IS NULL`), `tokens.clearSuccessors` y asiento `identity.session.revoked`
   `{"reason":"logout","families":1}` (entidad: la cuenta, como el de H2).
3. Familia ya revocada, vencida o token desconocido: ningún efecto y ningún asiento.
4. El controlador responde `204` en todos los casos, siempre con `SessionCookies.clear`. Funciona con el
   acceso vencido (DA-5 *a*).

El «puerto para localizar una familia por el hash» de la propuesta (alcance 13) **no es un puerto nuevo**: es
la composición de `findByHash` y `lockById`, que ya existen y que la rotación ya usa en ese orden. Lo nuevo
es `RefreshTokenFamilyRepository.revoke(..., SessionRevocationReason, ...)`; `revokeForReuse` se conserva.

**Cerrar todas** (`DELETE /api/v1/auth/sessions`, `Bearer` de acceso, `LIVE_SESSION`, `Origin`), caso de uso
`EndAllSessions(AuthenticatedActor)`: `accounts.lockById` (orden cuenta → familias) y
`AccountSessionRevoker.revokeAllLive(..., LOGOUT, now)`; asiento `{"reason":"logout","families":n}`;
`204` con `SessionCookies.clear`. `AuthenticatedEndpoints.forAdmin` añade la ruta con `LIVE_SESSION`.

### Decisión 16 — Migración `V9`

`V9__extend_identity_refresh_token_family_revocation_reasons.sql`:

```sql
-- S2 (session-endpoints-cookie-and-csrf), design.md decision 16. Widens the closed catalogue of
-- revocation reasons with the three producers this change adds. No grant changes: V8 already grants
-- UPDATE (revoked_at, revoked_reason) to confia_admin_app.
ALTER TABLE identity_refresh_token_family
    DROP CONSTRAINT identity_refresh_token_family_reason_chk;
ALTER TABLE identity_refresh_token_family
    ADD CONSTRAINT identity_refresh_token_family_reason_chk CHECK (revoked_reason IN
        ('reuse_detected', 'password_change', 'logout', 'mfa_change', 'binding_changed'));
```

- `SessionRevocationReason` gana `LOGOUT`, `MFA_CHANGE`, `BINDING_CHANGED`; `RevocationReasonCatalogIT` sigue
  comparando enum y restricción.
- **Ninguna concesión nueva:** la revocación usa las columnas ya concedidas en `V8`; la reinscripción usa el
  `UPDATE` de tabla de `V6` sobre la credencial y los códigos.
- El disparador `revocation_final` de `V8` no cambia: el motivo se fija una vez.
- Sin datos reales: `ADD CONSTRAINT` valida filas inexistentes. Reversión antes de producción: eliminar la
  migración (jOOQ se regenera en la construcción).

### Decisión 17 — Serializador común del JSON de auditoría (F1c, DA-13 *a*)

```java
// com.confia.shared.audit
public final class AuditJson {
    public static Builder object();
    public static final class Builder {
        public Builder text(String name, String value);     // escapado como canonString
        public Builder number(String name, long value);
        public Builder bool(String name, boolean value);
        public Builder instant(String name, Instant value); // Instant.toString()
        public String toJson();                             // miembros en orden de inserción, sin espacios
    }
}
```

- Nombres: `^[a-z][A-Za-z0-9]*$`, sin repetición (los dos fallan con `IllegalArgumentException`), así nunca
  hace falta escapar una clave. Textos: las mismas reglas de escape que
  `CanonicalAuditRowSerializer.canonString` (comillas, barra inversa y el rango C0 con `\u00xx`), extraídas a
  un método de paquete compartido.
- **Igualdad de bytes, demostrada en tres capas:**
  1. `AuditJsonSiteEqualityTest`, parametrizada por sitio: la expresión antigua (copiada literalmente en la
     prueba) y la nueva, sobre los valores de borde de cada sitio (`0`, `1`, `Integer.MAX_VALUE`, cada valor
     de cada enum de motivo, `true` y `false`, un `Instant` con y sin fracción), producen la misma cadena.
     Se escribe en rojo antes de migrar (la clase `AuditJson` no existe).
  2. Las pruebas de integración existentes que leen `after_value` corren sin cambios.
  3. `AuditChainVerifier` verifica la cadena después de la suite.
- **Guardia contra regresión:** `AuditJsonOnlySourceTest` recorre el código fuente de producción y falla si
  encuentra el literal `"{\"` fuera de `shared.security.token` (claims y cabecera JWS) y de `AuditJson`, con
  un fixture que lo contiene.
- Toda entrada nueva de S2 se escribe con `AuditJson` desde el primer commit (rebanada 2 antes de 4 a 7).

### Decisión 18 — Códigos de Problem Details nuevos

| Código | Estado | `WWW-Authenticate` | Título es-HN | Detalle es-HN |
|---|---|---|---|---|
| `authentication-failed` | 401 | `Bearer` | Credenciales inválidas | El identificador o la contraseña no son correctos. Revíselos e inténtelo de nuevo. |
| `second-factor-invalid` | 401 | `Bearer` | Código de verificación no válido | El código ingresado no es válido o ya se usó. Ingrese el código actual de su aplicación de autenticación o un código de recuperación. |
| `session-expired` | 401 | `Bearer` | La sesión terminó | Su sesión terminó o ya no es válida. Inicie sesión de nuevo. |
| `csrf-invalid` | 403 | — | Solicitud rechazada | No se pudo comprobar que la solicitud provenga de la aplicación. Recargue la página e inténtelo de nuevo. |
| `mfa-already-enrolled` | 409 | — | El segundo factor ya está configurado | La cuenta ya tiene un segundo factor activo. Inicie sesión con el código de su aplicación de autenticación. |

- `WWW-Authenticate: Bearer` en los tres `401` nuevos: RFC 9110 lo exige en todo `401`, y el mismo valor para
  los tres evita un oráculo por cabecera. Ninguno lleva `error="invalid_token"`: no se presentó un token de
  acceso inválido.
- Los controladores lanzan una excepción de `shared.web.problem` por código (`ProblemCodeException(code)`) que
  el traductor único responde; las cabeceras `Set-Cookie` añadidas antes al `HttpServletResponse` se
  conservan, y una prueba lo afirma (borrado de cookies en el `401` del refresco).
- `mfa-attempts-exceeded` del catálogo de interfaz no se crea (DA-11: el retroceso demora, no deniega); nota
  fechada en `docs/ui-ux/04`.

### Decisión 19 — Auditoría nueva

| Acción | Entidad | Resultado | `afterValue` |
|---|---|---|---|
| `identity.login.failure_spread_detected` | `identity.client_address` / dirección canónica | `denied` | `{"distinctIdentifiers":5,"windowSeconds":600}` |
| `identity.mfa.enrolled` (reinscripción) | credencial | `success` | `{"replacedUnverified":true,"invalidatedRecoveryCodes":n}` |
| `identity.mfa.enrollment_rejected` | credencial | `denied` | `{"reason":"already_enrolled"}` |
| `identity.mfa.activated` | credencial | `success` | `null` |
| `identity.session.revoked` | cuenta | `success` | `{"reason":"logout"\|"mfa_change","families":n}` |
| `identity.session.binding_changed` | familia | `denied` | `{"familyRevoked":true}` (acción propia, como `identity.session.reuse_detected`; `refresh_rejected` no cambia) |

Todas en la transacción de su efecto, con origen completado por `RequestOriginAuditLogWriter`. Nunca:
contraseña, refresco, su hash, token CSRF, token de acceso o restringido, `jti`, secreto TOTP ni códigos de
recuperación.

### Decisión 20 — ArchUnit, `ProcessBeanPolicy` e inversiones de inventario (O3)

**`ProcessBeanPolicy`**, cada línea en el PR de su `@Import`:

| Proceso | Permitidos nuevos | Prohibidos nuevos |
|---|---|---|
| `admin` | `com.confia.shared.security.csrf` (rebanada 1), `com.confia.shared.web.session` (3) | — |
| `portal` | — | `com.confia.shared.security.csrf`, `com.confia.shared.web.session` («credencial en cookie del dominio administrativo») |
| `worker` | — | los mismos |

**Inversiones**, cada una con su control negativo:

| Prueba | Cambio | Rebanada |
|---|---|---|
| `ProblemCatalogCoverageTest`, `ProblemCodeTest` | Dejan de negar `authentication-failed`; los cinco códigos nuevos con entrada en el catálogo | 1, 5, 6, 7 |
| `WebEdgeScopeExclusionInventoryTest`, cookie | De «ninguna clase usa `Cookie`» a «exactamente `SessionCookies`», con fixture que la usa fuera | 3 |
| `WebEdgeScopeExclusionInventoryTest`, limitador y materializador | Se permite además `com.confia.identity.web` (los controladores con `@RateLimited`, `RateLimitFailures` y el materializador); el fixture de un paquete ajeno sigue detectado | 5 |
| `PublicRouteAllowListTest` y enumerador de rutas | Rutas registradas = `PublicEndpoints ∪ AuthenticatedEndpoints ∪ secondFactorEndpoints` | 5, 6, 7 |
| Requisito «el restringido se rechaza en toda ruta» | Pasa a «se rechaza en toda ruta salvo las de segundo factor, y nunca puebla `AuthenticatedActor`» | 6 |
| Ausencias de `identity` (dimensión IP, vinculación, revocación por MFA, catálogo cerrado de motivos) | Invertidas a sus pruebas positivas | 4, 5, 6, 7 |
| WE29, WE30, WE31 de `web-edge` | Estrechadas a la recuperación (S3) | 8 |
| `WebLayerTokenIsolationTest` | **Sin cambios**: la exención sigue en dos clases; prueba nueva de que los controladores de S2 no dependen del paquete `token` | 5 a 7 |

`LayeredArchitectureTest`: `shared.web.csrf` y `shared.web.session` son capa `Web` y solo usan paquetes sin
capa (`shared.security`); los controladores solo usan `identity.application`.

### Decisión 21 — Documentación: notas fechadas, sin reescribir cuerpos

| Documento | Nota | Rebanada |
|---|---|---|
| `docs/03` §8.3 | Alcance de DA-4 *a*, formato del token, clave anterior, paso 4 de la decisión 2 | 1 |
| `docs/05` | `CONFIA_WEB_ALLOWEDORIGINS`, `CONFIA_SECURITY_CSRF_KEY` y `..._PREVIOUSKEY`; contradicción de `SESSION_COOKIE_DOMAIN` con la prohibición de `Domain` (se retira en una nota) | 1 |
| `docs/03` §10 | Filas `admin-mfa` y `admin-session` (DA-10), tope de espera (DA-11) | 3 |
| `docs/03` §4.4 | Control por IP aplicado al inicio de sesión (H1) y señal de cinco cuentas (DA-9 *b*) | 5 |
| `docs/03` §4.5, ADR-0005 | Cookie, vinculación (DA-8 *b*), revocación por MFA (O10), motivos nuevos, inscripción de dos momentos sin migración (DA-7 *c*), «MFA activa» = verificada | 6, 7 |
| `docs/03` §8.1 | `connect-src 'self'` (mismo origen, DA-4 de S1) | 8 |
| `docs/ui-ux/04`, `docs/ui-ux/06` | Catálogo de errores (cinco códigos; `mfa-attempts-exceeded` no existe), retardo sin bloqueo | 8 |
| `openspec/changes/foundations-plan/exploration.md` | H1 y condición 3 cerradas, con sus pruebas | 8 |

## 4. Concurrencia

Orden global de bloqueos, sin excepción: **cuenta → credencial TOTP (fila) → familias por `id`**. La
rotación y el cierre actual toman **solo** la familia y nunca la cuenta, así que no hay ciclo posible.

| Carrera | Mecanismo | Resultado en todo orden | Prueba (dos transacciones reales) |
|---|---|---|---|
| Cierre actual frente a rotación del mismo token | Ambos toman el bloqueo de la familia; la búsqueda por hash es sin bloqueo | Cierre primero: la rotación lee la familia revocada → `401 session-expired`. Rotación primero: el cierre revoca la familia (el token ya consumido la localiza igual) | `LogoutVersusRotationConcurrencyIT` |
| Cerrar todas frente a rotación | Cerrar todas: cuenta, luego familias `ORDER BY id`; rotación: solo la familia | La familia termina revocada; ninguna rotación deja un sucesor vivo | `CloseAllVersusRotationConcurrencyIT` |
| Inscripción frente a verificación | Ambas toman primero el bloqueo de la cuenta | Inscripción primero: la verificación usa el secreto nuevo. Verificación primero: la inscripción ve la credencial verificada → `409`. Nunca se marca verificada una credencial con un secreto que el usuario no validó | `EnrollVersusVerifyConcurrencyIT` |
| Dos inscripciones concurrentes (O1) | Bloqueo de la cuenta | La segunda es una reinscripción: dos `201`, gana la última; nunca `500` ni violación de unicidad | `ConcurrentEnrollmentIT` |
| Refresco desde dos pestañas | Gracia de S1 (mismo sucesor) + `SessionCookies.issue` en la gracia | Las cookies finales son siempre un par coherente: el refresco sucesor y un CSRF ligado a él, venga de la respuesta que venga | `TwoTabRefreshConcurrencyIT` (con el par de cookies de cada respuesta combinado en los cuatro órdenes) |
| Primera verificación frente a otro inicio de sesión | Cuenta con `mfa_required`: el otro inicio de sesión solo obtiene token restringido; la revocación `mfa_change` y la emisión ocurren bajo el bloqueo de la cuenta | La sesión nueva nunca es revocada por su propia activación | Incluida en `CompleteSecondFactorIT` |

Todas las pruebas retienen la primera transacción con un `CountDownLatch` en un repositorio decorado y la
liberan cuando `pg_stat_activity` muestra a la segunda esperando un bloqueo (condición con límite, como S1).

## 5. Flujo de datos

```
Verificación de segundo factor
  Bearer restringido ─► filtro: verifyMfa, purpose ∈ ruta ─► PendingSecondFactor
  ─► admin-mfa ─► SecondFactorController ─► Materializer
       CompleteSecondFactor (una transacción)
         lockById(cuenta) ─► credencial ─► TOTP (VerifyTotpCode) | recuperación (+ retroceso TOTP)
         éxito ENROLL: activar ─► revokeAllLive(mfa_change) ─► IssueSession
         éxito VERIFY: IssueSession
       COMMIT ─► firmar acceso ─► [fallo: RateLimitFailures.record("admin-mfa")] ─► espera
  ─► SessionCookies.issue ─► 200 authenticated  |  401 second-factor-invalid

Refresco
  cookie de sesión + X-CSRF-Token + Origin ─► CsrfOriginFilter (HMAC sobre SHA-256 del refresco)
  ─► admin-session ─► SessionRefreshController ─► RefreshSession ─► RotateRefreshToken
       hash ─► token (sin bloqueo) ─► familia FOR UPDATE ─► relectura ─► vaciar sucesores >= 10 s
       ─► RotationDecision (con vinculación) ─► efecto + auditoría ─► COMMIT ─► firmar acceso
  ─► Refreshed: SessionCookies.issue ─► 200  |  Rejected: clear + record("admin-session") ─► 401 session-expired

Cierre actual
  cookie + CSRF + Origin ─► EndCurrentSession: hash ─► token ─► familia FOR UPDATE ─► revoke(logout)
  ─► SessionCookies.clear ─► 204
```

## 6. Cambios de archivos, por rebanada

Rutas bajo `apps/api/app/src/` salvo indicación. «R» es la rebanada de la sección 9.

| Archivo | Acción | R |
|---|---|---|
| `main/java/com/confia/shared/security/csrf/{CsrfTokens,CsrfKeys,CsrfConfiguration,package-info}.java` | Crear (decisión 3) | 1 |
| `main/java/com/confia/shared/web/csrf/{CsrfOriginFilter,AllowedOrigins,CookieCredentialRoute,package-info}.java` | Crear (decisiones 2 y 4) | 1 |
| `main/java/com/confia/shared/web/request/WebEdgeProperties.java` | `allowedOrigins` | 1 |
| `main/java/com/confia/shared/web/edge/AdminSecurityConfiguration.java` | Filtro CSRF antes del de tokens | 1 |
| `main/java/com/confia/shared/security/token/SigningKeyPlacementGuard.java` | Propiedades CSRF prohibidas en portal y trabajador | 1 |
| `main/java/com/confia/bootstrap/admin/AdminApplication.java` | `@Import` de `CsrfConfiguration` (1) y `SessionCookieConfiguration` (3) | 1, 3 |
| `main/java/com/confia/shared/web/problem/{ProblemCode,ProblemResponses,ProblemCodeException}.java`; `main/resources/i18n/problems.properties` | Códigos de la decisión 18, cada uno en su rebanada | 1, 5, 6, 7 |
| `main/resources/application-local.yml` | `confia.web.allowed-origins: http://localhost:5173` | 1 |
| `main/java/com/confia/shared/audit/{AuditJson,CanonicalAuditRowSerializer}.java` | Crear y extraer el escape | 2 |
| `main/java/com/confia/identity/application/{AuthenticateWithPassword,ConsumeRecoveryCode,IssueSession,VerifyTotpCode,RequestPasswordReset,IssuePasswordResetToken,ResetPasswordWithToken,RotateRefreshToken}.java` | Migración a `AuditJson` | 2 |
| `main/java/com/confia/shared/web/ratelimit/{RateLimitProperties,RateLimitFailures}.java`; `shared/web/edge/ThrottlingConfiguration.java`; `shared/security/RateLimiterConfiguration.java` | Decisión 7 | 3 |
| `main/java/com/confia/shared/web/delay/{DelayProperties,RequiredDelayMaterializer}.java`; `shared/web/edge/RequiredDelayConfiguration.java` | Decisión 8 | 3 |
| `main/java/com/confia/shared/web/session/{SessionCookies,SessionCookie,package-info}.java`; `shared/web/edge/SessionCookieConfiguration.java` | Decisión 5 | 3 |
| `main/resources/application.yml` | Valores de las tres políticas, `max-wait`, `spring.jackson.deserialization.fail-on-unknown-properties` | 3 |
| `main/resources/db/migration/V9__extend_identity_refresh_token_family_revocation_reasons.sql` | Crear (decisión 16) | 4 |
| `main/java/com/confia/identity/application/{SessionRevocationReason,RefreshTokenFamilyRepository,EndCurrentSession,EndAllSessions}.java`; `identity/infrastructure/JooqRefreshTokenFamilyRepository.java`; `identity/infrastructure/wiring/IdentityConfiguration.java` | Decisión 15 (casos de uso sin HTTP) | 4 |
| `main/java/com/confia/identity/application/{AuthenticationCommand,LogIn,LoginCommand,LoginDecision,LoginOutcome,SessionGrant,SecondFactorChallenge,LoginFailureSpread}.java` | Decisiones 10 a 12 | 5 |
| `main/java/com/confia/identity/domain/FailureSpreadWindow.java`; `identity/infrastructure/InMemoryLoginFailureSpread.java` | Decisión 12 | 5 |
| `main/java/com/confia/identity/web/{LoginController,LoginRequest,AuthenticatedSessionResponse,SecondFactorPendingResponse}.java`; `identity/web/wiring/IdentityWebConfiguration.java` | Decisión 11 | 5 |
| `main/java/com/confia/shared/web/edge/PublicEndpoints.java` | `POST /auth/sessions` (5); refresco y cierre actual (7) | 5, 7 |
| `main/java/com/confia/shared/security/{PendingSecondFactor,SecondFactorPurpose}.java`; `shared/web/authentication/{SecondFactorEndpoint,SecondFactorAuthentication,AuthenticatedEndpoints,AccessTokenAuthenticationFilter}.java`; `shared/web/edge/SecurityChains.java` | Decisión 6 | 6 |
| `main/java/com/confia/identity/domain/TotpCredential.java`; `identity/application/{AuthenticateWithPassword,ResetPasswordWithToken,TotpCredentialRepository,RecoveryCodeRepository,EnrollTotpSecondFactor,EnrollTotpSecondFactorResult,CompleteSecondFactor}.java`; `identity/infrastructure/{JooqTotpCredentialRepository,JooqRecoveryCodeRepository}.java` | Decisión 13 | 6 |
| `main/java/com/confia/identity/web/{SecondFactorController,SecondFactorVerificationRequest,EnrollmentResponse}.java` | Decisión 13 | 6 |
| `main/java/com/confia/identity/domain/{RotationDecision,RefreshRejection,RefreshTokenFamilyState}.java`; `identity/application/{RotateRefreshTokenCommand,RotateRefreshToken,RefreshSession}.java` | Decisión 14 | 7 |
| `main/java/com/confia/identity/web/{SessionRefreshController,SessionEndController}.java`; `shared/web/authentication/AuthenticatedEndpoints.java` | Decisiones 14 y 15 | 7 |
| `apps/api/openapi/admin.openapi.json`; `packages/contracts` (regenerado, no comprometido) | Operación de cada rebanada | 5, 6, 7, 8 |
| `test/java/com/confia/bootstrap/{TestProcessArguments,ProcessBeanPolicy,PublicRouteAllowListTest,RegisteredRoutes}.java` | Clave CSRF de prueba, líneas, enumerador | 1, 3, 5 a 7 |
| `test/java/com/confia/architecture/{WebEdgeScopeExclusionInventoryTest,AuditJsonOnlySourceTest}.java` y fixtures | Inversiones y guardia | 2, 3, 5 |
| `test/java/**` restantes | Sección 8 | 1 a 8 |
| Documentos de la decisión 21 | Notas fechadas | 1, 3, 5 a 8 |

## 7. Interfaces y contratos

```java
// com.confia.identity.application
public final class LogIn { public LoginDecision execute(LoginCommand command); }
public final class CompleteSecondFactor {
    public SecondFactorDecision execute(PendingSecondFactor pending, SecondFactorProof proof,
            ClientAddress client, String userAgent, String requestId);   // Delayed in the decision
}
public interface LoginFailureSpread {
    boolean recordFailure(ClientAddress client, IdentifierFingerprint identifier, Instant now);
}
public final class EndCurrentSession { public void execute(String presentedRefreshToken, String requestId); }
public final class EndAllSessions { public int execute(AuthenticatedActor actor, String requestId); }
public interface RefreshTokenFamilyRepository {   // added
    boolean revoke(InstitutionId institutionId, UUID familyId, SessionRevocationReason reason, Instant now);
}
public interface TotpCredentialRepository {        // added
    boolean replaceUnverifiedSecret(InstitutionId institutionId, StaffAccountId accountId,
            String encryptedSecret, Instant now);
}
public interface RecoveryCodeRepository {          // added
    int invalidateUnused(InstitutionId institutionId, StaffAccountId accountId, Instant now);
}
// com.confia.identity.domain
public static RotationDecision decide(RefreshTokenFamilyState family, RefreshTokenState token,
        UserAgentFingerprint presenter, boolean addressChanged, Instant now, SessionLifetimePolicy policy);
```

**Contrato HTTP** (DA-14 *a*): `POST /api/v1/auth/sessions`, `POST /api/v1/auth/mfa/verifications`,
`POST /api/v1/auth/mfa/enrollments`, `POST /api/v1/auth/sessions/refresh`,
`DELETE /api/v1/auth/sessions/current`, `DELETE /api/v1/auth/sessions`. Ninguna con `Idempotency-Key`.
Todas `Cache-Control: no-store`. Cuerpos: decisiones 11, 13 y 14. **Propiedades nuevas:**
`confia.web.allowed-origins`, `confia.security.csrf.key`, `confia.security.csrf.previous-key`,
`confia.web.rate-limit.{admin-login,admin-mfa,admin-session}.*`, `confia.web.delay.max-wait`,
`spring.jackson.deserialization.fail-on-unknown-properties`.

## 8. Estrategia de pruebas, por rebanada (TDD estricto)

Cada fila nace **en rojo** con el motivo esperado registrado en `apply-progress.md`. Las pruebas de ataque se
escriben antes que el código que defienden. Ninguna prueba de concurrencia espera por reloj.

| R | Nivel | Qué | Cómo | Rojo esperado |
|---|---|---|---|---|
| 1 | Unidad | `CsrfTokens`: ida y vuelta; un bit alterado en `r` o en el MAC; MAC de otro refresco; clave anterior acepta y emite con la actual; longitud, relleno, `+`, `/` y bits de cola rechazados; jqwik «todo token alterado en una posición se rechaza» | `CsrfTokensTest`, `CsrfTokensProperties` | Clase inexistente |
| 1 | Unidad | `AllowedOrigins`: ruta, barra final, comodín, mayúsculas, `http` no local, repetidos, usuario; mensaje sin valor | `AllowedOriginsTest` | Clase inexistente |
| 1 | Contexto real | Administración no arranca sin clave, con clave corta, con Base64 inválido o con clave anterior igual a la actual; portal y trabajador no arrancan con la clave; los tres arrancan con `TestProcessArguments` | `CsrfKeyStartupTest` por `ConfiaApplication.launch` | Arranque sin fallo |
| 1 | Contexto (arnés sin base de datos) | **Condición 3:** con cookie de sesión del arnés y sin `X-CSRF-Token`, `403 csrf-invalid`; con token CSRF válido **de otra sesión**, `403`; con cookie CSRF y cabecera distintas, `403`; con dos cookies de sesión, `403`; sin `Origin` en `POST`, `403`; con `Origin` no permitido, `403`; `Origin: null`, `403`; válido, `200`; ruta de cookie sin cookie de sesión, pasa; `GET` sin `Origin`, pasa; lista vacía, todo `POST` `403` | `CsrfOriginFilterTest` sobre un controlador del arnés que emite y lee la cookie | `200` en todo caso |
| 1 | Contexto real | Orden de filtros: `CsrfOriginFilter` antes de `AccessTokenAuthenticationFilter`; una petición rechazada no llama al verificador (doble espía) | `AdminFilterOrderTest` | Filtro inexistente |
| 1 | Contexto real | Línea de `ProcessBeanPolicy` en rojo antes de añadirla | `ProcessBeanIsolationTest` | «not in the allow-list» |
| 2 | Unidad | `AuditJson`: escape, nombres inválidos y repetidos, orden; igualdad por sitio con la expresión antigua (decisión 17) | `AuditJsonTest`, `AuditJsonSiteEqualityTest` | Clase inexistente |
| 2 | Estático | Ningún `"{\"` fuera de la lista, con fixture | `AuditJsonOnlySourceTest` | Los 16 sitios actuales |
| 2 | Integración | Las pruebas existentes de `after_value` y de la cadena corren sin cambios | Suite existente | — |
| 3 | Unidad | Tres políticas con valores por omisión desde `application.yml`; componente ausente o desconocido detiene el arranque nombrando la propiedad | `RateLimitPropertiesTest` | Prefijo fijo |
| 3 | Unidad | Materializador: con retardo de 900 s espera 30 s (temporizador de prueba); `max-wait` fuera de rango no arranca; `Delayed` conserva el retardo | `RequiredDelayMaterializerTest` | Espera 900 s |
| 3 | Contexto (arnés) | **S-5:** propiedad desconocida en un cuerpo, `400 validation-failed` | `UnknownJsonPropertyTest` | `200` si el valor de omisión es ignorar |
| 3 | Unidad | `SessionCookies`: cada atributo exacto de las dos cookies; borrado con `Max-Age=0` y los mismos `Path`; lectura `ABSENT`, `PRESENT`, `DUPLICATED` | `SessionCookiesTest` | Clase inexistente |
| 3 | Estático | Cookie solo en `SessionCookies`, con fixture | `WebEdgeScopeExclusionInventoryTest` | «ninguna clase usa Cookie» |
| 4 | Integración | `V9`: cada motivo nuevo aceptado, uno inventado rechazado; catálogo enum = restricción | `RevocationReasonCatalogIT`, `RefreshTokenSchemaIT` | `CHECK` de V8 |
| 4 | Integración | `EndCurrentSession`: revoca con `logout` también con el token consumido; desconocido, revocado o vencido sin efecto ni asiento; `EndAllSessions`: 100 % de las familias vivas de la cuenta, ninguna de otra cuenta | `EndSessionIT` | Clases inexistentes |
| 4 | Integración | **F1b:** revocar en la institución A no toca familias de B (segunda institución de fixture) | `AccountSessionRevokerCrossInstitutionIT` | Fixture inexistente |
| 4 | Integración | **F1a:** tras el restablecimiento, ningún refresco rota y ninguna ruta `LIVE_SESSION` acepta un acceso de la cuenta, afirmado de forma directa | `PasswordResetLeavesNoValidTokenIT` | — (prueba nueva sobre comportamiento existente; su control negativo es un restablecimiento sin revocador) |
| 4 | Concurrencia | Cierre frente a rotación; cerrar todas frente a rotación | `LogoutVersusRotationConcurrencyIT`, `CloseAllVersusRotationConcurrencyIT` | Clases inexistentes |
| 5 | Unidad + jqwik | `FailureSpreadWindow`: 4 distintos no señalan, el quinto sí, la misma huella no suma, fuera de 10 min no cuenta, el sexto no vuelve a señalar dentro de la ventana | `FailureSpreadWindowTest` | Clase inexistente |
| 5 | Integración | `LogIn`: tres desenlaces; `Authenticated` crea la familia en la misma transacción que la auditoría (un escritor de auditoría que falla revierte ambas); token restringido con el `purpose` correcto; señal y asiento al quinto identificador | `LogInIT` | Clase inexistente |
| 5 | IT HTTP | **H1:** con la política `admin-login` real, N fallos desde una IP llegan a `429` con `Retry-After`; desde ahí, filas y `consecutive_failures` de `identity_login_backoff` y asientos `identity.login.failed` dejan de crecer (identificadores distintos en cada intento) | `LoginRateLimitClosesH1IT` | Ruta inexistente |
| 5 | IT HTTP | `401 authentication-failed` igual para cuenta existente e inexistente (cuerpo salvo `traceId`, estado, cabeceras salvo `Date`), con retardo cero y con retardo; `200` con cookies exactas; `200` restringido sin `Set-Cookie`; `415`; `400` con propiedad desconocida; `403` sin `Origin`; la espera no supera el tope | `LoginEndpointIT` | Ruta inexistente |
| 5 | Estático | Los controladores de S2 no dependen de `shared.security.token` ni de `identity.domain` | `WebLayerTokenIsolationTest`, `LayeredArchitectureTest` | — |
| 6 | Contexto (arnés) | Restringido en `GET /sessions/current`, `401 token-invalid`; acceso en verificaciones, `401 token-invalid`; `ENROLL` en inscripción y en verificación, aceptado; `VERIFY` en inscripción, `401`; dentro de la ruta restringida `AuthenticatedActor.current()` vacío y `TokenCurrentInstitutionProvider` lanza | `SecondFactorRouteTest` | Rutas sin clase |
| 6 | Integración | Reinscripción con credencial sin verificar: secreto sustituido, códigos previos con `used_at`, asiento; verificada: `409` sin escritura; primera verificación con `ENROLL`: activa, revoca las demás con `mfa_change`, emite sesión no revocada; recuperación rechazada con `ENROLL`; fallo de recuperación suma al retroceso TOTP | `EnrollTotpSecondFactorIT`, `CompleteSecondFactorIT` | Comportamiento de inserción simple |
| 6 | Integración | «MFA activa» = verificada: inicio de sesión con secreto sin verificar → `second-factor-enrollment-required`; restablecimiento sin segundo factor en ese caso | `AuthenticateWithPasswordIT`, `ResetPasswordWithTokenIT` actualizadas | Desenlace antiguo |
| 6 | Concurrencia | Inscripción frente a verificación; dos inscripciones (O1, nunca `500`) | `EnrollVersusVerifyConcurrencyIT`, `ConcurrentEnrollmentIT` | Clases inexistentes |
| 7 | Unidad + jqwik | `RotationDecision` con la tabla nueva y sus bordes; propiedad «con la vinculación sin cambio, el resultado es el de S1» | `RotationDecisionTest`, `RotationDecisionProperties` | Firma antigua |
| 7 | Integración | Vinculación: cambian agente y dirección → revoca con `binding_changed`; solo uno → rota; IPv6 comprimido y expandido → igual; `ip_first_seen` nulo → no cambiada | `RefreshBindingIT` | Sin vinculación |
| 7 | IT HTTP | **Condición 3 sobre las rutas reales:** refresco y cierre actual con cookie y sin `X-CSRF-Token` → `403`; con CSRF de otra sesión → `403`; sin `Origin` → `403` | `SessionCookieRoutesCsrfIT` | Rutas inexistentes |
| 7 | IT HTTP | Refresco: rota y reemite ambas cookies, también en la gracia; todo rechazo `401 session-expired` con cookies borradas; cierre actual `204` con acceso vencido; cerrar todas `204`, revoca todas y borra cookies | `SessionRefreshEndpointIT`, `SessionEndEndpointIT` | Rutas inexistentes |
| 7 | Concurrencia | Dos pestañas: el par final de cookies es coherente en los cuatro órdenes | `TwoTabRefreshConcurrencyIT` | Ruta inexistente |
| 8 | Unidad | **F2:** `epochSecond` −1, 0, `LAST_EPOCH_SECOND`, `+1`, `Long.MAX_VALUE`, decimales y cadenas | `ClaimReaderTest` | 8 mutantes vivos |
| 8 | Contexto | **F3:** el mapa `secrets` incluye el `jti` del acceso y del restringido | `SessionSecretsNotObservableTest` | `jti` ausente |
| 8 | IT HTTP | **O9:** un flujo completo (inicio, inscripción, verificación, refresco, cierre) no deja contraseña, acceso, refresco, token restringido, token CSRF, secreto TOTP, códigos de recuperación ni `Authorization` en ningún evento (mensaje, argumentos, MDC, excepción) del registro estructurado; control negativo con un evento sembrado | `SessionFlowRedactionIT` | — (control negativo primero) |
| 8 | IT HTTP | **Puerta de tiempo de §4.6:** 200 intentos con cuenta existente (contraseña errónea) y 200 con identificador inexistente; diferencia de medianas ≤ 50 ms. Con temporizador de prueba en el materializador (sin esperar el retardo) y la política de tasa elevada por propiedades de la prueba | `LoginTimingGateIT` | — |
| 8 | Estático | Inversiones restantes de la decisión 20, cada una con control negativo | Pruebas de la tabla | Ausencias de S1 |

- PIT ≥ 80 % y JaCoCo ≥ 95 % en `identity.domain` (incluidos `FailureSpreadWindow` y `RotationDecision`).
  `shared.security.csrf` entra en `targetClasses` de PIT.
- Testcontainers compartido; la puerta de tiempo añade unas 400 verificaciones Argon2id (riesgo de
  presupuesto de `verify`, sección 11).

## 9. Plan de entrega

DA-1 *a*: cadena de PR apilados sobre `main`, «partir solo si hace falta», 800 líneas efectivas por PR
(`docs/15` §3). La preflight declara `single-pr` y 400 líneas: se mantiene la decisión del propietario de
DA-1 y la fase de tareas lo registra. Los tamaños son los de la propuesta.

| # | Rebanada | Contenido | Nominal → realista |
|---|---|---|---|
| 1 | `csrf-and-origin-protection` | Decisiones 2, 3, 4; `csrf-invalid`; condición 3 en arnés | 330 → 730 |
| 2 | `audit-json-serializer` | Decisión 17 | 150 → 330 |
| 3 | `edge-policies-and-delay-cap` | Decisiones 5, 7, 8, 9 | 280 → 620 |
| 4 | `session-revocation-v9` | Decisiones 15 (casos de uso) y 16; F1a, F1b | 400 → 880 (2 PR) |
| 5 | `login-endpoint` (cierra H1) | Decisiones 10, 11, 12; `authentication-failed` | 600 → 1 320 (2 PR) |
| 6 | `second-factor-endpoints` | Decisiones 6, 13; dos códigos | 650 → 1 430 (2 PR) |
| 7 | `refresh-and-logout-endpoints` | Decisiones 14, 15 (HTTP); `session-expired`; condición 3 sobre rutas reales | 550 → 1 210 (2 PR) |
| 8 | `closing-and-follow-ups` | F2, F3, O9, puerta de tiempo, inversiones, contratos, notas | 350 → 770 |

**Órdenes duros:**

1. **R1 antes de cualquier PR que emita `Set-Cookie`, incluido el borrado.** R3 crea `SessionCookies` sin
   ninguna ruta que lo llame; la primera emisión real es R5.
2. **R2 antes de R4 a R7**: ninguna entrada nueva se escribe por concatenación.
3. R3 antes de R5 (políticas, tope, cookies, S-5).
4. R4 antes de R6 (`mfa_change`) y de R7 (`logout`, `binding_changed`).
5. R5 antes de R6 (el inicio de sesión emite el token restringido) y de R7.
6. **El limitador se aplica en el mismo PR que expone el inicio de sesión** (H1): si R5 se parte, el primer
   PR contiene `LogIn` sin controlador y el segundo, controlador, limitador y prueba de H1 juntos.

Costuras: R4 en `V9` + casos de uso / F1a + F1b + concurrencia; R5 en `LogIn` + señal / controlador + H1 +
OpenAPI; R6 en filtro + principal + rutas / inscripción + verificación; R7 en vinculación / HTTP.

## 10. Desviaciones respecto de la propuesta y precisiones para la especificación

| # | Punto de la propuesta | Precisión de este diseño | Motivo |
|---|---|---|---|
| P-1 | Alcance 13: «puerto y adaptador para localizar una familia por hash» | Sin puerto nuevo: composición de `findByHash` y `lockById`; solo se añade `revoke(reason)` | Ya existen y la rotación los usa en ese orden |
| P-2 | Alcance 9: «tope en `Delayed`» | Tope en `RequiredDelayMaterializer` y `DelayProperties.max-wait`; `Delayed` no cambia | El materializador es el único que espera (W4) y `Delayed` debe conservar el retardo de la política |
| P-3 | Alcance 3: HMAC en rutas que leen la cookie | Una ruta de cookie **sin** cookie de sesión no exige token CSRF (sigue exigiendo `Origin`) | Sin credencial ambiental no hay CSRF; evita `403` al cliente que ya cerró sesión |
| P-4 | Riesgo «clave CSRF»: los CSRF «se reemiten en el siguiente refresco» | Clave anterior opcional de solo verificación | Sin ella, el refresco mismo responde `403` tras rotar la clave |
| P-5 | Alcance 10: indicador de `Secure` por perfil con guardia | Sin indicador (pendiente de D-N1) | Los prefijos `__Host-` y `__Secure-` exigen `Secure` |
| P-6 | Igualdad «exacta» del `401` | Igualdad de cuerpo salvo `traceId`, y de cabeceras salvo `Date` | `traceId` es distinto por petición por diseño (4a) |
| P-7 | Riesgo «códigos de recuperación sin retroceso» («el diseño evalúa») | Sus fallos suman al retroceso TOTP en la ruta de inicio de sesión | Decisión 13 |
| P-8 | O1: inscripción concurrente «responde `409`, no `500`» | Bajo el bloqueo de la cuenta la segunda es reinscripción (`201`), nunca `500` (pendiente de D-N2) | Con DA-7 *c* la segunda inscripción es legítima mientras no se verifique |
| P-9 | Precisión de DA-7 *c* | Con `purpose = ENROLL` solo se acepta TOTP; con la credencial ya verificada, `second-factor-invalid` (pendiente de D-N3) | Decisión 13 |
| P-10 | Sitios de F1c: «al menos 16» | Exactamente 16 sitios en 8 clases a la fecha de `6a8c61f`; la guardia `AuditJsonOnlySourceTest` impide que aparezcan más | Inventario de la sección 2.2 |

**Para la especificación:** los escenarios de `identity` deben reflejar P-3, P-6, P-8 y P-9; los de
`web-edge`, la tabla de la decisión 2, la clave anterior (P-4) y las tres políticas con sus valores; los de
`audit-trail`, solo si fija un requisito observable de `AuditJson` (la igualdad de bytes ya la cubre la
reproducibilidad canónica existente).

## 11. Riesgos

| # | Riesgo | Mitigación |
|---|---|---|
| R-1 | Un token restringido usado como sesión | Principal y `ScopedValue` distintos; el filtro elige verificador por clase de ruta; `SecondFactorRouteTest` |
| R-2 | La propiedad de Jackson no se honra en Spring Boot 4.1.1 | Prueba en rojo en R3 y alternativa escrita (decisión 9) |
| R-3 | Comportamiento de `Secure` en `localhost` según navegador, no verificado | Sin indicador; Playwright con la primera pantalla; D-N1 |
| R-4 | Autodenegación por IP compartida (NAT, proxy con `trusted-proxies` vacío) | Valores en configuración (DA-10), señal sin bloqueo (DA-9), llenado de proxies en el cambio 11 |
| R-5 | Señal de cinco cuentas por proceso y volátil | Declarado; es una señal, no un control; Redis en el cambio 11 |
| R-6 | Ventana de primera inscripción extendida hasta la primera verificación (DA-15, DA-7 *c*) | Auditoría de cada (re)inscripción y de la activación; revisión en el cambio 8 |
| R-7 | Inicio de sesión con una cookie anterior deja viva la familia anterior hasta su vencimiento | Sin efecto de seguridad nuevo (la familia sigue sujeta a inactividad de 30 min); «cerrar todas» la revoca |
| R-8 | `ip_first_seen` nulo desactiva la mitad de la vinculación | Declarado; con la dirección resuelta por el filtro de origen solo ocurre sin dirección literal, y el limitador ya responde `503` en ese caso |
| R-9 | Duración de `verify` (puerta de tiempo, 400 Argon2id más) | Perfil Argon2 de piso en pruebas; medición en R8; si supera el presupuesto, la puerta pasa al perfil de mutación con decisión registrada |
| R-10 | Subestimación de tamaño (factor histórico 2,2 a 7,97) | Costuras nombradas por rebanada; replanificación al superar el 50 % |
| R-11 | Tiempos de corte de nginx y Cloudflare sin verificar | Tope de 30 s por debajo de ambos valores por omisión; cambio 11 |
| R-12 | Cambio de la definición de «MFA activa» altera el restablecimiento (S3 y especificación archivada) | Misma rebanada que la inscripción; escenarios del restablecimiento actualizados en la especificación |

## 12. Matriz de amenazas

La matriz de la fase de diseño no aplica: el cambio no toca órdenes de consola, subprocesos, automatización
de Git o de PR ni clasificación de archivos ejecutables.

| Frontera | Aplicabilidad |
|---|---|
| Rutas con apariencia de documentación | N/A: no se clasifican ni ejecutan archivos |
| Selección de repositorio Git, estado del commit, estado del push, órdenes de PR | N/A: sin automatización de Git ni de PR |

Fronteras de seguridad propias, cada una con prueba en rojo en la sección 8:

| Frontera | Comportamiento seguro | Prueba |
|---|---|---|
| Petición entre sitios con la cookie de sesión | `403 csrf-invalid` | `CsrfOriginFilterTest`, `SessionCookieRoutesCsrfIT` |
| Fijación del token CSRF desde un subdominio | Rechazo por HMAC ligado al refresco | Mismas |
| Inicio de sesión desde otro origen | `403 csrf-invalid` | `LoginEndpointIT` |
| Token restringido como sesión | `401 token-invalid` fuera de sus rutas; nunca `AuthenticatedActor` | `SecondFactorRouteTest` |
| Enumeración por respuesta o por tiempo | `401` uniforme y puerta de §4.6 | `LoginEndpointIT`, `LoginTimingGateIT` |
| Fuerza bruta desde una IP | `429` antes de Argon2id y de la transacción | `LoginRateLimitClosesH1IT` |
| Agotamiento del semáforo con esperas largas | Espera ≤ 30 s | `RequiredDelayMaterializerTest` |
| Refresco robado desde otro equipo y otra red | Familia revocada con `binding_changed` | `RefreshBindingIT` |
| Secretos en el registro | Ninguno en un flujo completo | `SessionFlowRedactionIT` |

## 13. Decisiones nuevas para el propietario

**D-N1. `Secure` en desarrollo local** (alcance 10 de la propuesta).
- *a)* Sin indicador: las cookies son siempre `Secure` y con prefijo. El desarrollo local funciona en
  Chromium y Firefox sobre `http://localhost` o con HTTPS local; Safari no se usa en desarrollo local. Menos
  código y ninguna guardia de producción que mantener.
- *b)* Indicador por perfil: con `Secure` apagado las cookies cambian de nombre (sin prefijo, porque el
  prefijo lo exige), con una guardia que impide el indicador fuera del perfil `local`. Unas 60 líneas y una
  segunda forma de las cookies que probar.
- **Recomendación: *a)*.**

**D-N2. Dos inscripciones concurrentes de la misma cuenta** (O1).
- *a)* Bajo el bloqueo de la cuenta, la segunda es una reinscripción: dos `201`, gana la última; el usuario
  que escaneó la primera falla la verificación y puede reinscribirse, porque la credencial sigue sin
  verificar.
- *b)* La segunda responde `409` si la primera tiene menos de N segundos. Añade un estado temporal sin
  beneficio de seguridad.
- **Recomendación: *a)*.**

**D-N3. Qué pruebas acepta la primera verificación (token `mfa-enroll`).**
- *a)* Solo TOTP: la activación prueba que el usuario tiene el secreto en su aplicación.
- *b)* También un código de recuperación de esa misma inscripción: activa la credencial sin que nadie haya
  probado el secreto, y el usuario puede quedar sin TOTP funcional.
- **Recomendación: *a)*.**

## 14. Preguntas abiertas

- [ ] D-N1, D-N2 y D-N3 (sección 13).
- [ ] Ninguna otra pregunta bloquea la fase de tareas. La sonda S-5 se resuelve con su prueba en rojo en R3.

**Nota (2026-10-20, decisiones del propietario).** El propietario acepta las recomendaciones de D-N1 (sin indicador: las cookies son siempre `Secure` y con prefijo), D-N2 (una segunda inscripción concurrente es una reinscripción: dos `201` y gana la última, nunca `500`) y D-N3 (la primera verificación con el token `mfa-enroll` acepta solo TOTP). La especificación se alinea con estas decisiones y con P-3, P-6, P-8 y P-9 antes de la fase de tareas.
