# Diseño: cableado de jOOQ, Flyway y Testcontainers

> **Sonda S1, parte ejecutada por el orquestador (2026-09-20).** Descargas de Maven Central con
> `-Djavax.net.ssl.trustStoreType=Windows-ROOT`: `org.jooq:jooq-codegen:3.21.7`,
> `org.testcontainers:testcontainers-jooq-codegen-maven-plugin:0.0.4`,
> `org.testcontainers:testcontainers-postgresql:2.0.5` y `testcontainers-junit-jupiter:2.0.5`
> resolvieron con salida 0: **el bloqueo de entorno S1.1 no existe**. Confirmado también S1.5 por
> observación directa: en `postgres:18-alpine` (PostgreSQL 18.6) el usuario creado por
> `POSTGRES_USER` nace con `rolsuper = t` y `rolbypassrls = t`, así que el diseño acierta al arrancar
> el contenedor como `postgres` y crear los cinco roles sin superusuario. Siguen sin verificar, como
> primera tarea de la aplicación: el complemento de generación sobre JDK 25, la API real de
> Testcontainers 2.0.5 y la unión del `DSLContext` a la transacción de la prueba. ADR-0021 fue
> **aceptado** por el propietario el 2026-09-20.

Cambio 5 de F0, **parte A**. Implementa la propuesta aprobada el 2026-09-20 (D5), con D1
(`scheduled_tasks` al cambio 9), D3 (paquete generado fuera de `com.confia`) y D4 (ninguna capacidad
nueva) ya resueltas por el propietario. **D2 (forma de entrega) sigue pendiente y este diseño no la
elige**; solo pronostica el tamaño por corte.

La especificación se escribe en paralelo. Donde este diseño propone un dato que la especificación
fija (nombres de columna, textos de error, criterios exactos de una puerta), **prevalece la
especificación** y las tablas de aquí se alinean antes de la fase de tareas.

---

## 1. Enfoque técnico

De afuera hacia adentro, en los tres cortes que la propuesta ya identificó. Cada corte deja
`./mvnw verify` en verde.

1. **A1 — contenedor y generación de código.** Versiones, dependencias, script de roles solo de
   prueba, `PostgresIntegrationTest` en sus dos variantes, complemento de generación de código sobre
   un PostgreSQL 18 efímero y la prueba mínima que demuestra que la tubería existe. No depende de
   ninguna tabla de negocio.
2. **A2 — primera migración y adaptador.** `organization_institution` con seguridad a nivel de fila
   y privilegios, adaptador jOOQ de `InstitutionRepository`, sus pruebas de ida y vuelta y de
   aislamiento, y la **retirada del `optionalLayer("Infrastructure")` de ADR-0020 en el mismo
   commit** que crea la primera clase de `infrastructure`.
3. **A3 — reglas y puertas de esquema.** Cuatro reglas de ArchUnit del cumplimiento de ADR-0015 con
   sus fixtures negativos permanentes, las puertas de catálogo expresadas como **pertenencia** y la
   fuente única de la versión de PostgreSQL.

El orden no es cosmético: el paso 2 de la propuesta («generación de código antes que consulta»)
exige comprobar que un cambio de esquema rompe la **compilación** antes de que exista una consulta
que dependa de ello, y el rojo programado de ADR-0020 se paga en un ciclo propio porque el
invariante de conteo del escáner de supresiones es frágil.

TDD estricto (`openspec/config.yaml`, `strict_tdd: true`) con Docker disponible en local, de modo
que las pruebas `*IT.java` nacen en rojo de verdad y no «en papel».

---

## 2. Evidencia obtenida en esta fase y límites

Esta fase **no dispuso de ninguna herramienta de ejecución de procesos** (ni Maven, ni Docker, ni
shell), igual que la fase de diseño del cambio 4 (su `design.md`, sección «Evidencia obtenida en
esta fase y límites»). Toda la evidencia de abajo es lectura de archivos reales; lo que exige
ejecutar algo queda marcado como **no verificado** y se convierte en la sonda bloqueante de la
sección 10.

| Pregunta | Resultado | Evidencia |
|---|---|---|
| Versiones que gestiona Spring Boot 4.1.1 para jOOQ, Flyway, Testcontainers y el controlador JDBC | **Verificado.** jOOQ **3.21.7**, Flyway **12.4.0**, Testcontainers **2.0.5**, `org.postgresql:postgresql` **42.7.13** | `~/.m2/repository/org/springframework/boot/spring-boot-dependencies/4.1.1/spring-boot-dependencies-4.1.1.pom`, líneas 58, 114, 165 y 209 |
| ¿El BOM de Spring Boot gestiona también los complementos de generación? | **Verificado, pero inaplicable aquí.** Declara `org.jooq:jooq-codegen-maven` y `org.flywaydb:flyway-maven-plugin` en `pluginManagement` (líneas 3618–3632), pero `apps/api/pom.xml` **importa el BOM como dependencia**, no hereda de `spring-boot-starter-parent`: `pluginManagement` de un BOM importado **no** se aplica. Toda versión de complemento se declara explícitamente en el POM padre | `spring-boot-dependencies-4.1.1.pom` líneas 3605–3632 frente a `apps/api/pom.xml` líneas 66–74 |
| ¿Cambian las coordenadas de Testcontainers 2.x? | **Verificado y crítico.** En 2.0.5 los módulos se renombran: `org.testcontainers:testcontainers-postgresql` y `org.testcontainers:testcontainers-junit-jupiter` (en 1.x eran `postgresql` y `junit-jupiter`). Declarar las coordenadas de 1.x contra el BOM de Boot 4.1 deja la dependencia **sin versión gestionada** | `~/.m2/repository/org/testcontainers/testcontainers-bom/2.0.5/testcontainers-bom-2.0.5.pom`, líneas 128, 133 y 243 |
| ¿Qué hay ya descargado en el repositorio local? | **Verificado.** De jOOQ solo el `jooq-bom-3.21.7.pom`; **ningún jar de jOOQ**. Sí hay jars de Flyway 10.20.1 (`core`, `database-postgresql`, `flyway-maven-plugin`), Testcontainers **1.20.6** y el controlador 42.7.4/42.7.5 | Listado de `~/.m2/repository/org/{jooq,flywaydb,testcontainers,postgresql}` |
| ¿Se pueden descargar artefactos nuevos en esta máquina? | **No verificado.** `apps/api/pom.xml` (líneas 82–94) documenta que una brecha de confianza PKIX de Windows impidió descargar el jar de ArchUnit 1.5.0. El orquestador aporta `MAVEN_OPTS="-Djavax.net.ssl.trustStoreType=Windows-ROOT"` como solución, **sin comprobación ejecutada en esta fase**. Es el primer riesgo del corte A1: sin descarga no hay jOOQ | Comentario de `dependencyManagement` en `apps/api/pom.xml` |
| ¿`jooq-codegen-maven` 3.21.7 y `testcontainers-jooq-codegen-maven-plugin` funcionan sobre JDK 25? | **No verificado.** Ninguno está en el repositorio local y no hubo forma de ejecutar Maven. La versión publicada del complemento de Testcontainers tampoco se pudo consultar | Sonda S1 (sección 10) |
| ¿La imagen `postgres:18-alpine` crea `POSTGRES_USER` como superusuario? | **Comportamiento documentado de la imagen oficial, no probado aquí.** Es determinante: el ejemplo de `docs/06` §14.2 nombra `confia_owner` como usuario del contenedor, lo que lo convertiría en **superusuario**, y un superusuario **omite por completo** la seguridad a nivel de fila, incluso con `FORCE`. Ver decisión 4 | `docs/06` §14.2 líneas 625–633 frente a `docs/03` §6.1 línea 632 («ninguno de estos roles es `SUPERUSER`») |
| ¿La retirada de ADR-0020 exige editar `SuppressionCitesAdrTest`? | **Verificado: no.** El conteo es dinámico (`optionalLayerOccurrences` frente a `EmptyShouldExceptionInventoryTest.countOf(...)`, líneas 133–135 y 151–156), y el propio archivo se excluye del recorrido (`SELF_FILE_NAME`, línea 237). Al retirar la llamada y su entrada, el invariante queda cuadrado solo | `SuppressionCitesAdrTest.java` líneas 54, 68, 133–135, 151–156, 237 |
| ¿El paquete generado fuera de `com.confia` queda fuera de ArchUnit y de Spring Modulith? | **Verificado por lectura.** `ArchitectureTestSupport.productionClasses()` hace `importPackages("com.confia")` y `SpringModulithVerificationTest` usa `ApplicationModules.of("com.confia", ...)`: un paquete raíz distinto nunca entra en el conjunto importado, y las reglas siguen pudiendo prohibirlo porque ArchUnit evalúa el **destino** de cada dependencia aunque no lo haya importado | `ArchitectureTestSupport.java` líneas 22, 29–33; `SpringModulithVerificationTest.java` líneas 35–38 |
| ¿Una exclusión de JaCoCo dispara el escáner de supresiones? | **Verificado: sí.** `JACOCO_CLASS_EXCLUSION` (`<exclude>` sin `:`) exige una cita `ADR-NNNN` a menos de cuatro líneas y que ese ADR exista en `docs/adr/`. Por eso la exclusión del código generado necesita un ADR real al que citar: ADR-0021 | `SuppressionCitesAdrTest.java` líneas 79–81, 97, 197–214 |
| ¿PIT necesita exclusión? | **Verificado: no.** `app/pom.xml` fija `targetClasses = com.confia.*.domain.*`; el paquete generado no coincide, así que **no se añade ningún marcador de supresión de PIT** | `apps/api/app/pom.xml` líneas 131–142 |
| ¿Existe hoy `src/main/resources` en `app`? | **Verificado: no existe.** `application.yml` se crea en este cambio | Listado de `apps/api/app/src/main/resources` |
| ¿Hace falta tocar `.gitattributes` por los finales de línea del script SQL? | **Verificado: no.** La primera regla es `* text=auto eol=lf`, que ya cubre `*.sql`. Se añade una línea explícita solo si la revisión la quiere como documentación | `.gitattributes` línea 2 |

