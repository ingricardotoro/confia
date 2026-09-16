# CONFIA — Registro de riesgos

> Estado: **Propuesta v1.0** — pendiente de aprobación del propietario del producto.
> Este registro se referencia desde `docs/09-roadmap-y-fases.md` y desde `docs/01-arquitectura.md`.
> Los identificadores de riesgo (R01, R02...) son estables: no se renumeran al agregar o cerrar
> riesgos, para que las referencias cruzadas en otros documentos sigan siendo válidas.

---

## 1. Escala

**Probabilidad** (1 a 5): qué tan probable es que el riesgo se materialice durante el proyecto.

| Valor | Significado |
|---|---|
| 1 | Improbable. No se espera que ocurra. |
| 2 | Posible. Podría ocurrir en circunstancias inusuales. |
| 3 | Probable. Es razonable esperar que ocurra al menos una vez. |
| 4 | Muy probable. Ocurre en la mayoría de proyectos comparables. |
| 5 | Casi seguro. Ya es una condición presente del proyecto. |

**Impacto** (1 a 5): qué tan grave es la consecuencia si el riesgo se materializa.

| Valor | Significado |
|---|---|
| 1 | Menor. Molestia sin efecto en dinero, datos ni calendario. |
| 2 | Bajo. Retraso o costo pequeño, contenido en una fase. |
| 3 | Moderado. Retraso de una fase completa o corrección visible al cliente. |
| 4 | Alto. Pérdida de confianza del cliente, exposición de datos limitada o parada operativa de días. |
| 5 | Catastrófico. Pérdida de dinero o datos financieros, exposición de datos de menores, o el proyecto no puede continuar con el desarrollador actual. |

**Exposición** = probabilidad × impacto (rango 1 a 25).

**Umbrales de acción:**

| Exposición | Nivel | Acción |
|---|---|---|
| 15 a 25 | Crítico | Mitigación activa obligatoria, revisión semanal, escalado inmediato al propietario del producto al detectarse. |
| 8 a 14 | Alto | Mitigación activa, revisión mensual en la cadencia normal del registro. |
| 4 a 7 | Medio | Monitoreo con indicador de alerta temprana, revisión trimestral. |
| 1 a 3 | Bajo | Aceptado o monitoreo pasivo, revisión semestral. |

---

## 2. Registro principal

Ordenado por exposición descendente.

