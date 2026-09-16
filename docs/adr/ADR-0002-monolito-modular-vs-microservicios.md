# ADR-0002: Monolito modular con arquitectura hexagonal y organización screaming

- **Estado:** Aceptado
- **Fecha:** 2026-09-09
- **Decisores:** Propietario del producto y arquitecto
- **Contexto técnico:** `apps/api` completo (proyecto Maven de varios módulos), módulo de núcleo sin dependencias (sustituye a `packages/domain`), `packages/contracts` (generado desde el OpenAPI), pruebas de arquitectura con ArchUnit y Spring Modulith, `maven-enforcer-plugin`, pipeline de integración continua.
- **Revisión:** 2026-09-14. Alineado con ADR-0013 (backend en Java con Spring Boot). La decisión no cambia; se actualizan las herramientas y los mecanismos de verificación.

## Contexto y problema

CONFIA tiene diecisiete capacidades de negocio identificadas: identidad, organización, estudiantes,
encargados, catálogo, becas, cargos, libro mayor, pagos, caja, facturación fiscal, cobranza,
conciliación bancaria, documentos, reportes, notificaciones y portal. Varias de ellas tienen
características operativas muy distintas: la generación mensual de cargos es un proceso por lotes,
las notificaciones dependen de proveedores externos poco confiables, la generación de reportes
consume mucha memoria y el portal recibe tráfico de internet abierto.

Esa heterogeneidad genera la tentación de separar en servicios. La pregunta que hay que responder es
si esa separación debe ser **física** desde el inicio o **lógica** con opción de volverse física
después.

Las fuerzas reales:

1. **Un solo desarrollador.** Los microservicios convierten cada cambio de negocio en un problema de
   sistemas distribuidos: versionado de contratos entre servicios, despliegue coordinado,
   observabilidad correlacionada y recuperación ante fallo parcial.
2. **El dominio es transaccional y fuertemente acoplado por naturaleza.** Registrar un pago toca
   pagos, libro mayor, caja, facturación y auditoría, y todo eso debe ocurrir **en una sola
   transacción atómica**. Ese requisito no proviene de una decisión de diseño: proviene de que el
   sistema maneja dinero. Repartir esa operación entre servicios obliga a transacciones
   distribuidas, sagas y compensaciones. Una compensación mal implementada en un sistema financiero
   produce un descuadre contable, que es exactamente el fallo que el sistema existe para prevenir.
3. **El monolito degenera si no hay disciplina.** El riesgo real de un monolito no es su despliegue
   único, sino que en el mes dieciocho el módulo de reportes importe directamente entidades del
   libro mayor y nadie pueda cambiar nada sin romper todo.
4. **La separación entre administración y portal es un requisito de seguridad**, no de escala, y se
   resuelve por perfil de despliegue, no por microservicios. Ver ADR-0003.

Si no se decide, la estructura interna emerge por accidente y el sistema pierde la capacidad de
extraer un servicio cuando aparezca una razón real.

## Factores de decisión

| Factor | Peso | Justificación |
|---|---|---|
| Atomicidad transaccional del núcleo financiero | Muy alto | Un pago debe cuadrar el libro mayor, actualizar la caja y facturar, o no ocurrir. |
| Carga cognitiva y operativa para una sola persona | Muy alto | Depurar un fallo distribuido a las dos de la mañana con una sola persona no es viable. |
| Preservación de fronteras internas a tres años | Muy alto | Sin fronteras verificadas, el monolito se convierte en la bola de lodo que motiva microservicios. |
| Reversibilidad de la decisión | Alto | Monolito modular a servicios es factible. Servicios a monolito es una reescritura. |
| Costo de infraestructura | Alto | Fase uno son dos hosts. Un bus de mensajes, un registro de servicios y una malla no caben. |
| Aislamiento de fallo por capacidad | Medio | Importa para notificaciones y reportes, no para el núcleo financiero. |
| Escala independiente | Bajo hoy | Cientos de estudiantes por institución. Sin problema de escala real. |
| Despliegue independiente por capacidad | Bajo hoy | Con un desarrollador no hay equipos que se bloqueen entre sí. |

## Opciones consideradas

### Opción A: Monolito por capas técnicas

Estructura de primer nivel por capa: `controllers/`, `services/`, `repositories/`, `entities/`.

