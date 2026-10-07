# Exploración: borde web y sesiones del personal (cambio 7 de F0, parte 4 de 4)

> **Alcance de este documento.** Esta exploración se hizo bajo el nombre
> `session-tokens-and-web-layer` y cubre la parte 4 completa del cambio 7. El 2026-10-04 el
> propietario del producto decidió partirla en **dos cambios SDD** (ver «Decisiones del propietario
> del 2026-10-04», al final):
>
> 1. `web-edge-foundations` — cortes C1a, C1b, C2 y C3 de la sección 5.
> 2. `session-tokens-and-web-layer` — cortes C4a a C8 de la sección 5.
>
> El documento describe **ambos** cambios. Se archiva con `web-edge-foundations`, que es el primero
> en ejecutarse, y `session-tokens-and-web-layer` lo cita como su exploración.
>
> **Procedencia.** La sesión de exploración no tenía permiso de escritura, así que el artefacto
> original existe solo en Engram (proyecto `confia`, observación #578, tema
> `sdd/session-tokens-and-web-layer/explore`). Este archivo lo transcribe al español neutro
> profesional sin añadir hallazgos. **Ninguna sonda se ejecutó**: la exploración fue de solo
> lectura.

## 1. Estado actual (verificado en código)

- No existe ningún controlador ni filtro de negocio. La única capa web es `shared.web.openapi`
  (`ContractSchemas`, `ProcessApiInfo`).
- Spring Security **no** es dependencia del proyecto: solo llega `spring-security-crypto` 7.0.0 de
  forma transitiva. No hay cliente de Redis ni biblioteca de JWT.
- Los tres puntos de entrada excluyen `DataSourceAutoConfiguration`. No hay `DataSource`,
  `TransactionRunner`, `DSLContext` ni `Clock` de producción registrados como bean, y los casos de
  uso de identidad tampoco son beans.
- Las pruebas `*Test` de arranque (`OpenApiContractSnapshotTest`, `ProcessBeanIsolationTest`,
  `OpenApiProcess`) arrancan el proceso real **sin base de datos**. Retirar la exclusión de
  `DataSourceAutoConfiguration` las rompe, salvo que se tome una decisión explícita.
- `requiredDelay` lo llevan `AuthenticationDecision`, `VerifyTotpCodeDecision` y
  `ResetPasswordDecision`, y **nadie lo materializa**. Su contrato de seis puntos está en el
  `design.md` archivado del cambio 1 de identidad (decisión 1).
- `AuditEntry.sourceIp` y `AuditEntry.userAgent` son siempre `null`. Ningún comando ni el
  `SecurityContext` llevan la IP: hay que añadir el origen de la petición a los comandos.
- `ProcessBeanPolicy`: el portal **prohíbe** `com.confia.identity`; el proceso administrativo solo
  permite `bootstrap.admin` y `shared.web.openapi`. Registrar algo nuevo exige `@Import` más una
  edición de `ProcessBeanPolicy` (ADR-0024, que nombra a este cambio como su primer consumidor).
- `identity_staff_account` no tiene columna de estado ni de desactivación.
- `EnrollTotpSecondFactor` inscribe sin confirmar con un primer código.
- `ConsumeRecoveryCode` no tiene retroceso propio.
- No existe catálogo de mensajes del backend (`messages*.properties`).
- `IdentityScopeExclusionInventoryTest.noClassOfIdentityReferencesSessionsOrRefreshTokens` se vuelve
  falsa con la primera emisión de refresco: hay que invertirla.
- Entorno: la brecha PKIX de Windows bloquea las descargas nuevas desde Maven Central
  (`apps/api/pom.xml`, líneas 130 a 135).
