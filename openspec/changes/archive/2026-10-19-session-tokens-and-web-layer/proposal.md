# Propuesta: núcleo de tokens, familias de refresco y revocación al restablecer (parte 4b, S1)

- **Cambio:** `session-tokens-and-web-layer`
- **Fase del roadmap:** F0, cambio 7, parte 4b, **primer cambio (S1) de tres** por decisión del
  propietario DA-1 del 2026-10-06. Cubre los cortes C4a, C4b, C5a, C5b y C7a de la exploración.
- **Depende de:** `web-edge-foundations` (parte 4a, archivada el 2026-10-06), `password-recovery-token`,
  `column-encryption-and-mfa-totp` e `identity-module-and-password-authentication`.
- **Exploración:** `openspec/changes/session-tokens-and-web-layer/exploration.md` (Engram #578). Las
  decisiones DA-1 a DA-6 de su sección 9 son vinculantes y esta propuesta no las reabre.
- **Línea base:** `main` en `2a599f5`.
- **Estado:** aprobada por el propietario el 2026-10-06, con las once recomendaciones de las preguntas
  abiertas adoptadas tal cual (sección «Decisiones del propietario» al final).

## Intención

La parte 4a dejó el borde web listo, pero el proceso administrativo todavía no puede reconocer a nadie:
no existe emisor ni verificador de tokens, ni anillo de claves, ni filtro de autenticación, ni almacén
de sesiones, ni adaptador de `CurrentInstitutionProvider` ni de `SessionValidity`. Mientras eso falte,
ningún endpoint de identidad puede existir.

S1 entrega **todo el mecanismo de sesión sin ningún endpoint que emita credenciales a un cliente**:
el JWS Ed25519 propio (DA-2), el anillo de claves con verificación de arranque, el token restringido
de MFA, el filtro de autenticación con su prueba de ADR-0009, la migración V8 de familias de refresco,
la emisión y la rotación con ventana de gracia y detección de reutilización, y la revocación de todas
las familias al restablecer la contraseña. Así S2 construye los endpoints de sesión sobre piezas ya
probadas, y la condición dura H2 queda cumplida **antes** de que exista un camino HTTP que entregue un
refresco, que es la lectura más estricta de su texto (exploración §2, H2).

**Por qué ahora:** es el orden fijado por DA-1 y DA-6 (S1, S2, cambio 9, S3), y S2 no puede empezar sin
el núcleo de tokens ni sin la revocación de H2.

**Cómo se ve el éxito:** un token emitido por el núcleo (en pruebas) autentica una ruta real del proceso
administrativo; un token del dominio equivocado, alterado, vencido o restringido no la autentica; la
institución solo sale del token; la rotación, la gracia y la reutilización se comportan como fija
ADR-0005 bajo dos transacciones reales; y un restablecimiento revoca todas las familias en la misma
transacción (escenario I24).

## Plan de tres cambios (DA-1 y DA-6)

| Orden | Cambio | Alcance | Depende de | Condiciones duras que cierra |
|---|---|---|---|---|
| 1 | **S1 `session-tokens-and-web-layer`** (este) | C4a núcleo de token y token restringido; C4b filtro, principal, `CurrentInstitutionProvider` y ruta de prueba de la autenticación; C5a V8 y emisión; C5b rotación, gracia, reutilización, vigencias y `SessionValidity`; C7a revocación al restablecer | `web-edge-foundations` | **H2** |
| 2 | **S2 `session-endpoints-cookie-and-csrf`** (identificador propuesto) | C6a inicio de sesión con limitador y materializador; C6b verificación e inscripción de MFA; C6c refresco, cierre de sesión, cookie `__Secure-confia_admin_sid`, CSRF de doble envío con `Origin`, redacción de credenciales (O9); instantánea OpenAPI de los endpoints públicos; DA-8, DA-9, DA-13, DA-14, DA-18 (i); O2, O6, O10 | S1 | **H1** y **condición 3** |
| 3 | `background-jobs-with-db-scheduler` (cambio 9) | Ejecutor de db-scheduler en el trabajador, adaptador de programación de la emisión de recuperación, purgas; condición 4 | S2 en el orden acordado (DA-6) | Condición 4 (propia) |
| 4 | **S3 `password-recovery-endpoints`** (identificador propuesto) | C7b `reset/request` y `reset/confirm`, `202` uniforme, `no-referrer`, 10 por hora por IP, puerta de tiempo HTTP; C8 cierre documental de la parte 4b | S1, S2, cambio 9 | **H3** y **H4** |

Cada uno se verifica y se archiva por separado. Los identificadores de S2 y S3 son propuestos; el
propietario puede cambiarlos al aprobar.

### Transferencia de las condiciones duras que S1 no cierra

H1, H3, H4 y la condición 3 nombran como dueño a `session-tokens-and-web-layer`. S1 **no las cierra y
no reescribe su texto**. La transferencia se registra en la tarea documental de S1:

1. **`openspec/changes/foundations-plan/exploration.md`:** nota fechada («quinto corte», 2026-10-06,
   DA-1) que registra la partición y declara que H1 y la condición 3 obligan a S2, y H3 y H4 a S3, como
   herederos del identificador; que H2 se cierra en S1; y que el archivo de S1 no puede declarar
   cerrada ninguna de las otras cuatro.
2. **Especificación `identity`:** delta que actualiza el destino de las brechas «Ausencia de la
   dimensión por dirección IP del retroceso» (a S2), «Ausencia del límite por dirección IP y de la
   respuesta HTTP de la recuperación» (a S3) y «Ausencia del restablecimiento administrativo» (a
   cambio 8 y S3), conservando la condición.
3. **Especificación `web-edge`:** el requisito de ausencia de autenticación se estrecha y nombra a S2 y
   S3 como destinos de lo que sigue ausente.
4. **Informe de archivo de S1:** lista las cuatro condiciones como abiertas y transferidas, con su
   nuevo dueño.

## Alcance

### Dentro de alcance

**C4a — Núcleo de token (DA-2)**

1. JWS compacto mínimo propio sobre `java.security.Signature("Ed25519")` del JDK 25, sin dependencias
   nuevas: `alg` fijo `EdDSA`, `kid` obligatorio, cualquier otra cabecera rechazada (incluida `crit`),
   base64url estricto sin relleno, comparación de firma por el JDK y vectores de RFC 8037. Pruebas de
   confusión de algoritmo (`none`, `HS256` con la clave pública, `alg` ausente o duplicado).
2. Anillo de claves con `kid` y hasta dos claves activas (rotación de `docs/03` §4.5), cargadas desde
   variables de entorno propias del dominio administrativo. **Verificación de arranque (O12):** el
   proceso administrativo aborta si falta su clave; los procesos del portal y del trabajador abortan si
   encuentran la clave privada administrativa (sujeto a la pregunta 9).
3. Token de acceso de 10 minutos con `iss`, `aud`, `sub` opaco, `exp`, `iat`, `jti`, `sid`, `tenant` y
   `amr`; sin ningún dato personal. Pruebas 3 y 4 de ADR-0005 (`exp - iat = 600`, `aud`; rechazo por
   firma de un token firmado con una clave ajena).
4. Token restringido de MFA: vida corta (unos 5 minutos, valor exacto en el diseño), claim de uso
   (`mfa-verify` o `mfa-enroll`), `amr=[pwd]`, sin familia ni refresco. S1 lo emite y lo verifica; las
   rutas que lo aceptan son de S2, así que en S1 el filtro lo rechaza en toda ruta.
5. Regla de ArchUnit de ADR-0005, verificación 13: ninguna clase fuera de `identity` y
   `shared.security` usa utilidades de firma ni de hash, con su fixture permanente de dos mitades.

**C4b — Filtro de autenticación y principal**

6. Filtro de `Authorization: Bearer` **solo en `AdminSecurityConfiguration`** (DA-3: el acceso viaja como
   `Bearer`). Ningún controlador interpreta tokens. El portal sigue denegando todo.
7. Principal autenticado (`AuthenticatedActor` o el nombre que fije el diseño) en `shared.security`, sin
   dependencia de `identity` (W3).
8. Autorización de rutas autenticadas sin permiso de rol (DA-7, pregunta 2), antes de la regla final
   `denyAll()`.
9. Códigos de Problem Details del filtro, con su entrada en el catálogo es-HN: `token-invalid` y
   `token-expired` (este último solo para un token íntegro y vencido), y la cabecera `WWW-Authenticate`
   en todo `401` (O5). `authentication-required` sigue siendo la respuesta sin credencial. Los códigos
   de institución quedan para la primera ruta que resuelva la institución contra `organization`.
10. Adaptador de `CurrentInstitutionProvider` sobre el principal, y la prueba de ADR-0009 (O7): ninguna
    cabecera, parámetro de consulta ni cuerpo altera la institución resuelta.
11. **Ruta de prueba de la autenticación** (pregunta 1): recomendación, `GET` de la sesión actual en
    producción, que devuelve un DTO con identificadores opacos, `amr`, institución y vencimientos, sin
    datos personales.

**C5a — Almacén de sesiones y emisión**

12. Migración `V8` con `identity_refresh_token_family` e `identity_refresh_token` (prefijo de ADR-0015,
    esquema conceptual de ADR-0005): `institution_id NOT NULL`, `ENABLE` y `FORCE ROW LEVEL SECURITY`
    con su política en la misma migración, `REVOKE ALL` de partida, matriz de privilegios explícita
    por columna, **ningún `DELETE`** para ningún rol de aplicación (la purga es del cambio 9),
    `last_used_at` y `absolute_expires_at` (DA-11), huella de agente de usuario e IP (DA-12) y la
    columna del sucesor cifrado para la ventana de gracia (DA-5), ligada a su fila. Regeneración de
    jOOQ.
13. Pruebas de integración de V8 con el rol restringido real: aislamiento entre instituciones, falla
    cerrada sin contexto, rechazo de `DELETE` y de `UPDATE` fuera de las columnas concedidas.
14. Emisión de una familia: refresco opaco de 32 bytes, almacenado solo como SHA-256, y auditoría de la
    emisión en la misma transacción. En S1 la invoca solo el código de pruebas; el inicio de sesión que
    la usa es de S2.

**C5b — Rotación y vigencia**

15. Rotación con `UPDATE` condicional (`consumed_at IS NULL`) y bloqueo de la fila de la familia, de modo
    que dos refrescos simultáneos del mismo token se serializan.
16. Ventana de gracia de 10 segundos desde el mismo dispositivo (huella de agente de usuario): devuelve
    el sucesor descifrado con `ColumnEncryptionService` (DA-5). El sucesor cifrado se vacía según la
    pregunta 6.
17. Detección de reutilización fuera de la gracia: revoca la familia con `reuse_detected` y audita, y
    **ese efecto se confirma aunque la petición se rechace** (el caso de uso devuelve un desenlace de
    rechazo; no lanza una excepción que revierta la revocación). El aviso al titular es del cambio 14.
18. Vigencias: inactividad y absoluta, con la lectura que fije la pregunta 4.
19. Adaptador de `SessionValidity` (O8) en `identity` y comprobación de `sid` según la pregunta 10.

**C7a — Revocación al restablecer (H2)**

20. Puerto nuevo en `identity.application` para revocar todas las familias vivas de una cuenta, con
    adaptador jOOQ sobre V8, inyectado en `ResetPasswordWithToken` (cambia su constructor y todas sus
    pruebas) y ejecutado **en la misma transacción** que el consumo del token y el cambio de hash, con
    su asiento de auditoría.
21. Prueba de integración I24 con PostgreSQL real: un restablecimiento revoca todas las familias de la
    cuenta y ninguna de otra cuenta; si la transacción se revierte, ninguna queda revocada.
22. Inversión de `IdentityScopeExclusionInventoryTest.noClassOfIdentityReferencesSessionsOrRefreshTokens`.
23. **Orden dentro de la cadena:** el PR de H2 se fusiona después de V8 y **antes** del PR de emisión,
    de modo que ningún commit de `main` emite refrescos sin la revocación (texto estricto del
    requisito de identidad).

**Transversal**

24. Inversiones de O3 que S1 rompe: escenarios «Ningún componente de tokens ni de sesión» y, si se
    acepta la pregunta 1, «Ninguna ruta de producción» del requisito de ausencia de `web-edge`; el
    requisito «La vigencia de sesión es un puerto sin implementación todavía»; la entrada «ninguna
    clase implementa `SessionValidity`» de `WebEdgeScopeExclusionInventoryTest`; la entrada W2a de
    `EmptyShouldExceptionInventoryTest` (con el primer controlador); la parte de
    `ProblemCatalogCoverageTest` que niega `token-invalid` y `token-expired`; y `PublicRouteAllowListTest`
    junto con el enumerador de rutas, para que distinga rutas públicas y autenticadas. Quedan para S2:
    cookie, limitador y materializador sin uso, `authentication-failed` y los códigos de institución.
25. Primera instantánea OpenAPI con una operación de producción, si se acepta la pregunta 1: decisión
    de `springdoc.override-with-generic-response`, miembro `errors` del esquema `ProblemDetail` y
    regeneración de `packages/contracts` (O4, solo para esa ruta).
26. Corrección mínima de `ProblemExceptionHandler.unexpected` (O1), si se acepta la pregunta 3.
27. Configuración pública de `identity` y líneas de `ProcessBeanPolicy` para los beans nuevos, en el
    mismo PR (ADR-0024).
28. Documentación con notas fechadas, sin reescribir cuerpos: transferencia de condiciones (arriba);
    `docs/03` §4.5 (JWS propio sobre el JDK, claves por variable de entorno, JWKS diferido si así se
    decide); `docs/05` (variables de las claves de firma); ADR-0005 solo con nota si el diseño fija una
    lectura de las vigencias distinta de su texto.

### Fuera de alcance, con dueño nombrado

| Exclusión | Dueño |
|---|---|
| Endpoints de inicio de sesión, MFA, refresco, cierre de sesión y cierre de todas | S2 |
| Cookie de refresco, CSRF de doble envío con `Origin` (condición 3), corrección del `connect-src` de `docs/03` §8.1 (DA-4) | S2 |
| Aplicación del limitador y del materializador a `login`; cierre de H1; tope de `Delayed` (O2, DA-9) | S2 |
| Inscripción de MFA ya inscrita (DA-8), límites de tasa sin fila en §10 (DA-13), vista de sesiones activas (DA-14), nombres de rutas de sesión (DA-18 i) | S2 |
| Propagación del origen de la petición a los comandos de identidad (O6) y redacción de credenciales en un flujo completo (O9) | S2 |
| Revocación por cambio de MFA (O10) | S2 para la inscripción; la baja voluntaria, con su cambio |
| `reset/request`, `reset/confirm`, H3, H4 y cierre documental de la parte 4b | S3, después del cambio 9 |
| Purga de familias, tokens de refresco e `identity_login_backoff` | Cambio 9 |
| Aviso al titular por reutilización detectada o restablecimiento | Cambio 14 |
| Matriz de roles, claim de permisos, cobertura de MFA por ruta, guarda de arranque de RBAC, cuenta desactivada y `admin_revoke` (DA-15) | Cambio 8 |
| Prueba de atributos de cookie con Playwright (ADR-0005, verificación 11) | Primer cambio de `apps/admin-web` |
| Filtro central de mensajes de excepción en el registro estructurado | Cambio 10 |
| Redis, llenado de proxies de confianza, custodia de claves fuera de variables de entorno | Cambio 11 |
| Endpoint JWKS (DA-16) | Según la pregunta 8 |
| Par de claves del portal (DA-17) | Según la pregunta 9 |

## Capacidades

> Contrato con `/sdd-spec`. Investigado contra `openspec/specs/` (diez capacidades publicadas).

### Nuevas

- Ninguna. El formato del token, las familias, la rotación y la revocación pertenecen a `identity`, que
  ya publica la rotación con reutilización y la separación de dominios; el filtro y el principal
  pertenecen a `web-edge`. La fase de especificación puede proponer separar una capacidad
  `session-tokens` si el delta de `identity` resulta inmanejable.

### Modificadas

- **`identity`:** formato y vida del token de acceso; token restringido de MFA; anillo de claves y
  verificación de arranque; familias de refresco (V8), emisión, rotación, ventana de gracia con sucesor
  cifrado, reutilización, inactividad y vigencia absoluta; `SessionValidity`; **inversión** del
  requisito «Ausencia de revocación de sesiones al restablecer» por su cumplimiento (H2, I24); destinos
  actualizados de las brechas que pasan a S2 y S3.
- **`web-edge`:** filtro de autenticación `Bearer`; autorización de rutas autenticadas (DA-7);
  `token-invalid` y `token-expired` en el catálogo; `WWW-Authenticate`; el puerto de vigencia de sesión
  pasa a tener adaptador; el requisito de ausencia de autenticación se estrecha y nombra a S2 y S3.
- **`build-integrity`:** regla de utilidades de firma y de hash (ADR-0005, verificación 13); retiro de la
  excepción W2a; enumerador de rutas con rutas autenticadas; `ProcessBeanPolicy`; primera operación en
  la instantánea OpenAPI (si se acepta la pregunta 1).
- **`organization`:** el adaptador real de `CurrentInstitutionProvider` y su escenario de ADR-0009.
- **`audit-trail`:** solo si su especificación fija el catálogo de acciones; la fase de especificación
  lo confirma.

## Enfoque

1. **Criptografía mínima y cerrada.** Un solo algoritmo, un solo formato de cabecera, rechazo de todo lo
   demás. Las pruebas de ataque se escriben antes que el código (TDD estricto) y la mutación de PIT
   cubre el códec.
2. **Ubicación.** Códec, anillo, verificador, filtro y principal viven en `shared.security` o
   `shared.web`; las reglas de sesión (familias, rotación, revocación) viven en `identity`. `shared` no
   depende de `identity` (W3); `identity` implementa `SessionValidity`. El diseño fija el paquete exacto
   dentro de los dos que permite ADR-0005.
3. **Transacciones explícitas.** Toda escritura de V8 ocurre dentro del `TransactionRunner` de
   `shared.security`, con bloqueo de la fila de la familia. La auditoría se escribe en la misma
   transacción que su efecto, también cuando el desenlace es un rechazo por reutilización.
4. **Sin endpoints de credenciales.** La emisión y la rotación se ejercitan desde pruebas de integración
   contra PostgreSQL real; la única ruta de producción nueva es la de lectura de la sesión.
5. **Concurrencia probada con dos transacciones reales** (`confia-testing-playbook` §5), con
   `CountDownLatch` o `CyclicBarrier`, nunca con esperas por reloj; reloj inyectado para las vigencias y
   la gracia.
6. **Sondas antes del diseño:** vectores de RFC 8037 con `Signature("Ed25519")` en JDK 25; reentrada de
   la gracia con sucesor cifrado; efecto de `springdoc.override-with-generic-response` (si se acepta la
   pregunta 1); rechazo de propiedades desconocidas de Jackson en Spring Boot 4; arranque de las pruebas
   `*Test` sin Docker con la verificación de claves activa.

## Áreas afectadas

| Área | Cambio |
|---|---|
| `apps/api/app/src/main/java/com/confia/shared/security/` | Códec JWS, anillo de claves, verificador, principal, verificación de arranque |
| `apps/api/app/src/main/java/com/confia/shared/web/edge/` | Filtro en `AdminSecurityConfiguration`, lista de rutas autenticadas |
| `apps/api/app/src/main/java/com/confia/shared/web/problem/` | `token-invalid`, `token-expired`, `WWW-Authenticate`; corrección de `unexpected` (pregunta 3) |
| `apps/api/app/src/main/java/com/confia/identity/{domain,application,infrastructure,web}/` | Familias, emisión, rotación, revocación, `SessionValidity`, puerto de revocación en `ResetPasswordWithToken`, controlador de la sesión actual |
| `apps/api/app/src/main/java/com/confia/organization/` o `shared.security` | Adaptador de `CurrentInstitutionProvider` (el diseño fija el lado) |
| `apps/api/app/src/main/resources/db/migration/V8__*.sql` | Tablas de familias y tokens |
| `apps/api/app/src/main/resources/i18n/problems.properties` | Mensajes es-HN de los códigos nuevos |
| `apps/api/openapi/admin.openapi.json`, `packages/contracts` | Primera operación (pregunta 1) |
| Pruebas | `IdentityScopeExclusionInventoryTest`, `WebEdgeScopeExclusionInventoryTest`, `EmptyShouldExceptionInventoryTest`, `ProblemCatalogCoverageTest`, `PublicRouteAllowListTest`, `ProcessBeanIsolationTest`, pruebas de arranque de los tres procesos |
| Documentación | `foundations-plan/exploration.md`, `docs/03` §4.5, `docs/05` |

## Riesgos

| Riesgo | Probabilidad | Mitigación |
|---|---|---|
| Defecto en el JWS propio (confusión de algoritmo, `crit`, base64url laxo) | Media | Vectores de RFC 8037, pruebas de ataque escritas primero, PIT sobre el códec, revisión de seguridad del PR |
| Carrera en la rotación: dos refrescos, gracia y reutilización a la vez | Alta si no se diseña | Bloqueo de la familia, `UPDATE` condicional, pruebas con dos transacciones reales |
| La revocación por reutilización se revierte con el rechazo | Media | Desenlace tipado en lugar de excepción; prueba que verifica la revocación confirmada |
| El sucesor cifrado queda recuperable más allá de 10 s (sin trabajos programados hasta el cambio 9) | Cierta | Pregunta 6: descifrado rechazado fuera de la ventana, vaciado al siguiente contacto y purga del cambio 9 |
| La verificación de claves en el arranque rompe las pruebas `*Test` sin Docker o el arranque local | Media | Claves de prueba generadas en el árbol de pruebas, nunca comprometidas; sonda previa |
| Cambio de constructor de `ResetPasswordWithToken`: verde por la razón equivocada | Media | Prueba I24 con base de datos real y control negativo (transacción revertida) |
| La primera operación en la instantánea OpenAPI arrastra respuestas genéricas no aprobadas (O4) | Media | Sonda de `override-with-generic-response` antes del PR |
| Subestimación de tamaño (4a: 8 veces el nominal inicial) | Alta | Pronóstico corregido abajo; cadena apilada; replanificación al superar el 50 % |
| Límite de quince tareas de `openspec/changes/README.md` | Media | Tareas por PR, no por pieza; si se supera, se pide decisión antes de aplicar |
| Duración de `verify` (5 min 24 s hoy, presupuesto de 8 min) | Media | Las pruebas de concurrencia comparten contenedor; las puertas de tiempo son de S2 y S3 |
| Claves privadas en variables de entorno hasta el cambio 11 | Cierta, declarada | Verificación de arranque y nota en `docs/03` y `docs/05` |
| La regla de utilidades de hash (ADR-0005, verificación 13) choca con usos legítimos ya fusionados fuera de `identity` y `shared.security` (por ejemplo, el hash de la carga útil de la idempotencia) | Media | El diseño inventaría los usos actuales antes de fijar el alcance de la regla; una excepción, si hace falta, cita su ADR |

## Plan de reversión

No existe ningún entorno desplegado ni dato real.

1. Cada PR se revierte con su commit de fusión, en orden inverso de la cadena.
2. `V8` crea tablas nuevas sin tocar las existentes. Antes de producción, revertirla es eliminar la
   migración y regenerar jOOQ; una vez aplicada en un entorno compartido, nunca se edita y se revierte
   con una migración `V9` que elimina las dos tablas.
3. Revertir el PR de H2 devuelve `ResetPasswordWithToken` a su constructor anterior; exige revertir
   antes el PR de emisión, para no dejar emisión sin revocación en `main`.
4. Revertir el filtro devuelve la cadena a denegar toda ruta no pública, el comportamiento de 4a.
5. S2 no ha empezado, así que nada depende todavía de este cambio.

## Dependencias

- **Consume:** `SecurityChains`, Problem Details y catálogo, `RequestOrigin`, `TransactionRunner`,
  `AuditLogWriter`, `ColumnEncryptionService` (ADR-0023), `SessionValidity` (puerto), `ResetPasswordWithToken`,
  `CurrentInstitutionProvider` (puerto).
- **Entrega a S2:** emisión de familias, rotación, token restringido, filtro, principal y la
  revocación al restablecer.
- **Herramienta:** ninguna dependencia Maven nueva (DA-2). Docker para las pruebas de integración.

## Criterios de éxito

- [ ] `./mvnw verify` en `apps/api` termina en verde, con la puerta de mutación; cobertura global de
      `app` al menos 80 % y del paquete `domain` de `identity` al menos 95 %; mutación de PIT al menos
      80 % en `domain` y en el códec.
- [ ] Los vectores de RFC 8037 pasan, y un token con `alg` distinto de `EdDSA`, sin `kid`, con un `kid`
      desconocido, con una cabecera adicional, con base64url no canónico o firmado con otra clave se
      rechaza en el 100 % de los casos de prueba.
- [ ] Un token decodificado cumple `exp - iat = 600` y `aud = confia-admin`, y no contiene ningún dato
      personal (prueba de lista cerrada de claims).
- [ ] El proceso administrativo no arranca sin su clave; el portal y el trabajador no arrancan con la
      clave privada administrativa presente (sujeto a la pregunta 9).
- [ ] La ruta de prueba responde `200` con un token válido; `401` con `token-invalid`, `token-expired` o
      `authentication-required` en los demás casos, siempre con `WWW-Authenticate`; un token restringido
      no la abre.
- [ ] Ninguna cabecera, parámetro ni cuerpo altera la institución que resuelve `CurrentInstitutionProvider`.
- [ ] Con el rol restringido, una sesión de la institución A ve cero filas de V8 de la institución B, y
      `DELETE` sobre las dos tablas es rechazado por el motor.
- [ ] El valor almacenado de un refresco no coincide con el entregado (prueba 5 de ADR-0005).
- [ ] Pruebas 1 y 2 de ADR-0005: reutilización fuera de la gracia revoca la familia con
      `reuse_detected`, deja cero tokens vigentes y escribe la auditoría; dentro de 10 s desde el mismo
      dispositivo devuelve el mismo sucesor sin revocar.
- [ ] Dos rotaciones simultáneas del mismo token producen exactamente un sucesor.
- [ ] I24: un restablecimiento revoca el 100 % de las familias vivas de la cuenta, ninguna ajena, y nada
      si la transacción se revierte. Ningún commit de `main` contiene emisión sin esta revocación.
- [ ] Las inversiones de inventario listadas en el punto 24 existen y cada una falla con su control
      negativo.
- [ ] Las notas de transferencia existen en `foundations-plan/exploration.md` y en los deltas de
      `identity` y `web-edge`, y ninguna reescribe el texto de una condición dura.

## Pronóstico de tamaño y entrega

Estrategia `auto-chain` con `stacked-to-main`; cada PR hasta 800 líneas efectivas
(`docs/15-flujo-de-trabajo-git.md` §3); las excepciones de tamaño se piden al propietario solo si hacen
falta.

**Nominal** (exploración §6): C4a 600 + C4b 500 + C5a 700 + C5b 750 + C7a 450 = 3 000, más unas 400 de
O1, O3, O4 y documentación: **unas 3 400 líneas**; la suma de la tabla de abajo, que ya reparte esas
piezas por PR, da unas 3 900.

**Realista**, con la corrección de 2 a 3 veces: **de 7 000 a 11 500 líneas efectivas**, con cola de unas
15 000 si se repite el patrón de 4a. Eso son **de 10 a 15 PR** como planeación, hasta unos 19 en la
cola. Tareas estimadas: **13 a 15**, en el límite de quince; si el plan de tareas lo supera, se pide
decisión al propietario antes de aplicar.

| Orden | PR provisional | Contenido | Nominal |
|---|---|---|---|
| 1 | C4a-1 | Códec JWS Ed25519, vectores de RFC 8037, pruebas de ataque | ~350 |
| 2 | C4a-2 | Anillo de claves con `kid`, carga por entorno, verificación de arranque, claves de prueba, regla de utilidades de firma | ~300 |
| 3 | C4a-3 | Token de acceso y token restringido, emisor y verificador, pruebas 3 y 4 de ADR-0005 | ~300 |
| 4 | C4b-1 | Filtro, principal, `token-invalid`, `token-expired`, `WWW-Authenticate`, rutas autenticadas, enumerador | ~300 |
| 5 | C4b-2 | `CurrentInstitutionProvider`, prueba de ADR-0009, ruta de la sesión actual, primera instantánea OpenAPI, retiro de W2a | ~300 |
| 6 | C5a-1 | `V8`, regeneración de jOOQ, pruebas de RLS y de privilegios | ~350 |
| 7 | C7a | Puerto y adaptador de revocación, `ResetPasswordWithToken`, I24, inversión del inventario de identidad (H2) | ~450 |
| 8 | C5a-2 | Emisión de familias y auditoría | ~350 |
| 9 | C5b-1 | Rotación, bloqueo, reutilización, vigencias | ~400 |
| 10 | C5b-2 | Ventana de gracia con sucesor cifrado y pruebas de concurrencia | ~350 |
| 11 | C5b-3 | `SessionValidity`, comprobación de `sid`, corrección de `unexpected` (pregunta 3), inversión de `web-edge` | ~300 |
| 12 | Docs | Transferencia de condiciones, `docs/03` §4.5, `docs/05` | ~150 |

El orden 6, 7, 8 es obligatorio por H2. Con el factor realista, los PR 1, 7, 9 y 10 son los candidatos a
partirse en dos.

## Preguntas abiertas para el propietario

Solo las que S1 debe resolver. DA-8, DA-9, DA-13, DA-14 y DA-18 (i) son de S2, y no se preguntan aquí.

1. **Ruta de prueba de la autenticación.** *a)* `GET` de la sesión actual en producción (por ejemplo
   `GET /api/v1/auth/sessions/current`), con DTO sin datos personales: prueba el filtro con código real,
   retira W2a y es la ruta que la SPA necesitará; a cambio adelanta a S1 la primera instantánea OpenAPI
   (O4) y el estilo de nombres de DA-18 (i) para esa ruta. *b)* Controlador solo del árbol de pruebas,
   como el de idempotencia de 4a: S1 queda sin contrato, pero la lista de rutas autenticadas no tiene
   ninguna entrada real y W2a sigue abierta. **Recomendación: *a)*, con estilo de recursos, que S2
   hereda.**
