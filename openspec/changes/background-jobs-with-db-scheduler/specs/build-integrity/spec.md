# Delta para Integridad de la construcción

- **Estado:** pendiente de aprobación del propietario del producto
- **Cambio:** `background-jobs-with-db-scheduler` (F0, cambio 9)
- **Propuesta:** `proposal.md`, aprobada el 2026-10-24, con P1 a P9 vinculantes (P2: funciones de purga
  `SECURITY DEFINER`; P6: el rol del portal resuelve la validación pendiente de ADR-0017; P7: el trabajador usa
  `confia_admin_app`)

**Convenciones de este delta.**

- Los escenarios nuevos se numeran `BI59` en adelante, a continuación del máximo de la especificación vigente
  (`BI58`); `BI59` a `BI103` son los de la primera redacción y `BI104` en adelante los añadidos al conciliar la
  especificación con el diseño y con las decisiones del propietario del 2026-10-24 (D-N1, D-N3). Los que se
  conservan de la especificación vigente conservan su número o no lo llevan.
- Una regla de construcción con «convención de dos mitades» DEBE tener un fixture permanente que la viole a
  propósito y una comprobación de que el código de producción real no falla por esa causa y evalúa clases
  reales, no un conjunto vacío.
- Las migraciones `V1` a `V9` no se editan. `V10` crea la tabla de tareas; `V11` crea las funciones de purga de
  recuperación y de refresco; `V12` crea las de idempotencia y de los dos retrocesos, con sus índices (el diseño,
  decisiones 15 y 16, fija los números).
- Cada función de purga recibe del llamador un corte (`p_cutoff`) y un límite de lote (`p_limit`), y aplica un
  corte efectivo de `LEAST(p_cutoff, ahora de la base de datos - piso)`: el llamador solo puede **adelantar la
  purga hacia el pasado** (cortes más antiguos), nunca hacia el presente, y ninguna fila más reciente que el piso se
  borra aunque el llamador pase el futuro. Las pruebas controlan el tiempo con el corte que pasan y con las marcas
  de las filas de prueba.
- El comportamiento de cada purga (qué filas, qué ventana) está en la capacidad dueña de la tabla (`identity` e
  idempotencia en este documento); este delta fija el mecanismo y los privilegios.

## ADDED Requirements

### Requisito: La versión de db-scheduler se fija una sola vez y la convergencia de dependencias la acepta

El sistema DEBE fijar la versión de la biblioteca db-scheduler (el iniciador de Spring Boot 4 y, donde haga
falta el cliente, el núcleo) una sola vez en el `dependencyManagement` de `apps/api/pom.xml`, sin rangos, y DEBE
verificar su compatibilidad con el BOM de Spring Boot 4.1.1, con Jackson 3 y con la regla `dependencyConvergence`
mediante un sondeo previo al diseño: arranque real del trabajador y `./mvnw validate` en verde. La versión se fija
releyendo la lista de versiones publicadas al momento de fijarla. `maven-enforcer-plugin` DEBE seguir prohibiendo
Quartz y JobRunr.

#### Escenario: [BI59] La versión tiene una sola fuente

- **DADO** los POM de `apps/api`
- **CUANDO** se busca la versión de db-scheduler
- **ENTONCES** está declarada exactamente una vez, en el `dependencyManagement` del POM padre, y ningún módulo la
  repite ni usa un rango

#### Escenario: [BI60] El sondeo arranca el trabajador real con la biblioteca

- **DADO** la versión fijada, el BOM de Spring Boot 4.1.1 y Jackson 3
- **CUANDO** se arranca el proceso trabajador por su camino de producción y se ejecuta `./mvnw validate`
- **ENTONCES** el contexto contiene exactamente un ejecutor propio (el del iniciador, retirado), no contiene
  ningún `MeterRegistry`, y `validate` termina sin violar `dependencyConvergence`
- **Y** si el sondeo falla, se aplica la alternativa documentada (el iniciador también excluido en el trabajador y
  solo el núcleo de la biblioteca) y se registra una nota fechada antes de continuar

#### Escenario: [BI61] Quartz y JobRunr siguen prohibidos con db-scheduler presente

- **DADO** el POM padre con db-scheduler admitido
- **CUANDO** un módulo agrega Quartz o JobRunr
- **ENTONCES** `./mvnw verify` falla señalando la dependencia prohibida

### Requisito: La migración `V10` crea `scheduled_tasks` y la biblioteca no crea tablas

El sistema DEBE incluir la migración `V10`, que crea la tabla técnica `scheduled_tasks` con el esquema de la
versión fijada de db-scheduler —incluidas las columnas de estado fallido `consecutive_failures` y
`last_failure`— y con los privilegios del requisito siguiente. La tabla pertenece al catálogo cerrado de
ADR-0017: no lleva `institution_id`, ni seguridad de fila, ni prefijo de módulo. La creación automática de la
tabla por la biblioteca DEBE estar desactivada y probada. `V10` NO DEBE contener nada más que esa tabla, sus
índices (con el nombre de la tabla como prefijo) y sus privilegios, y NO DEBE editar `V1` a `V9`. El catálogo de la
tabla DEBE ser igual al esquema de PostgreSQL de la versión fijada de la biblioteca (una prueba lo compara). El
código de jOOQ DEBE regenerarse desde las migraciones, **excluyendo `scheduled_tasks`** de la generación
(ADR-0017, regla 6).

#### Escenario: [BI62] La tabla existe con las columnas de la versión fijada

- **DADO** el esquema tras `V10`
- **CUANDO** se consulta el catálogo
- **ENTONCES** `scheduled_tasks` existe con las columnas que exige la versión fijada de la biblioteca, incluidas
  `consecutive_failures` y `last_failure`
- **Y** no tiene `institution_id` ni política de fila, y la prueba de catálogo no la exige

#### Escenario: [BI63] La biblioteca no crea ni altera tablas

- **DADO** una base de datos migrada solo hasta `V9`, sin `scheduled_tasks`
- **CUANDO** se arranca el proceso trabajador con la creación automática desactivada
- **ENTONCES** no existe `scheduled_tasks` después del arranque y no se emitió ninguna sentencia `CREATE` ni
  `ALTER`
- **Y** el trabajador no ejecuta ninguna tarea

#### Escenario: [BI64] `V10` no cambia nada más y no edita migraciones anteriores

- **DADO** el esquema tras `V10` frente al esquema tras `V9`
- **CUANDO** se comparan tablas, columnas, índices y privilegios
- **ENTONCES** la única diferencia son la tabla `scheduled_tasks`, sus índices y sus privilegios
- **Y** las sumas de verificación de Flyway de `V1` a `V9` no cambiaron

### Requisito: Permisos de acceso a `scheduled_tasks` por rol de base de datos

El sistema DEBE conceder sobre `scheduled_tasks`, exactamente y sin uno más ni uno menos (ADR-0017): a
`confia_admin_app`, `SELECT`, `INSERT`, `UPDATE` y `DELETE` (el trabajador usa ese rol, decisión P7); a
`confia_portal_app`, `INSERT` y **ningún** `UPDATE` ni `DELETE`, y `SELECT` si y solo si la prueba del cliente de
programación con `confia_portal_app` demuestra que lo exige; a `confia_readonly`, ningún privilegio; a
`confia_backup`, el alcance de `pg_read_all_data` que ya tiene; a `confia_owner`, ningún privilegio adicional al
de propietario del esquema, sin `BYPASSRLS`. El resultado de la prueba del portal DEBE quedar registrado. El
sistema DEBE extender `RolePrivilegeMatrixIT` con las filas de `scheduled_tasks` para los cinco roles.

