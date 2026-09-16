# CONFIA. Principios de diseño

> Estado: **Propuesta v1.0**
> Alcance: panel administrativo (`apps/admin-web`) y portal de encargados (`apps/portal-web`).
> Este documento es la fuente de verdad de las decisiones de producto y experiencia.
> Si un componente, una pantalla o una especificación contradice estos principios, el defecto
> está en el componente, no en el principio.

---

## 1. Por qué existen estos principios

CONFIA mueve dinero real de familias reales y registra documentos fiscales con validez legal
ante el SAR. Una interfaz confusa en una aplicación de notas produce molestia. Una interfaz
confusa aquí produce un cobro duplicado, una factura anulada mal, un padre que cree que ya pagó
o una caja que no cuadra al cierre.

Por eso el criterio de calidad de la interfaz no es "se ve bien". Es:

> **Una persona con prisa, cansada, con una fila esperando, debe poder hacer lo correcto sin
> pensarlo dos veces, y debe ser difícil hacer lo incorrecto sin darse cuenta.**

---

## 2. Las tres preguntas

Toda pantalla del sistema, sin excepción, debe poder responder estas tres preguntas o dejar
evidente dónde se responden.

| Pregunta | Qué significa en CONFIA | Dónde vive la respuesta |
|---|---|---|
| **¿Cuánto se debe?** | El saldo pendiente derivado del libro mayor, con su desglose por concepto y su mora acumulada. Nunca un número sin fecha de corte. | `AccountSummary`, `StatCard` de saldo, columna "Saldo" de los listados |
| **¿Qué se pagó?** | El historial verificable: fecha, monto, método, quién lo registró, a qué cargos se aplicó y qué documento fiscal se emitió. | `LedgerTimeline`, detalle de pago, `PaymentReceipt` |
| **¿Qué sigue?** | La acción disponible ahora: registrar pago, generar cargo, enviar notificación, registrar promesa, cerrar caja, o simplemente "nada pendiente". | `PageHeader` con acción primaria, `EmptyState` con acción, banda de alertas |

**Prueba de aceptación.** Toma cualquier maqueta o pantalla implementada. Si un revisor no puede
señalar con el dedo dónde se responde cada una de las tres, la pantalla no está terminada.

Ejemplo de violación real: un listado de estudiantes que solo muestra nombre, grado y sección.
Responde "quién existe", no responde ninguna de las tres. Corrección: agregar la columna de saldo
con su estado, la fecha del último pago y la acción de registrar pago en la fila.

---

## 3. Principios rectores

### Principio 1. Claridad sobre elegancia

**Regla accionable.** Cuando una decisión visual mejore la estética y reduzca la legibilidad, la
comprensión o la velocidad de lectura, se descarta. Los importes, los estados y las fechas se
optimizan para lectura rápida, no para composición.

**Qué lo viola.**

- Texto secundario en gris claro sobre fondo claro "porque se ve más limpio".
- Una tarjeta de indicador que muestra `L 1.2M` en lugar de `L 1,248,350.00` porque el número
  completo "rompe la retícula".
- Un encabezado de tabla que dice "Monto" cuando existen tres montos distintos en el sistema
  (cargado, pagado, saldo).
- Iconos sin etiqueta en la barra lateral para "ganar espacio". La barra colapsada es una opción
  del usuario, no un valor por omisión de diseño.

**Cómo se resuelve el conflicto.** Si el diseño requiere abreviar, se abrevia el texto de apoyo,
nunca la cifra ni el estado.

---

### Principio 2. El dinero nunca es ambiguo

**Regla accionable.** Todo importe visible en la interfaz cumple, sin excepción, las cinco
condiciones siguientes:

1. Lleva símbolo de moneda explícito (`L` para lempiras, `$` para dólares con prefijo `USD`
   cuando ambas monedas conviven en la misma vista).
2. Lleva separador de miles y **siempre** dos decimales, incluso cuando son `.00`.
3. Se alinea a la derecha en tablas y usa numeración tabular (`font-variant-numeric: tabular-nums`).
4. Declara su naturaleza: cargo, pago, descuento, mora, saldo. Un número suelto no existe.
5. Declara su fecha de corte cuando es un saldo derivado.

