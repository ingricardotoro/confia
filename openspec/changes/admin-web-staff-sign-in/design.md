# Diseño: pantallas de acceso del personal (cambio 13b-1 de F0, parte 2 de 2)

- **Cambio:** `admin-web-staff-sign-in`
- **Fase:** diseñar
- **Fecha:** 2026-11-07
- **Estado:** aprobado por el propietario el 2026-11-07, con los textos de la sección 8 y las decisiones de la sección 14.
- **Nota fechada 2026-11-07 (decisiones del propietario).** El propietario resolvió tres preguntas de la primera versión: el QR solo usa
  `QRCodeSVG` (E11), la entrega es de 9 PR con cortes predefinidos hasta 15 y excepciones para excesos pequeños (sección 12), y SS53 usa un nombre
  fijo con `aria-pressed` (E8). Las secciones afectadas conservan el texto anterior en notas fechadas. Los deltas y la propuesta recibieron notas
  fechadas equivalentes.
- **Entradas:** `proposal.md`; los deltas de `specs/` (SS01-SS05, SS28, SS29, SS31, SS37-SS102, SS113-SS117, SS123, SS127; AW09, AW49, AW51,
  AW54, AW64; BX102-BX105, BX108, BX109; I304, I305, I307-I309, I311); `admin-web-foundations-and-session/{proposal,design,tasks,apply-progress}.md`
  (D1-D19, P1-P10, Q1, Q2 y el orden aprobado el 2026-11-05, Engram 817); `docs/ui-ux/03`, `04`, `05`, `06`; `docs/13-metodologia-sdd.md`;
  `CLAUDE.md`. Código de la parte 1 leído en el árbol `C:\dev\confia-frontend`, rama `change/admin-web-foundations-and-session-9b-signed-in-page`
  (sin fusionar). Las rutas de este documento son relativas a `apps/admin-web/` salvo que se diga otra cosa.
- **Relación con el diseño de la parte 1.** La propuesta dice que este cambio no tendría diseño propio y reutilizaría D8, D10 a D12 y D15. Este
  documento **conserva** esas decisiones y solo las concreta o las corrige donde el código real de la parte 1 lo exige (E1, E2, E6, E9). Lo que no se
  menciona aquí sigue como lo fija el diseño de la parte 1.
- **Modo de TDD:** estricto, con los comandos por superficie de la parte 1 (**U**, **E**, **T**, **UI**, **PR**).
- **Dinero:** este cambio no muestra importes.
- **Limitación de esta fase:** sin consola ni red. Ninguna versión se consultó en un registro y ninguna medida se tomó; lo que depende de ello queda
  como la primera prueba en rojo de su PR.

## 1. Enfoque técnico

Tres pantallas reemplazan los contenedores provisionales de `src/features/identity/routes.tsx:51-67`, que hoy renderizan `AccessPlaceholder`. Cada
pantalla es un contenedor que habla con `AccessSession` y una presentación que solo recibe propiedades. Los formularios comparten un único gancho,
`shared/forms/use-access-form.ts`, que es el único archivo que importa `@tanstack/react-form` (D10) y que concentra el comportamiento común de
espera, error y foco del primer requisito de `staff-sign-in`.

Invariantes que se conservan de la parte 1 y que esta parte no puede romper:

1. Ningún contenedor toca un token: `AccessSession` devuelve desenlaces sin secretos de sesión.
2. Las escrituras de acceso no pasan por `useMutation` (D9): la contraseña, el código y el secreto nunca llegan a la caché de mutaciones.
3. El estado de la inscripción (identificador, secreto, códigos) vive en memoria de módulo, en un almacén privado, y se borra por cada camino
   que termina el segundo factor.
4. La interfaz nunca muestra `title` ni `detail`, y todo texto sale del catálogo `admin` en «usted».

```
 routes.tsx (guardas, sin cambios de forma)
   │
   ├─ /iniciar-sesion ──────────► SignInContainer ──► SignInForm, PasswordField, ProblemAlert
   ├─ /iniciar-sesion/verificacion ► SecondFactorContainer ─► SecondFactorForm, ProblemAlert
   └─ /iniciar-sesion/inscripcion ► EnrollmentContainer ─► SecretStep, RecoveryCodesStep, FirstCodeStep, TotpQrCode
                 │                         │
                 ▼                         ▼
       shared/forms/use-access-form   AccessSession (session/) ── token-store, enrollment-draft (nuevo)
       shared/forms/use-retry-countdown         │
       shared/forms/access-failure              ▼
                 │                    shared/api/auth-operations (sin cambios)
                 └──── shared/api/problem-keys (sin cambios de forma)
```

## 2. Estado de la parte 1 que condiciona el diseño

| Hallazgo | Fuente | Consecuencia |
|---|---|---|
| `verify()` solo usa el token de uso `mfa-verify` | `src/features/identity/session/access-session.ts:188-196` | El primer código de la inscripción, que va con el token `mfa-enroll`, recibiría `no-token`. **E2** agrega `activate(code)` |
| `signIn()` no guarda el identificador; la desviación 4 de 6b dejó esa conexión al contenedor de la parte 2 | `access-session.ts:177-186`; `apply-progress.md`, tarea 6b, desviación 4 | **E2** la resuelve dentro del módulo de sesión |
| `enroll()` no está memorizado y no guarda nada | `access-session.ts:197-204` | SS74 y SS75 exigen una sola petición por entrada: **E2** |
| `discardSecondFactor` solo termina la sesión local si no hay token de acceso; `endLocally` vacía el almacén y fija el aviso | `access-session.ts:107-111`, `:205-207` | Es el único punto por el que pasa todo descarte: el borrado de la inscripción se cuelga ahí |
| El aviso de llegada vive en `SessionView` (`anonymous` con `notice`) y una recarga lo pierde | `access-session.ts:23-27`; `routes.tsx:51-57` | SS60 se cumple sin cambiar la sesión; **E7** solo decide cuándo se oculta |
| `BootGate` no llama a `boot()` sin cookie CSRF; la vista queda en `restoring` | `apply-progress.md`, tarea 9a, desviación 1 | Las guardas comparan solo con `authenticated`; las pantallas nuevas no deben tratar `restoring` como bloqueo |
| La guarda de la raíz descarta el segundo factor solo si hay token restringido | `routes.tsx:31-37` | «Volver al inicio de sesión» (SS73) y el regreso del navegador (SS31, SS93, SS98) se resuelven navegando: no hace falta código propio de descarte |
| `PROBLEM_KEYS` lleva `token-*` a `notice.verificationInterrupted` | `src/shared/api/problem-keys.ts:22-23` | La inscripción elige `notice.enrollmentInterrupted` por su cuenta (E5) |
| `Button` con `loading` pone `aria-busy`, muestra `Spinner` y cancela el clic | `packages/ui/src/atoms/button/Button.tsx:60-77` | SS39 sin código nuevo; SS40 necesita además un guardia por `ref` para Intro (E3) |
| `FormErrorSummary` recibe `{ controlId, message }[]`, es `role="group"` con `tabIndex={-1}`, y un error sin control se muestra como texto | `packages/ui/src/molecules/form-error-summary/FormErrorSummary.tsx:36-86` (tarea 8, en curso) | AW51: un campo ajeno usa un `controlId` que no existe en el documento; la clave de React es el `controlId`, así que debe ser único |
| `FormField` genera su id con `useId`; `controlId` llega con DS98 en la tarea 8 | `packages/ui/src/molecules/form-field/FormField.tsx:56-58` | El formulario fija el `controlId` de cada campo para enlazar el resumen |
| `IconButton` exige `label` y reenvía el resto de propiedades a `Button` | `packages/ui/src/atoms/icon-button/IconButton.tsx:11-27` | `aria-pressed` pasa sin cambio de `ui` (P10) |
| Los esquemas generados exponen los topes: identificador 320, contraseña 1 024, `code` y `recoveryCode` 64, todos con `min(0)` | `packages/contracts/src/generated/admin/schemas.ts:47-56`, `:113-123` | E4: se agrega `.min(1)` y el patrón sobre el esquema del contrato |
| El simulador no expone la clave ni los códigos de la cuenta inscrita; `enroll` devuelve siempre el mismo secreto | `e2e/mock-api/server.ts:191-212`; `e2e/mock-api/session-model.ts:110-112`, `:222-228` | E13: un control de prueba nuevo y un secreto nuevo por inscripción (SS100) |
| Faltan escenarios del simulador para `400` con `errors`, `500` con `traceId` y token vencido en la inscripción | `e2e/mock-api/scenarios.ts:17-50` | E13 |
| La puerta, `playwright.config.ts` y `e2e/specs/` aún no existen en el árbol leído | Ausentes en `e2e/` | **Dependencia dura:** la tarea 12 (11b) de la parte 1 debe estar fusionada antes del primer PR con recorridos |
| `presentation-states.test.ts` exige una prueba con comentario `// States.` y `AW57` por cada archivo de `components/` | `src/app/presentation-states.test.ts:17-33` | Cada presentación nueva lleva su prueba con esa declaración |
| `lucide-react` 1.57.0 y `zod` 4.6.6 ya están en el catálogo; `qrcode.react` y `jsqr` no | `pnpm-workspace.yaml:64`, `:80` | Sección 9 |

