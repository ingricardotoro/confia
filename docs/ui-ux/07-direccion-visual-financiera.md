# CONFIA. Dirección visual financiera del panel administrativo

> Estado: **Propuesta v1.0**, fechada 2026-11-16, rama `feat/ui-professional-theme`.
> Continúa `00-principios-de-diseno.md` y amplía `01-tokens-de-diseno.md`. Ante conflicto gana
> `00`. Donde este documento cambia un valor de `01` (primario institucional, radio `xl`, sombras),
> este documento es el vigente y `01` lo remite aquí.
> Alcance: panel administrativo (`apps/admin-web`) y la base compartida de `packages/ui`. El portal
> de encargados hereda la paleta y la tipografía, pero no el armazón (barra lateral, densidad).

---

## 1. Usuario, objetivo, contexto y miedo

| Atributo | Detalle |
|---|---|
| **Usuario** | Personal administrativo: cajero (Wilmer), contadora (Doña Rina), dirección (Lic. Padilla), administrador del sistema. Perfiles de `00` §4 |
| **Objetivo en una frase** | Trabajar ocho horas diarias sobre datos financieros sin dudar de lo que ve |
| **Contexto** | Escritorio, monitores de 1366×768 a 1920×1080, jornadas largas, modo oscuro frecuente, presión de tiempo en ventanilla |
| **Qué le da miedo** | Que el sistema "parezca de juguete" y por eso no confíe en una cifra; equivocarse de registro en una tabla larga; no saber en qué parte del sistema está |

**Problema que resuelve este documento.** El panel actual usa el azul predeterminado de Tailwind
(`#2563EB`), una barra lateral blanca indistinguible del contenido, títulos sin jerarquía de página,
tablas con encabezado del mismo color que el lienzo y insignias tipo píldora intercambiables. El
resultado es correcto y accesible, pero genérico: no transmite autoridad financiera.

---

## 2. Concepto y tono

**Concepto: "mesa de tesorería".** La referencia no es una aplicación de consumo sino la
herramienta interna de un banco: calma, orden, datos alineados y una sola nota de color
institucional. Tres ideas guían cada decisión:

1. **Ancla oscura, trabajo claro.** Una barra lateral azul marino profunda fija la identidad y la
   orientación; el área de trabajo es clara, blanca sobre lienzo gris azulado, para que los datos
   sean lo más brillante de la pantalla.
2. **La estructura habla antes que el color.** Separación por líneas finas (1 px), encabezados de
   tabla con fondo propio, alineación estricta de cifras. El color se reserva para la acción
   primaria y para los estados.
3. **Autoridad sin dramatismo.** Esquinas contenidas (6 px en controles, 8 px en tarjetas), sombras
   cortas y frías, tipografía de peso medio. Nada de degradados, brillos, vidrio ni ilustraciones.

**Lo que se descarta y por qué.** Glassmorphism y degradados (bajan el contraste y envejecen mal);
azul eléctrico o violeta de "fintech de consumo" (comunican juego, no custodia); verde como color
de marca (choca con el estado "Pagado"); barras laterales blancas (pierden la orientación en
jornadas largas). No se usó la skill `ui-ux-pro-max` en esta iteración: la dirección se derivó de
los tokens existentes, de la referencia bancaria local (los bancos hondureños usan azul real
profundo como color de confianza) y de las restricciones de contraste ya vigiladas por las pruebas.

---

## 3. Paleta

Todos los valores son hexadecimales de seis dígitos, porque la guarda de contraste
(`packages/ui/src/tokens/tokens.contrast.test.ts`) solo resuelve ese formato. Las razones de
contraste de §3.6 se calcularon con la fórmula WCAG 2.1 (luminancia relativa sobre sRGB
linealizado, `(L1 + 0,05) / (L2 + 0,05)`), canal por canal, sin redondear hacia arriba.

### 3.1 Primario institucional: azul zafiro

Reemplaza la escala provisional de `01` §2.2. Mismos nombres de primitivo, valores nuevos, y un
paso nuevo `950` para la barra lateral.

| Primitivo | Hex | Uso |
|---|---|---|
| `--palette-primary-50` | `#EEF3FB` | Fondo suave: insignia primaria, selección de fila, ítem activo de pestaña |
| `--palette-primary-100` | `#DCE6F6` | Reserva (fondos suaves con más presencia) |
| `--palette-primary-200` | `#B9CCEC` | Reserva (bordes suaves sobre fondo primario claro) |
| `--palette-primary-300` | `#9DB5E6` | Texto suave en oscuro; foco dentro de la barra lateral |
| `--palette-primary-400` | `#7096DE` | Enlace y foco en oscuro; indicador del ítem activo de la barra lateral; serie 1 de gráficos en oscuro |
| `--palette-primary-500` | `#3D6BC9` | Anillo de foco en claro |
| `--palette-primary-600` | `#1D4FB0` | **Color primario**: botón primario, serie 1 de gráficos |
| `--palette-primary-700` | `#183F8E` | `hover` del primario, enlaces en texto, texto sobre fondo suave |
| `--palette-primary-800` | `#13306C` | `active` del primario |
| `--palette-primary-900` | `#0F2552` | Fondo suave en oscuro |
| `--palette-primary-950` | `#0B1834` | **Nuevo.** Fondo de la barra lateral en tema claro |

### 3.2 Neutros

**Sin cambios** respecto a `01` §2.1 (escala fría `#F8FAFC` a `#020617`). Se conservan a propósito:
los tres textos (`primary`, `secondary`, `tertiary`) ya cumplen con margen, la prueba de contraste
fija sus razones publicadas (17.85, 7.58, 4.76) y el lienzo `#F8FAFC` es el más oscuro posible que
mantiene el texto terciario por encima de 4.5:1 (un lienzo `#F5F7FA` lo bajaría a 4.43:1). La
sensación de "tarjeta sobre lienzo" se consigue con borde fino y sombra corta (§5), no oscureciendo
el lienzo.

### 3.3 Superficies para datos densos (tokens nuevos)

| Token | Claro | Oscuro | Uso | Restricción |
|---|---|---|---|---|
| `--color-bg-subtle` | `#F1F5F9` (`neutral-100`) | `#111A2B` | Encabezado de tabla, franja de cifras clave, pie de totales | En claro, **nunca** texto terciario encima (4.34:1). Solo texto primario o secundario |
| `--color-bg-hover` | `#F8FAFC` (`neutral-50`) | `#131C2E` | `hover` de fila de tabla y de ítem de lista | Admite todo texto, incluido el terciario |

