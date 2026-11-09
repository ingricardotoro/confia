# Avance de aplicación: `admin-web-staff-sign-in`

- **Cambio:** `admin-web-staff-sign-in` (13b-1 de F0, parte 2 de 2)
- **Modo:** TDD estricto. Comandos por superficie según `tasks.md` (pnpm y Vitest); el `test_command` de Maven no se usa.
- **Última actualización:** 2026-11-08
- **Entrega:** PR apilados (`stacked-to-main`), 800 líneas efectivas por PR (`docs/15` §3), umbral de excepción 880

## Estado acumulado

| Tarea | Estado | Rama | Líneas efectivas |
|---|---|---|---|
| 1 `enrollment-draft-in-session` | **Hecha** (sin corte) | `change/admin-web-staff-sign-in-1-enrollment-draft-in-session` | 698 de 800 (base `ca98c03`) |
| 2 `access-form-foundation` | **Hecha**, con corte 2a / 2b y excepción del propietario para 2a | 2a `change/admin-web-staff-sign-in-2-access-form-foundation`; 2b `change/admin-web-staff-sign-in-2b-access-form-feedback` | 2a 848 (base `152172b`, excepción aprobada 2026-11-07); 2b 443 (base `e226ce3`) |
| 3 `sign-in-form` | **Hecha**, con corte 3a / 3b (el total superó 880) | 3a `change/admin-web-staff-sign-in-3-sign-in-form`; 3b `change/admin-web-staff-sign-in-3b-sign-in-container` | Total 912 (base `cb3ded9`); 3a 569 (base `cb3ded9`); 3b 343 (base `2598e28`) |
| 4 `sign-in-states-and-journey` | **Hecha** (sin corte) | `change/admin-web-staff-sign-in-4-sign-in-states-and-journey` | 656 (base `2ceaa46`) |
| 5 `second-factor-screen` | **Hecha** (sin corte; excepción del propietario del 2026-11-09) | `change/admin-web-staff-sign-in-5-second-factor-screen` | 856 (base `60d4bf9`) |
| 6 a 9 | Pendientes | | |

## Tarea 1: `enrollment-draft-in-session`

### Medida (regla de las 800 líneas)

Medida con `git add -N .` y `git diff --numstat -M ca98c03` (sin `openspec/`, archivo de bloqueo ni código generado), antes del primer commit:
676 adiciones y 22 eliminaciones, **698 líneas**. La estimación era de 430 (1x) a 650 (1,5x): la medida quedó un 7 % sobre el techo
de 1,5x y por debajo de 800, así que no hubo corte ni excepción. `tooling/dependency-fixtures/README.md` quedó fuera (ver desviaciones).

### Evidencia del ciclo TDD

| Paso | ROJO observado | Causa observada | VERDE |
|---|---|---|---|
| 1.1 / 1.2 borrador y sesión | Sí, antes del código de producción | `Failed to resolve import "./enrollment-draft.ts"` en `access-session.test.ts` y `enrollment-draft.test.ts`; `accessSession.getEnrollment is not a function` (`index.test.ts`); `TypeCheckError`: `startEnrollment`, `getEnrollment`, `activate` y `enrollmentDraft` inexistentes | 94 pruebas del directorio `session/` pasan, sin errores de tipos |
| 1.1 / 1.2 `activate.test-d.ts` | Parcial | Falló `Property 'activate' does not exist on type 'AccessSession'`. Las directivas `@ts-expect-error` se cumplían por la causa equivocada (la propiedad no existía); la causa correcta la prueba la demostración 3 | `tsc` limpio |
| 1.1 / 1.2 regla de dependencia | Sí | `AssertionError: expected [] to include 'admin-token-store-is-private'` para el *fixture* `imports-enrollment-draft.ts`. Los dos casos aceptados pasaron en el rojo porque, sin la regla, nada se viola | 45 pruebas pasan |

Las pruebas y el *fixture* se escribieron antes que `enrollment-draft.ts`, los cambios de `access-session.ts`, `index.ts` y la regla.
Comprobación de tipos: `tsc --listFilesOnly` incluye `activate.test-d.ts` (por `include: ["src"]` de `tsconfig.json`) y Vitest lo corre además con su modo de tipos.

### Demostraciones deliberadas (tarea 1.3)

Procedimiento: copia a `$CLAUDE_JOB_DIR/tmp/demo`, ruptura, rojo por aserción, restauración desde la copia y `cmp` sin salida.

| # | Ruptura | Rojo observado | `cmp` |
|---|---|---|---|
| 1 | Quitar la promesa memorizada de `startEnrollment` | 3 fallos: SS74 `expected Promise to be Promise` y `to have a length of 2 but got 4` (peticiones); SS93 | idéntico |
| 2 | Conservar los códigos al pasar a `first-code` | 4 fallos: SS93 en sesión y en borrador, `expected { …(4) } to match object { step: 'first-code', … }` | idéntico |
| 3 | `activate(code: string \| { recoveryCode: string })` | `tsc`: `TS2578 Unused '@ts-expect-error' directive` en `activate.test-d.ts` y `TS2344` en la igualdad de tipo | idéntico |
| 4 | `endLocally` sin `forgetEnrollment()` | 7 fallos: SS98 (descartar, cerrar sesión, sesión vencida), aviso de inscripción interrumpida y respuesta en vuelo | idéntico |
| 5 | Regla sin `enrollment-draft` | `expected [] to include 'admin-token-store-is-private'` (*fixture*) | idéntico |

### Desviaciones y decisiones

1. **Memorización fuera del borrador.** El diseño §5 describe el borrador con la promesa memorizada; la tarea 1.2 pide «ningún token del borrador». La promesa se memoriza por token en una variable del cierre de `createAccessSession`, junto al almacén de tokens, para que el borrador no guarde ninguno.
2. **`enrollmentDraft` como dependencia opcional** de `createAccessSession`, para que las pruebas inspeccionen el borrador (SS59, SS98). Por defecto se crea uno nuevo.
3. **Recorte del identificador en el borrador** (`begin` recorta). `signIn` envía lo recibido; el contenedor de la tarea 3 envía el valor ya normalizado, así que lo enviado y lo guardado coinciden (SS58).
4. **`signIn` y la verificación correcta borran el borrador anterior**; una respuesta sin inscripción no deja identificador (SS59).
5. **`activate` sin token `mfa-enroll`** solo termina localmente si no hay sesión abierta, igual que `discardSecondFactor`.
6. **Citas ADR.** El escáner de supresiones exigió una cita en las directivas de `activate.test-d.ts` (falló `suppression-citations.test.ts` en la primera corrida de T); se citó ADR-0018.
7. **Archivos fuera de la lista de la tarea:** dos *fixtures* aceptados y el módulo mínimo `session/enrollment-draft.ts` y `session/index.ts` bajo `tooling/dependency-fixtures/apps/admin-web/` (necesarios para que el *fixture* de violación resuelva y para probar que el import desde `session/index.ts` no viola).
8. **Pendiente de decisión del propietario:** la tarea nombra `tooling/dependency-fixtures/README.md`, que queda fuera de la superficie autorizada; no se editó.

### Verificación de cierre

