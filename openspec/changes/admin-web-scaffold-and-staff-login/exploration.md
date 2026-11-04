# Exploración: admin-web-shell-and-staff-login (F0, cambio 13b, frontend)

- **Rama:** `change/admin-web-shell-and-staff-login`, desde `main` `2b71a9b`.
- **Fecha:** 2026-11-04. Exploración de solo lectura: no se ejecutó ningún build ni comando.
- **Copia en Engram:** `sdd/admin-web-shell-and-staff-login/explore` (observación 781).

## 1. Estado actual

### Espacio de trabajo

- `pnpm-workspace.yaml:1-4` ya incluye `apps/*`, pero hoy solo existe `apps/api` (con un script `verify` hacia `./mvnw`). **`apps/admin-web` y `apps/portal-web` no existen.**
- El catálogo (`pnpm-workspace.yaml:27-68`) trae React 19.3, Vite 8.3.4, Vitest 5.0.5, Storybook 10.6.2, el núcleo de Playwright 1.64.1, axe-core 4.14.0, zod 4.6.6 e i18next. **No hay `@tanstack/*`, `msw`, `@playwright/test`, `@axe-core/playwright` ni biblioteca de QR**: todas son dependencias nuevas, sujetas a la regla de antigüedad de dos semanas (`pnpm-workspace.yaml:77`).
- `turbo.json:37-45` ya define la tarea `test:a11y`. No hay tarea `dev` ni de e2e.
- `packages/config` contiene `tsconfig.base.json` (estricto, `exactOptionalPropertyTypes`), `eslint.js` y `vitest.js`; `confiaEslint({ui, react, portal})` está en `packages/config/eslint.js:259-277`.
- `tooling/` tiene pruebas que recorren el árbol real (`cruise(["apps","packages"])`, `tooling/src/dependency-rules.test.ts:85-89`) y el escáner de supresiones (`tooling/src/suppression-scanner.ts:9,12`), que hoy solo corre sobre `packages/ui` (`tooling/src/suppression-citations.test.ts:37-38`).

### Reglas que la aplicación hereda

- `.dependency-cruiser.cjs:12-77`: las apps no importan apps (:19-24), `contracts` solo se importa por `admin` o `portal` (:25-33), el portal no importa el panel (:34-42), y las capas de `ui` (:43-76), incluida `ui-never-fetches-data` (:59-67, que solo ve imports de bibliotecas de datos).
- **Brecha heredada de 13a:** la regla de red no se puede expresar en dependency-cruiser, porque `fetch` es un global y no un import. Existe solo en ESLint y solo para `ui`: `NO_REQUESTS` (`packages/config/eslint.js:226-229`), activada por `uiSourceRules` (:239-253) con `ui: true`.
  - Con `ui: true`, `admin-web` tendría prohibido su propio cliente de API.
  - Sin él, la app pierde también las reglas de texto en JSX (:49-63), de formato manual (:65-70) y de literales visuales (:101-117).
  - Hay que separar las reglas de fuente de app de las reglas solo de `ui`. En la configuración plana, una entrada posterior reemplaza a la anterior (:14-15, :146-149).
- **CI** (`.github/workflows/ci.yml`):
  - El job `frontend` (:102-127) ejecuta `pnpm turbo run lint typecheck test build --filter=!@confia/api`, con 10 minutos de límite. Su comando está fijado por `tooling/src/ci-workflow.test.ts:37-63` [BX19], así que `admin-web` entra solo con sus propias tareas `build`, `lint` y `test`.
  - `design-system-accessibility` (:172-206) tiene 15 minutos de límite y corre en Chromium, solo sobre `@confia/ui`.
  - El encabezado (:3-9) dice que el job e2e y `quality-gate` llegan con el cambio 11 (contenedores). Hoy no hay infraestructura de API en CI.
- **ADR-0026** (Propuesto): la puerta de axe cubre solo las historias de Storybook de `packages/ui` (:70-85), y `packages/ui/.storybook/main.ts:10` la limita a átomos, moléculas y dominio. La política vive en `packages/ui/src/a11y/policy.ts:8-60`: 60 líneas, pura, que solo depende del tipo de axe-core. **No es exportable**: `ui` exporta solo `.`, `./i18n` y `./styles.css` (`packages/ui/package.json:6-10`), y la spec `design-system` (:22-32) exige un único punto de entrada con exactamente 19 nombres.
- **`packages/ui` todavía no tiene** `FormErrorSummary`, `Alert`, `Banner`, `Toast`, `Dialog`, `Card`, `Sidebar`, `Topbar`, `PageHeader`, `Breadcrumb`, `DropdownMenu`, `SearchInput` ni `layouts/` (`packages/ui/src/index.ts:1-17`; inventario en `docs/ui-ux/02:95-173`). Según `docs/ui-ux/02:87-89`, un componente sube a `ui` solo cuando lo usan las dos apps o tres módulos.
- **Pendientes de 13a** (`archive-report.md:70-74,81`):
  - **W2:** `Button` anima `outline-color` por `transition-colors` (`Button.tsx:13`), así que el anillo de foco aparece con desvanecimiento. Solo hay una prueba en jsdom (`Button.test.tsx:69`).
  - **W3:** `ErrorState` acepta `retryLabel=""` (`ErrorState.tsx:33`), y `EmptyState` y `ErrorState` pintan un `<p>` vacío (:29, :25).
  - **S4:** con `colorBySign`, un negativo de `MoneyAmount` usa el color de «vencido» (`MoneyAmount.tsx:22`). No afecta a 13b, porque no hay importes, pero sigue siendo una pregunta de producto.

