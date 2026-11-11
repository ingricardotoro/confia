# CONFIA — Instrucciones de proyecto

Sistema de Control Financiero Académico. Gestión de pagos estudiantiles, facturación fiscal y
cobranza para instituciones educativas. Régimen fiscal inicial: Honduras (SAR / CAI).

**Antes de escribir cualquier código, lee `docs/01-arquitectura.md`.** Es la fuente de verdad.
Si algo en este archivo contradice ese documento, gana el documento de arquitectura.

---

## Reglas no negociables

Estas reglas existen porque el sistema maneja dinero real y datos de menores de edad. Violarlas
no es un problema de estilo: es un defecto.

### Dinero

1. **Nunca uses `double` ni `float` para un importe en Java, ni aritmética con `number` de
   JavaScript sobre un importe en el frontend.** Se usa el objeto de valor `Money` del módulo de
   núcleo del backend: `BigDecimal` normalizado a escala cuatro, moneda ISO 4217 explícita, sin
   constructor desde `double` ni `float`, igualdad sobre importe normalizado y moneda (nunca
   `BigDecimal.equals` sin normalizar) y redondeo explícito con `RoundingMode.HALF_UP`. En la API
   viaja como `{ amount: string, currency: string }`.
2. **Nunca almacenes dinero como `float` o `double`.** En PostgreSQL siempre `NUMERIC(14,4)`, con
   una columna de moneda junto a cada importe.
3. **Toda aritmética monetaria vive en el backend.** `Money` y sus operaciones viven en el módulo
   de núcleo; las reglas de negocio (mora, impuestos, imputación de pagos) viven en el paquete
   `domain` del módulo que las posee. No en controladores, no en servicios de aplicación, no en
   componentes de React. Los importes de presentación los redondea el servidor a la escala menor
   de la moneda (dos decimales para HNL y USD) con `HALF_UP`; el navegador solo los formatea. Un
   cálculo de dinero fuera de ese lugar se rechaza en revisión.
4. **El saldo se deriva del libro mayor, nunca se almacena como campo mutable.**
5. **Nada financiero se borra ni se edita.** Se reversa con un asiento nuevo. Sin excepciones.

### Escrituras financieras

6. Todo endpoint que mueva dinero exige cabecera `Idempotency-Key` y la respeta.
7. Toda operación que afecte el libro mayor ocurre dentro de una transacción de base de datos
   explícita, con bloqueo sobre la cuenta afectada.
8. Los correlativos fiscales se obtienen de una secuencia con bloqueo. Jamás con `MAX(...) + 1`.

### Seguridad

9. **Nunca confíes en el cliente.** Toda autorización se decide en el servidor, siempre.
10. Toda entrada se valida en el borde del backend con Jakarta Bean Validation. El frontend valida
    además con los esquemas Zod generados en `packages/contracts`, que nunca sustituyen la
    validación del servidor. Toda salida pasa por un DTO explícito. Nunca se serializa una entidad
    de base de datos directamente hacia el cliente.
11. **Nunca registres en logs** contraseñas, tokens, cabeceras de autorización, números de
    documento de identidad, números de tarjeta ni datos de menores.
12. Nunca uses concatenación de cadenas para construir SQL. Siempre consultas parametrizadas.
13. Secretos jamás en el repositorio. Ni en ejemplos, ni en pruebas, ni en comentarios.
14. Toda acción sensible escribe en la bitácora de auditoría. Auditar no es opcional.

### Datos personales

15. El sistema procesa datos de menores. Minimiza lo que recolectas, cifra lo sensible, y respeta
    la política de retención de `docs/08-datos-privacidad-y-retencion.md`.

---

## Idioma de los artefactos

- **Código, identificadores, comentarios técnicos, nombres de rama y mensajes de commit: inglés.**
- **Documentación de negocio, especificaciones y manuales de usuario: español neutro profesional.**
- **Texto de interfaz: se define en catálogos de internacionalización.** Nunca cadenas embebidas
  en componentes. Español de Honduras por defecto.
- La conversación con el propietario ocurre en español. Eso no cambia el idioma de los artefactos.

---

## Stack

El backend es Java y el frontend es TypeScript (ADR-0013). Detalle en `docs/01-arquitectura.md`,
sección 3.

