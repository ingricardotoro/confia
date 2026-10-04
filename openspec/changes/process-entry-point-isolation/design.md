# Diseño: aislamiento de los puntos de entrada por proceso

- **Cambio:** `process-entry-point-isolation` (F0, cambio 15)
- **Fase:** diseñar
- **Fecha:** 2026-10-03
- **Estado:** aprobado por el propietario del producto el 2026-10-03
- **Entradas:** `proposal.md` (aprobada el 2026-10-03), `exploration.md` y
  `specs/build-integrity/spec.md` (delta, aprobado el 2026-10-03)
- **Modo de TDD:** estricto. Ejecutor: `./mvnw verify` en `apps/api`, con JDK 25 y Docker.

## 1. Enfoque técnico

Se adopta el enfoque (d) de la propuesta: **cada proceso tiene su propio subpaquete de
`com.confia.bootstrap` y declara con `@Import` todo lo que carga, sin escaneo de componentes.** La
garantía tiene dos capas independientes:

1. **Estructural.** Sin `@ComponentScan`, un contexto solo contiene su clase de entrada, lo que
   esa clase importa y lo que aporta la autoconfiguración de Spring Boot. Ninguna clase de
   `com.confia` entra en un contexto sin aparecer escrita en un `@Import`.
2. **Verificada.** Una prueba arranca los tres procesos por `ConfiaApplication.launch` y compara
   cada bean de `com.confia` contra una lista de permitidos por proceso que falla cerrado, más una
   lista nominal de prohibidos. Tres reglas de ArchUnit impiden que la estructura se degrade: los
   subpaquetes de `bootstrap` no dependen entre sí, nada fuera de `bootstrap` referencia una clase de
   entrada y ningún punto de entrada vuelve a escanear.

```
APP_PROFILE ─► ConfiaApplication.launch (com.confia.bootstrap, único main)
                 ├─ admin  ─► bootstrap.admin.AdminApplication   ─@Import─► ContractSchemas, ProcessApiInfo
                 ├─ portal ─► bootstrap.portal.PortalApplication ─@Import─► ContractSchemas, ProcessApiInfo
                 └─ worker ─► bootstrap.worker.WorkerApplication (sin @Import, sin servidor web)

 cada contexto ──► ProcessBeanInspector ──► ProcessBeanPolicy del proceso
                                              (permitidos: falla cerrado; prohibidos: nominal)
```

Ningún módulo de negocio se registra en este cambio. La forma de lista de `@Import` es la que
`session-tokens-and-web-layer` extenderá con la configuración pública de cada módulo (ADR-0024).

## 2. Evidencia obtenida en esta fase

Leída en el árbol de la rama `change/process-entry-point-isolation` @ `6838892`, no supuesta:

- `AdminApplication`, `PortalApplication` y `WorkerApplication` viven en `com.confia.bootstrap`,
  con visibilidad de paquete, y las tres declaran `@SpringBootApplication(scanBasePackages =
  "com.confia.bootstrap", exclude = DataSourceAutoConfiguration.class)`. Administración y portal
  añaden `@Import({ContractSchemas.class, ProcessApiInfo.class})`.
- `@SpringBootApplication` es meta-anotación de `@SpringBootConfiguration`, que lo es de
  `@Configuration` y por tanto de `@Component`. Los filtros de exclusión que trae
  (`TypeExcludeFilter` y `AutoConfigurationExcludeFilter`) no excluyen una clase de entrada. Por
  eso se predice que cada contexto contiene **tres** clases de entrada y que el trabajador recibe
  `ContractSchemas` y `ProcessApiInfo`. La predicción no se ha ejecutado: la tarea 1 la prueba.
- `IntegrationTestApplication` (`com.confia.support`, pruebas) ya usa exactamente
  `@SpringBootConfiguration` + `@EnableAutoConfiguration`, sin escaneo. Es el precedente del
  repositorio para la forma elegida.
- `SpringModulithVerificationTest` y ADR-0022 (sonda S1) confirman que `ApplicationModules.of
  ("com.confia", …)` trata como módulo **cada subpaquete directo** de `com.confia`. No hay ningún
  `@ApplicationModule` en `bootstrap`, así que `bootstrap.admin`, `bootstrap.portal` y
  `bootstrap.worker` son paquetes internos del módulo `bootstrap`, no módulos nuevos.
  `ConfiaApplication`, en el paquete base de ese módulo, puede referenciarlos sin violación.
- `ArchitectureTestSupport.assertRuleRejects` y `archRule.failOnEmptyShould=true` (ADR-0018) fijan
  la convención de dos mitades: producción en verde y fixture rechazado con fragmentos de mensaje
  que nombran la violación deliberada.
- ArchUnit resuelve por defecto las clases ausentes desde el camino de clases
  (`archunit.properties` no lo desactiva), así que las meta-anotaciones de Spring se resuelven al
  evaluar `beMetaAnnotatedWith(ComponentScan.class)`.
