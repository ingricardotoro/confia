# Propuesta: componente transaccional único y bitácora de auditoría encadenada

- **Cambio:** `audit-log-and-transaction-runner`
- **Fase del roadmap:** F0, cambio 5 de 13, **parte B de dos** (`foundations-plan` §1, nota del
  2026-09-20). La parte A, `jooq-flyway-testcontainers-wiring`, está fusionada y archivada en
  `openspec/changes/archive/2026-09-21-jooq-flyway-testcontainers-wiring/`
- **Exploración:** `openspec/changes/audit-log-and-transaction-runner/exploration.md`
- **Rama:** `change/audit-log-and-transaction-runner`
- **Criterio de salida de F0 que cierra:** criterio 3, «el verificador de cadena de auditoría detecta
  una manipulación inyectada en una prueba» (`docs/09-roadmap-y-fases.md`)
- **Estado:** pendiente de aprobación del propietario (`openspec/config.yaml`, `rules.proposal`)

## Intención

La parte A dejó la tubería de datos en pie: Flyway, generación de código de jOOQ, Testcontainers,
los cinco roles de PostgreSQL y la primera tabla con aislamiento por fila. Lo que no dejó es la
capa transversal que hace que esa tubería sea segura y auditable, y que todo cambio posterior de F0
da por existente.

Hoy, verificado en el árbol: no existe `com.confia.shared.security`, no existe ninguna tabla de
auditoría, no existe el disparador de encadenamiento, no existe el verificador de cadena y no existe
`CommittingPostgresIntegrationTest`. Eso deja tres compromisos abiertos:

1. **Nadie abre una transacción del modo correcto, porque no hay un modo correcto.** ADR-0015 regla 7
   exige un componente único en `shared/security` que fije el nivel de aislamiento, establezca el
   contexto de sesión de `docs/03-seguridad.md` §6.2 como primera sentencia con parámetros vinculados
   y reintente un número acotado de veces ante errores de serialización o de interbloqueo. Sin él,
   las políticas de fila que la parte A instaló dependen de que cada prueba fije el contexto a mano,
   y el cambio 6 (idempotencia) y el 7 (autenticación) no tienen dónde apoyarse.
2. **La bitácora de auditoría es la brecha B6, bloqueante, de `docs/03-seguridad.md` §12.** El
   sistema exige que toda acción sensible se audite (`CLAUDE.md`, regla 14; `openspec/project.md`,
   regla 8), y una bitácora que un administrador puede editar no prueba nada. El encadenamiento por
   hash es lo que convierte un registro en evidencia.
3. **La regla R3 custodia hoy un paquete inexistente.** `TransactionsOnlyInSharedSecurityTest`
   prohíbe `@Transactional`, `TransactionTemplate` y `PlatformTransactionManager` fuera de
   `com.confia.shared.security`, y su propio Javadoc se declara guarda preventiva. Este cambio crea
   ese paquete, y con él la regla pasa a custodiar producción real por primera vez.

Este cambio es **fundación transversal, no negocio**. Su valor se mide por lo que hace posible
después: el cambio 6 no puede escribir idempotencia sin una transacción única, y los cambios 7 y 8
no pueden auditar sin tabla.

## Alcance

### Dentro de alcance

1. **Componente transaccional único** en `com.confia.shared.security` (ADR-0015 regla 7): es el único
   punto que abre transacciones; recibe el nivel de aislamiento requerido (`READ COMMITTED` por
   defecto, `SERIALIZABLE` donde ADR-0010 lo exija); ejecuta como **primera sentencia** de la
   transacción los cuatro `set_config(..., true)` de `docs/03` §6.2 (`app.actor_id`,
   `app.actor_kind`, `app.institution_id`, `app.request_id`) con parámetros vinculados y **jamás**
   `SET SESSION`; ejecuta el caso de uso; y reintenta un número acotado de veces ante errores de
   serialización o de interbloqueo.
2. **Clase base `CommittingPostgresIntegrationTest`** con confirmación real y truncamiento selectivo,
   la segunda variante que `foundations-plan` §2 pidió y que la parte A decidió no crear por no tener
   consumidor. Esta parte es su primer consumidor real, y lo es **dos veces**: la prueba de
   concurrencia y reintento del punto 1 y la prueba de manipulación del punto 5 necesitan filas
   confirmadas y visibles fuera de la transacción de la propia prueba.
3. **Corrección de la regla R3** (`TransactionsOnlyInSharedSecurityTest`): Javadoc de la regla y de
   `BadTransactionalRepository` actualizados —dejan de describir una guarda preventiva— y **aserción
   positiva** nueva: `com.confia.shared.security` contiene de verdad una clase que usa la API de
   transacciones. Sin esa mitad, si el componente se implementara por otro mecanismo la regla
   seguiría en verde sin custodiar nada; es la misma familia de defecto que la parte A pagó por
   aprender.
4. **Migración de `shared_audit_log`**: la tabla de `docs/03` §12.1 con su nombre corregido por la
   convención de prefijo de módulo (`foundations-plan` §2, ADR-0015 regla 3, contando `shared` como
   módulo válido); disparador `BEFORE INSERT` que calcula `prev_hash` y `row_hash` en el motor, no en
   la aplicación, «así una escritura por cualquier vía queda encadenada»; registro génesis con
   `prev_hash` de 32 bytes de ceros; disparadores que rechazan `UPDATE`, `DELETE` y `TRUNCATE`
   **incluso para el propietario del esquema**; `REVOKE ALL ... FROM PUBLIC`, `SELECT` e `INSERT`
   para `confia_admin_app` sin `UPDATE` ni `DELETE`, y ningún privilegio para `confia_portal_app`
   (`docs/03` §6.1 y §12.3); y política de fila por institución (`docs/03` §6.3), que además exigen
   automáticamente las puertas genéricas de `MultiTenantSchemaIT`.