- **Hallazgo:** Spring Security **no firma con EdDSA**. `NimbusJwtEncoder` no admite curvas de
  Edwards (incidencia `spring-projects/spring-security#17098`, abierta; PR #19175). Nimbus, para
  Ed25519, exige Google Tink, que en las versiones 9.x es una dependencia opcional pero obligatoria
  para ese algoritmo. **El supuesto de ADR-0005 queda en riesgo.**

## 2. Inventario de obligaciones

### Condiciones duras de aceptación

| Id | Condición | Fuente |
|---|---|---|
| H1 | El inicio de sesión no se fusiona sin un control por IP que exista, esté probado y esté operativo | `openspec/changes/foundations-plan/exploration.md`, líneas 108 a 138 |
| H2 | El restablecimiento debe revocar **todas** las familias de refresco; escenario I24 | `foundations-plan/exploration.md`, líneas 146 a 152; `openspec/specs/identity/spec.md`, líneas 1169 a 1190 |
| H3 | La solicitud de restablecimiento no se fusiona sin el límite de 10 por hora por IP | `foundations-plan/exploration.md`, líneas 154 a 157; spec de identidad, líneas 1233 a 1254 |
| H4 | `202 Accepted` + mensaje del catálogo i18n + `Referrer-Policy: no-referrer` + puerta de tiempo de `docs/03` §4.6 (200 + 200 intentos, diferencia de medianas ≤ 50 ms) sobre HTTP | `docs/09`, líneas 127 y 128; spec de identidad, líneas 250 a 258; `LoginTimingReportIT` y `PasswordResetRequestTimingReportIT` lo delegan |

### Brechas publicadas en la especificación de identidad

- Líneas 40 a 75 (MFA): traducir `SecondFactorRequired` y `SecondFactorEnrollmentRequired` a un
  token restringido.
- Líneas 474 a 492: dimensión por IP; el escenario se invierte.
- Líneas 1169 a 1190: revocación de sesiones al restablecer.
- Línea 1218: la IP del aviso al titular la aporta este cambio; el envío es del cambio 14.
- Líneas 1233 a 1254: límite por IP y respuesta HTTP de la recuperación.
- Líneas 1276 a 1296: restablecimiento administrativo. El endpoint depende del rol del cambio 8,
  por lo que se propone moverlo.

### Pendientes del roadmap y de las especificaciones

- Adaptador de `CurrentInstitutionProvider` sobre el token, más la prueba de ADR-0009
  (`docs/09`, líneas 95 a 103).
- Problem Details, y la distinción entre `institution-not-found` e `institution-inactive` solo tras
  confirmar que el identificador viene del token.
- DTO de instituciones con tope de 200 y 500 caracteres: **condicional** al primer endpoint de
  administración de instituciones, que no se entrega aquí.
- `Idempotency-Key` en el borde: `400`, `Idempotent-Replay`, traducción a `200`/`409`/`422`
  (`docs/09`, líneas 136 a 141; `build-integrity`, líneas 983 a 1000).
- Quién materializa `requiredDelay`.
- Dimensión por IP: el control aquí, Redis en el cambio 11.
- `DataSource` de producción.
- Configuración pública + `@Import` + `ProcessBeanPolicy`.
- Mecánica de RBAC con guarda de arranque (`foundations-plan/exploration.md`, líneas 83 a 91),
  ambigua frente al cambio 8.

### De `docs/03-seguridad.md` y ADR-0005

JWT EdDSA de 10 minutos; `aud`/`iss` y claves por dominio; rotación semestral con dos claves;
refresco opaco de 32 bytes con SHA-256 y rotación; detección de reutilización; ventana de gracia de
10 segundos por dispositivo; inactividad de 30 minutos; vigencia absoluta de 12 horas; revocación
por familia y de todas; vinculación de agente de usuario e IP; revocar las demás sesiones al cambiar
contraseña o MFA; vigencia de `sid` en escrituras sensibles; cookie `__Secure-confia_admin_sid`;
CSRF de doble envío más `Origin`; verificación de claves en el arranque; límites de `docs/03` §10.

## 3. Decisiones técnicas y recomendación

### Firma de tokens

| Opción | Descripción |
|---|---|
| A | `spring-boot-starter-oauth2-resource-server` + Nimbus + Tink. **Descartar.** |
| B | Nimbus directo + Tink, sin envoltorios de Spring |
| C | JWS compacto mínimo propio sobre `java.security.Signature("Ed25519")`: `alg` fijo, `kid` obligatorio, encabezados adicionales rechazados. Cero dependencias |
| D | Enmendar ADR-0005 a ES256 o RS256 |

**Recomendación:** C si la sonda muestra que Tink no converge; B si converge; D solo con decisión
del propietario. Spring Security se usa únicamente como cadena de filtros. Claves en variable de
entorno por proceso, con `kid`, anillo de dos claves; el arranque aborta si falta la clave propia o
si sobra la privada del otro dominio. JWKS: anillo en memoria; el endpoint se difiere porque no hay
un segundo verificador.

### Almacén del refresco

PostgreSQL (ADR-0005; no se reabre): `identity_refresh_token_family` e `identity_refresh_token`, con
seguridad de fila, sin `DELETE`, rotación con `UPDATE` condicional. Punto delicado: que la ventana de
gracia «devuelva el par ya emitido» choca con guardar solo el hash; requiere sonda.

### Cadena de filtros

- **Administración:** sin estado, denegar por defecto, lista blanca de rutas públicas, cabeceras de
  seguridad, CSRF solo donde la credencial sea una cookie.
- **Portal:** cadena que deniega todo + verificación de claves en el arranque (no hay encargados
  todavía e `identity` está prohibido en el portal).
- **Trabajador:** nada.
- `shared` no puede depender de `identity`: la vigencia de `sid` se expone como puerto en
  `shared.security`, implementado por `identity`.

### MFA

Token restringido de vida corta (unos 5 minutos) con un claim de uso (`mfa-verify` o `mfa-enroll`),
sin familia ni refresco. El inicio de sesión responde `200` con un estado; `/auth/mfa/verify` acepta
TOTP o código de recuperación; `/auth/mfa/enroll`. La sesión completa solo se emite tras un código
válido (`amr=[pwd,otp]`).

### Control por IP antes de Redis

Puerto `RateLimiter` en `shared.security` con adaptador en memoria (ventana deslizante, memoria
acotada, agregación de IPv6 por /64), ejecutado **antes** del caso de uso. El adaptador de Redis
llega en el cambio 11. PostgreSQL se descarta porque recrea el vector de crecimiento y denegación de
servicio de H1. La IP se obtiene solo a través de proxies de confianza: un `X-Forwarded-For`
falsificable anula H1.

**Materialización del retardo:** `Thread.sleep` en un hilo virtual con un `Semaphore` acotado;
respuesta `503` uniforme **antes** de procesar cuando está saturado. El retardo se suma (no absorbe
el tiempo ya transcurrido) y se abandona al cerrar la conexión. Sonda S5 pendiente. Regla de
ArchUnit acotada.

### Problem Details

RFC 9457, con `type` derivado de los códigos estables de `DomainException` (ADR-0019) y un catálogo
i18n es-HN nuevo. Catálogo mínimo propuesto:

| Código | Estado HTTP |
|---|---|
| `validation-failed` | 400 |
| `idempotency-key-missing` | 400 |
| `idempotency-conflict` | 409 |
| `idempotency-payload-mismatch` | 422 |
| `authentication-failed` | 401, único |
| `token-invalid`, `token-expired` | 401 |
| `forbidden` | 403 |
| `too-many-requests` | 429 + `Retry-After` |
| `capacity-exceeded` | 503 |
| `institution-not-found`, `institution-inactive` | solo autenticado |
| `internal-error` | 500 |

### Idempotencia

Construir el mecanismo HTTP genérico y probarlo con un controlador de prueba. **No** aplicarlo a
inicio de sesión, refresco, MFA ni restablecimiento: `IdempotentExecutor` persiste y reproduce el
cuerpo de la respuesta, de modo que los tokens quedarían en claro en `shared_idempotency_key`, contra
ADR-0005 (líneas 268 y 269) y las reglas 11 y 13 de `CLAUDE.md`; además, el hash de la carga útil
incluiría la contraseña. Contradice `docs/09`, líneas 114 y 115, por lo que exige decisión del
propietario.

## 4. Decisiones abiertas del propietario (al cierre de la exploración)

1. Firma: EdDSA con su costo frente a ES256/RS256.
2. Transporte del token de acceso: refresco en cookie `HttpOnly` y acceso en el cuerpo como
   `Bearer`, frente a ambos en cookie.
3. `Idempotency-Key` en el inicio de sesión y demás endpoints de identidad.
4. Aceptar el limitador en memoria como «tope funcional equivalente» de H1; si falla abierto o
   cerrado; valores donde `docs/03` §4.4 y §10 difieren.
5. Alcance del portal.
6. Alcance de la guarda de RBAC: aquí solo denegar por defecto + `sid`; la matriz, la cobertura de
   MFA por ruta y `mfa_required` al cambio 8.
7. Endpoint de JWKS ahora o diferido.
8. `DataSource` solo en administración frente a los tres procesos.
9. Endpoints de sesiones activas (`GET`/`DELETE`) aquí o después.
10. Cuenta desactivada (no hay columna).
11. Vinculación de agente de usuario e IP en el refresco.
12. Exigir confirmar el primer código tras inscribir MFA.
13. Claim de permisos vacío hasta el cambio 8.
14. Dividir en dos cambios SDD.

## 5. Corte propuesto

Líneas efectivas, sin `openspec` ni código generado. Recomendación: partir en dos cambios SDD en el
límite entre C3 y C4: **7d-1 `web-edge-foundations`** (C1a a C3) y **7d-2
`session-tokens-and-web-layer`** (C4a a C8). Total nominal de unas 8 150 líneas en 14 pull requests;
con el factor histórico de 1,3 a 1,5, entre 10 000 y 12 000.

| Corte | Contenido | Líneas |
|---|---|---|
| C1a | Cableado de producción: `DataSource`, `TransactionRunner`, `DSLContext`, `Clock`, beans de identidad, `@Import` + `ProcessBeanPolicy`, retirar la exclusión en administración | ~450 |
| C1b | Cadena base de Spring Security + Problem Details + i18n + filtro de contexto de petición (UUID del servidor, IP, agente de usuario) + ArchUnit de la capa web | ~700 |
| C2 | `RateLimiter` en memoria + `429` + materializador del retardo. Cierra parte de H1 y el contrato de `requiredDelay` | ~650 |
| C3 | Idempotencia en el borde con controlador de prueba (brecha B7) | ~450 |
| C4a | Núcleo del token: emisor y verificador, anillo de claves, token restringido, verificación de arranque | ~600 |
| C4b | Filtro + adaptador de `CurrentInstitutionProvider` + `GET /session` + prueba de ADR-0009 | ~500 |
| C5a | `V8` de familias y tokens + repositorios + emisión + revocación | ~700 |
| C5b | Rotación, gracia, reutilización, inactividad, vigencia absoluta, `sid` | ~750 |
| C6a | Inicio de sesión. Cierra H1 con prueba: N fallos desde una IP topan en `429` e `identity_login_backoff` deja de crecer; puerta de tiempo HTTP del inicio de sesión | ~700 |
| C6b | MFA: `verify` y `enroll` | ~600 |
| C6c | `refresh`, `logout`, `logout-all` + cookie + CSRF + redacción de registros | ~700 |
| C7a | El restablecimiento revoca todas las familias en la **misma** transacción + invertir el inventario + I24 (H2) | ~450 |
| C7b | Endpoints de recuperación: `202`, `no-referrer`, 10 por hora por IP, puerta de tiempo (H3, H4) | ~650 |
| C8 | Cierre documental | ~300 |

**Orden duro:** C2 antes de C6a; C5 antes de C7a; C7a antes de C7b; C4 antes de todo endpoint
autenticado.

## 6. Riesgos

- Spring Security sin EdDSA, y Tink es pesado.
- La brecha PKIX local bloquea dependencias nuevas.
- Retirar la exclusión de `DataSource` rompe las pruebas `*Test` que hoy no necesitan Docker.
- Ventana de gracia con un refresco que solo guarda el hash.
- IP falsificable.
- Limitador en memoria: por proceso, pierde el estado al reiniciar, memoria acotada.
- Puerta de tiempo de 200 + 200 con Argon2id y retroceso: usar identificadores distintos, perfil
  piso y presupuesto de 8 minutos; no puede ser solo un reporte.
- Las respuestas retardadas retienen sockets.
- Claves privadas en variables de entorno hasta el cambio 11.
- Los cambios de firma en casos de uso tocan muchas pruebas, con riesgo de verdes por la razón
  equivocada.
- El tamaño supera con mucho el límite de 15 tareas.
- Supuesto de mismo origen entre API y SPA (nginx) sin verificar.

## 7. Preparación para la propuesta

Lista, con condiciones: obtener las decisiones 1, 2, 3, 4, 6 y 14 del propietario, y ordenar las
sondas: Tink frente a `dependencyConvergence`/Trivy; arranque sin base de datos con el `DataSource`
de producción; sonda S5 de hilos virtuales bajo Tomcat. Ninguna sonda se ejecutó.

## Decisiones del propietario del 2026-10-04

Registradas en Engram (proyecto `confia`, observaciones #579 y #580). Son vinculantes para
`web-edge-foundations`. Las decisiones abiertas 1, 2, 7, 9, 10, 11, 12 y 13 de la sección 4 siguen
abiertas y son de `session-tokens-and-web-layer`.

- **D1 — División (decisión abierta 14).** La parte 4 se ejecuta como dos cambios SDD:
  `web-edge-foundations` (C1a, C1b, C2, C3; unas 2 250 líneas en 5 pull requests) y, después,
  `session-tokens-and-web-layer` (C4a a C8: tokens, familias de refresco, inicio de sesión, MFA,
  endpoints de recuperación y cierre de las condiciones duras H1 a H4; unas 5 900 líneas).
- **D2 — Limitador por IP (decisión abierta 4).** En memoria, por proceso; **falla cerrado** cuando
  su tabla acotada se llena; agrupa IPv6 por /64; se ejecuta antes del caso de uso. Se acepta por
  escrito como el control de H1 hasta que el cambio 11 traiga Redis, y la limitación (por proceso,
  se pierde al reiniciar) debe declararse.
- **D3 — `Idempotency-Key` en identidad (decisión abierta 3).** **No** se aplica a los endpoints de
  identidad (inicio de sesión, refresco, MFA, recuperación), porque `IdempotentExecutor` persiste y
  reproduce el cuerpo de la respuesta y los tokens quedarían guardados en claro. El borde HTTP
  genérico de idempotencia (cabecera obligatoria con rechazo `400`, `Idempotent-Replay`, traducción a
  `200`/`409`/`422`) **sí** se construye aquí y se prueba con un controlador solo de prueba.
  `docs/09-roadmap-y-fases.md` (entregable 6, y las líneas 114-115 y 136-141) debe corregirse en
  consecuencia.
- **D4 — RBAC (decisión abierta 6).** En este cambio solo se entrega denegar por defecto con lista
  blanca pública explícita, más el punto de enganche de la vigencia de sesión. La matriz de roles, la
  cobertura de MFA por ruta y el claim de permisos son del cambio 8.
- **D5 — `DataSource` (decisión abierta 8).** `DataSource` de producción **solo** en el proceso
  administrativo. Portal y trabajador siguen excluyendo `DataSourceAutoConfiguration` hasta que
  tengan un consumidor, de modo que sus pruebas `*Test` de arranque siguen sin necesitar Docker. Las
  pruebas de arranque del proceso administrativo requieren una decisión (sonda 2).
