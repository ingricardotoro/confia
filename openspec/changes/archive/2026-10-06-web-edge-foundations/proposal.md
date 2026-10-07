# Propuesta: fundamentos del borde web del proceso administrativo

- **Cambio:** `web-edge-foundations`
- **Fase del roadmap:** F0, cambio 7, **parte 4a** de la parte 4 (`docs/09-roadmap-y-fases.md` §3,
  entregables 3 y 6). La parte 4 se partió en dos cambios SDD por decisión del propietario del
  2026-10-04 (D1, abajo)
- **Depende de:** `process-entry-point-isolation` (ADR-0024, que nombra a la parte 4 como primer
  consumidor de su regla de registro), `idempotency-key-infrastructure` (cambio 6, que difirió la
  mitad HTTP de la brecha B7), y las tres partes previas del cambio 7:
  `identity-module-and-password-authentication`, `column-encryption-and-mfa-totp` y
  `password-recovery-token`
- **Siguiente cambio de la secuencia:** `session-tokens-and-web-layer` (parte 4b), que hereda las
  condiciones duras H1 a H4 y consume todo lo que este cambio entrega
- **Exploración:** `openspec/changes/web-edge-foundations/exploration.md`, que cubre las dos mitades
  de la parte 4 y registra las cinco decisiones del propietario del 2026-10-04
- **Estado:** aprobada por el propietario del producto el 2026-10-04, con las respuestas a las tres preguntas abiertas registradas al final

## Intención

El backend no tiene borde HTTP. No hay controladores ni filtros de negocio, Spring Security no es
dependencia, ningún proceso registra un `DataSource`, un `TransactionRunner`, un `DSLContext` ni un
`Clock` de producción, y ningún caso de uso de identidad es un bean (exploración, §1). Todo lo que las
tres partes anteriores del cambio 7 entregaron se ejecuta solo desde pruebas.

La parte 4 necesita ese borde antes de emitir un solo token, pero la mitad del borde no tiene nada que
ver con tokens: el cableado de producción, la cadena de seguridad que deniega por defecto, el formato
de error, el catálogo de mensajes, el origen de la petición, el limitador por IP, la materialización
del retardo y la superficie HTTP de la idempotencia. Este cambio entrega esa mitad, **sin ningún
endpoint de negocio**, para que `session-tokens-and-web-layer` construya sesiones sobre piezas ya
probadas y no tenga que resolver en el mismo cambio la infraestructura y el protocolo de
autenticación.

Lo que el cambio deja en pie es verificable sin endpoints reales: cada pieza se demuestra con pruebas
de la cadena de filtros y con controladores que existen **solo en el árbol de pruebas**.

## Alcance

### Dentro de alcance

**C1a — Cableado de producción del proceso administrativo**

1. `DataSource` de producción **solo en el proceso administrativo** (D5). Portal y trabajador
   conservan `@EnableAutoConfiguration(exclude = DataSourceAutoConfiguration.class)`.
2. Beans de producción de `TransactionRunner`, `DSLContext` y `Clock`, más los adaptadores de
   `shared` que consumen los casos de uso de identidad (bitácora de auditoría, almacén de
   idempotencia y los que el diseño identifique como necesarios).
3. Casos de uso de identidad registrados como beans mediante una **configuración pública** del
   módulo, importada con `@Import` en `AdminApplication` y permitida en `ProcessBeanPolicy`, las dos
   ediciones en el mismo pull request (ADR-0024, «Regla para módulos futuros»). El portal sigue
   prohibiendo `com.confia.identity`.
4. Resolución de la sonda 2 (arranque del proceso administrativo sin base de datos; ver «Sondas»).

**C1b — Cadena de seguridad, formato de error y contexto de petición**

5. Spring Security como **cadena de filtros únicamente**, sin el servidor de recursos OAuth2:
   - **Administración:** sin estado, denegar por defecto, lista blanca pública explícita y cerrada,
     cabeceras de seguridad base de `docs/03` §8.2 que correspondan a una API, y el **punto de
     enganche** de la vigencia de sesión como puerto en `shared.security` sin implementación de
     identidad todavía (D4).
   - **Portal:** cadena que **deniega todo**. Es la recomendación de la exploración y la elección de
     esta propuesta, abierta al propietario en la aprobación (pregunta 1).
   - **Trabajador:** ninguna cadena; sigue sin servidor web.
