# Diseño: componente transaccional único y bitácora de auditoría encadenada

Cambio 5 de F0, **parte B**. Implementa la propuesta aprobada
(`openspec/changes/audit-log-and-transaction-runner/proposal.md`) y las dos especificaciones delta
ya escritas: `specs/audit-trail/spec.md` (10 requisitos, 27 escenarios) y
`specs/build-integrity/spec.md` (4 requisitos, 15 escenarios). **Las especificaciones son el
contrato**: donde este diseño proponga un dato que la especificación fija, prevalece la
especificación y las tablas de aquí se alinean antes de la fase de tareas. La sección 7 traza los
41 escenarios contra su prueba, para que ninguno quede sin camino de implementación.

Decisiones del propietario ya tomadas, que este diseño **implementa y no reabre**: capacidad nueva
`audit-trail` con el componente transaccional dentro de `build-integrity` (D1, 2026-09-21); **una
cadena de hash por institución**, cada una con su registro génesis, y clave primaria compuesta
`(institution_id, id)` (P2 y P3, 2026-09-21); ancla externa fuera de alcance, del cambio 11;
presupuesto de 800 líneas de cambio efectivo por pull request con entrega en cortes encadenados.

---

## 1. Enfoque técnico

De adentro hacia afuera, en cinco cortes encadenados. Cada corte deja `./mvnw verify` en verde y
sigue TDD estricto (`openspec/config.yaml`, `strict_tdd: true`).

| Corte | Contenido |
|---|---|
| **B1** | Componente transaccional en `com.confia.shared.security`, `CommittingPostgresIntegrationTest`, extracción del contenedor a un titular perezoso, jqwik en `app`, mitad positiva de la regla R3 |
| **B2a** | `shared_audit_log` y `shared_audit_chain_head` sin encadenamiento: columnas, restricciones, índices, seguridad a nivel de fila, `REVOKE`/`GRANT`, disparadores de solo inserción; extensión de `RolePrivilegeMatrixIT`; **toda la corrección documental del nombre de la tabla** |
| **B2b** | Serialización canónica en PL/pgSQL, disparador `BEFORE INSERT`, asignación del identificador por institución, registro génesis, restricción única anti-bifurcación, pruebas de encadenamiento y de concurrencia |
| **B3** | Serialización canónica en Java, verificador de cadena con su puerto y su adaptador, prueba de manipulación con `SUPERUSER`, prueba del límite conocido, prueba cruzada con jqwik |
| **B4** | Regla de nomenclatura `*IT` (deuda W3) con su fixture negativo permanente |

### 1.1 El orden de B1 antes de B2a es una dependencia mecánica, verificada

`MultiTenantSchemaIT.everyBusinessTableNameCarriesItsOwnerModulesPrefixExceptTheClosedCatalogue`
(líneas 116–136) exige que el prefijo de toda tabla de negocio nombre un módulo **que exista de
verdad**. El conjunto de módulos lo deriva `productionModuleNames()` (líneas 148–156):

```java
new ClassFileImporter()
        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
        .importPackages("com.confia").stream()
        .map(JavaClass::getPackageName)
        .filter(name -> name.startsWith("com.confia."))
        .map(name -> name.substring("com.confia.".length()).split("\\.")[0])
        .collect(Collectors.toUnmodifiableSet());
```

`DO_NOT_INCLUDE_TESTS` excluye `target/test-classes`. Verificado por lectura del árbol: hoy no
existe **ninguna** clase bajo `apps/api/app/src/main/java/com/confia/shared/`, de modo que el
conjunto de módulos es `{organization, bootstrap, ...}` y **no contiene `shared`**. Una migración
que cree `shared_audit_log` antes de que exista una clase de producción en `com.confia.shared.*`
rompe esa puerta con el mensaje «business table shared_audit_log must be prefixed with the name of
a module that really exists».

No es preferencia de orden: es la misma clase de invariante que en la parte A ataba la primera
clase de `infrastructure` a la retirada del `optionalLayer`. Consecuencia para el plan de reversión:
**B1 y B2a no se revierten por separado** (propuesta, «Plan de reversión», punto 2).

Además, el prefijo se extrae con `table.name().indexOf('_')`, es decir el texto **antes del primer
guion bajo**: `shared_audit_log` → `shared` y `shared_audit_chain_head` → `shared`. Ambas tablas
pasan la puerta con la misma clase de producción.

### 1.2 La clase base de confirmación real nace con el componente, no con la bitácora

La verificación 7 de ADR-0015 exige una prueba de concurrencia con transacciones reales confirmadas
que fuerce un error de serialización. Eso es de B1, no de B3. Esto corrige la división de tres
cortes de la exploración y confirma la de la propuesta.

---

## 2. Evidencia obtenida en esta fase y límites

Esta fase **no dispuso de herramienta de ejecución de procesos** (ni Maven, ni Docker, ni
PostgreSQL, ni shell), igual que la fase de diseño de la parte A. Todo lo de abajo es lectura de
archivos reales del árbol. Lo que exige ejecutar algo está marcado como **no verificado** y aparece
como sonda numerada en la sección 10; ninguna decisión de este documento afirma como comprobado un
comportamiento que no se comprobó.

| Pregunta | Resultado | Evidencia |
|---|---|---|
| ¿`productionModuleNames()` deriva los módulos del código de producción real? | **Verificado.** Sí, con `DO_NOT_INCLUDE_TESTS` sobre `com.confia`. Sin clase en `com.confia.shared.*` el módulo `shared` no existe | `MultiTenantSchemaIT.java:148-156`, `116-136` |
| ¿La clave primaria compuesta `(institution_id, id)` obliga a tocar `MultiTenantSchemaIT`? | **Verificado: no.** `everyUniqueIndexOfABusinessTableIncludesTheInstitutionDiscriminator` (líneas 84–96) exige que todo índice único de una tabla distinta de la raíz contenga `institution_id`; la clave primaria compuesta y la restricción única anti-bifurcación `(institution_id, prev_hash)` lo contienen. **P2 queda cerrada sin modificar la puerta** | `MultiTenantSchemaIT.java:84-96` |
| ¿Existe hoy `com.confia.shared.*`? | **Verificado: no existe** ninguna clase, ni de producción ni de prueba | Listado de `apps/api/app/src/main/java/com/confia/` |
| ¿Hay una conexión `SUPERUSER` disponible en Testcontainers? | **Verificado por lectura.** El contenedor arranca con `withUsername("postgres")` y `withPassword(TEST_PASSWORD)`; la parte A confirmó por observación directa que `POSTGRES_USER` nace con `rolsuper = t` y `rolbypassrls = t` | `PostgresIntegrationTest.java:48-58`; `design.md` de la parte A, nota de la sonda S1.5 |
| ¿`jqwik` está disponible en el módulo `app`? | **Verificado: NO.** El POM padre lo declara en `dependencyManagement` (`jqwik.version` 1.10.1, líneas 72–75 y 128–133) pero **solo `kernel/pom.xml` lo declara como dependencia**. `app/pom.xml` no lo trae. **La propuesta lo daba por presente y no lo está**: añadirlo es trabajo de B1 | `apps/api/pom.xml:72-75,128-133`; `apps/api/kernel/pom.xml:39-41`; `apps/api/app/pom.xml:25-114` |
| ¿Dónde puede vivir el componente transaccional sin romper `LayeredArchitectureTest`? | **Verificado por lectura, pendiente de sonda.** Las cuatro capas se definen por los segmentos `..domain..`, `..application..`, `..infrastructure..` y `..web..`, y la regla usa `consideringOnlyDependenciesInLayers()`, que marca irrelevante toda dependencia cuyo origen **o destino** no encaje en ninguna capa declarada. `com.confia.shared.security` no lleva segmento de capa, así que ni entra en una capa ni sus dependencias entrantes se evalúan. **P5 se resuelve sin supresión y sin excepción de ADR** | `LayeredArchitectureTest.java:62-103`, y su Javadoc sobre `consideringOnlyDependenciesInLayers()` verificado con `javap` en la parte A. Sonda S7 |
| ¿R2 (propiedad de tablas) afecta al verificador? | **Verificado.** `moduleOf()` devuelve el segmento inmediatamente anterior al primer segmento de capa. `com.confia.shared.audit.infrastructure` daría módulo `audit` y exigiría un tipo generado con prefijo `Audit`, mientras el tipo real será `SharedAuditLog`: **rompería R2**. `com.confia.shared.infrastructure` da módulo `shared` y prefijo `Shared`, que sí encaja. Decisión 3 | `TableOwnershipByModuleTest.java:89-97,52-78` |
| ¿R1 permite que el componente transaccional use jOOQ? | **Verificado: no.** R1 prohíbe que una clase fuera de `..infrastructure..` dependa de `org.jooq..` o `confia.generated..`. El componente vive en `com.confia.shared.security`, sin segmento de capa: **no puede tocar jOOQ**. Decisión 1 | `JooqConfinedToInfrastructureTest.java:19-23` |
| ¿R4 permite `dsl.execute("select set_config(...)")` en producción? | **Verificado: no.** R4 rechaza `DSLContext.fetch/execute/resultQuery(String, ...)` y `DSL.field/table/condition/sql(String, ...)` fuera de una lista aprobada **hoy vacía**. El componente usa JDBC de Spring, no jOOQ, y por eso no entra en el alcance de R4 | `NoUnapprovedPlainSqlTest.java:36-45,60-90` |
| ¿Puede el componente registrarse como bean de Spring? | **Verificado: hoy no tiene sentido.** Los tres procesos excluyen `DataSourceAutoConfiguration` y escanean solo `com.confia.bootstrap`; el único `DSLContext` del árbol lo declara `IntegrationTestApplication`, que es código de prueba. Se sigue el precedente de `JooqInstitutionRepository`: clase `final` con constructor explícito, sin anotación de Spring | `AdminApplication.java:23`, `PortalApplication.java:20`, `WorkerApplication.java:17`, `IntegrationTestApplication.java:40-42`, `JooqInstitutionRepository.java:33-35` |
| ¿Qué límite de reintentos fija la documentación? | **Verificado: tres, con retroceso.** «El servidor reintenta de forma automática hasta tres veces con retroceso, y solo después devuelve error al cliente» | `docs/adr/ADR-0010-idempotencia-y-concurrencia-financiera.md:203-205` |
| ¿Hace falta `pgcrypto` para SHA-256? | **Verificado por lectura de la documentación de PostgreSQL, pendiente de sonda.** `sha256(bytea) → bytea` es función interna desde PostgreSQL 11; `pgcrypto` no hace falta. **Es determinante**: Flyway migra como `confia_owner`, que no es `SUPERUSER`, y `pgcrypto` no es una extensión de confianza, así que un `CREATE EXTENSION pgcrypto` en la migración **fallaría** | `create-test-roles.sql:11`; sonda S6 |
| ¿`shared_audit_log` rompe alguna puerta de catálogo existente? | **Verificado: no.** Lleva `institution_id NOT NULL`, tendrá seguridad a nivel de fila habilitada y forzada, y sus dos índices únicos contienen `institution_id`. Pasa las cuatro puertas genéricas sin lista de exclusión | `MultiTenantSchemaIT.java:51-96` |
| ¿`has_table_privilege('confia_owner', ..., 'UPDATE')` será falso? | **No verificado, pero determinante por diseño.** El propietario del esquema conserva todos los privilegios por definición; el rechazo al propietario lo produce **el disparador**, no el sistema de permisos. La fila de la matriz para `confia_owner` debe afirmar `true` en `UPDATE`, y la prueba de rechazo es independiente. Decisión 8 | `docs/03-seguridad.md:1390-1418`; `specs/audit-trail/spec.md`, escenario «`UPDATE` y `DELETE` rechazados incluso para el propietario del esquema» |
| ¿jqwik admite extensiones de JUnit Jupiter, es decir `@SpringBootTest`? | **No verificado.** jqwik es un motor propio de la plataforma JUnit, no un motor Jupiter; por documentación no ejecuta extensiones de Jupiter. Si se confirma, la prueba cruzada **no puede** extender `PostgresIntegrationTest`, que es `@SpringBootTest`. Decisión 6 y sonda S8 | `PostgresIntegrationTest.java:42-43` |
| ¿`to_json(text)` produce el escape exacto de RFC 8785 para cadenas? | **No verificado.** La expectativa es: `\"`, `\\`, `\b`, `\f`, `\n`, `\r`, `\t`, `\u00xx` en minúsculas para el resto de C0, sin escapar `/` ni el texto no ASCII. Sonda S1 | — |
| ¿`numeric::text` usa alguna vez notación exponencial? | **No verificado.** La expectativa es que no, para todo valor representable por `numeric`. Sonda S2 | — |
| ¿`INSERT ... ON CONFLICT DO UPDATE ... RETURNING` serializa a dos escritores concurrentes de la misma institución? | **No verificado.** Es el mecanismo elegido para el componente secuencial y para la exclusión mutua de la cadena. Sonda S4 | — |
| ¿Qué tipo Java genera jOOQ 3.21 para una columna `inet`? | **No verificado.** Si no es `String`, hace falta un `<forcedType>` en la configuración de generación. Sonda S9 | `apps/api/app/pom.xml:286-308` |

**Consecuencia de método.** Donde una decisión depende de un comportamiento no comprobado, este
diseño nombra la sonda y **deja una alternativa ya diseñada con su criterio de conmutación**, en vez
de afirmar como verificado lo que no se ejecutó.

---

## 3. Decisiones de arquitectura

### Decisión 1 — Dónde viven las clases nuevas, y por qué sin supresiones

**Elección.** Cuatro paquetes, ninguno de ellos con segmento de capa salvo el adaptador de jOOQ:

```
com.confia.shared.security/          TransactionRunner, SecurityContext, IsolationLevel
com.confia.shared.audit/             AuditChainVerifier, AuditChainVerification, AuditRowSnapshot,
                                     CanonicalAuditRow, CanonicalAuditRowSerializer, AuditLogReader (puerto)
com.confia.shared.infrastructure/    JooqAuditLogReader (única clase que toca jOOQ)
```

**Por qué encaja con cada regla entregada**, verificado por lectura en la sección 2:

| Regla | Efecto sobre estos paquetes |
|---|---|
| `LayeredArchitectureTest` | `shared.security` y `shared.audit` no encajan en ninguna capa; con `consideringOnlyDependenciesInLayers()` sus dependencias entrantes y salientes son irrelevantes. `shared.infrastructure` sí es capa `Infrastructure`, y solo depende de tipos sin capa. **Ninguna supresión, ninguna excepción de ADR-0008** |
| R1 `JooqConfinedToInfrastructureTest` | Solo `shared.infrastructure` toca `org.jooq..` y `confia.generated..` |
| R2 `TableOwnershipByModuleTest` | `moduleOf("com.confia.shared.infrastructure")` = `shared`, prefijo esperado `Shared`, tipo generado `SharedAuditLog`: encaja. `shared.security` y `shared.audit` no tienen segmento de capa, así que `moduleOf` devuelve vacío y la regla los omite |
| R3 `TransactionsOnlyInSharedSecurityTest` | La condición exime por `getPackageName().startsWith("com.confia.shared.security")`: el componente queda exento y todo lo demás sigue prohibido |
| R4 `NoUnapprovedPlainSqlTest` | Ninguna clase nueva llama a un punto de entrada de SQL plano de jOOQ. **La lista aprobada sigue vacía** |
| `NoTechnicalLayerPackageNamesTest` | `security`, `audit` e `infrastructure` no están en la lista prohibida |
| JaCoCo | Ninguno encaja con `com.confia.*.domain`: aplica solo la regla `BUNDLE` del 80 % de línea y rama |
| PIT | El selector es `com.confia.*.domain.*`: **ninguna clase nueva entra en la puerta de mutación** |

