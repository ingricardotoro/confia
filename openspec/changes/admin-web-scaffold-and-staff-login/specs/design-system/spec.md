# Delta para Sistema de diseño

- **Estado:** pendiente de aprobación del propietario del producto
- **Cambio:** `admin-web-scaffold-and-staff-login` (F0, cambio 13b-1)
- **Propuesta:** `proposal.md`, aprobada por el propietario el 2026-11-04 (P1: el catálogo `ui`, compartido por el panel y
  el portal, no se dirige a la persona; P8: la política de accesibilidad pasa a `@confia/config`)

**Convenciones de este delta.**

- Los escenarios nuevos se numeran `DS75` en adelante, a continuación del máximo vigente (`DS74`). Los escenarios que se
  conservan de la especificación vigente llevan aquí el identificador `DS01` a `DS74` con el que las pruebas de 13a los
  referencian (la especificación principal no los trae en el encabezado).
- Este delta agrega `FormErrorSummary` y `Alert`, corrige los pendientes W2 y W3 de 13a, y hace que la política de
  accesibilidad se consuma desde `@confia/config`. `Dialog`, `Toast`, `Banner` y el resto del inventario P0 siguen fuera
  (`Dialog` es del cambio 13b-2).
- **Valores que fija el diseño y que esta especificación no fija:** la capa de cada componente nuevo (átomo o molécula) y sus
  carpetas; la lista exacta de variantes de `Alert` por encima del mínimo exigido; el mecanismo con el que `FormErrorSummary`
  recibe el foco (referencia o propiedad); la lista de formas de segunda persona que la prueba de voz rechaza; el texto de
  la etiqueta de reintento por defecto de `ErrorState`.
- El texto del encabezado de `FormErrorSummary` de este delta difiere de «No se pudo guardar. Revise 2 campos.»
  (`docs/ui-ux/04` §7) porque «Revise» es un imperativo de segunda persona y el catálogo `ui` es compartido; la propuesta
  y el propietario deciden si prevalece el texto de la guía.

## ADDED Requirements

### Requisito: `FormErrorSummary` lista los errores de un formulario con enlaces a sus campos y puede recibir el foco

El sistema DEBE ofrecer `FormErrorSummary` como molécula de `packages/ui` (`docs/ui-ux/02` §4, `docs/ui-ux/03` §5). DEBE
recibir la lista de errores (identificador del control y mensaje) por propiedades y mostrar un encabezado con la cantidad de
campos con error, tomado del catálogo `ui` con plural (una forma para uno y otra para varios) en forma impersonal, y una
lista con un enlace por error. Activar un enlace DEBE mover el foco al control correspondiente sin navegar, sin cambiar la
URL ni agregar una entrada al historial. El contenedor DEBE poder recibir el foco de forma programática sin entrar en el
orden de tabulación, para que quien lo use lo enfoque al fallar un envío. Sin errores NO DEBE renderizar nada, ni siquiera un
contenedor vacío. Un error cuyo control no exista en el documento DEBE mostrarse como texto, no como enlace muerto. El
orden de la lista DEBE ser el de la entrada.

#### Escenario: [DS75] Encabezado con el conteo y un enlace por error

- **DADO** un `FormErrorSummary` con dos errores
- **CUANDO** se renderiza
- **ENTONCES** el encabezado dice, exactamente, «Hay 2 campos con errores.»
- **Y** la lista tiene dos elementos, cada uno con un enlace cuyo nombre accesible es el mensaje del error

#### Escenario: [DS76] Un solo error usa la forma singular

- **DADO** un `FormErrorSummary` con un error
- **CUANDO** se renderiza
- **ENTONCES** el encabezado dice, exactamente, «Hay 1 campo con error.»

#### Escenario: [DS77] El contenedor puede recibir el foco sin ser una parada de tabulación

- **DADO** un `FormErrorSummary` con errores
- **CUANDO** el código que lo usa le da el foco de forma programática y luego se recorre la página con `Tab`
- **ENTONCES** el foco se posa en el contenedor, cuyo nombre accesible es su encabezado
- **Y** el contenedor no es una parada del recorrido con `Tab`

#### Escenario: [DS78] Activar un enlace enfoca el control sin navegar

- **DADO** un `FormErrorSummary` con un error cuyo control existe en el documento
- **CUANDO** se activa el enlace con teclado
- **ENTONCES** el foco queda en ese control
- **Y** la URL y la longitud del historial no cambian

