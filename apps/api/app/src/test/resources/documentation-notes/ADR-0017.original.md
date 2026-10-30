# ADR-0017: Tablas técnicas de bibliotecas y tabla raíz de institución

- **Estado:** Aceptado
- **Fecha:** 2026-09-15
- **Decisores:** Propietario del producto y arquitecto
- **Contexto técnico:** Esquema de PostgreSQL, roles de base de datos de `docs/03-seguridad.md` sección 6.1, migraciones de Flyway, configuración de db-scheduler y de Spring Modulith, generación de código de jOOQ, verificaciones de esquema y de permisos en integración continua. Complementa ADR-0003, ADR-0009, ADR-0015 y ADR-0016.

## Contexto y problema

Tres decisiones aceptadas fijan reglas que valen para "cada tabla":

- **ADR-0009:** cada tabla lleva `institution_id NOT NULL` y política de seguridad a nivel de fila.
- **ADR-0015, regla 3:** cada tabla lleva el prefijo de su módulo, y un módulo solo usa las tablas
  que le pertenecen.
- **ADR-0003 y `docs/03-seguridad.md` sección 6.1:** el rol de la aplicación no tiene `DELETE` en
  ninguna tabla, y el rol del portal solo inserta en una lista cerrada de tablas.

Hay tablas que no pueden cumplir esas reglas:

1. **Tres tablas técnicas cuyo esquema define una biblioteca:** la de tareas de db-scheduler
   (ADR-0016), la de publicación de eventos de Spring Modulith (ADR-0015, regla 10) y el historial de
   migraciones de Flyway. No llevan `institution_id` ni prefijo de módulo, y db-scheduler necesita
   modificar y borrar filas de su tabla para funcionar.
2. **La tabla raíz de instituciones.** Su clave primaria *es* el identificador de institución; no
   tiene sentido que lleve una columna `institution_id` que se apunte a sí misma.

ADR-0016 exceptuó a las tres tablas técnicas solo de `institution_id`. Las demás excepciones (prefijo,
propiedad de tablas, permisos) y la tabla raíz quedaron sin respaldo en ningún ADR, y la
actualización de documentos posterior las escribió en `docs/03` sin una decisión aceptada detrás.
Como los ADR aceptados no se editan, esas excepciones necesitan un ADR propio.

Si no se decide, cada documento resuelve la excepción a su manera y las verificaciones automáticas
fallan o, peor, se relajan sin que nadie lo registre.

## Factores de decisión

| Factor | Peso | Justificación |
|---|---|---|
| Excepciones mínimas y en una lista cerrada | Muy alto | Una excepción abierta es una puerta para relajar las reglas sin decisión. |
| Ningún dato personal en tablas sin seguridad a nivel de fila | Muy alto | Datos de menores (`CLAUDE.md`, regla 15). |
| Mínimo privilegio para el proceso del portal | Muy alto | Es el proceso expuesto a internet (ADR-0003). |
| Esquema creado solo por migraciones | Alto | Un esquema creado en ejecución por una biblioteca escapa a la revisión (`CLAUDE.md`, migraciones). |
| Verificable en integración continua | Alto | Con un solo desarrollador, lo que no se verifica se erosiona. |
| No modificar bibliotecas de terceros | Alto | Una bifurcación es mantenimiento permanente. |

## Opciones consideradas

### Opción A: Catálogo cerrado de excepciones en un solo ADR

Se enumeran las tablas exceptuadas, de qué regla se exceptúa cada una, qué permisos exactos tiene
cada rol sobre ellas y qué contenido pueden guardar. Todo lo demás sigue las reglas generales.

**Ventajas.** Una sola fuente de verdad, verificable con una lista aprobada. Las reglas generales no
se debilitan.

**Desventajas.** Las tablas técnicas quedan sin seguridad a nivel de fila; hay que compensarlo
restringiendo su contenido.

### Opción B: Esquema de PostgreSQL separado para las tablas técnicas

Las tres tablas técnicas vivirían en un esquema propio, con permisos concedidos por esquema.

**Ventajas.** Separación visual y de permisos más limpia.

**Desventajas.** Exige verificar que cada biblioteca, en la versión fijada, soporta un esquema no
predeterminado sin configuración frágil, y complica la ruta de búsqueda de esquemas en cada proceso.
El beneficio no compensa el riesgo en la fase uno. **No se adopta.**

### Opción C: Adaptar las bibliotecas para que cumplan las reglas generales

