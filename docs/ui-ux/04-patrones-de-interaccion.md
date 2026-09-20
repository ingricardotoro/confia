# CONFIA. Patrones de interacción

> Estado: **Propuesta v1.0**
> Alcance: panel administrativo (`apps/admin-web`) y portal de encargados (`apps/portal-web`).
> Subordinado a `00-principios-de-diseno.md`. Si un patrón de este documento contradice un
> principio, gana el principio y el patrón se corrige.
> Los nombres de módulos, roles, estados y entidades provienen de `../02-modelo-de-dominio.md`
> y de `../00-vision-y-alcance.md`. No se inventan aquí.

---

## 1. Navegación del panel administrativo

### 1.1 Estructura de la ventana

El panel usa tres zonas fijas y una zona de contenido. Ninguna de las tres fijas se oculta por
scroll, porque el cajero necesita ver el estado de su sesión de caja en todo momento (principio 5).

```
+--------------------------------------------------------------------------------------+
| BARRA SUPERIOR (fija, 56 px)                                                         |
| [logo CONFIA] [buscar: / ]  [Año lectivo 2026 v] [Caja abierta L 12,450.00] [!] [RA] |
+------------------+-------------------------------------------------------------------+
| BARRA LATERAL    | MIGAS DE PAN                                                      |
| (persistente,    | Inicio / Caja / Cobrar en ventanilla                              |
|  260 px,         +-------------------------------------------------------------------+
|  colapsable      | ENCABEZADO DE PÁGINA                                              |
|  a 64 px)        | Título H1 + descripción + acción primaria única                   |
|                  +-------------------------------------------------------------------+
| [módulos         |                                                                   |
|  agrupados]      | CONTENIDO                                                         |
|                  |                                                                   |
+------------------+-------------------------------------------------------------------+
```

Reglas duras de la barra lateral:

| Regla | Detalle |
|---|---|
| Persistente por omisión | Nunca arranca colapsada. Colapsar es una decisión del usuario, se guarda en `localStorage` con la clave `confia.admin.sidebar.collapsed` |
| Colapsada muestra icono más etiqueta emergente | En estado colapsado cada icono expone su nombre en un `Tooltip` accesible por teclado. Nunca iconos mudos (principio 1) |
| Los grupos no se anidan más de dos niveles | Módulo, luego pantalla. Un tercer nivel se resuelve con pestañas dentro de la pantalla |
| Solo se muestran los módulos permitidos | Ocultar una entrada es cortesía. La autorización real vive en el servidor, según `../03-seguridad.md` sección 5.1 |
| El grupo activo se marca con color, peso tipográfico e indicador de barra lateral izquierda | El color nunca comunica solo (principio 6) |

### 1.2 Barra superior

| Elemento | Comportamiento | Componente |
|---|---|---|
| Búsqueda global | Se enfoca con `/` desde cualquier pantalla. Abre un `CommandPalette` con resultados agrupados por tipo | `GlobalSearch` |
| Selector de año lectivo | Muestra el año lectivo en contexto. Si el seleccionado no es el vigente, se pinta un `Banner` persistente en toda la aplicación | `AcademicYearSwitcher` |
| Indicador de sesión de caja | Visible solo para el rol Cajero. Muestra estado, hora de apertura y total acumulado. Enlaza a `/caja/sesion` | `CashSessionBadge` |
| Campana de notificaciones | Alertas operativas: rango CAI por agotarse, cierre de caja con diferencia pendiente de aprobación, aprobación asignada, importación bancaria terminada | `NotificationBell` |
| Menú de usuario | Nombre, rol activo, "Mi perfil", "Autenticación en dos pasos", "Mis sesiones activas", "Cerrar sesión" | `UserMenu` |

Texto del banner de año lectivo no vigente:

```
admin.banner.non_current_academic_year =
  "Está viendo el año lectivo 2025, que no es el vigente. Los cobros y la facturación no están disponibles en un año lectivo cerrado."
admin.banner.non_current_academic_year.action = "Volver al año lectivo 2026"
```

### 1.3 Migas de pan

Formato: `Inicio / <Módulo> / <Pantalla> / <Registro>`. El último segmento no es un enlace. Cuando
el registro es una persona, se muestra el nombre completo, nunca el identificador. Ejemplo:

```
Inicio / Estudiantes / Marlen Sofía Cruz Andino / Estado de cuenta
```

### 1.4 Árbol de navegación completo del panel

```
Inicio
└── Panel de control                                   /

Estudiantes
├── Estudiantes                                        /estudiantes
├── Matrículas del año lectivo                         /estudiantes/matriculas
├── Traslados de sección                               /estudiantes/traslados
└── Encargados de pago                                 /encargados

Cuentas y cargos
├── Estados de cuenta                                  /cuentas
├── Cargos                                             /cargos
├── Planes de cobro                                    /cargos/planes
├── Generación de cargos                               /cargos/generaciones
├── Becas                                              /becas
└── Descuentos                                         /descuentos

Caja
├── Cobrar en ventanilla                               /caja/cobrar
├── Mi sesión de caja                                  /caja/sesion
├── Movimientos de efectivo                            /caja/movimientos
├── Cierre y arqueo                                    /caja/cierre
└── Cierres anteriores                                 /caja/cierres

Facturación
├── Documentos fiscales                                /facturacion/documentos
├── Notas de crédito                                   /facturacion/notas-de-credito
├── Libro de ventas                                    /facturacion/libro-de-ventas
├── Rangos CAI                                         /facturacion/cai
└── Puntos de emisión                                  /facturacion/puntos-de-emision

Cobranza
├── Atrasos                                            /cobranza/atrasos
├── Casos de cobranza                                  /cobranza/casos
├── Promesas de pago                                   /cobranza/promesas
├── Reglas de mora                                     /cobranza/reglas
├── Avisos enviados                                    /cobranza/avisos
└── Incobrables                                        /cobranza/incobrables

Conciliación
├── Pagos declarados                                   /conciliacion/declarados
├── Importar estado de cuenta bancario                 /conciliacion/importar
├── Partidas por conciliar                             /conciliacion/pendientes
└── Cuentas bancarias                                  /conciliacion/cuentas

Documentos
├── Solicitudes                                        /documentos/solicitudes
├── Tipos de documento                                 /documentos/tipos
└── Plantillas                                         /documentos/plantillas

Reportes
├── Recaudación del período                            /reportes/recaudacion
├── Antigüedad de saldos                               /reportes/antiguedad
├── Morosidad por grado y modalidad                    /reportes/morosidad
├── Cierres de caja                                    /reportes/cierres-de-caja
├── Continuidad de correlativos                        /reportes/correlativos
├── Exportación contable                               /reportes/exportacion-contable
└── Vistas guardadas                                   /reportes/vistas

Configuración
├── Institución                                        /configuracion/institucion
├── Años lectivos                                      /configuracion/anios-lectivos
├── Modalidades, grados y secciones                    /configuracion/estructura
├── Conceptos de pago                                  /configuracion/conceptos
├── Listas de precios                                  /configuracion/precios
├── Impuestos                                          /configuracion/impuestos
├── Métodos de pago                                    /configuracion/metodos-de-pago
├── Plantillas de notificación                         /configuracion/plantillas
└── Parámetros del sistema                             /configuracion/parametros

Administración
├── Usuarios                                           /administracion/usuarios
├── Roles y permisos                                   /administracion/roles
├── Aprobaciones pendientes                            /administracion/aprobaciones
└── Sesiones activas                                   /administracion/sesiones

Auditoría
├── Bitácora de auditoría                              /auditoria/bitacora
├── Integridad del libro mayor                         /auditoria/integridad
└── Verificación de cadena de auditoría                /auditoria/cadena
```

Visibilidad por rol, derivada de la matriz de `../03-seguridad.md` sección 5.2:

