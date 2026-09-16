# Diseño: esqueleto de espacio de trabajo Maven e integración continua

Cambio 1 de F0. Implementa la propuesta aprobada el 2026-09-15 y el delta de `build-integrity`.

## Enfoque técnico

Reactor Maven de tres POM bajo `apps/api`: el padre concentra la versión de Java, la lista de
materiales de Spring Boot 4.1 y todas las verificaciones; `kernel` es una frontera de compilación
vacía; `app` contiene el lanzador y las pruebas de arquitectura. La construcción se ordena de
adentro hacia afuera: prueba de humo en verde, luego `kernel`, luego `app`, y al final las reglas.

El problema central de este cambio es que **sin módulos de negocio, toda regla de capa es vacía**:
no tiene clases que inspeccionar, pasa sin verificar nada y ArchUnit puede incluso fallar por
conjunto vacío. Se resuelve con fixtures negativos permanentes (decisión 3), no con una violación
temporal comprometida a la rama.

## Decisiones de arquitectura

### 1. Reactor de tres POM

**Elección:** padre `pom` + `kernel` + `app` (artefacto `confia-api`).
**Alternativas descartadas:** proyecto Maven de un solo módulo, que elimina la frontera de
compilación que ADR-0002 y ADR-0013 exigen (`kernel-is-pure` deja de ser un error del compilador y
pasa a ser una convención); Gradle, que contradice ADR-0013 sin aportar nada aquí.
**Razón:** la pureza de `kernel` se verifica gratis en el compilador y con `bannedDependencies`.

### 2. `maven-enforcer-plugin` en `validate`

Fase confirmada en `docs/06` §14.1. Reglas: `requireJavaVersion`, convergencia de dependencias,
`kernel` sin dependencias fuera del JDK, prohibidas Hibernate, Jakarta Persistence, Spring Data JPA
y Spring Data JDBC (ADR-0015) y Quartz, JobRunr y otras bibliotecas de programación (ADR-0016).

La prohibición de `SNAPSHOT` **es condicional a la rama principal**, y el enforcer no conoce ramas:
vive en un perfil activado por una propiedad que la integración continua fija solo en `main`. Así
una rama de trabajo puede usar un `SNAPSHOT` puntual y `main` nunca.

### 3. Reglas de arquitectura auto-probadas

**Elección:** las reglas de ADR-0002 (`domain-is-pure`, `no-cross-module-domain`,
`application-no-infrastructure`, `interface-only-application`, `no-cycles`) más la regla estructural
que rechaza paquetes `interface`, `interfaces` y nombres de capa técnica, se escriben una sola vez y
se ejercitan contra un paquete de fixtures deliberadamente inválido bajo `src/test/java`, que
**permanece** en el repositorio. Cada regla tiene dos pruebas: se aplica al código de producción
(hoy vacío) y se afirma que **rechaza** su fixture correspondiente.
**Alternativas descartadas:** una violación temporal comprometida y luego retirada, que solo prueba
la regla en el instante en que alguien la miró y no protege contra una regresión futura; aplazar
ArchUnit al primer módulo de negocio, que es exactamente el orden que la propuesta rechaza.
**Razón:** una regla que nunca falló no protege nada, y el fixture convierte esa demostración en
permanente y automática. Las reglas de producción excluyen el paquete de fixtures.

### 4. Lanzador por `APP_PROFILE`

Un único `main` lee `APP_PROFILE` y arranca la aplicación Spring correspondiente. Cada proceso es
una clase distinta con su propio `scanBasePackages` explícito, que es el mecanismo con el que
`admin`, `portal` y `worker` declararán sus módulos (ADR-0003, ADR-0013). Hoy ninguna declara
módulos de negocio porque no existen. `migrate` **solo se reconoce**; aplicar Flyway llega con el
cambio 5. Un valor ausente o desconocido aborta con mensaje explícito y código distinto de cero.

```
APP_PROFILE ──> ConfiaApplication#main
                     │
   admin ──> AdminApplication    (web)
   portal ─> PortalApplication   (web)
   worker ─> WorkerApplication   (sin HTTP)
   migrate > reconocido, sin comportamiento (cambio 5)
   otro ───> falla al arrancar, salida != 0
```

### 5. Integración continua parcial y honesta

**Elección:** crear `.github/workflows/ci.yml` únicamente con los trabajos que este cambio puede
dejar en verde (`backend` y el escaneo de dependencias), respetando la forma, los nombres y las
versiones ya declaradas en `docs/06` §14.5. Los trabajos `frontend`, `e2e` y `quality-gate` llegan
con los cambios 3 y 11. **No se pasa `-Pmutation-gate`**: esos perfiles los define el cambio 2.
**Alternativa descartada:** escribir el flujo completo declarado, que dejaría la rama en rojo
permanente por trabajos que aún no pueden pasar.

### 6. Escaneo de dependencias

`aquasecurity/trivy-action@0.28.0` en modo `fs`, severidad `HIGH,CRITICAL`, `exit-code: 1`. Es la
línea base ya escrita en `docs/06` §14.5 y `docs/03` §13 delega en F0 la herramienta definitiva.
**Alternativa descartada:** escanear solo al etiquetar una versión, que deja la regla nacida
desactivada.

## Cambios de archivos