- Las referencias a las clases de entrada fuera de `bootstrap` son solo Javadoc
  (`ProcessApiInfo`, `shared/web/openapi/package-info.java`, `TransactionRunner`,
  `JooqInstitutionRepository`, `ResolveCurrentInstitution`, `IntegrationTestApplication`). Javadoc no
  genera dependencias de bytecode, así que la regla de ArchUnit de la decisión 6 pasa sobre producción
  sin tocar código.

## 3. Decisiones de arquitectura

### Decisión 1 — Anotaciones exactas de cada clase de entrada

```java
package com.confia.bootstrap.admin;

@SpringBootConfiguration
@EnableAutoConfiguration(exclude = DataSourceAutoConfiguration.class)
@Import({ContractSchemas.class, ProcessApiInfo.class})
public class AdminApplication {
}
```

`PortalApplication` es idéntica en `com.confia.bootstrap.portal`. `WorkerApplication`, en
`com.confia.bootstrap.worker`, lleva las dos primeras anotaciones y **ningún** `@Import`.

- **Públicas y no `final`.** Públicas porque `ConfiaApplication` vive en otro paquete. No `final`
  porque `@SpringBootConfiguration` conserva `proxyBeanMethods = true` por omisión y Spring
  extiende la clase con CGLIB. Se mantiene el valor por omisión: es el comportamiento actual, y hace
  que la prueba real ejerza el tratamiento de proxies del inspector (decisión 4).
- **Nada más hace falta en Spring Boot 4.1.** `@SpringBootApplication` es exactamente
  `@SpringBootConfiguration` + `@EnableAutoConfiguration` + `@ComponentScan` con
  `TypeExcludeFilter` y `AutoConfigurationExcludeFilter`. Al quitar el escaneo, esos dos filtros
  dejan de tener objeto. `@ConfigurationPropertiesScan` no forma parte de `@SpringBootApplication`
  desde Spring Boot 2.2.1 y el código no tiene clases `@ConfigurationProperties`, así que su
  ausencia no cambia nada.
- **`@AutoConfigurationPackage` cambia de valor.** `@EnableAutoConfiguration` registra como paquete
  de autoconfiguración el de la clase anotada: pasa de `com.confia.bootstrap` a
  `com.confia.bootstrap.<proceso>`. Hoy no lo consume nadie: no hay JPA ni Spring Data, y la
  autoconfiguración de jOOQ no existe en Spring Boot 4.1 (`IntegrationTestApplication`). La
  instantánea del OpenAPI y `ConfiaApplicationTest` lo confirman al quedar en verde.
- La exclusión de `DataSourceAutoConfiguration` se conserva con su justificación actual y la
  retira el primer cambio que declare un `DataSource` de producción.

**Descartado:** `@SpringBootApplication(scanBasePackages = "com.confia.bootstrap.<proceso>")`
(enfoque a). Corta la fuga actual, pero sigue descubriendo por escaneo: cualquier clase anotada que
alguien deje en ese subpaquete entra sin pasar por un `@Import` visible. **Descartado también:**
`@SpringBootConfiguration(proxyBeanMethods = false)`. Ahorraría el proxy, pero las clases no
declaran métodos `@Bean` y la prueba perdería su único caso real de clase CGLIB.

### Decisión 2 — `ConfiaApplication` sigue siendo el único `main` y solo cambia sus importaciones

`ConfiaApplication`, `AppProfile`, `UnknownAppProfileException` y `LaunchOutcome` se quedan en
`com.confia.bootstrap`. `ConfiaApplication` importa
`com.confia.bootstrap.admin.AdminApplication`, `…portal.PortalApplication` y
`…worker.WorkerApplication`. La selección por `APP_PROFILE`, la propiedad de título del OpenAPI y
`WebApplicationType.NONE` del trabajador no cambian. El diagrama del Javadoc se actualiza con los
nombres calificados por subpaquete.

**Por qué:** el paquete base del módulo `bootstrap` es su única API por omisión en Spring Modulith y
el único punto que conoce los tres procesos. Mover el lanzador a un subpaquete obligaría a que un
subpaquete dependiera de los otros dos, justo lo que prohíbe la decisión 6.

### Decisión 3 — Los subpaquetes quedan dentro del único módulo de Spring Modulith `bootstrap`

No se añade `@ApplicationModule` ni `package-info.java` a los subpaquetes. Spring Modulith 2 solo
trata como módulo anidado un subpaquete anotado de forma explícita. Sin la anotación, los tres son
internos de `bootstrap`, y la separación entre ellos la vigila ArchUnit (decisión 6), que es más
precisa para este caso: Spring Modulith no prohíbe que un paquete interno use otro paquete interno
del mismo módulo.

