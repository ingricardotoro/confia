# Delta para Integridad de la construcción

- **Estado:** aprobada por el propietario del producto el 2026-10-04
- **Cambio:** `web-edge-foundations` (F0, cambio 7, parte 4a)

## ADDED Requirements

### Requisito: El proceso administrativo arranca sin conexión a la base de datos y sin migrar al arrancar

El proceso administrativo DEBE registrar el `DataSource` de producción y DEBE arrancar su contexto
aunque no exista ninguna base de datos accesible: el arranque NO DEBE realizar una validación
inicial bloqueante de la conexión y NO DEBE ejecutar migraciones de Flyway. Las migraciones siguen
siendo un paso separado del arranque. El portal y el trabajador NO DEBEN registrar ningún
`DataSource` (decisión D5 del propietario). Las pruebas de arranque `*Test` de los tres procesos
DEBEN seguir ejecutándose sin Docker y sin base de datos.

#### Escenario: El proceso administrativo arranca con la base de datos inalcanzable

- **DADO** el proceso administrativo arrancado por su camino de producción con una URL de base de
  datos que no responde en ningún puerto
- **CUANDO** se construye su contexto
- **ENTONCES** el contexto arranca sin error y contiene exactamente un bean `DataSource`
- **Y** ninguna migración de Flyway se ejecuta ni se consulta la tabla de historial durante el
  arranque

#### Escenario: Portal y trabajador no tienen `DataSource`

- **DADO** los procesos del portal y del trabajador arrancados por su camino de producción
- **CUANDO** se inspecciona cada contexto
- **ENTONCES** ninguno contiene un bean `DataSource`, `TransactionRunner` ni `DSLContext`

#### Escenario: Las pruebas de arranque no necesitan Docker

- **DADO** una máquina sin Docker ni PostgreSQL
- **CUANDO** se ejecutan las pruebas `*Test` que arrancan los tres procesos
- **ENTONCES** todas pasan, y ninguna de ellas es una prueba `*IT` ni usa Testcontainers

### Requisito: Spring Security se usa solo como cadena de filtros, sin servidor de recursos OAuth2

El sistema DEBE incorporar Spring Security únicamente como cadena de filtros. El camino de clases
de `apps/api` NO DEBE contener `spring-boot-starter-oauth2-resource-server` ni
`spring-security-oauth2-resource-server`, porque la firma de tokens es decisión de
`session-tokens-and-web-layer` y Spring Security no firma con EdDSA. La dependencia de Spring
Security DEBE pasar `dependencyConvergence` y el escaneo de vulnerabilidades de integración
continua ya exigidos por esta capacidad, sin excepciones nuevas.

#### Escenario: Se añade el servidor de recursos OAuth2

- **DADO** una dependencia de `spring-boot-starter-oauth2-resource-server` en `apps/api`
- **CUANDO** se ejecuta `./mvnw verify`
- **ENTONCES** la construcción falla señalando la dependencia prohibida

#### Escenario: La dependencia de Spring Security converge

- **DADO** la dependencia de `spring-boot-starter-security` tal como la entrega este cambio
- **CUANDO** se ejecuta `./mvnw verify` y el escaneo de integración continua
- **ENTONCES** `dependencyConvergence` pasa sin exclusiones nuevas y el escaneo no reporta
  vulnerabilidad alta o crítica atribuible a Spring Security

### Requisito: Reglas de dependencia de la capa `web` de producción

El sistema DEBE romper la construcción si una clase de un paquete `web` de producción (el de un
módulo de negocio o `com.confia.shared.web`) depende de una clase de `infrastructure`, del paquete
generado de jOOQ o del `domain` de otro módulo. `com.confia.shared` NO DEBE depender de ninguna clase
de un módulo de negocio; en particular, `shared.security` y `shared.web` NO DEBEN depender de
`identity`. Cada regla DEBE tener su propio fixture de prueba permanente que la viole a propósito,
con la convención de dos mitades del repositorio: una que demuestra que una violación real rompe la
construcción y otra que demuestra que el código de producción real no falla por esa causa.
La capa `Web` de `LayeredArchitectureTest` es obligatoria desde el cambio 3 y no tiene
`optionalLayer`; este cambio conserva esa condición y `shared.web` aporta clases reales a esa capa,
aunque no entrega ningún controlador de producción.

