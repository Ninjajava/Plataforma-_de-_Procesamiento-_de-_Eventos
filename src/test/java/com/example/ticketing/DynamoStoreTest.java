package com.example.ticketing;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.example.ticketing.application.TicketService;
import com.example.ticketing.domain.Model.*;
import com.example.ticketing.domain.Model.Order;
import com.example.ticketing.infrastructure.DynamoStore;
import java.time.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.*;
import reactor.core.publisher.*;
import reactor.test.StepVerifier;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.dynamodb.model.*;

class DynamoStoreTest {
  private MemoryDynamo memory;
  private MutableClock clock;
  private DynamoStore store;
  private TicketService service;

  @BeforeEach
  void setup() {
    memory = new MemoryDynamo();
    clock = new MutableClock();
    store = new DynamoStore(memory.client, "ticketing", clock);
    service = new TicketService(store, clock);
  }

  private Event create(int capacity) {
    return service.create("Concert", clock.instant().plusSeconds(86400), "Venue", capacity).block();
  }

  private Order buy(Event event, String id, int quantity, boolean courtesy) {
    return service.buy(id, event.id(), "customer", quantity, courtesy).block();
  }

  private void invariant(Event event) {
    assertEquals(
        event.capacity(),
        event.available()
            + event.reserved()
            + event.pending()
            + event.sold()
            + event.complimentary());
    assertTrue(event.available() >= 0);
    assertTrue(event.reserved() >= 0);
    assertTrue(event.pending() >= 0);
  }

  @Test
  void fullLifecyclePersistsAuditsInventoryAndOutbox() {
    Event event = create(5);
    Order order = buy(event, "order-001", 2, false);
    StepVerifier.create(store.events()).expectNext(event).verifyComplete();
    service.reserve(order.id()).block();
    assertEquals(OrderStatus.RESERVED, store.order(order.id()).block().status());
    service.confirm(order.id()).block();
    service.reserve(order.id()).block();
    service.reserve(order.id()).block();
    assertEquals(OrderStatus.SOLD, service.confirm(order.id()).block().status());
    Event current = store.event(event.id()).block();
    invariant(current);
    assertEquals(2, current.sold());
    assertEquals(3, current.available());
    List<Audit> history = store.audit(order.id()).collectList().block();
    assertEquals(4, history.size());
    assertTrue(
        history.stream()
            .anyMatch(
                entry -> entry.from().equals("PENDING_CONFIRMATION") && entry.to().equals("SOLD")));
    List<Outbox> messages = store.outbox().collectList().block();
    assertEquals(2, messages.size());
    Flux.fromIterable(messages).concatMap(message -> store.delivered(message.id())).then().block();
    StepVerifier.create(store.outbox()).verifyComplete();
    service.expire().block();
    assertEquals(OrderStatus.SOLD, store.order(order.id()).block().status());
  }

  @Test
  void expirationReturnsBothKindsOfHoldsExactlyOnceAndCourtesyIsNotRevenue() {
    Event event = create(5);
    Order reserved = buy(event, "reserved-001", 2, false);
    Order pending = buy(event, "pending-001", 2, false);
    service.reserve(reserved.id()).block();
    service.reserve(pending.id()).block();
    service.confirm(pending.id()).block();
    clock.advance(Duration.ofSeconds(600));
    Mono.when(service.expire(), service.expire(), service.reserve(pending.id()))
        .block(Duration.ofSeconds(30));
    assertEquals(OrderStatus.EXPIRED, store.order(reserved.id()).block().status());
    assertEquals(OrderStatus.EXPIRED, store.order(pending.id()).block().status());
    Order gift = buy(event, "gift-001", 1, true);
    service.reserve(gift.id()).block();
    service.reserve(gift.id()).block();
    Event current = store.event(event.id()).block();
    invariant(current);
    assertEquals(4, current.available());
    assertEquals(1, current.complimentary());
    assertEquals(0, current.sold());
  }

