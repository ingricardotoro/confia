# CONFIA — Metodología de desarrollo dirigido por especificaciones

> El proyecto se desarrolla bajo SDD (Spec-Driven Development). Ninguna funcionalidad se
> implementa sin una especificación aprobada. Esta no es una preferencia de estilo: con un solo
> desarrollador y un sistema que maneja dinero, la especificación es el único mecanismo que
> impide que una regla de negocio viva únicamente en la cabeza de una persona.

---

## 1. Por qué SDD en este proyecto

Un sistema financiero falla de forma silenciosa. Un error de cálculo de mora no lanza una
excepción: produce un número plausible que nadie cuestiona hasta que un padre reclama seis meses
después. Para entonces, el código cambió tres veces y nadie recuerda cuál era la regla correcta.

La especificación resuelve exactamente eso. Fija el comportamiento esperado **antes** de escribir
el código, en un lenguaje que el propietario del producto puede leer y aprobar, y que después se
convierte directamente en pruebas automatizadas. Cuando alguien pregunta por qué el sistema cobró
lo que cobró, la respuesta está escrita y versionada.

Beneficio adicional y nada menor: mitiga el factor de bus. El conocimiento del negocio queda en
el repositorio, no en la memoria del único desarrollador.

---

## 2. El ciclo completo

```
explorar → proponer → especificar → diseñar → tareas → aplicar → verificar → archivar
```

| Fase | Pregunta que responde | Artefacto | Agente |
|---|---|---|---|
| **Explorar** | ¿Qué existe hoy y qué opciones hay? | Notas de exploración | `sdd-explore` |
| **Proponer** | ¿Qué vamos a cambiar y por qué? | `proposal.md` | `sdd-propose` |
| **Especificar** | ¿Cómo debe comportarse? | `specs/<capacidad>/spec.md` (delta) | `sdd-spec` |
| **Diseñar** | ¿Cómo lo vamos a construir? | `design.md` | `sdd-design` |
| **Tareas** | ¿En qué pasos verificables? | `tasks.md` | `sdd-tasks` |
| **Aplicar** | Implementación | Código y pruebas | `sdd-apply` |
| **Verificar** | ¿Cumple el contrato? | Informe de verificación | `sdd-verify` |
| **Archivar** | Consolidar el conocimiento | Spec vigente actualizada | `sdd-archive` |

Cada fase tiene una **puerta de aprobación humana**. El agente no avanza solo de proponer a
especificar: el propietario del producto aprueba explícitamente.

---

## 3. Comandos disponibles

| Comando | Qué hace |
|---|---|
| `/sdd-init` | Inicializa el contexto SDD del proyecto. Ejecutar una vez, después de que exista el primer código. |
| `/sdd-new <idea>` | Explora y crea una propuesta de cambio nueva. |
| `/sdd-explore <tema>` | Investiga sin comprometerse a un cambio. |
| `/sdd-continue` | Avanza a la siguiente fase del cambio activo. |
| `/sdd-ff` | Recorre de una vez las fases de planificación, desde propuesta hasta tareas. |
| `/sdd-status` | Estado del cambio activo y qué falta. |
| `/sdd-verify` | Verifica que la implementación cumple especificación, diseño y tareas. |
| `/sdd-archive` | Cierra el cambio y consolida la especificación vigente. |

---

## 4. Estructura de artefactos

```
openspec/
├── project.md              # Contexto permanente del proyecto
├── specs/                  # Especificaciones VIGENTES por capacidad
│   ├── identity/spec.md
│   ├── ledger/spec.md
│   ├── payments/spec.md
│   ├── cashbox/spec.md
│   ├── invoicing/spec.md
│   └── .../spec.md
├── changes/                # Cambios EN CURSO
│   └── <id-del-cambio>/
│       ├── proposal.md
│       ├── design.md
│       ├── tasks.md
│       └── specs/          # Deltas: qué se agrega, modifica o elimina
│           └── <capacidad>/spec.md
└── archive/                # Cambios completados, con fecha
```

**Regla dura:** las especificaciones de `specs/` solo se actualizan al **archivar**. Durante un
cambio en curso, lo que se escribe es el delta dentro de la carpeta del cambio. Así, `specs/`
siempre refleja lo que el sistema hace hoy, no lo que se pretende que haga.

---

## 5. Formato de especificación

Las especificaciones describen **comportamiento observable**, nunca implementación.

