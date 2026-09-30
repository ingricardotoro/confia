# CONFIA — Infraestructura y despliegue

> Estado: **Propuesta v1.0** — pendiente de aprobación del propietario del producto.
> Coherente con `docs/01-arquitectura.md`, sección 9 (contenerización) y sección 5
> (separación administración/portal). Decisión estructural registrada en
> `docs/adr/ADR-0012-contenerizacion.md` y en
> `docs/adr/ADR-0014-proveedor-de-nube-aws-y-portabilidad.md` (proveedor de nube AWS, política
> de portabilidad y topología por fase). Donde este documento contradiga esos ADR, el ADR es la
> autoridad vigente.

---

## 1. Entornos

| Entorno | Propósito | Dónde vive |
|---|---|---|
| Local | Desarrollo diario de un solo desarrollador | La máquina del desarrollador, `docker compose up`, con MinIO como sustituto de S3 |
| Preproducción | Validar un cambio con datos realistas antes de promoverlo, incluida la validación fiscal del contador (fase F5) | AWS `us-east-1`, misma forma que producción y a menor escala: instancia EC2 `t4g.medium` y RDS `db.t4g.micro` (ADR-0014) |
| Producción | Operación real con dinero y datos de menores reales | AWS `us-east-1`: instancia o instancias EC2 descritas en la sección 2, RDS for PostgreSQL, S3, según `docs/01-arquitectura.md`, sección 5, y `docs/adr/ADR-0014-proveedor-de-nube-aws-y-portabilidad.md` |

### Qué difiere entre entornos

- Variables de entorno y secretos (sección 6).
- Volumen de datos: local usa datos ficticios de semilla; preproducción usa una copia
  anonimizada o un subconjunto realista; producción usa datos reales.
- Recursos asignados a cada contenedor (`docker-compose.prod.yml`, sección 3.5).
- Presencia de herramientas de desarrollo: MailHog y cualquier interfaz de administración de
  base de datos existen **solo** en local.
- **PostgreSQL y el almacenamiento de objetos.** Local y las pruebas de integración usan
  PostgreSQL y MinIO contenerizados. Preproducción y producción usan RDS for PostgreSQL y S3,
  servicios gestionados de AWS (ADR-0014); ninguno de los dos corre en contenedor fuera de local
  y pruebas.
- Nivel de acceso de red: producción y preproducción están detrás del borde nginx con TLS real;
  local expone los puertos directamente en `localhost`.

### Qué NUNCA difiere entre entornos

- **La imagen.** La misma imagen construida una sola vez pasa por preproducción y se promueve
  a producción por digest (sección 7). Nunca se reconstruye para producción.
- **La versión de PostgreSQL, Redis y de la JVM de Java 25.** Fijadas por digest en los tres
  entornos.
- **Las migraciones aplicadas.** El mismo conjunto de migraciones, en el mismo orden, aplicado
  primero en preproducción y después en producción.
- **La estructura del `docker-compose*.yml`.** Producción es un *overlay* sobre la base común,
  no un archivo distinto con servicios inventados.
- **Las reglas de seguridad a nivel de fila y los roles de PostgreSQL** descritos en
  `docs/01-arquitectura.md`, sección 5.

---

## 2. Topología de despliegue por fase

Coherente con `docs/01-arquitectura.md`, sección 5, y con
`docs/adr/ADR-0014-proveedor-de-nube-aws-y-portabilidad.md`. Proveedor: AWS, región `us-east-1`.
La base de datos gestionada (RDS for PostgreSQL) reemplaza al host de datos autogestionado de las
versiones anteriores de este documento; el almacenamiento de objetos es S3.

### Fase uno (F0 a F7, presupuesto mínimo)

```
                 Internet
                    |
        [ Cloudflare: DNS, WAF, TLS, proteccion DDoS ]
                    |
        +-----------+------------+
        |                        |
   admin.confia.example    (portal aun no expuesto,
   (acceso restringido      F8 lo activa)
    por IP y MFA)
        |
   +----+-----------------------------+
   |  Instancia EC2 (subred publica)  |
   |  nginx de borde + apps           |
   |  +------------+  +------------+  |
   |  | api-admin  |  |  worker    |  |
   |  +-----+------+  +-----+------+  |
   |  api-portal (sin exponer), Redis,|
   |  admin-web, portal-web           |
   +--------+----------------+--------+
            |  grupo de seguridad:    |
            |  solo HTTPS desde       |
            |  rangos de Cloudflare   |
   +--------+-----------------+-------+
   |  RDS for PostgreSQL (subred      |
   |  privada, sin IP publica)        |
   +-----------------------------------+
```

Sin puerto SSH abierto: el acceso administrativo a la instancia es por AWS Systems Manager
Session Manager. S3 sustituye a MinIO fuera de local. Sin NAT Gateway: la instancia está en
subred pública y RDS no necesita salida a internet.

### Fase dos (F8 en adelante, portal con tráfico real)

```
                 Internet
                    |
        [ Cloudflare: DNS, WAF, TLS, proteccion DDoS ]
                    |
        +-----------+------------+
        |                        |
   admin.confia.example    portal.confia.example
   (acceso restringido      (publico, limite de
    por IP y MFA)            tasa estricto)
        |                        |
   Instancia EC2 A          Instancia EC2 C
   (api-admin, worker,      (api-portal,
   nginx, Redis)            nginx propio)
        |                        |
        +-----------+------------+
                     |  grupo de seguridad,
                     |  solo trafico interno
        +------------+-------------+
        |  RDS for PostgreSQL      |
        |  (escritura + replica    |
        |  de lectura, subred      |
        |  privada, sin IP publica)|
        +---------------------------+
```

Regla permanente heredada de `docs/01-arquitectura.md`: PostgreSQL nunca tiene puerto público,
ni en desarrollo compartido ni de forma temporal. En AWS esto se aplica con RDS en subred
privada, sin acceso público, y un grupo de seguridad que solo admite conexiones desde el grupo
de seguridad de las instancias de aplicación.

### Preproducción

Misma forma que la fase uno, a menor escala: instancia EC2 `t4g.medium` y RDS `db.t4g.micro`
(ADR-0014), con `docker-compose.yml` + `docker-compose.prod.yml` idéntico al de producción.

---

## 3. Estrategia de contenerización

### 3.1 Reglas de construcción

1. **Una sola imagen de aplicación para los tres procesos.** `confia-api-admin`,
   `confia-api-portal` y `confia-worker` son el mismo artefacto con distinto punto de entrada,
   seleccionado por la variable `APP_PROFILE` (`admin`, `portal`, `worker` o `migrate`).
2. **Construcción multietapa**: etapa de compilación con el JDK y el Maven Wrapper, y etapa
   final mínima sobre un JRE de la misma distribución de Java 25, con solo el artefacto
   construido.
3. **Imagen base fijada por digest**, nunca por etiqueta móvil como `latest` o `25-jre`.
4. **Usuario sin privilegios de superusuario** (usuario dedicado `confia` con UID 10001) y
   **sistema de archivos raíz de solo lectura**, con volúmenes temporales explícitos
   (`tmpfs`) para lo que el proceso necesite escribir en ejecución.
5. **Comprobación de salud declarada** en cada servicio, que verifique dependencias reales
   (conexión a PostgreSQL, a Redis) y no solo que el proceso responde.
6. **Sin secretos horneados en ninguna capa.** Se inyectan como variables de entorno en tiempo
   de ejecución.
7. **Construir una vez, promover el mismo artefacto** (sección 7).
8. **Escaneo obligatorio con Trivy** antes de publicar. Vulnerabilidades altas o críticas
   bloquean la publicación.

### 3.2 Dockerfile de la API (Java 25 con Spring Boot, Maven Wrapper)