| Grupo | Super Admin | Administrador | Cajero | Contabilidad | Auditor | Coord. Académico |
|---|---|---|---|---|---|---|
| Inicio | Sí | Sí | Sí | Sí | Sí | Sí |
| Estudiantes | Sí | Sí | Solo lectura | Solo lectura | Solo lectura | Sí |
| Cuentas y cargos | Sí | Sí | Solo lectura | Solo lectura | Solo lectura | Solo lectura |
| Caja | Sí | Solo supervisión | Sí | Solo lectura | Solo lectura | No |
| Facturación | Sí | Solo aprobación | Emitir del pago propio | Sí | Solo lectura | No |
| Cobranza | Sí | Sí | Solo lectura | Solo lectura | Solo lectura | Solo lectura |
| Conciliación | Sí | Solo aprobación | No | Sí | Solo lectura | No |
| Documentos | Sí | Sí | Solo lectura | No | Solo lectura | Sí |
| Reportes | Sí | Sí | Solo su caja | Sí | Sí | Solo académico |
| Configuración | Sí | Lectura y edición parcial | No | Solo lectura | Solo lectura | No |
| Administración | Sí | Solo lectura | No | No | Solo lectura | No |
| Auditoría | Sí | Solo lectura | No | No | Sí | No |

---

## 2. Navegación del portal de encargados

### 2.1 Decisión de alcance

`00-principios-de-diseno.md` sección 5 fija **cuatro destinos** para el portal. Este documento no
los amplía a cinco. El perfil no es un destino de la barra inferior: vive en el menú de la barra
superior, porque un encargado entra dos o tres veces al mes y ninguno de esos ingresos tiene como
objetivo editar su perfil. Los avisos tampoco son destino: aparecen como tarjetas en Inicio, que es
donde el encargado ya mira.

### 2.2 Estructura móvil

```
+---------------------------------------+
| BARRA SUPERIOR (fija, 56 px)          |
| CONFIA          [avisos]  [menú]      |
+---------------------------------------+
|                                       |
| CONTENIDO                             |
| Una acción principal por pantalla     |
| Objetivos táctiles de 44 x 44 px      |
|                                       |
+---------------------------------------+
| BARRA INFERIOR (fija, 64 px)          |
| [Inicio] [Cuenta] [Comprobantes] [Sol]|
+---------------------------------------+
```

### 2.3 Árbol de navegación del portal

```
Inicio                                                 /
├── Resumen: cuánto debe y hasta cuándo
├── Tarjeta por estudiante vinculado
└── Avisos vigentes (vencimiento próximo, transferencia confirmada, promesa por vencer)

Estado de cuenta                                       /cuenta
├── Estado de cuenta por estudiante                    /cuenta/:studentId
├── Detalle de un concepto pendiente                   /cuenta/:studentId/cargo/:chargeId
└── Reportar una transferencia                         /cuenta/:studentId/reportar-transferencia

Comprobantes                                           /comprobantes
├── Historial de pagos aplicados                       /comprobantes
└── Detalle de un pago con su documento fiscal         /comprobantes/:paymentId

Solicitudes                                            /solicitudes
├── Mis solicitudes                                    /solicitudes
├── Nueva solicitud                                    /solicitudes/nueva
└── Detalle de una solicitud                           /solicitudes/:requestId

Menú superior (no es destino de la barra inferior)
├── Mi perfil y datos de contacto                      /perfil
├── Cómo quiero recibir los avisos                     /perfil/avisos
├── Seguridad y verificación en dos pasos              /perfil/seguridad
└── Cerrar sesión
```

Lo que **no existe** en el portal, por decisión de aislamiento (`../01-arquitectura.md` sección 5):
ninguna pantalla de caja, facturación, usuarios, cobranza operativa, configuración ni auditoría.
Tampoco existe ninguna acción destructiva. El encargado no anula, no edita y no borra nada.

---

## 3. Patrón estándar de página de listado

### 3.1 Composición

```
+----------------------------------------------------------------------------------+
| PageHeader                                                                       |
| H1 Cargos                        [Exportar v]  [+ Registrar cargo manual]        |
| 1,340 cargos en el año lectivo 2026                                              |
+----------------------------------------------------------------------------------+
| FilterBar                                                                        |
| [buscar estudiante o código]  [Estado v] [Concepto v] [Grado v] [Vence: rango]   |
| Filtros activos: Estado = Vencido  x   Grado = 7  x        [Limpiar todo]        |
+----------------------------------------------------------------------------------+
| DataTable  (paginada y filtrada EN SERVIDOR)                                     |
| [ ] Estudiante        Concepto      Período   Vence      Total      Saldo  Estado|
| [ ] Cruz Andino, M.   Colegiatura   Ago 2026  05/08/26  L 2,450.00  L 2,450.00 (!) Vencido
| [ ] Discua Lanza, J.  Transporte    Ago 2026  05/08/26  L   850.00  L   400.00 (~) Parcial
| ...                                                                              |
| TOTALES DE LA CONSULTA COMPLETA: Cargado L 1,248,350.00  Saldo L 412,900.00      |
+----------------------------------------------------------------------------------+
| BulkActionBar (aparece solo con selección)                                       |
| 12 cargos seleccionados   [Enviar aviso de cobranza] [Exportar selección]        |
+----------------------------------------------------------------------------------+
| TablePagination                                                                  |
| Mostrando 25 de 1,340 registros   [25 v] por página   < 1 2 3 ... 54 >           |
+----------------------------------------------------------------------------------+
```

Reglas del patrón:

1. **Una sola acción primaria** en el encabezado. Exportar es secundaria y vive en un menú.
2. **Los importes se alinean a la derecha** con numeración tabular y dos decimales (principio 2).
3. **El estado se expresa con icono, texto y color**, en ese orden de prioridad de lectura.
4. **Los totales se calculan sobre la consulta completa, no sobre la página visible**, y los
   devuelve el servidor en la respuesta del listado. Nunca se suman en el navegador.
5. **Las acciones por lote nunca incluyen operaciones fiscales.** No existe "anular en lote" ni
   "registrar pago en lote". El lote solo cubre acciones reversibles: enviar aviso, exportar,
   asignar responsable de una solicitud.
6. **El encabezado de columna nunca dice "Monto" a secas**: dice Cargado, Pagado, Saldo o Recargo.

### 3.2 Filtros en la URL

Todo el estado del listado vive en los parámetros de búsqueda de la URL, validados con Zod en
TanStack Router. La razón operativa es Doña Rina de contabilidad: pega la URL en un correo y quien
la abre ve exactamente lo mismo que ella (perfil 4.2 de los principios).

Contrato de parámetros. Los nombres van en inglés, como todo identificador del sistema.

| Parámetro | Tipo | Valor por omisión | Notas |
|---|---|---|---|
| `q` | texto | vacío | Búsqueda libre. Se aplica con retardo de escritura de 300 ms |
| `page` | entero mayor o igual a 1 | `1` | Solo en listados con total conocido |
| `cursor` | texto opaco | ausente | Solo en listados virtualizados. Ver 3.3 |
| `pageSize` | `25 \| 50 \| 100` | `25` | El servidor aplica el tope máximo, el cliente no decide |
| `sort` | `campo:asc` o `campo:desc` | según pantalla | Un solo criterio. Ejemplo `dueOn:asc` |
| `academicYear` | año | año vigente | Si difiere del vigente se muestra el banner persistente |
| `status` | lista separada por comas | vacío | Valores del dominio en inglés: `pending,partially_paid,overdue,paid,reversed` |
| `concept` | lista de códigos | vacío | Código de `PaymentConcept`, no su identificador |
| `grade` | lista de códigos | vacío | |
| `section` | lista de códigos | vacío | |
| `modality` | lista de códigos | vacío | |
| `dueFrom`, `dueTo` | fecha `AAAA-MM-DD` | vacío | Rango cerrado en ambos extremos |
| `amountMin`, `amountMax` | cadena decimal con dos decimales | vacío | Nunca número JSON. Ver `../02-modelo-de-dominio.md` sección 8 |
| `cashier` | identificador de usuario | vacío | Solo visible para roles con supervisión de caja |
| `density` | `comfortable \| compact` | `comfortable` | Preferencia visual, también persistida por usuario |
| `columns` | lista de campos | vista por omisión | Visibilidad de columnas |
| `view` | identificador de vista guardada | vacío | Al aplicarla, expande sus filtros a la URL |

