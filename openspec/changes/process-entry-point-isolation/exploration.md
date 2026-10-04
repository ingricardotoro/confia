# Exploración: aislamiento de los puntos de entrada por proceso (F0, cambio 15)

- **Cambio:** `process-entry-point-isolation`
- **Fase:** explorar
- **Fecha:** 2026-10-03
- **Estado:** exploración terminada, pendiente de la propuesta
- **Origen:** hallazgo de `frontend-monorepo-and-contracts-pipeline`, corte 3a
  (`apply-progress.md`, tarea 1.3). `foundations-plan/exploration.md`, fila 15, exige que este
  cambio se fusione **antes** de `session-tokens-and-web-layer`, el primer cambio que registra un
  módulo de negocio en un punto de entrada. `docs/09-roadmap-y-fases.md` repite la condición.

## Estado actual

- **Puntos de entrada.** `AdminApplication`, `PortalApplication` y `WorkerApplication` viven en
  `com.confia.bootstrap`, con visibilidad de paquete. Las tres llevan
  `@SpringBootApplication(scanBasePackages = "com.confia.bootstrap", exclude =
  DataSourceAutoConfiguration.class)`. Administración y portal importan además
  `ContractSchemas` y `ProcessApiInfo`; el trabajador no importa nada.
  `ConfiaApplication.launch` elige la clase según `APP_PROFILE` y arranca el trabajador con
  `WebApplicationType.NONE`.
- **La fuga ya existe.** `@SpringBootApplication` es a su vez una `@Configuration`, y por tanto un
  componente. Cada punto de entrada escanea `com.confia.bootstrap` y encuentra a los otros dos: el
  contexto del portal registra `AdminApplication` y `WorkerApplication`, y el del trabajador
  recibe `ContractSchemas` y `ProcessApiInfo` a través del `@Import` de las otras clases. El
  Javadoc de `ProcessApiInfo` ya advierte del riesgo. **Este diagnóstico se dedujo de la semántica
  de Spring y de la lectura del código, sin ejecutarlo.** La primera prueba en rojo del cambio debe
  demostrarlo antes de tocar código de producción.
- **Aún no hay beans de negocio.** `identity`, `organization` y `shared` no llevan anotaciones de
  Spring; los repositorios de jOOQ se construyen por constructor. No hay `DSLContext` de
  producción, ni db-scheduler, ni capa web de negocio. Nadie escanea `IntegrationTestApplication`
  (`com.confia.support`).
- **Restricciones vigentes.**
  - Spring Modulith 2 trata `bootstrap`, `identity`, `organization` y `shared` como módulos. Un
    punto de entrada que importe internos de otro módulo rompe `SpringModulithVerificationTest`.
    `shared.web.openapi` se expone como `@NamedInterface` según ADR-0022, que exige un consumidor
    real.
  - `LayeredArchitectureTest` solo cubre `..domain..`, `..application..`, `..infrastructure..` y
    `..web..`. Ninguna regla de ArchUnit menciona `bootstrap`.
  - La verificación 1 de ADR-0003 y la skill `confia-testing-playbook` ya exigen una prueba sobre
    el contexto del portal.
  - ADR-0003 dice que el trabajador corre «con el perfil administrativo», lo que es ambiguo para el
    grafo de beans.
- **Empaquetado.** No hay `spring-boot-maven-plugin`, ni `mainClass`, ni Dockerfile; llegan con
  el cambio 11. `.github/workflows/ci.yml` solo ejecuta `./mvnw verify`. `ConfiaApplication`
  sigue siendo el único `main`, así que este cambio no condiciona el empaquetado.

## Áreas afectadas

- `apps/api/app/src/main/java/com/confia/bootstrap/{Admin,Portal,Worker}Application.java`: se
  mueven a subpaquetes y pasan a ser públicas.
- `apps/api/app/src/main/java/com/confia/bootstrap/ConfiaApplication.java`: importaciones.
- `apps/api/app/src/main/java/com/confia/shared/web/openapi/{package-info,ProcessApiInfo}.java`:
  el Javadoc que justifica el diseño actual queda obsoleto.
- `apps/api/app/src/test/java/com/confia/bootstrap/`: nueva prueba de aislamiento de contexto por
  proceso; `ConfiaApplicationTest` y `OpenApiProcess` son el patrón a reutilizar.
- `apps/api/app/src/test/java/com/confia/architecture/`: nueva regla de slices con su fixture
  permanente.
- `docs/adr/`: ADR-0024, que fija el mecanismo.
- `docs/01-arquitectura.md`, `docs/09-roadmap-y-fases.md` y
  `.claude/skills/confia-module-scaffold/SKILL.md` §4.
- `openspec/specs/build-integrity/spec.md`: candidato natural para el delta de requisitos.

## Enfoques

