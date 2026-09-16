# CONFIA — Análisis de brechas del requerimiento original

> Qué falta en la propuesta original y por qué cada elemento es necesario para un sistema
> financiero de nivel profesional e internacional.
> Cada brecha indica su severidad y la fase del roadmap donde se resuelve.

Los doce módulos del documento original son correctos y necesarios. El problema no es lo que
incluyen, sino lo que asumen resuelto. Un sistema de cobros que no controla el efectivo, no
concilia con el banco y no puede emitir una nota de crédito no es un sistema financiero: es un
registro de pagos con apariencia de sistema financiero. La diferencia aparece en la primera
auditoría.

Severidad: **Bloqueante** significa que no se puede salir a producción sin ello.
**Alta** significa que se puede salir, pero genera deuda operativa inmediata.
**Media** significa que condiciona la escalabilidad o la ambición internacional.

---

## Brechas bloqueantes

### B1. Libro mayor de cuentas por estudiante (estado de cuenta como ledger)

**Qué falta.** El documento describe pagos y saldos pendientes, pero no define cómo se calcula
el saldo. La tentación natural es una columna `saldo` en la tabla del estudiante.

**Por qué importa.** Un campo de saldo mutable es la causa raíz del descuadre en prácticamente
todos los sistemas de cobro artesanales. Dos procesos concurrentes, un error de aplicación o un
reintento de red y el saldo deja de corresponder a los movimientos. Cuando eso ocurre nadie
puede reconstruir la verdad, porque la historia se perdió al sobrescribir.

**Qué se construye.** Libro mayor de doble partida, inmutable y con reversos. El saldo es una
función de los movimientos, no un dato. Ver arquitectura sección 6.

**Fase.** F3.

---

### B2. Motor de devengo y generación automática de cargos

**Qué falta.** El módulo de conceptos de pago crea los productos, pero nada describe cómo se
generan los cargos mensuales de cada estudiante.

**Por qué importa.** Sin esto, alguien crea manualmente la colegiatura de cada estudiante cada
mes. Con quinientos estudiantes y diez meses, son cinco mil registros manuales al año y una
fuente garantizada de omisiones y montos incorrectos.

**Qué se construye.** Planes de cobro asociados a grado, modalidad y año lectivo. Un trabajo
programado genera los cargos del período, aplica becas y descuentos vigentes, y registra el
resultado en el libro mayor con trazabilidad completa. La generación es idempotente: correr el
proceso dos veces no duplica cargos.

**Fase.** F3.

---

### B3. Tarifas con vigencia temporal

**Qué falta.** El documento habla de crear y editar conceptos de pago. Editar el precio de un
concepto es una operación destructiva sobre la historia.

**Por qué importa.** Si la colegiatura sube en enero y alguien edita el precio del concepto,
todos los cargos de los meses anteriores pasan a mostrar el precio nuevo. La facturación
histórica deja de cuadrar y la institución pierde la capacidad de justificar lo que cobró.

**Qué se construye.** Lista de precios versionada con fecha de inicio y fin de vigencia. El
cargo guarda el precio que aplicó y una referencia a la versión de tarifa usada. Los precios
nunca se editan: se crea una nueva vigencia.

**Fase.** F2.

---

### B4. Sesiones de caja y arqueo

**Qué falta.** El módulo de pagos permite registrar cobros en efectivo en ventanilla, pero no
existe ningún control sobre el efectivo físico.

**Por qué importa.** Esta es la brecha de control interno más grave del documento. Sin sesiones
de caja no hay forma de saber cuánto efectivo debería haber en la gaveta al final del día, quién
lo recibió ni cuándo se detectó un faltante. Un sistema que registra cobros en efectivo sin
arqueo es una invitación al desvío, y la responsabilidad recae sobre el cajero honesto que no
puede demostrar su inocencia.

