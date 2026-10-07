# Tareas: núcleo de tokens, familias de refresco y revocación al restablecer (parte 4b, S1)

- **Cambio:** `session-tokens-and-web-layer` (F0, cambio 7, parte 4b, primer cambio de tres)
- **Fase:** tareas
- **Fecha:** 2026-10-06
- **Estado:** pendiente de aprobación del propietario
- **Entradas aprobadas:** `proposal.md` (con «Decisiones del propietario»), `design.md` (secciones 3, 7, 8, 9 y 14, y las
  notas fechadas de 2.1) y los deltas `specs/identity/spec.md` (124 escenarios), `specs/web-edge/spec.md` (68),
  `specs/build-integrity/spec.md` (43) y `specs/organization/spec.md` (12): **247 escenarios**
- **Modo de TDD:** estricto. Ejecutor: `./mvnw verify` en `apps/api`, con `JAVA_HOME` apuntando a JDK 25 y Docker en ejecución.
- **Línea base de la cadena:** `main` en `2a599f5`. Rama de planificación: `change/session-tokens-and-web-layer`.

## Review Workload Forecast

| Field | Value |
|-------|-------|
| Estimated changed lines (additions + deletions, sin `openspec/` ni código generado) | **nominal unas 4 450; realista de 8 900 a 13 400 (centro unas 9 800)** con la corrección de 2 a 3 veces del historial (4a: 4 670 nominales, unas 11 000 a 12 700 reales) |
| 400-line budget risk | **High** frente a 400 de la preflight; **medio a alto por PR** frente al presupuesto del proyecto de 800 (realista de 440 a 790 por PR) |
| Chained PRs recommended | Yes |
| Suggested split | 15 PR apilados contra `main`, uno por tarea 1.1 a 5.2, en el orden del diseño §8 |
| Delivery strategy | `auto-chain` |
| Chain strategy | `stacked-to-main`: cada PR se fusiona a `main` en orden y el siguiente parte de `main` actualizado |
| Presupuesto del proyecto por PR (`docs/15-flujo-de-trabajo-git.md` §3) | 800 líneas de cambio efectivo |

Decision needed before apply: No (15 tareas, el límite de `openspec/changes/README.md`; `auto-chain` con `stacked-to-main` ya decidido)
Chained PRs recommended: Yes
Chain strategy: stacked-to-main
400-line budget risk: High

**Conflicto de estrategia.** La preflight de la sesión registra `single-pr` con revisión de 400 líneas. La sesión de esta fase
fija `auto-chain` con `stacked-to-main` y 800 líneas efectivas por PR, que es lo que exigen `CLAUDE.md` y `docs/15` §3, y es
lo que este documento registra (igual que 4a).

**Tareas.** 15, una por PR, **exactamente el límite**. No hay tareas de costura reservadas. Con el factor realista, los PR 1, 5,
9, 12, 13 y 15 están cerca de 800 y son los candidatos a partirse. **Regla:** si la medición de un PR supera 800 líneas, el
ejecutor se detiene y consulta al orquestador antes de partir. Cada costura nombrada en el diseño §8 (1b, 2b, 4b, 5b, 9a/9b, 10a, 12b,
15b) lleva el total por encima de 15 y exige antes replantear esta lista y una decisión del propietario, que ya concedió esa
excepción en 4a pero debe decidir de nuevo. Total con todas las costuras: 21 tareas.

**Orden duro.** 8 → 9 → 10 (H2: ningún commit de `main` emite refrescos sin revocación; ninguna clase de `identity` nombra una
sesión antes del PR 9 por `IdentityScopeExclusionInventoryTest`); 4 antes de 5; 14 antes de 15 (la ruta es `LIVE_SESSION`); la
ruta de la sesión actual va **última** (nota N-1). Los PR 3, 6 y 7 podrían reordenarse, pero no se hace sin decisión.

**Sondas.** S-1 a S-3 ya corrieron (nota fechada de `design.md` §2.1) y son pruebas ordinarias en los PR 1 y 2. S-4 y S-6 son el
**primer rojo** de los PR 15 y 2. S-5 es de S2.

### Reglas comunes a todas las tareas

- **Comandos.** Todos desde `apps/api`. `./mvnw verify` completo cierra cada tarea. Las órdenes focalizadas son solo para iterar:
  un `-Dtest=` acotado informa `BUILD FAILURE` por JaCoCo aunque las pruebas pasen, y eso no es un fallo de la tarea. Forma
  unitaria: `./mvnw -pl app -am verify -DskipITs -Dsurefire.failIfNoSpecifiedTests=false -Dtest='A,B'`. Forma de integración:
  `./mvnw -pl app -am verify -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=X -Dfailsafe.failIfNoSpecifiedTests=false`.
- **Rojo registrado.** Cada paso en rojo corre contra el árbol sin el código de producción y se registra en
  `openspec/changes/session-tokens-and-web-layer/apply-progress.md` con la causa **observada**. Si falla por otra causa, se
  detiene y se investiga. Un commit en rojo no entra al historial: las pruebas en rojo se comprometen con su verde.
- **Demostración deliberada.** Cada tarea nombra una ruptura temporal de producción que debe poner una prueba en rojo; se registra y se revierte.
- **Las pruebas de ataque del códec se escriben antes que el códec** (decisión 2).
- **Nombres explícitos.** Todo `@RequestParam`, `@PathVariable` y `@RequestHeader` declara su nombre (sin `-parameters`).
- **Salida de jqwik.** La línea dirigida a «agentes de IA» es salida de la herramienta y se ignora; se leen el código de salida y los informes.
- **Concurrencia determinista.** Sin esperas por reloj: `CyclicBarrier`, `CountDownLatch`, `MutableClock`. La segunda transacción se
  libera cuando `pg_stat_activity` muestra una espera de bloqueo (condición con límite, no espera fija).
- **Lista de permitidos.** Cada `@Import` nuevo y su línea de `ProcessBeanPolicy` entran en el mismo PR; se demuestra primero el rojo
  (`ProcessBeanIsolationTest` falla con `not in the allow-list` nombrando bean y paquete) y luego el verde.
- **Claves y secretos.** Ninguna clave privada se versiona (regla 13, decisión del propietario del 2026-10-06): los pares se generan
  en tiempo de ejecución con `KeyPairGenerator.getInstance("Ed25519")` en el árbol de pruebas. Del vector RFC 8037 A.4 solo se
  versionan clave pública, entrada de firma y firma publicada.
- **Registros.** Nunca un token, cabecera, firma, hash de refresco, sucesor cifrado, `jti` ni huella de agente en logs ni bitácora.
- **Medición antes de abrir el PR.** `git diff --numstat main...HEAD` excluyendo `openspec/` y código generado, registrada en `apply-progress.md`. Si supera 800, se detiene.
- **Commits.** Conventional Commits en inglés, sin atribución de herramientas de IA, con pruebas y docs en el mismo commit que el
  comportamiento. Una rama por PR: `change/session-tokens-and-web-layer-NN-<nombre>` (el PR 1 sale de la rama de planificación).
- **Idioma.** Código, identificadores y commits en inglés; documentación y notas fechadas en español neutro profesional; textos de
  interfaz solo por `problems.properties`.
- **Notas documentales.** Notas fechadas, sin reescribir cuerpos existentes.

### Suggested Work Units

| Unidad | Objetivo | PR probable (nominal → realista) | Comando de prueba enfocado | Arnés de ejecución | Frontera de reversión |
|---|---|---|---|---|---|
| 1.1 | Códec JWS Ed25519, base64url estricto, vectores RFC 8037, ataques, PIT | PR 1 `jws-compact-codec` (330 → 730) | `-Dtest='Ed25519SignaturesRfc8037Test,CompactJwsAttackTest,CompactJwsProperties,Base64UrlProperties'` | Códec con anillo en memoria y pares generados; `./mvnw verify` con perfil `mutation-report` | Se retira `shared.security.token` y la entrada de PIT |
| 1.2 | Anillo de claves, cargador, guardián de arranque, claves de prueba | PR 2 `signing-key-ring` (330 → 730) | `-Dtest='SigningKeyRingTest,SigningKeyStartupTest,ProcessBeanIsolationTest'` | `ConfiaApplication.launch` real de los tres procesos sin Docker | Se retiran loader, guardián, configuración y su `@Import` |
| 1.3 | Regla de firma y hash y `Digests` | PR 3 `signing-hash-confinement` (200 → 440) | `-Dtest='SigningAndHashingConfinementTest,CanonicalAuditRowSerializer*Test,RequestPayloadHasher*Test'` | Fixtures de dos mitades; cadena de auditoría existente sin cambios | `Digests` se inlinea de nuevo y se retira la regla |
| 1.4 | Token de acceso y restringido, emisor, verificador | PR 4 `access-and-mfa-tokens` (320 → 700) | `-Dtest='AccessTokenVerifierTest,AccessTokenIssuerTest,WebLayerTokenIsolationTest'` | Verificación con `MutableClock`; propiedades de claims | Se retiran claims, emisor, verificador y principal |
| 2.1 | Filtro `Bearer`, principal, lista autenticada, `token-invalid`, `token-expired`, `WWW-Authenticate` | PR 5 `bearer-authentication-filter` (360 → 790) | `-Dtest='AccessTokenAuthenticationFilterTest,AdminSecurityChainTest,PublicEndpointsTest,ProblemCatalogCoverageTest,WebEdgeScopeExclusionInventoryTest'` | Cadena real por HTTP (`RANDOM_PORT`) en el arnés sin base de datos | Se retira el filtro: la cadena vuelve a denegar todo lo no público |
| 2.2 | Adaptador de `CurrentInstitutionProvider` y prueba de ADR-0009 | PR 6 `current-institution-adapter` (220 → 480) | `-Dtest='CurrentInstitutionFromTokenTest,TokenCurrentInstitutionProviderTest,ProcessBeanIsolationTest'` | Controlador del arnés con institución A y cabeceras, consulta y cuerpo de B | Se retiran adaptador y configuración |
| 2.3 | Corrección de `ProblemExceptionHandler.unexpected` (O1) | PR 7 `data-access-log-redaction` (150 → 330) | `-Dtest='ProblemTranslationTest,ProblemExceptionHandlerTest'` | `PSQLException` real con `ListAppender` | Se retira el recorrido de causas |
| 3.1 | V8, jOOQ, RLS, privilegios por columna | PR 8 `refresh-token-store-v8` (300 → 660) | IT: `-Dit.test='RefreshTokenSchemaIT,RolePrivilegeMatrixIT,MultiTenantSchemaIT'` | PostgreSQL real con el rol `confia_admin_app` | Se elimina la migración (antes de producción) y se regenera jOOQ |
| 3.2 | **H2:** revocación de todas las familias al restablecer | PR 9 `revoke-sessions-on-reset` (360 → 790) | `-Dtest='ResetPasswordWithTokenTest,IdentityScopeExclusionInventoryTest'`; IT: `-Dit.test=PasswordResetRevokesSessionsIT` | I24 con PostgreSQL real y control negativo | Revertir devuelve el constructor viejo; exige haber revertido antes 3.3 a 5.2 |
| 3.3 | Emisión de una familia | PR 10 `session-issuance` (330 → 730) | IT: `-Dit.test=IssueSessionIT`; unidad `-Dtest='PlainRefreshToken*Test,RefreshTokenHash*Test,SessionLifetimePolicyTest'` | `IssueSession` contra PostgreSQL | Se retiran dominio, repositorios y caso de uso |
| 4.1 | Rotación fresca, vigencias y rechazos | PR 11 `refresh-rotation` (300 → 660) | `-Dtest='RotationDecisionTest,RotationDecisionProperties'`; IT: `-Dit.test=RotateRefreshTokenIT` | Rotación con `MutableClock` y PostgreSQL | Se retiran decisión y caso de uso |
| 4.2 | Reutilización confirmada y concurrencia (b) y (c) | PR 12 `reuse-detection` (300 → 660) | IT: `-Dit.test='RotateRefreshTokenIT,RefreshRotationConcurrencyIT'` | Dos transacciones reales con `CountDownLatch` | Se retira la rama de reutilización |
| 4.3 | Ventana de gracia con sucesor cifrado y concurrencia (a) | PR 13 `grace-window` (330 → 730) | IT: `-Dit.test='RefreshGraceWindowIT,RefreshRotationConcurrencyIT'` | AAD ligada a la fila, bordes de 10 s | Se retira la rama de gracia |
| 5.1 | `SessionLiveness` y comprobación `LIVE_SESSION` en el filtro | PR 14 `session-validity` (260 → 570) | IT: `-Dit.test=SessionLivenessIT`; unidad `-Dtest='AccessTokenAuthenticationFilterTest,WebEdgeScopeExclusionInventoryTest'` | Familia revocada con token vigente → `401` | Se retira el adaptador y el puerto vuelve sin consumidor |
| 5.2 | `GET /api/v1/auth/sessions/current`, OpenAPI, contratos, notas de transferencia | PR 15 `current-session-endpoint` (360 → 790) | IT: `-Dit.test=CurrentSessionEndpointIT`; unidad `-Dtest='OpenApiContractSnapshotTest,PublicRouteAllowListTest,EmptyShouldExceptionInventoryTest'` | Proceso administrativo real con token emitido en la prueba | Se retiran ruta, DTO, configuración y la instantánea |

