# Delta para Integridad de la construcción

- **Estado:** delta inicial del cambio, el 2026-11-10, tras la aprobación de la propuesta por el propietario
- **Cambio:** `admin-web-shell-and-session-lifecycle` (F0, cambio 13b-2). El criterio de salida 8 ya está cerrado (nota fechada 2026-11-09); este cambio **no
  puede desmarcarlo** y debe mantener la puerta en verde sobre las pantallas nuevas.
- **Propuesta:** `proposal.md`, aprobada por el propietario el 2026-11-10

**Convenciones de este delta.**

- Los escenarios nuevos se numeran `BX111` en adelante, a continuación del máximo vigente (`BX110`). Los escenarios `BX74` a `BX82` se conservan y `BX109` se precisa (la
  matriz completa pasa de 22 a 27 estados), con nota fechada. Ninguno se borra (ADR-0018).
- **Cómo se fusiona este delta.** Se archiva después de `admin-web-foundations-and-session` y `admin-web-staff-sign-in`. Los requisitos bajo `## ADDED Requirements` son
  nuevos. El requisito bajo `## MODIFIED Requirements` trae el cuerpo completo y **todos** sus escenarios (los que no cambian se copian sin alteración).
- Los valores fijados por el diseño son los de los deltas de 13b-1: fixtures de la puerta en `apps/admin-web/e2e/fixtures/a11y/*.html`, informes en
  `apps/admin-web/reports/a11y/report.json` y `reports/e2e/`, límite del trabajo de 20 minutos.
- **Modelo de evaluación.** Cada estado de la matriz se evalúa con la preferencia del sistema emulada como `light` y como `dark`. Los dos estados de tema
  explícito, además, tienen el atributo `data-theme` establecido, para demostrar que el tema explícito prevalece sobre la emulación contraria. Por eso la
  matriz de 27 estados produce 54 evaluaciones.
- Toda regla de este delta lleva su violación deliberada y no aprueba sobre un conjunto vacío (ADR-0018).

## ADDED Requirements

### Requisito: Cada estado nuevo de la matriz tiene su fixture de violación deliberada y un estado declarado pero no evaluado rompe la puerta

El sistema DEBE ofrecer, para cada estado que este cambio agrega a la matriz de la puerta (menú de usuario abierto, aviso de inactividad abierto, aviso de
vencimiento absoluto, tema explícito claro y tema explícito oscuro), un fixture de violación deliberada que haga fallar la puerta nombrando ruta, estado, tema,
regla de axe e impacto. La puerta DEBE fallar si la matriz declara un estado que la ejecución no evalúa: ningún estado nuevo puede quedar sin evaluar y dejar el
criterio 8 en verde sobre una pantalla no examinada.

#### Escenario: [BX111] Cada estado nuevo tiene su fixture de violación

- **DADO** los cinco estados nuevos y sus fixtures de violación deliberada
- **CUANDO** corre la puerta sobre cada fixture
- **ENTONCES** falla en cada uno nombrando la ruta, el estado, el tema, la regla de axe y su impacto

#### Escenario: [BX112] Un estado declarado pero no evaluado rompe la puerta

- **DADO** una matriz que declara el estado «aviso de inactividad abierto» y una ejecución que no lo evalúa
- **CUANDO** corre la verificación de completitud de la puerta
- **ENTONCES** falla nombrando el estado no evaluado

#### Escenario: [BX113] Los estados reales nuevos pasan en verde

- **DADO** los cinco estados nuevos reales, sin los fixtures
- **CUANDO** corre la puerta
- **ENTONCES** ninguno reporta una violación crítica ni seria en ningún tema, y el informe los lista

### Requisito: Los estados con tema explícito se evalúan con `data-theme` establecido y con la preferencia del sistema contraria

El sistema DEBE evaluar la página autenticada con tema explícito claro y con tema explícito oscuro estableciendo `data-theme` mediante el selector (no inyectándolo
solo desde la prueba), y DEBE evaluar cada uno con la preferencia del sistema emulada como `light` y como `dark`. La puerta DEBE comprobar antes de analizar que el
fondo calculado del documento corresponde al tema explícito y no al emulado. Un contraste insuficiente que ocurra solo bajo un tema explícito DEBE hacer fallar la
puerta.

#### Escenario: [BX114] El tema explícito prevalece durante la evaluación

- **DADO** el estado «tema explícito oscuro» con la preferencia del sistema emulada como `light`, y el estado «tema explícito claro» con la emulada como `dark`
- **CUANDO** corre la puerta
- **ENTONCES** antes de analizar, el fondo calculado de cada uno coincide con el token del tema explícito y no con el del sistema
- **Y** el elemento raíz tiene el valor de `data-theme` correspondiente

