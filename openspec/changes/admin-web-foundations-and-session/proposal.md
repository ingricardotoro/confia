# Propuesta: fundaciones de `apps/admin-web` y sesión (cambio 13b-1 de F0, parte 1 de 2)

- **Cambio:** `admin-web-foundations-and-session` (antes `admin-web-scaffold-and-staff-login`)
- **Rama:** `change/admin-web-foundations-and-session`
- **Fase del roadmap:** F0, cambio 13b-1, parte 1 de 2. **No cierra** el criterio de salida 8 de la fase 0; lo cierra la parte 2
  (`docs/09-roadmap-y-fases.md`:294, «La verificación de accesibilidad no reporta violaciones críticas
  en las pantallas existentes»), que la nota fechada de `docs/09`:225-237 asignó al antiguo 13b.
- **Sucesor:** `admin-web-staff-sign-in` (parte 2). **13b-2**, con el shell, la vida completa de la sesión y el aviso de inactividad,
  sigue a la parte 2.
- **Depende de:** `design-system-foundations-and-a11y` (13a), `session-endpoints-cookie-and-csrf`,
  `column-encryption-and-mfa-totp` y los esquemas de `@confia/contracts/admin`.
- **Exploración:** `openspec/changes/admin-web-foundations-and-session/exploration.md` (Engram
  `sdd/admin-web-shell-and-staff-login/explore`, observación 781). Se escribió con el nombre antiguo,
  `admin-web-shell-and-staff-login`; sus decisiones T1 a T11 valen salvo donde el propietario decidió
  otra cosa.
- **Decisiones del propietario:** Engram `sdd/admin-web-shell-and-staff-login/owner-decisions`
  (observación 784), tomadas el 2026-11-04. Son vinculantes y esta propuesta no las reabre.
- **Estado:** aprobada por el propietario el 2026-11-04 (`openspec/config.yaml`, `rules.proposal`); diseño aprobado el 2026-11-05, con
  los siete cambios de especificación de `design.md` §13, y Q1 resuelta ese día.

## Actualización del 2026-11-05: Q1 resuelta y cambio dividido

El propietario decidió **cortar la cadena después del PR 11b** (Q1). El cambio se vuelve dos:

| Parte | Cambio | Contenido | PR |
|---|---|---|---|
| 1 | `admin-web-foundations-and-session`, esta carpeta | PR 1 a 11b: reglas, aplicación, cliente, sesión, `ui`, arranque, página mínima, puerta y CI | De 12 a 17 |
| 2 | `admin-web-staff-sign-in` | PR 12a a 15: las tres pantallas. Empieza cuando la parte 1 esté fusionada | De 7 a 9 |

**Efecto sobre el criterio de salida 8.** La parte 1 **no lo cierra**: su puerta corre en verde sobre las pantallas de ese momento, pero
BX102 prohíbe marcarlo antes de que exista la verificación sobre inicio de sesión, verificación e inscripción. Lo cierra la parte 2, contra lo que
prometía P7. Al terminar la parte 1, una persona todavía no puede iniciar sesión.

**Lectura del resto de esta propuesta.** Se conserva como se aprobó. Lo que dice de las pantallas de inicio de sesión, verificación e
inscripción (alcance 17 a 20), de los PR 12 a 15, del criterio 8 y de los recorridos con inicio de sesión en «Criterios de éxito» pasa a la parte 2. El
reparto por escenario está en `specs/` de cada cambio, y `tasks.md` lista el de esta parte.

**Dueños de los defectos ajenos que encontró el diseño:**

| Defecto | Dueño | Mientras tanto |
|---|---|---|
| El esquema `ProblemDetail.instance` exige `zod.url()` y el backend lo emite relativo | **Cambio 8b** | El cliente no usa `ProblemDetail.safeParse`, y el simulador valida sin `instance` (D7) |
| `proxy_read_timeout 30s` iguala el retardo tope (`docs/05`:1477) | **Cambio 11** | Riesgo R3: sin reintento, de modo que no se agrava |
| La guarda de Java `noPlaywrightTestExercisesTheAuthenticationFlowYet` busca `*.spec.ts` y queda en verde y falsa | **Cambio 11** | Se queda así hasta entonces; `apps/api` no se toca (D18) |

## Intención

CONFIA tiene backend de autenticación completo y un sistema de diseño verificado, pero ninguna
aplicación: `apps/admin-web` no existe. El personal no puede iniciar sesión, y el criterio de salida 8
no se puede cerrar, porque un criterio sobre «pantallas existentes» que no evaluó ninguna pantalla no se
declara cumplido (ADR-0018).