#### Escenario: Fixture con una clase `web` que depende de `infrastructure`

- **DADO** un fixture de prueba permanente en el que una clase de un paquete `web` importa una clase
  de `infrastructure`
- **CUANDO** se ejecuta `./mvnw verify`
- **ENTONCES** la regla falla sobre el fixture, señalando la clase y la dependencia prohibida

#### Escenario: Fixture con `shared` que depende de `identity`

- **DADO** un fixture de prueba permanente en el que una clase de `com.confia.shared.web` o de
  `com.confia.shared.security` importa una clase de `com.confia.identity`
- **CUANDO** se ejecuta `./mvnw verify`
- **ENTONCES** la regla falla sobre el fixture, señalando la clase y la referencia prohibida

#### Escenario: Código de producción sin dependencias prohibidas

- **DADO** el código de producción real de los paquetes `web` y de `shared`
- **CUANDO** se ejecuta `./mvnw verify`
- **ENTONCES** las reglas pasan evaluando clases reales de `shared.web` y `shared.security`, sin
  excepción de conjunto vacío para esas dos reglas

### Requisito: Ningún tipo expuesto por la capa `web` es una entidad de dominio ni un registro de jOOQ

El sistema DEBE romper la construcción si un DTO de un paquete `web`, o la firma pública de un
controlador, expone un tipo del paquete `domain` o un tipo del código generado de jOOQ (`Record`,
`TableRecord` o POJO generado). Toda salida DEBE pasar por un DTO explícito (`CLAUDE.md`, regla 10).
La regla DEBE tener su propio fixture permanente con la convención de dos mitades. La mitad que
evalúa la firma pública de los controladores DEBE declarar su excepción de conjunto vacío en
`EmptyShouldExceptionInventoryTest` citando su dueño (`session-tokens-and-web-layer`, primer
endpoint de producción), mientras ningún paquete `web` de producción contenga un controlador (brecha
con destino: `session-tokens-and-web-layer`). La mitad que evalúa los registros y campos de los
paquetes `web` NO DEBE declarar excepción, porque `shared.web` ya contiene registros reales
(`ProblemBody`, `FieldViolation`).

#### Escenario: Fixture con un controlador que devuelve un registro de jOOQ

- **DADO** un fixture de prueba permanente con un método de controlador cuyo tipo de retorno es un
  `Record` de jOOQ
- **CUANDO** se ejecuta `./mvnw verify`
- **ENTONCES** la regla falla sobre el fixture, señalando el método y el tipo expuesto

#### Escenario: Fixture con un DTO que contiene un tipo de `domain`

- **DADO** un fixture de prueba permanente con un DTO de `web` cuyo campo es un tipo del paquete
  `domain`
- **CUANDO** se ejecuta `./mvnw verify`
- **ENTONCES** la regla falla sobre el fixture, señalando el DTO y el campo

#### Escenario: La excepción de conjunto vacío está declarada y con dueño

- **DADO** ningún controlador en el código de producción
- **CUANDO** se ejecuta `EmptyShouldExceptionInventoryTest`
- **ENTONCES** la regla de la firma de controladores figura en el inventario con la cita a
  `session-tokens-and-web-layer`, y eliminar la entrada sin que exista código de producción
  evaluable rompe la construcción

#### Escenario: La regla sobre registros de `web` evalúa clases reales

- **DADO** el código de producción real de `com.confia.shared.web`
- **CUANDO** se ejecuta la regla de los registros y campos de los paquetes `web`
- **ENTONCES** la regla pasa evaluando registros reales, como `ProblemBody` y `FieldViolation`, y no
  figura ninguna excepción de conjunto vacío para ella