#### Escenario: [DS79] Sin errores no se renderiza nada

- **DADO** un `FormErrorSummary` con una lista de errores vacía
- **CUANDO** se renderiza
- **ENTONCES** no agrega ningún elemento al documento

#### Escenario: [DS80] Un error sin control se muestra como texto

- **DADO** un error cuyo control no existe en el documento
- **CUANDO** se renderiza el resumen
- **ENTONCES** ese error aparece como texto sin enlace y los demás errores conservan su enlace

#### Escenario: [DS81] Se conserva el orden de la entrada

- **DADO** errores entregados en el orden B, A, C
- **CUANDO** se renderiza
- **ENTONCES** la lista muestra B, A, C en ese orden

### Requisito: `Alert` comunica severidad con color, icono y texto a la vez y se anuncia según su gravedad

El sistema DEBE ofrecer `Alert` como componente en línea y contextual (`docs/ui-ux/02` §4, `docs/ui-ux/01` §6), con al menos
las variantes informativa, de advertencia y destructiva. Cada variante DEBE comunicar su severidad con color, icono y texto
a la vez: el icono es decorativo (`aria-hidden`) y un texto visualmente oculto del catálogo `ui` nombra la severidad antes
del contenido. La variante destructiva DEBE anunciarse con `role="alert"` (cortesía asertiva) y las demás con
`role="status"` (cortesía `polite`): `assertive` solo para lo que impide continuar (`docs/ui-ux/03` §4). El contenido y el
título opcional DEBEN recibirse por propiedades, sin texto embebido, y omitir el contenido DEBE ser un error de tipos. El
contenedor DEBE poder recibir el foco de forma programática sin entrar en el orden de tabulación.

#### Escenario: [DS82] Cada variante lleva tres canales

- **DADO** las variantes informativa, de advertencia y destructiva, cada una con un contenido
- **CUANDO** se renderizan en modo claro y en modo oscuro
- **ENTONCES** cada una tiene un icono decorativo, un texto de severidad oculto del catálogo y un color propio de su token
  semántico
- **Y** ninguna comunica su severidad solo con el color

#### Escenario: [DS83] El rol depende de la gravedad

- **DADO** una `Alert` destructiva y otra informativa
- **CUANDO** se consultan por rol
- **ENTONCES** la destructiva se encuentra como `alert` y la informativa como `status`

#### Escenario: [DS84] El texto proviene de propiedades y del catálogo

- **DADO** una `Alert` con título y contenido entregados por propiedades
- **CUANDO** se renderiza
- **ENTONCES** muestra ese título y ese contenido, y el único texto propio del componente es el de severidad, tomado del
  catálogo `ui`

#### Escenario: [DS85] Omitir el contenido es un error de tipos

- **DADO** un uso de `Alert` sin contenido
- **CUANDO** se ejecuta la verificación de tipos
- **ENTONCES** falla señalando la propiedad obligatoria ausente

#### Escenario: [DS86] El foco programático no agrega una parada de tabulación

- **DADO** una `Alert` y un código que le da el foco de forma programática
- **CUANDO** se recorre la página con `Tab`
- **ENTONCES** la `Alert` recibe el foco programático y no es una parada del recorrido con `Tab`

#### Escenario: [DS87] El contraste es suficiente en ambos temas

- **DADO** las historias de las tres variantes en modo claro y oscuro
- **CUANDO** corre la puerta de accesibilidad de historias
- **ENTONCES** ninguna historia de `Alert` registra violaciones críticas ni serias, incluido el contraste

## MODIFIED Requirements

### Requisito: `packages/ui` expone un único punto de entrada público y solo el inventario de esta entrega

El sistema DEBE ofrecer `packages/ui` como paquete del espacio de trabajo con un único punto de
entrada público, organizado en `tokens/`, `atoms/`, `molecules/`, `domain/` y `hooks/`
(`docs/ui-ux/02` §3). El punto de entrada DEBE exportar exactamente los diecinueve componentes P0 de
esta entrega: `Button`, `IconButton`, `Input`, `Textarea`, `Label`, `Checkbox`, `RadioGroup`, `Badge`,
`Spinner`, `Separator`, `Skeleton`, `FormField`, `FormError`, `EmptyState`, `ErrorState`,
`MoneyAmount`, `StatusBadge`, `FormErrorSummary` y `Alert`, más dos compañeros de composición:
`RadioGroupItem`, sin el cual `RadioGroup` no es utilizable, y `buttonVariants`, la receta de clases
de `Button` para enlaces con apariencia de botón. En total son veintiún nombres exportados. NO DEBE
exportar componentes del resto del inventario P0 (por ejemplo `Select`, `Toast`, `Dialog` o
`DataTable`) ni ningún token primitivo. Cualquier compañero de composición adicional de `Alert` o de
`FormErrorSummary` cambia el conjunto exacto y exige modificar este requisito antes de implementarse.
(Previously: diecisiete componentes P0 y diecinueve nombres exportados, sin `FormErrorSummary` ni `Alert`.)

