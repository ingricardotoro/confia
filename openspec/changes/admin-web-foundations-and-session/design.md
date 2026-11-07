# Diseño: andamiaje de `apps/admin-web` e inicio de sesión del personal (cambio 13b-1 de F0)

- **Cambio:** `admin-web-scaffold-and-staff-login` (F0, cambio 13b-1; criterio de salida 8)
- **Fase:** diseñar
- **Fecha:** 2026-11-04
- **Estado:** pendiente de aprobación del propietario. P1, P2, P3, P6 a P10 y Q2 están decididas y no se reabren. Lo que este
  documento pide al propietario está en la sección 16.
- **Entradas:** `proposal.md` (aprobada el 2026-11-04), `exploration.md`, deltas de `specs/` (AW01-AW63, SS01-SS127, DS75-DS97,
  BX38-BX108, I304-I311), decisiones del propietario (Engram, observación 784), `docs/ui-ux/`, ADR-0006, ADR-0026,
  `packages/config/eslint.js`, `.dependency-cruiser.cjs`, `packages/ui/src/a11y/policy.ts`, `tooling/`, `.github/workflows/ci.yml`.
- **Modo de TDD:** estricto. El `test_command` de `openspec/config.yaml` es solo del backend; la sección 10 fija el comando por PR.
- **Dinero:** este cambio no muestra importes. Reglas aplicables de `CLAUDE.md`: 1 y 3 (estáticas), 9, 10, 11, 13 y 15.
- **Limitación de esta fase:** sin consola ni red. Ninguna versión de dependencia nueva se pudo consultar en un registro y ningún
  tiempo se midió; la sección 5, D19, y BX89 dicen qué queda para aplicar.

## 1. Enfoque técnico

La aplicación nace bajo sus reglas, y su sesión se construye con cinco invariantes que cada PR prueba en rojo antes de escribir
código:

1. **Ningún contenedor toca un token.** Los tokens viven en un cierre de `features/identity/session`; los contenedores llaman a
   operaciones que devuelven desenlaces sin secretos. Un token no puede llegar a la caché de Query, a la URL ni a un componente
   porque ningún tipo público lo transporta.
2. **La cookie CSRF es la única señal de sesión.** Sin ella no sale ningún refresco, ni al arrancar ni antes de una petición,
   porque cada rechazo cuenta como fallo de la IP en `admin-session`.
3. **Todo refresco y todo cierre ocurren dentro de un único bloqueo por navegador**, y el valor CSRF se lee **dentro** del
   bloqueo: el de antes de esperar puede estar ya rotado por otra pestaña.
4. **Las escrituras de acceso no pasan por Query.** `useMutation` guarda variables y datos en su caché, y ahí estarían la
   contraseña y los tokens (SS03).
5. **La puerta y los recorridos usan el mismo servidor simulado, detrás del mismo proxy, con el build real y la CSP real.** El
   simulador valida cada respuesta contra el contrato y nunca se presenta como prueba de la pila real.

```
 main.tsx ─► app/ (proveedores, i18n, Query, enrutador)
               │
               ▼
          BootGate ── restaurando / error ──► BootScreen (sin enrutador)
               │ resuelto
               ▼
          RouterProvider ── beforeLoad ──► guardas (leen AccessSession)
               │
   ┌───────────┼─────────────┬──────────────┐
   ▼           ▼             ▼              ▼
 SignIn     SecondFactor  Enrollment     SignedIn        contenedores
   │           │             │              │ useQuery
   ▼           ▼             ▼              ▼
 features/identity/session (AccessSession) ── token-store, refresh-coordinator
   │
   ▼
 shared/api (auth-operations ─► http: único fetch; problem; csrf-cookie)
   │  ruta relativa, mismo origen
   ▼
 proxy de Vite ──► backend local (dev manual) | simulador Node (recorridos)
```

## 2. Resultado de la sonda del paso 0 (SS122)

**Medido el 2026-11-04**, fuera del repositorio (`confia-scratch\probe-13b`), con vite 8.3.4, playwright 1.64.1, msw 3.0.3 (fuera
del catálogo del repositorio) y Chromium 156 sin interfaz.

| Observación sobre `http://localhost`, por el proxy de Vite, en `dev` y `preview` | Resultado |
|---|---|
| `__Secure-confia_admin_sid` y `__Host-confia_csrf` se guardan con todos sus atributos | Sí |
| La de sesión viaja solo a `/api/v1/auth/sessions` y por debajo, no a `/sessionsX` ni a otras rutas | Sí |
| JS lee solo la CSRF y no puede sobrescribir la de sesión | Sí |
| Las dos sobreviven a una recarga; `Max-Age=0` borra las dos | Sí |
| Una cookie con prefijo inválida se rechaza | Sí |
| Una petición entre sitios no lleva cookies | Sí. `Strict` **inferido**: no hubo control con `Lax` ni `None` |
| El proxy conserva `Origin`; `changeOrigin: true` reescribe solo `Host` | Sí |

**Consecuencia:** sin HTTPS local y sin indicador que desactive `Secure` (AW11).

| Mecanismo de simulación | Resultado | Decisión |
|---|---|---|
| **Servidor Node detrás del proxy de Vite** | Único que guarda las dos cookies desde una respuesta real que cruza el proxy, comprueba `Origin` y `Host` reales, no agrega service worker y sirve en `dev`, `preview` y desarrollo manual | **Elegido** |
| `page.route` con `fulfill` | Iguala los atributos de cookie (varias cookies unidas con `\n` en un solo `set-cookie`), pero se salta el proxy | Solo para fallos de red por prueba (D15) |
| `context.addCookies` | Solo siembra estado | Solo para fijar estados iniciales |
| MSW en el navegador | Solo reenvía a `document.cookie` las cookies legibles: la de sesión nunca se guardó. `msw init public` hace que `vite build` copie el worker a `dist` si no se desactiva `publicDir` | **Rechazado para el inicio de sesión** |
| MSW en Node | No aplica a cookies reales | Se conserva para pruebas de componente y contenedor |

**Corrección a la exploración (T7).** Decía que el navegador no deja a una respuesta sintética fijar un `Set-Cookie` real y que por
eso la cookie CSRF se simularía o se fijaría con `addCookies`. Era parcialmente falso: MSW sí refleja las cookies legibles en
`document.cookie`; la que nunca se guarda es la `HttpOnly`. Y `page.route` con `fulfill` sí fija cookies reales.

**Atributos ejercitados** con el mecanismo elegido: `Secure`, `HttpOnly`, prefijos `__Secure-` y `__Host-`, `Path`, `SameSite=Strict`
(inferido), `Max-Age=0`, persistencia al recargar, `Origin` y `Host`.

**No ejercitados:** el backend real (ni el `401` frente al rechazo de `Origin`, ni una receta de arranque), HTTPS local, Firefox y
WebKit, y `page.route`, MSW y `addCookies` bajo `preview`. El paso 4 de la sonda no se hizo porque el arranque no resultó barato: la
receta de arranque local sigue sin existir. Nada de esto prueba la pila real, que es del cambio 11.

## 3. Evidencia que condiciona el diseño

| Hallazgo | Fuente | Consecuencia |
|---|---|---|
| `noPlaywrightTestExercisesTheAuthenticationFlowYet` recorre todo `apps/` y falla ante un `*.spec.ts` o `*.spec.js` | `IdentityScopeExclusionInventoryTest.java:162-183` | D18: un `*.spec.ts` en `apps/admin-web` rompe `backend verify` |
| `docs/06` ya nombra los recorridos `e2e/specs/**/*.e2e.ts` | `docs/06:71`, `:736`, `:764` | D18: se sigue `docs/06` y `apps/api` no se toca |
| `pnpm-workspace.yaml` **no tiene** `minimumReleaseAge`; la «regla de dos semanas» es solo un comentario en `:77` | Búsqueda en el árbol | D19; BX93 parte de una premisa falsa (sección 13) |
| `instance` es la ruta de la petición, relativa, y el esquema generado usa `zod.url()` | `ProblemResponses.java:21`, `:106`; `schemas.ts:103`; `ContractSchemas.java:81` | D7: `ProblemDetail.safeParse` rechazaría una respuesta real |
| `proxy_read_timeout 30s` en el snippet de nginx, igual al retardo tope de la respuesta | `docs/05:1477`; `docs/ui-ux/06:46-54` | Riesgo R3: en producción, un fallo lento puede volverse `504`. Del cambio 11 |
| El backend ya recorta, aplica NFKC y pasa a minúsculas el identificador | `LoginIdentifier.java:57-62` | D12 |
| Un código de recuperación son 10 caracteres de un alfabeto sin `I L O 0 1`; otra longitud se rechaza sin retardo; se compara por hash exacto | `PlainRecoveryCode.java:33-35`, `:59-67`; `CompleteSecondFactor.java:205-210` | D12 |
| `FormField` genera el id de su control y no lo expone, y `FormErrorSummary` lo necesita | `FormField.tsx:57-58` | D13: propiedad nueva `controlId` |
| Ninguna regla impide a una app importar una ruta interna de `packages/ui`, y AW04 lo exige | `.dependency-cruiser.cjs:10-77` | D3: regla nueva |
| `ci-workflow.test.ts:70` compara la lista exacta de cinco trabajos | `ci-workflow.test.ts:69-74` | D16: esa línea cambia. Está fuera del bloque `[BX19]` |
| `allowed-origins` solo admite `http://localhost:5173`, y el puerto por defecto de `preview` es 4173 | `application-local.yml:16` | D4: `preview` usa 5173 |
| ADR-0006 confina TanStack Form a la capa de formularios de `packages/ui` | `ADR-0006:169` | D10: desviación; DS02 no admite otra exportación |
| El reloj de inactividad solo avanza al rotar | `docs/ui-ux/04:619-627` | D8, D9: sin temporizadores |
| `runnerImport` existe en Vite 8.3.4 | `vite/dist/node/index.d.ts:3926` | D15: el simulador carga TypeScript sin dependencia nueva |
| Tailwind `transition-colors` incluye `outline-color` | `Button.tsx:13` | D13, W2. `IconButton` compone `Button`, así que hereda el arreglo |
| `common.retry` ya existe y dice «Reintentar» | `ui.json:14` | D13, W3 |
| La restricción por IP es de un intento por minuto, con tope de una hora | `docs/03:478-481` | Valores de `Retry-After` |
| Siguiente ADR libre: 0028 | `docs/adr/README.md:76` | D14 |

