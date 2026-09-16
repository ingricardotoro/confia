# ADR-0013: Backend en Java con Spring Boot

- **Estado:** Aceptado
- **Fecha:** 2026-09-14
- **Decisores:** Propietario del producto y arquitecto
- **Contexto técnico:** `apps/api`, dominio financiero (objeto de valor `Money`, libro mayor), `packages/contracts`, `packages/domain`, integración continua, imágenes de contenedor. Reemplaza a ADR-0001.

## Contexto y problema

ADR-0001 eligió TypeScript de extremo a extremo con NestJS en el backend. Esa decisión quedó en
estado Propuesto y no llegó a aceptarse. Antes de su aprobación aparecieron dos hechos que
cambian el resultado del análisis:

1. **La experiencia del desarrollador único es mayor en Java y Spring Boot que en NestJS.**
   ADR-0001 no ponderó este factor, a pesar de que su propio factor de mayor peso es la carga
   cognitiva de mantenimiento a tres años. El mismo ADR-0001 reconoce, al evaluar .NET, que con
   experiencia previa en la plataforma la ventaja del decimal nativo "justificaría con holgura el
   costo del segundo lenguaje". Ese razonamiento aplica igual a Java.
2. **El argumento principal contra un segundo lenguaje es más débil de lo que ADR-0001 plantea.**
   ADR-0001 descartó las alternativas porque "el objeto `Money`, las reglas de mora y el cálculo de
   impuestos tendrían que existir dos veces". Sin embargo, `CLAUDE.md` establece que toda la
   aritmética monetaria vive en el dominio y nunca en componentes de React (regla 3), y que la
   autorización y la autoridad de cálculo pertenecen siempre al servidor (regla 9). Si el cliente
   no calcula dinero, no existe una segunda implementación que pueda divergir. El cliente muestra
   importes que calcula el servidor, y las vistas previas (por ejemplo, la mora estimada) se
   resuelven con un endpoint.

Las fuerzas que siguen vigentes desde ADR-0001:

- **Presupuesto de atención de una sola persona.** Cada cadena de herramientas adicional tiene un
  costo recurrente real.
- **El contrato de API es un producto.** El panel, el portal y la app móvil futura consumen el
  mismo contrato, y un cambio incompatible debe romper la construcción, no producción.
- **Dominio financiero.** Decimales exactos, transacciones explícitas, aislamiento seleccionable y
  bibliotecas maduras de PDF, criptografía y trabajos en segundo plano.
- **Alojamiento modesto.** Dos hosts pequeños con Docker Compose que ejecutan tres procesos de la
  misma imagen (administrativo, portal y trabajador), además de PostgreSQL, Redis y almacenamiento
  de objetos.

Si no se decide ahora, el proyecto arranca con un framework que el desarrollador domina menos que
la alternativa disponible, y paga esa diferencia en cada módulo del núcleo financiero.

## Factores de decisión

| Factor | Peso | Por qué pesa así con un solo desarrollador |
|---|---|---|
| Dominio real del stack por parte del desarrollador | Muy alto | La carga cognitiva a tres años depende más de la fluidez que de la cantidad de lenguajes. |
| Corrección monetaria garantizada por el lenguaje | Muy alto | Una garantía del lenguaje vale más que una disciplina impuesta por lint. |
| Contrato tipado verificable entre backend y clientes | Muy alto | Un cambio incompatible debe romper la construcción del monorepo, no producción. |
| Modularidad y fronteras impuestas por herramienta | Alto | El monolito modular de ADR-0002 depende de que las fronteras se verifiquen automáticamente. |
| Madurez transaccional y de concurrencia | Alto | Bloqueo pesimista, `SERIALIZABLE` y reintento son requisitos de ADR-0010. |
| Costo de alojamiento y memoria | Alto | Tres procesos de la misma imagen en hosts modestos. |
| Cantidad de cadenas de herramientas | Alto | Cada una suma dependencias, avisos de vulnerabilidad y actualizaciones. |
| Facilidad para contratar un relevo o transferir al departamento de sistemas | Medio | Mitigación directa del riesgo R01. |
| Rendimiento bruto por núcleo | Bajo | La base de datos es el cuello de botella, no el runtime. |

