# CONFIA. Tokens de diseño

> Estado: **Propuesta v1.0**
> Continúa las decisiones de `00-principios-de-diseno.md`. Ante cualquier conflicto, gana ese
> documento.
> Consumido por `packages/ui` (variables CSS y preset de Tailwind v4) y por
> `02-sistema-de-diseno-y-componentes.md` y `03-accesibilidad.md`, que reutilizan exactamente los
> nombres de token definidos aquí.

---

## 1. Arquitectura de tokens en tres niveles

Todo valor visual del sistema pasa por tres niveles. Un componente **nunca** consume un token
primitivo directamente.

```
Nivel 1: Primitivo    ->  Nivel 2: Semántico        ->  Nivel 3: Componente
--color-blue-600           --color-primary               --button-primary-bg
--color-green-600          --color-status-paid           --status-badge-paid-bg
--space-4                  --space-md                     --data-table-cell-padding-y
```

**Por qué un componente nunca usa un primitivo directamente.** Un primitivo describe un color o
una medida sin propósito ("azul 600", "16 píxeles"). Un semántico describe una decisión de
producto ("este es el color de una acción primaria", "este es el color de un cargo vencido"). Si
`Button` importara `--color-blue-600` en lugar de `--color-primary`, cambiar el color
institucional de CONFIA exigiría editar cada componente uno por uno. Si además `Button` y
`StatusBadge` compartieran el mismo primitivo por coincidencia, un cambio de marca podría teñir de
azul institucional una fila que debía verse vencida en rojo. El nivel semántico es el único punto
de verdad entre "qué significa" y "qué color tiene", y el nivel de componente es el único punto
donde un elemento de interfaz concreto decide cómo usar ese significado.

**Regla dura.** `packages/ui` exporta solo tokens semánticos y de componente como variables
utilizables (`bg-primary`, `text-status-overdue-fg`, etc.). Los primitivos existen únicamente como
la tabla de definición dentro de `@theme` y no se documentan como API pública del sistema de
diseño.

Ejemplo de la cadena completa para un botón primario:

```css
/* Nivel 1, primitivo, solo dentro de @theme */
--color-blue-600: #2563EB;

/* Nivel 2, semántico, en :root */
--color-primary: var(--color-blue-600);
--color-primary-fg: #FFFFFF;

/* Nivel 3, de componente, en el CSS de Button */
--button-primary-bg: var(--color-primary);
--button-primary-fg: var(--color-primary-fg);
```

---

## 2. Escala de color

### 2.1 Neutro base

Escala fría (con matiz azulado, coherente con el primario institucional), de 50 a 950.

| Paso | Hex | Uso típico |
|---|---|---|
| `neutral-50` | `#F8FAFC` | Fondo de lienzo (`canvas`) en modo claro |
| `neutral-100` | `#F1F5F9` | Fondo de fila alterna, fondo de badge neutro |
| `neutral-200` | `#E2E8F0` | Bordes decorativos, divisores |
| `neutral-300` | `#CBD5E1` | Bordes decorativos sobre superficie elevada |
| `neutral-400` | `#94A3B8` | Iconos inactivos, texto deshabilitado |
| `neutral-500` | `#64748B` | Texto tenue, bordes interactivos (cumple 3:1) |
| `neutral-600` | `#475569` | Texto secundario |
| `neutral-700` | `#334155` | Texto secundario sobre superficie elevada, encabezados de tabla |
| `neutral-800` | `#1E293B` | Superficie oscura elevada (modo oscuro) |
| `neutral-900` | `#0F172A` | Texto primario, superficie base (modo oscuro) |
| `neutral-950` | `#020617` | Texto de máximo énfasis, fondo de lienzo (modo oscuro) |

### 2.2 Primario institucional

Azul profundo. Transmite confianza y seriedad financiera sin la agresividad de un azul saturado
de marca de consumo. Es el único color que aparece en la acción primaria de cada pantalla
(principio 3 de `00-principios-de-diseno.md`).

| Paso | Hex | Uso típico |
|---|---|---|
| `primary-50` | `#EFF6FF` | Fondo suave (chips, selección de fila, estado activo de menú) |
| `primary-100` | `#DBEAFE` | Fondo suave con más presencia |
| `primary-200` | `#BFDBFE` | Borde suave sobre fondo primario claro |
| `primary-300` | `#93C5FD` | Reservado para modo oscuro (texto sobre fondo saturado) |
| `primary-400` | `#60A5FA` | Enlaces y foco en modo oscuro |
| `primary-500` | `#3B82F6` | Anillo de foco en modo claro |
| `primary-600` | `#2563EB` | Color primario por defecto: botones, enlaces activos, elementos seleccionados |
| `primary-700` | `#1D4ED8` | Estado `hover` y `active` del primario, enlaces en texto |
| `primary-800` | `#1E40AF` | Reservado para fondos densos (encabezado de impresión, barra lateral activa) |
| `primary-900` | `#1E3A8A` | Máximo énfasis, uso puntual |

### 2.3 Colores de apoyo (retroalimentación genérica de interfaz)

Distintos de los colores de estado financiero de la sección 4. Se usan en notificaciones,
alertas y validación de formularios que **no** describen el estado de una cuenta o un cargo (por
ejemplo "se guardó el cambio" o "revisa este campo"). Comparten familia de matiz con su estado
financiero análogo para que el sistema se sienta coherente, pero son tokens semánticos distintos:
cambiar el color de un mensaje de éxito genérico nunca debe cambiar accidentalmente el color de
"pagado".

| Semántico | Primitivo base | Hex |
|---|---|---|
| `--color-feedback-success` | `green-600` | `#16A34A` |
| `--color-feedback-warning` | `orange-600` | `#EA580C` |
| `--color-feedback-danger` | `red-600` | `#DC2626` |
| `--color-feedback-info` | `sky-600` | `#0284C7` |

---

## 3. Tokens semánticos

