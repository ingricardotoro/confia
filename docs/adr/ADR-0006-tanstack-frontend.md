# ADR-0006: Suite TanStack como base del frontend

- **Estado:** Aceptado
- **Fecha:** 2026-09-10
- **Decisores:** Propietario del producto y arquitecto
- **Contexto técnico:** `apps/admin-web`, `apps/portal-web`, `packages/ui`, `packages/contracts` (tipos y esquemas Zod generados con orval desde el OpenAPI del backend), `packages/config` (configuración compartida de ESLint, Vitest y Tailwind), integración continua del monorepo.
- **Revisión:** 2026-09-14. Alineado con ADR-0013 (backend en Java con Spring Boot). La decisión no cambia; se actualiza la procedencia de los esquemas de `packages/contracts`, que pasan a generarse desde el OpenAPI del backend.

## Contexto y problema

CONFIA tiene dos aplicaciones de navegador con exigencias muy concretas que no son las de un CRUD
genérico:

1. **Reportes filtrados y compartibles por enlace.** Un cajero necesita enviarle al contador un
   enlace que abra exactamente el mismo estado de cuenta filtrado por institución, año lectivo y
   rango de fechas. Si el filtro vive en estado de componente y no en la URL, ese enlace no existe y
   la conversación pasa a ser "filtra por tal cosa, luego por tal otra".
2. **Estado de servidor que cambia bajo los pies.** El saldo de un estudiante cambia cuando el
   cajero cobra, cuando el encargado paga por la pasarela y cuando el motor de devengo genera el
   cargo del mes. Ese dato no puede vivir duplicado en un almacén global del cliente, porque en el
   momento en que se duplica aparece la pregunta de cuál de las dos copias es la verdadera.
3. **Listados que nunca caben en el navegador.** El listado completo de estudiantes de una
   institución, o el de transacciones de un año lectivo, no se trae al cliente para filtrarlo ahí
   (`CLAUDE.md`, sección Frontend). La paginación, el orden y el filtrado ocurren en el servidor, y
   la tabla del cliente tiene que estar diseñada para ese modo.
4. **Vistas muy largas.** Un estado de cuenta de un año lectivo completo y un libro de ventas mensual
   tienen miles de filas. Renderizarlas todas cuelga la pestaña en el equipo modesto de una
   recepción.
5. **Un solo desarrollador.** Cada pieza que se adopta hay que aprenderla, actualizarla y depurarla.
   La coherencia entre piezas vale tanto como la calidad individual de cada una.

Existe además un dato de contexto que condiciona la decisión: **`docs/01-arquitectura.md`, sección
3.3, declara que el uso de la suite TanStack es una decisión explícita del propietario del producto
y queda adoptada.** Este ADR no reabre esa decisión. Lo que hace es dejar registrado qué
alternativas eran reales, qué ventajas técnicas concretas sostienen la elección, qué desventajas se
aceptan, y sobre todo **qué convenciones obligatorias hacen que la elección rinda**, porque adoptar
TanStack Query y seguir duplicando el saldo en un almacén global no resuelve nada.

Si no se decide, el resultado por defecto es una mezcla: enrutado con una librería, estado de
servidor con otra, filtros en estado local, tablas hechas a mano y una copia del saldo en un almacén
global "por si acaso". Esa mezcla es la que produce que el portal muestre un número y ventanilla
otro.

## Factores de decisión

| Factor | Peso | Justificación |
|---|---|---|
| Parámetros de búsqueda tipados y validados como estado de primera clase | Muy alto | Los reportes filtrados y compartibles por URL son un requerimiento explícito, no un adorno. |
| Una sola fuente de verdad para el estado de servidor | Muy alto | Una segunda copia del saldo en el cliente es la misma clase de error que una segunda base de datos (ADR-0003). |
| Soporte de primera clase para paginación, orden y filtrado en servidor | Muy alto | Traer el conjunto completo al navegador está prohibido por `CLAUDE.md`. |
| Requerimiento explícito del propietario del producto | Muy alto | Decisión ya tomada y registrada en la fuente de verdad de la arquitectura. |
| Coherencia entre las piezas del ecosistema | Alto | Un solo desarrollador se beneficia de un modelo mental compartido entre enrutado, datos, tablas y formularios. |
| Rendimiento con listados largos sin trabajo propio | Alto | Estados de cuenta y libros de ventas de miles de filas. |
| Madurez, comunidad y estabilidad de la API | Alto | Es el factor donde la elección es más débil, y hay que decirlo. |
| Reutilización de conocimiento hacia React Native | Medio | La app móvil futura comparte TanStack Query y los contratos, no el enrutado ni las tablas. |
| Tamaño del paquete final entregado al navegador | Medio | El portal se abre desde teléfonos con red móvil variable. |

