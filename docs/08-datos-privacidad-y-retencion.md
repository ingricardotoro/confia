# CONFIA — Datos personales, privacidad y retención

> Estado: **Propuesta v1.0**.
> Subordinado a `docs/01-arquitectura.md`, que es la fuente de verdad de la arquitectura.
> Si algo aquí lo contradice, gana el documento de arquitectura.

Este sistema trata datos personales de **menores de edad** y de sus responsables financieros. Esa
sola condición eleva el estándar por encima de un sistema corporativo típico: el titular de los
datos no puede consentir por sí mismo, no puede evaluar el riesgo, y la consecuencia de una fuga lo
acompaña durante décadas.

Cubre la brecha **B9** de `docs/10-analisis-de-brechas.md`, de severidad bloqueante, con diseño en
fase F0 y funcionalidad de autoservicio en fase F8.

---

## 1. Marco de referencia adoptado

Se adopta el **Reglamento General de Protección de Datos** de la Unión Europea (RGPD, conocido
también como GDPR) como estándar internacional de referencia, complementado con las obligaciones
locales hondureñas de conservación fiscal y documental.

Razón de la elección, en tres puntos:

1. El RGPD es el estándar más exigente de amplia adopción. Cumplirlo raramente incumple un marco
   local más laxo, y el camino inverso es una reescritura.
2. La ambición internacional declarada en `docs/01-arquitectura.md` sección 1 implica que el
   sistema puede terminar tratando datos de residentes de países con normativa equivalente.
3. Un marco reconocido da vocabulario y estructura: base legal, minimización, derechos del titular,
   encargado del tratamiento, evaluación de impacto y notificación de brechas. Inventar una
   política propia produce huecos que nadie detecta hasta la primera solicitud formal.

> ## Advertencia legal
>
> **Este documento no es asesoría jurídica y no sustituye la validación legal en Honduras.**
>
> Las obligaciones locales concretas de la República de Honduras en materia de protección de datos
> personales, incluidos la autoridad competente, el plazo exacto de notificación de brechas, los
> requisitos formales del consentimiento y la existencia o no de un registro obligatorio de
> tratamientos, **deben ser confirmadas por asesoría legal de la institución antes de salir a
> producción.**
>
> Igualmente deben confirmarse con el contador o asesor fiscal de la institución los plazos exactos
> de conservación documental exigidos por el régimen de la SAR. Los períodos de la sección 6 de
> este documento se declaran como **supuesto no verificado** y se marcan como tales hasta que esa
> confirmación exista y quede registrada con fecha y responsable.
>
> Mientras no haya confirmación, el sistema aplica el criterio más conservador: **conservar lo que
> podría ser obligatorio conservar y restringir el acceso, en vez de eliminar y descubrir después
> que era obligatorio conservarlo.** Una eliminación equivocada no se deshace.

---

## 2. Roles y responsabilidades

| Rol del marco | Quién lo ocupa en CONFIA | Responsabilidad |
|---|---|---|
| **Responsable del tratamiento** | La institución educativa | Define las finalidades y los medios. Es quien responde ante los titulares y ante la autoridad |
| **Encargado del tratamiento** | El desarrollador o la empresa que opera el sistema, y cada proveedor de la sección 10 | Trata datos por cuenta del responsable, solo según sus instrucciones documentadas |
| **Titular** | El estudiante (menor de edad, en la mayoría de los casos) y el encargado de pago | Ejerce los derechos de la sección 5 |
| **Representante del titular menor** | El padre, madre o tutor legal | Ejerce los derechos en nombre del menor y otorga el consentimiento cuando aplica |
| **Punto de contacto de privacidad** | Designado por la institución, publicado en el portal | Recibe solicitudes de derechos y consultas |

Consecuencia práctica: **el desarrollador no decide qué datos se recolectan.** Esa es una decisión
del responsable del tratamiento, y toda ampliación del inventario de la sección 3 requiere su
aprobación explícita y la actualización de este documento.

---

## 3. Inventario de datos personales tratados

Sensibilidad: **Alta** significa que su exposición produce daño directo al titular. **Media**
significa daño en combinación con otros datos. **Baja** significa dato de contexto.

Cifrado: **Columna** significa cifrado a nivel de columna con sobre de llaves
(`docs/03-seguridad.md` sección 7.3). **Disco** significa que solo está protegido por el cifrado de
disco y de respaldo, que ya es obligatorio para toda la base de datos.

| Dato | Categoría | Sensibilidad | ¿Menor de edad? | Dónde se almacena | Cifrado |
|---|---|---|---|---|---|
| Nombre y apellidos del estudiante | Identificación | Media | **Sí** | `student.first_name`, `student.last_name` | Disco |
| Código de estudiante | Identificación interna | Baja | Sí | `student.student_code` | Disco |
| Fecha de nacimiento | Identificación | Media | **Sí** | `student.birth_date` | Disco |
| Número de documento de identidad del estudiante | Identificación oficial | **Alta** | **Sí** | `student.national_id_encrypted` | **Columna** |
| Sexo | Demográfico | Media | **Sí** | `student.sex` | Disco |
| Grado, sección y modalidad | Académico | Baja | Sí | `enrollment`, `section` | Disco |
| Estado de matrícula y motivo de retiro | Académico | Media | **Sí** | `enrollment.status`, `enrollment.withdrawal_reason` | Disco |
| Condición de becado y tipo de beca | Socioeconómico | **Alta** | **Sí** | `scholarship_award` | Disco, con acceso restringido por permiso |
| Nombre y apellidos del encargado | Identificación | Media | No | `guardian.first_name`, `guardian.last_name` | Disco |
| Documento de identidad del encargado | Identificación oficial | **Alta** | No | `guardian.national_id_encrypted` | **Columna** |
| RTN del encargado | Fiscal | Media | No | `guardian.rtn`, `fiscal_document.customer_rtn` | Disco. Obligatorio en el documento fiscal |
| Correo electrónico del encargado | Contacto | Media | No | `guardian.email`, `user.email` | Disco |
| Teléfono del encargado | Contacto | Media | No | `guardian.phone` | Disco |
| Dirección del encargado | Contacto | Media | No | `guardian.address` | Disco |
| Relación con el estudiante y responsabilidad financiera | Familiar | **Alta** | Indirecto | `guardian_student.relationship`, `financial_responsibility_percent` | Disco. Puede revelar situación de custodia |
| Contraseña | Credencial | **Alta** | No | `user.password_hash` (Argon2id con pimienta) | Hash irreversible, nunca la contraseña |
| Secreto TOTP | Credencial | **Alta** | No | Tabla de MFA | **Columna** |
| Historial de pagos y saldo | Financiero | **Alta** | Indirecto | `ledger_transaction`, `ledger_entry`, `payment`, `charge` | Disco |
| Documentos fiscales emitidos | Fiscal | **Alta** | Indirecto | `fiscal_document`, PDF en almacenamiento de objetos | Disco, más cifrado del lado del servidor en el almacenamiento |
| Estado de morosidad y gestión de cobranza | Financiero | **Alta** | Indirecto | `dunning_case`, `payment_promise` | Disco |
| Baja por incobrable | Financiero | **Alta** | Indirecto | `write_off` | Disco |
| Bitácora de envío de notificaciones | Comunicaciones | Media | No | `notification_log.recipient_hash` | Destinatario almacenado como hash, no en claro |
| Preferencias y consentimiento de comunicación | Comunicaciones | Baja | No | `notification_preference` | Disco |
| Dirección IP y agente de usuario | Técnico y de seguridad | Media | No | `shared_audit_log.source_ip_hash`, `user_agent`, logs de nginx | IP almacenada como hash en auditoría |
| Identificador de solicitud y de traza | Técnico | Baja | No | Logs, `shared_audit_log` | Disco |
| Bitácora de auditoría de acciones | Trazabilidad | **Alta** por acumulación | Indirecto | `shared_audit_log` | Disco. De solo inserción, encadenada por hash |
| Solicitudes de documentos y su motivo | Académico y administrativo | Media | **Sí** | `document_request` | Disco |
| Referencia de pago bancario | Financiero | Media | No | `payment.reference`, `bank_transaction.reference` | Disco |

