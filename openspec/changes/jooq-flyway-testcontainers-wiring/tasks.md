# Tareas: cableado de jOOQ, Flyway y Testcontainers

## Review Workload Forecast

| Campo | Valor |
|---|---|
| Presupuesto de revisión de esta sesión (guardia literal de la fase) | 400 líneas |
| Presupuesto de revisión vigente para este cambio (`docs/15-flujo-de-trabajo-git.md` §3; precedente P1 del cambio 2 y del cambio 4) | **800 líneas** de cambio efectivo por pull request — prevalece sobre el valor de sesión; no se re-decide aquí |
| Líneas de autor estimadas (código, sin `openspec/`, sin `docs/adr/`, sin el paquete generado de jOOQ) | 1 447 a 2 415, según `design.md` §12 «Pronóstico de tamaño por corte» |
| Riesgo frente al presupuesto de 400 (guardia literal de la fase) | **High** — cualquier corte individual, incluso en su extremo bajo, supera 400 |
| Riesgo frente al presupuesto de 800 vigente | **Medium** — A2 cabe siempre (405–695); A1 (502–840) y A3 (540–880) caben en su extremo bajo y rozan o superan 800 en el alto. El diseño ya nombra los puntos de subdivisión (A1a/A1b, A3a/A3b/A3c); el riesgo real se confirma con la tarea de medición de cada corte |
| Pull requests encadenados recomendados | Sí — ya decidido por el propietario (D2 de la propuesta, `delivery_strategy: auto-chain`, `chain_strategy: stacked-to-main`) |
| División sugerida | PR A1 (`change/jooq-flyway-testcontainers-wiring`, base `main`) → PR A2 (`...-migration`, base PR A1) → PR A3 (`...-gates`, base PR A2) |
| Estrategia de entrega | `auto-chain` |
| Estrategia de cadena | `stacked-to-main` |

Decision needed before apply: No
Chained PRs recommended: Yes
Chain strategy: stacked-to-main
400-line budget risk: High

**Nota sobre el excedente frente al presupuesto de sesión (ya resuelta, no bloquea la aplicación):**
la política de la sesión SDD registra 400 líneas; el propietario ya fijó 800 para este proyecto y ya
resolvió D2 de la propuesta con cortes encadenados (A1, A2, A3), cada uno medido al cerrar. Si el
diff real de un corte supera 800, la tarea de medición de ese corte (1.9, 2.6 o 3.8) **detiene la
aplicación** y consulta al propietario, con los puntos de subdivisión que `design.md` §12 ya nombra
(A1a/A1b; A3a/A3b/A3c, con A3c fusionable con A2 si A2 quedara corto). Esto ya está decidido como
procedimiento, igual que en el precedente `institution-root-and-multitenancy-baseline`.

### Nota sobre el límite de quince tareas por pull request

Esta lista tiene **26 tareas en total** (10 en PR A1, 7 en PR A2, 9 en PR A3). **Se aplica el mismo
criterio que el propietario aceptó en los cambios 2 y 4: el límite de quince tareas rige por pull
request, no por cambio completo.** Queda muy por debajo de
quince **por pull request**. El lanzamiento de esta fase fija el límite en quince tareas por pull
request, no por cambio SDD completo, siguiendo el mismo precedente aceptado por el propietario en
los cambios 2 y 4 (`kernel-money-value-object` e `institution-root-and-multitenancy-baseline`). Se
deja constancia de que el total del cambio (26) supera el límite de quince de
`openspec/changes/README.md` medido por cambio completo, por si el propietario prefiere una lectura
distinta; no se decide aquí, se informa.

### Suggested Work Units

| Unit | Goal | Likely PR | Focused test command | Runtime harness | Rollback boundary |
|------|------|-----------|----------------------|-----------------|-------------------|
| A1 | Contenedor, roles de prueba, generación de código de jOOQ y prueba mínima de que la tubería funciona; sin ninguna tabla de negocio | PR A1 (`change/jooq-flyway-testcontainers-wiring`) | `./mvnw -B -pl apps/api/app -am test -Dtest=DatabasePipelineIT,PostgresImageSingleSourceTest -Dsurefire.failIfNoSpecifiedTests=false` | `./mvnw -B verify` en `apps/api`, JDK 25, Docker activo | Revertir el pull request completo; sin tabla de negocio ni adaptador, el repositorio vuelve exactamente al estado del cambio 4 |
| A2 | `organization_institution` con RLS y `GRANT`, adaptador jOOQ de solo lectura, y el cierre del rojo programado de ADR-0020 | PR A2 (`...-migration`, base PR A1) | `./mvnw -B -pl apps/api/app -am test -Dtest=JooqInstitutionRepositoryIT` | `./mvnw -B verify` en `apps/api` | Revertir PR A2 sin fusionar; PR A1 queda completo por sí solo (tubería sin tabla de negocio); el corte que retira el `optionalLayer` de `Infrastructure` y el que añade la primera clase de `infrastructure` se revierten juntos, por ser el mismo invariante (plan de reversión de la propuesta) |
| A3 | Cuatro reglas de ArchUnit del cumplimiento de ADR-0015, puertas de catálogo de esquema y matriz de privilegios | PR A3 (`...-gates`, base PR A2) | `./mvnw -B -pl apps/api/app -am test -Dtest=JooqConfinedToInfrastructureTest,TableOwnershipByModuleTest,TransactionsOnlyInSharedSecurityTest,NoUnapprovedPlainSqlTest` | `./mvnw -B verify` en `apps/api`, con la suite `*IT.java` completa por Failsafe | Revertir PR A3 sin fusionar; PR A2 queda completo y en verde por sí solo, con el adaptador y la migración operativos sin las reglas de ArchUnit ni las puertas de esquema |

