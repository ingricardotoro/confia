# Diseño: módulo de identidad y autenticación con contraseña

Cambio 7 de F0, **primera de tres partes**. Implementa la propuesta aprobada
(`openspec/changes/identity-module-and-password-authentication/proposal.md`, seis decisiones
resueltas el 2026-09-24) y los dos deltas ya escritos: `specs/identity/spec.md` (10 requisitos
añadidos y 1 modificado, 20 escenarios) y `specs/build-integrity/spec.md` (1 requisito modificado y
2 añadidos, 7 escenarios). **Las especificaciones son el contrato**: donde este diseño proponga un
dato que un delta fija, prevalece el delta. Donde este diseño necesite que un delta cambie para ser
implementable sin abrir un agujero, **lo dice con la redacción exacta propuesta** y no lo implementa
mal en silencio: ver la sección 15. La sección 7.1 traza los 27 escenarios contra su prueba.

Decisiones del propietario que este diseño **implementa y no reabre**: estado del retroceso en
PostgreSQL y no en Redis (D1); gobierna el modelo de retardo de `docs/03-seguridad.md` §4.4 sobre el
requisito publicado de bloqueo (D2); la institución previa a la autenticación proviene de la
configuración del proceso (D4); las cuatro exclusiones con destino nombrado (D5); y el tipo sellado
con exactamente dos desenlaces, sin campo de alcance.

---

## 1. Enfoque técnico

De adentro hacia afuera, como el cambio 5B, en tres cortes encadenados. Cada corte deja
`./mvnw verify` en verde y sigue **TDD estricto** (`openspec/config.yaml`, `strict_tdd: true`,
ejecutor `./mvnw verify` en `apps/api`): rojo observado y registrado antes de cada verde.

| Corte | Contenido |
|---|---|
| **C1** | Módulo `identity` con sus tres capas y su `package-info`; interfaces nombradas de Spring Modulith para `shared` (decisión 12) con su ADR; migración `V5` con las **dos** tablas, política de fila y privilegios; extensión de `RolePrivilegeMatrixIT`; cierre no vacuo del escenario W2 |
| **C2** | Objetos de valor con redacción, `BackoffPolicy` puro, tipo sellado del resultado, puerto de hash con su adaptador Argon2id sobre Bouncy Castle, códec del formato `$argon2id$`, pimienta, hash señuelo, huella del identificador |
| **C3** | Adaptadores jOOQ de cuenta y de retroceso, caso de uso completo, puerto y adaptador de escritura de auditoría, atomicidad y concurrencia, medición de uniformidad de tiempo, inventarios de exclusión, notas editoriales |

### 1.1 El orden «módulo antes que migración» es una dependencia mecánica, verificada

`MultiTenantSchemaIT.everyBusinessTableNameCarriesItsOwnerModulesPrefixExceptTheClosedCatalogue`
(líneas 116–136) exige que el prefijo de toda tabla de negocio nombre un módulo **que exista de
verdad**, y el conjunto de módulos lo deriva `productionModuleNames()` (líneas 148–156) del código
de producción real, con `DO_NOT_INCLUDE_TESTS`:

```java
new ClassFileImporter()
        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
        .importPackages("com.confia").stream()
        .map(JavaClass::getPackageName)
        ...
        .map(name -> name.substring("com.confia.".length()).split("\\.")[0])
```

Verificado por listado del árbol: hoy **no existe ninguna clase bajo
`apps/api/app/src/main/java/com/confia/identity/`**; solo viven `bootstrap`, `organization` y
`shared`. Una migración que cree `identity_staff_account` antes de que exista una clase de
producción en `com.confia.identity.*` rompe esa puerta. Es la misma invariante que ató B1 antes de
B2a en el cambio 5B, y su consecuencia para el plan de reversión ya está escrita en la propuesta:
**C2 y C3 no se revierten sin C1**.

El prefijo se extrae con `table.name().indexOf('_')`, el texto antes del primer guion bajo:
`identity_staff_account` → `identity` y `identity_login_backoff` → `identity`. Las dos tablas pasan
la puerta con las mismas clases de producción.

### 1.2 El problema que gobierna el resto del diseño

El requisito modificado exige un retardo **antes de responder** de hasta 900 segundos, y prohíbe
convertirlo en rechazo. La lectura literal —dormir el hilo que atiende la petición— convierte el
control anti-fuerza-bruta en un amplificador de denegación de servicio. La decisión 1 resuelve dónde
se materializa la espera y qué contrato deja escrito este cambio; la decisión 2 dice en voz alta qué
protege ese control y qué **no** protege. Todo lo demás —frontera transaccional, forma del
resultado, estrategia de pruebas— se deriva de ahí.

---

## 2. Evidencia obtenida en esta fase y límites

Esta fase **no dispuso de herramienta de ejecución de procesos** (ni Maven, ni Docker, ni
PostgreSQL, ni intérprete de comandos). Todo lo de abajo es lectura de archivos reales del árbol. Lo
que exige ejecutar algo está marcado como **no verificado** y aparece como sonda numerada en la
sección 10. Ninguna decisión de este documento afirma como comprobado algo que no se comprobó.

| Pregunta | Resultado | Evidencia |
|---|---|---|
| ¿Existe hoy `com.confia.identity`? | **Verificado: no.** Solo `bootstrap`, `organization` y `shared` | Listado de `apps/api/app/src/main/java/com/confia/` |
| ¿Cuántas migraciones hay? | **Verificado: cuatro** (`V1`–`V4`). La nueva es `V5` | Listado de `apps/api/app/src/main/resources/db/migration/` |
| ¿La puerta de prefijo de módulo obliga a crear el paquete antes que la tabla? | **Verificado: sí** | `MultiTenantSchemaIT.java:116-136,148-156` |
| ¿La puerta de índices únicos ve las columnas de un índice **de expresión**? | **Verificado: no.** Une `pg_attribute` con `a.attnum = any(ix.indkey)`; una expresión ocupa `indkey = 0` y no casa con ninguna columna. Un índice único sobre `lower(email)` pasaría la puerta **sin que la puerta vea la expresión**: por eso la decisión 4 usa una restricción única sobre columnas planas y normaliza en la aplicación | `MultiTenantSchemaIT.java:298-308` |
| ¿Qué prefijo de tipo generado exige R2 para `com.confia.identity.infrastructure`? | **Verificado: `Identity`.** `moduleOf` devuelve el segmento anterior al primer segmento de capa | `TableOwnershipByModuleTest.java:52-78,89-97` |
| ¿Puede `identity.application` depender de `com.confia.shared.security` sin romper la regla de capas? | **Verificado por lectura: sí.** `consideringOnlyDependenciesInLayers()` marca irrelevante toda dependencia cuyo origen **o destino** no encaje en ninguna capa declarada, y `shared.security` no lleva segmento de capa. El propio Javadoc de la prueba registra que ese comportamiento se confirmó decompilando archunit con `javap -c` | `LayeredArchitectureTest.java:28-42,62-71,90-103` |
| ¿Y sin romper la verificación de Spring Modulith? | **NO verificado, y es el riesgo estructural nuevo de este cambio.** Modulith trata **cada sub-paquete directo de `com.confia` como un módulo**, y `verify()` lanza `Violations` si un módulo accede a un paquete interno no expuesto de otro. `com.confia.shared.security` y `com.confia.shared.audit` son paquetes anidados de `shared`. Hoy el árbol no lo demuestra ni lo desmiente: **ninguna clase de producción cruza hoy de un módulo de negocio a otro** (verificado por búsqueda de importaciones). `kernel` funciona porque sus tipos viven en el **paquete base** del módulo, que sí es API por omisión. Decisión 12, sonda **S1** | `SpringModulithVerificationTest.java:13-30,33-39`; búsqueda de `import com.confia.*` en `src/main/java` |
| ¿`spring-modulith-core` está disponible en producción? | **Verificado: no.** Está declarado con `<scope>test</scope>` | `apps/api/app/pom.xml:110-114` |
| ¿Hay hoy alguna biblioteca criptográfica o de Spring Security en el módulo `app`? | **Verificado: ninguna.** Las dependencias son `confia-kernel`, `spring-boot-starter-web`, `spring-boot-starter-jdbc`, `jooq`, `flyway`, `postgresql` y las de prueba | `apps/api/app/pom.xml:25-123` |
| ¿La lista de dependencias prohibidas del enforcer alcanza a Bouncy Castle o a Spring Modulith? | **Verificado: no.** Prohíbe Hibernate, JPA, `spring-data-jpa`, `spring-data-jdbc`, Quartz y JobRunr | `apps/api/pom.xml:340-357` |
| ¿Qué versión de Java y de Spring Boot? | **Verificado: Java 25 y Spring Boot 4.1.1** | `apps/api/pom.xml:30,33` |
| ¿Hay hilos virtuales configurados? | **Verificado: no.** Ninguna aparición de `spring.threads.virtual` ni de `Thread.ofVirtual` en `apps/api`. `spring-boot-starter-web` sí está, pero ningún proceso levanta un servidor con `DataSource` real todavía | Búsqueda en `apps/api`; `apps/api/app/pom.xml:34` |
| ¿Cuál es el reloj inyectado del árbol? | **Verificado: `java.time.Clock` por constructor, fijado con `Clock.fixed(...)` en las pruebas.** Es el precedente que exige la sección 7.2 | `IdempotentExecutor.java:66,72,206,221`; `IdempotencyExpiryIT.java:24-26,43` |
| ¿El componente transaccional fija el contexto y reintenta? | **Verificado.** Cuatro `set_config` vinculados como primera sentencia, reintento acotado ante `40001`/`40P01` | `TransactionRunner.java:53-58,83-108,145-158` |
| ¿El disparador de encadenamiento ignora lo que pase el llamador en `id`, `prev_hash` y `row_hash`? | **Verificado: sí, los sobrescribe siempre** | `V3__chain_shared_audit_log.sql:217-237` |
| ¿Qué patrón sigue una migración nueva? | **Verificado.** `REVOKE ALL ... FROM PUBLIC` antes de todo `GRANT`, `ENABLE` más `FORCE ROW LEVEL SECURITY`, política con `NULLIF(current_setting(..., true), '')` y `WITH CHECK` explícito | `V4__create_shared_idempotency_key.sql:64-89` |
| ¿Los cinco roles son `NOBYPASSRLS`? | **Verificado**, y la contraseña literal de prueba está declarada como no secreta en su propio comentario | `create-test-roles.sql:8-18` |
| ¿Las puertas de cobertura y de mutación alcanzan a `identity.domain` sin tocar el POM? | **Verificado: sí.** JaCoCo incluye `com.confia.*.domain` y `com.confia.*.domain.*` al 95 %; PIT apunta a `com.confia.*.domain.*` | `apps/api/app/pom.xml:199-207,368-374` |
| ¿`Argon2PasswordEncoder` de Spring carece de parámetro de pimienta y `Argon2BytesGenerator` de Bouncy Castle lo tiene? | **NO verificado.** Ninguna de las dos bibliotecas está en el árbol, y esta fase no puede abrir un jar ni compilar. La propuesta lo anotaba también sin verificar. Decisión 6, sonda **S2** | — |
| ¿`INSERT ... ON CONFLICT DO UPDATE SET x = tabla.x RETURNING ...` toma el bloqueo de fila y devuelve el estado previo? | **Parcialmente verificado.** La sonda S4 del cambio 5B demostró, contra `postgres:18-alpine`, que `ON CONFLICT ... DO UPDATE ... RETURNING` **sí** toma el bloqueo por fila y bloquea a un segundo escritor hasta la confirmación del primero. Que un `SET` idempotente devuelva los valores previos no está comprobado. Decisión 5, sonda **S3** | `openspec/changes/archive/2026-09-22-audit-log-and-transaction-runner/design.md`, sección 10, resultado de S4 |
| ¿`Thread.sleep` sobre un hilo virtual libera el hilo portador en Java 25? | **NO verificado.** Es la afirmación de la que depende la recomendación de la decisión 1 para el cambio 3, y esta fase no puede ejecutar un programa. Sonda **S5** | — |

**Consecuencia de método.** Donde una decisión depende de un comportamiento no comprobado, este
diseño nombra la sonda y **deja una alternativa ya diseñada con su criterio de conmutación**.

---

## 3. Decisiones de arquitectura

### Decisión 1 — Dónde se materializa la espera: el caso de uso **calcula y exige**, el borde **cumple**

Es la decisión de arquitectura de este cambio.

**El problema, exacto.** `docs/03` §4.4 (línea 399) dice que «el retardo se aplica **antes** de
responder», con un tope de 900 segundos (línea 395), y el delta ya escrito prohíbe convertirlo en
rechazo con todas las letras: «el retroceso demora la respuesta, no la deniega», con un escenario que
exige que una contraseña correcta durante el retroceso **tenga éxito tras el retardo**. Las dos
salidas fáciles están cerradas: dormir el hilo que atiende la petición hasta quince minutos convierte
el control en un amplificador de denegación de servicio —agotar el grupo de hilos sale más barato que
adivinar la contraseña—, y responder de inmediato con un rechazo es el modelo de bloqueo que D2
descartó.

**Elección.** El retardo se parte en dos responsabilidades, y este cambio entrega la primera con el
contrato escrito de la segunda:

1. **El caso de uso calcula la duración exigible y la devuelve.** `AuthenticateWithPassword` no
   espera. Termina su transacción, la confirma, y devuelve un
   `AuthenticationDecision(AuthenticationResult result, Duration requiredDelay)`. La duración es
   parte del resultado del caso de uso, igual que el desenlace.
2. **El borde HTTP —`session-tokens-and-web-layer`— materializa la espera**, fuera de toda
   transacción, y solo entonces escribe la respuesta.

**Tres propiedades que esta partición compra, y que ninguna alternativa da a la vez:**

