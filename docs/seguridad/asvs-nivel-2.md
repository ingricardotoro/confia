# Comprobación ASVS nivel 2

> Registro vivo del cumplimiento del estándar de verificación de seguridad de aplicaciones, nivel 2.
> Se revisa **al cierre de cada fase** del roadmap.
>
> **Un requisito sin evidencia cuenta como no cumple.** No basta con afirmar que algo se hace: hay
> que poder señalar dónde se verifica.

- **Estado general:** sin iniciar. El proyecto está en planificación.
- **Última revisión:** pendiente
- **Próxima revisión:** al cierre de la fase F0

---

## Cómo se llena

| Columna | Qué va |
|---|---|
| Estado | `Cumple`, `No cumple`, `Parcial`, `No aplica` |
| Evidencia | Ruta de archivo, nombre de prueba, o regla de integración continua. **Nunca una afirmación** |
| Verificado | Fecha de la última comprobación real |

Ejemplos de evidencia válida: `apps/api/app/src/test/java/<paquete-base>/portal/GuardianRowLevelSecurityIT.java`, la regla
`no-floating-money` de ESLint, el paso `trivy-scan` del flujo de integración continua.

Ejemplos de evidencia inválida: "se implementó", "el framework lo hace", "revisado".

---

## V1. Arquitectura y modelado de amenazas

| Req | Descripción | Estado | Evidencia | Verificado |
|---|---|---|---|---|
| V1.1 | Ciclo de desarrollo seguro documentado | | `docs/13-metodologia-sdd.md` | |
| V1.2 | Modelo de amenazas por superficie | | `docs/03-seguridad.md` sección de amenazas | |
| V1.4 | Controles de acceso aplicados en el servidor | | | |
| V1.5 | Validación y codificación definidas por capa | | | |
| V1.8 | Clasificación de datos y protección de datos personales | | `docs/08-datos-privacidad-y-retencion.md` | |
| V1.11 | Lógica de negocio con controles de integridad | | `docs/adr/ADR-0007-libro-mayor-de-doble-partida.md` | |

## V2. Autenticación

| Req | Descripción | Estado | Evidencia | Verificado |
|---|---|---|---|---|
| V2.1 | Política de contraseñas alineada a NIST | | | |
| V2.2 | Verificadores resistentes: Argon2id | | | |
| V2.2 | MFA obligatoria para roles con escritura financiera | | `openspec/specs/identity/spec.md` | |
| V2.3 | Ciclo de vida de credenciales | | | |
| V2.5 | Recuperación con token de un solo uso y corta vida | | | |
| V2.7 | Segundo factor fuera de banda o TOTP | | | |
| V2.8 | Tiempo de validez del código de un solo uso | | | |

## V3. Gestión de sesiones

| Req | Descripción | Estado | Evidencia | Verificado |
|---|---|---|---|---|
| V3.2 | Tokens generados con aleatoriedad criptográfica | | | |
| V3.3 | Expiración y cierre de sesión efectivos | | | |
| V3.4 | Cookies con httpOnly, Secure y SameSite | | | |
| V3.5 | Rotación de token de refresco con detección de reutilización | | `docs/adr/ADR-0005-autenticacion-y-gestion-de-sesiones.md` | |
| V3.7 | Separación de dominios de identidad entre personal y encargados | | `docs/adr/ADR-0003-separacion-admin-portal.md` | |

## V4. Control de acceso

| Req | Descripción | Estado | Evidencia | Verificado |
|---|---|---|---|---|
| V4.1 | Decisión de autorización siempre en el servidor | | | |
| V4.2 | Verificación a nivel de objeto, sin referencias directas inseguras | | | |
| V4.3 | Segregación de funciones en operaciones sensibles | | `docs/03-seguridad.md` matriz de permisos | |
| V4.3 | Seguridad a nivel de fila activa y forzada | | `docs/adr/ADR-0009-multitenencia.md` | |

## V5. Validación, saneamiento y codificación

| Req | Descripción | Estado | Evidencia | Verificado |
|---|---|---|---|---|
| V5.1 | Validación de entrada en el borde con lista blanca | | | |
| V5.2 | Saneamiento de contenido no confiable | | | |
| V5.3 | Codificación de salida y política de seguridad de contenido | | | |
| V5.3 | Consultas parametrizadas, sin concatenación | | Regla de análisis estático | |
| V5.5 | Validación de tipo de archivo por contenido, no por extensión | | | |

