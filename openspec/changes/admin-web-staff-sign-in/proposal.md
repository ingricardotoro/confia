# Propuesta: pantallas de acceso del personal (cambio 13b-1 de F0, parte 2 de 2)

- **Cambio:** `admin-web-staff-sign-in`
- **Rama:** `change/admin-web-staff-sign-in`
- **Fase del roadmap:** F0, cambio 13b-1, parte 2 de 2. **Cierra el criterio de salida 8 de la fase 0** (`docs/09-roadmap-y-fases.md`).
- **Origen:** el propietario resolvió Q1 el 2026-11-05 cortando la cadena del antiguo `admin-web-scaffold-and-staff-login` después del PR 11b.
- **Depende de:** `admin-web-foundations-and-session` (parte 1). **Empieza cuando la parte 1 esté fusionada en `main`.**
- **Sucesor:** 13b-2, con el shell, la vida completa de la sesión y el aviso de inactividad.
- **Estado:** alcance fijado por la decisión del propietario del 2026-11-05. Las decisiones P1 a P3 y P6 a P10 y la resolución de Q2 siguen vinculantes.

## Alcance

Los PR 12a a 15 de `admin-web-foundations-and-session/design.md`, sección 12:

| PR | Rebanada | Contenido |
|---|---|---|
| 12a | `sign-in-form` | Formulario, `use-access-form`, contraseña, contacto |
| 12b | `sign-in-states` | `429`, `503`, espera, avisos, filas de la puerta, recorrido |
| 13 | `second-factor-screen` | Verificación, código de recuperación, filas de la puerta, recorrido |
| 14 | `enrollment-secret-and-qr` | Almacén, URI, QR, clave, decodificación |
| 15 | `enrollment-activation-and-criterion-8` | Códigos, primer código, centinelas, `docs/09`, notas de `docs/ui-ux/04` y `06` |

Son de 7 a 9 PR: 5 si cada rebanada cabe, y de 7 a 9 con las pruebas al sobrecosto de cuatro veces del cambio 8. Reemplaza la pantalla provisional de inicio
de sesión y los componentes provisionales de verificación e inscripción que deja la parte 1.

> **Nota fechada 2026-11-07 (decisión del propietario, fase de diseño).** La tabla y la cifra de arriba no se reescriben y quedan superadas. La
> entrega es una cadena de **9 PR** encadenados (`design.md`, sección 12), con cortes predefinidos que pueden llevarla hasta **15**. Cuando un PR
> supera las 800 líneas efectivas solo por poco, se pide al propietario una excepción en lugar de cortar; cuando las supera claramente, se aplica su
> corte predefinido. La previsión de «7 a 9 PR» se reemplaza porque el diseño dimensionó cada rebanada con las medidas reales de la parte 1.

**Cierre del criterio de salida 8.** Este cambio lleva la matriz de la puerta de 6 a 22 estados, por los dos temas. El criterio se marca en `docs/09` solo cuando
la puerta pase sobre inicio de sesión, verificación, inscripción, arranque y página autenticada (BX102, BX103, BX109). Si la puerta o una pantalla evaluada se
revierte, se desmarca con nota fechada (BX108).

## Especificación

`specs/` de esta carpeta. Cada escenario vive en un solo cambio. Se separaron de la parte 1 los escenarios cuyo trabajo llega en los PR 12a a 15: 81 de `staff-sign-in`,
5 de `admin-web-foundation`, 6 de `build-integrity` y 6 de `identity`, 98 en total. `design-system` no tiene delta aquí. Se archiva después de la parte 1, porque sus requisitos
`MODIFIED` suman escenarios a lo que aquella publica.

## Diseño y tareas

- **Diseño:** no tiene uno propio. Reutiliza `openspec/changes/admin-web-foundations-and-session/design.md`, aprobado el 2026-11-05 (D8, D10 a D12, D15, secciones 6 a 8, 10 y 11).
  > **Nota fechada 2026-11-07 (fase de diseño).** La frase anterior queda superada: el cambio tiene su propio `design.md`, pedido en la fase de
  > diseño. Conserva D8, D10 a D12 y D15 de la parte 1 y solo las concreta o las corrige donde el código real lo exige (E1, E2, E6 y E9 de ese
  > documento).
- **Tareas:** tendrá su propio `tasks.md` cuando empiece, con las mismas reglas de la parte 1 y las mediciones reales de esa cadena.

## Fuera de alcance

Lo que la propuesta de la parte 1 asigna a 13b-2, 8b y al cambio 11, con los mismos dueños. Ningún archivo de `apps/api` cambia. Los recorridos corren contra la API simulada y no se
presentan como prueba de la pila real.

## Reversión

Cada PR se revierte con su commit de fusión, en orden inverso. Las pantallas son aditivas sobre la parte 1. Revertir una pantalla evaluada desmarca el criterio 8 en `docs/09` con nota
fechada.
