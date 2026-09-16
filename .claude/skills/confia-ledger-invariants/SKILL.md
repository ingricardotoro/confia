---
name: confia-ledger-invariants
description: Invariantes del libro mayor de doble partida de CONFIA. Se dispara al trabajar con ledger, asientos, transacciones contables, saldo o balance de estudiante, estado de cuenta, cargos, devengo, pagos, abonos, aplicación de pago, imputación, reversos, anulaciones contables, ajustes, saldo a favor, incobrables o cualquier consulta que derive dinero adeudado.
---

# Invariantes del libro mayor de CONFIA

El módulo `ledger` (`apps/api/app/src/main/java/<paquete-base>/ledger/`) es el núcleo financiero.
Todo lo demás depende de que sus invariantes se cumplan siempre. Un descuadre aquí no se detecta en
pruebas: se detecta cuando un padre reclama y nadie puede reconstruir la verdad.

Antes de tocar este módulo lee `docs/01-arquitectura.md` sección 6, `docs/02-modelo-de-dominio.md`,
ADR-0004, ADR-0007 y ADR-0013. `<paquete-base>` se fija en F0. Los ejemplos de código son
ilustrativos: los nombres de tipos los fija la especificación de cada cambio.

## 1. Los seis invariantes duros

1. **Toda transacción cuadra.** La suma de los importes de debe iguala la suma de los importes de
   haber, dentro de la misma transacción y en la misma moneda.
2. **El saldo se deriva, nunca se almacena como campo mutable.** No existe una columna `balance` en
   la tabla del estudiante que alguien actualice. Un saldo materializado se permite solo como caché
   reconstruible y verificada.
3. **Nada se borra ni se edita.** No hay `DELETE` ni `UPDATE` sobre asientos. Una corrección es un
   asiento nuevo de reverso.
4. **Toda escritura ocurre dentro de una transacción de base de datos con bloqueo de la cuenta.**
5. **Toda escritura es idempotente**, identificada por la clave de idempotencia de la operación de
   origen.
6. **Los asientos nunca se persisten como entidades gestionadas.** No hay JPA en el proyecto: se
   escriben y se leen con jOOQ, con sentencias explícitas, para que ninguna actualización implícita
   pueda modificar un registro financiero (ADR-0013, ADR-0015). El repositorio del libro solo
   inserta y lee; no declara operaciones de actualización ni de borrado.

## 2. Modelo mínimo

Una transacción del libro mayor agrupa dos o más asientos. El asiento nunca existe suelto. Cada
importe lleva su propia columna de moneda (ADR-0004); todos los asientos de una transacción
comparten moneda. El esquema definitivo lo escribe `confia-database` como migración de Flyway.

```sql
-- Illustrative shape. The definitive schema is a Flyway migration owned by the ledger module.
CREATE TABLE ledger_transaction (
    id               UUID          PRIMARY KEY,
    institution_id   UUID          NOT NULL,
    account_id       UUID          NOT NULL,          -- student account
    type             TEXT          NOT NULL,          -- see section 3
    occurred_at      TIMESTAMPTZ   NOT NULL,          -- accounting effective date
    recorded_at      TIMESTAMPTZ   NOT NULL DEFAULT clock_timestamp(), -- server time, immutable
    idempotency_key  TEXT          NOT NULL,
    reverses_id      UUID          REFERENCES ledger_transaction (id),
    reason           TEXT,                            -- mandatory for ADJUSTMENT and REVERSAL
    created_by       UUID          NOT NULL,
    CONSTRAINT ledger_transaction_idempotency UNIQUE (institution_id, idempotency_key)
);

CREATE INDEX ledger_transaction_account_idx ON ledger_transaction (account_id, occurred_at);

CREATE TABLE ledger_entry (
    id               UUID          PRIMARY KEY,
    transaction_id   UUID          NOT NULL REFERENCES ledger_transaction (id),
    direction        TEXT          NOT NULL,          -- DEBIT | CREDIT
    account          TEXT          NOT NULL,          -- chart of accounts
    amount           NUMERIC(14,4) NOT NULL,          -- always positive; direction gives the sign
    currency         CHAR(3)       NOT NULL,          -- the currency belongs to the amount
    charge_id        UUID                             -- allocation, when applicable
);
```

Restricciones declaradas en el esquema, no solo en el código Java:

```sql
-- The amount of an entry is always positive. The direction provides the sign.
ALTER TABLE ledger_entry ADD CONSTRAINT entry_amount_positive CHECK (amount > 0);
ALTER TABLE ledger_entry ADD CONSTRAINT entry_direction_valid CHECK (direction IN ('DEBIT', 'CREDIT'));

-- A transaction has at least two entries, all in the same currency, and balances.
-- Verified with a DEFERRABLE INITIALLY DEFERRED constraint trigger that validates at COMMIT:
--   SUM(amount) FILTER (WHERE direction = 'DEBIT')
--     = SUM(amount) FILTER (WHERE direction = 'CREDIT')
--   AND COUNT(DISTINCT currency) = 1
-- grouped by transaction_id.

-- A reversal points to an existing transaction other than itself.
ALTER TABLE ledger_transaction
  ADD CONSTRAINT reversal_not_self CHECK (reverses_id IS DISTINCT FROM id),
  ADD CONSTRAINT reason_required_for_adjustment
    CHECK (type NOT IN ('ADJUSTMENT', 'REVERSAL') OR reason IS NOT NULL);

-- A transaction can be reversed only once.
CREATE UNIQUE INDEX ledger_single_reversal ON ledger_transaction (reverses_id)
  WHERE reverses_id IS NOT NULL;
```

Permisos: el rol de aplicación tiene `INSERT` y `SELECT` sobre `ledger_transaction` y
`ledger_entry`. **No tiene `UPDATE` ni `DELETE`.** El rol del portal tiene solo `SELECT` con
seguridad a nivel de fila.

## 3. Tipos de transacción permitidos

Solo estos. Agregar uno nuevo exige cambio SDD aprobado.

| Tipo | Origen | Efecto sobre el saldo |
|---|---|---|
| `CHARGE` | Devengo de colegiatura, matrícula, transporte u otro concepto | Aumenta la deuda |
| `DISCOUNT` | Beca o descuento vigente aplicado | Disminuye la deuda |
| `PAYMENT` | Efectivo, tarjeta, transferencia o pasarela | Disminuye la deuda |
| `LATE_FEE` | Recargo calculado por el motor de mora | Aumenta la deuda |
| `CREDIT_NOTE` | Nota de crédito fiscal | Disminuye la deuda |
| `REVERSAL` | Anulación contable de una transacción anterior | Opuesto exacto al original |
| `ADJUSTMENT` | Corrección administrativa con motivo y aprobación | Según el caso |
| `WRITE_OFF` | Baja de cartera por incobrable | Disminuye la deuda |

## 4. El saldo se calcula, no se lee

```sql
-- WRONG: mutable field. It is the root cause of every mismatch.
UPDATE student SET balance = balance + ? WHERE id = ?;
```

```java
// WRONG: reading a field as if it were the truth
Money balance = student.balance();

// CORRECT: the balance is a function of the entries
Money balance = ledger.balanceOf(accountId, asOf);
```

La consulta se muestra en SQL por legibilidad. En el adaptador se escribe con la DSL de jOOQ, con
parámetros vinculados (ADR-0015).

```sql
-- Canonical balance query. It lives in the adapter, not in the domain.
SELECT
  e.currency,
  SUM(CASE WHEN e.direction = 'DEBIT' THEN e.amount ELSE -e.amount END) AS balance
FROM ledger_entry e
JOIN ledger_transaction t ON t.id = e.transaction_id
WHERE t.account_id = ?
  AND t.institution_id = ?
  AND t.occurred_at <= ?
GROUP BY e.currency;
```

El `NUMERIC` que devuelve la consulta se convierte a `Money` en el adaptador, nunca se pasa como
`BigDecimal` suelto a la capa de aplicación. Ver `confia-money-rules`.

Si por rendimiento se materializa un saldo, se cumple todo esto o no se materializa:

- La tabla materializada se marca explícitamente como caché en su nombre y su comentario.
- Se actualiza dentro de la misma transacción que inserta los asientos.
- El trabajo nocturno de integridad la reconstruye desde cero y compara.
- **Ninguna decisión financiera se toma leyendo la caché sin verificar.** La aplicación de un pago
  y la emisión de un documento fiscal leen del libro con bloqueo.

## 5. Aplicación de un pago a los cargos pendientes

Un pago no es un número que baja un saldo: es un importe que se imputa a cargos concretos.

**Política de imputación configurable por institución. Por defecto: por antigüedad.**

Orden por defecto (`OLDEST_FIRST`):

1. Cargos vencidos, del más antiguo al más reciente por fecha de vencimiento.
2. Dentro de la misma fecha: primero recargos por mora, después el cargo principal.
3. Cargos por vencer, del más próximo al más lejano.
4. El remanente queda como saldo a favor, es decir saldo negativo de la cuenta.

Políticas alternativas admitidas: `NEWEST_FIRST`, `SMALLEST_FIRST`, `MANUAL`. En `MANUAL` la
imputación llega explícita en la petición y se valida igual que las demás.