5. **Verificador de cadena** en Java: rutina invocable y probada que recalcula `row_hash` desde el
   registro génesis y **reporta la primera fila divergente identificándola con exactitud**. Se
   nombra aquí su contrato público, para que el cambio 9 solo tenga que envolver la programación. Su
   demostración es la prueba de `docs/03` §12.4: insertar filas, alterar una directamente con
   `SUPERUSER` en el contenedor y verificar que el verificador la detecta e identifica.
6. **Prueba cruzada de la serialización canónica** entre PL/pgSQL y Java con jqwik, sobre entradas
   generadas. Ver «Enfoque», punto 3.
7. **Extensión de las puertas de esquema existentes**, sin reescribirlas: filas de `shared_audit_log`
   en `RolePrivilegeMatrixIT`, más el caso nuevo que el patrón actual no cubre —el rechazo de
   `UPDATE` y `DELETE` al propietario del esquema, que `organization_institution` no necesitó—.
8. **Regla de nomenclatura de pruebas de integración** (deuda W3 de la parte A): toda subclase de
   `PostgresIntegrationTest` termina en `*IT`, con su fixture negativo permanente y el patrón de dos
   mitades ya establecido. Encaja aquí de forma natural porque este cambio es donde la clase base de
   confirmación real obtiene sus primeras subclases.
9. **Corrección de la deriva del nombre de la tabla** en la documentación que hoy dice `audit_log`.
   Ver «Deriva del nombre de la tabla».

### Fuera de alcance

Cada exclusión lleva **destino nombrado**. Ver «Cómo se escriben las exclusiones para que no caduquen
en silencio»: estas tres primeras no se copian como prosa al delta de especificación, se escriben
como requisitos con condición de caducidad.

- **Ancla externa horaria** del último `row_hash` a almacenamiento de objetos con bloqueo en modo de
  cumplimiento (`docs/03` §12.1): **cambio 11**, por decisión del propietario del 2026-09-21, atada a
  la infraestructura de contenedores y despliegue. Se descartó declarar un puerto sin adaptador
  porque ADR-0019 desaconseja superficie especulativa sin consumidor de producción.
- **Programación recurrente del verificador** en sus tres cadencias —diaria, semanal y horaria de
  `docs/03` §12.4—: **cambio 9**, que trae db-scheduler; por ADR-0016 es trabajo de `confia-worker`.
- **Filtro de lectura por permiso `audit:read`** (`docs/03` §6.3, línea 711): **cambios 7 y 8**. El
  criterio de fila de la bitácora es solo la institución; el filtro por permiso se aplica en la capa
  `web` y depende del control de acceso por roles. Esta parte no construye lectura, ni controlador,
  ni verificación de permiso.

Y además, sin requisito de caducidad porque no son brechas de este control:

- **Infraestructura de clave de idempotencia** (ADR-0010, brecha B7): **cambio 6**,
  `idempotency-key-infrastructure`, que depende de este.
- **Bloqueo pesimista `SELECT ... FOR UPDATE`** de ADR-0015 regla 8: la consulta pertenece al
  repositorio dueño de la cuenta del estudiante, que es `ledger` en **F3**. El componente
  transaccional no lleva ayudante de bloqueo sin consumidor.
- **Adaptador de escritura de auditoría en Java.** Recomendación: no se entrega aquí. No hay ninguna
  acción sensible en producción que auditar hasta los **cambios 7 y 8**; el disparador encadena
  cualquier inserción venga de donde venga, y las pruebas insertan directamente. Ver la pregunta de
  diseño P4.
- **Eventos de auditoría obligatorios** de `docs/03` §12.2 (identidad, autorización, dinero, fiscal,
  caja, datos personales, configuración, operación): llegan con la capacidad que los produce. Esta
  parte entrega el continente, no el contenido.
- **Tablas `scheduled_tasks` y `event_publication`**: cambio 9 y el cambio que publique el primer
  evento de dominio, respectivamente, según resolvió la decisión D1 de la parte A.
- **Deudas W1 y W2 de la parte A.** Ver «Deudas heredadas».

## Capacidades

> Contrato con `/sdd-spec`. **D1 resuelta por el propietario el 2026-09-21**, en un punto
> intermedio entre las dos variantes que esta propuesta ofrecía: se publica **una** capacidad nueva,
> `audit-trail`, y el componente transaccional entra como delta de `build-integrity`, que ya
> custodia la regla 7 de ADR-0015. La línea 127 de `openspec/project.md` se actualiza **en este
> mismo cambio**, porque contradecirla en silencio dejaría el repositorio con dos convenciones.

`openspec/project.md` (líneas 127–128) dice que la bitácora de auditoría (`shared/audit`) y la
infraestructura de `shared/security` «se construyen en F0 como fundación transversal, no como
capacidad propia». Leído literalmente, eso deja sin casa el comportamiento observable que este
cambio entrega. La parte A resolvió su caso análogo metiendo el comportamiento de ejecución en la
capacidad de negocio que ya existía (`organization`); aquí no existe ninguna capacidad de negocio
equivalente, y `build-integrity` es una capacidad de **puertas de construcción**, no de controles de
seguridad en ejecución.

### Nuevas (decididas)

- `audit-trail`: la bitácora de solo inserción encadenada por hash, sus permisos y disparadores, el
  contrato del verificador de cadena, y **el límite declarado del control mientras no exista ancla
  externa**.

La bitácora recibe capacidad propia porque es una garantía de tiempo de ejecución con valor
probatorio, no una puerta de construcción: enterrada dentro de `build-integrity` sería imposible de
encontrar para quien busque qué promete el sistema sobre la trazabilidad del dinero. Sigue el
precedente de `money`, que también es una capacidad técnica publicada y ausente de la tabla de
capacidades de negocio de `openspec/project.md`.

`transactional-runtime` **no se crea.** El componente transaccional entra como delta de
`build-integrity`, que ya custodia la regla 7 de ADR-0015 a través del requisito «Transacciones
confinadas al componente único de `shared/security`». Una capacidad nueva para un solo componente,
cuyo contrato observable ya vive en una regla existente, añadiría superficie que mantener y archivar
sin hacer nada más fácil de encontrar.