| Token | Valor claro | Valor oscuro | Rol |
|---|---|---|---|
| `--color-bg-canvas` | `neutral-50` `#F8FAFC` | `#05080F` | Fondo de página, detrás de tarjetas y tablas |
| `--color-bg-surface` | `#FFFFFF` | `#0B1220` | Fondo de tarjeta, tabla, formulario |
| `--color-bg-surface-elevated` | `#FFFFFF` (con sombra) | `#131C2E` | Fondo de modal, popover, menú desplegable, tooltip |
| `--color-text-primary` | `neutral-900` `#0F172A` | `#F1F5F9` | Texto principal, importes, títulos |
| `--color-text-secondary` | `neutral-600` `#475569` | `#CBD5E1` | Texto de apoyo, descripciones, encabezados de tabla |
| `--color-text-tertiary` | `neutral-500` `#64748B` | `#94A3B8` | Texto tenue: metadatos, marcas de tiempo, ayudas |
| `--color-text-disabled` | `neutral-400` `#94A3B8` | `#475569` | Texto de control deshabilitado. Ver nota de exención en sección 5 |
| `--color-text-inverse` | `#FFFFFF` | `#0B1220` | Texto sobre fondo saturado (botón primario, badge sólido) |
| `--color-text-link` | `primary-700` `#1D4ED8` | `primary-400` `#60A5FA` | Enlaces en texto corrido |
| `--color-border-subtle` | `neutral-200` `#E2E8F0` | `#1E293B` | Divisores decorativos. No es el único portador de un límite interactivo |
| `--color-border-interactive` | `neutral-500` `#64748B` | `neutral-400` `#94A3B8` | Borde de input, select, checkbox, borde de tarjeta interactiva |
| `--color-border-focus` | `primary-500` `#3B82F6` | `primary-400` `#60A5FA` | Anillo de foco, 2 px mínimo |

---

## 4. Estados financieros: color, icono y texto

**Regla dura del principio 6 y del principio 2.** Ningún estado financiero se comunica solo con
color. Los tres portadores (color, icono, texto) son obligatorios y van juntos en `StatusBadge`
(ver `02-sistema-de-diseno-y-componentes.md`).

Cada estado define cinco tokens: color de identidad (`--color-status-{estado}`, usado en icono y
borde de acento), fondo suave (`-bg`), texto sobre ese fondo (`-fg`), borde de acento (`-border`,
igual al color de identidad) y fondo sólido para variantes rellenas o gráficos (`-solid-bg`, con
texto siempre en `--color-text-inverse`).

| Estado | Token base | Icono Lucide | Etiqueta es-HN | Significado en CONFIA |
|---|---|---|---|---|
| Pagado | `--color-status-paid` | `CheckCircle2` | "Pagado" | El cargo, o el período consultado, tiene saldo en cero y no queda remanente pendiente |
| Pendiente | `--color-status-pending` | `Clock3` | "Pendiente" | El cargo está devengado, su fecha de vencimiento aún no pasó, y no tiene pago aplicado |
| Parcial | `--color-status-partial` | `CircleDashed` | "Parcial" | Se aplicó un pago menor al importe del cargo. Queda saldo abierto por la diferencia |
| Vencido | `--color-status-overdue` | `AlertTriangle` | "Vencido" | La fecha de vencimiento ya pasó y el cargo no tiene pago total aplicado |
| Anulado | `--color-status-void` | `Ban` | "Anulado" | La transacción fue reversada contablemente (principio 4). No cuenta para el saldo vigente |
| En promesa | `--color-status-promised` | `CalendarClock` | "En promesa" | El encargado registró un compromiso de pago para una fecha futura, aún sin pago aplicado |
| Reembolsado | `--color-status-refunded` | `RotateCcw` | "Reembolsado" | Se emitió una nota de crédito o devolución sobre un pago que ya había sido recibido |

Valores concretos de cada token, verificados en la sección 5:

| Estado | `-border` / identidad claro | `-solid-bg` claro | `-bg` claro | `-fg` claro | identidad oscuro | `-bg` oscuro | `-fg` oscuro |
|---|---|---|---|---|---|---|---|
| `paid` | `#16A34A` | `#15803D` | `#F0FDF4` | `#166534` | `#4ADE80` | `#0F2A1B` | `#4ADE80` |
| `pending` | `#0284C7` | `#0369A1` | `#F0F9FF` | `#075985` | `#38BDF8` | `#0B2536` | `#38BDF8` |
| `partial` | `#EA580C` | `#C2410C` | `#FFF7ED` | `#9A3412` | `#FB923C` | `#2B1608` | `#FB923C` |
| `overdue` | `#DC2626` | `#B91C1C` | `#FEF2F2` | `#B91C1C` | `#F87171` | `#2A0F0F` | `#F87171` |
| `void` | `#52525B` | `#3F3F46` | `#F4F4F5` | `#3F3F46` | `#A1A1AA` | `#1F1F23` | `#A1A1AA` |
| `promised` | `#7C3AED` | `#6D28D9` | `#F5F3FF` | `#6D28D9` | `#A78BFA` | `#211539` | `#A78BFA` |
| `refunded` | `#0D9488` | `#0F766E` | `#F0FDFA` | `#0F766E` | `#2DD4BF` | `#0B2622` | `#2DD4BF` |

`-solid-bg` siempre se combina con texto `--color-text-inverse` (blanco en claro, `#0B1220` en
oscuro no aplica: el sólido se reserva para chips y gráficos y conserva texto blanco en ambos
temas porque el fondo sigue siendo el saturado de la tabla anterior).

---

## 5. Contraste: valores verificados

Todos los pares de este documento se calcularon con la fórmula de luminancia relativa de WCAG 2.1
(`(L1 + 0.05) / (L2 + 0.05)`, canal sRGB linealizado). No hay cifras estimadas a ojo. Umbral
mínimo: **4.5:1** para texto normal, **3:1** para componentes de interfaz (bordes que definen un
control, iconos que son el único indicador, anillo de foco).

### 5.1 Texto sobre superficie, modo claro (fondo `#FFFFFF`)

| Par | Contraste | Resultado |
|---|---|---|
| `--color-text-primary` `#0F172A` | 17.85:1 | Cumple |
| `--color-text-secondary` `#475569` | 7.58:1 | Cumple |
| `--color-text-tertiary` `#64748B` | 4.76:1 | Cumple |
| `--color-text-disabled` `#94A3B8` | 2.56:1 | **No cumple 4.5:1. Ver nota** |