**Qué lo viola.**

- `1200` en una celda. No se sabe si son lempiras, dólares, cargos o saldo.
- `L 1,200` sin decimales.
- Un saldo mostrado como `L 0` cuando en realidad es `L 0.00` a favor de la institución y otro
  caso distinto es `L 0.00` con crédito a favor del estudiante. Son estados distintos y deben
  verse distintos.
- Importes centrados o alineados a la izquierda en una tabla. Impide comparar magnitudes de un
  vistazo, que es exactamente lo que hace una contadora.
- Redondear en la interfaz. El redondeo es una regla de dominio y se aplica en el punto de
  emisión fiscal, no en el componente de React.

**Consecuencia de arquitectura.** El componente `MoneyAmount` es obligatorio. Ningún componente
puede interpolar un importe en una plantilla de texto.

---

### Principio 3. Cada pantalla responde una pregunta del usuario

**Regla accionable.** Cada ruta del sistema tiene una y solo una pregunta principal declarada en
su especificación. La acción primaria de la pantalla, única y visualmente dominante, es la
respuesta a esa pregunta. Todo lo demás es secundario y se ve secundario.

| Ruta | Pregunta principal | Acción primaria |
|---|---|---|
| `/caja/cobrar` | ¿Cuánto le cobro a esta persona ahora? | Registrar pago |
| `/estudiantes/:id/estado-cuenta` | ¿Cómo está la cuenta de este estudiante? | Registrar pago |
| `/cobranza/atrasos` | ¿A quién hay que cobrarle hoy? | Enviar notificación |
| `/caja/cierre` | ¿Cuadra mi caja? | Cerrar caja |
| `/facturacion/documentos` | ¿Qué documentos fiscales emití? | Exportar libro de ventas |
| `/reportes/:id` | ¿Cómo va el mes? | Exportar |
| Portal `/` | ¿Cuánto debo y de qué? | Ver detalle o pagar |

**Qué lo viola.**

- Un dashboard con doce indicadores, ninguno destacado y cuatro botones del mismo peso visual.
- Una pantalla de estado de cuenta que además permite editar los datos del estudiante, cambiar
  su beca y enviar un correo, todo con botones del mismo tamaño.
- Dos botones primarios en la misma vista.

---

### Principio 4. Las acciones irreversibles se ganan la confirmación

**Regla accionable.** La fricción de una acción es proporcional al daño que causa si se ejecuta
por error. Nunca menos, nunca más. Una confirmación en una acción inocua entrena a la persona
usuaria a confirmar sin leer, y esa costumbre es la que después hace que anule una factura sin
pensarlo.

| Impacto | Fricción exigida |
|---|---|
| Reversible, sin efecto fiscal (marcar un filtro, ocultar una columna) | Ninguna |
| Reversible con esfuerzo (archivar un concepto de pago) | Notificación emergente con acción de deshacer |
| Irreversible, sin efecto fiscal (cerrar sesión de caja) | `ConfirmDialog` con resumen de consecuencia |
| Irreversible con efecto fiscal o contable (anular factura, registrar pago, aplicar reverso) | `DestructiveConfirmDialog` con escritura de palabra de confirmación y motivo obligatorio |

**Qué lo viola.**

- Un botón "Anular" en una fila de tabla que ejecuta al primer clic.
- Un diálogo que dice "¿Está seguro?" sin decir qué se va a anular, por cuánto, de quién y qué
  documento se genera en su lugar.
- Pedir confirmación para exportar un CSV.
- Un botón destructivo del mismo color y peso que el botón primario, pegado a él.

**Regla adicional.** En CONFIA nada financiero se borra. La interfaz nunca usa la palabra
"Eliminar" sobre un movimiento de dinero. Usa "Anular", "Reversar" o "Emitir nota de crédito",
que es lo que realmente ocurre.

---

### Principio 5. El sistema siempre dice en qué estado está