## Opciones consideradas

### Opción A: Java 25 LTS con Spring Boot 4

Backend en Java 25 LTS con Spring Boot 4.1, construido con Maven. Frontend sin cambios: React 19
con Vite y la suite TanStack de ADR-0006, dentro del monorepo `pnpm` con Turborepo. El contrato de
API se genera desde el backend como OpenAPI 3.1 y los tipos TypeScript y los esquemas Zod de los
clientes se generan a partir de él.

**Ventajas.**

- **El desarrollador ya domina la plataforma.** Las dos semanas de curva inicial que ADR-0001
  aceptaba para NestJS desaparecen, y los errores sutiles del framework se reconocen antes.
- **`BigDecimal` nativo.** ADR-0001 declaró la falta de decimal nativo en Node como "el costo más
  caro de esta decisión". Con Java ese costo desaparece: `Money` se construye sobre un decimal
  exacto del lenguaje y no sobre un `bigint` con escala implícita.
- **Gestión transaccional declarativa madura.** `@Transactional` con nivel de aislamiento
  explícito, bloqueo pesimista y reintento ante error de serialización son patrones de primera
  clase en el ecosistema, lo cual simplifica ADR-0007 y ADR-0010.
- **Monolito modular verificado por herramienta.** Spring Modulith verifica las fronteras entre
  módulos de negocio y ofrece un registro persistente de publicación de eventos, que da el patrón
  de bandeja de salida transaccional sin construirlo a mano. ArchUnit verifica las reglas de capa
  hexagonal. Juntos cubren lo que ADR-0002 esperaba de `dependency-cruiser`.
- **Compilación como frontera.** El núcleo sin framework puede vivir en un módulo Maven sin
  dependencias, de modo que importar Spring desde el dominio es un error de compilación y no solo
  una regla de lint.
- **Ecosistema financiero maduro.** Spring Security, Flyway, Testcontainers, pruebas de mutación
  con PIT, bibliotecas de PDF y criptografía probadas durante años en producción.
- **Relevo más probable.** En la región es más común encontrar profesionales con Java que con
  NestJS, lo que favorece la mitigación de R01. Se confirmará con el departamento de sistemas de la
  institución.

**Desventajas.**

- **Dos cadenas de herramientas.** Maven para el backend y `pnpm` para el frontend, con dos
  pipelines de dependencias y dos fuentes de avisos de vulnerabilidad.
- **El contrato deja de compartirse de forma nativa.** Hace falta generar código a partir del
  OpenAPI. Es un paso de construcción adicional que hay que mantener, y la ruptura de contrato se
  detecta por regeneración y verificación de tipos, no por importación directa.
- **Mayor consumo de memoria en reposo.** Una JVM con Spring Boot consume del orden de cientos de
  megabytes por proceso, y la topología de fase uno ejecuta tres procesos.
- **Riesgo de inmutabilidad con JPA.** El seguimiento automático de cambios de Hibernate puede
  emitir actualizaciones implícitas sobre entidades gestionadas, lo cual contradice la regla de que
  nada financiero se edita. Obliga a elegir con cuidado el acceso a datos del núcleo financiero.
- **Trampas propias de `BigDecimal`.** `equals` distingue escala (`1.0` no es igual a `1.00`) y
  `new BigDecimal(double)` hereda el error de la coma flotante. Ambas se previenen con reglas
  automatizadas, no con buena voluntad.

### Opción B: NestJS sobre Node.js con TypeScript (decisión de ADR-0001)

Descrita en detalle en ADR-0001.

**Por qué pierde ahora.** Su ventaja principal, un lenguaje único con reglas financieras
compartidas por el cliente, se apoya en que el cliente ejecute reglas de dinero, lo cual las reglas
3 y 9 de `CLAUDE.md` prohíben. Lo que queda de esa ventaja es el contrato compartido sin
generación de código, que es real pero se obtiene también con OpenAPI y generación automatizada.
A cambio, obliga a un desarrollador con más experiencia en Java a aprender un framework nuevo y a
compensar la falta de decimal nativo con disciplina impuesta por lint.