## Opciones consideradas

### Opción A: React Router más una librería de estado de servidor más simple (SWR)

Enrutado con React Router en modo declarativo, obtención de datos con SWR, tablas y virtualización
resueltas con librerías sueltas o con implementación propia.

**Ventajas.**

- **React Router es la opción con más madurez, más adopción y más respuestas disponibles** ante
  cualquier problema. Para un desarrollador solo, la probabilidad de encontrar el caso ya resuelto es
  un factor real.
- SWR es diminuto, tiene una superficie de API muy pequeña y se aprende en una tarde.
- Menor peso combinado en el paquete final.
- Riesgo de ruptura por actualización menor considerablemente más bajo.

**Desventajas.**

- **Los parámetros de búsqueda no son estado tipado.** React Router entrega `URLSearchParams`, es
  decir, cadenas. Convertir `?from=2026-01-01&to=2026-03-31&institution=...&page=3` en un objeto
  validado, con valores por defecto y con serialización estable, es código propio que hay que
  escribir, tipar y probar en cada pantalla de reporte. Es exactamente el trabajo que el
  requerimiento uno exige y que TanStack Router resuelve de fábrica.
- SWR carece de invalidación por prefijo de clave jerárquica. Invalidar "todo lo que cuelga del
  estudiante X tras registrar un pago" pasa a ser una lista manual de claves que alguien tiene que
  mantener sincronizada. Ese mantenimiento manual falla, y cuando falla el cajero ve un saldo viejo
  después de cobrar.
- Sin herramientas equivalentes de inspección del caché, la depuración de "por qué esta pantalla
  muestra un dato viejo" se vuelve trabajo de conjeturas.
- Tablas y virtualización quedan como piezas sueltas de proveedores distintos, con modelos mentales
  distintos, o como implementación propia que hay que mantener.
- La ausencia de rutas tipadas devuelve los errores de navegación al tiempo de ejecución: un enlace
  a una ruta renombrada compila sin problema y falla en producción.

### Opción B: Next.js con Server Components

Framework completo con enrutado por sistema de archivos, componentes de servidor, acciones de
servidor y obtención de datos en el servidor.

**Ventajas.**

- Menos estado de servidor en el cliente por construcción: buena parte de los datos se resuelve en el
  servidor y llega renderizada.
- Excelente rendimiento de primera carga y buen comportamiento en redes lentas, que importa para el
  portal en teléfonos.
- Enrutado, obtención de datos y agrupamiento vienen resueltos de fábrica.

**Desventajas.**

- **Introduce un servidor de aplicación adicional en la topología.** Hoy `admin-web` y `portal-web`
  son construcciones estáticas de Vite servidas por nginx (`docs/01-arquitectura.md`, sección 9). Con
  Next.js pasan a ser dos procesos Node.js más que desplegar, dimensionar, monitorear, parchear y
  reiniciar. Para un desarrollador solo eso es carga operativa directa a cambio de un beneficio que
  este sistema, que es una herramienta interna y un portal autenticado, no necesita: no hay
  indexación pública que optimizar.
- **Difumina la frontera de seguridad que ADR-0003 construyó.** El aislamiento del portal descansa en
  que el proceso público solo tiene cargado el módulo `portal` y se conecta con un rol de PostgreSQL
  mínimo. Con acciones de servidor y componentes de servidor aparece un segundo lugar donde se
  ejecuta lógica privilegiada y donde alguien puede, sin querer, acceder a datos con credenciales del
  servidor de renderizado. Se puede evitar, pero exige disciplina adicional permanente sobre una
  frontera que hoy es estructural.
- La app móvil futura no puede consumir componentes de servidor. Necesita la API REST de todos modos,
  de modo que parte de la lógica de acceso a datos existiría dos veces: una en el servidor de Next.js
  y otra en el contrato REST.
- El modelo de caché de Next.js ha cambiado de forma significativa entre versiones mayores, con
  comportamientos por defecto poco intuitivos en datos que deben ser siempre frescos, que es
  precisamente el caso del saldo.
