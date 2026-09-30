# Exploración: monorepo del frontend y canal de contratos (F0, cambio 3)

- **Cambio:** `frontend-monorepo-and-contracts-pipeline`
- **Fase:** explorar
- **Fecha:** 2026-09-30
- **Estado:** exploración terminada, pendiente de aprobación de la propuesta
- **Origen:** el propietario pidió el 2026-09-30 empezar el frontend de F0 en paralelo con el
  cierre de `column-encryption-and-mfa-totp`. `foundations-plan/exploration.md` §1 ya preveía este
  cambio como paralelizable («los cambios 2, 3 y 4 después del 1»).

## Estado actual

- **No existe nada del lado TypeScript.** No hay `package.json` en la raíz, ni
  `pnpm-workspace.yaml`, ni `turbo.json`, ni directorio `packages/`, ni `apps/admin-web` ni
  `apps/portal-web`. Solo `.gitignore` anticipa `node_modules/`, `dist/` y `.turbo/`.
- **El backend no tiene springdoc.** `apps/api/app/pom.xml` trae `spring-boot-starter-web`, pero
  no `springdoc-openapi`. No hay ningún controlador: la primera capa `web` real llega con
  `session-tokens-and-web-layer`. Tampoco hay Spring Security.
- **La integración continua solo conoce el backend.** `.github/workflows/ci.yml` tiene dos
  trabajos, `backend verify` y `security scanning`. El comentario de este último dice
  literalmente que el escaneo de pnpm «llega con los cambios 3 y 11».
- **El criterio de salida 7 de F0 sigue abierto:** «OpenAPI 3.1 se genera y publica como
  artefacto versionado» (`docs/09-roadmap-y-fases.md`). La tabla de `foundations-plan` se lo asigna
  a este cambio, y la línea 214 de esa misma exploración confirma que «springdoc y endpoints»
  pertenecen al cambio 3.
- **La documentación de UI ya está escrita.** `docs/ui-ux/` tiene principios, tokens, sistema de
  diseño, accesibilidad, patrones, contenido y flujos. Es la materia prima del cambio 13
  (`design-system-foundations-and-a11y`), que depende de este.

## Lo que exigen las fuentes de verdad

| Fuente | Exigencia |
|---|---|
| `docs/01-arquitectura.md` §4 | pnpm workspaces y Turborepo; `apps/api` con un `package.json` mínimo que delega en el Maven Wrapper para que Turborepo ordene: backend y OpenAPI, luego `packages/contracts`, luego las aplicaciones web |
| `docs/01-arquitectura.md` §7 | OpenAPI 3.1 generado con springdoc desde el código; **comparado en cada construcción contra la instantánea aprobada, y una diferencia no declarada rompe la construcción**; Swagger UI y el endpoint solo en perfiles local y preproducción |
| `docs/01-arquitectura.md` §7 | `packages/contracts` se genera con orval (tipos TypeScript y esquemas Zod), no se edita a mano, y la construcción verifica los tipos de las aplicaciones contra él |
| `CLAUDE.md` y roadmap, entregable 1 | Reglas de dependencia del frontend verificadas por `dependency-cruiser` y ESLint; una violación rompe la construcción |
| ADR-0006 | `packages/config` con la configuración compartida de ESLint, Vitest y Tailwind |
| ADR-0008 y ADR-0013 | Escaneo de vulnerabilidades de dependencias que bloquea ante severidad alta o crítica |
| ADR-0018 | Una regla de arquitectura que nunca rechaza nada no protege nada: toda regla nueva lleva su violación deliberada |

## Hallazgos que condicionan la propuesta

### H1 — El canal de contratos no tiene todavía nada que transportar

Sin controladores, springdoc genera un documento con `paths` vacío, y orval podría no generar nada
o fallar ante un documento sin operaciones. Hay dos salidas honestas:

- **(a) Montar el canal completo sobre un documento casi vacío** y demostrar que funciona con los
  dos esquemas transversales que el contrato ya fija: el importe `{ amount: string, currency:
  string }` y el error en formato Problem Details (RFC 9457). springdoc solo publica los esquemas
  que alguna operación referencia, así que habría que registrarlos a mano con un
  `OpenApiCustomizer`. Queda por sondear si eso basta para que orval emita tipos y esquemas Zod.
