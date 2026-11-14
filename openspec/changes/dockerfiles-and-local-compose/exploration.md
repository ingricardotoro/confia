# Exploración: imágenes de contenedor y Compose local (cambio 11a de F0)

- **Cambio:** `dockerfiles-and-local-compose`
- **Origen:** división del cambio 11 aprobada por el propietario el 2026-11-09
  (`foundations-plan/exploration.md`, nota «división del cambio 11»).
- **Exploración completa del cambio 11 y del 12:** Engram `sdd/containerization-and-cicd-pipeline/explore`.
  Este documento recorta esa evidencia al alcance de 11a.
- **Fecha:** 2026-11-09.

## 1. Estado actual

| Elemento | Estado | Evidencia |
|---|---|---|
| Dockerfile, `docker-compose*.yml`, `infra/`, `.env.example` | No existen | Árbol del repositorio; solo existe `.github/workflows/ci.yml` |
| Jar ejecutable | No existe: ningún `pom.xml` de `apps/api` declara `spring-boot-maven-plugin` | `apps/api/pom.xml`, `apps/api/app/pom.xml` |
| Perfil `migrate` | Reconocido pero vacío: termina con código 0 sin aplicar nada | `apps/api/app/src/main/java/com/confia/bootstrap/ConfiaApplication.java:96-99` |
| Flyway en tiempo de ejecución | `flyway-core`, `flyway-database-postgresql` y `spring-boot-flyway` ya son dependencias de `app` | `apps/api/app/pom.xml:72-88` |
| Actuator y rutas de salud | No existen; la lista blanca pública lo declara y obliga al cambio que las añada a editarla | `apps/api/app/src/main/java/com/confia/shared/web/edge/PublicEndpoints.java:20-21` |
| Ausencia de exportadores | Escenario vigente [BJ38]: ningún proceso contiene un `MeterRegistry`; deja de ser cierto con los cambios 10 y 11 | `openspec/specs/background-jobs/spec.md:704-718` |
| Trabajador | Arranca sin servidor web (`WebApplicationType.NONE`) | `ConfiaApplication.java:90-95` |
| Roles de PostgreSQL | Solo se crean por scripts de prueba y de generación de código | `apps/api/app/src/test/resources/db/testing/create-test-roles.sql`, `.../db/codegen/beforeMigrate__create_codegen_roles.sql` |
| Aplicaciones web | Existe `apps/admin-web`; **`apps/portal-web` no existe** | `apps/*/package.json` |
| Construcción del frontend en CI | No necesita JDK ni Docker: `packages/contracts` se genera desde instantáneas aprobadas | `.github/workflows/ci.yml:102-110` |

## 2. Obligaciones que recaen en 11a

1. **Una imagen, cuatro procesos** seleccionados por `APP_PROFILE` (`admin`, `portal`, `worker`, `migrate`),
   multietapa, JRE de la misma distribución de Java 25, usuario 10001, raíz de solo lectura con `tmpfs`,
   sin secretos en capas (`docs/05-infraestructura-y-despliegue.md:135-151`).
2. **`migrate` aplica Flyway con `confia_owner` y termina**; los procesos desplegados nunca migran
   (`docs/05:755-759`, `docs/05:1276-1280`, `docs/07-observabilidad-y-operaciones.md:807-808`).
3. **Sondas de vida y de preparación** separadas: la de vida no verifica nada externo; la de preparación
   no revela detalle a un cliente no autenticado y responde solo `{"status":...}` o `503`
   (`docs/07:729-771`). Son rutas públicas explícitas de la lista blanca (`docs/07:741-743`).
4. **Comprobación de salud declarada en cada servicio** de Compose; el comando debe validarse porque la
   imagen JRE puede no traer `wget` ni `curl` (`docs/05:203-209`, `docs/07:820-822`).
5. **Imagen web Vite + nginx sin privilegios** con `/healthz` (`docs/05:229-330`) y decisión pendiente de
   `read_only` para nginx de las aplicaciones web (`docs/05:729-731`).
6. **Medición de memoria** de los tres procesos de la JVM con los límites de Compose y ajuste de
   `JAVA_TOOL_OPTIONS` (`docs/09-roadmap-y-fases.md:71`, ADR-0013 línea 242, `docs/05:742-747`).
7. **Secretos fuera del repositorio** (regla 13 de `CLAUDE.md`): el ejemplo de `docs/05:359-360` con una
   contraseña literal no se copia; los valores viven en `.env`, ignorado por Git, y `.env.example` solo trae
   marcadores.