**Por qué ahora:** la fase 0 no termina sin este criterio, y toda pantalla de F1 necesita la aplicación,
sus reglas estáticas, su cliente de API y una sesión que funcione.

**Cómo se ve el éxito:** una persona del personal escribe su identificador y su contraseña, verifica su
segundo factor (o lo inscribe, si es su primera vez) y llega a una página mínima autenticada; al recargar
sigue autenticada; puede cerrar sesión. Ningún token ni secreto queda en almacenamiento legible, en la URL
ni en el historial. La puerta de axe corre sobre esas rutas renderizadas, en los dos temas, dentro de CI.

## Decisiones del propietario aplicadas (2026-11-04)

| Id | Decisión | Consecuencia en esta propuesta |
|---|---|---|
| **P7** | División en 13b-1 y 13b-2 | Tras iniciar sesión se muestra una página mínima, no un shell |
| **P6** | e2e contra API simulada; la de pila real pasa al cambio 11 como brecha fechada | Sin semilla en `apps/api` y sin CORS; un proxy de Vite da el mismo origen que producción |
| **P1** | El personal recibe trato de «usted» | Nota fechada en `docs/ui-ux/05`; revisión de `ui.json` |
| **P2** | QR más clave manual en grupos de 4; emisor «CONFIA»; etiqueta igual al identificador escrito | El QR se genera solo en el navegador; el secreto nunca sale de él |
| **P3** | Texto de contacto, sin enlace de contraseña olvidada | No hay ruta ni endpoint de recuperación |
| **P8** | Playwright con axe sobre rutas, bajo un ADR nuevo | La política de bloqueo pasa de `packages/ui` a `@confia/config` |
| **P9** | Código de recuperación como alternativa en la verificación | Solo con token `mfa-verify` |
| **P10** | «Mostrar contraseña» entra; el selector de tema va a 13b-2 | La aplicación sigue el tema del sistema operativo |

P4 (identidad en el shell) y P5 (contenido del panel vacío) quedan pendientes para 13b-2 y no afectan a
este cambio. P11 (color de importe negativo en `MoneyAmount`) no bloquea: aquí no hay importes.

## Paso 0: sonda de cookies y de simulación

Es el primer paso del cambio y **se ejecuta antes de cerrar el diseño**, porque su resultado decide el
mecanismo de la API simulada. Es código desechable, fuera del repositorio y solo local. No es un PR ni una
tarea de `tasks.md`. Su resultado se registra en `design.md`.

1. **Cookies reales sobre `http://localhost`, en Chromium.** Un servidor Node mínimo, detrás del proxy de
   Vite (`dev` y `preview`), emite `__Secure-confia_admin_sid` (`HttpOnly`, `Secure`, `SameSite=Strict`,
   `Path=/api/v1/auth/sessions`) y `__Host-confia_csrf` (`Secure`, `Path=/`, legible por JS), con los
   atributos de `web-edge` (WE62). Se observa si el navegador las guarda, si las reenvía solo en su ruta,
   si JS lee únicamente la CSRF, si `Max-Age=0` las borra y si el proxy conserva `Origin`.
2. **Simular `Set-Cookie`.** Se comparan tres mecanismos: MSW en el navegador (su documentación dice que
   una respuesta sintética no fija una cookie real), `page.route` con `fulfill`, y el servidor Node simulado
   detrás del proxy. `context.addCookies` queda como complemento.
3. **Criterio de salida.** Se elige el mecanismo que ejercite más atributos reales de las cookies sin meter
   un service worker en el build de producción. Si las cookies fallan sobre `http://localhost`, el desarrollo
   local pasa a HTTPS local, que `web-edge` ya admite; no se agrega ningún indicador que desactive `Secure`,
   porque esa spec lo prohíbe.
4. **Límite conocido.** Sin una cuenta del personal sembrada, el backend real no puede emitir estas cookies.
   Si arrancarlo resulta barato, solo se comprueba que un inicio de sesión fallido a través del proxy
   responde `401` y no un rechazo de `Origin`. La receta de arranque local que se obtenga se documenta.

## Línea de la sesión entre 13b-1 y 13b-2

**Regla:** 13b-1 entrega lo necesario para obtener, conservar, recuperar y terminar una sesión. 13b-2
entrega lo que observa el paso del tiempo y lo anticipa ante la persona.