```dockerfile
# syntax=docker/dockerfile:1.7
# infra/docker/api.Dockerfile

# PLACEHOLDER DIGESTS. Resolve the real values before the first build (see note below).
# JDK and JRE must come from the same Java 25 distribution (for example Eclipse Temurin).
ARG JDK_IMAGE=eclipse-temurin:25-jdk@sha256:0000000000000000000000000000000000000000000000000000000000000000
ARG JRE_IMAGE=eclipse-temurin:25-jre@sha256:0000000000000000000000000000000000000000000000000000000000000000

########################################
# Stage 1: build with the Maven Wrapper
########################################
FROM ${JDK_IMAGE} AS build
WORKDIR /workspace

# Only the backend is copied. The API image never needs the frontend or pnpm.
COPY apps/api/ ./

# The local Maven repository lives in a BuildKit cache mount, so dependencies are
# reused across builds without being baked into any layer.
# Tests are not repeated here: `./mvnw verify` already ran them in ci.yml before this
# image is built (section 7).
RUN --mount=type=cache,id=maven-repository,target=/root/.m2/repository \
    ./mvnw --batch-mode package -DskipTests

########################################
# Stage 2: minimal runtime on a JRE
########################################
FROM ${JRE_IMAGE} AS runtime

RUN groupadd --gid 10001 confia \
    && useradd --uid 10001 --gid confia --shell /usr/sbin/nologin --no-create-home confia

# Container-aware heap sizing: the JVM derives its maximum heap from the container
# memory limit declared in docker-compose.prod.yml. The real footprint of each
# process is measured in F0 (ADR-0013) and these values are adjusted accordingly.
# /tmp is mounted as tmpfs so the root filesystem can stay read-only.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -Djava.io.tmpdir=/tmp"

WORKDIR /app
# The parent POM fixes the final artifact name, so this path does not depend on the version.
COPY --from=build /workspace/app/target/confia-api.jar /app/confia-api.jar

USER confia
EXPOSE 3000

# Probe: Spring Boot Actuator liveness group, exposed as /health/live
# (docs/07-observabilidad-y-operaciones.md, section 9).
# The exact command MUST be validated in F0: the JRE runtime image may not ship
# wget or curl. If it does not, the probe is replaced by a mechanism validated
# in F0; no tool is added to the image only for this purpose without review.
HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=3 \
    CMD wget -q --spider http://127.0.0.1:3000/health/live || exit 1

# A single launcher inside the artifact reads APP_PROFILE (admin, portal, worker or
# migrate) and starts the corresponding Spring application. Each application declares
# explicitly which modules it loads; the portal application never registers
# administrative controllers (ADR-0003). `migrate` applies Flyway migrations and exits.
ENTRYPOINT ["java", "-jar", "/app/confia-api.jar"]
```

> Los digests anteriores son marcadores de formato, no valores reales. Antes de construir la
> primera imagen real, se resuelve el digest vigente de cada imagen con
> `docker pull eclipse-temurin:25-jre && docker inspect --format='{{index .RepoDigests 0}}' eclipse-temurin:25-jre`
> (y lo mismo para la imagen JDK), y se fija ese valor exacto en el `ARG`. La distribución, la
> etiqueta exacta y el nombre final del artefacto (`confia-api.jar`) se confirman en F0. La
> versión de Java de estas imágenes debe coincidir con la declarada en el `pom.xml` padre y en la
> matriz de integración continua (ADR-0013, verificación 2).
>
> El mecanismo interno del lanzador que lee `APP_PROFILE` se define en F0; este documento solo
> fija el contrato: una variable, cuatro valores, un artefacto.

### 3.3 Dockerfile de las aplicaciones web (Vite + nginx)

Las aplicaciones web se siguen construyendo con Node, porque son estáticas de Vite; esta imagen
no ejecuta Java. `packages/contracts` es código generado: el pipeline genera primero el OpenAPI
desde el backend y regenera `packages/contracts` con orval, y solo después construye esta imagen,
de modo que el contexto de construcción ya contiene el contrato vigente. El orden obligatorio es:
backend y OpenAPI, luego generación de `packages/contracts`, luego construcción web (sección 7).

> **Nota editorial, 2026-09-30 (`frontend-monorepo-and-contracts-pipeline`).** La versión de Node pasa
> de 22 a **24**, la LTS activa, fijada en un solo lugar: `engines` del `package.json` raíz, con
> `.nvmrc` para las máquinas de desarrollo y la integración continua. La imagen de abajo ya no lleva
> dígest: el que tenía correspondía a la imagen de Node 22 y no se había verificado contra la imagen
> publicada. El dígest se fija en el mismo cambio que introduzca Renovate, igual que el de `postgres:18-alpine` (pendiente heredado del
> cambio 5 en `docs/09-roadmap-y-fases.md`). La versión de pnpm sale de `packageManager`.

```dockerfile
# infra/docker/web.Dockerfile
# syntax=docker/dockerfile:1.7

ARG NODE_IMAGE=node:24.21.0-bookworm-slim
ARG NGINX_IMAGE=nginx:1.27.2-alpine@sha256:2ae06ebd39899cf5d5a09d4e12c48a5a6a6a5fa6f9e3d5b4e9f8a1b2c3d4e5f6

# APP_DIR distingue admin-web de portal-web. Se construye una imagen por app,
# reutilizando el mismo Dockerfile con --build-arg.
ARG APP_DIR=admin-web

########################################
# Etapa 1: construccion estatica
########################################
FROM ${NODE_IMAGE} AS build
ARG APP_DIR
WORKDIR /workspace

RUN corepack enable && corepack prepare pnpm@9 --activate

COPY pnpm-workspace.yaml package.json pnpm-lock.yaml .npmrc* ./
COPY apps/${APP_DIR}/package.json apps/${APP_DIR}/package.json
COPY packages/ui/package.json packages/ui/package.json
COPY packages/contracts/package.json packages/contracts/package.json
COPY packages/config/package.json packages/config/package.json

RUN --mount=type=cache,id=pnpm-store,target=/root/.local/share/pnpm/store \
    pnpm install --frozen-lockfile

COPY . .
RUN pnpm turbo run build --filter=@confia/${APP_DIR}...

########################################
# Etapa 2: nginx sirviendo estatico
########################################
FROM ${NGINX_IMAGE} AS runtime
ARG APP_DIR

RUN addgroup -g 10001 confia \
    && adduser -D -H -u 10001 -G confia confia \
    && rm -rf /usr/share/nginx/html/* \
    && touch /var/run/nginx.pid \
    && chown -R confia:confia /var/cache/nginx /var/run/nginx.pid /etc/nginx

COPY infra/docker/web.nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build --chown=confia:confia /workspace/apps/${APP_DIR}/dist /usr/share/nginx/html

USER confia
EXPOSE 8080

HEALTHCHECK --interval=15s --timeout=3s --start-period=10s --retries=3 \
    CMD wget --spider -q http://127.0.0.1:8080/healthz || exit 1

CMD ["nginx", "-g", "daemon off;"]
```

```nginx
# infra/docker/web.nginx.conf
# nginx interno del contenedor. El TLS y las cabeceras de seguridad completas
# las agrega el nginx de borde (seccion 9); esta configuracion asume
# ejecucion detras de un proxy de confianza.

server {
    listen 8080;
    server_name _;

    root /usr/share/nginx/html;
    index index.html;

    location /healthz {
        access_log off;
        return 200 "ok\n";
        add_header Content-Type text/plain;
    }

    location / {
        try_files $uri $uri/ /index.html;
        add_header Cache-Control "no-store" always;
    }

    location ~* \.(js|css|woff2|png|svg)$ {
        add_header Cache-Control "public, max-age=31536000, immutable" always;
    }
}
```

---

## 4. `docker-compose.yml` (desarrollo local)

Esta base incluye `postgres` y `minio` porque **solo se usan en desarrollo local y en las
pruebas de integración con Testcontainers**. En preproducción y en producción, PostgreSQL es RDS
for PostgreSQL y el almacenamiento de objetos es S3, ambos fuera de Docker Compose
(`docs/adr/ADR-0014-proveedor-de-nube-aws-y-portabilidad.md`); la sección 5 documenta cómo el
*overlay* de producción omite esos dos servicios.