**Consecuencia de método.** Este diseño elige un mecanismo principal y deja **dos alternativas ya
diseñadas** con su criterio de conmutación, en vez de afirmar como verificado lo que no se ejecutó.
La sonda S1 es el primer paso de la aplicación y **bloquea** el resto del corte A1.

---

## 3. Decisiones de arquitectura

### Decisión 1 — Mecanismo de generación de código

**Elección: ruta A, `org.testcontainers:testcontainers-jooq-codegen-maven-plugin`**, en fase
`generate-sources` de `app`: el complemento levanta `postgres:18-alpine`, corre Flyway sobre
`src/main/resources/db/migration` y ejecuta el generador de jOOQ contra el esquema resultante. Es el
mecanismo que la propia ADR-0015 nombra como ejemplo (regla 2 y su referencia final), de modo que
**elegirlo no se aparta de la decisión aceptada y no exige ADR**.

**Alternativas, ya diseñadas, con criterio de conmutación explícito:**

| Ruta | En qué consiste | Cuándo se adopta | Costo |
|---|---|---|---|
| **A (principal)** | Un solo complemento: contenedor + Flyway + generación | Por defecto, si S1 resuelve versión y corre sobre JDK 25 | Menos líneas de POM; dependencia de un complemento de terceros poco mantenido |
| **B (respaldo)** | Tres pasos encadenados en `generate-sources`/`process-sources`: arranque del contenedor con un complemento de Docker (imagen fijada, `initdb.d` montado), `flyway-maven-plugin:migrate`, `jooq-codegen-maven:generate`, y parada del contenedor en `post-integration-test` | Si A no resuelve, no soporta JDK 25, o no admite crear los roles antes de migrar | 80 a 120 líneas más de POM; puerto fijo o asignado dinámicamente; parada garantizada del contenedor |
| **C (prohibida sin ADR)** | `org.jooq.meta.extensions.ddl.DDLDatabase`: generar analizando los archivos SQL, sin Docker | **No se adopta.** Contradice ADR-0015 regla 2 («se levanta un PostgreSQL 18 temporal») y el analizador de jOOQ tendría que aceptar `ALTER TABLE ... FORCE ROW LEVEL SECURITY`, `CREATE POLICY` y `GRANT`, que son justo lo que este cambio introduce | Si el propietario la quisiera, **se eleva a ADR** que reemplace la regla 2; nunca se entierra aquí |

**Razón.** El factor decisivo de ADR-0015 es «un cambio de esquema que rompe una consulta rompe la
compilación». Solo un servidor real aplica de verdad las migraciones que este cambio escribe. La
ruta C dejaría el esquema verificado por un analizador y no por PostgreSQL, que es precisamente la
garantía que se está comprando con el costo de exigir Docker en la construcción.

**Configuración común a A y B** (la misma en ambas rutas, para que conmutar no rediseñe nada):

- imagen `${confia.postgres.image}` (decisión 3);
- Flyway con **dos ubicaciones** en generación: las migraciones reales y una ubicación solo de
  generación con el devolución de llamada `beforeMigrate` de la decisión 5;
- generador `org.jooq.meta.postgres.PostgresDatabase`, esquema `public`;
- `<excludes>` del generador: `flyway_schema_history` y, cuando existan, `scheduled_tasks` y
  `event_publication` (ADR-0017 regla 6);
- destino: paquete `confia.generated.jooq`, directorio `target/generated-sources/jooq`;
- `<generate><globalObjectReferences>false</globalObjectReferences>`: sin `Tables`, `Keys` ni
  `Indexes` globales. Sin esa clase paraguas, **toda** referencia a una tabla nombra su propio tipo
  generado, que es lo que hace verificable la regla de propiedad de tablas (decisión 9, regla R2).

**Sin bandera de omisión.** No se añade ningún `skip` para saltarse la generación sin Docker:
ADR-0008 prohíbe desactivar una puerta con una bandera, y una generación omitida devolvería
exactamente la clase de rojo diferido que ADR-0015 existe para evitar. Ver decisión 12.

### Decisión 2 — Dónde vive el código generado (ADR-0021)

**Elección:** paquete **`confia.generated.jooq`**, fuera de `com.confia`, generado en
`target/generated-sources/jooq` del módulo `app`, nunca comprometido al repositorio.

Consecuencias que resuelven cuatro problemas de una sola vez:

| Herramienta | Efecto | Acción necesaria |
|---|---|---|
| ArchUnit | `productionClasses()` importa `com.confia`: el código generado no entra en el conjunto | Ninguna exclusión por caso |
| Spring Modulith | `ApplicationModules.of("com.confia", ...)`: idéntico | Ninguna |
| PIT | Selector `com.confia.*.domain.*`: no coincide | **Ninguna** (no se añade marcador de supresión) |
| JaCoCo | El código generado **sí** se compila a `target/classes` y contaría en la regla `BUNDLE` del 80 % | `<excludes><exclude>confia/generated/**</exclude></excludes>` en las ejecuciones `jacoco-report` y `jacoco-check` de `app`, **cada una con una cita `ADR-0021` adyacente** que el escáner exige |

**Alternativas descartadas:** (a) `com.confia.generated`, que obligaría a exceptuar el paquete en
ArchUnit, en Spring Modulith y en las reglas de capas, una por una, y que el propietario ya rechazó
en D3; (b) un módulo Maven aparte (`confia-db`) que evitaría incluso la exclusión de JaCoCo: es
atractivo, pero se aparta del mapa de áreas afectadas de la propuesta aprobada, duplica el cableado
de generación y deja el script de roles sin una ruta natural compartida entre módulos; se registra
como alternativa viable en ADR-0021; (c) comprometer el código generado, prohibido por ADR-0015
regla 2.

Esta decisión se eleva a **`docs/adr/ADR-0021-ubicacion-del-codigo-generado-de-jooq.md`**, en estado
**Propuesto**, porque es costosa de revertir (aparecería en los `import` de todos los repositorios
del sistema) y porque la exclusión de JaCoCo necesita un ADR real al que citar.

### Decisión 3 — Versiones y fuente única de la versión de PostgreSQL

**Elección:** las versiones de biblioteca **no se declaran**: las gestiona el BOM de Spring Boot
4.1.1 (jOOQ 3.21.7, Flyway 12.4.0, Testcontainers 2.0.5, controlador 42.7.13). Se declaran en
`apps/api/pom.xml` solo:

- `<confia.postgres.image>postgres:18-alpine</confia.postgres.image>`, **fuente única** de la imagen;
- la versión del complemento de generación de código (el `pluginManagement` del BOM no se hereda,
  sección 2);