#### Escenario: [BI65] La matriz cubre `scheduled_tasks` para los cinco roles

- **DADO** `RolePrivilegeMatrixIT` extendida con las filas de `scheduled_tasks`
- **CUANDO** se ejecuta `./mvnw verify`
- **ENTONCES** confirma exactamente los privilegios de este requisito para cada uno de los cinco roles, sin uno
  más ni uno menos

#### Escenario: [BI66] El portal programa con su rol real y no puede actualizar ni borrar

- **DADO** el rol `confia_portal_app` conectado y el cliente de programación real (primero en la sonda de la
  rebanada 1, que fija el `GRANT` de `V10`, y después a través del bean real del portal, con el `DataSource` de
  `confia_portal_app` de la decisión D-N1)
- **CUANDO** programa una tarea única y confirma, y después intenta `UPDATE` y `DELETE` sobre `scheduled_tasks`
- **ENTONCES** el `INSERT` se permite y el `UPDATE` y el `DELETE` se rechazan por falta de privilegio
- **Y** si la programación funciona sin `SELECT`, el rol no tiene `SELECT` sobre la tabla; si lo exige, la matriz
  lo afirma y el motivo queda registrado

#### Escenario: [BI67] `confia_readonly` no tiene ningún privilegio y `confia_admin_app` puede operar

- **DADO** los roles `confia_readonly` y `confia_admin_app`
- **CUANDO** el primero intenta `SELECT` sobre `scheduled_tasks` y el segundo `SELECT`, `INSERT`, `UPDATE` y
  `DELETE`
- **ENTONCES** el primero se rechaza y las cuatro operaciones del segundo se permiten

### Requisito: Las purgas usan funciones `SECURITY DEFINER` de `confia_owner`, con `EXECUTE` solo para `confia_admin_app`, y no se concede `DELETE` genérico

El sistema DEBE borrar filas de las tablas técnicas con vencimiento (tokens de recuperación, tokens de refresco y
familias, `shared_idempotency_key`, `identity_login_backoff` e `identity_mfa_totp_backoff`) únicamente mediante
funciones de purga (decisión P2; ADR-0025, propuesto). Hay cinco funciones, una por categoría, todas con la firma
`(p_cutoff timestamptz, p_limit integer)` (la de familias de refresco agrega dos salidas, familias y tokens
purgados). Cada función DEBE ser `SECURITY DEFINER`, propiedad de `confia_owner` (`NOBYPASSRLS`, con seguridad de
fila forzada en las tablas), con `search_path` fijo y nombres calificados, y con `EXECUTE` revocado de `PUBLIC` y
concedido solo a `confia_admin_app`.

Cada función DEBE llevar codificado el piso mínimo de su ventana (24 horas; 30 días en la de familias de refresco)
y aplicar el corte efectivo `LEAST(p_cutoff, ahora de la base de datos - piso)`: **el llamador solo puede mover el
corte hacia atrás, nunca hacia adelante**, de modo que ninguna fila más reciente que el piso se borra aunque el
llamador pase un corte en el futuro o un reloj adelantado. NO se rechaza un corte posterior al piso (una diferencia
de milisegundos entre el reloj de la aplicación y el de la base de datos no debe fallar la tarea): se recorta. El
límite `p_limit` se acota entre 1 y 10 000. Cada función DEBE exigir contexto de institución y actor `system`: sin
`app.institution_id` lanza el error `confia_purge_context_missing`, y con un actor que no es `system` lanza
`confia_purge_actor_not_system`; **nunca** termina con un éxito de cero filas por falta de contexto. Solo purga filas
de la institución del contexto. Ningún rol de aplicación DEBE recibir `DELETE` sobre estas tablas. Cada función DEBE
devolver el número de filas afectadas. Las llamadas desde la aplicación DEBEN hacerse con consultas parametrizadas
desde `infrastructure`.

#### Escenario: [BI68] El catálogo describe cada función como se exige

- **DADO** las funciones de purga tras la migración
- **CUANDO** se consulta `pg_proc` y el catálogo de privilegios
- **ENTONCES** cada una es `SECURITY DEFINER`, tiene por propietario a `confia_owner`, tiene `search_path` fijo y
  tiene `EXECUTE` solo para `confia_admin_app`
- **Y** un fixture con una función sin `search_path` fijo, otro con `EXECUTE` para `PUBLIC` y otro con
  propietario distinto hacen fallar la prueba de catálogo

#### Escenario: [BI69] Los demás roles no pueden ejecutar las funciones

- **DADO** los roles `confia_portal_app`, `confia_readonly`, `confia_backup` y un rol sin privilegios
- **CUANDO** cada uno intenta invocar una función de purga
- **ENTONCES** las cuatro invocaciones se rechazan por falta de privilegio y no se borra ninguna fila

#### Escenario: [BI70] Un `search_path` hostil del llamador no desvía la función

- **DADO** una sesión de `confia_admin_app` cuyo `search_path` antepone un esquema con una tabla homónima de la
  tabla purgada y con una función homónima de una función auxiliar
- **CUANDO** invoca la función de purga
- **ENTONCES** la función opera sobre la tabla real del esquema de la aplicación y no toca la homónima

#### Escenario: [BI71] El llamador solo puede mover el corte hacia atrás, nunca hacia adelante

- **DADO** para cada función filas vigentes, filas vencidas dentro de la ventana (más recientes que el piso) y filas
  vencidas fuera de ella (más antiguas que el piso)
- **CUANDO** el llamador invoca la función con un corte igual al piso, con un corte más antiguo que el piso y con
  los extremos de `p_limit` (cero, negativo y mayor que 10 000)
- **ENTONCES** con el corte igual al piso se eliminan solo las filas fuera de la ventana
- **Y** con un corte más antiguo se eliminan solo las filas más antiguas que ese corte (el llamador puede ser más
  conservador)
- **Y** `p_limit` se acota entre 1 y 10 000, de modo que el lote nunca supera 10 000 filas

#### Escenario: [BI104] Control negativo: un corte en el futuro no borra nada más reciente que el piso

- **DADO** filas vigentes y filas vencidas dentro de la ventana de cada tabla, y filas fuera de ella
- **CUANDO** el llamador invoca cada función con un `p_cutoff` en el futuro (por ejemplo, un día después del
  instante actual de la base de datos)
- **ENTONCES** ninguna fila más reciente que el piso se elimina, y solo se eliminan las más antiguas que el piso
- **Y** una variante de prueba de la función que usa `p_cutoff` sin el `LEAST` borra la fila reciente y hace fallar
  esta prueba señalando la fila

#### Escenario: [BI105] Sin contexto o con un actor que no es `system` la función falla

- **DADO** filas purgables y una sesión de `confia_admin_app` sin `app.institution_id`, y otra con el contexto de
  una institución pero con el actor `staff`
- **CUANDO** se invoca cada función en cada sesión
- **ENTONCES** la primera sesión recibe el error `confia_purge_context_missing` y la segunda
  `confia_purge_actor_not_system`
- **Y** no se elimina ninguna fila

#### Escenario: [BI72] Ningún rol de aplicación tiene `DELETE` sobre las tablas purgadas

- **DADO** los cinco roles y las seis tablas con purga (tokens de recuperación, familias y tokens de refresco,
  `shared_idempotency_key` y las dos de retroceso)
- **CUANDO** se consulta `has_table_privilege` para `DELETE`
- **ENTONCES** ningún rol de aplicación lo tiene sobre ninguna tabla de negocio

### Requisito: Cada función de purga borra solo las filas fuera de su ventana, en el borde exacto y bajo la seguridad de fila

