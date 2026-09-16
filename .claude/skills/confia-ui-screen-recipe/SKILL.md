---
name: confia-ui-screen-recipe
description: Receta para construir una pantalla de CONFIA. Se dispara al crear una pantalla, página, listado, formulario o diálogo en el panel administrativo o en el portal de encargados.
---

# Receta de pantalla de CONFIA

Sigue estos pasos en orden. Saltar el paso 1 es la causa más común de una pantalla que hay que
rehacer después de construida.

## 1. Qué leer antes de empezar

1. `docs/ui-ux/00-principios-de-diseno.md`: responde primero, por escrito, cuál es la **pregunta
   principal** de la pantalla nueva (ver su sección 3, principio 3) y cómo responde, o deja
   evidente dónde se responden, las tres preguntas del sistema: ¿cuánto se debe?, ¿qué se pagó?,
   ¿qué sigue?
2. `docs/ui-ux/01-tokens-de-diseno.md`: color, tipografía, espaciado y contraste verificados. No
   se inventan valores nuevos fuera de los tokens.
3. `docs/ui-ux/02-sistema-de-diseno-y-componentes.md`: inventario de componentes existentes en
   `packages/ui` antes de crear uno nuevo.
4. `docs/ui-ux/03-accesibilidad.md`: criterios AA aplicados a este producto.
5. `docs/ui-ux/04-patrones-de-interaccion.md`: navegación, listados, formularios y errores ya
   resueltos como patrón. Una pantalla nueva reutiliza el patrón existente en vez de inventar uno
   equivalente.
6. Decide el perfil de densidad correcto según si la pantalla vive en `admin-web` (densa, escritorio
   primero) o en `portal-web` (amplia, móvil primero, real). Ver
   `docs/ui-ux/00-principios-de-diseno.md` sección 5.

## 2. Estructura de archivos de una ruta con TanStack Router

```
apps/<admin-web|portal-web>/src/routes/
└── students/
    └── $studentId/
        └── account-summary.tsx
```

Los parámetros de búsqueda (filtros, orden, página) se declaran tipados con Zod en la propia ruta,
nunca leídos a mano desde `window.location`.

```tsx
// routes/students/$studentId/account-summary.tsx
import { createFileRoute } from '@tanstack/react-router';
import { z } from 'zod';

const searchSchema = z.object({
  from: z.string().date().optional(),
  status: z.enum(['ALL', 'OVERDUE', 'UPCOMING']).default('ALL'),
  cursor: z.string().optional(),
});

export const Route = createFileRoute('/students/$studentId/account-summary')({
  validateSearch: searchSchema,
  component: AccountSummaryPage,
});
```

Filtros persistidos en la URL son un requisito de los perfiles de contadora y auditor (ver
`docs/ui-ux/00-principios-de-diseno.md` sección 4.2): deben poder compartir y restaurar el mismo
filtro al volver a la vista.

## 3. Carga de datos con TanStack Query

Claves de consulta jerárquicas, de lo general a lo específico, para poder invalidar por nivel:

```ts
// features/students/students.keys.ts
export const studentKeys = {
  all: ['students'] as const,
  detail: (studentId: string) => [...studentKeys.all, studentId] as const,
  accountSummary: (studentId: string, filters: AccountSummaryFilters) =>
    [...studentKeys.detail(studentId), 'account-summary', filters] as const,
};
```

```tsx
// features/students/use-account-summary.query.ts
export function useAccountSummary(studentId: string, filters: AccountSummaryFilters) {
  return useQuery({
    queryKey: studentKeys.accountSummary(studentId, filters),
    queryFn: () => api.students.getAccountSummary(studentId, filters),
  });
}
```

**No dupliques datos de servidor en estado global.** El resultado de la consulta es la única fuente
de verdad en memoria del cliente. Ver `docs/01-arquitectura.md` sección "Frontend".

## 4. Tabla con paginación en servidor con TanStack Table

