# Capacidad: Raíz de institución y multi-institución

- **Identificador:** organization
- **Estado:** Borrador
- **Fase:** F0

## Propósito

Primera capacidad de negocio del backend (ADR-0017 asigna la tabla raíz de instituciones al
módulo `organization`; ADR-0009 exige que todo el modelo de datos gire alrededor de la
institución). Esta especificación cubre, para este cambio, únicamente el agregado `Institution`,
su identificador (`InstitutionId`, ubicado en `kernel` por decisión técnica 1 de la propuesta),
sus invariantes de construcción y de transición, su catálogo de errores de dominio, y la
resolución de la institución de la solicitud en curso a través de puertos de salida.

Fuentes de verdad: `docs/02-modelo-de-dominio.md` §2.2 y §3.1, `docs/adr/ADR-0009-multitenencia.md`,
`docs/adr/ADR-0017-tablas-tecnicas-y-tabla-raiz.md`,
`docs/adr/ADR-0019-error-de-dominio-base-en-el-nucleo.md`, y
`openspec/changes/institution-root-and-multitenancy-baseline/proposal.md`.

## Fuera de alcance

Estos puntos están deliberadamente fuera de esta especificación; se listan para que no se
confundan con un olvido:

- **Administración de instituciones.** No hay alta, edición, conmutador ni endpoint de
  instituciones. ADR-0009, punto 5, difiere esa funcionalidad hasta que exista una segunda
  institución real.
- **Adaptador de la institución en curso desde el token autenticado.** El puerto
  `CurrentInstitutionProvider` se especifica aquí; su adaptador real sobre Spring Security lo
  entrega el cambio 7.
- **`AcademicYear`, `Modality`, `Grade`, `Section` y `BillingPeriod`.** Permanecen en el
  entregable 1 de F1 (decisión D2 de la propuesta); esta especificación no los menciona más allá de
  este aviso.
- **Reglas de negocio de otras capacidades** (dinero, mora, facturación, cobranza): `organization`
  es calendario y estructura institucional, no dinero.

## Requisitos

### Requisito: Identificador de institución en el núcleo (`InstitutionId`)

El sistema DEBE representar el identificador de una institución con el objeto de valor
`InstitutionId`, ubicado en el módulo `kernel` (`com.confia.kernel`), envolviendo un `UUID`. El
sistema NO DEBE ofrecer ningún constructor o fábrica de `InstitutionId` que acepte un valor nulo.
`InstitutionId` DEBE definir igualdad y código de dispersión por el `UUID` subyacente, de modo que
todo módulo de negocio pueda usarlo en sus firmas sin importar el `domain` de `organization`
(ADR-0002).

#### Escenario: Construcción desde un `UUID` válido

- **DADO** un `UUID` generado aleatoriamente
- **CUANDO** se construye `InstitutionId` a partir de ese `UUID`
- **ENTONCES** el `InstitutionId` resultante expone ese mismo `UUID` como valor subyacente

#### Escenario: Rechazo de un identificador nulo

- **DADO** un valor nulo
- **CUANDO** se intenta construir `InstitutionId` a partir de ese valor
- **ENTONCES** la construcción falla con una excepción del JDK (`NullPointerException`), porque un
  argumento nulo es un error de programación y no una condición de negocio (ADR-0019, punto 6)

#### Escenario: Igualdad por el `UUID` subyacente

- **DADO** dos instancias de `InstitutionId` construidas a partir del mismo `UUID`
- **CUANDO** se comparan con `equals`
- **ENTONCES** son iguales y sus `hashCode` coinciden

### Requisito: Construcción de `Institution` y sus atributos de identidad obligatorios

