# Propuesta: monorepo del frontend y canal de contratos desde el OpenAPI

- **Cambio:** `frontend-monorepo-and-contracts-pipeline`
- **Fase del roadmap:** F0, cambio 3 de 13 (exploración `foundations-plan`, aprobada el 2026-09-15)
- **Depende de:** cambio 1 (`maven-workspace-and-ci-skeleton`, archivado el 2026-09-17)
- **Exploración:** `exploration.md` de este mismo cambio
- **Estado:** aprobada por el propietario del producto el 2026-09-30, con las cuatro
  recomendaciones de la ronda de preguntas

## Intención

El contrato entre el backend y el frontend es hoy una promesa escrita: `docs/01-arquitectura.md` §7
dice que el OpenAPI generado es la fuente de verdad, que se compara contra una instantánea aprobada
y que el frontend compila contra tipos generados desde él. **Nada de eso existe.** El backend no
genera OpenAPI, no hay espacio de trabajo de TypeScript y la integración continua solo construye
Java.

Este cambio instala esa maquinaria **antes** de la primera pantalla y del primer endpoint, igual
que el cambio 1 instaló las reglas de ArchUnit antes del primer módulo. Si la primera pantalla
llegara antes que el canal, sus tipos se escribirían a mano, y los tipos escritos a mano son
justo la segunda verdad que el contrato generado existe para impedir.

También cierra el **criterio de salida 7 de F0**: «OpenAPI 3.1 se genera y publica como artefacto
versionado».

## Alcance

### Dentro de alcance

1. **springdoc-openapi en el backend**, generando OpenAPI 3.1. Swagger UI y el endpoint del
   documento se habilitan solo con los perfiles `local` y `preprod` de `SPRING_PROFILES_ACTIVE`; en
   cualquier otro perfil, incluido `prod`, quedan deshabilitados en los tres procesos.
2. **Instantánea aprobada del OpenAPI**, comprometida en el repositorio. La construcción genera el
   documento y lo compara con ella: **cualquier diferencia no declarada rompe `./mvnw verify`**.
   Actualizarla es un paso explícito y visible en el diff del PR.
3. **Esquemas transversales del contrato registrados desde ya:** el importe `{ amount: string,
   currency: string }` y el error en formato Problem Details (RFC 9457). Hoy no hay ningún
   endpoint, y son lo único que el contrato ya fija (exploración, H1).
4. **Espacio de trabajo pnpm y Turborepo en la raíz**, con Node y pnpm fijados en un solo lugar
   (`engines`, `packageManager` y `.nvmrc`), y un `apps/api/package.json` mínimo que delega en el
   Maven Wrapper, para que Turborepo ordene backend → OpenAPI → contratos → aplicaciones.
5. **`packages/config`:** configuración compartida y estricta de TypeScript, ESLint y Vitest. La
   parte de Tailwind llega con los tokens del cambio 13.
6. **`packages/contracts` generado con orval** a partir de la instantánea: tipos TypeScript y
   esquemas Zod, más una prueba de tipos que actúa como consumidor y demuestra que un cambio
   incompatible en el contrato rompe la compilación.
7. **Reglas de dependencia del frontend** con `dependency-cruiser` y ESLint: ningún paquete importa
   de una aplicación, ninguna aplicación importa de otra, nadie importa rutas internas de
   `packages/contracts` y nadie edita su salida generada. **Cada regla trae su violación
   deliberada**, que demuestra que rompe la construcción (ADR-0018).
8. **Integración continua:** un trabajo `frontend verify` (instalación con el archivo de bloqueo
   congelado, y `lint`, `typecheck`, `test` y `build` por Turborepo) y el escaneo de dependencias de
   pnpm dentro del trabajo `security scanning`, que bloquea ante severidad alta o crítica.

### Fuera de alcance

- **`apps/admin-web` y `apps/portal-web`.** Este cambio no crea ninguna aplicación (exploración,
  H5; pregunta 3 abajo).
