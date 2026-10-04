# CONFIA — Documento de Arquitectura

> Estado: **Propuesta v1.0** — pendiente de aprobación del propietario del producto.
> Cada decisión de este documento tiene un ADR asociado en `docs/adr/`.
> Este archivo es la **fuente de verdad** de la arquitectura. Cualquier documento,
> agente o especificación que lo contradiga está desactualizado.

---

## 1. Contexto del proyecto

**CONFIA** (Control Financiero Académico) es un sistema de gestión de pagos estudiantiles
para instituciones educativas, con operación inicial en **Honduras** (régimen fiscal SAR/CAI).

### Restricciones que condicionan toda la arquitectura

| Restricción | Impacto arquitectónico |
|---|---|
| **Un solo desarrollador** | Sin microservicios, sin infraestructura que exija un equipo de plataforma, y sin más lenguajes que uno de servidor (Java) y uno de cliente (TypeScript), según ADR-0013. Se optimiza para carga cognitiva mínima y automatización máxima. |
| **Sistema financiero** | Dinero inmutable, contabilidad de doble partida, auditoría a prueba de manipulación, idempotencia obligatoria, cero borrados físicos. |
| **Cumplimiento fiscal SAR (Honduras)** | Correlativos irrepetibles, CAI con vigencia, anulaciones y notas de crédito, retención documental. Ver `docs/04-cumplimiento-fiscal-sar.md`. |
| **Dos audiencias con riesgo asimétrico** | Personal administrativo (interno, alto privilegio) frente a encargados de pago (internet abierto, bajo privilegio). |
| **App móvil futura** | El contrato de API es un producto en sí mismo desde el día uno. OpenAPI 3.1 generado desde el backend, más un paquete de contratos generado a partir de él. |
| **Ambición internacional** | Multi-moneda, internacionalización, multi-institución y accesibilidad deben existir en el modelo desde el inicio, aunque se activen después. |

### Datos de menores de edad

El sistema procesa datos personales de **menores de edad** y de sus responsables financieros.
Eso eleva el estándar de protección por encima de un sistema corporativo típico.
Ver `docs/08-datos-privacidad-y-retencion.md`.

---

## 2. Decisión central: monolito modular, no microservicios

**Se adopta un monolito modular con arquitectura hexagonal y organización screaming.**

Razón: con un solo desarrollador, los microservicios convierten cada cambio de negocio en un
problema de sistemas distribuidos. Transacciones distribuidas, versionado de contratos,
observabilidad correlacionada y despliegue coordinado tienen un costo inmediato, mientras que
el beneficio es hipotético. Un monolito modular con fronteras internas bien definidas puede
extraerse a servicios más adelante si aparece una razón real, como escala independiente o un
equipo separado. El camino inverso no existe.

La disciplina de módulos se hace obligatoria por herramienta, no por buena voluntad. En el
backend, las fronteras entre módulos se verifican con Spring Modulith y las reglas de capa
hexagonal con ArchUnit, y el módulo de núcleo es una frontera de compilación de Maven sin
dependencias fuera del JDK. En el frontend se mantienen `dependency-cruiser` y las reglas de
frontera de ESLint. Todo se ejecuta en integración continua y una violación rompe la
construcción.

Ver `docs/adr/ADR-0002-monolito-modular-vs-microservicios.md`.

---

## 3. Stack tecnológico

### 3.1 Backend