2. **Autorización de rutas autenticadas sin permiso (DA-7).** *A)* Lista cerrada de rutas autenticadas,
   paralela a `PublicEndpoints`, antes de `denyAll()`, con prueba que enumera las rutas. *B)* Anotación
   por controlador con prueba de recorrido. *C)* Esperar al cambio 8, lo que deja a S1 sin ruta real y
   a S2 sin cierre de sesión. **Recomendación: *A)*, el mismo patrón ya probado; el cambio 8 la
   sustituye por la matriz.**
3. **Fuga de `ProblemExceptionHandler.unexpected` (O1, DA-10).** *a)* Corrección mínima en S1 (para
   excepciones de acceso a datos, registrar solo clase, `SQLState` y `requestId`). *b)* Esperar al
   cambio 10. *c)* *a)* ahora y el filtro central en el cambio 10. En S1 ninguna escritura es alcanzable
   por HTTP, pero S1 trae la primera ruta que consulta la base, y S2 trae la violación de unicidad de
   DA-8. **Recomendación: *c)*, para que los endpoints de S2 nazcan protegidos (unas 150 líneas).**
4. **Inactividad y vigencias (DA-11).** *a)* Inactividad de 30 minutos aplicada al refrescar con
   `last_used_at` de la familia (la granularidad real es el ciclo de 10 minutos, y solo funciona si la
   SPA refresca por actividad y no por temporizador). *b)* Además, actualizar `last_used_at` en cada
   petición autenticada (una escritura por petición, contra ADR-0005). *c)* Solo en la interfaz.
   Lectura conjunta propuesta: cada refresco vence a `min(emisión + 8 h, absoluta)`; la familia vence a
   las 12 h de su creación y a los 30 min sin refresco. **Recomendación: *a)* con esa lectura, y una nota
   en `docs/ui-ux/04` para que la SPA refresque solo por actividad.**