```java
// ledger/application/ApplyPaymentUseCase.java
// Illustrative. TransactionRunner is a placeholder for the single transaction component of
// shared/security, fixed in F0 (ADR-0015): it sets the row-level security context first and
// retries on serialization failure or deadlock. No @Transactional here.
public PaymentApplied handle(ApplyPaymentCommand command) {
    return transactions.run(TxIsolation.READ_COMMITTED, () -> {
        // 1. Idempotency first: if it already exists, return the original response.
        Optional<LedgerTransaction> existing =
                ledger.findByIdempotencyKey(command.institutionId(), command.idempotencyKey());
        if (existing.isPresent()) {
            return PaymentApplied.from(existing.get());
        }

        // 2. Pessimistic lock on the account. Nobody else applies money to it now.
        accounts.lockForUpdate(command.accountId());

        // 3. Real state from the ledger, already under lock.
        List<OutstandingCharge> outstanding = ledger.outstandingCharges(command.accountId());

        // 4. The domain decides the allocation. No arithmetic here.
        PaymentAllocation allocation = PaymentAllocator.allocate(command.amount(), outstanding, command.policy());

        // 5. Atomic persistence: entries, audit and cache in the same transaction.
        LedgerTransaction transaction = ledger.record(allocation.toTransaction(command));
        audit.append(AuditEvent.paymentApplied(transaction, command.actor()));
        return PaymentApplied.from(transaction);
    });
}
```

El bloqueo, igual que la consulta de saldo, se muestra en SQL; el adaptador lo escribe con jOOQ
(`forUpdate()`), dentro de la transacción del componente transaccional.

Por ADR-0010, una segunda transacción sobre la misma cuenta **espera** a que la primera termine; no
falla de inmediato. La espera está acotada: el componente transaccional fija un `lock_timeout` local
a cada transacción, de modo que una transacción colgada no bloquee la ventanilla indefinidamente. El
valor concreto se fija en F0. Cuando una operación afecta a varias cuentas, se bloquean siempre en el
mismo orden, por identificador de estudiante.

```sql
-- Pessimistic lock (ADR-0010). Waits for a concurrent transaction on the same account;
-- the wait is bounded by the transaction-local lock_timeout set by the transaction component.
SELECT id FROM student_account
WHERE id = ? AND institution_id = ?
FOR UPDATE;
```

### Prohibición de sobreimputación

**Una imputación nunca puede exceder el saldo pendiente del cargo al que se aplica.**

```java
// WRONG: applies the whole payment to the first charge without looking at its outstanding amount
allocations.add(new Allocation(charges.get(0).id(), payment));

// CORRECT, inside the domain allocator
Money remaining = payment;
for (OutstandingCharge charge : ordered) {
    if (remaining.isZero()) {
        break;
    }
    Money applied = Money.min(remaining, charge.outstanding()); // already considers prior allocations
    if (applied.isZero()) {
        continue;
    }
    allocations.add(new Allocation(charge.id(), applied));
    remaining = remaining.subtract(applied);
}
// The remainder is an explicit credit balance, never silently discarded.
if (remaining.isPositive()) {
    allocations.add(Allocation.toAccount(LedgerAccount.CREDIT_BALANCE, remaining));
}
```

Además, se declara en base de datos:

```sql
-- The total allocated to a charge never exceeds its amount. Verified by a DEFERRABLE
-- trigger at COMMIT on ledger_entry grouped by charge_id.
```

## 6. Reversos, nunca correcciones

```sql
-- WRONG: rewrites history
UPDATE ledger_entry SET amount = ? WHERE id = ?;
DELETE FROM ledger_transaction WHERE id = ?;
```

```java
// CORRECT: reversal with a reason, then the correct entry
LedgerTransaction reversal = ledger.reverse(original.id(), new ReversalRequest(
        command.reason(),          // mandatory
        command.actor(),
        command.idempotencyKey()));
LedgerTransaction corrected = ledger.record(correctTransaction);
```

Reglas del reverso:

- Los asientos del reverso son la imagen espejo exacta del original: mismo importe, dirección
  contraria, misma moneda, mismas cuentas.
- El reverso registra `reverses_id`, motivo y actor. El motivo no puede ser vacío ni genérico.
- **Una transacción se reversa una sola vez.** El índice único lo garantiza.
- Un reverso no se reversa: si el reverso fue un error, se registra un ajuste con aprobación.
- El reverso de una transacción con documento fiscal emitido exige el flujo fiscal correspondiente.
  Ver `confia-sar-invoicing`.
- `occurred_at` del reverso es la fecha en que se detecta el error, no la del original. La historia
  no se retrocha.

## 7. Trabajo de verificación de integridad

Trabajo nocturno del módulo `ledger`: tarea recurrente de db-scheduler declarada en código con
expresión de calendario y zona horaria `America/Tegucigalpa`, definida en su capa `infrastructure`
y ejecutada solo por `confia-worker` (ADR-0016). Corre a través del componente transaccional con
actor `system` y el contexto de cada institución. Verifica, en este orden:

1. **Cuadre por transacción**: para cada transacción, suma de debe igual a suma de haber.
2. **Una sola moneda por transacción**: ninguna transacción mezcla monedas.
3. **Cuadre por cuenta**: el saldo recalculado desde los asientos coincide con el saldo
   materializado, si existe caché.
4. **Imputación**: ningún cargo tiene imputado más que su importe.
5. **Unicidad de reverso**: ninguna transacción tiene dos reversos.
6. **Continuidad de auditoría**: la cadena de hash de la bitácora verifica. Ver
   `confia-audit-logging`.
7. **Correlativo fiscal**: todo hueco corresponde a una anulación registrada.

El resultado se publica como métrica `confia_ledger_unbalanced_transactions`. La alerta se dispara
con un valor distinto de cero, sin umbral de tolerancia.

## 8. Qué hacer ante un descuadre

**Detente. Escala. No corrijas.**

1. **No ejecutes ningún `UPDATE` ni `DELETE` correctivo.** Ni siquiera "solo para ver".
2. Registra el hallazgo con el identificador de la cuenta, el de la transacción y el importe de la
   diferencia.
3. Marca la cuenta como `UNDER_REVIEW`: bloquea nuevas escrituras financieras sobre ella y deja las
   lecturas disponibles con aviso visible.
4. Emite la alerta operativa y notifica al propietario del sistema.
5. Presenta el diagnóstico y **espera decisión humana**. La corrección, cuando se autorice, será un
   asiento de ajuste con motivo, actor y aprobación registrada.

```sql
-- WRONG when a mismatch is detected
UPDATE student_account SET balance = ? WHERE id = ?;
```

```java
// CORRECT
accounts.flagForReview(accountId, IntegrityFinding.from(difference));
alerts.raise("ledger.unbalanced", accountId, difference);
throw new LedgerIntegrityException(accountId, difference); // stops the process
```

Si trabajas como agente y detectas un descuadre, **no propongas un script de corrección**. Reporta
el hallazgo y detente.

## 9. Casos límite obligatorios en pruebas

Con JUnit y AssertJ para el dominio, y con Testcontainers sobre PostgreSQL real para lo que depende
del motor (cuadre al `COMMIT`, concurrencia, permisos):

- [ ] Transacción que no cuadra: el `COMMIT` falla.
- [ ] Transacción con asientos en dos monedas: el `COMMIT` falla.
- [ ] Asiento con importe cero o negativo: rechazado.
- [ ] Pago exactamente igual al saldo: deja el saldo en cero y ningún saldo a favor.
- [ ] Pago mayor al saldo: genera saldo a favor por el remanente exacto.
- [ ] Pago menor al cargo más antiguo: imputación parcial, cargo sigue abierto.
- [ ] Dos pagos concurrentes sobre la misma cuenta: uno espera al otro, el saldo final es correcto.
- [ ] Misma clave de idempotencia dos veces: un solo asiento, misma respuesta.
- [ ] Reverso de un pago ya imputado: los cargos vuelven a quedar abiertos.
- [ ] Segundo reverso de la misma transacción: rechazado.
- [ ] Imputación manual que excede el saldo del cargo: rechazada.
- [ ] Saldo con moneda distinta a la de la cuenta: rechazado.
- [ ] Saldo `asOf` en una fecha pasada: ignora transacciones posteriores.
- [ ] `UPDATE` o `DELETE` sobre `ledger_entry` con el rol de aplicación: rechazado por el motor.

```java
@Test
void neverAllowsAnAllocationToExceedTheChargeOutstanding() {
    var charge = fixtures.charge(Money.of("500.00", CurrencyCode.HNL));

    assertThatThrownBy(() -> applyPayment.handle(manualPayment(
            Money.of("600.00", CurrencyCode.HNL),
            new Allocation(charge.id(), Money.of("600.00", CurrencyCode.HNL)))))
            .isInstanceOf(OverAllocationException.class);
}
```

## 10. Antes de dar por terminado

- [ ] Ninguna escritura del libro ocurre fuera de una transacción con bloqueo de cuenta.
- [ ] Ningún saldo se lee de un campo mutable para tomar una decisión financiera.
- [ ] No hay `UPDATE` ni `DELETE` sobre tablas del libro en el código nuevo.
- [ ] Sin JPA ni Spring Data; el repositorio del libro solo inserta y lee con jOOQ (ADR-0015).
- [ ] La transacción la abre solo el componente transaccional de `shared/security`.
- [ ] Toda corrección es un reverso con motivo obligatorio.
- [ ] La clave de idempotencia se verifica antes de escribir.
- [ ] La auditoría se escribe en la misma transacción de negocio.