## V7. Manejo de errores y registro

| Req | Descripción | Estado | Evidencia | Verificado |
|---|---|---|---|---|
| V7.1 | Sin datos sensibles en los registros | | Configuración de redacción de campos sensibles del registro estructurado del backend | |
| V7.2 | Registro de eventos de seguridad | | `.claude/skills/confia-audit-logging/SKILL.md` | |
| V7.3 | Protección de los registros contra manipulación | | Cadena de hash de la bitácora | |
| V7.4 | Errores sin filtrar detalles internos | | Formato Problem Details | |

## V8. Protección de datos

| Req | Descripción | Estado | Evidencia | Verificado |
|---|---|---|---|---|
| V8.1 | Protección de datos en cliente y en caché | | | |
| V8.2 | Sin datos sensibles en la dirección web ni en el almacenamiento del navegador | | | |
| V8.3 | Datos personales cifrados en reposo | | | |

## V9. Comunicaciones

| Req | Descripción | Estado | Evidencia | Verificado |
|---|---|---|---|---|
| V9.1 | TLS 1.3, sin protocolos obsoletos | | Configuración de nginx | |
| V9.2 | Verificación de certificado en conexiones salientes | | | |

## V10. Código malicioso y cadena de suministro

| Req | Descripción | Estado | Evidencia | Verificado |
|---|---|---|---|---|
| V10.2 | Sin funcionalidad no documentada | | Revisión de código | |
| V10.3 | Dependencias verificadas y fijadas | | Archivo de bloqueo y auditoría en integración continua | |
| V10.3 | Lista de materiales de software generada | | Paso de CycloneDX | |

## V11. Lógica de negocio

| Req | Descripción | Estado | Evidencia | Verificado |
|---|---|---|---|---|
| V11.1 | Flujos en orden y en tiempo razonable | | | |
| V11.1 | Protección contra procesamiento automatizado abusivo | | Limitación de tasa | |
| V11.1 | Idempotencia en escrituras financieras | | `docs/adr/ADR-0010-idempotencia-y-concurrencia-financiera.md` | |

## V12. Archivos y recursos

| Req | Descripción | Estado | Evidencia | Verificado |
|---|---|---|---|---|
| V12.1 | Límites de tamaño de carga | | | |
| V12.3 | Sin recorrido de rutas en nombres de archivo | | | |
| V12.4 | Almacenamiento fuera de la raíz web | | Almacenamiento compatible con S3 | |
| V12.5 | Tipos de archivo restringidos | | | |

## V13. API y servicios web

| Req | Descripción | Estado | Evidencia | Verificado |
|---|---|---|---|---|
| V13.1 | Autenticación y autorización en todos los endpoints | | Prueba de matriz de autorización | |
| V13.2 | Métodos HTTP y códigos correctos | | `.claude/skills/confia-api-conventions/SKILL.md` | |
| V13.2 | Protección contra falsificación de solicitud entre sitios | | Doble envío | |
| V13.4 | Sin exposición excesiva de datos en las respuestas | | DTO de salida explícitos | |

## V14. Configuración

| Req | Descripción | Estado | Evidencia | Verificado |
|---|---|---|---|---|
| V14.1 | Construcción y despliegue reproducibles | | `docs/adr/ADR-0012-contenerizacion.md` | |
| V14.2 | Dependencias sin vulnerabilidades conocidas altas o críticas | | Escaneo con Trivy | |
| V14.3 | Sin información sensible en respuestas ni cabeceras | | | |
| V14.4 | Cabeceras de seguridad completas | | Configuración de nginx | |
| V14.5 | Métodos HTTP restringidos | | | |

---

## Requisitos no aplicables

Se documenta aquí cada requisito marcado como no aplicable, con su justificación. Marcar algo como
no aplicable sin justificar es la forma más silenciosa de bajar el estándar.

| Req | Justificación |
|---|---|
| | |

---

## Historial de revisiones

| Fecha | Fase | Requisitos revisados | Hallazgos | Responsable |
|---|---|---|---|---|
| | | | | |