**Alternativas descartadas.**

- `com.confia.shared.audit.infrastructure` para el adaptador: `moduleOf` devolvería `audit` y R2
  exigiría un tipo generado con prefijo `Audit`, cuando el real es `SharedAuditLog`. **Rompe una
  puerta entregada y en verde.** Descartada.
- `com.confia.audit.*` como módulo propio: obligaría a renombrar la tabla a `audit_log`,
  contradiciendo la decisión de `foundations-plan` §2 y la especificación ya escrita. Descartada.
- Poner la serialización canónica en `com.confia.shared.audit.domain` para ganar la puerta del 95 %
  y la de mutación: es atractivo (es lógica pura y de alto valor probatorio) pero inventa un módulo
  `audit` que no existe y arrastra a R2 el día que el adaptador comparta prefijo. **Se compensa de
  otro modo**: la decisión 12 fija una puerta de cobertura explícita para el serializador, sin
  fabricar un módulo.

**Excepción de `foundations-plan` §2 que este diseño retira.** `foundations-plan` anticipaba que
`application` llamaría al componente «con una excepción documentada a la regla
`application-no-infrastructure` y comentario del ADR que la autoriza». Verificado en
`LayeredArchitectureTest`: **esa excepción no hace falta**, porque el componente no vive en ninguna
capa. Se registra aquí para que nadie la escriba de todos modos, y queda sujeta a la sonda S7.

### Decisión 2 — El componente transaccional no toca jOOQ: `DataSource` más `TransactionTemplate`

**Elección.** `TransactionRunner` es una clase `final` de `com.confia.shared.security`, sin
anotación de Spring, con constructor explícito sobre `PlatformTransactionManager`, `DataSource` y
un límite de reintentos. Ejecuta así:

1. Construye un `TransactionTemplate` con el nivel de aislamiento pedido (`READ_COMMITTED` por
   defecto, `SERIALIZABLE` donde ADR-0010 lo exija). Spring traduce el nivel a
   `Connection.setTransactionIsolation(...)` sobre la conexión real: nada de SQL a mano.
2. Dentro de la transacción, obtiene la conexión ligada con
   `DataSourceUtils.getConnection(dataSource)` y ejecuta **como primera sentencia**, con cuatro
   parámetros vinculados:

   ```sql
   SELECT set_config('app.actor_id',       ?, true),
          set_config('app.actor_kind',     ?, true),
          set_config('app.institution_id', ?, true),
          set_config('app.request_id',     ?, true)
   ```

3. Ejecuta el caso de uso.
4. Ante fallo por serialización o interbloqueo, reintenta hasta **tres** veces con retroceso
   (ADR-0010 línea 203), y al agotarlas propaga el error original sin reintentar de nuevo.

**Por qué JDBC de Spring y no jOOQ.** R1 prohíbe `org.jooq..` fuera de `..infrastructure..`, y el
componente vive fuera de toda capa (decisión 1). Con JDBC de Spring el componente depende de
`javax.sql.DataSource`, `org.springframework.jdbc.datasource.DataSourceUtils` y
`org.springframework.transaction.support.TransactionTemplate`, ninguno prohibido. jOOQ participa en
la misma transacción sin que el componente lo sepa, porque el `DSLContext` se construye sobre un
`TransactionAwareDataSourceProxy` (`IntegrationTestApplication:40-42`), que es el mecanismo que la
parte A ya dejó probado.

**Valores ausentes.** Un actor de sistema no tiene `actor_id`. Se pasa **cadena vacía**, nunca
`NULL` de SQL, para que la política caiga en la rama `NULLIF(current_setting(...), '')` que la parte
A ya dejó probada como denegación cerrada (`V1__create_organization_institution.sql:35-44`). Pasar
`NULL` a `set_config` deja el ajuste en un estado que la política no distingue del vacío, y no hay
razón para tener dos caminos.

**Detección del error de serialización.** Se reintenta cuando la excepción es
`org.springframework.dao.ConcurrencyFailureException` **o** cuando la `SQLException` raíz lleva
`SQLState` `40001` (serialization_failure) o `40P01` (deadlock_detected). La segunda condición es
redundante si el traductor de excepciones de Spring está bien configurado, y precisamente por eso se
escribe: una traducción distinta no debe convertir el control en un adorno.

**Retroceso.** Espera de `base * intento` con una fracción aleatoria, con `base` parametrizable y
por defecto pequeña (del orden de decenas de milisegundos). El valor exacto lo fija la
implementación; lo que el diseño fija es que **hay** retroceso, que es acotado y que la espera no se
usa como mecanismo de sincronización en ninguna prueba.

**Alternativa descartada:** `@Transactional` sobre un método del componente. Satisface R3 igual, pero
el nivel de aislamiento tendría que ser un valor de anotación, es decir constante de compilación, y
ADR-0010 exige elegirlo por caso de uso en ejecución. `TransactionTemplate` es la forma que admite un
nivel variable sin proxies.

### Decisión 3 — Estructura física: dos tablas, no una

**Elección.** `shared_audit_log` (la bitácora) y `shared_audit_chain_head` (el estado de cadena por
institución: el siguiente identificador a asignar). La segunda existe por una razón concreta: es
**el objeto estable sobre el que se serializan dos escritores concurrentes de la misma institución**,
y de paso resuelve el componente secuencial por institución con el mismo bloqueo.

```sql
CREATE TABLE shared_audit_chain_head (
    institution_id UUID   NOT NULL,
    next_id        BIGINT NOT NULL,
    CONSTRAINT shared_audit_chain_head_pk PRIMARY KEY (institution_id),
    CONSTRAINT shared_audit_chain_head_next_id_chk CHECK (next_id >= 2)
);
```

Pasa las cuatro puertas genéricas: lleva `institution_id NOT NULL`, recibe seguridad a nivel de fila
habilitada y forzada con la misma política por institución, su único índice único es la clave
primaria y contiene el discriminador, y su nombre lleva el prefijo del módulo `shared`.

**Ningún rol de aplicación recibe privilegio alguno sobre ella.** Solo la escribe el disparador, que
es `SECURITY DEFINER` y corre como `confia_owner` (decisión 5). Así `confia_admin_app` puede
insertar en la bitácora sin poder tocar el estado de la cadena, que es la propiedad que se quiere.

### Decisión 4 — P2: el componente secuencial por institución

**Elección: un contador por institución en `shared_audit_chain_head`, asignado con un `INSERT ...
ON CONFLICT DO UPDATE ... RETURNING` dentro del disparador.**

```sql
INSERT INTO shared_audit_chain_head (institution_id, next_id)
VALUES (NEW.institution_id, 2)
ON CONFLICT (institution_id) DO UPDATE
    SET next_id = shared_audit_chain_head.next_id + 1
RETURNING next_id - 1
INTO assigned_id;
```

Una sola sentencia que hace cuatro cosas a la vez:

1. **Crea la cabecera** la primera vez que una institución audita algo, devolviendo `1` (la rama de
   inserción pone `next_id = 2` y devuelve `2 - 1`). Esa fila `id = 1` es el **registro génesis** de
   esa institución.
2. **Asigna el siguiente identificador** en las veces sucesivas, devolviendo el valor anterior.
3. **Toma el bloqueo de fila** sobre la cabecera de esa institución, de modo que un segundo escritor
   de la misma institución queda bloqueado hasta que el primero confirme o revierta. Es el punto de
   serialización que el encadenamiento necesita.
4. **No deja huecos**: a diferencia de una secuencia, la asignación es transaccional. Si la
   transacción revierte, `next_id` vuelve a su valor anterior y el identificador se reutiliza.

**Alternativas evaluadas contra concurrencia y contra el disparador:**

| Opción | Concurrencia | Veredicto |
|---|---|---|
| **`BIGSERIAL` global con clave primaria `(institution_id, id)`** | La secuencia es no transaccional: deja huecos al revertir, y **no aporta ningún bloqueo**, así que dos insertores de la misma institución leerían el mismo `prev_hash` y bifurcarían la cadena | **Descartada.** Resuelve el identificador y no resuelve el problema real, que es la exclusión mutua |
| **`MAX(id) + 1` por institución** | Condición de carrera clásica bajo `READ COMMITTED`; además `CLAUDE.md` regla 8 y ADR-0015 regla 8 prohíben expresamente el patrón para correlativos | **Descartada**, y se nombra para que nadie la proponga al implementar |
| **Una secuencia de PostgreSQL por institución, creada dinámicamente** | DDL en tiempo de ejecución, catálogo que crece sin límite, sin transaccionalidad y con migraciones que dejan de ser reproducibles | **Descartada** |
| **`SELECT ... FOR UPDATE` sobre la última fila de la bitácora** | El objeto bloqueado no es estable: otra transacción puede insertar una fila más nueva, así que el bloqueo no impide la bifurcación. Además `SELECT ... FOR UPDATE` exige privilegio `UPDATE` sobre la tabla, que `confia_admin_app` no debe tener | **Descartada** |
| **`pg_advisory_xact_lock(hashtext(institution_id::text))`** | Serializa bien y no necesita tabla, pero el espacio de claves es de 64 bits derivado de un hash: dos instituciones pueden compartir cerradura (solo contención, no incorrección). No resuelve el identificador y es invisible en el catálogo, así que ninguna puerta de esquema puede comprobarlo | **Alternativa de respaldo** si la sonda S5 revelara que `SECURITY DEFINER` más seguridad a nivel de fila forzada sobre la cabecera no funciona como se espera |
| **Contador por institución con `ON CONFLICT DO UPDATE`** (elegida) | Serializa sobre un objeto estable que existe desde la primera auditoría, asigna identificadores contiguos por institución y sin huecos, y vive en el catálogo donde las puertas lo ven | **Elegida** |

**Segunda barrera, independiente del bloqueo.** La tabla lleva además:

```sql
CONSTRAINT shared_audit_log_prev_hash_uq UNIQUE (institution_id, prev_hash)
```

Dos filas de una misma institución no pueden encadenar contra el mismo padre. Si el bloqueo fallara
—por un error de implementación, por un `SECURITY DEFINER` mal configurado, por cualquier motivo—,
la bifurcación **no ocurriría en silencio**: la segunda inserción fallaría con violación de unicidad
y el componente transaccional la reintentaría. La misma restricción garantiza, sin ninguna lógica
adicional, que cada institución tenga **exactamente un registro génesis**, porque solo una fila
puede llevar los 32 bytes de ceros.

No es un control de seguridad nuevo: es la restricción de integridad del mecanismo de
encadenamiento que `docs/03` §12.1 ya ordena, escrita donde el motor la puede imponer.

### Decisión 5 — El disparador, en dos funciones: una pura y una con privilegio definidor

**Elección.** Tres objetos de base de datos, en vez de uno:

| Objeto | Volatilidad | Privilegio | Qué hace |
|---|---|---|---|
| `shared_audit_canonical_json(jsonb) → text` | `STABLE` | invocador | Forma canónica recursiva de un valor JSON (decisión 6) |
| `shared_audit_row_preimage(...) → bytea` y `shared_audit_row_hash(prev_hash bytea, ...) → bytea` | `STABLE` | invocador | Preimagen canónica y su SHA-256 sobre los 18 campos firmados (los 15 de `docs/03` §12.1 más `actor_label`, `user_agent` y `trace_id`, decisión del propietario del 2026-09-21) |
| `shared_audit_log_chain()` | disparador | **`SECURITY DEFINER`**, con `SET search_path = pg_catalog, public` | Asigna `NEW.id`, resuelve `NEW.prev_hash`, calcula `NEW.row_hash` |

**Por qué el cálculo se expone como función pura invocable.** Porque la prueba cruzada con jqwik
tiene que comparar **exactamente el código que el disparador ejecuta** contra el de Java, sobre
entradas generadas, sin insertar filas. Si la canonicalización viviera dentro del cuerpo del
disparador, la prueba de propiedades tendría que insertar una fila por cada intento —con su
institución, su cadena y su política de fila— y acabaría probando el camino de inserción en vez del
algoritmo. Con una función pura, la propiedad es una llamada por intento y el disparador es una
línea que la invoca: **una sola implementación en PL/pgSQL, verificada por la propiedad y usada por
el disparador**.

**Por qué `SECURITY DEFINER` solo en el disparador.** El disparador necesita escribir
`shared_audit_chain_head`, y el objetivo es que `confia_admin_app` **no** tenga ningún privilegio
sobre esa tabla. Correr el disparador como propietario del esquema es lo que separa ambas cosas.
`SET search_path = pg_catalog, public` en la definición de la función es el endurecimiento estándar
obligado de todo `SECURITY DEFINER`: sin él, un `search_path` de sesión manipulado podría desviar la
resolución de nombres. No es un control nuevo; es la forma correcta de usar el mecanismo que
`docs/03` §12.1 ordena.

Con `FORCE ROW LEVEL SECURITY` activa sobre `shared_audit_chain_head`, el propietario **sigue
sujeto a la política**, de modo que `SECURITY DEFINER` no abre ninguna puerta de aislamiento: la
cabecera que el disparador lee y escribe es siempre la de la institución del contexto de sesión.
Pendiente de la sonda S5.

**Cuerpo del disparador `BEFORE INSERT FOR EACH ROW`:**

```text
1. Asignar NEW.id con el INSERT ... ON CONFLICT ... RETURNING de la decisión 4.
   (Toma el bloqueo por institución.)
2. Leer el row_hash de la fila anterior de la MISMA institución:
      SELECT row_hash INTO prev
        FROM shared_audit_log
       WHERE institution_id = NEW.institution_id
       ORDER BY id DESC
       LIMIT 1;
   Si no hay ninguna, prev := '\x0000...00' (32 bytes de ceros).
3. NEW.prev_hash := prev
4. NEW.row_hash  := shared_audit_row_hash(prev, NEW.id, NEW.institution_id, NEW.occurred_at, ...)
5. RETURN NEW
```

El paso 2 es seguro **porque el paso 1 ya tomó el bloqueo**: cualquier otro insertor de esa
institución está detenido. Bajo `READ COMMITTED`, al liberarse el bloqueo el segundo escritor
vuelve a leer y ve la fila recién confirmada. Bajo `SERIALIZABLE`, puede abortar con `40001`, y ahí
es donde el reintento acotado de la decisión 2 cierra el círculo: **los dos controles de este cambio
se sostienen mutuamente, y eso se prueba**.

**El disparador ignora lo que el llamador haya pasado** en `id`, `prev_hash` y `row_hash`: los
sobrescribe siempre. Eso convierte el escenario «la fila almacenada tiene ambos valores calculados
por el disparador, no por ningún valor que el caso de uso haya podido pasar» en una prueba directa:
se inserta con valores deliberadamente falsos y se afirma que fueron reemplazados.