**Ventajas.** Es la estructura que casi todos los tutoriales enseñan, y no requiere pensar dónde va
un archivo nuevo.

**Desventajas.** Al abrir el repositorio no se ve qué hace el sistema, se ve qué framework usa. No
hay fronteras entre capacidades: `services/` acaba siendo un directorio con ciento veinte archivos
donde cualquiera importa a cualquiera. Extraer un servicio después exige reconstruir fronteras que
nunca existieron. Se descarta sin más análisis.

### Opción B: Microservicios desde el inicio

Un servicio por capacidad o por grupo de capacidades, comunicación por HTTP y por eventos, base de
datos por servicio.

**Ventajas.**

- Aislamiento de fallo real: una caída del proveedor de notificaciones no afecta el cobro.
- Escala y despliegue independientes por capacidad.
- Fronteras imposibles de violar por accidente, porque cruzarlas exige una llamada de red.

**Desventajas.**

- **Destruye la atomicidad del núcleo financiero.** Registrar un pago tendría que coordinar pagos,
  libro mayor, caja y facturación mediante saga. Un fallo intermedio deja el sistema en un estado
  que hay que compensar. En un sistema que factura con correlativo fiscal irrepetible, una
  compensación fallida produce un documento fiscal emitido contra un asiento que no existe.
- Multiplica la superficie operativa: registro de servicios, contratos versionados entre servicios,
  trazas correlacionadas, reintentos idempotentes en cada frontera, despliegue coordinado.
- La infraestructura de fase uno (dos hosts con Docker Compose) no sostiene esa topología.
- Con un solo desarrollador, todo el beneficio es organizacional y no hay organización que
  beneficiar. El costo, en cambio, se paga desde el primer día.

### Opción C: Monolito modular con arquitectura hexagonal y organización screaming

Un solo artefacto desplegable. Los directorios de primer nivel nombran capacidades de negocio, no
capas técnicas. Cada módulo se organiza internamente en `domain`, `application`, `infrastructure` e
`interface`; en Java, la capa `interface` vive en el paquete `web`, porque `interface` es palabra
reservada del lenguaje. Las reglas de dependencia se verifican con herramienta en cada construcción.

**Ventajas.**

- Transacciones ACID reales en el núcleo financiero, sin sagas ni compensaciones.
- Una sola construcción, un solo despliegue, una sola traza, un solo lugar donde depurar.
- Las fronteras existen y se verifican, de modo que extraer un servicio más adelante es un trabajo
  acotado y no una arqueología.
- La estructura comunica el dominio: quien abre el repositorio lee `ledger`, `invoicing`, `cashbox`,
  no `services` y `controllers`.
- El módulo de núcleo sin framework y la capa `domain` de cada módulo permiten pruebas unitarias
  rapidísimas y pruebas de mutación con PIT sobre la lógica que realmente importa.

**Desventajas.**

- Sin verificación automatizada, las fronteras son una convención y las convenciones se erosionan.
  Toda la validez de esta opción depende de que la verificación exista y bloquee la construcción.
- Un fallo grave (fuga de memoria, bucle infinito en un reporte) puede afectar a todo el proceso.
- Toda la aplicación se despliega junta: un cambio en notificaciones exige desplegar el núcleo
  financiero.
- Más ceremonia por caso de uso que un CRUD directo: puertos, adaptadores y mapeadores. En módulos
  puramente administrativos esa ceremonia no se paga sola.

## Decisión

**Se adopta la opción C: monolito modular con arquitectura hexagonal por módulo y organización
screaming en el primer nivel.**

El factor determinante es la **atomicidad transaccional del núcleo financiero**. Un pago que debe
asentar en el libro mayor, afectar la sesión de caja, emitir un documento fiscal con correlativo
irrepetible y escribir en la bitácora de auditoría es, por definición, una unidad atómica. Una base
de datos relacional resuelve eso con una transacción. Un sistema distribuido lo resuelve con una
saga que hay que diseñar, probar y operar. Con un desarrollador solo y dinero real de por medio, la
transacción gana sin discusión.

El segundo factor es la **reversibilidad**. El monolito modular con fronteras verificadas conserva
la opción de extraer un servicio. Los microservicios no conservan la opción de volver.

