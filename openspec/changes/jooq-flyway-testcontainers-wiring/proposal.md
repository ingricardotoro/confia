# Propuesta: cableado de jOOQ, Flyway y Testcontainers

- **Cambio:** `jooq-flyway-testcontainers-wiring`
- **Fase del roadmap:** F0, cambio 5 de 13, **parte A de dos** (`foundations-plan`, líneas 48–50; el
  propietario confirmó la división el 2026-09-20). La parte B es `audit-log-and-transaction-runner`
- **Exploración:** `openspec/changes/jooq-flyway-testcontainers-wiring/exploration.md`
- **Rama:** `change/jooq-flyway-testcontainers-wiring` (desde `main` 6963807)
- **Estado:** aprobada por el propietario el 2026-09-20 (D5, `openspec/config.yaml`,
  `rules.proposal`). D1 a D5 resueltas.
  Cuatro decisiones ya resueltas el 2026-09-20; ver «Resolución del propietario». Quedan D1 a D4

## Intención

Hoy el backend no toca la base de datos. No existe Flyway, ni jOOQ, ni Testcontainers, ni una sola
tabla, ni un solo rol de PostgreSQL de `docs/03-seguridad.md` §6.1 — verificado leyendo
`apps/api/pom.xml`, `apps/api/app/pom.xml` y el árbol de `apps/api/app/src/main/java/com/confia/**`.
El módulo `organization` que entregó el cambio 4 vive entero en memoria: `InstitutionRepository` es
un puerto sin adaptador, y su propio javadoc dice «its real implementation is change 5's
responsibility».

Eso deja tres compromisos abiertos que solo se cierran con el primer contacto real con PostgreSQL:

1. **La primera migración del sistema.** ADR-0009, punto 1, exige `institution_id NOT NULL` «desde la
   primera migración». Mientras no exista ninguna migración, esa exigencia no es verificable por
   nadie: no hay catálogo que consultar, ni política de fila que probar, ni aislamiento que
   demostrar. La decisión D1 del cambio 4 asignó esa primera migración —`organization_institution`—
   al cambio 5.
2. **El mecanismo de acceso a datos de ADR-0015.** La regla 2 exige generar el código de jOOQ desde
   las migraciones sobre un PostgreSQL 18 temporal en cada construcción, y dice explícitamente que
   «el mecanismo concreto se valida en F0». Ninguna consulta del sistema puede escribirse antes de
   que ese mecanismo exista, porque es lo que hace que un cambio de esquema rompa la compilación.
3. **El rojo programado de ADR-0020.** `LayeredArchitectureTest` declara hoy
   `.optionalLayer("Infrastructure")` con una caducidad ejecutable en
   `EmptyShouldExceptionInventoryTest` cuya condición es literal: «no production class resides in an
   infrastructure package yet (change 5 adds the first jOOQ adapter)». La primera clase de
   `infrastructure` rompe la construcción a propósito, y este cambio es el que la crea. ADR-0020,
   «Consecuencias», lo anticipa y pide preverlo en el `tasks.md` del cambio 5.

Este cambio es deliberadamente **mecanismo, no negocio**: levanta la tubería de datos y la demuestra
sobre la única tabla que ya tiene dominio, especificación y dueño. La lógica transversal que se
apoya en esa tubería —componente transaccional único y bitácora de auditoría— es la parte B, que no
puede empezar antes.

## Alcance

### Dentro de alcance

1. **Cableado de construcción.** Dependencias y versiones de Flyway, jOOQ de código abierto,
   controlador JDBC de PostgreSQL y Testcontainers en `apps/api`, con la generación de código de jOOQ
   en la fase `generate-sources` sobre un PostgreSQL 18 temporal alimentado por las migraciones
   (ADR-0015, regla 2; `docs/06` línea 605). El código generado **no se comprometa al repositorio** y
   queda excluido de JaCoCo, de PIT y del conteo de líneas de autor.
2. **Script de inicialización solo de prueba** que crea los cinco roles de `docs/03` §6.1
   (`confia_owner`, `confia_admin_app`, `confia_portal_app`, `confia_readonly`, `confia_backup`),
   ninguno `SUPERUSER` y ninguno con `BYPASSRLS`. Nunca una migración de Flyway con contraseña
   (`docs/06` §14.2, líneas 648–649). En el contenedor de prueba, `confia_owner` es ya el usuario del
   propio contenedor; el script crea los otros cuatro y sus contraseñas de prueba.
3. **Primera migración de Flyway: `organization_institution`.** Clave primaria `id`, que **es** el
   identificador de institución (ADR-0017, catálogo cerrado: la tabla raíz está exceptuada solo de la
   columna `institution_id`, no del aislamiento). Columnas de los atributos que ya fija
   `openspec/specs/organization/spec.md`, con `ENABLE ROW LEVEL SECURITY` + `FORCE ROW LEVEL
   SECURITY` y política `id = current_setting('app.institution_id', true)::uuid` (ADR-0017, regla 1;
   ADR-0009, «Implementación del aislamiento»; docs/03 §6.3). Restricciones de unicidad e índices
   según los fije el delta de especificación; la única que esta propuesta compromete es la clave
   primaria. `GRANT` de los privilegios de `docs/03` §6.1 para las tablas que existan en este cambio.
