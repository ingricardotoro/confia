# Sistema de diseño y componentes

> Requisito previo: [01-tokens-de-diseno.md](01-tokens-de-diseno.md). Ningún componente define
> colores, espaciados ni tipografías propias. Todo sale de los tokens semánticos.

---

## 1. Arquitectura del sistema de diseño

Se aplica diseño atómico. Los niveles no son una taxonomía decorativa: determinan **dónde vive el
código y qué puede saber cada pieza**.

| Nivel | Qué es | Puede conocer | Ejemplos en CONFIA |
|---|---|---|---|
| **Átomo** | Elemento indivisible de interfaz | Solo tokens | `Button`, `Input`, `Badge`, `Spinner`, `Label` |
| **Molécula** | Combinación pequeña con un propósito | Átomos y tokens | `FormField`, `StatusBadge`, `MoneyAmount`, `StatCard`, `SearchInput` |
| **Organismo** | Bloque funcional completo | Moléculas, átomos, tokens | `DataTable`, `LedgerTimeline`, `Sidebar`, `PaymentReceipt`, `AccountSummary` |
| **Plantilla** | Estructura de página sin datos reales | Organismos | `ListPageLayout`, `FormPageLayout`, `DashboardLayout`, `PortalLayout` |
| **Página** | Plantilla con datos y comportamiento | Todo, más la capa de datos | `PaymentsListPage`, `RegisterPaymentPage`, `StudentAccountPage` |

**Regla de conocimiento del dominio:** los átomos y las moléculas genéricas no saben nada del
negocio. `Button` no sabe qué es un cargo. Los organismos y las moléculas de dominio sí: `MoneyAmount`
conoce el concepto de importe con moneda, y `StatusBadge` conoce los estados financieros.

## 2. Contenedor y presentación

Es la separación que hace el sistema mantenible por una sola persona. El contenedor obtiene y
decide; la presentación recibe y muestra.

| | Contenedor | Presentación |
|---|---|---|
| Obtiene datos | Sí, con TanStack Query | **Nunca** |
| Ejecuta mutaciones | Sí | Nunca. Recibe callbacks |
| Conoce rutas y navegación | Sí | Solo por callback |
| Evalúa permisos | Sí | Recibe banderas ya resueltas |
| Tiene pruebas | De integración con red simulada | De componente, puramente con props |
| Se puede ver en Storybook | No | Sí |

**Incorrecto.** La presentación pide datos, así que no se puede probar ni documentar sin red:

```tsx
function StudentAccountCard({ studentId }: { studentId: string }) {
  const { data } = useQuery(studentAccountQuery(studentId)); // mal
  return <Card>{data?.balance}</Card>;
}
```

**Correcto.** El contenedor obtiene, la presentación solo recibe:

```tsx
// contenedor
function StudentAccountCardContainer({ studentId }: { studentId: string }) {
  const { data, isPending, error, refetch } = useQuery(studentAccountQuery(studentId));

  if (isPending) return <StudentAccountCardSkeleton />;
  if (error) return <ErrorState onRetry={refetch} />;

  return <StudentAccountCard account={data} onRegisterPayment={/* ... */} />;
}

// presentacion: pura, testeable, documentable
function StudentAccountCard({ account, onRegisterPayment }: StudentAccountCardProps) {
  return (
    <Card>
      <MoneyAmount value={account.balance} emphasis="strong" />
      <Button onClick={onRegisterPayment}>Registrar pago</Button>
    </Card>
  );
}
```

## 3. Dónde vive cada componente

```
packages/ui/src/
├── tokens/          # variables CSS y preset de Tailwind
├── atoms/           # sin dominio
├── molecules/       # genericas sin dominio
├── domain/          # MoneyAmount, StatusBadge, LedgerTimeline, PaymentReceipt
├── layouts/         # plantillas compartidas
└── hooks/           # utilidades de interfaz sin dominio

apps/admin-web/src/features/<modulo>/components/   # especifico del panel
apps/portal-web/src/features/<modulo>/components/  # especifico del portal
```

**Criterio para promover a `packages/ui`:** un componente sube cuando lo usan las dos aplicaciones,
o cuando lo usan tres o más módulos del panel. Antes de eso vive donde se usa. Promover demasiado
pronto produce componentes con siete props booleanas que nadie entiende.

