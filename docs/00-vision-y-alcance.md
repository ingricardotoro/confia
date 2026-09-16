# CONFIA — Visión y alcance

> Estado: **Propuesta v1.0** — pendiente de aprobación del propietario del producto.
> Documento subordinado a `docs/01-arquitectura.md`, que es la fuente de verdad técnica.
> Alcance funcional derivado de `docs/00-requerimiento-original.txt` y de
> `docs/10-analisis-de-brechas.md`.

---

## 1. Resumen ejecutivo

**CONFIA** (Control Financiero Académico) es un sistema web de gestión financiera estudiantil
para instituciones educativas. Centraliza la matrícula financiera, el catálogo de conceptos de
pago, la generación automática de cargos, el registro y aplicación de pagos, el control de
efectivo en ventanilla, la facturación fiscal bajo régimen SAR de Honduras, la cobranza de mora
y la información gerencial.

El sistema se despliega en dos superficies separadas: un **panel administrativo** de acceso
restringido para el personal de la institución, y un **portal de encargados de pago** expuesto a
internet con privilegio mínimo. Ambos comparten un solo código fuente y una sola base de datos,
con aislamiento por proceso, por rol de base de datos, por dominio de identidad y por origen web,
según `docs/01-arquitectura.md` sección 5.

### A quién sirve

| Beneficiario | Qué obtiene |
|---|---|
| Dirección de la institución | Visibilidad de cartera, recaudación, morosidad y proyección de ingresos en tiempo real |
| Administración financiera | Cierre diario cuadrado, trazabilidad completa de cada lempira y expediente auditable |
| Cajeros | Prueba objetiva de lo que recibieron y entregaron. Protección personal frente a un faltante |
| Contabilidad | Asientos exportables, libro de ventas, control de correlativos y respaldo documental fiscal |
| Encargados de pago | Estado de cuenta consultable sin llamar ni presentarse a la institución |
| Auditoría interna o externa | Bitácora inmutable, reversos explícitos y reconstrucción histórica de cualquier saldo |

---

## 2. Problema actual

La institución opera hoy sobre procesos manuales y herramientas de ofimática. De ahí se derivan
problemas concretos y medibles:

| Problema observado | Consecuencia operativa | Módulo de CONFIA que lo resuelve |
|---|---|---|
| El cargo mensual de cada estudiante se crea a mano | Omisiones, montos incorrectos y trabajo repetitivo de miles de registros al año | `charges` (motor de devengo) |
| El saldo del estudiante vive en una celda editable | Nadie puede reconstruir cómo se llegó a ese saldo. Un error se propaga sin rastro | `ledger` (libro mayor de doble partida) |
| El efectivo de ventanilla no se controla por sesión | No hay forma de saber cuánto debería haber en caja ni quién recibió qué | `cashbox` (sesiones de caja y arqueo) |
| Corregir un cobro implica editar o borrar el registro | Se pierde la historia. La factura emitida queda sin respaldo legal | `ledger` (reversos) y `invoicing` (notas de crédito) |
| La cobranza es reactiva y depende de que alguien recuerde llamar | La mora crece sin gestión temprana y sin evidencia de haber notificado | `collections` y `notifications` |
| Emitir un estado de cuenta exige buscar en varios archivos | Minutos de espera por padre atendido y respuestas inconsistentes | `reporting` y `portal` |
| El precio de un concepto se edita sobre el registro existente | La facturación histórica deja de cuadrar con lo que realmente se cobró | `catalog` (tarifas con vigencia) |
| No existe bitácora de quién cambió qué | Ante un reclamo o una auditoría no hay respuesta posible | `shared/audit` (bitácora encadenada) |
| Un doble clic registra el pago dos veces | Cobro duplicado y reclamo del encargado | Idempotencia obligatoria en escrituras financieras |
| No se valida que la transferencia notificada haya ingresado al banco | La cartera del sistema y el saldo bancario divergen desde el primer mes | `reconciliation` |

---

## 3. Objetivos de negocio medibles

