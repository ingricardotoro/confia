# ADR-0008: Estrategia de pruebas basada en el trofeo, con base de datos real

- **Estado:** Aceptado
- **Fecha:** 2026-09-10
- **Decisores:** Propietario del producto y arquitecto
- **Contexto técnico:** Todo el monorepo: backend Java construido con Maven y frontend TypeScript construido con `pnpm`. Integración continua.
- **Revisión:** 2026-09-14. Alineado con ADR-0013 (backend en Java con Spring Boot). La decisión no cambia; se actualizan las herramientas y los mecanismos de verificación. Los umbrales no cambian.

## Contexto y problema

CONFIA lo mantiene una sola persona. Eso cambia por completo la función de las pruebas.

En un equipo, las pruebas coordinan: evitan que alguien rompa el trabajo de otro. Con un solo
desarrollador esa función desaparece, pero aparece otra más importante: **las pruebas son el único
revisor disponible**. No hay un segundo par de ojos que note que el cálculo de mora ignora los días
de gracia. Solo hay una prueba que lo verifique, o nada.

Además, un sistema financiero falla de forma silenciosa. Un error de redondeo no lanza una
excepción: produce un número plausible. Un error en una política de aislamiento de datos no rompe
nada visible: simplemente muestra el estado de cuenta de otro estudiante. Ninguno de los dos se
detecta mirando la pantalla.

La pregunta a decidir es dónde invertir el esfuerzo limitado de pruebas para que detecte
precisamente estos fallos.

## Factores de decisión

| Factor | Peso | Razón |
|---|---|---|
| Capacidad de detectar fallos silenciosos de dinero | Muy alto | Es el modo de fallo característico del dominio |
| Confianza en refactorizar sin equipo que revise | Muy alto | Sin pruebas, un desarrollador solo deja de tocar el código por miedo |
| Capacidad de verificar transacciones, bloqueos y aislamiento de datos | Muy alto | Son garantías de base de datos, no de aplicación |
| Costo de mantenimiento de las pruebas | Alto | Pruebas frágiles se abandonan y dejan de proteger |
| Velocidad de retroalimentación | Medio | Importa, pero menos que la corrección en este dominio |

## Opciones consideradas

### Opción A: Pirámide clásica con dobles de prueba

Muchas pruebas unitarias con repositorios simulados, pocas de integración, muy pocas de extremo a
extremo.

**Ventajas.** Muy rápida. Sin infraestructura. Aislamiento perfecto de cada unidad.

**Desventajas.** Es la opción que **no detecta ninguno de los fallos que importan en este sistema**.
Un repositorio simulado devuelve lo que el desarrollador supuso que devolvería. No prueba que la
transacción exista, que el bloqueo funcione, que la restricción de cuadre esté activa, ni que la
política de aislamiento de filas impida leer datos ajenos. Una suite verde con dobles de prueba
puede convivir perfectamente con un sistema que filtra el estado de cuenta de todos los
estudiantes.

### Opción B: Trofeo de pruebas con base de datos real

Peso fuerte en pruebas de integración contra PostgreSQL real levantado en contenedor, unitarias
concentradas en la lógica pura de dominio, componentes con red simulada, y un conjunto corto de
recorridos de extremo a extremo.

**Ventajas.** Verifica lo que realmente puede fallar. Las pruebas de integración sobreviven a las
refactorizaciones internas porque prueban comportamiento y no estructura. Detecta errores de
esquema, de transacción, de bloqueo y de política de acceso.

**Desventajas.** Más lentas. Requiere Docker en la máquina de desarrollo y en integración continua.
Necesita disciplina de limpieza de datos entre pruebas.

### Opción C: Predominio de extremo a extremo

Pocas pruebas, pero completas, a través del navegador.

**Ventajas.** Máxima confianza en que el flujo completo funciona.

**Desventajas.** Lentas, inestables, y cuando fallan no dicen dónde está el problema. Con un solo
desarrollador, una suite inestable se ignora en dos semanas y deja de proteger.

## Decisión

**Se adopta la opción B: trofeo de pruebas, con pruebas de integración contra PostgreSQL real
mediante Testcontainers.**

El argumento decisivo es concreto. Estas son las garantías críticas del sistema y dónde viven:

| Garantía | ¿La verifica un doble de prueba? | ¿La verifica una base de datos real? |
|---|---|---|
| La transacción del libro mayor cuadra | No. La restricción vive en el motor | Sí |
| Dos cajeros no aplican el mismo cargo dos veces | No. El bloqueo vive en el motor | Sí |
| Un encargado no lee datos de otro estudiante | No. La política vive en el motor | Sí |
| El correlativo fiscal no se duplica | No | Sí |
| Una clave de idempotencia repetida no crea un segundo pago | No. La restricción única vive en el motor | Sí |
| El redondeo de un descuento con impuesto es correcto | Sí, y es donde las unitarias brillan | También |

Cinco de las seis garantías más importantes del sistema son **invisibles** para una prueba con
dobles. Optimizar por velocidad de ejecución a costa de no verificar esas cinco sería optimizar la
métrica equivocada.

Las pruebas unitarias no desaparecen: se concentran donde aportan más, que es la lógica pura del
backend (el módulo de núcleo y la capa `domain` de cada módulo): dinero, imputación de pagos, mora
e impuestos. Ahí la exigencia es la más alta del proyecto.

## Niveles y umbrales