- No resuelve tablas ni virtualización: se necesitarían igualmente librerías para eso.

### Opción C: Suite TanStack (Router, Query, Table, Form, Virtual)

**Ventajas.**

- **Parámetros de búsqueda como estado tipado y validado con Zod**, el mismo lenguaje de esquemas
  que usa `packages/contracts`, cuyos esquemas se generan desde el OpenAPI del backend.
- **Invalidación por prefijo de clave jerárquica**, que es lo que hace manejable la propagación de un
  cambio financiero.
- Tablas y virtualización sin interfaz de usuario impuesta, lo que encaja con `packages/ui` sobre
  shadcn/ui y Radix, donde el código de los componentes es propiedad del proyecto.
- Modelo mental coherente entre las cinco piezas, con un solo proveedor y un solo ciclo de
  actualización que seguir.
- Sale del navegador sin servidor propio: la construcción sigue siendo estática y servida por nginx,
  sin agregar procesos a la topología.
- TanStack Query funciona igual en React Native, de modo que la app móvil futura reutiliza la capa de
  datos y los contratos.
- Es la decisión explícita del propietario del producto, ya registrada en la fuente de verdad.

**Desventajas, declaradas sin adorno.**

- **TanStack Router tiene menos madurez y una comunidad bastante más pequeña que React Router.** Hay
  menos preguntas resueltas en foros, menos integraciones de terceros y menos ejemplos de casos
  raros. Cuando aparezca un problema poco común, es más probable tener que leer el código fuente de
  la librería que encontrar la respuesta escrita.
- **La API ha cambiado entre versiones menores.** Actualizar no es siempre un trámite, y para un
  desarrollador solo un día perdido en migrar una API de enrutado es un día que no se dedicó al
  dominio financiero.
- **Curva de aprendizaje real.** Quien viene de React Router o del enrutador por sistema de archivos
  de Next.js encuentra en TanStack Router un modelo distinto: árbol de rutas tipado, cargadores,
  validación de parámetros de búsqueda y generación de tipos. No es difícil, pero no es gratis.
- **Peso combinado.** Cinco librerías del mismo ecosistema pesan más que React Router más SWR. En el
  portal, que se abre desde teléfonos con red móvil variable, ese peso se paga en la primera carga.
- TanStack Form es la pieza más joven del conjunto y la que más probablemente necesite revisión.

**Cómo se mitiga cada desventaja.**

| Desventaja | Mitigación |
|---|---|
| Madurez y comunidad menores | Versiones fijadas de forma exacta en `package.json` (sin `^` ni `~`), con actualización deliberada y programada, nunca automática. Cada actualización pasa por la suite de extremo a extremo antes de fusionarse. |
| API que cambia entre versiones menores | Cobertura de Playwright sobre las rutas críticas (inicio de sesión con MFA, registro de pago, emisión de factura, anulación, cierre de caja, consulta del portal), de modo que una ruptura de API aparezca en integración continua y no en producción. |
| Curva de aprendizaje | Documentación interna de patrones propios en `docs/ui-ux/`: cómo se declara una ruta con parámetros de búsqueda validados, cómo se define una clave de consulta, cómo se conecta una tabla al servidor. Un patrón documentado se copia; uno no documentado se reinventa distinto cada vez. |
| Peso del paquete final | División de código por ruta, carga diferida de las vistas pesadas de reportes, y presupuesto de tamaño verificado en integración continua que falla si el paquete inicial del portal supera el umbral acordado. |
| Juventud de TanStack Form | Uso confinado a la capa de formularios de `packages/ui`, con los esquemas Zod generados en `packages/contracts` como fuente de validación. Si hubiera que sustituirlo, cambia la implementación del componente, no la validación ni los contratos. |

## Decisión

**Se adopta la suite TanStack completa (Router, Query, Table, Form y Virtual) como base del frontend
de `apps/admin-web` y `apps/portal-web`, junto con las convenciones obligatorias declaradas más
abajo.**

El criterio decisivo es doble y conviene ser preciso al respecto: **existe un requerimiento explícito
del propietario del producto**, ya registrado en `docs/01-arquitectura.md`, sección 3.3, **y ese
requerimiento coincide con la opción que mejor satisface los dos factores de peso muy alto propios
de este sistema**: parámetros de búsqueda tipados como estado de primera clase, e invalidación por
prefijo de clave jerárquica tras una escritura financiera. La opción A falla en ambos y obliga a
escribir código propio para lo primero y a mantener listas manuales de claves para lo segundo. La
opción B agrega dos procesos de servidor a la topología y difumina la frontera de seguridad que
ADR-0003 construyó, a cambio de un beneficio de renderizado que un panel administrativo autenticado
no necesita.