Cada objetivo declara una línea base, una meta y cómo se mide. La línea base se levanta durante
F0 con observación directa del proceso actual; los valores marcados como *por medir* se fijan en
el acta de arranque y no se inventan aquí.

| ID | Objetivo | Línea base | Meta v1 | Medición |
|---|---|---|---|---|
| OB-01 | Reducir el tiempo de registro de un pago en ventanilla | *por medir* (estimado 4 a 6 minutos) | Menor o igual a **90 segundos** desde identificar al estudiante hasta imprimir el comprobante | Instrumentación del caso de uso `RegisterPayment`, percentil 95 medido en producción |
| OB-02 | Reducir la mora mayor a 30 días | *por medir* | Reducción relativa del **30 %** al cierre del primer año lectivo completo con el sistema | Reporte de antigüedad de saldos, comparación mes contra el mismo mes del año anterior |
| OB-03 | Eliminar descuadres de caja no explicados | *por medir* | **Cero** cierres de caja con diferencia sin justificación registrada | Reporte de cierres de `cashbox`: toda diferencia exige motivo y aprobación |
| OB-04 | Reducir el tiempo de emisión de un estado de cuenta | *por medir* (estimado 10 a 20 minutos) | Menor o igual a **10 segundos** para generar el PDF; **autoservicio 24/7** en el portal desde F8 | Latencia percentil 95 del endpoint de estado de cuenta |
| OB-05 | Garantizar integridad contable | No existe control | **100 %** de las transacciones del libro mayor cuadradas | Trabajo nocturno de integridad: suma de débitos igual a suma de créditos por transacción |
| OB-06 | Eliminar la generación manual de cargos periódicos | 100 % manual | **95 %** o más de los cargos del período generados por el motor de devengo | Proporción de `Charge` con origen automático frente a manual |
| OB-07 | Garantizar trazabilidad fiscal | No existe control | **Cero** huecos de correlativo sin anulación registrada que lo explique | Reporte de continuidad de correlativos por punto de emisión |
| OB-08 | Reducir consultas telefónicas de saldo a la administración | *por medir* | Reducción del **50 %** a los tres meses de publicar el portal | Conteo manual en recepción antes y después de F8 |
| OB-09 | Probar la capacidad de recuperación ante desastre | No existe | Objetivo de punto de recuperación **15 minutos**, objetivo de tiempo de recuperación **4 horas**, con simulacro mensual documentado | Acta de simulacro de restauración |
| OB-10 | Demostrar la notificación previa a un recargo | No existe | **100 %** de los recargos aplicados con evidencia de notificación previa entregada | Cruce entre `DunningNotification` y `Charge` de tipo recargo |

---

## 4. Actores y roles del sistema

Los permisos concretos por operación viven en la matriz de autorización de `docs/03-seguridad.md`
(brecha A7). Aquí se define la intención de cada rol y su frontera de segregación de funciones.

| Rol | Dominio de identidad | Responsabilidad | No puede |
|---|---|---|---|
| **Super Administrador** | Personal | Configuración de institución, alta de usuarios y roles, rangos CAI, parámetros globales, banderas de funcionalidad | Registrar pagos ni emitir facturas de forma rutinaria. Su actividad se audita con alerta |
| **Administrador** | Personal | Operación completa: estudiantes, matrícula, catálogo, becas, planes de cobro, cobranza, aprobación de anulaciones y ajustes | Modificar la bitácora de auditoría, alterar correlativos emitidos ni desactivar la auditoría |
| **Cajero** | Personal | Abrir y cerrar su sesión de caja, registrar pagos, emitir factura del pago que registró, imprimir comprobantes | Anular su propia factura sin aprobación de Administrador. Cobrar sin sesión de caja abierta. Ver el arqueo de otro cajero |
| **Contabilidad** | Personal | Libro de ventas, exportación de asientos, notas de crédito, conciliación bancaria, cierre contable del período | Modificar cargos ni matrículas. Abrir sesiones de caja |
| **Auditor** | Personal | Lectura total del sistema, incluida la bitácora de auditoría y todos los reportes históricos | Escribir absolutamente nada. Su rol es de solo lectura por diseño, aplicado también a nivel de permisos de base de datos |
| **Coordinador Académico** | Personal | Matrícula, grados, secciones, modalidades, traslados de sección, consulta de estado financiero del estudiante para decisiones académicas | Registrar pagos, emitir facturas, modificar tarifas ni otorgar becas |
| **Encargado de pago** | Portal | Ver el estado de cuenta de los estudiantes bajo su responsabilidad, descargar comprobantes, iniciar un pago (desde F9), enviar solicitudes de documentos (desde F11) | Ver datos de estudiantes que no le corresponden. Acceder a ningún módulo administrativo. Su aislamiento se aplica con seguridad a nivel de fila en PostgreSQL |
| **Estudiante** (futuro) | Portal | Consulta de su propio estado de cuenta y solicitudes. Un estudiante mayor de edad puede ser su propio encargado de pago | Igual restricción que el encargado |