**Cierre bloqueado, sin commits (2026-11-07).** U (47 archivos, 475 pruebas, cobertura sobre el umbral) y T (9 archivos, 286 pruebas) pasaron.
E falló tres veces con conjuntos distintos de pruebas (15, 16 y 12), todas por `page.goto` agotado o abortado, sin aserción de resultado; las
mismas pruebas de `boot.e2e.ts` pasan (5 de 5) con los servidores ya levantados. La corrida de PR falló en `admin-web#test` por cuatro tiempos
agotados de importación en frío (`main.test.tsx`, `App.test.tsx`, `NotFound.test.tsx`, `routes.test.tsx`), que pasaron en U minutos antes.
La memoria libre medida fue de unos 840 MB. Con unos 5,7 GB libres, E volvió a fallar dos veces más (22 y 13 fallos), siempre por
`page.goto` agotado o abortado (7 `net::ERR_ABORTED`, 2 de 30 s en la corrida de referencia). Para aislar la causa se restauró temporalmente la
producción de `ca98c03` (`access-session.ts` e `index.ts`) y E dio 15 fallos del mismo tipo; los archivos se devolvieron desde copia.
La carga de CPU medida era del 97 % por procesos ajenos al trabajo. PR pasó completo (18 de 18 tareas) en la corrida con memoria libre.
Medida final con el README del *fixture*: 704 líneas efectivas.

**E con 2 trabajadores (corrida del coordinador, `pnpm --filter @confia/admin-web test:e2e --workers=2`, suite completa).** Primera corrida:
36 pasan y 1 falla, `[BX81] / [signed-in-error]` en el tema oscuro («The expected content of / [signed-in-error] did not appear within 10000 ms»;
su gemela clara pasó en 2,3 s). Repetición: 37 de 37 en 44,3 s. Registros: `e2e-w2.log` y `e2e-w2b.log`.

**Conclusión sobre E.** Con el número de trabajadores por defecto de Playwright (8), E falla por cargas de página bajo la carga de esta máquina
(mis corridas y la comparación con el árbol base). Con 2 trabajadores pasó en la repetición, tras una combinación intermitente de
`signed-in-error` en oscuro. Seguimiento a considerar, fuera de esta tarea: fijar `workers` en `playwright.config.ts` o subir la espera de ese estado.

## Tarea 2: `access-form-foundation`

### Medida (regla de las 800 líneas), 2026-11-07

Medida con `git add -N .` y `git diff --numstat -M 152172b` (sin `openspec/`, archivo de bloqueo ni código generado) sobre lo que hoy contiene el árbol,
que es **solo la parte 2a** (dependencia de prueba, razones de campo, clasificador, gancho, arnés, nota de `docs/ui-ux/04` y sus pruebas):
848 adiciones y 0 eliminaciones, **848 líneas**. Por archivo: `use-access-form.test.tsx` 236, `use-access-form.ts` 170, `field-errors.test.ts` 99,
`field-errors.ts` 63, `access-failure.test.ts` 88, `access-failure.ts` 50, `access-form-harness.tsx` 82, `docs-notes.test.ts` 51, `admin.json` 6,
`docs/ui-ux/04` 2, `package.json` 1. Falta la parte 2b (cuenta regresiva, `ProblemAlert`, `CopyButton`), así que la tarea completa supera con claridad
880 y el corte predefinido 2a / 2b aplica; pero 2a por sí sola ya está entre 800 y 880, por lo que **no se compromete** y se consulta (excepción
explícita hasta 880, o mover `docs-notes.test.ts` y la nota a 2b, que dejaría 2a en 795).

### Evidencia del ciclo TDD (2a)

| Paso | ROJO observado | Causa observada | VERDE |
|---|---|---|---|
| `field-errors`, `access-failure`, `use-access-form` | Sí, antes del código de producción | `Failed to resolve import "./field-errors.ts"`, `"./access-failure.ts"` y `"../shared/forms/use-access-form.ts"` (desde el arnés) | 46 pruebas de `shared/forms` y `docs-notes` pasan |
| `docs-notes.test.ts` | Sí | El digest y la longitud del original pasaron; las otras tres fallaron: `expected '' to match /foco/`, `expected '' to contain '04-patrones-de-interaccion.md:393-394'` y `Target cannot be null or undefined` (aún sin nota) | Pasa con la nota agregada al final |

`@testing-library/user-event` entró como `catalog:` (importador de `pnpm-lock.yaml` editado a mano): `pnpm install --frozen-lockfile --prefer-offline`
pasa y `pnpm why react` muestra una sola versión (19.3.0). Comprobaciones de 2a: `tsc --noEmit` y `eslint src/shared src/test src/app/docs-notes.test.ts` limpios.

### Demostración deliberada 2.4 (1)

Sin la guardia de envío en vuelo (`if (inFlight.current) return;` de `onSubmit`): rojo en `SS40: a submit with Enter in a field, and one that does not go
through the button, call submit once` con `expected "vi.fn()" to be called 1 times, but got 2 times`. Restaurado desde la copia; `cmp` sin salida.
La segunda demostración (cuenta regresiva) corresponde a 2b y no se hizo.

### Desviaciones y decisiones

1. **`liveMessage` en lugar de `waiting` y `countdown.done`.** El gancho expone una sola región `polite` por formulario con el texto de espera mientras
   hay una petición y vacía en otro caso (y `countdown.done` al terminar la espera, en 2b); el diseño §6 separaba `waiting: boolean` y `countdown.done`.
   Así la región activa cambia una vez al empezar y una al terminar (SS44, R4).
2. **`waitingKey` en las opciones del gancho** (clave del texto de espera de cada pantalla) y `fields[f]` con `normalize` aplicada por el gancho antes de
   `validate` y de `submit`.
3. **Referencias como funciones de referencia** (`summaryRef`, `alertRef` guardan el elemento en estado): la regla `react-hooks/refs` rechazaba objetos de referencia
   devueltos por el gancho. La presentación del arnés desestructura las dos antes de usar el resto.
4. **Formato `format` y razón desconocida** comparten la clave `field.invalid`, porque §8 solo trae cuatro claves de campo.
5. **Las aserciones de texto de `field.*`** viven en `field-errors.test.ts` y no en `src/i18n/catalog.test.ts`, que queda fuera de la superficie de la tarea.

### Decisión del propietario y parte 2b (2026-11-07)

**Excepción.** El propietario conservó el corte predefinido y aprobó 2a como excepción explícita dentro del umbral de 880: 848 líneas efectivas contra
`152172b`. 2a se comprometió en dos commits (`d0d9349` gancho y clasificador; `e226ce3` nota de `docs/ui-ux/04`).

**Medida de 2b** contra el commit de 2a (`e226ce3`): 440 adiciones y 3 eliminaciones, **443 líneas** (bajo 800).

**ROJO de 2b**, antes del código de producción: `Failed to resolve import "./use-retry-countdown.ts"`, `"./CopyButton.tsx"` y `"./ProblemAlert.tsx"`;
y, con el gancho sin cuenta regresiva, tres fallos de SS43, SS45 y SS46 (`Unable to find an element with the text: Puede intentar de nuevo en 15 s.`,
`expected "vi.fn()" to be called 1 times, but got 2 times`). Un error propio del verde: el reloj por defecto `Date.now` se capturaba al renderizar, antes de
que la prueba simulara los temporizadores; se cambió a `() => Date.now()` y la prueba de `clear()` separó los `act`.

**Demostración 2.4 (2).** Reiniciar la cuenta (`countdown.start(15)`) ante el segundo envío: rojo en `SS45: a submit during the wait does not call submit, does not restart the count, and returns the focus to the alert`
con `Unable to find an element with the text: Puede intentar de nuevo en 11 s.`. Restaurado desde la copia; `cmp` sin salida.

