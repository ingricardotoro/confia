# Delta para Integridad de la construcción

- **Estado:** pendiente de aprobación del propietario del producto
- **Cambio:** `design-system-foundations-and-a11y` (13a)

## ADDED Requirements

### Requisito: Reglas de internacionalización y de formato en el análisis estático de `packages/ui`

El sistema DEBE romper el análisis estático del monorepo (ESLint o la verificación equivalente) en
tres casos, conforme a ADR-0011 «Cumplimiento y verificación»: (1) un literal de texto en JSX, ya
sea contenido visible o texto de nombre accesible (`aria-label`, `title`, `alt`, `placeholder`);
(2) una llamada a `toFixed` o a `toLocaleString` fuera de las utilidades de formato designadas; y
(3) una clave de traducción usada en el código que no existe en el catálogo `es-HN`. Una clave que
la verificación no pueda resolver estáticamente (por ejemplo, construida por concatenación) DEBE
tratarse como clave inexistente. Las reglas aplican al código de producción de `packages/ui`.
Cada una de las tres reglas DEBE tener su propia violación deliberada y permanente, y NO DEBE
aprobarse sobre un conjunto vacío (ADR-0018).

#### Escenario: Literal de texto en JSX

- **DADO** la violación deliberada de un componente con el texto «Guardar» escrito dentro del JSX
- **CUANDO** se ejecuta el análisis estático del monorepo
- **ENTONCES** falla nombrando el archivo y la regla de literales

#### Escenario: Literal de texto en un atributo de nombre accesible

- **DADO** la violación deliberada de un botón con `aria-label="Cerrar"` escrito como literal
- **CUANDO** se ejecuta el análisis estático del monorepo
- **ENTONCES** falla nombrando el archivo y la regla de literales

#### Escenario: `toFixed` fuera de las utilidades de formato

- **DADO** la violación deliberada de un componente que invoca `toFixed(2)`
- **CUANDO** se ejecuta el análisis estático del monorepo
- **ENTONCES** falla nombrando el archivo y la regla de formato manual

#### Escenario: `toLocaleString` fuera de las utilidades de formato

- **DADO** la violación deliberada de un componente que invoca `toLocaleString`
- **CUANDO** se ejecuta el análisis estático del monorepo
- **ENTONCES** falla nombrando el archivo y la regla de formato manual

#### Escenario: Formato dentro de las utilidades designadas

- **DADO** el código real de las utilidades de formato, que usa `Intl.NumberFormat`
- **CUANDO** se ejecuta el análisis estático del monorepo
- **ENTONCES** la regla de formato manual no falla por ese código

#### Escenario: Clave de traducción inexistente

- **DADO** la violación deliberada de un componente que usa la clave `common.status.inexistente`,
  ausente del catálogo `es-HN`
- **CUANDO** se ejecuta la verificación de claves
- **ENTONCES** falla nombrando la clave, el archivo y el catálogo en el que debía existir

#### Escenario: Clave no resoluble estáticamente

- **DADO** la violación deliberada de un componente que construye una clave por concatenación de
  cadenas
- **CUANDO** se ejecuta la verificación de claves
- **ENTONCES** falla indicando que la clave no puede verificarse

#### Escenario: Código real sin infracciones de internacionalización

- **DADO** el código real de `packages/ui`, excluidas las violaciones deliberadas
- **CUANDO** se ejecuta el análisis estático del monorepo
- **ENTONCES** las tres reglas pasan habiendo evaluado archivos reales de componentes y al menos una
  clave real del catálogo, no un conjunto vacío

### Requisito: Aritmética y conversión a `number` de importes prohibidas en `packages/ui`