## 3. Decisiones de arquitectura

### E1. Un contenedor y una presentación por pantalla, en la carpeta de su capacidad

**Elección.** La estructura que fijó D1, sin cambios de nombre:

```
src/features/identity/
├── routes.tsx                         modifica: importa los tres contenedores reales; borra los provisionales
├── components/                        ProblemAlert, CopyButton (nuevos); AccessPlaceholder (se borra con su prueba)
├── sign-in/        SignInContainer.tsx, sign-in-schema.ts, components/{SignInForm,PasswordField}.tsx
├── second-factor/  SecondFactorContainer.tsx, second-factor-schema.ts, components/SecondFactorForm.tsx
├── enrollment/     EnrollmentContainer.tsx, otpauth-uri.ts, manual-key.ts,
│                   components/{SecretStep,RecoveryCodesStep,FirstCodeStep,TotpQrCode}.tsx
└── session/        enrollment-draft.ts (nuevo), access-session.ts (modifica)
src/shared/forms/   use-access-form.ts, access-failure.ts, field-errors.ts, use-retry-countdown.ts
```

Los contenedores reciben `session` y las rutas de destino por propiedad, igual que `SignedInContainer` (`signed-in/SignedInContainer.tsx:13-17`),
para que ninguno importe `routes.tsx`. Las presentaciones no importan Query, Router, Form ni `shared/api` (regla `admin-presentation-is-pure`),
reciben textos ya traducidos y exponen sus estados: inicial, enviando, con errores de campo, con error general y con aviso (inicio de sesión y
verificación); cargando, error, secreto, códigos y primer código (inscripción). El estado «vacío» no aplica a ninguna: lo declara su prueba (AW58).

**Alternativas descartadas.** Un componente por pantalla que mezcle datos y presentación (rompe `admin-presentation-is-pure` y la prueba de
estados); una ruta por paso de la inscripción (D11 ya la descartó: abriría regresos hacia los códigos).

**Trazabilidad.** SS52, SS55, SS61, SS64, SS89; AW57 y AW58 de la parte 1 siguen vigentes sobre los archivos nuevos.

### E2. El estado de la inscripción vive en el módulo de sesión, no en el contenedor

**Elección.** Un almacén nuevo, `session/enrollment-draft.ts`, privado del módulo de sesión como `token-store.ts`, y cuatro operaciones nuevas
de `AccessSession` (sección 6):

- `signIn` guarda en el borrador el identificador **que envió**, solo cuando la respuesta es `second-factor-enrollment-required` (SS58, SS59).
- `startEnrollment()` reemplaza a `enroll()`. Memoriza su promesa por token restringido: el montaje doble de React y una segunda llamada reciben
  la misma (SS74, SS75). Un fallo de red, `429`, `503` o inesperado **olvida** la promesa sin descartar el token, de modo que solo el reintento de
  la persona emite la petición siguiente (SS87). El `201` guarda secreto y códigos en el borrador.
- `advanceEnrollment()` y `reviewEnrollmentSecret()` mueven el paso (`secret` → `recovery-codes` → `first-code`, y de `first-code` a `secret`).
  Al salir de `recovery-codes`, los códigos se sobrescriben con `undefined`: no hay operación que los devuelva (SS93).
- `activate(code)` envía el primer código **solo como TOTP** con el token `mfa-enroll`. El tipo acepta una cadena, no un `SecondFactorProof`, así
  que un código de recuperación no se puede enviar por construcción (SS72). El `200` acepta el token de acceso y borra el borrador (SS96).
- `endLocally`, y por tanto `discardSecondFactor` y toda terminación local, borra también el borrador (SS86, SS98). Una verificación correcta lo
  borra igual.

`getEnrollment()` devuelve una instantánea inmutable y notifica por el mismo `subscribe` de la vista, para que el contenedor la lea con
`useSyncExternalStore`, como `useSessionView`.

**Corrige la desviación 4 de la tarea 6b**, que dejaba el identificador al contenedor. Razón: el contenedor del inicio de sesión se desmonta al
navegar, y el borrado tendría que coordinarse entre tres contenedores y la guarda de la raíz; con el borrador dentro de la sesión, un único
`endLocally` cubre todos los caminos y la regla `admin-token-store-is-private` lo protege sin regla nueva (se extiende a `enrollment-draft`).
`token-store.ts` sigue sin entrada para el identificador: su prueba de SS59 de la tarea 6a no cambia.

**Alternativas descartadas.** (a) Almacén en `enrollment/` suscrito a la vista: dos fuentes de verdad y una carrera entre el descarte y la
suscripción. (b) Estado de React en el contenedor: el montaje doble y la recarga lo duplicarían o lo perderían, y SS75 dependería de la limpieza
del efecto, que D11 descartó. (c) Pasar el borrador por Query: lo prohíbe el requisito de memoria de módulo (SS03).

**Costo.** `access-session.ts` crece unas 70 líneas y su prueba unas 150; cambia la firma pública (`enroll` sale).

**Trazabilidad.** SS58, SS59, SS71-SS75, SS86, SS87, SS93, SS96, SS98, SS99, SS100; requisito modificado de memoria de módulo (SS01-SS05).

### E3. `use-access-form`: TanStack Form detrás de un solo gancho, con validación al enviar y al salir del campo

**Elección.** `useAccessForm({ fields, validate, submit })`:

- `fields` declara, por campo, su `controlId` fijo, su valor inicial y su normalización (E4).
- `validate(values)` aplica la normalización y el `safeParse` del esquema de la pantalla, y devuelve errores por campo como **razones**
  (`required`, `too-long`, `format`), no como textos.
- `submit(normalized)` llama a la operación de `AccessSession` y devuelve un `AccessFailure` (E5) o nada.
- Expone a la presentación solo valores, manejadores, errores ya traducidos por campo, la lista del resumen, el `Alert` general, `submitting`, y
  dos `ref` (resumen y `Alert`).

Comportamiento:

| Regla | Mecanismo | Escenarios |
|---|---|---|
| Validar antes de enviar, sin petición si hay errores | Validador `onSubmit` del formulario; `submit` no se llama | SS37, SS63, SS66 |
| Validar al salir del campo y revalidar en cada tecla un campo ya marcado | Validador `onBlur` y `onChange` solo si el campo tiene error (`docs/ui-ux/04:392`) | — |
| Botón siempre habilitado, `aria-busy`, `Spinner` | `Button loading={submitting}` | SS39 |
| Sin segunda petición en vuelo | Un `ref` de envío en vuelo, consultado también por el envío con Intro | SS40 |
| Foco tras fallar | Un contador de fallos en un efecto enfoca el resumen si hay errores de campo, o el `Alert` si no | SS37, SS41, SS42 |
| Espera larga | Región `polite` propia del formulario que muestra el texto de espera al empezar y se vacía al terminar; sin tiempo límite propio (el del transporte es de 60 s) | SS48 |
| Pegado y topes | `noValidate` en el `form`, sin `maxLength` ni `pattern` en los controles | SS51 |
| Conservar lo escrito tras un error | El formulario no se reinicia ante un fallo; se reinicia al desmontar | SS49, SS56 |

