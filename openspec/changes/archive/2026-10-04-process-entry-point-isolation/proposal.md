# Propuesta: aislamiento de los puntos de entrada por proceso

- **Cambio:** `process-entry-point-isolation`
- **Fase del roadmap:** F0, cambio 15 (exploración `foundations-plan`, fila 15)
- **Debe fusionarse antes de:** `session-tokens-and-web-layer`, el primer cambio que registra un
  módulo de negocio en un punto de entrada (`docs/09-roadmap-y-fases.md`)
- **Origen:** hallazgo de `frontend-monorepo-and-contracts-pipeline`, corte 3a
  (`apply-progress.md`, tarea 1.3)
- **Exploración:** `exploration.md` de este mismo cambio
- **Estado:** aprobada por el propietario del producto el 2026-10-03 (`openspec/config.yaml`,
  `rules.proposal`).

## Intención

ADR-0003 promete que el proceso del portal **no tiene cargado** el código administrativo, porque
«cada punto de entrada declara de forma explícita qué módulos carga». Hoy esa promesa no se cumple
ni siquiera con el código vacío que existe.

`AdminApplication`, `PortalApplication` y `WorkerApplication` viven juntas en
`com.confia.bootstrap`, con visibilidad de paquete, y las tres llevan
`@SpringBootApplication(scanBasePackages = "com.confia.bootstrap", exclude =
DataSourceAutoConfiguration.class)`. Como `@SpringBootApplication` es a su vez una
`@Configuration`, cada punto de entrada, al escanear su propio paquete, **registra a los otros
dos**: el contexto del portal carga `AdminApplication` y `WorkerApplication`, y el del trabajador
recibe `ContractSchemas` y `ProcessApiInfo` a través del `@Import` de las clases administrativa y
del portal. Este diagnóstico se dedujo de la semántica de Spring y de la lectura del código, **sin
ejecutarlo**; por eso la primera tarea del cambio es una prueba en rojo que lo demuestre.

Hoy la fuga no expone nada, porque todavía no hay beans de negocio. El momento de cerrarla es
ahora: en cuanto `session-tokens-and-web-layer` registre el primer módulo en el punto de entrada
administrativo, ese módulo aparecería también en el portal sin que nadie lo note. Este cambio
convierte la separación por proceso en una garantía estructural y verificada, antes de que haya
algo que proteger, igual que el cambio 1 instaló ArchUnit antes del primer módulo.

## Alcance

### Dentro de alcance

1. **Prueba en rojo contra el estado actual.** Una prueba de contexto por proceso que demuestra la
   fuga cruzada antes de tocar código de producción (TDD estricto). Si la prueba no falla, el
   diagnóstico era incorrecto: el cambio se detiene y se informa.
2. **Subpaquetes por proceso.** Los tres puntos de entrada se mueven a `com.confia.bootstrap.admin`,
   `com.confia.bootstrap.portal` y `com.confia.bootstrap.worker`, como clases públicas.
   `ConfiaApplication` (único `main`) actualiza sus importaciones y conserva la selección por
   `APP_PROFILE` y el arranque del trabajador sin servidor web.
3. **Registro explícito, sin escaneo implícito.** `@SpringBootApplication` se sustituye por
   `@SpringBootConfiguration` + `@EnableAutoConfiguration` + `@Import` explícito de lo que cada
   proceso necesita hoy, conservando la exclusión de `DataSourceAutoConfiguration`.
   Administración y portal importan `ContractSchemas` y `ProcessApiInfo`; el trabajador no importa
   nada.
4. **Prueba de aislamiento por proceso con lista de permitidos**, en el paquete de pruebas
   `com.confia.bootstrap`, que arranca cada proceso con `ConfiaApplication.launch`, sin base de
   datos:
   - todo bean bajo `com.confia.*` pertenece a los paquetes permitidos de ese proceso; la lista
     **falla cerrado**, de modo que un módulo nuevo obliga a editarla y la edición se ve en
     revisión;
   - prohibidos nominales para el portal: ningún bean de `bootstrap.admin`, `bootstrap.worker`,
     `invoicing`, `cashbox`, `reconciliation`, ni de `identity` mientras sea solo para personal;
   - ausencia de db-scheduler (`com.github.kagkarlsson`) en administración y portal, **declarada
     de forma explícita como vacua** mientras la biblioteca no esté en el camino de clases
     (ADR-0018); la aserción positiva para el trabajador queda para el cambio 9.
5. **Prueba negativa permanente del inspector.** Un contexto con fuga deliberada debe ser
   detectado, para demostrar que la prueba de aislamiento no pasa por vacío.
6. **Reglas de ArchUnit** con su fixture permanente, según la convención de dos pruebas del
   repositorio:
   - los subpaquetes de `bootstrap` no dependen entre sí
     (`slices().matching("com.confia.bootstrap.(*)..")`);
   - nada fuera de `com.confia.bootstrap` referencia las clases de entrada, que ahora son públicas.