- **(b) Crear un endpoint técnico** para tener algo que generar. **Se descarta:** cualquier
  endpoint expuesto sin Spring Security contradice la regla 9 de `CLAUDE.md`, y uno solo de
  prueba sería código muerto en producción.

Se recomienda (a), con una sonda bloqueante antes de la tarea que genere los contratos.

### H2 — Hay dos API, no una

ADR-0003 separa los procesos administrativo y de portal: el portal solo carga su módulo. Si ambos
publican controladores distintos, springdoc generará **dos documentos**, y `packages/contracts`
necesita dos espacios de nombres para que el portal nunca compile contra una operación
administrativa. Hoy los dos documentos estarían vacíos, pero la forma del canal (uno o dos
documentos, una o dos salidas de orval) conviene fijarla ahora: cambiarla después obliga a tocar
cada importación del frontend.

### H3 — Cómo se genera el OpenAPI en la construcción

- **(a) `springdoc-openapi-maven-plugin`**, que arranca la aplicación con `spring-boot-maven-plugin`
  y descarga `/v3/api-docs`. Obliga a arrancar un proceso real, con su base de datos, dentro de
  `verify`.
- **(b) Una prueba de integración** (`*IT`, con el Testcontainers que ya existe) que levanta el
  contexto, pide el documento, lo escribe en `target/` y lo compara contra la instantánea
  comprometida. Reutiliza el instrumental del proyecto y la comparación ocurre donde ya ocurren
  las demás puertas.

Se inclina por (b); la decisión es de la fase de diseño.

### H4 — Versión de Node

`docs/05-infraestructura-y-despliegue.md` usa Node 22 en la imagen de construcción y en el ejemplo
de integración continua. A la fecha de este cambio, Node 24 es la versión LTS activa y Node 22 está
en mantenimiento. Hay que fijar una sola versión en un solo lugar (`engines` y `packageManager` en
el `package.json` raíz, `.nvmrc` para las máquinas de desarrollo) y alinear `docs/05` con ella.

### H5 — Nadie tiene asignado el esqueleto de `apps/admin-web` y `apps/portal-web`

Ninguno de los trece cambios de F0 dice explícitamente que crea las aplicaciones. El cambio 13
necesita renderizar componentes para verificar accesibilidad, y la primera pantalla real (el
inicio de sesión) llega con la capa web. Este cambio **no** debería crearlas: bastaría con que
`packages/contracts` tenga una prueba de tipos que actúe como consumidor. Conviene que el
propietario asigne ese esqueleto a un cambio concreto.

### H6 — ¿Se compromete el código generado?

`packages/contracts` es generado y no se edita a mano. Hay dos opciones:

- **No comprometerlo** (se ignora en Git y lo regenera Turborepo). La instantánea del OpenAPI ya
  comprometida es la fuente revisable.
- **Comprometerlo**, para que el diff del PR muestre también el impacto en TypeScript.

Se recomienda no comprometerlo: ya se revisa la instantánea del OpenAPI, y comprometer además la
salida generada duplica la revisión y abre la puerta a editarla a mano.

## Tamaño

La estimación de `foundations-plan` era de unas diez tareas. Con springdoc y la instantánea en el
backend, más el monorepo, `packages/config`, `packages/contracts`, las reglas de dependencia con sus
violaciones deliberadas y la integración continua, el cambio queda cerca de doce. Está bajo el
límite de quince, pero el PR probablemente pase de ochocientas líneas efectivas, sobre todo por
el archivo de bloqueo de pnpm (que es generado). Si en la fase de tareas se pasa del límite, se
parte así: **3a** springdoc, instantánea y criterio 7, del lado del backend; **3b** el monorepo
pnpm y Turborepo, orval y las reglas del frontend.

## Recomendación

Pasar a propuesta con H1 (a), H3 (b) como inclinación de diseño y H6 sin comprometer. H2, H4 y H5
quedan como preguntas para el propietario.
