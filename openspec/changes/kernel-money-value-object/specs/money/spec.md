# Capacidad: Dinero exacto en el núcleo

- **Identificador:** money
- **Estado:** Borrador
- **Fase:** F0

## Propósito

Capacidad técnica del módulo `kernel`: especifica el comportamiento exacto del objeto de valor
`Money`, de su colaborador `Percentage`, del tipo cerrado de moneda y de la jerarquía de errores de
dominio de `kernel`. No define reglas de negocio (mora, impuestos, imputación de pagos, becas): esas
viven en el paquete `domain` del módulo que las posee. `kernel` no depende de nada fuera del JDK
(verificado por la capacidad `build-integrity`).

Fuentes de verdad: `CLAUDE.md` reglas 1 a 5, `docs/01-arquitectura.md` §4 y §6,
`docs/adr/ADR-0004-postgresql-y-representacion-monetaria.md`, ADR-0008, ADR-0011 y
`.claude/skills/confia-money-rules/SKILL.md`.

## Requisitos

### Requisito: Construcción y normalización a escala cuatro

El sistema DEBE normalizar todo importe de `Money` a escala cuatro decimal, coherente con
`NUMERIC(14,4)`. La normalización NO DEBE truncar en silencio: el sistema DEBE rechazar la
construcción cuando el valor de entrada tiene más de cuatro decimales distintos de cero, en vez de
descartar dígitos, con el código de error de dominio `money-scale-exceeded`; los ceros sobrantes a
la derecha no pierden información y se aceptan. El sistema DEBE rechazar la construcción cuando la
cadena de entrada no tiene la forma de un decimal plano (`-?\d+(\.\d+)?`, sin notación exponencial,
sin signo `+`, sin espacios ni separadores de miles) o cuando supera 64 caracteres, con el código de
error de dominio `money-amount-malformed`. Cuando una entrada incumple varias reglas, el sistema DEBE
informar una sola, en este orden de precedencia: `money-amount-malformed`, luego
`money-scale-exceeded`, luego `money-amount-out-of-range`. El sistema DEBE rechazar la
construcción cuando el valor absoluto del importe supera `9999999999.9999`, el límite de
`NUMERIC(14,4)`, con el código de error de dominio `money-amount-out-of-range`. `Money` DEBE ofrecer
fábricas desde `String` y desde `BigDecimal`; el sistema NO DEBE ofrecer ninguna fábrica de `Money`
desde `double` ni desde `float`.

#### Escenario: Construcción desde una cadena decimal válida

- **DADO** la cadena decimal `"1250.15"` y la moneda HNL
- **CUANDO** se construye `Money` a partir de esa cadena y esa moneda
- **ENTONCES** el `Money` resultante tiene importe normalizado `1250.1500` a escala cuatro y moneda
  HNL

#### Escenario: Construcción del importe cero

- **DADO** la moneda HNL
- **CUANDO** se construye `Money.zero(HNL)`
- **ENTONCES** el `Money` resultante tiene importe `0.0000` y `isZero()` devuelve verdadero

#### Escenario: Construcción de un importe negativo

- **DADO** la cadena decimal `"-45.50"` y la moneda HNL
- **CUANDO** se construye `Money` a partir de esa cadena
- **ENTONCES** la construcción tiene éxito y el `Money` resultante representa `-45.5000`

#### Escenario: Construcción del importe mínimo representable

- **DADO** la cadena decimal `"0.01"` y la moneda HNL
- **CUANDO** se construye `Money` a partir de esa cadena
- **ENTONCES** el `Money` resultante representa exactamente `0.0100`

#### Escenario: Construcción en el límite de `NUMERIC(14,4)`

- **DADO** la cadena decimal `"9999999999.9999"`, el importe máximo representable en
  `NUMERIC(14,4)`
- **CUANDO** se construye `Money` a partir de esa cadena
- **ENTONCES** la construcción tiene éxito y el importe normalizado conserva los catorce dígitos
  significativos sin pérdida de precisión

#### Escenario: Rechazo de construcción con más de cuatro decimales

- **DADO** la cadena decimal `"1.00001"`
- **CUANDO** se intenta construir `Money` a partir de esa cadena
- **ENTONCES** la construcción falla con el código de error de dominio `money-scale-exceeded`, en vez
  de truncar el quinto decimal en silencio

#### Escenario: Ceros sobrantes a la derecha aceptados