- **`packages/ui`**, tokens, Tailwind, shadcn/ui y la verificación de accesibilidad: cambio 13
  (`design-system-foundations-and-a11y`).
- **Cualquier endpoint o controlador:** llegan con `session-tokens-and-web-layer`, junto con Spring
  Security. Este cambio no crea ninguno, ni siquiera uno técnico (exploración, H1 b).
- **i18next y los catálogos de texto:** llegan con la primera pantalla.
- **Dockerfiles e imagen de construcción del frontend:** cambio 11.
- **Publicación del OpenAPI fuera del repositorio** (registro de artefactos, portal de
  documentación). Aquí, «publicar como artefacto versionado» significa que la instantánea está
  comprometida y versionada con el código, y que la integración continua adjunta el documento
  generado a cada ejecución.

## Capacidades

### Modificadas

- `build-integrity`: nuevos requisitos verificables. Una diferencia no declarada entre el OpenAPI
  generado y la instantánea aprobada rompe la construcción. Un cambio incompatible del contrato
  rompe la compilación de TypeScript. Una violación de las reglas de dependencia del frontend
  rompe la construcción. Swagger UI y el endpoint del OpenAPI no responden en `prod`.

### Nuevas

- Ninguna.

## Entregable y criterio de salida de F0

- Cubre la **parte de pnpm y Turborepo del entregable 1**, más la generación de contratos con orval
  y las reglas de dependencia del frontend.
- **Cierra el criterio de salida 7** («OpenAPI 3.1 se genera y publica como artefacto versionado»).

## Enfoque

De adentro hacia afuera, igual que el cambio 1:

1. **Sondas.** (a) Compatibilidad de la versión de springdoc con Spring Boot 4.1 y Java 25. (b) Que
   springdoc publique los dos esquemas transversales sin ninguna operación. (c) Que orval genere
   tipos y esquemas Zod desde un documento sin operaciones. Si alguna falla, el cambio se detiene
   y se informa; no se inventa un endpoint para que funcione.
2. **Backend:** springdoc, restricción por perfil e instantánea con su puerta, primero en rojo.
3. **Monorepo:** pnpm, Turborepo y `packages/config`, con una prueba de humo en verde.
4. **Contratos:** orval y la prueba de tipos que actúa como consumidor.
5. **Reglas de dependencia**, cada una con su violación deliberada.
6. **Integración continua**, cuando todo pasa en local.

Las versiones exactas y el mecanismo de generación del OpenAPI (exploración, H3) **se fijan en la
fase de diseño**; esta propuesta no los fija.

## Áreas afectadas

| Área | Impacto | Descripción |
|---|---|---|
| `apps/api/app/pom.xml` | Modificada | Dependencia de springdoc |
| `apps/api/app/src/main/resources/` | Modificada | Configuración de springdoc por perfil |
| `apps/api/app/src/main/java/com/confia/shared/` | Nueva | Personalización del OpenAPI con los esquemas transversales (ubicación exacta en diseño) |
| `apps/api/openapi/` (ubicación exacta en diseño) | Nueva | Instantánea aprobada del OpenAPI |
| `apps/api/package.json` | Nueva | Delegación mínima en el Maven Wrapper |
| `package.json`, `pnpm-workspace.yaml`, `turbo.json`, `.nvmrc`, `pnpm-lock.yaml` | Nueva | Espacio de trabajo |
| `packages/config/`, `packages/contracts/` | Nueva | Configuración compartida y contratos generados |
| `.dependency-cruiser.cjs` y fixtures de violación | Nueva | Reglas de dependencia del frontend |
| `.github/workflows/ci.yml` | Modificada | Trabajo `frontend verify` y escaneo de pnpm |
| `docs/05-infraestructura-y-despliegue.md` | Modificada | Alinear la versión de Node (pregunta 2) |

## Criterios de aceptación

- [ ] `./mvnw verify` genera el OpenAPI 3.1 y lo compara con la instantánea comprometida; alterar
      la instantánea a mano rompe la construcción y restaurarla la devuelve a verde.
