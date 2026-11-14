# Delta para Integridad de la construcción

- **Estado:** pendiente de aprobación del propietario del producto
- **Cambio:** `password-recovery-endpoints` (F0, cambio 7, parte 4b, tercer cambio S3)
- **Propuesta:** `proposal.md`, aprobada el 2026-11-09 con las decisiones P1 a P7 del propietario

**Convenciones de este delta.**

- Los escenarios nuevos se numeran `BI140` en adelante, a continuación del máximo del delta en curso de
  `rbac-permission-matrix-and-audit-integration` (`BI139`). Este cambio ocupa `BI140` a `BI149`; `transactional-email-adapter`
  empieza en `BI150`.
- Este delta solo añade requisitos. No modifica «La instantánea del documento administrativo declara las siete
  operaciones de sesión», que el delta de rbac renombra y modifica: la instantánea de las dos operaciones de
  recuperación tiene su propio requisito, de modo que los dos deltas se archivan sin conflicto.
- «Rutas de recuperación» son `POST /api/v1/auth/password-reset-requests` y `POST /api/v1/auth/password-resets`.

## ADDED Requirements

### Requisito: La puerta de tiempo de la solicitud de recuperación corre en `verify`, falla la construcción y tiene un control negativo con poder discriminante

La construcción DEBE ejecutar en `./mvnw verify` la puerta de tiempo de la ruta de solicitud (200 solicitudes de
cuenta existente y 200 de identificadores sin cuenta, diferencia de medianas de 50 ms como máximo), sobre el proceso
administrativo arrancado por su camino de producción y una base PostgreSQL real, dentro del presupuesto de tiempo de
las pruebas `*IT`. La puerta DEBE tener un control negativo que demuestre que el arnés distingue una diferencia del
tamaño que la puerta debe rechazar: una variante que añade un trabajo de duración conocida, el doble del límite (100
ms), **solo** en el camino de la cuenta existente, en el punto donde una regresión real lo añadiría (la
programación de la emisión), y que la puerta DEBE rechazar. El control DEBE además medir una diferencia de al menos
el 80 % del trabajo añadido, para que no pase por una causa ajena (un `429`, un `400`, o las dos series sobre el
mismo camino). El trabajo añadido NO DEBE ser una espera con `Thread.sleep` en el código bajo prueba: lo introduce
solo el árbol de pruebas, sobre el puerto de programación, y solo en la ejecución del control. La medición
informativa `PasswordResetRequestTimingReportIT`, que medía el caso de uso sin HTTP y sin bloquear, DEBE retirarse,
porque la puerta la sustituye.

#### Escenario: [BI140] El control negativo falla la puerta

- **DADO** la variante con 100 ms añadidos en la programación de la emisión de la cuenta existente
- **CUANDO** se ejecuta la puerta de tiempo
- **ENTONCES** la diferencia medida supera 50 ms, la puerta la rechaza y el mensaje nombra las dos medianas
- **Y** la diferencia medida es de al menos 80 ms

#### Escenario: [BI141] La puerta pasa con el código real dentro del presupuesto

- **DADO** el código real, sin la variante
- **CUANDO** se ejecuta `./mvnw verify`
- **ENTONCES** la puerta pasa y la duración total de `verify` se registra en `apply-progress.md` frente al
  presupuesto vigente de las pruebas `*IT`

#### Escenario: [BI142] La medición informativa ya no existe

- **DADO** el árbol de pruebas al cerrar este cambio
- **CUANDO** se busca `PasswordResetRequestTimingReportIT`
- **ENTONCES** no existe, y el Javadoc de la puerta nombra el requisito al que sustituye

### Requisito: Los inventarios de ausencia que las rutas de recuperación rompen se invierten, cada uno con su control negativo

Las pruebas que hoy afirman la ausencia de recuperación por HTTP DEBEN invertirse en el mismo pull request que
registra cada ruta, nunca después: el detector de rutas de recuperación de `WebEdgeScopeExclusionInventoryTest`
(BI52) DEBE encontrar en producción exactamente las rutas de recuperación registradas hasta ese pull request, y su
fixture permanente `BadPasswordRecoveryController` DEBE seguir siendo detectado; la lista de rutas públicas y el
conjunto de rutas de producción de `PublicRouteAllowListTest` DEBEN contener las rutas nuevas; y la guarda de
cobertura de rutas DEBE arrancar con ellas clasificadas como públicas. Ninguna inversión DEBE dejar el detector sin
poder de detección.

#### Escenario: [BI143] El detector encuentra las rutas reales y el fixture

