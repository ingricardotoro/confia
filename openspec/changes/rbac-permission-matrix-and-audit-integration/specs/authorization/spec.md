# Capacidad: Autorización por permisos

- **Identificador:** authorization
- **Estado:** pendiente de aprobación del propietario del producto
- **Fase:** F0
- **Cambio:** `rbac-permission-matrix-and-audit-integration` (cambio 8)
- **Propuesta:** `proposal.md`, aprobada por el propietario el 2026-11-03, con O1 a O10 y Q1 a Q5 vinculantes

## Propósito

Capacidad nueva. Especifica el comportamiento observable de la autorización del proceso administrativo: el catálogo
cerrado de los seis roles del personal y de sus permisos `módulo:verbo`, la matriz efectiva y su paridad con
`docs/03-seguridad.md` §5.2, la resolución de permisos por petición que falla cerrado, la exigencia de una sesión con
`otp` en las rutas con permiso, la lista cerrada de rutas con permiso y la guarda de cobertura de rutas, y la política
de auditoría de las denegaciones por permiso (qué cuenta, tope, resumen, métrica y fallo de la escritura). Cierra el
criterio de salida 2 de la fase 0 (`docs/09-roadmap-y-fases.md`): «Un usuario sin permiso recibe 403 y el intento
queda en `AuditLog`».

NO le corresponde: la tabla de asignación de rol, el adaptador del resolutor, la política de MFA y la ruta
`GET /api/v1/identity/roles` (capacidad `identity`); la posición de la decisión en la cadena de filtros y el manejador
de `403` (capacidad `web-edge`); la forma del asiento en la cadena de hash (capacidad `audit-trail`); y las reglas de
construcción (capacidad `build-integrity`).

Fuentes de verdad: `docs/03-seguridad.md` §4.3, §5.1 a §5.4; ADR-0005, ADR-0009, ADR-0017 y el ADR nuevo de
«Autorización por permisos resueltos por petición»; `CLAUDE.md` reglas 5, 9 y 14.

## Convenciones de esta especificación

- Los escenarios se numeran `AZ01` en adelante. Un escenario marcado «(solo documental)» no tiene aserción automática.
- Leyenda de la matriz (`docs/03` §5.2): **C** crear, **L** leer, **A** actualizar, **N** anular, **P** aprobar,
  **X** exportar; nueva: **S** solicitar anulación. Equivalen a los verbos `create`, `read`, `update`, `void`,
  `approve` y `export`, y al permiso de solicitud de anulación.
- Esta especificación describe comportamiento observable. Los nombres de clase, de paquete y de tabla los fija el
  diseño.
- **Valores que fija el diseño y que esta especificación no codifica:** el código exacto del permiso de solicitud de
  anulación (provisional `invoicing:request-void`); la ortografía exacta de los verbos `create`, `update`, `approve` y
  `export` (la propuesta cita literalmente `identity:read`, `audit:read`, `invoicing:void` y `ledger:update`); los
  códigos de rol almacenados; el tope y la ventana de las denegaciones; el momento de emisión del asiento de resumen;
  los nombres de las acciones del asiento de resumen y de las métricas; el `entity_type` del asiento; la forma del
  resultado del resolutor ante un fallo de lectura; el estado HTTP del fallo de infraestructura de la resolución; si el
  rechazo por falta de `otp` deja un asiento propio; el número de la migración, del ADR y de las pruebas.
- **Determinismo.** Toda prueba del tope, de la ventana y del criterio 2 DEBE ser determinista: no depende del reloj
  real, del orden de ejecución de otras pruebas ni de estado que otra prueba haya dejado. El diseño DEBE proveer un
  reloj inyectable para la ventana y un estado aislado por prueba para el contador de denegaciones.

## Requisitos

### Requisito: El catálogo de roles del personal es cerrado y tiene exactamente seis roles

El sistema DEBE definir en código un catálogo cerrado de roles del personal con exactamente seis: Super Admin,
Administrador, Cajero, Contabilidad, Auditor y Coordinador Académico (decisiones O1 y O10). El Super Admin DEBE ser
por institución, no de plataforma. El catálogo NO DEBE incluir ningún rol del Encargado ni del portal: el control de
acceso por roles del Encargado espera a F8. El sistema NO DEBE almacenar el catálogo en tablas globales de roles ni de
permisos (ADR-0017 no cambia).

#### Escenario: [AZ01] El catálogo contiene exactamente los seis roles

- **DADO** el catálogo de roles tal como lo entrega este cambio
- **CUANDO** se enumeran sus miembros
- **ENTONCES** son exactamente Super Admin, Administrador, Cajero, Contabilidad, Auditor y Coordinador Académico, y
  ningún otro

#### Escenario: [AZ02] No existe un rol del Encargado ni tablas globales de roles o permisos

- **DADO** el catálogo y el esquema migrado
- **CUANDO** se busca un rol del Encargado, del portal o de plataforma, y una tabla global de roles o de permisos
- **ENTONCES** no existe ninguno, y la lista de tablas permitidas sin `institution_id` de ADR-0017 no cambia

#### Escenario: [AZ03] Un código de rol desconocido no se reconoce

- **DADO** un código de rol que no pertenece al catálogo
- **CUANDO** el catálogo intenta resolverlo
- **ENTONCES** el resultado es «desconocido», nunca un rol por defecto ni un conjunto de permisos

### Requisito: El catálogo de permisos es cerrado y contiene exactamente los permisos que concede algún rol

El sistema DEBE definir en código un catálogo cerrado de permisos con la forma `módulo:verbo`. Los verbos DEBEN ser los
seis de `docs/03` §5.2 (`create`, `read`, `update`, `void`, `approve`, `export`), y el catálogo DEBE incluir además el
permiso de solicitud de anulación de factura, distinto de los seis verbos (decisión O2-1). El catálogo DEBE contener
exactamente los permisos que concede al menos un rol de la matriz efectiva, es decir, **89**: 88 combinaciones de
módulo y verbo y el permiso de solicitud de anulación. Un permiso que ningún rol tiene NO DEBE existir en el catálogo.
Los módulos DEBEN ser `identity`, `organization`, `students`, `guardians`, `catalog`, `scholarships`, `charges`,
`ledger`, `payments`, `cashbox`, `invoicing`, `collections`, `reconciliation`, `documents`, `reporting`,
`notifications`, `audit` y `settings`. El permiso de solicitud de anulación solo se declara y se asigna al Cajero; el
flujo de solicitud pendiente y su aprobación no pertenecen a este cambio.

