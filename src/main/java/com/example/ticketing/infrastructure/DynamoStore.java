package com.example.ticketing.infrastructure;

import com.example.ticketing.application.TicketStore;
import com.example.ticketing.domain.Model.*;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.*;

@Repository
public class DynamoStore implements TicketStore {
  private final DynamoDbAsyncClient client;
  private final String table;
  private final Clock clock;

  public DynamoStore(
      DynamoDbAsyncClient client, @Value("${database.table}") String table, Clock clock) {
    this.client = client;
    this.table = table;
    this.clock = clock;
  }

  public Mono<Event> create(Event event) {
    return Mono.fromFuture(
            () ->
                client.putItem(
                    PutItemRequest.builder()
                        .tableName(table)
                        .item(eventItem(event))
                        .conditionExpression("attribute_not_exists(pk)")
                        .build()))
        .thenReturn(event)
        .onErrorMap(this::translate);
  }

  public Flux<Event> events() {
    return query("EVENT", null).map(this::readEvent);
  }

  public Mono<Event> event(String id) {
    return get("EVENT#" + id).map(this::readEvent);
  }

  public Mono<Order> order(String id) {
    return get("ORDER#" + id).map(this::readOrder);
  }

  private Mono<Map<String, AttributeValue>> get(String key) {
    return Mono.fromFuture(
            () ->
                client.getItem(
                    GetItemRequest.builder()
                        .tableName(table)
                        .key(Map.of("pk", text(key)))
                        .consistentRead(true)
                        .build()))
        .flatMap(
            response ->
                response.hasItem()
                    ? Mono.just(response.item())
                    : Mono.error(new Missing("Resource does not exist")))
        .onErrorMap(this::translate);
  }

  public Mono<Order> submit(Order order) {
    return Mono.defer(
            () ->
                write(
                        List.of(
                            put(orderItem(order), "attribute_not_exists(pk)", Map.of(), Map.of()),
                            TransactWriteItem.builder()
                                .conditionCheck(
                                    ConditionCheck.builder()
                                        .tableName(table)
                                        .key(Map.of("pk", text("EVENT#" + order.eventId())))
                                        .conditionExpression("attribute_exists(pk)")
                                        .build())
                                .build(),
                            auditItem(order.id(), "NONE", "QUEUED"),
                            outboxItem(order.id())))
                    .thenReturn(order))
        .onErrorResume(
            ConcurrentChange.class,
            error ->
                order(order.id())
                    .flatMap(
                        existing ->
                            sameRequest(existing, order)
                                ? Mono.just(existing)
                                : Mono.error(
                                    new Conflict(
                                        "Idempotency key was already used with a different"
                                            + " request")))
                    .onErrorResume(Missing.class, missing -> Mono.error(error)));
  }

  private boolean sameRequest(Order existing, Order requested) {
    return existing.eventId().equals(requested.eventId())
        && existing.customerId().equals(requested.customerId())
        && existing.quantity() == requested.quantity()
        && existing.courtesy() == requested.courtesy();
  }

  public Mono<Order> transition(
      Order expected,
      OrderStatus status,
      Instant expiresAt,
      TicketState source,
      TicketState target,
      boolean enqueue) {
    return Mono.defer(
        () -> {
          Order updated =
              new Order(
                  expected.id(),
                  expected.eventId(),
                  expected.customerId(),
                  expected.quantity(),
                  status,
                  expiresAt,
                  expected.createdAt(),
                  expected.courtesy());
          List<TransactWriteItem> changes = new ArrayList<>();
          changes.add(
              put(
                  orderItem(updated),
                  "#status = :expected",
                  Map.of("#status", "status"),
                  Map.of(":expected", text(expected.status().name()))));
          if (source != null) {
            changes.add(
                TransactWriteItem.builder()
                    .update(
                        Update.builder()
                            .tableName(table)
                            .key(Map.of("pk", text("EVENT#" + expected.eventId())))
                            .conditionExpression("#source >= :quantity")
                            .updateExpression(
                                "SET #source = #source - :quantity, #target = #target + :quantity")
                            .expressionAttributeNames(
                                Map.of("#source", bucket(source), "#target", bucket(target)))
                            .expressionAttributeValues(
                                Map.of(":quantity", number(expected.quantity())))
                            .build())
                    .build());
          }
          changes.add(auditItem(expected.id(), expected.status().name(), status.name()));
          if (enqueue) changes.add(outboxItem(expected.id()));
          return write(changes).thenReturn(updated);
        });
  }