**Qué se construye.** Apertura de sesión con fondo inicial, registro de todos los movimientos de
la sesión, cierre con conteo declarado frente a conteo esperado, registro explícito del
sobrante o faltante con justificación, y bloqueo de cobros fuera de sesión abierta. Reporte de
cierre firmado e imprimible.

**Fase.** F4.

---

### B5. Notas de crédito, anulaciones y reversos fiscales

**Qué falta.** El módulo de facturación describe la emisión, pero no la corrección.

**Por qué importa.** Una factura emitida no se borra ni se edita, en ningún régimen fiscal serio.
Si un pago se registró por monto equivocado, con el concepto equivocado o a nombre del
estudiante equivocado, el único camino legal es una nota de crédito o una anulación registrada.
Sin este flujo, el primer error obliga a manipular la base de datos a mano, y ahí se acaba la
confiabilidad del sistema completo.

**Qué se construye.** Emisión de notas de crédito con referencia a la factura original, flujo de
anulación con motivo y autorización, control de huecos en el correlativo, y reglas explícitas
sobre cuándo aplica cada mecanismo.

**Fase.** F5.

---

### B6. Bitácora de auditoría inmutable

**Qué falta.** La auditoría aparece en el objetivo general del documento, pero no como módulo.

**Por qué importa.** En un sistema financiero, la pregunta que llega tarde o temprano es quién
cambió qué y cuándo. Si la respuesta depende de logs de aplicación rotados a los siete días, no
hay respuesta. Además, una bitácora que un administrador puede editar no prueba nada.

**Qué se construye.** Tabla de auditoría de solo inserción, con actor, dirección de origen,
acción, entidad afectada, valores anteriores y posteriores, identificador de solicitud y marca
de tiempo del servidor. Cada registro incluye el hash del registro anterior, formando una cadena
que hace evidente cualquier manipulación. Copia periódica a almacenamiento de solo escritura.
Sin permisos de borrado ni actualización, ni siquiera para el rol administrativo de base de
datos usado por la aplicación.

**Fase.** F0.

---

### B7. Idempotencia y prevención de cobro duplicado

**Qué falta.** No se menciona en el documento.

**Por qué importa.** Un doble clic en el botón de registrar pago, un reintento del navegador o
un reenvío de webhook de la pasarela producen dos cobros. En un sistema de pagos, esto no es una
posibilidad teórica: ocurre en la primera semana de uso real.

**Qué se construye.** Clave de idempotencia obligatoria en toda escritura financiera, con
índice único y respuesta reproducible. En el frontend, bloqueo de reenvío y confirmación
explícita.

**Fase.** F0 como infraestructura, aplicada desde F4.

---

### B8. Conciliación bancaria

**Qué falta.** El documento permite registrar pagos por transferencia que el responsable
notificó, pero no valida que el dinero haya llegado.

**Por qué importa.** Registrar una transferencia porque el padre envió una foto del comprobante
significa acreditar dinero que quizá nunca ingresó a la cuenta. Sin conciliación contra el
estado de cuenta bancario, la cartera del sistema y el saldo real del banco divergen desde el
primer mes.

**Qué se construye.** Importación del estado de cuenta bancario, motor de emparejamiento
automático por monto, fecha y referencia, cola de partidas no conciliadas, y estado explícito
del pago: declarado frente a confirmado. Un pago declarado no cancela la deuda hasta que se
concilia, o bien la cancela de forma provisional con alerta configurable.

**Fase.** F10, con el estado declarado o confirmado presente desde F4.

---

### B9. Protección de datos personales de menores

**Qué falta.** El documento no aborda el tratamiento de datos personales.

**Por qué importa.** El sistema almacena nombres, documentos de identidad, direcciones,
teléfonos y datos financieros de menores de edad y de sus responsables. Para una ambición
internacional, el estándar de referencia es el Reglamento General de Protección de Datos europeo
y las leyes locales de protección de datos, que exigen base legal, minimización, derechos del
titular, retención limitada y notificación de brechas.