**Segregación de funciones mínima obligatoria en v1:**

1. Quien registra un pago no aprueba la anulación de la factura de ese pago.
2. Quien cuenta el efectivo al cierre no es quien autoriza la diferencia declarada.
3. Un ajuste manual al libro mayor exige motivo escrito y un aprobador distinto del solicitante.
4. La cuenta de Auditor no tiene ninguna capacidad de escritura, ni siquiera indirecta.

---

## 5. Alcance de la versión 1

La versión 1 comprende los **doce módulos del requerimiento original** más las **diez brechas
bloqueantes** del análisis de brechas. Todo lo bloqueante es condición de salida a producción.

### 5.1 Módulos del requerimiento original

| # | Módulo del requerimiento | Módulo técnico | Fase | Qué incluye en v1 |
|---|---|---|---|---|
| 1 | Acceso y seguridad | `identity` | F0 | Usuarios, roles, permisos, MFA para personal, sesiones, bloqueo por fuerza bruta, matriz de autorización |
| 2 | Gestión organizativa | `organization` | F1 | Institución, año lectivo, modalidad, grado, sección, períodos |
| 3 | Configuraciones generales | `organization`, `catalog`, `invoicing` | F1 a F5 | Monedas, métodos de pago, impuestos, parámetros de facturación, rangos CAI |
| 4 | Estudiantes y responsables | `students`, `guardians` | F1 | Estudiante, matrícula del año, encargados, vínculo con porcentaje de responsabilidad financiera |
| 5 | Conceptos de pago | `catalog` | F2 | Conceptos, listas de precios con vigencia, impuestos aplicables, filtros y búsqueda |
| 6 | Becas y descuentos | `scholarships` | F2 | Becas institucionales, descuentos por fecha o concepto, vigencia y efecto financiero visible |
| 7 | Pagos | `payments`, `cashbox` | F4 | Efectivo, tarjeta y transferencia declarada. Abonos, pagos parciales, aplicación a cargos, saldos a favor, sesiones de caja |
| 8 | Promesas de pago | `collections` | F6 | Registro del compromiso, historial, alertas de incumplimiento, suspensión de recargo mientras la promesa está vigente |
| 9 | Cobros | `collections`, `notifications` | F6 | Reglas de mora, escalamiento de avisos, plantillas, bitácora de entrega |
| 10 | Facturación | `invoicing` | F5 | Emisión con CAI y correlativo, anulación, notas de crédito, libro de ventas, panel de rangos |
| 11 | Dashboard general | `reporting` | F7 | Indicadores de recaudación, cartera, morosidad y caja, con filtros por fecha y modalidad |
| 12 | Estadísticas y reportes | `reporting` | F7 | Reportes financieros, antigüedad de saldos, exportación a CSV, Excel y PDF, exportación contable |

### 5.2 Brechas bloqueantes incorporadas al alcance de v1

