# Diseño: núcleo de tokens, familias de refresco y revocación al restablecer (parte 4b, S1)

- **Cambio:** `session-tokens-and-web-layer` (F0, cambio 7, parte 4b, primer cambio de tres)
- **Fase:** diseñar
- **Fecha:** 2026-10-06
- **Estado:** pendiente de aprobación del propietario
- **Entradas:** `proposal.md` (aprobada el 2026-10-06 con las once recomendaciones; Engram #598 y
  #599), `exploration.md` (secciones 1, 2, 7 y 9; Engram #578), el diseño archivado
  `openspec/changes/archive/2026-10-06-web-edge-foundations/design.md`, ADR-0005, ADR-0009, ADR-0015,
  ADR-0022, ADR-0023 y ADR-0024, `docs/03-seguridad.md` §4.5 a §4.7, §11 y §12, y el código de `main`
  en `2a599f5`. La especificación de este cambio todavía no existe; la fase de especificación puede
  correr en paralelo y este diseño le entrega correcciones en la sección 9.
- **Modo de TDD:** estricto. Ejecutor: `./mvnw verify` en `apps/api`, con JDK 25 y Docker.
- **Línea base de la cadena:** `main` en `2a599f5`.

## 1. Enfoque técnico

S1 entrega todo el mecanismo de sesión sin ningún endpoint que entregue una credencial a un cliente.
El criterio de ubicación es W3 (`shared` nunca depende de `identity`): **todo lo que el filtro
necesita para reconocer un token vive en `shared`**, y **todo lo que decide la vida de una sesión vive
en `identity`**, que implementa el puerto `SessionValidity` de `shared.security`.

```
                 ┌──────────────────────── proceso administrativo ────────────────────────┐
petición ─► SecurityHeadersFilter ─► RequestContextFilter (RequestOrigin por ScopedValue)    │
  │        ─► FilterChainProxy                                                               │
  │             AccessTokenAuthenticationFilter  (shared.web.authentication, nuevo)          │
  │               Authorization: Bearer ─► AccessTokenVerifier (shared.security.token)       │
  │                 falla ─► 401 token-invalid | token-expired + WWW-Authenticate            │
  │                 ruta LIVE ─► SessionValidity.isActive(actor) ─► identity (V8)            │
  │                 éxito ─► AuthenticatedActor en ScopedValue + Authentication de Spring    │
  │             autorización: PublicEndpoints ─► permitAll                                   │
  │                           AuthenticatedEndpoints ─► authenticated   (nuevo, DA-7)        │
  │                           resto ─► denyAll (401 anónimo / 403 autenticado)             │
  │        ─► DispatcherServlet ─► CurrentSessionController (identity.web, única ruta nueva) │
  │                                   └► DescribeCurrentSession (identity.application)      │
  └──────────────────────────────────────────────────────────────────────────────────────────┘
pruebas ─► IssueSession / RotateRefreshToken / ResetPasswordWithToken (+ AccountSessionRevoker)
           ─► TransactionRunner ─► V8 (identity_refresh_token_family, identity_refresh_token)
portal y trabajador: sin cambios de cadena; solo comprueban al arrancar que no tienen la clave
privada administrativa (O12, DA-17)
```

Las piezas criptográficas son mínimas y cerradas: un algoritmo (`EdDSA` sobre Ed25519 del JDK), una
cabecera exacta por `kid`, un conjunto cerrado de claims, y el análisis del JSON de la carga útil
**solo después** de verificar la firma.

## 2. Evidencia obtenida antes de esta fase

### 2.1 Sondas

**Ninguna sonda se ejecutó en esta fase.** El ejecutor de diseño de esta sesión no dispone de
terminal (solo lectura y escritura de archivos), así que no pudo compilar ni ejecutar código. Cada
sonda pedida se convierte en la **primera prueba en rojo** del PR que la necesita, con el resultado
esperado escrito aquí; si el resultado real difiere, el PR se detiene y el diseño se corrige con una
nota fechada.

| Sonda | Dónde se ejecuta | Resultado esperado | Si difiere |
|---|---|---|---|
| S-1, `Signature("Ed25519")` del JDK 25 contra RFC 8037 A.4 | PR 1, primera prueba | Ed25519 es determinista (RFC 8032): la firma de la entrada de firma del vector coincide byte a byte con la del RFC, y la verificación con la clave pública del vector es verdadera | Se detiene el PR 1; DA-2 se revisa con el propietario |
| S-2, firma maleable (`S + L`) y firma de longitud distinta de 64 | PR 1 | El JDK rechaza `S >= L` (RFC 8032 §5.1.7); la longitud la rechaza el códec antes del JDK | Si el JDK la acepta, el códec añade la comprobación `S < L` explícita, con su prueba |
| S-3, `KeyFactory("Ed25519")` con un PKCS#8 de Ed448 | PR 2 | Rechazo con `InvalidKeySpecException` | El cargador compara además `NamedParameterSpec` |
| S-4, `springdoc.override-with-generic-response` | PR 15, primera prueba | Con `true` (valor por omisión de springdoc) el traductor global puede añadir respuestas no aprobadas; con `false` solo aparecen las declaradas con `@ApiResponse` | Se registra en la nota del PR; la decisión 13 no cambia porque fija `false` en todo caso |
| S-5, `FAIL_ON_UNKNOWN_PROPERTIES` de Jackson 3 en Spring Boot 4 | No aplica a S1 | S1 no tiene ninguna ruta con cuerpo; el códec usa su propio `JsonMapper` con la opción explícita | Queda para S2, que trae los cuerpos (riesgo R-9) |
| S-6, arranque de las pruebas `*Test` sin Docker con la verificación de claves activa | PR 2 | `TestProcessArguments` genera el par en tiempo de ejecución y los tres procesos arrancan; el portal con la clave administrativa no arranca | Se corrige `TestProcessArguments` en el mismo PR |

**Nota fechada 2026-10-06 (sondas S-1 a S-3 ejecutadas por el orquestador con `java` 25.0.3, archivo de un solo uso fuera del
repositorio).**

| Sonda | Resultado |
|---|---|
| S-1 | La firma de la entrada del vector RFC 8037 A.4 coincide byte a byte con la del RFC, y la verificación con su clave pública es verdadera. |
| S-2 | El JDK rechaza la firma maleable (`S + L`, que cabe en 32 bytes) y la de 63 bytes, pero **no devuelve `false`: lanza `SignatureException`**. El códec debe capturarla y tratarla como token inválido, con una prueba por cada caso. La comprobación explícita de `S < L` no hace falta. |
| S-3 | `KeyFactory("Ed25519")` rechaza un PKCS#8 de Ed448 con `InvalidKeySpecException`. |

La afirmación de la decisión 16 también se verificó: la única clase de producción que usa `MessageDigest`, `Mac`, `Signature` o
HMAC fuera de `identity` y `shared.security` es `shared/audit/CanonicalAuditRowSerializer`.

### 2.2 Código leído en `main` @ `2a599f5`

- `SecurityChains.denyByDefault` deshabilita CSRF, sesión, cabeceras, Basic, formulario y cierre;
  permite `PublicEndpoints` y termina en `denyAll()`. `ProblemAuthenticationEntryPoint` responde
  siempre `authentication-required` sin mirar la causa.
- `SessionValidity.isActive(UUID)` en `shared.security`, sin implementación; su Javadoc permite
  ajustar la forma porque nadie lo consume.
- `CurrentInstitutionProvider` está en `organization.application`, sin adaptador; `ResolveCurrentInstitution`
  no es bean. W3 impide que `shared` lo implemente: **el adaptador tiene que vivir en `organization`**.
- `TransactionRunner` confirma todo valor devuelto y reintenta la transacción completa ante `40001` y
  `40P01`. `ResetPasswordWithToken` ya sigue la regla «todo rechazo es un valor, no una excepción».
- `ColumnEncryptionService.encryptForNewValue(table, column, institutionId, rowId, plaintext)` liga
  la AAD a `tabla|columna|institución:rowId`.
- `LayeredArchitectureTest`: `Infrastructure` solo accede a `Application` y `Domain`; `Web` solo a
  `Application`, y nadie accede a `Web`. **Por eso `IdentityConfiguration` (en
  `identity.infrastructure.wiring`) no puede registrar un controlador**: el controlador necesita una
  configuración propia dentro de `identity.web`.
- `IdentityScopeExclusionInventoryTest.noClassOfIdentityReferencesSessionsOrRefreshTokens` busca la
  expresión `session|refresh[_-]?token` (sin distinguir mayúsculas) en nombres de clase, campos,
  métodos y **tipos de los que depende** cualquier clase de `com.confia.identity`. Cualquier clase de
  `identity` que nombre una sesión, incluido el controlador de la sesión actual o el adaptador de
  `SessionValidity`, rompe esa prueba. Consecuencia en el orden de la cadena: sección 8 y nota N-1.
- `WebEdgeScopeExclusionInventoryTest` fija `ALLOWED_ROUTE_RULES = {permitAll, denyAll}`; una regla
  `authenticated()` la rompe hasta que se invierta.
- Usos de utilidades de hash y de firma en producción (inventario de la decisión 16):

| Clase | Uso | ¿Dentro de `identity` o `shared.security`? |
|---|---|---|
| `identity.domain.PasswordResetTokenHash` | `MessageDigest` SHA-256 | Sí |
| `identity.domain.TotpAlgorithm` | `Mac` HMAC | Sí |
| `identity.domain.TotpVerificationPolicy` | `MessageDigest.isEqual` | Sí |
| `identity.infrastructure.{HmacLoginIdentifierFingerprinter, BouncyCastleArgon2PasswordHasher, BouncyCastleRecoveryCodeHasher, Argon2PhcCodec}` | `Mac`, `MessageDigest.isEqual`, Bouncy Castle | Sí |
| `shared.security.RequestPayloadHasher` | `MessageDigest` SHA-256 | Sí |
| `shared.audit.CanonicalAuditRowSerializer` | `MessageDigest` SHA-256 (verificación de la cadena) | **No** |
| `kernel.AesGcmCipher` | `javax.crypto.Cipher` (cifrado, no hash ni firma) | Fuera del alcance de la regla |
| `SecureRandom` en `shared.crypto`, `shared.infrastructure` e `identity` | Generación aleatoria | Fuera del alcance de la regla |

- El código jOOQ se genera en `target/generated-sources/jooq`, paquete `confia.generated.jooq`, y no
  se compromete: la «regeneración» de V8 ocurre sola en la construcción.
- PIT apunta a `com.confia.*.domain.*`; el códec necesita una entrada propia.

## 3. Decisiones de arquitectura

### Decisión 1 — Paquetes y dirección de las dependencias

| Paquete | Contenido | ¿Bean? | `@NamedInterface` |
|---|---|---|---|
| `com.confia.shared.security.token` (nuevo) | `Base64Url`, `CompactJws`, `Ed25519Signatures`, `SigningKey`, `SigningKeyRing`, `SigningKeyLoader`, `AccessTokenClaims`, `MfaTokenClaims`, `AccessTokenIssuer`, `AccessTokenVerifier`, `TokenRejection`, `SigningKeyPlacementGuard`, `SessionTokenConfiguration` | Sí (anillo, emisor, verificador, configuración) | Sí; consumidores: `bootstrap`, `identity` |
| `com.confia.shared.security` (existente) | `AuthenticatedActor` (principal), `AuthenticationMethod`; `SessionValidity` cambia de forma (decisión 7) | No | Ya lo es |
| `com.confia.shared.web.authentication` (nuevo) | `AccessTokenAuthenticationFilter`, `ActorAuthentication`, `AuthenticatedEndpoint`, `AuthenticatedEndpoints`, `SessionCheck`, `AccessTokenRejectedException`, `AccessTokenExpiredException` | **No**: el filtro se construye con `new` dentro de la cadena (ver abajo) | No (interno a `shared`) |
| `com.confia.organization.infrastructure` | `TokenCurrentInstitutionProvider` | Sí | No |
| `com.confia.organization.infrastructure.wiring` (nuevo) | `OrganizationConfiguration` | Sí | Sí; consumidor: `AdminApplication` |
| `com.confia.identity.{domain,application,infrastructure}` | Familias, rotación, revocación, `SessionLiveness` (implementa `SessionValidity`) | Sí | — |
| `com.confia.identity.web` (nuevo) | `CurrentSessionController`, `CurrentSessionResponse` | Sí (controlador) | No |
| `com.confia.identity.web.wiring` (nuevo) | `IdentityWebConfiguration` | Sí | Sí; consumidor: `AdminApplication` |

- **El códec, el anillo, el emisor y el verificador viven en `shared.security.token`**, no en
  `identity`: el filtro (en `shared.web`) necesita verificar y W3 le impide depender de `identity`.
  ADR-0005, verificación 13, permite utilidades de firma en `shared.security` y sus subpaquetes. El
  significado de sesión (familia, rotación, revocación) sí es de `identity`.
- **El principal vive en `shared.security`**, igual que `RequestOrigin`: lo leen el filtro, el
  adaptador de `organization` y el controlador de `identity`.
- **El filtro no es un bean.** Spring Boot registra en el contenedor de servlets todo bean de tipo
  `Filter`; un filtro de autenticación registrado así correría dos veces, una de ellas fuera de la
  cadena. Se construye con `new` en `AdminSecurityConfiguration` y se añade con
  `addFilterBefore(..., AnonymousAuthenticationFilter.class)`. Como `shared.web.authentication` no
  aporta ningún bean, **no lleva línea en `ProcessBeanPolicy`** (la no vacuidad la rechazaría).
- **El controlador se registra desde `identity.web.wiring`**, no desde `IdentityConfiguration`:
  `LayeredArchitectureTest` prohíbe que `Infrastructure` acceda a `Web`.

**Descartado:** códec y verificador en `identity` con un puerto en `shared` para el filtro. El filtro
seguiría necesitando los claims (`tenant`, `sid`, `amr`) para construir el principal, de modo que el
formato del token acabaría duplicado en los dos lados. **Descartado:** el filtro como bean con un
`FilterRegistrationBean` deshabilitado: dos objetos que mantener para evitar un efecto lateral que
`new` no tiene.

### Decisión 2 — Códec JWS compacto (DA-2)

**Formato emitido**, único: `B64U(cabecera) "." B64U(carga) "." B64U(firma)`, con

```
cabecera = {"alg":"EdDSA","kid":"<kid>"}      (bytes exactos, en ese orden, sin espacios)
firma    = Ed25519(clave privada del kid, ASCII(B64U(cabecera) "." B64U(carga)))   (64 bytes)
```

**Base64url estricto** (`Base64Url`): alfabeto `[A-Za-z0-9_-]`, sin relleno, sin espacios; longitud
`mod 4 != 1`; y **canónico**: el texto decodificado y vuelto a codificar con
`Base64.getUrlEncoder().withoutPadding()` debe ser idéntico al recibido. Esto rechaza los bits de
cola distintos de cero, que el decodificador del JDK acepta en silencio, y con ello la maleabilidad
de un mismo token con dos textos.

**Verificación (`CompactJws.verify`), en este orden; la primera falla decide:**

| Paso | Regla | Rechazo |
|---|---|---|
| 1 | Longitud total `<= 2048` caracteres, solo ASCII visible, exactamente dos `.` y tres segmentos no vacíos | `MALFORMED` |
| 2 | **El segmento de cabecera es idéntico, carácter a carácter, a uno de los segmentos canónicos precalculados del anillo** (uno por `kid`). No se decodifica ni se analiza ningún JSON de cabecera aportado por el cliente | `UNKNOWN_HEADER` |
| 3 | Firma en base64url estricto y de exactamente 64 bytes | `MALFORMED` |
| 4 | `Signature("Ed25519").verify` con la clave pública del `kid`, sobre los bytes ASCII de `cabecera.carga` tal como llegaron | `BAD_SIGNATURE` |
| 5 | Carga en base64url estricto; JSON analizado con un `JsonMapper` propio (abajo) | `MALFORMED_CLAIMS` |
| 6 | Reglas de claims de la decisión 4 | `CLAIMS_INVALID` o `EXPIRED` |

- **El paso 2 cierra de una vez** `alg` distinto de `EdDSA`, `none`, `HS256` con la clave pública,
  `alg` ausente o duplicado, `kid` ausente o desconocido, `crit`, `jku`, `jwk`, `x5u`, `typ` y
  cualquier otra cabecera, mayúsculas distintas, espacios y orden distinto de los miembros. Es posible
  porque el único emisor es este mismo proceso. La prueba de cada ataque existe igual (sección 7).
- **Nada del JSON de la carga se analiza antes de verificar la firma.** Un atacante sin clave nunca
  alcanza el analizador.
- **`JsonMapper` del códec**, construido de forma explícita y nunca el de Spring:
  `StreamReadFeature.STRICT_DUPLICATE_DETECTION` (rechaza claims repetidos),
  `DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES` (rechaza claims no declarados, DA-18 ii),
  `FAIL_ON_TRAILING_TOKENS`, `FAIL_ON_NULL_FOR_PRIMITIVES`, sin tipado polimórfico. Los nombres
  exactos de las opciones en Jackson 3 (`tools.jackson`) se confirman en la prueba en rojo del PR 4.
- **Tiempo constante.** La única comparación sobre un secreto sería la de la firma, y la hace el JDK
  dentro de `Signature.verify` (no hay comparación de MAC en el código propio). La comparación del
  paso 2 es sobre datos públicos (la cabecera emitida), no requiere tiempo constante. La búsqueda de un
  refresco es por su SHA-256 en un índice, el patrón ya aceptado para el token de restablecimiento.
- **`Signature` no es seguro entre hilos**: `Ed25519Signatures` obtiene una instancia por llamada.
- **Nunca se registra** un token, una cabecera, una firma ni un fragmento de carga. El motivo del
  rechazo (`TokenRejection`) es un enumerado sin datos; el filtro lo usa para elegir el código y nada
  más.

**Descartado:** analizar la cabecera como JSON y validar sus miembros: es exactamente la superficie de
confusión de algoritmo y de miembros duplicados que el paso 2 elimina. **Descartado:** Nimbus y Tink
(DA-2).

### Decisión 3 — Anillo de claves, formatos, carga y verificación de arranque (O12)

**Propiedades**, leídas con `Environment.getProperty(String)` y nunca con `@ConfigurationProperties`
ni `@Value`, por el mismo motivo que la decisión 3 de 4a (el analizador de fallos imprime el valor):

| Propiedad | Variable de entorno | Contenido | Obligatoria |
|---|---|---|---|
| `confia.security.admin-signing.current.kid` | `CONFIA_SECURITY_ADMINSIGNING_CURRENT_KID` | `^[A-Za-z0-9._-]{1,64}$` | Sí |
| `confia.security.admin-signing.current.private-key` | `CONFIA_SECURITY_ADMINSIGNING_CURRENT_PRIVATEKEY` | PKCS#8 DER en Base64 estándar | Sí |
| `confia.security.admin-signing.current.public-key` | `CONFIA_SECURITY_ADMINSIGNING_CURRENT_PUBLICKEY` | X.509 `SubjectPublicKeyInfo` DER en Base64 estándar | Sí |
| `confia.security.admin-signing.previous.kid` | `CONFIA_SECURITY_ADMINSIGNING_PREVIOUS_KID` | Igual que `current.kid`, distinto de él | No |
| `confia.security.admin-signing.previous.public-key` | `CONFIA_SECURITY_ADMINSIGNING_PREVIOUS_PUBLICKEY` | X.509 DER en Base64 | Solo si hay `previous.kid` |

- **Dos claves en el anillo, una que firma.** `current` firma y verifica; `previous`, si existe, solo
  verifica, y **nunca lleva clave privada**: la rotación semestral de `docs/03` §11.2 publica la clave
  nueva como `current`, mueve la pública anterior a `previous` y la retira pasada una vida de token
  (10 minutos) más el margen operativo.
- **La clave pública es obligatoria junto a la privada** porque el JDK no expone cómo derivar la
  pública de una `EdECPrivateKey`. A cambio, el arranque **firma 32 bytes aleatorios con la privada y
  los verifica con la pública**: un par que no corresponde aborta el proceso.
- **Validación al construir el anillo** (fallo temprano, nunca en la primera petición): Base64
  válido, `KeyFactory.getInstance("Ed25519")` con `PKCS8EncodedKeySpec` y `X509EncodedKeySpec`,
  algoritmo exacto Ed25519 (S-3), `kid` con el formato y distintos entre sí, claves públicas distintas.
  Toda falla lanza `IllegalStateException` que **nombra la propiedad y no repite ningún valor**, sin
  encadenar la causa (el mensaje del decodificador puede llevar un fragmento de la entrada).
- **Segmentos de cabecera precalculados** por `kid` al construir el anillo (decisión 2, paso 2).
- **Verificación de arranque de ADR-0005, verificación 14 (O12, DA-17).** `SigningKeyPlacementGuard`
  es un `ApplicationListener<ApplicationEnvironmentPreparedEvent>` que `ConfiaApplication.launch`
  añade al constructor de cada proceso, antes de crear el contexto (no es un bean, así que no toca
  `ProcessBeanPolicy`). Reglas:

| Proceso | Comprobación | Fuente |
|---|---|---|
| `admin` | Su clave propia se valida al construir el anillo (bean) | ADR-0005 v14, primera mitad |
| `admin` | Ninguna propiedad `confia.security.portal-signing.*.private-key` presente (nombre reservado) | ADR-0005 v14, segunda mitad; **decisión nueva D-N2** |
| `portal`, `worker` | `confia.security.admin-signing.current.private-key` y `...previous.private-key` ausentes | DA-17 |

  La comprobación usa `Environment.containsProperty`, que cubre variables de entorno, propiedades
  del sistema y argumentos con el enlace relajado. Mensaje: nombra la propiedad, nunca el valor.
- **Claves de prueba.** `TestProcessArguments` genera **en tiempo de ejecución** un par con
  `KeyPairGenerator.getInstance("Ed25519")` y pasa `kid`, privada y pública como argumentos del
  proceso administrativo, igual que la llave maestra y la pimienta. Ninguna clave se escribe en el
  repositorio. Los arneses de pruebas con base de datos (`IntegrationTestApplication`) reciben el par
  del mismo generador. **Del vector de RFC 8037 A.4 solo se versionan la clave pública, la entrada de
  firma y la firma publicada**, nunca su clave privada (decisión del propietario del 2026-10-06, regla 13
  sin excepciones). La firma se prueba con pares generados en tiempo de ejecución; la reproducción byte a
  byte de la firma del RFC quedó demostrada por la sonda S-1 (sección 2.1).
- **JWKS diferido** (DA-16): el único verificador es el propio proceso, con el anillo en memoria.

**Descartado:** archivos PEM montados: añaden rutas de archivo que custodiar antes del cambio 11, y
ADR-0005 fija variables de entorno. **Descartado:** la verificación de ausencia como bean de cada
proceso: el trabajador no admite ningún paquete de `shared` en su lista de permitidos, y una línea
nueva solo para el guardián ampliaría lo que el trabajador puede registrar.

### Decisión 4 — Token de acceso y token restringido de MFA

| Claim | Acceso | Restringido de MFA |
|---|---|---|
| `iss` | `confia-admin` | `confia-admin` |
| `aud` | `confia-admin` (cadena, no arreglo) | **`confia-admin-mfa`** |
| `sub` | UUID de la cuenta del personal (opaco) | Igual |
| `iat`, `exp` | Segundos enteros; `exp - iat = 600` exacto | `exp - iat = 300` exacto |
| `jti` | UUID aleatorio | UUID aleatorio |
| `sid` | UUID de la familia de refresco | **Ausente** (no hay familia) |
| `tenant` | UUID de la institución | Igual |
| `amr` | `["pwd"]` o `["pwd","otp"]`, en ese orden | `["pwd"]` |
| `purpose` | **Ausente** | `mfa-verify` o `mfa-enroll` |
| `permissions` | **Ausente** hasta el cambio 8 (DA-18 ii) | Ausente |

- **Lista cerrada de claims**: cualquier claim no declarado, repetido, de tipo distinto o nulo se
  rechaza (`CLAIMS_INVALID`). Ningún claim contiene datos personales.
- **Separación por audiencia** (RFC 8725 §3.9): el verificador de acceso exige `aud = confia-admin` y
  el de MFA `aud = confia-admin-mfa`; un token restringido falla contra cualquier ruta autenticada por
  audiencia, sin depender de que alguien recuerde mirar `purpose`. Además el verificador de acceso
  exige `sid` presente y `purpose` ausente, y el de MFA lo contrario.
- **Reglas de tiempo**, con el `Clock` inyectado: `exp <= now` → `EXPIRED` (solo después de una
  firma válida y de claims íntegros); `iat > now + 60 s` → `CLAIMS_INVALID`; `exp - iat` distinto
  del valor exacto → `CLAIMS_INVALID`. Sin tolerancia sobre `exp`: el emisor y el verificador son el
  mismo proceso; entre réplicas el reloj lo sincroniza NTP.
- **Rutas que aceptan el token restringido en S1: ninguna.** S1 lo emite y lo verifica
  (`AccessTokenIssuer.issueMfa`, `AccessTokenVerifier.verifyMfa`); el filtro solo usa `verifyAccess`,
  así que lo rechaza como `token-invalid` en toda ruta. S2 añade sus rutas.
- **Vida de 5 minutos** para el restringido: cubre abrir la aplicación TOTP y escribir el código; se
  puede reutilizar dentro de esa vida, y el retroceso por cuenta de `identity_mfa_totp_backoff` limita
  el adivinado (riesgo ya registrado en la exploración).
- **Serialización determinista** del emisor: miembros en el orden de la tabla, sin espacios.

**Descartado:** distinguir el restringido solo por `purpose`: un verificador que olvide mirarlo lo
aceptaría como acceso. **Descartado:** cabecera `typ` (tipado explícito): la propuesta rechaza toda
cabecera distinta de `alg` y `kid`, y la audiencia logra la misma separación.

### Decisión 5 — Filtro de autenticación, principal y rutas autenticadas

**Principal**, en `shared.security`:

```java
public record AuthenticatedActor(UUID accountId, UUID institutionId, UUID sessionId, UUID tokenId,
        Set<AuthenticationMethod> methods, Instant issuedAt, Instant expiresAt) {
    static final ScopedValue<AuthenticatedActor> CURRENT = ScopedValue.newInstance();
    public static Optional<AuthenticatedActor> current();
    public SecurityContext securityContext(String requestId);   // actorKind "staff"
}
public enum AuthenticationMethod { PASSWORD /* "pwd" */, ONE_TIME_PASSWORD /* "otp" */ }
```

**Filtro `AccessTokenAuthenticationFilter`** (`OncePerRequestFilter`), solo en
`AdminSecurityConfiguration`; el portal no cambia:

1. Sin cabecera `Authorization`, o con un esquema distinto de `Bearer` → sigue la cadena como anónimo. Una cabecera presente pero vacía o solo de espacios no es «sin cabecera»: es una credencial rota y sigue el paso 2.
2. Más de una cabecera `Authorization`, una vacía o solo de espacios, esquema `Bearer` (sin distinguir mayúsculas) sin exactamente
   un espacio y un `token68`, o un token que el verificador rechaza → **no sigue la cadena**: llama al
   punto de entrada con `AccessTokenExpiredException` (solo `EXPIRED`) o
   `AccessTokenRejectedException` (todo lo demás).
3. Token válido y la petición coincide con una entrada `LIVE` de `AuthenticatedEndpoints` →
   `SessionValidity.isActive(actor)`; falso → `AccessTokenRejectedException` (decisión 7).
4. Éxito → `ActorAuthentication` (autenticada, sin autoridades) en el `SecurityContextHolder` de
   Spring para la autorización, y el resto de la cadena corre dentro de
   `ScopedValue.where(AuthenticatedActor.CURRENT, actor)`, igual que `RequestOrigin` (decisión 11 de
   4a). El propio filtro limpia el contexto de Spring en un `finally` (`SecurityContextHolder.clearContext()`), además de `SecurityContextHolderFilter`, para que un hilo del grupo nunca empiece la petición siguiente con el principal anterior.

- **Un token inválido en una ruta pública también responde `401`.** Un cliente que envía una
  credencial rota recibe la causa; la SPA no envía `Authorization` a rutas públicas.
- **Punto de entrada.** `ProblemAuthenticationEntryPoint` pasa a distinguir tres casos por tipo de
  excepción: `AccessTokenExpiredException` → `token-expired`; `AccessTokenRejectedException` →
  `token-invalid`; cualquier otra (petición anónima denegada) → `authentication-required`. La
  uniformidad de la denegación anónima no cambia: una ruta inexistente y una protegida siguen dando
  la misma respuesta sin credencial.
- **Una sesión revocada o vencida responde `token-invalid`.** RFC 6750 define `invalid_token` como
  «vencido, revocado, mal formado o inválido»; `token-expired` queda solo para un token íntegro
  vencido, como fija la propuesta. No se crea un código nuevo.

**`WWW-Authenticate` en todo `401` (O5).** `ProblemResponses.write` la fija a partir del código, en
un único lugar, así que la emiten la cadena, el traductor y la válvula por igual:

| Código | Cabecera |
|---|---|
| `authentication-required` | `WWW-Authenticate: Bearer` |
| `token-invalid`, `token-expired` | `WWW-Authenticate: Bearer error="invalid_token"` |

Sin `realm` ni `error_description` (ambos opcionales en RFC 6750): no hay nada que el cliente
necesite y una descripción solo ofrecería texto a un atacante. El portal también la emite en su
`401`, lo que RFC 9110 exige de todo `401`.

**Rutas autenticadas sin permiso de rol (DA-7, decisión 2 del propietario).** `AuthenticatedEndpoints`
es la lista cerrada paralela a `PublicEndpoints`, en un único lugar:

```java
public record AuthenticatedEndpoint(HttpMethod method, String pattern, SessionCheck check) { }
public enum SessionCheck { TOKEN_ONLY, LIVE_SESSION }
public final class AuthenticatedEndpoints {
    public static AuthenticatedEndpoints forAdmin();      // S1: GET /api/v1/auth/sessions/current LIVE_SESSION
    public AuthenticatedEndpoints(List<AuthenticatedEndpoint> endpoints);   // harnesses only add routes
}
```

`SecurityChains.denyByDefault` gana un segundo constructor para administración: primero las públicas
(`permitAll`), después las autenticadas (`authenticated()`), y la última regla `denyAll()`. Un
principal autenticado en una ruta fuera de las dos listas recibe `403 forbidden`, como hoy. La lista
la sustituye la matriz del cambio 8. Hasta el PR 15 la lista de producción está vacía y las pruebas
del filtro usan rutas del arnés.

**Descartado:** comprobar `sid` en un `AuthorizationManager`: una negativa con principal autenticado
va al manejador de acceso denegado y daría `403`, cuando una sesión revocada es una credencial que ya
no vale (`401`). **Descartado:** `AuthenticationProvider` y `BearerTokenAuthenticationFilter` de
Spring: el primero no aporta nada a un verificador sin estado, y el segundo pertenece al servidor de
recursos OAuth2, prohibido en `bannedDependencies`. La ausencia «ninguna clase es un
`AuthenticationProvider`» del inventario de 4a **se conserva**.

### Decisión 6 — Adaptador de `CurrentInstitutionProvider` (O7, ADR-0009)

`TokenCurrentInstitutionProvider` en `organization.infrastructure` devuelve
`new InstitutionId(AuthenticatedActor.current().orElseThrow(...).institutionId())`. Sin actor lanza
`IllegalStateException` (una ruta que llega sin autenticar es un defecto de cableado, no un rechazo).
Lo registra `OrganizationConfiguration` (`organization.infrastructure.wiring`, `@NamedInterface`),
importada por `AdminApplication`. No lee ninguna cabecera, parámetro ni cuerpo: no recibe la
petición.

- `ResolveCurrentInstitution` **sigue sin registrarse**: los códigos `institution-not-found` e
  `institution-inactive` son de la primera ruta que resuelva la institución contra `organization`.
- **Prueba de ADR-0009:** controlador del arnés que devuelve `currentInstitutionId()`, llamado con un
  token de la institución A y con `X-Institution-Id`, `?institutionId=`, `?tenant=` y un cuerpo con
  `institutionId` de la institución B: la respuesta es siempre A.

**Descartado:** adaptador en `shared.security`: W3 le impide implementar un puerto de
`organization`.

### Decisión 7 — `SessionValidity` y la comprobación de `sid` (O8, decisión 10 del propietario)

- **Forma nueva del puerto** (su Javadoc permite ajustarla): `boolean isActive(AuthenticatedActor
  actor)`. Con solo el `UUID` el adaptador no podría fijar `app.institution_id` y la seguridad a nivel
  de fila negaría toda fila.
- **Adaptador:** `SessionLiveness` en `identity.application` implementa el puerto; abre una
  transacción `READ COMMITTED` de solo lectura con `actor.securityContext(requestId)` y responde
  verdadero solo si la familia existe, pertenece a `actor.accountId()`, no está revocada,
  `now < absolute_expires_at` y `now < last_used_at + 30 min`. No escribe, no audita y no actualiza
  `last_used_at` (decisión 4 del propietario: la inactividad se mide al refrescar).
- **Dónde se aplica:** a las entradas `LIVE_SESSION` de `AuthenticatedEndpoints`, en el filtro
  (decisión 5, paso 3). En S1 la única es la sesión actual; las escrituras sensibles la declaran
  cuando lleguen. Un error de base de datos durante la comprobación se propaga y el último recurso
  responde `500`: falla cerrado.

### Decisión 8 — Migración V8

`V8__create_identity_refresh_token_tables.sql`:

```sql
CREATE TABLE identity_refresh_token_family (
    institution_id         UUID        NOT NULL,
    id                     UUID        NOT NULL,
    account_id             UUID        NOT NULL,
    second_factor_verified BOOLEAN     NOT NULL,   -- amr = pwd (+ otp when true)
    user_agent_fingerprint TEXT        NOT NULL,   -- hex SHA-256 of the sanitized User-Agent
    ip_first_seen          INET,                   -- clear text by owner decision (docs/08 §4.1)
    created_at             TIMESTAMPTZ NOT NULL,   -- no DEFAULT: from the injected Clock
    last_used_at           TIMESTAMPTZ NOT NULL,
    absolute_expires_at    TIMESTAMPTZ NOT NULL,
    revoked_at             TIMESTAMPTZ,
    revoked_reason         TEXT,
    CONSTRAINT identity_refresh_token_family_pk PRIMARY KEY (institution_id, id),
    CONSTRAINT identity_refresh_token_family_account_fk FOREIGN KEY (institution_id, account_id)
        REFERENCES identity_staff_account (institution_id, id),
    CONSTRAINT identity_refresh_token_family_fp_chk CHECK (user_agent_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT identity_refresh_token_family_window_chk
        CHECK (absolute_expires_at > created_at AND last_used_at >= created_at),
    CONSTRAINT identity_refresh_token_family_reason_chk
        CHECK (revoked_reason IN ('reuse_detected', 'password_change')),
    CONSTRAINT identity_refresh_token_family_revoked_chk
        CHECK ((revoked_at IS NULL) = (revoked_reason IS NULL))
);
CREATE INDEX identity_refresh_token_family_live_by_account
    ON identity_refresh_token_family (institution_id, account_id) WHERE revoked_at IS NULL;

CREATE TABLE identity_refresh_token (
    institution_id              UUID        NOT NULL,
    id                          UUID        NOT NULL,
    family_id                   UUID        NOT NULL,
    token_hash                  TEXT        NOT NULL,
    issued_at                   TIMESTAMPTZ NOT NULL,
    expires_at                  TIMESTAMPTZ NOT NULL,
    consumed_at                 TIMESTAMPTZ,
    consumed_user_agent_fingerprint TEXT,
    replaced_by                 UUID,
    successor_token_ciphertext  TEXT,
    CONSTRAINT identity_refresh_token_pk PRIMARY KEY (institution_id, id),
    CONSTRAINT identity_refresh_token_family_fk FOREIGN KEY (institution_id, family_id)
        REFERENCES identity_refresh_token_family (institution_id, id),
    CONSTRAINT identity_refresh_token_successor_fk FOREIGN KEY (institution_id, replaced_by)
        REFERENCES identity_refresh_token (institution_id, id) DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT identity_refresh_token_hash_uq UNIQUE (institution_id, token_hash),
    CONSTRAINT identity_refresh_token_hash_chk CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT identity_refresh_token_window_chk CHECK (expires_at > issued_at),
    CONSTRAINT identity_refresh_token_consumed_chk CHECK (
        (consumed_at IS NULL) = (replaced_by IS NULL)
        AND (consumed_at IS NULL) = (consumed_user_agent_fingerprint IS NULL)),
    CONSTRAINT identity_refresh_token_consumed_fp_chk
        CHECK (consumed_user_agent_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT identity_refresh_token_successor_chk CHECK (
        successor_token_ciphertext IS NULL
        OR (consumed_at IS NOT NULL AND successor_token_ciphertext LIKE 'v1:%'))
);
-- Safety net of "exactly one successor": at most one unconsumed token per family.
CREATE UNIQUE INDEX identity_refresh_token_one_open_per_family
    ON identity_refresh_token (institution_id, family_id) WHERE consumed_at IS NULL;
CREATE UNIQUE INDEX identity_refresh_token_one_predecessor
    ON identity_refresh_token (institution_id, replaced_by) WHERE replaced_by IS NOT NULL;
CREATE INDEX identity_refresh_token_family_idx ON identity_refresh_token (institution_id, family_id);
```

- **RLS** `ENABLE` y `FORCE` en ambas, con la política `NULLIF(current_setting('app.institution_id',
  true), '')::uuid` en `USING` y `WITH CHECK`, idéntica a V5 a V7.
- **Privilegios, por primera vez por columna** (la propuesta los pide: «matriz explícita por
  columna»):

```sql
REVOKE ALL ON identity_refresh_token_family FROM PUBLIC;
REVOKE ALL ON identity_refresh_token        FROM PUBLIC;
GRANT SELECT, INSERT ON identity_refresh_token_family TO confia_admin_app;
GRANT UPDATE (last_used_at, revoked_at, revoked_reason) ON identity_refresh_token_family TO confia_admin_app;
GRANT SELECT, INSERT ON identity_refresh_token TO confia_admin_app;
GRANT UPDATE (consumed_at, consumed_user_agent_fingerprint, replaced_by, successor_token_ciphertext)
    ON identity_refresh_token TO confia_admin_app;
GRANT SELECT ON identity_refresh_token_family TO confia_readonly;
GRANT SELECT ON identity_refresh_token        TO confia_readonly;
-- confia_portal_app: nothing. No DELETE to any role: the purge belongs to change 9.
```

  `SELECT ... FOR UPDATE` exige privilegio de `UPDATE` sobre al menos una columna, que las concesiones
  por columna cumplen. `confia_readonly` lee los hashes (inútiles sin el texto, como en V7) y el
  sucesor cifrado (inútil sin la llave maestra).
- **Sin `principal_type`:** la tabla es del personal (prefijo `identity_`, dominio `staff`); los
  encargados tendrán sus propias tablas cuando llegue su subdominio (ADR-0003, dominios disjuntos).
- **Motivos de revocación con productor en S1, y solo esos** (decisión 7 del propietario):
  `reuse_detected` (rotación) y `password_change` (restablecimiento, vocabulario de ADR-0005). `logout`
  llega con S2 y `admin_revoke` con el cambio 8, cada uno con su migración que amplía el `CHECK`. El
  vencimiento absoluto y la inactividad **no se escriben**: se derivan de las columnas y del reloj.
- **Vigencias (decisión 4 del propietario)**, en `identity.domain.SessionLifetimePolicy`, única
  fuente: refresco `expires_at = min(issued_at + 8 h, absolute_expires_at)`; familia
  `absolute_expires_at = created_at + 12 h`; inactividad `now >= last_used_at + 30 min`; gracia 10 s.
  Ningún `CHECK` repite esas duraciones (mismo criterio que V7).
- **Sucesor cifrado ligado a su fila (DA-5):** `successor_token_ciphertext` vive en la fila del
  **predecesor** consumido, cifrado con `ColumnEncryptionService.encryptForNewValue(
  "identity_refresh_token", "successor_token_ciphertext", institutionId, predecessorId, bytes)`. Copiado
  a otra fila no descifra (AAD).
- **Agente de usuario e IP (decisión 5 del propietario):** se registran y no se aplican. La huella es
  el SHA-256 en hexadecimal del agente de usuario tal como lo sanea `RequestContextFilter`; sin
  agente, la huella de la cadena vacía. La IP va en claro, como en la bitácora (`docs/08` §4.1), y se
  conserva hasta la purga del cambio 9; nota fechada en `docs/08`.
- **jOOQ** se regenera solo en la construcción (sección 2.2). `TableOwnershipByModuleTest` y la
  prueba de esquema multitenencia existentes cubren las dos tablas sin cambios.

**Descartado:** `token_hash BYTEA` del esquema conceptual de ADR-0005: el repositorio ya fijó hex en
`TEXT` (V5, V7). **Descartado:** marcar como consumidos los tokens al revocar la familia: la vigencia
se deriva de la familia, y el `CHECK` de consumo exige sucesor.

### Decisión 9 — Emisión de una familia

`IssueSession` en `identity.application`, con `execute` (abre su transacción) y
`runWithinTransaction` (para que S2 la componga con el inicio de sesión, el patrón de
`VerifyTotpCode`):

```java
public record IssueSessionCommand(StaffAccountId accountId, boolean secondFactorVerified,
        String userAgent, ClientAddress clientAddress) { }   // origin explicit, never ambient (O6)
```

1. En la transacción: `familyId` y `tokenId` aleatorios; `PlainRefreshToken.generate()` (32 bytes de
   `SecureRandom`, base64url sin relleno, 43 caracteres; `toString()` redactado);
   `RefreshTokenHash.of(token)` = SHA-256 en hex del texto; inserta la familia y el token; audita
   `identity.session.started`.
2. Después de confirmar: `AccessTokenIssuer.issueAccess(...)` con `sid = familyId` y el `amr` de la
   familia. Firmar fuera de la transacción garantiza que nunca se entrega un token de una familia que
   no se confirmó.
3. Devuelve `IssuedSession(sessionId, PlainRefreshToken, refreshExpiresAt, AccessToken,
   accessExpiresAt)`; `AccessToken` también redacta su `toString()`.

En S1 solo la invocan las pruebas. Prueba 5 de ADR-0005: el valor de `token_hash` no coincide con el
token entregado y es su SHA-256.

### Decisión 10 — Rotación, reutilización y vigencias

`RotateRefreshToken` en `identity.application`. La institución sale de `LoginInstitutionProvider`,
igual que en `ResetPasswordWithToken`: una petición de refresco no trae token de acceso vigente y la
cookie no lleva institución (riesgo R-6). Todo ocurre en **una** transacción de `TransactionRunner`:

| Paso | Acción |
|---|---|
| 1 | Texto mal formado → rechazo `UNKNOWN_TOKEN` (no puede ser un token emitido). |
| 2 | Busca el token por `(institution_id, token_hash)` **sin bloqueo**. No existe → `UNKNOWN_TOKEN`. |
| 3 | `SELECT ... FROM identity_refresh_token_family WHERE ... FOR UPDATE`: **el bloqueo de la familia es el punto de serialización** de toda rotación y revocación de esa familia. |
| 4 | **Relee** el token con una sentencia nueva (en `READ COMMITTED` ve lo que confirmó quien tenía el bloqueo). |
| 5 | Vacía `successor_token_ciphertext` de los tokens de la familia consumidos hace 10 s o más (decisión 6 del propietario: «se vacía en el siguiente uso de la familia»). |
| 6 | `RotationDecision.decide(family, token, presenterFingerprint, now, policy)`, **función pura del dominio** (abajo). |
| 7 | Ejecuta la decisión y audita en la misma transacción. Todo rechazo es un **valor devuelto**: `TransactionRunner` confirma la revocación por reutilización y su asiento aunque la petición se rechace. |
| 8 | Después de confirmar, si hubo sucesor: firma un token de acceso nuevo (`sid`, `amr` de la familia). |

**`RotationDecision`** (en `identity.domain`, objetivo de PIT), con el orden de evaluación fijo:

| Orden | Condición | Decisión | Efecto |
|---|---|---|---|
| 1 | Familia revocada | `REJECT_FAMILY_REVOKED` | Ninguno |
| 2 | `now >= absolute_expires_at` o `now >= last_used_at + 30 min` | `REJECT_FAMILY_EXPIRED` | Vacía todo sucesor cifrado de la familia |
| 3 | Token consumido, `now - consumed_at < 10 s`, huella del presentador igual a `consumed_user_agent_fingerprint`, sucesor cifrado presente y sucesor sin consumir | `GRACE_REPLAY` | Descifra el sucesor; `last_used_at = now` |
| 4 | Token consumido en cualquier otro caso | `REUSE_DETECTED` | Revoca la familia con `reuse_detected`; vacía todo sucesor cifrado de la familia |
| 5 | `now >= expires_at` del token | `REJECT_TOKEN_EXPIRED` | Ninguno |
| 6 | En otro caso | `ROTATE` | Abajo |

**`ROTATE`:** `UPDATE identity_refresh_token SET consumed_at = now, consumed_user_agent_fingerprint =
?, replaced_by = :successorId, successor_token_ciphertext = :cipher WHERE institution_id = ? AND id = ?
AND consumed_at IS NULL`; después `INSERT` del sucesor (la clave foránea de `replaced_by` es diferida,
por eso el orden actualizar-insertar no choca con el índice «un solo token abierto por familia»);
después `last_used_at = now`. Una actualización condicional que no afecta a una fila, imposible bajo
el bloqueo, lanza `IllegalStateException` y revierte: es la red de seguridad, no el mecanismo.

- **Desenlace tipado:** `RefreshOutcome` sellado: `Rotated(IssuedSession, RotationKind {FRESH,
  GRACE})` o `Rejected(RefreshRejection {UNKNOWN_TOKEN, FAMILY_REVOKED, FAMILY_EXPIRED, TOKEN_EXPIRED,
  REUSE_DETECTED})`. S2 traduce todo `Rejected` a una sola respuesta uniforme.
- **Concurrencia:** dos rotaciones simultáneas del mismo token se serializan en el bloqueo; la segunda
  relee el token consumido y cae en `GRACE_REPLAY` (mismo dispositivo, devuelve el mismo sucesor) o en
  `REUSE_DETECTED` (otro dispositivo, revoca). En ambos casos existe **exactamente un sucesor**.
- **Interbloqueos:** la rotación toma un solo bloqueo (la familia). La revocación de todas (decisión
  12) toma la cuenta y luego las familias en orden de `id`; la rotación nunca toma la cuenta. No hay
  ciclo posible.
- **Reintento de `TransactionRunner`:** si la transacción se reintenta, el token nuevo y su cifrado se
  generan de nuevo dentro del proveedor; nada se reutiliza de un intento abortado.
- **Inactividad:** la granularidad real es el ciclo de refresco y supone que la SPA refresca solo ante
  actividad (decisión 4 del propietario; nota en `docs/ui-ux/04`).

### Decisión 11 — Ventana de gracia con sucesor cifrado (DA-5, decisión 6 del propietario)

- **Mismo dispositivo** = la huella del agente de usuario que presenta el reintento es igual a la que
  presentó el consumo (`consumed_user_agent_fingerprint`), no a la del inicio de sesión: una
  actualización del navegador durante la sesión no convierte un reintento legítimo en reutilización.
- **Rechazo de descifrado pasados 10 s:** `RotationDecision` nunca devuelve `GRACE_REPLAY` con
  `now - consumed_at >= 10 s`, así que `decrypt` no se invoca fuera de la ventana. El borde es
  estricto: a los 10 s exactos es reutilización.
- **Vaciado físico:** en el paso 5 de toda rotación de la familia, en toda revocación (reutilización
  y restablecimiento) y en el rechazo por familia vencida. El cambio 9 purga lo que quede en familias
  que nadie vuelve a tocar.
- **Qué devuelve la gracia:** el **mismo token de refresco** sucesor y **un token de acceso nuevo**
  (otro `jti` e `iat`, mismos `sid`, `sub`, `tenant` y `amr`). El token de acceso del primer intento
  no se guarda en ningún lado. ADR-0005 (punto 4 y verificación 2) dice «el mismo par»: **decisión
  nueva D-N1**, con nota fechada en ADR-0005 si se aprueba.

### Decisión 12 — H2: revocación de todas las familias al restablecer

- **Puerto** `AccountSessionRevoker` en `identity.application`:
  `int revokeAllLive(InstitutionId, StaffAccountId, SessionRevocationReason, Instant now)`.
  **Adaptador** `JooqAccountSessionRevoker`: bloquea las familias vivas de la cuenta con `ORDER BY id
  FOR UPDATE`, las revoca con `password_change`, vacía sus sucesores cifrados y devuelve cuántas
  revocó. Las familias ya revocadas conservan su motivo.
- **`ResetPasswordWithToken`** recibe el puerto en su constructor (cambian el constructor, el bean de
  `IdentityConfiguration` y todas sus pruebas) y, **solo en el desenlace `Completed`**, después de
  sustituir el hash y en la misma transacción, revoca y audita `identity.session.revoked` con
  `{"reason":"password_change","families":n}`, también cuando `n = 0` (evidencia de que la revocación
  corrió). Ningún rechazo revoca nada. Si la revocación o su auditoría fallan, se revierte el
  restablecimiento completo.
- **Orden de la cadena:** V8 (PR 8), luego H2 (PR 9), luego emisión (PR 10). Ningún commit de `main`
  contiene emisión sin revocación.
- **Inversión de `IdentityScopeExclusionInventoryTest`:** se sustituye
  `noClassOfIdentityReferencesSessionsOrRefreshTokens` por dos reglas con fixture permanente:
  *(a)* `ResetPasswordWithToken` llama a `AccountSessionRevoker.revokeAllLive` (no vacía: busca la
  llamada real); *(b)* ninguna clase de producción distinta de `ResetPasswordWithToken` llama a
  `replacePasswordHash` sin llamar también a `revokeAllLive` (fixture: `ResetWithoutRevocation`). La
  regla vieja y su fixture `RefreshTokenFamilyRevoker` se retiran en el mismo PR.
- Si el PR 9 excede 800 líneas: costura 9a (puerto, adaptador, su prueba de integración, y la
  inversión del inventario estrechada a «ninguna clase de `identity` inserta en
  `identity_refresh_token_family`») y 9b (cableado en el restablecimiento, I24, inversión final).

### Decisión 13 — `GET /api/v1/auth/sessions/current` y la primera instantánea OpenAPI (O4)

- **Ruta** en estilo de recursos (decisión 1 del propietario; DA-18 i para esta ruta), entrada
  `LIVE_SESSION` de `AuthenticatedEndpoints`. Controlador `CurrentSessionController` en
  `identity.web`, registrado por `IdentityWebConfiguration`; obtiene el actor con
  `AuthenticatedActor.current()` y llama a `DescribeCurrentSession` (lectura de la familia en una
  transacción de solo lectura). Ningún controlador interpreta tokens.
- **DTO** (`identity.web.CurrentSessionResponse`), sin datos personales, fechas ISO 8601 en UTC:

```json
{
  "sessionId": "3f0c…",
  "accountId": "9d1e…",
  "institutionId": "7c2e…",
  "authenticationMethods": ["pwd", "otp"],
  "accessTokenExpiresAt": "2026-10-06T20:40:00Z",
  "sessionExpiresAt": "2026-10-07T08:30:00Z",
  "idleExpiresAt": "2026-10-06T21:00:00Z"
}
```

- **Respuestas:** `200`; `401` con `authentication-required`, `token-invalid` o `token-expired`,
  siempre con `WWW-Authenticate`; `500 internal-error`. No hay `403` alcanzable (todo actor
  autenticado puede leer su propia sesión) ni `429` (sin limitador en esta ruta: es una lectura de bajo
  costo; S2 decide los límites de las rutas de sesión, DA-13).
- **OpenAPI:** `springdoc.override-with-generic-response: false` en `application.yml`, y cada
  respuesta declarada con `@ApiResponse` sobre el método, con `application/problem+json` y el esquema
  `ProblemDetail` para el `401` y el `500`. Así la instantánea solo contiene lo aprobado, aunque el
  traductor global crezca (S-4). `ContractSchemas` declara además el esquema de seguridad
  `bearerAuth` (`http`, `bearer`, `bearerFormat: JWT`) y **el miembro `errors`** del esquema
  `ProblemDetail` (arreglo opcional de `{field, reason}`), que `OpenApiContractSnapshotTest` deja de
  negar.
- **Instantánea:** `apps/api/openapi/admin.openapi.json` se regenera con el procedimiento existente,
  se revisa como contrato y se aprueba en el PR 15. `packages/contracts` se regenera con orval
  (`pnpm --filter @confia/contracts generate`) y pasa `typecheck` y `test`; su código generado no se
  compromete (está ignorado por Git), así que lo comprometido es la instantánea.
- **Inversiones en el mismo PR:** la entrada W2a de `EmptyShouldExceptionInventoryTest` se retira
  (primer controlador de producción); el enumerador de rutas pasa de «ninguna ruta de producción en
  administración» a «las rutas registradas son exactamente `PublicEndpoints ∪ AuthenticatedEndpoints`»,
  y `PublicRouteAllowListTest` gana la comprobación de que cada entrada autenticada sirve una ruta y de
  que, con un token válido, una ruta registrada fuera de las dos listas responde `403`.

### Decisión 14 — Corrección mínima de `ProblemExceptionHandler.unexpected` (O1, decisión 3 del propietario)

Antes del registro genérico, `unexpected` recorre la cadena de causas (hasta 16, como
`clientWentAway`). Si encuentra una `java.sql.SQLException` o una
`org.springframework.dao.DataAccessException`, registra **una línea sin excepción adjunta**:

```java
LOG.error("A data access failure was not translated: exception={}, cause={}, sqlState={}",
        e.getClass().getName(), sqlException.getClass().getName(), sqlException.getSQLState());
```

- Sin mensaje, sin traza, sin argumentos tomados de la excepción salvo nombres de clase y `SQLState`.
  El `requestId` ya está en el MDC (decisión 11 de 4a) y es el `traceId` del cuerpo.
- La excepción de jOOQ envuelve la `SQLException` como causa; se detecta por la causa de JDK, así que
  `shared.web.problem` no depende de `org.jooq` (W1).
- Las demás excepciones siguen registrándose completas hasta el filtro central del cambio 10.
- **Prueba (`ProblemTranslationTest`, arnés sin base de datos):** un controlador de prueba lanza una
  excepción de acceso a datos cuya causa es una `PSQLException` real del controlador de PostgreSQL,
  construida con un `ServerErrorMessage` que contiene `Detail: Key (email)=(persona@ejemplo.hn) already
  exists` y `SQLState 23505`. Un `ListAppender` en la raíz afirma que ningún mensaje formateado,
  argumento, MDC ni `ThrowableProxy` contiene `Detail: Key` ni el correo, que el evento contiene
  `23505` y los dos nombres de clase, y que el MDC lleva el mismo `requestId` que el `traceId` del
  cuerpo. **Control negativo:** el mismo verificador sobre un evento registrado a propósito con ese
  texto reporta la violación.

### Decisión 15 — Auditoría

Todas en la misma transacción que su efecto, con `actorKind = staff`, `actorLabel` = UUID de la
cuenta (precedente de identidad) y `sourceIp`/`userAgent` completados por el decorador de 4a.

| Acción | Entidad | Resultado | `afterValue` |
|---|---|---|---|
| `identity.session.started` | `identity.refresh_token_family` / id de familia | `success` | `{"secondFactor":true\|false}` |
| `identity.session.refreshed` | familia | `success` | `{"rotation":"fresh"\|"grace"}` |
| `identity.session.refresh_rejected` | familia, o `unknown-token` sin fila | `denied` | `{"reason":"unknown_token"\|"family_revoked"\|"family_expired"\|"token_expired"}` |
| `identity.session.reuse_detected` | familia | `denied` | `{"familyRevoked":true}` |
| `identity.session.revoked` | `identity.staff_account` / id de cuenta | `success` | `{"reason":"password_change","families":n}` |

- **Nunca** se escriben en la bitácora ni en los registros: el token de refresco, su hash, el sucesor
  cifrado, el token de acceso, la cabecera `Authorization`, la huella del agente ni el `jti`. Los
  identificadores de familia y de cuenta son opacos y sí se escriben.
- Se audita también el refresco exitoso: un auditor reconstruye una sesión robada por su secuencia de
  refrescos («ante la duda, se audita»). Volumen estimado: unos 2 400 asientos diarios con 50 personas.
- El aviso al titular por reutilización es del cambio 14.

### Decisión 16 — Utilidades de firma y de hash solo en `identity` y `shared.security` (ADR-0005, verificación 13)

- **Regla `SigningAndHashingConfinementTest`** (dos mitades, ADR-0018): ninguna clase de producción
  fuera de `com.confia.identity..` y `com.confia.shared.security..` depende de
  `java.security.MessageDigest`, `java.security.Signature`, `java.security.KeyFactory`,
  `java.security.KeyPairGenerator`, `javax.crypto.Mac`, `javax.crypto.KeyGenerator` ni de
  `org.bouncycastle..`. Fixture permanente:
  `fixture/hashing/outside/BadDigestOutsideIdentity`. Fuera de alcance, explícito en el Javadoc:
  `javax.crypto.Cipher` (cifrado de ADR-0023, en `kernel`) y `SecureRandom`.
- **Inventario resuelto sin excepciones:** el único uso fuera, el SHA-256 de
  `shared.audit.CanonicalAuditRowSerializer`, pasa a `shared.security.Digests.sha256(byte[])`, que
  también usa `RequestPayloadHasher`. El resultado es byte a byte el mismo; las pruebas existentes de
  la cadena de auditoría lo demuestran sin cambios. La regla queda sin lista de permitidos.
- La primera mitad de la verificación 13 (dominio de encargados frente a administrativos) no aplica
  todavía: no hay subdominio de encargados.

**Descartado:** una excepción para `shared.audit` en la regla: exigiría citar un ADR que no autoriza
eso y dejaría una entrada que se pudre.

### Decisión 17 — ArchUnit, Spring Modulith, `ProcessBeanPolicy` e inversiones

**`ProcessBeanPolicy`**, cada línea en el PR de su `@Import`:

| Proceso | Permitidos que se añaden | Prohibidos nominales que se añaden |
|---|---|---|
| `admin` | `com.confia.shared.security.token` (PR 2); `com.confia.organization.infrastructure`, `com.confia.organization.infrastructure.wiring` (PR 6); `com.confia.identity.web`, `com.confia.identity.web.wiring` (PR 15) | Ninguno |
| `portal` | Ninguno | `com.confia.shared.security.token` («claves de firma administrativas, ADR-0005 v14») |
| `worker` | Ninguno | `com.confia.shared.security.token` (mismo motivo) |

`AdminApplication` importa además `SessionTokenConfiguration`, `OrganizationConfiguration` e
`IdentityWebConfiguration`.

**Reglas existentes y su interacción:**

| Regla | Efecto de S1 |
|---|---|
| W1 (`web` sin `infrastructure` ni jOOQ) | Se cumple: el controlador solo usa `identity.application` y `shared.security` |
| W2a/W2b | W2a pierde su `allowEmptyShould` con el primer controlador (PR 15); el DTO solo lleva `UUID`, `Instant` y `List<String>` |
| W3 (`shared` sin otros módulos) | El filtro y el verificador viven en `shared`; los adaptadores en `identity` y `organization` |
| W4 (sin esperas fuera del materializador) | Sin cambios: ninguna espera nueva |
| W5 (sin idempotencia en `identity`) | Sin cambios |
| `LayeredArchitectureTest` | `shared.web.authentication` y `identity.web.wiring` son capa `Web`; acceden solo a `Application` o a paquetes sin capa |
| `TransactionsOnlyInSharedSecurityTest`, `JooqConfinedToInfrastructureTest` | Toda escritura pasa por `TransactionRunner`; jOOQ solo en `identity.infrastructure` |
| `NoBlockingWaitInIdentityTest` | Sin cambios |
| Spring Modulith | `@NamedInterface` nuevos: `shared.security.token`, `organization.infrastructure.wiring`, `identity.web.wiring`, cada uno con su consumidor en el Javadoc (ADR-0022) |

**Inversiones de O3**, cada una con control negativo:

| Prueba | Cambio | PR |
|---|---|---|
| `ProblemCatalogCoverageTest` | Deja de negar `token-invalid` y `token-expired`; sigue negando `authentication-failed`, `institution-not-found` e `institution-inactive` | 5 |
| `WebEdgeScopeExclusionInventoryTest`, reglas de ruta | `ALLOWED_ROUTE_RULES = {permitAll, authenticated, denyAll}`; roles, permisos y MFA siguen prohibidos (cambio 8) | 5 |
| `WebEdgeScopeExclusionInventoryTest`, `SessionValidity` | De «sin implementación ni dependientes» a «exactamente una implementación, `SessionLiveness`, en `identity`, y solo el filtro depende del puerto» | 14 |
| `IdentityScopeExclusionInventoryTest` | Decisión 12 | 9 |
| `EmptyShouldExceptionInventoryTest` | Retiro de la entrada W2a | 15 |
| `PublicRouteAllowListTest` y enumerador | Decisión 13 | 15 |
| Se conservan | Ningún `AuthenticationProvider`, ninguna `Cookie`, limitador y materializador sin uso fuera del borde, ninguna clase `web` con `Institution` en el nombre | — |

### Decisión 18 — Documentación: notas fechadas, sin reescribir cuerpos

| Documento | Nota | PR |
|---|---|---|
| `docs/05-infraestructura-y-despliegue.md` | Las cinco variables de la decisión 3, el nombre reservado del portal, y que ninguna tiene valor en el repositorio | 2 |
| `docs/03-seguridad.md` §4.5 y §11.2 | JWS propio sobre el JDK (DA-2), claves por variable de entorno hasta el cambio 11, rotación con `current`/`previous`, JWKS diferido (DA-16) | 4 |
| `docs/08-datos-privacidad-y-retencion.md` | IP y huella del agente en `identity_refresh_token_family`, conservadas hasta la purga del cambio 9 | 8 |
| `docs/adr/ADR-0005` | Nota fechada: lectura de las vigencias (decisión 4 del propietario), vocabulario `password_change`, gracia con acceso nuevo (si se aprueba D-N1) | 13 |
| `openspec/changes/foundations-plan/exploration.md` | «Quinto corte» (DA-1): partición, H2 cerrada en S1, H1 y condición 3 a S2, H3 y H4 a S3 | 15 |
| `docs/ui-ux/04-patrones-de-interaccion.md` | La SPA refresca solo ante actividad del usuario | 15 |

## 4. Flujo de datos

```
Autenticación de una petición (proceso administrativo)
  Authorization: Bearer <h.p.s>
     │ AccessTokenAuthenticationFilter
     ├─ CompactJws: longitud ─► cabecera exacta por kid ─► firma Ed25519 ─► JSON estricto ─► claims
     │      └ falla: AccessToken{Expired|Rejected}Exception ─► ProblemAuthenticationEntryPoint
     │                 ─► 401 token-expired | token-invalid + WWW-Authenticate
     ├─ ruta LIVE_SESSION ─► SessionValidity.isActive(actor) ─► SessionLiveness (identity)
     │                          └ TransactionRunner (RLS: tenant) ─► identity_refresh_token_family
     └─ ScopedValue(AuthenticatedActor) + ActorAuthentication ─► autorización ─► controlador

Rotación (S1: solo desde pruebas; S2: endpoint de refresco)
  texto ─► hash ─► token (sin bloqueo) ─► familia FOR UPDATE ─► relectura ─► vaciar sucesores >= 10 s
       ─► RotationDecision ─┬─ ROTATE: consumir (condicional) ─► cifrar sucesor ─► insertar ─► last_used_at
                            ├─ GRACE_REPLAY: descifrar sucesor ─► last_used_at
                            ├─ REUSE_DETECTED: revocar familia ─► vaciar sucesores
                            └─ REJECT_*: nada
       ─► AuditLogWriter (misma transacción) ─► commit ─► firmar acceso nuevo (si hay sucesor)

Restablecimiento (H2)
  ResetPasswordWithToken ─► ... ─► consumir token de restablecimiento ─► nuevo hash
       ─► AccountSessionRevoker (familias vivas FOR UPDATE ORDER BY id ─► password_change)
       ─► auditoría ─► commit (todo o nada)
```

## 5. Cambios de archivos

Rutas bajo `apps/api/app/src/` salvo indicación. «PR» remite a la sección 8.

| Archivo | Acción | PR |
|---|---|---|
| `main/java/com/confia/shared/security/token/{Base64Url,CompactJws,Ed25519Signatures,TokenRejection}.java`, `package-info.java` | Crear (decisión 2) | 1 |
| `main/java/com/confia/shared/security/token/{SigningKey,SigningKeyRing,SigningKeyLoader,SigningKeyPlacementGuard,SessionTokenConfiguration}.java` | Crear (decisión 3) | 2 |
| `main/java/com/confia/bootstrap/ConfiaApplication.java` | Añade el guardián a los tres procesos | 2 |
| `main/java/com/confia/bootstrap/admin/AdminApplication.java` | `@Import` de `SessionTokenConfiguration`, `OrganizationConfiguration`, `IdentityWebConfiguration` | 2, 6, 15 |
| `main/java/com/confia/shared/security/Digests.java`; `shared/audit/CanonicalAuditRowSerializer.java`; `shared/security/RequestPayloadHasher.java` | Crear y delegar (decisión 16) | 3 |
| `main/java/com/confia/shared/security/token/{AccessTokenClaims,MfaTokenClaims,AccessTokenIssuer,AccessTokenVerifier,AccessToken}.java` | Crear (decisión 4) | 4 |
| `main/java/com/confia/shared/security/{AuthenticatedActor,AuthenticationMethod}.java` | Crear (decisión 5) | 4 (tipos), 5 (uso) |
| `main/java/com/confia/shared/web/authentication/*.java`, `package-info.java` | Crear (decisión 5) | 5, 14 |
| `main/java/com/confia/shared/web/edge/{SecurityChains,AdminSecurityConfiguration}.java` | Filtro y lista autenticada | 5, 14 |
| `main/java/com/confia/shared/web/problem/{ProblemCode,ProblemResponses,ProblemAuthenticationEntryPoint}.java` | `token-invalid`, `token-expired`, `WWW-Authenticate` | 5 |
| `main/resources/i18n/problems.properties` | Títulos y detalles es-HN de los dos códigos | 5 |
| `main/java/com/confia/organization/infrastructure/TokenCurrentInstitutionProvider.java`, `organization/infrastructure/wiring/{OrganizationConfiguration,package-info}.java` | Crear (decisión 6) | 6 |
| `main/java/com/confia/shared/web/problem/ProblemExceptionHandler.java` | Decisión 14 | 7 |
| `main/resources/db/migration/V8__create_identity_refresh_token_tables.sql` | Crear (decisión 8) | 8 |
| `main/java/com/confia/identity/application/{AccountSessionRevoker,SessionRevocationReason}.java`, `identity/infrastructure/JooqAccountSessionRevoker.java` | Crear (decisión 12) | 9 |
| `main/java/com/confia/identity/application/ResetPasswordWithToken.java`, `identity/infrastructure/wiring/IdentityConfiguration.java` | Constructor y cableado (decisión 12) | 9, 10, 11, 14 |
| `main/java/com/confia/identity/domain/{PlainRefreshToken,RefreshTokenHash,UserAgentFingerprint,SessionLifetimePolicy,RefreshTokenFamily,RefreshTokenRow}.java` | Crear | 10 |
| `main/java/com/confia/identity/application/{IssueSession,IssueSessionCommand,IssuedSession,RefreshTokenFamilyRepository,RefreshTokenRepository}.java`, `identity/infrastructure/{JooqRefreshTokenFamilyRepository,JooqRefreshTokenRepository}.java` | Crear (decisión 9) | 10 |
| `main/java/com/confia/identity/domain/{RotationDecision,RefreshRejection}.java`, `identity/application/{RotateRefreshToken,RotateRefreshTokenCommand,RefreshOutcome}.java` | Crear (decisión 10) | 11, 12, 13 |
| `main/java/com/confia/shared/security/SessionValidity.java`; `identity/application/SessionLiveness.java` | Forma nueva e implementación (decisión 7) | 14 |
| `main/java/com/confia/identity/application/{DescribeCurrentSession,CurrentSessionView}.java`, `identity/web/{CurrentSessionController,CurrentSessionResponse}.java`, `identity/web/wiring/{IdentityWebConfiguration,package-info}.java` | Crear (decisión 13) | 15 |
| `main/java/com/confia/shared/web/openapi/ContractSchemas.java`; `main/resources/application.yml` | `bearerAuth`, `errors`; `override-with-generic-response: false` | 15 |
| `apps/api/openapi/admin.openapi.json` | Primera operación aprobada | 15 |
| `apps/api/app/pom.xml` | PIT: `com.confia.shared.security.token.*` en `targetClasses` y `targetTests` | 1 |
| `test/java/com/confia/bootstrap/{TestProcessArguments,ProcessBeanPolicy,ProcessBeanIsolationTest,ConfiaApplicationTest,PublicRouteAllowListTest,RegisteredRoutes,AdminProductionWiringTest}.java` | Claves de prueba, líneas, guardián, enumerador | 2, 6, 15 |
| `test/java/com/confia/architecture/{SigningAndHashingConfinementTest,WebEdgeScopeExclusionInventoryTest,EmptyShouldExceptionInventoryTest}.java` y `fixture/hashing/**` | Decisiones 16 y 17 | 3, 5, 14, 15 |
| `test/java/com/confia/identity/IdentityScopeExclusionInventoryTest.java` y sus fixtures | Inversión (decisión 12) | 9 |
| `test/java/com/confia/shared/web/problem/ProblemCatalogCoverageTest.java`, `ProblemTranslationTest.java` | Inversión y decisión 14 | 5, 7 |
| `test/resources/rfc8037/ed25519-a4.properties` | Clave pública, entrada de firma y firma de RFC 8037 A.4, sin clave privada | 1 |
| `test/java/**` restantes | Pruebas de la sección 7 | 1 a 15 |
| Documentos de la decisión 18 | Notas fechadas | 2, 4, 8, 13, 15 |

## 6. Interfaces y contratos

```java
// com.confia.shared.security.token
public final class CompactJws {
    public String sign(SigningKeyRing ring, byte[] payloadJson);
    public Verified verify(SigningKeyRing ring, String compact);   // or throws TokenRejectedException
}
public enum TokenRejection { MALFORMED, UNKNOWN_HEADER, BAD_SIGNATURE, MALFORMED_CLAIMS,
    CLAIMS_INVALID, EXPIRED }
public final class SigningKeyRing {
    public static SigningKeyRing fromEnvironment(Environment environment); // fails naming the property
    public String currentKid();
}
public final class AccessTokenIssuer {
    public AccessToken issueAccess(UUID accountId, UUID institutionId, UUID sessionId,
            Set<AuthenticationMethod> methods);                   // exp - iat = 600
    public AccessToken issueMfa(UUID accountId, UUID institutionId, MfaPurpose purpose); // 300
}
public final class AccessTokenVerifier {
    public AuthenticatedActor verifyAccess(String compact);       // throws TokenRejectedException
    public MfaTokenClaims verifyMfa(String compact);
}
public record AccessToken(String compact, Instant expiresAt) { /* toString redacts compact */ }

// com.confia.shared.security
public interface SessionValidity { boolean isActive(AuthenticatedActor actor); }

// com.confia.identity.application
public interface AccountSessionRevoker {
    int revokeAllLive(InstitutionId institution, StaffAccountId account,
            SessionRevocationReason reason, Instant now);
}
public sealed interface RefreshOutcome {
    record Rotated(IssuedSession session, RotationKind kind) implements RefreshOutcome { }
    record Rejected(RefreshRejection reason) implements RefreshOutcome { }
}
```

**Contrato HTTP nuevo:** `GET /api/v1/auth/sessions/current` (decisión 13). **Códigos nuevos:**
`token-invalid` (401) y `token-expired` (401). **Cabecera nueva:** `WWW-Authenticate` en todo `401`.
**Propiedades nuevas:** las cinco de la decisión 3 y `springdoc.override-with-generic-response`;
ninguna con valor secreto en el repositorio.

## 7. Estrategia de pruebas (TDD estricto)

Cada fila nace **en rojo** con el motivo esperado registrado en `apply-progress.md`. **Las pruebas de
ataque se escriben antes que el código del códec.** Ninguna prueba de concurrencia espera por reloj:
`CyclicBarrier`, `CountDownLatch` y `MutableClock`.

| Nivel | Qué | Cómo | Rojo esperado |
|---|---|---|---|
| Unidad | RFC 8037 A.4: verificación de la firma publicada con la clave pública del RFC y rechazo con un bit alterado; firma determinista con un par generado en la prueba (I46) | `Ed25519SignaturesRfc8037Test`, con la clave pública cruda envuelta en X.509 (prefijo DER fijo), lo que ejercita también el cargador; sin clave privada versionada | Clase inexistente |
| Unidad | El vector completo de A.4 se **rechaza** como token (su cabecera no tiene `kid`): `UNKNOWN_HEADER` | `CompactJwsAttackTest` | Clase inexistente |
| Unidad, ataques | `alg: none` con firma vacía; `HS256` con HMAC de la clave pública; `alg` ausente, duplicado o en minúsculas; `kid` ausente, desconocido o duplicado; `crit`, `jku`, `jwk`, `typ` añadidos; espacios y orden distinto en la cabecera; relleno `=`; caracteres de base64 estándar (`+`, `/`); bits de cola distintos de cero; segmento vacío; cuatro segmentos; longitud > 2048; firma de 63 y 65 bytes; `S + L` (S-2); firma de otra clave con el mismo `kid` (prueba 4 de ADR-0005: el rechazo es por firma) | `CompactJwsAttackTest`, parametrizada | Clase inexistente |
| Unidad + jqwik | Propiedad: todo token válido con un bit alterado en cualquier posición se rechaza; `Base64Url` ida y vuelta y canónico frente al codificador del JDK | `CompactJwsProperties`, `Base64UrlProperties` | Clase inexistente |
| Unidad | Claims: lista cerrada (claim extra, repetido, nulo, tipo distinto, `aud` como arreglo); `exp - iat = 600` y `aud = confia-admin` (prueba 3 de ADR-0005); `iat` futuro; `exp == now` vencido; restringido con `aud` y `purpose`; restringido rechazado por `verifyAccess` y acceso por `verifyMfa`; ningún claim con datos personales | `AccessTokenVerifierTest`, `AccessTokenIssuerTest` | Clases inexistentes |
| Unidad | Anillo: propiedad ausente, Base64 inválido, Ed448 (S-3), par que no corresponde, `kid` inválido o repetido, `previous` sin pública; mensajes sin valor | `SigningKeyRingTest` | Clase inexistente |
| Contexto real sin contenedor | Administración no arranca sin clave; portal y trabajador no arrancan con la privada administrativa; administración no arranca con el nombre reservado del portal; los tres arrancan con `TestProcessArguments` (S-6) | `SigningKeyStartupTest` por `ConfiaApplication.launch` | Arranque sin fallo |
| Contexto real | Línea nueva de `ProcessBeanPolicy` en rojo antes de añadirla | `ProcessBeanIsolationTest` | «not in the allow-list» |
| Estático | Regla de firma y hash con fixture | `SigningAndHashingConfinementTest` | Falla por `CanonicalAuditRowSerializer` hasta extraer `Digests` |
| Contexto (arnés sin base de datos) | Filtro: sin credencial `401 authentication-required` + `WWW-Authenticate: Bearer`; token alterado, de otra clave, restringido, con dos cabeceras `Authorization` → `401 token-invalid`; vencido → `401 token-expired`; válido en ruta autenticada del arnés → `200`; válido fuera de las listas → `403`; `Basic` → anónimo; el portal no tiene filtro | `AccessTokenAuthenticationFilterTest` | `401 authentication-required` en todo caso |
| Contexto | ADR-0009: cabecera, consulta y cuerpo no alteran la institución | `CurrentInstitutionFromTokenTest` (arnés) | Sin adaptador |
| Contexto | Registros sin `Detail: Key`, con control negativo | `ProblemTranslationTest` (decisión 14) | La traza completa en el registro |
| Integración (`*IT`) | V8 con `confia_admin_app`: aislamiento A/B en ambas tablas, cero filas sin contexto, `INSERT` con otra institución rechazado por `WITH CHECK`, `DELETE` rechazado (`42501`), `UPDATE` de `token_hash` y de `account_id` rechazado, `UPDATE` de las columnas concedidas aceptado, `FOR UPDATE` aceptado; `confia_readonly` sin escritura; `confia_portal_app` sin acceso; `CHECK` de consumo, sucesor y motivo | `RefreshTokenSchemaIT` | Tablas inexistentes |
| Integración | **I24:** un restablecimiento revoca el 100 % de las familias vivas de la cuenta con `password_change`, conserva el motivo de una ya revocada, no toca otra cuenta y escribe el asiento; **control negativo:** un escritor de auditoría que falla en `identity.password_reset.completed` revierte todo y ninguna familia queda revocada; un rechazo (token, contraseña, segundo factor) no revoca nada | `PasswordResetRevokesSessionsIT` | Ninguna familia revocada |
| Unidad | `ResetPasswordWithToken`: el revocador se llama una vez y solo en `Completed`, con institución, cuenta, motivo y `now` | Pruebas existentes actualizadas | Constructor viejo |
| Integración | Emisión: filas y asiento en la misma transacción; prueba 5 de ADR-0005; vigencias de familia y de refresco | `IssueSessionIT` | Clase inexistente |
| Unidad + jqwik | `RotationDecision`: tabla completa de la decisión 10 con bordes exactos (10 s, 8 h, 12 h, 30 min, `expires_at` recortado por la absoluta), y propiedad «nunca `GRACE_REPLAY` con `now - consumed_at >= 10 s`» | `RotationDecisionTest`, `RotationDecisionProperties` | Clase inexistente |
| Integración | Rotación: sucesor único, `last_used_at`, rechazos sin efecto, prueba 1 de ADR-0005 (reutilización revoca, ningún token vigente, asiento); **la revocación por reutilización se confirma**: se lee en una transacción nueva después del rechazo | `RotateRefreshTokenIT` | Clase inexistente |
| Integración | Gracia: prueba 2 de ADR-0005 (mismo sucesor, familia viva); otra huella → reutilización; a los 10 s exactos → reutilización; el sucesor cifrado se vacía en el siguiente contacto; copiar el cifrado a otra fila no descifra | `RefreshGraceWindowIT` | Sin columna de sucesor |
| Concurrencia (dos transacciones reales) | *(a)* mismo token, misma huella: un `FRESH` y un `GRACE` con el mismo sucesor, un solo token abierto; *(b)* mismo token, huella distinta: un `FRESH` y un `REUSE_DETECTED`, familia revocada; *(c)* rotación frente a restablecimiento: la familia termina revocada en todo orden. La primera transacción se retiene con un `CountDownLatch` dentro de un repositorio decorado justo después de tomar el bloqueo, y se libera cuando `pg_stat_activity` muestra a la segunda esperando un bloqueo (condición con límite, no espera fija) | `RefreshRotationConcurrencyIT` | Dos sucesores o un interbloqueo |
| Integración | `SessionLiveness`: revocada, vencida absoluta, inactiva, de otra cuenta, inexistente → falso; viva → verdadero; ruta `LIVE` con familia revocada → `401 token-invalid` aunque el token no haya vencido (riesgo de ADR-0005) | `SessionLivenessIT` | Puerto sin implementación |
| Contexto real + IT | Sesión actual: `200` con el DTO exacto y sin campos de más; `401` en sus tres variantes; restringido no la abre; instantánea OpenAPI aprobada (S-4) | `CurrentSessionEndpointIT`, `OpenApiContractSnapshotTest` | Ruta inexistente |
| Estático | Inversiones de la decisión 17, cada una con su control negativo | Pruebas de la tabla | Las ausencias de 4a |

- **PIT** al menos 80 % sobre `com.confia.shared.security.token.*` y `com.confia.identity.domain.*`;
  JaCoCo al menos 95 % en `identity.domain`.
- **Testcontainers** compartido por todas las `*IT` nuevas para cuidar el presupuesto de `verify`
  (5 min 24 s hoy, 8 min de tope).
- **Trazabilidad:** cada escenario de la especificación se mapea en `tasks.md` a una de estas pruebas.

## 8. Plan de entrega en cadena

`auto-chain`, `stacked-to-main`, 800 líneas efectivas por PR (`docs/15` §3). Líneas efectivas de
autor: código, pruebas y documentación; sin `openspec/` ni código generado. «Realista» aplica el
factor central de 2,2 del historial (rango de 2 a 3). Cada PR cierra con `./mvnw verify` completo.

| # | PR | Contenido | Nominal → realista | Costura si excede |
|---|---|---|---|---|
| 1 | C4a-1 `jws-compact-codec` | `Base64Url`, `CompactJws`, `Ed25519Signatures`, RFC 8037, pruebas de ataque, PIT | 330 → 730 | Propiedades jqwik a 1b |
| 2 | C4a-2 `signing-key-ring` | Anillo, cargador, par, guardián de arranque, claves de prueba, política, nota `docs/05` | 330 → 730 | Guardián a 2b |
| 3 | C4a-3 `signing-hash-confinement` | Regla de la verificación 13, `Digests` | 200 → 440 | — |
| 4 | C4a-4 `access-and-mfa-tokens` | Claims, emisor, verificador, pruebas 3 y 4, nota `docs/03` | 320 → 700 | Token restringido a 4b |
| 5 | C4b-1 `bearer-authentication-filter` | Filtro, principal, lista autenticada, dos códigos, `WWW-Authenticate`, inversiones de catálogo y de reglas de ruta | 360 → 790 | `WWW-Authenticate` y catálogo a 5b |
| 6 | C4b-2 `current-institution-adapter` | Adaptador, configuración de `organization`, prueba de ADR-0009 | 220 → 480 | — |
| 7 | O1 `data-access-log-redaction` | Decisión 14 | 150 → 330 | — |
| 8 | C5a-1 `refresh-token-store-v8` | V8, pruebas de esquema y RLS, nota `docs/08` | 300 → 660 | — |
| 9 | C7a `revoke-sessions-on-reset` (**H2**) | Puerto, adaptador, `ResetPasswordWithToken`, I24, inversión del inventario | 360 → 790 | 9a/9b (decisión 12) |
| 10 | C5a-2 `session-issuance` | Dominio de refresco, repositorios, `IssueSession`, prueba 5 | 330 → 730 | Repositorios a 10a |
| 11 | C5b-1 `refresh-rotation` | `RotationDecision`, rotación fresca, vigencias, rechazos | 300 → 660 | — |
| 12 | C5b-2 `reuse-detection` | Reutilización confirmada, prueba 1, concurrencia *(b)* y *(c)* | 300 → 660 | Concurrencia a 12b |
| 13 | C5b-3 `grace-window` | Sucesor cifrado, gracia, vaciado, prueba 2, concurrencia *(a)*, nota ADR-0005 | 330 → 730 | — |
| 14 | C5b-4 `session-validity` | `SessionLiveness`, comprobación `LIVE` en el filtro, inversión del inventario de `SessionValidity` | 260 → 570 | — |
| 15 | C4b-3 `current-session-endpoint` | Ruta, DTO, OpenAPI, `errors`, contratos, W2a, enumerador, notas de transferencia y de `docs/ui-ux` | 360 → 790 | Notas documentales a 15b |

- **Órdenes duros:** 8 → 9 → 10 (H2). Ninguna clase de `identity` que nombre una sesión antes del 9
  (`IdentityScopeExclusionInventoryTest`). 14 antes de 15 (la ruta es `LIVE_SESSION`). 4 antes de 5.
  Los PR 3, 6 y 7 solo dependen de sus anteriores inmediatos por la pila; podrían reordenarse.
- **Total:** unas 4 450 líneas nominales (la propuesta estimaba unas 3 900 en 12 PR; este desglose
  añade el guardián, la regla de la verificación 13, la corrección de `unexpected` como PR propio y
  la separación de reutilización y gracia). **Realista: de 8 900 a 13 400 líneas, unas 9 800 en el
  centro**, en 15 PR; con todas las costuras, 21.
- **Tareas:** una por PR, **15, exactamente el límite** de `openspec/changes/README.md`. Cualquier
  costura lo supera: la fase de tareas lo declara y pide la decisión antes de aplicar, como fija la
  propuesta.
- **Reversión:** en orden inverso. Revertir el 9 exige revertir antes del 10 al 15 (no dejar emisión
  sin revocación). Revertir el 5 devuelve la cadena a denegar todo lo no público.

## 9. Notas fechadas y correcciones para la especificación

**Notas fechadas sobre la propuesta (2026-10-06), sin cambiar ninguna decisión del propietario:**

- **N-1, orden de la cadena.** La tabla provisional de la propuesta ponía la ruta de la sesión actual
  (su PR 5) antes de V8 y de H2. No es posible: el controlador y el adaptador de `SessionValidity`
  viven en `identity` y nombran una sesión, lo que rompe
  `IdentityScopeExclusionInventoryTest` hasta el PR de H2; y la ruta exige comprobar `sid` contra V8
  (decisión 10 del propietario). La ruta pasa al final (PR 15). El orden V8 → H2 → emisión no cambia.
- **N-2, sondas.** Ninguna se ejecutó en esta fase (sección 2.1); cada una es la primera prueba en
  rojo de su PR.
- **N-3, código de sesión revocada.** Una ruta `LIVE_SESSION` con familia revocada responde
  `token-invalid`; no se crea `session-revoked` (decisión 5).

**Correcciones que la especificación debe recoger:**

| # | Requisito o escenario | Corrección | Motivo |
|---|---|---|---|
| 1 | Token restringido «rechazado en toda ruta» | El rechazo es `401 token-invalid` por audiencia (`confia-admin-mfa`) | Decisión 4 |
| 2 | Ventana de gracia «devuelve el mismo par» | Mismo refresco, acceso nuevo, según D-N1 | Decisión 11 |
| 3 | Motivos de revocación | Solo `reuse_detected` y `password_change` en V8 | Decisión 8 y decisión 7 del propietario |
| 4 | Vigencia de sesión como puerto | `isActive(AuthenticatedActor)` en lugar de `isActive(UUID)` | Decisión 7 |
| 5 | Verificación de arranque | Tabla de la decisión 3, con el nombre reservado del portal si se aprueba D-N2 | Decisión 3 |
| 6 | `WWW-Authenticate` | Valores exactos de la decisión 5, también en el portal | O5 |

## 10. Riesgos

| # | Riesgo | Mitigación |
|---|---|---|
| R-1 | Defecto en el JWS propio | Cabecera exacta por `kid`, carga analizada solo tras la firma, pruebas de ataque primero, jqwik de bit alterado, PIT, revisión de seguridad del PR 1 |
| R-2 | El JDK acepta una firma maleable (S-2) | Prueba en rojo en el PR 1; comprobación `S < L` propia si hace falta |
| R-3 | Ninguna sonda se ejecutó en diseño | Sección 2.1: cada sonda es la primera prueba de su PR y detiene el PR si difiere |
| R-4 | Carrera en la rotación | Bloqueo de la familia, relectura, actualización condicional, índices únicos parciales, tres pruebas con dos transacciones reales |
| R-5 | La revocación por reutilización se revierte con el rechazo | Desenlace tipado; prueba que lee la revocación en una transacción nueva |
| R-6 | El refresco toma la institución de `LoginInstitutionProvider`: válido mientras el proceso administrativo sirve una sola institución | Mismo supuesto que el inicio de sesión y el restablecimiento (decisión 11 de la parte 1); un despliegue multiinstitución exigirá llevar la institución en el refresco, con su propio cambio |
| R-7 | Sucesor cifrado recuperable más de 10 s en una familia que nadie vuelve a tocar | Descifrado imposible fuera de la ventana por construcción; purga del cambio 9 |
| R-8 | 15 PR en el límite de 15 tareas; cualquier costura lo supera | Decisión antes de aplicar (sección 8) |
| R-9 | Jackson de Spring con propiedades desconocidas sin fijar (S-5) | No afecta a S1 (sin cuerpos); el códec usa su propio `JsonMapper`; S2 lo fija con su prueba |
| R-10 | Instantánea OpenAPI con respuestas no aprobadas | `override-with-generic-response: false` y `@ApiResponse` explícitos |
| R-11 | Claves privadas en variables de entorno hasta el cambio 11 | Guardián de arranque, nota en `docs/03` y `docs/05` |
| R-12 | El vector de RFC 8037 dispara el escáner de secretos | Resuelto: no se versiona su clave privada (decisión del propietario del 2026-10-06) |
| R-13 | Refactorizar el SHA-256 de la auditoría altera la cadena | `Digests.sha256` es la misma llamada; las pruebas existentes de la cadena corren sin cambios |
| R-14 | Presupuesto de `verify` (5 min 24 s de 8 min) | Contenedor compartido; las puertas de tiempo son de S2 y S3 |
| R-15 | Volumen de auditoría por refresco exitoso | Unos 2 400 asientos diarios con 50 personas; aceptable para la cadena |
| R-16 | Subestimación de tamaño | Costura nombrada por PR; replanificación al superar el 50 % del realista |

## 11. Matriz de amenazas

La matriz de la fase de diseño no aplica: el cambio no toca órdenes de consola, subprocesos,
automatización de Git o de PR ni clasificación de archivos ejecutables.

| Frontera | Aplicabilidad |
|---|---|
| Rutas con apariencia de documentación | N/A: no se clasifican ni ejecutan archivos |
| Selección de repositorio Git, estado del commit, estado del push, órdenes de PR | N/A: sin automatización de Git ni de PR |

Fronteras de seguridad propias, cada una con prueba en rojo en la sección 7:

| Frontera | Comportamiento seguro | Prueba |
|---|---|---|
| Confusión de algoritmo y de cabecera | Solo la cabecera exacta de un `kid` conocido | `CompactJwsAttackTest` |
| Token de otra clave o de otro dominio | Rechazo por firma | `CompactJwsAttackTest` (prueba 4 de ADR-0005) |
| Token restringido como acceso | Rechazo por audiencia | `AccessTokenVerifierTest`, `AccessTokenAuthenticationFilterTest` |
| Institución elegida por el cliente | Solo del token | `CurrentInstitutionFromTokenTest` |
| Sesión revocada con acceso vigente | `401` en rutas `LIVE_SESSION` | `SessionLivenessIT` |
| Reutilización de un refresco robado | Familia revocada y confirmada | `RotateRefreshTokenIT`, `RefreshRotationConcurrencyIT` |
| Clave privada administrativa en el portal o el trabajador | El proceso no arranca | `SigningKeyStartupTest` |
| Fuga de datos en el registro por un error de base de datos | Solo clase y `SQLState` | `ProblemTranslationTest` |

## 12. Decisiones nuevas para el propietario

**D-N1. Qué devuelve la ventana de gracia.** ADR-0005 dice «el par emitido en el paso 3».
- *a)* El mismo token de refresco sucesor y un token de acceso **nuevo** (mismos `sid`, `sub`,
  `tenant` y `amr`; otro `jti` e `iat`). El acceso es sin estado y equivalente; no se guarda nada más.
