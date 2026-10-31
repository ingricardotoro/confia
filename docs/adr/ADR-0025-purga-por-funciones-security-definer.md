# ADR-0025: Purga de tablas técnicas de retención mediante funciones `SECURITY DEFINER`

- **Estado:** Propuesto
- **Fecha:** 2026-10-29
- **Decisores:** Propietario del producto y arquitecto
- **Contexto técnico:** Esquema de PostgreSQL y roles de base de datos de `docs/03-seguridad.md` sección 6.1, migraciones de Flyway (`V11`, y `V12` en la tarea 3.6 del cambio), módulo `identity` (casos de uso de purga y su adaptador jOOQ), `shared.observability.events`, proceso `confia-worker`. Complementa ADR-0003, ADR-0005, ADR-0009, ADR-0015, ADR-0016 y ADR-0017.

## Contexto y problema

Varias tablas guardan filas que dejan de servir pasado un plazo: los tokens de recuperación de
contraseña, las familias y los tokens de refresco (ADR-0005), las claves de idempotencia (ADR-0010) y
las filas de retroceso de inicio de sesión y de TOTP. `docs/08-datos-privacidad-y-retencion.md`
sección 6 fija su eliminación automática, y hasta el cambio 9 (`background-jobs-with-db-scheduler`)
esas filas se retenían porque no existía el trabajo de purga.

Purgar exige borrar, y las reglas aceptadas lo impiden de forma deliberada:

- `docs/03-seguridad.md` sección 6.1: `confia_admin_app` no tiene `DELETE` en ninguna tabla de negocio,
  y `confia_portal_app` no tiene `UPDATE` ni `DELETE` en ninguna tabla.
- ADR-0017: los permisos de `scheduled_tasks`, `event_publication` y `flyway_schema_history` son
  «los únicos `UPDATE` y `DELETE` de `confia_admin_app` fuera de las tablas no financieras».

El mismo rol `confia_admin_app` lo usan el proceso administrativo, expuesto por HTTP, y el trabajador
(ADR-0024). Un `GRANT DELETE` sobre estas tablas no lo recibiría solo la purga: lo recibiría cualquier
ruta administrativa, incluida una con un defecto de autorización. Además las tablas tienen seguridad a
nivel de fila forzada y `confia_owner` es `NOBYPASSRLS`, de modo que un `DELETE` sin contexto de
institución vería cero filas y terminaría sin error: una purga rota parecería una purga sin trabajo.

El propietario decidió el 2026-10-24 que las purgas usen funciones `SECURITY DEFINER` (P2), con
ventanas diarias (P3), y que la decisión se registre en un ADR nuevo en estado «Propuesto», con una
nota fechada en ADR-0017 en lugar de reescribirlo (D-N3). Si no se registra, la excepción al principio
«ningún rol de aplicación borra» existiría solo en una migración, sin una regla verificable que
impida la siguiente excepción mal hecha.

## Factores de decisión

| Factor | Peso | Justificación |
|---|---|---|
| Ningún rol de aplicación recibe `DELETE` sobre tablas de negocio | Muy alto | Es el principio de `docs/03` sección 6.1 y la defensa ante un defecto en una ruta HTTP. |
| Imposible borrar una fila todavía vigente | Muy alto | Un token, una clave de idempotencia o una fila de retroceso borrados antes de tiempo reabren un ataque o una doble ejecución. |
| Una purga sin contexto falla, nunca informa cero | Muy alto | Con RLS forzada, el error silencioso es el modo de falla natural. |
| Bordes deterministas y verificables en pruebas | Alto | Con un solo desarrollador, lo que no se verifica se erosiona. |
| Sin infraestructura nueva | Alto | Restricción de un solo desarrollador (`docs/01` sección 1). |
| Evidencia de cada corrida en la bitácora de auditoría | Alto | `CLAUDE.md` regla 14 y `docs/08` sección 6.2. |

## Opciones consideradas

### Opción A: `GRANT DELETE` sobre las tablas purgables a `confia_admin_app`

**Ventajas.** Lo más simple: un `DELETE` parametrizado desde el adaptador jOOQ.