---

## Fase 1: núcleo criptográfico y de tokens

- [x] 1.1 **PR 1 `jws-compact-codec`: códec JWS Ed25519 (decisión 2).** Partida en 1.1a, 1.1b y 1.1c (nota fechada del
  final, 2026-10-06); el contenido de abajo es el alcance conjunto de las tres partes. Rutas bajo `apps/api/app/src/` salvo indicación.
  - **ROJO, ataques primero.** Crear en `test/java/com/confia/shared/security/token/`: `Ed25519SignaturesRfc8037Test.java`
    (S-1 ya probada: la firma publicada de RFC 8037 A.4 verifica con la clave pública del RFC, envuelta en X.509 con el prefijo DER
    fijo; con un bit alterado se rechaza; con un par generado en la prueba la firma es determinista, I46),
    `CompactJwsAttackTest.java` (parametrizada: `alg` `none` con firma vacía, `HS256` con HMAC de la clave pública, `RS256`, `ES256`,
    `eddsa` en minúsculas, `alg` ausente o duplicado, `kid` ausente, desconocido, vacío, de tipo erróneo o duplicado, `crit`, `jku`,
    `jwk`, `typ`, espacios y orden distinto, relleno `=`, `+` y `/`, bits de cola no nulos, segmento vacío, cuatro segmentos, longitud
    mayor que 2 048, firma de 63 y 65 bytes, `S + L` (S-2: el JDK **lanza** `SignatureException`; se espera `BAD_SIGNATURE`, nunca
    excepción), firma de otra clave con el mismo `kid`, vector A.4 completo rechazado con `UNKNOWN_HEADER`, y no recurso a otras
    claves del anillo), `CompactJwsProperties.java` y `Base64UrlProperties.java` (jqwik: bit alterado en cualquier posición se
    rechaza; ida y vuelta canónica frente al codificador del JDK), y `test/resources/rfc8037/ed25519-a4.properties` (solo clave
    pública, entrada de firma y firma publicada, citando el RFC). **Rojo esperado:** error de compilación por clases inexistentes.
  - **VERDE.** Crear en `main/java/com/confia/shared/security/token/`: `Base64Url`, `Ed25519Signatures` (una instancia de
    `Signature` por llamada), `TokenRejection` (`MALFORMED, UNKNOWN_HEADER, BAD_SIGNATURE, MALFORMED_CLAIMS, CLAIMS_INVALID, EXPIRED`),
    `TokenRejectedException`, `SigningKey` y `SigningKeyRing` mínimos **en memoria** (`SigningKeyRing.of(...)`, con los segmentos
    de cabecera canónicos precalculados por `kid`; el PR 2 añade `fromEnvironment`; la nota de abajo explica la costura), `CompactJws`
    (`sign` y `verify` con los seis pasos del orden de la decisión 2; el JSON de la carga se analiza solo tras la firma) y
    `package-info.java` con `@NamedInterface` y consumidores `bootstrap` e `identity` en el Javadoc (ADR-0022). Editar
    `apps/api/app/pom.xml`: `com.confia.shared.security.token.*` en `targetClasses` y `targetTests` de PIT. Editar `apps/api/pom.xml`:
    `bannedDependencies` con Nimbus JOSE, Tink, jjwt y java-jwt (mensaje que cita DA-2).
  - **Ataques por la prohibición.** (a) Añadir de forma temporal `com.nimbusds:nimbus-jose-jwt` y registrar que `./mvnw verify` falla
    señalando la dependencia (BI33); (b) repetir con el starter OAuth2 de recursos y con `jjwt`; revertir. Si la descarga falla
    por PKIX, usar artefactos falsos en un repositorio de archivos temporal, sin tocar TLS.
  - **Demostraciones deliberadas.** Mutar la comparación de la cabecera con la canónica del `kid`, o la selección de clave por `kid`,
    pone en rojo `CompactJwsAttackTest` (BI32); quitar la comprobación de base64url canónico pone en rojo `Base64UrlProperties`;
    se revierten. El perfil `mutation-report` informa la puntuación del códec (mínimo 80); en `main` el `mutation-gate` la exige.
  - **Cierre.** `./mvnw verify` completo. Commit: `feat(shared): add a closed-header EdDSA compact JWS codec`. Tamaño: nominal 330,
    realista 730; costura nombrada, propiedades jqwik a 1b (solo con decisión). — Escenarios I46 a I57, I147, I138, BI30 a BI33 y los dos
    de «Spring Security solo como cadena de filtros».
  - **Nota de costura.** El diseño §5 pone `SigningKey` y `SigningKeyRing` en el PR 2, pero `CompactJws` los necesita. Este PR crea la
    forma en memoria; el PR 2 añade el cargador sin cambiar la firma pública. Es un ajuste de orden, no de diseño.

- [x] 1.1a **PR 1a `jws-primitives`.** `Base64Url` (canónico estricto), `Ed25519Signatures` (convierte la `SignatureException` del
  JDK en `false`), `package-info` con `@NamedInterface`, `JwsFixtures`, `Base64UrlTest`, `Base64UrlPropertiesTest`,
  `Ed25519SignaturesRfc8037Test`, el recurso del vector RFC 8037 A.4 sin clave privada y el objetivo de PIT
  `com.confia.shared.security.token.*`. Escenarios I46 e I138 (parte de la primitiva). Medido: 551 líneas.
- [x] 1.1b **PR 1b `jws-key-ring-and-bans`.** `SigningKey` (restricción del `kid` a `[A-Za-z0-9._-]{1,64}`), `SigningKeyRing` en
  memoria con las cabeceras canónicas precalculadas, `SigningKeyRingInMemoryTest`, `TokenRejection`, `TokenRejectedException` y
  `bannedDependencies` de Nimbus, Tink, jjwt y java-jwt con la evidencia de BI33. Necesita 1.1a.
- [x] 1.1c **PR 1c `jws-compact-codec`.** `CompactJws`, `VerifiedJws`, `CompactJwsAttackTest` y `CompactJwsPropertiesTest`; demostraciones
  de la cabecera canónica y de la carga analizada antes de la firma; puerta de PIT del paquete (BI30 a BI32). Necesita 1.1b.
  1.1 se marca hecha cuando se fusiona 1.1c.