| Comportamiento | 13b-1 | 13b-2 |
|---|---|---|
| Tokens de acceso y restringido solo en memoria de módulo | Sí | |
| Restaurar al recargar con `POST /refresh`, **solo si existe `__Host-confia_csrf`** | Sí: sin ella, cada recarga exigiría iniciar sesión otra vez y dejaría sesiones vivas | |
| Una sola petición de refresco en vuelo, por pestaña (promesa compartida) y entre pestañas (Web Locks) | Sí: dos pestañas restaurando a la vez ya pueden revocar la familia | |
| Refresco previo a una petición con Bearer iniciada por la persona, si faltan menos de 60 s | Sí, dentro del cliente | |
| Sin temporizadores ni consultas de fondo que refresquen | Sí, como prohibición con prueba | Se mantiene |
| Cierre de sesión con `DELETE /sessions/current`, sin Bearer | Sí, un botón en la página mínima: sin él, una sesión en un equipo compartido no se podría terminar | Pasa al menú de usuario |
| `401 session-expired`: borrar memoria y volver al inicio de sesión con su mensaje | Sí | |
| Lectura de metadatos con `GET /sessions/current` | Sí, al entrar en la página mínima | Se usa para el aviso |
| Detectar actividad de la persona como disparador de refresco | | Sí |
| Aviso de inactividad a los 25 minutos, `Dialog`, región `assertive`, «Continuar» | | Sí |
| Cálculo con `idleExpiresAt` y corrección del desfase de reloj | | Sí |
| Avisos por vencimiento absoluto de la sesión | | Sí |
| Sincronizar el cierre de sesión entre pestañas | | Sí |
| Parámetro `redirect` y regreso a una ruta profunda | No aplica: solo hay una ruta protegida | Sí |
| «Mis sesiones activas» | | Sí, o el cambio que el propietario asigne con P4 |

**Brecha aceptada hasta 13b-2:** la página mínima no detecta por sí sola que la sesión venció por
inactividad. Sigue en pantalla, sin datos personales, y la siguiente acción lleva al inicio de sesión con el
mensaje de sesión vencida. Ese rechazo cuenta como un fallo de la IP en `admin-session`, el costo normal de
una sesión realmente vencida.

## Alcance

### Dentro de alcance

**Reglas de la aplicación (T9)**

1. Separación en `packages/config/eslint.js` entre las reglas comunes (texto en JSX, formato, literales
   visuales, aritmética de importes) y las solo de `ui` (red prohibida, capas). Con `ui: true`, la app
   tendría prohibido su cliente; sin él, perdería las comunes.
2. Reglas de `admin-web`: `fetch`, `XMLHttpRequest`, `WebSocket` y `EventSource` solo en `src/shared/api/**`
   y `e2e/**`; dependency-cruiser impide que la presentación importe `@tanstack/react-query` o `shared/api`.
3. Escáner de supresiones extendido a `apps/`. Cada regla lleva su violación deliberada (ADR-0018).

**Andamiaje (T1, T8)**

4. `apps/admin-web`: Vite, React 19, `@tailwindcss/vite`, `@confia/ui/styles.css`, `uiResources`, tema
   según el sistema operativo y build compatible con la CSP de `docs/03` (sin scripts en línea, sin `eval`,
   sin `style-src` en línea).
5. TanStack Router con **rutas por código**, guardas `beforeLoad` y TanStack Query. Se reevalúa con más de
   15 rutas.
6. Proxy de Vite de `/api` hacia el backend local, en `dev` y en `preview`. Sin CORS.
7. Catálogo i18n `admin` tipado (`AdminKey`) con trato de «usted» y prueba de claves inexistentes.
8. Dependencias nuevas en el catálogo, sujetas a la regla de dos semanas: `@tanstack/*`, `msw`,
   `@playwright/test`, `@axe-core/playwright` y una biblioteca de QR que funcione sin red.

**Cliente de API (T3)**

9. Cliente fino escrito a mano en `src/shared/api`, único lugar con `fetch`: rutas y cabeceras de las
   operaciones de acceso que usa este cambio, `credentials: "same-origin"`, `X-CSRF-Token` leído de la
   cookie, `Authorization` solo donde corresponde y sin `Idempotency-Key`, que no aplica a la autenticación.
   Valida las respuestas con los esquemas de `@confia/contracts/admin`.
10. Parser de `ProblemDetail` a una unión de códigos conocidos más uno desconocido, y mapa de código a clave
    de catálogo. Nunca se muestran `title` ni `detail`.