| Capa | Tecnología |
|---|---|
| Monorepo | pnpm workspaces + Turborepo; el backend es un proyecto Maven dentro de `apps/api` |
| Backend | Java 25 LTS (distribución OpenJDK, por ejemplo Eclipse Temurin), Spring Boot 4.1, Maven con Maven Wrapper |
| Modularidad | Spring Modulith 2 y ArchUnit |
| Datos | PostgreSQL 18 en todos los entornos (ADR-0015), migraciones con Flyway, Redis para caché y limitación de tasa |
| Acceso a datos | jOOQ de código abierto, con código generado desde las migraciones y confinado a `infrastructure` (ADR-0015). Sin JPA, Hibernate ni Spring Data. Un único componente transaccional en `shared/security` abre las transacciones |
| Trabajos en segundo plano | db-scheduler sobre PostgreSQL (ADR-0016), sin intermediario de mensajes ni colas en Redis. Las tareas se programan dentro de la transacción del negocio y solo `confia-worker` las ejecuta. Prohibidos `@Scheduled`, `@EnableScheduling`, `@Async` y cualquier otra biblioteca de programación |
| Seguridad | Spring Security |
| Contrato de API | OpenAPI 3.1 generado con springdoc-openapi; Swagger UI solo en local y preproducción |
| Frontend | React 19, Vite, TanStack Router/Query/Table/Form/Virtual |
| UI | Tailwind CSS v4, shadcn/ui sobre Radix |
| Catálogo de componentes | Storybook 10 (`@storybook/react-vite`) en `packages/ui`, con historias portables; es también el banco de la puerta de accesibilidad (ADR-0026) |
| Validación | Jakarta Bean Validation en el backend; Zod en el frontend, generado con orval en `packages/contracts` |
| Pruebas del backend | JUnit, AssertJ, Testcontainers, jqwik, PIT, JaCoCo, ArchUnit, pruebas de Spring Modulith |
| Pruebas del frontend | Vitest, Testing Library, MSW, Playwright, axe-core; la puerta de axe corre en Chromium con un proyecto de Vitest en modo navegador (`pnpm --filter @confia/ui test:a11y`, ADR-0026); desde 2026-11-07 también una puerta de axe sobre rutas renderizadas con Playwright y `@axe-core/playwright` (ADR-0028, Propuesto) |
| Rendimiento | k6 |
| Observabilidad | Registro estructurado nativo de Spring Boot en JSON, OpenTelemetry, Prometheus, Grafana |

---

## Estructura y reglas de dependencia

```
apps/api/                          # proyecto Maven de varios módulos
├── kernel/                        # Money, identificadores, errores de dominio y tipos base.
│                                  # Sin dependencias fuera del JDK.
└── app/                           # aplicación Spring Boot. Tres puntos de entrada en el mismo
    └── <paquete-base>/            # artefacto: administrativo, portal y trabajador.
        └── <capacidad>/
            ├── domain/            # sin framework, sin entrada/salida
            ├── application/       # casos de uso y puertos
            ├── infrastructure/    # adaptadores
            └── web/               # capa interface: controladores y DTO
```

`<paquete-base>` y los nombres de los módulos Maven se fijan en F0. La capa conceptual `interface`
vive en el paquete Java `web`, porque `interface` es palabra reservada de Java.

- `interface` (paquete `web`) → `application` → `domain`
- `infrastructure` implementa puertos de `application`
- `domain` no importa de nadie; el módulo `kernel` no depende de nada fuera del JDK
- Ningún módulo importa el `domain` de otro módulo. La comunicación entre módulos ocurre por
  casos de uso públicos o por eventos de dominio.

Las reglas se verifican en integración continua: ArchUnit y Spring Modulith en el backend, y
`dependency-cruiser` y ESLint en el frontend. Una violación rompe la construcción.

Los nombres de carpeta y de paquete de primer nivel describen **capacidades de negocio**, no capas
técnicas. Alguien que abre el repositorio debe leer qué hace el sistema.

`packages/contracts` es código generado desde el OpenAPI del backend. No se edita a mano.

---

## Frontend

- Estado de servidor con TanStack Query. **No dupliques datos de servidor en estado global.**
- Tablas con TanStack Table y paginación, orden y filtrado **en el servidor**. Nunca traigas el
  conjunto completo para filtrarlo en el navegador.
- Componentes bajo el patrón contenedor y presentación. La presentación no hace peticiones.
- Diseño atómico en `packages/ui`: átomos, moléculas, organismos, plantillas.
- Todo componente define de forma explícita sus estados de carga, vacío, error y éxito.
- Los importes se muestran con numeración tabular, alineados a la derecha, con moneda y siempre
  con dos decimales, tal como los entrega ya redondeados el servidor. El formato viene de
  `Intl.NumberFormat`, nunca de concatenación manual. El navegador no redondea ni calcula
  importes.
