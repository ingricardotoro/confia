# Runbook: Restauración de respaldo

- **Severidad:** Crítica
- **Tiempo objetivo de resolución:** 4 horas (objetivo de tiempo de recuperación)
- **Pérdida máxima aceptable:** 15 minutos (objetivo de punto de recuperación)
- **Última prueba de este procedimiento:** pendiente

---

## Síntoma

Cualquiera de estos:

- La base de datos no arranca o reporta corrupción.
- Se ejecutó una operación destructiva por error, como una migración mal aplicada o un borrado
  masivo.
- Se detectó corrupción lógica de datos financieros que no se puede corregir con asientos.
- El servidor se perdió por completo.
- Se necesita consultar el estado de los datos en un momento anterior para una investigación.

## Impacto si no se atiende

La institución no puede cobrar, facturar ni consultar estados de cuenta. En pérdida total sin
restauración, la información financiera de la institución desaparece, incluida la que respalda su
declaración fiscal.

---

## Regla que nunca se rompe

> **Nunca se restaura directamente sobre la base de datos de producción.**
>
> Se restaura primero en un entorno aislado, se verifica ahí, y solo después se decide qué hacer
> con producción. Restaurar encima de producción destruye la evidencia de qué pasó y, si el
> respaldo estaba mal, deja a la institución sin nada.

---

## Diagnóstico

### 1. Determinar el alcance

```bash
# Estado de la instancia RDS (consola de AWS o CLI equivalente; comando exacto validado en F0)
aws rds describe-db-instances --db-instance-identifier confia-production

# Estado de la instancia de aplicacion y sus contenedores
docker compose -f docker-compose.yml -f docker-compose.prod.yml ps
docker compose -f docker-compose.yml -f docker-compose.prod.yml logs --tail=200 api-admin
```

Responde antes de tocar nada:

- ¿RDS está caído, o está arriba pero con datos incorrectos?
- ¿Cuándo empezó el problema? Es el dato que determina el punto de restauración.
- ¿Qué operación lo provocó?
- **¿Es un fallo normal dentro de AWS, o una pérdida total de la cuenta de AWS?** Determina cuál de
  las dos rutas de restauración de la sección siguiente aplica (ADR-0014).

### 2. Verificar que existen respaldos utilizables

```bash
# Dentro de AWS: instantaneas automaticas y ultimo punto restaurable de RDS
aws rds describe-db-instances --db-instance-identifier confia-production \
  --query 'DBInstances[0].LatestRestorableTime'

# Fuera de AWS: respaldo logico cifrado mas reciente en el almacenamiento fuera de sitio
aws --endpoint-url "${OFFSITE_S3_ENDPOINT}" s3 ls "s3://${OFFSITE_S3_BUCKET}/nightly/" | tail -20
```

Confirma que existe un punto de recuperación **anterior** al inicio del problema: el punto
restaurable de RDS para un fallo normal dentro de AWS, o el respaldo lógico fuera de sitio más
reciente si la cuenta de AWS se perdió por completo (ADR-0014).

### 3. Congelar la operación

Si la base sigue en pie pero con datos incorrectos, detén las escrituras antes de que el problema
crezca.

```bash
docker compose -f docker-compose.yml -f docker-compose.prod.yml stop api-admin api-portal worker
```

Avisa a la institución de inmediato que el sistema está fuera de servicio y por cuánto tiempo
estimado.

---

## Resolución

Hay dos rutas. Usa la ruta A salvo que la cuenta de AWS esté totalmente perdida o comprometida sin
remedio: es la que cumple el objetivo de punto de recuperación de 15 minutos (ADR-0014).

### Ruta A: fallo normal dentro de AWS — restauración a un punto en el tiempo de RDS

#### Paso A1: Restaurar RDS a una instancia nueva

```bash
export RESTORE_TARGET="2026-01-15T14:30:00Z"   # momento al que se quiere volver, UTC

aws rds restore-db-instance-to-point-in-time \
  --source-db-instance-identifier confia-production \
  --target-db-instance-identifier confia-restore-check \
  --restore-time "$RESTORE_TARGET"
```

La instancia restaurada nace en la misma VPC, sin acceso público, y no sustituye la instancia de
producción hasta la sección "Promoción a producción". El nombre exacto de los parámetros de red y
de grupo de seguridad de la instancia restaurada se valida en F0.

#### Paso A2: Esperar a que la instancia restaurada esté disponible

```bash
aws rds wait db-instance-available --db-instance-identifier confia-restore-check
```

### Ruta B: pérdida total de la cuenta de AWS — respaldo lógico fuera de sitio

#### Paso B1: Descargar y descifrar el respaldo lógico más reciente

```bash
aws --endpoint-url "${OFFSITE_S3_ENDPOINT}" s3 cp \
  "s3://${OFFSITE_S3_BUCKET}/nightly/<timestamp>/confia-nightly-<timestamp>.dump.age" .

age --decrypt --identity /secure/confia-backup.key \
  "confia-nightly-<timestamp>.dump.age" > "confia-nightly-<timestamp>.dump"
```

La llave privada de descifrado vive fuera de AWS y fuera del repositorio (ADR-0014); nunca en el
mismo host que se está reconstruyendo.

#### Paso B2: Restaurar en un PostgreSQL limpio (en el proveedor alternativo del plan de salida, o local para verificación)

```bash
createdb confia_restore_check
pg_restore --dbname=confia_restore_check "confia-nightly-<timestamp>.dump"
```

El objetivo de punto de recuperación ante este escenario es el del último respaldo lógico fuera de
sitio, no 15 minutos: es el límite aceptado explícitamente en ADR-0014.

---

## Verificación