El sistema DEBE romper el análisis estático si el código de producción de `packages/ui` convierte a
`number`, redondea u opera aritméticamente sobre el importe de un `Money` (`CLAUDE.md`, reglas 1 y
3): `Number()`, `parseFloat()`, `parseInt()`, `+valor`, `Math.*`, `toFixed()` u operadores
aritméticos sobre `amount`. La regla DEBE tener su propia violación deliberada y NO DEBE aprobarse
sobre un conjunto vacío.

#### Escenario: Conversión del importe a `number`

- **DADO** la violación deliberada de un archivo de `packages/ui` que ejecuta `Number(money.amount)`
- **CUANDO** se ejecuta el análisis estático del monorepo
- **ENTONCES** falla nombrando el archivo y la regla incumplida

#### Escenario: Aritmética sobre el importe

- **DADO** la violación deliberada de un archivo que suma o multiplica `money.amount`
- **CUANDO** se ejecuta el análisis estático del monorepo
- **ENTONCES** falla nombrando el archivo y la regla incumplida

#### Escenario: Código real sin aritmética monetaria

- **DADO** el código real de `packages/ui`
- **CUANDO** se ejecuta el análisis estático del monorepo
- **ENTONCES** la regla pasa habiendo evaluado el código real de `MoneyAmount`

### Requisito: Las historias de `packages/ui` se verifican con axe en la integración continua

El sistema DEBE ejecutar en la integración continua la verificación de accesibilidad de todas las
historias de `packages/ui` con axe, en modo claro y oscuro, y DEBE romper esa verificación ante una
violación crítica o seria en cualquier historia, y ante un componente exportado por `packages/ui`
que carezca de historia (`docs/ui-ux/03` §11). La verificación NO DEBE aprobar un conjunto vacío de
historias (ADR-0018). El resultado DEBE ser visible en el remoto y el informe de violaciones DEBE
conservarse como artefacto de la ejecución. La misma verificación DEBE poder ejecutarse en local.

#### Escenario: Violación crítica o seria en una historia

- **DADO** una historia de violación deliberada con un control sin nombre accesible
- **CUANDO** corre la verificación de historias en la integración continua
- **ENTONCES** el trabajo falla nombrando la historia y la regla de axe, y el resultado en rojo queda
  visible en el remoto

#### Escenario: Componente exportado sin historia

- **DADO** un componente nuevo exportado por `packages/ui` sin ninguna historia
- **CUANDO** corre la verificación de historias
- **ENTONCES** el trabajo falla nombrando el componente sin historia

#### Escenario: Historias reales sin violaciones bloqueantes

- **DADO** las historias reales de `packages/ui`, sin las de violación deliberada
- **CUANDO** corre la verificación de historias
- **ENTONCES** el trabajo termina en verde, con el informe de violaciones conservado como artefacto

#### Escenario: Verificación vacía

- **DADO** una configuración en la que la verificación no descubre ninguna historia
- **CUANDO** corre la verificación de historias
- **ENTONCES** el trabajo falla en lugar de aprobar

#### Escenario: Violación moderada

- **DADO** una historia cuya única violación es de impacto moderado
- **CUANDO** corre la verificación de historias
- **ENTONCES** el trabajo no falla por esa causa y la advertencia queda visible en el informe

#### Escenario: Ejecución local

- **DADO** una máquina de desarrollo con las dependencias instaladas
- **CUANDO** se ejecuta localmente la verificación de historias
- **ENTONCES** produce el mismo veredicto que la integración continua sobre el mismo código

### Requisito: La verificación de historias entra a la integración continua solo de forma aditiva

El sistema DEBE incorporar la verificación de historias como un paso nuevo del trabajo de
verificación del frontend o como un trabajo nuevo, sin modificar, reordenar ni eliminar los
trabajos y pasos existentes de `.github/workflows/ci.yml`. El trabajo de verificación del
frontend DEBE seguir ejecutando análisis estático, verificación de tipos, pruebas y construcción
sobre el monorepo, ahora con `packages/ui` incluido, y cada una de esas tareas DEBE ejecutarse
sobre `packages/ui` de verdad, no omitirse por falta de scripts.

