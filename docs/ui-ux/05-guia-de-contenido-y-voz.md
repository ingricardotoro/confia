# Guía de contenido y voz

> El texto es parte del producto. En un sistema financiero, un mensaje ambiguo produce un cobro mal
> registrado o una llamada del padre a la institución. Se escribe con el mismo cuidado que el código.

---

## 1. Voz del producto

CONFIA habla como un colega competente que explica sin condescender: claro, directo, respetuoso, sin
jerga contable innecesaria y sin lenguaje de mercadeo.

Tres reglas que gobiernan todo lo demás:

1. **Preferir lo concreto a lo general.** No "hubo un problema", sino "el rango de facturación se
   agotó".
2. **Decir qué hacer.** Un mensaje que solo informa de un fallo deja a la persona atascada.
3. **No disculparse en exceso.** Un "lo sentimos" en cada error se vuelve ruido. Se reserva para
   cuando la culpa es realmente del sistema.

### Tono según la aplicación

| | Panel administrativo | Portal de encargados |
|---|---|---|
| Audiencia | Personal que usa el sistema a diario | Padres de familia, uso ocasional |
| Registro | Profesional y eficiente | Cercano y explicativo |
| Vocabulario | Admite términos del dominio: devengo, correlativo, imputación | Solo lenguaje cotidiano |
| Longitud | Breve. La persona ya sabe lo que hace | Un poco más explicativa |
| Ejemplo de saldo | "Saldo pendiente" | "Lo que falta por pagar" |
| Ejemplo de error | "Rango CAI agotado. No es posible emitir." | "No podemos generar tu factura en este momento. Tu pago sí quedó registrado." |

La diferencia no es de amabilidad sino de conocimiento previo. El cajero sabe qué es un correlativo;
el padre no tiene por qué saberlo, y explicárselo en un mensaje de error no es el momento.

---

## 2. Reglas de escritura de interfaz

| Elemento | Regla | Correcto | Incorrecto |
|---|---|---|---|
| Títulos de página | Mayúscula solo inicial. Sustantivo | Registro de pagos | Registro De Pagos |
| Botones | Verbo en infinitivo que nombra la acción | Registrar pago | Aceptar |
| Botón de cancelar | Nombra lo que se descarta si no es obvio | Descartar cambios | Cancelar |
| Etiquetas de campo | Máximo tres palabras, sin dos puntos | Monto recibido | Ingrese el monto recibido: |
| Texto de ayuda | Una frase. Debajo del campo | Incluye recargos si aplican | |
| Casillas | Redactadas en positivo | Notificar por correo | No notificar por correo |
| Encabezados de tabla | Sustantivo singular o plural, breve | Saldo, Vencimiento | Monto del saldo pendiente |
| Estados vacíos | Qué falta más la acción | | Sin datos |

**Prohibido el botón "Aceptar".** No dice qué se acepta. Un diálogo que pregunta si anular una
factura tiene un botón que dice "Anular factura", no "Aceptar". La persona debe poder confirmar
leyendo solo el botón.

### Consistencia terminológica

El texto de interfaz usa el mismo término que el lenguaje ubicuo del dominio. Si el modelo lo llama
encargado de pago, la interfaz no lo llama a veces responsable, a veces padre y a veces tutor. Ver
`docs/14-glosario.md`.

---

## 3. Formato de datos

Formato obligatorio. Todo pasa por las utilidades de formato del sistema de diseño, nunca por
concatenación manual.

| Dato | Formato | Ejemplo |
|---|---|---|
| Importe | Símbolo, espacio, miles con coma, siempre dos decimales | `L 3,200.00` |
| Importe cero | Nunca vacío ni guión | `L 0.00` |
| Importe negativo | Signo menos visible | `-L 450.00` |
| Saldo a favor | Positivo, con etiqueta explícita | `L 200.00 a favor` |
| Fecha | Día, mes y año con barras | `15/01/2026` |
| Fecha larga | En texto, para documentos | `15 de enero de 2026` |
| Fecha y hora | Con hora de 12 horas | `15/01/2026 3:45 p. m.` |
| Rango de fechas | Con guión corto y espacios | `01/01/2026 - 31/01/2026` |
| Documento fiscal | Cuatro grupos con guiones | `000-001-01-00001234` |
| Registro tributario | Sin separadores | `08011999123456` |
| Identidad | Con guiones según el formato oficial | `0801-1999-12345` |
| Teléfono | Con código de país y separación | `+504 9999-9999` |
| Nombre de persona | Nombres seguido de apellidos, sin mayúsculas completas | `María José Fernández López` |
| Período académico | Nombre y año | `Enero 2026`, `Año lectivo 2026` |
| Porcentaje | Sin decimales salvo que importen | `15%`, `12.5%` |
| Cantidad de resultados | Con separador de miles | `1,247 pagos` |

