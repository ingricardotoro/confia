# Propuesta: trabajos en segundo plano con db-scheduler (cambio 9 de F0)

- **Cambio:** `background-jobs-with-db-scheduler`
- **Fase del roadmap:** F0, cambio 9 (entregable 12, criterio de salida 5). Según el quinto corte de
  `foundations-plan/exploration.md` (nota del 2026-10-06), es el tercero de la secuencia S1, S2,
  cambio 9, S3: va después de `session-endpoints-cookie-and-csrf` (S2, archivado el 2026-10-24) y antes
  de `password-recovery-endpoints` (S3). El cambio 14, `transactional-email-adapter`, depende de él.
- **Depende de:** `transactional-runner-and-audit-log` (cambio 5), `idempotency-key-infrastructure`
  (cambio 6), `password-recovery-token`, `session-tokens-and-web-layer` (S1) y
  `session-endpoints-cookie-and-csrf` (S2).
- **Exploración:** `openspec/changes/background-jobs-with-db-scheduler/exploration.md` (Engram
  `sdd/background-jobs-with-db-scheduler/explore`).
- **Línea base:** `main` en `b76beda`.
- **Estado:** propuesta aprobada por el propietario el 2026-10-24 (`openspec/config.yaml`). Las decisiones
  P1, P2, P3 y P5, tomadas por el propietario el 2026-10-24, son vinculantes y esta propuesta no las
  reabre. P4, P6, P7, P8 y P9 se redactaron con la recomendación de la exploración y el propietario
  los confirmó el mismo día (sección final).

## Intención

ADR-0016 eligió db-scheduler sobre PostgreSQL para todo el trabajo en segundo plano, pero hoy no
existe ni la dependencia ni la tabla `scheduled_tasks`, y el proceso `confia-worker` arranca sin
`DataSource`. Por eso varias obligaciones de cambios anteriores siguen sin dueño en ejecución:

- el puerto `PasswordResetIssuanceScheduler` no tiene adaptador, así que la emisión del enlace de
  recuperación no se puede programar;
- ninguna tabla técnica de identidad ni la de idempotencia se purga (tokens de recuperación, familias y
  tokens de refresco con `ip_first_seen` y sucesor cifrado residual, `shared_idempotency_key`,
  `identity_login_backoff`, `identity_mfa_totp_backoff`);
- `AuditChainVerifier` existe como rutina invocable, pero nadie lo ejecuta en las cadencias de
  `docs/03` §12.4;
- el criterio de salida 5 de F0 sigue sin marcar en `docs/09`.

Además, la condición dura 4 (2026-10-04) prohíbe dar un `DataSource` al trabajador sin que la
protección de registros sensibles esté activa en ese proceso y probada contra el proceso real.

**Por qué ahora:** es el orden fijado por el quinto corte. S3 necesita programar la emisión de la
recuperación dentro de la transacción de la solicitud, y el cambio 14 necesita un trabajador que
ejecute el envío.

**Cómo se ve el éxito:** una tarea programada dentro de una transacción revertida no existe y una
programada dentro de una confirmada sí; solo `confia-worker` tiene un ejecutor; la emisión de
recuperación se programa y su manejador produce un solo efecto aunque corra dos veces; las purgas
diarias borran exactamente las filas vencidas por la ventana decidida y ninguna vigente; las
verificaciones diaria y semanal de la cadena de auditoría corren por institución; y con `org.jooq` y la
raíz en `DEBUG` ningún valor enlazado sensible aparece en los registros del trabajador real.

## Decisiones del propietario (2026-10-24)