4. **Nota de la guarda técnica del RTN.** La longitud de la columna del RTN hereda la guarda técnica
   de 1 a 20 dígitos del dominio y **no es una regla fiscal**; se declara como comentario de la
   columna y en el delta de especificación, mientras `docs/04-cumplimiento-fiscal-sar.md` §1 siga
   marcando el formato como pendiente de validación (`docs/09` líneas 118–122).
5. **Base de pruebas de integración.** Clase `PostgresIntegrationTest` de `docs/06` §14.2 con
   `postgres:18-alpine`, un contenedor por JVM de prueba, Flyway migrando como propietario y la
   aplicación conectando con su rol de mínimo privilegio. Dos variantes, como exige `foundations-plan`
   §2: una con reversión automática (`@Transactional` en la prueba) y otra con confirmación real y
   truncamiento selectivo. Convención `*IT.java` con Failsafe, que el cambio 1 ya dejó cableado sobre
   una suite vacía (`apps/api/pom.xml`, línea 222).
6. **Adaptador jOOQ de `InstitutionRepository`** en `com.confia.organization.infrastructure`, que
   implementa el puerto existente `findById` (devuelve `Optional` vacío ante ausencia, nunca
   excepción) y convierte filas en objetos de dominio de forma explícita. Ninguna clase generada ni
   ningún tipo de `org.jooq` sale de `infrastructure` (ADR-0015, regla 4).
7. **Pruebas de integración de la base multi-institución**, que son la primera verificación real de
   ADR-0009 «Cumplimiento»: ida y vuelta del adaptador contra el esquema real; inventario de
   `relrowsecurity` y `relforcerowsecurity`; aislamiento efectivo con dos instituciones sembradas;
   denegación cuando el contexto de sesión está ausente (una política que recibe `NULL` deniega,
   docs/03 §6.2 regla 4); verificación de catálogo de que toda restricción única de una tabla de
   negocio incluye el discriminador de institución; y matriz de privilegios por rol sobre las tablas
   existentes.
8. **Retirada del `optionalLayer("Infrastructure")` de ADR-0020** en `LayeredArchitectureTest`, su
   entrada en `EmptyShouldExceptionInventoryTest` y el conteo correspondiente en
   `SuppressionCitesAdrTest`, en el mismo commit que añade la primera clase de `infrastructure`
   (ADR-0020, alcance punto 3). `optionalLayer("Web")` permanece con su condición intacta.
9. **Reglas de ArchUnit del cumplimiento de ADR-0015 que ya tienen sujeto**, cada una con su fixture
   negativo permanente: jOOQ confinado a `infrastructure` (cumplimiento 2), propiedad de tablas por
   prefijo de módulo (cumplimiento 4), prohibición del SQL plano de jOOQ fuera de la lista aprobada
   (cumplimiento 5) y transacciones solo dentro del componente de `shared/security` (cumplimiento 3),
   que aquí actúa como guarda preventiva: impide que el primer adaptador abra su propia transacción
   antes de que exista el componente de la parte B. La sincronía esquema/código (cumplimiento 6) no
   es una regla sino una consecuencia del punto 1.
10. **Fuente única de la versión de PostgreSQL** en la construcción (cumplimiento 9 de ADR-0015, en su
    forma disponible hoy): una sola declaración de la imagen y versión que consumen el complemento de
    generación y `PostgresIntegrationTest`. La comparación de tres vías con Docker Compose y con la
    configuración de RDS solo es posible cuando existan, en los cambios 11 y 12; se declara ahí.
11. **Actualización documental**: `openspec/config.yaml` si el cableado cambia el comando o los
    umbrales; nota fechada en `openspec/changes/foundations-plan/exploration.md` con la división del
    cambio 5 (ya escrita en esta fase); y el estado de caducidad de ADR-0020 para `Infrastructure`.

### Fuera de alcance

Se listan para que no se confundan con un olvido. Los cinco primeros son la parte B o posterior; los
tres últimos son brechas conocidas ya documentadas, no tareas silenciosas.

- **Componente transaccional único de `shared/security`** (ADR-0015, regla 7; los cuatro parámetros
  de contexto de `docs/03` §6.2, aislamiento configurable, reintento acotado, `SELECT ... FOR
  UPDATE`): parte B. En este cambio, el contexto de sesión lo fija la propia prueba de integración
  con parámetros vinculados, y solo `app.institution_id`, que es el único que la política de la tabla
  raíz consulta.
- **Bitácora de auditoría `shared_audit_log`** con su encadenamiento por hash, sus disparadores que
  rechazan `UPDATE`/`DELETE`/`TRUNCATE` y sus permisos: parte B. El nombre `shared_audit_log` resuelve
  el `audit_log` literal de `docs/03` §12 según la convención de prefijo de `foundations-plan` §2; se
  menciona aquí solo como contexto.
- **Verificador de cadena de auditoría** y su prueba de manipulación (criterio de salida 3 de F0):
  parte B.