### 3.4 Armazón: barra lateral (tokens nuevos)

La barra lateral es azul marino en **ambos** temas: en claro es el ancla oscura; en oscuro se funde
con la superficie y se separa por un borde.

| Token | Claro | Oscuro | Uso |
|---|---|---|---|
| `--color-sidebar-bg` | `#0B1834` (`primary-950`) | `#0B1220` | Fondo de la barra |
| `--color-sidebar-fg` | `#C9D4E8` | igual | Texto de los ítems |
| `--color-sidebar-fg-muted` | `#8FA0BF` | igual | Etiquetas de sección en versalitas |
| `--color-sidebar-hover-bg` | `#13244A` | igual | `hover` de ítem |
| `--color-sidebar-active-bg` | `#1B2E57` | igual | Fondo del ítem actual |
| `--color-sidebar-active-fg` | `#FFFFFF` | igual | Texto del ítem actual y nombre del producto |
| `--color-sidebar-indicator` | `#7096DE` (`primary-400`) | igual | Barra de 3 px a la izquierda del ítem actual |
| `--color-sidebar-focus` | `#9DB5E6` (`primary-300`) | igual | Anillo de foco dentro de la barra |
| `--color-sidebar-border` | `#1B2A47` | `#1E293B` | Borde derecho y divisores (decorativo) |

### 3.5 Semánticos de retroalimentación y de ciclo de vida

Los colores de retroalimentación de `01` §2.3 y los siete estados financieros de `01` §4 **no
cambian**: su significado es compartido con el portal y ya está verificado. Se agrega un par suave
para el ciclo de vida de registros no financieros ("Activo"):

| Token | Claro | Oscuro |
|---|---|---|
| `--color-feedback-success-bg` | `#F0FDF4` | `#0F2A1B` |
| `--color-feedback-success-fg` | `#166534` | `#4ADE80` |

Tienen hoy los mismos valores que el par `paid`, pero son tokens distintos: recolorear "Activo"
nunca recolorea "Pagado" (`01` §2.3).

**Mapa de estados de registro** (no financieros; los financieros siguen en `StatusBadge`):

| Estado | Variante de `Badge` | Marca | Texto (catálogo) |
|---|---|---|---|
| Activo | `success` (nueva) | punto lleno | `resource.active` "Activo" |
| Inactivo | `neutral` | punto hueco | `resource.inactive` "Inactivo" |
| Archivada (lista de precios) | `neutral` | punto hueco | `catalog.priceLists.statuses.ARCHIVED` (valor existente) |
| Vigencia abierta | `primary` | punto lleno | `catalog.priceLists.detail.validityOpen` (valor existente) |
| Vigencia cerrada | `outline` | sin punto | `catalog.priceLists.detail.validityClosed` (valor existente) |

El punto es decorativo (`aria-hidden`); el texto es siempre el portador del significado, y la forma
(lleno, hueco, ninguno) es un segundo portador no cromático.

### 3.6 Contraste verificado

Fondos de referencia: superficie clara `#FFFFFF` (L = 1.0000), lienzo claro `#F8FAFC` (L = 0.9536),
superficie oscura `#0B1220` (L = 0.0061), lienzo oscuro `#05080F` (L = 0.0024), elevada oscura
`#131C2E` (L = 0.0117). Mínimos: 4.5:1 texto, 3:1 componentes e iconos portadores de información.

**Tema claro**

| Par | Razón | Mínimo | Resultado |
|---|---|---|---|
| Blanco sobre `--color-primary` `#1D4FB0` | 7.51 | 4.5 | Cumple |
| Blanco sobre `--color-primary-hover` `#183F8E` | 9.81 | 4.5 | Cumple |
| Blanco sobre `--color-primary-active` `#13306C` | 12.60 | 4.5 | Cumple |
| `--color-text-link` `#183F8E` sobre superficie / lienzo | 9.81 / 9.38 | 4.5 | Cumple |
| `--color-border-focus` `#3D6BC9` sobre superficie / lienzo | 5.07 / 4.84 | 3 | Cumple |
| `--color-primary-soft-fg` `#183F8E` sobre `-soft-bg` `#EEF3FB` | 8.81 | 4.5 | Cumple |
| Texto primario / secundario / terciario sobre superficie | 17.85 / 7.58 / 4.76 | 4.5 | Cumple (sin cambio) |
| Texto primario / secundario / terciario sobre lienzo y `bg-hover` | 17.06 / 7.24 / 4.55 | 4.5 | Cumple |
| Texto primario / secundario sobre `bg-subtle` `#F1F5F9` | 16.29 / 6.92 | 4.5 | Cumple |
| Texto terciario sobre `bg-subtle` | 4.34 | 4.5 | **No cumple como texto: prohibido**. Válido como icono (≥ 3) |
| `--color-feedback-success-fg` sobre `-bg` | 6.81 | 4.5 | Cumple (mismo par que `paid`, que la guarda ya mide) |

**Barra lateral** (fondo claro `#0B1834`, L = 0.0097; fondo oscuro `#0B1220`)

| Par | Claro | Oscuro | Mínimo | Resultado |
|---|---|---|---|---|
| `sidebar-fg` `#C9D4E8` sobre fondo | 11.78 | 12.54 | 4.5 | Cumple |
| `sidebar-fg-muted` `#8FA0BF` sobre fondo | 6.65 | 7.09 | 4.5 | Cumple |
| `sidebar-active-fg` `#FFFFFF` sobre fondo (nombre del producto) | 17.58 | 18.72 | 4.5 | Cumple |
| `sidebar-active-fg` sobre `sidebar-active-bg` `#1B2E57` | 13.33 | 13.33 | 4.5 | Cumple |
| `sidebar-fg` sobre `sidebar-hover-bg` `#13244A` | 10.20 | 10.20 | 4.5 | Cumple |
| `sidebar-indicator` `#7096DE` sobre fondo / sobre ítem activo | 5.95 / 4.51 | 6.34 / 4.51 | 3 | Cumple |
| `sidebar-focus` `#9DB5E6` sobre fondo / sobre ítem activo | 8.53 / 6.47 | 9.08 / 6.47 | 3 | Cumple |
| Foco global `#3D6BC9` sobre fondo de la barra (si no se usa el propio) | 3.47 | n/a | 3 | Cumple, con poco margen: por eso existe `sidebar-focus` |

