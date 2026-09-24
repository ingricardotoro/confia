# Propuesta: infraestructura de clave de idempotencia

- **Cambio:** `idempotency-key-infrastructure`
- **Fase del roadmap:** F0, cambio 6 de 13 (`openspec/changes/foundations-plan/exploration.md` §1)
- **Exploración:** `openspec/changes/idempotency-key-infrastructure/exploration.md`
- **Rama:** `change/idempotency-key-infrastructure`
- **Brecha que cierra:** B7 (infraestructura), `docs/09-roadmap-y-fases.md` F0
- **Criterio de salida de F0 que cierra:** criterio 4, «dos solicitudes con la misma clave de
  idempotencia producen un solo efecto y la misma respuesta»
- **Depende de:** cambio 5, `audit-log-and-transaction-runner`, fusionado y archivado
- **Estado:** pendiente de aprobación del propietario (`openspec/config.yaml`, `rules.proposal`)

## Intención

ADR-0010 declaró el control uno —idempotencia explícita— el 2026-09-10 y desde entonces **no existe
ni una línea de código que lo implemente**. Lo verificado hoy en el árbol: no hay tabla de claves,
no hay componente, no hay interceptor, y `TransactionRunner.execute(...)` no tiene ninguna noción
de idempotencia. Todo el comportamiento de la tabla de ADR-0010 —respuesta original replicada, 409
en curso, 422 por carga distinta, caducidad a veinticuatro horas— vive únicamente en documentación.

Eso deja tres compromisos abiertos:

1. **El criterio de salida 4 de F0 no tiene sustrato.** Es el único criterio de los siete que no
   puede demostrarse con lo entregado hasta el cambio 5.
2. **`payments/spec.md` ya promete el comportamiento y nada lo sostiene.** Su requisito «Registro de
   pago con clave de idempotencia» declara que un reintento devuelve el mismo `PAY-55010` sin crear
   un segundo pago. Esa promesa está escrita en una especificación publicada y hoy es una promesa
   sin mecanismo. F3 la consumirá dándola por existente.
3. **El cambio 9 depende de este.** La tabla de F0 lo declara explícitamente: db-scheduler depende
   de 5 y de 6. La purga por caducidad que ADR-0010 exige no puede escribirse antes que la tabla
   que purga.

Y deja además un compromiso **incumplido por escrito**: el Javadoc de `TransactionRunner` declara
que registrarlo como bean «es trabajo del primer cambio que lo consuma (cambio 6)». Este cambio es
ese primer consumidor y tiene que resolver esa frase de una manera u otra. La resuelve —ver
«Registro del bean»— con una corrección del Javadoc respaldada por evidencia, no con el registro
que la frase anticipaba.

Este cambio es **fundación transversal, no negocio**, igual que el 5. Su valor se mide por lo que
hace posible: F3 no puede registrar un pago idempotente sin él, y el cambio 9 no tiene qué purgar.

## Alcance

### Dentro de alcance

1. **Migración `V4` de la tabla `shared_idempotency_key`**: nombre con prefijo de módulo
   (ADR-0015 regla 3, contando `shared` como módulo válido, precedente de `shared_audit_log`);
   **clave primaria natural compuesta `(institution_id, endpoint, idempotency_key)`**, que satisface
   por construcción la puerta de índices únicos y evita un identificador sustituto, porque
   `pgcrypto` no está disponible (sonda S6 del cambio 5); columnas `request_hash`, `status`,
   `response_status`, `response_body`, `created_at`, `completed_at` y `expires_at` según ADR-0010;
   seguridad a nivel de fila habilitada y forzada con el mismo patrón `NULLIF` de `V1` a `V3`;
   `REVOKE ALL ... FROM PUBLIC` y los `GRANT` que `docs/03-seguridad.md` §6.1 ya concede.
2. **Extensión de las puertas de esquema existentes, sin reescribirlas**: filas de
   `shared_idempotency_key` en `RolePrivilegeMatrixIT` para los cinco roles, y verificación de que
   las puertas genéricas de `MultiTenantSchemaIT` pasan sobre la tabla **sin lista de exclusión**.
3. **Componente de ejecución idempotente en `com.confia.shared.security`**, que envuelve marcador y
   efecto en una sola llamada a `TransactionRunner.execute(...)` y por tanto en una sola
   transacción. Ver «Enfoque», punto 1.
4. **Puerto de almacenamiento y su adaptador jOOQ**, siguiendo el precedente exacto que el cambio 5
   estableció con `AuditLogReader` (puerto en `shared/audit`) y `JooqAuditLogReader` (adaptador en
   `shared/infrastructure`). El componente no toca jOOQ; la regla R1 lo confina a `infrastructure`.
5. **Espera acotada con `lock_timeout` explícito**, que convierte el bloqueo indefinido medido por
   la sonda en un error tratable. La decisión está tomada por el propietario el 2026-09-22 y esta
   propuesta no la reabre; el valor, el ámbito y la traducción del error quedan para el diseño.
6. **Hash de la carga útil canonicalizada** (`request_hash`), que es lo que distingue el caso
   «misma clave, misma carga» del caso «misma clave, carga distinta» que ADR-0010 rechaza con 422.
7. **Semántica de la clave caducada**, que ADR-0010 define como «se trata como nueva», resuelta con
   el único mecanismo que los privilegios permiten. Ver «La caducidad sin purga no es una purga».
8. **Demostración del criterio de salida 4** sobre el sustrato de escritura real de
   `organization_institution`. Ver «Enfoque», punto 3, y la declaración honesta de qué parte de la
   demostración es parcial.
9. **Nota editorial fechada sobre ADR-0010**, que corrige el esquema y la narrativa de concurrencia
   sin reescribir el cuerpo de una decisión ya tomada. Ver «Tratamiento de ADR-0010».
10. **Corrección del Javadoc de `TransactionRunner`**, que hoy afirma algo que este cambio decide no
    hacer. Ver «Registro del bean».
11. **Actualización de `docs/09-roadmap-y-fases.md`** en su criterio de salida 4 y en el estado del
    entregable 6, con la mitad diferida nombrada.

