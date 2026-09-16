---
description: Revisión completa de un cambio antes de fusionar, con foco financiero, de seguridad y de calidad
argument-hint: [rama o ruta a revisar]
---

Vas a revisar el cambio en $ARGUMENTS antes de que se fusione.

Ejecuta la revisión en este orden y **no la declares aprobada** si algún paso reporta un hallazgo
bloqueante.

1. **Reglas no negociables.** Delega al agente `confia-code-reviewer` la verificación contra
   `CLAUDE.md`. Presta atención especial a:
   - Uso de coma flotante para dinero (`double`, `float`, `new BigDecimal(double)`, `number` con
     aritmética en el frontend) o comparación de importes con `BigDecimal.equals`.
   - Aritmética monetaria fuera de `Money` (módulo `kernel`) o del paquete `domain` del módulo que
     posee la regla, o redondeo de importes en el navegador.
   - Campos de saldo mutables.
   - Borrado o edición de registros financieros en lugar de reverso.
   - Escrituras financieras sin idempotencia, sin transacción o sin bloqueo.
   - Acciones sensibles sin escritura en la bitácora de auditoría.

2. **Seguridad.** Delega al agente `confia-security-auditor`. Invoca la skill
   `confia-security-checklist` y marca cada casilla aplicable.

3. **Fiscal.** Si el cambio toca facturación, correlativos o impuestos, delega al agente
   `confia-fiscal-compliance`. Cualquier regla no confirmada se marca como pendiente de
   validación con el contador, no se asume.

4. **Reglas de dependencia.** Ejecuta la verificación de capas y de fronteras entre módulos:
   ArchUnit y Spring Modulith (`./mvnw verify` en `apps/api`) en el backend, `dependency-cruiser`
   en el frontend. Si cambió la API, confirma que la diferencia del OpenAPI está declarada y que
   `packages/contracts` se regeneró con orval.

5. **Pruebas.** Confirma que las pruebas nuevas cubren los casos límite obligatorios y que se
   cumplen los umbrales de cobertura.

6. **Trazabilidad SDD.** Confirma que cada comportamiento nuevo corresponde a un escenario de la
   especificación del cambio.

Reporta los hallazgos clasificados en **bloqueante**, **importante** y **sugerencia**, cada uno
con archivo, línea, motivo y corrección propuesta. Termina con un veredicto explícito de si el
cambio puede fusionarse.
