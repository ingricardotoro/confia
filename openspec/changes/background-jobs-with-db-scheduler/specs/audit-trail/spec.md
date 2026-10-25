# Delta para Bitácora de auditoría encadenada

- **Estado:** pendiente de aprobación del propietario del producto
- **Cambio:** `background-jobs-with-db-scheduler` (F0, cambio 9)
- **Propuesta:** `proposal.md`, aprobada el 2026-10-24; P1 fija que entran las cadencias diaria y semanal del
  verificador (deuda *f*) y que la horaria sale al cambio nuevo `audit-chain-hourly-anchor-verification`

**Convenciones de este delta.**

- Los escenarios nuevos se numeran `AT07` en adelante, a continuación del máximo de la especificación vigente
  (`AT06`); `AT07` a `AT22` son los de la primera redacción y `AT23` en adelante los añadidos al conciliar la
  especificación con el diseño (2026-10-24). Los escenarios de la especificación vigente que no llevaban número y se
  conservan no lo llevan.
- El requisito «Límite declarado del control mientras no exista ancla externa (cambio 11)» NO cambia: las
  cadencias de este delta recalculan la cadena y por lo tanto comparten su límite; la comparación contra el ancla
  externa es de la cadencia horaria, que no entra aquí.
- «Verificación fallida» es el resultado de una verificación que identificó una fila divergente. Es un resultado
  de negocio, distinto de un fallo técnico de la tarea (excepción, base de datos inaccesible).
- **Ventana diaria (decisión D-N2 del propietario, 2026-10-24):** «el último mes» es el **mes de calendario**, no
  una ventana móvil de 30 días: la verificación diaria cubre desde las 00:00 del primer día del mes anterior,
  en la zona horaria de la institución (`America/Tegucigalpa`), hasta el momento de la ejecución. Una fila entra en
  la ventana si su `occurred_at` es mayor o igual que ese inicio. Por tanto la ventana mide entre unos 31 y unos 62
  días según el día del mes.
- El diseño fija la forma del registro del resultado (acciones `shared.audit.chain_verified` y
  `shared.audit.chain_verification_failed`, ver los escenarios).

## ADDED Requirements

### Requisito: La verificación diaria cubre desde el primer día del mes anterior en cada institución, anclada en el hash previo almacenado de la primera fila de la ventana

El sistema DEBE ofrecer una verificación que recalcule `row_hash` solo para las filas de una institución cuyo
`occurred_at` sea mayor o igual que un instante de inicio (`since`), tomando como punto de partida el `prev_hash`
**almacenado** de la primera fila de la ventana, y que compare cada fila recalculada contra la almacenada, en el
orden de la cadena. Como esa primera fila no se compara contra la anterior, el vínculo en la frontera de la ventana
NO es objeto de esta verificación (lo cubre la cadencia semanal, que sale del génesis). Como la verificación de
cadena completa, DEBE identificar con exactitud la primera fila divergente por su `institution_id` y su `id`, sin
reportar divergencias derivadas, y NO DEBE ver las filas de otra institución. Sin filas en la ventana, el
resultado es «vacío». Una alteración anterior a la ventana NO es objeto de esta verificación. El inicio de la
cadencia diaria es, por decisión del propietario, las 00:00 del primer día del mes anterior en
`America/Tegucigalpa`.

#### Escenario: [AT07] Una cadena íntegra en la ventana reporta integridad y cuántas filas verificó

- **DADO** una institución con 40 filas, 15 de ellas dentro de la ventana (desde el 2026-09-01 00:00 en
  Tegucigalpa, con la verificación invocada el 2026-10-25), todas producidas por el disparador sin alteración
- **CUANDO** se invoca la verificación de ventana
- **ENTONCES** reporta la cadena íntegra y que verificó 15 filas

#### Escenario: [AT08] Una fila alterada dentro de la ventana se identifica con exactitud

- **DADO** la misma institución con una fila intermedia de la ventana alterada con un rol `SUPERUSER`, sin
  recalcular ningún hash
