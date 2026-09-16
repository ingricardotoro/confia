---
description: Crea un módulo nuevo del backend con las cuatro capas hexagonales, sus permisos y sus pruebas
argument-hint: <nombre-del-modulo> "<responsabilidad en una frase>"
---

Vas a crear el módulo `$1` en el backend de CONFIA.

Antes de escribir nada:

1. Lee `CLAUDE.md` y `docs/01-arquitectura.md` seccion 4 para confirmar la estructura de capas.
2. Invoca la skill `confia-module-scaffold`.
3. Verifica que exista una especificación aprobada para este módulo en `openspec/`. Si no existe,
   **detente** y propón crear el cambio SDD primero.

Luego delega al agente `confia-backend-dev` la creación del módulo con:

- Las cuatro capas como paquetes Java: `domain`, `application`, `infrastructure` y `web` (la capa
  conceptual `interface` vive en `web` porque `interface` es palabra reservada de Java).
- La declaración del módulo y de su API pública para Spring Modulith.
- La declaración de permisos del módulo.
- Los DTO de entrada validados con Jakarta Bean Validation y los DTO de salida explícitos.
- El registro del módulo en el punto de entrada que corresponda (administrativo, portal o
  trabajador). Un módulo administrativo **nunca** se registra en el punto de entrada del portal.
- Las pruebas unitarias del dominio (JUnit y AssertJ) y las de integración con Testcontainers.
- Si el módulo crea tablas, la migración de Flyway la escribe `confia-database`.

Al terminar, ejecuta `./mvnw verify` en `apps/api` (incluye ArchUnit y Spring Modulith) y reporta
el árbol de archivos creado más los pasos pendientes.

Responsabilidad declarada del módulo: $2