Ejecutor de todas las tareas: `./mvnw -B verify` en `apps/api`, con `JAVA_HOME` apuntando a JDK 25 y
`MAVEN_OPTS=-Djavax.net.ssl.trustStoreType=Windows-ROOT` (`design.md` §13, «Restricciones del entorno
local»); Docker debe estar activo porque la generación de código corre en `generate-sources`. La
integración continua en `ubuntu-latest` es la fuente de verdad ante cualquier divergencia observada en
Windows/WSL2. Nunca se baja un umbral ni se omite una prueba para pasar en local.

TDD estricto (`openspec/config.yaml`, `strict_tdd: true`): toda clase `*IT.java` observa ROJO antes de
VERDE, y esa evidencia se registra **en el repositorio** — la línea correspondiente de esta lista de
tareas y `openspec/changes/jooq-flyway-testcontainers-wiring/apply-progress.md` —, nunca solo en
Engram.

**Estado ya resuelto, no se re-planifica (Sonda S1, `design.md` §10 y nota del orquestador del
2026-09-20):** las descargas de Maven Central con el almacén de confianza `Windows-ROOT` funcionan
(jOOQ 3.21.7, el complemento de generación de Testcontainers 0.0.4, Testcontainers 2.0.5 resuelven), y
`postgres:18-alpine` crea `POSTGRES_USER` con `rolsuper = t` y `rolbypassrls = t`, confirmando que el
diseño acierta al arrancar el contenedor como `postgres` y crear los cinco roles sin superusuario
(decisión 4 de `design.md`). Ninguna tarea repite esta verificación. **Sigue pendiente, y es la
primera tarea de A1 (1.1):** que el complemento de generación corra sobre JDK 25, la API real de
Testcontainers 2.0.5 frente al patrón de `docs/06` §14.2, y si el `DSLContext` de Spring Boot 4.1 se
une a la transacción de la prueba.

**Nota de reconciliación entre `design.md` §5 y §11.** La secuencia de aplicación del diseño (§11,
paso 8) menciona `MultiTenantSchemaIT` puntos 1 a 3 dentro de A2, pero la tabla de «Cambios de
archivos» (§5) asigna la clase completa —los cuatro puntos— a A3. Esta lista de tareas sigue la tabla
de archivos (§5), que es la asignación de corte autoritativa: `MultiTenantSchemaIT` y
`RolePrivilegeMatrixIT` se crean enteras en PR A3. Lo que el diseño llama en su paso 11 «aislamiento
con dos instituciones, contexto ausente y contexto vacío» dentro de A2 corresponde a los escenarios de
aislamiento de `JooqInstitutionRepositoryIT` (especificación `organization`, no `build-integrity`),
que sí está asignada a A2 en la misma tabla de archivos (fila 553). Si la aplicación descubre que esta
lectura es incorrecta, se reporta la discrepancia, no se reinterpreta en silencio.

---

## PR A1 — corte A1: contenedor y generación de código

Rama `change/jooq-flyway-testcontainers-wiring` (rama actual), base `main`. Sin ninguna tabla de
negocio: demuestra que la tubería de Testcontainers + Flyway + generación de jOOQ funciona con la
prueba más pequeña posible (propuesta, «Enfoque», paso 1).

- [x] 1.1 **Ejecutar las partes restantes de la sonda S1 (bloqueante, `design.md` §10).** Antes de
  escribir cualquier archivo de producción del corte, verificar con evidencia real, fuera del árbol
  del repositorio o en archivos temporales que se borran: (a) que
  `org.testcontainers:testcontainers-jooq-codegen-maven-plugin:0.0.4` corre sobre JDK 25 y levanta
  `postgres:18-alpine` generando al menos una clase; (b) la API real de
  `PostgreSQLContainer`/`withTmpFs`/órdenes de arranque de Testcontainers 2.0.5 frente al patrón de
  `docs/06-estrategia-de-testing.md` §14.2; (c) si el `DSLContext` de Spring Boot 4.1 se une a la
  transacción de la prueba, de modo que `set_config(..., true)` sea visible para la consulta del
  adaptador. Registrar el resultado exacto de cada pregunta en
  `openspec/changes/jooq-flyway-testcontainers-wiring/apply-progress.md` (crear el archivo si no
  existe). **Consecuencia según `design.md` §10:** si el complemento no resuelve o no corre sobre
  JDK 25, adoptar la ruta B (respaldo, `design.md` decisión 1) sin ADR; si ninguna ruta con Docker
  funciona, **detener la aplicación y elevar un ADR** que reemplace la regla 2 de ADR-0015 — nunca
  comprometer código generado como salida de emergencia. Si (c) falla, el ayudante de contexto de
  sesión ejecuta la consulta del adaptador sobre la misma conexión obtenida explícitamente, sin
  cambiar el diseño del adaptador. — `design.md` §10 (sonda S1, primer paso de la aplicación,
  bloqueante)