### Contratos

- orval genera solo Zod (`client: "zod"`, `orval.config.ts:8-18`). `generated/admin/index.ts:9-129` trae esquemas por operación, pero ninguna URL, método ni código de estado. El punto de entrada `admin.ts:5` reexporta solo `schemas.js`.
- `schemas.ts:9-169` ya contiene todo lo que la pantalla necesita: `LoginRequest`, `LoginResponse` (una unión por `status`), `AuthenticatedSessionResponse`, `SecondFactorPendingResponse`, `EnrollmentResponse`, `SecondFactorVerificationRequest`, `CurrentSessionResponse`, `ProblemDetail` (con `errors`), `Money` y las constantes `*Max`.
- **No hay que reexportar `index.ts` con `export *`:** repite nombres (`CompleteSecondFactorBody`, `LogInBody`, `*Min`, `*Max`) y chocaría.
- Los esquemas admiten cadenas vacías (mínimo 0) y no expresan «exactamente uno de `code` o `recoveryCode`», así que la app debe agregar `.min(1)` y la validación cruzada.
- El cliente tendrá que escribir a mano rutas y cabeceras, porque no hay generador de operaciones.

### API de acceso del personal

Operaciones en `apps/api/openapi/admin.openapi.json`: inscripción (:250), verificaciones (:341), `DELETE` de sesiones (:461), `POST` de sesiones (:543), sesión actual (:659) y refresco (:785).

- **`POST /api/v1/auth/sessions`, inicio de sesión:** JSON `{identifier, password}`, `Origin` permitido obligatorio y sin CSRF.
  - Responde `200 authenticated` (cuerpo con `accessToken` y dos cookies).
  - O responde `200 second-factor-required` o `second-factor-enrollment-required`, con un `secondFactorToken` restringido (300 s) y **sin cookies**.
  - Errores: `401 authentication-failed`, `400 validation-failed`, `415`, `429 too-many-requests` con `Retry-After` y `503 capacity-exceeded` (identity spec:2378-2396; web-edge:1601-1611).
- **`POST /api/v1/auth/mfa/enrollments`:** con Bearer restringido `mfa-enroll` y sin cuerpo.
  - Responde `201 {secret, recoveryCodes}`: un secreto base32 de 32 caracteres y diez códigos de recuperación, **una única vez**.
  - Se puede repetir hasta la primera verificación; reinscribir reemplaza el secreto y anula los códigos.
  - `409 mfa-already-enrolled` (spec:2746-2760).
  - **El backend ya soporta la inscripción dentro del flujo de inicio de sesión.**
- **`POST /api/v1/auth/mfa/verifications`:** con Bearer restringido y un cuerpo con exactamente uno de `code` o `recoveryCode`.
  - Con `mfa-enroll` solo acepta un `code` TOTP. Su éxito activa la credencial y revoca las demás sesiones (`mfa_change`).
  - Responde `200` con la sesión y dos cookies.
  - Errores: `401 second-factor-invalid` (sin decir por qué), `401 token-expired` y `token-invalid` (spec:2620-2635, 2705-2715).
  - Los fallos de TOTP alargan la respuesta hasta un tope; `docs/ui-ux/06:46-54` dice 30 s, así que el cliente debe tolerar esperas largas.
- **`POST /api/v1/auth/sessions/refresh`:** lee el refresco solo de la cookie `__Secure-confia_admin_sid` (HttpOnly, Secure, SameSite=Strict, `Path=/api/v1/auth/sessions`).
  - Exige `Origin` y, con cookie, `X-CSRF-Token` igual a la cookie `__Host-confia_csrf` (legible por JS, `Path=/`).
  - Responde `200` con un `accessToken` nuevo y reemite las cookies.
  - Cualquier rechazo es `401 session-expired`, borra las cookies **y cuenta como un fallo de la IP** en la política `admin-session`: 30 rechazos en 10 minutos activan una restricción, con 120 por minuto (`docs/03:570`).
  - Hay 10 s de gracia entre pestañas; una reutilización fuera de esa gracia revoca la familia (spec:2813-2828, 2838-2843, 2845-2852).
