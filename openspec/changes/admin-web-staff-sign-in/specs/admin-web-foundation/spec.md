# Delta para Fundación de `apps/admin-web`

- **Estado:** escenarios separados el 2026-11-05 de `admin-web-foundations-and-session`, tras la resolución de Q1
- **Fase:** F0 (cambio 13b-1, parte 2 de 2)
- **Cambio:** `admin-web-staff-sign-in`

**Convenciones de este delta.**

- **Cómo se fusiona este delta.** Se archiva después de `admin-web-foundations-and-session`. Bajo `## MODIFIED Requirements`, un requisito con su encabezado
  exacto trae el cuerpo vigente y **solo los escenarios que esta parte agrega**. Al archivar, los escenarios que la parte 1 ya
  publicó se conservan y estos se suman. No se repiten porque un escenario vive en un solo cambio.
- `AW64` es nuevo: conserva la cláusula de pantalla de `AW41`, cuyo resultado del cliente queda en la parte 1.
- Los valores fijados por el diseño son los de la especificación de la parte 1.

## MODIFIED Requirements

### Requisito: Desarrollo y vista previa comparten origen con la API por un proxy, sin CORS y sin tocar `apps/api`

El sistema DEBE servir la aplicación y la API bajo el mismo origen en `dev` y en `preview`, mediante un proxy de Vite que
reenvía toda ruta que empiece con `/api` hacia el backend local, igual que el nginx de producción reenvía `/api/v1/`
(`docs/05-despliegue.md`, `docs/03-seguridad.md`). Toda petición del navegador a la API DEBE ser de ruta relativa y del
mismo origen que la página, de modo que el navegador no emite peticiones previas (`OPTIONS`) de CORS. El proxy DEBE
conservar la cabecera `Origin` de la página, que el filtro de CSRF del backend compara con su lista de orígenes permitidos
(`http://localhost:5173` ya figura en `application-local.yml`). Este cambio NO DEBE agregar configuración de CORS, ni un
indicador que desactive el atributo `Secure` de las cookies, ni modificar ningún archivo de `apps/api`.
(Previously: el mismo cuerpo, publicado por `admin-web-foundations-and-session`, sin los escenarios de este delta.)

#### Escenario: [AW09] Ninguna petición sale del origen de la página

- **DADO** un recorrido completo de inicio de sesión en Chromium con registro de todas las peticiones
- **CUANDO** termina
- **ENTONCES** el origen de cada petición es el de la página y no se emitió ninguna petición `OPTIONS`

### Requisito: El analizador de `ProblemDetail` produce una unión de códigos conocidos más uno desconocido, y la interfaz nunca muestra `title` ni `detail`

El sistema DEBE convertir toda respuesta de error en una unión tipada formada por los quince códigos conocidos —
`authentication-failed`, `second-factor-invalid`, `session-expired`, `csrf-invalid`, `mfa-already-enrolled`,
`validation-failed`, `unsupported-media-type`, `too-many-requests`, `capacity-exceeded`, `internal-error`,
`authentication-required`, `token-invalid`, `token-expired`, `forbidden` y `resource-not-found` — más un caso desconocido.
El código DEBE leerse del `type`, cuya forma es `https://confia.hn/problems/<código>`. Un `type` ausente, de otro anfitrión
o de un código fuera de la lista DEBE producir el caso desconocido. La traducción de código a clave del catálogo DEBE ser
exhaustiva por construcción: un código nuevo en la unión sin clave rompe la verificación de tipos. La interfaz NO DEBE
mostrar nunca `title` ni `detail` (`docs/ui-ux/04` §9). Para `validation-failed` DEBE exponer la lista `errors` de
`{field, reason}`; para `too-many-requests` DEBE leer `Retry-After` en segundos enteros. Un caso desconocido, un
`internal-error` y una respuesta inesperada DEBEN mostrar el mensaje genérico de error inesperado y, cuando la respuesta
traiga `traceId`, mostrarlo visible y copiable (`docs/ui-ux/04` §9, regla de presentación 1).
(Previously: el mismo cuerpo, publicado por `admin-web-foundations-and-session`, sin los escenarios de este delta.)

#### Escenario: [AW49] `title` y `detail` no se muestran

- **DADO** una respuesta `401 authentication-failed` con `title` «CENTINELA-TITULO» y `detail` «CENTINELA-DETALLE»
- **CUANDO** la pantalla de inicio de sesión la procesa
- **ENTONCES** ninguno de los dos textos aparece en el documento, en ningún atributo accesible ni en la consola

#### Escenario: [AW51] Una razón o un campo desconocidos usan el texto genérico

- **DADO** un `validation-failed` con una razón fuera de las conocidas (`not-blank`, `size`, `max`) y otro con un campo que
  el formulario no tiene
- **CUANDO** la pantalla los procesa
- **ENTONCES** la razón desconocida se muestra con el texto genérico del campo y el campo ajeno se muestra en el resumen,
  sin descartar ningún error

#### Escenario: [AW64] Una respuesta inesperada al iniciar sesión muestra el mensaje genérico

- **DADO** un `200` de inicio de sesión cuyo cuerpo no cumple `LoginResponse`
- **CUANDO** la pantalla de inicio de sesión lo procesa
- **ENTONCES** muestra el mensaje genérico de error inesperado y no conserva ningún token

#### Escenario: [AW54] El mensaje genérico muestra el identificador de traza

- **DADO** un `500 internal-error` con `traceId` «01J8Z4K2QF9P0X»
- **CUANDO** la pantalla lo procesa
- **ENTONCES** muestra el mensaje genérico, el identificador visible y un control con nombre accesible que lo copia