**Descartado:** declarar `bootstrap.admin`, `bootstrap.portal` y `bootstrap.worker` como módulos
anidados. Añade tres `package-info.java` y una semántica de API por omisión que nadie consume, a
cambio de una garantía que la regla de slices ya da con menos piezas.

### Decisión 4 — Forma del inspector de contexto

Dos clases de soporte de prueba en `com.confia.bootstrap` (pruebas), sin dependencias nuevas:

- **`ProcessBeanPolicy`** (`record`): nombre del proceso, paquete de su punto de entrada, paquetes
  permitidos y paquetes prohibidos con su motivo. Tres constantes: `ADMIN`, `PORTAL` y `WORKER`.
- **`ProcessBeanInspector`**: recorre `ConfigurableListableBeanFactory.getBeanNamesIterator()`, que
  incluye las definiciones y los singletons registrados a mano, y calcula los **paquetes de origen**
  de cada bean:
  1. el paquete de `ClassUtils.getUserClass(beanFactory.getType(name, false))`. `getUserClass`
     quita la subclase CGLIB (`AdminApplication$$SpringCGLIB$$0`). `allowFactoryBeanInit = false`
     evita inicializar un `FactoryBean` solo para inspeccionarlo;
  2. si existe definición, el paquete de `getBeanClassName()` de la definición fusionada (cubre un
     método `@Bean` estático y la clase declarada aunque el tipo efectivo sea un proxy de JDK);
  3. si la definición tiene `getFactoryBeanName()`, el paquete de la clase de usuario de ese bean
     fábrica (cubre `@Bean DSLContext` declarado en una configuración de `com.confia`, cuyo tipo es
     de `org.jooq`).

  Un bean es **de CONFIA** si alguno de sus paquetes de origen está bajo `com.confia`. Las reglas:
  - **Permitidos, falla cerrado:** todo paquete de origen bajo `com.confia` debe coincidir con un
    paquete permitido. Coincidir significa igualdad o prefijo seguido de punto, nunca prefijo
    textual a secas, así que `com.confia.bootstrap` no queda cubierto por
    `com.confia.bootstrap.portal`.
  - **Prohibidos, nominal:** **cualquier** paquete de origen, sea o no de `com.confia`, que coincida
    con un prohibido es violación. Es lo que permite incluir `com.github.kagkarlsson`.
  - Los beans del framework sin origen en `com.confia` ni en un prohibido se ignoran.

  Cada violación es una línea legible con el proceso, el nombre del bean, el paquete y la regla
  incumplida (`not in the allow-list` o `forbidden: <motivo>`). La prueba afirma que la lista está
  vacía, así que el mensaje de fallo enumera todas las violaciones a la vez.

  Ofrece además `entryPointConfigurations(context)`: los beans cuya clase de usuario lleva
  `@SpringBootConfiguration`, directa o como meta-anotación (`MergedAnnotations`).

**Descartado:** inspeccionar solo `getBeanDefinitionNames()` con `getType`. Omite los singletons
registrados sin definición y atribuye un `@Bean` de tipo externo al paquete del tipo y no al de la
configuración que lo declara, que es exactamente donde se escondería un módulo filtrado.
**Descartado también:** comparar la lista de nombres de bean contra una instantánea. Cambia con
cada versión de Spring Boot, y el ruido terminaría aprobándose sin leerlo.

### Decisión 5 — Listas por proceso y prueba de aislamiento

| Proceso | Paquete de entrada | Permitidos | Prohibidos nominales |
|---|---|---|---|
| `admin` | `com.confia.bootstrap.admin` | `com.confia.bootstrap.admin`, `com.confia.shared.web.openapi` | `com.confia.bootstrap.portal`, `com.confia.bootstrap.worker`, `com.github.kagkarlsson` |
| `portal` | `com.confia.bootstrap.portal` | `com.confia.bootstrap.portal`, `com.confia.shared.web.openapi` | `com.confia.bootstrap.admin`, `com.confia.bootstrap.worker`, `com.confia.invoicing`, `com.confia.cashbox`, `com.confia.reconciliation`, `com.confia.identity`, `com.github.kagkarlsson` |
| `worker` | `com.confia.bootstrap.worker` | `com.confia.bootstrap.worker` | `com.confia.bootstrap.admin`, `com.confia.bootstrap.portal`, `com.confia.shared.web.openapi` |

- La prohibición de `com.confia.identity` en el portal lleva un comentario: rige **mientras
  `identity` sea solo para personal**. Cuando llegue el subdominio de encargados (ADR-0003, «de
  `identity` solo el subdominio de encargados»), el cambio que lo cree la estrecha al subpaquete de
  personal, con la edición visible en el PR.
- Prohibir `shared.web.openapi` en el trabajador cubre `ContractSchemas` y `ProcessApiInfo` y
  cualquier pieza futura de la superficie OpenAPI, que el trabajador nunca sirve.