La validación al salir del campo no aparece en la especificación, que solo exige validar al enviar; se agrega porque `docs/ui-ux/04:392` la fija
como regla dura de todo formulario. El resumen solo aparece tras un envío fallido; un error de salida de campo se muestra bajo el campo.

**Nota fechada 2026-11-07 (decisión del propietario).** Tras un envío fallido, el foco va al resumen, como exigen SS37 y `docs/ui-ux/03:141`, y no al
primer campo con error ni a un resumen con `role="alert"`, como dice `docs/ui-ux/04:393-394`. Ganan la especificación y `docs/ui-ux/03`. La nota
fechada en `docs/ui-ux/04` la agrega el PR 2, que implementa este gancho, no esta fase; el delta `staff-sign-in` registra la decisión junto a SS51.

**Alternativas descartadas.** El adaptador de Standard Schema de TanStack Form con mensajes de Zod: los mensajes de Zod no son claves del catálogo y
la traducción tendría que adivinar por texto. Estado de React a mano: duplicaría lo que D10 asignó a Form y rompería el objetivo de que sustituir
Form cambie un archivo.

**Trazabilidad.** Requisito «Todo formulario de acceso se envía con el mismo comportamiento de espera, error y foco» (SS37-SS51).

### E4. Esquemas derivados del contrato, con normalización explícita antes de validar

**Elección.** Cada `*-schema.ts` deriva del esquema generado y solo **agrega** restricciones; ninguno declara un campo desde cero:

```ts
// sign-in/sign-in-schema.ts
const identifier = LoginRequest.shape.identifier.min(1);          // 0..320 del contrato, más min(1)
const password = LoginRequest.shape.password.min(1);              // 0..1024, más min(1)
export const normalizeIdentifier = (raw: string) => raw.trim();   // solo extremos; ni NFKC ni minúsculas (D12)

// second-factor/second-factor-schema.ts
const code = SecondFactorVerificationRequest.shape.code.unwrap().regex(/^\d{6}$/);
const recoveryCode = SecondFactorVerificationRequest.shape.recoveryCode.unwrap().min(1);   // 1..64
const SPACES = /[\s\u200B-\u200D\u2060\uFEFF]/g;
export const normalizeCode = (raw: string) => raw.replace(SPACES, "");
export const normalizeRecoveryCode = (raw: string) => raw.replace(SPACES, "").toUpperCase();
```

La normalización es una función pura, separada del esquema, aplicada **antes** de validar y **antes** de enviar: el valor validado, el enviado y el
guardado para el URI salen del mismo resultado (SS58, SS62). La contraseña no se transforma. Ningún archivo de la app importa `zod`: solo se
invocan métodos de los esquemas importados de `@confia/contracts/admin`. **Supuesto por verificar en rojo:** que `unwrap()` y `regex()` de Zod 4.6.6
se comporten así sobre los esquemas generados; si exigiera importar `zod`, se declara `zod: catalog:` en la app, sin versión nueva.

La casilla de los códigos de recuperación no tiene campo de contrato y no usa esquema: su comprobación es un booleano en el contenedor (E10).

**Alternativas descartadas.** `.trim()` de Zod dentro del esquema: corre después del `max` del contrato y mezcla normalizar con validar.
`z.preprocess` o `transform`: el resultado de salida dejaría de ser una cadena del tipo del contrato.

**Trazabilidad.** SS37, SS51, SS58, SS62, SS63, SS65, SS66, SS79.

### E5. Un clasificador de fallos compartido y la decisión propia de cada pantalla

**Elección.** `shared/forms/access-failure.ts` convierte un `ApiFailureResult` en una de cinco formas, sin textos:

```ts
export type AccessFailure =
  | { kind: "fields"; errors: readonly { field: string; reason: string }[] }        // 400 con errors
  | { kind: "rate-limited"; retryAfterSeconds: number }                            // 429
  | { kind: "message"; key: AdminKey; traceId?: string }                           // 401 de credencial, 503, 400 sin errors, red
  | { kind: "unexpected"; traceId?: string }                                        // 500, desconocido, fuera de esquema (AW64)
  | { kind: "restricted-token-ended" };                                             // 401 token-expired o token-invalid
```

Cada contenedor **primero** decide lo propio de su pantalla y luego delega en `useAccessForm`:

| Pantalla | Decisión propia antes del caso general |
|---|---|
| Inicio de sesión | Ninguna: `authentication-failed` usa `problem.authenticationFailed`, igual exista o no la cuenta (SS56) |
| Verificación | `second-factor-invalid`: clave según el modo (`problem.secondFactorInvalid` o `problem.recoveryInvalid`) y se queda en el modo (SS67, SS68). `restricted-token-ended` o `no-token`: `discardSecondFactor("second-factor-interrupted")` y navegar al inicio de sesión (SS69, SS70) |
| Primer código | `second-factor-invalid`: texto fijado del TOTP; QR y clave siguen visibles (SS97). Token terminado: aviso de inscripción interrumpida |
| Inscripción (petición) | `409 mfa-already-enrolled`: aviso `second-factor-already-enrolled` (SS86). Token terminado: `enrollment-interrupted` (SS88). Todo lo demás: `ErrorState` con reintento (SS87), y con identificador de traza si lo hay |

`field-errors.ts` traduce razón a clave, por campo: `not-blank` y la razón local `required` → «obligatorio»; `size`, `max` y `too-long` →
«demasiado largo»; `format` → formato; cualquier otra razón → el texto genérico del campo (AW51). Un campo que el formulario no tiene entra al
resumen como texto con `controlId` `unknown-field-<n>`, que no existe en el documento (AW51, DS80). Ningún mensaje incluye el valor rechazado
(SS41), porque todos son claves del catálogo.

`ProblemAlert` (presentación compartida) es un `Alert` `destructive` con el mensaje y, si llega `traceId`, el identificador visible y un
`CopyButton` (SS50, AW54). Los `Alert` de error son `destructive`, con `role="alert"` (SS42); el de servicio ocupado y el de red también, porque
la persona disparó la acción (`docs/ui-ux/04` §7).

**Alternativa descartada.** Extender `PROBLEM_KEYS` con claves por pantalla: el mapa es por código y no sabe en qué pantalla está.

**Trazabilidad.** SS41, SS42, SS47, SS49, SS50, SS56, SS67-SS70, SS86-SS88, SS97; AW49, AW51, AW54, AW64.

### E6. Cuenta regresiva del `429` sin anuncios por segundo

**Elección.** `shared/forms/use-retry-countdown.ts`: fija el instante límite (`now + segundos`), con `Retry-After` ya acotado por el analizador
(60 por defecto, tope de 3 600; D7). Un intervalo de 1 s existe **solo** mientras dura la espera y se limpia al terminar y al desmontar.

- El `Alert` tiene texto fijo (`problem.rateLimited`, SS43) y no cambia.
- El número restante (`countdown.remaining`) se muestra **fuera** de toda región activa.
- Al terminar, una región `polite` del formulario muestra `countdown.done` (SS44).
- Un envío durante la espera no llama a `submit`, no reinicia la cuenta y devuelve el foco al `Alert` (SS45). El botón no se deshabilita.

La región de espera de E3 se vacía al llegar la respuesta, en el mismo render que aparece el `Alert`: para el observador de SS44 eso cuenta como el
cambio «al aparecer el aviso». **Riesgo R4:** si el observador contara esas dos mutaciones por separado, la región de espera se vacía antes, al
resolver la promesa; se decide con la prueba en rojo.

**Alternativa descartada.** Anunciar el número con `aria-live="off"` dentro del `Alert`: el `Alert` destructivo es `role="alert"` y cualquier
cambio de su texto se anunciaría.

