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