**NestJS sigue siendo una opción legítima.** Para un desarrollador con más fluidez en TypeScript
que en Java, la decisión de ADR-0001 sería la correcta.

### Otras opciones

Laravel, .NET, Django y Fastify con tRPC se evaluaron en ADR-0001 y no se reevalúan aquí. .NET
empataría con Java en soporte de lenguaje para dinero, pero no en la experiencia del desarrollador,
que es el factor que decide este ADR.

## Decisión

**Se adopta Java 25 LTS con Spring Boot 4.1 en el backend, construido con Maven. La documentación
interactiva de la API se sirve con Swagger UI a través de springdoc-openapi, a partir del OpenAPI
3.1 generado desde el código. El frontend se mantiene en TypeScript con React y TanStack, y
consume tipos y esquemas Zod generados desde ese OpenAPI.**

El factor determinante es la combinación de los dos factores de mayor peso: **el dominio real del
stack por parte del desarrollador y la corrección monetaria garantizada por el lenguaje.** Ambos
favorecen a Java. El costo aceptado es una segunda cadena de herramientas y un paso de generación
de contrato, que se automatiza desde la primera semana para que la garantía de ruptura en
construcción se conserve.

### Especificación del stack del backend

| Elemento | Elección | Nota |
|---|---|---|
| Lenguaje y runtime | Java 25 LTS | Distribución OpenJDK sin costo de licencia, como Eclipse Temurin. |
| Framework | Spring Boot 4.1 | Línea con soporte de código abierto vigente. Las actualizaciones menores se planifican cada seis meses. |
| Construcción | Maven con Maven Wrapper | El wrapper se compromete al repositorio para fijar la versión de Maven. |
| Modularidad | Spring Modulith 2 y ArchUnit | Fronteras entre módulos y reglas de capa hexagonal verificadas en pruebas. |
| Migraciones | Flyway | Reemplaza a las migraciones de Prisma. Scripts SQL versionados y revisables. |
| Validación en el borde | Jakarta Bean Validation | Reemplaza a Zod en el servidor. Zod permanece en los clientes, generado desde el OpenAPI. |
| Seguridad | Spring Security | Base para ADR-0005. |
| Contrato y documentación | springdoc-openapi 3 con Swagger UI | OpenAPI 3.1 generado desde el código. |
| Registros | Registro estructurado nativo de Spring Boot en JSON | Con redacción de campos sensibles (`CLAUDE.md`, regla 11). |
| Pruebas | JUnit, AssertJ, Testcontainers, jqwik, PIT, JaCoCo | Reemplazan a Vitest y Stryker en el backend. Detalle en ADR-0008. |

### Estructura del backend

`apps/api` es un proyecto Maven de varios módulos dentro del monorepo:

- **Módulo de núcleo sin dependencias** (reemplaza a `packages/domain`): `Money`, identificadores,
  errores de dominio y tipos base. No declara ninguna dependencia fuera del JDK.
- **Módulo de aplicación Spring Boot**: los módulos de negocio de `docs/01-arquitectura.md`
  sección 4 (`ledger`, `payments`, `invoicing` y los demás) como paquetes de primer nivel, cada uno
  con sus capas `domain`, `application`, `infrastructure` e `interface`.
- **Tres puntos de entrada** en el mismo artefacto: administrativo, portal y trabajador. Cada uno
  declara de forma explícita qué módulos carga. El proceso del portal no registra controladores
  administrativos (ADR-0003).
- Un `package.json` mínimo en `apps/api` delega en el Maven Wrapper, para que Turborepo ordene la
  construcción: backend, luego generación de contrato, luego aplicaciones web.

### Representación monetaria en Java

La decisión de ADR-0004 sobre PostgreSQL (`NUMERIC(14,4)` y moneda junto a cada importe) no cambia.
Cambia la representación dentro de la aplicación:

- `Money` es un objeto de valor inmutable que encapsula un `BigDecimal` normalizado a escala cuatro
  y una moneda ISO 4217 explícita.
- No existe constructor desde `double` ni desde `float`. Se construye desde cadena decimal, desde
  `BigDecimal` o desde el valor `NUMERIC` que devuelve el controlador de base de datos.