### Requisito: La espera bloqueante del retardo se confina al materializador

El sistema DEBE romper la construcción si una clase de producción en un paquete `web`,
`application` o `domain` llama a `Thread.sleep`, `TimeUnit.sleep`, `LockSupport.park*` u
`Object.wait`, salvo el materializador del retardo. `NoBlockingWaitInIdentityTest` DEBE
mantenerse sin cambios. La regla DEBE tener su propio fixture permanente con la convención de dos
mitades, y NO DEBE evaluar un conjunto vacío.

#### Escenario: Fixture con una espera fuera del materializador

- **DADO** un fixture de prueba permanente en un paquete `web` o `application` que invoca
  `Thread.sleep`
- **CUANDO** se ejecuta `./mvnw verify`
- **ENTONCES** la regla falla sobre el fixture, señalando la clase y la llamada

#### Escenario: El materializador es la única clase con espera

- **DADO** el código de producción real
- **CUANDO** se ejecuta `./mvnw verify`
- **ENTONCES** la regla pasa evaluando clases reales, y la única llamada de espera en esos paquetes
  pertenece al materializador del retardo

#### Escenario: Se mantiene la prohibición en `identity`

- **DADO** una clase de `identity.application` o `identity.domain` que espera de forma bloqueante
- **CUANDO** se ejecuta `./mvnw verify`
- **ENTONCES** `NoBlockingWaitInIdentityTest` sigue fallando señalando la clase

### Requisito: Instantánea aprobada del mapa de rutas del portal

El sistema DEBE generar, en cada ejecución de `./mvnw verify`, el mapa de rutas que registra el
contexto del portal y DEBE compararlo contra una instantánea aprobada y comprometida en el
repositorio (ADR-0024, verificación 5). La instantánea entregada por este cambio DEBE ser el
conjunto vacío, declarado de forma explícita, porque el portal arrancado con el perfil por omisión
(configuración de producción) deniega toda ruta y no registra ninguna ruta de aplicación; las
entradas de documentación de springdoc solo existen con `local` y `preprod` y no forman parte de la
instantánea. La comparación NO
DEBE sobrescribir la instantánea nunca: actualizarla DEBE ser un paso explícito y separado, visible
en el diff del PR. La verificación DEBE fallar si el archivo de la instantánea no existe, de modo
que un mapa vacío no pase por ausencia de archivo.

#### Escenario: Una ruta nueva en el portal rompe la construcción

- **DADO** la instantánea vacía comprometida
- **CUANDO** el portal registra una ruta que la instantánea no contiene
- **ENTONCES** `./mvnw verify` falla señalando la ruta añadida
- **Y** la instantánea comprometida queda intacta después de la ejecución

#### Escenario: Instantánea ausente

- **DADO** el archivo de la instantánea eliminado
- **CUANDO** se ejecuta `./mvnw verify`
- **ENTONCES** la construcción falla señalando que falta la instantánea, en vez de tratar el mapa
  vacío como coincidente

#### Escenario: Mapa idéntico a la instantánea

- **DADO** el portal tal como lo entrega este cambio y la instantánea vacía sin alterar
- **CUANDO** se ejecuta `./mvnw verify`
- **ENTONCES** el mapa generado coincide con la instantánea y la verificación pasa

### Requisito: El mecanismo HTTP de idempotencia no se aplica a ningún endpoint de identidad

El sistema DEBE romper la construcción si una clase de producción en un paquete `web` aplica el
mecanismo HTTP de `Idempotency-Key` a un endpoint de inicio de sesión, refresco, MFA o recuperación
de contraseña, o a cualquier otro endpoint de `identity`, porque `IdempotentExecutor` persiste y
reproduce el cuerpo de la respuesta y los tokens quedarían almacenados en claro (decisión D3; `CLAUDE.md`,
reglas 11 y 13). La regla DEBE tener su propio fixture permanente con la convención de dos mitades.