El sistema DEBE construir el agregado raíz `Institution` con los atributos
`id` (`InstitutionId`), `legalName`, `tradeName`, `rtn`, `address`, `defaultCurrency`
(`CurrencyCode` de `kernel`), `locale`, `timezone` e `isActive`, según
`docs/02-modelo-de-dominio.md` §3.1. Todo atributo obligatorio nulo (todos salvo `tradeName`) es un
error de programación y DEBE producir `NullPointerException`, no un error de dominio (ADR-0019). El
sistema DEBE rechazar la construcción cuando `legalName` está vacío o contiene solo espacios en
blanco, con el código de error de dominio `institution-legal-name-blank`, y cuando `address` está
vacía o contiene solo espacios en blanco, con el código de error de dominio
`institution-address-blank`. Los textos se guardan sin espacios de borde. El sistema DEBE rechazar
un `legalName` de más de 200 caracteres (puntos de código), con `institution-legal-name-too-long`,
y una `address` de más de 500, con `institution-address-too-long`. Toda instancia nueva de
`Institution` DEBE nacer con `isActive` en verdadero.

#### Escenario: Construcción exitosa con todos los atributos válidos

- **DADO** un `InstitutionId` válido, `legalName` "Instituto San Marcos", `rtn` no vacío,
  `address` no vacía, moneda por defecto `HNL`, `locale` `es-HN` y `timezone` `America/Tegucigalpa`
- **CUANDO** se construye `Institution` con esos valores
- **ENTONCES** la construcción tiene éxito, la institución resultante conserva cada atributo (los
  textos, sin espacios de borde), y `isActive` es verdadero

#### Escenario: Rechazo de un nombre legal en blanco

- **DADO** los mismos valores válidos excepto `legalName`, que se provee como una cadena de solo
  espacios
- **CUANDO** se intenta construir `Institution`
- **ENTONCES** la construcción falla con el código de error de dominio
  `institution-legal-name-blank`

#### Escenario: Rechazo de una dirección vacía

- **DADO** los mismos valores válidos excepto `address`, que se provee como cadena vacía
- **CUANDO** se intenta construir `Institution`
- **ENTONCES** la construcción falla con el código de error de dominio `institution-address-blank`

#### Escenario: Longitud máxima del nombre legal y de la dirección

- **DADO** un `legalName` de exactamente 200 caracteres y otro de 201, y una `address` de
  exactamente 500 caracteres y otra de 501
- **CUANDO** se construye `Institution` con cada uno
- **ENTONCES** los de 200 y 500 caracteres se aceptan; el de 201 falla con
  `institution-legal-name-too-long` y el de 501 con `institution-address-too-long`

### Requisito: RTN presente y numérico, con el formato exacto pendiente

El sistema DEBE rechazar la construcción de `Institution` cuando `rtn` no es una secuencia de entre
1 y 20 dígitos ASCII, sin separadores ni espacios, con el código de error de dominio
`institution-rtn-invalid`. El límite de 20 es una guarda técnica, no una regla fiscal. El formato
exacto del RTN vigente ante el SAR (longitud, máscara y posible dígito verificador) NO está
confirmado contra una fuente primaria (`docs/04` lo marca como pendiente y la documentación de
interfaz contiene ejemplos de 13 y de 14 dígitos), por lo que esta especificación no lo exige.

#### Escenario: Rechazo de un RTN vacío

- **DADO** los demás atributos válidos y `rtn` como cadena vacía
- **CUANDO** se intenta construir `Institution`
- **ENTONCES** la construcción falla con el código de error de dominio `institution-rtn-invalid`

#### Escenario: Rechazo de un RTN con caracteres no numéricos o demasiado largo

- **DADO** los demás atributos válidos y `rtn` con valor `"0801-1990-12345"` (con guiones), y otro
  de 21 dígitos
- **CUANDO** se intenta construir `Institution` con cada uno
- **ENTONCES** ambos fallan con `institution-rtn-invalid`, y un RTN de 14 dígitos se acepta

> **Escenario diferido.** La validación del formato exacto del RTN vigente del SAR queda pendiente
> de una fuente primaria. Cuando se confirme, se especifica aquí sin cambiar el código
> `institution-rtn-invalid`; nunca se inventa un formato ni un dígito verificador no confirmados.

