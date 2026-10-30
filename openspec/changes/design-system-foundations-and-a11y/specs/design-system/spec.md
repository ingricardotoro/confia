# Capacidad: Sistema de diseño

- **Identificador:** design-system
- **Estado:** Borrador, pendiente de aprobación del propietario del producto
- **Fase:** F0 (cambio 13a, entregable 10)

## Propósito

Define el comportamiento observable de `packages/ui`: los tokens de diseño en tres niveles con
modo claro y oscuro, los componentes P0 de esta primera entrega, el formato de importes sin
aritmética en el navegador, el estado financiero con tres canales, los textos servidos solo desde
catálogos de internacionalización y la ausencia de violaciones críticas o serias de accesibilidad
en todas las historias del catálogo. Las fuentes de verdad son `docs/ui-ux/01` a `03`, ADR-0011 y
ADR-0018. Esta capacidad describe qué debe ser cierto; la forma de lograrlo pertenece al diseño.

Esta capacidad **no cierra** el criterio de salida 8 de F0 («sin violaciones críticas en las
pantallas existentes»): no existen pantallas, y un conjunto vacío no es verde (ADR-0018). Deja el
mecanismo listo para que la primera pantalla real lo cierre.

## Requisitos

### Requisito: `packages/ui` expone un único punto de entrada público y solo el inventario de esta entrega

El sistema DEBE ofrecer `packages/ui` como paquete del espacio de trabajo con un único punto de
entrada público, organizado en `tokens/`, `atoms/`, `molecules/`, `domain/` y `hooks/`
(`docs/ui-ux/02` §3). El punto de entrada DEBE exportar exactamente los componentes P0 de esta
entrega: `Button`, `IconButton`, `Input`, `Textarea`, `Label`, `Checkbox`, `RadioGroup`, `Badge`,
`Spinner`, `Separator`, `Skeleton`, `FormField`, `FormError`, `EmptyState`, `ErrorState`,
`MoneyAmount` y `StatusBadge`. NO DEBE exportar componentes del resto del inventario P0 (por
ejemplo `Select`, `Toast` o `DataTable`) ni ningún token primitivo.

#### Escenario: Importación desde el punto de entrada público

- **DADO** una aplicación del espacio de trabajo que declara `packages/ui` como dependencia
- **CUANDO** importa `MoneyAmount` desde el punto de entrada público del paquete
- **ENTONCES** la importación compila y resuelve el componente

#### Escenario: El conjunto exportado coincide con el inventario

- **DADO** el punto de entrada público de `packages/ui`
- **CUANDO** una prueba enumera sus exportaciones de componentes
- **ENTONCES** el conjunto es exactamente el de los diecisiete componentes listados, sin uno más ni
  uno menos
- **Y** ninguna exportación es un token primitivo

#### Escenario: Componente fuera del alcance de esta entrega

- **DADO** el punto de entrada público de `packages/ui`
- **CUANDO** una aplicación intenta importar `DataTable` o `Select`
- **ENTONCES** la verificación de tipos falla porque esas exportaciones no existen

### Requisito: Tokens en tres niveles, con los primitivos fuera de la superficie utilizable

El sistema DEBE definir los valores visuales en tres niveles: primitivos, semánticos y de
componente (`docs/ui-ux/01` §1). Los primitivos DEBEN existir solo como la tabla de definición
dentro de `@theme` y NO DEBEN ser utilizables por ningún componente. Los componentes DEBEN consumir
únicamente tokens semánticos o de componente. Ningún componente DEBE contener un valor visual
literal (color hexadecimal, `rgb()`, `hsl()` ni valor arbitrario de Tailwind con color) que evada
los tokens. Cambiar la marca DEBE consistir en editar los primitivos y la familia tipográfica, nunca
en editar componentes.

#### Escenario: Un componente consume un token primitivo

- **DADO** un componente de violación deliberada que usa un primitivo (por ejemplo
  `--color-blue-600` o la utilidad `bg-blue-600`)
- **CUANDO** se ejecuta la prueba que inspecciona el código de los componentes
- **ENTONCES** la prueba falla nombrando el componente y el primitivo consumido

#### Escenario: Un componente define un color literal

