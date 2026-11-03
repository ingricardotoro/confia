# Capacidad: Bitácora de auditoría encadenada

- **Identificador:** audit-trail
- **Estado:** Borrador
- **Fase:** F0

## Propósito

La bitácora de auditoría de solo inserción, encadenada por hash, es lo que convierte un registro de
acciones en evidencia. Sin ella, un administrador con acceso al motor puede alterar el rastro de una
operación financiera sin dejar huella, y entonces ningún reporte del sistema tiene valor probatorio
(`docs/03-seguridad.md` §12; actor 4 del modelado de amenazas).

Esta capacidad cubre la cadena por institución y su registro génesis, el cálculo del hash en el
motor y no en la aplicación, la inmutabilidad frente a todo rol —incluido el propietario del
esquema—, los permisos y el aislamiento por fila, el contrato observable del verificador de cadena,
y la reproducibilidad de la serialización canónica entre sus dos implementaciones.

Cubre además, de forma deliberada, **el límite declarado del control**: mientras no exista el ancla
externa del cambio 11, un actor con acceso administrativo que recalcule la cadena entera no deja
divergencia detectable. Ese límite se declara como requisito con escenarios ejecutables, no como
nota al pie, para que nadie lea esta bitácora como inviolable mientras no lo sea.

## Fuera de alcance

Las tres exclusiones con destino nombrado —el ancla externa del cambio 11, la programación
recurrente del verificador del cambio 9, y el filtro de lectura por permiso `audit:read` de los
cambios 7 y 8— **no se listan aquí**. Cada una vive dentro de su propio bloque de requisito, con un
escenario cierto hoy y falso el día que su cambio dueño la cierre. Esa decisión es deliberada: en la
parte A del cambio 5, un punto de «Fuera de alcance» dejó de ser cierto con su propia entrega y hubo
que retirarlo a mano durante el archivado, porque vivía fuera de todo bloque de requisito y el
mecanismo de deltas no lo alcanzaba.

## Requisitos

### Requisito: Cadena de hash por institución con registro génesis

El sistema DEBE mantener, en la tabla `shared_audit_log`, una cadena de encadenamiento por hash
independiente para cada institución. Cada institución DEBE tener su propio registro génesis, cuyo
`prev_hash` son 32 bytes de ceros, y toda fila posterior de esa misma institución DEBE encadenar su
`prev_hash` con el `row_hash` de la fila inmediatamente anterior **de la misma institución**. La
clave primaria de la tabla DEBE ser compuesta, `(institution_id, id)`, porque ningún rol de
PostgreSQL tiene el atributo `BYPASSRLS` (`docs/03-seguridad.md` §6.1) y un verificador que corre
con el contexto de una institución no puede leer ni recalcular una cadena global.

El conjunto de campos firmados DEBE incluir **`actor_label`, `user_agent` y `trace_id`**, además de
los que enumera `docs/03-seguridad.md` §12.1, por decisión del propietario del 2026-09-21. Un campo
que no entra a la preimagen puede alterarse sin que el verificador lo detecte, y `actor_label` es la
etiqueta legible de quién ejecutó la acción: a la vez el campo que un atacante querría cambiar y el
primero que leería un auditor externo. Ningún campo de la fila que tenga valor probatorio DEBE
quedar fuera de la preimagen.

#### Escenario: Primera fila de una institución es su registro génesis

- **DADO** que una institución no tiene todavía ninguna fila en `shared_audit_log`
- **CUANDO** se inserta la primera fila de esa institución
- **ENTONCES** esa fila queda registrada con `prev_hash` de 32 bytes de ceros

#### Escenario: Segunda fila encadena con la anterior de la misma institución

- **DADO** una institución con al menos una fila ya confirmada en `shared_audit_log`
- **CUANDO** se inserta una segunda fila para esa misma institución
- **ENTONCES** el `prev_hash` de la fila nueva es exactamente igual al `row_hash` de la fila
  anterior de esa institución

#### Escenario: Dos instituciones mantienen cadenas independientes

- **DADO** dos instituciones distintas, cada una con su propio registro génesis y filas
  confirmadas
- **CUANDO** se inserta una fila nueva para la primera institución
- **ENTONCES** el `prev_hash` de esa fila nueva se calcula solo a partir de la última fila de la
  primera institución, sin verse afectado por ninguna fila de la segunda institución

#### Escenario: Inserciones concurrentes de dos transacciones confirmadas encadenan sin condición de carrera