- La igualdad compara importe normalizado y moneda, nunca `BigDecimal.equals` sin normalizar.
- El redondeo es siempre explícito con `RoundingMode.HALF_UP`, conforme a la política de ADR-0004.
- La serialización hacia la API se mantiene como `{ amount: string, currency: string }`.

La actualización formal de la sección de representación de ADR-0004 se registra en un ADR propio.

### Contrato de API y Swagger

- El OpenAPI 3.1 se genera desde el código durante la construcción y se versiona como artefacto.
- `packages/contracts` deja de escribirse a mano: contiene los tipos TypeScript y los esquemas Zod
  generados con orval a partir de ese OpenAPI. El panel, el portal y la app móvil futura consumen
  ese paquete.
- **Swagger UI y el endpoint del OpenAPI se habilitan solo en los perfiles local y de
  preproducción.** En producción quedan deshabilitados en los tres procesos. La documentación de
  producción es el artefacto OpenAPI versionado, no un endpoint en vivo. Exponer el mapa completo
  de la API en el proceso público del portal contradice ADR-0003.

### Fuera del alcance de este ADR

- **Trabajos en segundo plano.** BullMQ era una elección atada a Node. Su reemplazo se decide en un
  ADR específico, con la restricción de no introducir un intermediario de mensajes nuevo en la fase
  uno.
- **Estrategia de acceso a datos.** Se decide en un ADR específico, con una restricción fijada
  desde ahora: los asientos del libro mayor y los documentos fiscales no se persisten como entidades
  JPA gestionadas, sino con acceso explícito (jOOQ o Spring JDBC), para que ninguna actualización
  implícita pueda modificar un registro financiero.

## Consecuencias

**Positivas:**

- El desarrollador trabaja desde el primer día en la plataforma que mejor domina.
- La exactitud decimal pasa a ser una garantía del lenguaje y no solo de la disciplina.
- El dominio sin framework se protege con una frontera de compilación de Maven, además de pruebas
  de arquitectura.
- Spring Modulith aporta verificación de fronteras y bandeja de salida transaccional para eventos
  de dominio sin desarrollo propio.
- Swagger UI da documentación interactiva de la API en desarrollo y preproducción sin trabajo
  adicional.
- La app móvil futura consume el mismo contrato generado, sin depender de que sea TypeScript.

**Negativas y costos aceptados:**

- **Dos cadenas de herramientas** que mantener actualizadas y auditar. Es el costo más caro de esta
  decisión.
- **Generación de contrato obligatoria.** Si el paso de generación falla o se omite, el frontend
  compila contra un contrato viejo. Se mitiga haciéndolo parte obligatoria de la construcción.
- **Mayor memoria por proceso.** Hay que medir el consumo real en F0 y dimensionar los hosts en
  consecuencia.
- **Reescritura de documentación antes de implementar.** `CLAUDE.md`, `docs/01-arquitectura.md`,
  ADR-0004, ADR-0008, ADR-0010, ADR-0012, `docs/06-estrategia-de-testing.md` y los agentes y skills
  del proyecto asumen NestJS, Prisma, Zod en el servidor y Vitest. Deben actualizarse antes de la
  primera línea de código.
- Se pierde el modo de trabajo de "un solo lenguaje" para quien lea el repositorio: el backend y el
  frontend se revisan con criterios y herramientas distintas.

**Riesgos y mitigaciones:**

