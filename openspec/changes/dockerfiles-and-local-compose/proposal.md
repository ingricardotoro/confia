# Propuesta: imágenes de contenedor y Compose local (cambio 11a de F0)

- **Cambio:** `dockerfiles-and-local-compose`
- **Fase del roadmap:** F0, cambio 11a, primera de las seis partes del cambio 11 (entregable 8 parcial y la
  medición de memoria del entregable 1). División aprobada por el propietario el 2026-11-09
  (`foundations-plan/exploration.md`, nota «división del cambio 11»).
- **Depende de:** cambios 1 y 3 (archivados). Incluye él mismo el subconjunto mínimo de salud del cambio 10.
- **Lo necesitan:** 11b `redis-shared-rate-limiting`, 11c `image-supply-chain-and-publishing`,
  11e `audit-chain-external-anchor` y 11f `real-stack-e2e-journeys`.
- **Exploración:** `openspec/changes/dockerfiles-and-local-compose/exploration.md` (Engram
  `sdd/containerization-and-cicd-pipeline/explore`).
- **Estado:** aprobada por el propietario el 2026-11-09.

## Intención

CONFIA no tiene hoy ninguna forma de ejecutarse como contenedor: no hay jar ejecutable, ni Dockerfile, ni
Compose, ni rutas de salud, y el perfil `migrate` termina sin aplicar ninguna migración
(`ConfiaApplication.java:96-99`). Sin eso no hay pila local reproducible, no se puede medir la memoria real
de los tres procesos de la JVM que exige F0 (`docs/09:71`, ADR-0013) y ningún cambio posterior del 11
(Redis, publicación, preproducción, ancla externa, recorridos reales) tiene sobre qué construirse.

**Por qué ahora:** el cambio 11 es el siguiente bloque de F0 y 11a es la base de las otras cinco partes.

**Cómo se ve el éxito:** con un `.env` local creado desde `.env.example`, `docker compose up` levanta
PostgreSQL, aplica las migraciones con `migrate` y termina, y arranca administración, portal, trabajador y
`admin-web`, todos sanos según su comprobación declarada; las rutas de salud responden sin revelar detalle;
CI rechaza un Dockerfile o un Compose inválidos; y la memoria medida de cada proceso queda documentada
contra su límite.

## Problema

1. **El Dockerfile documentado no puede funcionar.** `docs/05` §3.2 ejecuta `./mvnw package` dentro de
   `docker build`, pero la generación de código de jOOQ necesita un demonio de Docker para Testcontainers
   (ADR-0021). El jar debe construirse fuera y la imagen solo copiarlo.
2. **No hay salud sin Actuator**, y Actuator es del cambio 10, que no ha empezado. Por decisión del
   propietario, 11a trae solo el subconjunto mínimo.
3. **Las rutas públicas son una lista cerrada** (`PublicEndpoints.java:20-21`, `web-edge`): añadir salud
   obliga a editarla y a probarla.
4. **Actuator puede introducir un `MeterRegistry`**, lo que contradice el escenario vigente [BJ38] de
   `background-jobs` y adelantaría el cambio 10.

## Alcance

### Dentro de alcance

**Artefacto y procesos**

1. Empaquetado del jar ejecutable con `spring-boot-maven-plugin` y nombre fijo (`confia-api.jar`), con el
   lanzador actual que lee `APP_PROFILE`.
2. Perfil `migrate` real: aplica las migraciones de Flyway conectado como `confia_owner` y termina con
   código 0; ante cualquier fallo termina con código distinto de 0. Ningún otro perfil migra.

**Salud mínima (subconjunto del cambio 10)**

3. Spring Boot Actuator solo con salud: grupos de vida (`livenessState`, sin dependencias externas) y de
   preparación (`readinessState` y `db`) expuestos como `/health/live` y `/health/ready`, con
   `show-details: never`; ningún otro endpoint de Actuator expuesto y ningún `MeterRegistry` en ningún
   proceso.
4. Lista blanca pública de administración y de portal ampliada exactamente con esas dos rutas, con su
   prueba de lista cerrada; respuesta sin desglose ni mensaje del controlador de base de datos.
