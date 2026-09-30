# Informe de verificación: `frontend-monorepo-and-contracts-pipeline` (F0, cambio 3)

- **Fecha:** 2026-09-30
- **Rama verificada:** `main` @ `b94fedb`, con los tres cortes fusionados: 3a (#64), 3b (#65) y 3c
  (#66)
- **Naturaleza:** diagnóstico. No certifica aprobación; el propietario decide sobre cada hallazgo.

**Veredicto:** **0 CRITICAL, 4 WARNING, 4 SUGGESTION.** Las 13 tareas están cerradas y los tres
trabajos de la integración continua, en verde. De los 18 escenarios del delta:
- 13 tienen una **PRUEBA** que corre en cada construcción;
- 4 quedan **DEMOSTRADOS** una vez y registrados, como el diseño anunció en su §5;
- 1 es **PARCIAL**, apoyado en la semántica documentada de la herramienta.

Los ocho criterios de aceptación de la propuesta se cumplen. El cambio puede archivarse.

---

## 1. Verificación ejecutada

| Evidencia | Dónde | Resultado |
|---|---|---|
| `backend verify` (`./mvnw verify`) | Ejecuciones del PR #66 | `BUILD SUCCESS`: 343 unitarias y 162 de integración de `app`, cobertura cumplida, PIT de `domain` 94 % |
| `frontend verify` | Ejecución 223 | `Tasks: 10 successful, 10 total`, `Cached: 0 cached` |
| `security scanning` | Ejecución 223 | Maven y Trivy en verde; `pnpm audit` sin vulnerabilidades en ningún nivel |
| Demostraciones de las tareas 1.4, 2.1, 2.4 y 3.2 | `apply-progress.md` | Registradas con su salida literal |
| Versión de Node y de pnpm en la integración continua | Registro de la ejecución 223 | «Resolved .nvmrc as 24», `node: v24.21.0`, «Successfully updated pnpm to v12.8.1» |
| CI de `main` tras la fusión de #66 | Ejecución 228 | En curso al redactar este informe; su resultado se registra en el PR de archivado |

## 2. Trazabilidad escenario por escenario

| # | Requisito | Escenario | Verificación | Estado |
|---|---|---|---|---|
| 1 | OpenAPI coincide con la instantánea | Diferencia no declarada rompe la construcción | `OpenApiContractSnapshotTest.aSnapshotAlteredByHandFailsNamingTheFirstDifferenceAndIsLeftUntouched` y `theUpdateFlagRewritesTheSnapshotAndStillFails` | PRUEBA |
| 2 | | Documento idéntico a la instantánea | `eachProcessPublishesExactlyItsApprovedSnapshot` | PRUEBA |
| 3 | | Cada superficie tiene su propio documento | `theTwoProcessesPublishTwoDistinctDocumentsAndThePortalServesNoAdminPath` | PRUEBA (su mitad de rutas es cierta de vacío hoy; SUGGESTION-2) |
| 4 | Swagger solo en local y preproducción | Perfil de producción | `OpenApiExposureByProfileTest.neitherTheDocumentNorSwaggerUiAnswersOnAnyOtherProfile` | PRUEBA |
| 5 | | Perfil local | `theDocumentAndSwaggerUiAnswerOnTheLocalAndPreprodProfiles` | PRUEBA |
| 6 | Esquemas transversales | El importe viaja como cadena | `moneyTravelsAsTwoRequiredStringsAndNeverAsANumber` | PRUEBA |
| 7 | | Problem Details presente | `problemDetailDeclaresTheRfc9457FieldsAndTheTraceId` | PRUEBA |
| 8 | Contratos generados | Cambio incompatible del contrato | `money.test-d.ts`, y demostración de la tarea 2.4 (`Expected: string, Actual: number`) | PRUEBA |
| 9 | | Contrato sin cambios | `pnpm turbo run typecheck` en cada ejecución | PRUEBA |
| 10 | | La salida generada no está en el repositorio | `contracts.test.ts`, «is ignored by Git» | PRUEBA |
| 11 | Reglas de dependencia | Un paquete importa de una aplicación | `dependency-rules.test.ts` | PRUEBA |
| 12 | | El portal importa el contrato administrativo | `dependency-rules.test.ts` y `eslint-restrictions.test.ts` | PRUEBA |
| 13 | | Código que respeta las reglas | `dependency-rules.test.ts`, 19 módulos analizados | PRUEBA |
| 14 | Versión única de Node y pnpm | Versión de Node distinta | Demostración de la tarea 2.1 (`ERR_PNPM_UNSUPPORTED_ENGINE`) | DEMOSTRADO |
| 15 | | Archivo de bloqueo desactualizado | Demostración de la tarea 2.1 (`--frozen-lockfile`) | DEMOSTRADO |
| 16 | Integración continua | Empuje con un error de tipos | Ejecución 224 (`TS2322`), revertido en `aaf92d2` | DEMOSTRADO |
| 17 | | Vulnerabilidad crítica en pnpm | Observación real: `undici` 7.29.0, dos avisos altos, código 1 | DEMOSTRADO |
| 18 | | Solo severidad baja | Semántica documentada de `--audit-level high` y paso de informe que no bloquea | PARCIAL (WARNING-2) |

El escenario 8 tiene prueba permanente y, además, se demostró a mano en la tarea 2.4; cuenta como
PRUEBA. Total: 13 PRUEBA, 4 DEMOSTRADO y 1 PARCIAL.

## 3. Criterios de aceptación de `proposal.md`

| Criterio | Estado |
|---|---|
| `./mvnw verify` genera y compara; alterar rompe, restaurar devuelve el verde | **Cumplido** (escenario 1, con control negativo permanente) |
| `prod` sin Swagger ni documento; `local` con ambos | **Cumplido** (escenarios 4 y 5; también `preprod` y un perfil inexistente) |
| `Money` y `ProblemDetail` en la instantánea, `amount` como cadena | **Cumplido** (escenarios 6 y 7) |
| Instalación congelada y Turborepo en verde en máquina limpia con Node fijado | **Cumplido** (ejecución 223) |
| Los contratos se regeneran; cambiar `amount` rompe la prueba de tipos | **Cumplido** (escenario 8) |
| Cada regla de dependency-cruiser y de ESLint rechaza su violación | **Cumplido** (escenarios 11 y 12, y las tres pruebas de ESLint) |
| `frontend verify` corre y el escaneo de pnpm rompe ante alta o crítica | **Cumplido** (escenarios 16 y 17) |
| Criterio de salida 7 de F0 marcado como cerrado | **Cumplido** (`docs/09`, con la prueba) |

## 4. Hallazgos

### WARNING-1 — Tres desviaciones del diseño aprobado y una decisión que el diseño no preveía, todas registradas

1. `onlyBuiltDependencies` ya no existe en pnpm 11 o posterior, que lo ignora sin avisar. Se usa
   `strictDepBuilds` con `allowBuilds`.
2. pnpm 12 solo lee de `.npmrc` las claves de credenciales y registros. `engineStrict` vive en
   `pnpm-workspace.yaml`.
3. **El escaneo de pnpm con Trivy no escaneaba casi nada** (15 de 270 paquetes). La puerta es
   `pnpm audit`.
4. El diseño no decía cómo se distinguen los dos documentos. El título lo fija el lanzador y no un
   `@Bean`, por el escaneo cruzado de `bootstrap`.

Ninguna afecta al contrato publicado. **Resuelto en el archivado:** `design.md` lleva notas fechadas
en las decisiones 2, 6, 7 y 10.

### WARNING-2 — Escenario 18 sin demostración en vivo

Todas las observaciones del árbol eran de `undici`, y el `override` las eliminó. No queda ninguna
baja o moderada con la que mostrar que el escaneo informa sin bloquear. Se deja así, declarado. La
primera observación baja real que aparezca lo demostrará en el paso de informe.

### WARNING-3 — Cuatro escenarios se demostraron una sola vez

Son el 14, el 15, el 16 y el 17. `design.md` §5 lo anunció.
Aun así, nada impide hoy que un cambio futuro quite `engineStrict` o `--frozen-lockfile` sin que
ninguna prueba falle. **Resuelto en el archivado:** `tooling/src/workspace-guards.test.ts`
afirma que `engineStrict`, `strictDepBuilds`, el rango de `engines.node`, `--frozen-lockfile`, la
verificación de tipos y `pnpm audit --audit-level high` siguen presentes. Con `strictDepBuilds` cambiado
a `false`, su prueba falla; restaurado, vuelve a verde.

### WARNING-4 — Una rama de dependencias del CI de `main` vigila menos de lo que parece

El paso de Trivy sobre pnpm pasa siempre que no haya avisos en las dependencias de producción, hoy
`zod` y las suyas. Está declarado en su comentario, pero conviene recordarlo al leer un verde.

### SUGGESTION-1 — Riesgo para el cambio 13

`IdentityScopeExclusionInventoryTest.noPlaywrightTestExercisesTheAuthenticationFlowYet` recorre
`apps/` sin excluir `node_modules`. En cuanto exista `apps/admin-web/node_modules`, encontrará
archivos `*.spec.js` de dependencias. Debe excluirse antes de crear la primera aplicación.

### SUGGESTION-2 — Aserción de rutas cierta de vacío

La disyunción entre las rutas de `admin` y `portal` no puede fallar mientras ninguno de los dos
documentos tenga operaciones. Tendrá contenido con el primer controlador. Queda dicho en la prueba.

### SUGGESTION-3 — El contenido del artefacto no se inspeccionó

La ejecución del PR #66 sí subió el artefacto `backend-reports` (826 KB, disponible hasta el
2026-10-14), y la ruta `apps/api/app/target/openapi/` está en su lista. Pero desde esta sesión no
se puede descargar para confirmar que dentro están los dos documentos.

### SUGGESTION-4 — `process-entry-point-isolation` ya tiene dueño

El escaneo cruzado de `bootstrap` quedó en el plan de F0 como cambio 15. Se menciona aquí para que
el archivado lo arrastre como pendiente heredado.

## 5. Recomendación

Archivar. Se recomienda que la nota de WARNING-1 y la prueba de WARNING-3 vayan en el mismo PR de
archivado: son pequeñas y cierran los dos hallazgos que tienen solución hoy.