| ID | Riesgo | Categoría | Prob. | Impacto | Exposición | Mitigación | Plan de contingencia | Indicador de alerta temprana | Estado |
|---|---|---|---|---|---|---|---|---|---|
| R01 | Factor de bus igual a uno: un solo desarrollador conoce todo el sistema | Organizacional | 5 | 5 | 25 | Documentación viva obligatoria en cada cambio SDD; ADR por cada decisión estructural; código convencional y aburrido en vez de ingenioso; automatización máxima de la operación (contenedores, infraestructura como código, integración y despliegue continuo); transferencia tecnológica progresiva desde F0, intensiva en F10 y F12. Ver sección 3. | Runbooks de `infra/` permiten a un tercero levantar el entorno sin el desarrollador presente; el acceso al repositorio y a las credenciales queda en custodia también del propietario del producto, no solo del desarrollador. | Una sesión de traspaso no puede completarse sin explicación adicional del desarrollador; el departamento de sistemas no logra ejecutar el runbook de restauración en el simulacro del hito H11. | En mitigación continua desde F0 |
| R02 | Agotamiento o vencimiento del rango CAI sin que nadie lo note | Fiscal | 3 | 5 | 15 | Panel de control de rangos CAI (brecha A5) con alerta por porcentaje consumido y por proximidad de la fecha límite de emisión; bloqueo controlado antes del agotamiento. | Procedimiento documentado para solicitar un nuevo rango al SAR con antelación mínima declarada; modo de emisión manual temporal fuera del sistema mientras se resuelve, con reconciliación posterior obligatoria de los documentos emitidos manualmente. | Consumo del rango CAI supera el 80 %, o quedan menos de 30 días para la fecha límite de emisión. | Se cierra en F5, monitoreo continuo después |
| R05 | Compromiso del portal público expuesto a internet | Seguridad | 3 | 5 | 15 | Proceso separado `confia-api-portal` con rol de PostgreSQL mínimo, seguridad a nivel de fila obligatoria, dominio de identidad propio, limitación de tasa agresiva y revisión de seguridad dedicada antes de publicar (ver arquitectura sección 5). | Aislamiento de red inmediato del proceso portal sin afectar el proceso administrativo; rotación inmediata de credenciales del rol de base de datos del portal; el portal puede desactivarse por completo sin detener la operación interna de la institución. | Tasa de error o de intentos de autenticación fallidos anómala; el límite de tasa se activa repetidamente desde el mismo origen. | Se cierra en F8, monitoreo continuo después |
| R07 | Cambio de normativa fiscal hondureña durante el desarrollo | Fiscal | 3 | 4 | 12 | Puerto de emisión de documentos fiscales con un adaptador aislado para el régimen CAI; toda regla fiscal concreta se valida con el contador de la institución antes de especificar, nunca después de implementar (ver F5). | El adaptador fiscal se actualiza sin tocar `ledger`, `payments` ni `charges`; período de operación manual temporal para la emisión si el cambio normativo no puede absorberse a tiempo. | Comunicado oficial del SAR sobre cambio de régimen; el contador reporta una inconsistencia entre el sistema y la normativa vigente. | Monitoreo continuo desde F5 |
| R08 | Dependencia del proveedor de pasarela bancaria y sus tiempos de respuesta | Externo | 4 | 3 | 12 | Integración con página o campos alojados del proveedor, nunca formulario de tarjeta propio (brecha A9); F9 no inicia sin credenciales de prueba funcionando y documentación del webhook disponible. | Si el proveedor no responde a tiempo, F9 se pospone y se avanza F10 sin bloquear el resto del roadmap; el cobro en ventanilla y la transferencia notificada siguen disponibles mientras tanto. | El proveedor no entrega credenciales de prueba ni documentación del webhook en el plazo acordado antes de iniciar F9. | Aplica desde F9 |
| R09 | Alcance creciente sin control | Gestión | 4 | 3 | 12 | Ciclo SDD obligatorio: ninguna funcionalidad se implementa sin especificación aprobada; el producto mínimo viable queda fijado en F0 a F5; cada fase cierra por criterios de salida verificables, no por sensación de avance. | Toda solicitud fuera del alcance aprobado se convierte en una propuesta SDD nueva evaluada contra el roadmap vigente, nunca insertada directamente en una fase en curso. | Una fase supera su duración declarada en más del 20 % sin que el criterio de salida haya cambiado de definición. | Monitoreo continuo, cadencia mensual |
| R11 | Calidad de los datos migrados desde el sistema actual | Datos | 4 | 3 | 12 | Herramienta de migración con modo de simulación repetible, reporte de rechazos y acta de conciliación revisada registro por registro junto con la administración (ver F1). | La migración no se ejecuta en modo definitivo hasta que el reporte de rechazos esté en cero o explícitamente aceptado por la institución; los registros rechazados se cargan manualmente y quedan auditados. | El reporte de rechazos de una simulación supera el umbral acordado con la administración. | Se cierra en F1 |
| R13 | Deuda técnica acumulada por presión de entrega | Técnico | 4 | 3 | 12 | Reserva del 10 % de cada fase para gestión de deuda técnica; ningún elemento del núcleo financiero permanece en la lista de deuda más de una fase (ver roadmap sección 6). | Si la deuda del núcleo financiero se acumula, la fase siguiente no inicia hasta resolverla, aunque implique renegociar la fecha con el propietario del producto. | Un elemento de deuda técnica del núcleo financiero aparece en la lista priorizada de dos fases consecutivas. | Monitoreo continuo, cadencia por fase |
| R18 | Agotamiento del desarrollador único | Organizacional | 3 | 4 | 12 | Duraciones del roadmap calculadas con reuniones, soporte y correcciones ya descontadas (ver roadmap sección 1); margen del 20 % comunicado al cliente; la reserva de deuda técnica evita presión acumulada silenciosa. | Una fase puede extenderse sin comprometer la calidad del núcleo financiero; el propietario del producto se informa con anticipación de cualquier ajuste de calendario, nunca al final de la fase. | Horas efectivas por debajo de lo planificado durante dos semanas consecutivas; criterios de salida de una fase pospuestos más de una vez. | Monitoreo continuo, revisión mensual |
| R03 | Descuadre del libro mayor | Financiero | 2 | 5 | 10 | Doble partida obligatoria verificada por restricciones de base de datos; asientos inmutables; trabajo nocturno de integridad que verifica que toda transacción cuadre (ver arquitectura sección 6 y F3). | El trabajo de integridad aísla la transacción descuadrada y bloquea nuevas operaciones sobre la cuenta afectada hasta revisión manual; nunca se corrige editando, siempre con un asiento de ajuste aprobado. | Alerta del trabajo nocturno de integridad; divergencia entre el saldo cacheado y el saldo derivado del libro. | Mitigado por diseño desde F3 |
| R04 | Fuga de datos personales de menores | Privacidad | 2 | 5 | 10 | Cifrado de identificadores sensibles; minimización de datos recolectados; seguridad a nivel de fila; redacción de campos sensibles en logs; registro de actividades de tratamiento (brecha B9). | Procedimiento documentado de notificación de brechas con plazos declarados hacia la institución y, si aplica, hacia la autoridad correspondiente; aislamiento inmediato del componente comprometido. | Alerta de acceso anómalo o masivo a tablas con datos de estudiantes; intento de exportación fuera de los flujos autorizados. | En mitigación desde F0, autoservicio en F8 |
| R06 | Pérdida de la base de datos sin respaldo probado | Continuidad | 2 | 5 | 10 | Respaldo automático de RDS for PostgreSQL con restauración a un punto en el tiempo dentro de AWS, más respaldo lógico nocturno cifrado fuera de sitio en un proveedor distinto al de cómputo; regla tres, dos, uno; simulacro mensual de restauración documentado (brecha B10, F0, ADR-0014). | Runbook de recuperación ante desastre con objetivo de punto de recuperación de 15 minutos dentro de AWS y objetivo de tiempo de recuperación de 4 horas; ante la pérdida total de la cuenta de AWS, recuperación desde el último respaldo lógico fuera de sitio, límite aceptado explícitamente en ADR-0014. | Fallo de un respaldo automático de RDS o del respaldo lógico nocturno; un simulacro de restauración excede el objetivo de tiempo de recuperación. | Mitigado por diseño desde F0 |
| R14 | Error de cálculo de mora reclamado por un padre de familia | Financiero | 3 | 3 | 9 | Cálculo determinista y reproducible en el paquete `domain` del módulo `collections` del backend, con cobertura del 95 % y pruebas de mutación; vista de desglose línea por línea que la administración puede mostrar al padre (ver F6). | Reverso del recargo mal calculado con asiento de ajuste y motivo registrado; corrección de la regla en el motor de mora con prueba de regresión que cubra el caso reportado. | Más de un reclamo sobre el mismo tipo de cálculo en un período corto. | Se cierra en F6 |
| R16 | Desvío de efectivo en ventanilla | Control interno | 3 | 3 | 9 | Sesiones de caja obligatorias con apertura, movimientos, cierre con conteo declarado frente a esperado y diferencia justificada (brecha B4, F4); segregación de funciones: el aprobador de una diferencia no es el cajero. | Toda diferencia no justificada activa un flujo de investigación administrativa con la bitácora de auditoría como evidencia; posible suspensión temporal de acceso a caja del usuario involucrado. | Diferencias recurrentes en el cierre de un mismo cajero, aunque sean individualmente pequeñas. | Se cierra en F4 |
| R10 | Resistencia del personal administrativo al cambio | Organizacional | 4 | 2 | 8 | El arqueo de caja se presenta como protección del cajero honesto, no como desconfianza (ver F4); manuales de usuario por rol y ambiente de práctica con datos ficticios desde F4; capacitación antes de cada puesta en producción. | Acompañamiento presencial del desarrollador durante las primeras semanas de operación de cada módulo nuevo; canal directo de soporte para dudas del personal. | Cajeros o administrativos que evitan el sistema y vuelven a registros paralelos en papel o en hojas de cálculo. | Aplica desde F4 |
| R15 | Doble cobro por falta de idempotencia | Financiero | 2 | 4 | 8 | Cabecera `Idempotency-Key` obligatoria con índice único en toda escritura financiera desde F0; bloqueo de reenvío en la interfaz; idempotencia también sobre el webhook de la pasarela (F9). | Detección automática del cobro duplicado por la restricción única; reverso inmediato del segundo cobro con notificación al encargado afectado. | Intento de escritura con clave de idempotencia repetida rechazado por el índice único; se monitorea como indicador temprano, no se espera que llegue a producir un doble cobro real. | Mitigado por diseño desde F0 |
| R12 | Indisponibilidad o pérdida de la cuenta del proveedor de hosting (AWS) | Externo | 2 | 3 | 6 | Todo el sistema contenerizado y reproducible desde una imagen fijada por digest; infraestructura como código en `infra/tofu/` con OpenTofu; política de portabilidad de ADR-0014 (solo servicios de AWS con protocolo estándar en el código); PostgreSQL sin puerto público; respaldo lógico fuera de sitio en un proveedor distinto de AWS, con la llave de descifrado también fuera de AWS. | Reconstrucción completa del entorno en un proveedor alternativo a partir de la imagen por digest y del respaldo lógico fuera de sitio más reciente, siguiendo el plan de salida de ADR-0014 y el runbook de recuperación ante desastre; simulacro de salida anual en un proveedor distinto de AWS. | Incidentes de disponibilidad recurrentes de AWS; incumplimiento del acuerdo de nivel de servicio contratado; fallo del simulacro de salida anual. | Monitoreo continuo |
| R17 | Expectativas del cliente no alineadas con el alcance contratado | Gestión | 2 | 3 | 6 | Roadmap con fases, criterios de salida y producto mínimo viable comunicados y aprobados por escrito; hitos con demostración al cliente y acta de observaciones al cierre de cada fase (ver roadmap sección 5). | Toda expectativa fuera del alcance aprobado se documenta como solicitud de cambio y se evalúa contra el roadmap vigente antes de comprometerse a una fecha. | El propietario del producto solicita funcionalidad de una fase posterior antes de que la fase actual cierre sus criterios de salida. | Monitoreo continuo |

