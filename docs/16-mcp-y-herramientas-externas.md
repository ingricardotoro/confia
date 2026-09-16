# Servidores MCP y herramientas externas

> Los servidores del Protocolo de Contexto de Modelo (MCP) amplían lo que un asistente puede hacer
> en este proyecto. Cada uno abre una puerta, y en un sistema con datos de menores de edad y dinero
> real, cada puerta se justifica antes de abrirse.

---

## Advertencias que gobiernan todo este documento

> **1. Nunca conectes un servidor MCP con permisos de escritura a la base de datos de producción.**
>
> Ni siquiera "solo para revisar algo". Una consulta mal formada ejecutada por un asistente contra
> producción puede modificar registros financieros sin que quede claro qué pasó, y sin la bitácora
> de auditoría de la aplicación, porque no pasó por ella.

> **2. Nunca expongas datos personales reales de estudiantes o encargados a un servicio externo.**
>
> Un servidor MCP que consulta la base de datos y devuelve filas está enviando esos datos al
> proveedor del modelo. Los nombres, documentos de identidad, teléfonos y saldos de menores de edad
> no salen del sistema. La base de datos de desarrollo se siembra con **datos generados**, nunca con
> una copia de producción.

> **3. Verifica el nombre exacto del paquete antes de activar cualquier servidor.**
>
> El ecosistema MCP cambia rápido: los paquetes se renombran, se deprecan y cambian de mantenedor.
> Los nombres de este documento son un punto de partida, no una garantía. Instalar un paquete con un
> nombre parecido al correcto es un vector clásico de ataque a la cadena de suministro.

---

## Servidores configurados

La configuración vive en `.mcp.json` en la raíz del proyecto.

### `filesystem`

| | |
|---|---|
| **Qué aporta** | Lectura y escritura de archivos acotada a la carpeta del proyecto |
| **Cuándo usarlo** | Trabajo normal de desarrollo |
| **Permisos que concede** | Lectura y escritura dentro de la ruta configurada |
| **Riesgo** | Bajo, si la ruta está bien acotada. Alto si se configura con la raíz del disco |
| **Cómo limitarlo** | El argumento de ruta es obligatorio y apunta solo a la carpeta del proyecto. Nunca uses el directorio del usuario |

### `postgres-dev`

| | |
|---|---|
| **Qué aporta** | Exploración del esquema, verificación de índices y revisión de planes de ejecución |
| **Cuándo usarlo** | Diseño de migraciones, depuración de consultas lentas, verificación de restricciones |
| **Permisos que concede** | Los del rol de la cadena de conexión |
| **Riesgo** | **Alto si se apunta mal.** Es el servidor más peligroso de la lista |
| **Cómo limitarlo** | Ver abajo. Tres condiciones obligatorias |

Tres condiciones, todas obligatorias:

1. **Rol de solo lectura.** Se crea un rol dedicado sin permisos de escritura:

```sql
CREATE ROLE confia_mcp_readonly LOGIN PASSWORD '<generada>';
GRANT CONNECT ON DATABASE confia_dev TO confia_mcp_readonly;
GRANT USAGE ON SCHEMA public TO confia_mcp_readonly;
GRANT SELECT ON ALL TABLES IN SCHEMA public TO confia_mcp_readonly;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
  GRANT SELECT ON TABLES TO confia_mcp_readonly;
REVOKE INSERT, UPDATE, DELETE, TRUNCATE ON ALL TABLES IN SCHEMA public
  FROM confia_mcp_readonly;
```

2. **Base de datos de desarrollo únicamente.** La variable de entorno apunta a `confia_dev`. Producción
   no es alcanzable desde la máquina de desarrollo por diseño de red, y esa es la defensa real.

3. **Datos generados, nunca copiados de producción.** La semilla de desarrollo produce estudiantes y
   encargados ficticios. Copiar producción a desarrollo para "probar con datos reales" es una
   violación de protección de datos, no un atajo.

### `playwright`

| | |
|---|---|
| **Qué aporta** | Control de navegador para verificar pantallas, capturar estados y depurar pruebas de extremo a extremo |
| **Cuándo usarlo** | Depuración de pruebas de interfaz, verificación visual, revisión de accesibilidad |
| **Permisos que concede** | Control del navegador y navegación a cualquier sitio |
| **Riesgo** | Medio. Puede navegar fuera del entorno local |
| **Cómo limitarlo** | Úsalo solo contra el entorno local. **Nunca contra producción con una sesión de usuario real**: una acción accidental registraría un pago o anularía una factura de verdad |

