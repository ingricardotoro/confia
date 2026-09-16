# Runbook: Rango CAI agotado o vencido

- **Severidad:** Crítica
- **Tiempo objetivo de resolución:** inmediato para la contención, días para la solución definitiva
- **Última prueba de este procedimiento:** pendiente

---

## Síntoma

Cualquiera de estos:

- Alerta de consumo del rango al 95 por ciento o de fecha límite a menos de 15 días.
- Los intentos de emitir factura fallan con el mensaje de rango agotado o vencido.
- El indicador de rangos del panel de configuración muestra estado agotado o vencido.

## Impacto si no se atiende

**La institución no puede facturar.** No es un problema técnico que se pueda posponer: es una
parálisis operativa. Los pagos pueden seguir registrándose, pero cada uno queda con su factura
pendiente, y esa deuda documental crece cada hora.

Obtener un rango nuevo depende de un trámite ante la autoridad tributaria y **no está bajo control
del equipo técnico**. Puede tardar días. Por eso este runbook es sobre todo un procedimiento de
prevención y de contención, no de reparación.

---

## Diagnóstico

### 1. Confirmar el estado exacto del rango

```sql
SELECT id,
       point_of_sale,
       document_type,
       range_start,
       range_end,
       current_number,
       range_end - current_number      AS disponibles,
       expiration_date,
       expiration_date - CURRENT_DATE  AS dias_restantes,
       status
  FROM cai_ranges
 WHERE status = 'ACTIVE'
 ORDER BY expiration_date;
```

Determina cuál de los dos problemas es, porque la contención difiere:

| Condición | Diagnóstico |
|---|---|
| `disponibles` igual o menor que cero | Rango agotado por consumo |
| `dias_restantes` menor que cero | Rango vencido por fecha límite |
| Ambos | Ambos |

### 2. Verificar si existe otro rango disponible

```sql
SELECT * FROM cai_ranges
 WHERE status = 'PENDING'
   AND expiration_date >= CURRENT_DATE
 ORDER BY range_start;
```

Si existe un rango autorizado que nunca se activó, la solución es inmediata. Ve directamente al
paso 1 de la resolución.

### 3. Cuantificar el ritmo de consumo

```sql
SELECT date_trunc('day', issued_at) AS dia, count(*) AS emitidos
  FROM fiscal_documents
 WHERE issued_at >= now() - interval '30 days'
 GROUP BY 1 ORDER BY 1 DESC;
```

Sirve para estimar cuánto durará el rango nuevo y para dimensionar la solicitud siguiente.

---

## Resolución

### Caso A: existe un rango autorizado sin activar

1. Verifica con el contador de la institución que ese rango corresponde al establecimiento y punto
   de emisión correctos, y que su fecha límite está vigente.
2. Actívalo desde el módulo de configuración del panel. **No se activa por consulta directa a la
   base de datos**: la activación debe quedar en la bitácora de auditoría con el usuario responsable.
3. Verifica que la emisión funciona con un documento de prueba en el punto de emisión de pruebas.
4. Procesa la cola de facturas pendientes. Ver más abajo.

### Caso B: no hay rango disponible

**Contención inmediata, en el orden en que aparece:**

1. **Confirma que los pagos siguen registrándose.** Esto es lo más importante. El sistema está
   diseñado para separar el registro del pago de la emisión del documento fiscal, justo para este
   escenario. Verifica:

```sql
SELECT count(*) FROM payments WHERE created_at >= CURRENT_DATE;
```

2. **Activa el modo de facturación diferida** en la configuración. Los pagos se registran, el
   recibo interno se entrega al encargado, y la factura queda encolada.

```sql
SELECT count(*) AS facturas_pendientes
  FROM payments
 WHERE fiscal_document_id IS NULL
   AND requires_invoice = true;
```

3. **Avisa a la dirección y al contador de inmediato.** El trámite lo inicia la institución, no el
   desarrollador. Entrégales el dato concreto: cuántos documentos faltan, desde cuándo, y a qué
   ritmo crece la cola.

4. **Informa al personal de ventanilla** qué decir a los encargados: el pago queda registrado y la
   factura se entregará en cuanto se regularice la situación. Que el mensaje sea uniforme evita
   versiones contradictorias.

5. **Documenta la fecha y hora exacta** en que se dejó de facturar. El contador lo va a necesitar.

**Solución definitiva:**

6. La institución gestiona el rango nuevo ante la autoridad tributaria.
7. Al recibirlo, se registra en el módulo de configuración con todos sus datos: rango desde, rango
   hasta, fecha de autorización, fecha límite de emisión, tipo de documento y punto de emisión.
8. Se activa.
9. Se procesa la cola de facturas pendientes en orden cronológico de pago.

**Advertencia sobre el procesamiento de la cola:** consúltalo con el contador antes de ejecutarlo.
La fecha que debe llevar cada documento, si la del pago o la de emisión, es una decisión fiscal y no
técnica. Emitir con el criterio equivocado obliga a anular y volver a emitir.

---

## Verificación

- [ ] `SELECT` sobre `cai_ranges` muestra un rango activo, vigente y con disponibles suficientes
- [ ] Un documento de prueba se emite correctamente en el punto de emisión de pruebas
- [ ] El correlativo asignado es el siguiente del rango nuevo, sin saltos inexplicados
- [ ] La cola de facturas pendientes está en cero, o su procesamiento está autorizado y programado
- [ ] Las alertas de consumo y de vencimiento están configuradas para el rango nuevo
- [ ] La activación quedó registrada en la bitácora de auditoría
- [ ] El contador confirmó que la numeración es correcta

---

## Prevención

Este incidente **no debería ocurrir nunca**, porque es completamente predecible. Si ocurrió, falló
la prevención, no la operación.

| Control | Umbral | Acción automática |
|---|---|---|
| Consumo del rango | 70% | Aviso informativo en el panel de administración |
| Consumo del rango | 85% | Alerta a la dirección y al contador, por correo |
| Consumo del rango | 95% | Alerta crítica diaria hasta que se resuelva |
| Días hasta la fecha límite | 60 | Aviso informativo |
| Días hasta la fecha límite | 30 | Alerta a la dirección y al contador |
| Días hasta la fecha límite | 15 | Alerta crítica diaria |

Reglas de proceso:

1. **Solicitar el rango siguiente al llegar al 70 por ciento**, no al 95. El trámite tarda y el
   consumo se acelera en los meses de matrícula.
2. **Mantener siempre un rango de reserva registrado y sin activar.** Es el control que convierte
   este incidente crítico en un cambio de configuración de dos minutos.
3. **Revisión mensual de rangos** por parte del contador, como punto fijo.
4. Dimensionar cada solicitud con el consumo real de los últimos doce meses más un margen del
   cincuenta por ciento.

---

## Escalamiento

| Situación | A quién | Cuándo |
|---|---|---|
| Rango agotado o vencido sin reserva | Dirección y contador | Inmediato |
| Duda sobre la fecha de emisión de la cola pendiente | Contador | Antes de procesar |
| Trámite demorado más de lo previsto | Dirección | Diario, con el tamaño de la cola |
| Correlativo inconsistente detectado | Contador | Antes de reanudar la emisión |

---

## Registro del incidente

- Fecha y hora en que se dejó de facturar y en que se reanudó
- Cantidad y monto de las facturas que quedaron pendientes
- Por qué falló la prevención: qué alerta no llegó, o llegó y no se atendió
- Datos del rango nuevo
- Criterio de fecha usado al procesar la cola y quién lo autorizó