#### Escenario: [BX115] Una violación de contraste solo en un tema explícito falla

- **DADO** un fixture cuyo contraste es suficiente bajo el tema explícito claro e insuficiente bajo el tema explícito oscuro
- **CUANDO** corre la puerta
- **ENTONCES** falla nombrando el estado «tema explícito oscuro» y la regla de contraste

#### Escenario: [BX116] El tema explícito se establece por el selector

- **DADO** la definición del estado «tema explícito oscuro» de la matriz
- **CUANDO** se inspecciona cómo se alcanza
- **ENTONCES** se alcanza eligiendo «oscuro» en el selector de tema del menú de usuario y no escribiendo el atributo directamente

### Requisito: Los estados superpuestos se analizan solo cuando son visibles y con el fondo inerte

El sistema DEBE analizar el menú de usuario abierto y el aviso de inactividad abierto solo después de que su contenido esperado sea visible, con el fondo en el
estado inerte u oculto que produce el componente, y sin que la apertura dependa de esperas de tiempo real: el aviso se abre con el reloj controlado. Una
combinación cuyo contenido esperado nunca aparece DEBE fallar por tiempo de espera, como en el escenario BX78.

#### Escenario: [BX117] El aviso se abre con el reloj controlado antes de analizar

- **DADO** el estado «aviso de inactividad abierto»
- **CUANDO** corre la puerta
- **ENTONCES** el aviso se abre avanzando el reloj controlado a 5 minutos antes del plazo, nunca con una espera de tiempo real, y se analiza solo con el diálogo visible

#### Escenario: [BX118] Un estado superpuesto que nunca aparece falla por tiempo de espera

- **DADO** una combinación del menú de usuario abierto cuyo contenido esperado nunca aparece
- **CUANDO** corre la puerta
- **ENTONCES** falla por tiempo de espera del contenido esperado y no analiza la página cerrada como si fuera el estado abierto

### Requisito: El criterio de salida 8 se mantiene marcado y las notas fechadas de 13b-2 se verifican sin quitar las de 13b-1

El sistema DEBE mantener marcado el criterio de salida 8 de `docs/09-roadmap-y-fases.md` y NO DEBE desmarcarlo con este cambio; si una reversión deja una pantalla
autenticada sin evaluar por la puerta, DEBE desmarcarse con nota fechada en el mismo commit (escenario BX108). `docs/09` DEBE recibir una nota fechada que nombre
el cambio `admin-web-shell-and-session-lifecycle`, el cierre de 13b-2 (shell, vida de la sesión y aviso de inactividad) y la matriz ampliada. `docs/ui-ux/04`
DEBE recibir notas fechadas sobre el shell con solo barra superior y sobre la política de actividad (refresco por actividad con cadencia mínima, aviso a los 25
minutos, vencimiento absoluto sin extensión). `docs/ui-ux/01` DEBE recibir una nota fechada sobre el selector de tema de tres estados. Los textos originales NO DEBEN
reescribirse. `docs-notes.test.ts` DEBE verificar las notas nuevas sin quitar ninguna verificación de las notas de 13b-1.

#### Escenario: [BX119] El criterio 8 sigue marcado con la matriz ampliada

- **DADO** `docs/09-roadmap-y-fases.md` tras este cambio y la puerta en verde sobre los 27 estados en los dos temas
- **CUANDO** se lee el criterio de salida 8
- **ENTONCES** está marcado y cita la puerta y la matriz ampliada

#### Escenario: [BX120] La nota de `docs/09` es fechada y no reescribe nada

- **DADO** el diff de `docs/09-roadmap-y-fases.md` frente a `main`
- **CUANDO** se inspecciona
- **ENTONCES** la nota agregada está fechada, nombra el cambio `admin-web-shell-and-session-lifecycle` y declara que los recorridos corren contra una API simulada
  y que la prueba contra la pila real es del cambio 11
- **Y** las notas anteriores del documento no se reescriben

#### Escenario: [BX121] Las notas de `docs/ui-ux/04` y `docs/ui-ux/01` son fechadas y solo agregan

- **DADO** el diff de los dos documentos frente a `main`
- **CUANDO** se inspecciona
- **ENTONCES** solo contiene notas fechadas agregadas, sin líneas eliminadas ni modificadas
- **Y** la nota de `docs/ui-ux/04` declara el shell con solo barra superior y la política de actividad, y la de `docs/ui-ux/01` declara el selector de tema de tres estados

#### Escenario: [BX122] `docs-notes.test.ts` verifica las notas nuevas y conserva las anteriores

- **DADO** `apps/admin-web/src/app/docs-notes.test.ts`
- **CUANDO** se leen sus verificaciones
- **ENTONCES** incluye las de las notas de 13b-2 y conserva todas las de 13b-1
- **Y** una nota de 13b-2 ausente hace fallar la prueba nombrando el documento