### 3.1 Datos que el sistema nunca almacena

Estos no aparecen en el inventario porque **no entran al sistema**, y esa es una decisión de
arquitectura, no una omisión.

| Dato | Por qué no se almacena |
|---|---|
| Número de tarjeta, CVV, fecha de vencimiento, nombre del tarjetahabiente | Se usa página alojada o campos alojados del proveedor de pago. El alcance de cumplimiento se mantiene en el cuestionario de autoevaluación más simple. Ver `docs/03-seguridad.md` sección 16 |
| Datos de salud o discapacidad del estudiante | Categoría especial en el RGPD. No es necesaria para cobrar. Si la institución la requiere para otro fin, va en el sistema académico, no en el financiero |
| Religión, origen étnico, opinión política, afiliación sindical | Categorías especiales. Ninguna finalidad financiera las justifica |
| Fotografía del estudiante | No es necesaria para la gestión de pagos |
| Calificaciones y conducta | Pertenecen al sistema académico. CONFIA solo consume la matrícula |
| Ingresos familiares y situación laboral detallada | Aunque una beca socioeconómica podría justificarlo, se decide **no** almacenarlos: el proceso de asignación de beca se resuelve fuera del sistema y CONFIA solo registra el resultado, es decir el porcentaje de beneficio aprobado |
| Geolocalización | Ninguna finalidad la justifica |
| Contraseña en claro, en cualquier momento y en cualquier registro | Solo existe en memoria durante la verificación |

---

## 4. Base legal del tratamiento por finalidad

| Finalidad | Datos implicados | Base legal | Nota |
|---|---|---|---|
| Gestión de la matrícula y del vínculo académico y financiero | Identificación del estudiante, matrícula, vínculo con encargado | **Ejecución de contrato** entre la institución y la familia | Sin estos datos no existe la prestación del servicio educativo |
| Generación de cargos, cobro y control de cartera | Cargos, pagos, saldo, becas aplicadas | **Ejecución de contrato** | Es el núcleo del sistema |
| Emisión de documentos fiscales | Nombre, RTN, importe, concepto | **Obligación legal** (régimen SAR) | El titular no puede oponerse a un dato exigido por la ley fiscal |
| Conservación de documentación contable y fiscal | Documentos fiscales, asientos del libro mayor | **Obligación legal** | Prevalece sobre la solicitud de supresión. Ver sección 5.4 |
| Bitácora de auditoría de acciones | Actor, acción, entidad, IP hasheada, momento | **Interés legítimo** de la institución en prevenir y detectar fraude, más **obligación legal** de control interno financiero | El interés legítimo se documenta con la evaluación de la sección 4.1 |
| Notificaciones de cobro y de estado de cuenta | Correo, teléfono, saldo | **Ejecución de contrato** | Notificar una deuda es parte de la relación contractual, no es publicidad |
| Comunicaciones informativas no relacionadas con el cobro | Correo, teléfono | **Consentimiento** | Requiere consentimiento separado y revocable. Ver sección 8 |
| Seguridad del sistema, límite de tasa y detección de abuso | IP, agente de usuario, intentos fallidos | **Interés legítimo** | Retención corta y datos minimizados |
| Mejora del servicio mediante métricas agregadas | Datos agregados sin identificación | **Interés legítimo**, con datos anonimizados | Si el dato está correctamente anonimizado, deja de ser dato personal |
| Cobranza extrajudicial y gestión de mora | Cargos vencidos, promesas de pago, historial | **Ejecución de contrato** e **interés legítimo** | Sujeto a que la institución notifique antes de aplicar recargo |

### 4.1 Evaluación del interés legítimo

Para cada finalidad basada en interés legítimo se documenta la prueba de tres pasos. Ejemplo de la
bitácora de auditoría, que es la de mayor impacto:

1. **Finalidad legítima.** Prevenir el desvío de efectivo y poder demostrar quién hizo qué en un
   sistema que mueve dinero de familias. `docs/03-seguridad.md` sección 2.7 identifica al empleado
   que desvía efectivo como el actor con mayor probabilidad de causar pérdida real.
2. **Necesidad.** No existe forma menos intrusiva de lograrlo. Sin registro de actor, acción y
   momento, la institución no puede sostener una acusación ni un empleado honesto puede sostener su
   defensa.
3. **Equilibrio.** El impacto sobre el titular es bajo: se registran identificadores internos, la
   IP se almacena hasheada, el acceso a la bitácora está restringido al rol de Auditor y de Super
   Administrador, y la bitácora no se usa para ninguna finalidad distinta del control interno y la
   respuesta a incidentes. El beneficio para el conjunto de las familias, incluida la protección
   de su dinero, supera ese impacto.

---

## 5. Derechos del titular

Plazo de atención adoptado: **30 días naturales** desde la recepción de la solicitud verificada,
prorrogables por 60 días adicionales con notificación motivada al titular. Es el plazo del estándar
de referencia y se aplica mientras la asesoría legal no confirme uno local más corto.

**Verificación de identidad previa, obligatoria.** Una solicitud de derechos es también un vector
de ataque: un tercero que solicita "acceso" a los datos de un menor obtiene exactamente lo que
buscaba si el proceso no verifica. La verificación se hace por el canal ya confirmado del encargado
o de forma presencial en la institución con documento de identidad. **Nunca se atiende una
solicitud recibida por un canal no verificado.**

### 5.1 Acceso

- El encargado ve en el portal, de forma permanente y sin solicitud: el estado de cuenta completo
  de sus estudiantes vinculados, sus pagos, sus documentos fiscales y sus datos de contacto.
- Para el resto, existe la exportación del expediente completo: un archivo con todos los datos
  personales del titular y de los menores a su cargo, más las categorías de destinatarios, los
  plazos de conservación y el origen de cada dato.