| Elemento | Elección | Razón |
|---|---|---|
| Lenguaje y runtime | **Java 25 LTS**, distribución OpenJDK sin costo de licencia (por ejemplo, Eclipse Temurin) | Plataforma que el desarrollador domina. `BigDecimal` nativo como decimal exacto del lenguaje. |
| Framework | **Spring Boot 4.1** | Gestión transaccional madura, con aislamiento explícito, bloqueo pesimista y reintento, concentrada en un único componente transaccional de `shared/security` (ADR-0015). Actualizaciones menores planificadas cada seis meses. |
| Construcción | **Maven** con Maven Wrapper | El wrapper se compromete al repositorio para fijar la versión de Maven. Proyecto de varios módulos dentro del monorepo. |
| Modularidad | **Spring Modulith 2** y **ArchUnit** | Fronteras entre módulos y reglas de capa hexagonal verificadas en pruebas. Spring Modulith aporta además un registro persistente de publicación de eventos, con su almacenamiento JDBC y sin JPA (ADR-0015). |
| Migraciones | **Flyway** | Scripts SQL versionados y revisables, algo crítico en un sistema financiero. |
| Acceso a datos | **jOOQ**, edición de código abierto (ADR-0015) | Única herramienta de acceso a datos del backend: sin JPA, Hibernate ni Spring Data, de modo que ninguna escritura implícita puede modificar un asiento ni un documento fiscal. El código se genera en cada construcción desde las migraciones de Flyway, así que una consulta incompatible con el esquema no compila. jOOQ vive solo en `infrastructure` y cada módulo usa únicamente las clases de sus propias tablas. Un único componente transaccional en `shared/security` abre las transacciones, fija el contexto de seguridad a nivel de fila y reintenta ante errores de serialización. Los reportes pesados se resuelven con SQL y, desde la fase dos, sobre la réplica de lectura. |
| Validación | **Jakarta Bean Validation** en el borde | Validación de toda entrada en el servidor. Los clientes validan con esquemas Zod generados desde el OpenAPI, sin sustituir la validación del servidor. |
| Seguridad | **Spring Security** | Base de autenticación y autorización. Ver ADR-0005. |
| Contrato y documentación | **springdoc-openapi 3** con Swagger UI | OpenAPI 3.1 generado desde el código. Swagger UI solo en los perfiles local y de preproducción; deshabilitado en producción en los tres procesos. |
| Registros | Registro estructurado nativo de Spring Boot en JSON | Con redacción de campos sensibles. |
| Trabajos en segundo plano | **db-scheduler** sobre PostgreSQL (ADR-0016), licencia Apache 2.0 | Generación de cargos, notificaciones, reportes pesados, conciliación. Una sola tabla en la base existente, sin intermediario de mensajes ni colas en Redis. Las tareas se programan dentro de la transacción del negocio a través del componente transaccional único, llevan solo identificadores y se ejecutan únicamente en `confia-worker`, con reintentos acotados y manejadores idempotentes. Prohibidos `@Scheduled`, `@EnableScheduling`, `@Async` y cualquier otra biblioteca de programación. |
| Caché y limitación de tasa | **Redis** | Limitación distribuida, bloqueo de fuerza bruta, caché de lecturas. |
| Archivos | **Almacenamiento compatible con S3**: Amazon S3 en preproducción y producción, MinIO en local y en pruebas (ADR-0014) | PDF fiscales, adjuntos, constancias. Nunca en el disco del servidor de aplicación. |

El backend se escribe en Java y el frontend en TypeScript. El contrato entre ambos no se comparte
por importación directa: se genera desde el OpenAPI del backend en cada construcción (ver sección
7). Detalle, alternativas evaluadas y costos aceptados en
`docs/adr/ADR-0013-backend-java-spring-boot.md`, que reemplaza a
`docs/adr/ADR-0001-stack-tecnologico.md`.

### 3.2 Base de datos

**PostgreSQL 18**, la misma versión mayor en desarrollo local, pruebas, preproducción y producción
(ADR-0015). No es negociable para este dominio. El requisito mínimo por las capacidades que se
enumeran abajo es PostgreSQL 16; se fija la 18 porque la edición de código abierto de jOOQ solo
soporta la versión más reciente del motor. Un script de integración continua compara la versión
declarada en Docker Compose, en Testcontainers y en la configuración de RDS, y una divergencia
rompe la construcción.

- `NUMERIC` de precisión arbitraria para dinero. Nunca coma flotante.
- Transacciones ACID con niveles de aislamiento seleccionables. `SERIALIZABLE` para cierre de caja.
- **Seguridad a nivel de fila**: la separación de datos entre encargados se aplica en el motor,
  no solo en el código. Es la única forma de que un error de aplicación no se convierta
  automáticamente en una fuga de datos.
- Índices parciales, columnas generadas, restricciones `EXCLUDE` y `CHECK` complejos. Los
  invariantes financieros se declaran en el esquema, no solo en el código de la aplicación.
- Recuperación a un punto en el tiempo: capacidad nativa de PostgreSQL basada en el archivado de
  WAL. En preproducción y en producción la ofrece RDS for PostgreSQL de forma gestionada, sin
  archivado propio que operar (ADR-0014); en local y en pruebas es una capacidad del motor que no
  se ejercita.

### 3.3 Frontend