7. **ADR-0024**, breve, que fija el mecanismo de registro por punto de entrada y además:
   - aclara la frase de ADR-0003 «el trabajador corre con el perfil administrativo»: significa que
     usa el rol de base de datos y la configuración administrativos, **no** que cargue el grafo de
     beans administrativo; el trabajador declara sus propios módulos;
   - documenta que todo módulo futuro que un punto de entrada deba importar expone una
     configuración pública (clase en su paquete base o interfaz nombrada, ADR-0022), y que esa
     exposición se crea cuando exista el consumidor real, no en este cambio.
8. **Documentación:** `docs/01-arquitectura.md`, `docs/09-roadmap-y-fases.md`,
   `.claude/skills/confia-module-scaffold/SKILL.md` §4 (hoy dice que el mecanismo «se fija en F0»)
   y el Javadoc de `ProcessApiInfo` y de `shared/web/openapi/package-info.java`, que justifica el
   diseño actual y queda obsoleto.

### Fuera de alcance

- **Registrar cualquier módulo de negocio** en un punto de entrada. Lo hace
  `session-tokens-and-web-layer`, que también crea la configuración pública de cada módulo que
  importe.
- **La instantánea del mapa de rutas del portal** (ADR-0003, verificación 1). Hoy no existe ninguna
  ruta; llega con la primera capa web. Este cambio verifica el grafo de beans, que es la condición
  previa.
- **La aserción positiva de db-scheduler en el trabajador:** cambio 9.
- **Empaquetado, `spring-boot-maven-plugin`, `mainClass` y Dockerfiles:** cambio 11.
  `ConfiaApplication` sigue siendo el único `main`, así que este cambio no condiciona el
  empaquetado.
- **Condicionar por `@Profile`, `@ConditionalOnProperty` o `AppProfile`** (enfoque c de la
  exploración): descartado, ver Enfoque.
- **Reescribir ADR-0003.** Se aclara desde ADR-0024 con una nota de referencia, sin editar el cuerpo
  de la decisión.

## Capacidades

### Nuevas

- Ninguna.

### Modificadas

- `build-integrity`: requisitos nuevos verificables. Cada proceso solo registra beans de
  `com.confia.*` incluidos en su lista de permitidos, y un bean fuera de ella rompe la construcción.
  El contexto del portal no contiene beans de los otros puntos de entrada ni de los módulos
  administrativos. Los subpaquetes de `bootstrap` no dependen entre sí y nada fuera de `bootstrap`
  referencia una clase de entrada. La ausencia de db-scheduler en administración y portal se
  declara vacua hasta el cambio 9, con destino explícito, igual que los requisitos de brecha
  existentes en esa especificación.

## Enfoque

Se adopta el **enfoque (d) de la exploración**: híbrido acotado entre subpaquetes por proceso y
`@Import` explícito sin escaneo.

| Enfoque | Decisión | Razón |
|---|---|---|
| (a) Subpaquetes, cada uno con su `scanBasePackages` | No | Corta la fuga actual, pero sigue descubriendo por escaneo: cualquier ampliación del paquete escaneado vuelve a abrirla |
| (b) `@Import` explícito con configuración pública por módulo, ya construida | No, todavía | Coincide con ADR-0003, pero exige crear hoy APIs públicas de módulos sin consumidor real, contra ADR-0022 |
| (c) `@Profile`, `@ConditionalOnProperty` o `AppProfile` | **Descartado** | Falla abierto: un bean sin la condición se registra en todos los procesos. `APP_PROFILE` no es un perfil de Spring, y ADR-0003 exige separación por contexto, no por configuración |
| (d) Subpaquetes + `@Import` explícito de lo que existe hoy | **Adoptado** | Garantía estructural más una prueba que falla cerrado; deja la forma de lista que `session-tokens-and-web-layer` necesita, sin crear APIs prematuras |

Orden de trabajo, de adentro hacia afuera:

1. Prueba de lista de permitidos por proceso, **en rojo** contra el estado actual.
2. Mover y reformular los puntos de entrada hasta verde.
3. Prueba negativa del inspector.
4. Reglas de ArchUnit con su fixture.
5. ADR-0024.
6. Documentación, skill y Javadoc.
7. `./mvnw verify` completo y medición del diff.

Los nombres exactos de las clases de prueba, la forma del inspector y la redacción de la regla de
ArchUnit se fijan en la fase de diseño.

## Áreas afectadas

| Área | Impacto | Descripción |
|---|---|---|
| `apps/api/app/src/main/java/com/confia/bootstrap/{Admin,Portal,Worker}Application.java` | Movida y modificada | A `bootstrap.admin`, `bootstrap.portal` y `bootstrap.worker`, públicas, sin escaneo implícito |
| `apps/api/app/src/main/java/com/confia/bootstrap/ConfiaApplication.java` | Modificada | Importaciones de las clases movidas |
| `apps/api/app/src/main/java/com/confia/shared/web/openapi/{package-info,ProcessApiInfo}.java` | Modificada | Javadoc obsoleto |
| `apps/api/app/src/test/java/com/confia/bootstrap/` | Nueva | Prueba de aislamiento por proceso y prueba negativa del inspector |
| `apps/api/app/src/test/java/com/confia/architecture/` | Nueva | Reglas de ArchUnit de `bootstrap` y su fixture |
| `docs/adr/ADR-0024-*.md`, `docs/adr/README.md` | Nueva y modificada | Mecanismo de registro por punto de entrada |
| `docs/01-arquitectura.md`, `docs/09-roadmap-y-fases.md` | Modificada | Mecanismo y estado del cambio 15 |
| `.claude/skills/confia-module-scaffold/SKILL.md` §4 | Modificada | Cómo registrar un módulo en su punto de entrada |
| `openspec/changes/process-entry-point-isolation/specs/build-integrity/spec.md` | Nueva | Delta de requisitos |