| Nivel | Herramienta | Cubre | Umbral |
|---|---|---|---|
| Estático (backend) | Compilador de Java, `maven-enforcer-plugin`, ArchUnit y verificación de módulos de Spring Modulith | Tipos, pureza del módulo de núcleo, reglas de capa y fronteras entre módulos | Cero errores |
| Estático (frontend) | TypeScript estricto, ESLint, dependency-cruiser | Tipos, estilo, reglas del frontend | Cero errores |
| Unitario (backend) | JUnit y AssertJ, con jqwik para pruebas de propiedad | Lógica pura del módulo de núcleo y del paquete `domain` de cada módulo | Cobertura 95% y mutación 80% en el módulo de núcleo y en el paquete `domain` de cada módulo |
| Integración (backend) | JUnit con Testcontainers y PostgreSQL real | Repositorios, transacciones, bloqueos, restricciones, políticas de acceso por fila | Cobertura global 80% |
| Componente (frontend) | Vitest, Testing Library y MSW | Comportamiento de pantallas con red simulada | Recorridos principales de cada pantalla |
| Contrato | Comparación del OpenAPI generado contra la instantánea aprobada, regeneración de `packages/contracts` con orval y `tsc --noEmit` sobre las aplicaciones web | Que el backend no rompa al frontend ni a la app móvil | Cero incompatibilidades |
| Extremo a extremo | Playwright | Trece recorridos críticos, no más | Todos en verde |
| Accesibilidad | axe-core en Playwright y Storybook | Nivel AA | Cero violaciones críticas o serias |
| Rendimiento | k6 | Endpoints de pago y de reportes | Dentro del presupuesto de latencia |
| Seguridad | Semgrep, OWASP ZAP, Trivy, gitleaks, más escaneo de dependencias de Maven y de `pnpm` | Código, dependencias, aplicación desplegada, imagen, secretos | Cero severidad alta o crítica |
| Mutación | PIT sobre el módulo de núcleo y el paquete `domain` de cada módulo | Calidad real de las pruebas de dinero, mora, impuestos e imputación de pagos | Puntuación mínima 80 |

La prueba de mutación merece una nota. Una cobertura del noventa y cinco por ciento significa que
el código se ejecutó, no que se verificó. La prueba de mutación introduce defectos deliberados en
la aritmética monetaria y falla si las pruebas no los detectan. Es el único control que distingue
una prueba que verifica de una que solo pasa por encima.

## Estrategia de datos de prueba

- **Fábricas tipadas**, no accesorios estáticos. Un accesorio compartido se convierte en un objeto
  que nadie se atreve a cambiar porque no sabe qué prueba depende de él.
- **Base de datos limpia por prueba.** La transacción se revierte al terminar. El contenedor se
  reutiliza entre pruebas para no pagar el arranque cada vez.
- **Semilla determinista** para que un fallo sea reproducible.
- **Prohibido usar datos reales de estudiantes** en cualquier entorno que no sea producción. Los
  nombres, documentos de identidad y correos de las pruebas son generados. Esto no es una
  preferencia: es un requisito de protección de datos de menores.

## Consecuencias

**Positivas.**

- Las garantías financieras y de aislamiento quedan verificadas donde realmente viven.
- Las pruebas de integración sobreviven a las refactorizaciones internas.
- Un fallo de esquema o de migración se detecta en integración continua, no en producción.
- El desarrollador puede refactorizar sin pedir permiso al miedo.

**Negativas y costos aceptados.**

- La suite completa tarda más que una basada en dobles. Se mitiga separando la ejecución rápida
  que corre en cada guardado de la completa que corre antes de fusionar.
- Docker es requisito en la máquina de desarrollo y en integración continua. Ya lo es por la
  decisión de contenerización de ADR-0012.
- Escribir una prueba de concurrencia cuesta más que una prueba trivial. Es donde está el valor.

**Riesgos y mitigaciones.**

| Riesgo | Mitigación |
|---|---|
| Suite lenta que se termina ignorando | Separación entre suite rápida y suite completa. Paralelización. Contenedor reutilizado |
| Pruebas inestables de extremo a extremo | Se limitan a trece recorridos. Selectores por rol accesible, nunca por clase CSS. Cero esperas fijas |
| Cobertura alta con pruebas vacías | Prueba de mutación sobre el dominio, que es el control real de calidad |
| Presión de entrega que salta pruebas | Los umbrales bloquean la fusión de forma automática, no por decisión humana en el momento |

## Cumplimiento y verificación

| Control | Mecanismo | Cuándo |
|---|---|---|
| Umbrales de cobertura | JaCoCo en el backend y configuración de Vitest en el frontend, que fallan por debajo del umbral | En cada ejecución |
| Puntuación de mutación | PIT sobre el módulo de núcleo y el paquete `domain` de cada módulo, con umbral de ruptura en 80 | En cada fusión |
| Reglas de arquitectura del backend | Pruebas de ArchUnit y verificación de módulos de Spring Modulith, ver ADR-0002 | En cada ejecución |
| Prueba obligatoria de política de acceso por fila | Revisión que exige una prueba de integración por cada política nueva | En cada cambio de esquema |
| Casos límite de reglas monetarias | Lista obligatoria en `.claude/skills/confia-testing-playbook/SKILL.md`, verificada en revisión | En cada regla financiera nueva |
| Defecto corregido con prueba previa | El pull request de una corrección debe incluir la prueba que falla antes del arreglo | En cada corrección |
| Accesibilidad | axe-core en la suite de Playwright, con ruptura ante violación crítica o seria | En cada fusión |

## Referencias

- `docs/06-estrategia-de-testing.md`
- `.claude/skills/confia-testing-playbook/SKILL.md`
- ADR-0007 sobre el libro mayor
- ADR-0010 sobre idempotencia y concurrencia
- ADR-0012 sobre contenerización
- ADR-0013 sobre el backend en Java con Spring Boot