---

## 3. Mitigación del factor de bus (R01)

Este es el riesgo estructural más grave del proyecto, no porque sea el más probable de
materializarse en un incidente puntual, sino porque **ya es una condición presente**: hoy, todo el
conocimiento operativo del sistema vive en una sola persona. Un sistema financiero que solo una
persona sabe operar no es un sistema que la institución controla, es un sistema que la institución
alquila de facto a esa persona, aunque el código le pertenezca por contrato.

La mitigación no es un evento al final del proyecto, es una disciplina continua desde F0:

1. **Documentación viva en el repositorio.** Los documentos de `docs/` se actualizan en el mismo
   pull request que el código que describen. Un cambio que contradice la documentación no se
   fusiona. La documentación no es un entregable aparte que se pueda posponer.
2. **Transferencia tecnológica temprana y no al final.** Empieza en F0 con el acceso al
   repositorio y a la infraestructura, y se hace intensiva en F10 y F12, pero no es un evento
   único de traspaso al cierre del proyecto. Cada hito de la sección 5 del roadmap involucra al
   departamento de sistemas de la institución cuando el módulo lo justifica.
3. **Código convencional y aburrido en lugar de ingenioso.** Se prefiere la solución que cualquier
   desarrollador competente de Java en el backend o de TypeScript en el frontend pueda entender en
   minutos sobre la solución elegante que
   solo el autor puede mantener. Esto es una regla de revisión, no una preferencia estética.