- **DADO** un componente de violación deliberada con un color `#2563EB` escrito en línea
- **CUANDO** se ejecuta la misma prueba
- **ENTONCES** falla nombrando el componente y el literal

#### Escenario: Los componentes reales solo consumen semánticos y de componente

- **DADO** todos los componentes reales de `packages/ui`, excluidas las violaciones deliberadas
- **CUANDO** se ejecuta la prueba
- **ENTONCES** pasa evaluando al menos un componente real, y no un conjunto vacío

#### Escenario: Cambiar la marca no toca componentes

- **DADO** la cadena de referencias primitivo → semántico → componente de la salida CSS
- **CUANDO** se inspecciona `--color-primary` y `--button-primary-bg`
- **ENTONCES** `--color-primary` se resuelve por referencia a un primitivo y `--button-primary-bg`
  por referencia a `--color-primary`, de modo que redefinir el primitivo cambia la apariencia del
  botón sin editar el componente

### Requisito: Modo claro y modo oscuro por preferencia del sistema y por atributo `data-theme`

El sistema DEBE soportar los dos temas por dos mecanismos: la preferencia del sistema operativo
(`prefers-color-scheme: dark`) y el atributo `data-theme` en el elemento raíz, con `data-theme`
ganando siempre sobre la preferencia del sistema (`docs/ui-ux/01` §6). Todo token DEBE tener un
valor base en `:root`; ningún color DEBE definirse únicamente dentro del bloque oscuro. Cada
componente DEBE renderizar correctamente en ambos temas.

#### Escenario: Preferencia oscura del sistema sin atributo

- **DADO** un sistema con preferencia de modo oscuro y un documento sin `data-theme`
- **CUANDO** se renderiza un componente
- **ENTONCES** los tokens semánticos resuelven a sus valores oscuros

#### Escenario: El atributo manual gana sobre la preferencia del sistema

- **DADO** un sistema con preferencia de modo oscuro y el documento con `data-theme="light"`
- **CUANDO** se renderiza un componente
- **ENTONCES** los tokens semánticos resuelven a sus valores claros

#### Escenario: Atributo oscuro sobre un sistema claro

- **DADO** un sistema con preferencia de modo claro y el documento con `data-theme="dark"`
- **CUANDO** se renderiza un componente
- **ENTONCES** los tokens semánticos resuelven a sus valores oscuros

#### Escenario: Color definido solo en el bloque oscuro

- **DADO** una definición deliberada de un token que existe únicamente dentro del bloque oscuro
- **CUANDO** se ejecuta la prueba que compara los tokens de `:root` con los del bloque oscuro
- **ENTONCES** la prueba falla nombrando el token sin valor base

#### Escenario: Todos los tokens tienen valor base

- **DADO** la salida CSS real de los tokens
- **CUANDO** se ejecuta la misma prueba
- **ENTONCES** todo token con valor oscuro tiene también su valor base en `:root`

### Requisito: Marca provisional reemplazable y tipografía servida desde el propio paquete

El sistema DEBE concentrar la marca provisional (Inter, primario `#2563EB`) en los primitivos y en
la familia tipográfica. Inter DEBE servirse desde el propio paquete: el sistema NO DEBE realizar
peticiones a CDN ni a dominios de terceros para fuentes. La familia tipográfica DEBE declarar las
fuentes de respaldo de `docs/ui-ux/01` §7.1 para el caso en que Inter no cargue.

#### Escenario: Ninguna petición de fuentes a terceros

- **DADO** una historia renderizada con la red limitada al origen local
- **CUANDO** el navegador resuelve la familia tipográfica
- **ENTONCES** ninguna petición sale hacia un dominio distinto del origen y la fuente se obtiene del
  propio paquete

#### Escenario: Respaldo tipográfico

- **DADO** la declaración de `--font-family-sans`
- **CUANDO** se inspecciona su valor
- **ENTONCES** lista `Inter` seguida de `ui-sans-serif`, `system-ui` y las fuentes genéricas de
  respaldo

### Requisito: Átomos P0 con estados completos y accesibilidad por defecto