```tsx
// features/payments/payments-table.tsx
const columns: ColumnDef<PaymentRow>[] = [
  { accessorKey: 'occurredAt', header: 'Fecha' },
  { accessorKey: 'studentName', header: 'Estudiante' },
  {
    accessorKey: 'amount',
    header: 'Monto',
    cell: ({ row }) => <MoneyAmount value={row.original.amount} />,
    meta: { align: 'right' },
  },
  { accessorKey: 'status', header: 'Estado', cell: ({ row }) => <StatusBadge value={row.original.status} /> },
];

const table = useReactTable({
  data: page?.data ?? [],
  columns,
  manualPagination: true,
  manualSorting: true,
  manualFiltering: true,
  getCoreRowModel: getCoreRowModel(),
});
```

Orden, filtrado y paginación se resuelven en el servidor mediante los parámetros de búsqueda de la
ruta (paso 2) y el contrato de cursor de `confia-api-conventions`. **Nunca** se trae el conjunto
completo para paginar u ordenar en el cliente: es un antipatrón prohibido explícitamente en
`docs/ui-ux/00-principios-de-diseno.md` sección 6.

## 5. Formulario con TanStack Form y el esquema Zod generado

```tsx
// features/payments/register-payment.form.tsx
import { RegisterPaymentRequestSchema } from '@confia/contracts';

function RegisterPaymentForm({ studentId }: { studentId: string }) {
  const form = useForm({
    defaultValues: { studentId, amount: '', currency: 'HNL', method: 'CASH' as const },
    validators: { onChange: RegisterPaymentRequestSchema },
    onSubmit: async ({ value }) => registerPayment.mutateAsync(value),
  });

  return (
    <form onSubmit={(e) => { e.preventDefault(); form.handleSubmit(); }}>
      <form.Field name="amount">
        {(field) => <AmountInput field={field} currency="HNL" label="Monto a cobrar" />}
      </form.Field>
      {/* ... */}
    </form>
  );
}
```

`RegisterPaymentRequestSchema` es el esquema Zod que orval genera en `packages/contracts` a partir
del OpenAPI del backend, de modo que refleja el mismo contrato que el backend valida con Jakarta
Bean Validation. No existe una segunda definición escrita a mano en el formulario, y la validación
del cliente nunca sustituye la del servidor. El nombre exacto de los esquemas generados depende de
la configuración de orval que se fija en F0. Ver `confia-security-checklist` sección 3.

## 6. Separación contenedor y presentación

Un componente contenedor obtiene datos y estado; el componente de presentación solo recibe props y
renderiza. **La presentación nunca hace peticiones.**

```tsx
// container: features/students/account-summary.container.tsx
export function AccountSummaryContainer({ studentId }: { studentId: string }) {
  const { data, isPending, isError, error } = useAccountSummary(studentId, filters);
  if (isPending) return <AccountSummarySkeleton />;
  if (isError) return <ErrorState error={error} onRetry={refetch} />;
  if (data.charges.length === 0) return <EmptyState message="Sin cargos en el período" />;
  return <AccountSummaryView data={data} />;
}

// presentación: packages/ui/organisms/account-summary-view.tsx
export function AccountSummaryView({ data }: { data: AccountSummaryDto }) {
  return ( /* solo JSX, sin useQuery, sin fetch */ );
}
```

## 7. Los cuatro estados obligatorios

Todo componente que depende de datos remotos declara, de forma explícita y con su propia prueba de
componente, estos cuatro estados. Ninguno se puede omitir ni fusionar en uno solo:

1. **Carga.** Esqueleto que respeta la forma final del contenido, nunca un indicador giratorio de
   página completa (antipatrón prohibido). Ver `docs/ui-ux/00-principios-de-diseno.md` principio 5.
2. **Vacío.** Mensaje específico al contexto más, cuando exista, la acción primaria que resuelve el
   vacío. "Sin resultados" a secas no es un estado vacío bien resuelto.
3. **Error.** Mensaje sin jerga técnica en el portal, con código de referencia en el panel, y
   acción de reintento cuando aplica.
4. **Éxito.** El contenido real, incluida la variante con datos parciales o con un único elemento.