**Tema oscuro**

| Par | Razón | Mínimo | Resultado |
|---|---|---|---|
| `--color-text-link` `#7096DE` sobre superficie / lienzo / elevada | 6.34 / 6.78 / 5.76 | 4.5 | Cumple |
| `--color-border-focus` `#7096DE` sobre superficie | 6.34 | 3 | Cumple |
| `--color-primary-soft-fg` `#9DB5E6` sobre `-soft-bg` `#0F2552` | 7.24 | 4.5 | Cumple |
| Blanco sobre `--color-primary` (no cambia entre temas) | 7.51 | 4.5 | Cumple |
| Texto primario / secundario / terciario sobre `bg-subtle` `#111A2B` | 15.89 / 11.72 / 6.79 | 4.5 | Cumple |
| Texto primario / secundario / terciario / enlace sobre `bg-hover` `#131C2E` | 15.54 / 11.47 / 6.64 / 5.76 | 4.5 | Cumple |
| `--color-feedback-success-fg` `#4ADE80` sobre `-bg` `#0F2A1B` | 8.81 | 4.5 | Cumple |

**Observación declarada.** El relleno del botón primario `#1D4FB0` sobre la superficie oscura da
2.49:1. No es un incumplimiento: el control se identifica por su texto (7.51:1) y su foco (6.34:1),
igual que con el valor anterior. Si la puerta de axe lo señala, se agrega un borde
`--color-primary-hover`, no se cambia el primario.

### 3.7 Colores de gráfico

Serie categórica de seis colores, en orden de uso. Cada uno cumple 3:1 sobre la superficie del
tema. Un gráfico **siempre** lleva etiquetas directas o leyenda con texto; dos series contiguas
nunca dependen solo del matiz. Los gráficos de **estado financiero** no usan esta serie: usan los
tokens `--color-status-*` para que "vencido" sea el mismo rojo en una tabla y en un gráfico.

| Token | Claro | Razón sobre `#FFFFFF` | Oscuro | Razón sobre `#0B1220` |
|---|---|---|---|---|
| `--color-chart-1` | `#1D4FB0` | 7.51 | `#7096DE` | 6.34 |
| `--color-chart-2` | `#0F766E` | 5.47 | `#2DD4BF` | 10.06 |
| `--color-chart-3` | `#B45309` | 5.02 | `#FBBF24` | 11.22 |
| `--color-chart-4` | `#6D28D9` | 7.10 | `#A78BFA` | 6.88 |
| `--color-chart-5` | `#0369A1` | 5.93 | `#38BDF8` | 8.74 |
| `--color-chart-6` | `#475569` | 7.58 | `#94A3B8` | 7.30 |

---

## 4. Tipografía

### 4.1 Familia

**Se mantiene Inter Variable, servida desde el paquete.** La política de seguridad de contenido del
panel (`apps/admin-web/vite.config.ts`) declara `font-src 'self'` y la prueba
`tokens.fonts.test.ts` exige que ninguna fuente venga de un tercero. Google Fonts queda descartado
por ambas razones. Agregar una segunda familia autoalojada (por ejemplo una serif para titulares)
exigiría un paquete nuevo de `@fontsource` y otra entrada de `@font-face`; no se justifica: el
carácter profesional lo dan el peso, el tracking y las características OpenType de Inter, no una
segunda familia. Pila de respaldo sin cambio: `ui-sans-serif, system-ui, 'Segoe UI', Roboto…`.

### 4.2 Escala aplicada al panel

Se usan los tokens existentes de `01` §7.2; esta tabla fija cuál corresponde a cada pieza.

| Pieza | Token | Peso | Tracking | Color |
|---|---|---|---|---|
| Título de página (`h1`) | `text-2xl` (24/32) | `semibold` | `tracking-tight` | `text-text-primary` |
| Descripción de página | `text-sm` (14/20) | `regular` | normal | `text-text-secondary` |
| Título de sección (`h2`) | `text-lg` (18/28) | `semibold` | normal | `text-text-primary` |
| Subsección (`h3`, `h4`) | `text-base` (16/24) | `semibold` | normal | `text-text-primary` |
| Etiqueta en versalitas (sección de barra lateral, rótulo de cifra clave) | `text-xs` (12/16), `uppercase` | `semibold` | `tracking-caps` (nuevo, 0.06em) | `sidebar-fg-muted` o `text-text-secondary` |
| Encabezado de tabla | `text-xs`, `uppercase` | `semibold` | `tracking-caps` | `--table-header-fg` |
| Celda de tabla | `text-sm` | `regular` | normal | `text-text-primary` |
| Cifra clave | `text-2xl` | `semibold` | `tracking-tight` | `text-text-primary` |
| Migas de pan, metadatos | `text-xs` o `text-sm` | `medium` | normal | `text-text-secondary` |

Regla: nunca más de tres pesos en una vista (400, 500, 600). El 700 queda para el portal.

### 4.3 Cifras e identificadores

- **Importes**: `MoneyAmount`, que ya aplica `.money-amount` con `tabular-nums` obligatorio,
  alineado a la derecha, dos decimales y moneda visible. En tablas, la columna de importe es
  `align: "end"` y su encabezado también se alinea a la derecha.
- **Identificadores** (código de concepto, correlativo, CAI, identificador de traza): utilidad
  nueva `font-identifier` en `base.css`, que activa cifras tabulares, cero con barra (`zero`), `l`
  con cola (`cv05`) e `I` con serifa (`cv08`). Así `0`/`O` y `1`/`l`/`I` nunca se confunden.
- Fechas en tabla: `tabular-nums` para que las columnas de fecha se alineen.

### 4.4 Renderizado

`base.css` aplica `-webkit-font-smoothing: antialiased` al documento: trazos más finos y nítidos
en macOS, sin efecto en Windows.

---

## 5. Espaciado, radios, bordes y elevación

### 5.1 Espaciado

Base de 4 px sin cambios. Medidas del armazón:

| Pieza | Medida | Utilidad |
|---|---|---|
| Ancho de la barra lateral | 240 px | `w-60` |
| Alto de la barra superior | 56 px | `h-14` |
| Relleno del área de trabajo | 24 px; 32 px desde `xl` | `px-6 py-6 xl:px-8` |
| Separación entre bloques de página | 24 px | `gap-6` |
| Separación dentro de un bloque | 12 a 16 px | `gap-3`, `gap-4` |
| Fila de tabla cómoda / compacta | 40 / 32 px | `py-2.5` / `py-1.5` con `text-sm` |

La fila cómoda pasa de 36 a 40 px solo cuando la fila tiene botones de acción de 32 px; sin
acciones se queda en 36 px (`py-2`). `00` §5 admite ambas.

### 5.2 Radios

| Token | Antes | Ahora | Uso |
|---|---|---|---|
| `--radius-sm` | 4 px | 4 px | Insignias de estado, chips |
| `--radius-md` | 6 px | 6 px | Botones, entradas, ítems de navegación |
| `--radius-lg` | 8 px | 8 px | Tarjetas, contenedor de tabla, alertas |
| `--radius-xl` | 12 px | **10 px** | Diálogos |
| `--radius-2xl` | 16 px | 16 px | Solo portal |

Las insignias pasan de píldora (`rounded-full`) a `rounded-sm`: la píldora es el gesto más
reconocible de una interfaz genérica; la esquina contenida es el de un sistema contable.

### 5.3 Bordes y elevación

- Borde por defecto: 1 px `--color-border-subtle`. Toda tarjeta, tabla y barra lleva borde.
- Sombras nuevas, cortas y frías (valores en `semantic.css`): `shadow-sm` para tarjetas y
  contenedor de tabla, `shadow-md` para menús, `shadow-lg` para popovers, `shadow-xl` para
  diálogos. En oscuro siguen las sombras de `01` §9.3 y la elevación se lee por superficie.
- Prohibido: sombras de color, sombras sin borde para delimitar un control, más de un nivel de
  sombra dentro de una tarjeta.

---

## 6. Lenguaje de composición

### 6.1 Armazón de la aplicación

```
+-----------------------------------------------------------------------------------------+
| [Saltar al contenido]  (solo visible con foco)                                          |
+---------------------+-------------------------------------------------------------------+
| SIDEBAR  w-60       | TOPBAR  h-14  bg-bg-surface  border-b                             |
| bg-sidebar-bg       |  [≡ Menú] (<lg)                     [Institución*]  [Su cuenta v] |
|                     +-------------------------------------------------------------------+
|  CONFIA             | MAIN  bg-bg-canvas  px-6 py-6                                     |
|  Control financiero |                                                                   |
|  ---------------    |   Migas: Catálogo / Listas de precios / Lista 2027                |
|  > Inicio           |   PAGE HEADER (6.2)                                               |
|                     |                                                                   |
|  COMUNIDAD EDUCATIVA|   CONTENIDO (tabla, detalle, formulario)                          |
|    Estudiantes      |                                                                   |
|    Encargados       |                                                                   |
|    Matrículas       |                                                                   |
|                     |                                                                   |
|  FINANZAS           |                                                                   |
| |# Catálogo  (activo: bg sidebar-active-bg, barra indicadora 3 px, peso semibold)       |
|                     |                                                                   |
|  CONFIGURACIÓN      |                                                                   |
|    Estructura acad. |                                                                   |
|    Importaciones    |                                                                   |
+---------------------+-------------------------------------------------------------------+
```

- **Barra lateral** (`SideNavigation`, `apps/admin-web`): `nav` con `aria-label`
  `shell.navigationLabel`. Encabezado con el nombre del producto (`shell.brand`) y el subtítulo
  (`shell.tagline`). Grupos con etiqueta en versalitas: cada grupo es un `ul` precedido de un
  encabezado visual cuyo `id` nombra la lista (`aria-labelledby`). El ítem actual lleva
  `aria-current="page"`, fondo `sidebar-active-bg`, texto blanco, peso `semibold` **y** la barra
  indicadora: nunca solo color.
- **Barra superior**: superficie blanca con borde inferior. A la derecha, el grupo de cuenta
  existente (`shell.accountLabel`) con el botón de cerrar sesión como `Button variant="subtle"
  size="sm"`. **\* Nombre de la institución: pendiente.** La sesión actual no expone el nombre de
  la institución ni el de la persona, y `AppShell` decide a propósito no mostrar datos de la
  persona. Mostrar la institución requiere un campo en el contrato de sesión: se deriva a
  `confia-architect` / `confia-backend-dev`. Hasta entonces, la barra superior no muestra nombre.
- **Menú de cuenta desplegable**: requiere un `DropdownMenu` que `packages/ui` aún no tiene. Mientras
  no exista, se conserva el botón de cerrar sesión visible.

### 6.2 Encabezado de página

```
+-------------------------------------------------------------------------------------+
| Catálogo / Listas de precios                          (Breadcrumb, text-xs, medium) |
| Listas de precios                                     [ Secundaria ] [+ Primaria ]  |
| Precios por año lectivo y alcance, con su vigencia.   (description, text-sm, sec.)  |
+-------------------------------------------------------------------------------------+
| FRANJA DE CIFRAS CLAVE (opcional)  bg-bg-subtle  border  rounded-lg                 |
|  LISTAS EN TOTAL      |  ...                                                         |
|  24                   |                                                              |
+-------------------------------------------------------------------------------------+
```

- Una sola acción primaria a la derecha del título (principio 3); las secundarias a su izquierda.
- La franja de cifras clave solo muestra cifras **que entrega el servidor**. Hoy la única disponible
  en los listados es el total de la consulta paginada (`pagination.total`). Recuentos por estado o
  importes agregados requieren un endpoint: no se calculan en el navegador y se marcan pendientes.
- En pantallas de detalle, el título es el nombre del registro y la insignia de estado va junto a él.

### 6.3 Tablas de datos

