# ADR-0004: PostgreSQL como motor de datos y representación monetaria exacta

- **Estado:** Aceptado
- **Fecha:** 2026-09-09
- **Decisores:** Propietario del producto y arquitecto
- **Contexto técnico:** Esquema completo de PostgreSQL, módulo de núcleo del backend (objeto de valor `Money`, sustituye a `packages/domain`), `modules/ledger`, `modules/payments`, `modules/charges`, `modules/invoicing`, `modules/collections`, migraciones de Flyway, capa de presentación de importes en `apps/admin-web` y `apps/portal-web`.
- **Revisión:** 2026-09-14. Alineado con ADR-0013 (backend en Java con Spring Boot). La representación de `Money` dentro de la aplicación cambia de un `bigint` de unidades menores a un `BigDecimal` normalizado a escala cuatro con moneda explícita. La decisión sobre PostgreSQL (`NUMERIC(14,4)` y moneda junto a cada importe) no cambia.

## Contexto y problema

CONFIA calcula colegiaturas, descuentos por beca, impuesto sobre ventas, recargos por mora,
aplicaciones parciales de pago, notas de crédito y cierres de caja. Cada uno de esos cálculos
produce un número que alguien va a reclamar y que un auditor va a verificar.

Hay dos decisiones acopladas que deben tomarse juntas:

1. **Qué motor de base de datos** sostiene el libro mayor, y si soporta los invariantes que este
   dominio necesita declarados en el esquema y no solo en el código.
2. **Cómo se representa el dinero** en tránsito por la aplicación. El backend es Java (ver
   ADR-0013), que ofrece `BigDecimal` como decimal exacto del lenguaje, pero **un decimal exacto por
   sí solo no impide mezclar monedas ni usar `double` por descuido**. Los clientes web reciben
   importes calculados por el servidor y no hacen aritmética monetaria.

Las fuerzas:

- **El error de redondeo no se detecta, se acumula.** Un centavo de diferencia por transacción no
  produce una alerta: produce un cierre de caja descuadrado tres semanas después, sin forma de
  saber cuál de las novecientas transacciones lo causó.
- **La coma flotante binaria no puede representar exactamente la mayoría de los importes decimales.**
  No es un problema de precisión insuficiente: es que `0.1` no existe en base dos, igual que `1/3`
  no existe en base diez.
- **El régimen fiscal exige exactitud declarada.** El impuesto de una factura debe reproducirse
  exactamente si alguien recalcula. Un importe fiscal que difiere en un centavo al recalcularse es
  un documento cuestionable.
- **Los invariantes financieros deben vivir en el esquema.** Con un desarrollador solo, una
  restricción de base de datos vale más que una prueba, porque protege también contra el script de
  corrección de emergencia escrito a las once de la noche.

Si no se decide, el resultado por defecto es `double` en el backend, `number` de JavaScript en el
cliente y `float` en la base de datos, porque es lo que hace funcionar la primera pantalla. El costo aparece en el primer cierre de caja.

## Factores de decisión

| Factor | Peso | Justificación |
|---|---|---|
| Exactitud decimal sin pérdida | Muy alto | Es el requisito fundacional. Sin esto, nada más importa. |
| Transacciones ACID con aislamiento seleccionable | Muy alto | `SERIALIZABLE` para cierre de caja y correlativo fiscal. Ver ADR-0010. |
| Invariantes declarables en el esquema | Muy alto | `CHECK`, `EXCLUDE`, restricciones diferibles, columnas generadas. |
| Seguridad a nivel de fila en el motor | Muy alto | Requisito de ADR-0003. Solo PostgreSQL lo ofrece de forma madura entre los candidatos. |
| Detección temprana del error de tipo | Alto | Un error de dinero debe romper la compilación, no aparecer en producción. |
| Recuperación a un punto en el tiempo | Alto | Requisito de continuidad, brecha B10. |
| Costo de licencia y de operación | Alto | Presupuesto mínimo, un desarrollador. |
| Rendimiento de agregaciones sobre el libro mayor | Medio | Cientos de miles de asientos al año, no millones. |

## Opciones consideradas

### Motor de datos

#### Opción A: PostgreSQL 16 o superior