#### Escenario: El diff de `ci.yml` solo agrega

- **DADO** el diff de `.github/workflows/ci.yml` de este cambio frente a `main`
- **CUANDO** se inspecciona
- **ENTONCES** no contiene líneas eliminadas ni modificadas de trabajos o pasos existentes, solo
  líneas agregadas

#### Escenario: Los trabajos existentes siguen intactos

- **DADO** el flujo de trabajo tras el cambio
- **CUANDO** corre sobre una rama sin violaciones
- **ENTONCES** los trabajos preexistentes del backend y del frontend terminan en verde con los
  mismos pasos que antes

#### Escenario: Las tareas del monorepo no omiten `packages/ui`

- **DADO** el monorepo con `packages/ui` incluido
- **CUANDO** se ejecuta `pnpm turbo run lint typecheck test build`
- **ENTONCES** cada una de las cuatro tareas se ejecuta en `packages/ui` y la de pruebas ejecuta al
  menos una prueba real

#### Escenario: Instalación reproducible con el paquete nuevo

- **DADO** el archivo de bloqueo actualizado junto con las dependencias nuevas
- **CUANDO** la integración continua ejecuta `pnpm install --frozen-lockfile`
- **ENTONCES** la instalación termina sin modificar el archivo de bloqueo

### Requisito: Cobertura mínima de `packages/ui`

El sistema DEBE romper la verificación de pruebas del monorepo si la cobertura de `packages/ui`
cae por debajo de ochenta por ciento (`CLAUDE.md`, sección «Pruebas»).

#### Escenario: Cobertura por debajo del umbral

- **DADO** `packages/ui` con cobertura inferior a 80 %
- **CUANDO** se ejecuta la tarea de pruebas con cobertura
- **ENTONCES** falla señalando `packages/ui` y el porcentaje medido

#### Escenario: Cobertura en el umbral o por encima

- **DADO** `packages/ui` con cobertura igual o superior a 80 %
- **CUANDO** se ejecuta la tarea de pruebas con cobertura
- **ENTONCES** no falla por esta causa

#### Escenario: Un componente sin pruebas baja la cobertura

- **DADO** un componente nuevo de `packages/ui` sin pruebas
- **CUANDO** se mide la cobertura
- **ENTONCES** el código de ese componente cuenta en el denominador y no queda excluido de la medición

## MODIFIED Requirements

### Requisito: Reglas de dependencia del frontend

El sistema DEBE romper la construcción del monorepo, mediante `dependency-cruiser` y ESLint, cuando
el código TypeScript viola alguna de estas reglas: un paquete de `packages/` importa de una
aplicación de `apps/`; una aplicación importa de otra aplicación; cualquier código importa una ruta
interna de `packages/contracts` en lugar de su punto de entrada público; o el código del portal
importa el espacio de nombres administrativo de `packages/contracts`. Además, DEBE romper la
construcción cuando, dentro de `packages/ui`: un átomo importa una molécula o el dominio; una
molécula genérica importa el dominio; cualquier archivo importa TanStack Query o hace peticiones de
red (la presentación no obtiene datos, `docs/ui-ux/02` §2); o `packages/ui` importa de
`packages/contracts` algo distinto de tipos a través de su punto de entrada público. Cada regla DEBE
estar acompañada de una violación deliberada que demuestre que la rechaza (ADR-0018). Ninguna regla
puede pasar sobre un conjunto vacío: una regla que no encuentra nada que evaluar no protege nada.
(Previously: solo cubría las cuatro reglas entre paquetes, aplicaciones y `packages/contracts`; no
había reglas internas de capas de `packages/ui` ni restricción de uso de `packages/contracts` a
tipos.)

#### Escenario: Un paquete importa de una aplicación

- **DADO** la violación deliberada en la que un módulo de `packages/` importa de `apps/`
- **CUANDO** se ejecuta la verificación de dependencias del monorepo
- **ENTONCES** falla y nombra la regla violada

