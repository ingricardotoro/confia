# ADR-0016: Trabajos en segundo plano con db-scheduler sobre PostgreSQL

- **Estado:** Aceptado
- **Fecha:** 2026-09-15
- **Decisores:** Propietario del producto y arquitecto
- **Contexto técnico:** Proceso `confia-worker`, casos de uso que programan trabajo diferido en todos los módulos, eventos de dominio de Spring Modulith, `shared/security` (componente transaccional), esquema de PostgreSQL, métricas y alertas de `docs/07-observabilidad-y-operaciones.md`. Resuelve el tema abierto "trabajos en segundo plano" de ADR-0013.

## Contexto y problema

ADR-0013 dejó abierto el reemplazo de BullMQ con una restricción: **ningún intermediario de mensajes
nuevo en la fase uno**. ADR-0014 prohíbe además SQS y cualquier servicio de colas propietario de AWS.

CONFIA necesita ejecutar trabajo fuera de la petición HTTP:

- **Programado:** generación mensual de cargos, trabajo nocturno de integridad del libro mayor
  (ADR-0007), purga de tokens de refresco vencidos (ADR-0005), vigilancia del consumo del rango CAI
  (riesgo R02).
- **Diferido por una acción:** envío de recibos y avisos de cobro, generación de PDF, reportes pesados,
  conciliación bancaria.

Las fuerzas:

- **Ningún trabajo se pierde.** Un recibo que no se envía o un cargo que no se genera es un reclamo
  de un padre de familia o un descuadre.
- **Atomicidad con el negocio.** Si se registra un pago, su recibo debe quedar programado; si la
  transacción del pago se revierte, el recibo no debe existir. Programar el trabajo fuera de la
  transacción produce exactamente esas dos fallas.
- **Separación de procesos (ADR-0003).** El trabajo pesado corre en `confia-worker`, no en los
  procesos que atienden peticiones.
- **Un solo desarrollador.** Nada de infraestructura adicional que operar.
- **Portabilidad (ADR-0014).** El mecanismo debe funcionar igual en AWS, en otro proveedor o en la
  infraestructura propia de la institución.

Si no se decide, cada módulo resuelve el trabajo diferido a su manera: tareas en memoria que se
pierden al reiniciar, `@Scheduled` que se ejecuta dos veces cuando hay dos procesos, o correos
enviados dentro de la transacción financiera con el bloqueo de la cuenta tomado.

## Factores de decisión

| Factor | Peso | Justificación |
|---|---|---|
| Durabilidad: ningún trabajo se pierde ante un reinicio o una caída | Muy alto | Recibos y cargos no se pueden perder. |
| Programación dentro de la misma transacción que el negocio | Muy alto | Evita trabajos huérfanos o faltantes. |
| Sin infraestructura nueva | Muy alto | Restricción de ADR-0013 y de un solo desarrollador. |
| Reintentos con espera creciente y detección de trabajos abandonados | Alto | Los proveedores externos fallan de forma intermitente. |
| Tareas recurrentes con expresión de calendario, ejecutadas una sola vez aunque haya varios procesos | Alto | Cargos mensuales y trabajos nocturnos. |
| Licencia y costo | Alto | Presupuesto modesto. |
| Portabilidad entre proveedores | Alto | ADR-0014. |
| Simplicidad operativa y de esquema | Medio | Menos tablas y menos conceptos que aprender y respaldar. |
| Panel visual de trabajos | Bajo | Útil, pero las métricas y alertas de `docs/07` cubren la vigilancia. |

## Opciones consideradas

### Opción A: db-scheduler sobre PostgreSQL

Biblioteca de programación persistente que usa **una sola tabla** en la base de datos existente para
guardar las tareas y coordinar su ejecución entre procesos.

**Ventajas.**

- **Licencia Apache 2.0**, sin edición comercial que limite funciones.
- **Una sola tabla** en PostgreSQL. Sin servidor adicional; se respalda con la base.
- **Programación dentro de la transacción del negocio**: la programación usa la fuente de datos que
  se le entregue, y con una fuente de datos consciente de las transacciones de Spring participa de la
  transacción en curso. El trabajo existe si y solo si el negocio confirma.
