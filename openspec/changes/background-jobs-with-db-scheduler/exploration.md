# Exploración: `background-jobs-with-db-scheduler` (cambio 9 de F0)

Repositorio: `main` en b76beda. Exploración de solo lectura del 2026-10-24.

## 1. Estado actual

**Plan y deuda heredada.** La fila 9 de `foundations-plan/exploration.md` (línea 39) dice: «Entregable 12», «Criterio 5», depende de 5 y 6, «12 a 13 tareas». El quinto corte (líneas 275-307) lo ubica entre `session-endpoints-cookie-and-csrf` (S2, archivado el 2026-10-24) y `password-recovery-endpoints` (S3). Su alcance declarado es: «Ejecutor de db-scheduler en el trabajador, adaptador de programación de la emisión de recuperación y purgas». La fila 14, `transactional-email-adapter`, depende del 9.

Los cambios archivados y las especificaciones vigentes dejan estas deudas con destino «cambio 9»:

| # | Deuda | Fuente |
|---|---|---|
| a | Adaptador del puerto `PasswordResetIssuanceScheduler` | `identity/application/PasswordResetIssuanceScheduler.java`; especificación `identity`, requisito de la línea 1170 |
| b | Purga de `identity_password_reset_token` (V7) | Especificación `identity` (línea 1213); nota del 2026-10-03 en `docs/08` |
| c | Purga de `identity_refresh_token_family` e `identity_refresh_token` (V8), con `ip_first_seen` y el vaciado del `successor_token_ciphertext` residual | Especificación `identity` (línea 2251); nota del 2026-10-11 en `docs/08` |
| d | Purga de `shared_idempotency_key` con `expires_at` vencido | Especificación `build-integrity` (línea 983), que además señala la tensión de privilegios: ningún rol tiene `DELETE` |
| e | Purga de `identity_login_backoff` y, por analogía, de `identity_mfa_totp_backoff` | `foundations-plan` (líneas 134-138); comentario de V5 |
| f | Las tres cadencias del verificador de la cadena de auditoría: horaria, diaria y semanal | Especificación `audit-trail` (línea 292); `AuditChainVerifier` ya existe como rutina invocable |
| g | Rotación por lotes de las DEK `retired` | Especificación `identity` (línea 809). Hoy la superficie de gestión de llaves no ofrece ninguna operación de rotación |
| h | Condición dura 4 | `foundations-plan` (líneas 169-183) |
| i | Criterio de salida 5: «Una tarea programada dentro de una transacción revertida no existe, y solo el proceso `confia-worker` ejecuta tareas» | `docs/09`, línea 252, todavía sin marcar |

**Código del proceso trabajador.**

- `WorkerApplication` excluye `DataSourceAutoConfiguration`, `SecurityAutoConfiguration` y `UserDetailsServiceAutoConfiguration`, y no importa nada. Su Javadoc dice que no hay base de datos «until change 9».
- `ProcessBeanPolicy.WORKER` solo permite `com.confia.bootstrap.worker`. Prohíbe:
  - `com.confia.shared.web` y `com.confia.shared.platform`;
  - `org.springframework.security` y `org.springframework.boot.security`;
  - `shared.security.token`, `shared.security.csrf` y `shared.web.session`;
  - `com.confia.organization`.
- `SharedPlatformConfiguration` es solo administrativa. Reúne el `DSLContext` (con `Settings.withExecuteLogging(false)`), `TransactionRunner`, el reloj, el `AuditLogWriter` decorado, la idempotencia y el cifrado de columna.
- Administración y portal prohíben `com.github.kagkarlsson` (`SCHEDULER_PACKAGE`).
- `SensitiveLogGuard` vive en `com.confia.shared.web.request`, que el trabajador tiene prohibido. Su Javadoc ya cita la condición 4.
- `IdentityConfiguration` (31 beans) pertenece al proceso administrativo y arrastra el token, Argon2 y el cifrado de columna. Los repositorios de identidad solo necesitan `DSLContext`.
- `TransactionRunner` es el único componente transaccional (`shared/security`). Recibe `SecurityContext(actorId, actorKind, institutionId, requestId)` y reintenta de forma acotada ante 40001 y 40P01.
- `IssuePasswordResetToken`, que será el cuerpo del futuro manejador, recibe un `SecurityContext` de actor `system` con la institución de la tarea. Necesita un `PasswordResetLinkSender`, que no tendrá adaptador hasta el cambio 14.

**Base de datos.**