**Ventajas.**

- `NUMERIC` de precisión arbitraria, con aritmética decimal exacta, no aproximada.
- Transacciones ACID con `READ COMMITTED`, `REPEATABLE READ` y `SERIALIZABLE` reales, este último
  con detección de anomalías de serialización.
- `CHECK` con expresiones complejas, restricciones `EXCLUDE` con `btree_gist` (útil para impedir
  solapamiento de vigencias de tarifas), restricciones diferibles hasta el fin de transacción
  (indispensables para verificar el cuadre de una transacción del libro mayor, ver ADR-0007),
  columnas generadas e índices parciales y de expresión.
- **Seguridad a nivel de fila** madura, que es el mecanismo sobre el que descansa el aislamiento
  entre encargados de ADR-0003.
- Recuperación a un punto en el tiempo mediante archivado de WAL.
- `SELECT ... FOR UPDATE` y funciones de bloqueo de aviso, base del control de concurrencia de
  ADR-0010.
- Sin licencia. Disponible en cualquier proveedor y ejecutable en un host modesto.

**Desventajas.**

- La aritmética `NUMERIC` es más lenta que la de enteros o coma flotante. En este volumen es
  irrelevante, pero es un costo real en agregaciones muy grandes.
- Requiere ajuste consciente de `autovacuum` y de parámetros de memoria. Un desarrollador solo
  necesita aprender lo suficiente de operación de PostgreSQL, y eso es tiempo.
- El aislamiento `SERIALIZABLE` produce errores de serialización que la aplicación debe reintentar.
  Es trabajo adicional, obligatorio y fácil de omitir.

#### Opción B: MySQL o MariaDB

**Ventajas.** `DECIMAL` exacto, transacciones ACID con InnoDB, enorme disponibilidad de
alojamiento barato en la región, y la mayor familiaridad promedio entre desarrolladores locales.

**Desventajas en este contexto.**

- **No tiene seguridad a nivel de fila.** Esto solo ya lo descarta: el aislamiento entre encargados
  de ADR-0003 tendría que depender por completo del código de aplicación, que es precisamente lo que
  la arquitectura rechaza. Emularlo con vistas y funciones definidas por el invocador es frágil y
  fácil de eludir.
- Sin restricciones `EXCLUDE`, de modo que impedir el solapamiento de vigencias de tarifas (brecha
  B3) exige lógica de aplicación en lugar de una garantía del motor.
- Restricciones diferibles no disponibles, lo que complica verificar el cuadre de una transacción
  del libro mayor dentro de la propia transacción.
- `SERIALIZABLE` implementado por bloqueo, con más contención que la detección de anomalías de
  PostgreSQL.
- Históricamente permisivo con datos inválidos según el modo SQL configurado. En un sistema
  financiero, un motor que acepta silenciosamente lo que no debería es un riesgo de diseño.

#### Opción C: MongoDB

**Ventajas.** Esquema flexible, iteración rápida al inicio, escalado horizontal sencillo.

**Desventajas en este contexto.**

- **No tiene un tipo decimal en el modelo de documento por defecto.** `Decimal128` existe y es
  correcto, pero el tipo natural de un número en un documento JSON es coma flotante de doble
  precisión, y ese es el valor que se guardará por accidente en cuanto alguien no sea explícito.
- La ausencia de esquema es exactamente lo contrario de lo que necesita un sistema donde los
  invariantes deben estar declarados y ser imposibles de violar.
- Sin `CHECK`, sin claves foráneas reales, sin restricciones diferibles: **todos los invariantes
  financieros pasarían a depender del código de aplicación.**
- El libro mayor de doble partida es un modelo relacional por naturaleza: transacciones, asientos,
  cuentas y agregaciones por rango de fecha. Modelarlo en documentos obliga a duplicar datos o a
  hacer uniones en la aplicación.
- Las transacciones multidocumento existen, pero son la excepción del modelo y no su camino
  principal, mientras que en CONFIA son la operación normal.

Se descarta sin más análisis. Un almacén de documentos sin esquema para un libro mayor contable es
la elección incorrecta por razones estructurales, no de preferencia.

### Representación monetaria en la aplicación