- Implementación: caso de uso `ExportSubjectDataUseCase` en el módulo `guardians`, que produce un
  paquete firmado y de descarga única con expiración de 72 horas.
- **La exportación se audita siempre**, con el actor, el titular afectado y el conteo de registros,
  igual que cualquier otra exportación (`docs/03-seguridad.md` sección 5.2 regla 6).
- La entrega nunca se hace por correo con adjunto. Se entrega por descarga autenticada en el
  portal, o presencialmente.

### 5.2 Rectificación

- Datos de contacto del encargado: autoservicio en el portal, con verificación del canal nuevo
  antes de sustituir el anterior. Cambio auditado con valor anterior y posterior.
- Datos de identificación del estudiante y del encargado, incluido el documento de identidad: se
  solicitan a la institución con evidencia documental, y los modifica el personal administrativo.
  No es autoservicio porque son datos que sostienen documentos fiscales ya emitidos.
- **Un dato financiero nunca se rectifica.** Un cargo o un pago incorrecto se reversa con un asiento
  nuevo y se registra el correcto. Ver `.claude/skills/confia-ledger-invariants/SKILL.md` sección 6.
  Un documento fiscal incorrecto se corrige con nota de crédito o anulación, nunca con edición.
- **Un documento fiscal emitido no se rectifica**, aunque contenga un dato personal erróneo. La
  corrección es documental, no una edición del registro.

### 5.3 Portabilidad

- Alcance: los datos que el titular proporcionó y que se tratan por contrato o por consentimiento.
  No incluye los datos derivados por el sistema, como el cálculo de mora, ni los que existen por
  obligación legal.
- Formato: **JSON estructurado** con esquema publicado, más CSV de los movimientos financieros para
  uso en hoja de cálculo. Ambos son formatos abiertos y legibles por máquina.
- Se entrega por el mismo mecanismo que el derecho de acceso.

### 5.4 Supresión, y su límite fiscal

Este es el punto donde la mayoría de los sistemas se equivocan, en cualquiera de las dos
direcciones: borran lo que era obligatorio conservar, o ignoran la solicitud por completo.

**Regla del sistema: lo que tiene obligación legal de conservación no se suprime, se restringe.**

| Categoría de dato | ¿Se suprime a solicitud? | Qué ocurre en su lugar |
|---|---|---|
| Datos de contacto usados solo para comunicaciones opcionales | **Sí** | Eliminación efectiva y registro de la eliminación |
| Consentimiento de comunicaciones y preferencias | **Sí**, revocable siempre | Se conserva únicamente la evidencia de la revocación |
| Cuenta de acceso al portal y credenciales | **Sí** | Se elimina la credencial. El vínculo administrativo permanece |
| Datos de identificación asociados a documentos fiscales | **No** | **Restricción del tratamiento.** Ver abajo |
| Asientos del libro mayor, pagos y cargos | **No** | Restricción. El libro mayor es inmutable por diseño |
| Documentos fiscales emitidos | **No** | Restricción. Obligación fiscal de conservación |
| Bitácora de auditoría | **No** | Restricción. Es evidencia de solo inserción y encadenada |

**Cómo se implementa la restricción del tratamiento.** No es una promesa organizativa, es un
control técnico con tres capas:

```sql
-- 1. Estado explícito en el registro del titular. Nunca se borra el dato: se marca.
ALTER TABLE guardian
  ADD COLUMN processing_restricted        BOOLEAN     NOT NULL DEFAULT FALSE,
  ADD COLUMN processing_restricted_at     TIMESTAMPTZ,
  ADD COLUMN processing_restriction_basis TEXT;      -- 'subject_request' | 'legal_hold' | 'dispute'

ALTER TABLE student
  ADD COLUMN processing_restricted        BOOLEAN     NOT NULL DEFAULT FALSE,
  ADD COLUMN processing_restricted_at     TIMESTAMPTZ,
  ADD COLUMN processing_restriction_basis TEXT;

-- 2. Política de fila que excluye a los titulares restringidos de todo uso operativo.
--    El personal con permiso fiscal sigue viendo el registro; nadie más lo ve.
CREATE POLICY guardian_restriction_scope ON guardian
  FOR SELECT
  USING (
    processing_restricted = FALSE
    OR current_setting('app.purpose', true) IN ('fiscal_retention', 'legal_defense', 'audit')
  );
```

3. **Control en la capa de aplicación**: los casos de uso de notificación, cobranza, generación de
   cargos y exportación comercial excluyen a los titulares restringidos. La única lectura permitida
   es la que declara explícitamente su finalidad como conservación fiscal, defensa legal o
   auditoría, y esa lectura **se audita en cada acceso**.

Efectos concretos de la restricción, que el titular debe recibir por escrito:

- Deja de recibir cualquier comunicación, incluidas las de cobranza.
- Sus datos no aparecen en listados, reportes operativos ni exportaciones comerciales.
- Sus datos se conservan solo para cumplir la obligación fiscal y para defensa ante reclamaciones.
- Al vencer el plazo de conservación fiscal, la eliminación o anonimización ocurre automáticamente
  por la tarea de retención, sin necesidad de una solicitud nueva.

**Cómo se comprueba.** Prueba de integración: se marca un encargado como restringido, se ejecutan
los trabajos de cobranza y de notificación, y se verifica que no recibe ningún envío y que no
aparece en ningún reporte operativo. Prueba de política de fila con contexto de finalidad ausente,
esperando cero filas.

### 5.5 Oposición

- **A comunicaciones no esenciales**: efecto inmediato, sin justificación, desde el portal o desde
  el enlace de baja de cada mensaje. Ver sección 8.
- **A comunicaciones de cobranza**: la oposición no es absoluta porque la notificación es parte de
  la relación contractual y precondición para aplicar recargos. Se registra la oposición, se
  escala al canal alternativo declarado por el titular y se documenta el conflicto para decisión de
  la institución.
- **A decisiones automatizadas**: el sistema aplica recargos por mora de forma automática, lo que
  constituye tratamiento automatizado con efecto económico. Por eso el motor de mora es
  **determinista, reproducible y explicable**: ante un reclamo, el sistema debe mostrar la regla
  aplicada, sus parámetros vigentes en la fecha y el cálculo paso a paso. El titular puede
  solicitar revisión humana del recargo, y esa revisión produce, si procede, un asiento de ajuste
  con motivo y aprobación, nunca una edición.

### 5.6 Registro de solicitudes

Toda solicitud se registra en una tabla dedicada, no en un correo. Campos mínimos: identificador,
titular, tipo de derecho, canal de recepción, momento de recepción, método de verificación de
identidad, actor que la atendió, momento de resolución, resultado y evidencia entregada. La métrica
`confia_dsr_open_requests` expone las abiertas y su antigüedad, con alerta a los 25 días
(`docs/07-observabilidad-y-operaciones.md` sección 7.2).

---

## 6. Política de retención

> Los períodos con la marca **(por confirmar)** son un supuesto no verificado y deben validarse con
> asesoría legal y con el asesor fiscal de la institución antes de producción. Hasta entonces se
> aplica el criterio conservador de conservar y restringir.

