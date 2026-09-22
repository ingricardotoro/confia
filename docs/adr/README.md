# Registros de decisión de arquitectura

## Qué es un ADR

Un Registro de Decisión de Arquitectura documenta **una decisión estructural, su contexto y sus
consecuencias**, en el momento en que se toma.

No es documentación de diseño. Es memoria. Su función es responder, meses o años después, a la
pregunta que todo desarrollador se hace al encontrar algo raro en un sistema heredado: por qué está
hecho así.

En CONFIA esto importa más de lo normal por dos razones. La primera es que hay un solo
desarrollador, así que la única alternativa a escribirlo es recordarlo. La segunda es la
transferencia tecnológica al departamento de sistemas de la institución, que es un objetivo
contractual: sin ADR, esa transferencia entrega código sin razones.

## Cuándo escribir uno

Escribe un ADR cuando la decisión cumple al menos una de estas condiciones:

- Es costosa de revertir. Esquema de base de datos, elección de framework, modelo de identidad.
- Afecta a más de un módulo o a todo el sistema.
- Se eligió entre alternativas razonables y alguien va a preguntar por qué no la otra.
- Introduce una restricción permanente que el equipo debe respetar.
- Acepta deliberadamente un costo o un riesgo.

**No escribas un ADR** para elegir el nombre de una variable, para una decisión reversible en una
tarde, ni para documentar cómo funciona algo. Para eso están el código y la documentación técnica.

## Estados

| Estado | Significado |
|---|---|
| **Propuesto** | Escrito, pendiente de aprobación del propietario del producto |
| **Aceptado** | Aprobado y vigente. Obligatorio para todo el código nuevo |
| **Rechazado** | Se consideró y se descartó. Se conserva para no volver a discutirlo |
| **Obsoleto** | Ya no aplica porque el contexto cambió, sin decisión que lo sustituya |
| **Reemplazado por ADR-XXXX** | Una decisión posterior lo sustituye |

**Un ADR nunca se borra ni se reescribe.** Si la decisión cambia, se marca como reemplazado y se
escribe uno nuevo que explique qué cambió en el contexto. Borrar un ADR destruye exactamente la
información que justifica su existencia.

Lo único que se edita de un ADR ya aceptado es su línea de estado.

## Índice