#### Opción R1: Coma flotante binaria (`double` o `float` de Java)

Se descarta. La sección siguiente explica por qué con números concretos.

#### Opción R2: `BigDecimal` sin envoltura

**Ventajas.** Aritmética decimal exacta, nativa del lenguaje, con escala configurable y modos de
redondeo declarados.

**Desventajas.** Es un tipo genérico de número, no un tipo de dinero. No lleva moneda, de modo que
sumar lempiras con dólares compila y se ejecuta sin error. Nada impide pasar un `BigDecimal` de otra
naturaleza, como una tasa o un porcentaje, donde se espera un importe. Además arrastra dos trampas
propias: `equals` distingue escala (`1.0` no es igual a `1.00`) y `new BigDecimal(double)` hereda el
error de la coma flotante. Resuelve la aritmética, no el modelado.

#### Opción R3: Objeto de valor `Money` sobre `BigDecimal` a escala cuatro con moneda

Un tipo propio en el módulo de núcleo del backend que encapsula un `BigDecimal` normalizado a escala
cuatro y una moneda ISO 4217 explícita, con constructores validados, aritmética total (sin división
que pierda residuo sin declararlo) y una operación de reparto que garantiza que la suma de las
partes iguale el total.

**Ventajas.** Aritmética decimal exacta garantizada por el lenguaje, imposible por construcción
sumar monedas distintas, imposible construir un importe desde `double` o `float`, igualdad definida
sobre importe normalizado y moneda, y el redondeo es una operación explícita con política declarada
en lugar de un efecto colateral.

**Desventajas.** Hay que escribirlo y probarlo. Las trampas de `BigDecimal` siguen existiendo
alrededor de `Money` y hay que prohibirlas con reglas automatizadas. Y todo el equipo (aquí, una
persona) debe usarlo sin excepciones, lo cual exige que la herramienta lo imponga.

### Por qué la coma flotante es inaceptable: ejemplo numérico concreto

Los valores siguientes se obtuvieron ejecutando Node.js y se muestran con la sintaxis de JavaScript.
Son propios de la coma flotante binaria IEEE 754 de doble precisión y se reproducen en cualquier
entorno que la use, incluidos `double` de Java y `number` de JavaScript. Solo la forma de imprimir
el resultado puede variar entre lenguajes.

**Caso 1: el impuesto que pierde un centavo.**

Un concepto de pago de **L 6.70** con impuesto sobre ventas del quince por ciento. El impuesto
correcto es `6.70 × 0.15 = 1.0050`, que redondeado a dos decimales con redondeo comercial da
**L 1.01**.

```js
6.70 * 0.15                       // 1.005     parece correcto
Math.round(6.70 * 0.15 * 100)/100 // 1         devuelve L 1.00
```

El valor `1.005` que se imprime no es `1.005`: es el número binario más cercano, que resulta ser
ligeramente **menor**. Al multiplicarlo por cien se obtiene `100.49999999999999`, y `Math.round`
redondea hacia abajo. **El sistema factura un centavo menos de impuesto del que corresponde, y lo
hace de forma consistente, no aleatoria.** Multiplicado por miles de líneas de factura al año, eso
es una diferencia declarada ante la autoridad tributaria que nadie puede explicar.

**Caso 2: la colegiatura de tres meses.**

```js
1234.55 * 3   // 3703.6499999999996
```

El total correcto es `3703.65`. El valor almacenado no es igual a `3703.65`, de modo que una
comparación de igualdad contra el importe pagado falla, y el sistema declara pendiente una deuda
que ya está cancelada.

**Caso 3: el descuadre que se acumula.**

```js
let s = 0;
for (let i = 0; i < 1000; i++) s += 0.1;
s   // 99.9999999999986, no 100
```

Mil operaciones bastan para perder `1.4 × 10⁻¹²`. Un sistema escolar procesa fácilmente cien mil
movimientos al año entre cargos, pagos, becas y recargos. El error no cancela: se acumula en la
dirección que impongan los valores concretos. Cuando el cierre de caja no cuadra, no hay forma de
identificar qué transacción lo causó, porque **ninguna transacción individual está mal.**

**Caso 4: el clásico.**

