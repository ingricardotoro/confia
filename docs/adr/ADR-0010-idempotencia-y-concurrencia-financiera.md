# ADR-0010: Idempotencia y control de concurrencia en operaciones financieras

- **Estado:** Aceptado
- **Fecha:** 2026-09-10
- **Decisores:** Propietario del producto y arquitecto
- **Contexto técnico:** `modules/payments`, `modules/invoicing`, `modules/cashbox`, `modules/ledger`, `shared/security`

## Contexto y problema

En un sistema de cobros, cuatro escenarios de concurrencia producen daño real y ninguno es
hipotético. Todos ocurren en las primeras semanas de uso.

**Uno. El doble clic del cajero.** Hay una fila de padres esperando. El cajero presiona registrar
pago, la respuesta tarda, presiona otra vez. Sin protección, el estudiante queda con dos pagos de
tres mil doscientos lempiras y la institución con un saldo a favor que nadie sabe explicar.

**Dos. El reintento del webhook de la pasarela.** Las pasarelas de pago reintentan cuando no
reciben confirmación a tiempo. Es su comportamiento correcto y documentado. Un webhook procesado
dos veces acredita dos veces el mismo pago del banco.

**Tres. Dos cajeros cobrando al mismo estudiante.** Un padre paga en la ventanilla uno mientras la
madre paga en la ventanilla dos. Ambas transacciones leen el mismo saldo pendiente, ambas aplican
contra el mismo cargo, y el cargo termina con más dinero aplicado del que debía.

**Cuatro. Dos facturas con el mismo correlativo.** Dos emisiones simultáneas leen el último
correlativo usado, ambas calculan el siguiente y ambas lo toman. El resultado son dos documentos
fiscales con el mismo número, que es una infracción tributaria y no un defecto de software que se
pueda corregir después.

El problema común es que la corrección de estas operaciones no depende del código de negocio sino
de garantías de nivel inferior. Un caso de uso perfectamente escrito falla si dos copias suyas se
ejecutan a la vez.

## Factores de decisión

| Factor | Peso | Razón |
|---|---|---|
| Imposibilidad de doble cobro | Muy alto | Es dinero de un padre de familia y destruye la confianza institucional |
| Unicidad del correlativo fiscal | Muy alto | Un correlativo duplicado es una infracción, no un error corregible |
| Simplicidad operativa | Alto | Un solo desarrollador no puede depurar corrupción intermitente |
| Rendimiento en ventanilla | Medio | El cajero no puede esperar, pero corregir un cobro duplicado cuesta mucho más que medio segundo |
| Reversibilidad | Alto | Cuando algo falla, debe fallar de forma limpia y visible |

## Opciones consideradas

### Opción A: Confiar en la interfaz de usuario

Deshabilitar el botón después del primer clic y evitar el reenvío del formulario.

**Ventajas.** Trivial. Sin costo en el servidor.

**Desventajas.** No es un control. No protege contra reintentos de red, contra el webhook de la
pasarela, contra dos pestañas abiertas ni contra dos usuarios distintos. Un control que vive solo
en el cliente no es un control: es una sugerencia.

### Opción B: Detección por heurística de duplicados

Rechazar un pago si existe otro con el mismo estudiante, monto y fecha en los últimos minutos.

**Ventajas.** No requiere cambios en el contrato de la API.

**Desventajas.** Produce falsos positivos que bloquean operaciones legítimas: dos hermanos con la
misma colegiatura pagados uno tras otro, o un padre que abona dos veces el mismo monto a
propósito. Y produce falsos negativos, porque la ventana de tiempo siempre es arbitraria. Convierte
una garantía en una adivinanza.

### Opción C: Bloqueo optimista con número de versión

Cada cuenta lleva una versión. Si cambió entre la lectura y la escritura, la operación falla y se
reintenta.

**Ventajas.** Sin bloqueos sostenidos. Buen rendimiento con poca contención.

**Desventajas.** Traslada la complejidad del reintento al cliente y al caso de uso. En ventanilla,
un fallo por conflicto que obliga a reintentar es una mala experiencia con una fila esperando. Y no
resuelve por sí solo el problema del correlativo fiscal ni el del webhook repetido.

### Opción D: Idempotencia explícita más bloqueo pesimista más aislamiento serializable

Cada control ataca un escenario distinto, en la capa donde el escenario ocurre.

**Ventajas.** Cobertura completa de los cuatro escenarios. Garantías declaradas y verificables. El
comportamiento ante repetición es determinista y explicable a un auditor.

**Desventajas.** Más infraestructura: una tabla de idempotencia con su ciclo de vida. Los bloqueos
reducen la concurrencia. El aislamiento serializable puede abortar transacciones y requiere manejo
de reintento en el servidor.

## Decisión