- [x] 1.2 **PR 2 `signing-key-ring`: anillo de claves, cargador y verificación de arranque (decisión 3, O12, DA-17, D-N2).**
  - **ROJO.** Crear `test/java/com/confia/shared/security/token/SigningKeyRingTest.java` (propiedad ausente, Base64 inválido, PKCS#8 de
    Ed448 rechazado con `InvalidKeySpecException` (S-3 ya probada), par que no corresponde detectado al firmar y verificar 32 bytes
    aleatorios, `kid` inválido o repetido, claves públicas repetidas, `previous` sin pública, `previous` con privada, mensajes que
    nombran la propiedad sin ningún valor y sin causa encadenada; firma con `current`, verificación con las dos; retirar `previous`
    invalida sus tokens) y `test/java/com/confia/bootstrap/SigningKeyStartupTest.java` (por `ConfiaApplication.launch`, sin Docker,
    **S-6:** administración sin clave no arranca nombrando la propiedad; con clave generada arranca; portal y trabajador abortan si ven
    `...admin-signing.current.private-key` o `...previous.private-key`; y arrancan sin ellas; administración aborta si aparece
    `confia.security.portal-signing.*.private-key`; no se escribe ningún valor en el mensaje). Ampliar `TestProcessArguments.java` con un
    par generado en ejecución (`kid`, privada y pública) para el proceso administrativo, y `ProcessBeanIsolationTest`/`ProcessBeanPolicy.java`
    sin aún las líneas. Añadir pruebas de ausencia de ruta de claves públicas y de par del portal (`SigningKeyAbsencesTest.java`).
    **Rojos esperados:** clases inexistentes; con el `@Import` y sin línea de política, `not in the allow-list` nombrando
    `com.confia.shared.security.token`; si `TestProcessArguments` no se corrige, `ConfiaApplicationTest` y `OpenApiProcess` fallan (S-6).
  - **VERDE.** Crear `SigningKeyLoader`, `SigningKeyRing.fromEnvironment(Environment)`, `SigningKeyPlacementGuard`
    (`ApplicationListener<ApplicationEnvironmentPreparedEvent>`, no es bean, usa `Environment.containsProperty`) y
    `SessionTokenConfiguration` (bean del anillo; propiedades con `Environment.getProperty(String)`, nunca `@Value`). Editar
    `bootstrap/ConfiaApplication.java` (añade el guardián a los tres procesos), `bootstrap/admin/AdminApplication.java` (`@Import`) y
    `ProcessBeanPolicy.java` (administración permite `com.confia.shared.security.token`; portal y trabajador lo prohíben por nombre con el
    motivo «claves de firma administrativas, ADR-0005 v14», cada línea primero en rojo). Nota fechada en
    `docs/05-infraestructura-y-despliegue.md` con las cinco variables, el nombre reservado del portal y que ninguna tiene valor en el repositorio.
  - **Demostraciones deliberadas.** Quitar la comprobación de correspondencia del par pone en rojo `SigningKeyRingTest`; quitar una
    propiedad prohibida del guardián pone en rojo `SigningKeyStartupTest`; se revierten.
  - **Cierre.** `./mvnw verify` completo; el escaneo de secretos no halla ninguna clave versionada (BI29). Commits:
    `feat(security): load the admin signing key ring and verify key placement at startup` y
    `docs(infra): document the admin signing key variables`. Tamaño: nominal 330, realista 730; costura, guardián a 2b. — Escenarios
    I78 a I84, I140, BI21 a BI23, BI28, BI29 y las dos ausencias de JWKS y del par del portal.

- [x] 1.2a **PR 2a `signing-key-ring`.** `SigningKeyLoader`, `SigningKey.isValidKid` (visible en el paquete), `SigningKeyRing.fromEnvironment`,
  `SessionTokenConfiguration` y su `@Import` en `AdminApplication`, la línea de la lista de permitidos del administrativo, el par generado
  en `TestProcessArguments`, `SigningKeyRingTest`, la parte administrativa de `SigningKeyStartupTest`, `SigningKeyAbsencesTest` y la prueba
  de aislamiento del anillo. Medido: 762 líneas. Escenarios I78 a I82, I84, BI21, BI22, BI28, BI29 y las dos ausencias.
- [x] 1.2b **PR 2b `signing-key-placement-guard`.** `SigningKeyPlacementGuard` y su alta en `ConfiaApplication.launch`,
  `SigningKeyPlacementGuardTest`, el resto de `SigningKeyStartupTest` (portal, trabajador y nombre reservado), las líneas prohibidas de
  portal y trabajador en `ProcessBeanPolicy` con su prueba en `ProcessBeanInspectorTest`, y la nota de `docs/05`. Necesita 1.2a.
  Árbol completo en la rama local `wip/session-tokens-signing-key-ring-full` (cda620a). Escenarios I83, I140 y BI22 (prohibidos nominales).
  1.2 se marca hecha cuando se fusiona 1.2b.

  - **Nota fechada 2026-10-07 (revisión independiente de 1.2a; se construye en 1.2b).**
    - **Regla de orden:** ninguna tarea que emita o verifique tokens (1.4 y 3.3) se fusiona antes que 1.2b, y ningún despliegue recibe la
      clave privada administrativa mientras 1.2b no esté en `main`. Sin el guardián, nada impide arrancar el portal con esa clave en su
      entorno.
    - **H2:** `SigningKeyStartupTest` añade un arranque real con `current.private-key` en Base64 válido que no es una clave (o Ed448) y
      afirma con `assertNoSecretFragment` que la traza no repite el valor.
    - **Valores con salto de línea:** la nota de `docs/05` documenta que un valor con salto de línea final o un PEM de varias líneas falla
      en cerrado como «not valid standard Base64».
    - **H3 y H4 (opcionales):** la prueba de los caminos JWKS se compara con un camino arbitrario, y la cabecera canónica se toma de
      `JwsFixtures` en vez de reescribirla a mano.
- [x] 1.3 **PR 3 `signing-hash-confinement`: ninguna utilidad de firma ni de hash fuera de `identity` y `shared.security` (decisión 16).**
  - **ROJO.** Crear `test/java/com/confia/architecture/SigningAndHashingConfinementTest.java` (dos mitades, ADR-0018: producción real sin
    conjunto vacío y fixtures) con los fixtures permanentes `test/java/com/confia/architecture/fixture/hashing/outside/` —
    `BadDigestOutsideIdentity` (en `com.confia.shared.web`, `MessageDigest.getInstance`), `BadSignatureOutside`, `BadKeyFactoryOutside`,
    `BadKeyPairGeneratorOutside`, `BadMacOutside`, `BadKeyGeneratorOutside`, `BadBouncyCastleOutside`, y los dos que **no** deben fallar,
    `GoodCipherOutside` y `GoodSecureRandomOutside`. **Rojo esperado:** la regla falla sobre la producción real por
    `shared.audit.CanonicalAuditRowSerializer` (único uso preexistente, verificado en 2.1) hasta extraer `Digests`.
  - **VERDE.** Crear `main/java/com/confia/shared/security/Digests.java` (`sha256(byte[])`), delegar desde
    `shared/audit/CanonicalAuditRowSerializer.java` y `shared/security/RequestPayloadHasher.java`. La regla queda sin lista de permitidos;
    su Javadoc declara fuera de alcance `javax.crypto.Cipher` y `SecureRandom`. Las pruebas existentes de la cadena de auditoría corren
    **sin cambios** y demuestran el mismo resultado byte a byte (R-13).
  - **Demostración deliberada.** Reintroducir `MessageDigest` en `CanonicalAuditRowSerializer` pone en rojo la regla sobre producción
    real; se revierte.
  - **Cierre.** `./mvnw verify` completo. Commit: `refactor(shared): move the audit chain SHA-256 to a security utility and confine hashing`.
    Tamaño: nominal 200, realista 440. — Escenarios BI11, BI12, BI13, BI38.

- [ ] 1.4 **PR 4 `access-and-mfa-tokens`: claims, emisor, verificador y principal (decisión 4, decisión 5 parte tipos).**
  - **ROJO.** Crear `test/java/com/confia/shared/security/token/AccessTokenVerifierTest.java` y `AccessTokenIssuerTest.java` (lista cerrada:
    claim extra, repetido, nulo, de tipo distinto, `aud` como arreglo, `permissions` presente; `exp - iat = 600` y `aud = confia-admin`
    (ADR-0005, prueba 3); borde `exp == now` vencido; `iat > now + 60 s`; emisor y audiencia incorrectos; falta de cada claim; `Expired`
    solo tras firma válida y claims íntegros; entradas basura sin excepción; token del portal; clave del portal; `kid` administrativo con
    firma ajena (ADR-0005, prueba 4); restringido con `aud = confia-admin-mfa`, `exp - iat = 300`, `purpose` cerrado, sin `sid`;
    `verifyAccess` rechaza al restringido y `verifyMfa` al de acceso; emitir un restringido no escribe en ninguna tabla; `jti` distinto en
    cada emisión; `amr` `["pwd"]` o `["pwd","otp"]`; serialización determinista; `AccessToken.toString()` redactado; ningún dato
    personal), `AccessTokenClaimsPropertyTest.java` y `test/java/com/confia/architecture/WebLayerTokenIsolationTest.java` con fixture
    permanente `fixture/token/BadWebClassUsingVerifier` (BI14). **Rojo esperado:** clases inexistentes.
  - **VERDE.** Crear `AccessTokenClaims`, `MfaTokenClaims`, `MfaPurpose`, `AccessToken`, `AccessTokenIssuer` (`issueAccess`, `issueMfa`),
    `AccessTokenVerifier` (`verifyAccess`, `verifyMfa`, `JsonMapper` propio con duplicados, propiedades desconocidas y tokens finales
    rechazados; los nombres exactos de las opciones de Jackson 3 se confirman en el rojo) y `shared/security/{AuthenticatedActor,AuthenticationMethod}.java`
    (principal con `ScopedValue`). Registrar emisor y verificador en `SessionTokenConfiguration`. Nota fechada en
    `docs/03-seguridad.md` §4.5 y §11.2 (JWS propio sobre el JDK, claves por variable de entorno hasta el cambio 11, `current`/`previous`, JWKS diferido).
  - **Demostraciones deliberadas.** Cambiar `600` por `601` pone en rojo `AccessTokenIssuerTest`; quitar la lista cerrada pone en rojo
    `AccessTokenVerifierTest`; se revierten. PIT sobre `shared.security.token.*` en al menos 80.
  - **Cierre.** `./mvnw verify` completo. Commits: `feat(security): issue and verify admin access and restricted MFA tokens` y
    `docs(security): note the in-house JWS and key rotation`. Tamaño: nominal 320, realista 700; costura, restringido a 4b. — Escenarios
    I58 a I77, I139 y BI14.

  - **Nota fechada 2026-10-06 (revisión independiente de 1.1c; se construye en 1.4).** Las cinco sugerencias de la revisión del
    códec pasan a la tarea 1.4, la de los claims:
    - **S1:** `CompactJws.sign` lanza `IllegalArgumentException` ante una carga que no es un objeto JSON o que produce un token de más
      de 2 048 caracteres.
    - **S2:** `VerifiedJws` no expone un `ObjectNode` mutable (copia defensiva o `JsonNode`). El validador de claims comprueba el tipo
      de cada claim (`isIntegralNumber`, `isTextual`) y nunca se fía de `asLong()` o `asText()`, que convierten tipos.
    - **S3:** pruebas de una carga firmada con más de 500 niveles de anidamiento (`MALFORMED_CLAIMS`) y de un token de 10 MB (`MALFORMED`
      sin decodificar).
    - **S4:** la carga se decodifica como UTF-8 estricto (`CodingErrorAction.REPORT`), sin la detección automática de codificación de
      Jackson.
    - **S5:** la propiedad del bit único afirma que el motivo es `MALFORMED`, `UNKNOWN_HEADER` o `BAD_SIGNATURE`, nunca
      `MALFORMED_CLAIMS`.
  - **Nota fechada 2026-10-07: partición de 1.4.** La tarea completa y verificada midió **2 676 líneas efectivas**, por encima de 800; por la regla
    general del 2026-10-06 se parte sin consultar en cinco partes ordenadas. La tarea completa se conserva en la rama local
    `wip/session-tokens-access-tokens-full` (c7456c6), que es la fuente de verdad y no se modifica.

    | Parte | Contenido | Tamaño |
    |---|---|---|
    | 1.4a `codec-hardening` | S1 a S5: `CompactJws`, `VerifiedJws`, `CompactJwsHardeningTest`, cambio S5 de `CompactJwsPropertiesTest`; independiente del resto | unas 355 |
    | 1.4b `access-token-issuer` | `AuthenticatedActor`, `AuthenticationMethod`, `AccessToken`, mitad de serialización de `AccessTokenClaims`, `issueAccess`, sus pruebas y fixtures | unas 690 |
    | 1.4c `access-token-verifier` | `ClaimReader`, mitad de lectura, `verifyAccess`, beans de `SessionTokenConfiguration`, `AccessTokenVerifierTest`, viaje de ida y vuelta, `SigningKeyStartupTest`, notas de `docs/03` | unas 735 |
    | 1.4d `restricted-mfa-token` | `MfaTokenClaims`, `MfaPurpose`, `issueMfa`, `verifyMfa`, pruebas del restringido | unas 630 |
    | 1.4e `claim-rules-and-web-isolation` | `AccessTokenClaimsPropertyTest`, `WebLayerTokenIsolationTest`, `BadWebClassUsingVerifier` | unas 272 |

    - [x] 1.4a `codec-hardening` (S1 a S5). Commit `fix(shared): harden the JWS codec input, encoding and claims exposure`.
    - [ ] 1.4b `access-token-issuer`.
    - [ ] 1.4c `access-token-verifier`.
    - [ ] 1.4d `restricted-mfa-token`.
    - [ ] 1.4e `claim-rules-and-web-isolation`.
    - La casilla 1.4 se marca cuando 1.4a a 1.4e estén hechas.