El sistema DEBE ofrecer los átomos `Button`, `IconButton`, `Input`, `Textarea`, `Label`,
`Checkbox`, `RadioGroup`, `Badge`, `Spinner`, `Separator` y `Skeleton` sobre shadcn/ui y Radix
(`docs/ui-ux/02` §4). `IconButton` DEBE exigir una etiqueta accesible: omitirla DEBE ser un error
de tipos. Todo control interactivo DEBE mostrar un anillo de foco visible con
`--color-border-focus` y NO DEBE eliminar el contorno sin reemplazo. Un campo de solo lectura DEBE
poder seleccionarse y copiarse; un campo deshabilitado no. `Button` en estado de carga DEBE
permanecer enfocable y marcarse `aria-busy`, sin atributo `disabled` (`docs/ui-ux/03` §5).

#### Escenario: `IconButton` sin etiqueta accesible

- **DADO** un uso de `IconButton` sin la propiedad de etiqueta accesible
- **CUANDO** se ejecuta la verificación de tipos
- **ENTONCES** falla señalando la propiedad obligatoria ausente

#### Escenario: Anillo de foco visible

- **DADO** un `Button`, un `Input` y un `Checkbox` renderizados
- **CUANDO** cada uno recibe foco por teclado
- **ENTONCES** muestra un indicador de foco basado en `--color-border-focus` y ninguno declara
  `outline: none` sin un reemplazo visible

#### Escenario: Botón en estado de carga

- **DADO** un `Button` en estado de carga con un manejador de clic
- **CUANDO** una persona lo activa con teclado o ratón
- **ENTONCES** el botón sigue siendo enfocable, expone `aria-busy="true"`, muestra un `Spinner` y no
  invoca de nuevo el manejador

#### Escenario: Solo lectura frente a deshabilitado

- **DADO** un `Input` de solo lectura y un `Input` deshabilitado, ambos con el mismo valor
- **CUANDO** se inspeccionan
- **ENTONCES** el de solo lectura es enfocable y su valor es seleccionable, y el deshabilitado no es
  enfocable

#### Escenario: `Checkbox` y `RadioGroup` operables con teclado

- **DADO** un `Checkbox` y un `RadioGroup` con tres opciones
- **CUANDO** se operan solo con teclado
- **ENTONCES** la barra espaciadora alterna el `Checkbox` y las flechas mueven la selección del
  `RadioGroup`, con cada opción anunciada con su etiqueta

### Requisito: `FormField` une etiqueta, control, descripción y error

El sistema DEBE ofrecer `FormField` como una unidad que asocia por identificador la etiqueta
visible, el control, la descripción y el mensaje de error (`docs/ui-ux/03` §5). El error DEBE
identificarse en texto, asociarse con `aria-describedby` y marcar el control con `aria-invalid`;
NO DEBE depender solo del color. Un campo obligatorio DEBE marcarse en texto, no solo con asterisco.

#### Escenario: Etiqueta asociada al control

- **DADO** un `FormField` con una etiqueta y un `Input`
- **CUANDO** se consulta el control por su nombre accesible
- **ENTONCES** el nombre accesible es el texto de la etiqueta

#### Escenario: Campo con error

- **DADO** un `FormField` con un mensaje de error
- **CUANDO** se renderiza
- **ENTONCES** el control tiene `aria-invalid="true"`, su `aria-describedby` referencia el elemento
  del mensaje de error, y el mensaje está presente como texto

#### Escenario: Descripción y error a la vez

- **DADO** un `FormField` con descripción y con error
- **CUANDO** se renderiza
- **ENTONCES** el `aria-describedby` del control referencia ambos elementos

#### Escenario: Campo obligatorio marcado en texto

- **DADO** un `FormField` obligatorio
- **CUANDO** se renderiza
- **ENTONCES** la indicación de obligatorio es texto disponible para lectores de pantalla, tomado del
  catálogo, y no solo un asterisco

#### Escenario: Campo sin error

- **DADO** un `FormField` sin mensaje de error
- **CUANDO** se renderiza
- **ENTONCES** el control no tiene `aria-invalid="true"` y no existe un elemento de error vacío
  referenciado

### Requisito: `EmptyState` siempre con acción principal y `ErrorState` siempre con reintento

El sistema DEBE exigir que `EmptyState` reciba una acción principal y que `ErrorState` reciba una
función de reintento: omitirlas DEBE ser un error de tipos. `ErrorState` DEBE anunciar el error a
las tecnologías de asistencia mediante una región activa y ofrecer el reintento como control
operable por teclado.