```yaml
# docker-compose.yml
name: confia

networks:
  edge:
  data:

volumes:
  postgres-data:
  redis-data:
  minio-data:

services:
  postgres:
    # PostgreSQL 18 in every environment (ADR-0015). Placeholder digest: resolve it before use.
    image: postgres:18-bookworm@sha256:0000000000000000000000000000000000000000000000000000000000000000
    restart: unless-stopped
    environment:
      POSTGRES_USER: confia
      POSTGRES_PASSWORD: confia_dev_password
      POSTGRES_DB: confia
    volumes:
      - postgres-data:/var/lib/postgresql/data
    ports:
      - "127.0.0.1:5432:5432"
    networks: [data]
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U confia -d confia"]
      interval: 5s
      timeout: 3s
      retries: 10

  redis:
    image: redis:7.4.1-bookworm@sha256:9c1a3e5b7d9f1a3c5e7b9d1f3a5c7e9b1d3f5a7c9e1b3d5f7a9c1e3b5d7f9a1c
    restart: unless-stopped
    command: ["redis-server", "--appendonly", "yes"]
    volumes:
      - redis-data:/data
    ports:
      - "127.0.0.1:6379:6379"
    networks: [data]
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]
      interval: 5s
      timeout: 3s
      retries: 10

  minio:
    image: minio/minio:RELEASE.2024-10-13T13-34-11Z@sha256:8a4c2e6b0d4f8a2c6e0b4d8f2a6c0e4b8d2f6a0c4e8b2d6f0a4c8e2b6d0f4a8c
    restart: unless-stopped
    command: ["server", "/data", "--console-address", ":9001"]
    environment:
      MINIO_ROOT_USER: confia
      MINIO_ROOT_PASSWORD: confia_dev_password
    volumes:
      - minio-data:/data
    ports:
      - "127.0.0.1:9000:9000"
      - "127.0.0.1:9001:9001"
    networks: [data]
    healthcheck:
      test: ["CMD", "mc", "ready", "local"]
      interval: 5s
      timeout: 3s
      retries: 10

  mailhog:
    image: mailhog/mailhog:v1.0.1@sha256:8d76a3d4ffa32a3661311944007a415e40647a838eb2718ca0dc9a880aad0b9
    restart: unless-stopped
    ports:
      - "127.0.0.1:8025:8025"
      - "127.0.0.1:1025:1025"
    networks: [data]

  api-admin:
    build:
      context: .
      dockerfile: infra/docker/api.Dockerfile
    restart: unless-stopped
    env_file: .env
    environment:
      APP_PROFILE: admin
    ports:
      - "127.0.0.1:3000:3000"
    networks: [edge, data]
    depends_on:
      postgres:
        condition: service_healthy
      redis:
        condition: service_healthy
      minio:
        condition: service_healthy
    healthcheck:
      # Actuator liveness group. Command validated in F0 (section 3.2).
      test: ["CMD", "wget", "-q", "--spider", "http://127.0.0.1:3000/health/live"]
      interval: 15s
      timeout: 5s
      retries: 5
      start_period: 60s

  api-portal:
    build:
      context: .
      dockerfile: infra/docker/api.Dockerfile
    restart: unless-stopped
    env_file: .env
    environment:
      APP_PROFILE: portal
    ports:
      - "127.0.0.1:3001:3000"
    networks: [edge, data]
    depends_on:
      postgres:
        condition: service_healthy
      redis:
        condition: service_healthy
      minio:
        condition: service_healthy
    healthcheck:
      # Actuator liveness group. Command validated in F0 (section 3.2).
      test: ["CMD", "wget", "-q", "--spider", "http://127.0.0.1:3000/health/live"]
      interval: 15s
      timeout: 5s
      retries: 5
      start_period: 60s

  worker:
    build:
      context: .
      dockerfile: infra/docker/api.Dockerfile
    restart: unless-stopped
    env_file: .env
    environment:
      APP_PROFILE: worker
    networks: [data]
    depends_on:
      postgres:
        condition: service_healthy
      redis:
        condition: service_healthy
      minio:
        condition: service_healthy
    healthcheck:
      # Actuator liveness group. Command validated in F0 (section 3.2).
      test: ["CMD", "wget", "-q", "--spider", "http://127.0.0.1:3000/health/live"]
      interval: 15s
      timeout: 5s
      retries: 5
      start_period: 60s

  admin-web:
    build:
      context: .
      dockerfile: infra/docker/web.Dockerfile
      args:
        APP_DIR: admin-web
    restart: unless-stopped
    ports:
      - "127.0.0.1:5173:8080"
    networks: [edge]
    depends_on:
      api-admin:
        condition: service_healthy

  portal-web:
    build:
      context: .
      dockerfile: infra/docker/web.Dockerfile
      args:
        APP_DIR: portal-web
    restart: unless-stopped
    ports:
      - "127.0.0.1:5174:8080"
    networks: [edge]
    depends_on:
      api-portal:
        condition: service_healthy
```

> El digest de la imagen de PostgreSQL es un marcador de formato, igual que los de la sección 3.
> Antes del primer uso se resuelve con
> `docker pull postgres:18-bookworm && docker inspect --format='{{index .RepoDigests 0}}' postgres:18-bookworm`
> y se fija ese valor exacto, junto con la versión menor vigente. La versión mayor es 18 en todos
> los entornos (ADR-0015): un script de integración continua compara la versión declarada en este
> archivo, en Testcontainers y en la configuración de RDS, y una divergencia rompe la construcción.

---

## 5. `docker-compose.prod.yml` (diferencias de producción)

Se aplica como *overlay*: `docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d`,
sobre una base común reducida. En la práctica, preproducción y producción usan un
`docker-compose.yml` base que **omite por completo** `mailhog`, `postgres` y `minio`: los tres
son herramientas o sustitutos de solo desarrollo y de pruebas de integración
(`docs/adr/ADR-0014-proveedor-de-nube-aws-y-portabilidad.md`). PostgreSQL es RDS for PostgreSQL y
el almacenamiento de objetos es S3, ambos fuera de Docker Compose, con acceso por variables de
entorno (sección 6). `redis` sí se mantiene contenerizado en producción.

En consecuencia, `api-admin`, `api-portal` y `worker` **no dependen en producción** de los
servicios `postgres` ni `minio`: su `depends_on` en el overlay de producción se reemplaza por
completo para dejar solo `redis`, en vez de fusionarse con el de la base local. El mecanismo
exacto de reemplazo (por ejemplo la etiqueta `!override` del *merge* de Docker Compose, frente a
mantener dos archivos base distintos para local y para AWS) se valida en F0 con
`docker compose config` (ADR-0012, verificación 5).

```yaml
# docker-compose.prod.yml
name: confia

# Container hardening required by docs/03-seguridad.md (container controls table).
x-jvm-hardening: &jvm-hardening
  user: "10001:10001"
  read_only: true
  tmpfs:
    - /tmp:noexec,nosuid   # java.io.tmpdir; the root filesystem stays read-only
  security_opt:
    - "no-new-privileges:true"
  cap_drop: [ALL]

x-web-hardening: &web-hardening
  user: "10001:10001"
  # read_only is pending F0 validation: nginx needs writable cache and pid paths
  # owned by the non-root user (see the note below this file).
  security_opt:
    - "no-new-privileges:true"
  cap_drop: [ALL]

services:
  # postgres y minio NO existen en este overlay: la base de produccion (seccion 5,
  # introduccion) los omite por completo. PostgreSQL es RDS for PostgreSQL y el
  # almacenamiento de objetos es S3, configurados por variables de entorno (seccion 6).

  redis:
    restart: always
    ports: []
    deploy:
      resources:
        limits:
          cpus: "0.5"
          memory: 512m
    logging:
      driver: json-file
      options:
        max-size: "20m"
        max-file: "5"

  api-admin:
    image: ghcr.io/confia/api:${IMAGE_DIGEST}
    build: !reset null
    <<: *jvm-hardening
    restart: always
    ports: []  # solo accesible via nginx de borde en la red edge
    depends_on:
      redis:
        condition: service_healthy
    deploy:
      resources:
        limits:
          cpus: "1.0"
          memory: 768m
        reservations:
          cpus: "0.25"
          memory: 256m
    logging:
      driver: json-file
      options:
        max-size: "50m"
        max-file: "10"

  api-portal:
    image: ghcr.io/confia/api:${IMAGE_DIGEST}
    build: !reset null
    <<: *jvm-hardening
    restart: always
    ports: []
    depends_on:
      redis:
        condition: service_healthy
    deploy:
      resources:
        limits:
          cpus: "1.0"
          memory: 768m
        reservations:
          cpus: "0.25"
          memory: 256m
    logging:
      driver: json-file
      options:
        max-size: "50m"
        max-file: "10"

  worker:
    image: ghcr.io/confia/api:${IMAGE_DIGEST}
    build: !reset null
    <<: *jvm-hardening
    restart: always
    depends_on:
      redis:
        condition: service_healthy
    deploy:
      resources:
        limits:
          cpus: "1.0"
          memory: 1g
        reservations:
          cpus: "0.25"
          memory: 256m
    logging:
      driver: json-file
      options:
        max-size: "50m"
        max-file: "10"

  admin-web:
    image: ghcr.io/confia/admin-web:${IMAGE_DIGEST}
    build: !reset null
    <<: *web-hardening
    restart: always
    ports: []
    deploy:
      resources:
        limits:
          cpus: "0.25"
          memory: 128m
    logging:
      driver: json-file
      options:
        max-size: "20m"
        max-file: "5"

  portal-web:
    image: ghcr.io/confia/portal-web:${IMAGE_DIGEST}
    build: !reset null
    <<: *web-hardening
    restart: always
    ports: []
    deploy:
      resources:
        limits:
          cpus: "0.25"
          memory: 128m
    logging:
      driver: json-file
      options:
        max-size: "20m"
        max-file: "5"

  nginx-edge:
    image: nginx:1.27.2-alpine@sha256:2ae06ebd39899cf5d5a09d4e12c48a5a6a6a5fa6f9e3d5b4e9f8a1b2c3d4e5f6
    restart: always
    # Binds ports 80 and 443 and drops to the nginx user, so it cannot use cap_drop: [ALL]
    # without cap_add. See the note below this file.
    security_opt:
      - "no-new-privileges:true"
    volumes:
      - ./infra/nginx:/etc/nginx/conf.d:ro
      # Cloudflare origin certificate and private key, provisioned on the instance
      # outside the repository (section 10). Not Let's Encrypt/certbot: the security
      # group only admits HTTPS from Cloudflare ranges, so an HTTP-01 challenge over
      # port 80 is not reachable from Let's Encrypt's validation servers.
      - /etc/confia/origin-tls:/etc/confia/origin-tls:ro
    ports:
      - "443:443"
    networks: [edge]
    depends_on:
      - api-admin
      - api-portal
      - admin-web
      - portal-web
    logging:
      driver: json-file
      options:
        max-size: "50m"
        max-file: "10"
```