6. Traductor de errores a Problem Details (RFC 9457) con `type` derivado de los códigos estables de
   `DomainException` (ADR-0019), con el subconjunto del catálogo mínimo de la exploración (§3) que
   tenga productor en este cambio: `validation-failed`, `idempotency-key-missing`,
   `idempotency-conflict`, `idempotency-payload-mismatch`, `forbidden`, `too-many-requests`,
   `capacity-exceeded` e `internal-error`. Los códigos de autenticación y de institución llegan con
   su productor en `session-tokens-and-web-layer`.
7. Catálogo de mensajes del backend en español de Honduras (`messages*.properties` o equivalente;
   lo fija el diseño), primer catálogo del sistema.
8. Filtro de contexto de petición: identificador de petición generado **en el servidor**, IP del
   cliente obtenida **solo a través de proxies de confianza** y agente de usuario. Alimenta
   `AuditEntry.sourceIp` y `AuditEntry.userAgent`, hoy siempre `null`, y nunca registra cabeceras de
   autorización ni otros datos de la regla 11.
9. Reglas de ArchUnit de la capa `web` (paquete `web`): dependencias permitidas, ningún DTO que sea
   entidad o registro de jOOQ, y las demás que fije el diseño, cada una con su mitad de fixture
   permanente (ADR-0018).
10. Instantánea del mapa de rutas del portal, pendiente declarado de ADR-0024 (verificación 5) que
    «llega con la primera capa web». Ver pregunta 1.

**C2 — Limitador por IP y materialización del retardo**

11. Puerto `RateLimiter` en `shared.security` y adaptador **en memoria, por proceso**, con tabla
    acotada que **falla cerrado** al llenarse, agrupación de IPv6 por /64 y ejecución **antes** del
    caso de uso (D2). Valores en configuración, no en código (`docs/03` §10). La limitación se
    declara por escrito: el estado es por proceso y se pierde al reiniciar.
12. Respuesta `429` con `Retry-After` en segundos, sin revelar el cupo restante (`docs/03` §10).
13. Materializador de `requiredDelay`: espera en hilo virtual con un `Semaphore` acotado, `503`
    uniforme **antes** de procesar cuando está saturado, retardo que se suma y se abandona al cerrar
    la conexión, sin retener transacción, conexión del grupo, bloqueo ni hilo de plataforma
    (`docs/03` §4.4, nota del 2026-09-24; contrato de seis puntos de la decisión 1 del diseño del
    cambio 1 de identidad). Regla de ArchUnit acotada a su uso.
14. **H1 no se cierra aquí.** Este cambio entrega el control; la condición se cierra en el corte C6a
    de `session-tokens-and-web-layer`, con la prueba de que N fallos desde una IP topan en `429` y
    `identity_login_backoff` deja de crecer.

**C3 — Borde HTTP de la idempotencia**

15. Cabecera `Idempotency-Key` obligatoria con rechazo `400` (`idempotency-key-missing`), cabecera
    `Idempotent-Replay` en la respuesta reproducida, y traducción de las salidas de
    `IdempotentExecutor` a `200`, `409` y `422` (ADR-0010). Cierra la mitad HTTP de la brecha B7.
16. Demostración con un **controlador que vive solo en el árbol de pruebas**. Ningún endpoint de
    identidad usa el mecanismo (D3).

**Especificaciones y documentación**

17. Deltas de especificación (ver «Capacidades»).
18. Notas fechadas, sin reescribir el cuerpo de ninguna sección:
    - `docs/09-roadmap-y-fases.md`: corregir las líneas 114 y 115 (la cabecera `Idempotency-Key`
      **no** se exige en el inicio de sesión ni en ningún endpoint de identidad, D3), el entregable 6
      (líneas 136 a 141: la mitad HTTP la entrega `web-edge-foundations`, no
      `staff-authentication-mfa-sessions`) y registrar la partición de la parte 4.
    - `openspec/changes/foundations-plan/exploration.md`: nota del cuarto corte (D1), y constancia de
      que el propietario aceptó el limitador en memoria como «tope funcional equivalente» de H1
      hasta el cambio 11 (D2). Las condiciones duras H1 a H4 conservan su texto y su dueño.
    - `docs/03-seguridad.md` §4.4 y §10: el limitador en memoria por proceso como control interino,
      con su limitación declarada.