## Riesgos

| Riesgo | Probabilidad | Mitigación |
|---|---|---|
| El diagnóstico de la fuga es incorrecto, porque nunca se ejecutó | Baja | La primera tarea es la prueba en rojo; si no falla, el cambio se detiene y se informa |
| Quitar el escaneo deja fuera un bean que hoy se registra por accidente y del que algo depende | Baja | Hoy no hay beans de negocio; `ConfiaApplicationTest`, la prueba del OpenAPI y `./mvnw verify` completo lo detectan |
| `session-tokens-and-web-layer` descubre tarde que cada módulo necesita una configuración pública | Media | ADR-0024 y la skill `confia-module-scaffold` §4 lo dejan escrito, con referencia a ADR-0022 |
| La prueba de lista de permitidos pasa por vacío | Media | Prueba negativa permanente con fuga deliberada |
| La aserción de db-scheduler es vacua y se toma por garantía | Media | Se declara vacua de forma explícita (ADR-0018), con destino en el cambio 9 |
| Las clases de entrada públicas se referencian desde otros módulos | Baja | Regla de ArchUnit que lo prohíbe, con fixture |
| `SpringModulithVerificationTest` falla porque un punto de entrada importa internos de otro módulo | Baja | Solo se importan `ContractSchemas` y `ProcessApiInfo`, expuestos por la interfaz nombrada de `shared.web.openapi` |
| El diff supera el presupuesto de revisión | Media | Estimación en el límite; ADR breve y fixtures mínimos. Si se desborda, el corte es (1) prueba de contexto más movimiento de clases y (2) ArchUnit más documentación, lo que exige cambiar la estrategia de entrega con el propietario |

## Plan de reversión

No hay base de datos, migraciones, datos, endpoints ni despliegue: el empaquetado llega con el
cambio 11 y `ConfiaApplication` sigue siendo el único `main`. Revertir es revertir el commit de
fusión, que devuelve las tres clases a `com.confia.bootstrap` con `@SpringBootApplication`. Las
pruebas nuevas, las reglas de ArchUnit y ADR-0024 se revierten en el mismo commit. Si se revierte,
el cambio 15 queda abierto en `docs/09-roadmap-y-fases.md` y `session-tokens-and-web-layer` queda
bloqueado hasta resolverlo.

## Dependencias

- `frontend-monorepo-and-contracts-pipeline`, archivado (aporta `ContractSchemas`, `ProcessApiInfo`
  y la interfaz nombrada de `shared.web.openapi`).
- JDK 25 y Docker, como ya exige `./mvnw verify`.

## Estimación de tamaño

- Unas **400 líneas autorales cambiadas**, en el límite del presupuesto de revisión de 400 y lejos
  del umbral de ochocientas del proyecto. Estrategia de entrega: **PR único**.
- Desglose: clases movidas y editadas ~70; `ConfiaApplication` y Javadoc ~20; prueba de contexto e
  inspector ~150; reglas de ArchUnit y fixture ~70; ADR y documentación ~90.
- **Entre 7 y 8 tareas**, por debajo del límite de 15.

## Criterios de éxito

- [x] La prueba de lista de permitidos falla contra el estado actual, con evidencia en rojo
      registrada antes de cualquier cambio de producción.
- [x] Tras el cambio, el contexto de cada proceso solo contiene beans de `com.confia.*` de su lista
      de permitidos; el portal no contiene ningún bean de `bootstrap.admin` ni de
      `bootstrap.worker`.
- [x] El contexto del trabajador no contiene `ContractSchemas` ni `ProcessApiInfo`.
- [x] Ningún punto de entrada usa `@SpringBootApplication` ni `@ComponentScan`; los tres conservan la
      exclusión de `DataSourceAutoConfiguration`.
- [x] La prueba negativa demuestra que el inspector detecta un bean fuera de la lista.
- [x] Las dos reglas de ArchUnit rechazan su fixture de violación deliberada.
- [x] `SpringModulithVerificationTest`, `ConfiaApplicationTest` y la comparación de la instantánea
      del OpenAPI siguen en verde, y `./mvnw verify` completo termina en verde.
- [x] ADR-0024 está aceptado y aclara el significado de «perfil administrativo» del trabajador.
- [x] La skill `confia-module-scaffold` §4 explica cómo registrar un módulo en su punto de entrada.
- [x] El diff medido queda dentro del presupuesto, o el exceso se reporta antes de abrir el PR.