### `context7`

| | |
|---|---|
| **Qué aporta** | Documentación actualizada de Spring Boot, Spring Modulith, Flyway, TanStack, Tailwind y las demás librerías del stack |
| **Cuándo usarlo** | Siempre que haya duda sobre la API de una librería. Es preferible a responder de memoria |
| **Permisos que concede** | Ninguno sobre el proyecto. Solo consulta de documentación pública |
| **Riesgo** | Bajo |
| **Cómo limitarlo** | No requiere limitación |

Este es el servidor de mejor relación entre valor y riesgo del proyecto. El stack elegido evoluciona
rápido, y TanStack Router en particular ha tenido cambios de API significativos.

---

## Servidores candidatos, no configurados todavía

No están en `.mcp.json`. Se evalúan cuando el proyecto llegue a la fase correspondiente.

| Servidor | Qué aportaría | Fase | Qué verificar antes |
|---|---|---|---|
| **GitHub** | Gestión de incidencias y pull requests desde la sesión | F0 | El nombre exacto del paquete oficial. El token debe tener el alcance mínimo: sin permisos de administración de la organización ni de borrado de repositorio |
| **Sentry** | Triaje de errores de producción | F8 | Que las trazas no incluyan datos personales. Requiere que la configuración de eliminación de datos previa al envío esté verificada primero |
| **shadcn** | Búsqueda de componentes y ejemplos del sistema de diseño | F1 | Nombre exacto del paquete |
| **Grafana o Prometheus** | Consulta de métricas de producción | F10 | Que solo exponga métricas agregadas, nunca datos por estudiante |

Sobre Sentry en particular: conectarlo antes de verificar la eliminación de datos personales
significaría enviar información de menores a un servicio externo cada vez que ocurra un error. El
orden importa.

---

## Variables de entorno

Se definen en el entorno local del desarrollador. **Nunca en el repositorio**, ni siquiera con
valores de ejemplo que parezcan inofensivos.

| Variable | Propósito | Ejemplo seguro |
|---|---|---|
| `CONFIA_PROJECT_ROOT` | Raíz del proyecto para el servidor de archivos | `C:\Users\<usuario>\proyectos\Confia` |
| `CONFIA_DEV_DATABASE_URL_READONLY` | Conexión de solo lectura a desarrollo | `postgresql://confia_mcp_readonly:<clave>@localhost:5432/confia_dev` |
| `GITHUB_TOKEN` | Token de acceso, cuando se active ese servidor | Alcance mínimo, sin permisos de administración |

Verifica que ninguna quede en el historial del intérprete de comandos. En un sistema financiero, una
credencial en el historial es una credencial comprometida.

---

## Lista de verificación antes de activar un servidor MCP

- [ ] El nombre exacto del paquete está verificado en su repositorio oficial
- [ ] Se entiende qué permisos concede y a qué puede acceder
- [ ] Está acotado al alcance mínimo necesario
- [ ] No puede alcanzar producción
- [ ] No puede acceder a datos personales reales
- [ ] Las credenciales están en variables de entorno, nunca en el archivo de configuración
- [ ] Si es de escritura, se justificó por qué no basta uno de lectura
- [ ] Se documentó en este archivo, con su riesgo y su limitación

Un servidor que no pasa la lista no se activa. La comodidad de tener una herramienta más no compensa
la exposición de datos de menores de edad.

---

## Relación con las skills y los agentes

Los servidores MCP dan **capacidades**. Las skills dan **procedimientos**. Los agentes dan **roles**
con sus límites. Son tres cosas distintas y conviene no confundirlas:

- Una skill como `confia-money-rules` no necesita ningún servidor MCP: es conocimiento.
- Un agente como `confia-database` aprovecha `postgres-dev` para verificar un índice, pero su
  criterio de rechazo de migraciones destructivas es propio del agente, no del servidor.
- `context7` mejora la calidad de cualquier agente que trabaje con las librerías del stack.

Ver `docs/12-agentes-y-herramientas.md` y `.claude/skills/README.md`.