5. Mecanismo de salud del trabajador, que hoy no tiene servidor web (punto técnico para diseño).

**Imágenes**

6. Dockerfile de ejecución de la API: copia el jar construido fuera, JRE de Java 25 de la misma
   distribución que el JDK de CI, usuario 10001, preparado para raíz de solo lectura con `/tmp` en `tmpfs`
   `noexec,nosuid`, `JAVA_TOOL_OPTIONS` con `MaxRAMPercentage`, sonda validada sin añadir herramientas sin
   revisión. Imagen base por etiqueta exacta; la fijación por dígest es de 11c junto con Renovate.
7. Dockerfile de `apps/admin-web`: etapa de Node que construye con Vite desde las instantáneas aprobadas
   de `packages/contracts`, y etapa nginx sin privilegios con `/healthz`. Se parametriza por aplicación
   para que `apps/portal-web` la reutilice.
8. Decisión registrada sobre `read_only` del nginx de las aplicaciones web (`docs/05:729-731`): se valida y
   se activa, o se documenta la excepción con su motivo.

**Compose y configuración**

9. `docker-compose.yml` local con `postgres`, `migrate` (servicio de una sola ejecución del que dependen los
   demás), `api-admin`, `api-portal`, `worker` y `admin-web`, cada uno con su comprobación de salud y
   `depends_on` por condición; puertos solo en `127.0.0.1`.
10. Script de inicialización de roles de PostgreSQL exclusivo de Compose local, con contraseñas leídas del
    entorno.
11. *Overlay* de producción (`docker-compose.prod.yml`) con endurecimiento (`x-jvm-hardening`,
    `x-web-hardening`: usuario 10001, `no-new-privileges`, `cap_drop: [ALL]`, `read_only`), límites de
    memoria y sin `postgres` (RDS). Solo se valida con `docker compose config`; su uso real es de 11d.
12. `.env.example` sin ningún secreto real (solo marcadores) y `.env` en `.gitignore`.

**Integración continua y medición**

13. Hadolint sobre los Dockerfiles y `docker compose config` sobre la base y el *overlay*, como trabajos que
    rompen la construcción.
14. Medición del consumo de memoria de administración, portal y trabajador con los límites de Compose; los
    límites y `JAVA_TOOL_OPTIONS` se ajustan con el resultado y la medición se documenta.

**Documentación**

15. Notas fechadas, sin reescribir cuerpos: `docs/05` §3.2 (el jar se construye fuera de `docker build`),
    §3.3, §4, §5 y las rutas de archivo elegidas; `docs/07` §9 (subconjunto entregado y lo que queda en el
    cambio 10); `docs/09` (medición de memoria del entregable 1); `foundations-plan/exploration.md`.

### Fuera de alcance, con dueño nombrado

| Exclusión | Dueño |
|---|---|
| Redis, su servicio en Compose y el limitador compartido | 11b `redis-shared-rate-limiting` |
| `cd.yml`, publicación de imágenes, Trivy sobre imágenes, SBOM, cosign y SLSA, Renovate, fijación por dígest (incluido `confia.postgres.image`) | 11c `image-supply-chain-and-publishing` |
| AWS, OpenTofu, preproducción, SSM con OIDC, ADR del gestor de secretos, calibración de Argon2id | 11d `preprod-environment-and-deploy` |
| nginx de borde, Cloudflare, `cap_add` o puertos altos del borde (`docs/05:732-737`), `proxy_read_timeout` (riesgo R3 de `admin-web-foundations-and-session`) | 11d |
| MinIO, S3 y el ancla externa de la cadena de auditoría | 11e `audit-chain-external-anchor` |
| Recorridos de extremo a extremo contra la pila real | 11f `real-stack-e2e-journeys` (depende también del cambio 8b) |
| Registro estructurado, trazas, métricas, Prometheus, sonda `/health/startup` e indicadores `redis`, `objectStorage`, `backgroundJobs` y `migrations` | Cambio 10 `structured-logging-and-tracing` (el indicador `redis`, con 11b) |
| Imagen de `apps/portal-web` | El cambio que cree `apps/portal-web`: añade su servicio a Compose reutilizando el Dockerfile web parametrizado de este cambio. La aplicación no existe y no se construye una imagen vacía |
| Semilla de datos ficticios (entregable 11 de F0) | **Sin dueño.** Decisión del propietario (ver preguntas abiertas) |
| Respaldo y restauración | Cambio 12 `backup-restore-and-drill` |