```markdown
### Requisito: Aplicación de un pago a cargos pendientes

El sistema DEBE aplicar un pago a los cargos pendientes del estudiante siguiendo la política de
imputación configurada, y NO DEBE aplicar a un cargo un monto mayor a su saldo pendiente.

#### Escenario: Pago que cubre exactamente dos cargos

- **DADO** un estudiante con dos cargos pendientes de 1,500.00 HNL y 2,000.00 HNL
- **Y** la política de imputación configurada como "más antiguo primero"
- **CUANDO** se registra un pago de 3,500.00 HNL
- **ENTONCES** ambos cargos quedan en estado "pagado"
- **Y** el saldo del estudiante queda en 0.00 HNL
- **Y** se registran dos asientos de aplicación en el libro mayor

#### Escenario: Pago que excede la deuda total

- **DADO** un estudiante con un único cargo pendiente de 1,000.00 HNL
- **CUANDO** se registra un pago de 1,200.00 HNL
- **ENTONCES** el cargo queda en estado "pagado"
- **Y** se genera un saldo a favor de 200.00 HNL
- **Y** el saldo a favor queda disponible para cargos futuros
```

Criterios de calidad de una especificación:

- Cada requisito usa **DEBE** para lo obligatorio y **NO DEBE** para lo prohibido.
- Cada requisito tiene al menos dos escenarios: el camino esperado y un caso límite o de error.
- Los escenarios llevan datos concretos, con montos, fechas y estados reales. Un escenario que no
  se puede convertir directamente en una prueba automatizada está mal escrito.
- Nada de detalles de implementación. Ni nombres de tabla, ni de clase, ni de framework.

---

## 6. Regla de trazabilidad

Todo elemento del sistema debe poder rastrearse hacia atrás:

```
prueba automatizada → escenario de especificación → requisito → propuesta → objetivo de negocio
```

Si una prueba no corresponde a ningún escenario, o un escenario no tiene prueba, hay una brecha
que se resuelve antes de archivar el cambio.

---

## 7. Cómo se combina con el ciclo de vida completo

SDD cubre desde el análisis hasta la verificación. El resto del ciclo de vida se articula así:

| Etapa del ciclo | Cómo se cumple en CONFIA |
|---|---|
| Análisis de requerimientos | Fases explorar y proponer. `docs/00-vision-y-alcance.md`, `docs/10-analisis-de-brechas.md`. |
| Especificación | Fase especificar. `openspec/specs/`. |
| Diseño de arquitectura | `docs/01-arquitectura.md` y los ADR. |
| Diseño detallado | Fase diseñar. `design.md` de cada cambio. |
| Planificación | Fase tareas. `tasks.md`. `docs/09-roadmap-y-fases.md`. |
| Implementación | Fase aplicar, siguiendo CLAUDE.md y las skills del proyecto. |
| Pruebas | `docs/06-estrategia-de-testing.md`. Las pruebas derivan de los escenarios. |
| Revisión | Agente revisor y revisión dual antes de fusionar. |
| Despliegue | `docs/05-infraestructura-y-despliegue.md`. |
| Operación y monitoreo | `docs/07-observabilidad-y-operaciones.md` y los runbooks. |
| Mantenimiento y evolución | Cada cambio vuelve a entrar por el ciclo SDD. |
| Cierre y transferencia | `confia-tech-writer`. Objetivos 12 a 15 del requerimiento original. |

---

## 8. Reglas de disciplina

1. **Si te piden implementar algo sin especificación, detente y propón crear el cambio primero.**
2. Un cambio se mantiene pequeño. Si el `tasks.md` supera unas quince tareas, el cambio es
   demasiado grande y se divide.
3. La especificación se aprueba antes de escribir código. No al revés, no en paralelo.
4. Un cambio no se archiva si la verificación reporta brechas.
5. Las decisiones de arquitectura que surjan durante un cambio se elevan a un ADR. No se entierran
   en el `design.md`.
6. La especificación es el contrato con el propietario del producto. Se escribe en un lenguaje
   que él pueda leer y objetar.

---

## 9. Primer cambio recomendado

Una vez creado el repositorio de código, el primer cambio SDD debería ser la fase F0 del roadmap:
fundaciones de identidad, autorización, auditoría y despliegue. Es la base sobre la que todo lo
demás se apoya, y es donde se prueba que el ciclo completo funciona antes de aplicarlo a lógica
financiera compleja.

Comando sugerido para arrancar:

```
/sdd-new fundaciones de identidad, autorizacion basada en permisos y bitacora de auditoria inmutable
```
