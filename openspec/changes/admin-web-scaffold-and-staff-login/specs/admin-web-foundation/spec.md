# Capacidad: Fundación de `apps/admin-web`

- **Identificador:** admin-web-foundation
- **Estado:** pendiente de aprobación del propietario del producto
- **Fase:** F0 (cambio 13b-1)
- **Cambio:** `admin-web-scaffold-and-staff-login`
- **Propuesta:** `proposal.md`, aprobada por el propietario el 2026-11-04, con las decisiones P1, P2, P3 y P6 a P10 y la
  resolución de Q2 vinculantes

## Propósito

Capacidad nueva. Especifica el comportamiento observable de la aplicación del panel administrativo en el momento en que
nace: el paquete `apps/admin-web` y su compilación, el seguimiento del tema del sistema operativo, el mismo origen con la
API por un proxy de desarrollo y sin CORS, la compatibilidad del resultado con la política de contenido (CSP) de
`docs/03`, el enrutador por código, el catálogo de textos `admin` con trato de «usted», el cliente de API único, la
traducción de `ProblemDetail` a una unión de códigos conocidos y la separación entre presentación y datos.

NO le corresponde: las pantallas de acceso y la vida de la sesión (capacidad `staff-sign-in`); las reglas estáticas, la
puerta de accesibilidad sobre rutas, la integración continua y las dependencias (capacidad `build-integrity`); los
componentes de `packages/ui` (capacidad `design-system`); ni el contrato de los endpoints, que ya está especificado en
`identity` y `web-edge` y que este cambio no modifica.

Fuentes de verdad: `docs/01-arquitectura.md`, `docs/03-seguridad.md` (CSP y cookies), `docs/ui-ux/02`, `docs/ui-ux/04` §9,
`docs/ui-ux/05`, ADR-0006, ADR-0011, ADR-0018 y `CLAUDE.md`.

## Convenciones de esta especificación

- Los escenarios se numeran `AW01` en adelante. El prefijo `AW` no existe en ninguna otra especificación ni delta.
- Esta especificación describe comportamiento observable. Los nombres de carpeta, de módulo y de variable los fija el
  diseño.
- **Valores que fija el diseño y que esta especificación no fija:** la dirección y el nombre de la variable del backend
  local al que apunta el proxy; los nombres de las rutas; el nombre y la forma de los módulos de `shared/api`; el tiempo
  límite del cliente, que DEBE ser estrictamente mayor que el retardo máximo de 30 s que documenta `docs/ui-ux/06`; el
  valor por defecto cuando falta un `Retry-After` utilizable y su tope de presentación; la lista de formas verbales de
  tuteo que la prueba de voz rechaza; el destino del registro de un código Problem desconocido; la cabecera de CSP exacta
  con la que corren los recorridos; el mecanismo que simula el `Set-Cookie`, que decide la sonda del paso 0; los retardos
  de reintento de las consultas de lectura.
- Los textos de interfaz de esta capacidad se leen del catálogo `admin`; esta especificación fija el texto solo cuando un
  documento de `docs/ui-ux` ya lo fija.

## Requisitos

### Requisito: `apps/admin-web` es un paquete del espacio de trabajo con aplicación React 19 y Vite que compila bajo las reglas del monorepo

El sistema DEBE ofrecer `apps/admin-web` como paquete del espacio de trabajo pnpm, con React 19, Vite, Tailwind CSS v4 por
`@tailwindcss/vite`, la hoja de estilos `@confia/ui/styles.css` y los recursos de textos `uiResources` de `@confia/ui`
(`docs/01-arquitectura.md` §3). El paquete DEBE declarar los scripts `build`, `lint`, `typecheck` y `test`, y cada una de
esas tareas DEBE ejecutarse sobre él de verdad en `pnpm turbo run lint typecheck test build --filter=!@confia/api`, no
omitirse por falta de scripts. NO DEBE importar de otra aplicación ni de una ruta interna de `@confia/ui` (reglas de
dependencia vigentes de `build-integrity`).

#### Escenario: [AW01] Las cuatro tareas del monorepo se ejecutan sobre la aplicación

- **DADO** el monorepo con `apps/admin-web` incluido
- **CUANDO** se ejecuta `pnpm turbo run lint typecheck test build --filter=!@confia/api`
- **ENTONCES** cada una de las cuatro tareas se ejecuta en `@confia/admin-web` y la de pruebas ejecuta al menos una prueba
  real

