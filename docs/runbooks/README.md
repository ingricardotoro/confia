# Runbooks de operación

Procedimientos para atender incidentes en producción.

## Cómo se usan

Un runbook se escribe para ser seguido **a las dos de la mañana, bajo presión, por alguien que no
recuerda los detalles**. Por eso:

- Los pasos están numerados y son literales.
- Los comandos se pueden copiar y pegar.
- No hay teoría ni explicaciones de fondo. Eso vive en la documentación de arquitectura.
- Los pasos destructivos llevan advertencia explícita antes del comando.

Si mientras sigues un runbook descubres que un paso está mal o falta información, **corrígelo en el
momento**. Un runbook desactualizado es peor que ninguno, porque genera confianza injustificada.

## Índice

| Runbook | Severidad | Cuándo |
|---|---|---|
| [restauracion-de-respaldo.md](restauracion-de-respaldo.md) | Crítica | Pérdida o corrupción de datos, o restauración a un punto anterior |
| [rango-cai-agotado.md](rango-cai-agotado.md) | Crítica | El sistema no puede emitir facturas |
| [descuadre-de-libro-mayor.md](descuadre-de-libro-mayor.md) | Crítica | La verificación de integridad detectó una transacción descuadrada |
| [incidente-de-seguridad.md](incidente-de-seguridad.md) | Crítica | Sospecha o confirmación de acceso no autorizado |
| [cierre-de-caja-con-diferencia.md](cierre-de-caja-con-diferencia.md) | Alta | Un arqueo no coincide con lo esperado |

## Regla de simulacro

Un runbook que nunca se ejecutó es una hipótesis, no un procedimiento.

| Runbook | Cadencia de simulacro |
|---|---|
| Restauración de respaldo | Mensual, obligatorio, con acta. Una vez al año se ejecuta en un proveedor distinto de AWS como simulacro de salida (ADR-0014) |
| Incidente de seguridad | Semestral, en mesa |
| Los demás | Anual, o tras cualquier cambio que los afecte |

Cada runbook lleva la fecha de su última prueba. Si esa fecha tiene más de un año, considera que el
procedimiento no es confiable.

## Escalamiento

Con un solo desarrollador no hay guardia rotativa. El escalamiento realista es:

1. **Desarrollador responsable del sistema.** Primer y único responsable técnico.
2. **Dirección de la institución.** Para decisiones que afectan la operación: suspender cobros,
   comunicar a las familias, autorizar una restauración con pérdida de datos.
3. **Contador de la institución.** Para cualquier decisión con implicación fiscal.
4. **Proveedor de hosting o de la pasarela.** Cuando el problema está de su lado.

Esta ausencia de redundancia está registrada como el riesgo de mayor exposición del proyecto. Ver
`docs/11-riesgos.md`.
