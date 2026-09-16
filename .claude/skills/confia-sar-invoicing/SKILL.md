---
name: confia-sar-invoicing
description: Reglas de facturación fiscal hondureña de CONFIA. Se dispara al tocar facturación, CAI, correlativo, nota de crédito, anulación de documento fiscal, impuesto sobre ventas, documento fiscal o punto de emisión.
---

# Facturación fiscal SAR de CONFIA

## REGLA CRÍTICA: nunca inventes una regla fiscal concreta

**`docs/04-cumplimiento-fiscal-sar.md` existe, pero todo dato marcado en él como
`PENDIENTE DE VALIDACIÓN` sigue sin confirmar por el contador de la institución.** Ese documento es
la única fuente de verdad para el detalle normativo del régimen
SAR/CAI: estructura exacta del número de documento, campos obligatorios de una factura, plazos de
vigencia del CAI, porcentaje del impuesto sobre ventas, reglas exactas de cuándo corresponde
anulación y cuándo nota de crédito, y cualquier otro dato numérico o de formato exigido por el
régimen hondureño.

**Si vas a escribir o revisar código de facturación y el dato concreto que necesitas no está
confirmado en `docs/04-cumplimiento-fiscal-sar.md`:**

1. No lo inventes, no lo aproximes desde memoria general sobre el SAR y no copies un formato de
   otro país o de un régimen distinto.
2. Marca el punto exacto como `PENDIENTE DE VALIDACIÓN CON EL CONTADOR` en el código (comentario),
   en la especificación o en tu respuesta.
3. Detente en ese punto concreto. Puedes seguir trabajando en las partes del cambio que no
   dependen del dato pendiente, pero el dato en sí no se rellena con una suposición.

Esta regla no es negociable porque un documento fiscal mal formado no es un defecto de software:
es una exposición legal y económica real para la institución. Un valor de relleno que "parece
razonable" es peor que un campo vacío, porque un campo vacío se nota y un valor plausible no.

Lo que sigue en esta skill son los **conceptos estructurales que ya están confirmados** en
`docs/01-arquitectura.md` y `docs/02-modelo-de-dominio.md`, más los invariantes de ingeniería que
aplican sin importar el detalle normativo exacto. No sustituye la validación contable.

## 1. Conceptos del régimen, en el lenguaje ubicuo del proyecto

Ver `docs/02-modelo-de-dominio.md` sección 2 para la definición completa de cada entidad.

| Término | Entidad en código | Qué es, confirmado en arquitectura |
|---|---|---|
| Rango CAI | `CaiRange` | Autorización de emisión con rango de correlativos y fecha límite |
| Correlativo | `FiscalSequence` | Numeración consecutiva e irrepetible por punto de emisión |
| Documento fiscal | `FiscalDocument` | Factura o documento equivalente emitido bajo régimen SAR |
| Nota de crédito | `CreditNote` | Documento fiscal que corrige total o parcialmente una factura emitida |
| Impuesto | `TaxRate` | Tasa aplicable a un concepto, con vigencia |

El **puerto de emisión de documentos fiscales se diseña desde el inicio con un adaptador para el
régimen CAI**, de modo que una eventual migración a facturación electrónica sea agregar un
adaptador nuevo, no reescribir el módulo `invoicing`. Ver `docs/01-arquitectura.md` sección 10.

## 2. Correlativo fiscal: cómo se obtiene y cómo no

Esto está confirmado y es una regla dura, no un detalle pendiente de validar con contabilidad: es
una decisión de ingeniería para evitar una condición de carrera.

**El correlativo se obtiene de una secuencia con bloqueo, dentro de la transacción de emisión,
bajo aislamiento `SERIALIZABLE` o bloqueo explícito de fila sobre el rango CAI activo. Nunca se
calcula con `MAX(numero) + 1`.**

```sql
-- WRONG: two concurrent issuances read the same maximum before either commits,
-- and both get the same correlative.
SELECT COALESCE(MAX(correlative), 0) + 1 FROM fiscal_document WHERE cai_range_id = ?;
```