#### Escenario: [AW02] La compilación produce activos estáticos que sirve `vite preview`

- **DADO** la compilación de producción de la aplicación
- **CUANDO** se sirve con `vite preview` y se abre la ruta raíz en Chromium
- **ENTONCES** la página se renderiza sin errores de página no capturados ni mensajes de error de consola

#### Escenario: [AW03] La aplicación consume la hoja de estilos y los textos de `@confia/ui`

- **DADO** la aplicación renderizada en Chromium
- **CUANDO** se lee el valor de una propiedad personalizada de token semántico en `:root` y se renderiza un componente de
  `@confia/ui` con texto
- **ENTONCES** la propiedad tiene valor y el texto visible proviene del catálogo `es-HN` de `@confia/ui`, no de una clave

#### Escenario: [AW04] Importar de una aplicación o de una ruta interna de `@confia/ui` rompe la construcción

- **DADO** las violaciones deliberadas de `build-integrity` en las que un archivo de `apps/admin-web` importa de otra
  aplicación o de una ruta interna de `packages/ui`
- **CUANDO** se ejecuta la verificación de dependencias del monorepo
- **ENTONCES** falla nombrando la regla violada

### Requisito: La aplicación sigue el tema claro u oscuro del sistema operativo y no ofrece selector de tema

El sistema DEBE aplicar el tema según la preferencia del sistema operativo (`prefers-color-scheme`), mediante los tokens de
`@confia/ui`, y NO DEBE establecer el atributo `data-theme` ni ofrecer ningún control para cambiar de tema en este cambio
(decisión P10; el selector visible es del cambio 13b-2). Una persona que cambia la preferencia del sistema DEBE ver el
otro tema sin recargar.

#### Escenario: [AW05] El tema sigue la preferencia del sistema

- **DADO** la aplicación abierta en Chromium con la preferencia de color `light` emulada y luego `dark`
- **CUANDO** se lee el color de fondo calculado del documento en cada caso
- **ENTONCES** los dos colores son distintos y coinciden con el valor del token semántico de fondo de cada tema
- **Y** el elemento raíz no tiene el atributo `data-theme`

#### Escenario: [AW06] No existe un control de tema

- **DADO** cada pantalla de `apps/admin-web` renderizada
- **CUANDO** se busca un control cuyo nombre accesible se refiera al tema, al modo claro o al modo oscuro
- **ENTONCES** no existe ninguno
- **Y** este escenario deja de ser cierto el día que el cambio 13b-2 entregue el selector

### Requisito: Desarrollo y vista previa comparten origen con la API por un proxy, sin CORS y sin tocar `apps/api`

El sistema DEBE servir la aplicación y la API bajo el mismo origen en `dev` y en `preview`, mediante un proxy de Vite que
reenvía toda ruta que empiece con `/api` hacia el backend local, igual que el nginx de producción reenvía `/api/v1/`
(`docs/05-despliegue.md`, `docs/03-seguridad.md`). Toda petición del navegador a la API DEBE ser de ruta relativa y del
mismo origen que la página, de modo que el navegador no emite peticiones previas (`OPTIONS`) de CORS. El proxy DEBE
conservar la cabecera `Origin` de la página, que el filtro de CSRF del backend compara con su lista de orígenes permitidos
(`http://localhost:5173` ya figura en `application-local.yml`). Este cambio NO DEBE agregar configuración de CORS, ni un
indicador que desactive el atributo `Secure` de las cookies, ni modificar ningún archivo de `apps/api`.

#### Escenario: [AW07] `dev` reenvía `/api` al backend configurado

- **DADO** un servidor de pruebas que hace de backend y la aplicación en modo `dev` apuntando a él
- **CUANDO** el navegador pide `/api/v1/auth/sessions` en el origen de la página
- **ENTONCES** el servidor de pruebas recibe la misma ruta y método
- **Y** la cabecera `Origin` que recibe es la de la página

#### Escenario: [AW08] `preview` reenvía `/api` igual que `dev`

- **DADO** el mismo servidor de pruebas y la aplicación servida con `vite preview`
- **CUANDO** el navegador pide `/api/v1/auth/sessions/refresh`
- **ENTONCES** el servidor de pruebas recibe la misma ruta y método, con el `Origin` de la página