### Modificadas

- `build-integrity`: delta con `## ADDED Requirements` y `## MODIFIED Requirements` por
  - la regla R3 con su mitad positiva (el requisito «Transacciones confinadas al componente único de
    `shared/security`» deja de describir una guarda preventiva y pasa a exigir que el componente
    exista y use la API de transacciones);
  - la regla de nomenclatura de subclases de `PostgresIntegrationTest` (deuda W3, escenario 17 de la
    especificación de la parte A, hoy **SIN PRUEBA**);
  - la extensión de las puertas de catálogo y de privilegios a `shared_audit_log`, incluido el
    rechazo de `UPDATE` y `DELETE` al propietario del esquema.

El componente transaccional aporta además al delta de `build-integrity` su contexto de sesión por
transacción con parámetros vinculados, su nivel de aislamiento, su reintento acotado ante error de
serialización, y la prohibición de que cualquier otro código abra transacciones.

## Enfoque

De adentro hacia afuera, al revés que la parte A, porque aquí lo caro es el control y no el
mecanismo, que ya existe. Cada paso deja `./mvnw verify` en verde antes del siguiente y sigue TDD
estricto (`openspec/config.yaml`, `strict_tdd: true`).

1. **El componente transaccional antes que la tabla, y no por comodidad.** Verificado en
   `MultiTenantSchemaIT.everyBusinessTableNameCarriesItsOwnerModulesPrefixExceptTheClosedCatalogue`
   (líneas 117–135 y 148–155): el conjunto de módulos válidos se **deriva de los paquetes de
   producción reales** bajo `com.confia`, excluyendo pruebas. Mientras no exista ninguna clase de
   producción en `com.confia.shared.*`, el módulo `shared` no existe, y una migración que cree
   `shared_audit_log` **rompe esa puerta**. El orden no es preferencia: es una dependencia mecánica.
2. **La clase base de confirmación real nace con el componente, no con la bitácora.** ADR-0015
   verificación 7 exige una prueba de concurrencia que fuerce un error de serialización y afirme que
   la operación termina bien tras el reintento. Eso necesita dos transacciones reales confirmadas,
   igual que la prueba de manipulación. Esto corrige la división que proponía la exploración; ver
   «Pronóstico de cortes».
3. **La serialización canónica se fija primero y se prueba contra sí misma.** El algoritmo se declara
   de forma explícita —recomendación: adaptar la canonicalización de RFC 8785 al subconjunto de
   campos de `docs/03` §12.1, con orden de claves, normalización de números y codificación de
   `NULL` decididos por escrito— y se escribe una **prueba de propiedades con jqwik** que compare el
   hash de la implementación en PL/pgSQL con el de la implementación en Java sobre entradas
   generadas, incluidas las que producen divergencia clásica: números con cero a la derecha, textos
   con caracteres no ASCII, `NULL` frente a cadena vacía, `JSONB` con claves repetidas o anidadas, y
   marcas de tiempo con zona. El algoritmo exacto **es una decisión de la fase de diseño**, no de
   esta propuesta: hoy no hay una línea de PL/pgSQL escrita y la documentación actual no lo cierra.
4. **La tabla en rojo-verde, como hizo la parte A:** primero las pruebas de catálogo, privilegios y
   rechazo de escritura; después la migración.
5. **El verificador al final**, cuando ya hay filas confirmadas que recalcular y una clase base que
   las deja confirmadas.
6. **La documentación se corrige en el mismo corte que crea la tabla**, para que el repositorio no
   cruce ninguna frontera de fusión con dos nombres vivos para la misma tabla.

### El límite del control sin ancla externa, como requisito visible

`docs/03` §12.1 dice textualmente que sin el ancla publicada fuera de la base de datos «la cadena
solo protege contra manipulación torpe», y §12.3 añade que un `SUPERUSER` puede deshabilitar el
disparador: «el objetivo alcanzable no es hacer la manipulación imposible, sino hacerla evidente».
Esta propuesta **se compromete a que la especificación declare ese límite como requisito**, no como
nota al pie, y propone la forma concreta:

- Requisito propio, con título propio, dentro de la capacidad `audit-trail`, en la línea de
  **«Límite declarado del control mientras no exista ancla externa»**. No una nota, no un párrafo de
  contexto, no una advertencia dentro de otro requisito.
- Su enunciado debe decir, en positivo y en negativo, qué protege la cadena y qué no: detecta la
  alteración, el borrado o la inserción de una fila que **no** recalcule la cadena completa; **no**
  detecta a un actor con acceso administrativo al motor que recalcule toda la cadena, ni a uno que
  deshabilite el disparador.
- Y debe hacerlo **ejecutable**, que es lo que impide que envejezca en silencio: dos escenarios
  simétricos, ambos verificados por prueba de integración. Uno, manipulación sin recálculo: el
  verificador identifica la fila exacta. Dos, manipulación con recálculo completo de la cadena: el
  verificador **reporta integridad y no detecta nada**, y la especificación declara ese resultado
  como límite conocido y aceptado mientras el ancla del cambio 11 no exista.

El segundo escenario es incómodo a propósito. Una prueba que pasa afirmando que el control no ve la
manipulación es la única redacción que impide que alguien lea la bitácora como inviolable, y el día
que llegue el ancla externa esa prueba **tiene que cambiar**, lo que obliga al cambio 11 a emitir un
delta `MODIFIED` sobre este requisito en vez de olvidarlo.

### Cómo se escriben las exclusiones para que no caduquen en silencio

La parte A dejó un punto de «Fuera de alcance» en `openspec/specs/organization/spec.md` que dejó de
ser cierto con su propia entrega, y hubo que retirarlo a mano durante el archivado porque vivía
fuera de todo bloque `### Requisito:` y el mecanismo `ADDED`/`MODIFIED`/`REMOVED` no lo alcanzaba
(hallazgo S8 del informe de verificación de la parte A). Esta propuesta lo evita por construcción:

1. Toda exclusión que un cambio posterior vaya a cerrar se escribe **dentro de un bloque
   `### Requisito:`**, nunca como prosa suelta ni como sección «Fuera de alcance» del delta.
2. Cada uno de esos requisitos nombra **el cambio dueño** de la brecha (11, 9, o 7 y 8) y contiene al
   menos un escenario **cierto hoy y falso el día que la brecha se cierre**.
3. Donde una prueba pueda sostener el escenario, lo sostiene. Las tres exclusiones con destino
   nombrado admiten esa forma: el límite sin ancla, arriba; el alcance de verificación sin
   programación recurrente —el verificador es invocable y **no** hay tarea programada que lo
   ejecute—; y el criterio de fila solo por institución, **sin** filtro por `audit:read`.
4. La sección «Fuera de alcance» de este documento se queda en este documento. El delta de
   especificación no la repite.

## Deriva del nombre de la tabla

`foundations-plan` §2 decidió `shared_audit_log` y la parte A lo confirmó en su resolución del
2026-09-20, pero el repositorio sigue diciendo `audit_log` en varios sitios. Revisé por búsqueda y
por lectura cada archivo, y decido así:

| Archivo | Ocurrencias | Decisión | Motivo |
|---|---|---|---|
| `docs/03-seguridad.md` §6.1, §6.3, §12.1, §12.3, lista de verificación y tabla de trabajos | ~25 | **Dentro** | Es el documento fuente de este cambio. Si no se corrige aquí, no se corrige nunca |
| `.claude/skills/confia-audit-logging/SKILL.md` | ~17, con DDL y ejemplos de código | **Dentro** | Es la skill que un agente carga justo antes de escribir código de auditoría. Dejarla con el nombre viejo garantiza código equivocado |
| `.claude/agents/confia-database.md` | 4 | **Dentro** | Es el agente que escribe migraciones y revisa permisos; misma razón |
| `docs/07-observabilidad-y-operaciones.md` | 7, referencias a columnas (`audit_log.request_id`, `audit_log.trace_id`) | **Dentro** | Renombrado mecánico y barato; son contratos de correlación que el cambio 10 va a leer |
| `docs/08-datos-privacidad-y-retencion.md` | 4, inventario de datos y retención | **Dentro** | Renombrado mecánico; la política de conservación de 7 años apunta a esta tabla por nombre |
| `docs/runbooks/incidente-de-seguridad.md` | 5 | **Dentro, con reconciliación de columnas** | Es el runbook que `docs/03` §12.4 dispara ante discrepancia de cadena. Ver abajo |
| `docs/runbooks/restauracion-de-respaldo.md` | 2 | **Dentro, con reconciliación de columnas** | Ver abajo |
| `docs/runbooks/descuadre-de-libro-mayor.md` | 1 | **Dentro** | Solo renombrado; sus columnas sí existen |
| `docs/runbooks/cierre-de-caja-con-diferencia.md` | 1 | **Dentro** | Solo renombrado; sus columnas sí existen |
| `docs/adr/ADR-0003-separacion-admin-portal.md`, línea 225 | 1 | **Nota fechada, sin reescribir** | Un ADR es un registro histórico con fecha. Se añade una nota editorial de una línea que remite al nombre vigente, en vez de reescribir una decisión ya tomada. Si el propietario prefiere no tocar el ADR, se omite sin consecuencia |
| `openspec/changes/archive/**` | varias | **Fuera** | El archivo es rastro de auditoría. No se edita, nunca |

**Hallazgo adicional, verificado por lectura, no por búsqueda.** Dos runbooks no tienen solo el
nombre de la tabla equivocado: tienen la **consulta entera** escrita contra un esquema que nunca
existió. `incidente-de-seguridad.md` líneas 132–137 y `restauracion-de-respaldo.md` líneas 190–195
comparten esta consulta de integridad de cadena:

```sql
SELECT count(*) AS eslabones_rotos
  FROM audit_log a
  JOIN audit_log p ON p.id = a.previous_id
 WHERE a.previous_hash <> p.record_hash;
```

Las columnas `previous_id`, `previous_hash` y `record_hash` no aparecen en el DDL de `docs/03` §12.1,
que declara `prev_hash` y `row_hash` y no tiene ningún `previous_id`. `incidente-de-seguridad.md`
usa además `ip_address` donde la tabla declara `source_ip`. Un runbook que falla por «columna
inexistente» durante un incidente S1 falla exactamente cuando más se necesita, así que la
reconciliación de esas consultas contra el esquema real que este cambio entrega **entra en alcance**,
y es barata porque el esquema se está escribiendo aquí.

**Corrección a la exploración.** §5 de `exploration.md` listó
`.claude/skills/confia-security-checklist/SKILL.md` entre los archivos con deriva. Verifiqué: ese
archivo **no contiene** la cadena `audit_log` en ninguna línea; solo menciona «la bitácora de
auditoría» en prosa, en las líneas 51 y 283, que no necesitan cambio. Queda fuera de alcance por no
tener nada que corregir.

## Deudas heredadas de la parte A

El informe de verificación de la parte A dejó seis WARNING. Este cambio cierra dos y justifica por
qué no toca los otros.

