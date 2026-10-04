# Informe de archivo: aislamiento de los puntos de entrada por proceso

- **Cambio:** `process-entry-point-isolation` (F0, cambio 15)
- **Archivado:** 2026-10-04
- **Estado de cierre:** completo — todas las tareas implementadas y verificadas

## Artefactos

| Artefacto | Ubicación | Estado |
|-----------|-----------|--------|
| proposal.md | `openspec/changes/archive/2026-10-04-process-entry-point-isolation/proposal.md` | presente |
| design.md | `openspec/changes/archive/2026-10-04-process-entry-point-isolation/design.md` | presente |
| specs/build-integrity/spec.md (delta) | `openspec/changes/archive/2026-10-04-process-entry-point-isolation/specs/build-integrity/spec.md` | presente |
| tasks.md | `openspec/changes/archive/2026-10-04-process-entry-point-isolation/tasks.md` | presente |
| apply-progress.md | `openspec/changes/archive/2026-10-04-process-entry-point-isolation/apply-progress.md` | presente |
| verify-report | N/A | no entregado (verificación opcional) |

## Síntesis de implementación

Las 10 tareas del cambio fueron completadas en dos pull requests encadenados y fusionados a `main`:

- **PR #78** (fusionado como commit `4548e72`): tareas 1.1, 1.2 y 2.1  
  - Aislamiento de contextos con `ProcessBeanPolicy`, `ProcessBeanInspector` y `ProcessBeanIsolationTest`
  - Movimiento de los tres puntos de entrada a `bootstrap.admin`, `bootstrap.portal` y `bootstrap.worker`
  - Prueba negativa permanente con `ProcessBeanInspectorTest`
  - Revisión detectada y resuelta: commit `89ee958` agregó dos casos negativos faltantes para `identity` prohibido en portal y `OpenAPI` prohibido en trabajador

- **PR #79** (fusionado como commit `9051058`): tareas 3.1 a 6.1, más seguimiento de revisión S1/S2  
  - Reglas de ArchUnit con fixture permanente en `BootstrapEntryPointRulesTest`
  - ADR-0024: registro explícito por punto de entrada
  - Documentación: `docs/01-arquitectura.md`, `docs/09-roadmap-y-fases.md`
  - Skill `confia-module-scaffold` §4: cómo registrar un módulo
  - Javadoc actualizado en `ProcessApiInfo`, `package-info.java`, `JooqInstitutionRepository` e `IntegrationTestApplication`

**Criterios de éxito verificados:**

- [x] La prueba de lista de permitidos falla contra el estado actual, evidencia en rojo registrada
- [x] Tras el cambio, cada contexto contiene solo beans de su lista de permitidos
- [x] El portal no contiene beans de `bootstrap.admin`, `bootstrap.worker` ni módulos administrativos
- [x] El trabajador no contiene `ContractSchemas` ni `ProcessApiInfo`
- [x] Ningún punto de entrada usa `@SpringBootApplication` ni `@ComponentScan`
- [x] Prueba negativa demuestra que el inspector detecta beans fuera de la lista
- [x] Dos reglas de ArchUnit rechazan su fixture de violación deliberada
- [x] `SpringModulithVerificationTest`, `ConfiaApplicationTest` y OpenAPI siguen en verde
- [x] `./mvnw verify` completo: Surefire 186 + 414, Failsafe 225, JaCoCo en umbral
- [x] ADR-0024 aceptado y aclara el significado de «perfil administrativo»
- [x] Skill `confia-module-scaffold` §4 explica cómo registrar un módulo
- [x] Diff dentro del presupuesto de revisión

## Fusión de especificaciones

**Delta:** `openspec/changes/archive/2026-10-04-process-entry-point-isolation/specs/build-integrity/spec.md`

**Destino:** `openspec/specs/build-integrity/spec.md`

| Métrica | Antes | Después | Cambio |
|---------|-------|---------|--------|
| Requisitos | 53 | 61 | +8 |
| Escenarios | 132 | 151 | +19 |
| Líneas del archivo | 1440 | 1651 | +211 |

**Verificación de integridad:**