#### Escenario: [AZ04] El catálogo tiene 89 permisos con la forma `módulo:verbo`

- **DADO** el catálogo de permisos
- **CUANDO** se cuentan y se examinan sus códigos
- **ENTONCES** hay exactamente 89, de los cuales 88 tienen un módulo de la lista y uno de los seis verbos, y uno es el
  permiso de solicitud de anulación
- **Y** no hay códigos repetidos

#### Escenario: [AZ05] Un permiso que ningún rol tiene no existe

- **DADO** que ningún rol tiene actualizar el libro mayor ni actualizar pagos ni actualizar facturas
- **CUANDO** se buscan `ledger:update`, `payments:update` e `invoicing:update` en el catálogo
- **ENTONCES** ninguno existe
- **Y** toda entrada del catálogo es concedida por al menos un rol de la matriz efectiva

#### Escenario: [AZ06] El permiso de solicitud de anulación es distinto de los seis verbos

- **DADO** el permiso de solicitud de anulación de factura
- **CUANDO** se compara con `invoicing:void`
- **ENTONCES** son dos entradas distintas del catálogo
- **Y** el permiso de solicitud lo concede únicamente el rol Cajero

### Requisito: La matriz efectiva de los seis roles es la de la propuesta aprobada

El sistema DEBE conceder a cada rol exactamente los permisos de la matriz siguiente, sin uno más ni uno menos. «—» es
sin acceso. Las restricciones de alcance dentro de un permiso («solo su caja», «académico») NO forman parte de la
matriz: son de cada caso de uso (autorización a nivel de objeto, `docs/03` §5.4).

| Módulo | Super Admin | Administrador | Cajero | Contabilidad | Auditor | Coord. Académico |
|---|---|---|---|---|---|---|
| `identity` (usuarios, roles) | C L A N P X | L | — | — | L | — |
| `organization` | C L A N X | C L A X | L | L | L X | C L A X |
| `students` | C L A N X | C L A X | L | L | L X | C L A X |
| `guardians` | C L A N X | C L A X | L | L | L X | L |
| `catalog` | C L A N X | C L A X | L | L X | L X | L |
| `scholarships` | C L A N P X | C L A P X | L | L X | L X | C L |
| `charges` | C L A N P X | C L N P X | L | L X | L X | L |
| `ledger` | L N P X | L N X | L | L X | L X | — |
| `payments` | C L N P X | L N P X | C L | L N X | L X | — |
| `cashbox` | C L N P X | L P X | C L | L X | L X | — |
| `invoicing` | C L N P X | L P X | C L S | C L N P X | L X | — |
| `collections` | C L A N P X | C L A P X | L | L X | L X | L |
| `reconciliation` | C L A P X | L P X | — | C L A P X | L X | — |
| `documents` | C L A N P X | C L A P X | L | — | L X | C L A P X |
| `reporting` | L X | L X | L | L X | L X | L X |
| `notifications` | C L A N X | C L A X | L | — | L X | C L |
| `audit` (bitácora) | L X | — | — | — | L X | — |
| `settings` | C L A N X | L A | — | L | L X | — |

La MFA propia de la cuenta (`identity`, MFA propia) NO es una fila de permisos: son rutas de autoservicio para los seis
roles y no figuran en el catálogo.

#### Escenario: [AZ07] El Super Admin tiene los 88 permisos de módulo y verbo y no el de solicitud

- **DADO** el rol Super Admin
- **CUANDO** se listan sus permisos efectivos
- **ENTONCES** son exactamente 88, todos los de módulo y verbo del catálogo, y no incluye el permiso de solicitud de
  anulación

#### Escenario: [AZ08] El Cajero solicita la anulación pero no anula

- **DADO** el rol Cajero
- **CUANDO** se consultan los permisos de `invoicing`
- **ENTONCES** tiene `invoicing:create`, `invoicing:read` y el permiso de solicitud de anulación
- **Y** no tiene `invoicing:void`, y `invoicing:void` solo lo tienen Contabilidad y Super Admin

#### Escenario: [AZ09] El Administrador no cobra ni lee la bitácora

- **DADO** el rol Administrador
- **CUANDO** se consultan sus permisos de `payments` y de `audit`
- **ENTONCES** tiene `payments:read`, `payments:void`, `payments:approve` y `payments:export`, y no tiene
  `payments:create`
- **Y** no tiene ningún permiso de `audit`

#### Escenario: [AZ10] El Auditor solo lee y exporta

- **DADO** el rol Auditor
- **CUANDO** se listan sus permisos efectivos
- **ENTONCES** todos tienen el verbo `read` o `export`, y ninguno tiene `create`, `update`, `void` ni `approve`
- **Y** tiene `read` en los 18 módulos y `export` en todos salvo `identity`

#### Escenario: [AZ11] La lectura y exportación de la bitácora son solo del Super Admin y del Auditor

- **DADO** el catálogo y la matriz efectiva
- **CUANDO** se buscan los roles que tienen `audit:read` y `audit:export`
- **ENTONCES** son exactamente Super Admin y Auditor, para ambos permisos

#### Escenario: [AZ12] `identity:read` lo tienen tres roles y la MFA propia no es un permiso

- **DADO** la matriz efectiva
- **CUANDO** se buscan los roles con `identity:read` y un permiso de MFA propia
- **ENTONCES** `identity:read` lo tienen exactamente Super Admin, Administrador y Auditor
- **Y** no existe ningún permiso del catálogo para la MFA propia

#### Escenario: [AZ13] Un alcance dentro de un permiso no es parte de la matriz

- **DADO** el rol Cajero con `reporting:read` y el rol Coordinador Académico con `reporting:read` y `reporting:export`
- **CUANDO** se inspecciona el catálogo
- **ENTONCES** no existe ningún permiso «de su caja» ni «académico», y el permiso es el mismo `reporting:read`