**Consecuencia documental:** `docs/03` §12.3 concede `USAGE, SELECT ON SEQUENCE audit_log_id_seq`.
Con el identificador asignado por el disparador **no existe ninguna secuencia**, así que esa línea
desaparece en la corrección documental del corte B2a.

### Decisión 6 — P1: el algoritmo de serialización canónica

Es el riesgo técnico principal del cambio: se escribe dos veces, en PL/pgSQL y en Java, y una
divergencia silenciosa produce **falsos positivos de manipulación**, que destruyen la credibilidad
del control, que es todo su valor.

**Elección: no se adopta RFC 8785 completo. Se adopta un encabezado versionado más una codificación
de campos con longitud explícita sobre la lista fija de `docs/03` §12.1, y dentro de ella sí se
adopta la regla de escape de cadenas de RFC 8785, con dos desviaciones declaradas.**

#### 6.1 Por qué no RFC 8785 tal cual

RFC 8785 (JSON Canonicalization Scheme) exige dos cosas caras:

1. **Serialización de números según ECMAScript**, es decir el algoritmo de la representación más
   corta que reproduce el doble IEEE-754. Implementarlo en PL/pgSQL significa portar Ryū o
   equivalente. Además **pierde precisión**: `jsonb` guarda los números como `numeric`, que es
   exacto y de precisión arbitraria, y forzarlos por un doble de 64 bits convierte un dato fiel en
   uno aproximado dentro de un control probatorio sobre dinero.
2. **Orden de claves por unidades de código UTF-16**. PostgreSQL no tiene UTF-16 en ningún sitio;
   reproducir ese orden en SQL exige convertir cada clave a UTF-16 y comparar, o replicar la regla
   de sustitutos a mano.

Las dos son exactamente el tipo de superficie donde dos implementaciones divergen en silencio.
Adoptar RFC 8785 porque es un estándar y pagar su complejidad en PL/pgSQL sería elegir la autoridad
del documento por encima de la reducción del riesgo que el documento existe para reducir.

#### 6.2 Estructura de la preimagen

La preimagen es una concatenación de bytes, sin separadores ni delimitadores, porque cada elemento
lleva su longitud:

```text
preimage = utf8("confia.audit.v1")            -- etiqueta de versión de formato, sin prefijo
        || prev_hash                           -- 32 bytes crudos
        || F(id) || F(institution_id) || F(occurred_at) || F(actor_id) || F(actor_kind)
        || F(actor_label) || F(source_ip) || F(user_agent) || F(request_id) || F(trace_id)
        || F(action) || F(entity_type) || F(entity_id)
        || F(outcome) || F(before_value) || F(after_value) || F(reason) || F(approver_id)

row_hash = sha256(preimage)
```

Y para cada campo, en ese **orden fijo, escrito literalmente en las dos implementaciones**:

```text
F(v) = 0x00                                        si v IS NULL
F(v) = 0x01 || int8_be(byte_length(canon(v))) || utf8(canon(v))   en otro caso
```

`int8_be` son ocho bytes en orden de red (`int8send(bigint)` en PostgreSQL,
`ByteBuffer.allocate(8).putLong(n)` en Java).

Tres propiedades que esta forma compra, y que motivan elegirla:

1. **No hay orden de claves que acordar en el nivel exterior.** La lista de 18 campos es fija y está
   escrita en las dos implementaciones, una debajo de otra. Una divergencia de orden no es un error
   sutil de comparador: es una línea distinta, visible en una revisión de diez segundos.
2. **`NULL`, cadena vacía y ausencia son tres cosas distintas por construcción.** El marcador
   `0x00`/`0x01` y la longitud explícita lo garantizan sin ninguna convención adicional. Esa es la
   familia de divergencia D5 de la sección 6.5, resuelta por diseño y no por disciplina.
3. **La longitud cuenta bytes UTF-8, no caracteres.** Es la familia D11: en Java, `String.length()`
   cuenta unidades de código UTF-16, y usarla en lugar de `getBytes(UTF_8).length` es el error más
   fácil de cometer aquí. La especificación del formato lo dice en bytes y la propiedad lo detecta.

La **etiqueta de versión** no es adorno: el día que el algoritmo cambie, el cambio será visible en el
primer byte de toda preimagen nueva, y el cambio 11 podrá anclar declarando qué versión de formato
cubre cada tramo de cadena, en vez de descubrirlo por arqueología.

#### 6.3 Regla `canon` por tipo de columna

| Columna | Tipo SQL | `canon` | PL/pgSQL | Java |
|---|---|---|---|---|
| `id` | `BIGINT` | dígitos decimales, sin ceros a la izquierda, `0` para cero | `v::text` | `Long.toString(v)` |
| `institution_id`, `actor_id`, `request_id`, `approver_id` | `UUID` | 36 caracteres, hexadecimal **en minúsculas**, con guiones | `v::text` | `v.toString()` |
| `occurred_at` | `TIMESTAMPTZ` | **microsegundos desde el epoch Unix**, entero decimal con signo | `(EXTRACT(EPOCH FROM (v - TIMESTAMPTZ '1970-01-01 00:00:00+00')) * 1000000)::bigint::text` | `Long.toString(ChronoUnit.MICROS.between(Instant.EPOCH, v))` |
| `actor_kind`, `action`, `entity_type`, `entity_id`, `outcome`, `reason` | `TEXT` | el texto **tal cual**, sin recortes, sin plegado de mayúsculas y **sin normalización Unicode** | `v` | `v` |
| `source_ip` | `INET` | dirección más máscara, siempre explícita: `host(v) || '/' || masklen(v)` | idéntico | la cadena que entrega el controlador, con `/32` (sin `:`) o `/128` (con `:`) añadido **solo si falta** |
| `before_value`, `after_value` | `JSONB` | JSON canónico de 6.4 | `shared_audit_canonical_json(v)` | `CanonicalAuditRowSerializer.canonicalJson(JsonNode)` |

Tres puntos que son decisiones, no detalles:

- **`occurred_at` como microsegundos desde el epoch mata la familia entera de divergencias de zona
  horaria.** `timestamptz::text` depende de los parámetros de sesión `TimeZone` y `DateStyle`: el
  mismo instante produce dos textos distintos en dos sesiones, y el hash del disparador dependería
  de cómo estaba configurado el cliente que insertó. Un entero de microsegundos no depende de nada.
  El tipo `timestamptz` de PostgreSQL ya guarda un instante absoluto, así que no se pierde
  información: se pierde la representación, que es justo lo que sobra.
- **Sin normalización Unicode.** No se aplica NFC ni NFD en ninguno de los dos lados. Lo que se
  almacenó es lo que se firma. Aplicar una normalización en un lado y no en el otro es la divergencia
  D3, y aplicarla en los dos exige que PL/pgSQL y Java coincidan en la versión de la tabla Unicode,
  que es una dependencia que nadie quiere en un control probatorio. La base de datos debe estar en
  codificación `UTF8`, y esa condición se afirma en una prueba de catálogo.
- **Java nunca vuelve a formatear una dirección `inet`.** Toma la cadena tal como la entrega
  PostgreSQL y solo le añade la máscara si no la trae. Así la compresión de IPv6, que es donde dos
  bibliotecas discrepan sin falta, la decide un único actor: el motor.

#### 6.4 JSON canónico

Definido de forma recursiva sobre el valor JSON, que en `jsonb` ya llega con las claves duplicadas
resueltas —gana la última— y los números normalizados a `numeric` por el propio PostgreSQL en el
momento de la inserción. Sin espacios en blanco en ningún punto.

| Tipo JSON | Forma canónica |
|---|---|
| `null` | `null` |
| booleano | `true` / `false` |
| número | el valor como `numeric` exacto: sin signo `+`, sin ceros a la izquierda más allá de un único `0`, **sin ceros finales en la parte fraccionaria**, sin punto decimal si no hay fracción, **nunca notación exponencial**, y `-0` normalizado a `0` |
| cadena | `"` … `"` con el escape de RFC 8785: `\"`, `\\`, `\b`, `\f`, `\n`, `\r`, `\t`, y `\u00xx` en **minúsculas** para el resto de los caracteres de control por debajo de `U+0020`. **No se escapa `/` ni ningún carácter no ASCII**, que viaja como UTF-8 literal |
| arreglo | `[` elementos separados por `,` `]`, **en el orden original** |
| objeto | `{` pares `"clave":valor` separados por `,` `}`, con las claves **ordenadas de forma ascendente por su secuencia de bytes UTF-8** |

Implementación:

```sql
-- PL/pgSQL, esbozo; la forma exacta la fija la implementación
CASE jsonb_typeof(v)
  WHEN 'null'    THEN 'null'
  WHEN 'boolean' THEN CASE WHEN v = 'true'::jsonb THEN 'true' ELSE 'false' END
  WHEN 'number'  THEN trim_scale((v #>> '{}')::numeric)::text
  WHEN 'string'  THEN to_json(v #>> '{}')::text
  WHEN 'array'   THEN '[' || coalesce((SELECT string_agg(shared_audit_canonical_json(e), ',' ORDER BY ord)
                                         FROM jsonb_array_elements(v) WITH ORDINALITY AS t(e, ord)), '') || ']'
  WHEN 'object'  THEN '{' || coalesce((SELECT string_agg(to_json(k)::text || ':' || shared_audit_canonical_json(val),
                                                         ',' ORDER BY convert_to(k, 'UTF8'))
                                         FROM jsonb_each(v) AS t(k, val)), '') || '}'
END
```

```java
// Java: Jackson con USE_BIG_DECIMAL_FOR_FLOATS y USE_BIG_INTEGER_FOR_INTS, nunca double.
// Claves ordenadas por comparación sin signo de sus bytes UTF-8, NUNCA con String.compareTo.
```

**Dos desviaciones de RFC 8785, declaradas:**

1. **Orden de claves por bytes UTF-8, no por unidades de código UTF-16.** En PostgreSQL,
   `ORDER BY convert_to(k, 'UTF8')` es una comparación `memcmp` sobre `bytea`, **independiente de la
   intercalación de la base de datos**. Escribir `ORDER BY k` sería un error silencioso, porque
   ordenaría según el `collation` del servidor, que puede cambiar entre entornos: ese es el tipo de
   trampa que esta decisión existe para cerrar. En Java el costo de la desviación es una comparación
   de arreglos de bytes sin signo, de unas diez líneas; el costo de la alternativa sería, en
   PL/pgSQL, reimplementar el orden UTF-16 con su tratamiento de sustitutos.
2. **Números como `numeric` exacto, no como doble ECMAScript.** Razón en 6.1: precisión y
   simplicidad en el lado caro.

Ambas se escriben en el Javadoc de `CanonicalAuditRowSerializer` y en el comentario de la función
SQL, con este documento citado, para que quien lea cualquiera de las dos implementaciones encuentre
la otra y la razón de la diferencia.

#### 6.5 Familias de divergencia y cómo las ejercita la prueba cruzada

La prueba de propiedades con jqwik genera registros completos y compara el `bytea` que devuelve
`shared_audit_row_hash(...)` contra el `byte[]` de `CanonicalAuditRowSerializer`. Cada familia
aparece en el generador como una rama explícita, no como esperanza estadística:

| # | Familia | Qué genera el generador | Qué diverge si el diseño se ignora |
|---|---|---|---|
| D1 | Escala de números | `1`, `1.0`, `1.000`, `0.10`, `-0`, `-0.0`, `1e2`, `1E+2`, `100`, `0.1`, `0.2` | `jsonb` conserva la escala de entrada (`1.0` sigue siendo `1.0`): sin `trim_scale`/`stripTrailingZeros` los dos lados difieren |
| D2 | Magnitud de números | enteros de 40 dígitos, `1e300`, `1e-300`, fracciones de 30 decimales | Usar `double` en cualquiera de los dos lados pierde dígitos o desborda |
| D3 | Texto no ASCII | griego, árabe, CJK, emoji del plano astral (pares sustitutos), marcas combinantes (`é` como `U+00E9` y como `e` + `U+0301`) | UTF-16 frente a UTF-8, y normalización Unicode aplicada en un solo lado |
| D4 | Control y comillas dentro de cadenas JSON | `"`, `\`, `/`, `\n`, `\t`, `U+0001`–`U+001F`, `U+007F` | Escapes distintos, mayúsculas frente a minúsculas en `\u00XX`, `/` escapado en un lado |
| D5 | Vacío frente a nulo | por cada campo anulable: `NULL`, `''`, `'null'::jsonb`, `'""'::jsonb`, `'{}'::jsonb` | Sin el marcador `0x00`/`0x01` y la longitud, `NULL` y `''` colisionan |
| D6 | Orden de claves de objeto | claves que ordenan distinto en UTF-8 y en UTF-16 (ver abajo), claves que solo difieren en longitud (`"a"`, `"aa"`, `"ab"`), clave vacía `""` | El orden interno de `jsonb` es por longitud y después por bytes: confiar en el orden de `jsonb_each` en vez de reordenar produce otra cadena |
| D7 | Claves repetidas y anidamiento | `{"a":1,"a":2}`, objetos dentro de arreglos dentro de objetos con cinco niveles, `[]`, `{}` | La deduplicación la hace `jsonb` en la entrada; si Java usara un analizador que conserva duplicados en otro orden, divergiría |
| D8 | Marcas de tiempo con zona | el mismo instante escrito con desplazamientos distintos, fronteras de horario de verano de `America/Tegucigalpa` y de una zona que sí lo aplica, precisión de microsegundos, instantes anteriores a 1970 | `timestamptz::text` depende de `TimeZone` y `DateStyle` de la sesión |
| D9 | Formas de `inet` | IPv4 sin máscara y con máscara, IPv6 comprimida, IPv6 con máscara, IPv4 mapeada en IPv6 | Máscara implícita frente a explícita, compresión de IPv6 |
| D10 | Mayúsculas en UUID | UUID escrito en mayúsculas en la entrada | Un lado normaliza a minúsculas y el otro no |
| D11 | Longitud en bytes frente a caracteres | cualquier texto no ASCII, especialmente del plano astral | `String.length()` en vez de `getBytes(UTF_8).length` |

**Límite del generador, declarado:** `U+0000` **no se genera**, porque PostgreSQL no admite el
carácter nulo ni en `text` ni dentro de una cadena `jsonb`. No es una decisión de diseño, es una
restricción del motor, y queda escrita en el Javadoc del generador para que nadie la lea como un
hueco de cobertura.

**El escenario de «divergencia introducida a propósito» no se deja al azar.** La especificación
exige un fixture permanente que difiera en un solo campo y una prueba que falle ante él. Se usa un
par de claves **determinista**, elegido porque el orden UTF-8 y el orden UTF-16 son opuestos:

| Clave | UTF-16 (unidades de código) | UTF-8 (bytes) |
|---|---|---|
| `"\uFF3A"` (`U+FF3A`) | `FF3A` | `EF BC BA` |
| `"\uD83D\uDE00"` (`U+1F600`) | `D83D DE00` | `F0 9F 98 80` |

Por unidades de código UTF-16, `U+1F600` va primero (`D83D` < `FF3A`); por bytes UTF-8, va segundo
(`EF` < `F0`). Un objeto con esas dos claves distingue de forma determinista un comparador correcto
de uno que use `String.compareTo`. El fixture permanente
`Utf16OrderingCanonicalAuditRowSerializer` implementa deliberadamente el orden equivocado, y su
prueba afirma que la comparación **falla** sobre ese par concreto. Así la propiedad demuestra que
detecta una divergencia real, y no que pasa por ausencia de comparación.

### Decisión 7 — DDL de `shared_audit_log`

```sql
CREATE TABLE shared_audit_log (
    id               BIGINT        NOT NULL,   -- lo asigna el disparador (decisión 4)
    institution_id   UUID          NOT NULL,
    occurred_at      TIMESTAMPTZ   NOT NULL DEFAULT clock_timestamp(),
    actor_id         UUID,                     -- NULL para actor de sistema
    actor_kind       TEXT          NOT NULL,
    actor_label      TEXT          NOT NULL,
    source_ip        INET,
    user_agent       TEXT,
    request_id       UUID          NOT NULL,
    trace_id         TEXT,
    action           TEXT          NOT NULL,
    entity_type      TEXT          NOT NULL,
    entity_id        TEXT          NOT NULL,
    outcome          TEXT          NOT NULL,
    before_value     JSONB,
    after_value      JSONB,
    reason           TEXT,
    approver_id      UUID,
    prev_hash        BYTEA         NOT NULL,   -- lo calcula el disparador
    row_hash         BYTEA         NOT NULL,   -- lo calcula el disparador
    CONSTRAINT shared_audit_log_pk          PRIMARY KEY (institution_id, id),
    CONSTRAINT shared_audit_log_prev_hash_uq UNIQUE (institution_id, prev_hash),
    CONSTRAINT shared_audit_log_outcome_chk    CHECK (outcome IN ('success', 'denied', 'error')),
    CONSTRAINT shared_audit_log_actor_kind_chk CHECK (actor_kind IN ('staff', 'guardian', 'system')),
    CONSTRAINT shared_audit_log_prev_hash_len_chk CHECK (octet_length(prev_hash) = 32),
    CONSTRAINT shared_audit_log_row_hash_len_chk  CHECK (octet_length(row_hash)  = 32)
);

