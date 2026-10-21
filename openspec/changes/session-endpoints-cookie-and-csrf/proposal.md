# Propuesta: endpoints de sesión, cookie y CSRF (parte 4b, S2)

- **Cambio:** `session-endpoints-cookie-and-csrf`
- **Fase del roadmap:** F0, cambio 7, parte 4b, **segundo cambio (S2) de tres** según DA-1 y DA-6 del
  2026-10-06 (S1, S2, cambio 9, S3).
- **Depende de:** `session-tokens-and-web-layer` (S1, archivado el 2026-10-19), `web-edge-foundations`
  (4a), `column-encryption-and-mfa-totp`, `identity-module-and-password-authentication`.
- **Exploración:** `openspec/changes/session-endpoints-cookie-and-csrf/exploration.md` (Engram
  `sdd/session-endpoints-cookie-and-csrf/explore`). Las decisiones DA-1, DA-2, DA-3, DA-4, DA-5, DA-7 y
  DA-12 de su sección 5, tomadas por el propietario el 2026-10-20, son vinculantes y esta propuesta no
  las reabre.
- **Línea base:** rama `change/session-endpoints-cookie-and-csrf` en `663331c` (sobre `main` en `dafabf3`).
- **Estado:** propuesta aprobada por el propietario el 2026-10-20 (nota final). Las decisiones DA-6, DA-8, DA-9,
  DA-10, DA-11, DA-13, DA-14 y DA-15 se redactan con la recomendación de la exploración como posición de
  trabajo, confirmada por el propietario (sección final).

## Intención

S1 entregó todo el mecanismo de sesión (tokens, familias, rotación, gracia, reutilización,
`SessionLiveness`, filtro `Bearer`) sin ningún endpoint que entregue credenciales a un cliente. Hoy el
proceso administrativo no permite iniciar sesión: `IssueSession`, `RotateRefreshToken`, `VerifyTotpCode`,
`EnrollTotpSecondFactor` y el token restringido de MFA no tienen llamador de producción, el limitador
`admin-login` y el materializador del retardo no tienen ruta, y la cadena administrativa tiene CSRF
deshabilitado y prohíbe las cookies.

S2 es el primer cambio que **emite credenciales a un navegador**: inicio de sesión, verificación e
inscripción del segundo factor, refresco con cookie, cierre de sesión y cierre de todas las sesiones,
con la protección CSRF de doble envío y la verificación de `Origin` activas **antes** de que exista
cualquier `Set-Cookie`, y con el control por IP del inicio de sesión aplicado **antes** de que exista un
camino HTTP que escriba en `identity_login_backoff`.

**Por qué ahora:** es el orden fijado por DA-1 y DA-6 de S1. El cambio 9 y S3 (recuperación de
contraseña por HTTP) dependen de S2, y ninguna pantalla de `apps/admin-web` puede construirse sin una
sesión real.

**Cómo se ve el éxito:** un usuario del personal inicia sesión por HTTP, completa el segundo factor cuando
su cuenta lo exige, recibe un acceso `Bearer` en el cuerpo y un refresco en una cookie `HttpOnly`, lo
rota con CSRF y `Origin` válidos, y cierra su sesión aunque el acceso haya vencido; una petición
mutadora con la cookie y sin `X-CSRF-Token` recibe `403`; N fallos desde una IP topan en `429` sin que
`identity_login_backoff` siga creciendo; y ningún registro contiene contraseña, token, secreto TOTP ni
`Authorization` en un flujo completo.

## Condiciones duras que cierra

| Condición | Texto resumido | Cómo la cierra S2 | Prueba de cierre |
|---|---|---|---|
| **H1** (2026-09-25) | No fusionar un inicio de sesión que escriba en `identity_login_backoff` sin el control por IP de `docs/03` §4.4, o un tope equivalente, existente, probado y operativo. D2 (2026-10-04) acepta el limitador en memoria hasta el cambio 11 | `@RateLimited(policy = "admin-login")` sobre el manejador de inicio de sesión (el interceptor corre antes de abrir transacción y antes de Argon2id); `recordFailure(client)` tras confirmar el caso de uso cuando el desenlace es `Rejected`; los desenlaces `SecondFactorRequired` y `SecondFactorEnrollmentRequired` no cuentan. Fila «5 cuentas distintas en 10 minutos» según DA-9 | N fallos desde una IP topan en `429` con `Retry-After` y, a partir de ahí, `identity_login_backoff` deja de crecer. Reserva declarada de «operativo»: con `confia.web.trusted-proxies` vacío todo el tráfico detrás de nginx comparte un cubo (llenado en el cambio 11) |
| **Condición 3** (2026-10-04) | No fusionar la primera credencial en cookie sin CSRF de doble envío con verificación de `Origin`, y sin una prueba de que una petición mutadora con la cookie y sin `X-CSRF-Token` recibe `403` | El PR del mecanismo CSRF y `Origin` (rebanada 1) se fusiona **antes** que cualquier PR que emita `Set-Cookie`, con su prueba sobre un arnés del árbol de pruebas que emite una cookie | Arnés: con cookie y sin cabecera, `403 csrf-invalid`; con un token CSRF válido de otra sesión, `403`; sin `Origin` en método no seguro, `403`. Se repite contra las rutas reales de refresco y cierre cuando existen |