```java
// CORRECT: pessimistic lock on the CAI range row before reading and writing the
// next correlative, inside the same issuance transaction. Illustrative: TransactionRunner is a
// placeholder for the single transaction component of shared/security, fixed in F0 (ADR-0015).
// The component applies SERIALIZABLE and retries on serialization failure (ADR-0010).
public FiscalDocument issue(IssueFiscalDocumentCommand command) {
    return transactions.run(TxIsolation.SERIALIZABLE, () -> {
        CaiRange range = caiRanges.lockForUpdate(command.caiRangeId()); // SELECT ... FOR UPDATE
        range.assertCanIssue(command.occurredAt());                      // validity and availability, section 3
        long correlative = range.currentCorrelative() + 1;
        caiRanges.advanceTo(range.id(), correlative);
        return fiscalDocuments.insert(command.toDocument(correlative));  // insert-only jOOQ repository
    });
}
```

Esto se prueba con conexiones concurrentes reales, nunca con un doble de prueba. Ver
`confia-testing-playbook` y el ejemplo de `docs/06-estrategia-de-testing.md` sección 7 sobre
numeración concurrente.

## 3. Prohibiciones sobre el correlativo

- **Nunca se reutiliza un correlativo.** Un documento fallido o abandonado antes de confirmar no
  libera su número: o se completa la emisión, o la transacción entera se revierte y el número
  jamás se asignó (el incremento vive dentro de la misma transacción que crea el documento).
- **Nunca se deja un hueco sin anulación registrada.** Todo hueco visible en la secuencia debe
  corresponder a un documento anulado con su propio registro, nunca a un número simplemente
  saltado o borrado.
- **Antes de emitir**, el caso de uso valida dos cosas por separado y ambas son obligatorias:
  1. **Vigencia**: la fecha de la emisión está dentro del período autorizado por el CAI.
  2. **Disponibilidad del rango**: el siguiente correlativo no excede el límite superior
     autorizado.
  Cualquiera de las dos que falle bloquea la emisión con un error de dominio explícito, nunca con
  un correlativo fuera de rango.

```java
// WRONG: validates only validity and assumes the range is never exhausted
if (range.validUntil().isBefore(command.occurredAt())) {
    throw new ExpiredCaiRangeException();
}

// CORRECT: validity and range availability, checked separately
if (range.validUntil().isBefore(command.occurredAt())) {
    throw new ExpiredCaiRangeException(range.id());
}
if (range.currentCorrelative() + 1 > range.rangeTo()) {
    throw new CaiRangeExhaustedException(range.id());
}
```

## 4. Inmutabilidad del documento emitido

Consistente con el principio general de CONFIA de que nada financiero se borra ni se edita (ver
`confia-ledger-invariants`), un documento fiscal ya emitido:

- No se actualiza. Ningún campo de un `FiscalDocument` confirmado se modifica después de la
  emisión, incluidos los que parecen no fiscales (nombre del estudiante, dirección).
- No se borra.
- No se persiste como entidad gestionada: no hay JPA en el proyecto. Se escribe y se lee con jOOQ,
  con sentencias explícitas, para que ninguna actualización implícita pueda modificarlo (ADR-0013,
  ADR-0015). Su repositorio solo inserta y lee; no declara operaciones de actualización ni de
  borrado, y los permisos de base de datos lo respaldan.
- Toda corrección posterior a la emisión pasa por el árbol de decisión de la sección 5: anulación
  o nota de crédito, nunca una edición directa.

```sql
-- WRONG
UPDATE fiscal_document SET student_name = ? WHERE id = ?;

-- CORRECT: the original document stays intact. The correction is a new
-- document that references the original, as the decision tree of
-- section 5 requires.
```

## 5. Árbol de decisión: anulación o nota de crédito

La distinción exacta de cuándo el régimen SAR exige una u otra, y los campos y plazos que cada una
requiere, **está pendiente de validación con el contador en `docs/04-cumplimiento-fiscal-sar.md`.**
Lo que sí está confirmado como principio de diseño es la forma del árbol, no sus condiciones
normativas exactas:

```
¿El documento tiene efecto económico ya reconocido (el estudiante ya pagó, o el documento
ya circula fuera del control interno de la institución)?
│
├── NO, y el error se detecta en el mismo período de emisión sin efecto económico
│     └── PENDIENTE DE VALIDACIÓN: ¿el régimen permite anulación directa en este caso,
│         y bajo qué condiciones exactas de plazo y motivo?
│
└── SÍ, el documento ya tuvo efecto económico o fiscal
      └── PENDIENTE DE VALIDACIÓN: corresponde nota de crédito, pero el detalle de qué
          campos, qué referencia al documento original y qué plazo aplica debe confirmarse
          antes de implementar el caso de uso.
```

Lo que **sí** es un invariante de ingeniería, independiente del detalle normativo:

- Toda anulación y toda nota de crédito referencian de forma explícita el documento original.
- Toda anulación y toda nota de crédito son en sí mismas documentos o eventos auditados, con
  motivo obligatorio y, según `docs/03-seguridad.md` sección 5.3, con aprobación de un actor
  distinto del que emitió el documento original (segregación de funciones).
- El reverso contable asociado sigue las reglas de `confia-ledger-invariants`: asientos espejo,
  nunca edición del asiento original.

**No implementes el caso de uso de anulación o nota de crédito sin la confirmación normativa
correspondiente en `docs/04-cumplimiento-fiscal-sar.md`.** Implementar la mecánica de auditoría,
segregación de funciones y referencia al documento original sí es seguro de adelantar; rellenar el
criterio de negocio exacto con una suposición no lo es.

## 6. Tasa de impuesto: configuración con vigencia, nunca codificada

Esto está confirmado en `docs/01-arquitectura.md` y en el modelo de dominio (`TaxRate`): la tasa
del impuesto sobre ventas es un dato de configuración con vigencia temporal, gestionado como
cualquier otro precio vigente, **nunca un literal en el código.**

```java
// WRONG: the rate lives in the code (and as a double), changing it requires a deployment
// and loses the history of which rate applied to each past document.
static final double SALES_TAX_RATE = 0.15;

// CORRECT: the rate is looked up by validity at the issuance date,
// and the document persists the exact rate applied.
TaxRate taxRate = taxRates.effectiveAt(concept.taxCategory(), command.occurredAt());
Money tax = taxableBase.percentage(taxRate.value(), RoundingMode.HALF_UP)
        .roundToMinorUnit(RoundingMode.HALF_UP);
document.recordAppliedTaxRate(taxRate.id(), taxRate.value()); // traceability of the historical value
```

El valor numérico exacto de cualquier tasa, y si existen conceptos exentos o con tasa distinta,
**está pendiente de validación con el contador** hasta que `docs/04-cumplimiento-fiscal-sar.md`
lo confirme. La regla de ingeniería (configuración con vigencia, nunca codificada) aplica sin
importar cuál sea el valor final.

## 7. Antes de dar por terminado

- [ ] Ningún dato fiscal concreto (formato de número, porcentaje de impuesto, plazo, campo
      obligatorio) se implementó sin respaldo en `docs/04-cumplimiento-fiscal-sar.md`. Todo lo que
      faltaba quedó marcado `PENDIENTE DE VALIDACIÓN CON EL CONTADOR` y detenido.
- [ ] El correlativo se obtiene con bloqueo dentro de la transacción de emisión, nunca con
      `MAX(...) + 1`.
- [ ] Existe prueba de integración con conexiones concurrentes reales que confirma que N emisiones
      simultáneas producen N correlativos distintos y consecutivos.
- [ ] Se valida vigencia y disponibilidad de rango por separado antes de emitir.
- [ ] Ningún documento fiscal emitido se actualiza ni se borra en el código nuevo.
- [ ] Toda anulación y toda nota de crédito referencian el documento original y tienen motivo y
      aprobación de un actor distinto del emisor.
- [ ] La tasa de impuesto se lee de configuración con vigencia a la fecha de emisión, nunca está
      codificada como literal.