**Sesión básica (T2)**

11. Lo marcado en la columna 13b-1 de la «Línea de la sesión». Un refresco no se reintenta a ciegas tras un
    error de red.

**Componentes de `packages/ui` (T11)**

12. `FormErrorSummary` y `Alert`, con historia en los dos temas, prueba y claves es-HN. `Dialog` queda en
    13b-2, porque aquí no lo usa nadie.
13. Pendientes W2 (animación del anillo de foco de `Button`) y W3 (etiqueta vacía de `ErrorState` y `<p>`
    vacíos). El arranque necesita W3 para su reintento.
14. «Mostrar contraseña» y el QR viven en `features/identity`, componiendo `Input` e `IconButton`, según el
    criterio de promoción de `docs/ui-ux/02`:87-89.
15. El catálogo `ui` lo comparten panel y portal, y sus textos no se dirigen a la persona. Una primera lectura
    de `ui.json` no encontró ningún verbo en segunda persona; la revisión se cierra con las claves nuevas.

**Pantallas (T4)**

Todas con TanStack Form y Zod (más `.min(1)` y la validación cruzada que los esquemas no expresan), botón de
envío siempre habilitado con `aria-busy`, resumen de errores con foco, pegado permitido y estados explícitos.

16. **Arranque:** restaurando la sesión, o error de red con reintento.
17. **Inicio de sesión:** `username` y `current-password`; «mostrar contraseña»; texto de contacto (P3);
    mensaje único de credenciales inválidas; `400` por campo; `429` con cuenta regresiva de `Retry-After`
    anunciada con cortesía; `503`; espera de hasta unos 30 s sin tiempo límite menor; sesión vencida.
18. **Verificación:** TOTP de 6 dígitos con tolerancia a espacios y `one-time-code`; alternativa de código de
    recuperación; `second-factor-invalid`; token restringido vencido, que vuelve al inicio de sesión con
    mensaje propio.
19. **Inscripción obligatoria:** QR y clave en grupos de 4 con botón de copiar; URI `otpauth://` con emisor
    «CONFIA» y etiqueta igual al identificador escrito, codificada; los 10 códigos de recuperación, una sola
    vez, con confirmación explícita antes de seguir; primer código, solo TOTP; `mfa-already-enrolled`, que
    vuelve al inicio de sesión. El identificador y el secreto se guardan solo en memoria y se borran al terminar.
20. **Rama `authenticated` sin segundo factor:** se maneja y se prueba, aunque tras el cambio 8 no ocurra.
21. **Página mínima autenticada:** confirmación, sin datos personales, con «Cerrar sesión». No es el panel
    vacío de P5, y 13b-2 la reemplaza.

**Puerta de accesibilidad y e2e (T6, T7, T10)**

22. Extraer `packages/ui/src/a11y/policy.ts` a `@confia/config/a11y`, con sus pruebas. La política solo depende
    del tipo de axe-core. `ui` la consume desde ahí; exportarla desde `ui` rompería su punto de entrada único.
23. Playwright con `@axe-core/playwright` sobre una matriz de ruta, estado y tema: claro y oscuro, tras cargar
    el contenido, con la política compartida y fallando con cero rutas (ADR-0018).
24. Recorridos del navegador contra la API simulada que elija el paso 0, cuyas respuestas se validan contra los
    mismos esquemas de `@confia/contracts`. TOTP en pruebas con `node:crypto`, sin dependencia nueva.
25. Job aditivo `admin-web e2e and accessibility` (Chromium, `vite preview`, acciones fijadas por SHA, informe
    como artefacto), con su prueba en `ci-workflow.test.ts`. Tareas `test:e2e` y `dev` en `turbo.json`.

**Documentación y specs**

26. **ADR nuevo** (siguiente número libre, hoy 0028), «axe sobre rutas renderizadas», con índice en
    `docs/adr/README.md` y nota fechada en ADR-0026 sobre el traslado de la política; ADR-0026 no se reescribe.
27. `docs/09`: nota fechada con el nuevo nombre del cambio y la división; se marca el criterio 8 solo cuando la
    puerta pase sobre el inicio de sesión, la verificación y la inscripción. Nota de que semilla y CORS, que la
    propuesta de 13a asignaba a 13b, salen de él.
28. `docs/ui-ux/05`: nota fechada, el personal recibe «usted» (el catálogo de las líneas 105-123 está en tuteo)
    y el tono del portal no cambia. `docs/ui-ux/04` y `06`: notas donde el comportamiento se concreta.