Ejemplo concreto de URL compartible:

```
https://admin.confia.hn/cargos
  ?academicYear=2026
  &status=overdue,partially_paid
  &grade=7,8
  &dueFrom=2026-08-01&dueTo=2026-08-31
  &amountMin=500.00
  &sort=dueOn:asc
  &page=2&pageSize=50
  &columns=student,concept,period,dueOn,total,balance,status
  &density=compact
```

Reglas de la URL:

1. Un parámetro con su valor por omisión **no se escribe en la URL**. La URL limpia es la URL sin
   filtros.
2. Cambiar un filtro **reemplaza** la entrada del historial. Cambiar de página la **agrega**. Así
   el botón de retroceso del navegador devuelve a la página anterior, no recorre cada tecla escrita
   en el campo de búsqueda.
3. Un parámetro inválido no rompe la pantalla: se descarta, se aplica el valor por omisión y se
   muestra una notificación emergente con
   `admin.filters.invalid_param = "Se ignoró un filtro no válido de la dirección: {{param}}."`
4. **La exportación usa exactamente los filtros de la URL.** Si lo exportado no coincide con lo
   visible, es un defecto bloqueante.

### 3.3 Paginación: cuándo página y cuándo cursor

| Criterio | Paginación por página | Paginación por cursor |
|---|---|---|
| El usuario necesita el total y los totales monetarios | Sí | No |
| Volumen esperado | Hasta decenas de miles | Sin tope práctico |
| Ejemplos en CONFIA | Cargos, documentos fiscales, estudiantes, libro de ventas, atrasos | Línea de tiempo del libro mayor, bitácora de auditoría, comprobantes del portal |
| Comportamiento visual | Controles numerados más "Mostrando X de Y" | Desplazamiento con `TanStack Virtual` y botón "Cargar más" accesible |

En ambos casos el filtrado, el orden y el corte ocurren en el servidor. Traer el conjunto completo
al navegador es un antipatrón declarado en `00-principios-de-diseno.md` sección 6.

---

## 4. Patrón estándar de formulario

### 4.1 Composición

```
+--------------------------------------------------+
| H1 Registrar encargado de pago                   |
| Los campos marcados con * son obligatorios.      |
+--------------------------------------------------+
| ErrorSummary  (solo tras un intento de envío)    |
| (!) No se pudo guardar. Revise 2 campos:         |
|     - Correo electrónico: formato no válido      |
|     - Porcentaje de responsabilidad: debe sumar  |
|       100 % entre todos los encargados           |
+--------------------------------------------------+
| Sección: Identificación                          |
|   Nombres *                                      |
|   [__________________________________]           |
|   Apellidos *                                    |
|   [__________________________________]           |
|   Documento de identidad *                       |
|   [____-____-______]                             |
|   Se usa para la factura. Se almacena cifrado.   |
+--------------------------------------------------+
| Sección: Contacto                                |
|   ...                                            |
+--------------------------------------------------+
| Sección: Responsabilidad financiera              |
|   ...                                            |
+--------------------------------------------------+
| [Cancelar]                    [Guardar encargado]|
+--------------------------------------------------+
```

### 4.2 Reglas duras

| Regla | Detalle |
|---|---|
| Una sola columna | Sin excepciones en formularios de captura. Dos columnas solo se permiten en pantallas de configuración de solo lectura con edición en línea |
| Etiqueta arriba del campo | Nunca a la izquierda, nunca solo como texto de marcador de posición (antipatrón declarado) |
| Ancho máximo del campo proporcional al dato | El campo de documento de identidad no ocupa el mismo ancho que el de dirección. Un campo de importe nunca es más ancho que 12 caracteres |
| Validación al perder el foco | Se valida al salir del campo, no en cada tecla. Un campo ya marcado con error revalida en cada tecla, para que el error desaparezca en cuanto se corrija |
| Botón de envío siempre habilitado | Nunca se deshabilita por formulario inválido. Al pulsarlo con errores se muestra el resumen y el foco salta al primer campo con error |
| Resumen de errores al inicio | `role="alert"`, cada línea es un enlace que lleva el foco al campo correspondiente |
| Texto de ayuda antes del campo, no después | Se lee antes de escribir. Se asocia con `aria-describedby` |
| Agrupación por secciones con `fieldset` y `legend` | Máximo cinco campos por sección |
| Importes | Siempre con el componente `MoneyInput`, que fija dos decimales al perder el foco y no acepta separadores ambiguos |

### 4.3 Guardado y confirmación

El guardado exitoso de un formulario no financiero muestra una notificación emergente y navega al
detalle del registro creado. El guardado de un formulario con efecto financiero (registrar pago,
emitir nota de crédito, cerrar caja) **no se resuelve con una notificación emergente**: pasa por el
diálogo de confirmación de la sección 8 y termina en una pantalla de resultado.

### 4.4 Salida con cambios sin guardar

Se intercepta la navegación interna con el bloqueador de TanStack Router y la salida del navegador
con `beforeunload`. Texto exacto del diálogo:

```
admin.form.unsaved.title   = "Tiene cambios sin guardar"
admin.form.unsaved.body    = "Si sale ahora, se perderá lo que escribió en este formulario. Esta pantalla no guarda borradores."
admin.form.unsaved.stay    = "Seguir editando"
admin.form.unsaved.discard = "Salir sin guardar"
```

El botón de permanencia es el primario y recibe el foco inicial. "Salir sin guardar" es el
secundario, nunca destructivo en color, porque no destruye datos ya persistidos.

---

## 5. Asistente por pasos para registrar un pago

Este es el flujo más importante del sistema y su objetivo medible es el OB-01 de
`../00-vision-y-alcance.md`: noventa segundos desde identificar al estudiante hasta imprimir el
comprobante.

### 5.1 Decisión de forma

El asistente **no cambia de página** ni usa un componente de pasos con URLs distintas. Vive en la
ruta `/caja/cobrar` como un panel de tres zonas que avanzan en la misma pantalla. Motivo: con doce
personas en fila, cada navegación completa es tiempo perdido y una oportunidad de perder el estado
capturado. El estado del paso vive en el parámetro `step` de la URL solo para permitir volver atrás
con el teclado, nunca para recargar datos.

```
/caja/cobrar?student=<uuid>&step=allocation
```

### 5.2 Los cinco pasos

**Paso 1. Identificar al estudiante.**

El foco entra automáticamente en el campo de búsqueda al cargar la pantalla. Se busca por código de
estudiante, nombre, apellido o documento de identidad del encargado. Se muestran como máximo ocho
resultados con nombre, código, grado, sección y saldo. Al seleccionar, se cargan sus cargos
pendientes sin recargar la página.

Punto de decisión: si el estudiante tiene matrícula no activa en el año lectivo en contexto, se
muestra un `Alert` de advertencia y el cobro continúa solo si el rol tiene el permiso
correspondiente. Si el estudiante no tiene ningún encargado de pago vigente, el paso se bloquea:
no hay a quién facturar.

**Paso 2. Elegir qué se cobra.**

Se muestra la tabla de cargos pendientes ordenada por la prelación por omisión de la institución.
El cajero puede:

- Aceptar la prelación automática, que es el camino esperado y el que ocupa el botón primario.
- Cambiar a imputación manual, marcando cargos específicos. Esta opción está detrás de un
  interruptor etiquetado "Elegir a mano qué cargos se pagan", nunca por omisión.

**Paso 3. Capturar el pago.**