El sistema DEBE probar, para cada función de purga con PostgreSQL real, que borra el cien por ciento de las filas
fuera de su ventana y ninguna dentro de ella, con casos límite un segundo antes del corte, en el borde exacto y
un segundo después; que no toca filas de otra institución; que respeta el piso con un corte en el futuro; que
respeta el límite de lote (`p_limit`, con tope de 10 000); y que, invocada sin contexto de institución, lanza el
error `confia_purge_context_missing` sin borrar nada, y con un actor que no es `system`, `confia_purge_actor_not_system`.
La prueba DEBE afirmar el error y conteos exactos, de modo que una purga sin contexto no pase por una purga
exitosa. En el borde exacto la fila se conserva. La rutina generada por jOOQ para cada función DEBE ser la que la
prueba invoque; si una función no se pudiera generar como rutina, se admite una llamada de SQL plano de la lista
aprobada con un único parámetro enlazado.

#### Escenario: [BI73] Un segundo antes, en el borde y un segundo después

- **DADO** para cada función filas con la referencia exactamente en el corte efectivo menos un segundo, en el
  corte efectivo y en el corte efectivo más un segundo (con `p_cutoff` igual o anterior al piso)
- **CUANDO** corre la función
- **ENTONCES** elimina la fila más antigua que el corte (referencia estrictamente anterior) y conserva las otras
  dos, y devuelve el conteo 1

#### Escenario: [BI74] Nunca se borra una fila vigente

- **DADO** una población mixta con filas vigentes, filas dentro de la ventana y filas fuera de ella, en cada
  tabla
- **CUANDO** corre cada función
- **ENTONCES** el número de filas eliminadas es exactamente el de las filas fuera de la ventana
- **Y** todas las vigentes y todas las de dentro de la ventana siguen existiendo

#### Escenario: [BI75] Otra institución queda intacta

- **DADO** dos instituciones, cada una con filas purgables, y una sesión con el contexto de la primera
- **CUANDO** corre cada función
- **ENTONCES** se eliminan solo las de la primera y las de la segunda se conservan

#### Escenario: [BI76] Sin contexto de institución la función falla y la prueba lo detecta

- **DADO** filas purgables y una sesión de `confia_admin_app` sin `app.institution_id`
- **CUANDO** se invoca cada función
- **ENTONCES** lanza el error `confia_purge_context_missing` y no elimina ninguna fila
- **Y** la prueba afirma el error y que las filas siguen existiendo, y falla en una variante de prueba que, sin
  contexto, termina como un éxito de cero filas o devuelve el conteo de otra institución

#### Escenario: [BI77] Una variante sin corte hace fallar la prueba (control negativo)

- **DADO** un fixture permanente con una función de purga sin el corte mínimo
- **CUANDO** se ejecuta la prueba de filas vigentes sobre ese fixture
- **ENTONCES** falla señalando la fila vigente eliminada
- **Y** con las funciones reales la misma prueba pasa evaluando filas reales

#### Escenario: [BI106] El límite de lote se respeta y se acota a 10 000

- **DADO** una tabla con 12 000 filas purgables de la institución del contexto
- **CUANDO** se invoca la función con `p_limit` igual a 5 000, y después con `p_limit` igual a 50 000
- **ENTONCES** la primera elimina exactamente 5 000 y la segunda elimina como máximo 10 000

#### Escenario: [BI107] La función de familias de refresco elimina familias completas hasta el límite y respeta el piso de 30 días

- **DADO** una familia revocada hace 29 días, otra revocada hace 31 días y otra con vencimiento absoluto hace 31
  días, con tokens enlazados por sucesor
- **CUANDO** se invoca la función de familias con un corte en el futuro
- **ENTONCES** elimina las dos familias de 31 días con todos sus tokens y conserva la de 29 días
- **Y** devuelve las dos salidas, familias y tokens purgados, con los conteos exactos

### Requisito: Purga de claves de idempotencia caducadas, 24 horas después de `expires_at`, con índice por `expires_at`

El sistema DEBE eliminar, una vez al día por institución configurada, las filas de `shared_idempotency_key` cuyo
`expires_at` ocurrió hace **más de 24 horas** (decisión P3), mediante su función de purga. NO DEBE eliminar una
clave vigente, ni una vencida hace 24 horas o menos (que sigue reutilizable por actualización de su fila), ni una
de otra institución. La migración (`V12`) DEBE crear en `shared_idempotency_key` un índice por institución y
`expires_at` (`institution_id`, `expires_at`), y en `identity_login_backoff` otro por institución y `last_attempt_at`
(`institution_id`, `last_attempt_at`), porque esa tabla crece sin límite ante un ataque distribuido;
`identity_mfa_totp_backoff` tiene una fila por cuenta y no necesita índice nuevo. Una clave purgada, al
presentarse de nuevo, DEBE comportarse como una clave nueva.

#### Escenario: [BI78] Una clave caducada hace más de 24 horas se elimina

- **DADO** una fila con `expires_at` a las 10:00:00 del 2026-10-24
- **CUANDO** la purga corre a las 10:00:01 del 2026-10-25
- **ENTONCES** la fila ya no existe

#### Escenario: [BI79] El borde de 24 horas conserva la clave

- **DADO** tres filas con `expires_at` a las 10:00:00 del 2026-10-24
- **CUANDO** la purga corre, en pruebas separadas, a las 09:59:59, a las 10:00:00 y a las 10:00:01 del
  2026-10-25
- **ENTONCES** en las dos primeras la fila se conserva y en la tercera se elimina

#### Escenario: [BI80] Una clave vigente o recién caducada se conserva y sigue siendo reutilizable

- **DADO** una clave vigente y otra caducada hace 23 horas
- **CUANDO** corre la purga y después llega una solicitud con la clave caducada
- **ENTONCES** ambas filas se conservaron y la solicitud con la caducada ejecuta el caso de uso de nuevo,
  actualizando la fila existente

#### Escenario: [BI81] El índice por institución y `expires_at` existe

- **DADO** el esquema tras la migración de las funciones de purga
- **CUANDO** se consulta el catálogo de índices de `shared_idempotency_key`
- **ENTONCES** existe un índice cuyas columnas son, en orden, `institution_id` y `expires_at`

#### Escenario: [BI108] El índice de la tabla de retroceso de inicio de sesión existe

- **DADO** el esquema tras la migración de las funciones de purga
- **CUANDO** se consulta el catálogo de índices de `identity_login_backoff`
- **ENTONCES** existe un índice cuyas columnas son, en orden, `institution_id` y `last_attempt_at`

#### Escenario: [BI82] Una clave purgada se comporta como nueva

- **DADO** una clave cuya fila fue purgada
- **CUANDO** llega una solicitud con esa clave
- **ENTONCES** el sistema la trata como una clave nueva, inserta su fila y ejecuta el caso de uso una vez

### Requisito: Las pruebas de privilegios afirman que ningún rol tiene `DELETE` sobre tablas de negocio y que `EXECUTE` es exacto

El sistema DEBE ampliar `RolePrivilegeMatrixIT` e `IdempotencyKeyPrivilegeIT` para afirmar que siguen sin
existir privilegios `DELETE` sobre las tablas de negocio para ningún rol de aplicación (la excepción de
`scheduled_tasks` es de una tabla técnica del catálogo cerrado), y que `EXECUTE` sobre cada función de purga lo
tiene exactamente `confia_admin_app`, sin uno más ni uno menos para los cinco roles. Ambas pruebas DEBEN fallar
con un fixture que conceda `DELETE` o que conceda `EXECUTE` a otro rol.

#### Escenario: [BI83] La matriz afirma `DELETE` ausente y `EXECUTE` exacto