**Desventajas.** El permiso lo recibe también el proceso administrativo y cada una de sus rutas HTTP.
Rompe el principio de `docs/03` sección 6.1 sin acotarlo, y nada en el motor impide borrar una fila
vigente o borrar sin contexto. **Se descarta.**

### Opción B: Rol de base de datos propio para el trabajador con `DELETE`

**Ventajas.** El permiso no llega al proceso administrativo.

**Desventajas.** Un rol más que aprovisionar, rotar y probar en la matriz de permisos, y contradice
ADR-0024, que fija que el trabajador usa `confia_admin_app`. El rol seguiría pudiendo borrar filas
vigentes y borrar sin contexto. El costo operativo no compra la regla que importa, que es *qué* se
puede borrar. **Se descarta.**

### Opción C: Funciones `SECURITY DEFINER` con plantilla común (elegida)

Cada categoría purgable tiene una función propiedad de `confia_owner` que borra solo lo que su regla
permite. La aplicación no recibe `DELETE`; recibe `EXECUTE` sobre esas funciones.

**Ventajas.** El motor impone el piso de antigüedad, el contexto y el actor, sin importar quién llame.
El permiso concedido es exactamente «purgar lo vencido», no «borrar».

**Desventajas.** Lógica en PL/pgSQL, que se revisa con más cuidado que Java; una función por categoría.

### Opción D: Corte calculado solo con `now()` dentro de la función

**Desventajas.** Las pruebas de borde a un segundo dependerían del reloj de la base de datos. **Se
descarta** como variante de C: el corte lo pasa el llamador y la función lo acota.

## Decisión

**Se adopta la opción C: las tablas técnicas de retención se purgan solo mediante funciones
`SECURITY DEFINER` que siguen una plantilla común verificada en integración continua, y ningún rol
recibe `DELETE` sobre esas tablas.**

### Plantilla obligatoria

Toda función de purga cumple todas estas reglas. Una función que no las cumpla se rechaza en revisión
y rompe la construcción.

1. **Propietario `confia_owner`**, el rol que ejecuta Flyway, sin `OWNER TO` explícito (precedente de
   `V3`). `confia_owner` es `NOBYPASSRLS`, así que la RLS forzada sigue aplicando dentro de la función.
2. **`SET search_path = pg_catalog, public`** y todo objeto calificado con `public.`, para que una tabla
   temporal del llamador no pueda suplantar a la real.
3. **`SET TimeZone = 'UTC'`.** Restar un intervalo de días a un `timestamptz` es aritmética de
   calendario en la zona de la sesión: con horario de verano, `30 days` podría valer 29 días y 23
   horas. Fijar la zona hace el piso independiente de la sesión. Se agregó por decisión del
   propietario del 2026-10-28, a raíz de una advertencia de revisión, antes de fusionar `V11`.
4. **Piso codificado.** El corte efectivo es `LEAST(p_cutoff, now() - piso)`. El llamador calcula su
   corte con su `Clock`, lo que hace verificables los bordes al segundo, pero solo puede moverlo hacia
   atrás: la función nunca borra una fila más reciente que el piso, aunque reciba un corte en el
   futuro. No se lanza una excepción cuando el corte supera el piso, porque una diferencia de
   milisegundos entre el reloj de Java y el de PostgreSQL haría fallar la tarea de producción. El
   borde es estricto: la fila cuya referencia coincide con el corte se conserva.
5. **Contexto de institución obligatorio.** Sin `app.institution_id`, la función lanza
   `confia_purge_context_missing`. El filtro `institution_id = v_institution` repite lo que la política
   ya impone y usa el índice.
6. **Solo el actor `system`.** Con `app.actor_kind` distinto de `system`, la función lanza
   `confia_purge_actor_not_system`. Una ruta HTTP administrativa (actor `staff`) que la llamara por
   error falla en el motor.
7. **Lote acotado.** `p_limit` se acota a 1..10 000; el llamador repite mientras el lote venga lleno.
8. **`REVOKE ALL ... FROM PUBLIC` y `GRANT EXECUTE` solo a `confia_admin_app`.** Ningún otro de los
   cinco roles la ejecuta.
9. **Ningún rol recibe `DELETE`** sobre las tablas que la función purga.

### Funciones y ventanas (P3)