#### Escenario: `EmptyState` sin acción principal

- **DADO** un uso de `EmptyState` sin acción principal
- **CUANDO** se ejecuta la verificación de tipos
- **ENTONCES** falla señalando la propiedad obligatoria ausente

#### Escenario: `EmptyState` con acción principal

- **DADO** un `EmptyState` con título, descripción y una acción principal
- **CUANDO** se renderiza y se activa la acción
- **ENTONCES** la acción es un control con nombre accesible y se invoca exactamente una vez por
  activación

#### Escenario: `ErrorState` sin reintento

- **DADO** un uso de `ErrorState` sin función de reintento
- **CUANDO** se ejecuta la verificación de tipos
- **ENTONCES** falla señalando la propiedad obligatoria ausente

#### Escenario: `ErrorState` anuncia y permite reintentar

- **DADO** un `ErrorState` con mensaje y función de reintento
- **CUANDO** se renderiza y se activa el control de reintento con teclado
- **ENTONCES** el mensaje está dentro de una región activa y la función de reintento se invoca
  exactamente una vez

### Requisito: `MoneyAmount` formatea el importe del contrato solo con `Intl.NumberFormat`

El sistema DEBE ofrecer `MoneyAmount`, que recibe el `Money` del contrato generado,
`{ amount: string, currency: string }`, ya redondeado por el servidor, y lo formatea **solo** con
`Intl.NumberFormat`, con estilo de moneda y la moneda del propio importe, siempre con dos decimales
(ADR-0011 «Presentación»; `docs/ui-ux/02` §5.1). El cero DEBE mostrarse como `0.00`, nunca vacío ni
guion. Un importe negativo DEBE mostrar el signo menos. El componente NO DEBE redondear, truncar ni
alterar el valor recibido.

#### Escenario: Importe en lempiras

- **DADO** el valor `{ amount: "3200.00", currency: "HNL" }`
- **CUANDO** se renderiza `MoneyAmount`
- **ENTONCES** el texto visible es el que produce `Intl.NumberFormat` para el idioma activo con
  estilo de moneda y `HNL`, y contiene `3,200.00` con el símbolo de lempira (`L 3,200.00`)

#### Escenario: Importe en dólares

- **DADO** el valor `{ amount: "3200.00", currency: "USD" }`
- **CUANDO** se renderiza `MoneyAmount`
- **ENTONCES** el texto contiene `3,200.00` y un indicador de dólar estadounidense distinto del de
  lempira, de modo que dos importes iguales en monedas distintas no se confunden

#### Escenario: Cero

- **DADO** el valor `{ amount: "0.00", currency: "HNL" }`
- **CUANDO** se renderiza `MoneyAmount`
- **ENTONCES** el texto contiene `0.00` y no está vacío ni es un guion

#### Escenario: Cero negativo

- **DADO** los valores `{ amount: "-0.00", currency: "HNL" }` y `{ amount: "-0.0000", currency: "HNL" }`
- **CUANDO** se renderiza `MoneyAmount`
- **ENTONCES** el texto contiene `0.00` y no muestra signo menos

#### Escenario: Negativo

- **DADO** el valor `{ amount: "-1500.00", currency: "HNL" }`
- **CUANDO** se renderiza `MoneyAmount`
- **ENTONCES** el texto contiene el signo menos y `1,500.00`, y no se presenta como positivo

#### Escenario: Negativo en contexto contable

- **DADO** el valor `{ amount: "-1500.00", currency: "HNL" }` con el contexto contable declarado
  por quien lo usa
- **CUANDO** se renderiza `MoneyAmount`
- **ENTONCES** el negativo se muestra entre paréntesis y no solo con signo menos

#### Escenario: Importe máximo del contrato

- **DADO** el valor `{ amount: "9999999999.99", currency: "HNL" }` y su negativo
- **CUANDO** se renderiza `MoneyAmount`
- **ENTONCES** el texto contiene `9,999,999,999.99`, con el signo menos en el caso negativo

#### Escenario: Importe más allá de la precisión de `number`