CREATE INDEX shared_audit_log_entity_idx  ON shared_audit_log (institution_id, entity_type, entity_id, occurred_at DESC);
CREATE INDEX shared_audit_log_actor_idx   ON shared_audit_log (institution_id, actor_id, occurred_at DESC);
CREATE INDEX shared_audit_log_action_idx  ON shared_audit_log (institution_id, action, occurred_at DESC);
CREATE INDEX shared_audit_log_request_idx ON shared_audit_log (institution_id, request_id);

ALTER TABLE shared_audit_log ENABLE ROW LEVEL SECURITY;
ALTER TABLE shared_audit_log FORCE  ROW LEVEL SECURITY;

CREATE POLICY shared_audit_log_institution_isolation ON shared_audit_log
    USING      (institution_id = NULLIF(current_setting('app.institution_id', true), '')::uuid)
    WITH CHECK (institution_id = NULLIF(current_setting('app.institution_id', true), '')::uuid);
```

Puntos que no son obvios:

1. **`NULLIF(..., '')`, igual que en `V1`.** `current_setting(..., true)` devuelve `NULL` si falta el
   ajuste y la comparación deniega; una cadena **vacía**, en cambio, llegaría a `''::uuid` y
   produciría un error de conversión en vez de una denegación. Es el escenario «Contexto de
   institución ausente deniega en vez de fallar» de la especificación, y la parte A ya dejó el patrón
   probado.
2. **`WITH CHECK` explícito**, no heredado de `USING`: con `FORCE ROW LEVEL SECURITY` la política
   alcanza también al propietario, y al insertar PostgreSQL evalúa `WITH CHECK`. Escrito aparte para
   que se lea que **auditar una acción exige tener fijado el contexto de esa institución**, que es lo
   que ata el componente transaccional a la bitácora.
3. **Los cuatro índices se reordenan para empezar por `institution_id`.** `docs/03` §12.1 los declara
   sin él; con la política de fila filtrando siempre por institución, un índice que no lo lleve
   delante sirve de poco. No son índices únicos, así que no interactúan con la puerta de unicidad.
4. **Las restricciones de longitud de los hashes** son la expresión en el catálogo de los «32 bytes»
   que la especificación exige del registro génesis y de toda la cadena.
5. **`actor_label`, `user_agent` y `trace_id` existen en la tabla pero NO entran en el hash.** Es lo
   que `docs/03` §12.1 declara y este diseño no lo amplía. Queda como pregunta abierta 1 de la
   sección 15, porque es una debilidad real y no corresponde a esta fase decidirla en silencio.
6. **Sin `pgcrypto`.** `sha256(bytea)` es función interna de PostgreSQL desde la versión 11. Un
   `CREATE EXTENSION pgcrypto` fallaría: Flyway migra como `confia_owner`, que no es `SUPERUSER`
   (`create-test-roles.sql:11`) y `pgcrypto` no es extensión de confianza. Sonda S6.

### Decisión 8 — Inmutabilidad y permisos

**Disparadores de solo inserción**, sobre las dos tablas, siguiendo `docs/03` §12.3 con el nombre
corregido:

```sql
CREATE FUNCTION shared_audit_is_append_only() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '% es de solo inserción: % rechazado', TG_TABLE_NAME, TG_OP
        USING ERRCODE = 'insufficient_privilege';
END;
$$;
```

| Tabla | `BEFORE UPDATE` | `BEFORE DELETE` | `BEFORE TRUNCATE` |
|---|---|---|---|
| `shared_audit_log` | rechaza | rechaza | rechaza |
| `shared_audit_chain_head` | **permitido** (lo necesita el disparador de encadenamiento) | rechaza | rechaza |

`shared_audit_chain_head` recibe guardas de borrado y de truncamiento por una razón concreta y no
por simetría: **reiniciar la cabecera es exactamente cómo se bifurca una cadena en silencio**. Un
`next_id` vuelto a 1 haría que la siguiente inserción de esa institución se declarara registro
génesis, y solo la restricción única anti-bifurcación la detendría. No es un control nuevo: es la
integridad del mecanismo que `docs/03` §12.1 ordena. Además tiene una consecuencia operativa que la
decisión 9 aprovecha.

**Permisos:**

```sql
REVOKE ALL ON shared_audit_log        FROM PUBLIC;
REVOKE ALL ON shared_audit_chain_head FROM PUBLIC;

GRANT SELECT, INSERT ON shared_audit_log TO confia_admin_app;
GRANT SELECT          ON shared_audit_log TO confia_readonly;
-- confia_portal_app: ningún GRANT, sobre ninguna de las dos tablas (docs/03 §6.1)
-- confia_admin_app, confia_portal_app y confia_readonly: ningún GRANT sobre
-- shared_audit_chain_head; solo el disparador SECURITY DEFINER la escribe (decisión 5)
-- confia_backup lee por pg_read_all_data, que NO omite la seguridad a nivel de fila
```

**Lo que la matriz de privilegios debe afirmar, y que es contraintuitivo.**
`has_table_privilege('confia_owner', 'shared_audit_log', 'UPDATE')` devolverá **verdadero**: el
propietario del esquema conserva todos los privilegios por definición, y eso no es un defecto. La
fila de `confia_owner` en `RolePrivilegeMatrixIT` afirma ese verdadero, y el rechazo al propietario
lo demuestra **otra prueba distinta**, que intenta el `UPDATE` de verdad conectada como
`confia_owner` y espera el error del disparador. Escribir aquí que la matriz debe dar `false` para
el propietario sería pedir algo imposible y empujar a quien implemente a «arreglarlo» relajando el
control. `docs/03` §12.3 ya lo dice con otras palabras: el disparador es «una segunda barrera».

`confia_readonly` recibe `SELECT` porque así lo exige la especificación
(`specs/audit-trail/spec.md`, requisito de permisos) y `docs/03` §6.1 lo describe como rol de solo
lectura con seguridad a nivel de fila activa. `docs/03` §12.3 no lo menciona; la especificación es el
contrato y la corrección documental del corte B2a alinea §12.3.

### Decisión 9 — `CommittingPostgresIntegrationTest`, y cómo se limpia lo que no se puede truncar

**Contrato de la clase.**

```java
/** Base de toda *IT que necesita filas CONFIRMADAS, visibles fuera de su propia transacción. */
public abstract class CommittingPostgresIntegrationTest extends PostgresIntegrationTest {
    // Sin @Transactional: nada se revierte solo.
    @AfterEach void truncateCommittedBusinessTables() { ... }   // ver abajo
    protected TransactionRunner transactionRunner() { ... }     // el componente real de producción
}
```

Tres piezas del contrato, cada una con su razón:

**1. Ningún `@Transactional`, y por eso el contexto de sesión cambia de manos.** `withInstitutionContext`
de `PostgresIntegrationTest` (líneas 111–115) funciona **solo** dentro de una transacción de prueba:
ejecuta `set_config(..., true)` y después el cuerpo. Sin `@Transactional`, cada sentencia va en su
propia transacción implícita y el ajuste, que es local a la transacción, **desaparece antes de la
sentencia siguiente**. Una subclase de confirmación real que llame a ese ayudante obtendría cero
filas y leería el resultado como aislamiento correcto. Es un verde falso, de la familia exacta que
la parte A pagó por aprender.

La salida no es duplicar el ayudante: es que **las pruebas de confirmación real usen el componente
transaccional de producción**, que abre la transacción, fija los cuatro parámetros y ejecuta el
cuerpo. La clase base lo expone construido sobre el `DataSource` y el `PlatformTransactionManager`
del contexto. Donde una prueba necesite saltarse el componente a propósito —la manipulación con
`SUPERUSER`— usa una conexión JDBC cruda (decisión 10). El Javadoc de `withInstitutionContext` se
amplía para decir que no sirve fuera de una transacción de prueba.

**2. Truncamiento selectivo derivado del catálogo, nunca de una lista escrita a mano.** La limpieza
corre en una conexión de `confia_owner` —`confia_admin_app` no tiene `TRUNCATE` sobre nada— y el
conjunto de tablas a truncar se deriva así:

```sql
-- Tablas base de public que NO tienen un disparador BEFORE TRUNCATE
SELECT c.relname
  FROM pg_class c
  JOIN pg_namespace n ON n.oid = c.relnamespace
 WHERE n.nspname = 'public' AND c.relkind = 'r'
   AND c.relname <> 'flyway_schema_history'
   AND NOT EXISTS (SELECT 1 FROM pg_trigger t
                    WHERE t.tgrelid = c.oid AND NOT t.tgisinternal
                      AND (t.tgtype & 32) <> 0);   -- bit de TRUNCATE
```

Una tabla de solo inserción futura queda excluida **sola**, sin que nadie recuerde editar la clase
base. Es el mismo principio de pertenencia contra el catálogo que `MultiTenantSchemaIT` ya usa, y el
motivo por el que la decisión 8 pone guarda de truncamiento también en `shared_audit_chain_head`:
así el catálogo, y no un comentario, es quien dice qué no se limpia.

**3. El problema real, y su resolución: `shared_audit_log` no se limpia. Se aísla.**

La exploración detectó que `shared_audit_log` rechaza `TRUNCATE` por disparador, así que la limpieza
habitual no se puede aplicar. Las tres salidas posibles son:

| Salida | Qué exige | Veredicto |
|---|---|---|
| `ALTER TABLE ... DISABLE TRIGGER` como `confia_owner`, truncar, reactivar | Mutar el esquema en `@AfterEach`. Una prueba que falle entre las dos sentencias deja la tabla **sin protección** para el resto de la JVM, y todas las pruebas posteriores del control pasarían por la razón equivocada | **Descartada** |
| `SET session_replication_role = 'replica'` con conexión `SUPERUSER` y truncar | Usa la misma puerta que la prueba de manipulación. Es por sesión, así que no deja residuo de esquema, pero convierte la limpieza rutinaria en un uso normalizado del mecanismo que solo debería aparecer en la prueba que demuestra el límite del control | **Descartada** |
| **No borrar nada: aislar por institución** | Cada método de prueba genera su propio `InstitutionId` aleatorio | **Elegida** |

**Por qué aislar es mejor que limpiar, y no solo más barato.** La bitácora ya está particionada por
institución: la política de fila impide ver las filas ajenas y la cadena de cada institución es
independiente por decisión del propietario. Una prueba que trabaja con una institución nueva no ve
ni una sola fila de otra prueba, **y que no las vea es exactamente uno de los escenarios que la
especificación exige demostrar**. El residuo se acumula dentro del único contenedor de la JVM y
desaparece cuando el contenedor para, que es en cada `./mvnw verify`. El volumen es de decenas de
filas por corrida, sobre `tmpfs`.

Y lo decisivo: **no existe, en ningún punto del árbol, un mecanismo capaz de borrar una fila de la
bitácora.** Ni siquiera en código de prueba, ni siquiera transitoriamente. Un control de solo
inserción cuya suite de pruebas necesita una llave para borrar es un control con una llave.

El Javadoc de la clase base declara las dos tablas como no truncables por diseño, con esta razón
escrita, y la prueba de la propia clase base afirma que el conjunto derivado del catálogo **no**
las contiene: si algún día alguien retirase la guarda de truncamiento de la bitácora, esa prueba se
pondría roja antes que ninguna otra.

### Decisión 10 — Cómo se prueba la manipulación con `SUPERUSER`

Verificado por lectura: el contenedor arranca con `withUsername("postgres")` y
`withPassword(TEST_PASSWORD)` (`PostgresIntegrationTest:48-58`), y la parte A confirmó por
observación directa que ese usuario nace con `rolsuper = t` y `rolbypassrls = t`. **La conexión de
superusuario ya existe; no hay que crear nada.**

La clase base ofrece `SharedPostgresContainer.connectionAs(String role)`, que abre una conexión JDBC
nueva contra el mismo contenedor. Con `"postgres"` se obtiene el superusuario; con `"confia_owner"`,
el propietario del esquema, que es lo que necesita la prueba de rechazo al propietario.

**Secuencia de manipulación, para el escenario «alterada sin recalcular»:**

```sql
-- conexión SUPERUSER
SET session_replication_role = 'replica';   -- desactiva los disparadores que no son ENABLE ALWAYS
UPDATE shared_audit_log SET reason = 'manipulado' WHERE institution_id = ? AND id = ?;
SET session_replication_role = 'origin';
```

Dos propiedades de esta forma:

- **No muta el esquema.** `session_replication_role` es un parámetro de sesión: la conexión se
  cierra y la protección sigue intacta para toda la JVM, aunque la prueba falle a mitad. La
  alternativa, `ALTER TABLE ... DISABLE TRIGGER`, deja la tabla desprotegida si la prueba se
  interrumpe entre las dos sentencias.
- **El superusuario omite la seguridad a nivel de fila por atributo**, así que no hace falta fijar
  `app.institution_id` para alcanzar la fila.
- Los disparadores se crean **sin** `ENABLE ALWAYS`, que es el valor por omisión, y por tanto
  `session_replication_role = 'replica'` los desactiva. Esto es, textualmente, el límite que
  `docs/03` §12.3 admite: «un `SUPERUSER` de PostgreSQL puede deshabilitar el disparador». La prueba
  **usa** el límite declarado para demostrar el control, en vez de fingir que no existe. Sonda S3.

**Para el escenario del límite conocido —«alterada con recálculo completo»—** la misma sesión de
superusuario, tras alterar la fila, recalcula `row_hash` de esa fila y de todas las posteriores
llamando a `shared_audit_row_hash(...)`, la misma función del motor. Es honesto: un actor con acceso
administrativo tiene esa función igual que la tiene la prueba. El resultado esperado es que el
verificador **reporte integridad**, y ese verde es la declaración ejecutable del límite. El día que
el cambio 11 entregue el ancla, esa prueba tiene que cambiar, lo que obliga a emitir el delta
`MODIFIED` sobre el requisito en vez de olvidarlo.

### Decisión 11 — El verificador: contrato, recorrido e identificación

**Contrato público**, pensado para que el cambio 9 solo tenga que envolver la programación:

```java
package com.confia.shared.audit;