## 4. Valores fijados

| Valor | Decisión | Razón | Ref. |
|---|---|---|---|
| Variable del proxy | `CONFIA_API_PROXY_TARGET`, por defecto `http://localhost:8080` | Sin `VITE_`, nunca entra al bundle; 8080 porque ningún `application*.yml` fija `server.port` | D4 |
| Validación del destino | Solo `localhost` o `127.0.0.1`; otro valor aborta | Un error de variable no debe enviar credenciales fuera de la máquina | D4 |
| Puerto de `dev` y `preview` | `5173`, `strictPort`, anfitrión `localhost` | Es el único `Origin` permitido por el backend local | D4 |
| `changeOrigin` | `false` | El nginx de producción conserva `Host` (`docs/05:1470`); AW07 lo confirma | D4 |
| Rutas | `/iniciar-sesion`, `/iniciar-sesion/verificacion`, `/iniciar-sesion/inscripcion`, `/` y `notFoundComponent` | `/` es el panel en `docs/ui-ux/04:77` y 13b-2 reemplaza allí la página mínima | D5 |
| Módulos de `shared/api` | `http.ts`, `csrf-cookie.ts`, `problem.ts`, `problem-keys.ts`, `report-unknown-problem.ts`, `auth-operations.ts` | Un archivo por razón de cambio; solo `http.ts` tiene `fetch` | D6 |
| Tiempo límite del cliente | 60 s, con `setTimeout` y `AbortController` | El doble del tope de 30 s; temporizador simulable para probar AW44 sin esperar | D6 |
| `Retry-After` por defecto | 60 s | Es la ventana de `admin-login` y `admin-mfa` y el ritmo de la restricción: nunca invita a fallar antes | D7 |
| Tope de presentación | 3 600 s | Tope documentado de la restricción; un valor absurdo no bloquea la pantalla | D7 |
| Formas de tuteo rechazadas | Lista de D17 | Inequívocas en cualquier lugar; ambiguas solo al inicio de frase | D17 |
| Destino de un Problem desconocido | `console.warn` por una función inyectable, con estado y `type` sin consulta ni fragmento, a lo sumo 200 caracteres | No hay telemetría de navegador hasta el cambio 11; `warn` no rompe AW02 | D7 |
| CSP de los recorridos | Bloque de D4, en `preview.headers` | La de `docs/03` menos lo que depende de producción | D4 |
| Retardos de reintento de lecturas | 1 000 ms y 2 000 ms (`1000 * 2 ** intento`) | Bastan dos intentos y mantienen corta la espera | D9 |
| `networkMode` de Query | `always` | Con `online`, una consulta sin red queda en pausa y SS105 nunca vería el error | D9 |
| Patrón de carpeta de presentación | `src/**/components/`, contenedores un nivel arriba | Es el de `docs/ui-ux/02:83` | D1 |
| Nombre de Web Locks | `confia.admin.session.refresh`, exclusivo | Identifica app y función; no es secreto | D8 |
| Identificador | Se recortan solo los extremos antes de enviar y guardar; ni NFKC ni minúsculas | El valor enviado es la etiqueta; un espacio pegado no ensucia la app del teléfono; `.min(1)` rechaza solo espacios y evita un fallo de IP | D12 |
| URI `otpauth` | `otpauth://totp/CONFIA:<encodeURIComponent(id)>?secret=<secreto>&issuer=CONFIA` | Google recomienda prefijo y parámetro juntos; sin `algorithm`, `digits` ni `period` porque son los valores por defecto y un URI más corto da un QR más legible | D11 |
| Inscripción | Secciones de una sola ruta, solo hacia adelante, sin entradas de historial | Una página por paso abriría rutas con secretos y regresos que SS93 prohíbe | D11 |
| Código de recuperación | Sin espacios, en mayúsculas, de 1 a 64 caracteres; sin comprobar alfabeto | El formato no está en el contrato; lo juzga el backend sin revelar por qué | D12 |
| `Alert` | Molécula, `molecules/alert/`; variantes `info`, `warning`, `destructive` | `docs/ui-ux/02:113-133` la lista como molécula; ninguna pantalla pide `success` | D13 |
| `FormErrorSummary` | Molécula, `molecules/form-error-summary/` | `docs/ui-ux/02:119` | D13 |
| Foco de ambos | `tabIndex={-1}` fijo y `ref` por propiedad (React 19) | No agrega parada de tabulación ni API nueva | D13 |
| Etiqueta de reintento de `ErrorState` | `common.retry`, «Reintentar», ya en catálogo | Es impersonal y no hay clave nueva | D13 |
| Forma de ESLint | `appSourceRules()` y `confiaEslint({ app: true })`; `uiSourceRules` conserva firma y mensajes | La opción no rompe a quien ya la usa y BX48 sigue igual | D2 |
| `sendBeacon`, `Worker`, `SharedWorker` | **Se prohíben** junto con las cuatro interfaces | Abren red sin pasar por el cliente; nada los usa y cuesta un selector | D2 |
| *Fixtures* | ESLint: código en línea en `tooling/src/eslint-app-rules.test.ts`. Dependencias: `tooling/dependency-fixtures/apps/admin-web/src/`. Claves: `apps/admin-web/src/i18n/__fixtures__/*.fixture`. Supresiones: `tooling/src/__fixtures__/suppressions/`. Puerta: `apps/admin-web/e2e/fixtures/a11y/*.html` | El patrón que cada guarda ya usa | D2, D3, D15 |
| Informes | `apps/admin-web/reports/a11y/report.json` y `reports/e2e/`; artefacto `admin-web-e2e-report` | Mismo nombre y ruta relativa que `@confia/ui` | D16 |
| Límite del trabajo de CI | 20 minutos | Chromium sin caché, build y una espera real de 31 s; se ajusta con la medida de 11b | D16 |
| ADR | ADR-0028, «axe sobre rutas renderizadas», Propuesto | 0027 es del cambio 8 | D14 |
| Biblioteca de QR | `qrcode.react`, `QRCodeSVG`; versión en aplicar | D11 y D19 | D11 |
| Decodificador de prueba | `jsqr`; versión en aplicar | Sin dependencias, recibe píxeles y no necesita `pngjs` | D11 |

## 5. Decisiones de arquitectura

### D1. Paquetes y dirección de las dependencias

**Elección.** Nombres de capacidad de negocio y contenedor sobre presentación:

```
apps/admin-web/
├── index.html, package.json, tsconfig.json, eslint.config.js
├── vite.config.ts, vitest.config.ts, playwright.config.ts
├── src/
│   ├── main.tsx
│   ├── app/                     App, BootGate, router, query-client, components/NotFound
│   ├── i18n/                    create-admin-i18n, resources, use-admin-text, locales/es-HN/admin.json
│   ├── features/identity/
│   │   ├── routes.tsx
│   │   ├── session/             access-session, token-store, refresh-coordinator, current-session-query
│   │   ├── boot/                components/BootScreen
│   │   ├── sign-in/             SignInContainer, sign-in-schema, components/{SignInForm,PasswordField}
│   │   ├── second-factor/       SecondFactorContainer, second-factor-schema, components/SecondFactorForm
│   │   ├── enrollment/          EnrollmentContainer, enrollment-store, otpauth-uri,
│   │   │                        components/{SecretStep,RecoveryCodesStep,FirstCodeStep,TotpQrCode}
│   │   ├── signed-in/           SignedInContainer, components/SignedInPage
│   │   └── components/          ScreenLayout, ProblemAlert, CopyButton
│   ├── shared/
│   │   ├── api/                 módulos de la sección 4
│   │   └── forms/               use-access-form, use-retry-countdown
│   └── test/                    contract-responses, msw-server, render, text-keys
└── e2e/
    ├── mock-api/                start.mjs, server.ts, scenarios.ts, session-model.ts
    ├── fixtures/a11y/           páginas deliberadamente inaccesibles
    ├── support/                 totp.ts, sentinels.ts, fixtures.ts, a11y-gate.ts, a11y-matrix.ts
    └── specs/{access,a11y,build}/*.e2e.ts
```

```
 app ─► features/identity ─► shared/api ─► @confia/contracts/admin
  │              │            ▲
  │              └─ components (solo propiedades; @confia/ui)
  └─ shared/forms ─ i18n
 token-store ◄── solo features/identity/session/
```

Sin alias de ruta: importaciones relativas, igual que `packages/ui`. **Alternativa descartada:** `@/`, que exige configuración en
Vite, TypeScript y dependency-cruiser, cuyo `tsConfig` admite un solo archivo. **Sin historias de Storybook en la app:** el catálogo
es de `packages/ui` (ADR-0026) y `docs/ui-ux/02:87-89` deja esto en la app hasta que suba.

### D2. ESLint: separar reglas comunes de las de `ui`

**Elección.** Se conservan las constantes y `uiSourceRules` sin cambiar selectores ni mensajes (BX48). Se agregan:

```js
const appJsxText = jsxTextControls("useAdminText().t"); // misma forma, otra pista en el mensaje
const APP_NO_NETWORK = [...];        // globals: fetch, XMLHttpRequest, WebSocket, EventSource, Worker, SharedWorker
const APP_NO_NETWORK_PROPS = [...];  // window|globalThis|self . (las mismas), navigator.sendBeacon
const APP_MONEY = {...};             // solo sobre `amount`; ver abajo
const NO_COOKIE_WRITE = {...};       // AssignmentExpression[left.object.name='document'][left.property.name='cookie']

export function appSourceRules({ portal = false } = {}) { ... }
export function confiaEslint({ ..., ui = false, app = false, react = false }) { ... }
```

Ámbitos de `no-restricted-syntax`, cada uno con su lista completa porque en configuración plana una entrada reemplaza a la anterior:
`src/**/*.{ts,tsx}` (formato manual, token primitivo, literal visual, importes, escritura de cookie), `src/**/*.tsx` sin pruebas ni
*fixtures* (más texto en JSX), `src/**/*.test.tsx` (más consulta por estructura) y `__fixtures__` (nada). `no-restricted-globals` y
`no-restricted-properties` rigen en `src/**` y `e2e` queda fuera; `src/shared/api/**` se ignora con `ignores` exacto, así que
`src/shared/api-helpers/` no hereda la excepción (BX56).