La organización screaming se elige porque el repositorio es documentación. Los nombres de primer
nivel del sistema deben ser `ledger`, `invoicing`, `cashbox` y `collections`, no `controllers` y
`services`. Esto tiene valor operativo directo con un solo desarrollador: reduce el tiempo de
reorientación al volver a un módulo tras semanas sin tocarlo.

La arquitectura hexagonal se elige porque mantiene el módulo de núcleo y la capa `domain` de cada
módulo libres de framework y de entrada y salida. Eso permite probar las reglas financieras en
milisegundos, aplicarles pruebas de mutación, y sobrevivir a un cambio de framework o de ORM sin
tocar la lógica de dinero.

### Cuándo SÍ se justificaría extraer un servicio

La extracción no es un objetivo. Es una respuesta a un problema medido. Se justifica cuando se
cumple **al menos una** de estas condiciones, con evidencia:

1. **Perfil de recursos incompatible.** Una capacidad consume memoria o CPU de forma que degrada la
   latencia del núcleo financiero, medido en métricas de producción, no supuesto.
2. **Ciclo de despliegue incompatible.** Una capacidad necesita desplegarse varias veces al día
   mientras el núcleo financiero exige ventanas controladas.
3. **Aislamiento de fallo obligatorio.** Una dependencia externa inestable amenaza la disponibilidad
   del proceso completo y el aislamiento por cola no basta.
4. **Escala independiente real.** Una capacidad requiere replicación horizontal que al resto del
   sistema le sobra y le cuesta.
5. **Un segundo equipo.** Existe una persona o equipo con propiedad exclusiva de esa capacidad.

No son razones válidas para extraer: la estética arquitectónica, el tamaño del repositorio, una
tendencia del sector, ni la posibilidad hipotética de crecimiento.

### Primeros candidatos a extracción, en orden

| Orden | Candidato | Por qué es el primero | Precondición ya satisfecha |
|---|---|---|---|
| 1 | `notifications` | Depende de proveedores externos inestables (correo, mensajería, SMS), no participa en transacciones financieras, y su unidad de trabajo ya es un mensaje asíncrono. | Ya se comunica por eventos de dominio y por el sistema de trabajos en segundo plano (pendiente de ADR específico, ver ADR-0013). Extraerlo es cambiar de proceso el consumidor de sus trabajos. |
| 2 | `reporting` en su parte pesada | La generación de estados de cuenta masivos, libros de ventas y exportaciones PDF o Excel es intensiva en memoria y CPU, y puede degradar la latencia de cobro en ventanilla. Es de solo lectura. | Ya lee por SQL crudo y puede apuntar a una réplica de lectura sin tocar el nodo de escritura. |
| 3 | `documents` | Generación de constancias y certificaciones con PDF. Mismo perfil que reportes, sin participación transaccional. | Ya trabaja contra almacenamiento compatible con S3, no contra disco local. |

**No son candidatos, en ningún horizonte previsible:** `ledger`, `payments`, `charges`, `cashbox`,
`invoicing`. Estos cinco forman una única unidad transaccional. Separarlos exige transacciones
distribuidas sobre dinero, y ese es precisamente el fallo que la arquitectura evita.

El módulo `portal` no se extrae: ya se aísla por perfil de despliegue, rol de base de datos,
dominio de identidad y origen web, según ADR-0003. Eso resuelve el riesgo de exposición sin costo
distribuido.

## Consecuencias

**Positivas:**

- El núcleo financiero opera en transacciones ACID reales, con bloqueo explícito y aislamiento
  seleccionable, sin sagas ni compensaciones.
- Un solo artefacto de construcción, un solo pipeline, una sola traza distribuida, un solo lugar de
  depuración.
- Refactorización entre módulos con verificación de tipos completa del compilador dentro del
  backend, imposible de lograr entre servicios independientes.
- El módulo de núcleo sin framework permite pruebas unitarias en milisegundos y pruebas de mutación
  significativas.
- La estructura del repositorio funciona como documentación viva del dominio.
- La extracción futura de `notifications` o `reporting` es un trabajo acotado porque las fronteras
  ya existen y ya están verificadas.

**Negativas y costos aceptados:**

- Toda la aplicación se despliega junta. Un cambio en plantillas de notificación exige desplegar el
  proceso que registra pagos.