**Cierre (2a y 2b, cada una sobre su árbol).** U: 2a 51 archivos y 522 pruebas; 2b 54 archivos y 544 pruebas (1 `todo`), cobertura sobre el umbral.
T: 9 archivos, 286 pruebas. E con `--workers=2`: 37 de 37 en ambas. PR (`turbo run lint typecheck test build --filter=!@confia/api --concurrency=1`):
18 de 18 en ambas. Guarda de `*.spec.*` vacía y `git diff --name-only 152172b -- apps/api packages/ui packages/contracts` vacío. T se corrió porque el
catálogo y el archivo de bloqueo pueden alcanzar sus reglas; el importador editado pasó `pnpm install --frozen-lockfile --prefer-offline`.

**Desviaciones de 2b.** (1) La cuenta regresiva no tenía mensaje propio en 2.5: commit `feat(admin-web): add the retry countdown to the shared access form`.
(2) `CopyButton` usa `Button` con texto y no `IconButton`, porque `lucide-react` entra a la app en la tarea 3. (3) `CopyButton` lleva su propia región `status`
siempre presente (vacía hasta copiar). (4) `ProblemAlert` recibe `ref` como propiedad. Sin textos fuera de la sección 8.


## Tarea 3: `sign-in-form`

### Medida (regla de las 800 líneas), 2026-11-08

Medida con `git add -N .`, `git reset`, `git add -A` y `git diff --cached --numstat -M cb3ded9` (sin `openspec/`, archivo de bloqueo ni código generado) sobre
la tarea completa antes de comprometer: 887 adiciones y 23 eliminaciones, **910 líneas** (912 con la aserción de `type="text"` que se agregó en la
demostración 2). Está sobre 880, así que no se pidió excepción y se aplicó el corte predefinido por archivos completos:
**3a** (esquema, `PasswordField`, `SignInForm`, sus pruebas, las cuatro claves de `signIn.*`, `lucide-react`) **569** contra `cb3ded9`;
**3b** (`SignInContainer`, su prueba, `routes.tsx` y `routes.test.tsx`) **343** contra el commit de 3a (`2598e28`).

### Evidencia del ciclo TDD

| Paso | ROJO observado | Causa observada | VERDE |
|---|---|---|---|
| 3a: esquema, `PasswordField`, `SignInForm` | Sí, antes del código de producción | `Failed to resolve import "./sign-in-schema.ts"`, `"./PasswordField.tsx"` y `"./SignInForm.tsx"` (3 archivos fallidos, ninguna prueba ejecutada) | 31 pruebas. Un error de mi prueba: el nombre del botón enviando incluye el `Spinner`; la consulta pasó a `/Iniciar sesión/` |
| 3b: contenedor y rutas | Sí | Las 10 pruebas de `SignInContainer.test.tsx` fallan con `Unable to find an accessible element with the role "textbox"` (y `"button"` en la primera): la ruta aún mostraba el provisional sin campos. La prueba no importa el contenedor (se prueba por la app completa), así que el rojo es de comportamiento y no `Failed to resolve import` | 246 pruebas de `features/identity` y `app` |

El supuesto de 3.1 se cumplió: `LoginRequest.extend({ ... .min(1) })` sobre los esquemas generados de Zod 4.6.6 funciona sin importar `zod` en la app.
No hubo código de producción antes de su prueba.

### Demostraciones deliberadas (tarea 3.3)

Procedimiento: copia a `$CLAUDE_JOB_DIR/tmp/demo`, ruptura, rojo por aserción, restauración desde la copia y `cmp` sin salida.

| # | Ruptura | Rojo observado | `cmp` |
|---|---|---|---|
| 1 | `maxLength={320}` en el identificador | 2 fallos de SS51: `no field has a maxlength or a pattern` y el pegado de 400 caracteres (`expected last "vi.fn()" call to have been called with [ 'identifier', …(1) ]`) | idéntico |
| 2 | `type="email"` en el identificador | SS52 (`the identifier is plain text…`) y, tras agregar la aserción `type="text"` a la prueba del contenedor, SS58. Con la prueba de SS58 sin esa aserción **no** se ponía en rojo: jsdom recorta igual que `normalizeIdentifier`, así que el valor enviado era idéntico; se reforzó la prueba en lugar de dar por cumplida la demostración | idéntico |
| 3 | `normalizeIdentifier` con `.toLowerCase()` | 2 fallos de SS58: `expected 'ana.pérez+admin@colegio.edu.hn' to be 'Ana.Pérez+Admin@Colegio.edu.hn'` (esquema y contenedor) | idéntico |
| 4 | Nombre del control `Ocultar contraseña` cuando está visible | 2 fallos de SS53: `Unable to find an accessible element with the role "button" and name "Mostrar contraseña"` | idéntico |

### Desviaciones y decisiones

1. **Catálogo en 3a.** `tasks.md` asigna el catálogo a 3b; las presentaciones de 3a leen `signIn.identifier`, `password`, `submit` y `showPassword` con `useAdminText`,
   así que las cuatro claves van en 3a. Los textos son los de la sección 8, sin ninguno fuera de ella.
2. **Textos aprobados en pruebas de componente.** La aserción literal de los textos de §8 vive en `SignInForm.test.tsx` y `PasswordField.test.tsx`, no en
   `src/i18n/catalog.test.ts`, que queda fuera de la superficie de la tarea (igual que la desviación 5 de la tarea 2).
3. **Espera, cuenta regresiva y región `polite` sin renderizar.** El contenedor pasa a la presentación todo lo que expone el gancho, pero `SignInForm` solo dibuja
   resumen, `Alert`, avisos y botón; `liveMessage` y `countdown` son de la tarea 4 (SS43 a SS48), como fija su lista de archivos. Mientras tanto un `429` muestra el texto fijo
   del `Alert` sin número.
4. **Aviso de llegada.** Pasa tal cual del provisional: `Alert` `info` después del `h1`. `NOTICE_TEXT` se mudó de `routes.tsx` al contenedor. La variante `warning` y el ocultarse
   al primer envío son de la tarea 4 (E7).
5. **`routes.test.tsx`.** La prueba «the placeholders offer no field» ya no vale para el inicio de sesión; pasó a verificación e inscripción (siguen provisionales) y se agregó una
   que comprueba que la ruta de inicio de sesión dibuja el formulario.
6. **SS59 en pantalla es débil.** Antes de que llegue el secreto, `getEnrollment()` es `undefined` tanto con borrador como sin él; la prueba de pantalla comprueba que con
   `second-factor-required` no hay inscripción posible. La que distingue el borrador está en `access-session.test.ts` (tarea 1).
7. **Sin fila de la puerta de accesibilidad todavía.** La matriz no tiene filas del inicio de sesión hasta la tarea 4. Para no dejar la pantalla sin revisión de axe, se corrió una vez
   `@axe-core/playwright` desde un guion fuera del repositorio contra el simulador y la compilación (`vite preview`): 4 estados (inicial, resumen, contraseña visible,
   credenciales no aceptadas) por 2 temas sin violaciones; el primer `Tab` cae en el identificador; el foco va al resumen y al `Alert`. No es evidencia de la puerta.

### Verificación de cierre

Cada parte sobre su propio árbol (3a con los archivos de 3b apartados, sin `git stash`). U de 3a y 3b con `--maxWorkers=4` y PR con
`VITEST_MAX_WORKERS=4 pnpm turbo run lint typecheck test build --filter=!@confia/api --concurrency=1 --env-mode=loose`.
**Por qué se limitaron los trabajadores.** Con el número por defecto, U falló dos veces en 3a con tiempos agotados de 5 s en pruebas ajenas a la tarea y distintas cada vez
(la primera: `use-access-form.test.tsx > focuses the alert when the failure has no field…`; la segunda: 7 pruebas de `use-access-form`, `routes`, `CopyButton`,
`SignedInContainer`, `BootScreen` y `SignedInPage`), y PR falló dos veces por lo mismo (11 y 4 fallos). Ninguno se repitió en la misma prueba; el equipo tenía 40 % de CPU
ocupada por procesos ajenos. Con 4 trabajadores todo pasó a la primera.