5. **Vinculación a agente de usuario e IP (DA-12).** *a)* Solo registrar en la familia la huella del
   agente (hash) y la IP inicial. *b)* Aplicar al refrescar: si cambian ambos, revocar la familia.
   *c)* *b)* con aviso al titular (cambio 14). La huella del agente se usa de todos modos para «el mismo
   dispositivo» de la gracia. **Recomendación: *a)* en S1, con la limitación declarada; *b)* se reevalúa
   con el endpoint de refresco en S2. La IP queda sujeta a la retención de `docs/08` y a la purga del
   cambio 9.**
6. **Vaciado del sucesor cifrado (aclaración de DA-5, sin reabrirla).** «Se vacía al vencer» no tiene
   actor hasta el cambio 9, porque solo el trabajador ejecuta trabajos. *a)* Descifrado rechazado fuera
   de los 10 s en todo caso; vaciado físico en el siguiente contacto con la familia (rotación del
   sucesor, revocación) y purga residual en el cambio 9. *b)* Mantener el texto literal y bloquear S1
   hasta el cambio 9, contra el orden de DA-6. **Recomendación: *a)*.**
7. **Cuenta desactivada (DA-15).** *a)* Diferir al cambio 8, con el restablecimiento administrativo;
   V8 no declara motivos de revocación sin productor (`admin_revoke` llega con su migración). *b)*
   Columna mínima de estado en S1, sobre una tabla archivada. **Recomendación: *a)*.**