- **DADO** la cadena decimal `"1.000000"`, con seis decimales todos cero
- **CUANDO** se construye `Money` a partir de esa cadena
- **ENTONCES** la construcción tiene éxito y el importe normalizado es `1.0000`

#### Escenario: Longitud máxima de la cadena de importe

- **DADO** una cadena decimal válida de exactamente 64 caracteres dentro del rango representable, y
  otra de 65 caracteres
- **CUANDO** se construye `Money` a partir de cada una
- **ENTONCES** la de 64 caracteres se acepta y la de 65 falla con el código de error de dominio
  `money-amount-malformed`

#### Escenario: Precedencia entre los errores de construcción

- **DADO** la cadena `"1,234.56789"`, mal formada y con más de cuatro decimales, y la cadena
  `"10000000000.00001"`, con más de cuatro decimales y fuera de rango
- **CUANDO** se intenta construir `Money` a partir de cada una
- **ENTONCES** la primera falla con `money-amount-malformed` y la segunda con
  `money-scale-exceeded`: el sistema informa una sola causa, la de mayor precedencia

#### Escenario: Rechazo de una cadena de importe mal formada

- **DADO** la cadena `"1,234.56"`, con separador de miles
- **CUANDO** se intenta construir `Money` a partir de esa cadena
- **ENTONCES** la construcción falla con el código de error de dominio `money-amount-malformed`

#### Escenario: Rechazo de construcción por encima del límite de `NUMERIC(14,4)`

- **DADO** la cadena decimal `"10000000000.0000"`, un dígito entero más que el máximo representable
  en `NUMERIC(14,4)`
- **CUANDO** se intenta construir `Money` a partir de esa cadena
- **ENTONCES** la construcción falla con el código de error de dominio `money-amount-out-of-range`

#### Escenario: Ausencia de fábrica desde coma flotante

- **DADO** el tipo `Money`
- **CUANDO** se revisa su conjunto de fábricas públicas
- **ENTONCES** ninguna fábrica acepta un parámetro `double` ni `float`; el código que lo intente no
  compila

#### Escenario: Escala extrema en un `BigDecimal` de entrada

- **DADO** un `BigDecimal` de escala positiva mayor que 34 (por ejemplo `1E-500000000`) y otro de
  escala negativa que solo puede representar un número mayor que el límite (por ejemplo `1E+10` o
  `1E+500000000`)
- **CUANDO** se construye `Money` o `Percentage` a partir de cada uno
- **ENTONCES** el primero falla con `money-scale-exceeded` (o `percentage-scale-exceeded`) y el
  segundo con `money-amount-out-of-range` (o `percentage-out-of-range`), antes de cualquier
  reescalado y en tiempo acotado; una escala de hasta 34 sigue aceptándose cuando los decimales
  sobrantes son cero, y un cero de cualquier escala se normaliza a cero

### Requisito: Moneda cerrada al conjunto habilitado

El sistema DEBE representar la moneda con un tipo cerrado, `CurrencyCode`, restringido al conjunto
de códigos habilitados (HNL, USD), coherente con el `CHECK (currency IN ('HNL','USD'))` del esquema.
El sistema NO DEBE admitir un código de moneda fuera de ese conjunto.

#### Escenario: Construcción con una moneda habilitada

- **DADO** el código `HNL`
- **CUANDO** se construye `Money.of("100.00", CurrencyCode.HNL)`
- **ENTONCES** la construcción tiene éxito y el `Money` resultante reporta moneda `HNL`

#### Escenario: Código de moneda no habilitado

- **DADO** el conjunto cerrado de `CurrencyCode` con únicamente `HNL` y `USD`
- **CUANDO** se intenta resolver un código de moneda fuera de ese conjunto (por ejemplo, `EUR`)
- **ENTONCES** la resolución falla; no existe un tercer valor de `CurrencyCode` que lo represente

### Requisito: Igualdad por importe normalizado y moneda

`Money.equals` DEBE comparar el importe ya normalizado a escala cuatro y la moneda; NO DEBE usar
`BigDecimal.equals` sobre el importe sin normalizar. `Money.hashCode` DEBE ser coherente con esa
igualdad.

#### Escenario: Igualdad insensible a la escala de entrada

- **DADO** `Money.of("1.0", CurrencyCode.HNL)` y `Money.of("1.00", CurrencyCode.HNL)`
- **CUANDO** se comparan con `equals`
- **ENTONCES** son iguales y sus `hashCode` coinciden