#### Escenario: [AW09] Ninguna petición sale del origen de la página

- **DADO** un recorrido completo de inicio de sesión en Chromium con registro de todas las peticiones
- **CUANDO** termina
- **ENTONCES** el origen de cada petición es el de la página y no se emitió ninguna petición `OPTIONS`

#### Escenario: [AW10] El cambio no toca `apps/api`

- **DADO** el diff de la rama del cambio frente a `main`
- **CUANDO** se listan los archivos modificados
- **ENTONCES** no hay ninguno bajo `apps/api/`

#### Escenario: [AW11] No existe CORS ni un indicador que desactive `Secure`

- **DADO** la configuración de Vite, los scripts del paquete y el código de la aplicación
- **CUANDO** se busca una cabecera `Access-Control-Allow-*` o un indicador, variable o rama que quite `Secure` a una cookie
- **ENTONCES** no existe ninguno
- **Y** el desarrollo local sobre HTTPS local queda como la salida admitida si la sonda del paso 0 demuestra que las cookies
  `Secure` y con prefijo fallan sobre `http://localhost`

### Requisito: El resultado de compilación es compatible con la CSP estricta de `docs/03`

El sistema DEBE producir un resultado de compilación que funcione bajo una política de contenido sin código en línea: sin
`<script>` sin `src`, sin `eval` ni `new Function`, sin atributos ni elementos `style` en línea, con `script-src` y
`style-src` limitados a `'self'`, `img-src 'self' data: blob:` y `connect-src 'self'` (`docs/03-seguridad.md`, sección de
CSP del panel). El resultado de producción NO DEBE contener el service worker de MSW ni ninguna ruta de prueba. El *nonce*
de la CSP de producción para una SPA estática y su despliegue en nginx son del cambio 11.

#### Escenario: [AW12] Los recorridos corren sin violaciones de CSP

- **DADO** `vite preview` respondiendo con la CSP descrita, sin `'unsafe-inline'` ni `'unsafe-eval'`
- **CUANDO** corre cada recorrido de navegador del cambio
- **ENTONCES** el evento `securitypolicyviolation` no se dispara ni una vez y ninguna petición queda bloqueada por la CSP

#### Escenario: [AW13] El HTML compilado no contiene código en línea

- **DADO** el directorio de salida de la compilación de producción
- **CUANDO** se analiza cada archivo HTML
- **ENTONCES** todo `<script>` tiene `src`, no hay `<style>` ni atributo `style`, y ningún controlador de eventos en línea

#### Escenario: [AW14] El service worker de MSW no llega a producción

- **DADO** el directorio de salida de la compilación de producción
- **CUANDO** se busca el archivo del service worker de MSW y cualquier referencia a él
- **ENTONCES** no existe ni se referencia

#### Escenario: [AW15] La ruta inaccesible de prueba no llega a producción

- **DADO** la ruta deliberadamente inaccesible que usa la puerta de accesibilidad para demostrar que falla
- **CUANDO** se analiza el resultado de compilación de producción
- **ENTONCES** esa ruta y su código no están en él

### Requisito: El enrutamiento es TanStack Router con rutas definidas por código y guardas en `beforeLoad`

El sistema DEBE definir las rutas en código (`createRoute`), un módulo de rutas por funcionalidad, sin archivo de árbol
generado y sin supresiones de ESLint ni de TypeScript que el escáner de supresiones rechazaría (ADR-0006; se reevalúa con
más de 15 rutas). Las guardas de acceso DEBEN ejecutarse en `beforeLoad`. Una ruta inexistente DEBE mostrar un estado de
ruta no encontrada con texto del catálogo y una acción para ir al inicio de sesión, no el texto por defecto de la
biblioteca. En este cambio NO DEBE existir parámetro `redirect` ni regreso a una ruta profunda: solo hay una ruta
protegida.

#### Escenario: [AW16] No existe un archivo de árbol de rutas generado

- **DADO** el árbol de `apps/admin-web`
- **CUANDO** se busca un archivo de rutas generado (por ejemplo `routeTree.gen.ts`) o una tarea de generación previa al lint
- **ENTONCES** no existe

#### Escenario: [AW17] El código de la aplicación no necesita supresiones