**Importes.** En `ui`, `MONEY_ARITHMETIC` prohíbe toda aritmética porque su ámbito es una carpeta de dinero. En la app sería falso:
una cuenta regresiva resta. `APP_MONEY` selecciona llamadas a `Number`, `parseFloat`, `parseInt`, `new Number`, unarios, binarios y
asignaciones aritméticas, y `Math.*`, cuando el operando es `*.amount` o un identificador `amount` o `*Amount`.

**Límite conocido:** un alias (`const a = money.amount; Number(a)`) no se detecta. Hoy la app no maneja importes, y el primer cambio
con importes puede subir a una regla con tipos.

**Alternativa descartada:** `ui: true` en la app (prohibe su propio cliente) o no activar nada (pierde las reglas comunes).

### D3. Reglas de dependencia nuevas

| Regla | Desde | Hacia | Violación deliberada |
|---|---|---|---|
| `apps-use-ui-public-entry` | `apps/` | `packages/ui/src/` y `@confia/ui/...`, salvo `index.ts`, `i18n/resources.ts`, `tokens/index.css`, `./i18n`, `./styles.css` | `imports-ui-internal.ts` (AW04) |
| `admin-presentation-is-pure` | `apps/admin-web/src/(.+/)?components/` | `@tanstack/react-query`, `@tanstack/query-core`, `@tanstack/react-router`, `@tanstack/react-form`, `apps/admin-web/src/shared/api/`, `^[@~#]/?shared/api(/|$)` | relativa, alias y `import type` (BX58-BX60) |
| `admin-shared-never-imports-features` | `apps/admin-web/src/shared/` | `apps/admin-web/src/(features|app)/` | Sostiene que `shared/api` no conoce la sesión |
| `admin-token-store-is-private` | `apps/admin-web/src/`, salvo `features/identity/session/` | `features/identity/session/token-store` | Sostiene la invariante 1 |

Las dos primeras las pide la especificación; el router y Form en la presentación y las dos últimas son adiciones de bajo costo que
sostienen AW57 y las invariantes. El alias funciona sin `tsConfig` porque `to.path` compara también el nombre sin resolver, igual que
`ui-never-fetches-data`.

**Conjunto vacío (ADR-0018, BX62, BX46, BX57, BX65).** Las reglas llegan en el PR 1, antes de que exista código real. Esas cuatro
aserciones entran como `it.todo` con el PR que las activa y se convierten en prueba allí: BX57 y BX65 en el PR 2, BX46 y BX62 en el PR 9.
Un `todo` se ve en el informe; un paso en verde sobre un conjunto vacío, no.

### D4. Mismo origen, puertos y CSP

```ts
// vite.config.ts
const apiProxy = { "/api": { target: resolveApiTarget(process.env["CONFIA_API_PROXY_TARGET"]), changeOrigin: false } };
export default defineConfig({
  plugins: [react(), tailwindcss()],
  publicDir: false,
  build: { assetsInlineLimit: 0 },
  server:  { host: "localhost", port: 5173, strictPort: true, proxy: apiProxy },
  preview: { host: "localhost", port: 5173, strictPort: true, proxy: apiProxy, headers: { "Content-Security-Policy": CSP } },
});
```

```
default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self' data: blob:; font-src 'self';
connect-src 'self'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'; object-src 'none';
worker-src 'self' blob:; manifest-src 'self'
```

Frente a `docs/03:1228-1243`: `script-src 'self'` en lugar del *nonce* (cambio 11), `connect-src 'self'` según su nota del 2026-10-23,
y sin `upgrade-insecure-requests` ni `report-uri`, porque aquí no hay HTTPS ni endpoint de informes.

- `assetsInlineLimit: 0`: una fuente pequeña inlineada como `data:` chocaría con `font-src 'self'`.
- `publicDir: false`: evita que un `msw init` futuro copie un worker al build.
- `dev` no lleva CSP: Vite inyecta scripts y estilos en línea. React aplica `style` por CSSOM, que la CSP no bloquea; AW12 lo prueba.

### D5. Enrutador, arranque y guardas

**Elección.** TanStack Router por código, un módulo de rutas (`features/identity/routes.tsx`) más la raíz en `app/router.tsx`.
**El arranque no es una ruta sino `BootGate`**, por encima de `RouterProvider`, porque SS07 y SS10 exigen el mismo comportamiento desde
cualquier URL y porque así ninguna guarda necesita esperar una promesa. Sin cookie, el estado inicial ya es `anonymous` y el enrutador
monta en el primer render, sin pantalla de restauración.

| Ruta | Guarda (`beforeLoad`) |
|---|---|
| raíz | Si el destino no es de segundo factor, `session.discardSecondFactor()` |
| `/` | Sin sesión: a `/iniciar-sesion` |
| `/iniciar-sesion` | Con sesión: a `/` |
| `/iniciar-sesion/verificacion` | Con sesión: a `/`. Sin token `mfa-verify`: aviso `verification-interrupted` y a `/iniciar-sesion` |
| `/iniciar-sesion/inscripcion` | Con sesión: a `/`. Sin token `mfa-enroll`: aviso `enrollment-interrupted` y a `/iniciar-sesion` |

Descartar en la raíz cubre el regreso y el avance (SS31) en un solo lugar. Los avisos de llegada viven en memoria de `AccessSession`,
fuera de la URL y de `history.state`; la pantalla los lee y una recarga los pierde (SS60). Las navegaciones tras éxito usan `replace`
(SS99). Sin parámetro `redirect` (AW19). El router escribe `__TSR_index` y `__TSR_key` en `history.state`: no son secretos y SS02 busca
centinelas, no un estado vacío.

**Foco y título.** `ScreenLayout` pone `main` y un único `h1` con `tabIndex={-1}`, lo enfoca al montar y fija `document.title` con una
clave del catálogo. `lang` sale estático en `index.html` y se sincroniza con `resolvedLanguage`.

**Alternativas descartadas:** rutas por archivos (proposal); `beforeLoad` que espera la restauración (mostraría `pendingComponent` y
SS07 pediría un caso especial).

### D6. Cliente de API

`http.ts` es el único `fetch`. Rutas relativas, `credentials: "same-origin"`, tiempo límite propio de 60 s, y nunca lanza:

```ts
export type ApiResult<T> =
  | { kind: "ok"; status: number; data: T }
  | { kind: "problem"; problem: ApiProblem }       // incluye código desconocido y cuerpo ilegible
  | { kind: "network" }                            // sin estado HTTP; incluye el tiempo límite
  | { kind: "unexpected-response"; status: number } // 2xx que no cumple el esquema
```

`auth-operations.ts` exporta las seis operaciones, con tokens y valor CSRF **por parámetro**: `shared/api` no conoce la sesión (AW33-AW39).
`csrf-cookie.ts` divide `document.cookie` por `; ` y compara el nombre exacto (AW40); vacío equivale a ausente (SS08). Es la única lectura
de `document.cookie`. Un tiempo límite se trata como red y no se reintenta, porque el servidor pudo haber rotado.

**Alternativa descartada:** orval con generador de operaciones (T3): el mutador con refresco, CSRF y token restringido igual se escribe a
mano. Se reevalúa con el primer endpoint de negocio.

### D7. Analizador de `ProblemDetail`

```ts
export const KNOWN_PROBLEM_CODES = [/* los quince de la especificación */] as const;
export type ProblemCode = (typeof KNOWN_PROBLEM_CODES)[number];
export type ApiProblem = {
  status: number; code: ProblemCode | "unknown"; traceId?: string;
  errors: readonly { field: string; reason: string }[]; retryAfterSeconds?: number;
};
```

- **No usa `ProblemDetail.safeParse`.** `instance` llega relativo (`ProblemResponses.java:106`) y `zod.url()` lo rechaza, de modo que toda
  respuesta de error real caería en «desconocido». Lee `type`, `traceId` y `errors` campo por campo con un esquema angosto. `ProblemDetail`
  sigue siendo la fuente de la forma: el simulador valida con `ProblemDetail.omit({ instance: true })` y emite `instance` relativo, como el
  backend. Sección 13, cambio 4.
- `title`, `detail` e `instance` no entran al tipo: no se pueden mostrar ni registrar por error (AW49, AW55).
- `type` debe ser exactamente `https://confia.hn/problems/<código>`; se compara el origen, no un sufijo (AW47).
- `Retry-After`: solo `^[1-9]\d*$`; ausente, `0`, texto o fecha HTTP dan 60; se recorta a 3 600 (AW52, AW53).
- `problem-keys.ts`: `Record<ProblemCode | "unknown", AdminKey>`; un código sin clave no compila (AW48).

### D8. Sesión: almacén y refresco

`createAccessSession({ api, locks, now })` devuelve la única instancia, creada en `session/index.ts`: memoria de módulo, sin `window` y con
reloj y bloqueo inyectables para las pruebas.

```ts
type SessionView =
  | { status: "restoring" } | { status: "restore-failed" }
  | { status: "anonymous"; notice?: ArrivalNotice } | { status: "authenticated" };

interface AccessSession {
  subscribe(listener: () => void): () => void;      // para useSyncExternalStore
  getView(): SessionView;                           // nunca contiene un token
  boot(): Promise<void>; retryBoot(): Promise<void>;
  signIn(c: { identifier: string; password: string }): Promise<SignInOutcome>;
  hasSecondFactorToken(use: "mfa-verify" | "mfa-enroll"): boolean;
  verify(proof: { code: string } | { recoveryCode: string }): Promise<VerifyOutcome>;
  enroll(): Promise<EnrollOutcome>;
  discardSecondFactor(notice?: ArrivalNotice): void;
  readCurrentSession(): Promise<CurrentSessionResponseOutput>;  // lanza ApiFailure; lo usa Query
  signOut(): Promise<SignOutOutcome>;
}
```