4. **Automatización de la operación.** Contenedores, infraestructura como código, integración y
   despliegue continuo, y runbooks ejecutables reducen la cantidad de conocimiento tácito que
   existe únicamente en la memoria del desarrollador. Un runbook que no puede ejecutar un tercero
   sin ayuda no cuenta como documentado.

La advertencia central: **un sistema que solo una persona sabe operar es un sistema que la
institución no controla.** Cada entregable de este proyecto se evalúa también contra esa pregunta:
si el desarrollador desapareciera mañana, ¿podría el departamento de sistemas de la institución
levantar el entorno, restaurar un respaldo y entender qué se decidió y por qué?

---

## 4. Riesgos aceptados conscientemente

| Riesgo aceptado | Justificación |
|---|---|
| Sin equipo de guardia fuera de horario | Un desarrollador único no puede ofrecer disponibilidad continua 24/7. Se acepta a cambio de alertas accionables (ver `docs/07-observabilidad-y-operaciones.md`) y runbooks ejecutables por el departamento de sistemas, en vez de contratar personal de guardia adicional que el presupuesto del proyecto no contempla. |
| Base de datos de una sola zona de disponibilidad y un solo servidor de aplicación en la fase uno | Superado por `docs/adr/ADR-0014-proveedor-de-nube-aws-y-portabilidad.md`: PostgreSQL ya no corre en contenedor propio en producción, sino como RDS for PostgreSQL, un servicio gestionado con respaldo automático y restauración a un punto en el tiempo (ADR-0012, ADR-0014). El riesgo que sigue vigente y se acepta conscientemente es el de fase uno con una sola instancia EC2 y una sola zona de disponibilidad de RDS, sin conmutación por error automática: se acepta a cambio del costo estimado en ADR-0014, mitigado por los respaldos automáticos de RDS, el respaldo lógico fuera de sitio y el simulacro mensual de restauración. La fase dos (F8 en adelante) agrega una segunda instancia EC2 y una réplica de lectura de RDS (ADR-0014). |
| No se usa Kubernetes | Se acepta menor elasticidad de escala y mayor trabajo manual ante un pico de tráfico extremo, a cambio de una carga operativa que un desarrollador solo puede sostener con Docker Compose. Migrar a un orquestador más adelante no requiere cambiar la aplicación. |
| Facturación electrónica fuera del alcance actual | Se acepta operar bajo el régimen CAI vigente en vez de construir el soporte para facturación electrónica desde el día uno, porque el puerto de emisión de documentos fiscales se diseña desde el inicio para que migrar sea agregar un adaptador y no reescribir el módulo `invoicing`. |
| Tiempo completo como escenario de planificación principal | El roadmap se dimensiona para dedicación exclusiva del desarrollador. Se acepta que un escenario de tiempo parcial casi duplica el calendario (ver roadmap sección 1), en vez de fragmentar el proyecto en un equipo que la restricción de un solo desarrollador excluye por decisión de arquitectura. |