- [x] 1.2 **Cablear el POM padre.** En `apps/api/pom.xml`: declarar
  `<confia.postgres.image>postgres:18-alpine</confia.postgres.image>` como fuente única de la imagen
  (decisión 3); declarar la versión del complemento de generación de código elegido en 1.1 (el
  `pluginManagement` del BOM de Spring Boot no se hereda porque `apps/api/pom.xml` importa el BOM
  como dependencia, no como padre — `design.md` §2); si hiciera falta, fijar las coordenadas 2.x de
  Testcontainers (`testcontainers-postgresql`, `testcontainers-junit-jupiter`) en
  `dependencyManagement`. Confirmar con `./mvnw -B -N validate` que el reactor sigue resolviendo. —
  `design.md`, decisiones 1 y 3; especificación `build-integrity`, requisito «Fuente única de la
  versión de PostgreSQL en la construcción»

- [x] 1.3 **Cablear `apps/api/app/pom.xml`.** Añadir las dependencias de jOOQ (sin versión, gestionada
  por el BOM), Flyway, el controlador `org.postgresql:postgresql`, y las coordenadas 2.x de
  Testcontainers en alcance `test`. No declarar todavía el complemento de generación (tarea 1.7).
  Confirmar que `./mvnw -B -pl apps/api/app -am validate` resuelve sin romper
  `dependencyConvergence` del `maven-enforcer-plugin`; si desconverge alguna transitiva, fijar la
  versión mediadora en `dependencyManagement` con un comentario explícito, nunca con una exclusión
  silenciosa (`design.md` §7, «Riesgo de convergencia de dependencias»). — Áreas afectadas de la
  propuesta, fila `apps/api/app/pom.xml`

- [x] 1.4 **ROJO — humo de la tubería.** Crear
  `apps/api/app/src/test/java/com/confia/support/DatabasePipelineIT.java`: afirma que el contenedor
  arranca, que Flyway dejó su historial (`flyway_schema_history` con al menos una fila), que la
  fuente de datos de la aplicación conecta como `confia_admin_app`, y que los cinco roles de
  `docs/03-seguridad.md` §6.1 tienen `rolsuper = false` y `rolbypassrls = false`. Falla al compilar
  o al ejecutar porque no existen ni el contenedor de prueba ni los roles. Registrar el mensaje de
  fallo exacto en `apply-progress.md`. — Propuesta, punto de alcance 5 («Base de pruebas de
  integración»); criterios de éxito «La aplicación se conecta con `confia_admin_app` y ningún rol es
  `SUPERUSER` ni tiene `BYPASSRLS`»

- [x] 1.5 **VERDE — tubería mínima.** Crear
  `apps/api/app/src/test/resources/db/testing/create-test-roles.sql` (los cinco roles con `LOGIN` y
  contraseña de prueba literal `test-only-not-a-secret`, `NOSUPERUSER NOBYPASSRLS NOCREATEDB
  NOCREATEROLE`, propiedad de la base y del esquema `public` transferida a `confia_owner`, montado
  como `/docker-entrypoint-initdb.d/01-create-test-roles.sql`, nunca con `withInitScript(...)` —
  `design.md`, decisión 4); `apps/api/app/src/test/java/com/confia/support/PostgresIntegrationTest.java`
  (contenedor estático único por JVM, `fsync=off`, `synchronous_commit=off`, datos en `tmpfs`,
  `@DynamicPropertySource` con Flyway como `confia_owner` y la fuente de datos de la aplicación como
  `confia_admin_app`, sin `withReuse(true)`); `.../support/IntegrationTestApplication.java`
  (`@SpringBootConfiguration` solo de prueba, `@EnableAutoConfiguration`, sin escaneo de componentes,
  `webEnvironment = NONE` — `design.md`, decisión 7); `apps/api/app/src/main/resources/application.yml`
  (`spring.flyway.enabled: false`, `spring.jooq.sql-dialect: POSTGRES`); y
  `apps/api/app/src/main/resources/application-migrate.yml` (`spring.flyway.enabled: true`,
  `locations: classpath:db/migration`, sin ejecutor todavía). En el mismo commit, corregir el
  comentario obsoleto de `ConfiaApplication.MIGRATE` (línea ~65) que hoy nombra al cambio 5 como
  responsable de aplicar migraciones y salir: declarar que el ejecutor real es del cambio 11
  (`design.md`, decisión 12). Ejecutar `DatabasePipelineIT`: verde. — mismo alcance que 1.4;
  `docs/03` §6.1 (cinco roles); `docs/05-configuracion-y-perfiles.md` §9 (Flyway solo en el perfil de
  migración)