## 4. Inventario de componentes

Prioridad: **P0** necesario para la fase F0 a F4, **P1** para F5 a F8, **P2** posterior.

### Átomos

| Componente | Prioridad | Aplicación | Base shadcn | Notas |
|---|---|---|---|---|
| `Button` | P0 | Ambas | `button` | Variantes primaria, secundaria, sutil, destructiva, enlace |
| `IconButton` | P0 | Ambas | `button` | Requiere etiqueta accesible obligatoria |
| `Input` | P0 | Ambas | `input` | |
| `Textarea` | P0 | Ambas | `textarea` | |
| `Label` | P0 | Ambas | `label` | |
| `Checkbox` | P0 | Ambas | `checkbox` | |
| `RadioGroup` | P0 | Ambas | `radio-group` | |
| `Switch` | P1 | Panel | `switch` | Solo para preferencias, nunca para acciones financieras |
| `Badge` | P0 | Ambas | `badge` | |
| `Avatar` | P1 | Ambas | `avatar` | |
| `Spinner` | P0 | Ambas | propio | Solo para acciones en curso, nunca para carga de contenido |
| `Separator` | P0 | Ambas | `separator` | |
| `Skeleton` | P0 | Ambas | `skeleton` | Para carga de contenido |

### Moléculas

| Componente | Prioridad | Aplicación | Base shadcn | Notas |
|---|---|---|---|---|
| `FormField` | P0 | Ambas | `form` | Etiqueta, control, descripción y error como una unidad |
| `FormError` | P0 | Ambas | propio | |
| `FormErrorSummary` | P0 | Ambas | propio | Resumen al inicio del formulario, con enlaces al campo |
| `CurrencyInput` | P0 | Panel | propio | Entrada de importe. Nunca acepta coma flotante |
| `DateInput` | P0 | Ambas | `calendar` + `popover` | Entrada manual admitida además del calendario |
| `Select` | P0 | Ambas | `select` | Hasta unas quince opciones |
| `Combobox` | P0 | Panel | `command` + `popover` | Con búsqueda en servidor para estudiantes y conceptos |
| `SearchInput` | P0 | Ambas | propio | Con retardo de escritura y botón de limpiar |
| `Tooltip` | P0 | Ambas | `tooltip` | Nunca portador único de información esencial |
| `Popover` | P0 | Ambas | `popover` | |
| `DropdownMenu` | P0 | Panel | `dropdown-menu` | |
| `StatCard` | P1 | Panel | propio | Indicador con variación respecto al período anterior |
| `Breadcrumb` | P0 | Panel | `breadcrumb` | |
| `Pagination` | P0 | Ambas | propio | |
| `EmptyState` | P0 | Ambas | propio | Siempre con acción principal |
| `ErrorState` | P0 | Ambas | propio | Siempre con reintento |
| `Alert` | P0 | Ambas | `alert` | En línea, contextual |
| `Banner` | P0 | Ambas | propio | Persistente, a nivel de página |
| `Toast` | P0 | Ambas | `sonner` | Transitorio |

### Moléculas de dominio

| Componente | Prioridad | Aplicación | Notas |
|---|---|---|---|
| `MoneyAmount` | P0 | Ambas | El componente más usado del sistema. Ver especificación |
| `StatusBadge` | P0 | Ambas | Estado financiero con color, icono y texto |
| `DateDisplay` | P0 | Ambas | Formato regional consistente, con título accesible en formato largo |
| `StudentCard` | P0 | Ambas | Identificación compacta de estudiante |
| `FiscalDocumentNumber` | P1 | Panel | Formato del número de documento fiscal |

### Organismos