- **DADO** el valor `{ amount: "123456789012345678.90", currency: "HNL" }`
- **CUANDO** se renderiza `MoneyAmount`
- **ENTONCES** el texto contiene exactamente `123,456,789,012,345,678.90`, sin pérdida de dígitos,
  lo que demuestra que el importe nunca pasa por un `number`

#### Escenario: Entrada de cuatro decimales ya redondeada por el servidor

- **DADO** los valores `"3200.0000"`, `"3200.1000"` y `"3200.500"` con moneda `HNL`
- **CUANDO** se renderiza `MoneyAmount` con cada uno
- **ENTONCES** los textos contienen respectivamente `3,200.00`, `3,200.10` y `3,200.50`, porque los
  decimales sobrantes son ceros y no alteran el valor

#### Escenario: Entrada con menos de dos decimales

- **DADO** los valores `"3200"` y `"3200.5"` con moneda `HNL`
- **CUANDO** se renderiza `MoneyAmount` con cada uno
- **ENTONCES** los textos contienen `3,200.00` y `3,200.50`, siempre con dos decimales

#### Escenario: Formato regional con el idioma activo

- **DADO** el idioma activo `es-HN` y el valor `{ amount: "1500.00", currency: "HNL" }`
- **CUANDO** se renderiza `MoneyAmount`
- **ENTONCES** el texto visible es idéntico a `new Intl.NumberFormat("es-HN", { style: "currency",
  currency: "HNL" }).format("1500.00")`, sin concatenación manual de símbolo, separador ni signo

### Requisito: `MoneyAmount` no contiene aritmética, redondeo ni conversión a `number` del importe

El código de `MoneyAmount` y de sus utilidades de formato NO DEBE realizar aritmética, redondeo ni
conversión a `number` sobre el importe: ni `Number()`, `parseFloat()`, `parseInt()`, `+valor`,
`Math.*`, `toFixed()` ni operadores aritméticos sobre la cadena del importe (`CLAUDE.md`, reglas 1
y 3). La validación del formato de la cadena DEBE hacerse sin convertirla a `number`.

#### Escenario: Conversión a `number` en el código de `MoneyAmount`

- **DADO** una violación deliberada que convierte el importe con `Number(value.amount)`
- **CUANDO** se ejecuta el análisis estático de `packages/ui`
- **ENTONCES** falla nombrando el archivo y la regla incumplida

#### Escenario: Redondeo manual del importe

- **DADO** una violación deliberada que aplica `Math.round` o `toFixed(2)` al importe
- **CUANDO** se ejecuta el análisis estático de `packages/ui`
- **ENTONCES** falla nombrando el archivo y la regla incumplida

#### Escenario: El código real de `MoneyAmount` cumple la regla

- **DADO** el código real de `MoneyAmount` y de sus utilidades de formato
- **CUANDO** se ejecuta el análisis estático
- **ENTONCES** pasa evaluando esos archivos, y no un conjunto vacío

### Requisito: `MoneyAmount` rechaza visiblemente la entrada fuera del patrón del contrato

El sistema DEBE tratar como inválido todo valor que no sea una cadena decimal con signo opcional y
con a lo sumo dos decimales significativos, o cuya moneda no sea un código de tres letras
mayúsculas. Ante un valor inválido, `MoneyAmount` NO DEBE mostrar una cifra adivinada, un cero, un
vacío ni un redondeo silencioso; NO DEBE lanzar una excepción que derribe el resto de la pantalla;
DEBE mostrar un texto de «importe no disponible» tomado del catálogo, con nombre accesible
equivalente.

#### Escenario: Más de dos decimales significativos

- **DADO** el valor `{ amount: "3200.005", currency: "HNL" }`, que el servidor debió haber redondeado
- **CUANDO** se renderiza `MoneyAmount`
- **ENTONCES** muestra el texto de importe no disponible y no `3,200.01` ni `3,200.00`

#### Escenario: Cadena que no es decimal

- **DADO** los valores `"abc"`, `""`, `" 12.00"`, `"1e3"`, `"1,500.00"`, `"+5.00"` y `"12."`
- **CUANDO** se renderiza `MoneyAmount` con cada uno
- **ENTONCES** cada uno muestra el texto de importe no disponible

#### Escenario: Importe que no es una cadena en tiempo de ejecución