**Nota sobre texto deshabilitado.** El criterio 1.4.3 de WCAG exime explícitamente al texto de
controles inactivos ("contenido incidental" y componentes deshabilitados) del mínimo de
contraste, porque ese texto no transmite información que la persona deba leer para operar el
sistema en ese momento. `--color-text-disabled` se usa exclusivamente dentro de un control con
`disabled` o `aria-disabled="true"`, nunca en texto informativo activo. Si un componente necesita
comunicar por qué algo está inactivo, ese texto de explicación usa `--color-text-secondary`, que
sí cumple.

### 5.2 Componentes de interfaz, modo claro

| Par | Contraste | Resultado |
|---|---|---|
| `--color-border-interactive` `#64748B` sobre `#FFFFFF` | 4.76:1 | Cumple (min 3:1) |
| `--color-border-focus` `#3B82F6` sobre `#FFFFFF` | 3.68:1 | Cumple (min 3:1) |
| `--color-border-subtle` `#E2E8F0` sobre `#FFFFFF` | 1.23:1 | Decorativo, sin mínimo exigido (ver nota) |

**Nota sobre bordes decorativos.** `--color-border-subtle` separa filas y secciones sin ser el
portador de un estado ni el límite de un control operable. WCAG 1.4.11 exige 3:1 en los bordes que
identifican un componente de interfaz, no en divisores puramente visuales. Ningún borde decorativo
es, por sí solo, la única forma de saber que algo está vencido o es interactivo: eso lo cubren el
icono, el texto y, en controles, `--color-border-interactive` o `--color-border-focus`.

### 5.3 Estados financieros, modo claro

| Estado | Texto `-fg` sobre `-bg` | Contraste | Borde `-border`/identidad sobre `#FFFFFF` | Contraste | Blanco sobre `-solid-bg` | Contraste |
|---|---|---|---|---|---|---|
| Pagado | `#166534` / `#F0FDF4` | 6.81:1 | `#16A34A` | 3.30:1 | `#15803D` | 5.02:1 |
| Pendiente | `#075985` / `#F0F9FF` | 7.09:1 | `#0284C7` | 4.10:1 | `#0369A1` | 5.93:1 |
| Parcial | `#9A3412` / `#FFF7ED` | 6.88:1 | `#EA580C` | 3.56:1 | `#C2410C` | 5.18:1 |
| Vencido | `#B91C1C` / `#FEF2F2` | 5.91:1 | `#DC2626` | 4.83:1 | `#B91C1C` | 6.47:1 |
| Anulado | `#3F3F46` / `#F4F4F5` | 9.50:1 | `#52525B` | 7.73:1 | `#3F3F46` | 10.44:1 |
| En promesa | `#6D28D9` / `#F5F3FF` | 6.48:1 | `#7C3AED` | 5.70:1 | `#6D28D9` | 7.10:1 |
| Reembolsado | `#0F766E` / `#F0FDFA` | 5.25:1 | `#0D9488` | 3.74:1 | `#0F766E` | 5.47:1 |

Las tres primeras columnas cumplen el mínimo de texto (4.5:1) y las columnas de borde cumplen el
mínimo de componente (3:1). Ningún valor de esta tabla se redondeó hacia arriba para alcanzar el
umbral: donde un tono inicial no alcanzaba el mínimo (por ejemplo bordes en tono `100`/`200`,
descartados en la iteración de diseño), se sustituyó por un tono `600` que sí lo cumple.

### 5.4 Modo oscuro (fondo `#0B1220`)

| Par | Contraste | Resultado |
|---|---|---|
| `--color-text-primary` `#F1F5F9` | 17.09:1 | Cumple |
| `--color-text-secondary` `#CBD5E1` | 12.61:1 | Cumple |
| `--color-text-tertiary` `#94A3B8` | 7.30:1 | Cumple |
| `--color-text-primary` sobre `--color-bg-surface-elevated` `#131C2E` | 15.54:1 | Cumple |
| `--color-border-interactive` `#94A3B8` | 7.30:1 | Cumple (min 3:1) |
| `--color-border-focus` `#60A5FA` | 7.36:1 | Cumple (min 3:1) |

| Estado | Texto `-fg` sobre `-bg` oscuro | Contraste | Identidad sobre `#0B1220` | Contraste |
|---|---|---|---|---|
| Pagado | `#4ADE80` / `#0F2A1B` | 8.81:1 | `#4ADE80` | 10.74:1 |
| Pendiente | `#38BDF8` / `#0B2536` | 7.36:1 | `#38BDF8` | 8.74:1 |
| Parcial | `#FB923C` / `#2B1608` | 7.60:1 | `#FB923C` | 8.27:1 |
| Vencido | `#F87171` / `#2A0F0F` | 6.47:1 | `#F87171` | 6.77:1 |
| Anulado | `#A1A1AA` / `#1F1F23` | 6.41:1 | `#A1A1AA` | 7.31:1 |
| En promesa | `#A78BFA` / `#211539` | 6.27:1 | `#A78BFA` | 6.88:1 |
| Reembolsado | `#2DD4BF` / `#0B2622` | 8.58:1 | `#2DD4BF` | 10.06:1 |

Todos los pares de esta sección cumplen el mínimo aplicable. Cuando este documento cambie una
paleta, quien la cambie debe recalcular esta tabla antes de fusionar el cambio: no se aceptan
valores de contraste estimados.

---

## 6. Modo claro y modo oscuro

**Regla obligatoria.** Ningún color se define únicamente dentro del bloque de modo oscuro. Todo
token tiene un valor base en `:root` y, cuando corresponde, un `override` dentro del bloque de
modo oscuro. Esto evita variables `undefined` cuando el navegador no soporta la consulta de medio,
y hace explícito, en un solo lugar, cuáles tokens cambian entre temas y cuáles no (la paleta
`primary-600` de botón, por ejemplo, no cambia).

El sistema soporta dos mecanismos de activación, ambos obligatorios:

1. **Automático por preferencia del sistema operativo**, vía `@media (prefers-color-scheme: dark)`.
   Es el comportamiento por defecto del portal, que no ofrece conmutador visible (ver
   `00-principios-de-diseno.md`, sección 5).
2. **Manual por atributo `data-theme`** en el elemento raíz (`data-theme="dark"` o
   `data-theme="light"`), para el conmutador del panel administrativo. El atributo manual siempre
   gana sobre la preferencia del sistema.

El bloque completo de variables está en la sección 14.

---

## 7. Tipografía

### 7.1 Familia