| Componente | Prioridad | Aplicación | Notas |
|---|---|---|---|
| `DataTable` | P0 | Panel | El organismo más complejo. Ver especificación |
| `DataTableToolbar` | P0 | Panel | Búsqueda, filtros, columnas, densidad, exportación |
| `DataTableFilters` | P0 | Panel | Filtros por facetas sincronizados con la URL |
| `DataTablePagination` | P0 | Panel | Paginación en servidor |
| `ColumnVisibility` | P1 | Panel | |
| `Dialog` | P0 | Ambas | |
| `ConfirmDialog` | P0 | Ambas | Confirmación estándar |
| `DestructiveConfirmDialog` | P0 | Panel | Exige escribir una palabra |
| `Sheet` | P1 | Ambas | Panel lateral para detalle sin perder contexto |
| `Tabs` | P1 | Ambas | |
| `Accordion` | P1 | Portal | Ideal para el estado de cuenta en móvil |
| `Card` | P0 | Ambas | |
| `Sidebar` | P0 | Panel | Navegación colapsable |
| `Topbar` | P0 | Ambas | |
| `PageHeader` | P0 | Ambas | Título, migas, acciones |
| `Stepper` | P0 | Panel | Asistente de registro de pago |
| `Timeline` | P1 | Ambas | |
| `LedgerTimeline` | P0 | Ambas | Estado de cuenta como historial. Ver especificación |
| `AccountSummary` | P0 | Ambas | Resumen de saldo del estudiante |
| `PaymentReceipt` | P0 | Panel | Recibo imprimible |
| `ChartContainer` | P1 | Panel | Envoltorio de Recharts con leyenda y estados |
| `FileUpload` | P1 | Ambas | Comprobantes de transferencia y adjuntos |
| `PrintLayout` | P0 | Panel | Diseño de impresión sin navegación |

## 5. Especificación de los componentes de dominio

### 5.1 `MoneyAmount`

El componente más usado y el que más daño hace si está mal. Un importe ambiguo en un sistema
financiero no es un problema estético.

```typescript
interface MoneyAmountProps {
  /** Importe con moneda. Nunca un number suelto. */
  value: { amountMinor: string; currency: string };
  /** Peso visual. 'strong' para saldos y totales. */
  emphasis?: 'normal' | 'strong' | 'muted';
  size?: 'sm' | 'md' | 'lg' | 'xl';
  /** Colorea segun signo. Solo para variaciones, nunca para saldos. */
  colorBySign?: boolean;
  /** Muestra el signo mas en positivos. Para variaciones. */
  showPositiveSign?: boolean;
  /** Oculta el codigo o simbolo de moneda. Solo dentro de una tabla que ya lo declara. */
  hideCurrency?: boolean;
}
```

Reglas obligatorias:

| Regla | Razón |
|---|---|
| Siempre dos decimales, incluso en importes redondos | `1,500` frente a `1,500.00` se lee distinto bajo presión |
| Numeración tabular siempre | Las columnas de importes deben alinearse dígito con dígito |
| Alineado a la derecha en tablas | Es como se comparan los números |
| El cero se muestra como `0.00`, nunca como vacío ni como guión | Un vacío no distingue entre cero y desconocido |
| Los negativos usan signo menos y paréntesis en contexto contable | El signo solo puede pasar desapercibido |
| El formato viene de `Intl.NumberFormat` | Nunca concatenación manual |
| `colorBySign` solo en variaciones | Un saldo en rojo por ser negativo confunde: un saldo a favor es bueno |

```tsx
<MoneyAmount value={{ amountMinor: '320000', currency: 'HNL' }} emphasis="strong" size="lg" />
// L 3,200.00
```

### 5.2 `StatusBadge`

```typescript
type FinancialStatus =
  | 'paid' | 'pending' | 'partial' | 'overdue' | 'void' | 'promised' | 'refunded';

interface StatusBadgeProps {
  status: FinancialStatus;
  /** 'soft' por defecto. 'solid' solo para el estado dominante de una pagina. */
  variant?: 'soft' | 'solid' | 'outline';
  size?: 'sm' | 'md';
  /** Solo en tablas muy densas. Mantiene el texto accesible. */
  iconOnly?: boolean;
}
```

**Regla inviolable:** el estado siempre se comunica con **color, icono y texto** a la vez. Con
`iconOnly`, el texto sigue disponible para lectores de pantalla y en el título accesible. El color
nunca es el único portador.

