---
name: confia-money-rules
description: Reglas obligatorias de dinero en CONFIA. Se dispara al tocar importes, precios, tarifas, montos, totales, subtotales, saldos, descuentos, becas, recargos, mora, impuesto sobre ventas, redondeo, conversión de moneda, columnas NUMERIC, BigDecimal, el objeto Money, formateo de moneda o cualquier cálculo aritmético cuyo resultado sea dinero.
---

# Reglas de dinero de CONFIA

Este sistema mueve dinero real de familias. Un error de un centavo repetido quinientas veces al mes
es un descuadre que alguien tendrá que explicar ante un auditor. Estas reglas no son estilo: son
defectos cuando se violan.

Fuentes de verdad: `CLAUDE.md` (reglas 1 a 5), `docs/01-arquitectura.md` sección 6 y
`docs/adr/ADR-0004-postgresql-y-representacion-monetaria.md`. Si esta skill los contradice, ganan
ellos. Los ejemplos de código son ilustrativos: la API exacta de `Money` se fija al implementarlo en
F0.

## 1. Regla cero: la coma flotante está prohibida para dinero

**Nunca representes un importe con `double` ni `float` en Java, ni hagas aritmética con `number`
de JavaScript sobre un importe en el frontend.**

La coma flotante binaria no puede representar `0.1` ni `0.2` de forma exacta, en ningún lenguaje:

```java
0.1 + 0.2                                    // 0.30000000000000004
1250.15 * 3                                  // 3750.4500000000003
new BigDecimal(0.1)                          // 0.1000000000000000055511151231257827021181583404541015625
new BigDecimal("1.0").equals(new BigDecimal("1.00"))  // false: equals distinguishes scale
```

Las dos últimas líneas son las trampas propias de `BigDecimal`: construirlo desde un `double`
hereda el error de la coma flotante, y `equals` compara también la escala.

Con una colegiatura de `L 1,250.15` y trescientos estudiantes, ese error se acumula y el total del
libro de ventas deja de cuadrar con la suma de las facturas. No hay forma de justificar eso.

**Usa siempre el objeto de valor `Money` del módulo `kernel`.** `BigDecimal` normalizado a escala
cuatro, coherente con `NUMERIC(14,4)`, más una moneda ISO 4217 explícita. Inmutable.

```java
// kernel/Money.java  (conceptual shape; exact API fixed in F0)
public final class Money {

    private static final int SCALE = 4;

    private final BigDecimal amount;   // always scale 4
    private final CurrencyCode currency;

    private Money(BigDecimal amount, CurrencyCode currency) {
        // setScale(int) without a rounding mode throws ArithmeticException instead of
        // discarding digits: normalization never loses information silently.
        this.amount = amount.setScale(SCALE);
        this.currency = Objects.requireNonNull(currency);
    }

    /** Entry point for external decimal strings, such as API input. */
    public static Money of(String decimal, CurrencyCode currency) {
        return new Money(new BigDecimal(decimal), currency);
    }

    /** Entry point for NUMERIC values returned by the database driver. */
    public static Money of(BigDecimal amount, CurrencyCode currency) {
        return new Money(amount, currency);
    }

    public static Money zero(CurrencyCode currency) {
        return new Money(BigDecimal.ZERO, currency);
    }

    // There is deliberately no factory that accepts double or float.
}
```

### Correcto e incorrecto

```java
// WRONG: the amount is a double and rounding is implicit
double applyDiscount(double amount, double percent) {
    return amount - amount * (percent / 100);
}

// WRONG: exact decimal but no currency. Adding lempiras to dollars compiles.
BigDecimal total(List<BigDecimal> lines) {
    return lines.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
}

// CORRECT
Money applyDiscount(Money amount, Percentage percent) {
    return amount.subtract(amount.percentage(percent, RoundingMode.HALF_UP));
}
```

### Verificación automatizada

Estas reglas no dependen de buena voluntad (ADR-0004, verificación 2; ADR-0013):

- Reglas de ArchUnit en cada construcción que prohíben campos, parámetros y retornos `double` o
  `float` en tipos monetarios; `new BigDecimal(double)` y `BigDecimal.valueOf(double)` en todo el
  código de producción; y `BigDecimal.equals` fuera de `Money`.