**Trazabilidad.** SS43-SS46.

### E7. Avisos de llegada: se leen de la vista y se ocultan al primer envío

**Elección.** `SignInContainer` lee `view.notice` cuando la vista es `anonymous` (como el provisional de `routes.tsx:51-57`) y lo pasa a la
presentación, que lo muestra en un `Alert` `info` (o `warning` para `second-factor-already-enrolled`), con `role="status"`, después del `h1` y sin
moverle el foco (SS60). El contenedor lo oculta al empezar el primer envío, con estado local, para que el aviso de una verificación interrumpida no
conviva con el error de un intento nuevo. No se agrega nada a la URL ni a `history.state`; una recarga lo pierde porque la vista vive en memoria.

El aviso de inscripción interrumpida ya dice que el QR y los códigos anteriores no sirven y que se generarán nuevos
(`admin.json`, `notice.enrollmentInterrupted`), lo que cumple SS29 y la frase final del requisito del primer código.

**Trazabilidad.** SS28, SS29, SS31, SS60.

### E8. Inicio de sesión: campos, «mostrar contraseña» y texto de contacto

**Elección.**

- Identificador: `Input` de `type="text"`, `inputMode="email"`, `autoComplete="username"`, `autoCapitalize="none"`, `spellCheck={false}`.
  `type="email"` se descarta porque el navegador puede alterar o rechazar un valor que el backend acepta y que debe llegar idéntico al URI (SS58).
- Contraseña: `PasswordField` en `sign-in/components/`, que compone `Input` e `IconButton` (P10), con `autoComplete="current-password"`. Su estado
  `visible` es local y empieza en `false` en cada montaje, así que salir y volver lo oculta (SS54). El `IconButton` lleva el nombre fijo
  `signIn.showPassword` («Mostrar contraseña»), `aria-pressed` con el estado, e icono `Eye` o `EyeOff` de `lucide-react` (decorativo). Cambiar de
  estado no desmonta el botón, así que el foco y el valor se conservan (SS53).
- Ambos campos con `required` de `FormField`, que marca en texto (SS52).
- Contacto: un párrafo con `signIn.contact`, sin enlace, ruta ni operación (P3, SS55).
- Tras el `200`: `authenticated` → `navigate({ to: "/", replace: true })`; `second-factor-required` → verificación; enrollment → inscripción.
  Las dos últimas **apilan** una entrada (sin `replace`): SS31 necesita regresar y volver a avanzar. Solo la llegada a `/` reemplaza (SS99, SS101).

**Nota fechada 2026-11-07 (decisión del propietario sobre SS53).** La primera versión de este diseño seguía la especificación de entonces, que
exigía a la vez `aria-pressed` y un nombre que cambiaba entre `signIn.showPassword` y `signIn.hidePassword`, y dejaba la pregunta abierta. El
propietario enmendó el requisito y SS53: nombre fijo «Mostrar contraseña» y el estado solo en `aria-pressed`. La clave `signIn.hidePassword` sale de
la sección 8. La demostración deliberada propuesta es dar al control un nombre que cambie, que pone en rojo SS53.

**Trazabilidad.** SS52-SS58, SS101; AW49.

### E9. Verificación: un modo a la vez, con el foco en el campo nuevo al cambiar

**Elección.** `SecondFactorContainer` mantiene `mode: "code" | "recovery-code"`. El formulario se monta con `key={mode}`, así que cambiar de modo
reinicia valores y errores y nunca envía los dos campos (SS65). El foco va al campo nuevo solo cuando el cambio lo hizo la persona; al entrar a la
pantalla el foco sigue en el `h1` (D5, SS32). El campo TOTP lleva `inputMode="numeric"` y `autoComplete="one-time-code"` (SS61); el de recuperación,
`autoComplete="off"` y `autoCapitalize="characters"`. «Volver al inicio de sesión» navega sin aviso; la guarda de la raíz descarta el token
(`routes.tsx:31-37`, SS73). El `200` navega a `/` con reemplazo (SS71).

**Trazabilidad.** SS61-SS71, SS73, SS113, SS115; I304.

### E10. Inscripción en tres secciones de una ruta, solo hacia adelante

**Elección.** `EnrollmentContainer` llama a `startEnrollment()` en un efecto (memorizado, E2) y renderiza según la instantánea:

```
 cargando (Spinner + región polite, sin secreto; SS76)
    │ 201
    ▼
 secret: TotpQrCode + clave en grupos + CopyButton + «Continuar»
    │ advance
    ▼
 recovery-codes: lista de diez códigos como texto + casilla + «Continuar» (nunca deshabilitado)
    │ casilla marcada → advance: los códigos se olvidan (SS93)
    ▼
 first-code: TotpQrCode + clave + campo del código + «Activar» + «Ver de nuevo el código QR» (vuelve a secret)
    │ activate 200 → navigate("/", replace)
```

- Cada sección enfoca su `h2` al aparecer (D11). Los pasos no crean entradas de historial.
- La casilla usa `Checkbox` dentro de `FormField` con `controlId` fijo. Continuar sin marcarla muestra `FormErrorSummary` con un enlace a la casilla
  y le pasa el foco (SS90). El paso no ofrece descargar, imprimir ni enviar (SS95).
- El campo del primer código no se monta hasta `first-code` (SS92). El paso reutiliza el esquema TOTP de E4 y `use-access-form`.
- Desde `first-code`, «Ver de nuevo el código QR» vuelve a `secret`, cuyo «Continuar» salta a `first-code` porque ya no hay códigos (D11). El
  regreso del navegador sale de la ruta, la guarda de la raíz descarta todo, y volver a avanzar lleva al inicio de sesión con el aviso de inscripción
  interrumpida (SS93, SS98).
- `manual-key.ts` agrupa el secreto en bloques de 4 con un espacio (SS77); el `CopyButton` copia el secreto sin espacios con
  `navigator.clipboard.writeText` y anuncia `copy.keyDone` en una región `polite`, o `copy.failed` si el portapapeles rechaza (SS78).

**Trazabilidad.** SS74-SS78, SS87-SS100, SS114.

### E11. QR con `qrcode.react` como única vía

**Elección.** `TotpQrCode` renderiza `QRCodeSVG` de `qrcode.react` con `value={uri}`, `level="M"`, `marginSize={4}`, `role="img"` y `title` del
catálogo (`enrollment.qrLabel`), sin `style`, sin `imageSettings` y con los colores por defecto de la biblioteca (fondo blanco, módulos negros), de
modo que el código no escribe ningún literal visual y el contraste es el mismo en ambos temas (SS83, SS84). El fondo del SVG cubre la zona de silencio.
`otpauth-uri.ts` es la función de D11, sin cambios.

**Resolución de la contradicción.** El cuerpo del requisito dice que el QR **DEBE** generarse con `QRCodeSVG` de `qrcode.react`, mientras que los
«valores fijados» del mismo delta dan por decidida una salida con `qrcode` y `QRCode.create()` si SS83 falla. Las dos cosas no pueden ser ciertas a la
vez: aplicar la salida incumpliría un DEBE. Este diseño **no** aplica la salida por su cuenta: SS83 corre en rojo antes de escribir `TotpQrCode`, y
si `QRCodeSVG` no la cumple, el PR se detiene y la decisión vuelve al propietario con la evidencia. La pregunta 1 de la sección 14 le pide decidir
ahora si se borra la salida del delta o se reescribe el requisito para admitirla.

Además de la contradicción formal, la salida tiene costo propio: `qrcode` arrastra dependencias de ejecución que `qrcode.react` no tiene (tabla de D11),
y el `<path>` propio es código nuestro cuyo defecto bloquearía cuentas.

**Nota fechada 2026-11-07 (decisión del propietario).** La pregunta 1 está resuelta: se retiró la salida con `qrcode` y `QRCode.create()` del bloque
de valores fijados del delta `staff-sign-in`, con su propia nota fechada. `QRCodeSVG` de `qrcode.react` es la única vía. El PR 7 escribe SS83 en rojo
antes que `TotpQrCode`; si `QRCodeSVG` no la cumple, el PR se detiene sin cambiar de biblioteca y el propietario decide entonces con la evidencia. El
diseño no prepara ninguna alternativa ni agrega `qrcode` a las dependencias.

