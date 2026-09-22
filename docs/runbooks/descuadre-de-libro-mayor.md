# Runbook: Descuadre del libro mayor

- **Severidad:** Crítica
- **Tiempo objetivo de resolución:** contención inmediata, resolución en 24 horas
- **Última prueba de este procedimiento:** pendiente

---

## Síntoma

Cualquiera de estos:

- La métrica `ledger_unbalanced_transactions_total` es mayor que cero.
- La verificación nocturna de integridad reportó fallo.
- Un saldo cacheado no coincide con el saldo derivado de los asientos.
- Un usuario reporta un saldo que no corresponde a los movimientos visibles.

## Impacto si no se atiende

Los saldos del sistema dejan de ser confiables. La institución no puede sostener sus números ante un
auditor, ante contabilidad ni ante un padre de familia que reclame. Un descuadre que se propaga
contamina todos los reportes posteriores.

---

## Regla que nunca se rompe

> **Nunca corrijas un descuadre con `UPDATE` o `DELETE` sobre el libro mayor.**
>
> Toda corrección se hace con asientos nuevos: un reverso, o un ajuste con motivo y autorización.
> Modificar un asiento existente destruye la evidencia de qué pasó, que es exactamente lo que un
> auditor va a pedir, y convierte un incidente contenido en una pérdida permanente de confiabilidad.
>
> Los permisos de base de datos del rol de la aplicación ya impiden esta operación. Si alguien logra
> ejecutarla, es porque usó un rol administrativo, y eso es en sí mismo un incidente que reportar.

---

## Diagnóstico

### 1. Identificar las transacciones descuadradas

```sql
SELECT t.id,
       t.transaction_type,
       t.occurred_at,
       t.recorded_at,
       t.source_type,
       t.source_id,
       t.created_by,
       SUM(CASE WHEN e.direction = 'DEBIT' THEN e.amount ELSE -e.amount END) AS diferencia,
       count(e.id) AS asientos
  FROM ledger_transactions t
  JOIN ledger_entries e ON e.transaction_id = t.id
 GROUP BY t.id
HAVING SUM(CASE WHEN e.direction = 'DEBIT' THEN e.amount ELSE -e.amount END) <> 0
 ORDER BY t.recorded_at;
```

Anota los identificadores. Son la evidencia principal.

### 2. Buscar transacciones con un solo asiento

Un asiento huérfano es la forma más común de descuadre y no siempre aparece en la consulta anterior.

```sql
SELECT t.id, t.transaction_type, t.recorded_at, count(e.id) AS asientos
  FROM ledger_transactions t
  LEFT JOIN ledger_entries e ON e.transaction_id = t.id
 GROUP BY t.id
HAVING count(e.id) < 2;
```

### 3. Determinar el alcance

```sql
-- Estudiantes afectados
SELECT DISTINCT e.student_id
  FROM ledger_entries e
 WHERE e.transaction_id IN ( /* ids del paso 1 */ );

-- Cuanto tiempo lleva el problema
SELECT min(t.recorded_at) AS primera_ocurrencia
  FROM ledger_transactions t
 WHERE t.id IN ( /* ids del paso 1 */ );
```

Si la primera ocurrencia es reciente y aislada, el alcance está contenido. Si es antigua y recurrente,
hay un defecto de código activo y el paso siguiente es obligatorio.

### 4. Verificar los saldos cacheados

```sql
SELECT c.student_id,
       c.cached_balance,
       COALESCE(SUM(CASE WHEN e.direction = 'DEBIT' THEN e.amount ELSE -e.amount END), 0) AS derivado,
       c.cached_balance - COALESCE(SUM(CASE WHEN e.direction = 'DEBIT' THEN e.amount ELSE -e.amount END), 0) AS diferencia
  FROM student_account_balances c
  LEFT JOIN ledger_entries e
         ON e.student_id = c.student_id
        AND e.account_code = 'AR'
 GROUP BY c.student_id, c.cached_balance
HAVING c.cached_balance <> COALESCE(SUM(CASE WHEN e.direction = 'DEBIT' THEN e.amount ELSE -e.amount END), 0);
```

Una diferencia aquí con el libro cuadrado significa que el problema está en la caché, no en el libro.
Es mucho menos grave: la caché se reconstruye. Ver más abajo.

### 5. Revisar la bitácora de auditoría

```sql
SELECT * FROM shared_audit_log
 WHERE entity_id IN ( /* ids del paso 1 */ )
    OR request_id IN (
        SELECT request_id FROM ledger_transactions WHERE id IN ( /* ids */ )
       )
 ORDER BY occurred_at;
```

Identifica quién originó la operación, desde dónde y con qué petición. Si el descuadre proviene de
una operación manual con rol administrativo, el tratamiento pasa a ser el de un incidente de
seguridad. Ver `incidente-de-seguridad.md`.

---

## Contención

### Si el descuadre es recurrente o activo

1. **Detén el trabajador de segundo plano** para que los procesos automáticos no generen más
   transacciones defectuosas.