- La simetría de prohibidos en `admin` cuesta dos líneas y deja escrita la regla general: ningún
  proceso contiene otro punto de entrada.

**`ProcessBeanIsolationTest`** (`*Test`, sin contenedor, como `OpenApiExposureByProfileTest`):

- **`registersOnlyItsAllowedBeans(ProcessBeanPolicy)`**, parametrizada con `@MethodSource` sobre las
  tres políticas. Arranca **una vez** cada proceso con `ConfiaApplication.launch`
  (`--server.port=0` para los dos web, sin argumentos para el trabajador), lo cierra en `finally` y,
  con `SoftAssertions`, afirma:
  1. `violations(context, policy)` vacía;
  2. `entryPointConfigurations(context)` tiene exactamente un elemento. Es la aserción que delata la
     fuga actual, y no depende del nombre de paquete;
  3. **no vacuidad:** al menos un bean inspeccionado tiene origen en el paquete de entrada del
     proceso. Prueba que el inspector ve beans reales y que quita el proxy CGLIB.
- **`dbSchedulerAbsenceIsVacuousUntilChange9()`**: afirma que
  `com.github.kagkarlsson.scheduler.Scheduler` **no** está en el camino de clases, y su Javadoc y
  su mensaje dicen que la prohibición de `com.github.kagkarlsson` en administración y portal pasa por
  vacío (ADR-0018) hasta el cambio 9. El día que el cambio 9 añada la biblioteca, esta prueba falla
  y obliga a sustituirla por la aserción positiva del trabajador. La prohibición en sí no necesita
  reescribirse: el inspector ya la evalúa sobre cualquier bean.

**Descartado:** una lista de prohibidos como único control (la especificación lo prohíbe: falla
abierto ante un módulo que nadie pensó en prohibir). **Descartado:** permitir clases exactas en lugar
de paquetes. Es más estricto hoy, pero obligaría a editar la lista por cada bean de un módulo ya
aprobado, y el ruido diluiría la revisión de lo que importa, que es qué módulo entra en qué proceso.

### Decisión 6 — Reglas de ArchUnit de `bootstrap`, con fixture permanente

Un solo archivo, `BootstrapEntryPointRulesTest`, con las tres reglas construidas por un método que
recibe la raíz. La de producción es `com.confia.bootstrap` y la del fixture es
`com.confia.architecture.fixture.entrypoints`. Así la regla es la misma en las dos mitades, igual que
`NoCyclesTest`.

```java
static ArchRule entryPointsDoNotDependOnEachOther(String root) {
    return slices().matching(root + ".(*)..").should().notDependOnEachOther()
            .because("each process declares its own modules; one entry point never reaches "
                    + "another's wiring (ADR-0003, ADR-0024)");
}

static ArchRule nothingOutsideReferencesAnEntryPoint(String root) {
    return noClasses().that().resideOutsideOfPackage(root + "..")
            .should().dependOnClassesThat().resideInAPackage(root + ".*..")
            .because("entry point classes are public only for the launcher (ADR-0024)");
}

static ArchRule noEntryPointScansComponents(String root) {
    return noClasses().that().resideInAPackage(root + "..")
            .should().beMetaAnnotatedWith(ComponentScan.class)
            .because("an entry point registers what it loads with @Import, never by scanning "
                    + "(ADR-0024)");
}
```

- `beMetaAnnotatedWith(ComponentScan.class)` cubre `@ComponentScan` directo y
  `@SpringBootApplication`, que la lleva como meta-anotación.
- El destino de la segunda regla son **todas** las clases de los subpaquetes, no solo las tres de
  entrada: todo el cableado por proceso es privado de `bootstrap`. Las clases del paquete base
  (`ConfiaApplication`) no quedan afectadas.
- La tercera se limita a `bootstrap`. Si la configuración pública de un módulo futuro puede escanear
  su propio paquete es una decisión de ese cambio (ADR-0024, decisión de alcance).

**Fixture**, tres clases mínimas en `apps/api/app/src/test/java/com/confia/architecture/fixture/`:

| Clase | Viola |
|---|---|
| `entrypoints/admin/ScanningEntryPoint` | Lleva `@ComponentScan`: tercera regla; el fallo esperado nombra la clase y `ComponentScan` |
| `entrypoints/portal/CrossEntryPointDependency` | Referencia `ScanningEntryPoint`: primera regla |
| `entrypointclient/OutsideEntryPointReference` | Referencia `ScanningEntryPoint` desde fuera de la raíz: segunda regla |

Ninguna lleva `@SpringBootConfiguration`, para que ningún `@SpringBootTest` futuro la encuentre al
subir por los paquetes. Las mitades del fixture usan `assertRuleRejects` con el nombre simple de la
clase infractora como fragmento obligatorio. Los nombres de paquete no contienen segmentos de capa,
así que `LayeredArchitectureTest` no los ve. En `SpringModulithVerificationTest`, `entrypoints` y
`entrypointclient` pasan a ser módulos del fixture y añaden una violación más, sin cambiar el
resultado esperado.