**Lo que sigue sin verificar** (conocimiento previo, no consulta): que `QRCodeSVG` reenvíe `role` al `svg`, que `title` produzca un `<title>` como
nombre accesible y que no escriba `style`. Son exactamente las aserciones de SS83.

**Trazabilidad.** SS79-SS85; I311.

### E12. Textos nuevos del catálogo `admin`, en «usted»

**Elección.** Las claves de la sección 8. Pasan por la prueba de voz de D17 y por `AdminKey`; ninguna cadena queda en JSX. Los textos son
propuestas para el propietario, como en la parte 1.

### E13. El simulador y los recorridos

**Elección.** Cambios acotados en `e2e/mock-api/` (cuentan como pruebas):

| Cambio | Para |
|---|---|
| Presets `validation-failed` (inicio de sesión responde `400` con `errors: [{ field: "password", reason: "max" }]`) e `internal-error` (`500` con `traceId`) | SS41, SS50, AW54 en la puerta y los recorridos; las pruebas de componente usan MSW |
| Preset `enrollment-expired` (el token `mfa-enroll` vence antes de la inscripción) | SS88 |
| `enroll` genera clave y códigos nuevos en cada llamada, como el backend | SS100 |
| `GET /__control/account/:id`, solo local, con la clave base32 y los códigos vigentes de la cuenta ficticia | Recorridos con TOTP y con código de recuperación sobre una cuenta ya inscrita |

El registro de peticiones que necesitan SS74, SS85 y SS116 (cabeceras y cuerpos) se toma en Playwright con `page.on("request")`, no en el
simulador, que hoy guarda solo método y ruta (`server.ts:154`). Un fallo de red se provoca con `page.route(...).abort("failed")` (D15). Los recorridos
viven en `e2e/specs/access/{sign-in,second-factor,enrollment,secrets,keyboard}.e2e.ts` y declaran, en el informe y en `docs/09`, que corren contra la API
simulada (SS123, I307).

**Nota fechada 2026-11-07 (decisión del propietario).** Las brechas del simulador frente al requisito de los recorridos (sin escenarios por
configuración para `400` con `errors`, `500` con `traceId` ni token vencido en la inscripción; el mismo secreto en cada inscripción; sin acceso de
prueba a la clave y los códigos de la cuenta inscrita) quedan registradas con nota fechada en el delta `staff-sign-in`, junto a SS123. El requisito
no cambia: los cambios de esta tabla las cierran, en los PR 4 (presets de inicio de sesión), 6 (control de cuenta) y 7 y 8 (inscripción).

**Trazabilidad.** SS01-SS05, SS74, SS81, SS82, SS85, SS100, SS102, SS113-SS117, SS123; AW09; I304, I305, I307-I309.

### E14. Puerta de accesibilidad: 16 estados nuevos y cierre del criterio 8

**Elección.** La matriz de `e2e/support/a11y-matrix.ts` (tarea 12 de la parte 1) suma, en el PR de cada pantalla, sus filas:

| Pantalla | Estados (por 2 temas) | Cómo se llega |
|---|---|---|
| Inicio de sesión (8) | inicial; resumen de errores; credenciales no aceptadas; límite con cuenta regresiva; servicio ocupado; enviando; contraseña visible; con aviso | Presets `authentication-failed`, `too-many-requests`, `capacity-exceeded`, `slow`; aviso por `restricted-expired` |
| Verificación (3) | TOTP; recuperación; código no aceptado | `second-factor-required` |
| Inscripción (5) | cargando; QR y clave; códigos; primer código; error de red | `enrollment-required`; cargando con una ruta retenida por `page.route`; red con `abort` |

Con las 6 de la parte 1 suman 22 estados y 44 evaluaciones (BX109). `assertCoversMinimum` sube al mínimo completo en el último PR de pantallas.

**Criterio 8.** `docs/09-roadmap-y-fases.md` se marca **solo en el último PR**, cuando la puerta ya corrió en verde sobre las cinco pantallas en los
dos temas (BX102, BX103), con la nota fechada de BX104 y las notas de `docs/ui-ux/04` y `06` (BX105). La nota cita la puerta, el cambio y que los
recorridos son simulados. Si se revierte la puerta o una pantalla evaluada, el commit de reversión desmarca el criterio con nota fechada (BX108); lo
recuerda la descripción de cada PR.

**Trazabilidad.** BX102-BX105, BX108, BX109.

## 4. Flujos

**Inicio de sesión con inscripción y primer código**

```
 SignInContainer   AccessSession        enrollment-draft   API (simulada)
   │ submit(id normalizado, pwd)            │                 │
   │ signIn ──────────►│ POST /sessions ───────────────────────►│
   │                   │◄── 200 second-factor-enrollment-required + token
   │                   │ token-store: mfa-enroll; draft.begin(id)
   │◄─ enrollment-required                  │                 │
   │ navigate(inscripcion)  (apila entrada)  │                 │
 EnrollmentContainer   │                    │                 │
   │ efecto: startEnrollment() ─►│ promesa única ─ POST /mfa/enrollments ─►│
   │ (StrictMode: misma promesa) │◄── 201 {secret, recoveryCodes} ──────────│
   │◄─ instantánea: secret      │ draft.secret, draft.codes                │
   │ advance ×2 (códigos olvidados)                                        │
   │ activate(code) ──►│ POST /mfa/verifications {code}, Bearer mfa-enroll ─►│
   │                   │◄── 200 sesión ─ token-store: acceso; draft.clear() │
   │◄─ authenticated; navigate("/", replace)                               │
```

**Verificación con token vencido**

```
 SecondFactorContainer   AccessSession              API
   │ verify({code}) ───────►│ POST /mfa/verifications ─►│
   │                        │◄─ 401 token-expired ─────│
   │◄─ failed(restricted-token-ended)                  │
   │ discardSecondFactor("second-factor-interrupted") ─► endLocally: tokens y borrador fuera; vista anonymous+notice
   │ navigate(iniciar-sesion, replace)                 │
 SignInContainer: Alert info con notice.verificationInterrupted, foco en h1
```

**Límite de solicitudes**

```
 submit ─► 429, Retry-After 15 ─► Alert fijo (role=alert) + foco; número fuera de regiones activas
   cada 1 s: solo el número ─► a los 15 s: región polite «Ya puede intentar de nuevo.»; intervalo limpio
   envío antes de tiempo: sin petición, foco al Alert
```

## 5. Cambios de archivos