| Suite | 3a | 3b |
|---|---|---|
| U | 57 archivos, 579 pruebas y 1 `todo`, cobertura sobre el umbral | 58 archivos, 591 pruebas y 1 `todo` |
| T | 9 archivos, 286 pruebas | 9 archivos, 286 pruebas |
| E (`--workers=2`) | 37 de 37 | 37 de 37 |
| PR | 18 de 18 | 18 de 18 |

`pnpm install --frozen-lockfile --prefer-offline` pasa con el importador editado y `pnpm why react` muestra una sola versión. Guarda de `*.spec.*` vacía y
`git diff --name-only cb3ded9 -- apps/api packages/ui packages/contracts` vacío en ambas partes.


## Tarea 4: `sign-in-states-and-journey`

### Medida (regla de las 800 líneas), 2026-11-08

Medida con `git add -N .`, `git reset`, `git add -A` y `git diff --cached --numstat -M 2ceaa46` (sin `openspec/`, archivo de bloqueo ni código generado) antes de comprometer:
636 adiciones y 20 eliminaciones, **656 líneas**. Bajo 800: sin excepción y sin corte (4a / 4b no se aplican). Medida final sobre los dos commits: 639 adiciones y 19 eliminaciones, **658**.

### Evidencia del ciclo TDD

| Paso | ROJO observado | Causa observada | VERDE |
|---|---|---|---|
| Contenedor y presentación (jsdom) | Sí, antes del código de producción | 5 de 15 pruebas fallan: SS43 `Unable to find an element with the text: Puede intentar de nuevo en 15 s.`; SS44 `expected [ Array(1) ] to have a length of 2 but got 1`; SS48 `Unable to find an element with the text: Verificando sus datos…`; SS60 (tono de advertencia) y E7 (el aviso sigue tras el primer envío). En `SignInForm.test.tsx`, 6 fallos (`Objects are not valid as a React child (found: object with keys {message, tone})` y la región ausente) | 60 pruebas de `sign-in` |
| Simulador | Sí | `expected 400 to be 200` al aplicar `validation-failed` e `internal-error`: el preset no existía | 55 pruebas de `e2e/mock-api` |
| Matriz de la puerta | Sí | `expected [] to deeply equal [ 'initial', 'error-summary', … ]` | 32 pruebas de `e2e/support` |
| Recorrido | Sí | `Error: No tests found.` para `sign-in.e2e.ts` | 3 recorridos y 16 evaluaciones de la puerta |

**Ya en verde sin código nuevo (fijan comportamiento de las tareas 2 y 3, no hubo rojo posible):** SS41 (`400` por campo), SS47 (`503`), SS49 (red), SS50 y AW54 (`500` con traza), AW49, AW64 y el aviso de cada uno de los cuatro motivos de SS60. Se dejan escritas porque el requisito de la tarea las nombra.
**Código de apoyo escrito antes de su prueba:** `headers` opcional en `Reply` de `src/test/msw-server.ts` (para dar `Retry-After` al `429` de SS43); es solo de prueba.

### Demostraciones deliberadas (tarea 4.3)

Procedimiento: copia a `$CLAUDE_JOB_DIR/tmp/demo`, ruptura, rojo por aserción, restauración desde la copia y `cmp` sin salida.

| # | Ruptura | Rojo observado | `cmp` |
|---|---|---|---|
| 1 | El número de la cuenta regresiva dentro del texto del `Alert` (`ProblemAlert message={…+countdown.remaining}`) | SS44: `expected [ …(16) ] to have a length of 2 but got 16`; además SS43 | idéntico |
| 2 | Un aviso que mueve el foco (`useEffect` que enfoca el `Alert` del aviso) | 6 fallos de SS60: `expect(element).not.toHaveFocus()` en los cuatro motivos y en `SignInForm`, y `expect(element).toHaveFocus()` (el `h1`) | idéntico |

**Una demostración encontró una prueba débil.** La primera vez, la demostración 1 **no** puso en rojo SS44: el bucle de los 15 segundos era síncrono y `MutationObserver` entrega en microtareas, así que los 15 cambios llegaban en un solo lote. Se corrigió la prueba (`await act(async …)` por segundo) y la ruptura dio el rojo de arriba. Se repitió tras cambiar la lectura de las regiones, con el mismo resultado.

### Desviaciones y decisiones

1. **Región `polite` sin `role="status"`.** Es un `<p aria-live="polite" aria-atomic="true">` siempre presente. Con `role="status"`, `src/app/BootGate.test.tsx:47` (fuera de la superficie de la tarea) falla porque exige que la pantalla de inicio de sesión no tenga ningún `status`. Se evitó tocar ese archivo.
2. **Aviso por `restricted-expired` en la puerta: no es posible aún.** Con ese preset el inicio de sesión lleva a la verificación, que sigue siendo provisional hasta la tarea 5, y el aviso solo aparece al enviar el código. La fila «con aviso» usa un `refresh` rechazado con `401 session-expired` (`page.route`), que lleva al inicio de sesión con el aviso de sesión vencida. El preset `restricted-expired` queda para los recorridos de la tarea 6.
3. **Presets de la puerta.** `GateCombination` gana `preset?` y `checkCombination` recibe `scenarioId` para aplicarlo antes de `arrange` (`a11y-report.ts`, `a11y-gate.ts`, `gate.e2e.ts`; todos bajo `e2e/`). Las aserciones de total pasaron de `MINIMUM.length * 2` a `MATRIX.length * 2` en `a11y-report.test.ts` y `gate.e2e.ts`, porque la matriz tiene ahora 14 combinaciones y 28 evaluaciones; el mínimo sigue en 6.
4. **Aviso del `already-enrolled` en jsdom.** `startEnrollment` no termina la sesión ante `409`; lo hace el contenedor de inscripción de la tarea 7. La prueba llama a `discardSecondFactor("second-factor-already-enrolled")` como lo hará ese contenedor.
5. **`notice` pasa a `{ message, tone }`.** El tono `warning` es solo para `second-factor-already-enrolled` (E7). La cuenta regresiva se dibuja en un `<p>` fuera de toda región y de `Alert`.
6. **Lectura de las regiones activas en SS44.** `queryAllByText` con una función de coincidencia que ve cada elemento, porque la regla del repositorio prohíbe `querySelector*` y `getElementsByTagName`, y ninguna consulta por rol lista los `aria-live`.
7. **R4 decidido.** La región de espera se vacía en el mismo commit en que aparece el aviso; el observador ve exactamente dos cambios desde la aparición del aviso hasta el fin de la espera (`snapshots.slice(first)` de longitud 2).
8. **4.4 (medida de CI, R6) queda pendiente** hasta que el propietario empuje. El recorrido de 31 s (SS48) tarda unos 33 s; la suite E completa midió 1,1 min en esta máquina con 2 trabajadores.

### Verificación de cierre

| Suite | Resultado |
|---|---|
| U `pnpm --filter @confia/admin-web test` | 59 archivos, 615 pruebas y 1 `todo`, cobertura sobre el umbral |
| T `pnpm --filter ./tooling test` | 9 archivos, 286 pruebas |
| E `pnpm --filter @confia/admin-web test:e2e` | 56 de 56 (37 previas, 3 recorridos de inicio de sesión, 16 evaluaciones de la puerta) |
| PR `pnpm turbo run lint typecheck test build --filter=!@confia/api --concurrency=1` | 18 de 18 |