- **DADO** el árbol de producción al cerrar este cambio y el fixture `BadPasswordRecoveryController`
- **CUANDO** se ejecuta el detector de rutas de recuperación
- **ENTONCES** en producción encuentra exactamente las dos rutas de recuperación, y en el fixture encuentra
  `POST /api/v1/auth/password-recovery`

#### Escenario: [BI144] Una ruta de recuperación no declarada falla el inventario

- **DADO** una variante de prueba que añade `POST /api/v1/auth/password-reset-links` a un controlador de producción
  simulado
- **CUANDO** se ejecuta el inventario
- **ENTONCES** falla nombrando la ruta, porque no es una de las dos aprobadas

### Requisito: Toda ruta de recuperación declara su política de límite de tasa y una regla lo hace cumplir

La regla de ArchUnit que obliga a las rutas de sesión a declarar su política DEBE extenderse a los manejadores de
`com.confia.identity.web` de las dos rutas de recuperación: la solicitud DEBE declarar
`@RateLimited(policy = "admin-password-reset-request")` y la confirmación
`@RateLimited(policy = "admin-password-reset")`. Un manejador de recuperación sin política, o con otra, DEBE romper la
construcción, y la regla DEBE evaluar los manejadores reales (sin conjunto vacío).

#### Escenario: [BI145] Las dos rutas declaran su política

- **DADO** los manejadores reales de las dos rutas de recuperación
- **CUANDO** se ejecuta la regla
- **ENTONCES** pasa, y cada manejador declara exactamente su política

#### Escenario: [BI146] Un manejador de recuperación sin política rompe la construcción

- **DADO** el fixture permanente de un manejador de `POST /api/v1/auth/password-resets` sin `@RateLimited`
- **CUANDO** se ejecuta la regla sobre el fixture
- **ENTONCES** falla nombrando el manejador

### Requisito: La instantánea del documento administrativo declara las dos operaciones de recuperación con todas sus respuestas

El OpenAPI generado del proceso administrativo DEBE declarar las dos operaciones de recuperación y coincidir con la
instantánea aprobada `apps/api/openapi/admin.openapi.json`. La solicitud DEBE declarar `202` (con su esquema de
cuerpo), `400`, `403`, `415`, `429` (con `Retry-After`) y `503`. La confirmación DEBE declarar `204`, `400`, `403`,
`415`, `422`, `429` (con `Retry-After`) y `503`. Ninguna DEBE declarar `401` ni `WWW-Authenticate`, ni la cabecera
`Idempotency-Key`, ni un parámetro de consulta o de ruta. Los cuerpos de error DEBEN referirse al esquema de Problem
Details. El portal DEBE seguir sin operaciones. `packages/contracts` DEBE regenerarse con orval desde la instantánea
y las aplicaciones web DEBEN compilar contra él.

#### Escenario: [BI147] Las dos operaciones con exactamente sus respuestas

- **DADO** el documento generado del proceso administrativo
- **CUANDO** se leen las operaciones de las dos rutas de recuperación
- **ENTONCES** declaran exactamente las respuestas de este requisito, sin parámetros de consulta ni de ruta y sin
  `Idempotency-Key`
- **Y** el documento del portal sigue sin operaciones

#### Escenario: [BI148] Una diferencia no declarada rompe la construcción

- **DADO** una variante que añade a la confirmación una respuesta `401` no aprobada
- **CUANDO** se compara el documento generado con la instantánea
- **ENTONCES** la comparación falla nombrando la operación

### Requisito: Los beans y controladores de recuperación solo viven en el proceso administrativo

`RequestPasswordReset` y los dos controladores de recuperación DEBEN registrarse solo en el proceso administrativo.
Sus paquetes (`com.confia.identity.application` y `com.confia.identity.web`) ya figuran en la lista del proceso
administrativo de `ProcessBeanPolicy`, así que el registro no añade líneas; si el diseño moviera alguno a un paquete
nuevo, su línea DEBE entrar en el mismo pull request que el registro. El portal y el trabajador NO DEBEN contener
ninguno de los tres.

#### Escenario: [BI149] Solo el proceso administrativo los contiene

- **DADO** los tres procesos arrancados por su camino de producción, incluido el trabajador, que sí contiene beans de
  `com.confia.identity.application` para la emisión
- **CUANDO** se buscan los beans `RequestPasswordReset`, el controlador de solicitud y el de confirmación
- **ENTONCES** solo el proceso administrativo los contiene y el trabajador no tiene ningún bean de tipo
  `RequestPasswordReset`