- **Ancla externa horaria a almacenamiento de objetos** con bloqueo de cumplimiento (`docs/03` §12.1):
  depende de S3/MinIO (ADR-0014), que no existe antes de los cambios 11 y 12. **Brecha conocida y
  declarada**, no olvido.
- **Refinamiento de la política RLS de la bitácora por el permiso `audit:read`** (`docs/03` línea
  711): el RBAC real no existe hasta los cambios 7 y 8. **Brecha conocida y declarada.**
- **Programación de las verificaciones periódicas como tareas recurrentes de db-scheduler**
  (`docs/03` §12.4): por ADR-0016 es trabajo de `confia-worker`, cambio 9. **Brecha conocida y
  declarada.**
- **Tabla `scheduled_tasks`**: ver la decisión pendiente D1. La recomendación de esta propuesta es que
  pertenece al cambio 9 junto con el resto del entregable 12, no a la parte A ni a la parte B.
- **Tabla `event_publication` de Spring Modulith** (ADR-0017, regla 3): no hay ningún evento de
  dominio publicado todavía. La crea el cambio que publique el primero. La creación automática de
  esquema queda desactivada cuando esa biblioteca entre, no antes.
- **Adaptador de `CurrentInstitutionProvider`**: cambio 7. Nada aquí lo necesita.
- **Prueba de inmutabilidad de los repositorios financieros** (ADR-0015, cumplimiento 8): no existe
  ningún repositorio de libro mayor ni de documentos fiscales hasta F3. Se difiere a F3 con nombre y
  motivo, no se escribe una regla contra un conjunto vacío.
- **Docker Compose, RDS y cualquier despliegue**: cambios 11 y 12. La base de datos de este cambio
  vive solo en contenedores efímeros de prueba.

## Capacidades

`openspec/project.md` no lista ninguna capacidad de persistencia o de acceso a datos: su tabla de
capacidades es de negocio, y `build-integrity` es la única capacidad técnica existente. Además,
project.md establece el precedente de que las fundaciones transversales de F0 (bitácora de auditoría,
idempotencia) «se construyen en F0 como fundación transversal, **no como capacidad propia**». Esta
propuesta sigue ese precedente y **no introduce ninguna capacidad nueva**. Si el propietario prefiere
una capacidad `persistence` o `data-access`, es una decisión suya y exige añadirla a project.md antes
de la fase de especificación (decisión pendiente D4).

### Nuevas

Ninguna.

### Modificadas

- **`build-integrity`**: delta con `## ADDED Requirements` (encabezados `### Requisito:` en español,
  como la especificación canónica), por los mecanismos nuevos que deben romper `./mvnw verify`:
  - generación del código de jOOQ desde las migraciones en cada construcción, de modo que una
    consulta incompatible con el esquema no compile (ADR-0015, regla 2 y cumplimiento 6);
  - jOOQ y el paquete generado confinados a `infrastructure` (cumplimiento 2);
  - propiedad de tablas: un módulo no usa clases generadas de un prefijo ajeno (cumplimiento 4);
  - SQL plano de jOOQ prohibido fuera de la lista aprobada (cumplimiento 5);
  - transacciones solo dentro del componente de `shared/security` (cumplimiento 3);
  - verificación de esquema: toda tabla de negocio lleva el discriminador de institución salvo las del
    catálogo cerrado de ADR-0017, toda restricción única lo incluye, y la seguridad a nivel de fila
    está habilitada y forzada en cada tabla protegida (ADR-0009, «Cumplimiento»);
  - fuente única de la versión de PostgreSQL en la construcción (cumplimiento 9, en su alcance
    disponible hoy);
  - pruebas `*IT.java` con Testcontainers ejecutadas por Failsafe, dentro del presupuesto de 8 minutos
    de `docs/06` línea 68.
  Se **modifica** además la nota de caducidad de ADR-0020 para la capa `Infrastructure`, que vence con
  este cambio. `optionalLayer("Web")` no se toca.
- **`organization`**: delta con `## ADDED Requirements` y una modificación de su sección «Fuera de
  alcance», cuyo primer punto («Persistencia real … esa tabla y su repositorio son responsabilidad de
  la primera migración del cambio 5») deja de ser cierto con este cambio:
  - persistencia de `Institution` en `organization_institution`, con la clave primaria como
    identificador de institución y aislamiento por fila sobre ella;
  - contrato observable del adaptador de `InstitutionRepository` contra la base real: ida y vuelta
    fiel de cada atributo y ausencia de resultado sin excepción;
  - la columna del RTN como guarda técnica de 1 a 20 dígitos, no como regla fiscal.

## Enfoque

De afuera hacia adentro, porque aquí lo caro es el mecanismo y no el dominio, que ya existe y está
probado. Cada paso deja la construcción en verde antes del siguiente.

1. **Contenedor antes que esquema.** Cablear Testcontainers, Flyway y el script de roles, y demostrar
   con la prueba de integración más pequeña posible que el contenedor arranca, que Flyway corre como
   propietario y que la aplicación se conecta con `confia_admin_app`. Sin esto no hay rojo honesto
   para nada de lo que sigue.