El informe de archivo de S2 declara ambas cerradas con referencia a sus pruebas.

## Seguimientos transferidos desde el archivo de S1

| Id | Seguimiento | Entrega en S2 |
|---|---|---|
| F1a | Aserción directa de I24: ningún token de la cuenta queda vigente tras el restablecimiento | Prueba de integración que lo afirma de forma directa (hoy indirecta en `SessionLivenessIT` y `RotateRefreshTokenIT`) |
| F1b | Prueba del revocador entre instituciones | Prueba con una segunda institución de fixture: revocar en A no toca familias de B |
| F1c | Serializador común del JSON de auditoría | Constructor tipado en `shared.audit` y migración de los al menos 16 sitios en 8 clases (DA-13), con igualdad de bytes escritos |
| F2 | 8 mutantes sobrevivientes en `ClaimReader` | Pruebas del rango de `epochSecond`: época negativa, 0, `LAST_EPOCH_SECOND`, `LAST_EPOCH_SECOND + 1`, `Long.MAX_VALUE`, decimales y cadenas |
| F3 | `jti` en `SessionSecretsNotObservableTest` | El mapa `secrets` incluye el `jti` del acceso y del token restringido |

## Obligaciones heredadas de S1

| Id | Obligación | Entrega en S2 |
|---|---|---|
| **O2** | Tope superior de `requiredDelay` en `Delayed` | Tope configurable de la espera materializada (DA-11); el contador y la auditoría conservan el retardo de la política |
| **O6** | Propagar el origen (IP y agente) a los comandos de identidad | `AuthenticationCommand` y `RotateRefreshTokenCommand` reciben el origen que necesitan (IP para la vinculación de DA-8 y para la señal de DA-9); `IssueSessionCommand` ya lo lleva |
| **O9** | Un flujo completo (inicio, MFA, refresco, cierre) no deja contraseña, token de acceso, refresco, token restringido, token CSRF, secreto TOTP ni `Authorization` en ningún registro | Prueba de redacción de ADR-0005, verificación 12, con captura del registro estructurado |
| **O10** | Todo cambio de contraseña o de MFA cierra las demás sesiones (`docs/03` §4.5) | Al activarse una inscripción de MFA se revocan las demás familias vivas de la cuenta con motivo `mfa_change`. El cambio de contraseña con sesión es de un cambio posterior; el restablecimiento ya revoca todas (H2) |

También se atienden los pendientes de S1 que la exploración reasignó: corrección del `connect-src` de
`docs/03` §8.1 (mismo origen, DA-4 de S1), límites sin fila en §10 (DA-10), vista de sesiones activas
(DA-6) y que una violación de unicidad en la inscripción concurrente no responda `500` (O1, ver DA-7).

## Alcance

### Dentro de alcance

**Borde: CSRF y `Origin` (condición 3, DA-4 *a*)**

1. Filtro propio de CSRF y `Origin` en la cadena administrativa, construido sin bean como
   `AccessTokenAuthenticationFilter`, situado después del filtro de origen de la petición y antes del
   filtro de tokens y del limitador (una petición rechazada aquí no consume cupo).
2. `Origin` obligatorio en todo método no seguro de la cadena administrativa, contra la lista nueva
   `confia.web.allowed-origins`, validada al arrancar (vacía: ninguna petición mutadora pasa). La falta de
   `Origin` en un método no seguro se rechaza.
3. HMAC de doble envío exigido solo en las rutas que leen la cookie de sesión (refresco y cierre actual).
   Token `<aleatorio de 32 bytes>.<HMAC-SHA256(clave, aleatorio || SHA-256(refresco))>`, comparación en
   tiempo constante; el cálculo vive en `shared.security` (regla de confinamiento de firma y hash).
4. Cookie `__Host-confia_csrf`: sin `HttpOnly`, `Secure`, `Path=/`, sin `Domain`, `SameSite=Strict`, sin
   `Max-Age`; cabecera `X-CSRF-Token`. Se reemite en cada emisión o rotación, incluida la gracia.
5. Secreto nuevo `confia.security.csrf.key` por variable de entorno, con falla de arranque sin repetir el
   valor; ausente en el portal y el trabajador.
6. Código `csrf-invalid` (`403`), sin distinguir entre falta, discrepancia y origen no permitido.
7. El inicio de sesión se protege con `Origin` estricto, `Content-Type: application/json` obligatorio y
   `SameSite=Strict` de las cookies que emite; sin token CSRF anónimo previo.

