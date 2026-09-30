# Progreso de aplicación: `frontend-monorepo-and-contracts-pipeline`

- **Corte en curso:** 3a (backend: springdoc, instantáneas y criterio de salida 7)
- **Entorno:** sesión remota sin Docker ni JDK 25. Todo el corte 3a se verificó en la integración
  continua de la rama `change/frontend-monorepo-and-contracts-pipeline`, que parte de un checkout
  limpio con JDK 25 y Docker. Cada evidencia de abajo nombra la ejecución de la que sale.

---

## Tarea 1.1 — Sondas S1 y S2

**S1.** springdoc-openapi **3.1.1**. Motivo: su POM publicado declara como padre
`spring-boot-starter-parent` **4.1.0**, la misma línea que este proyecto (4.1.1). La 3.0.3 se
construyó sobre 4.0.5. Consultado en Maven Central el 2026-09-30. Ambos puntos de entrada arrancan
con springdoc y sin `DataSource`, y sirven `/v3/api-docs` con el perfil `local`: lo demuestra
`OpenApiExposureByProfileTest`, en verde desde la ejecución 214.

**S2.** El documento normalizado es determinista: `theNormalizedDocumentIsDeterministic` genera dos
veces el documento de `admin` y compara los bytes. En verde desde la ejecución 214. La normalización
ordena las claves, fija la sangría, convierte los finales de línea a LF y quita el bloque `servers`,
que depende del puerto aleatorio. `.gitattributes` (`* text=auto eol=lf`) mantiene la instantánea en
LF también en Windows.

## Tarea 1.2 — Swagger apagado por defecto

`application.yml` apaga el documento y Swagger UI y fija OpenAPI 3.1 (`springdoc.api-docs.version:
openapi_3_1`). Solo `application-local.yml` y `application-preprod.yml` los encienden.
`OpenApiExposureByProfileTest` comprueba los dos procesos con cuatro perfiles: `local` y `preprod`
responden; `prod` y un perfil inexistente dan 404 en las dos rutas.

## Tarea 1.3 — Esquemas transversales, y la caducidad de la capa Web

`ContractSchemas` publica `Money` y `ProblemDetail` en los dos documentos. `ProcessApiInfo` titula
cada documento con una propiedad que el lanzador fija por proceso. Las pruebas
`moneyTravelsAsTwoRequiredStringsAndNeverAsANumber` y
`problemDetailDeclaresTheRfc9457FieldsAndTheTraceId` están en verde.

**Punto de parada alcanzado y resuelto por el propietario.** `EmptyShouldExceptionInventoryTest`
falló, como pretende ADR-0018: `ContractSchemas` es la primera clase de producción en un paquete
`web`, que es justo la condición de caducidad que ADR-0020 §3 fijó para la capa `Web`. Se consultó
y el propietario eligió retirar la excepción (2026-09-30). En un solo commit: `optionalLayer("Web")`
pasa a `layer("Web")`, el inventario queda vacío y ADR-0020 recibe una nota fechada. Spring Modulith
aceptó el paquete nuevo con su `@NamedInterface` a la primera.

**Desviación del diseño, dicha aquí.** El diseño ponía el título en un `@Bean` por proceso. Los
tres puntos de entrada comparten el paquete `com.confia.bootstrap` y cada uno lo escanea, así que
un bean declarado en uno podría acabar en los contextos de los otros. Por eso el título lo fija el
lanzador (`ConfiaApplication.launch`) con una propiedad. **Riesgo más amplio, fuera de este
cambio:** ese mismo escaneo cruzado podría hacer que el contexto del portal cargue la configuración
de `AdminApplication` el día que esta registre un módulo administrativo, lo que va contra
ADR-0003. Se propone tratarlo en un cambio propio antes del primer módulo con `web`.

**Otra desviación menor:** las dos pruebas viven en `com.confia.bootstrap` (test), no en
`com.confia.architecture` como decía el diseño, porque los puntos de entrada son de paquete.

## Tarea 1.4 — Instantáneas y su puerta

La primera ejecución con la puerta (211) falló a propósito sin instantáneas e imprimió los dos
documentos. Se revisaron: OpenAPI 3.1.0, sin operaciones, con `Money` y `ProblemDetail`, iguales
salvo el título. Se comprometieron copiados byte a byte (`d43c8c5`). La ejecución 214 quedó en verde.

**La demostración obligatoria queda como prueba permanente**, en lugar de un empuje roto y revertido:
- `aSnapshotAlteredByHandFailsNamingTheFirstDifferenceAndIsLeftUntouched`: la misma comparación,
  sobre una copia alterada a mano, falla nombrando `$.info.title` y deja el archivo intacto.
- `theUpdateFlagRewritesTheSnapshotAndStillFails`: con la opción de actualización, la instantánea se
  reescribe y la prueba igualmente falla.

Una aserción que la puerta obliga a decir: con cero rutas en ambos documentos, la comprobación de
que el portal no sirve ninguna ruta administrativa **es cierta de vacío hoy**. Tendrá contenido con
el primer controlador.

## Tarea 1.5 — Integración continua, criterio 7 y medición

- `apps/api/app/target/openapi/` se sube dentro del artefacto `backend-reports`.
- El criterio de salida 7 de F0 queda marcado como cerrado en `docs/09-roadmap-y-fases.md`, con la
  prueba que lo demuestra.
- **Diff del corte**, sin `openspec/` ni las instantáneas: **+650 −28** en 19 archivos. Por debajo de
  800.