2. **Generación de código antes que consulta.** Añadir el complemento de generación de jOOQ y
   comprobar de verdad la propiedad que justifica todo el mecanismo: cambiar el esquema rompe la
   compilación de una consulta. Se comprueba introduciendo la ruptura a propósito, como hizo el cambio
   1 con las reglas de ArchUnit. Aquí también se confirma que el código generado queda fuera de JaCoCo
   y de PIT, antes de que la puerta global del 80 % lo note.
3. **Migración y aislamiento.** Escribir `organization_institution` con su política de fila y sus
   `GRANT`, en rojo-verde: primero la prueba de inventario de RLS y la de aislamiento con dos
   instituciones, después la migración.
4. **Rojo programado de ADR-0020.** La primera clase en `com.confia.organization.infrastructure`
   rompe el inventario de caducidad a propósito. Se retira `optionalLayer("Infrastructure")`, su
   entrada y el conteo del escáner **en el mismo commit**, como un ciclo separado, porque el
   invariante de conteo de `SuppressionCitesAdrTest` es frágil y el cambio 4 ya pagó ese costo.
5. **Adaptador jOOQ** contra el esquema real, con TDD estricto sobre `*IT.java` (Docker local
   disponible).
6. **Reglas de arquitectura del cumplimiento de ADR-0015**, cada una naciendo en rojo contra su
   fixture antes de su mitad de producción, en un ciclo aparte del anterior.
7. **Documentación** y pronóstico real de líneas al cerrar cada corte.

### Decisiones técnicas por defecto (con justificación)

1. **Los `GRANT` viajan en la migración de Flyway; los `CREATE ROLE` no.** `docs/06` §14.2 prohíbe que
   una migración lleve una contraseña, no que otorgue privilegios, y un privilegio sobre una tabla es
   parte inseparable del esquema de esa tabla. Consecuencia que esta propuesta declara para los
   cambios 11 y 12: **los roles deben existir en RDS antes de la primera migración**, o esa migración
   falla. La creación de roles en preproducción y producción queda con la infraestructura.
2. **El código generado de jOOQ se excluye de JaCoCo, de PIT y del conteo de líneas de autor.** Sin
   esa exclusión, la puerta global del 80 % sobre `app` que fijó el cambio 4 se rompería por código
   que nadie escribió. La alternativa —comprometer el código generado al repositorio— la prohíbe
   ADR-0015, regla 2.
3. **La política de la tabla raíz consulta solo `app.institution_id`.** Los otros tres parámetros de
   `docs/03` §6.2 (`app.actor_id`, `app.actor_kind`, `app.request_id`) los fija el componente
   transaccional de la parte B y los consumen políticas que todavía no existen. Escribirlos ahora sin
   un componente que los establezca produciría una política que deniega siempre.
4. **La verificación de catálogo trata la clave primaria de la tabla raíz como su discriminador de
   institución.** ADR-0017 la exceptúa solo de la columna, no del aislamiento; la prueba debe
   reconocerlo explícitamente en vez de añadir la tabla a una lista de exclusión, que la sacaría del
   control.
5. **El catálogo cerrado se verifica como pertenencia, no como presencia.** `docs/06` línea 609 fija
   «exactamente cuatro tablas sin `institution_id`», pero en este cambio solo existen dos de ellas
   (`flyway_schema_history` y la tabla raíz). La prueba afirma que **toda** tabla sin el discriminador
   pertenece al catálogo cerrado de cuatro nombres, no que las cuatro existan; así es correcta hoy y
   sigue siéndolo en los cambios 9 y siguientes, sin relajarse.
6. **Un contenedor por JVM de prueba, con `fsync=off` y datos en `tmpfs`**, como muestra `docs/06`
   §14.2: es lo que mantiene la suite dentro del presupuesto de 8 minutos.

## Áreas afectadas