- **Nada de infraestructura de base de datos se retiene durante la espera.** Cuando empieza el
  retardo, la transacción ya confirmó: no hay conexión del grupo tomada, ni bloqueo sobre la fila de
  retroceso, ni bloqueo sobre la cabecera de cadena de la bitácora. Un retardo de 900 segundos cuesta
  exactamente cero recursos de PostgreSQL. Ese es el agujero que había que cerrar y está cerrado del
  lado de este cambio, no del lado de una recomendación al cambio siguiente.
- **Se prueba sin esperar.** Lo que se afirma es la duración **calculada y exigida**, no la vivida:
  `decision.requiredDelay()` es un `Duration` comparado con `Duration.ofSeconds(900)`. Ninguna prueba
  de este cambio duerme, en ningún punto (sección 7.2).
- **El desenlace no depende de la espera.** El retroceso no rechaza: una contraseña correcta durante
  el retroceso produce `Authenticated` con `requiredDelay` de dos segundos, y el llamador espera y
  entrega el éxito. El escenario que distingue el modelo aprobado del bloqueo descartado es
  literalmente una aserción sobre dos campos de un registro.

**Alternativas evaluadas.**

| Opción | Qué pasa | Veredicto |
|---|---|---|
| **Dormir dentro del caso de uso, dentro de la transacción** | Retiene una conexión del grupo, el bloqueo de la fila de retroceso y —si la auditoría ya se insertó— la cabecera de cadena de la institución, hasta 900 segundos. Un atacante bloquea la auditoría de toda la institución con una petición | **Descartada.** Es el agujero, escrito con otras palabras |
| **Dormir dentro del caso de uso, después de confirmar** | Ya no retiene base de datos, pero sí el hilo del llamador, y sitúa una espera dentro de la capa `application`, donde una regla de arquitectura no la puede distinguir de un error | **Descartada.** Además haría imposible probar el caso de uso sin esperar |
| **Rechazar de inmediato con `Retry-After`** | Es un bloqueo con otro nombre | **Prohibida** por D2 y por el delta |
| **Devolver la duración exigible y materializarla en el borde** | Lo de arriba | **Elegida** |
| **Programar la respuesta con una tarea diferida (`db-scheduler`)** | ADR-0016 reserva `db-scheduler` para trabajo de fondo, y una respuesta HTTP pendiente no es trabajo de fondo: exige que la petición siga viva | **Descartada** |

**El contrato que este cambio deja escrito para `session-tokens-and-web-layer`**, en el Javadoc de
`AuthenticationDecision` y en la sección 6.2, para que no se reinvente:

1. **No se entrega la respuesta antes de que transcurra `requiredDelay`**, contado desde que el caso
   de uso devuelve. Se suma, no se absorbe: restar el tiempo ya consumido por Argon2id haría que la
   espera variara en sentido inverso al trabajo real y reintroduciría por la puerta de atrás el canal
   lateral de tiempo que §4.6 existe para cerrar.
2. **La espera ocurre fuera de toda transacción.** Está garantizado por construcción: el caso de uso
   confirma antes de devolver.
3. **La espera no retiene un hilo de plataforma.** La forma recomendada, sujeta a la sonda S5, es
   `Thread.sleep` sobre un hilo virtual con `spring.threads.virtual.enabled=true`: Java 25 trae
   hilos virtuales definitivos y, desde Java 24, un bloque `synchronized` ya no ancla el hilo virtual
   a su portador, de modo que la espera aparca la continuación y devuelve el hilo de plataforma al
   grupo. La alternativa, si S5 desmintiera algo, es Servlet asíncrono (`DeferredResult` o
   `CompletableFuture`) con un único planificador: libera el hilo de petición incluso sin hilos
   virtuales, a cambio de más maquinaria.
4. **El número de respuestas retardadas simultáneas está acotado**, y al alcanzar el límite el
   servidor responde con un error de capacidad **uniforme**, decidido antes de procesar el intento y
   por tanto independiente de si la cuenta existe. Jamás acorta el retardo para hacer sitio.
5. **La forma de la respuesta es idéntica en los dos desenlaces de rechazo**, y el motivo interno
   nunca viaja.
6. **Si el cliente cierra la conexión, la espera se abandona sin más.** Ningún efecto depende de
   ella: el contador ya avanzó y el asiento de auditoría ya está escrito. Ver la decisión 2.

**Guarda que este cambio sí puede poner hoy**, para que el agujero no vuelva por dentro: una regla
de ArchUnit rechaza que cualquier clase de producción bajo `com.confia.identity..` llame a
`Thread.sleep`, `TimeUnit.sleep`, `Object.wait` o `LockSupport.park*` (decisión 10, punto 4). No es
decorativa: es la única forma de que «el módulo de identidad nunca espera» sea una afirmación
verificada y no una nota de diseño.

### Decisión 2 — Qué protege el retardo, y qué no: se dice en voz alta

El retardo **no es un limitador de tasa**, y este diseño no finge que lo sea. Un atacante que cierra
la conexión después de enviar la petición no espera nada: el servidor ya hizo el trabajo y el
atacante ya obtuvo la única información que el canal le da —ninguna—. Lo que el retardo hace, y hace
bien, es:

- **Igualar el tiempo observable** entre cuenta existente e inexistente, que es el control de §4.6.
- **Penalizar al atacante ingenuo y secuencial**, que es la mayoría del tráfico real de relleno de
  credenciales contra una institución pequeña.
- **Dejar rastro**: cada ciclo queda auditado con su duración, y esa serie es la señal que un
  operador mira.

Lo que **no** hace es acotar la tasa de intentos de un atacante que no coopera. Eso lo hace la
dimensión por dirección IP de §4.4, que este cambio excluye con dueño nombrado —control en
`session-tokens-and-web-layer`, aprovisionamiento en el cambio 11— y cuyo delta ya está escrito. Esta
sección existe para que nadie lea el retroceso por cuenta como si cerrara esa brecha.

**Consecuencia sobre el tope de 900 segundos, declarada.** Ninguna respuesta HTTP retenida quince
minutos llega a un navegador real: cualquier intermediario razonable cierra la conexión antes. Eso
**no** rompe la uniformidad —las dos ramas se retardan igual y las dos mueren igual—, pero sí
significa que, por encima de cierto umbral de transporte, el retardo entregado deja de existir
mientras la duración **exigida y auditada** sigue siendo la del modelo. Este cambio conserva el
modelo intacto: calcula 900 segundos, los exige y los audita. Fijar un techo de transporte para la
espera efectivamente entregada es decisión de `session-tokens-and-web-layer`, que es quien tiene
servidor y cliente; queda escrito aquí y como pregunta abierta 1 para que ese cambio no lo descubra
en producción.

### Decisión 3 — Dos tablas, no una, y por qué el delta necesita ajustarse

**El problema, exacto.** El escenario «El retardo se aplica también a una cuenta inexistente,
calculado sobre el correo presentado» exige estado de retroceso **para identificadores que no
corresponden a ninguna cuenta**. Ese estado no cabe en la fila de una cuenta que no existe. El delta
de `build-integrity` habla en singular de «la tabla nueva de identidad … para la cuenta de personal y
su estado de retroceso»; con una sola tabla, ese escenario es inimplementable.

**Elección: dos tablas, y el estado de retroceso vive íntegro en la segunda, también para las cuentas
que sí existen.**

| Tabla | Qué guarda | Clave |
|---|---|---|
| `identity_staff_account` | La cuenta de personal: identificador, correo normalizado y hash Argon2id. **Ningún contador** | `(institution_id, id)` |
| `identity_login_backoff` | Contador de fallos consecutivos y marca del último intento, **por identificador presentado**, exista o no la cuenta | `(institution_id, identifier_hash)` |

**Por qué el contador no vive en la fila de la cuenta, que es lo que uno escribiría primero.** Si
viviera allí, habría dos caminos: uno con fila y otro sin ella. Dos caminos son dos tiempos y dos
conjuntos de defectos, y §4.6 pide exactamente lo contrario. Con el contador siempre en
`identity_login_backoff`, **el código del retroceso es literalmente el mismo** para una cuenta que
existe y para un correo inventado: la misma sentencia, el mismo bloqueo, el mismo número de
escrituras, el mismo número de asientos de auditoría. La uniformidad deja de ser una promesa y pasa a
ser una propiedad estructural.

**La clave no es el correo presentado, sino su huella con llave.** `identifier_hash` es el
HMAC-SHA-256 del identificador normalizado, en texto hexadecimal de 64 caracteres, calculado con una
subllave derivada de la pimienta (decisión 6). Tres razones, en orden de peso:

1. **La tabla no se convierte en un depósito de cadenas controladas por el atacante.** Sin huella,
   cualquiera puede escribir filas con el texto que quiera, del largo que quiera.
2. **No es un oráculo de enumeración para quien lea la tabla.** Es la misma preocupación que P2
   resuelve para `actor_label`, aplicada al esquema: un auditor que consulta
   `identity_login_backoff` no aprende qué correos se intentaron.
3. **Tamaño fijo.** La clave primaria es un índice B-tree, y un correo de 320 caracteres como clave
   es una mala idea antes que cualquier otra consideración.

**Texto hexadecimal y no `BYTEA`**, siguiendo el precedente literal de `request_hash` en `V4`: la
igualdad en Java es `String.equals`, sin la trampa del `==` sobre arreglos de bytes, y un `CHECK` con
expresión regular deja el formato visible en el catálogo
(`V4__create_shared_idempotency_key.sql:21-22` y su comentario de las líneas 38-40).

**Sin clave foránea hacia la cuenta, y se dice en voz alta**: la fila existe precisamente cuando no
hay cuenta a la que apuntar.

**Costo aceptado, también en voz alta.** Un ataque distribuido contra correos aleatorios hace crecer
`identity_login_backoff` sin cota. No se entrega índice ni trabajo de purga: siguiendo el precedente
del cambio 6 —que se negó a enviar un índice sobre `expires_at` sin consumidor—, la purga se declara
como brecha con dueño nombrado (sección 15, ajuste 2) y no como código sin usar. La cota real de ese
crecimiento es el control por IP, que ya tiene dueño.

**Ajuste al delta.** La redacción en singular de `specs/build-integrity/spec.md` debe pasar a dos
tablas. Redacción exacta en la sección 15, ajuste 1. **Es bloqueante**: sin él, el delta y el esquema
entregado no coinciden y el archivado fusionaría una mentira.

### Decisión 4 — DDL de `V5`, con su política de fila y sus privilegios (P4)

```sql
CREATE TABLE identity_staff_account (
    institution_id UUID        NOT NULL,
    id             UUID        NOT NULL,
    email          TEXT        NOT NULL,
    password_hash  TEXT        NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT identity_staff_account_pk        PRIMARY KEY (institution_id, id),
    CONSTRAINT identity_staff_account_email_uq  UNIQUE (institution_id, email),
    CONSTRAINT identity_staff_account_email_lower_chk CHECK (email !~ '[A-Z]'),
    CONSTRAINT identity_staff_account_email_len_chk   CHECK (char_length(email) BETWEEN 3 AND 320),
    CONSTRAINT identity_staff_account_password_hash_chk CHECK (password_hash LIKE '$argon2id$%')
);

CREATE TABLE identity_login_backoff (
    institution_id       UUID        NOT NULL,
    identifier_hash      TEXT        NOT NULL,
    consecutive_failures INTEGER     NOT NULL DEFAULT 0,
    last_attempt_at      TIMESTAMPTZ NOT NULL,
    CONSTRAINT identity_login_backoff_pk PRIMARY KEY (institution_id, identifier_hash),
    CONSTRAINT identity_login_backoff_hash_chk     CHECK (identifier_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT identity_login_backoff_failures_chk CHECK (consecutive_failures >= 0)
);

ALTER TABLE identity_staff_account ENABLE ROW LEVEL SECURITY;
ALTER TABLE identity_staff_account FORCE  ROW LEVEL SECURITY;
ALTER TABLE identity_login_backoff ENABLE ROW LEVEL SECURITY;
ALTER TABLE identity_login_backoff FORCE  ROW LEVEL SECURITY;

CREATE POLICY identity_staff_account_institution_isolation ON identity_staff_account
    USING      (institution_id = NULLIF(current_setting('app.institution_id', true), '')::uuid)
    WITH CHECK (institution_id = NULLIF(current_setting('app.institution_id', true), '')::uuid);

CREATE POLICY identity_login_backoff_institution_isolation ON identity_login_backoff
    USING      (institution_id = NULLIF(current_setting('app.institution_id', true), '')::uuid)
    WITH CHECK (institution_id = NULLIF(current_setting('app.institution_id', true), '')::uuid);

REVOKE ALL ON identity_staff_account FROM PUBLIC;
REVOKE ALL ON identity_login_backoff FROM PUBLIC;

GRANT SELECT, INSERT, UPDATE ON identity_staff_account TO confia_admin_app;
GRANT SELECT, INSERT, UPDATE ON identity_login_backoff TO confia_admin_app;
GRANT SELECT                  ON identity_staff_account TO confia_readonly;
GRANT SELECT                  ON identity_login_backoff TO confia_readonly;
-- confia_portal_app: ningún GRANT sobre ninguna de las dos (docs/03 §6.1, «Sin acceso alguno a
-- user, role, cai_range, cashbox_session ni shared_audit_log»).
-- confia_owner: propietario del esquema, no sujeto a GRANT/REVOKE, sin BYPASSRLS.
-- confia_backup: lee por pg_read_all_data, que no omite la seguridad a nivel de fila.
```

Puntos que no son obvios:

1. **`CHECK (email !~ '[A-Z]')` y no `CHECK (email = lower(email))`.** `lower()` depende de la
   intercalación de la base, y `String.toLowerCase(Locale.ROOT)` de Java no coincide con ella para
   todo Unicode —la `İ` turca es el contraejemplo clásico—. Una restricción que compara contra
   `lower()` pasaría en la imagen de prueba y podría rechazar en otra: exactamente la familia
   «verde por la razón equivocada» que este repositorio ya pagó tres veces. La expresión regular
   sobre `A-Z` es independiente de la intercalación y cubre el caso que de verdad importa, que es un
   correo escrito con mayúsculas ASCII.
2. **La normalización la hace la aplicación** (decisión 11) y el esquema solo la comprueba. La
   columna guarda el identificador ya normalizado: la forma de presentación del correo no se
   conserva en este cambio, y se dice en voz alta porque no hay pantalla que la necesite; el cambio
   que la necesite añade su columna.
3. **`CHECK (password_hash LIKE '$argon2id$%')`.** Es la expresión en el catálogo de lo que §4.1
   pide comprobar («el hash almacenado comienza con `$argon2id$`»), y hace imposible que una
   contraseña en claro llegue a esa columna por descuido.
4. **`created_at` con `clock_timestamp()`**, como `V4`, y no `now()`, que es el instante de inicio de
   la transacción.
5. **`last_attempt_at` sin valor por omisión.** Siempre lo escribe el adaptador con el reloj
   inyectado: el estado del retroceso lo decide el reloj de Java, nunca el del motor, porque es lo
   que hace comprobable el escenario de expiración a los 30 minutos sin esperar 31.
6. **Las dos tablas pasan las cuatro puertas genéricas sin lista de exclusión**: llevan
   `institution_id NOT NULL`, tienen seguridad de fila habilitada y forzada, todas sus restricciones
   únicas contienen `institution_id`, y sus nombres llevan el prefijo de un módulo que existe.
7. **`UPDATE` para `confia_admin_app` no es una excepción, es la regla aplicada** (§6.1: `UPDATE` en
   tablas no financieras). Hace falta para limpiar el contador tras un inicio de sesión exitoso y
   para incrementarlo. **Ningún `DELETE` a ningún rol**: limpiar el contador es poner cero, nunca
   borrar la fila, por la misma razón que el cambio 6 modeló la caducidad como `UPDATE`.

**Correspondencia documental, para la nota editorial de §6.1.** La tabla que §6.1 llama `user` es
aquí `identity_staff_account`, por la regla 3 de ADR-0015 y el precedente literal de
`shared_audit_log`. `identity_login_backoff` es tabla nueva que §6.1 no nombra, y recibe el mismo
trato que `user` por ser parte del mismo conjunto de datos de identidad: ningún privilegio para
`confia_portal_app`.

### Decisión 5 — Cómo se lee y se escribe el estado del retroceso sin perder escrituras

**Elección: una sentencia de reclamo que crea o bloquea la fila y devuelve el estado previo, la
decisión en el dominio, y una actualización simple al final.**

```sql
-- 1. Reclamo: crea la fila si no existe, la bloquea si existe, y devuelve el estado previo.
INSERT INTO identity_login_backoff (institution_id, identifier_hash, consecutive_failures,
                                    last_attempt_at)
VALUES (?, ?, 0, ?)
ON CONFLICT (institution_id, identifier_hash) DO UPDATE
    SET consecutive_failures = identity_login_backoff.consecutive_failures
RETURNING consecutive_failures, last_attempt_at;

-- 2. …el dominio decide…

-- 3. Cierre: el estado nuevo, ya calculado en Java.
UPDATE identity_login_backoff
   SET consecutive_failures = ?, last_attempt_at = ?
 WHERE institution_id = ? AND identifier_hash = ?;
```

**Por qué el reclamo es un `ON CONFLICT DO UPDATE` con asignación idempotente y no un
`SELECT ... FOR UPDATE`.** `FOR UPDATE` no bloquea una fila que no existe: dos primeros intentos
concurrentes contra el mismo identificador llegarían los dos al `INSERT` y uno moriría con `23505`,
que `TransactionRunner` **no** reintenta —solo reintenta `40001` y `40P01`
(`TransactionRunner.java:145-158`)—. La rama de inserción del `ON CONFLICT` resuelve la creación y la
rama de actualización toma el bloqueo, que es justo lo que la sonda S4 del cambio 5B ya demostró
contra `postgres:18-alpine`: la segunda sesión queda bloqueada hasta la confirmación de la primera.

**Por qué la decisión se toma en Java y no en la sentencia.** La regla tiene tres partes —umbral,
progresión con tope y expiración del contador a los 30 minutos— y las tres dependen del tiempo.
Escribirlas en SQL significaría usar el reloj del motor, y el escenario de expiración dejaría de
poder probarse sin esperar 31 minutos. Con el reloj inyectado y la regla en `identity.domain`, la
expiración se prueba con dos `Clock.fixed` y la regla entra además en las puertas del 95 % de
cobertura y del 80 de mutación, que es donde este repositorio quiere su lógica valiosa.

**Costo aceptado.** El reclamo escribe una versión nueva de la fila aunque no cambie nada, y el
cierre escribe otra: dos tuplas muertas por intento, con su carga de autovacío. La alternativa
—leer sin bloquear y confiar en el cierre— pierde escrituras bajo concurrencia, que es exactamente
el escenario que el delta exige demostrar.

**Alternativa descartada, nombrada para que nadie la proponga al implementar:** un
`UPDATE ... SET consecutive_failures = consecutive_failures + 1` que calcule la expiración con un
`CASE` sobre `now()`. Es una sentencia menos y duplica la regla en dos lenguajes, con el reloj del
motor de un lado y el de Java del otro. El cambio 5B ya pagó por saber lo que cuesta mantener un
algoritmo escrito dos veces.

**Sin `lock_timeout` en este cambio, y dicho con precisión.** `IdempotentExecutor` fija
`lock_timeout` como primera sentencia de sus transacciones porque su requisito exige una espera
acotada. Aquí ningún requisito lo exige, y la espera de un segundo intento concurrente contra el
mismo identificador está acotada en la práctica por una verificación Argon2id. Fijarlo convertiría
la contención en un `55P03` que este caso de uso tendría que traducir a algo, y no hay nada sano a lo
que traducirlo: no es `Rejected` —el retroceso no deniega— ni es éxito. Queda como consideración con
dueño en `session-tokens-and-web-layer`, que es quien tiene tiempos de espera de petición.

### Decisión 6 — Argon2id: biblioteca, pimienta y formato almacenado (P1)

**Lo que la propuesta anotaba sin verificar, y sigue sin verificarse aquí.** Que
`Argon2PasswordEncoder` de `spring-security-crypto` no expone parámetro de secreto y que
`Argon2BytesGenerator` de Bouncy Castle sí lo hace con `withSecret(...)`. **Esta fase no pudo
comprobarlo**: ninguna de las dos bibliotecas está en el árbol —verificado leyendo
`apps/api/app/pom.xml:25-123`— y no hay herramienta para abrir un jar ni compilar. Se deja como
sonda **S2** con su comando exacto, y la decisión de abajo queda condicionada a su resultado, con la
alternativa ya diseñada.

**Elección: Bouncy Castle directo (`org.bouncycastle:bcprov-jdk18on`), con códec propio del formato
`$argon2id$`, y sin `spring-security-crypto` en el árbol.**

Tres razones:

1. **La pimienta es obligatoria.** §4.1 exige un secreto de 32 bytes fuera de la base, «aplicado como
   `secret` de Argon2id». Si S2 confirma lo anotado, el codificador de Spring no puede aplicarla como
   `secret` y la decisión se toma sola.
2. **Añadir `spring-security-crypto` no ahorra la dependencia criptográfica**, porque su propio
   soporte de Argon2 se apoya en Bouncy Castle. Con Bouncy Castle directo hay **una** dependencia
   nueva, no dos.
3. **No acerca Spring Security ni un milímetro.** La propuesta ya precisaba que
   `spring-security-crypto` no es `spring-boot-starter-security`; aun así, no traerlo elimina de raíz
   cualquier discusión sobre si este cambio adelanta trabajo de `session-tokens-and-web-layer`.

**Alternativa diseñada, por si S2 desmiente lo anotado:** `Argon2PasswordEncoder` con la pimienta
aplicada como pre-hash, es decir Argon2id sobre `HMAC-SHA-256(pimienta, contraseña)`. Es una
construcción conocida y sólida, y su costo es una desviación declarada de la letra de §4.1 («aplicado
como `secret`»), que exigiría su propia nota editorial. Se conmuta solo si S2 lo obliga.

**Parámetros**, tomados literalmente de la tabla de §4.1 (líneas 297-304) y declarados como **piso,
no como valor calibrado** —la calibración es del cambio 11 y su ausencia ya es un requisito con
escenario en el delta—:

| Parámetro | Valor |
|---|---|
| `memoryCost` | 19456 KiB |
| `timeCost` | 3 |
| `parallelism` | 1 |
| Longitud de salida | 32 bytes |
| Sal | 16 bytes de `SecureRandom`, por contraseña |
| Pimienta | 32 bytes, fuera de la base, aplicada como `secret` |

**Formato almacenado**, el formato PHC que §4.1 pide comprobar:

```
$argon2id$v=19$m=19456,t=3,p=1$<sal en base64 sin relleno>$<etiqueta en base64 sin relleno>
```

**La pimienta no entra en la cadena almacenada.** Es un secreto y la cadena vive en la base: si
viajara con el hash, no serviría para nada. Un volcado de la base sin el gestor de secretos no
permite ataque por diccionario, que es exactamente lo que §4.1 compra con ella.

**La verificación usa los parámetros de la cadena almacenada, no los vigentes.** Es como funciona el
formato PHC y es lo coherente con la exclusión ya escrita: el hash no se recalcula aunque sus
parámetros difieran. Hoy coinciden, porque todas las cuentas se crean con el perfil vigente.

**Cómo se comprueba el códec sin confiar en él**: una prueba unitaria contra el **vector de prueba de
Argon2id de RFC 9106 §5.3**, que incluye un valor de `secret`, comparando la etiqueta producida byte
a byte. Es la única forma determinista de demostrar que `withSecret(...)` se está usando de verdad y
no ignorando en silencio; un hash que «funciona» sin aplicar la pimienta es indistinguible de uno
correcto salvo contra un vector conocido.

**Entrega de la pimienta.** Objeto de valor `Argon2Pepper` con `toString()` redactado, construido
desde configuración del proceso (variable de entorno, contenido en base64, 32 bytes exactos), que
**falla al construirse** si falta o si mide otra cosa. En pruebas se usa una pimienta literal
declarada no secreta en su propio comentario, el patrón ya revisado de `create-test-roles.sql:8-10`.
El cableado real desde el gestor de secretos es del cambio 11; aquí llega por constructor, como
`TransactionRunner` y `JooqInstitutionRepository` reciben lo suyo.

**Subllave para la huella del identificador, con separación de dominio.** La huella de la decisión 3
no usa la pimienta directamente: usa
`HMAC-SHA-256(pimienta, "confia.identity.login-identifier.v1")` como llave. Reutilizar la misma
llave para dos primitivas distintas es una mala práctica barata de evitar, y la etiqueta deja
espacio a una versión futura sin renombrar nada.

**Revisión de dependencia.** `bcprov-jdk18on` es dependencia nueva y pasa por la revisión obligatoria
de `docs/03` §2.6, fila «Dependencia maliciosa ejecuta código en la instalación». Verificado: no está
en la lista de prohibidas del `maven-enforcer-plugin` (`apps/api/pom.xml:340-357`). Su versión se
declara en el `dependencyManagement` del POM padre, y la convergencia del enforcer se comprueba en el
mismo corte (sonda **S6**).

### Decisión 7 — La forma del hash señuelo (P3)

**Elección: el señuelo se **calcula** al construir el verificador, hasheando una etiqueta constante
con los parámetros vigentes y la pimienta vigente, con una sal constante.**

```java
private static final String DECOY_LABEL = "confia.identity.decoy.v1";
// sal constante de 16 bytes, literal en el código: una sal no es un secreto
this.decoyHash = hasher.hash(PlainPassword.of(DECOY_LABEL), DECOY_SALT);
```

**Por qué calculado y no un literal `$argon2id$…` en el código.** Un literal queda congelado con los
parámetros del día que se escribió: cambiar `memoryCost` lo dejaría costando menos que una
verificación real, y el control de tiempo se vaciaría sin que nada fallara. Calculado, el señuelo
usa por construcción **los mismos parámetros que cualquier verificación real de este proceso**, que
es exactamente lo que el delta exige, y no hay nada que mantener sincronizado.

**«Constante» significa constante por proceso, y basta.** El señuelo nunca se persiste, nunca se
compara entre instancias y nunca se expone: lo único que importa es que dentro de un proceso la
verificación contra él cueste lo mismo que una real y no dependa de la entrada. Derivarlo de una
etiqueta y una sal fijas lo hace además determinista dado un despliegue, lo que permite afirmarlo en
una prueba.

**No introduce ningún secreto en el repositorio**: la etiqueta y la sal son públicas por
construcción; lo secreto es la pimienta, que ya tiene su camino.

**Costo:** una ejecución de Argon2id al construir el componente —del orden de cientos de
milisegundos, una sola vez— y ninguna en el camino caliente.

**Cómo se demuestra que el señuelo se ejecuta**, y no por cronómetro: el delta lo pide «verificable
por interacción con el puerto de verificación». Un doble contador de invocaciones del puerto
`PasswordHasher` afirma que el camino de cuenta inexistente llamó a `matches(...)` exactamente una
vez, con el hash señuelo. La medición de tiempo se **reporta** (sección 7.2), no se convierte en
puerta.

### Decisión 8 — Lo que se audita, y la etiqueta del actor cuando la cuenta no existe (P2)