| Id | Decisión | Consecuencia en el alcance |
|---|---|---|
| **P1** | Entran las cadencias **diaria** y **semanal** del verificador de la cadena de auditoría (deuda *f*). La cadencia **horaria** y la rotación por lotes de las DEK `retired` (deuda *g*) salen a cambios nuevos con nombre | Se crean los seguimientos `audit-chain-hourly-anchor-verification` (depende de los cambios 9 y 11, porque compara contra el ancla externa del cambio 11) y `data-encryption-key-batch-rotation` (depende del cambio 9 y de una superficie de rotación que hoy no existe). Ambos se registran con nota fechada en `foundations-plan/exploration.md` |
| **P2** | Las purgas usan funciones `SECURITY DEFINER` propiedad de `confia_owner`, con `EXECUTE` solo para `confia_admin_app`, `search_path` fijo y corte mínimo codificado en la función. No se concede `DELETE` genérico a ningún rol | `RolePrivilegeMatrixIT` e `IdempotencyKeyPrivilegeIT` siguen afirmando que no hay `DELETE` sobre tablas de negocio, y se amplían para afirmar el `EXECUTE` exacto. Las pruebas DEBEN demostrar que nunca se borra una fila vigente |
| **P3** | Las tablas técnicas se purgan **a diario** (`docs/07` §11), sin marca previa ni gracia de 7 días. Se agrega una nota editorial en `docs/08` §6.2: la regla semanal de marca y gracia aplica a datos de negocio y personales, no a tablas técnicas | Ventanas: familias y tokens de refresco, 30 días después de la expiración o la revocación; tokens de recuperación, 24 h después del vencimiento; `shared_idempotency_key`, 24 h después de `expires_at`; tablas de retroceso, 24 h después de `last_attempt_at` |
| **P5** | El trabajador obtiene por configuración las instituciones sobre las que corren las tareas recurrentes (F0 tiene una sola institución). Se registra deuda para la fase 2 | `confia_owner` es `NOBYPASSRLS` y las tablas tienen RLS forzada: las funciones de purga siguen necesitando el contexto de institución, que fija el `TransactionRunner` con actor `system` para cada institución configurada |

## Condiciones que cierra

| Condición | Cómo la cierra | Prueba de cierre |
|---|---|---|
| **Condición 4** (2026-10-04) | La guardia de registros sensibles se reubica en un paquete neutral que el trabajador puede importar (por ejemplo `shared.logging`), con una sola lista de prefijos protegidos para administración y trabajador. El `DataSource` del trabajador y su primera sentencia SQL llegan en el mismo PR que la guardia, o después | Arranque del proceso trabajador real con `org.jooq` y la raíz en `DEBUG`: un valor enlazado distintivo no aparece en ningún evento. Control negativo: sin la guardia, el valor aparece |
| **Criterio de salida 5** de F0 | Programación transaccional con cliente liviano en administración y portal; ejecutor solo en el trabajador | Pruebas de ADR-0016 §Cumplimiento 3 y 4 con PostgreSQL real; se marca `docs/09` |

## Alcance

### Dentro de alcance

**Dependencia y esquema**

1. Sondeo de compatibilidad antes del diseño: `db-scheduler-spring-boot-4-starter` en la versión
   publicada vigente al fijarla (hoy 16.12.0; se relee la lista de versiones porque `UPGRADING.md`
   menciona una línea 16.13.x), contra el BOM de Spring Boot 4.1.1, Jackson 3 y la regla
   `dependencyConvergence`, con arranque real del trabajador y `./mvnw validate`. El resultado queda en
   el diseño.
2. Versión fijada en el `dependencyManagement` de `apps/api/pom.xml`; el iniciador solo en el
   trabajador y el núcleo de la biblioteca donde haga falta el cliente. `maven-enforcer-plugin` sigue
   prohibiendo Quartz y JobRunr.
3. Migración `V10__create_scheduled_tasks.sql` con el DDL de la versión fijada y los permisos exactos de
   ADR-0017. La creación automática de la tabla por la biblioteca queda desactivada y probada.
4. Migración nueva (V11 en adelante; el número exacto lo fija el diseño) con las funciones de purga de
   P2 y el índice por `expires_at` de `shared_idempotency_key`. Las migraciones V1 a V9 no se editan.

**Ejecutor y cableado del trabajador**

5. `WorkerApplication` recupera su `DataSource` con el usuario de base de datos del trabajador y una
   configuración propia y estrecha: `DSLContext` con el registro de ejecución apagado,
   `TransactionRunner`, reloj, `AuditLogWriter` sin el decorador de origen de la petición, y solo los
   repositorios y casos de uso que usan los manejadores. No reutiliza `SharedPlatformConfiguration` ni
   `IdentityConfiguration`. Conserva las exclusiones de seguridad que hoy tiene, salvo que el diseño
   demuestre lo contrario.
