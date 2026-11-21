# Corte: permisos del usuario en la sesión (identity)

Cierre de F3 (pendiente «permisos en sesión»). Hoy `GET /api/v1/auth/sessions/current` no dice qué puede hacer el usuario, así que el
panel muestra a todos los roles entradas de menú y acciones que el servidor luego rechaza con 403 (caja, aprobar cierre, anular cargo).

## Objetivo

Que la respuesta de la sesión actual incluya los permisos efectivos del usuario, y que el panel oculte las entradas de menú, las rutas
y las acciones para las que el usuario no tiene permiso. **La interfaz solo oculta: el servidor sigue decidiendo cada petición**
(regla 9 de CLAUDE.md). Un permiso que falta en la respuesta nunca concede nada.

## Reglas

- `permissions`: lista ordenada de códigos (`cashbox:approve`, `charges:void`, …) del rol vigente de la cuenta, calculada en el
  servidor por el mismo camino que usa la autorización (sin duplicar la matriz). Sin rol vigente, lista vacía.
- Se lee en la misma transacción de solo lectura de la sesión; no se audita (es lectura de la propia sesión) y no se guarda en el
  token: un cambio de rol se refleja en la siguiente lectura de la sesión.
- El panel lee los permisos de la sesión ya cargada (TanStack Query), sin estado global duplicado. Un elemento de menú o una ruta
  declara el permiso que exige; si falta, el menú no la muestra y la ruta muestra «Sin acceso» en lugar de la pantalla. Las acciones
  (aprobar cierre, anular cargo, registrar movimientos, abrir caja) se ocultan sin permiso.
- La lista de permisos no es un secreto, pero no se registra en logs.

## Endpoint

`GET /api/v1/auth/sessions/current`: agrega `permissions: string[]` (requerido). El resto de la respuesta no cambia.

## Pantallas

Menú lateral, guardas de ruta y acciones de cargos (anular), caja (abrir, mover, cerrar, aprobar) y las demás acciones que ya
declaran un permiso en el servidor.

## Casos límite

Cuenta sin rol vigente (lista vacía, menú solo con Inicio), cada uno de los seis roles contra la matriz (`docs/03` §5.2), sesión de
otra institución (sigue respondiendo 401 como hoy), cambio de rol entre dos lecturas, navegación directa por URL a una ruta sin
permiso, y una petición que el servidor rechaza aunque la interfaz la muestre (sigue mostrando el error 403).

## Criterio de aceptación

Un cajero ve «Mi caja» y no ve «Aprobar cierre» ni «Anular cargo»; un administrador ve «Sesiones de caja» y «Aprobar cierre» y no ve
«Mi caja»; el auditor no ve ninguna acción de escritura. Con la interfaz alterada a mano, el servidor sigue respondiendo 403.

## Lo construido (backend)

- `GET /api/v1/auth/sessions/current` devuelve `permissions` (requerido): códigos ordenados del rol vigente de la cuenta; lista vacía
  sin rol o con un código fuera del catálogo. Respuesta de ocho miembros; el snapshot `admin.openapi.json` está regenerado.
- `DescribeCurrentSession` lee el código de rol con `StaffRoleAssignmentRepository` y lo traduce con `StaffRole.permissions()` dentro de
  la misma transacción de solo lectura de la sesión: mismo almacén y mismo catálogo que `AccountPermissions`, sin duplicar la matriz.
  No llama al puerto `PermissionResolver` porque la guarda de inventario (`PermissionResolverInventoryTest`) reserva esa llamada al
  `PermissionDecider`; ninguna guarda se tocó. No se audita, no va en el token y no se registra en logs.
- **Sesión sin segundo factor: lista vacía.** El `PermissionDecider` niega toda petición con permiso si la sesión no probó el código de
  un solo uso. Por eso, en ese caso, la lista viene vacía y el rol ni siquiera se lee. Así la interfaz nunca ofrece lo que el servidor
  niega (hallazgo M1 de la auditoría).