#### Escenario: Fixture con un endpoint de identidad que usa la idempotencia

- **DADO** un fixture de prueba permanente en un paquete `web` de `identity` que aplica el
  mecanismo de `Idempotency-Key`
- **CUANDO** se ejecuta `./mvnw verify`
- **ENTONCES** la regla falla sobre el fixture, señalando la clase y el uso prohibido

#### Escenario: Código de producción sin uso en identidad

- **DADO** el código de producción real
- **CUANDO** se ejecuta `./mvnw verify`
- **ENTONCES** ninguna clase de producción de `identity` aplica el mecanismo, y el único endpoint que
  lo aplica vive en el árbol de pruebas; fuera de `shared.web.idempotency`, solo la configuración de
  `shared.web.edge` lo referencia, para registrarlo

## MODIFIED Requirements

### Requisito: Cada proceso registra solo beans de `com.confia.*` incluidos en su lista de permitidos

El sistema DEBE verificar, en cada ejecución de `./mvnw verify`, que el contexto de cada uno de los
tres procesos —administrativo, portal y trabajador— contiene únicamente beans de `com.confia.*`
cuyo paquete pertenece a la lista de permitidos de ese proceso (ADR-0003). La verificación DEBE
arrancar cada proceso por el mismo camino de arranque de producción, sin base de datos accesible. La
lista de permitidos DEBE fallar cerrado: un bean de `com.confia.*` cuyo paquete no figura en la lista
del proceso DEBE romper la construcción, de modo que registrar un módulo nuevo en un punto de
entrada obliga a editar la lista y esa edición queda visible en la revisión. La lista NO DEBE
reemplazarse por una lista de prohibidos como único control. Toda configuración pública importada
con `@Import` por un punto de entrada y su entrada en la lista de permitidos DEBEN llegar en el mismo
pull request (ADR-0024). La lista del proceso administrativo DEBE permitir los beans de
`com.confia.identity` que su configuración pública registra y los beans de `com.confia.shared`
registrados para el borde web.
(Previously: la lista del proceso administrativo solo permitía `bootstrap.admin` y
`shared.web.openapi`, y la verificación arrancaba "sin base de datos" sin precisar accesibilidad.)

#### Escenario: Un bean fuera de la lista rompe la construcción

- **DADO** el contexto de un proceso con un bean de `com.confia.*` en un paquete que no figura en la
  lista de permitidos de ese proceso
- **CUANDO** se ejecuta `./mvnw verify`
- **ENTONCES** la construcción falla señalando el proceso, el bean y su paquete

#### Escenario: Un módulo nuevo obliga a editar la lista

- **DADO** un módulo nuevo cuya configuración un punto de entrada importa, sin haber editado la
  lista de permitidos de ese proceso
- **CUANDO** se ejecuta `./mvnw verify`
- **ENTONCES** la construcción falla, y el único modo de volverla verde es editar la lista, con la
  edición visible en el diff del PR

#### Escenario: Cada proceso solo contiene beans permitidos

- **DADO** los tres procesos tal como los entrega este cambio
- **CUANDO** se ejecuta `./mvnw verify`
- **ENTONCES** todo bean de `com.confia.*` de cada contexto pertenece a la lista de permitidos de su
  proceso, y la verificación evalúa beans reales de cada contexto, no un conjunto vacío

#### Escenario: El proceso administrativo registra el cableado de producción

- **DADO** el proceso administrativo tal como lo entrega este cambio
- **CUANDO** se inspecciona su contexto
- **ENTONCES** contiene los beans `DataSource`, `TransactionRunner`, `DSLContext` y `Clock` de
  producción y los casos de uso de identidad, todos dentro de su lista de permitidos
- **Y** la verificación evalúa esos beans reales, no un conjunto vacío

### Requisito: El contexto del portal no contiene beans de otros puntos de entrada ni de módulos administrativos