### Fuera de alcance

Cada exclusión lleva **destino nombrado**. Las tres primeras se escriben en el delta de
especificación **dentro de bloques `### Requisito:`**, no como prosa suelta ni como sección «Fuera
de alcance». Ver «Cómo se escriben las exclusiones para que no caduquen en silencio».

- **Superficie HTTP completa de ADR-0010** —cabecera `Idempotency-Key` obligatoria con `400` ante su
  ausencia, cabecera `Idempotent-Replay: true`, y la traducción a `200`, `409` y `422`—: **cambio
  7**, `staff-authentication-mfa-sessions`, que trae el primer endpoint según `docs/09` línea 99, y
  las capacidades consumidoras de F3. Motivo verificado, no preferencia: hoy no existe ninguna clase
  en una capa `web`, `optionalLayer("Web")` sigue declarado como capa opcional, y los tres puntos de
  entrada excluyen `DataSourceAutoConfiguration` porque ninguna propiedad `spring.datasource.*`
  existe. Adelantar la superficie HTTP aquí significa adelantar el pool de producción entero para un
  endpoint que ADR-0019 desaconseja por especulativo.
- **Purga física por caducidad**: **cambio 9**, `background-jobs-with-db-scheduler`, que trae
  db-scheduler. Esta propuesta modela `expires_at` y define la semántica lógica de la clave
  caducada; no borra ninguna fila. El cambio 9 hereda además una tensión de privilegios que esta
  propuesta deja escrita y no resuelve. Ver «La caducidad sin purga no es una purga».
- **Acceso del rol `confia_portal_app` a la tabla**: la capacidad de F3 o F4 que dé al portal su
  primer camino de escritura financiera. `docs/03` §6.1 concede a `confia_portal_app` `INSERT` solo
  en `document_request`, `payment_intent` y `notification_preference`, y «ningún `UPDATE` ni
  `DELETE` en ninguna tabla». Un pago iniciado desde el portal, que ADR-0010 sí contempla en su
  tabla «Origen de la clave», no podría escribir su marcador con esos privilegios. Esta propuesta
  **no inventa el `GRANT` que lo resolvería**: lo declara como límite conocido del control mientras
  no exista ese camino de escritura.

Y además, sin requisito de caducidad porque no son brechas de este control:

- **Controles dos, tres y cuatro de ADR-0010** —bloqueo pesimista sobre la cuenta del estudiante,
  aislamiento serializable de cierre de caja y de emisión fiscal, y secuencia de correlativo—: sus
  dueños son `ledger`, `cashbox` e `invoicing`, en **F3** y **F5**. `TransactionRunner` ya expone
  `SERIALIZABLE` desde el cambio 5 y este cambio no añade ayudante de bloqueo sin consumidor.
- **Deudas W1 y W2 heredadas del cambio 5A**: **cambio 11**,
  `containerization-and-cicd-pipeline`. Se confirma el reparto de la exploración §6 y el del propio
  cambio 5: W1 exige tocar `.github/workflows/ci.yml` y medir el tiempo de Failsafe, y W2 toca el
  complemento de generación de código y el POM. Este cambio no toca ninguna de las dos maquinarias
  para nada más, así que incorporarlas sería relleno que diluye la revisión.
- **Registro de `TransactionRunner` como bean de producción**: el cambio que declare la primera
  fuente de datos de producción y retire la exclusión de `DataSourceAutoConfiguration`. Ver
  «Registro del bean».

## Capacidades

> Contrato con `/sdd-spec`.

### Nuevas

**Ninguna.**

### Modificadas

- `build-integrity`: delta con `## ADDED Requirements` y, si el `lock_timeout` se fija en el
  componente transaccional, `## MODIFIED Requirements` sobre el requisito «Contexto de sesión, nivel
  de aislamiento y reintento acotado del componente transaccional único».

### Por qué se confirma la recomendación de la exploración

La exploración recomendó delta de `build-integrity` y no capacidad nueva. **Se confirma**, con su
evidencia y con dos argumentos más, uno de los cuales es la refutación del contraargumento más
fuerte que existe.

La evidencia de la exploración, verificada de nuevo:

- `docs/01-arquitectura.md` sitúa la idempotencia dentro del árbol `shared/security`, no como módulo
  de negocio propio.
- La matriz de permisos de `docs/03` §5.2 no tiene fila para idempotencia, mientras que `audit` sí
  la tiene. No hay verbo de negocio que la consuma como capacidad.
- `openspec/specs/payments/spec.md` ya declara su propio requisito «Registro de pago con clave de
  idempotencia», con dos escenarios. El comportamiento observable de negocio **ya tiene casa**.

Los dos argumentos adicionales:

- **El propósito declarado de `build-integrity` describe exactamente este cambio.** Su encabezado
  dice: «No define reglas de negocio nuevas; hace verificable lo ya decidido». ADR-0010 ya decidió;
  lo que falta es hacerlo verificable por `./mvnw verify`. Es la misma clase de garantía mecánica
  que el requisito del componente transaccional que esa capacidad ya alberga.
- **El precedente `audit-trail` no se traslada, y esa es la comparación que importa.** El cambio 5
  creó capacidad propia para la bitácora con un motivo concreto: «enterrada dentro de
  `build-integrity` sería imposible de encontrar para quien busque qué promete el sistema sobre la
  trazabilidad del dinero». Ese motivo se sostenía porque **ninguna capacidad de negocio declaraba
  la cadena de auditoría**. Aquí la situación es la inversa: `payments` ya la declara, y F3 añadirá
  `invoicing` y `cashbox`. Una capacidad `idempotency` publicada duplicaría una promesa que ya vive
  donde un lector la busca. El mismo cambio 5 rechazó `transactional-runtime` con este razonamiento
  literal: «una capacidad nueva para un solo componente, cuyo contrato observable ya vive en una
  regla existente, añadiría superficie que mantener y archivar sin hacer nada más fácil de
  encontrar».