#### Escenario: Mismo importe numérico, moneda distinta

- **DADO** `Money.of("100.00", CurrencyCode.HNL)` y `Money.of("100.00", CurrencyCode.USD)`
- **CUANDO** se comparan con `equals`
- **ENTONCES** no son iguales

#### Escenario: Importes distintos en la misma moneda

- **DADO** `Money.of("100.00", CurrencyCode.HNL)` y `Money.of("100.01", CurrencyCode.HNL)`
- **CUANDO** se comparan con `equals`
- **ENTONCES** no son iguales

### Requisito: Suma y resta solo entre la misma moneda

`Money.add` y `Money.subtract` DEBEN operar únicamente entre importes de la misma moneda y DEBEN
conservar precisión exacta a escala cuatro sin redondeo intermedio. Ante monedas distintas, el
sistema DEBE lanzar `CurrencyMismatchException`. El invariante de rango de `Money` (valor absoluto
menor o igual a `9999999999.9999`) se verifica sobre el resultado de toda operación, no solo sobre la
construcción inicial: un resultado de `add` o `subtract` fuera de ese rango DEBE fallar con el código
de error de dominio `money-amount-out-of-range`, sin construir ningún `Money`.

#### Escenario: Suma exitosa en la misma moneda

- **DADO** `Money.of("120.50", CurrencyCode.HNL)` y `Money.of("30.25", CurrencyCode.HNL)`
- **CUANDO** se suman con `add`
- **ENTONCES** el resultado es `Money.of("150.75", CurrencyCode.HNL)`

#### Escenario: Resta exitosa en la misma moneda

- **DADO** `Money.of("120.50", CurrencyCode.HNL)` y `Money.of("30.25", CurrencyCode.HNL)`
- **CUANDO** se restan con `subtract`
- **ENTONCES** el resultado es `Money.of("90.25", CurrencyCode.HNL)`

#### Escenario: Suma de monedas distintas

- **DADO** `Money.of("100.00", CurrencyCode.HNL)` y `Money.of("100.00", CurrencyCode.USD)`
- **CUANDO** se intenta sumarlos con `add`
- **ENTONCES** se lanza `CurrencyMismatchException` y ningún `Money` se construye

#### Escenario: Suma que excede el límite superior de `NUMERIC(14,4)`

- **DADO** `Money` construido con el importe máximo representable en `NUMERIC(14,4)`,
  `9999999999.9999`, en HNL
- **CUANDO** se le suma `Money.of("0.0001", CurrencyCode.HNL)`
- **ENTONCES** la operación falla con el código de error de dominio `money-amount-out-of-range`, sin
  construir ningún `Money`

#### Escenario: Resta que excede el límite inferior de `NUMERIC(14,4)`

- **DADO** `Money` construido con el importe mínimo representable en `NUMERIC(14,4)`,
  `-9999999999.9999`, en HNL
- **CUANDO** se le resta `Money.of("0.0001", CurrencyCode.HNL)`
- **ENTONCES** la operación falla con el código de error de dominio `money-amount-out-of-range`, sin
  construir ningún `Money`

### Requisito: Redondeo explícito con `HALF_UP` en toda operación que redondea

Toda operación de `Money` que pueda redondear (multiplicación por factor decimal, `percentage`,
`roundToMinorUnit`) DEBE recibir `RoundingMode` de forma explícita en su firma. Ninguna de esas
operaciones DEBE tener un modo de redondeo por defecto implícito. El modo `HALF_UP` redondea el
punto medio exacto alejándose de cero.

#### Escenario: `HALF_UP` en los tres puntos medios exactos obligatorios

- **DADO** los importes intermedios `0.615`, `2.345` y `1.005` en HNL, a escala cuatro
- **CUANDO** se redondean a la escala menor de la moneda (dos decimales) con `HALF_UP`
- **ENTONCES** los resultados son `0.62`, `2.35` y `1.01` respectivamente

#### Escenario: Simetría del redondeo `HALF_UP` en importes negativos

- **DADO** los importes intermedios `-0.615`, `-2.345` y `-1.005` en HNL, a escala cuatro
- **CUANDO** se redondean a la escala menor de la moneda (dos decimales) con `HALF_UP`
- **ENTONCES** los resultados son `-0.62`, `-2.35` y `-1.01` respectivamente, simétricos a sus
  contrapartes positivas