29. `CLAUDE.md` y `docs/01` §3: la fila de pruebas del frontend suma la puerta sobre rutas.
    `packages/config/package.json`: descripción y exportación nueva.

### Fuera de alcance, con dueño nombrado

| Exclusión | Dueño |
|---|---|
| Shell: `Topbar`, `Sidebar`, `PageHeader`, migas, enlace para saltar al contenido, menú de usuario y navegación | **13b-2** |
| Vida completa de la sesión y aviso de inactividad, según la «Línea de la sesión», con `Dialog` | **13b-2** |
| Selector visible de tema claro u oscuro | **13b-2** (P10) |
| Identidad en el shell y contenido del panel vacío (P4, P5) | **13b-2**, con decisión del propietario pendiente |
| Inscripción y retirada voluntarias de MFA con sesión completa | **8b** |
| Creación del primer Super Admin | **8b** |
| Restablecimiento administrativo de contraseña | **8b**. El autoservicio por HTTP no existe y hoy no tiene cambio asignado; P3 no lo necesita |
| e2e contra la pila real con cookies reales del backend, y su job de CI | **Cambio 11**, brecha fechada (P6) |
| Cuenta de personal para esa e2e | **Cambio 8b**, con el mecanismo del primer Super Admin; el cambio 11 la usa (Q2) |
| *Nonce* de la CSP de producción, despliegue de `admin-web` en nginx y regresión visual | **Cambio 11** |
| Semilla y CORS en `apps/api` | Retirados (P6): este cambio no toca `apps/api` |
| Generador de operaciones de orval | Se reevalúa con el primer endpoint de negocio |
| `apps/portal-web` | Primer cambio que entregue una pantalla del portal (recomendación de la propuesta de 13a) |

## Capacidades

> Contrato con `/sdd-spec`. Investigado contra `openspec/specs/` (doce capacidades publicadas:
> `audit-trail`, `background-jobs`, `build-integrity`, `cashbox`, `design-system`, `identity`, `invoicing`,
> `ledger`, `money`, `organization`, `payments`, `web-edge`).

### Nuevas

- **`admin-web-foundation`:** aplicación y mismo origen por proxy, reglas estáticas de la app, cliente de API
  único, traducción de `ProblemDetail`, catálogo `admin`, trato de «usted» y compatibilidad con la CSP.
- **`staff-sign-in`:** pantallas de inicio de sesión, verificación e inscripción con todos sus estados, sesión
  básica (tokens en memoria, restauración, refresco coordinado, cierre, vencimiento), página mínima y
  recorridos e2e simulados.

### Modificadas

- **`design-system`:** el conjunto exacto de exportaciones (`spec.md`:26-45, diecinueve nombres) crece con
  `FormErrorSummary` y `Alert`; W2 y W3; la política de accesibilidad se consume desde `@confia/config`.
- **`build-integrity`:** reglas de app con su violación deliberada, escáner sobre `apps/`, job nuevo de e2e y
  accesibilidad, puerta de axe sobre rutas que falla con cero rutas.
- **`identity`:** el requisito de `spec.md`:540-553 («ausencia de prueba de extremo a extremo», con destino F1 o
  posterior) se reemplaza por e2e simulada aquí y brecha fechada de pila real en el cambio 11. El párrafo de
  `spec.md`:599-601 (emisor y etiqueta «por institución») se actualiza: emisor fijo «CONFIA» y etiqueta igual al
  identificador escrito, construidos en el navegador.

`web-edge` no cambia: ningún comportamiento del backend se modifica.

## Enfoque

1. **Primero la sonda, luego el diseño.** El paso 0 decide la API simulada; así no se diseña sobre un supuesto
   que la documentación de MSW ya pone en duda.
2. **La app nace bajo sus reglas.** Las reglas estáticas llegan antes que su primera línea de código.
3. **Tokens solo en memoria.** Un almacén privado de módulo, fuera de la caché de Query, del almacenamiento del
   navegador, de la URL y del estado del historial. Los metadatos de sesión sí pasan por Query.
4. **El CSRF como señal de sesión.** Se refresca solo si existe la cookie CSRF, porque cada refresco rechazado
   cuenta como fallo de la IP (30 en 10 minutos en `admin-session`), lo que es peligroso detrás de la NAT de una
   oficina.
5. **Cliente escrito a mano.** orval genera solo Zod, sin rutas ni cabeceras; el mutador con refresco, CSRF y token
   restringido habría que escribirlo igual.