Se registra también lo que este ADR no dice: **no se afirma que TanStack Router sea más maduro que
React Router**, porque no lo es. Se acepta un riesgo de madurez concreto a cambio de un beneficio
concreto, y se declaran arriba las mitigaciones que hacen ese riesgo manejable.

### Qué resuelve cada pieza en este sistema

**TanStack Router: parámetros de búsqueda tipados y validados con Zod.**

El caso de uso es literal: el cajero abre el estado de cuenta filtrado por institución, año lectivo y
rango de fechas, y copia el enlace para enviárselo al contador. Ese enlace tiene que reabrir
exactamente el mismo estado, y los valores tienen que estar validados antes de llegar a la consulta.

```ts
// apps/admin-web/src/routes/reports/statement.tsx
import { createFileRoute } from '@tanstack/react-router';
import { z } from 'zod';

const statementSearchSchema = z.object({
  institutionId: z.string().uuid(),
  academicYearId: z.string().uuid(),
  from: z.string().date(),
  to: z.string().date(),
  page: z.number().int().min(1).default(1),
  pageSize: z.number().int().min(10).max(100).default(50),
});

export type StatementSearch = z.infer<typeof statementSearchSchema>;

export const Route = createFileRoute('/reports/statement')({
  validateSearch: statementSearchSchema,
  component: StatementReportPage,
});
```

Un enlace con `from` mal formado no llega a la consulta: falla en la validación de la ruta, con un
estado de error declarado. Y el objeto que recibe el componente está tipado, no es un mapa de
cadenas.

**TanStack Query: caché e invalidación del estado de servidor.**

El saldo de un estudiante, su estado de cuenta y el catálogo de conceptos y tarifas son estado de
servidor. Viven en el caché de Query y en ningún otro lugar del cliente. Cuando el cajero registra un
pago, la invalidación por prefijo propaga el cambio a todas las vistas que dependen de ese
estudiante sin que ninguna pantalla tenga que enterarse de las demás.

**TanStack Table: grids con paginación, orden y filtrado en servidor.**

La tabla no posee los datos ni los filtra. Recibe la página que el servidor devolvió y expone el
estado de paginación, orden y filtros, que se sincroniza con los parámetros de búsqueda de la ruta.
`manualPagination`, `manualSorting` y `manualFiltering` quedan activados de forma obligatoria en todo
grid de datos de servidor. Traer el listado completo de estudiantes o de transacciones al navegador
está prohibido (`CLAUDE.md`, sección Frontend).

**TanStack Virtual: virtualización de vistas largas.**

Un estado de cuenta de un año lectivo completo y un libro de ventas mensual tienen miles de filas
que el usuario recorre de forma continua, sin paginar. Ahí la virtualización de filas es lo que
mantiene la pestaña utilizable en el equipo modesto de una recepción.

**TanStack Form: formularios validados con los esquemas del contrato.**

Los esquemas Zod de `packages/contracts` se generan con orval desde el OpenAPI del backend, y las
restricciones de ese OpenAPI provienen de las anotaciones de Jakarta Bean Validation que validan el
borde del backend. El formulario y el borde del backend aplican así las mismas reglas desde una sola
fuente: el contrato del backend. Las restricciones que el OpenAPI no puede expresar, como las
validaciones entre campos o personalizadas, se aplican solo en el servidor, que conserva siempre la
autoridad (`CLAUDE.md`, regla 9). Un cambio de contrato regenera el paquete y rompe la verificación
de tipos de las aplicaciones web en la construcción, no la pantalla en producción
(`docs/01-arquitectura.md`, sección 7; ADR-0013).

### Convenciones obligatorias

**1. Claves de consulta jerárquicas.**

Toda clave se construye desde una fábrica central por módulo, nunca escrita a mano en un componente.
La jerarquía va de lo general a lo específico, de modo que un prefijo siempre pueda invalidar todo lo
que cuelga de él.