- **DADO** una institución con una fila ya confirmada en `shared_audit_log`
- **CUANDO** dos transacciones confirmadas insertan, a la vez, una fila cada una para esa misma
  institución
- **ENTONCES** ambas quedan encadenadas de forma consistente y secuencial, sin que ninguna de las
  dos calcule un `prev_hash` que ignore a la otra ni produzca una bifurcación de la cadena

### Requisito: El encadenamiento se calcula en el disparador del motor, no en la aplicación

El sistema DEBE calcular `prev_hash` y `row_hash` dentro de un disparador `BEFORE INSERT` de
PostgreSQL sobre `shared_audit_log`. La aplicación NO DEBE calcular ni pasar ninguno de los dos
valores en la sentencia de inserción, para que una escritura por cualquier vía —incluida una
inserción SQL directa que no pase por ningún caso de uso de la aplicación— quede encadenada.

#### Escenario: La aplicación inserta sin calcular el hash

- **DADO** un caso de uso que inserta una fila en `shared_audit_log` sin construir `prev_hash` ni
  `row_hash`
- **CUANDO** la inserción se confirma
- **ENTONCES** la fila almacenada tiene ambos valores calculados por el disparador, no por ningún
  valor que el caso de uso haya podido pasar

#### Escenario: Una inserción SQL directa, fuera de cualquier caso de uso, también queda encadenada

- **DADO** una sentencia SQL de inserción ejecutada directamente contra `shared_audit_log`, sin
  pasar por ningún caso de uso de la aplicación
- **CUANDO** esa sentencia se ejecuta con el rol `confia_admin_app`
- **ENTONCES** el disparador calcula `prev_hash` y `row_hash` igual que si la inserción hubiera
  venido de un caso de uso, y la fila queda encadenada con la anterior de su institución

### Requisito: Inmutabilidad de `shared_audit_log`, incluso para el propietario del esquema

El sistema DEBE rechazar, a nivel de motor, toda sentencia `UPDATE`, `DELETE` y `TRUNCATE` sobre
`shared_audit_log`, **incluso cuando la ejecuta el propietario del esquema** (`confia_owner`). Esta
es una segunda barrera independiente de los permisos de PostgreSQL: los permisos niegan la
capacidad a los roles de aplicación, pero el propietario del esquema conserva esa capacidad por
definición, así que el rechazo DEBE ocurrir por disparador, no solo por `GRANT`/`REVOKE`.

#### Escenario: `UPDATE` rechazado con el rol de aplicación

- **DADO** una fila ya confirmada en `shared_audit_log`
- **CUANDO** `confia_admin_app` intenta un `UPDATE` sobre esa fila
- **ENTONCES** el motor rechaza la operación

#### Escenario: `DELETE` rechazado con el rol de aplicación

- **DADO** una fila ya confirmada en `shared_audit_log`
- **CUANDO** `confia_admin_app` intenta un `DELETE` sobre esa fila
- **ENTONCES** el motor rechaza la operación

#### Escenario: `UPDATE` y `DELETE` rechazados incluso para el propietario del esquema

- **DADO** una fila ya confirmada en `shared_audit_log`
- **CUANDO** `confia_owner`, el propietario del esquema, intenta un `UPDATE` o un `DELETE` sobre
  esa fila
- **ENTONCES** el disparador rechaza ambas operaciones, a pesar de que el propietario del esquema
  no está sujeto a `GRANT`/`REVOKE`

#### Escenario: `TRUNCATE` rechazado, incluso para el propietario del esquema

- **DADO** `shared_audit_log` con filas confirmadas
- **CUANDO** `confia_owner` intenta `TRUNCATE` sobre la tabla
- **ENTONCES** el disparador rechaza la operación

### Requisito: Permisos de acceso a `shared_audit_log` por rol de base de datos

El sistema DEBE conceder, sobre `shared_audit_log`: a `confia_admin_app`, exactamente `SELECT` e
`INSERT`, sin `UPDATE` ni `DELETE`; a `confia_portal_app`, ningún privilegio; a `confia_readonly`,
solo `SELECT`, sujeto a la misma política de fila por institución que los demás roles. El sistema
DEBE partir de `REVOKE ALL ... FROM PUBLIC` antes de conceder ningún privilegio explícito
(`docs/03-seguridad.md` §6.1 y §12.3).

#### Escenario: `confia_admin_app` puede leer e insertar, pero no actualizar ni borrar