**Elección de `actor_label`: el correo normalizado cuando la cuenta existe, y la constante
`unknown-account` cuando no.** Escribir el identificador presentado convertiría la bitácora en un
depósito de cadenas controladas por el atacante y, leída por un auditor, en un oráculo de
enumeración. La columna es `NOT NULL` (`docs/03` §12.1, línea 1313) y su propio comentario la define
como «correo o nombre de trabajo, para lectura», así que el correo de una cuenta real es el valor
previsto; la regla 11 de `CLAUDE.md` no lo alcanza —prohíbe contraseñas, tokens, cabeceras de
autorización, documentos de identidad, tarjetas y datos de menores—.

**Forma completa del asiento**, con los tres eventos de la primera fila de `docs/03` §12.2 que este
cambio produce:

| Campo | Autenticación exitosa | Autenticación fallida | Ciclo de retroceso |
|---|---|---|---|
| `action` | `identity.login.succeeded` | `identity.login.failed` | `identity.login.backoff_applied` |
| `outcome` | `success` | `denied` | `success` |
| `actor_kind` | `staff` | `staff` | `staff` |
| `actor_id` | identificador de la cuenta | el de la cuenta, o `NULL` si no existe | ídem |
| `actor_label` | correo normalizado | correo normalizado, o `unknown-account` | ídem |
| `entity_type` | `identity.staff_account` | `identity.staff_account` | `identity.login_backoff` |
| `entity_id` | huella hexadecimal del identificador | ídem | ídem |
| `after_value` | `null` | `{"reason":"invalid-password"}` o `{"reason":"account-not-found"}` | `{"consecutiveFailures":n,"delaySeconds":d}` |

Cuatro puntos que son decisiones:

1. **`entity_id` es siempre la huella, nunca el correo ni el identificador de la cuenta.** Da al
   auditor lo que necesita —correlacionar intentos repetidos contra el mismo identificador— sin
   darle el identificador, y hace que las dos ramas produzcan asientos de la misma forma.
2. **El motivo interno viaja en `after_value`, no en un campo observable.** Es la obligación que
   sobrevive de la antigua D3 y que el delta conserva.
3. **`outcome = 'success'` en el ciclo de retroceso significa «el control se aplicó», no «el inicio
   de sesión tuvo éxito».** El dominio de la columna tiene exactamente tres valores
   (`V2`/`docs/03` §12.1) y el campo que distingue los eventos es `action`. Se escribe aquí para que
   nadie lo lea al revés.
4. **`actor_id` en `NULL` para un actor no identificado.** El comentario de columna dice «`NULL` para
   actor de sistema»; este cambio lo usa además para «actor no identificado», que es un superconjunto
   honesto. Pregunta abierta 3.

**Cuándo se escribe el asiento de retroceso:** siempre que `requiredDelay` sea mayor que cero,
incluso cuando el intento tiene éxito. El escenario del cuarto intento con la contraseña correcta
produce, por tanto, **dos** asientos: el ciclo con su duración de dos segundos y el éxito. Las dos
ramas —cuenta existente e inexistente— escriben el mismo número de filas para el mismo ordinal de
intento, que es una condición de la uniformidad de tiempo.

**Puerto y adaptador**, con la ubicación exacta del precedente del cambio 5B: `AuditLogWriter` y su
registro `AuditEntry` en `com.confia.shared.audit`, junto a `AuditLogReader`; `JooqAuditLogWriter` en
`com.confia.shared.infrastructure`, junto a `JooqAuditLogReader`. **El adaptador no calcula
`prev_hash` ni `row_hash` ni `id`**: el disparador los asigna y sobrescribe lo que se le pase
(verificado en `V3__chain_shared_audit_log.sql:217-237`). Tampoco fija `occurred_at`: lo pone el
valor por omisión `clock_timestamp()` de la columna, de modo que los dos asientos de un mismo intento
no comparten marca de tiempo. El orden lo da `id`, no el reloj.

**`AuditEntry` lleva las dieciséis columnas insertables de la tabla ya entregada**, incluidas
`source_ip` y `user_agent`, que este cambio pasa siempre en `NULL` porque no hay petición HTTP. No es
trabajo «preparado para»: es la forma de una tabla que ya existe en el árbol desde el cambio 5B, no
una conjetura sobre un cambio futuro.

### Decisión 9 — La frontera transaccional exacta, y el orden de los bloqueos

**Una sola invocación de `TransactionRunner.execute(...)`, en `READ COMMITTED`**, con este cuerpo y
en este orden:

```
(el componente)  set_config × 4                      ← contexto de fila, primera sentencia
 1. reclamo de la fila de retroceso                  ← toma el bloqueo por identificador
 2. búsqueda de la cuenta por (institución, identificador)
 3. verificación Argon2id contra el hash real o contra el señuelo
 4. el dominio calcula: ordinal, retardo, estado nuevo y desenlace
 5. escritura del estado nuevo de retroceso
 6. asiento de auditoría del desenlace                ← toma el bloqueo de cabecera de cadena
 7. asiento de auditoría del ciclo, si el retardo > 0
(confirmación)
 8. se devuelve AuthenticationDecision                ← y solo entonces el llamador espera
```

**`READ COMMITTED` basta**: la serialización que el escenario de concurrencia exige la da el bloqueo
de fila del paso 1, no el nivel de aislamiento. No se pide `SERIALIZABLE` porque no hay ninguna
lectura de predicado que proteger, y pedirlo añadiría abortos `40001` y reintentos a un camino que
ejecuta Argon2id: reintentar ahí cuesta cientos de milisegundos por intento.

**Los asientos de auditoría van al final, a propósito.** El disparador de encadenamiento toma un
bloqueo sobre la cabecera de cadena **de toda la institución**; cuanto más tarde se tome, menos dura.
Escribirlos antes de la verificación Argon2id retendría esa cabecera —y con ella toda la auditoría
de la institución— durante cientos de milisegundos por intento.

**Orden de bloqueos, uniforme y declarado:** fila de retroceso → cabecera de cadena. Todas las
transacciones de este caso de uso lo toman en ese orden, así que no se interbloquean entre sí. Ningún
otro escritor del árbol toma la fila de retroceso.

**Costo aceptado, dicho en voz alta.** La verificación Argon2id ocurre **dentro** de la transacción,
con el bloqueo de la fila de retroceso tomado: una conexión del grupo queda retenida durante el
tiempo de la verificación, y dos intentos concurrentes contra el mismo identificador se serializan.
Lo segundo es deseable —es, de hecho, un control—; lo primero es un costo real, acotado por el tamaño
del grupo de conexiones. La alternativa, verificar fuera y escribir dentro, partiría la transacción
en dos y rompería el requisito de atomicidad del delta, además de reabrir la pérdida de escrituras
entre ambas. Se acepta y se declara.

**Lo que el escenario de atomicidad exige demostrar:** si la transacción falla de forma determinista
antes de confirmar, ni el contador avanzó ni existe asiento nuevo. Se prueba con
`CommittingPostgresIntegrationTest`, que es la base con confirmación real que el cambio 5B entregó
para esto.

### Decisión 10 — Estructura de paquetes, tipo sellado y forma del resultado (P4)

```
com.confia.identity/                 package-info.java (módulo de Spring Modulith)
com.confia.identity.domain/          AuthenticationResult (sellado), Authenticated, Rejected,
                                     RejectionReason, StaffAccount, StaffAccountId, LoginIdentifier,
                                     PlainPassword, StoredPasswordHash, IdentifierFingerprint,
                                     BackoffState, BackoffPolicy, y los errores de dominio
com.confia.identity.application/     AuthenticateWithPassword, AuthenticationCommand,
                                     AuthenticationDecision, y los cinco puertos
com.confia.identity.infrastructure/  JooqStaffAccountRepository, JooqLoginBackoffStore,
                                     BouncyCastleArgon2PasswordHasher, Argon2PhcCodec,
                                     HmacLoginIdentifierFingerprinter,
                                     ConfiguredLoginInstitutionProvider
```

**Por qué encaja con cada regla entregada**, verificado por lectura en la sección 2:

| Regla | Efecto |
|---|---|
| `LayeredArchitectureTest` | Las tres capas existen y las dependencias van `application → domain` e `infrastructure → application, domain`. Las dependencias hacia `com.confia.shared.security` y `com.confia.shared.audit` son irrelevantes para la regla: esos paquetes no llevan segmento de capa |
| R1 `JooqConfinedToInfrastructureTest` | Solo `identity.infrastructure` toca `org.jooq..` y `confia.generated..` |
| R2 `TableOwnershipByModuleTest` | `moduleOf("com.confia.identity.infrastructure")` = `identity`, prefijo esperado `Identity`; los tipos generados serán `IdentityStaffAccount` e `IdentityLoginBackoff`. **El adaptador de auditoría no vive aquí** sino en `com.confia.shared.infrastructure`, precisamente porque usa `SharedAuditLog` |
| R3 `TransactionsOnlyInSharedSecurityTest` | Ninguna clase de `identity` abre transacciones: las abre `TransactionRunner` por composición, igual que `IdempotentExecutor` |
| R4 `NoUnapprovedPlainSqlTest` | Ningún SQL plano de jOOQ. La lista aprobada sigue vacía |
| `NoTechnicalLayerPackageNamesTest` | `domain`, `application` e `infrastructure` no están en la lista prohibida |
| JaCoCo y PIT | `com.confia.identity.domain` entra **sin tocar el POM** en la puerta del 95 % y en la de mutación del 80 |

**El tipo sellado, exactamente como el delta lo fija:**

```java
public sealed interface AuthenticationResult permits Authenticated, Rejected { }

public record Authenticated(StaffAccountId userId, InstitutionId institutionId)
        implements AuthenticationResult { }

public record Rejected(RejectionReason reason) implements AuthenticationResult { }

public enum RejectionReason { INVALID_PASSWORD, ACCOUNT_NOT_FOUND }
```

Ningún campo de alcance de autorización, y exactamente dos desenlaces. `mfa-totp-and-password-recovery`
**edita la cláusula `permits` de este archivo** para añadir los suyos; la propuesta ya explica por qué
eso es correcto y por qué el compilador lo hace seguro.

**El retardo viaja en un envoltorio, no dentro de los desenlaces:**

```java
public record AuthenticationDecision(AuthenticationResult result, Duration requiredDelay) { }
```

**Por qué el envoltorio y no un campo en cada variante.** Primero, porque el delta enumera lo que
lleva cada desenlace y añadirles un campo más lo contradiría de forma innecesaria. Segundo, y más
importante: el retardo es **ortogonal al desenlace** —se calcula igual con contraseña correcta que
con contraseña incorrecta, porque el ordinal del intento no depende del resultado— y ponerlo en las
variantes obligaría a cada desenlace futuro a repetirlo, con la posibilidad de que uno se olvide.
Con el envoltorio, el día que el cambio 2 añada `SecondFactorRequired`, el retardo sigue saliendo de
un solo sitio.

**Y una consecuencia que conviene ver escrita:** `requiredDelay` no distingue los dos motivos de
rechazo, porque se calcula antes de saber cuál fue. Para el mismo ordinal de intento, una cuenta
inexistente y una contraseña incorrecta producen el mismo `Duration`. Esa es la mitad del control de
§4.6 que se puede afirmar de forma determinista, sin cronómetro.

**Cuatro guardas en los objetos de valor**, todas con su prueba:

1. `PlainPassword` y `StoredPasswordHash` llevan `toString()` redactado y no son registros con
   `toString()` generado. `Argon2Pepper` también.
2. `PlainPassword` rechaza la cadena vacía y **acota la longitud a 1024 caracteres**. Es una guarda
   técnica, no una política de contraseñas —la política de §4.2 está fuera de alcance—: sin ella, una
   contraseña de diez megabytes es una denegación de servicio dirigida contra Argon2id. Se escribe en
   el Javadoc con esas palabras, siguiendo el precedente del comentario de columna del RTN
   («technical guard, not a fiscal rule»).
3. `LoginIdentifier` normaliza (decisión 11) y valida longitud.
4. Una regla de ArchUnit prohíbe `Thread.sleep`, `TimeUnit.sleep`, `Object.wait` y `LockSupport.park*`
   en todo `com.confia.identity..`, con su mitad de rechazo sobre un fixture permanente, como exige
   ADR-0018. Es la guarda de la decisión 1.

### Decisión 11 — La institución previa a la autenticación, y la normalización del identificador

**La institución (D4, aprobada).** El caso de uso **recibe el `SecurityContext` de su llamador**,
exactamente como `IdempotentExecutor` (punto 1 de «Qué queda fijado» de la propuesta), y de él toma
la institución: tiene que ser así, porque es el mismo valor que `TransactionRunner` fija en
`app.institution_id` y contra el que la política de fila evalúa la búsqueda de la cuenta. Resolver la
cuenta con una institución distinta de la del contexto devolvería cero filas por política, no un
error.

Pero «lo recibe del llamador» no es garantía de nada por sí solo, y D4 exige que **provenga de la
configuración del proceso**. Este cambio la escribe en el árbol con dos piezas:

- **`LoginInstitutionProvider`**, puerto propio de `identity.application`, con su adaptador
  `ConfiguredLoginInstitutionProvider` en `identity.infrastructure`, que recibe la institución de la
  configuración del proceso (clave canónica `confia.identity.login-institution-id`) y **falla al
  construirse** si falta. Es un puerto nuevo y no el `CurrentInstitutionProvider` de `organization`,
  a propósito: el contrato de aquel dice que el identificador **debe** derivarse del token
  autenticado, y aquí no hay token todavía. Reutilizarlo sería importar una promesa que este camino
  no puede cumplir.
- **Una guarda de cierre en el caso de uso**: si la institución del `SecurityContext` no coincide con
  la del proveedor, el caso de uso falla de forma ruidosa y **nunca** devuelve `Rejected`. No es un
  desenlace de negocio: es un error de cableado, y confundirlos sería peor que no tener la guarda.
  Esta guarda es la que hace que el escenario de D4 sea demostrable hoy, con el cambio 3 todavía sin
  existir, en vez de quedar como una nota de intenciones.