| Archivo | Acción | Descripción |
|---|---|---|
| `src/features/identity/session/enrollment-draft.ts` | Crear | Borrador privado: identificador, secreto, códigos, paso y promesa memorizada (E2) |
| `src/features/identity/session/access-session.ts` | Modificar | `signIn` guarda el identificador para la inscripción; `startEnrollment`, `advanceEnrollment`, `reviewEnrollmentSecret`, `activate`, `getEnrollment`; `endLocally` borra el borrador; sale `enroll` |
| `src/features/identity/session/index.ts` | Modificar | Reexporta los tipos nuevos |
| `.dependency-cruiser.cjs` | Modificar | `admin-token-store-is-private` cubre también `session/enrollment-draft` |
| `src/shared/forms/{use-access-form,access-failure,field-errors,use-retry-countdown}.ts` | Crear | E3, E5, E6 |
| `src/features/identity/components/{ProblemAlert,CopyButton}.tsx` | Crear | `Alert` con traza; copia con aviso `polite` |
| `src/features/identity/components/AccessPlaceholder.tsx` y su prueba | Borrar | Sin uso cuando las tres pantallas existen |
| `src/features/identity/sign-in/**` | Crear | Contenedor, esquema, `SignInForm`, `PasswordField` |
| `src/features/identity/second-factor/**` | Crear | Contenedor, esquema, `SecondFactorForm` |
| `src/features/identity/enrollment/**` | Crear | Contenedor, `otpauth-uri`, `manual-key`, cuatro presentaciones |
| `src/features/identity/routes.tsx` | Modificar | Importa los contenedores reales y pasa rutas por propiedad; borra los provisionales |
| `src/features/identity/session-secrets.flow.test.tsx` | Modificar | Centinelas de contraseña, código, secreto y códigos a lo largo de las tres pantallas (SS03, parte jsdom de SS116) |
| `src/i18n/locales/es-HN/admin.json` | Modificar | Claves de la sección 8 |
| `apps/admin-web/package.json`, `pnpm-workspace.yaml`, `pnpm-lock.yaml` | Modificar | `qrcode.react` (dependencia), `jsqr` (desarrollo), `lucide-react: catalog:` |
| `e2e/mock-api/{scenarios,session-model,server}.ts` y pruebas | Modificar | E13 |
| `e2e/support/a11y-matrix.ts` | Modificar | Filas de E14 |
| `e2e/specs/access/{sign-in,second-factor,enrollment,secrets,keyboard}.e2e.ts` | Crear | Recorridos |
| `docs/09-roadmap-y-fases.md`, `docs/ui-ux/04-patrones-de-interaccion.md`, `docs/ui-ux/06-flujos-clave.md` | Modificar | Solo notas fechadas agregadas y la marca del criterio 8 (último PR) |
| `apps/api/**`, `packages/ui/**`, `packages/contracts/**` | Sin cambios | Ningún componente de `ui` nuevo: todo compone lo publicado |

## 6. Interfaces

```ts
// session/access-session.ts — cambios públicos
export type EnrollmentStep = "secret" | "recovery-codes" | "first-code";

export interface EnrollmentSnapshot {
  readonly identifier: string;                      // el valor enviado al iniciar sesión, ya recortado
  readonly secret: string;                          // base32, tal como llegó
  readonly recoveryCodes: readonly string[] | undefined; // undefined desde que se confirmó haberlos guardado
  readonly step: EnrollmentStep;
}

export type StartEnrollmentOutcome =
  | { readonly kind: "ready" }                      // la instantánea se lee con getEnrollment()
  | { readonly kind: "no-token" }
  | { readonly kind: "failed"; readonly result: ApiFailureResult };

export interface AccessSession {
  // ... lo de la parte 1, sin `enroll`
  startEnrollment(): Promise<StartEnrollmentOutcome>; // una promesa por token restringido
  getEnrollment(): EnrollmentSnapshot | undefined;    // mismo objeto hasta que cambia
  advanceEnrollment(): void;                          // secret → recovery-codes (o first-code si ya no hay códigos) → first-code
  reviewEnrollmentSecret(): void;                     // first-code → secret
  activate(code: string): Promise<VerifyOutcome>;     // solo TOTP, con el token mfa-enroll
}
```

```ts
// shared/forms/use-access-form.ts — lo que ve la presentación
export interface AccessFormView<F extends string> {
  readonly values: Readonly<Record<F, string>>;
  readonly fieldErrors: Readonly<Partial<Record<F, string>>>;          // ya traducidos
  readonly summary: readonly { controlId: string; message: string }[]; // para FormErrorSummary
  readonly alert?: { readonly message: string; readonly traceId?: string };
  readonly waiting: boolean;                                           // texto de la región polite de espera
  readonly countdown?: { readonly remaining: string; readonly done: boolean };
  readonly submitting: boolean;
  readonly summaryRef: Ref<HTMLDivElement>;
  readonly alertRef: Ref<HTMLDivElement>;
  onChange(field: F, value: string): void;
  onBlur(field: F): void;
  onSubmit(event: FormEvent): void;
}
```

## 7. Mapa de errores por pantalla

Se conserva la sección 7 del diseño de la parte 1, con estas concreciones:

| Resultado | Inicio de sesión | Verificación | Primer código | Inscripción (petición) |
|---|---|---|---|---|
| `400` con `errors` | Campos y resumen (SS41, AW51) | Igual | Igual | `ErrorState` |
| `401 authentication-failed` | `Alert`, texto fijo, conserva el identificador | — | — | — |
| `401 second-factor-invalid` | — | `Alert` según modo | `Alert` TOTP; QR y clave visibles | — |
| `401 token-*` o `no-token` | — | Aviso de verificación interrumpida | Aviso de inscripción interrumpida | Aviso de inscripción interrumpida |
| `409 mfa-already-enrolled` | — | — | — | Aviso de segundo factor ya inscrito |
| `429` | Cuenta regresiva | Igual | Igual | `ErrorState` |
| `503`, red | `Alert` con su mensaje; nuevo envío permitido | Igual | Igual | `ErrorState` con reintento |
| `500`, desconocido, fuera de esquema | Mensaje genérico, traza copiable, sin token (AW64) | Igual | Igual | `ErrorState` con traza |

## 8. Textos propuestos

Para revisión del propietario. Van en `es-HN`, en «usted», y se cambian sin tocar código.

| Clave | Texto propuesto |
|---|---|
| `signIn.identifier`, `signIn.password`, `signIn.submit` | Correo electrónico / Contraseña / Iniciar sesión |
| `signIn.showPassword` | Mostrar contraseña (fijado por SS53, enmendado el 2026-11-07; `signIn.hidePassword` ya no existe) |
| `field.required`, `field.tooLong`, `field.invalid` | Este dato es obligatorio. / Este dato es demasiado largo. / Este dato no es válido. |
| `field.unknown` | Uno de los datos enviados no es válido. |
| `secondFactor.code`, `secondFactor.codeFormat` | Código de verificación / Escriba los 6 dígitos que muestra su aplicación. |
| `secondFactor.recoveryCode` | Código de recuperación |
| `secondFactor.useRecovery`, `secondFactor.useCode` | Usar un código de recuperación / Usar el código de la aplicación |
| `secondFactor.submit`, `secondFactor.back` | Verificar / Volver al inicio de sesión |
| `enrollment.loading`, `enrollment.failed` | Preparando su verificación en dos pasos. / No se pudo preparar la verificación en dos pasos. |
| `enrollment.secret.title`, `enrollment.secret.intro` | Escanee el código QR / Escanee el código con su aplicación de autenticación o escriba la clave manualmente. |
| `enrollment.qrLabel`, `enrollment.manualKey` | Código QR para su aplicación de autenticación / Clave para escribir manualmente |
| `enrollment.codes.title`, `enrollment.codes.intro` | Guarde sus códigos de recuperación / Cada código sirve una sola vez si pierde acceso a su aplicación. No se volverán a mostrar. |
| `enrollment.codes.confirm`, `enrollment.codes.confirmRequired` | He guardado mis códigos de recuperación / Confirme que guardó sus códigos de recuperación para continuar. |
| `enrollment.continue` | Continuar |
| `enrollment.firstCode.title`, `enrollment.firstCode.submit`, `enrollment.firstCode.reviewSecret` | Escriba el primer código / Activar / Ver de nuevo el código QR |

`enrollment.codes.confirm` es el texto que fija el requisito. Los textos de credenciales, código, límite y sesión vencida ya existen
(`admin.json:15`, `:29-31`).

## 9. Dependencias

| Paquete | Sección | Condición |
|---|---|---|
| `qrcode.react` | `dependencies` de la app, por catálogo | Publicada hace más de 14 días (`minimumReleaseAge: 20160`, `pnpm-workspace.yaml:28`), con React 19 en sus pares, sin dependencias de ejecución nuevas, `pnpm audit --audit-level high` limpio y `pnpm why react` sin duplicados. **Candidata por conocimiento previo, no verificada:** la serie 4.x |
| `jsqr` | `devDependencies` de la app, por catálogo | Mismas condiciones; solo prueba. **Candidata:** 1.4.0 |
| `lucide-react` | `dependencies` de la app | `catalog:` (1.57.0, ya en el archivo de bloqueo): sin versión nueva |

Si la versión más reciente no cumple la antigüedad, pnpm resuelve a la última que sí la cumple; el PR registra la versión elegida y su fecha. Si
ninguna sirve, se detiene el PR. BX92 y BX94 de la parte 1 los cubren al entrar.