| Estado | Token | Icono | Etiqueta |
|---|---|---|---|
| `paid` | `--color-status-paid` | `CheckCircle2` | Pagado |
| `pending` | `--color-status-pending` | `Clock3` | Pendiente |
| `partial` | `--color-status-partial` | `CircleDashed` | Parcial |
| `overdue` | `--color-status-overdue` | `AlertTriangle` | Vencido |
| `void` | `--color-status-void` | `Ban` | Anulado |
| `promised` | `--color-status-promised` | `CalendarClock` | En promesa |
| `refunded` | `--color-status-refunded` | `RotateCcw` | Reembolsado |

### 5.3 `DataTable`

El organismo que sostiene la mayor parte del panel. Construido sobre TanStack Table con **estado en
el servidor**, no en el cliente.

```typescript
interface DataTableProps<TData> {
  columns: ColumnDef<TData>[];
  data: TData[];
  /** Total de filas en el servidor, no las cargadas. */
  totalCount: number;
  /** Estado controlado, sincronizado con los parametros de busqueda de la ruta. */
  pagination: { pageIndex: number; pageSize: number };
  sorting: SortingState;
  columnFilters: ColumnFiltersState;
  onPaginationChange: OnChangeFn<PaginationState>;
  onSortingChange: OnChangeFn<SortingState>;
  onColumnFiltersChange: OnChangeFn<ColumnFiltersState>;

  isPending: boolean;
  isFetching: boolean;
  error?: Error;
  onRetry?: () => void;

  enableRowSelection?: boolean;
  bulkActions?: BulkAction<TData>[];
  exportFormats?: ('csv' | 'xlsx' | 'pdf')[];
  onExport?: (format: string, scope: 'page' | 'all') => void;

  density?: 'comfortable' | 'compact';
  stickyColumns?: number;
  savedViews?: SavedView[];
  emptyState: React.ReactNode;
}
```

Reglas obligatorias:

1. **Paginación, orden y filtrado siempre en el servidor.** Traer el conjunto completo para
   filtrarlo en el navegador es inaceptable con miles de pagos, y además expone al cliente datos
   que no debería recibir.
2. **El estado vive en los parámetros de búsqueda de la ruta.** Así una vista filtrada es
   compartible por URL, que es lo que contabilidad va a querer hacer.
3. **La exportación declara su alcance.** El botón pregunta si exporta la página visible o el
   resultado completo del filtro. Exportar en silencio solo lo visible produce reportes incorrectos.
4. **Las acciones por lote muestran el número exacto** de elementos afectados antes de ejecutarse.
5. **Distingue `isPending` de `isFetching`.** La primera carga muestra esqueleto; una recarga por
   cambio de filtro atenúa la tabla y mantiene el contenido, para que la pantalla no salte.
6. **Comportamiento responsivo:** por debajo del punto de quiebre medio, la tabla se convierte en
   una lista de tarjetas con los tres campos más importantes. Nunca desplazamiento horizontal en
   móvil.
7. **Selectores accesibles:** encabezados con `scope`, orden anunciado con `aria-sort`, y los
   cambios de resultado anunciados en una región activa.

### 5.4 `LedgerTimeline`

Presenta el estado de cuenta como lo que es: un historial cronológico con saldo acumulado. Es la
pantalla que un padre mira cuando reclama, así que debe explicarse sola.

```typescript
interface LedgerTimelineProps {
  entries: LedgerEntryView[];
  /** Saldo antes del primer movimiento mostrado. */
  openingBalance: Money;
  /** 'desc' por defecto: lo mas reciente primero. */
  order?: 'asc' | 'desc';
  groupBy?: 'month' | 'academic-period' | 'none';
  onEntryClick?: (entryId: string) => void;
}
```

Cada movimiento muestra fecha, descripción, tipo con su icono, importe con signo, saldo acumulado
después del movimiento, y referencia al documento fiscal si existe. Los movimientos reversados se
muestran tachados con su estado anulado, **nunca se ocultan**: ocultar un reverso es exactamente lo
que hace que un padre desconfíe del sistema.

### 5.5 `ConfirmDialog` frente a `DestructiveConfirmDialog`

| Uso | Componente | Fricción |
|---|---|---|
| Salir de un formulario con cambios | `ConfirmDialog` | Confirmar o cancelar |
| Cerrar una sesión de caja | `ConfirmDialog` con resumen | Confirmar tras revisar el resumen |
| Anular una factura | `DestructiveConfirmDialog` | Motivo obligatorio más escribir `ANULAR` |
| Reversar un pago registrado | `DestructiveConfirmDialog` | Motivo obligatorio más escribir `REVERSAR` |
| Eliminar un concepto de pago sin uso | `ConfirmDialog` destructivo | Confirmar |