- [ ] Con el perfil `prod`, ni Swagger UI ni el endpoint del OpenAPI responden; con `local`, sí.
- [ ] Los esquemas del importe y de Problem Details aparecen en la instantánea, y el importe viaja
      con `amount` de tipo cadena, nunca número.
- [ ] `pnpm install --frozen-lockfile` y `pnpm turbo run lint typecheck test build` terminan en
      verde en una máquina limpia, con la versión de Node fijada.
- [ ] `packages/contracts` se regenera desde la instantánea; cambiar el tipo de `amount` en ella
      rompe la prueba de tipos del consumidor.
- [ ] Cada regla de `dependency-cruiser` y de ESLint rechaza su violación deliberada.
- [ ] La integración continua ejecuta el trabajo `frontend verify`, y el escaneo de pnpm rompe la
      construcción ante una vulnerabilidad alta o crítica.
- [ ] El criterio de salida 7 de F0 queda marcado como cerrado en `docs/09-roadmap-y-fases.md`,
      con la prueba que lo demuestra.

## Riesgos

| Riesgo | Probabilidad | Mitigación |
|---|---|---|
| springdoc todavía no es compatible con Spring Boot 4.1 o Java 25 | Media | Sonda (a) antes de cualquier otra tarea; si falla, se detiene y se informa |
| orval no genera nada útil desde un documento sin operaciones | Media | Sonda (c); los dos esquemas transversales existen justo para eso |
| Una instantánea que se regenera sola no protege nada | Alta si no se mitiga | La prueba **compara**, nunca sobrescribe; actualizarla es una orden aparte y explícita |
| Un canal de un solo documento obliga a rehacer las importaciones cuando el portal tenga su API | Media | Se decide ahora con la pregunta 1 |
| El PR supera las ochocientas líneas efectivas | Alta | El archivo de bloqueo es generado y no cuenta como línea de autor; si aun así se excede, se divide en 3a (backend) y 3b (monorepo) |
| El escaneo de pnpm reporta vulnerabilidades en herramientas de desarrollo | Media | Se triagean una por una; nunca se desactiva el bloqueo en silencio |

## Plan de reversión

No hay base de datos, esquema, datos ni despliegue. Revertir es descartar la rama. La dependencia
de springdoc se puede quitar sin efecto sobre ningún módulo, porque no existe ningún controlador.

## Dependencias

- Cambio 1, archivado.
- JDK 25 y Docker, como ya exige el backend.
- Node y pnpm, en la versión que fije la pregunta 2.

## Ronda de preguntas de propuesta

1. **¿Uno o dos documentos OpenAPI?** (exploración, H2). ADR-0003 separa el proceso
   administrativo del portal. **Recomendación:** dos documentos (`admin` y `portal`) y dos espacios
   de nombres en `packages/contracts` desde el primer día, para que el portal nunca compile contra
   una operación administrativa. Hoy los dos saldrían vacíos, salvo los esquemas transversales.
2. **¿Node 24 o Node 22?** (exploración, H4). **Recomendación:** Node 24, la LTS activa. Hay que
   actualizar `docs/05`, que hoy dice 22.
3. **¿Qué cambio crea `apps/admin-web` y `apps/portal-web`?** (exploración, H5). **Recomendación:**
   el cambio 13 crea un esqueleto mínimo de ambas aplicaciones, porque necesita renderizar
   componentes para verificar accesibilidad; la primera pantalla real la añade después la capa web.
4. **¿Se compromete `packages/contracts`?** (exploración, H6). **Recomendación:** no; se ignora en
   Git y lo regenera Turborepo. La instantánea del OpenAPI es lo que se revisa.

**Respuesta del propietario (2026-09-30):** aprobadas las cuatro recomendaciones. Dos documentos
OpenAPI con dos espacios de nombres en `packages/contracts`; Node 24, con `docs/05` actualizado en
este cambio; el esqueleto de `apps/admin-web` y `apps/portal-web` pasa al cambio 13, que debe
recogerlo en su propia propuesta; y `packages/contracts` no se compromete.