### Fuera de alcance, con dueño nombrado

| Exclusión | Dueño |
|---|---|
| Emisor y verificador de tokens, anillo de claves, token restringido de MFA, verificación de claves en el arranque, JWKS | `session-tokens-and-web-layer` (C4a) |
| Filtro de autenticación, adaptador de `CurrentInstitutionProvider` sobre el token y prueba de ADR-0009 | `session-tokens-and-web-layer` (C4b) |
| Implementación por `identity` del puerto de vigencia de sesión (`sid`) | `session-tokens-and-web-layer` (C5b) |
| Familias de refresco, rotación, gracia, reutilización, inactividad, vigencia absoluta | `session-tokens-and-web-layer` (C5a, C5b) |
| Endpoints de inicio de sesión, MFA, refresco, cierre de sesión, recuperación | `session-tokens-and-web-layer` (C6a a C7b) |
| Aplicación del limitador y del materializador a esos endpoints; cierre de H1, H3 y H4 | `session-tokens-and-web-layer` (C6a, C7b) |
| Revocación de todas las familias al restablecer (H2) e inversión de `IdentityScopeExclusionInventoryTest` | `session-tokens-and-web-layer` (C7a) |
| Propagación del origen de la petición a los comandos de identidad | `session-tokens-and-web-layer`, con sus endpoints |
| CSRF de doble envío, cookie de sesión, redacción de registros de credenciales | `session-tokens-and-web-layer` (C6c) |
| Códigos de Problem Details de autenticación y la distinción `institution-not-found` frente a `institution-inactive` | `session-tokens-and-web-layer`, tras confirmar que la institución viene del token |
| Matriz de roles, cobertura de MFA por ruta, claim de permisos y guarda de arranque de RBAC | Cambio 8, `rbac-permission-matrix-and-audit-integration` (D4) |
| Adaptador de Redis del limitador y contador compartido entre procesos y réplicas | Cambio 11, `containerization-and-cicd-pipeline` |
| `DataSource` del portal y del trabajador | El primer cambio que les dé un consumidor (D5) |
| DTO de instituciones con tope de 200 y 500 caracteres | El primer endpoint de administración de instituciones |
| Firma EdDSA y sonda de Tink | `session-tokens-and-web-layer` |

## Inventario de obligaciones heredadas

Ninguna obligación de la exploración (§2) se omite. Las que no aparecen aquí como cerradas siguen con
su dueño actual.