```typescript
interface DestructiveConfirmDialogProps {
  title: string;
  /** Que va a pasar exactamente. Sin eufemismos. */
  consequence: string;
  /** Palabra que la persona debe escribir. En mayusculas, en espanol. */
  confirmationWord: string;
  requireReason?: boolean;
  reasonLabel?: string;
  onConfirm: (reason?: string) => Promise<void>;
}
```

El texto de consecuencia dice qué ocurre, no qué se hace: no *anular la factura*, sino *se emitirá
una anulación registrada ante la autoridad tributaria y el correlativo no podrá reutilizarse*.

### 5.6 `PaymentReceipt` y `StatCard`

`PaymentReceipt` presenta el comprobante inmediatamente después de registrar un pago, con estudiante,
encargado que pagó, cargos aplicados con su desglose, método de pago, cajero, sesión de caja y
número de documento fiscal. Tiene versión en pantalla y versión de impresión sin navegación.

`StatCard` muestra un indicador del dashboard con su valor, su etiqueta, y la variación respecto al
período anterior. La variación usa `MoneyAmount` con `colorBySign` y `showPositiveSign`, porque ahí
sí el signo tiene lectura de bueno o malo.

## 6. Estados obligatorios de todo componente

Ningún componente se considera terminado sin los ocho.

| Estado | Token | Regla |
|---|---|---|
| Por defecto | Semánticos base | |
| Cursor encima | Variante `-hover` | Nunca es el único indicador de interactividad |
| Foco | `--color-border-focus` | Anillo visible obligatorio. Nunca `outline: none` sin reemplazo |
| Activo | Variante `-active` | |
| Deshabilitado | Opacidad y cursor no permitido | Requiere explicación de por qué está deshabilitado |
| Cargando | `Spinner` o `Skeleton` | Esqueleto para contenido, indicador giratorio para acciones |
| Error | `--color-status-overdue` | Con mensaje asociado por identificador accesible |
| Solo lectura | `--color-text-muted` | Distinguible de deshabilitado: se puede seleccionar y copiar |

Distinguir deshabilitado de solo lectura importa. Un auditor ve importes en solo lectura y necesita
copiarlos; un campo deshabilitado no lo permite y genera fricción sin motivo.

## 7. Cuándo NO crear un componente nuevo

Antes de crear uno, responde estas preguntas. Si alguna se responde que sí, no lo crees.

- ¿Existe uno que resuelve el caso con una variante razonable?
- ¿Se usa en un solo lugar y no hay indicio de un segundo?
- ¿La diferencia con uno existente es solo de espaciado o de color? Eso es una variante de token.
- ¿Va a necesitar más de cinco props para cubrir sus usos? Probablemente son dos componentes.
- ¿Es una composición trivial de dos existentes? Compón en el lugar de uso.

Un sistema de diseño con doscientos componentes es un sistema que nadie usa: la búsqueda cuesta más
que reescribir. Se prefiere pocos componentes con buenas variantes.

## 8. Documentación en Storybook

Cada componente de `packages/ui` necesita como mínimo:

1. Historia por defecto con las props típicas.
2. Una historia por variante.
3. Historias de los estados de carga, vacío y error cuando apliquen.
4. Historia de caso límite: texto largo, importe grande, cero, valor nulo.
5. Verificación de accesibilidad con el complemento de axe, sin violaciones.

Un componente sin historia no se puede revisar visualmente ni comparar entre versiones, y con un
solo desarrollador Storybook es el sustituto más cercano a una revisión de diseño.

## 9. Documentos relacionados

- [00-principios-de-diseno.md](00-principios-de-diseno.md)
- [01-tokens-de-diseno.md](01-tokens-de-diseno.md)
- [03-accesibilidad.md](03-accesibilidad.md)
- [04-patrones-de-interaccion.md](04-patrones-de-interaccion.md)
- `.claude/skills/confia-ui-screen-recipe/SKILL.md`