Los importes se muestran **siempre con dos decimales**, incluso en cantidades redondas. Un cajero
leyendo rápido confunde `1,500` con quince mil; `1,500.00` no admite esa lectura.

---

## 4. Mensajes de error

### La fórmula

**Qué pasó. Por qué. Qué hacer.** En ese orden, en una o dos frases.

Nunca se muestra un código de error crudo, ni un mensaje de la base de datos, ni una traza. El
identificador de traza se muestra únicamente en el panel administrativo, para que el personal pueda
reportarlo.

### Catálogo de mensajes reales

| Situación | Incorrecto | Correcto |
|---|---|---|
| Rango CAI agotado | Error 500 | El rango de facturación autorizado se agotó. No es posible emitir facturas hasta registrar un rango nuevo. El pago sí puede registrarse. Avisa a administración. |
| Rango CAI vencido | CAI inválido | La fecha límite de emisión del rango autorizado ya pasó. No es posible facturar con este rango. Avisa a administración. |
| Sin sesión de caja abierta | Operación no permitida | No tienes una sesión de caja abierta. Abre tu caja antes de registrar pagos en efectivo. |
| Sesión de caja de otro cajero | Acceso denegado | Esta sesión de caja pertenece a otro usuario. Solo quien la abrió puede registrar movimientos en ella. |
| Pago mayor a la deuda | Monto inválido | El monto recibido supera el saldo pendiente de L 1,800.00. La diferencia de L 200.00 quedará como saldo a favor del estudiante. ¿Continuar? |
| Factura ya anulada | No se puede procesar | Esta factura ya fue anulada el 12/01/2026. No es posible anularla de nuevo ni emitir otra nota de crédito sobre ella. |
| Permiso insuficiente | 403 Forbidden | No tienes permiso para anular facturas. Solicita a un administrador que realice esta acción. |
| Sesión expirada | Token inválido | Tu sesión expiró por inactividad. Inicia sesión de nuevo. Los datos que no habías guardado se perdieron. |
| Monto con formato inválido | Valor incorrecto | Escribe el monto con dos decimales, por ejemplo 3200.00. |
| Monto cero o negativo | Monto inválido | El monto debe ser mayor que cero. |
| Estudiante sin encargado | Datos incompletos | Este estudiante no tiene un encargado de pago asignado. Asigna uno antes de generar cargos. |
| Concepto sin tarifa vigente | No se encontró precio | El concepto Transporte no tiene una tarifa vigente para la fecha 15/01/2026. Registra una tarifa antes de generar el cargo. |
| Error de red al registrar pago | Error de conexión | No pudimos confirmar si el pago se registró. **No vuelvas a registrarlo.** Revisa la lista de pagos del estudiante antes de intentar de nuevo. |
| Conflicto de concurrencia | Error de base de datos | Otro usuario modificó este registro mientras lo editabas. Recarga la pantalla para ver los datos actuales. |
| Archivo demasiado grande | Error al subir | El archivo supera los 5 MB. Sube un comprobante más liviano. |
| Tipo de archivo no permitido | Formato inválido | Solo se aceptan imágenes JPG o PNG y archivos PDF. |
| Credenciales incorrectas | Usuario no existe | El correo o la contraseña no son correctos. |
| Cuenta bloqueada | Acceso denegado | Tu cuenta está bloqueada temporalmente por varios intentos fallidos. Intenta de nuevo en 15 minutos. |
| Código de verificación inválido | MFA falló | El código de verificación no es correcto o ya expiró. Genera uno nuevo en tu aplicación. |