```
+-------------------------------------------------------------------------------------+
| [Categoría v]  [Estado v]                                    (filtros, gap-3)       |
+-------------------------------------------------------------------------------------+
| CÓDIGO   | NOMBRE              | CATEGORÍA   | ESTADO     |           ACCIONES      | <- sticky, bg-subtle,
|----------+---------------------+-------------+------------+-------------------------|    text-xs uppercase
| MAT-01   | Matrícula anual     | Matrícula   | ● Activo   |  [Editar] [Desactivar]  | <- línea 1 px,
| MEN-01   | Mensualidad         | Colegiatura | ○ Inactivo |  [Editar] [Activar]     |    hover bg-hover
+-------------------------------------------------------------------------------------+
|                                     Mostrando 1–50 de 120   [< Anterior] [Siguiente >] |
+-------------------------------------------------------------------------------------+
```

- Contenedor: `rounded-lg border border-(--table-border) bg-bg-surface shadow-(--card-shadow)`.
- Encabezado: `bg-(--table-header-bg) text-(--table-header-fg) text-xs font-semibold uppercase
  tracking-caps`, `sticky top-0` dentro de un contenedor con alto máximo cuando la tabla es larga.
- Filas: separadas por línea fina, `hover:bg-(--table-row-hover-bg)`. Sin cebra en densidad cómoda;
  la cebra se reserva para densidad compacta, con el mismo token `bg-hover`.
- Columnas de dinero: `align: "end"`, `MoneyAmount`, encabezado alineado a la derecha. Códigos con
  `font-identifier`. Fechas con `tabular-nums`.
- Acciones de fila: `Button variant="subtle" size="sm"`, con `aria-label` que nombra el registro
  (como hoy). La celda de acciones alinea a la derecha.
- El `caption` se conserva para tecnología asistiva. Cuando la pantalla ya tiene un título visible
  inmediatamente encima (detalle de lista de precios), el `caption` se oculta visualmente para no
  repetir el texto.
- Paginación, orden y filtro siguen en el servidor (`00` §6).

### 6.4 Detalle con tabla de historial

```
+-------------------------------------------------------------------------------------+
| Catálogo / Listas de precios / Lista general 2027                                   |
| Lista general 2027  [● Activa]                      [Archivar]  [+ Agregar precio]  |
+-------------------------------------------------------------------------------------+
| TARJETA RESUMEN  (dl en 4 columnas desde lg)                                        |
|  AÑO LECTIVO        ALCANCE          REFERENCIA        ESTADO                       |
|  2027               Modalidad        Bachillerato      ● Activa                     |
+-------------------------------------------------------------------------------------+
| Precios por concepto                                              (h3, text-lg)     |
|                                                                                     |
| MAT-01 · Matrícula anual                                          (h4)              |
| +-------------------------------------------------------------------------------+   |
| |          MONTO | MONEDA | VIGENTE DESDE | VIGENTE HASTA | VIGENCIA          |   |
| |   L 3,200.00   | HNL    | 2027-01-01    | Sin fin       | ● Abierta         |   |
| |   L 3,000.00   | HNL    | 2026-01-01    | 2026-12-31    | Cerrada           |   |
| +-------------------------------------------------------------------------------+   |
+-------------------------------------------------------------------------------------+
```

- El enlace "Volver" se reemplaza por la miga de pan (que incluye el destino de vuelta).
- Resumen: tarjeta con `dl`; `dt` en versalitas `text-xs text-text-secondary`, `dd` en
  `text-sm font-medium text-text-primary`.
- La vigencia abierta es la primera fila y su insignia es la única con punto lleno.

### 6.5 Formularios y diálogos

```
+----------------------------------------------------------+
| Nueva lista de precios                              [X]  |  (título text-lg semibold)
| Registre el año, el alcance y el nombre.                 |  (descripción text-sm sec.)
|----------------------------------------------------------|  (borde 1 px)
| Año lectivo *                                            |
| [ 2027                                    v ]            |
| Alcance *                                                |
| [ Toda la institución                      v ]           |
| Nombre *                                                 |
| [                                          ]             |
|----------------------------------------------------------|
| bg-bg-subtle                    [Cancelar] [Guardar]     |  (pie con fondo propio)
+----------------------------------------------------------+
```

- Diálogo: `rounded-xl` (10 px), `shadow-xl`, ancho 480 px (`max-w-120`) para formularios cortos.
- Encabezado y pie separados por línea fina; el pie con `bg-bg-subtle` y botones a la derecha:
  secundaria (Cancelar) primero, primaria al final.
- Etiquetas encima del control, `text-sm font-medium`; ayuda debajo en `text-xs
  text-text-secondary`. El botón de envío nunca se deshabilita (`03` §5).
- Operaciones irreversibles o de dinero: `ConfirmDialog` con importe y destinatario visibles (`02`
  §5.5); esta dirección visual no cambia su contenido.

### 6.6 Estados de carga, vacío y error

| Estado | Composición | Texto |
|---|---|---|
| Carga | Esqueletos con la forma real (filas de tabla, bloques del resumen). Nunca giratorio de página completa | Anuncio `common.loading` (existente) |
| Vacío | `EmptyState` dentro del contenedor de la tabla: icono `--icon-size-xl` en `text-text-tertiary` sobre superficie, título `text-base semibold`, descripción `text-sm secondary`, acción primaria | Textos existentes de cada pantalla |
| Error | `ErrorState` en el mismo contenedor, icono `AlertCircle`, botón Reintentar, identificador de traza con `font-identifier` | Textos existentes de cada pantalla |
| Éxito | `Alert` informativo en la región `aria-live` existente sobre la tabla | Textos existentes |

### 6.7 Insignias

- Base: `rounded-sm`, `h-5 px-1.5 text-xs font-medium` en tablas; `h-6 px-2 text-sm` en
  encabezados de detalle.
- Punto opcional de 6 px antes del texto (`aria-hidden`), lleno o hueco según §3.5.
- `StatusBadge` (financiero) conserva icono Lucide, color y texto; solo hereda el radio nuevo.

### 6.8 Inicio de sesión

```
lg y mayor                                              menor que lg
+---------------------------+-------------------------+  +---------------------------+
| PANEL DE MARCA            | bg-bg-canvas            |  | CONFIA                    |
| bg-sidebar-bg             |                         |  | Control financiero        |
|                           |  +-------------------+  |  |                           |
| CONFIA                    |  | Inicio de sesión  |  |  | +-----------------------+ |
| Control financiero        |  | (h1)              |  |  | | Inicio de sesión (h1) | |
| académico                 |  | Correo            |  |  | | ...                   | |
|                           |  | [            ]    |  |  | +-----------------------+ |
|                           |  | Contraseña        |  |  +---------------------------+
|                           |  | [            ]    |  |
|                           |  | [   Ingresar   ]  |  |
|                           |  +-------------------+  |
+---------------------------+-------------------------+
```

