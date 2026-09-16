# CONFIA — Contexto del proyecto

> Documento de contexto permanente para el ciclo de desarrollo dirigido por especificaciones
> (SDD). Todo agente que participe en una fase del ciclo lee este archivo antes de actuar.
> Si algo aquí contradice `docs/01-arquitectura.md`, gana el documento de arquitectura.

---

## Propósito del sistema

CONFIA (Control Financiero Académico) es un sistema de gestión de pagos estudiantiles para
instituciones educativas, con operación inicial bajo el régimen fiscal de Honduras (SAR/CAI).
Cubre el ciclo completo del dinero de un estudiante: devengo de cargos, cobro en ventanilla o en
línea, facturación fiscal, cobranza de mora, conciliación bancaria y reporte financiero, todo
sobre un libro mayor de doble partida inmutable que sirve de fuente única de verdad del saldo. El
sistema atiende dos audiencias con riesgo asimétrico (personal administrativo interno y
encargados de pago desde internet abierto) y procesa datos personales de menores de edad, lo que
exige un estándar de protección superior al de un sistema corporativo típico.

---

## Stack tecnológico

| Capa | Tecnología |
|---|---|
| Monorepo | pnpm workspaces + Turborepo |
| Lenguajes | Java 25 LTS en el backend, TypeScript estricto en el frontend |
| Backend | Spring Boot 4.1, construido con Maven y Maven Wrapper |
| Modularidad | Spring Modulith 2 y ArchUnit |
| Migraciones | Flyway |
| Acceso a datos | jOOQ de código abierto, con código generado desde las migraciones y confinado a `infrastructure` (ADR-0015). Sin JPA, Hibernate ni Spring Data. Un único componente transaccional en `shared/security` abre las transacciones |
| Base de datos | PostgreSQL 18 en todos los entornos (ADR-0015), `NUMERIC(14,4)` para dinero, seguridad a nivel de fila |
| Validación | Jakarta Bean Validation en el borde del backend; Zod en el frontend, generado desde el OpenAPI |
| Contrato de API | OpenAPI 3.1 generado con springdoc-openapi; `packages/contracts` generado con orval |
| Seguridad | Spring Security |
| Trabajos en segundo plano | db-scheduler sobre PostgreSQL (ADR-0016): tareas programadas dentro de la transacción del negocio y ejecutadas solo en `confia-worker`. Sin intermediario de mensajes ni colas en Redis |
| Caché y limitación de tasa | Redis |
| Archivos | Almacenamiento compatible con S3: Amazon S3 en preproducción y producción, MinIO en local y en pruebas de integración (ADR-0014). Respaldos fuera de sitio en un proveedor distinto de AWS (Backblaze B2 o Cloudflare R2) |
| Frontend | React 19 con Vite |
| Enrutado y estado de servidor | TanStack Router, TanStack Query |
| Tablas y formularios | TanStack Table, TanStack Form con Zod |
| UI | Tailwind CSS v4, shadcn/ui sobre Radix |
| Internacionalización | i18next con `Intl` nativo |
| Pruebas del backend | JUnit, AssertJ, Testcontainers, jqwik, PIT, JaCoCo, ArchUnit, pruebas de Spring Modulith |
| Pruebas del frontend | Vitest, Testing Library, MSW, Playwright, axe-core |
| Rendimiento | k6 |
| Observabilidad | Registro estructurado nativo de Spring Boot en JSON, OpenTelemetry, Prometheus, Grafana |

Detalle y alternativas descartadas: `docs/01-arquitectura.md` sección 3 y `docs/adr/`, en
particular ADR-0013.

---

## Convenciones de código y de estructura

Resumen operativo. La fuente de verdad completa vive en `CLAUDE.md` (raíz del repositorio) y en
`docs/01-arquitectura.md`.

- Arquitectura hexagonal por capacidad de negocio, organización screaming. Cada módulo de
  negocio es un paquete de primer nivel del módulo de aplicación Spring Boot en `apps/api`, con
  los paquetes `<module>.domain`, `<module>.application`, `<module>.infrastructure` y
  `<module>.web`. La capa conceptual `interface` vive en el paquete `web` porque `interface` es
  palabra reservada de Java. Regla de dependencia: `interface` (`web`) → `application` →
  `domain`; `infrastructure` implementa puertos de `application`; `domain` no importa de nadie;
  ningún módulo importa el `domain` de otro módulo. Se verifica en integración continua con
  ArchUnit y Spring Modulith.
- El objeto de valor `Money` vive en el módulo de núcleo del backend (módulo Maven sin
  dependencias fuera del JDK): `BigDecimal` normalizado a escala cuatro más moneda ISO 4217
  explícita, sin constructor desde `double` ni `float`, redondeo explícito `HALF_UP`. Toda
  aritmética monetaria es una operación de `Money`; las reglas de mora, impuestos e imputación de
  pagos viven en el paquete `domain` de cada módulo. Nunca `double` ni `float` para dinero en
  Java, y nunca aritmética con `number` de JavaScript sobre importes en el frontend.
