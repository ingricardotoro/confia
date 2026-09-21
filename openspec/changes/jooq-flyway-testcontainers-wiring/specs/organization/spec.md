# Delta para Raíz de institución y multi-institución

> **Nota para el archivado.** La sección «Fuera de alcance» de `openspec/specs/organization/spec.md`
> tiene un punto, «Persistencia real», que deja de ser cierto con este cambio: ya existe la tabla
> `organization_institution`, su migración y su repositorio jOOQ. Ese punto vive en una lista de
> exclusiones fuera de cualquier bloque `### Requisito:`, así que el mecanismo formal de
> ADDED/MODIFIED Requirements no lo alcanza. Al archivar, ese punto de «Fuera de alcance» DEBE
> retirarse o reescribirse para reflejar que la persistencia real ya no está fuera de alcance; los
> demás puntos de esa sección (administración de instituciones, adaptador de
> `CurrentInstitutionProvider`, calendario académico, reglas de otras capacidades) siguen vigentes
> sin cambio.

## MODIFIED Requirements

### Requisito: Puerto de salida para cargar una institución por identificador

La capa `application` de `organization` DEBE declarar el puerto de salida `InstitutionRepository`
con una operación que carga una `Institution` a partir de su `InstitutionId` y que devuelve una
ausencia de resultado (no una excepción) cuando no existe ninguna institución con ese
identificador. Este puerto NO DEBE depender de jOOQ, de un tipo de PostgreSQL ni de ningún otro
detalle de infraestructura. Su implementación real es el adaptador jOOQ de
`organization.infrastructure` que este cambio entrega, contra la tabla `organization_institution`.
(Previously: la última frase decía que la implementación real era responsabilidad del cambio 5;
ese cambio es este mismo, y la implementación ya existe.)

#### Escenario: Carga con dobles en memoria

- **DADO** un doble de prueba de `InstitutionRepository` que registra una `Institution` bajo un
  `InstitutionId` conocido
- **CUANDO** se invoca la operación de carga con ese identificador
- **ENTONCES** el doble devuelve esa `Institution`

#### Escenario: Ausencia de resultado para un identificador desconocido

- **DADO** un doble de prueba de `InstitutionRepository` sin ninguna institución registrada
- **CUANDO** se invoca la operación de carga con cualquier `InstitutionId`
- **ENTONCES** el doble devuelve una ausencia de resultado, sin lanzar ninguna excepción

## ADDED Requirements

### Requisito: Contrato observable del adaptador jOOQ de `InstitutionRepository` contra la base real

El adaptador jOOQ de `InstitutionRepository`, ubicado en `com.confia.organization.infrastructure`,
DEBE reconstruir una `Institution` fiel a la fila almacenada en `organization_institution`,
conservando cada atributo, incluidos el RTN como la secuencia exacta de dígitos almacenada (sin
normalización adicional) y la moneda por defecto. El adaptador **es de solo lectura en este cambio**:
el puerto `InstitutionRepository` solo declara `findById`, y una operación de escritura sin
consumidor en producción llegaría con la administración de instituciones (cambio 7), no aquí; la
siembra de filas en las pruebas es responsabilidad explícita de la prueba, con instrucciones SQL
directas. El adaptador DEBE devolver una ausencia de resultado, nunca una excepción, cuando se
consulta un `InstitutionId` para el que no existe fila. La base de datos DEBE rechazar la inserción
de dos filas con el mismo identificador de institución, porque la clave primaria de la tabla es ese
identificador.

#### Escenario: Reconstrucción fiel de cada atributo contra la base real

- **DADO** una fila sembrada por la prueba en `organization_institution` con `tradeName` presente,
  RTN de 14 dígitos y moneda `HNL`
- **CUANDO** el adaptador consulta esa institución por su `InstitutionId` contra el esquema real
- **ENTONCES** cada atributo reconstruido es igual al almacenado, incluidos el RTN como la misma
  secuencia exacta de dígitos y la moneda `HNL`

#### Escenario: Reconstrucción con nombre comercial ausente

- **DADO** una fila sembrada por la prueba con `trade_name` nulo
- **CUANDO** el adaptador consulta esa institución por su `InstitutionId`
- **ENTONCES** el `tradeName` reconstruido sigue siendo nulo, sin convertirse en cadena vacía