- **`DELETE /api/v1/auth/sessions/current`, cierre de sesión:** cookie, CSRF y `Origin`, sin Bearer. Responde siempre `204`. La SPA no debe enviar un Bearer vencido (`archive-report` de session-endpoints:119).
- **`GET /api/v1/auth/sessions/current`:** con Bearer.
  - Devuelve `sessionId`, `accountId`, `institutionId`, `amr`, `accessTokenExpiresAt`, `sessionExpiresAt` e `idleExpiresAt`, sin datos personales (spec:2183-2197).
  - **No mueve el reloj de inactividad.** Ese reloj (30 minutos) solo avanza al rotar el refresco, así que la SPA refresca solo ante actividad del usuario, nunca con un temporizador.
  - El aviso de inactividad sale a los 25 minutos, calculado con `idleExpiresAt` (`docs/ui-ux/04:612,619-627`).
- **Vigencias** (`docs/03:515,542-544`; spec:1955-1966):
  - token de acceso, 600 s;
  - token restringido, 300 s;
  - refresco, 8 h;
  - familia de sesión, 12 h absolutas;
  - inactividad, 30 minutos.
- **Límites** (`docs/03:555`):
  - `admin-login`, 10 por minuto por IP;
  - `admin-mfa`, 20 por minuto por IP;
  - 10 `second-factor-invalid` en 10 minutos activan una restricción.
- **Códigos Problem que la UI debe traducir a claves propias.** Nunca se muestran `title` ni `detail` (`docs/ui-ux/04:709-711`):
  - `authentication-failed`, `second-factor-invalid`, `session-expired`, `csrf-invalid` y `mfa-already-enrolled` (04:769-779);
  - `validation-failed`, con `errors[]` por campo;
  - `unsupported-media-type`, `too-many-requests` (con `Retry-After`), `capacity-exceeded` e `internal-error`;
  - `authentication-required`, `token-invalid`, `token-expired`, `forbidden` y `resource-not-found`.

  El `type` es la URL `https://confia.hn/problems/<código>`, y `WWW-Authenticate` no es legible desde el flujo. **`mfa-attempts-exceeded` y el bloqueo de cuenta no existen** (`docs/ui-ux/06:46-54`).
- **Topología:**
  - En producción, el panel y la API comparten origen: nginx manda `/api/v1/` a api-admin y `/` a admin-web (`docs/05:1403-1411`; `docs/03:1246-1252`, «`connect-src 'self'` basta»).
  - **El backend no tiene CORS.** El único código de orígenes es la lista del filtro CSRF (`AllowedOrigins.java`), y `application-local.yml:16` ya permite `http://localhost:5173`.
  - Las cookies son siempre `Secure` y con prefijo, en todos los perfiles. En local exigen un navegador que trate `http://localhost` como confiable (web-edge:1827-1830).
- **Cambio 8 (RBAC, en curso):**
  - En `main` hoy solo está el catálogo de roles en código (`StaffRole.java`).
  - El cambio agrega `GET /api/v1/identity/roles`, que es el informe de la matriz, no «mis permisos», y exige MFA a toda cuenta del personal.
  - Deja para 8b la inscripción y baja voluntaria de MFA y la creación del primer Super Admin.
- **Endpoints que no existen:**
  - **ninguno de perfil** (nombre, rol) ni de «mis permisos»;
  - **ninguno HTTP para recuperar la contraseña**;
  - la vista de sesiones activas corresponde al primer cambio de `apps/admin-web` que la muestre.
- **No existe una semilla de cuentas del personal.** Una cuenta se guarda con Argon2id, un *pepper* de entorno y una huella HMAC del identificador (`LoginEndpointSupport.java:35-77`). No se puede sembrar desde Node ni desde SQL sin ese *pepper*: sería trabajo de `apps/api` (perfil local), que 13a dejó asignado a 13b junto con CORS (propuesta de 13a:96-98).
- **La construcción del URI `otpauth://` y del QR** está asignada al cambio que construya la pantalla de inscripción (identity spec:599-601), porque necesita el emisor y la etiqueta de la institución, y la sesión solo devuelve `institutionId`.
- **El requisito vigente «sin prueba e2e de inicio de sesión»** (identity spec:540-553) deja de ser cierto cuando se entregue la pantalla con su prueba. `CLAUDE.md` exige una e2e de inicio de sesión con MFA; `docs/06:443-452` la lista como recorrido crítico, y ADR-0006 pide una suite de Playwright de rutas críticas en cada PR.
- **Conflicto de voz:** `docs/ui-ux/04` usa «usted» (:612, :727) y `docs/ui-ux/05` usa tuteo (:107-123).