### Requisito: Moneda por defecto, localización y huso horario válidos

El sistema DEBE exigir que `defaultCurrency` sea un `CurrencyCode` del conjunto cerrado de
`kernel` (HNL, USD); un valor nulo es un error de programación (`NullPointerException`), no un
error de dominio, porque `CurrencyCode` ya es un tipo cerrado que no admite un valor fuera de su
conjunto. El sistema DEBE rechazar la construcción cuando `locale` no tiene idioma (por ejemplo,
`Locale.ROOT`, que es lo que produce una etiqueta de idioma inválida), con el código de error de
dominio `institution-locale-invalid`; un `locale` nulo es un error de programación. El sistema DEBE
rechazar la construcción cuando `timezone` es un desplazamiento fijo (`ZoneOffset`, por ejemplo
`-06:00`) en lugar de un identificador de región, con el código de error de dominio
`institution-timezone-invalid`; un identificador de región no reconocido ya no puede construirse
como `ZoneId`.

#### Escenario: Huso horario reconocido

- **DADO** los demás atributos válidos y `timezone` `America/Tegucigalpa`
- **CUANDO** se construye `Institution`
- **ENTONCES** la construcción tiene éxito

#### Escenario: Huso horario de desplazamiento fijo

- **DADO** los demás atributos válidos y `timezone` `-06:00`, un desplazamiento fijo
- **CUANDO** se intenta construir `Institution`
- **ENTONCES** la construcción falla con el código de error de dominio
  `institution-timezone-invalid`

#### Escenario: Localización sin idioma

- **DADO** los demás atributos válidos y `locale` `Locale.ROOT`, sin idioma
- **CUANDO** se intenta construir `Institution`
- **ENTONCES** la construcción falla con el código de error de dominio `institution-locale-invalid`

#### Escenario: Moneda por defecto nula

- **DADO** los demás atributos válidos y `defaultCurrency` nulo
- **CUANDO** se intenta construir `Institution`
- **ENTONCES** la construcción falla con `NullPointerException`, no con un error de dominio

### Requisito: Nombre comercial opcional

El sistema PUEDE construir `Institution` sin `tradeName` (valor nulo), porque el nombre comercial
es distinto del nombre legal y no toda institución lo declara. El sistema DEBE rechazar la
construcción cuando `tradeName` se provee explícitamente como cadena vacía o de solo espacios en
blanco, con el código de error de dominio `institution-trade-name-blank`, para evitar un valor
presente pero vacío que confunda "sin nombre comercial" con "nombre comercial vacío". El sistema DEBE
rechazar un `tradeName` de más de 200 caracteres con `institution-trade-name-too-long`.

#### Escenario: Institución sin nombre comercial

- **DADO** los demás atributos válidos y `tradeName` nulo
- **CUANDO** se construye `Institution`
- **ENTONCES** la construcción tiene éxito y `tradeName` es nulo

#### Escenario: Nombre comercial provisto en blanco

- **DADO** los demás atributos válidos y `tradeName` como cadena de solo espacios
- **CUANDO** se intenta construir `Institution`
- **ENTONCES** la construcción falla con el código de error de dominio
  `institution-trade-name-blank`

### Requisito: Activación y desactivación de una institución

`Institution` DEBE exponer las transiciones `activate()` y `deactivate()`. El sistema DEBE
rechazar `activate()` sobre una institución ya activa, con el código de error de dominio
`institution-already-active`. El sistema DEBE rechazar `deactivate()` sobre una institución ya
inactiva, con el código de error de dominio `institution-already-inactive`. Ninguna transición
DEBE modificar ningún otro atributo de la institución.

#### Escenario: Desactivación exitosa de una institución activa