**Qué se construye.** Registro de actividades de tratamiento, consentimiento explícito para
comunicaciones, política de retención con eliminación o anonimización automática al vencer,
exportación y rectificación de datos a solicitud del titular, cifrado de identificadores
sensibles, y procedimiento documentado de notificación de brechas.

**Fase.** F0 en diseño, F8 en funcionalidad de autoservicio.

---

### B10. Respaldo, restauración y continuidad probados

**Qué falta.** El documento menciona implementación en servidores, sin plan de continuidad.

**Por qué importa.** La pérdida de la base de datos financiera de una institución educativa es
un evento del que no se regresa. Y un respaldo que nunca se restauró tiene una probabilidad alta
de estar corrupto, incompleto o cifrado con una llave que se perdió.

**Qué se construye.** Respaldo completo nocturno más archivado continuo del registro de
transacciones para recuperación a un punto en el tiempo, cifrado en reposo, copia fuera de sitio,
simulacro mensual de restauración con acta, y runbook de recuperación ante desastre con tiempos
objetivo declarados.

**Fase.** F0.

---

## Brechas altas

### A1. Motor de mora, recargos e intereses

El módulo de cobros contempla notificar el atraso, pero no calcularlo. Se necesita un motor de
reglas configurable: días de gracia, tipo de recargo fijo o porcentual, tope máximo, capitalización
o no, exenciones por beca y suspensión de recargo durante una promesa de pago vigente. El cálculo
debe ser determinista y reproducible, porque un padre va a reclamarlo y la institución debe poder
explicarlo con exactitud. **Fase F6.**

### A2. Notificaciones multicanal con bitácora de entrega

El documento asume correo electrónico. En Honduras y en la región, la mensajería instantánea
tiene tasas de lectura muy superiores al correo. Se necesita una abstracción de canal con
adaptadores para correo, mensajería y SMS, plantillas versionadas, registro de cada envío con su
estado de entrega, control de rebotes, límite de frecuencia por destinatario y mecanismo de baja.
Sin bitácora de entrega, la institución no puede probar que notificó antes de aplicar un recargo.
**Fase F6.**

### A3. Exportación contable y plan de cuentas

El requerimiento pide reportes financieros, pero contabilidad va a pedir asientos. Se necesita un
mapeo entre conceptos de pago y cuentas contables, y una exportación periódica de pólizas en el
formato que consuma el sistema contable de la institución. Sin esto, alguien transcribe a mano
cada mes. **Fase F7.**

### A4. Gestión de documentos y solicitudes estudiantiles

El documento lo menciona en la descripción del portal futuro, pero no lo eleva a módulo. Incluye
solicitudes de constancias de matrícula, de pago, de conducta y certificaciones, además de
justificaciones de inasistencia. Requiere flujo de estados, asignación a un responsable, plantillas
de documento, generación de PDF con folio verificable y bitácora. **Fase F11.**

### A5. Panel de control de rangos CAI

Incluido en configuraciones generales, pero su criticidad operativa exige tratamiento propio. Si el
rango autorizado se agota o vence sin que nadie lo note, la institución **no puede facturar** hasta
resolverlo con la autoridad tributaria. Se necesita alerta por porcentaje consumido, alerta por
proximidad de la fecha límite de emisión, bloqueo controlado y registro histórico de rangos.
**Fase F5.**

### A6. Gestión de reembolsos y saldos a favor

Cuando un estudiante se retira o paga de más, queda un saldo a favor. El documento no define qué
ocurre con él. Se necesitan reglas explícitas: aplicar a deuda futura, transferir a un hermano o
reembolsar, cada una con su asiento contable y su documento fiscal correspondiente. **Fase F4.**

### A7. Matriz de autorización y segregación de funciones

El módulo de acceso cubre usuarios, roles y permisos, pero no la segregación de funciones, que es
un control interno básico en finanzas. Quien registra un pago no debería poder anular su propia
factura sin una segunda aprobación. Se necesita una matriz explícita de permisos por rol, flujos de
aprobación para operaciones sensibles y un reporte de quién puede hacer qué. **Fase F0.**