```ts
// apps/admin-web/src/features/students/query-keys.ts
export const studentKeys = {
  all: ['students'] as const,
  detail: (studentId: string) => ['students', studentId] as const,
  statement: (studentId: string) => ['students', studentId, 'statement'] as const,
  balance: (studentId: string) => ['students', studentId, 'balance'] as const,
} as const;

export const catalogKeys = {
  all: ['catalog'] as const,
  concepts: (institutionId: string) => ['catalog', 'concepts', institutionId] as const,
  fees: (institutionId: string, academicYearId: string) =>
    ['catalog', 'fees', institutionId, academicYearId] as const,
} as const;

export const cashboxKeys = {
  all: ['cashbox'] as const,
  openSession: (cashierId: string) => ['cashbox', 'session', cashierId, 'open'] as const,
  dashboard: (cashierId: string) => ['cashbox', 'dashboard', cashierId] as const,
} as const;
```

**2. Tiempo de obsolescencia por naturaleza del dato.**

No hay un `staleTime` global correcto. Se declara por tipo de dato, y la razón queda escrita:

| Dato | `staleTime` | Razón |
|---|---|---|
| Catálogo de conceptos y tarifas | 5 minutos | Cambia por configuración deliberada, no por operación diaria. Refrescarlo constantemente es tráfico sin beneficio. |
| Estado de cuenta de un estudiante | 0 | Cambia con cada pago, cada cargo y cada nota de crédito. Se mantiene fresco por invalidación activa tras la escritura, no por sondeo. |
| Saldo de un estudiante | 0 | Es el dato que nunca puede estar viejo. Un saldo obsoleto en pantalla produce un cobro incorrecto. |
| Indicadores del dashboard | 1 minuto, con `refetchOnWindowFocus: false` | Son agregaciones costosas. Refrescarlas cada vez que el cajero vuelve a la pestaña satura el servidor sin aportar precisión útil. |
| Datos de organización (grados, secciones, año lectivo) | 15 minutos | Cambian una vez por período académico. |

```ts
// apps/admin-web/src/features/students/use-student-balance.ts
import { useQuery } from '@tanstack/react-query';
import { studentKeys } from './query-keys';
import { fetchStudentBalance } from './api';

export function useStudentBalance(studentId: string) {
  return useQuery({
    queryKey: studentKeys.balance(studentId),
    queryFn: () => fetchStudentBalance(studentId),
    staleTime: 0,
  });
}
```

**3. Invalidación tras una escritura financiera.**

Registrar un pago invalida, con la clave jerárquica raíz correcta y de forma explícita: el saldo y el
estado de cuenta del estudiante afectado, el dashboard de la caja del cajero y la sesión de caja
abierta.

```ts
// apps/admin-web/src/features/payments/use-register-payment.ts
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { studentKeys, cashboxKeys } from '../students/query-keys';
import { registerPayment } from './api';

export function useRegisterPayment(studentId: string, cashierId: string) {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: registerPayment,
    onSuccess: async () => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: studentKeys.detail(studentId) }),
        queryClient.invalidateQueries({ queryKey: cashboxKeys.openSession(cashierId) }),
        queryClient.invalidateQueries({ queryKey: cashboxKeys.dashboard(cashierId) }),
      ]);
    },
  });
}
```

`studentKeys.detail(studentId)` invalida como prefijo tanto `statement` como `balance`, porque ambas
cuelgan de él. Ese es el motivo por el que la jerarquía se define de esa forma.

**Queda prohibido `queryClient.invalidateQueries()` sin argumentos.** Una invalidación global vuelve
a pedir todo lo que hay en pantalla, incluidos catálogos y agregaciones costosas, y esconde el hecho
de que el desarrollador no sabía exactamente qué había cambiado. En un sistema financiero, saber
exactamente qué cambió es parte del trabajo.

**No se usa actualización optimista en escrituras financieras.** Mostrar un saldo que todavía no está
asentado en el libro mayor es exactamente la clase de discrepancia que el sistema existe para
evitar. El botón queda en estado de envío hasta que el servidor confirma.

**4. Traducción de errores.**

El backend devuelve errores en formato Problem Details, RFC 9457 (`docs/01-arquitectura.md`, sección
7). La traducción de ese objeto a un mensaje presentable es responsabilidad de **una sola capa**: el
cliente HTTP compartido que usan ambas aplicaciones normaliza la respuesta a un error tipado con el
esquema de Problem Details del contrato, y un mapeador único en `packages/ui` traduce el campo
`type` a una clave de i18next.

La forma del esquema es la siguiente. En `packages/contracts` se genera desde el OpenAPI del backend
y no se edita a mano (ADR-0013):