## Fase 2: filtro, institución y registro

- [ ] 2.1 **PR 5 `bearer-authentication-filter`: filtro, principal, lista autenticada y códigos (decisión 5).**
  - **ROJO.** Crear en `test/java/com/confia/shared/web/`: `AccessTokenAuthenticationFilterTest.java` (arnés sin base de datos con
    `RANDOM_PORT` y un doble de `SessionValidity`; sin credencial `401 authentication-required` + `WWW-Authenticate: Bearer`; `Basic`
    anónimo; `Bearer` vacío, con dos espacios, con espacio interior o dos cabeceras → `token-invalid`; token alterado, de otra clave,
    restringido, del portal o con `permissions` → `token-invalid` + `Bearer error="invalid_token"`; vencido → `token-expired`; firma
    maleable o de 63 bytes → `401`, no `500`; credencial en `?access_token=`, cookie, cuerpo o `X-Access-Token` ignorada; ruta pública
    con credencial rota → `401`; token válido en ruta autenticada del arnés → `200` con principal igual a las claims, y fuera de las
    listas → `403`; ninguna cookie ni sesión; `SECRETO-TOKEN` ausente de todo registro a `TRACE`; portal sin filtro; sin credencial
    sin principal; el principal no expone token ni datos personales), ampliar `AdminSecurityChainTest.java` y `PublicEndpointsTest.java`
    (método distinto, variantes de ruta, una ruta de la lista autenticada sin credencial → `401`, una ruta nueva sin editar la lista
    rompe la prueba, ninguna regla por rol, permiso o MFA, 401 del traductor con `WWW-Authenticate`) y el arnés
    `test/java/com/confia/shared/web/harness/` (rutas autenticadas añadidas solo por el arnés). Invertir en
    `ProblemCatalogCoverageTest.java` la negación de `token-invalid` y `token-expired` (BI19, con un catálogo sin la entrada es-HN de
    `token-expired` y otro con `authentication-failed` como controles negativos) y en `WebEdgeScopeExclusionInventoryTest.java`
    `ALLOWED_ROUTE_RULES = {permitAll, authenticated, denyAll}` (control negativo: regla por rol sigue prohibida). **Rojo esperado:**
    `401 authentication-required` en todo caso y clases inexistentes.
  - **VERDE.** Crear en `main/java/com/confia/shared/web/authentication/`: `AccessTokenAuthenticationFilter`, `ActorAuthentication`,
    `AuthenticatedEndpoint`, `AuthenticatedEndpoints` (`forAdmin()` vacía en producción hasta el PR 15), `SessionCheck`
    (`TOKEN_ONLY`, `LIVE_SESSION`; la consulta del puerto llega en 5.1), `AccessTokenRejectedException`, `AccessTokenExpiredException` y
    `package-info.java`; **el filtro no es un bean**: se construye con `new` en `shared/web/edge/AdminSecurityConfiguration.java` y se añade con
    `addFilterBefore(..., AnonymousAuthenticationFilter.class)`; no hay línea de `ProcessBeanPolicy` (BI21, una invocación por petición). Editar
    `shared/web/edge/SecurityChains.java` (segundo constructor: públicas `permitAll`, autenticadas `authenticated()`, `denyAll()` al final),
    `shared/web/problem/{ProblemCode,ProblemResponses,ProblemAuthenticationEntryPoint}.java` (tres casos por tipo de excepción;
    `WWW-Authenticate` en un único lugar), `main/resources/i18n/problems.properties` (es-HN de los dos códigos) y la cadena del portal
    (`WWW-Authenticate: Bearer` en su `401`).
  - **Demostraciones deliberadas.** Sustituir `AccessTokenExpiredException` por la de rechazo hace fallar el caso vencido; hacer que el
    filtro lea `?access_token=` pone en rojo la prueba de credencial fuera de `Authorization`; quitar `WWW-Authenticate` de `ProblemResponses`
    pone en rojo doce comprobaciones; se revierten.
  - **Cierre.** Las pruebas de OpenAPI en verde con la instantánea **sin cambios**, `SpringModulithVerificationTest`, `./mvnw verify`. Commits:
    `feat(web): authenticate the admin chain with a bearer access token filter` y
    `feat(web): add token-invalid and token-expired codes and WWW-Authenticate on every 401`. Tamaño: nominal 360, realista 790; costura,
    `WWW-Authenticate` y catálogo a 5b. — Escenarios WE01 a WE26 (salvo WE27 y WE28), WE30, WE31, WE33 a WE35, WE40 a WE42, BI19 y los
    escenarios sin identificador de la cadena que deniega por defecto, del `type`, del catálogo y de ausencia de reglas de permiso.

- [ ] 2.2 **PR 6 `current-institution-adapter`: institución solo del principal (decisión 6, ADR-0009).**
  - **ROJO.** Crear `test/java/com/confia/organization/infrastructure/TokenCurrentInstitutionProviderTest.java` (la institución sale del
    actor; sin actor `IllegalStateException`; la de configuración del proceso no se usa; 50 peticiones concurrentes de dos instituciones con
    `CyclicBarrier` sin cruces; el contexto de base de datos es el del token; `tenant` de A con `sid` de B no autentica la sesión) y
    `test/java/com/confia/shared/web/CurrentInstitutionFromTokenTest.java` (controlador del arnés que devuelve `currentInstitutionId()`,
    con `X-Institution-Id`, `?institutionId=`, `?tenant=`, cookie y cuerpo con la institución B, y valores malformados: siempre A, sin error).
    Ampliar `ProcessBeanPolicy.java` sin la línea. **Rojo esperado:** clases inexistentes; después `not in the allow-list` nombrando
    `com.confia.organization.infrastructure`.
  - **VERDE.** Crear `main/java/com/confia/organization/infrastructure/TokenCurrentInstitutionProvider.java`,
    `.../infrastructure/wiring/{OrganizationConfiguration,package-info}.java` (`@NamedInterface`, consumidor `AdminApplication`), `@Import` en
    `AdminApplication.java` y las dos líneas de `ProcessBeanPolicy.java`. `ResolveCurrentInstitution` sigue sin registrarse.
  - **Demostración deliberada.** Leer la institución de `X-Institution-Id` pone en rojo `CurrentInstitutionFromTokenTest`; se revierte.
  - **Cierre.** `./mvnw verify`. Commit: `feat(organization): resolve the current institution only from the authenticated principal`. Tamaño:
    nominal 220, realista 480. — Escenarios OR01 a OR11 y «Resolución con un doble en memoria».

- [ ] 2.3 **PR 7 `data-access-log-redaction`: corrección mínima de `unexpected` (decisión 14, O1).**
  - **ROJO.** Ampliar `test/java/com/confia/shared/web/problem/ProblemTranslationTest.java`: un controlador del arnés lanza una excepción de
    acceso a datos con una `PSQLException` real (`ServerErrorMessage` con `Detail: Key (email)=(valor.unico@colegio.edu.hn) already exists`,
    `SQLState 23505`); un `ListAppender` en la raíz afirma que ningún mensaje, argumento, MDC ni `ThrowableProxy` contiene `Detail:` ni el
    valor, que el evento contiene `23505` y las dos clases, que el MDC lleva el `requestId` igual al `traceId` del cuerpo, y la respuesta es
    `500 internal-error` sin el valor. **Control negativo:** el mismo verificador sobre un evento registrado a propósito con `Detail:` reporta
    la violación. Conservan su registro actual una `IllegalStateException` y la `DomainException`. **Rojo esperado:** el registro contiene la
    traza completa y el `Detail:`.
  - **VERDE.** Editar `main/java/com/confia/shared/web/problem/ProblemExceptionHandler.java`: recorrido de causas (hasta 16) buscando
    `java.sql.SQLException` o `DataAccessException`, una sola línea sin excepción adjunta; sin dependencia de `org.jooq` (W1).
  - **Demostración deliberada.** Adjuntar la excepción al `LOG.error` pone en rojo la prueba; se revierte.
  - **Cierre.** `./mvnw verify`. Commit: `fix(web): log only class names and SQLState for untranslated data access failures`. Tamaño: nominal 150,
    realista 330. — Escenarios WE36, WE37 y los de errores sin detalles internos y de excepción no prevista que no es de datos.

