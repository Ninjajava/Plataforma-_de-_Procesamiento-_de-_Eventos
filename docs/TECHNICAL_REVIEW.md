# Guía de sustentación técnica

## Recorrido sugerido: 15 a 20 minutos

Material adicional: [diagramas e interacciones](ARCHITECTURE.md), [infraestructura Terraform](../infra/terraform/README.md) y [operación, costos y gobernanza AWS](AWS_OPERATIONS.md). La topología AWS es una propuesta validada con mocks, todavía no desplegada.

1. **Problema e invariantes:** mostrar cómo una lectura de disponibilidad seguida de una escritura sin condición permite sobreventa. Definir conservación del inventario, un estado por asignación y estados finales.
2. **Arquitectura:** recorrer dominio → casos de uso → puertos → adaptadores. Mostrar que `TicketService` no importa Spring ni AWS y que `DynamoStore` implementa el puerto.
3. **Flujo principal:** ejecutar la colección Postman. Explicar que 202 confirma persistencia de la solicitud, que RESERVED asegura cupo y que SOLD finaliza la venta de la demostración.
4. **Consistencia:** abrir `DynamoStore.transition`. Explicar las dos condiciones: estado anterior de la orden e inventario suficiente. Mostrar que auditoría y outbox comparten la transacción.
5. **Fallos:** recorrer caída antes del commit, después del commit, después del envío SQS y después del procesamiento pero antes del delete. En cada caso identificar qué dato queda y quién reintenta.
6. **Evidencia:** ejecutar `mvn clean verify`, abrir JaCoCo y revisar la prueba de 100 órdenes contra capacidad 20. Distinguir el doble del SDK de las pruebas DynamoDB Local reales.
7. **Trade-offs y seguridad:** explicar límites de clave caliente, consistencia eventual de índices, pagos fuera de alcance y controles necesarios antes de producción.

## Decisiones que conviene defender con código

### WebFlux y SDK asíncrono

El objetivo es no retener un hilo por solicitud mientras espera I/O. Reactor permite componer llamadas al SDK asíncrono y limitar concurrencia en los consumidores. Esto no hace más rápida una operación de base de datos ni evita una clave caliente. No se envuelve un cliente bloqueante en `Mono.just`; las llamadas se crean mediante suppliers de futuros y se suscriben al ejecutar el flujo.

### DynamoDB con transacciones

Una actualización atómica aislada del contador evita valores negativos, pero no basta si la orden o auditoría se guardan por separado. `TransactWriteItems` permite actualizar esas entidades como una unidad. Se paga más capacidad y se introducen conflictos; para esta prueba se privilegia una invariante simple y verificable.

La condición de estado de la orden es el control optimista. No se requiere una versión adicional en este modelo porque las transiciones son monotónicas: una orden no vuelve a QUEUED o RESERVED después de salir de ellos. Si se agregaran cancelaciones reutilizables o reintentos de reserva en una misma orden, habría que revisar ese supuesto y añadir versión.

### Estado de la entrada frente al de la orden

AVAILABLE significa inventario sin propietario. Una solicitud encolada todavía no posee entradas y una solicitud rechazada no es una entrada disponible. Mantener QUEUED, REJECTED y EXPIRED en el estado de orden evita falsear disponibilidad o contabilizar reservas como ventas. `ticketState` solo describe las entradas actualmente asignadas.

### Outbox frente a escribir en DynamoDB y SQS directamente

No hay una transacción distribuida compartida entre ambos servicios. Persistir una orden y después enviarla puede dejar órdenes sin mensaje; enviar antes puede producir mensajes para órdenes inexistentes. El outbox convierte el envío en trabajo recuperable. Acepta duplicados y exige idempotencia en el consumidor.

La implementación local usa polling por GSI. Una evolución puede usar DynamoDB Streams para bajar latencia y costo de polling, conservando deduplicación y recuperación. Streams no elimina por sí mismo el problema de entregas repetidas.

### SQS Standard frente a FIFO o Kafka

No se exige orden global ni reproducción histórica de un log. SQS Standard es suficiente porque la base protege cada transición. FIFO puede reducir algunas entregas duplicadas, pero no reemplaza idempotencia durable ni el control de inventario. Kafka sería razonable si aparecieran necesidades de replay, múltiples consumidores independientes o analítica de eventos; no es necesario para esta prueba.

### Vencimiento y TTL

DynamoDB TTL no ejecuta una liberación puntual ni una transacción que devuelva cupo. Borrar una reserva por TTL destruiría información necesaria para reconciliar el inventario. Aquí una tarea de aplicación realiza la transición transaccional a EXPIRED. El plazo lógico es de 600 segundos; la devolución física puede retrasarse si hay backlog o una falla del servicio. La confirmación no extiende el plazo y también comprueba el vencimiento.

### Consistencia de lecturas