#### Escenario: El portal importa el contrato administrativo

- **DADO** la violación deliberada en la que código del portal importa el espacio de nombres
  administrativo de `packages/contracts`
- **CUANDO** se ejecuta la verificación de dependencias del monorepo
- **ENTONCES** falla y nombra la regla violada

#### Escenario: Código que respeta las reglas

- **DADO** el código real del monorepo, excluidas las violaciones deliberadas
- **CUANDO** se ejecuta la verificación de dependencias del monorepo
- **ENTONCES** termina en verde

#### Escenario: Un átomo importa una molécula

- **DADO** la violación deliberada en la que un archivo de `packages/ui/src/atoms` importa de
  `packages/ui/src/molecules`
- **CUANDO** se ejecuta la verificación de dependencias del monorepo
- **ENTONCES** falla y nombra la regla violada

#### Escenario: Un átomo importa el dominio

- **DADO** la violación deliberada en la que un archivo de `packages/ui/src/atoms` importa de
  `packages/ui/src/domain`
- **CUANDO** se ejecuta la verificación de dependencias del monorepo
- **ENTONCES** falla y nombra la regla violada

#### Escenario: Una molécula genérica importa el dominio

- **DADO** la violación deliberada en la que un archivo de `packages/ui/src/molecules` importa de
  `packages/ui/src/domain`
- **CUANDO** se ejecuta la verificación de dependencias del monorepo
- **ENTONCES** falla y nombra la regla violada

#### Escenario: Una molécula de dominio compone átomos y moléculas

- **DADO** el código real de `packages/ui/src/domain` que importa de `atoms` y de `molecules`
- **CUANDO** se ejecuta la verificación de dependencias del monorepo
- **ENTONCES** esas importaciones no violan ninguna regla

#### Escenario: `packages/ui` importa TanStack Query

- **DADO** la violación deliberada en la que un archivo de `packages/ui` importa
  `@tanstack/react-query`
- **CUANDO** se ejecuta la verificación de dependencias del monorepo
- **ENTONCES** falla y nombra la regla violada

#### Escenario: `packages/ui` hace una petición de red

- **DADO** la violación deliberada en la que un archivo de `packages/ui` invoca `fetch` o
  `XMLHttpRequest`
- **CUANDO** se ejecuta el análisis estático del monorepo
- **ENTONCES** falla y nombra la regla violada

#### Escenario: `packages/ui` importa una ruta interna de `packages/contracts`

- **DADO** la violación deliberada en la que un archivo de `packages/ui` importa una ruta interna
  de `packages/contracts` en lugar de su punto de entrada público
- **CUANDO** se ejecuta la verificación de dependencias del monorepo
- **ENTONCES** falla y nombra la regla violada

#### Escenario: `packages/ui` importa un valor de `packages/contracts`

- **DADO** la violación deliberada en la que un archivo de `packages/ui` importa por el punto de
  entrada público un valor de ejecución (por ejemplo un esquema Zod) y no solo un tipo
- **CUANDO** se ejecuta el análisis estático del monorepo
- **ENTONCES** falla y nombra la regla violada

#### Escenario: `packages/ui` importa solo tipos del punto de entrada público

- **DADO** el código real de `packages/ui` que importa el tipo `Money` con `import type` desde el
  punto de entrada público de `packages/contracts`
- **CUANDO** se ejecuta la verificación de dependencias del monorepo
- **ENTONCES** esa importación no viola ninguna regla

#### Escenario: Regla sin archivos que evaluar

- **DADO** una regla de capas de `packages/ui` cuyo selector de archivos coincide con cero
  archivos reales del paquete
- **CUANDO** se ejecuta la verificación de dependencias del monorepo
- **ENTONCES** la verificación falla por conjunto vacío en lugar de aprobar en silencio