- [x] 1.6 **ROJO/VERDE — fuente única de la versión de PostgreSQL.** ROJO: crear
  `apps/api/app/src/test/resources/confia-build.properties` con
  `postgres.image=${confia.postgres.image}` filtrado por Maven (`<includes>` restringido a este
  archivo, para que ningún `${...}` de un script SQL se sustituya por accidente), y
  `apps/api/app/src/test/java/com/confia/support/PostgresImageSingleSourceTest.java` que afirma que
  la propiedad existe, que `PostgresIntegrationTest` la usa para construir el contenedor, y que su
  etiqueta declara la misma versión mayor 18 que exige ADR-0015 regla 1; falla porque el filtrado no
  está cableado en `app/pom.xml` todavía. VERDE: cablear el filtrado de ese único recurso en
  `apps/api/app/pom.xml` (`maven-resources-plugin`) y hacer que `PostgresIntegrationTest` lea la
  propiedad. — Especificación `build-integrity`, requisito «Fuente única de la versión de PostgreSQL
  en la construcción» (ambos escenarios)

- [x] 1.7 **Complemento de generación de jOOQ y demostración deliberada de ruptura de compilación.**
  Cablear en `apps/api/app/pom.xml`, fase `generate-sources`, el mecanismo elegido en la tarea 1.1
  (ruta A o B de `design.md`, decisión 1): contenedor `postgres:18-alpine`, dos ubicaciones de
  Flyway (la real y `src/test/resources/db/codegen`), generador `org.jooq.meta.postgres.PostgresDatabase`
  contra el esquema `public`, `<excludes>flyway_schema_history</excludes>`, destino paquete
  `confia.generated.jooq` en `target/generated-sources/jooq`,
  `<generate><globalObjectReferences>false</globalObjectReferences>` (`design.md`, «Configuración
  común a A y B»; ADR-0021). Crear
  `apps/api/app/src/test/resources/db/codegen/beforeMigrate__create_codegen_roles.sql` con los cinco
  roles `NOLOGIN` y **sin contraseña alguna**, solo para el contenedor de generación (`design.md`,
  decisión 5). Añadir una migración mínima de prueba temporal con una sola tabla y una consulta jOOQ
  que la referencie; ejecutar `./mvnw -B -pl apps/api/app -am generate-sources compile`: verde.
  **Demostración deliberada** (sin comprometer): renombrar o eliminar la columna que la consulta
  referencia en la migración temporal, ejecutar de nuevo, y observar que la generación o la
  compilación falla — registrar el mensaje exacto en `apply-progress.md`; revertir la migración
  temporal y confirmar `git status` limpio. En el mismo commit, excluir el paquete generado de
  JaCoCo (`<excludes><exclude>confia/generated/**</exclude></excludes>` en `jacoco-report` y
  `jacoco-check` de `app`, cada exclusión con una cita `ADR-0021` a menos de cuatro líneas, exigida
  por `SuppressionCitesAdrTest`); confirmar leyendo el informe de JaCoCo que el paquete generado no
  cuenta en la cobertura. No añadir ninguna bandera de omisión de la generación (ADR-0008). —
  Especificación `build-integrity`, requisitos «Generación del código de jOOQ desde las migraciones
  de Flyway» (los tres escenarios) y «Código generado de jOOQ excluido de cobertura y de mutación»
  (ambos escenarios)

- [x] 1.8 **Medir el tiempo de la suite y documentar el requisito de Docker.** Ejecutar
  `./mvnw -B verify` completo en `apps/api` y medir el tiempo real de la fase `integration-test`
  (Failsafe). Crear `apps/api/README.md` con el requisito de Docker en la construcción (ADR-0015
  regla 2) y qué falla sin él (`generate-sources`, antes de compilar nada); añadir la nota junto a
  `test_command` en `openspec/config.yaml`; en `.github/workflows/ci.yml`, fijar un
  `timeout-minutes` explícito en el trabajo `backend`, un comentario que declare la dependencia real
  de Docker, y la mención del presupuesto de 8 minutos. Registrar el tiempo medido, no estimado. —
  Especificación `build-integrity`, requisito «Pruebas `*IT.java` con Testcontainers dentro del
  presupuesto de 8 minutos» (escenario «Suite dentro del presupuesto»); propuesta, riesgo «`./mvnw
  verify` pasa a exigir Docker...»