6. Guardia de registros neutral (condición 4) importada por administración y trabajador.
7. `ProcessBeanPolicy` actualizada en el mismo PR que cada paquete nuevo: el trabajador admite el
   paquete de trabajos y la guardia neutral; administración y portal siguen prohibiendo
   `com.github.kagkarlsson` como bean.

**Programación transaccional**

8. Paquete de trabajos en `shared` (por ejemplo `com.confia.shared.jobs`), importable por el trabajador:
   puerto de programación, envoltura `TracedJob<T>` con identificadores, correlación, actor e
   institución (`docs/07` §3.3), un `record` por tipo de tarea y serializador JSON de la biblioteca
   (`Jackson3Serializer`). Queda prohibida la serialización de objetos de Java en `task_data`.
9. En administración y portal se excluye la autoconfiguración de db-scheduler y el `SchedulerClient`
   se construye a mano dentro del adaptador, sin exponerse como bean, sobre el mismo
   `TransactionAwareDataSourceProxy` que usa el `DSLContext`, de modo que la tarea participe de la
   transacción del `TransactionRunner`.
10. Reintentos acotados con espera exponencial y máximo por tipo de tarea; ajuste del latido y de la
    detección de ejecuciones muertas contra la meta de 900 s de `QueueStalled`.
11. Tareas recurrentes declaradas en código con `Schedules.cron(...)` en la zona `America/Tegucigalpa`
    explícita; tareas únicas con identificador de instancia derivado de datos no personales.

**Manejadores**

12. Adaptador de `PasswordResetIssuanceScheduler` y manejador sobre `IssuePasswordResetToken`, con
    identificador de instancia determinista (institución, cuenta, identificador de solicitud) e
    idempotencia por sustitución (`supersedeOpen`), acotada por el límite de 3 por hora.
13. Purgas diarias (deudas *b* a *e*) por institución configurada, cada una por su función
    `SECURITY DEFINER`: tokens de recuperación; tokens de refresco antes que su familia, por familia
    completa, con vaciado por `UPDATE` del `successor_token_ciphertext` residual cuando vence
    `GRACE_WINDOW`; `shared_idempotency_key`; `identity_login_backoff` e `identity_mfa_totp_backoff`.
    Cada lote escribe un asiento de auditoría por categoría con el conteo, nunca los datos.
14. Cadencias diaria (último mes, por institución, recalculando cada `row_hash`) y semanal (desde el
    génesis de cada cadena) de `AuditChainVerifier` (deuda *f*, P1). Una discrepancia se registra como
    resultado fallido y alerta S1 según `docs/03` §12.4, sin intentar reparar la cadena.

**Observabilidad**

15. Puerto de métricas de cola (`confia_queue_depth`, `confia_queue_oldest_job_age_seconds`, tareas
    fallidas) con consultas de solo lectura sobre `scheduled_tasks` que nunca seleccionan `task_data`,
    incluidas en la lista aprobada de SQL plano de ADR-0015, y un adaptador por registro estructurado
    hasta los cambios 10 y 11. Estado degradado de la preparación a los 900 s.

**Pruebas de ADR-0016 §Cumplimiento**

16. Atomicidad (transacción confirmada y revertida); solo el trabajador ejecuta (arranque por perfil:
    cero beans `Scheduler` en administración y portal, uno en el trabajador, y comportamiento con
    PostgreSQL real); doble ejecución por tipo de tarea; lista aprobada de campos por tipo de tarea;
    contexto de seguridad por institución; regla ArchUnit que prohíbe `@Scheduled`,
    `@EnableScheduling` y `@Async` en producción.
17. Purgas: cada función borra solo filas fuera de su ventana, con casos límite en el borde exacto del
    corte, un segundo antes y un segundo después; afirmación de conteos (bajo RLS, una purga sin
    contexto borra cero filas sin error); otra institución intacta.

**Inversiones de inventario y documentación**

18. Inversiones con control negativo: `ProcessBeanIsolationTest.dbSchedulerAbsenceIsVacuousUntilChange9`
    se reemplaza por la afirmación positiva; `AuditScopeExclusionInventoryTest`;
    `IdentityScopeExclusionInventoryTest` (la ausencia del adaptador de emisión; la de la rotación de
    DEK se conserva y se reasigna a `data-encryption-key-batch-rotation`); ajustes de
    `RolePrivilegeMatrixIT`, `IdempotencyKeyPrivilegeIT` y `MultiTenantSchemaIT`.