```js
0.1 + 0.2   // 0.30000000000000004
8.7 * 3     // 26.099999999999998
```

**Conclusión.** El problema no es que la coma flotante sea imprecisa: es que es **exacta en base
dos y el dinero es decimal.** Ninguna cantidad de decimales de precisión arregla eso, igual que
ninguna cantidad de decimales representa exactamente un tercio en base diez. La única solución
correcta es no usar coma flotante para dinero, en ningún punto del sistema.

## Decisión

**Se adopta PostgreSQL 16 o superior como motor de datos, con `NUMERIC(14,4)` para todo importe
monetario y una columna de moneda junto a cada importe. En la aplicación, todo importe se
representa con el objeto de valor `Money` del módulo de núcleo del backend, que encapsula un
`BigDecimal` normalizado a escala cuatro y una moneda ISO 4217 explícita. Queda prohibido el tipo de
coma flotante para importes en cualquier capa del sistema.**

PostgreSQL gana por dos factores de peso muy alto que ningún otro candidato satisface a la vez:
**seguridad a nivel de fila madura** (sin la cual ADR-0003 no se puede implementar) e **invariantes
financieros declarables en el esquema** mediante `CHECK`, `EXCLUDE` y restricciones diferibles. La
exactitud decimal la ofrecen también MySQL y MariaDB; el aislamiento y los invariantes declarados,
no.

`Money` gana sobre `BigDecimal` sin envoltura porque el problema no es solo aritmético
sino de modelado: **un importe sin moneda no es dinero, es un número.** El sistema tiene ambición
multi-moneda declarada (ver ADR-0011) y debe hacer imposible por construcción sumar lempiras con
dólares.

### Especificación de la representación

**En PostgreSQL.**

- Todo importe monetario se almacena como `NUMERIC(14,4)`: diez dígitos enteros y cuatro decimales.
  El rango cubre hasta `9 999 999 999.9999`, muy por encima de cualquier importe institucional
  plausible, y los cuatro decimales permiten representar resultados intermedios de porcentajes sin
  redondear antes de tiempo.
- **Junto a cada importe hay una columna de moneda**, `currency CHAR(3)`, con `CHECK` contra el
  conjunto de códigos ISO 4217 habilitados. Nunca una moneda global de la fila, del documento ni de
  la institución: **la moneda pertenece al importe.**
- Los importes que no pueden ser negativos lo declaran con `CHECK (amount >= 0)`. Los asientos del
  libro mayor sí admiten signo según su naturaleza, con las restricciones descritas en ADR-0007.
- Se prohíbe explícitamente el uso de `REAL`, `DOUBLE PRECISION`, `FLOAT` y `MONEY` para importes.
  El tipo `MONEY` de PostgreSQL se descarta además porque su escala depende de una configuración
  regional del servidor, lo cual convierte un cambio de configuración en un cambio de datos.

**En Java.**

`Money` es un objeto de valor inmutable en el módulo de núcleo del backend, que no declara
dependencias fuera del JDK (ADR-0013), con la forma conceptual siguiente:

- Importe: `BigDecimal` **normalizado a escala cuatro**, coherente con `NUMERIC(14,4)`. La
  normalización nunca descarta dígitos de forma implícita.
- Moneda: código ISO 4217 explícito y validado contra el conjunto habilitado.
- Constructores explícitos: desde cadena decimal, desde `BigDecimal` y desde el valor `NUMERIC` que
  devuelve el controlador de base de datos. **No existe constructor desde `double` ni desde
  `float`.**
- Igualdad: compara importe normalizado y moneda, **nunca `BigDecimal.equals` sin normalizar**, y
  el código hash es coherente con esa igualdad.
- Aritmética: suma y resta solo entre importes de la misma moneda; multiplicación por un factor
  decimal exacto, con redondeo explícito a escala cuatro con `RoundingMode.HALF_UP` cuando el
  resultado la exceda; comparación total.
- `allocate(ratios)`: reparto proporcional que garantiza que **la suma de las partes es exactamente
  igual al total**, distribuyendo el residuo por el método del resto mayor. Sin esto, dividir un
  cargo de `L 100.00` entre tres cuotas produce tres importes de `L 33.33` y un centavo que
  desaparece.