| Elemento | Elección | Razón |
|---|---|---|
| Framework | **React 19** con **Vite** | Ecosistema amplio y reutilización de conocimiento hacia React Native. |
| Enrutado | **TanStack Router** | Rutas tipadas y parámetros de búsqueda validados, algo crítico para filtros de reportes compartibles por URL. |
| Estado de servidor | **TanStack Query** | Caché, reintentos, invalidación y estados normalizados. Elimina la mayor parte del estado global manual. |
| Tablas | **TanStack Table** | El requerimiento pide grids dinámicos. Paginación, orden y filtrado en servidor, visibilidad de columnas y vistas guardadas. |
| Formularios | **TanStack Form** con Zod | Esquemas Zod generados desde el OpenAPI del backend en `packages/contracts`. La validación del cliente mejora la experiencia; la autoridad es la validación del servidor. |
| Virtualización | **TanStack Virtual** | Listados largos como estados de cuenta y libros de ventas. |
| UI base | **Tailwind CSS v4** con **shadcn/ui** sobre Radix | Accesibilidad de fábrica y propiedad total del código de los componentes, sin dependencia de un proveedor. |
| Gráficos | **Recharts** | Suficiente para los indicadores del dashboard. El estándar de visualización vive en `docs/ui-ux/`. |
| Internacionalización | **i18next** con `Intl` nativo | Español de Honduras por defecto, inglés preparado. Moneda y fecha siempre por `Intl`. |

El uso de la suite TanStack es una decisión explícita del propietario y queda adoptada.
Ver `docs/adr/ADR-0006-tanstack-frontend.md`.

### 3.4 Pruebas

Detalle completo en `docs/06-estrategia-de-testing.md`.
Resumen del instrumental:

- **Backend:** JUnit, AssertJ, Testcontainers con PostgreSQL real, jqwik para pruebas de
  propiedad, PIT para pruebas de mutación, JaCoCo para cobertura, ArchUnit y las pruebas de
  Spring Modulith.
- **Frontend:** Vitest, Testing Library, MSW, Playwright y axe-core.
- **Rendimiento:** k6.

---

## 4. Estructura del repositorio

Se adopta un **monorepo** con `pnpm workspaces` y `Turborepo`. El backend es un proyecto Maven
de varios módulos dentro de `apps/api`, con un `package.json` mínimo que delega en el Maven
Wrapper para que Turborepo ordene la construcción: backend y OpenAPI, luego generación de
`packages/contracts`, luego aplicaciones web. El contrato entre backend, panel administrativo,
portal y app móvil se valida en cada commit, no en tiempo de integración.

```
confia/
├── apps/
│   ├── api/                 # Backend Java con Spring Boot. Proyecto Maven de varios módulos.
│   │                        # Un solo artefacto, tres procesos: admin, portal y worker.
│   ├── admin-web/           # Panel administrativo (React y TanStack)
│   ├── portal-web/          # Portal de encargados (React y TanStack)
│   └── mobile/              # Fase futura. Expo y React Native.
├── packages/
│   ├── contracts/           # GENERADO con orval desde el OpenAPI del backend: tipos
│   │                        # TypeScript y esquemas Zod. No se edita a mano.
│   ├── ui/                  # Sistema de diseño: componentes y tokens.
│   └── config/              # tsconfig, eslint, tailwind y vitest compartidos del frontend.
├── infra/
│   ├── docker/              # Dockerfiles y compose por entorno.
│   ├── nginx/               # Configuración de borde y cabeceras de seguridad.
│   └── scripts/             # Respaldo, restauración, rotación de secretos.
├── docs/
├── openspec/                # Artefactos del ciclo dirigido por especificaciones.
└── .claude/                 # Agentes, skills y comandos del proyecto.
```

### Arquitectura interna de la API: hexagonal y screaming

Los paquetes de primer nivel nombran **capacidades de negocio**, no capas técnicas. Quien abre
el repositorio debe leer qué hace el sistema, no qué framework usa.

`apps/api` contiene dos módulos Maven. Los nombres de directorio y el paquete base de Java se
fijan en el primer cambio de F0; en este documento `<paquete-base>` es un marcador, no un nombre
decidido.