`IMAGE_DIGEST` es una variable de entorno del *pipeline* de despliegue, nunca escrita a mano:
la fija el flujo de trabajo de promoción descrito en la sección 7. `build: !reset null` elimina
la instrucción de construcción heredada de la base: producción **nunca construye**, solo
descarga la imagen ya construida y escaneada.

**Endurecimiento de contenedores.** Los bloques `x-jvm-hardening` y `x-web-hardening` aplican los
controles de contenedor de `docs/03-seguridad.md`: usuario sin privilegios (UID 10001), sin
escalada de privilegios y sin capacidades. Los tres procesos de la JVM además corren con sistema de
archivos raíz de solo lectura y `/tmp` en `tmpfs` con `noexec` y `nosuid`, que es donde Java escribe
sus archivos temporales. Hay dos excepciones declaradas, que se resuelven en F0:

- **Aplicaciones web (nginx sin privilegios):** `read_only` queda pendiente hasta validar que la
  caché y el archivo de proceso de nginx pueden vivir en rutas temporales propiedad del usuario
  10001.
- **nginx de borde:** escucha en el puerto 443 (el grupo de seguridad de la instancia solo admite
  HTTPS desde los rangos de Cloudflare, ADR-0014; no hay puerto 80 público) y cambia al usuario de
  nginx, por lo que no puede eliminar todas las capacidades sin agregar las mínimas necesarias. Eso
  contradice la regla "sin `cap_add`" de `docs/03-seguridad.md`; en F0 se decide entre agregar solo
  las capacidades imprescindibles o escuchar en puertos altos detrás de la publicación de puertos
  de Docker.

Si alguna biblioteca de la JVM necesita extraer código nativo en `/tmp`, `noexec` lo impedirá; se
detecta en F0 y se resuelve con un directorio temporal dedicado, no quitando `noexec`.

**Límites de memoria de los procesos de la JVM.** Los límites de `api-admin`, `api-portal` y
`worker` son valores de partida. El consumo real de una JVM con Spring Boot se mide en F0 con
estos límites declarados (ADR-0013), con el heap derivado del límite del contenedor mediante
`JAVA_TOOL_OPTIONS` (sección 3.2), y los valores se corrigen con esa medición antes de producción.
Si la medición muestra que la topología de la sección 2 no alcanza, la decisión se escala según
la tabla de riesgos de ADR-0013.

---

## 6. Configuración: variables de entorno

| Variable | Propósito | Obligatoria | Ejemplo seguro |
|---|---|---|---|
| `APP_PROFILE` | Selecciona el proceso que arranca el lanzador: `admin`, `portal`, `worker` o `migrate` (aplica las migraciones de Flyway y termina) | Sí | `admin` |
| `SPRING_PROFILES_ACTIVE` | Perfil de configuración de Spring por entorno. Entre otros efectos, solo en local y preproducción habilita Swagger UI y el endpoint del OpenAPI; en producción quedan deshabilitados en los tres procesos | Sí | `prod` |
| `SERVER_PORT` | Puerto HTTP interno del proceso | Sí | `3000` |
| `SPRING_DATASOURCE_URL` | URL JDBC de PostgreSQL. En local, el contenedor `postgres`; en preproducción y producción, el punto de conexión de RDS for PostgreSQL (ADR-0014) | Sí | Local: `jdbc:postgresql://postgres:5432/confia`. AWS: `jdbc:postgresql://<identificador-rds>.<region>.rds.amazonaws.com:5432/confia` |
| `SPRING_DATASOURCE_USERNAME` | Rol de base de datos correspondiente al proceso: `confia_admin_app` en `admin` y `worker`, `confia_portal_app` en `portal` y el rol propietario `confia_owner` solo en `migrate` (`docs/03-seguridad.md`, sección 6.1) | Sí | `confia_admin_app` |
| `SPRING_DATASOURCE_PASSWORD` | Contraseña del rol anterior | Sí | `REEMPLAZAR` |
| `SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE` | Máximo de conexiones del pool por proceso | No, valor por defecto razonable | `10` |
| `JAVA_TOOL_OPTIONS` | Opciones de la JVM. Fija el heap relativo al límite de memoria del contenedor; el valor se ajusta con la medición de F0 | Sí | `-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError` |
| `REDIS_URL` | Cadena de conexión a Redis, usada por caché y límite de tasa | Sí | `redis://redis:6379/0` |
| `BACKUP_DATABASE_URL` | Cadena de conexión de `pg_dump` con el rol `confia_backup`, apuntando al punto de conexión de RDS for PostgreSQL en producción. La usa solo `infra/scripts/backup.sh`, ejecutado fuera de `confia-worker` (sección 11), nunca la aplicación | Sí en producción | `postgresql://confia_backup:REEMPLAZAR@<identificador-rds>.<region>.rds.amazonaws.com:5432/confia` |
| `OFFSITE_S3_ENDPOINT` / `OFFSITE_S3_BUCKET` | Punto de acceso y contenedor del proveedor de almacenamiento fuera de AWS que recibe el respaldo lógico cifrado (Backblaze B2 o Cloudflare R2, elegido en F0). Usadas solo por `infra/scripts/backup.sh` | Sí en producción | `REEMPLAZAR` |
| `JWT_ADMIN_SIGNING_KEY` | Llave de firma de tokens del dominio de identidad administrativo | Sí, en `admin` y `worker` | `REEMPLAZAR_CON_SECRETO_GENERADO` |
| `JWT_PORTAL_SIGNING_KEY` | Llave de firma de tokens del dominio de identidad del portal, distinta de la administrativa | Sí, en `portal` y `worker` | `REEMPLAZAR_CON_SECRETO_GENERADO` |
| `SESSION_COOKIE_DOMAIN` | Dominio de la cookie de sesión, distinto por perfil | Sí | `admin.confia.example` |
| `S3_ENDPOINT` | Punto de acceso del almacenamiento compatible con S3. En local, MinIO; en preproducción y producción, se omite o se deja vacío para que el cliente de S3 use el punto de acceso regional de AWS por defecto (ADR-0014) | Sí en local, no en AWS | Local: `http://minio:9000` |
| `S3_REGION` | Región de AWS del bucket de S3 | Sí en preproducción y producción | `us-east-1` |
| `S3_ACCESS_KEY_ID` / `S3_SECRET_ACCESS_KEY` | Credenciales del almacenamiento de objetos. En AWS, la instancia EC2 usa un rol de instancia y el cliente de S3 obtiene credenciales temporales automáticamente, sin que estas variables se definan; en otro proveedor o en local, el mismo cliente lee estas credenciales de variables de entorno, sin cambio de código (ADR-0014) | Sí en local y fuera de AWS, no en AWS | `REEMPLAZAR` |
| `S3_BUCKET_FISCAL_DOCUMENTS` | Nombre del contenedor para PDF fiscales | Sí | `confia-fiscal-documents` |
| `SMTP_HOST` / `SMTP_PORT` / `SMTP_USER` / `SMTP_PASSWORD` | Envío de correo transaccional. En producción, Amazon SES por SMTP (`email-smtp.<región>.amazonaws.com`, credenciales SMTP de SES, no credenciales de la API de AWS); en local, MailHog (ADR-0014) | Sí en producción, opcional en local (MailHog) | `email-smtp.us-east-1.amazonaws.com` |
| `IDEMPOTENCY_KEY_TTL_SECONDS` | Tiempo de vida de una clave de idempotencia almacenada | No | `86400` |
| `RATE_LIMIT_PORTAL_GENERAL` | Límite de tasa general del portal, solicitudes por minuto | No | `30` |
| `CSP_REPORT_URI` | Ruta que recibe los reportes de violación de la política de contenido | No | `/api/v1/csp-report` |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | Destino de trazas y métricas de OpenTelemetry | No en local | `https://otel-collector.internal.confia.example` |
| `SENTRY_DSN` | Destino de seguimiento de errores, con redacción de datos personales activa | No en local | `https://REEMPLAZAR@sentry.example/1` |
| `BACKUP_ENCRYPTION_PUBLIC_KEY` | Llave pública usada para cifrar los respaldos (sección 12) | Sí en producción | `age1REEMPLAZAR...` |