| Obligación | Fuente | Destino |
|---|---|---|
| Mitad HTTP de la idempotencia (brecha B7) | `docs/09` líneas 136 a 141; `build-integrity` líneas 983 a 1000 | **Se cierra aquí** (C3) |
| `Idempotency-Key` en el inicio de sesión | `docs/09` líneas 114 y 115 | **Se retira** por D3; la corrección documental es de este cambio |
| `DataSource` de producción | `foundations-plan/exploration.md`, parte B | **Se cierra aquí para administración** (C1a); portal y trabajador, con su consumidor |
| Configuración pública + `@Import` + `ProcessBeanPolicy` | ADR-0024 | **Se cierra aquí** (C1a) |
| Instantánea del mapa de rutas del portal | ADR-0024, verificación 5; ADR-0003, verificación 1 | **Se cierra aquí** (C1b), sujeto a la pregunta 1 |
| Quién materializa `requiredDelay` | `docs/09` línea 114; diseño del cambio 1 de identidad, decisión 1 | **Mecanismo aquí** (C2); aplicación a cada endpoint en `session-tokens-and-web-layer` |
| Dimensión por IP de `docs/03` §4.4 | spec de identidad, líneas 474 a 492 | **Control aquí** (C2); aplicación e inversión del escenario en C6a; Redis en el cambio 11 |
| H1: control por IP antes del inicio de sesión | `foundations-plan/exploration.md`, líneas 108 a 138 | `session-tokens-and-web-layer` (C6a), con el control de este cambio |
| H2: revocar todas las familias al restablecer, I24 | `foundations-plan` 146 a 152; spec de identidad 1169 a 1190 | `session-tokens-and-web-layer` (C7a) |
| H3: 10 solicitudes por hora por IP | `foundations-plan` 154 a 157; spec de identidad 1233 a 1254 | `session-tokens-and-web-layer` (C7b), con el control de este cambio |
| H4: `202`, mensaje i18n, `no-referrer`, puerta de tiempo HTTP | `docs/09` 127 y 128; spec de identidad 250 a 258 | `session-tokens-and-web-layer` (C7b); el catálogo de mensajes nace aquí |
| Mecánica de RBAC con guarda de arranque | `foundations-plan` 83 a 91 | **Denegar por defecto y lista blanca aquí** (D4); matriz, MFA por ruta, permisos y guarda de arranque en el cambio 8 |
| Problem Details | `docs/09` 101 a 103 | **Traductor aquí** (C1b); distinción de institución en `session-tokens-and-web-layer` |
| `AuditEntry.sourceIp` y `userAgent` siempre `null` | exploración, §1 | **Origen de la petición aquí** (C1b); propagación a comandos de identidad en `session-tokens-and-web-layer` |
| IP del aviso al titular tras el restablecimiento | spec de identidad, línea 1218 | `session-tokens-and-web-layer` aporta la IP; el envío es del cambio 14 |
| `CurrentInstitutionProvider` sobre el token + prueba de ADR-0009 | `docs/09` 95 a 100 | `session-tokens-and-web-layer` (C4b) |
| MFA a token restringido | spec de identidad, líneas 40 a 75 | `session-tokens-and-web-layer` (C6b) |
| Restablecimiento administrativo | spec de identidad, líneas 1276 a 1296 | Cambio 8 y `session-tokens-and-web-layer` |
| Controles de sesión de `docs/03` §4.5 y ADR-0005 | exploración, §2 | `session-tokens-and-web-layer` |
| DTO de instituciones de 200 y 500 caracteres | `docs/09` 99 a 101 | Primer endpoint de administración de instituciones |
| Purga de `identity_login_backoff` | `foundations-plan` 134 a 138 | Sin cambio: dueño por asignar antes de F1 |
| Decisiones abiertas 1, 2, 7, 9 a 13 de la exploración | exploración, §4 | `session-tokens-and-web-layer` |

## Capacidades

> Contrato con `/sdd-spec`. Investigado contra `openspec/specs/` (nueve capacidades publicadas).
> Ninguna capacidad publicada describe hoy el borde HTTP.

### Nuevas

- **`web-edge`** (nombre de trabajo; la fase de especificación puede ajustarlo): cadena de seguridad
  que deniega por defecto con lista blanca pública, cadena del portal, formato Problem Details y su
  derivación de códigos, catálogo de mensajes es-HN, contexto de petición y proxies de confianza,
  limitador por IP con `429` y `Retry-After`, falla cerrada y agrupación /64, materialización del
  retardo con `503` por saturación, y superficie HTTP de la idempotencia. Incluye los requisitos de
  ausencia con destino nombrado de la tabla «Fuera de alcance» que afecten al borde.

### Modificadas

- **`build-integrity`:**
  - el requisito «Ausencia de superficie HTTP que exija la cabecera de idempotencia (brecha con
    destino: cambio 7)» se **retira** o se sustituye por su cumplimiento, y su escenario deja de ser
    cierto como él mismo anuncia;
  - los requisitos de aislamiento por proceso (líneas 1443 a 1532) se modifican para que el proceso
    administrativo permita los beans de identidad y de `shared` registrados, y el portal siga
    prohibiendo `com.confia.identity`;
  - reglas nuevas de ArchUnit de la capa `web` y del materializador;
  - la instantánea del mapa de rutas del portal, si se confirma la pregunta 1.
- **`audit-trail`:** solo si su especificación fija el origen de la petición. La fase de
  especificación lo confirma; si no hay requisito publicado que cambie, no hay delta.
