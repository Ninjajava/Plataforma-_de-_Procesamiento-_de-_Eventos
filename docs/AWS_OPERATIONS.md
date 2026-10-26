# AWS: operación, costos, gobernanza y límites

## Alcance demostrable

| Nivel | Entregado | Pendiente |
|---|---|---|
| Backend | Inventario transaccional, WebFlux, idempotencia, outbox, auditoría y tests | Identidad de usuarios, pagos y butacas |
| Integración AWS | SDK asíncrono, región configurable, perfil AWS y task role definido | Verificación de permisos y red sobre cuenta real |
| Infraestructura | Terraform, guardrails, tests con mocks y bootstrap | Plan y apply con credenciales autorizadas |
| Operación | Logs, Flow Logs, alarmas, dashboard y presupuesto definidos | Prueba de alertas, guardias y ejercicios operativos |
| Seguridad | Red privada, IAM, cifrado, imagen por digest, root FS read-only definidos | JWT/ownership, pipeline de firma y gate de vulnerabilidades |
| Rendimiento | Pruebas locales de concurrencia y script k6 | Prueba sostenida en AWS con p95/p99 end-to-end |

Una definición Terraform validada no demuestra por sí sola experiencia de operación de una plataforma real. En la sustentación deben mostrarse evidencias y límites, sin presentar un despliegue no realizado como exitoso.

## Señales de operación

| Señal | Implementación | Acción inicial |
|---|---|---|
| Edad SQS > 300 s durante 2 minutos | CloudWatch alarm | Revisar errores, capacidad y conflictos antes de aumentar tareas |
| DLQ visible > 0 durante 2 minutos | CloudWatch alarm | Identificar causa; corregir y redrive controlado |
| Throttle de escritura tabla/GSI | CloudWatch alarm | Investigar clave caliente, límites y capacidad del índice |
| Target 5xx > 5 por minuto, 2 periodos | CloudWatch alarm | Correlacionar release, SDK, permisos y estado de servicios |
| Latencia ALB p95 > 1 s, 3 periodos | CloudWatch alarm | Separar tiempo de aceptación y tiempo de procesamiento |
| Errores de aplicación | Logs stdout → CloudWatch | Buscar orderId y operación; no registrar claves ni pagos |
| Rechazos de red | VPC Flow Logs | Revisar SG, endpoints, rutas y DNS |
| Solicitudes al ALB | Access logs S3 | Investigar patrones de abuso y errores de clientes |
| Backlog de outbox y reservas vencidas | **No hay métrica propia exportada todavía** | Añadir instrumentación y reconciliación; SQS vacío no significa outbox vacío |
| Trazas distribuidas / latencia hasta SOLD | **No implementadas** | Añadir OTel/ADOT y propagación de contexto por SQS |

Actuator expone únicamente health. Readiness indica disponibilidad del proceso, no la salud completa del flujo SQS/outbox. No se han publicado Prometheus ni métricas de negocio al endpoint HTTP. Container Insights observa contenedores, no sustituye esas señales.

Las alarmas apuntan a SNS. Un correo opcional necesita confirmación de suscripción; sin receptor confirmado, no hay garantía de aviso a una persona. El presupuesto también es opcional y no detiene gasto.

## Runbooks mínimos

### Crece la cola o el outbox

1. Verificar el estado del despliegue y si hay tareas sanas.
2. Comparar edad SQS con errores de consumidor. Si SQS está vacío pero hay órdenes QUEUED antiguas, investigar el publicador/outbox.
3. Revisar throttling y conflictos. Si la base es el cuello, añadir tareas puede empeorar la presión.
4. Mantener límites de concurrencia. Si se aumenta capacidad, hacerlo de manera acotada y observar la tasa de finalización.
5. Definir comunicación de disponibilidad al cliente; un 202 no equivale a cupo reservado.

### Mensajes en DLQ

1. Clasificar fallo transitorio, permisos, esquema o defecto de negocio.
2. Consultar la orden por lectura fuerte y su auditoría antes de reenviar.
3. Corregir causa y probar un lote pequeño de redrive con rol operativo separado.
4. No borrar mensajes como solución ni editar contadores a mano.
5. La idempotencia tolera reentregas; no elimina la necesidad de revisar órdenes terminales y vencimientos.

### Reservas vencidas no se liberan

Comprobar candidatos GSI ACTIVE, expirador, relojes y errores transaccionales. El atraso reduce disponibilidad, pero no autoriza vender antes de liberar. Diseñar una reconciliación que compare contadores con asignaciones bajo una estrategia consistente; no sumar páginas GSI eventualmente consistentes y sobrescribir el inventario a partir de ese resultado.

### Release defectuoso

ECS circuit breaker puede revertir un despliegue que no estabiliza. Confirmar que existe revisión anterior saludable; el primer despliegue no tiene una versión buena que recuperar. Mantener compatibilidad de esquemas y probar rollback después de cambios de datos. Los tests de readiness no verifican por sí solos el procesamiento de compras.

### Recuperación de datos

PITR permite restaurar una tabla a una **tabla nueva**; no restaura SQS ni el servicio completo. Un ejercicio debe definir corte, cambio de tabla, reconciliation de outbox, mensajes en vuelo, auditoría y reanudación. No se ha medido RPO/RTO. Objetivos iniciales deben acordarse con negocio y verificarse mediante un simulacro.

## Costos y decisiones

No hay una estimación monetaria ni benchmark de costo en esta entrega. El umbral opcional de USD 150 es un valor de alerta de ejemplo, no una promesa de costo ni un límite de facturación.