#### Escenario: Multiplicación por un factor decimal exacto

- **DADO** `Money.of("1234.55", CurrencyCode.HNL)`
- **CUANDO** se multiplica por el factor entero exacto `3`
- **ENTONCES** el resultado es `Money.of("3703.65", CurrencyCode.HNL)`, sin necesidad de redondeo
  porque el resultado cabe en la escala interna

#### Escenario: Factor decimal de escala absurda

- **DADO** un factor `BigDecimal` cuya escala está fuera de `[-34, 34]`
- **CUANDO** se invoca `multiply(factor, HALF_UP)`
- **ENTONCES** la operación falla de inmediato con `IllegalArgumentException`, porque un factor
  proviene del código y una escala así es un error de programación (ADR-0019), sin llegar a
  multiplicar

#### Escenario: Desbordamiento de rango en multiplicación y redondeo

- **DADO** importes cerca del máximo representable de `NUMERIC(14,4)`
- **CUANDO** se multiplican por un entero o por un factor decimal cuyo resultado supera el límite,
  o se aplica `roundToMinorUnit(HALF_UP)` a `9999999999.9999`
- **ENTONCES** cada operación falla con `money-amount-out-of-range` y no construye ningún `Money`

### Requisito: `Percentage` como colaborador explícito de `Money`

El sistema DEBE representar todo porcentaje utilizado en operaciones de `Money` mediante el objeto
de valor `Percentage`, construido desde `String` o desde `BigDecimal`. El sistema NO DEBE ofrecer una
fábrica de `Percentage` desde `double` ni desde `float`. `Money.percentage(Percentage, RoundingMode)`
DEBE recibir ese tipo explícito; ningún método de `Money` DEBE aceptar un `BigDecimal` o un número
crudo como porcentaje. El sistema DEBE restringir `Percentage` al intervalo cerrado `[0, 100]`; un
valor fuera de ese intervalo DEBE fallar con el código de error de dominio `percentage-out-of-range`.

#### Escenario: Aplicación de un porcentaje con redondeo explícito

- **DADO** `Money.of("12.30", CurrencyCode.HNL)` y `Percentage.of("5")`
- **CUANDO** se invoca `percentage(Percentage.of("5"), RoundingMode.HALF_UP)` y se redondea el
  resultado a la escala menor de la moneda con `HALF_UP`
- **ENTONCES** el resultado es `Money.of("0.62", CurrencyCode.HNL)` (el intermedio exacto es
  `0.6150`)

#### Escenario: Porcentaje con tasa cero

- **DADO** `Money.of("500.00", CurrencyCode.HNL)` y `Percentage.of("0")`
- **CUANDO** se invoca `percentage(Percentage.of("0"), RoundingMode.HALF_UP)`
- **ENTONCES** el resultado es `Money.of("0.00", CurrencyCode.HNL)` y el importe base no se modifica

#### Escenario: Rechazo de un porcentaje fuera de rango

- **DADO** la cadena decimal `"100.01"`
- **CUANDO** se intenta construir `Percentage.of("100.01")`
- **ENTONCES** la construcción falla con el código de error de dominio `percentage-out-of-range`

#### Escenario: Ausencia de fábrica de `Percentage` desde coma flotante

- **DADO** el tipo `Percentage`
- **CUANDO** se revisa su conjunto de fábricas públicas
- **ENTONCES** ninguna fábrica acepta un parámetro `double` ni `float`; el código que lo intente no
  compila

### Requisito: Comparación total ordenada entre importes de la misma moneda

`Money` DEBE exponer `isZero`, `isPositive`, `isNegative`, `isGreaterThan`, `isGreaterThanOrEqual`,
`isLessThan`, `isLessThanOrEqual` y `compareTo`. Toda comparación entre importes de monedas distintas
DEBE lanzar `CurrencyMismatchException`.

#### Escenario: Comparaciones ordenadas en la misma moneda

- **DADO** `Money.of("100.00", CurrencyCode.HNL)` y `Money.of("50.00", CurrencyCode.HNL)`
- **CUANDO** se evalúa `isGreaterThan` del primero sobre el segundo
- **ENTONCES** el resultado es verdadero, y `isLessThan` del segundo sobre el primero también es
  verdadero

#### Escenario: Verificación de cero, positivo y negativo