| Categoría de dato | Período | Base del período | Acción al vencer |
|---|---|---|---|
| Documentos fiscales emitidos (facturas y notas de crédito) y sus PDF | **7 años** desde la emisión **(por confirmar)** | Obligación de conservación documental del régimen fiscal hondureño | Ninguna eliminación automática. Revisión manual con el contador y decisión registrada |
| Asientos del libro mayor y sus imputaciones | **7 años** desde el cierre del año lectivo **(por confirmar)** | Obligación contable y fiscal | **Anonimización** del vínculo con la persona, conservando importes, fechas y conceptos para la contabilidad histórica |
| Rangos CAI y secuencias fiscales | **7 años** desde el vencimiento del rango **(por confirmar)** | Obligación fiscal. Evidencia de correlativos y huecos | Conservación. No contienen datos personales |
| Datos de identificación del estudiante y del encargado con historial financiero | Mientras exista obligación fiscal sobre sus documentos, es decir el mismo plazo anterior | Obligación legal | Anonimización, ver sección 7 |
| Datos de identificación del estudiante sin historial financiero, matrícula no concretada | **12 meses** desde la última interacción | Minimización. No hay obligación que justifique más | **Eliminación** |
| Matrícula y vínculo académico | **5 años** desde el egreso o retiro **(por confirmar)** | Prescripción de reclamaciones y necesidad de emitir constancias posteriores | Anonimización, conservando el dato agregado de egreso |
| Documento de identidad cifrado del estudiante y del encargado | Igual al período fiscal aplicable | Obligación legal, es dato del documento fiscal | **Eliminación del valor cifrado**, conservando el registro sin él |
| Bitácora de auditoría (`shared_audit_log`) | **7 años** **(por confirmar)** | Evidencia de control interno financiero y defensa ante reclamaciones | Anonimización del actor si el actor es un titular, conservando la cadena de hash intacta. **Nunca se borran filas**: romper la cadena destruiría el valor probatorio de todo el registro |
| Bitácora de envío de notificaciones (`notification_log`) | **24 meses** | Prueba de que se notificó antes de aplicar un recargo, más un margen para reclamaciones | **Eliminación** |
| Consentimientos y revocaciones de comunicación | **3 años** desde la revocación | Prueba de que la baja se respetó | Eliminación, conservando el hash del destinatario en la lista de supresión |
| Lista de supresión de destinatarios (baja de comunicaciones) | **Permanente**, en forma de hash | Es la única forma de garantizar que una baja se respeta para siempre | Ninguna. Contiene hash, no el contacto en claro |
| Cuentas de usuario del personal | **3 años** desde la baja | Trazabilidad de acciones pasadas en la bitácora | Anonimización del nombre y correo, conservando el identificador para la auditoría |
| Cuentas de acceso del portal de encargados | **12 meses** desde el último acceso, si no hay vínculo activo | Minimización | Eliminación de la credencial. El vínculo administrativo sigue su propio plazo |
| Contraseñas y secretos TOTP | Vida de la cuenta | Necesidad operativa | Eliminación inmediata al eliminar la credencial |
| Tokens de refresco y de recuperación | Refresco: mientras esté vigente según ADR-0005 (en el portal, hasta 30 días de inactividad y 90 días absolutos); los tokens expirados y las familias revocadas se purgan pasados 30 días. Recuperación: 30 minutos | Necesidad operativa | Eliminación automática |
| Solicitudes de documentos y constancias | **3 años** desde la emisión | Reemisión y trazabilidad | Anonimización |
| Logs de aplicación y de acceso web | **30 días** en caliente, **12 meses** en frío | Investigación de incidentes y de reclamos. Contienen IP, dato personal | **Eliminación** |
| Trazas distribuidas | **7 días** | Diagnóstico inmediato | Eliminación |
| Métricas agregadas | 13 meses | Comparación interanual | Ninguna. No contienen datos personales |
| Extractos bancarios importados y conciliación | **7 años** **(por confirmar)** | Obligación contable | Conservación, con acceso restringido |
| Registros de incidentes de seguridad y evidencia asociada | **Mínimo 2 años**, o lo que indique la asesoría legal | `docs/03-seguridad.md` sección 17.5 | Revisión manual antes de eliminar |
| Respaldos de base de datos | Escalonado: 7 diarios, 4 semanales, 12 mensuales | Objetivo de recuperación y protección ante ransomware | Rotación automática. **Ver la nota de respaldos abajo** |

### 6.1 La eliminación y los respaldos

Un dato eliminado de la base de datos sigue existiendo en los respaldos hasta que esos respaldos
rotan. Es una limitación real de cualquier sistema con recuperación ante desastres, y se declara de
forma explícita en vez de ocultarse.

Política adoptada:

- **No se restaura un respaldo para eliminar un dato de él.** El riesgo de corromper una copia de
  seguridad supera al beneficio.
- Se registra la eliminación en una **lista de eliminaciones pendientes de propagación**, con el
  identificador del titular y la fecha.
- Si un respaldo se restaura por cualquier motivo, el procedimiento de restauración incluye, como
  paso obligatorio de verificación, la reaplicación de esa lista antes de reanudar la operación.
  Ver `docs/runbooks/restauracion-de-respaldo.md`.
- El dato desaparece definitivamente cuando rota el último respaldo que lo contiene, lo que ocurre
  como máximo a los 12 meses con la política escalonada declarada.
- Esto se comunica al titular en la respuesta a su solicitud de supresión, en lenguaje claro.

### 6.2 Ejecución de la retención

La tarea semanal de limpieza por retención (`docs/07-observabilidad-y-operaciones.md` sección 11)
funciona así:

1. Identifica registros vencidos por categoría, con la consulta declarada en el módulo
   correspondiente.
2. **Verifica que ninguna obligación de conservación siga vigente** sobre cada registro. Un
   registro con documento fiscal dentro del plazo nunca se procesa, aunque otra regla lo señale.
3. Aplica la acción declarada: eliminación o anonimización.
4. Escribe en la bitácora de auditoría un registro por lote, con la categoría, el criterio y el
   conteo de registros afectados. **Nunca los datos eliminados.**
5. Publica `confia_data_retention_pending_records`. Un valor mayor a cero durante más de siete días
   genera alerta S3.
6. **Nada se elimina en la misma corrida en que se detecta.** Existe un período de gracia de siete
   días entre la marca y la ejecución, para que un error de criterio se detecte antes de ser
   irreversible.

---

## 7. Anonimización y seudonimización

Diferencia que importa y que se confunde a menudo: un dato **seudonimizado** sigue siendo dato
personal, porque existe una clave que permite volver a identificarlo. Un dato **anonimizado** ya no
lo es, porque esa reversión no es posible con medios razonables.