**Alternativa descartada:** una consulta global sin contexto de institución, imposible sin
`BYPASSRLS`, que ninguno de los cinco roles tiene (`create-test-roles.sql:11-15`).

**La normalización del identificador**, que hay que fijar porque decide qué bytes entran en la
huella y en la búsqueda: recorte de espacios en los extremos, normalización Unicode **NFKC**, y paso
a minúsculas con `Locale.ROOT`. Es determinista, vive en `LoginIdentifier` —dominio puro— y se aplica
**una sola vez**, en la frontera; todo lo demás trabaja con el valor ya normalizado. Que el correo se
trate como insensible a mayúsculas es una decisión declarada: RFC 5321 permite que la parte local
sea sensible, y en la práctica ningún proveedor lo explota.

**La normalización de la contraseña.** §4.2 pide «normalización NFKC antes de hashear». La política
de contraseñas está fuera de alcance, pero **esto no es política: es qué bytes entran en Argon2id**, y
si el alta normaliza y la verificación no, una contraseña con acentos deja de funcionar. Se aplica
NFKC en `PlainPassword`, con su prueba, y se declara aquí para que el cambio que implemente el alta
no invente otra cosa.

### Decisión 12 — `identity` consume `shared`: interfaces nombradas de Spring Modulith

**El problema, descubierto en esta fase y no anticipado por la propuesta.**
`SpringModulithVerificationTest` verifica el árbol de producción con
`ApplicationModules.of("com.confia", DO_NOT_INCLUDE_TESTS).verify()`, y su propio Javadoc registra el
comportamiento confirmado: **cada sub-paquete directo de la raíz es un módulo**, y `verify()` lanza
`Violations` «si un módulo accede al paquete interno no API de otro módulo»
(`SpringModulithVerificationTest.java:13-30`). `com.confia.shared.security` y
`com.confia.shared.audit` son paquetes anidados del módulo `shared`.

Este cambio es **el primero en que una clase de producción cruza de un módulo a otro**: verificado por
búsqueda de importaciones, hoy ninguna lo hace; las únicas dependencias entre paquetes de producción
son internas a `shared` o hacia `com.confia.kernel`, y esas funcionan porque los tipos del núcleo
viven en el **paquete base** de su módulo, que es API por omisión. `AuthenticateWithPassword`
necesita `TransactionRunner` —la propuesta lo fija en su sección de enfoque— y `AuditLogWriter`.

Es exactamente la pregunta abierta 2 que el cambio 5B dejó escrita («interfaz nombrada de Spring
Modulith para `com.confia.shared.security` … si resultara caro o ambiguo, se difiere al cambio 6»).
El cambio 6 no la necesitó porque consumió el componente solo desde pruebas. **Nos toca.**

**Elección: declarar interfaces nombradas para los dos paquetes de `shared` que otros módulos
consumen, con `@NamedInterface` en su `package-info`, y elevarlo a ADR-0022.**

- Es la opción **estrecha**: expone dos paquetes, no el módulo entero, y deja en el árbol la
  declaración explícita de qué parte de `shared` es API. Todo módulo futuro —`payments`, `billing`—
  va a chocar con lo mismo, y conviene que choque contra una decisión escrita.
- Cuesta una dependencia de producción nueva con las anotaciones de Spring Modulith (hoy
  `spring-modulith-core` es de alcance `test`, verificado en `apps/api/app/pom.xml:110-114`).
- **Es una decisión de arquitectura, no un detalle**: define el contrato público de `shared` para
  siempre. El siguiente número de ADR libre es **0022**, leído del directorio `docs/adr/` (existen
  0001 a 0021). Nota para la secuencia: la propuesta de `mfa-totp-and-password-recovery` menciona un
  «ADR-0022 nuevo» para el sobre de llaves; este cambio se fusiona antes, así que aquel toma el
  siguiente libre en su momento.

**Alternativas, con su criterio de conmutación (sonda S1):**

| Opción | Costo | Veredicto |
|---|---|---|
| `@NamedInterface` sobre `shared.security` y `shared.audit` | Una dependencia de producción de anotaciones; API explícita y estrecha | **Elegida** |
| `@ApplicationModule(type = OPEN)` sobre `com.confia.shared` | Misma dependencia, pero expone el módulo entero, incluido `shared.infrastructure` | Respaldo si `@NamedInterface` no se comporta como se espera |
| Detección basada en `@Modulithic(sharedModules = …)` desde la prueba | Evita anotar producción, pero la anotación sigue necesitando el artefacto, y traslada una decisión de arquitectura a un archivo de prueba | Descartada salvo que las dos anteriores fallen |
| Mover los tipos consumidos al paquete base `com.confia.shared` | Sin dependencia nueva, pero **imposible para `TransactionRunner`**: ADR-0015 regla 7 lo sitúa literalmente en `shared/security` y la regla R3 exime ese prefijo exacto | Descartada |

**S1 es bloqueante del corte C1**, y se ejecuta escribiendo la dependencia mínima que la reproduce.
Si ninguna opción funcionara, el diseño queda desmentido en este punto y **se reporta**: no se relaja
`SpringModulithVerificationTest` ni se añade una supresión de conveniencia.

### Decisión 13 — El escenario W2 se cierra demostrado, no declarado

El escenario diferido «Cada módulo usa solo su propio dominio» ya tiene una prueba que lo cubriría:
`NoCrossModuleDomainImportsTest.productionCodeHasNoCrossModuleDomainImportYet`. Hasta hoy pasaba **de
forma vacía**, porque con un solo módulo de negocio no hay otro dominio del que aislarse.

Con `identity` en el árbol deja de ser vacía, pero «deja de serlo» es justo la clase de afirmación
que este repositorio no acepta sin verificación. Dos cambios pequeños en esa prueba:

1. **Una guarda de no vacuidad**: se afirma que `productionClasses()` contiene clases de **al menos
   dos módulos de dominio distintos**, con la misma extracción de módulo que la regla ya usa. Si un
   día alguien borra `identity.domain`, la prueba falla en vez de volver a pasar por no haber nada.
2. **Renombrar el método**, de `productionCodeHasNoCrossModuleDomainImportYet` a
   `everyModuleUsesOnlyItsOwnDomain`. El sufijo `Yet` documentaba precisamente la vacuidad que este
   cambio elimina, y el nombre nuevo es el título del escenario que cierra.

Es la disciplina que el delta de `build-integrity` exige: incorporar el escenario con su
verificación, no una nota que diga que ya se puede.

### Decisión 14 — Ningún secreto observable, verificado y no confiado

El delta lo pide «por inspección del texto producido, no por confianza en el diseño». Cuatro piezas:

1. **El módulo `identity` no escribe ninguna traza de registro en este cambio.** Su registro de
   eventos es la bitácora de auditoría. Un inventario estático afirma que ninguna clase de producción
   de `com.confia.identity..` depende de `org.slf4j..`, `java.util.logging..` ni
   `org.apache.commons.logging..`, con la misma disciplina de conjunto no vacío que usa
   `AuditScopeExclusionInventoryTest`.
2. **`toString()` redactado** en `PlainPassword`, `StoredPasswordHash` y `Argon2Pepper`, con prueba
   directa sobre el texto devuelto.
3. **Una prueba de recolección**: se ejecuta un intento con la contraseña literal `Segura#2026` del
   escenario y se recoge todo el texto que el sistema produce —mensajes de las excepciones lanzadas en
   fallos forzados, `toString()` de cada objeto involucrado, y el contenido de las filas escritas en
   `shared_audit_log`—, afirmando que ninguno contiene la contraseña, el hash calculado ni la
   pimienta.
4. **Los asertos de las pruebas no imprimen los valores prohibidos al fallar**: se comparan
   longitudes y formas, nunca el contenido, y la descripción de AssertJ nombra el campo, no el valor.

---

## 4. Flujo de datos

```
        caller (hoy: prueba · mañana: session-tokens-and-web-layer)
             │  AuthenticationCommand(identifier, password) + SecurityContext
             ▼
   ┌───────────────────────────────────────────────────────────────────┐
   │ AuthenticateWithPassword (identity.application)                   │
   │   guarda: context.institutionId == LoginInstitutionProvider       │
   │   TransactionRunner.execute(context, READ COMMITTED, …)           │
   │  ┌─────────────────────────────────────────────────────────────┐  │
   │  │ 1 LoginBackoffStore.claim(...)      → estado previo + lock  │  │
   │  │ 2 StaffAccountRepository.findBy(...)→ Optional<StaffAccount>│  │
   │  │ 3 PasswordHasher.matches(pwd, hash real | hash señuelo)     │  │
   │  │ 4 BackoffPolicy (identity.domain)   → ordinal, retardo,     │  │
   │  │                                       estado nuevo, result  │  │
   │  │ 5 LoginBackoffStore.save(...)                               │  │
   │  │ 6 AuditLogWriter.append(desenlace)                          │  │
   │  │ 7 AuditLogWriter.append(ciclo)      si retardo > 0          │  │
   │  └─────────────────────────────────────────────────────────────┘  │
   │   commit                                                          │
   └───────────────────────────────────────────────────────────────────┘
             │  AuthenticationDecision(result, requiredDelay)
             ▼
        caller: espera requiredDelay FUERA de la transacción, y responde
```

**Secuencia del caso límite que distingue el modelo aprobado del bloqueo descartado** (cuarto intento
con la contraseña correcta, contador en tres):

```
prueba            caso de uso        retroceso        hasher        auditoría
  │  execute(cmd)      │                 │              │              │
  ├───────────────────►│                 │              │              │
  │                    │ claim ─────────►│  (3, 12:00)  │              │
  │                    │◄────────────────┤              │              │
  │                    │ findBy ─────────┼─────────────►│  cuenta      │
  │                    │ matches ────────┼─────────────►│  true        │
  │                    │ ordinal = 3+1 = 4 → delay = 2^(4-3) = 2 s     │
  │                    │ save(0, 12:00:10) ─────────────┼─────────────►│
  │                    │ append(login.succeeded) ───────┼─────────────►│
  │                    │ append(backoff_applied, 2 s) ──┼─────────────►│
  │◄───────────────────┤ Decision(Authenticated, PT2S)  │              │
  │  assert requiredDelay == PT2S  ← sin dormir un solo milisegundo    │
```

---

## 5. Cambios de archivos

| Archivo | Acción | Descripción |
|---|---|---|
| `apps/api/app/src/main/java/com/confia/identity/package-info.java` | Crear | Declara el módulo `identity`, su alcance y lo que **no** entrega, con el precedente de `organization/package-info.java` |
| `.../identity/domain/AuthenticationResult.java` y `Authenticated`, `Rejected`, `RejectionReason` | Crear | Tipo sellado con dos desenlaces (decisión 10) |
| `.../identity/domain/StaffAccount.java`, `StaffAccountId.java` | Crear | La cuenta como objeto de dominio |
| `.../identity/domain/LoginIdentifier.java` | Crear | Normalización NFKC, minúsculas, validación de longitud |
| `.../identity/domain/PlainPassword.java`, `StoredPasswordHash.java` | Crear | Envoltorios con `toString()` redactado y guardas técnicas |
| `.../identity/domain/IdentifierFingerprint.java` | Crear | Huella hexadecimal de 64 caracteres |
| `.../identity/domain/BackoffState.java`, `BackoffPolicy.java` | Crear | La regla completa del retroceso, pura y sin reloj propio |
| `.../identity/domain/*Exception.java` + `IdentityErrorCodesTest` | Crear | Errores con código estable (ADR-0019), con el precedente de `OrganizationErrorCodesTest` |
| `.../identity/application/AuthenticateWithPassword.java` | Crear | El caso de uso |
| `.../identity/application/AuthenticationCommand.java`, `AuthenticationDecision.java` | Crear | Entrada y contrato de salida, con el contrato del retardo en el Javadoc |
| `.../identity/application/StaffAccountRepository.java`, `LoginBackoffStore.java`, `PasswordHasher.java`, `LoginIdentifierFingerprinter.java`, `LoginInstitutionProvider.java` | Crear | Los cinco puertos |
| `.../identity/infrastructure/JooqStaffAccountRepository.java`, `JooqLoginBackoffStore.java` | Crear | Adaptadores jOOQ, sin abrir transacciones |
| `.../identity/infrastructure/BouncyCastleArgon2PasswordHasher.java`, `Argon2PhcCodec.java`, `Argon2Pepper.java` | Crear | Argon2id, pimienta y formato `$argon2id$` |
| `.../identity/infrastructure/HmacLoginIdentifierFingerprinter.java` | Crear | HMAC con subllave derivada |
| `.../identity/infrastructure/ConfiguredLoginInstitutionProvider.java` | Crear | La institución desde la configuración del proceso |
| `.../shared/audit/AuditLogWriter.java`, `AuditEntry.java` | Crear | Puerto de escritura diferido por el cambio 5B |
| `.../shared/infrastructure/JooqAuditLogWriter.java` | Crear | Su adaptador, sin calcular ningún hash |
| `.../shared/security/package-info.java`, `.../shared/audit/package-info.java` | Modificar | `@NamedInterface` (decisión 12) |
| `apps/api/app/src/main/resources/db/migration/V5__create_identity_staff_account.sql` | Crear | Las dos tablas, políticas y privilegios (decisión 4) |
| `apps/api/app/pom.xml` | Modificar | `bcprov-jdk18on` y las anotaciones de Spring Modulith en alcance de compilación |
| `apps/api/pom.xml` | Modificar | Versiones nuevas en `dependencyManagement` |
| `.../test/java/com/confia/architecture/NoCrossModuleDomainImportsTest.java` | Modificar | Guarda de no vacuidad y renombrado del método (decisión 13) |
| `.../test/java/com/confia/architecture/NoBlockingWaitInIdentityTest.java` + fixture | Crear | La guarda de la decisión 1 |
| `.../test/java/com/confia/schema/RolePrivilegeMatrixIT.java` | Modificar | Filas de las dos tablas nuevas para los cinco roles |
| `.../test/java/com/confia/identity/**` | Crear | Unitarias, propiedades e integración (sección 7) |
| `docs/adr/ADR-0022-interfaz-nombrada-del-modulo-shared.md` | Crear | Decisión 12 |
| `docs/03-seguridad.md` | Modificar | Notas editoriales fechadas en §4.4 y §6.1 (sección 15) |
| `docs/09-roadmap-y-fases.md` | Modificar | Entregable 3 con sus tres mitades diferidas nombradas |
| `openspec/changes/.../specs/build-integrity/spec.md` | Modificar | Ajuste 1 de la sección 15 |

