# ADR-0009: Esquema preparado para múltiples instituciones, despliegue de una sola

- **Estado:** Aceptado
- **Fecha:** 2026-09-10
- **Decisores:** Propietario del producto y arquitecto
- **Contexto técnico:** Esquema de base de datos completo y sus migraciones de Flyway, `shared/security`, todas las consultas
- **Revisión:** 2026-09-14. Alineado con ADR-0013 (backend en Java con Spring Boot). La decisión no cambia; se actualiza la herramienta de migraciones mencionada en la evaluación de alternativas. La variable de contexto de la política se unifica con `docs/03-seguridad.md` sección 6.2 (`app.institution_id`, leída con `current_setting(..., true)` para que una ausencia devuelva `NULL` y la política deniegue).

## Contexto y problema

CONFIA se construye para una institución concreta. Pero el propietario declara una ambición
comercial: que el sistema tenga nivel profesional e internacional, lo que en la práctica significa
poder venderlo a una segunda institución.

La decisión a tomar hoy es si el modelo de datos contempla desde el inicio la existencia de varias
instituciones, o si eso se resuelve cuando aparezca la segunda.

La trampa está en que **esta decisión parece diferible y no lo es**. Agregar el identificador de
institución después implica tocar cada tabla, cada índice, cada restricción única, cada consulta,
cada política de acceso por fila y cada prueba del sistema. Con un solo desarrollador y un sistema
en producción con dinero real, esa migración no es un refactor: es un proyecto de varios meses con
riesgo de corrupción de datos financieros.

## Factores de decisión

| Factor | Peso | Razón |
|---|---|---|
| Costo de retrofitear más adelante | Muy alto | Es el factor que decide, y es asimétrico |
| Aislamiento real entre instituciones | Muy alto | Los datos financieros de una institución no pueden filtrarse a otra |
| Costo operativo hoy, con una sola institución | Alto | Un desarrollador solo no puede pagar complejidad que no usa |
| Costo de respaldo y restauración por institución | Medio | Restaurar una institución sin afectar a otras |
| Rendimiento con varias instituciones | Medio | Relevante solo cuando exista la segunda |

## Opciones consideradas

### Opción A: Ignorar la multi-institución

Un despliegue por institución, con base de datos y aplicación separadas por completo, sin ningún
concepto de institución en el modelo.

**Ventajas.** El modelo más simple posible hoy. Aislamiento perfecto por construcción. Restaurar
una institución no toca a las demás.

**Desventajas.** Cada institución nueva es un despliegue completo que alguien debe operar,
actualizar, respaldar y monitorear. Con una persona, tres instituciones son tres veces el trabajo
operativo. Además, ningún reporte consolidado es posible, y cualquier cambio de esquema debe
aplicarse por separado en cada despliegue, con el riesgo de que se desincronicen.

### Opción B: Una base de datos por institución, una sola aplicación

La aplicación enruta a la base de datos correspondiente según el subdominio.

**Ventajas.** Aislamiento fuerte. Restauración independiente. Un solo código fuente.

**Desventajas.** La gestión de migraciones se multiplica: cada cambio de esquema debe aplicarse a
todas las bases y verificarse en todas. El grupo de conexiones se fragmenta. La complejidad
operativa crece de forma lineal con las instituciones y recae sobre una sola persona.

### Opción C: Un esquema de PostgreSQL por institución

Una base de datos, varios esquemas.

**Ventajas.** Aislamiento razonable. Respaldo por esquema posible.

**Desventajas.** Las migraciones dejan de aplicarse una sola vez. Con Flyway, la vía habitual para
replicar la estructura en cada esquema es ejecutar las migraciones una vez por esquema de
institución, cada uno con su propio historial, y verificar que todos quedaron en la misma versión.
Es orquestación propia que crece con cada institución. El número de objetos de base de datos crece
rápido y las consultas consolidadas se vuelven incómodas.

### Opción D: Columna discriminadora compartida con seguridad a nivel de fila

Todas las instituciones en las mismas tablas, con `institution_id` en cada una, y políticas de
PostgreSQL que filtran automáticamente por la institución de la sesión.

**Ventajas.** Una sola migración para todas. Una sola conexión. Reportes consolidados triviales. El
aislamiento lo aplica el motor de base de datos, no el código de aplicación. Operativamente es la
más barata, que es lo que un desarrollador solo necesita.

**Desventajas.** Un error en una política de acceso filtra datos entre instituciones. Restaurar una
sola institución de un respaldo es más laborioso. Toda restricción única debe incluir el
identificador de institución, y olvidarlo produce defectos sutiles.

## Decisión

**Se adopta la opción D en el modelo de datos, con despliegue efectivo de una sola institución.**

Concretamente:

1. **Cada tabla del sistema lleva `institution_id NOT NULL` desde la primera migración.** Sin
   excepciones, aunque hoy todas las filas tengan el mismo valor.
2. **Toda restricción única incluye `institution_id`.** El correlativo fiscal es único por
   institución y punto de emisión, no globalmente. El código de estudiante es único por
   institución. Esta es la regla que más se olvida y la que produce los defectos más difíciles de
   diagnosticar.
3. **Todo índice compuesto empieza por `institution_id`.** Es la columna de mayor selectividad en
   cuanto exista la segunda institución.
4. **Las políticas de seguridad a nivel de fila se escriben desde el inicio**, aunque hoy todas
   resuelvan a la misma institución. Escribirlas después obliga a auditar cada consulta ya escrita.
5. **La funcionalidad de administración de instituciones no se construye ahora.** No hay pantalla
   de alta de institución, no hay conmutador de institución en la interfaz, no hay facturación por
   inquilino. Eso se difiere hasta que exista una segunda institución real.

La distinción entre los puntos uno a cuatro y el punto cinco es el corazón de esta decisión. **El
modelo de datos es caro de cambiar después; la funcionalidad es barata de agregar.** Se paga hoy
solo lo que es caro pagar mañana.

El costo actual es marginal: una columna más por tabla y disciplina en las restricciones. El costo
de no hacerlo es una migración de varios meses sobre un sistema con dinero en producción.

## Implementación del aislamiento

El identificador de institución de la sesión se establece al inicio de cada transacción, a partir
del token autenticado y **nunca a partir de un parámetro del cliente**:

```sql
-- Transaction-local and with a bound parameter, as docs/03-seguridad.md section 6.2 requires.
SELECT set_config('app.institution_id', $1, true);
```

Y la política correspondiente en cada tabla:

```sql
ALTER TABLE payments ENABLE ROW LEVEL SECURITY;
ALTER TABLE payments FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON payments
  USING (institution_id = current_setting('app.institution_id', true)::uuid);
```

`FORCE ROW LEVEL SECURITY` importa: sin él, el propietario de la tabla ignora la política, y en un
descuido de configuración el rol de la aplicación podría ser el propietario.

La combinación con la separación entre administración y portal de ADR-0003 es de dos niveles: el
rol del portal filtra además por encargado, de modo que un encargado queda acotado a su institución
y dentro de ella a sus estudiantes.

## Consecuencias

**Positivas.**

- Vender a una segunda institución deja de ser un proyecto de migración y pasa a ser una
  funcionalidad acotada.
- El aislamiento lo garantiza el motor de base de datos, no la memoria del desarrollador.
- Una sola migración, una sola operación, un solo monitoreo.
- Los reportes consolidados serán triviales cuando se necesiten.

**Negativas y costos aceptados.**

- Una columna adicional en cada tabla, hoy con un único valor.
- Disciplina permanente en restricciones únicas e índices.
- Restaurar una sola institución desde un respaldo requiere trabajo de extracción selectiva.
- Un error en una política de acceso tendría consecuencias entre instituciones. Se mitiga con
  pruebas obligatorias.

**Riesgos y mitigaciones.**

| Riesgo | Mitigación |
|---|---|
| Restricción única sin `institution_id` | Verificación automatizada del esquema en integración continua que rechaza toda restricción única que no incluya la columna |
| Consulta que olvida el filtro | La política de acceso por fila lo aplica de todos modos. El código no es la última defensa |
| Identificador de institución tomado del cliente | Regla de revisión explícita y prueba que verifica que solo proviene del token |
| Falsa sensación de estar listo para vender | Este ADR declara explícitamente que la funcionalidad de gestión de inquilinos no existe todavía |

## Cumplimiento y verificación

| Control | Mecanismo | Cuándo |
|---|---|---|
| Columna presente en cada tabla | Prueba que consulta el catálogo de PostgreSQL y falla si alguna tabla de negocio no la tiene | En cada cambio de esquema |
| Restricciones únicas correctas | Prueba que verifica que toda restricción única de tablas de negocio incluye `institution_id` | En cada cambio de esquema |
| Política activa en cada tabla | Prueba que verifica que la seguridad a nivel de fila está habilitada y forzada | En cada cambio de esquema |
| Aislamiento efectivo | Prueba de integración con dos instituciones sembradas que verifica que una no puede leer datos de la otra | En cada fusión |
| Origen del identificador | Revisión con criterio explícito de que nunca proviene del cuerpo ni de la consulta | En cada fusión |

## Referencias

- `docs/01-arquitectura.md`, secciones 5 y 10
- ADR-0003 sobre la separación entre administración y portal
- ADR-0004 sobre PostgreSQL
- ADR-0013 sobre el backend en Java con Spring Boot
- `docs/03-seguridad.md`, sección de seguridad a nivel de fila