## 2. Áreas afectadas

- **Nuevo** `apps/admin-web`: paquete, Vite, enrutador, `features/identity`, shell, catálogo i18n `admin`, pruebas y Playwright.
- `packages/config/eslint.js` (y su `.d.ts`) y pruebas en `tooling/`: reglas de app, con `fetch` permitido solo en `shared/api`.
- `.dependency-cruiser.cjs`, `tooling/dependency-fixtures` y `tooling/src/dependency-rules.test.ts`: reglas de app con violación deliberada (ADR-0018).
- `tooling/src/suppression-citations.test.ts`: extender el escaneo a `apps/`.
- `packages/ui`:
  - `FormErrorSummary`, `Alert`, `Dialog` y, según la decisión, `Sidebar`, `Topbar` y `PageHeader`;
  - los arreglos W2 y W3;
  - la spec `design-system`, por el conjunto exacto de exportaciones.
- **Configuración y CI:**
  - `packages/config`, con un posible `a11y` que tome la política extraída de `ui`;
  - `turbo.json` (tareas) y `pnpm-workspace.yaml` (catálogo);
  - `ci.yml` (un job aditivo) y `ci-workflow.test.ts`.
- **Documentación y specs:**
  - un ADR nuevo (axe sobre rutas);
  - notas fechadas en `docs/09` (criterio de salida 8) y en `docs/ui-ux/04` y `06`;
  - la spec `identity` (escenario de ausencia de e2e);
  - deltas de `build-integrity` y `design-system`.
- `apps/api` **solo si el propietario lo decide** (semilla local, endpoint de perfil, CORS), porque queda fuera de la naturaleza de frontend del cambio.

## 3. Decisiones técnicas

### T1. Andamiaje y enrutado

- **A. Rutas por código** (`createRoute`), un módulo de rutas por feature.
  - A favor: no hay generador, no aparecen cabeceras `@ts-nocheck` ni `eslint-disable` que el escáner de supresiones rechazaría (`suppression-scanner.ts:9`), y no hace falta generar antes de comprobar tipos. El tipado es el mismo, y hoy son unas 6 rutas.
  - En contra: a más de 60 rutas (el árbol de `docs/ui-ux/04:75-156`), el ensamblado manual crece. Migrar a archivos después es mecánico.
- **B. Rutas por archivos** (`@tanstack/router-plugin` y `routeTree.gen.ts`).
  - A favor: es lo que recomienda TanStack, y divide el código automáticamente.
  - En contra: el archivo generado lleva supresiones (exige un ADR o una exención del escáner), aparece una tarea `generate` de turbo previa a lint, typecheck y test, y crece la superficie de dependencias en un enrutador que ADR-0006 (:147-155, :425) ya declara menos maduro.
- **Recomendación: A.** Con Vite, React 19 y Tailwind (`@tailwindcss/vite`), `@confia/ui/styles.css` y `uiResources`, los proveedores en `app/` y `features/identity/{sign-in,second-factor,enrollment,session}`. Reevaluar B cuando haya más de 15 rutas.

### T2. Estado de autenticación, sin tokens en almacenamiento legible por JS

- **Recomendado:**
  - El `accessToken` y el token restringido viven **solo** en un almacén privado de módulo, en memoria: no en la caché de TanStack Query, ni en el almacenamiento del navegador, ni en la URL, ni en el estado de navegación.
  - Los metadatos de sesión (vencimientos, `sessionId`) van por Query y `useSyncExternalStore`.
  - El contexto del enrutador expone `auth`, y las guardas `beforeLoad` redirigen. El parámetro `redirect` se valida con Zod como ruta relativa del mismo origen.
- **Restauración al recargar:** solo se llama a `POST /refresh` si existe la cookie `__Host-confia_csrf`, que es legible y se escribe y borra junto con la de sesión (`docs/03:561-564`). Si no existe, no se llama: cada sondeo anónimo sería un `401` y contaría como fallo de la IP en `admin-session` (30 en 10 minutos). Detrás de la NAT de una oficina eso es peligroso (spec:2861-2867).
- **Refresco:**
  - Una sola petición en vuelo por pestaña, con una promesa compartida, y una sola entre pestañas, con Web Locks (`navigator.locks`).
  - Se refresca por adelantado, antes de una petición iniciada por el usuario, cuando faltan menos de 60 s. **Nunca con un temporizador ni con consultas de fondo** (`docs/ui-ux/04:619-627`).
  - Un refresco no se reintenta tras un error de red fuera de los 10 s de gracia, porque una reutilización revoca la familia.
  - Las mutaciones no se reintentan (04:749-750). Por eso el refresco va antes de la petición, no como reacción a un error.