- **DADO** `RolePrivilegeMatrixIT` e `IdempotencyKeyPrivilegeIT` actualizadas
- **CUANDO** se ejecutan con el esquema real
- **ENTONCES** pasan afirmando ambas cosas para los cinco roles

#### Escenario: [BI84] Un `DELETE` o un `EXECUTE` de más hace fallar la matriz (control negativo)

- **DADO** una variante del esquema que concede `DELETE` sobre una tabla de negocio a `confia_admin_app`, y otra
  que concede `EXECUTE` de una función de purga a `confia_portal_app`
- **CUANDO** se ejecutan las dos pruebas sobre cada variante
- **ENTONCES** fallan señalando el rol, la tabla o la función

### Requisito: Ningún código de producción usa `@Scheduled`, `@EnableScheduling` ni `@Async`

El sistema DEBE romper la construcción si una clase de producción usa `@Scheduled`, `@EnableScheduling` o
`@Async` (ADR-0016, cumplimiento 2; `CLAUDE.md`). La regla DEBE tener su propio fixture permanente con la
convención de dos mitades y NO DEBE evaluar un conjunto vacío.

#### Escenario: [BI85] Fixtures con cada anotación prohibida

- **DADO** tres fixtures permanentes, uno con `@Scheduled`, otro con `@EnableScheduling` y otro con `@Async`
- **CUANDO** se ejecuta `./mvnw verify`
- **ENTONCES** la regla falla en los tres señalando la clase y la anotación

#### Escenario: [BI86] El código real no usa ninguna y la regla evalúa clases reales

- **DADO** el código de producción real de `apps/api`
- **CUANDO** se ejecuta la regla
- **ENTONCES** pasa evaluando clases reales, sin excepción de conjunto vacío

### Requisito: La guardia de registros sensibles vive en un paquete neutral con una única lista de prefijos, y el trabajador no recibe `DataSource` sin ella (condición 4)

El sistema DEBE ubicar la guardia de registros sensibles en un paquete neutral (`com.confia.shared.logging`), fuera
del borde web, con una **única** lista de prefijos protegidos que cubra jOOQ (`org.jooq`), el controlador JDBC de
PostgreSQL (`org.postgresql`), Hikari (`com.zaxxer.hikari`), `org.springframework.jdbc`,
`org.springframework.transaction` y db-scheduler (`com.github.kagkarlsson`). La guardia bloquea los eventos de nivel
`DEBUG` y `TRACE` de esos prefijos y deja pasar `INFO` y superiores. La guardia DEBE instalarse **antes de crear
cualquier bean** en los tres procesos (administrativo, portal y trabajador), mediante un inicializador del contexto
y no por un bean, y retirarse al cerrar el contexto; el borde web ya no la declara. Con la guardia activa, ningún
valor enlazado a una sentencia SQL DEBE aparecer en ningún evento de registro del proceso, aunque `org.jooq`, los
demás prefijos y la raíz estén en `DEBUG` o `TRACE`. Ningún proceso DEBE tener un `DataSource` sin la guardia activa en su contexto (los tres lo tienen tras la decisión
D-N1), y la prueba DEBE arrancar el proceso trabajador real. El
orden de entrega es: primero la guardia con su inicializador y sus pruebas, después el `DataSource` del trabajador;
ningún commit de `main` da un `DataSource` al trabajador sin la guardia, y la guardia no se revierte mientras exista
un trabajador con `DataSource`. Esta es la condición dura 4 del 2026-10-04.

#### Escenario: [BI87] Una sola lista de prefijos para los tres procesos

- **DADO** los contextos administrativo, del portal y del trabajador
- **CUANDO** se inspecciona la lista de prefijos que cada guardia aplica
- **ENTONCES** es la misma instancia de lista en los tres, y un fixture que declara una segunda lista falla la
  prueba
- **Y** la guardia ya no la declara el borde web ni vive en `shared.web.request`

#### Escenario: [BI88] La lista cubre las superficies de riesgo

- **DADO** la lista única de prefijos
- **CUANDO** se compara con las superficies que pueden registrar valores enlazados
- **ENTONCES** contiene jOOQ, el controlador JDBC de PostgreSQL, Hikari, Spring JDBC, Spring Transaction y
  db-scheduler, y la ausencia de cualquiera de ellos hace fallar la prueba

#### Escenario: [BI89] Un valor enlazado distintivo no aparece en el trabajador real con `DEBUG`

- **DADO** el proceso trabajador real arrancado (por el lanzador de producción y con PostgreSQL real) con la raíz
  y cada prefijo protegido en `DEBUG` y en `TRACE`, y una primera sentencia SQL con el valor enlazado distintivo
  `valor-distintivo-7f3a`, pasado por `set_config` y por una consulta del `DSLContext` real
- **CUANDO** el trabajador ejecuta esa sentencia
- **ENTONCES** ningún evento de registro de ningún nivel contiene `valor-distintivo-7f3a`

#### Escenario: [BI90] Sin la guardia el valor aparece (control negativo)

- **DADO** la misma prueba sobre un trabajador arrancado sin la guardia
- **CUANDO** ejecuta la misma sentencia con `org.jooq` en `DEBUG`
- **ENTONCES** el valor distintivo aparece en algún evento (al menos en el controlador JDBC de PostgreSQL), de
  modo que la prueba alcanza el caso peligroso y no pasa por vacío
- **Y** cada prefijo que realmente filtre queda anotado como «probado» y los demás como «preventivos»

#### Escenario: [BI91] Un trabajador con `DataSource` y sin guardia rompe la construcción

- **DADO** un contexto del trabajador con `DataSource` y sin la guardia
- **CUANDO** se ejecuta `./mvnw verify`
- **ENTONCES** la construcción falla señalando el proceso y la ausencia de la guardia

#### Escenario: [BI109] La guardia está instalada antes de crear el primer bean y se retira al cerrar

- **DADO** los tres procesos arrancados por su camino de producción y un procesador de prueba que observa el estado
  de la guardia al crearse el primer bean
- **CUANDO** se construye cada contexto y después se cierra
- **ENTONCES** en los tres la guardia ya estaba instalada cuando se crea el primer bean, y se retira al cerrar el
  contexto
- **Y** una variante que instala la guardia como bean (tarde) hace fallar la prueba

#### Escenario: [BI110] Los prefijos nuevos bloquean `DEBUG` y `TRACE` y dejan pasar `INFO` y superiores

- **DADO** cada uno de los seis prefijos protegidos y un prefijo parecido no cubierto
- **CUANDO** se registra un evento de nivel `DEBUG`, `TRACE`, `INFO` y `WARN` en cada uno
- **ENTONCES** `DEBUG` y `TRACE` se bloquean en los seis y pasan en el parecido, y `INFO` y `WARN` pasan en todos

### Requisito: El trabajador admite el paquete de trabajos y la guardia neutral; administración y portal siguen prohibiendo db-scheduler como bean, y cada paquete nuevo llega con su línea en el mismo pull request