**No continúes sin completar los cinco puntos.** Un respaldo restaurado que no se verificó no es una
recuperación: es una suposición. Ejecuta las consultas contra `confia-restore-check` (ruta A) o
`confia_restore_check` (ruta B), nunca contra la instancia de producción.

### 1. La base responde y tiene la fecha esperada

```sql
SELECT now();
SELECT max(recorded_at) FROM ledger_transactions;
SELECT max(created_at) FROM payments;
```

El último movimiento debe ser anterior o igual al momento objetivo.

### 2. Los conteos son razonables

```sql
SELECT
  (SELECT count(*) FROM students)             AS estudiantes,
  (SELECT count(*) FROM payments)             AS pagos,
  (SELECT count(*) FROM ledger_transactions)  AS transacciones,
  (SELECT count(*) FROM fiscal_documents)     AS documentos_fiscales;
```

Compara con los últimos valores conocidos. Una caída brusca indica que el respaldo está incompleto.

### 3. El libro mayor cuadra

Es la verificación que realmente importa.

```sql
SELECT t.id,
       SUM(CASE WHEN e.direction = 'DEBIT' THEN e.amount ELSE -e.amount END) AS diferencia
  FROM ledger_transactions t
  JOIN ledger_entries e ON e.transaction_id = t.id
 GROUP BY t.id
HAVING SUM(CASE WHEN e.direction = 'DEBIT' THEN e.amount ELSE -e.amount END) <> 0;
```

**Debe devolver cero filas.** Si devuelve alguna, la restauración quedó a mitad de una transacción y
el punto de restauración elegido no sirve. Elige un punto anterior y repite.

### 4. La cadena de auditoría está íntegra

```sql
SELECT count(*) AS eslabones_rotos
  FROM audit_log a
  JOIN audit_log p ON p.id = a.previous_id
 WHERE a.previous_hash <> p.record_hash;
```

Debe devolver cero.

### 5. Los correlativos fiscales son coherentes

```sql
SELECT point_of_sale, document_type, max(sequence_number) AS ultimo
  FROM fiscal_documents
 GROUP BY point_of_sale, document_type;
```

Compara contra `cai_ranges.current_number`. El contador del rango no puede ser menor que el último
documento emitido: eso produciría correlativos duplicados al reanudar. Si ocurre, **ajusta el
contador del rango al máximo emitido antes de reanudar la facturación** y documenta el ajuste.

---

## Promoción a producción

Solo después de que las cinco verificaciones pasen.

1. **Obtén autorización explícita de la dirección de la institución.** Restaurar implica perder los
   datos posteriores al punto elegido. Esa pérdida es una decisión del negocio, no del desarrollador.
2. Documenta qué se pierde: cuántos pagos, de qué monto, entre qué horas.
3. Preserva la instancia de producción actual antes de reemplazarla, aunque esté corrupta o
   caída. Es evidencia: no la elimines.

**Ruta A (RDS restaurado dentro de AWS):**

```bash
# Renombra la instancia de produccion actual y promueve la instancia restaurada a su lugar.
# El mecanismo exacto (renombrar ambas instancias, o repuntar SPRING_DATASOURCE_URL al nuevo
# punto de conexion) se valida en F0.
aws rds modify-db-instance --db-instance-identifier confia-production \
  --new-db-instance-identifier confia-production-incidente-$(date -u +%Y%m%d%H%M)
aws rds modify-db-instance --db-instance-identifier confia-restore-check \
  --new-db-instance-identifier confia-production
```

**Ruta B (respaldo lógico fuera de sitio, en el proveedor alternativo del plan de salida):**
sigue el plan de salida de ADR-0014: aprovisiona PostgreSQL en el proveedor destino con la
infraestructura como código correspondiente y restaura ahí el respaldo verificado.

4. Actualiza `SPRING_DATASOURCE_URL` al nuevo punto de conexión si cambió.
5. Levanta la aplicación y verifica el acceso, un estado de cuenta conocido, y la emisión de un
   documento de prueba en el punto de emisión de pruebas.
6. Comunica a la institución qué operaciones deben rehacerse.

---

## Prevención

| Control | Cadencia |
|---|---|
| Respaldo automático de RDS con restauración a un punto en el tiempo (ADR-0014) | Continuo, gestionado por AWS |
| Respaldo lógico fuera de sitio, cifrado (`infra/scripts/backup.sh`) | Diario, nocturno |
| Verificación automática de que el respaldo se creó y tiene tamaño razonable | Diaria, con alerta si falla |
| **Simulacro completo de restauración** | **Mensual, con acta firmada** |
| Simulacro de salida: el simulacro mensual ejecutado en un proveedor distinto de AWS (ADR-0014) | Anual, con acta firmada |
| Ejercicio de recuperación ante desastre con reconstrucción completa del entorno, adicional al simulacro mensual (`docs/03-seguridad.md`, sección 19) | Semestral |
| Verificación de que la llave de descifrado es accesible y no vive solo en el servidor | Mensual |

El simulacro mensual es el único control que realmente prueba que los respaldos sirven. Los demás
prueban que existen, que no es lo mismo.

---

## Escalamiento

| Situación | A quién |
|---|---|
| Pérdida de datos posterior al punto de restauración | Dirección de la institución, para autorizar |
| Documentos fiscales afectados | Contador de la institución |
| Ningún respaldo utilizable | Dirección, de inmediato. Es un evento de continuidad del negocio |
| Fallo del proveedor de hosting | Soporte del proveedor, en paralelo |

---

## Registro del incidente

Documenta y guarda junto al acta:

- Momento de detección y de resolución
- Causa raíz
- Punto de restauración elegido y por qué
- Datos perdidos, cuantificados
- Quién autorizó la restauración
- Resultado de las cinco verificaciones
- Qué se cambia para que no se repita