```css
--font-family-sans:
  'Inter', ui-sans-serif, system-ui, -apple-system, 'Segoe UI', Roboto,
  'Helvetica Neue', Arial, 'Noto Sans', sans-serif, 'Apple Color Emoji', 'Segoe UI Emoji';
```

Inter se elige por su métrica pensada para interfaces de datos: números tabulares nativos,
distinción clara entre `0` y `O`, `1` y `l`, algo que en una interfaz que muestra dinero e
identificadores fiscales no es un detalle estético.

### 7.2 Escala modular

| Token | rem | px de referencia | Line-height | Uso |
|---|---|---|---|---|
| `--text-xs` | `0.75rem` | 12 | `1rem` (16) | Metadatos de tabla densa, ayudas de formulario |
| `--text-sm` | `0.875rem` | 14 | `1.25rem` (20) | Texto de tabla, texto de apoyo, controles del panel |
| `--text-base` | `1rem` | 16 | `1.5rem` (24) | Cuerpo de texto por defecto, controles del portal |
| `--text-lg` | `1.125rem` | 18 | `1.75rem` (28) | Subtítulos, importe en fila destacada |
| `--text-xl` | `1.25rem` | 20 | `1.75rem` (28) | Título de tarjeta, importe en `StatCard` |
| `--text-2xl` | `1.5rem` | 24 | `2rem` (32) | Título de página (`PageHeader`) |
| `--text-3xl` | `1.875rem` | 30 | `2.25rem` (36) | Cifra dominante del portal ("cuánto debe") |
| `--text-4xl` | `2.25rem` | 36 | `2.5rem` (40) | Indicador grande de dashboard directivo |
| `--text-5xl` | `3rem` | 48 | `1` | Uso puntual en pantalla de proyección |

### 7.3 Pesos

| Token | Valor | Uso |
|---|---|---|
| `--font-weight-regular` | `400` | Cuerpo de texto |
| `--font-weight-medium` | `500` | Etiquetas, texto de botón, encabezado de tabla |
| `--font-weight-semibold` | `600` | Subtítulos, importes en énfasis medio |
| `--font-weight-bold` | `700` | Títulos de página, importe dominante del portal |

### 7.4 Espaciado entre letras

| Token | Valor | Uso |
|---|---|---|
| `--tracking-tight` | `-0.01em` | Títulos grandes (`--text-3xl` en adelante) |
| `--tracking-normal` | `0` | Cuerpo de texto |
| `--tracking-wide` | `0.02em` | Etiquetas en versalitas o mayúsculas (encabezado de sección, eyebrow) |

### 7.5 Numeración tabular: regla obligatoria

**Todo importe, sin excepción, se renderiza con numeración tabular.** Es la única forma de que una
columna de dinero se pueda leer verticalmente sin que los dígitos "bailen" por el ancho variable
de cada cifra. El componente `MoneyAmount` de `02-sistema-de-diseno-y-componentes.md` la aplica
internamente y ningún consumidor puede desactivarla.

```css
.money-amount,
[data-tabular-nums] {
  font-variant-numeric: tabular-nums;
  /* Respaldo explícito para motores que ignoran font-variant-numeric
     o cuando la fuente no expone la característica OpenType por esa vía. */
  font-feature-settings: 'tnum' 1, 'lnum' 1;
}
```

`'lnum'` (cifras de altura uniforme, sin descendentes) se declara junto con `'tnum'` porque una
tabla de importes donde un `4` desciende y un `8` no, rompe la misma lectura vertical que
`tabular-nums` busca resolver.

---

## 8. Espaciado

Base de 4 px. Toda medida de relleno, margen y separación del sistema es un múltiplo de esta base.

| Token | rem | px |
|---|---|---|
| `--space-0` | `0` | 0 |
| `--space-1` | `0.25rem` | 4 |
| `--space-2` | `0.5rem` | 8 |
| `--space-3` | `0.75rem` | 12 |
| `--space-4` | `1rem` | 16 |
| `--space-5` | `1.25rem` | 20 |
| `--space-6` | `1.5rem` | 24 |
| `--space-7` | `1.75rem` | 28 |
| `--space-8` | `2rem` | 32 |
| `--space-9` | `2.25rem` | 36 |
| `--space-10` | `2.5rem` | 40 |
| `--space-12` | `3rem` | 48 |
| `--space-14` | `3.5rem` | 56 |
| `--space-16` | `4rem` | 64 |
| `--space-20` | `5rem` | 80 |
| `--space-24` | `6rem` | 96 |

Aplicación por densidad (principio 7): fila de tabla del panel en densidad cómoda usa
`--space-2` más `--space-1` de relleno vertical (36 px de alto), densidad compacta usa
`--space-2` (32 px de alto). El portal usa `--space-4` a `--space-6` de relleno en tarjetas.

---

## 9. Radios, bordes y sombras

### 9.1 Radios

| Token | Valor | Uso |
|---|---|---|
| `--radius-sm` | `0.25rem` (4px) | Badge, chip, input pequeño |
| `--radius-md` | `0.375rem` (6px) | Botón, input, celda de menú |
| `--radius-lg` | `0.5rem` (8px) | Tarjeta, popover |
| `--radius-xl` | `0.75rem` (12px) | Modal, hoja lateral (`Sheet`) |
| `--radius-2xl` | `1rem` (16px) | Tarjeta destacada del portal (`StatCard`, `AccountSummary`) |
| `--radius-full` | `9999px` | Avatar, indicador de punto, botón circular |

### 9.2 Ancho de borde

| Token | Valor | Uso |
|---|---|---|
| `--border-width-hairline` | `1px` | Borde por defecto de tarjeta, input, tabla |
| `--border-width-thick` | `2px` | Anillo de foco, barra de acento de estado en fila de tabla |

### 9.3 Sombras y elevación (modo claro)

| Token | Valor | Uso |
|---|---|---|
| `--shadow-sm` | `0 1px 2px 0 rgb(15 23 42 / 0.06)` | Tarjeta en reposo |
| `--shadow-md` | `0 4px 8px -2px rgb(15 23 42 / 0.10), 0 2px 4px -2px rgb(15 23 42 / 0.06)` | Tarjeta con `hover`, dropdown |
| `--shadow-lg` | `0 12px 20px -4px rgb(15 23 42 / 0.12), 0 4px 8px -4px rgb(15 23 42 / 0.08)` | Popover, menú contextual |
| `--shadow-xl` | `0 24px 36px -8px rgb(15 23 42 / 0.18), 0 8px 12px -6px rgb(15 23 42 / 0.10)` | Modal, `Sheet` |