**Se adopta la opción D, aplicando cada control al escenario que le corresponde.**

| Escenario | Control | Capa |
|---|---|---|
| Doble clic del cajero | Clave de idempotencia obligatoria | Contrato de la API |
| Reintento de webhook de la pasarela | Clave de idempotencia derivada del identificador del evento del proveedor | Contrato de la API |
| Dos cajeros sobre el mismo estudiante | Bloqueo pesimista sobre la cuenta | Base de datos |
| Dos facturas con el mismo correlativo | Secuencia con bloqueo, dentro de una transacción serializable | Base de datos |

La razón de no elegir un control único es que no existe: la idempotencia protege contra la
repetición de la misma operación, mientras que el bloqueo protege contra la interferencia de
operaciones distintas. Son problemas diferentes y confundirlos deja un hueco.

## Control uno: idempotencia

### Contrato

Todo endpoint que mueva dinero exige la cabecera `Idempotency-Key` con un identificador único
generado por el cliente. La ausencia de la cabecera devuelve `400`.

Comportamiento definido:

| Situación | Respuesta |
|---|---|
| Clave nueva | Se ejecuta la operación y se almacena el resultado |
| Clave repetida, misma carga útil, operación completada | Se devuelve la respuesta original con `200` y la cabecera `Idempotent-Replay: true`. No se ejecuta nada |
| Clave repetida, misma carga útil, operación en curso | `409` con indicación de reintentar |
| Clave repetida, **carga útil distinta** | `422`. Es un error del cliente y nunca se ejecuta |
| Clave con más de veinticuatro horas | Se trata como nueva. Ver retención |

El caso de la clave repetida con carga útil distinta es el que más se pasa por alto y el más
peligroso. Significa que el cliente reutilizó una clave para otra operación, y ejecutar cualquiera
de las dos interpretaciones sería adivinar. Se rechaza.

La comparación se hace sobre un hash de la carga útil canonicalizada, no sobre el texto crudo,
para que un cambio de orden de claves en el JSON no produzca un falso conflicto.

### Esquema

```sql
CREATE TABLE idempotency_keys (
  id             UUID PRIMARY KEY,
  institution_id UUID NOT NULL,
  endpoint       TEXT NOT NULL,
  idempotency_key TEXT NOT NULL,
  request_hash   TEXT NOT NULL,          -- SHA-256 de la carga util canonicalizada
  status         TEXT NOT NULL CHECK (status IN ('IN_PROGRESS','COMPLETED','FAILED')),
  response_status INTEGER,
  response_body  JSONB,
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  completed_at   TIMESTAMPTZ,
  expires_at     TIMESTAMPTZ NOT NULL,
  CONSTRAINT uq_idempotency UNIQUE (endpoint, idempotency_key)
);
```

La restricción única es el control real. El registro se inserta en estado en curso **antes** de
ejecutar la operación, dentro de la misma transacción, de modo que dos peticiones simultáneas con
la misma clave hagan que la segunda choque contra la restricción en lugar de ejecutarse.

### Retención

Las claves se conservan **veinticuatro horas** y luego se eliminan con un trabajo programado. El
período cubre con margen cualquier reintento razonable de cliente o de pasarela, sin dejar crecer
la tabla de forma indefinida. La eliminación de una clave no afecta al pago, que ya está en el
libro mayor de forma permanente.

### Origen de la clave

| Cliente | Origen de la clave |
|---|---|
| Panel administrativo | UUID generado al abrir el formulario, no al enviarlo. Un reenvío del mismo formulario reutiliza la clave |
| Portal de encargados | Igual |
| Webhook de la pasarela | Identificador del evento del proveedor, prefijado con el nombre del proveedor |
| Trabajos internos | Identificador determinista del trabajo, por ejemplo la generación de cargos de un período, para que reejecutarlo no duplique |

Que la clave se genere al **abrir** el formulario y no al enviarlo es lo que hace que el doble clic
quede cubierto. Si se generara al enviar, cada clic produciría una clave distinta y el control no
serviría de nada.

## Control dos: bloqueo pesimista sobre la cuenta

Al aplicar un pago, el caso de uso bloquea la fila de la cuenta del estudiante antes de leer los
cargos pendientes.

```sql
SELECT * FROM student_accounts
 WHERE student_id = $1 AND institution_id = $2
   FOR UPDATE;
```

A partir de ese punto, cualquier otra transacción que intente aplicar un pago al mismo estudiante
espera. Se lee el estado real, se aplica, se registra en el libro mayor y se libera al confirmar.

Reglas obligatorias:

- El bloqueo se toma **siempre en el mismo orden** cuando una operación afecta a varias cuentas,
  ordenando por identificador de estudiante. Sin esta regla aparecen interbloqueos.
- El tiempo de espera de bloqueo se limita para que una transacción colgada no bloquee la
  ventanilla de forma indefinida.