- las coordenadas **2.x** de Testcontainers en `app`: `testcontainers-postgresql` y
  `testcontainers-junit-jupiter`, alcance `test`.

La fuente única se hace ejecutable así: `app` filtra **un solo** recurso de prueba,
`src/test/resources/confia-build.properties`, con `postgres.image=${confia.postgres.image}`;
`PostgresIntegrationTest` construye el contenedor leyendo esa propiedad, y
`PostgresImageSingleSourceTest` afirma que el valor existe, que es el que usa el contenedor y que su
etiqueta declara la **misma versión mayor 18** que exige ADR-0015 regla 1. El filtrado se restringe
a ese archivo (`<includes>`) para que ningún `${...}` de un script SQL sea sustituido por accidente.

La comparación a tres bandas de ADR-0015 cumplimiento 9 (Compose, Testcontainers y RDS) no es
posible hoy: Compose y RDS llegan con los cambios 11 y 12. Este cambio deja las dos fuentes que
existen unificadas y el punto declarado, no silenciado.

**Respaldo declarado:** si la API de Testcontainers 2.x resultara incompatible con el patrón de
`docs/06` §14.2, la alternativa es fijar `1.20.6` (ya en el repositorio local, con las coordenadas
antiguas) en `dependencyManagement` del POM padre, con comentario que explique la desviación
respecto del BOM. Es una desviación de versión, no de arquitectura: no exige ADR.

### Decisión 4 — El contenedor **no** arranca como `confia_owner`

**Elección:** el usuario del contenedor es `postgres` (el superusuario de arranque de la imagen).
El script de inicialización solo de prueba crea **los cinco** roles de `docs/03` §6.1 —
`confia_owner`, `confia_admin_app`, `confia_portal_app`, `confia_readonly`, `confia_backup` —, todos
`NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE`, con contraseña de prueba explícita, y transfiere
la propiedad de la base de datos y del esquema `public` a `confia_owner`. Flyway migra como
`confia_owner`; la aplicación conecta como `confia_admin_app`.

**Razón.** El ejemplo de `docs/06` §14.2 nombra `confia_owner` como `withUsername(...)`, y la imagen
oficial crea `POSTGRES_USER` **como superusuario**. Un superusuario omite la seguridad a nivel de
fila incluso con `FORCE`, de modo que toda prueba de aislamiento ejecutada como propietario sería
verde por una razón falsa, y la prueba de `rolbypassrls = false` de `docs/03` §6.4 punto 6 seguiría
pasando (el atributo sería falso y el superusuario seguiría omitiendo la política). Es una trampa
silenciosa, exactamente la clase de garantía cosmética que ADR-0018 y ADR-0008 persiguen.

**Verificación:** la prueba de atributos de rol afirma `rolsuper = false` **además de**
`rolbypassrls = false` para los cinco roles, y afirma que el usuario de la fuente de datos de la
aplicación es `confia_admin_app`. La extensión a `rolsuper` es un refuerzo de `docs/03` §6.4 punto 6
que este diseño declara explícitamente.

**Cómo se aplica el script:** se copia a `/docker-entrypoint-initdb.d/01-create-test-roles.sql`, de
modo que corre durante la inicialización del agrupamiento, antes de que Flyway o la aplicación
conecten. Se prefiere a `withInitScript(...)` porque este último ejecuta el archivo a través del
troceador de sentencias de Testcontainers, que es sensible a bloques `DO $$ ... $$`.

### Decisión 5 — `GRANT` en la migración; roles del contenedor de generación sin contraseña

La decisión técnica 1 de la propuesta (los `GRANT` viajan en la migración, los `CREATE ROLE` no) se
mantiene, y obliga a algo que la propuesta no resolvió: **el contenedor de generación de código
también ejecuta esa migración**, así que los roles deben existir también allí o la generación falla.

**Elección:** una ubicación de Flyway **solo de generación**,
`src/test/resources/db/codegen/beforeMigrate__create_codegen_roles.sql`, con los cinco roles creados
**`NOLOGIN` y sin contraseña alguna**. El contenedor de generación no necesita que esos roles inicien
sesión: solo necesita que existan como destinatarios de `GRANT`.

**Por qué respeta `docs/06` §14.2 líneas 648–649.** La regla prohíbe «una migración de Flyway que
lleve una contraseña». Este archivo (a) no es una migración versionada sino un devolución de llamada,
(b) vive en `src/test/resources`, nunca en el artefacto desplegable, (c) **no contiene ninguna
contraseña**. La ubicación real de despliegue (`classpath:db/migration`) queda intacta.

**Consecuencia declarada para los cambios 11 y 12** (ya anunciada en la propuesta): en RDS los cinco
roles deben existir **antes** de la primera migración, o esa migración falla de forma ruidosa. Se
descarta envolver los `GRANT` en un `DO ... IF EXISTS`: un salto silencioso dejaría la aplicación sin
privilegios y el fallo aparecería en ejecución, no en el despliegue.

### Decisión 6 — DDL de `organization_institution`

```sql
-- V1__create_organization_institution.sql
CREATE TABLE organization_institution (
    id                UUID         PRIMARY KEY,
    legal_name        VARCHAR(200) NOT NULL,
    trade_name        VARCHAR(200),
    rtn               VARCHAR(20)  NOT NULL,
    address           VARCHAR(500) NOT NULL,
    default_currency  CHAR(3)      NOT NULL,
    locale            VARCHAR(35)  NOT NULL,
    timezone          VARCHAR(64)  NOT NULL,
    is_active         BOOLEAN      NOT NULL DEFAULT TRUE,
    CONSTRAINT organization_institution_rtn_digits CHECK (rtn ~ '^[0-9]{1,20}$'),
    CONSTRAINT organization_institution_currency   CHECK (default_currency IN ('HNL', 'USD'))
);

COMMENT ON COLUMN organization_institution.rtn IS
  'Technical guard of 1 to 20 digits inherited from the domain aggregate, NOT a fiscal rule. '
  'The SAR format is pending primary-source validation (docs/04 section 1, docs/09 lines 118-122).';

ALTER TABLE organization_institution ENABLE ROW LEVEL SECURITY;
ALTER TABLE organization_institution FORCE  ROW LEVEL SECURITY;

CREATE POLICY organization_institution_isolation ON organization_institution
    USING      (id = NULLIF(current_setting('app.institution_id', true), '')::uuid)
    WITH CHECK (id = NULLIF(current_setting('app.institution_id', true), '')::uuid);

GRANT SELECT, INSERT, UPDATE ON organization_institution TO confia_admin_app;
GRANT SELECT                 ON organization_institution TO confia_readonly;
-- confia_portal_app: ningún privilegio (docs/03 §6.1: lista corta y explícita; ningún caso de uso
-- del portal toca la raíz de institución todavía). confia_backup lee por pg_read_all_data.
GRANT SELECT ON flyway_schema_history TO confia_admin_app, confia_portal_app; -- ADR-0017
```

Cinco puntos que **no** son obvios y que la prueba debe fijar:

1. **`NULLIF(..., '')`.** `current_setting('...', true)` devuelve `NULL` si falta el ajuste, y
   `id = NULL` deniega, que es el fallo cerrado de `docs/03` §6.2 regla 4. Pero una cadena **vacía**
   llegaría a `''::uuid` y produciría un error de conversión en vez de una denegación. `NULLIF` es lo
   que convierte ese caso en denegación. Hay prueba explícita para los dos casos.
2. **`WITH CHECK` explícito.** Con `FORCE ROW LEVEL SECURITY`, la política también alcanza al
   propietario. Al insertar, PostgreSQL usa `WITH CHECK`; si se omite, hereda `USING`. Se escribe
   explícito para que el comportamiento sea legible: **sembrar una institución exige fijar
   `app.institution_id` con el identificador de la fila que se va a insertar**. Ese detalle es lo que
   hace que la siembra de dos instituciones en las pruebas necesite dos contextos distintos, y lo que
   explicará, en el cambio de administración, por qué dar de alta una institución nueva no es una
   inserción ordinaria (ADR-0009 punto 5 difiere esa funcionalidad).
3. **Sin columna `institution_id`.** La clave primaria **es** el discriminador (ADR-0017, catálogo
   cerrado). La puerta de catálogo lo reconoce explícitamente en vez de meter la tabla en una lista
   de exclusión (decisión técnica 4 de la propuesta).