### A8. Portal de encargados como dominio de identidad separado

El documento lo describe como una vista del sistema. Debe tratarse como un producto con su propio
ciclo de identidad: registro validado contra el vínculo estudiante y encargado, verificación de
correo, recuperación de contraseña segura, MFA opcional y bloqueo independiente. Ver arquitectura
sección 5. **Fase F8.**

### A9. Alcance de cumplimiento de la pasarela de pago

Cuando se integre la pasarela bancaria, la decisión determinante es no tocar nunca los datos de
tarjeta. Con página alojada o campos alojados del proveedor, el alcance de cumplimiento se reduce
al cuestionario de autoevaluación más simple. Si el formulario de tarjeta vive en el portal, el
alcance se dispara a un nivel que un desarrollador solo no puede sostener. Además: verificación de
firma del webhook, idempotencia, y reconciliación de la liquidación del proveedor. **Fase F9.**

---

## Brechas medias

### M1. Multi-institución desde el esquema

Aunque se despliegue para una sola institución, incluir el identificador de institución en cada
tabla desde la primera migración cuesta poco hoy y cuesta una reescritura completa después. Es el
requisito previo para vender el sistema a una segunda institución.

### M2. Internacionalización y multi-moneda

Para la ambición internacional declarada: separación total entre texto e interfaz, formato de
moneda y fecha por configuración regional, y moneda almacenada junto a cada importe. Retrofitear
esto es de los refactors más caros que existen.

### M3. Accesibilidad como requisito, no como mejora

El nivel AA de las pautas de accesibilidad para el contenido web es requisito de contratación
pública en muchos países. Se define como criterio de aceptación y se verifica automáticamente.
Ver `docs/ui-ux/03-accesibilidad.md`.

### M4. Presupuesto y proyección de ingresos

El dashboard muestra lo ocurrido. La alta gerencia va a preguntar por lo que viene: cartera
esperada del período, proyección de recaudación, antigüedad de saldos y tasa de morosidad por
grado y modalidad. Es el reporte que justifica el sistema ante la dirección.

### M5. Portal de autoservicio del estudiante

Fuera del alcance inicial, pero el modelo de identidad debe contemplar que el estudiante mayor de
edad pueda ser su propio responsable de pago.

### M6. Banderas de funcionalidad y configuración por institución

Permiten desplegar continuamente sin exponer funcionalidad incompleta y habilitar módulos por
institución cuando exista la segunda.

---

## Entregables no técnicos del requerimiento

Los objetivos doce a quince del documento original son entregables contractuales y necesitan
planificación explícita, no aparecer al final del proyecto:

| Objetivo | Entregable | Fase |
|---|---|---|
| Manuales técnicos de diseño lógico y físico | Diagrama entidad-relación, diccionario de datos, documento de arquitectura y contrato de API publicado | Continuo, congelado por fase |
| Implementación en servidores | Runbook de despliegue, infraestructura como código, entorno de preproducción | F0 |
| Capacitación al personal administrativo | Manual de usuario por rol, videos cortos por flujo, ambiente de práctica con datos ficticios | F4 en adelante |
| Transferencia tecnológica al departamento de sistemas | Documentación de operación, runbooks de incidentes, acceso a repositorio, sesiones de traspaso grabadas | F10 y F12 |

---

## Resumen de decisión

De las veinticinco brechas identificadas, **diez son bloqueantes para producción**. La más urgente
en términos de riesgo real no es técnica sino de control interno: **sin sesiones de caja y sin
bitácora de auditoría inmutable, el sistema no protege ni a la institución ni al personal que
maneja el efectivo**.

La recomendación es incorporar las diez bloqueantes al alcance de la primera versión y negociar
las de severidad alta por fase, con el propietario del producto decidiendo el orden según el
riesgo operativo de la institución.