- **DADO** una `Institution` con `isActive` verdadero
- **CUANDO** se invoca `deactivate()`
- **ENTONCES** la operación tiene éxito e `isActive` pasa a falso, sin modificar ningún otro
  atributo

#### Escenario: Reactivación exitosa de una institución inactiva

- **DADO** una `Institution` con `isActive` falso
- **CUANDO** se invoca `activate()`
- **ENTONCES** la operación tiene éxito e `isActive` pasa a verdadero

#### Escenario: Activación de una institución ya activa

- **DADO** una `Institution` con `isActive` verdadero
- **CUANDO** se invoca `activate()`
- **ENTONCES** la operación falla con el código de error de dominio `institution-already-active`,
  sin modificar el estado

#### Escenario: Desactivación de una institución ya inactiva

- **DADO** una `Institution` con `isActive` falso
- **CUANDO** se invoca `deactivate()`
- **ENTONCES** la operación falla con el código de error de dominio `institution-already-inactive`,
  sin modificar el estado

### Requisito: Jerarquía de errores de dominio del módulo `organization`

Toda excepción de dominio del módulo `organization` DEBE heredar de `DomainException`
(`com.confia.kernel`, ADR-0019) y DEBE declarar su propio código estable mediante `code()`, en
kebab-case, con la forma `<sujeto>-<condición>` (por ejemplo, `institution-legal-name-blank`).
Los errores de programación de este módulo (argumento nulo, identificador de institución
inexistente pasado por el propio código en vez de por un dato de negocio) NO DEBEN señalarse con
`DomainException`: usan las excepciones del JDK, según ADR-0019 punto 6.

#### Escenario: Un error de construcción hereda de `DomainException`

- **DADO** cualquier excepción lanzada por una invariante de construcción o de transición de
  `Institution`
- **CUANDO** se verifica su jerarquía
- **ENTONCES** hereda de `DomainException`, es una excepción no comprobada, y `code()` devuelve un
  código propio del módulo

#### Escenario: Un argumento nulo no produce una excepción de dominio

- **DADO** una llamada a un constructor o método de `organization.domain` con un argumento
  obligatorio nulo (por ejemplo, `InstitutionId` nulo al construir `Institution`)
- **CUANDO** se invoca esa llamada
- **ENTONCES** se lanza `NullPointerException`, nunca una subclase de `DomainException`

### Requisito: Catálogo de códigos del módulo: formato y ausencia de repetidos

El sistema DEBE mantener una prueba de catálogo que enumere cada código de error de dominio que
emite `organization.domain` y afirme que todos cumplen el formato kebab-case
`^[a-z][a-z0-9]*(-[a-z0-9]+)*$`, de como máximo 64 caracteres, y que ningún código se repite
dentro del catálogo del módulo (ADR-0019, «Cumplimiento», punto 2, extendido a este módulo).

#### Escenario: El catálogo cumple el formato y no tiene repetidos

- **DADO** la lista completa de códigos que emite `organization.domain`
- **CUANDO** se ejecuta la prueba de catálogo
- **ENTONCES** cada código cumple el patrón kebab-case y su longitud máxima, y no hay dos entradas
  con el mismo código

#### Escenario: Un código incumple el formato

- **DADO** un código hipotético con una letra mayúscula o un guion al inicio
- **CUANDO** se ejecuta la prueba de catálogo sobre un catálogo que lo incluyera
- **ENTONCES** la prueba falla señalando el código que incumple el formato

### Requisito: Puerto de salida para cargar una institución por identificador

La capa `application` de `organization` DEBE declarar el puerto de salida `InstitutionRepository`
con una operación que carga una `Institution` a partir de su `InstitutionId` y que devuelve una
ausencia de resultado (no una excepción) cuando no existe ninguna institución con ese
identificador. Este puerto NO DEBE depender de jOOQ, de un tipo de PostgreSQL ni de ningún otro
detalle de infraestructura. Su implementación real es el adaptador jOOQ de
`organization.infrastructure` que el cambio 5 entregó, contra la tabla `organization_institution`.