- **DADO** el código de `apps/admin-web`
- **CUANDO** se ejecuta el escáner de supresiones sobre `apps/`
- **ENTONCES** no encuentra ninguna directiva `eslint-disable`, `@ts-nocheck`, `@ts-ignore` ni `@ts-expect-error` sin la cita de un ADR

#### Escenario: [AW18] Ruta inexistente

- **DADO** la aplicación abierta
- **CUANDO** se navega a una ruta que no existe
- **ENTONCES** se muestra un estado de ruta no encontrada con un encabezado de nivel 1 y un enlace al inicio de sesión, con
  textos del catálogo `admin`
- **Y** no aparece ningún texto en inglés de la biblioteca de enrutamiento

#### Escenario: [AW19] No hay parámetro `redirect`

- **DADO** una persona sin sesión que abre la ruta de la página autenticada
- **CUANDO** la guarda la lleva al inicio de sesión
- **ENTONCES** la URL resultante no contiene un parámetro `redirect` ni el destino original

### Requisito: Las consultas de lectura reintentan de forma acotada y las mutaciones nunca reintentan

El sistema DEBE configurar TanStack Query de modo que ninguna mutación se reintente nunca (`docs/ui-ux/04` §9, regla 3) y
las consultas de lectura reintenten como máximo dos veces con retroceso exponencial, sin reintentar ante una respuesta
`4xx`. Las consultas de la sesión NO DEBEN refrescarse al recuperar el foco de la ventana ni al reconectar la red (ver
`staff-sign-in`).

#### Escenario: [AW20] Una mutación que falla por red hace una sola petición

- **DADO** una mutación de la aplicación cuyo transporte falla con un error de red
- **CUANDO** se ejecuta
- **ENTONCES** el servidor de pruebas registra exactamente una petición

#### Escenario: [AW21] Una lectura reintenta como máximo dos veces ante un `5xx`

- **DADO** una consulta de lectura cuya respuesta es siempre `503`
- **CUANDO** se ejecuta hasta agotar sus reintentos
- **ENTONCES** el servidor registra como máximo tres peticiones en total, separadas por un retroceso creciente

#### Escenario: [AW22] Una lectura no reintenta ante un `4xx`

- **DADO** una consulta de lectura cuya respuesta es `401` o `404`
- **CUANDO** se ejecuta
- **ENTONCES** el servidor registra exactamente una petición

#### Escenario: [AW23] La configuración por defecto de Query se verifica

- **DADO** el cliente de Query de la aplicación
- **CUANDO** se leen sus opciones por defecto
- **ENTONCES** las mutaciones tienen cero reintentos y las lecturas tienen el máximo de dos reintentos

### Requisito: Todo texto de la aplicación proviene del catálogo `admin`, tipado, con `es-HN` por defecto e inglés preparado sin traducir

El sistema DEBE servir todo texto de interfaz de `apps/admin-web` desde un catálogo i18next `admin` junto a los recursos de
`@confia/ui`, con `es-HN` como idioma predeterminado completo y la infraestructura de inglés preparada sin traducciones
reales (ADR-0011), igual que `packages/ui`. El conjunto de claves DEBE ser el tipo `AdminKey`, derivado del archivo
`es-HN` del catálogo `admin`. Una clave ausente en el idioma activo DEBE resolverse con el texto de `es-HN` y NO DEBE
mostrarse la clave cruda. Una prueba DEBE fallar ante una clave usada que no exista en el catálogo o que no pueda
resolverse estáticamente.

#### Escenario: [AW24] Texto desde el catálogo por defecto

- **DADO** el idioma predeterminado y una pantalla de la aplicación
- **CUANDO** se renderiza
- **ENTONCES** todo texto visible y todo nombre accesible proviene de una clave del catálogo `admin` o de `@confia/ui`

#### Escenario: [AW25] Idioma sin traducción

- **DADO** el idioma activo `en` sin traducciones reales
- **CUANDO** se renderiza una pantalla
- **ENTONCES** muestra los textos de `es-HN` y ninguna clave cruda

#### Escenario: [AW26] La clave inexistente es un error de tipos

- **DADO** una prueba de tipos que usa una clave que no está en `AdminKey`
- **CUANDO** se ejecuta la verificación de tipos
- **ENTONCES** falla señalando la clave

#### Escenario: [AW27] Clave inexistente o construida por concatenación