- *b)* Guardar también el token de acceso cifrado 10 s para devolver el mismo. Otra columna cifrada y
  otro secreto recuperable, sin beneficio de seguridad.
- **Recomendación: *a)*, con nota fechada en ADR-0005.**

**D-N2. Verificación de arranque del lado administrativo frente a la clave del portal.** La decisión 9
del propietario fija lo que comprueban el portal y el trabajador; ADR-0005 (verificación 14) pide
también que administración compruebe que la clave privada del otro dominio no está en su entorno, y
esa clave todavía no existe (DA-17).
- *a)* Reservar ahora el nombre `confia.security.portal-signing.*.private-key` y que administración
  no arranque si aparece. Unas 20 líneas, simétrico, y la verificación 14 queda completa.
- *b)* Diferir la mitad administrativa al cambio que traiga el par del portal.
- **Recomendación: *a)*.**

## 13. Preguntas abiertas

- [ ] D-N1 y D-N2 (sección 12).
- [ ] Ninguna otra pregunta bloquea las tareas.

## 14. Decisiones del propietario sobre las decisiones nuevas (2026-10-06)

- **D-N1, opción (a).** En la ventana de gracia se devuelve el mismo token de refresco sucesor y un token de acceso recién emitido.
  ADR-0005 recibe una nota fechada que precisa «el mismo par» en ese sentido.
- **D-N2, opción (a).** Se reserva desde ahora el nombre `confia.security.portal-signing.*.private-key`, y el proceso administrativo
  no arranca si lo encuentra.