## Fase 3: almacén, revocación al restablecer y emisión

- [ ] 3.1 **PR 8 `refresh-token-store-v8`: migración V8 (decisión 8).**
  - **ROJO.** Crear `test/java/com/confia/identity/infrastructure/RefreshTokenSchemaIT.java` (rol `confia_admin_app` real: aislamiento A/B en
    ambas tablas, cero filas sin contexto y con contexto vacío, `INSERT` de otra institución rechazado por `WITH CHECK`, token hacia
    familia ajena rechazado, hash de 43 caracteres rechazado, motivos `admin_revoke` y `logout` rechazados y `reuse_detected` y
    `password_change` aceptados, `revoked_at` sin motivo rechazado, segundo token sin consumir y dos tokens con el mismo sucesor
    rechazados, consumir y luego insertar sucesor aceptado, consumo sin sucesor o sin huella o con huella no SHA-256 rechazado, sucesor
    sin `v1:` o en token sin consumir rechazado, `DELETE` rechazado `42501`, `UPDATE` de `token_hash`, `expires_at`,
    `absolute_expires_at`, huella, IP e `institution_id` rechazado, `UPDATE` de las columnas concedidas y `SELECT ... FOR UPDATE` aceptados,
    `confia_readonly` solo lectura, `confia_portal_app` sin acceso) y ampliar `RolePrivilegeMatrixIT.java` (cinco roles, nivel de columna) y
    la comprobación de que `MultiTenantSchemaIT` pasa sobre las dos tablas **sin lista de exclusión**; añadir la ausencia de motivos sin
    productor (`RevocationReasonCatalogTest`). **Rojo esperado:** tablas inexistentes.
  - **VERDE.** Crear `main/resources/db/migration/V8__create_identity_refresh_token_tables.sql` exactamente como la decisión 8 (RLS `ENABLE`
    y `FORCE`, `REVOKE ALL FROM PUBLIC`, concesiones por columna, ningún `DELETE`, ningún privilegio al portal). jOOQ se regenera solo en la
    construcción; `TableOwnershipByModuleTest` y la prueba de esquema multitenencia cubren las tablas sin cambios. Nota fechada en
    `docs/08-datos-privacidad-y-retencion.md` (IP en claro y huella del agente hasta la purga del cambio 9).
  - **Demostración deliberada.** Conceder `DELETE` o `UPDATE` completo a `confia_admin_app` pone en rojo `RefreshTokenSchemaIT` y
    `RolePrivilegeMatrixIT`; se revierte (en una migración local no comprometida).
  - **Cierre.** `./mvnw verify`. Commit: `feat(identity): add refresh token family and token tables with row-level security`. Tamaño: nominal 300,
    realista 660. — Escenarios BI01 a BI10, BI36, BI37 y «El catálogo de motivos no declara motivos sin productor».

- [ ] 3.2 **PR 9 `revoke-sessions-on-reset`: H2 (decisión 12).**
  - **ROJO.** Crear `test/java/com/confia/identity/infrastructure/PasswordResetRevokesSessionsIT.java` (**I24:** el restablecimiento revoca
    todas las familias vivas con `password_change`, conserva el motivo de una ya revocada, no toca otra cuenta, escribe
    `identity.session.revoked` con `{"reason":"password_change","families":n}` también con `n = 0`; **control negativo:** un escritor de
    auditoría que falla en `identity.password_reset.completed` revierte todo y ninguna familia queda revocada; un rechazo por token,
    contraseña o segundo factor no revoca nada; las familias se siembran por SQL porque aún no hay emisión), actualizar
    `ResetPasswordWithTokenTest.java` y todas las pruebas que construyen el caso de uso (el revocador se llama una vez y solo en
    `Completed`), invertir `IdentityScopeExclusionInventoryTest.java` en las dos reglas con fixtures permanentes `ResetWithoutRevocation` y
    el que reemplaza el hash sin revocar, retirando la regla vieja y `RefreshTokenFamilyRevoker`, y añadir la prueba de que inscribir un segundo
    factor no revoca nada. Se conservan intactas las pruebas de enumeración, de MFA, del puerto de envío y de «no notifica». **Rojo esperado:**
    constructor viejo y ninguna familia revocada.
  - **VERDE.** Crear `main/java/com/confia/identity/application/{AccountSessionRevoker,SessionRevocationReason}.java` y
    `identity/infrastructure/JooqAccountSessionRevoker.java` (`ORDER BY id FOR UPDATE`, vacía los sucesores cifrados), editar
    `ResetPasswordWithToken.java` y `identity/infrastructure/wiring/IdentityConfiguration.java`. Primero la inversión en rojo, después el verde.
  - **Demostración deliberada.** Revocar fuera de la rama `Completed` pone en rojo la prueba de rechazos; se revierte.
  - **Cierre.** `./mvnw verify`. Commit: `feat(identity): revoke every live session family when a password is reset`. Tamaño: nominal 360, realista 790;
    costura 9a/9b (decisión 12). **Este PR se fusiona antes que cualquier emisión.** — Escenarios I24, I122 a I125, BI16 y las ausencias de IP, de
    restablecimiento administrativo, de enumeración, de envío real y de notificación.

- [ ] 3.3 **PR 10 `session-issuance`: emisión de una familia (decisión 9).**
  - **ROJO.** Crear `test/java/com/confia/identity/infrastructure/IssueSessionIT.java` (familia y token en una transacción con
    `identity.session.started`; `token_hash` es el SHA-256 y no el valor entregado (ADR-0005, prueba 5); vigencias de 12 h y `min(8 h, absoluta)`;
    emisión y asiento se confirman o revierten juntos; asiento sin secretos; sin origen se acepta; huella y IP; origen en el asiento con el decorador
    de 4a, 50 peticiones concurrentes; una sesión iniciada después de un restablecimiento no se ve afectada), pruebas de unidad
    `PlainRefreshTokenTest`, `RefreshTokenHashTest`, `UserAgentFingerprintTest`, `SessionLifetimePolicyTest` (bordes 8 h, 12 h, 30 min) y los
    escenarios de MFA existentes que reciben `secondFactorVerified`. **Rojo esperado:** clases inexistentes.
  - **VERDE.** Crear en `identity.domain`: `PlainRefreshToken`, `RefreshTokenHash`, `UserAgentFingerprint`, `SessionLifetimePolicy`,
    `RefreshTokenFamily`, `RefreshTokenRow`; en `identity.application`: `IssueSession`, `IssueSessionCommand`, `IssuedSession`,
    `RefreshTokenFamilyRepository`, `RefreshTokenRepository`; en `identity.infrastructure`: `JooqRefreshTokenFamilyRepository`,
    `JooqRefreshTokenRepository`; registrarlos en `IdentityConfiguration.java`. La firma del acceso ocurre tras confirmar.
  - **Demostración deliberada.** Guardar el token en claro pone en rojo la prueba 5; se revierte.
  - **Cierre.** `./mvnw verify`; JaCoCo de `identity.domain` en 95 y PIT en 80. Commit: `feat(identity): issue refresh token families with hashed tokens`. Tamaño:
    nominal 330, realista 730; costura, repositorios a 10a. — Escenarios I87 a I90, I119, I120, I126, los dos de MFA y los tres de origen en la bitácora.

## Fase 4: rotación, reutilización y gracia

- [ ] 4.1 **PR 11 `refresh-rotation`: rotación fresca, vigencias y rechazos (decisión 10).**
  - **ROJO.** Crear `test/java/com/confia/identity/domain/RotationDecisionTest.java` y `RotationDecisionProperties.java` (tabla completa de la
    decisión 10 con bordes exactos: 10 s, 8 h, 12 h, 30 min, `expires_at` recortado por la absoluta; orden de evaluación; propiedad «nunca
    `GRACE_REPLAY` con `now - consumed_at >= 10 s`») y `test/java/com/confia/identity/infrastructure/RotateRefreshTokenIT.java` (rotación
    normal con un solo sucesor y `last_used_at`; token desconocido; de familia revocada; de otra institución no se ve; rechazo por vigencia
    no es reutilización; consumido presentado a familia vencida es rechazo por vigencia y vacía los sucesores; consumido y vencido por sí
    mismo en familia viva es reutilización; una petición autenticada no escribe `last_used_at`; cambio simultáneo de agente e IP no impide rotar;
    las filas vencidas y revocadas se conservan; todo rechazo es un valor devuelto). **Rojo esperado:** clases inexistentes.
  - **VERDE.** Crear `identity.domain.{RotationDecision,RefreshRejection}` y `identity.application.{RotateRefreshToken,RotateRefreshTokenCommand,RefreshOutcome}`
    con los pasos 1 a 8 de la decisión 10 (bloqueo de la familia, relectura, `UPDATE` condicional, sucesor insertado tras la actualización).
    Auditoría de `identity.session.refreshed` y `identity.session.refresh_rejected`. La rama de reutilización y la de gracia devuelven aún
    `Rejected(REUSE_DETECTED)` sin efecto, para que 4.2 y 4.3 las prueben en rojo.
  - **Demostración deliberada.** Cambiar `>=` por `>` en el borde de 30 minutos pone en rojo `RotationDecisionTest`; se revierte.
  - **Cierre.** `./mvnw verify`. Commit: `feat(identity): rotate refresh tokens with lifetime rules`. Tamaño: nominal 300, realista 660. — Escenarios I91 a I94, I109 a I114,
    I121, I142, I145 y las ausencias de conservación de filas y de vinculación por agente e IP.