```bash
# .env.example
# Copiar a .env y reemplazar cada valor marcado. Nunca commitear .env real.

APP_PROFILE=admin
SPRING_PROFILES_ACTIVE=local
SERVER_PORT=3000

SPRING_DATASOURCE_URL=jdbc:postgresql://postgres:5432/confia
SPRING_DATASOURCE_USERNAME=confia
SPRING_DATASOURCE_PASSWORD=confia_dev_password
SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=10

JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError

REDIS_URL=redis://redis:6379/0

BACKUP_DATABASE_URL=

JWT_ADMIN_SIGNING_KEY=REEMPLAZAR_CON_SECRETO_GENERADO
JWT_PORTAL_SIGNING_KEY=REEMPLAZAR_CON_SECRETO_GENERADO
SESSION_COOKIE_DOMAIN=localhost

# S3_ENDPOINT y las credenciales explicitas solo aplican fuera de AWS (local con MinIO,
# u otro proveedor). En AWS se omiten y el cliente de S3 usa el rol de instancia (ADR-0014).
S3_ENDPOINT=http://minio:9000
S3_REGION=us-east-1
S3_ACCESS_KEY_ID=confia
S3_SECRET_ACCESS_KEY=confia_dev_password
S3_BUCKET_FISCAL_DOCUMENTS=confia-fiscal-documents

SMTP_HOST=mailhog
SMTP_PORT=1025
SMTP_USER=
SMTP_PASSWORD=

IDEMPOTENCY_KEY_TTL_SECONDS=86400
RATE_LIMIT_PORTAL_GENERAL=30
CSP_REPORT_URI=/api/v1/csp-report

OTEL_EXPORTER_OTLP_ENDPOINT=
SENTRY_DSN=

BACKUP_ENCRYPTION_PUBLIC_KEY=
OFFSITE_S3_ENDPOINT=
OFFSITE_S3_BUCKET=
```

Los secretos reales de preproducción y producción viven en el almacén de secretos del
proveedor de CI/CD (secretos cifrados de GitHub Actions) y se inyectan en tiempo de despliegue,
nunca en el repositorio, conforme a `CLAUDE.md`, regla 13.

---

## 7. Integración y entrega continuas

El flujo de verificación (tipos, análisis estático, reglas de dependencia, pruebas unitarias,
pruebas de integración con Testcontainers, pruebas de extremo a extremo y escaneo de seguridad)
está definido completo en `docs/06-estrategia-de-testing.md`, sección 14, archivo
`.github/workflows/ci.yml`. Este documento no lo repite: lo **consume** como condición de
entrada al flujo de despliegue.

El backend se construye con el Maven Wrapper y el frontend con `pnpm`. El orden de construcción
es fijo: primero el backend y su OpenAPI, luego la regeneración de `packages/contracts` con orval,
luego las aplicaciones web. La versión de Java del flujo (`actions/setup-java`) coincide con la de
la imagen base de la API y con la del `pom.xml` padre (ADR-0013).

### Regla: construir una vez, promover el mismo digest

La imagen que pasa el flujo de `ci.yml` sobre `main` se construye exactamente una vez, se
publica en el registro con su digest de contenido, y ese digest exacto es el que se despliega
primero en preproducción y, tras aprobación manual, en producción. **Nunca se reconstruye para
producción.**

```yaml
# .github/workflows/cd.yml
name: cd

on:
  workflow_run:
    workflows: ["ci"]
    types: [completed]
    branches: [main]

concurrency:
  group: cd-main
  cancel-in-progress: false

env:
  REGISTRY: ghcr.io
  IMAGE_API: ghcr.io/confia/api
  IMAGE_ADMIN_WEB: ghcr.io/confia/admin-web
  IMAGE_PORTAL_WEB: ghcr.io/confia/portal-web

jobs:
  build-and-publish:
    name: build once and publish
    if: github.event.workflow_run.conclusion == 'success'
    runs-on: ubuntu-latest
    outputs:
      api_digest: ${{ steps.push-api.outputs.digest }}
      admin_web_digest: ${{ steps.push-admin-web.outputs.digest }}
      portal_web_digest: ${{ steps.push-portal-web.outputs.digest }}
    permissions:
      contents: read
      packages: write
    steps:
      - uses: actions/checkout@11d5960a326750d5838078e36cf38b85af677262 # v4

      - uses: docker/login-action@v3
        with:
          registry: ${{ env.REGISTRY }}
          username: ${{ github.actor }}
          password: ${{ secrets.GITHUB_TOKEN }}

      - id: push-api
        uses: docker/build-push-action@v6
        with:
          context: .
          file: infra/docker/api.Dockerfile
          push: true
          tags: ${{ env.IMAGE_API }}:${{ github.sha }}

      # Web images need the generated contract: backend OpenAPI first, then
      # packages/contracts regenerated with orval, then the static builds.
      # The Turborepo task name is fixed in F0.
      - uses: actions/setup-java@cf277c60eb25467037889841efdb72551f06f6c3 # v4
        with:
          distribution: temurin
          java-version: '25'
          cache: maven

      # pnpm's version comes from packageManager in the root package.json.
      - uses: pnpm/action-setup@v4

      - uses: actions/setup-node@v4
        with:
          node-version-file: .nvmrc
          cache: pnpm

      - run: pnpm install --frozen-lockfile

      - name: generate openapi and contracts
        run: pnpm turbo run contracts:generate

      - id: push-admin-web
        uses: docker/build-push-action@v6
        with:
          context: .
          file: infra/docker/web.Dockerfile
          build-args: APP_DIR=admin-web
          push: true
          tags: ${{ env.IMAGE_ADMIN_WEB }}:${{ github.sha }}

      - id: push-portal-web
        uses: docker/build-push-action@v6
        with:
          context: .
          file: infra/docker/web.Dockerfile
          build-args: APP_DIR=portal-web
          push: true
          tags: ${{ env.IMAGE_PORTAL_WEB }}:${{ github.sha }}

      - name: trivy scan api image
        uses: aquasecurity/trivy-action@ed142fd0673e97e23eac54620cfb913e5ce36c25 # v0.36.0
        with:
          image-ref: ${{ env.IMAGE_API }}@${{ steps.push-api.outputs.digest }}
          severity: HIGH,CRITICAL
          exit-code: '1'
          ignore-unfixed: true

  deploy-preprod:
    name: deploy to preproduccion
    needs: build-and-publish
    runs-on: ubuntu-latest
    environment: preproduccion
    permissions:
      id-token: write   # required for OIDC federation with AWS, no long-lived AWS keys
      contents: read
    steps:
      - uses: actions/checkout@11d5960a326750d5838078e36cf38b85af677262 # v4
      # Federates with AWS via OIDC and assumes a role scoped to this environment,
      # instead of long-lived AWS access keys stored as secrets. There is no open SSH
      # port on the instance (ADR-0014); the exact action and role permissions are
      # validated in F0.
      - name: assume aws deployment role
        uses: aws-actions/configure-aws-credentials@v4
        with:
          role-to-assume: ${{ secrets.PREPROD_DEPLOY_ROLE_ARN }}
          aws-region: us-east-1
      # Runs infra/scripts/deploy.sh on the EC2 instance through AWS Systems Manager
      # Run Command (SSM), which reaches the instance without SSH. The exact AWS CLI
      # invocation, the SSM document, and how command output is awaited are validated
      # in F0.
      - name: deploy via aws systems manager run command
        run: |
          aws ssm send-command \
            --instance-ids "${{ secrets.PREPROD_INSTANCE_ID }}" \
            --document-name "AWS-RunShellScript" \
            --parameters commands="cd /srv/confia && IMAGE_DIGEST_API=${{ needs.build-and-publish.outputs.api_digest }} IMAGE_DIGEST_ADMIN_WEB=${{ needs.build-and-publish.outputs.admin_web_digest }} IMAGE_DIGEST_PORTAL_WEB=${{ needs.build-and-publish.outputs.portal_web_digest }} ./infra/scripts/deploy.sh preprod"
          # Exit code handling and waiting for command completion validated in F0.

  approve-production:
    name: aprobacion manual de produccion
    needs: [build-and-publish, deploy-preprod]
    runs-on: ubuntu-latest
    environment: produccion-aprobacion
    steps:
      - run: echo "Aprobado para promover el mismo digest a produccion"

  deploy-production:
    name: promover el mismo digest a produccion
    needs: [build-and-publish, approve-production]
    runs-on: ubuntu-latest
    environment: produccion
    permissions:
      id-token: write
      contents: read
    steps:
      - uses: actions/checkout@11d5960a326750d5838078e36cf38b85af677262 # v4
      - name: assume aws deployment role
        uses: aws-actions/configure-aws-credentials@v4
        with:
          role-to-assume: ${{ secrets.PROD_DEPLOY_ROLE_ARN }}
          aws-region: us-east-1
      - name: deploy via aws systems manager run command
        run: |
          aws ssm send-command \
            --instance-ids "${{ secrets.PROD_INSTANCE_ID }}" \
            --document-name "AWS-RunShellScript" \
            --parameters commands="cd /srv/confia && IMAGE_DIGEST_API=${{ needs.build-and-publish.outputs.api_digest }} IMAGE_DIGEST_ADMIN_WEB=${{ needs.build-and-publish.outputs.admin_web_digest }} IMAGE_DIGEST_PORTAL_WEB=${{ needs.build-and-publish.outputs.portal_web_digest }} ./infra/scripts/deploy.sh production"
```