19. Notas fechadas, sin reescribir cuerpos: `docs/03` §6.1 (purga por funciones, sin `DELETE`),
    `docs/07` §11 (tabla de tareas), `docs/08` §6.2 (nota editorial de P3), `docs/05` §6 (usuario del
    trabajador), `docs/09` (criterio 5) y `foundations-plan/exploration.md` (condición 4 cerrada,
    seguimientos de P1 y deuda de P5).

### Fuera de alcance, con dueño nombrado

| Exclusión | Dueño |
|---|---|
| Cadencia horaria del verificador de la cadena contra el ancla externa | `audit-chain-hourly-anchor-verification` (nuevo; depende de los cambios 9 y 11) |
| Rotación por lotes y recifrado de las DEK `retired` (deuda *g*) | `data-encryption-key-batch-rotation` (nuevo; depende del cambio 9) |
| Endpoints `reset/request` y `reset/confirm`, H3 y H4 | S3 `password-recovery-endpoints` |
| Adaptador de correo y envío real del enlace de recuperación | Cambio 14 `transactional-email-adapter` |
| Exportadores de Micrometer, Prometheus y Actuator | Cambios 10 y 11 |
| Redis, dimensión por IP y proxies de confianza | Cambio 11 |
| Descubrimiento de instituciones por el trabajador sin configuración | Fase 2 (deuda de P5) |
| Tareas de negocio del inventario de ADR-0016 (cargos, integridad del libro mayor, rango CAI) | Cambios de los módulos que las posean |
| Ampliar la lista de tablas técnicas de ADR-0017 | Fuera de alcance; exige un ADR |
| Deriva documental de `JWT_ADMIN_SIGNING_KEY` para el trabajador en `docs/05` §6 | Se registra; no se corrige aquí |

## Capacidades

> Contrato con `/sdd-spec`. Investigado contra `openspec/specs/` (diez capacidades publicadas:
> `audit-trail`, `build-integrity`, `cashbox`, `identity`, `invoicing`, `ledger`, `money`,
> `organization`, `payments`, `web-edge`).

### Nuevas

- **`background-jobs`:** programación transaccional, ejecución solo en el trabajador, envoltura y
  serialización de datos de tarea con lista aprobada de campos, reintentos y estado fallido, tareas
  recurrentes con zona explícita, contexto de seguridad por institución, métricas de cola y estado
  degradado.

### Modificadas

- **`identity`:** adaptador y manejador de la emisión de recuperación (inversión de la ausencia de la
  línea 1170); purgas de tokens de recuperación (línea 1213) y de familias y tokens de refresco con el
  vaciado del sucesor residual (línea 2251); purga de las tablas de retroceso; la ausencia de la
  rotación de DEK (línea 809) cambia de destino a `data-encryption-key-batch-rotation`.
- **`build-integrity`:** purga de `shared_idempotency_key` (línea 983) y resolución de la tensión de
  privilegios por funciones `SECURITY DEFINER`; matriz de permisos con `scheduled_tasks` y `EXECUTE`;
  `ProcessBeanPolicy` y aislamiento de procesos con db-scheduler; regla ArchUnit contra
  `@Scheduled`, `@EnableScheduling` y `@Async`; condición 4 sobre el trabajador real.
- **`audit-trail`:** programación de las cadencias diaria y semanal (línea 292); la horaria queda
  transferida a `audit-chain-hourly-anchor-verification`; asientos de auditoría por lote de purga.

## Enfoque

1. **Sondeo primero.** Nada se diseña sobre la compatibilidad con Spring Boot 4.1.1 hasta que el
   iniciador arranque en el trabajador real y `./mvnw validate` pase la convergencia.
2. **Ejecutor solo en el trabajador** con el iniciador (opción A de D1); **cliente manual** en
   administración y portal con la autoconfiguración excluida (opción A de D2), construido sobre el
   proxy transaccional (D3).
3. **Defensa antes de exposición.** La guardia neutral de registros llega antes o junto con el primer
   `DataSource` del trabajador (condición 4).
4. **Datos de tarea mínimos y tipados:** JSON con un `record` por tipo, sin datos personales ni
   importes; los manejadores revalidan contra la base (ADR-0017, regla 7).