### Requisito: La prueba de secretos en almacenamiento admite la clave de preferencia de tema y sigue detectando tokens

El sistema DEBE acotar la prueba de que los almacenamientos web no contienen secretos para que admita la clave de preferencia de tema (cuyo valor es uno de tres estados) y
siga fallando ante cualquier token, secreto, código o centinela en `localStorage`, `sessionStorage` o IndexedDB. La clave de tema no debe poder usarse como vía para
guardar otro contenido.

#### Escenario: [BX123] La clave de tema no hace fallar la prueba

- **DADO** `localStorage` con solo la clave de preferencia de tema y un valor válido tras un recorrido
- **CUANDO** corre la prueba de secretos en almacenamiento
- **ENTONCES** pasa

#### Escenario: [BX124] Un token en `localStorage` sigue fallando

- **DADO** `localStorage` con la clave de tema y, además, un centinela con forma de token de acceso en otra clave, y en otra ejecución en la propia clave de tema
- **CUANDO** corre la prueba de secretos en almacenamiento
- **ENTONCES** falla en ambos casos nombrando la clave y el almacenamiento

#### Escenario: [BX125] Un valor de tema fuera de los tres estados falla

- **DADO** `localStorage` con la clave de tema y un valor distinto de los tres estados
- **CUANDO** corre la prueba de secretos en almacenamiento
- **ENTONCES** falla nombrando la clave

### Requisito: Un ADR registra las superposiciones nativas bajo CSP estricta y las primitivas de Radix que bloquean el desplazamiento no se importan desde `packages/ui`

La sonda de CSP (D12) mostró que `Dialog` y `DropdownMenu` de Radix inyectan un `<style>` (vía `react-remove-scroll-bar`) que viola `style-src 'self'`. El sistema DEBE
registrar en un ADR nuevo en `docs/adr/` (`design.md`, H17), indexado en `docs/adr/README.md`, que las superposiciones modales y los menús de `packages/ui` se
construyen sobre elementos nativos (`<dialog>` y divulgación) mientras la CSP sea `style-src 'self'` sin *nonce*. ADR-0026 y ADR-0028 NO DEBEN reescribirse. Además,
el sistema DEBE impedir por análisis estático que `packages/ui` importe de `radix-ui` los primitivos que bloquean el desplazamiento (`Dialog`, `AlertDialog`,
`DropdownMenu`, `Popover`, `Select`, `ContextMenu`, `Menubar`, `NavigationMenu`) y que importe `react-remove-scroll` o `react-remove-scroll-bar`, con la regla ESLint
`ui-no-scroll-locking-overlays` (`no-restricted-imports`, `design.md` H16) y un mensaje que cita el ADR. Los átomos de Radix que ya pasan AW12 (`Checkbox`,
`RadioGroup`) siguen permitidos. La regla lleva su violación deliberada y no aprueba sobre un conjunto vacío.

#### Escenario: [BX126] El ADR existe, está indexado y ADR-0026 y ADR-0028 conservan su texto

- **DADO** el directorio `docs/adr/` y su índice
- **CUANDO** se listan los ADR nuevos de este cambio
- **ENTONCES** existe un ADR nuevo, indexado, que cita la evidencia de la sonda de CSP y declara las superposiciones nativas
- **Y** ADR-0026 y ADR-0028 no tienen líneas modificadas ni eliminadas

#### Escenario: [BX127] Importar un primitivo de Radix que bloquea el desplazamiento desde `packages/ui` rompe el lint

- **DADO** un archivo de violación deliberada en `packages/ui` que importa `Dialog` (y, en otras ejecuciones, `AlertDialog`, `DropdownMenu`, `Popover`, `Select`, `ContextMenu`, `Menubar` y `NavigationMenu`) de `radix-ui`
- **CUANDO** corre ESLint
- **ENTONCES** falla en cada caso nombrando la regla `ui-no-scroll-locking-overlays` y citando el ADR

#### Escenario: [BX128] Importar `react-remove-scroll` desde `packages/ui` rompe el lint

- **DADO** un archivo de violación deliberada en `packages/ui` que importa `react-remove-scroll`, y otro que importa `react-remove-scroll-bar`
- **CUANDO** corre ESLint
- **ENTONCES** ambos fallan nombrando la regla `ui-no-scroll-locking-overlays`

#### Escenario: [BX129] Los átomos de Radix existentes siguen permitidos

- **DADO** `Checkbox` y `RadioGroup` de `packages/ui`, que importan de `radix-ui`
- **CUANDO** corre ESLint sobre el código real de `packages/ui`
- **ENTONCES** termina sin infracciones de la regla, y la regla evaluó archivos reales (no un conjunto vacío)