El contexto del proceso del portal NO DEBE contener ningún bean de los paquetes de entrada del
proceso administrativo (`com.confia.bootstrap.admin`) ni del proceso trabajador
(`com.confia.bootstrap.worker`), ni de los módulos `invoicing`, `cashbox` y `reconciliation`, ni de
`identity` mientras ese módulo sea solo para personal. Esta prohibición nominal DEBE regir además de
la lista de permitidos del requisito anterior, no en su lugar. El sistema DEBE romper la
construcción si el contexto del portal contiene alguno de ellos (ADR-0003, verificación 1, en su
parte de grafo de beans). El portal NO DEBE contener los beans de cableado de producción que solo
registra el proceso administrativo (`DataSource`, `TransactionRunner`, `DSLContext`, casos de uso de
identidad). El portal SÍ DEBE contener su cadena de seguridad que deniega todo.
(Previously: el requisito no mencionaba el cableado de producción del proceso administrativo ni la
cadena de seguridad del portal.)

#### Escenario: El portal arrastra un punto de entrada ajeno

- **DADO** el contexto del portal con un bean de `com.confia.bootstrap.admin` o de
  `com.confia.bootstrap.worker`
- **CUANDO** se ejecuta `./mvnw verify`
- **ENTONCES** la construcción falla señalando el bean prohibido

#### Escenario: El portal arrastra un módulo administrativo

- **DADO** el contexto del portal con un bean de `invoicing`, `cashbox`, `reconciliation` o de
  `identity` mientras `identity` sea solo para personal
- **CUANDO** se ejecuta `./mvnw verify`
- **ENTONCES** la construcción falla señalando el bean y el módulo prohibido, aunque la lista de
  permitidos del portal llegara a incluir su paquete por error

#### Escenario: El portal contiene solo lo suyo

- **DADO** el contexto del portal tal como lo entrega este cambio
- **CUANDO** se ejecuta `./mvnw verify`
- **ENTONCES** no contiene ningún bean de `bootstrap.admin`, de `bootstrap.worker` ni de los módulos
  administrativos, y la verificación pasa evaluando beans reales del contexto

#### Escenario: El portal no hereda el cableado administrativo

- **DADO** el contexto del portal, aunque el proceso administrativo registre `DataSource`,
  `TransactionRunner`, `DSLContext` y los casos de uso de identidad
- **CUANDO** se inspecciona el contexto del portal
- **ENTONCES** ninguno de esos beans existe en él, y contiene su cadena de seguridad que deniega
  todo y solo los beans del borde permitidos para el portal (`shared.web.edge`, `shared.web.request`
  y `shared.web.problem`)

### Requisito: El contexto del trabajador no contiene beans de los otros puntos de entrada ni sus importaciones

El contexto del proceso trabajador NO DEBE contener ningún bean de `com.confia.bootstrap.admin` ni
de `com.confia.bootstrap.portal`, ni los beans que esos puntos de entrada importan para su propia
superficie de API, `ContractSchemas` y `ProcessApiInfo`. El trabajador DEBE declarar sus propios
módulos y NO DEBE heredar el grafo de beans administrativo por el hecho de usar la configuración y
el rol de base de datos administrativos (aclaración de ADR-0003 que fija ADR-0024). El trabajador
NO DEBE contener ninguna cadena de seguridad, ningún bean de Spring Security, `DataSource`,
`RateLimiter` ni materializador del retardo, porque no tiene servidor web. El sistema DEBE romper la construcción si el contexto del
trabajador contiene alguno de ellos.
(Previously: el requisito no enumeraba la cadena de seguridad, el `DataSource`, el limitador ni el
materializador entre lo prohibido en el trabajador.)

#### Escenario: El trabajador recibe una importación ajena

- **DADO** el contexto del trabajador con un bean `ContractSchemas` o `ProcessApiInfo`, o con un
  bean de `bootstrap.admin` o de `bootstrap.portal`