4. **El comentario del RTN** es el mecanismo que impide una segunda verdad implícita (`docs/09`
   líneas 118–122). La longitud `VARCHAR(20)` y la restricción de comprobación repiten la guarda del
   agregado, no inventan formato fiscal.
5. **`UPDATE` sí, `DELETE` no**, porque `organization_institution` es una tabla de negocio **no
   financiera** (`docs/03` §6.1). `is_active` se modifica; ninguna fila se borra jamás.

`default_currency`, `locale` y `timezone` se almacenan como texto y se convierten en el adaptador:
el dominio ya tiene los tipos cerrados (`CurrencyCode`, `Locale`, `ZoneId`) y no hay ninguna razón
para llevar un tipo enumerado de PostgreSQL, que sería una migración cada vez que el conjunto
cambie.

### Decisión 7 — `PostgresIntegrationTest` y reutilización de contenedor

Tres clases en `com.confia.support` (paquete de prueba, jamás empaquetado):

| Clase | Qué aporta | Cuándo se usa |
|---|---|---|
| `PostgresIntegrationTest` (abstracta) | Contenedor estático único por JVM, `@DynamicPropertySource` con Flyway como `confia_owner` y la fuente de datos como `confia_admin_app`, y el ayudante de contexto de sesión | Base de las otras dos |
| `TransactionalPostgresIntegrationTest` (abstracta) | Añade `@Transactional`: la transacción de la prueba revierte al terminar | Ida y vuelta del adaptador, aislamiento, catálogo |
| `CommittingPostgresIntegrationTest` (abstracta) | Confirma de verdad y trunca de forma selectiva en `@AfterEach` | Pruebas de disparadores, concurrencia y de la parte B |

Detalles que sostienen el presupuesto de 8 minutos de `docs/06` línea 68:

- **Un contenedor por JVM de prueba**, arrancado en un bloque estático, con `fsync=off`,
  `synchronous_commit=off` y datos en `tmpfs`, exactamente como `docs/06` §14.2. Failsafe corre con
  una sola bifurcación reutilizada, de modo que una sola JVM ejecuta todas las `*IT.java`.
- **Sin `withReuse(true)`.** La reutilización entre construcciones exige un archivo de opción por
  máquina y deja contenedores vivos; no es determinista en integración continua y esconde estado
  entre ejecuciones. Se rechaza a propósito.
- **Una sola configuración de contexto de Spring** para todas las `*IT.java`, de modo que la caché
  de contextos de Spring lo cree una vez. Para eso hace falta la clase de la nota siguiente.
- **El tiempo se mide y se reporta** al cerrar el corte A1, no se estima (criterio de éxito de la
  propuesta).

**Nota de configuración obligatoria.** `@SpringBootTest` sin `classes` busca una
`@SpringBootConfiguration` subiendo por los paquetes del propio test; `AdminApplication`,
`PortalApplication` y `WorkerApplication` son de visibilidad de paquete, viven en
`com.confia.bootstrap` y fijan `scanBasePackages` restringido, así que **ninguna** sería encontrada
desde `com.confia.support`. Se añade una clase **solo de prueba**
`com.confia.support.IntegrationTestApplication`, anotada `@SpringBootConfiguration` y
`@EnableAutoConfiguration`, sin escaneo de componentes y con `webEnvironment = NONE`. Así el contexto
trae fuente de datos, Flyway y `DSLContext` y nada más, y no arrastra ninguno de los tres procesos.

### Decisión 8 — Adaptador jOOQ de `InstitutionRepository`

`com.confia.organization.infrastructure.JooqInstitutionRepository` implementa el puerto existente.
**Constructor explícito con `DSLContext`, sin anotación de Spring**: no hay ningún caso de uso
registrado como bean todavía (`ResolveCurrentInstitution` tampoco lo es, y los tres procesos escanean
solo `com.confia.bootstrap`). La prueba lo instancia con el `DSLContext` del contexto. Registrarlo
como bean es trabajo del cambio que lo consuma; hacerlo ahora obligaría a ampliar el escaneo de los
tres procesos sin ningún consumidor.

```java
public final class JooqInstitutionRepository implements InstitutionRepository {

    private final DSLContext dsl;                    // única dependencia; nunca abre transacciones

    @Override
    public Optional<Institution> findById(InstitutionId id) {
        return dsl.selectFrom(ORGANIZATION_INSTITUTION)          // tipo generado, sin Tables global
                  .where(ORGANIZATION_INSTITUTION.ID.eq(id.value()))
                  .fetchOptional()
                  .map(JooqInstitutionRepository::toDomain);     // conversión explícita
    }
}
```

Reglas de la conversión, que la prueba de ida y vuelta fija atributo por atributo:

| Columna | Tipo de dominio | Conversión |
|---|---|---|
| `id` | `InstitutionId` | `new InstitutionId(uuid)` |
| `trade_name` | `String` opcional | `NULL` de SQL ⇄ `null` de Java; nunca cadena vacía |
| `default_currency` | `CurrencyCode` | `CurrencyCode.valueOf(texto)` |
| `locale` | `Locale` | `Locale.forLanguageTag(texto)` / `toLanguageTag()` |
| `timezone` | `ZoneId` | `ZoneId.of(texto)` / `getId()` |
| `is_active` | `boolean` | `Institution.create(...)` nace activa; si la fila está inactiva, se aplica `deactivate()` al reconstruirla |

Tres cosas que el adaptador **no** hace, y su razón:

- **No abre transacciones ni fija el contexto de sesión.** Es la regla 7 de ADR-0015 y es trabajo de
  la parte B; la regla R3 de la decisión 9 lo impide de forma preventiva ya en este cambio.
- **No escribe.** El puerto solo declara `findById`. Cualquier inserción de prueba la hace la propia
  prueba, no un método de producción sin consumidor.
- **No deja salir ningún tipo de `org.jooq` ni del paquete generado.** Es la regla R1.

Con la seguridad a nivel de fila activa, `findById` devuelve vacío tanto para un identificador
inexistente como para una institución de **otra** institución: el aislamiento y la ausencia son
indistinguibles desde el puerto, que es exactamente lo que ADR-0009 quiere.

### Decisión 9 — Cuatro reglas de ArchUnit, cada una con su fixture negativo permanente

Mismo patrón de dos mitades de las reglas existentes (producción en verde, fixture rechazado
nombrando la clase infractora, a través de `ArchitectureTestSupport.assertRuleRejects`). **Ninguna
necesita `allowEmptyShould(true)`**: las cuatro seleccionan todo el código de producción, que ya no
está vacío.

| Regla | Clase de prueba | Qué prohíbe | Fixture negativo |
|---|---|---|---|
| **R1** jOOQ confinado (ADR-0015 cumpl. 2) | `JooqConfinedToInfrastructureTest` | Que una clase fuera de `..infrastructure..` dependa de `org.jooq..` o de `confia.generated..` | `fixture.jooq.application.BadJooqUser` (usa `DSLContext`) |
| **R2** propiedad de tablas (cumpl. 4) | `TableOwnershipByModuleTest` | Que una clase de `com.confia.<módulo>..` dependa de un tipo generado cuyo nombre simple no empiece por `<Módulo>` | `fixture.billing.infrastructure.BadForeignTableUser` (usa `OrganizationInstitution` desde el módulo `billing`) |
| **R3** transacciones centralizadas (cumpl. 3) | `TransactionsOnlyInSharedSecurityTest` | `@Transactional`, `TransactionTemplate` y `PlatformTransactionManager` fuera de `com.confia.shared.security..` | `fixture.transactions.BadTransactionalRepository` |
| **R4** sin SQL plano no aprobado (cumpl. 5) | `NoUnapprovedPlainSqlTest` | Llamadas a las firmas de SQL en texto libre de `DSL` y `DSLContext` fuera de una lista aprobada | `fixture.jooq.infrastructure.BadPlainSqlRepository` |

Precisiones que evitan reglas cosméticas:

- **R2 solo es verificable porque las referencias globales están desactivadas** (decisión 1). Con
  `Tables.ORGANIZATION_INSTITUTION` disponible, cualquier módulo alcanzaría cualquier tabla a través
  de un único tipo paraguas y la regla no distinguiría nada. El «módulo» se extrae con el mismo
  criterio que `NoCrossModuleDomainImportsTest`: el segmento inmediatamente anterior al segmento de
  capa, de modo que vale igual para producción (`com.confia.organization.infrastructure` → módulo
  `organization`) y para el fixture (`...fixture.billing.infrastructure` → módulo `billing`).
- **R3 es una guarda preventiva**: `com.confia.shared.security` **no existe todavía**. La regla no
  queda vacía porque selecciona todas las clases de producción y comprueba que ninguna use esos
  tipos. Su efecto real hoy es impedir que el primer adaptador abra su propia transacción antes de
  que exista el componente de la parte B. Las pruebas usan `@Transactional` libremente: la regla se
  evalúa sobre `productionClasses()`, que excluye `target/test-classes` (`docs/06` §14.2 lo dice
  explícitamente).
- **R4** se escribe **por firma exacta** (`DSL.field(String)`, `DSL.table(String)`,
  `DSL.condition(String)`, `DSL.sql(String)`, `DSLContext.fetch(String)`, `execute(String)`,
  `resultQuery(String)` y sus variantes con parámetros), no por nombre de método, para no confundir
  `DSL.field(Field)` con `DSL.field(String)`. La **lista aprobada está vacía hoy** y se declara como
  un conjunto inmutable vacío con comentario: las consultas de observabilidad sobre tablas técnicas
  de ADR-0017 regla 6 entran cuando existan esas tablas, en el cambio 9.

El fixture de R2 compila contra el tipo generado, de modo que el propio fixture confirma que la
generación ocurrió. Es deliberado: si algún día la generación se apagara, el fixture dejaría de
compilar y la construcción rompería, en vez de que la regla se volviera silenciosamente vacía.

### Decisión 10 — Puertas de esquema expresadas como pertenencia

Dos clases de integración en `com.confia.schema`, todas sobre el catálogo real de PostgreSQL:

`MultiTenantSchemaIT`

1. **Discriminador de institución.** Toda tabla base de `public` lleva `institution_id`, **o**
   pertenece al catálogo cerrado de ADR-0017 (`scheduled_tasks`, `event_publication`,
   `flyway_schema_history`, `organization_institution`). Se afirma **pertenencia, no presencia**
   (decisión técnica 5 de la propuesta): hoy solo existen dos de las cuatro, y la prueba seguirá
   siendo correcta y no se relajará cuando aparezcan las otras.
2. **RLS activa y forzada.** Toda tabla con `institution_id` tiene `relrowsecurity` y
   `relforcerowsecurity` en verdadero (consulta de `docs/03` §6.4 punto 4), **y además** la tabla raíz
   también los tiene, con al menos una política sobre su clave primaria (ADR-0017 regla 1, verificada
   con `pg_policies`).
3. **Restricciones únicas.** Toda restricción o índice único de una tabla de negocio incluye el
   discriminador: `institution_id` en general, y **`id` en la tabla raíz** (decisión técnica 4 de la
   propuesta, reconocida explícitamente en vez de exceptuada).
4. **Aislamiento efectivo.** Con dos instituciones sembradas, una sesión con el contexto de la
   primera no ve la fila de la segunda; sin contexto y con contexto **vacío**, la consulta devuelve
   cero filas y **no lanza** error.

`RolePrivilegeMatrixIT`

5. **Atributos de rol.** Para los cinco roles: `rolsuper = false` y `rolbypassrls = false`
   (decisión 4).
6. **Matriz de privilegios sobre las tablas que existen hoy**, con `has_table_privilege`:
   `confia_admin_app` con `SELECT`/`INSERT`/`UPDATE` y **sin** `DELETE`; `confia_portal_app` sin
   ningún privilegio sobre `organization_institution` y con `SELECT` sobre `flyway_schema_history`;
   `confia_readonly` solo `SELECT`.

Las seis son afirmaciones sobre el catálogo, no sobre una lista escrita a mano: una tabla nueva sin
política, sin discriminador o con una restricción única mal formada rompe la construcción sin que
nadie tenga que acordarse de ampliar la prueba.

### Decisión 11 — Retirada de ADR-0020, en un único commit

La primera clase de `com.confia.organization.infrastructure` hace vencer la entrada
`Infrastructure` del inventario. En **el mismo commit** que añade `JooqInstitutionRepository`:

1. `LayeredArchitectureTest` línea 66: `.optionalLayer("Infrastructure")` → `.layer("Infrastructure")`,
   y se retiran las dos líneas de comentario que citan ADR-0020 para esa capa (líneas 64–65).
2. `LayeredArchitectureTest`, Javadoc de `productionLayeringRule()` (líneas 46–58): deja de decir que
   `Infrastructure` es opcional; `Web` conserva su condición **intacta**.
3. `EmptyShouldExceptionInventoryTest.EXCEPTIONS` (líneas 42–48): se retira la entrada de
   `Infrastructure`; queda una sola, la de `Web`. Su Javadoc (líneas 31–40) pasa de «dos entradas» a
   una.
4. `SuppressionCitesAdrTest`: **ninguna edición**. Su invariante es un conteo dinámico y el archivo
   se excluye a sí mismo del recorrido, de modo que al bajar a la vez la llamada y la entrada el
   conteo cuadra solo (sección 2). Si la aplicación descubriera lo contrario, es una discrepancia con
   este diseño y se reporta, no se «arregla» tocando el conteo.
5. `LayeredArchitectureTest.noProductionClassInLayer(...)` **se conserva**: sigue siendo la condición
   ejecutable de la entrada de `Web`.

El commit cierra con `./mvnw verify` en verde. Si se entregara por cortes, el corte que retira el
`optionalLayer` y el que añade la primera clase de `infrastructure` **son el mismo**: no se separan,
porque son el mismo invariante (plan de reversión de la propuesta, punto 2).

### Decisión 12 — Configuración de Spring, perfil `migrate` y comportamiento sin Docker

- **`application.yml` (nuevo)**: `spring.flyway.enabled: false` y `spring.jooq.sql-dialect: POSTGRES`.
  Flyway **no** corre en los procesos `admin`, `portal` ni `worker` (`docs/05` §9): migrar es un
  proceso aparte.
- **`application-migrate.yml` (nuevo)**: `spring.flyway.enabled: true` con
  `locations: classpath:db/migration`. Es configuración, **no** ejecutable todavía.
- **`ConfiaApplication.MIGRATE` sigue devolviendo `exit(0)`**, y su comentario de la línea 65 se
  corrige: hoy nombra al cambio 5 como responsable de aplicar las migraciones y salir, pero no existe
  ningún entorno desplegado ni Compose que lo invoque hasta los cambios 11 y 12, y la propuesta
  aprobada no lo incluye en su alcance. **Se registra como decisión por defecto**: la corrección del
  comentario entra en este cambio (una línea), el ejecutor real se declara del cambio 11. Si el
  propietario prefiere adelantarlo, son unas 80 a 120 líneas más en el corte A2 y debe decirlo antes
  de la fase de tareas.
- **`./mvnw verify` sin Docker falla**, en `generate-sources`, antes de compilar nada. **Es una
  consecuencia aceptada de ADR-0015 regla 2 («la construcción necesita Docker»), no un defecto.** Se
  declara en tres sitios para que nadie la descubra en un rojo: `apps/api/README.md` (nuevo),
  `openspec/config.yaml` (nota junto a `test_command`) y el delta de especificación de
  `build-integrity`.
- **Integración continua:** `ubuntu-latest` trae Docker, de modo que no hace falta ningún paso nuevo
  de instalación. Los cambios en `.github/workflows/ci.yml` son tres, todos pequeños: un
  `timeout-minutes` explícito en el trabajo `backend`, un comentario que declare la dependencia real
  de Docker, y la mención del presupuesto de 8 minutos de las `*IT.java`. El trabajo `security`
  (Trivy sobre los `pom.xml`) no cambia, aunque ahora verá dependencias nuevas.

---

## 4. Flujo de datos

**Construcción (`./mvnw verify`)**