El mensaje de credenciales incorrectas dice deliberadamente "el correo **o** la contraseña", nunca
cuál de los dos falló. Decir "el usuario no existe" permite a un atacante averiguar qué correos
están registrados. Ver `docs/03-seguridad.md`.

El mensaje de error de red al registrar un pago es el más importante del sistema y por eso lleva la
advertencia en negrita. Un cajero que reintenta ante un error de red es la causa número uno de
cobros duplicados.

---

## 5. Mensajes de éxito, confirmación y advertencia

### Éxito

Confirman qué se hizo, con los datos que la persona necesita para verificar.

| Acción | Mensaje |
|---|---|
| Pago registrado | Pago de L 3,200.00 registrado. Recibo 000-001-01-00001234. |
| Sesión de caja abierta | Caja abierta con un fondo inicial de L 500.00. |
| Caja cerrada sin diferencia | Caja cerrada. El conteo coincide con lo esperado. |
| Caja cerrada con diferencia | Caja cerrada con un faltante de L 50.00. La diferencia quedó registrada. |
| Nota de crédito emitida | Nota de crédito 000-001-03-00000045 emitida sobre la factura 000-001-01-00001234. |
| Cargos generados | Se generaron 428 cargos de colegiatura de enero por un total de L 1,369,600.00. |
| Notificaciones enviadas | Se enviaron 87 avisos de cobro. 3 correos no pudieron entregarse. |

### Advertencias antes de confirmar

Dicen la consecuencia, no la acción.

| Acción | Texto de consecuencia |
|---|---|
| Anular factura | Se emitirá una anulación registrada ante la autoridad tributaria. El correlativo no podrá reutilizarse y esta operación no se puede deshacer. |
| Reversar pago | El pago volverá a quedar como deuda pendiente del estudiante. El movimiento de reverso quedará visible de forma permanente en el estado de cuenta. |
| Cerrar sesión de caja | No podrás registrar más movimientos en esta sesión. La diferencia detectada quedará registrada a tu nombre. |
| Generar cargos del mes | Se generarán cargos para 428 estudiantes por un total de L 1,369,600.00. Los cargos aparecerán de inmediato en los estados de cuenta. |
| Enviar ciclo de cobranza | Se enviarán 87 correos a encargados con pagos vencidos. |

---

## 6. Estados vacíos

Cada uno con su acción principal. Un estado vacío sin salida es una pantalla muerta.

| Pantalla | Mensaje | Acción |
|---|---|---|
| Pagos, sin registros | Todavía no hay pagos registrados en este período. | Registrar pago |
| Pagos, filtro sin resultados | Ningún pago coincide con los filtros aplicados. | Limpiar filtros |
| Estudiantes, sin registros | No hay estudiantes matriculados en el año lectivo 2026. | Matricular estudiante |
| Estado de cuenta sin movimientos | Este estudiante no tiene movimientos registrados. | Generar cargo |
| Promesas de pago | No hay promesas de pago activas. | Registrar promesa |
| Portal, sin deuda | No tienes pagos pendientes. Estás al día. | Ver historial |
| Portal, sin estudiantes | No hay estudiantes asociados a tu cuenta. Comunícate con la administración. | Contactar |
| Búsqueda global sin resultados | No encontramos nada para "meredith". Prueba con el nombre completo o el código del estudiante. | |

El estado vacío del portal cuando no hay deuda merece atención: es el mejor momento del producto
para un padre de familia y debe sentirse como tal, no como una tabla vacía.

---

## 7. Terminología prohibida

| No usar | Usar | Razón |
|---|---|---|
| Aceptar | El verbo de la acción | No dice qué se acepta |
| Eliminar (en algo financiero) | Anular, reversar | Nada financiero se elimina |
| Borrar factura | Anular factura | Legalmente no se borra |
| Usuario (para el padre) | Encargado de pago | El término del dominio |
| Cliente | Encargado de pago | No es una relación comercial de consumo |
| Deudor moroso | Encargado con pagos vencidos | Innecesariamente estigmatizante |
| Error fatal | Descripción del problema | Alarma sin informar |
| Oops | Descripción del problema | Trivializa un problema con dinero |
| Ups, algo salió mal | Qué salió mal exactamente | No informa nada |
| Inténtalo más tarde | Cuándo o qué hacer | Vago e inútil |
| Procesando | Registrando el pago | Genérico |
| Datos inválidos | El problema específico del campo | No dice qué corregir |
| Ingresar (para escribir) | Escribir, indicar | Ambiguo con iniciar sesión |
| Loguearse | Iniciar sesión | |