**Costo aceptado, declarado en voz alta.** La garantía mecánica queda en una capacidad que se llama
«integridad de la construcción», que no es donde un lector nuevo la buscaría primero. Se mitiga de
dos formas y ninguna es una nota al pie: el requisito lleva un título que nombra el criterio de
salida 4 de forma literal, y `payments/spec.md` permanece como la declaración orientada al negocio
que cualquier lector encuentra antes.

## Enfoque

De la tabla hacia afuera, al revés que el cambio 5B, porque aquí el mecanismo es lo caro y el
control ya está decidido. Cada paso deja `./mvnw verify` en verde antes del siguiente y sigue TDD
estricto (`openspec/config.yaml`, `strict_tdd: true`).

**Sin dependencia mecánica de orden, a diferencia del cambio 5.** Aquel tuvo que crear
`com.confia.shared.security` antes que la migración, porque el módulo `shared` no existía y la
puerta de prefijo de módulo se deriva de los paquetes de producción reales. Hoy `com.confia.shared`
ya tiene tres paquetes de producción. La tabla puede llegar primero.

### 1. La API que envuelve marcador y efecto sin duplicar el componente ni romper R3

La regla R3 confina la **apertura** de transacciones a `com.confia.shared.security`. Un componente
que vive en ese mismo paquete y delega en `TransactionRunner.execute(...)` satisface R3 por
construcción, no por excepción: no abre nada, reusa el único que abre.

La forma propuesta, cuyos nombres exactos fija el diseño:

- Un componente nuevo en `com.confia.shared.security` que recibe el `TransactionRunner` por
  constructor explícito —el mismo patrón sin anotación que ya usan `TransactionRunner` y
  `JooqInstitutionRepository`— y expone una operación que toma el `SecurityContext`, la identidad de
  la clave (`endpoint`, `idempotency_key`, `request_hash`) y el `Supplier<T>` del caso de uso.
- Dentro de **una sola** invocación de `execute(...)`, y por tanto de una sola transacción: escribe
  el marcador en curso, ejecuta el caso de uso, y completa el marcador con el resultado.
- El resultado devuelto distingue ejecución de repetición, para que el llamador —hoy una prueba,
  mañana un controlador— pueda producir la cabecera `Idempotent-Replay` sin volver a consultar nada.
- La escritura y la lectura del marcador salen por un **puerto** declarado junto al componente, con
  su adaptador jOOQ en `com.confia.shared.infrastructure`. Es el precedente literal de
  `AuditLogReader` y `JooqAuditLogReader`, y es lo que mantiene el componente libre de jOOQ, que la
  regla R1 confina a `infrastructure`.

**`TransactionRunner` no se modifica para saber de idempotencia, y esa es la decisión.** Añadirle
una sobrecarga consciente de claves lo convertiría en dos responsabilidades: el único punto que abre
transacciones, y además el dueño del ciclo de vida de un marcador. La composición lo evita sin costo
y sin duplicar nada.

**Consecuencia verificada del reintento acotado existente.** `TransactionRunner.isRetryable(...)`
reintenta únicamente ante `ConcurrencyFailureException` o `SQLState` `40001` y `40P01`. Ni el
choque por clave duplicada ni el agotamiento de `lock_timeout` están en esa lista, así que ambos
**se propagan sin reintentarse**. El componente nuevo no tiene que desactivar ni sortear el
reintento: la separación ya existe y es correcta. El diseño debe confirmarla con prueba, no darla
por buena por lectura.

### 2. La espera acotada y las tres salidas que el diseño debe mapear

La sonda del 2026-09-22 contra `postgres:18-alpine` midió el comportamiento real con clave primaria
natural compuesta: la segunda sesión **se bloquea** —5 847 ms si la primera confirma, 4 658 ms si
revierte— y `lock_timeout` vale `0` por defecto, es decir, sin límite.

Con `lock_timeout` explícito, la segunda solicitud concurrente tiene tres salidas y las tres son
distintas:

| Salida | Qué la produce | Significado |
|---|---|---|
| Agotamiento de la espera | La primera sigue abierta cuando vence el `lock_timeout` | Operación en curso; error tratable que la capa web traducirá a `409` |
| Choque por clave duplicada | La primera confirmó dentro de la espera | La clave ya está completada; corresponde repetir la respuesta original |
| Inserción exitosa | La primera revirtió dentro de la espera | Clave libre; se ejecuta de verdad |

**Consecuencia estructural que el diseño debe asumir:** la salida por clave duplicada aborta la
transacción en curso, así que la lectura de la respuesta original para repetirla **no puede ocurrir
dentro de esa misma transacción**. Requiere una transacción nueva. Esto no es un detalle de
implementación: determina la forma del componente y, con ella, cuántas veces se abre transacción en
el peor camino.

El camino frecuente —el reintento secuencial del cajero, no el concurrente— se resuelve con una
lectura previa del marcador antes de intentar la inserción, que nunca toca el índice único. La
comprobación previa **no sustituye** al índice: es una optimización del caso común sobre un control
que sigue viviendo en la restricción.

PostgreSQL señala el agotamiento de `lock_timeout` con su propio `SQLState` —presumiblemente
`55P03`, `lock_not_available`—, distinto del de la clave duplicada. **Esto no está verificado por
sonda y el diseño debe confirmarlo**, porque de ese código depende que las tres salidas se
distingan sin heurística sobre el texto del mensaje.

**Queda explícitamente para el diseño**, tal como lo dejó la exploración: el valor concreto del
`lock_timeout`, dónde se fija —por sesión, por transacción o por sentencia—, y la traducción exacta
del error de tiempo agotado a código de estado y a cuerpo de respuesta. Esta propuesta no los
adelanta.

### 3. El sustrato de demostración del criterio de salida 4, y qué parte es parcial

La exploración propuso reutilizar el camino de escritura real sobre `organization_institution` que
`TransactionRunnerRetryIT` ya usa. **Se acepta, con una corrección y una advertencia.**