- **Aviso de inactividad:** a los 25 minutos, con un `Dialog` modal y una región `assertive` (`docs/ui-ux/03:112`), calculado con `idleExpiresAt`. «Continuar» refresca. El riesgo es el desfase del reloj del cliente; se mitiga con la diferencia entre el `Date` de la respuesta y el reloj local.
- **Alternativas descartadas:**
  - la sesión en la caché de Query, porque los tokens quedarían en la caché y en las herramientas de desarrollo;
  - un Context puro, porque las guardas del enrutador no lo leen sin contexto;
  - el almacenamiento del navegador, que el cambio prohíbe.

### T3. Cliente de API y contratos

- **Recomendado:** un cliente fino escrito a mano en `shared/api`, el único lugar con `fetch`.
  - Lleva las rutas y cabeceras de las 7 operaciones y valida las respuestas con los Zod que ya exporta `@confia/contracts/admin`.
  - Usa `credentials: "same-origin"`, `X-CSRF-Token` leído de la cookie y `Authorization` solo donde corresponde. No lleva `Idempotency-Key`, que no aplica a la autenticación.
  - Un parser convierte `ProblemDetail` en una unión tipada de códigos conocidos más uno desconocido.
- **Alternativa:** orval con `client: "react-query"` o `fetch` y un mutador.
  - A favor: genera los hooks.
  - En contra: mucho código generado; el mutador con refresco, CSRF y token restringido igual se escribe a mano; y obliga a cambiar `orval.config.ts` y a agregar una tarea `generate`.
  - Se reevalúa con el primer endpoint de negocio.

### T4. Formularios y estados de las pantallas

- **Formularios:** TanStack Form con Zod, más `.min(1)` y la validación cruzada.
  - El botón de envío siempre está habilitado. Mientras la petición está en vuelo, lleva `aria-busy` y un `Spinner` dentro (`docs/ui-ux/03:145-159`).
  - Un resumen de errores con foco y enlaces; `FormErrorSummary` no existe y se crea en `ui`.
  - `autocomplete` con `username`, `current-password` y `one-time-code`. Se permite pegar (criterio 3.3.8), y ningún `maxLength` es menor que el del contrato.
- **Pantallas y estados.** Cada una tiene carga, error y éxito, y vacío donde aplique:
  1. **Arranque:** restaurando la sesión (un `Skeleton` del shell o una pantalla neutral) o un error de red recuperable (`ErrorState` con reintento, que requiere el arreglo W3).
  2. **Inicio de sesión:**
     - credenciales inválidas, con el mensaje único de `docs/ui-ux/05:121`;
     - `429`, con una cuenta regresiva de `Retry-After` anunciada con cortesía (`polite`), sin anunciar cada segundo;
     - `503 capacity-exceeded`;
     - `400` por campo;
     - una espera larga, de hasta unos 30 s, sin un tiempo límite menor;
     - la sesión vencida, con el mensaje de 04:727.
  3. **Verificación del segundo factor:**
     - código TOTP de 6 dígitos, con `inputmode=numeric` y tolerancia a espacios;
     - la alternativa del código de recuperación, solo con `mfa-verify`;
     - `second-factor-invalid`;
     - si vence el token restringido (300 s), se vuelve al inicio de sesión con un mensaje propio.
  4. **Inscripción obligatoria:**
     - la clave en grupos de 4, con un botón para copiarla, y el QR según la decisión;
     - los 10 códigos de recuperación, mostrados una sola vez, con una confirmación explícita antes de seguir;
     - el campo del primer código, solo TOTP;
     - `mfa-already-enrolled` (409), que vuelve al inicio de sesión.

     Recargar pierde el token restringido: se vuelve al inicio de sesión, y la reinscripción está permitida.
  5. **`authenticated` sin segundo factor:** se maneja y se prueba, aunque tras el cambio 8 no ocurra.
- **El token restringido no va en la URL ni en el estado del historial.** Una guarda de ruta redirige al inicio de sesión si no hay token.

### T5. Shell

- **Estructura** de `docs/ui-ux/04:19-34`:
  - una barra superior fija de 56 px;
  - una barra lateral persistente de 260 px, o de 64 px plegada, con el estado guardado en `localStorage` (`confia.admin.sidebar.collapsed`, 04:40);
  - migas, `PageHeader` y el contenido;
  - un enlace para saltar al contenido (criterio 2.4.1) y las regiones activas;
  - un menú de usuario con «Cerrar sesión».
- **Lo que queda fuera, porque sus datos todavía no existen:**
  - el buscador global y los atajos;
  - el selector de año lectivo;
  - el indicador de caja y la campana;
  - el nombre y el rol;
  - «Mis sesiones activas» y «Autenticación en dos pasos».
