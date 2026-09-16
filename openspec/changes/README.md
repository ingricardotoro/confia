# openspec/changes

Cambios en curso del ciclo SDD de CONFIA. Un cambio vive aquí desde que se propone hasta que se
archiva; ver `openspec/README.md` para el ciclo de vida completo y `docs/13-metodologia-sdd.md`
para el detalle de cada fase y sus comandos.

## Cómo se crea un cambio

1. Explorar el tema con `/sdd-explore <tema>` cuando hay incertidumbre real sobre el enfoque, o
   pasar directo a `/sdd-new <idea>` cuando el alcance ya está claro.
2. `/sdd-new` crea la carpeta `openspec/changes/<id-del-cambio>/` y redacta `proposal.md`.
3. El propietario del producto aprueba la propuesta. Solo entonces se redactan los deltas de
   especificación por capacidad, `design.md` y `tasks.md`, en ese orden, cada uno con su propia
   puerta de aprobación.
4. `/sdd-apply` implementa siguiendo esos tres artefactos. `/sdd-verify` confirma que la
   implementación cumple el contrato. `/sdd-archive` cierra el ciclo.

## Artefactos de un cambio

```
changes/<id-del-cambio>/
├── proposal.md              # Qué se cambia, por qué, y qué queda fuera de alcance
├── design.md                 # Cómo se construye: decisiones técnicas y alternativas descartadas
├── tasks.md                   # Lista de tareas verificables, en orden de ejecución
└── specs/
    └── <capacidad>/spec.md      # Delta: requisitos que se agregan, modifican o eliminan
```

- **`proposal.md`**: intención, alcance, fuera de alcance y justificación de negocio. Es lo
  primero que aprueba el propietario del producto.
- **`specs/<capacidad>/spec.md`**: el delta de comportamiento observable, en el mismo formato
  que las especificaciones vigentes (requisito con **DEBE**/**NO DEBE** y al menos dos
  escenarios). Un cambio puede tocar el delta de varias capacidades si su alcance real lo
  requiere.
- **`design.md`**: decisiones técnicas de implementación y las alternativas descartadas. Una
  decisión de arquitectura que surge aquí se eleva a un ADR en `docs/adr/`; no se entierra en
  este archivo.
- **`tasks.md`**: pasos verificables y ordenados que ejecuta la fase de aplicar.

## Convención de nombres del identificador del cambio

`kebab-case` en inglés técnico o en el idioma del resto de identificadores del repositorio,
corto y descriptivo del **resultado**, no de la capacidad tocada ni de la mecánica interna. Por
ejemplo `bloqueo-por-intentos-fallidos`, no `identity-change-1`. El identificador se usa igual
para la carpeta del cambio y para la rama de Git.

## Ciclo hasta el archivado

Un cambio pasa de `changes/` a `archive/` únicamente después de que `/sdd-verify` confirma que la
implementación cumple especificación, diseño y tareas sin brechas. Al archivar, el delta de
especificación se fusiona en `openspec/specs/<capacidad>/spec.md` y la carpeta completa del
cambio se mueve a `openspec/archive/<fecha>-<id-del-cambio>/`. Un cambio no se archiva con
verificación pendiente o con brechas reportadas.

## Regla de tamaño

**Un cambio con más de quince tareas en `tasks.md` es demasiado grande y se divide.** Quince es
el límite, no el objetivo: un cambio pequeño se revisa mejor, se verifica más rápido y reduce el
riesgo de dejar una regla financiera a medio implementar. Si `/sdd-tasks` produce una lista más
larga, la propuesta se parte en cambios secuenciales o paralelos antes de continuar a diseño.