#### Escenario: Ausencia de resultado para un identificador desconocido en la base real

- **DADO** la tabla `organization_institution` sin ninguna fila para un `InstitutionId` dado
- **CUANDO** el adaptador consulta ese identificador contra el esquema real
- **ENTONCES** el adaptador devuelve una ausencia de resultado, sin lanzar ninguna excepción

#### Escenario: Rechazo de una fila duplicada con el mismo identificador

- **DADO** una fila ya sembrada bajo un `InstitutionId` determinado
- **CUANDO** la prueba intenta insertar otra fila en `organization_institution` con el mismo
  identificador
- **ENTONCES** la base de datos rechaza la inserción por violación de la clave primaria; la garantía
  vive en el esquema, no en el código de la aplicación

### Requisito: Aislamiento por fila de la tabla raíz según ADR-0009

La tabla `organization_institution` DEBE tener `ENABLE ROW LEVEL SECURITY` y
`FORCE ROW LEVEL SECURITY`, con una política que filtra por
`id = current_setting('app.institution_id', true)::uuid` (ADR-0009, «Implementación del
aislamiento»; ADR-0017, regla 1). Una sesión cuyo contexto de institución no coincide con el
identificador de una fila NO DEBE poder leer esa fila. Una sesión sin contexto de institución
establecido, o con contexto vacío, DEBE recibir cero filas de la política en lugar de un error de
conversión.

#### Escenario: Una institución no puede leer la fila de otra

- **DADO** dos instituciones sembradas, cada una en su propia fila de `organization_institution`
- **CUANDO** una sesión con el contexto de la primera institución consulta la fila de la segunda
- **ENTONCES** la consulta devuelve cero filas, aunque la fila exista físicamente en la tabla

#### Escenario: Contexto de sesión ausente deniega en vez de fallar

- **DADO** una sesión sin `app.institution_id` establecido en absoluto
- **CUANDO** esa sesión consulta `organization_institution`
- **ENTONCES** la política deniega devolviendo cero filas, sin lanzar un error de conversión a
  `uuid`

#### Escenario: Contexto de sesión vacío deniega en vez de fallar

- **DADO** una sesión con `app.institution_id` establecido como cadena vacía
- **CUANDO** esa sesión consulta `organization_institution`
- **ENTONCES** la política deniega devolviendo cero filas, sin lanzar un error de conversión a
  `uuid`

#### Escenario: Una institución sí puede leer su propia fila

- **DADO** una institución sembrada con su fila en `organization_institution`
- **CUANDO** una sesión con el contexto de esa misma institución consulta su propia fila
- **ENTONCES** la consulta devuelve exactamente esa fila

### Requisito: La longitud de la columna del RTN es una guarda técnica, no una regla fiscal

La columna del RTN en `organization_institution` DEBE declarar, en un comentario de columna, que su
límite de longitud es una guarda técnica heredada del dominio (de 1 a 20 dígitos) y no una regla
fiscal, mientras `docs/04-cumplimiento-fiscal-sar.md` §1 mantenga el formato del RTN como pendiente
de validación. La columna DEBE rechazar, a nivel de base de datos, un valor que exceda esa longitud
técnica, como defensa adicional independiente de la validación que ya aplica el dominio.

#### Escenario: El comentario de columna declara la guarda técnica

- **DADO** la migración que crea `organization_institution`
- **CUANDO** se inspecciona el comentario de la columna del RTN en el esquema real
- **ENTONCES** el comentario declara explícitamente que el límite es una guarda técnica y no una
  regla fiscal

#### Escenario: La base de datos rechaza un RTN que excede la guarda técnica

- **DADO** un intento de insertar directamente una fila en `organization_institution` con un valor
  de RTN de más de 20 caracteres, sin pasar por la validación del dominio
- **CUANDO** se ejecuta esa inserción contra el esquema real
- **ENTONCES** la base de datos rechaza la fila por violar la restricción de longitud de la columna

#### Escenario: Un RTN de longitud válida se almacena sin alteración

- **DADO** una `Institution` válida con RTN de 14 dígitos
- **CUANDO** se persiste a través del adaptador
- **ENTONCES** la fila almacenada conserva el RTN como la misma secuencia exacta de 14 dígitos, sin
  relleno ni truncamiento