| Área | Impacto | Descripción |
|---|---|---|
| `apps/api/pom.xml` | Modificada | Versiones de Flyway, jOOQ, controlador JDBC y Testcontainers; complemento de generación de código; exclusiones de JaCoCo y PIT |
| `apps/api/app/pom.xml` | Modificada | Dependencias de persistencia y de prueba; generación en `generate-sources`; activación real de Failsafe |
| `apps/api/app/src/main/resources/db/migration/` | Nueva | Primera migración de Flyway: `organization_institution`, RLS y `GRANT` |
| `apps/api/app/src/main/resources/application*.yml` | Nueva o modificada | Fuente de datos, Flyway limitado al perfil de migración (`docs/05` §9) |
| `apps/api/app/src/main/java/com/confia/organization/infrastructure/` | Nueva | Adaptador jOOQ de `InstitutionRepository` y su conversión de filas |
| `apps/api/app/src/test/resources/` (script de roles) | Nueva | Inicialización solo de prueba de los cinco roles de `docs/03` §6.1 |
| `apps/api/app/src/test/java/com/confia/support/PostgresIntegrationTest.java` | Nueva | Base de Testcontainers, en sus dos variantes |
| `apps/api/app/src/test/java/com/confia/organization/infrastructure/**IT.java` | Nueva | Ida y vuelta del adaptador, aislamiento y privilegios |
| `apps/api/app/src/test/java/com/confia/schema/**IT.java` | Nueva | Catálogo: discriminador de institución, restricciones únicas, RLS habilitada y forzada |
| `apps/api/app/src/test/java/com/confia/architecture/LayeredArchitectureTest.java` | Modificada | Se retira `optionalLayer("Infrastructure")` |
| `apps/api/app/src/test/java/com/confia/architecture/EmptyShouldExceptionInventoryTest.java` | Modificada | Se retira la entrada vencida de `Infrastructure` |
| `apps/api/app/src/test/java/com/confia/architecture/SuppressionCitesAdrTest.java` | Modificada | El conteo de `optionalLayer(` baja a uno |
| `apps/api/app/src/test/java/com/confia/architecture/` (reglas y fixtures nuevos) | Nueva | Cumplimiento 2, 3, 4 y 5 de ADR-0015 |
| `.github/workflows/ci.yml` | Modificada | Docker disponible para la generación de código y para `*IT.java`; presupuesto de tiempo |
| `openspec/config.yaml` | Posible | Solo si cambia el comando o algún umbral |
| `openspec/changes/foundations-plan/exploration.md` | Modificada | Nota fechada de la división del cambio 5 (ya escrita) |
| `openspec/specs/build-integrity/spec.md` | Delta al archivar | Requisitos de acceso a datos y de esquema |
| `openspec/specs/organization/spec.md` | Delta al archivar | Persistencia real de `Institution` |

## Tamaño estimado y presupuesto de revisión

Pronóstico de líneas de autor (adiciones más eliminaciones, sin contar el código generado de jOOQ ni
los artefactos de OpenSpec), **ajustado al alza a propósito**: el cambio 2 rebasó 800 líneas en su
primer PR y hubo que partirlo sobre la marcha, y el cambio 4 pronosticó 232 a 334 líneas para su PR B1
y cerró en 933. El factor observado va de 1,5 a casi 3, y este cambio introduce un mecanismo sin
precedente en el repositorio.

| Bloque | Estimación |
|---|---|
| Cableado de construcción (POM padre y de `app`, complemento de generación, exclusiones) | 120 a 220 |
| Script de roles solo de prueba y su cableado | 60 a 120 |
| Migración `organization_institution`, RLS y `GRANT` | 80 a 150 |
| `PostgresIntegrationTest` en sus dos variantes | 120 a 220 |
| Adaptador jOOQ y conversión de filas | 100 a 180 |
| Pruebas de integración (ida y vuelta, aislamiento, contexto ausente, catálogo, privilegios) | 350 a 600 |
| Reglas de ArchUnit de ADR-0015, fixtures, inventario y escáner, y retirada de ADR-0020 | 220 a 380 |
| Fuente única de versión e integración continua | 60 a 140 |
| Documentación (`config.yaml`, notas de ADR y de roadmap) | 40 a 90 |
| **Total de código** | **1 150 a 2 100** |
| Artefactos de OpenSpec | 700 a 1 100 adicionales |

**El cambio supera el presupuesto de 800 líneas por pull request** (`docs/15-flujo-de-trabajo-git.md`
§3) incluso en el extremo bajo, y casi triplica en el alto. La estrategia de entrega de la sesión es
`single-pr`, de modo que hace falta la decisión D2 antes de aplicar. Esta propuesta **no elige**.

**Tareas:** entre 13 y 16. Toca o supera el límite de quince de `openspec/changes/README.md`, que se
mide por cambio; el cambio 4 sentó el precedente de pedir la excepción de «quince por pull request»
cuando el cambio se entrega encadenado. Forma parte de D2.

**Puntos de corte naturales, sin decidir.** Cada uno compila, pasa sus pruebas y tiene sentido por sí
solo, en el orden que `docs/15` §3 recomienda para una capacidad nueva:

- **Corte A1 — contenedor y generación de código (unas 350 a 620 líneas).** Cableado de construcción,
  script de roles, `PostgresIntegrationTest`, complemento de generación y la prueba mínima de que la
  tubería funciona. Autónomo: no depende de ninguna tabla de negocio.
- **Corte A2 — primera migración y adaptador (unas 500 a 950 líneas).** Migración, RLS, `GRANT`,
  adaptador jOOQ, pruebas de ida y vuelta y de aislamiento, y la retirada del `optionalLayer` de
  ADR-0020, que es inseparable de la primera clase de `infrastructure`. En el extremo alto todavía
  supera 800: se subdividiría separando las pruebas de aislamiento y de privilegios del adaptador.
- **Corte A3 — reglas y puertas de esquema (unas 330 a 620 líneas).** Reglas de ArchUnit de ADR-0015
  con sus fixtures, pruebas de catálogo de esquema y fuente única de versión.

## Riesgos

