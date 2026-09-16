---
description: Carga el contexto esencial del proyecto al iniciar una sesión de trabajo
---

Prepara el contexto de trabajo de CONFIA.

1. Lee `CLAUDE.md` y `docs/01-arquitectura.md`.
2. Ejecuta `mem_context` para recuperar el historial de sesiones previas.
3. Revisa `openspec/changes/` y reporta si hay un cambio activo y en qué fase está.
4. Revisa el estado del repositorio con `git status` y `git log --oneline -10`.

Luego responde en formato breve:

- **Cambio activo:** identificador y fase, o "ninguno".
- **Fase del roadmap:** en cuál estamos según `docs/09-roadmap-y-fases.md`.
- **Último trabajo:** qué se hizo en la sesión anterior.
- **Siguiente paso sugerido:** una sola recomendación concreta.

No empieces a implementar nada. Este comando solo carga contexto y reporta.