- **DADO** el rol `confia_admin_app` conectado con el contexto de una institución
- **CUANDO** intenta `SELECT` e `INSERT` sobre `shared_audit_log`, y después intenta `UPDATE` y
  `DELETE`
- **ENTONCES** `SELECT` e `INSERT` se ejecutan, y `UPDATE` y `DELETE` son rechazados por permiso
  insuficiente

#### Escenario: `confia_portal_app` no tiene ningún privilegio

- **DADO** el rol `confia_portal_app`
- **CUANDO** intenta `SELECT` sobre `shared_audit_log`
- **ENTONCES** el motor rechaza la operación por permiso insuficiente, porque no se emitió ningún
  `GRANT` para ese rol

#### Escenario: `PUBLIC` no tiene ningún privilegio de partida

- **DADO** cualquier rol que no sea uno de los cinco roles de aplicación de
  `docs/03-seguridad.md` §6.1
- **CUANDO** ese rol intenta cualquier operación sobre `shared_audit_log`
- **ENTONCES** el motor la rechaza, porque `REVOKE ALL ... FROM PUBLIC` no deja ningún privilegio
  heredado

### Requisito: Seguridad a nivel de fila por institución en `shared_audit_log`

El sistema DEBE activar `ENABLE ROW LEVEL SECURITY` y `FORCE ROW LEVEL SECURITY` en
`shared_audit_log`, con una política que filtra por
`institution_id = current_setting('app.institution_id', true)::uuid`. Una sesión sin contexto de
institución establecido, o con contexto vacío, DEBE recibir cero filas en vez de un error de
conversión.

#### Escenario: Una institución no puede leer las filas de otra

- **DADO** dos instituciones, cada una con filas confirmadas en `shared_audit_log`
- **CUANDO** una sesión con el contexto de la primera institución consulta la tabla
- **ENTONCES** recibe únicamente las filas de la primera institución, aunque las filas de la
  segunda existan físicamente en la tabla

#### Escenario: Contexto de institución ausente deniega en vez de fallar

- **DADO** una sesión sin `app.institution_id` establecido
- **CUANDO** esa sesión consulta `shared_audit_log`
- **ENTONCES** la política deniega devolviendo cero filas, sin lanzar un error de conversión a
  `uuid`

### Requisito: Contrato observable del verificador de cadena

El sistema DEBE exponer una rutina invocable, en Java, que recalcule `row_hash` desde el registro
génesis de una institución y compare cada fila recalculada contra la almacenada, en el orden de la
cadena. Cuando toda la cadena es consistente, la rutina DEBE reportar integridad sin identificar
ninguna fila. Cuando existe una fila cuyo hash recalculado no coincide con el almacenado, la
rutina DEBE identificar con exactitud esa primera fila divergente, por su `institution_id` y su
`id`, sin continuar reportando una segunda divergencia derivada de la primera.

#### Escenario: Cadena íntegra reporta integridad

- **DADO** una institución con varias filas confirmadas, todas producidas por el disparador de
  encadenamiento sin ninguna alteración posterior
- **CUANDO** se invoca el verificador con el contexto de esa institución
- **ENTONCES** reporta la cadena íntegra y no identifica ninguna fila

#### Escenario: Fila alterada directamente con acceso `SUPERUSER` es identificada con exactitud

- **DADO** una institución con varias filas confirmadas, y una fila intermedia alterada
  directamente con un rol `SUPERUSER` del contenedor de prueba, sin recalcular ningún hash
- **CUANDO** se invoca el verificador con el contexto de esa institución
- **ENTONCES** identifica exactamente esa fila, por su `institution_id` y su `id`, como la primera
  fila divergente de la cadena

#### Escenario: El verificador de una institución no recalcula ni ve las filas de otra

- **DADO** dos instituciones con filas confirmadas cada una
- **CUANDO** se invoca el verificador con el contexto de la primera institución
- **ENTONCES** solo recalcula y compara las filas de la primera institución, y una alteración en
  una fila de la segunda institución no aparece en su resultado

#### Escenario: Alterar la etiqueta legible del actor rompe la cadena

- **DADO** una fila confirmada de la bitácora cuya cadena el verificador reporta íntegra
- **CUANDO** un actor con acceso administrativo al motor cambia únicamente el valor de
  `actor_label` de esa fila, sin tocar ningún otro campo y sin recalcular la cadena
- **ENTONCES** el verificador reporta divergencia e identifica esa fila exacta, porque
  `actor_label` entra a la preimagen del hash; el mismo escenario sobre `user_agent` y sobre
  `trace_id` DEBE producir el mismo resultado