5. **Purgas por el motor:** funciones `SECURITY DEFINER` con corte mínimo codificado, invocadas desde
   la capa `infrastructure` por jOOQ con consultas parametrizadas, siempre dentro del
   `TransactionRunner` con la institución configurada y el actor `system`.
6. **TDD estricto** (`openspec/config.yaml`): las pruebas de atomicidad, de «solo el trabajador
   ejecuta», de doble ejecución y de no borrar filas vigentes se escriben en rojo antes del código.
7. Ubicación: paquete de trabajos y guardia en `shared` sin depender de `identity`; adaptador, manejador
   y casos de uso de purga en `identity.{application,infrastructure}`; ningún módulo importa el
   `domain` de otro.

## Áreas afectadas

| Área | Cambio |
|---|---|
| `apps/api/pom.xml`, `apps/api/app/pom.xml` | Versión fijada, iniciador, convergencia; enforcer sin cambios de prohibición |
| `apps/api/app/src/main/resources/db/migration/V10__create_scheduled_tasks.sql` | Nueva: tabla y permisos de ADR-0017 |
| `apps/api/app/src/main/resources/db/migration/V11__*.sql` (o siguientes) | Nuevas: funciones de purga e índice por `expires_at` |
| `apps/api/app/src/main/java/com/confia/bootstrap/worker/WorkerApplication.java` | `DataSource` y configuración propia del trabajador |
| `apps/api/app/src/main/java/com/confia/bootstrap/admin/AdminApplication.java`, `.../portal/PortalApplication.java` | Exclusión de la autoconfiguración de db-scheduler |
| `apps/api/app/src/main/java/com/confia/shared/jobs/` (nuevo) | Puerto, `TracedJob`, serializador, cliente, configuración del trabajador, métricas de cola |
| `apps/api/app/src/main/java/com/confia/shared/logging/` (nuevo) y `shared/web/request/SensitiveLogGuard` | Guardia neutral con lista única de prefijos |
| `apps/api/app/src/main/java/com/confia/identity/{application,infrastructure}/` | Adaptador de emisión, manejador, casos de uso y adaptadores de purga, configuración estrecha del trabajador |
| `apps/api/app/src/main/java/com/confia/shared/audit/` | Tareas recurrentes de verificación de la cadena |
| Pruebas | `ProcessBeanPolicy`, `ProcessBeanIsolationTest`, inventarios de exclusión, `RolePrivilegeMatrixIT`, `IdempotencyKeyPrivilegeIT`, `MultiTenantSchemaIT`, `RequestPasswordResetIT`, nuevas pruebas de atomicidad, ejecución, doble ejecución, lista de campos, purgas y condición 4 |
| Documentación | `docs/03` §6.1, `docs/05` §6, `docs/07` §11, `docs/08` §6.2, `docs/09`, `foundations-plan/exploration.md` |

## Riesgos

| Riesgo | Probabilidad | Mitigación |
|---|---|---|
| El iniciador no arranca contra Spring Boot 4.1.1 o rompe `dependencyConvergence` (solo comprobado en documentación) | Media | Sondeo como primera tarea; si falla, opción B de D1 (solo el núcleo con cableado propio) y se informa al propietario antes del diseño |
| El cliente recibe el `DataSource` sin proxy y la tarea se confirma fuera de la transacción | Media | Prueba de atomicidad en rojo antes del adaptador |
| Deserialización insegura en `task_data` | Baja con JSON | Serializador JSON obligatorio, prueba que rechaza la serialización de Java, lista aprobada de campos |
| Un proceso que no es el trabajador ejecuta tareas | Baja | Exclusión de autoconfiguración, `ProcessBeanPolicy` y prueba de arranque por perfil |
| Una función de purga borra filas vigentes o de otra institución | Media | Corte mínimo codificado en la función, pruebas en el borde exacto, segunda institución de fixture |
| Bajo RLS, una purga sin contexto borra cero filas en silencio | Media | Las pruebas afirman conteos exactos; el manejador registra el conteo en auditoría |
| Orden incorrecto entre tokens y familias de refresco rompe claves foráneas | Media | Borrado por familia completa, tokens primero, en una sola función |
| `QueueStalled` salta en falso por el latido por defecto | Media | Ajuste del latido y de la detección de ejecuciones muertas contra los 900 s, con prueba |
| Fuga de valores enlazados por el controlador JDBC, Hikari, Spring JDBC o db-scheduler en `DEBUG` | Media | Guardia neutral con prefijos de todas esas superficies y prueba sobre el trabajador real |
| Los primeros PR tocan a la vez pruebas de aislamiento y de privilegios | Alta | Orden duro de la cadena; cada inversión con su control negativo |
| Subestimación de tamaño (factor histórico de 2,2 a 7,97) y riesgo de superar 15 tareas | Alta | Si `tasks.md` supera 15 tareas, la fase de tareas propone partir en 9a (infraestructura, condición 4, atomicidad) y 9b (manejadores, purgas, cadencias) |
| La sesión registra la estrategia de entrega `single-pr`, incompatible con el tope de 800 líneas efectivas por PR de `docs/15` §3 para el tamaño previsto | Cierta | La fase de tareas debe resolver la estrategia con el orquestador antes de aplicar; esta propuesta asume la cadena de PR de abajo |