| Campo | Regla |
|---|---|
| Monto recibido | `MoneyInput`. Obligatorio. Debe ser mayor que cero |
| Método de pago | Efectivo, tarjeta o transferencia declarada. Efectivo exige sesión de caja abierta (INV-20) |
| Referencia | Obligatoria si el método la exige. Para transferencia: número de referencia bancaria y fecha del movimiento |
| Fecha de recepción | Por omisión hoy. Retroceder la fecha exige permiso y motivo |
| Encargado que paga | Se preselecciona el pagador principal. Es quien aparece en la factura |
| Facturar a | Nombre y RTN del receptor de la factura. Se precarga del encargado y es editable con confirmación |

Si el monto excede el total de los cargos seleccionados, la interfaz **no bloquea**: informa del
remanente y explica que quedará como saldo a favor del estudiante.

**Paso 4. Confirmar. Esta es la pantalla que evita el error.**

El diálogo de confirmación muestra, sin abreviar y sin desplazamiento oculto:

```
+---------------------------------------------------------------+
| Confirmar cobro                                               |
+---------------------------------------------------------------+
| Estudiante   Marlen Sofía Cruz Andino (EST-2026-0418)         |
|              7.º grado, sección A, año lectivo 2026           |
| Paga         Rosa Elena Andino Discua, documento 0801-1982-... |
| Método       Efectivo                                         |
| Caja         Caja 1, sesión abierta a las 07:45               |
+---------------------------------------------------------------+
| SE VA A COBRAR                              L    3,150.00     |
+---------------------------------------------------------------+
| Se aplicará en este orden (deudas más antiguas primero):      |
|                                                               |
| 1. Recargo por mora, Julio 2026     venció 05/07/26  L 122.50 |
| 2. Colegiatura Julio 2026           venció 05/07/26 L 2,450.00|
| 3. Transporte Agosto 2026           vence  05/08/26  L 577.50 |
|                                     (abono parcial de L 850.00)|
+---------------------------------------------------------------+
| Quedará como saldo a favor                       L      0.00  |
| Saldo del estudiante después del cobro           L    272.50  |
+---------------------------------------------------------------+
| Se emitirá                                                    |
| Factura bajo CAI 3B4C1D-9E2F0A-...-4521, correlativo          |
| 001-001-01-00004518, punto de emisión Caja 1.                 |
| Quedan 482 correlativos disponibles en el rango vigente.      |
+---------------------------------------------------------------+
| [Volver a revisar]                    [Cobrar e imprimir]     |
+---------------------------------------------------------------+
```

Reglas del paso 4, todas obligatorias:

1. El importe total se muestra en el tamaño tipográfico más grande de la pantalla.
2. **La prelación se muestra numerada y explicada en palabras**, no solo como una lista de cargos.
3. El correlativo y el CAI que se van a consumir se muestran antes de emitir, no después.
4. El botón primario dice lo que va a ocurrir: "Cobrar e imprimir". Nunca "Aceptar" ni "Confirmar".
5. El diálogo no se cierra al hacer clic fuera. Se cierra con `Escape` o con "Volver a revisar".
6. Al pulsar el botón primario, el botón entra en estado de carga y **queda bloqueado hasta que el
   servidor responde**. Se envía la cabecera `Idempotency-Key` generada al abrir el diálogo, no al
   pulsar el botón.

**Paso 5. Resultado.**

Pantalla de éxito con el número de recibo, el correlativo emitido, el botón "Imprimir comprobante"
con foco inicial, "Descargar PDF", "Enviar por correo al encargado" y "Cobrar a otro estudiante",
que reinicia el asistente con el foco en la búsqueda.

### 5.3 Prelación de aplicación de pagos

Orden por omisión (`OLDEST_FIRST`), tomado del dominio y no negociable en la interfaz:

| Prioridad | Regla |
|---|---|
| 1 | Cargos vencidos, del vencimiento más antiguo al más reciente |
| 2 | Dentro de la misma fecha de vencimiento: primero el recargo por mora, después el cargo principal |
| 3 | Cargos por vencer, del vencimiento más próximo al más lejano |
| 4 | El remanente queda como saldo a favor del estudiante, nunca se descarta ni se reparte solo |

Políticas alternativas configurables por institución: `NEWEST_FIRST`, `SMALLEST_FIRST` y `MANUAL`.
La interfaz muestra siempre el nombre legible de la política vigente en el paso 4:

```
admin.payment.allocation_policy.oldest_first  = "Deudas más antiguas primero"
admin.payment.allocation_policy.newest_first  = "Deudas más recientes primero"
admin.payment.allocation_policy.smallest_first= "Deudas de menor monto primero"
admin.payment.allocation_policy.manual        = "Imputación elegida a mano"
```

**El desglose de la imputación lo calcula el servidor y lo devuelve como vista previa.** La interfaz
nunca reparte el importe entre cargos por su cuenta. Ese cálculo lo hace el servidor, en el paquete `domain` del módulo `payments`.

---

## 6. Estados de la interfaz

Toda pantalla declara los cinco estados siguientes en su especificación. Una pantalla sin estado
vacío y sin estado de error no se implementa.

| Estado | Cuándo | Qué muestra | Qué acción ofrece |
|---|---|---|---|
| **Cargando** | Primera carga o cambio de filtros que invalida el contenido | Esqueleto con la forma exacta del contenido: mismas columnas, mismo número de filas que `pageSize` | Ninguna. El resto de la pantalla sigue operable |
| **Vacío por naturaleza** | La consulta es válida y no hay nada que mostrar porque aún no existe | Icono, título, una frase de explicación y la acción que crea el primer registro | Acción primaria de creación |
| **Sin resultados de búsqueda** | Hay filtros aplicados y ninguna coincidencia | Repite en texto los filtros activos, para que el usuario vea qué está limitando | "Limpiar filtros" y "Quitar solo el filtro de estado" |
| **Error total** | La consulta principal falló | Título, causa en lenguaje llano, identificador de traza copiable | "Reintentar" y "Copiar identificador de error" |
| **Error parcial** | El contenido principal cargó pero un bloque secundario falló | El contenido bueno se muestra normal. El bloque roto muestra su propio aviso en línea, con su reintento | Reintento del bloque, sin recargar la pantalla |

Regla de instrumento, obligatoria:

> **Esqueletos para contenido que llega. Indicador giratorio solo para una acción puntual en curso,
> siempre dentro del control que la disparó.** Nunca un indicador giratorio a pantalla completa.
> Nunca un esqueleto dentro de un botón.

Umbral: toda operación de más de 300 ms muestra retroalimentación (principio 5). Por debajo de ese
umbral no se muestra nada, para evitar el parpadeo.

Ejemplo de estado vacío por naturaleza en `/caja/cierres`:

```
admin.cashbox.closures.empty.title  = "Todavía no hay cierres de caja"
admin.cashbox.closures.empty.body   = "Aquí aparecerá el arqueo de cada jornada una vez que se cierre la sesión de caja."
admin.cashbox.closures.empty.action = "Abrir sesión de caja"
```

Ejemplo de error total:

```
admin.error.load_failed.title  = "No se pudo cargar la lista de cargos"
admin.error.load_failed.body   = "El servidor no respondió. Si el problema continúa, comparta este identificador con soporte: {{traceId}}"
admin.error.load_failed.retry  = "Reintentar"
admin.error.load_failed.copy   = "Copiar identificador"
```

---

## 7. Retroalimentación: qué canal usar