### Requisito: Reproducibilidad de la serialización canónica entre PL/pgSQL y Java

El sistema DEBE producir el mismo `row_hash` para la implementación de la serialización canónica
en PL/pgSQL, dentro del disparador, y para la implementación equivalente en Java, dentro del
verificador, sobre el mismo conjunto de campos de una misma fila. Esta propiedad DEBE verificarse
con una prueba de propiedades (jqwik) sobre entradas generadas, incluidas las familias de entrada
que producen divergencia clásica entre implementaciones: números con cero a la derecha, texto con
caracteres no ASCII, `NULL` frente a cadena vacía, `JSONB` con claves repetidas o anidadas, y
marcas de tiempo con zona horaria.

#### Escenario: Ambas implementaciones producen el mismo hash sobre entradas generadas

- **DADO** un conjunto de entradas generadas por jqwik que cubre las familias de divergencia
  clásica enumeradas arriba
- **CUANDO** se calcula el `row_hash` de cada entrada con la implementación en PL/pgSQL y con la
  implementación en Java
- **ENTONCES** ambos hashes coinciden byte a byte para cada entrada generada

#### Escenario: Una divergencia introducida a propósito hace fallar la prueba de propiedades

- **DADO** un fixture de prueba permanente donde la implementación en Java de la serialización
  canónica difiere deliberadamente de la implementación en PL/pgSQL en un solo campo (por
  ejemplo, el orden de claves de un objeto `JSONB` anidado)
- **CUANDO** se ejecuta la prueba de propiedades contra ese fixture
- **ENTONCES** la prueba falla señalando la entrada generada que produjo hashes distintos,
  demostrando que la prueba de propiedades detecta una divergencia real y no pasa por ausencia de
  comparación

### Requisito: Límite declarado del control mientras no exista ancla externa (cambio 11)

`docs/03-seguridad.md` §12.1 y §12.3 declaran que, sin una ancla externa publicada fuera de la
base de datos, la cadena de hash **solo protege contra manipulación torpe**: un actor con acceso
administrativo al motor que recalcule la cadena completa, o que deshabilite el disparador, no deja
divergencia detectable. El sistema DEBE detectar la alteración, el borrado o la inserción de una
fila que **no** recalcule la cadena completa a partir de ese punto. El sistema **NO** DEBE detectar
a un actor con acceso administrativo al motor que recalcule toda la cadena tras alterar una fila,
ni a uno que deshabilite el disparador de encadenamiento. Este es un límite conocido y aceptado
del control mientras no exista el ancla externa horaria del cambio 11 (fuera de alcance de este
cambio, por decisión del propietario del 2026-09-21). El día que el cambio 11 entregue esa ancla,
este requisito DEBE recibir un delta `MODIFIED` que declare la nueva capacidad de detección, en
vez de quedar tácitamente obsoleto.

#### Escenario: Manipulación sin recálculo de la cadena es detectada e identificada

- **DADO** una cadena de `shared_audit_log` con varias filas confirmadas para una institución
- **CUANDO** un actor con acceso `SUPERUSER` altera directamente un campo de una fila intermedia,
  sin recalcular el `row_hash` de esa fila ni el de ninguna fila posterior
- **ENTONCES** el verificador, al recalcular desde el registro génesis, identifica exactamente esa
  fila como la primera divergente

#### Escenario: Manipulación con recálculo completo de la cadena no es detectada — límite conocido

- **DADO** la misma cadena
- **CUANDO** un actor con acceso `SUPERUSER` altera un campo de una fila intermedia y además
  recalcula el `row_hash` de esa fila y el de todas las filas posteriores hasta la última, de modo
  que la cadena vuelve a ser internamente consistente
- **ENTONCES** el verificador reporta la cadena íntegra y no identifica ninguna fila divergente;
  este resultado es el límite conocido y aceptado del control mientras no exista el ancla externa
  del cambio 11

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
divergente y la descripción de la divergencia, resultado `error`, y un evento `ERROR` `audit_chain_broken` (alerta
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
  `error`) con la institución, el alcance, las filas verificadas y el `id` de esa fila, y una alerta de severidad
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

### Requisito: Criterio de fila de la bitácora limitado a la institución, sin filtro por `audit:read` (cambios 7 y 8)