**Regla accionable.** En todo momento la persona usuaria puede responder: qué está pasando, si
terminó, si falló, y qué datos está viendo. Nunca hay una pantalla que simplemente no se mueve.

Aplicaciones concretas:

- Toda operación de más de 300 ms muestra retroalimentación. Esqueleto para contenido que llega,
  indicador giratorio dentro del botón para una acción que se ejecuta.
- Toda tabla declara sobre qué universo de datos opera: "Mostrando 25 de 1,340 registros.
  Filtros activos: año 2026, grado 7." Un total sin contexto es una mentira útil para nadie.
- Toda sesión de caja abierta se ve permanentemente en la barra superior, con su hora de apertura
  y su total acumulado.
- Todo dato derivado declara su origen y su frescura: "Saldo al 09/09/2026 14:32."
- Un año lectivo distinto al vigente se anuncia con un banner persistente, no con un texto
  discreto. Estar viendo el año pasado y no notarlo es una fuente real de error de cobro.

**Qué lo viola.**

- Un botón "Guardar" que al pulsarlo no cambia y la persona lo pulsa tres veces. En un endpoint
  financiero esto es exactamente lo que la clave de idempotencia existe para atrapar, pero la
  interfaz no debe delegar su trabajo al backend.
- Una tabla vacía sin decir si está cargando, si falló o si de verdad no hay resultados.
- Un cierre de caja que se procesa sin indicar el avance en una operación que puede tardar.

---

### Principio 6. Accesible por omisión

**Regla accionable.** WCAG 2.2 nivel AA es criterio de aceptación en la definición de terminado,
no una mejora posterior. Un componente que no cumple no se fusiona.

Reglas duras, verificadas en integración continua:

- Contraste mínimo 4.5 a 1 para texto y 3 a 1 para elementos de interfaz y bordes que definen un
  control. Los valores verificados están en `01-tokens-de-diseno.md`.
- El color nunca comunica solo. Todo estado financiero se expresa con color **y** icono **y**
  texto.
- Todo control es operable con teclado, con foco visible de al menos 2 px y contraste suficiente.
- Objetivos táctiles de al menos 44 por 44 píxeles en el portal y 32 por 32 con separación de
  8 px en las tablas densas del panel, según el criterio 2.5.8 de tamaño mínimo.
- Se respeta `prefers-reduced-motion` en todas las transiciones.
- Zoom hasta 200 por ciento sin pérdida de contenido ni desplazamiento horizontal.

**Qué lo viola.**

- Una fila de tabla en rojo claro para indicar mora, sin icono ni etiqueta. Una persona con
  deuteranopía ve una fila normal y cobra mal.
- `outline: none` en un input "porque el anillo azul se ve feo".
- Un menú desplegable que solo se abre al pasar el cursor.
- Un icono de papelera sin `aria-label` en una tabla de cien filas. El lector de pantalla anuncia
  cien botones idénticos.

---

### Principio 7. La densidad se gana, no se asume

**Regla accionable.** El panel administrativo es denso porque su usuario es un profesional que
trabaja ocho horas en él y necesita ver muchas filas a la vez. El portal es amplio porque su
usuario entra tres minutos al mes, desde el teléfono, y no conoce el sistema. La densidad es una
decisión por aplicación y por vista, nunca un accidente.

**Qué lo viola.**

- Llevar la tabla densa del panel, con doce columnas y filas de 32 píxeles, al portal móvil.
- Poner tarjetas grandes con mucho aire en la pantalla de cobro en ventanilla, obligando al
  cajero a desplazarse para ver los cargos pendientes con una fila de padres esperando.
- Un mismo componente `DataTable` sin control de densidad, forzando a duplicar el componente.

---

## 4. Perfiles de usuario

Los perfiles no son decoración. Cada decisión de esta guía se justifica contra al menos uno.

### 4.1 Cajero en ventanilla

