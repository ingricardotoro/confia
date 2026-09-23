# Delta para Integridad de la construcción

## ADDED Requirements

### Requisito: Tabla `shared_idempotency_key`, clave primaria natural y seguridad de fila forzada

El sistema DEBE mantener la migración `V4` que crea la tabla `shared_idempotency_key`, con clave
primaria natural compuesta `(institution_id, endpoint, idempotency_key)` y las columnas
`request_hash`, `status`, `response_status`, `response_body`, `created_at`, `completed_at` y
`expires_at` (ADR-0010; ADR-0015 regla 3). La tabla DEBE tener `ENABLE ROW LEVEL SECURITY` y
`FORCE ROW LEVEL SECURITY`, con una política sobre `institution_id` que siga el mismo patrón
`current_setting(..., true)` con fallo cerrado ante `NULL` que ya usan las migraciones `V1` a `V3`
(`docs/03-seguridad.md` §6.2). La migración DEBE partir de `REVOKE ALL ... FROM PUBLIC` antes de
conceder ningún privilegio explícito.

#### Escenario: Las puertas genéricas de esquema pasan sobre `shared_idempotency_key` sin lista de exclusión

- **DADO** `shared_idempotency_key` ya migrada, con su clave primaria compuesta
  `(institution_id, endpoint, idempotency_key)` y su seguridad a nivel de fila habilitada y
  forzada
- **CUANDO** se ejecutan las pruebas de catálogo e inventario de `MultiTenantSchemaIT`
  —`institution_id NOT NULL`, `relrowsecurity` y `relforcerowsecurity` en verdadero, prefijo de
  módulo `shared_` y restricción única con discriminador de institución—
- **ENTONCES** todas pasan sobre `shared_idempotency_key` sin necesitar ninguna excepción ni
  entrada en una lista de exclusión

#### Escenario: La clave primaria compuesta actúa como discriminador de institución sin necesitar la excepción de la tabla raíz

- **DADO** que la restricción de unicidad con discriminador de institución solo exceptúa
  explícitamente a la tabla raíz de `organization`, cuya propia clave primaria hace ese papel
- **CUANDO** la prueba de catálogo de restricciones únicas evalúa la clave primaria de
  `shared_idempotency_key`
- **ENTONCES** la reconoce como conforme porque `institution_id` es la primera columna de esa
  clave primaria, sin necesitar entrar en la lista cerrada de excepciones que hoy solo contiene a
  la tabla raíz

### Requisito: Permisos de acceso a `shared_idempotency_key` por rol de base de datos

El sistema DEBE conceder sobre `shared_idempotency_key`, exactamente y sin uno más ni uno menos
(`docs/03-seguridad.md` §6.1): a `confia_admin_app`, `SELECT`, `INSERT` y `UPDATE`, sin `DELETE`;
a `confia_portal_app`, ningún privilegio; a `confia_readonly`, solo `SELECT`, sujeto a la misma
política de fila por institución que los demás roles; a `confia_backup`, el alcance de
`pg_read_all_data` que ya tiene sobre el resto del esquema; a `confia_owner`, ningún privilegio
adicional a los de propietario del esquema, sin el atributo `BYPASSRLS`. El sistema DEBE extender
`RolePrivilegeMatrixIT` con las filas de `shared_idempotency_key` para los cinco roles.

#### Escenario: La matriz de privilegios cubre `shared_idempotency_key` para los cinco roles

- **DADO** `RolePrivilegeMatrixIT` extendida con las filas de `shared_idempotency_key`
- **CUANDO** se ejecuta `./mvnw verify`
- **ENTONCES** confirma exactamente los privilegios de `docs/03-seguridad.md` §6.1 sobre
  `shared_idempotency_key` para cada uno de los cinco roles, sin uno más ni uno menos

#### Escenario: `confia_admin_app` puede leer, insertar y actualizar, pero no borrar

- **DADO** el rol `confia_admin_app` conectado con el contexto de una institución
- **CUANDO** intenta `SELECT`, `INSERT` y `UPDATE` sobre `shared_idempotency_key`, y después
  intenta `DELETE`
- **ENTONCES** las tres primeras operaciones se permiten y el `DELETE` se rechaza por falta de
  privilegio

#### Escenario: `confia_portal_app` no tiene ningún privilegio sobre la tabla