- **Navegación:** una configuración tipada con un `permission` opcional y **solo la ruta Inicio**, porque un enlace muerto es un defecto. El filtro por rol llega con el endpoint de permisos.
- **Dónde viven `Topbar`, `Sidebar` y `PageHeader`:** en `ui`, que es donde `docs/ui-ux/02` los lista como organismos P0 del panel, o en la app hasta que haya un segundo consumidor (criterio de promoción de 02:87-89).
  - **Recomendación:** en la app (`features/shell`), como componentes presentacionales con sus pruebas, y promoverlos cuando llegue `portal-web`.
  - `Dialog`, `Alert` y `FormErrorSummary` sí van a `ui`, porque los usan las dos apps.

### T6. Puerta de axe sobre pantallas reales

ADR-0026 solo cubre historias de Storybook.

- **A. Playwright Test con `@axe-core/playwright` sobre rutas renderizadas,** en una matriz de ruta por estado por tema (claro y oscuro), esperando a que cargue el contenido (`docs/ui-ux/03:230-231`), con la política compartida.
  - A favor: es lo que documentan `docs/ui-ux/03:207-247` y `docs/06:729-799`, y un solo ejecutor sirve para axe y para los recorridos.
  - En contra: dependencias nuevas, un navegador en CI y una API simulada.
- **B. Un proyecto de Vitest en modo navegador,** que ya existe para `ui`, renderizando rutas con MSW.
  - A favor: reutiliza el montaje de ADR-0026.
  - En contra: no prueba el enrutador ni las cookies reales, se aparta de `docs/06` y extiende a las apps el mecanismo de `ui`.
- **C. Solo historias.**
  - En contra: no cierra el criterio de salida 8, porque `docs/09:231-237` exige «pantallas existentes».
- **Recomendación: A,** con un **ADR nuevo**, «axe sobre rutas renderizadas», sin reescribir ADR-0026, que está Propuesto.
  - La política (`policy.ts`) se extrae a `@confia/config/a11y` para tener una sola fuente; exportarla desde `ui` rompería la spec del punto de entrada único.
  - El criterio 8 de `docs/09` se marca solo cuando la puerta pase sobre el inicio de sesión, la verificación, la inscripción y el shell.

### T7. e2e y simulación de la API

- **Realidad de partida:**
  - CI no tiene API ni base de datos hasta el cambio 11.
  - Sembrar cuentas exige código de `apps/api`, por el *pepper*.
  - Los límites de tasa (10 por minuto por IP) y el retardo por fallos afectan a una e2e real en paralelo.
- **E1. e2e contra la pila real en 13b,** con una semilla local en `apps/api`, un compose manual y el job de CI diferido al cambio 11.
  - A favor: cumple `CLAUDE.md` al pie de la letra.
  - En contra: mucho alcance de backend, y no corre en CI hasta el cambio 11.
- **E2. Recorridos de navegador contra una API simulada en 13b,** que corren en CI y son deterministas, más el escenario de la e2e real escrito como requisito diferido y asignado al cambio 11.
  - A favor: cabe en F0 sin tocar `apps/api`.
  - En contra: la e2e real de inicio de sesión con MFA sigue pendiente, y la simulación no prueba cookies reales.
- **Recomendación: E2,** y que el propietario acepte la brecha con fecha, como en identity spec:540.
  - **Simulación:** MSW en el navegador solo en modo `mock`, con el service worker fuera del build de producción, o `page.route`.
  - **Riesgo que hay que sondear:** el navegador no deja que una respuesta sintética fije un `Set-Cookie` real, como documenta MSW. Por eso la cookie CSRF se simula o se fija con `context.addCookies`.
  - **Pruebas de componente y contenedor:** MSW en Node (Vitest, jsdom).
  - **Cálculo de TOTP en las pruebas:** con `node:crypto` (RFC 6238, unas 20 líneas), para no agregar una dependencia.
- **Ubicación:** `apps/admin-web/e2e` por ahora. El directorio `e2e/` de `docs/06` se crea en el cambio 11 si hace falta una suite común.

### T8. Topología de desarrollo y CORS

- **Recomendado:** un proxy de Vite de `/api` hacia el backend local, para que el navegador y la API compartan origen como en producción (`docs/05:1403-1411`).
  - El origen `http://localhost:5173` ya está permitido (`application-local.yml:16`). No se agrega CORS al backend.
  - **Alternativa:** CORS en `apps/api`. Es un cambio de seguridad, porque implica credenciales, una lista blanca y la cuarta dimensión de ADR-0003, e innecesario con el mismo origen.