`MultiTenantSchemaIT` **no se modifica**: si hiciera falta tocarla, es una discrepancia con este
diseño y se reporta, no se relaja la puerta.

---

## 6. Contratos e interfaces

### 6.1 Los cinco puertos de `identity.application`

```java
public interface StaffAccountRepository {
    Optional<StaffAccount> findBy(InstitutionId institutionId, LoginIdentifier identifier);
}

public interface LoginBackoffStore {
    /** Crea o bloquea la fila y devuelve el estado previo (decisión 5). */
    BackoffState claim(InstitutionId institutionId, IdentifierFingerprint fingerprint, Instant now);

    void save(InstitutionId institutionId, IdentifierFingerprint fingerprint, BackoffState state);
}

public interface PasswordHasher {
    boolean matches(PlainPassword password, StoredPasswordHash hash);

    StoredPasswordHash hash(PlainPassword password);
}

public interface LoginIdentifierFingerprinter {
    IdentifierFingerprint fingerprintOf(LoginIdentifier identifier);
}

public interface LoginInstitutionProvider {
    /** Configuración del proceso. Jamás cuerpo, cabecera ni parámetro de consulta (D4). */
    InstitutionId loginInstitutionId();
}
```

`PasswordHasher.hash(...)` tiene consumidor de producción en este cambio: el hash señuelo
(decisión 7). No es superficie especulativa.

### 6.2 El contrato del retardo

```java
/**
 * Resultado del caso de uso de autenticación y la espera que el llamador DEBE cumplir antes de
 * responder (design.md, decisión 1).
 *
 * <p>{@code requiredDelay} se cuenta desde que este registro se devuelve, ya confirmada la
 * transacción. El llamador: (1) no entrega la respuesta antes de que transcurra; (2) no la
 * materializa dentro de ninguna transacción; (3) no retiene un hilo de plataforma mientras espera;
 * (4) acota el número de respuestas retardadas simultáneas y, al alcanzar el límite, responde con
 * un error de capacidad uniforme decidido antes de procesar el intento, nunca acortando la espera;
 * (5) produce la misma respuesta observable para los dos motivos de rechazo; (6) abandona la espera
 * si el cliente cierra la conexión, porque ningún efecto depende de ella.
 *
 * <p>El retroceso demora la respuesta y NO la deniega: {@code requiredDelay} puede ser positivo
 * junto a un desenlace {@link Authenticated} (specs/identity/spec.md).
 */
public record AuthenticationDecision(AuthenticationResult result, Duration requiredDelay) { }
```

### 6.3 La regla del retroceso, en el dominio

```java
public final class BackoffPolicy {
    public static final int FIRST_DELAYED_ATTEMPT = 3;          // docs/03 §4.4
    public static final Duration CAP = Duration.ofSeconds(900);
    public static final Duration COUNTER_WINDOW = Duration.ofMinutes(30);

    /** Ordinal del intento en curso dentro del ciclo vigente; 1 si el contador expiró. */
    public int attemptOrdinal(BackoffState prior, Instant now) { … }

    /** 0 por debajo del umbral; 2^(n-3) segundos en adelante, con tope de 900. */
    public Duration delayFor(int attemptOrdinal) { … }

    public BackoffState afterFailure(int attemptOrdinal, Instant now) { … }

    public BackoffState afterSuccess(Instant now) { … }   // contador a cero
}
```

**El ordinal no depende del desenlace**: es `prior.consecutiveFailures() + 1` cuando el contador
sigue vigente, y `1` cuando expiró. Es lo que hace que el cuarto intento con contraseña correcta se
retarde dos segundos, igual que se habría retardado un cuarto fallo, y es la razón por la que el
retardo no filtra nada.

### 6.4 El puerto de escritura de auditoría

```java
public interface AuditLogWriter {
    /**
     * Inserta una fila de {@code shared_audit_log} dentro de la transacción que el llamador ya
     * abrió. NO calcula {@code id}, {@code prev_hash} ni {@code row_hash}: los asigna el disparador
     * de encadenamiento (V3), que además sobrescribe lo que se le pase.
     */
    void append(AuditEntry entry);
}
```

---

## 7. Estrategia de pruebas

| Capa | Qué se prueba | Cómo |
|---|---|---|
| Unitaria (`domain`) | `BackoffPolicy` en sus cuatro reglas, los objetos de valor y su redacción, el tipo sellado | JUnit y AssertJ, sin contenedor. Puerta del 95 % y mutación 80 |
| Propiedades | Monotonía del retardo, tope en 900 s, cero por debajo del umbral, e idempotencia de la normalización del identificador | jqwik, ya disponible en `app` |
| Unitaria (`infrastructure`) | Códec `$argon2id$` de ida y vuelta, vector de RFC 9106 §5.3 con `secret`, rechazo de cadenas mal formadas, pimienta de longitud incorrecta | JUnit y AssertJ |
| Arquitectura | Ninguna espera en `identity`; ninguna traza de registro; W2 no vacuo; capas, R1–R4 y Modulith | ArchUnit con mitad de rechazo sobre fixture; `SpringModulithVerificationTest` |
| Integración (`*IT`) | Política de fila y privilegios de las dos tablas; contexto ausente con cero filas; adaptadores jOOQ; caso de uso completo; atomicidad; concurrencia | Failsafe y Testcontainers, conectando como `confia_admin_app` y `confia_portal_app`, nunca como propietario |
| Medición | Diferencia de medianas entre cuenta existente e inexistente | **Medida y reportada**, nunca puerta (decisión 7) |
| Rendimiento | Tiempo de la suite `*IT` | **Medido y reportado** al cerrar cada corte |

### 7.1 Trazabilidad: los 31 escenarios y su prueba

> **Corrección del orquestador (2026-09-24), encontrada por la fase de tareas.** Esta tabla decía
> «27 escenarios», con 20 de `identity` y 7 de `build-integrity`. Estaba desfasada respecto a los
> **ajustes 1 y 2 de la sección 15 de este mismo diseño**, que ya se aplicaron a los deltas y
> añadieron tres escenarios que la tabla nunca absorbió; y el recuento de `build-integrity` ya era
> uno menos que sus propias filas. Verificado contando los encabezados `#### Escenario:` de cada
> delta: **23 en `identity` y 8 en `build-integrity`, 31 en total.** Las tres filas nuevas van
> marcadas abajo.

**`identity` (23 escenarios)**

| Requisito | Escenario | Prueba | Corte |
|---|---|---|---|
| Resultado tipado | Contraseña correcta produce `Authenticated` sin alcance | `AuthenticateWithPasswordIT` + `AuthenticationResultTest` (reflexión: ningún componente de alcance) | C3 |
| | Toda causa de rechazo produce `Rejected` con motivo interno | `AuthenticateWithPasswordIT` | C3 |
| Verificación señuelo | El señuelo se ejecuta ante cuenta inexistente | `AuthenticateWithPasswordTest` con doble contador del puerto | C3 |
| Estado en PostgreSQL | Efecto y asiento se confirman o revierten juntos | `LoginBackoffAtomicityIT` sobre `CommittingPostgresIntegrationTest` | C3 |
| | Dos intentos fallidos concurrentes no pierden escrituras | `LoginBackoffConcurrencyIT` con `CyclicBarrier` | C3 |
| | **El estado del retroceso existe para un identificador sin cuenta** (ajuste 1) | `LoginBackoffIT`: contador en dos sin fila de cuenta, y el identificador solo como huella con llave | C3 |
| Institución del proceso | Se resuelve de la configuración, nunca de la solicitud | `LoginInstitutionIT`: dos instituciones con el mismo correo; y la guarda de cierre con contexto ajeno | C3 |
| Puerto de auditoría | Primer escritor de producción de `shared_audit_log` | `JooqAuditLogWriterIT`: la fila queda encadenada y el adaptador no calcula hashes | C3 |
| Sin secretos observables | Ningún registro ni excepción expone contraseña, hash o pimienta | `IdentitySecretRedactionIT` + inventario de registro | C2/C3 |
| Brecha IP | Ninguna regla de IP se aplica todavía | `IdentityScopeExclusionInventoryTest` | C3 |
| Brecha contraseñas comprometidas | Ninguna verificación contra listas | `IdentityScopeExclusionInventoryTest` (sin recurso empaquetado ni cliente de red) | C3 |
| | El hash almacenado no se recalcula | `AuthenticateWithPasswordTest`: `hash(...)` no se invoca en el camino de éxito | C3 |
| Brecha calibración | Los parámetros son el piso declarado | `Argon2ProfileTest` sobre los valores de §4.1 y su Javadoc | C2 |
| Brecha Playwright | Ninguna prueba de extremo a extremo | `IdentityScopeExclusionInventoryTest` sobre el árbol | C3 |
| **Retardo (modificado)** | Retardo desde el tercer fallo, ciclo auditado con su duración | `BackoffPolicyTest` + `AuthenticateWithPasswordIT` | C2/C3 |
| | La progresión se limita a 900 segundos | `BackoffPolicyTest` y propiedad jqwik | C2 |
| | El contador expira a los 30 minutos | `BackoffPolicyTest` con dos `Clock.fixed` | C2 |
| | Un inicio de sesión exitoso limpia el contador | `AuthenticateWithPasswordIT` | C3 |
| | El retardo se aplica también a cuenta inexistente | `AuthenticateWithPasswordIT`: mismo `requiredDelay` y mismo número de asientos | C3 |
| | El motivo del rechazo es interno | `AuthenticateWithPasswordIT` + `AuthenticationResultTest` | C3 |
| | Contraseña correcta durante el retroceso **tiene éxito** tras el retardo | `AuthenticateWithPasswordIT`: `Authenticated` con `requiredDelay = PT2S` | C3 |
| **Frontera del retardo** (ajuste 2) | **La duración exigible se devuelve y la transacción ya confirmó** | `AuthenticateWithPasswordIT`: `requiredDelay = PT2S`, contador ya limpio y ciclo ya auditado al devolver | C3 |
| | **Ninguna clase del módulo de identidad espera** | `NoBlockingWaitInIdentityTest` de ArchUnit, con fixture de violación (decisión 10, punto 4) | C2 |

**`build-integrity` (8 escenarios)**

| Requisito | Escenario | Prueba | Corte |
|---|---|---|---|
| Frontera de dominio | Importación cruzada de dominio | `NoCrossModuleDomainImportsTest`, mitad de rechazo (ya existe) | C1 |
| | **Cada módulo usa solo su propio dominio** | Mitad de producción con guarda de no vacuidad (decisión 13) | C1 |
| Tabla nueva | Las puertas genéricas pasan sin lista de exclusión | `MultiTenantSchemaIT`, **sin modificar** | C1 |
| | Una institución no lee la cuenta de personal de otra | `IdentityRowSecurityIT` | C1 |
| | Sin contexto, cero filas y no error de permiso | `IdentityRowSecurityIT` | C1 |
| Privilegios | La matriz cubre las tablas nuevas para los cinco roles | `RolePrivilegeMatrixIT` | C1 |
| | `confia_admin_app` lee, inserta y actualiza, pero no borra | `RolePrivilegeMatrixIT` | C1 |
| | `confia_portal_app` no tiene ningún privilegio | `RolePrivilegeMatrixIT` | C1 |

### 7.2 Las pruebas que no pueden depender del reloj ni del cronómetro

- **Ninguna prueba de este cambio duerme.** Lo que se afirma del retardo es el `Duration` calculado.
  Los escenarios de 900 segundos y de expiración a los 30 minutos se ejecutan en milisegundos con
  `Clock.fixed(instant, ZoneOffset.UTC)`, el precedente exacto de `IdempotencyExpiryIT:43`.
- **Concurrencia con barrera, jamás con espera.** `LoginBackoffConcurrencyIT` sincroniza dos hilos
  con `CyclicBarrier` después de abrir la transacción y antes del reclamo, como
  `IdempotentExecutorConcurrencyIT` y `TransactionRunnerRetryIT`. Se afirma que el contador final
  refleja los tres fallos y que ninguna escritura se perdió.
- **La uniformidad de tiempo se mide y se reporta.** `LoginTimingReportIT` ejecuta una muestra
  reducida —25 intentos con correo existente y 25 con inexistente, no los 200 de §4.6, que miden
  respuestas HTTP que aquí no existen y costarían minutos de Argon2id— y **reporta** la diferencia de
  medianas sin convertirla en puerta. La puerta real es de `session-tokens-and-web-layer`, sobre
  respuestas HTTP. El mecanismo sí se prueba de forma determinista, por interacción.
- **El escenario del señuelo se prueba por interacción**, con un doble que cuenta invocaciones y
  compara el hash recibido con el señuelo, nunca con un cronómetro.

---

## 8. Matriz de amenazas

No aplica en el sentido de `references/threat-matrix.md`: este cambio no introduce enrutamiento,
órdenes de intérprete de comandos, subprocesos de la aplicación, automatización de Git o de pull
requests, ni clasificación de archivos ejecutables. Las fronteras de seguridad que sí toca se tratan
como decisiones con verificación:

| Frontera | Tratamiento |
|---|---|
| **El retardo como amplificador de denegación de servicio** | Decisión 1: la espera sale de la transacción y del módulo; contrato de seis puntos para el borde; regla de ArchUnit que prohíbe esperar dentro de `identity` |
| **El retardo confundido con un limitador de tasa** | Decisión 2: se declara lo que no protege y se nombra al dueño del control que sí lo hace |
| **Enumeración de usuarios** | Un solo camino de código para cuenta existente e inexistente (decisión 3), señuelo con los parámetros vigentes (decisión 7), mismo número de escrituras y de asientos, motivo solo en la bitácora |
| **Secretos en registros, excepciones o pruebas** | Decisión 14, con inventario, redacción y recolección de texto real |
| **La bitácora como oráculo de enumeración** | P2: etiqueta constante para cuenta inexistente; `entity_id` es una huella, nunca el correo |
| **Reutilización de llave criptográfica** | Subllave con separación de dominio para la huella (decisión 6) |
| **Denegación de servicio contra Argon2id** | Guarda técnica de longitud de contraseña (decisión 10, punto 2) |
| **Aislamiento por institución** | Política con `USING` y `WITH CHECK` explícitos, `FORCE ROW LEVEL SECURITY`, y cero filas ante contexto ausente. Probado con el rol de aplicación, no con el propietario |
| **Institución tomada del cliente** | Puerto propio con adaptador de configuración y guarda de cierre en el caso de uso (decisión 11) |
| **Contención de la cabecera de cadena de auditoría** | Los asientos van al final de la transacción (decisión 9); orden de bloqueos declarado |
| **Credenciales en el repositorio** | Ninguna nueva. La pimienta de prueba sigue el patrón declarado no secreto de `create-test-roles.sql:8-10` |
| **Dependencia nueva** | `bcprov-jdk18on` y las anotaciones de Modulith pasan la revisión de `docs/03` §2.6; ninguna está en la lista prohibida del enforcer |

---

## 9. Migración y despliegue

No hay entorno desplegado ni dato real: la base vive solo en contenedores efímeros que se recrean en
cada construcción. Tres matices propios de este cambio:

1. **Una sola migración, `V5`, con las dos tablas.** Se entregan juntas porque el caso de uso no
   funciona con una sola y porque partirlas dejaría un corte con una tabla sin consumidor.
2. **El contenedor de generación de código también aplica `V5`.** El devolución de llamada
   `beforeMigrate__create_codegen_roles.sql` ya crea los cinco roles `NOLOGIN`, de modo que los
   `GRANT` tienen destinatario. Si los tipos generados no se llamaran `IdentityStaffAccount` e
   `IdentityLoginBackoff`, R2 rompería la construcción antes de compilar nada (sonda **S4**).
3. **Ningún proceso de producción arranca todavía.** No se registra ninguna fuente de datos ni
   ningún bean: las clases nuevas son `final`, con constructor explícito y sin anotación de Spring,
   el patrón de `TransactionRunner`, `IdempotentExecutor` y `JooqInstitutionRepository`. El cableado
   es de `session-tokens-and-web-layer`.

**Punto de no retorno práctico:** el cambio 11. Desde entonces, una migración aplicada no se edita, y
la pimienta pasa a ser un secreto real con rotación —que, conviene decirlo, **invalidaría todos los
hashes existentes**: rotar la pimienta exige un procedimiento de recontraseña, y ese procedimiento es
del cambio que introduzca el gestor de secretos, no de este.

---

## 10. Sondas, a ejecutar antes de la tarea que depende de cada una

Esta fase no tuvo herramienta de ejecución de procesos. Cada sonda se ejecuta fuera del árbol del
repositorio o en archivos temporales que se borran, y **su resultado se registra en el informe de
aplicación**.

| # | Pregunta | Comando o procedimiento exacto | Criterio de éxito y respaldo | Bloquea |
|---|---|---|---|---|
| **S1** | ¿`ApplicationModules.of("com.confia", …).verify()` rechaza que una clase de `com.confia.identity.application` dependa de `com.confia.shared.security.TransactionRunner`? ¿Y `@NamedInterface` en el `package-info` de `shared.security` lo permite? | Crear una clase de producción mínima en `com.confia.identity.application` que importe `TransactionRunner`, ejecutar `./mvnw -B -pl app test -Dtest=SpringModulithVerificationTest` desde `apps/api` y observar el resultado; después añadir el `package-info` anotado y repetir | El primer paso falla y el segundo pasa. Respaldo 1: `@ApplicationModule(type = OPEN)` sobre `com.confia.shared`. Respaldo 2: detección con `@Modulithic(sharedModules = "shared")`. Si ninguno funciona, **se reporta**; no se relaja la prueba | **C1** |
| **S1b** | ¿En qué artefacto vive `@NamedInterface` en Spring Modulith 2.1.1, y basta `spring-modulith-core` en alcance de compilación? | `./mvnw -B -pl app dependency:tree -Dincludes=org.springframework.modulith` y, sobre el jar resuelto en `~/.m2`, `jar tf … \| findstr NamedInterface` | Se identifica el artefacto exacto y se declara con esa coordenada, nunca «el que parezca» | **C1** |
| **S2** | ¿`Argon2PasswordEncoder` de `spring-security-crypto` expone un parámetro de secreto? ¿`Argon2BytesGenerator` de Bouncy Castle lo expone con `withSecret(...)`? | Descargar ambos jars al repositorio local y ejecutar `javap -classpath <jar> org.springframework.security.crypto.argon2.Argon2PasswordEncoder` y `javap -classpath <jar> org.bouncycastle.crypto.params.Argon2Parameters$Builder` | Se confirma o se desmiente lo que la propuesta anotó. Si Spring **sí** expusiera secreto, se reevalúa la decisión 6 y se reporta el cambio | **C2** |
| **S3** | ¿`INSERT … ON CONFLICT DO UPDATE SET c = tabla.c RETURNING c, last_attempt_at` devuelve los valores **previos** y toma el bloqueo de fila? | Contra `postgres:18-alpine` fuera del árbol: sesión 1 ejecuta el reclamo dentro de una transacción abierta; sesión 2 ejecuta el mismo reclamo y debe quedar bloqueada hasta la confirmación | Devuelve el estado previo y la segunda sesión se bloquea. Respaldo: `SELECT … FOR UPDATE` precedido de un `INSERT … ON CONFLICT DO NOTHING` para garantizar la existencia de la fila, a costa de una sentencia más | **C3** |
| **S4** | ¿jOOQ 3.21 genera `IdentityStaffAccount` e `IdentityLoginBackoff`? | `./mvnw -B -pl app generate-sources` y listar `target/generated-sources` | Los nombres empiezan por `Identity`. Si no, R2 rompe y el nombre de tabla se revisa antes de escribir adaptadores | **C3** |
| **S5** | ¿`Thread.sleep` sobre un hilo virtual de Java 25 libera el hilo portador? | Programa mínimo fuera del árbol: lanzar 10 000 hilos virtuales que duermen 5 s y observar con `jcmd Thread.print` que el número de hilos de plataforma se mantiene acotado | El número de hilos de plataforma no crece con el de hilos virtuales. Si no se cumpliera, la recomendación para el cambio 3 pasa al respaldo de Servlet asíncrono (decisión 1, punto 3) | Contrato del cambio 3 (no bloquea este cambio) |
| **S6** | ¿`dependencyConvergence` del enforcer sigue en verde al añadir `bcprov-jdk18on` y las anotaciones de Modulith en alcance de compilación? | `./mvnw -B -pl app enforcer:enforce` | En verde. Si no, la versión en conflicto se declara en `dependencyManagement` con un comentario que explique la mediación, **nunca** con una exclusión silenciosa | **C2** |
| **S7** | ¿El códec reproduce el vector de prueba de Argon2id de RFC 9106 §5.3, incluido el valor de `secret`? | Prueba unitaria escrita con los valores del RFC, ejecutada con `./mvnw -B -pl app test -Dtest=Argon2PhcCodecTest` | Coincidencia byte a byte. Es la única prueba de que la pimienta se aplica de verdad y no se ignora en silencio | **C2** |
| **S8** | ¿`CHECK (email !~ '[A-Z]')` acepta todo lo que `LoginIdentifier` produce con `toLowerCase(Locale.ROOT)` tras NFKC? | Prueba de integración con una muestra que incluya `İ` (U+0130), `ß`, griego y CJK | Ninguna inserción rechazada. Si alguna lo fuera, se acota el conjunto aceptado en `LoginIdentifier` y se documenta, nunca relajando la restricción | **C1/C3** |

---

## 11. Secuencia de aplicación con TDD estricto

**C1 — módulo, esquema y puertas**

1. Sondas S1 y S1b. Bloqueantes.
2. Rojo: la mitad de producción de `NoCrossModuleDomainImportsTest` con su guarda de no vacuidad
   —falla porque solo existe un módulo de dominio—. Verde: las primeras clases de
   `com.confia.identity.domain` y su `package-info`.
3. `@NamedInterface` en los dos `package-info` de `shared`, con su ADR-0022, y
   `SpringModulithVerificationTest` en verde.
4. Rojo: `RolePrivilegeMatrixIT` con las filas de las dos tablas e `IdentityRowSecurityIT`. Verde:
   `V5`.
5. Comprobación de que `MultiTenantSchemaIT` pasa **sin modificarse**. Si no pasara, es una
   discrepancia con este diseño y se reporta.
6. Medición del tiempo de la suite.

**C2 — contraseña, señuelo y regla del retroceso**

7. Sondas S2, S6 y S7. Bloqueantes.
8. Rojo: `BackoffPolicyTest` con los cuatro escenarios del delta y sus propiedades jqwik. Verde:
   `BackoffPolicy` y `BackoffState`.
9. Rojo: `Argon2PhcCodecTest` con el vector de RFC 9106. Verde: el códec y el adaptador de Bouncy
   Castle.
10. Rojo: la redacción de `toString()` y las guardas técnicas. Verde: los objetos de valor.
11. Rojo: el hash señuelo derivado con los parámetros vigentes. Verde: su construcción.
12. Rojo: la regla de ArchUnit de ninguna espera, con su fixture. Verde: la regla.

**C3 — caso de uso, auditoría, atomicidad y cierre**

13. Sondas S3, S4 y S8. Bloqueantes.
14. Rojo: `JooqAuditLogWriterIT`. Verde: puerto, registro y adaptador de escritura.
15. Rojo: `AuthenticateWithPasswordTest` (unitaria, con dobles) sobre los dos desenlaces, el señuelo
    por interacción y la ausencia de rehash. Verde: el caso de uso.
16. Rojo: `AuthenticateWithPasswordIT` con los escenarios de retardo, limpieza del contador y éxito
    retardado. Verde: los adaptadores jOOQ.
17. Rojo y verde: `LoginBackoffAtomicityIT` y `LoginBackoffConcurrencyIT` con barrera.
18. Rojo y verde: `LoginInstitutionIT`, `IdentitySecretRedactionIT` e
    `IdentityScopeExclusionInventoryTest`.
19. `LoginTimingReportIT`: medición reportada.
20. Notas editoriales de `docs/03` §4.4 y §6.1, cierre de `docs/09`, y medición final del tiempo de
    la suite.

---

## 12. Pronóstico de tamaño por corte

Líneas de autor (adiciones más eliminaciones) en código, pruebas, POM, SQL, configuración y
documentación. **Quedan fuera** los artefactos de OpenSpec y el código generado de jOOQ. Incorpora la
desviación de 1,5 a 3 veces que este repositorio ya midió.

| Corte | Desglose | Estimación |
|---|---|---|
| **C1** | `V5` con las dos tablas y sus comentarios (160–260), `package-info` del módulo y los dos anotados (60–110), ADR-0022 (90–140), `RolePrivilegeMatrixIT` (70–120), `IdentityRowSecurityIT` (90–150), guarda de no vacuidad y renombrado (40–70), primeras clases de dominio (60–110) | **570 a 960** |
| **C2** | `BackoffPolicy`, `BackoffState` y sus pruebas con jqwik (200–320), códec PHC y su vector (150–250), adaptador Argon2id, pimienta y señuelo (170–270), objetos de valor con redacción y sus pruebas (140–230), regla de ArchUnit con fixture (60–100), POM (25–45) | **745 a 1 215** |
| **C3** | Caso de uso y contratos (180–280), dos adaptadores jOOQ (170–270), puerto, registro y adaptador de auditoría (150–240), `AuthenticateWithPasswordIT` (180–290), atomicidad y concurrencia (170–270), institución, redacción, inventarios y medición (200–320), notas de `docs/03` y `docs/09` (80–140) | **1 130 a 1 810** |
| **Total** | | **2 445 a 3 985** |

**Frente al presupuesto de 800 líneas de cambio efectivo por pull request**
(`docs/15-flujo-de-trabajo-git.md` §3, `CLAUDE.md`):

- **C1** cabe salvo en su rango alto. **C2** lo supera en el rango alto. **C3** lo supera **incluso en
  su rango medio** y se planifica dividido desde el principio.
- Subdivisiones ya identificadas, sin separar nunca código de sus pruebas:
  - **C3a** = puerto y adaptador de auditoría, caso de uso y su prueba unitaria (≈500–790).
  - **C3b** = adaptadores jOOQ, integración, atomicidad, concurrencia, institución, inventarios y
    documentación (≈630–1 020), partible a su vez si el diff real lo exige.
- **Pronóstico de entrega: cuatro o cinco pull requests encadenados.** Al cerrar cada corte se mide
  el diff real y, si supera 800, el corte se parte, como hizo el cambio 5 cuando su corte A3 midió
  813 líneas.
- **Tareas: entre 9 y 13**, por debajo del límite de quince de `openspec/changes/README.md`.