```
generate-sources
  ┌──────────────────────────────────────────────────────────────┐
  │ contenedor postgres:18-alpine (efímero)                       │
  │   initdb / beforeMigrate  ──> los 5 roles (sin contraseña)     │
  │   Flyway  ──> db/migration/V1__create_organization_institution │
  │   jOOQ codegen ──> confia.generated.jooq (target/, no commit)  │
  └──────────────────────────────────────────────────────────────┘
        │
compile │  el adaptador compila contra los tipos generados
        │  (un cambio de esquema que rompe la consulta = error de compilación)
        ▼
test          Surefire: unitarias + ArchUnit (R1..R4) + Spring Modulith
integration   Failsafe: *IT.java sobre UN contenedor por JVM
verify        JaCoCo (excluye confia/generated/**) y, con perfil, PIT
```

**Ejecución de una lectura en una prueba de integración**

```
  *IT  ──set_config('app.institution_id', ?, true)──┐   (parámetro vinculado, local a la transacción)
   │                                                 ▼
   │                                    ┌────────────────────────┐
   └─ findById(id) ──> JooqInstitutionRepository ──> DSLContext ──> PostgreSQL
                                   (infrastructure)      │            │
                                                          │            ├─ política RLS: id = NULLIF(setting,'')::uuid
                            Institution  <── toDomain ────┘            └─ rol confia_admin_app, sin BYPASSRLS
```

Sin contexto, con contexto vacío o con el contexto de **otra** institución, la política deniega y
`findById` devuelve `Optional.empty()`, nunca una excepción.

---

## 5. Cambios de archivos

| Archivo | Acción | Descripción | Corte |
|---|---|---|---|
| `apps/api/pom.xml` | Modificar | `confia.postgres.image`, versión del complemento de generación, coordenadas 2.x de Testcontainers en `dependencyManagement` si hiciera falta | A1 |
| `apps/api/app/pom.xml` | Modificar | Dependencias (jOOQ, Flyway, controlador, Testcontainers), complemento de generación en `generate-sources`, exclusiones de JaCoCo con cita a ADR-0021, filtrado de un único recurso de prueba | A1 |
| `apps/api/app/src/test/resources/db/testing/create-test-roles.sql` | Crear | Los cinco roles con `LOGIN` y contraseña de prueba, `NOSUPERUSER NOBYPASSRLS`; propiedad de la base y del esquema a `confia_owner` | A1 |
| `apps/api/app/src/test/resources/db/codegen/beforeMigrate__create_codegen_roles.sql` | Crear | Los cinco roles `NOLOGIN`, **sin contraseña**, solo para el contenedor de generación | A1 |
| `apps/api/app/src/test/resources/confia-build.properties` | Crear | `postgres.image=${confia.postgres.image}`, fuente única filtrada por Maven | A1 |
| `apps/api/app/src/main/resources/application.yml` | Crear | Flyway desactivado por defecto, dialecto de jOOQ | A1 |
| `apps/api/app/src/main/resources/application-migrate.yml` | Crear | Flyway activo solo en el perfil de migración (`docs/05` §9) | A1 |
| `apps/api/app/src/test/java/com/confia/support/PostgresIntegrationTest.java` | Crear | Contenedor único por JVM, propiedades dinámicas, ayudante de contexto de sesión | A1 |
| `.../support/TransactionalPostgresIntegrationTest.java` | Crear | Variante con reversión automática | A1 |
| `.../support/CommittingPostgresIntegrationTest.java` | Crear | Variante con confirmación real y truncamiento selectivo | A1 |
| `.../support/IntegrationTestApplication.java` | Crear | `@SpringBootConfiguration` solo de prueba (decisión 7) | A1 |
| `.../support/PostgresImageSingleSourceTest.java` | Crear | Fuente única de la versión de PostgreSQL, ejecutable | A1 |
| `.../support/DatabasePipelineIT.java` | Crear | Humo: contenedor arriba, Flyway aplicado, rol conectado `confia_admin_app`, los cinco roles sin `SUPERUSER` ni `BYPASSRLS` | A1 |
| `apps/api/README.md` | Crear | La construcción exige Docker; cómo se ejecuta y qué falla sin él | A1 |
| `.github/workflows/ci.yml` | Modificar | `timeout-minutes`, nota de Docker y del presupuesto de 8 minutos | A1 |
| `openspec/config.yaml` | Modificar | Nota junto a `test_command`: `./mvnw verify` exige Docker desde este cambio | A1 |
| `apps/api/app/src/main/resources/db/migration/V1__create_organization_institution.sql` | Crear | Tabla, comprobaciones, RLS, política y `GRANT` (decisión 6) | A2 |
| `apps/api/app/src/main/java/com/confia/organization/infrastructure/JooqInstitutionRepository.java` | Crear | Adaptador y conversión explícita de fila a agregado | A2 |
| `.../organization/infrastructure/package-info.java` | Crear | Capa de adaptadores del módulo; nota de ADR-0015 regla 4 | A2 |
| `apps/api/app/src/test/java/com/confia/organization/infrastructure/JooqInstitutionRepositoryIT.java` | Crear | Ida y vuelta atributo por atributo, ausencia sin excepción, aislamiento, contexto ausente y vacío | A2 |
| `apps/api/app/src/test/java/com/confia/architecture/LayeredArchitectureTest.java` | Modificar | `optionalLayer("Infrastructure")` → `layer(...)`, comentario y Javadoc (decisión 11) | A2 |
| `.../architecture/EmptyShouldExceptionInventoryTest.java` | Modificar | Se retira la entrada vencida de `Infrastructure` | A2 |
| `.../architecture/JooqConfinedToInfrastructureTest.java` | Crear | Regla R1 | A3 |
| `.../architecture/TableOwnershipByModuleTest.java` | Crear | Regla R2 | A3 |
| `.../architecture/TransactionsOnlyInSharedSecurityTest.java` | Crear | Regla R3 | A3 |
| `.../architecture/NoUnapprovedPlainSqlTest.java` | Crear | Regla R4 | A3 |
| `.../architecture/fixture/jooq/**`, `fixture/billing/**`, `fixture/transactions/**` | Crear | Cuatro fixtures negativos permanentes | A3 |
| `apps/api/app/src/test/java/com/confia/schema/MultiTenantSchemaIT.java` | Crear | Puntos 1 a 4 de la decisión 10 | A3 |
| `apps/api/app/src/test/java/com/confia/schema/RolePrivilegeMatrixIT.java` | Crear | Puntos 5 y 6 de la decisión 10 | A3 |
| `docs/adr/ADR-0021-ubicacion-del-codigo-generado-de-jooq.md` | Crear | Escrito en esta fase, estado **Propuesto** | — |
| `docs/adr/README.md` | Modificar | Fila del índice de ADR-0021 (escrita en esta fase) | — |
| `openspec/specs/build-integrity/spec.md`, `openspec/specs/organization/spec.md` | Delta al archivar | Los escribe la fase de especificación | — |

`target/` ya está ignorado, de modo que el código generado no entra al repositorio sin tocar
`.gitignore`. `.gitattributes` ya fuerza LF para `*.sql` por su regla global (sección 2).

---

## 6. Contratos e interfaces

**Puerto, sin cambios.** `InstitutionRepository.findById(InstitutionId): Optional<Institution>` ya
existe y **no se toca**: este cambio solo le da implementación. Su Javadoc dice hoy que «su
implementación real es responsabilidad del cambio 5»; esa frase se actualiza al añadir el adaptador.

**Contexto de sesión en pruebas (solo test, parte A).**

```java
// com.confia.support.PostgresIntegrationTest
protected void withInstitutionContext(InstitutionId id, Runnable body) {
    // SELECT set_config('app.institution_id', ?, true) con parámetro vinculado, nunca interpolado,
    // y siempre local a la transacción en curso (docs/03 §6.2, reglas 1 y 2).
}
```

Este ayudante **no** es el componente transaccional de ADR-0015 regla 7 y su Javadoc lo dice: vive en
código de prueba, fija un único parámetro (`app.institution_id`, el único que consulta la política de
la tabla raíz) y desaparece como mecanismo cuando la parte B entregue el componente real, que fija
los cuatro parámetros de `docs/03` §6.2.

**Forma de las reglas nuevas** (esbozo; la firma exacta la fija la implementación):

