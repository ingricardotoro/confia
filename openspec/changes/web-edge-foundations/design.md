# Diseño: fundamentos del borde web del proceso administrativo

- **Cambio:** `web-edge-foundations` (F0, cambio 7, parte 4a)
- **Fase:** diseñar
- **Fecha:** 2026-10-04
- **Estado:** aprobado por el propietario del producto el 2026-10-04
- **Entradas:** `proposal.md` (aprobada el 2026-10-04, con las respuestas del propietario),
  `exploration.md`, `specs/web-edge/spec.md` (42 requisitos, 132 escenarios tras las correcciones de la sección 9) y
  `specs/build-integrity/spec.md` (delta), más las sondas P1 a P3 de Engram
  `sdd/web-edge-foundations/probes` (#584)
- **Modo de TDD:** estricto. Ejecutor: `./mvnw verify` en `apps/api`, con JDK 25 y Docker.

## 1. Enfoque técnico

El borde HTTP se construye **dentro del módulo `shared`**, en subpaquetes de `com.confia.shared.web`
con nombre de capacidad, y cada pieza entra en un proceso por una **configuración pública importada
con `@Import`** y su línea en `ProcessBeanPolicy`, en el mismo pull request (ADR-0024). Nada se
descubre por escaneo. Ningún controlador de producción se añade: todo se demuestra con la cadena de
filtros real y con controladores que viven solo en el árbol de pruebas.

```
                         ┌──────────────────── proceso administrativo ────────────────────┐
petición ─► Tomcat (hilos virtuales)                                                       │
  │   1. SecurityHeadersFilter      cabeceras base en toda respuesta                       │
  │   2. RequestContextFilter       id de petición (UUID del servidor), IP por proxies     │
  │                                 de confianza, agente de usuario, MDC, ScopedValue,     │
  │                                 último recurso: 500 Problem Details                    │
  │   3. FilterChainProxy           cortafuegos ─► 400 · lista blanca ─► permitido         │
  │      (Spring Security)          resto ─► 401 (anónimo) / 403 (autenticado)             │
  │   4. DispatcherServlet                                                                 │
  │        RateLimitInterceptor     @RateLimited ─► 429 / 503 antes del controlador        │
  │        IdempotencyKeyInterceptor @IdempotentWrite ─► 400 antes del controlador         │
  │        controlador (solo pruebas en este cambio)                                       │
  │          RequiredDelayMaterializer: permiso ─► caso de uso (commit) ─► espera          │
  │          IdempotentRequestHandler: IdempotentExecutor ─► 200/409/422 + Replay          │
  │        ProblemExceptionHandler  toda excepción ─► application/problem+json             │
  └──────────────────────────────────────────────────────────────────────────────────────┘
proceso del portal: pasos 1, 2 y 3 con una cadena que deniega todo (sin 4 de negocio)
proceso trabajador: sin servidor web, sin cadena, sin filtros, sin DataSource
```

El proceso administrativo registra además su cableado de producción: `DataSource`,
`DSLContext`, `TransactionRunner`, `Clock`, los adaptadores de `shared` y los casos de uso de
identidad cuyos puertos tienen adaptador de producción.

## 2. Evidencia obtenida antes de esta fase

### 2.1 Sondas (Engram #584, árbol de trabajo desechable, ya descartado)

| Sonda | Resultado observado | Consecuencia en este diseño |
|---|---|---|
| **P1, Spring Security** | `spring-boot-starter-security` 4.1.1 resuelve Spring Security 7.1.1 y añade `spring-boot-security`, `spring-security-{config,core,crypto,web}` y `spring-aop`. `dependencyConvergence` y `bannedDependencies` pasan. Los artefactos ya estaban en `~/.m2`, así que **la brecha PKIX no se ejerció**; Trivy no está instalado en local (solo integración continua). Con el starter y sin `SecurityFilterChain` propio, administración y portal responden `401` a todo y fallan 16 pruebas (8 de `OpenApiContractSnapshotTest`, 8 de `OpenApiExposureByProfileTest`); se registra una contraseña generada en el log; el trabajador recibe 3 beans de seguridad inertes; `ProcessBeanIsolationTest` sigue en verde porque ignora beans fuera de `com.confia` | Decisiones 4, 5 y 6 |
| **P2, `DataSource` administrativo** | Quitando la exclusión, el contexto arranca sin base de datos siempre que exista **cualquier** `spring.datasource.url` válida: Hikari conecta de forma perezosa. Sin URL: `Failed to determine a suitable driver class`. `spring.flyway.enabled=false` ya está en `application.yml`; activar Flyway rompe el arranque (`Connection refused`); solo el perfil `migrate` lo activa. Spring Boot 4.1.1 no autoconfigura `DSLContext`. Las clases de identidad y de cifrado no son beans; `ColumnEncryptionMasterKey` no es hoy dependencia del arranque | Decisiones 2 y 3 |
| **P3, hilos virtuales bajo Tomcat** | Con `spring.threads.virtual.enabled=true` y Tomcat a 4 hilos, 40 esperas concurrentes de 1,5 s terminan en unos 2,2 s y el manejador reporta `isVirtual=true`. `Semaphore.tryAcquire` da un `503` limpio para el exceso. **La desconexión del cliente solo se detecta al escribir o vaciar la respuesta** (`AsyncRequestNotUsableException`), no durante la espera | Decisión 18 y pregunta abierta 1 |
| Compilación | La construcción no compila con `-parameters` | Todo `@RequestParam`, `@PathVariable` y `@RequestHeader` declara su nombre de forma explícita |

### 2.2 Código leído en `change/web-edge-foundations` @ `7aa791d`

- Los tres puntos de entrada excluyen `DataSourceAutoConfiguration`. `AdminApplication` y
  `PortalApplication` importan solo `ContractSchemas` y `ProcessApiInfo`.
- `ProcessBeanPolicy` empareja paquetes por igualdad o por prefijo seguido de punto, y
  `ProcessBeanIsolationTest` exige que **cada** paquete permitido distinto del de entrada aporte al
  menos un bean (no vacuidad). Permitir `com.confia.shared` en bloque permitiría todo `shared`: por
  eso las configuraciones nuevas viven en subpaquetes concretos.
- `TransactionRunner`, `IdempotentExecutor`, `JooqAuditLogWriter`, `JooqIdempotencyRecordStore`,
  `ColumnEncryptionService`, los adaptadores jOOQ de identidad y los casos de uso son `final`, con
  constructor explícito y sin anotación de Spring.
- `IdempotentExecutor` captura el choque `23505` (`IdempotencyMarkerAlreadyExists`) y **reproduce**
  la respuesta del ganador en una transacción nueva; solo `55P03` (`WAIT_EXHAUSTED`) y la rama
  defensiva `MARKER_IN_PROGRESS` salen como `IdempotencyConflictException`. Relevante para la
  sección 9, corrección 4.
- `PasswordResetIssuanceScheduler` y `PasswordResetLinkSender` no tienen adaptador de producción y
  `IdentityScopeExclusionInventoryTest` lo exige (cambios 9 y 14). Advierte además que un lambda en
  una configuración quedaría fuera de su alcance: este diseño no crea ninguno.
- `ConfiguredLoginInstitutionProvider` lee `confia.identity.login-institution-id` de
  `System.getProperty`; `Argon2Pepper` y `ColumnEncryptionMasterKey` validan en construcción y sus
  mensajes de error no incluyen el valor.
- `ContractSchemas` declara el esquema `ProblemDetail` con `type`, `title`, `status`, `detail`,
  `instance` y **`traceId`**; `OpenApiContractSnapshotTest` exige exactamente esas seis propiedades.
- `LayeredArchitectureTest` ya no tiene `optionalLayer`: la capa `Web` es obligatoria desde el
  cambio 3 (`ContractSchemas`), y `EmptyShouldExceptionInventoryTest.EXCEPTIONS` está vacío.
- `NoBlockingWaitInIdentityTest` confina las esperas a nada dentro de `com.confia.identity`.
- `AuditEntry` es un registro que construye el llamador; `sourceIp` y `userAgent` viajan hoy en
  `null`. `shared_audit_log.source_ip` es `INET` y `user_agent` es `TEXT`.
- `shared_idempotency_key.idempotency_key` y `endpoint` admiten de 1 a 200 caracteres.
- No hay `spring-boot-starter-actuator` en `apps/api/app/pom.xml`: hoy no existe ninguna ruta de
  salud ni de preparación.
- `docs/ui-ux/04-patrones-de-interaccion.md` §9 usa la base `https://confia.hn/problems/` para
  `type`, un arreglo `errors` para validación y un vocabulario distinto en tres códigos (decisión 8).

## 3. Decisiones de arquitectura

### Decisión 1 — Paquetes, configuraciones públicas y registro por proceso

| Paquete (producción) | Contenido | `@NamedInterface` (ADR-0022) |
|---|---|---|
| `com.confia.shared.platform` | `SharedPlatformConfiguration`: `DSLContext`, `TransactionRunner`, `Clock`, `IdempotentExecutor` y sus colaboradores, escritor de auditoría decorado, cifrado de columnas | Sí; consumidor: `bootstrap.admin.AdminApplication` |
| `com.confia.shared.web.edge` | `WebEdgeConfiguration`, `AdminSecurityConfiguration`, `PortalSecurityConfiguration`, `ThrottlingConfiguration`, `IdempotencyEdgeConfiguration`, `SecurityChains`, `PublicEndpoints` | Sí; consumidores: `AdminApplication` y `PortalApplication` |
| `com.confia.shared.web.request` | `SecurityHeadersFilter`, `RequestContextFilter`, `ClientAddressResolver`, `TrustedProxies`, `CidrBlock`, `SensitiveLogGuard` | No (interno) |
| `com.confia.shared.web.problem` | `ProblemCode`, `ProblemBody`, `FieldViolation`, `ProblemResponses`, `ProblemExceptionHandler`, `ProblemAuthenticationEntryPoint`, `ProblemAccessDeniedHandler`, `ProblemRequestRejectedHandler`, `CapacityExceededException` | No |
| `com.confia.shared.web.ratelimit` | `@RateLimited`, `RateLimitInterceptor`, `RateLimitProperties`, `TooManyRequestsException` | No |
| `com.confia.shared.web.delay` | `RequiredDelayMaterializer`, `Delayed`, `DelayTimer`, `DelayProperties` | No |
| `com.confia.shared.web.idempotency` | `@IdempotentWrite`, `IdempotencyKeyInterceptor`, `IdempotentRequestHandler`, `IdempotencyKeyMissingException` | No |
| `com.confia.shared.security` (existente) | Puertos `RateLimiter`, `SessionValidity`; `RateLimitPolicy`, `RateLimitDecision`, `InMemoryRateLimiter`, `ClientAddress`, `RequestOrigin` | Ya lo es |
| `com.confia.shared.audit` (existente) | `RequestOriginAuditLogWriter` (decorador) | Ya lo es |
| `com.confia.identity` (raíz del módulo) | `IdentityConfiguration` | API por omisión del módulo |

- Las configuraciones que tocan adaptadores de `infrastructure` (`SharedPlatformConfiguration`,
  `IdentityConfiguration`) viven en paquetes **sin segmento de capa**, igual que
  `shared.security`: `LayeredArchitectureTest` no evalúa sus dependencias y la regla de la capa
  `web` (decisión 20) no se viola. Las configuraciones de `shared.web.edge` solo referencian
  `shared.web.*` y `shared.security`; por eso `InMemoryRateLimiter`, que no hace entrada ni salida,
  vive en `shared.security` y no en `shared.infrastructure`.
- `AdminApplication` importa `ContractSchemas`, `ProcessApiInfo`, `SharedPlatformConfiguration`,
  `IdentityConfiguration`, `WebEdgeConfiguration`, `AdminSecurityConfiguration`,
  `ThrottlingConfiguration` e `IdempotencyEdgeConfiguration`. `PortalApplication` importa
  `ContractSchemas`, `ProcessApiInfo`, `WebEdgeConfiguration` y `PortalSecurityConfiguration`.
  `WorkerApplication` no importa nada nuevo.
- **`ProcessBeanPolicy`** (cada línea aporta un bean real, como exige la no vacuidad):

| Proceso | Permitidos que se añaden | Prohibidos nominales que se añaden o cambian |
|---|---|---|
| `admin` | `com.confia.identity`, `com.confia.kernel` (bean `AesGcmCipher`), `com.confia.shared.platform`, `com.confia.shared.security`, `com.confia.shared.audit`, `com.confia.shared.crypto`, `com.confia.shared.infrastructure`, `com.confia.shared.web.edge`, `com.confia.shared.web.request`, `com.confia.shared.web.problem`, `com.confia.shared.web.ratelimit`, `com.confia.shared.web.delay`, `com.confia.shared.web.idempotency` | Ninguno |
| `portal` | `com.confia.shared.web.edge`, `com.confia.shared.web.request`, `com.confia.shared.web.problem` | `com.confia.shared.platform` (cableado solo administrativo, D5) |
| `worker` | Ninguno | `com.confia.shared.web` sustituye a `com.confia.shared.web.openapi` (cubre todo el borde); se añaden `com.confia.shared.platform`, `org.springframework.security` y `org.springframework.boot.security` |

Cada línea nueva se añade en el mismo pull request que su `@Import`, y cada PR lo demuestra en rojo:
primero el `@Import` sin la línea, con `ProcessBeanIsolationTest` fallando y nombrando el bean y el
paquete.

**Descartado:** una sola configuración `SharedConfiguration` en la raíz de `shared`. Obligaría a
permitir `com.confia.shared` completo en cada proceso y la lista dejaría de fallar cerrado.
**Descartado:** `@ComponentScan` restringido a `shared.web`. ADR-0024 lo prohíbe en los puntos de
entrada y la regla de «solo su propio paquete» no protege lo que entra en el portal.

### Decisión 2 — `DataSource` solo en administración, sin conexión ni migración en el arranque

- `AdminApplication` deja de excluir `DataSourceAutoConfiguration`. `PortalApplication` y
  `WorkerApplication` la conservan, con su Javadoc actualizado a la decisión D5.
- **Origen de la URL en producción:** las variables estándar de Spring Boot
  `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME` y `SPRING_DATASOURCE_PASSWORD`, que provee
  el despliegue (cambio 11). Ningún archivo del repositorio contiene una URL, un usuario ni una
  contraseña (regla 13). Sin URL, el proceso administrativo no arranca: es el fallo temprano
  deseado, con el mensaje de Spring Boot.
- `application.yml` añade `spring.datasource.hikari.initialization-fail-timeout: -1`: si algún
  componente futuro forzara el arranque del grupo, el contexto sigue arrancando sin base de datos.
  Es inerte en portal y trabajador, que excluyen la autoconfiguración.
- Flyway no cambia: `spring.flyway.enabled: false` en `application.yml` y solo el perfil `migrate`
  lo activa. La prueba de cableado afirma que el contexto administrativo no contiene ningún bean
  `Flyway` ni `FlywayMigrationInitializer`.
- `SharedPlatformConfiguration` declara de forma explícita:
  - `DSLContext` sobre `DataSourceConnectionProvider(new TransactionAwareDataSourceProxy(ds))` con
    `SQLDialect.POSTGRES`, el mismo mecanismo de `IntegrationTestApplication`;
  - `TransactionRunner(PlatformTransactionManager, DataSource)`; el administrador de transacciones lo
    aporta la autoconfiguración JDBC de Spring Boot;
  - `Clock.systemUTC()`;
  - `JooqAuditLogWriter` envuelto por `RequestOriginAuditLogWriter` (decisión 13), único bean
    `AuditLogWriter`;
  - `JooqIdempotencyRecordStore`, `RequestPayloadHasher` e `IdempotentExecutor` con sus valores por
    omisión (250 ms y 24 h);
  - `AesGcmCipher`, `ColumnEncryptionMasterKey`, `JooqDataEncryptionKeyRepository` y
    `ColumnEncryptionService`, porque los casos de TOTP los consumen.
- **Pruebas de arranque sin Docker y sin `application.yml` de prueba.** Un archivo
  `src/test/resources/application.yml` taparía el de producción en el camino de clases, así que no se
  crea. Una clase de soporte de pruebas, `com.confia.bootstrap.TestProcessArguments`, devuelve los
  argumentos de línea de órdenes de cada proceso: `--server.port=0` para los dos web y, solo para
  `admin`, `--spring.datasource.url=jdbc:postgresql://127.0.0.1:1/confia-unreachable` más los tres
  secretos de la decisión 3 generados **en tiempo de ejecución** con `SecureRandom` (nunca un valor
  escrito en el repositorio). `ProcessBeanIsolationTest`, `OpenApiProcess` y `ConfiaApplicationTest`
  la usan en lugar de sus arreglos literales; ninguna aserción cambia por esta causa.

**Descartado:** pasar las pruebas de arranque administrativas a `*IT` con Testcontainers (la
alternativa de la propuesta): P2 demuestra que no hace falta y costaría minutos del presupuesto.
**Descartado:** una URL por omisión en `application.yml`: un proceso mal configurado arrancaría
apuntando a un destino que nadie eligió.

### Decisión 3 — Casos de uso de identidad como beans, con secretos que fallan al arrancar

`com.confia.identity.IdentityConfiguration` registra **los cinco casos de uso cuyos puertos tienen
adaptador de producción**: `AuthenticateWithPassword`, `VerifyTotpCode`, `ConsumeRecoveryCode`,
`EnrollTotpSecondFactor` y `ResetPasswordWithToken`, con los adaptadores jOOQ de identidad,
`BouncyCastleArgon2PasswordHasher` y `BouncyCastleRecoveryCodeHasher` con `Argon2Profile.floor()`,
`HmacLoginIdentifierFingerprinter` y `ConfiguredLoginInstitutionProvider`.

- **No se registran** `RequestPasswordReset` ni `IssuePasswordResetToken`: dependen de
  `PasswordResetIssuanceScheduler` y `PasswordResetLinkSender`, sin adaptador hasta los cambios 9 y
  14. Registrarlos exigiría un adaptador falso o un lambda, que `IdentityScopeExclusionInventoryTest`
  prohíbe por intención aunque no lo detecte. Llegan con sus adaptadores.
- **Secretos, fallo temprano al arrancar.** La configuración lee tres propiedades con
  `Environment.getProperty(String)`, nunca con `@ConfigurationProperties` ni `@Value`, porque el
  analizador de fallos de enlace de Spring Boot imprime el valor rechazado:

| Propiedad | Variable de entorno | Construye |
|---|---|---|
| `confia.identity.argon2-pepper` | `CONFIA_IDENTITY_ARGON2PEPPER` | `Argon2Pepper.fromBase64` |
| `confia.crypto.column-master-key` | `CONFIA_CRYPTO_COLUMNMASTERKEY` | `ColumnEncryptionMasterKey.fromBase64` (en `SharedPlatformConfiguration`) |
| `confia.identity.login-institution-id` | `CONFIA_IDENTITY_LOGININSTITUTIONID` | `ConfiguredLoginInstitutionProvider` |

  Una propiedad ausente lanza `IllegalStateException` que nombra la propiedad y no contiene ningún
  valor. La llave maestra es **de fallo temprano, no perezosa**: es el contrato ya escrito en las
  clases («falla en construcción, nunca en el primer uso») y un proceso con un secreto mal
  configurado no debe aceptar ni una petición. `@Lazy` queda descartado porque traslada el fallo al
  primer inicio de sesión.
- `ConfiguredLoginInstitutionProvider` gana un método estático público `fromValue(String)` (el
  constructor de paquete actual pasa a delegar en él). La clave sigue siendo la misma, y
  `Environment` incluye las propiedades de sistema, así que la configuración por propiedad de sistema
  sigue funcionando.
- Ninguna clase de `identity` cambia de comportamiento: solo se registran.

**Desviación declarada de ADR-0024.** El ADR nombra a `session-tokens-and-web-layer` como primer
consumidor de la configuración pública de un módulo. La propuesta aprobada adelanta el registro a
este cambio (C1a, punto 3). El consumidor real sigue llegando en la parte 4b; aquí la no vacuidad de
`ProcessBeanIsolationTest` y la prueba de cableado demuestran que el registro es correcto.

### Decisión 4 — Spring Security como cadena de filtros, sin autoconfiguraciones sobrantes

- Se añade `spring-boot-starter-security`, gestionado por el BOM (P1). Se prohíben en
  `bannedDependencies` de `apps/api/pom.xml` `org.springframework.boot:spring-boot-starter-oauth2-resource-server`
  y `org.springframework.security:spring-security-oauth2-resource-server`, con mensaje que cita este
  diseño. La demostración en rojo (añadir el starter y ver fallar `verify`) se registra una vez en
  `apply-progress.md`, como las prohibiciones de ADR-0015 y ADR-0016.
- **Administración y portal** excluyen `UserDetailsServiceAutoConfiguration`: no hay almacén de
  usuarios de Spring y su efecto visible es registrar una contraseña generada en el log (regla 11).
- **Trabajador:** excluye `SecurityAutoConfiguration` y `UserDetailsServiceAutoConfiguration`. Queda
  con cero beans de seguridad. Se prefiere excluir a aceptar los 3 beans inertes de P1: un bean
  inerte hoy es una superficie que alguien conecta mañana, y el requisito «el trabajador no tiene
  cadena de seguridad» se verifica mejor con un contexto vacío de seguridad.
- **`ProcessBeanPolicy` empieza a inspeccionar** `org.springframework.security` y
  `org.springframework.boot.security`, solo como **prohibidos nominales del trabajador** (decisión
  1). En administración y portal los beans del marco siguen ignorados: su presencia es esperada y la
  lista de permitidos solo gobierna `com.confia`.
- Los nombres calificados exactos de las dos autoconfiguraciones en `spring-boot-security-4.1.1` se
  confirman contra el jar en la tarea en rojo; se referencian por clase, no por cadena, para que el
  compilador los valide.

### Decisión 5 — Cadenas de seguridad de administración y portal

Un único constructor, `SecurityChains.denyByDefault(HttpSecurity, PublicEndpoints, ProblemResponses)`,
aplica la misma configuración a los dos procesos y a los arneses de prueba:

```java
http.csrf(c -> c.disable())                 // sin credencial por cookie en este cambio (4b, C6c)
    .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
    .requestCache(r -> r.disable())          // ninguna petición guardada, ninguna sesión
    .httpBasic(b -> b.disable()).formLogin(f -> f.disable()).logout(l -> l.disable())
    .headers(h -> h.disable())               // un solo dueño de las cabeceras (decisión 7)
    .exceptionHandling(e -> e
        .authenticationEntryPoint(problemEntryPoint)    // 401 authentication-required
        .accessDeniedHandler(problemDeniedHandler))     // 403 forbidden
    .authorizeHttpRequests(a -> {
        endpoints.forEach(p -> a.requestMatchers(p.method(), p.pattern()).permitAll());
        a.anyRequest().denyAll();             // última regla
    });
```

- **401 frente a 403.** El filtro anónimo se mantiene activo: una petición sin credencial es
  anónima, `denyAll` lanza `AccessDeniedException` y `ExceptionTranslationFilter` la envía al punto
  de entrada (`401`). Un principal autenticado recibe `403 forbidden`. La decisión ocurre antes de
  resolver el controlador, así que una ruta inexistente y una registrada fuera de la lista dan la
  misma respuesta.
- **Lista blanca, `PublicEndpoints`.** Valor inmutable de pares (método, patrón), construido en un
  único lugar: `PublicEndpoints.forAdmin(Environment)` y `PublicEndpoints.forPortal(Environment)`.
  Contenido entregado:

| Entrada | Método | Condición |
|---|---|---|
| `/v3/api-docs`, `/v3/api-docs/swagger-config` | `GET` | `springdoc.api-docs.enabled=true` (solo `local` y `preprod`) |
| `/swagger-ui.html`, `/swagger-ui/**` | `GET` | `springdoc.swagger-ui.enabled=true` (solo `local` y `preprod`) |

  La condición es **la misma propiedad que enciende springdoc**, no el nombre del perfil, así que una
  sola fuente decide si la documentación existe y si es pública. **No hay rutas de salud ni de
  preparación** en la lista: no existe actuator y una entrada sin ruta registrada violaría el
  requisito «la lista contiene solo lo declarado». El cambio que incorpore actuator (cambio 11) añade
  sus entradas editando `PublicEndpoints`, y la prueba de la decisión 21 lo obliga.
- **Portal.** Misma lista condicionada a springdoc; en cualquier otro perfil queda vacía y el portal
  deniega todo. Ver la sección 9, corrección 3: es la única forma de conservar el requisito publicado
  «Swagger UI y el endpoint del OpenAPI solo en local y preproducción» y la instantánea OpenAPI del
  portal, que se genera por HTTP.
- **Variantes de ruta.** Spring Security 7 empareja con `PathPatternRequestMatcher`, sensible a
  mayúsculas y sin barra final implícita: `/X` y `/x/` no coinciden con `/x`. El cortafuegos estricto
  (`StrictHttpFirewall`, por omisión) rechaza `;`, `/.`, `/..` y separadores codificados antes de
  autorizar; `ProblemRequestRejectedHandler` (bean, lo recoge `WebSecurity`) responde
  `400 validation-failed` sin lista de violaciones. Ninguna variante llega al controlador.
- **Sin `/error` ni recursos estáticos.** Administración y portal excluyen `ErrorMvcAutoConfiguration`
  (no hay `BasicErrorController`, ni página de error blanca, ni despacho `ERROR` que autorizar) y
  `application.yml` fija `spring.web.resources.add-mappings: false` (sin `/**` estático). Así el mapa
  de rutas de producción es vacío de verdad. Riesgo: que springdoc dependa de esa propiedad para
  Swagger UI; lo cubre la prueba de exposición en `local`.
- **CORS:** sin configuración. Una petición `OPTIONS` previa recibe `401`, que es lo exigido en el
  portal. El supuesto de mismo origen es de la parte 4b.

**Descartado:** `WebSecurityCustomizer.ignoring()` para la documentación: saca esas rutas de la
cadena, sin cabeceras ni contexto de petición. **Descartado:** condicionar la lista con `@Profile`:
falla abierto ante un perfil nuevo, el mismo motivo por el que springdoc está apagado por omisión.

### Decisión 6 — Las cadenas y las pruebas del OpenAPI conviven por diseño

De las 16 pruebas que P1 rompe:

- **12 vuelven a verde sin tocar su código**: las 8 de `OpenApiContractSnapshotTest` y las 4
  positivas de `OpenApiExposureByProfileTest` arrancan con `local` o `preprod`, donde
  `PublicEndpoints` permite exactamente las rutas de springdoc. La instantánea no debe cambiar; si
  cambiara, el diseño es incorrecto y se investiga, no se aprueba la diferencia.
- **4 cambian su aserción porque la especificación lo exige**: las negativas (`prod` y un perfil
  inexistente) esperan hoy `404`; el escenario «Documentación de API con perfil `prod`» exige `401`,
  y además la denegación uniforme impide distinguir una ruta inexistente. La aserción pasa a `401`
  con `Content-Type: application/problem+json` y con `type` de `authentication-required`, lo que
  sigue demostrando que ni el documento ni la interfaz se sirven. El requisito publicado («ninguno de
  los dos devuelve el documento ni la interfaz») sigue siendo cierto.

### Decisión 7 — Cabeceras de seguridad base, con un solo dueño

`SecurityHeadersFilter` (primer filtro, `Ordered.HIGHEST_PRECEDENCE`) fija en toda respuesta de
administración y portal, antes de que nada se escriba:

| Cabecera | Valor |
|---|---|
| `X-Content-Type-Options` | `nosniff` |
| `X-Frame-Options` | `DENY` |
| `Referrer-Policy` | `strict-origin-when-cross-origin` |
| `Permissions-Policy` | `camera=(), microphone=(), geolocation=(), payment=(), usb=(), interest-cohort=()` |
| `Cache-Control` | `no-store` |

- Las cabeceras de Spring Security se desactivan (`headers().disable()`): con dos dueños, el de
  Spring escribe `Cache-Control: no-cache, no-store, max-age=0, must-revalidate`, `Pragma` y
  `Expires`, distinto de lo especificado. Al ir primero, el filtro cubre también los rechazos del
  cortafuegos, de la cadena, del limitador y del último recurso.
- `X-Powered-By` y `Server` no se emiten: Tomcat no los envía por omisión y `server.server-header`
  no se define. La prueba afirma su ausencia.
- **Conjunto mínimo confirmado:** las cinco de la especificación. `Strict-Transport-Security` y
  `Cross-Origin-Embedder-Policy` son del proxy (cambio 11). `Cross-Origin-Opener-Policy` y
  `Cross-Origin-Resource-Policy` no se emiten aquí: aplican a documentos y a la carga entre orígenes
  de la SPA, cuyo supuesto de mismo origen pertenece a la parte 4b.

### Decisión 8 — Problem Details: forma, `type`, `traceId` y catálogo de códigos

**Cuerpo.** Un registro propio, `ProblemBody`, serializado igual por la cadena de seguridad y por el
traductor de MVC, sin depender del mixin de `ProblemDetail` de Spring:

```json
{ "type": "https://confia.hn/problems/forbidden", "title": "…", "status": 403,
  "detail": "…", "instance": "/ruta/sin/consulta", "traceId": "3f0c…-uuid" }
```

- `type` = `https://confia.hn/problems/` + código estable, sin transformarlo (ADR-0019, punto 2; base
  de `docs/ui-ux` §9). Determinista y derivado solo del código.
- `title` y `detail` salen del catálogo es-HN (decisión 10), nunca del mensaje de la excepción.
- `instance` = `request.getRequestURI()` sin cadena de consulta, recortado a 1024 caracteres. Repite
  al cliente su propia ruta; no se registra en el log por esta vía.
- **Nombre del campo de traza: `traceId`**, comprobado contra `ContractSchemas.problemDetail()`. Su
  valor es el identificador de petición del servidor (decisión 11).
- `errors` solo en `validation-failed` (decisión 9).
- `Content-Type: application/problem+json` en todos los casos.

**Catálogo de códigos y estados**, en `ProblemCode` (enumerado: un código no puede repetirse):

| Código | Estado | Productor en este cambio |
|---|---|---|
| `validation-failed` | 400 | Validación, cuerpo mal formado, cortafuegos, cabecera de idempotencia repetida o larga |
| `idempotency-key-missing` | 400 | `IdempotencyKeyInterceptor` |
| `authentication-required` | 401 | Cadena: toda petición anónima denegada (**código de diseño**, sección 9) |
| `forbidden` | 403 | Cadena: todo principal autenticado fuera de la lista |
| `resource-not-found` | 404 | Recurso inexistente bajo un prefijo público de springdoc, solo `local` y `preprod` (**código de diseño**) |
| `idempotency-conflict` | 409 | `IdempotencyConflictException` (ambas razones) |
| `unsupported-media-type` | 415 | Tipo de contenido no admitido (**código de diseño**; la especificación exige `415` sin nombrar el código) |
| `idempotency-payload-mismatch` | 422 | `IdempotencyPayloadMismatchException` |
| `too-many-requests` | 429 + `Retry-After` | Limitador |
| `capacity-exceeded` | 503 | Tabla del limitador llena y semáforo del materializador agotado |
| `internal-error` | 500 | Toda excepción no prevista y todo `DomainException` de código desconocido |

- **Código de la denegación uniforme: `authentication-required`.** No puede ser
  `authentication-failed`, que la especificación reserva para el inicio de sesión de la parte 4b, ni
  `session-expired` o `token-invalid`, que describen causas que esta cadena no distingue. Nombra la
  condición observable (falta autenticación) y no la causa, que es justo lo que exige la uniformidad.
- `resource-not-found` existe porque un archivo inexistente bajo `/swagger-ui/**` en `local` daría
  `500` si no tuviera código. `405` y `406` no tienen productor alcanzable (la cadena responde antes)
  y no se catalogan: si aparecieran, caen en `internal-error` y se registran.
- **Vocabulario frente a `docs/ui-ux` §9.** Ese documento usa `validation-error`, `rate-limited` e
  `insufficient-permission`, y dice que `title` y `detail` llegan en inglés. Se conservan los códigos
  de la propuesta aprobada (`validation-failed`, `too-many-requests`, `forbidden`) y el catálogo
  es-HN de la especificación; se añade una nota fechada en `docs/ui-ux/04-patrones-de-interaccion.md`
  §9 con la correspondencia, sin reescribir su tabla. Un código publicado no se renombra (ADR-0019,
  punto 3), y estos son los primeros que se publican.

**Traducción.** `ProblemExceptionHandler` (`@RestControllerAdvice`, orden más alto):

1. `DomainException` → `ProblemCode.ofCode(code())`; desconocido → `internal-error`, sin el código
   original en el cuerpo, con registro `ERROR`.
2. Excepciones del marco enumeradas de forma explícita (decisión 9) → su código.
3. Desconexión del cliente (`DisconnectedClientHelper.isClientDisconnectedException`, que cubre
   `AsyncRequestNotUsableException` y el aborto de Tomcat) → no escribe nada y registra en `DEBUG`
   sin traza (requisito «el abandono no se registra como error»).
4. Cualquier otra → `500 internal-error`; registro `ERROR` con la excepción completa y el
   `requestId` en el MDC.

La cadena de seguridad escribe con el mismo `ProblemResponses` desde el punto de entrada, el
manejador de acceso denegado y el de rechazo del cortafuegos. `RequestContextFilter` es el último
recurso: si una excepción escapa a todo y la respuesta no está confirmada, escribe `500
internal-error`.

**Descartado:** `ProblemDetail` de Spring con `spring.mvc.problemdetails.enabled`: su `type` por
omisión es `about:blank`, sus `title` vienen de la razón HTTP en inglés y la cadena de seguridad
tendría que reproducir su serialización por otra vía.

### Decisión 9 — Validación sin repetir el valor rechazado

| Excepción | Respuesta |
|---|---|
| `MethodArgumentNotValidException`, `HandlerMethodValidationException`, `ConstraintViolationException` | `400 validation-failed` con `errors` |
| `HttpMessageNotReadableException` (cuerpo truncado o JSON inválido) | `400 validation-failed` sin `errors` y sin el mensaje del analizador |
| `MissingRequestHeaderException`, `MissingServletRequestParameterException`, `MethodArgumentTypeMismatchException` | `400 validation-failed` |
| `HttpMediaTypeNotSupportedException` | `415 unsupported-media-type` |
| `NoResourceFoundException` | `404 resource-not-found` |

- `errors` es un arreglo de `{ "field": "<ruta del campo>", "reason": "<clave de la restricción>" }`.
  `reason` es el nombre simple de la anotación en kebab-case (`not-blank`, `size`, `pattern`),
  estable para el mapa de la interfaz. Nunca se incluye el valor rechazado, el mensaje por omisión
  de la restricción ni la interpolación `${validatedValue}`.
- `errors` es un **miembro de extensión de RFC 9457** que el esquema `ProblemDetail` del contrato
  todavía no declara. No se añade ahora porque la especificación congela la instantánea OpenAPI en
  este cambio; lo declara el primer endpoint de producción con validación (parte 4b). Registrado
  como riesgo.

### Decisión 10 — Catálogo de mensajes es-HN con idioma fijo

- Archivo único `apps/api/app/src/main/resources/i18n/problems.properties`, UTF-8, contenido es-HN,
  con claves `problem.<código>.title` y `problem.<código>.detail`.
- Bean explícito `problemMessageSource` (`ResourceBundleMessageSource`, `defaultEncoding=UTF-8`,
  `fallbackToSystemLocale=false`, `useCodeAsDefaultMessage=false`) en `WebEdgeConfiguration`. Se
  resuelve siempre con `Locale.forLanguageTag("es-HN")`: `Accept-Language` no se consulta. No se usa
  el `messages.properties` de la autoconfiguración, para no mezclar este catálogo con el de otros
  usos.
- `ProblemCatalogCoverageTest`: cada `ProblemCode` tiene `title` y `detail` no vacíos, y cada clave
  del archivo corresponde a un `ProblemCode`; falla nombrando el código o la entrada. Afirma además
  que no existen `authentication-failed`, `token-invalid`, `token-expired`, `institution-not-found` ni
  `institution-inactive`.

### Decisión 11 — Contexto de petición: identificador, origen, MDC y `ScopedValue`

`RequestContextFilter` (`HIGHEST_PRECEDENCE + 10`, antes de `FilterChainProxy`):

1. `requestId = UUID.randomUUID()`. Toda cabecera del cliente (`X-Request-Id`, `traceparent`) se
   ignora. No se devuelve en una cabecera: viaja en `traceId` del cuerpo de error.
2. Resuelve la IP del cliente (decisión 12) y el agente de usuario: cabecera `User-Agent` truncada a
   `confia.web.user-agent-max-length` (por omisión 512) caracteres, con los caracteres de control
   sustituidos por espacios (`NUL` rompería la columna `TEXT` de PostgreSQL); ausente → `null`.
3. Pone `requestId` en el MDC (registro estructurado) y lo retira en `finally`.
4. Ejecuta el resto de la cadena dentro de `ScopedValue.where(RequestOrigin.CURRENT, origin)`. JDK 25
   finaliza `ScopedValue`: el valor es inmutable, solo existe durante la llamada y no puede filtrarse
   a otra petición aunque el hilo se reutilice. El hilo de la petición es el mismo que ejecuta el caso
   de uso, `TransactionRunner` y la espera del retardo.
5. Último recurso (decisión 8).

`RequestOrigin(UUID requestId, ClientAddress clientAddress, String userAgent)` vive en
`shared.security`, con `static Optional<RequestOrigin> current()`.

**Descartado:** `ThreadLocal` o `RequestContextHolder`: exigen limpieza manual y una fuga entre
peticiones solo se ve bajo concurrencia. **Descartado:** reutilizar el identificador del cliente:
permitiría al cliente fijar el identificador que aparece en la bitácora.

### Decisión 12 — IP del cliente solo a través de proxies de confianza

**Propiedad:** `confia.web.trusted-proxies`, lista de IP o rangos CIDR, vacía por omisión;
variable de entorno `CONFIA_WEB_TRUSTEDPROXIES` separada por comas (enlace relajado de Spring Boot).
`WebEdgeProperties` (registro enlazado por constructor) analiza cada entrada con `CidrBlock.parse`;
una entrada inválida (`10.0.0.0/33`, `no-es-una-ip`) lanza una excepción cuyo mensaje nombra
`confia.web.trusted-proxies[i]`, y el arranque falla. Ningún archivo de configuración del repositorio
fija un valor.

**Análisis de direcciones sin DNS.** Toda dirección (remota, de la cabecera o de la propiedad) se
analiza con `InetAddress.ofLiteral` (JDK 22+), que acepta solo literales y nunca resuelve nombres.
Una IPv6 mapeada a IPv4 se normaliza a su `Inet4Address`. `ClientAddress.parseLiteral` es el único
punto de entrada.

**Algoritmo** de `ClientAddressResolver`:

1. `remote = normalize(request.getRemoteAddr())`.
2. Si `remote` no pertenece a la lista → **cliente = `remote`**; `X-Forwarded-For` se ignora entero.
3. Si pertenece: se concatenan **todas** las líneas `X-Forwarded-For` en el orden recibido, se
   separan por comas y se recortan los espacios.
4. Si la lista resultante está vacía, si alguna entrada no es un literal IP válido (incluidas las
   vacías, `ip:puerto` y `[ipv6]`), o si supera 32 entradas o 1024 caracteres → **cliente =
   `remote`**.
5. Se recorre de derecha a izquierda: la primera entrada que **no** pertenece a la lista es el
   cliente.
6. Si todas pertenecen → cliente = la primera de la izquierda.

- `server.forward-headers-strategy: none` en `application.yml`. Sin esa línea, Spring Boot activa la
  estrategia nativa al detectar una plataforma de nube (por ejemplo, Kubernetes) y la válvula de
  Tomcat reescribiría `getRemoteAddr()` desde `X-Forwarded-For` con sus propios rangos privados por
  omisión, anulando esta decisión y con ella H1.
- Con la lista vacía (valor por omisión), ningún cliente puede fijar su IP.

### Decisión 13 — El origen llega a la bitácora por un decorador de `shared.audit`

`RequestOriginAuditLogWriter implements AuditLogWriter` envuelve a `JooqAuditLogWriter`:
si hay `RequestOrigin.current()` y el asiento trae `sourceIp` o `userAgent` en `null`, los completa
con la IP resuelta (texto canónico del literal) y el agente de usuario; un valor explícito del
llamador siempre gana; sin petición activa, el asiento pasa intacto con ambos en `null`.

- `shared.audit` depende de `shared.security` (`RequestOrigin`), nunca de `shared.web` ni de
  `identity`. Los casos de uso de identidad no cambian: el origen les llega sin que lo pidan. La
  propagación explícita a los comandos de identidad sigue siendo de la parte 4b.
- `requestId` y `traceId` del asiento no se tocan aquí: su semántica la fija la parte 4b con sus
  endpoints.

**Descartado:** que el escritor jOOQ lea el contexto directamente: mezclaría el adaptador con el
borde. **Descartado:** añadir el origen a cada comando de identidad ahora: es el alcance de la parte
4b y obligaría a cambiar casos de uso sin endpoint.

### Decisión 14 — Vigencia de sesión: puerto sin adaptador

```java
package com.confia.shared.security;
public interface SessionValidity {
    /** Whether the session identified by {@code sessionId} is still valid at this instant. */
    boolean isActive(UUID sessionId);
}
```

Sin implementación ni bean. `WebEdgeScopeExclusionInventoryTest` lo declara como puerto sin
adaptador (dueño: `session-tokens-and-web-layer`, C5b), con fixture que lo implementa y se detecta.
La forma exacta la puede ajustar la parte 4b: no tiene consumidores.

### Decisión 15 — Puerto `RateLimiter`: dos dimensiones y semántica de las capas

```java
public interface RateLimiter {
    RateLimitDecision tryAcquire(ClientAddress client);   // counts the request only if admitted
    void recordFailure(ClientAddress client);              // the failure dimension
}
public sealed interface RateLimitDecision {
    record Admitted() implements RateLimitDecision {}
    record Limited(Duration retryAfter) implements RateLimitDecision {}
    record CapacityExhausted() implements RateLimitDecision {}
}
```

Una instancia por política (aislamiento: llenar la tabla de una política no deja fuera a otra). La
política `admin-login` se registra en administración pero **no se aplica a ninguna ruta** de
producción en este cambio.

**Estado por clave:** `admitted` (instantes de las peticiones admitidas, como máximo
`requestLimit`), `lastAdmittedAt`, `failures` (anillo con los últimos `failureThreshold` fallos) y
`restrictedSince` (nulo si no hay restricción).

**Evaluación en `now`**, antes de cada operación:

1. Se descartan las admisiones con antigüedad `>= requestWindow`.
2. Si `restrictedSince != null` y `now >= restrictedSince + restrictionCap` → la restricción termina
   y `failures` se vacía (**tope de 1 hora; el contador parte de cero**).
3. Si `restrictedSince != null` y no hay **ningún** fallo con antigüedad `< failureWindow` → la
   restricción termina antes del tope; los fallos se conservan.
4. `failuresInWindow` = fallos con antigüedad **estrictamente menor** que `failureWindow`.

**`tryAcquire`:** capa 1 rechaza si `admitted.size() >= requestLimit`, con espera hasta que venza la
admisión más antigua; capa 2 rechaza si hay restricción y `now < lastAdmittedAt +
restrictedInterval`. Si rechazan ambas, `Retry-After` es el mayor de los dos. Un rechazo **no** se
registra ni mueve ninguna ventana. Si se admite: `admitted.add(now)` y `lastAdmittedAt = now`.

**`recordFailure`:** añade `now` al anillo; si no hay restricción y `failuresInWindow >=
failureThreshold`, `restrictedSince = now`. Un éxito no existe en el puerto: nunca limpia nada.

**Semántica de la capa 2, decidida:** la restricción termina en el primero de dos instantes, el tope
(`restrictedSince + 1 h`, con contador a cero) o 10 minutos sin ningún fallo. Se descartó terminarla
cuando `failuresInWindow` baja de 10: con un atacante a 1 intento por minuto, el fallo más antiguo
cae fuera justo en el instante del siguiente intento, la restricción se levantaría y la capa 1 le
devolvería una ráfaga de 10. También se descartó la duración fija de una hora sin salida temprana:
castiga una hora entera a una IP compartida que dejó de fallar. Comprobación contra los escenarios:
fallo 10 en `t0` → intento en `t0 + 30 s` da `Retry-After: 30` y `t0 + 60 s` se admite; un fallo con
antigüedad exacta de 10 minutos no cuenta.

**`Retry-After`:** segundos enteros, redondeados hacia arriba, nunca menores de 1. Ni la respuesta ni
sus cabeceras incluyen límite ni cupo.

**Clave del cliente (`ClientAddress.rateLimitKey()`):** IPv4 completa (4 bytes); IPv6 sus primeros 64
bits (8 bytes); IPv6 mapeada a IPv4 → la IPv4 (ya normalizada al analizarla); el identificador de
zona no forma parte de `getAddress()`, así que se ignora.

### Decisión 16 — Adaptador en memoria: tabla acotada que falla cerrado

`InMemoryRateLimiter(RateLimitPolicy policy, Clock clock)`, en `shared.security`:

- **Estructura:** `ConcurrentHashMap<ClientKey, Entry>` más un `AtomicInteger size`. Cada operación
  sobre una clave ocurre dentro de `map.compute(key, …)`, que es atómico por clave: las mutaciones de
  `Entry` están protegidas por el bloqueo del cubo del mapa, sin bloqueos propios.
- **Alta de una clave nueva:** dentro de `compute`, si `entry == null` se intenta reservar un hueco con
  CAS (`size.getAndUpdate(s -> s < max ? s + 1 : s)`). Sin hueco → no se inserta y el resultado es
  `CapacityExhausted`. **Nunca se desaloja una entrada vigente.** `size` solo sube en una inserción
  real y solo baja en una eliminación real, así que la tabla no supera `maxEntries` en ningún
  instante.
- **Recuperación de entradas vencidas, perezosa:** ante `CapacityExhausted`, si `now >= nextSweepAt`,
  un solo hilo (CAS sobre `nextSweepAt`) recorre el mapa y elimina con `computeIfPresent` las
  entradas inactivas (sin admisiones vigentes, sin fallos en ventana, sin restricción y con
  `lastAdmittedAt + restrictedInterval` vencido); después se reintenta una vez. El barrido ocurre como
  mucho una vez por segundo, para que un atacante con la tabla llena no convierta cada IP nueva en un
  recorrido completo. No hay tarea periódica: `@Scheduled` está prohibido.
- **Memoria acotada:** por entrada, dos anillos de `long` (10 + 10) y unos campos, unos 350 bytes con
  la clave y el nodo del mapa. `max-entries` por omisión 50 000, unos 17 MB por política en el peor
  caso.
- **Falla cerrado también ante errores:** toda excepción inesperada dentro del limitador se trata en
  el interceptor como `CapacityExhausted` (`503`), nunca como admisión.
- **Limitación declarada (D2):** estado por proceso, se pierde al reiniciar y no se comparte entre
  réplicas. Notas fechadas en `docs/03` §4.4 y §10.

**Descartado:** un candado global: exacto, pero serializa a todos los clientes. **Descartado:**
caché con desalojo LRU: desalojar una entrada vigente es fallar abierto, justo lo que D2 prohíbe.

### Decisión 17 — El limitador en el borde: interceptor, `429` y `503`

- `@RateLimited(policy = "admin-login")` sobre un método manejador. `RateLimitInterceptor`
  (`HandlerInterceptor`, `preHandle`) resuelve el limitador por nombre de política, toma
  `RequestOrigin.current().clientAddress()` y llama a `tryAcquire` **antes de invocar el
  controlador**: una petición rechazada no abre transacción, no ejecuta Argon2id y no registra fallos.
- `Limited` → `TooManyRequestsException(retryAfter)` → `429 too-many-requests` con `Retry-After`.
  `CapacityExhausted` o ausencia de `RequestOrigin` → `CapacityExceededException` → `503
  capacity-exceeded`, sin `Retry-After` y sin indicar qué IP lo causó.
- **Confirmado `503 capacity-exceeded` con la tabla llena**, como supuso la especificación: `429`
  diría al cliente que él excedió su límite, lo cual es falso, y le daría una espera que nadie puede
  prometer; `503` describe el estado real (el servidor no tiene capacidad) y comparte código con el
  semáforo, una sola respuesta uniforme de saturación. El registro del servidor distingue la causa.
- Un nombre de política desconocido en un `@RateLimited` hace fallar el arranque (comprobación al
  refrescar el contexto sobre todos los métodos manejadores).
- El controlador registra fallos con `rateLimiter.recordFailure(...)` según el resultado del caso de
  uso; en la parte 4b, después de que el caso de uso confirme.
- **Configuración** (`RateLimitProperties`, registro enlazado por constructor con validación que
  nombra la propiedad ante un valor no positivo), prefijo `confia.web.rate-limit.admin-login`:
  `request-limit: 10`, `request-window: 1m`, `failure-threshold: 10`, `failure-window: 10m`,
  `restricted-interval: 1m`, `restriction-cap: 1h`, `max-entries: 50000`.

### Decisión 18 — Materialización del retardo

```java
public final class RequiredDelayMaterializer {
    public <T> T execute(Supplier<Delayed<T>> useCase);   // Delayed<T>(T value, Duration requiredDelay)
}
```

Flujo, con el limitador ya superado:

1. `semaphore.tryAcquire()` (sin espera). Sin permiso → `CapacityExceededException` (`503`) **antes**
   de invocar el caso de uso: ningún efecto, ningún fallo registrado.
2. `try { result = useCase.get(); … } finally { semaphore.release(); }`: el permiso se libera siempre,
   también si el caso de uso lanza o la espera se interrumpe.
3. El caso de uso abre y confirma su transacción con `TransactionRunner` y retorna: al volver, no hay
   transacción, conexión del grupo ni bloqueo de fila. Defensa adicional: si
   `TransactionSynchronizationManager.isActualTransactionActive()` es cierto, el materializador lanza
   `IllegalStateException` en vez de esperar.
4. Retardo `<= 0` → no espera. Positivo → espera **el valor completo** desde ese instante (se suma al
   procesamiento, nunca lo absorbe), mediante `DelayTimer.await(Duration)`, cuyo valor por omisión,
   un lambda dentro de la propia clase, llama a `Thread.sleep(Duration)`. Las pruebas inyectan un
   temporizador controlado por `CountDownLatch`.
5. La espera ocurre en el hilo de la petición, que es virtual: `spring.threads.virtual.enabled: true`
   en `application.yml` (P3). Si el hilo actual no es virtual, el materializador lanza
   `IllegalStateException` (`500`) en vez de retener un hilo de plataforma; el bean además comprueba
   la propiedad al arrancar y falla si está apagada. JDK 25 ya no fija el hilo portador en bloques
   `synchronized` (JEP 491).

- **Retardo cero:** responde sin consumir permiso, porque el permiso se pide antes de saber el
  retardo; el escenario «el semáforo conserva todos sus permisos» se verifica después de la
  respuesta.
- **Desconexión del cliente (P3):** Tomcat no avisa durante la espera. El diseño hace la desconexión
  **inocua**: la espera termina en su plazo, el permiso se libera en `finally`, la escritura falla y
  el traductor la reconoce como desconexión y no la registra como error ni intenta escribir. No hay
  forma de liberar el permiso **antes** de que venza el retardo con E/S de servlet bloqueante; el
  modo asíncrono tampoco lo detecta sin escribir. Esto contradice un escenario de la especificación y
  el punto 6 del contrato de la decisión 1 de identidad: **pregunta abierta 1**.
- **Configuración:** `confia.web.delay.max-concurrent-waits` (por omisión 200, positivo, validado). Al
  arrancar se exige además que sea a lo sumo la mitad de `server.tomcat.max-connections` (8192 por
  omisión), para que las esperas nunca agoten los sockets de las peticiones sin retardo.
- **Tiempos de Tomcat** en `application.yml`: `server.tomcat.connection-timeout: 10s` (lectura de la
  petición), `server.tomcat.keep-alive-timeout: 20s`, `server.tomcat.max-keep-alive-requests: 100`,
  `server.max-http-request-header-size: 16KB` (acota también `X-Forwarded-For`).
- **Orden en un endpoint de autenticación (contrato para la parte 4b):** limitador → permiso del
  semáforo → caso de uso (confirma) → `recordFailure` si corresponde → espera → respuesta.

### Decisión 19 — Borde HTTP de la idempotencia

- **Declaración:** `@IdempotentWrite` sobre el método manejador que mueve dinero.
  `IdempotencyKeyInterceptor` (`preHandle`) valida la cabecera **antes del controlador**:
  - ausente o en blanco → `IdempotencyKeyMissingException` → `400 idempotency-key-missing`;
  - más de una cabecera `Idempotency-Key`, más de **128** caracteres o algún carácter fuera de ASCII
    visible (`0x21`–`0x7E`) → `400 validation-failed`. 128 queda por debajo del límite técnico de 200
    de la tabla y admite un UUID con holgura; el juego de caracteres evita la inyección en registros.
  - Un manejador sin la anotación no exige la cabecera.
- **`IdempotentRequestHandler.handle(HttpServletRequest, SecurityContext, JsonNode body,
  Supplier<IdempotentResponse> useCase)`**:
  - `IdempotencyKey.endpoint` = método + plantilla de la ruta (`HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE`),
    acotado y sin datos del cliente.
  - La carga que se resume es `{"body": <cuerpo>, "pathVariables": {…}}`: la misma clave sobre otro
    recurso no se confunde con una repetición. El resumen canonicalizado de `RequestPayloadHasher` ya
    hace equivalentes `{"a":1,"b":2}` y `{"b":2,"a":1}`.
  - La institución sale del `SecurityContext` (ADR-0009); en este cambio la aporta el controlador de
    prueba. Dos instituciones con la misma clave no se mezclan porque la clave primaria la incluye.
- **Traducción de salidas, sin cambiar el contrato de `IdempotentExecutor`:**

| Salida del componente | Respuesta |
|---|---|
| `Executed(response)` | `response.responseStatus()` y cuerpo, **sin** `Idempotent-Replay` |
| `Replayed(response)`, incluido el camino `23505` que el componente reproduce en su T3 | mismo estado y cuerpo, con `Idempotent-Replay: true` |
| `IdempotencyConflictException` (`WAIT_EXHAUSTED` por `55P03` o `MARKER_IN_PROGRESS`) | `409 idempotency-conflict` |
| `IdempotencyPayloadMismatchException` | `422 idempotency-payload-mismatch` |

  Ver la sección 9, corrección 4: el choque `23505` con la clave ya confirmada **se reproduce**, no
  responde `409`.
- **Controlador de demostración solo en pruebas:** `IdempotencyDemoController`, en el árbol de
  pruebas, registrado únicamente en el arnés con base de datos. No existe en el camino de producción;
  `OpenApiContractSnapshotTest` sigue igual. `IdempotencyNotInIdentityTest` (decisión 20) impide su
  uso en `identity`.

### Decisión 20 — Reglas de ArchUnit y de Spring Modulith

Todas con la convención de dos mitades (producción en verde y fixture permanente rechazado con
fragmentos que nombran la violación) salvo donde se indica.

| Prueba | Regla | Fixture permanente |
|---|---|---|
| `WebLayerDependencyRulesTest` (W1) | Ninguna clase de un paquete `..web..` depende de `..infrastructure..`, de `confia.generated.jooq..` ni de `org.jooq..` | `fixture/webedge/web/BadWebUsesInfrastructure` |
| `SharedBoundaryRulesTest` (W3) | Ninguna clase de `<raíz>.shared..` depende de `<raíz>.*` salvo `<raíz>.shared..` y `<raíz>.kernel..`; construida por raíz, como `BootstrapEntryPointRulesTest`: producción con `com.confia`, fixture con `com.confia.architecture.fixture.sharedboundary` | `fixture/sharedboundary/shared/web/BadSharedUsesIdentity` y `fixture/sharedboundary/identity/SomeIdentityType` |
| `WebExposedTypesRuleTest` (W2a) | Los métodos públicos de clases meta-anotadas con `@Controller` no exponen en su firma tipos de `..domain..`, `org.jooq.Record` ni `confia.generated.jooq..` | `fixture/webedge/web/BadRecordReturningController`. **Mitad de producción con `allowEmptyShould(true)`** y entrada `ALLOW_EMPTY_SHOULD` en `EmptyShouldExceptionInventoryTest`: condición «ninguna clase de producción está meta-anotada con `@Controller`», dueño `session-tokens-and-web-layer` (primer endpoint), ADR-0018 |
| `WebExposedTypesRuleTest` (W2b) | Ningún componente de registro ni campo de una clase de `..web..` es de un tipo de `..domain..` o de jOOQ | `fixture/webedge/web/BadDomainCarryingDto`. Mitad de producción **sin** excepción: evalúa los registros reales de `shared.web` (`ProblemBody`, `FieldViolation`, `PublicEndpoint`) |
| `BlockingWaitConfinementTest` (W4) | Ninguna clase de producción en `..web..`, `..application..` o `..domain..` llama a `Thread.sleep`, `TimeUnit.sleep`, `LockSupport.park*` ni `Object.wait`, salvo `RequiredDelayMaterializer`; afirma además que el materializador sí la llama (no vacuidad) | `fixture/webedge/web/BadSleepingWebComponent` |
| `IdempotencyNotInIdentityTest` (W5) | Ninguna clase de `com.confia.identity..` depende de `com.confia.shared.web.idempotency..`; ninguna clase de producción fuera de `shared.web.idempotency` y `shared.web.edge` lo hace | `fixture/identity/web/BadIdempotentLoginController` (paquete que simula `identity`, mismo recurso que `NoBlockingWaitInIdentityTest`) |
| `WebEdgeScopeExclusionInventoryTest` | Inventario de ausencias con destino, cada una sobre un conjunto base no vacío: limitador y materializador sin uso fuera de su paquete y de `shared.web.edge`; una sola implementación de `RateLimiter` y ningún cliente de Redis en el camino de clases; ninguna clase implementa `SessionValidity`, `AuthenticationProvider` ni usa `jakarta.servlet.http.Cookie`; ninguna clase de `..web..` cuyo nombre contenga `Institution` | Fixtures de detección por cada ausencia, como `IdentityScopeExclusionInventoryTest` |

- `NoBlockingWaitInIdentityTest` **no cambia**; W4 duplica su predicado de primitivas de espera en
  lugar de extraerlo, para no tocarlo.
- `optionalLayer("Web")` **sigue retirado**: la capa `Web` es obligatoria desde el cambio 3 y
  `shared.web` gana clases reales. La única entrada nueva del inventario es la de W2a, y
  `SuppressionCitesAdrTest` exige que el recuento de `allowEmptyShould(` coincida.
- **Spring Modulith:** `SpringModulithVerificationTest` sin cambios en su código. `shared.platform` y
  `shared.web.edge` llevan `@NamedInterface` con su consumidor en el Javadoc del `package-info`
  (ADR-0022). `identity` → `shared` y `bootstrap` → `identity`/`shared` son las únicas direcciones;
  W3 hace explícita la que Modulith solo vería como ciclo.
- W1 y W3 se solapan en parte con `LayeredArchitectureTest` y `NoCrossModuleDomainImportsTest`, que
  siguen vigentes; las nuevas existen porque la especificación pide una regla con fixture propio por
  cada prohibición y porque `LayeredArchitectureTest` no ve los paquetes sin capa.

### Decisión 21 — Lista blanca cerrada e instantánea del mapa de rutas del portal

- **Soporte de pruebas `RegisteredRoutes.of(ApplicationContext)`**: enumera los pares (método,
  patrón) de todo `RequestMappingInfoHandlerMapping`, de `RouterFunctionMapping` y de toda
  `AbstractUrlHandlerMapping`.
- **`PublicRouteAllowListTest`** (administración y portal, por `ConfiaApplication.launch` y
  `TestProcessArguments`, con el perfil por omisión y con `local`): para cada ruta registrada envía
  una petición anónima (sustituyendo comodines por una muestra); si la respuesta no es `401`, la ruta
  debe estar en `PublicEndpoints`; y cada entrada de `PublicEndpoints` corresponde a una ruta
  registrada cuando su condición está activa. El control negativo arranca el arnés con una segunda
  cadena de prueba que permite `/test/leak` y espera que la comprobación falle nombrando la ruta.
- **`PortalRouteMapSnapshotTest`**: genera el mapa del portal arrancado por su camino de producción
  con el perfil por omisión (la configuración de producción) y lo compara con
  `apps/api/routes/portal.routes.json`, comprometido con contenido explícito
  `{"process": "portal", "routes": []}`. Nunca sobrescribe; actualizar exige
  `-Dconfia.routes.update=true` y aun así falla, igual que la instantánea OpenAPI. Falla si el archivo
  no existe. Control negativo permanente sobre una copia alterada en un directorio temporal.
- La prueba de «ninguna ruta de producción en administración» usa el mismo enumerador con el perfil
  por omisión y espera el conjunto vacío.

### Decisión 22 — Ningún registro con cabeceras de autorización, cookies ni cuerpos

- `SensitiveLogGuard`: un `TurboFilter` de Logback instalado por un bean de `WebEdgeConfiguration`
  al refrescar el contexto (después de que el sistema de registro de Spring Boot se inicialice). Deniega
  los eventos `DEBUG` y `TRACE`, **a cualquier nivel configurado**, de una lista cerrada de prefijos
  de logger que registran cabeceras o cuerpos crudos: `org.springframework.web.servlet.mvc.method.annotation`,
  `org.springframework.http.converter`, `org.apache.coyote` y `org.apache.tomcat.util.net`. `INFO`,
  `WARN` y `ERROR` de esos paquetes siguen pasando.
- `spring.mvc.log-request-details: false` explícito en `application.yml`.
- **`SensitiveDataLoggingTest`** (arnés sin base de datos): adjunta un `ListAppender` a la raíz, pone
  la raíz **y** los prefijos protegidos en `TRACE`, envía una petición con `Authorization: Bearer
  SECRETO-A`, `Cookie: sid=SECRETO-B` y un cuerpo con `SECRETO-C` a tres rutas (aceptada, denegada y
  que falla con `500`) y afirma que ningún mensaje formateado, argumento, MDC ni traza contiene los
  tres valores. **Control negativo:** el mismo verificador sobre un evento registrado a propósito con
  `SECRETO-A` debe reportar la violación. También afirma que no aparece `generated security password`.
- La redacción de credenciales en DTO propios es de la parte 4b (C6c).

### Decisión 23 — Sin ADR nuevo; notas fechadas

El limitador interino no se eleva a ADR: lo decidió el propietario (D2), su vida termina en el
cambio 11 detrás del mismo puerto, y su limitación se declara en `docs/03`. El patrón de borde
(configuraciones públicas de `shared`, lista de permitidos) ya está en ADR-0022 y ADR-0024. Notas
fechadas, sin reescribir cuerpos:

- `docs/03-seguridad.md` §4.4 y §10: limitador en memoria por proceso como control interino de H1,
  con sus tres limitaciones (por proceso, se pierde al reiniciar, no compartido); semántica de la
  capa 2 de la decisión 15.
- `docs/09-roadmap-y-fases.md`: líneas 114 y 115 (D3), entregable 6 (la mitad HTTP la entrega este
  cambio) y la partición de la parte 4 (D1).
- `openspec/changes/foundations-plan/exploration.md`: cuarto corte (D1) y aceptación del limitador en
  memoria como tope funcional de H1 hasta el cambio 11 (D2).
- `docs/05-infraestructura-y-despliegue.md`: variables `SPRING_DATASOURCE_*`, los tres secretos de la
  decisión 3 y `CONFIA_WEB_TRUSTEDPROXIES` (vacía por omisión; el cambio 11 la llena).
- `docs/ui-ux/04-patrones-de-interaccion.md` §9: correspondencia de códigos y es-HN (decisión 8).

## 4. Flujo de datos

```
cliente ──► SecurityHeadersFilter ──► RequestContextFilter ──────────────────────────────┐
                                        │ requestId = UUID (servidor)                     │
                                        │ ClientAddressResolver(remote, XFF, proxies)      │
                                        │ User-Agent truncado                              │
                                        │ MDC.requestId · ScopedValue RequestOrigin        │
                                        ▼                                                 │
                              FilterChainProxy ── rechazo cortafuegos ─► 400 ─┐            │
                                        │── fuera de lista: 401 / 403 ───────┤            │
                                        ▼ permitido                          │            │
                              RateLimitInterceptor ── 429 / 503 ─────────────┤            │
                              IdempotencyKeyInterceptor ── 400 ──────────────┤            │
                                        ▼                                    │            │
                              controlador ─► materializer.execute(          │            │
                                               permiso ─► caso de uso ─► TransactionRunner (commit)
                                                                │ AuditLogWriter (decorado:
                                                                │   sourceIp, userAgent ◄── RequestOrigin)
                                               ◄── Delayed ◄────┘
                                               espera (hilo virtual) ─► respuesta
                                        │ excepción
                                        ▼
                              ProblemExceptionHandler ─► ProblemResponses ◄────────────────┘
                                        (type ◄ ProblemCode, title/detail ◄ problems.properties,
                                         traceId ◄ RequestOrigin.requestId)
```

## 5. Cambios de archivos

Rutas bajo `apps/api/app/src/` salvo indicación.

| Archivo | Acción | PR |
|---|---|---|
| `main/java/com/confia/bootstrap/admin/AdminApplication.java` | Sin exclusión de `DataSourceAutoConfiguration`; exclusiones de la decisión 4 y 5; `@Import` de la decisión 1; Javadoc | 1, 2, 3, 9, 10, 11 |
| `main/java/com/confia/bootstrap/portal/PortalApplication.java` | Exclusiones de las decisiones 4 y 5; `@Import`; Javadoc | 3 |
| `main/java/com/confia/bootstrap/worker/WorkerApplication.java` | Excluye las dos autoconfiguraciones de seguridad; Javadoc | 3 |
| `main/java/com/confia/shared/platform/{SharedPlatformConfiguration,package-info}.java` | Crear (decisión 2) | 1 |
| `main/java/com/confia/identity/IdentityConfiguration.java` | Crear (decisión 3) | 2 |
| `main/java/com/confia/identity/infrastructure/ConfiguredLoginInstitutionProvider.java` | `fromValue(String)` público | 2 |
| `main/java/com/confia/shared/web/edge/*.java` y `package-info.java` | Crear (decisiones 1, 5, 17, 18, 19) | 3, 9, 10, 11 |
| `main/java/com/confia/shared/web/request/*.java` | Crear (decisiones 7, 11, 12, 22) | 3, 4, 5 |
| `main/java/com/confia/shared/web/problem/*.java` | Crear (decisiones 8, 9) | 3, 6 |
| `main/java/com/confia/shared/web/ratelimit/*.java` | Crear (decisión 17) | 9 |
| `main/java/com/confia/shared/web/delay/*.java` | Crear (decisión 18) | 10 |
| `main/java/com/confia/shared/web/idempotency/*.java` | Crear (decisión 19) | 11 |
| `main/java/com/confia/shared/security/{RateLimiter,RateLimitDecision,RateLimitPolicy,InMemoryRateLimiter,ClientAddress}.java` | Crear (decisiones 15, 16) | 5 (`ClientAddress`), 8 |
| `main/java/com/confia/shared/security/{RequestOrigin,SessionValidity}.java` | Crear (decisiones 11, 14) | 5, 7 |
| `main/java/com/confia/shared/audit/RequestOriginAuditLogWriter.java` | Crear (decisión 13) | 5 |
| `main/java/com/confia/shared/audit/AuditEntry.java`, `shared/security/{TransactionRunner,IdempotentExecutor}.java`, `shared/infrastructure/*.java` | Javadoc: ya no «ningún proceso lo registra» | 1, 5 |
| `main/resources/application.yml` | Decisiones 2, 5, 12, 18 y 22 | 1, 3, 4, 5, 10 |
| `main/resources/i18n/problems.properties` | Crear (decisión 10) | 3, 6, 9, 10, 11 |
| `apps/api/app/pom.xml` | `spring-boot-starter-security` | 3 |
| `apps/api/pom.xml` | Dos exclusiones en `bannedDependencies` | 3 |
| `apps/api/routes/portal.routes.json` | Crear (decisión 21) | 4 |
| `test/java/com/confia/bootstrap/{ProcessBeanPolicy,ProcessBeanIsolationTest,OpenApiProcess,ConfiaApplicationTest,OpenApiExposureByProfileTest}.java` | Decisiones 1, 2, 6 | 1, 2, 3 |
| `test/java/com/confia/bootstrap/{TestProcessArguments,AdminProductionWiringTest,RegisteredRoutes,PublicRouteAllowListTest,PortalRouteMapSnapshotTest}.java` | Crear | 1, 4 |
| `test/java/com/confia/shared/web/harness/*` | Arnés sin base de datos y con base de datos, controladores de prueba, filtro de principal de prueba | 3, 4, 5, 6, 9, 10, 11 |
| `test/java/com/confia/shared/web/**`, `test/java/com/confia/shared/security/**` | Pruebas de la sección 7 | 3 a 11 |
| `test/java/com/confia/architecture/{WebLayerDependencyRulesTest,SharedBoundaryRulesTest,WebExposedTypesRuleTest,BlockingWaitConfinementTest,IdempotencyNotInIdentityTest,WebEdgeScopeExclusionInventoryTest}.java` | Crear (decisión 20) | 7, 10, 11 |
| `test/java/com/confia/architecture/EmptyShouldExceptionInventoryTest.java` | Entrada de W2a | 7 |
| `test/java/com/confia/architecture/fixture/{webedge,sharedboundary,identity/web}/**` | Fixtures de la decisión 20 | 7, 10, 11 |
| `docs/03-seguridad.md`, `docs/05-infraestructura-y-despliegue.md`, `docs/09-roadmap-y-fases.md`, `docs/ui-ux/04-patrones-de-interaccion.md`, `openspec/changes/foundations-plan/exploration.md` | Notas fechadas (decisión 23) | 2, 5, 6, 9, 11 |

## 6. Interfaces y contratos

```java
// com.confia.shared.security
public record ClientAddress(InetAddress address) {
    public static ClientAddress parseLiteral(String literal);   // InetAddress.ofLiteral, never DNS
    public ClientKey rateLimitKey();                            // IPv4: 4 bytes; IPv6: first 8 bytes
    public String canonical();                                  // text written to source_ip
}
public record RequestOrigin(UUID requestId, ClientAddress clientAddress, String userAgent) {
    static final ScopedValue<RequestOrigin> CURRENT = ScopedValue.newInstance();
    public static Optional<RequestOrigin> current();
}
public record RateLimitPolicy(String name, int requestLimit, Duration requestWindow,
        int failureThreshold, Duration failureWindow, Duration restrictedInterval,
        Duration restrictionCap, int maxEntries) { /* compact constructor: all positive */ }
public interface RateLimiter { RateLimitDecision tryAcquire(ClientAddress c); void recordFailure(ClientAddress c); }
public sealed interface RateLimitDecision { record Admitted(); record Limited(Duration retryAfter); record CapacityExhausted(); }
public final class InMemoryRateLimiter implements RateLimiter { public InMemoryRateLimiter(RateLimitPolicy p, Clock c); }
public interface SessionValidity { boolean isActive(UUID sessionId); }

// com.confia.shared.web.problem
public enum ProblemCode { VALIDATION_FAILED("validation-failed", 400), /* … decision 8 table … */;
    public String code(); public int status(); public URI type();
    public static Optional<ProblemCode> ofCode(String code); }
public record ProblemBody(URI type, String title, int status, String detail, String instance,
        String traceId, @JsonInclude(NON_EMPTY) List<FieldViolation> errors) { }
public record FieldViolation(String field, String reason) { }

// com.confia.shared.web.edge
public record PublicEndpoint(HttpMethod method, String pattern) { }
public final class PublicEndpoints {
    public static PublicEndpoints forAdmin(Environment environment);
    public static PublicEndpoints forPortal(Environment environment);
    public PublicEndpoints(List<PublicEndpoint> endpoints);       // test harnesses only add routes
}
public final class SecurityChains {
    public static HttpSecurity denyByDefault(HttpSecurity http, PublicEndpoints endpoints,
            ProblemResponses problems) throws Exception;
}

// com.confia.shared.web.delay
public record Delayed<T>(T value, Duration requiredDelay) { }
@FunctionalInterface public interface DelayTimer { void await(Duration delay) throws InterruptedException; }

// com.confia.shared.web.ratelimit / idempotency
@Target(METHOD) @Retention(RUNTIME) public @interface RateLimited { String policy(); }
@Target(METHOD) @Retention(RUNTIME) public @interface IdempotentWrite { }
```

**Contrato HTTP de error:** `application/problem+json`, campos de la decisión 8, `Retry-After` solo en
`429`, `Idempotent-Replay: true` solo en una repetición. **Propiedades nuevas:** las de las
decisiones 2, 3, 12, 17 y 18; ninguna con valor secreto en el repositorio.

## 7. Estrategia de pruebas (TDD estricto)

Cada fila nace **en rojo** contra el árbol sin el código de producción correspondiente, con el
motivo de fallo esperado registrado en `apply-progress.md`; si falla por otra causa, el paso se
detiene y se investiga. Ninguna prueba de concurrencia espera por reloj: `CyclicBarrier`,
`CountDownLatch` y reloj inyectado (`MutableClock` de pruebas).

| Nivel | Qué | Cómo | Rojo esperado |
|---|---|---|---|
| Contexto real sin contenedor | Cableado administrativo: un `DataSource`, `DSLContext`, `TransactionRunner`, `Clock`, cinco casos de uso; sin `Flyway`; arranca con URL inalcanzable; portal y trabajador sin ninguno | `AdminProductionWiringTest` por `ConfiaApplication.launch` | Cero beans `DataSource` (exclusión vigente) |
| Contexto real | Lista de permitidos y prohibidos por proceso | `ProcessBeanIsolationTest` existente | Tras el `@Import` y antes de la línea en la política: «not in the allow-list» nombrando el paquete |
| Contexto real | Secretos ausentes impiden el arranque nombrando la propiedad y sin valor | `IdentityConfigurationSecretsTest` | El contexto arranca sin secretos (no hay configuración) |
| Contexto real | Cadena de administración: `401` en ruta inexistente y registrada, uniforme; métodos sobre ruta pública `GET`; variantes de ruta; sin `Set-Cookie` ni sesión; cabeceras base en éxito y error | `AdminSecurityChainTest` (arnés sin base de datos, `RANDOM_PORT`) | Sin dependencia de seguridad: `200`/`404` |
| Contexto real | `403 forbidden` con principal de prueba | Mismo, filtro de principal del arnés | `401` o `200` |
| Contexto real | Portal deniega todo método y ruta | `PortalSecurityChainTest` por `ConfiaApplication.launch` | `404` |
| Contexto real | Trabajador sin `SecurityFilterChain`, filtros ni servidor | `ProcessBeanIsolationTest[worker]` y aserción de tipos | Los 3 beans inertes de P1 |
| Contexto real | OpenAPI por perfil | Pruebas existentes (decisión 6) | Las 16 de P1 |
| Contexto real | Lista blanca cerrada y mapa del portal | `PublicRouteAllowListTest`, `PortalRouteMapSnapshotTest` con controles negativos | Instantánea ausente |
| Unidad | `ProblemCode` único, formato kebab, estado por código; desconocido → `500` sin filtrar | `ProblemCodeTest` | Clase inexistente |
| Unidad | Catálogo ↔ códigos; idioma fijo | `ProblemCatalogCoverageTest` | Archivo ausente |
| Contexto | Traductor: validación con `errors` sin valor, cuerpo truncado, `415`, `IllegalStateException` con mensaje sensible, `DomainException` con mensaje interno, registro con el mismo `requestId` | `ProblemTranslationTest` (arnés) | Cuerpos por omisión de Spring |
| Unidad + jqwik | `CidrBlock.contains` frente a una implementación de referencia con `BigInteger`; `ClientAddress.rateLimitKey` (mismo /64 ⇔ misma clave; IPv4 completa; mapeada = IPv4) | `CidrBlockProperties`, `ClientAddressProperties` | Clases inexistentes |
| Unidad + jqwik | Resolución de `X-Forwarded-For`: los ocho escenarios de la especificación como ejemplos, más la propiedad «con origen no confiable el resultado es siempre `remote`» | `ClientAddressResolverTest` | Clase inexistente |
| Unidad | Propiedad de proxies: ausente, CIDR, inválida, variable de entorno (`SystemEnvironmentPropertySource` con mapa propio y `Binder`) | `WebEdgePropertiesTest` | Clase inexistente |
| Contexto | Id del servidor ignora el del cliente; 100 peticiones concurrentes con `CyclicBarrier` dan 100 ids; id presente en `401` | `RequestContextFilterTest` | Sin `traceId` |
| Integración (`*IT`) | Asiento durante petición lleva IP y agente; fuera de petición, nulos; 50 peticiones concurrentes sin cruces | `RequestOriginAuditIT` (arnés con base de datos) | `source_ip` nulo |
| Contexto | Registros sin secretos, con control negativo | `SensitiveDataLoggingTest` | Spring registra el cuerpo en `DEBUG` |
| Unidad + jqwik | Limitador frente a un **modelo de referencia ingenuo** (listas sin acotar) sobre secuencias aleatorias de `avanzar reloj`, `tryAcquire`, `recordFailure`: mismas decisiones y mismo `Retry-After` | `InMemoryRateLimiterProperties` | Clase inexistente |
| Unidad | Escenarios de las capas 1 y 2, bordes de ventana, tope de 1 h, rechazos que no mueven la ventana, `Retry-After` redondeado, reinicio = instancia nueva | `InMemoryRateLimiterTest` | Clase inexistente |
| Concurrencia | 50 hilos misma IP → exactamente 10; tabla `N` con `2N` IP → exactamente `N` y tamaño nunca `> N` (muestreo del máximo de `size`); repetido 20 veces | `InMemoryRateLimiterConcurrencyTest` | Clase inexistente |
| Contexto | Interceptor: `429` con `Retry-After`, `503` con tabla llena, cero invocaciones del controlador y cero transacciones; política desconocida impide el arranque; valores por omisión y no positivos | `RateLimitEdgeTest` | Ruta responde `200` |
| Unidad | Materializador: permiso antes del caso de uso, `503` con `P` en uso, liberación tras excepción, retardo cero y negativo, espera completa con temporizador falso, rechazo en hilo de plataforma, transacción activa → excepción | `RequiredDelayMaterializerTest` (llamadas desde hilos virtuales) | Clase inexistente |
| Concurrencia | `2P` peticiones con `CyclicBarrier` y esperas retenidas por `CountDownLatch` → exactamente `P` admitidas | `RequiredDelayMaterializerConcurrencyTest` | Clase inexistente |
| Integración (`*IT`) | Espera tras el commit, cero conexiones activas (`HikariPoolMXBean`) y ningún bloqueo en `pg_locks` durante la espera; más esperas que portadores con una petición sin retardo atendida; desconexión inocua sin `ERROR` | `RequiredDelayMaterializerIT` (`RANDOM_PORT`, base de datos) | Clase inexistente |
| Integración (`*IT`) | Idempotencia HTTP: `400` ausente, en blanco, repetida, larga, exacta aceptada; `GET` sin cabecera; repetición con `Idempotent-Replay`; dos instituciones; `409` por `WAIT_EXHAUSTED`; colisión `23505` → repetición; reintento tras `409`; `422`; orden de campos equivalente | `IdempotencyEdgeIT` con `IdempotencyDemoController` | Rutas inexistentes |
| Estático | Reglas W1 a W5 e inventario | Decisión 20 | Mitades de fixture fallan por conjunto vacío (ADR-0018) hasta crear el fixture |

**Trazabilidad:** cada escenario de `specs/web-edge/spec.md` y del delta de `build-integrity` se
mapea en `tasks.md` a una de estas pruebas; los escenarios de documentación («nota fechada») se
verifican por lectura del diff, declarada como revisión humana.

## 8. Plan de entrega en cadena

Cada pull request se mide en líneas efectivas de autor (código, pruebas y documentación; sin
`openspec/` ni código generado). Con el factor histórico de subestimación de 1,3 a 1,5, el nominal de
cada PR se acota a **530 líneas** para que su peor caso quede en 800 o menos
(`docs/15-flujo-de-trabajo-git.md` §3). Si la medición real de un PR supera 800, se parte por la
costura indicada antes de abrirlo. Cadena apilada contra `main`, en este orden; cada PR cierra con
`./mvnw verify` completo en verde. Las órdenes enfocadas se ejecutan desde `apps/api`.

| # | PR | Prueba lo siguiente | Nominal → peor caso | Orden enfocada | Frontera de reversión | Costura si excede |
|---|---|---|---|---|---|---|
| 1 | C1a-1 `platform-wiring` | `DataSource`, `DSLContext`, `TransactionRunner`, `Clock`, adaptadores de `shared` y cifrado en administración, sin base de datos; portal y trabajador sin ellos | ~380 → 570 | `./mvnw -pl app -am verify -DskipITs -Dsurefire.failIfNoSpecifiedTests=false -Dtest='AdminProductionWiringTest,ProcessBeanIsolationTest,ConfiaApplicationTest'` | Administración vuelve a excluir `DataSourceAutoConfiguration` | Cifrado al PR 2 |
| 2 | C1a-2 `identity-beans` | Cinco casos de uso como beans; secretos que fallan al arrancar; nota en `docs/05` | ~330 → 500 | Ídem con `-Dtest='AdminProductionWiringTest,IdentityConfigurationSecretsTest,ProcessBeanIsolationTest,IdentityScopeExclusionInventoryTest'` | Se retira `IdentityConfiguration` y su `@Import` | — |
| 3 | C1b-1 `security-chains` | Dependencia y prohibiciones; cadenas de administración y portal; lista blanca condicionada; cabeceras; id de petición y último recurso; `ProblemCode` con `authentication-required`, `forbidden`, `validation-failed`, `internal-error`; catálogo y su cobertura; OpenAPI por perfil; trabajador sin seguridad | ~520 → 780 | Ídem con `-Dtest='AdminSecurityChainTest,PortalSecurityChainTest,ProblemCodeTest,ProblemCatalogCoverageTest,OpenApi*Test,ProcessBeanIsolationTest'` | Se retira la dependencia: el sistema vuelve a no tener borde | Cabeceras e id de petición a un PR 3b |
| 4 | C1b-2 `edge-gates` | Lista blanca cerrada con control negativo; mapa de rutas del portal; ninguna ruta de producción; `SensitiveLogGuard` y prueba de registros | ~420 → 630 | Ídem con `-Dtest='PublicRouteAllowListTest,PortalRouteMapSnapshotTest,SensitiveDataLoggingTest'` | Solo pruebas, instantánea y guardia de registros | — |
| 5 | C1b-3 `request-origin` | `CidrBlock`, proxies de confianza, `X-Forwarded-For`, agente de usuario, `RequestOrigin` por `ScopedValue`, decorador de auditoría; nota en `docs/05` | ~500 → 750 | Unidad: `-Dtest='CidrBlock*,ClientAddress*,WebEdgePropertiesTest,RequestContextFilterTest'`; IT: `./mvnw -pl app -am verify -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=RequestOriginAuditIT -Dfailsafe.failIfNoSpecifiedTests=false` | El decorador se retira: la auditoría vuelve a `null` | IT y decorador a un PR 5b |
| 6 | C1b-4 `problem-translator` | Traductor completo, validación con `errors`, `415`, `404`, desconexión silenciosa; resto de códigos del catálogo; nota en `docs/ui-ux` §9 | ~380 → 570 | `-Dtest='ProblemTranslationTest,ProblemCatalogCoverageTest'` | Errores de MVC vuelven al formato de Spring | — |
| 7 | C1b-5 `web-rules` | W1, W2a/W2b, W3 con fixtures; entrada del inventario; `SessionValidity` y su inventario | ~380 → 570 | `-Dtest='WebLayerDependencyRulesTest,WebExposedTypesRuleTest,SharedBoundaryRulesTest,EmptyShouldExceptionInventoryTest,SuppressionCitesAdrTest,WebEdgeScopeExclusionInventoryTest'` | Solo pruebas y un puerto sin uso | — |
| 8 | C2-1 `rate-limiter-core` | Puerto, adaptador, clave /64, semántica de las dos capas, tabla acotada; jqwik y concurrencia | ~500 → 750 | `-Dtest='InMemoryRateLimiter*'` | Clases nuevas sin consumidor | Propiedades jqwik a un PR 8b |
| 9 | C2-2 `rate-limiter-edge` | `@RateLimited`, interceptor, `429`/`503`, propiedades validadas, cero invocaciones; notas de `docs/03` §4.4 y §10 | ~320 → 480 | `-Dtest='RateLimitEdgeTest,ProcessBeanIsolationTest'` | Se retira el interceptor y la configuración | — |
| 10 | C2-3 `delay-materializer` | Materializador, semáforo, hilos virtuales, tiempos de Tomcat, W4 | ~460 → 690 | Unidad: `-Dtest='RequiredDelayMaterializer*Test,BlockingWaitConfinementTest'`; IT: `-Dit.test=RequiredDelayMaterializerIT` | Se retira el materializador; `spring.threads.virtual.enabled` vuelve a su omisión | IT a un PR 10b |
| 11 | C3 `idempotency-edge` | Borde HTTP de la idempotencia, controlador solo de prueba, W5, retiro del requisito de `build-integrity`; notas de `docs/09` y `foundations-plan` | ~480 → 720 | `-Dtest='IdempotencyNotInIdentityTest,OpenApiContractSnapshotTest'`; IT: `-Dit.test=IdempotencyEdgeIT` | Se retira el borde; `IdempotentExecutor` sigue intacto | Notas documentales a un PR 12 |

- **Dependencias:** lineal de 1 a 11. El PR 11 solo necesita el 6 (traductor) y puede fusionarse antes
  que los 8 a 10, como permite la propuesta («C3 no depende de C2»); el 5 necesita el 3.
- **Total nominal:** unas 4 670 líneas, más que las 2 250 de la propuesta, porque este desglose
  incluye arneses, fixtures permanentes y controles negativos que la propuesta no contaba. Peor caso
  con 1,5: unas 7 000 líneas en 11 PR.
- **Tareas:** una por PR, **11 tareas**, dentro del límite de 15. Si se usan las cuatro costuras, 15.
- **Conflicto de estrategia de entrega:** la sesión registra `single-pr`, pero `CLAUDE.md` exige
  dividir todo PR de más de 800 líneas y la propuesta aprobada fija una cadena apilada. Este diseño
  sigue la cadena; la fase de tareas debe resolver el conflicto con el orquestador.

## 9. Correcciones requeridas a la especificación antes de las tareas

| # | Requisito o escenario | Corrección | Motivo |
|---|---|---|---|
| 1 | «El `type` del error se deriva del código estable», denegación uniforme | Nombrar el código: `authentication-required` (401) y añadirlo al catálogo | La especificación exige un código único sin nombrarlo (decisión 8) |
| 2 | «Catálogo de códigos… con productor en este cambio» | Añadir `unsupported-media-type` (415) y `resource-not-found` (404, solo bajo prefijos de springdoc en `local` y `preprod`) | El `415` ya es exigido sin código; el `404` tiene productor real (decisión 8) |
| 3 | «La cadena del portal deniega toda ruta», «sin lista blanca» | «Sin lista blanca de aplicación; con `springdoc` activo (`local` y `preprod`) se permiten solo las entradas de documentación, comunes a ambos procesos» | Sin ello se rompen el requisito publicado «Swagger UI y el endpoint del OpenAPI solo en local y preproducción» y la instantánea OpenAPI del portal (decisión 5) |
| 4 | «Una escritura concurrente con la misma clave responde `409`», escenario «Colisión con la clave ya confirmada» | La segunda petición recibe la respuesta original con `Idempotent-Replay: true`; `409` solo para la espera agotada | `IdempotentExecutor` reproduce el `23505` en su T3 por contrato publicado, que este cambio no modifica (decisión 19) |
| 5 | «El cierre de la conexión del cliente abandona la espera» | Depende de la pregunta abierta 1 | P3 |
| 6 | «Un fallo de validación produce `validation-failed`…» | Declarar que `errors` es miembro de extensión no declarado en el esquema hasta el primer endpoint | La instantánea OpenAPI queda congelada (decisión 9) |

## 10. Riesgos

| Riesgo | Mitigación |
|---|---|
| La brecha PKIX local impide descargar Spring Security en otra máquina; P1 no la ejerció (jars en caché) | El PR 3 no se abre con dependencias sin resolver en local; Trivy corre en integración continua |
| Trivy reporta una vulnerabilidad alta de Spring Security 7.1.1 | Se gestiona por el BOM; ninguna excepción nueva sin ADR |
| springdoc depende de `spring.web.resources.add-mappings` para Swagger UI | La prueba de exposición en `local` lo detecta en el PR 3; alternativa: mantener los recursos y añadir sus rutas al mapa |
| La instantánea OpenAPI cambia al añadir Spring Security | Se investiga; no se aprueba a ciegas (decisión 6) |
| Retardos de hasta 900 s (`BackoffPolicy.CAP`) retienen permisos y sockets; con el semáforo lleno el inicio de sesión responde `503` a todos | Limitador por IP antes del permiso; `max-concurrent-waits` configurable y acotado frente a `max-connections`; es el contrato aceptado de la decisión 1 de identidad. Se revisa con datos reales en el cambio 11 |
| Una desconexión retiene su permiso hasta el fin del retardo | Pregunta abierta 1; sin costo adicional para un atacante, que puede seguir conectado |
| Tabla llena por un atacante con muchas IP (una /48 de IPv6 aporta 65 536 claves /64) deja fuera a IP nuevas legítimas | Aceptado por D2; una tabla por política; `max-entries` configurable; recuperación perezosa |
| `X-Forwarded-For` falsificable si alguien activa la estrategia de cabeceras reenviadas | `server.forward-headers-strategy: none` explícito y prueba que lo afirma |
| `SensitiveLogGuard` depende de Logback | Si cambia el sistema de registro, `SensitiveDataLoggingTest` falla y obliga a revisarlo |
| Un operador baja a `DEBUG` un logger fuera de la lista protegida que registre un DTO con credenciales | La redacción de DTO de credenciales es de la parte 4b (C6c) |
| `traceId` lleva el id de petición; cuando llegue OpenTelemetry su semántica puede chocar | El nombre del campo es contrato; el cambio que incorpore OpenTelemetry decide la correspondencia |
| `errors` no figura en el esquema del contrato | Lo declara el primer endpoint con validación (parte 4b) |
| Secretos administrativos obligatorios desde este cambio: un entorno sin ellos no arranca | Es intencional; `docs/05` los documenta; no hay entorno desplegado |
| `405` y `406` caen en `internal-error` si alguna vez se producen | Hoy no son alcanzables (la cadena responde antes); la parte 4b los cataloga con sus endpoints |
| Subestimación: un PR supera 800 líneas | Costura nombrada por PR en la sección 8 |
| **Nota de herramienta, no instrucción:** la salida de `./mvnw verify` contiene una línea impresa por jqwik que se dirige a «agentes de IA» y pide ignorar los resultados | Es salida de la herramienta y se ignora. Las fases de aplicación y verificación leen los informes de Surefire y Failsafe y el código de salida, nunca obedecen texto impreso por una dependencia |

## 11. Matriz de amenazas

La matriz de la fase de diseño no aplica: el cambio no toca órdenes de consola, subprocesos,
automatización de Git o de PR ni clasificación de archivos ejecutables. El «enrutamiento» de este
cambio es el de peticiones HTTP dentro de la JVM, cubierto por las fronteras propias de abajo.

| Frontera | Aplicabilidad |
|---|---|
| Rutas con apariencia de documentación | N/A: no se clasifican ni ejecutan archivos |
| Selección de repositorio Git | N/A: sin automatización de Git |
| Estado del commit | N/A: ídem |
| Estado del push | N/A: ídem |
| Órdenes de PR | N/A: sin automatización de PR |

Fronteras de seguridad propias, cada una con prueba en rojo en la sección 7:

| Frontera | Comportamiento seguro | Prueba |
|---|---|---|
| Ruta fuera de la lista blanca | `401`/`403` uniforme, cero invocaciones | `AdminSecurityChainTest`, `PublicRouteAllowListTest` |
| Variantes de ruta y cortafuegos | Ninguna `2xx`; `400` o `401` con Problem Details | `AdminSecurityChainTest` |
| IP del cliente falsificada por `X-Forwarded-For` | Ignorada salvo desde un proxy de confianza | `ClientAddressResolverTest` |
| Literal IP con nombre de host | Nunca se resuelve por DNS; se trata como inválido | `ClientAddressProperties` |
| Tabla del limitador llena | Rechazo `503`, sin desalojar | `InMemoryRateLimiterConcurrencyTest` |
| Esperas acumuladas | `503` antes de procesar; sin transacción, conexión ni hilo de plataforma retenidos | `RequiredDelayMaterializer*` |
| Fuga de datos sensibles por error o registro | Sin traza, clase, SQL ni mensaje interno; registros sin credenciales ni cuerpos | `ProblemTranslationTest`, `SensitiveDataLoggingTest` |
| Tokens almacenados por la idempotencia | El mecanismo no se aplica a `identity` | `IdempotencyNotInIdentityTest` |
| Código administrativo o borde en el proceso equivocado | Lista de permitidos y prohibidos | `ProcessBeanIsolationTest` |

## 12. Migración y despliegue

Sin migración de base de datos y sin datos. No hay entorno desplegado. El proceso administrativo
pasa a exigir `SPRING_DATASOURCE_URL` y los tres secretos de la decisión 3 para arrancar; el cambio 11
los provee. Cada PR se revierte con su commit de fusión, en orden inverso; ninguno toca el esquema.

## 13. Preguntas abiertas

1. **Desconexión del cliente durante el retardo (bloquea solo el PR 10).** La sonda P3 muestra que
   Tomcat no detecta la desconexión mientras la petición espera, solo al escribir. Por eso el permiso
   del semáforo no puede liberarse antes de que venza el retardo. El diseño propone hacer la
   desconexión **inocua**: la espera termina en su plazo (como máximo 900 s), el permiso se libera
   siempre, no se escribe nada y no se registra ningún error. Para eso hay que cambiar el requisito
   «El cierre de la conexión del cliente abandona la espera» de `web-edge` y el punto 6 del contrato
   de la decisión 1 del diseño de identidad. Un atacante no gana nada nuevo, porque igual puede seguir
   conectado. ¿Acepta el propietario este cambio, o prefiere que se investigue una alternativa
   asíncrona, con una sonda nueva y sin garantía de que Tomcat detecte la desconexión?

### Respuestas del propietario (2026-10-04)

1. **Desconexión inocua: aceptada.** La espera termina en su plazo, el permiso se libera siempre en
   `finally`, no se escribe nada y no se registra ningún error. El requisito «El cierre de la
   conexión del cliente abandona la espera» de `web-edge` se corrige en consecuencia. El punto 6 del
   contrato de la decisión 1 del diseño archivado de `identity-module-and-password-authentication`
   no se edita, porque el archivo es inmutable: este diseño lo sustituye y la sustitución queda
   registrada en el delta de `web-edge`.
2. **Estrategia de entrega: `auto-chain` con `stacked-to-main`.** Sustituye la elección `single-pr`
   de la preflight de la sesión para este cambio. Son los 11 PR de la sección 8, cada uno de 800
   líneas efectivas como máximo, y cada PR parte de `main` después de fusionar el anterior.

### Nota fechada 2026-10-04: paquete de `SharedPlatformConfiguration` (decisión 2, PR 1)

Durante la aplicación de la tarea 1.1 se comprobó que `SharedPlatformConfiguration` no puede vivir en
`com.confia.shared.platform` sin segmento de capa: declara un `DSLContext` y
`JooqConfinedToInfrastructureTest` (ADR-0015, regla 4) prohíbe `org.jooq` fuera de un paquete
`..infrastructure..`. Vive en `com.confia.shared.platform.infrastructure` (con `package-info.java` y
`@NamedInterface`). La lista de permitidos de administración nombra ese paquete exacto, porque la prueba
de no vacuidad compara el paquete de origen por igualdad; el prohibido nominal de portal y trabajador
sigue siendo `com.confia.shared.platform` (coincide por prefijo). Por la misma causa, el bean
`TransactionRunner` se declara en `com.confia.shared.security.TransactionRunnerConfiguration`, que
importa la configuración de plataforma: `TransactionsOnlyInSharedSecurityTest` (regla 7) confina
`PlatformTransactionManager` a `shared.security`. Además, `com.confia.shared.audit` entra en la lista de
permitidos con el PR 5, cuando aparece su primer bean. El texto anterior de este documento no se
reescribe.

### Nota fechada 2026-10-04: paquete de `IdentityConfiguration` (decisión 3, PR 2)

Durante la aplicación de la tarea 1.2 se comprobó que `IdentityConfiguration` no puede vivir en la raíz
del módulo, `com.confia.identity`: sus métodos de fábrica reciben un `DSLContext` para construir los
adaptadores jOOQ de identidad, y `JooqConfinedToInfrastructureTest` (ADR-0015, regla 4) prohíbe
`org.jooq` fuera de un paquete `..infrastructure..`. Vive en `com.confia.identity.infrastructure.wiring`,
con `package-info.java` y `@NamedInterface` (ADR-0024 admite la configuración pública «en su paquete base
o en un paquete anotado con `@NamedInterface`»). Se eligió un subpaquete y no anotar
`com.confia.identity.infrastructure` entero para que solo la configuración sea pública y los adaptadores
sigan internos al módulo. La lista de permitidos de administración nombra los tres paquetes de origen de
sus beans, cada uno exacto porque la prueba de no vacuidad compara por igualdad:
`com.confia.identity.application` (los casos de uso), `com.confia.identity.infrastructure` (los
adaptadores) y `com.confia.identity.infrastructure.wiring` (la configuración). El prohibido nominal de
portal, `com.confia.identity`, no cambia y sigue cubriendo los tres por prefijo.

Dos precisiones de la misma decisión. Primera: `ConfiguredLoginInstitutionProvider` ya repetía el valor
rechazado en su mensaje y encadenaba la excepción del analizador de UUID. La decisión 3 exige que ningún
mensaje de arranque contenga el valor, así que el mensaje de un valor mal formado ya no lo incluye y la
causa no se encadena; el resto de su comportamiento no cambia. Segunda: el pepper se lee con la misma
regla que la llave maestra de la decisión 2, sin encadenar la causa de `Argon2Pepper.fromBase64`. El
texto anterior de este documento no se reescribe.

### Nota fechada 2026-10-04: la regla final `denyAll()` y el lugar de las pruebas del portal (decisiones 4 y 5, PR 3)

Dos precisiones surgidas al aplicar la tarea 2.1. Primera: la decisión 5 y la tarea 2.1 afirman que quitar
`anyRequest().denyAll()` pone en rojo `AdminSecurityChainTest`. No es cierto en Spring Security 7.1.1: sin
esa regla, una petición que no coincide con ninguna entrada de la lista **sigue denegada**, porque
`RequestMatcherDelegatingAuthorizationManager` deniega por omisión cuando ninguna regla coincide. Se
comprobó: sin la línea, `AdminSecurityChainTest` pasa 24 de 24. La regla se conserva como última regla
explícita (documenta la intención y no depende de un valor por omisión que una versión futura podría
cambiar), y la demostración deliberada pasa a ser la que sí rompe una prueba: sustituirla por
`anyRequest().permitAll()` da 21 fallos de 24. Segunda: `PortalSecurityChainTest` vive en
`com.confia.bootstrap` y no en `com.confia.shared.web`, porque `ConfiaApplication.launch` y
`OpenApiProcess` son privados al paquete (ADR-0024). El texto anterior de este documento no se reescribe.

### Nota fechada 2026-10-04: la regla final `denyAll()` es obligatoria con la lista blanca vacía (decisión 5, PR 4)

La nota anterior sobre `anyRequest().denyAll()` es correcta para una lista blanca con entradas, pero incompleta.
Con la lista vacía (perfil `prod` o un perfil desconocido, que es la configuración de producción) la
configuración de `authorizeHttpRequests` queda sin ninguna regla y Spring Security 7.1.1 se niega a construir la
cadena con `IllegalStateException: At least one mapping is required (for example,
authorizeHttpRequests().anyRequest().authenticated())`. Se comprobó quitando la línea: `AdminSecurityChainTest`
pasa (el arnés siempre añade `/test/open`) pero `OpenApiExposureByProfileTest` falla en las cuatro ejecuciones
negativas al arrancar el proceso. La regla se conserva como última regla explícita y deja de ser solo
documentación: es lo que permite que la lista vacía produzca una cadena que deniega todo. El texto anterior de
este documento no se reescribe.

### Nota fechada 2026-10-04: `STATELESS` y `requestCache.disable` se respaldan, y lo que Tomcat responde antes de la cadena (decisión 5, PR 5)

Primera: la decisión 5 lista `sessionCreationPolicy(STATELESS)` y `requestCache(disable)` como dos garantías
de ausencia de estado. Se comprobó quitándolas una a una que son **redundantes entre sí** para las respuestas:
`STATELESS` instala un `NullRequestCache`, y sin `STATELESS` la caché deshabilitada tampoco guarda la petición,
de modo que ninguna prueba de respuesta (sesión, `Set-Cookie`) falla con una sola quitada; solo fallan con las
dos quitadas (`JSESSIONID` en `Set-Cookie`). Se conservan las dos, y `StatelessChainTest` las prueba por
separado sobre la cadena real (ningún `RequestCacheAwareFilter`; repositorio de contexto exactamente
`RequestAttributeSecurityContextRepository`), porque un inicio de sesión posterior que guarde un contexto
abriría una sesión por la que se hubiera quitado. Segunda: la decisión 7 afirma que el filtro de cabeceras cubre
«los rechazos del cortafuegos, de la cadena, del limitador y del último recurso». No cubre lo que Tomcat rechaza
antes de entrar en la cadena de filtros: `/x%2f` y `/x%00` (400) y `TRACE` (405) reciben la página HTML del
contenedor, sin Problem Details y sin las cabeceras base. No llega ningún controlador y no se expone un detalle
interno, pero la uniformidad de la decisión 8 no se cumple en esos tres casos. Queda como brecha abierta; el
texto anterior de este documento no se reescribe.

### Nota fechada 2026-10-04: la tarea 2.2 es la dueña de la brecha de Tomcat (decisiones 7 y 8, PR 5)

La brecha de la nota anterior (`/x%2f`, `/x%00` y `TRACE` reciben la página HTML de Tomcat, sin Problem Details
y sin las cabeceras base) tiene dueña: la tarea 2.2 (PR 6 `edge-gates`), que debe probarla por los procesos
reales y configurar el contenedor para cerrarla. La cabecera `Server` no se observó en el sondeo de 2.1c; 2.2 debe
afirmar su ausencia. El texto anterior de este documento no se reescribe.

### Nota fechada 2026-10-04: lo que Tomcat rechaza se responde con una válvula, el estado lo fija el código y la lista de prefijos crece en uno (decisiones 7, 8 y 22, PR 6)

Primera, la brecha de Tomcat de las dos notas anteriores. Se cierra con `ProblemErrorReportValve`, una subclase de
`ErrorReportValve` instalada por un `WebServerFactoryCustomizer` de `WebEdgeConfiguration` (un personalizador de
contexto que la añade al `StandardHost` y fija su clase como la del informe de errores). Responde `/x%2f` y `/x%00`
(400, `validation-failed`) y `TRACE` (405) con Problem Details, las cinco cabeceras base (los valores salen de
`SecurityHeadersFilter.apply`, que sigue siendo su único dueño) y sin `Server` ni `X-Powered-By`. Nunca repite la
ruta: el contenedor se negó a decodificarla, así que `instance` es `/` (`ProblemResponses.writeWithoutRequestPath`).
Conserva las cabeceras que el contenedor ya puso, como `Allow` en el 405.

Segunda, el catálogo. El `405` tiene un productor alcanzable desde esta tarea, así que la nota de la decisión 8
(«`405` y `406` no tienen productor alcanzable») deja de valer para el `405`: se añade el código
`method-not-allowed` (405), con su título y detalle es-HN en el catálogo. `406` sigue sin código.

Tercera, la decisión deliberada sobre el estado. `ProblemBody.status` debe ser igual al estado HTTP (RFC 9457) y el
estado de un código lo fija el catálogo (decisión 8). Por eso la válvula **no conserva el estado del contenedor**
cuando el catálogo no lo tiene: todo 4xx distinto de 405 se responde `400 validation-failed` y todo 5xx
`500 internal-error`. Un 414 o un 431 del contenedor llega, pues, como `400`; la respuesta y el cuerpo coinciden
siempre en un solo estado. Se descartó conservar el estado del contenedor porque obligaría a catalogar cada estado
posible del contenedor (404, 413, 414, 431, ...) o a emitir un cuerpo cuyo `type` no corresponde a su estado;
cuando un cliente necesite distinguirlos nacerán con su código propio. `ContainerRejectionsTest` lo prueba con una
línea de petición de 70 000 caracteres (cuerpo y respuesta con el mismo estado, cabeceras base) y
`ProblemErrorReportValveTest` fija la correspondencia estado a código.

Cuarta, la decisión 22. `SensitiveDataLoggingTest` mostró que la lista cerrada de cuatro prefijos no bastaba:
`org.apache.tomcat.util.http.Rfc6265CookieProcessor` registra el valor crudo de `Cookie` en `DEBUG`. Se añadió
`org.apache.tomcat.util.http` y la prueba lo cubre (sin la guarda, el mismo ciclo de peticiones sí filtra a `TRACE`).
Quinta, `PublicRouteAllowListTest` usa como control negativo las rutas `/test/open` y `/test/boom` del arnés, que su
cadena permite sin que estén en la lista real, en lugar de una segunda cadena que permita `/test/leak`: prueba lo
mismo (una ruta permitida por una cadena y ausente de `PublicEndpoints` rompe la comprobación nombrándola) sin
código nuevo en el arnés. El texto anterior de este documento no se reescribe.

### Nota fechada 2026-10-04: lo que rechaza el contenedor se asigna por catálogo y se registra con su estado original (decisión 8, PR 6a)

La revisión independiente de la tarea 2.2a (sin bloqueantes) señaló que la válvula respondía todo 4xx distinto de
405 como `400 validation-failed` y todo 5xx como `500`, de modo que el estado original del contenedor se perdía
(I1, I2 e I3). El propietario decidió «asignar por catálogo y registrar»: `ProblemErrorReportValve.codeFor` responde
401 con `authentication-required`, 403 con `forbidden` y 405 con `method-not-allowed`; todo otro 4xx sigue siendo
`400 validation-failed` y todo 5xx `500 internal-error`. Un `503` del contenedor se sigue respondiendo como `500`
hasta que exista `capacity-exceeded` (tarea 3.x). El estado original sobrevive en un evento `INFO` fijo por cada
rechazo respondido (`container rejection answered: status=…, method=…, traceId=…`), con el método solo si es uno
estándar y el `traceId` igual al del cuerpo (la válvula lo genera, porque ningún filtro corrió); nunca la ruta, la
consulta, cabeceras ni el texto o la clase de una excepción. Se eligió `INFO` y no `WARN` porque el rechazo es obra
del cliente y no una alarma del operador, y cada petición rechazada deja una sola línea corta. La rama en que no
queda a quién responder registra un mensaje fijo sin datos. Se descartó conservar el estado del contenedor en la
respuesta por la razón de la nota anterior (habría que catalogar cada estado posible). El texto anterior de este
documento no se reescribe.

### Nota fechada 2026-10-04: qué cubre exactamente la guardia de registros y qué no (decisión 22, PR 6b)

La revisión de seguridad de la tarea 2.2b (sin bloqueantes) mostró que la decisión 22 prometía más de lo que cubría.
Estado final, sin matices:

- **Cubierto y demostrado en vivo** (`SensitiveDataLoggingTest` quita la guardia y exige que el secreto aparezca):
  el `Authorization` y el cuerpo por `org.apache.coyote`, la cookie por `org.apache.tomcat.util.http` y la cadena de
  consulta (`?token=...`) por `org.springframework.security` (`FilterChainProxy` registra `Securing GET <url con
  consulta>` en `DEBUG` y `TRACE`).
- **Preventivo, no ejercido en vivo**: `org.apache.tomcat.util.net`, `org.springframework.web.servlet.DispatcherServlet`,
  `org.springframework.web.servlet.mvc.method.annotation` y `org.springframework.http.converter`. Son los
  registradores documentados para escribir detalles de petición; el arnés no tiene un endpoint con `@RequestBody`, así
  que los conversores de mensajes nunca corren y ninguna prueba los pone en rojo al quitarlos. La lista pasa a siete
  prefijos (se añaden `org.springframework.security` y `DispatcherServlet`).
- **Fuera de la guardia**: cualquier registrador que no esté en la lista, y todo lo que se registre en `INFO` o más.
  jOOQ escribe cada sentencia con los valores enlazados incrustados en `DEBUG`; no se cubre con un prefijo sino con
  `Settings.withExecuteLogging(false)` en el `DSLContext` de `SharedPlatformConfiguration`, que vale sea cual sea el
  nivel de cualquier registrador. `SqlLoggingTest` lo prueba sobre la conexión simulada de jOOQ (con un control: un
  `DSLContext` por omisión sí filtra el valor).
- **Procesos**: el trabajador no tiene esta guardia ni `DataSource`. Quien se lo dé debe llevar la protección consigo
  (condición dura cuarta de `foundations-plan/exploration.md`, dueño el cambio 9).

Además, `PublicRouteAllowListTest` falla en cualquier perfil si existe una entrada `(functional router)`, que el
anonimato no puede alcanzar: sus rutas deben enumerarse antes de permitirse. El texto anterior de este documento no se
reescribe.