- Panel de marca: solo nombre del producto y subtítulo. Sin afirmaciones de seguridad ni
  ilustraciones. Texto en `sidebar-active-fg` y `sidebar-fg`.
- Tarjeta del formulario: `bg-bg-surface border rounded-lg shadow-sm p-8`, ancho máximo 400 px.
- Mismo armazón para segundo factor, enrolamiento y restauración de sesión (`ScreenLayout`).

---

## 7. Iconografía

Lucide sin cambios (`01` §12). Reglas de esta dirección:

- La barra lateral **no** lleva iconos por ítem en esta iteración: con siete destinos y etiquetas
  de grupo, el texto basta, y evita mezclar trazos. Si se agregan, `--icon-size-sm` con trazo 2,
  `aria-hidden`, del mapa de `01` §12.3 (`Users`, `GraduationCap`, `CircleDollarSign`, `Landmark`).
- Iconos de estado vacío: `--icon-size-xl`, trazo 1.5.
- El punto de las insignias no es un icono: es un elemento decorativo de 6 px.

---

## 8. Comportamiento responsivo, foco y accesibilidad

### 8.1 Responsivo

| Ancho | Comportamiento |
|---|---|
| ≥ 1280 px (`xl`) | Barra lateral fija, relleno 32 px, tablas con todas sus columnas |
| 1024 a 1279 px (`lg`) | Barra lateral fija, relleno 24 px |
| < 1024 px | La barra lateral se oculta; aparece el botón `shell.openNavigation` en la barra superior, que la abre como panel superpuesto con foco atrapado y cierre con Esc |
| 320 px | Tablas con desplazamiento horizontal dentro de su contenedor; encabezado de página apila título y acciones |

### 8.2 Orden de foco

1. Enlace "Saltar al contenido".
2. Barra superior: botón de menú (solo < lg), grupo de cuenta (cerrar sesión).
3. Barra lateral: ítems en orden de grupo.
4. `main`: migas de pan, `h1` (recibe el foco programático al cambiar de página, como hoy),
   acciones del encabezado, filtros, tabla, paginación.

### 8.3 Criterios de aceptación de accesibilidad (verificables)

- [ ] Todos los pares de §3.6 se reproducen en la guarda de contraste extendida (§9.3).
- [ ] El ítem actual de la barra lateral se distingue sin color: `aria-current`, peso y barra.
- [ ] El foco es visible en la barra lateral con `sidebar-focus` (≥ 3:1 sobre fondo e ítem activo).
- [ ] Ningún texto terciario sobre `bg-subtle` en tema claro.
- [ ] Cada insignia de estado tiene texto; el punto es `aria-hidden`.
- [ ] Los grupos de la barra lateral son listas nombradas (`aria-labelledby`).
- [ ] Las migas de pan son un `nav` con `aria-label` `shell.breadcrumbLabel`, y el último elemento
      lleva `aria-current="page"` sin enlace.
- [ ] axe sin violaciones críticas ni serias en claro y oscuro, en Storybook y en las rutas.
- [ ] El panel funciona a 320 px y al 200 % de zoom (§8.1).

### 8.4 Microcopy nuevo (catálogo `apps/admin-web/src/i18n/locales/es-HN/admin.json`)

| Clave | Valor es-HN |
|---|---|
| `shell.tagline` | Control financiero académico |
| `shell.breadcrumbLabel` | Ruta de navegación |
| `shell.openNavigation` | Abrir menú |
| `shell.closeNavigation` | Cerrar menú |
| `navigation.groups.community` | Comunidad educativa |
| `navigation.groups.finance` | Finanzas |
| `navigation.groups.settings` | Configuración |
| `catalog.priceLists.description` | Precios por año lectivo y alcance, con su vigencia. |
| `catalog.paymentConcepts.description` | Conceptos que la institución cobra, con su categoría y su condición fiscal. |
| `catalog.taxRates.description` | Tasas de impuesto y sus períodos de vigencia. |
| `resource.totalRecords` | `{count, plural, one {# registro} other {# registros}}` |

Las claves equivalentes en inglés se agregan con el mismo nombre. `catalog.taxRates.description`
no afirma ninguna tasa ni porcentaje fiscal: si se quiere citar la tasa legal vigente, ese texto
queda pendiente de validación fiscal.

---

## 9. Plan de implementación para `confia-frontend-dev`

Los tokens de §3 a §5 **ya están aplicados** en `packages/ui/src/tokens/` (primitivo, semánticos,
temas y componentes) y `base.css`. Con solo eso cambian en toda la aplicación el color primario
(botones, enlaces, foco, insignias primarias, pestañas activas), el radio de los diálogos y las
sombras. El resto requiere cambios de clases y de componentes, en este orden de impacto:

### 9.1 Cambios priorizados