**Descartado:** identificar las clases de entrada por `@SpringBootConfiguration` en la segunda regla.
Obligaría a poner esa anotación en el fixture, con el riesgo de descubrimiento que se acaba de
describir, y protegería menos que el paquete completo.

### Decisión 7 — ADR-0024, breve, como decisión de arquitectura del cambio

Se eleva a ADR porque fija cómo **todo** módulo futuro entra en un proceso y aclara dos ADR
existentes sin reescribirlos. Su contenido se detalla en la sección 8. Se escribe en la fase de
aplicación, antes de la documentación que lo cita, y se registra en `docs/adr/README.md`.

### Decisión 8 — Javadoc y documentación que quedan obsoletos

- `ProcessApiInfo`: el título sigue viniendo de la propiedad que fija el lanzador (no se cambia el
  mecanismo). El párrafo que lo justifica por el escaneo compartido se sustituye por la razón
  vigente: el título acompaña a la selección del proceso en un único lugar.
- `shared/web/openapi/package-info.java`: los consumidores son `bootstrap.admin.AdminApplication` y
  `bootstrap.portal.PortalApplication`, que lo importan sin escaneo.
- `JooqInstitutionRepository` (línea 21) e `IntegrationTestApplication` (Javadoc, pruebas): dejan
  de describir «escaneo restringido a `com.confia.bootstrap`» y «de paquete»; una línea cada uno.
- `docs/01-arquitectura.md` §4 (árbol de `bootstrap/`) y el párrafo de la regla de dependencia;
  `docs/09-roadmap-y-fases.md`, nota del riesgo del 2026-09-30, marcada como resuelta por el
  cambio 15; `confia-module-scaffold` §4, que hoy dice que el mecanismo «se fija en F0»: pasa a
  describir el `@Import` de la configuración pública del módulo y la edición obligatoria de
  `ProcessBeanPolicy`.

## 4. Cambios de archivos

| Archivo | Acción |
|---|---|
| `apps/api/app/src/main/java/com/confia/bootstrap/{Admin,Portal,Worker}Application.java` | Eliminar (movidos) |
| `apps/api/app/src/main/java/com/confia/bootstrap/admin/AdminApplication.java` | Crear (decisión 1) |
| `apps/api/app/src/main/java/com/confia/bootstrap/portal/PortalApplication.java` | Crear (decisión 1) |
| `apps/api/app/src/main/java/com/confia/bootstrap/worker/WorkerApplication.java` | Crear (decisión 1) |
| `apps/api/app/src/main/java/com/confia/bootstrap/ConfiaApplication.java` | Importaciones y Javadoc (decisión 2) |
| `apps/api/app/src/main/java/com/confia/shared/web/openapi/{ProcessApiInfo,package-info}.java` | Javadoc (decisión 8) |
| `apps/api/app/src/main/java/com/confia/organization/infrastructure/JooqInstitutionRepository.java` | Javadoc, una línea (decisión 8) |
| `apps/api/app/src/test/java/com/confia/bootstrap/ProcessBeanPolicy.java` | Crear (decisiones 4 y 5) |
| `apps/api/app/src/test/java/com/confia/bootstrap/ProcessBeanInspector.java` | Crear (decisión 4) |
| `apps/api/app/src/test/java/com/confia/bootstrap/ProcessBeanIsolationTest.java` | Crear (decisión 5) |
| `apps/api/app/src/test/java/com/confia/bootstrap/ProcessBeanInspectorTest.java` | Crear (sección 6) |
| `apps/api/app/src/test/java/com/confia/architecture/BootstrapEntryPointRulesTest.java` | Crear (decisión 6) |
| `apps/api/app/src/test/java/com/confia/architecture/fixture/entrypoints/admin/ScanningEntryPoint.java` | Crear (decisión 6) |
| `apps/api/app/src/test/java/com/confia/architecture/fixture/entrypoints/portal/CrossEntryPointDependency.java` | Crear (decisión 6) |
| `apps/api/app/src/test/java/com/confia/architecture/fixture/entrypointclient/OutsideEntryPointReference.java` | Crear (decisión 6) |
| `apps/api/app/src/test/java/com/confia/support/IntegrationTestApplication.java` | Javadoc (decisión 8) |
| `docs/adr/ADR-0024-registro-explicito-por-punto-de-entrada.md` | Crear (sección 8) |
| `docs/adr/README.md` | Registrar ADR-0024 |
| `docs/01-arquitectura.md`, `docs/09-roadmap-y-fases.md` | Decisión 8 |
| `.claude/skills/confia-module-scaffold/SKILL.md` §4 | Decisión 8 |