`ProcessBeanPolicy` DEBE admitir en el trabajador el paquete de trabajos de `shared` (y su cableado), la guardia
neutral, la seguridad de `shared` que ofrece el `TransactionRunner`, la infraestructura de `shared`, la
verificación de auditoría, las métricas de cola y las capas de aplicación, infraestructura y cableado de
`identity` que usan sus manejadores, y NO DEBE admitir en él el cableado administrativo ni de seguridad. En
particular, el trabajador NO DEBE contener ningún bean de `com.confia.shared.crypto` (no tiene llave maestra de
columnas) y conserva todas sus prohibiciones actuales, incluidos `shared.security.token` y `shared.security.csrf`,
que prevalecen sobre el permiso de `shared.security`. `ProcessBeanPolicy` DEBE admitir en el portal exactamente
`shared.logging`, `shared.security` (solo el `TransactionRunner` y el proxy transaccional), y los paquetes de
trabajos de `shared` y su cableado (solo el puerto de programación), y conservar todas sus prohibiciones. El proceso administrativo y el del portal DEBEN seguir
prohibiendo cualquier bean de `com.github.kagkarlsson` (`SCHEDULER_PACKAGE`). El trabajador NO DEBE reutilizar
`SharedPlatformConfiguration` ni `IdentityConfiguration`: su configuración propia contiene solo el `DSLContext`
con el registro de ejecución apagado, el `TransactionRunner`, el reloj, el escritor de la bitácora sin el
decorador de origen de la petición, y los repositorios y casos de uso que usan los manejadores. Toda línea nueva
de `ProcessBeanPolicy` DEBE llegar en el mismo pull request que el paquete o el bean que la requiere
(ADR-0024).

#### Escenario: [BI92] El trabajador registra su cableado estrecho y nada administrativo

- **DADO** el proceso trabajador arrancado por su camino de producción
- **CUANDO** se inspecciona su contexto
- **ENTONCES** contiene el `DataSource`, el `DSLContext`, el `TransactionRunner`, el reloj, el escritor de la
  bitácora, el ejecutor, los manejadores y la guardia, todos dentro de su lista de permitidos
- **Y** no contiene el códec de tokens, Argon2, el cifrado de columna, el decorador de origen de la petición ni
  ningún bean de `SharedPlatformConfiguration` o `IdentityConfiguration`

#### Escenario: [BI93] Administración y portal siguen prohibiendo db-scheduler como bean

- **DADO** un fixture que registra un bean de `com.github.kagkarlsson` en el contexto administrativo y otro en el
  del portal
- **CUANDO** se ejecuta `./mvnw verify`
- **ENTONCES** la construcción falla señalando el proceso y el bean

#### Escenario: [BI94] Un paquete nuevo del trabajador sin línea rompe la construcción

- **DADO** un paquete nuevo con un bean en el contexto del trabajador y sin su línea en la lista de permitidos
- **CUANDO** se ejecuta `./mvnw verify`
- **ENTONCES** la construcción falla señalando el proceso, el bean y su paquete

#### Escenario: [BI111] El trabajador no tiene llaves de cifrado de columna ni pimienta

- **DADO** el proceso trabajador arrancado por su camino de producción sin `confia.crypto.column-master-key` ni
  `confia.identity.argon2-pepper`
- **CUANDO** se construye su contexto
- **ENTONCES** arranca sin error, la lista de beans de `identity` es exactamente la de su cableado estrecho, y un
  fixture con un bean de `com.confia.shared.crypto` en el trabajador hace fallar la construcción

#### Escenario: [BI112] El portal solo admite el cableado de programación

- **DADO** el contexto del portal y un fixture que agrega a él un bean de `identity`, de `organization` o un
  `DSLContext`
- **CUANDO** se ejecuta `./mvnw verify`
- **ENTONCES** el portal real pasa con sus paquetes admitidos y el fixture falla señalando el proceso, el bean y su
  paquete

### Requisito: La aserción positiva sobre db-scheduler reemplaza a la aserción vacua, con su control negativo

La verificación de aislamiento DEBE afirmar que el contexto del trabajador contiene exactamente un ejecutor de
db-scheduler y que los contextos administrativo y del portal no contienen ningún bean de
`com.github.kagkarlsson`, y que no hay hilos del ejecutor en administración ni en el portal. La prueba
`ProcessBeanIsolationTest.dbSchedulerAbsenceIsVacuousUntilChange9` DEBE reemplazarse por esa afirmación positiva y
NO DEBE declararse vacua. La afirmación vigente de que portal y trabajador no tienen ningún bean de
`com.confia.shared.observability` DEBE estrecharse a `com.confia.shared.observability.metrics`, para admitir en el
trabajador las métricas de cola (`com.confia.shared.observability.queue`).

#### Escenario: [BI95] El trabajador tiene su ejecutor y los otros dos procesos no

- **DADO** db-scheduler en el camino de clases y los tres procesos por su camino de producción
- **CUANDO** se ejecuta la verificación de aislamiento
- **ENTONCES** el trabajador tiene exactamente un ejecutor y los otros dos no tienen ningún bean de
  `com.github.kagkarlsson`

#### Escenario: [BI96] El control negativo detecta un ejecutor donde no corresponde y un trabajador sin ejecutor

- **DADO** un fixture con un ejecutor en el contexto administrativo y otro con un trabajador sin ejecutor
- **CUANDO** se ejecuta la verificación de aislamiento
- **ENTONCES** falla en los dos fixtures señalando el proceso y el bean

#### Escenario: [BI113] La ausencia de observabilidad se estrecha a las métricas y admite las de cola en el trabajador

- **DADO** los contextos del portal y del trabajador, y un fixture con un bean de `shared.observability.metrics`
  en cada uno
- **CUANDO** se ejecuta la verificación de aislamiento
- **ENTONCES** el trabajador real, con su bean de `shared.observability.queue`, pasa; el portal real pasa sin ningún
  bean de observabilidad; y los dos fixtures fallan señalando el proceso y el bean

#### Escenario: [BI114] Una autoconfiguración nueva del iniciador sin excluir rompe la construcción

- **DADO** la lista de autoconfiguraciones del iniciador de la versión fijada y un fixture que agrega una más sin
  excluirla en el punto de entrada administrativo o del portal
- **CUANDO** se ejecuta `./mvnw verify`
- **ENTONCES** la construcción falla señalando la clase y el punto de entrada

### Requisito: Los inventarios de ausencia que el cambio 9 rompe se invierten, cada uno con su control negativo

El sistema DEBE invertir, en el mismo cambio que rompe cada ausencia, las pruebas de inventario que la
declaraban, y cada inversión DEBE fallar con su control negativo:
`ProcessBeanIsolationTest.dbSchedulerAbsenceIsVacuousUntilChange9` (ver el requisito anterior);
`AuditScopeExclusionInventoryTest` (la prueba que no admite dependencias de programación pasa a admitir
exclusivamente db-scheduler, y la que no admite `scheduled_tasks` en las migraciones pasa a exigir
exactamente `V10`, y ambas siguen negando Quartz, JobRunr y toda otra tabla de programación);
`IdentityScopeExclusionInventoryTest` (la ausencia del adaptador de emisión se retira y se sustituye por la
afirmación positiva; la de la rotación de DEK se conserva y se reasigna a `data-encryption-key-batch-rotation`);
`RolePrivilegeMatrixIT`, `IdempotencyKeyPrivilegeIT` (ver el requisito de privilegios) y `MultiTenantSchemaIT`
(su lista de excepciones, que ya nombra `scheduled_tasks`, pasa a evaluar la tabla real: la exención de
`institution_id`, de seguridad de fila y de prefijo aplica solo a las cuatro tablas del catálogo cerrado).

#### Escenario: [BI97] `AuditScopeExclusionInventoryTest` admite solo db-scheduler y `V10`

- **DADO** `AuditScopeExclusionInventoryTest` invertida
- **CUANDO** se ejecuta con el código real, con un fixture que añade Quartz, con otro que añade una tabla de
  programación distinta de `scheduled_tasks` y con otro que añade `scheduled_tasks` en una migración que no es
  `V10`
- **ENTONCES** pasa con el real y falla en los tres fixtures

#### Escenario: [BI98] `IdentityScopeExclusionInventoryTest` invierte el adaptador y conserva la rotación de DEK