| Número | Título | Estado | Fecha | Resumen |
|---|---|---|---|---|
| [0001](ADR-0001-stack-tecnologico.md) | Stack tecnológico | Reemplazado por ADR-0013 | 2026-09-09 | TypeScript en todo el stack, NestJS y React con Vite. Descarta Laravel, .NET y Spring por el lenguaje único y el costo operativo |
| [0002](ADR-0002-monolito-modular-vs-microservicios.md) | Monolito modular frente a microservicios | Aceptado | 2026-09-09 | Monolito modular con hexagonal y screaming. Los microservicios no se justifican con un solo desarrollador |
| [0003](ADR-0003-separacion-admin-portal.md) | Separación entre administración y portal | Aceptado | 2026-09-09 | Un código y una base de datos, con dos procesos, roles de base de datos separados, identidades disjuntas y orígenes distintos |
| [0004](ADR-0004-postgresql-y-representacion-monetaria.md) | PostgreSQL y representación monetaria | Aceptado | 2026-09-09 | PostgreSQL con NUMERIC de precisión fija y objeto de valor `Money` sobre `BigDecimal` con moneda explícita. La coma flotante queda prohibida |
| [0005](ADR-0005-autenticacion-y-gestion-de-sesiones.md) | Autenticación y gestión de sesiones | Aceptado | 2026-09-10 | Token de acceso corto más refresco rotativo con detección de reutilización, Argon2id y MFA obligatoria para roles financieros |
| [0006](ADR-0006-tanstack-frontend.md) | Suite TanStack en el frontend | Aceptado | 2026-09-10 | Router, Query, Table, Form y Virtual. Decisión del propietario, con sus convenciones obligatorias |
| [0007](ADR-0007-libro-mayor-de-doble-partida.md) | Libro mayor de doble partida | Aceptado | 2026-09-10 | Núcleo financiero inmutable con saldo derivado. Prohibido el campo de saldo mutable |
| [0008](ADR-0008-estrategia-de-pruebas.md) | Estrategia de pruebas | Aceptado | 2026-09-10 | Trofeo de pruebas con PostgreSQL real. Cinco de las seis garantías críticas son invisibles para un doble de prueba |
| [0009](ADR-0009-multitenencia.md) | Multi-institución | Aceptado | 2026-09-10 | Esquema preparado desde la primera migración, despliegue de una sola institución. La funcionalidad se difiere |
| [0010](ADR-0010-idempotencia-y-concurrencia-financiera.md) | Idempotencia y concurrencia financiera | Aceptado | 2026-09-10 | Clave de idempotencia, bloqueo pesimista, aislamiento serializable y secuencia de correlativo con bloqueo |
| [0011](ADR-0011-internacionalizacion-y-multimoneda.md) | Internacionalización y multi-moneda | Aceptado | 2026-09-10 | Estructura preparada desde el inicio, contenido diferido. La moneda se almacena junto a cada importe |
| [0012](ADR-0012-contenerizacion.md) | Contenerización | Aceptado | 2026-09-10 | Todo el sistema en contenedores con Docker Compose. Kubernetes descartado por costo operativo |
| [0013](ADR-0013-backend-java-spring-boot.md) | Backend en Java con Spring Boot | Aceptado | 2026-09-14 | Java 25 LTS, Spring Boot 4.1 y Maven. Swagger UI con springdoc solo fuera de producción. Frontend sin cambios, con contrato generado desde OpenAPI. Reemplaza a ADR-0001 |
| [0014](ADR-0014-proveedor-de-nube-aws-y-portabilidad.md) | AWS como proveedor de nube con política de portabilidad | Aceptado | 2026-09-14 | EC2, RDS for PostgreSQL, S3 y SES por SMTP. Servicios propietarios prohibidos en el código. DNS, borde y respaldos fuera de AWS. Simulacro de salida anual |
| [0015](ADR-0015-acceso-a-datos-con-jooq.md) | Acceso a datos con jOOQ y un componente transaccional único | Aceptado | 2026-09-15 | jOOQ de código abierto en todo el backend, sin JPA. PostgreSQL 18 en todos los entornos. Un único componente abre transacciones, fija el contexto de seguridad y reintenta |
| [0016](ADR-0016-trabajos-en-segundo-plano.md) | Trabajos en segundo plano con db-scheduler sobre PostgreSQL | Aceptado | 2026-09-15 | Tareas durables en una tabla de PostgreSQL, programadas dentro de la transacción del negocio y ejecutadas solo en el trabajador. Sin intermediario de mensajes |
| [0017](ADR-0017-tablas-tecnicas-y-tabla-raiz.md) | Tablas técnicas de bibliotecas y tabla raíz de institución | Aceptado | 2026-09-15 | Catálogo cerrado de cuatro tablas exceptuadas, permisos exactos por rol, sin datos personales en tablas técnicas y portal sin permisos de modificación ni borrado |
| [0018](ADR-0018-conjunto-vacio-en-reglas-de-arquitectura.md) | Conjunto vacío en las reglas de arquitectura y caducidad de sus excepciones | Aceptado | 2026-09-16 | El valor predeterminado de ArchUnit ante conjunto vacío vuelve a `true`. La excepción se declara regla por regla, cita este ADR y caduca con el primer módulo de negocio, verificado por un inventario que rompe la construcción |
| [0019](ADR-0019-error-de-dominio-base-en-el-nucleo.md) | Error de dominio base en el núcleo | Aceptado | 2026-09-18 | `DomainException` abstracta y no comprobada en `kernel`, con código estable en kebab-case que alimenta el `type` de Problem Details. Los errores de programación siguen usando las excepciones del JDK |
| [0020](ADR-0020-capas-opcionales-en-la-regla-de-capas.md) | Capas opcionales en la regla de capas mientras un módulo no tiene adaptadores | Aceptado | 2026-09-19 | Amplía ADR-0018: `Infrastructure` y `Web` pueden declararse opcionales en la mitad de producción de la regla de capas, cada una con caducidad propia en el inventario y marcador en el escáner. `withOptionalLayers(true)` queda prohibido |
| [0021](ADR-0021-ubicacion-del-codigo-generado-de-jooq.md) | Ubicación del código generado de jOOQ fuera del paquete base de la aplicación | Aceptado | 2026-09-20 | El código que genera jOOQ vive en `confia.generated.jooq`, fuera de `com.confia`, sin referencias globales y sin excepción alguna en las reglas de arquitectura. Solo JaCoCo lo excluye, con cita a este ADR |

## Cómo aprobar un ADR

Los ADR en estado **Propuesto** no son vinculantes todavía.

El propietario del producto los revisa, discute lo que quiera discutir, y cambia el estado a
**Aceptado** los que apruebe. A partir de ese momento son obligatorios para todo el código nuevo, y
el agente `confia-code-reviewer` los usa como criterio de revisión.

Un ADR que nadie aprueba explícitamente no protege nada. Aprobarlos es el primer paso del proyecto,
antes de escribir la primera línea de código.

## Plantilla

Para escribir un ADR nuevo, copia [ADR-template.md](ADR-template.md) y numéralo con el siguiente
número disponible. Los números no se reutilizan, ni siquiera los de ADR rechazados.
