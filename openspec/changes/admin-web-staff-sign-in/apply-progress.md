# Avance de aplicación: `admin-web-staff-sign-in`

- **Cambio:** `admin-web-staff-sign-in` (13b-1 de F0, parte 2 de 2)
- **Modo:** TDD estricto. Comandos por superficie según `tasks.md` (pnpm y Vitest); el `test_command` de Maven no se usa.
- **Última actualización:** 2026-11-07
- **Entrega:** PR apilados (`stacked-to-main`), 800 líneas efectivas por PR (`docs/15` §3), umbral de excepción 880

## Estado acumulado

| Tarea | Estado | Rama | Líneas efectivas |
|---|---|---|---|
| 1 `enrollment-draft-in-session` | **Hecha** (sin corte) | `change/admin-web-staff-sign-in-1-enrollment-draft-in-session` | 698 de 800 (base `ca98c03`) |
| 2 a 9 | Pendientes | | |

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