- **CSP estricta:** el build debe ser compatible con `script-src` sin código en línea y con `style-src 'self'` (`docs/03:1225-1243`). Eso significa sin scripts en línea, sin `eval` y con el service worker de MSW fuera del build.
- **Pendiente de sondear:**
  - las cookies `Secure` y `__Host-` sobre `http://localhost` en Chromium, documentado como soportado en web-edge:1827-1830;
  - la receta para arrancar en local el proceso administrativo, que no tiene README.

### T9. Reglas estáticas de la app

- **Separar en `packages/config`** las reglas comunes de las solo de `ui`:
  - **comunes:** texto en JSX, formato, literales visuales y aritmética o conversión de importes;
  - **solo de `ui`:** red prohibida y capas.
- **Reglas para `admin-web`:**
  - `fetch`, `XMLHttpRequest`, `WebSocket` y `EventSource` prohibidos, salvo en `src/shared/api/**` y `e2e/**`;
  - una regla de dependency-cruiser por la que la presentación no importa `@tanstack/react-query` ni `shared/api` (ruta `**/components/**`);
  - un catálogo `admin` tipado (`AdminKey`), con una prueba de claves inexistentes como `keys.check.test.ts`.
- **Extender el escáner de supresiones a `apps/`.**
- **Cada regla con su violación deliberada,** según ADR-0018, en los fixtures de `tooling/dependency-fixtures`.

### T10. CI, de forma aditiva

- **`frontend verify`** absorbe el lint, el typecheck, las pruebas y el build de `admin-web` sin cambiar su comando (`ci-workflow.test.ts:47-56`). Hay que medir si cabe en 10 minutos.
- **Un job nuevo, `admin-web e2e and accessibility`:** Chromium, `vite preview` y API simulada, con sus acciones fijadas por SHA, la subida del informe y su prueba en `ci-workflow.test.ts`.
- **`turbo.json`:** tareas `test:e2e` (sin caché) y `dev`.

### T11. Cambios en `packages/ui` para 13b

- **Componentes nuevos:** `FormErrorSummary`, `Alert` y `Dialog`, este último también para el aviso de inactividad.
- **Arreglos:** W2, con una prueba de estilo calculado en Chromium, y W3.
- **Lo que acompaña a cada componente:**
  - sus claves es-HN;
  - historias en los dos temas;
  - la actualización de la spec, por el conjunto exacto de exportaciones.
- **Candidatos opcionales** si se mueven a `ui`: `PageHeader` y `Card`.

## 4. Decisiones del propietario, no tomadas aquí

- **P1. Voz de la interfaz del personal:** tuteo (`docs/ui-ux/05`) o «usted» (`docs/ui-ux/04`). Afecta a todo el catálogo es-HN.
- **P2. Segundo factor:** el emisor y la etiqueta del URI `otpauth://`, y si se muestra un QR (una dependencia nueva) o solo la clave manual. Por ejemplo, un emisor fijo «CONFIA» y como etiqueta el correo escrito en el inicio de sesión, porque no hay endpoint de la institución.
- **P3. Enlace «¿Olvidó su contraseña?»:** no existe el endpoint. Omitirlo, o mostrar un texto de contacto con la administración.
- **P4. Identidad en el shell:** sin endpoint de perfil no hay nombre, ni rol, ni filtro de la navegación por rol. Aceptar un menú de usuario solo con «Cerrar sesión» y la navegación sin filtro, o pedir un endpoint, probablemente en 8b.
- **P5. Contenido del panel vacío:** `EmptyState` exige una acción primaria, y no hay módulos a los que ir. Definir el mensaje y la acción, o permitir una variante informativa.
- **P6. Frontera de la e2e:** aceptar E2 (API simulada ahora y la real diferida al cambio 11) o exigir E1 (semilla local en `apps/api`). Incluye si 13b toca `apps/api` y si se retira CORS del alcance de 13b, que 13a listaba.
- **P7. Estructura:** un cambio con 9 a 11 PR encadenados, o dos cambios por el tope de 15 tareas de `openspec/config.yaml`:
  - 13b-1, `admin-web-scaffold-and-staff-login`, hasta cerrar el criterio 8;
  - 13b-2, el shell y la vida de la sesión.
- **P8. ADR nuevo:** aprobar un ADR para axe sobre rutas y la extracción de la política a `@confia/config`.
- **P9. Código de recuperación:** incluirlo como alternativa en la pantalla de verificación. Se recomienda que sí, porque el backend ya lo soporta.
- **P10. Selector visible de tema claro u oscuro en el shell, y «mostrar contraseña»:** incluirlos o diferirlos.
- **P11, no bloquea 13b. Color del importe negativo de `MoneyAmount`** (saldo a favor): decidirlo antes de la primera pantalla con importes.

## 5. Tamaño y rebanadas