Guarda de `*.spec.*` vacía y `git diff --name-only 2ceaa46 -- apps/api packages/ui packages/contracts` vacío. Sin dependencias nuevas.


## Tarea 5: `second-factor-screen`

### Medida (regla de las 800 líneas), 2026-11-08

Medida con `git add -N .`, `git reset`, `git add -A` y `git diff --cached --numstat -M 60d4bf9` (sin `openspec/`, archivo de bloqueo ni código generado), antes de comprometer:
844 adiciones y 12 eliminaciones, **856 líneas**. Está entre 800 y 880: se pidió al propietario una excepción explícita, que concedió el 2026-11-09 (un solo PR). El corte
predefinido, no aplicado, por archivos completos mediría: 5a (esquema, presentación, sus pruebas y las siete claves de `secondFactor.*`) **431** contra `60d4bf9`; 5b (contenedor, su prueba,
`routes.tsx` y `routes.test.tsx`) **425** contra el commit de 5a.

### Evidencia del ciclo TDD

| Paso | ROJO observado | Causa observada | VERDE |
|---|---|---|---|
| Esquema y presentación | Sí, antes del código de producción | `Failed to resolve import "./second-factor-schema.ts"` y `"./SecondFactorForm.tsx"` (2 archivos fallidos, ninguna prueba ejecutada) | 56 pruebas con contenedor y rutas |
| Contenedor y rutas | Sí | 15 pruebas fallan por comportamiento: `Unable to find an accessible element with the role "textbox" and name /Código de verificación/` (y `"button"` con «Usar un código de recuperación»), y `routes.test.tsx` `expected  to have a length of 1 but got +0` (la ruta aún dibujaba el provisional). SS28, SS31 y el resto ya pasaban con el provisional | 56 pruebas |

El supuesto de 5.1 se cumplió: `unwrap()` y `.regex()` funcionan sobre los esquemas generados de Zod 4.6.6 sin importar `zod` en la app.
No hubo código de producción antes de su prueba.

### Demostraciones deliberadas (tarea 5.3)

Procedimiento: copia a `$CLAUDE_JOB_DIR/tmp/demo`, ruptura, rojo por aserción, restauración desde la copia y `cmp` sin salida.

| # | Ruptura | Rojo observado | `cmp` |
|---|---|---|---|
| 1 | Quitar `key={mode}` del contenedor | 2 fallos: SS64 (`expect(element).toHaveFocus()`) y SS65 (`expect(element).toHaveValue()`: el valor del otro modo sobrevive) | idéntico |
| 2 | `normalizeCode` con `/\s/g` en lugar de quitar también los de ancho cero | SS62 en el esquema (`expected '1\u200b2…' to be '123456'`) y en el contenedor (`Expected: "123456"`, recibido con los caracteres de ancho cero) | idéntico |

La primera vez la ruptura 2 se escribió mal con `sed` (el escape `\s` quedó como `s`) y el rojo era de otra causa; se restauró, se confirmó con `cmp` y se repitió bien.

### Desviaciones y decisiones

1. **Ambos campos declarados en el gancho.** `useAccessForm` recibe `code` y `recoveryCode` (cada uno con su normalización) y `validate`/`submit` miran solo el del modo; solo uno se monta. Con `key={mode}` el formulario se reinicia al cambiar de modo, así que la demostración 1 es observable.
2. **`SecondFactorEntry` dentro de `SecondFactorContainer.tsx`.** El contenedor guarda `mode` y `changedByPerson`; el componente interior, que llama al gancho, lleva la `key`. Así no se necesita un archivo más.
3. **Foco en el campo nuevo.** `SecondFactorForm` lo enfoca en su propio efecto, que corre después del de `ScreenLayout` (el de un hijo corre antes que el del padre); si el efecto fuera de un descendiente, el `h1` le quitaría el foco.
4. **Región `polite`.** Mismo `<p aria-live="polite" aria-atomic="true">` sin `role="status"` que el inicio de sesión (desviación 1 de la tarea 4).
5. **`routes.test.tsx`.** La prueba «the placeholders offer no field» queda solo para la inscripción y se agregó una que comprueba que la verificación dibuja su formulario.
6. **Textos.** Las siete claves de `secondFactor.*` son las de la sección 8; ninguna fuera de ella. `secondFactor.waiting`, `problem.secondFactorInvalid` y `problem.recoveryInvalid` ya existían.
7. **Sin fila de la puerta de accesibilidad todavía.** Las filas de la verificación son de la tarea 6 (`a11y-matrix.ts` no está en la lista de la tarea 5).

### Verificación (árbol sin comprometer)

| Suite | Resultado |
|---|---|
| U `pnpm --filter @confia/admin-web test` | 62 archivos, 659 pruebas y 1 `todo` |
| T `pnpm --filter ./tooling test` | 9 archivos, 286 pruebas |
| E `pnpm --filter @confia/admin-web test:e2e` | 56 de 56 |
| PR `pnpm turbo run lint typecheck test build --filter=!@confia/api --concurrency=1` | 18 de 18 (14 reutilizadas de la caché de turbo, que depende de las entradas) |

Guarda de `*.spec.*` vacía y `git diff --name-only 60d4bf9 -- apps/api packages/ui packages/contracts` vacío (índice y árbol). Sin dependencias nuevas. **Excepción del propietario (2026-11-09):** la tarea 5 se entrega como un solo PR de **856 líneas efectivas** (844 adiciones y 12 eliminaciones contra `60d4bf9`), dentro del umbral de 880; sin corte 5a / 5b.

### Corrección de la revisión R3-001 (2026-11-09)

La revisión nativa aprobó la tarea 5 y señaló un defecto (advertencia): el cambio de modo y «Volver al inicio de sesión» seguían activos con una verificación en vuelo. Cambiar de modo
remonta el formulario (`key={mode}`) con un gancho nuevo, así que se podía enviar una segunda prueba, y la respuesta huérfana navegaba después de que la persona se había ido.

- **ROJO observado:** el cambio de modo quitaba el campo del código (`Unable to find an accessible element with the role "textbox" and name /Código de verificación/`) y una respuesta liberada tras salir de la pantalla llevaba a `/` (`expected '/' to be '/iniciar-sesion'`). Una primera versión de la segunda prueba esperaba una petición registrada por MSW con la respuesta retenida antes del transporte; falló por esa causa y se corrigió a esperar el `aria-busy`.
- **VERDE:** mientras `form.submitting`, el cambio de modo y el regreso se ignoran (sin `disabled`: siguen enfocables y llevan `aria-disabled`, como el botón ocupado ignora una segunda activación); una respuesta que llega con el componente desmontado no navega ni descarta nada (`mounted` en un efecto, válido con `StrictMode`).
- **Pruebas:** 2 nuevas en `SecondFactorContainer.test.tsx` (con `completeSecondFactor` retenida). U 62 archivos y 661 pruebas y 1 `todo`; E 56 de 56; PR 18 de 18.



## Tarea 6: `second-factor-journeys`

### Medida (regla de las 800 líneas), 2026-11-08

Medida con `git add -A` y `git diff --cached --numstat -M 5199e0c` (sin `openspec/`, archivo de bloqueo ni código generado) antes de comprometer: 403 adiciones y 6 eliminaciones, **409 líneas**.
Bajo 800: sin excepción y sin corte. De ellas, 41 corresponden a `e2e/specs/build/dist.e2e.ts` (ver desviación 1).

### Evidencia del ciclo TDD