```java
// R1
noClasses().that().resideOutsideOfPackage("..infrastructure..")
    .should().dependOnClassesThat().resideInAnyPackage("org.jooq..", "confia.generated..")
    .because("jOOQ y el paquete generado viven solo en infrastructure (ADR-0015, regla 4)");

// R2  (condición propia, como NoCrossModuleDomainImportsTest)
classes().should(onlyUseGeneratedTypesOfItsOwnModule());

// R3
noClasses().that().resideOutsideOfPackage("com.confia.shared.security..")
    .should(useAnyTransactionApi())
    .because("solo el componente transaccional de shared/security abre transacciones (ADR-0015, regla 7)");

// R4
noClasses().should(callPlainSqlEntryPoint(APPROVED_PLAIN_SQL /* vacía hoy */))
    .because("el SQL plano de jOOQ solo se permite en la lista aprobada (ADR-0015, regla 9)");
```

---

## 7. Estrategia de pruebas

| Capa | Qué se prueba | Cómo |
|---|---|---|
| Unitaria | Conversión fila ⇄ agregado en los casos que no exigen base (nombre comercial nulo, texto a `Locale`/`ZoneId`) | JUnit + AssertJ, sin contenedor, sobre el convertidor extraído |
| Arquitectura | R1 a R4, cada una en verde contra producción y **rechazando** su fixture nombrando la clase infractora; capas con `Infrastructure` ya obligatoria; inventario de ADR-0020 con una sola entrada | ArchUnit, patrón de dos mitades existente |
| Construcción | Que un cambio de esquema rompe la **compilación**; que el código generado no está en el repositorio ni en las métricas; que la imagen de PostgreSQL tiene una sola fuente | Ruptura deliberada verificada a mano en el paso A1.6 (como el cambio 1 con ArchUnit), informe de JaCoCo y `PostgresImageSingleSourceTest` |
| Integración (`*IT.java`) | Humo de la tubería; ida y vuelta del adaptador; ausencia sin excepción; aislamiento con dos instituciones; contexto ausente y contexto vacío; inventario de RLS; discriminador y restricciones únicas; catálogo cerrado por pertenencia; matriz de privilegios; atributos de rol | Failsafe + Testcontainers, un contenedor por JVM, variante transaccional salvo donde se necesite confirmar |
| Rendimiento | Que la suite `*IT.java` cabe en 8 minutos | **Medido y reportado** al cerrar A1 y de nuevo al cerrar A3 (`docs/06` línea 68) |

Puertas heredadas que **no** cambian: JaCoCo 80 % global en `app` y 95 % en cada paquete `domain`;
PIT 80 sobre `domain` en el perfil de mutación; enforcer con su lista de dependencias prohibidas. La
única interacción nueva es la exclusión del paquete generado en JaCoCo (decisión 2), que se comprueba
leyendo el informe antes de escribir el adaptador, tal como pide el paso 2 del enfoque de la
propuesta.

**Riesgo de convergencia de dependencias.** El enforcer corre `dependencyConvergence` en `validate`.
Seis dependencias nuevas pueden desconvergir alguna transitiva (el cambio 1 ya documentó un caso con
ArchUnit). Si ocurre, se resuelve declarando la versión en `dependencyManagement` con un comentario
que explique qué mediación se está haciendo explícita, **nunca** con una exclusión silenciosa.

---

## 8. Matriz de amenazas

No aplica en el sentido de `references/threat-matrix.md`: el cambio no introduce enrutamiento,
órdenes de shell, subprocesos de la aplicación, automatización de Git o de pull requests, ni
clasificación de archivos ejecutables. Las tres fronteras de seguridad que sí toca se tratan como
decisiones con verificación, no como filas de matriz:

| Frontera | Tratamiento |
|---|---|
| La construcción ejecuta un contenedor de terceros | Imagen declarada en una **sola** propiedad (`postgres:18-alpine`), verificada por prueba. Fijar la imagen por resumen criptográfico pertenece a la política de imágenes de los cambios 11 y 12, donde existirán Compose y el registro |
| Credenciales de prueba en el repositorio | Solo en `src/test/resources`, con valor literal `test-only-not-a-secret`, nunca en `src/main`, nunca en una migración, nunca en `application*.yml`. El archivo de generación de código no lleva contraseña alguna (decisión 5) |
| Aislamiento por fila | Es el objeto de las pruebas de la decisión 10, incluido el caso de fallo cerrado. La prueba de atributos de rol cubre el hueco silencioso del superusuario (decisión 4) |

---

## 9. Migración y despliegue

No hay entorno desplegado ni dato real: la base de datos de este cambio vive solo en contenedores
efímeros que se recrean en cada construcción. Revertir es revertir el commit de fusión, con dos
matices ya escritos en la propuesta:

1. Al desaparecer la clase de `infrastructure`, la caducidad de ADR-0020 vuelve a cumplirse, de modo
   que `optionalLayer("Infrastructure")` y su entrada se restauran con el mismo revert. Si se revierte
   por cortes, el corte de la migración y el del `optionalLayer` se revierten juntos.
2. Si el mecanismo de generación resultara inviable, la reversión parcial es retirar el complemento y
   **detenerse**. No se compromete código generado al repositorio como salida de emergencia
   (ADR-0015 regla 2): se eleva un ADR.

El punto de no retorno práctico sigue siendo el cambio 11, cuando exista el primer entorno
desplegado.

---

## 10. Sonda S1 (primer paso de la aplicación, bloqueante)

Antes de escribir nada del corte A1, y fuera del árbol del repositorio o en archivos temporales que
se borran, se responde con evidencia:

| # | Pregunta | Criterio de éxito |
|---|---|---|
| S1.1 | ¿Se descargan artefactos nuevos de Maven Central en esta máquina con `MAVEN_OPTS="-Djavax.net.ssl.trustStoreType=Windows-ROOT"`? | Un `dependency:get` de `org.jooq:jooq:3.21.7` deja el jar en `~/.m2`. **Si falla, el cambio está bloqueado por entorno**, no por diseño |
| S1.2 | ¿Qué versiones de `testcontainers-jooq-codegen-maven-plugin` existen y resuelven? | Al menos una versión resoluble |
| S1.3 | ¿Corre esa versión sobre JDK 25 y levanta `postgres:18-alpine`? | El complemento termina en verde y genera al menos una clase |
| S1.4 | ¿Admite dos ubicaciones de Flyway, de modo que el devolución de llamada cree los roles antes de migrar? | La migración con `GRANT` se aplica sin error |
| S1.5 | ¿Qué API expone Testcontainers 2.0.5 para `PostgreSQLContainer`, `withTmpFs` y las órdenes de arranque? | Compila el patrón de `docs/06` §14.2, o se documenta la diferencia exacta |
| S1.6 | ¿El `DSLContext` de Spring Boot 4.1 se une a la transacción de la prueba, de modo que `set_config(..., true)` aplique a la consulta del adaptador? | Una consulta dentro de la transacción de la prueba ve el ajuste |

**Resultado y consecuencia:**

- S1.1 falla → **bloqueo de entorno**: se reporta al propietario y no se aplica nada.
- S1.2 o S1.3 fallan → se adopta la **ruta B** de la decisión 1, sin ADR (no se aparta de ADR-0015).
- S1.4 falla en la ruta A → ruta B, que monta `initdb.d` y no depende de devoluciones de llamada.
- Ninguna ruta con Docker funciona → **se detiene la aplicación y se eleva un ADR** que reemplace la
  regla 2 de ADR-0015. No se entierra aquí (`docs/13-metodologia-sdd.md`, regla 5).
- S1.5 incompatible → se fija Testcontainers 1.20.6 (decisión 3, respaldo declarado).
- S1.6 falla → el ayudante de contexto ejecuta la consulta del adaptador sobre la **misma conexión**
  obtenida explícitamente, y se documenta; el diseño del adaptador no cambia.

El resultado observado se registra en el informe de aplicación, como hizo la sonda de ADR-0020 en el
cambio 4.

---

## 11. Secuencia de aplicación con TDD estricto

**A1 — contenedor y generación de código**

1. S1 completa y registrada (sección 10). Bloqueante.
2. Rojo: `DatabasePipelineIT` afirma que el contenedor arranca, que Flyway dejó su historial y que la
   conexión de la aplicación es `confia_admin_app`. Falla porque no hay nada.
3. Verde: dependencias, `PostgresIntegrationTest`, `IntegrationTestApplication`, script de roles,
   `application*.yml`.