- **DADO** las violaciones deliberadas de `build-integrity` con una clave ausente del catálogo `admin` y con una clave
  construida por concatenación
- **CUANDO** se ejecuta la verificación de claves
- **ENTONCES** falla nombrando el archivo y, cuando TypeScript puede conocerla, la clave

#### Escenario: [AW28] Catálogo completo para las pantallas

- **DADO** el catálogo `es-HN` `admin` y todas las claves que usa la aplicación
- **CUANDO** se ejecuta la verificación de claves
- **ENTONCES** todas existen y la verificación evalúa al menos una clave real, no un conjunto vacío

### Requisito: El personal recibe trato de «usted» y el tono del portal no cambia

El sistema DEBE redactar los textos del catálogo `admin` dirigidos a la persona en «usted» (decisión P1): formas de
imperativo y de indicativo de tercera persona («Inicie sesión», «Su sesión»), sin tuteo ni voseo. La guía de contenido
`docs/ui-ux/05` DEBE recibir una nota fechada que fije ese trato para el personal y mantenga el tono del portal; el texto
original de la guía NO DEBE reescribirse. Una prueba DEBE rechazar las formas de tuteo y de voseo que fija el diseño.

#### Escenario: [AW29] Ningún texto del catálogo `admin` usa tuteo

- **DADO** todos los valores del catálogo `es-HN` `admin`
- **CUANDO** una prueba los compara con la lista de formas de tuteo y de voseo que fija el diseño (por ejemplo `tu`, `tus`,
  `tienes`, `puedes`, `inicia` como imperativo de tú, `vos`)
- **ENTONCES** ninguno coincide

#### Escenario: [AW30] La prueba de voz rechaza el tuteo

- **DADO** la violación deliberada de un valor de catálogo con el texto «Inicia sesión de nuevo»
- **CUANDO** corre la prueba de voz
- **ENTONCES** falla nombrando la clave

#### Escenario: [AW31] La nota de `docs/ui-ux/05` es fechada y no reescribe nada

- **DADO** el diff de `docs/ui-ux/05-guia-de-contenido-y-voz.md` frente a `main`
- **CUANDO** se inspecciona
- **ENTONCES** no contiene líneas eliminadas ni modificadas, solo una nota fechada agregada
- **Y** la nota declara «usted» para el personal y declara que el tono del portal no cambia

#### Escenario: [AW32] Los textos fijados por la documentación se conservan en «usted»

- **DADO** el mensaje de credenciales inválidas de `docs/ui-ux/05`:121 y el de sesión vencida de `docs/ui-ux/04`:727
- **CUANDO** se leen sus claves en el catálogo `admin`
- **ENTONCES** son, exactamente, «El correo o la contraseña no son correctos.» y «Su sesión se cerró por seguridad. Vuelva
  a iniciar sesión.»

### Requisito: El cliente de API es único, escrito a mano, del mismo origen y valida cada respuesta con los esquemas del contrato

El sistema DEBE concentrar toda comunicación con la API en un cliente en `src/shared/api`, el único lugar de la aplicación
con `fetch`. El cliente DEBE usar rutas relativas, `credentials: "same-origin"` y los esquemas Zod de
`@confia/contracts/admin` para validar cada respuesta con cuerpo. Para las seis operaciones de este cambio DEBE enviar
exactamente estas cabeceras y cuerpos, y NINGUNA lleva `Idempotency-Key`, que no aplica a la autenticación:

| Operación | Método y ruta | `Authorization` | `X-CSRF-Token` | Cuerpo |
|---|---|---|---|---|
| Iniciar sesión | `POST /api/v1/auth/sessions` | no | no | JSON `{identifier, password}` |
| Refrescar | `POST /api/v1/auth/sessions/refresh` | no | valor de la cookie CSRF | ninguno |
| Cerrar sesión | `DELETE /api/v1/auth/sessions/current` | no | valor de la cookie CSRF, si existe | ninguno |
| Leer la sesión | `GET /api/v1/auth/sessions/current` | `Bearer` del token de acceso | no | ninguno |
| Inscribir | `POST /api/v1/auth/mfa/enrollments` | `Bearer` del token restringido | no | ninguno |
| Verificar | `POST /api/v1/auth/mfa/verifications` | `Bearer` del token restringido | no | JSON con exactamente uno de `code` o `recoveryCode` |