| Tipo de evento | Notificación emergente | Mensaje en línea | Diálogo modal | Banner persistente | Ejemplo real en CONFIA |
|---|---|---|---|---|---|
| Éxito de acción reversible y de bajo impacto | **Sí**, 4 s, con acción de deshacer si aplica | No | No | No | "Concepto de pago archivado." con "Deshacer" |
| Éxito de acción financiera | No | No | No | No | El pago registrado abre la pantalla de resultado del paso 5, no una notificación emergente |
| Error de validación de un campo | No | **Sí**, bajo el campo, con `aria-describedby` | No | No | "El documento de identidad debe tener 13 dígitos." |
| Error de validación del formulario completo | No | **Sí**, en el resumen al inicio | No | No | "No se pudo guardar. Revise 2 campos." |
| Error del servidor en una acción que el usuario disparó | **Sí** si es transitorio y reintentable | **Sí** si el error apunta a un campo o a un dato concreto | No | No | "No se pudo enviar el aviso. Reintentar." |
| Error del servidor que impide continuar el flujo | No | No | **Sí** | No | "No hay correlativos disponibles en el rango CAI vigente." durante el cobro |
| Decisión irreversible que se va a ejecutar | No | No | **Sí** | No | Confirmación de cobro, anulación de factura, cierre de caja |
| Condición de contexto que afecta todo lo que se ve | No | No | No | **Sí** | "Está viendo el año lectivo 2025, que no es el vigente." |
| Condición operativa que requiere acción pronto pero no ahora | No | No | No | **Sí**, en la pantalla del módulo afectado | "El rango CAI vigente tiene 482 correlativos disponibles, un 4 % del total." |
| Cuenta de estudiante marcada en revisión por descuadre | No | No | No | **Sí**, en el estado de cuenta | "Esta cuenta está en revisión. No se pueden registrar movimientos hasta que contabilidad la libere." |
| Resultado de un proceso largo en segundo plano | **Sí**, con enlace al resultado | No | No | No | "La generación de cargos de agosto terminó: 512 creados, 3 omitidos. Ver detalle." |
| Sesión por expirar | No | No | **Sí**, en el panel administrativo, a los 25 minutos de inactividad | No | "Su sesión se cerrará en 5 minutos por inactividad." |

El cierre de sesión administrativa a los 30 minutos de inactividad (con este aviso a los 25) es un
control adicional de `docs/03-seguridad.md`, sección 4.5, compatible con la vida del token de
refresco de 8 horas de ADR-0005: reduce la ventana de exposición de una estación de caja
desatendida, sin cambiar la revocación por familia que ADR-0005 ya especifica.

Reglas adicionales:

1. Una notificación emergente **nunca** informa de un error que requiere una decisión. Si hay que
   decidir, es un diálogo modal.
2. Un diálogo modal **nunca** se usa para mostrar información no bloqueante (antipatrón declarado).
3. Nunca se apilan más de tres notificaciones emergentes. La cuarta reemplaza a la más antigua.
4. Toda notificación emergente es descartable con `Escape` y su contenido se anuncia con
   `role="status"` para éxito y `role="alert"` para error.

---

## 8. Confirmaciones y acciones destructivas

### 8.1 Escala de fricción

La fricción es proporcional al daño de una ejecución accidental (principio 4). Cinco niveles y
ninguno más:

| Nivel | Nombre | Mecanismo |
|---|---|---|
| F0 | Ninguna | La acción se ejecuta al primer clic |
| F1 | Deshacer | Se ejecuta y se ofrece revertir durante 8 segundos en la notificación emergente |
| F2 | Confirmación simple | `ConfirmDialog` con resumen de consecuencia y botón que nombra la acción |
| F3 | Confirmación con motivo | `ConfirmDialog` más campo de motivo obligatorio de al menos 20 caracteres |
| F4 | Confirmación con escritura | `DestructiveConfirmDialog`: motivo obligatorio más escribir una palabra exacta para habilitar el botón |

### 8.2 Asignación por acción real del sistema

| Acción | Nivel | Texto de consecuencia exacto |
|---|---|---|
| Ocultar una columna de la tabla | F0 | Sin texto |
| Cambiar la densidad de la tabla | F0 | Sin texto |
| Guardar una vista de reporte | F0 | Sin texto |
| Exportar a CSV o Excel | F0 | Sin texto. La exportación se audita, no se confirma |
| Archivar un concepto de pago | F1 | "Se archivó el concepto Transporte. No aparecerá en cobros nuevos. Los cargos ya emitidos no cambian." |
| Desactivar un descuento | F1 | "Se desactivó el descuento Pronto pago. Los cargos ya emitidos con ese descuento no cambian." |
| Quitar el vínculo de un encargado con un estudiante | F2 | "Rosa Elena Andino Discua dejará de ver el estado de cuenta de Marlen Sofía Cruz Andino y dejará de recibir sus avisos de cobranza. El historial de pagos que ya registró se conserva." |
| Desactivar un usuario del personal | F2 | "Wilmer Josué Fúnez no podrá volver a iniciar sesión y sus sesiones activas se cerrarán ahora. Su historial de cobros y los documentos que emitió se conservan íntegros." |
| Cancelar una promesa de pago vigente | F2 | "La promesa de L 3,000.00 para el 20/08/2026 quedará cancelada. Si la regla de mora estaba suspendida por esta promesa, el recargo volverá a calcularse desde la fecha de vencimiento original del cargo." |
| Cerrar sesión de caja sin diferencia | F2 | "Se cerrará la sesión de Caja 1 abierta a las 07:45. Conteo esperado L 18,320.00, conteo declarado L 18,320.00, sin diferencia. Después del cierre no podrá registrar más cobros en esta sesión." |
| Registrar un pago | F3 sin motivo, F2 reforzado | Ver el diálogo completo de la sección 5.2. El importe, el destinatario, la imputación y el correlativo son obligatorios en el texto |
| Cerrar sesión de caja con diferencia | F3 | "Se cerrará Caja 1 con un FALTANTE de L 200.00. Conteo esperado L 18,320.00, conteo declarado L 18,120.00. La diferencia queda registrada a su nombre y necesita la aprobación de un Administrador distinto de usted. Esta acción no se puede deshacer." |
| Reversar un pago ya aplicado | F4, palabra `REVERSAR` | "Se emitirá un asiento de reverso por L 3,150.00 del pago RCB-2026-004518 de Marlen Sofía Cruz Andino. Los 3 cargos que este pago canceló volverán a quedar abiertos y su saldo aumentará a L 3,422.50. El pago original no se borra: queda en el historial con estado Reversado. Si este pago tiene factura emitida, debe emitir además la nota de crédito correspondiente." |
| Anular una factura | F4, palabra `ANULAR` | "Se anulará la factura 001-001-01-00004518 por L 3,150.00 emitida el 09/09/2026 a nombre de Rosa Elena Andino Discua. El correlativo queda consumido y no se reutiliza: el hueco quedará justificado por esta anulación en el reporte de continuidad. La factura no se borra ni se edita. Esta acción necesita la aprobación de Contabilidad o Super Administrador, y esa persona no puede ser quien la emitió." |
| Emitir nota de crédito | F3 | "Se emitirá una nota de crédito por L 1,200.00 sobre la factura 001-001-01-00004518, que tiene L 3,150.00 sin acreditar. Se consumirá un correlativo del rango de notas de crédito y se asentará el crédito en la cuenta de Marlen Sofía Cruz Andino." |
| Dar de baja un cargo por incobrable | F4, palabra `INCOBRABLE` | "Se dará de baja por incobrable el cargo Colegiatura Julio 2025 por L 2,450.00 de Marlen Sofía Cruz Andino. La deuda no se borra: se reclasifica contablemente y deja de aparecer en la cartera activa. Necesita aprobación de Super Administrador." |
| Registrar un ajuste manual en el libro mayor | F4, palabra `AJUSTAR` | "Se asentará un ajuste de L 450.00 a favor del estudiante en la cuenta de Marlen Sofía Cruz Andino. Todo ajuste manual queda en la bitácora con su nombre, el motivo que escriba y el aprobador, y se revisa en la auditoría trimestral. Necesita aprobación de Super Administrador." |
| Eliminar un estudiante, un pago, una factura o un cargo | **No existe** | La interfaz no ofrece esta acción. En CONFIA nada financiero se borra. Ver principio 4 |

Reglas de escritura de los diálogos:

1. El diálogo dice **qué**, **cuánto**, **de quién** y **qué se genera en su lugar**. Un diálogo que
   solo dice "¿Está seguro?" es un defecto.
2. El botón primario del diálogo nombra la acción. El botón de cancelar dice "Cancelar" y recibe el
   foco inicial en los niveles F3 y F4.
3. En F4 el botón destructivo permanece deshabilitado hasta que la palabra coincide exactamente.
   Esta es la única excepción permitida a la regla de "nunca deshabilitar el botón de envío", y se
   permite porque el motivo del bloqueo está escrito de forma explícita justo encima.
4. El botón destructivo nunca comparte borde con el botón de cancelar: se separan con al menos
   24 px.

---

## 9. Manejo de errores del servidor

El contrato de la API devuelve errores en formato Problem Details según RFC 9457, sucesor de la
RFC 7807 (`../01-arquitectura.md` sección 7). Estructura recibida:

```jsonc
{
  "type": "https://confia.hn/problems/cai-range-exhausted",
  "title": "CAI range exhausted",
  "status": 409,
  "detail": "No sequence numbers left in the active CAI range for issuance point 001-001-01.",
  "instance": "/api/v1/payments/9f1c.../invoice",
  "traceId": "01J8Z4K2QF9P0X",
  "errors": []
}
```

**La interfaz nunca muestra `title` ni `detail` del servidor.** Ambos vienen en inglés y en
lenguaje técnico. La interfaz mapea `type` a una clave de internacionalización propia. Si el `type`
no está en el mapa, se usa el mensaje genérico y se registra el hallazgo.

| Tipo de error | Estado | Mensaje mostrado al usuario | Acción ofrecida |
|---|---|---|---|
| `cai-range-exhausted` | 409 | "No quedan correlativos disponibles en el rango CAI vigente. No se puede emitir la factura hasta registrar un rango nuevo autorizado por el SAR. El pago no se registró." | "Avisar a contabilidad" y "Volver al cobro" |
| `cai-range-expired` | 409 | "El rango CAI vigente venció el 31/08/2026. No se puede facturar con un rango vencido. El pago no se registró." | "Ver rangos CAI" (si tiene permiso) y "Volver al cobro" |
| `cash-session-not-open` | 409 | "No tiene una sesión de caja abierta. Para cobrar en efectivo primero debe abrir su caja con el fondo inicial." | "Abrir sesión de caja" |
| `cash-session-already-open` | 409 | "Ya tiene una sesión de caja abierta en Caja 1 desde las 07:45. Solo puede tener una sesión abierta a la vez." | "Ir a mi sesión de caja" |
| `cash-session-closed` | 409 | "La sesión de caja ya está cerrada y no acepta movimientos nuevos. Abra una sesión nueva para seguir cobrando." | "Abrir sesión de caja" |
| `over-allocation` | 422 | "No se puede aplicar L 600.00 al cargo Colegiatura Julio 2026, porque su saldo pendiente es L 500.00. Ajuste la imputación." | "Revisar la imputación" |
| `charge-already-settled` | 409 | "El cargo Colegiatura Julio 2026 ya fue cancelado por otro pago mientras usted capturaba. Vuelva a cargar los cargos pendientes antes de cobrar." | "Actualizar cargos pendientes" |
| `fiscal-document-already-voided` | 409 | "La factura 001-001-01-00004518 ya fue anulada el 09/09/2026 por Rina Contreras. No se puede anular dos veces." | "Ver la factura" |
| `credit-note-exceeds-document` | 422 | "La nota de crédito no puede acreditar L 4,000.00, porque la factura 001-001-01-00004518 tiene solo L 3,150.00 sin acreditar." | "Corregir el monto" |
| `approval-required` | 403 | "Esta operación necesita la aprobación de otra persona. Se envió la solicitud a Contabilidad y quedará en Aprobaciones pendientes." | "Ver aprobaciones pendientes" |
| `approver-is-initiator` | 403 | "No puede aprobar una operación que usted mismo inició. La debe aprobar Contabilidad o un Super Administrador." | "Volver a la lista" |
| `insufficient-permission` | 403 | "Su rol no tiene permiso para esta operación. Si la necesita para su trabajo, solicítela a un Super Administrador." | "Volver" |
| `session-expired` | 401 | "Su sesión se cerró por seguridad. Vuelva a iniciar sesión. Lo que había capturado en esta pantalla no se perdió." | "Iniciar sesión de nuevo" |
| `mfa-attempts-exceeded` | 429 | "Superó los intentos permitidos del código de verificación. Espere 15 minutos o use uno de sus códigos de recuperación." | "Usar un código de recuperación" |
| `rate-limited` | 429 | "Hizo demasiadas solicitudes seguidas. Espere unos segundos e intente de nuevo." | "Reintentar" con cuenta regresiva |
| `idempotency-conflict` | 409 | "Esta operación ya se registró. No se cobró dos veces. Se muestra el resultado del registro original." | "Ver el pago registrado" |
| `currency-mismatch` | 422 | "No se pueden mezclar lempiras y dólares en una misma operación. Registre un pago por cada moneda." | "Corregir la moneda" |
| `student-without-guardian` | 422 | "Marlen Sofía Cruz Andino no tiene un encargado de pago vigente. No hay a quién emitir la factura." | "Asignar encargado de pago" |
| `price-not-effective` | 422 | "El concepto Transporte no tiene una tarifa vigente para el 09/09/2026. Registre una vigencia antes de generar el cargo." | "Ver listas de precios" |
| `academic-year-closed` | 409 | "El año lectivo 2025 está cerrado. No se pueden registrar cobros ni emitir documentos en un año cerrado." | "Cambiar al año lectivo 2026" |
| `enrollment-inactive` | 409 | "Marlen Sofía Cruz Andino no tiene matrícula activa en el año lectivo 2026. Verifique su matrícula antes de cobrar." | "Ver matrícula" |
| `import-file-invalid` | 422 | "El archivo no se pudo leer. Se esperaba un archivo CSV con las columnas Fecha, Referencia, Descripción y Monto. Se encontró una columna faltante en la fila 1." | "Descargar plantilla de ejemplo" |
| `ledger-account-under-review` | 409 | "La cuenta de Marlen Sofía Cruz Andino está en revisión por una diferencia detectada en el libro mayor. No se pueden registrar movimientos hasta que contabilidad la libere." | "Avisar a contabilidad" |
| `validation-error` | 400 | Se distribuye por campo desde el arreglo `errors`, no se muestra como mensaje global | Foco al primer campo con error |
| Cualquier `type` desconocido | 500 y otros | "Ocurrió un error inesperado y la operación no se completó. Nada quedó registrado a medias. Comparta este identificador con soporte: {{traceId}}" | "Reintentar" y "Copiar identificador" |

Reglas de presentación del error:

1. **El identificador de traza siempre es visible y copiable** en el panel administrativo. En el
   portal no se muestra: se sustituye por el teléfono de la administración.
2. Un error de estado 5xx nunca ofrece "Reintentar" en una operación financiera sin decir antes si
   la operación se registró. Cuando el cliente no puede saberlo, el texto es:
   `admin.error.payment_unknown = "No se pudo confirmar si el cobro quedó registrado. NO vuelva a cobrar. Busque al estudiante en Estados de cuenta y verifique antes de intentarlo de nuevo."`
3. Los reintentos automáticos de TanStack Query están **prohibidos en mutaciones financieras**. Las
   consultas de lectura sí reintentan hasta dos veces con retroceso exponencial.

---

## 10. Búsqueda y filtrado

### 10.1 Búsqueda global del panel