```tsx
// INCORRECTO: colapsa carga y vacío en el mismo `data.length === 0`
if (!data || data.length === 0) return <p>No hay datos</p>;

// CORRECTO: los cuatro estados son ramas explícitas y distinguibles
if (isPending) return <Skeleton />;
if (isError) return <ErrorState error={error} />;
if (data.length === 0) return <EmptyState />;
return <DataView data={data} />;
```

## 8. El componente `MoneyAmount`

Todo importe visible en la interfaz se renderiza con `MoneyAmount` de `packages/ui`. Nunca se
interpola un importe en una plantilla de texto ni se formatea a mano.

```tsx
// INCORRECTO
<span>{`L ${Number(dto.amount).toFixed(2)}`}</span>

// CORRECTO
<MoneyAmount value={dto} />
```

`MoneyAmount` recibe el DTO `{ amount: string, currency: string }` tal como lo entrega el servidor,
ya redondeado a la escala menor de la moneda, y lo formatea con `Intl.NumberFormat`. No redondea,
no calcula y no convierte a `number`.

`MoneyAmount` garantiza las cinco condiciones de `docs/ui-ux/00-principios-de-diseno.md`
principio 2: símbolo de moneda explícito, separador de miles con dos decimales siempre visibles,
alineación a la derecha con numeración tabular en tablas, declaración de la naturaleza del importe
(cargo, pago, descuento, mora, saldo) y, cuando es un saldo derivado, su fecha de corte.

## 9. Invalidación de caché tras una escritura financiera

Toda mutación que registre un pago, emita un documento fiscal, aplique un reverso o modifique el
libro mayor invalida las consultas de TanStack Query afectadas, usando el nivel jerárquico correcto
de la clave (paso 3), nunca solo la clave exacta de la vista actual.

```ts
// features/payments/use-register-payment.mutation.ts
export function useRegisterPayment(studentId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: api.payments.register,
    onSuccess: () => {
      // invalida todo lo derivado del estudiante: saldo, historial, listados,
      // no solo la consulta puntual que disparó la mutación.
      queryClient.invalidateQueries({ queryKey: studentKeys.detail(studentId) });
    },
  });
}
```

## 10. Lista de verificación de accesibilidad antes de terminar

- [ ] Contraste mínimo 4.5:1 en texto y 3:1 en controles y bordes funcionales, usando los valores
      de `docs/ui-ux/01-tokens-de-diseno.md`.
- [ ] Ningún estado financiero se comunica solo por color. Color, icono y texto juntos.
- [ ] Todo control es operable con teclado, con foco visible de al menos 2 px.
- [ ] Objetivos táctiles de al menos 44×44 px en el portal, 32×32 px con 8 px de separación en
      tablas densas del panel.
- [ ] `prefers-reduced-motion` respetado en toda transición.
- [ ] Zoom a 200 % sin pérdida de contenido ni desplazamiento horizontal.
- [ ] Ningún icono funcional sin `aria-label`, especialmente en filas repetidas de una tabla.
- [ ] axe-core no reporta violaciones críticas ni serias en la prueba de componente ni en la de
      extremo a extremo.
- [ ] La pantalla no depende de la posición del cursor (sin menús que solo abren con `hover`).

## 11. Antes de dar por terminado

- [ ] La pregunta principal de la pantalla está identificada y respondida visualmente de forma
      dominante.
- [ ] Los parámetros de búsqueda de la ruta están tipados con Zod, no leídos a mano.
- [ ] El estado de servidor vive solo en TanStack Query, sin duplicado en estado global.
- [ ] Tablas grandes usan paginación, filtro y orden en servidor.
- [ ] El formulario usa el esquema Zod generado en `packages/contracts` desde el OpenAPI del
      backend, sin una definición paralela escrita a mano.
- [ ] Existe separación clara entre contenedor y presentación.
- [ ] Los cuatro estados (carga, vacío, error, éxito) están implementados con prueba de componente.
- [ ] Todo importe usa `MoneyAmount`, nunca interpolación manual.
- [ ] Toda mutación financiera invalida la caché al nivel jerárquico correcto.
- [ ] La lista de verificación de accesibilidad de la sección 10 está completa.