| Atributo | Detalle |
|---|---|
| **Nombre de referencia** | Wilmer, auxiliar de caja |
| **Objetivo** | Cobrar y entregar el recibo correcto en menos de noventa segundos por persona |
| **Contexto de uso** | Ventanilla, ocho a diez de la mañana en día de pago, fila de doce personas, ruido, interrupciones, el teléfono sonando |
| **Dispositivo** | Computadora de escritorio, monitor de 1366 por 768, teclado completo, impresora térmica o láser conectada |
| **Frecuencia** | Continua, todo el día, todos los días |
| **Competencia digital** | Alta en este sistema específico, baja en general. Memoriza rutas y atajos |
| **Frustraciones** | Tener que usar el ratón para todo. Buscar un estudiante y que la búsqueda tarde. No saber si el pago ya se registró y arriesgarse a duplicarlo. Descubrir al cierre que faltan 200 lempiras y no poder rastrear cuándo |
| **Qué exige del diseño** | Búsqueda con foco automático al cargar la pantalla. Atajos de teclado. Flujo de cobro sin cambio de página. Confirmación que muestra el desglose exacto antes de emitir. Impresión inmediata. Estado de la sesión de caja siempre visible |

### 4.2 Contadora cerrando el mes

| Atributo | Detalle |
|---|---|
| **Nombre de referencia** | Doña Rina, contabilidad |
| **Objetivo** | Cuadrar el libro de ventas contra el sistema, justificar cada anulación y entregar el reporte al SAR sin sorpresas |
| **Contexto de uso** | Últimos tres días del mes, escritorio propio, sesiones largas de dos a cuatro horas, hoja de cálculo abierta al lado |
| **Dispositivo** | Computadora de escritorio, dos monitores, Excel abierto permanentemente |
| **Frecuencia** | Diaria en consulta, intensa al cierre |
| **Competencia digital** | Media alta. Domina Excel mejor que cualquier sistema web |
| **Frustraciones** | Exportaciones que no cuadran con lo que ve en pantalla. Filtros que se pierden al navegar y volver. Correlativos con huecos sin explicación. No poder ver quién hizo un ajuste y por qué. Tener que pedirle al desarrollador una consulta a la base de datos |
| **Qué exige del diseño** | Filtros persistidos en la URL, compartibles y restaurados al volver. Exportación que respeta exactamente los filtros visibles. Trazabilidad completa: cada movimiento con autor, fecha, motivo y documento asociado. Vistas guardadas. Totales al pie de la tabla, no solo en la página visible |

### 4.3 Directora revisando indicadores

| Atributo | Detalle |
|---|---|
| **Nombre de referencia** | Licenciada Padilla, dirección |
| **Objetivo** | Saber en treinta segundos si la recaudación va bien, cuánta cartera está vencida y si hay algo que atender hoy |
| **Contexto de uso** | Entre reuniones, a veces desde el teléfono, a veces proyectando en junta directiva |
| **Dispositivo** | Portátil y teléfono, en proporción similar |
| **Frecuencia** | Dos o tres veces por semana, más al cierre de mes |
| **Competencia digital** | Media. No va a aprender atajos ni filtros avanzados |
| **Frustraciones** | Dashboards con muchos números y ninguna conclusión. No entender si un número es bueno o malo. Gráficos que no se leen en el proyector. Tener que pedirle el reporte a contabilidad |
| **Qué exige del diseño** | Pocos indicadores, grandes, con comparación contra el periodo anterior y una lectura explícita del sentido ("12% por encima de agosto"). Alertas accionables arriba. Gráficos legibles con etiquetas directas. Exportación a PDF presentable |

### 4.4 Encargado de pago

| Atributo | Detalle |
|---|---|
| **Nombre de referencia** | Doña Marlen, madre de dos estudiantes |
| **Objetivo** | Saber cuánto debe, de qué es, hasta cuándo tiene plazo, y comprobar que el pago que hizo la semana pasada ya está aplicado |
| **Contexto de uso** | De noche, en su casa o en el transporte, con poca señal, a veces con la pantalla del teléfono quebrada, a veces con un plan de datos limitado |
| **Dispositivo** | Teléfono Android de gama media, pantalla de 360 a 412 píxeles de ancho. Un porcentaje minoritario entra desde computadora |
| **Frecuencia** | Dos o tres veces al mes. Picos en fecha de vencimiento |
| **Competencia digital** | Muy variable. Desde quien usa banca en línea a diario hasta quien solo usa WhatsApp |
| **Frustraciones** | No entender qué significa un concepto de pago. Ver un saldo distinto al que le dijeron en la escuela. Hacer una transferencia y no saber si la recibieron. Formularios largos. Tener que crear otra contraseña |
| **Qué exige del diseño** | Móvil primero, real. Una sola cifra dominante al abrir. Lenguaje sin jerga contable. Cero tablas de doce columnas. Botones grandes. Confirmación explícita y visible de que una transferencia declarada fue recibida y en qué estado está. Comprobante descargable |