El cliente DEBE leer la cookie CSRF `__Host-confia_csrf` por su nombre exacto. Una respuesta que no cumpla el esquema
esperado DEBE tratarse como un resultado distinguible de «respuesta inesperada», no como un éxito ni como un fallo de
red, y NO DEBE usarse parcialmente. El cliente NO DEBE cancelar una petición por un tiempo límite propio menor que el
retardo máximo documentado de 30 s.

#### Escenario: [AW33] Inicio de sesión

- **DADO** el cliente y un servidor de pruebas que registra la petición
- **CUANDO** inicia sesión con un identificador y una contraseña
- **ENTONCES** la petición es `POST /api/v1/auth/sessions` con `Content-Type: application/json` y cuerpo con exactamente
  `identifier` y `password`
- **Y** no lleva `Authorization`, `X-CSRF-Token` ni `Idempotency-Key`

#### Escenario: [AW34] Refresco

- **DADO** la cookie `__Host-confia_csrf` con el valor `csrf-A`
- **CUANDO** el cliente refresca la sesión
- **ENTONCES** la petición es `POST /api/v1/auth/sessions/refresh` con `X-CSRF-Token: csrf-A`, sin `Authorization` y sin cuerpo
- **Y** la petición lleva las cookies del mismo origen

#### Escenario: [AW35] Cierre de sesión

- **DADO** la cookie `__Host-confia_csrf` con el valor `csrf-A`
- **CUANDO** el cliente cierra la sesión
- **ENTONCES** la petición es `DELETE /api/v1/auth/sessions/current` con `X-CSRF-Token: csrf-A` y sin `Authorization`

#### Escenario: [AW36] Cierre de sesión sin cookie CSRF

- **DADO** que no existe la cookie CSRF
- **CUANDO** el cliente cierra la sesión
- **ENTONCES** envía el `DELETE` sin la cabecera `X-CSRF-Token` y no inventa un valor

#### Escenario: [AW37] Lectura de la sesión

- **DADO** un token de acceso en memoria
- **CUANDO** el cliente lee `GET /api/v1/auth/sessions/current`
- **ENTONCES** la petición lleva `Authorization: Bearer <token de acceso>` y ninguna cabecera `X-CSRF-Token`

#### Escenario: [AW38] Inscripción y verificación con el token restringido

- **DADO** un token restringido en memoria
- **CUANDO** el cliente inscribe y luego verifica con `code`, y en otra ejecución verifica con `recoveryCode`
- **ENTONCES** la inscripción es `POST /api/v1/auth/mfa/enrollments` con `Authorization: Bearer <token restringido>` y sin
  cuerpo
- **Y** cada verificación es `POST /api/v1/auth/mfa/verifications` con el mismo `Bearer` y un cuerpo con exactamente uno de
  `code` o `recoveryCode`

#### Escenario: [AW39] Ninguna operación lleva `Idempotency-Key`

- **DADO** las seis operaciones ejecutadas por el cliente
- **CUANDO** se inspeccionan las cabeceras de cada petición
- **ENTONCES** ninguna contiene `Idempotency-Key`

#### Escenario: [AW40] La cookie CSRF se lee por nombre exacto

- **DADO** una cookie llamada `x__Host-confia_csrf` con el valor `falso` y ninguna cookie `__Host-confia_csrf`
- **CUANDO** el cliente busca el valor CSRF
- **ENTONCES** concluye que no existe

#### Escenario: [AW41] Una respuesta que no cumple el esquema es una respuesta inesperada

- **DADO** un `200` de inicio de sesión cuyo cuerpo no cumple `LoginResponse`
- **CUANDO** el cliente lo procesa
- **ENTONCES** devuelve el resultado «respuesta inesperada» sin ningún token y la pantalla muestra el mensaje genérico de
  error inesperado

#### Escenario: [AW42] La respuesta de inicio de sesión se distingue por `status`

- **DADO** respuestas válidas con `status` `authenticated`, `second-factor-required` y `second-factor-enrollment-required`
- **CUANDO** el cliente las procesa
- **ENTONCES** cada una produce su resultado tipado y distinto

#### Escenario: [AW43] Error de red y cuerpo de error ilegible