```ts
// Shape of the Problem Details schema in packages/contracts.
// Generated by orval from the backend OpenAPI; never edited by hand.
import { z } from 'zod';

export const problemDetailsSchema = z.object({
  type: z.string(),
  title: z.string(),
  status: z.number().int(),
  detail: z.string().optional(),
  instance: z.string().optional(),
  traceId: z.string().optional(),
});

export type ProblemDetails = z.infer<typeof problemDetailsSchema>;
```

Reglas derivadas: **ningún componente lee `error.response.data` directamente**, ningún componente
construye un mensaje de error concatenando texto, y **nunca se muestra al usuario el campo `detail`
en crudo**. Se muestra la cadena traducida correspondiente al `type`, más el identificador de traza
como referencia para soporte. Un `type` sin traducción cae a un mensaje genérico y registra el hecho
para que se corrija.

**5. Prohibición de duplicar estado de servidor en estado global.**

El estado de servidor vive **únicamente** en el caché de TanStack Query. Queda prohibido copiar a
Zustand, a un contexto de React o a `localStorage` el saldo, el estado de cuenta, el catálogo, la
lista de estudiantes o cualquier otro dato proveniente de la API.

El estado global se reserva para **estado de interfaz puro**: tema claro u oscuro, barra lateral
plegada, densidad de tabla preferida, idioma seleccionado y borradores de formulario no enviados.
Ninguno de esos valores existe en el servidor y ninguno se puede quedar obsoleto respecto de él.

La razón es la misma que sostiene ADR-0003: **dos copias de un dato financiero son dos verdades, y
en cuanto divergen no hay forma barata de saber cuál es la correcta.** El caché de Query tiene un
modelo explícito de frescura, de invalidación y de reintento; una copia en un almacén global no
tiene ninguno de los tres.

## Consecuencias

**Positivas:**

- Los filtros de reporte son estado de URL validado, de modo que un enlace compartido reproduce
  exactamente la misma vista, y un enlace corrupto falla de forma controlada.
- Una escritura financiera propaga su efecto a todas las vistas dependientes con una invalidación por
  prefijo, sin listas manuales de claves.
- Existe una sola copia del estado de servidor en el cliente, con política de frescura explícita por
  tipo de dato.
- Los grids operan contra el servidor por construcción, lo que impide traer conjuntos completos al
  navegador.
- Las vistas largas se mantienen fluidas en equipos modestos.
- La construcción del frontend sigue siendo estática, sin agregar procesos a la topología de
  ADR-0012.
- La app móvil futura reutiliza la capa de datos y los contratos.
- Los errores del backend tienen un solo punto de traducción, lo que hace posible cambiar el texto de
  un mensaje sin buscarlo en veinte componentes.

**Negativas y costos aceptados:**

- **Se acepta trabajar sobre un enrutador menos maduro que la alternativa dominante.** Habrá casos
  para los que no exista respuesta escrita y haya que leer el código fuente de la librería.
- Las actualizaciones de versión menor pueden requerir trabajo de migración. Por eso las versiones se
  fijan de forma exacta y la actualización es deliberada.
- Mayor peso del paquete final que la opción A, mitigado con división de código por ruta y con
  presupuesto de tamaño verificado.
- Curva de aprendizaje inicial que no produce pantallas visibles.
- La disciplina de claves jerárquicas y de invalidación explícita es fricción diaria: cada mutación
  obliga a pensar qué se invalida. Es fricción deliberada, porque la alternativa es no saberlo.
- TanStack Form es la pieza más joven y la más probable de tener que revisar.

**Riesgos y mitigaciones:**