Líneas escritas, con las pruebas incluidas y sin el lockfile ni el código generado. El tope es de 800 por PR.

Como referencia, las pruebas del cambio 8 superaron el pronóstico entre 2 y 4 veces. Se muestra un rango «realista» que aplica un factor de 1,5 a 2, porque aquí el código nuevo es sobre todo prueba de componente y contenedor.

| # | Rebanada | Pronóstico | Realista | PR |
|---|---|---|---|---|
| 1 | Andamiaje de `apps/admin-web`, proveedores, i18n `admin`, proxy, build y humo | 500-700 | 800-1 200 | 1-2 |
| 2 | Reglas de app: ESLint separado, dependency-cruiser, fixtures y escáner a `apps/` | 350-500 | 500-800 | 1 |
| 3 | Cliente de API, `ProblemDetail` y mapa de códigos, MSW de contrato | 500-700 | 800-1 300 | 1-2 |
| 4 | Ciclo de la sesión: almacén, refresco, Web Locks, arranque, cierre e inactividad | 500-700 | 800-1 300 | 1-2 |
| 5 | `ui`: `FormErrorSummary`, `Alert`, `Dialog`, arreglos W2 y W3, spec | 600-800 | 900-1 400 | 1-2 |
| 6 | Pantallas de inicio de sesión y verificación (TOTP y recuperación) | 600-800 | 900-1 400 | 1-2 |
| 7 | Inscripción (QR o clave, códigos de recuperación) | 400-600 | 600-1 000 | 1 |
| 8 | Shell, guardas, panel vacío y aviso de inactividad | 600-800 | 900-1 300 | 1-2 |
| 9 | Playwright y axe sobre rutas, recorrido e2e simulado, job de CI, ADR, docs y spec | 600-800 | 900-1 400 | 1-2 |

Total: entre 4 150 y 5 900 líneas de pronóstico, y entre 6 200 y 10 100 en el rango realista, es decir, de 9 a 15 PR. Una sola cadena de unas 13 tareas roza el tope de 15; por eso existe la decisión P7.

## 6. Recomendación

- **Estructura:** un único cambio, solo si el propietario acepta de 9 a 11 PR. Si no, dividirlo en dos:
  - **13b-1:** las rebanadas 1 a 7, y la 9 reducida al inicio de sesión. Cierra el criterio de salida 8.
  - **13b-2:** la rebanada 4 en parte y la 8.
- **Orden:** la rebanada 2 (reglas) antes que el código de la app, para que la app nazca bajo sus reglas. La 5 (`ui`) puede ir en paralelo a la 3 y la 4.
- **Enfoque técnico:**
  - rutas por código;
  - tokens solo en memoria;
  - un cliente propio;
  - Playwright con axe, bajo un ADR nuevo;
  - una e2e simulada, con la brecha fechada;
  - un proxy en lugar de CORS.

## 7. Riesgos

- **Cookies `Secure` y `__Host-` sobre `http://localhost`, y la simulación de `Set-Cookie`:** necesitan una sonda antes del diseño, como tarea 0.
- **Sondeo de refresco sin sesión:** cuenta como fallo de la IP (30 en 10 minutos en `admin-session`). La señal para no sondear es la ausencia de la cookie CSRF.
- **Refrescos concurrentes o errores de red:** pueden disparar `reuse_detected` y revocar la familia. Se mitiga con Web Locks y sin reintentos a ciegas.
- **Retardos de hasta unos 30 s por fallos, y `503 capacity-exceeded`:** afectan a los tiempos de espera de Playwright y a la experiencia de espera.
- **Tasa del backend en una e2e real compartida** (10 por minuto por IP): afecta a la opción E1.
- **Arreglos en `ui`:** cambian la spec `design-system` (exactamente 19 exportaciones), y cada componente nuevo exige historia, prueba y claves.
- **Presupuesto de CI:** `frontend verify` tiene 10 minutos, con `admin-web` y `ui` juntos, y el job nuevo usa Chromium sin caché.
- **CSP de producción:** el *nonce* para una SPA estática no está resuelto (`docs/03:1216-1217` frente a `docs/05:1400`). Queda para el cambio 11; 13b solo garantiza la compatibilidad.
- **Dependencias nuevas:** TanStack, MSW, Playwright Test, axe para Playwright y QR pasan por la puerta de `pnpm audit` y Trivy, y por la regla de dos semanas.
- **No verificado en esta exploración:**
  - el estado git de la rama;
  - las versiones publicadas de las dependencias nuevas;
  - la receta de arranque local del backend;
  - la rama de RBAC, que solo se leyó.

## 8. Listo para la propuesta

Sí, condicionado a las decisiones P1 a P8. P6 y P7 definen el alcance.