- **DADO** un valor `{ amount: 1500, currency: "HNL" }` introducido evadiendo los tipos
- **CUANDO** se renderiza `MoneyAmount`
- **ENTONCES** muestra el texto de importe no disponible y no `1,500.00`

#### Escenario: Moneda mal formada

- **DADO** los valores de moneda `"hnl"`, `"HN"`, `"HNLL"` y `""` con un importe válido
- **CUANDO** se renderiza `MoneyAmount` con cada uno
- **ENTONCES** cada uno muestra el texto de importe no disponible y la pantalla no se rompe

#### Escenario: El texto de importe no disponible viene del catálogo

- **DADO** el texto de importe no disponible mostrado por un valor inválido
- **CUANDO** se busca su origen
- **ENTONCES** proviene de una clave del catálogo `es-HN` y no de un literal en el componente

### Requisito: `MoneyAmount` es legible por lector de pantalla y mantiene su presentación tabular

`MoneyAmount` DEBE aplicar numeración tabular siempre. Su nombre accesible DEBE incluir la cantidad
y el nombre de la moneda según `Intl`, para que un lector de pantalla no anuncie un número sin
moneda (`docs/ui-ux/03` §10). `colorBySign` DEBE ser opcional y aplicarse solo si se solicita
explícitamente; el signo DEBE permanecer en el texto, de modo que el color nunca sea el único
portador. `showPositiveSign` DEBE anteponer el signo más solo a los positivos.

#### Escenario: Numeración tabular

- **DADO** cualquier `MoneyAmount` válido
- **CUANDO** se inspecciona su presentación
- **ENTONCES** aplica numeración tabular

#### Escenario: Nombre accesible con moneda

- **DADO** los valores `{ amount: "3200.00", currency: "HNL" }` y `{ amount: "3200.00", currency: "USD" }`
- **CUANDO** se consulta el nombre accesible de cada uno
- **ENTONCES** el primero incluye `3,200.00` y el nombre de la moneda lempira, y el segundo incluye
  `3,200.00` y el nombre de la moneda dólar, ambos tomados de `Intl`

#### Escenario: Color por signo opcional

- **DADO** un `MoneyAmount` negativo sin `colorBySign`
- **CUANDO** se renderiza
- **ENTONCES** no aplica color por signo; y con `colorBySign` aplica un token semántico y conserva
  el signo menos en el texto

#### Escenario: Signo positivo explícito

- **DADO** los valores `"50.00"`, `"0.00"` y `"-50.00"` con `showPositiveSign`
- **CUANDO** se renderiza `MoneyAmount` con cada uno
- **ENTONCES** el primero muestra el signo más, el cero no muestra signo y el tercero muestra el
  signo menos

### Requisito: `StatusBadge` comunica cada estado financiero con color, icono y texto a la vez

`StatusBadge` DEBE soportar los siete estados `paid`, `pending`, `partial`, `overdue`, `void`,
`promised` y `refunded`, y comunicar cada uno siempre con color (token `--color-status-{estado}`),
icono y texto del catálogo (`docs/ui-ux/01` §4; `docs/ui-ux/02` §5.2). Con `iconOnly`, el texto
DEBE seguir disponible para lectores de pantalla y como título accesible. El color NO DEBE ser
nunca el único portador. Un estado desconocido en tiempo de ejecución NO DEBE producir un estado
inventado.

#### Escenario: Los tres canales de cada estado

- **DADO** cada uno de los siete estados
- **CUANDO** se renderiza `StatusBadge`
- **ENTONCES** muestra un icono propio del estado, el texto de la etiqueta `es-HN` correspondiente
  (por ejemplo «Vencido» para `overdue`) y usa el token de color de ese estado

#### Escenario: Modo `iconOnly`

- **DADO** un `StatusBadge` con `iconOnly` y estado `overdue`
- **CUANDO** se consulta su nombre accesible y su título
- **ENTONCES** ambos son «Vencido», el texto no es visible a la vista y el icono es decorativo para
  los lectores de pantalla

#### Escenario: El icono decorativo no duplica el anuncio

- **DADO** un `StatusBadge` en modo normal
- **CUANDO** se consulta el árbol de accesibilidad
- **ENTONCES** el estado se anuncia una sola vez, con el texto, y el icono está oculto a las
  tecnologías de asistencia