```
apps/api/
├── pom.xml                  # POM padre: versión de Java, BOM de Spring Boot, enforcer
├── mvnw, .mvn/              # Maven Wrapper comprometido al repositorio
├── package.json             # Mínimo. Delega en el Maven Wrapper para Turborepo.
├── kernel/                  # Módulo de núcleo. Sin dependencias fuera del JDK.
│   └── src/main/java/<paquete-base>/kernel/
│                            # Money, identificadores, errores de dominio y tipos base.
│                            # Cobertura exigida 95% y mutación 80%.
└── app/                     # Módulo de aplicación Spring Boot. Depende de kernel.
    └── src/main/java/<paquete-base>/
        ├── identity/            # usuarios, roles, permisos, MFA, sesiones
        ├── organization/        # institución, año lectivo, modalidad, grado, sección
        ├── students/            # estudiantes y matrícula
        ├── guardians/           # encargados de pago y su vínculo con estudiantes
        ├── catalog/             # conceptos de pago, tarifas con vigencia, impuestos
        ├── scholarships/        # becas y descuentos
        ├── charges/             # motor de devengo y generación de cargos
        ├── ledger/              # libro mayor de doble partida. Núcleo financiero.
        ├── payments/            # registro y aplicación de pagos, abonos, pagos parciales
        ├── cashbox/             # sesiones de caja, arqueo y cierre diario
        ├── invoicing/           # facturación fiscal, CAI, correlativos, notas de crédito
        ├── collections/         # mora, promesas de pago y cobranza
        ├── reconciliation/      # conciliación bancaria
        ├── documents/           # solicitudes de constancias y justificaciones
        ├── reporting/           # reportes y exportación a CSV, Excel y PDF
        ├── notifications/       # correo, mensajería, plantillas y bitácora de envío
        ├── portal/              # capa de servicio del portal de encargados
        ├── shared/
        │   ├── audit/           # bitácora de auditoría encadenada por hash
        │   ├── security/        # configuración de Spring Security, políticas, cifrado,
        │   │                    # idempotencia
        │   └── observability/   # logs estructurados, métricas y trazas
        └── bootstrap/           # único `main` (`ConfiaApplication`, selecciona el proceso por
                                 # `APP_PROFILE`) y tres puntos de entrada en el mismo artefacto.
            ├── admin/           # `AdminApplication`: proceso administrativo
            ├── portal/          # `PortalApplication`: proceso del portal de encargados
            └── worker/          # `WorkerApplication`: proceso trabajador, sin servidor web
```

El tipo base y los errores de dominio que antes se ubicaban en `shared/kernel` viven en el módulo
Maven `kernel`. Ninguna regla de negocio vive en `kernel` ni en `shared`: las reglas de mora,
impuestos e imputación de pagos viven en el paquete `domain` del módulo de negocio que las posee.

Dentro de cada módulo de negocio:

```
payments/
├── domain/           # entidades, objetos de valor, invariantes y eventos. Sin framework.
├── application/      # casos de uso, puertos y orquestación transaccional
├── infrastructure/   # adaptadores: repositorios, clientes HTTP, trabajos en segundo plano
└── web/              # capa interface: controladores HTTP, DTO, mapeadores y permisos
                      # declarados
```

La capa conceptual `interface` vive en el paquete Java `web`, porque `interface` es palabra
reservada de Java y no puede usarse como nombre de paquete. Los paquetes de cada módulo son
`<module>.domain`, `<module>.application`, `<module>.infrastructure` y `<module>.web`. Ver
ADR-0002.

**Regla de dependencia verificada en integración continua**: `interface` (paquete `web`) depende
de `application`, que depende de `domain`. La capa `infrastructure` implementa puertos de
`application`. La capa `domain` no importa a nadie. Ningún módulo importa el `domain` de otro
módulo: la comunicación entre módulos ocurre por casos de uso públicos o por eventos de dominio.
ArchUnit verifica las reglas de capa, Spring Modulith verifica que ningún módulo acceda a los
internos de otro, y el módulo `kernel` es una frontera de compilación de Maven. El proceso del
portal no registra controladores de módulos administrativos (ADR-0003). Cada punto de entrada vive en su
subpaquete de `bootstrap`, declara con `@Import` lo que carga y no escanea componentes, así que
nada entra en un proceso sin estar escrito. Los subpaquetes no dependen entre sí, nada fuera de
`bootstrap` referencia una clase de entrada y una lista de permitidos por proceso falla cerrado
ante un bean ajeno (ADR-0024). Para el acceso a datos,
ArchUnit verifica además que jOOQ y las clases generadas solo aparezcan en `infrastructure`, que un
módulo no use las clases generadas de las tablas de otro, que ninguna transacción se abra fuera del
componente transaccional de `shared/security` y que la API de SQL plano de jOOQ solo aparezca en la
lista aprobada de clases de reporte (ADR-0015).

---

## 5. Separación entre administración y portal de encargados

El requerimiento original plantea la pregunta directamente. La respuesta corta es que el
instinto es correcto y la implementación propuesta es la forma cara y frágil de lograrlo.

Duplicar el sistema significa duplicar la base de datos, y en un sistema financiero dos bases de
datos con dinero producen una conciliación permanente que un desarrollador solo no puede
sostener. Duplicar el código significa mantener dos versiones de la misma regla de negocio y
descubrir las divergencias en producción.

### Estrategia recomendada: aislar por exposición, identidad y privilegio, no por duplicación

Se separa en cuatro dimensiones reales, manteniendo **una sola base de datos** y **un solo
código fuente**.

**Primera dimensión: proceso y red.** Un mismo artefacto de compilación se despliega como dos
procesos independientes.

