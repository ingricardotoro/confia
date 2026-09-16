# ADR-0007: Libro mayor de doble partida como núcleo financiero

- **Estado:** Aceptado
- **Fecha:** 2026-09-10
- **Decisores:** Propietario del producto y arquitecto
- **Contexto técnico:** `modules/ledger`, `modules/charges`, `modules/payments`, módulo de núcleo del backend (sustituye a `packages/domain`), esquema de base de datos y sus migraciones de Flyway
- **Revisión:** 2026-09-14. Alineado con ADR-0013 (backend en Java con Spring Boot). La decisión no cambia; se actualizan las herramientas y los mecanismos de verificación. Por decisión del propietario, la moneda se almacena solo en cada asiento y el disparador de cuadre rechaza transacciones con más de una moneda.

## Contexto y problema

CONFIA debe responder en todo momento, y de forma defendible ante un auditor, tres preguntas por
estudiante: cuánto debe, qué pagó y por qué el saldo es el que es.

La forma natural de resolverlo es una columna `balance` en la tabla del estudiante que se suma y
se resta con cada movimiento. Es la solución que aparece en casi todos los sistemas de cobro
artesanales, y es la causa raíz de casi todos sus descuadres.

El problema de un saldo mutable es que **destruye la historia al escribir**. Cuando el saldo deja
de corresponder a los movimientos, y eventualmente deja de corresponder, no existe forma de
reconstruir la verdad. Las causas son mundanas y todas ocurren en producción real:

- Dos procesos concurrentes leen el saldo, calculan y escriben. Una de las dos actualizaciones se
  pierde.
- Un error de aplicación resta dos veces.
- Un proceso falla a la mitad, después de actualizar el saldo pero antes de registrar el movimiento.
- Alguien corrige un dato a mano en la base de datos y no ajusta el saldo.

En un sistema financiero esto no es un defecto que se corrige con un parche. Es una pérdida
permanente de confiabilidad: si el saldo pudo estar mal una vez, la institución ya no puede
sostener ninguno de sus números sin verificarlos por fuera del sistema.

## Factores de decisión

| Factor | Peso | Razón |
|---|---|---|
| Reconstruibilidad de cualquier saldo histórico | Muy alto | Un auditor pregunta por el saldo de una fecha pasada, no por el de hoy |
| Resistencia a la concurrencia | Muy alto | Dos cajeros pueden cobrar al mismo estudiante simultáneamente |
| Trazabilidad completa de cada movimiento | Muy alto | Requisito de auditoría y de defensa ante reclamos de padres |
| Complejidad para un solo desarrollador | Alto | Una solución que no se entiende, no se mantiene |
| Rendimiento de consulta del saldo | Medio | Mitigable con caché derivada |
| Familiaridad del desarrollador con contabilidad | Medio | Requiere aprendizaje inicial |

## Opciones consideradas

### Opción A: Campo de saldo mutable

Una columna `balance` en el estudiante, actualizada con cada operación.

**Ventajas.** Trivial de implementar. Consulta del saldo instantánea. No requiere conocimiento
contable.

**Desventajas.** Pierde la historia. Vulnerable a condiciones de carrera. Imposible reconstruir un
saldo pasado. Imposible explicar una diferencia. Un solo error deja el sistema permanentemente
sospechoso, sin forma de demostrar lo contrario.

### Opción B: Tabla de movimientos de partida simple

Una tabla `movements` con importes positivos y negativos. El saldo es la suma.

**Ventajas.** Conserva la historia. Reconstruible. Mucho más simple que la doble partida. Resuelve
el problema principal de la opción A.

**Desventajas.** No hay verificación estructural: nada impide registrar un movimiento incompleto o
un importe en el lugar equivocado. No existe el concepto de contrapartida, así que no se puede
responder de dónde salió ni a dónde fue el dinero. No integra con contabilidad sin transformación
manual. No detecta un movimiento perdido, porque un movimiento suelto es válido por definición.

### Opción C: Libro mayor de doble partida inmutable

Cada hecho económico es una transacción compuesta por dos o más asientos que suman cero. El saldo
se deriva. Nada se edita ni se borra: se reversa.

**Ventajas.** El cuadre es una propiedad estructural verificable, no una esperanza. Cada movimiento
tiene contrapartida, así que siempre se sabe de dónde vino y a dónde fue el dinero. La historia es
completa e inmutable. Reconstruible a cualquier fecha. Detecta automáticamente un asiento perdido,
porque la transacción deja de cuadrar. Exporta a contabilidad sin transformación.

**Desventajas.** Requiere aprender contabilidad básica. Más tablas y más asientos por operación. El
saldo requiere agregación, no una lectura directa. Un desarrollador sin formación contable puede
modelar mal el plan de cuentas al inicio.

## Decisión

**Se adopta la opción C: libro mayor de doble partida inmutable, con saldo derivado.**