- **DADO** el rol `confia_portal_app` conectado con el contexto de una institución
- **CUANDO** intenta `SELECT`, `INSERT`, `UPDATE` o `DELETE` sobre `shared_idempotency_key`
- **ENTONCES** las cuatro operaciones se rechazan por falta de privilegio

### Requisito: Marcador y efecto de negocio en una única transacción atómica

El componente de ejecución idempotente de `com.confia.shared.security` DEBE escribir el marcador
en curso y ejecutar el efecto del caso de uso dentro de una única invocación del componente
transaccional único, y por tanto dentro de una única transacción real contra PostgreSQL, de modo
que un fallo del efecto revierta también el marcador. El componente NO DEBE abrir ninguna
transacción propia: la regla «Transacciones confinadas al componente único de `shared/security`»
ya exigida por esta capacidad se aplica sin excepción al componente nuevo, por composición y no
por excepción.

#### Escenario: El efecto exitoso completa el marcador dentro de la misma transacción

- **DADO** una clave de idempotencia nueva y un caso de uso real que se ejecuta con éxito
- **CUANDO** el componente lo ejecuta a través de una única invocación del componente
  transaccional
- **ENTONCES**, al confirmar, el marcador queda en estado completado con la respuesta del caso de
  uso, y ambas escrituras —marcador y efecto— son visibles o ninguna lo es

#### Escenario: El efecto que falla revierte también el marcador

- **DADO** una clave de idempotencia nueva y un caso de uso real que falla de forma determinista
  después de que el componente escribe el marcador en curso
- **CUANDO** el componente lo ejecuta
- **ENTONCES** la transacción completa revierte, ninguna fila del marcador sobrevive para esa
  clave, y una solicitud posterior con la misma clave se trata como clave nueva

### Requisito: Espera acotada ante escritura concurrente del marcador, con salidas distinguibles por `SQLState`

El sistema DEBE acotar con `lock_timeout` explícito la espera de una segunda escritura del
marcador que colisiona por clave primaria con una primera transacción todavía abierta sobre la
misma clave, en vez de bloquear sin límite. El sistema DEBE distinguir, por código `SQLState` y
nunca por texto de mensaje, entre el agotamiento de esa espera —mientras la primera transacción
sigue abierta— y el choque por clave duplicada —una vez que la primera ya confirmó—, porque son
dos comportamientos observables distintos que ocurren en momentos distintos. Ante el agotamiento
de la espera, el componente DEBE propagar un error tratable y distinguible, apto para que la capa
web lo traduzca a `409` cuando esa capa exista (cambio 7). El reintento acotado del componente
transaccional NO DEBE reintentar ninguna de las dos salidas.

#### Escenario: La espera se agota mientras la primera transacción sigue abierta

- **DADO** dos transacciones reales, ejecutadas a través del componente, que escriben el marcador
  con la misma clave primaria natural, sincronizadas de forma determinista —no por reloj— para que
  la primera permanezca abierta más allá del `lock_timeout` configurado
- **CUANDO** la segunda espera ese `lock_timeout`
- **ENTONCES** la segunda recibe un error tratable, distinguible por `SQLState` del choque por
  clave duplicada, sin haber escrito ningún marcador propio ni haber ejecutado su caso de uso, y
  la primera transacción continúa sin interferencia

#### Escenario: La primera transacción confirma dentro de la ventana de espera

- **DADO** dos transacciones reales, ejecutadas a través del componente, con la misma clave
  primaria natural, sincronizadas de forma determinista para que la primera confirme dentro de la
  ventana del `lock_timeout` de la segunda
- **CUANDO** la segunda, ya dentro de su ventana de espera, intenta escribir su marcador
- **ENTONCES** la segunda recibe un error de clave duplicada, distinguible por `SQLState` de la
  salida de espera agotada, sin haber ejecutado su caso de uso

#### Escenario: La primera transacción revierte dentro de la ventana de espera

- **DADO** dos transacciones reales, ejecutadas a través del componente, con la misma clave
  primaria natural, sincronizadas de forma determinista para que la primera revierta dentro de la
  ventana del `lock_timeout` de la segunda
- **CUANDO** la segunda, ya dentro de su ventana de espera, intenta escribir su marcador
- **ENTONCES** la segunda inserta su marcador con éxito y ejecuta su caso de uso, porque la clave
  quedó libre

#### Escenario: El reintento acotado no reintenta el agotamiento de espera ni el choque por clave duplicada