#### Escenario: [DS01] Importación desde el punto de entrada público

- **DADO** una aplicación del espacio de trabajo que declara `packages/ui` como dependencia
- **CUANDO** importa `MoneyAmount` desde el punto de entrada público del paquete
- **ENTONCES** la importación compila y resuelve el componente

#### Escenario: [DS02] El conjunto exportado coincide con el inventario

- **DADO** el punto de entrada público de `packages/ui`
- **CUANDO** una prueba enumera sus exportaciones de componentes
- **ENTONCES** el conjunto es exactamente el de los diecinueve componentes listados más
  `RadioGroupItem` y `buttonVariants`, veintiún nombres, sin uno más ni uno menos
- **Y** ninguna exportación es un token primitivo

#### Escenario: [DS03] Componente fuera del alcance de esta entrega

- **DADO** el punto de entrada público de `packages/ui`
- **CUANDO** una aplicación intenta importar `DataTable` o `Select`
- **ENTONCES** la verificación de tipos falla porque esas exportaciones no existen

#### Escenario: [DS88] Los componentes nuevos se importan del punto de entrada público

- **DADO** una aplicación del espacio de trabajo que declara `packages/ui` como dependencia
- **CUANDO** importa `FormErrorSummary` y `Alert` desde el punto de entrada público del paquete
- **ENTONCES** la importación compila y resuelve los dos componentes

#### Escenario: [DS89] `Dialog` no existe (brecha con destino: cambio 13b-2)

- **DADO** el punto de entrada público de `packages/ui`
- **CUANDO** una aplicación intenta importar `Dialog`
- **ENTONCES** la verificación de tipos falla porque esa exportación no existe
- **Y** este escenario deja de ser cierto el día que el cambio 13b-2 entregue `Dialog`

### Requisito: Átomos P0 con estados completos y accesibilidad por defecto

El sistema DEBE ofrecer los átomos `Button`, `IconButton`, `Input`, `Textarea`, `Label`,
`Checkbox`, `RadioGroup`, `Badge`, `Spinner`, `Separator` y `Skeleton` sobre shadcn/ui y Radix
(`docs/ui-ux/02` §4). `IconButton` DEBE exigir una etiqueta accesible: omitirla DEBE ser un error de
tipos. Todo control interactivo DEBE mostrar un anillo de foco visible con
`--color-border-focus` y NO DEBE eliminar el contorno sin reemplazo. El anillo de foco de `Button`
DEBE aparecer en el mismo fotograma en que el botón recibe el foco: ninguna propiedad de contorno
(`outline-color`, `outline-width`, `outline-style` ni `outline-offset`) DEBE participar en una
transición. Un campo de solo lectura DEBE
poder seleccionarse y copiarse; un campo deshabilitado no. `Button` en estado de carga DEBE
permanecer enfocable y marcarse `aria-busy`, sin atributo `disabled` (`docs/ui-ux/03` §5).
(Previously: sin la exigencia sobre la transición del anillo de foco de `Button`, pendiente W2 de 13a.)

#### Escenario: [DS15] `IconButton` sin etiqueta accesible

- **DADO** un uso de `IconButton` sin la propiedad de etiqueta accesible
- **CUANDO** se ejecuta la verificación de tipos
- **ENTONCES** falla señalando la propiedad obligatoria ausente

#### Escenario: [DS16] Anillo de foco visible

- **DADO** un `Button`, un `Input` y un `Checkbox` renderizados
- **CUANDO** cada uno recibe foco por teclado
- **ENTONCES** muestra un indicador de foco basado en `--color-border-focus` y ninguno declara
  `outline: none` sin un reemplazo visible

#### Escenario: [DS17] Botón en estado de carga