6. **La puerta antes que las pantallas.** Su arnés entra con la página de arranque, y cada pantalla llega con sus
   filas en la matriz y su recorrido. Es un cambio respecto de la exploración, que los dejaba al final: evita un PR
   final imposible de revisar y no separa las pruebas del código que prueban.
7. **El secreto no sale del navegador.** La biblioteca de QR no hace red. El render usa SVG con atributos o
   imagen `data:`/`blob:`, ambos dentro de `img-src 'self' data: blob:` de `docs/03`:1232, y sin estilos en línea.
8. **TDD estricto.** Cada comportamiento con prueba ejecutable se escribe en rojo primero. El `test_command` de
   `openspec/config.yaml` es solo del backend, así que la fase de tareas fija el comando de frontend por PR.

## Áreas afectadas

| Área | Cambio |
|---|---|
| `apps/admin-web/` | Nueva: Vite, rutas, `features/identity/{sign-in,second-factor,enrollment,session}`, `shared/api`, catálogo `admin`, `e2e/` |
| `packages/config/` | `eslint.js` y su `.d.ts` con reglas separadas; nueva exportación `./a11y` con la política |
| `packages/ui/` | `FormErrorSummary`, `Alert`, W2, W3; `src/a11y/policy.ts` sale; `index.ts` y claves es-HN |
| `.dependency-cruiser.cjs`, `tooling/` | Reglas de app, fixtures, `dependency-rules.test.ts`, `suppression-citations.test.ts`, `ci-workflow.test.ts` |
| `.github/workflows/ci.yml`, `turbo.json`, `pnpm-workspace.yaml` | Job aditivo, tareas `test:e2e` y `dev`, catálogo |
| `openspec/specs/` (al archivar) | Dos capacidades nuevas; deltas de `design-system`, `build-integrity` e `identity` |
| Documentación | ADR nuevo e índice; nota en ADR-0026; `docs/09`; `docs/ui-ux/04`, `05`, `06`; `docs/01` §3; `CLAUDE.md` |
| `apps/api/` | Sin cambios |

## Riesgos

| Riesgo | Probabilidad | Mitigación |
|---|---|---|
| Cookies `Secure` y `__Host-` sobre `http://localhost`, o `Set-Cookie` no simulable | Media | Paso 0, con salida a HTTPS local y a un servidor Node simulado |
| **Desviación entre la API simulada y la real:** ningún flujo se prueba contra el backend hasta el cambio 11, y sin cuenta sembrada tampoco a mano | Alta | Respuestas simuladas validadas con los esquemas generados, códigos Problem según la spec y brecha fechada; 13b-1 no declara probada la pila real |
| Tamaño: las pruebas del cambio 8 superaron su pronóstico de 2 a 4 veces, y cinco PR se planean justo en 800 líneas | Alta | Rango realista abajo; un PR que supere el tope se parte antes de fusionarse; ver Q1 |
| Refrescos concurrentes o reintentos que revocan la familia de sesión | Media | Promesa compartida, Web Locks, sin reintento a ciegas, prueba con dos pestañas |
| Sondeos de refresco sin sesión que agotan el límite de la IP | Media | Sin cookie CSRF no hay llamada; una prueba lo afirma |
| Filtración del secreto TOTP, de códigos de recuperación o de tokens | Baja | Recorrido que revisa almacenamiento, URL, historial y peticiones con centinelas; regla 11 sobre registros |
| Recargar durante la inscripción pierde el token; al reintentar, el secreto se reemplaza y lo ya escaneado deja de valer | Media | Mensaje propio y escenario de spec; el backend permite reinscribir hasta la primera verificación |
| Retardos de hasta unos 30 s por fallos | Media | Sin tiempo límite menor en el cliente; tiempos de espera explícitos en Playwright |
| Presupuesto de CI: `frontend verify` tiene 10 minutos, ahora con `admin-web` y `ui` | Media | Se mide en el PR 2; el comando fijado [BX19] no cambia; el job nuevo es aparte |
| Mover la política rompe la puerta de `ui` o su panel de Storybook | Baja | PR propio con `gate.a11y.test.tsx` y `policy.test.ts` en verde; una sola fuente |
| Dependencias nuevas: regla de dos semanas, `pnpm audit` y Trivy; las versiones no se verificaron | Media | Se verifican en el PR 2; una bloqueada se reemplaza en el diseño |
| El *nonce* de la CSP para una SPA estática sigue sin resolver | Media | Cambio 11; este cambio garantiza solo un build compatible |