- **CUANDO** se invoca la verificación de ventana
- **ENTONCES** identifica exactamente esa fila, por su `institution_id` y su `id`, como la primera divergente

#### Escenario: [AT09] La verificación de ventana no ve una alteración anterior a la ventana

- **DADO** una fila de hace 60 días (anterior al primer día del mes anterior) alterada con un rol `SUPERUSER`,
  sin recalcular ningún hash
- **CUANDO** se invoca la verificación de ventana y, aparte, la verificación de cadena completa
- **ENTONCES** la de ventana reporta integridad y la completa identifica esa fila

#### Escenario: [AT23] El inicio de la ventana es el primer día del mes anterior, no 30 días móviles

- **DADO** la verificación diaria que corre el 2026-10-25 a las 03:00 en Tegucigalpa, y filas con `occurred_at` el
  2026-08-31 23:59:59, el 2026-09-01 00:00:00 y el 2026-09-10 (hace 45 días), todas en la hora de Tegucigalpa
- **CUANDO** se calcula la ventana y se verifica
- **ENTONCES** el inicio es el 2026-09-01 00:00 en Tegucigalpa (06:00 UTC)
- **Y** la fila del 2026-08-31 queda fuera, y la del 2026-09-01 00:00:00 y la del 2026-09-10 entran
- **Y** una alteración de la fila del 2026-09-10 (hace 45 días, fuera de una ventana móvil de 30 días) se reporta

#### Escenario: [AT24] La ventana se recalcula cada día con el mes de calendario

- **DADO** la verificación diaria que corre el 2026-10-01 a las 03:00 y otra el 2026-10-31 a las 03:00
- **CUANDO** se calcula el inicio de cada una
- **ENTONCES** ambas empiezan el 2026-09-01 00:00 en Tegucigalpa
- **Y** el 2026-11-01 empieza el 2026-10-01 00:00, y el 2027-01-15 empieza el 2026-12-01 00:00 (cruce de año)

#### Escenario: [AT25] El vínculo en la frontera no se verifica y la verificación parte del hash previo almacenado

- **DADO** una institución con la primera fila de la ventana cuyo `prev_hash` almacenado fue alterado, sin tocar
  la fila anterior
- **CUANDO** se invoca la verificación de ventana y, aparte, la de cadena completa
- **ENTONCES** la de ventana reporta integridad (parte del `prev_hash` almacenado de esa fila) y la completa
  identifica la fila
- **Y** la cadencia semanal es la que cubre ese vínculo

#### Escenario: [AT26] Una ventana sin filas devuelve «vacío»

- **DADO** una institución sin filas con `occurred_at` mayor o igual que el inicio de la ventana
- **CUANDO** se invoca la verificación de ventana
- **ENTONCES** el resultado es «vacío», sin error

### Requisito: Cada lote de purga escribe un asiento de auditoría por categoría con el conteo y nunca los datos

El sistema DEBE escribir, por cada **lote** de una purga (hasta 5 000 filas por lote y hasta 200 lotes por
institución y por ocurrencia) y por cada categoría purgada (tokens de recuperación, familias y tokens de refresco,
vaciado del sucesor cifrado, claves de idempotencia, tablas de retroceso), un asiento de auditoría de la
institución con el actor `system` y la etiqueta `retention-purge`, cuya entidad es la ejecución (`shared.job_run`)
y cuyo identificador de solicitud es el identificador de la ocurrencia. Su detalle DEBE contener solo la categoría
(la acción), los conteos (en la de refresco, de familias y de tokens), el instante de corte y el número de lote.
Los asientos de las acciones son `identity.password_reset_token.purged`, `identity.refresh_token.successors_cleared`,
`identity.refresh_token.purged`, `shared.idempotency_key.purged`, `identity.login_backoff.purged` e
`identity.mfa_totp_backoff.purged`. El asiento DEBE escribirse también cuando el conteo es cero, de modo que una
purga que no actuó sea visible (cada lote, incluido uno vacío, deja su asiento). El asiento NO DEBE contener
identificadores de filas, correos, IP, huellas ni ningún dato de las filas purgadas. El asiento DEBE escribirse en
la misma transacción que su lote: si el lote se revierte, el asiento no existe. El detalle DEBE construirse con el
constructor tipado de `com.confia.shared.audit`.