| # | Cambio | Archivos | Detalle |
|---|---|---|---|
| 1 | **Barra lateral marino agrupada** | `apps/admin-web/src/app/shell/components/SideNavigation.tsx`, `AppShell.tsx`, `shared/navigation/navigation-entry.ts`, `app/router.tsx`, cada `features/*/feature.tsx`, `admin.json` | `NavigationEntry` gana `group?: "community" \| "finance" \| "settings"` (sin grupo = arriba). `SideNavigation` recibe `groups: { id: string; label?: string; items: SideNavigationItem[] }[]`. Clases: `nav` → `w-60 shrink-0 bg-sidebar-bg border-r border-sidebar-border flex flex-col`; marca → `px-4 h-14 flex flex-col justify-center` con `text-sidebar-active-fg font-semibold` y subtítulo `text-xs text-sidebar-fg`; etiqueta de grupo → `px-3 pt-5 pb-1.5 text-xs font-semibold uppercase tracking-caps text-sidebar-fg-muted`; ítem → `relative flex h-9 items-center rounded-md px-3 text-sm text-sidebar-fg hover:bg-sidebar-hover-bg focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-sidebar-focus`; actual → `bg-sidebar-active-bg text-sidebar-active-fg font-semibold before:absolute before:inset-y-1.5 before:left-0 before:w-0.75 before:rounded-full before:bg-sidebar-indicator`. Retirar `border-l-4` y `bg-bg-canvas`. La barra lateral sube a la altura total (fuera del `header`) |
| 2 | **Barra superior compacta** | `AppShell.tsx` | `header` → `h-14 flex items-center justify-end gap-3 border-b border-border-subtle bg-bg-surface px-6`; el nombre del producto pasa a la barra lateral. Cerrar sesión → `variant="subtle" size="sm"`. Estructura: `div.flex.min-h-screen` → barra lateral + columna (`header` + `main`) |
| 3 | **`PageHeader` y `Breadcrumb`** | Nuevos en `packages/ui/src/molecules/page-header/` y `packages/ui/src/molecules/breadcrumb/`; adoptar en `shared/layout/components/PageContent.tsx` y `shared/resource-screen/components/ResourceScreen.tsx` | Ver §9.2. `PageContent` delega en `PageHeader` el `h1` con foco programático. `ResourceScreen` pasa `heading`, `description` y la acción primaria al encabezado |
| 4 | **Estilo de `Table`** | `packages/ui/src/molecules/table/Table.tsx` | Contenedor `rounded-lg border-(--table-border) shadow-(--card-shadow)`; `thead` → `bg-(--table-header-bg)`; `th` → `px-3 py-2 text-xs font-semibold uppercase tracking-caps text-(--table-header-fg)`; `tr` del cuerpo → `hover:bg-(--table-row-hover-bg)`; props nuevas `density?: "comfortable" \| "compact"` (por defecto `comfortable`), `stickyHeader?: boolean`, `captionHidden?: boolean` (`sr-only` en el `caption`). El icono de orden inactivo puede seguir en `text-text-tertiary` (4.34:1 ≥ 3 como icono) |
| 5 | **Insignias** | `packages/ui/src/atoms/badge/Badge.tsx`, `apps/admin-web/src/shared/resource-screen/components/fields.tsx`, `PriceListDetailView.tsx` | `rounded-full` → `rounded-sm`; `sm` → `h-5 px-1.5`; variante nueva `success` con `--badge-success-*`; prop nueva `dot?: "filled" \| "hollow"`. `ActiveBadge` → `success` + `filled` o `neutral` + `hollow`. Vigencia abierta → `primary` + `filled`; cerrada → `outline` |
| 6 | **Tarjetas y detalle** | Nuevo `packages/ui/src/molecules/card/`; `PriceListDetailView.tsx`, `TaxRateDetailView.tsx` | Resumen `dl` dentro de `Card`; `dt` en versalitas; enlace "Volver" sustituido por `Breadcrumb`; `captionHidden` en las tablas por concepto; códigos con `font-identifier` |
| 7 | **Inicio de sesión** | `apps/admin-web/src/features/identity/components/ScreenLayout.tsx` | Rejilla `lg:grid-cols-[minmax(0,5fr)_minmax(0,7fr)] min-h-screen`; panel de marca `hidden lg:flex bg-sidebar-bg` con `shell.brand` y `shell.tagline` como `p` (no un segundo `h1`); formulario dentro de `rounded-lg border border-border-subtle bg-bg-surface p-8 shadow-sm max-w-100` (400 px; el tema de Tailwind está borrado, así que los anchos salen de `--spacing`). El `main` y el foco del `h1` no cambian |
| 8 | **Diálogo** | `packages/ui/src/molecules/dialog/Dialog.tsx` | Encabezado y pie con `border-border-subtle`; pie `bg-bg-subtle px-6 py-4 flex justify-end gap-2`; el radio ya llega por `rounded-xl` |
| 9 | **Franja de cifras clave** | Nuevo `packages/ui/src/molecules/key-figure/` | Solo con cifras del servidor (hoy, `pagination.total`). Ver §9.2 |
| 10 | **Barra lateral plegable < lg** | `AppShell.tsx`, `SideNavigation.tsx` | Botón `shell.openNavigation` con `aria-expanded` y `aria-controls`; panel superpuesto con foco atrapado (reutilizar el primitivo de Radix Dialog que ya usa `Dialog`) |

### 9.2 Componentes nuevos

| Componente | Nivel | Ubicación | Props |
|---|---|---|---|
| `Breadcrumb` | molécula | `packages/ui/src/molecules/breadcrumb/` | `label: string` (`aria-label` del `nav`); `items: readonly { id: string; label: string; href?: string; onNavigate?: (href: string) => void }[]`; el último sin `href` y con `aria-current="page"`; separador `ChevronRight` `aria-hidden` |
| `PageHeader` | molécula | `packages/ui/src/molecules/page-header/` | `title: string`; `description?: string`; `breadcrumb?: ReactNode`; `status?: ReactNode` (insignia junto al título); `actions?: ReactNode`; `headingRef?: Ref<HTMLHeadingElement>`; `headingLevel?: 1 \| 2` (por defecto 1); `children?: ReactNode` (franja de cifras) |
| `Card` | molécula | `packages/ui/src/molecules/card/` | `title?: string`; `description?: string`; `actions?: ReactNode`; `padding?: "md" \| "lg"`; `children: ReactNode`; lee `--card-*` |
| `KeyFigure` | molécula | `packages/ui/src/molecules/key-figure/` | `label: string`; `value: ReactNode` (`MoneyAmount` o número ya formateado por `Intl`); `description?: string`; `loading?: boolean` (esqueleto). No recibe números para operar con ellos |
| `Badge` (cambio) | átomo | existente | `variant` añade `"success"`; `dot?: "filled" \| "hollow"` |
| `Table` (cambio) | molécula | existente | `density?`, `stickyHeader?`, `captionHidden?` |
| `SideNavigation` (cambio) | organismo de la app | `apps/admin-web/src/app/shell/components/` | `groups` en lugar de `items` |

Cada componente nuevo de `packages/ui` necesita historia de Storybook con sus estados, prueba de
componente y entrada en `no-network.test.tsx` (el caso DS61 exige que el mapa de fixtures cubra
cada exportación).

### 9.3 Pruebas y puertas que cambian con los tokens ya aplicados