- [ ] 1.9 **Medir el diff real de PR A1** con
  `git diff --numstat main...change/jooq-flyway-testcontainers-wiring -- . ':(exclude)openspec' ':(exclude)docs/adr' ':(exclude)**/generated/**'`.
  Si el total cabe en 800 líneas, continuar. **Si supera 800, detener la aplicación y consultar al
  propietario**, con la subdivisión ya identificada en `design.md` §12: **A1a** (dependencias,
  `PostgresIntegrationTest`, roles y humo) y **A1b** (complemento de generación, exclusiones, fuente
  única y documentación); registrar la decisión que tome. — D2 de la propuesta; `design.md` §12,
  «Pronóstico de tamaño por corte»

- [ ] 1.10 **Verificación final de PR A1.** En checkout limpio, con `JAVA_HOME` en JDK 25 y
  `MAVEN_OPTS` con el almacén de confianza `Windows-ROOT`, ejecutar `./mvnw -B verify` en `apps/api`
  con Docker activo. Confirmar `DatabasePipelineIT` y `PostgresImageSingleSourceTest` en verde, que
  el paquete generado no aparece en el árbol de control de versiones, y que la cobertura de JaCoCo no
  decrece por el código generado. Empujar la rama `change/jooq-flyway-testcontainers-wiring` y
  confirmar en la integración continua que el trabajo `backend` termina en verde. — Criterios de
  éxito de la propuesta relativos a la construcción, Docker y el código generado

---

## PR A2 — corte A2: primera migración y adaptador

Rama `change/jooq-flyway-testcontainers-wiring-migration`, base PR A1 (el nombre debe empezar por
`change/` para que `ci.yml` lo ejecute mientras apunta a PR A1). Cierra el rojo programado de
ADR-0020 en el mismo commit que crea la primera clase de `infrastructure` (plan de reversión de la
propuesta, punto 2).

- [ ] 2.1 **ROJO — ida y vuelta, ausencia y duplicado.** Crear
  `apps/api/app/src/test/java/com/confia/organization/infrastructure/JooqInstitutionRepositoryIT.java`
  con: reconstrucción fiel de cada atributo de una fila sembrada por SQL directo (`tradeName`
  presente, RTN de 14 dígitos, moneda `HNL`); reconstrucción con `trade_name` nulo (nunca cadena
  vacía); ausencia de resultado sin excepción para un `InstitutionId` sin fila; rechazo de una
  segunda fila con el mismo identificador por violación de la clave primaria (sembrada dos veces por
  SQL directo, sin pasar por el adaptador). Falla al compilar porque no existen ni la tabla ni
  `JooqInstitutionRepository`. — Especificación `organization`, requisito «Contrato observable del
  adaptador jOOQ de `InstitutionRepository` contra la base real» (los cuatro escenarios)

- [ ] 2.2 **VERDE — migración `V1__create_organization_institution.sql`.** Crear
  `apps/api/app/src/main/resources/db/migration/V1__create_organization_institution.sql` con la
  tabla (`id UUID PRIMARY KEY`, `legal_name`, `trade_name`, `rtn`, `address`, `default_currency`,
  `locale`, `timezone`, `is_active`), las comprobaciones de RTN (`^[0-9]{1,20}$`) y de moneda
  (`HNL`/`USD`), el comentario de columna del RTN que declara la guarda técnica de 1 a 20 dígitos
  (no una regla fiscal, citando `docs/04-cumplimiento-fiscal-sar.md` §1 y `docs/09` líneas 118–122),
  `ENABLE ROW LEVEL SECURITY` + `FORCE ROW LEVEL SECURITY`, la política
  `id = NULLIF(current_setting('app.institution_id', true), '')::uuid` con `USING` **y** `WITH
  CHECK` explícitos, y los `GRANT` de `docs/03` §6.1 (`confia_admin_app`: `SELECT, INSERT, UPDATE`,
  sin `DELETE`; `confia_readonly`: `SELECT`; `confia_portal_app`: ningún privilegio sobre la tabla;
  `GRANT SELECT` sobre `flyway_schema_history` a `confia_admin_app` y `confia_portal_app`). Ejecutar
  `JooqInstitutionRepositoryIT` de la tarea 2.1 salvo los casos que dependen del adaptador (los de
  esquema, como el rechazo de PK duplicada): verde. — `design.md`, decisión 6; especificación
  `organization`, requisitos «Contrato observable...» (escenario de duplicado) y «La longitud de la
  columna del RTN es una guarda técnica, no una regla fiscal» (los tres escenarios)