- **`identity`:** no se espera delta. Los requisitos de ausencia de las líneas 474 a 492 y 1233 a
  1254 siguen siendo ciertos, porque el control existe pero ningún endpoint de identidad lo aplica
  todavía. Su inversión es de `session-tokens-and-web-layer`.

## Enfoque

1. **Registro explícito, sin escaneo.** Cada pieza nueva entra por la configuración pública de su
   módulo y un `@Import` en `AdminApplication` o `PortalApplication`, con su edición de
   `ProcessBeanPolicy` en el mismo pull request (ADR-0024). `ProcessBeanIsolationTest` sigue fallando
   cerrado.
2. **Spring Security solo como cadena de filtros.** No se incorpora
   `spring-boot-starter-oauth2-resource-server`: no firma EdDSA (exploración, §1 y §3), y la decisión
   de firma es de la parte 4b. La lista blanca pública es una constante cerrada y probada; toda ruta
   fuera de ella responde `401` o `403` con Problem Details.
3. **`shared` no depende de `identity`.** La vigencia de sesión es un puerto en `shared.security` sin
   implementación de producción en este cambio, con el mismo precedente de «puerto sin adaptador» de
   las partes anteriores y una prueba de inventario que lo declara.
4. **IP de confianza.** La IP del cliente se toma de la dirección remota, salvo que esa dirección
   pertenezca a la lista de proxies de confianza; solo entonces se lee `X-Forwarded-For`, de derecha
   a izquierda. Un `X-Forwarded-For` presentado por un origen no confiable se ignora, porque anularía
   H1. El origen de la lista es la pregunta 2.
5. **Limitador antes del caso de uso.** Se ejecuta en el borde, antes de abrir transacción y antes de
   pagar Argon2id. Su tabla acotada falla cerrado. PostgreSQL se descarta como almacén porque recrea
   el vector de crecimiento de H1 (exploración, §3). El adaptador de Redis del cambio 11 implementa el
   mismo puerto. El diseño evalúa elevar la decisión interina a ADR.
6. **Retardo fuera de la transacción.** El caso de uso decide y confirma; el borde materializa
   `requiredDelay` después, en un hilo virtual y bajo un semáforo acotado. Ninguna espera ocurre en
   `application` ni en `domain` (`NoBlockingWaitInIdentityTest` se mantiene).
7. **Idempotencia sin almacenar secretos.** El borde HTTP envuelve `IdempotentExecutor` sin
   modificar su contrato; ningún endpoint que devuelva credenciales lo usa (D3).
8. **Pruebas.** TDD estricto con `./mvnw verify` en `apps/api`. Pruebas de la cadena con
   controladores del árbol de pruebas; reglas de ArchUnit con fixture permanente; concurrencia del
   limitador y del semáforo con `CyclicBarrier` o `CountDownLatch`, nunca con esperas por reloj;
   reloj inyectado para ventanas y `Retry-After`.

## Sondas requeridas antes del diseño o dentro de él

| Sonda | Qué demuestra | Si falla |
|---|---|---|
| **1. Convergencia de Spring Security** | Que `spring-boot-starter-security` (gestionado por el BOM de Spring Boot 4.1) pasa `dependencyConvergence` y el escaneo de Trivy, y que sus artefactos se pueden descargar pese a la brecha PKIX de Windows (`apps/api/pom.xml`, líneas 130 a 135) | Resolver la confianza del almacén de certificados local antes de C1b, o cablear la cadena en una máquina sin la brecha; ningún pull request de C1b se abre con dependencias sin resolver localmente |
| **2. Arranque administrativo sin base de datos** | Que, con el `DataSource` de producción registrado, el contexto administrativo arranca sin conexión: Hikari sin validación inicial bloqueante y **sin migración en el arranque**. `spring-boot-flyway`, que aporta `FlywayAutoConfiguration` desde Spring Boot 4, está en el classpath de ejecución junto con `flyway-core` (`apps/api/app/pom.xml`), así que registrar un `DataSource` la activa y migraría al arrancar. Se comprueba también qué piden los beans de identidad en el arranque, por ejemplo la llave maestra de `ColumnEncryptionService` | **Preferencia de esta propuesta:** las pruebas `*Test` de arranque del proceso administrativo siguen sin Docker. **Alternativa:** pasan a `*IT` con Testcontainers, con su costo dentro del presupuesto de 8 minutos de `build-integrity` |
| **3. Sonda S5: hilos virtuales bajo Tomcat** | Que la espera del materializador en hilo virtual no retiene hilos de plataforma de Tomcat, que el semáforo acota los sockets retenidos y que el cierre de la conexión abandona la espera | Rediseñar el materializador antes de C2; C1a, C1b y C3 no dependen de esta sonda |