- [ ] 4.2 **PR 12 `reuse-detection`: reutilización confirmada y concurrencia (b) y (c).**
  - **ROJO.** Ampliar `RotateRefreshTokenIT.java` (ADR-0005, prueba 1: reutilización fuera de la gracia revoca la familia con `reuse_detected`,
    deja cero tokens vigentes y escribe `identity.session.reuse_detected`; **la revocación se lee en una transacción nueva tras el
    rechazo**; solo la familia afectada; presentar un token de familia ya revocada no la modifica; cada rechazo deja un asiento `denied` sin
    secretos; ningún aviso al titular) y crear `RefreshRotationConcurrencyIT.java` con dos transacciones reales: *(b)* mismo token y huella
    distinta → un `FRESH` y un `REUSE_DETECTED` con familia revocada y exactamente un sucesor; *(c)* rotación frente a restablecimiento: la
    familia termina revocada en todo orden y no queda ningún token vivo. La primera se retiene con `CountDownLatch` en un repositorio decorado
    justo tras tomar el bloqueo. **Rojo esperado:** la familia no se revoca o hay dos sucesores.
  - **VERDE.** Implementar `REUSE_DETECTED` en `RotationDecision`/`RotateRefreshToken` (revoca, vacía sucesores, audita en la misma transacción
    y devuelve valor).
  - **Demostración deliberada.** Lanzar una excepción tras la revocación revierte y pone en rojo la lectura en transacción nueva; se revierte.
  - **Cierre.** `./mvnw verify`. Commit: `feat(identity): revoke the family and audit when a refresh token is reused`. Tamaño: nominal 300, realista 660; costura,
    concurrencia a 12b. — Escenarios I104 a I108, I128, I143, I144 y la ausencia de aviso al titular.

- [ ] 4.3 **PR 13 `grace-window`: sucesor cifrado y ventana de 10 s (decisión 11, D-N1).**
  - **ROJO.** Crear `test/java/com/confia/identity/infrastructure/RefreshGraceWindowIT.java` (ADR-0005, prueba 2: reintento dentro de 10 s desde el
    mismo dispositivo devuelve el mismo refresco sucesor y un acceso **nuevo** con mismos `sid`, `sub`, `tenant`, `amr` y otro `jti`, sin revocar;
    otra huella dentro de la ventana es reutilización; a los 10 s exactos es reutilización; el sucesor se guarda con AAD ligada a la fila y
    copiado a otra fila no descifra; pasados 10 s el valor sigue en la base y no se descifra; se vacía en el siguiente uso; familia que ya
    avanzó no da gracia; sin agente de usuario en ambas presentaciones; el sucesor residual se conserva hasta la purga), ampliar
    `RefreshRotationConcurrencyIT.java` con *(a)* mismo token y misma huella: un `FRESH` y un `GRACE` con el mismo sucesor y un solo token
    abierto (I95), y crear `SessionSecretsNotObservableTest.java` (token, claves, sucesor, hash y cabecera ausentes de todo texto producido, con
    control negativo, I85 e I86). **Rojo esperado:** sin columna usada del sucesor; dos sucesores.
  - **VERDE.** Cifrar con `ColumnEncryptionService.encryptForNewValue("identity_refresh_token", "successor_token_ciphertext", institutionId,
    predecessorId, bytes)`, `GRACE_REPLAY` en la decisión, vaciado en el paso 5, en revocación y en vencimiento. Notas fechadas en
    `docs/adr/ADR-0005-*.md` (vigencias, vocabulario `password_change`, gracia con acceso nuevo) y en `docs/05` y `docs/08` ya hechas en 1.2 y 3.1.
  - **Demostración deliberada.** Quitar la AAD de la fila pone en rojo la prueba de copia a otra fila; se revierte.
  - **Cierre.** `./mvnw verify`; `identity.domain` en 95 y PIT en 80. Commits: `feat(identity): return the encrypted successor inside the grace window` y
    `docs(adr): note session lifetimes, password_change and the grace window`. Tamaño: nominal 330, realista 730. — Escenarios I85, I86, I95 a I103, I141, I148
    y la ausencia de purga del sucesor residual.

## Fase 5: vigencia de sesión y ruta de la sesión actual

- [ ] 5.1 **PR 14 `session-validity`: `SessionLiveness` y comprobación `LIVE_SESSION` (decisión 7).**
  - **ROJO.** Crear `test/java/com/confia/identity/infrastructure/SessionLivenessIT.java` (viva → verdadero; revocada, vencida absoluta, inactiva, de otra
    cuenta, de otra institución e inexistente → falso; no escribe ni audita ni actualiza `last_used_at`; un fallo de base de datos se propaga y falla cerrado;
    ruta `LIVE` con familia revocada → `401 token-invalid` aunque el token no haya vencido; token de una familia revocada por restablecimiento falla; una
    ruta sin la comprobación acepta el token hasta su vencimiento), ampliar `AccessTokenAuthenticationFilterTest.java` (doble que cuenta invocaciones y
    recuerda el actor) e invertir `WebEdgeScopeExclusionInventoryTest.java` (exactamente una implementación en `identity`, único consumidor el filtro; fixtures de
    segundo adaptador, de otro consumidor y con `Cookie`; BI17). **Rojo esperado:** puerto sin implementación.
  - **VERDE.** Cambiar `shared/security/SessionValidity.java` a `isActive(AuthenticatedActor)`, crear `identity/application/SessionLiveness.java` (transacción de solo lectura
    `READ COMMITTED` con `actor.securityContext(requestId)`), registrarlo en `IdentityConfiguration.java`, y activar en el filtro el paso 3 de la decisión 5.
    Nombrar la clase de modo que no rompa reglas vigentes: la regla vieja de sesiones ya se invirtió en 3.2.
  - **Demostración deliberada.** Quitar la comprobación de `revoked_at` pone en rojo `SessionLivenessIT`; se revierte.
  - **Cierre.** `./mvnw verify`. Commit: `feat(identity): check the session family on live-session routes`. Tamaño: nominal 260, realista 570. — Escenarios I115 a I118, I127, I146,
    WE27, WE28, BI17 y «El puerto no arrastra a identity».

- [ ] 5.2 **PR 15 `current-session-endpoint`: ruta, DTO, OpenAPI, contratos y notas de transferencia (decisión 13).**
  - **ROJO, primero la sonda S-4.** Una prueba con `springdoc.override-with-generic-response` por omisión que registre las respuestas añadidas por el traductor
    global; esperado: con `true` aparecen respuestas no aprobadas, con `false` solo las declaradas. Después crear `test/java/com/confia/identity/web/CurrentSessionEndpointIT.java`
    (`200` con el DTO exacto y sin campos de más; `401` en sus tres variantes con `WWW-Authenticate`; restringido, revocada y desconocida no la abren; solo lectura;
    enumerador que exige exactamente `GET /api/v1/auth/sessions/current`), actualizar `OpenApiContractSnapshotTest.java` (una operación, `bearerAuth`, `200`, `401`, `500`, miembro
    `errors`; control negativo con una respuesta `403` o `429` no aprobada; portal vacío), `PublicRouteAllowListTest.java` y `RegisteredRoutes.java` (rutas
    públicas y autenticadas; fixtures que responden `200` sin credencial), `WebLayerTokenIsolationTest.java` con el controlador real (BI15), la regla de la firma de
    controladores sin excepción (retirar la entrada W2a de `EmptyShouldExceptionInventoryTest.java`, BI18, con control negativo) y las reglas de registros y DTO
    de `web` (BI34, BI35). Ampliar las pruebas de validación (`errors` con `field` y `reason`), de ausencia de la ruta de demostración en los procesos reales y la
    de la ruta y los procesos con `ProcessBeanPolicy` (`com.confia.identity.web`, `com.confia.identity.web.wiring`). **Rojo esperado:** ruta inexistente.
  - **VERDE.** Crear `identity/application/{DescribeCurrentSession,CurrentSessionView}.java`, `identity/web/{CurrentSessionController,CurrentSessionResponse}.java`,
    `identity/web/wiring/{IdentityWebConfiguration,package-info}.java` (el controlador no se registra desde `identity.infrastructure.wiring`), `@Import` en `AdminApplication.java`,
    entrada `LIVE_SESSION` en `AuthenticatedEndpoints.forAdmin()`, `shared/web/openapi/ContractSchemas.java` (`bearerAuth`, `errors`), `application.yml`
    (`springdoc.override-with-generic-response: false`) y `@ApiResponse` explícitos. Regenerar `apps/api/openapi/admin.openapi.json` y revisarla como contrato; regenerar
    `packages/contracts` con `pnpm --filter @confia/contracts generate` y pasar `typecheck` y `test` (el código generado no se compromete). Notas fechadas: bloque «quinto
    corte» en `openspec/changes/foundations-plan/exploration.md` (partición, H2 cerrada en S1, H1 y condición 3 a S2, H3 y H4 a S3, sin reescribir sus textos) y
    `docs/ui-ux/04-patrones-de-interaccion.md` (la SPA refresca solo ante actividad). Dejar en `apply-progress.md` la lista de las cuatro condiciones abiertas y transferidas, con
    H2 cerrada por I24, para el informe de archivo.
  - **Demostraciones deliberadas.** Poner `override-with-generic-response: true` pone en rojo la instantánea; devolver un campo de nombre propio en el DTO pone en
    rojo la prueba de lista cerrada; se revierten.
  - **Cierre.** `./mvnw verify` con `mutation-gate` en `main`; cobertura global 80 y `identity.domain` 95; barrido de trazabilidad de los 247 escenarios. Commits:
    `feat(identity): add the current session read endpoint` y `docs(sdd): record the transfer of the hard conditions to S2 and S3`. Tamaño: nominal 360, realista 790;
    costura, notas documentales a 15b. — Escenarios I129 a I137, WE29, WE32, WE38, WE39, BI15, BI18, BI20, BI24 a BI27, BI34, BI35 y los siete escenarios sin identificador de validación, de
    la ruta de demostración y de las reglas de `web`.

---

## Trazabilidad: escenarios de `specs/identity/spec.md` (124)

Los escenarios sin identificador se citan por su título. Cada escenario tiene exactamente una tarea dueña; las notas
documentales repartidas en varios PR (I136, I148) se asignan al PR que las completa.