- [ ] 2.3 **VERDE — adaptador jOOQ de solo lectura.** Crear
  `apps/api/app/src/main/java/com/confia/organization/infrastructure/JooqInstitutionRepository.java`
  (`final`, constructor explícito con `DSLContext`, sin anotación de Spring, implementa únicamente
  `findById` del puerto existente, sin abrir transacciones ni fijar contexto de sesión, sin exponer
  ningún tipo de `org.jooq` ni del paquete generado) y
  `.../organization/infrastructure/package-info.java` (documenta la capa de adaptadores del módulo y
  cita ADR-0015 regla 4). Conversión explícita fila↔dominio: `id` → `InstitutionId`, `trade_name`
  nulo de SQL a `null` de Java, `default_currency` → `CurrencyCode.valueOf`, `locale` →
  `Locale.forLanguageTag`, `timezone` → `ZoneId.of`, `is_active` falso aplica `deactivate()` al
  reconstruir. Actualizar el Javadoc de
  `apps/api/app/src/main/java/com/confia/organization/application/InstitutionRepository.java`: ya no
  dice que la implementación real es responsabilidad del cambio 5, sino que es este adaptador.
  Ejecutar `JooqInstitutionRepositoryIT` completa: verde. — `design.md`, decisión 8; especificación
  `organization`, requisito «Puerto de salida para cargar una institución por identificador»
  (MODIFIED)

- [ ] 2.4 **Commit único de ADR-0020: cierre del rojo programado.** En el mismo commit que crea
  `JooqInstitutionRepository` (o inmediatamente adyacente, antes de continuar): en
  `apps/api/app/src/test/java/com/confia/architecture/LayeredArchitectureTest.java`, cambiar
  `.optionalLayer("Infrastructure")` por `.layer("Infrastructure")`, retirar las dos líneas de
  comentario que citan ADR-0020 para esa capa, y actualizar el Javadoc de
  `productionLayeringRule()` para que ya no la describa como opcional; `optionalLayer("Web")`
  permanece **intacto**. En
  `apps/api/app/src/test/java/com/confia/architecture/EmptyShouldExceptionInventoryTest.java`,
  retirar la entrada vencida de `Infrastructure`; queda una sola, la de `Web`. **No editar**
  `SuppressionCitesAdrTest.java`: su conteo es dinámico y debe cuadrar solo al bajar a la vez la
  llamada y la entrada; si no cuadra, reportar la discrepancia con `design.md` §2, no ajustar el
  conteo a mano. Ejecutar `./mvnw -B verify` completo: verde. — `design.md`, decisión 11;
  especificación `build-integrity`, requisito «`infrastructure` deja de ser una capa opcional en la
  verificación de capas» (ambos escenarios); ADR-0020, alcance punto 3

- [ ] 2.5 **ROJO/VERDE — aislamiento con dos instituciones, contexto ausente y contexto vacío.**
  Extender `JooqInstitutionRepositoryIT` con el ayudante `withInstitutionContext(InstitutionId,
  Runnable)` de `PostgresIntegrationTest` (parámetro vinculado, `SELECT set_config('app.institution_id',
  ?, true)`, nunca interpolado): sembrar dos instituciones y confirmar que una sesión con el contexto
  de la primera no puede leer la fila de la segunda; que una sesión sin `app.institution_id`
  establecido recibe ausencia de resultado sin error de conversión a `uuid`; que una sesión con
  `app.institution_id` como cadena vacía recibe el mismo resultado; y que una sesión con su propio
  contexto sí lee su propia fila. Ejecutar: verde tras la política `NULLIF(...)` de la tarea 2.2. —
  Especificación `organization`, requisito «Aislamiento por fila de la tabla raíz según ADR-0009»
  (los cuatro escenarios)

- [ ] 2.6 **Medir el diff real de PR A2** con
  `git diff --numstat <base-de-PR-A1>...HEAD -- . ':(exclude)openspec' ':(exclude)docs/adr' ':(exclude)**/generated/**'`.
  Si cabe en 800 líneas, continuar. **Si supera 800, detener la aplicación y consultar al
  propietario** entre una subdivisión adicional (separar las pruebas de aislamiento del resto del
  adaptador, `design.md` §12) o una excepción de tamaño; no decidirlo sin el propietario. — D2 de la
  propuesta; `design.md` §12, «Pronóstico de tamaño por corte»

- [ ] 2.7 **Verificación final de PR A2.** En checkout limpio, ejecutar `./mvnw -B verify` en
  `apps/api` con JDK 25 y Docker activo. Confirmar `JooqInstitutionRepositoryIT` en verde completa
  (ida y vuelta, ausencia, duplicado, aislamiento, contexto ausente y vacío), y que
  `LayeredArchitectureTest`/`EmptyShouldExceptionInventoryTest`/`SuppressionCitesAdrTest` pasan con
  `Infrastructure` ya obligatoria. Empujar la rama `...-migration` (apuntando a PR A1) y confirmar en
  la integración continua que el trabajo `backend` termina en verde. — Capacidad `organization`;
  criterios de éxito de la propuesta sobre RLS, aislamiento y el adaptador

---

## PR A3 — corte A3: reglas y puertas de esquema

Rama `change/jooq-flyway-testcontainers-wiring-gates`, base PR A2. Cada regla en su propio commit;
el conteo de `SuppressionCitesAdrTest` no se toca en este corte (`design.md`, decisión 9).

