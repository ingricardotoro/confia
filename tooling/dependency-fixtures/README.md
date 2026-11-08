# Violaciones deliberadas de las reglas de dependencia

Cada archivo de esta carpeta rompe **a propósito** una de las reglas de
`.dependency-cruiser.cjs` (frontend-monorepo-and-contracts-pipeline, `design.md` decisión 8). La
prueba `tooling/src/dependency-rules.test.ts` exige que cada regla las rechace al menos una vez: una
regla que nunca rechaza nada no protege nada (ADR-0018). Las rutas imitan `apps/` y `packages/` para
que las mismas reglas que vigilan el árbol real las reconozcan.

Nunca se importan desde código real, y la verificación de tipos y el análisis estático las excluyen.

Las reglas de capas de `packages/ui` (`ui-*`, decisión 10 del cambio `design-system-foundations-and-a11y`) tienen sus
violaciones bajo `packages/ui/src/`, con los módulos mínimos que importan. `domain/accepted-imports.ts` es el caso
aceptado: un módulo de dominio que importa átomos, moléculas y un tipo del contrato no viola ninguna regla.

Las reglas de `apps/admin-web` (`apps-use-ui-public-entry`, `admin-presentation-is-pure`,
`admin-shared-never-imports-features` y `admin-token-store-is-private`, decisión D3 del cambio
`admin-web-foundations-and-session`) tienen sus violaciones bajo `apps/admin-web/src/`. Los módulos aceptados
(`uses-ui-public-entry.ts`, `features/identity/container.ts` y `features/identity/session/access-session.ts`) no violan
ninguna regla. Esa carpeta no es la aplicación real: solo tiene las rutas que las reglas reconocen.

El borrador de la inscripción (`session/enrollment-draft`, decisión E2 del cambio `admin-web-staff-sign-in`) es tan privado como el
almacén de tokens. `features/identity/enrollment/imports-enrollment-draft.ts` lo importa directamente y viola
`admin-token-store-is-private`. Los casos aceptados son `features/identity/enrollment/uses-session-index.ts`, que pasa por la entrada
pública `session/index.ts`, y esa misma entrada, que importa su propio borrador. `session/enrollment-draft.ts` y `session/index.ts` son
módulos mínimos para que esos imports resuelvan.