**Borde: políticas, cookies y espera**

8. Generalización de `RateLimitProperties` a `confia.web.rate-limit.<política>.*` y tres políticas:
   `admin-login` (existente), `admin-mfa` y `admin-session` (DA-10).
9. Tope configurable de la espera materializada en `Delayed` (O2, DA-11).
10. Ayudante `SessionCookies` en `shared.web` para emitir y borrar la cookie de sesión
    `__Secure-confia_admin_sid` (`HttpOnly`, `Secure`, `SameSite=Strict`, `Path=/api/v1/auth/sessions`,
    sin `Domain`, sin `Max-Age`, DA-2 *a*) y la cookie CSRF. Guardia de arranque: un indicador de
    `Secure` desactivado para desarrollo local es imposible en el perfil de producción.
11. Sonda y configuración explícita del rechazo de propiedades desconocidas de Jackson 3 (sonda S-5 de
    S1), con prueba en rojo antes del controlador de inicio de sesión.

**Identidad: revocación y migración**

12. Migración `V9` que amplía el `CHECK` de `revoked_reason` con `logout`, `mfa_change` y
    `binding_changed` (este último según DA-8), con su prueba de contrato contra `SessionRevocationReason`.
13. Puerto y adaptador para localizar y revocar una familia por el hash de un refresco presentado (aunque
    esté consumido), con bloqueo solo de la familia, como la rotación; y caso de uso de cierre de todas
    las familias vivas de la cuenta con motivo `logout`, sobre `AccountSessionRevoker`.
14. F1a y F1b.

**Endpoints (nombres según DA-14 *a*)**

| # | Método y ruta | Autenticación | Limitación | Respuestas principales |
|---|---|---|---|---|
| 1 | `POST /api/v1/auth/sessions` (inicio de sesión) | Ninguna; `Origin` permitido | `admin-login` + materializador | `200` `status=authenticated` (acceso en el cuerpo, `Set-Cookie` de sesión y CSRF); `200` `status=second-factor-required` o `second-factor-enrollment-required` con token restringido en el cuerpo y sin cookies (DA-3 *a*); `401 authentication-failed` uniforme; `400`, `403 csrf-invalid`, `415`, `429`, `503` |
| 2 | `POST /api/v1/auth/mfa/verifications` | `Bearer` restringido, `purpose=mfa-verify` (y `mfa-enroll` para la primera verificación, ver DA-7) | `admin-mfa` + materializador | `200` sesión (como 1); `401 second-factor-invalid`; `401 token-invalid`/`token-expired`; `429`, `503` |
| 3 | `POST /api/v1/auth/mfa/enrollments` | `Bearer` restringido, `purpose=mfa-enroll` | `admin-mfa` | `201` secreto en base32 y 10 códigos de recuperación (`no-store`); `409 mfa-already-enrolled` si la credencial ya fue verificada; `401`, `429` |
| 4 | `POST /api/v1/auth/sessions/refresh` | Cookie de sesión + `X-CSRF-Token` + `Origin` | `admin-session` | `200` acceso nuevo y cookies rotadas; `401 session-expired` uniforme (borra las cookies); `403 csrf-invalid` |
| 5 | `DELETE /api/v1/auth/sessions/current` | Cookie + CSRF + `Origin` (DA-5 *a*) | `admin-session` | `204` ante toda petición bien formada, también con refresco desconocido o consumido; borra las cookies; revoca con `logout` |
| 6 | `DELETE /api/v1/auth/sessions` (cerrar todas) | `Bearer` de acceso, `LIVE_SESSION`, `Origin` | `admin-session` | `204`, revoca todas las familias vivas de la cuenta, incluida la actual, con `logout`; borra las cookies; `401` |
| — | `GET /api/v1/auth/sessions/current` | `Bearer`, `LIVE_SESSION` | ninguna | sin cambios |

Ninguna lleva `Idempotency-Key` (D3, W5). Todas responden `Cache-Control: no-store`.

15. Clase de ruta restringida en `AuthenticatedEndpoints` y principal propio del token restringido, de
    tipo y `ScopedValue` distintos de `AuthenticatedActor`, para que un token restringido nunca pueble
    `AuthenticatedActor.current()` ni lo consulten `TokenCurrentInstitutionProvider` y `SessionValidity`.
16. Inscripción según DA-7 *c* y DA-12 *a*: solo MFA al iniciar sesión (inscripción obligatoria y
    verificación); una credencial nunca verificada (`last_accepted_counter = -1`) puede reinscribirse,
    invalidando los códigos de recuperación previos; «MFA activa» pasa a significar secreto **verificado**
    (cambia el requisito del restablecimiento). La primera verificación correcta activa la credencial,
    emite la sesión y revoca las demás familias con `mfa_change` (O10). Una inscripción concurrente que
    choque con la unicidad responde `409`, no `500` (O1).