- El bloqueo cubre exclusivamente la operación financiera. Ninguna llamada externa, ningún envío
  de correo y ninguna generación de PDF ocurre con el bloqueo tomado.

## Control tres: aislamiento serializable

Se usa `SERIALIZABLE` únicamente en dos operaciones, porque su costo no se justifica en el resto:

| Operación | Razón |
|---|---|
| Cierre de sesión de caja | El conteo esperado debe corresponder a un conjunto de movimientos que no puede cambiar mientras se calcula |
| Emisión de documento fiscal | El correlativo y la validación del rango CAI deben ser atómicos frente a otra emisión |

Una transacción serializable puede abortar con error de serialización. El servidor reintenta de
forma automática hasta tres veces con retroceso, y solo después devuelve error al cliente. El
reintento es seguro precisamente porque la operación es idempotente.

## Control cuatro: secuencia de correlativo fiscal

El correlativo **nunca** se calcula con un máximo más uno. Se obtiene de una fila de contador
bloqueada dentro de la transacción de emisión.

```sql
UPDATE cai_ranges
   SET current_number = current_number + 1
 WHERE id = $1
   AND current_number < range_end
   AND expiration_date >= CURRENT_DATE
RETURNING current_number;
```

Esta sentencia hace cuatro cosas a la vez, y esa es la razón de escribirla así: bloquea la fila,
incrementa, valida que el rango no se agotó y valida que no venció. Si no devuelve ninguna fila, la
emisión se rechaza. No hay ventana entre validar y usar, que es exactamente donde se cuela el
correlativo duplicado.

Un hueco en el correlativo solo es admisible si corresponde a una anulación registrada. Ver
`docs/04-cumplimiento-fiscal-sar.md`.

## Consecuencias

**Positivas.**

- El doble cobro deja de ser posible, no solo improbable.
- El comportamiento ante repetición es determinista y explicable ante un reclamo.
- Los webhooks de la pasarela pueden reintentarse sin riesgo, que es lo que van a hacer.
- El correlativo fiscal es único por construcción.
- Los trabajos de generación de cargos pueden reejecutarse sin duplicar.

**Negativas y costos aceptados.**

- Una tabla adicional con su trabajo de limpieza.
- Los clientes deben generar y enviar la clave. Se encapsula en el cliente de API compartido para
  que no dependa de que cada pantalla lo recuerde.
- Los bloqueos reducen la concurrencia sobre el mismo estudiante. Es aceptable: dos cobros
  simultáneos al mismo estudiante son raros y deben serializarse de todos modos.
- El aislamiento serializable obliga a manejar reintentos en el servidor.

**Riesgos y mitigaciones.**

| Riesgo | Mitigación |
|---|---|
| Interbloqueo por orden de bloqueo inconsistente | Orden canónico obligatorio por identificador, verificado en revisión y con prueba de integración |
| Bloqueo sostenido por una llamada externa | Regla de que ninguna entrada y salida externa ocurre con el bloqueo tomado, verificada en revisión |
| Cliente que genera la clave al enviar en lugar de al abrir | Encapsulado en el cliente de API compartido, con prueba de componente que lo verifica |
| Crecimiento de la tabla de idempotencia | Trabajo de limpieza diario con métrica de tamaño |

## Cumplimiento y verificación

| Control | Mecanismo | Cuándo |
|---|---|---|
| Cabecera obligatoria en escrituras financieras | Interceptor que rechaza la petición sin la cabecera, más prueba de contrato por endpoint | En cada petición y en cada fusión |
| Unicidad de la clave | Restricción única en base de datos | En cada escritura |
| Repetición devuelve el resultado original | Prueba de integración que envía dos veces la misma petición y verifica un solo pago en el libro | En cada fusión |
| Clave repetida con carga distinta rechazada | Prueba de integración específica | En cada fusión |
| Concurrencia sobre la misma cuenta | Prueba de integración con dos transacciones simultáneas contra PostgreSQL real | En cada fusión |
| Correlativo único bajo concurrencia | Prueba de integración con emisiones paralelas que verifica ausencia de duplicados | En cada fusión |
| Ausencia de entrada y salida externa bajo bloqueo | Revisión del agente `confia-code-reviewer` con criterio explícito | En cada fusión |

Ninguna de estas garantías se puede verificar con dobles de prueba. Todas exigen PostgreSQL real,
que es la razón de la decisión tomada en ADR-0008.

## Referencias

- `docs/01-arquitectura.md`, sección 6
- `openspec/specs/payments/spec.md`
- `openspec/specs/invoicing/spec.md`
- `.claude/skills/confia-api-conventions/SKILL.md`
- ADR-0007 sobre el libro mayor de doble partida
- ADR-0008 sobre la estrategia de pruebas