- Un fallo de recurso en un módulo puede degradar todo el proceso. Se mitiga con límites de memoria
  por contenedor, tiempos de espera por operación y ejecución de trabajos pesados en un proceso de
  trabajador separado.
- Ceremonia hexagonal en módulos que no la necesitan. En `catalog` u `organization`, escribir puerto,
  adaptador y mapeador para un CRUD es sobrecosto real. Se acepta a cambio de uniformidad: una sola
  forma de hacer las cosas vale más, con un desarrollador solo, que la optimización local.
- Las fronteras dependen por completo de la verificación automatizada. Si las pruebas de
  arquitectura de ArchUnit y Spring Modulith se desactivan o sus reglas se relajan sin ADR, la
  decisión queda anulada en la práctica.
- El repositorio crece hasta un tamaño donde la construcción completa se vuelve lenta. Se mitiga con
  el caché de Turborepo y ejecución por proyecto afectado.

**Riesgos y mitigaciones:**

| Riesgo | Mitigación |
|---|---|
| Erosión de fronteras por conveniencia bajo presión de entrega | Pruebas de ArchUnit y verificación de módulos de Spring Modulith en cada construcción de integración continua. Una violación rompe la construcción. Ninguna excepción se agrega sin un ADR que la justifique. |
| Acoplamiento oculto por la base de datos compartida | Cada módulo posee sus tablas. Ningún repositorio de un módulo consulta tablas de otro módulo; la lectura cruzada ocurre por caso de uso público. Verificado por convención de prefijo de tabla y revisión de las migraciones de Flyway. |
| Explosión de eventos de dominio hasta volver el flujo imposible de seguir | Los eventos se usan para efectos secundarios (notificar, indexar, auditar), nunca para completar una transacción financiera. Un pago no depende de que un consumidor de eventos tenga éxito. |
| Módulo `shared` convertido en vertedero | `shared` solo contiene infraestructura transversal genuina: auditoría, seguridad, observabilidad y kernel. Ninguna regla de negocio vive ahí. Verificado por regla de dependencia y revisión. |
| Trabajos pesados degradando la latencia de ventanilla | Los trabajos en segundo plano corren en un proceso trabajador separado del proceso que atiende HTTP, con su propio límite de recursos. El mecanismo de trabajos está pendiente de ADR específico (ver ADR-0013). |

## Cumplimiento y verificación

### 1. Reglas de capa y de módulo con ArchUnit y Spring Modulith

Las reglas se escriben como pruebas de arquitectura en el módulo de aplicación Spring Boot y se
ejecutan en cada construcción dentro de la fase de pruebas de Maven. Un fallo rompe la
construcción. Las reglas se expresan por capa y por módulo de negocio, no por ruta de archivo.

Cada módulo de negocio se organiza en los paquetes `<module>.domain`, `<module>.application`,
`<module>.infrastructure` y `<module>.web`. La capa conceptual `interface` vive en el paquete `web`,
porque `interface` es palabra reservada de Java y no puede usarse como nombre de paquete. El paquete
`web` contiene los controladores HTTP, los DTO, los mapeadores y los permisos declarados. En las
reglas siguientes, "capa `interface`" designa siempre el paquete `<module>.web`.

Reglas obligatorias:

| Regla | Mecanismo | Desde | Hacia (prohibido) |
|---|---|---|---|
| `domain-is-pure` | ArchUnit | capa `domain` de cualquier módulo | capas `application`, `infrastructure` e `interface` (paquete `web`); `org.springframework..`, `jakarta..`; controladores de base de datos, clientes HTTP y clientes de Redis; paquetes de entrada y salida del JDK (`java.sql`, `java.net`, `java.nio.file`) |
| `no-cross-module-domain` | ArchUnit | cualquier clase del módulo `a` | capa `domain` del módulo `b`, donde `a != b` |
| `application-no-infrastructure` | ArchUnit | capa `application` | capa `infrastructure` |
| `interface-only-application` | ArchUnit | capa `interface` (paquete `<module>.web`) | capas `domain` e `infrastructure` |
| `kernel-is-pure` | Compilación de Maven y `bannedDependencies` de `maven-enforcer-plugin` | módulo de núcleo | cualquier dependencia fuera del JDK |
| `module-internals-private` | Spring Modulith | cualquier módulo | tipos de otro módulo que no pertenezcan a su API pública declarada |
| `no-cycles` | ArchUnit y Spring Modulith | cualquier módulo o capa | dependencias circulares entre módulos o entre capas |
| `portal-module-boundary` | ArchUnit | módulo `portal` | módulos `invoicing` y `cashbox`, y capa `domain` de `identity` |