8. **Endpoint JWKS (DA-16).** *a)* Diferir; el único verificador es el propio proceso, con el anillo en
   memoria. *b)* Publicarlo ahora como ruta pública. *c)* Publicarlo restringido por red (cambio 11).
   **Recomendación: *a)*, con nota en `docs/03` §4.5.**
9. **Alcance del portal (DA-17).** *a)* El portal sigue denegando todo; el portal y el trabajador solo
   comprueban en el arranque que la clave privada administrativa no está en su entorno. *b)* Registrar
   además el par del portal y su verificación sin ninguna ruta. **Recomendación: *a)*; el par del
   portal llega con su primera ruta.**
10. **Comprobación de `sid` contra la base (DA-18 iii).** *a)* En toda petición autenticada (contradice
    «sin consulta de estado» de ADR-0005). *b)* Solo en operaciones sensibles, como fija ADR-0005.
    *c)* *b)* y además en las rutas de gestión de sesión, incluida la lectura de la sesión actual.
    **Recomendación: *c)*: en S1 aplica a la ruta de la sesión actual y deja el mecanismo listo para
    las escrituras sensibles.**
11. **Claim `permissions` (DA-18 ii, forzado por el formato del token de S1).** *a)* Ausente hasta el
    cambio 8, con el verificador rechazando claims no declarados. *b)* Presente y vacío. **Recomendación:
    *a)*: un conjunto vacío puede leerse como autoritativo, y el cambio 8 lo añade con su significado.**