- Existen las migraciones V1 a V9. La tabla `scheduled_tasks` no existe.
- `docs/05` §6 y ADR-0017 asignan el rol `confia_admin_app` también al trabajador.
- ADR-0017 concede a `confia_admin_app` `SELECT`, `INSERT`, `UPDATE` y `DELETE` sobre `scheduled_tasks`. Al portal le concede `INSERT` y `SELECT`, solo si el cliente lo exige.
- Ningún rol tiene `DELETE` sobre las tablas de negocio. `RolePrivilegeMatrixIT` lo afirma tabla por tabla.

**Dependencias.**

- Los POM no incluyen db-scheduler, Micrometer ni Actuator.
- `maven-enforcer-plugin` prohíbe Quartz y JobRunr (`apps/api/pom.xml`, líneas 389-390).
- Hay una regla de `dependencyConvergence`.

## 2. Qué debe entregar el cambio 9

**Núcleo (criterio 5 y condición 4):**

1. **Dependencia y migración.** La dependencia queda fijada. La migración V10 crea `scheduled_tasks` con el DDL de la versión fijada y los permisos exactos de ADR-0017.
2. **Ejecutor solo en `confia-worker`.** El trabajador recupera su `DataSource` y queda sujeto a la condición 4.
3. **Programación transaccional.** Administración y portal programan tareas con un cliente liviano, dentro de la transacción del `TransactionRunner`.
4. **Envoltura de tarea.** Lleva identificadores, metadatos de correlación, actor e institución (`TracedJob`, `docs/07` §3.3), sin datos personales.
5. **Reintentos y estado.** Reintentos acotados con espera exponencial por tipo de tarea, estado fallido visible, métricas de cola y estado degradado.
6. **Pruebas de ADR-0016 §Cumplimiento:**
   - atomicidad con transacción confirmada y revertida;
   - solo el trabajador ejecuta;
   - doble ejecución;
   - lista aprobada de campos;
   - contexto de seguridad por institución.
7. **Emisión de recuperación.** Adaptador de `PasswordResetIssuanceScheduler` y su manejador (`IssuePasswordResetToken`).
8. **Purgas** (b) a (e), con la decisión sobre `DELETE` de la sección 6.

**Deudas cuyo alcance debe decidir el propietario** (pregunta P1 de la sección 9): (f) las cadencias de auditoría y (g) la rotación de DEK.

## 3. Condiciones duras que cierra y prohibiciones

**Cierra:**

- **Condición 4.** El trabajador no recibe `DataSource` ni ejecuta SQL sin la protección de registros activa y sin una prueba sobre el proceso real. Con `org.jooq` y la raíz en `DEBUG`, un valor enlazado distintivo no debe aparecer en ningún evento.
- **Criterio 5** de F0.
- Las brechas (a) a (e) y, según P1, (f) y (g).

**No debe:**

- usar `@Scheduled`, `@EnableScheduling`, `@Async` ni otra biblioteca de programación;
- ejecutar tareas en administración ni en el portal;
- editar las migraciones V1 a V9 (las nuevas son V10 en adelante);
- poner datos personales ni importes en los datos de tarea;
- entregar los endpoints de recuperación (S3), el adaptador de correo (cambio 14), Redis ni la dimensión por IP (cambio 11);
- ampliar la lista de tablas técnicas de ADR-0017, que hoy son `scheduled_tasks`, `event_publication`, `flyway_schema_history` y la tabla raíz;
- editar el texto de las condiciones del plan: solo se agrega una nota fechada, como hicieron los cambios anteriores.

## 4. Áreas afectadas

**Producción:**

- **Maven:** en `apps/api/pom.xml` y `apps/api/app/pom.xml`, la versión fijada en `dependencyManagement`, la dependencia del iniciador y la verificación de convergencia.
- **Migraciones:** `.../db/migration/V10__create_scheduled_tasks.sql`, nueva. Según P2, también una V11 de privilegios o de funciones de purga.
- **Puntos de entrada:**
  - `bootstrap/worker/WorkerApplication.java` deja de excluir `DataSourceAutoConfiguration` e importa configuraciones propias. Hay que decidir si conserva las exclusiones de seguridad.
  - `bootstrap/admin/AdminApplication.java` y `PortalApplication.java` excluyen la autoconfiguración de db-scheduler y registran el cliente liviano.