17. Vinculación por agente e IP al refrescar (DA-8), señal de relleno de credenciales por IP (DA-9).
18. O6 en `AuthenticationCommand` y `RotateRefreshTokenCommand`.
19. Códigos de Problem Details nuevos con texto es-HN en `problems.properties`: `authentication-failed`
    (401), `second-factor-invalid` (401), `session-expired` (401), `csrf-invalid` (403),
    `mfa-already-enrolled` (409).

**Transversal**

20. Serializador común del JSON de auditoría en `shared.audit` y migración de los sitios existentes
    (F1c, DA-13), antes de que S2 agregue asientos nuevos.
21. F2, F3 y O9.
22. Puerta de tiempo del inicio de sesión de `docs/03` §4.6 (200 intentos con cuenta existente y 200 con
    inexistente, diferencia de medianas de 50 ms como máximo) e igualdad exacta de cuerpo, estado y
    cabeceras del `401` (ADR-0005, verificación 7).
23. Inversiones de inventario (O3): requisitos de ausencia WE29, WE30 y WE31 de `web-edge`;
    `WebEdgeScopeExclusionInventoryTest` (cookie, uso del limitador y del materializador);
    `ProblemCatalogCoverageTest` y `ProblemCodeTest`; `PublicRouteAllowListTest`; el requisito «Solo un
    token de acceso completo… el restringido se rechaza en toda ruta»; y en `identity`, las ausencias de la
    dimensión IP, de la vinculación, de la revocación por MFA y del catálogo cerrado de motivos.
24. Líneas de `ProcessBeanPolicy` por cada bean y controlador nuevo, en el mismo PR (ADR-0024).
25. Instantánea OpenAPI de las rutas nuevas y regeneración de `packages/contracts`.
26. Documentación con notas fechadas, sin reescribir cuerpos: `docs/03` §4.4 (control por IP aplicado,
    DA-9), §4.5 (cookie, vinculación, revocación por MFA), §8.1 (`connect-src` de mismo origen), §8.3
    (alcance de CSRF de DA-4 *a*), §10 (filas de MFA y sesión de DA-10); `docs/05` (`allowed-origins`,
    clave CSRF y contradicción de `SESSION_COOKIE_DOMAIN` con la prohibición de `Domain`); `docs/ui-ux/04`
    y `docs/ui-ux/06` (catálogo de errores, retardo sin bloqueo); ADR-0005 (motivos nuevos e inscripción
    de dos momentos sin migración); `foundations-plan` (H1 y condición 3 cerradas).

### Fuera de alcance, con dueño nombrado

| Exclusión | Dueño |
|---|---|
| Vista de sesiones activas y columna `device_label` (DA-6 *b*) | Primer cambio de `apps/admin-web` que la muestre |
| Inscripción y baja voluntarias de MFA con sesión completa, step-up y política de rutas que exigen `otp` (DA-12 *a*) | Cambio 8 |
| Derivación de `mfa_required` desde el rol, cuenta desactivada, `admin_revoke` | Cambio 8 |
| Cambio de contraseña con sesión («todas salvo la actual») y «cerrar las demás» | Cambio que entregue el cambio de contraseña |
| URI `otpauth://` (emisor y etiqueta) | Cambio de la pantalla de inscripción |
| Bloqueo de 1 hora por relleno de credenciales (DA-9) | Cambio 11, con Redis y proxies de confianza |
| `reset/request`, `reset/confirm`, H3 y H4 | S3 `password-recovery-endpoints` |
| Purga de familias, tokens e `identity_login_backoff` | Cambio 9 |
| Avisos al titular (reutilización, vinculación, inscripción) | Cambio 14 |
| Prueba de atributos de cookie con Playwright (ADR-0005, verificación 11) | Primer cambio de `apps/admin-web` |
| Redis, llenado de `trusted-proxies`, tiempos de corte de nginx y Cloudflare, custodia de claves | Cambio 11 |
| Código de inscripción fuera de banda (DA-15 *b*) | Cambio 8 y cambio 14, si se reabre |

## Capacidades

> Contrato con `/sdd-spec`. Investigado contra `openspec/specs/` (diez capacidades publicadas).

### Nuevas

- Ninguna. Los endpoints, la revocación y el segundo factor pertenecen a `identity`; CSRF, `Origin`,
  cookies, políticas de tasa y la espera materializada, a `web-edge`.

### Modificadas