4. Rojo: `PostgresImageSingleSourceTest`. Verde: propiedad, filtrado y lectura.
5. Rojo: los roles sin `SUPERUSER` ni `BYPASSRLS`. Verde: atributos del script.
6. Complemento de generación en `generate-sources` sobre una migración mínima de prueba, y
   **demostración deliberada**: se cambia el esquema y se comprueba que una consulta deja de
   compilar; se revierte. Se confirma en el informe de JaCoCo que el paquete generado no cuenta.
7. Medición del tiempo de la suite y nota en `README.md`, `config.yaml` y el flujo de trabajo.

**A2 — migración y adaptador**

8. Rojo: `MultiTenantSchemaIT` puntos 1 a 3 y `JooqInstitutionRepositoryIT` de ida y vuelta.
9. Verde: `V1__create_organization_institution.sql` con RLS, política y `GRANT`.
10. **Commit único de ADR-0020**: primera clase de `infrastructure` + adaptador + retirada del
    `optionalLayer`, su entrada de inventario y el Javadoc (decisión 11), con `verify` en verde.
11. Rojo/verde: aislamiento con dos instituciones, contexto ausente y contexto vacío.

**A3 — reglas y puertas**

12. Un ciclo por regla (R1, R2, R3, R4): primero el fixture negativo y la mitad que lo rechaza,
    después la mitad de producción. Cada regla en su propio commit; el conteo del escáner no se toca.
13. Rojo/verde: `MultiTenantSchemaIT` punto 4 y `RolePrivilegeMatrixIT`.
14. Medición final del tiempo de la suite y cierre documental.

---

## 12. Pronóstico de tamaño por corte

Líneas de autor (adiciones más eliminaciones) en código, pruebas, POM, SQL y configuración. **Quedan
fuera** los artefactos de OpenSpec y ADR-0021 con su fila del índice (unas 120 líneas, ya escritas en
esta fase) y, por definición, el código generado de jOOQ. La estimación ya incorpora la desviación de
1,5 a 3 veces observada en los cambios 2 y 4.

| Corte | Desglose | Estimación |
|---|---|---|
| **A1** | POM padre (60–100), `app/pom.xml` (110–170), script de roles de prueba (35–60), devolución de llamada de generación (15–30), `application*.yml` (25–45), `PostgresIntegrationTest` y sus dos variantes (110–170), `IntegrationTestApplication` (15–25), fuente única y su prueba (30–50), `DatabasePipelineIT` (70–120), `README.md` (20–40), integración continua y `config.yaml` (12–30) | **502 a 840** |
| **A2** | `V1__...sql` (60–110), adaptador y conversión (90–150), `package-info` (10–20), ayudante de contexto (40–70), `JooqInstitutionRepositoryIT` (180–300), retirada de ADR-0020 (25–45) | **405 a 695** |
| **A3** | R1 (50–80), R2 (90–140), R3 (60–100), R4 (90–140), cuatro fixtures (70–120), `MultiTenantSchemaIT` (110–190), `RolePrivilegeMatrixIT` (70–110) | **540 a 880** |
| **Total** | | **1 447 a 2 415** |

**Frente al presupuesto de 800 líneas por pull request** (`docs/15-flujo-de-trabajo-git.md` §3,
vigente desde la decisión P1 del cambio 2; la política de la sesión SDD registra 400 y debe
reconciliarse, igual que en el cambio 4):

- Un solo pull request supera 800 **por un factor de entre 1,8 y 3**. Exigiría `size:exception`
  sobre código de mecanismo sin precedente, que es donde una revisión pesada falla más.
- Por cortes: **A2 cabe siempre**; **A1 y A3 caben en su rango bajo y rozan o superan 800 en el
  alto**. Puntos de subdivisión ya identificados, sin separar código de sus pruebas:
  - **A1a** = dependencias, `PostgresIntegrationTest`, roles y humo (≈300–500);
    **A1b** = complemento de generación, exclusiones, fuente única y documentación (≈200–340).
  - **A3a** = R1 y R3 con sus fixtures (≈180–300); **A3b** = R2 y R4 con sus fixtures (≈250–400);
    **A3c** = puertas de esquema (≈180–300). A3c puede fusionarse con A2 si A2 se queda corto.
- **Tareas:** entre 13 y 16, igual que pronosticó la propuesta; toca o supera el límite de quince por
  cambio de `openspec/changes/README.md`.
- **Este diseño no elige la forma de entrega: D2 es del propietario.**

---

## 13. Restricciones del entorno local

- `JAVA_HOME="C:/Program Files/Eclipse Adoptium/jdk-25.0.3.9-hotspot"` y
  `MAVEN_OPTS="-Djavax.net.ssl.trustStoreType=Windows-ROOT"` (esta última **nunca se compromete**);
  siempre `./mvnw -B` desde `apps/api`, con la salida redirigida a un archivo temporal.
- Docker 29.7.2 con `postgres:18-alpine` ya descargado. Sobre WSL2, `tmpfs` y el primer arranque
  pueden comportarse distinto que en Linux: **ante divergencia manda la integración continua**.
- El repositorio local **no tiene ningún jar de jOOQ**: la primera construcción descarga la
  biblioteca completa. Es el primer punto de fallo posible y lo cubre la sonda S1.1.
- Nunca se baja un umbral ni se omite una prueba para pasar en local.

---

## 14. Por confirmar durante la implementación

1. Las seis preguntas de la sonda S1 (sección 10).
2. Que Spring Boot 4.1 sigue admitiendo `spring.flyway.url/user/password` como fuente de datos
   independiente de la de la aplicación (es lo que separa al migrador `confia_owner` del ejecutor
   `confia_admin_app` en `docs/06` §14.2).
3. Que la imagen `postgres:18-alpine` crea `POSTGRES_USER` como superusuario, lo que la prueba de
   `rolsuper` convierte en verificación permanente (decisión 4).
4. Que `<globalObjectReferences>false</globalObjectReferences>` existe con ese nombre en el generador
   de jOOQ 3.21 y produce el efecto que la regla R2 necesita.
5. Que el filtrado de recursos restringido a `confia-build.properties` no altera ningún otro recurso
   de prueba, en particular los `.sql`.
6. Que las exclusiones de JaCoCo aceptan el patrón de ruta `confia/generated/**` en las ejecuciones
   `jacoco-report` y `jacoco-check`, y que cada una conserva su cita `ADR-0021` a menos de cuatro
   líneas (el escáner falla si no).
7. Que la retirada de ADR-0020 no exige tocar `SuppressionCitesAdrTest` (sección 2). Si la aplicación
   lo desmiente, **se reporta la discrepancia con este diseño**, no se ajusta el conteo.
8. Que `dependencyConvergence` sigue en verde con las dependencias nuevas.

Si alguna confirmación obliga a apartarse de lo decidido, se eleva a un ADR y no se entierra aquí
(`docs/13-metodologia-sdd.md`, regla 5).

---

## 15. Preguntas abiertas

- [ ] **D2, forma de entrega** (propietario): un pull request con `size:exception`, o los cortes A1,
      A2 y A3 encadenados, con las subdivisiones ya identificadas en la sección 12 y la excepción de
      «quince tareas por pull request» que el cambio 4 sentó como precedente.
- [ ] **ADR-0021** (propietario): aceptarlo antes de que el corte A1 escriba la exclusión de JaCoCo
      que lo cita. Sin aceptación, la cita apunta a un ADR en estado Propuesto: el escáner pasa
      (solo exige que el archivo exista), pero la decisión no sería vinculante.
- [ ] **Sonda S1 no ejecutada en diseño**: esta fase no tuvo herramienta de ejecución de procesos.
      Queda como paso bloqueante A1.1.
- [ ] **Perfil `migrate`** (decisión 12): este diseño registra por defecto que el ejecutor real es del
      cambio 11 y que aquí solo entra la configuración más la corrección del comentario de
      `ConfiaApplication`. El propietario puede adelantarlo por unas 80 a 120 líneas más en A2.
- [ ] **Privilegios de `confia_portal_app` sobre la raíz de institución**: este diseño no concede
      ninguno, por mínimo privilegio. Si el portal necesitara leer el nombre de la institución, es un
      `GRANT SELECT` que debe pedir el cambio que lo consuma, con su prueba.
- [ ] **Presupuesto de revisión**: la política de la sesión SDD registra 400 líneas; el proyecto fija
      800 (`docs/15` §3). El orquestador debe alinear su registro, como ya quedó anotado en el
      cambio 4.