```bash
docker compose -f infra/docker/docker-compose.prod.yml stop worker
```

2. **Evalúa si detener las escrituras financieras.** Es una decisión de negocio: parar la ventanilla
   tiene costo operativo. Recomendación: si el descuadre proviene de una operación específica que se
   puede identificar y evitar, deshabilita esa operación con la bandera de funcionalidad
   correspondiente y deja el resto operando. Si no se puede aislar, detén las escrituras.

3. **Congela el trabajo de reconstrucción de caché.** Reconstruir la caché sobre un libro descuadrado
   propaga el error y borra la evidencia de la diferencia.

### Si el descuadre es aislado y antiguo

No detengas la operación. Contén con la corrección puntual del paso siguiente.

---

## Resolución

### Paso 1: Determinar cuál es la verdad

Por cada transacción descuadrada, reconstruye qué debió ocurrir desde la operación origen:

```sql
SELECT * FROM payments WHERE id = '<source_id>';
-- o charges, fiscal_documents, segun source_type
```

Compara el importe de la operación origen contra los asientos registrados. La operación origen es la
referencia, porque es el hecho económico real.

### Paso 2: Reversar la transacción defectuosa

Se ejecuta a través del caso de uso de reverso del sistema, **no con SQL directo**.

El reverso crea una transacción nueva con los asientos invertidos y `reverses_id` apuntando a la
original. La transacción defectuosa queda visible, marcada como reversada.

Si la transacción está tan mal formada que el reverso automático no la puede procesar, por ejemplo
porque tiene un solo asiento, se registra un **asiento de ajuste** con:

- Motivo obligatorio que describe el incidente
- Referencia a la transacción defectuosa
- Autorización registrada del responsable
- El importe exacto que restaura el cuadre

### Paso 3: Registrar la transacción correcta

Con los datos de la operación origen, registra la transacción como debió haber sido.

### Paso 4: Reconstruir la caché de saldos

Solo después de que el libro cuadre por completo.

```bash
docker compose -f infra/docker/docker-compose.prod.yml exec api-admin \
  node dist/scripts/rebuild-balances.js --institution <id> --verify
```

La bandera de verificación compara antes de escribir y reporta cada diferencia encontrada.

### Paso 5: Corregir la causa raíz

Un descuadre siempre tiene causa de código o de proceso. Las más frecuentes:

| Causa | Corrección |
|---|---|
| Caso de uso que no envuelve toda la operación en una transacción | Envolver, y agregar prueba de integración |
| Restricción de cuadre no diferida, o ausente | Verificar el disparador y su modo diferido |
| Redondeo que deja diferencia de centavos al repartir | Corregir la política de redondeo y asignar el residuo a un asiento explícito |
| Escritura manual con rol administrativo | Revocar accesos y tratar como incidente de seguridad |
| Fallo parcial sin reversión | Revisar el manejo de errores del caso de uso |

**Escribe primero la prueba que reproduce el descuadre y falla.** Después corrige. Sin esa prueba, la
corrección no está verificada.

---

## Verificación

- [ ] La consulta de transacciones descuadradas devuelve cero filas
- [ ] La consulta de transacciones con menos de dos asientos devuelve cero filas
- [ ] Todos los saldos cacheados coinciden con los derivados
- [ ] Las correcciones aparecen como asientos de reverso o ajuste, **nunca como modificaciones**
- [ ] Cada estudiante afectado tiene su estado de cuenta revisado manualmente
- [ ] La causa raíz está identificada y corregida, con prueba que falla antes del arreglo
- [ ] La métrica de transacciones descuadradas está en cero
- [ ] La cadena de auditoría sigue íntegra
- [ ] Los servicios detenidos están de vuelta en operación

---

## Prevención

| Control | Frecuencia |
|---|---|
| Disparador de restricción diferida que verifica el cuadre en cada escritura | En cada transacción |
| Permisos revocados de actualización y borrado sobre el libro | Permanente |
| Verificación nocturna de integridad con alerta | Diaria |
| Prueba de integración de concurrencia sobre la misma cuenta | En cada fusión |
| Prueba de mutación sobre la lógica del libro, umbral 80 | En cada fusión |
| Revisión obligatoria del agente revisor en todo cambio del módulo de libro mayor | En cada cambio |

---

## Escalamiento

| Situación | A quién | Cuándo |
|---|---|---|
| Descuadre confirmado | Dirección de la institución | En cuanto se confirma el alcance |
| Documentos fiscales afectados | Contador | Inmediato |
| Estudiantes con saldo incorrecto ya comunicado a las familias | Dirección | Antes de corregir, para acordar la comunicación |
| Indicios de manipulación manual | Tratar como incidente de seguridad | Inmediato |

---

## Registro del incidente

- Identificadores de las transacciones afectadas y sus diferencias
- Estudiantes afectados y el impacto en cada saldo
- Ventana temporal del problema
- Causa raíz, con la prueba que la reproduce
- Asientos de corrección aplicados, con quién los autorizó
- Qué control falló y qué se agregó para que no se repita