| # | Enfoque | Ventajas | Desventajas | Esfuerzo |
|---|---|---|---|---|
| a | Subpaquetes `bootstrap.admin`, `bootstrap.portal` y `bootstrap.worker`, cada uno con su propio `scanBasePackages` | Cambio mínimo; corta la fuga cruzada | Sigue descubriendo por escaneo; solo la prueba impide volver a ampliarlo | Bajo |
| b | `@Import` explícito sin escaneo (`@SpringBootConfiguration` + `@EnableAutoConfiguration` + `@Import`); cada módulo expone una configuración pública | Coincide con ADR-0003 («declara de forma explícita qué módulos carga»); falla cerrado | Cada módulo necesita una API pública (Modulith, ADR-0022); costo real al registrar módulos | Medio |
| c | Condicionar por `AppProfile`, `@Profile` o `@ConditionalOnProperty` | Sin reestructurar paquetes | Falla abierto; `APP_PROFILE` no es un perfil de Spring; contradice ADR-0003. **Descartado** | Bajo, garantía más débil |
| d | **Recomendado.** Híbrido acotado de (a) y (b): subpaquetes más `@Import` explícito de lo que existe hoy, sin escaneo implícito | Garantía estructural más una prueba que falla cerrado; la forma de lista es la que necesita `session-tokens-and-web-layer` | El requisito de configuración pública por módulo solo se documenta, no se construye | Bajo a medio |

## Diseño de la prueba

Vive en el paquete de pruebas `com.confia.bootstrap`, arranca los procesos con
`ConfiaApplication.launch`, no necesita base de datos y se nombra `*Test`.

1. **Lista de permitidos por proceso.** Todo bean bajo `com.confia.*` debe pertenecer a los
   paquetes permitidos de ese proceso. Una lista de permitidos falla cerrado: un módulo nuevo
   obliga a editarla y la edición se ve en revisión.
2. **Prohibidos nominales para el portal.** Ningún bean de `bootstrap.admin` ni de
   `bootstrap.worker`, ni de `invoicing`, `cashbox`, `reconciliation`, ni de `identity` mientras
   sea solo para personal.
3. **db-scheduler (`com.github.kagkarlsson`) ausente en administración y portal.** Hoy es vacuo
   porque la biblioteca no está en el classpath; se anota de forma explícita (ADR-0018) y la
   aserción positiva del trabajador queda para el cambio 9.
4. **Prueba negativa permanente.** Un contexto con fuga deliberada debe ser detectado por el
   inspector. La evidencia en rojo es el paso 1 contra el estado actual.
5. **ArchUnit.** `slices().matching("com.confia.bootstrap.(*)..").should().notDependOnEachOther()`
   con fixture permanente, según la convención de dos pruebas del repositorio. Además, una regla
   que impida que algo fuera de `bootstrap` referencie las clases de entrada, ahora públicas.

## Recomendación

Enfoque (d). Mover los tres puntos de entrada a `bootstrap.admin`, `bootstrap.portal` y
`bootstrap.worker` como clases públicas; sustituir `@SpringBootApplication` por
`@SpringBootConfiguration`, `@EnableAutoConfiguration` y un `@Import` explícito, conservando la
exclusión de `DataSourceAutoConfiguration`. Agregar la prueba de contexto, la regla de slices,
ADR-0024 y las actualizaciones de documentación. **No registrar ningún módulo de negocio en este
cambio.**

### Estimación

- Unas 400 líneas autorales cambiadas, en el límite del presupuesto de revisión. El propietario
  eligió PR único, así que el ADR debe ser breve y los fixtures mínimos.
- Desglose: clases movidas y editadas ~70; `ConfiaApplication` y Javadoc ~20; prueba de contexto
  e inspector ~150; regla de ArchUnit y fixtures ~70; ADR y documentación ~90.
- Entre 7 y 8 tareas, lejos del límite de 15: prueba en rojo contra el estado actual; mover y
  reformular los puntos de entrada hasta verde; prueba negativa del inspector; regla de ArchUnit
  y fixture; ADR-0024; documentación, skill y Javadoc; `./mvnw verify` completo y medición del
  diff.
- Si el diff medido se desborda, el corte natural es prueba de contexto más movimiento de clases,
  y luego ArchUnit más documentación. Eso exigiría cambiar la estrategia de entrega.

## Riesgos

- Un módulo futuro debe exponer una configuración pública (interfaz nombrada o clase en el
  paquete base) para que un punto de entrada lo importe. ADR-0022 exige consumidor real, así que
  este cambio solo lo documenta; ADR-0024 debe citarlo para que `session-tokens-and-web-layer` no
  lo descubra tarde.
- La aserción sobre db-scheduler es vacua hasta el cambio 9.
- Hacer públicas las clases de entrada amplía su visibilidad; la regla de ArchUnit lo cubre.
- El diagnóstico de la fuga no se ejecutó: la prueba en rojo de la primera tarea es obligatoria.
- El presupuesto de revisión está en su límite.
- ADR-0024 debe aclarar qué significa para el grafo de beans que el trabajador corra «con el
  perfil administrativo».

## Listo para la propuesta

Sí, sin preguntas de producto abiertas. La propuesta debe fijar: el enfoque (d) con (c)
descartado; ningún módulo de negocio registrado en este cambio; la lista de permitidos como forma
de la prueba; ADR-0024 como entregable; y la redacción de ADR-0003 sobre el trabajador.