`mfa-verify` o `mfa-enroll` se deducen del `status` de la respuesta. El identificador se pasa a `enrollment-store` solo si el `status` es
`second-factor-enrollment-required` (SS59). **El refresco no se dispara con temporizador:** solo lo piden `boot`, `retryBoot` y
`readCurrentSession` si faltan menos de 60 s (`expiresAt - now() < 60_000`, SS22 y SS23).

**Coordinador.**

```ts
async function refreshOnce() {
  inFlight ??= withCrossTabLock(async () => {
    const csrf = readCsrfCookie();              // dentro del bloqueo: otra pestaña pudo rotarla o borrarla
    if (csrf === undefined) return { kind: "no-session" };
    return classify(await api.refresh(csrf));
  }).finally(() => { inFlight = undefined; });
  return inFlight;
}
const withCrossTabLock = (run) =>
  navigator.locks === undefined ? run() : navigator.locks.request(LOCK_NAME, { mode: "exclusive" }, run);
```

| Desenlace | Efecto |
|---|---|
| `200` | Guarda token y vencimiento; `authenticated` |
| Sin cookie al entrar al bloqueo | Termina la sesión local, **sin petición** |
| Error de red o tiempo límite | No reintenta. Al arrancar: `restore-failed`. Antes de una petición: la petición no sale |
| Cualquier otro resultado | Termina la sesión local con aviso `session-expired`; un solo intento (SS26) |

Cada pestaña rota por su cuenta, porque su token está en su propia memoria. `signOut` también toma el bloqueo para no enviar una cookie
vieja durante un refresco ajeno, y un `204` borra memoria y caché. Un `401` a una petición con `Bearer` termina la sesión sin refresco
reactivo (SS25). Al terminar, `app/` limpia Query con `queryClient.clear()` y navega: la sesión no conoce a Query.

**Alternativas descartadas:** token en Query o en Context (T2); un único token por `BroadcastChannel` (sincronización entre pestañas, 13b-2).

### D9. Query

```ts
new QueryClient({ defaultOptions: {
  queries: { retry: retryRead, retryDelay: (n) => 1000 * 2 ** n, networkMode: "always",
             refetchOnWindowFocus: false, refetchOnReconnect: false },
  mutations: { retry: 0, networkMode: "always" },
}});
const retryRead = (count, error) => count < 2 && isRetryable(error); // nunca ante 4xx
```

**Las operaciones de acceso no usan `useMutation`.** Sus variables y datos irían a la caché de mutaciones, que SS03 inspecciona. Los
contenedores usan `use-access-form`, con estado React y un guardia de envío en vuelo (SS40). `useMutation` queda para `signOut` (sin
variables ni datos), que da una instancia real a AW20. `useQuery` queda para `GET /sessions/current`, que solo guarda metadatos. Las
opciones de `currentSessionQueryOptions` se exportan para SS21.

### D10. Formularios

`shared/forms/use-access-form.ts` es **el único** archivo que importa `@tanstack/react-form`. Toma el esquema Zod, valida al enviar y
expone a la presentación solo valores, errores ya traducidos, manejadores y estado.

**Desviación de ADR-0006:169**, que confina Form a la capa de formularios de `packages/ui`: DS02 fija veintiún exportaciones y `ui` no
admite un componente de formulario. Se conserva el objetivo (sustituir Form cambia un archivo) y se reevalúa con la segunda aplicación.

Esquemas: `LoginRequest` y `SecondFactorVerificationRequest` aportan los topes (`*Max`); se agrega `.min(1)`, normalización y validación
cruzada, con mensajes que son `AdminKey`.

| Comportamiento | Mecanismo |
|---|---|
| Botón siempre habilitado, `aria-busy`, `Spinner` (SS39) | `Button loading`, que ya impide el segundo clic y el envío |
| Segunda activación (SS40) | Además, un `ref` de envío en vuelo: Intro también envía |
| Foco tras fallar | `errorTick` en un efecto enfoca el `FormErrorSummary` o el `Alert` por `ref` |
| Espera larga (SS48) | Región `polite` con el texto de espera al empezar el envío; sin temporizador |
| `429` (SS43-SS46) | `use-retry-countdown`: instante límite, intervalo de 1 s solo mientras dura. El número va **fuera** de toda región activa; el `Alert` tiene texto fijo; al terminar una región `polite` anuncia el fin, y un envío antes de tiempo no sale y reenfoca el `Alert` |
| Pegado y `maxLength` (SS51) | Sin atributo `maxLength`: el tope lo valida Zod |

### D11. Inscripción, URI y QR

`enrollment-store` (memoria) guarda `identifier`, `secret`, `recoveryCodes` y `step`. `start()` guarda su promesa: el montaje doble de
React y una segunda llamada reciben la misma, sin depender de la limpieza del efecto (SS74, SS75). Tres secciones en una ruta, solo hacia
adelante:

```
 secret (QR + clave) ─► recovery-codes (casilla) ─► first-code (QR + clave + campo)
                                │ continuar: recoveryCodes = null, irrecuperable
```

Cada paso enfoca su `h2`. En `first-code`, QR y clave siguen visibles (SS94, SS97). Un control vuelve de `first-code` a `secret`, nunca a
los códigos (SS93). El campo TOTP no se monta hasta confirmar (SS92). `discardSecondFactor` borra el almacén.

```ts
export const buildOtpauthUri = (identifier: string, secret: string) =>
  `otpauth://totp/CONFIA:${encodeURIComponent(identifier)}?secret=${secret}&issuer=CONFIA`;
```

`encodeURIComponent` codifica `:`, espacio, `%`, `+`, `@` y letras con tilde; el `:` del prefijo queda literal. `secret` es base32 (patrón del
contrato, `^[A-Z2-7]{32}$`) y no necesita codificación.

**QR: `qrcode.react`, `QRCodeSVG`** con `level="M"`, `marginSize={4}`, `title` del catálogo y colores por defecto (`#FFFFFF` y `#000000`, de
modo que el código no escribe ningún literal visual y el resultado es oscuro sobre claro en ambos temas).

| Criterio | `qrcode.react` | `qrcode` | `uqr` | Propio |
|---|---|---|---|---|
| Sin red, solo navegador | Sí | Sí | Sí | Sí |
| CSP | SVG con atributos, sin `style` ni `innerHTML` | Cadena SVG que pide `innerHTML`, o `create()` y dibujo propio | `encode()` y dibujo propio | Dibujo propio |
| Tamaño y dependencias | Pequeño, sin dependencias de ejecución | Arrastra `pngjs`, `dijkstrajs`, `yargs` | Muy pequeño, sin dependencias | Sin dependencias |
| Mantenimiento | Activo, muy usado | Activo | Pocas versiones | Nuestro; un defecto bloquea cuentas |
| Licencia | ISC o MIT | MIT | MIT | — |

**Lo anterior proviene de conocimiento previo, no de una consulta de hoy.** Sin registro ni código fuente local no se verificó la versión
ni la lectura de atributos. Por eso la primera prueba del PR 14 es SS83 en rojo: sin `style`, con nombre accesible, cero
`securitypolicyviolation`. Si falla, la salida ya decidida es `qrcode` con `QRCode.create()` y un `<path>` propio en `TotpQrCode`.

`jsqr` para SS81: en la prueba, dentro de la página, se dibuja el SVG en un `canvas` por una imagen `data:` (permitida por `img-src`), y
los píxeles se decodifican en Node. Los módulos del QR se verifican contra un fondo claro con los atributos `fill` (SS84).

### D12. Normalización de identificador y códigos

- **Identificador:** `.trim()`; ni NFKC ni minúsculas, para que la etiqueta conserve lo escrito. El backend hace el resto
  (`LoginIdentifier.java:59`). El valor enviado y el guardado salen del mismo resultado del esquema (SS58).
- **Contraseña:** sin transformación.
- **TOTP:** quita `\s` y `U+200B-U+200D`, `U+2060`, `U+FEFF`; exige `^\d{6}$` (SS62, SS63).
- **Recuperación:** quita los mismos caracteres, pasa a mayúsculas y exige de 1 a 64. Las mayúsculas ayudan porque el backend compara
  exacto y genera solo mayúsculas.

### D13. Componentes de `packages/ui`

**`Alert`.** Icono decorativo según `docs/ui-ux/01:507-508` (`Info`, `TriangleAlert`, `CircleAlert`), texto oculto `alert.severity.*`,
`role="alert"` en `destructive` y `role="status"` en las otras. `title?` y `children` obligatorio (DS85). Tokens de nivel 3 en
`components.css`: `--alert-{info,warning,destructive}-{bg,fg,border}`, que apuntan a `--color-status-{pending,partial,overdue}-*`, las
únicas parejas con contraste verificado en ambos temas (`--color-feedback-*` no trae fondo).

**`FormErrorSummary`.** `errors: readonly { controlId: string; message: string }[]`. `role="group"` con `aria-labelledby` del encabezado.
`formErrorSummary.heading` con plural ICU. Nada que renderizar si la lista está vacía. La presencia de cada control se lee con
`useSyncExternalStore(noop, () => claveDeControlesPresentes)`: React revalida la instantánea tras confirmar, sin efecto con `setState`,
que `react-hooks` marca. Sin control: texto, no enlace (DS80). Un enlace es `<a href="#id">` con `preventDefault` y `focus()` (DS78).

**`FormField` recibe `controlId?: string`.** Sin él no hay forma de enlazar un resumen. No cambia ninguna exportación.

**W2.** `transition-colors` pasa a `transition-[color,background-color,border-color]` en `buttonVariants`. **W3.** En `ErrorState`,
`retryLabel?.trim() ? retryLabel : t("common.retry")` y `description` solo si no está en blanco; lo mismo en `EmptyState`.

Índice: 21 nombres. Solo esos dos entran en `index.ts`.

### D14. Traslado de la política y ADR-0028

`packages/config/a11y.ts` con `a11y.test.ts`, por `git mv`. Exportación `./a11y` con `"types"` y `"default"` a `./a11y.ts`: los tres
consumidores (Vitest en navegador, Storybook y Playwright) transforman TypeScript. `axe-core` pasa a `devDependencies` de `@confia/config`,
solo de tipo (BX70). `run-gate.ts` y `.storybook/preview.tsx` cambian una importación; `gate.a11y.test.tsx` no cambia (DS97).