- **Separación entre quien programa y quien ejecuta**: los procesos administrativo y del portal
  pueden programar tareas con un cliente liviano sin ejecutarlas; solo el trabajador las ejecuta.
- Tareas recurrentes con expresiones de calendario al estilo de Spring, tareas únicas con datos,
  manejo de fallas configurable con espera exponencial y número máximo de intentos.
- Pensada para varios procesos a la vez: la coordinación entre instancias la resuelve la propia
  tabla, lo que habilita la fase dos sin cambiar el mecanismo.
- Existe un iniciador para Spring Boot 4.

**Desventajas.**

- No trae panel visual. La vigilancia se construye con métricas propias sobre la tabla.
- La documentación consultada no menciona integración con métricas de Micrometer; las métricas de
  cola se implementan consultando la tabla.
- Hay que diseñar la idempotencia de cada tarea, porque la ejecución es "al menos una vez".

### Opción B: JobRunr, edición de código abierto

**Ventajas.** Panel visual completo, reintentos automáticos, almacenamiento en la base existente,
iniciador para Spring Boot 4. Licencia LGPL versión 3, libre para uso comercial.

**Desventajas.** **La creación de trabajos dentro de una transacción de base de datos existente es
una función de la edición Pro**, no de la edición de código abierto. Sin ella, programar el recibo de
un pago no es atómico con el pago. Se podría compensar programando al confirmar la transacción, pero
eso reabre la falla que este ADR quiere cerrar. **Se descarta** por el factor de mayor peso.

### Opción C: Quartz con almacenamiento JDBC en clúster

**Ventajas.** Muy maduro, iniciador oficial de Spring Boot, clúster sobre la base de datos.

**Desventajas.** Esquema de varias tablas, modelo centrado en calendarios más que en trabajos únicos
con datos, y una API más pesada para el caso más común de CONFIA (enviar un recibo, generar un PDF).
**Es una opción legítima**, pero más compleja que la A para el mismo resultado.

### Opción D: Cola sobre Redis

**Desventajas.** Convierte a Redis en almacén durable del que depende no perder trabajos. ADR-0005 ya
rechazó poner a Redis en el camino crítico por el costo de operarlo con persistencia y alta
disponibilidad. **Se descarta.**

### Opción E: Cola propia sobre PostgreSQL con `FOR UPDATE SKIP LOCKED`

**Desventajas.** Obliga a construir y mantener reintentos, espera exponencial, detección de trabajos
abandonados, tareas recurrentes y coordinación entre procesos: exactamente lo que la opción A ya
resuelve. **Se descarta** por costo de mantenimiento.

### Opción F: Intermediario de mensajes (RabbitMQ, SQS)

**Se descarta** por restricción: ADR-0013 prohíbe un intermediario nuevo en la fase uno y ADR-0014
prohíbe SQS.

## Decisión

**Se adopta la opción A: db-scheduler sobre PostgreSQL para todo el trabajo en segundo plano. Las
tareas se programan dentro de la transacción del negocio y se ejecutan solo en el proceso
`confia-worker`.**

El factor determinante es la combinación de **durabilidad y programación dentro de la misma
transacción, sin infraestructura nueva**. db-scheduler cumple los tres con una tabla en la base que ya
existe y con licencia Apache 2.0. JobRunr pierde solo por la atomicidad, que en su edición gratuita
no está disponible.

### Eventos de dominio frente a trabajos en segundo plano

Son dos mecanismos distintos y cada uno tiene su lugar:

- **Eventos de dominio (Spring Modulith, ADR-0015 regla 10):** comunican módulos dentro del mismo
  proceso. El registro de publicación persiste cada evento y lo reenvía si el oyente falla. Sirven
  para reacciones internas y rápidas.
- **Trabajos en segundo plano (db-scheduler):** trabajo durable que corre en `confia-worker`: todo lo
  que es lento, llama a un proveedor externo o es programado en el calendario.