## 10. Estrategia de pruebas

TDD estricto: cada fila corre primero en rojo contra el árbol sin el código de producción, con la causa observada en `apply-progress.md`.

| Capa | Qué | Cómo | Escenarios |
|---|---|---|---|
| Unidad | Borrador y operaciones nuevas de sesión | Vitest con MSW en Node, reloj inyectado; `StrictMode` no aplica aquí | SS58, SS59 (contenedor), SS72, SS74, SS87, SS96, SS98, SS100 (parte cliente) |
| Unidad | Normalización, esquemas, razones de campo, clasificador, URI, clave en grupos | Tablas | SS62, SS63, SS66, SS77, SS79, SS80, AW51, I311 |
| Unidad | Cuenta regresiva | Temporizadores simulados | SS43-SS46 |
| Componente | Cada presentación, solo con propiedades, con su comentario `// States.` | Testing Library | AW57, AW58, SS52, SS53, SS61, SS64, SS89, SS95 |
| Componente | Cada contenedor con MSW y la app completa | `render-app.tsx` | SS37-SS42, SS47, SS49, SS50, SS54-SS57, SS60, SS65, SS67-SS73, SS75 (`StrictMode`), SS76, SS86, SS88, SS90-SS94, SS97, SS99, SS101, AW49, AW54, AW64, SS28, SS29, SS31 |
| Flujo jsdom | Cachés de Query sin centinelas en cada pantalla | `session-secrets.flow.test.tsx` | SS03, cláusula de caché de SS116 |
| Navegador | Recorridos sobre `vite preview` y la API simulada | Playwright, `*.e2e.ts` | SS01, SS02, SS04, SS05, SS48 (31 s, límite declarado), SS51, SS78, SS81-SS85, SS102, SS113-SS117, AW09, I304, I305 |
| Navegador | Puerta axe | Matriz de E14 | BX109, BX103 |
| Documentos | Notas fechadas y criterio 8 | Pruebas de diff como las de la parte 1 | BX102, BX104, BX105, BX108, SS123, SS127, I307, I308 |

Demostraciones deliberadas propuestas (copia fuera del repositorio, rojo por aserción, `cmp`): sin guardia de envío en vuelo (SS40); `maxLength` en
un campo (SS51); `type="email"` en el identificador (SS58); identificador en minúsculas (SS58); promesa de inscripción sin memorizar (SS75); códigos
conservados tras avanzar (SS93); `recoveryCode` permitido en `activate` (SS72, por `tsc`); número de la cuenta dentro del `Alert` (SS44); `style` en el
QR (SS83); borrador sin borrar en `endLocally` (SS98).

**SS48** es la única espera real (31 s), con `test.setTimeout(60_000)`, como fijó la parte 1. **SS81** dibuja el SVG en un `canvas` dentro de la página y
decodifica con `jsqr`.

## 11. Matriz de amenazas

N/A para la matriz de la fase: no hay órdenes de consola de producción, automatización de Git o de PR ni clasificación de archivos ejecutables. Fronteras
de seguridad propias, cada una con su prueba en rojo:

| Frontera | Comportamiento seguro | Prueba |
|---|---|---|
| Contraseña, código, secreto o códigos en almacenamientos, URL, historial, cachés, consola o `window` | Solo memoria de módulo o estado de componente que se desmonta | SS01-SS05, SS116 |
| Secreto, URI o identificador como etiqueta en una petición | No salen; el QR se dibuja en el navegador | SS82, SS85, I311 |
| Oráculo de cuentas | Un solo texto para `authentication-failed` | SS56 |
| Código de recuperación en la activación | Imposible por tipo | SS72 |
| Regreso a los códigos | No existe operación que los devuelva | SS93 |
| Reutilización de un token restringido | Descarte por la guarda de la raíz y por `endLocally` | SS31, SS69, SS70 |
| Control de prueba con la clave de la cuenta | Solo local, en el simulador, fuera del build | Prueba de `Host` ajeno de la tarea 11 y `specs/build` |
| Portapapeles con el secreto | Riesgo aceptado (R10 de la parte 1) | — |

## 12. Entrega: previsión y rebanadas

**Medida histórica.** Frente a sus estimaciones P+4T, la parte 1 midió: tarea 3, 1 214 frente a 740 (1,6×); tarea 4, 1 091 frente a 790 (1,4×);
tarea 5, 841 frente a 670 (1,25×); tarea 6, 1 127 frente a 1 005 (1,1×); tarea 7, 421 frente a 520 (0,8×); tarea 9, 1 476 frente a 1 005 (1,5×);
tarea 11a, 1 663 frente a 1 420 (1,2×). Cuatro de siete tareas pidieron excepción a las 800 líneas.

**Estimación propia.** Esta tabla se construyó de abajo arriba con los tamaños reales de archivos parecidos de la parte 1 (por ejemplo,
`access-session.ts` 220 y su prueba 360; `routes.tsx` 123; `SignedInContainer` y su página con pruebas, unas 580). Líneas efectivas, sin `openspec/`,
archivo de bloqueo ni código generado. **Son estimaciones, no medidas.**

| PR | Rebanada | Contenido | Estimado | ×1,2 | ×1,5 | Corte predefinido si mide más de 800 |
|---|---|---|---|---|---|---|
| 1 | `enrollment-draft-in-session` | E2, regla de dependencia, pruebas | 430 | 520 | 650 | Ninguno |
| 2 | `access-form-foundation` | `use-access-form`, clasificador, razones, cuenta regresiva, `ProblemAlert`, `CopyButton` | 620 | 740 | 930 | **2a** gancho, clasificador y razones / **2b** cuenta regresiva, `ProblemAlert`, `CopyButton` |
| 3 | `sign-in-form` | Contenedor, esquema, formulario, `PasswordField`, contacto, rutas, catálogo | 560 | 670 | 840 | **3a** esquema y presentación / **3b** contenedor y rutas |
| 4 | `sign-in-states-and-journey` | Avisos, `429`, `503`, red, `500`; presets; recorrido; 8 filas de la puerta; AW09 | 540 | 650 | 810 | **4a** estados en jsdom / **4b** presets, recorrido y puerta |
| 5 | `second-factor-screen` | Contenedor, esquema, modos, catálogo, pruebas de componente | 560 | 670 | 840 | **5a** esquema y presentación / **5b** contenedor |
| 6 | `second-factor-journeys` | Control de cuenta del simulador, recorridos TOTP, recuperación y token vencido, teclado, 3 filas | 430 | 520 | 650 | Ninguno |
| 7 | `enrollment-secret-and-qr` | Dependencias, URI, clave, `TotpQrCode` (SS83 primero), `SecretStep`, contenedor con carga y errores, 3 filas | 620 | 740 | 930 | **7a** URI, clave, `TotpQrCode` y dependencias / **7b** contenedor y `SecretStep` |
| 8 | `enrollment-codes-and-activation` | `RecoveryCodesStep`, `FirstCodeStep`, `activate`, secreto nuevo por inscripción, SS81, SS82, SS84, SS85, 2 filas (matriz completa) | 600 | 720 | 900 | **8a** pasos y activación / **8b** simulador y pruebas de navegador del QR |
| 9 | `access-journeys-and-criterion-8` | Recorrido completo de inscripción, centinelas, flujo jsdom, borrado de `AccessPlaceholder`, `docs/09` (criterio 8), notas de `04` y `06` | 500 | 600 | 750 | Ninguno |
| | **Total** | | **4 860** | **5 830** | **7 290** | |

**Conclusión.** De **9 a 15 PR**: 9 si todo mide como a 1,2×, y hasta 15 si los seis cortes predefinidos se usan. La propuesta decía de 7 a 9; esta
previsión la supera porque cada rebanada se dimensionó para que su estimado a 1,3× quede bajo 800, y porque los recorridos y la puerta pesan como
pruebas. Las tareas serán 9 de nivel superior, con los cortes como subtareas, dentro del tope de 15 de `openspec/config.yaml`.