La sonda de Tink y la de la firma EdDSA son de `session-tokens-and-web-layer`.

## Decisiones del propietario (2026-10-04, vinculantes)

- **D1.** La parte 4 se parte en `web-edge-foundations` (C1a, C1b, C2, C3) y
  `session-tokens-and-web-layer` (C4a a C8).
- **D2.** Limitador por IP en memoria por proceso, falla cerrado cuando su tabla acotada se llena,
  IPv6 agrupado por /64, ejecutado antes del caso de uso. Aceptado por escrito como el control de H1
  hasta que el cambio 11 traiga Redis; su limitación se declara.
- **D3.** `Idempotency-Key` no se aplica a los endpoints de identidad. El borde HTTP genérico sí se
  construye y se prueba con un controlador solo de prueba. `docs/09` se corrige.
- **D4.** RBAC aquí: solo denegar por defecto con lista blanca pública explícita y el punto de
  enganche de la vigencia de sesión. Matriz de roles, cobertura de MFA por ruta y claim de permisos al
  cambio 8.
- **D5.** `DataSource` de producción solo en el proceso administrativo. Portal y trabajador siguen
  excluyendo `DataSourceAutoConfiguration`.

## Áreas afectadas

| Área | Cambio |
|---|---|
| `apps/api/app/pom.xml` | Dependencia de Spring Security (sonda 1) |
| `com.confia.bootstrap.admin`, `com.confia.bootstrap.portal` | `@Import` de las configuraciones públicas; `DataSource` solo en administración |
| `com.confia.identity` | Configuración pública que declara los casos de uso como beans |
| `com.confia.shared.security` | Puertos `RateLimiter` y de vigencia de sesión; adaptador en memoria; configuración de producción de `TransactionRunner` y afines |
| `com.confia.shared.web` | Cadenas de filtros, traductor Problem Details, filtro de contexto de petición, materializador del retardo, borde de idempotencia |
| `apps/api/app/src/main/resources` | Catálogo de mensajes es-HN; propiedades de límites y de proxies de confianza |
| Pruebas | `ProcessBeanPolicy`, `ProcessBeanIsolationTest`, `OpenApiContractSnapshotTest`, reglas nuevas de ArchUnit, controladores del árbol de pruebas, pruebas de la cadena, del limitador y del materializador |
| `openspec/changes/web-edge-foundations/specs/` | `web-edge` nueva; deltas de `build-integrity` y, si aplica, `audit-trail` |
| Documentación | `docs/09` §3 (líneas 114-115 y entregable 6), `docs/03` §4.4 y §10, `foundations-plan/exploration.md` |

## Criterios de aceptación

- [ ] `./mvnw verify` en `apps/api` termina en verde, con la puerta de mutación incluida.
- [ ] El proceso administrativo registra el `DataSource`, `TransactionRunner`, `DSLContext`, `Clock`
      y los casos de uso de identidad; `ProcessBeanIsolationTest` los permite en administración y
      sigue prohibiendo `com.confia.identity` en el portal.
- [ ] Portal y trabajador siguen sin `DataSource`, y sus pruebas `*Test` de arranque siguen sin
      Docker. Las del proceso administrativo cumplen la decisión de la sonda 2.
- [ ] Una petición a cualquier ruta de administración fuera de la lista blanca recibe `401` o `403`
      con Problem Details; una prueba falla si se añade una ruta pública sin editar la lista.
- [ ] El portal cumple la elección aprobada en la pregunta 1, y su instantánea de rutas existe si se
      confirma.