- **DADO** una petición que falla por red y otra con respuesta `502` de cuerpo HTML
- **CUANDO** el cliente las procesa
- **ENTONCES** la primera produce el resultado «error de red», sin estado HTTP, y la segunda el resultado de código
  desconocido con estado `502`

#### Escenario: [AW44] El cliente espera una respuesta lenta

- **DADO** una respuesta del servidor simulado que tarda 31 s con una prueba cuyo tiempo límite se declara explícitamente
  mayor
- **CUANDO** el cliente la espera
- **ENTONCES** la procesa como cualquier otra, sin cancelarla

#### Escenario: [AW45] Las rutas son relativas

- **DADO** el código de `shared/api`
- **CUANDO** se inspeccionan las URL que construye
- **ENTONCES** todas comienzan con `/api/` y ninguna contiene un esquema ni un host

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

#### Escenario: [AW46] Los quince códigos conocidos se reconocen

- **DADO** una tabla de quince respuestas, una por código conocido, con su `type` `https://confia.hn/problems/<código>`
- **CUANDO** el analizador procesa cada una
- **ENTONCES** devuelve el código correspondiente en cada caso

#### Escenario: [AW47] Un código fuera de la lista, un `type` ausente y un anfitrión ajeno son desconocidos

- **DADO** respuestas con `type` `https://confia.hn/problems/algo-nuevo`, sin `type` y con
  `https://otro.example/problems/internal-error`
- **CUANDO** el analizador las procesa
- **ENTONCES** las tres producen el caso desconocido

#### Escenario: [AW48] La traducción a claves es exhaustiva

- **DADO** una prueba de tipos que agrega un código a la unión sin agregarlo al mapa de claves
- **CUANDO** se ejecuta la verificación de tipos
- **ENTONCES** falla señalando el código sin clave

#### Escenario: [AW49] `title` y `detail` no se muestran

- **DADO** una respuesta `401 authentication-failed` con `title` «CENTINELA-TITULO» y `detail` «CENTINELA-DETALLE»
- **CUANDO** la pantalla de inicio de sesión la procesa
- **ENTONCES** ninguno de los dos textos aparece en el documento, en ningún atributo accesible ni en la consola

#### Escenario: [AW50] `validation-failed` expone los errores por campo

- **DADO** una respuesta `400 validation-failed` con `errors` `[{"field":"identifier","reason":"not-blank"}]`
- **CUANDO** el analizador la procesa
- **ENTONCES** expone la lista con `field` y `reason`
- **Y** una respuesta `validation-failed` sin `errors` produce una lista vacía

#### Escenario: [AW51] Una razón o un campo desconocidos usan el texto genérico

- **DADO** un `validation-failed` con una razón fuera de las conocidas (`not-blank`, `size`, `max`) y otro con un campo que
  el formulario no tiene
- **CUANDO** la pantalla los procesa
- **ENTONCES** la razón desconocida se muestra con el texto genérico del campo y el campo ajeno se muestra en el resumen,
  sin descartar ningún error

#### Escenario: [AW52] `Retry-After` en segundos enteros

- **DADO** una respuesta `429` con `Retry-After: 15`
- **CUANDO** el analizador la procesa
- **ENTONCES** expone 15 segundos

#### Escenario: [AW53] `Retry-After` ausente o no utilizable

- **DADO** una respuesta `429` sin `Retry-After`, con `Retry-After: 0`, con `Retry-After: abc` y con una fecha HTTP
- **CUANDO** el analizador las procesa
- **ENTONCES** las cuatro usan el valor por defecto que fija el diseño y ninguna produce un número negativo, cero ni `NaN`

#### Escenario: [AW54] El mensaje genérico muestra el identificador de traza

- **DADO** un `500 internal-error` con `traceId` «01J8Z4K2QF9P0X»
- **CUANDO** la pantalla lo procesa
- **ENTONCES** muestra el mensaje genérico, el identificador visible y un control con nombre accesible que lo copia

#### Escenario: [AW55] El caso desconocido se registra sin el cuerpo

- **DADO** una respuesta de código desconocido con `title`, `detail` y `instance`
- **CUANDO** el analizador registra el hallazgo en el destino que fija el diseño
- **ENTONCES** el registro contiene como máximo el `type` y el estado, y ni el `title`, ni el `detail`, ni el `instance`, ni
  ningún token o dato de la persona (`CLAUDE.md`, regla 11)