- **`identity`:** contrato HTTP de inicio de sesión, verificación e inscripción de MFA, refresco y
  cierres; motivos `logout`, `mfa_change` y `binding_changed` (`V9`); revocación por familia y de todas
  por cierre; reinscripción de una credencial nunca verificada y nueva definición de «MFA activa» (afecta
  a «Segundo factor exigido en el restablecimiento cuando la cuenta tiene MFA activa» y «Una cuenta sin
  MFA activa restablece solo con el enlace»); revocación por cambio de MFA; vinculación al refrescar;
  origen en los comandos (O6); señal por IP. **Inversión** de las ausencias «dimensión por dirección IP»,
  «aplicación de la vinculación» y «revocación por cambio de MFA», y del escenario del catálogo cerrado
  de motivos.
- **`web-edge`:** CSRF de doble envío y `Origin`; `confia.web.allowed-origins`; cookies; clase de ruta
  restringida y su principal; políticas `admin-mfa` y `admin-session`; tope de la espera; códigos nuevos
  del catálogo; rutas públicas nuevas. **Inversión** de «Ausencia de endpoints de inicio de sesión, MFA,
  refresco, cierre de sesión y recuperación, de cookie de sesión y de CSRF» (estrechada a la
  recuperación, que pasa a S3) y del requisito que rechaza el token restringido en toda ruta.
- **`build-integrity`:** inventarios invertidos, `ProcessBeanPolicy`, enumerador de rutas con la clase
  restringida, instantánea OpenAPI y `packages/contracts`, puerta de tiempo del inicio de sesión.
- **`audit-trail`:** solo si el serializador común (F1c) fija un requisito observable; la fase de
  especificación lo confirma contra «Reproducibilidad de la serialización canónica entre PL/pgSQL y
  Java».

## Enfoque

1. **Entrega, no arquitectura.** La arquitectura de sesión la fijan ADR-0005 y las decisiones de S1. S2
   conecta piezas probadas al borde HTTP; ningún controlador interpreta tokens ni calcula retardos.
2. **Defensa antes de exposición.** Primero CSRF y `Origin`, después las políticas y el tope de espera,
   después la revocación, y solo entonces los endpoints que emiten credenciales.
3. **Ubicación.** Controladores y DTO en `identity.web`; cookies, filtro CSRF y `Origin` en `shared.web`;
   HMAC de CSRF en `shared.security`; reglas de revocación, vinculación e inscripción en `identity`.
   `shared` no depende de `identity` (W3); ninguna espera fuera de `RequiredDelayMaterializer` (W4);
   toda escritura por `TransactionRunner`.
4. **Orden dentro de una petición:** cabeceras, origen de la petición, CSRF y `Origin`, filtro de token,
   limitador, controlador (materializador, caso de uso, `recordFailure`).
5. **Uniformidad.** `401 authentication-failed` idéntico para cuenta existente e inexistente;
   `401 session-expired` uniforme para todo rechazo de refresco; `204` uniforme del cierre actual.
6. **TDD estricto** (`openspec/config.yaml`): pruebas de ataque (CSRF, token restringido usado como
   sesión, enumeración) antes del código; concurrencia con dos transacciones reales (cierre frente a
   rotación, refresco desde dos pestañas, inscripción doble).
7. **Sondas antes del diseño:** rechazo de propiedades desconocidas de Jackson 3 en Spring Boot 4;
   privilegios de `V6` sobre la tabla de códigos de recuperación (para invalidarlos al reinscribir);
   `Secure` y `__Host-` en `http://localhost` en los navegadores usados; tiempos de `verify`.

## Áreas afectadas

| Área | Cambio |
|---|---|
| `apps/api/app/src/main/java/com/confia/shared/web/edge/` | Filtro CSRF y `Origin`, `SecurityChains`, `PublicEndpoints`, `AuthenticatedEndpoints` (clase restringida), `allowed-origins` |
| `apps/api/app/src/main/java/com/confia/shared/web/` | `SessionCookies`, principal restringido, `RateLimitProperties` generalizada, tope de `Delayed` |
| `apps/api/app/src/main/java/com/confia/shared/security/` | HMAC de CSRF y su clave, configuración del limitador |
| `apps/api/app/src/main/java/com/confia/shared/audit/` | Serializador común del JSON de auditoría |
| `apps/api/app/src/main/java/com/confia/identity/{domain,application,infrastructure,web}/` | Controladores, DTO, revocación por familia y por cuenta, inscripción, vinculación, O6, cableado |
| `apps/api/app/src/main/java/com/confia/shared/web/problem/` y `resources/i18n/problems.properties` | Cinco códigos nuevos |
| `apps/api/app/src/main/resources/db/migration/V9__*.sql` | `CHECK` de motivos ampliado |
| `apps/api/openapi/admin.openapi.json`, `packages/contracts` | Operaciones nuevas |
| Pruebas | Inventarios de O3, `ProcessBeanIsolationTest`, `SessionSecretsNotObservableTest`, `ClaimReader`, pruebas de arranque de los tres procesos |
| Documentación | `docs/03`, `docs/05`, `docs/ui-ux/04`, `docs/ui-ux/06`, ADR-0005, `foundations-plan` |