- La API expone los importes como `{ amount: string, currency: string }`. Los campos de
  presentación llegan redondeados por el servidor a la escala menor de la moneda; el navegador
  solo formatea con `Intl.NumberFormat`.
- Frontend en patrón contenedor y presentación, diseño atómico en `packages/ui`, estado de
  servidor solo con TanStack Query, tablas paginadas y filtradas en servidor.
- Commits convencionales, sin atribución de herramientas de IA. Una rama por cambio SDD.
- Detalle completo, incluida la lista numerada de reglas no negociables: `CLAUDE.md`.

---

## Reglas no negociables del dominio

1. El saldo de un estudiante se deriva del libro mayor; nunca se almacena como campo mutable.
2. Nada financiero se borra ni se edita. Toda corrección es un asiento de reverso.
3. Toda transacción del libro mayor cuadra: la suma del debe iguala la suma del haber.
4. Todo endpoint que mueva dinero exige y respeta una clave de idempotencia.
5. Los correlativos fiscales se obtienen de una secuencia con bloqueo, jamás con `MAX(...) + 1`.
6. Toda autorización se decide en el servidor. Nunca se confía en el cliente.
7. Ninguna escritura financiera ocurre fuera de una transacción de base de datos explícita con
   bloqueo sobre la cuenta afectada.
8. Toda acción sensible se registra en la bitácora de auditoría inmutable.
9. Nunca se registran en logs contraseñas, tokens, cabeceras de autorización, números de
   documento de identidad, números de tarjeta ni datos de menores.
10. El sistema procesa datos de menores de edad: minimización, cifrado de lo sensible y respeto
    de la política de retención son condición de aceptación, no mejora posterior.

---

## Capacidades del sistema

Cada capacidad corresponde a un módulo de negocio del backend en `apps/api`
(`docs/01-arquitectura.md` sección 4). La fase referencia el roadmap de `docs/09-roadmap-y-fases.md` y las brechas
bloqueantes de `docs/10-analisis-de-brechas.md`.

| Identificador | Propósito | Fase |
|---|---|---|
| `identity` | Autenticación, autorización basada en permisos, MFA y sesiones del personal y de los encargados, en dominios de identidad separados | F0 |
| `organization` | Institución, año lectivo, modalidad, grado y sección | F0 |
| `students` | Estudiantes y matrícula | F1 |
| `guardians` | Encargados de pago y su vínculo con los estudiantes a su cargo | F1 |
| `catalog` | Conceptos de pago y tarifas con vigencia temporal | F2 |
| `scholarships` | Becas y descuentos aplicables a los cargos | F2 |
| `charges` | Motor de devengo y generación automática e idempotente de cargos | F3 |
| `ledger` | Libro mayor de doble partida, núcleo financiero inmutable del que se deriva el saldo | F3 |
| `payments` | Registro y aplicación de pagos, abonos y pagos parciales | F3 |
| `cashbox` | Sesiones de caja, arqueo y cierre diario del efectivo | F4 |
| `invoicing` | Facturación fiscal, control de rango CAI, correlativos y notas de crédito | F5 |
| `collections` | Motor de mora, recargos, promesas de pago y cobranza | F6 |
| `reconciliation` | Conciliación bancaria y estado declarado frente a confirmado del pago | F4 (estado), F10 (conciliación completa) |
| `documents` | Solicitudes de constancias, certificaciones y justificaciones estudiantiles | F11 |
| `reporting` | Reportes financieros, exportación contable y proyección de ingresos | F7 |
| `notifications` | Notificación multicanal con plantillas versionadas y bitácora de entrega | F6 |
| `portal` | Capa de servicio del portal de encargados, como dominio de identidad y proceso separado | F8 |

La bitácora de auditoría inmutable (`shared/audit`) y la infraestructura de idempotencia
(`shared/security`) se construyen en F0 como fundación transversal, no como capacidad propia.

---

## Convención de nombres de cambios y de ramas

- Identificador de cambio: `kebab-case` corto y descriptivo del resultado, sin prefijo de
  capacidad obligatorio cuando el cambio toca una sola (por ejemplo `bloqueo-por-intentos-fallidos`).
  Cuando el cambio cruza varias capacidades, se nombra por el objetivo de negocio, no por la
  lista de módulos.
- Carpeta del cambio: `openspec/changes/<identificador-del-cambio>/`.
- Rama de Git: una rama por cambio SDD, nombrada igual que el identificador del cambio
  (`git checkout -b <identificador-del-cambio>`).
- Un cambio archivado conserva su identificador como prefijo de carpeta en `openspec/archive/`,
  con la fecha de archivado.

---

## Idioma de los artefactos

- Documentos de `openspec/` (proposal, spec, design, tasks) y toda la documentación de negocio:
  español neutro profesional.
- Identificadores de capacidad, de campo, de estado y de transacción: inglés.
- Código, comentarios técnicos, nombres de rama y mensajes de commit: inglés, según `CLAUDE.md`.
- La conversación con el propietario del producto ocurre en español; eso no cambia el idioma de
  los artefactos técnicos.