| Paso | ROJO observado | Causa observada | VERDE |
|---|---|---|---|
| Control de cuenta (`server.test.ts`) | Sí, antes del código | 3 de 5 pruebas: `expected 404 to be 200` en `GET /__control/account/<id>` (forma, clave aceptada por el modelo, códigos vigentes) | 31 pruebas del servidor |
| Código de recuperación en mayúsculas (`session-model.test.ts`) | Sí | `expected 401 to be 200`: la pantalla envía el código en mayúsculas (SS65) y el modelo guardaba el código tal como lo repartió, en minúsculas | 1 prueba nueva |
| Filas de la puerta (`a11y-report.test.ts`) | Sí | `expected [] to deeply equal [ [ 'totp', …(1) ], …(2) ]` | 3 filas y 6 evaluaciones en dos temas |
| Recorridos y `dist.e2e.ts` | **No hubo rojo de navegador.** Los recorridos se escribieron después del control de cuenta, ya en verde, y no se corrió `No tests found` | 11 de 11 en verde a la primera | 68 de 68 en E |

**Ya en verde sin código nuevo:** las pruebas de `Host` ajeno y de id inválido o método distinto del control de cuenta (el control entero ya pasa por `isLocal`, y la ruta ya devolvía `404`); la prueba de `dist` (el build nunca contuvo `__control`). Se dejan porque el requisito las nombra y la demostración 1 prueba que la de `Host` puede fallar.

### Demostraciones deliberadas (tarea 6.3)

Procedimiento: copia a `$CLAUDE_JOB_DIR/tmp/demo`, ruptura, rojo por aserción, restauración desde la copia y `cmp` sin salida.

| # | Ruptura | Rojo observado | `cmp` |
|---|---|---|---|
| 1 | Control de cuenta sin comprobar `Host` (`isLocal` omitido para `/__control/account/`) | `AssertionError: expected [ 'evil.example', 200, true ] to deeply equal [ 'evil.example', 403, false ]`, y solo esa prueba (1 de 31) | idéntico |
| 2 | `className="hidden"` (`display: none`) en el campo del código de `SecondFactorForm.tsx` | `expect(locator).toBeFocused() failed` sobre el campo `Código de verificación` (`element(s) not found`), en el paso que sigue a la pantalla de verificación de `keyboard.e2e.ts` | idéntico |

### Desviaciones y decisiones

1. **`dist.e2e.ts` no estaba versionado.** `.gitignore` ignora `build/` y `e2e/specs/build/dist.e2e.ts` nunca se comprometió (`git log --all` sin historia; el árbol principal no lo tiene). Las suites E de las partes anteriores lo ejecutaban solo en este árbol. Se agregó con `git add -f` y se le sumó la comprobación de `__control`. **Pendiente del propietario:** una excepción en `.gitignore` (`!apps/admin-web/e2e/specs/build/`), que queda fuera de la superficie permitida.
2. **Defecto del simulador, no de `src/`.** Los códigos de recuperación del modelo salen en minúsculas hexadecimales y la pantalla los envía en mayúsculas (SS65), así que el recorrido de recuperación habría sido rechazado. El modelo compara ahora sin distinguir mayúsculas (`Map` con clave en mayúsculas) y `recoveryCodes()` devuelve los códigos como se repartieron. No se tocó `src/`.
3. **Control de cuenta.** `GET /__control/account/:id` devuelve `{ secret, recoveryCodes }` (la forma de `EnrollmentResponse`), solo con las comprobaciones de `Host` y de par de bucle de todo `/__control/*`; `:id` es un id de escenario; nada de esto está en `dist` (comprobado por `dist.e2e.ts`).
4. **Fila `code-rejected` por el modo de recuperación.** La puerta no conoce el id del escenario en `arrange`, así que el rechazo usa un código de recuperación que no puede ser hexadecimal. Un código TOTP fijo tendría una probabilidad pequeña de coincidir con el vigente.
5. **El recuento de la puerta sigue la matriz.** La prueba de las 8 filas del inicio de sesión comparaba con `(6 + 8) * 2` (igual al tamaño de la matriz de entonces); ahora compara con `MATRIX.length * THEMES.length`, igual que la de la tarea 4. La matriz tiene 17 combinaciones y 34 evaluaciones.
6. **Transitorio de la puerta.** Una vez, `code-rejected` en tema claro tardó 12,2 s en la corrida focalizada; repetida, 2,0 s por tema. No se reprodujo.
7. **Informe.** Los recorridos de verificación llevan la anotación `api: simulated; not the real stack` en su `describe`; los títulos dicen «against the simulated API».
8. **T no se tocó:** `tooling/` no cambió; se corrió de todos modos.

### Verificación de cierre

| Suite | Resultado |
|---|---|
| U `pnpm --filter @confia/admin-web test` | 62 archivos, 669 pruebas y 1 `todo` |
| T `pnpm --filter ./tooling test` | 9 archivos, 286 pruebas |
| E `pnpm --filter @confia/admin-web test:e2e` | 68 de 68 (56 previas, 4 de verificación, 1 de teclado, 1 de `dist`, 6 evaluaciones de la puerta) |
| PR `pnpm turbo run lint typecheck test build --filter=!@confia/api --concurrency=1` | 18 de 18 (14 de la caché de turbo) |

Guarda de `*.spec.*` vacía y `git diff --name-only 5199e0c -- apps/api packages/ui packages/contracts` vacío. Sin dependencias nuevas.


## Tarea 7: `enrollment-secret-and-qr`

### Dependencias (subtarea 7.1), 2026-11-09

| Paquete | Versión | Publicada | Sección | Licencia |
|---|---|---|---|---|
| `qrcode.react` | 4.2.0 | 2024-12-11 | `dependencies` de la app, por catálogo | ISC; par `react ^16.8 \|\| … \|\| ^19`, sin dependencias de ejecución |
| `jsqr` | 1.4.0 | 2021-04-24 | `devDependencies` de la app, por catálogo | Apache-2.0; sin dependencias |

Ambas son las últimas de su serie y llevan más de 14 días publicadas. `pnpm audit --audit-level high` termina con código 0 (quedan 2 avisos moderados ya existentes, de `glob` y `minimatch` por `packages/ui` y `packages/config`, ajenos a estas entradas); `pnpm why react` muestra una sola `react@19.3.0`; `pnpm install --frozen-lockfile --prefer-offline` pasa. `jsqr` queda declarado sin uso hasta el PR 8 (SS81).

### Medida (regla de las 800 líneas), 2026-11-09

Medida con `git reset`, `git add -A` y `git diff --cached --numstat -M af20884` (sin `openspec/`, archivo de bloqueo ni código generado): 661 adiciones y 26 eliminaciones, **687 líneas**. Bajo 800: sin excepción y sin corte 7a / 7b.

### Evidencia del ciclo TDD

| Paso | ROJO observado | Causa observada | VERDE |
|---|---|---|---|
| SS83 (`TotpQrCode.test.tsx`), antes de escribir `TotpQrCode` | Sí | `Failed to resolve import "./TotpQrCode.tsx"` | 2 pruebas: **`QRCodeSVG` cumple SS83** con `level="M"`, `marginSize={4}`, `role="img"` y `title`: el elemento tiene el nombre del catálogo y ningún elemento del subárbol lleva `style`. No hubo punto de detención |
| URI y clave | Sí | `Failed to resolve import "./otpauth-uri.ts"` y `"./manual-key.ts"` | 5 pruebas |
| `SecretStep` | Sí (corrida aparte quitando el archivo) | `Failed to resolve import "./SecretStep.tsx"`. **Escribí el componente en el mismo paso que su prueba**; el rojo se registró después, moviendo el archivo fuera, y se restauró | 4 pruebas |
| `EnrollmentContainer` y ruta | Sí | 11 de 12 fallan por comportamiento (la ruta aún dibujaba el provisional): `Unable to find role="heading" and name "Escanee el código QR"`, `Unable to find role="alert"`, `Unable to find an element with the text: Preparando su verificación…`. SS29 ya pasaba con la guarda | 12 pruebas más la de `routes.test.tsx` |
| Preset `enrollment-expired` | Sí | `expected 400 to be 200` (preset desconocido) | 32 pruebas del simulador |
| 3 filas de la puerta | Sí | `expected [] to deeply equal [ [ 'loading', …(1) ], …(2) ]` | 14 pruebas |
| Recorrido `enrollment.e2e.ts` | **No hubo rojo de navegador**: se escribió con la pantalla ya en verde | 2 de 2 en verde | 76 de 76 en E |

