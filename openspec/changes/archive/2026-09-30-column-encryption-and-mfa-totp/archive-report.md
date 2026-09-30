# Informe de archivado: `column-encryption-and-mfa-totp`

- **Fecha de archivado:** 2026-09-30
- **Cambio 7 de F0, segunda de tres partes.** Quedan pendientes `password-recovery-token` y
  `session-tokens-and-web-layer`.
- **Tareas:** 15 de 15 cerradas.
- **Entregado en trece pull requests** fusionados a `main`: #50, #51 y #52 (corte C1); #53 y #54
  (C2); #55, #56 y #57 (C3); #58 y #59 (C4); #60 y #61 (C5); y #62, la corrección posterior a la
  verificación.
- **Verificación:** `verify-report.md`. Terminó con 1 CRITICAL, resuelto antes de archivar, 3 WARNING
  y 3 SUGGESTION.

## Capacidades publicadas

La fusión de los deltas se hizo **a mano**, como en los cambios anteriores, porque
`sdd-archive-compose` no sabe leer los encabezados `### Requisito:` en español
(`Gentleman-Programming/gentle-ai#4797`). Se aplicaron las tres comprobaciones del precedente:
**prefijo original intacto**, **bytes añadidos idénticos a los del delta** (SHA-256 comparado) y
**conteos que cuadran**.

| Capacidad | Antes | Después | Delta aplicado |
|---|---|---|---|
| `identity` | 19 req / 37 escen | **29 req / 56 escen** | 10 añadidos (18 escen) y 2 **sustituidos** (5 escen, que reemplazan 4) |
| `build-integrity` | 42 req / 101 escen | **44 req / 107 escen** | 2 añadidos (6 escen) |

Las dos sustituciones se verificaron como tales, no como añadidos, y cada una ocupa la posición del
requisito que reemplaza:

- «MFA obligatoria para roles con escritura financiera o de configuración» → «MFA obligatoria para
  cuentas marcadas con `mfa_required`» (posición 2 de 29).
- «Resultado tipado de la autenticación con dos desenlaces» → «Resultado tipado de la autenticación
  con cuatro desenlaces» (posición 9 de 29).

Las notas de corrección fechadas que viven dentro de los requisitos (el límite de tasa TOTP) se
publican tal cual: son parte del contrato y explican por qué el texto dice lo que dice.

## Decisiones del propietario registradas durante el cambio

1. **El secreto TOTP se devuelve en base32 ahora; `otpauth://` y el QR, después** (2026-09-29). La
   verificación de C3 encontró que la inscripción no devolvía el secreto, así que ninguna aplicación
   de autenticación podía aprenderlo. El identificador URI exige emisor y etiqueta por institución, y
   pertenece al cambio que construya la pantalla de inscripción.
2. **El límite de tasa TOTP es la misma regla que el inicio de sesión** (2026-09-30, opción a de
   CRITICAL-1). El requisito decía «5 intentos por 15 minutos», copiado de `docs/03` §4.3; la
   regla reutilizada retrasa desde el tercer fallo, tiene tope de 900 s y reinicia el contador tras
   30 minutos. Se aceptó el comportamiento real, más estricto al principio, y se corrigieron el
   requisito, la decisión 8 del diseño y `docs/03` §4.3.
3. **La nota de §4.1 que nombraba un criterio de éxito se trata como errata del criterio**
   (WARNING-1): ninguna tarea la pedía, y su contenido ya está en la nota de §4.3.

## Lo que este cambio deja escrito para los siguientes

| Pendiente | Dueño |
|---|---|
| Traducir `SecondFactorRequired` y `SecondFactorEnrollmentRequired` a tokens de sesión, y **materializar el retraso** que devuelven `AuthenticateWithPassword` y `VerifyTotpCode` (WARNING-3) | `session-tokens-and-web-layer` |
| Derivar `mfa_required` del permiso del rol y eliminar la doble fuente | Cambio 8, `rbac-permission-matrix-and-audit-integration` |
| Recifrado por lotes de los valores bajo una DEK `retired` | Cambio 9, `background-jobs-with-db-scheduler` |
| `otpauth://` y QR de inscripción, con emisor y etiqueta por institución | El cambio que construya la pantalla de inscripción |
| Envío real del aviso de códigos de recuperación bajos | **Sin destino identificado en el roadmap** |
| Recuperación de contraseña | `password-recovery-token`, sin heredar nada de este cambio |

Dos pruebas de inventario dejarán de ser ciertas a propósito cuando llegue su dueño y habrá que
retirarlas en ese momento: `IdentityScopeExclusionInventoryTest.noScheduledJobReencryptsARetiredDataEncryptionKeyYet`
(cambio 9) y la ausencia de adaptador de envío en `ConsumeRecoveryCodeIT`.

## Verificación final

`main` @ `85c7213`, CI con la puerta de mutación bloqueante:

- `BUILD SUCCESS`: 186 pruebas de `kernel`, 325 unitarias y 162 de integración de `app`, cero
  fallos.
- JaCoCo cumplido, incluido el 95 % sobre `com.confia.*.domain`.
- PIT: `kernel` 99 %, paquetes `domain` de `app` 94 %; umbral de 80.
- Los 29 escenarios, con **PRUEBA** completa.

## El patrón que se repitió, y dónde cortarlo

Cuatro veces en este cambio una aserción pasó sin poder distinguir el caso correcto del peligroso
(`apply-progress.md`, C4). La verificación encontró una quinta, y la más cara, porque vivía en el
diseño y no en una prueba: el escenario del sexto intento pasaba con cualquiera de las dos reglas de
retroceso, y la decisión que las declaraba idénticas nunca se contrastó con las constantes del
código.

**Lección para los cambios siguientes:** cuando una decisión de diseño afirma que dos cosas son «la
misma», la fase de diseño cita la constante del código que lo demuestra, y la fase de tareas
incluye un escenario que falle si dejaran de serlo.