- No existe operación de división que descarte el residuo de forma implícita.
- Serialización en la frontera de la API como objeto explícito `{ amount: string, currency: string }`,
  con `amount` en notación decimal de cadena. **Nunca como número JSON**, porque un número JSON lo
  reconstruye el cliente como coma flotante de doble precisión y todo el trabajo se pierde en el
  último metro. El OpenAPI generado desde el código declara esa forma, y los tipos y esquemas Zod
  generados en `packages/contracts` la reciben como cadena (ADR-0013).
- **Campos de presentación.** Todo campo de la API destinado a mostrarse al usuario lleva el importe
  ya redondeado por el servidor a la escala menor de la moneda (dos decimales para HNL y USD) con
  `RoundingMode.HALF_UP`, conforme a la política de redondeo de este ADR. La escala interna de cuatro
  decimales no sale del servidor en los campos de presentación. La forma sigue siendo
  `{ amount: string, currency: string }`.

**En los clientes web.** El servidor es la única autoridad sobre el dinero. Los importes llegan
calculados y, en los campos de presentación, ya redondeados por el servidor. El navegador solo
formatea esa cadena con `Intl.NumberFormat`: no redondea, no calcula y no hace aritmética monetaria
(`CLAUDE.md`, reglas 3 y 9).

### Política de redondeo

1. **Los cálculos intermedios no se redondean.** La aritmética opera a escala cuatro de principio a
   fin de la operación.
2. **El redondeo ocurre en un solo punto y de forma explícita**: al emitir un documento fiscal, al
   registrar un asiento en el libro mayor y al presentar un importe al usuario. Los tres ocurren en
   el servidor; el redondeo de presentación se aplica al construir la respuesta de la API, nunca en
   el navegador.
3. **Modo de redondeo: half-up**, es decir, la mitad se redondea alejándose de cero (`1.005` a dos
   decimales da `1.01`). En Java corresponde a `RoundingMode.HALF_UP`, declarado siempre de forma
   explícita. Se elige sobre el redondeo bancario (half-even) porque es el que la
   práctica comercial y la autoridad tributaria hondureña esperan, y porque es el que un padre de
   familia va a reproducir con una calculadora al verificar su recibo. La coherencia con la
   expectativa del usuario y del fisco pesa más aquí que la neutralidad estadística del redondeo
   bancario.
4. **La escala de presentación y de emisión fiscal es la escala menor de la moneda**: dos decimales
   para HNL y USD.
5. **Todo redondeo aplicado queda registrado.** Cuando el redondeo de un documento fiscal produce
   una diferencia respecto de la suma de sus líneas, esa diferencia se asienta explícitamente como
   línea de ajuste por redondeo en el libro mayor. Un centavo sin asiento es un centavo que descuadra.
6. **El reparto usa `allocate`, nunca división seguida de redondeo individual.**

### Por qué la moneda se almacena junto a cada importe

- **Correctitud sobre datos históricos.** Si la moneda viviera en la institución o en la
  configuración global, cambiar esa configuración reinterpretaría retroactivamente todos los
  importes ya registrados. Un cargo emitido en lempiras debe seguir siendo lempiras para siempre,
  con independencia de cualquier configuración futura.
- **La ambición internacional está declarada.** Cuando exista una segunda institución en otra
  moneda, o un concepto cobrado en dólares dentro de una institución hondureña, el modelo ya lo
  soporta sin migración.
- **Hace verificable la prohibición de mezclar monedas.** Una restricción de base de datos puede
  exigir que todos los asientos de una transacción del libro mayor compartan moneda. Sin la columna,
  esa restricción no se puede escribir.
- **Retrofitear la columna es caro.** Añadirla después obliga a rellenar cada fila histórica con un
  valor asumido, reescribir cada consulta y cada índice, y aceptar que las filas anteriores a la
  migración tienen una moneda inferida y no declarada.

El costo es una columna de tres caracteres por importe. Es, con diferencia, el seguro más barato
del sistema.

## Consecuencias

**Positivas:**

- Aritmética exacta de extremo a extremo: base de datos, dominio, API y presentación.
- Imposible por construcción sumar importes de monedas distintas.
- Los invariantes financieros se declaran en el esquema, de modo que también protegen contra
  correcciones manuales y contra scripts de mantenimiento.