| Riesgo | Mitigación |
|---|---|
| Una actualización menor de TanStack Router rompe una ruta crítica | Versiones exactas fijadas, más suite de Playwright sobre las rutas críticas que corre en cada pull request y ante cada actualización de dependencias. |
| Alguien escribe una clave de consulta a mano y rompe la jerarquía de invalidación | Regla de ESLint que prohíbe literales de arreglo en la propiedad `queryKey`, exigiendo que provengan de una fábrica de claves. Severidad `error`. |
| Un pago registrado no refresca el saldo en pantalla | Prueba de integración de frontend con Testing Library y MSW que registra un pago y afirma que la vista de saldo se vuelve a solicitar y muestra el valor nuevo. |
| Un grid trae el conjunto completo al navegador | Prueba que afirma que toda instancia de tabla de datos de servidor declara `manualPagination`, `manualSorting` y `manualFiltering`, más verificación en la API de que todo listado aplica límite máximo en servidor (`docs/01-arquitectura.md`, sección 7). |
| Se duplica el saldo en un almacén global | Regla de ESLint que prohíbe importar el cliente de API dentro de los archivos de almacén global, más revisión de la lista de campos permitidos en el almacén, que es una instantánea aprobada. |
| Un error del backend se muestra en crudo al usuario, filtrando detalles internos | Prueba que provoca respuestas de error de varios tipos y afirma que el texto visible proviene del catálogo de i18next y que el campo `detail` no aparece literalmente en pantalla. |
| El paquete inicial del portal crece hasta ser inusable en red móvil | Presupuesto de tamaño verificado en integración continua, con fallo de construcción al superar el umbral acordado. |
| Una actualización optimista muestra un saldo no asentado | Regla de ESLint que prohíbe `onMutate` en las mutaciones marcadas como escritura financiera, más revisión de la lista de mutaciones financieras. |

## Cumplimiento y verificación

1. **Claves de consulta desde fábrica.** Regla de ESLint `confia/query-key-from-factory`, severidad
   `error`: prohíbe literales de arreglo como valor de `queryKey` y exige una referencia a una
   fábrica exportada desde un archivo `query-keys.ts`.
2. **Prohibición de invalidación global.** Regla de ESLint `confia/no-global-invalidate`, severidad
   `error`: `invalidateQueries` invocado sin argumentos o sin `queryKey` es un fallo de análisis
   estático.
3. **Prohibición de estado de servidor en estado global.** `dependency-cruiser` prohíbe que cualquier
   archivo bajo `src/stores/**` importe del cliente de API, de `packages/contracts` o de módulos de
   consultas. Severidad `error`.
4. **Grids en modo servidor.** Prueba de análisis estático que recorre las invocaciones de
   `useReactTable` en ambas aplicaciones y falla si alguna tabla marcada como tabla de datos de
   servidor no declara los tres modos manuales.
5. **Validación de parámetros de búsqueda.** Prueba que recorre el árbol de rutas generado y falla si
   una ruta que declara parámetros de búsqueda no tiene `validateSearch` con un esquema Zod.
6. **Propagación de invalidación tras escritura financiera.** Pruebas de integración de frontend con
   Vitest, Testing Library y MSW para cada mutación financiera: registrar pago, aplicar abono,
   anular, emitir factura. Cada una afirma que las consultas dependientes declaradas se vuelven a
   solicitar tras el éxito de la mutación.
7. **Rutas críticas de extremo a extremo.** Playwright sobre inicio de sesión con MFA, registro de
   pago, emisión de factura, anulación, cierre de caja y consulta del portal (`CLAUDE.md`, sección
   Pruebas). Esta suite es el detector temprano de rupturas de API de TanStack Router y corre también
   en el pull request de actualización de dependencias.
8. **Enlace de reporte reproducible.** Prueba de extremo a extremo que aplica filtros en la vista de
   estado de cuenta, copia la URL resultante, la abre en un contexto de navegador nuevo y afirma que
   la vista muestra exactamente el mismo conjunto filtrado.
9. **Presupuesto de tamaño.** Verificación en integración continua del tamaño del paquete inicial de
   cada aplicación, con umbral declarado en `packages/config`. Superarlo rompe la construcción.
10. **Versiones fijadas.** Verificación que falla si alguna dependencia de la suite TanStack está
    declarada con rango (`^` o `~`) en lugar de versión exacta.
11. **Accesibilidad.** axe-core integrado en las pruebas de Testing Library y de Playwright. Ninguna
    violación crítica puede fusionarse (ver ADR-0008).
12. **Traducción de errores.** Prueba que afirma que todo `type` de Problem Details declarado en
    `packages/contracts` tiene una clave correspondiente en el catálogo de i18next (ver ADR-0011).

## Referencias

- `docs/01-arquitectura.md`, secciones 3.3 y 7
- `CLAUDE.md`, secciones Frontend y Pruebas
- `docs/ui-ux/`
- ADR-0001: stack tecnológico (reemplazado por ADR-0013)
- ADR-0003: separación entre administración y portal
- ADR-0008: estrategia de pruebas
- ADR-0011: internacionalización y multi-moneda
- ADR-0012: contenerización
- ADR-0013: backend en Java con Spring Boot