| Escenario | Tarea |
|---|---|
| I46 Vector RFC 8037 y firma con claves generadas | 1.1 |
| I47 Token emitido se verifica, cabecera solo `alg` y `kid` | 1.1 |
| I48 Confusión de algoritmo | 1.1 |
| I49 `alg` ausente o duplicado | 1.1 |
| I50 `crit` y cabeceras adicionales | 1.1 |
| I147 Cabecera con otra serialización | 1.1 |
| I51 `kid` ausente | 1.1 |
| I52 `kid` desconocido, vacío o de tipo incorrecto | 1.1 |
| I53 Sin recurso a otras claves del anillo | 1.1 |
| I54 Firma de clave ajena con `kid` conocido | 1.1 |
| I55 Relleno, alfabeto estándar, espacios y bits sobrantes | 1.1 |
| I56 Segmentos y firma de longitud incorrecta | 1.1 |
| I57 JSON no objeto o con miembros repetidos | 1.1 |
| I138 Firma maleable o de 63 bytes es token inválido | 1.1 |
| I58 Lista de claims cerrada | 1.4 |
| I59 Vida de 600 s y audiencia | 1.4 |
| I60 Ningún dato personal ni `permissions` | 1.4 |
| I61 `amr` refleja el segundo factor | 1.4 |
| I62 `jti` distinto en cada emisión | 1.4 |
| I63 Borde de `exp` estricto | 1.4 |
| I64 Emisor o audiencia incorrectos | 1.4 |
| I65 Falta de una claim declarada | 1.4 |
| I66 Tipo incorrecto de una claim | 1.4 |
| I67 Claim no declarada, incluida `permissions` | 1.4 |
| I139 Reglas de tiempo de emisión y duración exacta | 1.4 |
| I68 Token firmado con la clave del portal | 1.4 |
| I69 `kid` administrativo con firma ajena | 1.4 |
| I70 Audiencia del portal | 1.4 |
| I71 Íntegro y vencido produce `Expired` | 1.4 |
| I72 Vencido con firma o audiencia incorrectas produce `Invalid` | 1.4 |
| I73 Entradas basura sin excepción no prevista | 1.4 |
| I74 Claims y vida del token restringido | 1.4 |
| I75 Emitir un restringido no crea familia | 1.4 |
| I76 Los dos tipos no son intercambiables | 1.4 |
| I77 Uso del restringido en conjunto cerrado | 1.4 |
| I78 Firma con la vigente, verifica con cualquiera de las dos | 1.2 |
| I79 La rotación retira la clave anterior | 1.2 |
| I80 Arranque falla con anillo mal formado | 1.2 |
| I81 Administración sin clave no arranca | 1.2 |
| I82 Administración con clave arranca | 1.2 |
| I83 Portal y trabajador abortan con la privada administrativa | 1.2 |
| I84 Portal y trabajador arrancan sin la clave | 1.2 |
| I140 Administración aborta con el nombre reservado del portal | 1.2 |
| I85 Ningún secreto observable en texto producido | 4.3 |
| I86 La prueba de redacción detecta una fuga | 4.3 |
| I87 Valores de la emisión | 3.3 |
| I88 Valor almacenado no es el entregado | 3.3 |
| I89 Emisión y asiento se confirman o revierten juntos | 3.3 |
| I90 Asiento de emisión sin secretos | 3.3 |
| I91 Rotación normal | 4.1 |
| I92 Token desconocido | 4.1 |
| I93 Token de familia revocada | 4.1 |
| I94 Token de otra institución no se ve | 4.1 |
| I95 Dos rotaciones concurrentes, un sucesor | 4.3 |
| I143 Presentación simultánea desde dispositivos distintos | 4.2 |
| I96 Reintento en gracia desde el mismo dispositivo | 4.3 |
| I141 Gracia: mismo refresco, acceso nuevo | 4.3 |
| I97 Borde de 10 s estricto | 4.3 |
| I98 Otro dispositivo en la ventana es reutilización | 4.3 |
| I99 Sucesor cifrado y ligado a su fila | 4.3 |
| I100 Pasados 10 s no se descifra | 4.3 |
| I101 Sucesor se vacía en el siguiente uso | 4.3 |
| I102 Familia que ya avanzó no da gracia | 4.3 |
| I103 Sin agente en ambas presentaciones | 4.3 |
| I104 Reutilización fuera de la gracia | 4.2 |
| I105 Revocación se confirma aunque se rechace | 4.2 |
| I106 Solo se revoca la familia afectada | 4.2 |
| I107 Token de familia revocada no la modifica | 4.2 |
| I108 Reutilización y rotación concurrentes | 4.2 |
| I144 Cada rechazo deja asiento de denegación | 4.2 |
| I109 Borde de 30 min de inactividad | 4.1 |
| I110 Vencimiento absoluto acota al token nuevo | 4.1 |
| I111 Borde de 12 h absolutas | 4.1 |
| I112 Vencimiento propio del token | 4.1 |
| I113 Rechazo por vigencia no es reutilización | 4.1 |
| I142 Consumido en familia vencida es vigencia | 4.1 |
| I145 Consumido y vencido por sí mismo es reutilización | 4.1 |
| I114 Petición autenticada no escribe `last_used_at` | 4.1 |
| I115 Familia activa | 5.1 |
| I116 Revocada, vencida, inactiva, desconocida, ajena | 5.1 |
| I117 Ruta de gestión rechaza familia revocada | 5.1 |
| I118 Ruta sin la comprobación acepta hasta vencer | 5.1 |
| I146 Fallo de base de datos falla cerrado | 5.1 |
| I119 Solo huella e IP inicial | 3.3 |
| I120 Emisión sin origen | 3.3 |
| I121 Cambio de agente e IP no impide rotar | 4.1 |
| I24 Restablecimiento revoca todas las familias de la cuenta | 3.2 |
| I122 Transacción revertida no revoca | 3.2 |
| I123 Si la revocación falla no hay restablecimiento | 3.2 |
| I124 Solo `Completed` revoca | 3.2 |
| I125 Cuenta sin familias y familias ya revocadas | 3.2 |
| I126 Sesión posterior no se ve afectada | 3.3 |
| I127 Acceso de familia revocada falla la comprobación de `sid` | 5.1 |
| I128 Restablecimiento y rotación concurrentes | 4.2 |
| I129 Token válido y sesión viva | 5.2 |
| I130 Lista de campos cerrada sin datos personales | 5.2 |
| I131 Sin credencial | 5.2 |
| I132 Vencido, alterado o restringido | 5.2 |
| I133 Sesión revocada o desconocida con token vigente | 5.2 |
| I134 La ruta es de solo lectura | 5.2 |
| I135 Nota «quinto corte» (documental) | 5.2 |
| I136 Notas en `docs/03`, `docs/05` y `docs/ui-ux/04` (documental) | 5.2 |
| I137 Informe de archivo lista las cuatro condiciones (documental) | 5.2 |
| I148 Notas en `docs/05`, `docs/08` y ADR-0005 (documental) | 4.3 |
| Una reutilización detectada no notifica a nadie | 4.2 |
| Las filas vencidas y revocadas se conservan | 4.1 |
| El sucesor cifrado residual se conserva hasta la purga | 4.3 |
| Un cambio simultáneo de agente y de IP no exige reautenticación todavía | 4.1 |
| Inscribir el segundo factor no revoca ninguna familia | 3.2 |
| El catálogo de motivos no declara motivos sin productor | 3.1 |
| Ninguna ruta de claves públicas | 1.2 |
| El portal no tiene par de claves | 1.2 |
| Ninguna regla de dirección IP se aplica todavía | 3.2 |
| Once solicitudes seguidas no encuentran ningún límite por IP | 3.2 |
| El resultado uniforme es del caso de uso, no una respuesta HTTP | 3.2 |
| El único camino para cambiar una contraseña es el token | 3.2 |
| Un restablecimiento sin token válido nunca cambia la contraseña | 3.2 |
| MFA: cuenta con `mfa_required` y secreto TOTP inscrito | 3.3 |
| MFA: cuenta con `mfa_required` sin secreto TOTP inscrito | 3.3 |
| Enumeración: solicitud con correo existente | 3.2 |
| Enumeración: solicitud con correo inexistente | 3.2 |
| Enumeración: cuenta en el límite por hora indistinguible | 3.2 |
| El puerto de envío no tiene adaptador de producción | 3.2 |
| Un restablecimiento completado no notifica a nadie | 3.2 |

## Trazabilidad: escenarios de `specs/web-edge/spec.md` (68)

| Escenario | Tarea |
|---|---|
| WE01 Token válido autentica ruta autenticada | 2.1 |
| WE02 Sin cabecera o con otro esquema | 2.1 |
| WE03 `Bearer` vacío, con espacios o repetido | 2.1 |
| WE04 Credencial fuera de `Authorization` se ignora | 2.1 |
| WE05 Ruta pública sirve sin credencial y rechaza una rota | 2.1 |
| WE06 El portal no autentica | 2.1 |
| WE07 Ninguna respuesta crea sesión ni cookie | 2.1 |
| WE08 El token no aparece en registros | 2.1 |
| WE09 El token restringido no abre ninguna ruta | 2.1 |
| WE10 El token del portal se rechaza | 2.1 |
| WE11 `WWW-Authenticate` sin credencial | 2.1 |
| WE12 `WWW-Authenticate` con credencial rechazada | 2.1 |
| WE13 Portal y documentación `prod` la llevan | 2.1 |
| WE41 `401` del traductor la lleva | 2.1 |
| WE14 Las demás respuestas no la llevan | 2.1 |
| WE15 Causas de rechazo indistinguibles | 2.1 |
| WE16 Solo íntegro vencido da `token-expired` | 2.1 |
| WE17 El mensaje no nombra la causa | 2.1 |
| WE42 Firma maleable es `401`, no `500` | 2.1 |
| WE18 La lista contiene solo la sesión actual | 2.1 |
| WE19 Ruta fuera de las listas con principal es `403` | 2.1 |
| WE20 Otro método sobre ruta autenticada es `403` | 2.1 |
| WE21 Variantes de la ruta no eluden la autenticación | 2.1 |
| WE22 Ruta nueva sin editar la lista rompe la prueba | 2.1 |
| WE23 Ruta de la lista sin credencial que responde falla la prueba | 2.1 |
| WE24 El principal refleja las claims | 2.1 |
| WE25 El principal no expone token ni datos personales | 2.1 |
| WE26 Sin credencial no hay principal | 2.1 |
| WE27 El puerto tiene un adaptador, en `identity` | 5.1 |
| El puerto no arrastra a `identity` | 5.1 |
| WE28 Solo se consulta en las rutas que lo declaran | 5.1 |
| Excepción no prevista que no es de datos conserva su registro | 2.3 |
| WE29 La única ruta de producción es la sesión actual | 5.2 |
| WE30 Ninguna cookie y CSRF sigue desactivado | 2.1 |
| WE31 Limitador y materializador sin endpoint de producción | 2.1 |
| WE32 H1, H3, H4 y condición 3 con nuevo dueño (documental) | 5.2 |
| Ruta no registrada sin credencial | 2.1 |
| Ruta registrada fuera de la lista blanca | 2.1 |
| La respuesta no distingue rutas existentes de inexistentes | 2.1 |
| Principal autenticado sin permiso | 2.1 |
| Método distinto al permitido en ruta pública | 2.1 |
| Variantes de la ruta no eluden la denegación | 2.1 |
| WE33 Ruta de la lista autenticada sin credencial | 2.1 |
| El mismo código produce el mismo `type` | 2.1 |
| Códigos distintos producen `type` distintos | 2.1 |
| La denegación por falta de credencial es uniforme | 2.1 |
| WE34 Credencial rechazada no usa `authentication-required` | 2.1 |
| Cada código tiene su estado | 2.1 |
| Tipo de contenido no admitido | 2.1 |
| Recurso inexistente bajo prefijo de documentación | 2.1 |
| Un código desconocido no se filtra | 2.1 |
| WE35 Códigos de token existen y los de inicio de sesión no | 2.1 |
| Excepción no prevista | 2.3 |
| Excepción de dominio con mensaje interno | 2.3 |
| El detalle técnico queda solo en el servidor | 2.3 |
| WE36 Violación de unicidad no filtra el valor | 2.3 |
| WE37 Control negativo detecta fuga de `Detail:` | 2.3 |
| Campo inválido | 5.2 |
| El valor rechazado no se repite | 5.2 |
| Cuerpo mal formado | 5.2 |
| WE38 `errors` declarado en el esquema | 5.2 |
| Asiento durante una petición | 3.3 |
| Asiento fuera de una petición | 3.3 |
| Peticiones concurrentes con orígenes distintos | 3.3 |
| El controlador de demostración no está en los procesos reales | 5.2 |
| WE39 OpenAPI no contiene la ruta de demostración | 5.2 |
| Ninguna regla de permiso sobre una ruta | 2.1 |
| WE40 Token con claim `permissions` se rechaza | 2.1 |