public interface AuditChainVerifier {
    /** Recalcula la cadena completa de una institución desde su registro génesis. */
    AuditChainVerification verifyChainOf(InstitutionId institutionId);
}

public sealed interface AuditChainVerification {

    /** No hay ninguna fila para esa institución: no hay cadena que verificar. */
    record Empty(InstitutionId institutionId) implements AuditChainVerification {}

    /** Toda la cadena recalcula igual. verifiedRows alimenta confia_audit_chain_verified_rows. */
    record Intact(InstitutionId institutionId, long verifiedRows, byte[] lastRowHash)
            implements AuditChainVerification {}

    /** Primera fila divergente, identificada por (institution_id, id). */
    record Diverged(InstitutionId institutionId, long verifiedRows, long firstDivergentId,
                    Divergence divergence, byte[] expectedHash, byte[] storedHash)
            implements AuditChainVerification {}

    enum Divergence { MISSING_GENESIS, GENESIS_PREV_HASH_MISMATCH, PREV_HASH_MISMATCH, ROW_HASH_MISMATCH }
}
```

Por qué así:

- **`sealed` con tres casos.** El cambio 9 necesita distinguir «íntegra» de «divergente» para decidir
  si emite una alerta S1 (`docs/03` §12.4), y necesita `verifiedRows` porque es exactamente la
  métrica `confia_audit_chain_verified_rows` que §12.4 nombra. `Empty` existe porque una institución
  sin actividad no es un fallo, y un `Intact` con cero filas sería una afirmación engañosa.
- **`firstDivergentId` más `institutionId`** es la identificación exacta que exige el criterio de
  salida 3 de F0 y la especificación: la clave primaria completa, no un desplazamiento.
- **`Divergence` distingue cuatro causas.** Una fila alterada produce `ROW_HASH_MISMATCH`; una fila
  borrada o insertada rompe primero el enlace y produce `PREV_HASH_MISMATCH`. Un runbook de
  incidente necesita esa diferencia para saber qué buscar.
- **Ningún método de verificación parcial.** `docs/03` §12.4 pide también una cadencia diaria sobre
  «el último mes», que necesitaría arrancar desde un punto conocido. Ese método **no se entrega**:
  ADR-0019 desaconseja superficie especulativa sin consumidor de producción, y su consumidor es el
  cambio 9. La cadencia semanal y el criterio de salida de F0 se satisfacen con el recorrido
  completo. Queda escrito aquí para que el cambio 9 lo añada sabiendo que es una ampliación
  deliberada y no un olvido.

**Recorrido.**

```text
running := 32 bytes de ceros
para cada fila de la institución, ORDER BY id ASC, en páginas acotadas:
    si fila.prev_hash <> running         -> Diverged(PREV_HASH_MISMATCH, fila.id);  parar
    esperado := sha256(preimagen(running, fila))
    si esperado <> fila.row_hash          -> Diverged(ROW_HASH_MISMATCH, fila.id);  parar
    running := fila.row_hash
Intact(filas, running)
```

Dos detalles:

- **Se para en la primera divergencia**, como exige la especificación («sin continuar reportando una
  segunda divergencia derivada de la primera»).
- **`running` avanza con el `row_hash` almacenado, no con el recalculado.** Parece irrelevante
  porque se para, y no lo es: es la regla que hace que el recorrido pueda reanudarse desde cualquier
  fila con su hash almacenado, que es lo que el cambio 9 necesitará para la cadencia diaria.
- **Paginación acotada** por `(institution_id, id)`, que es la clave primaria: la cadena de una
  institución puede llegar a millones de filas y el verificador no puede materializarlas.

**Aislamiento.** El verificador corre dentro de una transacción abierta por `TransactionRunner` con
el contexto de la institución, de modo que la política de fila le entrega solo las filas de esa
institución. Eso satisface el escenario «El verificador de una institución no recalcula ni ve las
filas de otra» **por el mismo mecanismo que el sistema usa en producción**, sin ningún
`BYPASSRLS`, que es la razón por la que el propietario eligió una cadena por institución.

**Dónde vive, contra las reglas de dependencia.** `AuditChainVerifier` y su implementación viven en
`com.confia.shared.audit`, sin segmento de capa; leen a través del puerto `AuditLogReader`, también
de `com.confia.shared.audit`; y el único adaptador, `JooqAuditLogReader`, vive en
`com.confia.shared.infrastructure`. Justificación completa en la decisión 1 y en la tabla de
evidencia de la sección 2: es la única ubicación que satisface a la vez R1 (jOOQ solo en
`..infrastructure..`), R2 (el módulo `shared` es dueño del prefijo `Shared`) y la regla de capas
(sin supresión ni excepción de ADR).

El puerto devuelve **tipos del JDK**, nunca `org.jooq.JSONB` ni `OffsetDateTime` de una fila
generada: `before_value` y `after_value` viajan como `String` con el texto `jsonb` tal como lo
entrega el motor, y `occurred_at` como `java.time.Instant`. Si el puerto expusiera `org.jooq.JSONB`,
un tipo de jOOQ saldría de `infrastructure` y R1 lo rechazaría.

### Decisión 12 — Cobertura y mutación de la serialización canónica

Ninguna clase nueva encaja con `com.confia.*.domain`, así que **ni la regla de 95 % de JaCoCo ni la
puerta de mutación de PIT las alcanzan** (verificado en `app/pom.xml:190-212` y `321-327`). Solo
aplica la regla `BUNDLE` del 80 % de línea y rama.

Para el serializador canónico eso es insuficiente: es lógica pura, de alto valor probatorio, y es
justo donde una rama no cubierta se convierte en un falso positivo de manipulación en producción.
**Elección:** se añade una regla `PACKAGE` de JaCoCo para `com.confia.shared.audit` con umbral de
**95 % de línea y rama**, junto a la regla existente de los paquetes `domain`, con su comentario
citando este diseño.

**No se amplía el selector de PIT.** Ampliar `targetClasses` a `com.confia.shared.*` arrastraría
también el componente transaccional, cuyo comportamiento es de entrada y salida contra una base de
datos real y produciría mutantes supervivientes sin significado. La prueba de propiedades con jqwik
contra la implementación en PL/pgSQL cumple, para el serializador, el papel que la prueba de
mutación cumple en otros sitios: una implementación alterada deja de coincidir con la otra.

**No se añade ningún `<exclude>` nuevo al POM.** `SuppressionCitesAdrTest` marca cualquier
`<exclude>...</exclude>` sin dos puntos, en cualquier punto de `apps/api`, como exclusión de clase
de JaCoCo que exige una cita `ADR-NNNN` a menos de cuatro líneas (`apps/api/app/pom.xml:117-127`
documenta el caso real que ya tropezó con esto). Si la implementación descubre que necesita uno,
**es una discrepancia con este diseño y se reporta**, no se resuelve añadiendo una cita de
conveniencia. Sonda S10.

### Decisión 13 — R3 con su mitad positiva, y la nomenclatura `*IT`

**R3, mitad positiva.** `TransactionsOnlyInSharedSecurityTest` gana un tercer método que afirma, con
una aserción independiente del rechazo del fixture, que **existe al menos una clase de producción en
`com.confia.shared.security` que usa la API de transacciones**. Se escribe sobre
`productionClasses()` filtrando por paquete y comprobando la misma condición que la mitad negativa,
pero invertida, de modo que las dos mitades comparten el criterio de «usar la API de transacciones»
y no pueden divergir. El Javadoc de la regla y el de `BadTransactionalRepository` dejan de declararse
guarda preventiva.

**Nomenclatura `*IT` (deuda W3).** Regla nueva sobre las **clases de prueba**, no sobre producción:
importar con `ImportOption.Predefined.ONLY_INCLUDE_TESTS` y rechazar toda clase que (a) sea
asignable a `PostgresIntegrationTest` **o** (b) dependa directamente de `SharedPostgresContainer`, y
cuyo nombre simple no termine en `IT`. La condición (b) amplía lo que la especificación pide, y lo
hace a más, no a menos: la prueba de propiedades con jqwik necesita el contenedor sin extender la
clase base (decisión 6 y sonda S8), y sin (b) quedaría fuera de la regla precisamente el caso nuevo
que este cambio introduce.

> **Advertencia explícita, por el precedente de la parte A.** La parte A propuso dos veces un esquema
> de ArchUnit que compiló y **nunca disparó la condición**
> (`noClasses().that().resideOutsideOfPackage(X).should(condicionPropia)`). Todo esquema de ArchUnit
> de este documento es un **esbozo**: la firma exacta la fija la implementación, y la única prueba de
> que la regla funciona es su mitad de rechazo nombrando la clase infractora a través de
> `ArchitectureTestSupport.assertRuleRejects(...)` con el fragmento de mensaje esperado. Una regla
> cuyo fixture no la haga fallar **nombrándolo** no está entregada.

**El fixture negativo permanente y su trampa.** El fixture tiene que extender `PostgresIntegrationTest`
y llamarse `*Test` para violar la regla. Pero entonces Surefire lo selecciona por patrón de nombre,
la plataforma JUnit carga la clase para buscarle métodos de prueba, y **el bloque estático de
`PostgresIntegrationTest` arranca un contenedor bajo Surefire**, que es exactamente el daño que W3
describe. La salida es la decisión 14.

### Decisión 14 — El contenedor pasa a un titular perezoso

**Elección.** El contenedor sale del bloque estático de `PostgresIntegrationTest` y pasa a
`com.confia.support.SharedPostgresContainer`, con arranque **perezoso** en la primera llamada a
`instance()`. `PostgresIntegrationTest` lo invoca desde su `@DynamicPropertySource`, que solo corre
cuando se crea el contexto de Spring de una prueba real.

Resuelve tres cosas de una vez:

1. **El fixture negativo de W3 deja de arrancar nada.** Cargar la clase ya no ejecuta ningún
   `start()`. Un fixture `*Test` sin métodos de prueba no produce descriptor alguno en la plataforma
   JUnit y no levanta contenedor. Sonda S11.
2. **La prueba de propiedades con jqwik obtiene una conexión** sin necesitar `@SpringBootTest`, que
   no puede usar (decisión 6, sonda S8). `SharedPostgresContainer` es público en el paquete de
   soporte y ofrece `jdbcUrl()`, `connectionAs(String role)` y `dataSourceFor(String role)`.
3. **Las conexiones de `confia_owner` y de `postgres`** que necesitan el truncamiento selectivo
   (decisión 9) y la prueba de manipulación (decisión 10) tienen un único punto de acceso, en vez de
   aparecer copiadas en cada prueba que las precise.

Es un refactor de código de prueba, sin cambio de comportamiento observable: el contenedor sigue
siendo uno por JVM, sin `withReuse(true)`, con `fsync=off`, `synchronous_commit=off` y datos en
`tmpfs` sobre `/var/lib/postgresql`, exactamente como la parte A lo dejó.

---

## 4. Flujo de datos

**Escritura de una fila de auditoría**

```
  caso de uso
      │
      ▼
  TransactionRunner  (com.confia.shared.security)
      │  1. BEGIN con el nivel de aislamiento pedido
      │  2. SELECT set_config('app.actor_id', ?, true), ... x4   ← primera sentencia, parámetros vinculados
      │  3. ejecuta el cuerpo
      ▼
  INSERT INTO shared_audit_log (institution_id, actor_kind, action, ...)     ← sin id, sin hashes
      │
      ▼
  ┌── PostgreSQL ───────────────────────────────────────────────────────────┐
  │  política de fila: institution_id = NULLIF(current_setting(...),'')::uuid │
  │                                                                          │
  │  BEFORE INSERT  shared_audit_log_chain()   [SECURITY DEFINER]            │
  │     ├─ INSERT ... ON CONFLICT DO UPDATE shared_audit_chain_head          │
  │     │     → asigna NEW.id y TOMA EL BLOQUEO por institución              │
  │     ├─ SELECT row_hash ... ORDER BY id DESC LIMIT 1  → prev              │
  │     │     (o 32 bytes de ceros si la institución no tiene ninguna fila)  │
  │     ├─ NEW.prev_hash := prev                                             │
  │     └─ NEW.row_hash  := shared_audit_row_hash(prev, NEW.id, ...)         │
  │                                                                          │
  │  UNIQUE (institution_id, prev_hash)  ← si el bloqueo fallara, esto grita │
  └──────────────────────────────────────────────────────────────────────────┘
      │
      ▼  COMMIT  (o 40001 → reintento acotado del TransactionRunner, hasta 3)
```

**Verificación de una cadena**

```
  invocador (una prueba hoy; el cambio 9 mañana)
      │
      ▼
  TransactionRunner  con el contexto de la institución
      │
      ▼
  AuditChainVerifier  (com.confia.shared.audit)
      │  running := 32 ceros
      │  por página, ORDER BY id ASC
      ▼
  AuditLogReader (puerto)  ──►  JooqAuditLogReader (com.confia.shared.infrastructure)
      │                              │  jOOQ sobre el tipo generado SharedAuditLog
      │                              ▼  política de fila: solo esta institución
      │                          PostgreSQL
      ▼
  CanonicalAuditRowSerializer  ──► sha256(preimagen) ──► compara con row_hash almacenado
      │
      ▼
  Intact(filas, últimoHash)  |  Diverged(institución, id, causa)  |  Empty(institución)
```

**La prueba cruzada de la serialización canónica**

```
  jqwik @Property, N intentos
      │
      ├──► shared_audit_row_hash(prev, id, institution_id, occurred_at, ...)   [PL/pgSQL]
      │         llamada directa por JDBC, sin insertar ninguna fila
      │
      └──► CanonicalAuditRowSerializer.rowHash(prev, CanonicalAuditRow)        [Java]

                          assertThat(bytesSql).isEqualTo(bytesJava)