### Requisito: Las cuatro diferencias con `docs/03` §5.2 son explícitas y una prueba de paridad compara el catálogo con el documento

La matriz efectiva DEBE diferir de la tabla de `docs/03` §5.2 solo en cuatro puntos, todos registrados por nota fechada
en el documento: (1) el Cajero gana el permiso de solicitud de anulación en `invoicing`; (2) el Administrador pierde
`audit:read`; (3) el Administrador pierde `payments:create`; (4) la MFA propia pasa a ser autoservicio y no una fila de
permisos. El sistema DEBE tener una prueba de paridad, al estilo de `DocumentationNotesTest`, que compare el catálogo
en código con la matriz efectiva publicada en `docs/03` §5.2 por nota fechada, celda por celda: un permiso cambiado en
un lado y no en el otro DEBE romper la construcción. La matriz vive en código y no en una migración de semilla. La
columna del Encargado queda solo documentada para F8 y no entra en la comparación.

#### Escenario: [AZ14] El catálogo coincide con la matriz del documento celda por celda

- **DADO** el catálogo en código y la matriz efectiva publicada en `docs/03` §5.2
- **CUANDO** se ejecuta la prueba de paridad
- **ENTONCES** para cada uno de los seis roles y cada uno de los 18 módulos, los permisos del código y los del
  documento son idénticos
- **Y** la prueba evalúa 6 roles por 18 módulos y no un conjunto vacío

#### Escenario: [AZ15] Un cambio en un solo lado rompe la construcción

- **DADO** una copia de prueba del catálogo a la que se agrega `payments:create` al Administrador, y otra copia del
  documento a la que se quita `audit:export` del Auditor
- **CUANDO** se ejecuta la prueba de paridad sobre cada una
- **ENTONCES** falla en las dos, señalando el rol, el módulo y el permiso que difieren

#### Escenario: [AZ16] Las cuatro diferencias figuran en una nota fechada (solo documental)

- **DADO** `docs/03-seguridad.md` §5.2
- **CUANDO** se lee la nota fechada de este cambio
- **ENTONCES** declara las cuatro diferencias, la regla 3 reescrita («el Auditor es el único rol de solo lectura
  absoluta»), el párrafo final (la matriz vive en código y la prueba de paridad la compara) y los iniciadores sin
  permiso de §5.3 (ajuste manual del libro mayor, baja por incobrable y reembolso) anotados como pendientes de los
  cambios que construyan cada operación
- **Y** el texto original de la tabla y de las reglas no se reescribe

### Requisito: Los permisos se resuelven por petición desde la base de datos, en solo lectura, y la resolución falla cerrada

El sistema DEBE resolver los permisos de la cuenta autenticada en cada petición a una ruta con permiso, a través de un
puerto de `shared.security` implementado por `identity`, con una consulta parametrizada de solo lectura. La
resolución DEBE usar la institución del token (ADR-0009) y nunca una institución recibida del cliente. NO DEBE haber
caché: un cambio de rol surte efecto en la petición siguiente. La resolución DEBE fallar cerrada: sin rol asignado, con
un código de rol desconocido, o con una asignación de otra institución, la cuenta NO DEBE tener ningún permiso. Ante un
error de lectura, la resolución NO DEBE conceder ningún permiso y DEBE señalar el fallo de forma distinguible de «sin
permisos»: ese caso se rige por el requisito de fallo de infraestructura de esta capacidad. El token y el principal NO
DEBEN llevar permisos (ADR nuevo, que reemplaza la parte de ADR-0005 sobre permisos en el token).

#### Escenario: [AZ17] Una cuenta con rol recibe exactamente los permisos de su rol

- **DADO** la cuenta `ACC-1` de la institución `INS-1` con el rol Contabilidad asignado
- **CUANDO** se resuelven sus permisos
- **ENTONCES** son exactamente los de Contabilidad de la matriz efectiva

#### Escenario: [AZ18] Sin rol asignado no hay ningún permiso

- **DADO** la cuenta `ACC-2` de `INS-1` sin ninguna fila de asignación
- **CUANDO** se resuelven sus permisos
- **ENTONCES** el conjunto es vacío y el resultado es una resolución exitosa de «ningún permiso», no un fallo

#### Escenario: [AZ19] Un código de rol desconocido no concede nada

- **DADO** una fila de asignación de `ACC-3` cuyo código de rol no pertenece al catálogo (creada con la restricción
  de la tabla desactivada solo para la prueba)
- **CUANDO** se resuelven sus permisos
- **ENTONCES** el conjunto es vacío y no se lanza una excepción no controlada

#### Escenario: [AZ20] Una asignación de otra institución no se usa

- **DADO** la cuenta `ACC-4` de `INS-1` sin rol, y una cuenta `ACC-4` de la institución `INS-2` con el rol Super Admin
- **CUANDO** se resuelven los permisos de `ACC-4` con el token de `INS-1`
- **ENTONCES** el conjunto es vacío y nunca contiene permisos de `INS-2`

#### Escenario: [AZ21] Un error de lectura no concede permisos y se distingue de «sin permisos»

- **DADO** una lectura de la base de datos que lanza un error de infraestructura
- **CUANDO** se resuelven los permisos
- **ENTONCES** no se devuelve ningún permiso
- **Y** el resultado es distinguible de la resolución exitosa con conjunto vacío del escenario AZ18

#### Escenario: [AZ22] Un cambio de rol surte efecto en la petición siguiente

- **DADO** la cuenta `ACC-5` con el rol Auditor y una petición a una ruta que exige `identity:read`, aceptada
- **CUANDO** el rol de `ACC-5` pasa a Cajero y se repite la petición con el mismo token
- **ENTONCES** la segunda petición se rechaza con `403 forbidden`, sin esperar a que venza el token

#### Escenario: [AZ23] La resolución es de solo lectura

- **DADO** la resolución de permisos
- **CUANDO** se ejecuta y se intenta una escritura en la misma transacción
- **ENTONCES** la base de datos rechaza la escritura por ser una transacción de solo lectura

