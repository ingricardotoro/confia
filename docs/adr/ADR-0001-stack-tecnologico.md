# ADR-0001: Stack tecnológico unificado en TypeScript

- **Estado:** Reemplazado por ADR-0013
- **Fecha:** 2026-09-09
- **Decisores:** Propietario del producto y arquitecto
- **Contexto técnico:** Todo el sistema. `apps/api`, `apps/admin-web`, `apps/portal-web`, `apps/mobile`, `packages/domain`, `packages/contracts`, monorepo e integración continua.

## Contexto y problema

CONFIA debe entregar, con **un solo desarrollador**, un backend financiero con facturación fiscal
hondureña, dos aplicaciones web con audiencias y niveles de privilegio distintos, y una app móvil
en una fase posterior que consumirá exactamente el mismo backend.

Las fuerzas que actúan sobre la elección del stack son:

1. **Presupuesto de atención, no de dinero.** El recurso escaso no es el hosting sino las horas de
   una sola persona. Cada lenguaje adicional multiplica el costo de mantenimiento: dos cadenas de
   herramientas, dos gestores de dependencias, dos suites de pruebas, dos pipelines de seguridad y
   dos conjuntos de avisos de vulnerabilidad que revisar.
2. **El contrato de API es un producto.** La app móvil futura y el portal deben consumir los mismos
   tipos que produce el backend. Si el contrato vive en un lenguaje y los clientes en otro, la
   verificación de compatibilidad se vuelve manual o depende de generación de código con un paso
   adicional que alguien tiene que mantener.
3. **Dominio financiero.** El sistema necesita decimales exactos, transacciones explícitas,
   niveles de aislamiento seleccionables y bibliotecas maduras de PDF, criptografía y colas.
4. **Alojamiento modesto.** La institución inicial no puede sostener un servidor de aplicaciones
   caro ni licencias. La fase uno corre en dos hosts pequeños con Docker Compose.

Si no se decide ahora, cada módulo se construye con la herramienta que parezca conveniente ese día,
y el sistema termina con dos o tres runtimes que una sola persona no puede parchear al ritmo que
exige un sistema que maneja dinero.

## Factores de decisión

| Factor | Peso | Por qué pesa así con un solo desarrollador |
|---|---|---|
| Lenguaje único de extremo a extremo | Muy alto | Un contrato compartido, una cadena de herramientas, una sola curva de actualización. |
| Contrato tipado compartido con la app móvil | Muy alto | Un cambio incompatible debe romper la compilación del monorepo, no producción. |
| Carga cognitiva de mantenimiento a tres años | Muy alto | El sistema sobrevive al entusiasmo inicial. Lo que importa es cómo se siente en el mes dieciocho. |
| Madurez para dominio financiero | Alto | Decimales exactos, transacciones, criptografía, PDF, colas confiables. |
| Costo de alojamiento y operación | Alto | Dos hosts pequeños, sin licencias, sin plataforma administrada obligatoria. |
| Modularidad y estructura impuesta por el framework | Alto | Un desarrollador solo necesita que el framework decida por él, no que le dé libertad infinita. |
| Disponibilidad de librerías y de respuestas | Medio | Integraciones bancarias, generación de PDF, TOTP, OpenAPI. |
| Facilidad para contratar un relevo | Medio | Importa a futuro, pero no puede dictar la decisión de hoy. |
| Rendimiento bruto por núcleo | Bajo | El sistema es de baja concurrencia y alta criticidad. La base de datos es el cuello de botella, no el runtime. |

## Opciones consideradas

### Opción A: NestJS sobre Node.js LTS con TypeScript estricto

Backend NestJS, frontend React con Vite, ambos en TypeScript, monorepo `pnpm` con Turborepo,
paquete `packages/contracts` con esquemas Zod como fuente de verdad de la API, y `packages/domain`
sin framework para la aritmética monetaria y el libro mayor.

**Ventajas.**

- Un solo lenguaje en backend, panel administrativo, portal, paquete de dominio y la app móvil
  futura con React Native. El objeto de valor `Money` y las reglas de mora se escriben una vez.
- NestJS impone modularidad, inyección de dependencias, guards e interceptors. Eso resuelve por
  arquitectura tres problemas transversales que un desarrollador solo tendería a resolver a mano en
  cada endpoint: autorización, auditoría e idempotencia.