| Dato | Técnica | Qué se preserva para análisis histórico |
|---|---|---|
| Nombre y apellidos | **Sustitución** por una etiqueta estable derivada del identificador interno, del tipo `Estudiante 4f2a` | Nada del nombre. La etiqueta permite leer un reporte histórico sin exponer identidad |
| Documento de identidad | **Eliminación** del valor cifrado y de la llave de datos asociada | Nada. El dato no tiene valor analítico |
| Fecha de nacimiento | **Generalización** al año de nacimiento, y a la cohorte de edad en los reportes | Distribución por edad y cohorte |
| Dirección | **Generalización** al municipio o al departamento | Distribución geográfica de la matrícula |
| Correo y teléfono | **Eliminación**, conservando un hash con sal en la lista de supresión | Capacidad de respetar una baja para siempre, sin conservar el contacto |
| Dirección IP en auditoría | **Hash con sal secreta** desde el momento de la escritura, nunca en claro | Correlación de acciones de una misma sesión, sin identificar la conexión |
| Destinatario de notificación | **Hash** desde el origen (`notification_log.recipient_hash`) | Conteo de envíos, tasa de entrega y de rebote por canal |
| Importes, fechas, conceptos y estados financieros | **Se preservan íntegros** | Toda la contabilidad histórica: recaudación por período, cartera, morosidad por grado y modalidad, comportamiento de pago agregado |
| Vínculo con la cuenta del libro mayor | **Seudonimización**: la cuenta conserva su identificador, el titular se anonimiza | Los asientos siguen cuadrando y los reportes contables históricos siguen siendo correctos |
| Grado, modalidad y sección | Se preservan | Análisis de morosidad y de recaudación por segmento |
| Condición de beca | **Generalización** al tramo de porcentaje, sin identificación del beneficiario | Impacto agregado de las becas sobre la recaudación |

**Regla dura sobre el libro mayor.** La anonimización de un titular **nunca modifica los asientos**.
Se anonimiza la entidad `Student` o `Guardian`; `ledger_transaction` y `ledger_entry` permanecen
intactos, con sus importes, sus fechas y su `student_account_id`. El cuadre del debe y el haber
sigue verificándose después de una anonimización, y esa verificación es parte de la prueba
obligatoria de la tarea de retención.

```sql
-- Verificación posterior a toda corrida de anonimización.
-- Debe devolver cero filas, igual que antes de ejecutarla.
SELECT t.id
FROM ledger_transaction t
JOIN ledger_entry e ON e.ledger_transaction_id = t.id
GROUP BY t.id
HAVING SUM(CASE WHEN e.direction = 'debit' THEN e.amount ELSE -e.amount END) <> 0;
```

### 7.1 Datos en entornos no productivos

**Prohibido copiar datos de producción al equipo local o a preproducción.** Ya declarado en
`docs/03-seguridad.md` sección 2.6, se reitera aquí por su relevancia para la privacidad.

Los entornos no productivos usan:

1. **Datos sintéticos generados por script**, con nombres de un catálogo ficticio, documentos de
   identidad con formato válido pero fuera de todo rango real, y correos del dominio de prueba.
2. Si un diagnóstico exige datos con forma real, se genera un volcado anonimizado con el mismo
   script de la sección 7, ejecutado **contra RDS for PostgreSQL en producción**, y solo sale de
   la red privada de AWS el resultado ya anonimizado. El volcado en claro nunca cruza la frontera
   de la red privada donde vive la base de datos (ADR-0014).

---

## 8. Consentimiento para comunicaciones

### 8.1 Qué requiere consentimiento y qué no

| Tipo de comunicación | Base | ¿Se puede dar de baja? |
|---|---|---|
| Estado de cuenta, recordatorio de vencimiento, aviso de mora, confirmación de pago, documento fiscal | Ejecución de contrato | **No de forma total.** Se puede cambiar el canal preferido, no dejar de recibirla. Es parte de la relación contractual y precondición para aplicar recargos |
| Aviso de seguridad de la cuenta: cambio de contraseña, inicio de sesión nuevo, alta de MFA | Interés legítimo en la seguridad del titular | **No.** Un aviso de seguridad del que uno puede darse de baja no protege a nadie |
| Comunicaciones institucionales generales, campañas, encuestas, promociones | **Consentimiento** | **Sí**, en cualquier momento y sin justificación |
| Comunicaciones por canal de mensajería instantánea | **Consentimiento explícito por canal**, porque el canal es más intrusivo y suele estar ligado a un número personal | **Sí** |

### 8.2 Registro del consentimiento

El consentimiento no es una casilla marcada: es un hecho con evidencia. Se registra por titular,
por canal y por finalidad, con estos campos mínimos:

| Campo | Contenido |
|---|---|
| `subject_id` y `subject_kind` | A quién pertenece |
| `channel` | `email`, `whatsapp`, `sms` |
| `purpose` | `transactional`, `institutional`, `marketing` |
| `state` | `granted`, `withdrawn`, `never_granted` |
| `granted_at`, `withdrawn_at` | Momentos exactos, hora del servidor |
| `evidence_source` | `portal_form`, `signed_paper_form`, `admin_registered` |
| `policy_version` | Versión exacta del aviso de privacidad aceptado |
| `request_id` | Correlación con la bitácora de auditoría |

Las casillas de consentimiento en el portal **nunca vienen premarcadas** y cada finalidad es una
casilla separada. Un consentimiento agrupado no es un consentimiento válido bajo el estándar
adoptado.

### 8.3 Mecanismo de baja

- Enlace de baja en **todo** mensaje de finalidad institucional o de campaña, funcional sin
  necesidad de iniciar sesión, con token de un solo uso.
- Baja también disponible desde las preferencias del portal, con el estado por canal y por
  finalidad visible.
- En canales de mensajería, se reconoce la respuesta de baja del propio canal, con las palabras que
  el proveedor y la práctica local establezcan.
- Plazo de efecto: **inmediato**. No existe "puede tardar hasta 10 días": el motor consulta el
  estado en el momento del envío, no en el momento de armar la lista.

### 8.4 Respeto obligatorio en el motor de notificaciones

Este es el control que convierte la política en realidad. Se implementa **dentro del motor**, no en
cada caso de uso que envía un mensaje, porque un control repetido en veinte lugares falla en el
lugar veintiuno.

```ts
// modules/notifications/application/use-cases/dispatch-notification.use-case.ts
async execute(cmd: DispatchNotificationCommand): Promise<DispatchResult> {
  // 1. Suppression list first. A hard bounce or a global unsubscribe wins over everything.
  if (await this.suppression.isSuppressed(cmd.recipientHash, cmd.channel)) {
    return this.log(cmd, 'suppressed', 'recipient_on_suppression_list');
  }

  // 2. Processing restriction (data subject rights). No message of any kind.
  if (await this.subjects.isProcessingRestricted(cmd.subjectId)) {
    return this.log(cmd, 'suppressed', 'processing_restricted');
  }

  // 3. Consent, only for purposes that require it. Transactional messages skip this check
  //    because their legal basis is contract, not consent.
  if (cmd.purpose !== 'transactional') {
    const consent = await this.consent.stateFor(cmd.subjectId, cmd.channel, cmd.purpose);
    if (consent !== 'granted') {
      return this.log(cmd, 'suppressed', 'consent_not_granted');
    }
  }

  // 4. Frequency cap per recipient, to avoid harassment through automation.
  if (await this.frequency.exceedsCap(cmd.recipientHash, cmd.channel)) {
    return this.log(cmd, 'suppressed', 'frequency_cap_reached');
  }

  return this.channels.for(cmd.channel).send(cmd);
}
```