- **DADO** `Money.zero(HNL)`, `Money.of("10.00", CurrencyCode.HNL)` y
  `Money.of("-10.00", CurrencyCode.HNL)`
- **CUANDO** se evalúan `isZero`, `isPositive` e `isNegative` sobre cada uno respectivamente
- **ENTONCES** cada predicado correspondiente devuelve verdadero y los demás devuelven falso

#### Escenario: Comparación entre monedas distintas

- **DADO** `Money.of("100.00", CurrencyCode.HNL)` y `Money.of("100.00", CurrencyCode.USD)`
- **CUANDO** se invoca `compareTo`, `isGreaterThan` o cualquier otra comparación entre ambos
- **ENTONCES** se lanza `CurrencyMismatchException`

### Requisito: Reparto proporcional sin pérdida de residuo (`allocate`)

`Money.allocate(pesos)` DEBE repartir el importe total en partes proporcionales a una lista de pesos
enteros positivos, distribuyendo el residuo por el método del resto mayor. La suma de las partes
devueltas DEBE ser exactamente igual al importe total. El sistema NO DEBE ofrecer una operación de
división que descarte el residuo de forma implícita. `allocate` DEBE rechazar una lista de pesos
vacía, con algún peso negativo, o con todos los pesos en cero.

#### Escenario: Reparto con residuo por el método del resto mayor

- **DADO** `Money.of("100.00", CurrencyCode.HNL)` y los pesos `[1, 1, 1]`
- **CUANDO** se invoca `allocate([1, 1, 1])`
- **ENTONCES** el resultado es `[33.34, 33.33, 33.33]` en HNL, y la suma de las tres partes es
  exactamente `100.00`

#### Escenario: Reparto exacto sin residuo

- **DADO** `Money.of("90.00", CurrencyCode.HNL)` y los pesos `[1, 1, 1]`
- **CUANDO** se invoca `allocate([1, 1, 1])`
- **ENTONCES** el resultado es `[30.00, 30.00, 30.00]` en HNL

#### Escenario: Rechazo de pesos inválidos

- **DADO** `Money.of("100.00", CurrencyCode.HNL)`
- **CUANDO** se invoca `allocate` con una lista vacía, con un peso negativo, o con todos los pesos en
  cero
- **ENTONCES** la operación falla en cada uno de los tres casos y no se construye ningún reparto

#### Escenario: Propiedad de reparto exacto para cualquier total y cualquier vector de pesos

- **DADO** cualquier importe total no negativo y cualquier vector de pesos enteros positivos de
  longitud uno o más
- **CUANDO** se invoca `allocate` con ese vector sobre ese total
- **ENTONCES** la suma de las partes devueltas es exactamente igual al total, y ninguna parte es
  negativa

### Requisito: Importes negativos como información contable

`Money` DEBE admitir importes negativos sin restricción propia de signo. La restricción de signo
pertenece al tipo de transacción del módulo dueño (por ejemplo, un cargo exige importe positivo), no
a `Money`.

#### Escenario: Aritmética con un importe negativo

- **DADO** `Money.of("-45.50", CurrencyCode.HNL)` y `Money.of("20.00", CurrencyCode.HNL)`
- **CUANDO** se suman con `add`
- **ENTONCES** el resultado es `Money.of("-25.50", CurrencyCode.HNL)`, sin normalizar el signo

#### Escenario: `Money` no ofrece una corrección implícita de signo

- **DADO** el conjunto de operaciones públicas de `Money`
- **CUANDO** se revisa si existe una operación que devuelva el valor absoluto de un importe
- **ENTONCES** no existe tal operación en `Money`; corregir un signo inesperado es responsabilidad
  del módulo dueño de la regla de negocio, nunca un efecto colateral de `Money`

### Requisito: Exposición del importe sin acoplarse al DTO de la API

`Money` DEBE exponer su importe como cadena decimal (`toPlainString`) y su moneda. `Money` NO DEBE
conocer ni depender de ningún tipo de las capas `web`, `application` o `infrastructure`, incluido el
DTO `{ amount: string, currency: string }`.

#### Escenario: Representación decimal exacta

- **DADO** `Money.of("1250.1500", CurrencyCode.HNL)`
- **CUANDO** se invoca `toPlainString`
- **ENTONCES** el resultado conserva el valor y la escala exactos, sin notación científica

#### Escenario: Ida y vuelta sin pérdida de valor