| Riesgo | Mitigación |
|---|---|
| Uso de `double` o `float` para un importe | Reglas de ArchUnit que prohíben campos, parámetros y retornos `double` o `float` en tipos monetarios, y prohíben `new BigDecimal(double)` y `BigDecimal.valueOf(double)` en todo el código de producción. |
| Comparación de importes con `BigDecimal.equals` | Regla de ArchUnit que prohíbe `BigDecimal.equals` fuera de `Money`, más normalización de escala en el constructor. |
| Deriva del contrato entre backend y clientes | Generación del OpenAPI y del paquete de contratos en cada construcción, verificación de tipos de las aplicaciones web contra el paquete regenerado, y comparación del OpenAPI contra la instantánea aprobada. |
| Actualización implícita de un registro financiero por JPA | Restricción de acceso explícito para libro mayor y documentos fiscales, más permisos de base de datos sin `UPDATE` ni `DELETE` sobre esas tablas (ADR-0003 y ADR-0007). |
| Exposición de Swagger UI en producción | Prueba de integración que arranca cada proceso con el perfil de producción y afirma que `/swagger-ui` y el endpoint del OpenAPI responden 404. |
| Memoria insuficiente en los hosts de fase uno | Medición en F0 con límites de memoria declarados en Docker Compose. Si no alcanza, se evalúa compilación nativa con GraalVM, que tiene costos propios de construcción y de reflexión. |
| Fin de soporte de la línea de Spring Boot | Ventana de actualización planificada cada seis meses, al ritmo de las versiones menores. |
| Vulnerabilidad en una dependencia de Maven o de `pnpm` | Escaneo en cada construcción de ambas cadenas, con bloqueo ante severidad alta o crítica. |

## Cumplimiento y verificación

Todo lo siguiente se ejecuta en integración continua y una falla rompe la construcción.

1. **Lenguajes permitidos.** Regla de la construcción que falla si aparece código fuente de
   servidor fuera de Java en `apps/api`, código Java fuera de `apps/api`, o un manifiesto de otro
   sistema de construcción de servidor (`build.gradle`, `build.gradle.kts`, `composer.json`,
   `*.csproj`, `requirements.txt`, `go.mod`).
2. **Versión de Java en un único lugar.** Declarada en el `pom.xml` padre y verificada con
   `maven-enforcer-plugin` (`requireJavaVersion`), más un script que compara esa versión con la
   imagen base del `Dockerfile` y con la matriz de integración continua. Divergencia igual a falla.
3. **Dependencias fijadas.** Maven Wrapper comprometido al repositorio, versiones gestionadas por la
   lista de materiales de Spring Boot, `maven-enforcer-plugin` con convergencia de dependencias y
   prohibición de versiones `SNAPSHOT` en la rama principal.
4. **Pureza del dominio.** El módulo de núcleo no declara dependencias fuera del JDK, verificado con
   `bannedDependencies` de `maven-enforcer-plugin`. ArchUnit verifica que ningún paquete `domain`
   importe `org.springframework`, `jakarta.persistence` ni clases de entrada y salida.
5. **Fronteras entre módulos.** Prueba de Spring Modulith que verifica la estructura de módulos de
   aplicación: ningún módulo accede a los internos de otro. Ver ADR-0002.
6. **Reglas monetarias.** Las reglas de ArchUnit de la tabla de riesgos se ejecutan en cada
   construcción. Los cuatro casos numéricos de regresión de ADR-0004 se mantienen como pruebas
   unitarias de `Money`.
7. **Contrato de API.** El OpenAPI generado se compara contra la instantánea versionada; una
   diferencia no declarada rompe la construcción. Todo campo monetario del OpenAPI debe ser el
   objeto `{ amount: string, currency: string }`. `packages/contracts` se regenera con orval y
   `tsc --noEmit` se ejecuta sobre las aplicaciones web contra el paquete regenerado.
8. **Swagger deshabilitado en producción.** Prueba de integración por proceso con el perfil de
   producción, descrita en la tabla de riesgos.
9. **Separación de procesos.** Prueba que arranca el contexto del portal y afirma que no registra
   ningún controlador de módulos administrativos. Ver ADR-0003.
10. **Vulnerabilidades.** Escaneo de las dependencias de Maven y de `pnpm` en cada construcción, con
    bloqueo ante severidad alta o crítica.

## Referencias

- ADR-0001: stack tecnológico unificado en TypeScript (reemplazado por este ADR)
- ADR-0002: monolito modular frente a microservicios
- ADR-0003: separación entre administración y portal
- ADR-0004: PostgreSQL y representación monetaria
- ADR-0006: suite TanStack en el frontend
- ADR-0007: libro mayor de doble partida
- ADR-0008: estrategia de pruebas
- ADR-0010: idempotencia y concurrencia financiera
- `CLAUDE.md`, reglas 3, 9, 10 y 11
- `docs/01-arquitectura.md`, secciones 3, 4 y 9
- `docs/11-riesgos.md`, riesgo R01