- **DADO** un `Button` en estado de carga con un manejador de clic
- **CUANDO** una persona lo activa con teclado o ratón
- **ENTONCES** el botón sigue siendo enfocable, expone `aria-busy="true"`, muestra un `Spinner` y no
  invoca de nuevo el manejador

#### Escenario: [DS18] Solo lectura frente a deshabilitado

- **DADO** un `Input` de solo lectura y un `Input` deshabilitado, ambos con el mismo valor
- **CUANDO** se inspeccionan
- **ENTONCES** el de solo lectura es enfocable y su valor es seleccionable, y el deshabilitado no es
  enfocable

#### Escenario: [DS19] `Checkbox` y `RadioGroup` operables con teclado

- **DADO** un `Checkbox` y un `RadioGroup` con tres opciones
- **CUANDO** se operan solo con teclado
- **ENTONCES** la barra espaciadora alterna el `Checkbox` y las flechas mueven la selección del
  `RadioGroup`, con cada opción anunciada con su etiqueta

#### Escenario: [DS90] El anillo de foco de `Button` no se desvanece

- **DADO** un `Button` renderizado en Chromium con un motor de estilos real
- **CUANDO** se lee su estilo calculado (`transition-property` y `transition-duration`) y luego recibe el foco por teclado
- **ENTONCES** ninguna de las propiedades de contorno figura entre las que transicionan, ni por nombre ni por `all`
- **Y** el color y el ancho del contorno en el primer fotograma tras el foco son los mismos que los finales

### Requisito: `EmptyState` siempre con acción principal y `ErrorState` siempre con reintento

El sistema DEBE exigir que `EmptyState` reciba una acción principal y que `ErrorState` reciba una
función de reintento: omitirlas DEBE ser un error de tipos. `ErrorState` DEBE anunciar el error a
las tecnologías de asistencia mediante una región activa y ofrecer el reintento como control
operable por teclado. El control de reintento de `ErrorState` DEBE tener siempre un nombre
accesible no vacío: ante una etiqueta vacía o formada solo por espacios DEBE usar la etiqueta por
defecto del catálogo `ui`. `EmptyState` y `ErrorState` NO DEBEN renderizar un párrafo vacío cuando
no reciben descripción.
(Previously: sin la exigencia de nombre accesible no vacío del reintento ni de ausencia de párrafos vacíos, pendiente W3 de 13a.)

#### Escenario: [DS25] `EmptyState` sin acción principal

- **DADO** un uso de `EmptyState` sin acción principal
- **CUANDO** se ejecuta la verificación de tipos
- **ENTONCES** falla señalando la propiedad obligatoria ausente

#### Escenario: [DS26] `EmptyState` con acción principal

- **DADO** un `EmptyState` con título, descripción y una acción principal
- **CUANDO** se renderiza y se activa la acción
- **ENTONCES** la acción es un control con nombre accesible y se invoca exactamente una vez por
  activación

#### Escenario: [DS27] `ErrorState` sin reintento

- **DADO** un uso de `ErrorState` sin función de reintento
- **CUANDO** se ejecuta la verificación de tipos
- **ENTONCES** falla señalando la propiedad obligatoria ausente

#### Escenario: [DS28] `ErrorState` anuncia y permite reintentar

- **DADO** un `ErrorState` con mensaje y función de reintento
- **CUANDO** se renderiza y se activa el control de reintento con teclado
- **ENTONCES** el mensaje está dentro de una región activa y la función de reintento se invoca
  exactamente una vez

#### Escenario: [DS91] Una etiqueta de reintento vacía usa la del catálogo

- **DADO** un `ErrorState` con la etiqueta de reintento vacía, y otro con la etiqueta formada solo por espacios
- **CUANDO** se consulta el control de reintento por rol
- **ENTONCES** su nombre accesible es el texto por defecto del catálogo `ui` y no es vacío

#### Escenario: [DS92] Sin descripción no hay párrafo vacío

- **DADO** un `EmptyState` y un `ErrorState` sin descripción, y otros con la descripción como cadena vacía
- **CUANDO** se renderizan
- **ENTONCES** ninguno contiene un elemento `p` sin texto

### Requisito: Todo texto de los componentes proviene del catálogo `es-HN`, con inglés preparado sin traducir