- `Money` no ofrece constructor desde `double` ni `float`, así que el compilador rechaza el intento
  directo.
- En el frontend, la regla de ESLint `confia/no-float-money` prohíbe operadores aritméticos,
  `parseFloat`, `Number()` y `toFixed()` sobre campos monetarios.

## 2. Persistencia

- En PostgreSQL, todo importe es `NUMERIC(14,4)`. Nunca `float8`, `real`, `double precision` ni
  `money`.
- La escala 4 existe para permitir cálculos intermedios de porcentaje sin perder precisión. La
  presentación y la emisión fiscal usan la escala menor de la moneda (2 decimales para HNL y USD).
- Toda columna de importe lleva al lado su propia columna de moneda (`currency CHAR(3)` con
  `CHECK` de ISO 4217). Nunca un importe suelto ni una moneda global de la fila o del documento.
- Toda columna de importe lleva `CHECK` cuando el signo está restringido por el dominio.

```sql
CREATE TABLE ledger_entry (
    -- ...
    amount    NUMERIC(14,4) NOT NULL,
    currency  CHAR(3)       NOT NULL,
    CONSTRAINT ledger_entry_currency_iso CHECK (currency IN ('HNL', 'USD')),
    CONSTRAINT ledger_entry_amount_positive CHECK (amount > 0)
);
```

El controlador de base de datos devuelve un `NUMERIC` como `BigDecimal`. Conviértelo a `Money` en
el borde del adaptador. **Un `BigDecimal` suelto que represente un importe no cruza hacia la capa
de aplicación ni hacia el dominio.** La conversión la hace un único convertidor compartido que
transforma el par `NUMERIC(14,4)` más moneda en `Money` y viceversa (ADR-0015, regla 5). Ningún
repositorio de jOOQ construye un importe por su cuenta.

```java
// infrastructure/persistence: record to domain. Illustrative; MoneyConverter and the generated
// LEDGER_ENTRY table names are fixed in F0 (ADR-0015).
Money amount = MoneyConverter.toMoney(record.get(LEDGER_ENTRY.AMOUNT), record.get(LEDGER_ENTRY.CURRENCY));
```

Una prueba de integración consulta `information_schema.columns` y falla si una columna monetaria no
es `numeric(14,4)` o no tiene su columna de moneda hermana (ADR-0004, verificación 1).

## 3. Dónde vive la aritmética

**Toda operación aritmética sobre dinero es una operación de `Money` o una regla del paquete
`domain` del módulo que la posee.** Sin excepciones. El código fuera de `kernel` que opere
directamente sobre el `BigDecimal` de un importe se rechaza en revisión.

| Lugar | Puede |
|---|---|
| `Money` (módulo `kernel`) | Sumar, restar, multiplicar por un factor decimal, aplicar porcentaje, redondear, comparar, repartir |
| Paquete `domain` del módulo dueño | Reglas de negocio sobre `Money`: mora, impuestos, imputación de pagos, becas |
| `application` | Orquestar llamadas al dominio y persistir el resultado |
| `infrastructure` | Convertir entre `Money` y la representación de base de datos |
| `web` | Serializar `Money` hacia un DTO, pidiendo a `Money` el redondeo de presentación |
| Frontend | Formatear para mostrar con `Intl.NumberFormat`. Nada más |

```java
// WRONG: arithmetic in the use case
BigDecimal total = charge.amount().subtract(discount.amount()).add(tax);
```

```tsx
// WRONG: arithmetic in React
const totalLabel = `L ${items.reduce((a, i) => a + Number(i.amount), 0).toFixed(2)}`;
```

```java
// CORRECT: the domain computes, the application orchestrates
ChargeSettlement settlement = ChargeSettlement.calculate(charge, scholarship, taxRate);
ledger.record(settlement.toTransaction());
```

Si una pantalla necesita una vista previa de un cálculo (por ejemplo, la mora estimada), se expone
un endpoint que la calcula en el servidor. El navegador nunca la calcula.

## 4. Orden de operaciones

El orden importa porque cada paso redondea. **Este es el único orden permitido:**

