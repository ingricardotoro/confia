# Avance de aplicación: `admin-web-staff-sign-in`

- **Cambio:** `admin-web-staff-sign-in` (13b-1 de F0, parte 2 de 2)
- **Modo:** TDD estricto. Comandos por superficie según `tasks.md` (pnpm y Vitest); el `test_command` de Maven no se usa.
- **Última actualización:** 2026-11-07
- **Entrega:** PR apilados (`stacked-to-main`), 800 líneas efectivas por PR (`docs/15` §3), umbral de excepción 880

## Estado acumulado

| Tarea | Estado | Rama | Líneas efectivas |
|---|---|---|---|
| 1 `enrollment-draft-in-session` | **Hecha** (sin corte) | `change/admin-web-staff-sign-in-1-enrollment-draft-in-session` | 698 de 800 (base `ca98c03`) |
| 2 `access-form-foundation` | **Hecha**, con corte 2a / 2b y excepción del propietario para 2a | 2a `change/admin-web-staff-sign-in-2-access-form-foundation`; 2b `change/admin-web-staff-sign-in-2b-access-form-feedback` | 2a 848 (base `152172b`, excepción aprobada 2026-11-07); 2b 443 (base `e226ce3`) |
| 3 a 9 | Pendientes | | |

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