- **CUANDO** se ejecuta `./mvnw verify`
- **ENTONCES** la construcción falla señalando el bean ajeno

#### Escenario: El trabajador arranca sin servidor web y sin beans ajenos

- **DADO** el proceso trabajador arrancado por el camino de producción, sin servidor web y sin base
  de datos
- **CUANDO** se inspecciona su contexto
- **ENTONCES** no contiene ningún bean de los otros dos puntos de entrada ni sus importaciones, y la
  verificación evalúa el contexto real del trabajador

#### Escenario: El trabajador no recibe el borde web

- **DADO** el contexto del trabajador con un bean `SecurityFilterChain`, cualquier otro bean de
  Spring Security, `RateLimiter` o materializador del retardo
- **CUANDO** se ejecuta `./mvnw verify`
- **ENTONCES** la construcción falla señalando el bean ajeno

### Requisito: Los puntos de entrada se registran de forma explícita, sin escaneo implícito

Cada uno de los tres puntos de entrada DEBE residir en su propio subpaquete de
`com.confia.bootstrap` (`admin`, `portal` y `worker`) y DEBE declarar de forma explícita lo que
importa. Los puntos de entrada NO DEBEN usar `@SpringBootApplication` ni `@ComponentScan`. El portal
y el trabajador DEBEN conservar la exclusión de `DataSourceAutoConfiguration`; el proceso
administrativo NO DEBE excluirla y es el único que registra un `DataSource` (decisión D5).
`ConfiaApplication` DEBE seguir siendo el único método `main` y DEBE conservar la selección del
proceso por `APP_PROFILE` y el arranque del trabajador sin servidor web. El sistema DEBE romper la
construcción si un punto de entrada vuelve a declarar un escaneo de componentes.
(Previously: los tres puntos de entrada conservaban la exclusión de `DataSourceAutoConfiguration`.)

#### Escenario: Un punto de entrada vuelve a escanear

- **DADO** un punto de entrada que declara `@SpringBootApplication` o `@ComponentScan`
- **CUANDO** se ejecuta `./mvnw verify`
- **ENTONCES** la construcción falla señalando el punto de entrada y la anotación prohibida

#### Escenario: Los tres puntos de entrada cumplen la forma explícita

- **DADO** `AdminApplication`, `PortalApplication` y `WorkerApplication` en `bootstrap.admin`,
  `bootstrap.portal` y `bootstrap.worker`
- **CUANDO** se ejecuta `./mvnw verify`
- **ENTONCES** ninguno usa `@SpringBootApplication` ni `@ComponentScan`, `PortalApplication` y
  `WorkerApplication` excluyen `DataSourceAutoConfiguration`, `AdminApplication` no la excluye, y
  `ConfiaApplication` sigue arrancando cada proceso según `APP_PROFILE`

#### Escenario: El portal o el trabajador dejan de excluir el `DataSource`

- **DADO** `PortalApplication` o `WorkerApplication` sin la exclusión de
  `DataSourceAutoConfiguration`
- **CUANDO** se ejecuta `./mvnw verify`
- **ENTONCES** la construcción falla señalando el punto de entrada

## REMOVED Requirements

### Requisito: Ausencia de superficie HTTP que exija la cabecera de idempotencia (brecha con destino: cambio 7)

(Reason: la brecha se cierra. `web-edge-foundations` entrega el borde HTTP genérico de la
idempotencia, demostrado con un controlador que vive solo en el árbol de pruebas, y el escenario del
requisito deja de ser cierto como él mismo anuncia. La mitad HTTP de la brecha B7 la cumple la
capacidad `web-edge`.)
(Migration: el requisito positivo vive en `web-edge`, «Cabecera `Idempotency-Key` obligatoria en
los endpoints que mueven dinero» y siguientes. La prohibición de aplicarlo a endpoints de identidad
se conserva como regla de ArchUnit en este delta, «El mecanismo HTTP de idempotencia no se aplica a
ningún endpoint de identidad».)