#### Escenario: Carga con dobles en memoria

- **DADO** un doble de prueba de `InstitutionRepository` que registra una `Institution` bajo un
  `InstitutionId` conocido
- **CUANDO** se invoca la operación de carga con ese identificador
- **ENTONCES** el doble devuelve esa `Institution`

#### Escenario: Ausencia de resultado para un identificador desconocido

- **DADO** un doble de prueba de `InstitutionRepository` sin ninguna institución registrada
- **CUANDO** se invoca la operación de carga con cualquier `InstitutionId`
- **ENTONCES** el doble devuelve una ausencia de resultado, sin lanzar ninguna excepción

### Requisito: Puerto de salida para la institución de la solicitud en curso

La capa `application` de `organization` DEBE declarar el puerto de salida
`CurrentInstitutionProvider`, con una operación que resuelve el `InstitutionId` de la solicitud en
curso. Este puerto NO DEBE depender de un parámetro provisto por el cliente (ADR-0009,
«Implementación del aislamiento»): su implementación real, a partir del token autenticado, es
responsabilidad del cambio 7. Esta especificación no exige que el puerto resuelva un identificador
en cada invocación; declarar el comportamiento ante ausencia de sesión autenticada es
responsabilidad del cambio que lo implemente.

#### Escenario: Resolución con un doble en memoria

- **DADO** un doble de prueba de `CurrentInstitutionProvider` configurado para devolver un
  `InstitutionId` conocido
- **CUANDO** se invoca su operación de resolución
- **ENTONCES** el doble devuelve ese `InstitutionId`

### Requisito: Caso de uso de resolución de la institución en curso

El sistema DEBE ofrecer un caso de uso que, usando `CurrentInstitutionProvider` para obtener el
`InstitutionId` de la solicitud en curso y `InstitutionRepository` para cargar la institución
correspondiente, devuelve esa `Institution` cuando existe y está activa. El sistema DEBE rechazar
la resolución cuando `InstitutionRepository` no encuentra ninguna institución para ese
identificador, con el código de error de dominio `institution-not-found`. El sistema DEBE
rechazar la resolución cuando la institución encontrada tiene `isActive` en falso, con el código
de error de dominio `institution-inactive`.

#### Escenario: Resolución exitosa de una institución activa

- **DADO** un `CurrentInstitutionProvider` que resuelve un `InstitutionId` conocido y un
  `InstitutionRepository` que devuelve, para ese identificador, una `Institution` con `isActive`
  verdadero
- **CUANDO** se ejecuta el caso de uso de resolución
- **ENTONCES** el caso de uso devuelve esa `Institution`

#### Escenario: Rechazo de una institución inexistente

- **DADO** un `CurrentInstitutionProvider` que resuelve un `InstitutionId`, y un
  `InstitutionRepository` que no tiene ninguna institución registrada bajo ese identificador
- **CUANDO** se ejecuta el caso de uso de resolución
- **ENTONCES** el caso de uso falla con el código de error de dominio `institution-not-found`

#### Escenario: Rechazo de una institución inactiva

- **DADO** un `CurrentInstitutionProvider` que resuelve un `InstitutionId`, y un
  `InstitutionRepository` que devuelve, para ese identificador, una `Institution` con `isActive`
  falso
- **CUANDO** se ejecuta el caso de uso de resolución
- **ENTONCES** el caso de uso falla con el código de error de dominio `institution-inactive`

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

#### Escenario: Un RTN de longitud válida se reconstruye sin alteración

- **DADO** una fila sembrada por la prueba en `organization_institution` con un RTN de 14 dígitos
- **CUANDO** el adaptador de solo lectura reconstruye esa institución contra el esquema real
- **ENTONCES** el RTN reconstruido es la misma secuencia exacta de 14 dígitos, sin relleno ni
  truncamiento