El sistema DEBE servir todo texto de interfaz de `packages/ui` desde catálogos de i18next:
`es-HN` como idioma predeterminado completo, y la infraestructura de inglés preparada sin
traducciones reales (ADR-0011). «Inglés preparado» significa que basta con añadir el catálogo
`packages/ui/src/i18n/locales/en/ui.json` y registrar `en` en `supportedLngs`; hoy ese catálogo no
existe y `supportedLngs` solo contiene `es-HN`. Ningún componente DEBE contener cadenas visibles o de nombre
accesible embebidas. Si el idioma activo carece de una clave, el sistema DEBE usar el texto de
`es-HN` y NO DEBE mostrar la clave cruda a la persona usuaria. El catálogo `ui` lo comparten el panel
y el portal, que tienen distinto trato a la persona, así que sus textos NO DEBEN dirigirse a la
persona en segunda persona: ni formas de tuteo, ni de «usted», ni pronombres o posesivos de segunda
persona. Los textos que se dirigen a la persona viven en el catálogo de cada aplicación.
(Previously: sin la exigencia de que los textos del catálogo `ui` no se dirijan a la persona, decisión P1.)

#### Escenario: [DS57] Texto desde el catálogo por defecto

- **DADO** el idioma predeterminado y un `StatusBadge` en estado `paid`
- **CUANDO** se renderiza
- **ENTONCES** el texto «Pagado» proviene del catálogo `es-HN`

#### Escenario: [DS58] Idioma sin traducción

- **DADO** el idioma activo `en` sin traducciones reales
- **CUANDO** se renderiza un `StatusBadge` en estado `paid`
- **ENTONCES** muestra el texto de `es-HN` y no la clave `common.status.paid`

#### Escenario: [DS59] Catálogo completo para los componentes

- **DADO** el catálogo `es-HN` y todas las claves que usan los componentes de `packages/ui`
- **CUANDO** se ejecuta la verificación de claves
- **ENTONCES** todas existen en el catálogo y la verificación evalúa al menos una clave real

#### Escenario: [DS60] Los componentes se prueban con el proveedor de idioma

- **DADO** una prueba de componente que renderiza un componente con texto
- **CUANDO** el entorno de pruebas inicializa el idioma predeterminado
- **ENTONCES** el texto visible es el del catálogo `es-HN` y no una clave ni un valor vacío

#### Escenario: [DS93] Ningún texto del catálogo `ui` se dirige a la persona

- **DADO** todos los valores de `packages/ui/src/i18n/locales/es-HN/ui.json`, incluidas las claves de `FormErrorSummary` y de
  `Alert`
- **CUANDO** una prueba los compara con la lista de formas de segunda persona que fija el diseño (tuteo, «usted», pronombres
  y posesivos)
- **ENTONCES** ninguno coincide

#### Escenario: [DS94] La prueba de voz rechaza una segunda persona

- **DADO** las violaciones deliberadas de un valor de catálogo con «Revisa los campos» y otro con «Revise los campos»
- **CUANDO** corre la prueba de voz del catálogo `ui`
- **ENTONCES** falla en los dos casos nombrando la clave

### Requisito: Cada componente tiene historias con sus estados en modo claro y oscuro

El sistema DEBE ofrecer en Storybook al menos una historia por componente exportado, con sus
estados aplicables: por defecto, cada variante, carga, vacío, error y deshabilitado
(`docs/ui-ux/02` §8). Las historias de caso límite de `MoneyAmount` DEBEN incluir cero, negativo e
importe grande. Las historias de `Alert` DEBEN incluir cada variante, con y sin título, y las de
`FormErrorSummary` DEBEN incluir un error, varios errores y un error cuyo control no existe. Cada
historia DEBE poder verse en modo claro y oscuro.
(Previously: sin las historias de `Alert` ni de `FormErrorSummary`.)

#### Escenario: [DS62] Cada componente exportado tiene historia

- **DADO** el conjunto de componentes exportados por el punto de entrada público
- **CUANDO** se compara con el conjunto de componentes que tienen al menos una historia
- **ENTONCES** son el mismo conjunto

#### Escenario: [DS63] Historias de estado

- **DADO** los componentes con estado de carga, vacío, error o deshabilitado aplicable (`Button`,
  `Input`, `Skeleton`, `EmptyState`, `ErrorState`, `FormField`)
- **CUANDO** se listan sus historias
- **ENTONCES** cada estado aplicable tiene su historia
- **Y** la historia de error de `Input` se llama `Invalid`; `EmptyState` cuenta su historia `Default`
  como su estado de vacío, y `ErrorState` cuenta su historia `Default` como su estado de error