## Plan de reversión

No existe ningún entorno desplegado ni dato real.

1. Cada PR se revierte con su commit de fusión, en orden inverso de la cadena.
2. **Nunca se revierte la guardia neutral de registros mientras exista en `main` un trabajador con
   `DataSource`** (condición 4): se revierte antes el cableado del trabajador.
3. Revertir la programación transaccional exige revertir antes el adaptador de emisión de recuperación,
   que depende de ella; `RequestPasswordResetIT` vuelve a su doble.
4. Las tareas recurrentes (purgas y cadencias) se revierten de forma independiente; las filas ya
   purgadas no se recuperan, lo que es aceptable porque solo eran datos técnicos vencidos.
5. V10 y la migración de funciones de purga: antes de producción, revertir es eliminar la migración y
   regenerar jOOQ. Una vez aplicadas en un entorno compartido nunca se editan; se revierten con una
   migración nueva que retire las funciones y sus `EXECUTE` y, solo si no hay tareas pendientes, la
   tabla `scheduled_tasks`.
6. Las notas documentales se revierten con su PR; ninguna reescribe un cuerpo original.

## Dependencias

- **Consume:** `TransactionRunner`, `SecurityContext`, `AuditLogWriter`, `AuditChainVerifier`,
  `IssuePasswordResetToken`, `PasswordResetIssuanceScheduler`, repositorios jOOQ de identidad e
  idempotencia, `ProcessBeanPolicy`, `SensitiveLogGuard`.
- **Entrega a S3 y al cambio 14:** programación transaccional, ejecutor en el trabajador y el manejador
  de emisión de recuperación.
- **Dependencia Maven nueva:** `com.github.kagkarlsson:db-scheduler-spring-boot-4-starter` (y el núcleo
  `db-scheduler` donde haga falta el cliente), licencia Apache 2.0. Docker para las pruebas de
  integración.

## Criterios de éxito

- [ ] `./mvnw verify` en `apps/api` en verde con la puerta de mutación; cobertura global de `app` al
      menos 80 %, `domain` al menos 95 %, mutación de PIT al menos 80 % en `domain`.
- [ ] **Criterio 5:** una tarea programada dentro de una transacción revertida no existe; dentro de una
      confirmada, sí; solo el contexto de `confia-worker` tiene un bean `Scheduler` y ejecuta tareas.
- [ ] **Condición 4:** con `org.jooq` y la raíz en `DEBUG`, un valor enlazado distintivo no aparece en
      ningún evento del trabajador real; sin la guardia, aparece. Ningún commit de `main` da un
      `DataSource` al trabajador antes de la guardia.
- [ ] Ningún uso de `@Scheduled`, `@EnableScheduling` ni `@Async` en producción (ArchUnit).
- [ ] Cada tipo de tarea pasa la prueba de lista aprobada de campos y la de doble ejecución con un solo
      efecto; `task_data` nunca usa serialización de Java.
- [ ] Un manejador solo ve datos de la institución de su tarea.
- [ ] Cada purga borra el 100 % de las filas fuera de su ventana y ninguna dentro de ella ni de otra
      institución, con conteos afirmados; ningún rol obtiene `DELETE` sobre tablas de negocio y solo
      `confia_admin_app` tiene `EXECUTE` sobre las funciones.