- La seguridad a nivel de fila de PostgreSQL habilita el aislamiento de ADR-0003.
- `SERIALIZABLE` real y bloqueo explícito habilitan los controles de concurrencia de ADR-0010.
- Recuperación a un punto en el tiempo con archivado de WAL.
- El modelo multi-moneda existe desde la primera migración sin costo operativo hoy.

**Negativas y costos aceptados:**

- Hay que **escribir y probar `Money`** antes de poder implementar cualquier funcionalidad
  financiera. Es trabajo inicial que no produce pantallas visibles.
- `BigDecimal` tiene trampas conocidas: `equals` distingue escala (`1.0` no es igual a `1.00`) y
  `new BigDecimal(double)` hereda el error de la coma flotante. Se previenen con la normalización
  en el constructor de `Money` y con reglas de ArchUnit, no con buena voluntad.
- El valor `NUMERIC` que entrega el controlador de base de datos es un `BigDecimal`, no un `Money`.
  Hay que mantener una conversión explícita entre ambos en todo el código de acceso a datos, con el
  riesgo de que alguien la salte. Su forma concreta depende de la estrategia de acceso a datos,
  pendiente de ADR específico (ver ADR-0013).
- La aritmética `NUMERIC` es más lenta que la de enteros. Las agregaciones grandes del libro mayor
  necesitarán índices bien elegidos y, posiblemente, saldos cacheados (ver ADR-0007).
- El aislamiento `SERIALIZABLE` obliga a implementar reintento ante error de serialización en cada
  caso de uso que lo utilice.
- La ergonomía de escribir `Money` en cada cálculo es peor que escribir `a + b`. Es fricción diaria
  aceptada a cambio de exactitud.

**Riesgos y mitigaciones:**

| Riesgo | Mitigación |
|---|---|
| Alguien usa `double` o `float` para un importe en un cálculo puntual | Reglas de ArchUnit que prohíben campos, parámetros y retornos `double` o `float` en tipos monetarios, y prohíben `new BigDecimal(double)` y `BigDecimal.valueOf(double)` en todo el código de producción. `Money` no ofrece constructor desde `double` ni `float`, de modo que el compilador rechaza el intento directo. |
| Alguien compara importes con `BigDecimal.equals` | Regla de ArchUnit que prohíbe `BigDecimal.equals` fuera de `Money`, más normalización de escala en el constructor. |
| El frontend calcula con un importe recibido de la API | Regla de ESLint en el frontend que prohíbe operadores aritméticos, `parseFloat`, `Number()` y `toFixed()` sobre campos monetarios. El cliente presenta importes, no los calcula. |
| Una migración crea una columna monetaria como `DOUBLE PRECISION` | Prueba de integración que consulta `information_schema.columns` y falla si una columna cuyo nombre coincide con el patrón monetario no es `numeric` con precisión 14 y escala 4. |
| Un importe se guarda sin su columna de moneda | La misma prueba de esquema exige que toda columna monetaria tenga una columna de moneda hermana con `CHECK` de ISO 4217. |
| Un importe se serializa como número JSON en la API | Prueba de contrato sobre el OpenAPI generado que falla si algún campo monetario está tipado como `number`. Verificación adicional en pruebas de extremo a extremo sobre la respuesta real. |
| Redondeo aplicado en un punto intermedio | Pruebas de propiedad con jqwik sobre `Money`: para cualquier reparto, la suma de las partes es exactamente el total; para cualquier secuencia de operaciones, el resultado coincide con el calculado a escala completa y redondeado una sola vez al final. |
| Errores de borde no cubiertos en `Money` | Pruebas de mutación con PIT sobre el módulo de núcleo y el paquete `domain` de cada módulo, umbral mínimo de ochenta. Un operador de redondeo mutado que no mate ninguna prueba señala una prueba que no verifica nada. Ver ADR-0008. |
| Error de serialización bajo `SERIALIZABLE` sin reintento | Componente transversal de reintento con retroceso, más prueba de concurrencia que fuerza el conflicto y afirma que la operación termina correctamente. Ver ADR-0010. |