#### Escenario: Estado desconocido en tiempo de ejecución

- **DADO** un valor de estado inexistente introducido evadiendo los tipos
- **CUANDO** se renderiza `StatusBadge`
- **ENTONCES** no muestra ninguno de los siete estados ni un color de estado, y muestra un texto
  neutro del catálogo con icono, sin lanzar una excepción

#### Escenario: Variantes y temas

- **DADO** los siete estados en las variantes `soft`, `solid` y `outline`
- **CUANDO** se renderizan en modo claro y en modo oscuro
- **ENTONCES** cada combinación conserva sus tres canales y no registra violaciones críticas ni
  serias de accesibilidad, incluido el contraste

### Requisito: Todo texto de los componentes proviene del catálogo `es-HN`, con inglés preparado sin traducir

El sistema DEBE servir todo texto de interfaz de `packages/ui` desde catálogos de i18next:
`es-HN` como idioma predeterminado completo, y la infraestructura de inglés preparada sin
traducciones reales (ADR-0011). Ningún componente DEBE contener cadenas visibles o de nombre
accesible embebidas. Si el idioma activo carece de una clave, el sistema DEBE usar el texto de
`es-HN` y NO DEBE mostrar la clave cruda a la persona usuaria.

#### Escenario: Texto desde el catálogo por defecto

- **DADO** el idioma predeterminado y un `StatusBadge` en estado `paid`
- **CUANDO** se renderiza
- **ENTONCES** el texto «Pagado» proviene del catálogo `es-HN`

#### Escenario: Idioma sin traducción

- **DADO** el idioma activo `en` sin traducciones reales
- **CUANDO** se renderiza un `StatusBadge` en estado `paid`
- **ENTONCES** muestra el texto de `es-HN` y no la clave `common.status.paid`

#### Escenario: Catálogo completo para los componentes

- **DADO** el catálogo `es-HN` y todas las claves que usan los componentes de `packages/ui`
- **CUANDO** se ejecuta la verificación de claves
- **ENTONCES** todas existen en el catálogo y la verificación evalúa al menos una clave real

#### Escenario: Los componentes se prueban con el proveedor de idioma

- **DADO** una prueba de componente que renderiza un componente con texto
- **CUANDO** el entorno de pruebas inicializa el idioma predeterminado
- **ENTONCES** el texto visible es el del catálogo `es-HN` y no una clave ni un valor vacío

### Requisito: La presentación de `packages/ui` no obtiene datos

Ningún componente de `packages/ui` DEBE obtener datos, ejecutar mutaciones ni hacer peticiones de
red: recibe datos y callbacks por propiedades (`docs/ui-ux/02` §2). Todo componente DEBE poder
renderizarse y probarse sin proveedor de consultas y sin red.

#### Escenario: Renderizado sin proveedor de consultas ni red

- **DADO** una prueba de componente cuyo entorno falla ante cualquier petición de red no simulada
- **CUANDO** se renderiza cada componente de `packages/ui` solo con propiedades, sin proveedor de
  TanStack Query
- **ENTONCES** todos renderizan sin error y sin ninguna petición

### Requisito: Cada componente tiene historias con sus estados en modo claro y oscuro

El sistema DEBE ofrecer en Storybook al menos una historia por componente exportado, con sus
estados aplicables: por defecto, cada variante, carga, vacío, error y deshabilitado
(`docs/ui-ux/02` §8). Las historias de caso límite de `MoneyAmount` DEBEN incluir cero, negativo e
importe grande. Cada historia DEBE poder verse en modo claro y oscuro.

#### Escenario: Cada componente exportado tiene historia

- **DADO** el conjunto de componentes exportados por el punto de entrada público
- **CUANDO** se compara con el conjunto de componentes que tienen al menos una historia
- **ENTONCES** son el mismo conjunto

#### Escenario: Historias de estado

- **DADO** los componentes con estado de carga, vacío, error o deshabilitado aplicable (`Button`,
  `Input`, `Skeleton`, `EmptyState`, `ErrorState`, `FormField`)
- **CUANDO** se listan sus historias
- **ENTONCES** cada estado aplicable tiene su historia