- Genera OpenAPI 3.1 desde el código, lo cual convierte el contrato en un artefacto versionado sin
  trabajo adicional.
- Zod produce validación en tiempo de ejecución, tipos estáticos y documentación desde un único
  esquema, reutilizable por el frontend sin duplicar reglas.
- Alojamiento barato: un contenedor de pocos cientos de megabytes de memoria por proceso.
- Prisma da migraciones versionadas y revisables, que en un sistema financiero es un requisito y no
  una comodidad.

**Desventajas.**

- El ecosistema Node rota rápido. Las dependencias envejecen más rápido que en .NET o Java, y eso
  se paga con actualizaciones frecuentes.
- Node no tiene decimal nativo. Obliga a disciplina explícita: `bigint` de unidades menores en el
  dominio y `NUMERIC(14,4)` en PostgreSQL. Esa disciplina hay que imponerla con lint y pruebas,
  no viene gratis del lenguaje. Ver ADR-0004.
- NestJS tiene una curva inicial real: módulos, proveedores, alcance de inyección, ciclo de vida.
  Las primeras dos semanas son más lentas que con un framework minimalista.
- El tipado de TypeScript se borra en tiempo de ejecución. Sin validación en el borde, los tipos
  dan una falsa sensación de seguridad frente a una entrada maliciosa.

### Opción B: Laravel con PHP

Backend Laravel con Eloquent, Filament o Livewire para el panel administrativo, API REST para el
portal y la app móvil.

**Ventajas.**

- Es probablemente la opción de mayor productividad bruta para un CRUD administrativo con
  facturación. Migraciones, colas, autenticación, políticas de autorización, notificaciones
  multicanal y programación de tareas vienen en la caja y están bien integrados.
- Alojamiento extremadamente barato y ampliamente disponible en la región.
- Ecosistema fiscal y contable de la región con presencia real en PHP.
- El tipo `decimal` y el manejo de dinero con librerías como `brick/money` son maduros.

**Desventajas en este contexto específico.**

- Rompe el lenguaje único. El frontend seguirá siendo TypeScript porque el requerimiento pide grids
  dinámicos, filtros compartibles por URL y una app móvil. Eso obliga a mantener el contrato en dos
  lenguajes y a generar tipos desde OpenAPI con un paso adicional que hay que sostener.
- El objeto `Money`, las reglas de mora y el cálculo de impuestos tendrían que existir dos veces: en
  PHP para el servidor y en TypeScript para la validación de cliente y la app móvil. **Dos
  implementaciones de la misma regla financiera divergen. No es una posibilidad, es cuestión de
  tiempo.** Esa es la razón principal del descarte.
- El desarrollador único tendría que mantener actualizadas dos cadenas de herramientas y dos
  superficies de vulnerabilidades.

**Laravel es una opción legítima.** Si el sistema fuera solo un panel administrativo con Blade o
Livewire, sin app móvil y sin portal público con reglas de cálculo en cliente, Laravel sería
probablemente la elección correcta y llegaría a producción antes. El factor que lo descarta no es
técnico sino de topología del producto: **hay tres clientes que deben compartir el mismo contrato y
las mismas reglas de dinero.**

### Opción C: .NET 8 con ASP.NET Core y Entity Framework Core

**Ventajas.**

- El mejor soporte de lenguaje para este dominio entre todos los candidatos: `decimal` nativo de
  128 bits, tipado fuerte real en tiempo de ejecución, y un modelo de concurrencia sólido.
- Herramientas de primer nivel para transacciones, niveles de aislamiento y diagnósticos.
- Madurez indiscutible en software financiero y contable.
- Generación de OpenAPI de calidad y clientes tipados.

**Desventajas en este contexto específico.**

- Rompe el lenguaje único con los mismos costos descritos en la opción B: duplicación de las reglas
  de dinero entre el servidor y los clientes.
- Mayor consumo de memoria por proceso, lo cual importa cuando la topología de fase uno son dos
  hosts modestos que además deben correr **dos procesos de API** (administrativo y portal, ver
  ADR-0003), PostgreSQL, Redis y almacenamiento de objetos.
- La cultura de herramientas alrededor de .NET tiende a asumir Windows Server, Visual Studio o
  Azure. Todo eso funciona en Linux y con contenedores, pero el camino más documentado y el que el
  desarrollador encontrará al buscar respuestas suele empujar hacia infraestructura de mayor costo.

