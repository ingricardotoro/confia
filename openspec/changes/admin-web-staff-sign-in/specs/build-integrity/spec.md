# Delta para Integridad de la construcción

- **Estado:** escenarios separados el 2026-11-05 de `admin-web-foundations-and-session`, tras la resolución de Q1
- **Cambio:** `admin-web-staff-sign-in` (F0, cambio 13b-1, parte 2 de 2). Este delta es el que cierra el criterio de salida 8.

**Convenciones de este delta.**

- **Cómo se fusiona este delta.** Se archiva después de `admin-web-foundations-and-session`. Bajo `## MODIFIED Requirements`, un requisito con su encabezado
  exacto trae el cuerpo vigente y **solo los escenarios que esta parte agrega**. Al archivar, los escenarios que la parte 1 ya
  publicó se conservan y estos se suman. No se repiten porque un escenario vive en un solo cambio.
- `BX109` es nuevo: la parte 1 evalúa solo las pantallas que existen al terminarla, de modo que `BX74` se cumple allí sobre su tabla de
  seis estados, y este escenario exige la tabla completa.
- Los valores fijados por el diseño son los del delta de la parte 1.

## MODIFIED Requirements

### Requisito: La puerta de accesibilidad se ejecuta sobre rutas renderizadas de `apps/admin-web`, en los dos temas, y falla con cero rutas

El sistema DEBE ejecutar axe con Playwright y `@axe-core/playwright` sobre una matriz de ruta, estado y tema de
`apps/admin-web` renderizada con `vite preview` (`docs/ui-ux/03` §9; ADR-0028, «axe sobre rutas renderizadas»; ADR-0018). Cada
combinación DEBE evaluarse en tema claro y en tema oscuro, emulando la preferencia del sistema, y DEBE analizarse solo después
de que el contenido esperado de su estado sea visible; analizar durante un esqueleto de carga produce un falso verde. La
matriz mínima cubre cada pantalla en estos estados:

| Pantalla | Estados mínimos |
|---|---|
| Arranque | restaurando; error de red con reintento |
| Inicio de sesión | inicial; con resumen de errores de campo; credenciales no aceptadas; límite con cuenta regresiva; servicio ocupado; enviando; contraseña visible; con aviso de llegada |
| Verificación | código TOTP; código de recuperación; código no aceptado |
| Inscripción | cargando; QR y clave; códigos de recuperación; primer código; error de red |
| Página autenticada | cargando; confirmación; error de lectura |
| Ruta no encontrada | única |

La puerta DEBE fallar ante una violación crítica o seria según la política de `@confia/config`, ante cero rutas evaluadas y
ante una ruta deliberadamente inaccesible que solo existe en pruebas. El informe de violaciones DEBE conservarse como artefacto
de la ejecución y la misma puerta DEBE poder correr en local con el mismo veredicto. Una violación moderada DEBE reportarse sin
fallar.
(Previously: la parte 1 publicó esta tabla sin las filas de inicio de sesión, verificación e inscripción.)

#### Escenario: [BX109] La matriz cubre las tres pantallas de acceso

- **DADO** la matriz de la puerta tras el último PR de las pantallas
- **CUANDO** se enumeran sus combinaciones
- **ENTONCES** contiene cada estado mínimo de inicio de sesión, verificación e inscripción en tema claro y en tema oscuro
- **Y** el total es de 22 estados por 2 temas, 44 evaluaciones

### Requisito: Un ADR nuevo y notas fechadas dejan escrita la puerta sobre rutas, y el criterio de salida 8 se marca solo cuando la puerta pasa

El sistema DEBE registrar la decisión de «axe sobre rutas renderizadas» en ADR-0028, en
`docs/adr/`, indexado en `docs/adr/README.md` y en estado Propuesto. ADR-0026 NO DEBE reescribirse: DEBE recibir solo una nota
fechada sobre el traslado de la política. `docs/09-roadmap-y-fases.md` DEBE recibir una nota fechada con los nombres de los
cambios `admin-web-foundations-and-session` y `admin-web-staff-sign-in` y la división 13b-1 y 13b-2, y con la salida de la semilla y de CORS de 13b (que la propuesta de 13a asignaba a él); el
criterio de salida 8 DEBE marcarse solo cuando la puerta pase sobre el inicio de sesión, la verificación y la inscripción, y
DEBE desmarcarse con nota fechada si la puerta o una pantalla evaluada se revierte. `docs/ui-ux/04` y `docs/ui-ux/06` DEBEN
recibir notas fechadas donde el comportamiento se concreta. `CLAUDE.md` y `docs/01-arquitectura.md` §3 DEBEN sumar la puerta
sobre rutas a la fila de pruebas del frontend, y `packages/config/package.json` DEBE actualizar su descripción y declarar la
exportación `./a11y`.
(Previously: la parte 1 publicó este requisito sin `docs/09`, `docs/ui-ux/04` ni `docs/ui-ux/06`, y prohibía marcar el criterio de salida 8.)

#### Escenario: [BX102] El criterio 8 no se marca antes de tiempo

- **DADO** `docs/09-roadmap-y-fases.md` en cualquier momento previo a que la puerta pase sobre el inicio de sesión, la
  verificación y la inscripción
- **CUANDO** se lee el criterio de salida 8
- **ENTONCES** no está marcado como cumplido

#### Escenario: [BX103] El criterio 8 se marca con su prueba

- **DADO** la puerta en verde sobre el inicio de sesión, la verificación, la inscripción, el arranque y la página autenticada, en
  los dos temas
- **CUANDO** se lee el criterio de salida 8 en `docs/09-roadmap-y-fases.md`
- **ENTONCES** está marcado y cita la puerta y el cambio

#### Escenario: [BX104] La nota de `docs/09` es fechada y registra la división

- **DADO** el diff de `docs/09-roadmap-y-fases.md` frente a `main`
- **CUANDO** se inspecciona
- **ENTONCES** la nota agregada está fechada, nombra los cambios `admin-web-foundations-and-session` y `admin-web-staff-sign-in`, describe la división 13b-1 y
  13b-2 y declara que la semilla y CORS salen de 13b
- **Y** las notas anteriores del documento no se reescriben

#### Escenario: [BX105] Las notas de `docs/ui-ux/04` y `docs/ui-ux/06` son fechadas

- **DADO** el diff de los dos documentos frente a `main`
- **CUANDO** se inspecciona
- **ENTONCES** solo contiene notas fechadas agregadas

#### Escenario: [BX108] Revertir la puerta o una pantalla desmarca el criterio

- **DADO** la reversión de la puerta o de una pantalla evaluada
- **CUANDO** se lee `docs/09-roadmap-y-fases.md` tras la reversión
- **ENTONCES** el criterio de salida 8 está desmarcado y una nota fechada explica por qué