**Nota sobre la política de la sesión, que la propuesta ya levantó y este diseño confirma.** La
preflight registra estrategia `single-pr` y presupuesto de 400 líneas. El proyecto fija **800 líneas
y cortes encadenados**. Este pronóstico no cabe en un solo pull request bajo ninguna lectura: **el
orquestador debe actualizar la estrategia a `auto-chain` antes de la fase de tareas**, o el
propietario debe aceptar explícitamente una excepción de tamaño que este diseño no recomienda.

---

## 13. Restricciones del entorno local

- `JAVA_HOME` apuntando al JDK 25 y `MAVEN_OPTS="-Djavax.net.ssl.trustStoreType=Windows-ROOT"` (esta
  última **nunca se compromete**); siempre `./mvnw -B` desde `apps/api`, con la salida redirigida a un
  archivo temporal.
- **Dos dependencias nuevas hay que descargar** (`bcprov-jdk18on` y el artefacto de anotaciones de
  Modulith). Esta máquina tiene una brecha de confianza PKIX conocida en descargas nuevas: si la
  descarga falla, es un problema de entorno y se reporta como tal, nunca se sortea desactivando la
  verificación de certificados.
- Docker con `postgres:18-alpine` ya descargado. Sobre WSL2 el comportamiento puede diferir del de
  Linux: **ante divergencia manda la integración continua**.
- **Este cambio empeora el tiempo de la suite de integración**: cada intento de autenticación cuesta
  una verificación Argon2id de cientos de milisegundos por diseño. Las pruebas usan el perfil
  **vigente** y no uno rebajado —rebajarlo probaría otra cosa—, salvo `LoginTimingReportIT`, cuya
  muestra ya está recortada a 25 más 25 por esta razón. El tiempo se mide al cerrar cada corte; si el
  margen frente a los 8 minutos se estrecha de verdad, la deuda W1 deja de ser diferible y se eleva.
- Nunca se baja un umbral ni se omite una prueba para pasar en local.

---

## 14. Por confirmar durante la implementación

1. Las ocho sondas de la sección 10.
2. Que `MultiTenantSchemaIT` pasa sobre las dos tablas nuevas **sin ninguna modificación**. Si la
   aplicación lo desmiente, se reporta la discrepancia; **no se relaja la puerta**.
3. Que `has_table_privilege('confia_owner', 'identity_staff_account', 'DELETE')` devuelve
   **verdadero**: el propietario conserva todo privilegio por definición, y la fila de la matriz debe
   afirmar ese verdadero, como ya hace para las tablas de auditoría.
4. Que el `DSLContext` construido sobre `TransactionAwareDataSourceProxy` se une a la transacción que
   abre `TransactionRunner`, de modo que los adaptadores vean el contexto fijado. Probado ya para
   `shared`; aquí lo usa un módulo de negocio por primera vez.
5. Que insertar en `shared_audit_log` con jOOQ **omitiendo** `id`, `prev_hash` y `row_hash` no
   produce una sentencia que los incluya con `NULL`: se construye con `.set()` explícito por columna,
   nunca con un registro completo.
6. Que el tiempo de construcción del hash señuelo no retrasa de forma apreciable el arranque de la
   suite de integración; si lo hiciera, el componente se construye una sola vez por JVM.
7. Que la guarda de longitud de contraseña (1024) no rechaza ninguna frase de paso razonable de
   §4.2, cuyo máximo declarado es 128 caracteres.

Si alguna confirmación obliga a apartarse de lo decidido, se eleva a un ADR y no se entierra aquí
(`docs/13-metodologia-sdd.md`).

---

## 15. Ajustes que este diseño propone, con su redacción exacta

### Ajuste 1 — `specs/build-integrity/spec.md`, de una tabla a dos. **Bloqueante**

Motivo: la decisión 3. Con una sola tabla, el escenario ya escrito del retardo sobre una cuenta
inexistente es inimplementable.

**Título del requisito**, de:

> ### Requisito: Tabla nueva de la cuenta de personal, con `institution_id` y seguridad de fila forzada

a:

> ### Requisito: Tablas nuevas de identidad, con `institution_id` y seguridad de fila forzada

**Primera frase del cuerpo**, de:

> El sistema DEBE mantener la migración `V5`, que crea la tabla nueva de identidad —con el prefijo de
> módulo `identity_`, según ADR-0015 regla 3— para la cuenta de personal y su estado de retroceso por
> intentos fallidos.

a:

> El sistema DEBE mantener la migración `V5`, que crea **dos** tablas nuevas de identidad —ambas con
> el prefijo de módulo `identity_`, según ADR-0015 regla 3—: una para la cuenta de personal, y otra
> para el estado de retroceso por intentos fallidos, indexada por una huella con llave del
> identificador presentado y **no** por la cuenta, porque el retardo se aplica también a
> identificadores que no corresponden a ninguna cuenta. Todo lo que este requisito exige de «la tabla
> nueva» DEBE cumplirse en **cada una de las dos**.

El resto del requisito y sus tres escenarios se aplican en plural sin más cambio que sustituir «la
tabla nueva de identidad» por «cada tabla nueva de identidad». En el requisito de privilegios, la
misma sustitución, y `RolePrivilegeMatrixIT` se extiende «con las filas de **las dos** tablas nuevas
para los cinco roles».

### Ajuste 2 — `specs/identity/spec.md`, un requisito nuevo. **Recomendado, no bloqueante**

Motivo: la decisión 1 es la decisión de arquitectura de este cambio y hoy no está escrita en ningún
delta. Los escenarios ya escritos se satisfacen con la duración calculada y auditada, así que el
cambio es implementable sin este ajuste; sin él, en cambio, nada impide que `session-tokens-and-web-layer`
materialice la espera dentro de la transacción y reabra el agujero.

> ### Requisito: El retardo se calcula dentro de la transacción y se materializa fuera de ella
>
> El caso de uso de autenticación DEBE calcular la duración del retardo exigible y devolverla junto
> al desenlace, y DEBE confirmar su transacción antes de devolverla. El sistema NO DEBE materializar
> la espera mientras mantiene abierta una transacción de base de datos, una conexión del grupo o un
> bloqueo de fila, ni reteniendo un hilo de plataforma. El número de respuestas retardadas
> simultáneas DEBE estar acotado, y al alcanzar ese límite el sistema DEBE responder con un error de
> capacidad uniforme decidido antes de procesar el intento, y NO DEBE acortar el retardo.
>
> #### Escenario: La duración exigible se devuelve y la transacción ya confirmó
>
> - **DADO** una cuenta con tres intentos fallidos consecutivos registrados
> - **CUANDO** se invoca el caso de uso con la contraseña correcta
> - **ENTONCES** devuelve el desenlace `Authenticated` junto con una duración exigible de 2 segundos
> - **Y** el contador de fallos ya está limpio y el ciclo ya está auditado en el momento en que
>   devuelve, sin que ninguna espera haya ocurrido todavía
>
> #### Escenario: Ninguna clase del módulo de identidad espera
>
> - **DADO** el árbol de clases de producción de `com.confia.identity`
> - **CUANDO** se inspecciona si alguna invoca una primitiva de espera del hilo
> - **ENTONCES** ninguna lo hace, y la construcción falla si alguna lo hiciera

### Ajuste 3 — `docs/03-seguridad.md` §4.4, nota editorial fechada

La propuesta ya la contempla (punto 10 de «Dentro de alcance»). Redacción propuesta, **sin reescribir
el cuerpo de la sección**:

> **Nota editorial, 2026-09-24 (`identity-module-and-password-authentication`).** El estado del
> retroceso **por cuenta** vive en PostgreSQL y no en Redis, por atomicidad con la bitácora de
> auditoría (propuesta D1, aprobada). La dimensión por dirección IP de esta misma sección sigue
> apuntando a Redis, con el control en `session-tokens-and-web-layer` y el aprovisionamiento en el
> cambio 11.
>
> Sobre «el retardo se aplica antes de responder»: el retardo se **calcula y se exige** dentro de la
> transacción del caso de uso, y se **materializa** en el borde HTTP una vez confirmada esa
> transacción. Nunca se espera reteniendo una transacción, una conexión del grupo, un bloqueo de fila
> ni un hilo de plataforma: hacerlo convertiría este control en un amplificador de denegación de
> servicio, que es justo lo que esta sección existe para evitar.
>
> Y una precisión sobre su alcance: el retardo por cuenta **no es un limitador de tasa**. Un atacante
> que cierra la conexión no espera nada. Lo que este control da es uniformidad de tiempo frente a la
> enumeración, penalización del atacante secuencial y rastro auditable. La cota de tasa la pone la
> dimensión por IP.

### Ajuste 4 — `docs/03-seguridad.md` §6.1, nota editorial fechada

> **Nota editorial, 2026-09-24 (`identity-module-and-password-authentication`).** La tabla que esta
> sección llama `user` se entrega como `identity_staff_account`, con el prefijo de módulo que exige
> la regla 3 de ADR-0015. La tabla `identity_login_backoff`, que esta sección no nombra por ser
> posterior, recibe el mismo trato que `user`: `SELECT`, `INSERT` y `UPDATE` para
> `confia_admin_app`, sin `DELETE`; `SELECT` para `confia_readonly`; y **ningún privilegio** para
> `confia_portal_app`.

### Ajuste 5 — `docs/03-seguridad.md` §4.1. **AUTORIZADO POR EL PROPIETARIO EL 2026-09-24**

La propuesta no sancionaba nota editorial sobre §4.1, y este diseño no la escribió sin permiso. El
propietario la autorizó de forma explícita, así que **entra en la fase de tareas** junto a las notas
ya sancionadas sobre §4.4 y §6.1.

Motivo: §4.1 dice que Argon2id se adopta «integrada con el codificador de contraseñas de Spring
Security», y este cambio no trae Spring Security en ninguna forma (decisión 6). La nota, fechada y
**sin reescribir el cuerpo de la sección**, debe dejar constancia de tres cosas:

1. Este cambio implementa Argon2id **sin** Spring Security, sobre Bouncy Castle directo, porque la
   cadena de filtros pertenece a `session-tokens-and-web-layer` y traerla aquí adelantaría trabajo de
   otro cambio.
2. La exigencia sustantiva de §4.1 —la pimienta de 32 bytes fuera de la base, aplicada como `secret`
   de Argon2id— **se cumple**, y es precisamente lo que empuja a Bouncy Castle directo.
3. La integración con `PasswordEncoder` sigue siendo trivial el día que llegue la cadena de filtros,
   porque el puerto `PasswordHasher` tiene exactamente esa forma. La nota no renuncia a la
   integración: registra que llega con el cambio que trae el marco.

Queda sujeta al resultado de la sonda **S2**: si S2 desmintiera lo que la propuesta anotó y el
codificador de Spring sí admitiera un secreto, la decisión 6 se reevalúa y esta nota se reescribe
antes de entregarla.

---

## 16. Preguntas abiertas

- [ ] **1. Techo de transporte para el retardo entregado.** Ninguna respuesta HTTP retenida 900
      segundos llega a un cliente real. Este cambio conserva el modelo íntegro —calcula, exige y
      audita la duración completa— y deja la decisión del techo efectivamente entregado a
      `session-tokens-and-web-layer`, que es quien tiene servidor, intermediarios y cliente.
      Recomendación: que ese cambio fije un techo explícito, lo documente y lo audite como tal, en vez
      de descubrirlo cuando un balanceador cierre la conexión en producción. **No bloquea este
      cambio.**
- [x] **2. Nota editorial sobre §4.1 y Spring Security** (ajuste 5). **Autorizada por el propietario
      el 2026-09-24.** Deja de ser pregunta abierta y entra en la fase de tareas, junto a las notas
      ya sancionadas sobre §4.4 y §6.1. Su redacción queda sujeta al resultado de la sonda **S2**.
- [ ] **3. `actor_id` en `NULL` para un actor no identificado.** El comentario de columna de §12.1
      dice «`NULL` para actor de sistema»; este cambio lo usa además para «actor no identificado».
      Recomendación: ampliar ese comentario en una pasada documental posterior, no en este cambio, que
      ya lleva dos notas editoriales.
- [x] **4. Purga y crecimiento de `identity_login_backoff`. TRASLADADA EL 2026-09-25, y deja de
      ser una pregunta abierta de este diseño.** Las filas de identificadores inexistentes no caducan
      físicamente: el contador expira a los 30 minutos, pero la fila permanece. No se entrega índice
      ni trabajo de purga, siguiendo el precedente del cambio 6 de no enviar un índice sin consumidor.

      El informe de seguridad previo a la fusión del corte C1 señaló que la mitigación que este
      diseño citaba —«la cota real es el control por IP, que ya tiene dueño»— es una secuencia de
      trabajo razonable pero **no una mitigación cerrada**, porque ese control no existe en ningún
      commit fusionado. Y una pregunta abierta de un diseño **no bloquea nada**.

      Por eso se reescribió como **condición dura de aceptación de `session-tokens-and-web-layer`**,
      en `openspec/changes/foundations-plan/exploration.md`, nota del 2026-09-25: ese cambio no debe
      fusionar un endpoint de inicio de sesión que escriba en esta tabla sin el control por dirección
      IP de `docs/03-seguridad.md` §4.4 —o un tope equivalente— existente, probado y operativo. La
      purga en sí sigue con dueño propuesto en el cambio que introduzca trabajo de mantenimiento
      programado con `db-scheduler` (ADR-0016), a asignar antes de F1.
- [x] **5. Estrategia de entrega de la sesión** (sección 12). **RESUELTA POR EL ORQUESTADOR EL
      2026-09-24:** `auto-chain` con cadena `stacked-to-main`, presupuesto de 800 líneas por pull
      request (`docs/15-flujo-de-trabajo-git.md` §3). No hizo falta excepción de tamaño: el total de
      2 445 a 3 985 líneas es del cambio completo, no de un corte. Ver `tasks.md`, sección
      «Estrategia de entrega».