| Archivo | Acción | Descripción |
|---|---|---|
| `apps/api/pom.xml` | Crear | POM padre: Java 25, BOM de Spring Boot 4.1, enforcer, Surefire, Failsafe |
| `apps/api/mvnw`, `mvnw.cmd`, `.mvn/wrapper/` | Crear | Maven Wrapper comprometido |
| `apps/api/kernel/pom.xml` + `package-info.java` | Crear | Frontera de compilación, sin dependencias |
| `apps/api/kernel/src/test/java/com/confia/kernel/BuildSmokeTest.java` | Crear | Prueba trivial en verde |
| `apps/api/app/pom.xml` | Crear | Artefacto `confia-api` |
| `apps/api/app/src/main/java/com/confia/bootstrap/` | Crear | Lanzador y las tres clases de proceso |
| `apps/api/app/src/test/java/com/confia/architecture/` | Crear | Reglas de ArchUnit, Spring Modulith y sus pruebas negativas |
| `apps/api/app/src/test/java/com/confia/architecture/fixture/` | Crear | Violaciones deliberadas permanentes |
| `apps/api/app/src/test/resources/archunit.properties` | Crear | Comportamiento ante conjunto vacío |
| `.github/workflows/ci.yml` | Crear | `backend` + escaneo de dependencias |
| `.gitattributes` | Hecho | Creado el 2026-09-15 (commit `7ff7bd5`): `* text=auto eol=lf`, `mvnw text eol=lf`, `*.cmd text eol=crlf` y reglas binarias. Queda solo fijar el bit de ejecución de `mvnw` |

## Contrato de `APP_PROFILE`

| Valor | Efecto en este cambio |
|---|---|
| `admin`, `portal`, `worker` | Arranca su contexto, sin módulos de negocio |
| `migrate` | Reconocido; sin comportamiento hasta el cambio 5 |
| ausente o desconocido | Falla al arrancar, código distinto de cero |

## Estrategia de pruebas

| Nivel | Qué se prueba | Cómo |
|---|---|---|
| Unitario | Prueba de humo; resolución de `APP_PROFILE`, incluido el valor inválido | JUnit y AssertJ (`*Test.java`, Surefire) |
| Arquitectura | Cada regla se aplica y **rechaza** su fixture | ArchUnit y verificación de Spring Modulith |
| Construcción | Dependencia prohibida y `SNAPSHOT` rompen la construcción | `maven-enforcer-plugin` |
| Integración | Ninguna todavía | Sin Testcontainers: no hay esquema, Flyway ni jOOQ (cambio 5) y Docker aún no es requisito de construcción. Failsafe queda cableado para no tocar el POM después |

## Matriz de amenazas

| Frontera | Aplicabilidad | Respuesta de diseño | Prueba |
|---|---|---|---|
| Clasificación de archivo ejecutable | **Aplicable**: `mvnw` se compromete y la integración continua lo invoca como `./mvnw` en `ubuntu-latest`. el archivo se crea desde Windows: puede perder el bit de ejecución o llevar CRLF | `.gitattributes` ya creado con `mvnw text eol=lf` (commit `7ff7bd5`); falta fijar el bit de ejecución con `git update-index --chmod=+x mvnw` al agregar el wrapper; verificar que `.gitignore` no excluye `.mvn/wrapper/` | El propio trabajo `backend` falla si el wrapper no es ejecutable |
| Enrutado por `APP_PROFILE` | **Aplicable**: una variable de entorno selecciona el proceso | Lista cerrada de cuatro valores; falla cerrado ante valor ausente, vacío o desconocido | Prueba por valor válido y por valor inválido |
| Selección de repositorio Git | N/A: el cambio no ejecuta `git -C` ni resuelve rutas de repositorio |  |  |
| Estado de índice y de empuje | N/A: el cambio no automatiza `commit` ni `push` |  |  |
| Órdenes de pull request | N/A: el cambio no automatiza pull requests |  |  |

## Por confirmar durante la implementación

No se inventan aquí. Se confirman contra la documentación de cada herramienta antes de escribirlos:

1. Versiones y nombres de objetivo de `maven-enforcer-plugin`, Surefire, Failsafe y del complemento
   de ArchUnit.
2. Mecanismo exacto de la verificación de módulos de Spring Modulith 2 y su API.
3. Propiedad de ArchUnit que gobierna el conjunto vacío (`archRule.failOnEmptyShould` o equivalente)
   y su ubicación.
4. Forma de activación del perfil que prohíbe `SNAPSHOT` solo en `main`.
5. Coordenadas del BOM de Spring Boot 4.1 y compatibilidad real con Java 25 y ArchUnit.
6. Si Trivy en modo `fs` cubre las dependencias **resueltas** de Maven o hace falta la ruta de SBOM
   CycloneDX de `docs/03` §13.
7. Modo del Maven Wrapper (con JAR o solo script).

Si alguna de estas confirmaciones obliga a apartarse de lo ya decidido en un ADR, se eleva a un ADR
nuevo y no se entierra aquí (`docs/13-metodologia-sdd.md`, regla 5).

## Reversión

Descartar la rama y borrar `apps/api`, `.github/workflows/ci.yml` y `.gitattributes`. Sin base de
datos, esquema ni despliegue.

## Preguntas resueltas

- [x] **Nombre de la rama.** Gana `docs/15-flujo-de-trabajo-git.md` §1: `change/<id>`. No hay
      contradicción real con `CLAUDE.md`, que pide una rama «nombrada con el identificador del
      cambio» sin prohibir el espacio de nombres `change/`. La rama de trabajo se renombró a
      `change/maven-workspace-and-ci-skeleton` el 2026-09-15 y ningún documento necesita corrección.
      El disparador usa `change/**` y `main`.
- [x] **Disparador de integración continua.** Se dispara en empuje a `change/**` y a `main`, y
      también en `pull_request` hacia `main`, tal como declara `docs/06` §14.5. Que el flujo sea
      parcial no cambia su disparador: los trabajos que faltan se agregan en los cambios 3 y 11.