| Proceso | Módulos expuestos | Exposición de red |
|---|---|---|
| `confia-api-admin` | Todos los módulos administrativos | No público. Detrás de VPN o acceso con lista de direcciones institucionales y MFA. |
| `confia-api-portal` | Únicamente el módulo de portal: consulta de estado de cuenta, inicio de pago y solicitudes | Internet abierto, tras cortafuegos de aplicación, con limitación de tasa agresiva. |

Si el proceso del portal fuera comprometido por completo, el atacante no tendría cargado en
memoria el código de facturación, de caja ni de usuarios administrativos. Esas rutas no existen
en ese proceso.

**Segunda dimensión: privilegio en la base de datos.** Esta es la frontera que realmente
importa. El proceso del portal se conecta con un rol de PostgreSQL distinto y mínimo.

- Sin permisos de borrado ni actualización sobre tablas financieras.
- Sin acceso alguno a usuarios, roles, rangos CAI, sesiones de caja ni bitácora de auditoría.
- Seguridad a nivel de fila obligatoria: cada consulta se filtra automáticamente por el
  identificador del encargado en sesión.

Un encargado no puede leer el estado de cuenta de otro estudiante aunque el código de aplicación
tenga un error, porque el motor de base de datos rechaza la consulta. Esa es la diferencia entre
confiar en que el programador no se equivoque y construir un sistema que no permite el error.
Con un solo desarrollador, solo la segunda opción es aceptable.

**Tercera dimensión: identidad.** Dos dominios de identidad disjuntos. Tablas separadas para
personal y para encargados. Claves de firma y audiencia de token distintas, de modo que un token
del portal sea criptográficamente inválido contra la API administrativa. Cookies con nombre y
dominio distintos, sin superposición de alcance. MFA obligatoria para el personal y recomendada
para encargados.

**Cuarta dimensión: origen web.** El panel vive en un subdominio administrativo y el portal en
otro. Dominios distintos, políticas de seguridad de contenido distintas y CORS con lista blanca
por aplicación. El aislamiento de origen del navegador hace el resto.

### Topología física por fase

**Proveedor de nube: AWS, bajo la política de portabilidad de
`docs/adr/ADR-0014-proveedor-de-nube-aws-y-portabilidad.md`.** La base de datos gestionada
reemplaza al host de datos autogestionado que planteaban las versiones anteriores de este
documento; el resto de las cuatro dimensiones de aislamiento (proceso y red, privilegio,
identidad, origen web) no cambia.

Fase uno (F0 a F7), con presupuesto mínimo:

```
                 Internet
                    |
        [ Cloudflare: DNS, WAF, TLS, proteccion DDoS ]
                    |
        +-----------+------------+
        |                        |
   subdominio admin        subdominio portal
   (acceso restringido      (aun no expuesto,
    por IP y MFA)            F8 lo activa)
        |                        |
   +----+------------------------+----+
   |   Instancia EC2 (subred publica) |
   |   nginx de borde                 |
   |  +------------+  +------------+  |
   |  | api-admin  |  | api-portal |  |  <- mismo artefacto, distinto perfil
   |  +-----+------+  +------+-----+  |
   |         worker, admin-web,       |
   |         portal-web, Redis        |
   +--------+----------------+--------+
            |  grupo de seguridad,   |
            |  solo trafico HTTPS    |
            |  desde Cloudflare      |
   +--------+----------------+--------+
   | RDS for PostgreSQL (subred       |
   | privada, sin IP publica)         |
   | rol administrativo / rol portal  |
   +-----------------------------------+
```

Sin acceso SSH: la administración de la instancia EC2 es por AWS Systems Manager Session
Manager. S3 reemplaza a MinIO en preproducción y producción; MinIO se mantiene únicamente en
desarrollo local (ADR-0014).

Fase dos (F8 en adelante), cuando el portal tenga tráfico real: `confia-api-portal` se muda a su
propia instancia EC2 y se agrega una réplica de lectura de RDS para sus consultas y para los
reportes pesados, con una fuente de datos de solo lectura y el rol `confia_readonly`
(ADR-0015). El nodo de escritura de RDS queda reservado para la operación administrativa y los
webhooks de la pasarela.

Regla permanente: PostgreSQL nunca tiene puerto público. Ni en desarrollo compartido, ni de
forma temporal para una migración. En AWS, RDS no admite acceso público y solo acepta conexiones
desde el grupo de seguridad de la instancia de aplicación.

---

## 6. Núcleo financiero: libro mayor de doble partida

Esta es la decisión de modelado más importante del sistema y la que separa un software
financiero serio de un CRUD de pagos.

### Principios

1. **El saldo de un estudiante nunca se almacena como campo mutable.** Se deriva del libro
   mayor. Un saldo cacheado se permite como optimización, pero se reconstruye y se verifica
   contra el libro en un trabajo nocturno de integridad.