- [ ] 3.1 **ROJO/VERDE — regla R1, jOOQ confinado a `infrastructure`.** ROJO: crear
  `apps/api/app/src/test/java/com/confia/architecture/JooqConfinedToInfrastructureTest.java`
  (prohíbe que una clase fuera de `..infrastructure..` dependa de `org.jooq..` o de
  `confia.generated..`) y el fixture
  `apps/api/app/src/test/java/com/confia/architecture/fixture/jooq/application/BadJooqUser.java`
  (usa `DSLContext` desde un paquete `application`); la prueba de rechazo del fixture falla al no
  existir la regla. VERDE: la regla pasa sobre `JooqInstitutionRepository` (producción real, ya no
  vacía) y rechaza el fixture nombrando la clase infractora. Sin `allowEmptyShould(true)`. —
  Especificación `build-integrity`, requisito «jOOQ y el paquete generado confinados a
  `infrastructure`» (ambos escenarios)

- [ ] 3.2 **ROJO/VERDE — regla R2, propiedad de tablas por prefijo de módulo.** ROJO: crear
  `apps/api/app/src/test/java/com/confia/architecture/TableOwnershipByModuleTest.java` (prohíbe que
  una clase de `com.confia.<módulo>..` dependa de un tipo generado cuyo nombre simple no empiece por
  `<Módulo>`, extrayendo el módulo con el mismo criterio que `NoCrossModuleDomainImportsTest`) y el
  fixture
  `apps/api/app/src/test/java/com/confia/architecture/fixture/billing/infrastructure/BadForeignTableUser.java`
  (usa `OrganizationInstitution`, el tipo generado de `organization`, desde el módulo `billing`); la
  prueba de rechazo falla al no existir la regla. VERDE: la regla rechaza el fixture y no falla sobre
  `JooqInstitutionRepository`, que solo usa su propio tipo generado. Esta regla solo es verificable
  porque `globalObjectReferences=false` ya está activo desde la tarea 1.7. — Especificación
  `build-integrity`, requisito «Propiedad de tablas generadas por prefijo de módulo» (ambos
  escenarios)

- [ ] 3.3 **ROJO/VERDE — regla R3, transacciones confinadas (guarda preventiva).** ROJO: crear
  `apps/api/app/src/test/java/com/confia/architecture/TransactionsOnlyInSharedSecurityTest.java`
  (prohíbe `@Transactional`, `TransactionTemplate` y `PlatformTransactionManager` fuera de
  `com.confia.shared.security..`, que todavía no existe) y el fixture
  `apps/api/app/src/test/java/com/confia/architecture/fixture/transactions/BadTransactionalRepository.java`
  (anotado `@Transactional`); la prueba de rechazo falla al no existir la regla. VERDE: la regla
  rechaza el fixture y no falla sobre `JooqInstitutionRepository`, que no abre ninguna transacción
  propia. La regla se evalúa sobre `productionClasses()`, que excluye `target/test-classes`, así que
  el uso libre de `@Transactional` en las pruebas de integración no la afecta. — Especificación
  `build-integrity`, requisito «Transacciones confinadas al componente único de `shared/security`
  (guarda preventiva)» (ambos escenarios)

- [ ] 3.4 **ROJO/VERDE — regla R4, SQL plano prohibido fuera de la lista aprobada.** ROJO: crear
  `apps/api/app/src/test/java/com/confia/architecture/NoUnapprovedPlainSqlTest.java` (prohíbe, por
  firma exacta, `DSL.field(String)`, `DSL.table(String)`, `DSL.condition(String)`, `DSL.sql(String)`,
  `DSLContext.fetch(String)`, `execute(String)`, `resultQuery(String)` y sus variantes con
  parámetros, salvo en una lista aprobada declarada como conjunto inmutable **vacío** hoy) y el
  fixture
  `apps/api/app/src/test/java/com/confia/architecture/fixture/jooq/infrastructure/BadPlainSqlRepository.java`
  (invoca `DSLContext.fetch(String)`); la prueba de rechazo falla al no existir la regla. VERDE: la
  regla rechaza el fixture y pasa sobre `JooqInstitutionRepository`, que no usa SQL plano. —
  Especificación `build-integrity`, requisito «Prohibición del SQL plano de jOOQ fuera de la lista
  aprobada» (ambos escenarios)