#### Escenario: [AZ24] El token no lleva permisos y una claim `permissions` se rechaza

- **DADO** un token firmado con la clave vigente, con las nueve claims correctas y una claim `permissions` con un
  arreglo vacío
- **CUANDO** se presenta a una ruta de la lista autenticada
- **ENTONCES** la respuesta es `401` con el código `token-invalid`
- **Y** el principal autenticado no expone ningún permiso ni rol

### Requisito: Toda ruta con permiso exige una sesión completa con `otp` en `amr`

El sistema DEBE exigir, en toda ruta de la lista cerrada de rutas con permiso, que el token de acceso lleve `otp` en
`amr` (sesión completa tras el segundo factor). Una petición cuyo `amr` no contiene `otp` NO DEBE llegar al
controlador ni a la consulta del resolutor, y DEBE responder `403` con el código `forbidden`. Este rechazo NO es una
denegación por permiso, de modo que no cuenta para el tope ni escribe el asiento `authorization.access.denied`. Una
petición sin credencial DEBE responder `401 authentication-required`. Un token restringido NO DEBE abrir una ruta con
permiso. Es un valor que fija el diseño si el rechazo por falta de `otp` deja un asiento propio con otra acción.

#### Escenario: [AZ25] Un token `[pwd]` no abre una ruta con permiso

- **DADO** un token de acceso con `amr` igual a `[pwd]` de una cuenta cuyo rol tiene el permiso de la ruta
- **CUANDO** se envía la petición
- **ENTONCES** la respuesta es `403` con `forbidden`, el controlador registra cero invocaciones y el resolutor
  registra cero invocaciones
- **Y** no existe ningún asiento `authorization.access.denied`

#### Escenario: [AZ26] Un token `[pwd, otp]` con el permiso llega al controlador

- **DADO** un token con `amr` igual a `[pwd, otp]` de una cuenta cuyo rol tiene el permiso de la ruta
- **CUANDO** se envía la petición
- **ENTONCES** el controlador registra una invocación y la respuesta es la del controlador

#### Escenario: [AZ27] Sin credencial una ruta con permiso responde 401

- **DADO** una ruta de la lista de rutas con permiso
- **CUANDO** se envía sin credencial
- **ENTONCES** la respuesta es `401` con `authentication-required`, el controlador registra cero invocaciones y no se
  escribe ningún asiento

#### Escenario: [AZ28] Un token restringido no abre una ruta con permiso

- **DADO** un token restringido `mfa-verify` y otro `mfa-enroll`
- **CUANDO** cada uno se presenta a una ruta con permiso
- **ENTONCES** ambos se rechazan sin llegar al controlador ni al resolutor, y no se escribe ningún asiento de
  denegación

### Requisito: La lista de rutas con permiso es cerrada y cada entrada nombra un permiso del catálogo

El sistema DEBE declarar las rutas con permiso como una lista cerrada, definida en un único lugar, con método,
plantilla de ruta y permiso por entrada. La lista DEBE contener, en producción y en este cambio, exactamente
`GET /api/v1/identity/roles` con `identity:read`; las rutas del árbol de pruebas se suman solo en el árbol de pruebas.
Las rutas de autoservicio (sesiones propias y MFA propia) DEBEN seguir en la lista de rutas autenticadas, que no son
permisos del catálogo. Toda entrada DEBE nombrar un permiso que exista en el catálogo. La decisión de permiso DEBE
hacerse en el servidor con los datos resueltos por el servidor: una afirmación de permiso enviada por el cliente
(cabecera, parámetro o cuerpo) NO DEBE influir.

#### Escenario: [AZ29] El contenido de producción de la lista

- **DADO** la lista de rutas con permiso tal como la entrega este cambio, sin las rutas del árbol de pruebas
- **CUANDO** se enumeran sus entradas
- **ENTONCES** contiene exactamente `GET /api/v1/identity/roles` con `identity:read`

#### Escenario: [AZ30] Una entrada con un permiso fuera del catálogo impide arrancar

- **DADO** una lista de prueba con una entrada cuyo permiso no figura en el catálogo
- **CUANDO** arranca el proceso administrativo con esa lista
- **ENTONCES** el arranque falla señalando la entrada

#### Escenario: [AZ31] El cliente no puede afirmar un permiso

- **DADO** un usuario con el rol Cajero y una petición a una ruta que exige `identity:read` con la cabecera
  `X-Permission: identity:read`, el parámetro `permission=identity:read` y el cuerpo `{"claimedPermission":"identity:read"}`
- **CUANDO** se envía
- **ENTONCES** la respuesta es `403 forbidden` y el controlador registra cero invocaciones

#### Escenario: [AZ32] Las variantes de la ruta ni eluden la decisión ni cambian el permiso

- **DADO** la ruta `GET /api/v1/identity/roles` y un usuario sin `identity:read`
- **CUANDO** se piden `/api/v1/identity/roles/`, `/API/V1/IDENTITY/ROLES`, `/api/v1/identity/roles;a=b`,
  `/api/v1/identity/./roles` y `/api/v1/identity/other/../roles`
- **ENTONCES** ninguna respuesta es `2xx` y el controlador registra cero invocaciones

#### Escenario: [AZ33] Otro método sobre una ruta con permiso cae en la denegación final

- **DADO** un usuario con `identity:read` y la ruta `/api/v1/identity/roles`
- **CUANDO** se envía `POST`, `PUT`, `PATCH` y `DELETE` a esa ruta
- **ENTONCES** las respuestas no son `2xx` y el controlador registra cero invocaciones
- **Y** no se escribe ningún asiento `authorization.access.denied`, porque la ruta y método no están en la lista

### Requisito: La guarda de cobertura de rutas hace fallar el arranque y una prueba la repite sobre OpenAPI

El proceso administrativo DEBE recorrer al arrancar el mapa de rutas registrado y DEBE fallar el arranque, señalando la
ruta, si una ruta registrada no es pública, de autoservicio, restringida ni con permiso (`docs/03` §5.1). El arranque
también DEBE fallar si una entrada de la lista de rutas con permiso no corresponde a una ruta registrada. La guarda DEBE
comparar contra el mapa real de rutas, de modo que una plantilla con variables de ruta (`/x/{id}`) coincida con sus
peticiones. Una prueba DEBE enumerar las operaciones del documento OpenAPI administrativo y verificar lo mismo. La lista
pública DEBE seguir siendo corta y revisada. La guarda DEBE existir solo en el proceso administrativo.