Cuando un evento necesita trabajo lento o externo, su oyente **programa una tarea**; nunca ejecuta
ese trabajo por sí mismo.

### Reglas de la decisión

1. **Solo `confia-worker` ejecuta tareas.** Los procesos administrativo y del portal solo programan,
   con el cliente liviano de la biblioteca, siempre desde un caso de uso.
2. **Programación transaccional.** La tarea se programa dentro del componente transaccional único
   (ADR-0015), en la misma transacción que el cambio de negocio. El mecanismo concreto (una fuente de
   datos consciente de las transacciones de Spring) se valida en F0 con una prueba.
3. **Datos de tarea sin datos personales.** Una tarea lleva solo identificadores (institución,
   entidad, clave de idempotencia). Nunca nombres, correos, teléfonos, documentos de identidad ni
   importes en claro. El manejador carga lo que necesita dentro de su transacción (`CLAUDE.md`,
   reglas 11 y 15).
4. **Cada ejecución respeta la seguridad a nivel de fila.** El manejador ejecuta su trabajo a través
   del componente transaccional con el contexto de la institución indicada en la tarea y el tipo de
   actor `system` (`docs/03-seguridad.md`, sección 6.2).
5. **Idempotencia obligatoria.** La ejecución es "al menos una vez": cada manejador debe producir un
   solo efecto aunque se ejecute dos veces. Se garantiza con restricciones únicas en la base (por
   ejemplo, un cargo por estudiante, concepto y período; un envío por notificación).
6. **Reintentos acotados.** Cada tipo de tarea declara su número máximo de intentos y su espera
   exponencial. Una tarea que agota sus intentos queda registrada como fallida y dispara una alerta;
   nunca se descarta en silencio.
7. **Nada lento dentro de la transacción financiera.** Envío de correo, generación de PDF y llamadas a
   proveedores externos ocurren en tareas separadas, nunca con el bloqueo de la cuenta tomado
   (ADR-0010).
8. **Tareas recurrentes declaradas en código**, con expresión de calendario y zona horaria explícita
   `America/Tegucigalpa`. Inventario inicial: generación de cargos, integridad nocturna del libro
   mayor, purga de tokens de refresco, vigilancia del rango CAI.
9. **Prohibidos los mecanismos paralelos.** Nada de `@Scheduled`, `@EnableScheduling` ni `@Async` de
   Spring, ni otras bibliotecas de programación: se ejecutarían en cada proceso, sin durabilidad ni
   coordinación.
10. **Vigilancia.** Métricas de tareas pendientes, antigüedad de la tarea vencida más antigua y
    tareas fallidas, calculadas sobre la tabla, con las alertas de "cola atascada" de `docs/07`.

### Excepción al esquema de ADR-0009

ADR-0009 exige `institution_id NOT NULL` en cada tabla. Las tablas técnicas cuyo esquema define una
biblioteca no pueden cumplirlo sin modificarla: la tabla de tareas de db-scheduler, la de publicación
de eventos de Spring Modulith y el historial de migraciones de Flyway. **Este ADR registra la
excepción para esas tres tablas técnicas y solo para ellas.** La institución viaja dentro de los datos
de cada tarea o evento, y el manejador fija el contexto de seguridad antes de tocar cualquier dato de
negocio. La verificación automática de esquema de ADR-0009 las excluye mediante una lista aprobada
que no admite tablas de negocio.

## Consecuencias

**Positivas:**

- Ningún trabajo se pierde ni queda huérfano: existe si y solo si el negocio confirmó.
- Cero infraestructura nueva: la cola vive en PostgreSQL y se respalda con él.
- Portable a cualquier proveedor que ofrezca PostgreSQL (ADR-0014).
- El trabajo pesado no degrada la atención en ventanilla, porque corre en otro proceso.
- La fase dos puede agregar instancias del trabajador sin cambiar el mecanismo.

**Negativas y costos aceptados:**

- **Sin panel visual.** La vigilancia depende de métricas y alertas propias.
- **Idempotencia a cargo de cada manejador**, con su prueba correspondiente.
- **Carga adicional sobre PostgreSQL** por el sondeo de la tabla de tareas. Con el volumen de una
  institución es menor, pero se vigila.