| ID | Brecha | Fase | Criterio de cierre |
|---|---|---|---|
| B1 | Libro mayor de doble partida por estudiante | F3 | El saldo se deriva del libro. No existe columna de saldo mutable como fuente de verdad |
| B2 | Motor de devengo y generación automática de cargos | F3 | Ejecutar el proceso dos veces sobre el mismo período no duplica cargos |
| B3 | Tarifas con vigencia temporal | F2 | Un precio no se edita. Se crea una nueva vigencia y el cargo referencia la versión aplicada |
| B4 | Sesiones de caja y arqueo | F4 | No se registra un pago en efectivo sin sesión abierta. El cierre exige conteo declarado |
| B5 | Notas de crédito, anulaciones y reversos fiscales | F5 | Ningún documento fiscal emitido se modifica ni se borra |
| B6 | Bitácora de auditoría inmutable | F0 | Tabla de solo inserción, encadenada por hash, sin permiso de actualización ni borrado |
| B7 | Idempotencia y prevención de cobro duplicado | F0 infraestructura, F4 aplicación | Toda escritura financiera exige clave de idempotencia con índice único |
| B8 | Conciliación bancaria | Estados declarado y confirmado desde F4; motor completo en F10 | Un pago declarado no se confunde con dinero confirmado |
| B9 | Protección de datos personales de menores | Diseño en F0, autoservicio en F8 | Registro de tratamiento, retención declarada, cifrado de identificadores sensibles |
| B10 | Respaldo, restauración y continuidad probados | F0 | Simulacro de restauración ejecutado y documentado antes del primer dato real |

### 5.3 Alcance no funcional de v1

- Accesibilidad nivel AA de las pautas WCAG como criterio de aceptación verificado automáticamente.
- Internacionalización desde el catálogo de textos, sin cadenas embebidas. Moneda almacenada junto a cada importe.
- Identificador de institución presente en el esquema desde la primera migración, aunque se opere una sola institución.
- Contrato OpenAPI 3.1 publicado y versionado como artefacto.
- Cobertura de pruebas conforme a `docs/06-estrategia-de-testing.md`.

---

## 6. Fuera de alcance explícito de la versión 1

Lo siguiente **no** se construye en v1. Se declara aquí para evitar la expansión silenciosa del
alcance. Cada elemento indica en qué fase entra y qué preparación se deja hecha desde v1 para que
incorporarlo no exija reescribir.

| Fuera de alcance de v1 | Fase de entrada | Preparación que sí se hace en v1 |
|---|---|---|
| **Aplicación móvil para encargados** | F12 | Contrato REST con OpenAPI y `packages/contracts` compartido desde el día uno. Ninguna lógica de negocio en el cliente web |
| **Pasarela de pago en línea** | F9 | `PaymentMethod` extensible, estados declarado y confirmado en `Payment`, idempotencia obligatoria y puerto de pasarela definido en `payments/application` |
| **Conciliación bancaria automática** | F10 | Entidades `BankStatement`, `BankTransaction` y `ReconciliationMatch` modeladas desde F3. Estado del pago diferenciado desde F4 |
| **Facturación electrónica** | Posterior a F10, sujeta a normativa vigente | Puerto de emisión de documento fiscal con adaptador CAI. Migrar es agregar un adaptador, no reescribir `invoicing` |
| **Multi-institución activa** | Posterior a F10, condicionada a una segunda venta | `institution_id` en cada tabla de negocio desde la primera migración, salvo la tabla raíz de instituciones, cuya clave primaria es ese identificador (catálogo cerrado de ADR-0017, que exceptúa además tres tablas técnicas), más índices y políticas de fila preparados |
| **Portal de autoservicio del estudiante** | Posterior a F8 | El modelo de identidad contempla que un estudiante mayor de edad sea su propio encargado de pago |
| **Gestión de documentos y solicitudes estudiantiles** | F11 | Superficie del portal ya construida en F8. Almacenamiento compatible con S3 disponible desde F0 |
| **Nómina, planilla docente o contabilidad general completa** | No planificado | Exportación de asientos hacia el sistema contable existente en F7. CONFIA no reemplaza al software contable |
| **Control académico de calificaciones y asistencia** | No planificado | El sistema es financiero. La asistencia solo aparece como justificación documental en F11 |