  @Test
  void parallelOrdersAndDuplicateDeliveriesCannotOversell() {
    Event event = create(20);
    List<Order> orders =
        Flux.range(0, 100)
            .concatMap(
                index -> service.buy("concurrent-" + index, event.id(), "customer", 1, false))
            .collectList()
            .block();
    Flux.fromIterable(orders)
        .flatMap(order -> service.reserve(order.id()), 16)
        .then()
        .block(Duration.ofSeconds(60));
    Flux.fromIterable(orders)
        .flatMap(order -> service.reserve(order.id()), 16)
        .then()
        .block(Duration.ofSeconds(60));
    Event current = store.event(event.id()).block();
    invariant(current);
    assertEquals(20, current.reserved());
    assertEquals(0, current.available());
    assertEquals(
        20L,
        Flux.fromIterable(orders)
            .concatMap(order -> store.order(order.id()))
            .filter(order -> order.status() == OrderStatus.RESERVED)
            .count()
            .block());
  }

  @Test
  void idempotencyValidatesEveryRequestFieldAndPersistsOnlyOneMessage() {
    Event event = create(5);
    Order original = buy(event, "same-key", 1, false);
    assertEquals(original, buy(event, "same-key", 1, false));
    assertThrows(Conflict.class, () -> buy(event, "same-key", 2, false));
    assertThrows(Conflict.class, () -> buy(event, "same-key", 1, true));
    assertThrows(
        Conflict.class,
        () -> service.buy("same-key", event.id(), "other-customer", 1, false).block());
    Event other = create(5);
    assertThrows(Conflict.class, () -> buy(other, "same-key", 1, false));
    assertEquals(1L, store.outbox().count().block());
  }

  @Test
  void failedConditionalTransactionRollsBackOrderInventoryAndAudit() {
    Event event = create(1);
    Order order = buy(event, "oversized", 2, false);
    StepVerifier.create(
            store.transition(
                order,
                OrderStatus.RESERVED,
                clock.instant().plusSeconds(600),
                TicketState.AVAILABLE,
                TicketState.RESERVED,
                false))
        .expectError(ConcurrentChange.class)
        .verify();
    assertEquals(OrderStatus.QUEUED, store.order(order.id()).block().status());
    assertEquals(1, store.event(event.id()).block().available());
    assertEquals(1L, store.audit(order.id()).count().block());
    service.reserve(order.id()).block();
    assertEquals(OrderStatus.REJECTED, store.order(order.id()).block().status());
  }

  @Test
  void missingResourcesAndConditionalCreateFailuresAreMapped() {
    StepVerifier.create(store.order("missing")).expectError(Missing.class).verify();
    StepVerifier.create(store.event("missing")).expectError(Missing.class).verify();
    Event event = create(1);
    StepVerifier.create(store.create(event)).expectError(ConcurrentChange.class).verify();
    Order orphan =
        new Order(
            "orphan", "missing", "customer", 1, OrderStatus.QUEUED, null, clock.instant(), false);
    StepVerifier.create(store.submit(orphan)).expectError(ConcurrentChange.class).verify();
  }

  @Test
  void transientAndPermanentSdkFailuresAreDistinguished() {
    Event event = new Event("event", "Concert", clock.instant(), "Venue", 1, 1, 0, 0, 0, 0);
    memory.nextFailure.set(SdkClientException.create("Connection failed"));
    StepVerifier.create(store.create(event)).expectError(TransientStoreFailure.class).verify();
    memory.nextFailure.set(
        DynamoDbException.builder().statusCode(503).message("Unavailable").build());
    StepVerifier.create(store.create(event)).expectError(TransientStoreFailure.class).verify();
    memory.nextFailure.set(
        ProvisionedThroughputExceededException.builder().message("Throttled").build());
    StepVerifier.create(store.create(event)).expectError(TransientStoreFailure.class).verify();
    memory.nextFailure.set(ResourceNotFoundException.builder().message("Missing table").build());
    StepVerifier.create(store.create(event)).expectError(ResourceNotFoundException.class).verify();
    when(memory.client.query(any(QueryRequest.class)))
        .thenReturn(CompletableFuture.failedFuture(SdkClientException.create("Connection failed")));
    StepVerifier.create(store.events()).expectError(TransientStoreFailure.class).verify();
  }
}