| Aspecto | Decisión |
|---|---|
| Apertura | Tecla `/` desde cualquier pantalla, o clic en el campo de la barra superior |
| Retardo de escritura | **300 ms** desde la última tecla |
| Mínimo de caracteres | 3, salvo que la cadena sea numérica: con 2 dígitos ya busca por código de estudiante y por correlativo |
| Ámbitos | Estudiantes, encargados de pago, documentos fiscales por correlativo, pagos por número de recibo, cargos por identificador |
| Presentación | Resultados agrupados por tipo, con encabezado de grupo, máximo cinco por grupo |
| Navegación | Flechas arriba y abajo, `Enter` para abrir, `Escape` para cerrar. El foco vuelve al elemento que abrió la búsqueda |
| Sin resultados | "No se encontró nada para «cruz andino». Pruebe con el código del estudiante o con el número de recibo." |
| Datos de menores | Los resultados muestran nombre, código, grado y sección. **Nunca el documento de identidad ni la fecha de nacimiento** |

### 10.2 Filtros por facetas en listados

| Aspecto | Decisión |
|---|---|
| Ubicación | Barra horizontal sobre la tabla. Nunca un panel lateral que tape las filas |
| Aplicación | Inmediata al seleccionar en un desplegable. El campo de texto libre aplica con retardo de 300 ms |
| Visibilidad de lo activo | Fichas removibles bajo la barra, con el nombre del filtro y su valor, más "Limpiar todo" |
| Conteo por faceta | Solo cuando el servidor lo devuelve sin costo adicional. Nunca se calcula en el navegador |
| Filtros de fecha | Atajos concretos del dominio: "Este mes", "Mes anterior", "Año lectivo completo", "Rango personalizado" |
| Persistencia | En la URL. Ver sección 3.2 |

### 10.3 Vistas guardadas

Una vista guardada almacena el conjunto completo de parámetros de la URL más el nombre que le puso
la persona usuaria. Sirve a Doña Rina, que repite la misma consulta cada cierre de mes.

| Regla | Detalle |
|---|---|
| Alcance | Privada por omisión. Compartir con el equipo es una acción explícita |
| Aplicación | Al aplicarla, sus parámetros se expanden a la URL, para que la URL siga siendo compartible sin la vista |
| Vista por omisión | Cada persona puede marcar una vista como la que se abre al entrar a esa pantalla |
| Nombre | Obligatorio, máximo 40 caracteres. Ejemplo real: "Mora mayor a 60 días, bachillerato" |

### 10.4 Sin resultados

Nunca se muestra una tabla vacía sin explicación. El estado de sin resultados repite los filtros
aplicados en texto y ofrece quitar el más restrictivo:

```
admin.table.no_results.title  = "Ningún cargo coincide con los filtros"
admin.table.no_results.body   = "Filtros activos: estado Vencido, grado 7 y 8, vence entre 01/08/2026 y 31/08/2026."
admin.table.no_results.clear_one = "Quitar el filtro de fechas"
admin.table.no_results.clear_all = "Limpiar todos los filtros"
```

---

## 11. Impresión y exportación

### 11.1 Criterio de formato

| Formato | Cuándo se usa | Cuándo NO se usa |
|---|---|---|
| **CSV** | Cuando el destino es otro sistema o una hoja de cálculo que va a recalcular: exportación contable de asientos, padrón de estudiantes para migración, partidas bancarias | Cuando el resultado se va a leer tal cual por una persona. El CSV pierde formato de moneda y el separador decimal se malinterpreta |
| **Excel** | Cuando la persona va a trabajar el archivo: antigüedad de saldos, cargos del período, libro de ventas, cierres de caja. Lleva encabezados congelados, anchos de columna, formato de moneda con dos decimales, totales al pie y una hoja "Filtros aplicados" con la consulta exacta | Para un documento que se firma o se entrega a un tercero |
| **PDF** | Cuando el documento se imprime, se firma, se archiva o se entrega: factura, nota de crédito, recibo, estado de cuenta del estudiante, reporte de cierre de caja, reporte de dirección para junta directiva | Cuando alguien va a sumar o filtrar el contenido después |

Regla transversal: **toda exportación respeta exactamente los filtros de la URL** y registra en la
bitácora el filtro aplicado y el número de filas, según `../03-seguridad.md` sección 2.2.

Exportaciones de más de 5,000 filas se procesan en cola y avisan al terminar:

```
admin.export.queued  = "Se está preparando la exportación de 12,480 filas. Le avisamos cuando esté lista."
admin.export.ready   = "La exportación de Antigüedad de saldos ya está lista."
admin.export.ready.action = "Descargar archivo"
```

### 11.2 Diseño de impresión del recibo de cobro

Formato objetivo: papel térmico de 80 mm y también media carta. Se usa una hoja de estilos de
impresión, no una captura de pantalla.

> **Los RTN de este ejemplo son ficticios y su longitud no está confirmada.** Aparecen con catorce y
> con trece dígitos solo como ilustración de maqueta; no son normativos. El formato vigente del RTN
> ante el SAR (longitud, máscara y posible dígito verificador) sigue **pendiente de validación**
> según `docs/04-cumplimiento-fiscal-sar.md`, sección 1. Nadie debe deducir el formato real de esta
> plantilla.

```
        INSTITUTO SAN JOSÉ
     RTN 08019012345678
   Col. Las Colinas, Tegucigalpa
        Tel. 2222-0000
---------------------------------
FACTURA
CAI 3B4C1D-9E2F0A-7B8C6D-1A2B3C-4521
No. 001-001-01-00004518
Fecha límite de emisión 31/12/2026
Rango autorizado 00004001 al 00005000
---------------------------------
Fecha    09/09/2026  14:32
Cajero   Wilmer Josué Fúnez
Caja     Caja 1
---------------------------------
Cliente  Rosa Elena Andino Discua
RTN      0801198200123
Estudiante Marlen Sofía Cruz Andino
         EST-2026-0418, 7.º A
---------------------------------
CONCEPTO                    IMPORTE
Recargo por mora Jul 2026   L   122.50
Colegiatura Julio 2026      L 2,450.00
Transporte Agosto 2026      L   577.50
---------------------------------
Subtotal                    L 3,150.00
Descuentos                  L     0.00
Importe exento              L 3,150.00
ISV 15 %                    L     0.00
TOTAL A PAGAR               L 3,150.00
---------------------------------
Recibido en efectivo        L 3,500.00
Cambio                      L   350.00
---------------------------------
Saldo del estudiante después
de este pago                L   272.50
---------------------------------
   Original: cliente
   La factura es un documento fiscal.
   Consérvela.
---------------------------------
```

Reglas del recibo impreso:

1. El CAI, el correlativo, la fecha límite de emisión y el rango autorizado son obligatorios y no
   se abrevian. El texto legal exacto exigido por el SAR **está pendiente de validación con el
   contador de la institución** y no se inventa en este documento. Ver `../00-vision-y-alcance.md`
   supuesto S-02.
2. Los importes se alinean a la derecha y llevan dos decimales, igual que en pantalla.
3. Nada de color: el recibo debe leerse igual en una impresora térmica monocromática.
4. Nunca se imprimen datos del menor más allá de su nombre, código, grado y sección.

### 11.3 Diseño de impresión del estado de cuenta

Una hoja carta vertical, con:

| Bloque | Contenido |
|---|---|
| Encabezado | Nombre legal de la institución, RTN, dirección, teléfono, logo monocromático |
| Identificación | Estudiante con código, grado, sección, año lectivo. Encargado de pago responsable |
| Corte | "Estado de cuenta al 09/09/2026, 14:32". Obligatorio: un saldo sin fecha de corte no se imprime |
| Resumen | Total cargado, total pagado, total de descuentos y becas, recargos, saldo pendiente, saldo a favor si lo hay |
| Detalle | Tabla cronológica: fecha, concepto, documento, cargo, abono, saldo acumulado. Encabezado repetido en cada página |
| Antigüedad | Corriente, 1 a 30 días, 31 a 60, 61 a 90, más de 90 |
| Pie | Numeración "Página 1 de 3", identificador del documento y aviso de que es informativo y no sustituye un documento fiscal |

---

## 12. Atajos de teclado del panel