```

---

## 5. Cambios de archivos

| Archivo | Acción | Descripción | Corte |
|---|---|---|---|
| `apps/api/app/pom.xml` | Modificar | Dependencia `net.jqwik:jqwik` de alcance `test` (verificado ausente hoy); regla `PACKAGE` de JaCoCo al 95 % para `com.confia.shared.audit` | B1 |
| `apps/api/app/src/test/resources/junit-platform.properties` | Crear | `jqwik.database` y `jqwik.tries.default` para `app`, con un valor de intentos acorde al presupuesto de 8 minutos | B1 |
| `.../main/java/com/confia/shared/security/TransactionRunner.java` | Crear | Componente transaccional único (decisión 2) | B1 |
| `.../main/java/com/confia/shared/security/SecurityContext.java` | Crear | Los cuatro valores de `docs/03` §6.2, como `record` | B1 |
| `.../main/java/com/confia/shared/security/IsolationLevel.java` | Crear | `READ_COMMITTED` y `SERIALIZABLE` (ADR-0010) | B1 |
| `.../main/java/com/confia/shared/security/package-info.java` | Crear | Paquete sin capa, con la razón escrita y la cita de ADR-0015 regla 7. Posible `@NamedInterface` de Spring Modulith (sonda S12) | B1 |
| `.../test/java/com/confia/support/SharedPostgresContainer.java` | Crear | Titular perezoso del contenedor y acceso por rol (decisión 14) | B1 |
| `.../test/java/com/confia/support/PostgresIntegrationTest.java` | Modificar | Delega el contenedor en el titular; Javadoc de `withInstitutionContext` declara que no sirve sin transacción de prueba | B1 |
| `.../test/java/com/confia/support/CommittingPostgresIntegrationTest.java` | Crear | Confirmación real, truncamiento selectivo derivado del catálogo, acceso al componente real (decisión 9) | B1 |
| `.../test/java/com/confia/support/CommittingBaseContractIT.java` | Crear | Afirma que el conjunto derivado del catálogo excluye las tablas de solo inserción | B2a |
| `.../test/java/com/confia/shared/security/TransactionRunnerContextIT.java` | Crear | Contexto antes de la primera consulta; ningún rastro sobre conexión reutilizada; niveles de aislamiento reales leídos del motor | B1 |
| `.../test/java/com/confia/shared/security/TransactionRunnerRetryIT.java` | Crear | Reintento con éxito ante conflicto real; agotamiento determinista y propagación del error original | B1 |
| `.../test/java/com/confia/architecture/TransactionsOnlyInSharedSecurityTest.java` | Modificar | Mitad positiva; Javadoc deja de declararse guarda preventiva | B1 |
| `.../test/java/com/confia/architecture/fixture/transactions/BadTransactionalRepository.java` | Modificar | Javadoc obsoleto | B1 |
| `.../main/resources/db/migration/V2__create_shared_audit_log.sql` | Crear | Las dos tablas, restricciones, índices, seguridad a nivel de fila, políticas, `REVOKE`/`GRANT`, disparadores de solo inserción (decisiones 3, 7, 8) | B2a |
| `.../test/java/com/confia/schema/RolePrivilegeMatrixIT.java` | Modificar | Filas de `shared_audit_log` y de `shared_audit_chain_head` para los cinco roles | B2a |
| `.../test/java/com/confia/shared/audit/AuditLogAppendOnlyIT.java` | Crear | Rechazo de `UPDATE`, `DELETE` y `TRUNCATE` con el rol de aplicación y **con `confia_owner`** | B2a |
| `.../test/java/com/confia/shared/audit/AuditLogRowSecurityIT.java` | Crear | Aislamiento entre dos instituciones; contexto ausente y vacío devuelven cero filas | B2a |
| `docs/03-seguridad.md` | Modificar | Nombre de la tabla en §6.1, §6.3, §12 y la lista de verificación; §12.1 con clave primaria compuesta y sin `BIGSERIAL`; §12.3 sin el `GRANT` de secuencia y con `confia_readonly`; §12.4 en plural, una cadena por institución | B2a |
| `docs/07-observabilidad-y-operaciones.md`, `docs/08-datos-privacidad-y-retencion.md` | Modificar | Nombre de la tabla y de sus columnas | B2a |
| `docs/runbooks/incidente-de-seguridad.md`, `restauracion-de-respaldo.md` | Modificar | Nombre de la tabla **y reconciliación de la consulta de integridad** contra el esquema real | B2a |
| `docs/runbooks/descuadre-de-libro-mayor.md`, `cierre-de-caja-con-diferencia.md` | Modificar | Solo el nombre de la tabla | B2a |
| `.claude/skills/confia-audit-logging/SKILL.md`, `.claude/agents/confia-database.md` | Modificar | Nombre de la tabla en su DDL y en sus ejemplos | B2a |
| `docs/adr/ADR-0003-separacion-admin-portal.md` | Modificar | Nota editorial fechada de una línea (D2; se omite si el propietario prefiere no tocar el ADR) | B2a |
| `.../main/resources/db/migration/V3__chain_shared_audit_log.sql` | Crear | `shared_audit_canonical_json`, `shared_audit_row_preimage`, `shared_audit_row_hash`, `shared_audit_log_chain()` y el disparador `BEFORE INSERT`; restricción única anti-bifurcación (decisiones 4, 5, 6) | B2b |
| `.../test/java/com/confia/shared/audit/AuditChainTriggerIT.java` | Crear | Génesis con 32 ceros, encadenamiento, dos instituciones independientes, el disparador sobrescribe lo que pase el llamador, inserción SQL directa también encadena | B2b |
| `.../test/java/com/confia/shared/audit/AuditChainConcurrencyIT.java` | Crear | Dos transacciones confirmadas que insertan a la vez, sincronizadas de forma determinista, encadenan sin bifurcación | B2b |
| `.../main/java/com/confia/shared/audit/CanonicalAuditRow.java` | Crear | Los 18 campos que entran al hash | B3 |
| `.../main/java/com/confia/shared/audit/CanonicalAuditRowSerializer.java` | Crear | Preimagen canónica y SHA-256 en Java (decisión 6) | B3 |
| `.../main/java/com/confia/shared/audit/AuditChainVerifier.java`, `AuditChainVerification.java` | Crear | Contrato público del verificador (decisión 11) | B3 |
| `.../main/java/com/confia/shared/audit/AuditLogReader.java`, `AuditRowSnapshot.java` | Crear | Puerto de lectura y su registro, solo con tipos del JDK | B3 |
| `.../main/java/com/confia/shared/audit/DefaultAuditChainVerifier.java` | Crear | Recorrido paginado e identificación de la primera divergencia | B3 |
| `.../main/java/com/confia/shared/infrastructure/JooqAuditLogReader.java` | Crear | Única clase nueva que toca jOOQ (decisión 1) | B3 |
| `.../main/java/com/confia/shared/infrastructure/package-info.java`, `.../shared/audit/package-info.java` | Crear | Capa de adaptadores del módulo `shared` y capacidad de auditoría, con sus citas | B3 |
| `.../test/java/com/confia/shared/audit/CanonicalSerializationCrossCheckIT.java` | Crear | Prueba de propiedades con jqwik, sin `@SpringBootTest` (decisión 6) | B3 |
| `.../test/java/com/confia/shared/audit/fixture/Utf16OrderingCanonicalAuditRowSerializer.java` | Crear | Fixture permanente con la divergencia deliberada | B3 |
| `.../test/java/com/confia/shared/audit/CanonicalSerializationDivergenceTest.java` | Crear | Afirma que la comparación falla contra el fixture, sobre el par de claves determinista | B3 |
| `.../test/java/com/confia/shared/audit/AuditChainVerifierIT.java` | Crear | Cadena íntegra; manipulación con `SUPERUSER` identificada con exactitud; otra institución no aparece | B3 |
| `.../test/java/com/confia/shared/audit/AuditChainKnownLimitIT.java` | Crear | Manipulación con recálculo completo **no** detectada: el límite, ejecutable | B3 |
| `.../test/java/com/confia/architecture/IntegrationTestNamingTest.java` | Crear | Regla de nomenclatura `*IT` (decisión 13) | B4 |
| `.../test/java/com/confia/architecture/fixture/naming/BadlyNamedContainerTest.java` | Crear | Fixture negativo permanente | B4 |
| `docs/09-roadmap-y-fases.md` | Modificar | Criterio de salida 3 y estado de las deudas W1, W2 y W3 | B4 |
| `openspec/specs/audit-trail/spec.md`, `openspec/specs/build-integrity/spec.md` | Delta al archivar | Los escribe la fase de archivado desde los deltas ya redactados | — |

---

## 6. Contratos e interfaces

### 6.1 Componente transaccional

```java
package com.confia.shared.security;

/** Los cuatro valores de docs/03 §6.2. Cadena vacía, nunca null, para un valor ausente. */
public record SecurityContext(String actorId, String actorKind, String institutionId,
                              String requestId) { }

public enum IsolationLevel { READ_COMMITTED, SERIALIZABLE }

public final class TransactionRunner {

    public TransactionRunner(PlatformTransactionManager transactionManager, DataSource dataSource);
    public TransactionRunner(PlatformTransactionManager transactionManager, DataSource dataSource,
                             int maxRetries, Duration backoffBase);

    /** READ COMMITTED. */
    public <T> T execute(SecurityContext context, Supplier<T> useCase);

    /** Nivel explícito, donde ADR-0010 lo exija. */
    public <T> T execute(SecurityContext context, IsolationLevel isolation, Supplier<T> useCase);
}
```

Igual que `JooqInstitutionRepository` en la parte A, es una clase `final` con constructor explícito y
**sin anotación de Spring**: hoy no hay ningún proceso que consuma un `DataSource`
(`AdminApplication`, `PortalApplication` y `WorkerApplication` excluyen `DataSourceAutoConfiguration`).
Registrarlo como bean y ampliar el escaneo es trabajo del cambio que lo consuma, el 6.

### 6.2 Verificador de cadena

Definido en la decisión 11. El cambio 9 solo necesita construir un `TransactionRunner`, llamar a
`verifyChainOf` por institución y traducir el resultado a la métrica
`confia_audit_chain_verified_rows` y a la alerta S1.

### 6.3 Serialización canónica

```java
package com.confia.shared.audit;

/** Los 18 campos firmados: los 15 de docs/03 §12.1 mas actor_label, user_agent y trace_id. */
public record CanonicalAuditRow(long id, UUID institutionId, Instant occurredAt, UUID actorId,
                                String actorKind, String sourceIp, UUID requestId, String action,
                                String entityType, String entityId, String outcome,
                                String beforeValueJson, String afterValueJson, String reason,
                                UUID approverId) { }

public final class CanonicalAuditRowSerializer {
    public static final byte[] GENESIS_PREV_HASH = new byte[32];
    public static final String FORMAT_VERSION = "confia.audit.v1";

    public byte[] preimage(byte[] prevHash, CanonicalAuditRow row);
    public byte[] rowHash(byte[] prevHash, CanonicalAuditRow row);
}
```

Su Javadoc lleva **la especificación completa del formato** de la decisión 6 y la cita cruzada a la
función SQL. La función SQL lleva el comentario recíproco. Un cambio en uno de los dos lados sin el
otro es visible en la revisión, además de romper la propiedad.

### 6.4 Forma de las reglas nuevas de ArchUnit

> **Esbozo. La firma exacta la fija la implementación** (advertencia de la decisión 13).

```java
// R3, mitad positiva (una aserción, no una ArchRule: afirma existencia, no ausencia)
assertThat(productionClasses().stream()
        .filter(c -> c.getPackageName().startsWith("com.confia.shared.security"))
        .anyMatch(TransactionsOnlyInSharedSecurityTest::usesTransactionApi))
    .as("shared.security debe contener al menos una clase de producción que abra transacciones")
    .isTrue();

// Nomenclatura *IT (W3): sobre clases de PRUEBA, no de producción
classes().should(beNamedWithIntegrationSuffixWhenItNeedsAContainer())
    .check(new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.ONLY_INCLUDE_TESTS)
            .importPackages("com.confia"));