#### Escenario: Casos límite de `MoneyAmount`

- **DADO** las historias de `MoneyAmount`
- **CUANDO** se listan
- **ENTONCES** incluyen cero, un importe negativo, un importe grande y las monedas `HNL` y `USD`

#### Escenario: Ambos temas disponibles

- **DADO** cualquier historia
- **CUANDO** se alterna entre modo claro y modo oscuro
- **ENTONCES** la historia se renderiza en ambos temas sin cambiar su contenido

### Requisito: Ninguna historia tiene violaciones críticas o serias de accesibilidad

Todas las historias de `packages/ui` DEBEN terminar sin violaciones críticas ni serias de axe, en
modo claro y en modo oscuro (`docs/ui-ux/03` §9 y §11). La verificación DEBE evaluar el contraste
de color con un motor de estilos real; una regla de contraste que axe no pueda decidir
(«incompleta») NO DEBE contarse como aprobada. Las violaciones moderadas DEBEN advertirse sin romper
la verificación, y las menores DEBEN registrarse. La verificación NO DEBE aprobar un conjunto vacío
de historias (ADR-0018).

#### Escenario: Historia deliberadamente inaccesible

- **DADO** una historia de violación deliberada con un botón sin nombre accesible
- **CUANDO** se ejecuta la verificación de historias
- **ENTONCES** falla nombrando la historia y la regla de axe incumplida, con impacto crítico o serio

#### Escenario: Contraste insuficiente detectado

- **DADO** una historia de violación deliberada con texto de bajo contraste
- **CUANDO** se ejecuta la verificación de historias
- **ENTONCES** falla por la regla de contraste, lo que demuestra que esa regla se evalúa de verdad

#### Escenario: Violación solo en modo oscuro

- **DADO** una historia de violación deliberada cuyo contraste es suficiente en modo claro e
  insuficiente en modo oscuro
- **CUANDO** se ejecuta la verificación de historias
- **ENTONCES** falla, porque cada historia se verifica en ambos temas

#### Escenario: Violación moderada

- **DADO** una historia con una violación de impacto moderado
- **CUANDO** se ejecuta la verificación de historias
- **ENTONCES** la violación se reporta como advertencia y la verificación no falla por esa causa

#### Escenario: Historias reales sin violaciones bloqueantes

- **DADO** las historias reales de `packages/ui`, excluidas las de violación deliberada
- **CUANDO** se ejecuta la verificación de historias
- **ENTONCES** termina en verde, habiendo evaluado al menos una historia por componente exportado

#### Escenario: Cero historias evaluadas

- **DADO** una configuración en la que la verificación no encuentra ninguna historia
- **CUANDO** se ejecuta la verificación de historias
- **ENTONCES** falla en lugar de aprobar un conjunto vacío

### Requisito: Cada componente tiene pruebas de componente puramente con propiedades

El sistema DEBE ofrecer para cada componente de `packages/ui` pruebas con Vitest y Testing
Library que se ejecutan solo con propiedades, consultando por rol y nombre accesible en lugar de
por detalles de implementación. Las pruebas de `MoneyAmount` DEBEN cubrir los casos límite de
ADR-0011: cero, negativo, importes grandes, monedas distintas y entrada fuera del patrón del
contrato.

#### Escenario: Cobertura de casos límite de `MoneyAmount`

- **DADO** el conjunto de pruebas de `MoneyAmount`
- **CUANDO** se ejecuta
- **ENTONCES** contiene al menos una prueba por cada caso límite: cero, cero negativo, negativo,
  importe máximo del contrato, importe más allá de la precisión de `number`, `HNL`, `USD` y cada
  forma de entrada inválida de este documento

#### Escenario: Cada componente exportado tiene prueba

- **DADO** el conjunto de componentes exportados por el punto de entrada público
- **CUANDO** se compara con el conjunto de componentes que tienen al menos un archivo de pruebas
- **ENTONCES** son el mismo conjunto

#### Escenario: Una prueba depende del comportamiento y no de la estructura

- **DADO** una prueba de `FormField`
- **CUANDO** se inspecciona cómo localiza los elementos
- **ENTONCES** los localiza por rol, nombre accesible o texto visible, no por clases CSS ni por
  estructura interna del DOM