**La corrección: el efecto tiene que ser contable.** `TransactionRunnerRetryIT` actualiza
`legal_name` a un valor fijo, y una actualización a valor fijo es idempotente por naturaleza:
ejecutarla dos veces deja el mismo resultado que ejecutarla una, así que **no puede testificar
«un solo efecto»**. La demostración necesita un efecto que sea observablemente distinto si ocurre
dos veces. La forma recomendada es una actualización acumulativa sobre la misma columna —del estilo
`legal_name = legal_name || <sufijo>`—, que conserva todo lo que hace real al sustrato —tabla de
producción, política de fila, contexto de seguridad, privilegios de `confia_admin_app`— y convierte
la duplicación en un valor final inequívocamente distinto. La inserción de una fila en
`shared_audit_log` es una alternativa igual de contable, por conteo de filas, disponible desde el
cambio 5. El diseño elige una y justifica.

**La advertencia, dicha sin adorno: la demostración va a ser parcial, y la parte que falta no es
pequeña.** No existe ninguna clase de producción en una capa `web` ni ningún camino de escritura en
Java sobre `organization_institution` —`JooqInstitutionRepository` solo expone `findById`—. Lo que
la demostración ejercita es el componente de producción real y el `TransactionRunner` de producción
real contra PostgreSQL real, con un caso de uso que aporta la prueba. Eso es genuino y es lo que el
criterio 4 pide.

Lo que **no** demuestra, y queda declarado como requisito con destino nombrado:

| Lo demostrado aquí | Lo no demostrado, y su dueño |
|---|---|
| Un solo efecto ante dos solicitudes con la misma clave | Que un endpoint exija la cabecera y devuelva `400` sin ella — **cambio 7** |
| La misma respuesta almacenada, devuelta sin reejecutar | La cabecera `Idempotent-Replay: true` sobre una respuesta HTTP — **cambio 7** |
| La salida tratable ante solicitud concurrente, con espera acotada | Su traducción a `409` en una respuesta HTTP — **cambio 7** |
| El rechazo de la misma clave con carga distinta | Su traducción a `422` con el cuerpo Problem Details — **cambio 7** |

El criterio de salida 4, leído literalmente, pide «un solo efecto y la misma respuesta». Esta
propuesta lo cierra en esos términos. El entregable 6 de F0 dice además «cabecera obligatoria», y
esa mitad se difiere con su cambio dueño escrito en `docs/09`, no se declara cumplida.

### La caducidad sin purga no es una purga

ADR-0010 dice que una clave de más de veinticuatro horas «se trata como nueva» y que las claves se
eliminan con un trabajo programado. El trabajo programado es del cambio 9. Sin él, la fila caducada
**sigue ocupando su clave primaria**, así que «tratarla como nueva» no puede ser una inserción:
chocaría contra la fila vieja.

`docs/03` §6.1 concede a `confia_admin_app` «`UPDATE` solo en tablas no financieras» y «sin `DELETE`
en ninguna tabla de negocio». La tabla de idempotencia no registra ningún importe ni moneda, así que
es no financiera y el `UPDATE` cabe; el `DELETE` **no cabe para ningún rol de aplicación**. De ahí
se siguen dos cosas que el diseño debe asumir y que esta propuesta no resuelve por él:

1. La reutilización de una clave caducada se modela como **actualización de la fila existente**, no
   como borrado más inserción. Es el único camino que los privilegios vigentes permiten.
2. **El cambio 9 hereda una tensión de privilegios, escrita aquí para que la encuentre.** La purga
   física que ADR-0010 le encarga necesita `DELETE`, y `docs/03` §6.1 se lo niega al rol con el que
   corre `confia-worker`. Resolverlo exige un `GRANT` que hoy ninguna fuente autoriza, o una
   excepción con ADR. Esta propuesta **no inventa ninguno de los dos**.

### Cómo se escriben las exclusiones para que no caduquen en silencio

El cambio 5A dejó un punto de «Fuera de alcance» en `openspec/specs/organization/spec.md` que dejó
de ser cierto con su propia entrega, y hubo que retirarlo a mano al archivar porque vivía fuera de
todo bloque `### Requisito:` y el mecanismo `ADDED`/`MODIFIED`/`REMOVED` no lo alcanzaba. El cambio
5B estableció la disciplina que lo evita y esta propuesta la aplica igual:

1. Toda exclusión que un cambio posterior vaya a cerrar se escribe **dentro de un bloque
   `### Requisito:`**, nunca como prosa suelta ni como sección «Fuera de alcance» del delta.
2. Cada uno de esos requisitos **nombra el cambio dueño** —7, 9, o el de F3/F4 que dé al portal su
   primer camino de escritura financiera— y contiene al menos un escenario **cierto hoy y falso el
   día que la brecha se cierre**.
3. Las tres exclusiones con destino nombrado admiten esa forma ejecutable:
   - **Superficie HTTP ausente:** el control es invocable desde código y **no** existe ningún
     endpoint que exija la cabecera. Escenario verificable hoy, falso el día del cambio 7.
   - **Caducidad sin purga:** una fila con `expires_at` vencido **sigue existiendo** en la tabla y su
     clave se reutiliza por actualización. Escenario verificable hoy, falso el día del cambio 9.
   - **Portal sin acceso:** `confia_portal_app` **no tiene ningún privilegio** sobre
     `shared_idempotency_key`, verificable por `RolePrivilegeMatrixIT`, lo que significa que ninguna
     escritura originada en el portal puede ser idempotente todavía.
4. La sección «Fuera de alcance» de este documento se queda en este documento. El delta de
   especificación no la repite.

## Tratamiento de ADR-0010

ADR-0010 viola hoy dos puertas que están en verde, y además contiene una afirmación de concurrencia
que la sonda refutó. Son dos problemas de naturaleza distinta y esta propuesta los trata distinto.