- [ ] 3.5 **ROJO/VERDE — puertas de catálogo de esquema, `MultiTenantSchemaIT`.** Crear
  `apps/api/app/src/test/java/com/confia/schema/MultiTenantSchemaIT.java` sobre el catálogo real de
  PostgreSQL con cuatro afirmaciones: (1) toda tabla base de `public` lleva `institution_id NOT
  NULL`, o pertenece por **nombre exacto** al catálogo cerrado de cuatro nombres de ADR-0017
  (`scheduled_tasks`, `event_publication`, `flyway_schema_history`, `organization_institution` —
  pertenencia, no presencia: hoy solo existen dos); (2) toda tabla con `institution_id` tiene
  `relrowsecurity` y `relforcerowsecurity` verdaderos, y la tabla raíz también, con al menos una
  política sobre su clave primaria (`pg_policies`); (3) toda restricción o índice único de una tabla
  de negocio incluye `institution_id`, salvo la tabla raíz, donde la propia `id` actúa como su
  discriminador; (4) con dos instituciones sembradas por SQL directo, una sesión con el contexto de
  la primera no ve la fila de la segunda, y sin contexto o con contexto vacío la consulta devuelve
  cero filas sin lanzar error. **Añadir además** la verificación de que el nombre de toda tabla de
  negocio lleva el prefijo `<módulo>_` de su módulo propietario salvo las tablas técnicas del
  catálogo cerrado, y que `organization_institution` no está exceptuada de ese prefijo por ser la
  tabla raíz (requisito de `build-integrity` sin punto propio en `design.md` §3, decisión 10; se
  verifica en esta misma clase por ser también una afirmación de catálogo). Falla al no existir la
  regla; pasa sobre el esquema real creado en A2. — Especificación `build-integrity`, requisitos
  «Toda tabla de negocio lleva `institution_id NOT NULL`...» (los tres escenarios), «Seguridad a
  nivel de fila habilitada y forzada en cada tabla protegida» (ambos escenarios), «Toda restricción
  única de una tabla de negocio incluye el discriminador de institución» (ambos escenarios) y
  «Nombres de tabla con el prefijo de su módulo, salvo el catálogo cerrado» (los tres escenarios)

- [ ] 3.6 **ROJO/VERDE — `RolePrivilegeMatrixIT`.** Crear
  `apps/api/app/src/test/java/com/confia/schema/RolePrivilegeMatrixIT.java` con: (5) para los cinco
  roles de `docs/03` §6.1, `rolsuper = false` y `rolbypassrls = false` (refuerzo de catálogo,
  complementario al chequeo ya hecho en `DatabasePipelineIT` de A1); (6) matriz de privilegios con
  `has_table_privilege` sobre las tablas existentes: `confia_admin_app` con `SELECT`/`INSERT`/
  `UPDATE` y sin `DELETE` sobre `organization_institution`; `confia_portal_app` sin ningún privilegio
  sobre `organization_institution` y con `SELECT` sobre `flyway_schema_history`; `confia_readonly`
  solo `SELECT`. Falla al no existir la clase; pasa contra los `GRANT` de la migración de A2. — No
  hay requisito exclusivo en la especificación para esta clase; trazado a `design.md`, decisión 10,
  puntos 5 y 6, y a los criterios de éxito de la propuesta sobre privilegios de rol

- [ ] 3.7 **Medición final del tiempo de la suite y cierre documental.** Ejecutar `./mvnw -B verify`
  completo en `apps/api` y medir de nuevo el tiempo de la fase `integration-test` con la suite
  `*IT.java` completa (`DatabasePipelineIT`, `JooqInstitutionRepositoryIT`, `MultiTenantSchemaIT`,
  `RolePrivilegeMatrixIT`). Actualizar `apps/api/README.md` y la nota de
  `openspec/changes/foundations-plan/exploration.md` (división del cambio 5) si el tiempo medido
  cambia alguna afirmación anterior. — Especificación `build-integrity`, requisito «Pruebas
  `*IT.java` con Testcontainers dentro del presupuesto de 8 minutos» (escenario «Suite dentro del
  presupuesto», medición final)

- [ ] 3.8 **Medir el diff real de PR A3** con
  `git diff --numstat <base-de-PR-A2>...HEAD -- . ':(exclude)openspec' ':(exclude)docs/adr' ':(exclude)**/generated/**'`.
  Si cabe en 800 líneas, continuar. **Si supera 800, detener la aplicación y consultar al
  propietario**, con la subdivisión ya identificada en `design.md` §12: **A3a** (R1 y R3 con sus
  fixtures), **A3b** (R2 y R4 con sus fixtures), **A3c** (puertas de esquema, fusionable con A2 si A2
  quedó corto). — D2 de la propuesta; `design.md` §12, «Pronóstico de tamaño por corte»

- [ ] 3.9 **Verificación final de PR A3 y del cambio completo.** En checkout limpio, con `JAVA_HOME`
  en JDK 25, `MAVEN_OPTS` con el almacén `Windows-ROOT` y Docker activo, ejecutar `./mvnw -B verify`
  en `apps/api`. Confirmar: las cuatro reglas R1–R4 en verde sobre producción y rechazando sus
  fixtures; `MultiTenantSchemaIT` y `RolePrivilegeMatrixIT` en verde; el inventario de ADR-0020 con
  una sola entrada restante (`Web`); el código generado ausente del repositorio y fuera de las
  métricas de JaCoCo y PIT; la suite `*IT.java` completa por debajo de 8 minutos, con el tiempo
  medido reportado. Empujar la rama `...-gates` (apuntando a PR A2) y confirmar en la integración
  continua que el trabajo `backend` termina en verde. — Todos los criterios de éxito de la propuesta