| Riesgo | Probabilidad | Mitigación |
|---|---|---|
| El pronóstico supera 800 líneas por pull request, con historial de subestimación de 1,5× a 3× | Alta | Decisión D2 antes de aplicar; medir el diff real al cerrar cada corte y detener la aplicación si lo rebasa, como hizo el cambio 4 |
| Primer contacto del proyecto con la base de datos: migraciones, RLS, roles y generación de código en la construcción, todo sin precedente en el repositorio | Alta | Orden de afuera hacia adentro (enfoque, pasos 1 y 2): la tubería se demuestra con la prueba más pequeña posible antes de que exista una tabla de negocio |
| El código generado de jOOQ hunde la cobertura global del 80 % sobre `app` que fijó el cambio 4 | Alta | Decisión técnica 2: exclusión de JaCoCo y de PIT verificada en el paso 2, antes de escribir el adaptador |
| `./mvnw verify` pasa a exigir Docker incluso para las pruebas unitarias, porque la generación de código corre en `generate-sources` | Alta | Es una consecuencia aceptada de ADR-0015, regla 2 («la construcción necesita Docker»), no un defecto. Se declara en `openspec/config.yaml` y en el README de `apps/api` para que nadie lo descubra en un rojo |
| La suite de integración rebasa los 8 minutos de `docs/06` línea 68 en integración continua | Media | Un contenedor por JVM, `fsync=off`, datos en `tmpfs` y `*IT.java` solo donde la base real aporta; medir el tiempo en el primer corte y reportarlo, no estimarlo |
| Incompatibilidad entre jOOQ de código abierto, PostgreSQL 18, Java 25 y Spring Boot 4.1, o del complemento de generación con el JDK 25 | Media | El paso 1 del enfoque es una prueba de humo del mecanismo antes de construir nada encima, igual que el cambio 1 hizo con ArchUnit. Si el complemento previsto no soporta el JDK 25, la alternativa se decide en diseño y, si cambia el mecanismo de ADR-0015 regla 2, se eleva a ADR |
| Especificidades de Docker en Windows: `tmpfs` sobre WSL2, finales de línea del script de inicialización montado y arranque lento del primer contenedor | Media | Docker 29.7.2 disponible y `postgres:18-alpine` ya descargado; forzar finales de línea LF en el script por `.gitattributes`; la integración continua sobre Linux es la fuente de verdad ante divergencia |
| Rojo programado de ADR-0020 y desbalance del conteo de `SuppressionCitesAdrTest` al retirar `optionalLayer("Infrastructure")` | Media | Ciclo separado (paso 4), retirada de la llamada, de su entrada y del conteo en el mismo commit, con `./mvnw verify` en verde al cerrar |
| La regla de capas o la verificación de Spring Modulith reaccionan de forma inesperada a la capa `infrastructure` recién poblada, o al paquete generado de jOOQ | Media | El paquete generado vive fuera de `com.confia` o se excluye explícitamente del escaneo de ArchUnit y de Modulith; se confirma en diseño antes de escribir el adaptador |
| Una política RLS que recibe cadena vacía en vez de `NULL` falla al convertir a `uuid` y produce error en vez de denegar | Media | Prueba explícita de contexto ausente y de contexto vacío (docs/03 §6.2 regla 4: la política debe denegar, no romper); el manejo exacto se fija en diseño |
| `GRANT` en la migración contra roles inexistentes rompe el despliegue en RDS | Media | Decisión técnica 1, con el prerrequisito declarado para los cambios 11 y 12 en esta propuesta y en el delta de especificación |
| Una migración aplicada se edita después | Baja hoy, alta más adelante | Hoy no hay entorno desplegado y la base solo vive en contenedores efímeros. La regla de `CLAUDE.md` («nunca se editan una vez aplicadas en producción») entra en vigor con el cambio 11 |

## Plan de reversión

**No existe ningún entorno desplegado ni dato real.** No hay Docker Compose, ni RDS, ni proceso de
migración en ejecución: la base de datos de este cambio vive exclusivamente en contenedores de prueba
efímeros, que se recrean desde cero en cada construcción. Eso hace la reversión barata y limpia:

1. **Revertir el commit de fusión en `main`** (o cerrar el pull request). Eso retira a la vez la
   migración, el adaptador, el cableado de construcción, las reglas nuevas y el script de roles.
2. Al desaparecer la clase de `infrastructure`, la condición de caducidad de ADR-0020 vuelve a
   cumplirse, así que `optionalLayer("Infrastructure")` y su entrada de inventario se restauran con el
   mismo revert y la construcción queda en verde. Si se revierte por cortes, el corte que retiró el
   `optionalLayer` y el que añadió la primera clase de `infrastructure` deben revertirse juntos: son
   el mismo invariante.
3. No hay dato que migrar hacia atrás ni migración de reverso que escribir. El siguiente
   `./mvnw verify` levanta un contenedor nuevo y aplica lo que quede en `db/migration`.
4. Una puerta que bloquee por error **no se desactiva con una bandera** (ADR-0008): se revierte el
   commit que la introdujo o se corrige con un ADR.
5. Si el mecanismo de generación de código resulta inviable con el stack fijado, la reversión parcial
   es retirar el complemento y detenerse: **no** se comprometa código generado al repositorio como
   salida de emergencia, porque ADR-0015 regla 2 lo prohíbe; se eleva un ADR.