| Deuda | Decisión | Motivo |
|---|---|---|
| **W3** — nada impide nombrar `*Test` a una clase que necesita contenedor (escenario 17, SIN PRUEBA) | **Dentro** | Este cambio crea la segunda clase base de contenedor y sus primeras subclases; es el momento en que el riesgo se multiplica. El propio informe señala que el riesgo no es teórico: `PostgresImageSingleSourceTest` vive en el mismo paquete `support` y bastaría hacerla extender la base para arrancar un contenedor bajo Surefire |
| **R3** — la regla de transacciones deja de ser guarda preventiva | **Dentro** | No es opcional: al crearse `com.confia.shared.security`, la rama de exención de la regla deja de estar vacía. Javadoc obsoleto en la regla y en `BadTransactionalRepository`, más la aserción positiva |
| **W1** — el presupuesto de 8 minutos no se exige | **Fuera** | Exigirlo requiere tocar `.github/workflows/ci.yml` y añadir una medición del tiempo de Failsafe: maquinaria de integración continua que este cambio no toca para ninguna otra cosa. Además, este cambio **empeora** el número, no lo mejora: añade pruebas de concurrencia con confirmación real. Medir y reportar el tiempo al cerrar cada corte sí entra, como hizo la parte A; exigirlo automáticamente se difiere, con destino nombrado en `docs/09` |
| **W2** — la fuente única de versión no cubre al segundo consumidor | **Fuera** | Toca el complemento de generación de código y el POM, maquinaria que esta parte no necesita tocar. La exploración lo llamó «relleno» y coincido. Se difiere al cambio que traiga Renovate, donde la etiqueta móvil `postgres:18-alpine` ya está registrada como seguimiento (S7) |
| W4 — `docs/06` §14.2 enseñaba un verde falso de RLS | **Ya cerrada** | Corregida en `main` por el commit `bc21440`; verificado: `docs/06` líneas 634 y 640 ya usan `withUsername("postgres")` y `/var/lib/postgresql` |
| W5 — la rama `is_active = false` sin prueba | **Ya cerrada** | Mismo commit |
| W6 — `design.md` de la parte A describía `CommittingPostgresIntegrationTest`, que no existía | **Se cierra por construcción** | Este cambio crea el archivo |

## Áreas afectadas

| Área | Impacto | Descripción |
|---|---|---|
| `apps/api/app/src/main/java/com/confia/shared/security/` | Nueva | Componente transaccional único y su API pública |
| `apps/api/app/src/main/java/com/confia/shared/audit/` | Nueva | Verificador de cadena y la serialización canónica en Java |
| `apps/api/app/src/main/resources/db/migration/` | Nueva | Migración de `shared_audit_log`: tabla, índices, disparador de encadenamiento, disparadores de solo inserción, política de fila y `GRANT` |
| `apps/api/app/src/test/java/com/confia/support/CommittingPostgresIntegrationTest.java` | Nueva | Clase base con confirmación real y truncamiento selectivo |
| `apps/api/app/src/test/java/com/confia/shared/security/**IT.java` | Nueva | Contexto establecido antes de cualquier consulta; reintento ante error de serialización; concurrencia real |
| `apps/api/app/src/test/java/com/confia/shared/audit/**IT.java` | Nueva | Manipulación detectada e identificada; manipulación con recálculo completo no detectada; concurrencia del disparador |
| `apps/api/app/src/test/java/com/confia/shared/audit/**Test.java` | Nueva | Prueba cruzada de serialización canónica con jqwik |
| `apps/api/app/src/test/java/com/confia/architecture/TransactionsOnlyInSharedSecurityTest.java` | Modificada | Javadoc y aserción positiva |
| `apps/api/app/src/test/java/com/confia/architecture/fixtures/BadTransactionalRepository.java` | Modificada | Javadoc obsoleto |
| `apps/api/app/src/test/java/com/confia/architecture/` (regla y fixture nuevos) | Nueva | Nomenclatura `*IT` de subclases de `PostgresIntegrationTest` (W3) |
| `apps/api/app/src/test/java/com/confia/schema/RolePrivilegeMatrixIT.java` | Modificada | Filas de `shared_audit_log`; rechazo de escritura al propietario del esquema |
| `apps/api/app/src/test/java/com/confia/schema/MultiTenantSchemaIT.java` | Posible | Solo si la clave primaria de la bitácora obliga a ajustar la puerta de índices únicos (ver P2) |
| `docs/03-seguridad.md` | Modificada | Nombre de la tabla en §6.1, §6.3, §12 y la lista de verificación |
| `docs/07-observabilidad-y-operaciones.md`, `docs/08-datos-privacidad-y-retencion.md` | Modificadas | Nombre de la tabla y de sus columnas |
| `docs/runbooks/` (cuatro archivos) | Modificados | Nombre de la tabla; reconciliación de columnas en dos de ellos |
| `.claude/skills/confia-audit-logging/SKILL.md`, `.claude/agents/confia-database.md` | Modificados | Nombre de la tabla en DDL y ejemplos |
| `docs/09-roadmap-y-fases.md` | Modificada | Criterio de salida 3 y estado de las deudas W1 y W2 |
| `openspec/project.md` | Posible | Solo si D1 se aprueba: alta de las capacidades nuevas |
| `openspec/specs/build-integrity/spec.md` | Delta al archivar | R3, W3 y puertas de esquema |

## Pronóstico de cortes y tamaño

Presupuesto vigente: **800 líneas de cambio efectivo por pull request** (`docs/15-flujo-de-trabajo-git.md`
§3, `CLAUDE.md`), con entrega en **cortes encadenados**. Esta propuesta no reabre ninguno de los dos.

Referencia real, no estimación: la parte A pronosticó 1 150 a 2 100 líneas y cerró en **1 537**
(1 502 adiciones más 35 eliminaciones), repartidas en cuatro pull requests de 727, 424, 768 y el
resto, porque A3 se partió sobre la marcha al medir 813. Esta parte tiene un alcance estructuralmente
mayor y, a diferencia de A, escribe lógica en **dos lenguajes** —PL/pgSQL y Java— para el mismo
algoritmo.

**La división de tres cortes de la exploración no se sostiene tal cual, y por una razón concreta.**
La exploración asignaba `CommittingPostgresIntegrationTest` al tercer corte, junto al verificador.
Pero la verificación 7 de ADR-0015 exige una prueba de concurrencia que fuerce un error de
serialización y afirme que la operación termina bien tras el reintento, y eso necesita dos
transacciones reales confirmadas: la clase base es requisito del **primer** corte, no del tercero.
La división correcta es la misma en espíritu, con la clase base movida:

| Corte | Contenido | Estimación |
|---|---|---|
| **B1 — componente transaccional y base de confirmación real** | Componente en `shared/security`, `CommittingPostgresIntegrationTest`, pruebas de contexto, de reintento y de concurrencia, corrección de R3 con su mitad positiva | 450 a 800 |
| **B2a — tabla, permisos y solo inserción** | DDL de `shared_audit_log` sin encadenamiento, política de fila, `REVOKE`/`GRANT`, disparadores que rechazan `UPDATE`, `DELETE` y `TRUNCATE` incluso al propietario, extensión de `RolePrivilegeMatrixIT` y de las puertas de catálogo, más **toda la corrección documental del nombre** | 450 a 800 |
| **B2b — encadenamiento por hash en el motor** | Serialización canónica en PL/pgSQL, disparador `BEFORE INSERT`, registro génesis, concurrencia del encadenamiento | 250 a 450 |
| **B3 — verificador y pruebas de manipulación** | Serialización canónica en Java, verificador con su contrato público, prueba de manipulación con `SUPERUSER`, prueba del límite sin ancla, prueba cruzada con jqwik | 450 a 800 |
| **B4 — regla de nomenclatura `*IT`** (W3) | Regla de ArchUnit, fixture negativo permanente e inventario | 100 a 200 |
| **Total de código** | | **1 700 a 3 050** |
| Artefactos de OpenSpec | | 700 a 1 200 adicionales |

**Pronóstico de entrega: cuatro o cinco pull requests encadenados.** B4 se fusiona con B1 o con B3 si
al medir cabe bajo 800; se declara aparte para que la decisión se tome con el diff en la mano y no
con una estimación. B2a y B2b podrían ir juntos en el extremo bajo, pero se declaran separados
porque el encadenamiento por hash es el punto más denso del cambio y merece revisión propia.

**Tareas:** entre 15 y 19, por encima del límite de quince de `openspec/changes/README.md`. Aplica el
mismo precedente que el propietario concedió en los cambios 4 y 5A: el límite se mide por pull
request cuando el cambio se entrega encadenado.

Al cerrar cada corte se mide el diff real, como hizo la parte A, y si supera 800 la aplicación se
detiene y el corte se parte.

## Puntos de diseño abiertos

No son decisiones de producto. Son preguntas técnicas que esta propuesta **no cierra a propósito**,
porque cerrarlas sin escribir una línea de PL/pgSQL sería inventar.

- **P1. Algoritmo exacto de serialización canónica.** Recomendación: adaptación documentada de RFC
  8785 al subconjunto de campos de `docs/03` §12.1, con reglas escritas para orden de claves,
  normalización de números, codificación de `NULL`, `JSONB` anidado y marcas de tiempo con zona. La
  duplicación entre PL/pgSQL y Java es **el riesgo técnico principal del cambio**; se mitiga con la
  prueba cruzada de jqwik, no con disciplina.
- **P2. RESUELTA junto con P3 (propietario, 2026-09-21): clave primaria compuesta
  `(institution_id, id)`.** Queda para el diseño únicamente la forma exacta de generar el
  componente secuencial por institución. Contexto original, que se conserva porque explica por qué:
  `MultiTenantSchemaIT.everyUniqueIndexOfABusinessTableIncludesTheInstitutionDiscriminator` (líneas
  84–95) exige que todo índice único de una tabla de negocio distinta de la raíz incluya
  `institution_id`. El DDL de `docs/03` §12.1 declara `id BIGSERIAL PRIMARY KEY`, cuyo índice único
  contiene solo `id`: **tal cual está escrito, rompe una puerta que la parte A ya entregó en verde**.
  El diseño debe resolverlo, y la salida obvia —clave primaria compuesta `(institution_id, id)`— está
  acoplada a P3.
- **P3. RESUELTA (propietario, 2026-09-21): una cadena por institución.** Cada institución tiene su
  propio registro génesis y su propia cadena; el verificador corre con el contexto de cada una y
  recalcula la suya. Se descartaron las dos variantes de cadena global: crear un sexto rol con
  `BYPASSRLS` contradice `docs/03` línea 632 —«ese detalle es crítico»— y crearía la única llave
  capaz de leer los datos de todas las instituciones; y verificar como propietario del esquema
  exigiría quitar `FORCE ROW LEVEL SECURITY` de la bitácora, que es justo el atributo que impide el
  verde falso en las pruebas de aislamiento.

  **Consecuencias que este cambio DEBE asumir:** `docs/03-seguridad.md` §12.4 se actualiza, porque su
  redacción en singular deja de ser cierta; y el cambio 11 anclará una cadena por institución, no un
  solo valor, lo que debe quedar escrito donde ese cambio lo encuentre.

  Contexto original, que se conserva porque explica por qué existía la tensión:
  `docs/03` §12.4 habla de «verificación completa
  desde el registro génesis», en singular, lo que sugiere una cadena única. Pero la tabla lleva
  política de fila por institución y **ningún rol del sistema tiene `BYPASSRLS`** (`docs/03` §6.1),
  así que un verificador que corra con contexto de una institución no puede ver las filas de las
  otras y no puede recalcular una cadena global. Una cadena por institución resuelve la tensión y
  encaja con P2, a costa de apartarse de la lectura literal de §12.4. Es una decisión de diseño con
  consecuencias para el cambio 11: el ancla externa ancla una cadena o varias.
- **P4. ¿Entrega esta parte un camino de escritura en Java?** Recomendación: no, por ADR-0019; las
  pruebas insertan directamente y el disparador encadena venga la escritura de donde venga. Si al
  escribir la prueba de manipulación resulta que hace falta una superficie mínima, el diseño lo
  declara y lo justifica en vez de ampliarlo en silencio.