- **DADO** las dos salidas anteriores —agotamiento de espera y choque por clave duplicada—,
  producidas por transacciones reales a través del componente transaccional único
- **CUANDO** cada una ocurre
- **ENTONCES** el componente transaccional propaga el error tras el primer intento, sin
  reintentarlo, a diferencia de los errores de serialización o de interbloqueo que sí reintenta

### Requisito: Rechazo de la misma clave con carga útil distinta, comparada por hash canonicalizado

El sistema DEBE comparar la carga útil de una solicitud repetida contra el hash de la carga
canonicalizada (`request_hash`) almacenado para esa clave, nunca contra el texto crudo, y DEBE
rechazar la ejecución sin invocar el caso de uso cuando los hashes difieren (ADR-0010).

#### Escenario: Misma clave, hash de carga distinto, caso de uso no invocado

- **DADO** una clave de idempotencia con una carga útil ya registrada y su `request_hash`
  almacenado
- **CUANDO** llega una solicitud con la misma clave y una carga útil cuyo hash canonicalizado
  difiere del almacenado
- **ENTONCES** el componente rechaza la solicitud sin invocar el caso de uso, verificable porque
  el efecto de negocio asociado no se ejecuta ninguna vez

#### Escenario: Misma clave, mismo hash de carga, se admite como repetición válida

- **DADO** la misma clave del escenario anterior
- **CUANDO** llega una solicitud con una carga útil cuyo hash canonicalizado coincide con el
  almacenado
- **ENTONCES** el componente no la rechaza por carga distinta

### Requisito: Respuesta reproducible ante clave completada, sin reejecutar el caso de uso

El sistema DEBE devolver, ante una clave ya completada con hash de carga coincidente, la respuesta
original almacenada, sin reejecutar el `Supplier` del caso de uso, y el resultado que el
componente devuelve DEBE distinguir una repetición de una ejecución real, para que el llamador
pueda producir la cabecera `Idempotent-Replay` cuando exista una capa web (cambio 7) sin volver a
consultar nada.

#### Escenario: La segunda solicitud recibe la respuesta original sin reejecutar el caso de uso

- **DADO** una clave de idempotencia que ya completó su ejecución y almacenó una respuesta
- **CUANDO** una solicitud posterior llega con la misma clave y la misma carga útil
- **ENTONCES** el componente devuelve exactamente la misma respuesta almacenada, el caso de uso
  registra cero invocaciones nuevas, y el resultado señala que se trató de una repetición

### Requisito: Criterio de salida 4 de F0 — un solo efecto contable ante dos solicitudes concurrentes con la misma clave

El sistema DEBE demostrar, sobre PostgreSQL real y un camino de escritura de producción real —por
ejemplo una actualización acumulativa sobre una columna de `organization_institution` o una
inserción contada en `shared_audit_log`, nunca una actualización a valor fijo, que sería
idempotente por naturaleza y no testificaría nada—, que dos solicitudes concurrentes con la misma
clave de idempotencia producen exactamente un efecto contable, sin importar cuál de las tres
salidas de la espera acotada resuelve a cada una.

#### Escenario: Dos solicitudes concurrentes con la misma clave producen un solo efecto contable

- **DADO** un caso de uso real que aplica un efecto contable —acumulativo o de conteo de filas,
  nunca una actualización a valor fijo— sobre una fila real de producción, invocado a través del
  componente
- **CUANDO** dos solicitudes reales, con la misma clave de idempotencia y sincronizadas de forma
  determinista, se ejecutan de manera concurrente
- **ENTONCES** el efecto contable se aplicó exactamente una vez, verificable por el valor final
  acumulado o por el conteo de filas resultante, y nunca dos veces

### Requisito: Reutilización de una clave caducada por actualización de la fila existente

El sistema DEBE tratar una clave de idempotencia con `expires_at` vencido como si fuera nueva,
ejecutando de nuevo el caso de uso, y DEBE hacerlo mediante `UPDATE` de la fila existente, nunca
mediante `DELETE` seguido de `INSERT`, porque ningún rol de aplicación tiene privilegio `DELETE`
sobre tablas de negocio (`docs/03-seguridad.md` §6.1).

#### Escenario: Una clave caducada se reutiliza actualizando la fila existente, no insertando una nueva