**Punto de no retorno práctico:** el cambio 11, cuando exista el primer entorno desplegado. Desde
entonces una migración aplicada no se edita, se compensa con otra. Antes de eso, el costo de revertir
es un `git revert`.

## Dependencias

- Cambios 1, 2 y 4, archivados y fusionados: reactor Maven, `maven-enforcer-plugin` con las
  dependencias prohibidas de ADR-0015 y ADR-0016, Failsafe ya cableado sobre una suite vacía, reglas
  de ArchUnit, inventario de ADR-0018 y ADR-0020, `Money`, `InstitutionId`, el agregado `Institution`
  y el puerto `InstitutionRepository`.
- JDK 25 en local y en integración continua.
- **Docker en la construcción**, no solo en las pruebas: la generación de código de jOOQ lo exige
  (ADR-0015, regla 2). Verificado en local el 2026-09-20: Docker 29.7.2 con `postgres:18-alpine` ya
  descargado. La integración continua debe ofrecer el mismo motor de contenedores.
- La parte B (`audit-log-and-transaction-runner`) depende de este cambio y no puede empezar antes.

## Criterios de éxito

- [ ] `./mvnw verify` en `apps/api` termina en verde con JDK 25 y Docker, en local y en integración
      continua.
- [ ] Un cambio en una migración que rompe una consulta del adaptador **impide la compilación**, no
      falla en tiempo de ejecución (ADR-0015, cumplimiento 6).
- [ ] El código generado de jOOQ no está en el repositorio y no participa en las métricas de cobertura
      ni de mutación.
- [ ] `organization_institution` existe con `ENABLE` y `FORCE ROW LEVEL SECURITY`, y una prueba de
      inventario sobre `relrowsecurity` y `relforcerowsecurity` lo demuestra.
- [ ] Con dos instituciones sembradas, una sesión con el contexto de la primera **no puede leer** la
      fila de la segunda.
- [ ] Sin contexto de sesión, o con contexto vacío, la política **deniega** en vez de fallar.
- [ ] Toda restricción única de una tabla de negocio incluye el discriminador de institución, y toda
      tabla sin ese discriminador pertenece al catálogo cerrado de cuatro nombres de ADR-0017.
- [ ] El adaptador jOOQ devuelve la `Institution` almacenada con cada atributo fiel, y un `Optional`
      vacío para un identificador desconocido, sin lanzar excepción.
- [ ] La aplicación se conecta con `confia_admin_app` y ningún rol es `SUPERUSER` ni tiene
      `BYPASSRLS`; una prueba de privilegios lo verifica.
- [ ] Un tipo de `org.jooq` o del paquete generado fuera de `infrastructure` rompe la construcción, y
      cada regla nueva de ADR-0015 rechaza su fixture negativo nombrando la clase infractora.
- [ ] `optionalLayer("Infrastructure")` ya no existe, su entrada de inventario tampoco, y el conteo de
      `SuppressionCitesAdrTest` cuadra con la única entrada restante (`Web`).
- [ ] La suite de `*IT.java` termina por debajo de 8 minutos en integración continua, **con el tiempo
      medido y reportado**, no estimado.
- [ ] La columna del RTN declara en un comentario que su límite es una guarda técnica y no una regla
      fiscal.

## Resolución del propietario (2026-09-20)

Decisiones ya tomadas. Se registran aquí y no se vuelven a preguntar.

- **D5 (aprobación): aprobada**, con los tres valores por defecto que la acompañaban.
- **D2 (forma de entrega): pull requests encadenados.** Los cortes A1 (contenedor y generación de
  código), A2 (migración, adaptador y retirada de la excepción de ADR-0020 para `infrastructure`) y
  A3 (reglas de ArchUnit y verificaciones de esquema) se entregan como pull requests encadenados
  según `docs/15-flujo-de-trabajo-git.md` §3: cada uno apunta al anterior y se fusionan en orden
  (`delivery_strategy: auto-chain`, `chain_strategy: stacked-to-main`). Al cerrar cada uno se mide
  el diff real; si supera 800 líneas, la aplicación se detiene y decide el propietario. El motivo de
  no usar una excepción de tamaño es que este cambio introduce mecanismos sin precedente en el
  repositorio (migraciones, roles, generación de código), justo donde una revisión grande falla.
- **D1 (`scheduled_tasks`): del cambio 9.** `docs/09-roadmap-y-fases.md` línea 126 la incluye en el
  entregable 12 y ADR-0016 no nombra ningún cambio, así que la tabla se crea con su ejecutor, no
  antes ni sin consumidor.
- **D3 (paquete generado de jOOQ): fuera de `com.confia`.** Así ninguna regla de ArchUnit ni de
  Spring Modulith necesita una excepción por caso, y el coste de moverlo después sería alto porque
  aparecería en los imports de todos los repositorios del sistema.
- **D4 (capacidad nueva): ninguna.** `openspec/project.md` establece que las fundaciones
  transversales de F0 no son capacidades propias; este cambio modifica `build-integrity` y
  `organization`.