#### Escenario: [AT18] Un asiento por categoría con el conteo

- **DADO** una ejecución de la purga de recuperación que elimina 3 tokens de la institución `INS-1`
- **CUANDO** termina y se confirma
- **ENTONCES** existe exactamente un asiento de `INS-1` (un solo lote), con actor `system`, que nombra la categoría
  y el conteo 3, el instante de corte y el lote 1
- **Y** el asiento lleva la etiqueta `retention-purge`, la entidad `shared.job_run` y como identificador de solicitud
  el de la ocurrencia

#### Escenario: [AT19] Una purga que no encontró filas también deja su asiento

- **DADO** una institución sin filas purgables
- **CUANDO** corre cada purga
- **ENTONCES** existe un asiento por categoría con conteo 0 (un lote vacío)

#### Escenario: [AT20] El asiento no contiene datos de las filas purgadas

- **DADO** una purga de familias de refresco sobre filas con IP `203.0.113.7`, huella de agente de usuario
  distintiva y correos de cuenta
- **CUANDO** se lee el detalle del asiento
- **ENTONCES** no contiene ninguno de esos valores ni ningún identificador de fila, solo la categoría, los
  conteos de familias y de tokens, el instante de corte y el número de lote

#### Escenario: [AT21] El asiento y la purga son atómicos

- **DADO** una purga que lanza una excepción después de borrar filas y de escribir su asiento
- **CUANDO** la transacción se revierte
- **ENTONCES** las filas siguen existiendo y no existe el asiento

#### Escenario: [AT27] Un asiento por lote, con su número de lote

- **DADO** 12 000 tokens de recuperación purgables de `INS-1`
- **CUANDO** corre la purga
- **ENTONCES** existen tres asientos de la misma ocurrencia (mismo identificador de solicitud), con conteos 5 000, 5 000
  y 2 000 y números de lote 1, 2 y 3
- **Y** cada asiento se confirmó en la misma transacción que su lote

#### Escenario: [AT28] El vaciado del sucesor deja su propio asiento

- **DADO** la purga de refresco que vacía 4 sucesores residuales de `INS-1`
- **CUANDO** corre
- **ENTONCES** existe un asiento `identity.refresh_token.successors_cleared` con el conteo 4 y el instante de corte, escrito
  en la misma transacción que el vaciado, separado de los asientos `identity.refresh_token.purged` de los lotes de
  familias

#### Escenario: [AT29] Agotar los 200 lotes deja 200 asientos y una advertencia

- **DADO** una institución con más de un millón de filas purgables de una categoría
- **CUANDO** corre la purga
- **ENTONCES** existen exactamente 200 asientos de lote de esa categoría y se escribe la advertencia
  `retention_purge_backlog`

#### Escenario: [AT22] La cadena sigue verificando tras los asientos de purga

- **DADO** una institución con asientos de purga escritos por el trabajador
- **CUANDO** se invoca el verificador de cadena
- **ENTONCES** reporta la cadena íntegra

## RENAMED Requirements

### Requisito: Alcance de la verificación de cadena sin programación recurrente (cambio 9) → Cadencias diaria y semanal del verificador de cadena (cambio 9); la horaria queda transferida

(Reason: el cambio 9 entrega la programación recurrente de las cadencias diaria y semanal; la horaria pasa por la
decisión P1 al cambio nuevo `audit-chain-hourly-anchor-verification`.)
(Migration: la prueba que comprobaba la ausencia de toda cadencia, `AuditScopeExclusionInventoryTest` en la que
no admite dependencias de programación y en la que no admite `scheduled_tasks` en las migraciones, se invierte
con su control negativo; la ausencia de la cadencia horaria se conserva en [AT17].)