### Demostraciones deliberadas (tarea 7.6)

Procedimiento: copia a `$CLAUDE_JOB_DIR/tmp/demo`, ruptura, rojo por aserción, restauración desde la copia y `cmp` sin salida.

| # | Ruptura | Rojo observado | `cmp` |
|---|---|---|---|
| 1 | `style={{ width: 200 }}` en `QRCodeSVG` | SS83: `expected '<svg height="128" width="128" viewBox…' not to match /\sstyle=/`, y la de igualdad con la salida de la biblioteca | idéntico |
| 2 | Promesa de inscripción sin memorizar en `access-session.ts` (se quitó el `return starting.promise`) | SS75 bajo `StrictMode`: `expected [ …(2) ] to have a length of 1 but got 2` | idéntico |
| 3 | *Adicional:* clave sin agrupar (`join("")`) | SS77: `expected 'JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP' to be 'JBSW Y3DP EHPK 3PXP …'` en `manual-key.test.ts` y en `SecretStep.test.tsx` | idéntico |

### Desviaciones y decisiones

1. **«Continuar» avanza el borrador, pero el contenedor solo dibuja `secret`.** Los pasos `recovery-codes` y `first-code` son de la tarea 8; hasta que se fusione, pulsar «Continuar» deja el contenedor sin contenido de sección. La cadena 7 a 8 se fusiona en orden.
2. **El `h2` toma el foco y el `h1` ya no.** Al aparecer la sección, su `h2` enfoca (E10). Por eso la prueba de SS32 de `routes.test.tsx` espera el foco en el `h2` para la inscripción; el `h1` lo recibe solo al montar la pantalla, durante la carga.
3. **`SignInContainer.states.test.tsx`** (fuera de la lista de la tarea pero dentro de `features/identity/**`): su prueba de SS60 simulaba a mano lo que ahora hace el contenedor real ante `409` (descartar con el aviso y volver); se quitó esa simulación y se espera el aviso.
4. **`strict` en `renderApp`** (`src/test/render-app.tsx`): envuelve la aplicación en `StrictMode` para SS75.
5. **`account.ts`** del simulador expone también `secret` en base32 (`TestAccount.secret`), para comparar con el portapapeles.
6. **Tamaño del QR.** Se dibuja con el tamaño por defecto de la biblioteca (128 px): el diseño prohíbe `style` y `imageSettings`, y SS83 compara con la salida de la biblioteca. Si el propietario lo quiere más grande, se decide aparte.
7. **Contenedor con carga y error en línea.** Sin archivos de presentación aparte para ellos; usan `Spinner` y `ErrorState` de `@confia/ui`. El detalle del error reutiliza `problem.*`; el identificador de traza se muestra con `CopyButton`.
8. **Textos.** Las siete claves son las de la sección 8; ninguna fuera de ella (`enrollment.title` ya existía).
9. **`routes.test.tsx`:** la prueba del provisional («sin campo») se reemplazó por una que comprueba la sección con su QR. `AccessPlaceholder` ya no se usa; su retiro es de la tarea 9.

### Verificación de cierre

| Suite | Resultado |
|---|---|
| U `pnpm --filter @confia/admin-web test` | 67 archivos, 696 pruebas y 1 `todo` |
| T `pnpm --filter ./tooling test` | 9 archivos, 286 pruebas |
| E `pnpm --filter @confia/admin-web test:e2e` | 76 de 76 (68 previas, 2 recorridos de inscripción, 6 evaluaciones de la puerta) |
| PR `pnpm turbo run lint typecheck test build --filter=!@confia/api --concurrency=1` | 18 de 18 |

Guarda de `*.spec.*` vacía y `git diff --name-only af20884 -- apps/api packages/ui packages/contracts` vacío (índice y árbol). Sin caracteres invisibles en los 24 archivos.


## Tarea 8: `enrollment-codes-and-activation`

### Corte aplicado, 2026-11-09

La tarea 8 se parte en **8a** `codes-first-code-and-activation` (rama `change/admin-web-staff-sign-in-8-enrollment-codes-and-activation`) y **8b** `simulator-and-qr-browser-tests` (rama `change/admin-web-staff-sign-in-8b-enrollment-browser-checks`, desde el commit de 8a). Esta sección documenta 8a; 8b agrega la suya al terminar.

### Medida de 8a (regla de las 800 líneas)

Medida con `git reset`, `git add -A` y `git diff --cached --numstat -M b923a63` (sin `openspec/`, archivo de bloqueo ni código generado): 781 adiciones y 11 eliminaciones, **792 líneas**. Bajo 800: sin excepción. Se midió el trabajo de 8a antes de escribir 8b porque la estimación de 8b (simulador, matriz y recorrido del QR) llevaba el total de la tarea muy por encima de 880.

### Evidencia del ciclo TDD

| Paso | ROJO observado | Causa observada | VERDE |
|---|---|---|---|
| `RecoveryCodesStep.test.tsx`, `FirstCodeStep.test.tsx` | Sí, antes del código | `Failed to resolve import "./RecoveryCodesStep.tsx"` y `"./FirstCodeStep.tsx"` | 7 y 7 pruebas |
| `EnrollmentContainer.test.tsx` (nuevas SS89 a SS94, SS96 a SS99) | Sí, antes del código | 12 de 12 fallan por comportamiento: `Unable to find role="heading" and name "Guarde sus códigos de recuperación"` (tras «Continuar» solo se dibujaba el vacío) | 24 pruebas |

### Demostraciones deliberadas (tarea 8.3, las de 8a)

Procedimiento: copia a `$CLAUDE_JOB_DIR/tmp/demo`, ruptura, rojo por aserción, restauración desde la copia y `cmp` sin salida.

| # | Ruptura | Rojo observado | `cmp` |
|---|---|---|---|
| 1 | *Adicional:* `disabled={!confirmed}` en «Continuar» de `RecoveryCodesStep.tsx` | SS90 en la presentación y en el contenedor: `Error: expect(element).toBeEnabled()` (2 de 2) | idéntico |
| 3 | *Adicional:* `advance()` de `enrollment-draft.ts` conserva los códigos al pasar a `first-code` | SS93 del contenedor: `AssertionError: expected [ '8fef6ed967', '1d007b855f', …(8) ] to be undefined` | idéntico |

La demostración 2 (matriz sin las filas nuevas) es de 8b.

### Desviaciones y decisiones