---

## 5. Proceso de revisión del registro

- **Cadencia general:** revisión mensual de todo el registro, coincidiendo con el cierre de
  sprint o con el cierre de fase, lo que ocurra primero. Se recalcula la exposición de cada riesgo
  activo y se actualiza el estado de su mitigación.
- **Quién revisa:** el desarrollador único prepara la actualización del registro; el propietario
  del producto la revisa y la aprueba en la misma sesión. Ningún riesgo crítico cambia de estado
  sin que el propietario del producto lo haya visto.
- **Escalado inmediato:** un riesgo que alcanza o supera exposición 15 se escala al propietario del
  producto fuera de la cadencia mensual, en un plazo máximo de 48 horas desde que se detecta,
  independientemente de si corresponde revisión ese mes.
- **Riesgos nuevos:** cualquier riesgo identificado durante el desarrollo se agrega al registro de
  inmediato con una evaluación inicial de probabilidad e impacto, aunque no le corresponda todavía
  una revisión completa.
- **Cierre de un riesgo:** un riesgo se marca cerrado únicamente cuando la fase que lo mitiga
  cumplió sus criterios de salida verificables en `docs/09-roadmap-y-fases.md`, no por sensación de
  que el trabajo relacionado ya se hizo.
- **Vínculo con el roadmap:** este registro es la referencia obligatoria para la sección de riesgos
  de cada fase del roadmap; una fase no cita un riesgo que no exista aquí con su identificador.