El `environment: produccion-aprobacion` de GitHub Actions exige una revisión humana registrada
antes de continuar: es el mecanismo concreto que impide que un despliegue a producción ocurra
sin que el propietario del producto lo autorice explícitamente.

**Mecanismo de despliegue sin SSH.** `docs/adr/ADR-0014-proveedor-de-nube-aws-y-portabilidad.md`
prohíbe el puerto SSH abierto en la instancia de aplicación. El flujo de despliegue federa con
AWS mediante OIDC (sin credenciales de AWS de larga vida almacenadas como secreto) y ejecuta
`infra/scripts/deploy.sh` en la instancia a través de AWS Systems Manager Run Command, el mismo
mecanismo que Session Manager usa para el acceso administrativo interactivo. El nombre exacto de
la acción de federación OIDC, el documento de SSM, los permisos exactos del rol de despliegue y
la forma de esperar y verificar el resultado del comando remoto **se validan en F0** antes del
primer despliegue real; lo que no cambia es la ausencia de SSH y de credenciales de AWS
persistentes en el repositorio o en los secretos del flujo.

---

## 8. Migraciones de base de datos

1. **Revisión obligatoria.** Las migraciones son scripts SQL versionados de Flyway, escritos y
   revisados directamente: lo que se revisa es exactamente lo que se ejecuta. Ninguna migración
   se fusiona a `main` sin esa revisión explícita. Una migración sobre una tabla financiera
   requiere además la revisión adversarial descrita en `CLAUDE.md`.
2. **Respaldo verificado previo, en dos formas.** Antes de aplicar una migración en producción se
   toma (a) una instantánea manual (*manual snapshot*) de RDS for PostgreSQL, y (b) un respaldo
   lógico (`pg_dump`) cifrado con `infra/scripts/backup.sh pre-migration`, subido de inmediato al
   almacenamiento fuera de sitio (sección 11). Ninguna migración en producción se aplica sin que
   ambos existan y sin que el respaldo lógico más reciente haya pasado el simulacro mensual de
   restauración (`CLAUDE.md`, regla de respaldo verificado).
3. **Cambios destructivos en dos pasos compatibles hacia adelante.** Renombrar o eliminar una
   columna, cambiar un tipo de forma incompatible, o eliminar una tabla, nunca ocurre en una
   sola migración:
   - **Paso uno (expansión):** se agrega la columna o tabla nueva, el código se despliega para
     escribir en ambos lugares (el viejo y el nuevo) y para leer del nuevo con reserva al
     viejo. Se despliega y se verifica en producción.
   - **Paso dos (contracción):** una vez confirmado que el nuevo camino es estable durante un
     período de observación declarado (mínimo un ciclo de despliegue completo), una migración
     posterior elimina la columna o tabla vieja.
   - Esto permite revertir el despliegue del código sin revertir el esquema, que es
     precisamente lo que una migración destructiva de un solo paso no permite.
4. **Las migraciones nunca se editan una vez aplicadas en producción** (`CLAUDE.md`, sección
   Convenciones de Git). Un error se corrige con una migración nueva.

### Procedimiento de reversión de una migración

No hay directorio local de respaldos en el host de aplicación: RDS no vive ahí y
`infra/scripts/backup.sh` no conserva copia local (sección 11). La reversión usa la instantánea
de RDS o el respaldo lógico fuera de sitio tomados justo antes de la migración (paso 2 anterior).

```bash
# 1. Confirmar que existen la instantanea manual de RDS y el respaldo logico fuera de
#    sitio correspondientes a "pre-migration", por ejemplo con la consola o la CLI de AWS
#    (aws rds describe-db-snapshots) y con el listado del bucket fuera de sitio.

# 2a. Opcion preferida dentro de AWS: restaurar la instantania de RDS a una instancia
#     nueva (aws rds restore-db-instance-from-db-snapshot) y verificar integridad ahi
#     antes de repuntar la aplicacion. El nombre exacto de la instancia restaurada y el
#     corte de trafico se validan en F0.

# 2b. Alternativa, o si la perdida es total de la cuenta de AWS: descargar y descifrar
#     el respaldo logico fuera de sitio, y restaurarlo en una base temporal para
#     verificar integridad antes de aplicarlo sobre la base real.
createdb confia_rollback_check
age --decrypt --identity /ruta/a/la/llave/privada confia-pre-migration.dump.age \
  | pg_restore --dbname=confia_rollback_check

# 3. Si la verificacion es correcta y se opta por 2b, aplicar el respaldo sobre la base
#    real dentro de una ventana de mantenimiento anunciada
age --decrypt --identity /ruta/a/la/llave/privada confia-pre-migration.dump.age \
  | pg_restore --clean --if-exists --dbname=confia

# 4. Revertir el codigo desplegado al digest anterior conocido bueno (seccion 9)
./infra/scripts/deploy.sh production --digest=<digest_anterior>
```

---

## 9. Despliegue sin interrupción y reversión

Con un solo host por perfil, el enfoque no es *blue-green* completo sino **actualización
progresiva con verificación de salud**, servicio por servicio, detrás de nginx.

```bash
#!/usr/bin/env bash
# infra/scripts/deploy.sh
set -euo pipefail

TARGET="${1:?uso: deploy.sh <preprod|production> [--digest=<digest>]}"
COMPOSE="docker compose -f docker-compose.yml -f docker-compose.prod.yml"

echo "==> Respaldo previo"
./infra/scripts/backup.sh pre-migration

echo "==> Aplicando migraciones pendientes con la imagen nueva"
# Same image, migrate profile: Flyway applies pending migrations and the container exits.
# The deploy environment injects the schema owner credentials (confia_owner) only for
# this step; the long running services keep their least privilege roles.
$COMPOSE pull api-admin
$COMPOSE run --rm -e APP_PROFILE=migrate api-admin

for service in worker api-portal api-admin admin-web portal-web; do
  echo "==> Actualizando ${service}"
  $COMPOSE pull "${service}"
  $COMPOSE up -d --no-deps "${service}"

  echo "==> Esperando comprobacion de salud de ${service}"
  for attempt in $(seq 1 20); do
    status=$($COMPOSE ps --format json "${service}" | jq -r '.[0].Health // "starting"')
    if [ "${status}" = "healthy" ]; then
      echo "    ${service} saludable"
      break
    fi
    if [ "${attempt}" -eq 20 ]; then
      echo "!! ${service} no alcanzo estado saludable, revirtiendo"
      $COMPOSE up -d --no-deps "${service}"  # con IMAGE_DIGEST anterior en el entorno
      exit 1
    fi
    sleep 3
  done
done

echo "==> Despliegue de ${TARGET} completo"
```

**Reversión:** el mismo script, invocado con la variable `IMAGE_DIGEST` apuntando al digest
anterior conocido bueno (registrado como salida del flujo de trabajo anterior en el historial
de GitHub Actions), aplica el mismo procedimiento de actualización progresiva en sentido
inverso. Como la imagen nunca se reconstruye, el digest anterior sigue existiendo en el
registro y el despliegue es determinista.

---

## 10. Configuración de nginx detrás de Cloudflare

Las cabeceras de seguridad completas, la política de contenido con nonce y la protección CSRF
están especificadas con su justificación en `docs/03-seguridad.md`, sección 8. Este documento
reproduce la configuración operativa que integra ambos subdominios en el mismo borde nginx de
la topología de la sección 2, detrás de Cloudflare (ADR-0014).