| Discrepancia | Naturaleza | Tratamiento propuesto |
|---|---|---|
| `CONSTRAINT uq_idempotency UNIQUE (endpoint, idempotency_key)` sin `institution_id` | Rompe `MultiTenantSchemaIT.everyUniqueIndexOfABusinessTableIncludesTheInstitutionDiscriminator` | Nota editorial fechada |
| Nombre `idempotency_keys` sin prefijo de módulo y en plural | Rompe la regla 3 de ADR-0015 | Nota editorial fechada |
| «Dos peticiones simultáneas con la misma clave hagan que la segunda **choque** contra la restricción en lugar de ejecutarse» | **Falso, medido:** la segunda se bloquea esperando, y con `lock_timeout` a cero espera sin límite | Nota editorial fechada que remite al requisito ejecutable |

**Decisión: nota editorial fechada sobre ADR-0010, sin reescribir su cuerpo.** Es el precedente que
el cambio 5 estableció con la decisión D2 sobre ADR-0003, y se sostiene aquí por la misma razón: un
ADR es un registro histórico con fecha, y reescribir su cuerpo borra la procedencia de una decisión
tomada el 2026-09-10 con la información de entonces. La nota debe hacer tres cosas y ninguna más:
remitir al nombre y a la clave primaria vigentes, dejar constancia de que la narrativa de colisión
inmediata fue refutada por sonda con fecha, y nombrar dónde vive el comportamiento corregido.

**Y una advertencia sobre el límite de ese precedente.** La corrección de nombre es editorial; la de
concurrencia **no lo es**: cambia qué le pasa a una solicitud concurrente. Lo que impide que una
nota al pie sea insuficiente no es la nota, sino que el comportamiento corregido se declare como
**requisito con escenario ejecutable** en el delta de `build-integrity`, donde una prueba lo
sostiene y donde envejecer en silencio deja de ser posible. La nota remite; el requisito obliga.

Si al fijar el valor del `lock_timeout` el diseño descubre que tiene consecuencias operativas más
allá de este control —por ejemplo, si se fija a nivel de sesión y alcanza a toda transacción del
sistema—, esa sí es una decisión de arquitectura y `openspec/config.yaml` exige elevarla a un ADR
propio. Esta propuesta no la prejuzga.

## Registro del bean

El Javadoc de `TransactionRunner` dice que registrarlo como bean «es trabajo del primer cambio que
lo consuma (cambio 6)». **Esta propuesta no lo registra, y no por comodidad.**

Verificado en `AdminApplication`, `PortalApplication` y `WorkerApplication`: los tres excluyen
`DataSourceAutoConfiguration` con un motivo escrito —«no `spring.datasource.*` property exists yet
in this process's configuration»— y una condición de retirada también escrita: «the exclusion is
removed by whichever change first registers a real production `DataSource`». Registrar
`TransactionRunner` como bean exige un `DataSource` de producción, que exige propiedades de conexión
de producción, un pool, y la retirada de esa exclusión en tres puntos de entrada. Todo eso para un
componente cuyo único consumidor en F0 es una prueba de integración, que ya lo construye por
constructor explícito desde `CommittingPostgresIntegrationTest`.

ADR-0019 desaconseja superficie especulativa sin consumidor de producción, y el propio cambio 5
aplicó ese criterio para no entregar un adaptador de escritura de auditoría.

Lo que esta propuesta **sí** hace es dejar de mentir: corrige el Javadoc para que nombre al dueño
real —el primer cambio que registre una fuente de datos de producción, que es el mismo que retira la
exclusión de `DataSourceAutoConfiguration`— en vez de seguir señalando al cambio 6. Una frase que
apunta a un cambio que decidió no hacerlo es peor que ninguna frase.

## Áreas afectadas

| Área | Impacto | Descripción |
|---|---|---|
| `apps/api/app/src/main/resources/db/migration/V4__*.sql` | Nueva | Tabla `shared_idempotency_key`, clave primaria natural compuesta, política de fila, `REVOKE`/`GRANT` |
| `apps/api/app/src/main/java/com/confia/shared/security/` | Nueva | Componente de ejecución idempotente y el puerto de almacenamiento |
| `apps/api/app/src/main/java/com/confia/shared/infrastructure/` | Nueva | Adaptador jOOQ del puerto, junto a `JooqAuditLogReader` |
| `apps/api/app/src/main/java/com/confia/shared/security/TransactionRunner.java` | Modificada | Solo Javadoc: el dueño real del registro del bean |
| `apps/api/app/src/test/java/com/confia/shared/security/**IT.java` | Nueva | Un solo efecto; misma respuesta repetida; solicitud concurrente con espera acotada; carga distinta rechazada; clave caducada reutilizada |
| `apps/api/app/src/test/java/com/confia/schema/RolePrivilegeMatrixIT.java` | Modificada | Filas de `shared_idempotency_key` para los cinco roles |
| `apps/api/app/src/test/java/com/confia/schema/MultiTenantSchemaIT.java` | Posible | Solo si la tabla obliga a ajustar una puerta; la expectativa es que pase sin tocarla |
| `docs/adr/ADR-0010-idempotencia-y-concurrencia-financiera.md` | Nota fechada | Esquema, nombre y narrativa de concurrencia; sin reescribir el cuerpo |
| `docs/09-roadmap-y-fases.md` | Modificada | Criterio de salida 4 y estado del entregable 6, con su mitad diferida nombrada |
| `openspec/specs/build-integrity/spec.md` | Delta al archivar | Requisitos nuevos y, si procede, el del componente transaccional |
| `.claude/skills/`, `.claude/agents/` | Posible | Solo si alguna skill o agente enseña hoy el esquema de ADR-0010 con el nombre viejo |

## Pronóstico de cortes y tamaño

Presupuesto vigente: **800 líneas de cambio efectivo por pull request**
(`docs/15-flujo-de-trabajo-git.md` §3, `CLAUDE.md`), con entrega en **cortes encadenados**. Esta
propuesta no reabre ninguno de los dos.

**La estimación de 8 a 9 tareas de la tabla de F0 no se sostiene**, y la razón es que esa tabla se
escribió antes de saber tres cosas que la exploración descubrió: que hace falta una API nueva y no
solo una tabla y un interceptor, que el `lock_timeout` es un mecanismo propio con sus tres salidas,
y que la demostración del criterio de salida 4 necesita sustrato construido a mano. Inventario real:

| Bloque de trabajo | Tareas |
|---|---|
| Migración, política de fila, privilegios y extensión de las puertas de esquema | 2 a 3 |
| Puerto, adaptador jOOQ y componente de ejecución idempotente | 2 a 3 |
| `lock_timeout`, sus tres salidas y el hash de carga canonicalizada | 2 a 3 |
| Demostración del criterio de salida 4, concurrencia, carga distinta y caducidad | 2 a 3 |
| Nota de ADR-0010, Javadoc de `TransactionRunner` y `docs/09` | 1 a 2 |
| **Total** | **10 a 13** |

Queda **por debajo del límite de quince** de `openspec/changes/README.md`, así que el cambio no se
parte y no necesita el precedente que el propietario concedió a los cambios 4 y 5A.

Estimación de líneas, con el historial de subestimación de 1,5× a 3× de este repositorio declarado
y no descontado:

| Corte | Contenido | Estimación |
|---|---|---|
| **C1 — tabla y puertas** | Migración `V4`, política de fila, `REVOKE`/`GRANT`, filas de `RolePrivilegeMatrixIT`, verificación de que `MultiTenantSchemaIT` pasa sin excepción | 350 a 600 |
| **C2 — componente y mecanismo** | Puerto, adaptador jOOQ, componente en `shared/security`, `lock_timeout` con sus tres salidas, hash de carga canonicalizada, pruebas unitarias y de integración del mecanismo | 500 a 800 |
| **C3 — demostración y documentación** | Criterio de salida 4, concurrencia con espera acotada, carga distinta, clave caducada, nota de ADR-0010, Javadoc y `docs/09` | 400 a 700 |
| **Total de código** | | **1 250 a 2 100** |
| Artefactos de OpenSpec | | 600 a 1 000 adicionales |

**Pronóstico de entrega: dos o tres pull requests encadenados.** C1 y C3 podrían ir juntos en el
extremo bajo, pero se declaran separados para que la decisión se tome con el diff en la mano y no
con una estimación, que es el patrón que el cambio 5 pagó por aprender cuando A3 midió 813. Al
cerrar cada corte se mide el diff real y, si supera 800, la aplicación se detiene y el corte se
parte.

## Puntos de diseño abiertos

No son decisiones de producto. Son preguntas técnicas que esta propuesta **no cierra a propósito**.

- **P1. Valor, ámbito y traducción del `lock_timeout`.** Explícitamente reservado al diseño por la
  resolución del propietario del 2026-09-22: cuánto vale, si se fija por sesión, por transacción o
  por sentencia, y cómo se traduce el error de tiempo agotado a código de estado y a cuerpo de
  respuesta. El ámbito importa más de lo que parece: fijado a nivel de sesión alcanzaría a toda
  transacción del sistema, incluidas las `SERIALIZABLE` y el disparador de encadenamiento de la
  bitácora.
- **P2. `SQLState` exacto de cada una de las tres salidas.** Presumiblemente `55P03` para el
  agotamiento de la espera y `23505` para la clave duplicada, pero **solo la segunda está medida**
  por la sonda del 2026-09-22. El diseño debe confirmarlo con sonda propia, porque distinguir las
  tres salidas por texto de mensaje en vez de por código sería frágil y silencioso.
- **P3. Forma del hash de la carga útil canonicalizada.** ADR-0010 exige comparar «sobre un hash de
  la carga útil canonicalizada, no sobre el texto crudo». Sin capa `web` no hay JSON de petición, así
  que el diseño debe decidir qué recibe el componente y quién canonicaliza. El cambio 5 ya entregó
  `CanonicalAuditRowSerializer`: el diseño debe evaluar si se reutiliza o si el problema es
  suficientemente distinto para justificar una segunda canonicalización, con el riesgo de
  divergencia que el propio cambio 5 identificó como su riesgo principal.
- **P4. Cuántas transacciones abre el peor camino.** La salida por clave duplicada aborta la
  transacción, así que repetir la respuesta original exige una transacción nueva. El diseño fija si
  eso es una segunda invocación del componente, una lectura previa que lo evita en el caso común, o
  ambas.
- **P5. Sustrato contable de la demostración.** Actualización acumulativa sobre `legal_name` de
  `organization_institution`, o conteo de filas insertadas en `shared_audit_log`. El diseño elige y
  justifica; lo que esta propuesta fija es que el efecto **debe ser contable**, porque una
  actualización a valor fijo no testifica «un solo efecto».
- **P6. Nombres exactos** del componente, del puerto, del adaptador y del tipo de resultado. En
  inglés, según `CLAUDE.md`.

## Riesgos