## MODIFIED Requirements

### Requisito: Cadencias diaria y semanal del verificador de cadena (cambio 9); la horaria queda transferida

El sistema DEBE ejecutar el verificador de cadena como tareas recurrentes del trabajador (ADR-0016) en las
cadencias de `docs/03-seguridad.md` §12.4: **diaria** (03:00), por cada institución configurada, desde las 00:00
del primer día del mes anterior en `America/Tegucigalpa` hasta el momento de la ejecución (decisión D-N2),
recalculando cada `row_hash`, y **semanal** (domingo, 03:30), por cada institución configurada, sobre la cadena
completa desde su propio registro génesis. Cada ejecución DEBE correr con el actor `system` y el contexto de la
institución. Una discrepancia DEBE registrarse como verificación fallida, identificando la institución y la fila, y
DEBE generar una alerta de severidad S1 según `docs/03-seguridad.md` §12.4; el sistema NO DEBE intentar reparar la
cadena ni modificar ninguna fila. Una verificación íntegra DEBE registrar el número de filas verificadas
(`confia_audit_chain_verified_rows`) y su resultado.

El resultado de cada institución DEBE quedar registrado, en una transacción separada que se escribe después del
recorrido, así: **íntegra**, un asiento `shared.audit.chain_verified` con el alcance (`daily` o `weekly`) y las filas
verificadas, y un evento `INFO` `audit_chain_verified`; **vacía** (ninguna fila en el alcance), el mismo asiento con
cero filas y una advertencia `audit_chain_empty_for_configured_institution`, porque una institución real siempre
tiene asientos y el resultado suele indicar un UUID mal configurado; **divergente**, un asiento
`shared.audit.chain_verification_failed` con el alcance, las filas verificadas, el identificador de la primera fila
divergente y la descripción de la divergencia, resultado `failure`, y un evento `ERROR` `audit_chain_broken` (alerta
S1). Una verificación fallida es un resultado de negocio: tras registrarlo la ocurrencia termina como fallida y
visible, pero NO se reintenta. Un fallo técnico de la tarea (base de datos inaccesible) tampoco se reintenta: como
toda tarea recurrente, se reprograma a su próxima ocurrencia y queda visible como fallida hasta su próximo éxito.
La verificación sigue siendo invocable directamente, sin tarea programada. La cadencia
**horaria**, que compara el último `row_hash` contra el ancla externa, queda transferida al cambio nuevo
`audit-chain-hourly-anchor-verification` (decisión P1 del propietario del 2026-10-24; depende de los cambios 9 y
11) y este cambio NO DEBE implementarla.
(Previously: «Alcance de la verificación de cadena sin programación recurrente (cambio 9)»: el verificador era
solo una rutina invocable y el cambio 9 era dueño de programar sus tres cadencias.)

#### Escenario: El verificador se invoca directamente, sin infraestructura de programación

- **DADO** el verificador de cadena, en un contexto de prueba sin ninguna tarea programada
- **CUANDO** se invoca directamente, por ejemplo desde una prueba de integración
- **ENTONCES** produce su resultado de verificación —cadena íntegra o fila divergente identificada— sin
  depender de ninguna tarea programada

#### Escenario: [AT10] La cadencia diaria corre por cada institución configurada desde el primer día del mes anterior

- **DADO** el trabajador configurado con `INS-1` e `INS-2`, cada una con filas de hace 10 y de hace 60 días, y el
  ciclo diario del 2026-10-25
- **CUANDO** vence el ciclo diario
- **ENTONCES** hay una ejecución de la verificación por institución, con el actor `system` y su contexto
- **Y** cada una verificó solo las filas desde el 2026-09-01 00:00 en Tegucigalpa de su institución y registró
  cuántas

#### Escenario: [AT11] La cadencia semanal verifica la cadena completa desde el génesis