Reglas que acompañan al código:

- **Ningún módulo envía un mensaje sin pasar por este caso de uso.** Una regla de ArchUnit
  prohíbe que cualquier módulo distinto de `notifications` dependa de un cliente de correo o de
  mensajería.
- Todo envío y toda supresión quedan en `notification_log` con su motivo. Esa bitácora es la prueba
  de que la institución notificó, y también la prueba de que respetó una baja.
- Un rebote permanente añade el destinatario a la lista de supresión de forma automática.
- **Cómo se comprueba.** Prueba de integración por cada rama del caso de uso: titular en lista de
  supresión, titular con tratamiento restringido, consentimiento no otorgado y tope de frecuencia
  alcanzado. En los cuatro casos se espera cero envíos al adaptador de canal y un registro con el
  motivo correcto.

---

## 9. Encargados del tratamiento

Todo tercero que trate datos personales por cuenta de la institución necesita un **acuerdo de
tratamiento de datos** firmado antes de recibir el primer dato. Sin acuerdo firmado no se integra
el proveedor, y esa es una condición de la lista de verificación previa a producción.

| Encargado | Qué datos trata | Ubicación probable | Requisitos |
|---|---|---|---|
| **Proveedor de alojamiento e infraestructura** | Todos, por almacenamiento y cómputo | **Amazon Web Services, región `us-east-1`** (EC2, RDS for PostgreSQL, S3), decisión de `docs/adr/ADR-0014-proveedor-de-nube-aws-y-portabilidad.md`. Fuera de Honduras | Acuerdo de tratamiento, cifrado en reposo, compromiso de notificación de brecha, garantías de subencargados, procedimiento de eliminación al terminar el servicio. **Ningún dato real se carga antes de la confirmación legal de este documento y de la firma del acuerdo de tratamiento** (ADR-0014) |
| **Almacenamiento de objetos para respaldos fuera de sitio** | Respaldos lógicos completos cifrados | **Backblaze B2 o Cloudflare R2, elegido en F0**, distinto del proveedor de cómputo (ADR-0014). Fuera de Honduras | Acuerdo de tratamiento, cifrado del lado del servidor, bloqueo de objeto en modo de cumplimiento, credencial sin permiso de borrado |
| **Proveedor de borde, DNS y protección DDoS** | Metadatos de tráfico HTTP (direcciones IP, encabezados) hacia los dos subdominios | **Cloudflare** (ADR-0014). Fuera de Honduras | Acuerdo de tratamiento, configuración que evita el registro de contenido de solicitudes con datos personales |
| **Proveedor de correo transaccional** | Correo del encargado, nombre, contenido del mensaje con importes | **Amazon SES, región `us-east-1`** (ADR-0014). Fuera de Honduras | Acuerdo de tratamiento, sin uso de los datos para fines propios, retención mínima del contenido, registros de entrega |
| **Proveedor de mensajería instantánea o SMS** | Teléfono, contenido del mensaje | Habitualmente fuera de Honduras, por confirmar según el proveedor elegido | Acuerdo de tratamiento, política de retención del proveedor documentada, respeto de la baja |
| **Pasarela de pago** | Nombre, correo, importe, referencia. **Nunca datos de tarjeta a través de CONFIA** | Habitualmente fuera de Honduras | Acuerdo de tratamiento, cumplimiento de la norma de la industria de tarjetas, verificación de firma de webhook, alcance de cumplimiento reducido mediante campos alojados |
| **Institución bancaria** | Datos de la transferencia y su referencia | Honduras | Relación contractual propia de la institución. Se documenta el flujo de datos |
| **Servicio de seguimiento de errores**, si se adopta | Contexto técnico. **Datos personales eliminados antes del envío** | Fuera de Honduras | Acuerdo de tratamiento, depuración de datos personales en el cliente antes de transmitir, retención corta |
| **Desarrollador u operador del sistema** | Acceso técnico a todos los datos | Honduras | Acuerdo de confidencialidad y de tratamiento, obligación de secreto que sobrevive al fin del contrato, registro de accesos a producción |

Se mantiene un **registro de actividades de tratamiento** con este inventario, la finalidad, las
categorías de datos, los destinatarios, las transferencias internacionales y los plazos de
conservación. Ese registro es el documento que una autoridad pide primero.

---

## 10. Transferencias internacionales

Si el alojamiento, el correo, la mensajería, la pasarela o el almacenamiento de objetos están fuera
de Honduras, **existe transferencia internacional de datos personales de menores**, y esa
transferencia debe estar amparada.

Implicaciones y medidas:

1. **Documentar cada transferencia** en el registro de actividades: qué datos, a qué país, a qué
   proveedor, con qué finalidad y con qué garantía.
2. **Cláusulas contractuales** en el acuerdo de tratamiento que impongan al proveedor un nivel de
   protección equivalente, incluido el compromiso de notificar cualquier requerimiento de una
   autoridad extranjera sobre esos datos, en la medida en que la ley se lo permita.
3. **Minimizar lo que sale.** El proveedor de correo no necesita el documento de identidad ni el
   detalle del expediente: recibe el destinatario y el contenido del mensaje. El proveedor de
   seguimiento de errores no necesita ningún dato personal.
4. **Cifrado en reposo y en tránsito** en todo destino, con llaves bajo control de la institución
   siempre que el servicio lo permita. Los respaldos salen del host **ya cifrados con age**, de
   modo que el proveedor de almacenamiento custodia bytes que no puede leer. Esa es la mitigación
   más efectiva de la transferencia.
5. **Preferencia por región cercana** cuando el proveedor ofrezca elección, por latencia y por
   reducir la cantidad de jurisdicciones implicadas.
6. **Punto que requiere confirmación legal:** si la normativa hondureña exige una autorización
   previa, una notificación o un requisito formal específico para la transferencia internacional,
   y si existe alguna categoría de dato que deba permanecer en territorio nacional. Sin esa
   confirmación, la decisión de proveedor no debe considerarse cerrada.
7. **Región elegida, condicionada a este punto.** `docs/adr/ADR-0014-proveedor-de-nube-aws-y-portabilidad.md`
   elige la región `us-east-1` de AWS sobre la alternativa más cercana (`mx-central-1`, México)
   porque mantiene todos los datos en una sola jurisdicción, incluido el correo transaccional por
   SES. Esa elección, y la carga de cualquier dato real de estudiantes o encargados en AWS, quedan
   **condicionadas a la confirmación legal del punto 6 y a la firma del acuerdo de tratamiento**
   (ADR-0014).

---

## 11. Procedimiento de notificación de brechas

