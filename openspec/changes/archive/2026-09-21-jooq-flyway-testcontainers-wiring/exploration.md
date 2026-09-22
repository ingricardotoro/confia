# Exploración: cableado de jOOQ, Flyway y Testcontainers (F0, cambio 5, parte A)

- **Cambio:** `jooq-flyway-testcontainers-wiring`
- **Fase:** explorar
- **Fecha:** 2026-09-20
- **Estado:** exploración terminada. El propietario dividió el cambio 5 en dos partes el 2026-09-20 y
  resolvió cuatro puntos; ver `proposal.md`, «Resolución del propietario»
- **Fuente:** transcripción literal de la observación de Engram `sdd/change-5/explore` (#435). El
  agente de exploración no escribe archivos del repositorio; la fase de propuesta escribe este
  archivo. El cuerpo se conserva tal como se registró, incluidos sus encabezados y su título
  original, que todavía nombra el cambio 5 sin dividir.

---

## Exploración: change 5 de F0 — `transactional-runner-and-audit-log`

### Estado actual

`apps/api` tiene el reactor Maven (`kernel` + `app`), ArchUnit/Spring Modulith, JaCoCo 80 %/95 %+PIT 80
por `domain`, CI con puerta de mutación en `main`. El módulo `organization` existe solo como dominio en
memoria (`Institution`, `InstitutionId` en `kernel`, puertos `InstitutionRepository` y
`CurrentInstitutionProvider` sin implementación) — confirmado por
`apps/api/app/src/main/java/com/confia/organization/**`. No existe ninguna tabla, ni Flyway, ni jOOQ, ni
Testcontainers, ni `docker-compose.yml`, ni un solo rol de PostgreSQL (`confia_owner`,
`confia_admin_app`, `confia_portal_app`, `confia_readonly`, `confia_backup` de `docs/03-seguridad.md`
§6.1 no existen todavía en ningún artefacto). Docker Desktop está instalado pero detenido en esta
máquina (ya verificado por el orquestador).

### 1. Obligaciones exactas, con archivo y línea

**ADR-0015 (jOOQ)**
- Regla 2 (líneas 146–149): generación de código desde Flyway sobre un PostgreSQL 18 temporal en cada
  construcción; mecanismo concreto (p. ej. `testcontainers-jooq-codegen-maven-plugin`) se valida en F0.
- Regla 4 (152–154): jOOQ vive solo en `infrastructure`.
- Regla 6 (158–160): repositorios de libro mayor y documentos fiscales solo `insert`/lectura, sin
  `update`/`delete` expuestos, respaldado por permisos de PostgreSQL.
- Regla 7 (161–166): **componente transaccional único** en `shared/security`: recibe nivel de
  aislamiento, fija contexto de seguridad como primera sentencia con parámetros vinculados, ejecuta el
  caso de uso, reintenta un número acotado de veces ante error de serialización/interbloqueo.
- Regla 9 (169–170): SQL plano de jOOQ prohibido salvo lista aprobada de reportes.
- Regla 10 (171–172): registro de publicación de Spring Modulith persistido con JDBC, sin JPA.
- Cumplimiento 1–9 (214–236): baneo de dependencias por enforcer (probablemente ya en cambio 1),
  reglas ArchUnit de confinamiento de jOOQ y transacciones, propiedad de tablas por prefijo,
  sincronía esquema/código en build, prueba de contexto+reintento, prueba de inmutabilidad de
  repositorios financieros, script de versión única de PostgreSQL.

**ADR-0009 (multi-institución)**
- Puntos 1–4 (líneas 90–99): `institution_id NOT NULL` desde la primera migración sin excepciones,
  toda restricción única la incluye, todo índice compuesto empieza por ella, RLS desde el inicio.
- Implementación (111–136): `set_config('app.institution_id', $1, true)` local a la transacción;
  `ENABLE ROW LEVEL SECURITY` + `FORCE ROW LEVEL SECURITY` en cada tabla protegida.
- Cumplimiento (165–173): prueba de catálogo que rechaza restricciones únicas sin `institution_id`,
  prueba de RLS activa/forzada, prueba de aislamiento con dos instituciones sembradas.

**ADR-0017 (tablas técnicas y tabla raíz)**
- Catálogo cerrado de exactamente 4 tablas exceptuadas (84–95): `scheduled_tasks`,
  `event_publication`, `flyway_schema_history`, tabla raíz de `organization` — la raíz **solo** está
  exceptuada de la columna `institution_id`, no de RLS (regla 1, 99–101: política sobre su propia
  clave primaria).
- Regla 3 (106–109): tablas técnicas creadas solo por migraciones de Flyway ejecutadas como
  `confia_owner`; creación automática de esquema desactivada.
- Regla 4 (110–113): modo de finalización `DELETE` de Spring Modulith; modo archivo prohibido.
- Regla 6 (118–121): tablas técnicas fuera de la generación de código jOOQ.
- Permisos exactos (126–134): `confia_admin_app` con `SELECT/INSERT/UPDATE/DELETE` en
  `scheduled_tasks` y `event_publication`, solo `SELECT` en `flyway_schema_history`;
  `confia_portal_app` con `INSERT` (+`SELECT` "validado en F0") en `scheduled_tasks`, **ningún**
  privilegio en `event_publication`, `SELECT` en `flyway_schema_history`.
- **Nota crítica de ADR-0016** (líneas 172–180, "Excepción al esquema de ADR-0009"): la tabla
  `scheduled_tasks` debe existir por migración de Flyway aunque el ejecutor de `confia-worker` llegue
  en el cambio 9. **Confirma que crear esa tabla es responsabilidad del cambio 5, no del 9.**

**docs/01-arquitectura.md**
- §3.1 (líneas 63–74): filas de jOOQ y db-scheduler del stack, reafirmando generación de código y
  componente transaccional único.
- §3.2 (82–89): PostgreSQL 18 en los tres entornos, RLS como capa del motor.
- §4 (200–208, 237–240): `shared/audit` y `shared/security` como paquetes de primer nivel; regla de
  ArchUnit que confina jOOQ, transacciones y propiedad de tablas — es el mandato arquitectónico que
  las pruebas de cumplimiento de ADR-0015 deben satisfacer.

**docs/03-seguridad.md**
- §6.1 (línea 622, tabla 627–630): roles exactos y sus privilegios — **ninguno existe todavía**;
  crearlos (al menos vía script de inicialización solo de prueba, ver más abajo) es obligación nueva
  de este cambio, no heredada de ningún cambio anterior.
- §6.2 (635–666): el contexto de sesión lleva **cuatro** parámetros (`app.actor_id`, `app.actor_kind`,
  `app.institution_id`, `app.request_id`), no solo `institution_id` como en el ejemplo más antiguo de
  ADR-0009 (ADR-0009 mismo lo reconoce en su nota de revisión, línea 7: "se unifica con docs/03 §6.2").
  El componente transaccional del cambio 5 debe fijar los cuatro, parametrizados por el llamador (no
  hay `CurrentInstitutionProvider` real todavía).
- §6.3–6.4 (665–739): patrón de política RLS con `FORCE`, tabla de criterios por tabla (línea 711:
  `audit_log` filtra por institución y por **personal con permiso `audit:read`**), y las cuatro
  pruebas de cumplimiento obligatorias por política (integración con dos actores, contexto ausente,
  fuga entre solicitudes del pool, inventario de `relrowsecurity`/`relforcerowsecurity`).
- §12 completo (1299–1433): diseño de `audit_log` — DDL (1307–1332), encadenamiento por hash vía
  disparador `BEFORE INSERT` en PL/pgSQL con JSON canónico (1340–1355), **ancla externa horaria** a
  almacenamiento de objetos con bloqueo de cumplimiento (1357–1360), catálogo de eventos obligatorios
  (1362–1375), permisos y disparadores que rechazan `UPDATE`/`DELETE`/`TRUNCATE` incluso para el
  propietario del esquema (1377–1413), verificación periódica diaria/semanal/horaria (1420–1428) y su
  prueba de cumplimiento exacta (1430–1432, que coincide con el criterio de salida 3 de F0).

**docs/06-estrategia-de-testing.md**
- Línea 68 y 605–613: `*IT.java` con Testcontainers y PostgreSQL 18, generación de jOOQ en
  `generate-sources`, menos de 8 min en CI.
- Línea 609 (ya citada por el orquestador): catálogo cerrado de exactamente cuatro tablas sin
  `institution_id` en las pruebas de trabajos en segundo plano.
- §14.2 (615–667): clase base `PostgresIntegrationTest` con Testcontainers, y **nota explícita**: "los
  roles de aplicación los crea un script de inicialización solo de pruebas, nunca una migración de
  Flyway que lleve una contraseña" (línea 648–649). Esto significa que crear los roles de PostgreSQL
  de `docs/03` §6.1 **no es tarea de Flyway**; es un script de inicialización de prueba para CI/local,
  y la creación en RDS de preproducción/producción es un procedimiento de infraestructura aparte
  (`docs/05`), probablemente todavía sin dueño en la planificación de F0 — ver preguntas abiertas.

### 2. Obligaciones heredadas

**(a) Migración `organization_institution` y adaptador jOOQ.** Confirmado en
`openspec/changes/foundations-plan/exploration.md` (nota 2026-09-19, decisión D1) y en
`openspec/specs/organization/spec.md` §"Fuera de alcance" (líneas 26–30): el cambio 4 entregó
`organization` sin tabla; la migración `organization_institution` (RLS sobre su propia clave primaria,
ADR-0009/ADR-0017; prefijo ADR-0015) y el `InstitutionRepository` jOOQ **son la primera migración del
cambio 5**. Si el cambio 5 se divide, la división debe asignar ambas piezas explícitamente a una parte.
`docs/09-roadmap-y-fases.md` línea 118–122 registra el pendiente heredado exacto.

**(b) Guarda técnica del RTN.** `docs/09` líneas 118–122 y `openspec/specs/organization/spec.md`
líneas 118–125 ya declaran que el límite de 20 dígitos es una guarda técnica, no una regla fiscal,
mientras `docs/04-cumplimiento-fiscal-sar.md` §1 mantenga el formato como pendiente de validación. La
migración/columna del cambio 5 debe repetir esa nota (comentario SQL o especificación del delta) para
no crear una segunda verdad implícita.

**(c) `CurrentInstitutionProvider` sin implementar hasta el cambio 7.** Confirmado por
`openspec/specs/organization/spec.md` líneas 34–36 y `design.md` línea 718. **Nada en el cambio 5 lo
necesita**: el componente transaccional recibe el contexto de seguridad (incluido `institution_id`)
como parámetro explícito del llamador/prueba, no lo resuelve él mismo. Es agnóstico de cómo se produce
ese contexto.

### 3. La bitácora de auditoría (brecha B6): qué es verificable en F0 y qué depende de algo que no existe

**Verificable en el cambio 5, sin depender de trabajo posterior:**
- Tabla, restricciones, índices, disparador de encadenamiento por hash (JSON canónico + SHA-256),
  disparadores que rechazan `UPDATE`/`DELETE`/`TRUNCATE`, permisos de rol (`SELECT, INSERT` para
  `confia_admin_app`; nada para `confia_portal_app`).
- El **verificador de cadena** como caso de uso/función invocable directamente: la prueba exacta que
  pide docs/03 §12.4 (insertar filas, alterar una con `SUPERUSER` en el contenedor de prueba, verificar
  que el verificador la detecta e identifica la fila) **no requiere ningún ejecutor de trabajos en
  segundo plano**. Esto satisface el **criterio de salida 3** de F0 directamente en el cambio 5.

**Depende de algo que este cambio no puede construir todavía:**
- **Los roles de PostgreSQL de docs/03 §6.1 no existen en ningún cambio anterior.** El cambio 5 es el
  primero que toca Flyway/PostgreSQL, así que crear al menos `confia_owner`, `confia_admin_app` y
  `confia_portal_app` (y sus `GRANT`) es una obligación **nueva**, no heredada, y afecta el tamaño del
  cambio (ver punto 6). Por `docs/06` §14.2, la creación es un script de inicialización solo de prueba
  para Testcontainers/CI, no una migración de Flyway; la creación en RDS queda para infraestructura
  (`docs/05`) sin dueño claro todavía en la planificación de F0 — pregunta abierta para el propietario.
- **La política RLS de `audit_log` exige el permiso `audit:read`** (docs/03 línea 711), pero el RBAC
  real (roles, permisos, verbo `read` del módulo `audit`) no existe hasta los cambios 7–8. El nombre
  mismo del cambio 8 (`rbac-permission-matrix-and-audit-integration`) sugiere que la integración
  fina RBAC+auditoría se resuelve ahí. **Recomendación:** el cambio 5 implementa una política RLS
  simple de `audit_log` filtrada solo por `institution_id` (consistente con el resto del esquema) y
  documenta explícitamente que el filtro adicional por `audit:read` se añade en el cambio 8, para no
  inventar un sistema de permisos a medias.
- **El ancla externa horaria a almacenamiento de objetos con bloqueo de cumplimiento** (docs/03
  §12.1, líneas 1357–1360) depende de S3/MinIO (ADR-0014), que no aparece como entregable explícito de
  F0 antes del cambio 11/12 (contenerización, respaldo). No es razonable construirla en el cambio 5 sin
  esa infraestructura. **Se recomienda diferir el ancla externa** (dejarlo documentado como brecha
  conocida en la propuesta) y limitar el cambio 5 a la cadena de hash interna y su verificación, salvo
  que el propietario prefiera adelantar una integración mínima con MinIO local.
- **La programación de las verificaciones diaria/semanal/horaria como tareas recurrentes** (docs/03
  §12.4) es, por ADR-0016, trabajo de `confia-worker`/db-scheduler, cuyo ejecutor llega en el cambio 9.
  El cambio 5 puede declarar el verificador como componente invocable y probarlo directamente; **no
  puede** dejarlo corriendo en producción hasta el cambio 9.

### 4. LA DECISIÓN PRINCIPAL: ¿sigue en pie la división preaprobada?

**Sí, con un ajuste de contenido, no de forma.** La división preaprobada en
`foundations-plan/exploration.md` (línea 48–50) ya es: `jooq-flyway-testcontainers-wiring` +
`audit-log-and-transaction-runner`. Sigue siendo la partición correcta, pero el aterrizaje de la
organización en el cambio 4 (con su migración pendiente) y los hallazgos de este análisis obligan a
precisar qué lleva cada parte:

**Parte A — `jooq-flyway-testcontainers-wiring`** (mecanismo de acceso a datos, sin lógica de negocio
propia):
- Configuración de Flyway y del complemento de generación de jOOQ con Testcontainers.
- Primera migración: creación de roles de PostgreSQL (`confia_owner`, `confia_admin_app`,
  `confia_portal_app`; `confia_readonly` y `confia_backup` pueden diferirse si ningún cambio de F0 los
  usa todavía) — **como script de inicialización solo de prueba**, no como migración de Flyway con
  contraseña, según `docs/06` §14.2.
- Migración `organization_institution` (heredada, D1) con RLS sobre su propia clave primaria y el
  adaptador jOOQ de `InstitutionRepository`.
- Verificación de versión única de PostgreSQL (Compose/Testcontainers/RDS).
- Clase base `PostgresIntegrationTest` de `docs/06` §14.2, con las dos variantes (reversión automática
  y confirmación real con truncamiento selectivo) que exige `foundations-plan` §2.
- **Puede probarse solo:** una consulta contra `organization_institution` que compila porque el
  esquema real existe, y el aislamiento básico institución-a-institución sobre esa única tabla.

**Parte B — `audit-log-and-transaction-runner`** (depende de A):
- Componente transaccional único en `shared/security` (los cuatro parámetros de contexto, aislamiento
  configurable, reintento acotado ante error de serialización/interbloqueo, bloqueo `FOR UPDATE`
  genérico aunque `ledger` no exista aún).
- Migración de `shared_audit_log` (prefijo `<módulo>_<entidad>` ya decidido, tabla en `foundations-plan`
  línea 67) con encadenamiento por hash, disparadores de solo inserción, permisos por rol.
- Caso de uso verificador de cadena y su prueba de manipulación (criterio de salida 3).
- Excepción documentada de ArchUnit para que `application` llame al componente transaccional
  (`foundations-plan` línea 69).
- Reglas de ArchUnit de confinamiento de jOOQ/transacciones/propiedad de tablas (cumplimiento de
  ADR-0015).
- **Puede probarse solo:** cualquier caso de uso de prueba que pase por el componente transaccional
  deja una fila en `shared_audit_log`, y alterar esa fila con `SUPERUSER` la detecta el verificador.

**Explícitamente fuera de ambas partes** (documentar como brecha conocida, no como tarea pendiente
silenciosa): el ancla externa a S3/MinIO, la programación como tarea recurrente de db-scheduler
(cambio 9), y el refinamiento de RLS de `audit_log` por permiso `audit:read` (cambio 8).

### 5. Bloqueo local: Docker detenido

Sin Docker, TDD estricto sobre `*IT.java` no puede correr en esta máquina. Opciones, sin elegir por el
propietario:

1. **El propietario inicia Docker Desktop.** Restaura TDD local estricto de inmediato; costo cero de
   proceso, pero depende de una acción manual antes de cada sesión de trabajo con Testcontainers.
2. **CI como fuente de verdad para `*IT.java`, unitarias en local.** El ciclo rojo-verde-refactor local
   se cumple solo para pruebas unitarias y de arquitectura; las de integración se escriben con TDD
   "en papel" (rojo esperado documentado) y se confirman en la construcción de CI. Riesgo: un ciclo de
   retroalimentación más largo (minutos de CI en vez de segundos locales) y la posibilidad de acumular
   varios cambios antes de descubrir un rojo de integración.
3. **Diferir las pruebas de integración.** Implementar con dobles de prueba y posponer `*IT.java` a un
   cierre posterior. Contradice `docs/06` línea 68 y la definición de terminado de `CLAUDE.md`; no es
   compatible con `strict_tdd` si ya está en `true` desde el cambio 2. **No se recomienda** salvo que
   el propietario acepte explícitamente la excepción.

### 6. Pronóstico de tamaño

- Presupuesto vigente del proyecto: **800 líneas de cambio efectivo por pull request**
  (`docs/15-flujo-de-trabajo-git.md` §3, línea 143; confirmado como decisión P1 del propietario en el
  cambio 4), no las 400 líneas literales de la guardia de sesión — el propio cambio 4 ya documentó y
  seguido esa precedencia.
- Límite de tareas: `openspec/changes/README.md` fija quince tareas por **cambio**; el precedente del
  cambio 4 registra que el propietario aceptó una excepción de "quince tareas por **pull request**"
  cuando el cambio se divide en varios PR encadenados (32 tareas totales repartidas en cinco PR). Si el
  cambio 5 se divide en dos PR (A y B), cabe pedir la misma excepción en vez de recortar alcance.
- **Evidencia de subestimación real:** el cambio 4 pronosticó 232–334 líneas para su PR B1 y terminó
  bloqueado en 933 líneas (más del doble, superando incluso el presupuesto de 800); el cambio 2 superó
  las 800 líneas en su PR 1 completo y tuvo que dividirse en 1a (~646) y 1b (~691) sobre la marcha. Con
  ese historial, un pronóstico de 13–14 tareas para el cambio 5 —que además introduce un mecanismo
  nuevo (jOOQ+Testcontainers+Flyway) sin precedente en el repositorio— tiene un riesgo **alto**, no solo
  medio, de superar 800 líneas en al menos una de sus dos partes. La parte B (auditoría + componente
  transaccional, con el disparador PL/pgSQL de JSON canónico) es la de mayor riesgo de subestimación,
  porque el hashing canónico en PL/pgSQL y las cuatro pruebas de cumplimiento de RLS (§6.4) son trabajo
  nuevo sin plantilla previa en el repositorio.
- Recomendación: forecast explícito por PR en `design.md`/`tasks.md`, con el mismo formato de "Pronóstico
  de tamaño por corte" que usó el cambio 4, y aceptar de entrada una probable subdivisión adicional de
  la parte B en dos PR encadenados si el pronóstico se acerca a 700 líneas antes de contar las pruebas
  de RLS.

### 7. Preguntas abiertas

**Decisiones reales del propietario:**
1. ¿Quién crea los roles de PostgreSQL en RDS (preproducción/producción): el cambio 5, o queda para
   contenerización/infraestructura (cambio 11/12), dado que `docs/06` aclara que Flyway no debe llevar
   contraseñas de rol?
2. ¿Se difiere el ancla externa a S3/MinIO fuera del cambio 5 (recomendado), o el propietario quiere
   una integración mínima con MinIO local ya en este cambio?
3. ¿Cuál de las tres opciones del bloqueo de Docker local prefiere el propietario (punto 5)?
4. ¿Se pide de nuevo la excepción de "quince tareas por pull request" si el cambio 5 se divide, en vez
   de recortar tareas para caber en quince por cambio completo?

**Valores técnicos por defecto (no requieren decisión de negocio, pero se dejan explícitos):**
- Renombrar `audit_log` a `shared_audit_log` en la migración real, siguiendo la convención de prefijo ya
  decidida, y actualizar la referencia de `docs/03` §12 para que no queden dos nombres de tabla en
  conflicto.
- La política RLS de `audit_log`/`shared_audit_log` en el cambio 5 filtra solo por `institution_id`;
  el filtro adicional por `audit:read` se documenta como trabajo del cambio 8.
- Creación de roles vía script de inicialización solo de prueba para Testcontainers/CI (no vía Flyway).

### Riesgos
- Alto riesgo de que la parte B del cambio 5 (auditoría + componente transaccional) supere 800 líneas,
  dado el patrón de subestimación de los cambios 2 y 4 y la novedad del hashing PL/pgSQL.
- El bloqueo de Docker local impide TDD estricto sobre `*IT.java` hasta que el propietario decida una
  de las tres opciones del punto 5.
- Si no se aclara ahora el alcance del ancla externa y del refinamiento RLS por `audit:read`, existe
  riesgo de que la propuesta calle una brecha en vez de documentarla explícitamente (como ya ocurrió y
  se corrigió con la nota del RTN en el cambio 4).

### Listo para propuesta
Sí. La división en dos cambios sigue siendo correcta con el contenido precisado arriba. El propietario
debe resolver las cuatro preguntas de la sección 7 antes o durante `/sdd-propose`, y la propuesta debe
declarar explícitamente el ancla externa y el refinamiento RLS por `audit:read` como brechas diferidas,
no como olvidos.

---

## Nota de la fase de propuesta (2026-09-20)

Dos afirmaciones del cuerpo transcrito quedaron corregidas al verificarlas contra sus fuentes durante
la propuesta; se dejan aquí para que la transcripción no se lea como verdad vigente sin matiz:

1. **Docker local.** El punto 5 («Docker detenido») quedó sin efecto: el propietario confirmó el
   2026-09-20 que Docker 29.7.2 está disponible y `postgres:18-alpine` descargado. Las pruebas de
   integración corren en local con TDD estricto.
2. **`scheduled_tasks`.** El punto 1 afirma que ADR-0016 líneas 172–180 «confirman que crear esa tabla
   es responsabilidad del cambio 5, no del 9». Esas líneas registran la excepción de ADR-0009 para las
   tres tablas técnicas y **no nombran ningún cambio**. En cambio `docs/09-roadmap-y-fases.md` línea
   126 asigna explícitamente «tabla de tareas creada por migración de Flyway» al entregable 12, que
   `foundations-plan` mapea al cambio 9. Ver la decisión pendiente D1 de `proposal.md`.