- La traducción del código de rol a permisos vive en un solo lugar, `EffectiveRolePermissions`, que usan `AccountPermissions` y
  `DescribeCurrentSession`.
- Pruebas:
  - `DescribeCurrentSessionTest` y `CurrentSessionControllerTest`.
  - `CurrentSessionEndpointIT`: los seis roles contra `docs/03` §5.2, cuenta sin rol, sesión sin segundo factor, cambio de rol entre dos
    lecturas y rol de otra institución.
  - `OpenApiContractSnapshotTest`.
- Pendiente de este corte: regenerar `packages/contracts` y todo el frontend (menú, guardas de ruta, acciones).

## Lo construido (frontend)

- `packages/contracts` regenerado: la sesión actual trae `permissions: string[]` (requerido).
- **Lectura sin estado duplicado.** `useSignedInSession` toma `permissions` de la consulta de la sesión actual (TanStack Query, la misma
  que ya cargaba el armazón). El contenedor del armazón (`AppShellContainer`) las entrega con `PermissionsProvider`
  (`shared/permissions/permissions.tsx`): es un valor derivado de la consulta, no una copia. `usePermissions().can(código)` es el único
  punto de lectura; fuera de un proveedor no concede nada, igual que un código que no viene en la lista.
- **Códigos del servidor, no inventados.** `shared/permissions/permission-codes.ts` lista los códigos que el panel pregunta; cada uno sale del
  `x-required-permission` de las operaciones de `apps/api/openapi/admin.openapi.json` (catalog, charges, guardians, ledger, organization,
  students, identity). Los códigos de caja no están: se agregan allí cuando llegue su PR.
- **Menú.** `NavigationEntry.permission` declara lo que exige cada entrada; el armazón lista solo las que la sesión puede usar y un grupo
  sin entradas visibles desaparece (sesión sin permisos: solo «Inicio»). Entradas: estructura académica `organization:read`; catálogo
  `catalog:read`; cobros, estado de cuenta, cargos del estudiante e integridad `charges:read`; **generación de cargos `charges:create`**
  (la página previsualiza y ejecuta con ese permiso); estudiantes y matrículas `students:read`; encargados `guardians:read`;
  importaciones `students:read`; plan de cuentas `ledger:read`.
- **Rutas.** `FeatureModule.routePermissions` declara el permiso por prefijo de ruta (gana el prefijo más largo, p. ej. `/charges/accrual`
  pide `charges:create` y `/charges/accrual/runs` solo `charges:read`; el mapa de cuentas contables lee de cobros y pide `charges:read`).
  El armazón muestra «Sin acceso» (`NoAccess`, texto en `noAccess.*` del catálogo es-HN, con vuelta a Inicio) en lugar de la pantalla, sin
  montarla, de modo que no hace peticiones. Carga y error de la sesión los resuelve antes el armazón (`SignedInPage`).
- **Acciones de escritura ocultas** sin su permiso (los botones no se dibujan; el manejador ausente es la señal): crear, editar y
  activar/desactivar en organización (4 vistas), catálogo (conceptos, tasas, listas de precios; agregar precio, vigencia, archivar),
  estudiantes, encargados (y registrar consentimientos), plan de cuentas, planes de cobro (editar, activar, archivar, agregar y quitar
  ítems), matrículas (matricular, trasladar, dar de baja), importaciones (cada tipo según `students:create` o `guardians:create`; sin
  ninguno no se dibuja el asistente), conciliación (formulario con `students:create`), generación y «Recalcular» (`charges:create`), el mapa de
  cuentas (solo lectura sin `ledger:create`) y **«Anular cargo» (`charges:void`)**. La columna de acciones desaparece cuando ya no queda
  ninguna. El estado vacío de un listado sin permiso de crear ofrece «Actualizar la lista» en lugar de «Crear…» (ADR-0018: el estado
  vacío conserva una acción), y los de detalle ofrecen volver a la lista.
