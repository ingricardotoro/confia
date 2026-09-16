# ADR-0011: Internacionalización y multi-moneda desde el inicio

- **Estado:** Aceptado
- **Fecha:** 2026-09-10
- **Decisores:** Propietario del producto y arquitecto
- **Contexto técnico:** `apps/admin-web`, `apps/portal-web`, `packages/ui`, módulo de núcleo del backend (objeto de valor `Money`, sustituye a `packages/domain`), esquema de base de datos
- **Revisión:** 2026-09-14. Alineado con ADR-0013 (backend en Java con Spring Boot). La decisión no cambia; el ejemplo de `Money` pasa a Java y la presentación de importes deja de convertir a `number`.

## Contexto y problema

CONFIA opera en Honduras, en español, con lempiras. Nada indica que eso vaya a cambiar pronto.

Sin embargo, el propietario declara una ambición internacional para el producto. Y hay una asimetría
brutal en el costo de esta decisión: preparar la internacionalización hoy cuesta poco y se paga
sobre todo en disciplina, mientras que agregarla después de tener cincuenta pantallas construidas
significa revisar cada archivo, extraer cada cadena, encontrar cada fecha formateada a mano y cada
concatenación de moneda.

El caso de la moneda es peor todavía, porque no es de presentación sino de datos. Si los importes
se almacenan sin moneda, la información se perdió y ninguna migración la recupera: no hay forma de
saber si una fila antigua estaba en lempiras o en otra divisa, porque el dato nunca existió.

## Factores de decisión

| Factor | Peso | Razón |
|---|---|---|
| Costo de retrofitear | Muy alto | Asimétrico. Barato hoy, muy caro después |
| Pérdida irreversible de información | Muy alto | Un importe sin moneda no se puede reconstruir |
| Costo de disciplina diaria | Alto | Un desarrollador solo abandona lo que estorba |
| Beneficio inmediato | Bajo | Hoy no hay un segundo idioma real |
| Calidad de la presentación de datos | Alto | Formatear fechas y montos a mano produce errores visibles |

## Opciones consideradas

### Opción A: Español embebido, moneda implícita

Cadenas escritas directamente en los componentes, importes sin moneda asociada.

**Ventajas.** Máxima velocidad hoy. Cero infraestructura. Se lee el texto directamente en el
código.

**Desventajas.** Convierte cada pantalla en deuda. Y en el caso de la moneda, produce una pérdida
de información que no tiene reparación posible.

### Opción B: Preparación completa desde el inicio

Catálogos de traducción, formato mediante la interfaz de internacionalización del navegador, moneda
almacenada junto a cada importe, y propiedades CSS lógicas.

**Ventajas.** Elimina la deuda antes de que exista. El formato correcto es el camino por defecto,
no un esfuerzo adicional. La moneda queda registrada de forma permanente.

**Desventajas.** Una capa de indirección entre el código y el texto visible. Cuesta un poco más
escribir cada pantalla.

### Opción C: Preparar solo la moneda, diferir el idioma

Almacenar la moneda pero dejar el texto embebido.

**Ventajas.** Evita la pérdida irreversible con costo mínimo.

**Desventajas.** Deja la parte más voluminosa del retrofit para después. Y una vez que hay cincuenta
pantallas con texto embebido, la extracción se pospone indefinidamente.

## Decisión

**Se adopta la opción B, con un alcance deliberadamente acotado en lo que se activa hoy.**

### Lo que se hace desde el primer día

1. **Ninguna cadena visible se escribe embebida en un componente.** Todo texto de interfaz proviene
   del catálogo de traducción, incluidos los mensajes de error, los estados vacíos y los textos de
   los botones.
2. **Todo importe se almacena junto a su moneda.** En el objeto de valor `Money` y en la base de
   datos como columna `currency CHAR(3)` adyacente a cada columna monetaria.
3. **Todo formato de número, moneda y fecha pasa por `Intl`.** Ninguna concatenación manual, ningún
   formato construido con plantillas de cadena.
4. **Propiedades CSS lógicas** en lugar de direccionales: márgenes y rellenos de inicio y fin en
   lugar de izquierda y derecha.
5. **La zona horaria es explícita.** Las marcas de tiempo se almacenan en UTC y se presentan en la
   zona de la institución, que es un dato de configuración y no del navegador.

### Lo que se difiere

- **Traducciones reales a otros idiomas.** Existe un solo catálogo, en español de Honduras. La
  infraestructura está lista, el contenido no.
- **Conversión entre monedas distintas.** Se almacena la moneda, pero no hay tasas de cambio ni
  conversión. Sumar importes de monedas distintas está prohibido y lanza error en el dominio.
- **Selector de idioma en la interfaz.** No se construye hasta que exista un segundo catálogo.
- **Adaptación completa a escritura de derecha a izquierda.** Se usan propiedades lógicas para no
  cerrar la puerta, pero no se verifica ni se prueba.

Esta separación es el punto. Se paga la parte estructural, que es cara de agregar después, y se
difiere la parte de contenido, que es barata de agregar cuando se necesite.