**ADR-0028** registra: Playwright con `@axe-core/playwright` sobre rutas renderizadas, en Propuesto; política única; matriz fija; falla con
cero rutas; y sus cumplimientos. Nota fechada en ADR-0026, sin reescribirlo. Queda fuera: el simulador no es prueba de la pila real.

**Alternativas descartadas:** proyecto de Vitest en navegador (no prueba enrutador ni cookies, T6); exportar la política desde `ui` (rompe
BX72).

### D15. Simulador, aislamiento y puerta

**Arranque.** `e2e/mock-api/start.mjs` es Node plano y llama a `runnerImport("./server.ts")` de Vite, que transforma TypeScript y los `.js`
del contrato. Sirve también al desarrollo manual (`pnpm --filter @confia/admin-web mock:api`). Node 24 no basta solo: `admin.ts:5` importa
`./generated/admin/schemas.js`, que es un `.ts`. **Por probar en rojo en 11a:** que `runnerImport` no externalice un paquete enlazado; la
salida es arrancar en `globalSetup` de Playwright.

**Puerto** `8099`, solo `localhost`. El proxy solo reenvía `/api`, de modo que `/__control` no se alcanza desde la página.

**Aislamiento por contexto.** `context.setExtraHTTPHeaders({ "x-confia-mock-scenario": id })` cruza el proxy, y cada contexto tiene sus
cookies y su escenario, con `fullyParallel`. Dos pestañas del mismo contexto comparten escenario (SS17). El control de los recorridos va a
`POST /__control/scenarios/:id` y `GET /__control/requests/:id`, con el `request` de Playwright.

**Fidelidad.** `session-model.ts` modela familia, rotación, gracia de 10 s (SS120), `Origin` permitido, doble envío CSRF, cookies con los
atributos de WE62 y WE63, tokens restringidos con uso y vencimiento, y `mfa-enroll` rechazando `recoveryCode` (SS121). `contract-responses.ts`
vive en `src/test/`: lo usan el simulador y MSW en Node, así que hay una sola fuente. Valida antes de enviar. Si falla, lanza en prueba
unitaria (SS118), y en ejecución guarda la violación en `/__control/violations`, que un `afterEach` exige vacío (I306).

**Error de red.** El proxy convierte un corte del servidor en `500`, así que **no** sirve para simularlo. Un fallo de red real es
`page.route(url, (r) => r.abort("failed"))`, y `context.setOffline(true)` en SS20.

**Puerta.** `a11y-gate.ts`, una sola función para rutas reales y *fixtures*:

```ts
export async function checkCombination(page, c: Combination): Promise<GateResult> {
  await c.arrange(page);                                       // lleva la app al estado
  await expect(c.expected(page)).toBeVisible({ timeout: 10_000 }); // BX78: sin esto no analiza
  const r = await new AxeBuilder({ page }).withTags([...A11Y_TAGS]).analyze();
  return { ...c.id, theme, ...partitionViolations(r.violations, r.incomplete) };
}
```

`a11y-matrix.ts` es la tabla de `build-integrity` más `not-found`: 22 estados por 2 temas, 44 evaluaciones. `assertCoversMinimum` compara con
el mínimo escrito en la prueba (BX74) y falla con cero (BX77), además de que Playwright sin `--pass-with-no-tests` también falla. Un
`afterAll` escribe `reports/a11y/report.json`.

**Rutas inaccesibles de prueba.** Son HTML en `e2e/fixtures/a11y/` servidos con `page.route` en una URL del mismo origen, sin código en el
enrutador ni bandera de compilación: AW15 se cumple por construcción y una prueba de `specs/build` lo vigila buscando su marcador en `dist`.

**Alternativa descartada:** ruta real tras `import.meta.env`: una bandera mal fijada la publicaría.

### D16. Integración continua y Turborepo

```yaml
  admin-web-e2e:
    name: admin-web e2e and accessibility
    runs-on: ubuntu-latest
    timeout-minutes: 20
    steps:
      - checkout, pnpm/action-setup y setup-node con los SHA de `frontend`
      - run: pnpm install --frozen-lockfile
      - run: pnpm --filter @confia/admin-web exec playwright install --with-deps chromium
      - run: pnpm turbo run test:e2e --filter=@confia/admin-web
      - if: always()   # upload-artifact con el SHA existente; apps/admin-web/reports/; admin-web-e2e-report; 14 días
```

`turbo.json`: `test:e2e` con `cache: false`, `dependsOn: ["^generate", "generate"]`, `outputs: ["reports/**"]`, `passThroughEnv: ["CI"]`; `dev`
con `cache: false`, `persistent: true`, `dependsOn: ["^generate"]`. **`test:e2e` no depende de `build`**: `^build` de `@confia/ui` es Storybook
y este trabajo no lo necesita. `webServer` ejecuta `vite build && vite preview`, con `reuseExistingServer: !CI`.

`ci-workflow.test.ts` agrega el bloque del trabajo y cambia la línea 70 a `slice(0, 5)`. **BX19 no se toca.** El encabezado de `ci.yml` (:3-5)
sigue diciendo que el e2e llega con el cambio 11: BX83 prohíbe modificar líneas, así que el comentario del trabajo nuevo aclara que aquí la API
es simulada.

**BX89: el tiempo no se pudo medir en esta fase.** El PR 2 lo mide en la primera ejecución remota y lo agrega a esta sección con nota fechada.