**Modo oscuro no usa las mismas sombras.** Una sombra negra semitransparente es casi invisible
sobre un fondo ya oscuro. La elevación en modo oscuro se comunica principalmente con
`--color-bg-surface-elevated` (un paso más claro que la superficie base) y, de forma secundaria,
con una sombra de opacidad mayor:

| Token | Valor | Uso |
|---|---|---|
| `--shadow-dark-md` | `0 4px 12px -2px rgb(0 0 0 / 0.45)` | Dropdown, popover en modo oscuro |
| `--shadow-dark-xl` | `0 24px 48px -12px rgb(0 0 0 / 0.60)` | Modal en modo oscuro |

---

## 10. Movimiento

| Token | Valor | Uso |
|---|---|---|
| `--duration-fast` | `100ms` | Cambios de estado de un control (`hover`, `active`) |
| `--duration-base` | `150ms` | Aparición de tooltip, popover |
| `--duration-moderate` | `200ms` | Apertura de dropdown, acordeón |
| `--duration-slow` | `300ms` | Entrada de modal y `Sheet` |
| `--ease-standard` | `cubic-bezier(0.4, 0, 0.2, 1)` | Transición general |
| `--ease-decelerate` | `cubic-bezier(0, 0, 0.2, 1)` | Elemento que entra a la pantalla |
| `--ease-accelerate` | `cubic-bezier(0.4, 0, 1, 1)` | Elemento que sale de la pantalla |

Coherente con el principio 7 y con la sección 5 de `00-principios-de-diseno.md`: nunca hay
animación decorativa, y el portal no alarga estas duraciones salvo la confirmación visual de una
transferencia declarada, que puede usar `--duration-slow` una sola vez.

**Bloque obligatorio de movimiento reducido:**

```css
@media (prefers-reduced-motion: reduce) {
  *,
  *::before,
  *::after {
    animation-duration: 0.01ms !important;
    animation-iteration-count: 1 !important;
    transition-duration: 0.01ms !important;
    scroll-behavior: auto !important;
  }
}
```

No se usa `animation: none` ni `transition: none` porque algunos componentes dependen de que un
evento `transitionend` se dispare para limpiar estado; anular la duración a un valor casi cero
conserva ese evento sin producir movimiento perceptible.

---

## 11. Puntos de quiebre responsivos

| Token | Valor | Uso |
|---|---|---|
| `--breakpoint-sm` | `640px` | Portal: transición de una columna a dos en formularios |
| `--breakpoint-md` | `768px` | Portal en tablet, panel en el límite inferior soportado |
| `--breakpoint-lg` | `1024px` | Panel: aparece la barra lateral persistente sin colapsar |
| `--breakpoint-xl` | `1280px` | Panel: `DataTable` muestra todas las columnas por defecto |
| `--breakpoint-2xl` | `1536px` | Panel en monitor amplio: aparece panel de detalle lateral fijo |

El portal se diseña **primero** para 360 a 412 px de ancho (perfil de Doña Marlen, sección 4.4 de
`00-principios-de-diseno.md`) y escala hacia arriba. El panel se diseña primero para `lg` (1024px)
y degrada hacia abajo solo para consulta, nunca para cobro en ventanilla.

---

## 12. Iconografía

**Librería única: Lucide.** Prohibido el emoji como icono funcional (antipatrón de
`00-principios-de-diseno.md`, sección 6): depende de la fuente del sistema operativo, no se puede
tematizar con los tokens de esta sección y no imprime de forma consistente en un recibo o un
reporte PDF.

### 12.1 Tamaños permitidos

| Token | Valor | Uso |
|---|---|---|
| `--icon-size-xs` | `14px` | Icono dentro de `Badge` pequeño, junto a texto `--text-xs` |
| `--icon-size-sm` | `16px` | Icono en celda de tabla densa, `StatusBadge` en tabla |
| `--icon-size-md` | `20px` | Icono por defecto: botón, input, ítem de menú |
| `--icon-size-lg` | `24px` | Icono de encabezado de sección, `PageHeader` |
| `--icon-size-xl` | `32px` | Icono de `EmptyState`, `ErrorState` |
| `--icon-size-2xl` | `40px` | Ilustración de estado vacío en el portal |

Ningún icono se usa fuera de esta escala. Un tamaño intermedio arbitrario (por ejemplo 18px) es un
defecto de implementación.

### 12.2 Grosor de trazo

- `--icon-stroke-fine`: `1.5`. Se usa en `--icon-size-lg` en adelante, donde un trazo de `2` se ve
  pesado.
- `--icon-stroke-default`: `2`. Se usa en `--icon-size-md` y menores, donde un trazo de `1.5` pierde
  definición a tamaño pequeño y en pantallas de baja densidad de píxeles.

Nunca se mezclan los dos grosores dentro del mismo grupo visual (por ejemplo, dos iconos
consecutivos en una barra de herramientas).

### 12.3 Mapa de icono a significado