- **P5. Capa del componente transaccional.** `foundations-plan` §2 ya decidió que `application` lo
  llama «tratándolo como API pública de `shared/security`, con una excepción documentada a la regla
  `application-no-infrastructure` y comentario del ADR que la autoriza». El diseño debe fijar en qué
  paquete vive exactamente para que `LayeredArchitectureTest` no lo rechace, y la excepción debe
  citar su ADR, porque ADR-0008 y `SuppressionCitesAdrTest` no admiten supresiones sin cita.

## Riesgos

| Riesgo | Probabilidad | Mitigación |
|---|---|---|
| **Divergencia silenciosa entre la serialización canónica de PL/pgSQL y la de Java**, que produce falsos positivos de manipulación en producción | Alta | Es el riesgo principal. Algoritmo explícito y escrito (P1) más prueba de propiedades con jqwik que compara ambas implementaciones sobre entradas generadas, con las familias de entrada que históricamente divergen enumeradas en el diseño. Un falso positivo destruye la credibilidad del control, que es todo su valor |
| El orden de los cortes se altera y la migración llega antes que `com.confia.shared.*`, rompiendo la puerta de prefijo de módulo | Media | Dependencia mecánica declarada en el enfoque, punto 1, y en la tabla de cortes. Es un rojo inmediato y ruidoso, no un defecto silencioso |
| La clave primaria de `docs/03` §12.1 rompe la puerta de índices únicos (P2) | **Alta, verificada** | Resolver en diseño junto con P3, antes de escribir la migración. No relajar la puerta: es un control de aislamiento entregado y probado |
| Cadena global incompatible con la política de fila y con la ausencia de `BYPASSRLS` (P3) | Alta | Decisión de diseño explícita, con su consecuencia para el cambio 11 declarada por escrito |
| El pronóstico (1 700 a 3 050 líneas) exige cuatro o cinco pull requests, con historial de subestimación de 1,5× a 3× en este repositorio | Alta | Cortes encadenados ya fijados; medir el diff real al cerrar cada corte y partir el corte si supera 800, como hizo la parte A con A3 |
| La prueba de concurrencia del disparador `BEFORE INSERT` resulta escamosa: dos transacciones confirmadas encadenando a la vez pueden serializar o chocar según el aislamiento | Media | Diseñar la prueba sobre un mecanismo de sincronización determinista, no sobre tiempos; si el encadenamiento exige un bloqueo, declararlo como decisión de diseño con su costo de contención escrito |
| Las pruebas con confirmación real dejan residuos entre clases y contaminan otras pruebas | Media | Truncamiento selectivo en la clase base, con la salvedad de que `shared_audit_log` **rechaza `TRUNCATE` por disparador**: el diseño debe resolver cómo se limpia la bitácora entre pruebas sin debilitar el control en producción |
| La suite de integración crece y se acerca al presupuesto de 8 minutos, que hoy se mide pero no se exige (W1) | Media | Medir y reportar el tiempo al cerrar cada corte, como hizo la parte A. Si el margen se estrecha de verdad, W1 deja de ser diferible y se eleva |
| El límite sin ancla externa se lee como defecto y no como límite declarado | Media | Requisito propio con escenario ejecutable, no nota al pie. Ver «El límite del control sin ancla externa» |
| Corregir el nombre de la tabla en nueve archivos de documentación infla el diff del corte B2a | Media | Es renombrado mecánico y se concentra en un solo corte; si empuja B2a sobre 800, la corrección documental se entrega como corte propio antes de la migración, nunca después |
| Una migración aplicada se edita después | Baja hoy | No hay entorno desplegado: la base vive solo en contenedores efímeros. La regla entra en vigor con el cambio 11 |

## Plan de reversión

Sigue siendo barata, y por la misma razón que en la parte A: **no existe ningún entorno desplegado ni
ningún dato real**. La base de datos vive exclusivamente en contenedores de prueba efímeros.

1. **Revertir el commit de fusión** del corte afectado, o cerrar su pull request. Con los cortes
   encadenados, revertir uno obliga a revertir los posteriores que dependan de él.
2. **B1 y B2a no se revierten por separado.** Si desaparece `com.confia.shared.security`, el módulo
   `shared` deja de existir y `shared_audit_log` rompe la puerta de prefijo de módulo. Son el mismo
   invariante, igual que lo eran el `optionalLayer("Infrastructure")` y la primera clase de
   `infrastructure` en la parte A.
3. **La corrección de R3 se revierte con el componente.** Si el componente se va y la aserción
   positiva se queda, la regla falla contra un paquete inexistente.
4. No hay dato que migrar hacia atrás ni migración de reverso que escribir: el siguiente
   `./mvnw verify` levanta un contenedor nuevo y aplica lo que quede en `db/migration`.
5. Si el encadenamiento por hash en PL/pgSQL resulta inviable con PostgreSQL 18, la reversión parcial
   es retirar B2b y detenerse con la tabla de solo inserción entregada, que ya es un control real.
   **No** se mueve el cálculo del hash a la aplicación como salida de emergencia: `docs/03` §12.1 lo
   sitúa en el motor precisamente para que una escritura por cualquier vía quede encadenada. Cambiar
   eso es una decisión de arquitectura y se eleva a ADR.
6. Una puerta que bloquee por error **no se desactiva con una bandera** (ADR-0008): se revierte el
   commit que la introdujo o se corrige con un ADR.

**Punto de no retorno práctico:** el cambio 11, cuando exista el primer entorno desplegado y la
bitácora empiece a acumular evidencia real. Desde entonces, la cadena no se reconstruye: se preserva.

## Dependencias

- **Parte A, `jooq-flyway-testcontainers-wiring`**, fusionada y archivada: Flyway, generación de
  código de jOOQ, Testcontainers, los cinco roles de PostgreSQL, `TransactionalPostgresIntegrationTest`,
  `RolePrivilegeMatrixIT`, `MultiTenantSchemaIT` y las cuatro reglas de ArchUnit de ADR-0015.
