# Runbook: Cierre de caja con diferencia

- **Severidad:** Alta
- **Tiempo objetivo de resolución:** el mismo día del cierre
- **Última prueba de este procedimiento:** pendiente

---

## Síntoma

Al cerrar una sesión de caja, el conteo declarado de efectivo no coincide con el total esperado
calculado por el sistema.

- **Faltante:** hay menos efectivo del esperado.
- **Sobrante:** hay más efectivo del esperado.

## Impacto si no se atiende

Una diferencia sin investigar se normaliza. Si el sistema acepta diferencias sin registro ni
seguimiento, la caja deja de ser un control y se convierte en un trámite. El daño no es el monto de
una diferencia: es la pérdida del control interno completo.

Además, y esto importa tanto como lo anterior: **un cajero honesto necesita este procedimiento para
poder demostrar que lo es.** Sin arqueo formal, una acusación no tiene forma de refutarse.

---

## Principio de tratamiento

Una diferencia es **un hecho a registrar y explicar**, no una acusación.

La mayoría de las diferencias tienen causas mundanas: un vuelto mal dado, un billete pegado, un pago
registrado con el método equivocado. Tratar cada diferencia como sospecha de fraude destruye la
confianza del equipo y consigue que la gente oculte diferencias en lugar de reportarlas, que es
exactamente lo contrario de lo que se busca.

**Un sobrante es tan importante como un faltante.** Un sobrante recurrente suele significar que
alguien está cobrando de más a los encargados.

---

## Umbrales

Configurables por institución. Valores de referencia:

| Diferencia | Tratamiento |
|---|---|
| Cero | Cierre directo |
| Hasta L 50.00 o el 0.1 por ciento de lo recaudado | Justificación escrita del cajero. Cierre permitido |
| Sobre ese umbral | Justificación más **autorización de un supervisor**. El cajero no puede cerrar solo |
| Sobre L 500.00 | Además, investigación obligatoria el mismo día |
| Tercera diferencia del mismo cajero en un mes | Investigación obligatoria, sin importar el monto |

La regla de la tercera diferencia importa: tres faltantes pequeños son un patrón, y un patrón dice
más que un monto aislado.

---

## Diagnóstico

Sigue el orden. Las causas están ordenadas de más frecuente a menos frecuente, y encontrar la
primera evita seguir investigando.

### 1. Recontar el efectivo

Antes de cualquier consulta. El error de conteo es, por amplio margen, la causa más común. Que lo
cuente una segunda persona.

### 2. Verificar el fondo inicial

```sql
SELECT id, cashier_id, opened_at, opening_float, expected_cash, declared_cash,
       declared_cash - expected_cash AS diferencia
  FROM cash_sessions
 WHERE id = '<session_id>';
```

¿El fondo inicial declarado al abrir corresponde al efectivo que realmente había? Un error en la
apertura arrastra toda la sesión.

### 3. Revisar los movimientos por método de pago

Un pago con tarjeta registrado como efectivo produce un faltante exacto por ese monto. Es la segunda
causa más común.

```sql
SELECT payment_method, count(*), SUM(amount) AS total
  FROM cash_movements
 WHERE cash_session_id = '<session_id>'
 GROUP BY payment_method;
```

Busca en la lista de pagos del turno un monto que coincida exactamente con la diferencia. Una
coincidencia exacta casi siempre identifica el movimiento problemático.

```sql
SELECT * FROM cash_movements
 WHERE cash_session_id = '<session_id>'
   AND amount = <diferencia_absoluta>;
```

### 4. Revisar retiros y depósitos intermedios

```sql
SELECT * FROM cash_movements
 WHERE cash_session_id = '<session_id>'
   AND movement_type IN ('WITHDRAWAL','DEPOSIT','EXPENSE')
 ORDER BY occurred_at;
```

Un retiro para depósito bancario sin registrar produce un faltante grande y perfectamente explicable.

### 5. Revisar reversos y anulaciones del turno