- [ ] Las cadencias diaria y semanal del verificador corren por institución configurada y una
      alteración de la cadena produce un resultado fallido visible.
- [ ] Las métricas de cola nunca leen `task_data` y la preparación se degrada a los 900 s.
- [ ] Las inversiones de inventario existen y cada una falla con su control negativo; las notas
      documentales existen y ninguna reescribe un texto original; `docs/09` marca el criterio 5.

## Pronóstico de tamaño y entrega

Cadena de PR sobre `main` con un máximo de 800 líneas efectivas por PR (`docs/15` §3); las pruebas
nunca se separan del código que prueban. Rangos de la exploración, ajustados por P1 y P2.

| Orden | Rebanada | Contenido | Líneas aprox. |
|---|---|---|---|
| 1 | `scheduler-dependency-and-table` | Sondeo, versión fijada, V10, permisos, matriz de privilegios, inversión de las pruebas de ausencia | 300 a 450 |
| 2 | `worker-wiring-and-log-guard` | Guardia neutral, cableado estrecho del trabajador, `ProcessBeanPolicy`, prueba de la condición 4 sobre el proceso real | 550 a 750 |
| 3 | `transactional-scheduling` | Puerto, `TracedJob`, JSON, cliente manual, exclusión de la autoconfiguración, atomicidad, «solo el trabajador ejecuta», ArchUnit | 600 a 800 |
| 4 | `password-reset-issuance-job` | Adaptador, manejador, reintentos, doble ejecución, lista de campos | 450 a 650 |
| 5a | `identity-token-purges` | Migración de funciones de purga, purgas de recuperación y refresco con vaciado del sucesor, auditoría por lote | 550 a 800 |
| 5b | `idempotency-and-backoff-purges` | Funciones e índice de idempotencia y retroceso, tareas recurrentes por institución configurada | 400 a 650 |
| 6 | `audit-chain-verification-cadences` | Cadencias diaria y semanal del verificador | 250 a 400 |
| 7 | `queue-observability-and-closing` | Métricas de cola, estado degradado, notas documentales, criterio 5, seguimientos de P1 | 300 a 500 |

**Órdenes duros:** 1 antes que todo; 2 antes de cualquier SQL en el trabajador (condición 4); 3 antes
de 4, 5a, 5b y 6; la migración de funciones de purga (5a) antes de 5b.

## Supuestos confirmados por el propietario

Se redactaron con la recomendación de la exploración. El propietario confirmó los cinco el 2026-10-24.

| Id | Supuesto | Efecto si el propietario elige otra opción |
|---|---|---|
| **P4** | Mientras no exista el cambio 14, el manejador de emisión se registra con un emisor de enlaces que **falla de forma explícita** (opción A de D12): la tarea agota sus reintentos, queda fallida y visible, sin dejar tokens huérfanos ni ocultar el hueco | *B)* no registrar el manejador deja tareas sin ejecutor que harían saltar `QueueStalled`; *C)* un emisor que no hace nada emite tokens que nadie recibe y oculta la brecha |
| **P6** | El cliente de programación del **portal se cablea en este cambio** (D2, opción A), con la autoconfiguración excluida, y una prueba con `confia_portal_app` resuelve la validación pendiente de ADR-0017: `INSERT` basta y `SELECT` solo se concede si el cliente lo exige | Diferirlo deja la validación de ADR-0017 abierta hasta el primer caso de uso del portal que programe y reduce la rebanada 3 |
| **P7** | El trabajador usa el rol **`confia_admin_app`** como usuario de base de datos (`docs/05` §6, ADR-0017) | Un rol propio (`confia_worker_app`) enmienda ADR-0017 y `docs/05`, y añade un rol, una credencial y una configuración de Hikari |
| **P8** | Hasta los cambios 10 y 11, las métricas de cola se publican con un **adaptador por registro estructurado** detrás de un puerto | Traer Micrometer y Actuator ahora adelanta parte de los cambios 10 y 11 y añade dependencias |
| **P9** | El estado fallido se representa con las columnas `consecutive_failures` y `last_failure` de la propia tabla: una tarea que agota sus intentos se conserva visible para las métricas y la alerta, nunca se descarta en silencio | Una tabla propia de tareas fallidas amplía la lista de tablas técnicas de ADR-0017 y exige un ADR |