- Accesibilidad nivel AA es criterio de aceptación, no una mejora posterior.

Lee `docs/ui-ux/` antes de construir cualquier pantalla.

---

## Pruebas

- Cobertura global mínima del ochenta por ciento. El módulo de núcleo del backend y el paquete
  `domain` de cada módulo exigen noventa y cinco, medida con JaCoCo.
- Pruebas de mutación con PIT sobre el módulo de núcleo y el paquete `domain` de cada módulo,
  umbral mínimo de ochenta.
- Toda regla financiera necesita una prueba unitaria con casos límite explícitos: cero,
  negativo, redondeo, moneda distinta y concurrencia.
- Toda política de seguridad a nivel de fila necesita una prueba de integración que demuestre
  que un usuario no puede leer datos de otro.
- Los flujos críticos tienen prueba de extremo a extremo: inicio de sesión con MFA, registro de
  pago, emisión de factura, anulación, cierre de caja y consulta del portal.

Detalle en `docs/06-estrategia-de-testing.md`.

---

## Metodología: cortes verticales ágiles (ODD ligero)

Se abandona el ciclo SDD completo (`docs/13-metodologia-sdd.md` queda como histórico; no se crean
más cambios en `openspec/`). La unidad de entrega es un **corte vertical**: una capacidad de
negocio terminada de punta a punta (migración, backend, contrato, pantalla, pruebas) que se puede
demostrar al propietario.

```
nota de corte (≤ 1 página) → backend → contrato → frontend → verificar → PR → demo
```

1. **Nota de corte.** Un archivo `docs/cortes/<modulo>-<corte>.md` de una página: objetivo,
   reglas e invariantes que aplican, endpoints, pantallas, casos límite y criterio de aceptación.
   Sin diseño formal ni lista de tareas aparte. Si la regla es financiera o fiscal, se cita el
   invariante de `docs/02-modelo-de-dominio.md`.
2. **Un corte = una rama = un PR** de hasta unos pocos días de trabajo. Un módulo se entrega en
   dos a cuatro cortes; el primero es siempre el más delgado que ya muestre algo funcionando
   (listar y crear), y los siguientes agregan reglas.
3. **Pruebas dentro del corte, proporcionales al riesgo.** Reglas financieras, de seguridad y de
   aislamiento por institución: prueba primero y casos límite (obligatorio). CRUD simple: pruebas
   de integración del endpoint y de componente de la pantalla. No se exigen compuertas de mutación
   ni verificaciones formales por corte; PIT y la cobertura global corren en integración continua y
   se revisan por fase, no por PR.
4. **Revisión ligera.** Cada PR pasa por `confia-code-reviewer`; solo los que tocan dinero,
   autenticación, permisos o migraciones sobre datos existentes pasan además por
   `confia-security-auditor`.
5. **Demo al cierre de cada corte** y una línea de avance en `docs/09-roadmap-y-fases.md`
   (casilla marcada con el PR que la cierra). Es el único registro de progreso obligatorio.

Las reglas no negociables y la arquitectura no se relajan: lo que se simplifica es el proceso, no
la calidad del dinero ni de la seguridad.

---

## Definición de terminado (por corte)

- [ ] Backend y frontend del corte funcionan juntos (demostrable en local)
- [ ] El contrato OpenAPI y `packages/contracts` están regenerados
- [ ] Pasan tipos, análisis estático, formato y las pruebas del corte
- [ ] Reglas de dependencia entre capas respetadas (ArchUnit, dependency-cruiser)
- [ ] Estados de carga, vacío, error y éxito implementados; sin violaciones críticas de axe
- [ ] Acciones sensibles auditadas; permisos y aislamiento por institución probados
- [ ] Nota de corte actualizada con lo que realmente se construyó

---

## Convenciones de Git

- Commits convencionales. Sin atribución de herramientas de IA.
- Una rama por corte vertical, nombrada `feat/<modulo>-<corte>`.
- Los pull requests que superen ochocientas líneas de cambio efectivo se dividen en unidades
  revisables (`docs/15-flujo-de-trabajo-git.md` §3).
- Las migraciones de base de datos se revisan con especial cuidado y nunca se editan una vez
  aplicadas en producción.

---

## Agentes disponibles

Los agentes especializados del proyecto están en `.claude/agents/`. Las skills reutilizables
están en `.claude/skills/`. Consulta `docs/12-agentes-y-herramientas.md` para saber cuál usar en
cada situación.