## Decisiones del propietario (2026-10-06)

El propietario aprobó la propuesta y adoptó las once recomendaciones de «Preguntas abiertas para el propietario»:

1. La autenticación se demuestra con una ruta de producción `GET /api/v1/auth/sessions/current`, en estilo de recursos, que S2
   hereda. Esto trae a S1 la primera instantánea OpenAPI (O4) y la parte de DA-18 (i) que afecta a esa ruta.
2. DA-7: las rutas autenticadas sin permiso de rol van en una lista cerrada antes de `denyAll()`, paralela a `PublicEndpoints`.
3. O1 y DA-10: corrección mínima de `ProblemExceptionHandler.unexpected` en S1 y filtro central en el cambio 10.
4. DA-11: la inactividad se aplica al refrescar, con `last_used_at`.
   - Cada token de refresco vence en el mínimo entre su emisión más 8 h y el vencimiento absoluto.
   - La familia vence a las 12 h de creada o tras 30 min sin refresco.
   - La SPA solo refresca ante actividad del usuario.
5. DA-12: en S1 solo se registran el agente de usuario y la IP; la aplicación de la vinculación se revisa en S2. La huella del agente
   sigue haciendo falta para el «mismo dispositivo» de la ventana de gracia.
6. DA-5: el sucesor cifrado se rechaza para descifrar pasados 10 s y se vacía en el siguiente uso de la familia; el cambio 9 purga
   el resto.
7. DA-15: la cuenta desactivada se difiere al cambio 8, sin motivos de revocación en V8 que todavía no tengan productor.
8. DA-16: el endpoint JWKS se difiere.
9. DA-17: el portal y el trabajador solo comprueban al arrancar que no tienen la clave privada administrativa.
10. DA-18 (iii): `sid` se comprueba contra la base de datos en las operaciones sensibles y en las rutas de gestión de sesión.
11. DA-18 (ii): el claim `permissions` queda ausente hasta el cambio 8.

Estrategia de entrega confirmada: `auto-chain` con `stacked-to-main` y 800 líneas efectivas por PR.