| Riesgo | Probabilidad | Mitigación |
|---|---|---|
| **Un `lock_timeout` de ámbito de sesión alcanza transacciones que no lo esperan**, incluidas las `SERIALIZABLE` de ADR-0010 y el disparador de encadenamiento de la bitácora, y produce fallos intermitentes lejos de la idempotencia | **Alta** | Es el riesgo principal. P1 lo declara como decisión de diseño con el ámbito explícito; el diseño debe probar el alcance real, no declararlo |
| Las tres salidas de la solicitud concurrente se distinguen por texto de mensaje en vez de por `SQLState`, y una actualización de PostgreSQL las vuelve indistinguibles en silencio | Media | P2: sonda de diseño que fije los códigos, y detección por `SQLState` como en `TransactionRunner.isRetryable(...)`, nunca por texto |
| La demostración del criterio de salida 4 se escribe sobre una actualización a valor fijo y **pasa sin demostrar nada**, porque esa actualización es idempotente por naturaleza | **Alta si no se corrige** | Corregido en el enfoque, punto 3: el efecto debe ser contable. Es un verde falso de la misma familia que el cambio 5A pagó por aprender |
| Divergencia entre la canonicalización de la carga útil y la ya entregada para la bitácora, o una segunda canonicalización que envejece por separado | Media | P3: el diseño decide reutilizar o duplicar, y si duplica, escribe por qué y cómo se prueba la equivalencia |
| El cambio 9 llega y descubre que no puede purgar porque `docs/03` §6.1 le niega `DELETE` | **Alta, verificada** | Escrito aquí y en el delta de especificación como requisito con dueño nombrado, no como sorpresa. Resolverlo exige un `GRANT` o un ADR, y esta propuesta no inventa ninguno |
| Una escritura financiera originada en el portal resulta no ser idempotente en F3 o F4, porque `confia_portal_app` no tiene privilegio sobre la tabla | Media | Declarado como límite del control con dueño nombrado, verificable por `RolePrivilegeMatrixIT` desde este cambio |
| El pronóstico (1 250 a 2 100 líneas) exige tres pull requests, con historial de subestimación de 1,5× a 3× | Media | Cortes encadenados ya fijados; medir el diff real al cerrar cada corte y partir si supera 800 |
| La suite `*IT.java` crece con pruebas de concurrencia que **esperan de verdad** por diseño, y se acerca al presupuesto de 8 minutos que hoy se mide pero no se exige (W1) | Media | Medir y reportar el tiempo al cerrar cada corte. Un `lock_timeout` corto en las pruebas acota el costo, y esa elección pertenece a P1 |
| La prueba de concurrencia resulta escamosa porque depende de tiempos en vez de sincronización determinista | Media | El cambio 5 ya estableció el patrón con `CyclicBarrier` en `TransactionRunnerRetryIT`; el diseño lo reutiliza y no introduce esperas por reloj |
| Se adelanta superficie HTTP para «completar» el entregable 6 y se arrastra el pool de producción entero a F0 | Baja | Exclusión declarada con dueño nombrado (cambio 7) y respaldo de ADR-0019 |
| Una migración aplicada se edita después | Baja hoy | No hay entorno desplegado: la base vive solo en contenedores efímeros. La regla entra en vigor con el cambio 11 |

## Plan de reversión

Sigue siendo barata por la misma razón que en los cambios 4 y 5: **no existe ningún entorno
desplegado ni ningún dato real**. La base de datos vive exclusivamente en contenedores de prueba
efímeros.

1. **Revertir el commit de fusión** del corte afectado, o cerrar su pull request. Con los cortes
   encadenados, revertir uno obliga a revertir los posteriores que dependan de él.
2. **C1 se puede revertir sola; C2 no se revierte sin C3.** Si desaparece la tabla, el adaptador jOOQ
   deja de compilar, porque el código generado se produce desde las migraciones y una consulta contra
   una tabla inexistente **rompe la generación o la compilación**, no la ejecución (requisito
   «Generación del código de jOOQ desde las migraciones de Flyway»). Es un rojo inmediato y ruidoso.
3. **La corrección del Javadoc de `TransactionRunner` se revierte con su corte.** Es una línea de
   documentación y no arrastra nada.
4. **La nota editorial sobre ADR-0010 se revierte por separado y sin consecuencia técnica.** Si el
   propietario prefiere no tocar ningún ADR, se omite desde el principio: nada del resto depende de
   ella, exactamente como ocurrió con la decisión D2 del cambio 5.
5. No hay dato que migrar hacia atrás ni migración de reverso que escribir: el siguiente
   `./mvnw verify` levanta un contenedor nuevo y aplica lo que quede en `db/migration`.
6. Si la espera acotada resulta inviable —por ejemplo, si el `SQLState` no permite distinguir las
   tres salidas—, la reversión parcial es retirar el `lock_timeout` y quedarse con el bloqueo sin
   límite, que **sigue garantizando un solo efecto**, que es lo esencial, a costa del agotamiento del
   pool bajo carga. Esa salida se documenta como degradación conocida y se eleva al propietario; **no**
   se sustituye por confirmar el marcador primero, porque esa variante ya fue descartada por romper
   la atomicidad y dejar marcadores huérfanos sin mecanismo de reconciliación hasta el cambio 9.
7. Una puerta que bloquee por error **no se desactiva con una bandera** (ADR-0008): se revierte el
   commit que la introdujo o se corrige con un ADR.

**Punto de no retorno práctico:** el cambio 11, cuando exista el primer entorno desplegado. Desde
entonces, una migración aplicada no se edita.

## Dependencias

- **Cambio 5, `audit-log-and-transaction-runner`**, fusionado y archivado: `TransactionRunner`,
  `SecurityContext`, `IsolationLevel`, `CommittingPostgresIntegrationTest`, el paquete
  `com.confia.shared.infrastructure` con el precedente de adaptador, `RolePrivilegeMatrixIT`,
  `MultiTenantSchemaIT` y la regla R3 con su mitad positiva.
- **Cambio 4**, archivado: `organization_institution`, que es el sustrato de demostración.
- JDK 25 y **Docker** en local y en integración continua, ya exigidos por la construcción desde A1.
- **Dependen de este cambio:** el **9** (db-scheduler, que hereda la purga y su tensión de
  privilegios) y las capacidades financieras de **F3**, que consumen el control por endpoint.

## Criterios de éxito

- [ ] `./mvnw verify` en `apps/api` termina en verde con JDK 25 y Docker, en local y en integración
      continua, incluida la puerta de mutación de `main`.
- [ ] `shared_idempotency_key` existe con clave primaria natural compuesta
      `(institution_id, endpoint, idempotency_key)`, con `ENABLE` y `FORCE ROW LEVEL SECURITY` y
      política por institución.
- [ ] Las puertas genéricas de `MultiTenantSchemaIT` pasan sobre esa tabla **sin excepción ni lista
      de exclusión**, incluida la de restricciones únicas con discriminador de institución.
- [ ] `RolePrivilegeMatrixIT` confirma los privilegios de `docs/03` §6.1 sobre la tabla para los
      cinco roles, sin uno más ni uno menos, e incluye explícitamente que `confia_portal_app` **no
      tiene ninguno**.
- [ ] Existe **un solo** punto que abre transacciones y sigue siendo el de `shared/security`: la
      regla R3 pasa en sus dos mitades y el componente nuevo no abre ninguna transacción propia.