```

---

## 7. Estrategia de pruebas

| Capa | Qué se prueba | Cómo |
|---|---|---|
| Unitaria | Serialización canónica sobre casos límite escritos a mano; construcción del `SecurityContext`; reglas de `canon` por tipo | JUnit y AssertJ, sin contenedor |
| Propiedades | Coincidencia byte a byte entre PL/pgSQL y Java sobre entradas generadas, con las once familias de divergencia | jqwik, conexión JDBC directa, sin `@SpringBootTest` |
| Arquitectura | R3 en sus dos mitades; nomenclatura `*IT` con su fixture rechazado por nombre | ArchUnit, patrón de dos mitades con `assertRuleRejects` |
| Integración (`*IT`) | Contexto, aislamiento y reintento del componente; permisos y catálogo; solo inserción incluido el propietario; encadenamiento, génesis y concurrencia; verificador, manipulación y límite conocido | Failsafe y Testcontainers, un contenedor por JVM, variante de confirmación real donde haga falta |
| Rendimiento | Que la suite `*IT` sigue cabiendo en 8 minutos | **Medido y reportado** al cerrar cada corte, no estimado |

### 7.1 Trazabilidad: los 41 escenarios y su prueba

**`audit-trail` (27 escenarios)**

| Requisito | Escenario | Prueba | Corte |
|---|---|---|---|
| Cadena por institución | Primera fila es génesis | `AuditChainTriggerIT` | B2b |
| | Segunda fila encadena | `AuditChainTriggerIT` | B2b |
| | Dos instituciones independientes | `AuditChainTriggerIT` | B2b |
| | Inserciones concurrentes sin carrera | `AuditChainConcurrencyIT` | B2b |
| Encadenamiento en el motor | La aplicación inserta sin calcular el hash | `AuditChainTriggerIT` (inserta valores falsos y afirma que fueron sobrescritos) | B2b |
| | Inserción SQL directa también encadena | `AuditChainTriggerIT` | B2b |
| Inmutabilidad | `UPDATE` rechazado con el rol de aplicación | `AuditLogAppendOnlyIT` | B2a |
| | `DELETE` rechazado con el rol de aplicación | `AuditLogAppendOnlyIT` | B2a |
| | `UPDATE` y `DELETE` rechazados al propietario | `AuditLogAppendOnlyIT`, con conexión `confia_owner` | B2a |
| | `TRUNCATE` rechazado al propietario | `AuditLogAppendOnlyIT` | B2a |
| Permisos | `confia_admin_app` lee e inserta, no actualiza ni borra | `RolePrivilegeMatrixIT` | B2a |
| | `confia_portal_app` sin ningún privilegio | `RolePrivilegeMatrixIT` | B2a |
| | `PUBLIC` sin ningún privilegio de partida | `RolePrivilegeMatrixIT`, con un rol de prueba ajeno a los cinco | B2a |
| Seguridad de fila | Una institución no lee las de otra | `AuditLogRowSecurityIT` | B2a |
| | Contexto ausente deniega sin error de conversión | `AuditLogRowSecurityIT` | B2a |
| Verificador | Cadena íntegra reporta integridad | `AuditChainVerifierIT` | B3 |
| | Fila alterada con `SUPERUSER` identificada con exactitud | `AuditChainVerifierIT` | B3 |
| | El verificador de una institución no ve las de otra | `AuditChainVerifierIT` | B3 |
| | Alterar solo `actor_label` rompe la cadena | `AuditChainVerifierIT` | B3b |
| Reproducibilidad | Mismo hash sobre entradas generadas | `CanonicalSerializationCrossCheckIT` | B3 |
| | Divergencia deliberada hace fallar la propiedad | `CanonicalSerializationDivergenceTest` | B3 |
| Límite sin ancla | Manipulación sin recálculo es detectada | `AuditChainVerifierIT` | B3 |
| | Manipulación con recálculo completo **no** es detectada | `AuditChainKnownLimitIT` | B3 |
| Sin programación (cambio 9) | Se invoca directamente | `AuditChainVerifierIT` | B3 |
| | Ninguna cadencia periódica implementada | Prueba de inventario: ninguna clase de producción depende de un tipo de programación; ningún `@Scheduled`; `scheduled_tasks` no existe en el catálogo | B3 |
| Sin `audit:read` (cambios 7 y 8) | La política filtra solo por institución | `AuditLogRowSecurityIT` | B2a |
| | Ningún endpoint expone la bitácora | Prueba de inventario: ninguna clase de producción en un paquete `..web..` depende del módulo `audit`, y el inventario de rutas está vacío | B3 |

**`build-integrity` (15 escenarios)**

| Requisito | Escenario | Prueba | Corte |
|---|---|---|---|
| Transacciones confinadas | Adaptador abre su propia transacción | `TransactionsOnlyInSharedSecurityTest`, mitad de rechazo (ya existe) | B1 |
| | Adaptador sin control transaccional propio | `TransactionsOnlyInSharedSecurityTest`, mitad de producción (ya existe) | B1 |
| | `shared/security` sin ninguna clase que use la API | Mitad **positiva** nueva (decisión 13) | B1 |
| | El componente satisface la aserción positiva | Mitad positiva nueva, en verde | B1 |
| Contexto, aislamiento y reintento | Contexto fijado antes de la primera consulta | `TransactionRunnerContextIT` | B1 |
| | Ningún contexto sobrevive sobre conexión reutilizada | `TransactionRunnerContextIT`, dos transacciones consecutivas | B1 |
| | `READ COMMITTED` leído del motor real | `TransactionRunnerContextIT`, con `current_setting('transaction_isolation')` | B1 |
| | `SERIALIZABLE` leído del motor real | `TransactionRunnerContextIT` | B1 |
| | El reintento tiene éxito dentro del límite | `TransactionRunnerRetryIT`, dos hilos sincronizados con barrera | B1 |
| | El reintento se agota y propaga el error | `TransactionRunnerRetryIT`, cuerpo que ejecuta `RAISE EXCEPTION ... ERRCODE = '40001'` en cada intento | B1 |
| Nomenclatura `*IT` | Fixture nombrado `Test` rompe la construcción | `IntegrationTestNamingTest`, mitad de rechazo | B4 |
| | Las subclases reales están todas nombradas `IT` | `IntegrationTestNamingTest`, mitad de producción sobre el árbol real | B4 |
| Puertas extendidas | La matriz cubre `shared_audit_log` para los cinco roles | `RolePrivilegeMatrixIT` | B2a |
| | Las puertas genéricas pasan sin lista de exclusión | `MultiTenantSchemaIT`, **sin modificar** | B2a |
| | Rechazo de `UPDATE` y `DELETE` al propietario | `AuditLogAppendOnlyIT` | B2a |

### 7.2 Dos pruebas que no pueden depender de tiempos

- **Concurrencia del disparador** (`AuditChainConcurrencyIT`): dos hilos, cada uno con su
  `TransactionRunner` y su conexión, sincronizados con una `CyclicBarrier` en un punto concreto —
  después de abrir la transacción y antes de insertar—, de modo que el orden de llegada al bloqueo
  de la cabecera es real y no depende de una espera. Se afirma que las dos filas resultantes forman
  una cadena lineal: `id` consecutivos, `prev_hash` de la segunda igual al `row_hash` de la primera,
  y ninguna violación de la restricción única.
- **Reintento con éxito** (`TransactionRunnerRetryIT`): dos transacciones `SERIALIZABLE` con un
  conflicto real de predicado, también con barrera. El escenario de **agotamiento** no usa
  concurrencia en absoluto: un cuerpo que ejecuta
  `DO $$ BEGIN RAISE EXCEPTION USING ERRCODE = '40001'; END $$;` produce el error de serialización
  desde el motor real, traducido por el traductor real de Spring, de forma determinista en cada
  intento. Es la única manera de probar el agotamiento sin una prueba escamosa.

---

## 8. Matriz de amenazas

No aplica en el sentido de `references/threat-matrix.md`: este cambio no introduce enrutamiento,
órdenes de intérprete de comandos, subprocesos de la aplicación, automatización de Git o de pull
requests, ni clasificación de archivos ejecutables. Las fronteras de seguridad que sí toca se tratan
como decisiones con verificación:

| Frontera | Tratamiento |
|---|---|
| Un disparador con privilegio definidor | `SECURITY DEFINER` con `SET search_path = pg_catalog, public` (decisión 5). Corre como `confia_owner`, no como superusuario, y sigue sujeto a `FORCE ROW LEVEL SECURITY`. Su único efecto es escribir `shared_audit_chain_head`, sobre la que ningún rol de aplicación tiene privilegio. Sonda S5 |
| Contexto de sesión en el componente transaccional | Cuatro parámetros **vinculados**, `set_config(..., true)`, jamás `SET SESSION` ni interpolación. Dos escenarios lo prueban, uno de ellos sobre una conexión reutilizada del pool |
| Credenciales en el repositorio | Ninguna nueva. Las del contenedor siguen siendo la literal de prueba de la parte A, solo en `src/test/resources` |
| Aislamiento por fila de la bitácora | Política por institución con `USING` y `WITH CHECK` explícitos, `FORCE ROW LEVEL SECURITY`, y cero filas ante contexto ausente o vacío. Probado, no declarado |
| Una llave de borrado en la suite de pruebas | **No existe.** La decisión 9 elimina la necesidad: las pruebas aíslan por institución en vez de borrar filas de la bitácora |
| El límite del control sin ancla externa | Declarado como requisito con dos escenarios simétricos, uno de los cuales pasa afirmando que el control **no** ve la manipulación. Es incómodo a propósito |
| Registro de datos sensibles | Este cambio no escribe ninguna traza. Los campos `before_value` y `after_value` se declaran «redactados, sin datos sensibles en claro» en `docs/03` §12.1, y quien los rellene son los cambios 7 y 8 |

---

## 9. Migración y despliegue

No hay entorno desplegado ni dato real: la base vive solo en contenedores efímeros que se recrean en
cada construcción. Dos matices propios de este cambio:

1. **Dos migraciones, no una.** `V2` crea las tablas y sus guardas; `V3` añade el encadenamiento.
   Separarlas es lo que permite entregar B2a y B2b como cortes distintos y, si el encadenamiento en
   PL/pgSQL resultara inviable, **detenerse con la tabla de solo inserción entregada**, que ya es un
   control real. No se mueve el cálculo del hash a la aplicación como salida de emergencia: `docs/03`
   §12.1 lo sitúa en el motor para que una escritura por cualquier vía quede encadenada, y cambiar
   eso es una decisión de arquitectura que se eleva a ADR.
2. **El contenedor de generación de código también aplica estas migraciones.** El devolución de
   llamada `beforeMigrate__create_codegen_roles.sql` ya crea los cinco roles `NOLOGIN` sin
   contraseña, de modo que los `GRANT` de `V2` tienen destinatario. No hace falta tocarlo. Sí hace
   falta comprobar que el tipo generado para la columna `inet` es utilizable (sonda S9).

**Punto de no retorno práctico:** el cambio 11, cuando exista el primer entorno desplegado y la
bitácora empiece a acumular evidencia real. Desde entonces la cadena no se reconstruye: se preserva,
y un cambio en el formato canónico exige una etiqueta de versión nueva (decisión 6.2), no una
reescritura.

---

## 10. Sondas, a ejecutar antes de la tarea que depende de cada una

Esta fase no tuvo herramienta de ejecución de procesos. Cada sonda se ejecuta fuera del árbol del
repositorio o en archivos temporales que se borran, y **su resultado se registra en el informe de
aplicación**, como hizo la sonda S1 de la parte A.

**Resultados de las sondas ejecutadas por el orquestador el 2026-09-21**, contra un contenedor
`postgres:18-alpine` levantado fuera del árbol del repositorio y detenido al terminar. Intercalación
de la base de la sonda: `en_US.utf8`.

| # | Resultado | Detalle observado |
|---|---|---|
| **S6** | **PASA. El cambio no está bloqueado por entorno.** | `sha256('abc')` devuelve el vector conocido `ba7816bf…15ad` y `pg_proc.prolang = 12`, es decir función interna: **no hace falta `pgcrypto`**, que `confia_owner` no podría crear. `int8send(1::bigint)` da `0000000000000001`, ocho bytes en orden de red. `convert_to('ñ','UTF8')` da `c3b1` |
| **S1** | **PASA, coincide con la regla de 6.4** | `to_json` escapa `"` como `\"`; la barra invertida como `\` (verificado con `chr(92)`, salida de 6 caracteres, el colapso inicial era del intérprete de comandos); los controles como `

	`; el resto de C0 como ``, ``, en minúsculas; **no** escapa `/` ni el texto no ASCII (`ñ😀` viaja literal) |
| **S2** | **PASA** | `numeric::text` nunca usó notación exponencial: `1e30` sale como `1000000000000000000000000000000` y `0.00000000001` sin exponente, incluso viniendo de `jsonb`. `trim_scale` retira ceros finales (`1.2300` → `1.23`, `100.000` → `100`), también sobre valores extraídos de `jsonb` |
| **S4** | **PASA de forma concluyente** | Rama de inserción y rama de actualización devuelven 1, 2 y 3 sin huecos. En concurrencia real: la sesión 1 obtuvo el id 4 dentro de una transacción abierta y **la sesión 2 quedó bloqueada 4 407 ms** hasta la confirmación, tras lo cual obtuvo el id 5. El `ON CONFLICT ... DO UPDATE ... RETURNING` **sí** toma el bloqueo por institución. No se conmuta a `pg_advisory_xact_lock` |

**Hallazgo adicional sobre el orden de claves, que refuerza la decisión del diseño.** Con una muestra
deliberadamente discriminante (`a`, `A`, `_b`, `B`), `ORDER BY k` y
`ORDER BY convert_to(k,'UTF8')` devolvieron **el mismo resultado**, `A,B,_b,a`, que es exactamente el
orden de bytes. Es decir: en esta imagen, la intercalación declarada `en_US.utf8` se comporta como
`C`, probablemente porque Alpine no trae los datos de configuración regional completos.

Eso **no desmiente la regla del diseño, la justifica más**: una prueba local que usara `ORDER BY k`
pasaría en verde aquí y podría ordenar distinto en una imagen con datos de configuración regional
completos. Es la misma familia de defecto que esta entrega ya encontró tres veces —verde por la razón
equivocada—, así que `ORDER BY convert_to(k,'UTF8')` se mantiene como obligatorio y **la prueba
cruzada no debe apoyarse en que ambos coincidan**.

Quedan sin ejecutar S3, S5, S7, S8, S9, S10, S11, S12 y S13, que dependen de código o de esquema que
este cambio todavía no ha escrito; se ejecutan en el corte que las bloquea, como indica la columna.

| # | Pregunta | Criterio de éxito | Bloquea |
|---|---|---|---|
| **S1** | ¿`to_json(texto)::text` en PostgreSQL 18 produce `\"`, `\\`, `\b`, `\f`, `\n`, `\r`, `\t` y `\u00xx` en minúsculas para el resto de C0, sin escapar `/` ni el texto no ASCII? | Coincide con la regla de 6.4. Si no, el escape se escribe a mano en PL/pgSQL y la regla del diseño no cambia | B2b |
| **S2** | ¿`numeric::text` usa notación exponencial en algún caso representable? ¿`trim_scale()` se comporta como se espera sobre valores venidos de `jsonb`? | Salida siempre en notación decimal plana; `trim_scale` retira ceros finales. Si hubiera exponente, la regla de 6.4 se reescribe con `to_char` explícito y la sonda se repite | B2b |
| **S3** | ¿`SET session_replication_role = 'replica'` como `postgres` desactiva el disparador `BEFORE UPDATE` de `shared_audit_log` y permite el `UPDATE`? | El `UPDATE` pasa y, al volver a `'origin'`, vuelve a ser rechazado. Si no, se usa `ALTER TABLE ... DISABLE TRIGGER` dentro de un `try/finally` que lo reactive siempre | B3 |
| **S4** | ¿`INSERT ... ON CONFLICT DO UPDATE ... RETURNING next_id - 1` devuelve el identificador correcto en las dos ramas y bloquea a un segundo escritor de la misma institución hasta la confirmación del primero? | Dos sesiones: la segunda queda bloqueada y, al desbloquearse, obtiene el identificador siguiente. Si no, se conmuta a `pg_advisory_xact_lock` (decisión 4, alternativa de respaldo) | B2b |
| **S5** | ¿Un disparador `SECURITY DEFINER` propiedad de `confia_owner` puede escribir `shared_audit_chain_head` con `FORCE ROW LEVEL SECURITY` activa, cuando el invocador es `confia_admin_app` sin ningún privilegio sobre esa tabla? ¿Y la política sigue aplicándose al propietario? | Escribe solo la fila de la institución del contexto; una institución distinta es rechazada por la política. Si no, respaldo: la cabecera sin seguridad a nivel de fila, con el aislamiento garantizado por el propio disparador, **declarado y probado** | B2b |
| **S6** | ¿`sha256(bytea)` está disponible sin `pgcrypto` en `postgres:18-alpine`, y `int8send` y `convert_to` se comportan como se espera? | Las tres funciones responden como interno. Si `sha256` no existiera, el cambio está **bloqueado por entorno** y se reporta: no se instala `pgcrypto`, que `confia_owner` no puede crear | B2b |
| **S7** | Con `consideringOnlyDependenciesInLayers()`, ¿una dependencia de `..application..` hacia `com.confia.shared.security` (sin segmento de capa) es una violación? | No lo es. Si lo fuera, el componente pasa a `com.confia.shared.security` con una supresión que cita ADR-0015 regla 7, y este diseño queda desmentido en ese punto, que se reporta | B1 |
| **S8** | ¿Una clase jqwik `@Property` corre bajo Failsafe en este reactor? ¿Ignora las extensiones de Jupiter, es decir no puede usar `@SpringBootTest`? | La propiedad se ejecuta en la fase de integración. Si jqwik **sí** admitiera `@SpringBootTest`, la prueba cruzada puede extender la clase base y `SharedPostgresContainer` sigue siendo útil igualmente | B3 |
| **S9** | ¿Qué tipo Java genera jOOQ 3.21 para una columna `inet` de PostgreSQL? | `String`, o se añade un `<forcedType>` a `VARCHAR` en la configuración de generación de `app/pom.xml` | B3 |
| **S10** | ¿Añadir la regla `PACKAGE` de JaCoCo para `com.confia.shared.audit` introduce algún `<exclude>` que dispare `SuppressionCitesAdrTest`? | Ninguno: la regla usa `<includes>`, no `<excludes>`. Si la implementación descubre lo contrario, **se reporta la discrepancia**, no se añade una cita de conveniencia | B1 |
| **S11** | ¿Surefire y la plataforma JUnit toleran una clase `*Test` sin ningún método de prueba? ¿Con el contenedor en un titular perezoso, cargarla no arranca nada? | La clase no produce descriptor y ningún contenedor arranca en la fase `test`. Si no, el fixture se excluye de Surefire y esa exclusión necesita su cita de ADR | B4 |
| **S12** | ¿Cómo expone Spring Modulith 2 el paquete `com.confia.shared.security` como interfaz nombrada del módulo `shared`, y `SpringModulithVerificationTest` sigue en verde al añadirlo? | Un `package-info.java` con la anotación correspondiente deja la verificación en verde. Si resultara caro o ambiguo, **se difiere al cambio 6**, que es el primero que llamará al componente desde otro módulo, y se deja escrito aquí para que no lo descubra en un rojo | B1 |
| **S13** | ¿`dependencyConvergence` del enforcer sigue en verde tras añadir `net.jqwik:jqwik` de alcance `test` a `app`? | En verde. Si no, se declara la versión en conflicto en `dependencyManagement` con un comentario que explique qué mediación se hace explícita, **nunca** con una exclusión silenciosa | B1 |