- **DADO** una fila de `shared_idempotency_key` con `expires_at` vencido, para una clave que ya
  había completado una ejecución anterior
- **CUANDO** llega una nueva solicitud con esa misma clave
- **ENTONCES** el sistema ejecuta el caso de uso de nuevo, como si la clave fuera nueva, y la fila
  existente se actualiza con el nuevo estado, hash e `expires_at`, sin que exista en ningún momento
  más de una fila para esa clave primaria

### Requisito: Ausencia de superficie HTTP que exija la cabecera de idempotencia (brecha con destino: cambio 7)

El sistema NO DEBE exponer, en esta parte del cambio, ninguna clase en una capa `web` que exija la
cabecera `Idempotency-Key`, que rechace su ausencia con `400`, que agregue la cabecera
`Idempotent-Replay`, ni que traduzca las salidas del componente a `200`, `409` o `422` HTTP. El
control es invocable únicamente desde código Java. La superficie HTTP completa de ADR-0010 es
responsabilidad del **cambio 7** (`staff-authentication-mfa-sessions`), que trae el primer
endpoint, y de las capacidades consumidoras de F3.

#### Escenario: Ninguna clase en capa `web` exige la cabecera de idempotencia

- **DADO** el código de producción de `apps/api/app` entregado hasta este cambio, con
  `optionalLayer("Web")` todavía declarado en `LayeredArchitectureTest`
- **CUANDO** se inspeccionan los tres puntos de entrada —`AdminApplication`, `PortalApplication` y
  `WorkerApplication`— y sus paquetes `web`
- **ENTONCES** ninguno contiene una clase que valide, rechace o traduzca la cabecera
  `Idempotency-Key`, porque ninguna capa `web` de producción existe todavía; este escenario deja
  de ser cierto el día que el cambio 7 entregue el primer endpoint

### Requisito: Ausencia de purga física de claves caducadas (brecha con destino: cambio 9)

El sistema NO DEBE eliminar físicamente ninguna fila de `shared_idempotency_key` con `expires_at`
vencido. Esta parte del cambio modela `expires_at` y la semántica lógica de caducidad —ver
«Reutilización de una clave caducada por actualización de la fila existente»—, pero no borra
ninguna fila. La purga física es responsabilidad del **cambio 9**
(`background-jobs-with-db-scheduler`), que hereda además una tensión de privilegios: `docs/03-
seguridad.md` §6.1 no concede `DELETE` sobre tablas de negocio a ningún rol de aplicación,
incluido el que ejecuta `confia-worker`.

#### Escenario: Una fila caducada sigue existiendo porque no hay ningún trabajo de purga desplegado

- **DADO** una fila de `shared_idempotency_key` con `expires_at` vencido hace más de veinticuatro
  horas, y ningún trabajo programado de purga desplegado en este cambio
- **CUANDO** se consulta la tabla directamente
- **ENTONCES** la fila sigue existiendo; este escenario deja de ser cierto el día que el cambio 9
  entregue su trabajo de purga

### Requisito: Ausencia de acceso del portal a `shared_idempotency_key` (brecha con destino: F3/F4)

El sistema NO DEBE conceder a `confia_portal_app` ningún privilegio sobre `shared_idempotency_key`
(ver «Permisos de acceso a `shared_idempotency_key` por rol de base de datos»). Ningún camino de
escritura financiera iniciado desde el portal puede ser idempotente todavía, porque
`docs/03-seguridad.md` §6.1 concede a `confia_portal_app` `INSERT` únicamente en
`document_request`, `payment_intent` y `notification_preference`, ninguna de las cuales es esta
tabla. El primer `GRANT` que resuelva esta brecha es responsabilidad de la capacidad de **F3 o
F4** que dé al portal su primer camino de escritura financiera.

#### Escenario: `confia_portal_app` no tiene ningún privilegio, y ninguna escritura del portal puede ser idempotente

- **DADO** `shared_idempotency_key` migrada con los `GRANT` de `docs/03-seguridad.md` §6.1
- **CUANDO** `RolePrivilegeMatrixIT` verifica los privilegios de `confia_portal_app` sobre esa
  tabla
- **ENTONCES** no encuentra ningún privilegio —ni `SELECT`, ni `INSERT`, ni `UPDATE`, ni
  `DELETE`—; este escenario deja de ser cierto el día que una capacidad de F3 o F4 conceda el
  primer `GRANT` de escritura financiera al portal
