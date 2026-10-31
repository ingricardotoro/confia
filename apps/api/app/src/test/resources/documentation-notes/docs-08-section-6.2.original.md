### 6.2 Ejecución de la retención

La tarea semanal de limpieza por retención (`docs/07-observabilidad-y-operaciones.md` sección 11)
funciona así:

1. Identifica registros vencidos por categoría, con la consulta declarada en el módulo
   correspondiente.
2. **Verifica que ninguna obligación de conservación siga vigente** sobre cada registro. Un
   registro con documento fiscal dentro del plazo nunca se procesa, aunque otra regla lo señale.
3. Aplica la acción declarada: eliminación o anonimización.
4. Escribe en la bitácora de auditoría un registro por lote, con la categoría, el criterio y el
   conteo de registros afectados. **Nunca los datos eliminados.**
5. Publica `confia_data_retention_pending_records`. Un valor mayor a cero durante más de siete días
   genera alerta S3.
6. **Nada se elimina en la misma corrida en que se detecta.** Existe un período de gracia de siete
   días entre la marca y la ejecución, para que un error de criterio se detecte antes de ser
   irreversible.

---