---

## 7. Criterios de éxito y métricas de aceptación

### 7.1 Criterios de aceptación del producto mínimo viable (cierre de F5)

El sistema se considera apto para producción cuando **todos** los siguientes se cumplen y quedan
documentados con evidencia:

| ID | Criterio | Evidencia requerida |
|---|---|---|
| CA-01 | Un año lectivo completo se configura desde cero: institución, modalidades, grados, secciones, conceptos, tarifas y planes de cobro | Recorrido guiado con datos reales de la institución |
| CA-02 | La matrícula de todos los estudiantes activos existe en el sistema y coincide con la matrícula oficial | Acta de conciliación de la migración firmada por la administración |
| CA-03 | El motor de devengo genera los cargos del período sin duplicados al reejecutarse | Ejecución doble en preproducción con conteo idéntico |
| CA-04 | Toda transacción del libro mayor cuadra | Trabajo de integridad con cero hallazgos durante 30 días corridos |
| CA-05 | Un pago en efectivo no puede registrarse sin sesión de caja abierta | Prueba de extremo a extremo que verifica el rechazo |
| CA-06 | Un cierre de caja produce un reporte imprimible con conteo esperado, conteo declarado y diferencia justificada | Reporte de cierre de un día real en preproducción |
| CA-07 | Una factura se emite con CAI y correlativo válido, y no puede editarse ni borrarse después | Intento de modificación rechazado a nivel de base de datos |
| CA-08 | Una nota de crédito corrige una factura errónea sin alterar el documento original | Caso completo ejecutado y validado por contabilidad |
| CA-09 | Un doble envío de la misma operación de pago produce un solo cobro | Prueba de idempotencia con la misma clave |
| CA-10 | La bitácora de auditoría registra cada acción sensible y su cadena de hash verifica sin roturas | Verificador de cadena ejecutado sobre el período completo |
| CA-11 | Un respaldo se restaura en un entorno limpio dentro del objetivo de tiempo declarado | Acta de simulacro de restauración |
| CA-12 | La verificación automática de accesibilidad no reporta violaciones críticas en las pantallas del flujo de cobro | Reporte de axe-core en integración continua |
| CA-13 | El personal capacitado ejecuta el flujo de cobro completo sin asistencia del desarrollador | Sesión de aceptación observada con al menos dos cajeros |

### 7.2 Métricas de operación posteriores a la puesta en producción

Se revisan mensualmente durante el primer año lectivo:

| Métrica | Umbral aceptable |
|---|---|
| Transacciones descuadradas detectadas | 0 |
| Cierres de caja con diferencia sin justificar | 0 |
| Huecos de correlativo sin anulación asociada | 0 |
| Disponibilidad del panel administrativo en horario de cobro | 99,5 % o superior |
| Latencia percentil 95 del registro de pago | Menor o igual a 800 ms |
| Fallos de respaldo no atendidos en 24 horas | 0 |
| Incidentes de acceso indebido a datos de otro estudiante | 0 |
| Reclamos de cobro duplicado atribuibles al sistema | 0 |

---

## 8. Supuestos y dependencias externas

### 8.1 Supuestos

| ID | Supuesto | Riesgo si es falso |
|---|---|---|
| S-01 | La institución designa un responsable funcional con autoridad para decidir reglas de negocio y aceptar entregas | El proyecto se bloquea en decisiones pendientes. Riesgo R09 |
| S-02 | La institución cuenta con el rango CAI vigente y su contador puede validar las reglas fiscales aplicadas | Bloqueo en F5. Riesgo R02 |
| S-03 | Los datos del sistema actual son exportables a un formato tabular legible | La migración pasa de automatizada a transcripción manual. Riesgo R11 |
| S-04 | El personal administrativo participa en la capacitación durante horario laboral | Adopción baja y regreso a los procesos manuales. Riesgo R10 |
| S-05 | El volumen inicial es del orden de cientos a pocos miles de estudiantes, no decenas de miles | Cambio de dimensionamiento de infraestructura, no de arquitectura |
| S-06 | La institución acepta que las correcciones se hacen por reverso y nota de crédito, nunca por edición | Fricción operativa constante y presión para abrir puertas traseras |
| S-07 | El desarrollador dispone de dedicación sostenida y previsible | Duplicación de los plazos del roadmap. Riesgo R01 |