- ✓ Prefijo pre-existente (líneas 1–13, cabecera y inicio del apartado de requisitos): byte-idéntico al original
- ✓ SHA-256 del contenido apendido: `e7b5fd8ec21a591a5998baa9c3e4c1d0ebc0dfc42fa179eb2ac6fdb0fdde43ed`
- ✓ Bytes apendidos: 11798
- ✓ Requisitos nuevos: 8 (nombrados con formato `### Requisito:` en español neutro profesional)
- ✓ Escenarios nuevos: 19 (nombrados con formato `#### Escenario:`)
- ✓ Conteos coinciden: 53 + 8 = 61 requisitos; 132 + 19 = 151 escenarios

Los 8 requisitos nuevos registran los comportamientos verificables que garantizan el aislamiento de procesos:

1. Cada proceso registra solo beans de `com.confia.*` incluidos en su lista de permitidos
2. El contexto del portal no contiene beans de otros puntos de entrada ni de módulos administrativos
3. El contexto del trabajador no contiene beans de los otros puntos de entrada ni sus importaciones
4. Los puntos de entrada se registran de forma explícita, sin escaneo implícito
5. Los subpaquetes de `bootstrap` no dependen entre sí
6. Nada fuera de `bootstrap` referencia una clase de entrada
7. La aserción sobre db-scheduler en administración y portal es vacua hasta el cambio 9
8. Prueba negativa permanente del inspector de aislamiento

## Movimiento del cambio al archivo

**Origen:** `openspec/changes/process-entry-point-isolation`  
**Destino:** `openspec/changes/archive/2026-10-04-process-entry-point-isolation`

Todos los artefactos se movieron con `git mv` sin alteración de contenido. Verificación post-movimiento:
- El árbol de origen se fue completamente
- El árbol de destino es byte-idéntico a la instantánea previa al movimiento
- Todos los archivos permanecen intactos

## Riesgos y limitaciones

**Riesgos reportados en `design.md` que se materialisaron:**

Ninguno. La predicción del diagnóstico de fuga cruzada se confirmó en la tarea 1.1, y todos los pasos posteriores de la sección 7 de `design.md` convergieron correctamente.

**Conocidas limitaciones operacionales registradas en `proposal.md` y `design.md` que permanecen abiertas:**

- El portal, la administración y el trabajador prohiben nominalmente módulos (`invoicing`, `cashbox`, `reconciliation`) que todavía no tienen beans. Esas prohibiciones no se pueden ejercer hasta que el cambio 9 u otro agregue beans a esos módulos.
- La aserción de db-scheduler es vacua hasta el cambio 9, cuando esa biblioteca entre en el camino de clases. La aserción es explícita sobre esa vacuidad (requisito del delta, escenario 2).

## Entrega de autoridad final

Per la sección de Final-State Authority de `sdd-archive` SKILL.md:

- **Estado reportado en tareas.md:** 10 tareas, todas marcadas completas
- **Estado reportado en apply-progress.md:** all_done, 10/10 tareas, sin bloqueadores
- **Hechos finales de la sesión del orquestador:** ambos PR fusionados a main, verificación completa en verde, todas las métricas de cobertura cumplidas
- **Verificación:** no se entregó verify-report; la verificación es opcional y no bloquea el archivo

Todas las fuentes coinciden: el cambio está completo, verificado y listo para su archivo.

## Commits de archivo

Se crearán dos commits convencionales:

1. `docs(sdd): archive process-entry-point-isolation and sync specs`  
   Fusiona delta de especificaciones en `openspec/specs/build-integrity/spec.md` y mueve la carpeta de cambio al archivo.

2. `docs(sdd): publish process-entry-point-isolation requirements into the main specs`  
   (Alias del anterior si ambas operaciones forman un solo commit coherente, o segundo commit si se separan por claridad.)

En convención Conventional Commits, sin atribución de herramienta de IA.

## Próximas recomendaciones

Ninguna. El cambio está archivado y cerrado. El siguiente cambio del roadmap de F0 es cambio 16 (`session-tokens-and-web-layer`), que usa las configuraciones públicas `ContractSchemas` y `ProcessApiInfo` importadas aquí mediante `@Import`.

---

**Archivo generado el:** 2026-10-04  
**Cambio del roadmap:** F0, cambio 15  
**Identificador de cambio:** `process-entry-point-isolation`  
**Línea de base de fusión:** commit `9051058` (PR #79 merged to main)