Las pruebas existentes (`ConfiaApplicationTest`, `OpenApiProcess`, `OpenApiContractSnapshotTest`,
`OpenApiExposureByProfileTest`) no cambian: usan `ConfiaApplication.launch`, que conserva su firma.

## 5. Interfaces de soporte de prueba

```java
// apps/api/app/src/test/java/com/confia/bootstrap/ProcessBeanPolicy.java
record ProcessBeanPolicy(String process, String entryPackage, Set<String> allowedPackages,
        Map<String, String> forbiddenPackages) {

    static final ProcessBeanPolicy ADMIN = …;   // decision 5 table
    static final ProcessBeanPolicy PORTAL = …;
    static final ProcessBeanPolicy WORKER = …;

    static Stream<ProcessBeanPolicy> all() { return Stream.of(ADMIN, PORTAL, WORKER); }
}

// apps/api/app/src/test/java/com/confia/bootstrap/ProcessBeanInspector.java
final class ProcessBeanInspector {

    record InspectedBean(String name, Set<String> originPackages) {}

    /** Beans with at least one origin package under com.confia (decision 4). */
    static List<InspectedBean> confiaBeans(ConfigurableApplicationContext context);

    /** One readable line per violation: process, bean, package and rule; empty when clean. */
    static List<String> violations(ConfigurableApplicationContext context, ProcessBeanPolicy policy);

    /** Beans whose user class carries @SpringBootConfiguration, directly or as a meta-annotation. */
    static List<String> entryPointConfigurations(ConfigurableApplicationContext context);
}
```

`process()` coincide con el valor de `APP_PROFILE`, así que la prueba parametrizada arranca el
proceso con `ConfiaApplication.launch(args, policy.process())`.

## 6. Estrategia de pruebas y trazabilidad

| Nivel | Qué | Cómo |
|---|---|---|
| Contexto real (sin contenedor) | Lista de permitidos, prohibidos, una sola clase de entrada y no vacuidad, por proceso | `ProcessBeanIsolationTest`, arrancando por `ConfiaApplication.launch` |
| Inspector (sin Spring Boot) | Que el inspector detecta y que no rechaza todo | `ProcessBeanInspectorTest` sobre `GenericApplicationContext` construidos a mano |
| Estático | Slices, referencias externas y escaneo | `BootstrapEntryPointRulesTest`, dos mitades por regla |
| Regresión | Arranque, OpenAPI, módulos | `ConfiaApplicationTest`, pruebas del OpenAPI, `SpringModulithVerificationTest` sin cambios |

**`ProcessBeanInspectorTest`, prueba negativa permanente.** Tres contextos `GenericApplicationContext`
con `registerBean`, sin procesadores de anotaciones, que se refrescan y se cierran en la prueba:

1. **Fuera de la lista:** un bean de una clase anidada de la propia prueba (paquete
   `com.confia.bootstrap`, fuera de toda lista) evaluado con `PORTAL` → una violación
   `not in the allow-list` que nombra el bean y el paquete.
2. **Prohibido nominal:** `AdminApplication` registrada como bean simple evaluada con `PORTAL` → la
   violación nombra `com.confia.bootstrap.admin` y el motivo de prohibición.
3. **Contexto limpio:** `PortalApplication`, `ContractSchemas` y un bean del JDK evaluados con
   `PORTAL` → ninguna violación. Demuestra que la detección de 1 y 2 no se debe a un inspector que
   rechaza todo, y que los beans ajenos a `com.confia` se ignoran.

| Requisito del delta | Escenario | Prueba |
|---|---|---|
| Lista de permitidos | Un bean fuera de la lista rompe la construcción | `ProcessBeanInspectorTest` (1) |
| | Un módulo nuevo obliga a editar la lista | `ProcessBeanInspectorTest` (1): mismo mecanismo; la lista es código revisado |
| | Cada proceso solo contiene beans permitidos | `ProcessBeanIsolationTest`, aserciones 1 y 3 |
| Portal sin beans ajenos | Punto de entrada ajeno / módulo administrativo | `ProcessBeanInspectorTest` (2) y `ProcessBeanIsolationTest[portal]` |
| | El portal contiene solo lo suyo | `ProcessBeanIsolationTest[portal]` |
| Trabajador sin beans ajenos | Importación ajena / arranque sin servidor web | `ProcessBeanIsolationTest[worker]`; `ConfiaApplicationTest` para la ausencia de servidor |
| Registro explícito | Un punto de entrada vuelve a escanear | `BootstrapEntryPointRulesTest`, tercera regla, mitad de fixture |
| | Los tres cumplen la forma explícita | Tercera regla, mitad de producción, más `ConfiaApplicationTest` |
| Slices de `bootstrap` | Fixture / producción | Primera regla, dos mitades |
| Nada fuera referencia una entrada | Fixture / producción | Segunda regla, dos mitades |
| db-scheduler vacuo | La aserción es vacua y está declarada | `dbSchedulerAbsenceIsVacuousUntilChange9` |
| | Se vuelve efectiva con la biblioteca | Prohibido `com.github.kagkarlsson` evaluado sobre todo bean (decisión 4); se demuestra en el cambio 9 |
| Prueba negativa del inspector | Los tres escenarios | `ProcessBeanInspectorTest` (1), (2) y (3) |