## 3. Hallazgo bloqueante del texto de `docs/05`

El Dockerfile de `docs/05:169-180` ejecuta `./mvnw package` dentro de `docker build`. **No funciona en este
repositorio:** la generación de código de jOOQ arranca PostgreSQL con Testcontainers en cada construcción
(ADR-0021), y `docker build` no tiene acceso a un demonio de Docker. El jar debe construirse fuera
(trabajo `backend` de CI o anfitrión) y la imagen de ejecución solo lo copia. Esto exige una nota fechada
en `docs/05` §3.2 y deja en §7 el «construir una vez, promover el mismo dígest» intacto.

## 4. Lo que no pertenece a 11a

Redis (11b), `cd.yml` y publicación con Trivy, SBOM, cosign, Renovate y fijación por dígest (11c),
OpenTofu, nginx de borde, Cloudflare, SSM, `cap_add` del nginx de borde (`docs/05:732-737`) y
`proxy_read_timeout` (`docs/05:1477`, riesgo R3 de `admin-web-foundations-and-session`) (11d), MinIO y el
ancla externa (11e), recorridos reales de extremo a extremo (11f), y el resto del cambio 10: registro,
trazas, métricas, indicadores `redis`, `objectStorage`, `backgroundJobs` y `migrations` (`docs/07:766-770`).

**Semilla de datos ficticios** (entregable 11 de F0, `docs/09:239`; `docs/05:24`): ningún cambio de la
tabla de F0 la tiene asignada (el cambio 8 solo trae la «semilla mínima» de roles y permisos). En F0 no
existen todavía datos de negocio que sembrar y la primera cuenta de personal es del cambio 8b. Queda como
brecha sin dueño, a decidir por el propietario.

## 5. Riesgos

| Riesgo | Evidencia | Nota |
|---|---|---|
| Actuator trae una autoconfiguración de métricas y aparece un `MeterRegistry`, lo que rompe [BJ38] y adelanta el cambio 10 | `background-jobs/spec.md:704-718`; Spring Boot 4 separó la autoconfiguración en módulos (`apps/api/app/pom.xml:79-84`) | Verificar si basta el módulo de salud sin el de métricas; si no, excluir la autoconfiguración de métricas y enmendar [BJ38] con delta |
| Nuevas rutas públicas debilitan la cadena de seguridad o filtran detalle | `PublicEndpoints.java:20-21`, `web-edge/spec.md:88-107` | Solo dos rutas, `show-details: never`, prueba de la lista cerrada |
| El trabajador no tiene servidor web, así que no hay ruta HTTP de salud | `ConfiaApplication.java:90-95` | Punto abierto técnico (sección 6) |
| La imagen JRE no trae cliente HTTP para la sonda | `docs/05:205-207` | Validar; no se añade herramienta sin revisión |
| La memoria medida no cabe en los límites de partida (512 MB a 1 GB) | `docs/05:580-648`, ADR-0013 línea 242 | Escalar según ADR-0013 antes de 11d |
| `noexec` en `/tmp` rompe alguna biblioteca con código nativo | `docs/05:739-740` | Directorio temporal dedicado, nunca quitar `noexec` |
| Roles locales creados con un script que se confunda con producción | Scripts actuales solo en `src/test` | Script de inicialización exclusivo de Compose local, con contraseñas desde `.env` |
| Rutas divergentes en los documentos (`infra/docker/api.Dockerfile` y `infra/docker/compose.prod.yml` frente a `docker-compose.yml` en la raíz) | `docs/05:159`, `docs/05:341`, `docs/07:816` | Se fija en diseño y se anota en ambos documentos |

## 6. Puntos técnicos abiertos (para diseño, no del propietario)

1. Salud del trabajador: puerto de gestión propio solo en el trabajador, sonda por comando dentro del
   contenedor, o ninguna comprobación HTTP y solo reinicio por terminación de la JVM.
2. Mecanismo de la sonda de la API si la imagen JRE no trae `wget` ni `curl`.
3. Contenido del grupo de preparación en 11a: `readinessState` y `db`; los demás indicadores son del
   cambio 10 o de 11b.
4. Si la imagen web se parametriza por aplicación (argumento de construcción) para que `apps/portal-web`
   la reutilice cuando exista.
5. Si CI levanta la pila con Compose como prueba de humo o se limita a `docker compose config`, Hadolint y
   la construcción de las imágenes; coste en minutos frente al presupuesto del trabajo `backend`.
6. `read_only` de nginx de las aplicaciones web: validar caché y `pid` en rutas temporales del usuario 10001.