#### Escenario: [AZ34] Una ruta sin clasificar impide arrancar

- **DADO** un controlador de prueba cuya ruta no es pública, ni de autoservicio, ni restringida, ni con permiso
- **CUANDO** arranca el proceso administrativo
- **ENTONCES** el arranque falla con un mensaje que nombra el método y la ruta

#### Escenario: [AZ35] Una entrada de la lista sin ruta registrada impide arrancar

- **DADO** una entrada con permiso `GET /api/v1/identity/ghost` sin ningún controlador registrado
- **CUANDO** arranca el proceso administrativo
- **ENTONCES** el arranque falla señalando la entrada

#### Escenario: [AZ36] El proceso real arranca con todas sus rutas clasificadas

- **DADO** el proceso administrativo arrancado por su camino de producción
- **CUANDO** se ejecuta la guarda
- **ENTONCES** pasa, y evalúa un conjunto no vacío de rutas

#### Escenario: [AZ37] Una plantilla con variable de ruta coincide con sus peticiones

- **DADO** una ruta con permiso de prueba `GET /test/accounts/{id}`
- **CUANDO** se piden `/test/accounts/123` y `/test/accounts/abc` con un usuario sin el permiso
- **ENTONCES** ambas reciben `403 forbidden` por la entrada de la lista, no por la denegación final

#### Escenario: [AZ38] La prueba sobre OpenAPI falla ante una operación sin clasificar

- **DADO** una operación del documento OpenAPI que no figura en ninguna de las cuatro clases
- **CUANDO** se ejecuta la prueba de enumeración de OpenAPI
- **ENTONCES** falla señalando la operación, y con el documento real pasa evaluando un conjunto no vacío

#### Escenario: [AZ39] El portal y el trabajador no ejecutan la guarda ni cargan beans de autorización

- **DADO** los procesos del portal y del trabajador arrancados por su camino de producción
- **CUANDO** se inspecciona cada contexto
- **ENTONCES** ninguno contiene la guarda, la lista de rutas con permiso, el decisor de permisos ni el resolutor

### Requisito: Una denegación por permiso escribe exactamente un asiento `authorization.access.denied` en su propia transacción

Cuando un actor autenticado, con sesión completa, no tiene el permiso de una ruta de la lista, el sistema DEBE
responder `403` con `forbidden`, DEBE dejar el controlador sin invocar y DEBE escribir exactamente un asiento de
auditoría con `action` igual a `authorization.access.denied`, `outcome` igual a `denied`, el código del permiso como
`entity_id`, la plantilla de ruta de la lista (nunca la ruta cruda), `request_id` igual al `traceId` del Problem
Details y la institución del token. El asiento DEBE escribirse en su propia transacción por el único componente
transaccional, fuera de toda transacción de negocio, de modo que ninguna reversión de negocio lo deshaga. `actor_kind`
DEBE ser `staff`, y `actor_label` el identificador de la cuenta, nunca el correo. El asiento DEBE conservar el origen de
la petición (IP y agente de usuario acotado) por los mecanismos existentes. La cadena de hash DEBE verificar tras
los asientos de denegación.

#### Escenario: [AZ40] Un actor sin el permiso deja exactamente un asiento

- **DADO** una cuenta `ACC-6` de `INS-1` con el rol Cajero y sesión `[pwd, otp]`, y una ruta que exige `identity:read`
- **CUANDO** envía la petición
- **ENTONCES** la respuesta es `403` con el código `forbidden` y el controlador registra cero invocaciones
- **Y** existe exactamente un asiento de `INS-1` con `action` `authorization.access.denied`, `outcome` `denied`,
  `actor_kind` `staff`, `actor_id` igual a `ACC-6` y `entity_id` igual a `identity:read`

#### Escenario: [AZ41] El `request_id` coincide con el `traceId` de la respuesta

- **DADO** la denegación del escenario anterior
- **CUANDO** se compara el `traceId` del cuerpo Problem con el `request_id` del asiento
- **ENTONCES** son idénticos

#### Escenario: [AZ42] El `actor_label` es el identificador de la cuenta, no el correo

- **DADO** la cuenta `ACC-6` con el correo `luis.cruz@colegio.edu.hn`
- **CUANDO** se lee el asiento de la denegación
- **ENTONCES** `actor_label` es el identificador de la cuenta y ningún campo del asiento contiene el correo

#### Escenario: [AZ43] El asiento se escribe en su propia transacción y no se revierte

- **DADO** una ruta con permiso de prueba y una transacción de negocio que se revertiría si se abriera
- **CUANDO** un actor sin el permiso la solicita
- **ENTONCES** el asiento existe después de la respuesta, y al escribirse no había ninguna transacción de negocio
  abierta

#### Escenario: [AZ44] El asiento lleva la plantilla de ruta, no la ruta cruda

- **DADO** una ruta con permiso `GET /test/accounts/{id}` y una petición a `/test/accounts/SENTINEL-ID-9`
- **CUANDO** se lee el asiento
- **ENTONCES** el detalle contiene la plantilla `/test/accounts/{id}` y no contiene `SENTINEL-ID-9`

#### Escenario: [AZ45] La cadena de hash verifica tras los asientos de denegación

- **DADO** una institución con asientos de denegación escritos
- **CUANDO** se invoca el verificador de cadena
- **ENTONCES** reporta la cadena íntegra

#### Escenario: [AZ46] Un acceso concedido no deja asiento de denegación

- **DADO** un actor con el permiso de la ruta
- **CUANDO** envía la petición
- **ENTONCES** la respuesta es la del controlador y no existe ningún asiento `authorization.access.denied`

### Requisito: Solo las denegaciones por permiso se auditan como tales