---

## 8. Notificaciones de cobranza

Son el punto de contacto más delicado del sistema. Un aviso mal redactado daña la relación entre la
institución y una familia, y además puede exponer legalmente a la institución.

### Reglas

**Debe contener siempre:**

- Nombre del estudiante y su grado o sección
- Concepto adeudado y período
- Monto exacto, con el recargo desglosado si aplica
- Fecha de vencimiento original y días de atraso
- Canales de pago disponibles
- Contacto de la administración para consultas o acuerdos
- Enlace al portal para ver el detalle

**Nunca debe:**

- Amenazar con consecuencias académicas para el estudiante
- Mencionar al estudiante como responsable de la deuda
- Usar lenguaje que avergüence
- Incluir datos de otros estudiantes o encargados
- Enviarse a un destinatario que no sea el encargado de pago registrado
- Enviarse si hay una promesa de pago vigente sin vencer

La regla del estudiante importa: la deuda es del encargado, y redactar como si el niño la debiera es
tanto incorrecto como dañino.

### Primer aviso, cortés

> Asunto: Recordatorio de pago pendiente - [Estudiante]
>
> Estimado/a [Encargado]:
>
> Le recordamos que el pago de [Concepto] correspondiente a [Período] del estudiante [Estudiante],
> de [Grado] [Sección], venció el [Fecha] y se encuentra pendiente.
>
> Monto pendiente: L [Monto]
>
> Puede realizar su pago en las oficinas de la institución o mediante transferencia bancaria. Para
> ver el detalle de su estado de cuenta, ingrese al portal: [Enlace]
>
> Si ya realizó este pago, por favor ignore este mensaje o comuníquese con nosotros para
> verificarlo.
>
> Atentamente,
> Administración - [Institución]
> [Teléfono] | [Correo]

### Segundo aviso, con recargo

> Asunto: Pago vencido - [Estudiante] - [Días] días de atraso
>
> Estimado/a [Encargado]:
>
> El pago de [Concepto] correspondiente a [Período] del estudiante [Estudiante] presenta [Días] días
> de atraso.
>
> Monto original: L [Monto]
> Recargo por mora: L [Recargo]
> **Total a pagar: L [Total]**
>
> Le solicitamos regularizar esta situación a la brevedad. Si necesita establecer un acuerdo de pago,
> comuníquese con la administración al [Teléfono]. Estamos disponibles para encontrar una solución.
>
> Detalle de su estado de cuenta: [Enlace]
>
> Atentamente,
> Administración - [Institución]

### Aviso final

> Asunto: Situación de pago pendiente - [Estudiante] - Requiere atención
>
> Estimado/a [Encargado]:
>
> A pesar de nuestros recordatorios anteriores, el pago de [Concepto] correspondiente a [Período]
> continúa pendiente, con [Días] días de atraso.
>
> Total a pagar: L [Total]
>
> Le solicitamos comunicarse con la administración antes del [Fecha] para regularizar su situación o
> establecer un acuerdo de pago. Nuestro interés es encontrar una solución que funcione para su
> familia.
>
> Puede contactarnos al [Teléfono] o al correo [Correo], en horario de [Horario].
>
> Atentamente,
> Administración - [Institución]

El aviso final mantiene el tono de solución y no de amenaza. La institución educativa tiene una
relación continua con esa familia, no una gestión de cobranza de una sola vez.

---

## 9. Documentos relacionados

- [00-principios-de-diseno.md](00-principios-de-diseno.md)
- [04-patrones-de-interaccion.md](04-patrones-de-interaccion.md)
- `docs/14-glosario.md`
- `docs/adr/ADR-0011-internacionalizacion-y-multimoneda.md`