- **DADO** `IdentityScopeExclusionInventoryTest` actualizada
- **CUANDO** se ejecuta con el código real, con un fixture sin el adaptador de emisión y con un fixture que
  registra una tarea de recifrado de DEK
- **ENTONCES** pasa con el real, falla en el fixture sin adaptador (exige exactamente uno) y falla en el de la
  tarea de recifrado

#### Escenario: [BI99] `MultiTenantSchemaIT` evalúa la tabla real y no exime nada más

- **DADO** `MultiTenantSchemaIT` con `scheduled_tasks` presente
- **CUANDO** se ejecuta con el esquema real y con un fixture de tabla de negocio sin `institution_id` que se
  nombra como excepción
- **ENTONCES** pasa con el real y falla en el fixture, y la lista de excepciones sigue siendo de cuatro nombres

## MODIFIED Requirements

### Requisito: Dependencias de persistencia y de tareas prohibidas

El sistema NO DEBE permitir en `apps/api` a Hibernate, Jakarta Persistence, Spring Data JPA,
Spring Data JDBC, Quartz, JobRunr ni otra biblioteca de programación de tareas **distinta de db-scheduler**
(ADR-0015, ADR-0016). db-scheduler es la única biblioteca de programación admitida y su versión se fija una sola
vez. El controlador JDBC de PostgreSQL y el JDBC del JDK no están prohibidos: jOOQ y el
componente transaccional se apoyan en ellos.
(Previously: ninguna biblioteca de programación de tareas estaba permitida, porque db-scheduler aún no se
incorporaba.)

#### Escenario: Se agrega Hibernate

- **DADO** el POM padre sin dependencias prohibidas
- **CUANDO** un módulo agrega Hibernate
- **ENTONCES** `./mvnw verify` falla señalando la dependencia prohibida

#### Escenario: Sin dependencias prohibidas

- **DADO** el mismo POM padre
- **CUANDO** ningún módulo declara una dependencia de la lista prohibida
- **ENTONCES** la construcción no falla por esta causa

#### Escenario: [BI100] db-scheduler está admitido y otra biblioteca de programación no

- **DADO** el POM padre con db-scheduler en su versión fijada
- **CUANDO** un módulo agrega Quartz, JobRunr o cualquier otra biblioteca de programación de tareas
- **ENTONCES** `./mvnw verify` falla señalando la dependencia
- **Y** con solo db-scheduler la construcción no falla por esta causa

### Requisito: Prohibición del SQL plano de jOOQ fuera de la lista aprobada

El sistema DEBE romper la construcción si una clase invoca un método de jOOQ que reciba una
cadena de SQL plano (por ejemplo `DSLContext.fetch(String)` o `resultQuery(String)`), salvo que la
clase figure en una lista explícita y cerrada de reportes aprobados (ADR-0015, regla 9 y
cumplimiento 5). La lista contiene exactamente el lector de las métricas de cola, cuyas consultas son de solo
lectura sobre `scheduled_tasks` y nunca seleccionan la columna `task_data`.
(Previously: la lista estaba vacía porque ningún reporte existía todavía.)

#### Escenario: SQL plano fuera de la lista aprobada

- **DADO** una clase de `infrastructure` que invoca un método de jOOQ con una cadena de SQL plano
  y no figura en la lista aprobada
- **CUANDO** se ejecuta `./mvnw verify`
- **ENTONCES** la construcción falla señalando la clase infractora

#### Escenario: [BI101] El lector de métricas de cola es el único con SQL plano y no lee `task_data`

- **DADO** la lista aprobada con el lector de métricas de cola
- **CUANDO** se ejecuta `./mvnw verify` con el código real, con un fixture del lector que selecciona
  `task_data` y con un fixture de otra clase con SQL plano
- **ENTONCES** pasa con el código real, evaluando la clase real del lector, y falla en los dos fixtures

### Requisito: Permisos de acceso a la tabla de tokens de recuperación por rol de base de datos

El sistema DEBE conceder sobre la tabla de tokens de recuperación de contraseña, exactamente y sin
uno más ni uno menos (`docs/03-seguridad.md` §6.1 y su adenda): a `confia_admin_app`, `SELECT`,
`INSERT` y `UPDATE`, **sin `DELETE`**, porque las filas vencidas las elimina la función de purga `SECURITY DEFINER`
del cambio 9 y no un `DELETE` directo (decisión P2 del propietario del 2026-10-24); a `confia_portal_app`,
**ningún privilegio**, por ser datos de personal; a `confia_readonly`, solo `SELECT`, sujeto a la misma
política de fila por institución; a `confia_backup`, el alcance de `pg_read_all_data` que ya tiene
sobre el resto del esquema; a `confia_owner`, ningún privilegio adicional a los de propietario del
esquema, sin el atributo `BYPASSRLS`. El sistema DEBE extender `RolePrivilegeMatrixIT` con las filas
de la tabla nueva para los cinco roles.
(Previously: sin `DELETE` porque las filas vencidas se retenían hasta que el cambio 9 decidiera la purga.)

#### Escenario: La matriz de privilegios cubre la tabla nueva para los cinco roles

- **DADO** `RolePrivilegeMatrixIT` extendida con las filas de la tabla de tokens de recuperación
- **CUANDO** se ejecuta `./mvnw verify`
- **ENTONCES** confirma exactamente los privilegios de este requisito para cada uno de los cinco
  roles, sin uno más ni uno menos

#### Escenario: `confia_admin_app` puede leer, insertar y actualizar, pero no borrar

- **DADO** el rol `confia_admin_app` conectado con el contexto de una institución
- **CUANDO** intenta `SELECT`, `INSERT` y `UPDATE` sobre la tabla de tokens de recuperación, y
  después intenta `DELETE`
- **ENTONCES** las tres primeras operaciones se permiten y el `DELETE` se rechaza por falta de
  privilegio

#### Escenario: `confia_portal_app` no tiene ningún privilegio sobre la tabla nueva

- **DADO** el rol `confia_portal_app` conectado con el contexto de una institución
- **CUANDO** intenta `SELECT`, `INSERT`, `UPDATE` o `DELETE` sobre la tabla de tokens de recuperación
- **ENTONCES** las cuatro operaciones se rechazan por falta de privilegio

### Requisito: Permisos de acceso a las tablas de familias y tokens de refresco por rol de base de datos y por columna

El sistema DEBE conceder sobre cada una de las dos tablas nuevas, exactamente y sin uno más ni uno menos
(`docs/03-seguridad.md` §6.1): a `confia_admin_app`, `SELECT` e `INSERT` y `UPDATE` **solo sobre las
columnas que cambian de estado**, y **ningún `DELETE`**, porque las filas las elimina la función de purga
`SECURITY DEFINER` del cambio 9 y no un `DELETE` directo (decisión P2 del propietario del 2026-10-24). Las
columnas actualizables DEBEN ser, en la familia, el último uso y la
revocación (instante y motivo); en el token, el consumo (instante y huella del agente de usuario de
quien lo presentó), el sucesor y el valor cifrado del sucesor. `SELECT ... FOR UPDATE` sobre las dos
tablas DEBE estar permitido a `confia_admin_app`, porque el bloqueo de la familia serializa la rotación.
El resto de las columnas (`token_hash`, los vencimientos, la huella del agente de usuario, la IP inicial,
`institution_id` y los identificadores) DEBEN ser inmutables para el rol de aplicación. A
`confia_portal_app`, **ningún privilegio**; a `confia_readonly`, solo `SELECT`, sujeto a la misma
política de fila; a `confia_backup`, el alcance de `pg_read_all_data` que ya tiene; a `confia_owner`,
ningún privilegio adicional a los de propietario del esquema, sin el atributo `BYPASSRLS`. El sistema
DEBE extender `RolePrivilegeMatrixIT` con las filas de las dos tablas para los cinco roles, incluido el
nivel de columna del `UPDATE`.
(Previously: ningún `DELETE` porque las filas se retenían hasta que el cambio 9 decidiera la purga.)