El sistema NO DEBE escribir el asiento `authorization.access.denied` ante: una ruta sin mapear rechazada por la
denegación final; el rechazo de CSRF o de `Origin`; el `404` a nivel de objeto; ninguna respuesta `401`; el rechazo por
falta de `otp`; ni el fallo de infraestructura de la resolución. Estos casos conservan su respuesta propia.

#### Escenario: [AZ47] Ruta sin mapear

- **DADO** un principal autenticado con sesión completa y una ruta fuera de todas las listas
- **CUANDO** envía la petición
- **ENTONCES** la respuesta es `403 forbidden`, el controlador registra cero invocaciones y no existe ningún asiento
  `authorization.access.denied`

#### Escenario: [AZ48] Rechazo de CSRF

- **DADO** una ruta de cookie y una petición con `Origin` no permitido
- **CUANDO** se envía
- **ENTONCES** la respuesta es `403` con `csrf-invalid` y no existe ningún asiento `authorization.access.denied`

#### Escenario: [AZ49] `404` a nivel de objeto

- **DADO** un caso de uso de prueba que responde `404 resource-not-found` a un objeto de otra institución
- **CUANDO** un actor con el permiso lo solicita
- **ENTONCES** la respuesta es `404` y no existe ningún asiento `authorization.access.denied`

#### Escenario: [AZ50] Las respuestas `401` no se auditan como denegación

- **DADO** una ruta con permiso y peticiones sin credencial, con token vencido y con firma alterada
- **CUANDO** se envían
- **ENTONCES** las respuestas son `401` y no existe ningún asiento `authorization.access.denied`

### Requisito: El asiento y los registros de una denegación no contienen la ruta cruda, la consulta, el cuerpo ni las cabeceras

El sistema NO DEBE incluir en el asiento de denegación, en el asiento de resumen, en las métricas ni en los registros
de la aplicación la ruta cruda, la cadena de consulta, el cuerpo de la petición ni las cabeceras (salvo la IP y el
agente de usuario acotado que ya captura el origen de la petición), ni ningún valor de variable de ruta. Es un valor que
fija el diseño si el agente de usuario acotado permanece en el asiento de denegación.

#### Escenario: [AZ51] Una búsqueda de centinelas no encuentra fugas

- **DADO** una petición denegada con la variable de ruta `SENTINEL-PATH-1`, la consulta `?q=SENTINEL-QUERY-2`, el
  cuerpo `{"x":"SENTINEL-BODY-3"}` y las cabeceras `X-Test: SENTINEL-HEADER-4` y `Authorization` con el token
- **CUANDO** se busca cada centinela y el token en el asiento, en las métricas emitidas y en los registros capturados
- **ENTONCES** ninguno aparece

### Requisito: El tope por usuario y permiso en una ventana limita las filas completas y produce una fila de resumen y una métrica

El sistema DEBE contar las denegaciones por permiso por cuenta y permiso en una ventana acotada en memoria. Hasta el
tope configurado, cada denegación DEBE escribir su asiento completo. Pasado el tope dentro de la ventana, las
denegaciones adicionales NO DEBEN escribir una fila completa; el sistema DEBE escribir una fila de resumen con el
conteo de denegaciones omitidas, la cuenta, el código del permiso y la ventana, y DEBE emitir una métrica. Todas las
denegaciones DEBEN responder `403 forbidden` con independencia del tope. El contador DEBE ser por cuenta y permiso: dos
cuentas, o dos permisos de la misma cuenta, llevan contadores independientes. Al vencer la ventana el contador DEBE
reiniciarse. El estado en memoria DEBE tener una cota de tamaño. El tope en memoria es por proceso (declarado en el
diseño). Los valores del tope, de la ventana, de la cota y del momento de emisión del resumen los fija el diseño; los
escenarios usan valores de prueba. La ventana DEBE usar un reloj inyectable y las pruebas un estado aislado.

#### Escenario: [AZ52] Hasta el tope, filas completas

- **DADO** un tope de prueba `T` igual a 3, un reloj manual y un contador vacío
- **CUANDO** una cuenta sin permiso es denegada 3 veces dentro de la ventana
- **ENTONCES** existen exactamente 3 asientos completos de esa cuenta y ese permiso, y no existe fila de resumen

#### Escenario: [AZ53] Pasado el tope, resumen y métrica, y siempre 403

- **DADO** el tope de prueba `T` igual a 3 y 5 denegaciones de la misma cuenta y permiso dentro de la ventana
- **CUANDO** se hace avanzar el reloj manual hasta el cierre de la ventana y se dispara la emisión de resúmenes
  pendientes (el mecanismo lo fija el diseño)
- **ENTONCES** existen exactamente 3 asientos completos y exactamente una fila de resumen con el conteo omitido 2, la
  cuenta y el código del permiso
- **Y** la métrica de omisión se incrementó en 2 y las 5 respuestas fueron `403 forbidden`

#### Escenario: [AZ54] Los contadores son independientes por cuenta y por permiso

- **DADO** el tope de prueba `T` igual a 3, y 3 denegaciones de la cuenta `ACC-6` por `identity:read`
- **CUANDO** `ACC-7` es denegada por `identity:read` y `ACC-6` es denegada por otro permiso
- **ENTONCES** ambas denegaciones escriben su asiento completo

#### Escenario: [AZ55] Al vencer la ventana el contador se reinicia

- **DADO** el tope de prueba `T` igual a 3, la ventana de prueba `V` y 3 asientos completos escritos
- **CUANDO** el reloj manual avanza más de `V` y la cuenta es denegada otra vez
- **ENTONCES** la denegación escribe un asiento completo

#### Escenario: [AZ56] El estado en memoria está acotado

- **DADO** una cota de prueba de 10 claves y denegaciones de 25 cuentas distintas
- **CUANDO** se inspecciona el tamaño del estado
- **ENTONCES** nunca supera la cota y todas las respuestas son `403 forbidden`
- **Y** el comportamiento de una clave nueva con el estado lleno lo fija el diseño

#### Escenario: [AZ57] El tope y la ventana se prueban sin reloj real ni estado compartido

- **DADO** las pruebas del tope
- **CUANDO** se ejecutan en cualquier orden y repetidas
- **ENTONCES** dan el mismo resultado, porque usan un reloj inyectado, no esperan tiempo real y no heredan el contador
  de otra prueba