## Capacidades

> Contrato con `/sdd-spec`. Investigado contra `openspec/specs/` (doce capacidades publicadas:
> `audit-trail`, `background-jobs`, `build-integrity`, `cashbox`, `design-system`, `identity`, `invoicing`,
> `ledger`, `money`, `organization`, `payments`, `web-edge`).

### Nuevas

- **`container-runtime`:** contrato de ejecución en contenedores: una imagen y cuatro perfiles, `migrate`
  que aplica y termina, sondas de vida y de preparación y su semántica, endurecimiento de imágenes y de
  Compose, ausencia de secretos en imágenes y en el repositorio, y medición de memoria contra límites.

### Modificadas

- **`build-integrity`:** jar ejecutable como artefacto de la construcción; puertas de CI de Hadolint y de
  `docker compose config`.
- **`web-edge`:** la lista blanca pública de administración y de portal incluye `/health/live` y
  `/health/ready`, sin desglose; el resto de rutas de Actuator no existe o se deniega.
- **`background-jobs`:** el escenario [BJ38] se ajusta: Actuator existe para salud, pero ningún proceso
  contiene un `MeterRegistry` hasta el cambio 10.

## Enfoque

1. **Jar fuera, imagen delgada.** El trabajo `backend` de CI (o el anfitrión en local) produce el jar con
   la generación de jOOQ y las pruebas; el Dockerfile de ejecución solo lo copia. Se conserva una imagen
   para los cuatro perfiles.
2. **Salud mínima y cerrada.** Solo el módulo de salud de Actuator que exija Spring Boot 4.1, sin
   autoconfiguración de métricas; prueba que afirma la ausencia de `MeterRegistry` y de cualquier ruta de
   Actuator fuera de las dos públicas.
3. **`migrate` como servicio de una sola ejecución** en Compose, del que dependen los tres procesos con
   `service_completed_successfully`.
4. **Secretos solo por entorno:** `.env` local ignorado, marcadores en `.env.example`, ningún literal de
   contraseña copiado de `docs/05`.
5. **Pruebas primero** donde existe ejecutor determinista (`openspec/config.yaml`): `migrate` aplica y
   termina, rutas de salud y lista cerrada, ausencia de `MeterRegistry`, empaquetado. Para Dockerfiles y
   Compose, las comprobaciones son Hadolint, `docker compose config` y el arranque real local.

## Áreas afectadas

| Área | Cambio |
|---|---|
| `apps/api/pom.xml`, `apps/api/app/pom.xml` | `spring-boot-maven-plugin`, nombre fijo del jar, dependencia de salud de Actuator |
| `apps/api/app/src/main/java/com/confia/bootstrap/ConfiaApplication.java` | Perfil `migrate` real |
| `apps/api/app/src/main/java/com/confia/shared/web/edge/PublicEndpoints.java` y la cadena del portal | Rutas de salud en la lista blanca |
| `apps/api/app/src/main/resources/application*.yml` | Grupos de salud y exposición de Actuator |
| Dockerfiles (ruta fijada en diseño, `infra/docker/` según `docs/05`) | Nuevos: API y web |
| `docker-compose.yml`, `docker-compose.prod.yml`, script de roles local, `.env.example`, `.gitignore` | Nuevos o ampliados |
| `.github/workflows/ci.yml` | Hadolint y `docker compose config` |
| Pruebas | `migrate`, salud, lista cerrada, ausencia de `MeterRegistry` ([BJ38]), empaquetado |
| Documentación | `docs/05`, `docs/07`, `docs/09`, `foundations-plan/exploration.md` |

## Riesgos

