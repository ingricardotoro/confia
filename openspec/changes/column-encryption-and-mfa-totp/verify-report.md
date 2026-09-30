# Informe de verificación: `column-encryption-and-mfa-totp` (F0, cambio 7, parte 2)

- **Fecha:** 2026-09-30
- **Rama verificada:** `main` @ `842044d`, con todos los cortes fusionados (C1 a C3c, luego C4a #58,
  C4b #59, C5.1 #60 y C5.2 #61)
- **Modo TDD:** estricto (`openspec/config.yaml`, `strict_tdd: true`)
- **Naturaleza:** diagnóstico. No certifica aprobación; el propietario decide sobre cada hallazgo.

**Veredicto:** **1 CRITICAL, 3 WARNING, 3 SUGGESTION.** La construcción completa está en verde,
incluida la puerta de mutación bloqueante de `main`. Los 28 escenarios de los dos deltas tienen al
menos una prueba real en verde: 27 con **PRUEBA** completa y 1 **PARCIAL**. El hallazgo crítico no es
de cobertura sino de contrato: el texto normativo de un requisito no describe lo que el código hace.
**Se recomienda no archivar hasta que el propietario decida sobre CRITICAL-1.**

---

## 1. Verificación ejecutada

Esta sesión no tiene Docker ni JDK 25, así que **no hubo ejecución local**. La evidencia es la
integración continua de `main` sobre el commit verificado, que parte de un checkout limpio con
JDK 25 y Docker.

### 1.1 `./mvnw --batch-mode verify -Dconfia.ci.mainBranch=true` (ejecución 200 de `ci`, `main` @ `842044d`)

| Medida | Valor observado |
|---|---|
| Resultado | `BUILD SUCCESS`, `02:50 min` |
| Pruebas unitarias de `kernel` | **186**, 0 fallos, 0 errores, 0 omitidas |
| Pruebas unitarias de `app` | **325**, 0 fallos, 0 errores, 0 omitidas |
| Pruebas de integración de `app` | **161**, 0 fallos, 0 errores, 0 omitidas |
| JaCoCo | `All coverage checks have been met.` en `kernel` y en `app`, que incluye la regla de 95 % de líneas y ramas sobre `com.confia.*.domain` |
| PIT sobre `kernel` | 194 mutantes, **192 eliminados (99 %)**; umbral 80, **bloqueante** en `main` |
| PIT sobre `com.confia.*.domain.*` de `app` | 201 mutantes, **188 eliminados (94 %)**; umbral 80, **bloqueante** en `main` |
| `security scanning` | En verde |

### 1.2 Comprobaciones directas del árbol

| Comprobación | Resultado |
|---|---|
| Ningún rastro de recuperación de contraseña (`password_recovery`, `PasswordReset`, `recovery_token`…) bajo `apps/api` | Cero coincidencias |
| `switch` de `AuthenticateWithPassword` sin `default` | La palabra solo aparece en Javadoc, nunca en código |
| Notas editoriales de este cambio en `docs/03-seguridad.md` | §4.3 (línea 398), adenda de §6.1 (línea 679) y §7.3 (línea 953), cada una fechada y sin reescribir el cuerpo de la sección |

---

## 2. Trazabilidad escenario por escenario

**PRUEBA**: un método de prueba afirma el «ENTONCES» del escenario. **PARCIAL**: se afirma solo una
parte del «ENTONCES».

### 2.1 `specs/identity/spec.md`: 22 escenarios

| # | Escenario | Método | Estado |
|---|---|---|---|
| 1 | Secreto TOTP almacenado con formato `v1:`, nunca en claro | `ColumnEncryptionIT.theStoredValueStartsWithV1AndNeverContainsThePlaintextSecret` | PRUEBA |
| 2 | AAD de otra fila falla al descifrar | `ColumnEncryptionIT.decryptingWithAnotherAccountsRowIdentifierFailsAuthentication` | PRUEBA |
| 3 | La inscripción devuelve el secreto en base32 una única vez | `EnrollTotpSecondFactorIT.theReturnedBase32SecretIsTheSameSecretThatWasStoredEncrypted` | PRUEBA |
| 4 | Código válido dentro de la ventana se acepta | `VerifyTotpCodeIT.aCodeWithinTheToleranceWindowIsAccepted` | PRUEBA |
| 5 | Código ya aceptado no se reutiliza | `VerifyTotpCodeIT.theSameCodeCannotBeAcceptedTwice` | PRUEBA |
| 6 | Sexto intento TOTP en 15 minutos activa el retroceso | `TotpVerificationBackoffIT.theSixthFailedAttemptInTheSameWindowAppliesBackoffBeforeItsOwnOutcome` | PRUEBA, **pero ver CRITICAL-1**: el escenario pasa; el requisito que lo contiene no se cumple literalmente |
| 7 | Diez códigos generados y mostrados una vez | `EnrollTotpSecondFactorIT.enrollingGeneratesTenDistinctRecoveryCodesShownOnce` y `noSubsequentQueryCanRecoverTheCodesInClearText` | PRUEBA |
| 8 | Usar un código lo invalida sin afectar a los otros nueve | `ConsumeRecoveryCodeIT.usingOneCodeInvalidatesItWithoutAffectingTheOtherNine` y `theSameCodeCannotBeAcceptedTwice` | PRUEBA |
| 9 | Consumir el octavo código deja el aviso activado, con conteo 2 | `ConsumeRecoveryCodeIT.consumingTheEighthCodeAuditsTheLowSignalWithoutSendingAnyEmail` (afirma `remainingUnusedCodes = 2`) | PRUEBA |
| 10 | Quedar con exactamente tres no activa el aviso | `ConsumeRecoveryCodeIT.consumingTheSeventhCodeLeavesExactlyThreeAndDoesNotAuditTheLowSignal` | **PARCIAL**: afirma la ausencia de la señal, pero no que «quedan tres códigos sin usar» (WARNING-2) |
| 11 | `mfa_required` se fija al crear la cuenta, sin rol | `MultiTenantSchemaIT.mfaRequiredIsFixedAtAccountCreationWithNoRoleDataBackingIt` | PRUEBA |
| 12 | Este cambio no deriva `mfa_required` de ningún permiso | `AuthenticateWithPasswordIT.mfaRequiredFalseStillProducesAuthenticatedWithNoDerivationFromAnyFuturePermission` | PRUEBA |
| 13 | Un desenlace no manejado rompe la compilación | `ExhaustiveAuthenticationResultSwitchCompilationTest.aSwitchMissingOneOutcomeFailsToCompile` y su gemelo positivo; control negativo registrado en C4 | PRUEBA |
| 14 | `SecondFactorRequired` no avanza el retroceso ni se audita como fallo | `AuthenticateWithPasswordIT.mfaRequiredAccountWithEnrolledTotpProducesSecondFactorRequiredWithoutAdvancingBackoffOrAuditingFailure`, que lee el contador persistido | PRUEBA |
| 15 | Ningún secreto nuevo es observable | `IdentitySecretRedactionIT.noExceptionNorToStringNorRowExposesTheTotpSecretARecoveryCodeTheKekOrADek` y el control negativo `theRedactionSweepDetectsARealLeak` | PRUEBA |
| 16 | Ninguna DEK retirada se recifra automáticamente | `IdentityScopeExclusionInventoryTest.noScheduledJobReencryptsARetiredDataEncryptionKeyYet` (ausencia) y `ColumnEncryptionIT.aValueEncryptedUnderARetiredKeyStillDecryptsAndNewValuesUseAFreshActiveKey` (sigue descifrando) | PRUEBA |
| 17 | El aviso queda auditado, sin ningún correo | `ConsumeRecoveryCodeIT.consumingTheEighthCodeAuditsTheLowSignalWithoutSendingAnyEmail` | PRUEBA |
| 18 | `mfa_required` con secreto inscrito → `SecondFactorRequired` | `AuthenticateWithPasswordIT.mfaRequiredAccountWithEnrolledTotpProduces…` | PRUEBA |
| 19 | `mfa_required` sin secreto inscrito → `SecondFactorEnrollmentRequired` | `AuthenticateWithPasswordIT.mfaRequiredAccountWithoutAnyEnrolledSecretProducesSecondFactorEnrollmentRequired` | PRUEBA |
| 20 | Contraseña correcta sin MFA → `Authenticated` (regresión) | `AuthenticateWithPasswordIT.mfaRequiredFalseStillProducesAuthenticated…` y las pruebas de la parte 1 | PRUEBA |
| 21 | Toda causa de rechazo → `Rejected` (regresión) | Pruebas de retroceso de `AuthenticateWithPasswordIT` y `AuthenticateWithPasswordTest` | PRUEBA |
| 22 | Los cuatro desenlaces son exactamente el `permits` | `AuthenticationResultTest.permitsExactlyTheFourOutcomesAndNoMore` | PRUEBA |

### 2.2 `specs/build-integrity/spec.md`: 6 escenarios

| # | Escenario | Método | Estado |
|---|---|---|---|
| 1 | Las puertas genéricas de esquema pasan sobre cada tabla nueva sin exclusión | Las cinco puertas de catálogo de `MultiTenantSchemaIT` | PRUEBA |
| 2 | Una institución no lee la DEK de otra | `DataEncryptionKeyRowSecurityIT.oneInstitutionCannotReadAnotherInstitutionsDataEncryptionKey` | PRUEBA |
| 3 | Sin contexto, cero filas | `DataEncryptionKeyRowSecurityIT.anAbsentInstitutionContextReturnsZeroRowsNotAPermissionError` | PRUEBA |
| 4 | La matriz cubre las cuatro tablas para los cinco roles | Las seis pruebas `…CryptoMfaTables…` de `RolePrivilegeMatrixIT` | PRUEBA |
| 5 | `confia_admin_app` lee, inserta y actualiza, pero no borra | `RolePrivilegeMatrixIT.confiaAdminAppCanSelectInsertAndUpdateButNeverDeleteOnAllFourCryptoMfaTables` | PRUEBA |
| 6 | `confia_portal_app` sin ningún privilegio | `RolePrivilegeMatrixIT.confiaPortalAppHasNoPrivilegeOnAnyCryptoMfaTable` | PRUEBA |

---

## 3. Criterios de éxito de `proposal.md`

| Criterio | Estado |
|---|---|
| `./mvnw verify` en verde, incluida la puerta de mutación de `main` | **Cumplido** (§1.1) |
| Cuatro desenlaces, `switch` exhaustivo sin `default`, rama olvidada rechazada por el compilador | **Cumplido** (escenarios 13 y 22) |
| `SecondFactorRequired` y `SecondFactorEnrollmentRequired`, ninguno avanza el retroceso | **Cumplido** (escenarios 14, 18 y 19) |
| Secreto con formato `v1:…`, columna sin el secreto en claro | **Cumplido** (escenario 1) |
| AAD de otra fila falla al descifrar | **Cumplido** (escenario 2) |
| Diez códigos generados, mostrados una vez, uso sin afectar a los otros nueve | **Cumplido** (escenarios 7 y 8) |
| Ningún secreto observable, verificado por inspección del texto | **Cumplido** (escenario 15, con control negativo) |
| Notas fechadas en `docs/09` y `docs/03` (§4.1, §4.3 y §7.3) | **Cumplido salvo §4.1**, ver WARNING-1 |
| 95 % de JaCoCo y 80 de PIT en los paquetes `domain` | **Cumplido** (§1.1) |
| Ningún rastro de recuperación de contraseña | **Cumplido** (§1.2) |

---

## 4. Hallazgos

### CRITICAL-1 — El límite de tasa TOTP no es el que el requisito publicado dice

El requisito «Límite de tasa sobre la verificación de código TOTP» dice: «DEBE limitar la
verificación de un código TOTP a **5 intentos por cada 15 minutos** por cuenta, con retroceso
exponencial posterior **una vez agotado ese límite**» (`docs/03-seguridad.md` §4.3 dice lo mismo).

La decisión 8 de `design.md` reutilizó `BackoffPolicy` afirmando que esos parámetros «son
literalmente los mismos umbrales que `BackoffPolicy.FIRST_DELAYED_ATTEMPT` y
`BackoffPolicy.COUNTER_WINDOW` ya codifican». **La premisa es falsa**:

| Parámetro | Requisito publicado | `BackoffPolicy` (lo que corre) |
|---|---|---|
| Intentos sin retraso | 5 | **2**: `FIRST_DELAYED_ATTEMPT = 3`, así que el retraso empieza en el tercer fallo |
| Ventana del contador | 15 minutos | **30 minutos** (`COUNTER_WINDOW`) |
| Tope por intento | no fijado | 900 s |

**Por qué el escenario pasa igual:** el sexto intento sí recibe retroceso (8 s), que es lo único que
afirma el escenario. Pero también lo reciben el tercero, el cuarto y el quinto; la propia prueba lo
confirma con `hasSize(4)` sobre las filas `backoff_applied`. El escenario no puede distinguir
«retroceso desde el sexto» de «retroceso desde el tercero», el mismo tipo de ceguera que el control
negativo de C4 encontró en el contador de contraseña.

**Riesgo real:** el comportamiento es **más estricto** que el publicado en los primeros intentos, y
el tope de 900 s limita el ritmo sostenido a unos 4 intentos por hora. No es una vulnerabilidad. El
problema es de contrato: si se archiva así, `openspec/specs/identity/spec.md` y `docs/03` quedarían
afirmando una regla que el código no aplica.

**Una segunda diferencia en el mismo escenario:** «aplica el retroceso **antes de evaluar** ese
código». En realidad `VerifyTotpCode` evalúa el código dentro de la transacción y devuelve
`requiredDelay`, que el llamador DEBE respetar antes de responder; es el mismo contrato que la parte
1 estableció para la contraseña. Ese llamador no existe todavía: llega con
`session-tokens-and-web-layer`, así que hoy nadie espera. Para un atacante el efecto es equivalente,
porque no ve el resultado hasta que pasa el retraso, pero el texto no lo describe.

**Decisión del propietario, antes de archivar:**
- **(a) Recomendada.** Aceptar el comportamiento real y corregir el requisito del delta y la nota de
  `docs/03` §4.3 para que digan lo que corre: la misma `BackoffPolicy` que el inicio de sesión,
  retraso desde el tercer fallo consecutivo, contador de 30 minutos y tope de 900 s, materializado
  por el llamador. Es solo documentación, más una prueba que afirme que el segundo intento no se
  retrasa y el tercero sí, para que el escenario deje de ser ciego.
- **(b)** Implementar una política propia de 5 intentos por 15 minutos para TOTP. Exige código de
  dominio nuevo, contradice la decisión D7 de reutilizar la misma regla, y deja dos políticas que
  mantener.

### WARNING-1 — El criterio de éxito nombra una nota en §4.1 que ninguna tarea pidió

`proposal.md` exige notas en «§4.1, §4.3 y §7.3». La decisión 10 de `design.md` y la tarea 5.2 solo
piden §4.3, §7.3 y la adenda de §6.1, y eso es lo que se entregó. El contenido que afectaría a §4.1
(los códigos de recuperación usan el mismo perfil Argon2id) ya está en la nota de §4.3.
**Recomendación:** tratarlo como una errata del criterio y dejarlo registrado aquí, sin añadir una
nota redundante.

### WARNING-2 — Escenario 10 cubierto solo en parte

`consumingTheSeventhCodeLeavesExactlyThreeAndDoesNotAuditTheLowSignal` afirma que no se audita la
señal, pero no el «ENTONCES» anterior: «el sistema calcula que quedan tres códigos sin usar».
`ConsumeRecoveryCodeDecision` no expone el conteo. **Recomendación:** contar en la prueba las filas
con `used_at IS NULL` y afirmar que son exactamente 3. Es una línea, sin cambios de producción.

### WARNING-3 — El retraso de la verificación TOTP todavía no lo materializa nadie

Mismo patrón que `AuthenticationDecision.requiredDelay()` en la parte 1, y con el mismo dueño,
`session-tokens-and-web-layer`, que `docs/09` ya nombra. Se registra para que el archivado lo lleve
como pendiente heredado explícito y no se pierda.

### SUGGESTION-1 — Conteo de escenarios corregido durante la aplicación

El plan contaba 27 escenarios; el delta tiene 28. El escenario del secreto en base32, añadido en C3c,
no estaba en la tabla. Ya se corrigió en `tasks.md` con una nota fechada; se registra aquí porque es
la segunda vez en este cambio que un escenario añadido a mitad de camino no llega a la tabla de
trazabilidad.

### SUGGESTION-2 — La tarea 5.2 pedía una prueba con base de datos en una clase sin contenedor

Se resolvió dividiendo el escenario 16 entre `IdentityScopeExclusionInventoryTest` y
`ColumnEncryptionIT`. Conviene que `tasks.md` de cambios futuros compruebe el sufijo `*Test` o `*IT`
de la clase que nombra antes de asignarle una aserción.

### SUGGESTION-3 — Mutantes supervivientes en `domain` de `app`

PIT sobre los paquetes `domain` de `app` da 94 %: de 201 mutantes, 13 no se eliminan y uno de ellos no tiene
cobertura.
Está por encima del umbral, pero conviene revisar el informe `backend-reports` de la ejecución 200
para decidir si alguno esconde una aserción ciega como la de CRITICAL-1.

---

## 5. Recomendación

No archivar todavía. Con la decisión del propietario sobre CRITICAL-1, la opción (a) cabe en un PR
pequeño: el requisito y la nota corregidos, la prueba del segundo y tercer intento, y la prueba de
WARNING-2. Después, `/sdd-archive`.