### 4.5 Perfiles secundarios

| Perfil | Objetivo | Nota de diseño |
|---|---|---|
| **Coordinador académico** | Ver qué estudiantes de su grado tienen mora antes de una actividad | Acceso de solo lectura, sin importes detallados si la política lo restringe. Necesita listado filtrable y exportable, no capacidad de cobro |
| **Auditor** | Reconstruir qué pasó con una transacción específica | Solo lectura absoluta. Necesita búsqueda por correlativo, por identificador de transacción y línea de tiempo completa con cadena de auditoría |
| **Administrador del sistema** | Configurar CAI, tarifas, usuarios y permisos | Baja frecuencia, alto impacto. Cada pantalla de configuración debe explicar la consecuencia del cambio antes de guardarlo |

---

## 5. Diferencias deliberadas entre el panel y el portal

No son dos temas visuales del mismo producto. Son dos productos con la misma identidad y
decisiones opuestas donde corresponde.

| Dimensión | Panel administrativo | Portal de encargados | Por qué |
|---|---|---|---|
| **Densidad** | Alta. Filas de tabla de 36 px en densidad cómoda y 32 px en compacta. Hasta 14 columnas. Espaciado base de 4 px | Baja. Tarjetas con 16 a 24 px de relleno, una idea por bloque, listas verticales sin tablas | El profesional compara muchos registros. El encargado consulta uno o dos estudiantes |
| **Tono** | Operativo y preciso. Usa el lenguaje ubicuo del dominio: cargo, devengo, nota de crédito, correlativo, arqueo | Cotidiano y tranquilizador. Traduce: "cargo" es "concepto pendiente", "nota de crédito" es "corrección a su favor" | El personal necesita precisión contable. El encargado necesita comprensión |
| **Cantidad de opciones** | Alta. Sesenta o más rutas, filtros avanzados, acciones por lote, exportación configurable | Mínima. Cuatro destinos: inicio, estado de cuenta, comprobantes, solicitudes | El personal aprende el sistema. El encargado no lo aprenderá nunca y no debe tener que hacerlo |
| **Navegación** | Barra lateral persistente con módulos agrupados, barra superior con búsqueda global y migas de pan | Barra inferior fija con cuatro destinos como máximo, sin barra lateral, sin migas | El escritorio tiene espacio horizontal. El teléfono tiene el pulgar abajo |
| **Dispositivo objetivo** | Escritorio primero. Tablet soportada. Teléfono soportado solo para consulta y aprobaciones, nunca para cobro en ventanilla | Móvil primero, real. Escritorio como escala del mismo diseño | El cobro en ventanilla con teclado no ocurre en un teléfono |
| **Errores** | Detallados, con código de referencia y sugerencia técnica cuando aplica | Sin códigos, sin jerga. Siempre con salida: "Comuníquese con la administración al 2222-0000" | El personal puede escalar. El encargado necesita saber a quién llamar |
| **Acciones destructivas** | Existen, con fricción proporcional y auditoría | No existen. El encargado nunca puede anular, editar ni borrar nada | El portal es un proceso de bajo privilegio con rol de base de datos restringido. La interfaz refleja esa frontera |
| **Movimiento** | Mínimo. Transiciones de 150 a 200 ms, sin animación decorativa | Mínimo también, pero con confirmaciones visuales algo más notorias en el envío de una transferencia | Nadie quiere esperar una animación con doce personas en fila |
| **Modo oscuro** | Soportado y esperado. Jornadas largas | Soportado, siguiendo la preferencia del sistema. No hay conmutador visible | El personal lo pide. El encargado no lo busca |
| **Idioma** | Español neutro profesional con vocabulario del dominio | Español de Honduras, cotidiano, sin tecnicismos | Ver `05-guia-de-contenido-y-voz.md` |