#### Escenario: [DS64] Casos límite de `MoneyAmount`

- **DADO** las historias de `MoneyAmount`
- **CUANDO** se listan
- **ENTONCES** incluyen cero, un importe negativo, un importe grande y las monedas `HNL` y `USD`

#### Escenario: [DS65] Ambos temas disponibles

- **DADO** cualquier historia
- **CUANDO** se alterna entre modo claro y modo oscuro
- **ENTONCES** la historia se renderiza en ambos temas sin cambiar su contenido

#### Escenario: [DS95] Historias de los componentes nuevos

- **DADO** las historias de `Alert` y de `FormErrorSummary`
- **CUANDO** se listan
- **ENTONCES** `Alert` tiene una historia por cada variante y una con título y otra sin él
- **Y** `FormErrorSummary` tiene una historia con un error, otra con varios y otra con un error sin control

### Requisito: Ninguna historia tiene violaciones críticas o serias de accesibilidad

Todas las historias de `packages/ui` DEBEN terminar sin violaciones críticas ni serias de axe, en
modo claro y en modo oscuro (`docs/ui-ux/03` §9 y §11). La verificación DEBE evaluar el contraste
de color con un motor de estilos real; una regla de contraste que axe no pueda decidir
(«incompleta») NO DEBE contarse como aprobada. Las violaciones moderadas DEBEN advertirse sin romper
la verificación, y las menores DEBEN registrarse. La verificación NO DEBE aprobar un conjunto vacío
de historias (ADR-0018). La política de bloqueo (las etiquetas de axe y los impactos que rompen la
construcción) DEBE tener una única fuente en `@confia/config`, la misma que consume la puerta sobre
rutas renderizadas de `apps/admin-web`; `packages/ui` NO DEBE conservar una copia.
(Previously: la política vivía en `packages/ui/src/a11y/policy.ts`; sin la exigencia de fuente única en `@confia/config`.)

#### Escenario: [DS66] Historia deliberadamente inaccesible

- **DADO** una historia de violación deliberada con un botón sin nombre accesible
- **CUANDO** se ejecuta la verificación de historias
- **ENTONCES** falla nombrando la historia y la regla de axe incumplida, con impacto crítico o serio

#### Escenario: [DS67] Contraste insuficiente detectado

- **DADO** una historia de violación deliberada con texto de bajo contraste
- **CUANDO** se ejecuta la verificación de historias
- **ENTONCES** falla por la regla de contraste, lo que demuestra que esa regla se evalúa de verdad

#### Escenario: [DS68] Violación solo en modo oscuro

- **DADO** una historia de violación deliberada cuyo contraste es suficiente en modo claro e
  insuficiente en modo oscuro
- **CUANDO** se ejecuta la verificación de historias
- **ENTONCES** falla, porque cada historia se verifica en ambos temas

#### Escenario: [DS69] Violación moderada

- **DADO** una historia con una violación de impacto moderado
- **CUANDO** se ejecuta la verificación de historias
- **ENTONCES** la violación se reporta como advertencia y la verificación no falla por esa causa

#### Escenario: [DS70] Historias reales sin violaciones bloqueantes

- **DADO** las historias reales de `packages/ui`, excluidas las de violación deliberada
- **CUANDO** se ejecuta la verificación de historias
- **ENTONCES** termina en verde, habiendo evaluado al menos una historia por componente exportado

#### Escenario: [DS71] Cero historias evaluadas

- **DADO** una configuración en la que la verificación no encuentra ninguna historia
- **CUANDO** se ejecuta la verificación de historias
- **ENTONCES** falla en lugar de aprobar un conjunto vacío

#### Escenario: [DS96] La puerta de historias usa la política de `@confia/config`

- **DADO** la puerta de historias de `packages/ui` y el panel de accesibilidad de Storybook
- **CUANDO** se inspeccionan sus importaciones de la política de bloqueo
- **ENTONCES** las dos importan de `@confia/config/a11y`
- **Y** no existe `packages/ui/src/a11y/policy.ts`

#### Escenario: [DS97] La puerta de historias sigue en verde y sigue fallando donde debe

- **DADO** la política movida a `@confia/config`
- **CUANDO** se ejecuta la verificación de historias sobre las historias reales y sobre las de violación deliberada
- **ENTONCES** termina en verde con las primeras y falla con cada una de las segundas, igual que antes del traslado