2. **Nada se borra ni se edita, todo se reversa.** Un pago mal registrado no se corrige con una
   actualización: se emite un asiento de reverso y se registra el pago correcto. El historial
   queda completo y auditable, que es exactamente lo que un auditor externo va a pedir.
3. **Toda transacción cuadra.** La suma del debe iguala la suma del haber en cada transacción.
   Se verifica con restricciones de base de datos y con un trabajo de integridad continuo.
4. **El dinero es un objeto de valor, no un número.** Se representa con el objeto de valor
   inmutable `Money` del módulo de núcleo: un `BigDecimal` normalizado a escala cuatro más una
   moneda ISO 4217 explícita, sin constructor desde `double` ni `float`, con igualdad sobre el
   importe normalizado y la moneda, y redondeo explícito con `RoundingMode.HALF_UP`. Queda
   prohibido el tipo numérico de coma flotante para importes en cualquier capa. En PostgreSQL se
   almacena como `NUMERIC(14,4)` con una columna de moneda junto a cada importe. En la API viaja
   como `{ amount: string, currency: string }`. Las reglas de redondeo se declaran una sola vez
   en `Money` y se aplican en el servidor en el punto de emisión fiscal, de asiento y de
   presentación: los campos de presentación salen del servidor ya redondeados a la escala menor
   de la moneda (dos decimales para HNL y USD), y el navegador solo los formatea con
   `Intl.NumberFormat`. Ver ADR-0004.

### Tipos de transacción del libro mayor

| Transacción | Origen |
|---|---|
| Cargo | Devengo de colegiatura, matrícula, transporte u otro concepto |
| Descuento o beca | Aplicación de un beneficio vigente |
| Pago | Efectivo, tarjeta, transferencia o pasarela |
| Recargo por mora | Calculado por el motor de reglas |
| Nota de crédito | Documento fiscal de crédito |
| Reverso | Anulación contable de una transacción anterior |
| Ajuste | Corrección administrativa, siempre con motivo y aprobación registrada |
| Incobrable | Baja de cartera |

### Integridad y concurrencia

- **Idempotencia obligatoria** en todo endpoint que mueva dinero, mediante una cabecera de clave
  de idempotencia con índice único. Sin esto, un doble clic o el reintento de una pasarela cobra
  dos veces.
- **Bloqueo explícito** de la cuenta del estudiante al aplicar pagos.
- **Aislamiento serializable** para cierre de caja y emisión de correlativo fiscal.
- **Correlativos fiscales** asignados por una secuencia con bloqueo, nunca calculados con un
  máximo más uno, nunca reutilizados. Un hueco en el correlativo debe corresponder a una
  anulación registrada.

Ver `docs/adr/ADR-0007-libro-mayor-de-doble-partida.md` y `docs/02-modelo-de-dominio.md`.

---

## 7. Contrato de API

- **REST con OpenAPI 3.1**, generado desde el código con springdoc-openapi y publicado como
  artefacto versionado. El OpenAPI generado se compara en cada construcción contra la instantánea
  aprobada; una diferencia no declarada rompe la construcción.
- **Swagger UI y el endpoint del OpenAPI** se habilitan solo en los perfiles local y de
  preproducción. En producción quedan deshabilitados en los tres procesos; la documentación de
  producción es el artefacto versionado, no un endpoint en vivo.
- Versionado por ruta bajo `/api/v1`. Ninguna ruptura de contrato sin nueva versión.
- Errores en formato Problem Details (RFC 9457), con tipo, título, estado, detalle, instancia e
  identificador de traza. Nunca se devuelven trazas de pila ni mensajes de base de datos.
- Cabecera de idempotencia obligatoria en escrituras financieras.
- Paginación por cursor para listados grandes, con límite máximo aplicado en servidor.
- Todo importe viaja como `{ amount: string, currency: string }`, nunca como número JSON.
- El OpenAPI generado desde el backend es la fuente de verdad del contrato. `packages/contracts`
  se genera a partir de él con orval (tipos TypeScript y esquemas Zod) y no se edita a mano. El
  frontend y la futura app móvil consumen ese paquete; la construcción lo regenera y verifica los
  tipos de las aplicaciones web contra él, de modo que un cambio incompatible rompe la
  compilación del monorepo en integración continua y no en producción.

---

## 8. Observabilidad, respaldo y operación

Detalle en `docs/07-observabilidad-y-operaciones.md`.

- **Logs estructurados** en JSON, con el registro estructurado nativo de Spring Boot, e
  identificador de traza, de usuario y de solicitud. Queda prohibido registrar datos personales,
  contraseñas, tokens o números de tarjeta. La redacción se aplica por lista de campos.