- **DADO** `Money.of("1234.5678", CurrencyCode.HNL)`
- **CUANDO** se obtiene su representación decimal con `toPlainString` y se construye un nuevo
  `Money` a partir de esa cadena y la misma moneda
- **ENTONCES** el `Money` reconstruido es igual al original según `equals`

### Requisito: Coherencia entre operaciones intermedias y el cálculo a escala completa

Toda secuencia de sumas, restas y multiplicaciones por factor decimal de `Money` que no invoque
explícitamente un redondeo a la unidad menor DEBE coincidir exactamente con el resultado de realizar
el mismo cálculo en `BigDecimal` a escala completa sin redondeos intermedios y redondear una sola vez
al final con `HALF_UP`. Ninguna operación intermedia DEBE redondear de forma implícita.

#### Escenario: Propiedad de coherencia con el cálculo a escala completa

- **DADO** cualquier secuencia de sumas, restas y multiplicaciones por factor decimal exacto sobre
  importes de una misma moneda
- **CUANDO** se compara el resultado final obtenido con `Money` contra el resultado de realizar el
  mismo cálculo enteramente en `BigDecimal` sin redondeos intermedios y redondeado una sola vez al
  final con `HALF_UP`
- **ENTONCES** ambos valores coinciden exactamente

### Requisito: Casos de regresión permanentes de ADR-0004

El sistema DEBE mantener como pruebas de regresión permanentes los cuatro casos numéricos
documentados en ADR-0004 §Cumplimiento 5, ejecutados con `Money` en vez de con coma flotante.

#### Escenario: El impuesto que la coma flotante pierde

- **DADO** `Money.of("6.70", CurrencyCode.HNL)`
- **CUANDO** se calcula `percentage(Percentage.of("15"), RoundingMode.HALF_UP)` y se redondea el
  resultado a la escala menor de la moneda con `HALF_UP`
- **ENTONCES** el resultado es `Money.of("1.01", CurrencyCode.HNL)` (el intermedio exacto es
  `1.0050`), nunca `1.00`

#### Escenario: La colegiatura de tres meses

- **DADO** `Money.of("1234.55", CurrencyCode.HNL)`
- **CUANDO** se multiplica por el factor entero exacto `3`
- **ENTONCES** el resultado es exactamente `Money.of("3703.65", CurrencyCode.HNL)`, e igual, según
  `equals`, a un `Money` construido directamente desde la cadena `"3703.65"`

#### Escenario: La acumulación de mil sumas de `0.1`

- **DADO** `Money.zero(HNL)`
- **CUANDO** se le suma `Money.of("0.1", CurrencyCode.HNL)` de forma consecutiva mil veces
- **ENTONCES** el resultado final es exactamente `Money.of("100.00", CurrencyCode.HNL)`, sin
  desviación

#### Escenario: El caso clásico

- **DADO** `Money.of("0.1", CurrencyCode.HNL)` y `Money.of("0.2", CurrencyCode.HNL)`
- **CUANDO** se suman con `add`
- **ENTONCES** el resultado es exactamente `Money.of("0.3", CurrencyCode.HNL)`

### Requisito: Jerarquía de errores de dominio de `kernel`

El sistema DEBE definir en `kernel` una clase abstracta y no comprobada `DomainException` (extiende
`RuntimeException`) con un método `code()` que expone un código estable legible por máquina. Toda
excepción de dominio de `kernel`, incluida `CurrencyMismatchException`, DEBE heredar de
`DomainException` y DEBE declarar su propio código estable a través de `code()`.

#### Escenario: `CurrencyMismatchException` hereda de `DomainException`

- **DADO** `CurrencyMismatchException`
- **CUANDO** se verifica su jerarquía y su `code()`
- **ENTONCES** hereda de `DomainException`, es una excepción no comprobada, y `code()` devuelve un
  identificador estable propio (por ejemplo, `currency-mismatch`)

#### Escenario: `DomainException` no se instancia directamente

- **DADO** que `DomainException` es abstracta
- **CUANDO** se intenta instanciarla directamente
- **ENTONCES** el código no compila; solo sus subclases concretas son instanciables

#### Escenario: Las excepciones de `kernel` no obligan a declararse ni a capturarse

- **DADO** un método que lanza una subclase de `DomainException`
- **CUANDO** otro código la invoca sin declarar `throws` ni capturarla
- **ENTONCES** el código compila igual, porque `DomainException` es una excepción no comprobada