| Icono Lucide | Significado en CONFIA | Contexto de uso |
|---|---|---|
| `CheckCircle2` | Pagado, confirmado, éxito | `StatusBadge` pagado, toast de éxito |
| `Clock3` | Pendiente, en espera | `StatusBadge` pendiente |
| `CircleDashed` | Parcial, incompleto | `StatusBadge` parcial |
| `AlertTriangle` | Vencido, advertencia | `StatusBadge` vencido, banner de alerta |
| `Ban` | Anulado, acción bloqueada | `StatusBadge` anulado |
| `CalendarClock` | Promesa de pago, fecha comprometida | `StatusBadge` en promesa |
| `RotateCcw` | Reembolsado, reverso, deshacer | `StatusBadge` reembolsado, acción "Reversar" |
| `AlertCircle` | Error de formulario, error de sistema | `FormError`, `ErrorState`, `Alert` destructivo |
| `Info` | Información neutra | `Alert` informativo, ayuda contextual |
| `Search` | Buscar | Campo de búsqueda, búsqueda global del panel |
| `Filter` | Filtrar | `DataTableFilters`, `DataTableToolbar` |
| `SlidersHorizontal` | Ajustes de columnas o densidad | `ColumnVisibility`, control de densidad |
| `Download` | Exportar, descargar comprobante | Exportación de tabla, `PaymentReceipt` descargable |
| `Upload` | Adjuntar archivo | `FileUpload` |
| `Printer` | Imprimir | `PrintLayout`, recibo en ventanilla |
| `Plus` | Crear, agregar | Acción primaria de creación |
| `Pencil` | Editar (solo entidades no financieras) | Editar datos de estudiante, encargado, catálogo |
| `Trash2` | Eliminar (solo entidades no financieras, nunca dinero) | Eliminar un adjunto, un borrador |
| `CircleDollarSign` | Dinero, cobro, concepto de pago | Ícono de módulo de cobro, `CurrencyInput` |
| `Receipt` | Documento fiscal, factura | Ícono de módulo de facturación, `PaymentReceipt` |
| `Landmark` | Institución, cuenta bancaria | Conciliación, datos de la institución |
| `Wallet` | Sesión de caja, arqueo | Módulo de caja, `Topbar` con sesión abierta |
| `Users` | Estudiantes o encargados (plural) | Módulo de estudiantes, listado de encargados |
| `User` | Una persona | Avatar por defecto, perfil |
| `GraduationCap` | Estudiante, matrícula | `StudentCard`, módulo académico |
| `Bell` | Notificación | Icono de campana en `Topbar` |
| `ChevronDown` | Desplegar | `Select`, `Accordion`, `DropdownMenu` |
| `ChevronRight` | Navegar hacia adelante, expandir fila | `Breadcrumb`, fila expandible de `DataTable` |
| `X` | Cerrar | `Dialog`, `Sheet`, `Toast` |
| `MoreHorizontal` | Más acciones | Menú de acciones por fila |
| `ArrowUpDown` | Ordenar columna | Encabezado de `DataTable` ordenable |
| `Eye` | Ver detalle, solo lectura | Acción "ver" en fila, indicador de rol de auditor |
| `Lock` | Restringido, bloqueado por permiso | Campo o acción sin permiso |
| `ShieldCheck` | Verificado, auditado | Indicador de bitácora de auditoría |
| `CalendarDays` | Fecha, período | `DateInput`, `DateDisplay`, filtro de rango |
| `TrendingUp` / `TrendingDown` | Variación positiva o negativa respecto al período anterior | `StatCard` |

---

## 13. Elevación y capas: escala de `z-index`

Escala nombrada para evitar que dos desarrollos independientes se disputen números arbitrarios
(`z-index: 9999`). Se declara como propiedades CSS simples en `:root`, **fuera** del bloque
`@theme` de Tailwind, porque la v4 no define un espacio de nombres de tema para `z-index`. Se
consume con la sintaxis arbitraria de Tailwind (`z-[var(--z-modal)]`) o directamente en CSS.

| Token | Valor | Capa |
|---|---|---|
| `--z-base` | `0` | Contenido normal del documento |
| `--z-sticky` | `10` | Encabezado de tabla pegajoso (`sticky`), columna congelada |
| `--z-dropdown` | `1000` | `DropdownMenu`, `Select`, `Combobox` |
| `--z-fixed` | `1100` | `Sidebar` y `Topbar` del panel, barra inferior del portal |
| `--z-overlay` | `1200` | Fondo semitransparente detrás de un `Dialog` o `Sheet` |
| `--z-modal` | `1300` | `Dialog`, `ConfirmDialog`, `DestructiveConfirmDialog`, `Sheet` |
| `--z-popover` | `1400` | `Popover`, `Select` abierto **dentro** de un modal |
| `--z-toast` | `1500` | `Toast` |
| `--z-tooltip` | `1600` | `Tooltip`, siempre por encima de cualquier otro elemento |

Jerarquía de lectura: un tooltip debe poder aparecer sobre un toast, que debe poder aparecer sobre
un modal, que debe poder aparecer sobre la barra fija, que debe poder aparecer sobre un
desplegable de página, que debe poder aparecer sobre contenido pegajoso. El espaciado de 100 entre
capas mayores deja margen para casos intermedios sin renumerar toda la escala.

---

## 14. Bloque final: variables CSS y preset de Tailwind v4

Archivo de referencia: `packages/ui/src/styles/tokens.css`. Se importa una sola vez en el punto de
entrada de cada aplicación (`apps/admin-web` y `apps/portal-web`).