#### Escenario: [BI07] La matriz de privilegios cubre las dos tablas para los cinco roles

- **DADO** `RolePrivilegeMatrixIT` extendida con las filas de las dos tablas nuevas, con el nivel de
  columna del `UPDATE`
- **CUANDO** se ejecuta `./mvnw verify`
- **ENTONCES** confirma exactamente los privilegios de este requisito para cada uno de los cinco roles,
  sin uno más ni uno menos

#### Escenario: [BI08] `confia_admin_app` no puede borrar en ninguna de las dos tablas

- **DADO** el rol `confia_admin_app` conectado con el contexto de una institución
- **CUANDO** intenta `SELECT`, `INSERT` y `UPDATE` de una columna concedida sobre cada tabla, y después
  intenta `DELETE`
- **ENTONCES** las tres primeras operaciones se permiten y el `DELETE` se rechaza por falta de privilegio
- **Y** `SELECT ... FOR UPDATE` sobre cada tabla se permite

#### Escenario: [BI09] El `UPDATE` fuera de las columnas concedidas se rechaza

- **DADO** el rol `confia_admin_app` con el contexto de su institución y una fila en cada tabla
- **CUANDO** intenta actualizar `token_hash`, `expires_at`, `absolute_expires_at`, la huella del agente
  de usuario, la IP inicial e `institution_id`
- **ENTONCES** cada intento se rechaza por falta de privilegio sobre la columna

#### Escenario: [BI10] `confia_portal_app` no tiene ningún privilegio

- **DADO** el rol `confia_portal_app` conectado con el contexto de una institución
- **CUANDO** intenta `SELECT`, `INSERT`, `UPDATE` o `DELETE` sobre cualquiera de las dos tablas
- **ENTONCES** las cuatro operaciones se rechazan por falta de privilegio

### Requisito: El contexto del portal no contiene beans de otros puntos de entrada ni de módulos administrativos

El contexto del proceso del portal NO DEBE contener ningún bean de los paquetes de entrada del
proceso administrativo (`com.confia.bootstrap.admin`) ni del proceso trabajador
(`com.confia.bootstrap.worker`), ni de los módulos `invoicing`, `cashbox` y `reconciliation`, ni de
`identity` mientras ese módulo sea solo para personal. Esta prohibición nominal DEBE regir además de
la lista de permitidos del requisito anterior, no en su lugar. El sistema DEBE romper la
construcción si el contexto del portal contiene alguno de ellos (ADR-0003, verificación 1, en su
parte de grafo de beans). El portal NO DEBE contener los beans de cableado de producción que solo
registra el proceso administrativo (`DSLContext`, repositorios y casos de uso de identidad). Por la decisión D-N1
del propietario del 2026-10-24 (que revierte la decisión D5 de `web-edge-foundations`), el portal SÍ DEBE contener
su `DataSource` (rol `confia_portal_app`) y el cableado mínimo de programación: el `TransactionRunner`, el proxy
transaccional y el puerto de programación de tareas, y ningún `Scheduler`. El portal SÍ DEBE contener su cadena de
seguridad que deniega todo.
(Previously: el portal NO DEBÍA contener el `DataSource`, el `TransactionRunner` ni el `DSLContext` del cableado de
producción del proceso administrativo; ahora solo se le prohíben el `DSLContext`, los repositorios y los casos de
uso, y se le exigen el `DataSource`, el `TransactionRunner`, el proxy transaccional y el puerto de programación.)

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

#### Escenario: El portal no hereda el cableado administrativo pero sí tiene su cableado de programación

- **DADO** el contexto del portal, aunque el proceso administrativo registre `DSLContext` y los casos de uso de
  identidad
- **CUANDO** se inspecciona el contexto del portal
- **ENTONCES** ni el `DSLContext` ni ningún repositorio ni caso de uso de identidad existen en él
- **Y** contiene su `DataSource`, el `TransactionRunner`, el proxy transaccional y el puerto de programación, sin
  ningún `Scheduler`
- **Y** contiene su cadena de seguridad que deniega todo y solo los beans del borde permitidos para el portal
  (`shared.web.edge`, `shared.web.request` y `shared.web.problem`)

### Requisito: El contexto del trabajador no contiene beans de los otros puntos de entrada ni sus importaciones

El contexto del proceso trabajador NO DEBE contener ningún bean de `com.confia.bootstrap.admin` ni
de `com.confia.bootstrap.portal`, ni los beans que esos puntos de entrada importan para su propia
superficie de API, `ContractSchemas` y `ProcessApiInfo`. El trabajador DEBE declarar sus propios
módulos y NO DEBE heredar el grafo de beans administrativo por el hecho de usar el rol de base de datos
administrativo (aclaración de ADR-0003 que fija ADR-0024); su configuración propia es estrecha y NO reutiliza
`SharedPlatformConfiguration` ni `IdentityConfiguration`. El trabajador SÍ DEBE contener un `DataSource`, un
`DSLContext`, un `TransactionRunner` y exactamente un ejecutor de db-scheduler. El trabajador
NO DEBE contener ninguna cadena de seguridad, ningún bean de Spring Security, `RateLimiter` ni materializador del
retardo, porque no tiene servidor web. El sistema DEBE romper la construcción si el contexto del
trabajador contiene alguno de los prohibidos.
(Previously: el trabajador tampoco podía contener un `DataSource`, y no se enumeraba su cableado propio.)

#### Escenario: El trabajador recibe una importación ajena

- **DADO** el contexto del trabajador con un bean `ContractSchemas` o `ProcessApiInfo`, o con un
  bean de `bootstrap.admin` o de `bootstrap.portal`
- **CUANDO** se ejecuta `./mvnw verify`
- **ENTONCES** la construcción falla señalando el bean ajeno

#### Escenario: El trabajador arranca sin servidor web y con su cableado de base de datos

- **DADO** el proceso trabajador arrancado por el camino de producción, sin servidor web
- **CUANDO** se inspecciona su contexto
- **ENTONCES** contiene un `DataSource`, un `DSLContext`, un `TransactionRunner` y un ejecutor, no contiene ningún
  bean de los otros dos puntos de entrada ni sus importaciones, y la verificación evalúa el contexto real del
  trabajador

#### Escenario: El trabajador no recibe el borde web

- **DADO** el contexto del trabajador con un bean `SecurityFilterChain`, cualquier otro bean de
  Spring Security, `RateLimiter` o materializador del retardo
- **CUANDO** se ejecuta `./mvnw verify`
- **ENTONCES** la construcción falla señalando el bean ajeno

### Requisito: Los puntos de entrada se registran de forma explícita, sin escaneo implícito

