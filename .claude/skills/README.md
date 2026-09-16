# Skills del proyecto CONFIA

Las skills son procedimientos reutilizables que se cargan cuando la tarea lo requiere. A diferencia
de la documentación, están escritas en modo imperativo y con criterios verificables: no explican,
instruyen.

## Regla de activación

**Antes de cada respuesta, verifica si alguna skill aplica a lo que se pide.** Si aplica, cárgala
antes de generar la respuesta. Saltarse este paso en un sistema financiero produce código que viola
reglas que están escritas y que nadie leyó.

Varias skills pueden aplicar a la vez. Un endpoint de registro de pago activa `confia-money-rules`,
`confia-ledger-invariants`, `confia-api-conventions`, `confia-audit-logging` y
`confia-security-checklist`.

## Índice

| Skill | Se dispara cuando | Propósito |
|---|---|---|
| [confia-money-rules](confia-money-rules/SKILL.md) | Importes, precios, tarifas, totales, saldos, descuentos, becas, recargos, mora, impuestos, redondeo, formateo de moneda | Cómo se representa y se opera el dinero. Prohibición de coma flotante |
| [confia-ledger-invariants](confia-ledger-invariants/SKILL.md) | Libro mayor, asientos, saldos, estados de cuenta, cargos, pagos, imputación, reversos, ajustes | Invariantes del libro de doble partida y qué hacer ante un descuadre |
| [confia-security-checklist](confia-security-checklist/SKILL.md) | Antes de fusionar, al crear un endpoint, al tocar autenticación, autorización, consultas o archivos | Lista de verificación por tipo de cambio y patrones peligrosos a rechazar |
| [confia-sar-invoicing](confia-sar-invoicing/SKILL.md) | Facturación, CAI, correlativo, nota de crédito, anulación, impuesto sobre ventas, punto de emisión | Reglas del régimen fiscal hondureño y cuándo detenerse a validar |
| [confia-module-scaffold](confia-module-scaffold/SKILL.md) | Crear un módulo nuevo del backend o estructurar código Java de Spring Boot | Las cuatro capas hexagonales, sus reglas y sus plantillas |
| [confia-api-conventions](confia-api-conventions/SKILL.md) | Crear o modificar un endpoint, definir un DTO, versionar la API | Rutas, códigos de estado, errores, idempotencia y paginación |
| [confia-ui-screen-recipe](confia-ui-screen-recipe/SKILL.md) | Crear una pantalla, listado, formulario o diálogo | Receta paso a paso con TanStack y el sistema de diseño |
| [confia-testing-playbook](confia-testing-playbook/SKILL.md) | Escribir pruebas, decidir qué probar, corregir un defecto | Qué nivel corresponde a cada caso y los casos límite obligatorios |
| [confia-audit-logging](confia-audit-logging/SKILL.md) | Acción sensible, cambio de configuración, movimiento de dinero, cambio de permisos, acceso a datos personales | Qué se audita, cómo se encadena y qué se redacta |

## Skills por tipo de tarea

| Tarea | Skills a cargar |
|---|---|
| Endpoint financiero nuevo | `money-rules`, `ledger-invariants`, `api-conventions`, `audit-logging`, `security-checklist` |
| Módulo de backend nuevo | `module-scaffold`, `api-conventions`, `testing-playbook` |
| Pantalla del panel | `ui-screen-recipe`, `money-rules` si muestra importes |
| Cambio en facturación | `sar-invoicing`, `ledger-invariants`, `audit-logging` |
| Corrección de un defecto | `testing-playbook`, más la del dominio afectado |
| Revisión antes de fusionar | `security-checklist`, más las del dominio afectado |
| Cambio de esquema | `ledger-invariants` si toca el libro, `testing-playbook` |

## Skills frente a agentes frente a documentación

Se confunden con facilidad, así que conviene la distinción:

- **La documentación en `docs/`** explica **por qué**. Se lee para entender y para decidir.
- **Las skills** dicen **cómo hacerlo**. Se cargan al ejecutar una tarea concreta.
- **Los agentes en `.claude/agents/`** definen **quién lo hace** y con qué límites, incluidos sus
  criterios para detenerse y escalar.

Una skill no reemplaza a la documentación: la destila en instrucciones accionables. Cuando una skill
y un documento se contradicen, gana el documento y la skill se corrige.

## Cómo agregar una skill

Una skill nueva se justifica cuando un procedimiento se repite y equivocarse tiene costo. Si es
conocimiento que se consulta una vez, es documentación. Si es una regla que hay que aplicar cada
vez, es una skill.

1. Carpeta con su nombre en kebab-case, prefijada con `confia-`.
2. Archivo `SKILL.md` con frontmatter de nombre y descripción. La descripción debe contener
   **disparadores concretos**, porque de ella depende que la skill se active cuando corresponde.
3. Cuerpo imperativo, con ejemplos de código correcto e incorrecto.
4. Registro en la tabla de este archivo.

Una descripción vaga es el fallo más común: una skill que nunca se dispara es una skill que no
existe.