- **Excepción controlada a ADR-0009** para tres tablas técnicas.

**Riesgos y mitigaciones:**

| Riesgo | Mitigación |
|---|---|
| Una tarea se programa fuera de la transacción del negocio | Prueba de integración: una tarea programada dentro de una transacción revertida no existe; programada dentro de una confirmada, sí. |
| El proceso administrativo o del portal ejecuta tareas por error | Prueba de arranque por perfil: solo el contexto del trabajador inicia el ejecutor de tareas. |
| Un manejador produce el efecto dos veces | Prueba por tipo de tarea que ejecuta el manejador dos veces con los mismos datos y afirma un solo efecto. |
| Datos personales en los datos de una tarea | Prueba que serializa los datos de cada tipo de tarea y los compara contra una lista aprobada de campos permitidos. |
| Una tarea falla indefinidamente sin que nadie lo note | Máximo de intentos por tipo, estado fallido y alerta de `docs/07`. |
| El trabajador se cae a mitad de una tarea | Detección de ejecuciones abandonadas de la biblioteca y reejecución idempotente. El mecanismo concreto se valida en F0. |
| Alguien usa `@Scheduled` o `@Async` por comodidad | Regla de ArchUnit que los prohíbe en el código de producción. |

## Cumplimiento y verificación

Todo lo siguiente se ejecuta en integración continua y una falla rompe la construcción.

1. **Biblioteca única.** `maven-enforcer-plugin` prohíbe Quartz, JobRunr y cualquier otra biblioteca
   de programación.
2. **Sin programación en memoria.** Regla de ArchUnit que prohíbe `@Scheduled`, `@EnableScheduling` y
   `@Async` en el código de producción.
3. **Ejecución solo en el trabajador.** Prueba de arranque de cada perfil: el ejecutor de tareas solo
   existe en el contexto de `confia-worker`.
4. **Atomicidad.** Prueba de integración de programación dentro de transacciones confirmadas y
   revertidas.
5. **Idempotencia.** Prueba de doble ejecución por cada tipo de tarea.
6. **Sin datos personales.** Prueba de lista aprobada de campos por tipo de tarea.
7. **Contexto de seguridad.** Prueba de integración que ejecuta un manejador y verifica que solo ve
   datos de la institución indicada en la tarea.
8. **Excepción de esquema acotada.** La verificación de ADR-0009 usa una lista aprobada con exactamente
   las tres tablas técnicas; agregar otra exige un ADR.
9. **Vigilancia.** Las métricas de cola y la alerta de cola atascada existen y se prueban en
   preproducción.

## Referencias

- ADR-0003: separación entre administración y portal
- ADR-0005: autenticación y gestión de sesiones
- ADR-0007: libro mayor de doble partida
- ADR-0009: multi-institución
- ADR-0010: idempotencia y concurrencia financiera
- ADR-0013: backend en Java con Spring Boot
- ADR-0014: AWS como proveedor de nube
- ADR-0015: acceso a datos con jOOQ
- `docs/03-seguridad.md`, sección 6.2; `docs/07-observabilidad-y-operaciones.md`
- [db-scheduler](https://github.com/kagkarlsson/db-scheduler) y su [iniciador para Spring Boot 4](https://central.sonatype.com/artifact/com.github.kagkarlsson/db-scheduler-spring-boot-4-starter)
- [Trabajos programados dentro de la transacción con db-scheduler y Spring](https://blogg.bekk.no/transactionally-staged-jobs-with-db-scheduler-and-spring-7c3a609c132b)
- [JobRunr: licencia](https://github.com/jobrunr/jobrunr/blob/master/License.md) y [edición Pro](https://www.jobrunr.io/en/pricing/)
- [Spring Modulith: eventos de aplicación](https://docs.spring.io/spring-modulith/reference/events.html)
- [Quartz en Spring Boot](https://docs.spring.io/spring-boot/reference/io/quartz.html)