#### Escenario: [BX130] Ningún componente real de `packages/ui` importa un primitivo que bloquea el desplazamiento

- **DADO** el código real de `packages/ui` y el resultado de compilación de producción de `apps/admin-web`
- **CUANDO** se buscan importaciones o referencias a `react-remove-scroll` y a los primitivos prohibidos
- **ENTONCES** no existe ninguna

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
| Página autenticada | cargando; confirmación (el shell con el panel de bienvenida); error de lectura; menú de usuario abierto; aviso de inactividad abierto; aviso de vencimiento absoluto; tema explícito claro; tema explícito oscuro |
| Ruta no encontrada | única |

La puerta DEBE fallar ante una violación crítica o seria según la política de `@confia/config`, ante cero rutas evaluadas y
ante una ruta deliberadamente inaccesible que solo existe en pruebas. El informe de violaciones DEBE conservarse como artefacto
de la ejecución y la misma puerta DEBE poder correr en local con el mismo veredicto. Una violación moderada DEBE reportarse sin
fallar.
(Previously: la tabla traía 22 estados; la página autenticada tenía solo cargando, confirmación y error de lectura, sin el menú abierto, el aviso de inactividad, el aviso de vencimiento absoluto ni el tema explícito.)

#### Escenario: [BX74] Cada combinación mínima se evalúa en ambos temas

- **DADO** la matriz de la puerta
- **CUANDO** se enumeran sus combinaciones
- **ENTONCES** contiene, para cada pantalla y estado mínimos de la tabla, una evaluación en tema claro y otra en tema oscuro

#### Escenario: [BX75] Una violación seria rompe la puerta nombrando ruta, estado, tema y regla

- **DADO** una violación seria en una combinación de la matriz
- **CUANDO** corre la puerta
- **ENTONCES** falla nombrando la ruta, el estado, el tema, la regla de axe y su impacto

#### Escenario: [BX76] Una ruta deliberadamente inaccesible falla

- **DADO** la ruta de prueba con un control sin nombre accesible
- **CUANDO** corre la puerta sobre ella
- **ENTONCES** falla, lo que demuestra que la puerta evalúa de verdad

#### Escenario: [BX77] Cero rutas evaluadas falla

- **DADO** una configuración de la puerta cuya matriz queda vacía
- **CUANDO** corre
- **ENTONCES** falla en lugar de aprobar

#### Escenario: [BX78] No se analiza durante la carga

- **DADO** una combinación cuyo contenido esperado nunca aparece y que permanece en su estado de carga
- **CUANDO** corre la puerta
- **ENTONCES** falla por tiempo de espera del contenido esperado y no analiza el estado de carga

#### Escenario: [BX79] Una violación solo en el tema oscuro falla

- **DADO** la ruta de prueba cuyo contraste es suficiente en el tema claro e insuficiente en el oscuro
- **CUANDO** corre la puerta
- **ENTONCES** falla, porque cada combinación se evalúa en ambos temas

#### Escenario: [BX80] Una violación moderada se reporta sin fallar

- **DADO** una combinación cuya única violación es de impacto moderado
- **CUANDO** corre la puerta
- **ENTONCES** no falla por esa causa y la advertencia queda en el informe

#### Escenario: [BX81] Rutas reales sin violaciones bloqueantes

- **DADO** las combinaciones reales de la matriz, sin la ruta de prueba
- **CUANDO** corre la puerta
- **ENTONCES** termina en verde con el informe conservado como artefacto

#### Escenario: [BX82] Ejecución local con el mismo veredicto

- **DADO** una máquina de desarrollo con las dependencias y Chromium instalados
- **CUANDO** se ejecuta localmente la puerta
- **ENTONCES** produce el mismo veredicto que la integración continua sobre el mismo código

#### Escenario: [BX109] La matriz cubre las tres pantallas de acceso y los estados del shell

- **DADO** la matriz de la puerta tras el último PR de este cambio
- **CUANDO** se enumeran sus combinaciones
- **ENTONCES** contiene cada estado mínimo de inicio de sesión, verificación e inscripción en tema claro y en tema oscuro
- **Y** contiene los cinco estados nuevos de la página autenticada (menú de usuario abierto, aviso de inactividad abierto, aviso de vencimiento absoluto, tema explícito claro y tema explícito oscuro) además de los tres anteriores
- **Y** el total es de 27 estados por 2 emulaciones del sistema, 54 evaluaciones

> **Nota fechada 2026-11-10 (cambio `admin-web-shell-and-session-lifecycle`, 13b-2, escenario precisado).** Texto anterior: «el total es de 22 estados por 2 temas, 44 evaluaciones» y la cláusula sobre los estados del shell no existía. Los 22 estados anteriores se conservan.