**Nota del 2026-11-05 (BX89, medida del PR 2, #259):** el trabajo `frontend verify` tardó **1 min 51 s** y **2 min 11 s** en las dos primeras ejecuciones remotas del PR 2. Cabe con holgura en el límite de 10 minutos, así que BX19 no cambia.
Si `frontend verify` no cabe en 10 minutos, se detiene el PR 2: subir el límite cambia BX19.

### D17. Voz

`@confia/config/voice` exporta `findVoiceViolations(catalog, rules)`, sin dependencias. Compara por palabra, sin distinguir mayúsculas.

| Catálogo | En cualquier lugar | Solo al inicio de frase |
|---|---|---|
| `admin` (AW29, AW30) | `tu tus ti te contigo tuyo tuya tuyos tuyas vos tienes puedes quieres debes sabes estás eres has haz tenés podés querés sabés iniciá ingresá escribí revisá intentá usá guardá copiá volvé esperá verificá confirmá abrí cerrá inténtalo comunícate` | `inicia ingresa escribe revisa intenta genera usa guarda copia vuelve espera verifica confirma continúa abre cierra avisa solicita recarga registra asigna` |
| `ui` (DS93, DS94) | Lo anterior, más `usted su sus suyo suya suyos suyas` | Lo anterior, más `inicie ingrese escriba revise intente genere use guarde copie vuelva espere verifique confirme continúe abra cierre avise solicite recargue registre asigne` |

La segunda columna existe porque «Su sesión se cierra» es tercera persona y no debe fallar: la forma coincide con el imperativo de tú, pero no
abre frase. `ten`, `sal`, `ve` y `pon` quedan fuera porque aparecen en texto legítimo.

### D18. Nombre de los recorridos y la guarda de Java

**Elección.** `*.e2e.ts` en `apps/admin-web/e2e/specs/`, como dice `docs/06:71`. `testMatch: /\.e2e\.ts$/`. `apps/api` no se toca (AW10) y
`backend verify` sigue en verde.

**Costo que se deja escrito.** `IdentityScopeExclusionInventoryTest.noPlaywrightTestExercisesTheAuthenticationFlowYet` seguirá en verde y dirá
algo falso: existe una prueba de Playwright y la guarda ya no la ve. El delta de `identity` reemplaza el requisito que cita. Se corrige en
`packages/config/vitest.js` el comentario que dice «`*.spec.ts` reservado para Playwright». Sección 16, punto 3: decide el propietario.

**Alternativa descartada:** `*.spec.ts`, que rompe `backend verify` y exige editar Java contra AW10.

### D19. Dependencias nuevas

**No se pudo consultar ningún registro en esta fase.** En `node_modules` ninguna existe. Cada versión se fija en el PR 2 (en el PR 14, la de QR),
con estas condiciones: publicada hace más de 14 días, `pnpm audit --audit-level high` limpio y `pnpm why` sin duplicar `react` ni `axe-core`.

| Paquete | Sección | Restricción |
|---|---|---|
| `@tanstack/react-router`, `@tanstack/react-query`, `@tanstack/react-form` | `dependencies` | Versión exacta (ADR-0006:440) |
| `qrcode.react` | `dependencies` | D11 |
| `lucide-react`, `react`, `react-dom`, `react-i18next`, `i18next`, `i18next-icu`, `zod` | `dependencies` | Ya en catálogo |
| `@playwright/test` | `devDependencies` | **Exactamente 1.64.1**: igual que `playwright`, para un solo Chromium |
| `@axe-core/playwright` | `devDependencies` | Que resuelva a `axe-core` 4.14.0 |
| `msw` | `devDependencies` | Sondeado con 3.0.3; falta comprobar antigüedad. Sin `msw init` |
| `jsqr` | `devDependencies` | Solo prueba |

**`minimumReleaseAge: 20160` se agrega en el PR 2**, con su fijación en `workspace-guards.test.ts`. Hoy nada hace cumplir la regla y BX93 no tendría
qué comparar. Por verificar en rojo: que pnpm 12.8.1 lea esa clave.

> **Nota del 2026-11-05 (tarea 2).** pnpm 12.8.1 sí lee `minimumReleaseAge`. Al activarla, el archivo de bloqueo heredado de `main` dejó de pasar la política: `browserslist@4.29.5` (publicada el 2026-10-26, la trae `@babel/core`) y otras diez versiones transitivas tenían menos de 14 días. Decisión del propietario: sin `minimumReleaseAgeExclude`; se fija `browserslist` a 4.29.4 con un `overrides` fechado en `pnpm-workspace.yaml` (mismo mecanismo que `undici`) y el archivo de bloqueo se regeneró para que pnpm elija versiones maduras. El `overrides` se retira cuando 4.29.5 cumpla 14 días, hacia el 2026-11-09. Las versiones del catálogo no cambian.

## 6. Flujos

**Arranque, con y sin cookie**

```
 BootGate       AccessSession     refresh-coordinator      navigator.locks     API
    │ getView()       │                                                         │
    │ sin CSRF ──► anonymous ─► monta enrutador                (cero peticiones)│
    │ con CSRF ──► restoring ─► BootScreen                                      │
    │ boot() ────────►│ refreshOnce() ──────►│ request(lock) ─────►│            │
    │                 │                      │ readCsrfCookie()    │            │
    │                 │                      │ POST /refresh, X-CSRF-Token ────►│
    │                 │                      │◄──── 200 + Set-Cookie ───────────│
    │◄─ authenticated │◄─ token en memoria ──│ libera ────────────►│            │
    │ o 401/otro ─► anonymous(session-expired); o red ─► restore-failed         │
```

**Dos pestañas (SS17)**

```
 Pestaña A                  Bloqueo                  Pestaña B
 request ──► concedido                                request ──► en espera
 lee CSRF-1; POST refresh(CSRF-1)
 ◄─ 200, cookies nuevas ─► libera ──────────────────► concedido
                                                      lee CSRF-2; POST refresh(CSRF-2)
```

**Inicio de sesión con verificación**

```
 SignInContainer     AccessSession              API
   │ onSubmit(valores validados)                 │
   │ signIn() ─────────►│ POST /sessions ───────►│
   │                    │◄─ 200 second-factor-required + token
   │                    │ guarda token restringido, uso mfa-verify
   │◄─ {kind:"second-factor-required"}           │   (sin token)
   │ navigate(verificacion)                      │
 SecondFactorContainer  │                        │
   │ verify({code}) ───►│ POST /mfa/verifications, Bearer restringido ─►│
   │                    │◄─ 200 sesión + cookies │
   │                    │ guarda acceso; descarta restringido
   │◄─ {kind:"authenticated"}; navigate("/", replace)
```

**Inscripción**

```
 EnrollmentContainer   enrollment-store     AccessSession            API
   │ efecto: start() ───────►│ promesa única ──► enroll() ─ POST /mfa/enrollments ─►│
   │                         │◄── {secret, recoveryCodes}; 201 ◄───────────────────│
   │◄ paso secret: buildOtpauthUri(identifier, secret); solo en memoria
   │ confirmar códigos ─────►│ recoveryCodes = null
   │ verify({code}) ──────────────────────────►│ POST /mfa/verifications (solo code) ─►│
   │◄ authenticated; store.clear(); navigate("/", replace)
```

**Cierre:** `signOut` toma el bloqueo, lee CSRF y envía `DELETE` sin `Bearer`. `204`: borra memoria y caché y navega sin aviso. Cualquier otro
resultado: `Alert` con reintento y la sesión no se declara cerrada (SS109).

## 7. Mapa de errores

Los textos fijos están en `specs/staff-sign-in`; el resto, en la sección 8.

| Resultado | Inicio de sesión | Verificación y primer código | Inscripción (POST) | Página mínima (`GET`) | Refresco | Cierre |
|---|---|---|---|---|---|---|
| `authentication-failed` | `Alert` destructivo, texto fijo, conserva identificador | — | — | — | — | — |
| `second-factor-invalid` | — | `Alert`: texto TOTP o de recuperación, según modo | — | — | — | — |
| `validation-failed` con `errors` | Resumen y campos; razón `not-blank`, `size`, `max` o genérica; campo ajeno como texto | Igual | Inesperado | — | — | — |
| `validation-failed` sin `errors` | `Alert` de datos no procesados | Igual | Inesperado | — | — | — |
| `too-many-requests` | `Alert` fijo y cuenta regresiva | Igual | `ErrorState` | `ErrorState` | Termina sesión | `Alert` con reintento |
| `capacity-exceeded` | `Alert` servicio ocupado | Igual | `ErrorState` | `ErrorState` | Termina sesión | `Alert` con reintento |
| `token-expired`, `token-invalid` | — | Descarta; aviso de interrupción | Descarta; aviso de inscripción | Termina sesión | — | — |
| `authentication-required`, otro `401` | Inesperado | Igual que arriba | Igual que arriba | Termina sesión | Termina sesión | `Alert` con reintento |
| `session-expired`, `csrf-invalid` | Inesperado | Inesperado | Inesperado | Inesperado | Termina sesión | `Alert` con reintento |
| `mfa-already-enrolled` | — | — | Descarta; aviso ya inscrito | — | — | — |
| `internal-error`, desconocido, `unsupported-media-type`, `forbidden`, `resource-not-found` | Mensaje genérico; traza visible y copiable | Igual | `ErrorState` con traza | `ErrorState` con traza | Termina sesión | `Alert` con reintento y traza |
| Respuesta que no cumple el esquema | Mensaje genérico, sin token | Igual | `ErrorState` | `ErrorState` | Termina sesión | No aplica: `204` sin cuerpo |
| Red o tiempo límite | `Alert` de red; conserva valores | Igual | `ErrorState`; reintento manual | `ErrorState`; hasta 2 reintentos de lectura | Sin reintento; arranque: `restore-failed`; petición: no sale | `Alert` con reintento |

Un `5xx` de lectura reintenta dos veces y un `4xx` ninguna. **Termina sesión:** vacía memoria y caché y lleva al inicio de sesión con «Su sesión se
cerró por seguridad. Vuelva a iniciar sesión.».

## 8. Interfaces y textos

**`AdminKey`:** `type AdminKey = Leaves<typeof admin>`, y `useAdminText()` es el espejo de `useUiText()`. `createAdminI18n` registra `ui` y `admin`, con
`supportedLngs: ["es-HN"]`, `fallbackLng: "es-HN"`, ICU y sin *backend*. `text-keys.ts` de `packages/ui` pasa a `@confia/config/text-keys`, como
`createTextKeyChecker(packageRoot)`, porque la app lo necesita igual; `packages/ui` cambia su importación.

**Textos propuestos para revisión del propietario.** No son definitivos; van al catálogo `es-HN` en «usted» y se cambian sin tocar código.

| Clave | Texto propuesto |
|---|---|
| `signIn.contact` | Si no recuerda su contraseña, comuníquese con la administración de su institución. |
| `signIn.waiting` | Verificando sus datos. Esto puede tardar hasta 30 segundos. |
| `secondFactor.waiting` | Verificando el código. Esto puede tardar hasta 30 segundos. |
| `notice.verificationInterrupted` | La verificación se interrumpió. Inicie sesión de nuevo para continuar. |
| `notice.enrollmentInterrupted` | La inscripción se interrumpió. El código QR y los códigos de recuperación anteriores ya no sirven; al iniciar sesión se generarán nuevos. |
| `notice.alreadyEnrolled` | Su cuenta ya tiene un segundo factor inscrito. Inicie sesión y use su código de verificación. |
| `signedIn.title`, `signedIn.message` | Sesión iniciada. / Su sesión está activa. |
| `problem.capacityExceeded` | El servicio está ocupado en este momento. Espere un momento e intente de nuevo. |
| `problem.network` | No se pudo conectar con el servidor. Revise su conexión e intente de nuevo. |
| `problem.recoveryInvalid` | El código de recuperación no es correcto o ya se usó. Revíselo e intente de nuevo. |
| `problem.notProcessed` | No se pudieron procesar los datos enviados. Revíselos e intente de nuevo. |
| `countdown.remaining`, `countdown.done` | Puede intentar de nuevo en {seconds} s. / Ya puede intentar de nuevo. |
| `copy.key`, `copy.keyDone` | Copiar clave / Clave copiada. |
| `copy.trace`, `copy.traceDone` | Copiar identificador / Identificador copiado. |
| `copy.failed` | No se pudo copiar. Seleccione el texto y cópielo manualmente. |
| `boot.restoring` | Restaurando su sesión. |
| `boot.failed` | No se pudo restaurar la sesión. Revise su conexión e intente de nuevo. |
| `notFound.title`, `notFound.link` | Esta página no existe. / Ir a inicio de sesión |

Claves de `ui`: `alert.severity.{info,warning,destructive}` («Información», «Advertencia», «Error») y `formErrorSummary.heading`, con DS75 y DS76.

## 9. Cambios de archivos, por rebanada

| PR | Archivos |
|---|---|
| 1 | `packages/config/eslint.js`, `eslint.d.ts`; `.dependency-cruiser.cjs`; `tooling/dependency-fixtures/apps/admin-web/src/**`, su `README.md`; `tooling/src/{eslint-app-rules,dependency-rules,suppression-citations}.test.ts`, `suppression-scanner.ts` (agrega `target`, `playwright-report`, `test-results`) |
| 2 | `apps/admin-web/{package.json,index.html,tsconfig.json,eslint.config.js,vite.config.ts,vitest.config.ts}`, `src/main.tsx`, `src/app/{App,router}.tsx`; `pnpm-workspace.yaml`, `pnpm-lock.yaml`, `turbo.json`; `tooling/src/workspace-guards.test.ts` |
| 3 | `src/i18n/**`, `src/app/query-client.ts`; `packages/config/{voice,text-keys}.ts`; `packages/ui/src/test/text-keys.ts`; `docs/ui-ux/05` |
| 4 | `src/shared/api/{http,csrf-cookie,problem,problem-keys,report-unknown-problem}.ts` |
| 5 | `src/shared/api/auth-operations.ts`, `src/test/{contract-responses,msw-server}.ts` |
| 6 | `src/features/identity/session/**` |
| 7 | `packages/ui/src/molecules/alert/**`, `atoms/button/Button.tsx`, `molecules/{error-state,empty-state}/**`, `tokens/components.css`, `i18n/locales/es-HN/ui.json`, `index.ts`, prueba de exportaciones |
| 8 | `packages/ui/src/molecules/form-error-summary/**`, `molecules/form-field/FormField.tsx`, `ui.json`, `index.ts`, prueba de voz de `ui` |
| 9 | `src/app/{BootGate.tsx,components/NotFound.tsx}`, `src/features/identity/{routes.tsx,boot/**,signed-in/**,components/**}`, `admin.json` |
| 10 | `packages/config/{a11y.ts,a11y.test.ts,package.json}`, `packages/ui/src/a11y/{policy.ts,policy.test.ts}` (salen), `run-gate.ts`, `.storybook/preview.tsx`, `docs/adr/{ADR-0028-*.md,README.md,ADR-0026-*.md}`, `CLAUDE.md`, `docs/01` |
| 11a | `e2e/mock-api/**`, `e2e/support/{totp,sentinels}.ts`, pruebas de esos módulos; `packages/config/vitest.js` (comentario) |
| 11b | `playwright.config.ts`, `e2e/support/{fixtures,a11y-gate,a11y-matrix}.ts`, `e2e/fixtures/a11y/**`, `e2e/specs/{a11y,build,access/boot-and-signed-in}.e2e.ts`; `.github/workflows/ci.yml`; `turbo.json`; `tooling/src/ci-workflow.test.ts` |
| 12a, 12b | `src/features/identity/sign-in/**`, `src/shared/forms/**`, `admin.json`, filas de matriz, `e2e/specs/access/sign-in.e2e.ts` |
| 13 | `src/features/identity/second-factor/**`, filas, `e2e/specs/access/second-factor.e2e.ts` |
| 14 | `src/features/identity/enrollment/{otpauth-uri,enrollment-store,EnrollmentContainer}.*`, `components/{SecretStep,TotpQrCode}`, filas, `e2e/specs/access/enrollment-secret.e2e.ts` |
| 15 | `components/{RecoveryCodesStep,FirstCodeStep}`, `e2e/specs/access/{enrollment,secrets,keyboard}.e2e.ts`, `docs/09`, `docs/ui-ux/{04,06}`, spec del cierre |

## 10. Estrategia de pruebas, por PR (TDD estricto)

Comandos: **U** `pnpm --filter @confia/admin-web test`; **E** `pnpm --filter @confia/admin-web test:e2e`; **C** `pnpm --filter @confia/config test`;
**T** `pnpm --filter ./tooling test`; **UI** `pnpm --filter @confia/ui test` y `test:a11y`. La prueba de PR completo es
`pnpm turbo run lint typecheck test build --filter=!@confia/api`.

| PR | Comando | Rojo primero | Escenarios |
|---|---|---|---|
| 1 | T | Violaciones en línea y en árbol; `ui` sigue igual | BX38-BX45, BX47-BX56, BX58-BX61, BX63, BX64, AW04; `todo`: BX46, BX57, BX62, BX65 |
| 2 | U, T | Proxy con `createServer` y un servidor de prueba, sin Chromium; destino no local aborta; guarda de antigüedad | AW01, AW07, AW08, AW10, AW11, AW16, AW17, AW62, BX57, BX65, BX88, BX91-BX94, BX96-BX99, BX89 (medida) |
| 3 | U, C | Clave inexistente; concatenación; «Inicia sesión de nuevo»; opciones de Query | AW23-AW32, BX42, BX43 |
| 4 | U | Tablas de Problem; `502` HTML; cookie con nombre parecido; 31 s con temporizador simulado | AW40, AW43-AW48, AW50, AW52, AW53, AW55, AW56 |
| 5 | U | Cabeceras y cuerpos exactos; respuesta fuera de esquema | AW33-AW39, AW41, AW42, AW20-AW22, I310 |
| 6 | U | Reloj y bloqueo inyectados: 59 y 60 s, disparos simultáneos, rechazo compartido, sin Web Locks, CSRF leído dentro del bloqueo | SS14-SS16, SS18, SS21-SS26, SS06, SS59 (almacén), AW37 |
| 7 | UI | Tipos sin contenido; estilo calculado en Chromium; etiqueta en blanco; `p` vacío | DS82-DS87, DS90-DS92 |
| 8 | UI | Foco programático, URL intacta, sin control, orden, plural; exportaciones | DS01-DS03, DS75-DS81, DS88, DS89, DS93-DS95 |
| 9 | U | Guardas y BootGate; `StrictMode`; flujo jsdom de memoria y caché | SS07-SS13, SS27, SS30, SS32-SS36, SS103-SS112, AW18, AW19, AW57-AW61, AW63, BX46, BX62 |
| 10 | C, UI | `policy.ts` no existe y `ui` importa de `config` | BX66-BX73, DS96, DS97, BX100, BX101, BX106, BX107 |
| 11a | U | RFC 6238; gracia a 5 y 30 s; `mfa-enroll` con recuperación; respuesta fuera de contrato | SS118-SS121, I306 |
| 11b | E, T | *Fixtures* inaccesibles antes que las rutas reales; `dist` limpio; `ci.yml` | BX74-BX88, BX90, BX95, AW02, AW03, AW05, AW06, AW09, AW12-AW15, SS17, SS19, SS20, SS122, SS124-SS126 |
| 12a, 12b | U, E | Presentación con propiedades; contenedor con MSW; cuenta regresiva con observador | SS37-SS60, SS101, SS102, AW49, AW51, AW54 |
| 13 | U, E | Normalización de TOTP; modo recuperación; token vencido; teclado | SS28, SS31, SS61-SS73, SS113, SS115, I304 |
| 14 | U, E | SS83 **antes** de escribir el componente; URI con caracteres reservados; decodificación | SS29, SS74-SS88, I311 |
| 15 | E | Centinelas en cada recorrido; códigos no reaparecen | SS01-SS05, SS89-SS100, SS114, SS116, SS117, SS123, SS127, I305, I307-I309, BX102-BX105, BX108 |

**Cobertura:** `vitest.config.ts` copia los umbrales de `packages/ui` (80 %, archivos sin prueba en el denominador), BX97-BX99.

**Caché en el flujo jsdom (SS03, SS06, parte de SS116).** Producción no expone el `QueryClient` y agregarlo solo para prueba sería una puerta. `session-secrets.flow.test.tsx` monta `App` con MSW y centinelas y serializa ambas cachés en
cada pantalla. Sección 13, cambio 2. Los demás lugares de SS116 los ve Playwright.

**Centinelas:** `e2e/support/sentinels.ts` busca en claro, base64, URL-codificada y en grupos de 4, en almacenamientos, URL y `history.state` en cada pantalla, consola,
propiedades enumerables de `window` y cuerpos y cabeceras de cada petición.

**Tiempos:** `page.clock` para SS12, SS19 y SS44; `expect` con límite explícito y `test.setTimeout(60_000)` solo para SS48. Es el único caso con espera real.

## 11. Demostraciones deliberadas (ADR-0018)

| Guarda | Violación | Rechaza |
|---|---|---|
| ESLint de app | «Entrar» en JSX; `aria-label="Cerrar"`; `toFixed`; `toLocaleString`; `Number(money.amount)`; `money.amount * 2` | Regla nombrada |
| Red de app | `fetch`, `XMLHttpRequest`, `WebSocket`, `EventSource`, `window.fetch`, `globalThis.fetch`, `sendBeacon`, `Worker`, en `src/features` y `src/shared/api-helpers` | `no-restricted-*` |
| `ui` tras separar | Las seis de 13a; `fetch` en `ui` | Misma regla (BX47, BX48) |
| Cookie | `document.cookie = "x"` | Selector nuevo |
| Dependencias | Cuatro reglas de D3, con relativa, alias y `import type` | Regla nombrada |
| Conjunto vacío | Selector de presentación sin archivos reales | Prueba de BX62 |
| Supresiones | `eslint-disable` sin ADR en `apps/`; con ADR no cuenta | Escáner |
| Claves | `missing-key.fixture`, `built-key.fixture` de `admin` | `tsc` y prueba |
| Voz | «Inicia sesión de nuevo» en `admin`; «Revisa» y «Revise» en `ui` | Prueba, con la clave |
| Problem | Código en la unión sin clave | `tsc` |
| Contrato | Secreto que no cumple el patrón | SS118 |
| Puerta | `inaccessible.html`, `dark-only-contrast.html`, `moderate-only.html`, `stuck-loading.html`, matriz vacía | BX76-BX80, BX77 |
| CSP | Página con `<style>` en línea servida con la cabecera | Se dispara `securitypolicyviolation`: el detector funciona |
| Build | Marcador de *fixture*, `mockServiceWorker`, `msw`, `@playwright/test`, `axe` en `dist` | `specs/build` |

## 12. Entrega

**Líneas efectivas, por PR.** Producción, configuración y documentos (P), y pruebas e infraestructura de prueba (T). El simulador cuenta como T. **Son estimaciones
de este diseño, no mediciones.** Se aplican los sobrecostos de 2 a 4 veces vistos en el cambio 8 solo sobre T. Tope: 800 (`docs/15` §3). El PR 10 cuenta el traslado como renombre.

| PR | Rebanada | Contenido | P | T | P+2T | P+4T | Veredicto |
|---|---|---|---|---|---|---|---|
| 1 | `app-static-rules` | ESLint, dependencias, escáner, *fixtures* | 220 | 150 | 520 | 820 | Justo |
| 2 | `admin-web-package-and-router` | Paquete, Vite, proxy, enrutador, turbo, antigüedad, medida | 290 | 90 | 470 | 650 | Cabe |
| 3 | `providers-i18n-and-voice` | Proveedores, catálogo, Query, voz, nota de `05` | 300 | 110 | 520 | 740 | Cabe |
| 4 | `api-transport-and-problem-map` | Transporte, CSRF, Problem, mapa | 270 | 130 | 530 | 790 | Justo |
| 5 | `auth-operations-and-contract-mocks` | Operaciones, respuestas de contrato | 230 | 110 | 450 | 670 | Cabe |
| 6 | `session-basics` | Sesión y coordinador | 325 | 170 | 665 | 1 005 | Se parte a 4x |
| 7 | `ui-alert-and-fixes` | `Alert`, W2, W3 | 160 | 90 | 340 | 520 | Cabe |
| 8 | `ui-form-error-summary` | `FormErrorSummary`, `FormField`, voz de `ui` | 125 | 90 | 305 | 485 | Cabe |
| 9 | `boot-and-signed-in-page` | BootGate, página mínima, no encontrada, guardas | 380 | 150 | 680 | 980 | Se parte a 4x |
| 10 | `a11y-policy-move-and-adr` | Política, ADR-0028, notas | 210 | 15 | 240 | 270 | Cabe |
| 11a | `simulated-api` | Simulador, TOTP, modelo de sesión | 60 | 340 | 740 | 1 420 | **Partido ya; se parte de nuevo a 4x** |
| 11b | `route-gate-and-ci` | Puerta, matriz, CI, recorridos de arranque | 120 | 240 | 600 | 1 080 | **Partido ya; se parte a 4x** |
| 12a | `sign-in-form` | Formulario, `use-access-form`, contraseña, contacto | 260 | 190 | 640 | 1 020 | **Partido ya; se parte a 4x** |
| 12b | `sign-in-states` | `429`, `503`, espera, avisos, axe, recorrido | 170 | 140 | 450 | 730 | Partido ya |
| 13 | `second-factor-screen` | Verificación, recuperación, axe, recorrido | 235 | 240 | 715 | 1 195 | Se parte a 4x |
| 14 | `enrollment-secret-and-qr` | Almacén, URI, QR, clave, decodificación | 275 | 170 | 615 | 955 | Se parte a 4x |
| 15 | `enrollment-activation-and-criterion-8` | Códigos, primer código, centinelas, `docs/09` | 190 | 230 | 650 | 1 110 | Se parte a 4x |
| | **Total** | | **3 820** | **2 655** | **9 130** | **14 440** | |

**Órdenes duros:** los de la propuesta, con 11a antes de 11b y 12a antes de 12b. Paso 0 ya ejecutado.

**La cadena no cabe en el tope de 15 tareas.**

- **Con 2x, ya son 17 PR**, no 15. El PR 11 de la propuesta suma P+2T = 1 340 con el simulador contado como prueba, y el 12 suma 1 090. Ninguno cabe en 800 aunque
  todo salga bien.
- **Con 4x, 14 440 / 800 da al menos 19 PR**, y hasta 26 si cada fila se parte sola. El total coincide con el peor caso de la propuesta, de unas 14 000.
- Fusionar no devuelve a 15: la única pareja coherente que cabe es 7 y 8 (645 a 2x), que da 16 y vuelve a superar el tope a 4x (1 005). Las demás parejas vecinas
  ya superan 800 a 2x (4 y 5 suman 980) o mezclarían rebanadas sin relación.

**Esto activa Q1: es del propietario y este diseño no la decide.** El corte natural es **después de 11b**:

| Parte | Contenido | PR a 2x | PR a 4x |
|---|---|---|---|
| Primera | PR 1 a 11b: reglas, app, cliente, sesión, `ui`, arranque, página mínima, puerta y CI | 12 | De 12 a 17 |
| Segunda | PR 12a a 15: las tres pantallas | 5 | De 7 a 9 |

**Efecto sobre el criterio 8.** Al terminar la primera parte, la puerta corre en verde sobre las pantallas de ese momento, pero BX102 prohíbe marcar el criterio hasta que pase sobre inicio de sesión,
verificación e inscripción. Por lo tanto **el criterio sale de la primera parte y se cierra en la segunda**, contra lo que prometía P7, y el inicio de sesión tampoco existe hasta esa segunda parte.

Si se elige una sola cadena con excepción al tope, el cierre no se mueve, pero `openspec/config.yaml` queda incumplido con razón escrita.

Líneas exactas para `sdd-tasks`: `Decision needed before apply: Yes`; `Chained PRs recommended: Yes`; `400-line budget risk: High`. El presupuesto del proyecto es 800 según `docs/15` §3.

**Migración y puesta en marcha:** sin migraciones, sin datos y sin entorno. Reversión según la propuesta; con 17 PR, sus referencias a «PR 11 a 15» y «PR 10» pasan a 11a a 15 y 10.

## 13. Cambios recomendados en la especificación

Ninguno se editó. Cada uno espera la decisión del propietario.

| # | Escenario | Cambio recomendado | Por qué |
|---|---|---|---|
| 1 | AW59 | Quitar «arranque» de la lista de rutas y decir «más la pantalla de arranque, que no es una ruta» | D5: es un guardián por encima del enrutador |
| 2 | SS03, SS06 y la cláusula de caché de SS116 | Admitir un flujo en jsdom con la aplicación completa | Producción no expone el `QueryClient`; abrirlo sería peor que el problema |
| 3 | SS80, I311 | «La etiqueta decodificada, **sin el prefijo `CONFIA:`**, es idéntica...»; `:`, espacio y `%` del identificador codificados | SS79 ya admite el prefijo y estos dos no; D11 lo usa |
| 4 | Requisito del cliente («valida cada respuesta con cuerpo»), I306, SS118 | Excluir `instance` en errores, o corregir el contrato a `uri-reference` | `zod.url()` rechaza `instance` real. Corregirlo toca `apps/api`, fuera de este cambio: proponer dueño en 8b u 11 |
| 5 | BX93 | «El valor existe y no es menor de 20 160 minutos» | Hoy no hay ninguna configuración que comparar |
| 6 | Agregar DS98 | `FormField` acepta `controlId` y lo usa en control y etiqueta | Sin eso, DS78 no se puede cumplir con `FormField` |
| 7 | BX89 | El tiempo se registra en este diseño al ejecutar el PR 2 | No se puede medir sin construir |

## 14. Riesgos

| Id | Riesgo | Mitigación |
|---|---|---|
| R1 | Desviación entre simulador y backend, sin prueba real hasta el cambio 11, ni manual hasta 8b | Contrato validado, modelo de sesión probado y brecha fechada; nada se declara probado contra la pila real |
| R2 | En la sonda, `SameSite=Strict` es inferido: no hubo control con `Lax` ni `None` | Cambio 11, con cookies reales |
| R3 | `proxy_read_timeout 30s` (`docs/05:1477`) iguala el retardo tope: un fallo lento puede dar `504` en producción, que aquí se muestra como error inesperado | Dueño propuesto: cambio 11. Sin reintento, de modo que no agrava |
| R4 | Las versiones no se verificaron y una puede estar bloqueada | D19; para QR, salida ya decidida |
| R5 | `IdentityScopeExclusionInventoryTest` queda en verde y falso | Sección 16, punto 3 |
| R6 | `runnerImport`, `minimumReleaseAge` y atributos de QR se dedujeron de tipos o memoria | Son la primera prueba en rojo de su PR, con salida escrita |
| R7 | Aislamiento por cabecera sin medir bajo paralelismo | Primera prueba de 11b; la salida es `workers: 1`, a cambio de más tiempo |
| R8 | `frontend verify` puede superar 10 minutos | Medida en PR 2, con detención si no cabe |
| R9 | `APP_MONEY` no ve un alias | Declarado en D2; ningún importe aquí |
| R10 | Copiar el secreto al portapapeles lo deja accesible a otras aplicaciones | Lo pide P2; el aviso no lo oculta y no se limpia el portapapeles, porque leerlo pediría otro permiso |
| R11 | `Files.walk` de la guarda de Java recorre `apps/admin-web`; no sigue enlaces, y no hay `dist/**/*.spec.*`. Más tiempo, sin cambio de veredicto | Sin acción |

## 15. Matriz de amenazas

La matriz de la fase no aplica: el cambio no toca órdenes de consola de producción, automatización de Git o de PR ni clasificación de archivos ejecutables.

| Frontera | Aplicabilidad |
|---|---|
| Rutas con apariencia de documentación | N/A: no se clasifican ni ejecutan archivos |
| Selección de repositorio, estado del commit y del push, órdenes de PR | N/A: sin automatización de Git ni de PR |

Fronteras de seguridad propias, cada una con prueba en rojo en la sección 10:

| Frontera | Comportamiento seguro | Prueba |
|---|---|---|
| Token en caché, URL, historial, consola, `window` | Ningún tipo público lo transporta | SS01-SS06, SS116 |
| Secreto o URI en una petición | No sale | SS85, SS116, I311 |
| Sondeo sin sesión | Cero peticiones sin cookie, también al entrar al bloqueo | SS07, SS08, SS108 |
| Reutilización de refresco | Bloqueo, CSRF dentro del bloqueo, sin reintento | SS12, SS14-SS17, SS24 |
| Refresco de fondo | Sin temporizador, foco ni reconexión | SS19-SS21 |
| Oráculo de cuentas | Un solo texto | SS56 |
| `title`, `detail` o valor rechazado | No entran al tipo | AW49, AW55, SS41 |
| Enlace o clave hacia otro host | Rutas relativas; `connect-src 'self'`; destino del proxy solo local | AW09, AW12, AW45 |
| Inyección en la etiqueta del URI | `encodeURIComponent` | SS79, SS80 |
| *Fixture* inaccesible o simulador en producción | Fuera del código compilado | AW14, AW15, BX95 |
| Señalar sesión cerrada sin cerrarla | Solo tras `204` | SS109 |

## 16. Decisiones para el propietario

Las decisiones técnicas están tomadas. Solo esto necesita al propietario:

1. **Q1. Tope de 15 tareas.** La cadena realista es de 17 a 26 PR (sección 12). Bloquea la fase de tareas. Opciones: cortar después de 11b, con la consecuencia de que el criterio 8 y el inicio de sesión pasan a la segunda parte; o excepción al tope en una sola cadena.
2. **Aprobar ADR-0028 y este diseño**, incluida la desviación de ADR-0006:169 (D10).
3. **La guarda de Java falsa (D18).** Dejarla en verde hasta el cambio 11 y anotarlo; o permitir, como única excepción a AW10 y al criterio «ningún archivo de `apps/api` cambió», editar ese método para que refleje el nuevo requisito. El diseño sigue la primera porque es lo aprobado; la segunda es más honesta y cuesta unas 10 líneas.
4. **Dueño de dos defectos ajenos:** `instance` frente a `zod.url()` (sección 13, cambio 4) y `proxy_read_timeout` (R3). Se proponen el 8b y el cambio 11.
5. **Texto de `FormErrorSummary`:** el delta fija «Hay 2 campos con errores.», impersonal, y `docs/ui-ux/04:604` dice «Revise 2 campos.». Este diseño sigue el delta y agrega la nota en `04`.
6. **Textos de la sección 8.**
7. **Los siete cambios de especificación** de la sección 13.
8. **`minimumReleaseAge`:** agregarlo hace cumplir de verdad una regla que hasta hoy era un comentario, también en futuras actualizaciones.

Ninguna otra pregunta técnica bloquea. Q1 bloquea la fase de tareas si el conteo se confirma.