**Terminación TLS: certificado de origen de Cloudflare, no Let's Encrypt.** El grupo de seguridad
de la instancia solo admite HTTPS desde los rangos de Cloudflare (ADR-0014); no hay puerto 80
público para que Let's Encrypt complete un desafío HTTP-01, y mantener credenciales de API de
Cloudflare en el flujo de despliegue solo para un desafío DNS-01 agrega una dependencia que un
certificado de origen evita. Se usa un **certificado de origen de Cloudflare** (validez de hasta
15 años, confiado solo por Cloudflare en modo *Full (strict)*), provisto una vez fuera del
repositorio y colocado en la instancia (sección 5). Esto elimina la renovación automática por
`certbot` de versiones anteriores de este documento; si en el futuro se necesita un certificado
público verificable de forma independiente, la alternativa es ACME por DNS-01 contra la zona de
Cloudflare, decisión que queda abierta para F0 si el propietario del producto la requiere.

**IP real del cliente.** Cloudflare sustituye la IP de origen por la suya al conectar con el
servidor; sin corregirlo, la lista de direcciones institucionales del panel administrativo
compararía siempre la IP de un borde de Cloudflare, no la del cliente real. El módulo
`ngx_http_realip_module` de nginx, restringido a los rangos de IP publicados por Cloudflare,
recupera la IP real desde la cabecera `CF-Connecting-IP` antes de que se evalúe la restricción de
acceso administrativo.

```nginx
# infra/nginx/confia.conf

limit_req_zone  $binary_remote_addr zone=portal_general:10m rate=30r/m;
limit_req_zone  $binary_remote_addr zone=portal_auth:10m    rate=5r/m;
limit_req_zone  $binary_remote_addr zone=admin_general:10m  rate=120r/m;
limit_conn_zone $binary_remote_addr zone=per_ip_conn:10m;

# Rangos de IP de Cloudflare, unica fuente confiable de X-Forwarded-For / CF-Connecting-IP.
# La lista completa y su actualizacion periodica viven en infra/nginx/cloudflare-ips.conf
# (generada desde https://www.cloudflare.com/ips/, validado en F0).
include /etc/nginx/cloudflare-ips.conf;
real_ip_header CF-Connecting-IP;

# Restriccion de acceso al panel administrativo por lista de direcciones institucionales,
# evaluada sobre la IP real del cliente recuperada por el bloque anterior.
# La lista real vive fuera del repositorio, en infra/nginx/admin-allowlist.conf,
# generada por el procedimiento de configuracion del entorno.
geo $admin_allowed {
    default 0;
    include /etc/nginx/admin-allowlist.conf;
}

server {
    listen 443 ssl;
    listen [::]:443 ssl;
    http2 on;
    server_name admin.confia.example;

    if ($admin_allowed = 0) {
        return 403;
    }

    ssl_certificate     /etc/confia/origin-tls/fullchain.pem;
    ssl_certificate_key /etc/confia/origin-tls/privkey.pem;

    ssl_protocols             TLSv1.2 TLSv1.3;
    ssl_ciphers               ECDHE-ECDSA-AES256-GCM-SHA384:ECDHE-RSA-AES256-GCM-SHA384:ECDHE-ECDSA-CHACHA20-POLY1305:ECDHE-RSA-CHACHA20-POLY1305;
    ssl_prefer_server_ciphers off;
    ssl_session_cache         shared:SSL:10m;
    ssl_session_timeout       1d;
    ssl_session_tickets       off;
    ssl_stapling              on;
    ssl_stapling_verify       on;

    add_header Strict-Transport-Security "max-age=63072000; includeSubDomains; preload" always;
    add_header X-Content-Type-Options    "nosniff" always;
    add_header X-Frame-Options           "DENY" always;
    add_header Referrer-Policy           "strict-origin-when-cross-origin" always;
    add_header Permissions-Policy        "camera=(), microphone=(), geolocation=(), payment=(), usb=()" always;
    add_header Cross-Origin-Opener-Policy   "same-origin" always;
    add_header Cross-Origin-Resource-Policy "same-origin" always;
    add_header Cross-Origin-Embedder-Policy "require-corp" always;

    server_tokens off;
    client_max_body_size 5m;
    client_body_timeout  15s;
    client_header_timeout 15s;
    send_timeout          30s;
    limit_conn per_ip_conn 20;

    # La Content-Security-Policy con nonce la emite la aplicacion, no nginx
    # (docs/03-seguridad.md, seccion 8.1).

    location /api/v1/ {
        limit_req zone=admin_general burst=40 nodelay;
        proxy_pass http://api-admin:3000;
        include /etc/nginx/snippets/proxy-headers.conf;
    }

    location / {
        proxy_pass http://admin-web:8080;
        include /etc/nginx/snippets/proxy-headers.conf;
    }
}

server {
    listen 443 ssl;
    listen [::]:443 ssl;
    http2 on;
    server_name portal.confia.example;

    ssl_certificate     /etc/confia/origin-tls/fullchain.pem;
    ssl_certificate_key /etc/confia/origin-tls/privkey.pem;

    ssl_protocols             TLSv1.2 TLSv1.3;
    ssl_ciphers               ECDHE-ECDSA-AES256-GCM-SHA384:ECDHE-RSA-AES256-GCM-SHA384:ECDHE-ECDSA-CHACHA20-POLY1305:ECDHE-RSA-CHACHA20-POLY1305;
    ssl_prefer_server_ciphers off;
    ssl_session_cache         shared:SSL:10m;
    ssl_session_timeout       1d;
    ssl_session_tickets       off;
    ssl_stapling              on;
    ssl_stapling_verify       on;

    add_header Strict-Transport-Security "max-age=63072000; includeSubDomains; preload" always;
    add_header X-Content-Type-Options    "nosniff" always;
    add_header X-Frame-Options           "DENY" always;
    add_header Referrer-Policy           "strict-origin-when-cross-origin" always;
    add_header Permissions-Policy        "camera=(), microphone=(), geolocation=(), payment=(), usb=()" always;
    add_header Cross-Origin-Opener-Policy   "same-origin" always;
    add_header Cross-Origin-Resource-Policy "same-origin" always;

    server_tokens off;
    client_max_body_size 5m;
    client_body_timeout  15s;
    client_header_timeout 15s;
    send_timeout          30s;
    limit_conn per_ip_conn 20;

    location /api/v1/auth/ {
        limit_req zone=portal_auth burst=3 nodelay;
        proxy_pass http://api-portal:3000;
        include /etc/nginx/snippets/proxy-headers.conf;
    }

    location /api/v1/ {
        limit_req zone=portal_general burst=20 nodelay;
        proxy_pass http://api-portal:3000;
        include /etc/nginx/snippets/proxy-headers.conf;
    }

    location / {
        proxy_pass http://portal-web:8080;
        include /etc/nginx/snippets/proxy-headers.conf;
    }
}
```

```nginx
# infra/nginx/snippets/proxy-headers.conf
proxy_http_version 1.1;
proxy_set_header Host              $host;
proxy_set_header X-Real-IP         $remote_addr;
proxy_set_header X-Forwarded-For   $proxy_add_x_forwarded_for;
proxy_set_header X-Forwarded-Proto $scheme;
proxy_set_header X-Request-Id      $request_id;
proxy_set_header Upgrade           $http_upgrade;
proxy_set_header Connection        "";
proxy_read_timeout 30s;
proxy_connect_timeout 5s;
proxy_buffering on;
```

**Cómo se comprueba:** igual que en `docs/03-seguridad.md`, `nginx -t` en el flujo de
despliegue y una prueba de humo posterior con `curl -I` que verifica las seis cabeceras
obligatorias en ambos subdominios.

---

## 11. Respaldos