## Plan de reversión

No hay entorno desplegado ni datos reales, y este cambio no toca `apps/api` ni migraciones.

1. Cada PR se revierte con su commit de fusión, en orden inverso de la cadena. `apps/admin-web` es aditiva.
2. Revertir los PR 11 a 15 deja la aplicación sin alguna pantalla o sin puerta. **El criterio 8 se desmarca en
   `docs/09` con nota fechada** si la puerta o una pantalla evaluada se revierte.
3. Mover la política de vuelta a `packages/ui` revierte el PR 10; el ADR nuevo pasa a **Rechazado** y su número no
   se reutiliza. Como `docs/ui-ux/03` §11 exige la puerta, quitarla exige un ADR sucesor.
4. La separación de reglas de ESLint solo se revierte junto con la app, porque sin ella `admin-web` no pasa lint.
5. La nota de `docs/ui-ux/05` es fechada: revertirla no reescribe ningún texto original.

## Dependencias

- **Consume:** las operaciones de acceso del OpenAPI administrativo, los esquemas de `@confia/contracts/admin`, 19
  exportaciones de `@confia/ui`, el arnés de Chromium de 13a y `http://localhost:5173` ya permitido en
  `application-local.yml`:16.
- **Entrega a 13b-2:** la aplicación, el almacén de sesión, el coordinador de refresco, el cliente y la puerta sobre
  rutas.
- **Entrega al cambio 11:** el escenario de la e2e real, escrito como requisito diferido, y el job de Playwright.
- Node 24 y pnpm según el repositorio; Chromium en CI. Sin Docker ni JDK para este cambio.

## Criterios de éxito

- [ ] `pnpm turbo run lint typecheck test build --filter=!@confia/api` en verde, dentro del límite de tiempo.
- [ ] **Criterio 8:** la puerta sobre rutas no reporta violaciones críticas ni serias en arranque, inicio de sesión,
      verificación, inscripción y página mínima, en los dos temas; falla con cero rutas y con una ruta deliberadamente
      inaccesible; `docs/09` lo marca con su prueba.
- [ ] Recorridos simulados en verde: inicio con TOTP, con código de recuperación e inscripción obligatoria completa;
      recarga que conserva la sesión; cierre de sesión; sesión vencida; token restringido vencido.
- [ ] Sin cookie CSRF, un arranque no llama a `/refresh`; con dos pestañas, una sola petición de refresco a la vez.
- [ ] Tras cada recorrido, ningún token, secreto ni código aparece en `localStorage`, `sessionStorage`, IndexedDB, la
      URL ni `history.state`; el secreto y el URI `otpauth://` no aparecen en ninguna petición saliente.
- [ ] El URI lleva emisor «CONFIA» y la etiqueta codificada, y el QR se decodifica al mismo URI en una prueba.
- [ ] Ningún refresco se dispara por temporizador ni consulta de fondo.
- [ ] `fetch` fuera de `shared/api` y una importación de datos en presentación rompen la construcción, con su fixture.
- [ ] Ningún texto de interfaz embebido; textos del personal con «usted»; `title` y `detail` nunca se muestran.
- [ ] El build no contiene el service worker de MSW ni scripts en línea.
- [ ] Ningún archivo de `apps/api` cambió. Ningún PR supera 800 líneas efectivas.
- [ ] El ADR nuevo existe, ADR-0026 conserva su texto con una nota, y la brecha de la e2e real está fechada y asignada
      al cambio 11.

## Pronóstico de tamaño y entrega

Líneas escritas con pruebas, sin lockfile ni código generado. Ninguna cifra es una medición.

- **Optimista (no se usa para planear):** de 3 700 a 5 100, tomando de la exploración las rebanadas 1, 2, 3, 5, 6, 7
  y 9, y parte de la 4, sin `Dialog` ni shell.
- **Realista (el que manda):** **de 6 300 a 10 050**, la suma de la tabla. Es del orden de 1,7 a 2 veces el optimista.
- **Si se repite lo peor del cambio 8:** con unas pruebas al 60 % del total que crezcan 4 veces, se llegaría a unas
  14 000 líneas, que esta cadena no absorbe. Esa es la razón de Q1.

Supera con claridad el tope de 800 líneas de `docs/15` §3, así que se propone una **cadena de 15 PR apilados**, con
base sucesiva y fusión en orden hacia `main`. Las pruebas nunca se separan del código que prueban.