Cada uno de los tres puntos de entrada DEBE residir en su propio subpaquete de
`com.confia.bootstrap` (`admin`, `portal` y `worker`) y DEBE declarar de forma explícita lo que
importa. Los puntos de entrada NO DEBEN usar `@SpringBootApplication` ni `@ComponentScan`. Ninguno de los tres
puntos de entrada DEBE excluir `DataSourceAutoConfiguration`: el proceso administrativo, el trabajador y el portal
registran cada uno su `DataSource` (decisión D5 del cambio `web-edge-foundations`, ampliada por el cambio 9 al
trabajador y **revertida para el portal** por la decisión D-N1 del propietario del 2026-10-24: el portal recibe su
primer `DataSource`, con el rol `confia_portal_app`). El proceso administrativo y el del portal DEBEN excluir, en
el código de su punto de entrada, todas las clases de autoconfiguración del iniciador de db-scheduler; el trabajador
NO DEBE excluirla. `ConfiaApplication` DEBE seguir siendo el único método `main` y DEBE conservar la selección del
proceso por `APP_PROFILE` y el arranque del trabajador sin servidor web. El sistema DEBE romper la
construcción si un punto de entrada vuelve a declarar un escaneo de componentes.
(Previously: el portal y el trabajador conservaban la exclusión de `DataSourceAutoConfiguration` y solo el proceso
administrativo registraba un `DataSource`, y no se excluía la autoconfiguración de db-scheduler.)

#### Escenario: Un punto de entrada vuelve a escanear

- **DADO** un punto de entrada que declara `@SpringBootApplication` o `@ComponentScan`
- **CUANDO** se ejecuta `./mvnw verify`
- **ENTONCES** la construcción falla señalando el punto de entrada y la anotación prohibida

#### Escenario: Los tres puntos de entrada cumplen la forma explícita

- **DADO** `AdminApplication`, `PortalApplication` y `WorkerApplication` en `bootstrap.admin`,
  `bootstrap.portal` y `bootstrap.worker`
- **CUANDO** se ejecuta `./mvnw verify`
- **ENTONCES** ninguno usa `@SpringBootApplication` ni `@ComponentScan`, ninguno excluye
  `DataSourceAutoConfiguration`, `AdminApplication` y `PortalApplication` excluyen la autoconfiguración de
  db-scheduler y `WorkerApplication` no, y `ConfiaApplication` sigue arrancando cada proceso según `APP_PROFILE`

#### Escenario: [BI115] Un punto de entrada que excluye el `DataSource` rompe la construcción

- **DADO** `PortalApplication`, `AdminApplication` o `WorkerApplication` con la exclusión de
  `DataSourceAutoConfiguration`
- **CUANDO** se ejecuta `./mvnw verify`
- **ENTONCES** la construcción falla señalando el punto de entrada (el escenario vigente del portal, que fallaba
  al *dejar* de excluirla, queda invertido por la decisión D-N1)

#### Escenario: [BI102] Administración o portal sin la exclusión de db-scheduler rompen la construcción

- **DADO** `AdminApplication` o `PortalApplication` sin la exclusión de la autoconfiguración de db-scheduler
- **CUANDO** se ejecuta `./mvnw verify`
- **ENTONCES** la construcción falla señalando el punto de entrada, porque la verificación de aislamiento
  encuentra un ejecutor donde no corresponde

### Requisito: El proceso administrativo arranca sin conexión a la base de datos y sin migrar al arrancar

El proceso administrativo DEBE registrar el `DataSource` de producción y DEBE arrancar su contexto
aunque no exista ninguna base de datos accesible: el arranque NO DEBE realizar una validación
inicial bloqueante de la conexión y NO DEBE ejecutar migraciones de Flyway. Las migraciones siguen
siendo un paso separado del arranque. El trabajador DEBE registrar su propio `DataSource` con el usuario de
base de datos de ese proceso y, igual que el administrativo, su contexto NO DEBE exigir una base accesible ni
ejecutar migraciones para construirse. El portal DEBE registrar también su `DataSource`, con el usuario de base de
datos de ese proceso (`confia_portal_app`), por la decisión D-N1 del propietario del 2026-10-24 (que revierte la
decisión D5 y la nota de `docs/05` del 2026-10-04), y su contexto, igual que los otros dos, NO DEBE exigir una base
accesible ni ejecutar migraciones para construirse. Las pruebas de arranque `*Test` de los tres procesos DEBEN
seguir ejecutándose sin Docker y sin base de datos.
(Previously: ni el trabajador ni el portal registraban `DataSource` (decisión D5 del propietario); el trabajador
pasó a registrarlo con el cambio 9 y el portal con la decisión D-N1.)

#### Escenario: El proceso administrativo arranca con la base de datos inalcanzable

- **DADO** el proceso administrativo arrancado por su camino de producción con una URL de base de
  datos que no responde en ningún puerto
- **CUANDO** se construye su contexto
- **ENTONCES** el contexto arranca sin error y contiene exactamente un bean `DataSource`
- **Y** ninguna migración de Flyway se ejecuta ni se consulta la tabla de historial durante el
  arranque

#### Escenario: El trabajador y el portal tienen `DataSource`, y solo el trabajador tiene `DSLContext`

- **DADO** los procesos del portal y del trabajador arrancados por su camino de producción
- **CUANDO** se inspecciona cada contexto
- **ENTONCES** el trabajador contiene exactamente un bean `DataSource`, un `TransactionRunner` y un
  `DSLContext`
- **Y** el portal contiene exactamente un bean `DataSource` y un `TransactionRunner`, y no contiene `DSLContext`
  (decisión D-N1)

#### Escenario: [BI116] El contexto del portal se construye con la base de datos inalcanzable

- **DADO** el proceso del portal arrancado por su camino de producción con una URL de base de datos que no
  responde en ningún puerto, en una máquina sin Docker
- **CUANDO** se construye su contexto
- **ENTONCES** el contexto se construye sin error, sin migraciones ni validación inicial bloqueante de la
  conexión, y contiene su `DataSource`

#### Escenario: [BI103] El contexto del trabajador se construye con la base de datos inalcanzable

- **DADO** el proceso trabajador arrancado por su camino de producción con una URL de base de datos que no
  responde en ningún puerto, en una máquina sin Docker
- **CUANDO** se construye su contexto
- **ENTONCES** el contexto se construye sin error, sin migraciones ni validación inicial bloqueante de la
  conexión

#### Escenario: Las pruebas de arranque no necesitan Docker

- **DADO** una máquina sin Docker ni PostgreSQL
- **CUANDO** se ejecutan las pruebas `*Test` que arrancan los tres procesos
- **ENTONCES** todas pasan, y ninguna de ellas es una prueba `*IT` ni usa Testcontainers

## REMOVED Requirements

### Requisito: Ausencia de purga física de claves caducadas (brecha con destino: cambio 9)

(Reason: el cambio 9 entrega la purga física de `shared_idempotency_key` mediante una función `SECURITY DEFINER`
(P2) con ventana de 24 horas (P3); el escenario «Una fila caducada sigue existiendo porque no hay ningún trabajo
de purga desplegado» deja de ser cierto, y la tensión de privilegios queda resuelta sin conceder `DELETE`.)
(Migration: «Purga de claves de idempotencia caducadas, 24 horas después de `expires_at`, con índice por
`expires_at`» y «Las purgas usan funciones `SECURITY DEFINER` de `confia_owner`, con `EXECUTE` solo para
`confia_admin_app`, y no se concede `DELETE` genérico».)

### Requisito: La aserción sobre db-scheduler en administración y portal es vacua hasta el cambio 9 (brecha con destino: cambio 9)

(Reason: el cambio 9 incorpora db-scheduler al camino de clases; la aserción deja de ser vacua y el escenario «La
aserción es vacua y está declarada como tal» deja de ser cierto. El escenario «La ausencia de db-scheduler se
vuelve efectiva con la biblioteca presente» se conserva en [BI95] y [BI96].)
(Migration: «La aserción positiva sobre db-scheduler reemplaza a la aserción vacua, con su control negativo».
`ProcessBeanIsolationTest.dbSchedulerAbsenceIsVacuousUntilChange9` se reemplaza por la afirmación positiva.)
