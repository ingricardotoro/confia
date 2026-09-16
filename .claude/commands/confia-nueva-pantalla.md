---
description: Construye una pantalla del panel o del portal siguiendo el sistema de diseño y los patrones de interacción
argument-hint: <panel|portal> <ruta> "<objetivo de la pantalla>"
---

Vas a construir una pantalla en la aplicación `$1`, en la ruta `$2`.

Antes de escribir código:

1. Lee `docs/ui-ux/00-principios-de-diseno.md`, `docs/ui-ux/01-tokens-de-diseno.md` y
   `docs/ui-ux/04-patrones-de-interaccion.md`.
2. Invoca la skill `confia-ui-screen-recipe`.
3. Identifica cuál de las tres preguntas responde esta pantalla: cuánto se debe, qué se pagó, o
   qué sigue. Si no responde ninguna, cuestiona si la pantalla debe existir.

Luego delega al agente `confia-frontend-dev`, exigiendo:

- Ruta con TanStack Router y parámetros de búsqueda tipados y validados.
- Datos con TanStack Query, con clave jerárquica y política de invalidación declarada.
- Si hay tabla: TanStack Table con paginación, orden y filtrado **en el servidor**.
- Si hay formulario: TanStack Form con el esquema Zod generado en `packages/contracts` desde el
  OpenAPI del backend.
- Separación entre contenedor y presentación.
- Los cuatro estados obligatorios: carga, vacío, error y éxito.
- Importes siempre con el componente de dinero del sistema de diseño, tal como llegan ya
  redondeados del servidor. Sin cálculo ni redondeo en el navegador.
- Ningún texto embebido: todo pasa por el catálogo de internacionalización.

Antes de reportar, ejecuta la verificación de accesibilidad y confirma cero violaciones críticas
o serias.

Objetivo declarado de la pantalla: $3