## Riesgos

| Riesgo | Probabilidad | Mitigación |
|---|---|---|
| Un token restringido se usa como sesión completa | Media | Principal y `ScopedValue` distintos; prueba de que nunca puebla `AuthenticatedActor.current()` |
| Bloqueo por inscripción perdida | Baja con DA-7 *c* | Reinscripción mientras la credencial no se verificó |
| Ventana de primera inscripción por quien conoce la contraseña (DA-15), extendida por DA-7 *c* hasta la primera verificación | Media | Auditoría `identity.mfa.enrolled` en cada (re)inscripción; riesgo escrito; revisión en el cambio 8 |
| Códigos de recuperación sin retroceso propio | Media | Limitador `admin-mfa` por IP; el diseño evalúa sumar sus fallos al contador TOTP |
| Rechazo de propiedades desconocidas sin comprobar | Media | Sonda S-5 y prueba en rojo antes del controlador |
| Enumeración por diferencia de respuesta o de tiempo | Media | Igualdad exacta del `401` y puerta de tiempo de §4.6 |
| Autodenegación por IP compartida (NAT, proxy con `trusted-proxies` vacío) | Alta en producción, declarada | Valores en configuración (DA-10), señal sin bloqueo (DA-9), llenado de proxies en el cambio 11 |
| Agotamiento del semáforo del materializador con esperas largas | Media | Tope de la espera (DA-11) |
| Desincronización de cookies con refresco desde dos pestañas | Media | La cookie CSRF se reemite también en la gracia con el mismo vínculo; prueba de concurrencia |
| Cierre frente a rotación | Media | Ambos toman el bloqueo de la familia; prueba con dos transacciones reales |
| Clave CSRF en variable de entorno | Cierta, declarada | Falla de arranque sin repetir el valor; rotarla invalida los CSRF vigentes, que se reemiten en el siguiente refresco |
| Falsos positivos de la vinculación (CGNAT o cambio de red más actualización del navegador) | Baja | Límite declarado; el costo es un nuevo inicio de sesión |
| Cookies `Secure` en desarrollo local sin HTTPS | Media | Indicador por perfil con guardia de arranque en producción |
| Sin `apps/admin-web`: sin prueba de navegador de los atributos de cookie | Cierta | Pruebas de `Set-Cookie` en el servidor; Playwright con la primera pantalla |
| Subestimación de tamaño (factor histórico de 2,2 a 7,97) | Alta | Cadena apilada; replanificación al superar el 50 %; partición en dos cambios si `tasks.md` pasa de 15 tareas |
| Duración de `verify` (5,5 min sin mutación, 13,6 con ella; 400 verificaciones Argon2id nuevas) | Media | Medición en la sonda; contenedor compartido |

## Plan de reversión

No existe ningún entorno desplegado ni dato real.

1. Cada PR se revierte con su commit de fusión, en orden inverso de la cadena.
2. **Nunca se revierte el PR de CSRF mientras exista en `main` un PR que emita `Set-Cookie`:** se revierten
   antes los de endpoints (refresco y cierre, segundo factor, inicio de sesión).
3. Revertir el inicio de sesión exige revertir antes el segundo factor y el refresco, que dependen de él.
   Revertir el limitador aplicado al inicio de sesión sin revertir el endpoint reabre H1 y no se permite.
4. `V9` solo amplía un `CHECK`. Antes de producción, revertirla es eliminar la migración y regenerar
   jOOQ. Una vez aplicada en un entorno compartido nunca se edita; se revierte con una migración nueva
   que restaure el `CHECK`, y solo si no existen filas con los motivos nuevos.
5. El serializador común se revierte por separado: los bytes escritos son idénticos antes y después.
6. Las notas documentales se revierten con su PR; ninguna reescribe un cuerpo original.

## Dependencias

- **Consume:** `IssueSession`, `RotateRefreshToken`, `SessionLiveness`, `AccountSessionRevoker`,
  `AuthenticateWithPassword`, `VerifyTotpCode`, `ConsumeRecoveryCode`, `EnrollTotpSecondFactor`,
  `AccessTokenIssuer`/`AccessTokenVerifier`, `AccessTokenAuthenticationFilter`, `RateLimiter`,
  `RequiredDelayMaterializer`, `RequestOrigin`, `TransactionRunner`, `AuditLogWriter`, Problem Details.
- **Entrega a S3 y al cambio 9:** sesiones reales, cookies, CSRF y `Origin`, políticas de tasa
  generalizadas.
- **Herramienta:** ninguna dependencia Maven nueva. Docker para las pruebas de integración.

## Criterios de éxito

- [ ] `./mvnw verify` en `apps/api` en verde, con la puerta de mutación; cobertura global de `app` al
      menos 80 %, `domain` de `identity` al menos 95 %; mutación de PIT al menos 80 % en `domain`;
      los 8 mutantes de `ClaimReader` (F2) eliminados.