### 8.2 Dependencias externas

| Dependencia | Necesaria desde | Qué se necesita concretamente | Plan si no está disponible |
|---|---|---|---|
| **Banco de la institución** | F9 (pasarela) y F10 (conciliación) | Contrato de comercio electrónico, credenciales de prueba, documentación de webhook, formato del estado de cuenta y frecuencia de descarga | F9 se pospone. F10 arranca con importación manual de archivo |
| **Contabilidad de la institución** | F5 y F7 | Plan de cuentas, mapeo concepto a cuenta contable, formato de póliza que consume su sistema, validación de reglas fiscales | La exportación contable se entrega como CSV genérico y se ajusta después |
| **SAR (autoridad tributaria)** | F5 | Rango CAI vigente, punto de emisión autorizado, requisitos formales del documento impreso | No se puede facturar. Bloqueo duro del producto mínimo viable |
| **Proveedor de correo transaccional** | F6 | Servicio de correo de AWS (SES) por SMTP, según ADR-0014. Dominio verificado, registros SPF, DKIM y DMARC, cuota suficiente, acceso a eventos de rebote y acceso de producción solicitado con anticipación, porque el servicio inicia en modo de pruebas | Se retrasa la cobranza automatizada. El envío manual no es sustituto |
| **Proveedor de mensajería instantánea** | F6, opcional | Cuenta de negocio aprobada y plantillas autorizadas | El canal se desactiva por bandera de funcionalidad. Solo correo |
| **Nube e infraestructura (AWS)** | F0 | Lo que la institución debe proveer o decidir: la cuenta de AWS y su medio de facturación; el dominio, con su DNS administrado en Cloudflare; la cuenta en un proveedor distinto de AWS para los respaldos cifrados fuera de sitio; y la confirmación legal de la transferencia internacional de datos personales de menores, con el acuerdo de tratamiento de datos del proveedor firmado. El acceso a estas cuentas y sus credenciales quedan en custodia también del propietario del producto, no solo del desarrollador (riesgo R01). Detalle en la sección 8.3 | Sin la cuenta de AWS no hay entorno de preproducción y F0 no cierra. Sin la confirmación legal y el acuerdo firmado, los entornos operan solo con datos ficticios y no se carga ningún dato real. Si el costo resultara inasumible, ADR-0014 mantiene como alternativa viable los servidores virtuales económicos sin cambiar la aplicación, previa decisión del propietario del producto |
| **Departamento de sistemas de la institución** | F10 y F12 | Interlocutor para la transferencia tecnológica, credenciales de operación y participación en los runbooks | El factor de bus permanece en uno indefinidamente. Riesgo R01 |

### 8.3 Infraestructura de nube

La infraestructura se decidió en ADR-0014 (AWS como proveedor de nube con política de
portabilidad). En términos de negocio:

- **Proveedor y región.** Amazon Web Services, en la región Norte de Virginia (`us-east-1`), de
  modo que todos los datos quedan en una sola jurisdicción.
- **Fase uno (F0 a F7).** Un servidor de aplicación en AWS ejecuta los componentes del sistema con
  Docker Compose. La base de datos es un PostgreSQL gestionado por AWS (RDS), que se encarga del
  respaldo automático, del parcheo y de la restauración a un punto en el tiempo. La base de datos
  nunca es accesible desde internet.
- **Fase dos (F8 en adelante).** Cuando el portal de encargados recibe tráfico real, pasa a un
  segundo servidor y se agrega una réplica de lectura de la base de datos para sus consultas.
- **Archivos.** Los PDF fiscales y los adjuntos se guardan en el almacenamiento de objetos de AWS
  (S3).