---

## 11. Secuencia de aplicación con TDD estricto

**B1 — componente transaccional y base de confirmación real**

1. Sondas S7, S10, S12 y S13. Bloqueantes de este corte.
2. Rojo: `TransactionRunnerContextIT` afirma que los cuatro `set_config` ocurren antes de la primera
   consulta y que el aislamiento leído del motor es el pedido. Falla porque no hay componente.
3. Verde: `TransactionRunner`, `SecurityContext`, `IsolationLevel` y sus `package-info`.
4. Refactor: el contenedor sale a `SharedPostgresContainer` con arranque perezoso; `verify` en verde.
5. Rojo: `CommittingPostgresIntegrationTest` con su prueba de contrato. Verde: la clase base.
6. Rojo: `TransactionRunnerRetryIT`, primero el agotamiento determinista y después el éxito con
   barrera. Verde: el reintento acotado.
7. Rojo: la mitad positiva de R3. Verde: ya lo está por el paso 3; se corrigen los dos Javadoc.
8. jqwik y `junit-platform.properties` en `app`; regla `PACKAGE` de JaCoCo. Medición del tiempo de la
   suite.

**B2a — tablas, permisos y solo inserción**

9. Rojo: `RolePrivilegeMatrixIT` con las filas nuevas, `AuditLogAppendOnlyIT` y
   `AuditLogRowSecurityIT`.
10. Verde: `V2__create_shared_audit_log.sql`.
11. Comprobación de que `MultiTenantSchemaIT` pasa **sin modificarse**. Si no pasara, es una
    discrepancia con este diseño y se reporta, no se relaja la puerta.
12. Corrección documental completa del nombre de la tabla, incluida la reconciliación de las dos
    consultas de runbook contra el esquema entregado, en este mismo corte.

**B2b — encadenamiento por hash en el motor**

13. Sondas S1, S2, S4, S5 y S6. Bloqueantes de este corte.
14. Rojo: `AuditChainTriggerIT` (génesis, encadenamiento, dos instituciones, sobrescritura de lo que
    pase el llamador, inserción SQL directa).
15. Verde: `V3__chain_shared_audit_log.sql` con las tres funciones, el disparador y la restricción
    única anti-bifurcación.
16. Rojo y verde: `AuditChainConcurrencyIT` con barrera.
17. Medición del tiempo de la suite.

**B3 — verificador y pruebas de manipulación**

18. Sondas S3, S8 y S9. Bloqueantes de este corte.
19. Rojo: `CanonicalSerializationCrossCheckIT` con las once familias. Verde:
    `CanonicalAuditRowSerializer`. Es el orden correcto porque la propiedad es el contrato del
    serializador, no su comprobación posterior.
20. Rojo: `CanonicalSerializationDivergenceTest` contra el fixture de orden UTF-16. Verde: el fixture.
21. Rojo: `AuditChainVerifierIT`. Verde: puerto, adaptador y verificador.
22. Rojo y verde: `AuditChainKnownLimitIT` y las dos pruebas de inventario de las exclusiones con
    destino nombrado.

**B4 — nomenclatura `*IT`**

23. Sonda S11.
24. Rojo: la mitad de rechazo con el fixture. Verde: la regla. Después, la mitad de producción sobre
    el árbol real.
25. Cierre documental de `docs/09` y medición final del tiempo de la suite.

---

## 12. Pronóstico de tamaño por corte

Líneas de autor (adiciones más eliminaciones) en código, pruebas, POM, SQL, configuración y
documentación. **Quedan fuera** los artefactos de OpenSpec y, por definición, el código generado de
jOOQ. La estimación incorpora la desviación de 1,5 a 3 veces observada en los cambios 2 y 4, y el
cierre real de la parte A: pronóstico de 1 150 a 2 100, cierre en 1 537.

| Corte | Desglose | Estimación |
|---|---|---|
| **B1** | `TransactionRunner` y sus tres tipos (140–220), `SharedPostgresContainer` y el refactor de la base (90–150), `CommittingPostgresIntegrationTest` (70–120), `TransactionRunnerContextIT` (110–180), `TransactionRunnerRetryIT` (110–190), mitad positiva de R3 y los dos Javadoc (40–70), POM y `junit-platform.properties` (25–45) | **585 a 975** |
| **B2a** | `V2__...sql` (110–170), `RolePrivilegeMatrixIT` (60–110), `AuditLogAppendOnlyIT` (90–150), `AuditLogRowSecurityIT` (70–120), `CommittingBaseContractIT` (30–50), corrección documental en nueve archivos más dos consultas de runbook (170–290) | **530 a 890** |
| **B2b** | `V3__...sql` con las tres funciones y el disparador (170–280), `AuditChainTriggerIT` (130–210), `AuditChainConcurrencyIT` (90–150) | **390 a 640** |
| **B3** | `CanonicalAuditRowSerializer` y `CanonicalAuditRow` (170–260), verificador, puerto, registro y adaptador (180–290), `CanonicalSerializationCrossCheckIT` con once familias (150–250), fixture de divergencia y su prueba (60–100), `AuditChainVerifierIT` (110–180), `AuditChainKnownLimitIT` (60–100), dos pruebas de inventario (50–90) | **780 a 1 270** |
| **B4** | Regla, fixture, inventario y cierre de `docs/09` (110–200) | **110 a 200** |
| **Total** | | **2 395 a 3 975** |

**Frente al presupuesto de 800 líneas de cambio efectivo por pull request**
(`docs/15-flujo-de-trabajo-git.md` §3):

- **B2b y B4 caben siempre.** **B2a** cabe en su rango alto por poco margen. **B1** y **B3** lo
  superan en su rango alto, y B3 lo supera **incluso en su rango medio**.
- **Subdivisiones ya identificadas**, sin separar nunca código de sus pruebas:
  - **B1a** = `TransactionRunner`, sus tipos, `TransactionRunnerContextIT` y la mitad positiva de R3
    (≈340–560); **B1b** = titular perezoso, `CommittingPostgresIntegrationTest`,
    `TransactionRunnerRetryIT`, jqwik y JaCoCo (≈245–415).
  - **B2a-doc** = la corrección documental completa y la reconciliación de runbooks (≈170–290),
    entregada **antes** de la migración, nunca después; **B2a-sql** = las dos tablas, sus guardas y
    sus pruebas (≈360–600).
  - **B3a** = `CanonicalAuditRow`, `CanonicalAuditRowSerializer`, la prueba cruzada con jqwik y el
    fixture de divergencia (≈380–610); **B3b** = verificador, puerto, adaptador, manipulación,
    límite conocido e inventarios (≈400–660). **B3 se planifica dividido desde el principio**: su
    rango medio ya supera el presupuesto, y dividirlo al medir el diff sería descubrir tarde algo que
    ya se sabe.
- **Pronóstico de entrega: seis pull requests encadenados** (B1a, B1b, B2a-doc + B2a-sql, B2b, B3a,
  B3b, B4 fusionado con B3b o con B1b según lo que quepa). Al cerrar cada corte se mide el diff real,
  como hizo la parte A, y si supera 800 el corte se parte.
- **Tareas:** entre 17 y 21, por encima del límite de quince de `openspec/changes/README.md`. Aplica
  el precedente que el propietario concedió en los cambios 4 y 5A: el límite se mide por pull request
  cuando el cambio se entrega encadenado.

**Nota sobre la política de la sesión.** La preflight de esta sesión registra estrategia de entrega
`single-pr` y presupuesto de revisión de 400 líneas. El proyecto fija **800 líneas y cortes
encadenados** (`docs/15-flujo-de-trabajo-git.md` §3 y `CLAUDE.md`, decisión del propietario del
2026-09-18), y la exploración ya resolvió que ese conflicto no debe reabrirse. Este diseño planifica
contra 800 y cortes encadenados, y **deja constancia de la discrepancia para que el orquestador
alinee su registro**, igual que quedó anotado en el cambio 4 y en la parte A.

---

## 13. Restricciones del entorno local

- `JAVA_HOME="C:/Program Files/Eclipse Adoptium/jdk-25.0.3.9-hotspot"` y
  `MAVEN_OPTS="-Djavax.net.ssl.trustStoreType=Windows-ROOT"` (esta última **nunca se compromete**);
  siempre `./mvnw -B` desde `apps/api`, con la salida redirigida a un archivo temporal.
- El jar de `net.jqwik:jqwik` 1.10.1 **ya está en el repositorio local**, porque `kernel` lo usa; por
  tanto añadirlo a `app` no depende de una descarga nueva, que en esta máquina tiene una brecha de
  confianza PKIX conocida.
- Docker 29.7.2 con `postgres:18-alpine` ya descargado. Sobre WSL2, `tmpfs` y el primer arranque
  pueden comportarse distinto que en Linux: **ante divergencia manda la integración continua**.
- Este cambio **empeora** el tiempo de la suite de integración: añade pruebas de concurrencia con
  confirmación real y una prueba de propiedades con una llamada SQL por intento. El número de
  intentos por defecto de jqwik en `app` se fija por debajo del de `kernel` por esa razón, y el
  tiempo se mide al cerrar cada corte. Si el margen frente a los 8 minutos se estrecha de verdad, la
  deuda W1 deja de ser diferible y se eleva.
- Nunca se baja un umbral ni se omite una prueba para pasar en local.

---

## 14. Por confirmar durante la implementación

1. Las trece sondas de la sección 10.
2. Que el tipo generado para `shared_audit_log` se llama `SharedAuditLog`, que es lo que hace válido
   el razonamiento de R2 en la decisión 1.
3. Que `MultiTenantSchemaIT` pasa sobre las dos tablas nuevas **sin ninguna modificación**. Si la
   aplicación lo desmiente, se reporta la discrepancia con este diseño; **no se relaja la puerta**.
4. Que el `DSLContext` construido sobre `TransactionAwareDataSourceProxy` se une a la transacción que
   abre `TransactionRunner`, de modo que un `SELECT` de jOOQ dentro del caso de uso vea el contexto
   fijado por el componente. La parte A lo dejó probado para la transacción de la prueba; aquí la
   abre el componente.
5. Que `RAISE EXCEPTION ... USING ERRCODE = '40001'` se traduce a una excepción que el componente
   reconoce como fallo de serialización. Si el traductor no lo hiciera, la comprobación por `SQLState`
   de la decisión 2 es la que sostiene la prueba, y eso queda escrito en su Javadoc.
6. Que el número de intentos de jqwik elegido mantiene la suite de integración dentro del presupuesto
   de 8 minutos, medido, no estimado.
7. Que la restricción única `(institution_id, prev_hash)` no produce contención observable en la
   prueba de concurrencia. Si la produjera, la decisión es **mantenerla** y documentar el costo: su
   valor es convertir una bifurcación silenciosa en un error ruidoso.

Si alguna confirmación obliga a apartarse de lo decidido, se eleva a un ADR y no se entierra aquí
(`docs/13-metodologia-sdd.md`, regla 5).

---

## 15. Preguntas abiertas

- [x] **1. `actor_label`, `user_agent` y `trace_id` SÍ entran al hash. RESUELTA por el propietario
      el 2026-09-21: se incluyen los tres.** La preimagen pasa de 15 a 18 campos, en el orden de
      columnas de `docs/03` §12.1. `actor_label` es la etiqueta legible de quién ejecutó la acción:
      es a la vez el campo que un atacante querría cambiar y el primero que leería un auditor
      externo, así que dejarlo fuera del hash vaciaba de sentido la parte del control que más se
      consulta. **Consecuencias que este cambio DEBE asumir:** se actualiza la lista de campos
      firmados de `docs/03` §12.1, y el delta de `audit-trail` y la decisión 6.2 de este diseño ya
      quedan ajustados, todo antes del corte B2b. El generador de jqwik suma las familias de
      divergencia de los tres campos nuevos.

      Contexto original, que se conserva porque explica la decisión: Es lo que `docs/03` §12.1
      declara y este diseño no lo amplía, porque ampliar el conjunto firmado es una decisión sobre el
      alcance del control y no una elección de implementación. La consecuencia es real y conviene que
      el propietario la vea escrita: **un actor con acceso al motor puede cambiar la etiqueta legible
      del actor de una fila sin que la cadena lo detecte.** Recomendación: incluir los tres campos en
      la preimagen. Costo: tres líneas en cada implementación y tres familias más en el generador.
      Si se acepta, la decisión 6.2 y el delta de `audit-trail` cambian **antes** del corte B2b.
- [ ] **2. Interfaz nombrada de Spring Modulith para `com.confia.shared.security`** (sonda S12). Este
      diseño recomienda declararla ahora, porque el cambio 6 va a llamar al componente desde otro
      módulo con certeza y descubrirlo entonces sería un rojo en medio de trabajo ajeno. Si la sonda
      muestra que es ambiguo o caro, se difiere al cambio 6.
- [ ] **3. Nota editorial en ADR-0003** (D2 de la propuesta). Sigue sin resolverse; si el propietario
      prefiere no tocar ningún ADR, se omite sin consecuencia para el resto del renombrado.
- [x] **4. Presupuesto de revisión de la sesión. CERRADA por el orquestador: no había conflicto.**
      El presupuesto es de **800 líneas** de cambio efectivo, fijado por el propietario el 2026-09-18
      (`docs/15-flujo-de-trabajo-git.md` §3, `CLAUDE.md`), y la preflight de esta sesión, del
      2026-09-21, eligió **cortes encadenados** (`auto`), no `single-pr`. Los valores de 400 y
      `single-pr` provienen del registro de `sdd-init` del 2026-09-15, caducado y ya corregido. Esta
      es la tercera fase consecutiva que levanta esta falsa alarma leyendo la misma fuente obsoleta;
      queda anotado aquí para que la fase de tareas no la repita. La sección 12 ya planifica contra
      800 y cortes encadenados, así que no hay nada que cambiar en el diseño.
- [ ] **5. Método de verificación parcial para la cadencia diaria** de `docs/03` §12.4. No se entrega
      aquí por ADR-0019. Queda declarado como ampliación deliberada del cambio 9, no como olvido.