- **DADO** una institución configurada con una fila alterada de hace 60 días, sin recalcular ningún hash
- **CUANDO** vence el ciclo semanal
- **ENTONCES** la verificación recorre la cadena desde el registro génesis y reporta verificación fallida
  identificando esa fila
- **Y** el ciclo diario del mismo día, con la misma alteración, no la reporta

#### Escenario: [AT12] Una discrepancia genera un resultado fallido y una alerta S1, sin reparar nada

- **DADO** una institución configurada con una fila de los últimos 10 días alterada con un rol `SUPERUSER`, sin
  recalcular ningún hash
- **CUANDO** corre la cadencia diaria
- **ENTONCES** se registra una verificación fallida (asiento `shared.audit.chain_verification_failed`, resultado
  `failure`) con la institución, el alcance, las filas verificadas y el `id` de esa fila, y una alerta de severidad
  S1 (evento `ERROR` `audit_chain_broken`)
- **Y** ninguna fila de `shared_audit_log` cambió como consecuencia de la verificación (salvo el asiento del propio
  resultado), y la fila alterada se conserva como evidencia
- **Y** la ocurrencia queda como fallida y visible, y no se reintenta como fallo técnico

#### Escenario: [AT13] Una cadena íntegra registra un resultado correcto

- **DADO** una institución configurada con una cadena íntegra
- **CUANDO** corre cualquiera de las dos cadencias
- **ENTONCES** se registra el resultado íntegro y el número de filas verificadas (asiento
  `shared.audit.chain_verified` con el alcance y el evento `INFO` `audit_chain_verified`), sin alerta

#### Escenario: [AT30] Una institución configurada sin asientos deja una advertencia

- **DADO** una institución configurada que no tiene ninguna fila de auditoría en el alcance
- **CUANDO** corre cualquiera de las dos cadencias
- **ENTONCES** se registra el asiento `shared.audit.chain_verified` con cero filas verificadas
- **Y** se escribe la advertencia `audit_chain_empty_for_configured_institution`, sin alerta S1

#### Escenario: [AT31] El resultado se escribe después del recorrido en una transacción separada

- **DADO** una verificación de una institución con la cadena alterada
- **CUANDO** termina el recorrido y se escribe su resultado
- **ENTONCES** el asiento del resultado se confirma en una transacción distinta de la del recorrido, y su alcance es
  `daily` o `weekly` según la cadencia

#### Escenario: [AT14] Una alteración en una institución no aparece en la ejecución de otra

- **DADO** `INS-1` e `INS-2` configuradas, con una fila alterada solo en `INS-2`
- **CUANDO** corre la cadencia diaria
- **ENTONCES** la ejecución de `INS-1` reporta integridad y la de `INS-2` reporta verificación fallida

#### Escenario: [AT15] Una institución no configurada no se verifica

- **DADO** `INS-3` con una fila alterada y no configurada en el trabajador
- **CUANDO** corre cualquier cadencia
- **ENTONCES** ninguna ejecución usa el contexto de `INS-3`

#### Escenario: [AT16] Ninguna ocurrencia se reintenta: el fallo técnico no deja resultado y el de integridad sí

- **DADO** una ejecución cuya base de datos falla de forma transitoria y otra cuya cadena está alterada
- **CUANDO** corre cada una
- **ENTONCES** ninguna se reintenta: ambas ocurrencias quedan fallidas y visibles y la tarea sigue programada para
  su próxima ocurrencia
- **Y** la segunda registra además el asiento `shared.audit.chain_verification_failed` y la alerta S1, y la primera
  no registra ningún asiento de resultado

#### Escenario: [AT17] No existe la cadencia horaria — cierto hoy, falso al cerrar `audit-chain-hourly-anchor-verification`

- **DADO** el registro de tipos de tarea del trabajador
- **CUANDO** se busca una tarea que compare el último `row_hash` con un ancla externa o que corra cada hora
- **ENTONCES** ninguna existe; este resultado deja de ser cierto el día que `audit-chain-hourly-anchor-verification`
  registre esa tarea