- **Correo.** El correo transaccional se envía con el servicio de correo de AWS (SES).
- **Dominio, borde y protección.** El DNS, los certificados, el filtrado de tráfico malicioso y la
  protección ante ataques de denegación de servicio están en Cloudflare, fuera de AWS.
- **Respaldos fuera de sitio.** Además de los respaldos automáticos de la base de datos gestionada,
  se guardan copias cifradas en un proveedor distinto de AWS. La llave para descifrarlas no vive en
  AWS ni en ese proveedor, de modo que perder la cuenta de AWS no implica perder los datos.
- **Portabilidad.** La aplicación solo usa servicios con protocolo o interfaz estándar, sin
  dependencia exclusiva de AWS: cambiar de proveedor exige rehacer la configuración de
  infraestructura, no reescribir el sistema. Una vez al año se ensaya la salida ejecutando el
  simulacro de restauración en un proveedor distinto de AWS, con acta.
- **Condición para operar con datos reales.** Ningún dato real se carga en AWS antes de la
  confirmación legal de la transferencia internacional de datos personales de menores y de la firma
  del acuerdo de tratamiento de datos que ofrece el proveedor
  (`docs/08-datos-privacidad-y-retencion.md`, ADR-0014).

---

## 9. Restricciones conocidas

| Restricción | Naturaleza | Consecuencia asumida |
|---|---|---|
| **Un solo desarrollador** | Estructural, no negociable | Sin microservicios, sin poliglotismo, sin Kubernetes. Se prioriza automatización y código convencional. Toda estimación reconoce que no hay paralelismo posible entre fases |
| **Presupuesto acotado** | Comercial | Infraestructura mínima viable en la fase uno de despliegue (F0 a F7): un servidor de aplicación en AWS y una base de datos PostgreSQL gestionada en una sola zona, sin alta disponibilidad activa (ADR-0014). La recuperación se apoya en respaldos probados, no en redundancia |
| **Datos de menores de edad** | Legal y ética | Minimización de datos, cifrado de identificadores sensibles, retención limitada, prohibición de registrar datos personales en logs. Eleva el estándar por encima de un sistema corporativo típico. Ver `docs/08-datos-privacidad-y-retencion.md` |
| **Cumplimiento fiscal hondureño** | Legal | Correlativos irrepetibles, CAI con vigencia, anulaciones y notas de crédito, retención documental. **Toda regla fiscal concreta debe ser validada con el contador de la institución y contra la normativa vigente del SAR antes de implementarse.** Este documento no sustituye asesoría fiscal |
| **Institución en operación** | Operativa | La puesta en producción debe ocurrir en una ventana compatible con el calendario escolar, preferiblemente al inicio de un año lectivo o de un período de cobro |
| **Conectividad variable** | Técnica | La interfaz de cobro debe tolerar latencia y reintentos sin duplicar operaciones. La idempotencia no es un lujo |
| **Ambición internacional declarada** | De producto | Multi-moneda, internacionalización, multi-institución y accesibilidad se modelan desde el inicio aunque se activen después. Retrofitearlos es de los refactores más costosos que existen |

---

## 10. Documentos relacionados

| Documento | Contenido |
|---|---|
| `docs/01-arquitectura.md` | Fuente de verdad técnica |
| `docs/02-modelo-de-dominio.md` | Lenguaje ubicuo, entidades, invariantes y máquinas de estado |
| `docs/03-seguridad.md` | Modelo de amenazas y matriz de autorización |
| `docs/04-cumplimiento-fiscal-sar.md` | CAI, correlativos, impuesto sobre ventas y notas de crédito |
| `docs/08-datos-privacidad-y-retencion.md` | Datos de menores, consentimiento y retención |
| `docs/09-roadmap-y-fases.md` | Plan de entrega por fases |
| `docs/10-analisis-de-brechas.md` | Módulos faltantes en el requerimiento original |
| `docs/11-riesgos.md` | Registro de riesgos y mitigaciones |
| `docs/14-glosario.md` | Glosario de negocio, fiscal y técnico |