```css
/* ==========================================================================
   CONFIA — Design tokens
   Fuente de verdad: docs/ui-ux/01-tokens-de-diseno.md
   ========================================================================== */

@import 'tailwindcss';

/* --------------------------------------------------------------------------
   1. @theme: primitivos y semánticos con valor por defecto (modo claro).
   Tailwind v4 genera automáticamente la variable CSS y las utilidades
   correspondientes (bg-primary, text-status-overdue-fg, etc).
   -------------------------------------------------------------------------- */
@theme {
  /* Tipografía */
  --font-sans:
    'Inter', ui-sans-serif, system-ui, -apple-system, 'Segoe UI', Roboto,
    'Helvetica Neue', Arial, 'Noto Sans', sans-serif, 'Apple Color Emoji', 'Segoe UI Emoji';

  --text-xs: 0.75rem;
  --text-xs--line-height: 1rem;
  --text-sm: 0.875rem;
  --text-sm--line-height: 1.25rem;
  --text-base: 1rem;
  --text-base--line-height: 1.5rem;
  --text-lg: 1.125rem;
  --text-lg--line-height: 1.75rem;
  --text-xl: 1.25rem;
  --text-xl--line-height: 1.75rem;
  --text-2xl: 1.5rem;
  --text-2xl--line-height: 2rem;
  --text-3xl: 1.875rem;
  --text-3xl--line-height: 2.25rem;
  --text-4xl: 2.25rem;
  --text-4xl--line-height: 2.5rem;
  --text-5xl: 3rem;
  --text-5xl--line-height: 1;

  --font-weight-regular: 400;
  --font-weight-medium: 500;
  --font-weight-semibold: 600;
  --font-weight-bold: 700;

  --tracking-tight: -0.01em;
  --tracking-normal: 0em;
  --tracking-wide: 0.02em;

  /* Espaciado (base 4px, un único multiplicador según convención de Tailwind v4) */
  --spacing: 0.25rem;

  /* Puntos de quiebre */
  --breakpoint-sm: 640px;
  --breakpoint-md: 768px;
  --breakpoint-lg: 1024px;
  --breakpoint-xl: 1280px;
  --breakpoint-2xl: 1536px;

  /* Radios */
  --radius-sm: 0.25rem;
  --radius-md: 0.375rem;
  --radius-lg: 0.5rem;
  --radius-xl: 0.75rem;
  --radius-2xl: 1rem;
  --radius-full: 9999px;

  /* Sombras, modo claro por defecto */
  --shadow-sm: 0 1px 2px 0 rgb(15 23 42 / 0.06);
  --shadow-md: 0 4px 8px -2px rgb(15 23 42 / 0.10), 0 2px 4px -2px rgb(15 23 42 / 0.06);
  --shadow-lg: 0 12px 20px -4px rgb(15 23 42 / 0.12), 0 4px 8px -4px rgb(15 23 42 / 0.08);
  --shadow-xl: 0 24px 36px -8px rgb(15 23 42 / 0.18), 0 8px 12px -6px rgb(15 23 42 / 0.10);

  /* Curvas y duraciones de movimiento */
  --ease-standard: cubic-bezier(0.4, 0, 0.2, 1);
  --ease-decelerate: cubic-bezier(0, 0, 0.2, 1);
  --ease-accelerate: cubic-bezier(0.4, 0, 1, 1);

  /* Primitivos de color: neutro */
  --color-neutral-50: #F8FAFC;
  --color-neutral-100: #F1F5F9;
  --color-neutral-200: #E2E8F0;
  --color-neutral-300: #CBD5E1;
  --color-neutral-400: #94A3B8;
  --color-neutral-500: #64748B;
  --color-neutral-600: #475569;
  --color-neutral-700: #334155;
  --color-neutral-800: #1E293B;
  --color-neutral-900: #0F172A;
  --color-neutral-950: #020617;

  /* Primitivos de color: primario institucional */
  --color-primary-50: #EFF6FF;
  --color-primary-100: #DBEAFE;
  --color-primary-200: #BFDBFE;
  --color-primary-300: #93C5FD;
  --color-primary-400: #60A5FA;
  --color-primary-500: #3B82F6;
  --color-primary-600: #2563EB;
  --color-primary-700: #1D4ED8;
  --color-primary-800: #1E40AF;
  --color-primary-900: #1E3A8A;

  /* Semánticos: superficie y texto (valor de modo claro) */
  --color-bg-canvas: var(--color-neutral-50);
  --color-bg-surface: #FFFFFF;
  --color-bg-surface-elevated: #FFFFFF;
  --color-text-primary: var(--color-neutral-900);
  --color-text-secondary: var(--color-neutral-600);
  --color-text-tertiary: var(--color-neutral-500);
  --color-text-disabled: var(--color-neutral-400);
  --color-text-inverse: #FFFFFF;
  --color-text-link: var(--color-primary-700);
  --color-border-subtle: var(--color-neutral-200);
  --color-border-interactive: var(--color-neutral-500);
  --color-border-focus: var(--color-primary-500);

  /* Semánticos: primario */
  --color-primary: var(--color-primary-600);
  --color-primary-hover: var(--color-primary-700);
  --color-primary-active: var(--color-primary-800);
  --color-primary-fg: #FFFFFF;
  --color-primary-soft-bg: var(--color-primary-50);
  --color-primary-soft-fg: var(--color-primary-700);

  /* Semánticos: retroalimentación genérica de interfaz */
  --color-feedback-success: #16A34A;
  --color-feedback-warning: #EA580C;
  --color-feedback-danger: #DC2626;
  --color-feedback-info: #0284C7;

  /* Semánticos: estados financieros (valor de modo claro) */
  --color-status-paid: #16A34A;
  --color-status-paid-bg: #F0FDF4;
  --color-status-paid-fg: #166534;
  --color-status-paid-border: #16A34A;
  --color-status-paid-solid-bg: #15803D;

  --color-status-pending: #0284C7;
  --color-status-pending-bg: #F0F9FF;
  --color-status-pending-fg: #075985;
  --color-status-pending-border: #0284C7;
  --color-status-pending-solid-bg: #0369A1;

  --color-status-partial: #EA580C;
  --color-status-partial-bg: #FFF7ED;
  --color-status-partial-fg: #9A3412;
  --color-status-partial-border: #EA580C;
  --color-status-partial-solid-bg: #C2410C;

  --color-status-overdue: #DC2626;
  --color-status-overdue-bg: #FEF2F2;
  --color-status-overdue-fg: #B91C1C;
  --color-status-overdue-border: #DC2626;
  --color-status-overdue-solid-bg: #B91C1C;

  --color-status-void: #52525B;
  --color-status-void-bg: #F4F4F5;
  --color-status-void-fg: #3F3F46;
  --color-status-void-border: #52525B;
  --color-status-void-solid-bg: #3F3F46;

  --color-status-promised: #7C3AED;
  --color-status-promised-bg: #F5F3FF;
  --color-status-promised-fg: #6D28D9;
  --color-status-promised-border: #7C3AED;
  --color-status-promised-solid-bg: #6D28D9;

  --color-status-refunded: #0D9488;
  --color-status-refunded-bg: #F0FDFA;
  --color-status-refunded-fg: #0F766E;
  --color-status-refunded-border: #0D9488;
  --color-status-refunded-solid-bg: #0F766E;
}

/* --------------------------------------------------------------------------
   2. z-index: fuera de @theme (Tailwind v4 no tiene espacio de nombres para
   z-index). Se consumen con la sintaxis arbitraria z-[var(--z-modal)].
   -------------------------------------------------------------------------- */
:root {
  --z-base: 0;
  --z-sticky: 10;
  --z-dropdown: 1000;
  --z-fixed: 1100;
  --z-overlay: 1200;
  --z-modal: 1300;
  --z-popover: 1400;
  --z-toast: 1500;
  --z-tooltip: 1600;

  /* Iconografía */
  --icon-size-xs: 14px;
  --icon-size-sm: 16px;
  --icon-size-md: 20px;
  --icon-size-lg: 24px;
  --icon-size-xl: 32px;
  --icon-size-2xl: 40px;
  --icon-stroke-fine: 1.5;
  --icon-stroke-default: 2;

  /* Bordes */
  --border-width-hairline: 1px;
  --border-width-thick: 2px;

  /* Sombras de modo oscuro (no forman parte de @theme: se activan solo bajo el tema oscuro) */
  --shadow-dark-md: 0 4px 12px -2px rgb(0 0 0 / 0.45);
  --shadow-dark-xl: 0 24px 48px -12px rgb(0 0 0 / 0.60);
}

/* --------------------------------------------------------------------------
   3. Modo oscuro automático, según preferencia del sistema operativo.
   Se anula si la persona usuaria eligió un tema de forma explícita
   (atributo data-theme="light" en la raíz).
   -------------------------------------------------------------------------- */
@media (prefers-color-scheme: dark) {
  :root:not([data-theme='light']) {
    --color-bg-canvas: #05080F;
    --color-bg-surface: #0B1220;
    --color-bg-surface-elevated: #131C2E;
    --color-text-primary: #F1F5F9;
    --color-text-secondary: #CBD5E1;
    --color-text-tertiary: #94A3B8;
    --color-text-disabled: #475569;
    --color-text-inverse: #0B1220;
    --color-text-link: var(--color-primary-400);
    --color-border-subtle: #1E293B;
    --color-border-interactive: var(--color-neutral-400);
    --color-border-focus: var(--color-primary-400);

    --color-primary-soft-fg: var(--color-primary-300);

    --color-status-paid: #4ADE80;
    --color-status-paid-bg: #0F2A1B;
    --color-status-paid-fg: #4ADE80;
    --color-status-paid-border: #4ADE80;

    --color-status-pending: #38BDF8;
    --color-status-pending-bg: #0B2536;
    --color-status-pending-fg: #38BDF8;
    --color-status-pending-border: #38BDF8;

    --color-status-partial: #FB923C;
    --color-status-partial-bg: #2B1608;
    --color-status-partial-fg: #FB923C;
    --color-status-partial-border: #FB923C;

    --color-status-overdue: #F87171;
    --color-status-overdue-bg: #2A0F0F;
    --color-status-overdue-fg: #F87171;
    --color-status-overdue-border: #F87171;

    --color-status-void: #A1A1AA;
    --color-status-void-bg: #1F1F23;
    --color-status-void-fg: #A1A1AA;
    --color-status-void-border: #A1A1AA;

    --color-status-promised: #A78BFA;
    --color-status-promised-bg: #211539;
    --color-status-promised-fg: #A78BFA;
    --color-status-promised-border: #A78BFA;

    --color-status-refunded: #2DD4BF;
    --color-status-refunded-bg: #0B2622;
    --color-status-refunded-fg: #2DD4BF;
    --color-status-refunded-border: #2DD4BF;
  }
}

/* --------------------------------------------------------------------------
   4. Modo oscuro manual, vía data-theme="dark" en la raíz (conmutador del
   panel administrativo). Mismos valores que el bloque de preferencia del
   sistema, para que ambos mecanismos sean indistinguibles en pantalla.
   -------------------------------------------------------------------------- */
:root[data-theme='dark'] {
  --color-bg-canvas: #05080F;
  --color-bg-surface: #0B1220;
  --color-bg-surface-elevated: #131C2E;
  --color-text-primary: #F1F5F9;
  --color-text-secondary: #CBD5E1;
  --color-text-tertiary: #94A3B8;
  --color-text-disabled: #475569;
  --color-text-inverse: #0B1220;
  --color-text-link: var(--color-primary-400);
  --color-border-subtle: #1E293B;
  --color-border-interactive: var(--color-neutral-400);
  --color-border-focus: var(--color-primary-400);

  --color-primary-soft-fg: var(--color-primary-300);

  --color-status-paid: #4ADE80;
  --color-status-paid-bg: #0F2A1B;
  --color-status-paid-fg: #4ADE80;
  --color-status-paid-border: #4ADE80;

  --color-status-pending: #38BDF8;
  --color-status-pending-bg: #0B2536;
  --color-status-pending-fg: #38BDF8;
  --color-status-pending-border: #38BDF8;

  --color-status-partial: #FB923C;
  --color-status-partial-bg: #2B1608;
  --color-status-partial-fg: #FB923C;
  --color-status-partial-border: #FB923C;

  --color-status-overdue: #F87171;
  --color-status-overdue-bg: #2A0F0F;
  --color-status-overdue-fg: #F87171;
  --color-status-overdue-border: #F87171;

  --color-status-void: #A1A1AA;
  --color-status-void-bg: #1F1F23;
  --color-status-void-fg: #A1A1AA;
  --color-status-void-border: #A1A1AA;

  --color-status-promised: #A78BFA;
  --color-status-promised-bg: #211539;
  --color-status-promised-fg: #A78BFA;
  --color-status-promised-border: #A78BFA;

  --color-status-refunded: #2DD4BF;
  --color-status-refunded-bg: #0B2622;
  --color-status-refunded-fg: #2DD4BF;
  --color-status-refunded-border: #2DD4BF;
}

/* --------------------------------------------------------------------------
   5. Numeración tabular obligatoria para todo importe.
   -------------------------------------------------------------------------- */
.money-amount,
[data-tabular-nums] {
  font-variant-numeric: tabular-nums;
  font-feature-settings: 'tnum' 1, 'lnum' 1;
}
```

### Uso en un componente (ejemplo, nivel 3 de la arquitectura de tokens)

```css
/* packages/ui/src/components/button/button.css */
.button-primary {
  background-color: var(--color-primary);
  color: var(--color-primary-fg);
  border-radius: var(--radius-md);
  font-weight: var(--font-weight-medium);
  transition: background-color var(--duration-fast) var(--ease-standard);
}

.button-primary:hover {
  background-color: var(--color-primary-hover);
}

.button-primary:focus-visible {
  outline: var(--border-width-thick) solid var(--color-border-focus);
  outline-offset: 2px;
}
```

---

## 15. Documentos relacionados

| Documento | Contenido |
|---|---|
| `00-principios-de-diseno.md` | Principios rectores y antipatrones que estos tokens implementan |
| `02-sistema-de-diseno-y-componentes.md` | Componentes que consumen estos tokens |
| `03-accesibilidad.md` | Criterios WCAG 2.2 AA que estos tokens satisfacen |