El factor decisivo no es la elegancia contable sino la **restricción de un solo desarrollador**.
Contra la intuición, la opción más simple de escribir es la más cara de operar. Con un equipo
grande, un saldo mutable se sostiene a base de vigilancia: revisiones, monitoreo, alguien que
concilia. Con una sola persona, esa vigilancia no existe, y el sistema debe hacer imposible el
error en lugar de confiar en detectarlo.

La doble partida convierte la corrección financiera en una propiedad que la base de datos verifica
en cada escritura, no en una disciplina que alguien debe recordar.

Sobre el costo de aprendizaje: es real, pero acotado. El plan de cuentas necesario para cobros
estudiantiles cabe en una página y no cambia. Es un costo de una vez contra un riesgo permanente.

## Esquema conceptual

```sql
CREATE TABLE ledger_transactions (
  id              UUID PRIMARY KEY,
  institution_id  UUID NOT NULL REFERENCES institutions(id),
  transaction_type TEXT NOT NULL,           -- CHARGE, PAYMENT, DISCOUNT, LATE_FEE, ...
  occurred_at     TIMESTAMPTZ NOT NULL,     -- fecha del hecho economico
  recorded_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  description     TEXT NOT NULL,
  source_type     TEXT NOT NULL,            -- entidad que origino la transaccion
  source_id       UUID NOT NULL,
  reverses_id     UUID REFERENCES ledger_transactions(id),
  created_by      UUID NOT NULL,
  CONSTRAINT no_self_reversal CHECK (reverses_id IS DISTINCT FROM id)
);

CREATE TABLE ledger_entries (
  id              UUID PRIMARY KEY,
  transaction_id  UUID NOT NULL REFERENCES ledger_transactions(id),
  institution_id  UUID NOT NULL,
  account_code    TEXT NOT NULL,            -- cuenta contable
  student_id      UUID,                     -- nulo en cuentas que no son por estudiante
  direction       TEXT NOT NULL CHECK (direction IN ('DEBIT','CREDIT')),
  amount          NUMERIC(14,4) NOT NULL CHECK (amount > 0),
  currency        CHAR(3) NOT NULL          -- the currency belongs to the amount (ADR-0004)
);

-- Un asiento nunca se modifica ni se borra.
REVOKE UPDATE, DELETE ON ledger_transactions, ledger_entries FROM confia_admin_app;
```

Nótese que `amount` es siempre positivo y el signo lo aporta `direction`. Permitir importes
negativos duplica las representaciones del mismo hecho y hace que las sumas de verificación dejen
de significar lo que parecen significar.

La moneda vive en cada asiento y no en la transacción, porque la moneda pertenece al importe
(ADR-0004). Una columna de moneda en la transacción duplicaría el dato y podría divergir de la de
sus asientos. Todos los asientos de una transacción comparten moneda, y lo verifica el mismo
disparador de cuadre.

### Restricción de cuadre

El cuadre se verifica en el momento de la escritura, con un disparador de restricción diferida que
se evalúa al final de la transacción de base de datos, cuando todos los asientos ya están
insertados.

```sql
CREATE OR REPLACE FUNCTION assert_transaction_balanced()
RETURNS TRIGGER AS $$
DECLARE
  diff            NUMERIC(14,4);
  currency_count  INTEGER;
BEGIN
  SELECT COALESCE(SUM(CASE WHEN direction = 'DEBIT' THEN amount ELSE -amount END), 0),
         COUNT(DISTINCT currency)
    INTO diff, currency_count
    FROM ledger_entries
   WHERE transaction_id = NEW.transaction_id;

  -- A transaction never mixes currencies: 100 HNL against 100 USD must not balance.
  IF currency_count <> 1 THEN
    RAISE EXCEPTION 'Transaccion % mezcla monedas', NEW.transaction_id;
  END IF;

  IF diff <> 0 THEN
    RAISE EXCEPTION 'Transaccion % descuadrada por %', NEW.transaction_id, diff;
  END IF;

  RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER trg_transaction_balanced
  AFTER INSERT ON ledger_entries
  DEFERRABLE INITIALLY DEFERRED
  FOR EACH ROW EXECUTE FUNCTION assert_transaction_balanced();
```

La palabra importante es `DEFERRABLE INITIALLY DEFERRED`. Sin ella, la restricción fallaría al
insertar el primer asiento, porque en ese instante la transacción todavía no cuadra.

## Política de reverso

Ninguna corrección modifica un asiento existente.

| Situación | Acción |
|---|---|
| Pago registrado por monto equivocado | Reverso del pago completo, luego registro del pago correcto |
| Cargo generado por error | Reverso del cargo |
| Beca aplicada a quien no correspondía | Reverso del descuento |
| Factura emitida con error | Nota de crédito fiscal más el asiento correspondiente |
| Diferencia detectada en auditoría | Asiento de ajuste con motivo obligatorio y aprobación registrada |

El asiento de reverso apunta al original mediante `reverses_id`. Una transacción no puede
reversarse dos veces, lo que se verifica con un índice único parcial sobre `reverses_id`.