| Componente | Costo / riesgo | Decisión o alternativa |
|---|---|---|
| Fargate | Dos tareas mínimas permanentes y hasta doble capacidad durante rolling deploy | Limitar máximo; usar on-demand para el flujo crítico; evaluar Spot solo para trabajo tolerante |
| ALB | Horas y LCUs aunque no haya compras | Mantener solo si se necesita esa entrada; no asumir costo cero en dev |
| Interface endpoints | 5 servicios × 2 AZ = 10 presencias AZ facturables, más procesamiento | Comparar con NAT y necesidades de salida; privado no significa más barato |
| Gateway DynamoDB/S3 | Sin cargo adicional del endpoint | Reducen necesidad de NAT; los servicios siguen facturando uso |
| DynamoDB | Transacciones, GSI ALL, almacenamiento, PITR y clave caliente | On-demand simplifica arranque; medir patrón real antes de provisioned/cupos particionados |
| SQS | Send/receive/delete, polling y duplicados | Long polling; evaluar lotes sin perder semántica de acuse |
| KMS | Claves y solicitudes | Reuso de data key SQS 300 s; revisar requisitos antes de consolidar más claves |
| Logs / Flow Logs / Insights | Ingesta, retención y consultas | Retención de 30 días; evitar PII y logs repetitivos sin control |
| S3 | Access logs, versiones y almacenamiento | 90 días logs actuales y 30 versiones anteriores; validar política legal |
| Red entre AZ | Puede agregar transferencias según servicio/ruta | Medir tráfico y distribución; no sacrificar disponibilidad por intuición |

El presupuesto definido es **de la cuenta completa**. Es útil si cada entorno usa una cuenta aislada; en una cuenta compartida incluye otros proyectos. Tags no filtran automáticamente ese presupuesto. Para showback, activar tags de asignación de costos y Cost and Usage Report/Data Exports en la plataforma de gobierno.

Consultar [AWS Pricing Calculator](https://calculator.aws/) con región, duración de campañas, volumen de solicitudes, tamaño de ítems, GSI y retención. Los precios dependen de esos parámetros. Los [gateway endpoints no tienen cargo adicional](https://docs.aws.amazon.com/vpc/latest/privatelink/gateway-endpoints.html).

## Gobernanza de cuentas y cambios

- Cuentas por entorno bajo Organizations; SCP para restringir regiones y operaciones peligrosas. **Organizations/SCP no se crean aquí**.
- IAM Identity Center para personas y OIDC para CI. No access keys en repositorio, estado o task definition.
- Roles separados para ejecución de Terraform, aplicación y operación/redrive. El rol de task no administra infraestructura.
- Revisar plan en PR, comprobar cuenta y estado antes de aplicar, separar aprobación de despliegue y conservar evidencias.
- Backend S3 cifrado/versionado con lockfile y permisos por prefijo. Un workspace no es un aislamiento de IAM.
- Tags Project, Environment, Owner, CostCenter y ManagedBy en recursos que admiten default tags; revisar excepciones.
- CloudTrail organizacional, Config, GuardDuty y Security Hub deben formar parte de la landing zone. **No se implementan ni se duplican en este módulo**.
- Secretos existentes se referencian por ARN; Terraform no crea secret versions ni recibe valores. El ARN y el plan siguen siendo metadatos que deben protegerse.
- Escaneo ECR está habilitado, pero el gate de vulnerabilidades y la firma/verificación de imágenes quedan para el pipeline.
- Retiro y destrucción son operaciones revisadas. No desactivar prevent_destroy o deletion protection automáticamente para evitar costos.

## Qué cambiaría en producción

| Prioridad | Cambio | Motivo |
|---|---|---|
| Antes de entrada pública | OIDC/JWT, ownership de orden y roles de administración | El cliente actual puede declarar customerId y consultar/confirmar órdenes sin autenticación |
| Antes de cobrar | Integración real de autorización/captura, callbacks autenticados y compensación | El endpoint confirm actual es una demostración, no una prueba de pago |
| Antes de campaña masiva | Sala de espera/rate limits y carga sostenida con fallos | Proteger capacidad real y medir latencia de reserva/finalización |
| Según carga | Separar API, publicador, consumidores y expirador en roles/procesos | Escalar por carga propia, no solo por CPU de un proceso combinado |
| Según contención | Cupos por partición/sección y GSI por shard/ventana temporal | Distribuir claves calientes conservando el total de capacidad |
| Operación | Métricas outbox, expiración, retries y latencia de negocio; trazas OTel | Detectar fallos que métricas de SQS/ALB no ven |
| Recuperación | Ensayos PITR, redrive y reconciliación; definir RPO/RTO | Los backups no equivalen a una recuperación probada |
| Auditoría y privacidad | Retención legal, acceso restringido y eventos de actor/causa | La auditoría actual registra transición e instante, sin identidad autenticada |
| Selección de butacas | Estado por asiento y transacciones de asignación | Un contador por cantidad solo modela admisión general |

## Matices que deben exponerse

- La condición de DynamoDB protege estado y contador, pero la comprobación del vencimiento se hace con el reloj del proceso antes del commit. Definir semántica de corte y comportamiento de pagos cercanos al límite.
- SOLD y COMPLIMENTARY son finales en el backend. Un reembolso debe ser un movimiento contable independiente, no una reversión silenciosa a AVAILABLE.
- El GSI de outbox es eventualmente consistente. El diseño favorece recuperación eventual; no promete un tiempo máximo de publicación durante una caída.
- En una caída prolongada hay que dimensionar almacenamiento outbox y retención de órdenes. No existe retención/archivado automático del historial implementado.
- La alta cobertura valida rutas de código; no sustituye pentest, prueba de caos, capacidad ni aceptación de AWS real.