### Requisito: La métrica de denegaciones y de errores se publica por un puerto

El sistema DEBE publicar la métrica de denegaciones omitidas y la métrica de error de la escritura del asiento por un
puerto, con un adaptador de producción por registro estructurado (precedente de las métricas de cola), y la prueba DEBE
poder sustituir el puerto por un doble. Las etiquetas de la métrica NO DEBEN contener datos personales ni la ruta cruda.

#### Escenario: [AZ58] La métrica llega al puerto sin datos personales

- **DADO** un doble del puerto y denegaciones de la cuenta `ACC-6`
- **CUANDO** se supera el tope
- **ENTONCES** el doble recibe la métrica con el código del permiso, sin correo, sin ruta cruda y sin IP

### Requisito: Si falla la escritura del asiento de denegación, la respuesta sigue siendo `403 forbidden`

Si la escritura del asiento de denegación falla, el sistema DEBE responder igualmente `403` con `forbidden`, NO DEBE
invocar el controlador, NO DEBE responder `500`, y DEBE emitir una métrica de error.

#### Escenario: [AZ59] Una auditoría que falla no cambia la respuesta

- **DADO** un escritor de bitácora que lanza una excepción al insertar
- **CUANDO** un actor sin el permiso solicita la ruta
- **ENTONCES** la respuesta es `403` con `forbidden`, el controlador registra cero invocaciones y la métrica de error
  se emitió una vez
- **Y** el cuerpo no contiene el mensaje ni el nombre de la excepción

### Requisito: Un fallo de infraestructura de la resolución deniega el acceso pero no es una denegación por permiso

Si la consulta del resolutor falla por una causa de infraestructura, el sistema DEBE denegar el acceso (falla cerrado):
el controlador NO DEBE invocarse. El fallo NO es una denegación por permiso: NO DEBE escribir el asiento
`authorization.access.denied`, NO DEBE contar para el tope de ninguna cuenta y NO DEBE responder `403 forbidden`, para
no aparentar que el usuario carece del permiso. La respuesta DEBE ser un Problem Details construido con el
tratamiento existente de un fallo de dependencia, sin detalles internos (hoy `500` con `internal-error`; el estado
exacto, y si hace falta un código nuevo con `503`, lo fija el diseño, que además DEBE resolver cómo el filtro produce
esa respuesta, porque una excepción del filtro no llega al manejador de excepciones de los controladores). El fallo DEBE
quedar observable para operación sin datos personales. El primer acceso tras recuperarse la base de datos DEBE
resolverse con normalidad.

#### Escenario: [AZ60] El fallo de lectura no es una denegación por permiso

- **DADO** una cuenta con el rol Auditor y una lectura de la base de datos que falla durante la resolución
- **CUANDO** envía una petición a una ruta que exige `identity:read`
- **ENTONCES** el controlador registra cero invocaciones
- **Y** la respuesta es un Problem Details cuyo código no es `forbidden`
- **Y** no existe ningún asiento `authorization.access.denied` y el contador del tope de esa cuenta y permiso no varió

#### Escenario: [AZ61] El fallo no revela detalles internos

- **DADO** el fallo de lectura del escenario anterior con un mensaje de excepción que contiene `SENTINEL-DB-5`
- **CUANDO** se inspecciona la respuesta
- **ENTONCES** `type`, `title` y `detail` salen del catálogo y no contienen `SENTINEL-DB-5` ni el nombre de la clase

#### Escenario: [AZ62] El fallo no consume el tope

- **DADO** el tope de prueba `T` igual a 3 y 10 fallos de resolución de la misma cuenta
- **CUANDO** después se producen 3 denegaciones reales por permiso de esa cuenta
- **ENTONCES** las 3 escriben su asiento completo

#### Escenario: [AZ63] El estado HTTP del fallo es el del tratamiento existente

- **DADO** el catálogo de códigos de error de `web-edge`
- **CUANDO** se produce el fallo de resolución
- **ENTONCES** el estado y el código son los que fija el diseño (hoy `500` con `internal-error`) y ninguno es `403`

#### Escenario: [AZ64] Tras recuperarse la base de datos, el acceso se resuelve con normalidad

- **DADO** un fallo de resolución seguido de la recuperación de la lectura
- **CUANDO** el mismo actor, con el permiso, repite la petición
- **ENTONCES** el controlador registra una invocación y la respuesta es la del controlador

### Requisito: Criterio de salida 2 de F0, demostrado de extremo a extremo con sus controles

El sistema DEBE tener una prueba de integración con el proceso administrativo real (`HarnessProcess`), PostgreSQL real
y un token real de inicio de sesión con MFA, sobre un controlador del árbol de pruebas y sobre la ruta real
`GET /api/v1/identity/roles`: un usuario sin el permiso recibe `403 forbidden`, el controlador se invoca cero veces y
existe exactamente un asiento `denied` cuyo `request_id` coincide con el `traceId`. La prueba DEBE incluir los
controles positivos y negativos de los escenarios siguientes y DEBE ser determinista (reloj inyectado, estado aislado,
datos propios).

#### Escenario: [AZ65] Criterio 2: sin el permiso, 403 y un asiento

- **DADO** una cuenta con el rol Cajero que completó el inicio de sesión con contraseña y código TOTP
- **CUANDO** envía `GET /api/v1/identity/roles` con su token de acceso
- **ENTONCES** la respuesta es `403` con `forbidden`, el controlador registra cero invocaciones
- **Y** existe exactamente un asiento `denied` con `action` `authorization.access.denied` y `entity_id`
  `identity:read`, cuyo `request_id` coincide con el `traceId` de la respuesta

#### Escenario: [AZ66] Control positivo: con el permiso, 200 y ningún asiento de denegación

- **DADO** una cuenta con el rol Auditor, con sesión completa
- **CUANDO** envía la misma petición
- **ENTONCES** la respuesta es `200` y no existe ningún asiento `authorization.access.denied` de esa cuenta

#### Escenario: [AZ67] El verificador de cadena sigue pasando