**Desventajas.** Agregar `institution_id` o renombrar tablas dentro de una biblioteca es mantener una
bifurcación. **Se descarta.**

### Opción D: Dejar las excepciones descritas solo en los documentos

**Desventajas.** Es la situación actual: excepciones sin decisión aceptada que las respalde. **Se
descarta.**

## Decisión

**Se adopta la opción A: un catálogo cerrado de cuatro tablas exceptuadas, con permisos exactos por
rol y restricciones de contenido, verificado en integración continua.**

### Catálogo cerrado

| Tabla | Tipo | Exceptuada de | Propietario |
|---|---|---|---|
| `scheduled_tasks` | Técnica, db-scheduler | `institution_id`, seguridad a nivel de fila, prefijo de módulo, propiedad de tablas | Infraestructura compartida |
| `event_publication` | Técnica, Spring Modulith | `institution_id`, seguridad a nivel de fila, prefijo de módulo, propiedad de tablas | Infraestructura compartida |
| `flyway_schema_history` | Técnica, Flyway | `institution_id`, seguridad a nivel de fila, prefijo de módulo, propiedad de tablas | Infraestructura compartida |
| Tabla raíz de instituciones | De negocio | Solo la columna `institution_id`: su clave primaria es el identificador de institución | Módulo `organization` |

Los nombres de las tres tablas técnicas son los predeterminados de cada biblioteca y se confirman en
F0. El nombre de la tabla raíz sigue la regla de prefijo de ADR-0015 como cualquier tabla de negocio.
**Ninguna otra tabla puede exceptuarse sin un ADR nuevo.**

### Reglas

1. **La tabla raíz tiene seguridad a nivel de fila.** Su política filtra por
   `id = current_setting('app.institution_id', true)::uuid`. Solo está exceptuada de llevar la
   columna, no del aislamiento.
2. **Las tablas técnicas no guardan datos personales.** Los datos de tarea llevan solo
   identificadores (ADR-0016, regla 3). Los eventos de dominio que se persisten en
   `event_publication` llevan identificadores y hechos sin datos personales: nunca nombres, correos,
   teléfonos ni documentos de identidad. Es la compensación por no tener seguridad a nivel de fila.
3. **Esquema creado solo por migraciones.** Las tres tablas técnicas se crean con migraciones de
   Flyway, ejecutadas con `confia_owner`, a partir del esquema publicado por cada biblioteca en la
   versión fijada. La creación automática de esquema en ejecución queda desactivada. Una
   actualización de biblioteca que cambie su esquema se acompaña de una migración nueva.
4. **Spring Modulith borra las publicaciones completadas.** Se usa el modo de finalización `DELETE`:
   las publicaciones completadas se eliminan y no hace falta una tarea de purga. El modo de archivo
   queda prohibido, porque crea una cuarta tabla técnica. La historia de lo que ocurrió vive en la
   bitácora de auditoría, no en el registro de eventos.
5. **El proceso del portal no usa el registro de eventos.** Las reacciones que un caso de uso del
   portal necesite de forma durable se programan como tareas de db-scheduler. Así el portal no
   necesita ningún permiso sobre `event_publication` y nunca tiene `UPDATE` ni `DELETE` en ninguna
   tabla.
6. **Fuera de la generación de código de jOOQ.** Las tablas técnicas no se generan como clases. El
   único código de la aplicación que las lee directamente es el componente de observabilidad de
   `shared` (métricas de cola y comprobación de salud), con consultas de solo lectura incluidas en la
   lista aprobada de SQL plano de ADR-0015, regla 9.
7. **Los manejadores revalidan todo.** Como las tablas técnicas no tienen aislamiento por fila, un
   manejador de tarea o de evento nunca confía en los datos que recibe: revalida contra la base de
   datos cada entidad referenciada y recalcula cada importe (ADR-0016, `docs/03` sección 2.4).

### Permisos exactos sobre las tablas exceptuadas

| Rol | `scheduled_tasks` | `event_publication` | `flyway_schema_history` |
|---|---|---|---|
| `confia_owner` | Propietario | Propietario | Propietario |
| `confia_admin_app` (procesos administrativo y trabajador) | `SELECT`, `INSERT`, `UPDATE`, `DELETE` | `SELECT`, `INSERT`, `UPDATE`, `DELETE` | `SELECT`, para la comprobación de salud de migraciones aplicadas |
| `confia_portal_app` | `INSERT`, más `SELECT` solo si el cliente de programación lo exige (validado en F0). Nunca `UPDATE` ni `DELETE` | Ninguno | `SELECT`, para la comprobación de salud |
| `confia_readonly` | Ninguno | Ninguno | Ninguno |
| `confia_backup` | Lectura, como todo el esquema | Lectura, como todo el esquema | Lectura, como todo el esquema |