  public Flux<Order> expired(Instant cutoff) {
    return query("ACTIVE", sortable(cutoff) + "~").map(this::readOrder);
  }

  public Flux<Outbox> outbox() {
    return query("OUTBOX", null)
        .take(100)
        .map(item -> new Outbox(string(item, "id"), string(item, "orderId")));
  }

  public Mono<Void> delivered(String id) {
    return Mono.fromFuture(
            () ->
                client.deleteItem(
                    DeleteItemRequest.builder()
                        .tableName(table)
                        .key(Map.of("pk", text("OUTBOX#" + id)))
                        .build()))
        .then()
        .onErrorMap(this::translate);
  }

  public Flux<Audit> audit(String id) {
    return query("AUDIT#" + id, null)
        .map(
            item ->
                new Audit(
                    string(item, "id"),
                    id,
                    string(item, "fromState"),
                    string(item, "toState"),
                    Instant.parse(string(item, "at"))));
  }

  private Flux<Map<String, AttributeValue>> query(String kind, String upperBound) {
    QueryRequest.Builder request =
        QueryRequest.builder()
            .tableName(table)
            .indexName("kind-sort-index")
            .keyConditionExpression(
                "#kind = :kind" + (upperBound == null ? "" : " AND #sort <= :cutoff"))
            .expressionAttributeNames(
                upperBound == null
                    ? Map.of("#kind", "kind")
                    : Map.of("#kind", "kind", "#sort", "sort"))
            .expressionAttributeValues(
                upperBound == null
                    ? Map.of(":kind", text(kind))
                    : Map.of(":kind", text(kind), ":cutoff", text(upperBound)))
            .limit(100);
    return Flux.defer(() -> client.queryPaginator(request.build()))
        .concatMapIterable(QueryResponse::items)
        .onErrorMap(this::translate);
  }

  private Mono<Void> write(List<TransactWriteItem> changes) {
    return Mono.fromFuture(
            () ->
                client.transactWriteItems(
                    TransactWriteItemsRequest.builder()
                        .transactItems(changes)
                        .clientRequestToken(UUID.randomUUID().toString())
                        .build()))
        .then()
        .onErrorMap(this::translate);
  }

  private Throwable translate(Throwable error) {
    if (error instanceof TransactionCanceledException
        || error instanceof ConditionalCheckFailedException) return new ConcurrentChange(error);
    if (error instanceof SdkClientException
        || error instanceof ProvisionedThroughputExceededException
        || error instanceof DynamoDbException failure
            && (failure.isThrottlingException() || failure.statusCode() >= 500))
      return new TransientStoreFailure(error);
    return error;
  }

  private TransactWriteItem put(
      Map<String, AttributeValue> item,
      String condition,
      Map<String, String> names,
      Map<String, AttributeValue> values) {
    Put.Builder request = Put.builder().tableName(table).item(item).conditionExpression(condition);
    if (!names.isEmpty()) request.expressionAttributeNames(names);
    if (!values.isEmpty()) request.expressionAttributeValues(values);
    return TransactWriteItem.builder().put(request.build()).build();
  }

  private TransactWriteItem auditItem(String orderId, String from, String to) {
    String id = UUID.randomUUID().toString();
    Map<String, AttributeValue> item =
        base("AUDIT#" + id, "AUDIT#" + orderId, sortable(clock.instant()) + id);
    item.putAll(
        Map.of(
            "id",
            text(id),
            "orderId",
            text(orderId),
            "fromState",
            text(from),
            "toState",
            text(to),
            "at",
            text(clock.instant().toString())));
    return put(item, "attribute_not_exists(pk)", Map.of(), Map.of());
  }