| Aspecto | Definición |
|---|---|
| Qué | Dos niveles, según ADR-0014. Dentro de AWS: respaldos automáticos de RDS for PostgreSQL con restauración a un punto en el tiempo, gestionados por el proveedor. Fuera de AWS: respaldo lógico completo de PostgreSQL (`pg_dump` formato personalizado), cifrado. Objetos de S3 (PDF fiscales, adjuntos) respaldados por replicación al almacenamiento fuera de sitio |
| Frecuencia | Respaldos automáticos de RDS continuos, gestionados por AWS. Respaldo lógico completo fuera de sitio nocturno. Replicación de objetos por evento |
| Dónde | Los respaldos automáticos de RDS, dentro de AWS. El respaldo lógico y la copia de objetos, fuera de AWS, en almacenamiento de objetos de un proveedor distinto al de cómputo, por ejemplo Backblaze B2 o Cloudflare R2 (regla 3-2-1: tres copias, dos medios, una fuera de sitio). La pérdida de la cuenta de AWS no implica pérdida de datos |
| Cifrado | Cifrado del lado del cliente antes de salir del servidor que ejecuta el respaldo, con `age`, usando `BACKUP_ENCRYPTION_PUBLIC_KEY`. La llave privada de descifrado **no** vive en AWS ni en el proveedor del respaldo |
| Retención | Respaldos lógicos fuera de sitio escalonados: 7 diarios, 4 semanales y 12 mensuales, conforme a `docs/08-datos-privacidad-y-retencion.md`, sección 6, que es la política de retención vigente. `infra/scripts/backup.sh` no aplica retención local (no existe copia local que retener); la retención se aplica **fuera de sitio**, con reglas de ciclo de vida del bucket de destino sobre el prefijo `${LABEL}/`, validadas en F0. El período de retención de los respaldos automáticos de RDS se fija en F0, coherente con esa política. El período de retención específico de documentos fiscales sigue lo definido en `docs/04-cumplimiento-fiscal-sar.md`, sección 10, que puede exceder esta política operativa general |
| Programación | Un temporizador de `systemd` en la instancia EC2 ejecuta `infra/scripts/backup.sh nightly` cada noche, fuera de `confia-worker` (ADR-0016 acota db-scheduler a tareas de aplicación, no a respaldo de infraestructura) y desde la instancia que sí tiene acceso de red a RDS, a diferencia de un ejecutor de GitHub Actions. La unidad de `systemd` exacta se valida en F0 |
| Punto de recuperación | El objetivo de quince minutos se cumple con la restauración a un punto en el tiempo de RDS ante fallos normales. Ante la pérdida total de la cuenta de AWS, el punto de recuperación es el último respaldo fuera de sitio (ADR-0014) |
| Restauración | Procedimiento documentado y ensayado **mensualmente**: el simulacro restaura un respaldo real, descargado del almacenamiento fuera de sitio y descifrado con la llave custodiada, en un entorno limpio, verifica la integridad, mide el tiempo frente al objetivo de cuatro horas y deja acta firmada (`docs/01-arquitectura.md`, sección 8). Una vez al año, ese simulacro se ejecuta en un proveedor distinto de AWS como simulacro de salida (ADR-0014). El ejercicio semestral de recuperación ante desastre de `docs/03-seguridad.md`, sección 19, que reconstruye el entorno completo, es adicional y no reemplaza al mensual |

```bash
#!/usr/bin/env bash
# infra/scripts/backup.sh
#
# Runs against the RDS for PostgreSQL endpoint with the confia_backup role
# (BACKUP_DATABASE_URL). There is no local retention on the application host: the
# dump is generated, encrypted and uploaded from a temporary directory that is
# always removed on exit, whether the script succeeds or fails. Retention (7 daily,
# 4 weekly, 12 monthly, docs/08-datos-privacidad-y-retencion.md section 6) is
# enforced offsite by lifecycle rules on the destination bucket, keyed by the
# "${LABEL}/" prefix, validated in F0 (section 11).
#
# Scheduling: a systemd timer on the EC2 instance runs this script nightly with
# LABEL=nightly, outside confia-worker (ADR-0016 scopes db-scheduler to application
# tasks, not host-level backup) and reachable from RDS's security group, which a
# GitHub Actions runner is not (RDS has no public access). The timer unit and its
# exact schedule are validated in F0.
set -euo pipefail

LABEL="${1:-nightly}"
TIMESTAMP=$(date -u +%Y%m%dT%H%M%SZ)
WORKDIR=$(mktemp -d)
trap 'rm -rf "${WORKDIR}"' EXIT

DUMP_FILE="${WORKDIR}/confia-${LABEL}-${TIMESTAMP}.dump"
ENCRYPTED_FILE="${DUMP_FILE}.age"

echo "==> Generando respaldo logico desde RDS"
pg_dump \
  --format=custom \
  --file="${DUMP_FILE}" \
  "${BACKUP_DATABASE_URL}"

echo "==> Cifrando respaldo"
age --encrypt --recipient "${BACKUP_ENCRYPTION_PUBLIC_KEY}" \
  --output "${ENCRYPTED_FILE}" "${DUMP_FILE}"

echo "==> Calculando hash de integridad"
sha256sum "${ENCRYPTED_FILE}" > "${ENCRYPTED_FILE}.sha256"

echo "==> Subiendo a almacenamiento fuera de sitio (Backblaze B2 o Cloudflare R2, elegido en F0)"
aws --endpoint-url "${OFFSITE_S3_ENDPOINT}" s3 cp \
  "${ENCRYPTED_FILE}" "s3://${OFFSITE_S3_BUCKET}/${LABEL}/${TIMESTAMP}/"
aws --endpoint-url "${OFFSITE_S3_ENDPOINT}" s3 cp \
  "${ENCRYPTED_FILE}.sha256" "s3://${OFFSITE_S3_BUCKET}/${LABEL}/${TIMESTAMP}/"

# No local retention step here on purpose: nothing is kept on this host beyond the
# temporary directory removed by the trap above. Retention lives offsite, by
# lifecycle rules on the "${LABEL}/" prefix of the destination bucket.

echo "==> Respaldo ${LABEL} completo: ${TIMESTAMP}"
```

Procedimiento de restauración: sección 8, pasos 1 a 3, que reutiliza el mismo respaldo cifrado
descargado del almacenamiento fuera de sitio en vez del respaldo local previo a migración.

---

## 12. Estimación de costos de infraestructura por fase

La estimación de costos vigente es la de
`docs/adr/ADR-0014-proveedor-de-nube-aws-y-portabilidad.md`, sección "Estimación de costos", que
sustituye por completo la tabla de VPS que tenía esta sección. Resumen (dólares
estadounidenses por mes, `us-east-1`, sin impuestos, precios del 2026-09-14):

| Escenario | Rango mensual estimado |
|---|---|
| F0 a F7, mínimo (compromiso de un año de EC2 y RDS, preproducción encendida solo en horario laboral) | **US$ 76** |
| F0 a F7, máximo (pago por uso, preproducción siempre encendida) | **US$ 126** |
| Incremento de fase dos (segunda instancia EC2 para el portal, réplica de lectura de RDS), pago por uso | **+ US$ 81** |

Ver ADR-0014 para el desglose por componente (EC2, RDS, S3, SES, IPv4 pública, respaldo fuera de
sitio, Cloudflare), los supuestos de tamaño, la comparación con la región de México y con
Lightsail, y lo que la estimación no incluye (impuestos, dominio, soporte de AWS, Cloudflare Pro,
crecimiento de datos por encima de los supuestos, costo humano del desarrollador).

---

## 13. Dominios, certificados y renovación automática

- Dos subdominios: `admin.confia.example` y `portal.confia.example` (los nombres reales se
  definen con el propietario del producto antes de F0).
- **DNS gestionado en Cloudflare** (ADR-0014), no en AWS Route 53: con el DNS fuera de AWS,
  migrar el tráfico ante un cambio de proveedor es cambiar un registro, no migrar una zona.
  Ambos subdominios están proxiados por Cloudflare (nube naranja), que también aporta WAF,
  protección DDoS y TLS de borde.
- **Certificado de origen de Cloudflare** en modo *Full (strict)* entre Cloudflare y la instancia
  EC2 (sección 10), en vez de Let's Encrypt / `certbot`: el grupo de seguridad de la instancia
  solo admite HTTPS desde los rangos de Cloudflare, sin puerto 80 público para un desafío HTTP-01.
  El certificado de origen cubre ambos subdominios, tiene una validez de hasta 15 años y no
  requiere renovación automatizada en la instancia; se reemite solo si se revoca o si cambia el
  conjunto de dominios cubiertos.
- Alerta operativa cuando el certificado de origen o el certificado de borde de Cloudflare tienen
  menos de 15 días para vencer, coherente con la lista de alertas accionables de
  `docs/01-arquitectura.md`, sección 8.
- El registro DNS de ambos subdominios apunta a la instancia EC2 a través de Cloudflare (proxiado,
  no en modo DNS-only, para que aplique la protección de borde). No hay DNS público para RDS
  (sección 2, regla permanente): RDS no tiene nombre DNS resoluble fuera de la VPC.

---

## 14. Documentos relacionados

| Documento | Contenido |
|---|---|
| `docs/01-arquitectura.md` | Fuente de verdad técnica, secciones 5 y 9 |
| `docs/03-seguridad.md` | Cabeceras, CSP con nonce, CSRF y configuración nginx detallada |
| `docs/06-estrategia-de-testing.md` | Flujo de integración continua completo (`ci.yml`), umbrales de calidad |
| `docs/adr/ADR-0012-contenerizacion.md` | Decisión de contenerizar todo el sistema y sus consecuencias |
| `docs/07-observabilidad-y-operaciones.md` | Logs, métricas, alertas y runbooks operativos |