| PR | Rebanada | Contenido | Líneas realistas |
|---|---|---|---|
| — | Paso 0 | Sonda de cookies y de simulación; sin PR | — |
| 1 | `app-static-rules` | Reglas de ESLint separadas, dependency-cruiser, fixtures, escáner sobre `apps/` | 500 a 800 |
| 2 | `admin-web-package-and-router` | Paquete, Vite, proxy, rutas por código, humo, tareas de turbo, medición de CI | 450 a 750 |
| 3 | `providers-i18n-and-voice` | Proveedores, catálogo `admin`, prueba de claves, tema del sistema, nota de `docs/ui-ux/05` | 350 a 600 |
| 4 | `api-transport-and-problem-map` | Transporte, cabeceras, CSRF, parser de Problem, mapa de códigos | 450 a 750 |
| 5 | `auth-operations-and-contract-mocks` | Operaciones de acceso con Zod y simulaciones de contrato | 350 a 600 |
| 6 | `session-basics` | Almacén en memoria, coordinador con Web Locks, restauración, cierre, vencimiento | 550 a 800 |
| 7 | `ui-alert-and-fixes` | `Alert`, W2, W3, spec | 350 a 500 |
| 8 | `ui-form-error-summary` | `FormErrorSummary`, historias, claves, revisión de `ui.json` | 300 a 450 |
| 9 | `boot-and-signed-in-page` | Pantalla de arranque, página mínima, cierre de sesión, guardas | 350 a 600 |
| 10 | `a11y-policy-move-and-adr` | Política hacia `@confia/config`, ADR nuevo, nota en ADR-0026, `CLAUDE.md`, `docs/01` | 250 a 400 |
| 11 | `route-gate-simulated-api-and-ci` | Playwright, API simulada, matriz sobre el arranque y la página mínima, ruta inaccesible de prueba, job de CI | 500 a 800 |
| 12 | `sign-in-screen` | Inicio de sesión con todos sus estados, «mostrar contraseña», contacto, axe y recorrido | 550 a 800 |
| 13 | `second-factor-screen` | Verificación con TOTP y recuperación, axe y recorrido | 500 a 750 |
| 14 | `enrollment-secret-and-qr` | Inscripción, URI, QR, clave en grupos, prueba de decodificación, axe | 400 a 650 |
| 15 | `enrollment-activation-and-criterion-8` | Códigos de recuperación, primer código, recorrido completo, centinelas, `docs/09`, brecha fechada | 450 a 800 |

**Órdenes duros:**

- El paso 0 antes de cerrar el diseño y de todo PR.
- 1 antes de 2: la app nace bajo sus reglas.
- 2 → 3 → 4 → 5 → 6: cada uno usa el anterior.
- 7 antes de 9: el arranque necesita W3.
- 3 y 6 antes de 9.
- 10 antes de 11: el ADR y la política preceden a la puerta.
- 9 antes de 11: la puerta necesita rutas reales y falla con cero.
- 7, 8 y 11 antes de 12: toda pantalla nace bajo la puerta.
- 12 → 13 → 14 → 15: cada pantalla necesita el token de la anterior.
- El criterio 8 se marca solo en el PR 15.

**Tope de 15 tareas de `openspec/config.yaml`: cabe justo, sin ningún margen.** Son 15 tareas si la relación es de una
por PR y el paso 0 se ejecuta antes de `tasks.md`. Cinco PR ya se planean al tope, y partir cualquiera excede el
límite, lo que según esa regla obliga a dividir el cambio. El punto de corte natural es después del PR 11; ver Q1.

## Preguntas abiertas para el propietario

- **Q1, resuelta por el propietario el 2026-11-05: cortar después del PR 11b.** La sección «Actualización del 2026-11-05» dice su efecto. Texto original de la pregunta: la división de P7 prometió que 13b-1 cierra el criterio 8,
  pero un corte tras el PR 11 lo llevaría a una segunda parte, porque la puerta ya correría sin las tres pantallas. Es
  una decisión sobre entrega y sobre cuándo se cierra el criterio, y no se toma aquí. Este cambio puede arrancar sin ella;
  se vuelve bloqueante en la fase de tareas, si el conteo pasa de 15.
- **Q2, resuelta por el propietario el 2026-11-04.** La cuenta del personal para la e2e real la crea el **cambio 8b**, con el mismo mecanismo que usa para el primer Super Admin; el cambio 11 solo la usa. Hasta entonces nadie puede probar este inicio de sesión a mano contra el backend.