## 7. Secuencia de aplicación, de rojo a verde

1. **ROJO contra el estado actual.** Crear `ProcessBeanPolicy`, `ProcessBeanInspector` y
   `ProcessBeanIsolationTest` sin tocar producción y ejecutar la prueba. La evidencia que cuenta,
   registrada en `apply-progress.md`, es: **tres** configuraciones de entrada en los contextos
   administrativo y del portal, y `ContractSchemas` y `ProcessApiInfo` en el trabajador. Que las
   clases actuales estén en `com.confia.bootstrap` y no en su subpaquete también falla, pero es
   esperado y secundario. **Si cualquiera de las dos predicciones no se cumple (el portal o la
   administración con una sola configuración de entrada, o el trabajador sin beans del OpenAPI),
   el diagnóstico era incorrecto al menos en parte: el cambio se detiene y se informa.**
2. **VERDE.** Mover y reanotar las tres clases (decisión 1) y actualizar `ConfiaApplication`
   (decisión 2). Quedan en verde `ProcessBeanIsolationTest`, `ConfiaApplicationTest`, las pruebas del
   OpenAPI sin cambios en la instantánea y `SpringModulithVerificationTest`.
3. **Inspector.** `ProcessBeanInspectorTest` con sus tres casos. Para probar que protege, se anula
   temporalmente el cuerpo de `violations` y se confirma que fallan los casos 1 y 2; se registra y
   se revierte.
4. **ROJO y VERDE de ArchUnit.** `BootstrapEntryPointRulesTest` sin fixture: las tres mitades de
   fixture fallan por conjunto vacío (ADR-0018). Se añaden las tres clases del fixture y pasan a
   verde. Las mitades de producción están en verde desde el paso 2. Para la tercera regla se
   registra además una demostración: `@ComponentScan` añadido temporalmente a `WorkerApplication`
   rompe la mitad de producción.
5. **ADR-0024** y su registro en `docs/adr/README.md`.
6. **Documentación, skill y Javadoc** (decisión 8).
7. **`./mvnw verify` completo** en `apps/api` y medición del diff de autor.

Los pasos 1 y 2 pueden ejecutarse con `./mvnw verify -Dtest=…` para acotar, pero cada paso cierra
con `./mvnw verify` completo en verde, salvo el paso 1, que debe terminar en rojo.

## 8. Contenido de ADR-0024

**Archivo:** `docs/adr/ADR-0024-registro-explicito-por-punto-de-entrada.md`.
**Título:** «ADR-0024: Registro explícito de lo que carga cada punto de entrada, sin escaneo de
componentes». **Estado:** Aceptado. **Contexto técnico:** módulo `bootstrap` de `apps/api/app`;
concreta ADR-0003 y ADR-0013 y aclara ADR-0003 y ADR-0022 sin reescribirlas.

- **Contexto y problema.** ADR-0003 promete que «cada punto de entrada declara de forma explícita
  qué módulos carga». Las tres clases compartían paquete y escaneo, de modo que cada contexto
  registraba las otras dos y sus importaciones. Se cita la evidencia en rojo del paso 1 de la
  sección 7. Si no se decide, el primer módulo que se registre en administración aparece en el
  portal.
- **Factores de decisión.** La separación falla cerrado; es verificable en `./mvnw verify`; no crea
  API pública de módulos sin consumidor (ADR-0022); costo mínimo para un desarrollador solo.
- **Opciones.** (A) subpaquetes con su propio `scanBasePackages`; (B) `@Import` de la configuración
  pública de cada módulo, construida ya; (C) condiciones `@Profile`, `@ConditionalOnProperty` o
  `AppProfile`; (D) subpaquetes más `@Import` explícito de lo que existe hoy. Ventajas y desventajas
  según la tabla de la propuesta. C se descarta porque falla abierto.
- **Decisión.** D: `@SpringBootConfiguration` + `@EnableAutoConfiguration(exclude = …)` + `@Import`,
  un subpaquete por proceso, `ConfiaApplication` como único `main`.
- **Aclaración de ADR-0003.** «El trabajador corre con el perfil administrativo» significa que usa
  el rol de base de datos `confia_admin_app` y la configuración administrativa, **no** que cargue el
  grafo de beans administrativo. El trabajador declara sus propios módulos en `WorkerApplication`.