**Órdenes duros.** 1 antes de 2 y de 7; 2 antes de 3, 5 y 8; 3 antes de 4; 5 antes de 6; 7 antes de 8; 4, 6 y 8 antes de 9. La cadena lineal 1 a 9 los
cumple. Antes del PR 4 debe estar fusionada la tarea 12 (11b) de la parte 1; antes del PR 2, la tarea 8 (`FormErrorSummary` y `controlId`).

Líneas para `sdd-tasks`: `Decision needed before apply: Yes` (pregunta 2); `Chained PRs recommended: Yes`; `400-line budget risk: High`. El presupuesto del
proyecto es de 800 por PR (`docs/15` §3).

**Nota fechada 2026-11-07 (decisión del propietario: estrategia de entrega).** La pregunta 2 está resuelta y fija la estrategia de entrega del cambio:

- **Cadena de 9 PR encadenados**, uno por fila de la tabla, en el orden 1 a 9, cada uno sobre la rama del anterior.
- **Cortes predefinidos** los de la tabla, que pueden llevar la cadena hasta **15 PR**.
- **Regla de las 800 líneas.** Se mide antes de cada commit, como en la parte 1. Si un PR supera 800 **solo por poco**, no se corta: el ejecutor se
  detiene, registra la medida y pide al propietario una excepción explícita. Si la supera claramente, aplica el corte predefinido de su fila, por archivos
  completos y con las pruebas junto a su código; si la fila no tiene corte o una parte sigue sobre 800, se detiene y consulta. Qué es «por poco» lo
  decide el propietario en cada consulta; nunca se quitan pruebas, comentarios ni líneas para caber.
- Con la estrategia ya decidida, las líneas para `sdd-tasks` pasan a ser: `Decision needed before apply: No`; `Chained PRs recommended: Yes`;
  `400-line budget risk: High`. Se comporta como `auto-chain` con la estrategia de cadena ya elegida.
- La propuesta recibió una nota fechada que reemplaza su previsión de «7 a 9 PR».

**Migración y reversión.** Sin datos ni entorno. Cada PR se revierte con su commit de fusión, en orden inverso; revertir los PR 4, 6, 7, 8 o 9, o la puerta,
desmarca el criterio 8 con nota fechada (BX108).

## 13. Riesgos

| Id | Riesgo | Mitigación |
|---|---|---|
| R1 | `QRCodeSVG` no cumple SS83 | Rojo primero en el PR 7; sin alternativa preparada: detención y decisión del propietario (E11, nota del 2026-11-07) |
| R2 | La parte 1 aún no tiene puerta ni Playwright en el árbol leído | Dependencia dura antes del PR 4 |
| R3 | Las versiones de `qrcode.react` y `jsqr` no se verificaron | Sección 9; primera tarea del PR 7 |
| R4 | El observador de SS44 cuenta por separado el vaciado de la región de espera y la aparición del aviso | E6; la prueba en rojo decide el orden |
| R5 | Un intervalo de 1 s de la cuenta regresiva confunde las pruebas de la parte 1 que vigilan intervalos de refresco | Esas pruebas no provocan un `429`; si alguna lo hiciera, se acota por nombre del temporizador |
| R6 | Los recorridos con espera real (SS48) y el arranque en frío alargan el trabajo de CI de 20 minutos | Se mide en el PR 4 y se ajusta con nota fechada, como hizo la parte 1 |
| R7 | La desviación de 6b se corrige y cambia la firma de `AccessSession` | E2; las pruebas de 6b que usan `enroll` cambian en el PR 1 |
| R8 | El simulador sigue sin ser la pila real | Declarado en cada recorrido y en `docs/09`; prueba real del cambio 11 (I307-I309) |

## 14. Contradicciones y decisiones para el propietario

**Contradicciones encontradas.**

1. **QR.** El requisito exige `QRCodeSVG` de `qrcode.react` con DEBE, y el bloque «Valores fijados» del mismo delta (`specs/staff-sign-in`, línea 30)
   da por decidida la salida con `qrcode` y `QRCode.create()`. E11 no aplica la salida sin decisión.
2. **Diseño propio.** `proposal.md` dice que el cambio no tiene diseño propio; este documento existe por pedido del coordinador y corrige D8 en un punto
   (E2) apoyado en el código real.
3. **Desviación 4 de la tarea 6b** (`apply-progress.md`): dejaba el identificador al contenedor; E2 lo lleva al módulo de sesión.
4. **Foco del resumen.** `docs/ui-ux/04:393` dice que el foco salta al primer campo con error y `:394` que el resumen es `role="alert"`; la especificación
   (SS37), `docs/ui-ux/03:141` y `FormErrorSummary` (`role="group"`) enfocan el resumen. Se sigue la especificación y se agrega una nota fechada en `04`.
5. **Número de PR.** La propuesta prevé de 7 a 9; la sección 12 prevé de 9 a 15.
6. **Simulador.** El diseño de la parte 1 suponía que el simulador podía provocar un `400` con `errors` y daba un secreto por inscripción; el código actual
   no lo hace (E13).
7. **SS53** pide a la vez nombre accesible cambiante y `aria-pressed`, dos señales del mismo estado que las guías de ARIA desaconsejan combinar.

**Preguntas para el propietario.**

1. **QR (bloquea el PR 7):** ¿Se borra del delta `staff-sign-in` la salida alternativa con `qrcode` y `QRCode.create()`, de modo que un fallo de SS83 detenga
   el PR, o se reescribe el requisito para admitir esa salida como cumplimiento válido?
2. **Tamaño (bloquea la fase de tareas):** ¿Acepta una cadena de 9 PR con cortes predefinidos que la pueden llevar a 15, o prefiere, como en la parte 1,
   excepciones por PR a las 800 líneas para mantenerla cerca de 9?
3. **SS53:** ¿Se conserva el control «mostrar contraseña» con nombre cambiante y `aria-pressed` a la vez, como dice la especificación, o se enmienda SS53 para
   usar un nombre fijo con `aria-pressed`?
4. **Textos:** ¿Aprueba los textos de la sección 8?
5. **Diseño:** ¿Aprueba este diseño, incluido que el estado de la inscripción pase al módulo de sesión (E2) y que el formulario valide también al salir del
   campo (E3, por `docs/ui-ux/04:392`)?

> **Nota fechada 2026-11-07 (decisiones del propietario).** Las listas de arriba no se reescriben. Estado de cada punto:
>
> | Punto | Estado | Dónde queda escrito |
> |---|---|---|
> | Contradicción 1 y pregunta 1 (QR) | Resuelta: se retiró la salida con `qrcode`; `QRCodeSVG` es la única vía y un fallo de SS83 detiene el PR 7 | E11; delta `staff-sign-in`, valores fijados |
> | Contradicción 2 (diseño propio) | Registrada | `proposal.md`, nota fechada en «Diseño y tareas» |
> | Contradicción 3 (desviación 4 de 6b) | Se corrige con E2; queda dentro de la pregunta 5 | E2 |
> | Contradicción 4 (foco del resumen) | Resuelta: ganan la especificación y `docs/ui-ux/03`; la nota en `docs/ui-ux/04` la agrega el PR 2 | E3; delta `staff-sign-in`, junto a SS51 |
> | Contradicción 5 y pregunta 2 (tamaño) | Resuelta: 9 PR, cortes hasta 15, excepción para excesos pequeños | Sección 12; `proposal.md`, nota fechada en «Alcance» |
> | Contradicción 6 (simulador) | Registrada; esta parte cierra las brechas | E13; delta `staff-sign-in`, junto a SS123 |
> | Contradicción 7 y pregunta 3 (SS53) | Resuelta: nombre fijo «Mostrar contraseña» y estado en `aria-pressed` | E8; delta `staff-sign-in`, requisito del inicio de sesión y SS53 |
>
> **Siguen abiertas:** la pregunta 4 (textos de la sección 8, ya sin `signIn.hidePassword`) y la pregunta 5 (aprobación final de este diseño).