- **El servidor sigue decidiendo.** No se tocó el manejo del 403: una petición rechazada aunque la interfaz la muestre sigue mostrando
  su error.
- **Caja** (llegó con #334 y se integró al traer main). Los permisos de cada parte son los que exige el servidor en
  `x-required-permission`:
  - «Mi caja» (`/cashbox`): `cashbox:create`, porque abre, registra movimientos y cierra.
  - «Sesiones de caja» y el reporte de una sesión (`/cashbox/sessions`): `cashbox:read`. Ese prefijo es más largo, así que gana sobre `/cashbox`.
  - El reporte ofrece «Aprobar cierre» solo con `cashbox:approve`.

  Resultado por rol: el cajero ve «Mi caja» y no ve «Aprobar cierre». El administrador ve «Sesiones de caja» y «Aprobar cierre», y no ve
  «Mi caja», porque no tiene `cashbox:create`. El auditor solo ve la lista y el reporte.

  Prueba: `CashSessionDetailContainer.permissions.test.tsx`.
- **Simulador (`e2e/mock-api`).** La sesión simulada devuelve todos los códigos que el panel conoce (los recorridos existentes siguen
  igual); los presets `role-auditor` (solo lecturas) y `role-without-void` (todo menos `charges:void`) fijan un rol restringido por
  escenario.
- Pruebas: `permissions.test.tsx` (ayudante y prefijos de ruta), `AppShellPermissions.test.tsx` (menú, grupo vacío, sin permisos, ruta
  sin permiso, vuelta a Inicio), `NoAccess.test.tsx`, `*.permissions.test.tsx` de conceptos de pago, estudiantes y cargos del estudiante,
  pruebas del simulador y el recorrido `e2e/specs/journeys/permissions.e2e.ts` (auditor, rol sin anulación, cajero y administrador). El
  recorrido de Playwright se ejecutó en esta rama y pasan sus 4 pruebas: `pnpm --filter @confia/admin-web exec playwright test
  specs/journeys/permissions.e2e.ts`.
- Pendiente, fuera del corte: enlaces de lectura que llevan a una pantalla sin permiso (por ejemplo «Estado de cuenta» desde la lista de
  estudiantes, o el paso «Generación» de la miga de una corrida) siguen visibles y llevan a «Sin acceso»; el diseño puede decidir si se
  ocultan. La puerta de accesibilidad con rutas no incluye todavía «Sin acceso».
- **Límites conocidos de la pista** (auditoría, hallazgos bajos). La interfaz solo oculta, así que ninguno concede nada; en cada caso el
  servidor responde 403 o 404:
  - Un permiso limitado a los registros propios llega como un código más. El cajero recibe `cashbox:read` igual que el administrador,
    pero el servidor solo le deja leer sus propias sesiones, así que la lista y el reporte le muestran solo lo suyo.
  - Algunos endpoints exigen un segundo permiso (`alsoRequired` del decisor). Ese segundo permiso no se publica en el contrato, así que
    la interfaz solo comprueba el principal. Si una acción lo necesita, puede quedar visible y recibir 403.
- **Guardas contra desvíos:**
  - `permission-codes.test.ts` exige que cada código del panel sea el `x-required-permission` de alguna operación de
    `admin.openapi.json`. Se quitó `identity:read`, que ninguna pantalla usaba.
  - `features.permissions.test.ts` exige que cada entrada de cada módulo declare su permiso y que una declaración de ruta cubra su
    dirección con ese mismo permiso. Así, una ruta nueva no queda abierta por olvido.
- **Recorridos por rol:** los presets `role-cashier` y `role-administrator` cubren el criterio de aceptación de caja en
  `permissions.e2e.ts`:
  - el cajero ve «Mi caja» y no ve «Aprobar cierre»;
  - el administrador ve «Sesiones de caja» y «Aprobar cierre»; no ve «Mi caja» y, si entra por dirección, obtiene «Sin acceso».