  private TransactWriteItem outboxItem(String orderId) {
    String id = UUID.randomUUID().toString();
    Map<String, AttributeValue> item = base("OUTBOX#" + id, "OUTBOX", id);
    item.putAll(Map.of("id", text(id), "orderId", text(orderId)));
    return put(item, "attribute_not_exists(pk)", Map.of(), Map.of());
  }

  private Map<String, AttributeValue> eventItem(Event event) {
    Map<String, AttributeValue> item = base("EVENT#" + event.id(), "EVENT", event.id());
    item.putAll(
        Map.of(
            "id",
            text(event.id()),
            "name",
            text(event.name()),
            "date",
            text(event.date().toString()),
            "venue",
            text(event.venue()),
            "capacity",
            number(event.capacity()),
            "available",
            number(event.available()),
            "reserved",
            number(event.reserved()),
            "pending",
            number(event.pending()),
            "sold",
            number(event.sold()),
            "complimentary",
            number(event.complimentary())));
    return item;
  }

  private Map<String, AttributeValue> orderItem(Order order) {
    Map<String, AttributeValue> item =
        base(
            "ORDER#" + order.id(),
            order.expiresAt() == null ? "ORDER" : "ACTIVE",
            order.expiresAt() == null ? order.id() : sortable(order.expiresAt()) + order.id());
    item.putAll(
        Map.of(
            "id",
            text(order.id()),
            "eventId",
            text(order.eventId()),
            "customerId",
            text(order.customerId()),
            "quantity",
            number(order.quantity()),
            "status",
            text(order.status().name()),
            "createdAt",
            text(order.createdAt().toString()),
            "courtesy",
            AttributeValue.builder().bool(order.courtesy()).build()));
    if (order.expiresAt() != null) item.put("expiresAt", text(order.expiresAt().toString()));
    return item;
  }

  private Event readEvent(Map<String, AttributeValue> item) {
    return new Event(
        string(item, "id"),
        string(item, "name"),
        Instant.parse(string(item, "date")),
        string(item, "venue"),
        integer(item, "capacity"),
        integer(item, "available"),
        integer(item, "reserved"),
        integer(item, "pending"),
        integer(item, "sold"),
        integer(item, "complimentary"));
  }

  private Order readOrder(Map<String, AttributeValue> item) {
    return new Order(
        string(item, "id"),
        string(item, "eventId"),
        string(item, "customerId"),
        integer(item, "quantity"),
        OrderStatus.valueOf(string(item, "status")),
        item.containsKey("expiresAt") ? Instant.parse(string(item, "expiresAt")) : null,
        Instant.parse(string(item, "createdAt")),
        item.get("courtesy").bool());
  }

  private Map<String, AttributeValue> base(String key, String kind, String sort) {
    return new HashMap<>(Map.of("pk", text(key), "kind", text(kind), "sort", text(sort)));
  }

  private static AttributeValue text(String value) {
    return AttributeValue.builder().s(value).build();
  }

  private static AttributeValue number(int value) {
    return AttributeValue.builder().n(Integer.toString(value)).build();
  }

  private static String string(Map<String, AttributeValue> item, String name) {
    return item.get(name).s();
  }

  private static int integer(Map<String, AttributeValue> item, String name) {
    return Integer.parseInt(item.get(name).n());
  }

  private static String sortable(Instant value) {
    return String.format(java.util.Locale.ROOT, "%020d#", value.toEpochMilli());
  }

  private static String bucket(TicketState state) {
    return switch (state) {
      case AVAILABLE -> "available";
      case RESERVED -> "reserved";
      case PENDING_CONFIRMATION -> "pending";
      case SOLD -> "sold";
      case COMPLIMENTARY -> "complimentary";
    };
  }
}
