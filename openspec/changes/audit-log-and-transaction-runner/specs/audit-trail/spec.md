# Delta para Bitácora de auditoría (audit-trail)

## ADDED Requirements

### Requisito: Cadena de hash por institución con registro génesis

El sistema DEBE mantener, en la tabla `shared_audit_log`, una cadena de encadenamiento por hash
independiente para cada institución. Cada institución DEBE tener su propio registro génesis, cuyo
`prev_hash` son 32 bytes de ceros, y toda fila posterior de esa misma institución DEBE encadenar su
`prev_hash` con el `row_hash` de la fila inmediatamente anterior **de la misma institución**. La
clave primaria de la tabla DEBE ser compuesta, `(institution_id, id)`, porque ningún rol de
PostgreSQL tiene el atributo `BYPASSRLS` (`docs/03-seguridad.md` §6.1) y un verificador que corre
con el contexto de una institución no puede leer ni recalcular una cadena global.

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

### Requisito: Alcance de la verificación de cadena sin programación recurrente (cambio 9)

El verificador de cadena que este cambio entrega es una rutina invocable directamente. El sistema
NO DEBE depender de ninguna infraestructura de programación de tareas para ejecutarlo, porque
`db-scheduler` (ADR-0016) no se introduce hasta el cambio 9, que es dueño de programar sus tres
cadencias de `docs/03-seguridad.md` §12.4 (diaria, semanal, horaria).

#### Escenario: El verificador se invoca directamente, sin infraestructura de programación

- **DADO** el verificador de cadena que este cambio entrega, en un árbol de código sin ninguna
  infraestructura de programación de tareas
- **CUANDO** se invoca directamente, por ejemplo desde una prueba de integración
- **ENTONCES** produce su resultado de verificación —cadena íntegra o fila divergente
  identificada— sin depender de ninguna tarea programada

#### Escenario: Ninguna cadencia periódica está implementada todavía — cierto hoy, falso al cerrar el cambio 9

- **DADO** las tres cadencias de verificación periódica de `docs/03-seguridad.md` §12.4
- **CUANDO** se inspecciona el código que este cambio entrega buscando una tarea programada que
  ejecute alguna de esas cadencias
- **ENTONCES** ninguna existe; este resultado deja de ser cierto el día que el cambio 9 registre la
  primera tarea programada que invoque el verificador

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