- **Regla para módulos futuros (ADR-0022).** Un módulo que un punto de entrada deba cargar expone
  una configuración pública, en su paquete base o en un paquete con `@NamedInterface`, que declara
  sus beans de forma explícita. Esa configuración se crea cuando existe el consumidor real
  (`session-tokens-and-web-layer` es el primero), nunca de forma anticipada. Registrar el módulo
  exige dos ediciones visibles: el `@Import` en la clase de entrada y la línea en `ProcessBeanPolicy`.
  Si un módulo necesitara escaneo, solo puede ser de su propio paquete, y lo decide el cambio que lo
  introduce.
- **Consecuencias.** Positivas: garantía estructural y verificada, y una lista de módulos por
  proceso legible en un solo archivo. Costos: cada módulo necesita una configuración pública; las
  clases de entrada pasan a ser públicas; `@AutoConfigurationPackage` apunta al subpaquete. Riesgos
  y mitigaciones: los de la sección 9.
- **Cumplimiento y verificación.** `ProcessBeanIsolationTest`, `ProcessBeanInspectorTest`,
  `BootstrapEntryPointRulesTest` (tres reglas con fixture) y `SpringModulithVerificationTest`. La
  instantánea del mapa de rutas del portal (ADR-0003, verificación 1) sigue pendiente hasta la
  primera capa web.
- **Referencias.** ADR-0003, ADR-0013, ADR-0016, ADR-0018, ADR-0022; esta carpeta de cambio.

## 9. Riesgos

| Riesgo | Mitigación |
|---|---|
| La predicción de la fuga es incorrecta | Paso 1 de la sección 7, con criterio de parada explícito |
| `@AutoConfigurationPackage` apunta ahora a `bootstrap.<proceso>` y una biblioteca futura que lo use para buscar componentes (por ejemplo el registro de eventos de Spring Modulith en tiempo de ejecución, o Spring Data, prohibido por ADR-0015) vería una raíz demasiado estrecha | Se documenta en ADR-0024. Quien incorpore una de esas bibliotecas declara la raíz de forma explícita; el inspector delata cualquier bean que aparezca sin estar permitido |
| El cambio 9 añade el starter de db-scheduler y, con `@EnableAutoConfiguration`, se autoconfigura también en administración y portal | El prohibido `com.github.kagkarlsson` lo detecta sin reescribirse, y `dbSchedulerAbsenceIsVacuousUntilChange9` obliga a revisar el caso ese mismo día |
| `getType(name, false)` devuelve `null` para un `FactoryBean` no resoluble | El origen por definición y por bean fábrica (decisión 4, puntos 2 y 3) sigue atribuyéndolo; un `FactoryBean` de `com.confia` nunca queda sin clase declarada |
| Git registra el movimiento de las tres clases como borrado y alta, y el diff medido supera el presupuesto de 400 líneas | Javadoc de las clases movidas breve, fixture de tres clases y ADR conciso. Si el exceso aparece, se informa antes del PR con el corte de la propuesta: (1) pasos 1 a 3 y (2) pasos 4 a 6 |
| El arranque de tres contextos alarga `./mvnw verify` | Un arranque por proceso en la prueba parametrizada; mismo costo que `ConfiaApplicationTest` |

## 10. Matriz de amenazas

La matriz de la fase de diseño no aplica: este cambio no toca enrutamiento, órdenes de consola,
subprocesos, automatización de Git o de PR ni clasificación de archivos ejecutables. Los «procesos»
de este cambio son contextos de Spring dentro de la JVM, no procesos del sistema operativo lanzados
por el código.

| Frontera | Aplicabilidad |
|---|---|
| Rutas con apariencia de documentación | N/A: no se clasifican ni ejecutan archivos |
| Selección de repositorio Git | N/A: sin automatización de Git |
| Estado del commit | N/A: ídem |
| Estado del push | N/A: ídem |
| Órdenes de PR | N/A: sin automatización de PR |

Frontera de seguridad propia del cambio, cubierta por las pruebas de la sección 6:

| Frontera | Tratamiento |
|---|---|
| Código administrativo cargado en el proceso expuesto a internet | Lista de permitidos y prohibidos del portal (decisión 5) |
| Un punto de entrada que vuelve a escanear | Tercera regla de ArchUnit (decisión 6) |
| Un módulo de negocio que referencia una clase de entrada pública | Segunda regla de ArchUnit (decisión 6) |
| Un inspector que no inspecciona nada | `ProcessBeanInspectorTest` y la aserción de no vacuidad |

## 11. Migración y despliegue

Sin migración. No hay base de datos, datos, endpoints ni empaquetado: `ConfiaApplication` sigue
siendo el único `main` y el empaquetado llega con el cambio 11. La reversión es revertir el commit
de fusión.

## 12. Preguntas abiertas

- Ninguna bloqueante. El alcance de la regla de escaneo (solo `bootstrap`) queda decidido en la
  decisión 6 y en ADR-0024; ampliarlo a las configuraciones de módulos es decisión de
  `session-tokens-and-web-layer`.