#### Escenario: [AW56] Un cuerpo de error no JSON se trata como desconocido

- **DADO** una respuesta `503` con `Content-Type: text/html`
- **CUANDO** el analizador la procesa
- **ENTONCES** produce el caso desconocido con el estado `503` y no lanza una excepción

### Requisito: Los componentes de presentación reciben datos y callbacks por propiedades y se prueban sin proveedor de consultas ni red

El sistema DEBE implementar cada pantalla como un contenedor que obtiene datos y ejecuta mutaciones, y un componente de
presentación que solo recibe propiedades y callbacks (`CLAUDE.md`, sección «Frontend»). Todo componente de presentación
DEBE renderizarse y probarse sin proveedor de consultas y sin red, y DEBE definir explícitamente sus estados de carga,
vacío, error y éxito cuando le correspondan, o declarar por qué un estado no le corresponde.

#### Escenario: [AW57] Renderizado sin proveedor de consultas ni red

- **DADO** una prueba cuyo entorno falla ante cualquier petición no simulada
- **CUANDO** se renderiza cada componente de presentación de `apps/admin-web` solo con propiedades, sin proveedor de
  TanStack Query
- **ENTONCES** todos renderizan sin error y sin ninguna petición

#### Escenario: [AW58] Cada componente de presentación declara sus estados

- **DADO** cada componente de presentación de pantalla
- **CUANDO** se listan sus pruebas
- **ENTONCES** hay una prueba por cada estado de carga, vacío, error y éxito que le corresponda
- **Y** cada estado que no le corresponde queda declarado en un comentario del archivo de pruebas que cita el motivo

### Requisito: Ausencia del shell, del selector de tema, de `Dialog` y de las demás exclusiones (brecha con destino: cambios 13b-2 y 11)

El sistema NO DEBE contener en este cambio: el shell (`Topbar`, `Sidebar`, `PageHeader`, migas de pan, enlace para saltar
al contenido, menú de usuario y navegación), el selector visible de tema, el componente `Dialog` ni la identidad de la
persona en el shell ni el contenido del panel vacío (P4 y P5, con decisión del propietario pendiente): son del **cambio
13b-2**. NO DEBE contener el *nonce* de la CSP de producción, el despliegue de `admin-web` en nginx ni la regresión
visual: son del **cambio 11**. NO DEBE contener `apps/portal-web`, que llega con el primer cambio que entregue una pantalla
del portal. NO DEBE contener un generador de operaciones de orval, que se reevalúa con el primer endpoint de negocio.

#### Escenario: [AW59] Las únicas rutas son las de este cambio

- **DADO** el árbol de rutas de `apps/admin-web`
- **CUANDO** se enumeran sus rutas
- **ENTONCES** son únicamente las de arranque, inicio de sesión, verificación, inscripción, página autenticada y ruta no
  encontrada
- **Y** este escenario deja de ser cierto el día que el cambio 13b-2 entregue el shell

#### Escenario: [AW60] No existen componentes del shell en la aplicación

- **DADO** el código de `apps/admin-web` y el punto de entrada de `@confia/ui`
- **CUANDO** se buscan `Topbar`, `Sidebar`, `PageHeader`, `Breadcrumb` y `Dialog`
- **ENTONCES** no existe ninguno
- **Y** este escenario deja de ser cierto el día que el cambio 13b-2 los entregue

#### Escenario: [AW61] No existe `apps/portal-web`

- **DADO** el directorio `apps/`
- **CUANDO** se listan sus paquetes
- **ENTONCES** `portal-web` no existe
- **Y** este escenario deja de ser cierto el día que el primer cambio con pantalla del portal lo cree

#### Escenario: [AW62] Ningún generador de operaciones de orval

- **DADO** `packages/contracts/orval.config.ts`
- **CUANDO** se inspeccionan sus clientes
- **ENTONCES** sigue generando solo esquemas Zod, sin rutas ni cabeceras de operaciones

#### Escenario: [AW63] La CSP de producción no se entrega

- **DADO** el repositorio tras este cambio
- **CUANDO** se busca una configuración de nginx de `admin-web` o un *nonce* de CSP
- **ENTONCES** no existe ninguno
- **Y** este escenario deja de ser cierto el día que el cambio 11 los entregue