- **Métricas** de latencia, tasa de error, profundidad de colas, consumo de rango CAI y
  transacciones descuadradas.
- **Trazas distribuidas** con OpenTelemetry.
- **Seguimiento de errores** con eliminación de datos personales antes del envío.
- **Alertas accionables**, no ruido: fallo de respaldo, rango CAI por agotarse, cierre de caja
  descuadrado, tasa de error superior al uno por ciento, cola atascada, certificado por vencer.
- **Respaldos en dos niveles**, según `docs/adr/ADR-0014-proveedor-de-nube-aws-y-portabilidad.md`:
  respaldo automático de RDS for PostgreSQL con restauración a un punto en el tiempo, gestionado
  por el proveedor dentro de AWS; y respaldo lógico (`pg_dump`) cifrado, nocturno, fuera de AWS,
  en un proveedor de almacenamiento distinto al de cómputo (regla tres, dos, uno). El objetivo de
  punto de recuperación de quince minutos se cumple con la restauración a un punto en el tiempo de
  RDS ante fallos normales; ante la pérdida total de la cuenta de AWS, el punto de recuperación es
  el del último respaldo lógico fuera de sitio, límite aceptado explícitamente en ese ADR. Objetivo
  de tiempo de recuperación de cuatro horas.

Un respaldo que no se ha restaurado no es un respaldo. El simulacro de restauración es mensual y
queda documentado. Una vez al año, ese simulacro se repite como simulacro de salida en un
proveedor distinto de AWS (ADR-0014).

---

## 9. Contenerización

**Decisión: todo el sistema se ejecuta en contenedores, en los tres entornos.** Local,
preproducción y producción usan la misma imagen construida una sola vez. Ver
`docs/adr/ADR-0012-contenerizacion.md`.

### Alcance

Todo componente de la aplicación corre contenerizado, en los tres entornos. Las dos excepciones
son los servicios gestionados de AWS que reemplazan a PostgreSQL y al almacenamiento de objetos
en preproducción y producción (ADR-0014), señaladas en la tabla:

| Componente | Imagen | Notas |
|---|---|---|
| `confia-api-admin` | Imagen propia, multietapa | Mismo artefacto que el portal, distinto perfil de arranque |
| `confia-api-portal` | La misma imagen | Solo cambia el punto de entrada y las variables de entorno |
| `confia-worker` | La misma imagen | Único proceso que ejecuta los trabajos en segundo plano con db-scheduler (ADR-0016): cargos, notificaciones, reportes y tareas recurrentes. Los procesos administrativo y del portal solo programan tareas |
| `admin-web` | Imagen con nginx sirviendo los archivos estáticos | Construcción estática de Vite |
| `portal-web` | La misma estrategia | Construcción estática de Vite |
| PostgreSQL | Imagen oficial fijada por versión exacta | Contenerizado solo en desarrollo local y en las pruebas de integración (Testcontainers), con volumen persistente y nunca efímero. En preproducción y producción, PostgreSQL es RDS for PostgreSQL, un servicio gestionado, no un contenedor (ADR-0014) |
| Redis | Imagen oficial fijada por versión exacta | Persistencia habilitada, contenerizado en los tres entornos |
| MinIO o equivalente | Imagen oficial | Almacenamiento de PDF fiscales y adjuntos, solo en desarrollo local. En preproducción y producción el almacenamiento es S3 (ADR-0014) |
| nginx de borde | Imagen oficial | Terminación TLS y cabeceras de seguridad |
| Observabilidad | Prometheus, Grafana, colector de OpenTelemetry | Fase posterior |

En desarrollo local se agregan un servidor de correo de prueba y una interfaz de administración
de base de datos. Ninguno de los dos existe en producción.

### Reglas de construcción

1. **Una sola imagen de aplicación para los tres procesos.** La API administrativa, la API del
   portal y el trabajador son el mismo artefacto con distinto punto de entrada. Construir tres
   imágenes distintas garantiza que tarde o temprano divergen de versión.
2. **Construcción multietapa.** Etapa de compilación con el Maven Wrapper y el JDK, y etapa final
   mínima sobre un JRE de la misma distribución de Java 25, con solo el artefacto construido. El
   consumo real de memoria de la JVM por proceso se mide en F0 (ADR-0013).
3. **Imagen base mínima y fijada por digest**, no por etiqueta móvil. Una etiqueta como `latest`
   o incluso `25-jre` cambia bajo los pies y rompe la reproducibilidad de una compilación que
   ayer funcionaba.
4. **Usuario sin privilegios de superusuario** y sistema de archivos raíz de solo lectura, con
   volúmenes temporales explícitos para lo que necesite escribir.
5. **Comprobación de salud declarada** en cada servicio, que verifique dependencias reales y no
   solo que el proceso está vivo.