## Saldo derivado y caché

El saldo se calcula por agregación de los asientos de la cuenta por cobrar del estudiante.

Para el rendimiento del panel y del portal se mantiene una **caché** en una tabla aparte, con la
marca de la última transacción incorporada. La caché es una optimización, nunca la verdad:

1. Se recalcula de forma incremental al registrar una transacción, dentro de la misma transacción
   de base de datos.
2. Un trabajo nocturno la reconstruye desde cero y compara con el valor cacheado.
3. Cualquier diferencia genera una alerta de severidad alta y **no se corrige automáticamente**.
   Una diferencia entre la caché y el libro significa que algo se rompió, y sobrescribir la caché
   borraría la única evidencia de qué fue.

## Verificación nocturna de integridad

Un trabajo programado verifica, para toda la institución:

- Toda transacción cuadra. Suma de débitos igual a suma de créditos.
- Ninguna transacción tiene menos de dos asientos.
- Ningún asiento tiene importe cero o negativo.
- El saldo cacheado coincide con el derivado, por estudiante.
- La cadena de hash de la bitácora de auditoría es íntegra.
- Ninguna transacción reversa a otra ya reversada.

El resultado se registra como métrica y cualquier fallo dispara alerta. Ver
`docs/runbooks/descuadre-de-libro-mayor.md`.

## Consecuencias

**Positivas.**

- El saldo de cualquier estudiante a cualquier fecha pasada es reconstruible con exactitud.
- Un descuadre es imposible de persistir, no solo improbable.
- Cada movimiento tiene contrapartida, así que toda pregunta de auditoría tiene respuesta.
- La exportación a contabilidad es directa, sin transformación ni transcripción manual.
- Las condiciones de carrera dejan de producir corrupción silenciosa.

**Negativas y costos aceptados.**

- Costo de aprendizaje contable inicial para el desarrollador.
- Más filas: cada operación genera al menos dos asientos en lugar de una actualización.
- La consulta del saldo requiere agregación o caché, no una lectura de campo.
- Corregir un error operativo exige dos transacciones, reverso y registro correcto, en lugar de
  una edición. Esto es deliberado y es el punto.

**Riesgos y mitigaciones.**

| Riesgo | Mitigación |
|---|---|
| Plan de cuentas mal modelado al inicio | Se define y se revisa con el contador de la institución antes de la fase F3. Cambiarlo después es caro |
| Crecimiento de la tabla de asientos | Particionado por año lectivo cuando supere los umbrales de rendimiento. Los índices se definen desde el inicio |
| Tentación de corregir a mano en producción | Permisos de base de datos revocados para actualización y borrado. El acceso directo a producción queda registrado y alertado |
| Caché desincronizada | Verificación nocturna con alerta y sin corrección automática |

## Cumplimiento y verificación

| Control | Mecanismo | Cuándo |
|---|---|---|
| Cuadre de toda transacción | Disparador de restricción diferida en PostgreSQL | En cada escritura |
| Inmutabilidad de asientos | `REVOKE UPDATE, DELETE` sobre las tablas del libro para el rol de la aplicación | Permanente, verificado en la lista previa a producción |
| Ausencia de campo de saldo mutable | Prueba de integración contra PostgreSQL real que, tras aplicar las migraciones de Flyway, consulta el catálogo y falla si aparece una columna de saldo fuera de la tabla de caché derivada descrita en este ADR, más revisión de toda migración que la toque | En cada cambio de esquema |
| Aritmética monetaria solo en el dominio | `Money` vive en el módulo de núcleo, frontera de compilación de Maven sin dependencias fuera del JDK. Reglas de ArchUnit prohíben construir importes desde coma flotante y comparar con `BigDecimal.equals` fuera de `Money` (ADR-0013). La aritmética directa sobre importes fuera del núcleo se rechaza en revisión | En cada compilación |
| Integridad continua | Trabajo nocturno con métrica `ledger_unbalanced_transactions_total` y alerta al superar cero | Diario |
| Cobertura de las reglas del libro | Cobertura mínima del noventa y cinco por ciento, medida con JaCoCo, y puntuación de mutación mínima de ochenta con PIT en el módulo de núcleo y en el paquete `domain` de cada módulo, incluido `ledger.domain` | En cada fusión |
| Prueba de concurrencia | Prueba de integración con dos transacciones simultáneas sobre la misma cuenta, con base de datos real | En cada fusión |

## Referencias

- `docs/01-arquitectura.md`, sección 6
- `docs/02-modelo-de-dominio.md`, invariantes del libro mayor
- `openspec/specs/ledger/spec.md`
- `.claude/skills/confia-ledger-invariants/SKILL.md`
- `docs/runbooks/descuadre-de-libro-mayor.md`
- ADR-0004 sobre representación monetaria
- ADR-0010 sobre idempotencia y concurrencia financiera
- ADR-0013 sobre el backend en Java con Spring Boot