- [ ] Una prueba de integración demuestra que el marcador y el efecto de negocio se escriben
      **dentro de la misma transacción**: si el efecto falla, el marcador no sobrevive.
- [ ] **Criterio de salida 4 de F0, mitad «un solo efecto»:** dos solicitudes con la misma clave
      producen exactamente un efecto, verificado sobre un efecto **contable**, no sobre una
      actualización idempotente por naturaleza.
- [ ] **Criterio de salida 4 de F0, mitad «la misma respuesta»:** la segunda solicitud devuelve la
      respuesta almacenada de la primera, idéntica, sin reejecutar el caso de uso.
- [ ] Una prueba de concurrencia con transacciones reales y sincronización determinista —nunca por
      reloj— demuestra que la segunda solicitud espera un tiempo **acotado** y recibe un error
      tratable y distinguible, en vez de esperar sin límite.
- [ ] Una prueba demuestra que el reintento acotado de `TransactionRunner` **no** reintenta ni el
      agotamiento de la espera ni el choque por clave duplicada.
- [ ] La misma clave con carga útil distinta se rechaza, sin ejecutar nada, comparando sobre el hash
      de la carga canonicalizada y no sobre el texto crudo.
- [ ] Una clave con `expires_at` vencido se reutiliza **por actualización de la fila existente**, y
      una prueba demuestra que la fila vieja seguía ahí, porque no hay purga hasta el cambio 9.
- [ ] Las tres exclusiones con destino nombrado —superficie HTTP, purga física y acceso del portal—
      viven **dentro de bloques `### Requisito:`**, nombran su cambio dueño y tienen al menos un
      escenario que dejará de ser cierto cuando la brecha se cierre.
- [ ] ADR-0010 lleva su nota editorial fechada, con el cuerpo de la decisión **sin reescribir**, y
      esa nota cubre las tres discrepancias: nombre, clave primaria y narrativa de concurrencia.
- [ ] El Javadoc de `TransactionRunner` ya no afirma que el cambio 6 registra el bean; nombra al
      dueño real.
- [ ] `docs/09-roadmap-y-fases.md` refleja el criterio de salida 4 como cerrado y el entregable 6 con
      su mitad de cabecera obligatoria diferida al cambio 7, nombrada.
- [ ] El tiempo de la suite `*IT.java` se **mide y se reporta** al cerrar cada corte, no se estima.

## Decisiones que confirma el propietario al aprobar

El modo de ejecución es automático: estas decisiones quedan registradas en lugar de preguntarse en
conversación. El orquestador las presenta.

> **Las cuatro decisiones quedaron resueltas el 2026-09-22.** Se conservan abajo con su
> razonamiento original, porque explican por qué se decidió así.
>
> - **D1: sí, nota editorial fechada.** Decisión del orquestador, por el precedente del cambio 5. Con
>   el matiz que esta propuesta señala y que se adopta: la nota **remite**, pero lo que **obliga** es
>   el requisito con escenario ejecutable del delta. La corrección de concurrencia no es editorial,
>   porque una sonda refutó lo que el ADR narraba.
> - **D2: no se registra el bean, y se corrige el Javadoc.** Decisión del propietario. Registrarlo
>   arrastraría a F0 la fuente de datos de producción, su pool y su configuración por perfil, para un
>   componente cuyo único consumidor en esta fase sería una prueba; los tres puntos de entrada
>   excluyen `DataSourceAutoConfiguration` con una condición de retirada escrita. El Javadoc pasa a
>   nombrar al dueño real en vez de a «el primer cambio que lo consuma».
> - **D3: sí, la cabecera obligatoria se difiere al cambio 7.** Decisión del orquestador. Es donde
>   nace la capa web; `docs/09` se actualiza para que la mitad diferida del entregable 6 no caduque
>   en silencio.
> - **D4: propuesta aprobada** por el propietario, con D2 resuelta.
>
> **Estrategia de entrega: `auto-chain`**, no `single-pr`. Ver la sección correspondiente de
> `exploration.md`.

- **D1. Nota editorial fechada sobre ADR-0010, sin reescribir su cuerpo.** Recomendación: sí, por el
  precedente del cambio 5 (decisión D2 sobre ADR-0003). Alternativa: no tocar ningún ADR, con lo que
  el registro histórico conserva un esquema que rompe dos puertas y una narrativa de concurrencia
  refutada, y el lector solo encuentra la corrección en la especificación. Si el propietario prefiere
  no tocar el ADR, la propuesta se omite sin consecuencia técnica: nada depende de la nota.
- **D2. No registrar `TransactionRunner` como bean en este cambio, y corregir su Javadoc.**
  Recomendación: sí, por la evidencia de «Registro del bean» —los tres puntos de entrada excluyen
  `DataSourceAutoConfiguration` y ninguna propiedad `spring.datasource.*` existe— y por ADR-0019.
  Alternativa: registrarlo aquí, lo que arrastra a F0 la fuente de datos de producción, su pool y su
  configuración por perfil, para un componente cuyo único consumidor en F0 es una prueba.
- **D3. Diferir al cambio 7 la mitad de «cabecera obligatoria» del entregable 6 de F0.**
  Recomendación: sí. El criterio de salida 4, que es la puerta, no la menciona; y no existe capa
  `web` donde exigirla. Alternativa: adelantar un endpoint mínimo, que rompe el límite de fase y
  contradice ADR-0019.
- **D4. Aprobación de la propuesta completa** antes de especificar, diseñar y planificar tareas
  (`openspec/config.yaml`, `rules.proposal`).

**No forman parte de estas decisiones**, por estar ya fijadas: el comportamiento ante solicitud
concurrente —atomicidad con espera acotada, resuelto por el propietario el 2026-09-22—, el
presupuesto de revisión de 800 líneas de cambio efectivo, la entrega en cortes encadenados, el modo
de ejecución automático con TDD estricto, y la exclusión de las deudas W1 y W2 con destino en el
cambio 11.