- JDK 25 y **Docker** en local y en integración continua, ya exigidos por la construcción desde A1.
- jqwik, ya presente en el stack de pruebas del backend.
- **Dependen de este cambio:** el 6 (idempotencia), el 7 (autenticación), el 8 (control de acceso por
  roles), el 9 (trabajos en segundo plano) y el 11 (ancla externa).

## Criterios de éxito

- [ ] `./mvnw verify` en `apps/api` termina en verde con JDK 25 y Docker, en local y en integración
      continua, incluida la puerta de mutación de `main`.
- [ ] Existe **un solo** componente que abre transacciones, en `com.confia.shared.security`, y la
      regla R3 lo demuestra en sus dos mitades: rechaza su fixture nombrándolo, y **afirma en
      positivo** que ese paquete contiene una clase que usa la API de transacciones.
- [ ] Una prueba de integración demuestra que el componente ejecuta los cuatro `set_config(..., true)`
      con parámetros vinculados **antes de cualquier consulta** del caso de uso, y que ningún valor
      sobrevive a la transacción.
- [ ] Una prueba de concurrencia con transacciones confirmadas fuerza un error de serialización y
      afirma que la operación termina correctamente tras el reintento acotado (ADR-0015,
      verificación 7).
- [ ] `shared_audit_log` existe con `ENABLE` y `FORCE ROW LEVEL SECURITY` y política por institución,
      y las puertas genéricas de `MultiTenantSchemaIT` pasan sobre ella sin excepción ni lista de
      exclusión.
- [ ] `confia_admin_app` puede `SELECT` e `INSERT` y **no** puede `UPDATE` ni `DELETE`;
      `confia_portal_app` no tiene ningún privilegio; una prueba de matriz lo verifica.
- [ ] `UPDATE`, `DELETE` y `TRUNCATE` sobre la bitácora son rechazados por el motor **también para el
      propietario del esquema**, con su propio caso de prueba.
- [ ] Toda fila insertada queda encadenada por el disparador, sin que la aplicación calcule ni pase
      `prev_hash` o `row_hash`, y la cadena arranca en un registro génesis con 32 bytes de ceros.
- [ ] Dos transacciones confirmadas que insertan a la vez encadenan sin condición de carrera.
- [ ] **Criterio de salida 3 de F0:** una fila alterada directamente con `SUPERUSER` es detectada por
      el verificador, que **identifica la fila exacta**.
- [ ] Una prueba de propiedades con jqwik compara los hashes de la implementación en PL/pgSQL y la de
      Java sobre entradas generadas y no encuentra divergencia.
- [ ] La especificación declara, **como requisito con título propio y con escenario ejecutable**, que
      mientras no exista el ancla externa del cambio 11 la cadena no detecta a un actor con acceso
      administrativo que la recalcule entera. No como nota, no como advertencia dentro de otro
      requisito.
- [ ] Las tres exclusiones con destino nombrado —ancla externa, programación recurrente y filtro por
      `audit:read`— viven dentro de bloques `### Requisito:`, nombran su cambio dueño y tienen un
      escenario que dejará de ser cierto cuando la brecha se cierre.
- [ ] Nombrar `*Test` a una subclase de `PostgresIntegrationTest` rompe la construcción, con fixture
      negativo permanente (deuda W3).
- [ ] Una búsqueda de `audit_log` en el repositorio devuelve resultados **solo** en
      `openspec/changes/archive/` y en el registro histórico de los ADR.
- [ ] Las consultas de integridad de cadena de `docs/runbooks/incidente-de-seguridad.md` y
      `docs/runbooks/restauracion-de-respaldo.md` se ejecutan sin error contra el esquema entregado.
- [ ] El tiempo de la suite `*IT.java` se **mide y se reporta** al cerrar cada corte, no se estima.

## Decisiones que confirma el propietario al aprobar

El modo de ejecución es automático: estas decisiones quedan registradas en lugar de preguntarse en
conversación. El orquestador las presenta. Son las únicas que esta propuesta **no** resuelve.

- **D1. ¿Capacidades nuevas, o todo dentro de `build-integrity`?** Afecta directamente al contrato con
  `/sdd-spec` y, si se aprueba, exige actualizar `openspec/project.md` **antes** de la fase de
  especificación.
  - *Recomendación:* dos capacidades técnicas nuevas, `transactional-runtime` y `audit-trail`, más el
    delta de `build-integrity`. Motivo: `build-integrity` especifica puertas de construcción; el
    encadenamiento por hash, el verificador y el límite declarado del control son comportamiento de
    seguridad en ejecución, y van a ser leídos por los cambios 7, 8, 9 y 11 como contrato, no como
    regla de compilación. La nota de `project.md` (líneas 127–128) dice que estas fundaciones no son
    **capacidad de negocio**, y `build-integrity` ya sentó el precedente de que una capacidad técnica
    es admisible.
  - *Alternativa:* ninguna capacidad nueva y todos los requisitos dentro de `build-integrity`, como
    resolvió la decisión D4 de la parte A. Más barato hoy, a costa de que la especificación de las
    puertas de construcción acabe siendo la casa del control de auditoría del sistema.
- **D2. Nota editorial en ADR-0003.** Su línea 225 nombra `audit_log`. La recomendación es añadir una
  nota fechada de una línea que remita al nombre vigente, **sin reescribir** el cuerpo de una
  decisión ya tomada. Si el propietario prefiere no tocar ningún ADR, se omite: el resto del
  renombrado no depende de ello.
- **D3. Aprobación de la propuesta completa** antes de especificar, diseñar y planificar tareas
  (`openspec/config.yaml`, `rules.proposal`).

**No forman parte de estas decisiones**, por estar ya fijadas y verificadas: el presupuesto de
revisión de 800 líneas de cambio efectivo (`docs/15-flujo-de-trabajo-git.md` §3, propietario,
2026-09-18), la entrega en cortes encadenados, el modo de ejecución automático con TDD estricto, y la
exclusión del ancla externa como cambio propio atado al cambio 11.