## Cumplimiento y verificación

1. **Verificación del esquema, automatizada.** Prueba de integración contra PostgreSQL real
   (Testcontainers) que consulta `information_schema.columns` y afirma:
   - ninguna columna del esquema tiene tipo `real`, `double precision` ni `money`;
   - toda columna cuyo nombre coincida con el patrón monetario acordado (`*_amount`, `amount`,
     `*_total`, `debit`, `credit`, `balance*`) es `numeric(14,4)`;
   - cada una de esas columnas tiene una columna de moneda asociada con `CHECK` de ISO 4217.
2. **Prohibición de coma flotante en el dominio financiero.** Reglas de ArchUnit que se ejecutan en
   cada construcción y prohíben: campos, parámetros y retornos `double` o `float` en tipos
   monetarios; `new BigDecimal(double)` y `BigDecimal.valueOf(double)` en todo el código de
   producción; y `BigDecimal.equals` fuera de `Money` (ADR-0013). En el frontend, la regla de ESLint
   `confia/no-float-money`, severidad `error`, prohíbe operadores aritméticos, `parseFloat`,
   `Number()` y `toFixed()` sobre campos monetarios.
3. **Aritmética confinada al núcleo.** `Money` vive en el módulo de núcleo, que es una frontera de
   compilación de Maven sin dependencias fuera del JDK. Toda aritmética monetaria se ofrece como
   operación de `Money`; el código fuera del módulo de núcleo que opere directamente sobre el
   `BigDecimal` de un importe se rechaza en revisión (`CLAUDE.md`, regla 3). Ver ADR-0002.
4. **Contrato de API.** Prueba que analiza el OpenAPI generado por springdoc-openapi y falla si
   algún esquema de campo monetario no es el objeto `{ amount: string, currency: string }`.
5. **Pruebas de `Money` con casos límite obligatorios.** Cada regla financiera exige pruebas de
   cero, negativo, redondeo en el punto medio exacto, moneda distinta y el máximo representable
   (`CLAUDE.md`, sección de pruebas). Los cuatro casos numéricos documentados en este ADR son
   pruebas unitarias de regresión permanentes de `Money`: `6.70 × 0.15`, `1234.55 × 3`, la
   acumulación de mil sumas de `0.1`, y `0.1 + 0.2`.
6. **Pruebas de propiedad del reparto.** Con jqwik: para todo total y todo vector de proporciones,
   la suma de las partes devueltas por `allocate` es exactamente igual al total, y ninguna parte es
   negativa cuando el total no lo es.
7. **Cobertura y mutación.** El módulo de núcleo y el paquete `domain` de cada módulo exigen
   noventa y cinco por ciento de cobertura, medida con JaCoCo, y umbral de mutación de ochenta con
   PIT.
8. **Trabajo nocturno de integridad.** Verifica que ninguna transacción del libro mayor mezcle
   monedas y que toda transacción cuadre en la moneda de la transacción. Ver ADR-0007.
9. **Revisión de migraciones.** Toda migración de Flyway que cree o altere una columna monetaria se revisa con
   el criterio explícito de este ADR, y las migraciones nunca se editan una vez aplicadas en
   producción (`CLAUDE.md`, convenciones de Git).
10. **Escala de los campos de presentación.** Prueba de contrato o de integración que ejercita
    las respuestas reales de la API y falla si algún campo de importe de presentación trae más
    decimales que la escala menor de su moneda (dos para HNL y USD). Garantiza que la escala
    interna de cuatro decimales no sale del servidor en un campo destinado a mostrarse.

## Referencias

- `docs/01-arquitectura.md`, secciones 3.2 y 6
- `CLAUDE.md`, sección Dinero, reglas 1 a 5
- `docs/10-analisis-de-brechas.md`, brechas B1 y M2
- ADR-0001: stack tecnológico (reemplazado por ADR-0013)
- ADR-0003: separación entre administración y portal
- ADR-0007: libro mayor de doble partida
- ADR-0008: estrategia de pruebas
- ADR-0010: idempotencia y concurrencia financiera
- ADR-0011: internacionalización y multi-moneda
- ADR-0013: backend en Java con Spring Boot