- **Paquete nuevo de trabajos,** por ejemplo `com.confia.shared.jobs`: puerto de programación, envoltura, serializador JSON y configuración del trabajador. El trabajador debe poder importarlo, así que no puede ser `shared.web` ni `shared.platform`.
- **Guardia de registros:** reubicarla o duplicarla fuera de `shared.web`.
- **Identidad (`identity/...`):** adaptador del puerto, manejador, casos de uso de purga y una configuración estrecha del trabajador, distinta de `IdentityConfiguration`.
- **Observabilidad:** un componente de `shared` con las consultas de solo lectura sobre `scheduled_tasks`, añadido a la lista aprobada de SQL plano.

**Pruebas y reglas que cambian de signo:**

- `ProcessBeanPolicy` (`WORKER`, `ADMIN` y `PORTAL`) y `ProcessBeanIsolationTest.dbSchedulerAbsenceIsVacuousUntilChange9`. Esta última falla a propósito cuando db-scheduler entra al classpath, y se reemplaza por la afirmación positiva sobre el trabajador.
- `AuditScopeExclusionInventoryTest`: sus dos pruebas, la que no admite dependencias de programación y la que no admite `scheduled_tasks` en las migraciones.
- `IdentityScopeExclusionInventoryTest`: `noScheduledJobReencryptsARetiredDataEncryptionKeyYet` y la prueba de ausencia del adaptador de emisión.
- `RolePrivilegeMatrixIT` e `IdempotencyKeyPrivilegeIT`, que hoy afirman que no hay `DELETE`.
- `MultiTenantSchemaIT`: su lista de excepciones ya nombra `scheduled_tasks`.
- `IdempotencyExpiryIT`, `IdempotencyScopeExclusionInventoryTest`, `JooqPasswordResetTokenRepositoryIT`, `RefreshGraceWindowIT` y `ColumnEncryptionIT`.
- `RequestPasswordResetIT`: el doble de la programación pasa a ser el adaptador real, en una prueba nueva.

**Documentación:**

- `docs/03` §6.1 (`DELETE`), `docs/07` §11 (tabla de tareas), `docs/08` §6 (notas editoriales de retención), `docs/09` (marcar el criterio 5) y `docs/05` §6 (usuario del trabajador).
- Notas fechadas en `foundations-plan/exploration.md`.
- Especificaciones `identity`, `build-integrity` y `audit-trail`.

## 5. Puntos de diseño abiertos

### D1. Versión y compatibilidad con Spring Boot 4.1.1

**Evidencia:**