| Función | Migración | Piso | Fila purgable |
|---|---|---|---|
| `identity_purge_password_reset_tokens` | `V11` | 24 h | `expires_at < corte` |
| `identity_purge_refresh_token_families` | `V11` | 30 días | Familia con `revoked_at < corte` o `absolute_expires_at < corte`, con todos sus tokens; las familias se bloquean en orden de `id` |
| `shared_purge_idempotency_keys` | `V12` (tarea 3.6) | 24 h | `expires_at < corte` |
| `identity_purge_login_backoff` | `V12` (tarea 3.6) | 24 h | `last_attempt_at < corte` |
| `identity_purge_mfa_totp_backoff` | `V12` (tarea 3.6) | 24 h | `last_attempt_at < corte` |

Las tres funciones de `V12` no existen todavía; cuando se entreguen siguen esta plantilla sin
excepción. Una categoría purgable nueva exige su función con esta plantilla; ampliar la lista con un
`GRANT DELETE` exige un ADR nuevo.

### Uso desde la aplicación

- Solo el trabajador purga. Cada purga es una tarea recurrente de db-scheduler (ADR-0016), en el
  calendario `America/Tegucigalpa`, que corre por cada institución configurada: tokens de
  recuperación a las 04:30 y tokens de refresco a las 04:35, a diario, sin marca previa ni gracia de
  siete días (P3).
- Cada lote es su propia transacción, abierta con el contexto `system` de la institución, y en esa
  misma transacción se escribe **un asiento de auditoría por lote** con el conteo, también cuando es
  cero: si el asiento falla, el lote se revierte. El asiento lleva la etiqueta `retention-purge`,
  `entity_type = "shared.job_run"` y solo conteos e instantes, nunca datos de las filas.
- Lotes de 5 000, hasta 200 por institución y por ocurrencia. Si se agotan los 200 lotes, el caso de
  uso escribe un `WARN` `retention_purge_backlog` con `runId`, `task` e `institution` mediante
  `OperationalEventLog` (`shared.observability.events`), que acepta solo identificadores cerrados y
  `UUID`, sin texto libre; el resto sigue al día siguiente. Esa vía, en lugar de una exención para usar
  slf4j en `identity`, la decidió el propietario el 2026-10-28 (opción B).
- El vaciado del sucesor residual de los tokens de refresco **no** usa una función: es un `UPDATE` de
  una columna sobre la que el rol ya tiene `UPDATE` y que el disparador de `V8` permite vaciar. No
  borra, así que queda fuera de esta decisión.

## Consecuencias

**Positivas:**

- Ningún rol de aplicación recibe `DELETE` sobre tablas de negocio; el principio de `docs/03`
  sección 6.1 se conserva, acotado por una lista de funciones.
- Una fila vigente no puede borrarse ni por un defecto del llamador ni por un reloj adelantado.
- Una purga sin contexto o desde una ruta HTTP falla de forma visible.
- Cada corrida deja evidencia en la bitácora, aunque no haya purgado nada.

**Negativas y costos aceptados:**

- **Lógica en PL/pgSQL.** Se revisa con el cuidado de las migraciones y se cubre con pruebas sobre
  PostgreSQL real; no la alcanzan las pruebas de mutación de Java.
- **La frase de ADR-0017** «estos son los únicos `UPDATE` y `DELETE` de `confia_admin_app`» deja de
  describir todo lo que puede borrar el proceso: borra también a través de estas funciones. ADR-0017
  no se reescribe; recibe una nota fechada que apunta aquí.
- **Una migración por función nueva.** Cambiar un piso exige una migración, nunca una configuración.
- **Atraso posible.** Con más de un millón de filas purgables por institución y tarea, la corrida no
  termina y el resto espera al día siguiente; se informa con `retention_purge_backlog`.

**Seguimientos conocidos (no bloqueantes):**

- El vaciado del sucesor residual es un único `UPDATE` sin lotes, y compara `consumed_at` contra un
  corte calculado con el reloj de la JVM, no con el de la base de datos. Con muchas filas puede ser una
  transacción larga; si crece, se acota por lotes.
- El adaptador `JooqIdentityRetentionPurges` recibe un `InstitutionId` que no usa en las dos llamadas a
  las funciones, porque la institución llega por el contexto de la transacción. El parámetro documenta
  la intención pero puede inducir a creer que filtra; se revisa al entregar `V12`.