| Prueba | Efecto esperado | Acción |
|---|---|---|
| `packages/ui/src/tokens/tokens.chain.test.ts` línea 23 | **Falla**: espera `#2563eb` | Cambiar a `#1d4fb0` |
| `packages/ui/src/tokens/tokens.build.test.ts` línea 33 | **Falla**: espera `--palette-primary-600: #2563eb` | Cambiar a `#1d4fb0` |
| `packages/ui/src/tokens/tokens.contrast.test.ts` | Debe pasar: textos neutros sin cambio (17.85 / 7.58 / 4.76) y pares de marca recalculados en §3.6 | Extender `sharedPairs` con los pares nuevos de §3.6: `bg-subtle` (primario y secundario), `bg-hover` (los cuatro textos), barra lateral, `feedback-success`, y los seis `chart-*` (3:1) |
| `packages/ui/src/tokens/tokens.parity.test.ts` | Debe pasar: los dos bloques oscuros declaran los mismos valores nuevos | Ejecutar |
| `packages/ui/src/tokens/tokens.scan.test.ts` | Debe pasar: `themes.css` no nombra primitivos (usa `--dark-chart-1`) | Ejecutar |
| `packages/ui/src/tokens/tokens.fonts.test.ts` | Debe pasar: sin fuentes nuevas ni URL externas | Ejecutar |
| Puerta de axe de Storybook (`pnpm --filter @confia/ui test:a11y`) | Debe pasar; recalcula contraste con el primario nuevo en ambos temas | Ejecutar en claro y oscuro |
| Puerta de axe de rutas con Playwright (ADR-0028) | Afectada cuando se apliquen 9.1 #1 y #2 | Ejecutar tras cada cambio del armazón |
| `apps/admin-web/src/app/shell/components/SideNavigation.test.tsx` y `AppShell.test.tsx` | Afectadas por 9.1 #1 y #2 (props `groups`, marca en la barra lateral) | Actualizar con el cambio |
| `packages/ui/src/atoms/badge/Badge.test.tsx` línea 80 | Afectada por 9.1 #5 si cambia la altura o el relleno por tamaño | Actualizar con el cambio |
| `packages/ui/src/molecules/table/Table.test.tsx`, `ResourceScreen.test.tsx`, `PageContent.test.tsx`, `ScreenLayout.test.tsx` | Afectadas por 9.1 #3, #4 y #7 | Actualizar con el cambio |
| `packages/ui/src/no-network.test.tsx` | Falla DS61 en cuanto se exporte un componente nuevo sin fixture | Agregar `Breadcrumb`, `PageHeader`, `Card`, `KeyFigure` |

---

## 10. Documentos relacionados

| Documento | Relación |
|---|---|
| `00-principios-de-diseno.md` | Principios 2, 3, 6 y 7; diferencias entre panel y portal |
| `01-tokens-de-diseno.md` | Arquitectura de tokens; este documento sustituye su §2.2 y amplía §3, §9 |
| `02-sistema-de-diseno-y-componentes.md` | Inventario donde se incorporan `PageHeader`, `Breadcrumb`, `Card`, `KeyFigure` |
| `03-accesibilidad.md` | Criterios AA y puertas de axe |
| `04-patrones-de-interaccion.md` | Navegación, listados y confirmaciones |

---

## 11. Cambios visuales (segunda pasada, panel compacto)

Reglas que se aplican a todo el panel administrativo. El portal conserva sus objetivos táctiles de 44 px.

**Diálogos.** El ancho depende del propósito, con la propiedad opcional `size` de `Dialog` y `ResourceFormDialog`: `sm` (28 rem) para confirmaciones, `md` (34 rem, valor por defecto) para formularios y `lg` (44 rem) para formularios anchos y diálogos de detalle. Relleno de 16 a 20 px, título de 16 px, pie compacto con botones `sm`. El cuerpo sigue siendo la única zona con desplazamiento, con trampa de foco y `aria` de Radix intactos. Los campos cortos se agrupan en dos columnas con `FieldGrid` y `FullRow` (fechas, importe y moneda, código y nombre); en pantallas estrechas vuelven a una columna. Campos de formulario de 36 px de alto.

**Tablas.** Filas de 28 a 32 px (`py-1.5`), texto de 13 px, encabezado de 11 px en mayúsculas con relleno reducido, flechas de orden de 14 px y leyenda oculta visualmente cuando un título ya nombra la tabla. Encabezado fijo (`stickyHeader`) y resaltado al pasar el cursor en los listados. Los importes siguen alineados a la derecha con numeración tabular. Las acciones de fila usan el tamaño `xs` (28 px, texto de 12 px).

**Color de los botones según la acción.** Nunca se depende solo del color: el texto del botón se conserva.

| Acción | Variante | Uso |
|---|---|---|
| Crear, guardar, confirmar | `primary` (azul de marca) | Acción principal de la pantalla o del diálogo |
| Cancelar, editar, ver detalle | `secondary` (contorno neutro) | Acciones secundarias |
| Archivar, desactivar, cerrar vigencia, retirar, anular el consentimiento | `destructive` (rojo) en cabeceras y diálogos; `destructive-soft` en filas | Acciones destructivas o irreversibles |
| Activar, reactivar, otorgar | `success` (verde) en cabeceras y diálogos; `success-soft` en filas | Cambios de estado positivos |
| Advertencia | `warning` (ámbar) | Disponible; ninguna pantalla lo usa todavía |

Los tokens nuevos son `--button-success-*`, `--button-warning-*`, `--button-danger-soft-*` y `--button-success-soft-*` en `components.css`. Reutilizan pares ya medidos (`--color-status-*-solid-bg` con texto blanco, y los pares fondo y texto de las insignias de estado), de modo que el contraste AA de ambos temas queda cubierto sin colores nuevos. `ResourceFormDialog` acepta `submitVariant` para el botón de envío.

**Cabeceras.** `PageHeader` es compacto: migas pequeñas arriba a la izquierda; título (20 px en nivel 1, 16 px en nivel 2) con la insignia de estado en la misma línea y descripción de una línea en tono atenuado a la izquierda; acciones a la derecha en la misma fila, con el botón primario al final. En los listados, la barra de filtros va justo debajo, en una sola fila con borde (`ResourceScreen`). Los espacios verticales bajan de 24 a 12 px entre cabecera, barra y tabla. En los detalles, título, insignia y acciones comparten fila, y la tarjeta de resumen usa `Card padding="sm"` con los términos en una cuadrícula de 3 o 4 columnas.
