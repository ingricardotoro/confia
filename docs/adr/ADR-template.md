# ADR-XXXX: <Título>

- **Estado:** Propuesto
- **Fecha:** AAAA-MM-DD
- **Decisores:** Propietario del producto y arquitecto
- **Contexto técnico:** <módulos o capas afectadas>

## Contexto y problema

<El problema real que hay que resolver. Qué fuerzas actúan. Qué pasa si no se decide.>

## Factores de decisión

<Lista de criterios con los que se evaluaron las opciones, ponderados por la restricción de un solo desarrollador.>

## Opciones consideradas

### Opción A: <nombre>
Descripción, ventajas, desventajas.

### Opción B: <nombre>
Descripción, ventajas, desventajas.

### Opción C: <nombre>
(si aplica)

## Decisión

<Qué se eligió y POR QUÉ, en términos de los factores de decisión.>

## Consecuencias

**Positivas:** ...
**Negativas y costos aceptados:** ...
**Riesgos y mitigaciones:** ...

## Cumplimiento y verificación

<Cómo se verifica en integración continua o en revisión que esta decisión se está respetando. Sé concreto: regla de lint, prueba automatizada, revisión de esquema, etc.>

## Referencias

---

## Cómo usar esta plantilla

1. Copia el archivo a `docs/adr/ADR-NNNN-titulo-en-kebab-case.md`, con `NNNN` como el siguiente
   número libre de la secuencia. Los números nunca se reutilizan, ni siquiera si el ADR se rechaza.
2. Escribe el ADR **antes** de implementar. Un ADR redactado después de escribir el código
   documenta una justificación, no una decisión.
3. La sección de opciones debe incluir al menos una alternativa que fuera realmente viable. Si no
   hubo alternativa viable, no había decisión que tomar y no hace falta un ADR.
4. La sección de consecuencias debe declarar costos reales. Un ADR sin costos es propaganda.
5. La sección de cumplimiento debe nombrar el mecanismo automatizado concreto. Si la única
   verificación posible es la revisión humana, dilo explícitamente y explica qué se revisa.
6. Registra el nuevo ADR en la tabla de `docs/adr/README.md`.