- [ ] **Condición 3:** una petición mutadora con la cookie y sin `X-CSRF-Token` recibe `403`; con un token
      CSRF de otra sesión, `403`; sin `Origin` o con un `Origin` no permitido en método no seguro, `403`.
      Ningún commit de `main` emite `Set-Cookie` antes del PR del mecanismo CSRF.
- [ ] **H1:** N fallos desde una IP producen `429` con `Retry-After` antes del caso de uso, y
      `identity_login_backoff` deja de crecer desde ese punto. Ningún commit de `main` expone el inicio
      de sesión sin el limitador.
- [ ] Inicio de sesión: `200` con sesión o con token restringido según el desenlace; `401
      authentication-failed` idéntico en cuerpo, estado y cabeceras para cuenta existente e inexistente;
      la diferencia de medianas de 200 + 200 intentos no supera 50 ms.
- [ ] `Set-Cookie` de sesión con `HttpOnly`, `Secure`, `SameSite=Strict`, `Path=/api/v1/auth/sessions`,
      sin `Domain` ni `Max-Age`; cookie CSRF con `__Host-`, `Path=/`, sin `HttpOnly`.
- [ ] Un token restringido abre solo su ruta y su `purpose`, y nunca puebla `AuthenticatedActor.current()`.
- [ ] Una credencial nunca verificada se reinscribe e invalida los códigos previos; una verificada
      responde `409`; la primera verificación revoca las demás familias con `mfa_change` (O10); una
      inscripción concurrente no responde `500`.
- [ ] Refresco con cookie, CSRF y `Origin` válidos rota y reemite ambas cookies (también en la gracia);
      todo rechazo responde `401 session-expired` y borra las cookies.
- [ ] El cierre actual responde `204` y revoca con `logout` aunque el acceso haya vencido; «cerrar todas»
      revoca el 100 % de las familias vivas de la cuenta y ninguna ajena (F1b).
- [ ] F1a: tras un restablecimiento, ningún token de la cuenta queda vigente, afirmado de forma directa.
- [ ] O9: un flujo completo no deja contraseña, tokens, token CSRF, secreto TOTP ni `Authorization` en
      ningún registro; F3 incluye el `jti`.
- [ ] La espera materializada nunca supera su tope configurado (O2).
- [ ] El JSON de auditoría de los sitios migrados es igual byte a byte (F1c).
- [ ] Las inversiones de inventario existen y cada una falla con su control negativo; las notas
      documentales existen y ninguna reescribe un texto original.

## Pronóstico de tamaño y entrega

Estrategia DA-1 *a*: un cambio, cadena de PR apilados sobre `main` (`stacked-to-main`), con la regla
permanente del propietario «partir solo si hace falta», y un máximo de 800 líneas efectivas por PR
(`docs/15-flujo-de-trabajo-git.md` §3). Si `tasks.md` supera 15 tareas, la fase de tareas decide partir
S2 en dos cambios (DA-1 *b*).

**Nominal:** unas 3 300 líneas efectivas. **Realista** (factor 2,2): unas 7 300, rango de 6 600 a 9 900,
en **10 a 14 PR**.

| Orden | Rebanada | Contenido | Nominal | Realista |
|---|---|---|---|---|
| 1 | `csrf-and-origin-protection` | Filtro CSRF y `Origin`, HMAC, clave, `allowed-origins`, `csrf-invalid`, prueba de la condición 3 sobre arnés | 330 | 730 |
| 2 | `audit-json-serializer` | Serializador común y migración de los sitios (F1c) | 150 | 330 |
| 3 | `edge-policies-and-delay-cap` | `RateLimitProperties` generalizada, políticas `admin-mfa` y `admin-session`, tope de `Delayed` (O2), `SessionCookies`, sonda S-5 | 280 | 620 |
| 4 | `session-revocation-v9` | `V9`, revocación por familia y por cuenta, F1a, F1b | 400 | 880 (2 PR) |
| 5 | `login-endpoint` (cierra H1) | Controlador, DTO, `authentication-failed`, limitador, materializador, cookies, O6, OpenAPI, señal de DA-9 | 600 | 1 320 (2 PR) |
| 6 | `second-factor-endpoints` | Clase restringida, principal, verificación, inscripción (DA-7 *c*), O10 | 650 | 1 430 (2 PR) |
| 7 | `refresh-and-logout-endpoints` | Refresco con cookie y CSRF, vinculación (DA-8), cierres, borrado de cookies | 550 | 1 210 (2 PR) |
| 8 | `closing-and-follow-ups` | F2, F3, O9, puerta de tiempo, inversiones de inventario, `packages/contracts`, notas documentales | 350 | 770 |
| | **Total** | | **3 310** | **7 290** |