6. **La imagen no contiene secretos.** Se inyectan como variables de entorno en tiempo de
   ejecución. Un secreto horneado en una capa de imagen queda ahí para siempre, aunque se borre
   en una capa posterior.
7. **Construir una vez, promover el mismo artefacto.** La imagen que pasó las pruebas en
   preproducción es exactamente la que se despliega en producción, identificada por digest.
   Nunca se reconstruye para producción.
8. **Escaneo obligatorio** de la imagen con Trivy antes de publicarla. Vulnerabilidades altas o
   críticas bloquean la publicación.

### Por qué contenedores en este proyecto

Con un solo desarrollador, el beneficio no es la escalabilidad sino la **reproducibilidad y la
recuperación**. Tres consecuencias concretas:

- El entorno de desarrollo se levanta con un solo comando, incluida la base de datos con datos
  de prueba. Eso importa cuando llegue el momento de la transferencia tecnológica al departamento
  de sistemas de la institución.
- Si el servidor se pierde por completo, la reconstrucción es levantar los contenedores y
  restaurar el respaldo, no reinstalar y reconfigurar a mano recordando qué se hizo hace un año.
- La diferencia entre "funciona en mi máquina" y producción desaparece como categoría de
  problema, que es donde un desarrollador solo pierde más horas.

### Qué queda deliberadamente fuera

**No se usa Kubernetes.** Docker Compose con un pipeline de despliegue simple es suficiente hasta
varias instituciones, y su costo operativo es proporcional al equipo disponible. Migrar a un
orquestador más adelante no requiere cambiar la aplicación, solo la capa de despliegue.

**PostgreSQL contenerizado es aceptable en desarrollo local y en pruebas, pero con condiciones.**
Volumen persistente en disco del anfitrión, versión fijada, respaldos fuera del contenedor y
procedimiento de actualización mayor documentado (ADR-0012). En preproducción y producción,
PostgreSQL corre como RDS for PostgreSQL, un servicio gestionado por AWS que traslada el
respaldo, el parcheo y la restauración a un punto en el tiempo al proveedor, bajo la política de
portabilidad de ADR-0014: la aplicación se conecta por el protocolo estándar de PostgreSQL y no
sabe que la base es gestionada.

---

## 10. Qué no se construye ahora y por qué

- **Microservicios**: no hay razón de escala ni de equipo. Ver sección dos.
- **Kubernetes**: costo operativo desproporcionado. Ver seccion 9. Docker Compose con un pipeline
  simple alcanza para varias instituciones.
- **Facturación electrónica**: fuera del alcance actual, pero el puerto de emisión de documentos
  fiscales se define desde el inicio con un adaptador para el régimen CAI, de modo que migrar
  sea agregar un adaptador y no reescribir el módulo.
- **Multi-tenencia activa**: se despliega para una institución, pero el esquema incluye el
  identificador de institución desde la primera migración. Agregarlo después obliga a reescribir
  cada consulta y cada índice del sistema. Ver `docs/adr/ADR-0009-multitenencia.md`.
- **App móvil**: fase posterior, pero el contrato de API y el paquete de contratos se diseñan hoy
  para soportarla sin refactorización.

---

## 11. Documentos relacionados

| Documento | Contenido |
|---|---|
| `docs/00-vision-y-alcance.md` | Objetivos, alcance, fuera de alcance y criterios de éxito |
| `docs/02-modelo-de-dominio.md` | Entidades, relaciones, invariantes y lenguaje ubicuo |
| `docs/03-seguridad.md` | Modelo de amenazas y controles |
| `docs/04-cumplimiento-fiscal-sar.md` | CAI, correlativos, impuesto sobre ventas y notas de crédito |
| `docs/05-infraestructura-y-despliegue.md` | Entornos, contenedores, integración continua y endurecimiento |
| `docs/06-estrategia-de-testing.md` | Niveles de prueba, umbrales y puertas de calidad |
| `docs/07-observabilidad-y-operaciones.md` | Logs, métricas, alertas, respaldos y runbooks |
| `docs/08-datos-privacidad-y-retencion.md` | Datos de menores, consentimiento y retención |
| `docs/09-roadmap-y-fases.md` | Plan de entrega por fases |
| `docs/10-analisis-de-brechas.md` | Módulos faltantes en el requerimiento original |
| `docs/11-riesgos.md` | Registro de riesgos y mitigaciones |
| `docs/13-metodologia-sdd.md` | Ciclo de desarrollo dirigido por especificaciones |
| `docs/ui-ux/` | Sistema de diseño, accesibilidad y patrones de interacción |
| `docs/adr/` | Registros de decisión de arquitectura |