| Riesgo | Probabilidad | Mitigación |
|---|---|---|
| Actuator activa métricas y un `MeterRegistry` | Media | Verificar la modularización de Spring Boot 4.1 primero; excluir la autoconfiguración de métricas; prueba de ausencia |
| Las rutas de salud filtran detalle de infraestructura | Baja | `show-details: never`, prueba del cuerpo de respuesta ante PostgreSQL caído |
| La imagen JRE no trae cliente HTTP para la sonda | Media | Validar en la primera tarea de imagen; alternativa revisada en diseño |
| El trabajador no tiene ruta HTTP de salud | Cierta | Decisión técnica de diseño entre puerto de gestión propio, sonda por comando o solo reinicio por terminación |
| La memoria medida supera los límites de partida | Media | Se ajustan los límites; si la topología no alcanza, se escala según ADR-0013 antes de 11d |
| `noexec` en `/tmp` rompe una biblioteca nativa | Baja | Directorio temporal dedicado, nunca retirar `noexec` |
| Un secreto de ejemplo termina en el repositorio | Baja | Solo marcadores en `.env.example`; revisión de `security` en CI |
| Tamaño: 15 tareas es el límite exacto | Alta | Ver pronóstico abajo; corte propuesto si el plan de tareas lo supera |

## Plan de reversión

No existe ningún entorno desplegado. Cada PR se revierte con su commit de fusión en orden inverso. Revertir
la salud exige revertir antes los `healthcheck` de Compose que la consumen. Revertir `migrate` devuelve el
perfil a su estado vacío sin efecto sobre datos (ninguna migración cambia). Los archivos de contenedor y de
Compose son nuevos y se eliminan sin tocar código de negocio. Las notas documentales se revierten con su PR.

## Dependencias

- Docker con Compose v2 en local y en CI (ya requerido por Testcontainers).
- Imágenes base: JRE de Java 25 (Eclipse Temurin u otra distribución OpenJDK igual a la de CI), Node para
  la construcción web, nginx sin privilegios y PostgreSQL 18.
- Hadolint como herramienta de CI (licencia GPL-3.0, solo se ejecuta; no se distribuye).

## Criterios de éxito

- [ ] `./mvnw verify` en `apps/api` en verde, con cobertura y mutación en sus umbrales.
- [ ] `APP_PROFILE=migrate` aplica todas las migraciones como `confia_owner` y termina con 0; ante fallo,
      con distinto de 0; ningún otro perfil migra.
- [ ] `/health/live` y `/health/ready` responden `200` o `503` solo con `{"status":...}`; con PostgreSQL
      caído, la vida sigue en `200` y la preparación pasa a `503`.
- [ ] La prueba de la lista blanca cerrada pasa con exactamente las dos rutas nuevas; ninguna otra ruta de
      Actuator responde; ningún proceso contiene un `MeterRegistry`.
- [ ] `docker compose up` con un `.env` desde `.env.example` deja sanos los cinco servicios de larga vida y
      `migrate` termina con éxito.
- [ ] Hadolint y `docker compose config` (base y *overlay*) corren en CI y rompen la construcción ante un
      error.
- [ ] Ningún secreto real en el repositorio ni en una capa de imagen.
- [ ] La memoria de los tres procesos queda medida y documentada contra sus límites.

## Pronóstico de tamaño

Estimación: **de 13 a 15 tareas**, en el límite de la regla de quince. Si `tasks.md` lo supera, el corte
propuesto es sacar a un cambio **11a-2** la medición de memoria y el *overlay* de producción
(puntos 11 y 14), que no bloquean a 11b, 11c ni 11e, dejando en 11a el artefacto, la salud, las imágenes,
Compose local y las puertas de CI.

## Preguntas abiertas para el propietario

1. **Semilla de datos ficticios (entregable 11 de F0).** No tiene dueño en la tabla de F0 y no se incluye
   aquí, porque en F0 aún no existen datos de negocio y la primera cuenta de personal es del cambio 8b. Hace
   falta asignarle un cambio (por ejemplo uno propio después de 8b, o dentro de 11f).