### Lo que sí comparten

Estas cosas no se bifurcan nunca:

1. Los tokens de diseño. Un solo archivo de variables CSS en `packages/ui`.
2. El formato del dinero. `MoneyAmount` es el mismo componente en ambas aplicaciones.
3. El mapa de estados financieros. Un cargo vencido se ve vencido en los dos lados, con el mismo
   color, el mismo icono y la misma palabra.
4. El nivel de accesibilidad exigido.
5. La identidad visual: color primario, tipografía, radios y sombras.

Si un encargado llama a la institución y dice "aquí me sale en rojo que dice vencido", el cajero
debe estar viendo exactamente lo mismo. Esa coherencia no es estética, es operativa.

---

## 6. Antipatrones prohibidos en CONFIA

| Antipatrón | Por qué se prohíbe |
|---|---|
| Emoji como icono funcional | Depende de la fuente del sistema, no se puede tematizar y rompe en la impresión. Se usa Lucide |
| Importes sin moneda o sin dos decimales | Ambigüedad sobre dinero |
| Color como único portador de estado | Falla para daltonismo, impresión en blanco y negro y lectores de pantalla |
| Placeholder como única etiqueta | Desaparece al escribir. Falla en revisión y en accesibilidad |
| Botón de envío deshabilitado hasta que el formulario sea válido | Oculta la razón del bloqueo y es una trampa para teclado. Ver `03-accesibilidad.md` |
| Filtrado o paginación de tablas en el navegador | El requerimiento y la arquitectura exigen servidor. Traer 20,000 filas al cliente es un defecto |
| Estado de servidor duplicado en estado global | Contradice la decisión de TanStack Query en `docs/01-arquitectura.md` |
| Cadenas de texto embebidas en componentes | El texto vive en catálogos de internacionalización |
| Cálculo monetario en un componente de React | Toda aritmética de dinero vive en el servidor. El componente solo formatea con `Intl.NumberFormat` importes ya calculados y redondeados por el servidor |
| Dos botones primarios en una vista | Elimina la jerarquía y obliga a decidir sin guía |
| Diálogo modal para mostrar información no bloqueante | El modal interrumpe. Se reserva para decisiones |
| Indicador giratorio de página completa | Se usan esqueletos para contenido. El giratorio es para acciones |
| Tabla horizontal con desplazamiento en el portal móvil | Ver principio 7 |

---

## 7. Cómo se usa este documento

1. Antes de especificar una pantalla, se responde por escrito cuál es su pregunta principal y
   cómo responde las tres preguntas del sistema.
2. En revisión de diseño se verifica contra los siete principios.
3. En revisión de código se verifica contra los antipatrones de la sección 6 y contra la lista de
   `03-accesibilidad.md`.
4. Un principio solo se rompe con una justificación escrita en el ADR o en la especificación del
   cambio. "Se veía mejor" no es una justificación.

---

## 8. Documentos relacionados

| Documento | Contenido |
|---|---|
| `01-tokens-de-diseno.md` | Color, tipografía, espaciado, sombras, movimiento e iconografía |
| `02-sistema-de-diseno-y-componentes.md` | Diseño atómico, inventario y especificación de componentes |
| `03-accesibilidad.md` | WCAG 2.2 AA aplicado a este producto y puerta de calidad |
| `04-patrones-de-interaccion.md` | Navegación, listados, formularios, errores y atajos |
| `05-guia-de-contenido-y-voz.md` | Voz, formato de datos y catálogo de mensajes |
| `06-flujos-clave.md` | Los trece recorridos críticos con diagramas |
| `../01-arquitectura.md` | Fuente de verdad de la arquitectura |
| `../02-modelo-de-dominio.md` | Lenguaje ubicuo que la interfaz debe respetar |
