package com.example.ticketing;

import static org.junit.jupiter.api.Assertions.*;

import com.example.ticketing.application.TicketService;
import com.example.ticketing.domain.Model.*;
import com.example.ticketing.domain.Model.Order;
import com.example.ticketing.infrastructure.DynamoStore;
import java.net.URI;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import reactor.core.publisher.*;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.*;

@EnabledIfEnvironmentVariable(named = "TEST_DYNAMODB_ENDPOINT", matches = ".+")
class DynamoIntegrationTest {
  private DynamoDbAsyncClient client;
  private String table;
  private MutableClock clock;
  private DynamoStore store;
  private TicketService service;

  @BeforeEach
  void setup() {
    client =
        DynamoDbAsyncClient.builder()
            .endpointOverride(URI.create(System.getenv("TEST_DYNAMODB_ENDPOINT")))
            .region(Region.US_EAST_1)
            .credentialsProvider(
                StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
            .build();
    table = "ticketing-test-" + UUID.randomUUID();
    clock = new MutableClock();
    client
        .createTable(
            CreateTableRequest.builder()
                .tableName(table)
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .attributeDefinitions(attribute("pk"), attribute("kind"), attribute("sort"))
                .keySchema(key("pk", KeyType.HASH))
                .globalSecondaryIndexes(
                    GlobalSecondaryIndex.builder()
                        .indexName("kind-sort-index")
                        .keySchema(key("kind", KeyType.HASH), key("sort", KeyType.RANGE))
                        .projection(Projection.builder().projectionType(ProjectionType.ALL).build())
                        .build())
                .build())
        .join();
    store = new DynamoStore(client, table, clock);
    service = new TicketService(store, clock);
  }

  private AttributeDefinition attribute(String name) {
    return AttributeDefinition.builder()
        .attributeName(name)
        .attributeType(ScalarAttributeType.S)
        .build();
  }

  private KeySchemaElement key(String name, KeyType type) {
    return KeySchemaElement.builder().attributeName(name).keyType(type).build();
  }

  @AfterEach
  void cleanup() {
    try {
      client.deleteTable(DeleteTableRequest.builder().tableName(table).build()).join();
    } finally {
      client.close();
    }
  }

  @Test
  void realConditionalTransactionsPreventOversellUnderConcurrencyAndRedelivery() {
    Event event =
        service.create("Concert", clock.instant().plusSeconds(86400), "Venue", 20).block();
    List<Order> orders =
        Flux.range(0, 100)
            .concatMap(index -> service.buy("request-" + index, event.id(), "customer", 1, false))
            .collectList()
            .block();
    Flux.fromIterable(orders)
        .flatMap(order -> service.reserve(order.id()), 8)
        .then()
        .block(Duration.ofSeconds(90));
    Flux.fromIterable(orders)
        .flatMap(order -> service.reserve(order.id()), 8)
        .then()
        .block(Duration.ofSeconds(90));
    Event current = store.event(event.id()).block();
    assertEquals(0, current.available());
    assertEquals(20, current.reserved());
    assertEquals(
        20L,
        Flux.fromIterable(orders)
            .concatMap(order -> store.order(order.id()))
            .filter(order -> order.status() == OrderStatus.RESERVED)
            .count()
            .block());
    assertEquals(
        80L,
        Flux.fromIterable(orders)
            .concatMap(order -> store.order(order.id()))
            .filter(order -> order.status() == OrderStatus.REJECTED)
            .count()
            .block());
  }

  @Test
  void realOutboxAuditIdempotencyAndExpirationRemainConsistent() {
    Event event = service.create("Concert", clock.instant().plusSeconds(86400), "Venue", 5).block();
    Order order = service.buy("request-001", event.id(), "customer", 2, false).block();
    assertEquals(order, service.buy(order.id(), event.id(), "customer", 2, false).block());
    assertThrows(
        Conflict.class, () -> service.buy(order.id(), event.id(), "customer", 3, false).block());
    service.reserve(order.id()).block();
    service.confirm(order.id()).block();
    service.reserve(order.id()).block();
    assertEquals(OrderStatus.SOLD, store.order(order.id()).block().status());
    assertEquals(4L, store.audit(order.id()).count().block());
    assertEquals(2L, store.outbox().count().block());
    Order expiring = service.buy("request-002", event.id(), "customer", 2, false).block();
    service.reserve(expiring.id()).block();
    clock.advance(Duration.ofSeconds(600));
    Mono.when(service.expire(), service.expire()).block(Duration.ofSeconds(30));
    Event current = store.event(event.id()).block();
    assertEquals(3, current.available());
    assertEquals(2, current.sold());
    assertEquals(0, current.reserved());
    assertEquals(OrderStatus.EXPIRED, store.order(expiring.id()).block().status());
  }
}