## Convenciones

### Claves de traducción

Jerárquicas, por módulo y por función, en inglés y en minúsculas con puntos.

```
payments.register.title
payments.register.confirmStep.summary
payments.errors.exceedsChargeBalance
common.actions.save
common.status.overdue
```

**Prohibido usar el texto en español como clave.** Cuando el texto cambie, la clave dejaría de
corresponder a su contenido y el catálogo se vuelve incomprensible.

### Plurales y variables

Se usa el formato de mensajes ICU, que maneja plurales y género de forma correcta por idioma. Nunca
se construye una frase concatenando fragmentos, porque el orden de las palabras cambia entre
idiomas y el resultado es intraducible.

```
"collections.overdueNotice": "{count, plural, one {# pago vencido} other {# pagos vencidos}}"
```

### Dinero

El objeto de valor lleva la moneda. La operación de suma entre monedas distintas lanza error de
dominio en lugar de producir un número sin sentido. Forma conceptual en el módulo de núcleo del
backend, coherente con ADR-0004:

```java
public final class Money {

    private static final int SCALE = 4;

    private final BigDecimal amount;
    private final CurrencyCode currency;

    private Money(BigDecimal amount, CurrencyCode currency) {
        // Normalizes to scale 4; throws instead of silently discarding digits.
        this.amount = amount.setScale(SCALE, RoundingMode.UNNECESSARY);
        this.currency = currency;
    }

    public Money add(Money other) {
        if (!currency.equals(other.currency)) {
            throw new CurrencyMismatchException(currency, other.currency);
        }
        return new Money(amount.add(other.amount), currency);
    }

    // equals and hashCode compare the normalized amount and the currency (ADR-0004).
}
```

En la API, el dinero se serializa como cadena con su moneda, nunca como número de coma flotante.
Ver `.claude/skills/confia-api-conventions/SKILL.md`.

### Presentación

El servidor es la única autoridad sobre el dinero. Todo campo de la API destinado a mostrarse llega
como el objeto `{ amount: string, currency: string }` del contrato generado en `packages/contracts`,
con el importe **ya redondeado por el servidor** a la escala menor de la moneda (dos decimales para
HNL y USD) con redondeo half-up, conforme a la política de ADR-0004. La escala interna de cuatro
decimales no sale del servidor en los campos de presentación.

El frontend solo formatea esa cadena con `Intl.NumberFormat`, con estilo de moneda y la moneda del
propio importe. **No redondea, no calcula y no hace aritmética monetaria**: toda la aritmética y
todo redondeo ocurren en el backend, con `Money` sobre `BigDecimal` (ADR-0004; `CLAUDE.md`,
reglas 3 y 9).

## Consecuencias

**Positivas.**

- Abrir un segundo idioma pasa a ser traducir un archivo, no reescribir la aplicación.
- La moneda queda registrada de forma permanente, sin pérdida irreversible.
- El formato de importes y fechas es consistente en todo el sistema por construcción.
- Los errores clásicos de formato manual desaparecen.

**Negativas y costos aceptados.**

- Una capa de indirección: el texto visible no se lee directamente en el componente.
- Escribir una pantalla cuesta un poco más.
- El catálogo puede acumular claves huérfanas si nadie las limpia.

**Riesgos y mitigaciones.**

| Riesgo | Mitigación |
|---|---|
| Cadenas embebidas por prisa | Regla de ESLint que prohíbe literales de texto en JSX, verificada en integración continua |
| Formato manual de moneda o fecha | Regla de análisis estático que prohíbe `toFixed` y `toLocaleString` fuera de las utilidades de formato |
| Claves huérfanas acumuladas | Verificación periódica de claves no usadas, con reporte |
| Falsa sensación de estar internacionalizado | Este ADR declara explícitamente que no hay segundo idioma ni conversión de moneda |

## Cumplimiento y verificación

| Control | Mecanismo | Cuándo |
|---|---|---|
| Sin texto embebido | Regla de ESLint contra literales en JSX | En cada compilación |
| Sin formato manual | Regla de análisis estático contra `toFixed` y `toLocaleString` fuera de utilidades | En cada compilación |
| Moneda presente en el esquema | Verificación de que toda columna `NUMERIC` monetaria tiene su columna de moneda adyacente | En cada cambio de esquema |
| Suma entre monedas distintas rechazada | Prueba unitaria de `Money` en el módulo de núcleo del backend | En cada fusión |
| Formato correcto de importes | Prueba de componente sobre `MoneyAmount` con varios valores límite | En cada fusión |
| Claves faltantes en el catálogo | Verificación que falla si una clave usada no existe en el catálogo | En cada compilación |

## Referencias

- `docs/ui-ux/05-guia-de-contenido-y-voz.md`
- `.claude/skills/confia-money-rules/SKILL.md`
- ADR-0004 sobre representación monetaria
- ADR-0006 sobre el frontend con TanStack
- ADR-0013 sobre el backend en Java con Spring Boot