- [ ] Toda respuesta de error tiene `Content-Type: application/problem+json`, un `type` derivado de
      un código estable de `DomainException` y un `detail` del catálogo es-HN; ninguna expone traza,
      clase ni mensaje interno.
- [ ] El identificador de petición lo genera el servidor aunque el cliente envíe uno. Un
      `X-Forwarded-For` desde un origen no confiable no altera la IP resuelta; desde un proxy de
      confianza sí.
- [ ] Un asiento de auditoría escrito durante una petición servida por la cadena lleva `sourceIp` y
      `userAgent`.
- [ ] El limitador devuelve `429` con `Retry-After` al exceder el límite, agrupa dos IPv6 del mismo
      /64 en un solo contador, y con la tabla llena **rechaza** toda IP nueva en lugar de admitirla.
- [ ] El materializador aplica el retardo después de confirmar la transacción, responde `503`
      uniforme cuando el semáforo está agotado, y abandona la espera al cerrar el cliente la
      conexión.
- [ ] El controlador de prueba demuestra `400` sin `Idempotency-Key`, `Idempotent-Replay` en la
      respuesta reproducida, `409` ante escritura concurrente y `422` ante carga útil distinta.
- [ ] Ninguna clase de producción en un paquete `web` aplica `Idempotency-Key` a un endpoint de
      identidad.
- [ ] Ningún registro contiene cabeceras de autorización, cookies ni cuerpos de petición,
      verificado con control negativo.
- [ ] Las notas fechadas de `docs/09`, `docs/03` y `foundations-plan/exploration.md` existen, y
      ninguna reescribe el cuerpo de su sección.
- [ ] La cobertura global de `app` sigue en 80 % y la del paquete `domain` de cada módulo en 95 %.

## Riesgos

| Riesgo | Probabilidad | Mitigación |
|---|---|---|
| La brecha PKIX local impide descargar Spring Security | Alta | Sonda 1 antes de C1b; C1a no añade dependencias |
| Spring Security rompe `dependencyConvergence` o trae una vulnerabilidad alta | Media | Sonda 1; gestión por el BOM de Spring Boot |
| Registrar el `DataSource` activa Flyway y la conexión inicial en el arranque y rompe las pruebas sin Docker | Alta si no se diseña | Sonda 2, con preferencia y alternativa declaradas |
| `X-Forwarded-For` falsificable anula el limitador y, con él, H1 | Alta si no se diseña | Proxies de confianza (pregunta 2) y prueba negativa |
| Limitador en memoria: por proceso, se pierde al reiniciar, no compartido entre réplicas | Cierta, aceptada por D2 | Declarada en `docs/03`; Redis en el cambio 11 |
| Falla cerrada: un atacante con muchas IP llena la tabla y deja fuera a usuarios legítimos | Media | Aceptada por D2; tamaño de tabla y expiración en configuración; agrupación /64 |
| Las respuestas retardadas retienen sockets | Media | Semáforo acotado, `503` por saturación, sonda 3 |
| Cadena de seguridad demasiado abierta por una regla mal ordenada | Media | Denegar por defecto como última regla y prueba que enumera todas las rutas registradas |
| Subestimación histórica de 1,3 a 1,5 veces: C1b y C2 superarían las 800 líneas | Alta | C1b se parte en dos pull requests; C2 puede partirse en limitador y materializador |
| Supuesto de mismo origen entre API y SPA sin verificar | Baja aquí | Afecta a cookie y CSRF, que son de la parte 4b |

## Plan de reversión

No existe ningún entorno desplegado ni dato real.

1. Cada pull request se revierte con su commit de fusión, en orden inverso.
2. El cambio no añade migraciones: revertirlo no toca el esquema.
3. Revertir C1a devuelve el proceso administrativo a excluir `DataSourceAutoConfiguration`; ningún
   caso de uso de identidad cambia de comportamiento, porque solo se registran como beans.
4. Las notas fechadas de documentación no arrastran código.
5. `session-tokens-and-web-layer` no ha empezado, así que nada depende todavía de este cambio.

## Dependencias

- **Consume:** `TransactionRunner`, `IdempotentExecutor`, `AuditLogWriter`, `DomainException` y sus
  códigos estables (ADR-0019), `ProcessBeanPolicy` y `ProcessBeanIsolationTest` (ADR-0024), y los
  casos de uso de identidad de las partes 1 a 3.