Complementa `docs/03-seguridad.md` sección 17.4 y `docs/runbooks/incidente-de-seguridad.md`. Este
documento aporta la perspectiva del titular, aquel documento la perspectiva de la contención.

### 11.1 Qué es una brecha notificable

Toda violación de la seguridad que ocasione destrucción, pérdida, alteración, comunicación o acceso
no autorizado a datos personales. Incluye casos que no parecen ataques:

- Un correo con el estado de cuenta enviado al encargado equivocado.
- Una exportación descargada por una persona sin necesidad de conocerla.
- Un respaldo perdido, aunque esté cifrado, si existe duda sobre la custodia de la llave.
- Una computadora robada con acceso al sistema.
- Un error de política de fila que permitió a un encargado ver datos de otro estudiante.

### 11.2 Plazos

| Etapa | Plazo | Nota |
|---|---|---|
| Registro interno del incidente | Inmediato al detectarlo | `docs/incidentes/AAAA-MM-DD-<identificador>.md` |
| Evaluación de si hay datos personales afectados | Primeras 24 horas | Determinar categorías, número de titulares y cuántos son **menores** |
| Notificación a la autoridad competente | **72 horas** desde el conocimiento **(plazo de referencia, por confirmar en Honduras)** | Si falta información, se notifica lo conocido y se completa después. El plazo no se detiene mientras se investiga |
| Notificación a los titulares afectados | **Sin dilación indebida** cuando exista alto riesgo | Tratándose de menores, el criterio por defecto es **notificar** |
| Informe de cierre con causa raíz y acciones correctivas | 30 días desde la contención | Se conserva con el registro del incidente |

### 11.3 Destinatarios

| Destinatario | Cuándo | Quién comunica |
|---|---|---|
| Propietario del producto de la institución | Inmediato, en toda brecha | Desarrollador |
| Dirección de la institución | Toda brecha con datos personales | Propietario del producto |
| Asesoría legal de la institución | Toda brecha con datos personales, antes de notificar a la autoridad | Dirección |
| Autoridad de protección de datos competente | Según determine la asesoría legal, con el plazo de referencia de 72 horas | La institución, como responsable del tratamiento |
| Titulares afectados: padres y encargados | Cuando exista alto riesgo | La institución, nunca el desarrollador a título individual |
| Encargado del tratamiento implicado | Si la brecha ocurrió en su ámbito | Desarrollador |
| Proveedor de pasarela o banco | Si hay datos de pago implicados | Desarrollador y contabilidad |

### 11.4 Contenido de la notificación a la autoridad

1. Naturaleza de la brecha, con las categorías de datos y de titulares afectados.
2. Número aproximado de titulares, **con indicación expresa de cuántos son menores de edad**.
3. Momento de ocurrencia, de detección y de contención.
4. Consecuencias probables para los titulares.
5. Medidas adoptadas y propuestas, incluidas las de mitigación.
6. Datos de contacto del punto de contacto de privacidad.
7. Si la información está incompleta, lo conocido, con compromiso de ampliación.

### 11.5 Contenido de la notificación a los titulares

En lenguaje claro, sin tecnicismos, y en español:

1. Qué ocurrió, en una frase comprensible.
2. Qué datos suyos y de su hijo estuvieron afectados, con precisión y sin minimizar.
3. Cuándo ocurrió y cuándo se detectó.
4. Qué ha hecho y qué está haciendo la institución.
5. **Qué debe hacer el titular**, en pasos concretos. Por ejemplo, cambiar la contraseña si la
   reutiliza en otros servicios, y estar atento a comunicaciones que suplanten a la institución.
6. A quién contactar, con un canal real y atendido.
7. Sin lenguaje que traslade la responsabilidad al titular.

**Plantilla preparada por adelantado.** Redactar esta comunicación por primera vez durante un
incidente produce demoras y errores. La plantilla vive en `docs/plantillas/notificacion-brecha.md`
y se revisa anualmente.

> **Documento no creado todavía:** `docs/plantillas/notificacion-brecha.md` y
> `docs/incidentes/` se declaran como pendientes. Se crean antes de producción.

---

## 12. Evaluación de impacto sobre la protección de datos

### 12.1 Por qué este sistema la requiere

Una evaluación de impacto (DPIA) es obligatoria cuando el tratamiento entraña un alto riesgo para
los derechos de las personas. Este sistema cumple **al menos cuatro** criterios de forma
simultánea, y con dos ya sería exigible:

1. **Tratamiento de datos de menores de edad**, que son titulares vulnerables y no pueden evaluar
   el riesgo ni consentir por sí mismos.
2. **Tratamiento de datos financieros** a gran escala relativa: la situación de pago de una familia
   revela su situación económica, y la condición de becado la revela de forma directa.
3. **Evaluación sistemática y decisiones automatizadas con efecto económico**: el motor de mora
   aplica recargos de forma automática, y el motor de cobranza escala la gestión según el
   comportamiento de pago.
4. **Cruce de datos de distintas fuentes**: matrícula, pagos, banco y pasarela se combinan para
   producir un perfil de comportamiento de pago de la familia.

Un quinto criterio aparece si la institución activa la multi-institución: tratamiento por una
plataforma que concentra datos de varias comunidades educativas.

### 12.2 Estructura de la evaluación

La evaluación completa vive en `docs/privacidad/dpia.md`, se aprueba antes de producción y se
revisa cuando cambia el tratamiento de forma sustancial, en particular al integrar la pasarela de
pago (fase F9) y al activar la conciliación bancaria (fase F10).

> **Documento no creado todavía:** `docs/privacidad/dpia.md`. Se declara como pendiente y se
> elabora en la fase F0, con revisión en F9.

Estructura obligatoria:

| Sección | Contenido |
|---|---|
| 1. Descripción sistemática | Finalidades, naturaleza, alcance y contexto del tratamiento. Diagrama de flujo de datos por módulo, incluidos los encargados y las transferencias |
| 2. Base legal por finalidad | La tabla de la sección 4 de este documento |
| 3. Necesidad y proporcionalidad | Justificación de cada dato del inventario. Un dato sin finalidad declarada se elimina del sistema |
| 4. Consulta a interesados | Recoger la opinión de una muestra de padres y del personal administrativo. No es un trámite: son quienes detectan usos que el diseñador no anticipó |
| 5. Identificación de riesgos | Para cada riesgo: origen, naturaleza, probabilidad, gravedad e impacto sobre el titular. Se reutiliza el modelo de amenazas de `docs/03-seguridad.md` sección 2, traducido a impacto sobre la persona en vez de sobre el sistema |
| 6. Medidas de mitigación | Controles ya existentes: seguridad a nivel de fila, cifrado de columna, separación de procesos y de roles de base de datos, minimización, retención limitada, bitácora inmutable |
| 7. Riesgo residual | Riesgo que permanece tras las medidas, y decisión explícita del responsable de aceptarlo o de exigir controles adicionales |
| 8. Aprobación y revisión | Firma del responsable del tratamiento, fecha, y disparadores de revisión |

### 12.3 Riesgos principales identificados de forma preliminar