- **DADO** los asientos de los dos escenarios anteriores
- **CUANDO** se invoca el verificador de cadena de la institución
- **ENTONCES** reporta la cadena íntegra

#### Escenario: [AZ68] Control de centinelas en el flujo real

- **DADO** la petición denegada con centinelas en la consulta y en cabeceras ajenas
- **CUANDO** se buscan en `shared_audit_log` y en los registros capturados
- **ENTONCES** no aparece ninguno

#### Escenario: [AZ69] Control del camino de fallo y del tope en el flujo real

- **DADO** el flujo real con un escritor de bitácora que falla en una prueba, y con un tope de prueba `T` igual a 2
  en otra
- **CUANDO** el rol Cajero solicita la ruta
- **ENTONCES** en la primera la respuesta sigue siendo `403 forbidden`, y en la segunda la tercera denegación produce
  la fila de resumen y la métrica

#### Escenario: [AZ70] Control determinista

- **DADO** la prueba del criterio 2
- **CUANDO** se ejecuta aislada, repetida y tras otra prueba que denegó a la misma cuenta
- **ENTONCES** el resultado es el mismo, porque usa su propia institución, un reloj inyectado y un contador aislado

### Requisito: La documentación registra las decisiones con notas fechadas y el criterio 2 cita su prueba

El cambio DEBE registrar, con notas fechadas y sin reescribir cuerpos originales: `docs/03` §4.3 (MFA obligatoria para
los seis roles, derivada de los roles), §5.1 (un rol por institución, lista cerrada en el filtro en lugar de
anotación, permisos resueltos por petición), §5.2 (matriz efectiva, regla 3, permiso de solicitud, matriz en código) y
§5.3 (el informe trimestral revisa operaciones, no pares de permisos). DEBE existir un ADR nuevo, «Autorización por
permisos resueltos por petición», con el siguiente número libre, listado en `docs/adr/README.md`, que reemplaza la parte
de ADR-0005 sobre permisos en el token, el retraso de propagación y la regla de MFA por rol. ADR-0005 DEBE conservar su
texto original con una nota fechada de remisión. `docs/09-roadmap-y-fases.md` DEBE marcar el criterio de salida 2 con
su prueba de cierre.

#### Escenario: [AZ71] Las notas están fechadas y no reescriben el cuerpo (solo documental)

- **DADO** `docs/03-seguridad.md` y ADR-0005
- **CUANDO** se comparan con su versión anterior
- **ENTONCES** el texto original de cada sección y del ADR no cambia y cada nota nueva lleva fecha

#### Escenario: [AZ72] El ADR nuevo está indexado

- **DADO** `docs/adr/README.md`
- **CUANDO** se lee el índice
- **ENTONCES** lista el ADR nuevo con su número, estado y fecha, y ADR-0005 enlaza al nuevo mediante una nota fechada

#### Escenario: [AZ73] El criterio 2 de `docs/09` cita su prueba (solo documental)

- **DADO** `docs/09-roadmap-y-fases.md`
- **CUANDO** se lee el criterio de salida 2
- **ENTONCES** figura marcado y nombra la prueba de integración de cierre

### Requisito: Ausencia de la gestión de roles y cuentas, de la lectura de la bitácora y del control de acceso del portal (brecha con destino: cambio 8b y F8)

El sistema NO DEBE incluir, en este cambio: la asignación de roles por endpoint ni la regla «un usuario nunca modifica
su propio rol» (§5.3); la desactivación de cuentas ni el motivo de revocación `admin_revoke`; la revocación de sesiones
al cambiar un rol; la inscripción ni la retirada voluntarias de MFA; la reautenticación reforzada; el restablecimiento
administrativo de contraseña; la revisión DA-15; la creación del primer Super Admin; ni el endpoint de lectura de la
bitácora con `audit:read`. Todo ello es del cambio **8b**. Los datos ficticios de desarrollo quedan fuera, con dueño a
confirmar. El control de acceso por roles del Encargado y sus permisos `L*`/`A*` son de **F8**. Los roles solo se
asignan en pruebas.

#### Escenario: [AZ74] Ninguna ruta de gestión de roles, cuentas ni bitácora

- **DADO** el mapa de rutas de producción del proceso administrativo
- **CUANDO** se busca una ruta que asigne roles, desactive cuentas, restablezca contraseñas de otros, inscriba MFA con
  sesión completa, o lea la bitácora
- **ENTONCES** no existe ninguna
- **Y** este escenario deja de ser cierto el día que el cambio 8b las entregue

#### Escenario: [AZ75] Los roles solo se asignan en pruebas

- **DADO** el código de producción
- **CUANDO** se busca un caso de uso, una migración o una tarea que inserte filas de asignación de rol
- **ENTONCES** no existe ninguno, y ninguna migración inserta cuentas ni asignaciones

#### Escenario: [AZ76] El portal no tiene control de acceso por roles

- **DADO** la cadena del portal
- **CUANDO** se inspecciona su configuración de autorización
- **ENTONCES** no contiene roles, permisos ni la lista de rutas con permiso

### Requisito: Ausencia de flujo de anulación, de restricciones de alcance, de informe de segregación y de tope compartido entre procesos (brecha con destino: cambios futuros)

El sistema NO DEBE incluir, en este cambio: el flujo de solicitud pendiente y de aprobación de la anulación de factura,
ni la validación «aprobador distinto del emisor» (cambio que construya la anulación en `invoicing`); las restricciones
de alcance dentro de un permiso (casos de uso de cada módulo); el trabajo diario de integridad de §4.3; el informe
trimestral de segregación por operaciones (cambio que entregue la primera operación con aprobación); ni un tope de
denegaciones compartido entre procesos (cambio 11, con Redis, si hace falta).

#### Escenario: [AZ77] Ninguna de estas piezas existe

- **DADO** el código de producción y el árbol de tareas del trabajador
- **CUANDO** se busca un caso de uso de solicitud o aprobación de anulación, una comprobación de alcance dentro de un
  permiso, una tarea de integridad de MFA, un informe de segregación y un cliente de Redis para el tope
- **ENTONCES** no existe ninguno, y cada escenario deja de ser cierto el día que su cambio dueño lo entregue