1. **`SecretStep.tsx` no cambia.** La lista de la tarea lo nombra para que «Continuar» salte a `first-code` sin códigos, pero `advance()` del borrador (tarea 1) ya lo hace: la prueba de SS94 lo demuestra con el contenedor.
2. **Archivos fuera de la lista de la tarea, dentro de la superficie permitida.** `enrollment/enrollment-controls.ts` (los dos `controlId` fijos), `features/identity/routes.tsx` (el contenedor recibe `homePath`) y `src/test/setup.ts`: jsdom no tiene `ResizeObserver`, que el `Checkbox` de `@confia/ui` usa dentro de un `form`, así que se agregó un sustituto que no mide nada.
3. **Navegación tras activar.** El `200` borra el borrador y desmonta la sección antes de que su propia respuesta llegue, de modo que «estar montado» no dice si la persona sigue ahí. La sección compara la ruta de entrada con la actual (`useRouter`): navega al inicio o al inicio de sesión solo si la persona sigue en ella. La primera versión usaba una guarda de montaje y la prueba de SS96 la puso en rojo (el recorrido se quedaba en la carga).
4. **Casilla y resumen.** La confirmación y el «Continuar» sin marcar son estado local de `RecoveryCodesEntry`; no usan `useAccessForm`, cuyos valores son cadenas con normalización. Se muestran el resumen (con el foco y el enlace) y el mensaje bajo la casilla, que lleva «obligatorio» por el `FormField`.
5. **El primer código repite el QR y la clave.** El diseño (E10) y SS97 lo piden; «Ver de nuevo el código QR» vuelve a `secret` sin petición (SS94).
6. **Textos.** Las ocho claves nuevas son las de la sección 8 (`codes.title`, `intro`, `confirm`, `confirmRequired`, `firstCode.title`, `submit`, `reviewSecret`); ninguna fuera de ella. Se reutilizan `secondFactor.code`, `secondFactor.codeFormat`, `secondFactor.waiting` y `problem.secondFactorInvalid`.
7. **`startEnrollment` sin `catch`.** No se corrigió: las operaciones de `shared/api` devuelven resultados en lugar de lanzar, y la prueba que lo justificaría no existe todavía.
8. **Sin prueba de respuesta tardía.** La rama de `stillHere()` falso (la persona se fue con la petición en vuelo) no tiene prueba propia: hacerla exige simular una activación que llega después de que la guarda descartó el token, un caso del módulo de sesión que no se investigó aquí.

### Verificación de cierre de 8a

| Suite | Resultado |
|---|---|
| U `pnpm --filter @confia/admin-web test` | 69 archivos, 724 pruebas y 1 `todo` |
| E `pnpm --filter @confia/admin-web test:e2e` | 76 de 76 |
| PR `pnpm turbo run lint typecheck test build --filter=!@confia/api --concurrency=1` | 18 de 18 (14 de la caché de turbo) |

Guarda de `*.spec.*` vacía y `git diff --name-only b923a63 -- apps/api packages/ui packages/contracts` vacío. Sin caracteres invisibles en los 10 archivos. T no se tocó (`tooling/` sin cambios).

### Parte 8b `simulator-and-qr-browser-tests` (rama `change/admin-web-staff-sign-in-8b-enrollment-browser-checks`)

Medida con `git reset`, `git add -A` y `git diff --cached --numstat -M 6ff4c32` (sin `openspec/`): 265 adiciones y 22 eliminaciones, **287 líneas**. La tarea completa suma 792 + 287 = 1 079 contra `b923a63`, sobre las 880 del umbral del propietario: por eso se aplicó el corte.

| Paso | ROJO observado | Causa observada | VERDE |
|---|---|---|---|
| Simulador (`session-model.test.ts` y `server.test.ts`) | Sí, antes del código | 2 pruebas: `expected 'VYP4P7CWNNUE62ST3WFBFBTA3YN57QOA' not to be 'VYP4P7CWNNUE62ST3WFBFBTA3YN57QOA'`: el secreto era idéntico entre inscripciones | 64 pruebas del simulador |
| Matriz y mínimo (`a11y-report.test.ts`) | Sí, antes del código | 5 pruebas: `expected [ { route: '/', …(1) }, …(5) ] to have a length of 22 but got 6`, más las filas de inscripción (`…(2)` contra `…(4)`) | 15 pruebas; 22 estados y 44 evaluaciones |
| Recorridos SS81, SS82, SS84, SS85, SS100 | **No hubo rojo de navegador** de entrada: la pantalla y el simulador ya estaban en verde cuando se escribieron. SS100 sí se puso en rojo con la demostración 3 | 5 de 5 en verde a la primera | 85 de 85 en E |

### Demostraciones deliberadas (tarea 8.3, las de 8b)

Procedimiento: copia a `$CLAUDE_JOB_DIR/tmp/demo`, ruptura, rojo por aserción, restauración desde la copia y `cmp` sin salida.

| # | Ruptura | Rojo observado | `cmp` |
|---|---|---|---|
| 2 | Matriz sin las filas `recovery-codes` y `first-code` | `Error: The matrix lacks: /iniciar-sesion/inscripcion [recovery-codes], /iniciar-sesion/inscripcion [first-code].` en la prueba del mínimo y en las de cada pantalla, y `AssertionError: expected [ [ 'loading', …(1) ], …(2) ] to deeply equal [ [ 'loading', …(1) ], …(4) ]` en la de inscripción (4 de 15) | idéntico |
| 4 | *Adicional:* `value={`${uri}x`}` en `TotpQrCode.tsx` | SS81: el texto decodificado difiere del URI (`Expected` y `Received` distan en la `x` final) | idéntico |
| 5 | *Adicional:* el simulador no vuelve a generar la clave tras la primera inscripción | SS100: `Error: the second secret differs from the first` (`Expected: true`, `Received: false`) | idéntico |

La demostración 1 (continuar deshabilitado) y la 3 de la tarea (códigos conservados) están en 8a.

### Desviaciones y decisiones de 8b

1. **El `SS78` de la tarea 7 cambió.** Leía la cuenta antes de iniciar sesión; con un secreto nuevo por inscripción, la lee cuando la pantalla ya muestra el suyo.
2. **Sin cambio en `server.ts`.** La lista de la tarea lo nombra, pero `enroll` vive en `session-model.ts`; el control de la cuenta ya leía el modelo. Se agregó una prueba de servidor (`server.test.ts`).
3. **Comparaciones sin imprimir secretos.** SS81 y SS100 comparan con un booleano y un mensaje, para que un fallo no escriba el secreto en el informe (regla 11). El `SS78` de la tarea 7 sigue comparando el texto del portapapeles directamente y un fallo lo imprimiría; es un secreto ficticio de la ejecución, pero queda anotado.
4. **`MINIMUM` son los 22 estados.** Se escribe a mano, aparte de la matriz, con los ocho del inicio de sesión, los tres de la verificación y los cinco de la inscripción además de los seis de la parte 1. Las pruebas que decían «el mínimo sigue en seis» ahora dicen que contiene cada pantalla.
5. **SS84.** Se leen los rellenos calculados de los dos `path` del SVG y se comprueba que el fondo cubre todo el dibujo y que los módulos empiezan a 4 unidades del borde; no se mide un contraste.
6. **SS85.** Cubre URL, cabeceras y cuerpos de todas las peticiones de una inscripción completa. El identificador aparece en una sola petición (el cuerpo del inicio de sesión).

### Verificación de cierre de 8b (y de la tarea 8)

| Suite | Resultado |
|---|---|
| U `pnpm --filter @confia/admin-web test` | 69 archivos, 727 pruebas y 1 `todo` |
| E `pnpm --filter @confia/admin-web test:e2e` | 85 de 85 (76 previas, 5 recorridos, 4 evaluaciones de la puerta de los dos estados nuevos) |
| PR `pnpm turbo run lint typecheck test build --filter=!@confia/api --concurrency=1` | 18 de 18 (14 de la caché de turbo) |

T no se corrió: `tooling/` no cambió en esta tarea. Guarda de `*.spec.*` vacía y `git diff --name-only b923a63 -- apps/api packages/ui packages/contracts` vacío. Sin caracteres invisibles en los archivos de 8b.