- La última versión es la v16.12.0, del 2026-05-29 ([versiones](https://github.com/kagkarlsson/db-scheduler/releases), [API de GitHub](https://api.github.com/repos/kagkarlsson/db-scheduler/releases/latest)).
- El iniciador de Spring Boot 4 llegó en la v16.7.0 (enero de 2026). El artefacto es `db-scheduler-spring-boot-4-starter` ([Maven Central](https://central.sonatype.com/artifact/com.github.kagkarlsson/db-scheduler-spring-boot-4-starter)).
- Dependencias del iniciador:
  - obligatorias: `spring-boot`, `spring-boot-sql`, `spring-boot-jdbc`, `spring-boot-autoconfigure`, `micrometer-core` y `jackson-annotations`;
  - opcionales: `spring-boot-micrometer-metrics`, `spring-boot-health` y Jackson 3 (`tools.jackson`).
- Requiere Java 17 o superior ([README](https://github.com/kagkarlsson/db-scheduler/blob/master/README.md)).

**Sin verificar:**

- que el iniciador arranque contra Spring Boot 4.1.1, el BOM del proyecto;
- la compatibilidad exacta con Jackson 3 y con `dependencyConvergence`.

Antes de cerrar el diseño hace falta un sondeo: arranque real del trabajador y `./mvnw validate`. Además, `UPGRADING.md` de `master` menciona una línea 16.13.x, pero la versión publicada que consta es la 16.12.0, así que hay que releer la lista de versiones al fijarla.

**Opciones:**

- A. El iniciador en el trabajador.
- B. Solo el núcleo, con cableado propio.
- C. El núcleo en todos los procesos y el iniciador solo en el trabajador.

**Recomendación:** A para el ejecutor, y construir a mano el `SchedulerClient` del núcleo en administración y portal. La versión se fija en el `dependencyManagement` del POM padre y el sondeo queda registrado en el diseño.

### D2. El iniciador no sirve tal cual para administración y portal

**Evidencia** ([`DbSchedulerAutoConfiguration`](https://raw.githubusercontent.com/kagkarlsson/db-scheduler/master/db-scheduler-spring-boot-starter-parent/db-scheduler-spring-boot4-starter/src/main/java/com/github/kagkarlsson/scheduler/boot/autoconfigure/DbSchedulerAutoConfiguration.java)):

- Se activa con `@ConditionalOnBean(DataSource.class)` y `@ConditionalOnProperty("db-scheduler.enabled", matchIfMissing = true)`.
- Define un `Scheduler` completo y un `DbSchedulerStarter`.
- No define ningún bean `SchedulerClient`.

**Consecuencia:** el proceso administrativo, que ya tiene `DataSource`, arrancaría un ejecutor completo. Eso viola la regla 1 de ADR-0016 y `ProcessBeanPolicy`.

**Opciones:**

- A. Excluir la autoconfiguración en `AdminApplication` y `PortalApplication`, y crear el cliente desde un adaptador propio.
- B. Poner `db-scheduler.enabled=false` en esos perfiles.

**Recomendación:** A, con el cliente construido dentro del adaptador y sin exponerlo como bean. Así se conserva la prohibición total del paquete en administración y portal.

### D3. Participación en la transacción de Spring

**Evidencia:** el iniciador envuelve el `DataSource` en `TransactionAwareDataSourceProxy` ([`DbSchedulerConfigurationSupport`](https://raw.githubusercontent.com/kagkarlsson/db-scheduler/master/db-scheduler-spring-boot-starter-parent/db-scheduler-spring-common/src/main/java/com/github/kagkarlsson/scheduler/boot/config/DbSchedulerConfigurationSupport.java)).

**Consecuencia:** el cliente manual de administración y portal debe recibir el mismo proxy transaccional que usa el `DSLContext`. Si recibe el `DataSource` crudo, la tarea se confirma fuera de la transacción del negocio, que es justo lo que ADR-0016 quiere evitar. La prueba de atomicidad de D5 lo valida.

### D4. Serialización de `task_data`

El serializador por defecto del iniciador es la serialización de objetos de Java. Deserializar objetos desde una tabla en la que cualquier proceso con `INSERT` puede escribir (incluido el portal) es una superficie de ataque, y además impide la prueba de lista aprobada de campos.

**Recomendación:** el serializador JSON del núcleo (`Jackson3Serializer`, desde la v16.8.0), con un `record` por tipo de tarea dentro de la envoltura `TracedJob<T>`, y prohibiciones de campos probadas por una lista aprobada.

### D5. Cómo probar que solo el trabajador ejecuta

**Ejecución:**

1. **Arranque por perfil.** Una prueba que extiende `ProcessBeanIsolationTest`: ningún bean `Scheduler` en administración ni en el portal, y exactamente uno en el trabajador.
2. **Comportamiento con PostgreSQL real.** Programar desde un contexto administrativo con la transacción confirmada, y comprobar que solo el contexto del trabajador ejecuta la tarea.
3. **Regla ArchUnit o de enforcer.** Prohíbe `@Scheduled`, `@EnableScheduling` y `@Async` (ADR-0016 §Cumplimiento 2).

**Atomicidad:** programar dentro de `runner.execute(...)` y lanzar una excepción, para comprobar que la tarea no existe. Otra prueba confirma la transacción y comprueba que sí existe. La base son `TransactionRunnerRetryIT` y `CommittingPostgresIntegrationTest`.

### D6. Tareas recurrentes y únicas; semántica de «al menos una vez»

- **Recurrentes** (purgas y verificaciones): `Tasks.recurring` con `Schedules.cron(...)` en la zona `America/Tegucigalpa` explícita. Honduras no usa horario de verano, así que el desplazamiento es constante. Al fijar la versión hay que confirmar la firma que acepta la zona.
- **Únicas** (emisión de recuperación): una instancia por solicitud, con un identificador de instancia que se deriva de datos no personales: institución, cuenta e identificador de solicitud.
- **Reintentos:** máximo y espera exponencial por tipo de tarea; `MaxRetriesBuilder` existe desde la v16.11.0. Por defecto, las tareas únicas se reintentan a los 5 minutos.
- **Ejecuciones muertas:** latido cada 5 minutos y 6 latidos perdidos antes de revivir la tarea. Hay que reajustarlo contra la meta de 900 s de `QueueStalled`.

### D7. Idempotencia de cada manejador

- **Purgas:** son idempotentes por naturaleza.
- **Emisión de recuperación:** no lo es. Una segunda ejecución emite otro token, que sustituye al primero (`supersedeOpen`).

**Opciones:**

- A. Aceptar la idempotencia por sustitución, acotada por el límite de 3 por hora.
- B. Un marcador de ejecución por solicitud.
- C. Un identificador de instancia determinista.

**Recomendación:** C y A juntas. Se necesita una prueba de doble ejecución (ADR-0016 §Cumplimiento 5).

### D8. Condición 4: protección de registros en el trabajador

El trabajador no puede importar `SharedPlatformConfiguration` ni `SensitiveLogGuard`. Las superficies de riesgo son:

- jOOQ, que ya cubre el ajuste;
- el controlador JDBC de PostgreSQL;
- Hikari;
- `org.springframework.jdbc` y `org.springframework.transaction` en `DEBUG` o `TRACE`;
- el propio db-scheduler.

**Recomendación:** mover la guardia a un paquete neutral (por ejemplo `shared.logging`) e importarla en administración y en el trabajador. Debe haber una única lista de prefijos protegidos, con una prueba que retira la guardia y demuestra que el secreto aparece. Esa prueba arranca el proceso trabajador real.

### D9. Cableado propio del trabajador

El trabajador necesita:

- `DataSource`, con el usuario del trabajador;
- `DSLContext` con el registro de ejecución apagado;
- `TransactionRunner` y el reloj;
- `AuditLogWriter`, sin el decorador de origen de la petición;
- los repositorios de identidad y los casos de uso del manejador.

**Recomendación:** una configuración nueva y estrecha del trabajador, que no reutilice las administrativas. Cada paquete nuevo entra en `allowedPackages` del trabajador en el mismo PR.

### D10. Observabilidad

`docs/07` pide `confia_queue_depth` y `confia_queue_oldest_job_age_seconds`, y que la preparación se degrade a los 900 s. En el repositorio no hay Micrometer, Actuator ni Prometheus.

**Recomendación:** un puerto de métricas de cola con un adaptador por log por ahora; los exportadores llegan en los cambios 10 y 11. Las consultas son de solo lectura y nunca seleccionan `task_data`.

### D11. Contexto de seguridad e instituciones

El manejador ejecuta mediante `TransactionRunner`, con la institución de la tarea y el actor `system`. Para las purgas, el trabajador no puede listar instituciones: `organization_institution` tiene RLS sobre su propia clave y el módulo `organization` le está prohibido.

**Opciones:**

- A. Una lista de instituciones por configuración.
- B. Una tarea recurrente por institución.
- C. Una función de base de datos.

**Recomendación:** A en F0, con deuda registrada para la fase 2.

### D12. Emisión de recuperación mientras no exista el cambio 14

**Opciones:**

- A. Registrar el manejador con un emisor que falla de forma explícita.
- B. No registrar el manejador.
- C. Un emisor que no hace nada.

**Recomendación:** A. Es la única opción que no deja tokens huérfanos ni oculta el hueco.

## 6. Mecanismo de `DELETE` para las purgas (decisión del propietario)

`docs/03` §6.1 y las pruebas de integración vigentes prohíben `DELETE` a todos los roles de aplicación sobre las tablas de negocio. El trabajador usa `confia_admin_app`, y las filas están bajo RLS forzada.

| Opción | A favor | En contra |
|---|---|---|
| A. `GRANT DELETE` a `confia_admin_app` sobre cinco tablas | Simple | También se lo da al proceso administrativo; cambia `docs/03` §6.1 y todas las pruebas de la matriz |
| B. Funciones de purga `SECURITY DEFINER` (propietario `confia_owner`, `EXECUTE` solo a `confia_admin_app`, `search_path` fijo, corte mínimo codificado) | El motor impone que solo se borren filas vencidas y no se concede `DELETE` genérico | La lógica vive en SQL; hay que probar que no borra filas vigentes |
| C. Un rol nuevo, `confia_worker_app`, con `DELETE` acotado | Mínimo privilegio real | Enmienda ADR-0017 y `docs/05`; añade un rol, una credencial y un usuario de Hikari |

**Recomendación:** B. C es más limpia si el propietario acepta enmendar ADR-0017.

**Detalles por tabla, a ratificar en el diseño:**

- **Refresco:**
  - Se purga 30 días después de la expiración o la revocación.
  - Los tokens se borran antes que su familia, por familia completa.
  - El sucesor cifrado residual se vacía con `UPDATE` cuando pasa `GRACE_WINDOW`.
- **Recuperación:** se propone 24 h tras el vencimiento (P3).
- **`shared_idempotency_key`:** se proponen 24 h tras `expires_at`. Requiere un índice por `expires_at`.
- **Tablas de retroceso** (backoff): se proponen 24 h después de `last_attempt_at`.
- **Auditoría:** un asiento por categoría con el conteo, nunca los datos.

## 7. Enfoques y esfuerzo

| Enfoque | Descripción | Ventajas | Desventajas | Esfuerzo |
|---|---|---|---|---|
| 1. Un cambio completo (recomendado) | Núcleo, adaptador de emisión y purgas, sin (f) ni (g) | Cierra el criterio 5, la condición 4 y las deudas (a) a (e) | Excede las 13 tareas del plan; 6 PR | Alto |
| 2. Dos cambios | 9a: infraestructura, condición 4 y atomicidad. 9b: manejadores y purgas | Cada uno cabe en el presupuesto | Pide un identificador nuevo; S3 espera al 9b | Alto |
| 3. Cambio máximo | Todo, incluidas (f) y (g) | Cierra todas las deudas | La rotación de DEK es un diseño aparte | Muy alto |

## 8. Propuesta de división en PR (≤800 líneas efectivas)

| PR | Contenido | Líneas aprox. |
|---|---|---|
| 1 | Sondeo y dependencia: versión fijada, V10 `scheduled_tasks`, permisos, pruebas de matriz e inversión de las pruebas de ausencia | 300 a 450 |
| 2 | Cableado del trabajador y condición 4: guardia neutral, `ProcessBeanPolicy`, prueba del proceso real | 550 a 750 |
| 3 | Programación transaccional: puerto, `TracedJob`, JSON, cliente liviano, exclusión de la autoconfiguración, atomicidad y «solo el trabajador ejecuta» | 600 a 800 |
| 4 | Emisión de recuperación: adaptador, manejador, reintentos, doble ejecución | 450 a 650 |
| 5 | Purgas: mecanismo de `DELETE`, vaciado del sucesor, índices, tareas recurrentes, auditoría por lote | 650 a 800 (5a y 5b si se pasa) |
| 6 | Observabilidad y cierre: consultas de cola, `QueueStalled`, documentación, criterio 5 | 300 a 500 |

## 9. Preguntas para el propietario

- **P1.** ¿Entran en el cambio 9 (f), las cadencias de auditoría, y (g), la rotación de DEK, o se asignan a cambios nuevos?
- **P2.** ¿Qué mecanismo de borrado se usa: A, B o C de la sección 6?
- **P3.** ¿Qué plazos de retención aplican a las purgas técnicas? ¿Aplican la gracia de 7 días y la frecuencia semanal de `docs/08` §6.2, o la purga diaria de `docs/07` §11?
- **P4.** ¿Qué hace la emisión de recuperación mientras no exista el adaptador de correo: A, B o C de D12?
- **P5.** ¿Cómo obtiene el trabajador las instituciones: por configuración, con una tarea por institución o con una función de base de datos?
- **P6.** ¿Se cablea el cliente de programación del portal en este cambio, o se difiere?
- **P7.** ¿Se confirma `confia_admin_app` como usuario de base de datos del trabajador?
- **P8.** ¿Se acepta un adaptador de métricas por log hasta los cambios 10 y 11?
- **P9.** ¿Se acepta que el estado fallido se represente con `consecutive_failures` y `last_failure`?

## 10. Riesgos

- La compatibilidad con Spring Boot 4.1.1 solo está comprobada en la documentación.
- La serialización Java en `task_data` abre un riesgo de deserialización; lo mitiga D4.
- Si el cliente recibe el `DataSource` sin proxy, las tareas se confirman fuera de la transacción del negocio.
- `QueueStalled` puede saltar en falso.
- Bajo RLS, una purga sin contexto borra cero filas sin dar error, así que las pruebas deben afirmar los conteos.
- Un orden incorrecto al borrar familias y tokens de refresco rompe las claves foráneas.
- Los PR 1 a 3 tocan a la vez pruebas de aislamiento y de privilegios.
- `docs/05` §6 lista `JWT_ADMIN_SIGNING_KEY` para el trabajador, en contra de `SigningKeyPlacementGuard`. Es una deriva documental, fuera del alcance.
- Los documentos `docs/07` y `docs/08` se contradicen (P3).

## 11. Listo para la propuesta

Sí, pero hay que resolver antes P1, P2, P3 y P5.