Estos son los únicos `UPDATE` y `DELETE` de `confia_admin_app` fuera de las tablas no financieras que
ya permitía `docs/03` sección 6.1. La tabla raíz sigue los permisos de cualquier tabla de negocio.

## Consecuencias

**Positivas:**

- Todas las excepciones a las reglas generales viven en un solo lugar y en una lista cerrada.
- El proceso del portal conserva el mínimo privilegio: ningún `UPDATE` ni `DELETE` en ninguna tabla.
- Ningún dato personal queda en tablas sin seguridad a nivel de fila.
- El esquema completo, incluidas las tablas de bibliotecas, pasa por migraciones revisables.

**Negativas y costos aceptados:**

- **Las tablas técnicas no tienen aislamiento por fila.** Se compensa con la restricción de contenido
  y la revalidación en los manejadores.
- **El diseño de eventos de dominio queda restringido**: sin datos personales en los eventos que se
  persisten.
- **El portal no puede usar eventos persistidos**; sus reacciones durables pasan por tareas.
- **Actualizar db-scheduler o Spring Modulith puede exigir una migración** si cambia su esquema.
- `docs/03` secciones 6.1 y 6.4, `docs/07` (comprobación de salud) y el agente `confia-database`
  deben alinearse con este ADR una vez aceptado.

**Riesgos y mitigaciones:**

| Riesgo | Mitigación |
|---|---|
| Alguien agrega una tabla a la lista de excepciones sin decisión | La lista aprobada de la verificación de esquema contiene exactamente las cuatro tablas; ampliarla exige un ADR. |
| Un evento persistido incluye datos personales | Prueba de lista aprobada de campos por tipo de evento persistido, igual que para las tareas. |
| Un portal comprometido inserta tareas falsas | Los manejadores revalidan contra la base y recalculan importes; los datos de tarea no contienen importes ni datos personales. |
| Una biblioteca crea o altera su tabla en ejecución | Creación automática desactivada y verificada por prueba de configuración; el rol de la aplicación no es propietario del esquema. |
| Se activa el modo de archivo de Spring Modulith | Prueba de configuración que exige el modo `DELETE`. |

## Cumplimiento y verificación

Todo lo siguiente se ejecuta en integración continua y una falla rompe la construcción.

1. **Esquema multi-institución.** La verificación de ADR-0009 exceptúa exactamente las cuatro tablas
   del catálogo, y además comprueba que la tabla raíz tiene seguridad a nivel de fila activa y forzada
   y una política sobre su clave primaria.
2. **Matriz de permisos.** La prueba de permisos de ADR-0003 incluye las tres tablas técnicas con los
   privilegios exactos de la tabla de este ADR: el portal sin ningún privilegio sobre
   `event_publication` y sin `UPDATE` ni `DELETE` sobre `scheduled_tasks`.
3. **Casos de uso del portal con su rol real.** Las pruebas de integración de los casos de uso del
   portal se ejecutan con `confia_portal_app`; cualquier intento de escribir en `event_publication`
   falla la prueba.
4. **Configuración de bibliotecas.** Prueba que verifica que la creación automática de esquema de
   Spring Modulith está desactivada, que su modo de finalización es `DELETE` y que el modo de archivo
   no está activo.
5. **Contenido de eventos persistidos.** Prueba de lista aprobada de campos por tipo de evento que se
   persiste en el registro.
6. **Fuera de jOOQ.** La configuración de generación de código excluye las tres tablas técnicas, y la
   lista aprobada de SQL plano de ADR-0015 contiene solo las consultas de solo lectura del componente
   de observabilidad sobre ellas.

## Referencias

- ADR-0003: separación entre administración y portal
- ADR-0009: multi-institución
- ADR-0015: acceso a datos con jOOQ
- ADR-0016: trabajos en segundo plano
- `docs/03-seguridad.md`, secciones 2.4, 6.1 y 6.4
- `CLAUDE.md`, reglas 11 y 15
- [Spring Modulith: modos de finalización del registro de eventos](https://docs.spring.io/spring-modulith/reference/events.html)
- [db-scheduler](https://github.com/kagkarlsson/db-scheduler)