- **División del cambio 5: confirmada, parte A primero.** El cambio 5 se ejecuta como dos cambios SDD
  secuenciales: `jooq-flyway-testcontainers-wiring` (este) y después
  `audit-log-and-transaction-runner`. La migración de `organization_institution` y su repositorio
  jOOQ, que la decisión D1 del cambio 4 asignó al cambio 5, quedan asignados a la **parte A**. Nota
  fechada escrita en `openspec/changes/foundations-plan/exploration.md`.
- **Roles de PostgreSQL: script de inicialización solo de prueba en la parte A.** Los cinco roles de
  `docs/03` §6.1 se crean con un script solo de prueba para Testcontainers y para integración
  continua, **nunca con una migración de Flyway que lleve una contraseña** (`docs/06` líneas 648–649).
  El aprovisionamiento en RDS de preproducción y producción queda con los cambios de infraestructura
  11 y 12.
- **Nombre de la tabla de auditoría: `shared_audit_log`.** Sigue la convención de prefijo
  `<módulo>_<entidad>` de ADR-0015 con `shared` como módulo válido, y resuelve el `audit_log` literal
  de `docs/03` §12. **La tabla pertenece a la parte B**; aquí se menciona solo como contexto, y
  `docs/03` §12 se actualiza cuando esa parte la cree.
- **Docker disponible en local.** Docker 29.7.2 con `postgres:18-alpine` ya descargado, de modo que
  las pruebas de integración corren en local bajo TDD estricto. Queda sin efecto la pregunta 3 de la
  exploración.

## Decisiones que confirma el propietario al aprobar

El modo de ejecución es automático: estas decisiones quedan registradas en lugar de preguntarse en
conversación. El orquestador las presenta. Son las únicas que esta propuesta **no** resuelve.

- **D1. ¿A qué cambio pertenece la tabla `scheduled_tasks`?** Hay un conflicto real entre fuentes. La
  exploración afirma que ADR-0016 líneas 172–180 «confirman que crear esa tabla es responsabilidad del
  cambio 5, no del 9»; al verificarlo, esas líneas registran la excepción de ADR-0009 para las tres
  tablas técnicas y **no nombran ningún cambio**. En sentido contrario, `docs/09-roadmap-y-fases.md`
  línea 126 asigna explícitamente «tabla de tareas creada por migración de Flyway» al entregable 12,
  que `foundations-plan` mapea al cambio 9, y `docs/06` línea 609 sitúa la verificación del catálogo
  cerrado dentro de las pruebas de trabajos en segundo plano.
  - *Recomendación:* **cambio 9**, con el resto del entregable 12. Crear la tabla ahora significaría
    fijar el esquema de una biblioteca cuya versión todavía no está elegida, sin ningún consumidor que
    lo ejercite, y las tres primeras verificaciones de ADR-0017 sobre ella (permisos, ausencia de
    datos personales, configuración) tampoco podrían escribirse aquí.
  - *Alternativa:* crearla en la parte A por ser la primera que toca Flyway, a costa de un esquema sin
    consumidor y de tener que revisarlo en el cambio 9 de todos modos.
  - Si el propietario elige la recomendación, el cuerpo de la exploración queda corregido por su nota
    de propuesta y no hace falta tocar `docs/09`.
- **D2. Forma de entrega ante el exceso de tamaño.** El pronóstico (1 150 a 2 100 líneas) supera 800
  con la estrategia `single-pr` de la sesión, y el conteo de tareas (13 a 16) toca o supera el límite
  de quince por cambio.
  - *Opción A:* `size:exception` para este cambio. Un solo pull request; revisión muy pesada sobre
    código de mecanismo sin precedente, que es justo donde una revisión pesada falla más.
  - *Opción B:* pull requests encadenados dentro de este cambio SDD, con los cortes A1, A2 y A3 de
    «Tamaño estimado», más la excepción de «quince tareas por pull request» que el propietario ya
    concedió en el cambio 4. Exige cambiar la estrategia de entrega de la sesión.
  - La propuesta no elige.
- **D3. ¿Dónde vive el paquete generado de jOOQ?** Afecta al escaneo de ArchUnit y de Spring Modulith
  y, por tanto, a reglas que este cambio escribe.
  - *Recomendación:* fuera de `com.confia` (por ejemplo, `com.confia.generated` explícitamente
    excluido, o un paquete raíz propio), de modo que ninguna regla de arquitectura tenga que
    exceptuarlo caso por caso.
  - Es una decisión de bajo costo hoy y de alto costo después: mueve el paquete de cada import de cada
    repositorio del sistema.
- **D4. ¿Capacidad nueva o no?** Esta propuesta no crea ninguna, porque `openspec/project.md` no lista
  ninguna capacidad de persistencia y sí establece que las fundaciones transversales de F0 no son
  capacidad propia. Si el propietario prefiere una capacidad `persistence` o `data-access`, hay que
  añadirla a `openspec/project.md` **antes** de la fase de especificación, porque la sección
  «Capacidades» de esta propuesta es el contrato que lee `/sdd-spec`.
- **D5. Aprobación de la propuesta completa** antes de especificar, diseñar y planificar tareas
  (`openspec/config.yaml`, `rules.proposal`).