Los GET por identificador leen la tabla con `consistentRead=true`. Las búsquedas por tipo, auditoría y expiración usan un índice global eventualmente consistente. Una auditoría recién confirmada puede tardar en aparecer; una reserva vencida puede seguir descontada hasta liberarse. Esto es conservador respecto de sobreventa. No se debe presentar el listado GSI como una lectura fuerte.

## Carreras y recuperación

| Carrera o fallo | Mecanismo | Resultado |
|---|---|---|
| Dos órdenes buscan el último cupo | Condición del contador dentro de transacción | Solo una asignación puede consumirlo |
| Dos consumidores procesan la misma orden | Condición de estado anterior | Una transición y una auditoría efectiva |
| Confirmación compite con expiración | Ambas condicionan el estado esperado | La transacción que pierde relee la orden |
| Consumidor cae después del commit | SQS reentrega; estado ya procesado | No se repite el movimiento |
| Publicador cae después de send | Outbox permanece | Se puede reenviar, sin duplicar negocio |
| DynamoDB devuelve error transitorio | Backoff y jitter acotados | Se vuelve a leer; no se reusa un snapshot viejo |
| Mensaje falla reiteradamente | Redrive policy a DLQ | Investigación y reenvío operativo controlado |

`clientRequestToken` protege retries de una solicitud transaccional concreta. La clave de idempotencia de la orden protege el negocio durante toda su existencia; son mecanismos distintos.

## Qué demuestran las pruebas

- Los casos de uso prueban las reglas con mocks de `TicketStore`, `Clock` controlado y `StepVerifier`.
- El tiempo virtual verifica backoff y agotamiento sin dormir el hilo de test.
- `WebTestClient` verifica respuestas reactivas, validación y traducción de errores.
- `MemoryDynamo` es un doble del SDK que aplica cambios bajo exclusión mutua y permite comprobar abortos sin infraestructura. Su lock solo existe en tests; no es el mecanismo de concurrencia del backend.
- `DynamoIntegrationTest` ejecuta las expresiones y transacciones sobre DynamoDB Local real. Las tablas de prueba son aisladas y se eliminan.
- Cobertura alta no demuestra capacidad de producción. La prueba k6 incluida requiere ejecución sobre una infraestructura dimensionada y evaluación de p95/p99 de aceptación y finalización.

## Preguntas difíciles y respuestas honestas

**¿Maneja miles de compradores?** La API es no bloqueante y la recepción está desacoplada del procesamiento. No se ha medido esa capacidad. El inventario por evento es una clave caliente y puede saturar o generar retries. Hace falta prueba de carga y control de admisión antes de afirmar una cifra.

**¿Cómo escalar el inventario?** Particionar cupos por evento/sección/bloque y asignar presupuesto a cada partición. La suma de presupuestos debe conservar la capacidad total. No repartir contadores arbitrariamente ni sumar lecturas eventualmente consistentes para autorizar una venta.

**¿Por qué no bloquear con Redis?** Un lock distribuido introduce vencimiento, renovación, fencing y más infraestructura. Una condición en la base mantiene la invariante donde está el dato. Redis puede ser útil para una sala de espera o caché, pero la caché no debe autorizar una venta.

**¿Y si el pago llega tarde?** No hay pasarela en esta entrega. En producción hay que definir autorización/captura, callback autenticado e idempotente y compensación de un pago recibido después del vencimiento. Nunca cambiar SOLD a AVAILABLE para representar un reembolso; registrar el movimiento contable separado.

**¿Es segura para exponer públicamente?** No todavía. Faltan autenticación del cliente y autorización por propietario de orden. El `customerId` del body no es una identidad verificada; el secreto de cortesías es únicamente un mecanismo de desarrollo. Añadir OIDC/JWT, roles, ownership, alcance de claves de idempotencia por principal, TLS, secretos e IAM mínimo.

**¿Qué se monitorea?** Edad y volumen del outbox, antigüedad SQS, mensajes DLQ, tasa de conflictos/retries, errores permanentes, backlog de vencimientos, latencia hasta RESERVED y SOLD, y reconciliación del inventario. Registrar identificadores de orden y evento sin datos de pago o credenciales.

**¿Hay asientos numerados?** No. Es admisión general. Para butacas se necesita identificar cada asiento y condicionarlo en la misma frontera transaccional, con límites de tamaño de transacción y cantidad por orden.

## Evolución antes de producción

1. Identidad, autorización y flujo real de pagos.
2. Métricas, trazas, alarmas y procedimiento de redrive de DLQ.
3. Límite de admisión y protección frente a abuso.
4. Prueba de carga sostenida y de fallos con SQS real; dimensionamiento de consumidores y visibility timeout.
5. Sharding de inventario e índices según resultados; no por intuición.
6. Infraestructura como código, backups, recuperación y reconciliación periódica.