- **Entrega a `session-tokens-and-web-layer`:** la cadena con su lista blanca, el puerto de vigencia
  de sesión, el traductor y el catálogo, el origen de la petición, el limitador y el materializador.
  Las condiciones duras H1 a H4 siguen siendo suyas.
- **Deja al cambio 8:** matriz de roles, cobertura de MFA por ruta, claim de permisos y guarda de
  arranque de RBAC.
- **Deja al cambio 11:** el adaptador de Redis del limitador.
- **Herramienta:** la sonda 1 depende de poder descargar dependencias nuevas en la máquina local.

## Tamaño y orden de entrega

Cadena apilada contra `main`, cada pull request por debajo de 800 líneas efectivas
(`docs/15-flujo-de-trabajo-git.md` §3).

| Orden | Pull request | Líneas nominales | Depende de |
|---|---|---|---|
| 1 | C1a: cableado de producción del proceso administrativo | ~450 | Sonda 2 |
| 2 | C1b-1: Spring Security, cadenas de administración y portal, contexto de petición, reglas de la capa `web` | ~400 | Sonda 1, PR 1 |
| 3 | C1b-2: Problem Details, catálogo es-HN, instantánea de rutas del portal | ~300 | PR 2 |
| 4 | C2: limitador, `429`, materializador del retardo | ~650 | Sonda 3, PR 3 |
| 5 | C3: borde HTTP de la idempotencia y notas de documentación | ~450 | PR 3 |

Total nominal de unas 2 250 líneas; con el factor histórico, de 2 900 a 3 400. Si C2 supera el
presupuesto, se parte en limitador y materializador (seis pull requests). C3 no depende de C2 y puede
fusionarse antes. Se estiman **13 a 15 tareas**, en el límite de quince de
`openspec/changes/README.md`.

## Preguntas abiertas para el propietario

Solo las que afectan a este cambio. Esta propuesta no las decide.

1. **Cadena del portal.** ¿Se entrega ahora una cadena de Spring Security que deniega todo en el
   portal, con la instantánea de su mapa de rutas (recomendación de la exploración y elección de esta
   propuesta), o el portal queda sin cadena hasta que tenga su primera ruta?
2. **Origen de la lista de proxies de confianza.** ¿De dónde sale la lista de proxies cuyo
   `X-Forwarded-For` se acepta (variable de entorno por proceso, propiedad de configuración por
   entorno u otra fuente), y cuál es su valor por defecto cuando no está definida?
3. **Valores del limitador donde `docs/03` §4.4 y §10 difieren.** Para el inicio de sesión
   administrativo, §4.4 limita a 1 intento por minuto a partir del **intento fallido** 10 en 10
   minutos, con tope de 1 hora, y §10 fija 10 **peticiones** por minuto por IP. ¿Rige una, la otra o
   ambas en capas? La respuesta decide si el puerto `RateLimiter` de este cambio cuenta peticiones,
   fallos o los dos.

### Respuestas del propietario (2026-10-04)

1. **Cadena del portal: sí.** El portal recibe ahora una cadena de Spring Security que deniega toda
   ruta, con la instantánea de su mapa de rutas (verificación 5 de ADR-0024).
2. **Proxies de confianza: propiedad de configuración por entorno, vacía por defecto.** Se puede
   inyectar por variable de entorno. Con la lista vacía se ignora `X-Forwarded-For` y se usa la IP
   de la conexión, de modo que ningún cliente puede fijar su propia IP mientras el proxy no esté
   configurado. El cambio 11 la llena con nginx y los rangos de Cloudflare.
3. **Límites del inicio de sesión administrativo: ambos, en capas.** Capa 1 (§10): 10 peticiones por
   minuto por IP. Capa 2 (§4.4): desde el fallo 10 en 10 minutos, 1 intento por minuto para esa IP,
   con tope de 1 hora. El puerto `RateLimiter` cuenta peticiones y fallos como dimensiones separadas.
   `docs/03` no necesita corrección en este punto, porque sus dos textos ya describen las dos capas.