| Riesgo para el titular | Gravedad | Mitigación principal |
|---|---|---|
| Un padre accede al estado de cuenta del hijo de otra familia | Alta | Tres barreras independientes: identificadores opacos, verificación de propiedad en el caso de uso y seguridad a nivel de fila en PostgreSQL |
| Exposición de la condición socioeconómica por la condición de beca | Alta | Permiso separado para becas, sin exposición de la condición en listados generales, generalización al anonimizar |
| Exposición de la situación de custodia por el campo de relación y de responsabilidad financiera | Alta | Acceso restringido por permiso, exclusión del dato de los reportes generales |
| Fuga masiva del padrón por exportación | Alta | `export` es un permiso separado de `read`, se audita con filtro y conteo, y genera alerta por volumen |
| Fuga del documento de identidad de un menor | Alta | Cifrado a nivel de columna con sobre de llaves. Un volcado de base sin el gestor de llaves no lo revela |
| Recargo automático incorrecto con efecto económico | Media | Motor determinista y explicable, notificación previa obligatoria, derecho a revisión humana, corrección por asiento de ajuste |
| Comunicación de deuda a un destinatario equivocado | Media | Destinatario derivado del vínculo vigente, nunca de una lista manual. Bitácora de envío |
| Conservación de datos más allá de lo necesario | Media | Política de retención automatizada con métrica y alerta |
| Acceso del desarrollador a datos de producción sin control | Media | Acceso solo por túnel con llave, sesiones registradas, prohibición de copiar datos a local, auditoría de acceso interactivo |

---

## 13. Lista de verificación de privacidad antes de producción

Ninguna casilla se marca por criterio: cada una exige el artefacto o la prueba ejecutada.

### Gobierno y documentación

- [ ] La asesoría legal de la institución revisó este documento y confirmó o corrigió cada período
      marcado como **(por confirmar)**, con fecha y responsable registrados.
- [ ] El contador confirmó los plazos de conservación fiscal aplicables.
- [ ] Existe el registro de actividades de tratamiento, completo y actualizado.
- [ ] La evaluación de impacto (`docs/privacidad/dpia.md`) está redactada y aprobada por el
      responsable del tratamiento.
- [ ] Existe un punto de contacto de privacidad designado y publicado en el portal.
- [ ] El aviso de privacidad está publicado, versionado y su versión se registra con cada
      consentimiento.
- [ ] La plantilla de notificación de brecha existe y fue revisada.

### Inventario y minimización

- [ ] Cada campo de datos personales del esquema aparece en el inventario de la sección 3.
- [ ] Ningún campo del esquema carece de finalidad declarada.
- [ ] No existe en el esquema ninguna categoría especial de datos de las prohibidas en 3.1.
- [ ] El sistema no recibe ni almacena dato alguno de tarjeta, verificado con una prueba que envía
      un número con formato de tarjeta y confirma su rechazo y su redacción en logs.
- [ ] Los datos de las tareas en segundo plano contienen solo identificadores (institución,
      entidad, clave de idempotencia), nunca nombres, correos, teléfonos, documentos de identidad
      ni importes en claro. La tabla de tareas es una tabla técnica sin política de fila, por lo
      que se verifica con la prueba de lista aprobada de campos por tipo de tarea (ADR-0016).

### Controles técnicos

- [ ] El documento de identidad del estudiante y del encargado está cifrado a nivel de columna, con
      la llave fuera de la base de datos.
- [ ] El secreto TOTP está cifrado a nivel de columna.
- [ ] La seguridad a nivel de fila está activa y forzada en todas las tablas del inventario, con
      su prueba de integración correspondiente en verde.
- [ ] La lista de redacción del registro estructurado del backend cubre todos los campos personales del inventario, verificado
      por prueba.
- [ ] Los respaldos salen del host cifrados y la llave de restauración está custodiada aparte.
- [ ] La IP se almacena hasheada en la bitácora de auditoría, nunca en claro.
- [ ] El destinatario de notificación se almacena como hash.

### Derechos del titular

- [ ] El caso de uso de exportación del expediente completo funciona y produce JSON y CSV.
- [ ] El procedimiento de verificación de identidad previa a una solicitud está documentado y
      probado con un caso simulado.
- [ ] La restricción del tratamiento está implementada con la columna, la política de fila y la
      exclusión en los casos de uso, con prueba de integración que demuestra cero envíos.
- [ ] La rectificación de datos de contacto por autoservicio verifica el canal nuevo antes de
      sustituir el anterior.
- [ ] El registro de solicitudes de derechos existe y publica su métrica.
- [ ] La alerta de solicitud próxima a vencer está configurada.

### Consentimiento y comunicaciones

- [ ] El registro de consentimiento almacena canal, finalidad, momento, evidencia y versión de
      política.
- [ ] Ninguna casilla de consentimiento viene premarcada, verificado en la interfaz.
- [ ] Todo mensaje no transaccional incluye enlace de baja funcional sin inicio de sesión.
- [ ] Las cuatro ramas de supresión del motor de notificaciones tienen prueba de integración.
- [ ] Ningún módulo distinto de `notifications` puede importar un cliente de correo o mensajería,
      verificado por ArchUnit.

### Retención

- [ ] La tarea semanal de retención está programada y publica su métrica.
- [ ] La tarea verifica la obligación de conservación antes de eliminar, con prueba.
- [ ] La anonimización preserva el cuadre del libro mayor, verificado con la consulta de la
      sección 7.
- [ ] El período de gracia de siete días entre marca y eliminación está implementado.
- [ ] La lista de eliminaciones pendientes de propagación a respaldos existe y el runbook de
      restauración la incluye como paso obligatorio.

### Encargados y transferencias

- [ ] Cada encargado de la sección 9 tiene acuerdo de tratamiento firmado y archivado.
- [ ] Cada transferencia internacional está documentada con su garantía.
- [ ] El proveedor de seguimiento de errores, si se adopta, elimina datos personales antes del
      envío, verificado con una prueba.

### Brechas

- [ ] El procedimiento de notificación de brecha está documentado y sus contactos verificados.
- [ ] Existe copia impresa de los contactos de incidente, porque un incidente puede dejar sin
      acceso al repositorio.
- [ ] Se ejecutó al menos un simulacro de brecha en mesa, con acta.

---

## 14. Documentos relacionados

| Documento | Relación |
|---|---|
| `docs/01-arquitectura.md` | Fuente de verdad. Sección 1 declara el tratamiento de datos de menores |
| `docs/03-seguridad.md` | Controles técnicos: cifrado, seguridad a nivel de fila, auditoría, respuesta a incidentes |
| `docs/07-observabilidad-y-operaciones.md` | Redacción de logs, retención técnica y tarea programada de retención |
| `docs/10-analisis-de-brechas.md` | Brecha B9, protección de datos personales de menores |
| `docs/runbooks/incidente-de-seguridad.md` | Procedimiento operativo ante una brecha |
| `docs/02-modelo-de-dominio.md` | Entidades y atributos que componen el inventario de datos |