**Órdenes duros:**

1. La rebanada 1 (CSRF y `Origin`) se fusiona **antes** que cualquier PR que emita `Set-Cookie`, incluido
   el borrado de cookies (condición 3).
2. La 3 antes de la 5 (políticas, tope y cookies).
3. La 4 antes de la 6 (`mfa_change`) y de la 7 (`logout`, `binding_changed`).
4. La 5 antes de la 6 (el inicio de sesión emite el token restringido) y de la 7.
5. El limitador se aplica en el mismo PR que expone el inicio de sesión (H1).
6. La 2 antes de cualquier rebanada que agregue asientos de auditoría (4 a 7).

Respecto de la exploración, el serializador de auditoría se adelanta de la posición 7 a la 2, para que S2
no agregue sitios nuevos de concatenación que luego habría que migrar.

## Decisiones pendientes de confirmación del propietario

Posición de trabajo de esta propuesta: la recomendación de la exploración. Ninguna se considera aprobada
hasta que el propietario la confirme.

| Decisión | Posición de trabajo | Efecto si el propietario elige otra opción |
|---|---|---|
| **DA-6** Vista de sesiones activas | *b)* Diferirla a la primera pantalla de `apps/admin-web`, con su propio cambio | *a)* añade `device_label` a `V9`, dos rutas y una decisión de datos personales (`docs/08`) |
| **DA-8** Vinculación por agente e IP | *b)* Si cambian ambos respecto de la creación, rechazar el refresco y revocar la familia con `binding_changed` | *a)* elimina `binding_changed` de `V9` y deja solo un evento de auditoría |
| **DA-9** Alcance del control por IP de H1 | *b)* Capas 1 y 2 del limitador más la fila «5 cuentas distintas en 10 minutos» como señal (`WARN` estructurado y asiento de auditoría), sin bloqueo | *a)* reduce la rebanada 5; *c)* añade un bloqueo de 1 hora con riesgo de autodenegación |
| **DA-10** Límites de tasa sin fila en §10 | *b)* `admin-mfa` (20 por minuto por IP, umbral de fallos 10 en 10 minutos) y `admin-session` (120 por minuto por IP), en configuración, con nota en §10 | *c)* deja MFA, refresco y cierres sin límite propio |
| **DA-11** Techo de la espera (O2) | *b)* Tope configurable de la espera materializada, valor de partida 30 s; contador y auditoría conservan el retardo de la política | *a)* mantiene 900 s y el riesgo de agotar el semáforo; *c)* contradice «el retroceso demora, no deniega» |
| **DA-13** Serializador del JSON de auditoría | *a)* Constructor tipado en `shared.audit` y migración de los al menos 16 sitios, con igualdad de bytes | *b)* o *c)* retiran la rebanada 2 y F1c queda abierto |
| **DA-14** Nombres de rutas y códigos | *a)* Estilo de recursos con subruta de acción solo para el refresco, y los cinco códigos de la sección de alcance | *b)* o *c)* cambian el contrato OpenAPI y los nombres de las pruebas |
| **DA-15** Ventana de primera inscripción | *a)* Aceptar el riesgo, escribirlo y auditar la inscripción; revisión en el cambio 8 | *b)* bloquea la inscripción hasta los cambios 8 y 14 |

**Precisión de DA-7 *c* que el diseño debe fijar y el propietario confirmar (no reabre DA-7):** con la
reinscripción mientras la credencial no se verificó, el inicio de sesión de una cuenta con secreto sin
verificar vuelve a devolver `SecondFactorEnrollmentRequired`, de modo que el usuario nunca recibe un
token `mfa-verify` para su primer código. Posición de trabajo: la ruta de verificación acepta también el
token `mfa-enroll` cuando la credencial está sin verificar, y esa primera verificación correcta activa la
credencial, emite la sesión y revoca las demás familias con `mfa_change`. Consecuencia declarada: hasta esa
primera verificación, quien conozca la contraseña puede reemplazar el secreto (la ventana de DA-15 se
extiende hasta ella).

**Nota (2026-10-20, aprobación del propietario).** El propietario aprueba esta propuesta. Confirma las posiciones de trabajo de DA-6 *b*, DA-8 *b*, DA-9 *b*, DA-10 *b*, DA-11 *b*, DA-13 *a*, DA-14 *a* y DA-15 *a*, y la precisión de DA-7 *c*: mientras la credencial no esté verificada, la verificación acepta también el token `mfa-enroll`, y esa primera verificación activa la credencial, emite la sesión y revoca las demás familias con `mfa_change`. Comprobación previa al diseño: `V6__add_mfa_required_and_create_crypto_mfa_tables.sql` concede `SELECT, INSERT, UPDATE` sobre `identity_mfa_recovery_code` a `confia_admin_app`, por lo que los códigos de recuperación previos se pueden invalidar sin migración.