La regla de archivos huérfanos de la configuración anterior no tiene equivalente directo en estas
herramientas y no se traslada. El código inalcanzable queda a cargo de la revisión.

Además se genera la documentación de módulos de Spring Modulith, con el diagrama de dependencias
entre módulos, como artefacto de la construcción, de modo que la deriva estructural sea visible en
la revisión y no solo en el resultado binario de la verificación.

### 2. Frontera de compilación y retroalimentación durante la escritura

Las reglas de frontera de ESLint no se aplican al backend. Siguen vigentes únicamente para el código
del frontend.

El valor que aportaban en el backend, que el desarrollador vea el error mientras escribe y no veinte
minutos después en la construcción, se obtiene así:

- **Módulo de núcleo como frontera de compilación.** Al ser un módulo Maven sin dependencias fuera
  del JDK, importar Spring, Jakarta o cualquier biblioteca de entrada y salida desde él es un error
  de compilación que el entorno de desarrollo muestra de inmediato.
- **API pública de cada módulo.** Cada módulo declara de forma explícita en Spring Modulith qué tipos
  forman su API pública: los casos de uso públicos y los eventos que publica. Todo lo demás es
  interno, y cualquier referencia desde otro módulo a un tipo interno falla la verificación. Esto
  sustituye al punto de entrada público y a la prohibición de importar archivos internos.
- **Pruebas de arquitectura rápidas.** Las pruebas de ArchUnit y la verificación de Spring Modulith
  analizan las clases compiladas sin arrancar el contexto de Spring, de modo que se ejecutan desde el
  entorno de desarrollo como cualquier prueba unitaria.
- La prohibición de importar framework y entrada y salida dentro de cualquier capa `domain` queda
  cubierta por la regla `domain-is-pure`.

### 3. Verificaciones adicionales en la construcción

- **Estructura obligatoria.** Verificación que falla si el paquete de un módulo de negocio no
  contiene los cuatro paquetes canónicos (`domain`, `application`, `infrastructure` y `web`), si
  aparece un paquete llamado `interface` o `interfaces`, o si aparece un paquete de primer nivel con nombre de capa
  técnica (`controllers`, `services`, `repositories`, `entities`, `utils`, `helpers`, `common`).
- **Propiedad de tablas.** Cada módulo es dueño de las migraciones de Flyway que crean sus tablas, y
  cada tabla lleva el prefijo de su módulo propietario. Una verificación falla si el código de acceso
  a datos de un módulo referencia una tabla cuyo prefijo pertenezca a otro módulo, tomando como
  fuente el catálogo de PostgreSQL resultante de aplicar las migraciones. El mecanismo concreto de
  detección depende de la estrategia de acceso a datos, pendiente de ADR específico (ver ADR-0013).
- **Arranque de perfiles.** Prueba que arranca el contexto del punto de entrada del portal y afirma
  que los controladores de `invoicing`, `cashbox` e `identity` administrativo **no** están
  registrados en el mapa de rutas. Ver ADR-0003.
- **Cobertura del dominio.** El módulo de núcleo y el paquete `domain` de cada módulo de negocio
  exigen noventa y cinco por ciento de cobertura, medida con JaCoCo, y umbral de mutación de ochenta
  con PIT. Ahí viven el dinero, la mora, los impuestos y la imputación de pagos. Ver ADR-0008.
- **Revisión de excepciones.** Cualquier excepción a una regla de ArchUnit o de Spring Modulith
  (clases ignoradas o dependencias permitidas adicionales) requiere un comentario con el número de
  ADR que la autoriza. Un script falla si encuentra una excepción sin referencia a un ADR.

## Referencias

- `docs/01-arquitectura.md`, secciones 2 y 4
- `CLAUDE.md`, sección Estructura y reglas de dependencia
- ADR-0001: stack tecnológico (reemplazado por ADR-0013)
- ADR-0003: separación entre administración y portal
- ADR-0007: libro mayor de doble partida
- ADR-0008: estrategia de pruebas
- ADR-0013: backend en Java con Spring Boot