## Trazabilidad: escenarios de `specs/build-integrity/spec.md` (43)

| Escenario | Tarea |
|---|---|
| BI01 Puertas genéricas de esquema sobre cada tabla | 3.1 |
| BI02 Una institución no lee las de otra | 3.1 |
| BI03 Sin contexto, cero filas | 3.1 |
| BI04 Hash que no es SHA-256 rechazado | 3.1 |
| BI05 Catálogo de motivos cerrado | 3.1 |
| BI06 Token no apunta a familia de otra institución | 3.1 |
| BI36 Un token sin consumir por familia, un predecesor | 3.1 |
| BI37 Consumo coherente y sucesor con `v1:` | 3.1 |
| BI07 Matriz de privilegios, cinco roles | 3.1 |
| BI08 Sin `DELETE` para `confia_admin_app` | 3.1 |
| BI09 `UPDATE` fuera de columnas concedidas rechazado | 3.1 |
| BI10 `confia_portal_app` sin privilegios | 3.1 |
| BI11 Fixture `shared.web` calcula un hash | 1.3 |
| BI12 Código real no falla | 1.3 |
| BI13 Uso preexistente movido a `shared.security` | 1.3 |
| BI38 Seis utilidades cubiertas, cifrado y aleatorio fuera | 1.3 |
| BI14 Fixture con controlador que verifica un token | 1.4 |
| BI15 Producción sin dependencias prohibidas | 5.2 |
| BI16 Sesiones y refrescos solo en clases de sesión | 3.2 |
| BI17 `SessionValidity` con un adaptador | 5.1 |
| BI18 Entrada de firma de controladores retirada | 5.2 |
| BI19 Catálogo exige códigos de token | 2.1 |
| BI20 Enumerador distingue públicas y autenticadas | 5.2 |
| BI21 Administración registra el cableado de sesión | 1.2 |
| BI22 Portal y trabajador sin bean de sesión | 1.2 |
| BI23 Bean sin línea en la lista rompe la construcción | 1.2 |
| BI24 Operación y respuestas coinciden con la instantánea | 5.2 |
| BI25 Respuesta genérica no aprobada rompe | 5.2 |
| BI26 `errors` declarado y contrato regenerado | 5.2 |
| BI27 Instantánea del portal vacía | 5.2 |
| BI28 Arranque de tres procesos sin Docker | 1.2 |
| BI29 Ninguna clave de firma versionada | 1.2 |
| BI30 Mutación baja del códec en la rama principal | 1.1 |
| BI31 Mutación baja del códec en rama de trabajo | 1.1 |
| BI32 Mutar cabecera exacta o selección por `kid` | 1.1 |
| Se añade el servidor de recursos OAuth2 | 1.1 |
| La dependencia de Spring Security converge | 1.1 |
| BI33 Biblioteca JOSE o JWT de terceros | 1.1 |
| Fixture con controlador que devuelve registro de jOOQ | 5.2 |
| Fixture con DTO que contiene tipo de `domain` | 5.2 |
| BI34 Regla de firma de controladores evalúa uno real | 5.2 |
| BI35 DTO de la sesión actual sin tipos de `domain` ni jOOQ | 5.2 |
| La regla sobre registros de `web` evalúa clases reales | 5.2 |

## Trazabilidad: escenarios de `specs/organization/spec.md` (12)

| Escenario | Tarea |
|---|---|
| OR01 La institución sale del token | 2.2 |
| OR02 La de configuración del proceso no se usa | 2.2 |
| OR03 Sin principal falla cerrado | 2.2 |
| OR04 Peticiones concurrentes no se cruzan | 2.2 |
| OR05 `tenant` de una institución y `sid` de otra | 2.2 |
| OR06 Cabeceras de institución se ignoran | 2.2 |
| OR07 Parámetros de consulta y cookies se ignoran | 2.2 |
| OR08 Campo de institución en el cuerpo se ignora | 2.2 |
| OR09 Valor malformado no cambia la resolución | 2.2 |
| OR10 Contexto de base de datos es el del token | 2.2 |
| Resolución con un doble en memoria | 2.2 |
| OR11 La implementación real falla cerrado | 2.2 |

## Resumen de cobertura

| Tarea | Escenarios | Tarea | Escenarios |
|---|---|---|---|
| 1.1 | 20 | 3.3 | 12 |
| 1.2 | 15 | 4.1 | 15 |
| 1.3 | 4 | 4.2 | 9 |
| 1.4 | 22 | 4.3 | 14 |
| 2.1 | 49 | 5.1 | 10 |
| 2.2 | 12 | 5.2 | 29 |
| 2.3 | 6 | | |
| 3.1 | 13 | **Total** | **247 de 247** |
| 3.2 | 17 | | |

Los escenarios documentales (I135, I136, I137, I148, WE32) se verifican por lectura del diff, declarada como revisión humana; I137 queda
preparado en `apply-progress.md` por la tarea 5.2 y se cumple en el informe de archivo.

## Riesgos de la cadena

- **Tamaño.** El centro realista (unas 9 800 líneas) da unas 650 por PR; los PR 1, 5, 9, 12, 13 y 15 rondan 800. Sin costuras reservadas: la
  medición decide y, si supera 800, se consulta antes de partir (cada costura excede las 15 tareas).
- **Ajuste de orden en 1.1.** `SigningKeyRing` en memoria llega en el PR 1 y el cargador en el PR 2 (nota de 1.1).
- **PR 5 con 49 escenarios.** Es el más denso en escenarios; son sobre todo pruebas sobre un arnés compartido.
- **H2.** 3.2 debe fusionarse antes que 3.3; revertir 3.2 exige revertir antes 3.3 a 5.2.
- **`verify`.** 5 min 24 s hoy contra 8 min de tope; las `*IT` nuevas comparten contenedor.
- **Jackson 3.** Los nombres exactos de las opciones del `JsonMapper` se confirman en el rojo de 1.4 (S-5 es de S2).

## Nota fechada 2026-10-06: partición de 1.1 y regla general de tamaño

La tarea 1.1 verificada midió **1 631 líneas efectivas** (419 de producción, 19 de los `pom.xml` y 1 193 de pruebas) frente al
tope de 800 y a un pronóstico realista de 730. El árbol completo y verificado (`./mvnw verify`: Surefire 186 + 1 355, Failsafe 250;
PIT del paquete 94 de 95, 98,9 %) se conserva en la rama **local** `wip/session-tokens-jws-codec-full` (2461b4f). El propietario
aprobó partirla en tres PR:

| Parte | Contenido | Líneas |
|---|---|---|
| 1.1a `jws-primitives` | Base64url, primitiva Ed25519, vector RFC sin clave privada, fixtures | 551 (medidas) |
| 1.1b `jws-key-ring-and-bans` | Anillo en memoria, tipos de rechazo, prohibiciones de Maven | unas 300 |
| 1.1c `jws-compact-codec` | Códec, ataques y propiedades, puerta de PIT | unas 830 (excepción de unas 30 líneas si se confirma la medida) |

**Regla general aprobada por el propietario (2026-10-06).** Cuando una tarea de este cambio supere 800 líneas efectivas, el
orquestador la parte por sus costuras naturales en PR de 800 como máximo **sin consultar**, con una nota fechada que registra la
medida y la partición. Solo consulta al propietario si una parte indivisible exige una excepción de tamaño. El tope de 15 tareas se
supera en consecuencia: cada parte cuenta como una tarea de la cadena.

## Nota fechada 2026-10-07: partición de 1.2

La tarea 1.2 verificada midió **1 127 líneas efectivas** (`git diff --numstat main`, sin `openspec/`; 32 de ellas de `docs/05`), por encima
de 800. El árbol completo y verificado (`./mvnw verify -Pmutation-gate`: Surefire 186 + 1 420, Failsafe 250) se conserva en la rama
**local** `wip/session-tokens-signing-key-ring-full` (cda620a). Por la regla general de 2026-10-06 se parte sin consultar:

| Parte | Contenido | Líneas |
|---|---|---|
| 1.2a `signing-key-ring` | Cargador, anillo desde el entorno, configuración y su `@Import`, claves de prueba, pruebas del anillo, de arranque administrativo, de ausencias y de aislamiento | 762 (medidas) |
| 1.2b `signing-key-placement-guard` | Guardián de ubicación, su alta, pruebas de portal, trabajador y nombre reservado, líneas prohibidas, nota de `docs/05` | unas 352 |