```sql
SELECT * FROM payments
 WHERE cash_session_id = '<session_id>'
   AND status IN ('REVERSED','VOIDED')
 ORDER BY created_at;
```

Un pago en efectivo reversado sin devolver el efectivo produce sobrante. Con el efectivo devuelto
pero sin reverso registrado, produce faltante.

### 6. Revisar la bitácora de auditoría del turno

```sql
SELECT occurred_at, actor_id, action, entity_type, entity_id
  FROM shared_audit_log
 WHERE occurred_at BETWEEN '<opened_at>' AND now()
   AND actor_id = '<cashier_id>'
 ORDER BY occurred_at;
```

---

## Resolución

### Si se identifica la causa y es un error de registro

1. **No se edita el pago.** Se corrige con el mecanismo que corresponda: reverso más registro
   correcto, o ajuste del movimiento de caja con motivo.
2. Se recalcula el esperado.
3. Se cierra la sesión con la diferencia resultante, que en la mayoría de los casos pasa a cero.
4. Se documenta la causa en la justificación del cierre.

### Si se identifica la causa y es efectivo realmente faltante o sobrante

1. Se cierra la sesión **registrando la diferencia real**. No se maquilla ni se ajusta el esperado
   para que cuadre.
2. Se registra la justificación con el detalle de lo investigado.
3. Se aplica la política de la institución: reposición del faltante, registro del sobrante como
   ingreso a regularizar, u otra según su reglamento.
4. Se obtiene la autorización del supervisor si supera el umbral.

### Si no se identifica la causa

1. Se cierra registrando la diferencia como no explicada. **Este estado es legítimo y debe existir**:
   forzar una explicación inventada es peor que registrar honestamente que no se sabe.
2. Se escala al supervisor.
3. Se marca para seguimiento y se revisa si hay patrón con cierres anteriores del mismo cajero o del
   mismo punto de emisión.

---

## Verificación

- [ ] El efectivo fue recontado por una segunda persona
- [ ] Se revisaron las cinco causas del diagnóstico
- [ ] La sesión quedó cerrada con la diferencia real registrada, sin ajustes cosméticos
- [ ] La justificación describe qué se investigó, no solo el monto
- [ ] La autorización del supervisor está registrada si el umbral lo exigía
- [ ] El reporte de cierre está impreso y firmado
- [ ] Cualquier corrección se hizo por reverso o ajuste, nunca por edición
- [ ] Se revisó si hay patrón con cierres anteriores

---

## Prevención

| Control | Cómo |
|---|---|
| Un cajero, una sesión | El sistema no permite dos sesiones simultáneas del mismo usuario |
| Sin cobro fuera de sesión | El registro de pago en efectivo requiere sesión abierta |
| Total esperado visible antes del conteo | Deliberado, para que el proceso sea transparente |
| Arqueos parciales durante el turno | Permitidos, para detectar la diferencia cerca de su origen |
| Retiros registrados en el momento | Nunca al final del turno de memoria |
| Método de pago confirmado en el paso de confirmación | Reduce el error de registro, que es la causa más común |
| Reporte mensual de diferencias por cajero | Detecta patrones que un cierre aislado no muestra |
| Capacitación del personal de ventanilla | El error de registro se reduce con práctica, no con controles |

---

## Escalamiento

| Situación | A quién | Cuándo |
|---|---|---|
| Diferencia sobre el umbral | Supervisor | Antes de cerrar |
| Diferencia sobre L 500.00 | Administración | El mismo día |
| Tercera diferencia del mismo cajero en un mes | Administración y recursos humanos | Al detectarse el patrón |
| Indicio de manipulación de registros | Dirección, y tratar como incidente de seguridad | Inmediato |
| Diferencia con documentos fiscales involucrados | Contador | El mismo día |

---

## Registro del incidente

En el reporte de cierre queda: monto esperado, monto declarado, diferencia, causa identificada o su
ausencia, qué se investigó, correcciones aplicadas, autorización del supervisor, y firma del cajero.

El reporte se conserva junto con los demás cierres del período y alimenta el reporte mensual de
diferencias.