1. **Base**: precio de la tarifa vigente por cantidad.
2. **Descuento comercial**: se resta de la base.
3. **Beca**: se aplica sobre la base ya descontada, nunca sobre el precio de lista.
4. **Base imponible**: resultado del paso 3, redondeado a la escala menor de la moneda.
5. **Impuesto**: se calcula sobre la base imponible, con la tasa vigente a la fecha de emisión.
6. **Total**: base imponible más impuesto.

```java
// CORRECT
Money base = unitPrice.multiply(quantity);
Money afterDiscount = base.subtract(base.percentage(discountPercent, RoundingMode.HALF_UP));
Money afterScholarship = afterDiscount.subtract(
        afterDiscount.percentage(scholarshipPercent, RoundingMode.HALF_UP));
Money taxableBase = afterScholarship.roundToMinorUnit(RoundingMode.HALF_UP);
Money tax = taxableBase.percentage(taxRate.value(), RoundingMode.HALF_UP).roundToMinorUnit(RoundingMode.HALF_UP);
Money total = taxableBase.add(tax);

// WRONG: tax before the scholarship. It charges tax on money the family does not pay.
Money withTax = base.add(base.percentage(taxRate.value(), RoundingMode.HALF_UP));
Money wrongTotal = withTax.subtract(withTax.percentage(scholarshipPercent, RoundingMode.HALF_UP));
```

Cuando un descuento se define como importe fijo y no como porcentaje, se resta directamente y
**nunca puede dejar la base negativa**: si el descuento excede la base, el resultado es cero y se
registra el excedente no aplicado.

## 5. Redondeo

- Modo: **medio hacia arriba**, `RoundingMode.HALF_UP` de `java.math`, alejándose de cero en el
  punto medio (`1.005` a dos decimales da `1.01`). Se declara explícitamente en cada llamada, jamás
  se asume. Ninguna operación de `Money` que pueda redondear tiene un modo por defecto.
- Los cálculos intermedios conservan la escala 4. Cuando un resultado la excede, se redondea a
  escala 4 con `HALF_UP` de forma explícita, nunca descartando dígitos en silencio.
- El redondeo a la escala menor de la moneda ocurre **solo en el servidor** y en puntos declarados:
  al emitir un documento fiscal (base imponible e impuesto), al registrar un asiento en el libro
  mayor y al presentar un importe al usuario (ADR-0004, política de redondeo).
- **Los campos de presentación salen del servidor ya redondeados** a la escala menor de la moneda.
  El mapeador de `web` pide ese redondeo a `Money`; el navegador solo formatea la cadena recibida.
- **Nunca redondees dentro de un bucle de acumulación.** Suma con escala completa y redondea el
  total una sola vez.
- Todo redondeo de un documento fiscal que produzca diferencia respecto de la suma de sus líneas se
  asienta como línea de ajuste por redondeo. Un centavo sin asiento es un centavo que descuadra.
- Un reparto de un importe entre N destinos usa **`allocate` con reparto del residuo por el método
  del resto mayor**: nunca dividas y multipliques por separado. `Money` no ofrece una división que
  descarte el residuo de forma implícita.

```java
// WRONG: loses or invents cents
Money perChild = total.divide(3);        // does not exist on purpose
Money check = perChild.multiply(3);      // != total

// CORRECT: the remainder is distributed deterministically
List<Money> parts = total.allocate(1, 1, 1); // [33.34, 33.33, 33.33] for 100.00
// invariant: the sum of parts equals total, always
```

## 6. Monedas distintas

**Sumar, restar o comparar dinero de monedas distintas lanza error de dominio.** Nunca convierte de
forma silenciosa.

```java
// CORRECT, inside Money
public Money add(Money other) {
    requireSameCurrency(other); // throws CurrencyMismatchException
    return new Money(amount.add(other.amount), currency);
}
```

La conversión de moneda es una operación explícita, con tasa fechada y trazable, y produce un
asiento propio. Nunca es un efecto colateral de una suma.

```java
// WRONG
Money total = hnlAmount.add(usdAmount.convertToHnl());

// CORRECT
Conversion conversion = ExchangeRate.at(date, CurrencyCode.USD, CurrencyCode.HNL)
        .convert(usdAmount, RoundingMode.HALF_UP);
Money total = hnlAmount.add(conversion.result()); // conversion.rateId() is recorded in the entry
```

## 7. Importes negativos

- `Money` **admite** valores negativos. El signo es información contable legítima: un reverso, una
  nota de crédito o un saldo a favor.