**.NET es una opción legítima.** Para un equipo con experiencia previa en .NET, o si el sistema no
tuviera app móvil ni portal público con lógica compartida, la ventaja del `decimal` nativo y de la
madurez del ecosistema financiero justificaría con holgura el costo del segundo lenguaje.

### Opción D: Java con Spring Boot

**Ventajas.** `BigDecimal`, JPA, gestión transaccional declarativa madura y el ecosistema
financiero más probado en producción a gran escala.

**Desventajas.** Es el candidato con mayor ceremonia por unidad de valor entregado y con mayor
consumo de memoria en reposo. Todo lo dicho sobre el segundo lenguaje aplica igual. Para un
desarrollador solo con un plazo real, la relación entre esfuerzo y funcionalidad entregada es la
peor del conjunto.

### Opción E: Django con Python

**Ventajas.** Panel administrativo automático que ahorra semanas en los módulos de catálogo y
configuración, ORM con migraciones, `Decimal` correcto y una comunidad enorme.

**Desventajas.** El panel automático de Django es una trampa para este sistema: los módulos que
realmente importan (libro mayor, caja, facturación fiscal, conciliación) no son CRUD y no se
benefician de él, mientras que exponer el panel automático sobre tablas financieras contradice
directamente la separación de privilegios del ADR-0003. El tipado gradual de Python es más débil
que TypeScript en modo estricto para refactorizaciones grandes, y el segundo lenguaje vuelve a
costar la duplicación de las reglas de dinero.

### Opción F descartada sin desarrollo: Fastify con tRPC

Más ligero que NestJS y con tipado de extremo a extremo sin generación intermedia. Se descarta
porque tRPC ata al cliente a TypeScript: penaliza una app móvil nativa, cualquier integración de
terceros y la publicación de un contrato consumible por la institución. La API de CONFIA debe ser
REST con OpenAPI, porque el contrato es un entregable. Además, NestJS aporta la estructura que un
desarrollador solo necesita que el framework imponga.

## Decisión

**Se adopta la opción A: TypeScript estricto en todo el stack, Node.js LTS 22 o superior, NestJS en
el backend y React 19 con Vite en el frontend, dentro de un monorepo `pnpm` con Turborepo.**

El factor determinante es el de mayor peso: **un solo lenguaje de extremo a extremo con un contrato
tipado compartido.** El sistema tiene tres consumidores del mismo backend (panel administrativo,
portal de encargados y app móvil futura) y reglas financieras que deben ejecutarse de forma idéntica
en todos ellos. En un stack unificado, el objeto de valor `Money`, la aritmética de mora y las
reglas de impuestos viven una sola vez en `packages/domain`, y un cambio incompatible en
`packages/contracts` rompe la compilación del monorepo en integración continua. En un stack mixto,
esa misma garantía requiere generación de código, disciplina manual y una segunda implementación de
las reglas de dinero.

Laravel y .NET pierden por topología del producto, no por calidad. Laravel entregaría el panel
administrativo antes; .NET ofrece mejor soporte de lenguaje para dinero. Ninguno de los dos compensa
el costo, para una sola persona, de mantener dos cadenas de herramientas y dos copias de las reglas
financieras durante los próximos tres años.

NestJS se elige sobre alternativas minimalistas porque **impone decisiones**. Con un desarrollador
solo, la libertad estructural es un pasivo: cada semana sin un patrón obligatorio es una semana en
la que el proyecto acumula tres formas distintas de hacer lo mismo. Los guards y los interceptors de
NestJS son el lugar natural para autorización, auditoría e idempotencia, tres controles que este
sistema no puede permitirse implementar endpoint por endpoint.

## Consecuencias

**Positivas:**

- Una sola cadena de herramientas, un gestor de paquetes, un formateador, un linter, un corredor de
  pruebas y un pipeline de vulnerabilidades.
- `packages/domain` y `packages/contracts` se consumen sin traducción desde el backend, el panel, el
  portal y la app móvil futura. La regla de mora se escribe una vez y se prueba una vez.
- La ruptura de contrato se detecta en integración continua del monorepo, antes del despliegue.
- NestJS genera OpenAPI 3.1 desde el código, de modo que el manual técnico de la API es un artefacto
  producido por la construcción y no un documento que envejece.