`docs/03-seguridad.md` §6.3 declara que el criterio de fila de `shared_audit_log` es solo la
institución; el permiso `audit:read` de `docs/03-seguridad.md` §5.2 se aplica en la capa `web`, no
en la política de fila, y depende del control de acceso por roles que entregan los cambios 7 y 8.
Este cambio NO construye ningún controlador, caso de uso ni endpoint que lea `shared_audit_log`, y
la política de fila que sí entrega no conoce el permiso `audit:read`.

#### Escenario: La política de fila filtra solo por institución, sin verificar ningún permiso

- **DADO** dos instituciones con filas propias en `shared_audit_log`
- **CUANDO** una sesión con el contexto de la primera institución consulta la tabla con
  `confia_admin_app`
- **ENTONCES** recibe las filas de su propia institución sin ninguna verificación adicional del
  permiso `audit:read`, porque la política de fila no lo conoce

#### Escenario: Ningún endpoint expone la bitácora con el permiso `audit:read` — cierto hoy, falso al cerrar los cambios 7 y 8

- **DADO** el inventario de rutas de OpenAPI que la aplicación administrativa expone al cerrar
  este cambio
- **CUANDO** se busca una ruta que exponga `shared_audit_log` o el módulo `audit` con el permiso
  `audit:read`
- **ENTONCES** ninguna existe; este resultado deja de ser cierto el día que los cambios 7 y 8
  agreguen el controlador y la verificación de ese permiso
### Requisito: El JSON de detalle de un asiento se construye con un constructor tipado común y es igual byte a byte al anterior

El sistema DEBE proveer en `com.confia.shared.audit` un constructor tipado del JSON de detalle de un asiento de
auditoría, que escape correctamente todo valor, emita los miembros en el orden de inserción (determinista y
elegido por el llamador, para conservar el orden de los bytes que cada sitio escribía) y no permita construir el
documento por concatenación. Los nombres de miembro DEBEN cumplir `^[a-z][A-Za-z0-9]*$` y no repetirse; un nombre
inválido o repetido DEBE fallar con `IllegalArgumentException`. Todo sitio de producción que escribe un asiento
con detalle (exactamente dieciséis sitios en ocho clases de `identity` a la fecha de `6a8c61f`) DEBE usarlo. La migración de los sitios existentes NO DEBE cambiar
los bytes del detalle que cada uno escribía: para las mismas entradas, el JSON escrito DEBE ser idéntico byte a
byte, de modo que `row_hash` de los asientos nuevos sea el que habría resultado antes y la verificación de la
cadena no cambie. Los asientos nuevos de este cambio DEBEN usar el constructor desde su primer uso.

#### Escenario: [AT01] Igualdad byte a byte en los sitios migrados

- **DADO** cada sitio migrado y las entradas con las que sus pruebas existentes lo ejercitan
- **CUANDO** se compara el detalle escrito por la versión anterior (capturado como valor esperado) con el escrito
  por el constructor
- **ENTONCES** los bytes son idénticos en todos los sitios

#### Escenario: [AT02] Un valor con caracteres especiales se escapa

- **DADO** un valor de detalle con comillas, barra invertida, salto de línea y caracteres no ASCII
- **CUANDO** el constructor lo serializa
- **ENTONCES** el resultado es JSON válido que, al leerse, devuelve el valor original

#### Escenario: [AT03] El orden de los miembros es el de inserción y es determinista

- **DADO** el mismo conjunto de miembros añadido dos veces en el mismo orden, y en otro orden distinto
- **CUANDO** el constructor serializa cada documento
- **ENTONCES** las dos primeras serializaciones producen los mismos bytes, sin espacios
- **Y** la del otro orden emite los miembros en ese orden de inserción (no se reordenan alfabéticamente)

#### Escenario: [AT04] La cadena verifica tras la migración

- **DADO** una cadena con asientos escritos con el constructor
- **CUANDO** el verificador de cadena la recorre
- **ENTONCES** no reporta divergencia y la prueba de propiedades de la serialización canónica sigue pasando

#### Escenario: [AT05] Un valor que no es un tipo admitido se rechaza

- **DADO** un intento de añadir un objeto arbitrario al constructor
- **CUANDO** se compila o se ejecuta
- **ENTONCES** no se admite: solo texto, enteros, booleanos e instantes en una forma fija (`Instant.toString()`)

#### Escenario: [AT06] Nombres de miembro inválidos o repetidos

- **DADO** un nombre con mayúscula inicial, uno con guion, uno vacío y un nombre añadido dos veces
- **CUANDO** se intenta añadirlos al constructor
- **ENTONCES** cada uno falla con `IllegalArgumentException` y ningún documento se construye

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