- La restricción de signo pertenece al tipo de transacción, no al objeto de valor. Un cargo exige
  importe positivo; un reverso exige el importe exactamente opuesto al asiento original.
- **Nunca uses el valor absoluto para "arreglar" un signo inesperado.** Un signo inesperado es un
  defecto: detente y falla.

```java
// WRONG
Money amount = Money.of(raw.abs(), CurrencyCode.HNL);

// CORRECT
if (!amount.isPositive()) {
    throw new InvalidChargeAmountException(amount);
}
```

- El saldo a favor se representa como saldo negativo del estudiante, no como una tabla aparte de
  "créditos".

## 8. Comparación

- **Nunca compares importes con `==`, con `BigDecimal.equals` ni con `compareTo` sobre el
  `BigDecimal` crudo.** Usa los métodos de `Money`, que validan la moneda.
- La igualdad de `Money` exige mismo importe normalizado **y** misma moneda, y su código hash es
  coherente con esa igualdad. Como `Money` normaliza a escala 4 en el constructor, `1.0` y `1.00`
  son el mismo `Money`.

```java
// WRONG
if (payment.amount() == charge.amount()) { /* compares references */ }
if (payment.amount().value().equals(charge.amount().value())) { /* scale-sensitive, no currency */ }

// CORRECT
if (payment.amount().equals(charge.amount())) { /* ... */ }
if (payment.amount().isGreaterThan(charge.outstanding())) {
    throw new OverpaymentException();
}
```

Métodos mínimos que expone `Money`: `equals`, `hashCode`, `isZero`, `isPositive`, `isNegative`,
`isGreaterThan`, `isGreaterThanOrEqual`, `isLessThan`, `isLessThanOrEqual`, `compareTo` (que falla
ante monedas distintas).

## 9. Serialización en la API

**Todo importe cruza la API como cadena decimal, acompañado de su moneda. Nunca como número JSON.**

Razón: un cliente JavaScript convierte un número JSON a coma flotante de doble precisión. Un
importe que sale correcto del servidor puede llegar corrompido al cliente. Una cadena atraviesa
cualquier cliente sin pérdida, incluida la futura app móvil.

```jsonc
// WRONG
{ "amount": 1250.15, "currency": "HNL" }

// CORRECT
{ "amount": "1250.15", "currency": "HNL" }
```

En el backend, todo DTO que declare un campo de dinero usa el único DTO de dinero del proyecto, con
la forma `{ amount: string, currency: string }`. Los campos de presentación llevan `amount` ya
redondeado a la escala menor de la moneda; la escala interna de cuatro decimales no sale del
servidor en un campo destinado a mostrarse.

```java
// web/dto: illustrative shape of the single money DTO
public record MoneyDto(String amount, String currency) {

    /** Presentation field: rounded on the server to the currency minor unit. */
    public static MoneyDto forDisplay(Money money) {
        Money rounded = money.roundToMinorUnit(RoundingMode.HALF_UP);
        return new MoneyDto(rounded.toPlainString(), rounded.currency().name());
    }
}
```

El OpenAPI generado declara esa forma, y los tipos y esquemas Zod de `packages/contracts` se generan
con orval a partir de él; nadie los escribe a mano. Una prueba de contrato falla si algún campo
monetario del OpenAPI no es el objeto `{ amount: string, currency: string }`, y otra falla si algún
campo de presentación trae más decimales que la escala menor de su moneda (ADR-0004,
verificaciones 4 y 10).

En el frontend, el DTO se formatea con `Intl.NumberFormat` a través del componente `MoneyAmount`
de `packages/ui`. **Nunca se convierte a `number` para mostrarlo, sumarlo ni redondearlo.**

```tsx
// WRONG
<span>{`L ${Number(dto.amount).toFixed(2)}`}</span>

// CORRECT
<MoneyAmount value={dto} />
```

## 10. Casos límite obligatorios en pruebas

Toda regla financiera necesita pruebas unitarias en JUnit con AssertJ que cubran, como mínimo,
estos casos. Si falta alguno, la regla no está probada:

- [ ] Importe cero.
- [ ] Importe negativo, y rechazo cuando el tipo de transacción no lo admite.
- [ ] Importe mínimo representable: un centavo.
- [ ] Importe grande cercano al límite de `NUMERIC(14,4)`, para detectar desbordes.
- [ ] Redondeo `HALF_UP` en el punto medio exacto: `0.615`, `2.345`, `1.005`.
- [ ] Redondeo de importe negativo en el punto medio, que debe ser simétrico al positivo.
- [ ] Escala igual: `1.0` y `1.00` son el mismo `Money`.
- [ ] Construcción desde una cadena con más de cuatro decimales: rechazada o redondeada de forma
      explícita, nunca truncada en silencio.
- [ ] Descuento del cien por ciento: total cero, no negativo.
- [ ] Descuento mayor que la base: resultado cero y excedente no aplicado registrado.
- [ ] Beca y descuento comercial combinados, verificando el orden de aplicación.
- [ ] Impuesto con tasa cero y con concepto exento.
- [ ] Suma de monedas distintas: debe lanzar `CurrencyMismatchException`.
- [ ] Prorrateo cuyo total no divide exacto: la suma de las partes iguala el total.
- [ ] Acumulación de mil importes con decimales: el total coincide con el cálculo exacto.
- [ ] Ida y vuelta de serialización: `Money` a DTO a `Money` conserva el valor exacto.
- [ ] Los cuatro casos de regresión permanentes de ADR-0004: `6.70 × 0.15`, `1234.55 × 3`, la
      acumulación de mil sumas de `0.1`, y `0.1 + 0.2`.

```java
class MoneyTest {

    @Test
    void roundsHalfUpAtTheExactMidpoint() {
        Money base = Money.of("12.30", CurrencyCode.HNL);
        Money fivePercent = base.percentage(Percentage.of("5"), RoundingMode.HALF_UP)
                .roundToMinorUnit(RoundingMode.HALF_UP);
        assertThat(fivePercent).isEqualTo(Money.of("0.62", CurrencyCode.HNL)); // 0.615
    }

    @Test
    void treatsDifferentScalesAsTheSameAmount() {
        assertThat(Money.of("1.0", CurrencyCode.HNL)).isEqualTo(Money.of("1.00", CurrencyCode.HNL));
    }

    @Test
    void rejectsOperationsBetweenCurrencies() {
        Money hnl = Money.of("100.00", CurrencyCode.HNL);
        Money usd = Money.of("100.00", CurrencyCode.USD);
        assertThatThrownBy(() -> hnl.add(usd)).isInstanceOf(CurrencyMismatchException.class);
    }
}

class MoneyAllocationProperties {

    // jqwik property: for any total and any ratios, the parts add up exactly to the total.
    @Property
    void allocationNeverLosesCents(
            @ForAll @IntRange(min = 0, max = 100_000_000) int cents,
            @ForAll @Size(min = 1, max = 12) List<@IntRange(min = 1, max = 100) Integer> ratios) {
        Money total = Money.of(BigDecimal.valueOf(cents, 2), CurrencyCode.HNL);
        List<Money> parts = total.allocate(ratios.stream().mapToInt(Integer::intValue).toArray());
        assertThat(parts.stream().reduce(Money.zero(CurrencyCode.HNL), Money::add)).isEqualTo(total);
    }
}
```

El módulo `kernel` exige noventa y cinco por ciento de cobertura medida con JaCoCo y puntuación de
mutación mínima de ochenta con PIT. Ver `confia-testing-playbook`.

## 11. Antes de dar por terminado

- [ ] Ningún `double`, `float` ni `number` con aritmética representa un importe en el código nuevo.
- [ ] Ningún `new BigDecimal(double)`, `BigDecimal.valueOf(double)` ni `BigDecimal.equals` sobre
      importes.
- [ ] Ninguna columna de dinero es de coma flotante y todas tienen su columna de moneda.
- [ ] Ningún cálculo de dinero vive fuera de `Money` o del paquete `domain` del módulo dueño.
- [ ] Cada operación que redondea declara `RoundingMode.HALF_UP` de forma explícita.
- [ ] Cada importe de la API sale como cadena con su moneda, y los de presentación, redondeados
      por el servidor.
- [ ] Los casos límite de la sección 10 tienen prueba.
- [ ] Las reglas de ArchUnit monetarias están en verde, sin exclusiones nuevas.