- Conocimiento reutilizable entre React y React Native, lo cual reduce el costo de la fase móvil.
- Alojamiento de fase uno viable en dos hosts modestos, incluyendo dos procesos de API.

**Negativas y costos aceptados:**

- **Node no tiene decimal nativo.** La corrección monetaria depende de disciplina impuesta por
  herramienta, no por el lenguaje. Es el costo más caro de esta decisión y se mitiga en ADR-0004.
- Rotación de dependencias más alta que en .NET o Java. Se acepta un presupuesto recurrente de
  mantenimiento de dependencias.
- Curva inicial de NestJS de una a dos semanas antes de alcanzar velocidad de crucero.
- Los tipos de TypeScript no existen en tiempo de ejecución. Toda entrada debe validarse con Zod en
  el borde, sin excepción, incluidas las respuestas de la pasarela de pago y los webhooks.
- Se renuncia al panel administrativo automático que Laravel o Django habrían dado casi gratis para
  los módulos de catálogo y configuración.

**Riesgos y mitigaciones:**

| Riesgo | Mitigación |
|---|---|
| Uso accidental de `number` para importes | Regla de ESLint personalizada que prohíbe operadores aritméticos sobre tipos monetarios fuera de `packages/domain`, más revisión de tipos. Ver ADR-0004. |
| Vulnerabilidad en una dependencia transitiva | `pnpm audit` y escaneo de dependencias en cada construcción. Bloqueo de la construcción ante severidad alta o crítica. Versiones fijadas con archivo de bloqueo comprometido al repositorio. |
| Deriva del contrato entre backend y clientes | `packages/contracts` es la única fuente de verdad. Verificación de tipos del monorepo completo en cada commit. Prueba de contrato que compara el OpenAPI generado contra la instantánea aprobada. |
| Node LTS queda sin soporte | Ventana de actualización planificada por cada versión LTS, con la versión del runtime declarada en `.nvmrc`, en la imagen Docker y en la matriz de integración continua. |
| Dependencia excesiva de la magia de NestJS | La lógica de negocio vive en `domain` y `application`, sin decoradores de framework. Un cambio de framework afectaría `interface` e `infrastructure`, no el núcleo. |

## Cumplimiento y verificación

Todo lo siguiente se ejecuta en integración continua y una falla rompe la construcción.

1. **Un solo lenguaje.** Regla de la construcción que falla si aparece en el repositorio un archivo
   fuente de servidor fuera de TypeScript, o un manifiesto de otro gestor de paquetes
   (`composer.json`, `*.csproj`, `pom.xml`, `requirements.txt`, `go.mod`).
2. **TypeScript estricto.** `tsconfig` base en `packages/config` con `strict`, `noUncheckedIndexedAccess`,
   `exactOptionalPropertyTypes` y `noImplicitOverride` activados. `tsc --noEmit` sobre todo el
   monorepo en cada commit. La opción `any` implícita es un error, no un aviso.
3. **Versión del runtime.** Node LTS declarado en un único lugar (`package.json` con `engines`) y
   verificado por un script que compara `.nvmrc`, la imagen base del `Dockerfile` y la matriz de
   integración continua. Divergencia igual a falla.
4. **Contrato de API.** El OpenAPI generado por NestJS se compara contra la instantánea versionada.
   Una diferencia no declarada rompe la construcción y obliga a incrementar la versión o a aprobar
   la instantánea de forma explícita.
5. **Pureza del dominio.** `dependency-cruiser` prohíbe que `packages/domain` importe cualquier
   dependencia de framework, de entrada y salida, o de Node. Ver ADR-0002.
6. **Dependencias.** Auditoría de vulnerabilidades en cada construcción, con bloqueo ante severidad
   alta o crítica. Archivo de bloqueo obligatorio en el repositorio; una instalación que lo modifique
   sin cambio declarado falla.

## Referencias

- `docs/01-arquitectura.md`, secciones 3 y 4
- `CLAUDE.md`, sección Stack y Reglas no negociables
- ADR-0002: monolito modular frente a microservicios
- ADR-0004: PostgreSQL y representación monetaria
- ADR-0006: suite TanStack en el frontend
- ADR-0008: estrategia de pruebas