- Los campos clave-valor del `WARN` `retention_purge_backlog` solo se vuelven consultables con el
  registro estructurado en JSON del cambio 10; hasta entonces aparecen como pares en la línea.
- Las advertencias de revisión de la tarea 3.3 sobre un `p_cutoff` o un `p_limit` nulos (`LEAST`
  ignora `NULL`) y sobre los lotes cortos ante purgas concurrentes siguen abiertas.

**Riesgos y mitigaciones:**

| Riesgo | Mitigación |
|---|---|
| Alguien concede `DELETE` para «simplificar» | `RolePrivilegeMatrixIT` afirma la ausencia de `DELETE` para cada rol sobre cada tabla de negocio. |
| Una función nueva omite el piso, el contexto o el actor | Pruebas de comportamiento por función (borde exacto, corte en el futuro, sin contexto, actor `staff`, otra institución, límite de lote) y esta plantilla como criterio de revisión. |
| Una función se ejecuta con el `search_path` del llamador | La configuración de la función se afirma en `pg_proc` (`search_path` y `TimeZone`). |
| `EXECUTE` se filtra a otro rol o a `PUBLIC` | `has_function_privilege` exacto para los cinco roles en `RolePrivilegeMatrixIT`. |
| Una purga y una rotación de refresco se esperan en ciclo | La función bloquea las familias en orden de `id`, igual que la rotación; `RefreshPurgeVersusRotationConcurrencyIT` lo demuestra. |

## Cumplimiento y verificación

Todo lo siguiente se ejecuta en integración continua y una falla rompe la construcción.

1. **Matriz de permisos.** `RolePrivilegeMatrixIT` afirma que ningún rol tiene `DELETE` sobre las
   tablas purgables, que solo `confia_admin_app` tiene `EXECUTE` sobre cada función, que su propietario
   es `confia_owner` y que su configuración incluye `search_path` y `TimeZone=UTC`.
   `IdempotencyKeyPrivilegeIT` se amplía igual con `V12`.
2. **Comportamiento de las funciones.** `RetentionPurgeFunctionsIT` y
   `RefreshTokenFamilyPurgeFunctionIT`, sobre PostgreSQL real con dos instituciones: borde exacto, un
   segundo antes y después, piso con corte en el futuro, sin contexto, actor `staff`, otra institución
   y límite de lote. Las demostraciones deliberadas de la tarea 3.3 (sin piso, sin comprobación de
   contexto, `EXECUTE` a `PUBLIC`) pusieron en rojo estas pruebas.
3. **Esquema.** `V11SchemaDiffIT` compara las funciones creadas con las esperadas.
4. **Casos de uso.** `IdentityTokenPurgeJobsIT`, `RefreshTokenPurgeIT`, `AuditChainAfterPurgeIT` e
   `IdentityPurgeJobsOnWorkerIT`: un asiento por lote en la misma transacción, también con cero; los
   200 lotes y el `WARN`; la doble ejecución sin efecto; la cadena de auditoría sigue verificando.
5. **Calendario.** `RecurringJobCatalogTest` fija las tareas, sus horas y la zona `America/Tegucigalpa`.

## Referencias

- ADR-0003: separación entre administración y portal
- ADR-0005: autenticación y gestión de sesiones
- ADR-0015: acceso a datos con jOOQ
- ADR-0016: trabajos en segundo plano
- ADR-0017: tablas técnicas de bibliotecas y tabla raíz de institución
- ADR-0024: registro explícito por punto de entrada
- `docs/03-seguridad.md`, sección 6.1
- `docs/08-datos-privacidad-y-retencion.md`, secciones 6 y 6.2
- `apps/api/app/src/main/resources/db/migration/V11__create_identity_token_purge_functions.sql`
- `openspec/changes/background-jobs-with-db-scheduler/design.md`, decisiones 16, 17 y 22, y sección 13 (D-N3)
- `openspec/changes/background-jobs-with-db-scheduler/proposal.md`, decisiones P2 y P3
- [PostgreSQL: escribir funciones `SECURITY DEFINER` de forma segura](https://www.postgresql.org/docs/current/sql-createfunction.html#SQL-CREATEFUNCTION-SECURITY)