Diseñados para el flujo de ventanilla de Wilmer (perfil 4.1). Ningún atajo pisa un atajo estándar
del navegador. Los atajos de una sola tecla solo actúan cuando el foco **no** está en un campo de
texto.

| Atajo | Acción | Ámbito |
|---|---|---|
| `/` | Enfocar la búsqueda global | Global |
| `?` | Abrir la lista de atajos | Global |
| `g` luego `c` | Ir a Cobrar en ventanilla | Global |
| `g` luego `s` | Ir a Mi sesión de caja | Global |
| `g` luego `e` | Ir a Estudiantes | Global |
| `g` luego `a` | Ir a Cobranza, Atrasos | Global |
| `g` luego `f` | Ir a Facturación, Documentos fiscales | Global |
| `g` luego `i` | Ir al Panel de control | Global |
| `Alt` + `N` | Acción primaria de la pantalla actual | Global |
| `Escape` | Cerrar diálogo, menú o panel. Devuelve el foco al origen | Global |
| `Alt` + `F` | Abrir o cerrar la barra de filtros | Listados |
| `Alt` + `L` | Limpiar todos los filtros | Listados |
| `Alt` + `E` | Exportar con los filtros actuales | Listados |
| `Alt` + flecha izquierda o derecha | Página anterior o siguiente | Listados |
| `Alt` + `S` | Guardar la vista actual | Listados |
| `Enter` | Seleccionar el resultado enfocado de la búsqueda de estudiante | Cobrar en ventanilla |
| `Alt` + `1` | Volver al paso 1, identificar estudiante | Cobrar en ventanilla |
| `Alt` + `2` | Ir al paso 2, elegir qué se cobra | Cobrar en ventanilla |
| `Alt` + `3` | Ir al paso 3, capturar el pago | Cobrar en ventanilla |
| `Alt` + `M` | Enfocar el campo Monto recibido | Cobrar en ventanilla |
| `Alt` + `Enter` | Abrir el diálogo de confirmación de cobro | Cobrar en ventanilla |
| `Alt` + `P` | Imprimir el comprobante mostrado | Resultado del cobro |
| `Alt` + `O` | Cobrar a otro estudiante y reiniciar el asistente | Resultado del cobro |
| `Alt` + `K` | Abrir o cerrar la barra lateral | Global |

Reglas:

1. **Ningún atajo ejecuta directamente una operación irreversible.** `Alt` + `Enter` abre el diálogo
   de confirmación, no cobra.
2. La lista de atajos se abre con `?` y también desde el menú de usuario, para quien no conoce la
   tecla.
3. Los atajos se anuncian en la etiqueta accesible del control correspondiente, con formato
   "Cobrar e imprimir (Alt más Enter)".

---

## 13. Comportamiento ante error de red y sin conexión

### 13.1 Regla de integridad financiera

> ## ADVERTENCIA CRÍTICA
> **NUNCA se registra un pago, un cierre de caja, una factura, una nota de crédito ni ningún otro
> movimiento financiero de forma optimista. NINGUNA operación financiera se muestra como
> completada antes de recibir la respuesta del servidor. NO existe modo sin conexión para cobrar.
> NO existe cola local de pagos pendientes de enviar. Si no hay respuesta del servidor, no hay
> cobro, y la interfaz debe decirlo con esas palabras.**

Motivo: un pago mostrado como registrado y nunca persistido produce un recibo entregado sin asiento
contable, es decir un faltante de caja sin origen. El costo de que el cajero espere dos segundos es
infinitamente menor que el costo de reconstruir esa jornada.

Las actualizaciones optimistas sí se permiten en operaciones no financieras y reversibles: marcar
un aviso como leído, cambiar la densidad de la tabla, reordenar columnas, guardar una vista.

### 13.2 Detección y presentación

| Situación | Detección | Qué muestra la interfaz | Qué permite hacer |
|---|---|---|---|
| Sin conexión de red | Evento `offline` del navegador | Banner persistente rojo en la parte superior: "Sin conexión. No se pueden registrar cobros hasta que vuelva la conexión." | Consultar lo ya cargado en caché. El botón de cobro queda inoperante con explicación visible |
| Conexión recuperada | Evento `online` | El banner cambia a informativo por 5 segundos: "Conexión restablecida. Verifique el último cobro antes de continuar." | Todo |
| Servidor no responde a una lectura | Tiempo de espera agotado o error 5xx | Estado de error total de la sección 6, con reintento | Reintentar |
| Servidor no responde a una escritura financiera | Tiempo de espera agotado, error de red, o 5xx | Diálogo bloqueante, nunca notificación emergente. Ver texto abajo | Verificar antes de reintentar |
| Servidor responde 409 `idempotency-conflict` | Respuesta del servidor | "Esta operación ya se registró. No se cobró dos veces." | Ver el pago registrado |

Texto exacto del diálogo de escritura financiera sin respuesta:

```
admin.network.write_unknown.title  = "No sabemos si el cobro quedó registrado"
admin.network.write_unknown.body   = "Se envió el cobro de L 3,150.00 de Marlen Sofía Cruz Andino, pero el servidor no respondió a tiempo. NO vuelva a cobrar todavía: es posible que sí se haya registrado."
admin.network.write_unknown.step1  = "1. Verifique el estado de cuenta del estudiante."
admin.network.write_unknown.step2  = "2. Si el pago aparece, entregue el comprobante desde ahí."
admin.network.write_unknown.step3  = "3. Si no aparece, vuelva a registrarlo. El sistema no permitirá un cobro duplicado con la misma operación."
admin.network.write_unknown.verify = "Verificar el estado de cuenta"
admin.network.write_unknown.retry  = "Ya verifiqué, reintentar el cobro"
```

### 13.3 Idempotencia en el cliente

| Regla | Detalle |
|---|---|
| Generación de la clave | Un identificador UUID versión 4 generado **al abrir el diálogo de confirmación**, no al pulsar el botón |
| Reutilización | El reintento del mismo cobro reutiliza exactamente la misma clave. Un cobro nuevo genera una clave nueva |
| Invalidación | La clave se descarta al cerrar el diálogo sin confirmar o al completar el cobro con éxito |
| Doble envío | El botón queda bloqueado desde el primer clic hasta la respuesta. La clave es la segunda barrera, no la primera |
| Ámbito | Toda escritura financiera: registrar pago, emitir factura, emitir nota de crédito, anular, cerrar caja, generar cargos, registrar promesa |

### 13.4 Portal de encargados

En el portal la regla es la misma, con distinto tono. El encargado nunca ve un comprobante de
transferencia como enviado si el servidor no lo confirmó:

```
portal.network.offline.title = "No hay conexión"
portal.network.offline.body  = "Revise sus datos o su wifi. Lo que ya cargó puede seguir viéndolo, pero no podemos recibir su reporte de transferencia hasta que vuelva la conexión."
portal.network.write_unknown.title = "No pudimos confirmar su reporte"
portal.network.write_unknown.body  = "Su reporte de transferencia no llegó a registrarse con seguridad. Vuelva a entrar en unos minutos y revise Estado de cuenta. Si no aparece, repórtelo de nuevo. Si tiene dudas, llame a la administración al 2222-0000."
```

---

## 14. Documentos relacionados

| Documento | Contenido |
|---|---|
| `00-principios-de-diseno.md` | Principios rectores, perfiles y antipatrones. Incluye los tokens de color, tipografía y espaciado vigentes |
| `05-guia-de-contenido-y-voz.md` | Voz, formato de datos y catálogo de mensajes |
| `06-flujos-clave.md` | Los trece recorridos críticos con diagramas |
| `../01-arquitectura.md` | Fuente de verdad técnica, separación panel y portal, contrato de API |
| `../02-modelo-de-dominio.md` | Lenguaje ubicuo, invariantes y máquinas de estado |
| `../03-seguridad.md` | Matriz de permisos por rol y segregación de funciones |
| `../10-analisis-de-brechas.md` | Origen de los módulos de caja, conciliación, notas de crédito y auditoría |
